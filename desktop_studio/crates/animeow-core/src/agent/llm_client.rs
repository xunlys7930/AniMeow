use crate::agent::providers::{
    classify_http_error, classify_network_error, filter_chat_models, is_local_base_url,
    normalize_base_url, suggest_v1_url, WebSearchMode,
};
use crate::agent::types::{
    DiagnosticError, FetchModelsResult, ProbeResult, ProbeStepResult, RemoteModelInfo, TokenUsage,
    ToolCallRecord,
};
use chrono::Utc;
use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION, CONTENT_TYPE};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

const IDLE_CHUNK_TIMEOUT_SECS: u64 = 45;
const PROBE_TIMEOUT_SECS: u64 = 25;

fn build_http_client(connect_timeout_secs: u64) -> Result<reqwest::Client, DiagnosticError> {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(connect_timeout_secs))
        .user_agent("AniMeowStudio/Agent-Harness")
        .build()
        .map_err(|e| classify_network_error(&e.to_string(), "client-init"))
}

fn build_headers(base_url: &str, api_key: Option<&str>) -> HeaderMap {
    let mut headers = HeaderMap::new();
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    if let Some(key) = api_key.map(str::trim).filter(|s| !s.is_empty()) {
        if let Ok(val) = HeaderValue::from_str(&format!("Bearer {key}")) {
            headers.insert(AUTHORIZATION, val);
        }
    }
    if base_url.to_ascii_lowercase().contains("openrouter.ai") {
        headers.insert(
            "HTTP-Referer",
            HeaderValue::from_static("https://www.xunlys.top"),
        );
        headers.insert("X-Title", HeaderValue::from_static("AniMeow Studio"));
    }
    headers
}

#[derive(Debug, Clone)]
pub struct StreamTurnOutput {
    pub content: String,
    pub reasoning_content: String,
    pub tool_calls: Vec<ToolCallRecord>,
    pub finish_reason: Option<String>,
    pub usage: Option<TokenUsage>,
    pub cancelled: bool,
}

#[derive(Default)]
struct PartialToolCall {
    id: String,
    name: String,
    arguments: String,
    extra_content: Option<Value>,
}

/// 解析单个 SSE `data:` JSON 帧并拼装增量文本、思考流、tool_calls 与 usage
#[derive(Default)]
pub struct SseFrameAssembler {
    pub content: String,
    pub reasoning_content: String,
    tool_calls: Vec<PartialToolCall>,
    /// 按非空 `id` 精准映射槽位（兼容 Gemini 并发调用全部返回 `index: 0` 但 `id` 不同的行为）
    tool_slot_by_id: HashMap<String, usize>,
    /// 记录每个 `index` 当前最新活跃的槽位（兼容标准 OpenAI/DeepSeek 仅首帧带 `id`、后续帧仅带 `index` 的交错分片）
    tool_last_slot_by_index: HashMap<usize, usize>,
    pub finish_reason: Option<String>,
    pub usage: Option<TokenUsage>,
}

impl SseFrameAssembler {
    pub fn ingest_json<FDelta, FReason>(
        &mut self,
        val: &Value,
        mut on_delta: FDelta,
        mut on_reasoning: FReason,
    ) where
        FDelta: FnMut(&str),
        FReason: FnMut(&str),
    {
        if let Some(u) = val.get("usage").and_then(|v| v.as_object()) {
            let prompt = u.get("prompt_tokens").and_then(|v| v.as_u64()).unwrap_or(0);
            let completion = u
                .get("completion_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            let total = u
                .get("total_tokens")
                .and_then(|v| v.as_u64())
                .unwrap_or(prompt + completion);
            let cached = u
                .get("prompt_cache_hit_tokens")
                .and_then(|v| v.as_u64())
                .or_else(|| {
                    u.get("prompt_tokens_details")
                        .and_then(|d| d.get("cached_tokens"))
                        .and_then(|v| v.as_u64())
                })
                .or_else(|| u.get("cached_tokens").and_then(|v| v.as_u64()))
                .unwrap_or(0);
            if total > 0 || prompt > 0 || completion > 0 {
                self.usage = Some(TokenUsage {
                    prompt_tokens: prompt,
                    completion_tokens: completion,
                    total_tokens: total,
                    cached_tokens: cached,
                    estimated: false,
                    usage_source: Some("reported".into()),
                    request_count: Some(1),
                    compaction_tokens: None,
                });
            }
        }

        let Some(choice) = val
            .get("choices")
            .and_then(|c| c.as_array())
            .and_then(|arr| arr.first())
        else {
            return;
        };

        if let Some(reason) = choice.get("finish_reason").and_then(|v| v.as_str()) {
            if !reason.is_empty() {
                self.finish_reason = Some(reason.to_string());
            }
        }

        // 兼容流式 `delta` 与非流式 `message`
        let delta = choice.get("delta").or_else(|| choice.get("message"));
        let Some(delta_obj) = delta.and_then(|d| d.as_object()) else {
            return;
        };

        if let Some(rc) = delta_obj
            .get("reasoning_content")
            .or_else(|| delta_obj.get("reasoning"))
            .and_then(|v| v.as_str())
        {
            if !rc.is_empty() {
                self.reasoning_content.push_str(rc);
                on_reasoning(rc);
            }
        }

        if let Some(text) = delta_obj.get("content").and_then(|v| v.as_str()) {
            if !text.is_empty() {
                self.content.push_str(text);
                on_delta(text);
            }
        }

        if let Some(tcs) = delta_obj.get("tool_calls").and_then(|v| v.as_array()) {
            for (fallback_idx, tc) in tcs.iter().enumerate() {
                let raw_idx = tc
                    .get("index")
                    .and_then(|v| v.as_u64())
                    .map(|n| n as usize);
                let idx = raw_idx.unwrap_or(fallback_idx);
                let incoming_id = tc
                    .get("id")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty());

                let slot = if let Some(id_str) = incoming_id {
                    if let Some(&existing) = self.tool_slot_by_id.get(id_str) {
                        self.tool_last_slot_by_index.insert(idx, existing);
                        existing
                    } else {
                        let new_slot = self.tool_calls.len();
                        self.tool_calls.push(PartialToolCall {
                            id: id_str.to_string(),
                            ..Default::default()
                        });
                        self.tool_slot_by_id.insert(id_str.to_string(), new_slot);
                        self.tool_last_slot_by_index.insert(idx, new_slot);
                        new_slot
                    }
                } else if let Some(&existing) = self.tool_last_slot_by_index.get(&idx) {
                    existing
                } else if raw_idx.is_none() && !self.tool_calls.is_empty() {
                    self.tool_calls.len() - 1
                } else {
                    let new_slot = self.tool_calls.len();
                    self.tool_calls.push(PartialToolCall::default());
                    self.tool_last_slot_by_index.insert(idx, new_slot);
                    new_slot
                };

                let entry = &mut self.tool_calls[slot];
                if let Some(id_str) = incoming_id {
                    entry.id = id_str.to_string();
                }
                if let Some(extra) = tc.get("extra_content") {
                    if !extra.is_null() {
                        entry.extra_content = Some(extra.clone());
                    }
                }
                if let Some(func) = tc.get("function").and_then(|v| v.as_object()) {
                    if let Some(name) = func.get("name").and_then(|v| v.as_str()) {
                        if !name.is_empty() {
                            // 兼容首片完整名或极少数分片名
                            if entry.name.is_empty() {
                                entry.name.push_str(name);
                            } else if !entry.name.ends_with(name) {
                                entry.name.push_str(name);
                            }
                        }
                    }
                    if let Some(args) = func.get("arguments").and_then(|v| v.as_str()) {
                        entry.arguments.push_str(args);
                    }
                }
            }
        }
    }

    pub fn into_output(self, cancelled: bool) -> StreamTurnOutput {
        let mut tool_calls = Vec::new();
        for (idx, tc) in self.tool_calls.into_iter().enumerate() {
            if tc.name.trim().is_empty() {
                continue;
            }
            let id = if tc.id.trim().is_empty() {
                format!("call_{idx}")
            } else {
                tc.id
            };
            let args = if tc.arguments.trim().is_empty() {
                "{}".to_string()
            } else {
                tc.arguments
            };
            tool_calls.push(ToolCallRecord {
                id,
                name: tc.name.trim().to_string(),
                arguments: args,
                extra_content: tc.extra_content,
            });
        }
        StreamTurnOutput {
            content: self.content,
            reasoning_content: self.reasoning_content,
            tool_calls,
            finish_reason: self.finish_reason,
            usage: self.usage,
            cancelled,
        }
    }
}

fn apply_provider_web_search_payload(
    payload: &mut Value,
    model: &str,
    web_search_mode: WebSearchMode,
) {
    match web_search_mode {
        WebSearchMode::None | WebSearchMode::DeepSeekTools => {}
        WebSearchMode::DashScopeNative => {
            payload["enable_search"] = json!(true);
        }
        WebSearchMode::ZhipuNative => {
            let mut tools_arr = payload
                .get("tools")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();
            tools_arr.push(json!({
                "type": "web_search",
                "web_search": {
                    "enable": true,
                    "search_result": true
                }
            }));
            payload["tools"] = Value::Array(tools_arr);
        }
        WebSearchMode::MoonshotNative => {
            let mut tools_arr = payload
                .get("tools")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();
            tools_arr.push(json!({
                "type": "builtin_function",
                "function": {
                    "name": "$web_search"
                }
            }));
            payload["tools"] = Value::Array(tools_arr);
        }
        WebSearchMode::OpenRouterOnline => {
            if !model.trim().ends_with(":online") {
                payload["model"] = json!(format!("{}:online", model.trim()));
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub async fn stream_chat_completion<FDelta, FReason>(
    base_url: &str,
    api_key: Option<&str>,
    model: &str,
    temperature: f64,
    messages: &[Value],
    tools: Option<&[Value]>,
    tool_choice: Option<&str>,
    web_search_mode: WebSearchMode,
    cancel: &CancellationToken,
    mut on_delta: FDelta,
    mut on_reasoning: FReason,
) -> Result<StreamTurnOutput, DiagnosticError>
where
    FDelta: FnMut(&str),
    FReason: FnMut(&str),
{
    let normalized = normalize_base_url(base_url).map_err(|e| DiagnosticError {
        code: "INVALID_URL".into(),
        title: "模型接口地址无效".into(),
        explanation: e.to_string(),
        suggestion: "请在配置中检查 Base URL 格式，或选择官方一键预设。".into(),
        quick_fix: Some("open_config".into()),
        suggested_base_url: None,
        technical_detail: format!("Invalid Base URL: {base_url}"),
    })?;

    if !is_local_base_url(&normalized)
        && api_key.map(str::trim).unwrap_or("").is_empty()
    {
        return Err(DiagnosticError {
            code: "MISSING_API_KEY".into(),
            title: "尚未填写 API Key".into(),
            explanation: "当前使用的是远程模型服务，必须填写你的 API Key 才能发起请求。".into(),
            suggestion: "点击右上角齿轮打开配置填入 Key；如果想零费用免 Key 使用，可改选「本地 Ollama」预设。".into(),
            quick_fix: Some("open_config".into()),
            suggested_base_url: None,
            technical_detail: format!("Missing API Key for remote endpoint {normalized}"),
        });
    }

    let client = build_http_client(15)?;
    let endpoint = format!("{normalized}/chat/completions");

    let mut payload = json!({
        "model": model,
        "messages": messages,
        "stream": true,
        "temperature": temperature,
    });

    // 请求流式附带 usage（绝大多数现代 OpenAI-Compatible 端点支持；不支持时下面遇到 400 可容错）
    if !normalized.to_ascii_lowercase().contains("127.0.0.1:1234") {
        payload["stream_options"] = json!({ "include_usage": true });
    }

    if let Some(tool_list) = tools {
        if !tool_list.is_empty() {
            payload["tools"] = Value::Array(tool_list.to_vec());
            if let Some(choice) = tool_choice {
                payload["tool_choice"] = Value::String(choice.to_string());
            } else {
                payload["tool_choice"] = Value::String("auto".to_string());
            }
        }
    }
    apply_provider_web_search_payload(&mut payload, model, web_search_mode);

    let req_fut = client
        .post(&endpoint)
        .headers(build_headers(&normalized, api_key))
        .json(&payload)
        .send();

    let mut resp = tokio::select! {
        _ = cancel.cancelled() => {
            return Ok(SseFrameAssembler::default().into_output(true));
        }
        res = tokio::time::timeout(Duration::from_secs(IDLE_CHUNK_TIMEOUT_SECS), req_fut) => {
            match res {
                Ok(Ok(r)) => r,
                Ok(Err(e)) => return Err(classify_network_error(&e.to_string(), &normalized)),
                Err(_) => return Err(classify_network_error("request timed out", &normalized)),
            }
        }
    };

    // 若某些小众中转不认 stream_options 报 400，自动去掉 stream_options 静默重试一次
    if resp.status().as_u16() == 400 && payload.get("stream_options").is_some() {
        let err_text = resp.text().await.unwrap_or_default();
        if err_text.to_ascii_lowercase().contains("stream_options") {
            payload.as_object_mut().map(|m| m.remove("stream_options"));
            let retry_resp = tokio::select! {
                _ = cancel.cancelled() => {
                    return Ok(SseFrameAssembler::default().into_output(true));
                }
                res = tokio::time::timeout(
                    Duration::from_secs(IDLE_CHUNK_TIMEOUT_SECS),
                    client.post(&endpoint).headers(build_headers(&normalized, api_key)).json(&payload).send()
                ) => {
                    match res {
                        Ok(Ok(r)) => r,
                        Ok(Err(e)) => return Err(classify_network_error(&e.to_string(), &normalized)),
                        Err(_) => return Err(classify_network_error("request timed out", &normalized)),
                    }
                }
            };
            resp = retry_resp;
        } else {
            return Err(classify_http_error(400, &err_text, &normalized, model));
        }
    }

    let status = resp.status().as_u16();
    if !(200..300).contains(&status) {
        let body = resp.text().await.unwrap_or_default();
        return Err(classify_http_error(status, &body, &normalized, model));
    }

    let content_type = resp
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();

    let mut assembler = SseFrameAssembler::default();

    // 兼容极少数忽略 stream=true 直接返回整段 application/json 的中转站
    if content_type.contains("application/json") && !content_type.contains("event-stream") {
        let text = resp.text().await.unwrap_or_default();
        if let Ok(val) = serde_json::from_str::<Value>(&text) {
            assembler.ingest_json(&val, &mut on_delta, &mut on_reasoning);
        }
        return Ok(assembler.into_output(false));
    }

    let mut pending_buf = Vec::<u8>::new();
    let mut cancelled = false;

    loop {
        let next_chunk = tokio::select! {
            _ = cancel.cancelled() => {
                cancelled = true;
                break;
            }
            res = tokio::time::timeout(Duration::from_secs(IDLE_CHUNK_TIMEOUT_SECS), resp.chunk()) => {
                match res {
                    Ok(Ok(Some(bytes))) => bytes,
                    Ok(Ok(None)) => break,
                    Ok(Err(e)) => {
                        if assembler.finish_reason.is_some() {
                            break;
                        }
                        return Err(interrupted_stream(&e.to_string()));
                    }
                    Err(_) => {
                        if assembler.finish_reason.is_some() {
                            break;
                        }
                        return Err(classify_network_error("stream chunk timed out", &normalized));
                    }
                }
            }
        };

        pending_buf.extend_from_slice(&next_chunk);
        while let Some(pos) = pending_buf.iter().position(|&b| b == b'\n') {
            let line_bytes: Vec<u8> = pending_buf.drain(..=pos).collect();
            let line = String::from_utf8_lossy(&line_bytes);
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with(':') {
                continue;
            }
            let data = trimmed
                .strip_prefix("data:")
                .map(str::trim)
                .unwrap_or("");
            if data.is_empty() {
                continue;
            }
            if data == "[DONE]" {
                return Ok(assembler.into_output(false));
            }
            if let Ok(val) = serde_json::from_str::<Value>(data) {
                assembler.ingest_json(&val, &mut on_delta, &mut on_reasoning);
            }
        }
    }

    // 处理末尾没有换行的残留帧
    if !cancelled && !pending_buf.is_empty() {
        let tail = String::from_utf8_lossy(&pending_buf);
        let trimmed = tail.trim();
        let data = trimmed.strip_prefix("data:").map(str::trim).unwrap_or(trimmed);
        if data == "[DONE]" {
            return Ok(assembler.into_output(false));
        }
        if let Ok(val) = serde_json::from_str::<Value>(data) {
            assembler.ingest_json(&val, &mut on_delta, &mut on_reasoning);
        }
    }

    if !cancelled && assembler.finish_reason.is_none() {
        return Err(interrupted_stream("SSE ended before finish_reason or [DONE]"));
    }
    Ok(assembler.into_output(cancelled))
}

fn interrupted_stream(detail: &str) -> DiagnosticError {
    DiagnosticError {
        code: "STREAM_INTERRUPTED".into(),
        title: "模型回复中途断开".into(),
        explanation: "模型没有完整返回本次回复，已停止处理这一步。".into(),
        suggestion: "请检查模型服务和网络连接后重试。".into(),
        quick_fix: Some("retry".into()),
        suggested_base_url: None,
        technical_detail: detail.into(),
    }
}

/// 一键拉取模型列表（自动清洗 URL、过滤非对话模型、404 自动探测 `/v1`）
pub async fn fetch_provider_models(
    base_url: &str,
    api_key: Option<&str>,
) -> Result<FetchModelsResult, DiagnosticError> {
    let normalized = normalize_base_url(base_url).map_err(|e| DiagnosticError {
        code: "INVALID_URL".into(),
        title: "接口地址格式不正确".into(),
        explanation: e.to_string(),
        suggestion: "请填写完整 https:// 接口地址或选择预设服务商。".into(),
        quick_fix: None,
        suggested_base_url: None,
        technical_detail: format!("normalize_base_url failed for {base_url}"),
    })?;

    let client = build_http_client(12)?;
    let (status, body) = send_models_get(&client, &normalized, api_key).await?;

    if (200..300).contains(&status) {
        let (models, filtered) = parse_models_response(&body);
        return Ok(FetchModelsResult {
            normalized_base_url: normalized,
            suggested_base_url: None,
            models,
            filtered_out_count: filtered,
        });
    }

    // 若 404 且疑似漏写 `/v1`，自动尝试补 `/v1` 探测
    if status == 404 {
        if let Some(suggested) = suggest_v1_url(&normalized) {
            if let Ok((st2, body2)) = send_models_get(&client, &suggested, api_key).await {
                if (200..300).contains(&st2) {
                    let (models, filtered) = parse_models_response(&body2);
                    return Ok(FetchModelsResult {
                        normalized_base_url: normalized,
                        suggested_base_url: Some(suggested),
                        models,
                        filtered_out_count: filtered,
                    });
                }
            }
        }
    }

    Err(classify_http_error(status, &body, &normalized, "(models)"))
}

async fn send_models_get(
    client: &reqwest::Client,
    normalized_url: &str,
    api_key: Option<&str>,
) -> Result<(u16, String), DiagnosticError> {
    let url = format!("{normalized_url}/models");
    let res = tokio::time::timeout(
        Duration::from_secs(15),
        client
            .get(&url)
            .headers(build_headers(normalized_url, api_key))
            .send(),
    )
    .await
    .map_err(|_| classify_network_error("timeout", normalized_url))?
    .map_err(|e| classify_network_error(&e.to_string(), normalized_url))?;
    let status = res.status().as_u16();
    let body = res.text().await.unwrap_or_default();
    Ok((status, body))
}

fn parse_models_response(body: &str) -> (Vec<RemoteModelInfo>, usize) {
    let Ok(val) = serde_json::from_str::<Value>(body) else {
        return (vec![], 0);
    };
    let arr = val
        .get("data")
        .and_then(|v| v.as_array())
        .or_else(|| val.get("models").and_then(|v| v.as_array()));
    let Some(list) = arr else {
        return (vec![], 0);
    };
    let mut raw = Vec::new();
    for item in list {
        let id = item
            .get("id")
            .or_else(|| item.get("name"))
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty());
        if let Some(id) = id {
            let owned_by = item
                .get("owned_by")
                .and_then(|v| v.as_str())
                .map(str::to_string);
            raw.push(RemoteModelInfo {
                id: id.to_string(),
                owned_by,
            });
        }
    }
    filter_chat_models(raw)
}

/// 三段式低消耗可用性体检：1) 连通鉴权 2) SSE 流式 3) Tool Calling 能力
pub async fn probe_agent_profile(
    base_url: &str,
    api_key: Option<&str>,
    model: &str,
) -> ProbeResult {
    let now = Utc::now().to_rfc3339();
    let normalized = match normalize_base_url(base_url) {
        Ok(u) => u,
        Err(e) => {
            let diag = DiagnosticError {
                code: "INVALID_URL".into(),
                title: "接口地址格式不正确".into(),
                explanation: e.to_string(),
                suggestion: "请检查 Base URL，远程需使用 https://，本机允许 http://127.0.0.1。".into(),
                quick_fix: None,
                suggested_base_url: None,
                technical_detail: format!("Invalid Base URL: {base_url}"),
            };
            return ProbeResult {
                ok: false,
                normalized_base_url: base_url.trim().to_string(),
                suggested_base_url: None,
                connectivity: ProbeStepResult { ok: false, latency_ms: 0, detail: diag.title.clone() },
                streaming: ProbeStepResult { ok: false, latency_ms: 0, detail: "未执行".into() },
                tool_calling: ProbeStepResult { ok: false, latency_ms: 0, detail: "未执行".into() },
                supports_tools: false,
                supports_reasoning: false,
                diagnostic: Some(diag),
                probed_at: now,
            };
        }
    };

    let model = model.trim();
    if model.is_empty() {
        let diag = DiagnosticError {
            code: "MISSING_MODEL".into(),
            title: "尚未填写模型名称".into(),
            explanation: "必须指定一个模型 ID 才能进行可用性体检。".into(),
            suggestion: "点击「获取模型」从列表挑选，或直接输入现役模型名（如 deepseek-flash、qwen3.5-plus、glm-5）。".into(),
            quick_fix: None,
            suggested_base_url: None,
            technical_detail: "Empty model name".into(),
        };
        return ProbeResult {
            ok: false,
            normalized_base_url: normalized,
            suggested_base_url: None,
            connectivity: ProbeStepResult { ok: false, latency_ms: 0, detail: "缺少模型名称".into() },
            streaming: ProbeStepResult { ok: false, latency_ms: 0, detail: "未执行".into() },
            tool_calling: ProbeStepResult { ok: false, latency_ms: 0, detail: "未执行".into() },
            supports_tools: false,
            supports_reasoning: false,
            diagnostic: Some(diag),
            probed_at: now,
        };
    }

    // Step 1 + Step 2: 用极短流式请求一次同时验证鉴权连通性 + SSE 流式 + 思考字段（极省 Token）
    let t0 = Instant::now();
    let cancel = CancellationToken::new();
    let got_chunk = std::sync::atomic::AtomicBool::new(false);
    let ping_messages = vec![json!({
        "role": "user",
        "content": "Reply with OK"
    })];

    let step1_res = tokio::time::timeout(
        Duration::from_secs(PROBE_TIMEOUT_SECS),
        stream_chat_completion(
            &normalized,
            api_key,
            model,
            0.0,
            &ping_messages,
            None,
            None,
            WebSearchMode::None,
            &cancel,
            |d| {
                if !d.is_empty() {
                    got_chunk.store(true, std::sync::atomic::Ordering::Relaxed);
                }
            },
            |r| {
                if !r.is_empty() {
                    got_chunk.store(true, std::sync::atomic::Ordering::Relaxed);
                }
            },
        ),
    )
    .await;

    let step1_ms = t0.elapsed().as_millis() as u64;
    let got_chunk = got_chunk.load(std::sync::atomic::Ordering::Relaxed);
    let mut suggested_base_url = None;

    let supports_reasoning = match step1_res {
        Ok(Ok(out)) => {
            !out.reasoning_content.is_empty()
                || model.to_ascii_lowercase().contains("reasoner")
                || model.to_ascii_lowercase().contains("r1")
        }
        Ok(Err(mut diag)) => {
            if diag.code == "NOT_FOUND" {
                if let Some(sug) = suggest_v1_url(&normalized) {
                    if let Ok(client) = build_http_client(8) {
                        if let Ok((st, _)) = send_models_get(&client, &sug, api_key).await {
                            if (200..300).contains(&st) {
                                suggested_base_url = Some(sug.clone());
                                diag.suggested_base_url = Some(sug);
                                diag.quick_fix = Some("apply_suggested_url".into());
                            }
                        }
                    }
                }
            }
            return ProbeResult {
                ok: false,
                normalized_base_url: normalized,
                suggested_base_url,
                connectivity: ProbeStepResult {
                    ok: false,
                    latency_ms: step1_ms,
                    detail: diag.title.clone(),
                },
                streaming: ProbeStepResult {
                    ok: false,
                    latency_ms: 0,
                    detail: "前置连通失败".into(),
                },
                tool_calling: ProbeStepResult {
                    ok: false,
                    latency_ms: 0,
                    detail: "前置连通失败".into(),
                },
                supports_tools: false,
                supports_reasoning: false,
                diagnostic: Some(diag),
                probed_at: now,
            };
        }
        Err(_) => {
            let diag = classify_network_error("probe timed out", &normalized);
            return ProbeResult {
                ok: false,
                normalized_base_url: normalized,
                suggested_base_url: None,
                connectivity: ProbeStepResult {
                    ok: false,
                    latency_ms: step1_ms,
                    detail: "连通探测超时".into(),
                },
                streaming: ProbeStepResult { ok: false, latency_ms: 0, detail: "超时".into() },
                tool_calling: ProbeStepResult { ok: false, latency_ms: 0, detail: "超时".into() },
                supports_tools: false,
                supports_reasoning: false,
                diagnostic: Some(diag),
                probed_at: now,
            };
        }
    };

    let connectivity = ProbeStepResult {
        ok: true,
        latency_ms: step1_ms,
        detail: format!("鉴权与响应正常（{step1_ms}ms）"),
    };
    let streaming = ProbeStepResult {
        ok: got_chunk,
        latency_ms: step1_ms,
        detail: if got_chunk {
            "流式输出正常".into()
        } else {
            "收到响应但未检测到流式增量".into()
        },
    };

    // Step 3: 极小工具调用能力探测
    let t2 = Instant::now();
    let probe_tools = vec![json!({
        "type": "function",
        "function": {
            "name": "ping_harness",
            "description": "Connectivity probe tool. Call it when requested.",
            "parameters": {
                "type": "object",
                "properties": {
                    "echo": { "type": "string" }
                },
                "required": ["echo"]
            }
        }
    })];
    let probe_msgs = vec![json!({
        "role": "user",
        "content": "Call tool ping_harness with echo='ok'."
    })];

    let tool_res = tokio::time::timeout(
        Duration::from_secs(PROBE_TIMEOUT_SECS),
        stream_chat_completion(
            &normalized,
            api_key,
            model,
            0.0,
            &probe_msgs,
            Some(&probe_tools),
            Some("auto"),
            WebSearchMode::None,
            &cancel,
            |_| {},
            |_| {},
        ),
    )
    .await;
    let step3_ms = t2.elapsed().as_millis() as u64;

    let (supports_tools, tool_calling) = match tool_res {
        Ok(Ok(out)) => {
            if !out.tool_calls.is_empty() {
                (
                    true,
                    ProbeStepResult {
                        ok: true,
                        latency_ms: step3_ms,
                        detail: format!("支持完整智能体工具调用（{step3_ms}ms）"),
                    },
                )
            } else {
                // 接口接受了 tools 参数但模型未触发，仍可视作兼容或保守提醒
                (
                    true,
                    ProbeStepResult {
                        ok: true,
                        latency_ms: step3_ms,
                        detail: "接口接受工具参数（模型偏保守，实际整理时可正常触发）".into(),
                    },
                )
            }
        }
        Ok(Err(diag)) => (
            false,
            ProbeStepResult {
                ok: false,
                latency_ms: step3_ms,
                detail: format!("不支持工具调用（将自动降级为纯对话模式）：{}", diag.title),
            },
        ),
        Err(_) => (
            false,
            ProbeStepResult {
                ok: false,
                latency_ms: step3_ms,
                detail: "工具探测超时，建议先以纯对话模式使用或换更快模型".into(),
            },
        ),
    };

    ProbeResult {
        ok: true,
        normalized_base_url: normalized,
        suggested_base_url: None,
        connectivity,
        streaming,
        tool_calling,
        supports_tools,
        supports_reasoning,
        diagnostic: None,
        probed_at: now,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sse_assembler_reassembles_fragmented_tool_calls_and_reasoning() {
        let mut asm = SseFrameAssembler::default();
        let frames = vec![
            json!({
                "choices": [{ "delta": { "reasoning_content": "先查一下库" } }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_abc",
                            "function": { "name": "query_library", "arguments": "{\"key" }
                        }]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "function": { "arguments": "word\":\"芙莉莲\"}" }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }],
                "usage": { "prompt_tokens": 120, "completion_tokens": 35, "total_tokens": 155 }
            }),
        ];
        let mut reasons = String::new();
        for f in &frames {
            asm.ingest_json(f, |_| {}, |r| reasons.push_str(r));
        }
        let out = asm.into_output(false);
        assert_eq!(reasons, "先查一下库");
        assert_eq!(out.tool_calls.len(), 1);
        assert_eq!(out.tool_calls[0].id, "call_abc");
        assert_eq!(out.tool_calls[0].name, "query_library");
        assert_eq!(out.tool_calls[0].arguments, "{\"keyword\":\"芙莉莲\"}");
        assert!(out.tool_calls[0].extra_content.is_none());
        assert!(out.tool_calls[0].to_openai_wire_value().get("extra_content").is_none());
        assert_eq!(out.usage.unwrap().total_tokens, 155);
    }

    #[test]
    fn sse_assembler_preserves_gemini_thought_signature_and_roundtrips_json() {
        let mut asm = SseFrameAssembler::default();
        let frames = vec![
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_gem_1",
                            "type": "function",
                            "extra_content": {
                                "google": { "thought_signature": "AQIDBAUG_sig==" }
                            },
                            "function": { "name": "query_library", "arguments": "{\"kind\":" }
                        }]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "extra_content": null,
                            "function": { "arguments": "\"anime\"}" }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }]
            }),
        ];
        for f in &frames {
            asm.ingest_json(f, |_| {}, |_| {});
        }
        let out = asm.into_output(false);
        assert_eq!(out.tool_calls.len(), 1);
        let tc = &out.tool_calls[0];
        assert_eq!(tc.id, "call_gem_1");
        assert_eq!(tc.name, "query_library");
        assert_eq!(tc.arguments, "{\"kind\":\"anime\"}");
        assert_eq!(
            tc.extra_content.as_ref().unwrap()["google"]["thought_signature"],
            "AQIDBAUG_sig=="
        );

        // 模拟写入 SQLite 后重新反序列化，再构造上游请求体
        let persisted = serde_json::to_string(&out.tool_calls).unwrap();
        let restored: Vec<ToolCallRecord> = serde_json::from_str(&persisted).unwrap();
        let wire = restored[0].to_openai_wire_value();
        assert_eq!(wire["extra_content"]["google"]["thought_signature"], "AQIDBAUG_sig==");
        assert!(wire.get("extraContent").is_none());
    }

    #[test]
    fn sse_assembler_splits_parallel_tool_calls_with_same_index_zero() {
        let mut asm = SseFrameAssembler::default();
        let frames = vec![
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_1",
                            "extra_content": { "google": { "thought_signature": "sig_first" } },
                            "function": { "name": "load_skill", "arguments": "{\"name\":" }
                        }]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "function": { "arguments": "\"/整理\"}" }
                        }]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_2",
                            "function": { "name": "run_library_diagnostics", "arguments": "{\"scope\":\"all\"}" }
                        }]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call_3",
                            "function": { "name": "get_library_overview", "arguments": "{}" }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }]
            }),
        ];
        for f in &frames {
            asm.ingest_json(f, |_| {}, |_| {});
        }
        let out = asm.into_output(false);
        assert_eq!(out.tool_calls.len(), 3);
        assert_eq!(out.tool_calls[0].id, "call_1");
        assert_eq!(out.tool_calls[0].name, "load_skill");
        assert_eq!(
            serde_json::from_str::<Value>(&out.tool_calls[0].arguments).unwrap(),
            json!({ "name": "/整理" })
        );
        assert!(out.tool_calls[0].extra_content.is_some());

        assert_eq!(out.tool_calls[1].id, "call_2");
        assert_eq!(out.tool_calls[1].name, "run_library_diagnostics");
        assert_eq!(
            serde_json::from_str::<Value>(&out.tool_calls[1].arguments).unwrap(),
            json!({ "scope": "all" })
        );
        assert!(out.tool_calls[1].extra_content.is_none());

        assert_eq!(out.tool_calls[2].id, "call_3");
        assert_eq!(out.tool_calls[2].name, "get_library_overview");
        assert_eq!(
            serde_json::from_str::<Value>(&out.tool_calls[2].arguments).unwrap(),
            json!({})
        );
        assert!(out.tool_calls[2].extra_content.is_none());
    }

    #[test]
    fn sse_assembler_keeps_standard_openai_interleaved_index_streaming() {
        let mut asm = SseFrameAssembler::default();
        let frames = vec![
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [
                            { "index": 0, "id": "oa_1", "function": { "name": "query_library", "arguments": "{\"k" } },
                            { "index": 1, "id": "oa_2", "function": { "name": "get_library_overview", "arguments": "{" } }
                        ]
                    }
                }]
            }),
            json!({
                "choices": [{
                    "delta": {
                        "tool_calls": [
                            { "index": 0, "function": { "arguments": "ind\":\"anime\"}" } },
                            { "index": 1, "function": { "arguments": "}" } }
                        ]
                    },
                    "finish_reason": "tool_calls"
                }]
            }),
        ];
        for f in &frames {
            asm.ingest_json(f, |_| {}, |_| {});
        }
        let out = asm.into_output(false);
        assert_eq!(out.tool_calls.len(), 2);
        assert_eq!(out.tool_calls[0].id, "oa_1");
        assert_eq!(out.tool_calls[0].arguments, "{\"kind\":\"anime\"}");
        assert_eq!(out.tool_calls[1].id, "oa_2");
        assert_eq!(out.tool_calls[1].arguments, "{}");
    }

    #[test]
    fn provider_web_search_payload_injects_only_supported_params() {
        let mut p_ds = json!({ "model": "deepseek-flash", "tools": [] });
        apply_provider_web_search_payload(&mut p_ds, "deepseek-flash", WebSearchMode::DeepSeekTools);
        assert!(p_ds.get("enable_search").is_none());
        assert_eq!(p_ds["model"], "deepseek-flash");

        let mut p_qwen = json!({ "model": "qwen3.7-max" });
        apply_provider_web_search_payload(&mut p_qwen, "qwen3.7-max", WebSearchMode::DashScopeNative);
        assert_eq!(p_qwen["enable_search"], json!(true));

        let mut p_glm = json!({ "model": "glm-5.3", "tools": [] });
        apply_provider_web_search_payload(&mut p_glm, "glm-5.3", WebSearchMode::ZhipuNative);
        assert_eq!(p_glm["tools"][0]["type"], "web_search");

        let mut p_kimi = json!({ "model": "kimi-k3", "tools": [] });
        apply_provider_web_search_payload(&mut p_kimi, "kimi-k3", WebSearchMode::MoonshotNative);
        assert_eq!(p_kimi["tools"][0]["type"], "builtin_function");
        assert_eq!(p_kimi["tools"][0]["function"]["name"], "$web_search");

        let mut p_or = json!({ "model": "deepseek/deepseek-v4" });
        apply_provider_web_search_payload(
            &mut p_or,
            "deepseek/deepseek-v4",
            WebSearchMode::OpenRouterOnline,
        );
        assert_eq!(p_or["model"], "deepseek/deepseek-v4:online");

        let mut p_none = json!({ "model": "qwen3:8b" });
        apply_provider_web_search_payload(&mut p_none, "qwen3:8b", WebSearchMode::None);
        assert!(p_none.get("enable_search").is_none());
        assert!(p_none.get("tools").is_none());
    }
}
