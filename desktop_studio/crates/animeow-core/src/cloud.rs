use crate::dpapi::{
    clear_cloud_session, load_cloud_session, save_cloud_session, PublicBangumiProfile,
    PublicCloudSessionInfo, StoredCloudSession,
};
use crate::studio_features::RemoteCatalogHit;
use crate::{Result, StudioError};
use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use chrono::Utc;
use reqwest::redirect::Policy;
use reqwest::{Client, Method, StatusCode, Url};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::Path;
use std::time::Duration;
use uuid::Uuid;

pub const DEFAULT_CLOUD_API_BASE: &str = "https://www.xunlys.top";
const CLOUD_CONFIG_FILE: &str = "cloud_config.json";
const STUDIO_UA: &str = "AniMeow-Studio/0.2.0 (Windows; Rust/Tauri2)";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudEndpointConfig {
    pub api_base: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudBackupRow {
    pub id: i64,
    pub file_name: String,
    pub payload_size: i64,
    pub content_hash: Option<String>,
    pub device_id: Option<String>,
    pub client_modified_at: Option<String>,
    pub upload_date: Option<String>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommunityPublishResult {
    pub post_id: i64,
    pub status_note: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BangumiAuthorizeStart {
    pub authorize_url: String,
    pub state: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BangumiDiffRow {
    pub subject_id: i64,
    pub local_anime_id: Option<i64>,
    pub title: String,
    pub diff_kind: String, // "changed" | "only_remote" | "only_local"
    pub local_summary: String,
    pub remote_summary: String,
    pub remote_status: String,
    pub remote_watched_eps: i64,
    pub remote_total_eps: i64,
    pub remote_rate_10: Option<i64>,
    pub remote_cover_url: String,
    pub remote_air_date: String,
    pub remote_comment: String,
}

/// 校验并规范化云端服务器基址（WIN-01：公网连接强制 HTTPS，仅允许回环与 RFC1918 局域网开发地址使用 HTTP）
pub fn validate_cloud_base_url(raw: &str) -> Result<String> {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(StudioError::InvalidInput("云端服务地址不能为空".into()));
    }
    let parsed = Url::parse(trimmed)
        .map_err(|_| StudioError::InvalidInput("云端服务地址格式无效".into()))?;
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err(StudioError::InvalidInput(
            "云端服务地址禁止内嵌账号或密码".into(),
        ));
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return Err(StudioError::InvalidInput(
            "云端服务基址不能包含查询参数或锚点".into(),
        ));
    }

    let scheme = parsed.scheme().to_ascii_lowercase();
    let host = parsed
        .host_str()
        .ok_or_else(|| StudioError::InvalidInput("云端服务地址缺少主机名".into()))?
        .to_ascii_lowercase();

    match scheme.as_str() {
        "https" => {}
        "http" => {
            if !is_local_or_private_dev_host(&host) {
                return Err(StudioError::InvalidInput(
                    "安全限制：公网服务器必须使用 HTTPS 加密连接；仅本机 (127.0.0.1/localhost) 或局域网调试环境允许 HTTP"
                        .into(),
                ));
            }
        }
        _ => {
            return Err(StudioError::InvalidInput(
                "云端服务地址仅支持 HTTPS 协议".into(),
            ));
        }
    }

    Ok(parsed.as_str().trim_end_matches('/').to_string())
}

fn is_local_or_private_dev_host(host: &str) -> bool {
    let clean = host.trim_start_matches('[').trim_end_matches(']');
    if clean == "localhost" || clean == "127.0.0.1" || clean == "::1" {
        return true;
    }
    let parts: Vec<u8> = clean
        .split('.')
        .filter_map(|p| p.parse::<u8>().ok())
        .collect();
    if parts.len() == 4 {
        if parts[0] == 10 {
            return true;
        }
        if parts[0] == 192 && parts[1] == 168 {
            return true;
        }
        if parts[0] == 172 && (16..=31).contains(&parts[1]) {
            return true;
        }
    }
    false
}

/// 百分号解码并校验相对 API / 预设资源路径（WIN-04：防止 `..` 穿越、协议切换或向第三方域泄露会话令牌）
pub fn resolve_safe_cloud_url(validated_base: &str, relative_path: &str) -> Result<Url> {
    let base = Url::parse(&format!("{}/", validated_base.trim_end_matches('/')))
        .map_err(|_| StudioError::InvalidInput("基础地址解析失败".into()))?;

    let rel = relative_path.trim();
    if rel.is_empty()
        || rel.starts_with("http://")
        || rel.starts_with("https://")
        || rel.starts_with("//")
        || rel.contains('\\')
    {
        return Err(StudioError::InvalidInput(
            "安全拦截：禁止跨源或绝对路径请求携带凭据".into(),
        ));
    }

    let decoded = percent_decode_lossy(rel);
    if decoded.contains('\\')
        || decoded.contains('\0')
        || decoded.starts_with("//")
        || decoded.contains("://")
    {
        return Err(StudioError::InvalidInput(
            "安全拦截：解码后路径包含非法跳转字符".into(),
        ));
    }

    let path_part = decoded.split('?').next().unwrap_or(&decoded);
    for seg in path_part.split('/') {
        if seg == ".." || seg == "." {
            return Err(StudioError::InvalidInput(
                "安全拦截：禁止相对路径上级目录穿越 (..)".into(),
            ));
        }
    }

    let joined = base
        .join(rel.trim_start_matches('/'))
        .map_err(|_| StudioError::InvalidInput("请求路径拼接无效".into()))?;

    if joined.scheme() != base.scheme()
        || joined.host_str() != base.host_str()
        || joined.port_or_known_default() != base.port_or_known_default()
    {
        return Err(StudioError::InvalidInput(
            "安全拦截：目标地址偏离已配置的云端服务源".into(),
        ));
    }

    Ok(joined)
}

fn percent_decode_lossy(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

pub fn load_cloud_endpoint_config(studio_data_dir: &Path) -> CloudEndpointConfig {
    let file = studio_data_dir.join(CLOUD_CONFIG_FILE);
    if let Ok(raw) = std::fs::read_to_string(&file) {
        if let Ok(cfg) = serde_json::from_str::<CloudEndpointConfig>(&raw) {
            if let Ok(valid) = validate_cloud_base_url(&cfg.api_base) {
                return CloudEndpointConfig { api_base: valid };
            }
        }
    }
    CloudEndpointConfig {
        api_base: DEFAULT_CLOUD_API_BASE.to_string(),
    }
}

pub fn save_cloud_endpoint_config(
    studio_data_dir: &Path,
    api_base: &str,
) -> Result<CloudEndpointConfig> {
    let valid = validate_cloud_base_url(api_base)?;
    std::fs::create_dir_all(studio_data_dir)?;
    let cfg = CloudEndpointConfig { api_base: valid };
    std::fs::write(
        studio_data_dir.join(CLOUD_CONFIG_FILE),
        serde_json::to_vec_pretty(&cfg)?,
    )?;
    Ok(cfg)
}

fn build_secure_http_client(timeout_secs: u64) -> Result<Client> {
    // 禁用自动重定向以杜绝 HTTPS -> HTTP 静默降级或跨域转发 Authorization 头
    Client::builder()
        .user_agent(STUDIO_UA)
        .redirect(Policy::none())
        .timeout(Duration::from_secs(timeout_secs))
        .build()
        .map_err(|e| StudioError::InvalidInput(format!("初始化安全网络客户端失败：{e}")))
}

async fn cloud_json_request_raw(
    studio_data_dir: &Path,
    method: Method,
    rel_path: &str,
    body: Option<Value>,
    require_auth: bool,
    timeout_secs: u64,
) -> Result<Value> {
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let url = resolve_safe_cloud_url(&cfg.api_base, rel_path)?;
    let client = build_secure_http_client(timeout_secs)?;

    let pinned_session: Option<StoredCloudSession> = if require_auth {
        let sess = load_cloud_session(studio_data_dir)?
            .ok_or_else(|| StudioError::InvalidInput("请先登录追番喵云端账号".into()))?;
        Some(sess)
    } else {
        load_cloud_session(studio_data_dir).ok().flatten()
    };

    let mut req = client.request(method, url).header("Accept", "application/json");
    if let Some(ref sess) = pinned_session {
        if require_auth {
            req = req.header("Authorization", format!("Bearer {}", sess.token));
        }
    }
    if let Some(json_body) = body {
        req = req.json(&json_body);
    }

    let resp = req.send().await.map_err(|_| {
        StudioError::InvalidInput("无法连接追番喵云端服务，请检查网络或稍后重试".into())
    })?;

    let status = resp.status();
    let text = resp.text().await.unwrap_or_default();
    let parsed: Value = serde_json::from_str(&text).unwrap_or_else(|_| json!({}));

    if !status.is_success() {
        let server_msg = parsed
            .get("message")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| format!("云端请求未通过（HTTP {}）", status.as_u16()));

        // WIN-03 & 必读规范 #38：仅在明确的 401 会话失效且本机未切换新账号时清理旧会话；
        // 网络故障、5xx、429、409 (如 Bangumi 令牌过期) 绝不误清用户主会话
        if status == StatusCode::UNAUTHORIZED
            && (server_msg.contains("登录会话已失效") || server_msg.contains("请先登录"))
        {
            if let Some(ref pinned) = pinned_session {
                if let Ok(Some(current)) = load_cloud_session(studio_data_dir) {
                    if current.token == pinned.token {
                        let _ = clear_cloud_session(studio_data_dir);
                    }
                }
            }
        }
        return Err(StudioError::InvalidInput(server_msg));
    }

    if let Some("error") = parsed.get("status").and_then(|v| v.as_str()) {
        let msg = parsed
            .get("message")
            .and_then(|v| v.as_str())
            .unwrap_or("云端返回失败状态");
        return Err(StudioError::InvalidInput(msg.to_string()));
    }

    Ok(parsed)
}

async fn cloud_json_request(
    studio_data_dir: &Path,
    method: Method,
    rel_path: &str,
    body: Option<Value>,
    require_auth: bool,
    timeout_secs: u64,
) -> Result<Value> {
    let parsed = cloud_json_request_raw(
        studio_data_dir,
        method,
        rel_path,
        body,
        require_auth,
        timeout_secs,
    )
    .await?;
    Ok(parsed.get("data").cloned().unwrap_or(parsed))
}

fn extract_public_bangumi(val: Option<&Value>) -> Option<PublicBangumiProfile> {
    let obj = val?.as_object()?;
    let id = obj.get("id")?.as_i64().filter(|&v| v > 0)?;
    let nickname = obj
        .get("nickname")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let username = obj
        .get("username")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .or_else(|| nickname.clone())
        .unwrap_or_else(|| id.to_string());
    let avatar = obj
        .get("avatar")
        .and_then(|v| {
            v.as_str().map(str::to_string).or_else(|| {
                v.get("large")
                    .or_else(|| v.get("medium"))
                    .and_then(|s| s.as_str())
                    .map(str::to_string)
            })
        })
        .filter(|s| !s.is_empty());
    Some(PublicBangumiProfile {
        id,
        username,
        nickname,
        avatar,
    })
}

pub async fn cloud_login_password(
    studio_data_dir: &Path,
    username: &str,
    password: &str,
) -> Result<PublicCloudSessionInfo> {
    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/login",
        Some(json!({
            "username": username.trim(),
            "password": password,
        })),
        false,
        20,
    )
    .await?;

    let saved = persist_auth_response(studio_data_dir, &data)?;
    // 登录接口默认不含 Bangumi 绑定摘要，立刻补拉一次 /api/user/me 同步跨端绑定状态
    Ok(cloud_refresh_session_profile(studio_data_dir)
        .await
        .unwrap_or(saved))
}

pub async fn cloud_register_account(
    studio_data_dir: &Path,
    username: &str,
    password: &str,
    device_registration_id: &str,
) -> Result<PublicCloudSessionInfo> {
    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/register",
        Some(json!({
            "username": username.trim(),
            "password": password,
            "device_registration_id": device_registration_id,
        })),
        false,
        20,
    )
    .await?;

    persist_auth_response(studio_data_dir, &data)
}

pub async fn cloud_login_device(
    studio_data_dir: &Path,
    device_registration_id: &str,
) -> Result<PublicCloudSessionInfo> {
    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/device-login",
        Some(json!({
            "device_registration_id": device_registration_id,
        })),
        false,
        20,
    )
    .await?;

    let saved = persist_auth_response(studio_data_dir, &data)?;
    Ok(cloud_refresh_session_profile(studio_data_dir)
        .await
        .unwrap_or(saved))
}

fn persist_auth_response(studio_data_dir: &Path, data: &Value) -> Result<PublicCloudSessionInfo> {
    let user = data
        .get("user")
        .ok_or_else(|| StudioError::InvalidInput("登录响应缺少用户信息".into()))?;
    let user_id = user
        .get("id")
        .and_then(|v| v.as_i64())
        .filter(|&id| id > 0)
        .ok_or_else(|| StudioError::InvalidInput("登录响应用户 ID 无效".into()))?;
    let uname = user
        .get("username")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| StudioError::InvalidInput("登录响应用户名无效".into()))?;
    let token = data
        .get("token")
        .and_then(|v| v.as_str())
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| StudioError::InvalidInput("登录响应缺少会话令牌".into()))?;
    let bgm = extract_public_bangumi(data.get("bangumi").or_else(|| user.get("bangumi")));

    let saved = save_cloud_session(studio_data_dir, user_id, uname, token, bgm)?;
    Ok(PublicCloudSessionInfo::from_stored(&saved))
}

pub async fn cloud_refresh_session_profile(
    studio_data_dir: &Path,
) -> Result<PublicCloudSessionInfo> {
    let Some(existing) = load_cloud_session(studio_data_dir)? else {
        return Ok(PublicCloudSessionInfo::logged_out());
    };
    let data = cloud_json_request(studio_data_dir, Method::GET, "api/user/me", None, true, 15).await?;
    let user = data.get("user").unwrap_or(&data);
    let bgm = extract_public_bangumi(user.get("bangumi"));
    if let Ok(Some(now_sess)) = load_cloud_session(studio_data_dir) {
        if now_sess.token == existing.token && now_sess.bangumi != bgm {
            let updated = save_cloud_session(
                studio_data_dir,
                now_sess.user_id,
                &now_sess.username,
                &now_sess.token,
                bgm,
            )?;
            return Ok(PublicCloudSessionInfo::from_stored(&updated));
        }
        return Ok(PublicCloudSessionInfo::from_stored(&now_sess));
    }
    Ok(PublicCloudSessionInfo::logged_out())
}

pub async fn cloud_list_backups(studio_data_dir: &Path) -> Result<Vec<CloudBackupRow>> {
    let data = cloud_json_request(
        studio_data_dir,
        Method::GET,
        "api/user/backups",
        None,
        true,
        20,
    )
    .await?;
    let arr = data
        .as_array()
        .ok_or_else(|| StudioError::InvalidInput("云备份列表格式异常".into()))?;
    let mut rows = Vec::with_capacity(arr.len());
    for item in arr {
        if let Some(id) = item.get("id").and_then(|v| v.as_i64()) {
            rows.push(CloudBackupRow {
                id,
                file_name: item
                    .get("file_name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("AniMeow.zip")
                    .to_string(),
                payload_size: item
                    .get("payload_size")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0),
                content_hash: item
                    .get("content_hash")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                device_id: item
                    .get("device_id")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                client_modified_at: item
                    .get("client_modified_at")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                upload_date: item
                    .get("upload_date")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                created_at: item
                    .get("created_at")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
            });
        }
    }
    Ok(rows)
}

pub async fn cloud_upload_backup_file(
    studio_data_dir: &Path,
    zip_path: &Path,
    device_id: &str,
) -> Result<CloudBackupRow> {
    let bytes = std::fs::read(zip_path)?;
    if bytes.is_empty() {
        return Err(StudioError::InvalidInput("待上传备份文件为空".into()));
    }
    let mut hasher = Sha256::new();
    hasher.update(&bytes);
    let content_hash = hex::encode(hasher.finalize());
    let b64 = BASE64_STD.encode(&bytes);
    let file_name = zip_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("AniMeow_Studio.animeow.zip");
    let now_iso = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);

    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/user/backups",
        Some(json!({
            "file_name": file_name,
            "backup_base64": b64,
            "content_hash": content_hash,
            "device_id": device_id,
            "client_modified_at": now_iso,
        })),
        true,
        90,
    )
    .await?;

    Ok(CloudBackupRow {
        id: data.get("id").and_then(|v| v.as_i64()).unwrap_or(0),
        file_name: data
            .get("file_name")
            .and_then(|v| v.as_str())
            .unwrap_or(file_name)
            .to_string(),
        payload_size: data
            .get("payload_size")
            .and_then(|v| v.as_i64())
            .unwrap_or(bytes.len() as i64),
        content_hash: Some(content_hash),
        device_id: Some(device_id.to_string()),
        client_modified_at: Some(now_iso.clone()),
        upload_date: data
            .get("upload_date")
            .and_then(|v| v.as_str())
            .map(str::to_string),
        created_at: data
            .get("created_at")
            .and_then(|v| v.as_str())
            .unwrap_or(&now_iso)
            .to_string(),
    })
}

/// 下载指定云备份到本机暂存目录并校验 SHA-256，供三栏差异对账器直接预检与合并恢复
pub async fn cloud_download_backup_to_staging(
    studio_data_dir: &Path,
    backup_id: i64,
    expected_hash: Option<&str>,
) -> Result<String> {
    if backup_id <= 0 {
        return Err(StudioError::InvalidInput("云备份编号无效".into()));
    }
    let sess = load_cloud_session(studio_data_dir)?
        .ok_or_else(|| StudioError::InvalidInput("请先登录追番喵云端账号".into()))?;
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let url = resolve_safe_cloud_url(
        &cfg.api_base,
        &format!("api/user/backups/{backup_id}/download"),
    )?;
    let client = build_secure_http_client(90)?;

    let resp = client
        .get(url)
        .header("Authorization", format!("Bearer {}", sess.token))
        .send()
        .await
        .map_err(|_| StudioError::InvalidInput("下载云端备份失败，请检查网络连接".into()))?;

    if !resp.status().is_success() {
        return Err(StudioError::InvalidInput(format!(
            "下载云端备份被拒绝（HTTP {}）",
            resp.status().as_u16()
        )));
    }

    let etag_hash = resp
        .headers()
        .get("etag")
        .and_then(|v| v.to_str().ok())
        .map(|s| s.trim_matches('"').to_ascii_lowercase())
        .filter(|s| s.len() == 64);

    let bytes = resp
        .bytes()
        .await
        .map_err(|_| StudioError::InvalidInput("读取云端备份数据流中断".into()))?;

    let mut hasher = Sha256::new();
    hasher.update(&bytes);
    let actual_hash = hex::encode(hasher.finalize());

    if let Some(exp) = expected_hash
        .map(str::trim)
        .filter(|s| s.len() == 64)
        .map(str::to_ascii_lowercase)
        .or(etag_hash)
    {
        if actual_hash != exp {
            return Err(StudioError::InvalidInput(
                "云备份下载完整性校验未通过（SHA-256 不一致），已丢弃暂存文件".into(),
            ));
        }
    }

    let staging_dir = studio_data_dir.join("cloud_staging");
    std::fs::create_dir_all(&staging_dir)?;
    let out_path = staging_dir.join(format!("cloud-backup-{backup_id}.animeow.zip"));
    std::fs::write(&out_path, &bytes)?;
    Ok(out_path.to_string_lossy().into_owned())
}

pub async fn cloud_delete_backup(studio_data_dir: &Path, backup_id: i64) -> Result<()> {
    let _ = cloud_json_request(
        studio_data_dir,
        Method::DELETE,
        &format!("api/user/backups/{backup_id}"),
        None,
        true,
        20,
    )
    .await?;
    Ok(())
}

/// 三栏漫评室单向发布长评到社区（不加载信息流瀑布流，仅做单向作品书评投递）
pub async fn cloud_publish_review_post(
    studio_data_dir: &Path,
    headline: &str,
    work_title: &str,
    carrier_label: &str,
    personal_score: Option<f64>,
    spoiler_warning: bool,
    review_markdown: &str,
) -> Result<CommunityPublishResult> {
    let body_trimmed = review_markdown.trim();
    if body_trimmed.is_empty() {
        return Err(StudioError::InvalidInput("漫评正文不能为空".into()));
    }

    let mut composed = String::new();
    let clean_head = headline.trim();
    if !clean_head.is_empty() {
        composed.push_str(&format!("【{}】\n", clean_head));
    }
    let score_badge = personal_score
        .map(|s| format!(" · 个人评分 {:.1}/10", s))
        .unwrap_or_default();
    composed.push_str(&format!(
        "关联{}：《{}》{}\n",
        carrier_label, work_title, score_badge
    ));
    if spoiler_warning {
        composed.push_str("提示：本文包含关键剧情解析与剧透，请酌情阅读。\n");
    }
    composed.push('\n');
    composed.push_str(body_trimmed);

    let tags = vec![
        format!("{}漫评", carrier_label),
        work_title.chars().take(24).collect::<String>(),
    ];

    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/community/posts",
        Some(json!({
            "content": composed,
            "tags": tags,
            "images": [],
        })),
        true,
        30,
    )
    .await?;

    let post_id = data
        .get("id")
        .or_else(|| data.get("post").and_then(|p| p.get("id")))
        .and_then(|v| v.as_i64())
        .unwrap_or(0);

    Ok(CommunityPublishResult {
        post_id,
        status_note: if post_id > 0 {
            format!("已成功投递至追番喵社区（帖子 #{post_id}）")
        } else {
            "已提交至追番喵社区".into()
        },
    })
}

// ============================================================================
// Bangumi OAuth 与双向中继对账
// ============================================================================

pub async fn bangumi_start_authorize(studio_data_dir: &Path) -> Result<BangumiAuthorizeStart> {
    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/bangumi/authorize-url",
        Some(json!({})),
        false,
        15,
    )
    .await?;
    let authorize_url = data
        .get("authorize_url")
        .and_then(|v| v.as_str())
        .filter(|s| s.starts_with("https://"))
        .ok_or_else(|| StudioError::InvalidInput("未获取到有效的 Bangumi HTTPS 授权地址".into()))?
        .to_string();
    let state = data
        .get("state")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| StudioError::InvalidInput("未获取到 Bangumi 授权 state".into()))?
        .to_string();
    Ok(BangumiAuthorizeStart {
        authorize_url,
        state,
    })
}

/// 完成 Bangumi OAuth 换票（支持粘贴 `animeow://oauth/bangumi?...` 回调链接或 `ticket` / `code`）
pub async fn bangumi_complete_oauth(
    studio_data_dir: &Path,
    callback_or_ticket: &str,
    expected_state: Option<&str>,
) -> Result<PublicCloudSessionInfo> {
    let trimmed = callback_or_ticket.trim();
    if trimmed.is_empty() {
        return Err(StudioError::InvalidInput(
            "请粘贴浏览器完成授权后的回调链接或一次性票据".into(),
        ));
    }

    let payload = if trimmed.starts_with("animeow://") || trimmed.starts_with("https://") {
        let parsed = Url::parse(trimmed)
            .map_err(|_| StudioError::InvalidInput("回调链接格式无法解析".into()))?;
        let mut qp: HashMap<String, String> = HashMap::new();
        for (k, v) in parsed.query_pairs() {
            qp.insert(k.into_owned(), v.into_owned());
        }
        if qp.contains_key("error") {
            return Err(StudioError::InvalidInput(
                "Bangumi 授权已取消或未通过，请重新发起授权".into(),
            ));
        }
        if let Some(exp) = expected_state.filter(|s| !s.is_empty()) {
            if let Some(got_state) = qp.get("state") {
                if got_state != exp {
                    return Err(StudioError::InvalidInput(
                        "授权 state 校验不匹配，请重新发起授权".into(),
                    ));
                }
            }
        }
        if let Some(ticket) = qp.get("ticket").filter(|s| !s.is_empty()) {
            json!({ "ticket": ticket })
        } else if let (Some(code), Some(st)) = (qp.get("code"), qp.get("state")) {
            json!({ "code": code, "state": st })
        } else {
            return Err(StudioError::InvalidInput(
                "回调链接缺少 ticket 或 code 参数".into(),
            ));
        }
    } else {
        json!({ "ticket": trimmed })
    };

    let has_session = load_cloud_session(studio_data_dir)?.is_some();
    let data = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/bangumi/exchange",
        Some(payload),
        has_session,
        25,
    )
    .await?;

    persist_auth_response(studio_data_dir, &data)
}

pub async fn bangumi_unbind_account(studio_data_dir: &Path) -> Result<PublicCloudSessionInfo> {
    let sess = load_cloud_session(studio_data_dir)?
        .ok_or_else(|| StudioError::InvalidInput("请先登录追番喵账号".into()))?;
    let _ = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/bangumi/unbind",
        Some(json!({})),
        true,
        15,
    )
    .await?;
    let updated = save_cloud_session(
        studio_data_dir,
        sess.user_id,
        &sess.username,
        &sess.token,
        None,
    )?;
    Ok(PublicCloudSessionInfo::from_stored(&updated))
}

fn bgm_type_to_status(t: i64) -> &'static str {
    match t {
        1 => "未看",
        2 => "看完",
        3 => "在看",
        4 => "搁置",
        5 => "弃坑",
        _ => "在看",
    }
}

fn status_to_bgm_type(status: &str) -> i64 {
    match status.trim() {
        "未看" | "想看" => 1,
        "看完" => 2,
        "在看" => 3,
        "搁置" => 4,
        "弃坑" => 5,
        _ => 3,
    }
}

/// 通过后端 Bangumi 中继拉取本人番剧收藏并与本机 `animeow.db` 生成三栏差异对账单
pub async fn bangumi_preview_diff(
    studio_data_dir: &Path,
    local_animes: Vec<(i64, String, String, i64, i64, Option<i64>, Option<String>)>,
) -> Result<Vec<BangumiDiffRow>> {
    let mut remote_rows = Vec::new();
    let mut offset = 0_usize;
    let mut total_expected: Option<usize> = None;

    while total_expected.is_none() || remote_rows.len() < total_expected.unwrap_or(0) {
        let page = cloud_json_request(
            studio_data_dir,
            Method::GET,
            &format!(
                "api/auth/bangumi/relay/me/collections?subject_type=2&limit=50&offset={offset}"
            ),
            None,
            true,
            25,
        )
        .await?;

        let total = page
            .get("total")
            .and_then(|v| v.as_u64())
            .ok_or_else(|| StudioError::InvalidInput("Bangumi 收藏总数返回异常".into()))?
            as usize;
        let arr = page
            .get("data")
            .and_then(|v| v.as_array())
            .ok_or_else(|| StudioError::InvalidInput("Bangumi 收藏分页列表格式异常".into()))?;

        if let Some(prev_total) = total_expected {
            if prev_total != total {
                return Err(StudioError::InvalidInput(
                    "Bangumi 收藏列表在读取期间发生变化，请重新点击预览对账".into(),
                ));
            }
        }
        total_expected = Some(total);
        if arr.is_empty() {
            break;
        }
        offset += arr.len();
        remote_rows.extend(arr.iter().cloned());
        if offset >= 2000 {
            break;
        }
    }

    // 索引本机已关联 Bangumi subject_id 的动画：(id, title, status, watched, total, rating100, ext_id)
    let mut local_by_bgm: HashMap<i64, (i64, String, String, i64, i64, Option<i64>)> =
        HashMap::new();
    for (id, title, status, watched, total, rating, ext_id) in local_animes {
        if let Some(sid) = ext_id.as_deref().and_then(|s| s.parse::<i64>().ok()) {
            local_by_bgm.insert(sid, (id, title, status, watched, total, rating));
        }
    }

    let mut diffs = Vec::new();
    let mut seen_subjects = std::collections::HashSet::new();

    for raw in &remote_rows {
        let Some(subject_id) = raw.get("subject_id").and_then(|v| v.as_i64()) else {
            continue;
        };
        seen_subjects.insert(subject_id);
        let sub = raw.get("subject").unwrap_or(&Value::Null);
        let r_title = sub
            .get("name_cn")
            .and_then(|v| v.as_str())
            .filter(|s| !s.trim().is_empty())
            .or_else(|| sub.get("name").and_then(|v| v.as_str()))
            .unwrap_or("Bangumi 番剧")
            .trim()
            .to_string();
        let r_type = raw.get("type").and_then(|v| v.as_i64()).unwrap_or(3);
        let r_status = bgm_type_to_status(r_type).to_string();
        let r_ep = raw.get("ep_status").and_then(|v| v.as_i64()).unwrap_or(0);
        let r_total = sub.get("eps").and_then(|v| v.as_i64()).unwrap_or(0);
        let r_rate = raw
            .get("rate")
            .and_then(|v| v.as_i64())
            .filter(|&r| (1..=10).contains(&r));
        let r_cover = sub
            .get("images")
            .and_then(|img| {
                img.get("large")
                    .or_else(|| img.get("common"))
                    .and_then(|v| v.as_str())
            })
            .unwrap_or("")
            .replace("https://lain.bgm.tv", "https://img-bgm.xunlys.top");
        let r_date = sub
            .get("date")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let r_comment = raw
            .get("comment")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let remote_summary = format!(
            "{} · {}/{} 集 · {}",
            r_status,
            r_ep,
            if r_total > 0 {
                r_total.to_string()
            } else {
                "?".into()
            },
            r_rate
                .map(|r| format!("{r}分"))
                .unwrap_or_else(|| "未评".into())
        );

        if let Some((lid, l_title, l_status, l_watched, l_total, l_rating)) =
            local_by_bgm.get(&subject_id)
        {
            let l_rate_10 = l_rating.filter(|&r| r > 0).map(|r| (r + 5) / 10);
            if *l_status != r_status || *l_watched != r_ep || l_rate_10 != r_rate {
                let local_summary = format!(
                    "{} · {}/{} 集 · {}",
                    l_status,
                    l_watched,
                    if *l_total > 0 {
                        l_total.to_string()
                    } else {
                        "?".into()
                    },
                    l_rate_10
                        .map(|r| format!("{r}分"))
                        .unwrap_or_else(|| "未评".into())
                );
                diffs.push(BangumiDiffRow {
                    subject_id,
                    local_anime_id: Some(*lid),
                    title: l_title.clone(),
                    diff_kind: "changed".into(),
                    local_summary,
                    remote_summary,
                    remote_status: r_status,
                    remote_watched_eps: r_ep,
                    remote_total_eps: r_total,
                    remote_rate_10: r_rate,
                    remote_cover_url: r_cover,
                    remote_air_date: r_date,
                    remote_comment: r_comment,
                });
            }
        } else {
            diffs.push(BangumiDiffRow {
                subject_id,
                local_anime_id: None,
                title: r_title,
                diff_kind: "only_remote".into(),
                local_summary: "本机未收录".into(),
                remote_summary,
                remote_status: r_status,
                remote_watched_eps: r_ep,
                remote_total_eps: r_total,
                remote_rate_10: r_rate,
                remote_cover_url: r_cover,
                remote_air_date: r_date,
                remote_comment: r_comment,
            });
        }
    }

    for (sid, (lid, l_title, l_status, l_watched, l_total, l_rating)) in local_by_bgm {
        if !seen_subjects.contains(&sid) {
            let l_rate_10 = l_rating.filter(|&r| r > 0).map(|r| (r + 5) / 10);
            diffs.push(BangumiDiffRow {
                subject_id: sid,
                local_anime_id: Some(lid),
                title: l_title,
                diff_kind: "only_local".into(),
                local_summary: format!(
                    "{} · {}/{} 集 · {}",
                    l_status,
                    l_watched,
                    if l_total > 0 {
                        l_total.to_string()
                    } else {
                        "?".into()
                    },
                    l_rate_10
                        .map(|r| format!("{r}分"))
                        .unwrap_or_else(|| "未评".into())
                ),
                remote_summary: "Bangumi 未收藏".into(),
                remote_status: String::new(),
                remote_watched_eps: 0,
                remote_total_eps: l_total,
                remote_rate_10: None,
                remote_cover_url: String::new(),
                remote_air_date: String::new(),
                remote_comment: String::new(),
            });
        }
    }

    Ok(diffs)
}

/// 将选定的 Bangumi 差异项拉取合并进本机 `animeow.db`
pub fn bangumi_apply_pull_to_local(
    sync_conn: &mut Connection,
    rows: &[BangumiDiffRow],
    prefer_remote_on_conflict: bool,
) -> Result<usize> {
    let tx = sync_conn.transaction()?;
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let mut applied = 0_usize;

    for row in rows {
        if row.diff_kind == "only_local" {
            continue;
        }
        let ext_id = row.subject_id.to_string();
        let rating_100 = row.remote_rate_10.map(|r| r * 10);
        let existing_id = tx
            .query_row(
                "SELECT id FROM animes WHERE externalSource = 'bangumi' AND externalId = ?1",
                params![ext_id],
                |r| r.get::<_, i64>(0),
            )
            .optional()?;

        if let Some(aid) = existing_id {
            if prefer_remote_on_conflict {
                tx.execute(
                    "UPDATE animes SET
                        deletedAt = NULL,
                        status = ?1,
                        watchedEpisodes = ?2,
                        totalEpisodes = CASE WHEN ?3 > 0 THEN ?3 ELSE totalEpisodes END,
                        rating = COALESCE(?4, rating),
                        review = CASE WHEN (review IS NULL OR review = '') AND ?5 != '' THEN ?5 ELSE review END
                     WHERE id = ?6",
                    params![
                        row.remote_status,
                        row.remote_watched_eps,
                        row.remote_total_eps,
                        rating_100,
                        row.remote_comment,
                        aid
                    ],
                )?;
                applied += 1;
            }
        } else {
            tx.execute(
                "INSERT INTO animes (
                    title, coverUrl, status, rating, review, createdAt, airDate,
                    watchedEpisodes, totalEpisodes, subjectType, externalSource, externalId
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, 'anime', 'bangumi', ?10)",
                params![
                    row.title,
                    (!row.remote_cover_url.is_empty()).then_some(row.remote_cover_url.as_str()),
                    row.remote_status,
                    rating_100,
                    (!row.remote_comment.is_empty()).then_some(row.remote_comment.as_str()),
                    now,
                    (!row.remote_air_date.is_empty()).then_some(row.remote_air_date.as_str()),
                    row.remote_watched_eps,
                    row.remote_total_eps,
                    ext_id,
                ],
            )?;
            applied += 1;
        }
    }

    tx.commit()?;
    Ok(applied)
}

/// 将单部本机动画的状态、评分与已看集数上报至 Bangumi 中继
pub async fn bangumi_push_local_anime(
    studio_data_dir: &Path,
    subject_id: i64,
    local_status: &str,
    local_watched_eps: i64,
    local_rating_100: Option<i64>,
) -> Result<()> {
    let bgm_type = status_to_bgm_type(local_status);
    let mut col_body = json!({
        "subject_id": subject_id,
        "type": bgm_type,
    });
    if let Some(r100) = local_rating_100.filter(|&r| r > 0) {
        let rate_10 = ((r100 + 5) / 10).clamp(1, 10);
        col_body["rate"] = json!(rate_10);
    }

    let _ = cloud_json_request(
        studio_data_dir,
        Method::POST,
        "api/auth/bangumi/relay/collection",
        Some(col_body),
        true,
        25,
    )
    .await?;

    if local_watched_eps > 0 {
        let _ = cloud_json_request(
            studio_data_dir,
            Method::POST,
            "api/auth/bangumi/relay/episodes",
            Some(json!({
                "subject_id": subject_id,
                "watched_until": local_watched_eps,
                "ensure_collection": true,
            })),
            true,
            35,
        )
        .await?;
    }

    Ok(())
}

// ============================================================================
// 多源公开资料检索：VNDB / 云端 Galgame 联合检索 & DLsite RJ 音声元数据直解
// ============================================================================

pub async fn search_vndb_and_cloud_games(
    studio_data_dir: &Path,
    keyword: &str,
) -> Result<Vec<RemoteCatalogHit>> {
    let q = keyword.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }

    // 优先经 AniMeow 公开游戏缓存网关联合检索 Bangumi + VNDB
    let encoded_q = urlencoding_component(q);
    if let Ok(val) = cloud_json_request(
        studio_data_dir,
        Method::GET,
        &format!("api/games/search?q={encoded_q}&includeVndb=1"),
        None,
        false,
        14,
    )
    .await
    {
        let arr_opt = val
            .as_array()
            .or_else(|| val.get("data").and_then(|d| d.as_array()));
        if let Some(arr) = arr_opt {
            let mut hits = Vec::new();
            for item in arr.iter().take(20) {
                let title = item
                    .get("title")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                if title.is_empty() {
                    continue;
                }
                let vndb_id = item.get("vndbId").and_then(|v| v.as_str());
                let bgm_id = item.get("id").and_then(|v| v.as_i64());
                let (ext_src, ext_id) = if let Some(bid) = bgm_id.filter(|&id| id > 0) {
                    ("bangumi".to_string(), bid.to_string())
                } else if let Some(vid) = vndb_id.filter(|s| !s.is_empty()) {
                    ("vndb".to_string(), vid.to_string())
                } else {
                    ("vndb".to_string(), format!("vn-{}", Uuid::new_v4()))
                };

                hits.push(RemoteCatalogHit {
                    kind: "game".into(),
                    external_source: ext_src,
                    external_id: ext_id,
                    title,
                    original_title: item
                        .get("originalTitle")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    cover_url: item
                        .get("coverUrl")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    release_date: item
                        .get("releaseDate")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    total_episodes: 0,
                    public_score: item
                        .get("score")
                        .and_then(|v| v.as_f64())
                        .filter(|&s| s > 0.0),
                    studio_or_circle: item
                        .get("developer")
                        .or_else(|| item.get("publisher"))
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    synopsis: item
                        .get("synopsis")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .chars()
                        .take(240)
                        .collect(),
                });
            }
            if !hits.is_empty() {
                return Ok(hits);
            }
        }
    }

    search_vndb_catalog(q, false).await
}

pub async fn search_vndb_catalog(q: &str, direct: bool) -> Result<Vec<RemoteCatalogHit>> {
    let filter = if direct { "id" } else { "search" };
    let client = build_secure_http_client(12)?;
    let resp = client
        .post("https://api.vndb.org/kana/vn")
        .json(&json!({
            "filters": [filter, "=", q],
            "fields": "id,title,alttitle,released,rating,description,image.url,developers.name",
            "results": 16
        }))
        .send()
        .await
        .map_err(|_| StudioError::InvalidInput("VNDB 游戏资料库暂时无法连接".into()))?;
    if !resp.status().is_success() {
        return Err(StudioError::InvalidInput(format!("VNDB 查询失败（{}）", resp.status())));
    }

    let body: Value = resp
        .json()
        .await
        .map_err(|_| StudioError::InvalidInput("VNDB 响应解析失败".into()))?;
    let mut hits = Vec::new();
    if let Some(arr) = body.get("results").and_then(|v| v.as_array()) {
        for item in arr {
            let vid = item.get("id").and_then(|v| v.as_str()).unwrap_or("");
            let orig = item.get("title").and_then(|v| v.as_str()).unwrap_or("");
            let alt = item
                .get("alttitle")
                .and_then(|v| v.as_str())
                .filter(|s| !s.trim().is_empty())
                .unwrap_or(orig);
            let devs = item
                .get("developers")
                .and_then(|v| v.as_array())
                .map(|list| {
                    list.iter()
                        .filter_map(|d| d.get("name").and_then(|n| n.as_str()))
                        .collect::<Vec<_>>()
                        .join(" / ")
                })
                .unwrap_or_default();
            let score = item
                .get("rating")
                .and_then(|v| v.as_f64())
                .filter(|&r| r > 0.0)
                .map(|r| (r / 10.0 * 10.0).round() / 10.0);
            hits.push(RemoteCatalogHit {
                kind: "game".into(),
                external_source: "vndb".into(),
                external_id: vid.to_string(),
                title: alt.to_string(),
                original_title: orig.to_string(),
                cover_url: item
                    .get("image")
                    .and_then(|img| img.get("url"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                release_date: item
                    .get("released")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                total_episodes: 0,
                public_score: score,
                studio_or_circle: devs,
                synopsis: item
                    .get("description")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .chars()
                    .take(240)
                    .collect(),
            });
        }
    }
    Ok(hits)
}

pub async fn lookup_dlsite_audio_catalog(
    studio_data_dir: &Path,
    input: &str,
) -> Result<Vec<RemoteCatalogHit>> {
    let q = input.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }
    let upper = q.to_ascii_uppercase();
    let is_rj_direct = (upper.starts_with("RJ")
        && (8..=10).contains(&upper.len())
        && upper[2..].bytes().all(|b| b.is_ascii_digit()))
        || ((6..=8).contains(&upper.len()) && upper.bytes().all(|b| b.is_ascii_digit()))
        || q.starts_with("https://www.dlsite.com/");

    let encoded = urlencoding_component(q);
    if is_rj_direct {
        let data = cloud_json_request(
            studio_data_dir,
            Method::GET,
            &format!("api/audio/metadata?input={encoded}"),
            None,
            false,
            18,
        )
        .await?;
        let code = data
            .get("workCode")
            .and_then(|v| v.as_str())
            .unwrap_or(&upper)
            .to_string();
        let circle = data
            .get("circle")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let vas = data
            .get("voiceActors")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let tags = data
            .get("tags")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        return Ok(vec![RemoteCatalogHit {
            kind: "audio".into(),
            external_source: "dlsite".into(),
            external_id: code.clone(),
            title: data
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or(&code)
                .to_string(),
            original_title: vas.clone(), // 携带声优名单以便入库时直接回填 voiceActors
            cover_url: data
                .get("coverUrl")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            release_date: data
                .get("releaseDate")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            total_episodes: 1,
            public_score: None,
            studio_or_circle: circle,
            synopsis: if !vas.is_empty() {
                format!("CV：{vas} · 标签：{tags}")
            } else {
                tags
            },
        }]);
    }

    let data = cloud_json_request(
        studio_data_dir,
        Method::GET,
        &format!("api/audio/search?q={encoded}"),
        None,
        false,
        18,
    )
    .await?;
    let mut hits = Vec::new();
    if let Some(items) = data.get("items").and_then(|v| v.as_array()) {
        for item in items {
            let code = item
                .get("workCode")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let title = item
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if code.is_empty() || title.is_empty() {
                continue;
            }
            hits.push(RemoteCatalogHit {
                kind: "audio".into(),
                external_source: "dlsite".into(),
                external_id: code.clone(),
                title,
                original_title: String::new(),
                cover_url: item
                    .get("coverUrl")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                release_date: String::new(),
                total_episodes: 1,
                public_score: None,
                studio_or_circle: item
                    .get("circle")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                synopsis: format!("DLsite 作品编号：{code}（点击入库或输入编号精确补全声优）"),
            });
        }
    }
    Ok(hits)
}

// ============================================================================
// 在线追番播放源网关（复用 Android 端 /api/playback/* 同源服务契约）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackSource {
    pub id: String,
    pub name: String,
    pub version: String,
    pub rules_version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackWork {
    pub id: String,
    pub title: String,
    pub cover_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackEpisode {
    pub id: String,
    pub label: String,
    pub number: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackLine {
    pub id: String,
    pub name: String,
    pub episodes: Vec<PlaybackEpisode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaybackStream {
    pub url: String,
    pub mime_type: String,
    pub headers: HashMap<String, String>,
}

fn is_public_playback_host(host: &str) -> bool {
    let lower = host.to_ascii_lowercase();
    if lower.is_empty()
        || !lower.contains('.')
        || lower.starts_with('[')
        || lower == "localhost"
        || lower.ends_with(".local")
        || lower.ends_with(".internal")
        || lower.ends_with(".localhost")
    {
        return false;
    }
    if lower.parse::<std::net::IpAddr>().is_ok() {
        return false;
    }
    true
}

pub fn is_valid_playback_url(raw: &str) -> bool {
    let Ok(parsed) = reqwest::Url::parse(raw.trim()) else {
        return false;
    };
    if parsed.scheme() != "https" || !parsed.username().is_empty() || parsed.password().is_some() {
        return false;
    }
    if let Some(port) = parsed.port() {
        if port != 443 {
            return false;
        }
    }
    let Some(host) = parsed.host_str() else {
        return false;
    };
    is_public_playback_host(host)
}

/// 本地 HLS 代理重写分片校验：允许公网域名 http/https，依然严格拒绝回环、内网与裸 IP
pub fn is_valid_proxy_segment_url(raw: &str) -> bool {
    let Ok(parsed) = reqwest::Url::parse(raw.trim()) else {
        return false;
    };
    if !matches!(parsed.scheme(), "https" | "http")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return false;
    }
    let Some(host) = parsed.host_str() else {
        return false;
    };
    is_public_playback_host(host)
}

pub async fn fetch_playback_sources(studio_data_dir: &Path) -> Result<Vec<PlaybackSource>> {
    let val = cloud_json_request(
        studio_data_dir,
        Method::GET,
        "api/playback/sources",
        None,
        false,
        15,
    )
    .await?;
    let mut out = Vec::new();
    let rows_opt = val
        .as_array()
        .or_else(|| val.get("data").and_then(|d| d.as_array()));
    if let Some(rows) = rows_opt {
        for r in rows {
            let id = r.get("id").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
            let name = r.get("name").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
            if id.is_empty() || name.is_empty() {
                continue;
            }
            out.push(PlaybackSource {
                id,
                name,
                version: r.get("version").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                rules_version: r
                    .get("rulesVersion")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
            });
        }
    }
    Ok(out)
}

pub async fn search_playback_works(
    studio_data_dir: &Path,
    source: &str,
    query: &str,
) -> Result<Vec<PlaybackWork>> {
    let q = query.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }
    let path = format!(
        "api/playback/search?source={}&q={}",
        urlencoding_component(source.trim()),
        urlencoding_component(q)
    );
    let val = cloud_json_request(studio_data_dir, Method::GET, &path, None, false, 18).await?;
    let mut out = Vec::new();
    let rows_opt = val
        .as_array()
        .or_else(|| val.get("data").and_then(|d| d.as_array()));
    if let Some(rows) = rows_opt {
        for r in rows {
            let id = r.get("id").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
            let title = r.get("title").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
            if id.is_empty() || title.is_empty() {
                continue;
            }
            out.push(PlaybackWork {
                id,
                title,
                cover_url: r.get("coverUrl").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            });
        }
    }
    Ok(out)
}

pub async fn fetch_playback_episodes(
    studio_data_dir: &Path,
    source: &str,
    work_id: &str,
) -> Result<Vec<PlaybackLine>> {
    let path = format!(
        "api/playback/episodes?source={}&workId={}",
        urlencoding_component(source.trim()),
        urlencoding_component(work_id.trim())
    );
    let val = cloud_json_request(studio_data_dir, Method::GET, &path, None, false, 18).await?;
    let mut lines = Vec::new();
    let arr_opt = val
        .get("lines")
        .or_else(|| val.get("data").and_then(|d| d.get("lines")))
        .and_then(|l| l.as_array());
    if let Some(arr) = arr_opt {
        for line in arr {
            let id = line.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
            let name = line.get("name").and_then(|v| v.as_str()).unwrap_or("默认线路").to_string();
            let mut eps = Vec::new();
            if let Some(ep_arr) = line.get("episodes").and_then(|e| e.as_array()) {
                for ep in ep_arr {
                    let eid = ep.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                    let label = ep.get("label").and_then(|v| v.as_str()).unwrap_or("").to_string();
                    if eid.is_empty() {
                        continue;
                    }
                    let number = ep.get("number").and_then(|v| v.as_i64()).filter(|&n| n > 0);
                    eps.push(PlaybackEpisode {
                        id: eid,
                        label,
                        number,
                    });
                }
            }
            lines.push(PlaybackLine {
                id,
                name,
                episodes: eps,
            });
        }
    }
    Ok(lines)
}

pub async fn resolve_playback_stream(
    studio_data_dir: &Path,
    source: &str,
    work_id: &str,
    episode_id: &str,
) -> Result<PlaybackStream> {
    let path = format!(
        "api/playback/resolve?source={}&workId={}&episodeId={}",
        urlencoding_component(source.trim()),
        urlencoding_component(work_id.trim()),
        urlencoding_component(episode_id.trim())
    );
    let val = cloud_json_request(studio_data_dir, Method::GET, &path, None, false, 22).await?;
    let data = val.get("data").unwrap_or(&val);
    let url = data
        .get("url")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    if !is_valid_playback_url(&url) {
        return Err(StudioError::InvalidInput(
            "来源返回的播放地址不可用，请切换其他线路".into(),
        ));
    }
    let mime_type = data
        .get("mimeType")
        .and_then(|v| v.as_str())
        .unwrap_or("application/x-mpegURL")
        .to_string();
    let mut headers = HashMap::new();
    if let Some(obj) = data.get("headers").and_then(|v| v.as_object()) {
        for key in ["Referer", "User-Agent"] {
            if let Some(hv) = obj.get(key).and_then(|v| v.as_str()) {
                let clean = hv.trim();
                if !clean.is_empty() && !clean.contains('\r') && !clean.contains('\n') {
                    headers.insert(key.to_string(), clean.to_string());
                }
            }
        }
    }
    Ok(PlaybackStream {
        url,
        mime_type,
        headers,
    })
}

// ============================================================================
// 每日放送表（Bangumi 公开 Calendar API）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BangumiCalendarItem {
    pub id: i64,
    pub title: String,
    pub original_title: String,
    pub cover_url: String,
    pub air_date: String,
    pub score: Option<f64>,
    pub rating_count: i64,
    pub rank: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BangumiCalendarDay {
    pub weekday_id: i64,
    pub weekday_cn: String,
    pub weekday_en: String,
    pub items: Vec<BangumiCalendarItem>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BangumiCalendarResponse {
    pub days: Vec<BangumiCalendarDay>,
    pub source_label: String,
    pub updated_at: Option<String>,
    pub stale: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryFeedRequest {
    pub kind: String,
    pub feed: String,
    pub sort: Option<String>,
    pub query: Option<String>,
    pub year: Option<i32>,
    pub month: Option<i32>,
    pub page: Option<u32>,
    #[serde(default)]
    pub tag: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryItem {
    pub kind: String,
    pub external_source: String,
    pub external_id: String,
    pub title: String,
    pub original_title: String,
    pub cover_url: String,
    pub air_date: String,
    pub score: Option<f64>,
    pub rating_count: i64,
    pub rank: Option<i64>,
    pub total_episodes: i64,
    pub summary: String,
    pub tags: Vec<String>,
    pub studio_or_dev: String,
    pub media_format: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryPage {
    pub items: Vec<DiscoveryItem>,
    pub page: u32,
    pub has_more: bool,
    pub total: i64,
    pub source_label: String,
    pub updated_at: Option<String>,
    pub stale: bool,
}

const BGM_MIRROR_API_BASE: &str = "https://api-bgm.xunlys.top";
const BGM_MIRROR_IMG_BASE: &str = "https://img-bgm.xunlys.top";

/// 将 Bangumi 封面与服务端相对封面路径统一转换为国内直连可用的 HTTPS 镜像地址。
pub fn rewrite_public_cover_url(api_base: &str, raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    if trimmed.starts_with('/') {
        return format!("{}{}", api_base.trim_end_matches('/'), trimmed);
    }
    let https = trimmed.replace("http://", "https://");
    https
        .replace("https://lain.bgm.tv", BGM_MIRROR_IMG_BASE)
        .replace("https://lain.bangumi.tv", BGM_MIRROR_IMG_BASE)
}

fn parse_bgm_calendar_array(raw: &Value, api_base: &str) -> Vec<BangumiCalendarDay> {
    let mut days = Vec::new();
    if let Some(arr) = raw.as_array() {
        for day in arr {
            let wd = day.get("weekday");
            let weekday_id = wd.and_then(|w| w.get("id")).and_then(|v| v.as_i64()).unwrap_or(0);
            let weekday_cn = wd
                .and_then(|w| w.get("cn"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let weekday_en = wd
                .and_then(|w| w.get("en"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let mut items = Vec::new();
            let mut seen_ids = std::collections::HashSet::new();
            if let Some(item_arr) = day.get("items").and_then(|v| v.as_array()) {
                for it in item_arr {
                    let id = it.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
                    let orig = it.get("name").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
                    let cn = it.get("name_cn").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
                    let title = if !cn.is_empty() { cn } else { orig.clone() };
                    if id <= 0 || title.is_empty() || !seen_ids.insert(id) {
                        continue;
                    }
                    let cover_raw = it
                        .get("images")
                        .and_then(|im| {
                            im.get("large")
                                .or_else(|| im.get("common"))
                                .or_else(|| im.get("medium"))
                                .and_then(|v| v.as_str())
                        })
                        .unwrap_or("");
                    let cover_url = rewrite_public_cover_url(api_base, cover_raw);
                    let air_date = it
                        .get("air_date")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let score = it
                        .get("rating")
                        .and_then(|r| r.get("score"))
                        .and_then(|v| v.as_f64())
                        .filter(|&s| s > 0.0);
                    let rating_count = it
                        .get("rating")
                        .and_then(|r| r.get("total"))
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0);
                    let rank = it.get("rank").and_then(|v| v.as_i64()).filter(|&r| r > 0);
                    items.push(BangumiCalendarItem {
                        id,
                        title,
                        original_title: orig,
                        cover_url,
                        air_date,
                        score,
                        rating_count,
                        rank,
                    });
                }
            }
            if weekday_id > 0 {
                days.push(BangumiCalendarDay {
                    weekday_id,
                    weekday_cn,
                    weekday_en,
                    items,
                });
            }
        }
    }
    days
}

/// 每日放送表：优先经追番喵服务器 `/api/catalog/public/bangumi` 获取（1 小时自动刷新 + 每日定时预热），
/// 服务器不可达时自动回退国内镜像 `api-bgm.xunlys.top` 与官方接口，全程免梯子。
pub async fn fetch_bangumi_calendar(studio_data_dir: &Path) -> Result<BangumiCalendarResponse> {
    let cfg = load_cloud_endpoint_config(studio_data_dir);

    if let Ok(env) = cloud_json_request_raw(
        studio_data_dir,
        Method::POST,
        "api/catalog/public/bangumi",
        Some(json!({ "method": "GET", "path": "/calendar" })),
        false,
        16,
    )
    .await
    {
        if let Some(data) = env.get("data") {
            let days = parse_bgm_calendar_array(data, &cfg.api_base);
            if !days.is_empty() {
                return Ok(BangumiCalendarResponse {
                    days,
                    source_label: "每日放送".into(),
                    updated_at: env
                        .get("updatedAt")
                        .and_then(|v| v.as_str())
                        .map(str::to_string),
                    stale: env.get("stale").and_then(|v| v.as_bool()).unwrap_or(false),
                });
            }
        }
    }

    // 兜底：国内自建镜像 -> 官方
    let client = Client::builder()
        .user_agent(STUDIO_UA)
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(14))
        .build()
        .map_err(|e| StudioError::InvalidInput(format!("初始化放送表请求失败：{e}")))?;

    for (endpoint, label) in [
        (format!("{BGM_MIRROR_API_BASE}/calendar"), "在线放送表"),
        ("https://api.bgm.tv/calendar".to_string(), "Bangumi"),
    ] {
        if let Ok(resp) = client
            .get(&endpoint)
            .header("Accept", "application/json")
            .send()
            .await
        {
            if resp.status().is_success() {
                if let Ok(raw) = resp.json::<Value>().await {
                    let days = parse_bgm_calendar_array(&raw, &cfg.api_base);
                    if !days.is_empty() {
                        return Ok(BangumiCalendarResponse {
                            days,
                            source_label: label.into(),
                            updated_at: None,
                            stale: false,
                        });
                    }
                }
            }
        }
    }

    Err(StudioError::InvalidInput(
        "暂时无法获取每日放送表，请检查网络或稍后重试".into(),
    ))
}

fn parse_bgm_subject_to_discovery(item: &Value, kind: &str, api_base: &str) -> Option<DiscoveryItem> {
    let id = item.get("id")?.as_i64().filter(|&v| v > 0)?;
    let orig = item.get("name").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
    let cn = item.get("name_cn").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
    let title = if !cn.is_empty() { cn } else { orig.clone() };
    if title.is_empty() {
        return None;
    }
    let cover_raw = item
        .get("images")
        .and_then(|im| {
            im.get("large")
                .or_else(|| im.get("common"))
                .or_else(|| im.get("medium"))
                .and_then(|v| v.as_str())
        })
        .or_else(|| item.get("image").and_then(|v| v.as_str()))
        .unwrap_or("");
    let score = item
        .get("rating")
        .and_then(|r| r.get("score"))
        .and_then(|v| v.as_f64())
        .or_else(|| item.get("score").and_then(|v| v.as_f64()))
        .filter(|&s| s > 0.0);
    let rating_count = item
        .get("rating")
        .and_then(|r| r.get("total"))
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    let rank = item
        .get("rating")
        .and_then(|r| r.get("rank"))
        .and_then(|v| v.as_i64())
        .or_else(|| item.get("rank").and_then(|v| v.as_i64()))
        .filter(|&r| r > 0);
    let total_episodes = item
        .get("eps")
        .and_then(|v| v.as_i64())
        .or_else(|| item.get("total_episodes").and_then(|v| v.as_i64()))
        .unwrap_or(0);
    let air_date = item
        .get("date")
        .or_else(|| item.get("air_date"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let summary = item
        .get("summary")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .chars()
        .take(180)
        .collect();
    let tags = item
        .get("meta_tags")
        .or_else(|| item.get("tags"))
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|t| {
                    t.as_str()
                        .or_else(|| t.get("name").and_then(|n| n.as_str()))
                        .map(|s| s.trim().to_string())
                })
                .filter(|s| !s.is_empty())
                .take(4)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    let media_format = item
        .get("platform")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));

    Some(DiscoveryItem {
        kind: kind.to_string(),
        external_source: "bangumi".into(),
        external_id: id.to_string(),
        title,
        original_title: orig,
        cover_url: rewrite_public_cover_url(api_base, cover_raw),
        air_date,
        score,
        rating_count,
        rank,
        total_episodes,
        summary,
        tags,
        studio_or_dev: String::new(),
        media_format,
    })
}

fn season_date_bounds(year: i32, month: i32) -> (String, String) {
    let m = match month {
        1 | 4 | 7 | 10 => month,
        _ => 1,
    };
    let start = format!("{year:04}-{m:02}-01");
    let end = if m == 10 {
        format!("{:04}-01-01", year + 1)
    } else {
        format!("{year:04}-{:02}-01", m + 3)
    };
    (start, end)
}

/// 桌面端统一「发现」数据入口：全部优先走追番喵服务器（与手机端同源，免梯子且自动更新缓存）。
pub async fn fetch_discovery_feed(
    studio_data_dir: &Path,
    req: DiscoveryFeedRequest,
) -> Result<DiscoveryPage> {
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let page = req.page.unwrap_or(1).max(1);
    let kind = match req.kind.as_str() {
        "book" => "book",
        "game" => "game",
        _ => "anime",
    };
    let query_str = req.query.as_deref().unwrap_or("").trim();

    // 1) 游戏发现与检索：直走服务端 /api/games/discover（含 Bangumi + VNDB 联合检索与每日自动刷新）
    if kind == "game" {
        let sort = match req.sort.as_deref() {
            Some("score") => "score",
            Some("date") => "date",
            _ => "heat",
        };
        let offset = (page - 1) * 20;
        let mut path = format!("api/games/discover?sort={sort}&offset={offset}");
        if !query_str.is_empty() {
            path.push_str(&format!(
                "&q={}&includeVndb=1",
                urlencoding_component(query_str)
            ));
        }
        let env = cloud_json_request_raw(studio_data_dir, Method::GET, &path, None, false, 18).await?;
        let arr = env.get("data").and_then(|v| v.as_array()).cloned().unwrap_or_default();
        let mut seen = std::collections::HashSet::new();
        let mut items = Vec::new();
        for row in &arr {
            let title = row
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if title.is_empty() {
                continue;
            }
            let bgm_id = row.get("id").and_then(|v| v.as_i64()).filter(|&v| v > 0);
            let vndb_id = row
                .get("vndbId")
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty());
            let (ext_src, ext_id) = if let Some(bid) = bgm_id {
                ("bangumi".to_string(), bid.to_string())
            } else if let Some(vid) = vndb_id {
                ("vndb".to_string(), vid.to_string())
            } else {
                continue;
            };
            if !seen.insert(format!("{ext_src}:{ext_id}")) {
                continue;
            }
            let cover_raw = row.get("coverUrl").and_then(|v| v.as_str()).unwrap_or("");
            let score = row
                .get("score")
                .and_then(|v| v.as_f64())
                .filter(|&s| s > 0.0);
            let studio_or_dev = row
                .get("developer")
                .or_else(|| row.get("publisher"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            let mut tags = Vec::new();
            if let Some(g) = row.get("genre").and_then(|v| v.as_str()) {
                for part in g.split('/').map(str::trim).filter(|s| !s.is_empty()).take(2) {
                    tags.push(part.to_string());
                }
            }
            if let Some(p) = row.get("platforms").and_then(|v| v.as_str()) {
                for part in p.split('/').map(str::trim).filter(|s| !s.is_empty()).take(2) {
                    tags.push(part.to_string());
                }
            }
            items.push(DiscoveryItem {
                kind: "game".into(),
                external_source: ext_src,
                external_id: ext_id,
                title,
                original_title: row
                    .get("originalTitle")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string(),
                cover_url: rewrite_public_cover_url(&cfg.api_base, cover_raw),
                air_date: row
                    .get("releaseDate")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                score,
                rating_count: 0,
                rank: None,
                total_episodes: 0,
                summary: row
                    .get("synopsis")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .chars()
                    .take(180)
                    .collect(),
                tags,
                studio_or_dev,
                media_format: None,
            });
        }
        let total = env
            .get("total")
            .and_then(|v| v.as_i64())
            .unwrap_or(items.len() as i64);
        let has_more = ((offset as usize) + items.len()) < (total as usize);
        return Ok(DiscoveryPage {
            items,
            page,
            has_more,
            total,
            source_label: if query_str.is_empty() {
                "游戏发现".into()
            } else {
                "Bangumi + VNDB 联合检索".into()
            },
            updated_at: env
                .get("dataUpdatedAt")
                .or_else(|| env.get("updatedAt"))
                .and_then(|v| v.as_str())
                .map(str::to_string),
            stale: env
                .get("dataStale")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
        });
    }

    // 2) 动画 · 新番总览：优先走服务端 /api/catalog/seasons（每日 00:00 自动刷新当季与下季），
    //    若服务端未收录该历史季度，则自动转由服务端 /api/catalog/public/bangumi 按季度日期区间实时拉取。
    if kind == "anime" && req.feed == "seasonal" {
        let now_year = chrono::Utc::now().format("%Y").to_string().parse::<i32>().unwrap_or(2026);
        let year = req.year.unwrap_or(now_year).clamp(1970, 2100);
        let month = match req.month.unwrap_or(10) {
            1 | 4 | 7 | 10 => req.month.unwrap_or(10),
            m if m <= 3 => 1,
            m if m <= 6 => 4,
            m if m <= 9 => 7,
            _ => 10,
        };
        let sort = match req.sort.as_deref() {
            Some("score") => "SCORE",
            Some("date") => "NEWEST",
            _ => "POPULARITY",
        };
        let mut path = format!(
            "api/catalog/seasons?year={year}&month={month}&page={page}&limit=24&sort={sort}"
        );
        if !query_str.is_empty() {
            path.push_str(&format!("&keyword={}", urlencoding_component(query_str)));
        }
        if let Ok(env) =
            cloud_json_request_raw(studio_data_dir, Method::GET, &path, None, false, 16).await
        {
            let arr = env.get("data").and_then(|v| v.as_array()).cloned().unwrap_or_default();
            let mut seen = std::collections::HashSet::new();
            let mut items = Vec::new();
            for row in &arr {
                let id = row
                    .get("api_id")
                    .or_else(|| row.get("id"))
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0);
                let orig = row
                    .get("name_original")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                let cn = row
                    .get("name_cn")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string();
                let title = if !cn.is_empty() { cn } else { orig.clone() };
                if id <= 0 || title.is_empty() || !seen.insert(id) {
                    continue;
                }
                let cover_raw = row
                    .get("cover_url")
                    .or_else(|| row.get("cover_bangumi"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let tags = row
                    .get("tags")
                    .and_then(|v| v.as_str())
                    .map(|s| {
                        s.split(',')
                            .map(str::trim)
                            .filter(|t| !t.is_empty())
                            .take(4)
                            .map(str::to_string)
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default();
                items.push(DiscoveryItem {
                    kind: "anime".into(),
                    external_source: "bangumi".into(),
                    external_id: id.to_string(),
                    title,
                    original_title: orig,
                    cover_url: rewrite_public_cover_url(&cfg.api_base, cover_raw),
                    air_date: row
                        .get("air_date")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    score: row
                        .get("score")
                        .and_then(|v| v.as_f64())
                        .filter(|&s| s > 0.0),
                    rating_count: row.get("rating_count").and_then(|v| v.as_i64()).unwrap_or(0),
                    rank: row
                        .get("source_rank")
                        .or_else(|| row.get("rank"))
                        .and_then(|v| v.as_i64())
                        .filter(|&r| r > 0),
                    total_episodes: row.get("total_episodes").and_then(|v| v.as_i64()).unwrap_or(0),
                    summary: row
                        .get("summary")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .trim()
                        .chars()
                        .take(180)
                        .collect(),
                    tags,
                    studio_or_dev: row
                        .get("studio")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .trim()
                        .to_string(),
                    media_format: row
                        .get("media_format")
                        .or_else(|| row.get("platform"))
                        .and_then(|v| v.as_str())
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty()),
                });
            }
            let total = env
                .get("pagination")
                .and_then(|p| p.get("total"))
                .and_then(|v| v.as_i64())
                .unwrap_or(items.len() as i64);
            let has_more = env
                .get("pagination")
                .and_then(|p| p.get("hasMore"))
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let snap = env.get("snapshot");
            return Ok(DiscoveryPage {
                items,
                page,
                has_more,
                total,
                source_label: format!("{year} 年 {month} 月新番"),
                updated_at: snap
                    .and_then(|s| s.get("updatedAt"))
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                stale: snap
                    .and_then(|s| s.get("stale"))
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
            });
        }

        // 历史季度未在服务端快照时，仍经由追番喵服务器 /api/catalog/public/bangumi 查询该季度日期范围
        let (start, end) = season_date_bounds(year, month);
        let bgm_sort = match req.sort.as_deref() {
            Some("score") => "score",
            Some("rank") => "rank",
            _ => "heat",
        };
        let offset = (page - 1) * 24;
        let bgm_body = json!({
            "keyword": query_str,
            "sort": bgm_sort,
            "filter": {
                "type": [2],
                "air_date": [format!(">={start}"), format!("<{end}")]
            }
        });
        let env = cloud_json_request_raw(
            studio_data_dir,
            Method::POST,
            "api/catalog/public/bangumi",
            Some(json!({
                "method": "POST",
                "path": format!("/v0/search/subjects?limit=24&offset={offset}"),
                "body": bgm_body
            })),
            false,
            18,
        )
        .await?;
        let data = env.get("data").cloned().unwrap_or(json!({}));
        let arr = data.get("data").and_then(|v| v.as_array()).cloned().unwrap_or_default();
        let mut seen = std::collections::HashSet::new();
        let items: Vec<DiscoveryItem> = arr
            .iter()
            .filter_map(|row| parse_bgm_subject_to_discovery(row, "anime", &cfg.api_base))
            .filter(|it| seen.insert(it.external_id.clone()))
            .collect();
        let total = data.get("total").and_then(|v| v.as_i64()).unwrap_or(items.len() as i64);
        let has_more = ((offset as i64) + (arr.len() as i64)) < total && !arr.is_empty();
        return Ok(DiscoveryPage {
            items,
            page,
            has_more,
            total,
            source_label: format!("{year} 年 {month} 月番剧"),
            updated_at: env
                .get("updatedAt")
                .and_then(|v| v.as_str())
                .map(str::to_string),
            stale: env.get("stale").and_then(|v| v.as_bool()).unwrap_or(false),
        });
    }

    // 3) 动画 / 书籍 热门推荐、排行榜、关键词搜索：经追番喵服务器 /api/catalog/public/bangumi
    let bgm_type = if kind == "book" { 1 } else { 2 };
    let offset = (page - 1) * 24;
    let tag_filter = req
        .tag
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let mut filter_obj = serde_json::Map::new();
    filter_obj.insert("type".into(), json!([bgm_type]));
    if let Some(tag) = tag_filter {
        filter_obj.insert("tag".into(), json!([tag]));
    }
    let bgm_body = if !query_str.is_empty() {
        json!({
            "keyword": query_str,
            "sort": "match",
            "filter": Value::Object(filter_obj)
        })
    } else if req.feed == "ranking" {
        if req.sort.as_deref() == Some("score") {
            filter_obj.insert("rating_count".into(), json!([">=100"]));
            json!({
                "keyword": "",
                "sort": "score",
                "filter": Value::Object(filter_obj)
            })
        } else {
            filter_obj.insert("rank".into(), json!([">=1"]));
            json!({
                "keyword": "",
                "sort": "rank",
                "filter": Value::Object(filter_obj)
            })
        }
    } else {
        json!({
            "keyword": "",
            "sort": "heat",
            "filter": Value::Object(filter_obj)
        })
    };

    let bgm_path = format!("/v0/search/subjects?limit=24&offset={offset}");
    let (data, updated_at, stale, source_label) = match cloud_json_request_raw(
        studio_data_dir,
        Method::POST,
        "api/catalog/public/bangumi",
        Some(json!({
            "method": "POST",
            "path": bgm_path,
            "body": bgm_body
        })),
        false,
        18,
    )
    .await
    {
        Ok(env) => (
            env.get("data").cloned().unwrap_or(json!({})),
            env.get("updatedAt")
                .and_then(|v| v.as_str())
                .map(str::to_string),
            env.get("stale").and_then(|v| v.as_bool()).unwrap_or(false),
            "在线资料库".to_string(),
        ),
        Err(server_err) => {
            // 服务端不可达时回退国内镜像，仍免梯子
            let client = build_secure_http_client(12)?;
            let mirror_url = format!("{BGM_MIRROR_API_BASE}{bgm_path}");
            let resp = client
                .post(&mirror_url)
                .header("Accept", "application/json")
                .json(&bgm_body)
                .send()
                .await
                .map_err(|_| server_err)?;
            if !resp.status().is_success() {
                return Err(StudioError::InvalidInput(format!(
                    "暂时无法拉取发现列表（HTTP {}）",
                    resp.status().as_u16()
                )));
            }
            let raw: Value = resp
                .json()
                .await
                .map_err(|_| StudioError::InvalidInput("解析发现列表失败".into()))?;
            (raw, None, false, "在线资料库".to_string())
        }
    };

    let arr = data
        .get("data")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let mut seen = std::collections::HashSet::new();
    let items: Vec<DiscoveryItem> = arr
        .iter()
        .filter_map(|row| parse_bgm_subject_to_discovery(row, kind, &cfg.api_base))
        .filter(|it| seen.insert(it.external_id.clone()))
        .collect();
    let total = data
        .get("total")
        .and_then(|v| v.as_i64())
        .unwrap_or(items.len() as i64);
    let has_more = ((offset as i64) + (arr.len() as i64)) < total && !arr.is_empty();

    Ok(DiscoveryPage {
        items,
        page,
        has_more,
        total,
        source_label,
        updated_at,
        stale,
    })
}

/// 经追番喵服务器优先（失败回退国内镜像）检索 Bangumi 条目，供 Ctrl+K 极速入库使用。
pub async fn search_bangumi_via_server_first(
    studio_data_dir: &Path,
    keyword: &str,
    kind: &str,
) -> Result<Vec<RemoteCatalogHit>> {
    let q = keyword.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let bgm_type = match kind {
        "book" => 1,
        "audio" => 3,
        "game" => 4,
        _ => 2,
    };
    let normalized_kind = match bgm_type {
        1 => "book",
        3 => "audio",
        4 => "game",
        _ => "anime",
    };

    // 动画 / 书籍 / 游戏走服务端 /api/catalog/public/bangumi，国内零梯子可用
    if bgm_type != 3 {
        if let Ok(env) = cloud_json_request_raw(
            studio_data_dir,
            Method::POST,
            "api/catalog/public/bangumi",
            Some(json!({
                "method": "POST",
                "path": "/v0/search/subjects?limit=16&offset=0",
                "body": {
                    "keyword": q,
                    "sort": "match",
                    "filter": { "type": [bgm_type] }
                }
            })),
            false,
            16,
        )
        .await
        {
            if let Some(arr) = env
                .get("data")
                .and_then(|d| d.get("data"))
                .and_then(|v| v.as_array())
            {
                let mut hits = Vec::new();
                for item in arr.iter().take(16) {
                    if item["type"].as_i64().is_some_and(|item_type| item_type != bgm_type) {
                        continue;
                    }
                    if let Some(d) = parse_bgm_subject_to_discovery(item, normalized_kind, &cfg.api_base) {
                        hits.push(RemoteCatalogHit {
                            kind: d.kind,
                            external_source: d.external_source,
                            external_id: d.external_id,
                            title: d.title,
                            original_title: d.original_title,
                            cover_url: d.cover_url,
                            release_date: d.air_date,
                            total_episodes: d.total_episodes,
                            public_score: d.score,
                            studio_or_circle: String::new(),
                            synopsis: d.summary,
                        });
                    }
                }
                return Ok(hits);
            }
        }
    }

    // 音声或服务器暂不可达时回退国内镜像 -> 官方，并重写封面为国内镜像
    let mut hits = crate::studio_features::search_bangumi_subjects(q, kind).await?;
    for h in &mut hits {
        h.cover_url = rewrite_public_cover_url(&cfg.api_base, &h.cover_url);
    }
    Ok(hits)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteSubjectDetail {
    pub external_source: String,
    pub external_id: String,
    pub title: Option<String>,
    pub original_title: Option<String>,
    pub cover_url: Option<String>,
    pub cover_thumb_url: Option<String>,
    pub release_date: Option<String>,
    pub total_episodes: Option<i64>,
    pub summary: Option<String>,
    pub studio: Option<String>,
    pub media_format: Option<String>,
    pub tags: Vec<String>,
    pub public_score: Option<f64>,
}

fn parse_bgm_subject_detail_value(raw: &Value, subject_id: &str, api_base: &str) -> RemoteSubjectDetail {
    let clean = |s: Option<&str>| -> Option<String> {
        s.map(str::trim)
            .filter(|v| !v.is_empty() && !v.eq_ignore_ascii_case("null"))
            .map(str::to_string)
    };
    let name = clean(raw.get("name").and_then(Value::as_str));
    let name_cn = clean(raw.get("name_cn").and_then(Value::as_str));
    let title = name_cn.or_else(|| name.clone());
    let images = raw.get("images");
    let raw_cover = clean(
        images
            .and_then(|im| {
                im.get("large")
                    .or_else(|| im.get("common"))
                    .or_else(|| im.get("medium"))
                    .and_then(Value::as_str)
            })
            .or_else(|| raw.get("image").and_then(Value::as_str)),
    );
    let raw_thumb = clean(images.and_then(|im| {
        im.get("medium")
            .or_else(|| im.get("grid"))
            .or_else(|| im.get("small"))
            .or_else(|| im.get("common"))
            .and_then(Value::as_str)
    }));
    let cover_url = raw_cover.map(|u| rewrite_public_cover_url(api_base, &u));
    let cover_thumb_url = raw_thumb.map(|u| rewrite_public_cover_url(api_base, &u));

    let release_date = clean(
        raw.get("date")
            .or_else(|| raw.get("air_date"))
            .and_then(Value::as_str),
    );
    let total_episodes = raw
        .get("total_episodes")
        .and_then(Value::as_i64)
        .filter(|&v| v > 0)
        .or_else(|| raw.get("eps").and_then(Value::as_i64).filter(|&v| v > 0))
        .or_else(|| raw.get("volumes").and_then(Value::as_i64).filter(|&v| v > 0));
    let summary = clean(raw.get("summary").and_then(Value::as_str));
    let media_format = clean(raw.get("platform").and_then(Value::as_str));
    let studio_raw = extract_bgm_infobox_value(
        raw,
        &["动画制作", "制作", "出版社", "连载杂志", "开发", "制作会社"],
    );
    let studio = (!studio_raw.trim().is_empty()).then_some(studio_raw);

    let mut tags: Vec<String> = Vec::new();
    let mut seen_tags = std::collections::HashSet::<String>::new();
    if let Some(meta_tags) = raw.get("meta_tags").and_then(Value::as_array) {
        for mt in meta_tags {
            if let Some(name) = clean(mt.as_str()) {
                if seen_tags.insert(name.to_ascii_lowercase()) {
                    tags.push(name);
                }
            }
        }
    }
    if let Some(tag_objs) = raw.get("tags").and_then(Value::as_array) {
        let mut ranked: Vec<(i64, String)> = tag_objs
            .iter()
            .filter_map(|t| {
                let name = clean(t.get("name").and_then(Value::as_str))?;
                let cnt = t.get("count").and_then(Value::as_i64).unwrap_or(0);
                // 过滤纯年份/季度类噪声标签，优先保留高票真实题材标签
                if name.len() == 4 && name.chars().all(|c| c.is_ascii_digit()) {
                    return None;
                }
                if name.ends_with("年") || name.ends_with("月") {
                    return None;
                }
                Some((cnt, name))
            })
            .collect();
        ranked.sort_by(|a, b| b.0.cmp(&a.0));
        for (_, name) in ranked.into_iter().take(10) {
            if seen_tags.insert(name.to_ascii_lowercase()) {
                tags.push(name);
            }
            if tags.len() >= 8 {
                break;
            }
        }
    }
    let public_score = raw
        .get("rating")
        .and_then(|r| r.get("score"))
        .and_then(Value::as_f64)
        .filter(|s| *s > 0.0);

    RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: subject_id.to_string(),
        title,
        original_title: name,
        cover_url,
        cover_thumb_url,
        release_date,
        total_episodes,
        summary,
        studio,
        media_format,
        tags,
        public_score,
    }
}

/// 经追番喵服务器优先（失败回退直连镜像/官方）拉取 Bangumi 条目完整元数据，供回填骨架记录与桌面端一键补全使用。
pub async fn fetch_bangumi_subject_detail_via_server_first(
    studio_data_dir: &Path,
    subject_id: &str,
) -> Result<RemoteSubjectDetail> {
    let sid = subject_id.trim();
    if sid.is_empty() || !sid.chars().all(|c| c.is_ascii_digit()) {
        return Err(StudioError::InvalidInput(format!("无效的 Bangumi 条目 ID：{sid}")));
    }
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let path = format!("/v0/subjects/{sid}");

    if let Ok(env) = cloud_json_request_raw(
        studio_data_dir,
        Method::POST,
        "api/catalog/public/bangumi",
        Some(json!({
            "method": "GET",
            "path": path,
        })),
        false,
        16,
    )
    .await
    {
        if let Some(data) = env.get("data").filter(|d| d.is_object() && d.get("id").is_some()) {
            return Ok(parse_bgm_subject_detail_value(data, sid, &cfg.api_base));
        }
    }

    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(16))
        .user_agent("AniMeowStudio/2.3 (https://www.xunlys.top)")
        .build()
        .map_err(|e| StudioError::InvalidInput(format!("初始化网络客户端失败：{e}")))?;
    let direct_url = format!("https://api.bgm.tv/v0/subjects/{sid}");
    let resp = client
        .get(&direct_url)
        .send()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("获取 Bangumi 条目 {sid} 详情失败：{e}")))?;
    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        return Err(StudioError::InvalidInput(format!(
            "Bangumi 条目 {sid} 返回 HTTP {}",
            status.as_u16()
        )));
    }
    let raw: Value = serde_json::from_str(&body)
        .map_err(|e| StudioError::InvalidInput(format!("解析 Bangumi 条目 {sid} 失败：{e}")))?;
    Ok(parse_bgm_subject_detail_value(&raw, sid, &cfg.api_base))
}

fn detect_image_extension(bytes: &[u8]) -> Option<&'static str> {
    if bytes.len() < 12 {
        return None;
    }
    if bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF {
        return Some("jpg");
    }
    if bytes.starts_with(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) {
        return Some("png");
    }
    if &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Some("webp");
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some("gif");
    }
    None
}

fn build_remote_cover_candidates(raw_url: &str) -> Vec<String> {
    let trimmed_raw = raw_url.trim();
    if trimmed_raw.is_empty() {
        return Vec::new();
    }
    let normalized = if trimmed_raw.starts_with("//") {
        format!("https:{trimmed_raw}")
    } else {
        trimmed_raw.to_string()
    };
    let trimmed = normalized.as_str();
    let mut urls = Vec::new();
    let lower = trimmed.to_ascii_lowercase();
    if let Some(rest) = lower
        .strip_prefix("https://lain.bgm.tv/")
        .or_else(|| lower.strip_prefix("http://lain.bgm.tv/"))
    {
        let orig_path = &trimmed[trimmed.len() - rest.len()..];
        urls.push(format!("https://img-bgm.xunlys.top/{orig_path}"));
        urls.push(format!("https://lain.bgm.tv/{orig_path}"));
    } else if lower.starts_with("http://") {
        urls.push(format!("https://{}", &trimmed[7..]));
        urls.push(trimmed.to_string());
    } else if lower.starts_with("https://") {
        urls.push(trimmed.to_string());
    }
    urls
}

/// D4/D6(7)：将远端封面下载并校验为真实图片后落盘至 `covers/native/`，返回规范 `animeow-cover://native/...` 标记，
/// 自动纳入 `LocalMediaServer` 本地服务与 `cloudIncludeCovers` 备份打包范围；下载或校验失败绝不落坏文件或坏链接。
pub async fn download_remote_cover_to_local(
    studio_data_dir: &Path,
    raw_url: &str,
    hint: &str,
) -> Result<String> {
    let candidates = build_remote_cover_candidates(raw_url);
    if candidates.is_empty() {
        return Err(StudioError::InvalidInput("无效的远端封面 URL".into()));
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .timeout(Duration::from_secs(16))
        .user_agent("AniMeowStudio/2.3 (https://www.xunlys.top)")
        .build()
        .map_err(|e| StudioError::InvalidInput(format!("初始化下载客户端失败：{e}")))?;

    let mut last_err = String::from("远端封面不可用");
    for cand in &candidates {
        match client.get(cand).send().await {
            Ok(resp) if resp.status().is_success() => {
                let bytes = match resp.bytes().await {
                    Ok(b) => b,
                    Err(e) => {
                        last_err = format!("读取封面字节失败：{e}");
                        continue;
                    }
                };
                if bytes.len() < 64 || bytes.len() > 12 * 1024 * 1024 {
                    last_err = format!("封面体积异常（{} 字节）", bytes.len());
                    continue;
                }
                let Some(ext) = detect_image_extension(&bytes) else {
                    last_err = "远端返回非有效图片格式（可能触发防盗链或 404 页面）".into();
                    continue;
                };
                let safe_hint: String = hint
                    .chars()
                    .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                    .take(28)
                    .collect();
                let mut hasher = sha2::Sha256::new();
                use sha2::Digest;
                hasher.update(&bytes);
                let digest = hex::encode(hasher.finalize());
                let rel = format!(
                    "native/rehydrate_{}_{}.{}",
                    if safe_hint.is_empty() { "cover" } else { &safe_hint },
                    &digest[..10],
                    ext
                );
                let covers_root = studio_data_dir.join("covers");
                let target_path = covers_root.join(&rel);
                if let Some(parent) = target_path.parent() {
                    std::fs::create_dir_all(parent)?;
                }
                let tmp_path = target_path.with_extension("tmp");
                std::fs::write(&tmp_path, &bytes)?;
                std::fs::rename(&tmp_path, &target_path)?;
                return Ok(format!("{}{rel}", crate::backup::COVER_MARKER));
            }
            Ok(resp) => {
                last_err = format!("HTTP {}", resp.status().as_u16());
            }
            Err(e) => {
                last_err = format!("{e}");
            }
        }
    }
    Err(StudioError::InvalidInput(format!("封面下载失败（{last_err}）")))
}

/// 探测远端封面 URL 可用性（镜像优先；支持 HEAD 并在返回 403/405 时回退 GET Range 0-63 验证 image/* 或图片魔数），
/// 成功返回经过验证的规范 HTTPS 远程 URL，失败返回明确原因。
pub async fn probe_remote_cover_url(raw_url: &str) -> std::result::Result<String, String> {
    let candidates = build_remote_cover_candidates(raw_url);
    if candidates.is_empty() {
        return Err("无效的远端封面 URL".into());
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(8))
        .user_agent("AniMeowStudio/2.3 (https://www.xunlys.top)")
        .build()
        .map_err(|e| format!("初始化探测客户端失败：{e}"))?;

    let mut last_err = String::from("远端封面不可达");
    for cand in &candidates {
        match client.head(cand).send().await {
            Ok(resp) if resp.status().is_success() => {
                let ct = resp
                    .headers()
                    .get(reqwest::header::CONTENT_TYPE)
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_ascii_lowercase();
                if ct.starts_with("image/") {
                    return Ok(cand.clone());
                }
                if ct.is_empty() || ct.contains("octet-stream") {
                    // 部分 CDN / 对象存储 HEAD 不回 image/* 或返回 octet-stream，用轻量 Range GET 校验魔数
                    if let Ok(r2) = client
                        .get(cand)
                        .header(reqwest::header::RANGE, "bytes=0-63")
                        .send()
                        .await
                    {
                        if r2.status().is_success() {
                            let ct2 = r2
                                .headers()
                                .get(reqwest::header::CONTENT_TYPE)
                                .and_then(|v| v.to_str().ok())
                                .unwrap_or("")
                                .to_ascii_lowercase();
                            if ct2.starts_with("image/") {
                                return Ok(cand.clone());
                            }
                            if let Ok(bytes) = r2.bytes().await {
                                if detect_image_extension(&bytes).is_some() {
                                    return Ok(cand.clone());
                                }
                            }
                        }
                    }
                    last_err = "远端响应缺少有效 image/* 内容类型".into();
                } else {
                    last_err = format!("content-type 非图片（{ct}）");
                }
            }
            Ok(resp) if resp.status().as_u16() == 405 || resp.status().as_u16() == 403 => {
                // 部分站点拒绝 HEAD 但允许 GET，轻量探测首字节
                if let Ok(r2) = client
                    .get(cand)
                    .header(reqwest::header::RANGE, "bytes=0-63")
                    .send()
                    .await
                {
                    if r2.status().is_success() {
                        let ct2 = r2
                            .headers()
                            .get(reqwest::header::CONTENT_TYPE)
                            .and_then(|v| v.to_str().ok())
                            .unwrap_or("")
                            .to_ascii_lowercase();
                        if ct2.starts_with("image/") {
                            return Ok(cand.clone());
                        }
                        if let Ok(bytes) = r2.bytes().await {
                            if detect_image_extension(&bytes).is_some() {
                                return Ok(cand.clone());
                            }
                        }
                        last_err = format!("content-type 非图片（{ct2}）");
                    } else {
                        last_err = format!("HTTP {}", r2.status().as_u16());
                    }
                } else {
                    last_err = format!("HTTP {}", resp.status().as_u16());
                }
            }
            Ok(resp) => {
                last_err = format!("HTTP {}", resp.status().as_u16());
            }
            Err(e) => {
                last_err = format!("请求失败（{e}）");
            }
        }
    }
    Err(last_err)
}

/// D5：探测远端封面 URL 可用性（镜像优先），用于 `get_work_detail` 缓存结果。
pub async fn probe_remote_cover_available(raw_url: &str) -> bool {
    probe_remote_cover_url(raw_url).await.is_ok()
}

#[derive(Debug, Clone)]
pub struct ResolvedCoverTarget {
    pub url: String,
    pub is_local: bool,
    pub reason: String,
}

/// 按 `coverStoragePolicy`（"remote" | "auto" | "local"）决定外部封面落库策略：
/// - "remote" / "auto"（默认）：先做可用性探测（带缓存），通过直接返回规范 HTTPS 远程 URL（不落盘、不占备份体积）；
///   探测失败则自动回落下载至 `covers/native/`（绝不写入坏链接）。
/// - "local"：始终下载落盘至 `covers/native/`。
pub async fn resolve_remote_cover_by_policy(
    studio_data_dir: &Path,
    local_db: Option<&std::sync::Mutex<rusqlite::Connection>>,
    raw_url: &str,
    hint: &str,
    policy: &str,
) -> std::result::Result<ResolvedCoverTarget, String> {
    let normalized_policy = policy.trim().to_ascii_lowercase();
    if normalized_policy == "local" {
        let local_uri = download_remote_cover_to_local(studio_data_dir, raw_url, hint)
            .await
            .map_err(|e| e.to_string())?;
        return Ok(ResolvedCoverTarget {
            url: local_uri,
            is_local: true,
            reason: "用户设置离线存储".into(),
        });
    }

    // remote / auto：优先使用探测通过的远程 HTTPS URL
    let candidates = build_remote_cover_candidates(raw_url);
    if let Some(db) = local_db {
        let cached_hit = db.lock().ok().and_then(|conn| {
            candidates.iter().find_map(|cand| {
                if let Some((true, _)) = crate::agent::storage::get_cached_cover_probe(&conn, cand) {
                    Some(cand.clone())
                } else {
                    None
                }
            })
        });
        if let Some(cand) = cached_hit {
            return Ok(ResolvedCoverTarget {
                url: cand,
                is_local: false,
                reason: "远程封面探测可用（缓存命中）".into(),
            });
        }
    }

    match probe_remote_cover_url(raw_url).await {
        Ok(verified_url) => {
            if let Some(db) = local_db {
                if let Ok(conn) = db.lock() {
                    let _ = crate::agent::storage::save_cached_cover_probe(&conn, &verified_url, true);
                    let _ = crate::agent::storage::save_cached_cover_probe(&conn, raw_url.trim(), true);
                }
            }
            Ok(ResolvedCoverTarget {
                url: verified_url,
                is_local: false,
                reason: "远程封面探测可用".into(),
            })
        }
        Err(probe_err) => {
            if let Some(db) = local_db {
                if let Ok(conn) = db.lock() {
                    let _ = crate::agent::storage::save_cached_cover_probe(&conn, raw_url.trim(), false);
                }
            }
            match download_remote_cover_to_local(studio_data_dir, raw_url, hint).await {
                Ok(local_uri) => Ok(ResolvedCoverTarget {
                    url: local_uri,
                    is_local: true,
                    reason: format!("远程不可用（{probe_err}），已回落落盘本地"),
                }),
                Err(dl_err) => Err(format!("远程探测失败（{probe_err}）且本地回落下载失败（{dl_err}）")),
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteCharacterHit {
    pub bgm_id: Option<i64>,
    pub anilist_id: Option<i64>,
    pub name: String,
    pub name_cn: String,
    pub image_url: String,
    pub summary: String,
    pub gender: String,
    pub birth_month: Option<i64>,
    pub birth_day: Option<i64>,
    pub blood_type: String,
    pub role_name: String,
    pub voice_actor: String,
    pub source: String,
}

fn extract_bgm_infobox_value(item: &Value, keys: &[&str]) -> String {
    let Some(arr) = item.get("infobox").and_then(Value::as_array) else {
        return String::new();
    };
    for entry in arr {
        let key = entry.get("key").and_then(Value::as_str).unwrap_or("").trim();
        if !keys.iter().any(|k| *k == key) {
            continue;
        }
        if let Some(s) = entry.get("value").and_then(Value::as_str) {
            let trimmed = s.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
        if let Some(list) = entry.get("value").and_then(Value::as_array) {
            for sub in list {
                if let Some(v) = sub.get("v").and_then(Value::as_str) {
                    let trimmed = v.trim();
                    if !trimmed.is_empty() {
                        return trimmed.to_string();
                    }
                }
            }
        }
    }
    String::new()
}

fn parse_remote_character_item(item: &Value, api_base: &str) -> Option<RemoteCharacterHit> {
    let name = item
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    let mut name_cn = item
        .get("name_cn")
        .or_else(|| item.get("nameCn"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    if name_cn.is_empty() {
        name_cn = extract_bgm_infobox_value(item, &["简体中文名", "中文名"]);
    }
    if name.is_empty() && name_cn.is_empty() {
        return None;
    }

    let source = item
        .get("source")
        .and_then(Value::as_str)
        .unwrap_or("bangumi")
        .trim()
        .to_lowercase();

    let raw_id = item.get("id").and_then(Value::as_i64).filter(|&v| v > 0);
    let source_id = item.get("source_id").and_then(Value::as_i64).filter(|&v| v > 0);
    let mut bgm_id = item
        .get("bgm_id")
        .or_else(|| item.get("bgmId"))
        .and_then(Value::as_i64)
        .filter(|&v| v > 0);
    let mut anilist_id = item
        .get("anilist_id")
        .or_else(|| item.get("anilistId"))
        .and_then(Value::as_i64)
        .filter(|&v| v > 0);
    if source == "anilist" {
        if anilist_id.is_none() {
            anilist_id = source_id.or(raw_id);
        }
    } else if bgm_id.is_none() {
        bgm_id = source_id.or(raw_id);
    }

    let raw_img = item
        .get("image_url")
        .or_else(|| item.get("imageUrl"))
        .and_then(Value::as_str)
        .or_else(|| {
            item.get("images").and_then(|imgs| {
                imgs.get("large")
                    .or_else(|| imgs.get("medium"))
                    .or_else(|| imgs.get("grid"))
                    .or_else(|| imgs.get("small"))
                    .and_then(Value::as_str)
            })
        })
        .unwrap_or("")
        .trim();
    let image_url = rewrite_public_cover_url(api_base, raw_img);

    let summary = item
        .get("summary")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    let mut gender = item
        .get("gender")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    if gender.is_empty() {
        gender = extract_bgm_infobox_value(item, &["性别"]);
    }
    let gender_norm = match gender.to_lowercase().as_str() {
        "female" => "女".to_string(),
        "male" => "男".to_string(),
        _ => gender,
    };

    let birth_month = item
        .get("birth_mon")
        .or_else(|| item.get("birth_month"))
        .or_else(|| item.get("birthMonth"))
        .and_then(Value::as_i64)
        .filter(|&m| (1..=12).contains(&m));
    let birth_day = item
        .get("birth_day")
        .or_else(|| item.get("birthDay"))
        .and_then(Value::as_i64)
        .filter(|&d| (1..=31).contains(&d));
    let mut blood_type = item
        .get("blood_type")
        .or_else(|| item.get("bloodType"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();
    if blood_type.is_empty() {
        if let Some(bt_num) = item.get("blood_type").and_then(Value::as_i64) {
            blood_type = match bt_num {
                1 => "A".to_string(),
                2 => "B".to_string(),
                3 => "AB".to_string(),
                4 => "O".to_string(),
                _ => String::new(),
            };
        } else {
            blood_type = extract_bgm_infobox_value(item, &["血型"]);
        }
    }

    let role_name = item
        .get("role_name")
        .or_else(|| item.get("relation"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_string();

    let voice_actor = item
        .get("voice_actor")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .or_else(|| {
            item.get("actors")
                .and_then(Value::as_array)
                .and_then(|arr| arr.first())
                .and_then(|a| {
                    a.get("name_cn")
                        .and_then(Value::as_str)
                        .filter(|s| !s.trim().is_empty())
                        .or_else(|| a.get("name").and_then(Value::as_str))
                })
                .map(|s| s.trim().to_string())
        })
        .unwrap_or_default();

    Some(RemoteCharacterHit {
        bgm_id,
        anilist_id,
        name: if name.is_empty() { name_cn.clone() } else { name },
        name_cn,
        image_url,
        summary,
        gender: gender_norm,
        birth_month,
        birth_day,
        blood_type,
        role_name,
        voice_actor,
        source,
    })
}

/// 联网搜索角色（优先走追番喵服务器缓存+代理接口，国内免梯子，失败回退 Bangumi 公共代理/官方）
pub async fn search_characters_via_server_first(
    studio_data_dir: &Path,
    keyword: &str,
) -> Result<Vec<RemoteCharacterHit>> {
    let q = keyword.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }
    let cfg = load_cloud_endpoint_config(studio_data_dir);

    // 1. 服务端 /api/character/search（服务器本地缓存 + Bangumi + AniList）
    let search_path = format!("api/character/search?q={}&limit=24", urlencoding_component(q));
    if let Ok(env) =
        cloud_json_request_raw(studio_data_dir, Method::GET, &search_path, None, false, 16).await
    {
        if let Some(arr) = env
            .get("data")
            .and_then(|d| d.get("characters"))
            .and_then(Value::as_array)
        {
            let hits: Vec<RemoteCharacterHit> = arr
                .iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect();
            if !hits.is_empty() {
                return Ok(hits);
            }
        }
    }

    // 2. 服务端 /api/catalog/public/bangumi 代理 Bangumi /v0/search/characters
    if let Ok(env) = cloud_json_request_raw(
        studio_data_dir,
        Method::POST,
        "api/catalog/public/bangumi",
        Some(json!({
            "method": "POST",
            "path": "/v0/search/characters?limit=20&offset=0",
            "body": { "keyword": q, "filter": {} }
        })),
        false,
        16,
    )
    .await
    {
        if let Some(arr) = env
            .get("data")
            .and_then(|d| d.get("data"))
            .and_then(Value::as_array)
        {
            let hits: Vec<RemoteCharacterHit> = arr
                .iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect();
            if !hits.is_empty() {
                return Ok(hits);
            }
        }
    }

    // 3. 直连 Bangumi 官方 API 兜底
    let client = build_secure_http_client(14)?;
    let resp = client
        .post("https://api.bgm.tv/v0/search/characters?limit=20&offset=0")
        .header("User-Agent", "AniMeowStudio/0.1 (Windows Desktop)")
        .json(&json!({ "keyword": q, "filter": {} }))
        .send()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("联网搜索角色失败：{e}")))?;
    if !resp.status().is_success() {
        return Err(StudioError::InvalidInput(format!(
            "角色数据源返回状态码 {}",
            resp.status().as_u16()
        )));
    }
    let body: Value = resp
        .json()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("角色数据解析失败：{e}")))?;
    let hits = body
        .get("data")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect()
        })
        .unwrap_or_default();
    Ok(hits)
}

/// 按 Bangumi 作品 ID 拉取该作品的全部出场角色（含头像、主角/配角定位与声优 CV）
pub async fn fetch_work_characters_via_server_first(
    studio_data_dir: &Path,
    subject_id: i64,
) -> Result<Vec<RemoteCharacterHit>> {
    if subject_id <= 0 {
        return Err(StudioError::InvalidInput("无效的 Bangumi 作品 ID".into()));
    }
    let cfg = load_cloud_endpoint_config(studio_data_dir);

    // 1. 优先走服务端 Bangumi 公共代理（可拿到完整的 actors 声优数组与 relation）
    if let Ok(env) = cloud_json_request_raw(
        studio_data_dir,
        Method::POST,
        "api/catalog/public/bangumi",
        Some(json!({
            "method": "GET",
            "path": format!("/v0/subjects/{subject_id}/characters")
        })),
        false,
        18,
    )
    .await
    {
        let arr_opt = env
            .get("data")
            .and_then(Value::as_array)
            .or_else(|| env.get("data").and_then(|d| d.get("data")).and_then(Value::as_array));
        if let Some(arr) = arr_opt {
            let hits: Vec<RemoteCharacterHit> = arr
                .iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect();
            if !hits.is_empty() {
                return Ok(hits);
            }
        }
    }

    // 2. 走服务端缓存接口 /api/catalog/bangumi/:id/characters
    let path = format!("api/catalog/bangumi/{subject_id}/characters");
    if let Ok(env) =
        cloud_json_request_raw(studio_data_dir, Method::GET, &path, None, false, 18).await
    {
        if let Some(arr) = env
            .get("data")
            .and_then(|d| d.get("characters"))
            .and_then(Value::as_array)
        {
            let hits: Vec<RemoteCharacterHit> = arr
                .iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect();
            if !hits.is_empty() {
                return Ok(hits);
            }
        }
    }

    // 3. 直连 Bangumi 官方兜底
    let client = build_secure_http_client(16)?;
    let resp = client
        .get(format!("https://api.bgm.tv/v0/subjects/{subject_id}/characters"))
        .header("User-Agent", "AniMeowStudio/0.1 (Windows Desktop)")
        .send()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("拉取作品角色失败：{e}")))?;
    if !resp.status().is_success() {
        return Err(StudioError::InvalidInput(format!(
            "作品角色接口返回状态码 {}",
            resp.status().as_u16()
        )));
    }
    let body: Value = resp
        .json()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("作品角色解析失败：{e}")))?;
    let hits = body
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|item| parse_remote_character_item(item, &cfg.api_base))
                .collect()
        })
        .unwrap_or_default();
    Ok(hits)
}

fn urlencoding_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char);
            }
            _ => {
                out.push_str(&format!("%{:02X}", b));
            }
        }
    }
    out
}
