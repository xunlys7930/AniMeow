use crate::agent::knowledge::search_app_knowledge;
use crate::cloud::{
    fetch_bangumi_calendar, fetch_discovery_feed, fetch_work_characters_via_server_first,
    search_bangumi_via_server_first, search_characters_via_server_first,
    search_vndb_and_cloud_games, DiscoveryFeedRequest,
};
use crate::repository::{query_curator_items, CuratorItem};
use crate::studio_features::{
    list_characters_manage, list_series_manage, list_tags_manage, list_work_characters,
    query_duplicate_groups, query_series_suggestions, RemoteCatalogHit,
};
use crate::{Result, StudioError};
use chrono::{Datelike, Local};
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, Instant};

const BANGUMI_CACHE_TTL: Duration = Duration::from_secs(600);
pub const BANGUMI_BATCH_MAX_IDS: usize = 50;
const BANGUMI_BATCH_CONCURRENCY: usize = 4;

static BANGUMI_SEARCH_CACHE: LazyLock<
    Mutex<HashMap<(String, String), (Instant, Vec<RemoteCatalogHit>)>>,
> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn is_blank_or_null_field(raw: &str) -> bool {
    let t = raw.trim();
    t.is_empty() || t.eq_ignore_ascii_case("null") || t == "-" || t == "未知"
}

fn collect_works_with_external_id(sync_conn: &Connection) -> HashSet<(String, i64)> {
    let mut set = HashSet::new();
    if let Ok(mut stmt) = sync_conn.prepare(
        "SELECT id, COALESCE(subjectType, 'anime'), externalId
         FROM animes
         WHERE deletedAt IS NULL",
    ) {
        if let Ok(rows) = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, Option<String>>(2)?,
            ))
        }) {
            for row in rows.flatten() {
                let kind = if row.1 == "book" { "book" } else { "anime" };
                if let Some(ext) = row.2 {
                    if !is_blank_or_null_field(&ext) {
                        set.insert((kind.to_string(), row.0));
                    }
                }
            }
        }
    }
    if let Ok(mut stmt) = sync_conn.prepare(
        "SELECT id, bangumiId, vndbId
         FROM games
         WHERE deletedAt IS NULL",
    ) {
        if let Ok(rows) = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, Option<i64>>(1)?,
                r.get::<_, Option<String>>(2)?,
            ))
        }) {
            for row in rows.flatten() {
                let has_bgm = row.1.map(|id| id > 0).unwrap_or(false);
                let has_vndb = row
                    .2
                    .as_deref()
                    .map(|s| !is_blank_or_null_field(s))
                    .unwrap_or(false);
                if has_bgm || has_vndb {
                    set.insert(("game".to_string(), row.0));
                }
            }
        }
    }
    if let Ok(mut stmt) = sync_conn.prepare(
        "SELECT id, workCode, sourceUrl
         FROM audio_works
         WHERE deletedAt IS NULL",
    ) {
        if let Ok(rows) = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, Option<String>>(2)?,
            ))
        }) {
            for row in rows.flatten() {
                let has_code = row
                    .1
                    .as_deref()
                    .map(|s| !is_blank_or_null_field(s))
                    .unwrap_or(false);
                let has_url = row
                    .2
                    .as_deref()
                    .map(|s| !is_blank_or_null_field(s))
                    .unwrap_or(false);
                if has_code || has_url {
                    set.insert(("audio".to_string(), row.0));
                }
            }
        }
    }
    set
}

fn collect_works_with_source_id(sync_conn: &Connection, source: &str) -> HashSet<(String, i64)> {
    let mut set = HashSet::new();
    match source {
        "bangumi" => {
            if let Ok(mut stmt) = sync_conn.prepare(
                "SELECT id, COALESCE(subjectType, 'anime'), externalSource, externalId
                 FROM animes
                 WHERE deletedAt IS NULL",
            ) {
                if let Ok(rows) = stmt.query_map([], |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, Option<String>>(2)?,
                        r.get::<_, Option<String>>(3)?,
                    ))
                }) {
                    for row in rows.flatten() {
                        let kind = if matches!(
                            row.1.to_ascii_lowercase().as_str(),
                            "book" | "manga" | "novel"
                        ) {
                            "book"
                        } else {
                            "anime"
                        };
                        let is_bgm = row
                            .2
                            .as_deref()
                            .map(|s| s.trim().eq_ignore_ascii_case("bangumi"))
                            .unwrap_or(false);
                        let has_id = row
                            .3
                            .as_deref()
                            .map(|s| !is_blank_or_null_field(s))
                            .unwrap_or(false);
                        if is_bgm && has_id {
                            set.insert((kind.to_string(), row.0));
                        }
                    }
                }
            }
            if let Ok(mut stmt) =
                sync_conn.prepare("SELECT id, bangumiId FROM games WHERE deletedAt IS NULL")
            {
                if let Ok(rows) = stmt.query_map([], |r| {
                    Ok((r.get::<_, i64>(0)?, r.get::<_, Option<i64>>(1)?))
                }) {
                    for row in rows.flatten() {
                        if row.1.map(|id| id > 0).unwrap_or(false) {
                            set.insert(("game".to_string(), row.0));
                        }
                    }
                }
            }
        }
        "vndb" => {
            if let Ok(mut stmt) =
                sync_conn.prepare("SELECT id, vndbId FROM games WHERE deletedAt IS NULL")
            {
                if let Ok(rows) = stmt.query_map([], |r| {
                    Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?))
                }) {
                    for row in rows.flatten() {
                        if row
                            .1
                            .as_deref()
                            .map(|s| !is_blank_or_null_field(s))
                            .unwrap_or(false)
                        {
                            set.insert(("game".to_string(), row.0));
                        }
                    }
                }
            }
        }
        "dlsite" => {
            if let Ok(mut stmt) =
                sync_conn.prepare("SELECT id, workCode FROM audio_works WHERE deletedAt IS NULL")
            {
                if let Ok(rows) = stmt.query_map([], |r| {
                    Ok((r.get::<_, i64>(0)?, r.get::<_, Option<String>>(1)?))
                }) {
                    for row in rows.flatten() {
                        if row
                            .1
                            .as_deref()
                            .map(|s| !is_blank_or_null_field(s))
                            .unwrap_or(false)
                        {
                            set.insert(("audio".to_string(), row.0));
                        }
                    }
                }
            }
        }
        _ => {}
    }
    set
}

pub fn infer_progress_unit_from_parts(
    kind: &str,
    media_format: Option<&str>,
    tags: &[String],
    book_volumes: Option<i64>,
    book_chapters: Option<i64>,
) -> &'static str {
    match kind {
        "game" => "分钟",
        "audio" => "秒",
        "book" => {
            let mf = media_format.unwrap_or("").to_ascii_lowercase();
            let has_manga_tag = tags.iter().any(|t| {
                matches!(
                    t.trim().to_ascii_lowercase().as_str(),
                    "漫画" | "manga" | "条漫"
                )
            });
            let has_novel_tag = tags.iter().any(|t| {
                matches!(
                    t.trim().to_ascii_lowercase().as_str(),
                    "轻小说" | "小说" | "novel" | "web小说" | "小说系列"
                )
            });
            if book_volumes.is_some() && book_chapters.is_none() {
                "卷"
            } else if book_chapters.is_some()
                || mf.contains("漫画")
                || mf.contains("manga")
                || (has_manga_tag && !has_novel_tag)
            {
                "话"
            } else {
                "卷"
            }
        }
        _ => "集",
    }
}

pub fn infer_progress_unit(it: &CuratorItem) -> &'static str {
    infer_progress_unit_from_parts(
        &it.kind,
        it.media_format.as_deref(),
        &it.tags,
        it.book_volumes,
        it.book_chapters,
    )
}

pub struct CoverProjection {
    pub cover_obj: Value,
    pub cover_type: &'static str,   // "local" | "remote" | "none"
    pub cover_source: &'static str, // "user_custom" | "external_remote" | "external_native" | "none"
    pub cover_url: Option<String>,
    pub cover_local_path: Option<String>,
    /// 本地文件按磁盘存在性返回 Some(true/false)；远程 URL 严格反映缓存探测结果，未探测时返回 None（JSON null），禁止把未探测 URL 假标 true。
    pub cover_available: Option<bool>,
    pub cover_updated_at: Option<String>,
    pub missing_cover: bool,
}

/// 判定本地 `animeow-cover://` 文件是否属于程序回填/入库自动落盘（external_native），其余均保守视为用户自定义（user_custom）。
pub fn is_auto_native_cover_rel(rel: &str, field_source_cover: Option<&str>) -> bool {
    let fname = rel.rsplit('/').next().unwrap_or(rel);
    if fname.starts_with("rehydrate_") || fname.starts_with("create_") {
        return true;
    }
    matches!(field_source_cover, Some("external"))
}

/// 统一投影作品封面信息，保证 `cover.source`（`user_custom` | `external_remote` | `external_native` | `none`）与 `fieldSources.cover` 单一事实来源一致。
pub fn inspect_cover_metadata_with_source(
    raw_cover: &str,
    field_source_cover: Option<&str>,
    studio_data_dir: Option<&Path>,
    local_conn: Option<&Connection>,
) -> CoverProjection {
    let trimmed = raw_cover.trim();
    if is_blank_or_null_field(trimmed) {
        return CoverProjection {
            cover_obj: json!({
                "type": "none",
                "coverType": "none",
                "source": "none",
                "url": Value::Null,
                "localPath": Value::Null,
                "available": false,
            }),
            cover_type: "none",
            cover_source: "none",
            cover_url: None,
            cover_local_path: None,
            cover_available: Some(false),
            cover_updated_at: None,
            missing_cover: true,
        };
    }
    if trimmed.starts_with("animeow-cover://") {
        let rel = trimmed.trim_start_matches("animeow-cover://");
        let source = if is_auto_native_cover_rel(rel, field_source_cover) {
            "external_native"
        } else {
            "user_custom"
        };
        let (resolved_path, available, updated_at) = if let Some(dir) = studio_data_dir {
            let covers_root = dir.join("covers");
            if let Some((_, abs_path)) = crate::backup::resolve_existing_cover_file(&covers_root, rel) {
                let mtime = std::fs::metadata(&abs_path)
                    .ok()
                    .and_then(|m| m.modified().ok())
                    .map(|t| chrono::DateTime::<chrono::Utc>::from(t).to_rfc3339());
                (Some(abs_path.to_string_lossy().to_string()), true, mtime)
            } else {
                (Some(covers_root.join(rel).to_string_lossy().to_string()), false, None)
            }
        } else {
            (Some(rel.to_string()), true, None)
        };
        return CoverProjection {
            cover_obj: json!({
                "type": "local_native",
                "coverType": "local",
                "source": source,
                "url": trimmed,
                "localPath": resolved_path,
                "available": available,
            }),
            cover_type: "local",
            cover_source: source,
            cover_url: Some(trimmed.to_string()),
            cover_local_path: resolved_path,
            cover_available: Some(available),
            cover_updated_at: updated_at,
            missing_cover: !available,
        };
    }
    if trimmed.starts_with("file://")
        || trimmed.starts_with('/')
        || trimmed.get(1..3).is_some_and(|s| s == ":\\" || s == ":/")
    {
        let local_path = trimmed.trim_start_matches("file:///").trim_start_matches("file://");
        let path_obj = std::path::Path::new(local_path);
        let exists = path_obj.exists();
        let mtime = if exists {
            std::fs::metadata(path_obj)
                .ok()
                .and_then(|m| m.modified().ok())
                .map(|t| chrono::DateTime::<chrono::Utc>::from(t).to_rfc3339())
        } else {
            None
        };
        return CoverProjection {
            cover_obj: json!({
                "type": "local_file",
                "coverType": "local",
                "source": "user_custom",
                "url": trimmed,
                "localPath": local_path,
                "available": exists,
            }),
            cover_type: "local",
            cover_source: "user_custom",
            cover_url: Some(trimmed.to_string()),
            cover_local_path: Some(local_path.to_string()),
            cover_available: Some(exists),
            cover_updated_at: mtime,
            missing_cover: !exists,
        };
    }
    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        let host_kind = if trimmed.contains("bgm.tv")
            || trimmed.contains("lain.bgm.tv")
            || trimmed.contains("img-bgm.xunlys.top")
            || trimmed.contains("/api/catalog/public/image")
        {
            "bangumi"
        } else if trimmed.contains("vndb") {
            "vndb"
        } else if trimmed.contains("dlsite") {
            "dlsite"
        } else {
            "other"
        };
        let cached = local_conn.and_then(|c| crate::agent::storage::get_cached_cover_probe(c, trimmed));
        let (avail_opt, checked_at) = match cached {
            Some((ok, ts)) => (Some(ok), Some(ts)),
            None => (None, None),
        };
        return CoverProjection {
            cover_obj: json!({
                "type": "remote_url",
                "coverType": "remote",
                "source": "external_remote",
                "provider": host_kind,
                "url": trimmed,
                "localPath": Value::Null,
                "available": avail_opt,
            }),
            cover_type: "remote",
            cover_source: "external_remote",
            cover_url: Some(trimmed.to_string()),
            cover_local_path: None,
            cover_available: avail_opt,
            cover_updated_at: checked_at,
            missing_cover: avail_opt == Some(false),
        };
    }
    CoverProjection {
        cover_obj: json!({
            "type": "unknown",
            "coverType": "none",
            "source": "none",
            "url": trimmed,
            "localPath": Value::Null,
            "available": false,
        }),
        cover_type: "none",
        cover_source: "none",
        cover_url: None,
        cover_local_path: None,
        cover_available: Some(false),
        cover_updated_at: None,
        missing_cover: true,
    }
}

/// D3/D5：兼容旧调用签名（无 `fieldSources` 提示时按文件名规则识别）
pub fn inspect_cover_metadata_detailed(
    raw_cover: &str,
    studio_data_dir: Option<&Path>,
    local_conn: Option<&Connection>,
) -> CoverProjection {
    inspect_cover_metadata_with_source(raw_cover, None, studio_data_dir, local_conn)
}

/// D3：兼容旧调用签名
pub fn inspect_cover_metadata(raw_cover: &str) -> (Value, bool) {
    let p = inspect_cover_metadata_detailed(raw_cover, None, None);
    (p.cover_obj, p.missing_cover)
}

fn explicit_anime_format_type(mf: &str) -> Option<&'static str> {
    let low = mf.trim().to_ascii_lowercase();
    if low.is_empty() {
        return None;
    }
    if low.contains("剧场版")
        || low.contains("劇場版")
        || low.contains("电影")
        || low.contains("映画")
        || low == "movie"
        || low.contains(" movie")
    {
        return Some("剧场版");
    }
    if low.contains("oad") {
        return Some("OAD");
    }
    if low.contains("ova") {
        return Some("OVA");
    }
    if low == "web" || low.contains("web动画") || low.contains("网络动画") || low == "ona" {
        return Some("WEB");
    }
    if low.contains("总集篇") || low.contains("総集編") || low.contains("recap") {
        return Some("总集篇");
    }
    if low.contains("特别篇") || low.contains("特別編") || low.contains("短片") || low == "sp" {
        return Some("特别篇/短片");
    }
    if low == "tv" || low.contains("tv动画") || low == "番剧" {
        return Some("TV");
    }
    None
}

pub fn is_movie_work(it: &CuratorItem) -> bool {
    if it.kind != "anime" {
        return false;
    }
    if let Some(explicit) = it.media_format.as_deref().and_then(explicit_anime_format_type) {
        return explicit == "剧场版";
    }
    let check = |s: &str| {
        let low = s.trim().to_ascii_lowercase();
        low.contains("剧场版")
            || low.contains("劇場版")
            || low.contains("电影")
            || low.contains("映画")
            || low == "movie"
            || low.contains(" movie")
    };
    it.tags.iter().any(|t| check(t)) || check(&it.title) || check(&it.original_title)
}

/// 明确区分 `workType` 是来自库内显式存储的 `mediaFormat`，还是读取时根据标题/标签动态推断。
pub fn work_type_source(it: &CuratorItem) -> &'static str {
    match it.kind.as_str() {
        "game" | "audio" => "explicit",
        "book" => {
            if it.media_format.as_deref().is_some_and(|s| !s.trim().is_empty()) {
                "explicit"
            } else {
                "inferred"
            }
        }
        _ => {
            if it
                .media_format
                .as_deref()
                .and_then(explicit_anime_format_type)
                .is_some()
            {
                "explicit"
            } else {
                "inferred"
            }
        }
    }
}

pub fn infer_work_type(it: &CuratorItem) -> &'static str {
    match it.kind.as_str() {
        "game" => "游戏",
        "audio" => "音声",
        "book" => {
            let mf = it
                .media_format
                .as_deref()
                .unwrap_or("")
                .to_ascii_lowercase();
            if mf.contains("漫画")
                || mf.contains("manga")
                || it.tags.iter().any(|t| t.trim() == "漫画")
            {
                "漫画"
            } else if mf.contains("小说")
                || mf.contains("novel")
                || it.tags.iter().any(|t| t.trim().contains("小说"))
            {
                "轻小说"
            } else if it.book_chapters.is_some() && it.book_volumes.is_none() {
                "漫画"
            } else {
                "书籍"
            }
        }
        _ => {
            if let Some(explicit) = it.media_format.as_deref().and_then(explicit_anime_format_type) {
                return explicit;
            }
            if is_movie_work(it) {
                return "剧场版";
            }
            let hay = format!(
                "{} {} {} {}",
                it.media_format.as_deref().unwrap_or(""),
                it.title,
                it.original_title,
                it.tags.join(" ")
            )
            .to_ascii_lowercase();
            if hay.contains("总集篇") || hay.contains("総集編") || hay.contains("recap") {
                "总集篇"
            } else if hay.contains("oad") {
                "OAD"
            } else if hay.contains("ova") {
                "OVA"
            } else if hay.contains("web") || hay.contains("网络动画") {
                "WEB"
            } else if hay.contains("特别篇")
                || hay.contains("特別編")
                || hay.contains("lite")
                || hay.contains("短片")
            {
                "特别篇/短片"
            } else {
                "TV"
            }
        }
    }
}

fn format_progress_for_ai(kind: &str, primary: i64, total: i64) -> String {
    if kind == "game" {
        format!("{primary} 分钟")
    } else if kind == "audio" {
        if total > 0 { format!("{primary}/{total} 秒") } else { format!("{primary} 秒（总时长未知）") }
    } else if total > 0 {
        format!("{primary}/{total}")
    } else if kind == "book" {
        format!("{primary}/未知（连载中或总卷数未知）")
    } else {
        format!("{primary}/未知（总集数未知）")
    }
}

pub fn build_library_brief_line(sync_conn: &Connection, local_conn: &Connection) -> String {
    let Ok(items) = query_curator_items(sync_conn, local_conn) else {
        return "藏品库暂未读取".into();
    };
    let mut anime = 0usize;
    let mut book = 0usize;
    let mut game = 0usize;
    let mut audio = 0usize;
    let mut watching = 0usize;
    for it in &items {
        match it.kind.as_str() {
            "anime" => anime += 1,
            "book" => book += 1,
            "game" => game += 1,
            "audio" => audio += 1,
            _ => {}
        }
        if it.status.contains('在') || it.status.contains("进行") {
            watching += 1;
        }
    }
    format!(
        "共 {} 部（动画 {} / 书籍 {} / 游戏 {} / 音声 {}），当前在看/进行中 {} 部",
        items.len(),
        anime,
        book,
        game,
        audio,
        watching
    )
}

pub fn infer_personal_score_source(
    personal_score: Option<f64>,
    public_score: Option<f64>,
    explicit_source: Option<&str>,
) -> Option<&'static str> {
    match (personal_score, public_score) {
        (None, _) => None,
        (Some(_), _) if explicit_source == Some("user") => Some("user"),
        (Some(p), Some(pub_s)) if (p - pub_s).abs() < 0.05 => Some("suspected_external"),
        (Some(_), _) => Some("user"),
    }
}

pub fn exec_get_library_overview(sync_conn: &Connection, local_conn: &Connection) -> Result<Value> {
    let items = query_curator_items(sync_conn, local_conn)?;
    let score_sources = crate::agent::storage::load_field_source_map(local_conn, "personal_score");
    let mut by_kind: HashMap<String, usize> = HashMap::new();
    let mut by_status: HashMap<String, usize> = HashMap::new();
    let mut tag_counts: HashMap<String, usize> = HashMap::new();
    let mut scored_sum = 0.0f64;
    let mut scored_count = 0usize;
    let mut suspected_external_score_count = 0usize;
    let mut score_buckets = [0usize; 5]; // <6, 6-7, 7-8, 8-9, 9-10

    for it in &items {
        *by_kind.entry(it.kind.clone()).or_insert(0) += 1;
        *by_status.entry(it.status.clone()).or_insert(0) += 1;
        for t in &it.tags {
            *tag_counts.entry(t.clone()).or_insert(0) += 1;
        }
        if let Some(sc) = it.personal_score {
            scored_sum += sc;
            scored_count += 1;
            let explicit = score_sources
                .get(&(it.kind.clone(), it.id))
                .map(String::as_str);
            if infer_personal_score_source(it.personal_score, it.public_score, explicit)
                == Some("suspected_external")
            {
                suspected_external_score_count += 1;
            }
            if sc < 6.0 {
                score_buckets[0] += 1;
            } else if sc < 7.0 {
                score_buckets[1] += 1;
            } else if sc < 8.0 {
                score_buckets[2] += 1;
            } else if sc < 9.0 {
                score_buckets[3] += 1;
            } else {
                score_buckets[4] += 1;
            }
        }
    }

    let mut top_tags: Vec<(String, usize)> = tag_counts.into_iter().collect();
    top_tags.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    top_tags.truncate(20);

    let series_count = list_series_manage(sync_conn).map(|v| v.len()).unwrap_or(0);
    let char_count = list_characters_manage(sync_conn)
        .map(|v| v.len())
        .unwrap_or(0);
    let dup_count = query_duplicate_groups(sync_conn)
        .map(|v| v.len())
        .unwrap_or(0);
    let suggest_count = query_series_suggestions(sync_conn)
        .map(|v| v.len())
        .unwrap_or(0);

    Ok(json!({
        "totalWorks": items.len(),
        "byKind": by_kind,
        "byStatus": by_status,
        "scoredCount": scored_count,
        "suspectedExternalScoreCount": suspected_external_score_count,
        "averageScore": if scored_count > 0 { ((scored_sum / scored_count as f64) * 100.0).round() / 100.0 } else { 0.0 },
        "scoreDistribution": {
            "below6": score_buckets[0],
            "6to7": score_buckets[1],
            "7to8": score_buckets[2],
            "8to9": score_buckets[3],
            "9to10": score_buckets[4],
        },
        "topTags": top_tags.into_iter().map(|(name, count)| json!({ "name": name, "count": count })).collect::<Vec<_>>(),
        "seriesCount": series_count,
        "characterCount": char_count,
        "pendingDuplicateGroups": dup_count,
        "pendingSeriesSuggestions": suggest_count,
        "recommendedExternalSourceByKind": {
            "anime": "bangumi",
            "book": "bangumi",
            "game": "vndb",
            "audio": "dlsite",
        },
    }))
}

pub fn exec_query_library(
    sync_conn: &Connection,
    local_conn: &Connection,
    args: &Value,
) -> Result<Value> {
    let items = query_curator_items(sync_conn, local_conn)?;

    // 批量标题存在性核查模式（一次核查最多 30 部作品是否已在藏品库中，省去循环多次调工具）
    if let Some(kw_arr) = args.get("keywords").and_then(|v| v.as_array()) {
        let queries: Vec<String> = kw_arr
            .iter()
            .filter_map(|v| v.as_str())
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .take(30)
            .collect();
        if !queries.is_empty() {
            let mut results = Vec::with_capacity(queries.len());
            for q in &queries {
                let q_low = q.to_ascii_lowercase();
                let hits: Vec<Value> = items
                    .iter()
                    .filter(|it| {
                        let hay = format!(
                            "{} {} {}",
                            it.title,
                            it.original_title,
                            it.series_name.as_deref().unwrap_or("")
                        )
                        .to_ascii_lowercase();
                        hay.contains(&q_low)
                    })
                    .take(3)
                    .map(|it| {
                        json!({
                            "id": it.id,
                            "kind": it.kind,
                            "title": it.title,
                            "status": it.status,
                            "score": it.personal_score,
                        })
                    })
                    .collect();
                results.push(json!({
                    "query": q,
                    "inLibrary": !hits.is_empty(),
                    "matches": hits,
                }));
            }
            return Ok(json!({
                "mode": "batch_check",
                "checked": results.len(),
                "results": results,
            }));
        }
    }

    validate_query_library_args(args)?;
    let matched = filter_library_with_local(sync_conn, Some(local_conn), &items, args);
    let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(20).clamp(1, 30) as usize;
    let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
    let total = matched.len();
    Ok(library_record_page_with_local(
        Some(local_conn),
        matched.into_iter(),
        total,
        offset,
        limit,
    ))
}

pub fn validate_query_library_args(args: &Value) -> Result<()> {
    let missing_external_id = args
        .get("missing_external_id")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_external_source = args
        .get("missing_external_source")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty());
    if missing_external_id && missing_external_source.is_some() {
        return Err(StudioError::InvalidInput(
            "missing_external_id 与 missing_external_source 不能同时传入，请二选一。".into(),
        ));
    }
    if let Some(src) = missing_external_source {
        if !matches!(src, "bangumi" | "vndb" | "dlsite") {
            return Err(StudioError::InvalidInput(format!(
                "不支持的 missing_external_source `{src}`，仅支持 bangumi / vndb / dlsite。"
            )));
        }
    }
    if let Some(ss) = args
        .get("score_source")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        if !matches!(ss, "user" | "suspected_external") {
            return Err(StudioError::InvalidInput(format!(
                "不支持的 score_source `{ss}`，仅支持 user / suspected_external。"
            )));
        }
    }
    Ok(())
}

pub fn library_record_page<'a>(
    items: impl Iterator<Item = &'a CuratorItem>,
    total: usize,
    offset: usize,
    limit: usize,
) -> Value {
    library_record_page_with_local(None, items, total, offset, limit)
}

pub fn library_record_page_with_local<'a>(
    local_conn: Option<&Connection>,
    items: impl Iterator<Item = &'a CuratorItem>,
    total: usize,
    offset: usize,
    limit: usize,
) -> Value {
    let score_sources = local_conn
        .map(|c| crate::agent::storage::load_field_source_map(c, "personal_score"))
        .unwrap_or_default();
    let mut page = Vec::new();
    let mut chars = 256;
    for item in items.skip(offset).take(limit) {
        let explicit = score_sources
            .get(&(item.kind.clone(), item.id))
            .map(String::as_str);
        let row = library_item_summary_with_source(item, explicit);
        let length = row.to_string().chars().count() + 1;
        if !page.is_empty() && chars + length > super::registry::ARTIFACT_SPILL_THRESHOLD_CHARS {
            break;
        }
        chars += length;
        page.push(row);
    }
    let shown = page.len();
    let end = offset.saturating_add(shown);
    json!({
        "total": total,
        "offset": offset,
        "shown": shown,
        "coverageNote": "已读取本页，尚需逐条核验",
        "hasMore": end < total,
        "nextOffset": (end < total).then_some(end),
        "items": page,
    })
}

pub fn filter_library<'a>(
    sync_conn: &Connection,
    items: &'a [CuratorItem],
    args: &Value,
) -> Vec<&'a CuratorItem> {
    filter_library_with_local(sync_conn, None, items, args)
}

pub fn filter_library_with_local<'a>(
    sync_conn: &Connection,
    local_conn: Option<&Connection>,
    items: &'a [CuratorItem],
    args: &Value,
) -> Vec<&'a CuratorItem> {
    let keyword = args
        .get("keyword")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    let kind = args
        .get("kind")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty() && *s != "all");
    let status = args
        .get("status")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty() && *s != "all")
        .map(|s| match s {
            "已看" | "已看完" => "看完",
            "抛弃" => "弃坑",
            other => other,
        });
    let series = args
        .get("series")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    let unassigned_series = args
        .get("unassigned_series")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let tag = args
        .get("tag")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    let min_score = args.get("min_score").and_then(|v| v.as_f64());
    let max_score = args.get("max_score").and_then(|v| v.as_f64());
    let score_source_filter = args
        .get("score_source")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| matches!(*s, "user" | "suspected_external"));
    let score_sources = if score_source_filter.is_some() {
        local_conn
            .map(|c| crate::agent::storage::load_field_source_map(c, "personal_score"))
            .unwrap_or_default()
    } else {
        HashMap::new()
    };
    let progress_total_unknown = args
        .get("progress_total_unknown")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_score = args
        .get("missing_score")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_tags = args
        .get("missing_tags")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_release_date = args
        .get("missing_release_date")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_cover = args
        .get("missing_cover")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let incomplete_metadata = args
        .get("incomplete_metadata")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let incomplete_fields: Vec<String> = args
        .get("incomplete_fields")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(|s| s.trim().to_ascii_lowercase()))
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default();
    let effective_incomplete_fields: Vec<&str> = if !incomplete_fields.is_empty() {
        incomplete_fields.iter().map(String::as_str).collect()
    } else {
        vec!["cover", "release_date", "tags"]
    };
    let missing_external_id = args
        .get("missing_external_id")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let missing_external_source = args
        .get("missing_external_source")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| matches!(*s, "bangumi" | "vndb" | "dlsite"));
    let works_with_ext_id = if missing_external_id {
        Some(collect_works_with_external_id(sync_conn))
    } else {
        None
    };
    let works_with_source_id =
        missing_external_source.map(|src| collect_works_with_source_id(sync_conn, src));
    let works_with_characters: Option<std::collections::HashSet<i64>> =
        if incomplete_metadata && effective_incomplete_fields.iter().any(|f| matches!(*f, "characters" | "character")) {
            let mut set = std::collections::HashSet::new();
            if let Ok(mut stmt) = sync_conn.prepare("SELECT DISTINCT animeId FROM anime_characters") {
                if let Ok(rows) = stmt.query_map([], |r| r.get::<_, i64>(0)) {
                    for id in rows.flatten() {
                        set.insert(id);
                    }
                }
            }
            Some(set)
        } else {
            None
        };
    let works_with_summary: Option<std::collections::HashSet<i64>> =
        if incomplete_metadata && effective_incomplete_fields.iter().any(|f| matches!(*f, "summary" | "synopsis")) {
            let mut set = std::collections::HashSet::new();
            if let Ok(mut stmt) = sync_conn.prepare("SELECT id FROM animes WHERE COALESCE(TRIM(synopsis), '') <> ''") {
                if let Ok(rows) = stmt.query_map([], |r| r.get::<_, i64>(0)) {
                    for id in rows.flatten() {
                        set.insert(id);
                    }
                }
            }
            Some(set)
        } else {
            None
        };
    let sort = args
        .get("sort")
        .and_then(|v| v.as_str())
        .unwrap_or("recent");

    let mut matched: Vec<&CuratorItem> = items
        .iter()
        .filter(|it| {
            if let Some(k) = kind {
                if it.kind != k {
                    return false;
                }
            }
            if let Some(st) = status {
                let actual = it.status.trim();
                let same_status = actual == st
                    || matches!((st, actual), ("看完", "已看" | "已看完") | ("弃坑", "抛弃"));
                if !same_status {
                    return false;
                }
            }
            if unassigned_series && it.series_name.as_deref().map(|s| !s.trim().is_empty()).unwrap_or(false) {
                return false;
            }
            if progress_total_unknown && (it.kind == "game" || it.progress_total > 0) {
                return false;
            }
            if missing_score && it.personal_score.is_some() {
                return false;
            }
            if missing_tags && !it.tags.is_empty() {
                return false;
            }
            if missing_release_date && !is_blank_or_null_field(&it.release_date) {
                return false;
            }
            if missing_cover {
                let (_, is_missing) = inspect_cover_metadata(&it.cover_url);
                if !is_missing {
                    return false;
                }
            }
            if incomplete_metadata {
                let has_missing_required = effective_incomplete_fields.iter().any(|field| match *field {
                    "cover" | "cover_url" => inspect_cover_metadata(&it.cover_url).1,
                    "release_date" | "air_date" => is_blank_or_null_field(&it.release_date),
                    "tags" => it.tags.is_empty(),
                    "score" | "personal_score" => it.personal_score.is_none(),
                    "studio" | "studio_or_circle" => is_blank_or_null_field(&it.studio_or_circle),
                    "external_id" => it.external_id.as_deref().map(is_blank_or_null_field).unwrap_or(true),
                    "total_episodes" | "progress_total" => it.kind != "game" && it.progress_total == 0,
                    "characters" | "character" => {
                        matches!(it.kind.as_str(), "anime" | "book")
                            && works_with_characters.as_ref().is_some_and(|s| !s.contains(&it.id))
                    }
                    "summary" | "synopsis" => {
                        matches!(it.kind.as_str(), "anime" | "book")
                            && works_with_summary.as_ref().is_some_and(|s| !s.contains(&it.id))
                    }
                    _ => false,
                });
                if !has_missing_required {
                    return false;
                }
            }
            if let Some(ref ext_set) = works_with_ext_id {
                if ext_set.contains(&(it.kind.clone(), it.id)) {
                    return false;
                }
            }
            if let (Some(src), Some(ref src_set)) = (missing_external_source, &works_with_source_id) {
                let kind_applicable = match src {
                    "bangumi" => {
                        if kind == Some("game") {
                            it.kind == "game"
                        } else {
                            matches!(it.kind.as_str(), "anime" | "book")
                        }
                    }
                    "vndb" => it.kind == "game",
                    "dlsite" => it.kind == "audio",
                    _ => false,
                };
                if !kind_applicable || src_set.contains(&(it.kind.clone(), it.id)) {
                    return false;
                }
            }
            if let Some(ref ser) = series {
                let s_low = it
                    .series_name
                    .as_deref()
                    .unwrap_or("")
                    .to_ascii_lowercase();
                if !s_low.contains(ser) {
                    return false;
                }
            }
            if let Some(ref tg) = tag {
                if !it.tags.iter().any(|t| t.to_ascii_lowercase().contains(tg)) {
                    return false;
                }
            }
            if let Some(min_s) = min_score {
                if it.personal_score.unwrap_or(-1.0) < min_s {
                    return false;
                }
            }
            if let Some(max_s) = max_score {
                if it.personal_score.unwrap_or(999.0) > max_s {
                    return false;
                }
            }
            if let Some(expected_ss) = score_source_filter {
                let explicit = score_sources
                    .get(&(it.kind.clone(), it.id))
                    .map(String::as_str);
                if infer_personal_score_source(it.personal_score, it.public_score, explicit)
                    != Some(expected_ss)
                {
                    return false;
                }
            }
            if let Some(ref kw) = keyword {
                let hay = format!(
                    "{} {} {} {} {}",
                    it.title,
                    it.original_title,
                    it.studio_or_circle,
                    it.series_name.as_deref().unwrap_or(""),
                    it.tags.join(" ")
                )
                .to_ascii_lowercase();
                if !hay.contains(kw) {
                    return false;
                }
            }
            true
        })
        .collect();

    matched.sort_by(|a, b| {
        let order = match sort {
        "score_desc" => {
            b.personal_score
                .unwrap_or(-1.0)
                .partial_cmp(&a.personal_score.unwrap_or(-1.0))
                .unwrap_or(std::cmp::Ordering::Equal)
        }
        "score_asc" => {
            a.personal_score
                .unwrap_or(999.0)
                .partial_cmp(&b.personal_score.unwrap_or(999.0))
                .unwrap_or(std::cmp::Ordering::Equal)
        }
        "title" => a.title.cmp(&b.title),
        _ => b.last_modified_at.cmp(&a.last_modified_at),
        };
        order.then_with(|| (&a.kind, a.id).cmp(&(&b.kind, b.id)))
    });

    matched
}

pub fn library_item_summary(it: &CuratorItem) -> Value {
    library_item_summary_with_source(it, None)
}

pub fn library_item_summary_with_source(it: &CuratorItem, explicit_score_source: Option<&str>) -> Value {
    let (_, missing_cover) = inspect_cover_metadata(&it.cover_url);
    let score_source = infer_personal_score_source(
        it.personal_score,
        it.public_score,
        explicit_score_source,
    );
    json!({
        "id": it.id,
        "kind": it.kind,
        "title": it.title.chars().take(180).collect::<String>(),
        "originalTitle": it.original_title.chars().take(180).collect::<String>(),
        "status": it.status.chars().take(40).collect::<String>(),
        "progress": format_progress_for_ai(&it.kind, it.progress_primary, it.progress_total),
        "progressUnit": infer_progress_unit(it),
        "workType": infer_work_type(it),
        "progressTotalUnknown": it.kind != "game" && it.progress_total == 0,
        "score": it.personal_score,
        "publicScore": it.public_score,
        "personalScoreSource": score_source,
        "releaseDate": it.release_date.chars().take(40).collect::<String>(),
        "missingCover": missing_cover,
        "series": it.series_name.as_ref().map(|name| name.chars().take(80).collect::<String>()),
        "tags": it.tags.iter().take(6).map(|tag| tag.chars().take(24).collect::<String>()).collect::<Vec<_>>(),
        "mediaFormat": it.media_format.as_ref().map(|name| name.chars().take(40).collect::<String>()),
        "movieFormat": is_movie_work(it),
        "externalSource": it.external_source,
        "externalId": it.external_id,
        "updatedAt": it.last_modified_at,
    })
}

fn clean_opt_str(raw: Option<String>) -> Option<String> {
    raw.map(|s| s.trim().to_string())
        .filter(|s| !is_blank_or_null_field(s))
}

fn fetch_deleted_work_detail(
    sync_conn: &Connection,
    work_id: i64,
    kind: Option<&str>,
    include_review: bool,
) -> Option<Value> {
    let review_cap = if include_review { 2000 } else { 240 };
    if kind.is_none() || matches!(kind, Some("anime" | "book")) {
        let row: Option<(
            i64,
            String,
            String,
            String,
            String,
            i64,
            i64,
            Option<i64>,
            String,
            String,
            Option<String>,
            Option<String>,
            Option<String>,
            String,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
        )> = sync_conn
            .query_row(
                "SELECT a.id, COALESCE(a.subjectType, 'anime'), a.title, COALESCE(a.originalTitle, ''),
                        a.status, a.watchedEpisodes, a.totalEpisodes, a.rating,
                        COALESCE(a.studio, ''), COALESCE(a.airDate, ''), a.mediaFormat,
                        s.name, a.seriesRole, COALESCE(a.review, ''), a.memo,
                        a.externalSource, a.externalId, a.deletedAt
                 FROM animes a
                 LEFT JOIN series s ON s.id = a.seriesId
                 WHERE a.id = ?1 AND a.deletedAt IS NOT NULL",
                [work_id],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                        r.get(6)?,
                        r.get(7)?,
                        r.get(8)?,
                        r.get(9)?,
                        r.get(10)?,
                        r.get(11)?,
                        r.get(12)?,
                        r.get(13)?,
                        r.get(14)?,
                        r.get(15)?,
                        r.get(16)?,
                        r.get(17)?,
                    ))
                },
            )
            .optional()
            .ok()
            .flatten();
        if let Some((
            id,
            raw_subj,
            title,
            original_title,
            status,
            watched,
            total,
            raw_rating,
            studio,
            air_date,
            media_format,
            series_name,
            series_role,
            review,
            memo,
            ext_src,
            ext_id,
            deleted_at,
        )) = row
        {
            let resolved_kind = if matches!(
                raw_subj.to_ascii_lowercase().as_str(),
                "book" | "manga" | "novel"
            ) {
                "book"
            } else {
                "anime"
            };
            if kind.is_none() || kind == Some(resolved_kind) {
                let (book_volumes, book_chapters, cover_raw, updated_raw): (
                    Option<i64>,
                    Option<i64>,
                    String,
                    Option<i64>,
                ) = sync_conn
                    .query_row(
                        "SELECT bookReadVolumes, bookReadChapters, COALESCE(coverUrl, ''), updatedAt
                         FROM animes WHERE id = ?1",
                        [id],
                        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                    )
                    .unwrap_or((None, None, String::new(), None));
                let del_tags: Vec<String> = sync_conn
                    .prepare(
                        "SELECT t.name FROM tags t JOIN anime_tags at ON at.tagId = t.id WHERE at.animeId = ?1",
                    )
                    .and_then(|mut s| {
                        let rows = s.query_map([id], |r| r.get::<_, String>(0))?;
                        Ok(rows.flatten().collect())
                    })
                    .unwrap_or_default();
                let unit = infer_progress_unit_from_parts(
                    resolved_kind,
                    media_format.as_deref(),
                    &del_tags,
                    book_volumes,
                    book_chapters,
                );
                let cov = inspect_cover_metadata_detailed(&cover_raw, None, None);
                return Some(json!({
                    "found": true,
                    "deleted": true,
                    "deletedAt": deleted_at,
                    "id": id,
                    "kind": resolved_kind,
                    "title": title,
                    "originalTitle": original_title,
                    "status": status,
                    "progressPrimary": watched,
                    "progressTotal": total,
                    "progressUnit": unit,
                    "progressTotalUnknown": total == 0,
                    "progressText": format_progress_for_ai(resolved_kind, watched, total),
                    "personalScore": raw_rating.map(|v| (v as f64) / 10.0),
                    "studioOrCircle": studio,
                    "releaseDate": air_date,
                    "mediaFormat": clean_opt_str(media_format),
                    "seriesName": series_name,
                    "seriesRole": series_role,
                    "tags": del_tags,
                    "coverType": cov.cover_type,
                    "coverUrl": cov.cover_url,
                    "coverLocalPath": cov.cover_local_path,
                    "coverAvailable": cov.cover_available,
                    "coverUpdatedAt": cov.cover_updated_at,
                    "cover": cov.cover_obj,
                    "missingCover": cov.missing_cover,
                    "memo": memo,
                    "reviewPreview": review.chars().take(review_cap).collect::<String>(),
                    "externalSource": clean_opt_str(ext_src),
                    "externalId": clean_opt_str(ext_id),
                    "updatedAt": updated_raw,
                }));
            }
        }
    }
    if kind.is_none() || kind == Some("game") {
        let row: Option<(
            i64,
            String,
            String,
            String,
            i64,
            i64,
            String,
            String,
            String,
            String,
            Option<i64>,
            Option<String>,
            Option<String>,
        )> = sync_conn
            .query_row(
                "SELECT id, title, COALESCE(originalTitle, ''), status, playMinutes, rating,
                        COALESCE(developer, ''), COALESCE(releaseDate, ''), COALESCE(tags, ''),
                        COALESCE(review, ''), bangumiId, vndbId, deletedAt
                 FROM games WHERE id = ?1 AND deletedAt IS NOT NULL",
                [work_id],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                        r.get(6)?,
                        r.get(7)?,
                        r.get(8)?,
                        r.get(9)?,
                        r.get(10)?,
                        r.get(11)?,
                        r.get(12)?,
                    ))
                },
            )
            .optional()
            .ok()
            .flatten();
        if let Some((
            id,
            title,
            original_title,
            status,
            play_minutes,
            rating,
            developer,
            release_date,
            tags_raw,
            review,
            bgm_id,
            vndb_id,
            deleted_at,
        )) = row
        {
            let clean_vndb = clean_opt_str(vndb_id);
            let clean_bgm = bgm_id.filter(|&v| v > 0);
            let (ext_src, ext_id) = if let Some(ref vid) = clean_vndb {
                (Some("vndb".to_string()), Some(vid.clone()))
            } else if let Some(bid) = clean_bgm {
                (Some("bangumi".to_string()), Some(bid.to_string()))
            } else {
                (None, None)
            };
            let tags: Vec<String> = tags_raw
                .split([',', '，', ';'])
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            return Some(json!({
                "found": true,
                "deleted": true,
                "deletedAt": deleted_at,
                "id": id,
                "kind": "game",
                "title": title,
                "originalTitle": original_title,
                "status": status,
                "progressPrimary": play_minutes,
                "progressTotal": 0,
                "progressUnit": "分钟",
                "workType": "游戏",
                "personalScore": (rating > 0).then(|| if rating > 10 { (rating as f64) / 10.0 } else { rating as f64 }),
                "studioOrCircle": developer,
                "releaseDate": release_date,
                "tags": tags,
                "reviewPreview": review.chars().take(review_cap).collect::<String>(),
                "externalSource": ext_src,
                "externalId": ext_id,
                "bangumiId": clean_bgm,
                "vndbId": clean_vndb,
            }));
        }
    }
    if kind.is_none() || kind == Some("audio") {
        let row: Option<(
            i64,
            String,
            String,
            String,
            i64,
            i64,
            String,
            String,
            String,
            String,
            Option<String>,
        )> = sync_conn
            .query_row(
                "SELECT id, title, COALESCE(originalTitle, ''), status, listenedSeconds, durationSeconds,
                        COALESCE(circle, ''), COALESCE(releaseDate, ''), COALESCE(workCode, ''),
                        COALESCE(review, ''), deletedAt
                 FROM audio_works WHERE id = ?1 AND deletedAt IS NOT NULL",
                [work_id],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                        r.get(6)?,
                        r.get(7)?,
                        r.get(8)?,
                        r.get(9)?,
                        r.get(10)?,
                    ))
                },
            )
            .optional()
            .ok()
            .flatten();
        if let Some((
            id,
            title,
            original_title,
            status,
            listened,
            duration,
            circle,
            release_date,
            work_code,
            review,
            deleted_at,
        )) = row
        {
            let clean_code = (!is_blank_or_null_field(&work_code)).then(|| work_code.trim().to_string());
            return Some(json!({
                "found": true,
                "deleted": true,
                "deletedAt": deleted_at,
                "id": id,
                "kind": "audio",
                "title": title,
                "originalTitle": original_title,
                "status": status,
                "progressPrimary": listened,
                "progressTotal": duration,
                "progressUnit": "秒",
                "workType": "音声",
                "studioOrCircle": circle,
                "releaseDate": release_date,
                "reviewPreview": review.chars().take(review_cap).collect::<String>(),
                "externalSource": clean_code.as_ref().map(|_| "dlsite"),
                "externalId": clean_code,
            }));
        }
    }
    None
}

fn build_active_work_detail(
    sync_conn: &Connection,
    local_conn: &Connection,
    studio_data_dir: Option<&Path>,
    it: &CuratorItem,
    include_review: bool,
    compact: bool,
) -> Value {
    let characters = if !compact && (it.kind == "anime" || it.kind == "book") {
        list_work_characters(sync_conn, it.id).unwrap_or_default()
    } else {
        vec![]
    };

    let (ext_source, ext_id, extra_bgm_id, extra_vndb_id): (
        Option<String>,
        Option<String>,
        Option<i64>,
        Option<String>,
    ) = match it.kind.as_str() {
        "anime" | "book" => {
            let pair = sync_conn
                .query_row(
                    "SELECT externalSource, externalId FROM animes WHERE id = ?1",
                    [it.id],
                    |r| Ok((r.get::<_, Option<String>>(0)?, r.get::<_, Option<String>>(1)?)),
                )
                .ok();
            (
                pair.as_ref().and_then(|x| clean_opt_str(x.0.clone())),
                pair.as_ref().and_then(|x| clean_opt_str(x.1.clone())),
                None,
                None,
            )
        }
        "game" => {
            let pair = sync_conn
                .query_row(
                    "SELECT bangumiId, vndbId FROM games WHERE id = ?1",
                    [it.id],
                    |r| Ok((r.get::<_, Option<i64>>(0)?, r.get::<_, Option<String>>(1)?)),
                )
                .ok();
            let bgm = pair.as_ref().and_then(|x| x.0).filter(|&v| v > 0);
            let vndb = pair.as_ref().and_then(|x| clean_opt_str(x.1.clone()));
            let (s, id) = if let Some(ref v) = vndb {
                (Some("vndb".to_string()), Some(v.clone()))
            } else if let Some(b) = bgm {
                (Some("bangumi".to_string()), Some(b.to_string()))
            } else {
                (None, None)
            };
            (s, id, bgm, vndb)
        }
        "audio" => (it.external_source.clone(), it.external_id.clone(), None, None),
        _ => (None, None, None, None),
    };

    let review_text = if include_review {
        it.review.chars().take(2000).collect::<String>()
    } else if compact {
        it.review.chars().take(120).collect::<String>()
    } else {
        it.review.chars().take(240).collect::<String>()
    };

    let raw_field_sources =
        crate::agent::storage::load_work_field_sources(local_conn, &it.kind, it.id)
            .unwrap_or_default();
    let cov = inspect_cover_metadata_with_source(
        &it.cover_url,
        raw_field_sources.get("cover").map(|(s, _)| s.as_str()),
        studio_data_dir,
        Some(local_conn),
    );
    let mut field_sources: serde_json::Map<String, Value> = raw_field_sources
        .iter()
        .map(|(k, (src, _ts))| (k.clone(), json!(src)))
        .collect();
    // 单一事实来源一致性：保证 `fieldSources.cover` 与 `cover.source` 不矛盾
    match cov.cover_source {
        "user_custom" => {
            field_sources.insert("cover".into(), json!("user"));
        }
        "external_remote" | "external_native" => {
            field_sources.insert("cover".into(), json!("external"));
        }
        _ => {}
    }
    let cover_updated_at = cov.cover_updated_at.or_else(|| {
        raw_field_sources.get("cover").map(|(_, ts)| ts.clone())
    });
    let personal_score_source = infer_personal_score_source(
        it.personal_score,
        it.public_score,
        raw_field_sources.get("personal_score").map(|(s, _)| s.as_str()),
    );
    let mut val = json!({
        "found": true,
        "deleted": false,
        "id": it.id,
        "kind": it.kind,
        "title": it.title,
        "originalTitle": it.original_title,
        "status": it.status,
        "progressPrimary": it.progress_primary,
        "progressTotal": it.progress_total,
        "progressUnit": infer_progress_unit(it),
        "workType": infer_work_type(it),
        "workTypeSource": work_type_source(it),
        "movieFormat": is_movie_work(it),
        "progressTotalUnknown": it.kind != "game" && it.progress_total == 0,
        "progressText": format_progress_for_ai(&it.kind, it.progress_primary, it.progress_total),
        "personalScore": it.personal_score,
        "personalScoreSource": personal_score_source,
        "publicScore": it.public_score,
        "studioOrCircle": it.studio_or_circle,
        "releaseDate": it.release_date,
        "mediaFormat": it.media_format,
        "coverType": cov.cover_type,
        "coverUrl": cov.cover_url,
        "coverLocalPath": cov.cover_local_path,
        "coverAvailable": cov.cover_available,
        "coverUpdatedAt": cover_updated_at,
        "cover": cov.cover_obj,
        "missingCover": cov.missing_cover,
        "fieldSources": field_sources,
        "seriesName": it.series_name,
        "seriesRole": it.series_role,
        "tags": it.tags,
        "memo": it.memo,
        "reviewPreview": review_text,
        "reviewFullLength": it.review.chars().count(),
        "localFileCount": it.local_file_count,
        "externalSource": ext_source,
        "externalId": ext_id,
        "updatedAt": it.last_modified_at,
    });
    if it.kind == "game" {
        val["bangumiId"] = json!(extra_bgm_id);
        val["vndbId"] = json!(extra_vndb_id);
    }
    if !compact {
        val["characterTotal"] = json!(characters.len());
        val["characters"] = json!(characters.into_iter().take(25).map(|c| json!({
            "id": c.id,
            "name": if c.name_cn.is_empty() { c.name } else { c.name_cn },
            "role": c.role_name,
            "voiceActor": c.voice_actor,
        })).collect::<Vec<_>>());
    }
    val
}

pub fn exec_get_work_detail(
    sync_conn: &Connection,
    local_conn: &Connection,
    args: &Value,
) -> Result<Value> {
    exec_get_work_detail_with_dir(sync_conn, local_conn, None, args)
}

pub fn exec_get_work_detail_with_dir(
    sync_conn: &Connection,
    local_conn: &Connection,
    studio_data_dir: Option<&Path>,
    args: &Value,
) -> Result<Value> {
    let items = query_curator_items(sync_conn, local_conn)?;
    let kind = args.get("kind").and_then(|v| v.as_str()).map(str::trim);
    let include_review = args
        .get("include_review")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let include_deleted = args
        .get("include_deleted")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    // 批量读取详情模式（work_ids 最多 30 条）
    if let Some(id_arr) = args.get("work_ids").and_then(|v| v.as_array()) {
        if id_arr.is_empty() {
            return Err(StudioError::InvalidInput("work_ids 不能为空。".into()));
        }
        if id_arr.len() > 30 {
            return Err(StudioError::InvalidInput(
                "get_work_detail 单次最多批量读取 30 部作品，请分批查询。".into(),
            ));
        }
        let mut results = Vec::new();
        let mut missing_ids = Vec::new();
        for raw in id_arr {
            let Some(wid) = raw.as_i64() else {
                continue;
            };
            if let Some(it) = items
                .iter()
                .find(|it| it.id == wid && kind.map(|k| k == it.kind).unwrap_or(true))
            {
                results.push(build_active_work_detail(sync_conn, local_conn, studio_data_dir, it, include_review, true));
            } else if include_deleted {
                if let Some(del) = fetch_deleted_work_detail(sync_conn, wid, kind, include_review) {
                    results.push(del);
                } else {
                    missing_ids.push(wid);
                }
            } else {
                missing_ids.push(wid);
            }
        }
        return Ok(json!({
            "mode": "batch",
            "requested": id_arr.len(),
            "foundCount": results.len(),
            "missingIds": missing_ids,
            "items": results,
        }));
    }

    let work_id = args.get("work_id").and_then(|v| v.as_i64());
    let title_kw = args
        .get("title_keyword")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());

    let target: Option<&CuratorItem> = if let Some(wid) = work_id {
        items
            .iter()
            .find(|it| it.id == wid && kind.map(|k| k == it.kind).unwrap_or(true))
    } else if let Some(ref kw) = title_kw {
        let mut hits: Vec<&CuratorItem> = items
            .iter()
            .filter(|it| {
                let t = it.title.to_ascii_lowercase();
                let o = it.original_title.to_ascii_lowercase();
                t.contains(kw) || o.contains(kw)
            })
            .collect();
        if hits.len() > 1 {
            // 优先精确匹配
            if let Some(exact) = hits.iter().find(|it| it.title.to_ascii_lowercase() == *kw) {
                Some(*exact)
            } else {
                hits.truncate(10);
                return Ok(json!({
                    "ambiguous": true,
                    "message": format!("匹配到多个包含「{kw}」的作品，请指定精确 work_id"),
                    "candidates": hits.into_iter().map(|it| json!({
                        "id": it.id,
                        "kind": it.kind,
                        "title": it.title,
                        "originalTitle": it.original_title,
                        "status": it.status,
                        "progress": format_progress_for_ai(&it.kind, it.progress_primary, it.progress_total),
                        "progressUnit": infer_progress_unit(it),
                        "workType": infer_work_type(it),
                        "progressTotalUnknown": it.kind != "game" && it.progress_total == 0,
                        "score": it.personal_score,
                        "releaseDate": it.release_date,
                        "series": it.series_name,
                        "externalSource": it.external_source,
                        "externalId": it.external_id,
                    })).collect::<Vec<_>>(),
                }));
            }
        } else {
            hits.first().copied()
        }
    } else {
        return Err(StudioError::InvalidInput("请提供 work_id、work_ids 或 title_keyword".into()));
    };

    if let Some(it) = target {
        return Ok(build_active_work_detail(sync_conn, local_conn, studio_data_dir, it, include_review, false));
    }

    if include_deleted {
        if let Some(wid) = work_id {
            if let Some(del) = fetch_deleted_work_detail(sync_conn, wid, kind, include_review) {
                return Ok(del);
            }
        }
    }

    Ok(json!({
        "found": false,
        "code": "NOT_FOUND_LOCAL",
        "message": if include_deleted {
            "藏品库与回收站中均未找到对应作品"
        } else {
            "藏品库中未找到对应作品（若怀疑在回收站，请传 include_deleted=true 查询）"
        }
    }))
}

pub fn exec_find_external_id_usage(sync_conn: &Connection, args: &Value) -> Result<Value> {
    let source_filter = args
        .get("source")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty() && s != "all");
    if let Some(ref src) = source_filter {
        if !matches!(src.as_str(), "bangumi" | "vndb" | "dlsite") {
            return Err(StudioError::InvalidInput(format!(
                "不支持的 source `{src}`，仅支持 bangumi / vndb / dlsite。"
            )));
        }
    }

    let mut targets = Vec::<String>::new();
    if let Some(single) = args.get("external_id").and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()) {
        targets.push(single.to_string());
    } else if let Some(single_num) = args.get("external_id").and_then(|v| v.as_i64()) {
        targets.push(single_num.to_string());
    }
    if let Some(arr) = args.get("external_ids").and_then(|v| v.as_array()) {
        if arr.len() > 30 {
            return Err(StudioError::InvalidInput(
                "find_external_id_usage 单次最多反查 30 个外部 ID。".into(),
            ));
        }
        for item in arr {
            if let Some(s) = item.as_str().map(str::trim).filter(|s| !s.is_empty()) {
                targets.push(s.to_string());
            } else if let Some(n) = item.as_i64() {
                targets.push(n.to_string());
            }
        }
    }
    if targets.is_empty() {
        return Err(StudioError::InvalidInput(
            "请提供 external_id 或 external_ids 数组。".into(),
        ));
    }

    let mut matches = Vec::<Value>::new();
    for eid in &targets {
        let mut holders = Vec::<Value>::new();
        // 1. animes (含 anime & book，覆盖在库与回收站)
        if source_filter.is_none() || source_filter.as_deref() == Some("bangumi") {
            if let Ok(mut stmt) = sync_conn.prepare(
                "SELECT id, COALESCE(subjectType, 'anime'), title, COALESCE(originalTitle, ''),
                        externalSource, externalId, deletedAt
                 FROM animes
                 WHERE externalId = ?1
                   AND (?2 IS NULL OR LOWER(COALESCE(externalSource, '')) = ?2)",
            ) {
                if let Ok(rows) = stmt.query_map(params![eid, source_filter.as_deref()], |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, Option<String>>(4)?,
                        r.get::<_, Option<String>>(5)?,
                        r.get::<_, Option<String>>(6)?,
                    ))
                }) {
                    for row in rows.flatten() {
                        let kind = if matches!(
                            row.1.to_ascii_lowercase().as_str(),
                            "book" | "manga" | "novel"
                        ) {
                            "book"
                        } else {
                            "anime"
                        };
                        holders.push(json!({
                            "workId": row.0,
                            "kind": kind,
                            "title": row.2,
                            "originalTitle": row.3,
                            "externalSource": row.4,
                            "externalId": row.5,
                            "deleted": row.6.as_deref().is_some_and(|s| !s.trim().is_empty()),
                            "deletedAt": row.6,
                        }));
                    }
                }
            }
        }
        // 2. games (bangumiId / vndbId，覆盖在库与回收站)
        if source_filter.is_none() || source_filter.as_deref() == Some("bangumi") {
            if let Ok(num_id) = eid.parse::<i64>() {
                if let Ok(mut stmt) = sync_conn.prepare(
                    "SELECT id, title, COALESCE(originalTitle, ''), deletedAt
                     FROM games WHERE bangumiId = ?1",
                ) {
                    if let Ok(rows) = stmt.query_map([num_id], |r| {
                        Ok((
                            r.get::<_, i64>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, Option<String>>(3)?,
                        ))
                    }) {
                        for row in rows.flatten() {
                            holders.push(json!({
                                "workId": row.0,
                                "kind": "game",
                                "title": row.1,
                                "originalTitle": row.2,
                                "externalSource": "bangumi",
                                "externalId": num_id.to_string(),
                                "deleted": row.3.as_deref().is_some_and(|s| !s.trim().is_empty()),
                                "deletedAt": row.3,
                            }));
                        }
                    }
                }
            }
        }
        if source_filter.is_none() || source_filter.as_deref() == Some("vndb") {
            if let Ok(mut stmt) = sync_conn.prepare(
                "SELECT id, title, COALESCE(originalTitle, ''), vndbId, deletedAt
                 FROM games WHERE LOWER(COALESCE(vndbId, '')) = LOWER(?1)",
            ) {
                if let Ok(rows) = stmt.query_map([eid], |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, Option<String>>(4)?,
                    ))
                }) {
                    for row in rows.flatten() {
                        holders.push(json!({
                            "workId": row.0,
                            "kind": "game",
                            "title": row.1,
                            "originalTitle": row.2,
                            "externalSource": "vndb",
                            "externalId": row.3,
                            "deleted": row.4.as_deref().is_some_and(|s| !s.trim().is_empty()),
                            "deletedAt": row.4,
                        }));
                    }
                }
            }
        }
        // 3. audio_works (workCode，覆盖在库与回收站)
        if source_filter.is_none() || source_filter.as_deref() == Some("dlsite") {
            if let Ok(mut stmt) = sync_conn.prepare(
                "SELECT id, title, COALESCE(originalTitle, ''), workCode, deletedAt
                 FROM audio_works WHERE UPPER(COALESCE(workCode, '')) = UPPER(?1)",
            ) {
                if let Ok(rows) = stmt.query_map([eid], |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, Option<String>>(4)?,
                    ))
                }) {
                    for row in rows.flatten() {
                        holders.push(json!({
                            "workId": row.0,
                            "kind": "audio",
                            "title": row.1,
                            "originalTitle": row.2,
                            "externalSource": "dlsite",
                            "externalId": row.3,
                            "deleted": row.4.as_deref().is_some_and(|s| !s.trim().is_empty()),
                            "deletedAt": row.4,
                        }));
                    }
                }
            }
        }
        matches.push(json!({
            "externalId": eid,
            "inUse": !holders.is_empty(),
            "holders": holders,
        }));
    }

    if targets.len() == 1 {
        let first = matches.into_iter().next().unwrap();
        Ok(json!({
            "source": source_filter,
            "externalId": first["externalId"],
            "inUse": first["inUse"],
            "holders": first["holders"],
        }))
    } else {
        Ok(json!({
            "source": source_filter,
            "checked": matches.len(),
            "results": matches,
        }))
    }
}

pub fn exec_list_taxonomy(sync_conn: &Connection, args: &Value) -> Result<Value> {
    let entity = args
        .get("entity")
        .and_then(|v| v.as_str())
        .unwrap_or("series");
    let kw = args
        .get("keyword")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty());
    let limit = args
        .get("limit")
        .and_then(|v| v.as_u64())
        .unwrap_or(25)
        .clamp(1, 30) as usize;
    let offset = args.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize;

    match entity {
        "series" => {
            let all = list_series_manage(sync_conn)?;
            let filtered: Vec<_> = all
                .into_iter()
                .filter(|s| {
                    kw.as_ref()
                        .map(|k| {
                            s.name.to_ascii_lowercase().contains(k)
                                || s.members
                                    .iter()
                                    .any(|m| m.title.to_ascii_lowercase().contains(k))
                        })
                        .unwrap_or(true)
                })
                .collect();
            let total = filtered.len();
            let items: Vec<Value> = filtered
                .into_iter()
                .skip(offset)
                .take(limit)
                .map(|s| {
                    json!({
                        "id": s.id,
                        "name": s.name,
                        "memberCount": s.member_count,
                        "sampleMembers": s.members.iter().take(5).map(|m| json!({
                            "kind": m.kind,
                            "id": m.id,
                            "title": m.title,
                            "role": m.series_role,
                        })).collect::<Vec<_>>(),
                    })
                })
                .collect();
            Ok(json!({ "entity": "series", "total": total, "offset": offset, "shown": items.len(),
                "hasMore": offset.saturating_add(items.len()) < total, "items": items }))
        }
        "tag" => {
            let all = list_tags_manage(sync_conn)?;
            let filtered: Vec<_> = all
                .into_iter()
                .filter(|t| {
                    kw.as_ref()
                        .map(|k| t.name.to_ascii_lowercase().contains(k))
                        .unwrap_or(true)
                })
                .collect();
            let total = filtered.len();
            let items: Vec<Value> = filtered
                .into_iter()
                .skip(offset)
                .take(limit)
                .map(|t| {
                    json!({
                        "id": t.id,
                        "name": t.name,
                        "usageCount": t.usage_count,
                        "blocked": t.blocked,
                    })
                })
                .collect();
            Ok(json!({ "entity": "tag", "total": total, "offset": offset, "shown": items.len(),
                "hasMore": offset.saturating_add(items.len()) < total, "items": items }))
        }
        "character" => {
            let all = list_characters_manage(sync_conn)?;
            let filtered: Vec<_> = all
                .into_iter()
                .filter(|c| {
                    kw.as_ref()
                        .map(|k| {
                            c.name.to_ascii_lowercase().contains(k)
                                || c.name_cn.to_ascii_lowercase().contains(k)
                                || c.works
                                    .iter()
                                    .any(|w| w.title.to_ascii_lowercase().contains(k))
                        })
                        .unwrap_or(true)
                })
                .collect();
            let total = filtered.len();
            let work_limit = args
                .get("work_limit")
                .and_then(|v| v.as_u64())
                .unwrap_or(10)
                .clamp(1, 30) as usize;
            let relation_limit = args
                .get("relation_limit")
                .and_then(|v| v.as_u64())
                .unwrap_or(15)
                .clamp(1, 50) as usize;
            let items: Vec<Value> = filtered
                .into_iter()
                .skip(offset)
                .take(limit)
                .map(|c| {
                    json!({
                        "id": c.id,
                        "name": c.name,
                        "nameCn": c.name_cn,
                        "bgmId": c.bgm_id,
                        "hasImage": !c.image_url.trim().is_empty(),
                        "hasSummary": !c.summary.trim().is_empty(),
                        "hasReview": !c.review.trim().is_empty(),
                        "tags": c.tags,
                        "workTotal": c.works.len(),
                        "relationTotal": c.relations.len(),
                        "works": c.works.iter().take(work_limit).map(|w| json!({
                            "animeId": w.anime_id,
                            "title": w.title,
                            "role": w.role_name,
                            "voiceActor": w.voice_actor,
                        })).collect::<Vec<_>>(),
                        "relations": c.relations.iter().take(relation_limit).map(|r| json!({
                            "id": r.id,
                            "source": r.source_name,
                            "target": r.target_name,
                            "type": r.relation_type,
                        })).collect::<Vec<_>>(),
                    })
                })
                .collect();
            Ok(json!({ "entity": "character", "total": total, "offset": offset, "shown": items.len(),
                "hasMore": offset.saturating_add(items.len()) < total, "items": items }))
        }
        other => Err(StudioError::InvalidInput(format!(
            "不支持的分类实体类型 `{other}`，仅支持 series / tag / character"
        ))),
    }
}

pub fn exec_run_library_diagnostics(sync_conn: &Connection, args: &Value) -> Result<Value> {
    let scope = args.get("scope").and_then(|v| v.as_str()).unwrap_or("all");
    let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
    let mut out = json!({ "scope": scope, "offset": offset, "note": "候选为分页结果，处理范围以本页条目为准。" });

    if scope == "all" || scope == "series" {
        let groups = query_series_suggestions(sync_conn)?;
        out["seriesSuggestionsTotal"] = json!(groups.len());
        out["seriesSuggestions"] = json!(groups
            .into_iter()
            .skip(offset)
            .take(12)
            .map(|g| json!({
                "suggestedName": g.series_name,
                "reason": g.reason,
                "existingSeries": g.existing_series,
                "members": g.members.into_iter().map(|m| json!({
                    "kind": m.kind,
                    "id": m.id,
                    "title": m.title,
                    "currentSeries": m.current_series_name,
                })).collect::<Vec<_>>(),
            }))
            .collect::<Vec<_>>());
    }

    if scope == "all" || scope == "duplicates" {
        let dups = query_duplicate_groups(sync_conn)?;
        out["duplicateGroupsTotal"] = json!(dups.len());
        out["duplicateGroups"] = json!(dups
            .into_iter()
            .skip(offset)
            .take(10)
            .map(|g| {
                let titles: Vec<String> = g.candidates.iter().map(|c| c.title.clone()).collect();
                let similarity = if titles.len() >= 2 {
                    let mut max_sim = 0.0f64;
                    for i in 0..titles.len() {
                        for j in (i + 1)..titles.len() {
                            let s = title_similarity(&titles[i], &titles[j]);
                            if s > max_sim {
                                max_sim = s;
                            }
                        }
                    }
                    ((max_sim * 100.0).round()) / 100.0
                } else {
                    1.0
                };
                let candidates: Vec<Value> = g.candidates.into_iter().map(|c| {
                    let (orig, rel_date, series_name, ext_src, ext_id, tags_count, has_memo) =
                        load_duplicate_candidate_meta(sync_conn, &g.kind, c.id);
                    json!({
                        "id": c.id,
                        "title": c.title,
                        "originalTitle": orig,
                        "releaseDate": rel_date,
                        "seriesName": series_name,
                        "externalSource": ext_src,
                        "externalId": ext_id,
                        "tagsCount": tags_count,
                        "hasMemo": has_memo,
                        "status": c.status,
                        "progress": format_progress_for_ai(&g.kind, c.progress_primary, c.progress_total),
                        "score": c.rating.map(|r| (r as f64) / 10.0),
                    })
                }).collect();
                let same_date = {
                    let dates: HashSet<&str> = candidates
                        .iter()
                        .filter_map(|c| c["releaseDate"].as_str().map(str::trim).filter(|s| !s.is_empty()))
                        .collect();
                    dates.len() == 1 && candidates.iter().filter(|c| !c["releaseDate"].as_str().unwrap_or("").trim().is_empty()).count() >= 2
                };
                let same_progress = {
                    let progs: HashSet<&str> = candidates.iter().filter_map(|c| c["progress"].as_str()).collect();
                    progs.len() == 1
                };
                json!({
                    "kind": g.kind,
                    "reason": g.reason,
                    "similarity": similarity,
                    "evidence": {
                        "sameReleaseDate": same_date,
                        "sameProgress": same_progress,
                    },
                    "suggestedKeepId": g.recommended_keep_id,
                    "candidates": candidates,
                })
            })
            .collect::<Vec<_>>());
    }

    if scope == "all" || scope == "unused_tags" {
        let tags = list_tags_manage(sync_conn)?;
        let unused: Vec<Value> = tags
            .iter()
            .filter(|t| t.usage_count == 0)
            .skip(offset)
            .take(30)
            .map(|t| json!({ "id": t.id, "name": t.name }))
            .collect();
        let total_unused = tags.iter().filter(|t| t.usage_count == 0).count();
        out["unusedTagsTotal"] = json!(total_unused);
        out["unusedTagsSample"] = json!(unused);
    }

    Ok(out)
}

fn load_duplicate_candidate_meta(
    sync_conn: &Connection,
    kind: &str,
    id: i64,
) -> (String, String, Option<String>, Option<String>, Option<String>, i64, bool) {
    match kind {
        "game" => {
            if let Ok((orig, date, bgm, vndb, tags_raw)) = sync_conn.query_row(
                "SELECT COALESCE(originalTitle, ''), COALESCE(releaseDate, ''), bangumiId, vndbId, COALESCE(tags, '')
                 FROM games WHERE id = ?1",
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, Option<i64>>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, String>(4)?,
                    ))
                },
            ) {
                let vndb_clean = clean_opt_str(vndb);
                let bgm_clean = bgm.filter(|&b| b > 0);
                let (es, eid) = if let Some(v) = vndb_clean {
                    (Some("vndb".into()), Some(v))
                } else if let Some(b) = bgm_clean {
                    (Some("bangumi".into()), Some(b.to_string()))
                } else {
                    (None, None)
                };
                let tcnt = tags_raw
                    .split([',', '，', ';'])
                    .filter(|s| !s.trim().is_empty())
                    .count() as i64;
                return (orig, date, None, es, eid, tcnt, false);
            }
        }
        "audio" => {
            if let Ok((orig, date, code, tags_raw)) = sync_conn.query_row(
                "SELECT COALESCE(originalTitle, ''), COALESCE(releaseDate, ''), COALESCE(workCode, ''), COALESCE(tags, '')
                 FROM audio_works WHERE id = ?1",
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, String>(3)?,
                    ))
                },
            ) {
                let clean = (!is_blank_or_null_field(&code)).then(|| code.trim().to_string());
                let tcnt = tags_raw
                    .split([',', '，', ';'])
                    .filter(|s| !s.trim().is_empty())
                    .count() as i64;
                return (orig, date, None, clean.as_ref().map(|_| "dlsite".into()), clean, tcnt, false);
            }
        }
        _ => {
            if let Ok((orig, date, sname, es, eid, memo)) = sync_conn.query_row(
                "SELECT COALESCE(a.originalTitle, ''), COALESCE(a.airDate, ''), s.name,
                        a.externalSource, a.externalId, COALESCE(a.memo, '')
                 FROM animes a
                 LEFT JOIN series s ON s.id = a.seriesId
                 WHERE a.id = ?1",
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, String>(1)?,
                        r.get::<_, Option<String>>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, Option<String>>(4)?,
                        r.get::<_, String>(5)?,
                    ))
                },
            ) {
                let tcnt: i64 = sync_conn
                    .query_row("SELECT COUNT(*) FROM anime_tags WHERE animeId = ?1", [id], |r| r.get(0))
                    .unwrap_or(0);
                return (
                    orig,
                    date,
                    sname,
                    clean_opt_str(es),
                    clean_opt_str(eid),
                    tcnt,
                    !memo.trim().is_empty(),
                );
            }
        }
    }
    (String::new(), String::new(), None, None, None, 0, false)
}

fn to_halfwidth_char(ch: char) -> char {
    match ch {
        '\u{FF01}'..='\u{FF5E}' => char::from_u32(ch as u32 - 0xFEE0).unwrap_or(ch),
        '\u{3000}' => ' ',
        '’' | '′' => '\'',
        _ => ch,
    }
}

fn normalized_title(title: &str) -> String {
    title
        .chars()
        .map(to_halfwidth_char)
        .filter(|ch| ch.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn strip_bracket_annotations(title: &str) -> String {
    let mut out = String::with_capacity(title.len());
    let mut depth = 0usize;
    let mut bracket_buf = String::new();
    let flush_bracket = |buf: &str, out: &mut String| {
        let low = buf.to_ascii_lowercase();
        let keep_variant = low.contains("剧场版")
            || low.contains("劇場版")
            || low.contains("映画")
            || low.contains("ova")
            || low.contains("oad")
            || low.contains("lite")
            || low.contains("总集篇")
            || low.contains("総集編")
            || low.contains("特别篇")
            || low.contains("特別編")
            || low.contains("番外")
            || low.contains("外传")
            || low.contains("外伝")
            || low.contains("ver.")
            || low.contains("β");
        if keep_variant {
            out.push(' ');
            out.push_str(buf);
            out.push(' ');
        }
    };
    for ch in title.chars() {
        let hw = to_halfwidth_char(ch);
        if matches!(hw, '(' | '[' | '{') || matches!(ch, '（' | '【' | '「' | '『') {
            if depth == 0 {
                bracket_buf.clear();
            } else {
                bracket_buf.push(ch);
            }
            depth += 1;
        } else if matches!(hw, ')' | ']' | '}') || matches!(ch, '）' | '】' | '」' | '』') {
            if depth > 0 {
                depth -= 1;
                if depth == 0 {
                    flush_bracket(&bracket_buf, &mut out);
                    bracket_buf.clear();
                } else {
                    bracket_buf.push(ch);
                }
            }
        } else if depth > 0 {
            bracket_buf.push(ch);
        } else {
            out.push(ch);
        }
    }
    if depth > 0 && !bracket_buf.is_empty() {
        out.push_str(&bracket_buf);
    }
    let trimmed = out.trim();
    if trimmed.is_empty() {
        title.to_string()
    } else {
        trimmed.to_string()
    }
}

fn is_clean_subtitle_prefix(short_raw: &str, long_raw: &str) -> bool {
    let short_norm = normalized_title(short_raw);
    let long_norm = normalized_title(long_raw);
    let short_len = short_norm.chars().count();
    let long_len = long_norm.chars().count();
    if short_len < 3 || long_len <= short_len || !long_norm.starts_with(&short_norm) {
        return false;
    }
    // 检查原长标题在消费完 short_norm 的所有字母数字字符后，紧跟的是否是空白或副标题分隔符
    let mut matched = 0usize;
    let short_chars: Vec<char> = short_norm.chars().collect();
    let mut chars_iter = long_raw.chars().peekable();
    while let Some(ch) = chars_iter.next() {
        let hw = to_halfwidth_char(ch);
        if hw.is_alphanumeric() {
            for lc in hw.to_lowercase() {
                if matched < short_chars.len() && lc == short_chars[matched] {
                    matched += 1;
                }
            }
            if matched == short_chars.len() {
                break;
            }
        }
    }
    if matched != short_chars.len() {
        return false;
    }
    match chars_iter.peek() {
        Some(&next_ch) => {
            let hw = to_halfwidth_char(next_ch);
            hw.is_whitespace()
                || matches!(
                    next_ch,
                    '～' | '~' | '—' | '―' | '－' | '-' | ':' | '：' | '·' | '・' | '！' | '!' | '?' | '？' | '/' | '／' | '、' | '。'
                )
        }
        None => false,
    }
}

fn extract_ascii_anchor_tokens(text: &str) -> HashSet<String> {
    let mut out = HashSet::new();
    let mut cur = String::new();
    for ch in text.chars() {
        let hw = to_halfwidth_char(ch);
        if hw.is_ascii_alphanumeric() {
            cur.push(hw.to_ascii_lowercase());
        } else {
            if cur.len() >= 4 && !cur.chars().all(|c| c.is_ascii_digit()) {
                out.insert(std::mem::take(&mut cur));
            } else {
                cur.clear();
            }
        }
    }
    if cur.len() >= 4 && !cur.chars().all(|c| c.is_ascii_digit()) {
        out.insert(cur);
    }
    out
}

fn has_shared_ascii_anchor(a: &str, b: &str) -> bool {
    let ta = extract_ascii_anchor_tokens(a);
    if ta.is_empty() {
        return false;
    }
    let tb = extract_ascii_anchor_tokens(b);
    ta.intersection(&tb).any(|tok| {
        !matches!(
            tok.as_str(),
            "season" | "part" | "movie" | "film" | "special" | "final" | "episode" | "anime" | "book" | "manga" | "novel" | "edition" | "version" | "lite" | "mini"
        )
    })
}

fn count_trailing_primes(title: &str) -> usize {
    let trimmed = title.trim_end();
    let mut count = 0usize;
    for ch in trimmed.chars().rev() {
        let hw = to_halfwidth_char(ch);
        if hw == '\'' {
            count += 1;
        } else if ch == '″' || hw == '"' {
            count += 2;
        } else {
            break;
        }
    }
    count
}

fn title_similarity(a: &str, b: &str) -> f64 {
    let na: Vec<char> = normalized_title(a).chars().collect();
    let nb: Vec<char> = normalized_title(b).chars().collect();
    if na.is_empty() || nb.is_empty() {
        return 0.0;
    }
    if na == nb {
        return 1.0;
    }
    // 字符多重集重合度（兼容单字译名差异，如「重启」vs「重来」）
    let mut ca: HashMap<char, usize> = HashMap::new();
    let mut cb: HashMap<char, usize> = HashMap::new();
    for &c in &na {
        *ca.entry(c).or_insert(0) += 1;
    }
    for &c in &nb {
        *cb.entry(c).or_insert(0) += 1;
    }
    let char_inter: usize = ca
        .iter()
        .map(|(k, &v)| v.min(cb.get(k).copied().unwrap_or(0)))
        .sum();
    let char_dice = (2.0 * char_inter as f64) / ((na.len() + nb.len()) as f64);

    if na.len() < 2 || nb.len() < 2 {
        return char_dice;
    }
    let mut ba: HashMap<(char, char), usize> = HashMap::new();
    let mut bb: HashMap<(char, char), usize> = HashMap::new();
    for w in na.windows(2) {
        *ba.entry((w[0], w[1])).or_insert(0) += 1;
    }
    for w in nb.windows(2) {
        *bb.entry((w[0], w[1])).or_insert(0) += 1;
    }
    let bi_inter: usize = ba
        .iter()
        .map(|(k, &v)| v.min(bb.get(k).copied().unwrap_or(0)))
        .sum();
    let bi_dice = (2.0 * bi_inter as f64) / ((na.len() - 1 + nb.len() - 1) as f64);
    0.55 * bi_dice + 0.45 * char_dice
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct VariantFlags {
    movie: bool,
    ova: bool,
    lite: bool,
    recap: bool,
    special: bool,
    alt_version: bool,
}

impl VariantFlags {
    fn from_texts(texts: &[&str]) -> Self {
        let mut hay = String::new();
        for t in texts {
            for ch in t.chars() {
                hay.push(to_halfwidth_char(ch));
            }
            hay.push(' ');
        }
        let low = hay.to_ascii_lowercase();
        let has_word = |w: &str| {
            low.split(|c: char| !c.is_alphanumeric() && c != '.')
                .any(|tok| tok == w)
        };
        Self {
            movie: low.contains("剧场版")
                || low.contains("劇場版")
                || low.contains("映画")
                || low.contains("电影")
                || has_word("movie")
                || has_word("film"),
            ova: has_word("ova") || has_word("oad") || low.contains("ova") || low.contains("oad"),
            lite: has_word("lite")
                || has_word("mini")
                || low.contains("迷你")
                || low.contains("短片")
                || low.contains("ぷち")
                || has_word("chibi"),
            recap: low.contains("总集篇")
                || low.contains("総集編")
                || has_word("recap")
                || has_word("digest"),
            special: low.contains("特别篇")
                || low.contains("特別編")
                || low.contains("番外")
                || low.contains("外传")
                || low.contains("外伝")
                || has_word("special")
                || has_word("sp"),
            alt_version: low.contains("ver.β")
                || low.contains("ver.b")
                || low.contains("ver β")
                || low.contains("β")
                || has_word("beta")
                || has_word("remix")
                || low.contains("re:edit"),
        }
    }

    fn is_non_tv_format(&self) -> bool {
        self.movie || self.ova || self.lite || self.recap || self.special
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
struct SeasonSignature {
    season: Option<u8>,
    part: Option<u8>,
    primes: usize,
    is_final: bool,
    has_flat: bool,
    has_herz: bool,
    has_2wei: bool,
}

impl SeasonSignature {
    fn from_texts(title: &str, orig: &str) -> Self {
        let mut raw = String::new();
        for t in [title, orig] {
            for ch in t.chars() {
                raw.push(to_halfwidth_char(ch));
            }
            raw.push(' ');
        }
        let low = raw.to_ascii_lowercase();
        let season = if low.contains("第4季") || low.contains("第四季") || low.contains("season 4") || low.contains("4th") || low.contains("ⅳ") {
            Some(4)
        } else if low.contains("第3季") || low.contains("第三季") || low.contains("season 3") || low.contains("3rd") || low.contains("ⅲ") {
            Some(3)
        } else if low.contains("第2季") || low.contains("第二季") || low.contains("season 2") || low.contains("2nd") || low.contains("ⅱ") {
            Some(2)
        } else if low.contains("第1季") || low.contains("第一季") || low.contains("season 1") || low.contains("1st") {
            Some(1)
        } else {
            None
        };
        let part = if low.contains("第2部分") || low.contains("第二部分") || low.contains("part 2") || low.contains("part2") || low.contains("后半") || low.contains("後半") || low.contains("下半") || low.contains("cour 2") {
            Some(2)
        } else if low.contains("第3部分") || low.contains("part 3") {
            Some(3)
        } else {
            None
        };
        let is_final = low.contains("终章")
            || low.contains("終章")
            || low.contains("完结篇")
            || low.contains("完結編")
            || low.contains("final season")
            || title.trim_end().ends_with('完')
            || orig.trim_end().ends_with('完');
        let primes = count_trailing_primes(title).max(count_trailing_primes(orig));
        Self {
            season,
            part,
            primes,
            is_final,
            has_flat: title.contains('♭') || orig.contains('♭'),
            has_herz: low.contains("herz"),
            has_2wei: low.contains("2wei"),
        }
    }

    /// 返回 `(hard_conflict, soft_omission_conflict)`:
    /// - `hard_conflict`: 两侧均显式标注了不同季数、分部、尾撇号、终章或 ♭/Herz/2wei 修饰词；
    /// - `soft_omission_conflict`: 仅一侧写了第 N 季（N>=2），另一侧未写季数（常见于官方用副标题命名续作，如「辉夜大小姐 第三季」vs「辉夜大小姐 -究极浪漫-」）。
    fn conflict_kind(&self, other: &Self) -> (bool, bool) {
        if self.part != other.part
            || self.primes != other.primes
            || self.is_final != other.is_final
            || self.has_flat != other.has_flat
            || self.has_herz != other.has_herz
            || self.has_2wei != other.has_2wei
        {
            return (true, false);
        }
        let s1 = self.season.filter(|&s| s > 1);
        let s2 = other.season.filter(|&s| s > 1);
        match (s1, s2) {
            (Some(a), Some(b)) if a != b => (true, false),
            (Some(_), None) | (None, Some(_)) => (false, true),
            _ => (false, false),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum ParsedDate {
    Full(chrono::NaiveDate),
    YearMonth(i32, u32),
    Year(i32),
}

fn parse_release_date_normalized(raw: &str) -> Option<ParsedDate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    // 兼容历史库/JS 序列化出的 UTC ISO 时间戳（如 `2016-07-05T16:00:00.000Z` 实际为东八区 `2016-07-06` 零点）
    if trimmed.contains('T') && (trimmed.ends_with('Z') || trimmed.ends_with('z') || trimmed.ends_with("+00:00")) {
        if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(trimmed) {
            let utc = dt.with_timezone(&chrono::Utc);
            let shifted = utc + chrono::Duration::hours(8);
            return Some(ParsedDate::Full(shifted.date_naive()));
        }
    }
    let digits: String = trimmed
        .chars()
        .map(|c| if c.is_ascii_digit() { c } else { '-' })
        .collect();
    let parts: Vec<&str> = digits.split('-').filter(|s| !s.is_empty()).collect();
    if parts.is_empty() || parts[0].len() != 4 {
        return None;
    }
    let y: i32 = parts[0].parse().ok()?;
    let m: Option<u32> = parts.get(1).and_then(|s| s.parse().ok()).filter(|&m| (1..=12).contains(&m));
    let d: Option<u32> = parts.get(2).and_then(|s| s.parse().ok()).filter(|&d| (1..=31).contains(&d));
    match (m, d) {
        (Some(mm), Some(dd)) => chrono::NaiveDate::from_ymd_opt(y, mm, dd)
            .map(ParsedDate::Full)
            .or(Some(ParsedDate::YearMonth(y, mm))),
        (Some(mm), None) => Some(ParsedDate::YearMonth(y, mm)),
        _ => Some(ParsedDate::Year(y)),
    }
}

pub fn compare_dates(a: &str, b: &str) -> &'static str {
    let (Some(pa), Some(pb)) = (parse_release_date_normalized(a), parse_release_date_normalized(b)) else {
        return "unknown";
    };
    match (pa, pb) {
        (ParsedDate::Full(da), ParsedDate::Full(db)) => {
            let diff = (da - db).num_days().abs();
            // ±1 天容差：同时覆盖 UTC+8/JST+9 跨日与日本深夜 25:00/26:00 放送导致的跨日/跨月/跨年偏差
            if diff <= 1 {
                "exact"
            } else if diff <= 31 {
                "month"
            } else if diff <= 365 {
                "year"
            } else {
                "mismatch"
            }
        }
        (ParsedDate::Full(da), ParsedDate::YearMonth(yb, mb))
        | (ParsedDate::YearMonth(yb, mb), ParsedDate::Full(da)) => {
            if da.year() == yb && da.month() == mb {
                "month"
            } else if da.year() == yb {
                "year"
            } else {
                "mismatch"
            }
        }
        (ParsedDate::YearMonth(ya, ma), ParsedDate::YearMonth(yb, mb)) => {
            if ya == yb && ma == mb {
                "month"
            } else if ya == yb {
                "year"
            } else {
                "mismatch"
            }
        }
        (pa, pb) => {
            let ya = match pa {
                ParsedDate::Full(d) => d.year(),
                ParsedDate::YearMonth(y, _) | ParsedDate::Year(y) => y,
            };
            let yb = match pb {
                ParsedDate::Full(d) => d.year(),
                ParsedDate::YearMonth(y, _) | ParsedDate::Year(y) => y,
            };
            if ya == yb {
                "year"
            } else {
                "mismatch"
            }
        }
    }
}

#[derive(Debug, Clone)]
pub struct LocalWorkVerifyTarget {
    pub work_id: i64,
    pub kind: String,
    pub title: String,
    pub original_title: String,
    pub release_date: String,
    pub total_episodes: i64,
    pub media_format: Option<String>,
    pub tags: Vec<String>,
    pub existing_bangumi_id: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ScoredCandidate {
    pub hit: RemoteCatalogHit,
    pub confidence: f64,
    pub evidence: Value,
    pub reason: String,
    pub blocked_exact: bool,
}

pub fn score_bangumi_candidate(
    target: &LocalWorkVerifyTarget,
    hit: &RemoteCatalogHit,
) -> ScoredCandidate {
    let local_title_clean = strip_bracket_annotations(&target.title);
    let local_orig_clean = strip_bracket_annotations(&target.original_title);
    let cand_title_clean = strip_bracket_annotations(&hit.title);
    let cand_orig_clean = strip_bracket_annotations(&hit.original_title);

    let local_title_norm = normalized_title(&local_title_clean);
    let local_orig_norm = normalized_title(&local_orig_clean);
    let cand_title_norm = normalized_title(&cand_title_clean);
    let cand_orig_norm = normalized_title(&cand_orig_clean);

    let local_primes = count_trailing_primes(&local_title_clean).max(count_trailing_primes(&local_orig_clean));
    let cand_primes = count_trailing_primes(&cand_title_clean).max(count_trailing_primes(&cand_orig_clean));
    let primes_eq = local_primes == cand_primes;

    let mut local_variant_texts: Vec<&str> = vec![&target.title, &target.original_title];
    if let Some(ref mf) = target.media_format {
        local_variant_texts.push(mf);
    }
    for tg in &target.tags {
        local_variant_texts.push(tg);
    }
    let local_flags = VariantFlags::from_texts(&local_variant_texts);
    let cand_flags = VariantFlags::from_texts(&[&hit.title, &hit.original_title]);
    let variant_conflict = local_flags != cand_flags;

    let local_season = SeasonSignature::from_texts(&target.title, &target.original_title);
    let cand_season = SeasonSignature::from_texts(&hit.title, &hit.original_title);

    let has_local_orig = !local_orig_norm.is_empty();
    let has_cand_orig = !cand_orig_norm.is_empty();

    let original_eq = has_local_orig && has_cand_orig && local_orig_norm == cand_orig_norm && primes_eq;
    let title_eq = !local_title_norm.is_empty()
        && primes_eq
        && (local_title_norm == cand_title_norm || local_title_norm == cand_orig_norm);

    let date_rel = compare_dates(&target.release_date, &hit.release_date);
    let date_eq = date_rel == "exact";

    // 剧场版 / OVA / 短片 / 总集篇禁止按集数加分
    let allow_ep_scoring = !local_flags.is_non_tv_format() && !cand_flags.is_non_tv_format();
    let (episodes_eq, ep_rel) = if allow_ep_scoring && target.total_episodes > 0 && hit.total_episodes > 0 {
        if target.total_episodes == hit.total_episodes {
            (true, "exact")
        } else if (target.total_episodes - hit.total_episodes).abs() <= 1 {
            (false, "near")
        } else {
            (false, "mismatch")
        }
    } else {
        (false, "unknown")
    };

    // 检测去括号后严格前缀加短续作后缀（如「中二病也要谈恋爱」vs「中二病也要谈恋爱 恋」），
    // 排除纯数字年份后缀，且当日期+集数双精确一致或原名精确一致时不误判为季别冲突
    let strict_prefix_extra = !original_eq
        && !local_title_norm.is_empty()
        && !cand_title_norm.is_empty()
        && local_title_norm != cand_title_norm
        && !(date_eq && episodes_eq)
        && ((cand_title_norm.starts_with(&local_title_norm)
            && {
                let tail = &cand_title_norm[local_title_norm.len()..];
                let tc = tail.chars().count();
                tc <= 4 && !tail.chars().all(|c| c.is_ascii_digit())
            })
            || (local_title_norm.starts_with(&cand_title_norm)
                && {
                    let tail = &local_title_norm[cand_title_norm.len()..];
                    let tc = tail.chars().count();
                    tc <= 4 && !tail.chars().all(|c| c.is_ascii_digit())
                }));

    let (hard_season_conflict_raw, soft_season_omission) = local_season.conflict_kind(&cand_season);
    let hard_season_conflict = hard_season_conflict_raw || strict_prefix_extra;
    // 单侧省略季数（如「辉夜大小姐 第三季」vs「辉夜大小姐 -究极浪漫-」）：
    // 若原名一致或开播日+集数双中，证明就是同一季，不作冲突；否则作为轻度降权交人工确认
    let active_soft_season = soft_season_omission && !original_eq && !(date_eq && episodes_eq);
    let season_conflict = hard_season_conflict || active_soft_season;

    let is_safe_substr = |a: &str, b: &str| -> bool {
        let la = a.chars().count();
        let lb = b.chars().count();
        let min_l = la.min(lb);
        let max_l = la.max(lb);
        min_l >= 4 && (min_l as f64 / max_l as f64) >= 0.65 && (a.contains(b) || b.contains(a))
    };

    let clean_subtitle_prefix = !hard_season_conflict
        && (is_clean_subtitle_prefix(&local_title_clean, &cand_title_clean)
            || is_clean_subtitle_prefix(&cand_title_clean, &local_title_clean)
            || is_clean_subtitle_prefix(&local_title_clean, &cand_orig_clean));

    let shared_ascii_anchor = has_shared_ascii_anchor(&local_title_clean, &cand_title_clean)
        || has_shared_ascii_anchor(&local_title_clean, &cand_orig_clean)
        || (has_local_orig && has_shared_ascii_anchor(&local_orig_clean, &cand_orig_clean));

    let orig_sim = if has_local_orig && has_cand_orig {
        title_similarity(&local_orig_clean, &cand_orig_clean)
    } else {
        0.0
    };
    let title_sim = title_similarity(&local_title_clean, &cand_title_clean)
        .max(title_similarity(&local_title_clean, &cand_orig_clean));

    let mut score = 0.0f64;
    let mut reasons = Vec::<String>::new();

    if has_local_orig && has_cand_orig {
        if original_eq {
            score += 0.65;
            reasons.push("原名精确一致".into());
        } else if is_safe_substr(&local_orig_norm, &cand_orig_norm) {
            score += 0.28;
            reasons.push("原名子串包含".into());
        } else if orig_sim >= 0.78 {
            score += 0.32 * orig_sim;
            reasons.push("原名高相似".into());
        }

        if title_eq {
            score += 0.25;
            reasons.push("标题精确一致".into());
        } else if clean_subtitle_prefix {
            score += 0.22;
            reasons.push("主标题前缀匹配".into());
        } else if is_safe_substr(&local_title_norm, &cand_title_norm) {
            score += 0.15;
            reasons.push("标题子串包含".into());
        } else if title_sim >= 0.78 {
            score += 0.20 * title_sim;
            reasons.push("标题高相似".into());
        }

        match date_rel {
            "exact" => {
                score += 0.18;
                reasons.push("开播/发售日一致".into());
            }
            "month" => {
                score += 0.12;
                reasons.push("同年同月".into());
            }
            "year" => {
                score += 0.06;
                reasons.push("同年份".into());
            }
            "mismatch" => {
                score -= 0.15;
                reasons.push("日期不符".into());
            }
            _ => {}
        }

        match ep_rel {
            "exact" => {
                score += 0.14;
                reasons.push("集数一致".into());
            }
            "near" => {
                score += 0.05;
            }
            "mismatch" => {
                score -= 0.12;
                reasons.push("集数不符".into());
            }
            _ => {}
        }
    } else {
        // 原名缺失降级打分模式：避免因本地原名为空或简称/跨语种译名误判 no_match
        if title_eq {
            score += 0.55;
            reasons.push("标题精确一致(无本地原名)".into());
        } else if clean_subtitle_prefix {
            score += 0.50;
            reasons.push("主标题与副标题前缀吻合".into());
        } else if is_safe_substr(&local_title_norm, &cand_title_norm) && !strict_prefix_extra {
            score += 0.34;
            reasons.push("标题子串包含".into());
        } else if title_sim >= 0.74 {
            score += 0.46 * title_sim;
            reasons.push(format!("标题高相似({:.2})", title_sim));
        } else if shared_ascii_anchor && date_eq && episodes_eq {
            score += 0.52;
            reasons.push("英文/副标题词根与日期集数一致".into());
        }

        match date_rel {
            "exact" => {
                score += 0.26;
                reasons.push("开播/发售日一致".into());
            }
            "month" => {
                score += 0.16;
                reasons.push("同年同月".into());
            }
            "year" => {
                score += 0.08;
                reasons.push("同年份".into());
            }
            "mismatch" => {
                score -= 0.15;
                reasons.push("日期不符".into());
            }
            _ => {
                if title_eq && !variant_conflict && !season_conflict {
                    score += 0.17;
                    reasons.push("缺少发售日，仅标题命中，需人工确认".into());
                }
            }
        }

        match ep_rel {
            "exact" => {
                score += 0.16;
                reasons.push("集数一致".into());
            }
            "near" => {
                score += 0.06;
            }
            "mismatch" => {
                score -= 0.15;
                reasons.push("集数不符".into());
            }
            _ => {}
        }
    }

    // 原名精确一致独立高置信通道：日文原名为主键，无形态/显式季别/日期/集数冲突时，不受中文译名差异拖累
    if original_eq
        && !variant_conflict
        && !hard_season_conflict
        && date_rel != "mismatch"
        && ep_rel != "mismatch"
    {
        let floor = if date_eq || episodes_eq {
            0.95
        } else if date_rel == "month" || date_rel == "year" {
            0.92
        } else {
            0.91
        };
        if score < floor {
            score = floor;
        }
    }

    // 跨语种/副标题兜底：无形态与硬季别冲突且开播日+集数双精确命中时，至少给到高疑似区间，避免被误杀为 0.40 no_match
    if !variant_conflict && !hard_season_conflict && date_eq && episodes_eq {
        if (clean_subtitle_prefix || shared_ascii_anchor || title_sim >= 0.48) && score < 0.92 {
            score = 0.92;
        } else if score < 0.78 {
            score = 0.78;
            reasons.push("开播日与集数双精确一致(译名差异待复核)".into());
        }
    }

    let mut blocked_exact = false;
    if variant_conflict {
        score -= 0.25;
        blocked_exact = true;
        reasons.push("形态/版本修饰词不一致(如剧场版/OVA/Lite/Ver.β)".into());
    }
    if hard_season_conflict {
        score -= 0.25;
        blocked_exact = true;
        reasons.push("季别/分部/后缀标记不一致".into());
    } else if active_soft_season {
        score -= 0.10;
        blocked_exact = true;
        reasons.push("单侧标注季数，需结合日期/集数复核".into());
    }

    // 若本地已绑定同一外部 ID，直接认定精确匹配
    if target
        .existing_bangumi_id
        .as_deref()
        .is_some_and(|eid| eid == hit.external_id)
    {
        score = 1.0;
        blocked_exact = false;
        reasons.push("已绑定外部ID精确命中".into());
    }

    // D6：显式区分每一项校验是「一致 / 不一致 / 本地缺失 / 外部源缺失」，不再隐藏 date_eq=false 或 episodes_eq=false
    let local_date_missing = is_blank_or_null_field(&target.release_date);
    let remote_date_missing = is_blank_or_null_field(&hit.release_date);
    let date_status = if date_eq {
        "match"
    } else if date_rel == "month" || date_rel == "year" {
        date_rel
    } else if remote_date_missing && local_date_missing {
        "missing_both"
    } else if remote_date_missing {
        "missing_remote"
    } else if local_date_missing {
        "missing_local"
    } else {
        "mismatch"
    };
    let local_ep_missing = target.total_episodes <= 0;
    let remote_ep_missing = hit.total_episodes <= 0;
    let episodes_status = if episodes_eq {
        "match"
    } else if ep_rel == "near" {
        "near"
    } else if remote_ep_missing && local_ep_missing {
        "missing_both"
    } else if remote_ep_missing {
        "missing_remote"
    } else if local_ep_missing {
        "missing_local"
    } else {
        "mismatch"
    };

    if !date_eq {
        let note = match date_status {
            "missing_remote" => "日期未核对(外部源缺少日期)",
            "missing_local" => "日期未核对(本地缺少日期)",
            "missing_both" => "日期未核对(双侧缺少日期)",
            "month" | "year" => "日期仅部分一致(非精确同日)",
            _ => "日期不符(date_eq=false)",
        };
        if !reasons.iter().any(|r| r.contains("日期")) {
            reasons.push(note.into());
        }
        if date_status == "mismatch" {
            score = (score - 0.15).min(0.84);
        }
    }
    if !episodes_eq {
        let note = match episodes_status {
            "missing_remote" => "集数未核对(外部源缺少集数)",
            "missing_local" => "集数未核对(本地总集数未知)",
            "missing_both" => "集数未核对(双侧缺少集数)",
            "near" => "集数接近但不完全一致",
            _ => "集数不符(episodes_eq=false)",
        };
        if !reasons.iter().any(|r| r.contains("集数")) {
            reasons.push(note.into());
        }
        if episodes_status == "mismatch" {
            score = (score - 0.12).min(0.84);
        }
    }

    let confidence = (score.clamp(0.0, 1.0) * 100.0).round() / 100.0;
    let evidence = json!({
        "title_eq": title_eq,
        "original_eq": original_eq,
        "date_eq": date_eq,
        "date_status": date_status,
        "episodes_eq": episodes_eq,
        "episodes_status": episodes_status,
        "variant_conflict": variant_conflict,
        "season_conflict": season_conflict,
    });
    let reason = if reasons.is_empty() {
        "未满足高置信匹配条件".into()
    } else {
        reasons.join("；")
    };

    ScoredCandidate {
        hit: hit.clone(),
        confidence,
        evidence,
        reason,
        blocked_exact,
    }
}

pub fn evaluate_bangumi_matches(
    target: &LocalWorkVerifyTarget,
    hits: Vec<RemoteCatalogHit>,
    max_candidates: usize,
) -> Value {
    evaluate_bangumi_matches_with_mode(target, hits, max_candidates, false)
}

pub fn evaluate_bangumi_matches_with_mode(
    target: &LocalWorkVerifyTarget,
    hits: Vec<RemoteCatalogHit>,
    max_candidates: usize,
    compact: bool,
) -> Value {
    let mut scored: Vec<ScoredCandidate> = hits
        .into_iter()
        .filter(|h| h.kind == target.kind)
        .map(|h| score_bangumi_candidate(target, &h))
        .collect();
    scored.sort_by(|a, b| {
        b.confidence
            .partial_cmp(&a.confidence)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.blocked_exact.cmp(&b.blocked_exact))
            .then_with(|| a.hit.external_id.cmp(&b.hit.external_id))
    });

    let cand_to_json = |s: &ScoredCandidate| {
        let cov = (!s.hit.cover_url.trim().is_empty()).then_some(s.hit.cover_url.as_str());
        json!({
            "externalId": s.hit.external_id,
            "title": s.hit.title,
            "originalTitle": s.hit.original_title,
            "releaseDate": s.hit.release_date,
            "totalEpisodes": (s.hit.total_episodes > 0).then_some(s.hit.total_episodes),
            "coverUrl": cov,
            "cover_url": cov,
            "confidence": s.confidence,
        })
    };
    let cand_to_compact = |s: &ScoredCandidate| {
        let mut obj = serde_json::Map::new();
        obj.insert("externalId".into(), json!(s.hit.external_id));
        obj.insert("title".into(), json!(s.hit.title));
        if !s.hit.original_title.trim().is_empty() {
            obj.insert("originalTitle".into(), json!(s.hit.original_title));
        }
        if !s.hit.release_date.trim().is_empty() {
            obj.insert("date".into(), json!(s.hit.release_date));
        }
        if s.hit.total_episodes > 0 {
            obj.insert("ep".into(), json!(s.hit.total_episodes));
        }
        obj.insert("c".into(), json!(s.confidence));
        Value::Object(obj)
    };

    let Some(best) = scored.first() else {
        if compact {
            return json!({
                "work_id": target.work_id,
                "matchStatus": "no_match",
                "confidence": 0.0,
                "reason": "无同载体候选",
                "top": Value::Null,
            });
        }
        return json!({
            "work_id": target.work_id,
            "kind": target.kind,
            "title": target.title,
            "matchStatus": "no_match",
            "confidence": 0.0,
            "chosen": Value::Null,
            "candidates": [],
            "evidence": {
                "title_eq": false,
                "original_eq": false,
                "date_eq": false,
                "episodes_eq": false,
            },
            "reason": "线上未返回同载体候选条目",
        });
    };

    let tied_top = scored
        .get(1)
        .is_some_and(|second| (best.confidence - second.confidence).abs() <= 0.02);

    let (match_status, chosen, reason) = if best.confidence >= 0.90 && !best.blocked_exact && !tied_top {
        ("exact_match", Some(cand_to_json(best)), best.reason.clone())
    } else if best.confidence >= 0.70 {
        let mut r = best.reason.clone();
        if tied_top {
            r.push_str("；存在同分候选，已降级为需人工点选");
        }
        (
            "probable_match",
            if tied_top { None } else { Some(cand_to_json(best)) },
            r,
        )
    } else {
        ("no_match", None, best.reason.clone())
    };

    if compact {
        return match match_status {
            "exact_match" => {
                let mut obj = serde_json::Map::new();
                obj.insert("work_id".into(), json!(target.work_id));
                obj.insert("matchStatus".into(), json!("exact_match"));
                obj.insert("externalId".into(), json!(best.hit.external_id));
                if !best.hit.original_title.trim().is_empty() {
                    obj.insert("originalTitle".into(), json!(best.hit.original_title));
                }
                obj.insert("confidence".into(), json!(best.confidence));
                Value::Object(obj)
            }
            "probable_match" => {
                let cap = max_candidates.clamp(1, 2);
                let cands: Vec<Value> = scored.iter().take(cap).map(cand_to_compact).collect();
                json!({
                    "work_id": target.work_id,
                    "matchStatus": "probable_match",
                    "confidence": best.confidence,
                    "reason": reason,
                    "candidates": cands,
                })
            }
            _ => json!({
                "work_id": target.work_id,
                "matchStatus": "no_match",
                "confidence": best.confidence,
                "reason": reason,
                "top": cand_to_compact(best),
            }),
        };
    }

    // 精确匹配时仅保留 1 条命中精简上下文；probable/no_match 最多保留 max_candidates 条供点选
    let cap = if match_status == "exact_match" {
        1
    } else {
        max_candidates.clamp(1, 5)
    };
    let candidates: Vec<Value> = scored.iter().take(cap).map(cand_to_json).collect();

    json!({
        "work_id": target.work_id,
        "kind": target.kind,
        "title": target.title,
        "matchStatus": match_status,
        "confidence": best.confidence,
        "chosen": chosen,
        "candidates": candidates,
        "evidence": best.evidence,
        "reason": reason,
    })
}

async fn cached_bangumi_search(
    studio_data_dir: &Path,
    keyword: &str,
    kind: &str,
) -> Result<Vec<RemoteCatalogHit>> {
    let clean_kw = keyword.trim();
    if clean_kw.is_empty() {
        return Ok(Vec::new());
    }
    let key = (
        format!("{}::{kind}", studio_data_dir.to_string_lossy()),
        clean_kw.to_ascii_lowercase(),
    );
    if let Ok(cache) = BANGUMI_SEARCH_CACHE.lock() {
        if let Some((ts, hits)) = cache.get(&key) {
            if ts.elapsed() < BANGUMI_CACHE_TTL {
                return Ok(hits.clone());
            }
        }
    }

    let mut last_err = None;
    for attempt in 0..3 {
        match search_bangumi_via_server_first(studio_data_dir, clean_kw, kind).await {
            Ok(hits) => {
                if let Ok(mut cache) = BANGUMI_SEARCH_CACHE.lock() {
                    if cache.len() > 512 {
                        cache.retain(|_, (ts, _)| ts.elapsed() < BANGUMI_CACHE_TTL);
                    }
                    cache.insert(key, (Instant::now(), hits.clone()));
                }
                return Ok(hits);
            }
            Err(e) => {
                let msg = e.to_string();
                let retryable = msg.contains("429")
                    || msg.contains("timeout")
                    || msg.contains("timed out")
                    || msg.contains("502")
                    || msg.contains("503")
                    || msg.contains("504");
                last_err = Some(e);
                if retryable && attempt < 2 {
                    tokio::time::sleep(Duration::from_millis(350 * (attempt as u64 + 1))).await;
                    continue;
                }
                break;
            }
        }
    }
    Err(last_err.unwrap_or_else(|| StudioError::InvalidInput("Bangumi 检索失败".into())))
}

pub async fn verify_bangumi_batch(
    studio_data_dir: &Path,
    targets: Vec<LocalWorkVerifyTarget>,
    missing_ids: Vec<i64>,
    kind: &str,
    max_candidates: usize,
    compact: bool,
) -> (Value, Vec<Value>) {
    let sem = Arc::new(tokio::sync::Semaphore::new(BANGUMI_BATCH_CONCURRENCY));
    let mut join_set = tokio::task::JoinSet::new();

    for (idx, target) in targets.into_iter().enumerate() {
        let dir = studio_data_dir.to_path_buf();
        let permit_sem = sem.clone();
        join_set.spawn(async move {
            let _permit = permit_sem.acquire_owned().await;
            let res: Result<Vec<RemoteCatalogHit>> = async {
                let mut hits = cached_bangumi_search(&dir, &target.title, &target.kind).await?;
                let preliminary = evaluate_bangumi_matches_with_mode(&target, hits.clone(), max_candidates, false);
                let need_orig = preliminary["matchStatus"] != "exact_match"
                    && !target.original_title.trim().is_empty()
                    && normalized_title(&target.title) != normalized_title(&target.original_title);
                if need_orig {
                    if let Ok(more) = cached_bangumi_search(&dir, &target.original_title, &target.kind).await {
                        let mut seen: HashSet<String> = hits.iter().map(|h| h.external_id.clone()).collect();
                        hits.extend(more.into_iter().filter(|h| seen.insert(h.external_id.clone())));
                    }
                }
                Ok(hits)
            }
            .await;

            let (item_json, exact_binding) = match res {
                Ok(hits) => {
                    let mut full = evaluate_bangumi_matches_with_mode(&target, hits.clone(), max_candidates, false);
                    // D2：compact=false 时按需补齐远端完整元数据（tags / coverUrl / coverThumbUrl / summary / studio / mediaFormat / characters），缺失显式返回 null
                    if !compact {
                        if let Some(chosen_id) = full
                            .get("chosen")
                            .and_then(|c| c.get("externalId"))
                            .and_then(Value::as_str)
                            .map(str::to_string)
                        {
                            let detail_opt = crate::cloud::fetch_bangumi_subject_detail_via_server_first(&dir, &chosen_id)
                                .await
                                .ok();
                            let chars_opt = chosen_id.parse::<i64>().ok();
                            let char_summary: Option<Vec<Value>> = if let Some(sid_num) = chars_opt {
                                crate::cloud::fetch_work_characters_via_server_first(&dir, sid_num)
                                    .await
                                    .ok()
                                    .map(|list| {
                                        list.into_iter()
                                            .take(12)
                                            .map(|c| {
                                                json!({
                                                    "bgmId": c.bgm_id,
                                                    "name": if c.name_cn.is_empty() { c.name } else { c.name_cn },
                                                    "role": c.role_name,
                                                    "voiceActor": c.voice_actor,
                                                })
                                            })
                                            .collect()
                                    })
                            } else {
                                None
                            };
                            if let Some(chosen_obj) = full.get_mut("chosen").and_then(Value::as_object_mut) {
                                let existing_cov_non_empty = chosen_obj
                                    .get("coverUrl")
                                    .and_then(Value::as_str)
                                    .is_some_and(|s| !s.trim().is_empty());
                                if let Some(det) = detail_opt {
                                    if det.cover_url.as_deref().is_some_and(|s| !s.trim().is_empty())
                                        || !existing_cov_non_empty
                                    {
                                        chosen_obj.insert("coverUrl".into(), json!(det.cover_url));
                                        chosen_obj.insert("cover_url".into(), json!(det.cover_url));
                                    }
                                    chosen_obj.insert("coverThumbUrl".into(), json!(det.cover_thumb_url));
                                    chosen_obj.insert(
                                        "tags".into(),
                                        if det.tags.is_empty() { Value::Null } else { json!(det.tags) },
                                    );
                                    chosen_obj.insert("summary".into(), json!(det.summary));
                                    chosen_obj.insert("studio".into(), json!(det.studio));
                                    chosen_obj.insert("mediaFormat".into(), json!(det.media_format));
                                    if det.release_date.is_some()
                                        && chosen_obj
                                            .get("releaseDate")
                                            .and_then(Value::as_str)
                                            .map(is_blank_or_null_field)
                                            .unwrap_or(true)
                                    {
                                        chosen_obj.insert("releaseDate".into(), json!(det.release_date));
                                    }
                                    if det.total_episodes.is_some()
                                        && chosen_obj.get("totalEpisodes").and_then(Value::as_i64).unwrap_or(0) <= 0
                                    {
                                        chosen_obj.insert("totalEpisodes".into(), json!(det.total_episodes));
                                    }
                                } else {
                                    if !existing_cov_non_empty {
                                        chosen_obj.insert("coverUrl".into(), Value::Null);
                                        chosen_obj.insert("cover_url".into(), Value::Null);
                                    }
                                    chosen_obj.insert("coverThumbUrl".into(), Value::Null);
                                    chosen_obj.insert("tags".into(), Value::Null);
                                    chosen_obj.insert("summary".into(), Value::Null);
                                    chosen_obj.insert("studio".into(), Value::Null);
                                    chosen_obj.insert("mediaFormat".into(), Value::Null);
                                }
                                chosen_obj.insert(
                                    "characters".into(),
                                    match char_summary {
                                        Some(v) if !v.is_empty() => json!(v),
                                        _ => Value::Null,
                                    },
                                );
                            }
                        }
                    }
                    let binding = if full["matchStatus"] == "exact_match" {
                        full.get("chosen").and_then(|c| c.get("externalId")).and_then(Value::as_str).map(|eid| {
                            let orig = full["chosen"]["originalTitle"].as_str().unwrap_or("").trim().to_string();
                            let mut obj = serde_json::Map::new();
                            obj.insert("work_id".into(), json!(target.work_id));
                            obj.insert("kind".into(), json!(target.kind));
                            obj.insert("external_source".into(), json!("bangumi"));
                            obj.insert("external_id".into(), json!(eid));
                            if !orig.is_empty() {
                                obj.insert("original_title".into(), json!(orig));
                            }
                            Value::Object(obj)
                        })
                    } else {
                        None
                    };
                    let shown = if compact {
                        evaluate_bangumi_matches_with_mode(&target, hits, max_candidates, true)
                    } else {
                        full
                    };
                    (shown, binding)
                }
                Err(err) => (
                    json!({
                        "work_id": target.work_id,
                        "matchStatus": "error",
                        "confidence": 0.0,
                        "error": err.to_string(),
                        "code": if err.to_string().contains("429") { "RATE_LIMITED" } else { "REMOTE_ERROR" },
                    }),
                    None,
                ),
            };
            (idx, item_json, exact_binding)
        });
    }

    let mut ordered: Vec<(usize, Value, Option<Value>)> = Vec::new();
    while let Some(joined) = join_set.join_next().await {
        if let Ok(triple) = joined {
            ordered.push(triple);
        }
    }
    ordered.sort_by_key(|(idx, _, _)| *idx);
    let mut results: Vec<Value> = Vec::with_capacity(ordered.len() + missing_ids.len());
    let mut exact_bindings: Vec<Value> = Vec::new();
    for (_, v, b) in ordered {
        results.push(v);
        if let Some(binding) = b {
            exact_bindings.push(binding);
        }
    }

    for mid in missing_ids {
        results.push(json!({
            "work_id": mid,
            "matchStatus": "not_found_local",
            "confidence": 0.0,
            "reason": "本地在库藏品中未找到该 work_id",
        }));
    }

    let mut exact = 0usize;
    let mut probable = 0usize;
    let mut no_match = 0usize;
    let mut errors = 0usize;
    let mut not_found = 0usize;
    for r in &results {
        match r["matchStatus"].as_str().unwrap_or("") {
            "exact_match" => exact += 1,
            "probable_match" => probable += 1,
            "no_match" => no_match += 1,
            "not_found_local" => not_found += 1,
            _ => errors += 1,
        }
    }

    let payload = json!({
        "mode": "batch_verify",
        "compact": compact,
        "source": "bangumi",
        "searchedKind": kind,
        "count": results.len(),
        "summary": {
            "exactMatch": exact,
            "probableMatch": probable,
            "noMatch": no_match,
            "notFoundLocal": not_found,
            "error": errors,
        },
        "note": "默认紧凑投影输出以防触发归档；可将返回的 verifyId 传给 propose_work_mutations(mode=\"bulk_bind\", from_verify_ids=[...]) 直接生成待确认批量绑定提案，或设 compact=false 查看单条完整证据。",
        "results": results,
    });
    (payload, exact_bindings)
}

/// 可续跑的持久化批量核实步进执行器：
/// - 从 `agent_verify_job_items` 取下一批 `pending`（或 `retry_failed=true` 时取 `failed`）作品；
/// - 自动剔除用户在 `agent_verify_rejections` 中标为“都不匹配”的候选；
/// - 将每项完整候选与状态落盘，精确项另存 `ver_...` 凭据；
/// - 默认仅向模型回传汇总数字、`verifyJobId`、`verifyId` 及需要人工判断的例外项（大幅省 Token）。
fn verify_title_search_fallback(title: &str, preliminary: &Value) -> Option<String> {
    if preliminary["matchStatus"] == "exact_match" {
        return None;
    }
    let cleaned = title.replace(['-', '－', '–', '—', '/', '／', '·', '・', '《', '》', '【', '】'], " ");
    let cleaned = cleaned.split_whitespace().collect::<Vec<_>>().join(" ");
    (!cleaned.is_empty() && cleaned != title).then_some(cleaned)
}

pub async fn run_verify_job_batch(
    studio_data_dir: &Path,
    local_db: &std::sync::Mutex<Connection>,
    job_id: &str,
    batch_size: usize,
    retry_failed: bool,
) -> Result<Value> {
    let cap = batch_size.clamp(1, 50);
    let (session_id, job_kind, pending_rows, rejected_map) = {
        let conn = local_db
            .lock()
            .map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let summary = crate::agent::storage::get_verify_job_summary(&conn, job_id)?
            .ok_or_else(|| StudioError::InvalidInput(format!("核实任务 `{job_id}` 不存在")))?;
        let status_filter = if retry_failed { "failed" } else { "pending" };
        let items = crate::agent::storage::load_verify_job_items(&conn, job_id, Some(status_filter))?;
        let slice: Vec<_> = items.into_iter().take(cap).collect();
        let mut rej_map = HashMap::<(String, i64), HashSet<String>>::new();
        for it in &slice {
            if let Ok(set) = crate::agent::storage::load_rejected_external_ids(&conn, &it.kind, it.work_id, "bangumi") {
                if !set.is_empty() {
                    rej_map.insert((it.kind.clone(), it.work_id), set);
                }
            }
        }
        (summary.session_id, summary.kind, slice, rej_map)
    };

    if pending_rows.is_empty() {
        let conn = local_db
            .lock()
            .map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let summary = crate::agent::storage::refresh_verify_job_counters(&conn, job_id, None)?;
        return Ok(json!({
            "mode": "verify_job_step",
            "verifyJobId": summary.id,
            "resultId": summary.result_id,
            "status": summary.status,
            "processedThisStep": 0,
            "summary": {
                "total": summary.total_count,
                "processed": summary.processed_count,
                "remaining": summary.total_count.saturating_sub(summary.processed_count),
                "exactMatch": summary.exact_count,
                "probableMatch": summary.probable_count,
                "noMatch": summary.no_match_count,
                "failed": summary.failed_count,
            },
            "verifyIds": summary.verify_ids,
            "exceptions": [],
            "note": if retry_failed { "当前没有失败项需要重试。" } else { "清单内所有条目均已核实完成。" },
        }));
    }

    let sem = Arc::new(tokio::sync::Semaphore::new(BANGUMI_BATCH_CONCURRENCY));
    let mut join_set = tokio::task::JoinSet::new();
    for (idx, row) in pending_rows.into_iter().enumerate() {
        let dir = studio_data_dir.to_path_buf();
        let permit_sem = sem.clone();
        let rejected = rejected_map
            .get(&(row.kind.clone(), row.work_id))
            .cloned()
            .unwrap_or_default();
        join_set.spawn(async move {
            let _permit = permit_sem.acquire_owned().await;
            let meta = &row.local_meta;
            let target = LocalWorkVerifyTarget {
                work_id: row.work_id,
                kind: row.kind.clone(),
                title: row.title.clone(),
                original_title: row.original_title.clone(),
                release_date: meta["releaseDate"].as_str().unwrap_or("").to_string(),
                total_episodes: meta["totalEpisodes"].as_i64().unwrap_or(0),
                media_format: meta["mediaFormat"].as_str().map(str::to_string),
                tags: meta["tags"]
                    .as_array()
                    .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                    .unwrap_or_default(),
                existing_bangumi_id: (meta["existingExternalSource"].as_str() == Some("bangumi"))
                    .then(|| meta["existingExternalId"].as_str().map(str::to_string))
                    .flatten(),
            };
            let res: Result<Vec<RemoteCatalogHit>> = async {
                let mut hits = cached_bangumi_search(&dir, &target.title, &target.kind).await?;
                if !rejected.is_empty() {
                    hits.retain(|h| !rejected.contains(&h.external_id));
                }
                let preliminary = evaluate_bangumi_matches_with_mode(&target, hits.clone(), 4, false);
                let need_orig = preliminary["matchStatus"] != "exact_match"
                    && !target.original_title.trim().is_empty()
                    && normalized_title(&target.title) != normalized_title(&target.original_title);
                let cleaned = verify_title_search_fallback(&target.title, &preliminary);
                let original = need_orig.then_some(target.original_title.as_str())
                    .filter(|title| Some(*title) != cleaned.as_deref());
                let mut seen: HashSet<String> = hits.iter().map(|h| h.external_id.clone()).collect();
                for keyword in cleaned.as_deref().into_iter().chain(original) {
                    match cached_bangumi_search(&dir, keyword, &target.kind).await {
                        Ok(more) => hits.extend(more.into_iter().filter(|hit| {
                            !rejected.contains(&hit.external_id) && seen.insert(hit.external_id.clone())
                        })),
                        Err(err) => eprintln!("分批核实补查失败：{err}"),
                    }
                }
                Ok(hits)
            }
            .await;
            (idx, row, target, res)
        });
    }

    let mut ordered = Vec::new();
    while let Some(joined) = join_set.join_next().await {
        if let Ok(item) = joined {
            ordered.push(item);
        }
    }
    ordered.sort_by_key(|(idx, _, _, _)| *idx);

    let mut exact_bindings = Vec::<Value>::new();
    let mut exceptions = Vec::<Value>::new();
    let processed_this_step = ordered.len();

    {
        let conn = local_db
            .lock()
            .map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        for (_, row, target, res) in ordered {
            match res {
                Ok(hits) => {
                    let full = evaluate_bangumi_matches_with_mode(&target, hits, 4, false);
                    let mstatus = full["matchStatus"].as_str().unwrap_or("no_match");
                    let conf = full["confidence"].as_f64().unwrap_or(0.0);
                    let chosen_eid = full.get("chosen").and_then(|c| c.get("externalId")).and_then(Value::as_str);
                    let chosen_orig = full.get("chosen").and_then(|c| c.get("originalTitle")).and_then(Value::as_str);
                    let cands = full.get("candidates").cloned().unwrap_or_else(|| json!([]));
                    let db_status = match mstatus {
                        "exact_match" => "exact",
                        "probable_match" => "probable",
                        _ => "no_match",
                    };
                    crate::agent::storage::update_verify_job_item_result(
                        &conn,
                        job_id,
                        &row.kind,
                        row.work_id,
                        db_status,
                        Some(mstatus),
                        conf,
                        chosen_eid,
                        chosen_orig,
                        &cands,
                        None,
                    )?;
                    if mstatus == "exact_match" {
                        if let Some(eid) = chosen_eid {
                            let mut obj = serde_json::Map::new();
                            obj.insert("work_id".into(), json!(row.work_id));
                            obj.insert("kind".into(), json!(row.kind));
                            obj.insert("external_source".into(), json!("bangumi"));
                            obj.insert("external_id".into(), json!(eid));
                            if let Some(orig) = chosen_orig.map(str::trim).filter(|s| !s.is_empty()) {
                                obj.insert("original_title".into(), json!(orig));
                            }
                            exact_bindings.push(Value::Object(obj));
                        }
                    } else {
                        exceptions.push(json!({
                            "work_id": row.work_id,
                            "kind": row.kind,
                            "title": row.title,
                            "matchStatus": mstatus,
                            "confidence": conf,
                            "reason": full["reason"],
                            "candidates": cands,
                        }));
                    }
                }
                Err(err) => {
                    let msg = err.to_string();
                    crate::agent::storage::update_verify_job_item_result(
                        &conn,
                        job_id,
                        &row.kind,
                        row.work_id,
                        "failed",
                        Some("error"),
                        0.0,
                        None,
                        None,
                        &json!([]),
                        Some(&msg),
                    )?;
                    exceptions.push(json!({
                        "work_id": row.work_id,
                        "kind": row.kind,
                        "title": row.title,
                        "matchStatus": "error",
                        "error": msg,
                    }));
                }
            }
        }
        let ver_id = if !exact_bindings.is_empty() {
            crate::agent::storage::save_verify_batch_result(&conn, &session_id, &exact_bindings).ok()
        } else {
            None
        };
        let summary = crate::agent::storage::refresh_verify_job_counters(&conn, job_id, ver_id.as_deref())?;
        let remaining = summary.total_count.saturating_sub(summary.processed_count);
        Ok(json!({
            "mode": "verify_job_step",
            "verifyJobId": summary.id,
            "resultId": summary.result_id,
            "kind": job_kind,
            "status": summary.status,
            "processedThisStep": processed_this_step,
            "exactThisStep": exact_bindings.len(),
            "verifyId": ver_id,
            "verifyIds": summary.verify_ids,
            "summary": {
                "total": summary.total_count,
                "processed": summary.processed_count,
                "remaining": remaining,
                "exactMatch": summary.exact_count,
                "probableMatch": summary.probable_count,
                "noMatch": summary.no_match_count,
                "failed": summary.failed_count,
            },
            "exceptionsCount": exceptions.len(),
            "exceptions": exceptions.into_iter().take(15).collect::<Vec<_>>(),
            "note": "精确命中项已本地持久化至 verifyId（默认不逐条展开以节省额度），可直接传给 propose_work_mutations(mode=\"bulk_bind\", from_verify_ids=[...])；若仍有 remaining > 0，可传 verify_job_id 继续下一批或由用户在抽屉卡片点击继续。",
        }))
    }
}

fn bangumi_result(
    mut hits: Vec<RemoteCatalogHit>,
    kind: &str,
    names: &[&str],
    binding: Option<&str>,
    max_candidates: Option<usize>,
) -> Value {
    let titles: HashSet<String> = names.iter().map(|name| normalized_title(name))
        .filter(|name| !name.is_empty()).collect();
    let is_match = |hit: &RemoteCatalogHit| {
        hit.kind == kind && if let Some(id) = binding {
            hit.external_id == id
        } else {
            titles.contains(&normalized_title(&hit.title)) || titles.contains(&normalized_title(&hit.original_title))
        }
    };
    let exact_count = hits.iter().filter(|hit| is_match(hit)).count();
    let total = hits.len();
    hits.sort_by_key(|hit| !is_match(hit));
    let cap = max_candidates
        .map(|n| n.clamp(1, 8))
        .unwrap_or_else(|| if exact_count == 1 { 3 } else { 6 });
    let results: Vec<_> = hits.into_iter().take(cap).map(|hit| json!({
        "exactMatch": is_match(&hit),
        "kind": hit.kind, "externalSource": hit.external_source, "externalId": hit.external_id,
        "title": hit.title, "originalTitle": hit.original_title, "releaseDate": hit.release_date,
        "totalEpisodes": (hit.total_episodes > 0).then_some(hit.total_episodes),
        "sourceUrl": if hit.external_id.chars().all(|ch| ch.is_ascii_digit()) && !hit.external_id.is_empty() {
            Some(format!("https://bgm.tv/subject/{}", hit.external_id))
        } else { None },
    })).collect();
    json!({
        "source": "bangumi", "searchedKind": kind, "count": total, "shown": results.len(),
        "exactMatches": exact_count, "matchStatus": match exact_count {0 => "no_exact_match", 1 => "exact_match", _ => "ambiguous"},
        "note": "只核对本次来源返回的候选及指定载体。总集数为空表示此来源未提供。",
        "results": results,
    })
}

pub async fn verify_bangumi_work(
    studio_data_dir: &Path,
    kind: &str,
    title: &str,
    original: &str,
    binding: Option<&str>,
) -> Result<Value> {
    let mut hits = cached_bangumi_search(studio_data_dir, title, kind).await?;
    let names = [title, original];
    let initial = bangumi_result(hits.clone(), kind, &names, binding, None);
    if initial["exactMatches"] != 0 || original.trim().is_empty() || normalized_title(title) == normalized_title(original) {
        return Ok(initial);
    }
    let more = cached_bangumi_search(studio_data_dir, original, kind).await?;
    let mut ids: HashSet<_> = hits.iter().map(|hit| hit.external_id.clone()).collect();
    hits.extend(more.into_iter().filter(|hit| ids.insert(hit.external_id.clone())));
    Ok(bangumi_result(hits, kind, &names, binding, None))
}

/// 异步在线目录检索（绝不在持有 DB Mutex 时调用！）
pub async fn exec_search_remote_catalog(studio_data_dir: &Path, args: &Value) -> Result<Value> {
    let source = args
        .get("source")
        .and_then(|v| v.as_str())
        .unwrap_or("bangumi");
    let keyword = args
        .get("keyword")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    let max_candidates = args
        .get("max_candidates")
        .and_then(|v| v.as_u64())
        .map(|n| n as usize);

    match source {
        "bangumi" | "anime" | "book" => {
            if keyword.is_empty() {
                return Err(StudioError::InvalidInput("在线检索需要提供 keyword".into()));
            }
            let kind = args
                .get("kind")
                .and_then(|v| v.as_str())
                .unwrap_or(if source == "book" { "book" } else { "anime" });
            let hits = cached_bangumi_search(studio_data_dir, keyword, kind).await?;
            Ok(bangumi_result(hits, kind, &[keyword], None, max_candidates))
        }
        "game" => {
            if keyword.is_empty() {
                return Err(StudioError::InvalidInput("游戏检索需要提供 keyword".into()));
            }
            let hits = search_vndb_and_cloud_games(studio_data_dir, keyword).await?;
            Ok(json!({
                "source": "game",
                "count": hits.len(),
                "results": hits.into_iter().take(8).map(|h| json!({
                    "kind": h.kind,
                    "externalSource": h.external_source,
                    "externalId": h.external_id,
                    "title": h.title,
                    "originalTitle": h.original_title,
                    "releaseDate": h.release_date,
                    "publicScore": h.public_score,
                    "studio": h.studio_or_circle,
                    "coverUrl": h.cover_url,
                    "synopsis": h.synopsis.chars().take(160).collect::<String>(),
                })).collect::<Vec<_>>(),
            }))
        }
        "character" => {
            if let Some(subject_id) = args.get("bangumi_subject_id").and_then(|v| v.as_i64()) {
                let mut hits =
                    fetch_work_characters_via_server_first(studio_data_dir, subject_id).await?;
                let role_rank = |role: &str| -> u8 {
                    let r = role.trim();
                    if r.contains("主角") {
                        0
                    } else if r.contains("主要") {
                        1
                    } else if r.contains("配角") {
                        2
                    } else if r.contains("客串") {
                        4
                    } else {
                        3
                    }
                };
                // 稳定按主次排序：主角 → 主要角色 → 配角 → 其他 → 客串，避免客串占满前排
                hits.sort_by_key(|c| role_rank(&c.role_name));

                let filter_kw = keyword.trim().to_ascii_lowercase();
                if !filter_kw.is_empty() {
                    hits.retain(|c| {
                        c.name.to_ascii_lowercase().contains(&filter_kw)
                            || c.name_cn.to_ascii_lowercase().contains(&filter_kw)
                            || c.voice_actor.to_ascii_lowercase().contains(&filter_kw)
                            || c.summary.to_ascii_lowercase().contains(&filter_kw)
                    });
                }

                let total = hits.len();
                let offset = args.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
                let limit = args
                    .get("limit")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(30)
                    .clamp(1, 50) as usize;
                let page: Vec<Value> = hits
                    .into_iter()
                    .skip(offset)
                    .take(limit)
                    .map(|c| {
                        json!({
                            "bgmId": c.bgm_id,
                            "name": c.name,
                            "nameCn": c.name_cn,
                            "roleName": c.role_name,
                            "voiceActor": c.voice_actor,
                            "imageUrl": c.image_url,
                            "summary": c.summary.chars().take(80).collect::<String>(),
                        })
                    })
                    .collect();
                let shown = page.len();
                let has_more = offset.saturating_add(shown) < total;
                return Ok(json!({
                    "source": "work_characters",
                    "subjectId": subject_id,
                    "filterKeyword": (!filter_kw.is_empty()).then_some(keyword),
                    "total": total,
                    "count": total,
                    "offset": offset,
                    "shown": shown,
                    "hasMore": has_more,
                    "nextOffset": has_more.then_some(offset.saturating_add(shown)),
                    "results": page,
                }));
            }
            if keyword.is_empty() {
                return Err(StudioError::InvalidInput(
                    "角色搜索需要提供 keyword 或 bangumi_subject_id".into(),
                ));
            }
            let hits = search_characters_via_server_first(studio_data_dir, keyword).await?;
            let limit = args
                .get("limit")
                .and_then(|v| v.as_u64())
                .unwrap_or(15)
                .clamp(1, 30) as usize;
            Ok(json!({
                "source": "character",
                "count": hits.len(),
                "results": hits.into_iter().take(limit).map(|c| json!({
                    "bgmId": c.bgm_id,
                    "name": c.name,
                    "nameCn": c.name_cn,
                    "gender": c.gender,
                    "imageUrl": c.image_url,
                    "summary": c.summary.chars().take(100).collect::<String>(),
                })).collect::<Vec<_>>(),
            }))
        }
        "calendar" => {
            let cal = fetch_bangumi_calendar(studio_data_dir).await?;
            let day_filter = args.get("day").and_then(|v| v.as_i64());
            let days: Vec<Value> = cal
                .days
                .into_iter()
                .filter(|d| day_filter.map(|f| d.weekday_id == f).unwrap_or(true))
                .map(|d| {
                    json!({
                        "weekdayId": d.weekday_id,
                        "weekdayCn": d.weekday_cn,
                        "items": d.items.into_iter().take(10).map(|it| json!({
                            "subjectId": it.id,
                            "title": it.title,
                            "score": it.score,
                            "airDate": it.air_date,
                        })).collect::<Vec<_>>(),
                    })
                })
                .collect();
            Ok(json!({ "source": "calendar", "days": days }))
        }
        other => Err(StudioError::InvalidInput(format!(
            "不支持的在线源 `{other}`，可选 bangumi / book / game / character / calendar"
        ))),
    }
}

pub fn exec_query_app_knowledge(args: &Value, current_settings: Option<&Value>) -> Value {
    let topic = args.get("topic").and_then(|v| v.as_str()).unwrap_or("");
    let include_current = args
        .get("include_current_settings")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);
    search_app_knowledge(topic, include_current, current_settings)
}

fn current_season_year_month() -> (i32, i32) {
    let now = Local::now();
    let month = match now.month() {
        1..=3 => 1,
        4..=6 => 4,
        7..=9 => 7,
        _ => 10,
    };
    (now.year(), month)
}

fn next_season_year_month(year: i32, month: i32) -> (i32, i32) {
    match month {
        1 => (year, 4),
        4 => (year, 7),
        7 => (year, 10),
        _ => (year + 1, 1),
    }
}

/// 异步拉取追番喵自有发现页榜单（全模型免费通用，绝不在持有 DB Mutex 时调用！）
pub async fn exec_get_discovery_feed(studio_data_dir: &Path, args: &Value) -> Result<Value> {
    let kind = match args.get("kind").and_then(|v| v.as_str()).unwrap_or("anime").trim() {
        "book" => "book".to_string(),
        "game" => "game".to_string(),
        _ => "anime".to_string(),
    };
    let raw_feed = args
        .get("feed")
        .and_then(|v| v.as_str())
        .unwrap_or("seasonal")
        .trim();
    let (cur_year, cur_month) = current_season_year_month();
    let (feed, default_year, default_month) = match raw_feed {
        "upcoming" => {
            let (ny, nm) = next_season_year_month(cur_year, cur_month);
            ("seasonal".to_string(), Some(ny), Some(nm))
        }
        "ranking" => ("ranking".to_string(), None, None),
        "popular" => ("popular".to_string(), None, None),
        _ => ("seasonal".to_string(), Some(cur_year), Some(cur_month)),
    };
    let year = args
        .get("year")
        .and_then(|v| v.as_i64())
        .map(|y| y as i32)
        .or(default_year);
    let month = args
        .get("month")
        .and_then(|v| v.as_i64())
        .map(|m| m as i32)
        .or(default_month);
    let sort = args
        .get("sort")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let query = args
        .get("query")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let tag = args
        .get("tag")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let page = args
        .get("page")
        .and_then(|v| v.as_u64())
        .unwrap_or(1)
        .clamp(1, 20) as u32;
    let limit = args
        .get("limit")
        .and_then(|v| v.as_u64())
        .unwrap_or(12)
        .clamp(1, 20) as usize;

    let req = DiscoveryFeedRequest {
        kind: kind.clone(),
        feed: feed.clone(),
        sort,
        query,
        year,
        month,
        page: Some(page),
        tag,
    };
    let res = fetch_discovery_feed(studio_data_dir, req).await?;
    let shown_items: Vec<Value> = res
        .items
        .into_iter()
        .take(limit)
        .map(|it| {
            json!({
                "kind": it.kind,
                "externalSource": it.external_source,
                "externalId": it.external_id,
                "title": it.title,
                "originalTitle": it.original_title,
                "airDate": it.air_date,
                "score": it.score,
                "rank": it.rank,
                "totalEpisodes": it.total_episodes,
                "studioOrDev": it.studio_or_dev,
                "mediaFormat": it.media_format,
                "tags": it.tags.into_iter().take(4).collect::<Vec<_>>(),
                "summary": it.summary.chars().take(120).collect::<String>(),
            })
        })
        .collect();
    let count = shown_items.len();

    Ok(json!({
        "kind": kind,
        "feed": raw_feed,
        "year": year,
        "month": month,
        "page": res.page,
        "total": res.total,
        "shown": count,
        "hasMore": res.has_more,
        "sourceLabel": res.source_label,
        "updatedAt": res.updated_at,
        "items": shown_items,
    }))
}

/// 收集所有被在库作品、回收站软删除作品、角色以及未撤销/待确认提案快照引用的 `covers/native/` 文件名，
/// 确保孤儿清理绝不误删任何仍在引用或尚可撤销的本地封面。
pub fn collect_protected_native_cover_filenames(
    sync_conn: &Connection,
    local_conn: &Connection,
) -> HashSet<String> {
    let mut protected = HashSet::<String>::new();
    let mut collect_from_text = |text: &str| {
        let mut cursor = text;
        while let Some(pos) = cursor.find("animeow-cover://") {
            let after = &cursor[pos + "animeow-cover://".len()..];
            let end = after
                .find(|c: char| matches!(c, '"' | '\'' | '\\' | ' ' | '\n' | '\r' | '\t' | ',' | ')' | '}'))
                .unwrap_or(after.len());
            let rel = after[..end].trim().trim_start_matches('/');
            if let Some(fname) = rel.strip_prefix("native/") {
                if !fname.is_empty() && !fname.contains('/') && !fname.contains("..") {
                    protected.insert(fname.to_string());
                }
            }
            cursor = &after[end..];
        }
    };

    for sql in [
        "SELECT COALESCE(coverUrl, '') FROM animes WHERE coverUrl IS NOT NULL",
        "SELECT COALESCE(coverUrl, '') FROM games WHERE coverUrl IS NOT NULL",
        "SELECT COALESCE(coverUrl, '') FROM audio_works WHERE coverUrl IS NOT NULL",
        "SELECT COALESCE(imageUrl, '') FROM characters WHERE imageUrl IS NOT NULL",
    ] {
        if let Ok(mut stmt) = sync_conn.prepare(sql) {
            if let Ok(rows) = stmt.query_map([], |r| r.get::<_, String>(0)) {
                for s in rows.flatten() {
                    collect_from_text(&s);
                }
            }
        }
    }
    // 保护仍处于 pending / applied / auto_applied 的提案操作体与撤销快照里的封面，避免撤销时找不到原文件
    if let Ok(mut stmt) = local_conn.prepare(
        "SELECT COALESCE(ops_json, ''), COALESCE(rollback_snapshot_json, '')
         FROM agent_proposals
         WHERE status IN ('pending', 'applied', 'auto_applied')",
    ) {
        if let Ok(rows) = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))) {
            for (ops_s, snap_s) in rows.flatten() {
                collect_from_text(&ops_s);
                collect_from_text(&snap_s);
            }
        }
    }
    protected
}

/// 实测统计全库 `coverType` / `cover.source` 分布以及 `covers/native/` 目录真实磁盘占用与孤儿文件数。
pub fn compute_cover_baseline_stats(
    sync_conn: &Connection,
    local_conn: &Connection,
    studio_data_dir: &Path,
) -> Result<crate::agent::types::CoverBaselineStats> {
    let items = query_curator_items(sync_conn, local_conn)?;
    let mut remote_count = 0usize;
    let mut local_user_custom_count = 0usize;
    let mut local_external_native_count = 0usize;
    let mut none_count = 0usize;

    for it in &items {
        let fsrc = crate::agent::storage::load_work_field_sources(local_conn, &it.kind, it.id)
            .ok()
            .and_then(|m| m.get("cover").map(|(s, _)| s.clone()));
        let cov = inspect_cover_metadata_with_source(
            &it.cover_url,
            fsrc.as_deref(),
            Some(studio_data_dir),
            Some(local_conn),
        );
        match cov.cover_source {
            "external_remote" => remote_count += 1,
            "user_custom" => local_user_custom_count += 1,
            "external_native" => local_external_native_count += 1,
            _ => none_count += 1,
        }
    }

    let protected = collect_protected_native_cover_filenames(sync_conn, local_conn);
    let native_dir = studio_data_dir.join("covers").join("native");
    let mut native_dir_file_count = 0usize;
    let mut native_dir_total_bytes = 0u64;
    let mut rehydrate_file_count = 0usize;
    let mut rehydrate_total_bytes = 0u64;
    let mut orphan_file_count = 0usize;
    let mut orphan_total_bytes = 0u64;

    if let Ok(entries) = std::fs::read_dir(&native_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(fname) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            if fname.ends_with(".tmp") {
                continue;
            }
            let bytes = entry.metadata().map(|m| m.len()).unwrap_or(0);
            native_dir_file_count += 1;
            native_dir_total_bytes += bytes;
            if fname.starts_with("rehydrate_") || fname.starts_with("create_") {
                rehydrate_file_count += 1;
                rehydrate_total_bytes += bytes;
            }
            if !protected.contains(fname) {
                orphan_file_count += 1;
                orphan_total_bytes += bytes;
            }
        }
    }

    let rehydrate_avg_bytes = if rehydrate_file_count > 0 {
        rehydrate_total_bytes / (rehydrate_file_count as u64)
    } else {
        0
    };

    Ok(crate::agent::types::CoverBaselineStats {
        total_works: items.len(),
        remote_count,
        local_user_custom_count,
        local_external_native_count,
        none_count,
        native_dir_file_count,
        native_dir_total_bytes,
        rehydrate_file_count,
        rehydrate_total_bytes,
        rehydrate_avg_bytes,
        orphan_file_count,
        orphan_total_bytes,
    })
}

/// 扫描或清理 `covers/native/` 下不再被任何作品、回收站记录、角色或可撤销提案引用的孤儿封面文件。
/// `dry_run = true` 时只扫描汇报、不删除文件；`dry_run = false` 时执行物理清理并汇报释放字节数。
pub fn cleanup_orphan_native_covers(
    sync_conn: &Connection,
    local_conn: &Connection,
    studio_data_dir: &Path,
    dry_run: bool,
) -> Result<crate::agent::types::OrphanCoverCleanupResult> {
    let protected = collect_protected_native_cover_filenames(sync_conn, local_conn);
    let native_dir = studio_data_dir.join("covers").join("native");
    let mut scanned_files = 0usize;
    let mut protected_files = 0usize;
    let mut orphan_files = Vec::<String>::new();
    let mut removed_count = 0usize;
    let mut freed_bytes = 0u64;

    if let Ok(entries) = std::fs::read_dir(&native_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let Some(fname) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            if fname.ends_with(".tmp") {
                continue;
            }
            scanned_files += 1;
            if protected.contains(fname) {
                protected_files += 1;
                continue;
            }
            let bytes = entry.metadata().map(|m| m.len()).unwrap_or(0);
            orphan_files.push(fname.to_string());
            if !dry_run {
                if std::fs::remove_file(&path).is_ok() {
                    removed_count += 1;
                    freed_bytes += bytes;
                }
            } else {
                freed_bytes += bytes;
            }
        }
    }
    orphan_files.sort();
    let message = if dry_run {
        if orphan_files.is_empty() {
            format!("扫描完成：covers/native/ 共 {scanned_files} 个文件，均处于引用或可撤销保护中，无孤儿文件。")
        } else {
            format!(
                "扫描发现 {} 个无引用孤儿封面文件（预计可释放 {} 字节）；请经用户二次确认后再执行清理。",
                orphan_files.len(),
                freed_bytes
            )
        }
    } else if removed_count == 0 {
        "未发现可清理的孤儿封面文件。".into()
    } else {
        format!("已安全清理 {removed_count} 个孤儿本地封面文件，释放 {freed_bytes} 字节。")
    };
    Ok(crate::agent::types::OrphanCoverCleanupResult {
        dry_run,
        scanned_files,
        protected_files,
        orphan_files,
        removed_count,
        freed_bytes,
        message,
    })
}

/// 将历史遗留的 ISO UTC 时间戳（如 `2026-10-10T16:00:00.000Z`）按东八区（UTC+8）换算为标准 `YYYY-MM-DD`，
/// 避免直接截断前 10 位导致少一天；同时识别 `/` 分隔等非常规日期格式。
pub fn suggest_normalized_release_date(raw: &str) -> Option<String> {
    let s = raw.trim();
    if s.is_empty() {
        return None;
    }
    // 已经是合法 YYYY / YYYY-MM / YYYY-MM-DD 时返回 None（无需归一）
    if (s.len() == 4 && s.chars().all(|c| c.is_ascii_digit()))
        || (s.len() == 7
            && s.as_bytes()[4] == b'-'
            && chrono::NaiveDate::parse_from_str(&format!("{s}-01"), "%Y-%m-%d").is_ok())
        || (s.len() == 10 && chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d").is_ok())
    {
        return None;
    }
    if let Ok(dt) = chrono::DateTime::parse_from_rfc3339(s) {
        let cst = chrono::FixedOffset::east_opt(8 * 3600)?;
        return Some(dt.with_timezone(&cst).format("%Y-%m-%d").to_string());
    }
    // 处理 YYYY/MM/DD 或 YYYY.MM.DD
    let replaced = s.replace(['/', '.'], "-");
    let parts: Vec<&str> = replaced.split('-').collect();
    if parts.len() == 3 {
        if let (Ok(y), Ok(m), Ok(d)) = (
            parts[0].parse::<i32>(),
            parts[1].parse::<u32>(),
            parts[2].chars().take(2).collect::<String>().parse::<u32>(),
        ) {
            if let Some(nd) = chrono::NaiveDate::from_ymd_opt(y, m, d) {
                return Some(nd.format("%Y-%m-%d").to_string());
            }
        }
    }
    None
}

/// 扫描全库存量中非标准格式的 `release_date`（只报不改，返回每条的当前值与东八区推荐归一值，供进一步提交提案）。
pub fn scan_nonstandard_release_dates(
    sync_conn: &Connection,
    local_conn: &Connection,
) -> Result<Value> {
    let items = query_curator_items(sync_conn, local_conn)?;
    let mut findings = Vec::<Value>::new();
    for it in &items {
        let raw = it.release_date.trim();
        if raw.is_empty() {
            continue;
        }
        let is_standard = (raw.len() == 4 && raw.chars().all(|c| c.is_ascii_digit()))
            || (raw.len() == 7
                && raw.as_bytes()[4] == b'-'
                && chrono::NaiveDate::parse_from_str(&format!("{raw}-01"), "%Y-%m-%d").is_ok())
            || (raw.len() == 10 && chrono::NaiveDate::parse_from_str(raw, "%Y-%m-%d").is_ok());
        if !is_standard {
            findings.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "currentReleaseDate": raw,
                "suggestedReleaseDate": suggest_normalized_release_date(raw),
            }));
        }
    }
    Ok(json!({
        "totalScanned": items.len(),
        "nonstandardCount": findings.len(),
        "items": findings,
        "note": "此为只读扫描结果；如需修正，请通过 propose_work_mutations(operations=[{action:'update', work_id, kind, release_date}]) 提交可撤销提案。"
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{init_local_db, init_sync_db};
    use tempfile::tempdir;

    #[test]
    fn decorated_titles_get_a_second_search_only_without_exact_match() {
        let miss = json!({"matchStatus": "no_match"});
        let exact = json!({"matchStatus": "exact_match"});
        assert_eq!(
            verify_title_search_fallback("【86 -不存在的战区-】", &miss).as_deref(),
            Some("86 不存在的战区")
        );
        assert_eq!(verify_title_search_fallback("86 -不存在的战区-", &exact), None);
        assert_eq!(verify_title_search_fallback("86 不存在的战区", &miss), None);
        assert_eq!(verify_title_search_fallback("《—》", &miss), None);
    }

    #[test]
    fn record_pages_cover_every_id_without_counting_games_as_unknown() {
        let dir = tempdir().unwrap();
        let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch(
            "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n < 3000)
             INSERT INTO animes (id, title, totalEpisodes)
             SELECT n, '重复标题', 0 FROM seq;
             INSERT INTO games (syncId, title, playMinutes, updatedAt) VALUES ('g', '按时长记录', 0, '2026-11-21');"
        ).unwrap();
        let all = query_curator_items(&sync, &local).unwrap();
        let matches = filter_library(&sync, &all, &json!({"progress_total_unknown":true,"sort":"title"}));
        assert_eq!(matches.len(), 3000);
        for count in [59, 300, 3000] {
            let mut offset = 0;
            let mut ids = Vec::new();
            loop {
                let page = library_record_page(matches[..count].iter().copied(), count, offset, 30);
                assert!(page.to_string().chars().count() <= super::super::registry::ARTIFACT_SPILL_THRESHOLD_CHARS);
                ids.extend(page["items"].as_array().unwrap().iter().map(|row| row["id"].as_i64().unwrap()));
                match page["nextOffset"].as_u64() {
                    Some(next) => { assert!(next as usize > offset); offset = next as usize; }
                    None => break,
                }
            }
            assert_eq!(ids, (1..=count as i64).collect::<Vec<_>>());
        }
    }

    #[test]
    fn query_library_supports_missing_field_filters() {
        let dir = tempdir().unwrap();
        let sync_conn = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local_conn = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync_conn
            .execute(
                "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, rating, airDate, externalSource, externalId, subjectType)
                 VALUES
                   (1, '完整番', '看完', 12, 12, 85, '2025-04-01', 'bangumi', '1001', 'anime'),
                   (2, '缺总集缺分缺日期缺外链', '想看', 0, 0, NULL, 'null', NULL, NULL, 'anime'),
                   (3, '连载轻小说', '在看', 3, 0, 78, '2023-01-01', 'bangumi', '2002', 'book')",
                [],
            )
            .unwrap();
        sync_conn
            .execute("INSERT INTO tags (id, name) VALUES (10, '治愈')", [])
            .unwrap();
        sync_conn
            .execute("INSERT INTO anime_tags (animeId, tagId) VALUES (1, 10)", [])
            .unwrap();

        let unknown_res = exec_query_library(
            &sync_conn,
            &local_conn,
            &json!({ "progress_total_unknown": true }),
        )
        .unwrap();
        assert_eq!(unknown_res["total"], 2);

        let missing_score_res = exec_query_library(
            &sync_conn,
            &local_conn,
            &json!({ "missing_score": true }),
        )
        .unwrap();
        assert_eq!(missing_score_res["total"], 1);
        assert_eq!(missing_score_res["items"][0]["id"], 2);

        let missing_tags_res = exec_query_library(
            &sync_conn,
            &local_conn,
            &json!({ "missing_tags": true }),
        )
        .unwrap();
        assert_eq!(missing_tags_res["total"], 2);

        let missing_date_res = exec_query_library(
            &sync_conn,
            &local_conn,
            &json!({ "missing_release_date": true }),
        )
        .unwrap();
        assert_eq!(missing_date_res["total"], 1);
        assert_eq!(missing_date_res["items"][0]["id"], 2);

        let missing_ext_res = exec_query_library(
            &sync_conn,
            &local_conn,
            &json!({ "missing_external_id": true }),
        )
        .unwrap();
        assert_eq!(missing_ext_res["total"], 1);
        assert_eq!(missing_ext_res["items"][0]["id"], 2);
    }
}
