use crate::repository::{parse_anime_filename, CuratorItem};
use crate::{Result, StudioError};
use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use chrono::Utc;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalCaptureRow {
    pub id: i64,
    pub work_kind: String,
    pub work_id: i64,
    pub work_title: String,
    pub image_rel_path: Option<String>,
    pub note: String,
    pub episode_hint: Option<i64>,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EpisodeNoteRef {
    pub episode: i64,
    pub title: String,
    pub review: String,
    pub rating: Option<i64>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BookBookmarkRef {
    pub id: i64,
    pub chapter_title: String,
    pub snippet: String,
    pub note: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkReferenceBundle {
    pub episode_notes: Vec<EpisodeNoteRef>,
    pub bookmarks: Vec<BookBookmarkRef>,
    pub captures: Vec<LocalCaptureRow>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TierListEntry {
    pub work_kind: String,
    pub work_id: i64,
    pub tier: String, // "S" | "A" | "B" | "C" | "D"
    pub sort_order: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExternalPlayerDetection {
    pub player_exe: String,
    pub window_title: String,
    pub parsed_title: String,
    pub parsed_episode: i64,
    pub matched_anime_id: i64,
    pub matched_anime_title: String,
    pub current_watched_episodes: i64,
    pub total_episodes: i64,
}

/// 保存 `Alt+Space` 截帧手账与灵感速记到 `desktop_local.db`（可选同时追加时间戳笔记至 `animes.memo`）
pub fn save_local_capture(
    studio_data_dir: &Path,
    sync_conn: &Connection,
    local_conn: &Connection,
    work_kind: &str,
    work_id: i64,
    work_title: &str,
    note: &str,
    episode_hint: Option<i64>,
    image_base64: Option<&str>,
    append_to_memo: bool,
) -> Result<LocalCaptureRow> {
    let trimmed_note = note.trim();
    let has_img = image_base64.map(|s| !s.trim().is_empty()).unwrap_or(false);
    if trimmed_note.is_empty() && !has_img {
        return Err(StudioError::InvalidInput(
            "请输入考据笔记或粘贴一张剪贴板截图".into(),
        ));
    }

    let mut image_rel_path: Option<String> = None;
    if let Some(raw_b64) = image_base64.map(str::trim).filter(|s| !s.is_empty()) {
        let (ext, payload) = if let Some((header, data)) = raw_b64.split_once(";base64,") {
            let e = if header.contains("jpeg") || header.contains("jpg") {
                "jpg"
            } else if header.contains("webp") {
                "webp"
            } else {
                "png"
            };
            (e, data)
        } else {
            ("png", raw_b64)
        };

        let bytes = BASE64_STD
            .decode(payload.trim())
            .map_err(|_| StudioError::InvalidInput("剪贴板截图 Base64 解码失败".into()))?;
        if bytes.len() > 16 * 1024 * 1024 {
            return Err(StudioError::InvalidInput("单张手账截图不能超过 16 MB".into()));
        }

        let captures_dir = studio_data_dir.join("captures");
        std::fs::create_dir_all(&captures_dir)?;
        let file_name = format!("cap-{}.{ext}", Uuid::new_v4());
        std::fs::write(captures_dir.join(&file_name), &bytes)?;
        image_rel_path = Some(file_name);
    }

    let now_iso = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    local_conn.execute(
        "INSERT INTO local_captures (work_kind, work_id, work_title, image_rel_path, note, episode_hint, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![
            work_kind,
            work_id,
            work_title.trim(),
            image_rel_path,
            trimmed_note,
            episode_hint,
            now_iso
        ],
    )?;
    let id = local_conn.last_insert_rowid();

    if append_to_memo && !trimmed_note.is_empty() && (work_kind == "anime" || work_kind == "book") {
        let existing_memo: Option<String> = sync_conn
            .query_row(
                "SELECT memo FROM animes WHERE id = ?1",
                params![work_id],
                |r| r.get(0),
            )
            .ok()
            .flatten();
        let stamp = &now_iso[..16.min(now_iso.len())].replace('T', " ");
        let ep_prefix = episode_hint
            .map(|ep| format!(" [EP{ep}]"))
            .unwrap_or_default();
        let line = format!("[{stamp}]{ep_prefix} {trimmed_note}");
        let merged = match existing_memo.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            Some(prev) => format!("{prev}\n{line}"),
            None => line,
        };
        let _ = sync_conn.execute(
            "UPDATE animes SET memo = ?1 WHERE id = ?2",
            params![merged, work_id],
        );
    }

    Ok(LocalCaptureRow {
        id,
        work_kind: work_kind.to_string(),
        work_id,
        work_title: work_title.trim().to_string(),
        image_rel_path,
        note: trimmed_note.to_string(),
        episode_hint,
        created_at: now_iso,
    })
}

pub fn list_local_captures(
    local_conn: &Connection,
    work_kind: Option<&str>,
    work_id: Option<i64>,
) -> Result<Vec<LocalCaptureRow>> {
    let mapper = |r: &rusqlite::Row<'_>| {
        Ok(LocalCaptureRow {
            id: r.get(0)?,
            work_kind: r.get(1)?,
            work_id: r.get(2)?,
            work_title: r.get(3)?,
            image_rel_path: r.get(4)?,
            note: r.get(5)?,
            episode_hint: r.get(6)?,
            created_at: r.get(7)?,
        })
    };

    if let (Some(k), Some(wid)) = (work_kind, work_id) {
        let mut stmt = local_conn.prepare(
            "SELECT id, work_kind, work_id, work_title, image_rel_path, note, episode_hint, created_at
             FROM local_captures
             WHERE work_kind = ?1 AND work_id = ?2
             ORDER BY id DESC LIMIT 100",
        )?;
        let rows = stmt
            .query_map(params![k, wid], mapper)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    } else {
        let mut stmt = local_conn.prepare(
            "SELECT id, work_kind, work_id, work_title, image_rel_path, note, episode_hint, created_at
             FROM local_captures
             ORDER BY id DESC LIMIT 100",
        )?;
        let rows = stmt
            .query_map([], mapper)?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(rows)
    }
}

pub fn delete_local_capture(
    studio_data_dir: &Path,
    local_conn: &Connection,
    capture_id: i64,
) -> Result<()> {
    let rel: Option<String> = local_conn
        .query_row(
            "SELECT image_rel_path FROM local_captures WHERE id = ?1",
            params![capture_id],
            |r| r.get(0),
        )
        .ok()
        .flatten();
    local_conn.execute("DELETE FROM local_captures WHERE id = ?1", params![capture_id])?;
    if let Some(rel_name) = rel {
        if let Ok(safe) = crate::backup::safe_child(&studio_data_dir.join("captures"), &rel_name) {
            let _ = std::fs::remove_file(safe);
        }
    }
    Ok(())
}

/// 聚合三栏漫评室左栏所需跨端考据素材：手机分集短评 (`episode_details`) + 手机阅读书签摘录 (`book_bookmarks`) + 本机截帧手账 (`local_captures`)
pub fn query_work_reference_bundle(
    sync_conn: &Connection,
    local_conn: &Connection,
    work_kind: &str,
    work_id: i64,
) -> Result<WorkReferenceBundle> {
    let mut episode_notes = Vec::new();
    let mut bookmarks = Vec::new();

    if work_kind == "anime" || work_kind == "book" {
        let mut ep_stmt = sync_conn.prepare(
            "SELECT episode, COALESCE(title, ''), COALESCE(review, ''), rating, COALESCE(updatedAt, '')
             FROM episode_details
             WHERE animeId = ?1 AND (review IS NOT NULL AND TRIM(review) != '' OR rating IS NOT NULL)
             ORDER BY episode ASC",
        )?;
        let ep_rows = ep_stmt.query_map(params![work_id], |r| {
            Ok(EpisodeNoteRef {
                episode: r.get(0)?,
                title: r.get(1)?,
                review: r.get(2)?,
                rating: r.get(3)?,
                updated_at: r.get(4)?,
            })
        })?;
        for row in ep_rows {
            episode_notes.push(row?);
        }

        let mut bm_stmt = sync_conn.prepare(
            "SELECT id, chapterTitle, snippet, COALESCE(note, ''), createdAt
             FROM book_bookmarks
             WHERE animeId = ?1
             ORDER BY chapterIndex ASC, id ASC",
        )?;
        let bm_rows = bm_stmt.query_map(params![work_id], |r| {
            Ok(BookBookmarkRef {
                id: r.get(0)?,
                chapter_title: r.get(1)?,
                snippet: r.get(2)?,
                note: r.get(3)?,
                created_at: r.get(4)?,
            })
        })?;
        for row in bm_rows {
            bookmarks.push(row?);
        }
    }

    let captures = list_local_captures(local_conn, Some(work_kind), Some(work_id))?;

    Ok(WorkReferenceBundle {
        episode_notes,
        bookmarks,
        captures,
    })
}

// ============================================================================
// 游戏启动器绑定持久化 (desktop_local.db -> game_launchers)
// ============================================================================

pub fn bind_game_launcher(local_conn: &Connection, work_id: i64, exe_path: &str) -> Result<()> {
    let trimmed = exe_path.trim();
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    if trimmed.is_empty() {
        local_conn.execute(
            "DELETE FROM game_launchers WHERE work_id = ?1",
            params![work_id],
        )?;
    } else {
        local_conn.execute(
            "INSERT INTO game_launchers (work_id, exe_path, updated_at)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(work_id) DO UPDATE SET exe_path = excluded.exe_path, updated_at = excluded.updated_at",
            params![work_id, trimmed, now],
        )?;
    }
    Ok(())
}

pub fn list_game_launchers(local_conn: &Connection) -> Result<HashMap<i64, String>> {
    let mut stmt = local_conn.prepare("SELECT work_id, exe_path FROM game_launchers")?;
    let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
    let mut map = HashMap::new();
    for r in rows {
        let (k, v) = r?;
        map.insert(k, v);
    }
    Ok(map)
}

// ============================================================================
// 数据透视年鉴：个人殿堂 Tier List 持久化 (desktop_local.db -> tier_list_entries)
// ============================================================================

pub fn list_tier_entries(local_conn: &Connection) -> Result<Vec<TierListEntry>> {
    let mut stmt = local_conn.prepare(
        "SELECT work_kind, work_id, tier, sort_order
         FROM tier_list_entries
         ORDER BY sort_order ASC, updated_at ASC",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(TierListEntry {
                work_kind: r.get(0)?,
                work_id: r.get(1)?,
                tier: r.get(2)?,
                sort_order: r.get(3)?,
            })
        })?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(rows)
}

pub fn save_tier_entry(
    local_conn: &Connection,
    work_kind: &str,
    work_id: i64,
    tier: Option<&str>,
    sort_order: i64,
) -> Result<()> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    match tier.map(str::trim).filter(|s| !s.is_empty()) {
        Some(t) => {
            local_conn.execute(
                "INSERT INTO tier_list_entries (work_kind, work_id, tier, sort_order, updated_at)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(work_kind, work_id) DO UPDATE SET
                    tier = excluded.tier,
                    sort_order = excluded.sort_order,
                    updated_at = excluded.updated_at",
                params![work_kind, work_id, t, sort_order, now],
            )?;
        }
        None => {
            local_conn.execute(
                "DELETE FROM tier_list_entries WHERE work_kind = ?1 AND work_id = ?2",
                params![work_kind, work_id],
            )?;
        }
    }
    Ok(())
}

// ============================================================================
// 软删除与 UTF-8 BOM CSV 表格导出
// ============================================================================

pub fn soft_delete_curator_item(sync_conn: &Connection, kind: &str, id: i64) -> Result<()> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    match kind {
        "anime" | "book" => {
            sync_conn.execute(
                "UPDATE animes SET deletedAt = ?1 WHERE id = ?2",
                params![now, id],
            )?;
        }
        "game" => {
            sync_conn.execute(
                "UPDATE games SET deletedAt = ?1, updatedAt = ?1 WHERE id = ?2",
                params![now, id],
            )?;
        }
        "audio" => {
            sync_conn.execute(
                "UPDATE audio_works SET deletedAt = ?1, updatedAt = ?1 WHERE id = ?2",
                params![now, id],
            )?;
        }
        other => {
            return Err(StudioError::InvalidInput(format!("未知的载体类型：{other}")));
        }
    }
    Ok(())
}

pub fn export_curator_csv(output_path: &Path, items: &[CuratorItem]) -> Result<usize> {
    let mut buf = String::with_capacity(items.len() * 128 + 256);
    // 写入 UTF-8 BOM 以保证 Windows Excel 双击直接识别中文无乱码
    buf.push('\u{feff}');
    buf.push_str("载体,编号,标题,原名,状态,当前进度,总集数/总长,个人分,外部均分,分差,制作方/社团,放送/发售日,所属企划,企划角色,标签,私密考据\r\n");

    for it in items {
        let fields = [
            it.kind.clone(),
            it.id.to_string(),
            it.title.clone(),
            it.original_title.clone(),
            it.status.clone(),
            it.progress_primary.to_string(),
            it.progress_total.to_string(),
            it.personal_score
                .map(|v| format!("{v:.1}"))
                .unwrap_or_default(),
            it.public_score
                .map(|v| format!("{v:.1}"))
                .unwrap_or_default(),
            it.score_delta
                .map(|v| format!("{v:+.1}"))
                .unwrap_or_default(),
            it.studio_or_circle.clone(),
            it.release_date.clone(),
            it.series_name.clone().unwrap_or_default(),
            it.series_role.clone().unwrap_or_default(),
            it.tags.join(" / "),
            it.memo.clone().unwrap_or_default(),
        ];
        let escaped: Vec<String> = fields.iter().map(|f| csv_escape(f)).collect();
        buf.push_str(&escaped.join(","));
        buf.push_str("\r\n");
    }

    if let Some(parent) = output_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(output_path, buf.as_bytes())?;
    Ok(items.len())
}

fn csv_escape(val: &str) -> String {
    if val.contains([',', '"', '\r', '\n']) {
        format!("\"{}\"", val.replace('"', "\"\""))
    } else {
        val.to_string()
    }
}

// ============================================================================
// M2 · 白名单外部本地播放器窗口标题识别与看番进度匹配（隐私优先：仅读取白名单播放器窗口标题）
// ============================================================================

const WHITELISTED_PLAYERS: &[&str] = &[
    "potplayermini64.exe",
    "potplayermini.exe",
    "potplayer.exe",
    "mpv.exe",
    "vlc.exe",
    "mpc-hc64.exe",
    "mpc-hc.exe",
    "mpc-be64.exe",
];

/// 从播放器窗口标题清洗尾部软件名并提取番剧标题与集号
pub fn parse_player_window_title(window_title: &str) -> Option<(String, i64)> {
    let mut body = window_title.trim();
    for suffix in [
        " - PotPlayer",
        " - mpv",
        " - VLC media player",
        " - Media Player Classic",
        " - MPC-HC",
        " - MPC-BE",
    ] {
        if let Some(stripped) = body.strip_suffix(suffix) {
            body = stripped.trim();
        }
    }
    if body.is_empty() {
        return None;
    }
    let (title_opt, ep_opt) = parse_anime_filename(body);
    let title = title_opt?.trim().to_string();
    let ep = ep_opt.filter(|&e| (1..=2000).contains(&e))?;
    if title.is_empty() {
        return None;
    }
    Some((title, ep))
}

/// 将外部播放器检测到的 `(player_exe, window_title)` 列表与本机 `animeow.db` 动画收藏进行匹配
pub fn match_player_windows_with_library(
    sync_conn: &Connection,
    player_windows: &[(String, String)],
) -> Result<Vec<ExternalPlayerDetection>> {
    if player_windows.is_empty() {
        return Ok(Vec::new());
    }

    let mut stmt = sync_conn.prepare(
        "SELECT id, title, COALESCE(originalTitle, ''), watchedEpisodes, totalEpisodes
         FROM animes
         WHERE deletedAt IS NULL AND subjectType = 'anime'",
    )?;
    let library: Vec<(i64, String, String, i64, i64)> = stmt
        .query_map([], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
        })?
        .collect::<std::result::Result<Vec<_>, _>>()?;

    let mut detections = Vec::new();
    for (exe, win_title) in player_windows {
        let Some((parsed_title, parsed_ep)) = parse_player_window_title(win_title) else {
            continue;
        };
        let p_low = parsed_title.to_lowercase();
        // 寻找标题或原名双向子串匹配度最高的本地收藏番剧
        let matched = library.iter().find(|(_, t, orig, _, _)| {
            let t_low = t.to_lowercase();
            let o_low = orig.to_lowercase();
            (!t_low.is_empty() && (p_low.contains(&t_low) || t_low.contains(&p_low)))
                || (!o_low.is_empty() && (p_low.contains(&o_low) || o_low.contains(&p_low)))
        });

        if let Some((aid, atitle, _, watched, total)) = matched {
            detections.push(ExternalPlayerDetection {
                player_exe: exe.clone(),
                window_title: win_title.clone(),
                parsed_title,
                parsed_episode: parsed_ep,
                matched_anime_id: *aid,
                matched_anime_title: atitle.clone(),
                current_watched_episodes: *watched,
                total_episodes: *total,
            });
        }
    }

    Ok(detections)
}

#[cfg(target_os = "windows")]
pub fn enumerate_whitelisted_player_windows() -> Vec<(String, String)> {
    use core::ffi::c_void;

    type Hwnd = isize;
    type Bool = i32;
    type EnumProc = unsafe extern "system" fn(Hwnd, isize) -> Bool;

    const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;

    #[link(name = "User32")]
    extern "system" {
        fn EnumWindows(lp_enum_func: EnumProc, l_param: isize) -> Bool;
        fn IsWindowVisible(h_wnd: Hwnd) -> Bool;
        fn GetWindowThreadProcessId(h_wnd: Hwnd, lpdw_process_id: *mut u32) -> u32;
        fn GetWindowTextLengthW(h_wnd: Hwnd) -> i32;
        fn GetWindowTextW(h_wnd: Hwnd, lp_string: *mut u16, n_max_count: i32) -> i32;
    }

    #[link(name = "Kernel32")]
    extern "system" {
        fn OpenProcess(
            dw_desired_access: u32,
            b_inherit_handle: Bool,
            dw_process_id: u32,
        ) -> *mut c_void;
        fn QueryFullProcessImageNameW(
            h_process: *mut c_void,
            dw_flags: u32,
            lp_exe_name: *mut u16,
            lpdw_size: *mut u32,
        ) -> Bool;
        fn CloseHandle(h_object: *mut c_void) -> Bool;
    }

    unsafe extern "system" fn enum_cb(hwnd: Hwnd, lparam: isize) -> Bool {
        if IsWindowVisible(hwnd) == 0 {
            return 1;
        }
        let mut pid: u32 = 0;
        GetWindowThreadProcessId(hwnd, &mut pid);
        if pid == 0 {
            return 1;
        }
        let h_proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h_proc.is_null() {
            return 1;
        }
        let mut path_buf = [0u16; 520];
        let mut path_len = path_buf.len() as u32;
        let ok = QueryFullProcessImageNameW(h_proc, 0, path_buf.as_mut_ptr(), &mut path_len);
        CloseHandle(h_proc);
        if ok == 0 || path_len == 0 {
            return 1;
        }
        let full_path = String::from_utf16_lossy(&path_buf[..path_len as usize]);
        let exe_name = Path::new(&full_path)
            .file_name()
            .map(|n| n.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default();

        // 严格隐私红线：非白名单本地播放器进程直接跳过，绝不调用 GetWindowTextW 读取其他应用窗口标题
        if !WHITELISTED_PLAYERS.contains(&exe_name.as_str()) {
            return 1;
        }

        let text_len = GetWindowTextLengthW(hwnd);
        if text_len <= 0 || text_len > 1024 {
            return 1;
        }
        let mut title_buf = vec![0u16; (text_len + 2) as usize];
        let read_len = GetWindowTextW(hwnd, title_buf.as_mut_ptr(), text_len + 1);
        if read_len > 0 {
            let title = String::from_utf16_lossy(&title_buf[..read_len as usize]);
            let out = &mut *(lparam as *mut Vec<(String, String)>);
            out.push((exe_name, title));
        }
        1
    }

    let mut found: Vec<(String, String)> = Vec::new();
    unsafe {
        let ptr = &mut found as *mut Vec<(String, String)> as isize;
        EnumWindows(enum_cb, ptr);
    }
    found
}

#[cfg(not(target_os = "windows"))]
pub fn enumerate_whitelisted_player_windows() -> Vec<(String, String)> {
    Vec::new()
}

pub fn scan_external_players(sync_conn: &Connection) -> Result<Vec<ExternalPlayerDetection>> {
    let windows = enumerate_whitelisted_player_windows();
    match_player_windows_with_library(sync_conn, &windows)
}
