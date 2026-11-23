use crate::agent::types::{DiagnosticError, ProviderPreset, RemoteModelInfo};
use crate::{Result, StudioError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WebSearchMode {
    None,
    /// DeepSeek 官方独立 Search & Fetch API（POST /v1/tools/search & /v1/tools/fetch）
    DeepSeekTools,
    /// 阿里云百炼：请求体附加 enable_search: true
    DashScopeNative,
    /// 智谱 AI：tools 附加 web_search 内置工具
    ZhipuNative,
    /// 月之暗面 Kimi：tools 附加 $web_search 内置函数
    MoonshotNative,
    /// OpenRouter：模型名追加 :online 后缀
    OpenRouterOnline,
}

impl WebSearchMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::DeepSeekTools => "deepseek_tools",
            Self::DashScopeNative => "dashscope_native",
            Self::ZhipuNative => "zhipu_native",
            Self::MoonshotNative => "moonshot_native",
            Self::OpenRouterOnline => "openrouter_online",
        }
    }

    pub fn note(self) -> &'static str {
        match self {
            Self::DeepSeekTools => "复用现有 DeepSeek 密钥，支持官方独立联网搜索与网页正文阅读（按成功次数计费，每千次约 5 元，失败或空结果免费）。",
            Self::DashScopeNative => "支持百炼原生联网搜索（开启后请求附加 enable_search，按服务商规则计费）。",
            Self::ZhipuNative => "支持智谱 GLM 原生联网搜索（开启后自动挂载官方 web_search 能力）。",
            Self::MoonshotNative => "支持 Kimi 原生联网搜索（开启后自动启用官方 $web_search 能力）。",
            Self::OpenRouterOnline => "支持 OpenRouter 联网搜索（开启后自动为所选模型附加 :online 联网后缀）。",
            Self::None => "当前服务商暂无统一原生联网接口；你仍可免费使用追番喵内置的发现页榜单、放送表与番剧/游戏/角色检索，或通过「扩展插件」接入外部搜索服务。",
        }
    }
}

pub fn detect_web_search_mode(provider_id: &str, base_url: &str) -> WebSearchMode {
    let pid = provider_id.trim().to_ascii_lowercase();
    let lower_url = base_url.trim().to_ascii_lowercase();
    if pid == "deepseek" || lower_url.contains("api.deepseek.com") {
        WebSearchMode::DeepSeekTools
    } else if pid == "dashscope" || lower_url.contains("dashscope.aliyuncs.com") {
        WebSearchMode::DashScopeNative
    } else if pid == "zhipu" || lower_url.contains("open.bigmodel.cn") {
        WebSearchMode::ZhipuNative
    } else if pid == "moonshot"
        || lower_url.contains("api.moonshot.cn")
        || lower_url.contains("api.moonshot.ai")
    {
        WebSearchMode::MoonshotNative
    } else if pid == "openrouter" || lower_url.contains("openrouter.ai") {
        WebSearchMode::OpenRouterOnline
    } else {
        WebSearchMode::None
    }
}

pub fn list_provider_presets() -> Vec<ProviderPreset> {
    let mut presets = vec![
        // ================= 国内直连主流厂商 =================
        ProviderPreset {
            id: "deepseek".into(),
            name: "DeepSeek".into(),
            region: "cn".into(),
            base_url: "https://api.deepseek.com/v1".into(),
            default_model: "deepseek-flash".into(),
            recommended_models: vec!["deepseek-flash".into(), "deepseek-v4-pro".into()],
            requires_api_key: true,
            is_local: false,
            badge: Some("主力推荐".into()),
            help_note: "国内直连稳定，首选基准模型：deepseek-flash（V4.1 Flash，约 1M 上下文，高性价比且缓存命中价更低）；deepseek-v4-pro 适合复杂分季/同名难题对照。".into(),
            console_url: Some("https://platform.deepseek.com/api_keys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "dashscope".into(),
            name: "阿里云百炼 (Qwen)".into(),
            region: "cn".into(),
            base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1".into(),
            default_model: "qwen3.8-flash".into(),
            recommended_models: vec![
                "qwen3.8-flash".into(),
                "qwen3.7-flash".into(),
                "ZHIPU/GLM-5.3-Flash".into(),
                "qwen3.7-max".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("国内首选".into()),
            help_note: "国内直连，OpenAI 兼容且支持工具调用：首选 qwen3.8-flash（约 1M，人民币报价有优势），轻量短请求可选 qwen3.7-flash，同端点也可直调托管的 ZHIPU/GLM-5.3-Flash。".into(),
            console_url: Some("https://bailian.console.aliyun.com/".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "zhipu".into(),
            name: "智谱 AI (GLM)".into(),
            region: "cn".into(),
            base_url: "https://open.bigmodel.cn/api/paas/v4".into(),
            default_model: "glm-5.3-flash".into(),
            recommended_models: vec![
                "glm-5.3-flash".into(),
                "glm-5.3".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("高性价比".into()),
            help_note: "国内直连，优先推荐 glm-5.3-flash（工具调用与性价比兼顾）；直连智谱请以点「获取模型」或控制台实际返回的模型 ID 与报价为准。".into(),
            console_url: Some("https://open.bigmodel.cn/usercenter/apikeys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "siliconflow".into(),
            name: "硅基流动".into(),
            region: "cn".into(),
            base_url: "https://api.siliconflow.cn/v1".into(),
            default_model: "deepseek-ai/DeepSeek-V4".into(),
            recommended_models: vec![
                "deepseek-ai/DeepSeek-V4".into(),
                "Qwen/Qwen3-235B-A22B".into(),
                "zai-org/GLM-5.1".into(),
                "moonshotai/Kimi-K2.6".into(),
                "MiniMaxAI/MiniMax-M3".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("国内聚合".into()),
            help_note: "国内直连聚合平台，一个 Key 调用多家开源模型；不同托管商 ID 可能变动，建议点「获取模型」核对实时列表。".into(),
            console_url: Some("https://cloud.siliconflow.cn/account/ak".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "volcengine".into(),
            name: "火山方舟 (豆包)".into(),
            region: "cn".into(),
            base_url: "https://ark.cn-beijing.volces.com/api/v3".into(),
            default_model: "doubao-seed-2-1-lite-260915".into(),
            recommended_models: vec![
                "doubao-seed-2-1-lite-260915".into(),
                "glm-5.3-flash".into(),
                "doubao-seed-2.1-pro".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("国内直连".into()),
            help_note: "国内直连，推荐豆包 Seed 2.1 Lite（约 1M，先用低延迟端点）或方舟托管 glm-5.3-flash；亦支持直接填写控制台推理接入点 ID（如 ep-2026xxxx）。".into(),
            console_url: Some("https://console.volcengine.com/ark".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "hunyuan".into(),
            name: "腾讯混元".into(),
            region: "cn".into(),
            base_url: "https://api.hunyuan.cloud.tencent.com/v1".into(),
            default_model: "hy3".into(),
            recommended_models: vec![
                "hy3".into(),
                "hunyuan-turbos-latest".into(),
                "hunyuan-t1-latest".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("低价补充".into()),
            help_note: "国内直连，混元 Hy3（256K，TokenHub 兼容 ID 为 hy3，参考价约 ¥1/¥4）；若使用旧版混元端点可选 hunyuan-turbos-latest 或点「获取模型」核对账户可用 ID。".into(),
            console_url: Some("https://console.cloud.tencent.com/hunyuan".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "moonshot".into(),
            name: "月之暗面 (Kimi)".into(),
            region: "cn".into(),
            base_url: "https://api.moonshot.cn/v1".into(),
            default_model: "kimi-k3".into(),
            recommended_models: vec![
                "kimi-k3".into(),
                "kimi-k3.1-pro".into(),
                "kimi-k2.5".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("超长上下文".into()),
            help_note: "国内直连，擅长超长上下文；注意 kimi-k3.1-pro 单价明显偏高，仅建议用于复杂任务对照，日常整理优先选性价比 Flash 档（海外可用 api.moonshot.ai）。".into(),
            console_url: Some("https://platform.moonshot.cn/console/api-keys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "minimax".into(),
            name: "MiniMax".into(),
            region: "cn".into(),
            base_url: "https://api.minimax.cn/v1".into(),
            default_model: "MiniMax-M3".into(),
            recommended_models: vec!["MiniMax-M3".into(), "MiniMax-M2.5".into()],
            requires_api_key: true,
            is_local: false,
            badge: None,
            help_note: "国内直连，自然日常对话表现柔和；M3.1 Flash 直连 ID 与报价请点「获取模型」或以 MiniMax 控制台为准（OpenRouter 网关可用 minimax/minimax-m3.1-flash）。".into(),
            console_url: Some("https://platform.minimaxi.com/".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "stepfun".into(),
            name: "阶跃星辰 (StepFun)".into(),
            region: "cn".into(),
            base_url: "https://api.stepfun.com/v1".into(),
            default_model: "step-3.7-flash".into(),
            recommended_models: vec!["step-3.7-flash".into(), "step-2-16k".into()],
            requires_api_key: true,
            is_local: false,
            badge: None,
            help_note: "国内直连，推荐更新至 step-3.7-flash（256K，性价比好；直连端点实际 ID 建议点「获取模型」核对，亦支持通过 OpenRouter 调用 stepfun/step-3.7-flash）。".into(),
            console_url: Some("https://platform.stepfun.com/".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "qianfan".into(),
            name: "百度千帆 V2".into(),
            region: "cn".into(),
            base_url: "https://qianfan.baidubce.com/v2".into(),
            default_model: "ernie-4.0".into(),
            recommended_models: vec!["ernie-4.0".into(), "ernie-3.5-8k".into()],
            requires_api_key: true,
            is_local: false,
            badge: None,
            help_note: "国内直连，百度千帆 V2 标准兼容接口，亦支持调用平台托管的开源旗舰模型。".into(),
            console_url: Some("https://console.bce.baidu.com/qianfan/".into()),
            ..Default::default()
        },

        // ================= 国际主流与多模型聚合 =================
        ProviderPreset {
            id: "gemini".into(),
            name: "Google Gemini".into(),
            region: "global".into(),
            base_url: "https://generativelanguage.googleapis.com/v1beta/openai".into(),
            default_model: "gemini-3.8-flash".into(),
            recommended_models: vec![
                "gemini-3.8-flash".into(),
                "gemini-3.5-flash-lite".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("需海外网络".into()),
            help_note: "【国内需代理】常用首选 gemini-3.8-flash（约 1M，2026-12-31 前优惠价 $0.75/$3.75，2027 年起 $1.50/$7.50），轻量查库选 gemini-3.5-flash-lite（$0.30/$2.50）。高价 3.1 Pro Preview 已从推荐栏撤下（旧配置继续兼容）；gemini-4-hydrogen 待多轮工具回传验收后再上架，可手动填写试用。".into(),
            console_url: Some("https://aistudio.google.com/apikey".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "openrouter".into(),
            name: "OpenRouter".into(),
            region: "global".into(),
            base_url: "https://openrouter.ai/api/v1".into(),
            default_model: "google/gemini-3.8-flash".into(),
            recommended_models: vec![
                "google/gemini-3.8-flash".into(),
                "google/gemini-3.5-flash-lite".into(),
                "minimax/minimax-m3.1-flash".into(),
                "stepfun/step-3.7-flash".into(),
                "deepseek/deepseek-v4".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("全球聚合".into()),
            help_note: "【海外聚合 · 国内建议配合代理】推荐栏均为 OpenRouter 实测/核对过的网关可调用 ID，兼顾性价比与工具调用；也可点「获取模型」搜索全部海外模型。".into(),
            console_url: Some("https://openrouter.ai/keys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "openai".into(),
            name: "OpenAI".into(),
            region: "global".into(),
            base_url: "https://api.openai.com/v1".into(),
            default_model: "gpt-chat-latest".into(),
            recommended_models: vec![
                "gpt-chat-latest".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("需海外网络".into()),
            help_note: "【国内无法直连，需海外网络或改用中转站】注意 GPT-6.1/6.2（Terra/Sol/Astra）官方工具调用依赖 Responses API、GPT-6 Luna 需显式关闭思考档，暂不放入 Chat Completions 通用推荐；使用第三方中转请选「自定义 / 中转服务」。".into(),
            console_url: Some("https://platform.openai.com/api-keys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "mistral".into(),
            name: "Mistral AI".into(),
            region: "global".into(),
            base_url: "https://api.mistral.ai/v1".into(),
            default_model: "mistral-large-4".into(),
            recommended_models: vec![
                "mistral-large-4".into(),
                "mistral-medium-latest".into(),
                "mistral-small-latest".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("海外中价".into()),
            help_note: "【海外服务 · 国内直连不稳定时需代理】mistral-large-4（$0.70/$2.10，约 1M）支持 Chat Completions 函数调用，可作海外中价备选。".into(),
            console_url: Some("https://console.mistral.ai/api-keys".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "xai".into(),
            name: "xAI (Grok)".into(),
            region: "global".into(),
            base_url: "https://api.x.ai/v1".into(),
            default_model: "grok-4.7".into(),
            recommended_models: vec!["grok-4.7".into(), "grok-4.6".into()],
            requires_api_key: true,
            is_local: false,
            badge: Some("需海外网络".into()),
            help_note: "【国内需代理】Grok 4.7（<200K $2/$6）偏海外测试备选，日常高频追番整理优先推荐国内直连 Flash 系列以控制成本。".into(),
            console_url: Some("https://console.x.ai/".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "groq".into(),
            name: "Groq (极速推理)".into(),
            region: "global".into(),
            base_url: "https://api.groq.com/openai/v1".into(),
            default_model: "openai/gpt-oss-120b".into(),
            recommended_models: vec![
                "openai/gpt-oss-120b".into(),
                "qwen/qwen3.8-27b".into(),
                "openai/gpt-oss-20b".into(),
            ],
            requires_api_key: true,
            is_local: false,
            badge: Some("需海外网络".into()),
            help_note: "【国内需代理】LPU 硬件极速推理，吐字延迟极低，适合快速聊番与实时查询。".into(),
            console_url: Some("https://console.groq.com/keys".into()),
            ..Default::default()
        },

        // ================= 本地零费用免 Key =================
        ProviderPreset {
            id: "ollama".into(),
            name: "本地 Ollama".into(),
            region: "local".into(),
            base_url: "http://127.0.0.1:11434/v1".into(),
            default_model: "qwen3:8b".into(),
            recommended_models: vec![
                "qwen3:8b".into(),
                "qwen3:14b".into(),
                "deepseek-r1:8b".into(),
            ],
            requires_api_key: false,
            is_local: true,
            badge: Some("本地免 Key".into()),
            help_note: "完全运行在你本机，无需填写 API Key，零费用且数据不出电脑。点「获取模型」直接扫描本机已拉取模型。".into(),
            console_url: Some("https://ollama.com".into()),
            ..Default::default()
        },
        ProviderPreset {
            id: "lmstudio".into(),
            name: "本地 LM Studio".into(),
            region: "local".into(),
            base_url: "http://127.0.0.1:1234/v1".into(),
            default_model: String::new(),
            recommended_models: vec![],
            requires_api_key: false,
            is_local: true,
            badge: Some("本地免 Key".into()),
            help_note: "先在 LM Studio 启动 Local Server（默认 1234 端口），点「获取模型」自动识别当前加载模型。".into(),
            console_url: Some("https://lmstudio.ai".into()),
            ..Default::default()
        },

        // ================= 自定义 / 中转站 =================
        ProviderPreset {
            id: "custom".into(),
            name: "自定义 / 中转服务".into(),
            region: "custom".into(),
            base_url: String::new(),
            default_model: String::new(),
            recommended_models: vec![],
            requires_api_key: true,
            is_local: false,
            badge: Some("任意兼容".into()),
            help_note: "兼容任何 OpenAI 格式的第三方中转站（OneAPI / NewAPI / 自建网关等），支持自动清洗 /chat/completions 与 404 自动补 /v1。".into(),
            console_url: None,
            web_search_mode: String::new(),
            web_search_note: String::new(),
        },
    ];
    for p in &mut presets {
        let mode = detect_web_search_mode(&p.id, &p.base_url);
        p.web_search_mode = mode.as_str().to_string();
        p.web_search_note = mode.note().to_string();
    }
    presets
}

/// 智能归一化并安全校验 Base URL（复用 Android 端 HTTPS/Localhost 策略）
pub fn normalize_base_url(raw: &str) -> Result<String> {
    let mut clean = raw.trim().replace('\\', "/");
    while clean.ends_with('/') {
        clean.pop();
    }
    if clean.is_empty() {
        return Err(StudioError::InvalidInput("请填写模型接口地址（Base URL）".into()));
    }
    for suffix in ["/chat/completions", "/completions", "/models"] {
        if clean.to_ascii_lowercase().ends_with(suffix) {
            let new_len = clean.len() - suffix.len();
            clean.truncate(new_len);
            while clean.ends_with('/') {
                clean.pop();
            }
        }
    }
    let parsed = reqwest::Url::parse(&clean)
        .map_err(|_| StudioError::InvalidInput("接口地址格式不正确，请以 https:// 或本机 http://127.0.0.1 开头".into()))?;
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err(StudioError::InvalidInput("接口地址中请勿包含账号或密码信息".into()));
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return Err(StudioError::InvalidInput("接口地址中请勿包含查询参数（?）或锚点（#）".into()));
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| StudioError::InvalidInput("接口地址缺少有效域名或主机地址".into()))?
        .to_ascii_lowercase();
    let is_local = matches!(host.as_str(), "localhost" | "127.0.0.1" | "::1" | "[::1]");
    let scheme = parsed.scheme().to_ascii_lowercase();
    if scheme != "https" && !(scheme == "http" && is_local) {
        return Err(StudioError::InvalidInput(
            "远程模型接口必须使用 HTTPS；仅本机调试地址（127.0.0.1 / localhost）允许 HTTP".into(),
        ));
    }
    let mut normalized = parsed.to_string();
    while normalized.ends_with('/') {
        normalized.pop();
    }
    Ok(normalized)
}

pub fn is_local_base_url(normalized_url: &str) -> bool {
    reqwest::Url::parse(normalized_url)
        .ok()
        .and_then(|u| u.host_str().map(|h| h.to_ascii_lowercase()))
        .map(|h| matches!(h.as_str(), "localhost" | "127.0.0.1" | "::1" | "[::1]"))
        .unwrap_or(false)
}

/// 当地址疑似漏写 `/v1` 时生成建议地址
pub fn suggest_v1_url(normalized_url: &str) -> Option<String> {
    let lower = normalized_url.to_ascii_lowercase();
    if lower.ends_with("/v1")
        || lower.ends_with("/v2")
        || lower.ends_with("/v3")
        || lower.ends_with("/openai")
        || lower.contains("/v1/")
        || lower.contains("/v3/")
    {
        return None;
    }
    Some(format!("{}/v1", normalized_url.trim_end_matches('/')))
}

/// 过滤非对话模型（embedding/rerank/audio/image/moderation 等）
pub fn filter_chat_models(raw_models: Vec<RemoteModelInfo>) -> (Vec<RemoteModelInfo>, usize) {
    const EXCLUDE_KEYWORDS: &[&str] = &[
        "embedding",
        "embed-",
        "rerank",
        "bge-",
        "whisper",
        "tts-",
        "-tts",
        "dall-e",
        "flux",
        "stable-diffusion",
        "sdxl",
        "midjourney",
        "moderation",
        "text-moderation",
        "speech",
        "transcribe",
        "realtime",
        "sora",
        "wanx",
        "kolors",
        "cosyvoice",
        "fish-speech",
        "sensevoice",
    ];
    let mut kept = Vec::new();
    let mut filtered = 0usize;
    for m in raw_models {
        let lower = m.id.to_ascii_lowercase();
        if EXCLUDE_KEYWORDS.iter().any(|k| lower.contains(k)) {
            filtered += 1;
        } else {
            kept.push(m);
        }
    }
    kept.sort_by(|a, b| a.id.cmp(&b.id));
    kept.dedup_by(|a, b| a.id == b.id);
    (kept, filtered)
}

pub fn mask_api_key(key: &str) -> Option<String> {
    let trimmed = key.trim();
    if trimmed.is_empty() {
        return None;
    }
    let chars: Vec<char> = trimmed.chars().collect();
    if chars.len() <= 8 {
        return Some("••••••••".into());
    }
    let head: String = chars[..4].iter().collect();
    let tail: String = chars[chars.len() - 4..].iter().collect();
    Some(format!("{head}••••{tail}"))
}

/// 将任意底层 HTTP / 网络 / 厂商错误翻译为结构化「人话诊断」（绝不泄露 API Key）
pub fn classify_http_error(
    status: u16,
    body_snippet: &str,
    base_url: &str,
    model: &str,
) -> DiagnosticError {
    let lower = body_snippet.to_ascii_lowercase();
    let safe_body = sanitize_error_snippet(body_snippet);
    let tech = format!("HTTP {status} @ {base_url} (model={model})\n{safe_body}");

    if status == 401
        || lower.contains("invalid_api_key")
        || lower.contains("incorrect api key")
        || lower.contains("authentication")
        || lower.contains("unauthorized")
    {
        return DiagnosticError {
            code: "AUTH_FAILED".into(),
            title: "API Key 无效或已过期".into(),
            explanation: "模型服务商拒绝了当前的 API Key 鉴权。".into(),
            suggestion: "请核对是否多粘了空格、选错了对应的服务商，或前往服务商控制台重新生成一个 Key。".into(),
            quick_fix: Some("open_config".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 402
        || lower.contains("insufficient_quota")
        || lower.contains("insufficient balance")
        || lower.contains("balance is not enough")
        || lower.contains("quota exceeded")
        || lower.contains("arrearage")
    {
        return DiagnosticError {
            code: "INSUFFICIENT_QUOTA".into(),
            title: "账户余额不足或额度已用完".into(),
            explanation: "你的模型服务商账户当前没有可用余额或免费额度已耗尽。".into(),
            suggestion: "请前往服务商后台充值，或者在左下角切换到其他已配置的模型档案。".into(),
            quick_fix: Some("open_config".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 403 {
        return DiagnosticError {
            code: "FORBIDDEN".into(),
            title: "无权访问该模型或地区受限".into(),
            explanation: "当前账号或网络环境没有权限调用所选模型。".into(),
            suggestion: "请检查账号是否已在后台开通该模型、确认模型名正确，或检查网络代理设置。".into(),
            quick_fix: Some("open_config".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 404 || lower.contains("model_not_found") || lower.contains("does not exist") {
        let suggested = suggest_v1_url(base_url);
        let suggestion = if let Some(ref s) = suggested {
            format!("接口地址疑似缺少版本号路径，建议尝试改为 {s}；如果地址无误，请点击「获取模型」重新核对模型名称。")
        } else {
            "请确认模型名称拼写无误（可点击「获取模型」从列表选择，火山方舟请填写推理接入点 ep-xxxx）。".into()
        };
        return DiagnosticError {
            code: "NOT_FOUND".into(),
            title: "接口路径不正确，或模型名称不存在".into(),
            explanation: "服务端返回 404，找不到对应的接口路径或所填模型。".into(),
            suggestion,
            quick_fix: if suggested.is_some() {
                Some("apply_suggested_url".into())
            } else {
                Some("open_config".into())
            },
            suggested_base_url: suggested,
            technical_detail: tech,
        };
    }

    if status == 400
        && (lower.contains("preceding message with 'tool_calls'")
            || lower.contains("role 'tool'")
            || lower.contains("tool_call_id"))
    {
        return DiagnosticError {
            code: "INVALID_MESSAGE_SEQUENCE".into(),
            title: "历史上下文消息序列异常".into(),
            explanation: "上游接口拒绝了包含孤立或未闭合工具记录的对话上下文（并非当前模型不支持工具调用）。".into(),
            suggestion: "建议点击「重试」或新开一段对话继续；系统已自动开启历史上下文自愈过滤。".into(),
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 400 && lower.contains("thought_signature") {
        return DiagnosticError {
            code: "MISSING_THOUGHT_SIGNATURE".into(),
            title: "上游要求工具调用携带思考签名（thought_signature）".into(),
            explanation: "当前历史对话中存在缺少签名字段的旧工具调用记录，导致上游拒绝继续续传（并非模型不支持工具调用）。".into(),
            suggestion: "建议点击「+ 新对话」重新发起任务；新产生的对话已自动透传工具调用签名。".into(),
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 400
        && (lower.contains("tool")
            || lower.contains("function")
            || lower.contains("schema")
            || lower.contains("tool_choice"))
    {
        return DiagnosticError {
            code: "TOOLS_UNSUPPORTED".into(),
            title: "当前模型或接口不支持工具调用".into(),
            explanation: "所选模型无法解析智能体工具定义（Function Calling）。".into(),
            suggestion: "可以一键切换为「仅对话模式」继续聊番，或改用支持工具调用的现役模型（如 deepseek-flash、qwen3.8-flash、glm-5.3-flash、gemini-3.8-flash）。".into(),
            quick_fix: Some("disable_tools".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status == 429 || lower.contains("rate limit") || lower.contains("too many requests") {
        return DiagnosticError {
            code: "RATE_LIMITED".into(),
            title: "请求太频繁，触发了服务商限流".into(),
            explanation: "短时间内并发或请求次数超过了当前渠道的速率限制。".into(),
            suggestion: "请稍等 3～5 秒后点重试；免费渠道可降低并发频率或切换备用档案。".into(),
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    if status >= 500 {
        return DiagnosticError {
            code: "UPSTREAM_5XX".into(),
            title: "模型服务商服务器暂时繁忙".into(),
            explanation: format!("远端接口返回 HTTP {status}，属于模型服务商侧临时波动，并非追番喵故障。"),
            suggestion: "建议稍后重试，或在左下角临时切换到其他已配置的备用模型。".into(),
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: tech,
        };
    }

    DiagnosticError {
        code: format!("HTTP_{status}"),
        title: format!("模型接口返回异常（HTTP {status}）"),
        explanation: "服务端拒绝了本次请求，可能是参数、上下文长度或模型限制所致。".into(),
        suggestion: "可展开下方技术详情查看具体原因，或尝试新开会话 / 切换模型重试。".into(),
        quick_fix: Some("retry".into()),
        suggested_base_url: None,
        technical_detail: tech,
    }
}

pub fn classify_network_error(err: &str, base_url: &str) -> DiagnosticError {
    let lower = err.to_ascii_lowercase();
    let is_local = is_local_base_url(base_url);
    let safe_err = sanitize_error_snippet(err);

    if lower.contains("timed out") || lower.contains("timeout") {
        return DiagnosticError {
            code: "TIMEOUT".into(),
            title: "连接或等待回复超时".into(),
            explanation: "模型长时间未返回新数据，已按防卡死护栏自动中止。".into(),
            suggestion: if is_local {
                "本地模型首次加载显存或推理较慢，请确认电脑资源充足后重试。".into()
            } else {
                "请检查网络连接或代理稳定性，也可切换响应更快的模型重试。".into()
            },
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: format!("Network timeout @ {base_url}\n{safe_err}"),
        };
    }

    if is_local
        && (lower.contains("connect")
            || lower.contains("refused")
            || lower.contains("actively refused")
            || lower.contains("10061"))
    {
        return DiagnosticError {
            code: "LOCAL_SERVICE_OFFLINE".into(),
            title: "未检测到运行中的本地模型服务".into(),
            explanation: format!("无法连接到本机地址 {base_url}，本地服务尚未启动或端口不一致。"),
            suggestion: "请先启动本机的 Ollama（默认 11434 端口）或 LM Studio Local Server（默认 1234 端口），然后再试。".into(),
            quick_fix: Some("retry".into()),
            suggested_base_url: None,
            technical_detail: format!("Local connection refused @ {base_url}\n{safe_err}"),
        };
    }

    DiagnosticError {
        code: "NETWORK_ERROR".into(),
        title: "无法连接到模型接口服务器".into(),
        explanation: "网络握手或 TLS 证书校验失败，请求未能送达目标服务器。".into(),
        suggestion: "请检查 Base URL 是否拼写正确、网络是否通畅，以及系统代理设置是否正常。".into(),
        quick_fix: Some("open_config".into()),
        suggested_base_url: None,
        technical_detail: format!("Network error @ {base_url}\n{safe_err}"),
    }
}

pub(crate) fn redact_credentials(raw: &str) -> String {
    static CREDENTIAL_RE: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"(?i)(sk-[a-z0-9_\-]{8,}|bearer\s+[a-z0-9_\-\.]{8,})").unwrap()
    });
    CREDENTIAL_RE.replace_all(raw, "[REDACTED]").into_owned()
}

fn sanitize_error_snippet(raw: &str) -> String {
    redact_credentials(raw).chars().take(600).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_strips_chat_completions_and_validates_scheme() {
        assert_eq!(
            normalize_base_url("  https://api.deepseek.com/v1/chat/completions/  ").unwrap(),
            "https://api.deepseek.com/v1"
        );
        assert_eq!(
            normalize_base_url("http://127.0.0.1:11434/v1/models").unwrap(),
            "http://127.0.0.1:11434/v1"
        );
        assert!(normalize_base_url("http://example.com/v1").is_err());
        assert!(normalize_base_url("https://user:pass@example.com/v1").is_err());
        assert!(normalize_base_url("https://example.com/v1?key=123").is_err());
    }

    #[test]
    fn suggest_v1_works_only_when_missing() {
        assert_eq!(
            suggest_v1_url("https://api.deepseek.com").as_deref(),
            Some("https://api.deepseek.com/v1")
        );
        assert_eq!(suggest_v1_url("https://api.deepseek.com/v1"), None);
        assert_eq!(
            suggest_v1_url("https://ark.cn-beijing.volces.com/api/v3"),
            None
        );
    }

    #[test]
    fn filter_chat_models_removes_non_chat() {
        let input = vec![
            RemoteModelInfo { id: "deepseek-chat".into(), owned_by: None },
            RemoteModelInfo { id: "BAAI/bge-m3-embedding".into(), owned_by: None },
            RemoteModelInfo { id: "whisper-1".into(), owned_by: None },
            RemoteModelInfo { id: "Qwen/Qwen2.5-72B-Instruct".into(), owned_by: None },
        ];
        let (kept, removed) = filter_chat_models(input);
        assert_eq!(removed, 2);
        assert_eq!(kept.len(), 2);
    }

    #[test]
    fn diagnostic_redacts_api_keys() {
        let diag = classify_http_error(
            401,
            "Invalid key sk-1234567890abcdef1234567890",
            "https://api.deepseek.com/v1",
            "deepseek-chat",
        );
        assert_eq!(diag.code, "AUTH_FAILED");
        assert!(!diag.technical_detail.contains("sk-1234567890"));
        assert!(diag.technical_detail.contains("[REDACTED]"));
    }

    #[test]
    fn provider_presets_match_2026_11_22_selection_baseline() {
        let presets = list_provider_presets();
        let gemini = presets.iter().find(|p| p.id == "gemini").expect("gemini preset");
        assert_eq!(gemini.default_model, "gemini-3.8-flash");
        assert!(gemini.recommended_models.contains(&"gemini-3.8-flash".to_string()));
        assert!(gemini.recommended_models.contains(&"gemini-3.5-flash-lite".to_string()));
        assert!(!gemini.recommended_models.contains(&"gemini-3.1-pro-preview".to_string()));

        let dashscope = presets.iter().find(|p| p.id == "dashscope").expect("dashscope preset");
        assert_eq!(dashscope.default_model, "qwen3.8-flash");
        assert!(dashscope.recommended_models.contains(&"qwen3.8-flash".to_string()));

        let zhipu = presets.iter().find(|p| p.id == "zhipu").expect("zhipu preset");
        assert_eq!(zhipu.default_model, "glm-5.3-flash");

        for p in presets.iter().filter(|p| p.region == "global") {
            assert!(
                p.help_note.contains("代理") || p.help_note.contains("海外"),
                "海外服务商 {} 应包含面向国内用户的网络/代理提示",
                p.id
            );
        }
    }

    #[test]
    fn thought_signature_400_is_not_misclassified_as_tools_unsupported() {
        let diag = classify_http_error(
            400,
            "INVALID_ARGUMENT: Function call is missing a thought_signature in functionCall parts.",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-4-hydrogen",
        );
        assert_eq!(diag.code, "MISSING_THOUGHT_SIGNATURE");
        assert_ne!(diag.code, "TOOLS_UNSUPPORTED");
    }
}
