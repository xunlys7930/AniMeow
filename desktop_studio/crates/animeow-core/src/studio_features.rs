use crate::repository::is_completed_status;
use crate::{Result, StudioError};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io::Read;
use std::path::Path;
use uuid::Uuid;
use zip::ZipArchive;

const BANGUMI_PRIMARY: &str = "https://api.bgm.tv";
const BANGUMI_MIRROR: &str = "https://api-bgm.xunlys.top";
const STUDIO_USER_AGENT: &str = "AniMeowStudio/0.2.0 (+https://github.com/xunlys7930/AniMeow)";

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewCuratorItemInput {
    pub kind: String, // "anime" | "book" | "game" | "audio"
    pub title: String,
    pub original_title: Option<String>,
    pub cover_url: Option<String>,
    pub status: Option<String>,
    pub total_episodes: Option<i64>,
    pub studio_or_circle: Option<String>,
    pub release_date: Option<String>,
    pub public_score: Option<f64>,
    pub external_source: Option<String>,
    pub external_id: Option<String>,
    pub work_code: Option<String>,
    pub voice_actors: Option<String>,
    pub synopsis: Option<String>,
    #[serde(default)]
    pub media_format: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteCatalogHit {
    pub kind: String,
    pub external_source: String,
    pub external_id: String,
    pub title: String,
    pub original_title: String,
    pub cover_url: String,
    pub release_date: String,
    pub total_episodes: i64,
    pub public_score: Option<f64>,
    pub studio_or_circle: String,
    pub synopsis: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CanvasNode {
    pub node_id: String,
    pub node_type: String, // "hub" | "anime" | "book" | "game" | "audio" | "character"
    pub label: String,
    pub sublabel: String,
    pub badge: String,
    pub work_kind: Option<String>,
    pub work_id: Option<i64>,
    #[serde(default)]
    pub image_url: Option<String>,
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CanvasEdge {
    pub from_id: String,
    pub to_id: String,
    pub relation: String,
    #[serde(default)]
    pub relation_id: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UniverseGraph {
    pub mode: String,
    pub nodes: Vec<CanvasNode>,
    pub edges: Vec<CanvasEdge>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaySessionRow {
    pub id: i64,
    pub work_kind: String,
    pub work_id: i64,
    pub work_title: String,
    pub launcher_path: Option<String>,
    pub started_at: String,
    pub ended_at: String,
    pub duration_seconds: i64,
    pub note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupDiffEntry {
    pub kind: String,
    pub title: String,
    pub diff_type: String, // "changed" | "only_remote" | "only_local"
    pub local_summary: String,
    pub remote_summary: String,
}

/// 在跨端主库 `animeow.db` 中新建藏品（支持动画、书籍、游戏、音声四类载体，幂等防重复外部源）
pub fn create_curator_item(sync_conn: &Connection, input: &NewCuratorItemInput) -> Result<i64> {
    insert_curator_item(sync_conn, input, true)
}

pub(crate) fn create_new_curator_item(sync_conn: &Connection, input: &NewCuratorItemInput) -> Result<i64> {
    insert_curator_item(sync_conn, input, false)
}

fn insert_curator_item(sync_conn: &Connection, input: &NewCuratorItemInput, allow_existing: bool) -> Result<i64> {
    let duplicate = || StudioError::InvalidInput(
        "这个来源已绑定藏品（可能在回收站）。请选择现有条目修改或恢复。".into(),
    );
    let title = input.title.trim();
    if title.is_empty() {
        return Err(StudioError::InvalidInput("作品标题不能为空".into()));
    }
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let orig = input.original_title.as_deref().unwrap_or("").trim();
    let cover = input.cover_url.as_deref().unwrap_or("").trim();
    let maker = input.studio_or_circle.as_deref().unwrap_or("").trim();
    let rel_date = input.release_date.as_deref().unwrap_or("").trim();
    let va = input.voice_actors.as_deref().unwrap_or("").trim();
    let syn = input.synopsis.as_deref().unwrap_or("").trim();

    match input.kind.as_str() {
        "anime" | "book" => {
            let default_status = "在看";
            let status = input
                .status
                .as_deref()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or(default_status);
            let ext_src = input
                .external_source
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty());
            let ext_id = input
                .external_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty());

            if let (Some(src), Some(eid)) = (ext_src, ext_id) {
                if let Some(existing_id) = sync_conn
                    .query_row(
                        "SELECT id FROM animes WHERE externalSource = ?1 AND externalId = ?2",
                        params![src, eid],
                        |r| r.get::<_, i64>(0),
                    )
                    .optional()?
                {
                    if !allow_existing {
                        return Err(duplicate());
                    }
                    let today = Utc::now().format("%Y-%m-%d").to_string();
                    let status_changed_at = Utc::now().timestamp_millis();
                    if is_completed_status(status) {
                        sync_conn.execute(
                            "UPDATE animes
                             SET deletedAt = NULL,
                                 status = ?1,
                                 statusChangedAt = ?2,
                                 watchedEpisodes = CASE WHEN COALESCE(totalEpisodes, 0) > 0 THEN totalEpisodes ELSE watchedEpisodes END,
                                 watchFinishDate = CASE WHEN COALESCE(watchFinishDate, '') = '' THEN ?3 ELSE watchFinishDate END
                             WHERE id = ?4",
                            params![status, status_changed_at, today, existing_id],
                        )?;
                    } else {
                        sync_conn.execute(
                            "UPDATE animes SET deletedAt = NULL, status = ?1, statusChangedAt = ?2 WHERE id = ?3",
                            params![status, status_changed_at, existing_id],
                        )?;
                    }
                    return Ok(existing_id);
                }
            }

            let source_rating_json = input.public_score.filter(|&sc| sc > 0.0).map(|sc| {
                json!({
                    "source": ext_src.unwrap_or("bangumi"),
                    "id": ext_id.unwrap_or(""),
                    "score": sc,
                    "updatedAt": now
                })
                .to_string()
            });

            let subject_type = if input.kind == "book" { "book" } else { "anime" };
            let total_eps = input.total_episodes.unwrap_or(0).max(0);
            let completed = is_completed_status(status);
            let initial_watched = if completed && total_eps > 0 { total_eps } else { 0 };
            let finish_date = if completed {
                Some(Utc::now().format("%Y-%m-%d").to_string())
            } else {
                None
            };
            let status_changed_at = Utc::now().timestamp_millis();

            let media_format = input
                .media_format
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"));

            sync_conn.execute(
                "INSERT INTO animes (
                    title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
                    subjectType, createdAt, airDate, studio, externalSource, externalId,
                    sourceRatingJson, voiceActors, synopsis, statusChangedAt, watchFinishDate, mediaFormat, updatedAt
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
                params![
                    title,
                    (!orig.is_empty()).then_some(orig),
                    (!cover.is_empty()).then_some(cover),
                    status,
                    initial_watched,
                    total_eps,
                    subject_type,
                    now,
                    (!rel_date.is_empty()).then_some(rel_date),
                    (!maker.is_empty()).then_some(maker),
                    ext_src,
                    ext_id,
                    source_rating_json,
                    (!va.is_empty()).then_some(va),
                    (!syn.is_empty()).then_some(syn),
                    status_changed_at,
                    finish_date,
                    media_format,
                    status_changed_at,
                ],
            )?;
            Ok(sync_conn.last_insert_rowid())
        }
        "game" => {
            let status = input
                .status
                .as_deref()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or("想玩");
            let bgm_id = if input.external_source.as_deref() == Some("bangumi") {
                input
                    .external_id
                    .as_deref()
                    .and_then(|s| s.parse::<i64>().ok())
            } else {
                None
            };
            if let Some(bid) = bgm_id {
                if let Some(existing_id) = sync_conn
                    .query_row(
                        "SELECT id FROM games WHERE bangumiId = ?1",
                        params![bid],
                        |r| r.get::<_, i64>(0),
                    )
                    .optional()?
                {
                    if !allow_existing {
                        return Err(duplicate());
                    }
                    sync_conn.execute(
                        "UPDATE games SET deletedAt = NULL, status = ?1, updatedAt = ?2 WHERE id = ?3",
                        params![status, now, existing_id],
                    )?;
                    return Ok(existing_id);
                }
            }
            let sync_id = format!("studio-game-{}", Uuid::new_v4());
            let score = input.public_score.unwrap_or(0.0).max(0.0);
            sync_conn.execute(
                "INSERT INTO games (
                    syncId, bangumiId, title, originalTitle, coverUrl, synopsis,
                    releaseDate, developer, score, status, playMinutes, updatedAt
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, 0, ?11)",
                params![
                    sync_id,
                    bgm_id,
                    title,
                    orig,
                    cover,
                    syn,
                    rel_date,
                    maker,
                    score,
                    status,
                    now
                ],
            )?;
            Ok(sync_conn.last_insert_rowid())
        }
        "audio" => {
            let status = input
                .status
                .as_deref()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or("想听");
            let code = input
                .work_code
                .as_deref()
                .or(input.external_id.as_deref())
                .unwrap_or("")
                .trim()
                .to_ascii_uppercase();
            if !code.is_empty() {
                if let Some(existing_id) = sync_conn
                    .query_row(
                        "SELECT id FROM audio_works WHERE workCode = ?1 LIMIT 1",
                        params![code],
                        |r| r.get::<_, i64>(0),
                    )
                    .optional()?
                {
                    if !allow_existing {
                        return Err(duplicate());
                    }
                    sync_conn.execute(
                        "UPDATE audio_works SET deletedAt = NULL, status = ?1, updatedAt = ?2 WHERE id = ?3",
                        params![status, now, existing_id],
                    )?;
                    return Ok(existing_id);
                }
            }
            let sync_id = format!("studio-audio-{}", Uuid::new_v4());
            sync_conn.execute(
                "INSERT INTO audio_works (
                    syncId, title, originalTitle, coverUrl, voiceActors, circle,
                    releaseDate, workCode, status, updatedAt
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
                params![sync_id, title, orig, cover, va, maker, rel_date, code, status, now],
            )?;
            Ok(sync_conn.last_insert_rowid())
        }
        other => Err(StudioError::InvalidInput(format!("不支持的载体类型：{other}"))),
    }
}

/// 将多部不同载体的藏品归入同一跨媒体企划系列（同步维护 `series` 与 `series.linkedMediaJson`）
pub fn batch_assign_series(
    sync_conn: &mut Connection,
    targets: &[(String, i64)],
    series_name: &str,
    series_role: Option<&str>,
) -> Result<usize> {
    let entries: Vec<(String, i64, Option<String>)> = targets
        .iter()
        .map(|(k, id)| (k.clone(), *id, None))
        .collect();
    let (_series_id, affected) =
        batch_assign_series_with_roles(sync_conn, &entries, series_name, series_role)?;
    Ok(affected)
}

/// 将多部不同载体的藏品归入同一跨媒体企划系列，支持为每个成员单独指定定位，并返回创建或命中的 `series_id`。
pub fn batch_assign_series_with_roles(
    sync_conn: &mut Connection,
    targets: &[(String, i64, Option<String>)],
    series_name: &str,
    default_role: Option<&str>,
) -> Result<(i64, usize)> {
    let clean_name = series_name.trim();
    if clean_name.is_empty() {
        return Err(StudioError::InvalidInput("系列企划名称不能为空".into()));
    }
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let tx = sync_conn.savepoint()?;

    let series_id: i64 = match tx
        .query_row(
            "SELECT id FROM series WHERE name = ?1 LIMIT 1",
            params![clean_name],
            |r| r.get(0),
        )
        .optional()?
    {
        Some(id) => id,
        None => {
            tx.execute(
                "INSERT INTO series (name, createdAt, linkedMediaJson) VALUES (?1, ?2, '[]')",
                params![clean_name, now],
            )?;
            tx.last_insert_rowid()
        }
    };

    let existing_linked_raw: Option<String> = tx
        .query_row(
            "SELECT linkedMediaJson FROM series WHERE id = ?1",
            params![series_id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    let mut linked_arr: Vec<Value> = existing_linked_raw
        .as_deref()
        .and_then(|s| serde_json::from_str(s).ok())
        .unwrap_or_default();

    let default_role_opt = default_role.map(str::trim).filter(|s| !s.is_empty());

    let mut affected = 0usize;
    let updated_ms = Utc::now().timestamp_millis();
    let mut touched_old_series_ids: HashSet<i64> = HashSet::new();
    for (kind, id, item_role) in targets {
        let effective_role = item_role
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .or(default_role_opt);
        let role_for_linked = effective_role.unwrap_or("关联作");
        match kind.as_str() {
            "anime" | "book" => {
                if let Ok(Some(old_sid)) = tx
                    .query_row(
                        "SELECT seriesId FROM animes WHERE id = ?1",
                        params![id],
                        |r| r.get::<_, Option<i64>>(0),
                    )
                    .optional()
                    .map(|opt| opt.flatten())
                {
                    if old_sid != series_id {
                        touched_old_series_ids.insert(old_sid);
                    }
                }
                affected += tx.execute(
                    "UPDATE animes SET seriesId = ?1, seriesRole = COALESCE(?2, seriesRole), updatedAt = ?3 WHERE id = ?4",
                    params![series_id, effective_role, updated_ms, id],
                )?;
            }
            "game" => {
                if let Some(sync_id) = tx
                    .query_row("SELECT syncId FROM games WHERE id = ?1", params![id], |r| {
                        r.get::<_, String>(0)
                    })
                    .optional()?
                {
                    remove_linked_media_from_other_series(&tx, series_id, "GAME", &sync_id, &mut touched_old_series_ids)?;
                    upsert_linked_media(&mut linked_arr, "GAME", &sync_id, role_for_linked);
                    affected += 1;
                }
            }
            "audio" => {
                if let Some(sync_id) = tx
                    .query_row(
                        "SELECT syncId FROM audio_works WHERE id = ?1",
                        params![id],
                        |r| r.get::<_, String>(0),
                    )
                    .optional()?
                {
                    remove_linked_media_from_other_series(&tx, series_id, "AUDIO", &sync_id, &mut touched_old_series_ids)?;
                    upsert_linked_media(&mut linked_arr, "AUDIO", &sync_id, role_for_linked);
                    affected += 1;
                }
            }
            _ => {}
        }
    }

    tx.execute(
        "UPDATE series SET linkedMediaJson = ?1 WHERE id = ?2",
        params![serde_json::to_string(&linked_arr)?, series_id],
    )?;

    // 自动清理被全部迁空的历史拆分系列，避免左侧系列列表残留 0 项空壳
    for old_sid in touched_old_series_ids {
        cleanup_series_if_empty(&tx, old_sid)?;
    }
    tx.commit()?;
    Ok((series_id, affected))
}

fn remove_linked_media_from_other_series(
    tx: &Connection,
    keep_series_id: i64,
    kind_upper: &str,
    work_sync_id: &str,
    touched: &mut HashSet<i64>,
) -> Result<()> {
    let mut updates: Vec<(i64, String)> = Vec::new();
    {
        let mut stmt = tx.prepare(
            "SELECT id, COALESCE(linkedMediaJson, '[]') FROM series WHERE id != ?1",
        )?;
        let rows = stmt.query_map(params![keep_series_id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })?;
        for row in rows {
            let (sid, raw) = row?;
            if let Ok(mut arr) = serde_json::from_str::<Vec<Value>>(&raw) {
                let before = arr.len();
                arr.retain(|e| {
                    !(e.get("kind").and_then(Value::as_str) == Some(kind_upper)
                        && e.get("workSyncId").and_then(Value::as_str) == Some(work_sync_id))
                });
                if arr.len() != before {
                    updates.push((sid, serde_json::to_string(&arr)?));
                }
            }
        }
    }
    for (sid, next_json) in updates {
        tx.execute(
            "UPDATE series SET linkedMediaJson = ?1 WHERE id = ?2",
            params![next_json, sid],
        )?;
        touched.insert(sid);
    }
    Ok(())
}

fn cleanup_series_if_empty(tx: &Connection, sid: i64) -> Result<()> {
    let active_animes: i64 = tx.query_row(
        "SELECT COUNT(*) FROM animes WHERE seriesId = ?1 AND deletedAt IS NULL",
        params![sid],
        |r| r.get(0),
    )?;
    if active_animes > 0 {
        return Ok(());
    }
    let linked_raw: Option<String> = tx
        .query_row(
            "SELECT linkedMediaJson FROM series WHERE id = ?1",
            params![sid],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    let linked_cnt = linked_raw
        .as_deref()
        .and_then(|s| serde_json::from_str::<Vec<Value>>(s).ok())
        .map(|v| v.len())
        .unwrap_or(0);
    if linked_cnt == 0 {
        tx.execute("DELETE FROM series WHERE id = ?1", params![sid])?;
    }
    Ok(())
}

fn upsert_linked_media(arr: &mut Vec<Value>, kind_upper: &str, work_sync_id: &str, role: &str) {
    for entry in arr.iter_mut() {
        if entry.get("kind").and_then(Value::as_str) == Some(kind_upper)
            && entry.get("workSyncId").and_then(Value::as_str) == Some(work_sync_id)
        {
            entry["role"] = Value::String(role.to_string());
            return;
        }
    }
    arr.push(json!({
        "kind": kind_upper,
        "workSyncId": work_sync_id,
        "role": role
    }));
}

/// 通过官方 HTTPS 线路（超时自动回退 `api-bgm.xunlys.top` 镜像）检索 Bangumi 四类条目，供 `Ctrl+K` 极速入库使用
pub async fn search_bangumi_subjects(keyword: &str, kind: &str) -> Result<Vec<RemoteCatalogHit>> {
    let q = keyword.trim();
    if q.is_empty() {
        return Ok(Vec::new());
    }
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

    let client = reqwest::Client::builder()
        .user_agent(STUDIO_USER_AGENT)
        .timeout(std::time::Duration::from_secs(7))
        .build()
        .map_err(|e| StudioError::InvalidInput(format!("HTTP 客户端初始化失败：{e}")))?;

    let body = json!({
        "keyword": q,
        "filter": { "type": [bgm_type] }
    });

    // 国内免梯子优先走自建镜像，失败再回退 Bangumi 官方线路
    let payload = match post_bgm_search(&client, BANGUMI_MIRROR, &body).await {
        Ok(val) => val,
        Err(_) => post_bgm_search(&client, BANGUMI_PRIMARY, &body).await?,
    };

    let mut hits = Vec::new();
    if let Some(arr) = payload.get("data").and_then(Value::as_array) {
        for item in arr.iter().take(12) {
            if item["type"].as_i64().is_some_and(|item_type| item_type != bgm_type) {
                continue;
            }
            let Some(id_num) = item.get("id").and_then(Value::as_i64) else {
                continue;
            };
            let name = item.get("name").and_then(Value::as_str).unwrap_or("").trim();
            let name_cn = item
                .get("name_cn")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            let title = if !name_cn.is_empty() { name_cn } else { name };
            if title.is_empty() {
                continue;
            }
            let cover = item
                .get("images")
                .and_then(|im| {
                    im.get("common")
                        .or_else(|| im.get("medium"))
                        .or_else(|| im.get("large"))
                })
                .and_then(Value::as_str)
                .unwrap_or("")
                .replace("http://", "https://");
            let date = item
                .get("date")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim()
                .to_string();
            let eps = item
                .get("eps")
                .or_else(|| item.get("total_episodes"))
                .and_then(Value::as_i64)
                .unwrap_or(0);
            let score = item
                .get("rating")
                .and_then(|r| r.get("score"))
                .and_then(Value::as_f64)
                .filter(|&s| s > 0.0);
            let summary = item
                .get("summary")
                .and_then(Value::as_str)
                .unwrap_or("")
                .chars()
                .take(180)
                .collect::<String>();
            let platform = item
                .get("platform")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();

            hits.push(RemoteCatalogHit {
                kind: normalized_kind.to_string(),
                external_source: "bangumi".into(),
                external_id: id_num.to_string(),
                title: title.to_string(),
                original_title: name.to_string(),
                cover_url: cover,
                release_date: date,
                total_episodes: eps,
                public_score: score,
                studio_or_circle: platform,
                synopsis: summary,
            });
        }
    }
    Ok(hits)
}

async fn post_bgm_search(client: &reqwest::Client, base: &str, body: &Value) -> Result<Value> {
    let url = format!("{base}/v0/search/subjects?limit=12");
    let resp = client
        .post(url)
        .json(body)
        .send()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("外部资料源连接失败：{e}")))?;
    if !resp.status().is_success() {
        return Err(StudioError::InvalidInput(format!(
            "外部资料源返回状态码 {}",
            resp.status().as_u16()
        )));
    }
    resp.json::<Value>()
        .await
        .map_err(|e| StudioError::InvalidInput(format!("外部资料响应解析失败：{e}")))
}

/// 构建跨媒体 IP 宇宙谱系图 (`mode="ip"`)、声优/制作寻踪图 (`mode="seiyuu"`) 或角色关系图谱 (`mode="characters"`)，并合并 `desktop_local.db` 中保存的画布坐标
pub fn query_universe_graph(
    sync_conn: &Connection,
    local_conn: &Connection,
    mode: &str,
) -> Result<UniverseGraph> {
    let canvas_key = format!("universe:{mode}");
    let saved_coords: HashMap<String, (f64, f64)> = local_conn
        .query_row(
            "SELECT payload_json FROM canvas_layouts WHERE canvas_key = ?1",
            params![canvas_key],
            |r| r.get::<_, String>(0),
        )
        .optional()?
        .and_then(|s| serde_json::from_str::<HashMap<String, [f64; 2]>>(&s).ok())
        .map(|m| m.into_iter().map(|(k, [x, y])| (k, (x, y))).collect())
        .unwrap_or_default();

    let mut nodes = Vec::new();
    let mut edges = Vec::new();
    let mut seen_nodes = HashSet::new();

    if mode == "characters" {
        // 角色关系与作品出演图谱
        struct CharRow {
            id: i64,
            name: String,
            name_cn: String,
            image_url: String,
            gender: String,
            rating: Option<i64>,
        }
        let mut chars: Vec<CharRow> = Vec::new();
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, name, COALESCE(nameCn, ''), COALESCE(imageUrl, ''), COALESCE(gender, ''), rating
                 FROM characters
                 ORDER BY CASE WHEN rating IS NOT NULL AND rating > 0 THEN 0 ELSE 1 END,
                          COALESCE(rating, 0) DESC,
                          id ASC",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok(CharRow {
                    id: r.get(0)?,
                    name: r.get(1)?,
                    name_cn: r.get(2)?,
                    image_url: r.get(3)?,
                    gender: r.get(4)?,
                    rating: r.get(5)?,
                })
            })?;
            for r in rows {
                chars.push(r?);
            }
        }
        let char_map: HashMap<i64, &CharRow> = chars.iter().map(|c| (c.id, c)).collect();

        // 角色 ↔ 作品关联
        let mut work_info: HashMap<i64, (String, String)> = HashMap::new(); // animeId -> (kind, title)
        let mut work_chars: HashMap<i64, Vec<(i64, String, String)>> = HashMap::new(); // animeId -> [(charId, role, va)]
        let mut char_work_count: HashMap<i64, usize> = HashMap::new();
        {
            let mut stmt = sync_conn.prepare(
                "SELECT ac.animeId, ac.characterId, COALESCE(ac.roleName, ''), COALESCE(ac.voiceActor, ''),
                        a.title, COALESCE(a.subjectType, 'anime')
                 FROM anime_characters ac
                 JOIN animes a ON a.id = ac.animeId AND a.deletedAt IS NULL
                 ORDER BY ac.animeId ASC, ac.sortOrder ASC, ac.characterId ASC",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, String>(5)?,
                ))
            })?;
            for r in rows {
                let (aid, cid, role, va, title, subj) = r?;
                if !char_map.contains_key(&cid) {
                    continue;
                }
                let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                    "book".to_string()
                } else {
                    "anime".to_string()
                };
                work_info.entry(aid).or_insert((kind, title));
                work_chars.entry(aid).or_default().push((cid, role, va));
                *char_work_count.entry(cid).or_insert(0) += 1;
            }
        }

        // 角色 ↔ 角色关系
        let mut char_rel_count: HashMap<i64, usize> = HashMap::new();
        let mut rel_rows: Vec<(i64, i64, i64, String, String, String)> = Vec::new();
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, sourceCharacterId, targetCharacterId, COALESCE(relationType, '关联'),
                        COALESCE(sourceRole, ''), COALESCE(targetRole, '')
                 FROM character_relations
                 ORDER BY strength DESC, id ASC",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, String>(5)?,
                ))
            })?;
            for r in rows {
                let (rid, src, dst, rtype, srole, trole) = r?;
                if char_map.contains_key(&src) && char_map.contains_key(&dst) {
                    *char_rel_count.entry(src).or_insert(0) += 1;
                    *char_rel_count.entry(dst).or_insert(0) += 1;
                    rel_rows.push((rid, src, dst, rtype, srole, trole));
                }
            }
        }

        let make_char_node = |c: &CharRow, x: f64, y: f64| -> CanvasNode {
            let display = if !c.name_cn.trim().is_empty() {
                c.name_cn.trim().to_string()
            } else {
                c.name.trim().to_string()
            };
            let mut parts: Vec<String> = Vec::new();
            if !c.name_cn.trim().is_empty() && !c.name.trim().is_empty() && c.name_cn.trim() != c.name.trim() {
                parts.push(c.name.trim().to_string());
            }
            if !c.gender.trim().is_empty() {
                parts.push(c.gender.trim().to_string());
            }
            let wc = char_work_count.get(&c.id).copied().unwrap_or(0);
            let rc = char_rel_count.get(&c.id).copied().unwrap_or(0);
            if wc > 0 {
                parts.push(format!("出演 {wc}"));
            }
            if rc > 0 {
                parts.push(format!("关系 {rc}"));
            }
            let sublabel = if parts.is_empty() {
                "角色档案".to_string()
            } else {
                parts.join(" · ")
            };
            let badge = match c.rating.filter(|&v| v > 0) {
                Some(r) => format!("角色 · {:.1}", (r as f64) / 10.0),
                None => "角色".to_string(),
            };
            CanvasNode {
                node_id: format!("char:{}", c.id),
                node_type: "character".into(),
                label: display,
                sublabel,
                badge,
                work_kind: None,
                work_id: Some(c.id),
                image_url: (!c.image_url.trim().is_empty()).then(|| c.image_url.trim().to_string()),
                x,
                y,
            }
        };

        // 1) 按作品分簇摆放：左侧为作品节点，右侧为其出场角色（按重要度与关系数排序，防重叠动态列宽）
        let mut work_order: Vec<(i64, usize)> = work_chars
            .iter()
            .map(|(&aid, list)| (aid, list.len()))
            .collect();
        work_order.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));

        let role_priority = |role: &str| -> u8 {
            let r = role.trim();
            if r.contains("主角") || r.contains("女主") || r.contains("男主") {
                0
            } else if r.contains("主要") {
                1
            } else if r.contains("配角") {
                2
            } else if r.contains("客串") || r.contains("暂定") {
                4
            } else {
                3
            }
        };

        // 预先估算两列中左列最大宽度，避免多角色作品向右越界撞到右列（为作品分区底框预留足够留白）
        let char_cluster_gap = 136.0_f64;
        let char_col_gap = 220.0_f64;
        let mut est_col_y = [72.0_f64, 72.0_f64];
        let mut left_col_max_width = UNIVERSE_HUB_TO_MEMBER_X + UNIVERSE_CARD_W;
        for (aid, _) in &work_order {
            if !work_info.contains_key(aid) {
                continue;
            }
            let Some(members) = work_chars.get(aid) else {
                continue;
            };
            let col = if est_col_y[0] <= est_col_y[1] { 0 } else { 1 };
            let mc = members.len();
            if col == 0 {
                let sub_cols = universe_cluster_subcols(mc) as f64;
                let w = UNIVERSE_HUB_TO_MEMBER_X + (sub_cols - 1.0).max(0.0) * UNIVERSE_SUBCOL_GAP_X + UNIVERSE_CARD_W;
                if w > left_col_max_width {
                    left_col_max_width = w;
                }
            }
            est_col_y[col] += universe_cluster_height(mc) + char_cluster_gap;
        }

        let mut col_y = [72.0_f64, 72.0_f64];
        let col_x = [72.0_f64, 72.0_f64 + left_col_max_width + char_col_gap];

        for (aid, _) in work_order {
            let Some((wkind, wtitle)) = work_info.get(&aid) else {
                continue;
            };
            let Some(raw_members) = work_chars.get(&aid) else {
                continue;
            };
            let mut members = raw_members.clone();
            members.sort_by(|a, b| {
                role_priority(&a.1)
                    .cmp(&role_priority(&b.1))
                    .then_with(|| {
                        char_rel_count
                            .get(&b.0)
                            .copied()
                            .unwrap_or(0)
                            .cmp(&char_rel_count.get(&a.0).copied().unwrap_or(0))
                    })
                    .then_with(|| a.0.cmp(&b.0))
            });
            let wn_id = format!("work:{wkind}:{aid}");
            let col = if col_y[0] <= col_y[1] { 0 } else { 1 };
            let cluster_top = col_y[col];
            let member_count = members.len();
            let cluster_h = universe_cluster_height(member_count);
            let base_x = col_x[col];
            let base_y = cluster_top + ((cluster_h - UNIVERSE_CARD_H) / 2.0).max(0.0);

            if seen_nodes.insert(wn_id.clone()) {
                let (wx, wy) = saved_coords.get(&wn_id).copied().unwrap_or((base_x, base_y));
                nodes.push(CanvasNode {
                    node_id: wn_id.clone(),
                    node_type: wkind.clone(),
                    label: wtitle.clone(),
                    sublabel: format!("关联角色 {} 位", member_count),
                    badge: carrier_label(wkind).into(),
                    work_kind: Some(wkind.clone()),
                    work_id: Some(aid),
                    image_url: None,
                    x: wx,
                    y: wy,
                });
            }

            for (idx, (cid, role, va)) in members.iter().enumerate() {
                let cn_id = format!("char:{cid}");
                if seen_nodes.insert(cn_id.clone()) {
                    if let Some(c) = char_map.get(cid) {
                        let (cx_def, cy_def) =
                            universe_member_coords(cluster_top, base_x, member_count, idx);
                        let (cx, cy) = saved_coords.get(&cn_id).copied().unwrap_or((cx_def, cy_def));
                        nodes.push(make_char_node(c, cx, cy));
                    }
                }
                let mut rel_parts = Vec::new();
                if !role.trim().is_empty() {
                    rel_parts.push(role.trim().to_string());
                }
                if !va.trim().is_empty() {
                    rel_parts.push(format!("CV: {}", va.trim()));
                }
                edges.push(CanvasEdge {
                    from_id: wn_id.clone(),
                    to_id: cn_id,
                    relation: if rel_parts.is_empty() {
                        "出场".to_string()
                    } else {
                        rel_parts.join(" · ")
                    },
                    relation_id: None,
                });
            }

            col_y[col] = cluster_top + cluster_h + char_cluster_gap;
        }

        // 2) 尚未绑定任何作品的独立角色，整齐排布在下方网格
        let mut standalone_idx = 0usize;
        let standalone_base_y = col_y[0].max(col_y[1]);
        for c in &chars {
            let cn_id = format!("char:{}", c.id);
            if seen_nodes.insert(cn_id.clone()) {
                let col_i = standalone_idx % 4;
                let row_i = standalone_idx / 4;
                let dx = 72.0 + (col_i as f64) * 256.0;
                let dy = standalone_base_y + (row_i as f64) * (UNIVERSE_CARD_H + 24.0);
                let (cx, cy) = saved_coords.get(&cn_id).copied().unwrap_or((dx, dy));
                nodes.push(make_char_node(c, cx, cy));
                standalone_idx += 1;
            }
        }

        // 3) 角色 ↔ 角色关系边
        for (rid, src, dst, rtype, srole, trole) in rel_rows {
            let mut label = if rtype.trim().is_empty() {
                "关联".to_string()
            } else {
                rtype.trim().to_string()
            };
            if !srole.trim().is_empty() && !trole.trim().is_empty() {
                label = format!("{label}（{} ↔ {}）", srole.trim(), trole.trim());
            } else if !srole.trim().is_empty() {
                label = format!("{label}（{}）", srole.trim());
            }
            edges.push(CanvasEdge {
                from_id: format!("char:{src}"),
                to_id: format!("char:{dst}"),
                relation: label,
                relation_id: Some(rid),
            });
        }
    } else if mode == "seiyuu" {
        // 汇总声优与代表制作会社连接关系
        let mut person_works: HashMap<String, Vec<(String, i64, String, String)>> = HashMap::new();

        // 1. anime_characters 里的声优
        {
            let mut stmt = sync_conn.prepare(
                "SELECT ac.voiceActor, a.id, a.title, COALESCE(a.subjectType, 'anime'), c.name
                 FROM anime_characters ac
                 JOIN animes a ON a.id = ac.animeId AND a.deletedAt IS NULL
                 JOIN characters c ON c.id = ac.characterId
                 WHERE ac.voiceActor IS NOT NULL AND TRIM(ac.voiceActor) != ''",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, String>(4)?,
                ))
            })?;
            for r in rows {
                let (va_raw, id, title, subj, char_name) = r?;
                let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                    "book"
                } else {
                    "anime"
                };
                for va in split_people(&va_raw) {
                    person_works.entry(va).or_default().push((
                        kind.to_string(),
                        id,
                        title.clone(),
                        format!("饰 {char_name}"),
                    ));
                }
            }
        }

        // 2. animes.voiceActors 文本列
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, title, COALESCE(subjectType, 'anime'), voiceActors
                 FROM animes WHERE deletedAt IS NULL AND voiceActors IS NOT NULL AND TRIM(voiceActors) != ''",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                ))
            })?;
            for r in rows {
                let (id, title, subj, va_raw) = r?;
                let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                    "book"
                } else {
                    "anime"
                };
                for va in split_people(&va_raw) {
                    let list = person_works.entry(va).or_default();
                    if !list.iter().any(|(k, wid, _, _)| k == kind && *wid == id) {
                        list.push((kind.to_string(), id, title.clone(), "出演".into()));
                    }
                }
            }
        }

        // 3. audio_works.voiceActors 文本列
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, title, voiceActors, workCode FROM audio_works
                 WHERE deletedAt IS NULL AND TRIM(voiceActors) != ''",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                ))
            })?;
            for r in rows {
                let (id, title, va_raw, code) = r?;
                for va in split_people(&va_raw) {
                    person_works.entry(va).or_default().push((
                        "audio".into(),
                        id,
                        title.clone(),
                        if code.is_empty() { "主役音声".into() } else { code.clone() },
                    ));
                }
            }
        }

        let mut ranked: Vec<_> = person_works.into_iter().collect();
        ranked.sort_by(|a, b| b.1.len().cmp(&a.1.len()).then_with(|| a.0.cmp(&b.0)));

        let mut col_y = [56.0_f64, 56.0_f64];
        let col_x = [56.0_f64, 620.0_f64];

        for (cv_name, works) in ranked.into_iter().take(12) {
            let hub_id = format!("cv:{cv_name}");
            let total_works = works.len();
            let shown_works: Vec<_> = works.into_iter().take(6).collect();
            let col = if col_y[0] <= col_y[1] { 0 } else { 1 };
            let cluster_top = col_y[col];
            let cluster_h = universe_cluster_height(shown_works.len());
            let base_x = col_x[col];
            let base_y = cluster_top + ((cluster_h - UNIVERSE_CARD_H) / 2.0).max(0.0);
            let (hx, hy) = saved_coords.get(&hub_id).copied().unwrap_or((base_x, base_y));

            nodes.push(CanvasNode {
                node_id: hub_id.clone(),
                node_type: "hub".into(),
                label: cv_name,
                sublabel: format!("关联藏品 {} 部", total_works),
                badge: "声优中枢".into(),
                work_kind: None,
                work_id: None,
                image_url: None,
                x: hx,
                y: hy,
            });

            let member_count = shown_works.len();
            for (w_idx, (wkind, wid, wtitle, rel)) in shown_works.into_iter().enumerate() {
                let wn_id = format!("work:{wkind}:{wid}");
                if seen_nodes.insert(wn_id.clone()) {
                    let (wx_default, wy_default) =
                        universe_member_coords(cluster_top, base_x, member_count, w_idx);
                    let (wx, wy) = saved_coords
                        .get(&wn_id)
                        .copied()
                        .unwrap_or((wx_default, wy_default));
                    nodes.push(CanvasNode {
                        node_id: wn_id.clone(),
                        node_type: wkind.clone(),
                        label: wtitle,
                        sublabel: rel.clone(),
                        badge: carrier_label(&wkind).into(),
                        work_kind: Some(wkind),
                        work_id: Some(wid),
                        image_url: None,
                        x: wx,
                        y: wy,
                    });
                }
                edges.push(CanvasEdge {
                    from_id: hub_id.clone(),
                    to_id: wn_id,
                    relation: rel,
                    relation_id: None,
                });
            }
            col_y[col] = cluster_top + cluster_h + UNIVERSE_CLUSTER_GAP;
        }
    } else {
        // IP 企划谱系图：整合 series + linkedMediaJson + media_series
        let mut games_by_sync: HashMap<String, (i64, String, String)> = HashMap::new();
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, syncId, title, status FROM games WHERE deletedAt IS NULL",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                ))
            })?;
            for r in rows {
                let (id, sid, t, st) = r?;
                games_by_sync.insert(sid, (id, t, st));
            }
        }

        let mut audio_by_sync: HashMap<String, (i64, String, String)> = HashMap::new();
        {
            let mut stmt = sync_conn.prepare(
                "SELECT id, syncId, title, workCode FROM audio_works WHERE deletedAt IS NULL",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                ))
            })?;
            for r in rows {
                let (id, sid, t, code) = r?;
                audio_by_sync.insert(sid, (id, t, code));
            }
        }

        let mut stmt = sync_conn.prepare(
            "SELECT id, name, COALESCE(linkedMediaJson, '[]') FROM series ORDER BY id DESC LIMIT 64",
        )?;
        let series_rows: Vec<(i64, String, String)> = stmt
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<std::result::Result<Vec<_>, _>>()?;

        let mut col_y = [56.0_f64, 56.0_f64];
        let col_x = [56.0_f64, 800.0_f64];

        for (sid, sname, linked_json) in series_rows {
            let hub_id = format!("series:{sid}");

            // 查询该系列下的 animes / books
            let mut astmt = sync_conn.prepare(
                "SELECT id, title, COALESCE(subjectType, 'anime'), status, COALESCE(seriesRole, '系列成员')
                 FROM animes WHERE seriesId = ?1 AND deletedAt IS NULL ORDER BY id ASC",
            )?;
            let arows: Vec<(i64, String, String, String, String)> = astmt
                .query_map(params![sid], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
                })?
                .collect::<std::result::Result<Vec<_>, _>>()?;

            let linked_items: Vec<Value> = serde_json::from_str(&linked_json).unwrap_or_default();

            // 预收集实际有效成员总数，以准确计算簇高、避免上下企划卡片重叠
            let mut members: Vec<(String, String, String, String, String, i64, String)> = Vec::new();
            for (aid, atitle, subj, astatus, arole) in arows {
                let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                    "book"
                } else {
                    "anime"
                };
                members.push((
                    format!("work:{kind}:{aid}"),
                    kind.into(),
                    atitle,
                    format!("{arole} · {astatus}"),
                    carrier_label(kind).into(),
                    aid,
                    arole,
                ));
            }
            for item in linked_items {
                let kind_upper = item.get("kind").and_then(Value::as_str).unwrap_or("");
                let wsync = item.get("workSyncId").and_then(Value::as_str).unwrap_or("");
                let role = item
                    .get("role")
                    .and_then(Value::as_str)
                    .unwrap_or("跨载体衍生")
                    .to_string();
                if kind_upper == "GAME" {
                    if let Some((gid, gtitle, gstatus)) = games_by_sync.get(wsync) {
                        members.push((
                            format!("work:game:{gid}"),
                            "game".into(),
                            gtitle.clone(),
                            format!("{role} · {gstatus}"),
                            "游戏".into(),
                            *gid,
                            role,
                        ));
                    }
                } else if kind_upper == "AUDIO" {
                    if let Some((aid, atitle, code)) = audio_by_sync.get(wsync) {
                        members.push((
                            format!("work:audio:{aid}"),
                            "audio".into(),
                            atitle.clone(),
                            if code.is_empty() {
                                role.clone()
                            } else {
                                format!("{role} · {code}")
                            },
                            "音声".into(),
                            *aid,
                            role,
                        ));
                    }
                }
            }

            if members.is_empty() {
                continue;
            }

            let col = if col_y[0] <= col_y[1] { 0 } else { 1 };
            let cluster_top = col_y[col];
            let member_count = members.len();
            let cluster_h = universe_cluster_height(member_count);
            let base_x = col_x[col];
            let base_y = cluster_top + ((cluster_h - UNIVERSE_CARD_H) / 2.0).max(0.0);
            let (hx, hy) = saved_coords.get(&hub_id).copied().unwrap_or((base_x, base_y));

            nodes.push(CanvasNode {
                node_id: hub_id.clone(),
                node_type: "hub".into(),
                label: sname,
                sublabel: format!("跨媒体企划 · {} 项关联", member_count),
                badge: "企划核心".into(),
                work_kind: None,
                work_id: None,
                image_url: None,
                x: hx,
                y: hy,
            });

            for (member_idx, (wn_id, kind, label, sublabel, badge, work_id, relation)) in
                members.into_iter().enumerate()
            {
                if seen_nodes.insert(wn_id.clone()) {
                    let (wx_default, wy_default) =
                        universe_member_coords(cluster_top, base_x, member_count, member_idx);
                    let (wx, wy) = saved_coords
                        .get(&wn_id)
                        .copied()
                        .unwrap_or((wx_default, wy_default));
                    nodes.push(CanvasNode {
                        node_id: wn_id.clone(),
                        node_type: kind.clone(),
                        label,
                        sublabel,
                        badge,
                        work_kind: Some(kind),
                        work_id: Some(work_id),
                        image_url: None,
                        x: wx,
                        y: wy,
                    });
                }
                edges.push(CanvasEdge {
                    from_id: hub_id.clone(),
                    to_id: wn_id,
                    relation,
                    relation_id: None,
                });
            }

            col_y[col] = cluster_top + cluster_h + UNIVERSE_CLUSTER_GAP;
        }
    }

    Ok(UniverseGraph {
        mode: mode.to_string(),
        nodes,
        edges,
    })
}

pub fn save_canvas_layout(
    local_conn: &Connection,
    mode: &str,
    coords: &HashMap<String, [f64; 2]>,
) -> Result<()> {
    let key = format!("universe:{mode}");
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let payload = serde_json::to_string(coords)?;
    local_conn.execute(
        "INSERT INTO canvas_layouts (canvas_key, payload_json, updated_at)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(canvas_key) DO UPDATE SET payload_json = excluded.payload_json, updated_at = excluded.updated_at",
        params![key, payload, now],
    )?;
    Ok(())
}

/// 记录一次游玩/观看会话流水，若为游戏则同步累加 `games.playMinutes`
pub fn record_play_session(
    sync_conn: &Connection,
    local_conn: &Connection,
    work_kind: &str,
    work_id: i64,
    launcher_path: Option<&str>,
    started_at: &str,
    ended_at: &str,
    duration_seconds: i64,
    note: Option<&str>,
) -> Result<()> {
    let clean_secs = duration_seconds.max(1);
    local_conn.execute(
        "INSERT INTO play_sessions (work_kind, work_id, launcher_path, started_at, ended_at, duration_seconds, note)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
        params![work_kind, work_id, launcher_path, started_at, ended_at, clean_secs, note],
    )?;

    if work_kind == "game" {
        let added_minutes = ((clean_secs + 59) / 60).max(1);
        sync_conn.execute(
            "UPDATE games
             SET playMinutes = playMinutes + ?1,
                 status = CASE WHEN TRIM(status) IN ('想玩', '未玩') THEN '在玩' ELSE status END,
                 updatedAt = ?2
             WHERE id = ?3",
            params![added_minutes, ended_at, work_id],
        )?;
    }
    Ok(())
}

pub fn list_play_sessions(
    sync_conn: &Connection,
    local_conn: &Connection,
) -> Result<Vec<PlaySessionRow>> {
    let mut stmt = local_conn.prepare(
        "SELECT id, work_kind, work_id, launcher_path, started_at, ended_at, duration_seconds, note
         FROM play_sessions ORDER BY id DESC LIMIT 40",
    )?;
    let raw_rows: Vec<(i64, String, i64, Option<String>, String, String, i64, Option<String>)> = stmt
        .query_map([], |r| {
            Ok((
                r.get(0)?,
                r.get(1)?,
                r.get(2)?,
                r.get(3)?,
                r.get(4)?,
                r.get(5)?,
                r.get(6)?,
                r.get(7)?,
            ))
        })?
        .collect::<std::result::Result<Vec<_>, _>>()?;

    let mut out = Vec::new();
    for (id, wkind, wid, lpath, start, end, dur, note) in raw_rows {
        let title = lookup_work_title(sync_conn, &wkind, wid).unwrap_or_else(|| format!("#{wid}"));
        out.push(PlaySessionRow {
            id,
            work_kind: wkind,
            work_id: wid,
            work_title: title,
            launcher_path: lpath,
            started_at: start,
            ended_at: end,
            duration_seconds: dur,
            note,
        });
    }
    Ok(out)
}

fn lookup_work_title(sync_conn: &Connection, kind: &str, id: i64) -> Option<String> {
    let sql = match kind {
        "game" => "SELECT title FROM games WHERE id = ?1",
        "audio" => "SELECT title FROM audio_works WHERE id = ?1",
        _ => "SELECT title FROM animes WHERE id = ?1",
    };
    sync_conn
        .query_row(sql, params![id], |r| r.get::<_, String>(0))
        .optional()
        .ok()
        .flatten()
}

/// 预检 `.animeow.zip` 与当前桌面主库 `animeow.db` 的逐条差异（三栏差异对账视图数据源）
/// 仅在双方均有该条目且进度/状态/评分确实不同时标记为 `changed`；按 `changed` -> `only_remote` -> `only_local` 排序。
pub fn preview_backup_diff(sync_conn: &Connection, zip_path: &Path) -> Result<Vec<BackupDiffEntry>> {
    let file = std::fs::File::open(zip_path)?;
    let mut archive = ZipArchive::new(file)?;
    let mut db_text = String::new();
    {
        let mut db_entry = archive
            .by_name("database.json")
            .map_err(|_| StudioError::BackupFormat("备份包缺少 database.json".into()))?;
        db_entry.read_to_string(&mut db_text)?;
    }
    let root: Value = serde_json::from_str(&db_text)?;
    let mut changed_diffs = Vec::new();
    let mut only_remote_diffs = Vec::new();
    let mut only_local_diffs = Vec::new();

    // 1. 对比 animes / books（按 externalSource+externalId 优先，次按 (载体类别, 标题) 精准配对，避免同名动画与轻小说/漫画互相碰撞）
    struct LocalAnimeItem {
        title: String,
        kind_bucket: &'static str,
        kind_label: &'static str,
        status: String,
        watched_eps: i64,
        rating: Option<i64>,
        ext_key: Option<String>,
    }
    let mut local_animes: Vec<LocalAnimeItem> = Vec::new();
    let mut anime_by_ext: HashMap<String, Vec<usize>> = HashMap::new();
    let mut anime_by_title: HashMap<(String, String), Vec<usize>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT title, COALESCE(subjectType, 'anime'), status, watchedEpisodes, rating,
                    COALESCE(externalSource, ''), COALESCE(externalId, '')
             FROM animes WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, i64>(3)?,
                r.get::<_, Option<i64>>(4)?,
                r.get::<_, String>(5)?,
                r.get::<_, String>(6)?,
            ))
        })?;
        for r in rows {
            let (title, subj, st, ep, rt, ext_src, ext_id) = r?;
            let is_book = matches!(subj.trim(), "book" | "manga" | "novel");
            let kind_bucket = if is_book { "book" } else { "anime" };
            let kind_label = if is_book { "书籍" } else { "动画" };
            let ext_key = if !ext_src.trim().is_empty() && !ext_id.trim().is_empty() {
                Some(format!(
                    "{}:{}:{}",
                    kind_bucket,
                    ext_src.trim().to_lowercase(),
                    ext_id.trim().to_lowercase()
                ))
            } else {
                None
            };
            let idx = local_animes.len();
            if let Some(ref ek) = ext_key {
                anime_by_ext.entry(ek.clone()).or_default().push(idx);
            }
            anime_by_title
                .entry((kind_bucket.to_string(), title.trim().to_lowercase()))
                .or_default()
                .push(idx);
            local_animes.push(LocalAnimeItem {
                title,
                kind_bucket,
                kind_label,
                status: st,
                watched_eps: ep,
                rating: normalize_rating_opt(rt),
                ext_key,
            });
        }
    }

    let mut matched_local_animes = vec![false; local_animes.len()];
    if let Some(arr) = root.get("animes").and_then(Value::as_array) {
        for item in arr {
            if is_json_soft_deleted(item.get("deletedAt")) {
                continue;
            }
            let title = item.get("title").and_then(Value::as_str).unwrap_or("").trim();
            if title.is_empty() {
                continue;
            }
            let subj = item
                .get("subjectType")
                .and_then(Value::as_str)
                .unwrap_or("anime")
                .trim();
            let is_book = matches!(subj, "book" | "manga" | "novel");
            let kind_bucket = if is_book { "book" } else { "anime" };
            let kind_label = if is_book { "书籍" } else { "动画" };
            let r_status = item.get("status").and_then(Value::as_str).unwrap_or("未看").trim();
            let r_ep = item
                .get("watchedEpisodes")
                .and_then(Value::as_i64)
                .unwrap_or(0);
            let r_rating = parse_json_rating(item.get("rating"));
            let r_sum = format_anime_summary(r_status, r_ep, r_rating);

            let r_ext_src = item.get("externalSource").and_then(Value::as_str).unwrap_or("").trim();
            let r_ext_id = item.get("externalId").and_then(Value::as_str).unwrap_or("").trim();
            let r_ext_key = if !r_ext_src.is_empty() && !r_ext_id.is_empty() {
                Some(format!(
                    "{}:{}:{}",
                    kind_bucket,
                    r_ext_src.to_lowercase(),
                    r_ext_id.to_lowercase()
                ))
            } else {
                None
            };

            let pick_unmatched = |candidates: &[usize], matched: &[bool]| -> Option<usize> {
                candidates
                    .iter()
                    .copied()
                    .find(|&idx| {
                        !matched[idx]
                            && local_animes[idx].status.trim() == r_status
                            && local_animes[idx].watched_eps == r_ep
                            && local_animes[idx].rating == r_rating
                    })
                    .or_else(|| candidates.iter().copied().find(|&idx| !matched[idx]))
            };

            let matched_idx = r_ext_key
                .as_ref()
                .and_then(|ek| anime_by_ext.get(ek))
                .and_then(|c| pick_unmatched(c, &matched_local_animes))
                .or_else(|| {
                    anime_by_title
                        .get(&(kind_bucket.to_string(), title.to_lowercase()))
                        .and_then(|c| pick_unmatched(c, &matched_local_animes))
                });

            if let Some(idx) = matched_idx {
                matched_local_animes[idx] = true;
                let loc = &local_animes[idx];
                let _ = (loc.kind_bucket, &loc.ext_key);
                if loc.status.trim() != r_status || loc.watched_eps != r_ep || loc.rating != r_rating {
                    changed_diffs.push(BackupDiffEntry {
                        kind: kind_label.into(),
                        title: title.to_string(),
                        diff_type: "changed".into(),
                        local_summary: format_anime_summary(
                            loc.status.trim(),
                            loc.watched_eps,
                            loc.rating,
                        ),
                        remote_summary: r_sum,
                    });
                }
            } else {
                only_remote_diffs.push(BackupDiffEntry {
                    kind: kind_label.into(),
                    title: title.to_string(),
                    diff_type: "only_remote".into(),
                    local_summary: "（本机未收录）".into(),
                    remote_summary: r_sum,
                });
            }
        }
    }

    for (idx, loc) in local_animes.into_iter().enumerate() {
        if !matched_local_animes[idx] {
            only_local_diffs.push(BackupDiffEntry {
                kind: loc.kind_label.into(),
                title: loc.title,
                diff_type: "only_local".into(),
                local_summary: format_anime_summary(loc.status.trim(), loc.watched_eps, loc.rating),
                remote_summary: "（备份包未含此项）".into(),
            });
        }
    }

    // 2. 对比 games（按 syncId 优先，次按标题配对）
    struct LocalGameItem {
        title: String,
        status: String,
        play_minutes: i64,
        rating: Option<i64>,
    }
    let mut local_games: Vec<LocalGameItem> = Vec::new();
    let mut game_by_sync: HashMap<String, Vec<usize>> = HashMap::new();
    let mut game_by_title: HashMap<String, Vec<usize>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT title, status, playMinutes, rating, COALESCE(syncId, '')
             FROM games WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, Option<i64>>(3)?,
                r.get::<_, String>(4)?,
            ))
        })?;
        for r in rows {
            let (title, st, mins, rt, sync_id) = r?;
            let idx = local_games.len();
            if !sync_id.trim().is_empty() {
                game_by_sync
                    .entry(sync_id.trim().to_string())
                    .or_default()
                    .push(idx);
            }
            game_by_title
                .entry(title.trim().to_lowercase())
                .or_default()
                .push(idx);
            local_games.push(LocalGameItem {
                title,
                status: st,
                play_minutes: mins,
                rating: normalize_rating_opt(rt),
            });
        }
    }
    let mut matched_local_games = vec![false; local_games.len()];
    if let Some(arr) = root.get("games").and_then(Value::as_array) {
        for item in arr {
            if is_json_soft_deleted(item.get("deletedAt")) {
                continue;
            }
            let title = item.get("title").and_then(Value::as_str).unwrap_or("").trim();
            if title.is_empty() {
                continue;
            }
            let r_sync_id = item.get("syncId").and_then(Value::as_str).unwrap_or("").trim();
            let r_status = item.get("status").and_then(Value::as_str).unwrap_or("想玩").trim();
            let r_mins = item.get("playMinutes").and_then(Value::as_i64).unwrap_or(0);
            let r_rating = parse_json_rating(item.get("rating"));
            let r_sum = format_game_summary(r_status, r_mins, r_rating);

            let pick_game = |candidates: &[usize], matched: &[bool]| -> Option<usize> {
                candidates
                    .iter()
                    .copied()
                    .find(|&idx| {
                        !matched[idx]
                            && local_games[idx].status.trim() == r_status
                            && local_games[idx].play_minutes == r_mins
                            && local_games[idx].rating == r_rating
                    })
                    .or_else(|| candidates.iter().copied().find(|&idx| !matched[idx]))
            };

            let matched_idx = (!r_sync_id.is_empty())
                .then(|| game_by_sync.get(r_sync_id))
                .flatten()
                .and_then(|c| pick_game(c, &matched_local_games))
                .or_else(|| {
                    game_by_title
                        .get(&title.to_lowercase())
                        .and_then(|c| pick_game(c, &matched_local_games))
                });

            if let Some(idx) = matched_idx {
                matched_local_games[idx] = true;
                let loc = &local_games[idx];
                if loc.status.trim() != r_status || loc.play_minutes != r_mins || loc.rating != r_rating {
                    changed_diffs.push(BackupDiffEntry {
                        kind: "游戏".into(),
                        title: title.to_string(),
                        diff_type: "changed".into(),
                        local_summary: format_game_summary(
                            loc.status.trim(),
                            loc.play_minutes,
                            loc.rating,
                        ),
                        remote_summary: r_sum,
                    });
                }
            } else {
                only_remote_diffs.push(BackupDiffEntry {
                    kind: "游戏".into(),
                    title: title.to_string(),
                    diff_type: "only_remote".into(),
                    local_summary: "（本机未收录）".into(),
                    remote_summary: r_sum,
                });
            }
        }
    }
    for (idx, loc) in local_games.into_iter().enumerate() {
        if !matched_local_games[idx] {
            only_local_diffs.push(BackupDiffEntry {
                kind: "游戏".into(),
                title: loc.title,
                diff_type: "only_local".into(),
                local_summary: format_game_summary(loc.status.trim(), loc.play_minutes, loc.rating),
                remote_summary: "（备份包未含此项）".into(),
            });
        }
    }

    let mut diffs = Vec::with_capacity(
        (changed_diffs.len() + only_remote_diffs.len() + only_local_diffs.len()).min(2000),
    );
    for entry in changed_diffs
        .into_iter()
        .chain(only_remote_diffs)
        .chain(only_local_diffs)
        .take(2000)
    {
        diffs.push(entry);
    }
    Ok(diffs)
}

const UNIVERSE_CARD_W: f64 = 220.0;
const UNIVERSE_CARD_H: f64 = 96.0;
const UNIVERSE_ROW_GAP: f64 = 24.0;
const UNIVERSE_STEP_Y: f64 = UNIVERSE_CARD_H + UNIVERSE_ROW_GAP;
const UNIVERSE_CLUSTER_GAP: f64 = 84.0;
const UNIVERSE_HUB_TO_MEMBER_X: f64 = 280.0;
const UNIVERSE_SUBCOL_GAP_X: f64 = 252.0;

fn universe_cluster_subcols(member_count: usize) -> usize {
    if member_count <= 5 {
        1
    } else if member_count <= 12 {
        2
    } else if member_count <= 21 {
        3
    } else {
        4
    }
}

fn universe_cluster_rows(member_count: usize) -> usize {
    let cols = universe_cluster_subcols(member_count);
    member_count.max(1).div_ceil(cols)
}

fn universe_cluster_height(member_count: usize) -> f64 {
    let rows = universe_cluster_rows(member_count) as f64;
    (rows * UNIVERSE_STEP_Y - UNIVERSE_ROW_GAP).max(UNIVERSE_CARD_H)
}

fn universe_member_coords(
    cluster_top: f64,
    hub_x: f64,
    total_members: usize,
    idx: usize,
) -> (f64, f64) {
    let rows = universe_cluster_rows(total_members).max(1);
    let sub_col = idx / rows;
    let row = idx % rows;
    let x = hub_x + UNIVERSE_HUB_TO_MEMBER_X + (sub_col as f64) * UNIVERSE_SUBCOL_GAP_X;
    let y = cluster_top + (row as f64) * UNIVERSE_STEP_Y;
    (x, y)
}

fn normalize_rating_opt(v: Option<i64>) -> Option<i64> {
    v.filter(|&r| r > 0)
}

fn parse_json_rating(v: Option<&Value>) -> Option<i64> {
    let val = v?;
    let n = val
        .as_i64()
        .or_else(|| val.as_f64().map(|f| f.round() as i64))?;
    (n > 0).then_some(n)
}

fn is_json_soft_deleted(v: Option<&Value>) -> bool {
    match v {
        None | Some(Value::Null) => false,
        Some(Value::String(s)) => !s.trim().is_empty(),
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0) != 0,
        Some(Value::Bool(b)) => *b,
        _ => true,
    }
}

fn format_anime_summary(status: &str, ep: i64, rating: Option<i64>) -> String {
    match rating.filter(|&r| r > 0) {
        Some(r) => format!("{status} · 进度 {ep} · 评分 {:.1}", (r as f64) / 10.0),
        None => format!("{status} · 进度 {ep}"),
    }
}

fn format_game_summary(status: &str, mins: i64, rating: Option<i64>) -> String {
    let dur = if mins >= 60 {
        format!("{}h {}m", mins / 60, mins % 60)
    } else {
        format!("{mins}m")
    };
    match rating.filter(|&r| r > 0) {
        Some(r) => format!("{status} · {dur} · 评分 {:.1}", (r as f64) / 10.0),
        None => format!("{status} · {dur}"),
    }
}

fn split_people(raw: &str) -> Vec<String> {
    raw.split(['/', '、', ',', '，', ';', '|'])
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && s.chars().count() <= 24)
        .collect()
}

pub(crate) fn carrier_label(kind: &str) -> &'static str {
    match kind {
        "book" => "书籍",
        "game" => "游戏",
        "audio" => "音声",
        _ => "动画",
    }
}

// ============================================================================
// 回收站管理（软删除恢复与彻底清理）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrashItem {
    pub kind: String,
    pub id: i64,
    pub title: String,
    pub status: String,
    pub progress_text: String,
    pub rating: Option<f64>,
    pub deleted_at: String,
}

pub fn query_trash_items(sync_conn: &Connection) -> Result<Vec<TrashItem>> {
    let mut out = Vec::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, COALESCE(subjectType, 'anime'), status, watchedEpisodes, totalEpisodes, rating, COALESCE(deletedAt, '')
             FROM animes WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''
             ORDER BY deletedAt DESC, id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let subj: String = r.get(2)?;
            let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            let watched: i64 = r.get(4)?;
            let total: i64 = r.get(5)?;
            let rating = r
                .get::<_, Option<i64>>(6)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let progress_text = if total > 0 {
                format!("{watched} / {total}")
            } else {
                format!("{watched}")
            };
            Ok(TrashItem {
                kind,
                id: r.get(0)?,
                title: r.get(1)?,
                status: r.get(3)?,
                progress_text,
                rating,
                deleted_at: r.get(7)?,
            })
        })?;
        for row in rows {
            out.push(row?);
        }
    }
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, status, playMinutes, rating, COALESCE(deletedAt, '')
             FROM games WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''
             ORDER BY deletedAt DESC, id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let mins: i64 = r.get(3)?;
            let rating = r
                .get::<_, Option<i64>>(4)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let progress_text = if mins >= 60 {
                format!("{}h {}m", mins / 60, mins % 60)
            } else {
                format!("{mins}m")
            };
            Ok(TrashItem {
                kind: "game".into(),
                id: r.get(0)?,
                title: r.get(1)?,
                status: r.get(2)?,
                progress_text,
                rating,
                deleted_at: r.get(5)?,
            })
        })?;
        for row in rows {
            out.push(row?);
        }
    }
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, status, listenedSeconds, rating, COALESCE(deletedAt, '')
             FROM audio_works WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''
             ORDER BY deletedAt DESC, id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let secs: i64 = r.get(3)?;
            let rating = r
                .get::<_, Option<i64>>(4)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let mins = secs / 60;
            Ok(TrashItem {
                kind: "audio".into(),
                id: r.get(0)?,
                title: r.get(1)?,
                status: r.get(2)?,
                progress_text: format!("已听 {mins}m"),
                rating,
                deleted_at: r.get(5)?,
            })
        })?;
        for row in rows {
            out.push(row?);
        }
    }
    out.sort_by(|a, b| b.deleted_at.cmp(&a.deleted_at));
    Ok(out)
}

pub fn restore_trash_items(sync_conn: &mut Connection, items: &[(String, i64)]) -> Result<usize> {
    let tx = sync_conn.transaction()?;
    let mut restored = 0usize;
    for (kind, id) in items {
        let sql = match kind.as_str() {
            "game" => "UPDATE games SET deletedAt = NULL WHERE id = ?1 AND deletedAt IS NOT NULL",
            "audio" => "UPDATE audio_works SET deletedAt = NULL WHERE id = ?1 AND deletedAt IS NOT NULL",
            _ => "UPDATE animes SET deletedAt = NULL WHERE id = ?1 AND deletedAt IS NOT NULL",
        };
        restored += tx.execute(sql, [id])?;
    }
    tx.commit()?;
    Ok(restored)
}

pub fn purge_trash_items(sync_conn: &mut Connection, items: &[(String, i64)]) -> Result<usize> {
    let tx = sync_conn.transaction()?;
    let mut purged = 0usize;
    if items.is_empty() {
        purged += tx.execute("DELETE FROM animes WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''", [])?;
        purged += tx.execute("DELETE FROM games WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''", [])?;
        purged += tx.execute("DELETE FROM audio_works WHERE deletedAt IS NOT NULL AND TRIM(deletedAt) != ''", [])?;
    } else {
        for (kind, id) in items {
            let sql = match kind.as_str() {
                "game" => "DELETE FROM games WHERE id = ?1 AND deletedAt IS NOT NULL",
                "audio" => "DELETE FROM audio_works WHERE id = ?1 AND deletedAt IS NOT NULL",
                _ => "DELETE FROM animes WHERE id = ?1 AND deletedAt IS NOT NULL",
            };
            purged += tx.execute(sql, [id])?;
        }
    }
    tx.commit()?;
    Ok(purged)
}

// ============================================================================
// 重复藏品检测与安全合并（合并最优进度/评分/长评，重复项软删可回收）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateCandidate {
    pub id: i64,
    pub kind: String,
    pub title: String,
    pub original_title: String,
    pub status: String,
    pub progress_primary: i64,
    pub progress_total: i64,
    pub rating: Option<f64>,
    pub review_len: usize,
    pub external_id: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateGroup {
    pub group_key: String,
    pub kind: String,
    pub confidence: String, // "exact" | "suspected"
    pub reason: String,
    pub title: String,
    pub recommended_keep_id: i64,
    pub candidates: Vec<DuplicateCandidate>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct SeasonFingerprint {
    /// 0 表示无感叹号或单个尾感叹号（允许「孤独摇滚」与「孤独摇滚！」互认）；
    /// 2/3... 表示连续 2 个以上感叹号（隔离「xxx！」与「xxx！！」这类续季命名）；
    /// 10+ 表示含撇号+感叹号组合（如「WORKING'!!」）。
    bang_tier: u8,
    question_count: u8,
    season_marker: Option<String>,
    derivative_kind: Option<&'static str>,
}

fn to_halfwidth_lower(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.chars() {
        let mapped = match ch {
            '\u{3000}' => ' ',
            '\u{FF01}'..='\u{FF5E}' => char::from_u32((ch as u32) - 0xFEE0).unwrap_or(ch),
            '’' | '‘' => '\'',
            '・' | '•' | '·' => '·',
            '—' | '–' => '-',
            '〜' => '~',
            '×' => '×',
            _ => ch,
        };
        for lc in mapped.to_lowercase() {
            out.push(lc);
        }
    }
    out
}

fn normalize_exact_title(raw: &str) -> String {
    to_halfwidth_lower(raw)
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect()
}

fn normalize_loose_title(raw: &str) -> String {
    to_halfwidth_lower(raw)
        .chars()
        .filter(|c| {
            !c.is_whitespace()
                && !matches!(
                    c,
                    '!' | '?' | '.' | ',' | ':' | ';' | '-' | '_' | '~' | '·' | '\'' | '"'
                        | '`' | '(' | ')' | '[' | ']' | '{' | '}' | '<' | '>' | '/' | '\\'
                        | '。' | '，' | '、' | '；' | '：' | '（' | '）' | '【' | '】'
                        | '「' | '」' | '『' | '』' | '《' | '》' | '…' | '☆' | '★' | '♪'
                )
        })
        .collect()
}

fn parse_cn_or_arabic_num(s: &str) -> Option<u32> {
    if let Ok(n) = s.parse::<u32>() {
        return (1..=99).contains(&n).then_some(n);
    }
    match s {
        "一" => Some(1),
        "二" | "两" | "貳" => Some(2),
        "三" => Some(3),
        "四" => Some(4),
        "五" => Some(5),
        "六" => Some(6),
        "七" => Some(7),
        "八" => Some(8),
        "九" => Some(9),
        "十" => Some(10),
        _ => None,
    }
}

fn has_ascii_word(haystack: &str, word: &str) -> bool {
    for (idx, _) in haystack.match_indices(word) {
        let before_ok = haystack[..idx]
            .chars()
            .next_back()
            .is_none_or(|c| !c.is_ascii_alphanumeric());
        let after_ok = haystack[idx + word.len()..]
            .chars()
            .next()
            .is_none_or(|c| !c.is_ascii_alphanumeric());
        if before_ok && after_ok {
            return true;
        }
    }
    false
}

fn extract_season_fingerprint(raw: &str) -> SeasonFingerprint {
    let norm = to_halfwidth_lower(raw);
    let trimmed = norm.trim();

    // 1. 尾感叹号层级：0/1 个合并为 tier 0，2+ 个或带撇号（WORKING'!!）严格隔离
    let chars: Vec<char> = trimmed.chars().collect();
    let mut end = chars.len();
    let mut bang_count = 0usize;
    while end > 0 && chars[end - 1] == '!' {
        bang_count += 1;
        end -= 1;
    }
    let has_apostrophe_before_bang = bang_count > 0 && end > 0 && chars[end - 1] == '\'';
    let bang_tier = if has_apostrophe_before_bang {
        10u8 + (bang_count.min(9) as u8)
    } else if bang_count >= 2 {
        bang_count.min(9) as u8
    } else {
        0
    };

    // 2. 衍生形态标识（剧场版 / OVA / SP 等）
    let mut derivative_kind = if trimmed.contains("剧场版")
        || trimmed.contains("劇場版")
        || trimmed.contains("电影版")
        || has_ascii_word(trimmed, "movie")
    {
        Some("movie")
    } else if has_ascii_word(trimmed, "ova") || has_ascii_word(trimmed, "oad") {
        Some("ova")
    } else if has_ascii_word(trimmed, "ona") {
        Some("ona")
    } else if trimmed.contains("特别篇")
        || trimmed.contains("总集篇")
        || trimmed.contains("番外")
        || trimmed.contains("外传")
        || trimmed.contains("小剧场")
        || has_ascii_word(trimmed, "sp")
    {
        Some("sp")
    } else {
        None
    };

    // 3. 季数 / 续篇标识
    let mut season_marker: Option<String> = None;

    // 3a. 中文「第X季/期/部/幕/章」
    if let Some(pos) = trimmed.find('第') {
        let rest = &trimmed[pos + '第'.len_utf8()..];
        let mut num_buf = String::new();
        for ch in rest.chars() {
            if matches!(ch, '季' | '期' | '部' | '幕' | '章') {
                if let Some(n) = parse_cn_or_arabic_num(&num_buf) {
                    season_marker = Some(format!("s{n}"));
                }
                break;
            }
            if ch.is_ascii_digit()
                || matches!(ch, '一' | '二' | '三' | '四' | '五' | '六' | '七' | '八' | '九' | '十' | '两')
            {
                num_buf.push(ch);
            } else {
                break;
            }
        }
    }

    // 3b. 英文 season X / part X / cour X / s2 / 2nd / 3rd
    if season_marker.is_none() {
        for (prefix, tag) in [("season", "s"), ("part", "p"), ("cour", "c")] {
            if let Some(idx) = trimmed.find(prefix) {
                let after = trimmed[idx + prefix.len()..].trim_start_matches([' ', '-', ':']);
                let digits: String = after.chars().take_while(|c| c.is_ascii_digit()).collect();
                if let Some(n) = parse_cn_or_arabic_num(&digits) {
                    season_marker = Some(format!("{tag}{n}"));
                    break;
                }
            }
        }
    }
    if season_marker.is_none() {
        for tok in trimmed.split(|c: char| !c.is_ascii_alphanumeric()) {
            if tok.len() >= 2 && tok.starts_with('s') && tok[1..].chars().all(|c| c.is_ascii_digit()) {
                if let Some(n) = parse_cn_or_arabic_num(&tok[1..]) {
                    season_marker = Some(format!("s{n}"));
                    break;
                }
            }
            for suf in ["nd", "rd", "th"] {
                if let Some(num_part) = tok.strip_suffix(suf) {
                    if let Some(n) = parse_cn_or_arabic_num(num_part).filter(|&v| v >= 2) {
                        season_marker = Some(format!("s{n}"));
                        break;
                    }
                }
            }
            if season_marker.is_some() {
                break;
            }
        }
    }

    // 3c. 罗马数字 / 乘号 / 尾部独立数字 / 完结篇等
    if season_marker.is_none() {
        if trimmed.ends_with(" iv") || trimmed.ends_with('ⅳ') {
            season_marker = Some("s4".into());
        } else if trimmed.ends_with(" iii") || trimmed.ends_with('ⅲ') {
            season_marker = Some("s3".into());
        } else if trimmed.ends_with(" ii") || trimmed.ends_with('ⅱ') {
            season_marker = Some("s2".into());
        } else if let Some(idx) = trimmed.find('×') {
            let digits: String = trimmed[idx + '×'.len_utf8()..]
                .chars()
                .take_while(|c| c.is_ascii_digit())
                .collect();
            if !digits.is_empty() {
                season_marker = Some(format!("x{digits}"));
            }
        } else if trimmed.contains("最终季")
            || trimmed.contains("最终章")
            || trimmed.contains("完结篇")
            || trimmed.contains("final season")
        {
            season_marker = Some("final".into());
        } else if trimmed.contains("前篇") {
            season_marker = Some("part_first".into());
        } else if trimmed.contains("后篇") {
            season_marker = Some("part_second".into());
        } else if trimmed.contains("续篇") || trimmed.starts_with("续 ") || trimmed.starts_with("续·") {
            season_marker = Some("sequel".into());
        } else {
            // 末尾空格后接单个小数字（如 "某动画 2"、"某动画 3"），排除 4 位年份
            let without_bangs = trimmed.trim_end_matches(['!', '?', ' ']);
            if let Some(last_tok) = without_bangs.split_whitespace().last() {
                if without_bangs.contains(' ') && last_tok.len() == 1 {
                    if let Some(n) = parse_cn_or_arabic_num(last_tok).filter(|&v| v >= 2) {
                        season_marker = Some(format!("n{n}"));
                    }
                }
            }
        }
    }

    if season_marker.is_none() {
        let low = trimmed.to_ascii_lowercase();
        if low.contains("后半部分") || low.contains("后半") || low.contains("part 2") || low.contains("part2") || low.contains("cour 2") {
            season_marker = Some("part2".into());
        } else if low.contains("前半部分") || low.contains("前半") || low.contains("part 1") || low.contains("part1") {
            season_marker = Some("part1".into());
        } else if low.contains("终章") || low.contains("最终章") || low.contains("final") || trimmed.ends_with('完') || low.ends_with(" 完") {
            season_marker = Some("final".into());
        } else if trimmed.ends_with('续') || low.ends_with(" 续") || low.contains("after story") {
            season_marker = Some("sequel".into());
        } else if low.contains("2wei herz") || low.contains("herz") {
            season_marker = Some("2wei_herz".into());
        } else if low.contains("2wei") {
            season_marker = Some("2wei".into());
        } else if low.contains("3rei") {
            season_marker = Some("3rei".into());
        }
    }
    if derivative_kind.is_none() {
        let low = trimmed.to_ascii_lowercase();
        if low.contains("另一个世界") || low.contains("外传") || low.contains("番外") || low.contains("杏篇") || low.contains("智代篇") {
            derivative_kind = Some("spinoff");
        }
    }

    let question_count = trimmed.chars().filter(|&c| c == '?').count().min(9) as u8;

    SeasonFingerprint {
        bang_tier,
        question_count,
        season_marker,
        derivative_kind,
    }
}

fn parse_release_year_opt(raw: &str) -> Option<i32> {
    let s = raw.trim();
    if s.len() >= 4 {
        let prefix = &s[..4];
        if prefix.chars().all(|c| c.is_ascii_digit()) {
            let y: i32 = prefix.parse().ok()?;
            if (1900..=2100).contains(&y) {
                return Some(y);
            }
        }
    }
    None
}

fn bigram_dice(a: &str, b: &str) -> f64 {
    let ca: Vec<char> = a.chars().collect();
    let cb: Vec<char> = b.chars().collect();
    if ca.len() < 2 || cb.len() < 2 {
        return 0.0;
    }
    let mut counts: HashMap<(char, char), usize> = HashMap::new();
    for w in ca.windows(2) {
        *counts.entry((w[0], w[1])).or_default() += 1;
    }
    let mut inter = 0usize;
    for w in cb.windows(2) {
        if let Some(cnt) = counts.get_mut(&(w[0], w[1])) {
            if *cnt > 0 {
                *cnt -= 1;
                inter += 1;
            }
        }
    }
    (2.0 * inter as f64) / ((ca.len() - 1 + cb.len() - 1) as f64)
}

struct PreparedDupCandidate {
    cand: DuplicateCandidate,
    ext_source: Option<String>,
    exact_title: String,
    loose_title: String,
    loose_orig: String,
    fp: SeasonFingerprint,
    release_year: Option<i32>,
    series_id: Option<i64>,
    series_role: String,
}

impl PreparedDupCandidate {
    fn from_candidate_with_meta(
        cand: DuplicateCandidate,
        release_date: &str,
        series_id: Option<i64>,
        series_role: Option<String>,
    ) -> Self {
        let ext_source = cand
            .external_id
            .split_once(':')
            .map(|(s, _)| s.trim().to_lowercase())
            .filter(|s| !s.is_empty());
        let exact_title = normalize_exact_title(&cand.title);
        let loose_title = normalize_loose_title(&cand.title);
        let loose_orig = normalize_loose_title(&cand.original_title);
        let fp = extract_season_fingerprint(&cand.title);
        let release_year = parse_release_year_opt(release_date);
        let series_role = series_role.unwrap_or_default().trim().to_string();
        Self {
            cand,
            ext_source,
            exact_title,
            loose_title,
            loose_orig,
            fp,
            release_year,
            series_id,
            series_role,
        }
    }
}

/// 比较两条同载体条目，返回 `(confidence, reason)`；
/// 严格守卫：不同外部 ID、同系列不同角色、跨年发行（>=1 年）、不同总集数或不同季/篇/外传一律不误报重复。
fn match_duplicate_pair(
    a: &PreparedDupCandidate,
    b: &PreparedDupCandidate,
) -> Option<(&'static str, &'static str)> {
    // 1. 相同外部 ID → 精确重复
    if !a.cand.external_id.is_empty()
        && a.cand.external_id.eq_ignore_ascii_case(&b.cand.external_id)
    {
        return Some(("exact", "相同外部条目 ID"));
    }

    // 双方均已绑定外部 ID 且不同 → 直接判为非重复（同源不同 ID 100% 排除；跨源若年份或集数不同也排除）
    if !a.cand.external_id.is_empty() && !b.cand.external_id.is_empty() {
        if a.ext_source == b.ext_source {
            return None;
        }
        if let (Some(ya), Some(yb)) = (a.release_year, b.release_year) {
            if (ya - yb).abs() >= 1 {
                return None;
            }
        }
        if a.cand.progress_total > 0 && b.cand.progress_total > 0 && a.cand.progress_total != b.cand.progress_total {
            return None;
        }
    }

    // 同系列内不同 role（如本篇 vs 续作/剧场版/外传）→ 一律不判重
    if let (Some(sa), Some(sb)) = (a.series_id, b.series_id) {
        if sa == sb && !a.series_role.is_empty() && !b.series_role.is_empty() && a.series_role != b.series_role {
            return None;
        }
    }

    // 2. 严格同名（仅忽略大小写与空白）→ 仅在无跨年冲突时判精确重复
    if !a.exact_title.is_empty() && a.exact_title == b.exact_title {
        if let (Some(ya), Some(yb)) = (a.release_year, b.release_year) {
            if (ya - yb).abs() >= 2 {
                return None;
            }
        }
        return Some(("exact", "同载体同名重复"));
    }

    // 3. 季数与衍生作防误判守卫（如 xxx! vs xxx!!、第1季 vs 第2季、续/完/终章/后半/外传、TV vs 剧场版/OVA）
    if a.fp != b.fp {
        return None;
    }

    // 跨年发行（差 >= 1 年）或总集数明确不同 → 排除模糊标题误判
    if let (Some(ya), Some(yb)) = (a.release_year, b.release_year) {
        if (ya - yb).abs() >= 1 {
            return None;
        }
    }
    if a.cand.progress_total > 0
        && b.cand.progress_total > 0
        && a.cand.progress_total != b.cand.progress_total
    {
        return None;
    }

    // 4. 忽略标点/全半角/单尾感叹号后完全一致（如「孤独摇滚」vs「孤独摇滚！」）
    if !a.loose_title.is_empty() && a.loose_title == b.loose_title {
        return Some(("suspected", "标点/符号差异（如尾感叹号、全半角、空格）"));
    }

    // 5. 原名与中文名交叉匹配，或原名相同
    if (!a.loose_orig.is_empty() && a.loose_orig == b.loose_title)
        || (!b.loose_orig.is_empty() && b.loose_orig == a.loose_title)
        || (!a.loose_orig.is_empty() && a.loose_orig == b.loose_orig)
    {
        return Some(("suspected", "原名或别名一致"));
    }

    // 6. 名称高度相似：必须存在年份一致或总集数一致作为辅助证据，避免不同季/续篇误判
    let has_corroboration = match (a.release_year, b.release_year) {
        (Some(ya), Some(yb)) if ya == yb => true,
        _ => a.cand.progress_total > 0 && a.cand.progress_total == b.cand.progress_total,
    };
    let la = a.loose_title.chars().count();
    let lb = b.loose_title.chars().count();
    if has_corroboration && la >= 4 && lb >= 4 {
        let ratio = (la.min(lb) as f64) / (la.max(lb) as f64);
        if ratio >= 0.78 && (a.loose_title.contains(&b.loose_title) || b.loose_title.contains(&a.loose_title)) {
            return Some(("suspected", "名称高度相似且年份/集数一致"));
        }
        if bigram_dice(&a.loose_title, &b.loose_title) >= 0.86 {
            return Some(("suspected", "名称高度相似且年份/集数一致"));
        }
    }

    None
}

fn uf_find(parent: &mut [usize], mut x: usize) -> usize {
    while parent[x] != x {
        parent[x] = parent[parent[x]];
        x = parent[x];
    }
    x
}

fn uf_union(parent: &mut [usize], a: usize, b: usize) {
    let ra = uf_find(parent, a);
    let rb = uf_find(parent, b);
    if ra != rb {
        parent[rb] = ra;
    }
}

fn candidate_completeness_score(c: &DuplicateCandidate) -> (i64, i64, usize, i64) {
    let has_rating = i64::from(c.rating.is_some());
    let has_ext = i64::from(!c.external_id.is_empty());
    (c.progress_primary + has_rating * 5 + has_ext * 3, has_rating, c.review_len, -c.id)
}

pub fn query_duplicate_groups(sync_conn: &Connection) -> Result<Vec<DuplicateGroup>> {
    let mut by_kind: HashMap<String, Vec<PreparedDupCandidate>> = HashMap::new();

    // 1. 动画 / 书籍
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, COALESCE(originalTitle, ''), COALESCE(subjectType, 'anime'), status,
                    watchedEpisodes, totalEpisodes, rating, COALESCE(review, ''),
                    COALESCE(externalSource, ''), COALESCE(externalId, ''), COALESCE(createdAt, ''),
                    COALESCE(airDate, ''), seriesId, seriesRole
             FROM animes WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            let subj: String = r.get(3)?;
            let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            let ext_src: String = r.get(9)?;
            let ext_id: String = r.get(10)?;
            let review: String = r.get(8)?;
            let rating = r
                .get::<_, Option<i64>>(7)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let ext_tag = if !ext_src.trim().is_empty() && !ext_id.trim().is_empty() {
                format!("{}:{}", ext_src.trim(), ext_id.trim())
            } else {
                String::new()
            };
            let air_date: String = r.get(12)?;
            let series_id: Option<i64> = r.get(13)?;
            let series_role: Option<String> = r.get(14)?;
            Ok((
                DuplicateCandidate {
                    id: r.get(0)?,
                    kind,
                    title: r.get(1)?,
                    original_title: r.get(2)?,
                    status: r.get(4)?,
                    progress_primary: r.get(5)?,
                    progress_total: r.get(6)?,
                    rating,
                    review_len: review.chars().count(),
                    external_id: ext_tag,
                    created_at: r.get(11)?,
                },
                air_date,
                series_id,
                series_role,
            ))
        })?;
        for row in rows {
            let (cand, air_date, series_id, series_role) = row?;
            by_kind
                .entry(cand.kind.clone())
                .or_default()
                .push(PreparedDupCandidate::from_candidate_with_meta(
                    cand,
                    &air_date,
                    series_id,
                    series_role,
                ));
        }
    }

    // 2. 游戏
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, COALESCE(originalTitle, ''), status, playMinutes, rating,
                    COALESCE(review, ''), bangumiId, COALESCE(vndbId, ''), COALESCE(updatedAt, ''),
                    COALESCE(releaseDate, '')
             FROM games WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            let bgm_id: Option<i64> = r.get(7)?;
            let vndb_id: String = r.get(8)?;
            let review: String = r.get(6)?;
            let rating = r
                .get::<_, Option<i64>>(5)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let ext_tag = if let Some(bid) = bgm_id.filter(|&v| v > 0) {
                format!("bangumi:{bid}")
            } else if !vndb_id.trim().is_empty() {
                format!("vndb:{}", vndb_id.trim())
            } else {
                String::new()
            };
            let rel_date: String = r.get(10)?;
            Ok((
                DuplicateCandidate {
                    id: r.get(0)?,
                    kind: "game".into(),
                    title: r.get(1)?,
                    original_title: r.get(2)?,
                    status: r.get(3)?,
                    progress_primary: r.get(4)?,
                    progress_total: 0,
                    rating,
                    review_len: review.chars().count(),
                    external_id: ext_tag,
                    created_at: r.get(9)?,
                },
                rel_date,
            ))
        })?;
        for row in rows {
            let (cand, rel_date) = row?;
            by_kind
                .entry("game".into())
                .or_default()
                .push(PreparedDupCandidate::from_candidate_with_meta(
                    cand,
                    &rel_date,
                    None,
                    None,
                ));
        }
    }

    // 3. 音声
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, COALESCE(workCode, ''), status, listenedSeconds, durationSeconds, rating,
                    COALESCE(review, ''), COALESCE(updatedAt, ''), COALESCE(releaseDate, '')
             FROM audio_works WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            let work_code: String = r.get(2)?;
            let listened_sec: i64 = r.get(4)?;
            let duration_sec: i64 = r.get(5)?;
            let review: String = r.get(7)?;
            let rating = r
                .get::<_, Option<i64>>(6)?
                .filter(|&v| v > 0)
                .map(|v| (v as f64) / 10.0);
            let ext_tag = if !work_code.trim().is_empty() {
                format!("audio_code:{}", work_code.trim().to_uppercase())
            } else {
                String::new()
            };
            let rel_date: String = r.get(9)?;
            Ok((
                DuplicateCandidate {
                    id: r.get(0)?,
                    kind: "audio".into(),
                    title: r.get(1)?,
                    original_title: String::new(),
                    status: r.get(3)?,
                    progress_primary: listened_sec / 60,
                    progress_total: duration_sec / 60,
                    rating,
                    review_len: review.chars().count(),
                    external_id: ext_tag,
                    created_at: r.get(8)?,
                },
                rel_date,
            ))
        })?;
        for row in rows {
            let (cand, rel_date) = row?;
            by_kind
                .entry("audio".into())
                .or_default()
                .push(PreparedDupCandidate::from_candidate_with_meta(
                    cand,
                    &rel_date,
                    None,
                    None,
                ));
        }
    }

    let mut groups: Vec<DuplicateGroup> = Vec::new();
    for (kind, items) in by_kind {
        let n = items.len();
        if n < 2 {
            continue;
        }
        let mut parent: Vec<usize> = (0..n).collect();
        let mut edges: Vec<(usize, usize, &'static str, &'static str)> = Vec::new();

        for i in 0..n {
            for j in (i + 1)..n {
                if let Some((conf, reason)) = match_duplicate_pair(&items[i], &items[j]) {
                    uf_union(&mut parent, i, j);
                    edges.push((i, j, conf, reason));
                }
            }
        }

        let mut clusters: HashMap<usize, Vec<usize>> = HashMap::new();
        for i in 0..n {
            let r = uf_find(&mut parent, i);
            clusters.entry(r).or_default().push(i);
        }

        for (root, indices) in clusters {
            if indices.len() < 2 {
                continue;
            }
            let mut is_exact = false;
            let mut chosen_reason = "疑似重复";
            for &(u, _, conf, reason) in &edges {
                if uf_find(&mut parent, u) == root {
                    if conf == "exact" {
                        is_exact = true;
                        chosen_reason = reason;
                        break;
                    } else if chosen_reason == "疑似重复" {
                        chosen_reason = reason;
                    }
                }
            }

            let mut list: Vec<DuplicateCandidate> = indices.into_iter().map(|i| items[i].cand.clone()).collect();
            list.sort_by(|a, b| candidate_completeness_score(b).cmp(&candidate_completeness_score(a)));

            let mut sorted_ids: Vec<i64> = list.iter().map(|c| c.id).collect();
            sorted_ids.sort_unstable();
            let id_sig = sorted_ids
                .iter()
                .map(|id| id.to_string())
                .collect::<Vec<_>>()
                .join("-");
            let confidence = if is_exact { "exact" } else { "suspected" };
            let group_key = format!("{confidence}:{kind}:{id_sig}");

            groups.push(DuplicateGroup {
                group_key,
                kind: kind.clone(),
                confidence: confidence.to_string(),
                reason: chosen_reason.to_string(),
                title: list[0].title.clone(),
                recommended_keep_id: list[0].id,
                candidates: list,
            });
        }
    }

    groups.sort_by(|a, b| {
        let ord_a = if a.confidence == "exact" { 0 } else { 1 };
        let ord_b = if b.confidence == "exact" { 0 } else { 1 };
        ord_a
            .cmp(&ord_b)
            .then(b.candidates.len().cmp(&a.candidates.len()))
            .then(a.title.cmp(&b.title))
    });
    Ok(groups)
}

fn merge_csv_tag_union(base: &str, extra: &str) -> String {
    let mut seen = HashSet::<String>::new();
    let mut out = Vec::<String>::new();
    for raw in [base, extra] {
        for piece in raw.split([',', '，', ';']) {
            let trimmed = piece.trim();
            if trimmed.is_empty() {
                continue;
            }
            let key = trimmed.to_ascii_lowercase();
            if seen.insert(key) {
                out.push(trimmed.to_string());
            }
        }
    }
    out.join(", ")
}

fn non_blank_opt(raw: Option<String>) -> Option<String> {
    raw.map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("null"))
}

pub fn merge_duplicate_group(
    sync_conn: &mut Connection,
    kind: &str,
    keep_id: i64,
    drop_ids: &[i64],
) -> Result<usize> {
    let clean_drops: Vec<i64> = drop_ids.iter().copied().filter(|&id| id != keep_id).collect();
    if clean_drops.is_empty() {
        return Ok(0);
    }
    let now = Utc::now().to_rfc3339();
    let updated_ms = Utc::now().timestamp_millis();
    let tx = sync_conn.savepoint()?;
    let mut dropped = 0usize;

    if kind == "game" {
        let (
            mut k_mins,
            mut k_rating,
            mut k_review,
            mut k_cover,
            mut k_tags,
            mut k_orig,
            mut k_date,
            mut k_dev,
            mut k_bgm_id,
            mut k_vndb_id,
        ): (
            i64,
            Option<i64>,
            String,
            String,
            String,
            String,
            String,
            String,
            Option<i64>,
            Option<String>,
        ) = tx.query_row(
            "SELECT playMinutes, rating, COALESCE(review, ''), COALESCE(coverUrl, ''),
                    COALESCE(tags, ''), COALESCE(originalTitle, ''), COALESCE(releaseDate, ''),
                    COALESCE(developer, ''), bangumiId, vndbId
             FROM games WHERE id = ?1",
            [keep_id],
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
                ))
            },
        )?;
        k_bgm_id = k_bgm_id.filter(|&id| id > 0);
        k_vndb_id = non_blank_opt(k_vndb_id);
        for &did in &clean_drops {
            if let Ok((d_mins, d_rating, d_review, d_cover, d_tags, d_orig, d_date, d_dev, d_bgm, d_vndb)) =
                tx.query_row(
                    "SELECT playMinutes, rating, COALESCE(review, ''), COALESCE(coverUrl, ''),
                            COALESCE(tags, ''), COALESCE(originalTitle, ''), COALESCE(releaseDate, ''),
                            COALESCE(developer, ''), bangumiId, vndbId
                     FROM games WHERE id = ?1",
                    [did],
                    |r| {
                        Ok((
                            r.get::<_, i64>(0)?,
                            r.get::<_, Option<i64>>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, String>(3)?,
                            r.get::<_, String>(4)?,
                            r.get::<_, String>(5)?,
                            r.get::<_, String>(6)?,
                            r.get::<_, String>(7)?,
                            r.get::<_, Option<i64>>(8)?,
                            r.get::<_, Option<String>>(9)?,
                        ))
                    },
                )
            {
                k_mins = k_mins.max(d_mins);
                if k_rating.filter(|&v| v > 0).is_none() {
                    k_rating = d_rating.filter(|&v| v > 0);
                }
                if d_review.chars().count() > k_review.chars().count() {
                    k_review = d_review;
                }
                if k_cover.trim().is_empty() && !d_cover.trim().is_empty() {
                    k_cover = d_cover;
                }
                k_tags = merge_csv_tag_union(&k_tags, &d_tags);
                if k_orig.trim().is_empty() && !d_orig.trim().is_empty() {
                    k_orig = d_orig;
                }
                if k_date.trim().is_empty() && !d_date.trim().is_empty() {
                    k_date = d_date;
                }
                if k_dev.trim().is_empty() && !d_dev.trim().is_empty() {
                    k_dev = d_dev;
                }
                if k_bgm_id.is_none() {
                    k_bgm_id = d_bgm.filter(|&id| id > 0);
                }
                if k_vndb_id.is_none() {
                    k_vndb_id = non_blank_opt(d_vndb);
                }
            }
            // 同事务内先清空被删条目的唯一外部 ID，避免回收站幽灵占位阻塞主条目移交或后续绑定
            dropped += tx.execute(
                "UPDATE games SET deletedAt = ?1, bangumiId = NULL, vndbId = NULL, updatedAt = ?1 WHERE id = ?2",
                params![now, did],
            )?;
        }
        tx.execute(
            "UPDATE games
             SET playMinutes = ?1, rating = ?2, review = ?3, coverUrl = ?4, tags = ?5,
                 originalTitle = ?6, releaseDate = ?7, developer = ?8, bangumiId = ?9, vndbId = ?10, updatedAt = ?11
             WHERE id = ?12",
            params![
                k_mins, k_rating, k_review, k_cover, k_tags, k_orig, k_date, k_dev, k_bgm_id,
                k_vndb_id, now, keep_id
            ],
        )?;
    } else if kind == "audio" {
        let (mut k_secs, mut k_rating, mut k_review, mut k_cover, mut k_tags, mut k_orig, mut k_code, mut k_url): (
            i64,
            Option<i64>,
            String,
            String,
            String,
            String,
            String,
            String,
        ) = tx.query_row(
            "SELECT listenedSeconds, rating, COALESCE(review, ''), COALESCE(coverUrl, ''),
                    COALESCE(tags, ''), COALESCE(originalTitle, ''), COALESCE(workCode, ''), COALESCE(sourceUrl, '')
             FROM audio_works WHERE id = ?1",
            [keep_id],
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
                ))
            },
        )?;
        for &did in &clean_drops {
            if let Ok((d_secs, d_rating, d_review, d_cover, d_tags, d_orig, d_code, d_url)) =
                tx.query_row(
                    "SELECT listenedSeconds, rating, COALESCE(review, ''), COALESCE(coverUrl, ''),
                            COALESCE(tags, ''), COALESCE(originalTitle, ''), COALESCE(workCode, ''), COALESCE(sourceUrl, '')
                     FROM audio_works WHERE id = ?1",
                    [did],
                    |r| {
                        Ok((
                            r.get::<_, i64>(0)?,
                            r.get::<_, Option<i64>>(1)?,
                            r.get::<_, String>(2)?,
                            r.get::<_, String>(3)?,
                            r.get::<_, String>(4)?,
                            r.get::<_, String>(5)?,
                            r.get::<_, String>(6)?,
                            r.get::<_, String>(7)?,
                        ))
                    },
                )
            {
                k_secs = k_secs.max(d_secs);
                if k_rating.filter(|&v| v > 0).is_none() {
                    k_rating = d_rating.filter(|&v| v > 0);
                }
                if d_review.chars().count() > k_review.chars().count() {
                    k_review = d_review;
                }
                if k_cover.trim().is_empty() && !d_cover.trim().is_empty() {
                    k_cover = d_cover;
                }
                k_tags = merge_csv_tag_union(&k_tags, &d_tags);
                if k_orig.trim().is_empty() && !d_orig.trim().is_empty() {
                    k_orig = d_orig;
                }
                if k_code.trim().is_empty() && !d_code.trim().is_empty() {
                    k_code = d_code;
                }
                if k_url.trim().is_empty() && !d_url.trim().is_empty() {
                    k_url = d_url;
                }
            }
            dropped += tx.execute(
                "UPDATE audio_works SET deletedAt = ?1, updatedAt = ?1 WHERE id = ?2",
                params![now, did],
            )?;
        }
        tx.execute(
            "UPDATE audio_works
             SET listenedSeconds = ?1, rating = ?2, review = ?3, coverUrl = ?4, tags = ?5,
                 originalTitle = ?6, workCode = ?7, sourceUrl = ?8, updatedAt = ?9
             WHERE id = ?10",
            params![k_secs, k_rating, k_review, k_cover, k_tags, k_orig, k_code, k_url, now, keep_id],
        )?;
    } else {
        let (
            mut k_ep,
            mut k_total,
            mut k_rating,
            mut k_review,
            mut k_memo,
            mut k_cover,
            mut k_orig,
            mut k_air_date,
            mut k_studio,
            mut k_media_format,
            mut k_ext_source,
            mut k_ext_id,
            mut k_ext_url,
        ): (
            i64,
            i64,
            Option<i64>,
            String,
            String,
            String,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<String>,
        ) = tx.query_row(
            "SELECT watchedEpisodes, totalEpisodes, rating, COALESCE(review, ''), COALESCE(memo, ''),
                    COALESCE(coverUrl, ''), originalTitle, airDate, studio, mediaFormat,
                    externalSource, externalId, externalUrl
             FROM animes WHERE id = ?1",
            [keep_id],
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
        )?;
        k_orig = non_blank_opt(k_orig);
        k_air_date = non_blank_opt(k_air_date);
        k_studio = non_blank_opt(k_studio);
        k_media_format = non_blank_opt(k_media_format);
        k_ext_source = non_blank_opt(k_ext_source);
        k_ext_id = non_blank_opt(k_ext_id);
        k_ext_url = non_blank_opt(k_ext_url);

        for &did in &clean_drops {
            // 合并标签取并集，避免仅保留主条目而丢失副条目已有标签
            tx.execute(
                "INSERT OR IGNORE INTO anime_tags (animeId, tagId)
                 SELECT ?1, tagId FROM anime_tags WHERE animeId = ?2",
                params![keep_id, did],
            )?;
            if let Ok((
                d_ep,
                d_total,
                d_rating,
                d_review,
                d_memo,
                d_cover,
                d_orig,
                d_air_date,
                d_studio,
                d_media_format,
                d_ext_source,
                d_ext_id,
                d_ext_url,
            )) = tx.query_row(
                "SELECT watchedEpisodes, totalEpisodes, rating, COALESCE(review, ''), COALESCE(memo, ''),
                        COALESCE(coverUrl, ''), originalTitle, airDate, studio, mediaFormat,
                        externalSource, externalId, externalUrl
                 FROM animes WHERE id = ?1",
                [did],
                |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, Option<i64>>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, String>(4)?,
                        r.get::<_, String>(5)?,
                        r.get::<_, Option<String>>(6)?,
                        r.get::<_, Option<String>>(7)?,
                        r.get::<_, Option<String>>(8)?,
                        r.get::<_, Option<String>>(9)?,
                        r.get::<_, Option<String>>(10)?,
                        r.get::<_, Option<String>>(11)?,
                        r.get::<_, Option<String>>(12)?,
                    ))
                },
            ) {
                k_ep = k_ep.max(d_ep);
                k_total = k_total.max(d_total);
                if k_rating.filter(|&v| v > 0).is_none() {
                    k_rating = d_rating.filter(|&v| v > 0);
                }
                if d_review.chars().count() > k_review.chars().count() {
                    k_review = d_review;
                }
                if d_memo.chars().count() > k_memo.chars().count() {
                    k_memo = d_memo;
                }
                if k_cover.trim().is_empty() && !d_cover.trim().is_empty() {
                    k_cover = d_cover;
                }
                if k_orig.is_none() {
                    k_orig = non_blank_opt(d_orig);
                }
                if k_air_date.is_none() {
                    k_air_date = non_blank_opt(d_air_date);
                }
                if k_studio.is_none() {
                    k_studio = non_blank_opt(d_studio);
                }
                if k_media_format.is_none() {
                    k_media_format = non_blank_opt(d_media_format);
                }
                if k_ext_id.is_none() {
                    if let (Some(src), Some(eid)) = (non_blank_opt(d_ext_source), non_blank_opt(d_ext_id)) {
                        k_ext_source = Some(src);
                        k_ext_id = Some(eid);
                        if k_ext_url.is_none() {
                            k_ext_url = non_blank_opt(d_ext_url);
                        }
                    }
                }
            }
            // 同事务内显式清空被合并条目的 externalSource/externalId/externalUrl，杜绝回收站幽灵占位
            dropped += tx.execute(
                "UPDATE animes
                 SET deletedAt = ?1, externalSource = NULL, externalId = NULL, externalUrl = NULL, updatedAt = ?2
                 WHERE id = ?3",
                params![now, updated_ms, did],
            )?;
        }
        tx.execute(
            "UPDATE animes
             SET watchedEpisodes = ?1, totalEpisodes = ?2, rating = ?3, review = ?4, memo = ?5,
                 coverUrl = ?6, originalTitle = ?7, airDate = ?8, studio = ?9, mediaFormat = ?10,
                 externalSource = ?11, externalId = ?12, externalUrl = ?13, updatedAt = ?14
             WHERE id = ?15",
            params![
                k_ep,
                k_total,
                k_rating,
                k_review,
                k_memo,
                k_cover,
                k_orig,
                k_air_date,
                k_studio,
                k_media_format,
                k_ext_source,
                k_ext_id,
                k_ext_url,
                updated_ms,
                keep_id
            ],
        )?;
    }

    tx.commit()?;
    Ok(dropped)
}

// ============================================================================
// 同系列自动归类建议（跨载体识别续作、剧场版、原作轻小说/漫画、已有系列延伸）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeriesSuggestionMember {
    pub id: i64,
    pub kind: String,
    pub title: String,
    pub status: String,
    pub current_series_name: Option<String>,
    pub suggested_role: String,
    pub already_grouped: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeriesSuggestionGroup {
    pub group_key: String,
    pub series_name: String,
    pub existing_series: bool,
    pub reason: String,
    pub unassigned_count: usize,
    pub members: Vec<SeriesSuggestionMember>,
}

const GENERIC_SERIES_STOPWORDS: &[&str] = &[
    "关于", "关于我", "我的", "我们的", "你的", "她的", "这个", "那个", "这样",
    "魔法", "魔法少女", "异世界", "青春", "恋爱", "少女", "少年", "世界",
    "物语", "物语系列", "剧场版", "电影版", "新世纪", "续", "真", "新",
    "the", "my", "our", "new", "re", "super", "hyper", "ultra",
];

fn is_generic_series_root(key: &str) -> bool {
    let trimmed = key.trim();
    if trimmed.is_empty() {
        return true;
    }
    let lower = trimmed.to_lowercase();
    if GENERIC_SERIES_STOPWORDS.contains(&lower.as_str()) {
        return true;
    }
    let cjk_count = trimmed
        .chars()
        .filter(|&c| ('\u{4e00}'..='\u{9fff}').contains(&c) || ('\u{3040}'..='\u{30ff}').contains(&c))
        .count();
    let alnum_count = trimmed.chars().filter(|c| c.is_alphanumeric()).count();
    if cjk_count > 0 {
        cjk_count < 2
    } else {
        alnum_count < 4
    }
}

/// 从标题提取“剥离季数/衍生标签/尾标点”后的干净系列根展示名（保留原大小写）
fn strip_series_suffixes(raw: &str) -> String {
    let mut s = raw
        .trim()
        .replace('【', " ")
        .replace('】', " ")
        .replace('「', " ")
        .replace('」', " ")
        .replace('『', " ")
        .replace('』', " ");
    // 去掉开头的“剧场版 ” / “电影版 ” / “续·”
    loop {
        let trimmed = s.trim_start();
        let next = trimmed
            .strip_prefix("剧场版")
            .or_else(|| trimmed.strip_prefix("电影版"))
            .or_else(|| trimmed.strip_prefix("OVA "))
            .or_else(|| trimmed.strip_prefix("续·"))
            .or_else(|| trimmed.strip_prefix("续 "));
        if let Some(rest) = next {
            let clean = rest.trim_start_matches([' ', '·', '：', ':', '-', '—', '　']);
            if !clean.is_empty() {
                s = clean.to_string();
                continue;
            }
        }
        break;
    }

    // 先剥离标题中段、位于副标题分隔符前的季数词（如「无职转生 第二季 ～到了异世界…」→「无职转生 ～到了异世界…」）
    static MID_SEASON_RE: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(
            r"(?i)(?:[\s　]+|^)(?:第[0-9一二三四五六七八九十两]{1,3}(?:季|部分|部|期|章|弹)|(?:2nd|3rd|4th|5th)\s+season|season\s*\d{1,2}|part\s*\d{1,2}|s[2-9])([\s　]*[～~:：\-—])",
        )
        .unwrap()
    });
    s = MID_SEASON_RE.replace_all(&s, "$1").into_owned();

    // 剥离尾部括号说明如 "(前)" "(后)" "(上)" "(下)" "（TV）" "（全12话）"
    loop {
        let t = s.trim_end().to_string();
        let mut stripped = None;
        for (open, close) in [('(', ')'), ('（', '）'), ('[', ']')] {
            if t.ends_with(close) {
                if let Some(pos) = t.rfind(open) {
                    let inner = t[pos + open.len_utf8()..t.len() - close.len_utf8()].trim();
                    if inner.chars().count() <= 10 && pos > 0 {
                        stripped = Some(t[..pos].trim_end().to_string());
                        break;
                    }
                }
            }
        }
        if let Some(next) = stripped {
            if !next.is_empty() {
                s = next;
                continue;
            }
        }
        break;
    }

    // 反复剥离末尾的季数/部分/衍生词/尾标点/罗马数字/小数字
    let tail_keywords = [
        "最终季", "最终章", "完结篇", "特别篇", "番外篇", "总集篇", "前篇", "后篇", "上篇", "下篇",
        "续篇", "新篇", "剧场版", "电影版", "外传", "前传", "广播剧", "轻小说", "漫画版", "小说版",
        "原声集", "设定集", "画集", "系列", "final season", "the final", "ova", "oad", "special",
        "movie", "recap", "ost", "sp",
    ];
    loop {
        let before = s.trim().to_string();
        let mut cur = before
            .trim_end_matches(['!', '！', '?', '？', '\'', '’', '′', '~', '～', '·', '-', '—', '/', ':', '：', ' ', '　'])
            .to_string();

        // 剥离 "第X季/部/期/部分/章/弹"
        if let Some(idx) = cur.rfind('第') {
            let tail = &cur[idx..];
            let tail_chars: Vec<char> = tail.chars().collect();
            let valid_season_tail = tail_chars.len() >= 3
                && tail_chars.len() <= 7
                && (tail.ends_with('季')
                    || tail.ends_with("部分")
                    || tail.ends_with('部')
                    || tail.ends_with('期')
                    || tail.ends_with('章')
                    || tail.ends_with('弹'));
            if valid_season_tail && idx > 0 {
                cur = cur[..idx].trim_end().to_string();
            }
        }

        // 剥离末尾衍生词与英文季数词
        let lower_cur = cur.to_lowercase();
        for kw in &tail_keywords {
            if lower_cur.ends_with(kw) && cur.len() > kw.len() {
                let cut = cur.len() - kw.len();
                if cur.is_char_boundary(cut) {
                    let prefix = cur[..cut].trim_end();
                    let is_ascii_kw = kw.is_ascii();
                    let boundary_ok = !is_ascii_kw
                        || prefix.is_empty()
                        || cur[..cut].ends_with(|c: char| !c.is_ascii_alphanumeric());
                    if boundary_ok && !prefix.is_empty() {
                        cur = prefix.to_string();
                        break;
                    }
                }
            }
        }

        // 剥离末尾 "Season N" / "Part N" / "Cour N" / "S2" / "2nd" / "3rd" / "×2" / 罗马数字 / 单数字
        let lower_cur = cur.to_lowercase();
        let mut cut_token = false;
        if let Some(last_ws) = cur.rfind(|c: char| c.is_whitespace() || c == '　') {
            let last_tok = cur[last_ws..].trim();
            let lt = last_tok.to_lowercase();
            let is_roman = matches!(last_tok, "II" | "III" | "IV" | "V" | "VI" | "Ⅱ" | "Ⅲ" | "Ⅳ");
            let is_s_num = lt.starts_with('s')
                && lt.len() >= 2
                && lt.len() <= 3
                && lt[1..].chars().all(|c| c.is_ascii_digit());
            let is_ord = matches!(lt.as_str(), "2nd" | "3rd" | "4th" | "5th");
            let is_x_num = (lt.starts_with('x') || lt.starts_with('×'))
                && lt.chars().skip(1).all(|c| c.is_ascii_digit())
                && lt.chars().count() <= 3;
            let is_small_digit = last_tok.len() == 1 && last_tok.chars().all(|c| c.is_ascii_digit());
            if (is_roman || is_s_num || is_ord || is_x_num || is_small_digit) && last_ws > 0 {
                cur = cur[..last_ws].trim_end().to_string();
                cut_token = true;
            } else if last_tok.chars().all(|c| c.is_ascii_digit()) && last_tok.len() <= 2 {
                let prev = cur[..last_ws].trim_end();
                let prev_lower = prev.to_lowercase();
                for marker in ["season", "part", "cour", "act", "vol.", "vol"] {
                    if prev_lower.ends_with(marker) && prev.len() > marker.len() {
                        cur = prev[..prev.len() - marker.len()].trim_end().to_string();
                        cut_token = true;
                        break;
                    }
                }
            }
        }
        if !cut_token {
            for rom in ["Ⅱ", "Ⅲ", "Ⅳ"] {
                if cur.ends_with(rom) && cur.len() > rom.len() {
                    cur = cur[..cur.len() - rom.len()].trim_end().to_string();
                    break;
                }
            }
            // 末尾直接紧贴的 “2nd Season” 等
            for suf in ["2nd season", "3rd season", "4th season"] {
                if lower_cur.ends_with(suf) && cur.len() > suf.len() {
                    let cut = cur.len() - suf.len();
                    if cur.is_char_boundary(cut) {
                        cur = cur[..cut].trim_end().to_string();
                    }
                    break;
                }
            }
        }

        cur = cur.trim().to_string();
        if cur == before || cur.is_empty() {
            return if cur.is_empty() { before } else { cur };
        }
        s = cur;
    }
}

/// 提取副标题分隔符之前的主系列名（如「无职转生 ～到了异世界…」「Fate/Zero」「某系列：副标题」）
fn extract_subtitle_prefix(clean_base: &str) -> Option<String> {
    let delimiters = [" ～", " ~", "～", "~", "：", ":", " - ", " — ", "——", "—", "／", "/"];
    let mut best_pos: Option<usize> = None;
    for delim in delimiters {
        if let Some(pos) = clean_base.find(delim) {
            if pos > 0 && best_pos.map_or(true, |b| pos < b) {
                best_pos = Some(pos);
            }
        }
    }
    let pos = best_pos?;
    let raw_prefix = clean_base[..pos].trim();
    let prefix = strip_series_suffixes(raw_prefix);
    let prefix = prefix.trim();
    let cjk = prefix
        .chars()
        .filter(|&c| ('\u{4e00}'..='\u{9fff}').contains(&c) || ('\u{3040}'..='\u{30ff}').contains(&c))
        .count();
    let alnum = prefix.chars().filter(|c| c.is_alphanumeric()).count();
    if ((cjk >= 3) || (cjk == 0 && alnum >= 4)) && !is_generic_series_root(prefix) {
        Some(prefix.to_string())
    } else {
        None
    }
}

/// 提取「凉宫春日的忧郁 / 凉宫春日的消失」这类「主名+的+副词」中的主名（要求 ≥ 4 个汉字且非泛词）
fn extract_possessive_prefix(clean_base: &str) -> Option<String> {
    let idx = clean_base.find('的')?;
    let prefix = clean_base[..idx].trim();
    let tail = clean_base[idx + '的'.len_utf8()..].trim();
    if tail.is_empty() {
        return None;
    }
    let cjk = prefix
        .chars()
        .filter(|&c| ('\u{4e00}'..='\u{9fff}').contains(&c))
        .count();
    if cjk >= 4 && prefix.chars().count() <= 8 && !is_generic_series_root(prefix) {
        Some(prefix.to_string())
    } else {
        None
    }
}

/// 剥离紧贴中文末尾的小数/卷号（如「安达与岛村99.9」→「安达与岛村」），仅在存在同名兄弟作品时使用
fn strip_trailing_attached_number(clean_base: &str) -> Option<String> {
    let trimmed = clean_base.trim_end();
    let num_start = trimmed
        .rfind(|c: char| !(c.is_ascii_digit() || c == '.'))
        .map(|i| {
            let ch = trimmed[i..].chars().next().unwrap();
            i + ch.len_utf8()
        })?;
    if num_start == 0 || num_start >= trimmed.len() {
        return None;
    }
    let prefix = trimmed[..num_start].trim_end();
    let suffix = &trimmed[num_start..];
    if suffix.chars().any(|c| c.is_ascii_digit()) && !is_generic_series_root(prefix) {
        Some(prefix.to_string())
    } else {
        None
    }
}

fn infer_series_role(kind: &str, title: &str) -> String {
    let fp = extract_season_fingerprint(title);
    match kind {
        "book" => "原作/书籍".into(),
        "game" => "衍生游戏".into(),
        "audio" => "音声/OST".into(),
        _ => {
            if let Some(dk) = fp.derivative_kind.as_deref() {
                match dk {
                    "movie" => "剧场版".into(),
                    "ova" => "OVA/OAD".into(),
                    "sp" => "特别篇/番外".into(),
                    "recap" => "总集篇".into(),
                    "spin" => "外传/前传".into(),
                    _ => "衍生作".into(),
                }
            } else if fp.season_marker.is_some() || fp.bang_tier >= 2 {
                "续作".into()
            } else {
                "本篇".into()
            }
        }
    }
}

struct SeriesScanWork {
    id: i64,
    kind: String,
    title: String,
    status: String,
    current_series_name: Option<String>,
    clean_base: String,
    base_key: String,
    sub_prefix: Option<(String, String)>,
    poss_prefix: Option<(String, String)>,
    num_stripped: Option<(String, String)>,
}

fn title_starts_with_series_anchor_raw(raw_title: &str, raw_anchor: &str) -> bool {
    let title_norm = normalize_loose_title(raw_title);
    let anchor_norm = normalize_loose_title(raw_anchor);
    if anchor_norm.is_empty() || title_norm.is_empty() {
        return false;
    }
    if title_norm == anchor_norm {
        return true;
    }
    if !title_norm.starts_with(&anchor_norm) {
        return false;
    }
    // 锚点若为纯 ASCII（如 fate / clannad），在原始字符串上检查词边界：
    // 放行 Fate/stay night、Fate Zero、Clannad ~After Story~，拦截 Fated 等同前缀无关英文单词
    if anchor_norm.is_ascii() {
        if anchor_norm.len() < 4 {
            return false;
        }
        let raw_t = raw_title
            .trim()
            .trim_start_matches(['【', '「', '『', '(', '（', '['])
            .to_lowercase();
        let raw_a = raw_anchor.trim().to_lowercase();
        if let Some(rest) = raw_t.strip_prefix(&raw_a) {
            if let Some(next_ch) = rest.chars().next() {
                if next_ch.is_ascii_alphabetic() {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
    if anchor_norm.chars().count() < 3 {
        return false;
    }
    true
}

fn title_starts_with_series_anchor(title_norm: &str, anchor_norm: &str) -> bool {
    if title_norm == anchor_norm {
        return true;
    }
    if !title_norm.starts_with(anchor_norm) {
        return false;
    }
    let rest = &title_norm[anchor_norm.len()..];
    // 锚点若为纯 ASCII（如 fate / clannad），避免命中同前缀英文长词（如 fated）
    if anchor_norm.is_ascii() {
        if let Some(next_ch) = rest.chars().next() {
            if next_ch.is_ascii_alphabetic() {
                return false;
            }
        }
    }
    true
}

/// 扫描全库藏品，自动识别属于同一系列的作品（含加入已有系列与新聚类系列）
pub fn query_series_suggestions(sync_conn: &Connection) -> Result<Vec<SeriesSuggestionGroup>> {
    // 1. 加载已有系列与跨媒体关联
    let mut existing_series: Vec<(i64, String, String)> = Vec::new(); // (id, name, norm_key)
    let mut series_name_by_id: HashMap<i64, String> = HashMap::new();
    let mut linked_sync_to_series: HashMap<(String, String), String> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, name, COALESCE(linkedMediaJson, '[]') FROM series ORDER BY id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?))
        })?;
        for row in rows {
            let (sid, name, linked_json) = row?;
            let clean = name.trim().to_string();
            if clean.is_empty() {
                continue;
            }
            series_name_by_id.insert(sid, clean.clone());
            let norm = normalize_loose_title(&clean);
            if !is_generic_series_root(&clean) && !norm.is_empty() {
                existing_series.push((sid, clean.clone(), norm));
            }
            if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&linked_json) {
                for item in arr {
                    if let (Some(k), Some(wsid)) = (
                        item.get("kind").and_then(Value::as_str),
                        item.get("workSyncId").and_then(Value::as_str),
                    ) {
                        linked_sync_to_series
                            .insert((k.to_lowercase(), wsid.to_string()), clean.clone());
                    }
                }
            }
        }
    }
    // 更长的已有系列名优先匹配，防止短名截胡
    existing_series.sort_by(|a, b| b.2.chars().count().cmp(&a.2.chars().count()));

    // 2. 加载全库未删除藏品（动画、书籍、游戏、音声）
    let mut works: Vec<SeriesScanWork> = Vec::new();
    let build_work = |id: i64, kind: String, title: String, status: String, current_series_name: Option<String>| {
        let clean_base = strip_series_suffixes(&title);
        let base_key = normalize_loose_title(&clean_base);
        let sub_prefix = extract_subtitle_prefix(&clean_base).and_then(|p| {
            let k = normalize_loose_title(&p);
            if k.is_empty() || k == base_key { None } else { Some((p, k)) }
        });
        let poss_prefix = extract_possessive_prefix(&clean_base).and_then(|p| {
            let k = normalize_loose_title(&p);
            if k.is_empty() || k == base_key { None } else { Some((p, k)) }
        });
        let num_stripped = strip_trailing_attached_number(&clean_base).and_then(|p| {
            let k = normalize_loose_title(&p);
            if k.is_empty() || k == base_key { None } else { Some((p, k)) }
        });
        SeriesScanWork {
            id,
            kind,
            title,
            status,
            current_series_name,
            clean_base,
            base_key,
            sub_prefix,
            poss_prefix,
            num_stripped,
        }
    };

    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, COALESCE(subjectType, 'anime'), status, seriesId
             FROM animes WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, Option<i64>>(4)?,
            ))
        })?;
        for row in rows {
            let (id, title, subj, status, sid) = row?;
            let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            let current_series = sid.and_then(|id| series_name_by_id.get(&id).cloned());
            works.push(build_work(id, kind, title, status, current_series));
        }
    }
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, status, COALESCE(syncId, '') FROM games WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })?;
        for row in rows {
            let (id, title, status, sync_id) = row?;
            let current_series = linked_sync_to_series.get(&("game".into(), sync_id)).cloned();
            works.push(build_work(id, "game".into(), title, status, current_series));
        }
    }
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, title, status, COALESCE(syncId, '') FROM audio_works WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })?;
        for row in rows {
            let (id, title, status, sync_id) = row?;
            let current_series = linked_sync_to_series.get(&("audio".into(), sync_id)).cloned();
            works.push(build_work(id, "audio".into(), title, status, current_series));
        }
    }

    // 3. 统计各候选根词出现次数与不同作品标题数，用于判断是否构成有效系列
    let mut base_key_counts: HashMap<String, usize> = HashMap::new();
    let mut sub_key_counts: HashMap<String, usize> = HashMap::new();
    let mut sub_key_distinct_bases: HashMap<String, HashSet<String>> = HashMap::new();
    let mut poss_key_counts: HashMap<String, usize> = HashMap::new();
    let mut poss_key_distinct_bases: HashMap<String, HashSet<String>> = HashMap::new();
    let mut num_key_counts: HashMap<String, usize> = HashMap::new();
    let mut existing_member_counts: HashMap<String, usize> = HashMap::new();
    for w in &works {
        if let Some(ref cur) = w.current_series_name {
            *existing_member_counts.entry(cur.clone()).or_default() += 1;
        }
        if !w.base_key.is_empty() && !is_generic_series_root(&w.clean_base) {
            *base_key_counts.entry(w.base_key.clone()).or_default() += 1;
        }
        if let Some((_, ref k)) = w.sub_prefix {
            *sub_key_counts.entry(k.clone()).or_default() += 1;
            sub_key_distinct_bases.entry(k.clone()).or_default().insert(w.base_key.clone());
        }
        if let Some((_, ref k)) = w.poss_prefix {
            *poss_key_counts.entry(k.clone()).or_default() += 1;
            poss_key_distinct_bases.entry(k.clone()).or_default().insert(w.base_key.clone());
        }
        if let Some((_, ref k)) = w.num_stripped {
            *num_key_counts.entry(k.clone()).or_default() += 1;
        }
    }

    // 3b. 检测「已被拆成多个不同名字的同系列」并映射到最规范/人数最多的目标系列名
    //     例如：
    //     - 「无职转生 第二季 ～到了异世界就拿出真本事」→「无职转生～到了异世界就拿出真本事」
    //     - 「凉宫春日的忧郁」→「凉宫春日」
    //     - 「Fate/stay night [Unlimited Blade Works]」→「fate」
    let mut series_merge_target: HashMap<String, String> = HashMap::new();
    {
        struct SeriesMeta {
            name: String,
            clean_base: String,
            base_key: String,
            sub_key: Option<String>,
            poss_key: Option<String>,
            count: usize,
        }
        let mut metas: Vec<SeriesMeta> = existing_series
            .iter()
            .filter_map(|(_, sname, _)| {
                let cnt = existing_member_counts.get(sname).copied().unwrap_or(0);
                if cnt == 0 {
                    return None;
                }
                let cb = strip_series_suffixes(sname);
                let bk = normalize_loose_title(&cb);
                let sk = extract_subtitle_prefix(&cb)
                    .map(|p| normalize_loose_title(&p))
                    .filter(|k| !k.is_empty());
                let pk = extract_possessive_prefix(&cb)
                    .map(|p| normalize_loose_title(&p))
                    .filter(|k| !k.is_empty());
                Some(SeriesMeta {
                    name: sname.clone(),
                    clean_base: cb,
                    base_key: bk,
                    sub_key: sk,
                    poss_key: pk,
                    count: cnt,
                })
            })
            .collect();
        // 规范目标优先级：更短干净根名优先，其次成员更多
        metas.sort_by(|a, b| {
            a.clean_base
                .chars()
                .count()
                .cmp(&b.clean_base.chars().count())
                .then_with(|| b.count.cmp(&a.count))
        });

        for i in 0..metas.len() {
            for j in (i + 1)..metas.len() {
                let short_m = &metas[i];
                let long_m = &metas[j];
                if series_merge_target.contains_key(&long_m.name) {
                    continue;
                }
                let same_base = !short_m.base_key.is_empty() && short_m.base_key == long_m.base_key;
                let same_sub = short_m.sub_key.is_some() && short_m.sub_key == long_m.sub_key;
                let same_poss = short_m.poss_key.is_some() && short_m.poss_key == long_m.poss_key;
                let prefix_hit =
                    title_starts_with_series_anchor_raw(&long_m.name, &short_m.name)
                        || title_starts_with_series_anchor_raw(&long_m.clean_base, &short_m.clean_base);
                if same_base || same_sub || same_poss || prefix_hit {
                    // 若二者长度相近（如同一带副标题系列），优先保留成员数更多且不含季数词的那一个
                    let target_name = if same_base
                        && long_m.count > short_m.count
                        && !long_m.name.contains("第")
                    {
                        long_m.name.clone()
                    } else {
                        let root_target = series_merge_target
                            .get(&short_m.name)
                            .cloned()
                            .unwrap_or_else(|| short_m.name.clone());
                        root_target
                    };
                    if target_name != long_m.name {
                        series_merge_target.insert(long_m.name.clone(), target_name);
                    }
                }
            }
        }
    }

    // 规范锚点列表：排除待被合并掉的拆分系列，且按短锚点合并后较长名称优先匹配（先精后泛）
    let canonical_existing_series: Vec<(i64, String, String)> = existing_series
        .iter()
        .filter(|(_, sname, _)| !series_merge_target.contains_key(sname))
        .cloned()
        .collect();

    // 4. 为每部作品确定归属桶：(bucket_key, display_name, existing_series, reason)
    struct BucketAccum {
        series_name: String,
        existing_series: bool,
        reason: String,
        member_indices: Vec<usize>,
    }
    let mut buckets: HashMap<String, BucketAccum> = HashMap::new();

    for (idx, w) in works.iter().enumerate() {
        // 4a. 若已归入某系列：
        //     - 若该系列属于被拆分的重复系列，则归并到规范目标系列桶（在生成成员时标为待并入）
        //     - 否则作为已有系列的参照成员放入原桶
        if let Some(ref cur_series) = w.current_series_name {
            let target_series = series_merge_target
                .get(cur_series)
                .cloned()
                .unwrap_or_else(|| cur_series.clone());
            let is_merge_split = target_series != *cur_series;
            let key = format!("existing:{}", normalize_loose_title(&target_series));
            let acc = buckets.entry(key).or_insert_with(|| BucketAccum {
                series_name: target_series.clone(),
                existing_series: true,
                reason: if is_merge_split {
                    "检测到同系列被拆分为多个系列，建议一键合并".into()
                } else {
                    "匹配到已有系列企划，建议将同系列未归类作品并入".into()
                },
                member_indices: Vec::new(),
            });
            if is_merge_split {
                acc.reason = "检测到同系列被拆分为多个系列，建议一键合并".into();
            }
            acc.member_indices.push(idx);
            continue;
        }

        // 4b. 未归类作品优先尝试匹配规范已有系列锚点（支持原始词边界 + 剥离季数后的根名）
        let title_norm = normalize_loose_title(&w.title);
        let mut matched_existing: Option<String> = None;
        for (_, sname, snorm) in &canonical_existing_series {
            let s_clean = strip_series_suffixes(sname);
            let s_sub = extract_subtitle_prefix(&s_clean);
            let hit = title_starts_with_series_anchor_raw(&w.title, sname)
                || title_starts_with_series_anchor_raw(&w.clean_base, &s_clean)
                || title_starts_with_series_anchor(&w.base_key, snorm)
                || title_starts_with_series_anchor(&title_norm, snorm)
                || s_sub.as_deref().is_some_and(|sp| {
                    title_starts_with_series_anchor_raw(&w.clean_base, sp)
                        || w.sub_prefix.as_ref().is_some_and(|(_, wk)| wk == &normalize_loose_title(sp))
                });
            if hit {
                matched_existing = Some(sname.clone());
                break;
            }
        }
        if let Some(sname) = matched_existing {
            let key = format!("existing:{}", normalize_loose_title(&sname));
            buckets
                .entry(key)
                .or_insert_with(|| BucketAccum {
                    series_name: sname,
                    existing_series: true,
                    reason: "匹配到已有系列企划，建议将同系列未归类作品并入".into(),
                    member_indices: Vec::new(),
                })
                .member_indices
                .push(idx);
            continue;
        }

        // 4c. 优先归并到覆盖 ≥ 2 部不同主标题的共享父系列根（防止「凉宫春日的忧郁」与「凉宫春日」被拆成两个系列），
        //     其次再按基础系列名聚簇
        let chosen: Option<(String, String, &'static str)> = None
            .or_else(|| {
                if let Some((ref disp, ref k)) = w.sub_prefix {
                    let distinct_cnt = sub_key_distinct_bases
                        .get(k)
                        .map(HashSet::len)
                        .unwrap_or(0)
                        + usize::from(base_key_counts.get(k).copied().unwrap_or(0) > 0);
                    if distinct_cnt >= 2 {
                        return Some((k.clone(), disp.clone(), "共享同一系列主名与副标题结构"));
                    }
                }
                None
            })
            .or_else(|| {
                if let Some((ref disp, ref k)) = w.poss_prefix {
                    let distinct_cnt = poss_key_distinct_bases
                        .get(k)
                        .map(HashSet::len)
                        .unwrap_or(0)
                        + usize::from(base_key_counts.get(k).copied().unwrap_or(0) > 0);
                    if distinct_cnt >= 2 {
                        return Some((k.clone(), disp.clone(), "共享同一系列主名（如系列篇章/剧场版）"));
                    }
                }
                None
            })
            .or_else(|| {
                if let Some((ref disp, ref k)) = w.num_stripped {
                    if base_key_counts.get(k).copied().unwrap_or(0) >= 1 {
                        return Some((k.clone(), disp.clone(), "同系列主名（含续作/卷号延伸）"));
                    }
                }
                None
            })
            .or_else(|| {
                if !w.base_key.is_empty()
                    && !is_generic_series_root(&w.clean_base)
                    && (base_key_counts.get(&w.base_key).copied().unwrap_or(0) >= 2
                        || sub_key_counts.get(&w.base_key).copied().unwrap_or(0) >= 1
                        || poss_key_counts.get(&w.base_key).copied().unwrap_or(0) >= 1
                        || num_key_counts.get(&w.base_key).copied().unwrap_or(0) >= 1)
                {
                    Some((
                        w.base_key.clone(),
                        w.clean_base.clone(),
                        "同主标题的不同季数、剧场版或跨载体作品",
                    ))
                } else {
                    None
                }
            })
            .or_else(|| {
                if let Some((ref disp, ref k)) = w.sub_prefix {
                    let total = sub_key_counts.get(k).copied().unwrap_or(0)
                        + base_key_counts.get(k).copied().unwrap_or(0);
                    if total >= 2 {
                        return Some((k.clone(), disp.clone(), "共享同一系列主名与副标题结构"));
                    }
                }
                if let Some((ref disp, ref k)) = w.poss_prefix {
                    let total = poss_key_counts.get(k).copied().unwrap_or(0)
                        + base_key_counts.get(k).copied().unwrap_or(0);
                    if total >= 2 {
                        return Some((k.clone(), disp.clone(), "共享同一系列主名（如系列篇章/剧场版）"));
                    }
                }
                None
            });

        if let Some((root_key, display_name, reason)) = chosen {
            let key = format!("auto:{root_key}");
            let entry = buckets.entry(key).or_insert_with(|| BucketAccum {
                series_name: display_name.clone(),
                existing_series: false,
                reason: reason.into(),
                member_indices: Vec::new(),
            });
            // 优先使用更简洁、非全小写的系列展示名
            if display_name.chars().count() < entry.series_name.chars().count() {
                entry.series_name = display_name;
            }
            entry.member_indices.push(idx);
        }
    }

    // 5. 生成最终建议列表：至少 2 部作品，且至少包含 1 部待归入或待合并的作品
    let mut suggestions: Vec<SeriesSuggestionGroup> = Vec::new();
    for (group_key, acc) in buckets {
        if acc.member_indices.len() < 2 {
            continue;
        }
        let target_norm = normalize_loose_title(&acc.series_name);
        let mut members: Vec<SeriesSuggestionMember> = acc
            .member_indices
            .iter()
            .map(|&i| {
                let w = &works[i];
                // 只有已归属到「当前目标系列」的才算 already_grouped；来自被拆分系列或未归类的都标为待并入
                let already_grouped = w
                    .current_series_name
                    .as_deref()
                    .is_some_and(|cur| normalize_loose_title(cur) == target_norm);
                SeriesSuggestionMember {
                    id: w.id,
                    kind: w.kind.clone(),
                    title: w.title.clone(),
                    status: w.status.clone(),
                    current_series_name: w.current_series_name.clone(),
                    suggested_role: infer_series_role(&w.kind, &w.title),
                    already_grouped,
                }
            })
            .collect();
        let unassigned_count = members.iter().filter(|m| !m.already_grouped).count();
        if unassigned_count == 0 {
            continue;
        }
        // 若非已有系列，且所有成员都是同载体、同松散标题、同季数指纹，则属于“查重”而非“同系列”，不误推为系列
        if !acc.existing_series {
            let distinct_entries: std::collections::HashSet<(String, String, SeasonFingerprint)> = acc
                .member_indices
                .iter()
                .map(|&i| {
                    let w = &works[i];
                    (
                        w.kind.clone(),
                        normalize_loose_title(&w.title),
                        extract_season_fingerprint(&w.title),
                    )
                })
                .collect();
            if distinct_entries.len() < 2 {
                continue;
            }
        }
        // 未归类排前面，方便用户一眼看出本次将新增归入哪些作品
        members.sort_by(|a, b| {
            a.already_grouped
                .cmp(&b.already_grouped)
                .then_with(|| a.title.cmp(&b.title))
        });
        suggestions.push(SeriesSuggestionGroup {
            group_key,
            series_name: acc.series_name,
            existing_series: acc.existing_series,
            reason: acc.reason,
            unassigned_count,
            members,
        });
    }

    suggestions.sort_by(|a, b| {
        b.existing_series
            .cmp(&a.existing_series)
            .then_with(|| b.unassigned_count.cmp(&a.unassigned_count))
            .then_with(|| b.members.len().cmp(&a.members.len()))
            .then_with(|| a.series_name.cmp(&b.series_name))
    });
    Ok(suggestions)
}

// ============================================================================
// CSV 表格批量导入（兼容桌面端导出 CSV 与常见通用表格列）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CsvImportReport {
    pub imported: usize,
    pub skipped_duplicates: usize,
    pub failed_rows: usize,
}

fn parse_csv_rows(raw: &str) -> Vec<Vec<String>> {
    let text = raw.strip_prefix('\u{feff}').unwrap_or(raw);
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut in_quotes = false;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if in_quotes {
            if c == '"' {
                if chars.peek() == Some(&'"') {
                    chars.next();
                    field.push('"');
                } else {
                    in_quotes = false;
                }
            } else {
                field.push(c);
            }
        } else {
            match c {
                '"' => in_quotes = true,
                ',' => {
                    row.push(std::mem::take(&mut field));
                }
                '\r' => {
                    if chars.peek() == Some(&'\n') {
                        chars.next();
                    }
                    row.push(std::mem::take(&mut field));
                    rows.push(std::mem::take(&mut row));
                }
                '\n' => {
                    row.push(std::mem::take(&mut field));
                    rows.push(std::mem::take(&mut row));
                }
                _ => field.push(c),
            }
        }
    }
    if !field.is_empty() || !row.is_empty() {
        row.push(field);
        rows.push(row);
    }
    rows
}

pub fn import_works_from_csv(sync_conn: &mut Connection, csv_text: &str) -> Result<CsvImportReport> {
    let rows = parse_csv_rows(csv_text);
    if rows.len() < 2 {
        return Err(StudioError::InvalidInput("CSV 文件缺少表头或数据行".into()));
    }
    let headers: Vec<String> = rows[0].iter().map(|h| h.trim().to_lowercase()).collect();
    let find_col = |names: &[&str]| -> Option<usize> {
        headers
            .iter()
            .position(|h| names.iter().any(|n| h == n || h.contains(n)))
    };

    let idx_title = find_col(&["标题", "名称", "作品", "title", "name"])
        .ok_or_else(|| StudioError::InvalidInput("CSV 表头缺少「标题 / title」列".into()))?;
    let idx_kind = find_col(&["载体", "类型", "分类", "kind", "type"]);
    let idx_orig = find_col(&["原名", "original"]);
    let idx_status = find_col(&["状态", "status"]);
    let idx_prog = find_col(&["主进度", "已看", "进度", "watched", "progress"]);
    let idx_total = find_col(&["总进度", "总集", "total"]);
    let idx_score = find_col(&["个人评分", "评分", "score", "rating"]);
    let idx_studio = find_col(&["社团", "制作", "studio", "circle"]);
    let idx_date = find_col(&["发售", "开播", "日期", "date"]);

    let mut existing = HashSet::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT COALESCE(subjectType, 'anime'), title FROM animes WHERE deletedAt IS NULL",
        )?;
        let iter = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        for r in iter {
            let (subj, t) = r?;
            let bucket = if matches!(subj.as_str(), "book" | "manga" | "novel") { "book" } else { "anime" };
            existing.insert(format!("{bucket}:{}", t.trim().to_lowercase()));
        }
    }
    {
        let mut stmt = sync_conn.prepare("SELECT title FROM games WHERE deletedAt IS NULL")?;
        let iter = stmt.query_map([], |r| r.get::<_, String>(0))?;
        for r in iter {
            existing.insert(format!("game:{}", r?.trim().to_lowercase()));
        }
    }

    let get_cell = |r: &[String], idx: Option<usize>| -> String {
        idx.and_then(|i| r.get(i)).map(|s| s.trim().to_string()).unwrap_or_default()
    };

    let mut imported = 0usize;
    let mut skipped_duplicates = 0usize;
    let mut failed_rows = 0usize;
    let now = Utc::now().to_rfc3339();
    let tx = sync_conn.transaction()?;

    for r in rows.iter().skip(1) {
        let title = get_cell(r, Some(idx_title));
        if title.is_empty() {
            continue;
        }
        let raw_kind = get_cell(r, idx_kind).to_lowercase();
        let kind = if raw_kind.contains("书") || raw_kind.contains("小说") || raw_kind.contains("漫") || raw_kind == "book" {
            "book"
        } else if raw_kind.contains("游") || raw_kind.contains("gal") || raw_kind == "game" {
            "game"
        } else if raw_kind.contains("音") || raw_kind.contains("asmr") || raw_kind == "audio" {
            "audio"
        } else {
            "anime"
        };
        let dup_key = format!("{kind}:{}", title.to_lowercase());
        if existing.contains(&dup_key) {
            skipped_duplicates += 1;
            continue;
        }

        let orig = get_cell(r, idx_orig);
        let status = get_cell(r, idx_status);
        let prog = get_cell(r, idx_prog).parse::<i64>().unwrap_or(0).max(0);
        let total = get_cell(r, idx_total).parse::<i64>().unwrap_or(0).max(0);
        let rating = get_cell(r, idx_score)
            .parse::<f64>()
            .ok()
            .filter(|&v| v > 0.0 && v <= 10.0)
            .map(|v| (v * 10.0).round() as i64);
        let studio = get_cell(r, idx_studio);
        let date = get_cell(r, idx_date);

        let res = match kind {
            "game" => {
                let st = if status.is_empty() { "想玩".to_string() } else { status };
                let rating_val = rating.unwrap_or(0);
                tx.execute(
                    "INSERT INTO games (syncId, title, originalTitle, status, playMinutes, rating, developer, releaseDate, updatedAt)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                    params![Uuid::new_v4().to_string(), title, orig, st, prog, rating_val, studio, date, now],
                )
            }
            "audio" => {
                let st = if status.is_empty() { "想听".to_string() } else { status };
                let rating_val = rating.unwrap_or(0);
                tx.execute(
                    "INSERT INTO audio_works (syncId, title, originalTitle, status, listenedSeconds, rating, circle, releaseDate, updatedAt)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                    params![Uuid::new_v4().to_string(), title, orig, st, prog * 60, rating_val, studio, date, now],
                )
            }
            _ => {
                let st = if status.is_empty() { "想看".to_string() } else { status };
                let subj = if kind == "book" { "book" } else { "anime" };
                tx.execute(
                    "INSERT INTO animes (title, originalTitle, status, watchedEpisodes, totalEpisodes, rating, studio, airDate, subjectType, createdAt)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
                    params![title, orig, st, prog, total, rating, studio, date, subj, now],
                )
            }
        };
        match res {
            Ok(_) => {
                existing.insert(dup_key);
                imported += 1;
            }
            Err(_) => failed_rows += 1,
        }
    }
    tx.commit()?;
    Ok(CsvImportReport {
        imported,
        skipped_duplicates,
        failed_rows,
    })
}

// ============================================================================
// 画布用户自定义节点与自定义连线（仅存 desktop_local.db，零跨端污染）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CanvasCustomNode {
    pub id: String,
    pub label: String,
    pub subtitle: String,
    pub color: String,
    pub x: f64,
    pub y: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CanvasCustomEdge {
    pub id: String,
    pub from: String,
    pub to: String,
    pub label: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CanvasCustomState {
    pub nodes: Vec<CanvasCustomNode>,
    pub edges: Vec<CanvasCustomEdge>,
}

pub fn load_canvas_custom_state(local_conn: &Connection, mode: &str) -> Result<CanvasCustomState> {
    let key = format!("custom_{mode}");
    let raw: Option<String> = local_conn
        .query_row(
            "SELECT payload_json FROM canvas_layouts WHERE canvas_key = ?1",
            [&key],
            |r| r.get(0),
        )
        .ok();
    match raw {
        Some(text) => Ok(serde_json::from_str(&text).unwrap_or_default()),
        None => Ok(CanvasCustomState::default()),
    }
}

pub fn save_canvas_custom_state(
    local_conn: &Connection,
    mode: &str,
    state: &CanvasCustomState,
) -> Result<()> {
    let key = format!("custom_{mode}");
    let text = serde_json::to_string(state)?;
    local_conn.execute(
        "INSERT INTO canvas_layouts (canvas_key, payload_json, updated_at)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(canvas_key) DO UPDATE SET payload_json = excluded.payload_json, updated_at = excluded.updated_at",
        params![key, text, Utc::now().to_rfc3339()],
    )?;
    Ok(())
}

// ============================================================================
// 系列企划管理（重命名 / 合并 / 解散 / 成员角色关系维护）与全库标签管理
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeriesManageMember {
    pub kind: String,
    pub id: i64,
    pub title: String,
    pub status: String,
    pub series_role: Option<String>,
    pub cover_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SeriesManageItem {
    pub id: i64,
    pub name: String,
    pub description: Option<String>,
    pub member_count: usize,
    pub members: Vec<SeriesManageMember>,
}

pub fn list_series_manage(sync_conn: &Connection) -> Result<Vec<SeriesManageItem>> {
    let mut game_by_sync: HashMap<String, (i64, String, String, String)> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, syncId, title, status, COALESCE(coverUrl, '') FROM games WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
            ))
        })?;
        for r in rows {
            let (id, sid, title, status, cover) = r?;
            game_by_sync.insert(sid, (id, title, status, cover));
        }
    }
    let mut audio_by_sync: HashMap<String, (i64, String, String, String)> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, syncId, title, status, COALESCE(coverUrl, '') FROM audio_works WHERE deletedAt IS NULL",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
            ))
        })?;
        for r in rows {
            let (id, sid, title, status, cover) = r?;
            audio_by_sync.insert(sid, (id, title, status, cover));
        }
    }

    let mut members_by_series: HashMap<i64, Vec<SeriesManageMember>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, COALESCE(subjectType, 'anime'), title, status, seriesId, seriesRole, COALESCE(coverUrl, '')
             FROM animes
             WHERE deletedAt IS NULL AND seriesId IS NOT NULL
             ORDER BY COALESCE(airDate, ''), id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, i64>(4)?,
                r.get::<_, Option<String>>(5)?,
                r.get::<_, String>(6)?,
            ))
        })?;
        for r in rows {
            let (id, subj, title, status, sid, role, cover) = r?;
            let kind = if matches!(subj.to_ascii_lowercase().as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            members_by_series.entry(sid).or_default().push(SeriesManageMember {
                kind,
                id,
                title,
                status,
                series_role: role.filter(|s| !s.trim().is_empty()),
                cover_url: cover,
            });
        }
    }

    let mut out: Vec<SeriesManageItem> = Vec::new();
    let mut stmt = sync_conn.prepare(
        "SELECT id, name, description, COALESCE(linkedMediaJson, '[]') FROM series ORDER BY name COLLATE NOCASE ASC",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok((
            r.get::<_, i64>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, Option<String>>(2)?,
            r.get::<_, String>(3)?,
        ))
    })?;
    for r in rows {
        let (sid, name, desc, linked_json) = r?;
        let mut members = members_by_series.remove(&sid).unwrap_or_default();
        if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&linked_json) {
            for entry in arr {
                let kind_up = entry.get("kind").and_then(Value::as_str).unwrap_or("");
                let wsid = entry.get("workSyncId").and_then(Value::as_str).unwrap_or("");
                let role = entry
                    .get("role")
                    .and_then(Value::as_str)
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty());
                if kind_up.eq_ignore_ascii_case("GAME") {
                    if let Some((gid, title, status, cover)) = game_by_sync.get(wsid) {
                        members.push(SeriesManageMember {
                            kind: "game".into(),
                            id: *gid,
                            title: title.clone(),
                            status: status.clone(),
                            series_role: role,
                            cover_url: cover.clone(),
                        });
                    }
                } else if kind_up.eq_ignore_ascii_case("AUDIO") {
                    if let Some((aid, title, status, cover)) = audio_by_sync.get(wsid) {
                        members.push(SeriesManageMember {
                            kind: "audio".into(),
                            id: *aid,
                            title: title.clone(),
                            status: status.clone(),
                            series_role: role,
                            cover_url: cover.clone(),
                        });
                    }
                }
            }
        }
        let member_count = members.len();
        out.push(SeriesManageItem {
            id: sid,
            name,
            description: desc.filter(|s| !s.trim().is_empty()),
            member_count,
            members,
        });
    }
    out.sort_by(|a, b| b.member_count.cmp(&a.member_count).then_with(|| a.name.cmp(&b.name)));
    Ok(out)
}

pub fn rename_series(sync_conn: &mut Connection, series_id: i64, new_name: &str) -> Result<()> {
    let clean = new_name.trim();
    if clean.is_empty() {
        return Err(StudioError::InvalidInput("系列名称不能为空".into()));
    }
    let existing_other: Option<i64> = sync_conn
        .query_row(
            "SELECT id FROM series WHERE name = ?1 AND id != ?2 LIMIT 1",
            params![clean, series_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(target_id) = existing_other {
        return merge_series(sync_conn, series_id, target_id).map(|_| ());
    }
    sync_conn.execute(
        "UPDATE series SET name = ?1 WHERE id = ?2",
        params![clean, series_id],
    )?;
    Ok(())
}

pub fn merge_series(
    sync_conn: &mut Connection,
    source_series_id: i64,
    target_series_id: i64,
) -> Result<usize> {
    if source_series_id == target_series_id {
        return Ok(0);
    }
    let tx = sync_conn.savepoint()?;
    let updated_ms = Utc::now().timestamp_millis();
    let moved_animes = tx.execute(
        "UPDATE animes SET seriesId = ?1, updatedAt = ?2 WHERE seriesId = ?3",
        params![target_series_id, updated_ms, source_series_id],
    )?;

    let src_linked: Option<String> = tx
        .query_row(
            "SELECT linkedMediaJson FROM series WHERE id = ?1",
            params![source_series_id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();
    let dst_linked: Option<String> = tx
        .query_row(
            "SELECT linkedMediaJson FROM series WHERE id = ?1",
            params![target_series_id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();

    let mut dst_arr: Vec<Value> = dst_linked
        .as_deref()
        .and_then(|s| serde_json::from_str(s).ok())
        .unwrap_or_default();
    let src_arr: Vec<Value> = src_linked
        .as_deref()
        .and_then(|s| serde_json::from_str(s).ok())
        .unwrap_or_default();
    let mut moved_linked = 0usize;
    for entry in src_arr {
        if let (Some(k), Some(wsid)) = (
            entry.get("kind").and_then(Value::as_str),
            entry.get("workSyncId").and_then(Value::as_str),
        ) {
            let role = entry
                .get("role")
                .and_then(Value::as_str)
                .unwrap_or("关联作");
            upsert_linked_media(&mut dst_arr, k, wsid, role);
            moved_linked += 1;
        }
    }
    tx.execute(
        "UPDATE series SET linkedMediaJson = ?1 WHERE id = ?2",
        params![serde_json::to_string(&dst_arr)?, target_series_id],
    )?;
    tx.execute("DELETE FROM series WHERE id = ?1", params![source_series_id])?;
    tx.commit()?;
    Ok(moved_animes + moved_linked)
}

pub fn delete_series(sync_conn: &mut Connection, series_id: i64) -> Result<()> {
    let tx = sync_conn.savepoint()?;
    let updated_ms = Utc::now().timestamp_millis();
    tx.execute(
        "UPDATE animes SET seriesId = NULL, seriesRole = NULL, updatedAt = ?1 WHERE seriesId = ?2",
        params![updated_ms, series_id],
    )?;
    tx.execute("DELETE FROM series WHERE id = ?1", params![series_id])?;
    tx.commit()?;
    Ok(())
}

pub fn remove_member_from_series(
    sync_conn: &mut Connection,
    series_id: i64,
    kind: &str,
    work_id: i64,
) -> Result<()> {
    let tx = sync_conn.savepoint()?;
    let updated_ms = Utc::now().timestamp_millis();
    match kind {
        "anime" | "book" => {
            tx.execute(
                "UPDATE animes SET seriesId = NULL, seriesRole = NULL, updatedAt = ?1 WHERE id = ?2 AND seriesId = ?3",
                params![updated_ms, work_id, series_id],
            )?;
        }
        "game" | "audio" => {
            let table = if kind == "game" { "games" } else { "audio_works" };
            let kind_upper = if kind == "game" { "GAME" } else { "AUDIO" };
            if let Some(sync_id) = tx
                .query_row(
                    &format!("SELECT syncId FROM {table} WHERE id = ?1"),
                    params![work_id],
                    |r| r.get::<_, String>(0),
                )
                .optional()?
            {
                let raw: Option<String> = tx
                    .query_row(
                        "SELECT linkedMediaJson FROM series WHERE id = ?1",
                        params![series_id],
                        |r| r.get(0),
                    )
                    .optional()?
                    .flatten();
                let mut arr: Vec<Value> = raw
                    .as_deref()
                    .and_then(|s| serde_json::from_str(s).ok())
                    .unwrap_or_default();
                arr.retain(|e| {
                    !(e.get("kind").and_then(Value::as_str) == Some(kind_upper)
                        && e.get("workSyncId").and_then(Value::as_str) == Some(sync_id.as_str()))
                });
                tx.execute(
                    "UPDATE series SET linkedMediaJson = ?1 WHERE id = ?2",
                    params![serde_json::to_string(&arr)?, series_id],
                )?;
            }
        }
        _ => {}
    }
    tx.commit()?;
    Ok(())
}

pub fn update_series_member_role(
    sync_conn: &mut Connection,
    series_id: i64,
    kind: &str,
    work_id: i64,
    role: &str,
) -> Result<()> {
    let clean_role = role.trim();
    let role_opt = (!clean_role.is_empty()).then_some(clean_role);
    let tx = sync_conn.savepoint()?;
    let updated_ms = Utc::now().timestamp_millis();
    match kind {
        "anime" | "book" => {
            tx.execute(
                "UPDATE animes SET seriesRole = ?1, updatedAt = ?2 WHERE id = ?3 AND seriesId = ?4",
                params![role_opt, updated_ms, work_id, series_id],
            )?;
        }
        "game" | "audio" => {
            let table = if kind == "game" { "games" } else { "audio_works" };
            let kind_upper = if kind == "game" { "GAME" } else { "AUDIO" };
            if let Some(sync_id) = tx
                .query_row(
                    &format!("SELECT syncId FROM {table} WHERE id = ?1"),
                    params![work_id],
                    |r| r.get::<_, String>(0),
                )
                .optional()?
            {
                let raw: Option<String> = tx
                    .query_row(
                        "SELECT linkedMediaJson FROM series WHERE id = ?1",
                        params![series_id],
                        |r| r.get(0),
                    )
                    .optional()?
                    .flatten();
                let mut arr: Vec<Value> = raw
                    .as_deref()
                    .and_then(|s| serde_json::from_str(s).ok())
                    .unwrap_or_default();
                upsert_linked_media(&mut arr, kind_upper, &sync_id, role_opt.unwrap_or("关联作"));
                tx.execute(
                    "UPDATE series SET linkedMediaJson = ?1 WHERE id = ?2",
                    params![serde_json::to_string(&arr)?, series_id],
                )?;
            }
        }
        _ => {}
    }
    tx.commit()?;
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TagManageItem {
    pub id: i64,
    pub name: String,
    pub blocked: bool,
    pub usage_count: i64,
}

pub fn list_tags_manage(sync_conn: &Connection) -> Result<Vec<TagManageItem>> {
    let mut stmt = sync_conn.prepare(
        "SELECT t.id, t.name, COALESCE(t.blocked, 0),
                COUNT(CASE WHEN a.id IS NOT NULL AND a.deletedAt IS NULL THEN 1 END) AS usage_cnt
         FROM tags t
         LEFT JOIN anime_tags at ON at.tagId = t.id
         LEFT JOIN animes a ON a.id = at.animeId
         GROUP BY t.id, t.name, t.blocked
         ORDER BY usage_cnt DESC, t.name COLLATE NOCASE ASC",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(TagManageItem {
            id: r.get(0)?,
            name: r.get(1)?,
            blocked: r.get::<_, i64>(2)? != 0,
            usage_count: r.get(3)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

fn rewrite_csv_tags_in_table(
    tx: &Connection,
    table: &str,
    old_name: &str,
    new_name: Option<&str>,
) -> Result<()> {
    let mut rows_to_update: Vec<(i64, String)> = Vec::new();
    {
        let mut stmt = tx.prepare(&format!("SELECT id, tags FROM {table} WHERE tags != ''"))?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
        for r in rows {
            let (id, raw) = r?;
            let parts: Vec<String> = raw
                .split([',', '，', ';'])
                .map(|s| s.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            let mut changed = false;
            let mut seen = HashSet::new();
            let mut next = Vec::new();
            for p in parts {
                if p.eq_ignore_ascii_case(old_name) {
                    changed = true;
                    if let Some(rep) = new_name {
                        let key = rep.to_lowercase();
                        if seen.insert(key) {
                            next.push(rep.to_string());
                        }
                    }
                } else {
                    let key = p.to_lowercase();
                    if seen.insert(key) {
                        next.push(p);
                    }
                }
            }
            if changed {
                rows_to_update.push((id, next.join(", ")));
            }
        }
    }
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    for (id, joined) in rows_to_update {
        tx.execute(
            &format!("UPDATE {table} SET tags = ?1, updatedAt = ?2 WHERE id = ?3"),
            params![joined, now, id],
        )?;
    }
    Ok(())
}

pub fn rename_tag(sync_conn: &mut Connection, tag_id: i64, new_name: &str) -> Result<()> {
    let clean = new_name.trim().trim_start_matches('#').trim();
    if clean.is_empty() {
        return Err(StudioError::InvalidInput("标签名称不能为空".into()));
    }
    let existing_other: Option<i64> = sync_conn
        .query_row(
            "SELECT id FROM tags WHERE name = ?1 AND id != ?2 LIMIT 1",
            params![clean, tag_id],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(target_id) = existing_other {
        return merge_tags(sync_conn, tag_id, target_id).map(|_| ());
    }
    let old_name: Option<String> = sync_conn
        .query_row("SELECT name FROM tags WHERE id = ?1", params![tag_id], |r| r.get(0))
        .optional()?;
    let tx = sync_conn.savepoint()?;
    tx.execute("UPDATE tags SET name = ?1 WHERE id = ?2", params![clean, tag_id])?;
    if let Some(old) = old_name {
        rewrite_csv_tags_in_table(&tx, "games", &old, Some(clean))?;
        rewrite_csv_tags_in_table(&tx, "audio_works", &old, Some(clean))?;
    }
    tx.commit()?;
    Ok(())
}

pub fn merge_tags(
    sync_conn: &mut Connection,
    source_tag_id: i64,
    target_tag_id: i64,
) -> Result<usize> {
    if source_tag_id == target_tag_id {
        return Ok(0);
    }
    let old_name: Option<String> = sync_conn
        .query_row("SELECT name FROM tags WHERE id = ?1", params![source_tag_id], |r| r.get(0))
        .optional()?;
    let new_name: String = sync_conn.query_row(
        "SELECT name FROM tags WHERE id = ?1",
        params![target_tag_id],
        |r| r.get(0),
    )?;
    let tx = sync_conn.savepoint()?;
    let moved = tx.execute(
        "INSERT OR IGNORE INTO anime_tags (animeId, tagId)
         SELECT animeId, ?1 FROM anime_tags WHERE tagId = ?2",
        params![target_tag_id, source_tag_id],
    )?;
    tx.execute("DELETE FROM anime_tags WHERE tagId = ?1", params![source_tag_id])?;
    tx.execute("DELETE FROM tags WHERE id = ?1", params![source_tag_id])?;
    if let Some(old) = old_name {
        rewrite_csv_tags_in_table(&tx, "games", &old, Some(&new_name))?;
        rewrite_csv_tags_in_table(&tx, "audio_works", &old, Some(&new_name))?;
    }
    tx.commit()?;
    Ok(moved)
}

pub fn delete_tag(sync_conn: &mut Connection, tag_id: i64) -> Result<()> {
    let old_name: Option<String> = sync_conn
        .query_row("SELECT name FROM tags WHERE id = ?1", params![tag_id], |r| r.get(0))
        .optional()?;
    let tx = sync_conn.transaction()?;
    tx.execute("DELETE FROM anime_tags WHERE tagId = ?1", params![tag_id])?;
    tx.execute("DELETE FROM tags WHERE id = ?1", params![tag_id])?;
    if let Some(old) = old_name {
        rewrite_csv_tags_in_table(&tx, "games", &old, None)?;
        rewrite_csv_tags_in_table(&tx, "audio_works", &old, None)?;
    }
    tx.commit()?;
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PurgedTagSnapshot {
    pub id: i64,
    pub name: String,
    pub color: Option<i64>,
    pub blocked: bool,
    pub sort_order: Option<i64>,
    pub deleted_anime_links: Vec<i64>,
}

fn load_tag_snapshot_for_purge(sync_conn: &Connection, tag_id: i64) -> Result<Option<PurgedTagSnapshot>> {
    let row: Option<(i64, String, Option<i64>, i64, Option<i64>)> = sync_conn
        .query_row(
            "SELECT id, name, color, COALESCE(blocked, 0), sortOrder FROM tags WHERE id = ?1",
            params![tag_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?;
    let Some((id, name, color, blocked_int, sort_order)) = row else {
        return Ok(None);
    };
    let mut stmt = sync_conn.prepare("SELECT animeId FROM anime_tags WHERE tagId = ?1")?;
    let links = stmt
        .query_map(params![tag_id], |r| r.get::<_, i64>(0))?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    Ok(Some(PurgedTagSnapshot {
        id,
        name,
        color,
        blocked: blocked_int != 0,
        sort_order,
        deleted_anime_links: links,
    }))
}

pub fn purge_unused_tag_by_id(sync_conn: &Connection, tag_id: i64) -> Result<Option<PurgedTagSnapshot>> {
    let active_refs: i64 = sync_conn.query_row(
        "SELECT COUNT(1)
         FROM anime_tags at
         JOIN animes a ON a.id = at.animeId
         WHERE at.tagId = ?1 AND a.deletedAt IS NULL",
        params![tag_id],
        |r| r.get(0),
    )?;
    if active_refs > 0 {
        return Err(StudioError::InvalidInput(format!(
            "标签（#{tag_id}）当前仍被 {active_refs} 部作品使用，无法按 0 使用标签清理。"
        )));
    }
    let Some(snap) = load_tag_snapshot_for_purge(sync_conn, tag_id)? else {
        return Ok(None);
    };
    sync_conn.execute("DELETE FROM anime_tags WHERE tagId = ?1", params![tag_id])?;
    sync_conn.execute("DELETE FROM tags WHERE id = ?1", params![tag_id])?;
    Ok(Some(snap))
}

pub fn purge_unused_tags_with_snapshot(sync_conn: &Connection) -> Result<Vec<PurgedTagSnapshot>> {
    let mut stmt = sync_conn.prepare(
        "SELECT id FROM tags
         WHERE id NOT IN (
             SELECT DISTINCT at.tagId
             FROM anime_tags at
             JOIN animes a ON a.id = at.animeId
             WHERE a.deletedAt IS NULL
         )
         ORDER BY id ASC",
    )?;
    let ids: Vec<i64> = stmt
        .query_map([], |r| r.get::<_, i64>(0))?
        .collect::<std::result::Result<Vec<_>, _>>()?;
    let mut snapshots = Vec::new();
    for tid in ids {
        if let Some(snap) = purge_unused_tag_by_id(sync_conn, tid)? {
            snapshots.push(snap);
        }
    }
    Ok(snapshots)
}

pub fn restore_purged_tag(sync_conn: &Connection, snap: &PurgedTagSnapshot) -> Result<()> {
    let existing_by_name: Option<i64> = sync_conn
        .query_row("SELECT id FROM tags WHERE name = ?1", params![snap.name], |r| r.get(0))
        .optional()?;
    let restored_id = if let Some(eid) = existing_by_name {
        eid
    } else {
        let existing_by_id: Option<String> = sync_conn
            .query_row("SELECT name FROM tags WHERE id = ?1", params![snap.id], |r| r.get(0))
            .optional()?;
        if existing_by_id.is_none() {
            sync_conn.execute(
                "INSERT INTO tags (id, name, color, blocked, sortOrder) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![
                    snap.id,
                    snap.name,
                    snap.color,
                    if snap.blocked { 1 } else { 0 },
                    snap.sort_order,
                ],
            )?;
            snap.id
        } else {
            sync_conn.execute(
                "INSERT INTO tags (name, color, blocked, sortOrder) VALUES (?1, ?2, ?3, ?4)",
                params![
                    snap.name,
                    snap.color,
                    if snap.blocked { 1 } else { 0 },
                    snap.sort_order,
                ],
            )?;
            sync_conn.last_insert_rowid()
        }
    };
    for anime_id in &snap.deleted_anime_links {
        let exists: i64 = sync_conn
            .query_row("SELECT COUNT(1) FROM animes WHERE id = ?1", params![anime_id], |r| r.get(0))
            .unwrap_or(0);
        if exists > 0 {
            sync_conn.execute(
                "INSERT OR IGNORE INTO anime_tags (animeId, tagId) VALUES (?1, ?2)",
                params![anime_id, restored_id],
            )?;
        }
    }
    Ok(())
}

pub fn purge_unused_tags(sync_conn: &mut Connection) -> Result<usize> {
    let deleted = purge_unused_tags_with_snapshot(sync_conn)?;
    Ok(deleted.len())
}

pub fn toggle_tag_blocked(sync_conn: &Connection, tag_id: i64, blocked: bool) -> Result<()> {
    sync_conn.execute(
        "UPDATE tags SET blocked = ?1 WHERE id = ?2",
        params![if blocked { 1 } else { 0 }, tag_id],
    )?;
    Ok(())
}

// ============================================================================
// 角色管理（角色档案、角色 ↔ 动画/书籍关联、角色 ↔ 角色关系）
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CharacterWorkLink {
    pub anime_id: i64,
    pub kind: String,
    pub title: String,
    pub role_name: String,
    pub voice_actor: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CharacterRelationItem {
    pub id: i64,
    pub source_character_id: i64,
    pub target_character_id: i64,
    pub source_name: String,
    pub target_name: String,
    pub relation_type: String,
    pub source_role: String,
    pub target_role: String,
    pub note: String,
    pub strength: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CharacterManageItem {
    pub id: i64,
    pub bgm_id: Option<i64>,
    pub name: String,
    pub name_cn: String,
    pub image_url: String,
    pub summary: String,
    pub gender: String,
    pub birth_month: Option<i64>,
    pub birth_day: Option<i64>,
    pub blood_type: String,
    pub rating: Option<i64>,
    pub review: String,
    #[serde(default)]
    pub tags: Vec<String>,
    pub works: Vec<CharacterWorkLink>,
    pub relations: Vec<CharacterRelationItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkCharacterBrief {
    pub id: i64,
    pub name: String,
    pub name_cn: String,
    pub image_url: String,
    pub role_name: String,
    pub voice_actor: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpsertCharacterInput {
    pub id: Option<i64>,
    #[serde(default)]
    pub bgm_id: Option<i64>,
    #[serde(default)]
    pub anilist_id: Option<i64>,
    pub name: String,
    pub name_cn: Option<String>,
    pub image_url: Option<String>,
    pub summary: Option<String>,
    pub gender: Option<String>,
    pub birth_month: Option<i64>,
    pub birth_day: Option<i64>,
    pub blood_type: Option<String>,
    pub rating: Option<i64>,
    pub review: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpsertCharacterRelationInput {
    pub id: Option<i64>,
    pub source_character_id: i64,
    pub target_character_id: i64,
    pub relation_type: String,
    pub source_role: Option<String>,
    pub target_role: Option<String>,
    pub note: Option<String>,
    pub strength: Option<i64>,
}

pub fn list_characters_manage(sync_conn: &Connection) -> Result<Vec<CharacterManageItem>> {
    let mut items: Vec<CharacterManageItem> = Vec::new();
    let mut display_name_map: HashMap<i64, String> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, bgmId, name, COALESCE(nameCn, ''), COALESCE(imageUrl, ''),
                    COALESCE(summary, ''), COALESCE(gender, ''), birthMonth, birthDay,
                    COALESCE(bloodType, ''), rating, COALESCE(review, '')
             FROM characters
             ORDER BY CASE WHEN rating IS NOT NULL AND rating > 0 THEN 0 ELSE 1 END,
                      COALESCE(rating, 0) DESC,
                      id DESC",
        )?;
        let rows = stmt.query_map([], |r| {
            let id: i64 = r.get(0)?;
            let name: String = r.get(2)?;
            let name_cn: String = r.get(3)?;
            Ok(CharacterManageItem {
                id,
                bgm_id: r.get(1)?,
                name,
                name_cn,
                image_url: r.get(4)?,
                summary: r.get(5)?,
                gender: r.get(6)?,
                birth_month: r.get(7)?,
                birth_day: r.get(8)?,
                blood_type: r.get(9)?,
                rating: r.get::<_, Option<i64>>(10)?.filter(|&v| v > 0),
                review: r.get(11)?,
                tags: Vec::new(),
                works: Vec::new(),
                relations: Vec::new(),
            })
        })?;
        for r in rows {
            let it = r?;
            let disp = if !it.name_cn.trim().is_empty() {
                it.name_cn.trim().to_string()
            } else {
                it.name.trim().to_string()
            };
            display_name_map.insert(it.id, disp);
            items.push(it);
        }
    }

    let mut works_by_char: HashMap<i64, Vec<CharacterWorkLink>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT ac.characterId, ac.animeId, COALESCE(a.subjectType, 'anime'), a.title,
                    COALESCE(ac.roleName, ''), COALESCE(ac.voiceActor, '')
             FROM anime_characters ac
             JOIN animes a ON a.id = ac.animeId AND a.deletedAt IS NULL
             ORDER BY ac.sortOrder ASC, ac.animeId ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            let subj: String = r.get(2)?;
            let kind = if matches!(subj.as_str(), "book" | "manga" | "novel") {
                "book".to_string()
            } else {
                "anime".to_string()
            };
            Ok((
                r.get::<_, i64>(0)?,
                CharacterWorkLink {
                    anime_id: r.get(1)?,
                    kind,
                    title: r.get(3)?,
                    role_name: r.get(4)?,
                    voice_actor: r.get(5)?,
                },
            ))
        })?;
        for r in rows {
            let (cid, link) = r?;
            works_by_char.entry(cid).or_default().push(link);
        }
    }

    let mut rels_by_char: HashMap<i64, Vec<CharacterRelationItem>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT id, sourceCharacterId, targetCharacterId, COALESCE(relationType, '关联'),
                    COALESCE(sourceRole, ''), COALESCE(targetRole, ''), COALESCE(note, ''),
                    COALESCE(strength, 3)
             FROM character_relations
             ORDER BY strength DESC, id ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
                r.get::<_, String>(6)?,
                r.get::<_, i64>(7)?,
            ))
        })?;
        for r in rows {
            let (id, src, dst, rtype, srole, trole, note, strength) = r?;
            let src_name = display_name_map
                .get(&src)
                .cloned()
                .unwrap_or_else(|| format!("#{src}"));
            let dst_name = display_name_map
                .get(&dst)
                .cloned()
                .unwrap_or_else(|| format!("#{dst}"));
            let rel = CharacterRelationItem {
                id,
                source_character_id: src,
                target_character_id: dst,
                source_name: src_name,
                target_name: dst_name,
                relation_type: rtype,
                source_role: srole,
                target_role: trole,
                note,
                strength,
            };
            rels_by_char.entry(src).or_default().push(rel.clone());
            if dst != src {
                rels_by_char.entry(dst).or_default().push(rel);
            }
        }
    }

    let mut tags_by_char: HashMap<i64, Vec<String>> = HashMap::new();
    {
        let mut stmt = sync_conn.prepare(
            "SELECT ctl.characterId, ct.name
             FROM character_tag_links ctl
             JOIN character_tags ct ON ct.id = ctl.tagId
             ORDER BY ct.name COLLATE NOCASE ASC",
        )?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?)))?;
        for r in rows {
            let (cid, tname) = r?;
            let clean = tname.trim();
            if !clean.is_empty() {
                tags_by_char.entry(cid).or_default().push(clean.to_string());
            }
        }
    }

    for it in &mut items {
        if let Some(ts) = tags_by_char.remove(&it.id) {
            it.tags = ts;
        }
        if let Some(ws) = works_by_char.remove(&it.id) {
            it.works = ws;
        }
        if let Some(rs) = rels_by_char.remove(&it.id) {
            it.relations = rs;
        }
    }
    Ok(items)
}

pub fn list_work_characters(sync_conn: &Connection, anime_id: i64) -> Result<Vec<WorkCharacterBrief>> {
    let mut stmt = sync_conn.prepare(
        "SELECT c.id, c.name, COALESCE(c.nameCn, ''), COALESCE(c.imageUrl, ''),
                COALESCE(ac.roleName, ''), COALESCE(ac.voiceActor, '')
         FROM anime_characters ac
         JOIN characters c ON c.id = ac.characterId
         WHERE ac.animeId = ?1
         ORDER BY ac.sortOrder ASC,
                  CASE COALESCE(ac.roleName, '') WHEN '主角' THEN 0 WHEN '主要角色' THEN 1 WHEN '女主' THEN 1 WHEN '男主' THEN 1 WHEN '配角' THEN 2 WHEN '暂定' THEN 4 ELSE 3 END,
                  c.id ASC",
    )?;
    let rows = stmt.query_map(params![anime_id], |r| {
        Ok(WorkCharacterBrief {
            id: r.get(0)?,
            name: r.get(1)?,
            name_cn: r.get(2)?,
            image_url: r.get(3)?,
            role_name: r.get(4)?,
            voice_actor: r.get(5)?,
        })
    })?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r?);
    }
    Ok(out)
}

pub fn set_character_tags(
    sync_conn: &mut Connection,
    character_id: i64,
    tags: &[String],
) -> Result<()> {
    let tx = sync_conn.savepoint()?;
    tx.execute(
        "DELETE FROM character_tag_links WHERE characterId = ?1",
        params![character_id],
    )?;
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let mut seen = HashSet::new();
    for raw in tags {
        let clean = raw.trim().trim_start_matches('#').trim();
        if clean.is_empty() || !seen.insert(clean.to_lowercase()) {
            continue;
        }
        tx.execute(
            "INSERT OR IGNORE INTO character_tags (name, createdAt) VALUES (?1, ?2)",
            params![clean, now],
        )?;
        if let Some(tag_id) = tx
            .query_row(
                "SELECT id FROM character_tags WHERE name = ?1 LIMIT 1",
                params![clean],
                |r| r.get::<_, i64>(0),
            )
            .optional()?
        {
            tx.execute(
                "INSERT OR IGNORE INTO character_tag_links (characterId, tagId) VALUES (?1, ?2)",
                params![character_id, tag_id],
            )?;
        }
    }
    tx.commit()?;
    Ok(())
}

pub fn batch_delete_characters(sync_conn: &mut Connection, ids: &[i64]) -> Result<usize> {
    if ids.is_empty() {
        return Ok(0);
    }
    let tx = sync_conn.transaction()?;
    let mut deleted = 0usize;
    for &cid in ids {
        tx.execute("DELETE FROM anime_characters WHERE characterId = ?1", params![cid])?;
        tx.execute(
            "DELETE FROM character_relations WHERE sourceCharacterId = ?1 OR targetCharacterId = ?1",
            params![cid],
        )?;
        tx.execute("DELETE FROM character_tag_links WHERE characterId = ?1", params![cid])?;
        tx.execute("DELETE FROM character_group_characters WHERE characterId = ?1", params![cid])?;
        deleted += tx.execute("DELETE FROM characters WHERE id = ?1", params![cid])?;
    }
    tx.commit()?;
    Ok(deleted)
}

pub fn save_png_data_url(output_path: &std::path::Path, data_url: &str) -> Result<()> {
    use base64::Engine;
    let payload = data_url
        .split_once(',')
        .map(|(_, b64)| b64)
        .unwrap_or(data_url)
        .trim();
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(payload)
        .map_err(|e| StudioError::InvalidInput(format!("PNG 图片解码失败：{e}")))?;
    if let Some(parent) = output_path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    std::fs::write(output_path, bytes)?;
    Ok(())
}

const CHARACTER_PROFILE_FIELDS: &[(&str, &str)] = &[
    ("name", "name"), ("name_cn", "nameCn"), ("image_url", "imageUrl"), ("summary", "summary"),
    ("gender", "gender"), ("birth_month", "birthMonth"), ("birth_day", "birthDay"),
    ("blood_type", "bloodType"), ("rating", "rating"), ("review", "review"),
    ("bgm_id", "bgmId"), ("anilist_id", "anilistId"),
];

pub(crate) fn character_id_by_bangumi(conn: &Connection, id: i64) -> Result<Option<i64>> {
    Ok(conn.query_row("SELECT id FROM characters WHERE bgmId = ?1", [id], |r| r.get(0)).optional()?)
}

pub(crate) fn read_character_fields(conn: &Connection, id: i64) -> Result<Value> {
    let columns = CHARACTER_PROFILE_FIELDS.iter().map(|(field, column)| format!("'{field}', {column}"))
        .collect::<Vec<_>>().join(", ");
    let json: String = conn.query_row(
        &format!("SELECT json_object({columns}) FROM characters WHERE id = ?1"), [id], |row| row.get(0),
    ).optional()?.ok_or_else(|| StudioError::InvalidInput(format!("找不到角色 #{id}")))?;
    Ok(serde_json::from_str(&json)?)
}

pub(crate) fn write_character_fields(conn: &Connection, id: i64, fields: &Value) -> Result<()> {
    let assignments = CHARACTER_PROFILE_FIELDS.iter().filter(|(field, _)| fields.get(*field).is_some())
        .map(|(field, column)| format!("{column} = json_extract(?1, '$.{field}')"))
        .collect::<Vec<_>>().join(", ");
    if assignments.is_empty() {
        return Ok(());
    }
    let count = conn.execute(
        &format!("UPDATE characters SET {assignments}, updatedAt = ?2 WHERE id = ?3"),
        params![fields.to_string(), Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true), id],
    )?;
    if count != 1 {
        return Err(StudioError::InvalidInput(format!("找不到角色 #{id}")));
    }
    Ok(())
}

pub fn upsert_character(sync_conn: &Connection, input: &UpsertCharacterInput) -> Result<i64> {
    save_character_profile(sync_conn, input, None)
}

pub(crate) fn save_character_profile(
    sync_conn: &Connection,
    input: &UpsertCharacterInput,
    submitted: Option<&Value>,
) -> Result<i64> {
    let raw_name = input.name.trim();
    let raw_name_cn = input.name_cn.as_deref().unwrap_or("").trim();
    let final_name = if !raw_name.is_empty() {
        raw_name
    } else if !raw_name_cn.is_empty() {
        raw_name_cn
    } else if input.id.is_some() && submitted.is_some_and(|fields| fields.get("name").is_none()) {
        ""
    } else {
        return Err(StudioError::InvalidInput("角色名称不能为空".into()));
    };
    let name_cn_opt = (!raw_name_cn.is_empty()).then_some(raw_name_cn);
    let image_opt = input
        .image_url
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let summary_opt = input
        .summary
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let gender_opt = input
        .gender
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let blood_opt = input
        .blood_type
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let review_opt = input
        .review
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let rating_opt = input.rating.filter(|&v| v > 0).map(|v| v.clamp(1, 100));
    let birth_month = input.birth_month.filter(|&m| (1..=12).contains(&m));
    let birth_day = input.birth_day.filter(|&d| (1..=31).contains(&d));
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);

    let bgm_id_opt = input.bgm_id.filter(|&v| v > 0);
    let anilist_id_opt = input.anilist_id.filter(|&v| v > 0);

    // 若未指定本地 id 但带 bgmId，先查找库中是否已有同 bgmId 角色，避免唯一索引冲突并自动补全缺失字段
    let resolved_id = if let Some(id) = input.id.filter(|&v| v > 0) {
        Some(id)
    } else if let Some(bid) = bgm_id_opt {
        character_id_by_bangumi(sync_conn, bid)?
    } else {
        None
    };

    if let Some(id) = resolved_id {
        if input.id.filter(|&v| v > 0).is_some() {
            let mut fields = serde_json::json!({
                "name": final_name, "name_cn": name_cn_opt, "image_url": image_opt,
                "summary": summary_opt, "gender": gender_opt, "birth_month": birth_month,
                "birth_day": birth_day, "blood_type": blood_opt, "rating": rating_opt,
                "review": review_opt, "bgm_id": bgm_id_opt, "anilist_id": anilist_id_opt,
            });
            let values = fields.as_object_mut().expect("角色字段使用 JSON 对象");
            values.retain(|key, value| {
                submitted.map_or(
                    !matches!(key.as_str(), "bgm_id" | "anilist_id") || !value.is_null(),
                    |input| input.get(key).is_some(),
                )
            });
            write_character_fields(sync_conn, id, &fields)?;
        } else {
            // 按 bgmId 幂等导入：仅补全空白字段，保留用户已有评分/评价/自定义信息
            sync_conn.execute(
                "UPDATE characters
                 SET name = CASE WHEN COALESCE(TRIM(name), '') = '' THEN ?1 ELSE name END,
                     nameCn = COALESCE(NULLIF(TRIM(nameCn), ''), ?2),
                     imageUrl = COALESCE(NULLIF(TRIM(imageUrl), ''), ?3),
                     summary = COALESCE(NULLIF(TRIM(summary), ''), ?4),
                     gender = COALESCE(NULLIF(TRIM(gender), ''), ?5),
                     birthMonth = COALESCE(birthMonth, ?6),
                     birthDay = COALESCE(birthDay, ?7),
                     bloodType = COALESCE(NULLIF(TRIM(bloodType), ''), ?8),
                     anilistId = COALESCE(anilistId, ?9),
                     updatedAt = ?10
                 WHERE id = ?11",
                params![
                    final_name,
                    name_cn_opt,
                    image_opt,
                    summary_opt,
                    gender_opt,
                    birth_month,
                    birth_day,
                    blood_opt,
                    anilist_id_opt,
                    now,
                    id
                ],
            )?;
        }
        Ok(id)
    } else {
        sync_conn.execute(
            "INSERT INTO characters
             (bgmId, anilistId, name, nameCn, imageUrl, summary, gender, birthMonth, birthDay, bloodType, rating, review, updatedAt)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
            params![
                bgm_id_opt,
                anilist_id_opt,
                final_name,
                name_cn_opt,
                image_opt,
                summary_opt,
                gender_opt,
                birth_month,
                birth_day,
                blood_opt,
                rating_opt,
                review_opt,
                now
            ],
        )?;
        Ok(sync_conn.last_insert_rowid())
    }
}

pub fn delete_character(sync_conn: &mut Connection, character_id: i64) -> Result<()> {
    let tx = sync_conn.savepoint()?;
    tx.execute(
        "DELETE FROM anime_characters WHERE characterId = ?1",
        params![character_id],
    )?;
    tx.execute(
        "DELETE FROM character_relations WHERE sourceCharacterId = ?1 OR targetCharacterId = ?1",
        params![character_id],
    )?;
    tx.execute(
        "DELETE FROM character_tag_links WHERE characterId = ?1",
        params![character_id],
    )?;
    tx.execute(
        "DELETE FROM character_group_characters WHERE characterId = ?1",
        params![character_id],
    )?;
    tx.execute("DELETE FROM characters WHERE id = ?1", params![character_id])?;
    tx.commit()?;
    Ok(())
}

pub fn link_character_work(
    sync_conn: &Connection,
    character_id: i64,
    anime_id: i64,
    role_name: Option<&str>,
    voice_actor: Option<&str>,
) -> Result<()> {
    link_character_work_with_propagation(sync_conn, character_id, anime_id, role_name, voice_actor, true)
}

pub fn link_character_work_with_propagation(
    sync_conn: &Connection,
    character_id: i64,
    anime_id: i64,
    role_name: Option<&str>,
    voice_actor: Option<&str>,
    propagate_provisional: bool,
) -> Result<()> {
    save_character_work_link(sync_conn, character_id, anime_id, role_name, voice_actor, propagate_provisional, false)
}

pub fn link_character_work_from_catalog(
    sync_conn: &Connection,
    character_id: i64,
    anime_id: i64,
    role_name: Option<&str>,
    voice_actor: Option<&str>,
) -> Result<()> {
    save_character_work_link(sync_conn, character_id, anime_id, role_name, voice_actor, true, true)
}

fn save_character_work_link(
    sync_conn: &Connection,
    character_id: i64,
    anime_id: i64,
    role_name: Option<&str>,
    voice_actor: Option<&str>,
    propagate_provisional: bool,
    preserve_empty_voice_actor: bool,
) -> Result<()> {
    let role = role_name
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let va = voice_actor
        .map(str::trim)
        .filter(|s| !s.is_empty());
    sync_conn.execute(
        "INSERT INTO anime_characters (animeId, characterId, roleName, voiceActor, sortOrder)
         VALUES (?1, ?2, ?3, ?4, 0)
         ON CONFLICT(animeId, characterId) DO UPDATE SET
           roleName = excluded.roleName,
           voiceActor = CASE WHEN ?5 THEN COALESCE(excluded.voiceActor, anime_characters.voiceActor) ELSE excluded.voiceActor END",
        params![anime_id, character_id, role, va, preserve_empty_voice_actor],
    )?;
    if propagate_provisional {
        // 联动：将该角色的直接关联角色也自动关联到同一作品（仅补充未关联的，定位标记为「暂定」，绝不覆盖已有定位）
        sync_conn.execute(
            "INSERT OR IGNORE INTO anime_characters (animeId, characterId, roleName, voiceActor, sortOrder)
             SELECT ?1,
                    CASE WHEN cr.sourceCharacterId = ?2 THEN cr.targetCharacterId ELSE cr.sourceCharacterId END AS rel_cid,
                    '暂定',
                    NULL,
                    0
             FROM character_relations cr
             JOIN characters c ON c.id = (CASE WHEN cr.sourceCharacterId = ?2 THEN cr.targetCharacterId ELSE cr.sourceCharacterId END)
             WHERE (cr.sourceCharacterId = ?2 OR cr.targetCharacterId = ?2)
               AND (CASE WHEN cr.sourceCharacterId = ?2 THEN cr.targetCharacterId ELSE cr.sourceCharacterId END) != ?2",
            params![anime_id, character_id],
        )?;
    }
    Ok(())
}

pub fn unlink_character_work(
    sync_conn: &Connection,
    character_id: i64,
    anime_id: i64,
) -> Result<()> {
    sync_conn.execute(
        "DELETE FROM anime_characters WHERE animeId = ?1 AND characterId = ?2",
        params![anime_id, character_id],
    )?;
    Ok(())
}

pub fn upsert_character_relation(
    sync_conn: &Connection,
    input: &UpsertCharacterRelationInput,
) -> Result<i64> {
    upsert_character_relation_with_propagation(sync_conn, input, true)
}

pub fn upsert_character_relation_with_propagation(
    sync_conn: &Connection,
    input: &UpsertCharacterRelationInput,
    propagate_provisional: bool,
) -> Result<i64> {
    if input.source_character_id == input.target_character_id {
        return Err(StudioError::InvalidInput("不能为同一个角色建立自我关系".into()));
    }
    let rel_type = input.relation_type.trim();
    let final_type = if rel_type.is_empty() { "关联" } else { rel_type };
    let src_role = input
        .source_role
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let dst_role = input
        .target_role
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let note = input
        .note
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let strength = input.strength.unwrap_or(3).clamp(1, 5);
    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);

    let rel_id = if let Some(id) = input.id.filter(|&v| v > 0) {
        sync_conn.execute(
            "UPDATE character_relations
             SET sourceCharacterId = ?1, targetCharacterId = ?2, relationType = ?3,
                 sourceRole = ?4, targetRole = ?5, note = ?6, strength = ?7, updatedAt = ?8
             WHERE id = ?9",
            params![
                input.source_character_id,
                input.target_character_id,
                final_type,
                src_role,
                dst_role,
                note,
                strength,
                now,
                id
            ],
        )?;
        id
    } else {
        sync_conn.execute(
            "INSERT INTO character_relations
             (sourceCharacterId, targetCharacterId, relationType, sourceRole, targetRole, note, strength, createdAt, updatedAt)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                input.source_character_id,
                input.target_character_id,
                final_type,
                src_role,
                dst_role,
                note,
                strength,
                now,
                now
            ],
        )?;
        sync_conn.last_insert_rowid()
    };

    if propagate_provisional {
        // 双向联动：建立角色关系时，若一方已绑定作品，自动将另一方以「暂定」定位补充关联到该作品（不覆盖已有绑定）
        for (from_cid, to_cid) in [
            (input.source_character_id, input.target_character_id),
            (input.target_character_id, input.source_character_id),
        ] {
            sync_conn.execute(
                "INSERT OR IGNORE INTO anime_characters (animeId, characterId, roleName, voiceActor, sortOrder)
                 SELECT ac.animeId, ?2, '暂定', NULL, 0
                 FROM anime_characters ac
                 JOIN animes a ON a.id = ac.animeId AND a.deletedAt IS NULL
                 WHERE ac.characterId = ?1",
                params![from_cid, to_cid],
            )?;
        }
    }

    Ok(rel_id)
}

pub fn delete_character_relation(sync_conn: &Connection, relation_id: i64) -> Result<()> {
    sync_conn.execute(
        "DELETE FROM character_relations WHERE id = ?1",
        params![relation_id],
    )?;
    Ok(())
}
