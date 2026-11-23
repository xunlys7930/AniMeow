use crate::agent::providers::{mask_api_key, normalize_base_url};
use crate::agent::types::{
    normalize_verify_decision, AgentMemory, AgentMessage, AgentPolicyConfig, AgentProfile,
    AgentProposal, AgentRunEvent, AgentSession, AgentSlashCommand, AgentTrajectorySummary,
    DiagnosticError, McpServerConfig, MemoryChangeEvent, MessageVersion, ProbeResult,
    ProposalItemDiff, SaveAgentProfileInput, SaveMcpServerInput, SettingsUndoRecord, TokenUsage,
    ToolCallRecord, ToolTraceItem, UiActionCommand, VerifyJobDetail, VerifyJobItem,
    VerifyJobSummary, WebSourceCitation,
};
use crate::dpapi::{decrypt_secret_dpapi, encrypt_secret_dpapi};
use crate::repository::CuratorItem;
use crate::{Result, StudioError};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use uuid::Uuid;

const AGENT_KEYS_FILE: &str = "agent_keys.dpapi.json";
const POLICY_KV_KEY: &str = "agent_policy_v1";

#[derive(Debug, Default, Serialize, Deserialize)]
struct KeyVaultDisk {
    #[serde(default)]
    keys: HashMap<String, String>,
    #[serde(default)]
    hints: HashMap<String, String>,
}

fn key_vault_path(studio_data_dir: &Path) -> PathBuf {
    studio_data_dir.join(AGENT_KEYS_FILE)
}

fn load_key_vault(studio_data_dir: &Path) -> KeyVaultDisk {
    let path = key_vault_path(studio_data_dir);
    let Ok(raw) = std::fs::read_to_string(&path) else {
        return KeyVaultDisk::default();
    };
    serde_json::from_str(&raw).unwrap_or_default()
}

fn save_key_vault(studio_data_dir: &Path, vault: &KeyVaultDisk) -> Result<()> {
    std::fs::create_dir_all(studio_data_dir)?;
    let path = key_vault_path(studio_data_dir);
    let tmp = studio_data_dir.join(format!("{AGENT_KEYS_FILE}.tmp"));
    let json = serde_json::to_string_pretty(vault)?;
    std::fs::write(&tmp, json)?;
    std::fs::rename(&tmp, &path)?;
    Ok(())
}

pub fn save_profile_api_key(studio_data_dir: &Path, profile_id: &str, api_key: &str) -> Result<()> {
    let trimmed = api_key.trim();
    let mut vault = load_key_vault(studio_data_dir);
    if trimmed.is_empty() {
        vault.keys.remove(profile_id);
        vault.hints.remove(profile_id);
    } else {
        let encrypted = encrypt_secret_dpapi(trimmed)?;
        vault.keys.insert(profile_id.to_string(), encrypted);
        if let Some(hint) = mask_api_key(trimmed) {
            vault.hints.insert(profile_id.to_string(), hint);
        }
    }
    save_key_vault(studio_data_dir, &vault)
}

pub fn load_profile_api_key(studio_data_dir: &Path, profile_id: &str) -> Result<Option<String>> {
    let vault = load_key_vault(studio_data_dir);
    let Some(enc) = vault.keys.get(profile_id) else {
        return Ok(None);
    };
    let plain = decrypt_secret_dpapi(enc)?;
    Ok(Some(plain))
}

pub fn delete_profile_api_key(studio_data_dir: &Path, profile_id: &str) -> Result<()> {
    let mut vault = load_key_vault(studio_data_dir);
    vault.keys.remove(profile_id);
    vault.hints.remove(profile_id);
    save_key_vault(studio_data_dir, &vault)
}

pub fn list_profiles(conn: &Connection, studio_data_dir: &Path) -> Result<Vec<AgentProfile>> {
    let vault = load_key_vault(studio_data_dir);
    let mut stmt = conn.prepare(
        "SELECT id, name, provider_id, base_url, model, temperature, max_steps, max_turn_tokens,
                supports_tools, supports_reasoning, enable_web_search, last_probe_json, is_default, updated_at
         FROM agent_profiles
         ORDER BY is_default DESC, updated_at DESC",
    )?;
    let rows = stmt.query_map([], |row| {
        let id: String = row.get(0)?;
        let last_probe_json: Option<String> = row.get(11)?;
        let last_probe: Option<ProbeResult> = last_probe_json
            .as_deref()
            .and_then(|s| serde_json::from_str(s).ok());
        Ok(AgentProfile {
            has_api_key: vault.keys.contains_key(&id),
            api_key_hint: vault.hints.get(&id).cloned(),
            id,
            name: row.get(1)?,
            provider_id: row.get(2)?,
            base_url: row.get(3)?,
            model: row.get(4)?,
            temperature: row.get(5)?,
            max_steps: row.get::<_, i64>(6)?.clamp(2, 12) as u32,
            max_turn_tokens: row.get::<_, i64>(7)?.clamp(8000, 300_000) as u32,
            supports_tools: row.get::<_, i64>(8)? != 0,
            supports_reasoning: row.get::<_, i64>(9)? != 0,
            enable_web_search: row.get::<_, i64>(10).unwrap_or(0) != 0,
            last_probe,
            is_default: row.get::<_, i64>(12)? != 0,
            updated_at: row.get(13)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn get_profile(
    conn: &Connection,
    studio_data_dir: &Path,
    profile_id: Option<&str>,
) -> Result<Option<AgentProfile>> {
    let all = list_profiles(conn, studio_data_dir)?;
    if let Some(pid) = profile_id {
        if let Some(found) = all.iter().find(|p| p.id == pid) {
            return Ok(Some(found.clone()));
        }
    }
    Ok(all.into_iter().next())
}

pub fn save_profile(
    conn: &Connection,
    studio_data_dir: &Path,
    input: SaveAgentProfileInput,
) -> Result<AgentProfile> {
    let normalized_url = normalize_base_url(&input.base_url)?;
    let model = input.model.trim().to_string();
    if model.is_empty() {
        return Err(StudioError::InvalidInput("请填写或选择模型名称".into()));
    }
    let name = if input.name.trim().is_empty() {
        model.clone()
    } else {
        input.name.trim().to_string()
    };
    let id = input
        .id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    let existing_count: i64 = conn.query_row("SELECT COUNT(*) FROM agent_profiles", [], |r| r.get(0))?;
    let make_default = input.is_default.unwrap_or(existing_count == 0);
    if make_default {
        conn.execute("UPDATE agent_profiles SET is_default = 0", [])?;
    }

    let temperature = input.temperature.unwrap_or(0.4).clamp(0.0, 2.0);
    let max_steps = input.max_steps.unwrap_or(8).clamp(2, 12);
    let max_turn_tokens = input.max_turn_tokens.unwrap_or(60_000).clamp(8_000, 300_000);
    let supports_tools = input.supports_tools.unwrap_or(true);
    let supports_reasoning = input.supports_reasoning.unwrap_or(false);
    let enable_web_search = input.enable_web_search.unwrap_or(false);
    let last_probe_json = input
        .last_probe
        .as_ref()
        .and_then(|p| serde_json::to_string(p).ok());
    let now = Utc::now().to_rfc3339();

    conn.execute(
        "INSERT INTO agent_profiles (
            id, name, provider_id, base_url, model, temperature, max_steps, max_turn_tokens,
            supports_tools, supports_reasoning, enable_web_search, last_probe_json, is_default, updated_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            provider_id = excluded.provider_id,
            base_url = excluded.base_url,
            model = excluded.model,
            temperature = excluded.temperature,
            max_steps = excluded.max_steps,
            max_turn_tokens = excluded.max_turn_tokens,
            supports_tools = excluded.supports_tools,
            supports_reasoning = excluded.supports_reasoning,
            enable_web_search = excluded.enable_web_search,
            last_probe_json = COALESCE(excluded.last_probe_json, agent_profiles.last_probe_json),
            is_default = excluded.is_default,
            updated_at = excluded.updated_at",
        params![
            id,
            name,
            input.provider_id.trim(),
            normalized_url,
            model,
            temperature,
            max_steps as i64,
            max_turn_tokens as i64,
            if supports_tools { 1i64 } else { 0i64 },
            if supports_reasoning { 1i64 } else { 0i64 },
            if enable_web_search { 1i64 } else { 0i64 },
            last_probe_json,
            if make_default { 1i64 } else { 0i64 },
            now
        ],
    )?;

    if input.clear_api_key.unwrap_or(false) {
        delete_profile_api_key(studio_data_dir, &id)?;
    } else if let Some(ref key) = input.api_key {
        if !key.trim().is_empty() {
            save_profile_api_key(studio_data_dir, &id, key)?;
        }
    }

    get_profile(conn, studio_data_dir, Some(&id))?
        .ok_or_else(|| StudioError::InvalidInput("保存模型档案后读取失败".into()))
}

pub fn update_profile_probe(
    conn: &Connection,
    profile_id: &str,
    probe: &ProbeResult,
) -> Result<()> {
    let json = serde_json::to_string(probe)?;
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "UPDATE agent_profiles
         SET supports_tools = ?1, supports_reasoning = ?2, last_probe_json = ?3, updated_at = ?4
         WHERE id = ?5",
        params![
            if probe.supports_tools { 1i64 } else { 0i64 },
            if probe.supports_reasoning { 1i64 } else { 0i64 },
            json,
            now,
            profile_id
        ],
    )?;
    Ok(())
}

pub fn delete_profile(conn: &Connection, studio_data_dir: &Path, profile_id: &str) -> Result<()> {
    conn.execute("DELETE FROM agent_profiles WHERE id = ?1", params![profile_id])?;
    let _ = delete_profile_api_key(studio_data_dir, profile_id);
    // 若删掉了默认档案，自动把最近更新的一条设为默认
    let has_default: i64 = conn.query_row(
        "SELECT COUNT(*) FROM agent_profiles WHERE is_default = 1",
        [],
        |r| r.get(0),
    )?;
    if has_default == 0 {
        let first_id: Option<String> = conn
            .query_row(
                "SELECT id FROM agent_profiles ORDER BY updated_at DESC LIMIT 1",
                [],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(fid) = first_id {
            conn.execute(
                "UPDATE agent_profiles SET is_default = 1 WHERE id = ?1",
                params![fid],
            )?;
        }
    }
    Ok(())
}

pub fn get_policy_config(conn: &Connection) -> Result<AgentPolicyConfig> {
    let raw: Option<String> = conn
        .query_row(
            "SELECT v FROM agent_kv WHERE k = ?1",
            params![POLICY_KV_KEY],
            |r| r.get(0),
        )
        .optional()?;
    let Some(s) = raw else {
        return Ok(AgentPolicyConfig::default());
    };
    Ok(serde_json::from_str(&s).unwrap_or_default())
}

pub fn save_policy_config(conn: &Connection, mut cfg: AgentPolicyConfig) -> Result<AgentPolicyConfig> {
    if !matches!(
        cfg.trust_mode.as_str(),
        "strict" | "balanced" | "auto_safe" | "quick_log"
    ) {
        cfg.trust_mode = "balanced".into();
    }
    if !matches!(cfg.cover_storage_policy.as_str(), "remote" | "local" | "auto") {
        cfg.cover_storage_policy = "remote".into();
    }
    cfg.auto_approve.max_items_per_auto_batch = cfg.auto_approve.max_items_per_auto_batch.clamp(1, 200);
    cfg.auto_compact_tokens = cfg.auto_compact_tokens.clamp(8_000, 200_000);
    cfg.max_history_turns = cfg.max_history_turns.clamp(4, 30);
    if cfg.custom_instructions.chars().count() > 2000 {
        cfg.custom_instructions = cfg.custom_instructions.chars().take(2000).collect();
    }
    let json = serde_json::to_string(&cfg)?;
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO agent_kv (k, v, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at",
        params![POLICY_KV_KEY, json, now],
    )?;
    Ok(cfg)
}

pub fn list_sessions(
    conn: &Connection,
    include_archived: bool,
    keyword: Option<&str>,
) -> Result<Vec<AgentSession>> {
    let kw = keyword
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    let mut stmt = conn.prepare(
        "SELECT s.id, s.title, s.profile_id, s.persona_id, s.pinned, s.archived,
                s.rolling_summary, s.compacted_up_to_msg_id,
                s.total_prompt_tokens, s.total_completion_tokens,
                s.created_at, s.updated_at,
                (SELECT COUNT(*) FROM agent_messages m WHERE m.session_id = s.id AND m.role IN ('user', 'assistant')) AS msg_cnt
         FROM agent_sessions s
         WHERE (?1 = 1 OR s.archived = 0)
         ORDER BY s.pinned DESC, s.updated_at DESC
         LIMIT 120",
    )?;
    let rows = stmt.query_map(params![if include_archived { 1i64 } else { 0i64 }], |row| {
        Ok(AgentSession {
            id: row.get(0)?,
            title: row.get(1)?,
            profile_id: row.get(2)?,
            persona_id: row.get(3)?,
            pinned: row.get::<_, i64>(4)? != 0,
            archived: row.get::<_, i64>(5)? != 0,
            rolling_summary: row.get(6)?,
            compacted_up_to_msg_id: row.get(7)?,
            total_prompt_tokens: row.get::<_, i64>(8)?.max(0) as u64,
            total_completion_tokens: row.get::<_, i64>(9)?.max(0) as u64,
            created_at: row.get(10)?,
            updated_at: row.get(11)?,
            message_count: row.get::<_, i64>(12)?.max(0) as usize,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        let sess = r?;
        if let Some(ref k) = kw {
            let title_hit = sess.title.to_ascii_lowercase().contains(k);
            let msg_hit = if title_hit {
                true
            } else {
                let pattern = format!("%{k}%");
                conn.query_row(
                    "SELECT 1 FROM agent_messages WHERE session_id = ?1 AND lower(content) LIKE ?2 LIMIT 1",
                    params![sess.id, pattern],
                    |_| Ok(1i64),
                )
                .optional()?
                .is_some()
            };
            if !msg_hit {
                continue;
            }
        }
        out.push(sess);
    }
    Ok(out)
}

pub fn get_session(conn: &Connection, session_id: &str) -> Result<Option<AgentSession>> {
    conn.query_row(
        "SELECT s.id, s.title, s.profile_id, s.persona_id, s.pinned, s.archived,
                s.rolling_summary, s.compacted_up_to_msg_id,
                s.total_prompt_tokens, s.total_completion_tokens,
                s.created_at, s.updated_at,
                (SELECT COUNT(*) FROM agent_messages m WHERE m.session_id = s.id AND m.role IN ('user', 'assistant')) AS msg_cnt
         FROM agent_sessions s
         WHERE s.id = ?1",
        params![session_id],
        |row| {
            Ok(AgentSession {
                id: row.get(0)?,
                title: row.get(1)?,
                profile_id: row.get(2)?,
                persona_id: row.get(3)?,
                pinned: row.get::<_, i64>(4)? != 0,
                archived: row.get::<_, i64>(5)? != 0,
                rolling_summary: row.get(6)?,
                compacted_up_to_msg_id: row.get(7)?,
                total_prompt_tokens: row.get::<_, i64>(8)?.max(0) as u64,
                total_completion_tokens: row.get::<_, i64>(9)?.max(0) as u64,
                created_at: row.get(10)?,
                updated_at: row.get(11)?,
                message_count: row.get::<_, i64>(12)?.max(0) as usize,
            })
        },
    )
    .optional()
    .map_err(Into::into)
}

pub fn create_session(
    conn: &Connection,
    title: Option<&str>,
    profile_id: Option<&str>,
    persona_id: Option<&str>,
) -> Result<AgentSession> {
    let id = Uuid::new_v4().to_string();
    let now = Utc::now().to_rfc3339();
    let title = title
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("新对话")
        .to_string();
    let persona_id = persona_id.unwrap_or("curator").to_string();
    conn.execute(
        "INSERT INTO agent_sessions (
            id, title, profile_id, persona_id, pinned, archived, total_prompt_tokens, total_completion_tokens, created_at, updated_at
        ) VALUES (?1, ?2, ?3, ?4, 0, 0, 0, 0, ?5, ?5)",
        params![id, title, profile_id, persona_id, now],
    )?;
    Ok(AgentSession {
        id,
        title,
        profile_id: profile_id.map(str::to_string),
        persona_id,
        pinned: false,
        archived: false,
        rolling_summary: None,
        compacted_up_to_msg_id: None,
        message_count: 0,
        total_prompt_tokens: 0,
        total_completion_tokens: 0,
        created_at: now.clone(),
        updated_at: now,
    })
}

pub fn update_session_meta(
    conn: &Connection,
    session_id: &str,
    title: Option<&str>,
    pinned: Option<bool>,
    archived: Option<bool>,
    persona_id: Option<&str>,
    profile_id: Option<&str>,
    rolling_summary: Option<Option<&str>>,
) -> Result<AgentSession> {
    let now = Utc::now().to_rfc3339();
    if let Some(t) = title.map(str::trim).filter(|s| !s.is_empty()) {
        conn.execute(
            "UPDATE agent_sessions SET title = ?1, updated_at = ?2 WHERE id = ?3",
            params![t, now, session_id],
        )?;
    }
    if let Some(p) = pinned {
        conn.execute(
            "UPDATE agent_sessions SET pinned = ?1, updated_at = ?2 WHERE id = ?3",
            params![if p { 1i64 } else { 0i64 }, now, session_id],
        )?;
    }
    if let Some(a) = archived {
        conn.execute(
            "UPDATE agent_sessions SET archived = ?1, updated_at = ?2 WHERE id = ?3",
            params![if a { 1i64 } else { 0i64 }, now, session_id],
        )?;
    }
    if let Some(pid) = persona_id.map(str::trim).filter(|s| !s.is_empty()) {
        conn.execute(
            "UPDATE agent_sessions SET persona_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![pid, now, session_id],
        )?;
    }
    if let Some(prof) = profile_id.map(str::trim).filter(|s| !s.is_empty()) {
        conn.execute(
            "UPDATE agent_sessions SET profile_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![prof, now, session_id],
        )?;
    }
    if let Some(summary_opt) = rolling_summary {
        let clean = summary_opt.map(str::trim).filter(|s| !s.is_empty());
        if clean.is_none() {
            conn.execute(
                "UPDATE agent_sessions SET rolling_summary = NULL, compacted_up_to_msg_id = NULL, updated_at = ?1 WHERE id = ?2",
                params![now, session_id],
            )?;
        } else {
            conn.execute(
                "UPDATE agent_sessions SET rolling_summary = ?1, updated_at = ?2 WHERE id = ?3",
                params![clean, now, session_id],
            )?;
        }
    }
    get_session(conn, session_id)?
        .ok_or_else(|| StudioError::InvalidInput("对话不存在".into()))
}

pub fn save_session_compaction(
    conn: &Connection,
    session_id: &str,
    summary: &str,
    compacted_up_to_msg_id: &str,
) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "UPDATE agent_sessions
         SET rolling_summary = ?1, compacted_up_to_msg_id = ?2, updated_at = ?3
         WHERE id = ?4",
        params![summary.trim(), compacted_up_to_msg_id, now, session_id],
    )?;
    Ok(())
}

pub fn clear_session_messages(conn: &Connection, session_id: &str) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    conn.execute("DELETE FROM agent_proposals WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_messages WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_run_events WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_tool_artifacts WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_verify_jobs WHERE session_id = ?1", params![session_id])?;
    conn.execute(
        "UPDATE agent_sessions
         SET rolling_summary = NULL, compacted_up_to_msg_id = NULL, updated_at = ?1
         WHERE id = ?2",
        params![now, session_id],
    )?;
    Ok(())
}

/// 删除指定消息及其之后的全部消息（用于「重新生成最后一条回复」或「修改某条问题并重发」）
pub fn truncate_session_from_message(conn: &Connection, session_id: &str, message_id: &str) -> Result<usize> {
    let rowid: Option<i64> = conn
        .query_row(
            "SELECT rowid FROM agent_messages WHERE session_id = ?1 AND id = ?2",
            params![session_id, message_id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(rid) = rowid else {
        return Ok(0);
    };
    let deleted = conn.execute(
        "DELETE FROM agent_messages WHERE session_id = ?1 AND rowid >= ?2",
        params![session_id, rid],
    )?;
    Ok(deleted)
}

pub fn export_session_markdown(conn: &Connection, session_id: &str) -> Result<String> {
    let sess = get_session(conn, session_id)?
        .ok_or_else(|| StudioError::InvalidInput("对话不存在".into()))?;
    let msgs = load_session_messages(conn, session_id)?;
    let mut md = format!("# {}\n\n> 导出自追番喵桌面端 AI 智能体 · 创建时间：{}\n\n", sess.title, sess.created_at);
    if let Some(ref sum) = sess.rolling_summary {
        md.push_str("## 对话滚动摘要\n\n");
        md.push_str(sum);
        md.push_str("\n\n---\n\n");
    }
    for m in msgs {
        match m.role.as_str() {
            "user" => {
                md.push_str("### 我\n\n");
                md.push_str(&m.content);
                md.push_str("\n\n");
            }
            "assistant" => {
                let is_intermediate_tool_call = m
                    .tool_calls
                    .as_ref()
                    .map(|v| !v.is_empty())
                    .unwrap_or(false)
                    && m.content.trim().is_empty()
                    && m.tool_traces.is_empty();
                if is_intermediate_tool_call {
                    continue;
                }
                md.push_str("### 追番喵智能体\n\n");
                if !m.tool_traces.is_empty() {
                    md.push_str(&format!(
                        "*已执行 {} 步工具：{}*\n\n",
                        m.tool_traces.len(),
                        m.tool_traces
                            .iter()
                            .map(|t| t.label.as_str())
                            .collect::<Vec<_>>()
                            .join(" → ")
                    ));
                }
                md.push_str(&m.content);
                md.push_str("\n\n");
            }
            _ => {}
        }
    }
    Ok(md)
}

/// 零 Token 本地分析藏品库，提炼 3～5 条可勾选导入的初始口味记忆草稿
pub fn draft_memories_from_library(
    sync_conn: &Connection,
    local_conn: &Connection,
) -> Result<Vec<(String, String)>> {
    let items = crate::repository::query_curator_items(sync_conn, local_conn)?;
    if items.is_empty() {
        return Ok(vec![]);
    }
    let mut drafts = Vec::<(String, String)>::new();

    // 1. 载体偏好
    let mut kind_counts: HashMap<&str, usize> = HashMap::new();
    let mut tag_scores: HashMap<String, (f64, usize)> = HashMap::new();
    let mut high_works: Vec<(&str, f64)> = Vec::new();
    let mut low_works: Vec<(&str, f64)> = Vec::new();
    let mut scores: Vec<f64> = Vec::new();

    for it in &items {
        *kind_counts.entry(it.kind.as_str()).or_insert(0) += 1;
        if let Some(sc) = it.personal_score {
            scores.push(sc);
            if sc >= 8.5 {
                high_works.push((it.title.as_str(), sc));
            } else if sc <= 5.5 {
                low_works.push((it.title.as_str(), sc));
            }
            for t in &it.tags {
                let e = tag_scores.entry(t.clone()).or_insert((0.0, 0));
                e.0 += sc;
                e.1 += 1;
            }
        }
    }

    // 高分偏爱题材标签
    let mut fav_tags: Vec<(String, f64, usize)> = tag_scores
        .into_iter()
        .filter(|(_, (_, cnt))| *cnt >= 3)
        .map(|(k, (sum, cnt))| (k, sum / cnt as f64, cnt))
        .collect();
    fav_tags.sort_by(|a, b| {
        b.1.partial_cmp(&a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| b.2.cmp(&a.2))
    });
    if !fav_tags.is_empty() {
        let top: Vec<String> = fav_tags
            .iter()
            .take(5)
            .map(|(name, avg, cnt)| format!("{name}({avg:.1}分/{cnt}部)"))
            .collect();
        drafts.push((
            "preference".into(),
            format!("偏爱的高分题材标签：{}", top.join("、")),
        ));
    }

    // 代表性挚爱作品
    high_works.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    if !high_works.is_empty() {
        let top_titles: Vec<String> = high_works
            .iter()
            .take(5)
            .map(|(t, sc)| format!("《{t}》({sc:.1})"))
            .collect();
        drafts.push((
            "preference".into(),
            format!("高分挚爱代表作：{}", top_titles.join("、")),
        ));
    }

    // 打分严苛度画像
    if scores.len() >= 5 {
        let avg = scores.iter().sum::<f64>() / scores.len() as f64;
        let style = if avg < 7.2 {
            "打分偏严格，8 分以上才算非常认可"
        } else if avg > 8.2 {
            "打分偏宽容鼓励，9 分以上代表心头好"
        } else {
            "打分分布均衡，8.5 分以上代表强推神作"
        };
        drafts.push((
            "rating_rule".into(),
            format!("历史已评分 {} 部，个人均分 {:.2} 分（{}）", scores.len(), avg, style),
        ));
    }

    // 低分避雷参考
    low_works.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
    if low_works.len() >= 2 {
        let sample: Vec<String> = low_works
            .iter()
            .take(3)
            .map(|(t, sc)| format!("《{t}》({sc:.1})"))
            .collect();
        drafts.push((
            "avoid".into(),
            format!("历史评分较低的雷区参考：{}", sample.join("、")),
        ));
    }

    Ok(drafts)
}

pub fn touch_session_usage(
    conn: &Connection,
    session_id: &str,
    maybe_title: Option<&str>,
    prompt_delta: u64,
    completion_delta: u64,
) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    if let Some(title) = maybe_title {
        let trimmed: String = title.trim().chars().take(28).collect();
        if !trimmed.is_empty() {
            conn.execute(
                "UPDATE agent_sessions
                 SET title = CASE WHEN title = '新对话' THEN ?1 ELSE title END,
                     total_prompt_tokens = total_prompt_tokens + ?2,
                     total_completion_tokens = total_completion_tokens + ?3,
                     updated_at = ?4
                 WHERE id = ?5",
                params![trimmed, prompt_delta as i64, completion_delta as i64, now, session_id],
            )?;
            return Ok(());
        }
    }
    conn.execute(
        "UPDATE agent_sessions
         SET total_prompt_tokens = total_prompt_tokens + ?1,
             total_completion_tokens = total_completion_tokens + ?2,
             updated_at = ?3
         WHERE id = ?4",
        params![prompt_delta as i64, completion_delta as i64, now, session_id],
    )?;
    Ok(())
}

pub fn delete_session(conn: &Connection, session_id: &str) -> Result<()> {
    conn.execute("DELETE FROM agent_proposals WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_messages WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_run_events WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_tool_artifacts WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_verify_jobs WHERE session_id = ?1", params![session_id])?;
    conn.execute("DELETE FROM agent_sessions WHERE id = ?1", params![session_id])?;
    Ok(())
}

pub fn insert_message(conn: &Connection, msg: &AgentMessage) -> Result<()> {
    let tool_calls_json = msg
        .tool_calls
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let tool_traces_json = if msg.tool_traces.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.tool_traces).ok()
    };
    let ui_actions_json = if msg.ui_actions.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.ui_actions).ok()
    };
    let settings_undo_json = msg
        .settings_undo
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let memory_events_json = if msg.memory_events.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.memory_events).ok()
    };
    let diagnostic_error_json = msg
        .diagnostic_error
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let token_usage_json = msg
        .token_usage
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let proposal_ids_json = if msg.proposal_ids.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.proposal_ids).ok()
    };
    let versions_json = if msg.versions.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.versions).ok()
    };
    let web_sources_json = if msg.web_sources.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.web_sources).ok()
    };

    conn.execute(
        "INSERT INTO agent_messages (
            id, session_id, role, content, reasoning_content, tool_calls_json, tool_call_id,
            tool_name, tool_traces_json, ui_actions_json, settings_undo_json, memory_events_json,
            proposal_id, proposal_ids_json, diagnostic_error_json, token_usage_json, versions_json, active_version_idx,
            runtime_context, stopped, web_sources_json, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21, ?22)",
        params![
            msg.id,
            msg.session_id,
            msg.role,
            msg.content,
            msg.reasoning_content,
            tool_calls_json,
            msg.tool_call_id,
            msg.tool_name,
            tool_traces_json,
            ui_actions_json,
            settings_undo_json,
            memory_events_json,
            msg.proposal_id,
            proposal_ids_json,
            diagnostic_error_json,
            token_usage_json,
            versions_json,
            msg.active_version_idx as i64,
            msg.runtime_context,
            if msg.stopped { 1i64 } else { 0i64 },
            web_sources_json,
            msg.created_at
        ],
    )?;
    Ok(())
}

pub fn load_session_messages(conn: &Connection, session_id: &str) -> Result<Vec<AgentMessage>> {
    let mut stmt = conn.prepare(
        "SELECT id, session_id, role, content, reasoning_content, tool_calls_json, tool_call_id,
                tool_name, tool_traces_json, ui_actions_json, settings_undo_json, memory_events_json,
                proposal_id, proposal_ids_json, diagnostic_error_json, token_usage_json, versions_json, active_version_idx,
                runtime_context, COALESCE(stopped, 0), web_sources_json, created_at
         FROM agent_messages
         WHERE session_id = ?1
         ORDER BY rowid ASC",
    )?;
    let rows = stmt.query_map(params![session_id], |row| {
        let tool_calls_json: Option<String> = row.get(5)?;
        let tool_traces_json: Option<String> = row.get(8)?;
        let ui_actions_json: Option<String> = row.get(9)?;
        let settings_undo_json: Option<String> = row.get(10)?;
        let mem_events_json: Option<String> = row.get(11)?;
        let proposal_id: Option<String> = row.get(12)?;
        let proposal_ids_json: Option<String> = row.get(13)?;
        let mut proposal_ids = proposal_ids_json
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok())
            .unwrap_or_default();
        if proposal_ids.is_empty() {
            if let Some(ref pid) = proposal_id {
                proposal_ids.push(pid.clone());
            }
        }
        let diag_json: Option<String> = row.get(14)?;
        let usage_json: Option<String> = row.get(15)?;
        let versions_json: Option<String> = row.get(16)?;
        let active_idx = row.get::<_, i64>(17).unwrap_or(0).max(0) as usize;
        let stopped = row.get::<_, i64>(19).unwrap_or(0) != 0;
        let web_sources_json: Option<String> = row.get(20)?;

        Ok(AgentMessage {
            id: row.get(0)?,
            session_id: row.get(1)?,
            role: row.get(2)?,
            content: row.get(3)?,
            reasoning_content: row.get(4)?,
            tool_calls: tool_calls_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<ToolCallRecord>>(s).ok()),
            tool_call_id: row.get(6)?,
            tool_name: row.get(7)?,
            tool_traces: tool_traces_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<ToolTraceItem>>(s).ok())
                .unwrap_or_default(),
            ui_actions: ui_actions_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<UiActionCommand>>(s).ok())
                .unwrap_or_default(),
            settings_undo: settings_undo_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<SettingsUndoRecord>(s).ok()),
            memory_events: mem_events_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<MemoryChangeEvent>>(s).ok())
                .unwrap_or_default(),
            proposal_id,
            proposal_ids,
            diagnostic_error: diag_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<DiagnosticError>(s).ok()),
            token_usage: usage_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<TokenUsage>(s).ok()),
            versions: versions_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<MessageVersion>>(s).ok())
                .unwrap_or_default(),
            active_version_idx: active_idx,
            runtime_context: row.get(18)?,
            stopped,
            web_sources: web_sources_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<WebSourceCitation>>(s).ok())
                .unwrap_or_default(),
            created_at: row.get(21)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

fn message_to_version(msg: &AgentMessage) -> MessageVersion {
    MessageVersion {
        content: msg.content.clone(),
        reasoning_content: msg.reasoning_content.clone(),
        tool_traces: msg.tool_traces.clone(),
        ui_actions: msg.ui_actions.clone(),
        settings_undo: msg.settings_undo.clone(),
        memory_events: msg.memory_events.clone(),
        proposal_id: msg.proposal_id.clone(),
        proposal_ids: msg.proposal_ids.clone(),
        diagnostic_error: msg.diagnostic_error.clone(),
        token_usage: msg.token_usage.clone(),
        stopped: msg.stopped,
        web_sources: msg.web_sources.clone(),
        created_at: msg.created_at.clone(),
    }
}

/// 无损重新生成：将新生成的回答追加为指定 assistant 消息的新微版本，旧版本完整保留可切换！
pub fn append_assistant_version(
    conn: &Connection,
    session_id: &str,
    message_id: &str,
    new_ver: MessageVersion,
) -> Result<AgentMessage> {
    let msgs = load_session_messages(conn, session_id)?;
    let mut target = msgs
        .into_iter()
        .find(|m| m.id == message_id && m.role == "assistant")
        .ok_or_else(|| StudioError::InvalidInput("未找到要重新生成的回复消息".into()))?;

    if target.versions.is_empty() {
        target.versions.push(message_to_version(&target));
    }
    target.versions.push(new_ver.clone());
    target.active_version_idx = target.versions.len() - 1;
    apply_version_to_message(&mut target, &new_ver);
    persist_message_versions_update(conn, &target)?;
    Ok(target)
}

/// 切换某条 assistant 消息的当前展示版本（◀ 1/2 ▶）
pub fn switch_message_version(
    conn: &Connection,
    session_id: &str,
    message_id: &str,
    version_idx: usize,
) -> Result<AgentMessage> {
    let msgs = load_session_messages(conn, session_id)?;
    let mut target = msgs
        .into_iter()
        .find(|m| m.id == message_id && m.role == "assistant")
        .ok_or_else(|| StudioError::InvalidInput("未找到对应回复消息".into()))?;
    if version_idx >= target.versions.len() {
        return Err(StudioError::InvalidInput("目标版本索引越界".into()));
    }
    let chosen = target.versions[version_idx].clone();
    target.active_version_idx = version_idx;
    apply_version_to_message(&mut target, &chosen);
    persist_message_versions_update(conn, &target)?;
    Ok(target)
}

fn apply_version_to_message(msg: &mut AgentMessage, ver: &MessageVersion) {
    msg.content = ver.content.clone();
    msg.reasoning_content = ver.reasoning_content.clone();
    msg.tool_traces = ver.tool_traces.clone();
    msg.ui_actions = ver.ui_actions.clone();
    msg.settings_undo = ver.settings_undo.clone();
    msg.memory_events = ver.memory_events.clone();
    msg.proposal_id = ver.proposal_id.clone();
    msg.proposal_ids = if !ver.proposal_ids.is_empty() {
        ver.proposal_ids.clone()
    } else {
        ver.proposal_id.iter().cloned().collect()
    };
    msg.diagnostic_error = ver.diagnostic_error.clone();
    msg.token_usage = ver.token_usage.clone();
    msg.stopped = ver.stopped;
    msg.web_sources = ver.web_sources.clone();
}

fn persist_message_versions_update(conn: &Connection, msg: &AgentMessage) -> Result<()> {
    let tool_traces_json = if msg.tool_traces.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.tool_traces).ok()
    };
    let ui_actions_json = if msg.ui_actions.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.ui_actions).ok()
    };
    let settings_undo_json = msg
        .settings_undo
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let memory_events_json = if msg.memory_events.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.memory_events).ok()
    };
    let proposal_ids_json = if msg.proposal_ids.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.proposal_ids).ok()
    };
    let diagnostic_error_json = msg
        .diagnostic_error
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let token_usage_json = msg
        .token_usage
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let versions_json = serde_json::to_string(&msg.versions)?;
    let web_sources_json = if msg.web_sources.is_empty() {
        None
    } else {
        serde_json::to_string(&msg.web_sources).ok()
    };

    conn.execute(
        "UPDATE agent_messages
         SET content = ?1,
             reasoning_content = ?2,
             tool_traces_json = ?3,
             ui_actions_json = ?4,
             settings_undo_json = ?5,
             memory_events_json = ?6,
             proposal_id = ?7,
             proposal_ids_json = ?8,
             diagnostic_error_json = ?9,
             token_usage_json = ?10,
             versions_json = ?11,
             active_version_idx = ?12,
             stopped = ?13,
             web_sources_json = ?14
         WHERE id = ?15",
        params![
            msg.content,
            msg.reasoning_content,
            tool_traces_json,
            ui_actions_json,
            settings_undo_json,
            memory_events_json,
            msg.proposal_id,
            proposal_ids_json,
            diagnostic_error_json,
            token_usage_json,
            versions_json,
            msg.active_version_idx as i64,
            if msg.stopped { 1i64 } else { 0i64 },
            web_sources_json,
            msg.id
        ],
    )?;
    Ok(())
}

/// 会话分叉（Fork）：从任意消息节点复制上下文衍生一条全新的平行对话，原对话一字不丢！
pub fn fork_session_from_message(
    conn: &Connection,
    source_session_id: &str,
    up_to_message_id: Option<&str>,
    custom_title: Option<&str>,
) -> Result<AgentSession> {
    let src = get_session(conn, source_session_id)?
        .ok_or_else(|| StudioError::InvalidInput("源对话不存在".into()))?;
    let msgs = load_session_messages(conn, source_session_id)?;

    let end_idx = if let Some(mid) = up_to_message_id.map(str::trim).filter(|s| !s.is_empty()) {
        msgs.iter()
            .position(|m| m.id == mid)
            .ok_or_else(|| StudioError::InvalidInput("未找到指定的分叉消息节点".into()))?
            + 1
    } else {
        msgs.len()
    };

    let title = custom_title
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| format!("{} (分叉)", src.title));

    let tx = conn.unchecked_transaction()?;
    let conn = &tx;
    let new_sess = create_session(
        conn,
        Some(&title),
        src.profile_id.as_deref(),
        Some(&src.persona_id),
    )?;

    static ARTIFACT_ID: std::sync::LazyLock<regex::Regex> =
        std::sync::LazyLock::new(|| regex::Regex::new(r"\b(art|ver)_[a-f0-9]{12}\b").unwrap());
    let mut artifact_map = HashMap::<String, String>::new();
    let mut id_map = HashMap::<String, String>::new();
    for m in &msgs[..end_idx] {
        let serialized = serde_json::to_string(m)?;
        for hit in ARTIFACT_ID.find_iter(&serialized) {
            let id = hit.as_str();
            if artifact_map.contains_key(id) {
                continue;
            }
            let prefix = if id.starts_with("ver_") { "ver" } else { "art" };
            let copy_id = format!("{prefix}_{}", &Uuid::new_v4().simple().to_string()[..12]);
            let copied = conn.execute(
                "INSERT INTO agent_tool_artifacts (id, session_id, tool_name, char_len, full_content, preview, created_at)
                 SELECT ?1, ?2, tool_name, char_len, full_content, replace(preview, id, ?1), created_at
                 FROM agent_tool_artifacts WHERE id = ?3 AND session_id = ?4",
                params![copy_id, new_sess.id, id, source_session_id],
            )?;
            artifact_map.insert(id.to_owned(), if copied == 1 { copy_id } else { id.to_owned() });
        }
        let copied_message = ARTIFACT_ID.replace_all(&serialized, |captures: &regex::Captures| {
            artifact_map[&captures[0]].clone()
        });
        let mut cloned: AgentMessage = serde_json::from_str(&copied_message)?;
        let new_id = Uuid::new_v4().to_string();
        id_map.insert(m.id.clone(), new_id.clone());
        cloned.id = new_id;
        cloned.session_id = new_sess.id.clone();
        insert_message(conn, &cloned)?;
    }

    // 若源对话的 rolling_summary 截断点位于分叉点之前，一并继承
    if let (Some(ref sum), Some(ref cutoff)) = (&src.rolling_summary, &src.compacted_up_to_msg_id) {
        if let Some(mapped_cutoff) = id_map.get(cutoff) {
            save_session_compaction(conn, &new_sess.id, sum, mapped_cutoff)?;
        }
    } else if let Some(ref sum) = src.rolling_summary {
        let _ = update_session_meta(conn, &new_sess.id, None, None, None, None, None, Some(Some(sum)));
    }

    let result = get_session(conn, &new_sess.id)?
        .ok_or_else(|| StudioError::InvalidInput("分叉对话读取失败".into()))?;
    tx.commit()?;
    Ok(result)
}

// ============================================================================
// 工具超长结果归档存储（Tool Artifact 溢写与分页回读）
// ============================================================================

pub fn save_tool_artifact(
    conn: &Connection,
    session_id: &str,
    tool_name: &str,
    full_content: &str,
    preview_chars: usize,
) -> Result<(String, String)> {
    let id = format!("art_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let char_len = full_content.chars().count();
    let head: String = full_content.chars().take(preview_chars).collect();
    let preview = format!(
        "{head}\n\n[超长输出归档通知: 完整输出共 {char_len} 字符已落盘为 `{id}`，如需查看后半段细节请调用 read_tool_artifact(artifact_id=\"{id}\", offset={preview_chars}, limit=3000)]"
    );
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO agent_tool_artifacts (id, session_id, tool_name, char_len, full_content, preview, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![id, session_id, tool_name, char_len as i64, full_content, preview, now],
    )?;
    Ok((id, preview))
}

pub fn read_tool_artifact(
    conn: &Connection,
    artifact_id: &str,
    offset: usize,
    limit: usize,
) -> Result<Value> {
    let row: Option<(String, String, i64)> = conn
        .query_row(
            "SELECT tool_name, full_content, char_len FROM agent_tool_artifacts WHERE id = ?1",
            params![artifact_id.trim()],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    let Some((tool_name, full_content, char_len)) = row else {
        return Err(StudioError::InvalidInput(format!(
            "Artifact `{artifact_id}` 不存在或已过期"
        )));
    };
    let clamped_limit = limit.clamp(200, 4500);
    let chars: Vec<char> = full_content.chars().collect();
    let total = chars.len();
    let start = offset.min(total);
    let end = (start + clamped_limit).min(total);
    let slice: String = chars[start..end].iter().collect();
    Ok(serde_json::json!({
        "artifactId": artifact_id,
        "toolName": tool_name,
        "totalChars": char_len,
        "offset": start,
        "returnedChars": end - start,
        "hasMore": end < total,
        "nextOffset": if end < total { Some(end) } else { None::<usize> },
        "content": slice,
    }))
}

pub fn load_library_result(conn: &Connection, id: &str, session: Option<&str>) -> Result<Vec<CuratorItem>> {
    let content: Option<String> = conn.query_row(
        "SELECT full_content FROM agent_tool_artifacts
         WHERE id = ?1 AND tool_name = 'library_result' AND (?2 IS NULL OR session_id = ?2)",
        params![id, session],
        |row| row.get(0),
    ).optional()?;
    let content = content.ok_or_else(|| StudioError::InvalidInput("这份藏品清单已失效，请重新筛选。".into()))?;
    Ok(serde_json::from_str(&content)?)
}

pub fn save_verify_batch_result(
    conn: &Connection,
    session_id: &str,
    bindings: &[Value],
) -> Result<String> {
    let id = format!("ver_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let full_content = serde_json::to_string(bindings)?;
    let char_len = full_content.chars().count() as i64;
    let preview = format!("verify_batch_result: {} exact matches", bindings.len());
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    conn.execute(
        "INSERT INTO agent_tool_artifacts (id, session_id, tool_name, char_len, full_content, preview, created_at)
         VALUES (?1, ?2, 'verify_batch_result', ?3, ?4, ?5, ?6)",
        params![id, session_id, char_len, full_content, preview, now],
    )?;
    Ok(id)
}

pub fn load_verify_batch_bindings(
    conn: &Connection,
    id: &str,
    session: Option<&str>,
) -> Result<Vec<Value>> {
    let content: Option<String> = conn
        .query_row(
            "SELECT full_content FROM agent_tool_artifacts
             WHERE id = ?1 AND tool_name = 'verify_batch_result' AND (?2 IS NULL OR session_id = ?2)",
            params![id.trim(), session],
            |row| row.get(0),
        )
        .optional()?;
    let content = content.ok_or_else(|| {
        StudioError::InvalidInput(format!("核实句柄 `{id}` 不存在或已过期，请重新批量核实。"))
    })?;
    Ok(serde_json::from_str(&content)?)
}

pub fn save_proposal_record(
    conn: &Connection,
    proposal: &AgentProposal,
    rollback_snapshot: Option<&Value>,
) -> Result<()> {
    let ops_json = serde_json::to_string(&proposal.ops)?;
    let diff_json = serde_json::to_string(&proposal.diffs)?;
    let snap_json = rollback_snapshot.and_then(|v| serde_json::to_string(v).ok());
    let applied_indices_json = proposal
        .applied_indices
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let overlap_json = if proposal.overlap_warnings.is_empty() {
        None
    } else {
        serde_json::to_string(&proposal.overlap_warnings).ok()
    };
    conn.execute(
        "INSERT INTO agent_proposals (
            id, session_id, kind, title, summary, status, ops_json, diff_preview_json,
            rollback_snapshot_json, applied_count, applied_indices_json,
            last_attempt_failed, last_error, batch_group_id, overlap_warnings_json,
            created_at, applied_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17)
        ON CONFLICT(id) DO UPDATE SET
            status = excluded.status,
            rollback_snapshot_json = COALESCE(excluded.rollback_snapshot_json, agent_proposals.rollback_snapshot_json),
            applied_count = COALESCE(excluded.applied_count, agent_proposals.applied_count),
            applied_indices_json = COALESCE(excluded.applied_indices_json, agent_proposals.applied_indices_json),
            last_attempt_failed = excluded.last_attempt_failed,
            last_error = excluded.last_error,
            batch_group_id = COALESCE(excluded.batch_group_id, agent_proposals.batch_group_id),
            overlap_warnings_json = COALESCE(excluded.overlap_warnings_json, agent_proposals.overlap_warnings_json),
            applied_at = excluded.applied_at",
        params![
            proposal.id,
            proposal.session_id,
            proposal.kind,
            proposal.title,
            proposal.summary,
            proposal.status,
            ops_json,
            diff_json,
            snap_json,
            proposal.applied_count.map(|n| n as i64),
            applied_indices_json,
            if proposal.last_attempt_failed { 1i64 } else { 0i64 },
            proposal.last_error,
            proposal.batch_group_id,
            overlap_json,
            proposal.created_at,
            proposal.applied_at
        ],
    )?;
    Ok(())
}

/// 覆盖恢复主库后使所有历史待确认提案失效，防止跨库同 ID 误写
pub fn invalidate_pending_proposals_on_overwrite(conn: &Connection) -> Result<usize> {
    let changed = conn.execute(
        "UPDATE agent_proposals SET status = 'expired' WHERE status = 'pending'",
        [],
    )?;
    Ok(changed)
}

pub fn set_proposal_batch_group(conn: &Connection, proposal_id: &str, batch_group_id: &str) -> Result<()> {
    conn.execute(
        "UPDATE agent_proposals SET batch_group_id = ?1 WHERE id = ?2",
        params![batch_group_id, proposal_id],
    )?;
    Ok(())
}

pub fn mark_proposal_noop(conn: &Connection, proposal_id: &str, note: Option<&str>) -> Result<()> {
    conn.execute(
        "UPDATE agent_proposals
         SET status = 'noop', last_attempt_failed = 0, last_error = ?1, applied_count = 0
         WHERE id = ?2 AND status = 'pending'",
        params![note, proposal_id],
    )?;
    Ok(())
}

pub fn load_proposals_by_batch_group(conn: &Connection, batch_group_id: &str) -> Result<Vec<AgentProposal>> {
    let mut stmt = conn.prepare(
        "SELECT id, session_id, kind, title, summary, status, ops_json, diff_preview_json,
                rollback_snapshot_json, created_at, applied_at, applied_count, applied_indices_json,
                last_attempt_failed, last_error, batch_group_id, overlap_warnings_json
         FROM agent_proposals
         WHERE batch_group_id = ?1
         ORDER BY applied_at DESC, created_at DESC",
    )?;
    let rows = stmt.query_map(params![batch_group_id], |row| {
        let ops_json: String = row.get(6)?;
        let diff_json: String = row.get(7)?;
        let snap_json: Option<String> = row.get(8)?;
        let status: String = row.get(5)?;
        let applied_count: Option<usize> = row.get::<_, Option<i64>>(11)?.map(|v| v.max(0) as usize);
        let applied_indices: Option<Vec<usize>> = row
            .get::<_, Option<String>>(12)?
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<usize>>(s).ok());
        let overlap_warnings: Vec<String> = row
            .get::<_, Option<String>>(16)?
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok())
            .unwrap_or_default();
        let can_revert = crate::agent::types::is_proposal_applied_status(&status) && snap_json.is_some();
        Ok(AgentProposal {
            id: row.get(0)?,
            session_id: row.get(1)?,
            kind: row.get(2)?,
            title: row.get(3)?,
            summary: row.get(4)?,
            can_revert,
            status,
            ops: serde_json::from_str(&ops_json).unwrap_or(Value::Null),
            diffs: serde_json::from_str::<Vec<ProposalItemDiff>>(&diff_json).unwrap_or_default(),
            applied_count,
            applied_indices,
            last_attempt_failed: row.get::<_, i64>(13).unwrap_or(0) != 0,
            last_error: row.get(14)?,
            batch_group_id: row.get(15)?,
            overlap_warnings,
            created_at: row.get(9)?,
            applied_at: row.get(10)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn load_proposal(conn: &Connection, proposal_id: &str) -> Result<Option<(AgentProposal, Option<Value>)>> {
    let row = conn
        .query_row(
            "SELECT id, session_id, kind, title, summary, status, ops_json, diff_preview_json,
                    rollback_snapshot_json, created_at, applied_at, applied_count, applied_indices_json,
                    last_attempt_failed, last_error, batch_group_id, overlap_warnings_json
             FROM agent_proposals
             WHERE id = ?1",
            params![proposal_id],
            |row| {
                let ops_json: String = row.get(6)?;
                let diff_json: String = row.get(7)?;
                let snap_json: Option<String> = row.get(8)?;
                let snap_val: Option<Value> = snap_json
                    .as_deref()
                    .and_then(|s| serde_json::from_str(s).ok());
                let status: String = row.get(5)?;
                let can_revert =
                    crate::agent::types::is_proposal_applied_status(&status) && snap_val.is_some();
                let applied_count: Option<usize> = row.get::<_, Option<i64>>(11)?.map(|v| v.max(0) as usize);
                let applied_indices: Option<Vec<usize>> = row
                    .get::<_, Option<String>>(12)?
                    .as_deref()
                    .and_then(|s| serde_json::from_str::<Vec<usize>>(s).ok());
                let overlap_warnings: Vec<String> = row
                    .get::<_, Option<String>>(16)?
                    .as_deref()
                    .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok())
                    .unwrap_or_default();
                Ok((
                    AgentProposal {
                        id: row.get(0)?,
                        session_id: row.get(1)?,
                        kind: row.get(2)?,
                        title: row.get(3)?,
                        summary: row.get(4)?,
                        status,
                        ops: serde_json::from_str(&ops_json).unwrap_or(Value::Null),
                        diffs: serde_json::from_str::<Vec<ProposalItemDiff>>(&diff_json)
                            .unwrap_or_default(),
                        can_revert,
                        applied_count,
                        applied_indices,
                        last_attempt_failed: row.get::<_, i64>(13).unwrap_or(0) != 0,
                        last_error: row.get(14)?,
                        batch_group_id: row.get(15)?,
                        overlap_warnings,
                        created_at: row.get(9)?,
                        applied_at: row.get(10)?,
                    },
                    snap_val,
                ))
            },
        )
        .optional()?;
    Ok(row)
}

pub fn load_session_proposals(conn: &Connection, session_id: &str) -> Result<Vec<AgentProposal>> {
    let mut stmt = conn.prepare(
        "SELECT id, session_id, kind, title, summary, status, ops_json, diff_preview_json,
                rollback_snapshot_json, created_at, applied_at, applied_count, applied_indices_json,
                last_attempt_failed, last_error, batch_group_id, overlap_warnings_json
         FROM agent_proposals
         WHERE session_id = ?1
         ORDER BY created_at ASC",
    )?;
    let rows = stmt.query_map(params![session_id], |row| {
        let ops_json: String = row.get(6)?;
        let diff_json: String = row.get(7)?;
        let snap_json: Option<String> = row.get(8)?;
        let status: String = row.get(5)?;
        let applied_count: Option<usize> = row.get::<_, Option<i64>>(11)?.map(|v| v.max(0) as usize);
        let applied_indices: Option<Vec<usize>> = row
            .get::<_, Option<String>>(12)?
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<usize>>(s).ok());
        let overlap_warnings: Vec<String> = row
            .get::<_, Option<String>>(16)?
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok())
            .unwrap_or_default();
        Ok(AgentProposal {
            id: row.get(0)?,
            session_id: row.get(1)?,
            kind: row.get(2)?,
            title: row.get(3)?,
            summary: row.get(4)?,
            can_revert: crate::agent::types::is_proposal_applied_status(&status) && snap_json.is_some(),
            status,
            ops: serde_json::from_str(&ops_json).unwrap_or(Value::Null),
            diffs: serde_json::from_str::<Vec<ProposalItemDiff>>(&diff_json).unwrap_or_default(),
            applied_count,
            applied_indices,
            last_attempt_failed: row.get::<_, i64>(13).unwrap_or(0) != 0,
            last_error: row.get(14)?,
            batch_group_id: row.get(15)?,
            overlap_warnings,
            created_at: row.get(9)?,
            applied_at: row.get(10)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn read_proposal_statuses(conn: &Connection, session_id: &str, id: Option<&str>) -> Result<Vec<Value>> {
    read_proposal_statuses_paged(conn, session_id, id, 0, 50)
}

pub fn read_proposal_statuses_paged(
    conn: &Connection,
    session_id: &str,
    id: Option<&str>,
    offset: usize,
    limit: usize,
) -> Result<Vec<Value>> {
    let include_details = id.is_some();
    let page_limit = limit.clamp(1, 100);
    let mut stmt = conn.prepare(
        "SELECT id, title, status, json_array_length(ops_json),
                coalesce(applied_count, case when status in ('applied', 'auto_applied') then json_array_length(rollback_snapshot_json) end),
                last_attempt_failed, rollback_snapshot_json, created_at, applied_at, diff_preview_json, applied_indices_json,
                last_error, batch_group_id, overlap_warnings_json
         FROM agent_proposals
         WHERE session_id = ?1 AND (?2 IS NULL OR id = ?2)
         ORDER BY created_at DESC, id DESC LIMIT 10",
    )?;
    let rows = stmt.query_map(params![session_id, id], |row| {
        let status: String = row.get(2)?;
        let items: usize = row.get(3)?;
        let applied: Option<usize> = row.get(4)?;
        let state = if crate::agent::types::is_proposal_applied_status(&status)
            && applied.is_some_and(|n| n < items)
        {
            "partially_applied"
        } else {
            status.as_str()
        };
        let title: String = row.get(1)?;
        let snap_raw: Option<String> = row.get(6)?;
        let created_at: String = row.get(7)?;
        let applied_at: Option<String> = row.get(8)?;
        let diff_raw: Option<String> = row.get(9)?;
        let applied_indices_raw: Option<String> = row.get(10)?;
        let last_error: Option<String> = row.get(11)?;
        let batch_group_id: Option<String> = row.get(12)?;
        let overlap_warnings: Vec<String> = row
            .get::<_, Option<String>>(13)?
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok())
            .unwrap_or_default();
        let applied_indices: Option<Vec<usize>> = applied_indices_raw
            .as_deref()
            .and_then(|s| serde_json::from_str::<Vec<usize>>(s).ok());
        let applied_set: Option<std::collections::HashSet<usize>> = applied_indices
            .as_ref()
            .map(|v| v.iter().copied().collect());
        let can_revert = crate::agent::types::is_proposal_applied_status(&status)
            && snap_raw.as_deref().is_some_and(|s| !s.trim().is_empty());
        let confirmed_by = match status.as_str() {
            "auto_applied" => Some("auto"),
            "applied" | "reverted" => Some("user"),
            _ => None,
        };
        let all_items_applied = applied.is_some_and(|a| a >= items);
        let applied_indices_inline = applied_indices.as_ref().and_then(|v| {
            if all_items_applied && v.len() > 10 {
                None
            } else if v.len() <= 200 {
                Some(v.clone())
            } else {
                None
            }
        });
        let mut entry = json!({
            "proposalId": row.get::<_, String>(0)?,
            "title": title.chars().take(120).collect::<String>(),
            "status": state,
            "itemCount": items,
            "appliedCount": applied,
            "appliedIndices": applied_indices_inline,
            "allItemsApplied": all_items_applied,
            "skippedCount": applied.map(|a| items.saturating_sub(a)),
            "lastAttemptFailed": row.get::<_, bool>(5)?,
            "lastError": last_error,
            "batchGroupId": batch_group_id,
            "overlapWarnings": overlap_warnings,
            "createdAt": created_at,
            "appliedAt": applied_at,
            "confirmedBy": confirmed_by,
            "canRevert": can_revert,
        });
        if include_details {
            if let Some(mut diffs) = diff_raw
                .as_deref()
                .and_then(|s| serde_json::from_str::<Value>(s).ok())
                .and_then(|v| v.as_array().cloned())
            {
                let total_diffs = diffs.len();
                // 若存在逐项执行回执，优先把实际已执行的条目排在前面，避免 80 项中勾第 70 项被前 50 项截断
                if let Some(ref aset) = applied_set {
                    diffs.sort_by_key(|d| {
                        let idx = d["index"].as_u64().unwrap_or(0) as usize;
                        (!aset.contains(&idx), idx)
                    });
                }
                let item_details: Vec<Value> = diffs
                    .into_iter()
                    .skip(offset)
                    .take(page_limit)
                    .map(|d| {
                        let idx = d["index"].as_u64().unwrap_or(0) as usize;
                        let item_status = if let Some(ref aset) = applied_set {
                            if aset.contains(&idx) { "applied" } else { "unselected" }
                        } else if crate::agent::types::is_proposal_applied_status(&status)
                            && applied == Some(items)
                        {
                            "applied"
                        } else if status == "reverted" {
                            "reverted"
                        } else {
                            status.as_str()
                        };
                        json!({
                            "index": d["index"],
                            "target": d["targetLabel"],
                            "action": d["actionLabel"],
                            "before": d["before"],
                            "after": d["after"],
                            "executionStatus": item_status,
                        })
                    })
                    .collect();
                let shown = item_details.len();
                entry["items"] = json!(item_details);
                entry["itemsTotal"] = json!(total_diffs);
                entry["itemsOffset"] = json!(offset);
                entry["hasMoreItems"] = json!(offset + shown < total_diffs);
            }
        }
        if let Some(arr) = snap_raw
            .as_deref()
            .and_then(|s| serde_json::from_str::<Value>(s).ok())
            .and_then(|v| v.as_array().cloned())
        {
            let affected_series: Vec<Value> = arr
                .iter()
                .filter_map(|op| {
                    let sid = op.get("series_id")?.as_i64()?;
                    let sname = op.get("series_name").and_then(Value::as_str).unwrap_or("");
                    Some(json!({ "seriesId": sid, "seriesName": sname }))
                })
                .collect();
            if !affected_series.is_empty() {
                entry["affectedSeries"] = json!(affected_series);
            }
        }
        Ok(entry)
    })?;
    Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
}

pub fn summarize_session_runtime_stats(conn: &Connection, session_id: &str) -> Result<Value> {
    let mut stmt = conn.prepare(
        "SELECT run_id, event_type, duration_ms, payload_json
         FROM agent_run_events
         WHERE session_id = ?1
         ORDER BY id ASC",
    )?;
    let mut run_ids = std::collections::HashSet::<String>::new();
    let mut llm_steps = 0usize;
    let mut total_tool_calls = 0usize;
    let mut total_prompt_tokens = 0u64;
    let mut total_cached_tokens = 0u64;
    let mut total_completion_tokens = 0u64;
    let mut compactions = 0usize;
    let mut latest_prompt_tokens = 0u64;
    let mut latest_cache_hit_pct = 0.0f64;
    let mut by_tool: HashMap<String, (usize, usize, u64)> = HashMap::new(); // (calls, output_chars, duration_ms)

    let rows = stmt.query_map(params![session_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, i64>(2)?.max(0) as u64,
            row.get::<_, String>(3)?,
        ))
    })?;
    for row in rows {
        let (run_id, event_type, dur_ms, payload_str) = row?;
        run_ids.insert(run_id);
        let payload: Value = serde_json::from_str(&payload_str).unwrap_or(Value::Null);
        match event_type.as_str() {
            "llm_step" => {
                llm_steps += 1;
                let pt = payload["promptTokens"].as_u64().unwrap_or(0);
                let ct = payload["completionTokens"].as_u64().unwrap_or(0);
                let cached = payload["cachedTokens"].as_u64().unwrap_or(0);
                total_prompt_tokens += pt;
                total_completion_tokens += ct;
                total_cached_tokens += cached;
                if pt > 0 {
                    latest_prompt_tokens = pt;
                    latest_cache_hit_pct = ((cached as f64 / pt as f64) * 1000.0).round() / 10.0;
                }
            }
            "tool_call" => {
                total_tool_calls += 1;
                let tname = payload["toolName"].as_str().unwrap_or("unknown").to_string();
                let chars = payload["outputChars"].as_u64().unwrap_or(0) as usize;
                let e = by_tool.entry(tname).or_insert((0, 0, 0));
                e.0 += 1;
                e.1 += chars;
                e.2 += dur_ms;
            }
            "compaction" => {
                compactions += 1;
            }
            _ => {}
        }
    }

    let overall_cache_hit_pct = if total_prompt_tokens > 0 {
        ((total_cached_tokens as f64 / total_prompt_tokens as f64) * 1000.0).round() / 10.0
    } else {
        0.0
    };
    let mut top_tools: Vec<(String, usize, usize, u64)> = by_tool
        .into_iter()
        .map(|(name, (calls, chars, ms))| (name, calls, chars, ms))
        .collect();
    top_tools.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| b.1.cmp(&a.1)));
    top_tools.truncate(8);

    Ok(json!({
        "sessionId": session_id,
        "turns": run_ids.len(),
        "llmSteps": llm_steps,
        "totalToolCalls": total_tool_calls,
        "compactions": compactions,
        "totalPromptTokens": total_prompt_tokens,
        "totalCachedTokens": total_cached_tokens,
        "totalCompletionTokens": total_completion_tokens,
        "overallCacheHitPct": overall_cache_hit_pct,
        "latestPromptTokens": latest_prompt_tokens,
        "latestCacheHitPct": latest_cache_hit_pct,
        "topToolsByOutputChars": top_tools.into_iter().map(|(name, calls, chars, ms)| json!({
            "tool": name,
            "calls": calls,
            "outputChars": chars,
            "durationMs": ms,
        })).collect::<Vec<_>>(),
    }))
}

pub fn list_memories(conn: &Connection) -> Result<Vec<AgentMemory>> {
    let mut stmt = conn.prepare(
        "SELECT id, category, COALESCE(tier, 'core'), content, enabled, created_at, updated_at
         FROM agent_user_memories
         ORDER BY CASE WHEN COALESCE(tier, 'core') = 'core' THEN 0 ELSE 1 END, updated_at DESC",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok(AgentMemory {
            id: row.get(0)?,
            category: row.get(1)?,
            tier: row.get(2)?,
            content: row.get(3)?,
            enabled: row.get::<_, i64>(4)? != 0,
            created_at: row.get(5)?,
            updated_at: row.get(6)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

fn normalize_memory_cmp(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_whitespace() && !matches!(c, '，' | '。' | '、' | ',' | '.' | '!' | '！' | '?' | '？' | ';' | '；' | ':' | '：'))
        .collect::<String>()
        .to_ascii_lowercase()
}

pub fn upsert_memory_checked(
    conn: &Connection,
    id: Option<&str>,
    category: &str,
    tier: Option<&str>,
    content: &str,
    enabled: bool,
) -> Result<(AgentMemory, bool)> {
    let trimmed = content.trim();
    if trimmed.is_empty() {
        return Err(StudioError::InvalidInput("记忆内容不能为空".into()));
    }
    let category = if category.trim().is_empty() {
        "preference".to_string()
    } else {
        category.trim().to_string()
    };
    let tier = match tier.map(str::trim) {
        Some("archive") => "archive".to_string(),
        _ => "core".to_string(),
    };
    let explicit_id = id.map(str::trim).filter(|s| !s.is_empty());
    // 写入前去重：未指定 id 时，在同 category + tier 下若存在完全相同或高度重合的内容，直接返回已有条目，避免重复建档
    if explicit_id.is_none() {
        let norm_new = normalize_memory_cmp(trimmed);
        if !norm_new.is_empty() {
            if let Ok(existing_all) = list_memories(conn) {
                for m in existing_all {
                    if m.category == category && m.tier == tier {
                        let norm_old = normalize_memory_cmp(&m.content);
                        if norm_old == norm_new
                            || (norm_old.len() >= 8
                                && norm_new.len() >= 8
                                && (norm_old.contains(&norm_new) || norm_new.contains(&norm_old))
                                && (norm_old.len().min(norm_new.len()) * 100 / norm_old.len().max(norm_new.len())) >= 85)
                        {
                            return Ok((m, true));
                        }
                    }
                }
            }
        }
    }
    let id = explicit_id
        .map(str::to_string)
        .unwrap_or_else(|| Uuid::new_v4().to_string());
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO agent_user_memories (id, category, tier, content, enabled, created_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)
         ON CONFLICT(id) DO UPDATE SET
            category = excluded.category,
            tier = excluded.tier,
            content = excluded.content,
            enabled = excluded.enabled,
            updated_at = excluded.updated_at",
        params![id, category, tier, trimmed, if enabled { 1i64 } else { 0i64 }, now],
    )?;
    Ok((
        AgentMemory {
            id,
            category,
            tier,
            content: trimmed.to_string(),
            enabled,
            created_at: now.clone(),
            updated_at: now,
        },
        false,
    ))
}

pub fn upsert_memory(
    conn: &Connection,
    id: Option<&str>,
    category: &str,
    tier: Option<&str>,
    content: &str,
    enabled: bool,
) -> Result<AgentMemory> {
    Ok(upsert_memory_checked(conn, id, category, tier, content, enabled)?.0)
}

fn infer_memory_category_intents(q: &str) -> Vec<&'static str> {
    let mut intents = Vec::new();
    if ["避开", "雷点", "雷区", "忌口", "讨厌", "不喜欢", "劝退", "不要推荐", "避雷", "踩雷", "avoid"]
        .iter()
        .any(|k| q.contains(k))
    {
        intents.push("avoid");
    }
    if ["风格", "语气", "说话", "怎么回复", "回复方式", "style"]
        .iter()
        .any(|k| q.contains(k))
    {
        intents.push("style");
    }
    if ["打分", "评分", "星级", "评价标准", "rating"]
        .iter()
        .any(|k| q.contains(k))
    {
        intents.push("rating_rule");
    }
    if ["剧透", "spoiler"].iter().any(|k| q.contains(k)) {
        intents.push("spoiler_rule");
    }
    if ["偏好", "口味", "喜欢看", "偏爱", "爱看", "preference"]
        .iter()
        .any(|k| q.contains(k))
    {
        intents.push("preference");
    }
    if ["身份", "称呼", "叫我", "个人资料", "profile"]
        .iter()
        .any(|k| q.contains(k))
    {
        intents.push("profile");
    }
    intents
}

fn token_matches_memory(token: &str, hay: &str, mem_category: &str) -> bool {
    if hay.contains(token) {
        return true;
    }
    let intents = infer_memory_category_intents(token);
    intents.contains(&mem_category)
}

/// 三层记忆按需召回：支持 `tier`（`all` | `core` | `archive`）、`category`、中文意图同义扩展与多词 AND 精确过滤。
pub fn recall_memories_local_with_tier(
    conn: &Connection,
    query: &str,
    category: Option<&str>,
    tier: Option<&str>,
    match_mode: &str,
) -> Result<Vec<AgentMemory>> {
    let all = list_memories(conn)?;
    let q = query.trim().to_ascii_lowercase();
    let tokens: Vec<&str> = q
        .split(|c: char| c.is_whitespace() || matches!(c, ',' | '，' | '、' | ';' | '；' | '?' | '？' | '!' | '！'))
        .filter(|t| !t.is_empty())
        .collect();
    let cat_filter = category
        .map(str::trim)
        .filter(|s| !s.is_empty() && *s != "all");
    let tier_filter = tier
        .map(str::trim)
        .filter(|s| !s.is_empty() && *s != "all");
    let use_or = match_mode.eq_ignore_ascii_case("or");
    let sentence_intents = if tokens.len() == 1 && q.chars().count() >= 4 {
        infer_memory_category_intents(&q)
    } else {
        Vec::new()
    };

    let mut scored: Vec<(usize, AgentMemory)> = all
        .into_iter()
        .filter(|m| m.enabled)
        .filter(|m| cat_filter.map(|c| m.category == c).unwrap_or(true))
        .filter(|m| tier_filter.map(|t| m.tier == t).unwrap_or(true))
        .filter_map(|m| {
            if tokens.is_empty() {
                return Some((if m.tier == "core" { 2 } else { 1 }, m));
            }
            let hay = format!("{} {} {}", m.category, m.tier, m.content).to_ascii_lowercase();
            let mut score = 0usize;
            if hay.contains(&q) {
                score += 6;
            }
            if tokens.len() >= 2 && !use_or {
                // 多词查询默认 AND 模式：所有词元均需命中（含同义分类意图），避免 "紫色 棒球" 误召回仅含 "紫色" 的条目
                let all_hit = tokens
                    .iter()
                    .all(|t| token_matches_memory(t, &hay, &m.category));
                if !all_hit {
                    return None;
                }
                score += tokens.len() * 3;
            } else {
                for t in &tokens {
                    if token_matches_memory(t, &hay, &m.category) {
                        score += 2;
                    }
                }
                if score == 0 && !sentence_intents.is_empty() && sentence_intents.contains(&m.category.as_str()) {
                    // 中文自然问句（如 "推荐时应该避开什么"）命中对应分类意图
                    score += 4;
                }
            }
            if score > 0 {
                Some((score, m))
            } else {
                None
            }
        })
        .collect();

    scored.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| b.1.updated_at.cmp(&a.1.updated_at)));
    scored.truncate(15);
    Ok(scored.into_iter().map(|(_, m)| m).collect())
}

/// 兼容旧签名：默认在 `all` 层级执行多词 AND + 意图检索
pub fn recall_memories_local(
    conn: &Connection,
    query: &str,
    category: Option<&str>,
) -> Result<Vec<AgentMemory>> {
    recall_memories_local_with_tier(conn, query, category, None, "and")
}

pub fn delete_memory(conn: &Connection, id: &str) -> Result<()> {
    conn.execute("DELETE FROM agent_user_memories WHERE id = ?1", params![id])?;
    Ok(())
}

pub fn ensure_builtin_slash_commands(conn: &Connection) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    let builtins: &[(&str, &str, &str, &str, &str, &str, &[&str])] = &[
        (
            "builtin-organize",
            "/整理",
            "藏品库深度整理规程",
            "扫描疑似重复条目、待归并同系列和无用标签，并输出可勾选撤销的整理提案",
            "请先加载 `/整理` 技能规程，运行全库诊断并给我一份结构化整理提案。",
            "## 藏品库深度整理规程（Skill）\n1. 先调用 `run_library_diagnostics(scope=\"all\")` 获取拆分系列、疑似重复和 0 使用标签。\n2. 按置信度分类汇报：高置信度问题（明确同系列不同季/同义标签/同外部源重复）直接调用对应 `propose_*` 生成可勾选提案；低置信度疑难项只做文字建议不强行提案。\n3. 系列归并优先并入已有系列，跨媒体（小说/漫画/动画）同 IP 可归同一系列并用 `series_role` 区分。\n4. 每次提案单批不超过 15 项，便于用户逐项核对。",
            &["read", "write"],
        ),
        (
            "builtin-spoiler",
            "/防剧透",
            "零剧透伴看讨论规程",
            "自动读取当前已看集数/进度，严格锁死防剧透边界讨论剧情、伏笔与演出",
            "请按 `/防剧透` 规程，先确认作品当前已看进度，在严禁剧透后续任何情节的前提下和我聊聊当前进度的看点与伏笔。",
            "## 零剧透伴看讨论规程（Skill）\n1. 若未在上下文中明确进度，先调 `get_work_detail` 查清 `progressPrimary / progressTotal`。\n2. 严禁提及、暗示、反问或预测第 `progressPrimary` 集之后的任何人物命运、反转、新角色或结局走向。\n3. 重点围绕已看部分的演出细节、伏笔回收、人物动机与音乐作画展开讨论。",
            &["read"],
        ),
        (
            "builtin-recommend",
            "/推番",
            "私人品味画像推番规程",
            "结合高分偏爱、严苛度、雷区记忆与已看避雷精准推荐 5 部高契合作品",
            "请按 `/推番` 规程分析我的藏品库与记忆偏好，避开我已看/已收藏作品，推荐 5 部高契合佳作。",
            "## 私人品味画像推番规程（Skill）\n1. 一次并行调用 `get_library_overview`、`query_library(status=\"看完\", sort=\"score_desc\", limit=20)` 和 `recall_memories(query=\"偏好 雷区 评分\")` 收集口味样本（无需反复翻页）。\n2. 脑内拟定 8~12 部候选佳作后，**只调用一次** `query_library(keywords=[\"片名1\", \"片名2\", ...])` 批量核实哪些未在库中（禁止逐部循环调用 `query_library`！）。\n3. 如对冷门作品的集数或年份不确定，最多查 1~2 次 `search_remote_catalog`，常规经典番直接输出即可，确保 3~4 步内完成。\n4. 最终用 3 句话总结用户品味画像，再给出 5 部未入库推荐，每部写清「契合你哪一点」及「潜在劝退点」。",
            &["read", "online"],
        ),
        (
            "builtin-characters",
            "/建关系",
            "角色与关系图谱建档规程",
            "拉取目标作品出场角色、梳理人物关系并生成可一键写入关系画布的提案",
            "请按 `/建关系` 规程检查目标作品角色，必要时在线拉取主要角色并生成角色与人物关系建档提案。",
            "## 角色与关系图谱建档规程（Skill）\n1. 调 `get_work_detail` 获取作品 `id`、`externalId`（Bangumi ID）及现有角色列表。\n2. 若缺少角色且有 `externalId`，调 `search_remote_catalog(source=\"character\", bangumi_subject_id=...)` 拉取官方角色名、头像和声优。\n3. 梳理 5～12 位核心角色及其两两关系（师徒/挚友/宿敌/搭档等），用 `propose_character_mutations` 一次性提交清晰提案；用户确认后可调用 `focus_canvas(mode=\"characters\")` 展示图谱。",
            &["read", "online", "write", "ui"],
        ),
        (
            "builtin-settings",
            "/调设置",
            "桌面端偏好调优与排障规程",
            "查阅同源内置设置注册表，按自然语言需求解释并直接调整桌面端外观/超分/系统偏好",
            "请按 `/调设置` 规程查阅我当前的桌面端设置，结合我的需求给出建议并在合适时直接应用（附带撤销）。",
            "## 桌面端偏好调优与排障规程（Skill）\n1. 先调用 `query_app_knowledge(topic=..., include_current_settings=true)` 核对真实设置键名、允许值和当前生效值。\n2. 遇到播放卡顿/掉帧优先建议将 `upscaleMode` 降级为 `spatial` 或 `off`；遇到老电脑界面不流畅建议开启 `reduceMotion`。\n3. 调用 `update_desktop_settings` 直接应用合法改动，并告知用户气泡内可随时一键撤销。",
            &["read", "ui"],
        ),
    ];

    for (id, cmd, label, desc, tpl, guide, scope) in builtins {
        let scope_json = serde_json::to_string(scope)?;
        conn.execute(
            "INSERT INTO agent_slash_commands
                (id, command, label, description, prompt_template, skill_guide, tool_scope_json, is_builtin, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 1, ?8)
             ON CONFLICT(id) DO UPDATE SET
                skill_guide = CASE
                    WHEN agent_slash_commands.skill_guide IS NULL
                      OR agent_slash_commands.skill_guide LIKE '%必须先用 `query_library` 核实未在库中！%'
                    THEN excluded.skill_guide
                    ELSE agent_slash_commands.skill_guide
                END",
            params![id, cmd, label, desc, tpl, guide, scope_json, now],
        )?;
    }
    Ok(())
}

pub fn list_slash_commands(conn: &Connection) -> Result<Vec<AgentSlashCommand>> {
    ensure_builtin_slash_commands(conn)?;
    let mut stmt = conn.prepare(
        "SELECT id, command, label, description, prompt_template, skill_guide, tool_scope_json, is_builtin, updated_at
         FROM agent_slash_commands
         ORDER BY is_builtin DESC, command ASC",
    )?;
    let rows = stmt.query_map([], |row| {
        let scope_json: Option<String> = row.get(6)?;
        Ok(AgentSlashCommand {
            id: row.get(0)?,
            command: row.get(1)?,
            label: row.get(2)?,
            description: row.get(3)?,
            prompt_template: row.get(4)?,
            skill_guide: row.get(5)?,
            tool_scope: scope_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Vec<String>>(s).ok()),
            is_builtin: row.get::<_, i64>(7)? != 0,
            updated_at: row.get(8)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn load_skill_guide(conn: &Connection, name_or_cmd: &str) -> Result<Option<AgentSlashCommand>> {
    let all = list_slash_commands(conn)?;
    let q = name_or_cmd.trim().to_ascii_lowercase();
    let q_slash = if q.starts_with('/') { q.clone() } else { format!("/{q}") };
    Ok(all.into_iter().find(|c| {
        c.command.to_ascii_lowercase() == q
            || c.command.to_ascii_lowercase() == q_slash
            || c.label.to_ascii_lowercase().contains(&q)
    }))
}

pub fn save_slash_command(
    conn: &Connection,
    id: Option<&str>,
    command: &str,
    label: &str,
    description: &str,
    prompt_template: &str,
    skill_guide: Option<&str>,
    tool_scope: Option<Vec<String>>,
) -> Result<AgentSlashCommand> {
    let mut cmd = command.trim().to_string();
    if cmd.is_empty() {
        return Err(StudioError::InvalidInput("指令/技能名不能为空".into()));
    }
    if !cmd.starts_with('/') {
        cmd = format!("/{cmd}");
    }
    if prompt_template.trim().is_empty() {
        return Err(StudioError::InvalidInput("提示词模板不能为空".into()));
    }
    let id = id
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| Uuid::new_v4().to_string());
    let label = if label.trim().is_empty() {
        cmd.clone()
    } else {
        label.trim().to_string()
    };
    let clean_guide = skill_guide
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let scope_json = tool_scope
        .as_ref()
        .and_then(|v| serde_json::to_string(v).ok());
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO agent_slash_commands
            (id, command, label, description, prompt_template, skill_guide, tool_scope_json, is_builtin, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8)
         ON CONFLICT(id) DO UPDATE SET
            command = excluded.command,
            label = excluded.label,
            description = excluded.description,
            prompt_template = excluded.prompt_template,
            skill_guide = excluded.skill_guide,
            tool_scope_json = excluded.tool_scope_json,
            updated_at = excluded.updated_at",
        params![id, cmd, label, description.trim(), prompt_template.trim(), clean_guide, scope_json, now],
    )?;
    Ok(AgentSlashCommand {
        id,
        command: cmd,
        label,
        description: description.trim().to_string(),
        prompt_template: prompt_template.trim().to_string(),
        skill_guide: clean_guide,
        tool_scope,
        is_builtin: false,
        updated_at: now,
    })
}

pub fn delete_slash_command(conn: &Connection, id: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM agent_slash_commands WHERE id = ?1 AND is_builtin = 0",
        params![id],
    )?;
    Ok(())
}

// ============================================================================
// MCP Server 本地配置存储（Token 走 Win32 DPAPI 加密，绝不入同步库）
// ============================================================================

fn mcp_vault_key(server_id: &str) -> String {
    format!("mcp:{server_id}")
}

pub fn load_mcp_auth_token(studio_data_dir: &Path, server_id: &str) -> Result<Option<String>> {
    load_profile_api_key(studio_data_dir, &mcp_vault_key(server_id))
}

pub fn list_mcp_servers(conn: &Connection, studio_data_dir: &Path) -> Result<Vec<McpServerConfig>> {
    let vault = load_key_vault(studio_data_dir);
    let mut stmt = conn.prepare(
        "SELECT id, name, transport, endpoint_or_cmd, args_json, headers_json, enabled,
                disabled_tools_json, require_confirm_tools_json, last_status, last_latency_ms, updated_at
         FROM agent_mcp_servers
         ORDER BY enabled DESC, name ASC",
    )?;
    let rows = stmt.query_map([], |row| {
        let id: String = row.get(0)?;
        let vk = mcp_vault_key(&id);
        let args_json: String = row.get(4)?;
        let headers_json: String = row.get(5)?;
        let disabled_json: String = row.get(7)?;
        let confirm_json: String = row.get(8)?;
        Ok(McpServerConfig {
            has_auth_token: vault.keys.contains_key(&vk),
            auth_token_hint: vault.hints.get(&vk).cloned(),
            id,
            name: row.get(1)?,
            transport: row.get(2)?,
            endpoint_or_cmd: row.get(3)?,
            args: serde_json::from_str(&args_json).unwrap_or_default(),
            headers: serde_json::from_str(&headers_json).unwrap_or_default(),
            enabled: row.get::<_, i64>(6)? != 0,
            disabled_tools: serde_json::from_str(&disabled_json).unwrap_or_default(),
            require_confirm_tools: serde_json::from_str(&confirm_json).unwrap_or_default(),
            last_status: row.get(9)?,
            last_latency_ms: row.get::<_, Option<i64>>(10)?.map(|v| v.max(0) as u64),
            updated_at: row.get(11)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn save_mcp_server(
    conn: &Connection,
    studio_data_dir: &Path,
    input: SaveMcpServerInput,
) -> Result<McpServerConfig> {
    let name = input.name.trim().to_string();
    if name.is_empty() {
        return Err(StudioError::InvalidInput("MCP 服务名称不能为空".into()));
    }
    let transport = match input.transport.trim() {
        "stdio" => "stdio".to_string(),
        _ => "http".to_string(),
    };
    let endpoint_or_cmd = if transport == "http" {
        normalize_base_url(&input.endpoint_or_cmd)?
    } else {
        let c = input.endpoint_or_cmd.trim().to_string();
        if c.is_empty() {
            return Err(StudioError::InvalidInput("本地 stdio MCP 命令不能为空".into()));
        }
        c
    };

    let existing_by_name: Option<String> = conn
        .query_row(
            "SELECT id FROM agent_mcp_servers WHERE name = ?1",
            params![name],
            |r| r.get(0),
        )
        .optional()?;
    let id = input
        .id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .or(existing_by_name)
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    let args_json = serde_json::to_string(&input.args.unwrap_or_default())?;
    let headers_json = serde_json::to_string(&input.headers.unwrap_or_default())?;
    let disabled_json = serde_json::to_string(&input.disabled_tools.unwrap_or_default())?;
    let confirm_json = serde_json::to_string(&input.require_confirm_tools.unwrap_or_default())?;
    let enabled = input.enabled.unwrap_or(true);
    let now = Utc::now().to_rfc3339();

    conn.execute(
        "INSERT INTO agent_mcp_servers (
            id, name, transport, endpoint_or_cmd, args_json, headers_json, enabled,
            disabled_tools_json, require_confirm_tools_json, updated_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name,
            transport = excluded.transport,
            endpoint_or_cmd = excluded.endpoint_or_cmd,
            args_json = excluded.args_json,
            headers_json = excluded.headers_json,
            enabled = excluded.enabled,
            disabled_tools_json = excluded.disabled_tools_json,
            require_confirm_tools_json = excluded.require_confirm_tools_json,
            updated_at = excluded.updated_at",
        params![
            id,
            name,
            transport,
            endpoint_or_cmd,
            args_json,
            headers_json,
            if enabled { 1i64 } else { 0i64 },
            disabled_json,
            confirm_json,
            now
        ],
    )?;

    let vk = mcp_vault_key(&id);
    if input.clear_auth_token.unwrap_or(false) {
        delete_profile_api_key(studio_data_dir, &vk)?;
    } else if let Some(ref tok) = input.auth_token {
        if !tok.trim().is_empty() {
            save_profile_api_key(studio_data_dir, &vk, tok)?;
        }
    }

    list_mcp_servers(conn, studio_data_dir)?
        .into_iter()
        .find(|s| s.id == id)
        .ok_or_else(|| StudioError::InvalidInput("保存 MCP 配置后读取失败".into()))
}

pub fn update_mcp_server_status(
    conn: &Connection,
    server_id: &str,
    status: &str,
    latency_ms: u64,
) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "UPDATE agent_mcp_servers SET last_status = ?1, last_latency_ms = ?2, updated_at = ?3 WHERE id = ?4",
        params![status, latency_ms as i64, now, server_id],
    )?;
    Ok(())
}

pub fn delete_mcp_server(conn: &Connection, studio_data_dir: &Path, server_id: &str) -> Result<()> {
    conn.execute("DELETE FROM agent_mcp_servers WHERE id = ?1", params![server_id])?;
    let _ = delete_profile_api_key(studio_data_dir, &mcp_vault_key(server_id));
    Ok(())
}


// ============================================================================
// 追番喵 AI 全链路运行记录与导出（Append-Only Event Log -> JSONL / Markdown）
// ============================================================================

#[allow(clippy::too_many_arguments)]
pub fn record_run_event(
    conn: &Connection,
    run_id: &str,
    session_id: &str,
    step: u32,
    event_type: &str,
    title: &str,
    status: &str,
    duration_ms: u64,
    payload: &Value,
) -> Result<()> {
    let payload_json = crate::agent::providers::redact_credentials(&payload.to_string());
    let title = crate::agent::providers::redact_credentials(title);
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    conn.execute(
        "INSERT INTO agent_run_events (
            run_id, session_id, step, event_type, title, status, duration_ms, payload_json, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        params![
            run_id,
            session_id,
            step as i64,
            event_type,
            title,
            status,
            duration_ms as i64,
            payload_json,
            now
        ],
    )?;
    Ok(())
}

pub fn load_session_trajectory(
    conn: &Connection,
    session_id: &str,
) -> Result<AgentTrajectorySummary> {
    let sess = get_session(conn, session_id)?;
    let session_title = sess
        .as_ref()
        .map(|s| s.title.clone())
        .unwrap_or_else(|| "当前对话".into());

    let mut stmt = conn.prepare(
        "SELECT id, run_id, session_id, step, event_type, title, status, duration_ms, payload_json, created_at
         FROM agent_run_events
         WHERE session_id = ?1
         ORDER BY id ASC",
    )?;
    let rows = stmt.query_map(params![session_id], |row| {
        let pjson: String = row.get(8)?;
        Ok(AgentRunEvent {
            id: row.get(0)?,
            run_id: row.get(1)?,
            session_id: row.get(2)?,
            step: row.get::<_, i64>(3)?.max(0) as u32,
            event_type: row.get(4)?,
            title: crate::agent::providers::redact_credentials(&row.get::<_, String>(5)?),
            status: row.get(6)?,
            duration_ms: row.get::<_, i64>(7)?.max(0) as u64,
            payload: serde_json::from_str(&crate::agent::providers::redact_credentials(&pjson)).unwrap_or(Value::Null),
            created_at: row.get(9)?,
        })
    })?;

    let mut events = Vec::new();
    let mut run_ids = std::collections::HashSet::<String>::new();
    let mut total_llm_steps = 0usize;
    let mut total_tool_calls = 0usize;
    let mut failed_tool_calls = 0usize;
    let mut guard_triggers = 0usize;
    let mut total_prompt_tokens = 0u64;
    let mut total_cached_tokens = 0u64;
    let mut total_completion_tokens = 0u64;
    let mut total_duration_ms = 0u64;

    for r in rows {
        let ev = r?;
        run_ids.insert(ev.run_id.clone());
        match ev.event_type.as_str() {
            "llm_step" => {
                total_llm_steps += 1;
                total_prompt_tokens += ev
                    .payload
                    .get("promptTokens")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0);
                total_cached_tokens += ev
                    .payload
                    .get("cachedTokens")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0);
                total_completion_tokens += ev
                    .payload
                    .get("completionTokens")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0);
            }
            "tool_call" => {
                total_tool_calls += 1;
                if ev.status != "ok" {
                    failed_tool_calls += 1;
                }
            }
            "guard" | "compaction" => {
                guard_triggers += 1;
            }
            "run_end" | "run_error" => {
                total_duration_ms += ev.duration_ms;
            }
            _ => {}
        }
        events.push(ev);
    }

    Ok(AgentTrajectorySummary {
        session_id: session_id.to_string(),
        session_title,
        total_runs: run_ids.len(),
        total_llm_steps,
        total_tool_calls,
        failed_tool_calls,
        guard_triggers,
        total_prompt_tokens,
        total_cached_tokens,
        total_completion_tokens,
        total_duration_ms,
        events,
    })
}

/// 导出追番喵 AI 运行记录的 JSONL 原始数据或 Markdown 报告。
pub fn export_session_trajectory(
    conn: &Connection,
    session_id: &str,
    format: &str,
) -> Result<String> {
    let traj = load_session_trajectory(conn, session_id)?;
    if format.eq_ignore_ascii_case("jsonl") {
        let mut lines = Vec::with_capacity(traj.events.len() + 1);
        lines.push(
            serde_json::json!({
                "type": "trajectory_meta",
                "format": "animeow-agent-trace-jsonl-v1",
                "sessionId": traj.session_id,
                "sessionTitle": traj.session_title,
                "exportedAt": Utc::now().to_rfc3339(),
                "summary": {
                    "totalRuns": traj.total_runs,
                    "totalLlmSteps": traj.total_llm_steps,
                    "totalToolCalls": traj.total_tool_calls,
                    "failedToolCalls": traj.failed_tool_calls,
                    "guardTriggers": traj.guard_triggers,
                    "totalPromptTokens": traj.total_prompt_tokens,
                    "totalCachedTokens": traj.total_cached_tokens,
                    "totalCompletionTokens": traj.total_completion_tokens,
                    "totalDurationMs": traj.total_duration_ms,
                }
            })
            .to_string(),
        );
        for ev in &traj.events {
            lines.push(serde_json::to_string(ev)?);
        }
        return Ok(lines.join("\n"));
    }

    // Markdown 运行报告
    let cache_pct = if traj.total_prompt_tokens > 0 {
        (traj.total_cached_tokens as f64 / traj.total_prompt_tokens as f64) * 100.0
    } else {
        0.0
    };
    let mut md = format!(
        "# AI 运行报告：{}\n\n\
         > 导出时间：{} · 会话 ID：`{}`\n\n\
         ## 全局指标概览\n\n\
         | 指标 | 数值 |\n\
         | :--- | :--- |\n\
         | 对话运行轮数（Runs） | {} |\n\
         | LLM 推理步数 | {} |\n\
         | 工具调用总数 / 失败数 | {} / {} |\n\
         | 护栏与压缩触发次数 | {} |\n\
         | 累计输入 Token / 缓存命中 | {} / {} ({:.1}%) |\n\
         | 累计输出 Token | {} |\n\
         | 累计总耗时 | {:.2}s |\n\n\
         ## 详细执行时间线\n\n",
        traj.session_title,
        Utc::now().to_rfc3339(),
        traj.session_id,
        traj.total_runs,
        traj.total_llm_steps,
        traj.total_tool_calls,
        traj.failed_tool_calls,
        traj.guard_triggers,
        traj.total_prompt_tokens,
        traj.total_cached_tokens,
        cache_pct,
        traj.total_completion_tokens,
        traj.total_duration_ms as f64 / 1000.0,
    );

    let mut last_run = String::new();
    for ev in &traj.events {
        if ev.run_id != last_run {
            last_run = ev.run_id.clone();
            md.push_str(&format!("### Run `{}`\n\n", &last_run[..last_run.len().min(8)]));
        }
        let status_tag = match ev.status.as_str() {
            "error" => "[ERROR]",
            "warn" => "[WARN]",
            _ => "[OK]",
        };
        md.push_str(&format!(
            "- **{status_tag} [Step {} · {}]** {}（`{}ms` · `{}`）\n",
            ev.step, ev.event_type, ev.title, ev.duration_ms, ev.created_at
        ));
        if let Some(obj) = ev.payload.as_object() {
            if !obj.is_empty() {
                let mut preview = obj.clone();
                let mut truncated = false;
                for value in preview.values_mut() {
                    if let Some(s) = value.as_str().filter(|s| s.chars().count() > 1000) {
                        *value = Value::String(s.chars().take(1000).collect());
                        truncated = true;
                    }
                }
                if truncated {
                    preview.insert("reportPreviewTruncated".into(), Value::Bool(true));
                }
                let pretty = serde_json::to_string_pretty(&preview).unwrap_or_default();
                md.push_str("  ```json\n");
                for line in pretty.lines() {
                    md.push_str("  ");
                    md.push_str(line);
                    md.push('\n');
                }
                md.push_str("  ```\n");
            }
        }
    }

    Ok(md)
}

/// 反馈用精简脱敏诊断导出：严禁包含会话标题、用户提问、作品名、私评、工具入参或结果预览。
/// 仅保留软件版本、各步事件类型、工具名、状态、耗时、错误分类和用量来源。
pub fn export_session_feedback_diagnostics(
    conn: &Connection,
    session_id: &str,
    app_version: &str,
) -> Result<String> {
    let traj = load_session_trajectory(conn, session_id)?;
    let sanitized_events: Vec<Value> = traj
        .events
        .iter()
        .map(|ev| {
            let p = &ev.payload;
            let tool_name = p.get("tool").or_else(|| p.get("name")).and_then(Value::as_str);
            let error_code = p.get("code").or_else(|| p.get("reason")).and_then(Value::as_str);
            let usage_source = p.get("usageSource").and_then(Value::as_str);
            let request_kind = p.get("requestKind").and_then(Value::as_str);
            let prompt_tokens = p.get("promptTokens").and_then(Value::as_u64);
            let cached_tokens = p.get("cachedTokens").and_then(Value::as_u64);
            let completion_tokens = p.get("completionTokens").and_then(Value::as_u64);
            let mut obj = serde_json::Map::new();
            obj.insert("runId".into(), json!(&ev.run_id[..ev.run_id.len().min(8)]));
            obj.insert("step".into(), json!(ev.step));
            obj.insert("eventType".into(), json!(ev.event_type));
            obj.insert("status".into(), json!(ev.status));
            obj.insert("durationMs".into(), json!(ev.duration_ms));
            obj.insert("createdAt".into(), json!(ev.created_at));
            if let Some(t) = tool_name {
                obj.insert("tool".into(), json!(t));
            }
            if let Some(c) = error_code {
                obj.insert("category".into(), json!(c));
            }
            if let Some(us) = usage_source {
                obj.insert("usageSource".into(), json!(us));
            }
            if let Some(rk) = request_kind {
                obj.insert("requestKind".into(), json!(rk));
            }
            if let Some(pt) = prompt_tokens {
                obj.insert("promptTokens".into(), json!(pt));
            }
            if let Some(ct) = cached_tokens {
                obj.insert("cachedTokens".into(), json!(ct));
            }
            if let Some(ot) = completion_tokens {
                obj.insert("completionTokens".into(), json!(ot));
            }
            Value::Object(obj)
        })
        .collect();

    let report = json!({
        "format": "animeow-feedback-diagnostics-v1",
        "privacyNotice": "已自动移除会话标题、聊天正文、作品名称、私人备注及工具入参/输出预览，仅保留排障所需的技术指标。",
        "appVersion": app_version,
        "exportedAt": Utc::now().to_rfc3339(),
        "summary": {
            "totalRuns": traj.total_runs,
            "totalLlmSteps": traj.total_llm_steps,
            "totalToolCalls": traj.total_tool_calls,
            "failedToolCalls": traj.failed_tool_calls,
            "guardTriggers": traj.guard_triggers,
            "totalPromptTokens": traj.total_prompt_tokens,
            "totalCachedTokens": traj.total_cached_tokens,
            "totalCompletionTokens": traj.total_completion_tokens,
            "totalDurationMs": traj.total_duration_ms,
        },
        "events": sanitized_events,
    });
    Ok(serde_json::to_string_pretty(&report)?)
}

/// 将前端当前筛选结果保存为本会话的 `library_result` 快照，返回 `art_<12hex>` 供助手直接读取与批改
pub fn save_filter_snapshot(
    conn: &Connection,
    session_id: &str,
    items: &[CuratorItem],
    label: &str,
) -> Result<String> {
    let full = serde_json::to_string(items)?;
    let _ = label;
    let (id, _) = save_tool_artifact(conn, session_id, "library_result", &full, 240)?;
    Ok(id)
}

// ============================================================================
// 可续跑批量核实任务 & 人工排除记录（全部存于 desktop_local.db）
// ============================================================================

pub fn create_verify_job_from_items(
    conn: &Connection,
    session_id: &str,
    result_id: &str,
    kind: &str,
    items: &[CuratorItem],
) -> Result<VerifyJobSummary> {
    let id = format!("vjob_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let filtered: Vec<&CuratorItem> = items
        .iter()
        .filter(|it| kind == "all" || it.kind == kind)
        .filter(|it| matches!(it.kind.as_str(), "anime" | "book"))
        .collect();
    if filtered.is_empty() {
        return Err(StudioError::InvalidInput(
            "清单中没有可核实 Bangumi 的动画或书籍条目。".into(),
        ));
    }
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "INSERT INTO agent_verify_jobs (
            id, session_id, result_id, kind, status, total_count, processed_count,
            exact_count, probable_count, no_match_count, failed_count, verify_ids_json, created_at, updated_at
        ) VALUES (?1, ?2, ?3, ?4, 'running', ?5, 0, 0, 0, 0, 0, '[]', ?6, ?6)",
        params![id, session_id, result_id, kind, filtered.len() as i64, now],
    )?;
    for it in &filtered {
        let local_meta = json!({
            "releaseDate": it.release_date,
            "totalEpisodes": it.progress_total,
            "mediaFormat": it.media_format,
            "tags": it.tags,
            "existingExternalSource": it.external_source,
            "existingExternalId": it.external_id,
        });
        tx.execute(
            "INSERT OR IGNORE INTO agent_verify_job_items (
                job_id, kind, work_id, title, original_title, local_meta_json, status, updated_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'pending', ?7)",
            params![
                id,
                it.kind,
                it.id,
                it.title,
                it.original_title,
                local_meta.to_string(),
                now
            ],
        )?;
    }
    tx.commit()?;
    get_verify_job_summary(conn, &id)?
        .ok_or_else(|| StudioError::InvalidInput("核实任务创建失败".into()))
}

pub fn get_verify_job_summary(conn: &Connection, job_id: &str) -> Result<Option<VerifyJobSummary>> {
    let row = conn
        .query_row(
            "SELECT id, session_id, result_id, kind, status, total_count, processed_count,
                    exact_count, probable_count, no_match_count, failed_count, verify_ids_json,
                    created_at, updated_at
             FROM agent_verify_jobs WHERE id = ?1",
            [job_id],
            |r| {
                let vids_raw: String = r.get(11)?;
                let verify_ids: Vec<String> = serde_json::from_str(&vids_raw).unwrap_or_default();
                Ok(VerifyJobSummary {
                    id: r.get(0)?,
                    session_id: r.get(1)?,
                    result_id: r.get(2)?,
                    kind: r.get(3)?,
                    status: r.get(4)?,
                    total_count: r.get::<_, i64>(5).unwrap_or(0).max(0) as usize,
                    processed_count: r.get::<_, i64>(6).unwrap_or(0).max(0) as usize,
                    exact_count: r.get::<_, i64>(7).unwrap_or(0).max(0) as usize,
                    probable_count: r.get::<_, i64>(8).unwrap_or(0).max(0) as usize,
                    no_match_count: r.get::<_, i64>(9).unwrap_or(0).max(0) as usize,
                    failed_count: r.get::<_, i64>(10).unwrap_or(0).max(0) as usize,
                    verify_ids,
                    created_at: r.get(12)?,
                    updated_at: r.get(13)?,
                })
            },
        )
        .optional()?;
    Ok(row)
}

pub fn list_session_verify_jobs(conn: &Connection, session_id: &str) -> Result<Vec<VerifyJobSummary>> {
    let mut stmt = conn.prepare(
        "SELECT id FROM agent_verify_jobs WHERE session_id = ?1 ORDER BY updated_at DESC LIMIT 12",
    )?;
    let ids: Vec<String> = stmt
        .query_map([session_id], |r| r.get(0))?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    let mut out = Vec::new();
    for id in ids {
        if let Some(s) = get_verify_job_summary(conn, &id)? {
            out.push(s);
        }
    }
    Ok(out)
}

pub fn load_verify_job_items(conn: &Connection, job_id: &str, status_filter: Option<&str>) -> Result<Vec<VerifyJobItem>> {
    let mut stmt = conn.prepare(
        "SELECT job_id, kind, work_id, title, original_title, local_meta_json, status, match_type,
                confidence, chosen_external_id, chosen_original_title, user_decision, candidates_json,
                error_message, updated_at
         FROM agent_verify_job_items
         WHERE job_id = ?1 AND (?2 IS NULL OR status = ?2)
         ORDER BY rowid ASC",
    )?;
    let rows = stmt.query_map(params![job_id, status_filter], |r| {
        let meta_raw: String = r.get(5)?;
        let cands_raw: String = r.get(12)?;
        Ok(VerifyJobItem {
            job_id: r.get(0)?,
            kind: r.get(1)?,
            work_id: r.get(2)?,
            title: r.get(3)?,
            original_title: r.get(4)?,
            local_meta: serde_json::from_str(&meta_raw).unwrap_or_else(|_| json!({})),
            status: r.get(6)?,
            match_type: r.get(7)?,
            confidence: r.get(8)?,
            chosen_external_id: r.get(9)?,
            chosen_original_title: r.get(10)?,
            user_decision: r.get(11)?,
            candidates: serde_json::from_str(&cands_raw).unwrap_or_else(|_| json!([])),
            error_message: r.get(13)?,
            updated_at: r.get(14)?,
        })
    })?;
    Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
}

pub fn get_verify_job_detail(conn: &Connection, job_id: &str) -> Result<Option<VerifyJobDetail>> {
    let Some(job) = get_verify_job_summary(conn, job_id)? else {
        return Ok(None);
    };
    let items = load_verify_job_items(conn, job_id, None)?;
    Ok(Some(VerifyJobDetail { job, items }))
}

pub fn update_verify_job_item_result(
    conn: &Connection,
    job_id: &str,
    kind: &str,
    work_id: i64,
    status: &str,
    match_type: Option<&str>,
    confidence: f64,
    chosen_external_id: Option<&str>,
    chosen_original_title: Option<&str>,
    candidates: &Value,
    error_message: Option<&str>,
) -> Result<()> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    conn.execute(
        "UPDATE agent_verify_job_items
         SET status = ?1, match_type = ?2, confidence = ?3, chosen_external_id = ?4,
             chosen_original_title = ?5, candidates_json = ?6, error_message = ?7, updated_at = ?8
         WHERE job_id = ?9 AND kind = ?10 AND work_id = ?11",
        params![
            status,
            match_type,
            confidence,
            chosen_external_id,
            chosen_original_title,
            candidates.to_string(),
            error_message,
            now,
            job_id,
            kind,
            work_id
        ],
    )?;
    Ok(())
}

pub fn refresh_verify_job_counters(
    conn: &Connection,
    job_id: &str,
    append_verify_id: Option<&str>,
) -> Result<VerifyJobSummary> {
    let items = load_verify_job_items(conn, job_id, None)?;
    let total = items.len();
    let mut processed = 0usize;
    let mut exact = 0usize;
    let mut probable = 0usize;
    let mut no_match = 0usize;
    let mut failed = 0usize;
    for it in &items {
        if it.status != "pending" {
            processed += 1;
        }
        match it.status.as_str() {
            "exact" | "selected" => exact += 1,
            "probable" => probable += 1,
            "no_match" | "rejected" | "skipped" => no_match += 1,
            "failed" => failed += 1,
            _ => {}
        }
    }
    let mut vids = get_verify_job_summary(conn, job_id)?
        .map(|s| s.verify_ids)
        .unwrap_or_default();
    if let Some(vid) = append_verify_id.filter(|s| !s.trim().is_empty()) {
        if !vids.iter().any(|v| v == vid) {
            vids.push(vid.to_string());
        }
    }
    let status = if processed >= total && failed == 0 {
        "completed"
    } else if processed >= total && failed > 0 {
        "partial_failed"
    } else {
        "paused"
    };
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    conn.execute(
        "UPDATE agent_verify_jobs
         SET status = ?1, total_count = ?2, processed_count = ?3, exact_count = ?4,
             probable_count = ?5, no_match_count = ?6, failed_count = ?7,
             verify_ids_json = ?8, updated_at = ?9
         WHERE id = ?10",
        params![
            status,
            total as i64,
            processed as i64,
            exact as i64,
            probable as i64,
            no_match as i64,
            failed as i64,
            serde_json::to_string(&vids)?,
            now,
            job_id
        ],
    )?;
    get_verify_job_summary(conn, job_id)?
        .ok_or_else(|| StudioError::InvalidInput("核实任务不存在".into()))
}

pub fn load_rejected_external_ids(
    conn: &Connection,
    kind: &str,
    work_id: i64,
    source: &str,
) -> Result<std::collections::HashSet<String>> {
    let mut stmt = conn.prepare(
        "SELECT external_id FROM agent_verify_rejections WHERE kind = ?1 AND work_id = ?2 AND source = ?3",
    )?;
    let rows = stmt.query_map(params![kind, work_id, source], |r| r.get::<_, String>(0))?;
    let mut out = std::collections::HashSet::new();
    for r in rows {
        out.insert(r?);
    }
    Ok(out)
}

pub fn resolve_verify_job_item_decision(
    conn: &Connection,
    job_id: &str,
    kind: &str,
    work_id: i64,
    decision: &str,
    external_id: Option<&str>,
    original_title: Option<&str>,
) -> Result<VerifyJobDetail> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let normalized = normalize_verify_decision(decision)
        .ok_or_else(|| StudioError::InvalidInput(format!("不支持的对照决策：{decision}")))?;
    match normalized {
        "select" => {
            let eid = external_id
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| StudioError::InvalidInput("选择候选需要提供 external_id".into()))?;
            conn.execute(
                "DELETE FROM agent_verify_rejections WHERE kind = ?1 AND work_id = ?2 AND source = 'bangumi' AND external_id = ?3",
                params![kind, work_id, eid],
            )?;
            conn.execute(
                "UPDATE agent_verify_job_items
                 SET status = 'selected', user_decision = 'select', chosen_external_id = ?1,
                     chosen_original_title = ?2, updated_at = ?3
                 WHERE job_id = ?4 AND kind = ?5 AND work_id = ?6",
                params![eid, original_title, now, job_id, kind, work_id],
            )?;
        }
        "reject_all" => {
            let items = load_verify_job_items(conn, job_id, None)?;
            if let Some(target) = items.iter().find(|it| it.kind == kind && it.work_id == work_id) {
                if let Some(arr) = target.candidates.as_array() {
                    for c in arr {
                        if let Some(cid) = c.get("externalId").and_then(Value::as_str).filter(|s| !s.is_empty()) {
                            let _ = conn.execute(
                                "INSERT OR IGNORE INTO agent_verify_rejections (kind, work_id, source, external_id, created_at)
                                 VALUES (?1, ?2, 'bangumi', ?3, ?4)",
                                params![kind, work_id, cid, now],
                            );
                        }
                    }
                }
            }
            conn.execute(
                "UPDATE agent_verify_job_items
                 SET status = 'rejected', user_decision = 'reject_all', chosen_external_id = NULL,
                     chosen_original_title = NULL, updated_at = ?1
                 WHERE job_id = ?2 AND kind = ?3 AND work_id = ?4",
                params![now, job_id, kind, work_id],
            )?;
        }
        "defer" => {
            conn.execute(
                "UPDATE agent_verify_job_items
                 SET status = 'probable', user_decision = 'defer', updated_at = ?1
                 WHERE job_id = ?2 AND kind = ?3 AND work_id = ?4",
                params![now, job_id, kind, work_id],
            )?;
        }
        "clear_rejection" => {
            conn.execute(
                "DELETE FROM agent_verify_rejections WHERE kind = ?1 AND work_id = ?2 AND source = 'bangumi'",
                params![kind, work_id],
            )?;
            conn.execute(
                "UPDATE agent_verify_job_items
                 SET status = 'probable', user_decision = NULL, updated_at = ?1
                 WHERE job_id = ?2 AND kind = ?3 AND work_id = ?4",
                params![now, job_id, kind, work_id],
            )?;
        }
        _ => {
            return Err(StudioError::InvalidInput(format!("不支持的对照决策：{decision}")));
        }
    }
    refresh_verify_job_counters(conn, job_id, None)?;
    get_verify_job_detail(conn, job_id)?
        .ok_or_else(|| StudioError::InvalidInput("核实任务不存在".into()))
}

/// 压缩后从本地库结构化汇总仍在活跃的清单、核实任务与待确认提案句柄，确保长对话压缩不丢状态
pub fn summarize_active_session_handles(conn: &Connection, session_id: &str) -> Result<Option<String>> {
    let mut lines = Vec::<String>::new();
    // 最近 3 份藏品清单快照
    if let Ok(mut stmt) = conn.prepare(
        "SELECT id, preview FROM agent_tool_artifacts
         WHERE session_id = ?1 AND tool_name = 'library_result'
         ORDER BY created_at DESC LIMIT 3",
    ) {
        if let Ok(rows) = stmt.query_map([session_id], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        }) {
            let list: Vec<String> = rows
                .flatten()
                .map(|(id, prev)| format!("{id}（{prev}）"))
                .collect();
            if !list.is_empty() {
                lines.push(format!("- 可继续使用的清单快照：{}", list.join("；")));
            }
        }
    }
    // 进行中或有疑似/失败项的核实任务
    if let Ok(jobs) = list_session_verify_jobs(conn, session_id) {
        for j in jobs.iter().take(3) {
            lines.push(format!(
                "- 批量核实任务 {}（清单 {}，进度 {}/{}，精确 {} / 疑似 {} / 失败 {}，verifyIds={:?}）",
                j.id, j.result_id, j.processed_count, j.total_count, j.exact_count, j.probable_count, j.failed_count, j.verify_ids
            ));
        }
    }
    // 待确认提案
    if let Ok(props) = load_session_proposals(conn, session_id) {
        let pendings: Vec<String> = props
            .iter()
            .filter(|p| p.status == "pending")
            .map(|p| format!("{}（{}，{}项）", p.id, p.title, p.diffs.len()))
            .collect();
        if !pendings.is_empty() {
            lines.push(format!("- 仍待确认的提案：{}", pendings.join("；")));
        }
    }
    if lines.is_empty() {
        Ok(None)
    } else {
        Ok(Some(format!(
            "【本地活跃任务与句柄（程序自动承接，继续操作前请重查当前库状态）】\n{}",
            lines.join("\n")
        )))
    }
}

/// 记录作品各可写字段的值来源（`user` | `external` | `inferred`），用于回填时严格区分事实来源与手工/推断值。
pub fn record_work_field_source(
    conn: &Connection,
    kind: &str,
    work_id: i64,
    field_name: &str,
    source: &str,
) -> Result<()> {
    let normalized_source = match source.trim().to_ascii_lowercase().as_str() {
        "external" => "external",
        "inferred" => "inferred",
        _ => "user",
    };
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO work_field_sources (kind, work_id, field_name, source, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(kind, work_id, field_name) DO UPDATE SET
           source = excluded.source,
           updated_at = excluded.updated_at",
        params![kind, work_id, field_name, normalized_source, now],
    )?;
    Ok(())
}

pub fn delete_work_field_source(
    conn: &Connection,
    kind: &str,
    work_id: i64,
    field_name: &str,
) -> Result<()> {
    conn.execute(
        "DELETE FROM work_field_sources WHERE kind = ?1 AND work_id = ?2 AND field_name = ?3",
        params![kind, work_id, field_name],
    )?;
    Ok(())
}

pub fn load_work_field_sources(
    conn: &Connection,
    kind: &str,
    work_id: i64,
) -> Result<std::collections::BTreeMap<String, (String, String)>> {
    let mut stmt = conn.prepare(
        "SELECT field_name, source, updated_at
         FROM work_field_sources
         WHERE kind = ?1 AND work_id = ?2",
    )?;
    let rows = stmt.query_map(params![kind, work_id], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?))
    })?;
    let mut out = std::collections::BTreeMap::new();
    for r in rows {
        let (f, s, u) = r?;
        out.insert(f, (s, u));
    }
    Ok(out)
}

pub fn load_field_source_map(
    conn: &Connection,
    field_name: &str,
) -> std::collections::HashMap<(String, i64), String> {
    let mut out = std::collections::HashMap::new();
    let Ok(mut stmt) = conn.prepare(
        "SELECT kind, work_id, source
         FROM work_field_sources
         WHERE field_name = ?1",
    ) else {
        return out;
    };
    let Ok(rows) = stmt.query_map(params![field_name], |r| {
        Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, String>(2)?))
    }) else {
        return out;
    };
    for r in rows.flatten() {
        out.insert((r.0, r.1), r.2);
    }
    out
}

/// 远程封面可用性探测缓存有效期（24 小时）
pub const COVER_PROBE_TTL_SECS: i64 = 24 * 3600;

pub fn get_cached_cover_probe(conn: &Connection, url: &str) -> Option<(bool, String)> {
    let (avail, checked_at): (bool, String) = conn
        .query_row(
            "SELECT available, checked_at FROM remote_cover_probe_cache WHERE url = ?1",
            [url],
            |r| Ok((r.get::<_, i64>(0)? != 0, r.get::<_, String>(1)?)),
        )
        .optional()
        .ok()
        .flatten()?;
    if let Ok(ts) = chrono::DateTime::parse_from_rfc3339(&checked_at) {
        let age = Utc::now().signed_duration_since(ts.with_timezone(&Utc)).num_seconds();
        if age > COVER_PROBE_TTL_SECS {
            return None;
        }
    }
    Some((avail, checked_at))
}

pub fn save_cached_cover_probe(conn: &Connection, url: &str, available: bool) -> Result<()> {
    let now = Utc::now().to_rfc3339();
    conn.execute(
        "INSERT INTO remote_cover_probe_cache (url, available, checked_at)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(url) DO UPDATE SET
           available = excluded.available,
           checked_at = excluded.checked_at",
        params![url, if available { 1i64 } else { 0i64 }, now],
    )?;
    Ok(())
}
