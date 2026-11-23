pub mod external_binding;

use crate::{Result, StudioError};
use chrono::Utc;
use regex::Regex;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;

static SUBGROUP_BRACKET_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\[[^\]]*\]|\([^\)]*\)").unwrap());
static EPISODE_RE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(?:-\s*|\bEP?|\b第)(\d{1,4})(?:\s*(?:话|集|END|\b|v\d))").unwrap()
});
static RJ_CODE_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?i)\b(RJ\d{6,8})\b").unwrap());

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CuratorItem {
    pub kind: String, // "anime" | "book" | "game" | "audio"
    pub id: i64,
    pub sync_key: String,
    pub title: String,
    pub original_title: String,
    pub cover_url: String,
    pub status: String,
    pub progress_primary: i64, // 已看集数 / 已读主进度 / 游玩分钟 / 已听秒数
    pub progress_total: i64,
    pub book_volumes: Option<i64>,
    pub book_chapters: Option<i64>,
    pub personal_score: Option<f64>, // 统一折算 10 分制便于跨载体比较
    pub raw_rating: Option<i64>,
    pub public_score: Option<f64>,
    pub score_delta: Option<f64>,
    pub studio_or_circle: String,
    pub release_date: String,
    pub media_format: Option<String>,
    pub last_modified_at: i64,
    pub series_name: Option<String>,
    pub series_role: Option<String>,
    pub tags: Vec<String>,
    pub memo: Option<String>,
    pub review: String,
    pub local_file_count: usize,
    #[serde(default)]
    pub external_source: Option<String>,
    #[serde(default)]
    pub external_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub secondary_bangumi_id: Option<String>,
    pub mobile_pass_through: MobilePassThroughInfo,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MobilePassThroughInfo {
    pub broadcast_schedule: Option<String>,
    pub merged_source_count: usize,
    pub reading_chapter_title: Option<String>,
    pub reading_permille: Option<i64>,
    pub legado_chapter_title: Option<String>,
    pub bookmark_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VaultRootRow {
    pub id: i64,
    pub path: String,
    pub media_kind: String,
    pub label: String,
    pub last_scanned_at: Option<String>,
    pub file_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VaultFileRow {
    pub id: i64,
    pub root_id: Option<i64>,
    pub file_path: String,
    pub file_name: String,
    pub extension: String,
    pub size_bytes: i64,
    pub parsed_title: Option<String>,
    pub parsed_episode: Option<i64>,
    pub parsed_rj_code: Option<String>,
    pub bound_kind: Option<String>,
    pub bound_work_id: Option<i64>,
    pub subtitle_path: Option<String>,
    pub script_path: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CellPatch {
    pub kind: String,
    pub id: i64,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub progress_primary: Option<i64>,
    #[serde(default)]
    pub progress_total: Option<i64>,
    #[serde(default)]
    pub book_volumes: Option<i64>,
    #[serde(default)]
    pub book_chapters: Option<i64>,
    #[serde(default)]
    pub raw_rating: Option<i64>,
    #[serde(default)]
    pub memo: Option<String>,
    #[serde(default)]
    pub review: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub original_title: Option<String>,
    #[serde(default)]
    pub cover_url: Option<String>,
    #[serde(default)]
    pub studio_or_circle: Option<String>,
    #[serde(default)]
    pub release_date: Option<String>,
    #[serde(default)]
    pub media_format: Option<String>,
    #[serde(default)]
    pub synopsis: Option<String>,
    #[serde(default)]
    pub series_name: Option<String>,
    #[serde(default)]
    pub series_role: Option<String>,
    #[serde(default)]
    pub tags: Option<Vec<String>>,
}

pub fn query_curator_items(sync_conn: &Connection, local_conn: &Connection) -> Result<Vec<CuratorItem>> {
    // 1. 统计本地挂载文件数
    let mut bound_counts: HashMap<(String, i64), usize> = HashMap::new();
    {
        let mut stmt = local_conn.prepare(
            "SELECT bound_kind, bound_work_id, COUNT(*) FROM vault_files
             WHERE bound_kind IS NOT NULL AND bound_work_id IS NOT NULL
             GROUP BY bound_kind, bound_work_id",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, i64>(2)? as usize,
            ))
        })?;
        for r in rows {
            let (k, id, cnt) = r?;
            bound_counts.insert((k, id), cnt);
        }
    }

    // 2. 预取动画/书籍标签
    let mut anime_tags_map: HashMap<i64, Vec<String>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT at.animeId, t.name FROM anime_tags at JOIN tags t ON t.id = at.tagId ORDER BY t.name",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
        for r in rows {
            let (aid, name) = r?;
            anime_tags_map.entry(aid).or_default().push(name);
        }
    }

    // 3. 预取电子书阅读状态与书签计数（静默保真展示）
    let mut reading_map: HashMap<i64, (Option<String>, Option<i64>, Option<String>)> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT animeId, chapterTitle, progressPermille, legadoChapterTitle FROM book_reading_states",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, Option<i64>>(2)?,
                r.get::<_, Option<String>>(3)?,
            ))
        })?;
        for r in rows {
            let (id, ct, pm, lct) = r?;
            reading_map.insert(id, (ct, pm, lct));
        }
    }
    let mut bookmark_counts: HashMap<i64, i64> = HashMap::new();
    {
        let mut stmt =
            sync_conn.prepare("SELECT animeId, COUNT(*) FROM book_bookmarks GROUP BY animeId")?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))?;
        for r in rows {
            let (id, cnt) = r?;
            bookmark_counts.insert(id, cnt);
        }
    }

    // 3b. 预取跨媒体系列归属（供游戏 / 音声在左侧系列栏与列表中完整展示）
    let mut media_series_map: HashMap<(String, String), String> = HashMap::new();
    {
        if let Ok(mut stmt) = sync_conn.prepare(
            "SELECT m.kind, m.workSyncId, ms.name
             FROM media_series_members m
             JOIN media_series ms ON ms.id = m.seriesId
             WHERE m.seriesId IS NOT NULL AND ms.deletedAt IS NULL",
        ) {
            if let Ok(rows) = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                ))
            }) {
                for r in rows.flatten() {
                    let (k, sync_id, name) = r;
                    let norm_k = k.trim().to_ascii_lowercase();
                    let norm_name = name.trim().to_string();
                    if !norm_name.is_empty() {
                        media_series_map.insert((norm_k, sync_id), norm_name);
                    }
                }
            }
        }
        if let Ok(mut stmt) = sync_conn.prepare(
            "SELECT name, COALESCE(linkedMediaJson, '[]') FROM series",
        ) {
            if let Ok(rows) = stmt.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
            }) {
                for r in rows.flatten() {
                    let (sname, linked_json) = r;
                    let norm_name = sname.trim().to_string();
                    if norm_name.is_empty() {
                        continue;
                    }
                    if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&linked_json) {
                        for entry in arr {
                            if let (Some(k), Some(wsid)) = (
                                entry.get("kind").and_then(Value::as_str),
                                entry.get("workSyncId").and_then(Value::as_str),
                            ) {
                                media_series_map
                                    .entry((k.trim().to_ascii_lowercase(), wsid.to_string()))
                                    .or_insert_with(|| norm_name.clone());
                            }
                        }
                    }
                }
            }
        }
    }

    fn parse_iso_millis(raw: Option<&str>) -> Option<i64> {
        let s = raw?.trim();
        if s.is_empty() {
            return None;
        }
        chrono::DateTime::parse_from_rfc3339(s)
            .ok()
            .map(|dt| dt.timestamp_millis())
    }

    let mut out = Vec::new();

    // 4. 读取 animes (含 anime & book)
    {
        let mut stmt = sync_conn.prepare(
            "SELECT a.id, a.title, COALESCE(a.originalTitle, ''), COALESCE(a.coverUrl, ''),
                    a.status, a.watchedEpisodes, a.totalEpisodes, a.rating,
                    COALESCE(a.studio, ''), COALESCE(a.airDate, ''),
                    s.name, a.seriesRole, a.memo, COALESCE(a.review, ''),
                    COALESCE(a.subjectType, 'anime'), a.bookReadVolumes, a.bookReadChapters,
                    a.sourceRatingJson, a.broadcastDay, a.broadcastTime, a.mergedSourcesJson,
                    a.mediaFormat, a.updatedAt, a.statusChangedAt, a.createdAt,
                    a.externalSource, a.externalId
             FROM animes a
             LEFT JOIN series s ON s.id = a.seriesId
             WHERE a.deletedAt IS NULL
             ORDER BY COALESCE(a.pinnedAt, 0) DESC, a.id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let id: i64 = r.get(0)?;
            let raw_subj: String = r.get(14)?;
            let kind = if matches!(raw_subj.to_ascii_lowercase().as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            let raw_rating: Option<i64> = r.get(7)?;
            let personal_score = raw_rating.map(|v| (v as f64) / 10.0);
            let source_rating_json: Option<String> = r.get(17)?;
            let public_score = source_rating_json
                .as_deref()
                .and_then(|s| serde_json::from_str::<Value>(s).ok())
                .and_then(|v| v.get("score").and_then(|sc| sc.as_f64()))
                .filter(|&sc| sc > 0.0);
            let score_delta = match (personal_score, public_score) {
                (Some(p), Some(pub_s)) => Some(((p - pub_s) * 10.0).round() / 10.0),
                _ => None,
            };

            let bday: Option<i64> = r.get(18)?;
            let btime: Option<String> = r.get(19)?;
            let schedule = bday.filter(|&d| (1..=7).contains(&d)).map(|d| {
                let weekday = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][(d - 1) as usize];
                match btime.as_deref().filter(|t| !t.trim().is_empty()) {
                    Some(t) => format!("{weekday} {t}"),
                    None => weekday.to_string(),
                }
            });
            let merged_src_cnt = r
                .get::<_, Option<String>>(20)?
                .as_deref()
                .and_then(|s| serde_json::from_str::<Value>(s).ok())
                .and_then(|v| v.as_array().map(Vec::len))
                .unwrap_or(0);
            let media_format = r
                .get::<_, Option<String>>(21)?
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));
            let updated_at: Option<i64> = r.get(22)?;
            let status_changed_at: Option<i64> = r.get(23)?;
            let created_at: Option<String> = r.get(24)?;
            let last_modified_at = [
                updated_at,
                status_changed_at,
                parse_iso_millis(created_at.as_deref()),
            ]
            .into_iter()
            .flatten()
            .max()
            .unwrap_or(0);
            let external_source = r
                .get::<_, Option<String>>(25)?
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));
            let external_id = r
                .get::<_, Option<String>>(26)?
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));

            Ok((
                id,
                kind,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, i64>(5)?,
                r.get::<_, i64>(6)?,
                raw_rating,
                personal_score,
                public_score,
                score_delta,
                r.get::<_, String>(8)?,
                r.get::<_, String>(9)?,
                r.get::<_, Option<String>>(10)?,
                r.get::<_, Option<String>>(11)?,
                r.get::<_, Option<String>>(12)?,
                r.get::<_, String>(13)?,
                r.get::<_, Option<i64>>(15)?,
                r.get::<_, Option<i64>>(16)?,
                schedule,
                merged_src_cnt,
                media_format,
                last_modified_at,
                external_source,
                external_id,
            ))
        })?;

        for row in rows {
            let (
                id,
                kind,
                title,
                original_title,
                cover_url,
                status,
                watched,
                total,
                raw_rating,
                personal_score,
                public_score,
                score_delta,
                studio,
                air_date,
                series_name,
                series_role,
                memo,
                review,
                book_volumes,
                book_chapters,
                broadcast_schedule,
                merged_source_count,
                media_format,
                last_modified_at,
                external_source,
                external_id,
            ) = row?;

            let (reading_chapter_title, reading_permille, legado_chapter_title) =
                reading_map.get(&id).cloned().unwrap_or((None, None, None));
            let bookmark_count = bookmark_counts.get(&id).copied().unwrap_or(0);
            let local_file_count = bound_counts.get(&(kind.clone(), id)).copied().unwrap_or(0);

            out.push(CuratorItem {
                sync_key: format!("{kind}:{id}"),
                kind,
                id,
                title,
                original_title,
                cover_url,
                status,
                progress_primary: watched,
                progress_total: total,
                book_volumes,
                book_chapters,
                personal_score,
                raw_rating,
                public_score,
                score_delta,
                studio_or_circle: studio,
                release_date: air_date,
                media_format,
                last_modified_at,
                series_name,
                series_role,
                tags: anime_tags_map.remove(&id).unwrap_or_default(),
                memo,
                review,
                local_file_count,
                external_source,
                external_id,
                secondary_bangumi_id: None,
                mobile_pass_through: MobilePassThroughInfo {
                    broadcast_schedule,
                    merged_source_count,
                    reading_chapter_title,
                    reading_permille,
                    legado_chapter_title,
                    bookmark_count,
                },
            });
        }
    }

    // 5. 读取 games
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, syncId, title, originalTitle, coverUrl, status, playMinutes,
                    rating, score, developer, releaseDate, tags, review, updatedAt,
                    bangumiId, vndbId
             FROM games WHERE deletedAt IS NULL ORDER BY updatedAt DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let id: i64 = r.get(0)?;
            let sync_id: String = r.get(1)?;
            let rating: i64 = r.get(7)?;
            let raw_rating = (rating > 0).then_some(rating);
            let personal_score = raw_rating.map(|v| if v > 10 { (v as f64) / 10.0 } else { v as f64 });
            let pub_sc: f64 = r.get(8)?;
            let public_score = (pub_sc > 0.0).then_some((pub_sc * 10.0).round() / 10.0);
            let score_delta = match (personal_score, public_score) {
                (Some(p), Some(ps)) => Some(((p - ps) * 10.0).round() / 10.0),
                _ => None,
            };
            let tags_raw: String = r.get(11)?;
            let tags: Vec<String> = tags_raw
                .split([',', '，', ';'])
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            let updated_str: String = r.get(13)?;
            let last_modified_at = parse_iso_millis(Some(&updated_str)).unwrap_or(0);
            let bangumi_id: Option<i64> = r.get(14)?;
            let vndb_id = r
                .get::<_, Option<String>>(15)?
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));
            let (external_source, external_id) = if let Some(vid) = vndb_id {
                (Some("vndb".to_string()), Some(vid))
            } else if let Some(bid) = bangumi_id.filter(|&b| b > 0) {
                (Some("bangumi".to_string()), Some(bid.to_string()))
            } else {
                (None, None)
            };
            let series_name = media_series_map
                .get(&("game".to_string(), sync_id.clone()))
                .cloned();
            Ok(CuratorItem {
                kind: "game".into(),
                id,
                sync_key: sync_id,
                title: r.get(2)?,
                original_title: r.get(3)?,
                cover_url: r.get(4)?,
                status: r.get(5)?,
                progress_primary: r.get(6)?,
                progress_total: 0,
                book_volumes: None,
                book_chapters: None,
                personal_score,
                raw_rating,
                public_score,
                score_delta,
                studio_or_circle: r.get(9)?,
                release_date: r.get(10)?,
                media_format: None,
                last_modified_at,
                series_name,
                series_role: None,
                tags,
                memo: None,
                review: r.get(12)?,
                local_file_count: bound_counts.get(&("game".into(), id)).copied().unwrap_or(0),
                secondary_bangumi_id: (external_source.as_deref() == Some("vndb"))
                    .then_some(bangumi_id).flatten().filter(|id| *id > 0).map(|id| id.to_string()),
                external_source,
                external_id,
                mobile_pass_through: MobilePassThroughInfo::default(),
            })
        })?;
        for r in rows {
            out.push(r?);
        }
    }

    // 6. 读取 audio_works
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, syncId, title, originalTitle, coverUrl, status, listenedSeconds,
                    durationSeconds, rating, circle, releaseDate, tags, review, workCode, updatedAt, sourceUrl
             FROM audio_works WHERE deletedAt IS NULL ORDER BY updatedAt DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let id: i64 = r.get(0)?;
            let sync_id: String = r.get(1)?;
            let rating: i64 = r.get(8)?;
            let raw_rating = (rating > 0).then_some(rating);
            let personal_score = raw_rating.map(|v| if v > 10 { (v as f64) / 10.0 } else { v as f64 });
            let tags_raw: String = r.get(11)?;
            let mut tags: Vec<String> = tags_raw
                .split([',', '，', ';'])
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            let code: String = r.get(13)?;
            let clean_code = code.trim().to_string();
            let (external_source, external_id) = if !clean_code.is_empty() {
                (Some("dlsite".to_string()), Some(clean_code.clone()))
            } else if let Some(id) = r.get::<_, String>(15)?.strip_prefix("https://bgm.tv/subject/")
                .filter(|id| !id.is_empty() && id.bytes().all(|b| b.is_ascii_digit()))
            {
                (Some("bangumi".into()), Some(id.into()))
            } else {
                (None, None)
            };
            if !clean_code.is_empty() && !tags.contains(&clean_code) {
                tags.insert(0, clean_code);
            }
            let updated_str: String = r.get(14)?;
            let last_modified_at = parse_iso_millis(Some(&updated_str)).unwrap_or(0);
            let series_name = media_series_map
                .get(&("audio".to_string(), sync_id.clone()))
                .cloned();
            Ok(CuratorItem {
                kind: "audio".into(),
                id,
                sync_key: sync_id,
                title: r.get(2)?,
                original_title: r.get(3)?,
                cover_url: r.get(4)?,
                status: r.get(5)?,
                progress_primary: r.get(6)?,
                progress_total: r.get(7)?,
                book_volumes: None,
                book_chapters: None,
                personal_score,
                raw_rating,
                public_score: None,
                score_delta: None,
                studio_or_circle: r.get(9)?,
                release_date: r.get(10)?,
                media_format: None,
                last_modified_at,
                series_name,
                series_role: None,
                tags,
                memo: None,
                review: r.get(12)?,
                local_file_count: bound_counts.get(&("audio".into(), id)).copied().unwrap_or(0),
                external_source,
                external_id,
                secondary_bangumi_id: None,
                mobile_pass_through: MobilePassThroughInfo::default(),
            })
        })?;
        for r in rows {
            out.push(r?);
        }
    }

    Ok(out)
}

pub(crate) fn normalize_tag_list(raw: &[String]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for item in raw {
        let t = item.trim().trim_start_matches('#').trim();
        if t.is_empty() {
            continue;
        }
        let cleaned: String = t.chars().take(48).collect();
        let key = cleaned.to_lowercase();
        if seen.insert(key) {
            out.push(cleaned);
        }
    }
    out
}

/// 策展工作表与详情编辑（支持修改标题、标签、总集数、制作方、日期、封面、系列、进度、评分、备注与感想，不动未知保真列）
/// 判断状态文案是否属于「已完成 / 看完」态，用于在切到完结状态时自动对齐总集数。
pub fn is_completed_status(status: &str) -> bool {
    matches!(
        status.trim(),
        "看完" | "已看" | "读完" | "已读" | "已玩" | "已听" | "通关" | "已完成" | "完结"
    )
}

pub(crate) const MAX_EPISODE_TOTAL: i64 = 100_000;

pub(crate) fn clamp_recorded_progress(progress: i64, total: i64) -> i64 {
    if total > 0 { progress.clamp(0, total) } else { progress.max(0) }
}

pub(crate) fn automatic_progress_status(kind: &str, current: &str, reached_end: bool) -> Option<&'static str> {
    match (kind, current, reached_end) {
        ("anime" | "book", "想看" | "未看" | "在看", true) => Some("看完"),
        ("anime" | "book", "想看" | "未看", false) => Some("在看"),
        ("game", "想玩" | "未玩", _) => Some("在玩"),
        ("audio", "想听" | "未听" | "在听", true) => Some("已听"),
        ("audio", "想听" | "未听", false) => Some("在听"),
        _ => None,
    }
}

pub fn update_curator_cell(sync_conn: &Connection, patch: &CellPatch) -> Result<()> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    match patch.kind.as_str() {
        "anime" | "book" => {
            if let Some(title) = &patch.title {
                let t = title.trim();
                if !t.is_empty() {
                    sync_conn.execute("UPDATE animes SET title = ?1 WHERE id = ?2", params![t, patch.id])?;
                }
            }
            if let Some(orig) = &patch.original_title {
                let t = orig.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET originalTitle = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(cover) = &patch.cover_url {
                let t = cover.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET coverUrl = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(studio) = &patch.studio_or_circle {
                let t = studio.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET studio = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(date) = &patch.release_date {
                let t = date.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET airDate = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(syn) = &patch.synopsis {
                let t = syn.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET synopsis = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(total) = patch.progress_total {
                sync_conn.execute(
                    "UPDATE animes SET totalEpisodes = ?1 WHERE id = ?2",
                    params![total.clamp(0, MAX_EPISODE_TOTAL), patch.id],
                )?;
            }
            if let Some(series_name) = &patch.series_name {
                let trimmed = series_name.trim();
                if trimmed.is_empty() {
                    sync_conn.execute("UPDATE animes SET seriesId = NULL WHERE id = ?1", params![patch.id])?;
                } else {
                    let existing: Option<i64> = sync_conn
                        .query_row("SELECT id FROM series WHERE name = ?1 LIMIT 1", params![trimmed], |r| r.get(0))
                        .ok();
                    let sid = match existing {
                        Some(id) => id,
                        None => {
                            sync_conn.execute(
                                "INSERT INTO series (name, createdAt) VALUES (?1, ?2)",
                                params![trimmed, now],
                            )?;
                            sync_conn.last_insert_rowid()
                        }
                    };
                    sync_conn.execute("UPDATE animes SET seriesId = ?1 WHERE id = ?2", params![sid, patch.id])?;
                }
            }
            if let Some(role) = &patch.series_role {
                let t = role.trim();
                let val = (!t.is_empty()).then_some(t);
                sync_conn.execute("UPDATE animes SET seriesRole = ?1 WHERE id = ?2", params![val, patch.id])?;
            }
            if let Some(raw_tags) = &patch.tags {
                let tags = normalize_tag_list(raw_tags);
                sync_conn.execute("DELETE FROM anime_tags WHERE animeId = ?1", params![patch.id])?;
                for tag_name in tags {
                    sync_conn.execute(
                        "INSERT OR IGNORE INTO tags (name, blocked) VALUES (?1, 0)",
                        params![tag_name],
                    )?;
                    if let Ok(tag_id) = sync_conn.query_row(
                        "SELECT id FROM tags WHERE name = ?1",
                        params![tag_name],
                        |r| r.get::<_, i64>(0),
                    ) {
                        sync_conn.execute(
                            "INSERT OR IGNORE INTO anime_tags (animeId, tagId) VALUES (?1, ?2)",
                            params![patch.id, tag_id],
                        )?;
                    }
                }
            }
            if let Some(st) = &patch.status {
                let status_changed_at = Utc::now().timestamp_millis();
                if is_completed_status(st) {
                    let today = Utc::now().format("%Y-%m-%d").to_string();
                    if patch.progress_primary.is_none() {
                        sync_conn.execute(
                            "UPDATE animes
                             SET status = ?1,
                                 statusChangedAt = ?2,
                                 watchedEpisodes = CASE WHEN COALESCE(totalEpisodes, 0) > 0 THEN totalEpisodes ELSE watchedEpisodes END,
                                 watchFinishDate = CASE WHEN COALESCE(watchFinishDate, '') = '' THEN ?3 ELSE watchFinishDate END
                             WHERE id = ?4",
                            params![st, status_changed_at, today, patch.id],
                        )?;
                    } else {
                        sync_conn.execute(
                            "UPDATE animes
                             SET status = ?1,
                                 statusChangedAt = ?2,
                                 watchFinishDate = CASE WHEN COALESCE(watchFinishDate, '') = '' THEN ?3 ELSE watchFinishDate END
                             WHERE id = ?4",
                            params![st, status_changed_at, today, patch.id],
                        )?;
                    }
                } else {
                    sync_conn.execute(
                        "UPDATE animes SET status = ?1, statusChangedAt = ?2 WHERE id = ?3",
                        params![st, status_changed_at, patch.id],
                    )?;
                }
            }
            let mut progress_advanced = false;
            let mut clamped_watched_and_total: Option<(i64, i64)> = None;
            if let Some(p) = patch.progress_primary {
                let total: i64 = sync_conn
                    .query_row(
                        "SELECT COALESCE(totalEpisodes, 0) FROM animes WHERE id = ?1",
                        params![patch.id],
                        |r| r.get(0),
                    )
                    .unwrap_or(0);
                let clamped = clamp_recorded_progress(p, total);
                sync_conn.execute(
                    "UPDATE animes SET watchedEpisodes = ?1 WHERE id = ?2",
                    params![clamped, patch.id],
                )?;
                if clamped > 0 {
                    progress_advanced = true;
                }
                clamped_watched_and_total = Some((clamped, total));
            }
            if let Some(vol) = patch.book_volumes {
                let v = (vol >= 0).then_some(vol.min(10_000));
                sync_conn.execute("UPDATE animes SET bookReadVolumes = ?1 WHERE id = ?2", params![v, patch.id])?;
                if v.unwrap_or(0) > 0 {
                    progress_advanced = true;
                }
            }
            if let Some(ch) = patch.book_chapters {
                let c = (ch >= 0).then_some(ch.min(10_000));
                sync_conn.execute("UPDATE animes SET bookReadChapters = ?1 WHERE id = ?2", params![c, patch.id])?;
                if c.unwrap_or(0) > 0 {
                    progress_advanced = true;
                }
            }
            if patch.status.is_none() && progress_advanced {
                let current_status: String = sync_conn
                    .query_row(
                        "SELECT COALESCE(status, '') FROM animes WHERE id = ?1",
                        params![patch.id],
                        |r| r.get(0),
                    )
                    .unwrap_or_default();
                let reached_end = clamped_watched_and_total
                    .map(|(watched, total)| total > 0 && watched >= total)
                    .unwrap_or(false);
                let next_status = automatic_progress_status(&patch.kind, current_status.trim(), reached_end);
                if let Some(ns) = next_status {
                    let today = Utc::now().format("%Y-%m-%d").to_string();
                    let status_changed_at = Utc::now().timestamp_millis();
                    sync_conn.execute(
                        "UPDATE animes
                         SET status = ?1,
                             statusChangedAt = ?2,
                             watchStartDate = CASE WHEN COALESCE(watchStartDate, '') = '' THEN ?3 ELSE watchStartDate END,
                             watchFinishDate = CASE WHEN ?1 = '看完' AND COALESCE(watchFinishDate, '') = '' THEN ?3 ELSE watchFinishDate END
                         WHERE id = ?4",
                        params![ns, status_changed_at, today, patch.id],
                    )?;
                }
            }
            if let Some(rt) = patch.raw_rating {
                let r = (1..=100).contains(&rt).then_some(rt);
                sync_conn.execute(
                    "UPDATE animes SET rating = ?1, ratingGrade = NULL WHERE id = ?2",
                    params![r, patch.id],
                )?;
            }
            if let Some(memo) = &patch.memo {
                let trimmed = memo.trim();
                let m = (!trimmed.is_empty()).then(|| trimmed.chars().take(10_000).collect::<String>());
                sync_conn.execute("UPDATE animes SET memo = ?1 WHERE id = ?2", params![m, patch.id])?;
            }
            if let Some(mf) = &patch.media_format {
                let trimmed = mf.trim();
                let v = (!trimmed.is_empty() && !trimmed.eq_ignore_ascii_case("null"))
                    .then(|| trimmed.chars().take(64).collect::<String>());
                sync_conn.execute("UPDATE animes SET mediaFormat = ?1 WHERE id = ?2", params![v, patch.id])?;
            }
            if let Some(rev) = &patch.review {
                sync_conn.execute("UPDATE animes SET review = ?1 WHERE id = ?2", params![rev, patch.id])?;
            }
            let updated_ms = Utc::now().timestamp_millis();
            sync_conn.execute(
                "UPDATE animes SET updatedAt = ?1 WHERE id = ?2",
                params![updated_ms, patch.id],
            )?;
        }
        "game" => {
            if let Some(title) = &patch.title {
                let t = title.trim();
                if !t.is_empty() {
                    sync_conn.execute("UPDATE games SET title = ?1, updatedAt = ?2 WHERE id = ?3", params![t, now, patch.id])?;
                }
            }
            if let Some(orig) = &patch.original_title {
                sync_conn.execute("UPDATE games SET originalTitle = ?1, updatedAt = ?2 WHERE id = ?3", params![orig.trim(), now, patch.id])?;
            }
            if let Some(cover) = &patch.cover_url {
                sync_conn.execute("UPDATE games SET coverUrl = ?1, updatedAt = ?2 WHERE id = ?3", params![cover.trim(), now, patch.id])?;
            }
            if let Some(dev) = &patch.studio_or_circle {
                sync_conn.execute("UPDATE games SET developer = ?1, updatedAt = ?2 WHERE id = ?3", params![dev.trim(), now, patch.id])?;
            }
            if let Some(date) = &patch.release_date {
                sync_conn.execute("UPDATE games SET releaseDate = ?1, updatedAt = ?2 WHERE id = ?3", params![date.trim(), now, patch.id])?;
            }
            if let Some(raw_tags) = &patch.tags {
                let joined = normalize_tag_list(raw_tags).join(", ");
                sync_conn.execute("UPDATE games SET tags = ?1, updatedAt = ?2 WHERE id = ?3", params![joined, now, patch.id])?;
            }
            if let Some(st) = &patch.status {
                sync_conn.execute(
                    "UPDATE games SET status = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![st, now, patch.id],
                )?;
            }
            if let Some(mins) = patch.progress_primary {
                let clamped_mins = mins.max(0);
                sync_conn.execute(
                    "UPDATE games SET playMinutes = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![clamped_mins, now, patch.id],
                )?;
                if patch.status.is_none() && clamped_mins > 0 {
                    let current = sync_conn.query_row("SELECT COALESCE(TRIM(status), '') FROM games WHERE id = ?1", [patch.id],
                        |row| row.get::<_, String>(0)).optional()?;
                    if let Some((current, status)) = current.and_then(|current| {
                        automatic_progress_status("game", &current, false).map(|status| (current, status))
                    }) {
                        sync_conn.execute(
                            "UPDATE games SET status = ?1, updatedAt = ?2 WHERE id = ?3 AND TRIM(status) = ?4",
                            params![status, now, patch.id, current],
                        )?;
                    }
                }
            }
            if let Some(rt) = patch.raw_rating {
                sync_conn.execute(
                    "UPDATE games SET rating = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![rt.clamp(0, 100), now, patch.id],
                )?;
            }
            if let Some(rev) = &patch.review {
                sync_conn.execute(
                    "UPDATE games SET review = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![rev, now, patch.id],
                )?;
            }
        }
        "audio" => {
            if let Some(title) = &patch.title {
                let t = title.trim();
                if !t.is_empty() {
                    sync_conn.execute("UPDATE audio_works SET title = ?1, updatedAt = ?2 WHERE id = ?3", params![t, now, patch.id])?;
                }
            }
            if let Some(orig) = &patch.original_title {
                sync_conn.execute("UPDATE audio_works SET originalTitle = ?1, updatedAt = ?2 WHERE id = ?3", params![orig.trim(), now, patch.id])?;
            }
            if let Some(cover) = &patch.cover_url {
                sync_conn.execute("UPDATE audio_works SET coverUrl = ?1, updatedAt = ?2 WHERE id = ?3", params![cover.trim(), now, patch.id])?;
            }
            if let Some(circle) = &patch.studio_or_circle {
                sync_conn.execute("UPDATE audio_works SET circle = ?1, updatedAt = ?2 WHERE id = ?3", params![circle.trim(), now, patch.id])?;
            }
            if let Some(date) = &patch.release_date {
                sync_conn.execute("UPDATE audio_works SET releaseDate = ?1, updatedAt = ?2 WHERE id = ?3", params![date.trim(), now, patch.id])?;
            }
            if let Some(total) = patch.progress_total {
                sync_conn.execute(
                    "UPDATE audio_works SET durationSeconds = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![total.max(0), now, patch.id],
                )?;
            }
            if let Some(raw_tags) = &patch.tags {
                let joined = normalize_tag_list(raw_tags).join(", ");
                sync_conn.execute("UPDATE audio_works SET tags = ?1, updatedAt = ?2 WHERE id = ?3", params![joined, now, patch.id])?;
            }
            if let Some(st) = &patch.status {
                sync_conn.execute(
                    "UPDATE audio_works SET status = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![st, now, patch.id],
                )?;
            }
            if let Some(secs) = patch.progress_primary {
                let (total_secs, current): (i64, String) = sync_conn
                    .query_row(
                        "SELECT COALESCE(durationSeconds, 0), COALESCE(TRIM(status), '') FROM audio_works WHERE id = ?1",
                        params![patch.id],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .optional()?
                    .unwrap_or_default();
                let clamped = clamp_recorded_progress(secs, total_secs);
                sync_conn.execute(
                    "UPDATE audio_works SET listenedSeconds = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![clamped, now, patch.id],
                )?;
                if patch.status.is_none() && clamped > 0 {
                    let reached_end = total_secs > 0 && clamped >= total_secs;
                    if let Some(status) = automatic_progress_status("audio", &current, reached_end) {
                        sync_conn.execute(
                            "UPDATE audio_works SET status = ?1, updatedAt = ?2 WHERE id = ?3 AND TRIM(status) = ?4",
                            params![status, now, patch.id, current],
                        )?;
                    }
                }
            }
            if let Some(rt) = patch.raw_rating {
                sync_conn.execute(
                    "UPDATE audio_works SET rating = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![rt.clamp(0, 100), now, patch.id],
                )?;
            }
            if let Some(rev) = &patch.review {
                sync_conn.execute(
                    "UPDATE audio_works SET review = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![rev, now, patch.id],
                )?;
            }
        }
        other => return Err(StudioError::InvalidInput(format!("不支持的藏品载体：{other}"))),
    }
    Ok(())
}

pub fn batch_update_status(
    sync_conn: &mut Connection,
    targets: &[(String, i64)],
    new_status: &str,
) -> Result<usize> {
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let today = Utc::now().format("%Y-%m-%d").to_string();
    let status_changed_at = Utc::now().timestamp_millis();
    let completed = is_completed_status(new_status);
    let tx = sync_conn.transaction()?;
    let mut updated = 0usize;
    for (kind, id) in targets {
        updated += match kind.as_str() {
            "anime" | "book" => {
                if completed {
                    tx.execute(
                        "UPDATE animes
                         SET status = ?1,
                             statusChangedAt = ?2,
                             watchedEpisodes = CASE WHEN COALESCE(totalEpisodes, 0) > 0 THEN totalEpisodes ELSE watchedEpisodes END,
                             watchFinishDate = CASE WHEN COALESCE(watchFinishDate, '') = '' THEN ?3 ELSE watchFinishDate END
                         WHERE id = ?4",
                        params![new_status, status_changed_at, today, id],
                    )?
                } else {
                    tx.execute(
                        "UPDATE animes SET status = ?1, statusChangedAt = ?2 WHERE id = ?3",
                        params![new_status, status_changed_at, id],
                    )?
                }
            }
            "game" => tx.execute(
                "UPDATE games SET status = ?1, updatedAt = ?2 WHERE id = ?3",
                params![new_status, now, id],
            )?,
            "audio" => tx.execute(
                "UPDATE audio_works SET status = ?1, updatedAt = ?2 WHERE id = ?3",
                params![new_status, now, id],
            )?,
            _ => 0,
        };
    }
    tx.commit()?;
    Ok(updated)
}

/// 挂载本地磁盘媒体目录并执行增量文件名/RJ号识别与藏品自动关联
pub fn mount_and_scan_vault(
    sync_conn: &Connection,
    local_conn: &mut Connection,
    dir_path: &Path,
    media_kind: &str,
) -> Result<VaultRootRow> {
    if !dir_path.is_dir() {
        return Err(StudioError::InvalidInput(format!(
            "所选路径不是有效文件夹：{}",
            dir_path.display()
        )));
    }
    let canon = dir_path.canonicalize().unwrap_or_else(|_| dir_path.to_path_buf());
    let path_str = strip_win_verbatim(&canon.to_string_lossy());
    let label = dir_path
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| path_str.clone());
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);

    local_conn.execute(
        "INSERT INTO vault_roots (path, media_kind, label, enabled, last_scanned_at)
         VALUES (?1, ?2, ?3, 1, ?4)
         ON CONFLICT(path) DO UPDATE SET media_kind = excluded.media_kind, last_scanned_at = excluded.last_scanned_at",
        params![path_str, media_kind, label, now],
    )?;
    let root_id: i64 = local_conn.query_row(
        "SELECT id FROM vault_roots WHERE path = ?1",
        params![path_str],
        |r| r.get(0),
    )?;

    // 预加载收藏库标题与 RJ 编号索引用于自动配对
    let mut anime_index: Vec<(i64, String)> = Vec::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, LOWER(title) FROM animes WHERE deletedAt IS NULL AND COALESCE(subjectType, 'anime') = 'anime'",
        )?;
        for r in stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))? {
            let (id, t): (i64, String) = r?;
            if !t.trim().is_empty() {
                anime_index.push((id, t));
            }
        }
    }
    let mut rj_index: HashMap<String, i64> = HashMap::new();
    {
        let mut stmt = sync_conn
            .prepare("SELECT id, UPPER(workCode) FROM audio_works WHERE deletedAt IS NULL AND workCode != ''")?;
        for r in stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))? {
            let (id, code) = r?;
            rj_index.insert(code, id);
        }
    }

    let mut media_files = Vec::new();
    collect_media_files(dir_path, 0, &mut media_files);

    let tx = local_conn.transaction()?;
    for file in &media_files {
        let ext = file
            .extension()
            .map(|e| e.to_string_lossy().to_ascii_lowercase())
            .unwrap_or_default();
        let file_name = file
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let full_str = strip_win_verbatim(&file.to_string_lossy());
        let size_bytes = std::fs::metadata(file).map(|m| m.len() as i64).unwrap_or(0);

        let (parsed_title, parsed_ep) = parse_anime_filename(&file_name);
        let parsed_rj = RJ_CODE_RE
            .captures(&full_str)
            .and_then(|c| c.get(1))
            .map(|m| m.as_str().to_ascii_uppercase());

        let subtitle_path = find_companion_file(file, &["vtt", "srt", "ass"]);
        let script_path = find_companion_file(file, &["lrc", "txt"]);

        // 自动匹配本地藏品
        let mut bound_kind: Option<String> = None;
        let mut bound_id: Option<i64> = None;
        if let Some(rj) = &parsed_rj {
            if let Some(&aid) = rj_index.get(rj) {
                bound_kind = Some("audio".into());
                bound_id = Some(aid);
            }
        }
        if bound_id.is_none() && matches!(ext.as_str(), "mp4" | "mkv" | "webm" | "avi") {
            let hay = parsed_title
                .as_deref()
                .unwrap_or(&file_name)
                .to_lowercase();
            if let Some((aid, _)) = anime_index.iter().find(|(_, title)| hay.contains(title.as_str()) || title.contains(hay.as_str())) {
                bound_kind = Some("anime".into());
                bound_id = Some(*aid);
            }
        }

        tx.execute(
            "INSERT INTO vault_files (
                root_id, file_path, file_name, extension, size_bytes,
                parsed_title, parsed_episode, parsed_rj_code,
                bound_kind, bound_work_id, subtitle_path, script_path, updated_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
             ON CONFLICT(file_path) DO UPDATE SET
                size_bytes = excluded.size_bytes,
                parsed_title = excluded.parsed_title,
                parsed_episode = excluded.parsed_episode,
                parsed_rj_code = excluded.parsed_rj_code,
                bound_kind = COALESCE(vault_files.bound_kind, excluded.bound_kind),
                bound_work_id = COALESCE(vault_files.bound_work_id, excluded.bound_work_id),
                subtitle_path = excluded.subtitle_path,
                script_path = excluded.script_path,
                updated_at = excluded.updated_at",
            params![
                root_id,
                full_str,
                file_name,
                ext,
                size_bytes,
                parsed_title,
                parsed_ep,
                parsed_rj,
                bound_kind,
                bound_id,
                subtitle_path,
                script_path,
                now
            ],
        )?;
    }

    let count: i64 = tx.query_row(
        "SELECT COUNT(*) FROM vault_files WHERE root_id = ?1",
        params![root_id],
        |r| r.get(0),
    )?;
    tx.execute(
        "UPDATE vault_roots SET file_count = ?1, last_scanned_at = ?2 WHERE id = ?3",
        params![count, now, root_id],
    )?;
    tx.commit()?;

    Ok(VaultRootRow {
        id: root_id,
        path: path_str,
        media_kind: media_kind.to_string(),
        label,
        last_scanned_at: Some(now),
        file_count: count,
    })
}

pub fn list_vault_roots(local_conn: &Connection) -> Result<Vec<VaultRootRow>> {
    let mut stmt = local_conn.prepare(
        "SELECT id, path, media_kind, label, last_scanned_at, file_count FROM vault_roots ORDER BY id DESC",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(VaultRootRow {
            id: r.get(0)?,
            path: r.get(1)?,
            media_kind: r.get(2)?,
            label: r.get(3)?,
            last_scanned_at: r.get(4)?,
            file_count: r.get(5)?,
        })
    })?;
    rows.collect::<std::result::Result<Vec<_>, _>>().map_err(Into::into)
}

pub fn list_vault_files(
    local_conn: &Connection,
    bound_kind: Option<&str>,
    bound_work_id: Option<i64>,
) -> Result<Vec<VaultFileRow>> {
    let sql = if bound_kind.is_some() && bound_work_id.is_some() {
        "SELECT id, root_id, file_path, file_name, extension, size_bytes, parsed_title, parsed_episode,
                parsed_rj_code, bound_kind, bound_work_id, subtitle_path, script_path
         FROM vault_files WHERE bound_kind = ?1 AND bound_work_id = ?2
         ORDER BY COALESCE(parsed_episode, 99999), file_name"
    } else {
        "SELECT id, root_id, file_path, file_name, extension, size_bytes, parsed_title, parsed_episode,
                parsed_rj_code, bound_kind, bound_work_id, subtitle_path, script_path
         FROM vault_files ORDER BY id DESC LIMIT 300"
    };

    let mut stmt = local_conn.prepare(sql)?;
    let mapper = |r: &rusqlite::Row<'_>| {
        Ok(VaultFileRow {
            id: r.get(0)?,
            root_id: r.get(1)?,
            file_path: r.get(2)?,
            file_name: r.get(3)?,
            extension: r.get(4)?,
            size_bytes: r.get(5)?,
            parsed_title: r.get(6)?,
            parsed_episode: r.get(7)?,
            parsed_rj_code: r.get(8)?,
            bound_kind: r.get(9)?,
            bound_work_id: r.get(10)?,
            subtitle_path: r.get(11)?,
            script_path: r.get(12)?,
        })
    };

    let rows = if let (Some(k), Some(id)) = (bound_kind, bound_work_id) {
        stmt.query_map(params![k, id], mapper)?
            .collect::<std::result::Result<Vec<_>, _>>()?
    } else {
        stmt.query_map([], mapper)?
            .collect::<std::result::Result<Vec<_>, _>>()?
    };
    Ok(rows)
}

pub fn bind_vault_file(
    local_conn: &Connection,
    vault_file_id: i64,
    kind: Option<&str>,
    work_id: Option<i64>,
) -> Result<()> {
    local_conn.execute(
        "UPDATE vault_files SET bound_kind = ?1, bound_work_id = ?2 WHERE id = ?3",
        params![kind, work_id, vault_file_id],
    )?;
    Ok(())
}

pub fn parse_anime_filename(file_name: &str) -> (Option<String>, Option<i64>) {
    let stem = Path::new(file_name)
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| file_name.to_string());
    let cleaned = SUBGROUP_BRACKET_RE.replace_all(&stem, " ");
    let episode = EPISODE_RE
        .captures(&cleaned)
        .and_then(|c| c.get(1))
        .and_then(|m| m.as_str().parse::<i64>().ok());

    let title_part = if let Some(m) = EPISODE_RE.find(&cleaned) {
        &cleaned[..m.start()]
    } else {
        cleaned.as_ref()
    };
    let normalized_title = title_part
        .replace(['_', '.'], " ")
        .trim_matches(|c: char| c.is_whitespace() || c == '-')
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    let title = (!normalized_title.is_empty()).then_some(normalized_title);
    (title, episode)
}

fn collect_media_files(dir: &Path, depth: usize, out: &mut Vec<PathBuf>) {
    if depth > 4 || out.len() >= 5_000 {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let Ok(ft) = entry.file_type() else { continue };
        if ft.is_symlink() {
            continue;
        }
        let p = entry.path();
        if ft.is_dir() {
            collect_media_files(&p, depth + 1, out);
        } else if ft.is_file() {
            let ext = p
                .extension()
                .map(|e| e.to_string_lossy().to_ascii_lowercase())
                .unwrap_or_default();
            if matches!(
                ext.as_str(),
                "mp4" | "mkv" | "webm" | "avi" | "mp3" | "flac" | "wav" | "ogg" | "m4a" | "exe"
            ) {
                out.push(p);
            }
        }
    }
}

fn find_companion_file(media_file: &Path, exts: &[&str]) -> Option<String> {
    let parent = media_file.parent()?;
    let stem = media_file.file_stem()?.to_string_lossy();
    for ext in exts {
        let direct = parent.join(format!("{stem}.{ext}"));
        if direct.is_file() {
            return Some(strip_win_verbatim(&direct.to_string_lossy()));
        }
        // 支持常见的 .sc.srt / .chs.srt 双语后缀
        for lang in ["sc", "chs", "zh", "zh-cn", "tc", "cht"] {
            let tagged = parent.join(format!("{stem}.{lang}.{ext}"));
            if tagged.is_file() {
                return Some(strip_win_verbatim(&tagged.to_string_lossy()));
            }
        }
    }
    None
}

pub fn strip_win_verbatim(p: &str) -> String {
    p.strip_prefix(r"\\?\").unwrap_or(p).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{init_local_db, init_sync_db};

    #[test]
    fn update_curator_cell_edits_tags_and_full_metadata() {
        let dir = tempfile::tempdir().unwrap();
        let sync_conn = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local_conn = init_local_db(&dir.path().join("desktop_local.db")).unwrap();

        sync_conn
            .execute(
                "INSERT INTO animes (title, status, watchedEpisodes, totalEpisodes) VALUES (?1, '想看', 0, 12)",
                params!["测试动画"],
            )
            .unwrap();
        let id = sync_conn.last_insert_rowid();

        let patch = CellPatch {
            kind: "anime".into(),
            id,
            status: Some("在看".into()),
            progress_primary: Some(5),
            progress_total: Some(24),
            book_volumes: None,
            book_chapters: None,
            raw_rating: Some(92),
            memo: Some("私密备注".into()),
            review: None,
            title: Some("新标题".into()),
            original_title: Some("Original Title".into()),
            cover_url: Some("https://img-bgm.xunlys.top/pic/cover/l/test.jpg".into()),
            studio_or_circle: Some("京阿尼".into()),
            release_date: Some("2026-04-01".into()),
            media_format: Some("小说".into()),
            synopsis: None,
            series_name: Some("测试企划".into()),
            series_role: Some("正传".into()),
            tags: Some(vec!["#治愈".into(), "日常".into(), "治愈".into()]),
        };
        update_curator_cell(&sync_conn, &patch).unwrap();

        let items = query_curator_items(&sync_conn, &local_conn).unwrap();
        assert_eq!(items.len(), 1);
        let it = &items[0];
        assert_eq!(it.title, "新标题");
        assert_eq!(it.original_title, "Original Title");
        assert_eq!(it.progress_primary, 5);
        assert_eq!(it.progress_total, 24);
        assert_eq!(it.studio_or_circle, "京阿尼");
        assert_eq!(it.release_date, "2026-04-01");
        assert_eq!(it.media_format.as_deref(), Some("小说"));
        assert_eq!(it.series_name.as_deref(), Some("测试企划"));
        assert_eq!(it.series_role.as_deref(), Some("正传"));
        assert_eq!(it.tags, vec!["日常".to_string(), "治愈".to_string()]);

        // Clear tags and series
        let clear_patch = CellPatch {
            kind: "anime".into(),
            id,
            status: None,
            progress_primary: None,
            progress_total: None,
            book_volumes: None,
            book_chapters: None,
            raw_rating: None,
            memo: None,
            review: None,
            title: None,
            original_title: None,
            cover_url: None,
            studio_or_circle: None,
            release_date: None,
            media_format: Some("".into()),
            synopsis: None,
            series_name: Some("".into()),
            series_role: None,
            tags: Some(vec![]),
        };
        update_curator_cell(&sync_conn, &clear_patch).unwrap();
        let items2 = query_curator_items(&sync_conn, &local_conn).unwrap();
        assert!(items2[0].tags.is_empty());
        assert_eq!(items2[0].series_name, None);
        assert_eq!(items2[0].media_format, None);
    }
}
