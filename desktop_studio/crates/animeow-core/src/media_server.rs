use crate::{Result, StudioError};
use axum::body::Body;
use axum::extract::{Path as AxumPath, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::SeekFrom;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::{Arc, LazyLock, RwLock};
use tokio::fs::File;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio::net::TcpListener;
use tokio_util::io::ReaderStream;
use uuid::Uuid;

static SRT_TIME_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(\d{2}:\d{2}:\d{2}),(\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}),(\d{3})").unwrap()
});

#[derive(Clone)]
struct RegisteredMedia {
    file_path: PathBuf,
    mime_type: String,
    subtitle_path: Option<PathBuf>,
}

#[derive(Clone)]
struct RegisteredRemoteStream {
    url: String,
    mime_type: String,
    headers: HashMap<String, String>,
}

#[derive(Clone)]
struct MediaRegistry {
    entries: Arc<RwLock<HashMap<String, RegisteredMedia>>>,
    remote_streams: Arc<RwLock<HashMap<String, RegisteredRemoteStream>>>,
    covers_root: Arc<RwLock<Option<PathBuf>>>,
    captures_root: Arc<RwLock<Option<PathBuf>>>,
    http_client: reqwest::Client,
}

#[derive(Clone)]
pub struct LocalMediaServer {
    addr: SocketAddr,
    registry: MediaRegistry,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalPlaybackTicket {
    pub token: String,
    pub stream_url: String,
    pub subtitle_url: Option<String>,
    pub script_text: Option<String>,
    pub mime_type: String,
    pub file_name: String,
    pub file_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteStreamTicket {
    pub token: String,
    pub proxy_url: String,
    pub raw_url: String,
    pub mime_type: String,
    pub headers: HashMap<String, String>,
}

impl LocalMediaServer {
    /// 启动仅绑定本机回环 `127.0.0.1:0` 的轻量流媒体服务（支持 206 Range 秒拖、SRT->WebVTT 字幕转换、封面读取与在线 HLS/MP4 代理）
    pub async fn start(covers_root: Option<PathBuf>) -> Result<Self> {
        let captures_root = covers_root
            .as_ref()
            .and_then(|p| p.parent().map(|parent| parent.join("captures")));
        let http_client = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(12))
            .read_timeout(std::time::Duration::from_secs(30))
            .redirect(reqwest::redirect::Policy::limited(6))
            .build()
            .map_err(|e| StudioError::InvalidInput(format!("初始化代理客户端失败：{e}")))?;

        let registry = MediaRegistry {
            entries: Arc::new(RwLock::new(HashMap::new())),
            remote_streams: Arc::new(RwLock::new(HashMap::new())),
            covers_root: Arc::new(RwLock::new(covers_root)),
            captures_root: Arc::new(RwLock::new(captures_root)),
            http_client,
        };

        let app = Router::new()
            .route("/media/:token", get(handle_media_stream))
            .route("/subtitle/:token", get(handle_subtitle))
            .route(
                "/proxy/:token",
                get(handle_remote_proxy).options(handle_proxy_options),
            )
            .route("/cover/*rel", get(handle_cover))
            .route("/capture/*rel", get(handle_capture))
            .with_state(registry.clone());

        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let addr = listener.local_addr()?;

        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });

        Ok(Self { addr, registry })
    }

    pub fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.addr.port())
    }

    pub fn set_covers_root(&self, root: PathBuf) {
        if let Some(parent) = root.parent() {
            if let Ok(mut cap_guard) = self.registry.captures_root.write() {
                *cap_guard = Some(parent.join("captures"));
            }
        }
        if let Ok(mut guard) = self.registry.covers_root.write() {
            *guard = Some(root);
        }
    }

    /// 为指定的本机视频或音频文件签发一次性播放票据
    pub fn register_file(
        &self,
        file_path: &Path,
        subtitle_path: Option<&Path>,
        script_path: Option<&Path>,
    ) -> Result<LocalPlaybackTicket> {
        if !file_path.is_file() {
            return Err(StudioError::InvalidInput(format!(
                "媒体文件不存在或无法读取：{}",
                file_path.display()
            )));
        }
        let mime_type = guess_media_mime(file_path).to_string();
        let token = Uuid::new_v4().to_string();
        let sub_buf = subtitle_path.filter(|p| p.is_file()).map(Path::to_path_buf);
        let scr_buf = script_path.filter(|p| p.is_file()).map(Path::to_path_buf);

        let script_text = scr_buf
            .as_ref()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .map(|s| s.chars().take(50_000).collect::<String>());

        if let Ok(mut map) = self.registry.entries.write() {
            if map.len() >= 128 {
                map.clear();
            }
            map.insert(
                token.clone(),
                RegisteredMedia {
                    file_path: file_path.to_path_buf(),
                    mime_type: mime_type.clone(),
                    subtitle_path: sub_buf.clone(),
                },
            );
        }

        let base = self.base_url();
        let file_name = file_path
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();

        Ok(LocalPlaybackTicket {
            stream_url: format!("{base}/media/{token}"),
            subtitle_url: sub_buf.map(|_| format!("{base}/subtitle/{token}")),
            script_text,
            token,
            mime_type,
            file_name,
            file_path: file_path.to_string_lossy().into_owned(),
        })
    }

    /// 为在线播放流签发本机代理票据（自动附加 Referer/User-Agent 与 CORS，并重写 HLS 分片地址）
    pub fn register_remote_stream(
        &self,
        url: String,
        mime_type: String,
        headers: HashMap<String, String>,
    ) -> Result<RemoteStreamTicket> {
        if !crate::cloud::is_valid_playback_url(&url) {
            return Err(StudioError::InvalidInput("在线播放地址格式不合法".into()));
        }
        let token = Uuid::new_v4().to_string();
        if let Ok(mut map) = self.registry.remote_streams.write() {
            if map.len() >= 64 {
                map.clear();
            }
            map.insert(
                token.clone(),
                RegisteredRemoteStream {
                    url: url.clone(),
                    mime_type: mime_type.clone(),
                    headers: headers.clone(),
                },
            );
        }
        let base = self.base_url();
        Ok(RemoteStreamTicket {
            proxy_url: format!("{base}/proxy/{token}"),
            token,
            raw_url: url,
            mime_type,
            headers,
        })
    }
}

async fn handle_media_stream(
    AxumPath(token): AxumPath<String>,
    headers: HeaderMap,
    State(registry): State<MediaRegistry>,
) -> Response {
    let entry = registry
        .entries
        .read()
        .ok()
        .and_then(|m| m.get(&token).cloned());
    let Some(entry) = entry else {
        return (StatusCode::FORBIDDEN, "播放令牌无效或已过期").into_response();
    };

    let Ok(meta) = tokio::fs::metadata(&entry.file_path).await else {
        return (StatusCode::NOT_FOUND, "本地媒体文件未找到").into_response();
    };
    let total_size = meta.len();
    if total_size == 0 {
        return (StatusCode::OK, Body::empty()).into_response();
    }

    let range_header = headers.get(header::RANGE).and_then(|v| v.to_str().ok());
    let (start, end, is_partial) = match range_header {
        Some(raw) => match parse_byte_range(raw, total_size) {
            Some((s, e)) => (s, e, true),
            None => {
                return Response::builder()
                    .status(StatusCode::RANGE_NOT_SATISFIABLE)
                    .header(header::CONTENT_RANGE, format!("bytes */{total_size}"))
                    .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(Body::empty())
                    .unwrap();
            }
        },
        None => (0, total_size - 1, false),
    };

    let Ok(mut file) = File::open(&entry.file_path).await else {
        return (StatusCode::INTERNAL_SERVER_ERROR, "无法打开本地媒体文件").into_response();
    };
    if start > 0 && file.seek(SeekFrom::Start(start)).await.is_err() {
        return (StatusCode::INTERNAL_SERVER_ERROR, "媒体定位失败").into_response();
    }

    let content_len = end - start + 1;
    let limited = file.take(content_len);
    let stream = ReaderStream::with_capacity(limited, 64 * 1024);

    let mut builder = Response::builder()
        .status(if is_partial {
            StatusCode::PARTIAL_CONTENT
        } else {
            StatusCode::OK
        })
        .header(header::CONTENT_TYPE, entry.mime_type)
        .header(header::CONTENT_LENGTH, content_len.to_string())
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "no-store")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    if is_partial {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{total_size}"),
        );
    }

    builder.body(Body::from_stream(stream)).unwrap()
}

async fn handle_subtitle(
    AxumPath(token): AxumPath<String>,
    State(registry): State<MediaRegistry>,
) -> Response {
    let sub_path = registry
        .entries
        .read()
        .ok()
        .and_then(|m| m.get(&token).and_then(|e| e.subtitle_path.clone()));
    let Some(sub_path) = sub_path else {
        return (StatusCode::NOT_FOUND, "未关联外挂字幕").into_response();
    };
    let Ok(bytes) = tokio::fs::read(&sub_path).await else {
        return (StatusCode::NOT_FOUND, "字幕文件读取失败").into_response();
    };
    let raw_text = String::from_utf8_lossy(&bytes);
    let ext = sub_path
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();

    let vtt = if ext == "vtt" {
        raw_text.into_owned()
    } else {
        srt_to_webvtt(&raw_text)
    };

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/vtt; charset=utf-8")
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .body(Body::from(vtt))
        .unwrap()
}

async fn handle_cover(
    AxumPath(rel): AxumPath<String>,
    State(registry): State<MediaRegistry>,
) -> Response {
    let root = registry.covers_root.read().ok().and_then(|g| g.clone());
    serve_safe_image(root, &rel, "封面").await
}

async fn handle_capture(
    AxumPath(rel): AxumPath<String>,
    State(registry): State<MediaRegistry>,
) -> Response {
    let root = registry.captures_root.read().ok().and_then(|g| g.clone());
    serve_safe_image(root, &rel, "截帧手账").await
}

async fn serve_safe_image(root: Option<PathBuf>, rel: &str, label: &str) -> Response {
    let Some(root) = root else {
        return (StatusCode::NOT_FOUND, format!("{label}根目录未就绪")).into_response();
    };
    let clean_rel = rel.trim_start_matches('/');
    let target = match crate::backup::safe_child(&root, clean_rel) {
        Ok(p) if p.is_file() => p,
        Ok(_) => {
            if let Some((_, existing)) = crate::backup::resolve_existing_cover_file(&root, clean_rel) {
                existing
            } else {
                return (StatusCode::NOT_FOUND, format!("{label}不存在")).into_response();
            }
        }
        Err(_) => {
            return (StatusCode::FORBIDDEN, format!("禁止越界访问{label}")).into_response();
        }
    };
    let Ok(bytes) = tokio::fs::read(&target).await else {
        return (StatusCode::NOT_FOUND, format!("{label}不存在")).into_response();
    };
    let mime = match target
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        _ => "image/jpeg",
    };
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, mime)
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .body(Body::from(bytes))
        .unwrap()
}

pub fn srt_to_webvtt(srt: &str) -> String {
    let normalized = srt.trim_start_matches('\u{feff}').replace("\r\n", "\n");
    let converted = SRT_TIME_RE.replace_all(&normalized, "$1.$2 --> $3.$4");
    format!("WEBVTT\n\n{converted}\n")
}

fn parse_byte_range(header_val: &str, total_size: u64) -> Option<(u64, u64)> {
    let spec = header_val.trim().strip_prefix("bytes=")?;
    let first_range = spec.split(',').next()?.trim();
    let (start_str, end_str) = first_range.split_once('-')?;
    if start_str.is_empty() {
        let suffix: u64 = end_str.parse().ok()?;
        if suffix == 0 {
            return None;
        }
        let start = total_size.saturating_sub(suffix);
        Some((start, total_size - 1))
    } else {
        let start: u64 = start_str.parse().ok()?;
        if start >= total_size {
            return None;
        }
        let end = if end_str.is_empty() {
            total_size - 1
        } else {
            let parsed_end: u64 = end_str.parse().ok()?;
            if parsed_end < start {
                return None;
            }
            parsed_end.min(total_size - 1)
        };
        Some((start, end))
    }
}

#[derive(Deserialize)]
struct ProxyQuery {
    u: Option<String>,
}

static M3U8_URI_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r#"URI="([^"]+)""#).unwrap());

async fn handle_proxy_options() -> Response {
    Response::builder()
        .status(StatusCode::NO_CONTENT)
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .header(header::ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, OPTIONS")
        .header(header::ACCESS_CONTROL_ALLOW_HEADERS, "Range, Content-Type")
        .header(
            header::ACCESS_CONTROL_EXPOSE_HEADERS,
            "Content-Length, Content-Range, Accept-Ranges",
        )
        .body(Body::empty())
        .unwrap()
}

async fn handle_remote_proxy(
    State(registry): State<MediaRegistry>,
    AxumPath(token): AxumPath<String>,
    Query(query): Query<ProxyQuery>,
    req_headers: HeaderMap,
) -> Response {
    let entry = {
        let Ok(map) = registry.remote_streams.read() else {
            return (StatusCode::INTERNAL_SERVER_ERROR, "代理注册表不可用").into_response();
        };
        map.get(&token).cloned()
    };
    let Some(entry) = entry else {
        return (StatusCode::FORBIDDEN, "无效的在线流媒体票据").into_response();
    };

    let target_url = match query.u {
        Some(ref raw) if !raw.trim().is_empty() => raw.trim().to_string(),
        _ => entry.url.clone(),
    };
    if !crate::cloud::is_valid_proxy_segment_url(&target_url) {
        return (StatusCode::BAD_REQUEST, "目标流地址不受支持").into_response();
    }

    let mut req = registry.http_client.get(&target_url);
    for (k, v) in &entry.headers {
        req = req.header(k.as_str(), v.as_str());
    }
    if !entry.headers.contains_key("User-Agent") {
        req = req.header(
            header::USER_AGENT,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        );
    }
    if let Some(range) = req_headers.get(header::RANGE).and_then(|v| v.to_str().ok()) {
        req = req.header(header::RANGE, range);
    }

    let upstream = match req.send().await {
        Ok(resp) => resp,
        Err(err) => {
            return (StatusCode::BAD_GATEWAY, format!("拉取在线视频流失败：{err}")).into_response();
        }
    };

    let final_url = upstream.url().clone();
    let status = StatusCode::from_u16(upstream.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let content_type = upstream
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or(&entry.mime_type)
        .to_string();
    let content_range = upstream
        .headers()
        .get(header::CONTENT_RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);
    let content_len = upstream
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);

    let lower_ct = content_type.to_ascii_lowercase();
    let lower_path = final_url.path().to_ascii_lowercase();
    let is_hls = lower_ct.contains("mpegurl")
        || lower_path.ends_with(".m3u8")
        || (query.u.is_none() && entry.mime_type.to_ascii_lowercase().contains("mpegurl"));

    if is_hls && status.is_success() {
        let Ok(body_text) = upstream.text().await else {
            return (StatusCode::BAD_GATEWAY, "读取 HLS 播放列表失败").into_response();
        };
        let rewritten = rewrite_hls_manifest(&body_text, &final_url, &token);
        return Response::builder()
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, "application/vnd.apple.mpegurl; charset=utf-8")
            .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .header(header::CACHE_CONTROL, "no-store")
            .body(Body::from(rewritten))
            .unwrap();
    }

    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .header(
            header::ACCESS_CONTROL_EXPOSE_HEADERS,
            "Content-Length, Content-Range, Accept-Ranges",
        )
        .header(header::ACCEPT_RANGES, "bytes");
    if let Some(cr) = content_range {
        builder = builder.header(header::CONTENT_RANGE, cr);
    }
    if let Some(cl) = content_len {
        builder = builder.header(header::CONTENT_LENGTH, cl);
    }
    builder
        .body(Body::from_stream(upstream.bytes_stream()))
        .unwrap()
}

fn rewrite_hls_manifest(manifest: &str, base_url: &reqwest::Url, token: &str) -> String {
    let mut out = String::with_capacity(manifest.len() + 256);
    for raw_line in manifest.lines() {
        let line = raw_line.trim_end_matches('\r');
        let trimmed = line.trim();
        if trimmed.is_empty() {
            out.push('\n');
            continue;
        }
        if trimmed.starts_with('#') {
            let replaced = M3U8_URI_RE.replace_all(line, |caps: &regex::Captures<'_>| {
                let raw_uri = &caps[1];
                if let Ok(abs) = base_url.join(raw_uri) {
                    format!("URI=\"/proxy/{token}?u={}\"", percent_encode_url(abs.as_str()))
                } else {
                    caps[0].to_string()
                }
            });
            out.push_str(&replaced);
            out.push('\n');
        } else if let Ok(abs) = base_url.join(trimmed) {
            out.push_str(&format!(
                "/proxy/{token}?u={}\n",
                percent_encode_url(abs.as_str())
            ));
        } else {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

fn percent_encode_url(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.as_bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char);
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

fn guess_media_mime(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "mkv" => "video/x-matroska",
        "avi" => "video/x-msvideo",
        "mp3" => "audio/mpeg",
        "flac" => "audio/flac",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "m4a" => "audio/mp4",
        _ => "application/octet-stream",
    }
}
