use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderPreset {
    pub id: String,
    pub name: String,
    /// "cn" | "global" | "local" | "custom"
    pub region: String,
    pub base_url: String,
    pub default_model: String,
    pub recommended_models: Vec<String>,
    pub requires_api_key: bool,
    pub is_local: bool,
    pub badge: Option<String>,
    pub help_note: String,
    pub console_url: Option<String>,
    /// "deepseek_tools" | "dashscope_native" | "zhipu_native" | "moonshot_native" | "openrouter_online" | "none"
    #[serde(default)]
    pub web_search_mode: String,
    #[serde(default)]
    pub web_search_note: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeStepResult {
    pub ok: bool,
    pub latency_ms: u64,
    pub detail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeResult {
    pub ok: bool,
    pub normalized_base_url: String,
    pub suggested_base_url: Option<String>,
    pub connectivity: ProbeStepResult,
    pub streaming: ProbeStepResult,
    pub tool_calling: ProbeStepResult,
    pub supports_tools: bool,
    pub supports_reasoning: bool,
    pub diagnostic: Option<DiagnosticError>,
    pub probed_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentProfile {
    pub id: String,
    pub name: String,
    pub provider_id: String,
    pub base_url: String,
    pub model: String,
    pub temperature: f64,
    pub max_steps: u32,
    pub max_turn_tokens: u32,
    pub supports_tools: bool,
    pub supports_reasoning: bool,
    #[serde(default)]
    pub enable_web_search: bool,
    pub has_api_key: bool,
    pub api_key_hint: Option<String>,
    pub last_probe: Option<ProbeResult>,
    pub is_default: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveAgentProfileInput {
    pub id: Option<String>,
    pub name: String,
    pub provider_id: String,
    pub base_url: String,
    pub model: String,
    pub api_key: Option<String>,
    pub clear_api_key: Option<bool>,
    pub temperature: Option<f64>,
    pub max_steps: Option<u32>,
    pub max_turn_tokens: Option<u32>,
    pub supports_tools: Option<bool>,
    pub supports_reasoning: Option<bool>,
    #[serde(default)]
    pub enable_web_search: Option<bool>,
    pub last_probe: Option<ProbeResult>,
    pub is_default: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteModelInfo {
    pub id: String,
    pub owned_by: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchModelsResult {
    pub normalized_base_url: String,
    pub suggested_base_url: Option<String>,
    pub models: Vec<RemoteModelInfo>,
    pub filtered_out_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiagnosticError {
    pub code: String,
    pub title: String,
    pub explanation: String,
    pub suggestion: String,
    pub quick_fix: Option<String>,
    pub suggested_base_url: Option<String>,
    pub technical_detail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnabledToolGroups {
    pub read: bool,
    pub online: bool,
    pub ui: bool,
    pub write: bool,
    pub memory: bool,
    #[serde(default)]
    pub mcp: bool,
}

impl Default for EnabledToolGroups {
    fn default() -> Self {
        Self {
            read: true,
            online: true,
            ui: true,
            write: true,
            memory: true,
            mcp: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AutoApprovePolicy {
    /// 单条进度、状态、评分、短评快捷记账
    #[serde(default = "default_true")]
    pub quick_log: bool,
    /// 仅填空值的外部元数据与封面回填（绝不覆盖已有字段）
    #[serde(default = "default_true")]
    pub rehydrate_empty: bool,
    /// 置信度 >= 0.95 且无日期/集数冲突的外部 ID 绑定
    #[serde(default = "default_true")]
    pub high_confidence_bind: bool,
    /// 经外部检索入库的新增作品（支持软删撤销）
    #[serde(default)]
    pub create_from_catalog: bool,
    /// 仅追加标签（不删除或替换已有标签）
    #[serde(default)]
    pub append_tags_only: bool,
    /// 单次自动审批允许的最大作品数上限（超出自动降级为手动确认）
    #[serde(default = "default_max_auto_items")]
    pub max_items_per_auto_batch: usize,
}

fn default_true() -> bool {
    true
}

fn default_max_auto_items() -> usize {
    20
}

fn default_cover_storage_policy() -> String {
    "remote".to_string()
}

impl Default for AutoApprovePolicy {
    fn default() -> Self {
        Self {
            quick_log: true,
            rehydrate_empty: true,
            high_confidence_bind: true,
            create_from_catalog: false,
            append_tags_only: false,
            max_items_per_auto_batch: default_max_auto_items(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentPolicyConfig {
    /// "strict" | "balanced" | "auto_safe" | "quick_log" (legacy alias)
    pub trust_mode: String,
    pub enabled_groups: EnabledToolGroups,
    pub default_anti_spoiler: bool,
    /// 自动触发滚动摘要压缩的上下文 Token 阈值（默认 32000，不硬删长对话）
    #[serde(default = "default_auto_compact_tokens")]
    pub auto_compact_tokens: u32,
    /// 压缩后仍原文保留的最近对话轮数（默认 8 轮）
    #[serde(default = "default_keep_recent_turns")]
    pub max_history_turns: u32,
    pub persona_id: String,
    pub custom_instructions: String,
    /// 安全自动审批细粒度白名单（trust_mode 为 auto_safe 时生效）
    #[serde(default)]
    pub auto_approve: AutoApprovePolicy,
    /// 封面存储策略："remote"（默认，远程优先、探测失败回落本地）| "auto" | "local"（全部落盘本地）
    #[serde(default = "default_cover_storage_policy")]
    pub cover_storage_policy: String,
    /// 回填标签时默认过滤形态/地区/改编来源/制作组类元数据标签
    #[serde(default = "default_true")]
    pub filter_metadata_tags: bool,
}

fn default_auto_compact_tokens() -> u32 {
    32_000
}

fn default_keep_recent_turns() -> u32 {
    8
}

impl Default for AgentPolicyConfig {
    fn default() -> Self {
        Self {
            trust_mode: "balanced".to_string(),
            enabled_groups: EnabledToolGroups::default(),
            default_anti_spoiler: true,
            auto_compact_tokens: default_auto_compact_tokens(),
            max_history_turns: default_keep_recent_turns(),
            persona_id: "curator".to_string(),
            custom_instructions: String::new(),
            auto_approve: AutoApprovePolicy::default(),
            cover_storage_policy: default_cover_storage_policy(),
            filter_metadata_tags: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenUsage {
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    pub total_tokens: u64,
    #[serde(default)]
    pub cached_tokens: u64,
    pub estimated: bool,
    /// "reported" | "estimated" | "mixed" | "missing"
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage_source: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_count: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub compaction_tokens: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WebSourceCitation {
    pub title: String,
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub site: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub published_date: Option<String>,
    pub fetched_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyJobSummary {
    pub id: String,
    pub session_id: String,
    pub result_id: String,
    pub kind: String,
    pub status: String,
    pub total_count: usize,
    pub processed_count: usize,
    pub exact_count: usize,
    pub probable_count: usize,
    pub no_match_count: usize,
    pub failed_count: usize,
    pub verify_ids: Vec<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyJobItem {
    pub job_id: String,
    pub kind: String,
    pub work_id: i64,
    pub title: String,
    pub original_title: String,
    pub local_meta: Value,
    pub status: String,
    pub match_type: Option<String>,
    pub confidence: f64,
    pub chosen_external_id: Option<String>,
    pub chosen_original_title: Option<String>,
    pub user_decision: Option<String>,
    pub candidates: Value,
    pub error_message: Option<String>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VerifyJobDetail {
    pub job: VerifyJobSummary,
    pub items: Vec<VerifyJobItem>,
}

/// 规范化分批核实的人工对照决策字符串（兼容历史与前端别名，落库统一为规范值）。
pub fn normalize_verify_decision(decision: &str) -> Option<&'static str> {
    match decision.trim() {
        "select" | "selected" => Some("select"),
        "reject_all" | "rejected" => Some("reject_all"),
        "defer" | "deferred" => Some("defer"),
        "clear_rejection" => Some("clear_rejection"),
        _ => None,
    }
}

/// 单一判定源：核实条目是否属于“精确命中”（兼容 `exact_match` 与 `exact`）。
pub fn is_verify_match_exact(match_type: Option<&str>, status: &str) -> bool {
    matches!(match_type, Some("exact_match" | "exact")) || status == "exact"
}

/// 单一判定源：核实条目是否处于“可直接生成绑定方案”状态。
pub fn is_verify_item_ready_to_bind(item: &VerifyJobItem) -> bool {
    let has_eid = item
        .chosen_external_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .is_some();
    if !has_eid {
        return false;
    }
    match item
        .user_decision
        .as_deref()
        .and_then(normalize_verify_decision)
    {
        Some("select") => true,
        Some("reject_all" | "defer") => false,
        _ => is_verify_match_exact(item.match_type.as_deref(), &item.status),
    }
}

/// 从分批核实任务明细中汇总所有可直接进入 `create_bulk_bind_proposal` 的绑定条目。
pub fn collect_verify_job_bindable_entries(detail: &VerifyJobDetail) -> Vec<Value> {
    let mut bindings = Vec::new();
    for item in &detail.items {
        if !is_verify_item_ready_to_bind(item) {
            continue;
        }
        let Some(ext_id) = item
            .chosen_external_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        else {
            continue;
        };
        bindings.push(json!({
            "kind": item.kind,
            "work_id": item.work_id,
            "external_source": "bangumi",
            "external_id": ext_id,
            "original_title": item.chosen_original_title,
        }));
    }
    bindings
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolCallRecord {
    pub id: String,
    pub name: String,
    pub arguments: String,
    /// 上游厂商扩展字段（例如 Google Gemini 4 在 OpenAI 兼容格式下返回的
    /// `extra_content.google.thought_signature`）。为空时不序列化，零影响其他厂商。
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        alias = "extra_content"
    )]
    pub extra_content: Option<Value>,
}

impl ToolCallRecord {
    /// 构造发往 OpenAI 兼容上游接口的单条 `tool_calls` 协议对象（单一序列化源）。
    /// 仅在 `extra_content` 非空且非 null 时附带 snake_case 键 `"extra_content"`，
    /// 避免结构体 `rename_all = "camelCase"` 错发为 `"extraContent"` 或影响无扩展字段的厂商。
    pub fn to_openai_wire_value(&self) -> Value {
        let mut obj = json!({
            "id": self.id,
            "type": "function",
            "function": {
                "name": self.name,
                "arguments": self.arguments,
            }
        });
        if let Some(ref extra) = self.extra_content {
            if !extra.is_null() {
                obj["extra_content"] = extra.clone();
            }
        }
        obj
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolTraceItem {
    pub id: String,
    pub name: String,
    pub label: String,
    pub group: String,
    pub args_preview: String,
    pub ok: bool,
    pub summary: String,
    pub duration_ms: u64,
    pub truncated: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UiActionCommand {
    /// "navigate" | "apply_filter" | "focus_canvas"
    pub action: String,
    pub label: String,
    pub payload: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsUndoRecord {
    pub summary: String,
    pub patch: Value,
    pub revert_patch: Value,
    pub changes: Vec<ProposalItemDiff>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalItemDiff {
    pub index: usize,
    pub target_label: String,
    pub action_label: String,
    pub before: String,
    pub after: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentProposal {
    pub id: String,
    pub session_id: String,
    pub kind: String,
    pub title: String,
    pub summary: String,
    /// "pending" | "applied" | "auto_applied" | "noop" | "rejected" | "reverted" | "unconfirmed" | "expired"
    pub status: String,
    pub ops: Value,
    pub diffs: Vec<ProposalItemDiff>,
    pub can_revert: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub applied_count: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub applied_indices: Option<Vec<usize>>,
    #[serde(default)]
    pub last_attempt_failed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub batch_group_id: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub overlap_warnings: Vec<String>,
    pub created_at: String,
    pub applied_at: Option<String>,
}

/// 提案已真实落库状态的单一判定源（含手动确认 `applied` 与安全自动审批 `auto_applied`）。
/// 所有需要判断是否已执行/可撤销/防重生成重复写入的分支必须统一调用本函数，严禁散落手写 `status == "applied"`。
pub fn is_proposal_applied_status(status: &str) -> bool {
    matches!(status, "applied" | "auto_applied")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalApplyResult {
    pub proposal: AgentProposal,
    pub applied_count: usize,
    #[serde(default)]
    pub applied_indices: Vec<usize>,
    pub changed_domains: Vec<String>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchProposalItemOutcome {
    pub proposal_id: String,
    pub title: String,
    /// "applied" | "noop" | "conflict_skipped" | "failed" | "skipped"
    pub outcome: String,
    pub applied_count: usize,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proposal: Option<AgentProposal>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchProposalCommitResult {
    pub batch_group_id: String,
    pub applied_proposals: usize,
    pub noop_proposals: usize,
    pub skipped_or_failed_proposals: usize,
    pub total_applied_items: usize,
    pub changed_domains: Vec<String>,
    pub outcomes: Vec<BatchProposalItemOutcome>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchProposalRevertResult {
    pub batch_group_id: String,
    pub reverted_proposals: usize,
    pub failed_proposals: usize,
    pub changed_domains: Vec<String>,
    pub outcomes: Vec<BatchProposalItemOutcome>,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CoverBaselineStats {
    pub total_works: usize,
    pub remote_count: usize,
    pub local_user_custom_count: usize,
    pub local_external_native_count: usize,
    pub none_count: usize,
    pub native_dir_file_count: usize,
    pub native_dir_total_bytes: u64,
    pub rehydrate_file_count: usize,
    pub rehydrate_total_bytes: u64,
    pub rehydrate_avg_bytes: u64,
    pub orphan_file_count: usize,
    pub orphan_total_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OrphanCoverCleanupResult {
    pub dry_run: bool,
    pub scanned_files: usize,
    pub protected_files: usize,
    pub orphan_files: Vec<String>,
    pub removed_count: usize,
    pub freed_bytes: u64,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentSession {
    pub id: String,
    pub title: String,
    pub profile_id: Option<String>,
    pub persona_id: String,
    pub pinned: bool,
    pub archived: bool,
    pub rolling_summary: Option<String>,
    pub compacted_up_to_msg_id: Option<String>,
    pub message_count: usize,
    pub total_prompt_tokens: u64,
    pub total_completion_tokens: u64,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MemoryChangeEvent {
    pub action: String, // "added" | "updated" | "deleted"
    pub id: String,
    pub category: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageVersion {
    pub content: String,
    pub reasoning_content: Option<String>,
    #[serde(default)]
    pub tool_traces: Vec<ToolTraceItem>,
    #[serde(default)]
    pub ui_actions: Vec<UiActionCommand>,
    pub settings_undo: Option<SettingsUndoRecord>,
    #[serde(default)]
    pub memory_events: Vec<MemoryChangeEvent>,
    pub proposal_id: Option<String>,
    #[serde(default)]
    pub proposal_ids: Vec<String>,
    pub diagnostic_error: Option<DiagnosticError>,
    pub token_usage: Option<TokenUsage>,
    #[serde(default)]
    pub stopped: bool,
    #[serde(default)]
    pub web_sources: Vec<WebSourceCitation>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentMessage {
    pub id: String,
    pub session_id: String,
    pub role: String,
    pub content: String,
    pub reasoning_content: Option<String>,
    pub tool_calls: Option<Vec<ToolCallRecord>>,
    pub tool_call_id: Option<String>,
    pub tool_name: Option<String>,
    pub tool_traces: Vec<ToolTraceItem>,
    pub ui_actions: Vec<UiActionCommand>,
    pub settings_undo: Option<SettingsUndoRecord>,
    #[serde(default)]
    pub memory_events: Vec<MemoryChangeEvent>,
    pub proposal_id: Option<String>,
    #[serde(default)]
    pub proposal_ids: Vec<String>,
    pub diagnostic_error: Option<DiagnosticError>,
    pub token_usage: Option<TokenUsage>,
    #[serde(default)]
    pub versions: Vec<MessageVersion>,
    #[serde(default)]
    pub active_version_idx: usize,
    /// 本轮提问发送时的运行态上下文快照（仅供跨轮重放以保住提示词缓存命中，不展示于气泡或导出）
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_context: Option<String>,
    #[serde(default)]
    pub stopped: bool,
    #[serde(default)]
    pub web_sources: Vec<WebSourceCitation>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentMemory {
    pub id: String,
    pub category: String,
    /// "core" (核心常驻) | "archive" (归档按需召回)
    #[serde(default = "default_memory_tier")]
    pub tier: String,
    pub content: String,
    pub enabled: bool,
    pub created_at: String,
    pub updated_at: String,
}

fn default_memory_tier() -> String {
    "core".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentSlashCommand {
    pub id: String,
    pub command: String,
    pub label: String,
    pub description: String,
    pub prompt_template: String,
    /// 渐进式披露完整 Skill 规程（Markdown，按需由 load_skill 工具读取）
    pub skill_guide: Option<String>,
    pub tool_scope: Option<Vec<String>>,
    pub is_builtin: bool,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct McpServerConfig {
    pub id: String,
    pub name: String,
    /// "http" | "stdio"
    pub transport: String,
    pub endpoint_or_cmd: String,
    pub args: Vec<String>,
    pub headers: std::collections::HashMap<String, String>,
    pub has_auth_token: bool,
    pub auth_token_hint: Option<String>,
    pub enabled: bool,
    pub disabled_tools: Vec<String>,
    pub require_confirm_tools: Vec<String>,
    pub last_status: Option<String>,
    pub last_latency_ms: Option<u64>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveMcpServerInput {
    pub id: Option<String>,
    pub name: String,
    pub transport: String,
    pub endpoint_or_cmd: String,
    pub args: Option<Vec<String>>,
    pub headers: Option<std::collections::HashMap<String, String>>,
    pub auth_token: Option<String>,
    pub clear_auth_token: Option<bool>,
    pub enabled: Option<bool>,
    pub disabled_tools: Option<Vec<String>>,
    pub require_confirm_tools: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct McpToolInfo {
    pub server_name: String,
    pub name: String,
    pub description: String,
    pub input_schema: Value,
    pub requires_confirm: bool,
    pub disabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct McpTestResult {
    pub ok: bool,
    pub latency_ms: u64,
    pub server_info: Option<String>,
    pub tools: Vec<McpToolInfo>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentRunEvent {
    pub id: i64,
    pub run_id: String,
    pub session_id: String,
    pub step: u32,
    /// "run_start" | "llm_step" | "tool_call" | "guard" | "compaction" | "ui_action" | "proposal" | "memory" | "run_end" | "run_error"
    pub event_type: String,
    pub title: String,
    /// "ok" | "warn" | "error"
    pub status: String,
    pub duration_ms: u64,
    pub payload: Value,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentTrajectorySummary {
    pub session_id: String,
    pub session_title: String,
    pub total_runs: usize,
    pub total_llm_steps: usize,
    pub total_tool_calls: usize,
    pub failed_tool_calls: usize,
    pub guard_triggers: usize,
    pub total_prompt_tokens: u64,
    pub total_cached_tokens: u64,
    pub total_completion_tokens: u64,
    pub total_duration_ms: u64,
    pub events: Vec<AgentRunEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActiveContextChip {
    pub active_tab: String,
    pub active_sub_view: Option<String>,
    pub work_kind: Option<String>,
    pub work_id: Option<i64>,
    pub work_title: Option<String>,
    pub work_status: Option<String>,
    pub progress_primary: Option<i64>,
    pub progress_total: Option<i64>,
    pub anti_spoiler_enabled: bool,
    #[serde(default)]
    pub media_format: Option<String>,
    #[serde(default)]
    pub progress_unit: Option<String>,
    #[serde(default)]
    pub filter_result_id: Option<String>,
    #[serde(default)]
    pub filter_count: Option<usize>,
    #[serde(default)]
    pub filter_label: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendAgentMessageInput {
    pub session_id: String,
    pub profile_id: Option<String>,
    pub user_message: String,
    pub slash_command: Option<String>,
    pub context_chip: Option<ActiveContextChip>,
    pub current_settings: Option<Value>,
    /// 若传入已存在的 assistant 消息 ID，则执行「无损重新生成」并将新回答追加为该消息的下一个微版本（保留旧版本可切换！）
    #[serde(default)]
    pub regenerate_message_id: Option<String>,
    /// 本会话临时自动托管开关（仅作用于当前轮次，切会话或重启自动复位）
    #[serde(default)]
    pub session_autopilot: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentStreamEnvelope {
    pub run_id: String,
    pub session_id: String,
    pub event: AgentStreamEvent,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum AgentStreamEvent {
    Started {
        user_message: AgentMessage,
        active_groups: Vec<String>,
    },
    StepStart {
        step: u32,
        max_steps: u32,
    },
    Delta {
        text: String,
    },
    ReasoningDelta {
        text: String,
    },
    ToolStart {
        id: String,
        name: String,
        label: String,
        group: String,
        args_preview: String,
    },
    ToolResult {
        trace: ToolTraceItem,
    },
    UiAction {
        action: UiActionCommand,
    },
    SettingsApplied {
        undo: SettingsUndoRecord,
    },
    MemoryChanged {
        event: MemoryChangeEvent,
    },
    ContextCompacted {
        summary: String,
        compacted_messages: usize,
    },
    ProposalCreated {
        proposal: AgentProposal,
    },
    QuickApplied {
        changed_domains: Vec<String>,
        summary: String,
        proposal: AgentProposal,
    },
    AutoApplied {
        changed_domains: Vec<String>,
        summary: String,
        proposal: AgentProposal,
    },
    Usage {
        usage: TokenUsage,
    },
    Done {
        assistant_message: AgentMessage,
        cancelled: bool,
    },
    DiagnosticError {
        error: DiagnosticError,
        partial_message: Option<AgentMessage>,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stream_event_serializes_nested_fields_in_camel_case() {
        let msg = AgentMessage {
            id: "m1".into(),
            session_id: "s1".into(),
            role: "user".into(),
            content: "hello".into(),
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
            stopped: false,
            web_sources: vec![],
            created_at: "2026-11-20T00:00:00Z".into(),
        };
        let started = serde_json::to_value(AgentStreamEvent::Started {
            user_message: msg.clone(),
            active_groups: vec!["read".into()],
        })
        .unwrap();
        assert_eq!(started["type"], "started");
        assert!(started.get("userMessage").is_some());
        assert!(started.get("activeGroups").is_some());
        assert!(started.get("user_message").is_none());

        let step = serde_json::to_value(AgentStreamEvent::StepStart {
            step: 1,
            max_steps: 8,
        })
        .unwrap();
        assert_eq!(step["type"], "stepStart");
        assert_eq!(step["maxSteps"], 8);

        let done = serde_json::to_value(AgentStreamEvent::Done {
            assistant_message: msg,
            cancelled: false,
        })
        .unwrap();
        assert_eq!(done["type"], "done");
        assert!(done.get("assistantMessage").is_some());
    }
}
