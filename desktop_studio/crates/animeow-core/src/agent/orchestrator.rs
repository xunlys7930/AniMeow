use crate::agent::llm_client::stream_chat_completion;
use crate::agent::mcp::{
    invoke_mcp_tool, test_and_list_mcp_tools, tool_looks_mutating, wrap_untrusted_mcp_output,
};
use crate::agent::prompt::{
    assemble_history_messages, build_static_system_prompt, build_trailing_runtime_reminder,
    estimate_messages_tokens, estimate_tokens,
};
use crate::agent::providers::{detect_web_search_mode, WebSearchMode};
use crate::agent::registry::{
    build_stable_tool_schemas, canonical_call_signature, tool_meta, ARTIFACT_PREVIEW_CHARS,
    ARTIFACT_SPILL_THRESHOLD_CHARS,
};
use crate::agent::storage::{
    append_assistant_version, create_verify_job_from_items, get_policy_config, get_profile,
    get_session, insert_message, list_mcp_servers, list_memories, list_slash_commands,
    load_library_result, load_mcp_auth_token, load_profile_api_key, load_proposal,
    load_session_messages, load_skill_guide, read_proposal_statuses_paged,
    read_tool_artifact, recall_memories_local_with_tier, record_run_event, save_policy_config,
    save_session_compaction, save_tool_artifact, save_verify_batch_result,
    summarize_active_session_handles, summarize_session_runtime_stats, touch_session_usage,
};
use crate::agent::tools_read::{
    build_library_brief_line, compute_cover_baseline_stats, exec_find_external_id_usage,
    exec_get_discovery_feed, exec_get_library_overview, exec_get_work_detail_with_dir,
    exec_list_taxonomy, exec_query_app_knowledge, exec_query_library, exec_run_library_diagnostics,
    exec_search_remote_catalog, filter_library_with_local, library_record_page_with_local,
    run_verify_job_batch,
    scan_nonstandard_release_dates, validate_query_library_args, verify_bangumi_batch,
    verify_bangumi_work, LocalWorkVerifyTarget, BANGUMI_BATCH_MAX_IDS,
};
use crate::agent::tools_ui::{
    build_desktop_settings_update, exec_apply_library_filter, exec_focus_canvas, exec_navigate_ui,
    exec_update_user_memory,
};
use crate::agent::tools_web::{exec_deepseek_fetch_webpage, exec_deepseek_web_search};
use crate::agent::tools_write::{
    cancel_pending_proposal, commit_proposal_as_auto, create_bulk_bind_proposal,
    create_character_mutations_proposal, create_mcp_call_proposal,
    create_merge_duplicates_proposal, create_migrate_covers_to_remote_proposal,
    create_rehydrate_metadata_proposal, create_series_mutations_proposal,
    create_tag_mutations_proposal, create_work_mutations_proposal,
    enrich_create_args_from_bangumi_with_policy, recover_proposal_writes,
    should_auto_approve_work_proposal,
};
use crate::agent::types::{
    AgentMessage, AgentStreamEnvelope, AgentStreamEvent, DiagnosticError, MemoryChangeEvent,
    MessageVersion, SendAgentMessageInput, SettingsUndoRecord, TokenUsage, ToolTraceItem,
    UiActionCommand, WebSourceCitation,
};
use chrono::Utc;
use rusqlite::Connection;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

const TURN_TOTAL_TIMEOUT_SECS: u64 = 180;
const CONSECUTIVE_ERROR_CAP: u32 = 3;
const MAX_PROPOSALS_PER_TURN: usize = 6;

fn classify_error_code(msg: &str) -> &'static str {
    if msg.contains("TITLE_MISMATCH") {
        "TITLE_MISMATCH"
    } else if msg.contains("已绑定到在库作品") || msg.contains("已绑定其他作品") || msg.contains("重复绑定") {
        "CONFLICT_EXTERNAL_ID"
    } else if msg.contains("已有外部绑定") {
        "ALREADY_BOUND"
    } else if msg.contains("找不到") || msg.contains("不存在") || msg.contains("未找到") {
        "NOT_FOUND_LOCAL"
    } else if msg.contains("已锁定核实对象") {
        "LOCKED_BY_TARGET"
    } else if msg.contains("变更方案数量上限") || msg.contains("已生成一份变更方案") {
        "PROPOSAL_LIMIT_REACHED"
    } else if msg.contains("read_library_page") {
        "WRONG_TOOL"
    } else if msg.contains("429") || msg.contains("限流") {
        "RATE_LIMITED"
    } else if msg.contains("已过期") || msg.contains("已变化") {
        "STALE_REVISION"
    } else if msg.contains("未授权") || msg.contains("权限") {
        "PERMISSION_DENIED"
    } else {
        "VALIDATION_ERROR"
    }
}

fn is_non_circuit_guard_error(raw_output: &str) -> bool {
    let Ok(v) = serde_json::from_str::<Value>(raw_output) else {
        return false;
    };
    matches!(
        v.get("code").and_then(Value::as_str),
        Some(
            "LOCKED_BY_TARGET"
                | "DUPLICATE_CALL"
                | "PROPOSAL_LIMIT_REACHED"
                | "WRONG_TOOL"
                | "TITLE_MISMATCH"
                | "VALIDATION_ERROR"
                | "NOT_FOUND_LOCAL"
                | "CONFLICT_EXTERNAL_ID"
                | "ALREADY_BOUND"
        )
    )
}

fn tool_error_json(code: &str, message: impl Into<String>) -> String {
    json!({ "error": message.into(), "code": code }).to_string()
}

fn push_unique_proposal(list: &mut Vec<String>, id: String) {
    if !list.iter().any(|existing| existing == &id) {
        list.push(id);
    }
}

#[derive(Clone)]
pub struct AgentRuntimeHandles {
    pub studio_data_dir: PathBuf,
    pub sync_db: Arc<Mutex<Connection>>,
    pub local_db: Arc<Mutex<Connection>>,
}

/// 执行一轮完整的追番喵桌面端确定性智能体循环：
/// - 严格 Prefix-Cache 纪律（静态系统前缀 + 固定工具表 + Append-Only 历史与每轮运行态快照）
/// - 大工具结果一次性溢写落盘为 Artifact，支持 `read_tool_artifact` 分页回读
/// - 支持无损重新生成（追加为 assistant 消息的新微版本，保留旧版本可切换）
/// - 5 道防死循环与预算熔断闸门
/// - 短锁原则：绝不在持有任何 DB Mutex 时 await
pub async fn run_agent_turn<FEmit>(
    handles: AgentRuntimeHandles,
    run_id: String,
    input: SendAgentMessageInput,
    cancel: CancellationToken,
    emit: FEmit,
) where
    FEmit: Fn(AgentStreamEnvelope) + Send + Sync + 'static,
{
    let send_ev = |event: AgentStreamEvent| {
        emit(AgentStreamEnvelope {
            run_id: run_id.clone(),
            session_id: input.session_id.clone(),
            event,
        });
    };

    let regenerate_id = input
        .regenerate_message_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);

    // 1. 短拿锁：读取 Profile、Policy、Memories、Skills、MCP 状态、会话历史与藏品库概况
    let prep = (|| -> std::result::Result<_, DiagnosticError> {
        let sync = handles.sync_db.lock().map_err(|_| DiagnosticError {
            code: "LOCK_ERROR".into(),
            title: "藏品库暂不可用".into(),
            explanation: "读取藏品库时发生错误。".into(),
            suggestion: "请稍后重试。".into(),
            quick_fix: None,
            suggested_base_url: None,
            technical_detail: "sync_db mutex poisoned".into(),
        })?;
        let local = handles.local_db.lock().map_err(|_| DiagnosticError {
            code: "LOCK_ERROR".into(),
            title: "本地状态锁异常".into(),
            explanation: "读取桌面端本地数据库时锁状态异常。".into(),
            suggestion: "请重启追番喵桌面端后重试。".into(),
            quick_fix: None,
            suggested_base_url: None,
            technical_detail: "local_db mutex poisoned".into(),
        })?;

        let profile = get_profile(&local, &handles.studio_data_dir, input.profile_id.as_deref())
            .map_err(|e| DiagnosticError {
                code: "STORAGE_ERROR".into(),
                title: "读取模型档案失败".into(),
                explanation: e.to_string(),
                suggestion: "请打开右上角配置检查模型设置。".into(),
                quick_fix: Some("open_config".into()),
                suggested_base_url: None,
                technical_detail: e.to_string(),
            })?
            .ok_or_else(|| DiagnosticError {
                code: "NO_PROFILE".into(),
                title: "尚未配置大模型接入".into(),
                explanation: "开始对话前，请先选一个预设服务商并填入你的 API Key（或选择本地 Ollama 免 Key）。".into(),
                suggestion: "点击下方按钮打开模型接入配置，30 秒即可完成。".into(),
                quick_fix: Some("open_config".into()),
                suggested_base_url: None,
                technical_detail: "No agent_profiles found".into(),
            })?;

        let api_key = load_profile_api_key(&handles.studio_data_dir, &profile.id).map_err(|e| {
            DiagnosticError {
                code: "DPAPI_ERROR".into(),
                title: "读取本地加密 API Key 失败".into(),
                explanation: e.to_string(),
                suggestion: "请在模型配置中重新输入并保存 API Key。".into(),
                quick_fix: Some("open_config".into()),
                suggested_base_url: None,
                technical_detail: e.to_string(),
            }
        })?;

        let policy = get_policy_config(&local).unwrap_or_default();
        let memories = if policy.enabled_groups.memory {
            list_memories(&local).unwrap_or_default()
        } else {
            Vec::new()
        };
        let slash_commands = list_slash_commands(&local).unwrap_or_default();
        let has_enabled_mcp = list_mcp_servers(&local, &handles.studio_data_dir)
            .map(|list| list.iter().any(|s| s.enabled))
            .unwrap_or(false);

        let library_brief = if policy.enabled_groups.read {
            build_library_brief_line(&sync, &local)
        } else {
            "已关闭读取藏品库".into()
        };
        let context = if policy.enabled_groups.read { input.context_chip.as_ref() } else { None };
        let trailing_reminder = build_trailing_runtime_reminder(&policy, context, &library_brief);

        let (user_msg, history, regen_prior_note) = if let Some(ref regen_mid) = regenerate_id {
            // 无损重新生成：不插入新 user 消息，截取该 assistant 消息之前的历史上下文重跑
            let all_msgs = load_session_messages(&local, &input.session_id).unwrap_or_default();
            let cutoff = all_msgs
                .iter()
                .position(|m| m.id == *regen_mid)
                .ok_or_else(|| DiagnosticError {
                    code: "MSG_NOT_FOUND".into(),
                    title: "未找到要重新生成的消息".into(),
                    explanation: "目标回复消息不存在或已被清空。".into(),
                    suggestion: "请刷新对话后重试。".into(),
                    quick_fix: None,
                    suggested_base_url: None,
                    technical_detail: format!("regenerate_message_id={regen_mid} not found"),
                })?;
            let history_before = all_msgs[..cutoff].to_vec();
            let orig_target_msg = &all_msgs[cutoff];
            let mut prior_applied_notes = Vec::<String>::new();
            let mut seen_prior_pids = HashSet::<String>::new();
            let candidate_pids = orig_target_msg
                .proposal_ids
                .iter()
                .chain(orig_target_msg.proposal_id.iter())
                .chain(orig_target_msg.versions.iter().flat_map(|v| {
                    v.proposal_ids.iter().chain(v.proposal_id.iter())
                }));
            for pid in candidate_pids {
                if !seen_prior_pids.insert(pid.clone()) {
                    continue;
                }
                if let Ok(Some((prop, _))) = load_proposal(&local, pid) {
                    if crate::agent::types::is_proposal_applied_status(&prop.status) {
                        prior_applied_notes.push(format!(
                            "提案 {}「{}」已执行（{} 项，可撤销）",
                            prop.id,
                            prop.title,
                            prop.applied_count.unwrap_or(prop.diffs.len())
                        ));
                    }
                }
            }
            let last_user = history_before
                .iter()
                .rev()
                .find(|m| m.role == "user")
                .cloned()
                .ok_or_else(|| DiagnosticError {
                    code: "NO_USER_MSG".into(),
                    title: "未找到对应的提问消息".into(),
                    explanation: "无法在目标回复之前找到用户提问。".into(),
                    suggestion: "请直接重新输入问题发送。".into(),
                    quick_fix: None,
                    suggested_base_url: None,
                    technical_detail: "no user message before regenerate target".into(),
                })?;
            let regen_note = if prior_applied_notes.is_empty() {
                None
            } else {
                Some(format!(
                    "[重新生成说明] 原回复关联的写库操作已生效：{}。请直接基于已保存结果重新整理答复，不要重复追加相同进度或再次修改。",
                    prior_applied_notes.join("；")
                ))
            };
            (last_user, history_before, regen_note)
        } else {
            // 正常发送：解析可选 `/` 快捷技能指令并追加写入新 user 消息（附带本轮运行态上下文快照以保住跨轮 KV 缓存）
            let mut expanded_prompt = input.user_message.trim().to_string();
            if let Some(ref cmd_name) = input.slash_command {
                if let Some(sc) = slash_commands
                    .iter()
                    .find(|c| c.command.eq_ignore_ascii_case(cmd_name.trim()))
                {
                    if expanded_prompt.is_empty() || expanded_prompt == sc.command {
                        expanded_prompt = sc.prompt_template.clone();
                    } else {
                        expanded_prompt =
                            format!("{}\n\n【补充说明】{}", sc.prompt_template, expanded_prompt);
                    }
                }
            }

            let user_msg = AgentMessage {
                id: Uuid::new_v4().to_string(),
                session_id: input.session_id.clone(),
                role: "user".into(),
                content: expanded_prompt,
                reasoning_content: None,
                tool_calls: None,
                tool_call_id: None,
                tool_name: None,
                tool_traces: vec![],
                ui_actions: vec![],
                settings_undo: None,
                memory_events: vec![],
                proposal_id: None,
                proposal_ids: vec![],
                diagnostic_error: None,
                token_usage: None,
                versions: vec![],
                active_version_idx: 0,
                runtime_context: Some(trailing_reminder.clone()),
                stopped: false,
                web_sources: vec![],
                created_at: Utc::now().to_rfc3339(),
            };
            let _ = insert_message(&local, &user_msg);
            let _ = touch_session_usage(&local, &input.session_id, Some(&input.user_message), 0, 0);
            let history = load_session_messages(&local, &input.session_id).unwrap_or_default();
            (user_msg, history, None)
        };

        let session_meta = get_session(&local, &input.session_id).ok().flatten();

        Ok((
            profile,
            api_key,
            policy,
            memories,
            slash_commands,
            has_enabled_mcp,
            session_meta,
            user_msg,
            history,
            trailing_reminder,
            regen_prior_note,
        ))
    })();

    let (
        profile,
        api_key,
        policy,
        memories,
        slash_commands,
        has_enabled_mcp,
        session_meta,
        user_msg,
        history,
        trailing_reminder,
        regen_prior_note,
    ) = match prep {
        Ok(p) => p,
        Err(diag) => {
            send_ev(AgentStreamEvent::DiagnosticError {
                error: diag,
                partial_message: None,
            });
            return;
        }
    };

    let key_for_report = api_key.as_deref().filter(|key| key.len() >= 8);
    let encoded_key = key_for_report.map(|key| serde_json::to_string(key).unwrap_or_default());
    let log_traj = |step: u32, event_type: &str, title: &str, status: &str, duration_ms: u64, payload: Value| {
        if let Ok(local) = handles.local_db.lock() {
            let title = key_for_report.map_or_else(|| title.to_string(), |key| title.replace(key, "[REDACTED]"));
            let payload = if let Some(key) = encoded_key.as_deref().and_then(|key| key.strip_prefix('"')?.strip_suffix('"')) {
                serde_json::from_str(&payload.to_string().replace(key, "[REDACTED]")).unwrap_or(Value::Null)
            } else {
                payload
            };
            let _ = record_run_event(&local, &run_id, &input.session_id, step, event_type, &title, status, duration_ms, &payload);
        }
    };

    let mut active_groups_vec = Vec::<String>::new();
    if policy.enabled_groups.read {
        active_groups_vec.push("read".into());
    }
    if policy.enabled_groups.online {
        active_groups_vec.push("online".into());
    }
    if policy.enabled_groups.ui {
        active_groups_vec.push("ui".into());
    }
    if policy.enabled_groups.write {
        active_groups_vec.push("write".into());
    }
    if policy.enabled_groups.memory {
        active_groups_vec.push("memory".into());
    }
    if policy.enabled_groups.mcp && has_enabled_mcp {
        active_groups_vec.push("mcp".into());
    }

    if regenerate_id.is_none() {
        send_ev(AgentStreamEvent::Started {
            user_message: user_msg,
            active_groups: active_groups_vec.clone(),
        });
    }

    let session_persona = session_meta.as_ref().map(|s| s.persona_id.as_str());
    let static_system_prompt = build_static_system_prompt(
        &policy,
        session_persona,
        &memories,
        &slash_commands,
        profile.supports_tools,
    );

    let effective_web_mode = if profile.enable_web_search && policy.enabled_groups.online {
        detect_web_search_mode(&profile.provider_id, &profile.base_url)
    } else {
        WebSearchMode::None
    };
    let enable_deepseek_web_tools = effective_web_mode == WebSearchMode::DeepSeekTools
        && api_key
            .as_deref()
            .map(|k| !k.trim().is_empty())
            .unwrap_or(false);
    let stable_tools_schema = if profile.supports_tools {
        build_stable_tool_schemas(
            &policy.enabled_groups,
            has_enabled_mcp,
            enable_deepseek_web_tools,
        )
    } else {
        vec![]
    };

    let mut rolling_summary = session_meta
        .as_ref()
        .and_then(|s| s.rolling_summary.clone());
    let mut compacted_up_to = session_meta
        .as_ref()
        .and_then(|s| s.compacted_up_to_msg_id.clone());

    let mut messages = assemble_history_messages(
        &static_system_prompt,
        rolling_summary.as_deref(),
        compacted_up_to.as_deref(),
        &history,
        &trailing_reminder,
    );

    // 长对话自动滚动摘要压缩：当上下文超过阈值（默认 32k tokens）时自动归档较早轮次
    let turn_started = Instant::now();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(TURN_TOTAL_TIMEOUT_SECS);
    let estimated_ctx_tokens = estimate_messages_tokens(&messages);
    log_traj(
        0,
        "run_start",
        &format!("启动推理 · {} ({})", profile.name, profile.model),
        "ok",
        0,
        json!({
            "model": profile.model,
            "baseUrl": profile.base_url,
            "persona": session_persona.unwrap_or(&policy.persona_id),
            "supportsTools": profile.supports_tools,
            "enableWebSearch": profile.enable_web_search,
            "webSearchMode": effective_web_mode.as_str(),
            "regenerate": regenerate_id.is_some(),
            "enabledGroups": active_groups_vec,
            "messagesCount": messages.len(),
            "estimatedContextTokens": estimated_ctx_tokens,
            "userMessage": input.user_message.chars().take(300).collect::<String>(),
        }),
    );

    let mut compaction_tokens = 0u64;
    if regenerate_id.is_none() && estimated_ctx_tokens >= policy.auto_compact_tokens as u64 {
        let t_comp = Instant::now();
        let compaction = compact_session_context_with_usage(
            &handles,
            &input.session_id,
            Some(&profile.id),
            policy.max_history_turns.clamp(4, 16) as usize,
        );
        let compacted = tokio::select! {
            biased;
            _ = cancel.cancelled() => None,
            result = tokio::time::timeout_at(deadline, compaction) => result.ok().and_then(|result| result.ok()).flatten(),
        };
        if let Some((new_summary, cutoff_id, compacted_cnt, used_tok)) = compacted {
            compaction_tokens = compaction_tokens.saturating_add(used_tok);
            rolling_summary = Some(new_summary.clone());
            compacted_up_to = Some(cutoff_id);
            log_traj(
                0,
                "compaction",
                &format!("长对话自动整理压缩（归档 {compacted_cnt} 条历史）"),
                "ok",
                t_comp.elapsed().as_millis() as u64,
                json!({
                    "compactedMessages": compacted_cnt,
                    "compactionTokens": used_tok,
                    "triggerTokens": estimated_ctx_tokens,
                    "threshold": policy.auto_compact_tokens,
                    "summaryPreview": new_summary.chars().take(400).collect::<String>(),
                }),
            );
            send_ev(AgentStreamEvent::ContextCompacted {
                summary: new_summary,
                compacted_messages: compacted_cnt,
            });
            messages = assemble_history_messages(
                &static_system_prompt,
                rolling_summary.as_deref(),
                compacted_up_to.as_deref(),
                &history,
                &trailing_reminder,
            );
        }
    }

    // 运行期状态与 5 道熔断闸门计数器
    let max_steps = profile.max_steps.clamp(2, 24);
    let mut tail_notes = Vec::<String>::new();
    if let Some(ref note) = regen_prior_note {
        tail_notes.push(note.clone());
    }
    if policy.enabled_groups.read {
        if let Ok(proposals) = current_proposal_statuses(&handles, &input.session_id, None) {
            // 只精简携带仍待确认的提案 + 最近最多 3 条状态变化（已执行/部分执行/已撤销/失败/过期），避免闲聊反复带 10 份完整 JSON
            let mut compact_props = Vec::<Value>::new();
            let mut changed_included = 0usize;
            for p in &proposals {
                let st = p["status"].as_str().unwrap_or("");
                let failed = p["lastAttemptFailed"].as_bool().unwrap_or(false);
                let keep = if st == "pending" || failed {
                    true
                } else if changed_included < 3 {
                    changed_included += 1;
                    true
                } else {
                    false
                };
                if keep {
                    compact_props.push(json!({
                        "id": p["proposalId"],
                        "title": p["title"],
                        "status": st,
                        "appliedCount": p["appliedCount"],
                        "itemCount": p["itemCount"],
                        "canRevert": p["canRevert"],
                        "failed": failed,
                    }));
                }
            }
            if !compact_props.is_empty() {
                tail_notes.push(format!(
                    "[本机最新提案摘要] {}（详细逐项回执或旧方案请调 get_proposal_status(proposal_id)）。",
                    json!(compact_props)
                ));
            }
        }
    }
    // 合并入最后一条 user 消息尾部，避免连续两条 role="user" 触发部分模型兼容问题
    if !tail_notes.is_empty() {
        let combined = tail_notes.join("\n");
        if let Some(last_user_msg) = messages.iter_mut().rev().find(|m| m["role"] == "user") {
            let prev = last_user_msg["content"].as_str().unwrap_or("").to_string();
            last_user_msg["content"] = json!(format!("{prev}\n\n{combined}"));
        } else {
            messages.push(json!({ "role": "user", "content": combined }));
        }
    }

    let anti_spoiler_active = input
        .context_chip
        .as_ref()
        .map(|c| c.anti_spoiler_enabled)
        .unwrap_or(policy.default_anti_spoiler);
    let tools_schema_est_tokens: u64 = if profile.supports_tools && !stable_tools_schema.is_empty() {
        estimate_tokens(&serde_json::to_string(&stable_tools_schema).unwrap_or_default())
    } else {
        0
    };

    let max_turn_tokens = profile.max_turn_tokens.max(8_000) as u64;

    let mut seen_call_sigs = HashSet::<String>::new();
    let mut tool_fail_streaks = HashMap::<String, u32>::new();
    let mut disabled_tools_this_turn = HashSet::<String>::new();
    let mut force_no_tools_next = false;
    let mut limited = false;
    let mut used_external_data = false;
    let mut catalog_target = None;
    let allowed_tools: HashSet<&str> = stable_tools_schema.iter()
        .filter_map(|schema| schema["function"]["name"].as_str()).collect();
    let mut total_error_streak = 0u32;

    let mut all_traces = Vec::<ToolTraceItem>::new();
    let mut library_reads = std::collections::BTreeMap::<String, (usize, HashSet<usize>)>::new();
    let mut all_ui_actions = Vec::<UiActionCommand>::new();
    let mut last_settings_undo: Option<SettingsUndoRecord> = None;
    let mut all_memory_events = Vec::<MemoryChangeEvent>::new();
    let mut all_web_sources = Vec::<WebSourceCitation>::new();
    let mut created_proposal_ids: Vec<String> = Vec::new();

    let mut final_content = String::new();
    let mut final_reasoning = String::new();
    let mut acc_prompt_tokens = 0u64;
    let mut acc_completion_tokens = 0u64;
    let mut acc_cached_tokens = 0u64;
    let mut reported_steps = 0u32;
    let mut estimated_steps = 0u32;
    let mut request_count = 0u32;
    let mut was_cancelled = false;
    let mut executed_steps = 0u32;

    for step in 1..=max_steps {
        if cancel.is_cancelled() {
            was_cancelled = true;
            break;
        }
        if tokio::time::Instant::now() >= deadline {
            limited = true;
            log_traj(
                step,
                "guard",
                &format!("触发总耗时安全熔断（{TURN_TOTAL_TIMEOUT_SECS}s）"),
                "warn",
                turn_started.elapsed().as_millis() as u64,
                json!({ "reason": format!("turn_timeout_{TURN_TOTAL_TIMEOUT_SECS}s"), "timeoutSecs": TURN_TOTAL_TIMEOUT_SECS }),
            );
            final_content.push_str(&format!(
                "\n\n> [超时保护] 本轮处理已达到安全总耗时上限（{TURN_TOTAL_TIMEOUT_SECS} 秒），已自动收尾。如需继续请直接告诉我。"
            ));
            break;
        }

        executed_steps = step;
        send_ev(AgentStreamEvent::StepStart { step, max_steps });

        // 闸门 ①：收尾步保持稳定 tools 定义与 tool_choice="auto" 不变（保住服务端 KV Prefix-Cache 高命中），
        // 仅靠末尾收尾指令约束直接总结，并在编排层拦截越界工具调用！
        let is_last_step = step == max_steps;
        let disallow_tools_this_step = is_last_step || force_no_tools_next;
        let tools_schema = &stable_tools_schema;

        if disallow_tools_this_step && profile.supports_tools {
            messages.push(json!({
                "role": "user",
                "content": "[系统收尾提示] 工具预算或步数已用尽。请直接回答，说明实际完成的查询范围及未核验项。分别列出已生成的提案和未提交的修改，不把历史回执说成当前状态，不在正文输出工具调用标签。"
            }));
        }

        let tool_choice = if tools_schema.is_empty() {
            None
        } else {
            Some("auto")
        };

        let t_llm = Instant::now();
        // 对收尾步延迟检查前缀，避免模型若意外泄露 DSML 标签时直接闪现到前端气泡
        let mut step_delta_buf = String::new();
        let mut streamed_content = String::new();
        let mut streamed_reasoning = String::new();
        let mut step_delta_flushed = !disallow_tools_this_step;
        let mut need_step_gap = !final_content.is_empty() && !final_content.ends_with('\n');
        let stream = stream_chat_completion(
            &profile.base_url,
            api_key.as_deref(),
            &profile.model,
            profile.temperature,
            &messages,
            if tools_schema.is_empty() {
                None
            } else {
                Some(tools_schema.as_slice())
            },
            tool_choice,
            effective_web_mode,
            &cancel,
            |delta| {
                if step_delta_flushed {
                    streamed_content.push_str(delta);
                    if need_step_gap && !delta.is_empty() {
                        need_step_gap = false;
                        send_ev(AgentStreamEvent::Delta {
                            text: "\n\n".to_string(),
                        });
                    }
                    send_ev(AgentStreamEvent::Delta {
                        text: delta.to_string(),
                    });
                } else {
                    step_delta_buf.push_str(delta);
                    let trimmed = step_delta_buf.trim_start();
                    if contains_raw_tool_markup(trimmed) {
                        // 拦截裸工具标签，不向前端推送
                    } else if trimmed.len() >= 18
                        || (!trimmed.is_empty() && !trimmed.starts_with('<'))
                    {
                        step_delta_flushed = true;
                        streamed_content.push_str(&step_delta_buf);
                        if need_step_gap {
                            need_step_gap = false;
                            send_ev(AgentStreamEvent::Delta {
                                text: "\n\n".to_string(),
                            });
                        }
                        send_ev(AgentStreamEvent::Delta {
                            text: std::mem::take(&mut step_delta_buf),
                        });
                    }
                }
            },
            |reason| {
                streamed_reasoning.push_str(reason);
                send_ev(AgentStreamEvent::ReasoningDelta {
                    text: reason.to_string(),
                });
            },
        );
        let outcome = tokio::time::timeout_at(deadline, stream).await;
        if !matches!(&outcome, Ok(Ok(_))) {
            if !streamed_content.is_empty() {
                if !final_content.is_empty() {
                    final_content.push_str("\n\n");
                }
                final_content.push_str(&streamed_content);
            }
            final_reasoning.push_str(&streamed_reasoning);
        }
        let step_out = match outcome {
            Err(_) => {
                limited = true;
                final_content.push_str(&format!("\n\n> [超时保护] 等待模型达到本轮时限（{TURN_TOTAL_TIMEOUT_SECS} 秒），结果可能不完整。"));
                log_traj(step, "guard", &format!("等待模型达到本轮时限（{TURN_TOTAL_TIMEOUT_SECS}s）"), "warn", t_llm.elapsed().as_millis() as u64,
                    json!({"reason": format!("turn_timeout_{TURN_TOTAL_TIMEOUT_SECS}s"), "timeoutSecs": TURN_TOTAL_TIMEOUT_SECS}));
                break;
            }
            Ok(Ok(out)) => out,
            Ok(Err(diag)) => {
                log_traj(
                    step,
                    "run_error",
                    &format!("模型调用失败：{}", diag.title),
                    "error",
                    turn_started.elapsed().as_millis() as u64,
                    json!({
                        "code": diag.code,
                        "title": diag.title,
                        "explanation": diag.explanation,
                        "technicalDetail": diag.technical_detail,
                    }),
                );
                let partial = persist_or_append_assistant_message(
                    &handles,
                    &input.session_id,
                    regenerate_id.as_deref(),
                    final_content,
                    final_reasoning,
                    all_traces,
                    all_ui_actions,
                    last_settings_undo,
                    all_memory_events,
                    created_proposal_ids,
                    Some(diag.clone()),
                    build_usage(
                        acc_prompt_tokens,
                        acc_completion_tokens,
                        acc_cached_tokens,
                        reported_steps,
                        estimated_steps,
                        request_count,
                        compaction_tokens,
                    ),
                    was_cancelled,
                    all_web_sources,
                );
                send_ev(AgentStreamEvent::DiagnosticError {
                    error: diag,
                    partial_message: partial,
                });
                return;
            }
        };
        let llm_ms = t_llm.elapsed().as_millis() as u64;
        request_count += 1;

        let (step_pt, step_ct, step_cached, step_usage_source) = if let Some(u) = step_out.usage.as_ref() {
            acc_prompt_tokens += u.prompt_tokens;
            acc_completion_tokens += u.completion_tokens;
            acc_cached_tokens += u.cached_tokens;
            reported_steps += 1;
            (u.prompt_tokens, u.completion_tokens, u.cached_tokens, "reported")
        } else {
            let prompt_est: u64 = messages
                .iter()
                .map(|m| estimate_tokens(&m.to_string()))
                .sum::<u64>()
                .saturating_add(tools_schema_est_tokens);
            let tc_est: u64 = step_out
                .tool_calls
                .iter()
                .map(|tc| estimate_tokens(&tc.name) + estimate_tokens(&tc.arguments))
                .sum();
            let comp_est = estimate_tokens(&step_out.content)
                + estimate_tokens(&step_out.reasoning_content)
                + tc_est;
            acc_prompt_tokens += prompt_est;
            acc_completion_tokens += comp_est;
            estimated_steps += 1;
            (prompt_est, comp_est, 0, "estimated")
        };

        let cache_pct = if step_pt > 0 {
            ((step_cached as f64 / step_pt as f64) * 1000.0).round() / 10.0
        } else {
            0.0
        };
        let incomplete_reason = step_out.finish_reason.as_deref()
            .filter(|reason| matches!(*reason, "length" | "content_filter"));
        log_traj(
            step,
            "llm_step",
            &format!(
                "LLM 推理第 {step} 步（{}）",
                if !step_out.tool_calls.is_empty() {
                    format!("发起 {} 次工具调用", step_out.tool_calls.len())
                } else {
                    "输出最终答复".into()
                }
            ),
            if step_out.cancelled || incomplete_reason.is_some() { "warn" } else { "ok" },
            llm_ms,
            json!({
                "finishReason": step_out.finish_reason,
                "messagesIn": messages.len(),
                "toolsExposed": tools_schema.len(),
                "toolCallsCount": step_out.tool_calls.len(),
                "promptTokens": step_pt,
                "cachedTokens": step_cached,
                "cacheHitPct": cache_pct,
                "completionTokens": step_ct,
                "usageSource": step_usage_source,
                "requestKind": "main",
                "reasoningChars": step_out.reasoning_content.chars().count(),
                "contentChars": step_out.content.chars().count(),
                "reasoningPreview": step_out.reasoning_content.chars().take(600).collect::<String>(),
                "contentPreview": step_out.content.chars().take(600).collect::<String>(),
            }),
        );

        if !step_out.reasoning_content.is_empty() {
            if !final_reasoning.is_empty() {
                final_reasoning.push('\n');
            }
            final_reasoning.push_str(&step_out.reasoning_content);
        }

        // 若模型在无工具调用返回中意外泄露原始 DSML / XML 工具标签，自动拦截并补发一次纯文本收尾总结
        if step_out.tool_calls.is_empty()
            && !step_out.cancelled
            && contains_raw_tool_markup(&step_out.content)
        {
            log_traj(
                step,
                "guard",
                "拦截模型裸输出的工具调用标签（DSML），自动补发纯文本最终总结",
                "warn",
                0,
                json!({
                    "reason": "raw_tool_markup_leak",
                    "rawPreview": step_out.content.chars().take(240).collect::<String>(),
                }),
            );
            let mut clean_messages = messages.clone();
            clean_messages.push(json!({
                "role": "user",
                "content": "[重要修正] 禁止输出任何 <DSML> / XML / 工具调用格式。现在立刻用纯中文 Markdown 直接回答用户刚才的请求，给出完整结论和理由。"
            }));
            let recovery = stream_chat_completion(
                &profile.base_url,
                api_key.as_deref(),
                &profile.model,
                profile.temperature,
                &clean_messages,
                None,
                None,
                WebSearchMode::None,
                &cancel,
                |delta| {
                    send_ev(AgentStreamEvent::Delta {
                        text: delta.to_string(),
                    });
                },
                |reason| {
                    send_ev(AgentStreamEvent::ReasoningDelta {
                        text: reason.to_string(),
                    });
                },
            );
            if let Ok(Ok(recovery)) = tokio::time::timeout_at(deadline, recovery).await {
                was_cancelled = recovery.cancelled;
                if let Some(u) = recovery.usage.as_ref() {
                    acc_prompt_tokens += u.prompt_tokens;
                    acc_completion_tokens += u.completion_tokens;
                    acc_cached_tokens += u.cached_tokens;
                }
                if !recovery.content.is_empty() && !contains_raw_tool_markup(&recovery.content) {
                    final_content.push_str(&recovery.content);
                } else {
                    final_content.push_str(&strip_raw_tool_markup(&recovery.content));
                }
            } else {
                limited = true;
                final_content.push_str(&strip_raw_tool_markup(&step_out.content));
            }
            break;
        }

        if !step_delta_flushed && !step_delta_buf.is_empty() {
            if need_step_gap {
                send_ev(AgentStreamEvent::Delta {
                    text: "\n\n".to_string(),
                });
            }
            send_ev(AgentStreamEvent::Delta {
                text: std::mem::take(&mut step_delta_buf),
            });
        }
        if !step_out.content.is_empty() {
            if !final_content.is_empty() && !final_content.ends_with('\n') {
                final_content.push_str("\n\n");
            }
            final_content.push_str(&step_out.content);
        }

        if step_out.cancelled {
            was_cancelled = true;
            break;
        }

        if let Some(reason) = incomplete_reason {
            limited = true;
            final_content.push_str("\n\n> 模型提前结束了回复，本轮结果可能不完整。");
            log_traj(step, "guard", "模型提前结束回复", "warn", 0, json!({"finishReason":reason}));
            break;
        }

        if step_out.tool_calls.is_empty() {
            break;
        }

        // 收尾步越界拦截：模型在禁止调工具的收尾步仍然发起了 tool_calls，绝不执行，直接拦截并补发一次纯文本收尾总结
        if disallow_tools_this_step {
            limited = true;
            log_traj(
                step,
                "guard",
                "拦截收尾步越界工具调用，自动整理最终答复",
                "warn",
                0,
                json!({
                    "reason": "final_step_tool_call_intercepted",
                    "attemptedTools": step_out.tool_calls.iter().map(|t| t.name.as_str()).collect::<Vec<_>>(),
                }),
            );
            if step_out.content.trim().is_empty() {
                let mut clean_messages = messages.clone();
                clean_messages.push(json!({
                    "role": "user",
                    "content": "[最终收尾] 工具权限已关闭，请不要再调用工具，立刻用清晰完整的简体中文总结前面已查到的全部结果。"
                }));
                let recovery = stream_chat_completion(
                    &profile.base_url,
                    api_key.as_deref(),
                    &profile.model,
                    profile.temperature,
                    &clean_messages,
                    None,
                    None,
                    WebSearchMode::None,
                    &cancel,
                    |delta| {
                        send_ev(AgentStreamEvent::Delta {
                            text: delta.to_string(),
                        });
                    },
                    |reason| {
                        send_ev(AgentStreamEvent::ReasoningDelta {
                            text: reason.to_string(),
                        });
                    },
                );
                if let Ok(Ok(recovery)) = tokio::time::timeout_at(deadline, recovery).await {
                    was_cancelled = recovery.cancelled;
                    if let Some(u) = recovery.usage.as_ref() {
                        acc_prompt_tokens += u.prompt_tokens;
                        acc_completion_tokens += u.completion_tokens;
                        acc_cached_tokens += u.cached_tokens;
                    }
                    let cleaned = if contains_raw_tool_markup(&recovery.content) {
                        strip_raw_tool_markup(&recovery.content)
                    } else {
                        recovery.content
                    };
                    if !cleaned.trim().is_empty() {
                        if !final_content.is_empty() && !final_content.ends_with('\n') {
                            final_content.push_str("\n\n");
                        }
                        final_content.push_str(&cleaned);
                    }
                }
            }
            break;
        }

        let mut assistant_turn_val = json!({
            "role": "assistant",
            "content": step_out.content,
            "tool_calls": step_out.tool_calls.iter().map(crate::agent::types::ToolCallRecord::to_openai_wire_value).collect::<Vec<_>>()
        });
        if !step_out.reasoning_content.is_empty() {
            assistant_turn_val["reasoning_content"] = json!(step_out.reasoning_content);
        }
        messages.push(assistant_turn_val);

        // 同步落盘本步带 tool_calls 的 assistant 中间记录，确保后续多轮历史中每条 role="tool"
        // 前面都有合法的 preceding assistant(tool_calls) 消息，符合 DeepSeek/OpenAI 协议并保持 KV 缓存命中！
        if regenerate_id.is_none() {
            if let Ok(local) = handles.local_db.lock() {
                let amsg = AgentMessage {
                    id: Uuid::new_v4().to_string(),
                    session_id: input.session_id.clone(),
                    role: "assistant".into(),
                    content: step_out.content.clone(),
                    reasoning_content: if step_out.reasoning_content.is_empty() {
                        None
                    } else {
                        Some(step_out.reasoning_content.clone())
                    },
                    tool_calls: Some(step_out.tool_calls.clone()),
                    tool_call_id: None,
                    tool_name: None,
                    tool_traces: vec![],
                    ui_actions: vec![],
                    settings_undo: None,
                    memory_events: vec![],
                    proposal_id: None,
                    proposal_ids: vec![],
                    diagnostic_error: None,
                    token_usage: None,
                    versions: vec![],
                    active_version_idx: 0,
                    runtime_context: None,
                    stopped: false,
                    web_sources: vec![],
                    created_at: Utc::now().to_rfc3339(),
                };
                let _ = insert_message(&local, &amsg);
            }
        }

        for tc in &step_out.tool_calls {
            if cancel.is_cancelled() {
                was_cancelled = true;
                break;
            }

            let (label, group) = tool_meta(&tc.name);
            let args_preview: String = tc.arguments.chars().take(120).collect();
            send_ev(AgentStreamEvent::ToolStart {
                id: tc.id.clone(),
                name: tc.name.clone(),
                label: label.to_string(),
                group: group.to_string(),
                args_preview: args_preview.clone(),
            });

            let t_tool = Instant::now();
            let parsed_args: std::result::Result<Value, String> =
                serde_json::from_str::<Value>(&tc.arguments)
                    .map_err(|e| format!("JSON 参数解析失败：{e}，请检查引号与字段格式后重试"));

            let (tool_ok, raw_output, trace_summary) = match parsed_args {
                Err(parse_err) => {
                    (false, tool_error_json("VALIDATION_ERROR", parse_err), "参数格式错误".into())
                }
                Ok(_) if !allowed_tools.contains(tc.name.as_str())
                    && !(tc.name == "$web_search" && effective_web_mode == WebSearchMode::MoonshotNative) =>
                {
                    (false, tool_error_json("PERMISSION_DENIED", "当前对话未授权此功能。"), "权限已拦截".into())
                }
                Ok(_) if used_external_data
                    && matches!(tc.name.as_str(), "update_desktop_settings" | "update_user_memory") =>
                {
                    (false, tool_error_json("PERMISSION_DENIED", "本轮已读取外部内容。请在独立对话轮次明确请求这项修改。"), "已保护本机设置与记忆".into())
                }
                Ok(_) if created_proposal_ids.len() >= MAX_PROPOSALS_PER_TURN && group == "write" => {
                    (
                        false,
                        tool_error_json(
                            "PROPOSAL_LIMIT_REACHED",
                            format!("本轮已达变更方案数量上限（{MAX_PROPOSALS_PER_TURN} 份）。请先确认当前方案，再在下一轮继续提交。"),
                        ),
                        "已达本轮方案上限".into(),
                    )
                }
                Ok(args_val) => {
                    // 闸门 ②：单工具熔断拦截 + 精确重复调用熔断（不中途删减 tools_schema 以保住 KV 缓存）
                    if disabled_tools_this_turn.contains(&tc.name) {
                        (
                            false,
                            tool_error_json(
                                "CIRCUIT_OPEN",
                                format!("工具 `{}` 本轮连续多次失败已临时熔断，请换用其他工具或直接基于已有信息答复。", tc.name),
                            ),
                            "已熔断跳过".into(),
                        )
                    } else {
                        let sig = canonical_call_signature(&tc.name, &args_val);
                        if seen_call_sigs.contains(&sig) {
                            (
                                false,
                                tool_error_json(
                                    "DUPLICATE_CALL",
                                    "重复调用拦截：你刚用完全相同的参数成功调用过该工具，结果已在上下文中，请勿重复调用，直接整合已有数据回答或更换查询条件（如加 offset/keyword 翻页筛选）。",
                                ),
                                "拦截重复调用".into(),
                            )
                        } else {
                            let execution = execute_single_tool(
                                &handles,
                                &input,
                                &profile,
                                api_key.as_deref(),
                                enable_deepseek_web_tools,
                                &policy,
                                used_external_data,
                                regenerate_id.is_some(),
                                anti_spoiler_active,
                                &mut catalog_target,
                                &tc.name,
                                &args_val,
                                &mut all_ui_actions,
                                &mut last_settings_undo,
                                &mut all_memory_events,
                                &mut all_web_sources,
                                &mut created_proposal_ids,
                                &send_ev,
                            );
                            let outcome = if matches!(group, "online" | "mcp") {
                                tokio::select! {
                                    biased;
                                    _ = cancel.cancelled() => {
                                        was_cancelled = true;
                                        (false, json!({"error":"已停止联网工具。"}).to_string(), "已取消".into())
                                    }
                                    result = tokio::time::timeout_at(deadline, execution) => match result {
                                        Ok(outcome) => outcome,
                                        Err(_) => {
                                            limited = true;
                                            (false, json!({"error":"联网工具已到达本轮时限。"}).to_string(), "工具超时".into())
                                        }
                                    }
                                }
                            } else {
                                execution.await
                            };
                            if outcome.0 {
                                match call_signature_effect(group, &tc.name, &outcome.1) {
                                    CallSignatureEffect::Clear => seen_call_sigs.clear(),
                                    CallSignatureEffect::Skip => {}
                                    CallSignatureEffect::Record => {
                                        seen_call_sigs.insert(sig);
                                    }
                                }
                            }
                            outcome
                        }
                    }
                }
            };
            if matches!(group, "online" | "mcp") && tool_ok {
                used_external_data = true;
            }

            let duration_ms = t_tool.elapsed().as_millis() as u64;
            if tool_ok {
                total_error_streak = 0;
                tool_fail_streaks.insert(tc.name.clone(), 0);
            } else if !is_non_circuit_guard_error(&raw_output) {
                // 参数校验/本地守卫拒绝不触发单工具整轮熔断，避免换参数也调不动
                total_error_streak += 1;
                let streak = tool_fail_streaks.entry(tc.name.clone()).or_insert(0);
                *streak += 1;
                if *streak >= CONSECUTIVE_ERROR_CAP {
                    disabled_tools_this_turn.insert(tc.name.clone());
                }
            } else {
                total_error_streak += 1;
            }

            if tool_ok && tc.name == "read_library_page" {
                if let Ok(page) = serde_json::from_str::<Value>(&raw_output) {
                    if let (Some(id), Some(total), Some(offset), Some(shown)) = (
                        page["resultId"].as_str(), page["total"].as_u64(), page["offset"].as_u64(), page["shown"].as_u64(),
                    ) {
                        let (_, read) = library_reads.entry(id.into()).or_insert_with(|| (total as usize, HashSet::new()));
                        read.extend(offset as usize..(offset.saturating_add(shown)) as usize);
                    }
                }
            }

            // 超长工具输出归档：一次性落盘，历史上下文固定保存紧凑预览 + 句柄（既省 Token 又不丢信息、不破坏 KV Cache）
            let raw_output_chars = raw_output.chars().count();
            let mut spilled_artifact_id: Option<String> = None;
            let (final_tool_text, spilled) = if raw_output_chars > ARTIFACT_SPILL_THRESHOLD_CHARS
                && tc.name != "read_tool_artifact"
            {
                if let Ok(local) = handles.local_db.lock() {
                    match save_tool_artifact(
                        &local,
                        &input.session_id,
                        &tc.name,
                        &raw_output,
                        ARTIFACT_PREVIEW_CHARS,
                    ) {
                        Ok((art_id, preview)) => {
                            spilled_artifact_id = Some(art_id);
                            (preview, true)
                        }
                        Err(_) => {
                            let head: String = raw_output.chars().take(ARTIFACT_PREVIEW_CHARS).collect();
                            (format!("{head}\n... [输出已截断]"), true)
                        }
                    }
                } else {
                    let head: String = raw_output.chars().take(ARTIFACT_PREVIEW_CHARS).collect();
                    (format!("{head}\n... [输出已截断]"), true)
                }
            } else {
                (raw_output, false)
            };

            let parsed_args_for_log: Value =
                serde_json::from_str(&tc.arguments).unwrap_or_else(|_| json!(tc.arguments));
            log_traj(
                step,
                "tool_call",
                &format!("{label}（{}）", tc.name),
                if was_cancelled { "warn" } else if tool_ok { "ok" } else { "error" },
                duration_ms,
                json!({
                    "toolCallId": tc.id,
                    "toolName": tc.name,
                    "group": group,
                    "ok": tool_ok,
                    "cancelled": was_cancelled,
                    "summary": trace_summary,
                    "args": parsed_args_for_log,
                    "outputChars": raw_output_chars,
                    "spilledArtifactId": spilled_artifact_id,
                    "outputPreview": final_tool_text.chars().take(1800).collect::<String>(),
                }),
            );

            let trace = ToolTraceItem {
                id: tc.id.clone(),
                name: tc.name.clone(),
                label: label.to_string(),
                group: group.to_string(),
                args_preview,
                ok: tool_ok,
                summary: if spilled {
                    format!("{trace_summary} · 已溢写归档")
                } else {
                    trace_summary
                },
                duration_ms,
                truncated: spilled,
            };
            all_traces.push(trace.clone());
            send_ev(AgentStreamEvent::ToolResult { trace });

            messages.push(json!({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": final_tool_text,
            }));

            // 仅正常发送轮次将 tool 消息落入历史（无损重生成不破坏原消息顺序）
            if regenerate_id.is_none() {
                if let Ok(local) = handles.local_db.lock() {
                    let history_content = if tool_ok && tc.name == "read_tool_artifact" {
                        serde_json::from_str::<Value>(&final_tool_text).map(|mut page| {
                            if let Some(fields) = page.as_object_mut() {
                                fields.remove("content");
                            }
                            page["note"] = json!("历史字符片段已保存在归档，可按原位置重新读取。");
                            page.to_string()
                        }).unwrap_or(final_tool_text)
                    } else {
                        final_tool_text
                    };
                    let tmsg = AgentMessage {
                        id: Uuid::new_v4().to_string(),
                        session_id: input.session_id.clone(),
                        role: "tool".into(),
                        content: history_content,
                        reasoning_content: None,
                        tool_calls: None,
                        tool_call_id: Some(tc.id.clone()),
                        tool_name: Some(tc.name.clone()),
                        tool_traces: vec![],
                        ui_actions: vec![],
                        settings_undo: None,
                        memory_events: vec![],
                        proposal_id: None,
                        proposal_ids: vec![],
                        diagnostic_error: None,
                        token_usage: None,
                        versions: vec![],
                        active_version_idx: 0,
                        runtime_context: None,
                        stopped: false,
                        web_sources: vec![],
                        created_at: Utc::now().to_rfc3339(),
                    };
                    let _ = insert_message(&local, &tmsg);
                }
            }
            if was_cancelled || tokio::time::Instant::now() >= deadline {
                break;
            }
        }

        // 闸门 ③ & ④：连续错误熔断 / Token 总预算熔断
        if total_error_streak >= CONSECUTIVE_ERROR_CAP {
            limited = true;
            log_traj(
                step,
                "guard",
                "触发连续工具错误熔断，转入总结收尾",
                "warn",
                0,
                json!({ "consecutiveErrors": total_error_streak }),
            );
            messages.push(json!({
                "role": "user",
                "content": "[系统提示] 连续多次工具调用未成功，请停止继续尝试工具，直接向用户说明当前遇到的问题和已查到的信息。"
            }));
            force_no_tools_next = true;
        }

        let budget_tokens = acc_prompt_tokens.saturating_sub(acc_cached_tokens).saturating_add(acc_completion_tokens);
        if budget_tokens >= max_turn_tokens {
            limited = true;
            log_traj(
                step,
                "guard",
                "触发单轮 Token 预算熔断，转入总结收尾",
                "warn",
                0,
                json!({
                    "accumulatedTokens": budget_tokens,
                    "cachedInputTokens": acc_cached_tokens,
                    "budget": max_turn_tokens,
                }),
            );
            messages.push(json!({
                "role": "user",
                "content": "[系统提示] 本轮已达到未缓存输入与输出 Token 预算。保留已取得的结果，说明尚未核验或提交的事项。"
            }));
            force_no_tools_next = true;
        }
    }

    let library_coverage: Vec<_> = library_reads.iter().map(|(id, (total, read))| {
        json!({"resultId": id, "total": total, "recordsRead": read.len()})
    }).collect();
    for (total, read) in library_reads.values() {
        if read.len() < *total {
            final_content.push_str(&format!("\n\n本轮从一份藏品清单读取了 {} / {total} 条记录，仍有 {} 条未读取。", read.len(), total - read.len()));
        }
    }
    log_traj(
        executed_steps,
        "run_end",
        if was_cancelled {
            "本轮对话已手动中止"
        } else if limited {
            "本轮到达处理限制，结果可能不完整"
        } else {
            "本轮对话完成"
        },
        if was_cancelled || limited { "warn" } else { "ok" },
        turn_started.elapsed().as_millis() as u64,
        json!({
            "steps": executed_steps,
            "toolCalls": all_traces.len(),
            "promptTokens": acc_prompt_tokens,
            "cachedTokens": acc_cached_tokens,
            "completionTokens": acc_completion_tokens,
            "cancelled": was_cancelled,
            "limited": limited,
            "hasProposal": !created_proposal_ids.is_empty(),
            "proposalCount": created_proposal_ids.len(),
            "libraryReadCoverage": library_coverage,
        }),
    );

    let final_usage = build_usage(
        acc_prompt_tokens,
        acc_completion_tokens,
        acc_cached_tokens,
        reported_steps,
        estimated_steps,
        request_count,
        compaction_tokens,
    );
    if let Some(ref u) = final_usage {
        send_ev(AgentStreamEvent::Usage { usage: u.clone() });
        if let Ok(local) = handles.local_db.lock() {
            let _ = touch_session_usage(
                &local,
                &input.session_id,
                None,
                u.prompt_tokens,
                u.completion_tokens,
            );
        }
    }

    let assistant_msg = persist_or_append_assistant_message(
        &handles,
        &input.session_id,
        regenerate_id.as_deref(),
        final_content,
        final_reasoning,
        all_traces,
        all_ui_actions,
        last_settings_undo,
        all_memory_events,
        created_proposal_ids,
        None,
        final_usage,
        was_cancelled,
        all_web_sources,
    )
    .unwrap_or_else(|| AgentMessage {
        id: Uuid::new_v4().to_string(),
        session_id: input.session_id.clone(),
        role: "assistant".into(),
        content: String::new(),
        reasoning_content: None,
        tool_calls: None,
        tool_call_id: None,
        tool_name: None,
        tool_traces: vec![],
        ui_actions: vec![],
        settings_undo: None,
        memory_events: vec![],
        proposal_id: None,
        proposal_ids: vec![],
        diagnostic_error: None,
        token_usage: None,
        versions: vec![],
        active_version_idx: 0,
        runtime_context: None,
        stopped: was_cancelled,
        web_sources: vec![],
        created_at: Utc::now().to_rfc3339(),
    });

    send_ev(AgentStreamEvent::Done {
        assistant_message: assistant_msg,
        cancelled: was_cancelled,
    });
}

/// 长对话自动/手动滚动摘要压缩
pub async fn compact_session_context(
    handles: &AgentRuntimeHandles,
    session_id: &str,
    profile_id: Option<&str>,
    keep_recent_turns: usize,
) -> std::result::Result<Option<(String, String, usize)>, DiagnosticError> {
    Ok(
        compact_session_context_with_usage(handles, session_id, profile_id, keep_recent_turns)
            .await?
            .map(|(s, c, n, _)| (s, c, n)),
    )
}

async fn compact_session_context_with_usage(
    handles: &AgentRuntimeHandles,
    session_id: &str,
    profile_id: Option<&str>,
    keep_recent_turns: usize,
) -> std::result::Result<Option<(String, String, usize, u64)>, DiagnosticError> {
    let (profile, api_key, prev_summary, to_compact, cutoff_id) = {
        let local = handles.local_db.lock().map_err(|_| DiagnosticError {
            code: "LOCK_ERROR".into(),
            title: "本地锁异常".into(),
            explanation: "压缩上下文时读取本地数据库失败".into(),
            suggestion: "请稍后重试".into(),
            quick_fix: None,
            suggested_base_url: None,
            technical_detail: "local_db lock failed".into(),
        })?;
        let profile = get_profile(&local, &handles.studio_data_dir, profile_id)
            .ok()
            .flatten()
            .ok_or_else(|| DiagnosticError {
                code: "NO_PROFILE".into(),
                title: "尚未配置模型".into(),
                explanation: "整理压缩上文需要调用你配置的模型".into(),
                suggestion: "请先完成模型接入配置".into(),
                quick_fix: Some("open_config".into()),
                suggested_base_url: None,
                technical_detail: "no profile".into(),
            })?;
        let api_key = load_profile_api_key(&handles.studio_data_dir, &profile.id)
            .ok()
            .flatten();
        let sess = get_session(&local, session_id).ok().flatten();
        let prev_summary = sess.as_ref().and_then(|s| s.rolling_summary.clone());
        let prev_cutoff = sess.as_ref().and_then(|s| s.compacted_up_to_msg_id.clone());
        let history = load_session_messages(&local, session_id).unwrap_or_default();

        let start_idx = prev_cutoff
            .as_deref()
            .and_then(|cid| history.iter().position(|m| m.id == cid).map(|i| i + 1))
            .unwrap_or(0);
        let uncompacted = &history[start_idx..];
        let user_indices: Vec<usize> = uncompacted
            .iter()
            .enumerate()
            .filter(|(_, m)| m.role == "user")
            .map(|(i, _)| i)
            .collect();

        let keep = keep_recent_turns.max(2);
        if user_indices.len() <= keep {
            return Ok(None);
        }
        let split_at = user_indices[user_indices.len() - keep];
        if split_at == 0 {
            return Ok(None);
        }
        let older_slice: Vec<AgentMessage> = uncompacted[..split_at].to_vec();
        let cutoff_id = older_slice.last().map(|m| m.id.clone()).unwrap_or_default();
        (profile, api_key, prev_summary, older_slice, cutoff_id)
    };

    if cutoff_id.is_empty() || to_compact.is_empty() {
        return Ok(None);
    }

    let mut transcript = String::new();
    for m in &to_compact {
        match m.role.as_str() {
            "user" => {
                transcript.push_str("用户：");
                transcript.push_str(&m.content.chars().take(600).collect::<String>());
                transcript.push('\n');
            }
            "assistant" => {
                transcript.push_str("助手：");
                transcript.push_str(&m.content.chars().take(800).collect::<String>());
                transcript.push('\n');
            }
            _ => {}
        }
    }

    let mut prompt = String::from(
        "请把以下较早对话压缩为一份高信息密度、结构化的简体中文「滚动上下文摘要」（不超过 500 字），供后续长对话无缝衔接：\n\
         必须保留：1) 讨论过的具体作品名/ID/集数进度与防剧透边界；2) 用户的明确偏好、观点与已达成的整理决定；3) 尚未完成的待办问题。不要废话。\n",
    );
    if let Some(prev) = prev_summary.as_deref().filter(|s| !s.trim().is_empty()) {
        prompt.push_str("\n【已有前序摘要（请合并更新，不要丢失关键信息）】\n");
        prompt.push_str(prev);
        prompt.push('\n');
    }
    prompt.push_str("\n【新增待归档对话】\n");
    prompt.push_str(&transcript);

    let req_msgs = vec![json!({ "role": "user", "content": prompt })];
    let cancel = CancellationToken::new();
    let out = stream_chat_completion(
        &profile.base_url,
        api_key.as_deref(),
        &profile.model,
        0.2,
        &req_msgs,
        None,
        None,
        WebSearchMode::None,
        &cancel,
        |_| {},
        |_| {},
    )
    .await?;

    let mut summary = out.content.trim().to_string();
    if summary.is_empty() {
        return Ok(None);
    }

    if let Ok(local) = handles.local_db.lock() {
        // 长聊压缩后结构化接回本地活跃清单、核实任务和待确认提案句柄，不再因丢弃 tool 消息而失忆
        if let Ok(Some(handles_block)) = summarize_active_session_handles(&local, session_id) {
            if !summary.contains("【本地活跃任务与句柄") {
                summary.push_str("\n\n");
                summary.push_str(&handles_block);
            }
        }
        let _ = save_session_compaction(&local, session_id, &summary, &cutoff_id);
        if let Some(ref u) = out.usage {
            let _ = touch_session_usage(
                &local,
                session_id,
                None,
                u.prompt_tokens,
                u.completion_tokens,
            );
        }
    }

    let used_tokens = out
        .usage
        .as_ref()
        .map(|u| u.total_tokens)
        .unwrap_or_else(|| estimate_tokens(&prompt) + estimate_tokens(&summary));
    Ok(Some((summary, cutoff_id, to_compact.len(), used_tokens)))
}

fn current_proposal_statuses(handles: &AgentRuntimeHandles, session_id: &str, id: Option<&str>) -> crate::Result<Vec<Value>> {
    current_proposal_statuses_paged(handles, session_id, id, 0, 50)
}

fn current_proposal_statuses_paged(
    handles: &AgentRuntimeHandles,
    session_id: &str,
    id: Option<&str>,
    offset: usize,
    limit: usize,
) -> crate::Result<Vec<Value>> {
    let sync = handles.sync_db.lock()
        .map_err(|_| crate::StudioError::InvalidInput("暂时无法读取藏品。".into()))?;
    let local = handles.local_db.lock()
        .map_err(|_| crate::StudioError::InvalidInput("暂时无法读取提案。".into()))?;
    recover_proposal_writes(&sync, &local)?;
    read_proposal_statuses_paged(&local, session_id, id, offset, limit)
}

#[allow(clippy::too_many_arguments)]
async fn execute_single_tool<FEmit>(
    handles: &AgentRuntimeHandles,
    input: &SendAgentMessageInput,
    profile: &crate::agent::types::AgentProfile,
    api_key: Option<&str>,
    enable_deepseek_web_tools: bool,
    policy: &crate::agent::types::AgentPolicyConfig,
    used_external_data: bool,
    is_regenerate: bool,
    anti_spoiler_active: bool,
    catalog_target: &mut Option<(String, i64)>,
    tool_name: &str,
    args: &Value,
    all_ui_actions: &mut Vec<UiActionCommand>,
    last_settings_undo: &mut Option<SettingsUndoRecord>,
    all_memory_events: &mut Vec<MemoryChangeEvent>,
    all_web_sources: &mut Vec<WebSourceCitation>,
    created_proposal_ids: &mut Vec<String>,
    send_ev: &FEmit,
) -> (bool, String, String)
where
    FEmit: Fn(AgentStreamEvent),
{
    match tool_name {
        "get_proposal_status" => {
            let id = args.get("proposal_id").and_then(Value::as_str);
            let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
            let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(50) as usize;
            let result = current_proposal_statuses_paged(handles, &input.session_id, id, offset, limit).map(|proposals| {
                json!({"proposals": proposals, "note": "空结果表示当前对话未找到该提案。状态与逐项回执以本次查询为准。"})
            });
            format_json_result(result, "已读取提案当前状态")
        }
        "get_runtime_stats" => {
            let res = (|| {
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                summarize_session_runtime_stats(&local, &input.session_id)
            })();
            format_json_result(res, "已读取会话运行与缓存统计")
        }
        "read_library_page" => {
            let result = (|| {
                let id = args["result_id"].as_str().unwrap_or_default();
                let offset = args["offset"].as_u64().unwrap_or(0) as usize;
                let limit = args["limit"].as_u64().unwrap_or(20).clamp(1, 30) as usize;
                let local = handles.local_db.lock()
                    .map_err(|_| crate::StudioError::InvalidInput("暂时无法读取藏品清单。".into()))?;
                let items = load_library_result(&local, id, Some(&input.session_id))?;
                let mut page = library_record_page_with_local(
                    Some(&local),
                    items.iter(),
                    items.len(),
                    offset,
                    limit,
                );
                page["resultId"] = json!(id);
                Ok(page)
            })();
            format_json_result(result, "已读取藏品清单中的作品记录")
        }
        "read_tool_artifact" => {
            let res = (|| {
                let aid = args
                    .get("artifact_id")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let offset = args.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                let limit = args.get("limit").and_then(|v| v.as_u64()).unwrap_or(3000) as usize;
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                let source: String = local.query_row(
                    "SELECT tool_name FROM agent_tool_artifacts WHERE id = ?1 AND session_id = ?2",
                    rusqlite::params![aid, input.session_id],
                    |row| row.get(0),
                ).map_err(|_| crate::StudioError::InvalidInput("当前对话无法访问这份记录。".into()))?;
                if source == "library_result" {
                    return Err(crate::StudioError::InvalidInput(format!(
                        "该 ID（{aid}）是本地藏品清单快照，不能用 read_tool_artifact 字符回读；请改用 read_library_page(result_id=\"{aid}\", offset=0, limit=30) 按作品记录翻页。"
                    )));
                }
                let allowed = match tool_meta(&source).1 {
                    "read" => policy.enabled_groups.read,
                    "online" => policy.enabled_groups.online,
                    "mcp" => policy.enabled_groups.mcp,
                    "memory" => policy.enabled_groups.memory,
                    _ => false,
                };
                if !allowed {
                    return Err(crate::StudioError::InvalidInput("当前对话未授权读取这份记录。".into()));
                }
                read_tool_artifact(&local, aid, offset, limit)
            })();
            format_json_result(res, "已分页回读溢写数据")
        }
        "load_skill" => {
            let res = (|| {
                let name = args.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                match load_skill_guide(&local, name)? {
                    Some(sc) => Ok(json!({
                        "command": sc.command,
                        "label": sc.label,
                        "description": sc.description,
                        "guide": sc.skill_guide.unwrap_or(sc.prompt_template),
                    })),
                    None => Err(crate::StudioError::InvalidInput(format!(
                        "未找到名为 `{name}` 的追番技能"
                    ))),
                }
            })();
            format_json_result(res, "已加载追番技能规程")
        }
        "recall_memories" => {
            let res: crate::Result<Value> = (|| {
                let query = args.get("query").and_then(|v| v.as_str()).unwrap_or("");
                let cat = args.get("category").and_then(|v| v.as_str());
                let tier = args.get("tier").and_then(|v| v.as_str());
                let match_mode = args.get("match_mode").and_then(|v| v.as_str()).unwrap_or("and");
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                let hits = recall_memories_local_with_tier(&local, query, cat, tier, match_mode)?;
                Ok(json!({
                    "count": hits.len(),
                    "tier": tier.unwrap_or("all"),
                    "matchMode": match_mode,
                    "memories": hits.into_iter().map(|m| json!({
                        "id": m.id,
                        "category": m.category,
                        "tier": m.tier,
                        "content": m.content,
                    })).collect::<Vec<_>>(),
                }))
            })();
            match res {
                Ok(v) => {
                    let cnt = v.get("count").and_then(|x| x.as_u64()).unwrap_or(0);
                    (true, v.to_string(), format!("召回 {cnt} 条记忆"))
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "mcp_list_tools" => {
            // 纯异步发现外部 MCP 工具（绝不持有 DB 锁期间 await）
            let target_server = args
                .get("server_name")
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);

            let servers_with_tokens: Vec<_> = {
                let Ok(local) = handles.local_db.lock() else {
                    return (false, json!({ "error": "local_db 锁异常" }).to_string(), "锁异常".into());
                };
                let all = list_mcp_servers(&local, &handles.studio_data_dir).unwrap_or_default();
                all.into_iter()
                    .filter(|s| s.enabled)
                    .filter(|s| target_server.as_deref().map(|n| n == s.name).unwrap_or(true))
                    .map(|s| {
                        let tok = load_mcp_auth_token(&handles.studio_data_dir, &s.id)
                            .ok()
                            .flatten();
                        (s, tok)
                    })
                    .collect()
            };

            if servers_with_tokens.is_empty() {
                return (
                    true,
                    json!({ "servers": [], "note": "当前没有已启用的 MCP Server" }).to_string(),
                    "无已启用 MCP".into(),
                );
            }

            let mut out_servers = Vec::new();
            let mut total_tools = 0usize;
            for (srv, tok) in servers_with_tokens {
                let res = test_and_list_mcp_tools(&srv, tok.as_deref()).await;
                let active_tools: Vec<Value> = res
                    .tools
                    .into_iter()
                    .filter(|t| !t.disabled)
                    .map(|t| {
                        total_tools += 1;
                        json!({
                            "name": t.name,
                            "description": t.description,
                            "requiresConfirm": t.requires_confirm,
                            "inputSchema": t.input_schema,
                        })
                    })
                    .collect();
                out_servers.push(json!({
                    "serverName": srv.name,
                    "ok": res.ok,
                    "error": res.error,
                    "tools": active_tools,
                }));
            }
            (
                true,
                json!({ "totalTools": total_tools, "servers": out_servers }).to_string(),
                format!("发现 {total_tools} 个外部 MCP 工具"),
            )
        }
        "mcp_call_tool" => {
            let server_name = args
                .get("server_name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            let mcp_tool = args
                .get("tool_name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            let mcp_args = args
                .get("arguments")
                .cloned()
                .unwrap_or_else(|| json!({}));

            let server_opt = {
                let Ok(local) = handles.local_db.lock() else {
                    return (false, json!({ "error": "local_db 锁异常" }).to_string(), "锁异常".into());
                };
                list_mcp_servers(&local, &handles.studio_data_dir)
                    .unwrap_or_default()
                    .into_iter()
                    .find(|s| s.name == server_name && s.enabled)
                    .map(|s| {
                        let tok = load_mcp_auth_token(&handles.studio_data_dir, &s.id)
                            .ok()
                            .flatten();
                        (s, tok)
                    })
            };

            let Some((server, auth_token)) = server_opt else {
                return (
                    false,
                    json!({ "error": format!("MCP 服务 `{server_name}` 不存在或未启用") }).to_string(),
                    "MCP 未启用".into(),
                );
            };
            if server.disabled_tools.iter().any(|d| d == &mcp_tool) {
                return (
                    false,
                    json!({ "error": format!("工具 `{mcp_tool}` 已被用户禁用") }).to_string(),
                    "工具已禁用".into(),
                );
            }

            let needs_confirm = server.require_confirm_tools.iter().any(|c| c == &mcp_tool)
                || tool_looks_mutating(&mcp_tool, "")
                || policy.trust_mode == "strict"
                || used_external_data;

            if needs_confirm {
                if created_proposal_ids.len() >= MAX_PROPOSALS_PER_TURN {
                    return (
                        false,
                        tool_error_json(
                            "PROPOSAL_LIMIT_REACHED",
                            format!("本轮已达变更方案数量上限（{MAX_PROPOSALS_PER_TURN} 份）。"),
                        ),
                        "已达本轮方案上限".into(),
                    );
                }
                let prop_res = (|| {
                    let local = handles
                        .local_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    create_mcp_call_proposal(
                        &local,
                        &input.session_id,
                        &server.name,
                        &mcp_tool,
                        &mcp_args,
                    )
                })();
                match prop_res {
                    Ok(proposal) => {
                        push_unique_proposal(created_proposal_ids, proposal.id.clone());
                        send_ev(AgentStreamEvent::ProposalCreated {
                            proposal: proposal.clone(),
                        });
                        return (
                            true,
                            json!({
                                "status": "pending_user_confirmation",
                                "proposalId": proposal.id,
                                "note": "该外部 MCP 工具需要用户确认，已生成审批卡片等待点击确认"
                            })
                            .to_string(),
                            format!("外部 MCP `{mcp_tool}` 待确认"),
                        );
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        return (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg);
                    }
                }
            }

            // 只读外部 MCP 工具：异步执行 + 不可信隔离标签包裹
            match invoke_mcp_tool(&server, auth_token.as_deref(), &mcp_tool, &mcp_args).await {
                Ok(raw_text) => {
                    let wrapped = wrap_untrusted_mcp_output(&server.name, &mcp_tool, &raw_text);
                    (true, wrapped, format!("MCP `{mcp_tool}` 已返回"))
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "get_library_overview" => {
            let res = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                exec_get_library_overview(&sync, &local)
            })();
            format_json_result(res, "已读取藏品库概况")
        }
        "query_library" => {
            let res = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                let limit = args.get("limit").and_then(Value::as_u64);
                if args.get("keywords").is_none()
                    && (args.get("complete").and_then(Value::as_bool) == Some(true)
                        || limit.is_some_and(|n| n > 30))
                {
                    validate_query_library_args(args)?;
                    if limit == Some(0) || args.get("offset").and_then(Value::as_u64).unwrap_or(0) != 0 {
                        return Err(crate::StudioError::InvalidInput("本地清单从首条开始生成，条数应大于零或省略。".into()));
                    }
                    let items = crate::repository::query_curator_items(&sync, &local)?;
                    let mut matches = filter_library_with_local(&sync, Some(&local), &items, args);
                    let total = matches.len();
                    matches.truncate(limit.unwrap_or(u64::MAX).try_into().unwrap_or(usize::MAX));
                    let count = matches.len();
                    let (id, _) = save_tool_artifact(
                        &local, &input.session_id, "library_result",
                        &serde_json::to_string(&matches)?, 0,
                    )?;
                    all_ui_actions.push(UiActionCommand {
                        action: "library_result".into(),
                        label: format!("藏品清单 · {count} 条"),
                        payload: json!({"resultId": id, "total": total, "count": count}),
                    });
                    return Ok(json!({
                        "resultId": id, "total": total, "delivered": count,
                        "readByModel": 0,
                        "note": "清单已保存，可在对话的卡片查看或导出 CSV。若还需逐条核验，从 read_library_page(result_id) 开始。"
                    }));
                }
                exec_query_library(&sync, &local, args)
            })();
            match res {
                Ok(v) => {
                    let total = v.get("total").and_then(|x| x.as_u64()).unwrap_or(0);
                    if let Some(count) = v.get("delivered").and_then(Value::as_u64) {
                        return (true, v.to_string(), format!("匹配 {total} 条，已生成 {count} 条的本地清单"));
                    }
                    if let Some(checked) = v.get("checked").and_then(Value::as_u64) {
                        return (true, v.to_string(), format!("已核查 {checked} 个标题"));
                    }
                    let shown = v.get("shown").and_then(|x| x.as_u64()).unwrap_or(0);
                    (true, v.to_string(), format!("命中 {total} 条（展示 {shown} 条）"))
                }
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        "get_work_detail" => {
            let res = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                exec_get_work_detail_with_dir(&sync, &local, Some(&handles.studio_data_dir), args)
            })();
            match res {
                Ok(mut v) => {
                    // D5 / #17：默认不阻塞读库，仅在显式传 probe_cover=true 且联网允许时，并发短超时探测未缓存的远程封面（单次上限 5 条）
                    let should_probe_cover = policy.enabled_groups.online
                        && args
                            .get("probe_cover")
                            .and_then(Value::as_bool)
                            .unwrap_or(false);
                    if should_probe_cover {
                        let mut to_probe = Vec::<String>::new();
                        let collect_url = |obj: &Value, buf: &mut Vec<String>| {
                            if obj.get("coverType").and_then(Value::as_str) == Some("remote")
                                && obj.get("coverUpdatedAt").is_none_or(Value::is_null)
                            {
                                if let Some(u) = obj.get("coverUrl").and_then(Value::as_str) {
                                    if !buf.iter().any(|x| x == u) && buf.len() < 5 {
                                        buf.push(u.to_string());
                                    }
                                }
                            }
                        };
                        if let Some(arr) = v.get("items").and_then(Value::as_array) {
                            for it in arr {
                                collect_url(it, &mut to_probe);
                            }
                        } else {
                            collect_url(&v, &mut to_probe);
                        }
                        if !to_probe.is_empty() {
                            let mut join_set = tokio::task::JoinSet::new();
                            for u in to_probe {
                                join_set.spawn(async move {
                                    let ok = tokio::time::timeout(
                                        std::time::Duration::from_secs(4),
                                        crate::cloud::probe_remote_cover_available(&u),
                                    )
                                    .await
                                    .unwrap_or(false);
                                    (u, ok)
                                });
                            }
                            let mut probed = std::collections::HashMap::<String, (bool, String)>::new();
                            while let Some(res) = join_set.join_next().await {
                                if let Ok((u, ok)) = res {
                                    let ts = Utc::now().to_rfc3339();
                                    if let Ok(local) = handles.local_db.lock() {
                                        let _ = crate::agent::storage::save_cached_cover_probe(&local, &u, ok);
                                    }
                                    probed.insert(u, (ok, ts));
                                }
                            }
                            let apply_probe = |obj: &mut Value| {
                                if let Some(u) = obj.get("coverUrl").and_then(Value::as_str).map(str::to_string) {
                                    if let Some((ok, ts)) = probed.get(&u) {
                                        obj["coverAvailable"] = json!(*ok);
                                        obj["missingCover"] = json!(!*ok);
                                        obj["coverUpdatedAt"] = json!(ts);
                                        if let Some(c) = obj.get_mut("cover").and_then(Value::as_object_mut) {
                                            c.insert("available".into(), json!(*ok));
                                        }
                                    }
                                }
                            };
                            if let Some(arr) = v.get_mut("items").and_then(Value::as_array_mut) {
                                for it in arr {
                                    apply_probe(it);
                                }
                            } else {
                                apply_probe(&mut v);
                            }
                        }
                    }
                    let summary = if v.get("mode").and_then(|x| x.as_str()) == Some("batch") {
                        let found = v.get("foundCount").and_then(|x| x.as_u64()).unwrap_or(0);
                        let req = v.get("requested").and_then(|x| x.as_u64()).unwrap_or(0);
                        format!("批量读取 {found}/{req} 部作品详情")
                    } else if v.get("ambiguous").and_then(|x| x.as_bool()).unwrap_or(false) {
                        "命中多部同名作品，待确认".into()
                    } else if let Some(t) = v.get("title").and_then(|x| x.as_str()) {
                        if v.get("deleted").and_then(|x| x.as_bool()).unwrap_or(false) {
                            format!("已读取回收站条目《{t}》")
                        } else {
                            format!("已读取《{t}》")
                        }
                    } else {
                        "未找到对应作品".into()
                    };
                    (true, v.to_string(), summary)
                }
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        "find_external_id_usage" => {
            let res = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                exec_find_external_id_usage(&sync, args)
            })();
            format_json_result(res, "已完成外部条目占用反查")
        }
        "list_taxonomy" => {
            let res = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                exec_list_taxonomy(&sync, args)
            })();
            match res {
                Ok(v) => {
                    let total = v.get("total").and_then(|x| x.as_u64()).unwrap_or(0);
                    (true, v.to_string(), format!("共 {total} 项"))
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "run_library_diagnostics" => {
            let scope = args.get("scope").and_then(Value::as_str).unwrap_or("all");
            let res: crate::Result<Value> = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                if scope == "covers" {
                    let local = handles
                        .local_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    let stats = compute_cover_baseline_stats(&sync, &local, &handles.studio_data_dir)?;
                    return Ok(json!({
                        "scope": "covers",
                        "baselineStats": stats,
                        "note": "以上为全库封面分布与 covers/native/ 真实磁盘字节统计；如需将程序自动落盘（external_native）的本地封面瘦身替换为远程链接，可调用 propose_work_mutations(mode=\"migrate_covers_to_remote\")。"
                    }));
                }
                if scope == "dates" {
                    let local = handles
                        .local_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    return scan_nonstandard_release_dates(&sync, &local);
                }
                exec_run_library_diagnostics(&sync, args)
            })();
            format_json_result(res, "已完成全库诊断")
        }
        "query_app_knowledge" => {
            let val = exec_query_app_knowledge(args, input.current_settings.as_ref());
            (true, val.to_string(), "已查阅内置手册与设置注册表".into())
        }
        "search_remote_catalog" => {
            let res = async {
                let has_work_id = args.get("work_id").and_then(Value::as_i64).is_some();
                let has_work_ids = args.get("work_ids").and_then(Value::as_array).is_some();
                if has_work_id && has_work_ids {
                    return Err(crate::StudioError::InvalidInput(
                        "work_id 与 work_ids 不能同时传入，请二选一。".into(),
                    ));
                }

                // 可续跑批量核实模式：直接接收 result_id（清单快照）或 verify_job_id（续跑/仅重试失败项）
                if let Some(job_id) = args
                    .get("verify_job_id")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    let retry_failed = args
                        .get("batch_action")
                        .and_then(Value::as_str)
                        == Some("retry_failed");
                    let batch_size = args
                        .get("batch_size")
                        .and_then(Value::as_u64)
                        .unwrap_or(30) as usize;
                    return run_verify_job_batch(
                        &handles.studio_data_dir,
                        &handles.local_db,
                        job_id,
                        batch_size,
                        retry_failed,
                    )
                    .await;
                }
                if let Some(result_id) = args
                    .get("result_id")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    if !policy.enabled_groups.read {
                        return Err(crate::StudioError::InvalidInput("批量核实清单需要读库权限。".into()));
                    }
                    let kind = args.get("kind").and_then(Value::as_str).unwrap_or("all");
                    let batch_size = args
                        .get("batch_size")
                        .and_then(Value::as_u64)
                        .unwrap_or(30) as usize;
                    let job = {
                        let local = handles
                            .local_db
                            .lock()
                            .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                        let items = load_library_result(&local, result_id, Some(&input.session_id))?;
                        create_verify_job_from_items(&local, &input.session_id, result_id, kind, &items)?
                    };
                    return run_verify_job_batch(
                        &handles.studio_data_dir,
                        &handles.local_db,
                        &job.id,
                        batch_size,
                        false,
                    )
                    .await;
                }

                // 批量核实模式：work_ids (1..=50)
                if let Some(id_arr) = args.get("work_ids").and_then(Value::as_array) {
                    if catalog_target.is_some() {
                        return Err(crate::StudioError::InvalidInput(
                            "本轮已锁定核实对象，请继续核实这部作品。".into(),
                        ));
                    }
                    if id_arr.is_empty() {
                        return Err(crate::StudioError::InvalidInput("work_ids 不能为空。".into()));
                    }
                    if id_arr.len() > BANGUMI_BATCH_MAX_IDS {
                        return Err(crate::StudioError::InvalidInput(format!(
                            "work_ids 单次最多核实 {BANGUMI_BATCH_MAX_IDS} 部（本次传入 {} 部），请分批调用。",
                            id_arr.len()
                        )));
                    }
                    let kind = args.get("kind").and_then(Value::as_str).unwrap_or("");
                    if !policy.enabled_groups.read || !matches!(kind, "anime" | "book") {
                        return Err(crate::StudioError::InvalidInput(
                            "批量核实库内作品需要读库权限与明确的动画或书籍载体。".into(),
                        ));
                    }
                    let max_candidates = args
                        .get("max_candidates")
                        .and_then(Value::as_u64)
                        .map(|n| n as usize)
                        .unwrap_or(3);
                    let mut req_ids = Vec::with_capacity(id_arr.len());
                    let mut seen = HashSet::new();
                    for raw in id_arr {
                        let Some(wid) = raw.as_i64() else {
                            return Err(crate::StudioError::InvalidInput(
                                "work_ids 中每项必须为整数作品 ID。".into(),
                            ));
                        };
                        if seen.insert(wid) {
                            req_ids.push(wid);
                        }
                    }

                    let (targets, missing_ids) = {
                        let sync = handles
                            .sync_db
                            .lock()
                            .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                        let local = handles
                            .local_db
                            .lock()
                            .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                        let all_items = crate::repository::query_curator_items(&sync, &local)?;
                        let map: HashMap<i64, _> = all_items
                            .into_iter()
                            .filter(|it| it.kind == kind)
                            .map(|it| (it.id, it))
                            .collect();
                        let mut found = Vec::new();
                        let mut missing = Vec::new();
                        for wid in req_ids {
                            if let Some(it) = map.get(&wid) {
                                let existing_bgm = (it.external_source.as_deref() == Some("bangumi"))
                                    .then(|| it.external_id.clone())
                                    .flatten()
                                    .filter(|s| !s.trim().is_empty());
                                found.push(LocalWorkVerifyTarget {
                                    work_id: it.id,
                                    kind: it.kind.clone(),
                                    title: it.title.clone(),
                                    original_title: it.original_title.clone(),
                                    release_date: it.release_date.clone(),
                                    total_episodes: it.progress_total,
                                    media_format: it.media_format.clone(),
                                    tags: it.tags.clone(),
                                    existing_bangumi_id: existing_bgm,
                                });
                            } else {
                                missing.push(wid);
                            }
                        }
                        (found, missing)
                    };

                    let compact = args
                        .get("compact")
                        .and_then(Value::as_bool)
                        .unwrap_or(true);
                    let (mut batch_json, exact_bindings) = verify_bangumi_batch(
                        &handles.studio_data_dir,
                        targets,
                        missing_ids,
                        kind,
                        max_candidates,
                        compact,
                    )
                    .await;
                    if !exact_bindings.is_empty() {
                        if let Ok(local) = handles.local_db.lock() {
                            if let Ok(ver_id) =
                                save_verify_batch_result(&local, &input.session_id, &exact_bindings)
                            {
                                batch_json["verifyId"] = json!(ver_id);
                                batch_json["exactBindableCount"] = json!(exact_bindings.len());
                            }
                        }
                    }
                    return Ok(batch_json);
                }

                let Some(id) = args.get("work_id").and_then(Value::as_i64) else {
                    let source = args
                        .get("source")
                        .and_then(Value::as_str)
                        .unwrap_or("bangumi")
                        .trim();
                    // 查作品角色表（source="character"）属于只读补充角色资料，不受单作品核实锁拦截
                    if catalog_target.is_some() && source != "character" {
                        return Err(crate::StudioError::InvalidInput(
                            "本轮已锁定核实对象，请继续核实这部作品（查角色表请传 source=\"character\"）。".into(),
                        ));
                    }
                    let mut out = exec_search_remote_catalog(&handles.studio_data_dir, args).await?;
                    // 防剧透保护：开着防剧透时收起在线角色简介，避免后期黑幕/结局剧透喂给模型
                    if anti_spoiler_active && source == "character" {
                        if let Some(arr) = out.get_mut("results").and_then(Value::as_array_mut) {
                            for item in arr {
                                if let Some(obj) = item.as_object_mut() {
                                    if obj.get("summary").and_then(Value::as_str).is_some_and(|s| !s.trim().is_empty()) {
                                        obj.insert(
                                            "summary".into(),
                                            json!("[防剧透已折叠：角色生平简介可能含后期剧情，请仅基于用户已看进度讨论]"),
                                        );
                                    }
                                }
                            }
                        }
                        if let Some(obj) = out.as_object_mut() {
                            obj.insert("antiSpoilerRedacted".into(), json!(true));
                        }
                    }
                    return Ok(out);
                };
                let kind = args.get("kind").and_then(Value::as_str).unwrap_or("");
                if !policy.enabled_groups.read || !matches!(kind, "anime" | "book") {
                    return Err(crate::StudioError::InvalidInput("核实库内作品需要读库权限与明确的动画或书籍载体。".into()));
                }
                let target = (kind.to_string(), id);
                if catalog_target.as_ref().is_some_and(|previous| previous != &target) {
                    return Err(crate::StudioError::InvalidInput("本轮已锁定核实对象，请继续核实这部作品。".into()));
                }
                let work = {
                    let sync = handles.sync_db.lock().map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                    let local = handles.local_db.lock().map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    exec_get_work_detail_with_dir(&sync, &local, Some(&handles.studio_data_dir), &json!({"work_id": id, "kind": kind}))?
                };
                let title = work["title"].as_str().filter(|title| !title.trim().is_empty())
                    .ok_or_else(|| crate::StudioError::InvalidInput("藏品中找不到这部作品。".into()))?;
                let original = work["originalTitle"].as_str().unwrap_or("");
                let binding = (work["externalSource"] == "bangumi").then(|| work["externalId"].as_str())
                    .flatten().filter(|id| !id.is_empty());
                *catalog_target = Some(target);
                verify_bangumi_work(&handles.studio_data_dir, kind, title, original, binding).await
            }.await;
            match res {
                Ok(v) => {
                    let summary = if v.get("mode").and_then(Value::as_str) == Some("batch_verify") {
                        let cnt = v.get("count").and_then(Value::as_u64).unwrap_or(0);
                        let exact = v["summary"]["exactMatch"].as_u64().unwrap_or(0);
                        let prob = v["summary"]["probableMatch"].as_u64().unwrap_or(0);
                        let no_m = v["summary"]["noMatch"].as_u64().unwrap_or(0);
                        format!("批量核实 {cnt} 部（精确 {exact} / 疑似 {prob} / 无匹配 {no_m}）")
                    } else if v.get("mode").and_then(Value::as_str) == Some("verify_job_step") {
                        let step_cnt = v
                            .get("processedThisStep")
                            .and_then(Value::as_u64)
                            .unwrap_or(0);
                        let processed = v["summary"]["processed"].as_u64().unwrap_or(step_cnt);
                        let total = v["summary"]["total"].as_u64().unwrap_or(processed);
                        let exact = v["summary"]["exactMatch"].as_u64().unwrap_or(0);
                        let prob = v["summary"]["probableMatch"].as_u64().unwrap_or(0);
                        let no_m = v["summary"]["noMatch"].as_u64().unwrap_or(0);
                        format!(
                            "分批核实本批 {step_cnt} 部（累计 {processed}/{total} · 精确 {exact} / 待选 {prob} / 无匹配 {no_m}）"
                        )
                    } else {
                        let cnt = v.get("count").and_then(|x| x.as_u64()).unwrap_or(0);
                        match v["matchStatus"].as_str() {
                            Some("exact_match") => format!("在线返回 {cnt} 个候选，精确匹配 1 条"),
                            Some("no_exact_match") => format!("在线返回 {cnt} 个候选，无精确匹配"),
                            Some("ambiguous") => format!("在线返回 {cnt} 个候选，存在多个精确匹配"),
                            _ => format!("在线返回 {cnt} 条结果"),
                        }
                    };
                    (true, v.to_string(), summary)
                }
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        "get_discovery_feed" => {
            let res = exec_get_discovery_feed(&handles.studio_data_dir, args).await;
            match res {
                Ok(v) => {
                    let shown = v.get("shown").and_then(|x| x.as_u64()).unwrap_or(0);
                    let src = v
                        .get("sourceLabel")
                        .and_then(|x| x.as_str())
                        .unwrap_or("发现页");
                    (true, v.to_string(), format!("拉取 {shown} 条（{src}）"))
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "web_search" => {
            if !enable_deepseek_web_tools {
                return (
                    false,
                    json!({ "error": "当前模型未开启全网搜索，或当前服务商不支持独立搜索工具" }).to_string(),
                    "未开启联网搜索".into(),
                );
            }
            let key = api_key.unwrap_or("");
            match exec_deepseek_web_search(&profile.base_url, key, args).await {
                Ok((wrapped, hit_count, citations)) => {
                    for c in citations {
                        if !all_web_sources.iter().any(|x| x.url == c.url) {
                            all_web_sources.push(c);
                        }
                    }
                    (
                        true,
                        wrapped,
                        format!("全网搜索返回 {hit_count} 条结果"),
                    )
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "fetch_webpage" => {
            if !enable_deepseek_web_tools {
                return (
                    false,
                    json!({ "error": "当前模型未开启网页阅读，或当前服务商不支持独立抓取工具" }).to_string(),
                    "未开启网页阅读".into(),
                );
            }
            let key = api_key.unwrap_or("");
            match exec_deepseek_fetch_webpage(&profile.base_url, key, args).await {
                Ok((wrapped, chars, citations)) => {
                    for c in citations {
                        if !all_web_sources.iter().any(|x| x.url == c.url) {
                            all_web_sources.push(c);
                        }
                    }
                    (
                        true,
                        wrapped,
                        format!("已抓取网页正文（{chars} 字）"),
                    )
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "$web_search" => {
            // Kimi 原生 $web_search 协议：将参数原样回传给服务端执行搜索
            (true, args.to_string(), "已交由服务商执行联网检索".into())
        }
        "navigate_ui" => match exec_navigate_ui(args) {
            Ok((val, cmd)) => {
                let lbl = cmd.label.clone();
                all_ui_actions.push(cmd.clone());
                send_ev(AgentStreamEvent::UiAction { action: cmd });
                (true, val.to_string(), lbl)
            }
            Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
        },
        "apply_library_filter" => match exec_apply_library_filter(args) {
            Ok((val, cmd)) => {
                let lbl = cmd.label.clone();
                all_ui_actions.push(cmd.clone());
                send_ev(AgentStreamEvent::UiAction { action: cmd });
                (true, val.to_string(), lbl)
            }
            Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
        },
        "focus_canvas" => match exec_focus_canvas(args) {
            Ok((val, cmd)) => {
                let lbl = cmd.label.clone();
                all_ui_actions.push(cmd.clone());
                send_ev(AgentStreamEvent::UiAction { action: cmd });
                (true, val.to_string(), lbl)
            }
            Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
        },
        "update_desktop_settings" => {
            if is_regenerate {
                return (
                    true,
                    json!({
                        "ok": true,
                        "skipped": true,
                        "note": "当前处于重新生成模式，已跳过重复修改桌面设置，请基于已有结果直接整理回复。"
                    })
                    .to_string(),
                    "重生成模式已跳过重复改设置".into(),
                );
            }
            match build_desktop_settings_update(args, input.current_settings.as_ref()) {
                Ok(undo) => {
                    // 若本次修改了 coverStoragePolicy，同步持久化至 AgentPolicyConfig 以便后端回填与入库即时生效
                    if let Some(cov_pol) = undo.patch.get("coverStoragePolicy").and_then(Value::as_str) {
                        if let Ok(local) = handles.local_db.lock() {
                            if let Ok(mut cfg) = get_policy_config(&local) {
                                cfg.cover_storage_policy = cov_pol.to_string();
                                let _ = save_policy_config(&local, cfg);
                            }
                        }
                    }
                    let sum = undo.summary.clone();
                    *last_settings_undo = Some(undo.clone());
                    send_ev(AgentStreamEvent::SettingsApplied { undo });
                    (
                        true,
                        json!({ "ok": true, "applied": true, "summary": sum, "note": "已直接生效，并在界面提供了一键撤销按钮" }).to_string(),
                        sum,
                    )
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "update_user_memory" => {
            if is_regenerate {
                return (
                    true,
                    json!({
                        "ok": true,
                        "skipped": true,
                        "note": "当前处于重新生成模式，已跳过重复写入长期记忆，请直接整理回复。"
                    })
                    .to_string(),
                    "重生成模式已跳过重复写记忆".into(),
                );
            }
            let res = (|| {
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                exec_update_user_memory(&local, args)
            })();
            match res {
                Ok((val, mem_ev)) => {
                    let lbl = format!(
                        "已记忆：{}",
                        mem_ev.content.chars().take(24).collect::<String>()
                    );
                    all_memory_events.push(mem_ev.clone());
                    send_ev(AgentStreamEvent::MemoryChanged { event: mem_ev });
                    (true, val.to_string(), lbl)
                }
                Err(e) => (false, json!({ "error": e.to_string() }).to_string(), e.to_string()),
            }
        }
        "propose_work_mutations" => {
            let mode_str = args.get("mode").and_then(Value::as_str).unwrap_or("");
            if mode_str == "migrate_covers_to_remote" {
                let res = create_migrate_covers_to_remote_proposal(
                    &handles.sync_db,
                    &handles.local_db,
                    &handles.studio_data_dir,
                    &input.session_id,
                    args,
                )
                .await;
                return match res {
                    Ok(outcome) => {
                        let kept_cnt = outcome.kept_local.len();
                        let excl_cnt = outcome.excluded_user_custom_count;
                        if let Some(proposal) = outcome.proposal {
                            push_unique_proposal(created_proposal_ids, proposal.id.clone());
                            let count = proposal.diffs.len();
                            send_ev(AgentStreamEvent::ProposalCreated {
                                proposal: proposal.clone(),
                            });
                            (
                                true,
                                json!({
                                    "mode": "migrate_covers_to_remote",
                                    "status": if proposal.status == "noop" { "noop" } else { "pending_user_confirmation" },
                                    "proposalVersion": 1,
                                    "proposalId": proposal.id,
                                    "migratedCount": count,
                                    "keptLocalCount": kept_cnt,
                                    "keptLocal": outcome.kept_local,
                                    "excludedUserCustomCount": excl_cnt,
                                    "baselineStats": outcome.baseline_stats,
                                    "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                                    "note": "已生成本地封面转远程瘦身提案（仅针对程序自动落盘封面，已自动排除所有用户手传封面，支持整体撤销）。确认执行后可再提醒用户于设置或同步页安全清理无引用孤儿文件。"
                                })
                                .to_string(),
                                format!("已生成 {count} 部本地封面转远程提案（保留本地 {kept_cnt} / 排除手传 {excl_cnt}）"),
                            )
                        } else {
                            (
                                true,
                                json!({
                                    "mode": "migrate_covers_to_remote",
                                    "status": "skipped",
                                    "migratedCount": 0,
                                    "keptLocalCount": kept_cnt,
                                    "keptLocal": outcome.kept_local,
                                    "excludedUserCustomCount": excl_cnt,
                                    "baselineStats": outcome.baseline_stats,
                                    "note": "当前无可替换为远程链接的程序落盘封面（用户自定义封面已自动排除，远程不可达项已继续保留本地）。"
                                })
                                .to_string(),
                                format!("无可替换本地封面（保留本地 {kept_cnt} / 排除手传 {excl_cnt}）"),
                            )
                        }
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                    }
                };
            }

            if mode_str == "rehydrate" {
                let res = create_rehydrate_metadata_proposal(
                    &handles.sync_db,
                    &handles.local_db,
                    &handles.studio_data_dir,
                    &input.session_id,
                    args,
                )
                .await;
                return match res {
                    Ok(outcome) => {
                        let s_cnt = outcome.skipped.len();
                        let char_prop_info = if let Some(cp) = outcome.character_proposal {
                            let c_id = cp.id.clone();
                            let c_cnt = cp.diffs.len();
                            push_unique_proposal(created_proposal_ids, cp.id.clone());
                            send_ev(AgentStreamEvent::ProposalCreated { proposal: cp });
                            Some((c_id, c_cnt))
                        } else {
                            None
                        };
                        if let Some(proposal) = outcome.proposal {
                            let auto_result = (|| -> Option<(crate::agent::types::AgentProposal, Vec<String>)> {
                                let mut sync = handles.sync_db.lock().ok()?;
                                let local = handles.local_db.lock().ok()?;
                                if should_auto_approve_work_proposal(
                                    &proposal,
                                    policy,
                                    input.session_autopilot,
                                    "rehydrate_empty",
                                    is_regenerate,
                                    &local,
                                ) {
                                    commit_proposal_as_auto(&mut sync, &local, &proposal.id)
                                        .ok()
                                        .map(|a| (a.proposal, a.changed_domains))
                                } else {
                                    None
                                }
                            })();
                            if let Some((applied_prop, domains)) = auto_result {
                                push_unique_proposal(created_proposal_ids, applied_prop.id.clone());
                                let count = applied_prop.applied_count.unwrap_or(applied_prop.diffs.len());
                                let summary = format!("已自动补全 {} 部作品缺失资料（支持一键撤销）", count);
                                send_ev(AgentStreamEvent::AutoApplied {
                                    changed_domains: domains,
                                    summary: summary.clone(),
                                    proposal: applied_prop.clone(),
                                });
                                let mut payload = json!({
                                    "mode": "rehydrate",
                                    "status": "auto_applied",
                                    "proposalVersion": 1,
                                    "proposalId": applied_prop.id,
                                    "itemCount": count,
                                    "remoteCoverCount": outcome.remote_cover_count,
                                    "localFallbackCoverCount": outcome.local_fallback_cover_count,
                                    "skippedCoverCount": outcome.skipped_cover_count,
                                    "skippedCount": s_cnt,
                                    "skipped": outcome.skipped,
                                    "skippedNotInWhitelist": outcome.skipped_not_in_whitelist,
                                    "summary": summary,
                                    "note": "已按当前安全自动审批策略落库（支持界面一键撤销），可直接继续后续步骤或汇报结果。"
                                });
                                if let Some((cid, ccnt)) = &char_prop_info {
                                    payload["characterProposalId"] = json!(cid);
                                    payload["characterItemCount"] = json!(ccnt);
                                }
                                return (true, payload.to_string(), format!("已自动补全 {count} 部作品资料"));
                            }

                            push_unique_proposal(created_proposal_ids, proposal.id.clone());
                            let count = proposal.diffs.len();
                            send_ev(AgentStreamEvent::ProposalCreated {
                                proposal: proposal.clone(),
                            });
                            let mut payload = json!({
                                "mode": "rehydrate",
                                "status": if proposal.status == "noop" { "noop" } else { "pending_user_confirmation" },
                                "proposalVersion": 1,
                                "proposalId": proposal.id,
                                "itemCount": count,
                                "remoteCoverCount": outcome.remote_cover_count,
                                "localFallbackCoverCount": outcome.local_fallback_cover_count,
                                "skippedCoverCount": outcome.skipped_cover_count,
                                "skippedCount": s_cnt,
                                "skipped": outcome.skipped,
                                "skippedNotInWhitelist": outcome.skipped_not_in_whitelist,
                                "overlapWarnings": proposal.overlap_warnings,
                                "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                                "note": "已生成外部元数据补全提案（仅填空值、绝不覆盖用户手填值；封面默认采用探测通过的远程链接以控制备份体积，支持整体撤销）。"
                            });
                            if let Some((cid, ccnt)) = &char_prop_info {
                                payload["characterProposalId"] = json!(cid);
                                payload["characterItemCount"] = json!(ccnt);
                            }
                            (
                                true,
                                payload.to_string(),
                                format!("已生成 {count} 部元数据补全提案（远程封面 {} / 本地回落 {} / 跳过 {}）", outcome.remote_cover_count, outcome.local_fallback_cover_count, s_cnt),
                            )
                        } else if let Some((cid, ccnt)) = char_prop_info {
                            (
                                true,
                                json!({
                                    "mode": "rehydrate",
                                    "status": "pending_user_confirmation",
                                    "proposalVersion": 1,
                                    "proposalId": cid,
                                    "characterProposalId": cid,
                                    "characterItemCount": ccnt,
                                    "itemCount": ccnt,
                                    "skippedCount": s_cnt,
                                    "skipped": outcome.skipped,
                                    "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                                    "note": "基础元数据已完整，已为缺失角色表的作品生成角色补全提案（支持一键撤销）。"
                                })
                                .to_string(),
                                format!("已生成 {ccnt} 位角色补全提案"),
                            )
                        } else {
                            (
                                true,
                                json!({
                                    "mode": "rehydrate",
                                    "status": "skipped",
                                    "itemCount": 0,
                                    "remoteCoverCount": outcome.remote_cover_count,
                                    "localFallbackCoverCount": outcome.local_fallback_cover_count,
                                    "skippedCoverCount": outcome.skipped_cover_count,
                                    "skippedCount": s_cnt,
                                    "skipped": outcome.skipped,
                                    "skippedNotInWhitelist": outcome.skipped_not_in_whitelist,
                                    "note": "本次未生成新修改，请查看 skipped 中的逐条原因（如本地已完整或外部源缺少对应字段）。"
                                })
                                .to_string(),
                                format!("无需回填或外部源缺字段（跳过 {s_cnt} 项）"),
                            )
                        }
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                    }
                };
            }
            let is_bulk_bind = mode_str == "bulk_bind"
                || args.get("bindings").is_some()
                || args.get("from_verify_ids").is_some()
                || args.get("from_verify_id").is_some();
            if is_bulk_bind {
                let from_verify = args.get("from_verify_ids").is_some() || args.get("from_verify_id").is_some();
                let res = (|| {
                    let sync = handles
                        .sync_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                    let local = handles
                        .local_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    create_bulk_bind_proposal(&sync, &local, &input.session_id, args)
                })();
                return match res {
                    Ok(outcome) => {
                        let c_cnt = outcome.conflicts.len();
                        let s_cnt = outcome.skipped.len();
                        if let Some(proposal) = outcome.proposal {
                            if from_verify && c_cnt == 0 {
                                let auto_bind = (|| -> Option<(crate::agent::types::AgentProposal, Vec<String>)> {
                                    let mut sync = handles.sync_db.lock().ok()?;
                                    let local = handles.local_db.lock().ok()?;
                                    if should_auto_approve_work_proposal(
                                        &proposal,
                                        policy,
                                        input.session_autopilot,
                                        "high_confidence_bind",
                                        is_regenerate,
                                        &local,
                                    ) {
                                        commit_proposal_as_auto(&mut sync, &local, &proposal.id)
                                            .ok()
                                            .map(|a| (a.proposal, a.changed_domains))
                                    } else {
                                        None
                                    }
                                })();
                                if let Some((applied_prop, domains)) = auto_bind {
                                    push_unique_proposal(created_proposal_ids, applied_prop.id.clone());
                                    let count = applied_prop.applied_count.unwrap_or(applied_prop.diffs.len());
                                    let summary = format!("已自动完成 {count} 项高置信度外部绑定（支持一键撤销）");
                                    send_ev(AgentStreamEvent::AutoApplied {
                                        changed_domains: domains,
                                        summary: summary.clone(),
                                        proposal: applied_prop.clone(),
                                    });
                                    return (
                                        true,
                                        json!({
                                            "mode": "bulk_bind",
                                            "status": "auto_applied",
                                            "proposalId": applied_prop.id,
                                            "itemCount": count,
                                            "conflictsCount": c_cnt,
                                            "skippedCount": s_cnt,
                                            "summary": summary,
                                            "note": "高置信度绑定已按安全自动审批策略生效（支持一键撤销），可直接继续后续回填步骤。"
                                        })
                                        .to_string(),
                                        format!("已自动绑定 {count} 部作品"),
                                    );
                                }
                            }
                            push_unique_proposal(created_proposal_ids, proposal.id.clone());
                            let count = proposal.diffs.len();
                            send_ev(AgentStreamEvent::ProposalCreated {
                                proposal: proposal.clone(),
                            });
                            (
                                true,
                                json!({
                                    "mode": "bulk_bind",
                                    "status": "pending_user_confirmation",
                                    "proposalVersion": 1,
                                    "proposalId": proposal.id,
                                    "itemCount": count,
                                    "conflictsCount": c_cnt,
                                    "skippedCount": s_cnt,
                                    "conflicts": outcome.conflicts,
                                    "skipped": outcome.skipped,
                                    "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                                    "note": "已生成批量绑定确认卡片（支持一键确认落库与整体撤销）；冲突项与跳过项已单列，不阻断其余条目。"
                                })
                                .to_string(),
                                format!("已生成 {count} 项批量绑定提案（冲突 {c_cnt} / 跳过 {s_cnt}）"),
                            )
                        } else {
                            (
                                true,
                                json!({
                                    "mode": "bulk_bind",
                                    "status": "no_valid_bindings",
                                    "itemCount": 0,
                                    "conflictsCount": c_cnt,
                                    "skippedCount": s_cnt,
                                    "conflicts": outcome.conflicts,
                                    "skipped": outcome.skipped,
                                    "note": "本批次没有可新绑定的有效条目，请先核查冲突或跳过原因。"
                                })
                                .to_string(),
                                format!("无有效可绑条目（冲突 {c_cnt} / 跳过 {s_cnt}）"),
                            )
                        }
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                    }
                };
            }

            let enriched_args = enrich_create_args_from_bangumi_with_policy(
                &handles.studio_data_dir,
                args,
                &policy.cover_storage_policy,
                policy.filter_metadata_tags,
            )
            .await;
            let is_direct_append_tags_only = enriched_args
                .get("operations")
                .and_then(Value::as_array)
                .is_some_and(|ops| {
                    !ops.is_empty()
                        && ops.iter().all(|o| {
                            let Some(map) = o.as_object() else {
                                return false;
                            };
                            let act = map.get("action").and_then(Value::as_str).unwrap_or("update");
                            if act != "update" || !map.contains_key("add_tags") {
                                return false;
                            }
                            map.keys().all(|k| {
                                matches!(
                                    k.as_str(),
                                    "action"
                                        | "kind"
                                        | "work_id"
                                        | "id"
                                        | "expected_title"
                                        | "add_tags"
                                        | "_expectedRevision"
                                        | "_fieldBaselines"
                                        | "_identityTitle"
                                )
                            })
                        })
                });
            let auto_category: &'static str = if (args.get("result_id").is_some()
                && args
                    .get("patch")
                    .and_then(Value::as_object)
                    .is_some_and(|p| p.len() == 1 && p.contains_key("add_tags")))
                || is_direct_append_tags_only
            {
                "append_tags_only"
            } else if enriched_args
                .get("operations")
                .and_then(Value::as_array)
                .is_some_and(|ops| {
                    !ops.is_empty()
                        && ops.iter().all(|o| {
                            o.get("action").and_then(Value::as_str) == Some("create")
                                && o.get("external_id").and_then(Value::as_str).is_some_and(|s| !s.trim().is_empty())
                        })
                })
            {
                "create_from_catalog"
            } else {
                "other_safe"
            };
            let res: crate::Result<(crate::agent::types::AgentProposal, Option<Vec<String>>, bool)> =
                (|| {
                    let mut sync = handles
                        .sync_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                    let local = handles
                        .local_db
                        .lock()
                        .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                    let (proposal, is_quick_log) =
                        create_work_mutations_proposal(&sync, &local, &input.session_id, &enriched_args)?;
                    if proposal.status == "noop" {
                        return Ok((proposal, None, false));
                    }
                    let eff_cat = if is_quick_log && !used_external_data {
                        "quick_log"
                    } else {
                        auto_category
                    };
                    if should_auto_approve_work_proposal(
                        &proposal,
                        policy,
                        input.session_autopilot,
                        eff_cat,
                        is_regenerate,
                        &local,
                    ) {
                        let applied = commit_proposal_as_auto(&mut sync, &local, &proposal.id)?;
                        Ok((applied.proposal, Some(applied.changed_domains), eff_cat == "quick_log"))
                    } else {
                        Ok((proposal, None, false))
                    }
                })();
            match res {
                Ok((proposal, Some(domains), is_ql)) => {
                    push_unique_proposal(created_proposal_ids, proposal.id.clone());
                    let summary = if is_ql {
                        format!("已快捷记账：{}（支持一键撤销）", proposal.title)
                    } else {
                        format!("已自动执行：{}（支持一键撤销）", proposal.title)
                    };
                    if is_ql {
                        send_ev(AgentStreamEvent::QuickApplied {
                            changed_domains: domains,
                            summary: summary.clone(),
                            proposal: proposal.clone(),
                        });
                    } else {
                        send_ev(AgentStreamEvent::AutoApplied {
                            changed_domains: domains,
                            summary: summary.clone(),
                            proposal: proposal.clone(),
                        });
                    }
                    (
                        true,
                        json!({
                            "status": if is_ql { "quick_applied" } else { "auto_applied" },
                            "proposalId": proposal.id,
                            "summary": summary
                        })
                        .to_string(),
                        if is_ql { "快捷记账已生效" } else { "已按策略自动生效" }.into(),
                    )
                }
                Ok((proposal, None, _)) => {
                    push_unique_proposal(created_proposal_ids, proposal.id.clone());
                    let count = proposal.diffs.len();
                    send_ev(AgentStreamEvent::ProposalCreated {
                        proposal: proposal.clone(),
                    });
                    if proposal.status == "noop" {
                        return (
                            true,
                            json!({
                                "status": "noop",
                                "proposalVersion": 1,
                                "proposalId": proposal.id,
                                "itemCount": count,
                                "note": "所有目标字段当前值均已达成，已自动标记为无需重复执行（no-op）。"
                            })
                            .to_string(),
                            "目标已达成（无需执行）".into(),
                        );
                    }
                    let sample_diffs: Vec<Value> = proposal
                        .diffs
                        .iter()
                        .take(3)
                        .map(|d| {
                            let clip = |s: &str| -> String {
                                let mut out: String = s.chars().take(120).collect();
                                if s.chars().count() > 120 {
                                    out.push('…');
                                }
                                out
                            };
                            json!({
                                "target": clip(&d.target_label),
                                "action": clip(&d.action_label),
                                "before": clip(&d.before),
                                "after": clip(&d.after),
                            })
                        })
                        .collect();
                    (
                        true,
                        json!({
                            "status": "pending_user_confirmation",
                            "proposalVersion": 1,
                            "proposalId": proposal.id,
                            "itemCount": count,
                            "sampleDiffs": sample_diffs,
                            "overlapWarnings": proposal.overlap_warnings,
                            "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                            "note": "已生成变更预览卡片（sampleDiffs 展示了实际生效前后效果），请向用户简要解释改动并提醒点击「确认执行」"
                        })
                        .to_string(),
                        format!("已生成 {count} 项待确认提案"),
                    )
                }
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        "propose_series_mutations"
        | "propose_tag_mutations"
        | "propose_character_mutations"
        | "propose_merge_duplicates" => {
            let res: crate::Result<(crate::agent::types::AgentProposal, Vec<Value>)> = (|| {
                let sync = handles
                    .sync_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("sync_db 锁异常".into()))?;
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                let mut resolved_series = Vec::new();
                let proposal = match tool_name {
                    "propose_series_mutations" => {
                        let p = create_series_mutations_proposal(&sync, &local, &input.session_id, args)?;
                        let existing_series = crate::studio_features::list_series_manage(&sync).unwrap_or_default();
                        if let Some(ops) = p.ops.as_array() {
                            for op in ops {
                                if op["action"] == "assign_series" {
                                    if let Some(sname) = op["series_name"].as_str().map(str::trim) {
                                        let found_id = existing_series
                                            .iter()
                                            .find(|s| s.name == sname)
                                            .map(|s| s.id);
                                        resolved_series.push(json!({
                                            "seriesName": sname,
                                            "seriesId": found_id,
                                            "willCreate": found_id.is_none(),
                                        }));
                                    }
                                }
                            }
                        }
                        p
                    }
                    "propose_tag_mutations" => {
                        create_tag_mutations_proposal(&sync, &local, &input.session_id, args)?
                    }
                    "propose_character_mutations" => {
                        create_character_mutations_proposal(&sync, &local, &input.session_id, args)?
                    }
                    _ => create_merge_duplicates_proposal(&sync, &local, &input.session_id, args)?,
                };
                Ok((proposal, resolved_series))
            })();
            match res {
                Ok((proposal, resolved_series)) => {
                    push_unique_proposal(created_proposal_ids, proposal.id.clone());
                    let count = proposal.diffs.len();
                    send_ev(AgentStreamEvent::ProposalCreated {
                        proposal: proposal.clone(),
                    });
                    let mut out = json!({
                        "status": "pending_user_confirmation",
                        "proposalVersion": 1,
                        "proposalId": proposal.id,
                        "itemCount": count,
                        "statusNote": "此回执仅代表提案刚创建时的初始状态；用户可能在界面随时确认或撤销，后续回答前务必以最新运行时摘要或 get_proposal_status(proposal_id) 核实真实状态。",
                        "note": "已生成变更预览卡片，请向用户说明方案并等待点击确认"
                    });
                    if tool_name == "propose_character_mutations" {
                        if let Some(ops) = proposal.ops.as_array() {
                            let mut works_map = std::collections::BTreeMap::<i64, String>::new();
                            for op in ops {
                                if let (Some(aid), Some(wt)) = (
                                    op.get("anime_id").and_then(Value::as_i64),
                                    op.get("_resolvedWorkTitle").and_then(Value::as_str),
                                ) {
                                    works_map.insert(aid, wt.to_string());
                                }
                            }
                            if !works_map.is_empty() {
                                out["affectedWorks"] = Value::Array(
                                    works_map
                                        .into_iter()
                                        .map(|(wid, title)| json!({ "workId": wid, "title": title }))
                                        .collect(),
                                );
                            }
                        }
                    }
                    if !resolved_series.is_empty() {
                        out["resolvedSeries"] = Value::Array(resolved_series);
                        out["note"] = json!("已生成系列变更预览卡片；新建系列的 seriesId 将在用户确认执行后生成，可通过 get_proposal_status 查看。");
                    }
                    (
                        true,
                        out.to_string(),
                        format!("已生成 {count} 项待确认提案"),
                    )
                }
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        "cancel_proposal" => {
            let res = (|| {
                let local = handles
                    .local_db
                    .lock()
                    .map_err(|_| crate::StudioError::InvalidInput("local_db 锁异常".into()))?;
                let pid = args.get("proposal_id").and_then(Value::as_str).unwrap_or("");
                let reason = args.get("reason").and_then(Value::as_str);
                cancel_pending_proposal(&local, &input.session_id, pid, reason)
            })();
            match res {
                Ok(v) => (true, v.to_string(), "已撤回待确认提案".into()),
                Err(e) => {
                    let msg = e.to_string();
                    (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
                }
            }
        }
        other => (
            false,
            tool_error_json("UNKNOWN_TOOL", format!("未知或未授权的工具 `{other}`")),
            "未知工具".into(),
        ),
    }
}

fn format_json_result(res: crate::Result<Value>, ok_summary: &str) -> (bool, String, String) {
    match res {
        Ok(v) => (true, v.to_string(), ok_summary.to_string()),
        Err(e) => {
            let msg = e.to_string();
            (false, tool_error_json(classify_error_code(&msg), msg.clone()), msg)
        }
    }
}

fn build_usage(
    prompt: u64,
    completion: u64,
    cached: u64,
    reported_steps: u32,
    estimated_steps: u32,
    request_count: u32,
    compaction_tokens: u64,
) -> Option<TokenUsage> {
    if prompt == 0 && completion == 0 {
        return None;
    }
    let usage_source = match (reported_steps > 0, estimated_steps > 0) {
        (true, false) => "reported",
        (false, true) => "estimated",
        (true, true) => "mixed",
        (false, false) => "missing",
    };
    Some(TokenUsage {
        prompt_tokens: prompt,
        completion_tokens: completion,
        total_tokens: prompt + completion,
        cached_tokens: cached,
        estimated: estimated_steps > 0,
        usage_source: Some(usage_source.to_string()),
        request_count: (request_count > 0).then_some(request_count),
        compaction_tokens: (compaction_tokens > 0).then_some(compaction_tokens),
    })
}

#[allow(clippy::too_many_arguments)]
fn persist_or_append_assistant_message(
    handles: &AgentRuntimeHandles,
    session_id: &str,
    regenerate_message_id: Option<&str>,
    content: String,
    reasoning_content: String,
    tool_traces: Vec<ToolTraceItem>,
    ui_actions: Vec<UiActionCommand>,
    settings_undo: Option<SettingsUndoRecord>,
    memory_events: Vec<MemoryChangeEvent>,
    proposal_ids: Vec<String>,
    diagnostic_error: Option<DiagnosticError>,
    token_usage: Option<TokenUsage>,
    stopped: bool,
    web_sources: Vec<WebSourceCitation>,
) -> Option<AgentMessage> {
    let reasoning_opt = if reasoning_content.trim().is_empty() {
        None
    } else {
        Some(reasoning_content)
    };
    let primary_proposal_id = proposal_ids.first().cloned();
    let now = Utc::now().to_rfc3339();

    if let Some(regen_id) = regenerate_message_id {
        let ver = MessageVersion {
            content,
            reasoning_content: reasoning_opt,
            tool_traces,
            ui_actions,
            settings_undo,
            memory_events,
            proposal_id: primary_proposal_id,
            proposal_ids,
            diagnostic_error,
            token_usage,
            stopped,
            web_sources,
            created_at: now,
        };
        if let Ok(local) = handles.local_db.lock() {
            if let Ok(updated) = append_assistant_version(&local, session_id, regen_id, ver) {
                return Some(updated);
            }
        }
        return None;
    }

    let msg = AgentMessage {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        role: "assistant".into(),
        content,
        reasoning_content: reasoning_opt,
        tool_calls: None,
        tool_call_id: None,
        tool_name: None,
        tool_traces,
        ui_actions,
        settings_undo,
        memory_events,
        proposal_id: primary_proposal_id,
        proposal_ids,
        diagnostic_error,
        token_usage,
        versions: vec![],
        active_version_idx: 0,
        runtime_context: None,
        stopped,
        web_sources,
        created_at: now,
    };
    if let Ok(local) = handles.local_db.lock() {
        let _ = insert_message(&local, &msg);
    }
    Some(msg)
}

fn contains_raw_tool_markup(text: &str) -> bool {
    text.contains("DSML｜")
        || text.contains("DSML|")
        || text.contains("<|tool_call")
        || text.contains("<｜tool▁call")
        || text.contains("<function_calls>")
        || text.contains("<invoke name=")
}

fn strip_raw_tool_markup(text: &str) -> String {
    let mut out = String::new();
    for line in text.lines() {
        let t = line.trim();
        if t.contains("DSML")
            || t.starts_with("<|tool_call")
            || t.starts_with("<｜tool")
            || t.starts_with("<function_calls")
            || t.starts_with("</function_calls")
            || t.starts_with("<invoke ")
            || t.starts_with("</invoke")
            || t.starts_with("<parameter ")
            || t.starts_with("</parameter")
        {
            continue;
        }
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(line);
    }
    let cleaned = out.trim().to_string();
    if cleaned.is_empty() {
        "已完成信息检索，但在整理最终答复时模型输出格式异常，请点下方「重新生成」再试一次。".into()
    } else {
        cleaned
    }
}

#[derive(Debug, PartialEq, Eq)]
enum CallSignatureEffect {
    Record,
    Skip,
    Clear,
}

fn call_signature_effect(group: &str, tool_name: &str, payload: &str) -> CallSignatureEffect {
    if matches!(group, "write" | "settings")
        || matches!(tool_name, "update_user_memory" | "cancel_proposal")
    {
        return CallSignatureEffect::Clear;
    }
    if tool_name == "search_remote_catalog"
        && serde_json::from_str::<Value>(payload).is_ok_and(|value| {
            value["mode"] == "verify_job_step"
                && value["processedThisStep"].as_u64().unwrap_or_default() > 0
                && value["summary"]["remaining"].as_u64().unwrap_or_default() > 0
        })
    {
        return CallSignatureEffect::Skip;
    }
    CallSignatureEffect::Record
}

#[cfg(test)]
mod call_signature_tests {
    use super::*;

    #[test]
    fn records_stalled_and_finished_batches_but_allows_progressing_batches() {
        for (processed, remaining, expected) in [
            (50, 70, CallSignatureEffect::Skip),
            (50, 0, CallSignatureEffect::Record),
            (0, 70, CallSignatureEffect::Record),
        ] {
            let payload = json!({
                "mode": "verify_job_step",
                "processedThisStep": processed,
                "summary": {"remaining": remaining},
            });
            assert_eq!(call_signature_effect("online", "search_remote_catalog", &payload.to_string()), expected);
        }
    }

    #[test]
    fn records_regular_reads_and_clears_after_writes() {
        assert_eq!(call_signature_effect("online", "search_remote_catalog", "{}"), CallSignatureEffect::Record);
        assert_eq!(call_signature_effect("write", "propose_work_mutations", "{}"), CallSignatureEffect::Clear);
    }
}
