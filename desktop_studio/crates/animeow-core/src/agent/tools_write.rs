use crate::repository::external_binding::{find_active_bangumi_holder, replace_anime_bangumi_binding};
use crate::agent::storage::{
    load_library_result, load_proposal, load_verify_batch_bindings, save_proposal_record,
};
use crate::agent::types::{AgentProposal, ProposalApplyResult, ProposalItemDiff};
use crate::companion_ext::soft_delete_curator_item;
use crate::repository::{
    automatic_progress_status, clamp_recorded_progress, is_completed_status, normalize_tag_list,
    query_curator_items, update_curator_cell, CellPatch, CuratorItem, MAX_EPISODE_TOTAL,
};
use crate::studio_features::{
    batch_assign_series, batch_assign_series_with_roles, carrier_label, character_id_by_bangumi, create_new_curator_item, delete_character,
    delete_character_relation, delete_series, link_character_work_with_propagation,
    list_characters_manage, list_series_manage, list_tags_manage, merge_duplicate_group,
    merge_series, merge_tags, purge_unused_tag_by_id, purge_unused_tags_with_snapshot,
    remove_member_from_series, rename_series, rename_tag, restore_purged_tag, set_character_tags,
    toggle_tag_blocked, unlink_character_work, update_series_member_role, upsert_character,
    upsert_character_relation_with_propagation, read_character_fields, save_character_profile,
    write_character_fields, NewCuratorItemInput, PurgedTagSnapshot, UpsertCharacterInput,
    UpsertCharacterRelationInput,
};
use crate::{Result, StudioError};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use uuid::Uuid;

fn norm_title_check(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn check_expected_title(op: &Value, item: &CuratorItem) -> Result<()> {
    let Some(expected) = op
        .get("expected_title")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
    else {
        return Ok(());
    };
    let ne = norm_title_check(expected);
    let nt = norm_title_check(&item.title);
    let no = norm_title_check(&item.original_title);
    let ok = !ne.is_empty()
        && ((!nt.is_empty() && (nt == ne || nt.contains(&ne) || ne.contains(&nt)))
            || (!no.is_empty() && (no == ne || no.contains(&ne) || ne.contains(&no))));
    if !ok {
        return Err(StudioError::InvalidInput(format!(
            "TITLE_MISMATCH: 作品 #{} 实际标题为《{}》，与传入的 expected_title「{expected}」不一致，已拦截防误写。请核对正确的作品 ID。",
            item.id, item.title
        )));
    }
    Ok(())
}

fn find_item<'a>(items: &'a [CuratorItem], kind: Option<&str>, id: i64) -> Option<&'a CuratorItem> {
    items.iter().find(|item| item.id == id && kind.is_none_or(|kind| item.kind == kind))
}

fn resolve_work(items: &[CuratorItem], id: i64) -> Result<&CuratorItem> {
    let mut matches = items.iter().filter(|item| item.id == id);
    let item = matches.next().ok_or_else(|| StudioError::InvalidInput(
        format!("找不到作品 #{id}，请重新查询藏品。"),
    ))?;
    if matches.next().is_some() {
        return Err(StudioError::InvalidInput(format!("作品编号 #{id} 对应多个载体，请指定 kind。")));
    }
    Ok(item)
}

/// 统一的作品目标解析：传入 kind 时按载体精确查找；省略 kind 时走多载体歧义拦截（验证 2）
fn resolve_work_by_kind_or_unique<'a>(
    items: &'a [CuratorItem],
    kind: Option<&str>,
    id: i64,
) -> Result<&'a CuratorItem> {
    let clean_kind = kind.map(str::trim).filter(|s| !s.is_empty());
    if let Some(k) = clean_kind {
        find_item(items, Some(k), id).ok_or_else(|| {
            StudioError::InvalidInput(format!("找不到{}下的作品 #{id}，请先查库核对。", carrier_label(k)))
        })
    } else {
        resolve_work(items, id)
    }
}

/// D1/E：校验 ISO 日期格式（允许空字符串表示清空，或 YYYY / YYYY-MM / YYYY-MM-DD）；
/// 若传入带时间后缀的 UTC 串（如 `2026-10-10T16:00:00.000Z`），给出按东八区换算的建议值，避免直接截断少一天。
fn validate_iso_release_date(raw: &str) -> Result<()> {
    let s = raw.trim();
    if s.is_empty() {
        return Ok(());
    }
    if s.len() == 4 && s.chars().all(|c| c.is_ascii_digit()) {
        let y: i32 = s.parse().unwrap_or(0);
        if (1900..=2100).contains(&y) {
            return Ok(());
        }
    }
    if s.len() == 7 && s.as_bytes()[4] == b'-' {
        let padded = format!("{s}-01");
        if chrono::NaiveDate::parse_from_str(&padded, "%Y-%m-%d").is_ok() {
            return Ok(());
        }
    }
    if s.len() == 10 && chrono::NaiveDate::parse_from_str(s, "%Y-%m-%d").is_ok() {
        return Ok(());
    }
    if let Some(suggested) = crate::agent::tools_read::suggest_normalized_release_date(s) {
        return Err(StudioError::InvalidInput(format!(
            "release_date `{s}` 包含时间后缀或非标准分隔符，请使用标准日期格式（按东八区换算建议：`{suggested}`）。"
        )));
    }
    Err(StudioError::InvalidInput(format!(
        "release_date `{s}` 格式无效，请使用 YYYY-MM-DD（如 2014-04-11）、YYYY-MM、YYYY 或空字符串清空。"
    )))
}

fn validate_status(conn: &Connection, kind: &str, status: &str, current: Option<&str>) -> Result<()> {
    let status = status.trim();
    let allowed = match kind {
        "game" => matches!(status, "想玩" | "未玩" | "在玩" | "已玩" | "搁置" | "弃坑" | "通关"),
        "audio" => matches!(status, "想听" | "未听" | "在听" | "已听" | "搁置" | "弃坑"),
        "anime" | "book" => {
            matches!(status, "在看" | "看完" | "想看" | "未看" | "搁置" | "弃坑" | "已看"
                | "想读" | "在读" | "未读" | "读完" | "已读" | "已完成" | "完结")
                || conn.query_row(
                    "SELECT EXISTS (SELECT 1 FROM watch_statuses WHERE name = ?1)",
                    [status], |row| row.get(0),
                )?
        }
        _ => false,
    };
    if !status.is_empty() && (allowed || current == Some(status)) {
        return Ok(());
    }
    let (label, supported) = match kind {
        "game" => ("游戏", "想玩/未玩/在玩/已玩/通关/搁置/弃坑"),
        "audio" => ("音声", "想听/未听/在听/已听/搁置/弃坑"),
        "book" => ("书籍", "想看/想读/在看/在读/看完/读完/搁置/弃坑"),
        _ => ("动画", "想看/未看/在看/看完/搁置/弃坑"),
    };
    Err(StudioError::InvalidInput(format!(
        "「{status}」不是{label}可用的状态（{label}支持：{supported}；混合清单批量改状态仅支持共同状态「搁置」「弃坑」）。"
    )))
}

const WORK_UPDATE_SUPPORTED_FIELDS: &str =
    "status, progress_primary, progress_total, total_episodes, personal_score, memo, review, tags, release_date, cover_url, workType, mediaFormat, media_format, movieFormat, studio_or_circle, synopsis, original_title, external_source, external_id, field_source";

const WORK_CREATE_SUPPORTED_FIELDS: &str =
    "kind, title, original_title, cover_url, status, progress_primary, progress_total, total_episodes, studio_or_circle, release_date, public_score, external_source, external_id, synopsis, workType, mediaFormat, media_format, movieFormat, tags, series_name, series_role, field_source";

fn normalize_work_format_value(raw: &str) -> String {
    let t = raw.trim();
    if t.is_empty() {
        return String::new();
    }
    match t.to_ascii_lowercase().as_str() {
        "tv" | "tv动画" => "TV".into(),
        "movie" | "剧场版" => "剧场版".into(),
        "剧场版动画" => "剧场版动画".into(),
        "ova" => "OVA".into(),
        "oad" => "OAD".into(),
        "web" | "ona" | "web动画" | "网络动画" => "WEB".into(),
        "总集篇" | "recap" => "总集篇".into(),
        "特别篇" | "sp" => "特别篇".into(),
        _ => t.to_string(),
    }
}

fn format_is_movie_str(s: &str) -> bool {
    let low = s.trim().to_ascii_lowercase();
    low.contains("剧场版")
        || low.contains("劇場版")
        || low.contains("电影")
        || low.contains("映画")
        || low == "movie"
        || low.contains(" movie")
}

/// D3/D7：统一解析 `media_format` / `mediaFormat` / `workType` / `work_type` / `movieFormat` / `movie_format`，
/// 并校验字段间不矛盾（例如 `workType="剧场版"` 与 `movieFormat=false` 冲突）。
fn resolve_op_media_format(op: &Value, kind: &str) -> Result<Option<String>> {
    let mf_raw = op
        .get("mediaFormat")
        .or_else(|| op.get("media_format"))
        .and_then(Value::as_str);
    let wt_raw = op
        .get("workType")
        .or_else(|| op.get("work_type"))
        .and_then(Value::as_str);
    let movie_flag = op
        .get("movieFormat")
        .or_else(|| op.get("movie_format"))
        .and_then(Value::as_bool);

    if mf_raw.is_none() && wt_raw.is_none() && movie_flag.is_none() {
        return Ok(None);
    }
    if matches!(kind, "game" | "audio") {
        return Err(StudioError::InvalidInput(format!(
            "载体 {kind} 不支持 workType / mediaFormat / movieFormat 字段。"
        )));
    }

    let mf_norm = mf_raw.map(normalize_work_format_value);
    let wt_norm = wt_raw.map(normalize_work_format_value);

    // 校验显式传入的 workType 与 mediaFormat 若同为非空时不可矛盾
    if let (Some(ref m), Some(ref w)) = (&mf_norm, &wt_norm) {
        if !m.is_empty() && !w.is_empty() && format_is_movie_str(m) != format_is_movie_str(w) {
            return Err(StudioError::InvalidInput(format!(
                "workType({w}) 与 mediaFormat({m}) 语义冲突，请统一。"
            )));
        }
    }
    let chosen = mf_norm
        .filter(|s| !s.is_empty())
        .or_else(|| wt_norm.filter(|s| !s.is_empty()))
        .or_else(|| {
            if mf_raw.is_some() || wt_raw.is_some() {
                Some(String::new())
            } else {
                None
            }
        });

    if let Some(is_movie) = movie_flag {
        if let Some(ref c) = chosen {
            if !c.is_empty() && format_is_movie_str(c) != is_movie {
                let field_name = if wt_raw.is_some() { "workType" } else { "mediaFormat" };
                return Err(StudioError::InvalidInput(format!(
                    "字段 {field_name}({c}) 与 movieFormat({is_movie}) 语义冲突：{} 对应的 movieFormat 应为 {}，请统一或只传 {field_name}。",
                    c,
                    format_is_movie_str(c)
                )));
            }
        } else {
            return Ok(Some(if is_movie { "剧场版".into() } else { "TV".into() }));
        }
    }

    Ok(chosen)
}

fn parse_personal_score_input(value: &Value) -> Option<f64> {
    if value.is_null() {
        return Some(0.0);
    }
    value.as_f64().filter(|score| (0.0..=10.0).contains(score))
}

fn validate_work_update(op: &Value, item: &CuratorItem, conn: &Connection) -> Result<()> {
    let fields = op.as_object().ok_or_else(|| StudioError::InvalidInput("作品变更须为对象。".into()))?;
    let mut rejected = Vec::<String>::new();
    for (name, value) in fields {
        let valid = match name.as_str() {
            "status" | "memo" | "review" | "action" | "kind" | "resolvedTitle" | "title"
            | "expected_title" | "original_title" | "external_source" | "external_id"
            | "release_date" | "cover_url" | "studio_or_circle" | "media_format" | "mediaFormat"
            | "workType" | "work_type" | "synopsis" | "field_source" | "_expectedRevision"
            | "_rehydrateSource" | "_coverStrategyReason" => value.is_string(),
            "movieFormat" | "movie_format" | "_coverIsLocal" | "_fromResultSnapshot" => value.is_boolean(),
            "progress_primary" | "progress_total" | "total_episodes" => value.as_i64().is_some_and(|n| n >= 0),
            "personal_score" => parse_personal_score_input(value).is_some(),
            "tags" => value.as_array().is_some_and(|tags| tags.iter().all(Value::is_string)),
            "work_id" => value.as_i64().is_some_and(|id| id > 0),
            "_previewVersion" => value.as_u64() == Some(2),
            "_progressEffects" => value.is_array(),
            "_fieldBaselines" => value.is_object(),
            _ => false,
        };
        if !valid {
            rejected.push(name.clone());
        }
    }
    if !rejected.is_empty() {
        let score_hint = if rejected.iter().any(|k| k == "personal_score") {
            "（提示：personal_score 须为 0~10 数值或 null，传 0 或 null 可清空评分恢复为未评）"
        } else {
            ""
        };
        return Err(StudioError::InvalidInput(format!(
            "字段 [{}] 的值无效或 update 操作不支持该字段{}。update 支持字段：{WORK_UPDATE_SUPPORTED_FIELDS}。",
            rejected.join(", "),
            score_hint
        )));
    }
    let changes = [
        "status",
        "memo",
        "review",
        "progress_primary",
        "progress_total",
        "total_episodes",
        "personal_score",
        "tags",
        "external_source",
        "external_id",
        "original_title",
        "release_date",
        "cover_url",
        "studio_or_circle",
        "media_format",
        "mediaFormat",
        "workType",
        "work_type",
        "movieFormat",
        "movie_format",
        "synopsis",
    ];
    if !changes.iter().any(|key| fields.contains_key(*key)) {
        let passed: Vec<&str> = fields.keys().map(String::as_str).collect();
        return Err(StudioError::InvalidInput(format!(
            "请选择需要修改的字段（当前仅传入：[{}]）。update 支持字段：{WORK_UPDATE_SUPPORTED_FIELDS}。",
            passed.join(", ")
        )));
    }
    let _ = resolve_op_media_format(op, &item.kind)?;
    if let Some(rd) = op["release_date"].as_str() {
        validate_iso_release_date(rd)?;
    }
    if let Some(status) = op["status"].as_str() {
        validate_status(conn, &item.kind, status, Some(&item.status))?;
    }
    if fields.contains_key("external_source") || fields.contains_key("external_id") {
        let external_id = op["external_id"].as_str().unwrap_or_default();
        if !matches!(item.kind.as_str(), "anime" | "book") || op["external_source"] != "bangumi"
            || !external_id.parse::<u64>().is_ok_and(|id| id > 0 && id.to_string() == external_id)
        {
            return Err(StudioError::InvalidInput("绑定需要动画或书籍、Bangumi 来源及有效的数字条目 ID。".into()));
        }
        let already_bound: bool = conn.query_row(
            "SELECT coalesce(trim(externalId), '') NOT IN ('', 'null') FROM animes WHERE id = ?1",
            [item.id], |row| row.get(0),
        )?;
        if already_bound {
            return Err(StudioError::InvalidInput(format!(
                "作品 #{}《{}》已有外部绑定，请先在作品详情中核对。",
                item.id, item.title
            )));
        }
        if let Some((holder_id, holder_kind, holder_title)) =
            find_active_bangumi_holder(conn, external_id, item.id)?
        {
            return Err(StudioError::InvalidInput(format!(
                "该 Bangumi 条目（{external_id}）已绑定到在库作品：《{holder_title}》（{} #{holder_id}）。",
                carrier_label(&holder_kind)
            )));
        }
    }
    if op["memo"].as_str().is_some_and(|text| text.trim().chars().count() > 10_000) {
        return Err(StudioError::InvalidInput("短评最多 10000 字。".into()));
    }
    if op["tags"].as_array().is_some_and(|tags| {
        tags.iter().any(|tag| tag.as_str().is_some_and(|text| text.trim().trim_start_matches('#').trim().is_empty()))
    }) {
        return Err(StudioError::InvalidInput("标签不能是空白。清空标签时请提供空数组。".into()));
    }
    if (item.kind == "game" && extract_op_progress_total(op).is_some())
        || (matches!(item.kind.as_str(), "game" | "audio") && op["memo"].is_string())
    {
        return Err(StudioError::InvalidInput("此载体不支持该进度或短评字段。".into()));
    }
    if op["progress_total"].is_number() && op["total_episodes"].is_number()
        && op["progress_total"] != op["total_episodes"]
    {
        return Err(StudioError::InvalidInput("提案中提供了两个不同的总进度。".into()));
    }
    if matches!(item.kind.as_str(), "game" | "audio")
        && op["tags"].as_array().is_some_and(|tags| {
            tags.iter().any(|tag| tag.as_str().is_some_and(|text| text.contains([',', '，', ';'])))
        })
    {
        return Err(StudioError::InvalidInput("单个游戏或音声标签不能含逗号或分号。".into()));
    }
    // 进度语义安全：修改 workType/mediaFormat 后，或同时传入当前进度与总进度时，校验 progressPrimary <= progressTotal
    let req_total_opt = extract_op_progress_total(op);
    let req_primary_opt = op.get("progress_primary").and_then(Value::as_i64);
    let touches_format = fields.contains_key("workType")
        || fields.contains_key("work_type")
        || fields.contains_key("mediaFormat")
        || fields.contains_key("media_format")
        || fields.contains_key("movieFormat")
        || fields.contains_key("movie_format");
    if matches!(item.kind.as_str(), "anime" | "book")
        && (touches_format || (req_total_opt.is_some() && req_primary_opt.is_some()))
    {
        let eff_total = req_total_opt.unwrap_or(item.progress_total);
        let eff_primary = req_primary_opt.unwrap_or(item.progress_primary);
        if eff_total > 0 && eff_primary > eff_total {
            return Err(StudioError::InvalidInput(format!(
                "字段 progress_primary({eff_primary}) 与 progress_total({eff_total}) 冲突：形态或进度修改后当前进度超过总进度，请先调整进度或总集数。"
            )));
        }
    }
    Ok(())
}

fn validate_work_create(op: &Value) -> Result<()> {
    let fields = op.as_object().ok_or_else(|| StudioError::InvalidInput("新增作品须为对象。".into()))?;
    let mut rejected = Vec::<String>::new();
    for (name, value) in fields {
        let valid = match name.as_str() {
            "action" | "kind" | "title" | "original_title" | "cover_url" | "status"
            | "studio_or_circle" | "release_date" | "external_source" | "external_id"
            | "synopsis" | "media_format" | "mediaFormat" | "workType" | "work_type"
            | "series_name" | "series_role" | "role" | "field_source" => value.is_string(),
            "movieFormat" | "movie_format" => value.is_boolean(),
            "total_episodes" | "progress_total" | "progress_primary" => {
                value.as_i64().is_some_and(|n| n >= 0)
            }
            "public_score" => value.as_f64().is_some_and(|score| (0.0..=10.0).contains(&score)),
            "tags" => value.as_array().is_some_and(|tags| tags.iter().all(Value::is_string)),
            _ => false,
        };
        if !valid {
            rejected.push(name.clone());
        }
    }
    if !rejected.is_empty() {
        return Err(StudioError::InvalidInput(format!(
            "新增作品不支持字段 [{}] 或其值无效。create 支持字段：{WORK_CREATE_SUPPORTED_FIELDS}。",
            rejected.join(", ")
        )));
    }
    let kind = op.get("kind").and_then(Value::as_str).unwrap_or("anime");
    let _ = resolve_op_media_format(op, kind)?;
    if let Some(rd) = op.get("release_date").and_then(Value::as_str) {
        validate_iso_release_date(rd)?;
    }
    if op.get("progress_total").is_some()
        && op.get("total_episodes").is_some()
        && op["progress_total"] != op["total_episodes"]
    {
        return Err(StudioError::InvalidInput("新增作品提供了两个不同的总进度（progress_total 与 total_episodes）。".into()));
    }
    let total = extract_op_progress_total(op).unwrap_or(0);
    if let Some(primary) = op.get("progress_primary").and_then(Value::as_i64) {
        if total > 0 && primary > total {
            return Err(StudioError::InvalidInput(format!(
                "新增作品当前进度 progress_primary({primary}) 不能超过总进度({total})。"
            )));
        }
    }
    if op["tags"].as_array().is_some_and(|tags| {
        tags.iter().any(|tag| tag.as_str().is_some_and(|text| text.trim().trim_start_matches('#').trim().is_empty()))
    }) {
        return Err(StudioError::InvalidInput("新增作品标签不能包含空白项。".into()));
    }
    Ok(())
}

fn tag_names(values: &[Value]) -> Vec<String> {
    values.iter().filter_map(Value::as_str).map(str::to_string).collect()
}

fn work_tag_links(conn: &Connection, work_id: i64) -> Result<Vec<(i64, String)>> {
    let mut stmt = conn.prepare_cached(
        "SELECT t.id, t.name FROM tags t JOIN anime_tags a ON a.tagId = t.id
         WHERE a.animeId = ?1 ORDER BY t.id",
    )?;
    let rows = stmt.query_map([work_id], |row| Ok((row.get(0)?, row.get(1)?)))?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

fn restore_tag_links(conn: &Connection, work_id: i64, links: &Value, legacy_names: &Value) -> Result<()> {
    let changed = || StudioError::InvalidInput("原标签已改名或移除，无法安全撤销本次标签修改。".into());
    let ids = if links.is_array() {
        let links: Vec<(i64, String)> = serde_json::from_value(links.clone())?;
        let mut stmt = conn.prepare_cached("SELECT name FROM tags WHERE id = ?1")?;
        for (id, original_name) in &links {
            let current = stmt.query_row([id], |row| row.get::<_, String>(0)).optional()?;
            if current.as_deref() != Some(original_name) {
                return Err(changed());
            }
        }
        links.into_iter().map(|(id, _)| id).collect::<Vec<_>>()
    } else {
        let names = legacy_names.as_array().ok_or_else(changed)?;
        let mut stmt = conn.prepare_cached("SELECT id FROM tags WHERE name = ?1")?;
        let mut ids = Vec::new();
        for name in names {
            let name = name.as_str().ok_or_else(changed)?;
            ids.push(stmt.query_row([name], |row| row.get(0)).optional()?.ok_or_else(changed)?);
        }
        ids
    };
    conn.prepare_cached("DELETE FROM anime_tags WHERE animeId = ?1")?.execute([work_id])?;
    let mut insert = conn.prepare_cached("INSERT INTO anime_tags (animeId, tagId) VALUES (?1, ?2)")?;
    for tag_id in ids {
        insert.execute(params![work_id, tag_id])?;
    }
    Ok(())
}

fn tag_definitions(conn: &Connection) -> Result<Vec<(i64, String)>> {
    let mut stmt = conn.prepare("SELECT id, json_array(name,color,blocked,sortOrder) FROM tags")?;
    let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

fn score_to_raw(score: f64) -> i64 {
    (score.clamp(0.0, 10.0) * 10.0).round() as i64
}

fn format_progress_preview(kind: &str, primary: i64, total: i64) -> String {
    if total > 0 {
        format!("{primary}/{total}")
    } else if kind == "game" {
        format!("{primary} 分钟")
    } else {
        format!("{primary}/未知")
    }
}

struct WorkProgressPreview {
    primary: i64,
    total: i64,
    show_progress: bool,
    effects: Vec<(&'static str, String, &'static str)>,
}

fn work_progress_preview(conn: &Connection, op: &Value, item: &CuratorItem) -> Result<WorkProgressPreview> {
    let is_episode_work = matches!(item.kind.as_str(), "anime" | "book");
    let explicit_status = op["status"].as_str().map(str::trim);
    let requested_total = extract_op_progress_total(op);
    let total = requested_total
        .map(|total| if is_episode_work { total.min(MAX_EPISODE_TOTAL) } else { total })
        .unwrap_or(item.progress_total);
    let requested_primary = op["progress_primary"].as_i64();
    let explicit_completion = is_episode_work && explicit_status.is_some_and(is_completed_status);
    let auto_fill_progress = explicit_completion && requested_primary.is_none() && total > 0;
    let primary = if let Some(requested_primary) = requested_primary {
        clamp_recorded_progress(requested_primary, total)
    } else if auto_fill_progress {
        total
    } else {
        item.progress_primary
    };
    let auto_status = if explicit_status.is_none() && requested_primary.is_some() && primary > 0 {
        let current = if is_episode_work { item.status.trim() } else { item.status.trim_matches(' ') };
        automatic_progress_status(&item.kind, current, total > 0 && primary >= total)
    } else {
        None
    };
    let mut effects = Vec::new();
    if let Some(status) = auto_status {
        effects.push(("状态", item.status.clone(), status));
    }
    if is_episode_work && (explicit_completion || auto_status.is_some()) {
        let (start, finish): (String, String) = conn.prepare_cached(
            "SELECT COALESCE(watchStartDate, ''), COALESCE(watchFinishDate, '') FROM animes WHERE id = ?1",
        )?.query_row([item.id], |row| Ok((row.get(0)?, row.get(1)?)))?;
        if auto_status.is_some() && start.is_empty() {
            effects.push(("开始日期", "未填写".into(), "执行时填写日期"));
        }
        if (explicit_completion || auto_status == Some("看完")) && finish.is_empty() {
            effects.push(("完结日期", "未填写".into(), "执行时填写日期"));
        }
    }
    Ok(WorkProgressPreview {
        primary,
        total,
        show_progress: requested_primary.is_some() || requested_total.is_some() || auto_fill_progress,
        effects,
    })
}

fn extract_op_progress_total(op: &Value) -> Option<i64> {
    op.get("progress_total")
        .and_then(|v| v.as_i64())
        .or_else(|| op.get("total_episodes").and_then(|v| v.as_i64()))
        .map(|v| v.max(0))
}

fn work_columns(kind: &str) -> (&'static str, &'static str) {
    match kind {
        "game" => ("games", "status,playMinutes,rating,review,tags,originalTitle,releaseDate,coverUrl,developer,deletedAt"),
        "audio" => ("audio_works", "status,listenedSeconds,durationSeconds,rating,review,tags,originalTitle,releaseDate,coverUrl,circle,deletedAt"),
        _ => ("animes", "status,watchedEpisodes,totalEpisodes,rating,ratingGrade,memo,review,originalTitle,airDate,coverUrl,studio,mediaFormat,synopsis,watchStartDate,watchFinishDate,statusChangedAt,deletedAt"),
    }
}

fn raw_work_fields(conn: &Connection, kind: &str, id: i64) -> Result<String> {
    let (table, columns) = work_columns(kind);
    Ok(conn.query_row(
        &format!("SELECT json_array({columns}) FROM {table} WHERE id = ?1"),
        [id],
        |row| row.get(0),
    )?)
}

fn item_revision(item: &CuratorItem) -> Result<String> {
    Ok(hex::encode(Sha256::digest(serde_json::to_vec(item)?)))
}

/// 忽略 `last_modified_at`（updatedAt 时间戳）的内容哈希：
/// 用于多提案依次撤销场景（先撤 P2 会刷新 updatedAt，但内容已精准恢复到 P1 落库状态，不应误判为外部编辑）。
fn item_content_revision(item: &CuratorItem) -> Result<String> {
    let mut normalized = item.clone();
    normalized.last_modified_at = 0;
    Ok(hex::encode(Sha256::digest(serde_json::to_vec(&normalized)?)))
}

fn check_revision(item: Option<&CuratorItem>, expected: Option<&str>) -> Result<()> {
    if let (Some(item), Some(expected)) = (item, expected) {
        if item_revision(item)? == expected {
            return Ok(());
        }
    }
    Err(StudioError::InvalidInput("作品已变化或提案已过期，请重新生成后确认。".into()))
}

fn check_content_revision(item: Option<&CuratorItem>, expected: Option<&str>) -> Result<()> {
    if let (Some(item), Some(expected)) = (item, expected) {
        if item_content_revision(item)? == expected {
            return Ok(());
        }
    }
    Err(StudioError::InvalidInput("作品已变化或提案已过期，请重新生成后确认。".into()))
}

fn read_live_synopsis(conn: &Connection, kind: &str, wid: i64) -> String {
    if matches!(kind, "anime" | "book") {
        conn.query_row(
            "SELECT COALESCE(synopsis, '') FROM animes WHERE id = ?1",
            [wid],
            |r| r.get::<_, String>(0),
        )
        .unwrap_or_default()
    } else {
        String::new()
    }
}

fn read_live_external_id(conn: &Connection, kind: &str, wid: i64) -> String {
    if matches!(kind, "anime" | "book") {
        conn.query_row(
            "SELECT COALESCE(externalId, '') FROM animes WHERE id = ?1",
            [wid],
            |r| r.get::<_, String>(0),
        )
        .unwrap_or_default()
    } else {
        String::new()
    }
}

/// 捕获一条 `update` 操作拟改动字段集合的真实 `before` 值快照，用于字段级乐观锁与 `no-op` 检测。
fn capture_work_field_baselines(
    op: &Value,
    item: &CuratorItem,
    sync_conn: &Connection,
) -> serde_json::Map<String, Value> {
    let mut base = serde_json::Map::new();
    let Some(obj) = op.as_object() else {
        return base;
    };
    if obj.contains_key("status") {
        base.insert("status".into(), json!(item.status.trim()));
    }
    if obj.contains_key("progress_primary") {
        base.insert("progress_primary".into(), json!(item.progress_primary));
    }
    if obj.contains_key("progress_total") || obj.contains_key("total_episodes") {
        base.insert("progress_total".into(), json!(item.progress_total));
    }
    if obj.contains_key("personal_score") {
        base.insert(
            "personal_score".into(),
            json!(item.personal_score.map(score_to_raw).unwrap_or(0)),
        );
    }
    if obj.contains_key("memo") {
        base.insert(
            "memo".into(),
            json!(item.memo.as_deref().unwrap_or("").trim()),
        );
    }
    if obj.contains_key("review") {
        base.insert("review".into(), json!(item.review));
    }
    if obj.contains_key("tags") {
        let mut sorted = item.tags.clone();
        sorted.sort();
        base.insert("tags".into(), json!(sorted));
    }
    if obj.contains_key("original_title") {
        base.insert("original_title".into(), json!(item.original_title.trim()));
    }
    if obj.contains_key("release_date") {
        base.insert("release_date".into(), json!(item.release_date.trim()));
    }
    if obj.contains_key("cover_url") {
        base.insert("cover_url".into(), json!(item.cover_url.trim()));
    }
    if obj.contains_key("studio_or_circle") {
        base.insert("studio_or_circle".into(), json!(item.studio_or_circle.trim()));
    }
    if obj.contains_key("media_format")
        || obj.contains_key("mediaFormat")
        || obj.contains_key("workType")
        || obj.contains_key("work_type")
        || obj.contains_key("movieFormat")
        || obj.contains_key("movie_format")
    {
        base.insert(
            "media_format".into(),
            json!(item.media_format.as_deref().unwrap_or("").trim()),
        );
    }
    if obj.contains_key("synopsis") {
        base.insert(
            "synopsis".into(),
            json!(read_live_synopsis(sync_conn, &item.kind, item.id).trim()),
        );
    }
    if obj.contains_key("external_id") {
        base.insert(
            "external_id".into(),
            json!(read_live_external_id(sync_conn, &item.kind, item.id).trim()),
        );
    }
    base
}

fn target_value_for_field(op: &Value, field: &str, kind: &str) -> Option<Value> {
    match field {
        "status" => op.get("status").and_then(Value::as_str).map(|s| json!(s.trim())),
        "progress_primary" => op.get("progress_primary").and_then(Value::as_i64).map(|v| json!(v)),
        "progress_total" => extract_op_progress_total(op).map(|v| json!(v)),
        "personal_score" => op
            .get("personal_score")
            .and_then(parse_personal_score_input)
            .map(|s| json!(score_to_raw(s))),
        "memo" => op.get("memo").and_then(Value::as_str).map(|s| json!(s.trim())),
        "review" => op.get("review").and_then(Value::as_str).map(|s| json!(s)),
        "tags" => op.get("tags").and_then(Value::as_array).map(|a| {
            let mut norm = normalize_tag_list(&tag_names(a));
            norm.sort();
            json!(norm)
        }),
        "original_title" => op.get("original_title").and_then(Value::as_str).map(|s| json!(s.trim())),
        "release_date" => op.get("release_date").and_then(Value::as_str).map(|s| json!(s.trim())),
        "cover_url" => op.get("cover_url").and_then(Value::as_str).map(|s| json!(s.trim())),
        "studio_or_circle" => op.get("studio_or_circle").and_then(Value::as_str).map(|s| json!(s.trim())),
        "media_format" => resolve_op_media_format(op, kind)
            .ok()
            .flatten()
            .map(|s| json!(s.trim())),
        "synopsis" => op.get("synopsis").and_then(Value::as_str).map(|s| json!(s.trim())),
        "external_id" => op.get("external_id").and_then(Value::as_str).map(|s| json!(s.trim())),
        _ => None,
    }
}

/// 检查单条 `update` 操作的所有目标字段当前真实值是否已全部等于目标 `after`（即 `no-op`）。
fn is_work_update_already_satisfied(op: &Value, item: &CuratorItem, sync_conn: &Connection) -> bool {
    let live = capture_work_field_baselines(op, item, sync_conn);
    if live.is_empty() {
        return false;
    }
    live.iter().all(|(field, cur_val)| {
        target_value_for_field(op, field, &item.kind).is_some_and(|target| &target == cur_val)
    })
}

/// 字段级乐观锁校验：
/// - 优先比对 `_fieldBaselines` 中每个拟改字段的当前真实值：非重叠字段被其他提案修改时不误杀；
/// - 若目标字段已被其他操作修改且不等于目标值，明确指出冲突字段名、原基线与当前真实值；
/// - 兼容旧版无 `_fieldBaselines` 的历史提案（回退到整行 `check_revision`）。
fn check_work_update_optimistic_lock(
    op: &Value,
    item_opt: Option<&CuratorItem>,
    sync_conn: &Connection,
) -> Result<()> {
    let Some(item) = item_opt else {
        return Err(StudioError::InvalidInput("作品已不存在，无法执行提案。".into()));
    };
    // 由筛选清单快照（result_id）派生的批量提案维持整行 revision 校验，防止筛选条件依赖的字段变化后误写
    if op.get("_fromResultSnapshot").and_then(Value::as_bool) == Some(true) {
        return check_revision(Some(item), op["_expectedRevision"].as_str());
    }
    if let Some(baselines) = op.get("_fieldBaselines").and_then(Value::as_object) {
        if !baselines.is_empty() {
            if let Some(expected_title) = baselines.get("_identityTitle").and_then(Value::as_str) {
                if item.title.trim() != expected_title.trim() {
                    return Err(StudioError::InvalidInput(format!(
                        "目标作品身份已变化（原《{expected_title}》，现《{}》），请重新生成提案。",
                        item.title
                    )));
                }
            }
            let live = capture_work_field_baselines(op, item, sync_conn);
            for (field, expected_val) in baselines {
                if field == "_identityTitle" {
                    continue;
                }
                let current_val = live.get(field).unwrap_or(&Value::Null);
                if current_val != expected_val {
                    // 若目标字段当前已经等于拟写入值（幂等达成），留给上层 no-op 判定而不误报冲突
                    if target_value_for_field(op, field, &item.kind)
                        .is_some_and(|target| &target == current_val)
                    {
                        continue;
                    }
                    return Err(StudioError::InvalidInput(format!(
                        "目标字段 `{field}` 已被其他操作修改（提案基线：{expected_val}，当前真实值：{current_val}），请重新生成提案。"
                    )));
                }
            }
            return Ok(());
        }
    }
    check_revision(Some(item), op["_expectedRevision"].as_str())
}

fn op_touched_fields_for_overlap(op: &Value) -> Vec<String> {
    let mut out = Vec::new();
    let Some(obj) = op.as_object() else {
        return out;
    };
    for key in [
        "status",
        "progress_primary",
        "progress_total",
        "total_episodes",
        "personal_score",
        "memo",
        "review",
        "tags",
        "original_title",
        "release_date",
        "cover_url",
        "studio_or_circle",
        "media_format",
        "mediaFormat",
        "workType",
        "work_type",
        "movieFormat",
        "movie_format",
        "synopsis",
        "external_id",
    ] {
        if obj.contains_key(key) {
            let canonical = match key {
                "total_episodes" => "progress_total",
                "mediaFormat" | "workType" | "work_type" | "movieFormat" | "movie_format" => "media_format",
                other => other,
            };
            if !out.iter().any(|x| x == canonical) {
                out.push(canonical.to_string());
            }
        }
    }
    out
}

/// 扫描同会话其他 `pending` 提案，检测是否写同一作品同一字段并生成可读重叠提醒。
fn detect_session_proposal_overlaps(
    local_conn: &Connection,
    session_id: &str,
    new_ops: &[Value],
) -> Vec<String> {
    let mut warnings = Vec::<String>::new();
    let Ok(existing) = crate::agent::storage::load_session_proposals(local_conn, session_id) else {
        return warnings;
    };
    let mut new_keys = Vec::<(String, i64, String, String)>::new();
    for op in new_ops {
        if op.get("action").and_then(Value::as_str) != Some("update") {
            continue;
        }
        let kind = op.get("kind").and_then(Value::as_str).unwrap_or("anime").to_string();
        let wid = op.get("work_id").and_then(Value::as_i64).unwrap_or(0);
        if wid <= 0 {
            continue;
        }
        let title = op
            .get("resolvedTitle")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        for f in op_touched_fields_for_overlap(op) {
            new_keys.push((kind.clone(), wid, f, title.clone()));
        }
    }
    if new_keys.is_empty() {
        return warnings;
    }
    for prev in existing.into_iter().filter(|p| p.status == "pending" && p.kind == "work_mutations") {
        let Some(prev_ops) = prev.ops.as_array() else {
            continue;
        };
        let mut overlapped_desc = Vec::<String>::new();
        for pop in prev_ops {
            let pkind = pop.get("kind").and_then(Value::as_str).unwrap_or("anime");
            let pwid = pop.get("work_id").and_then(Value::as_i64).unwrap_or(0);
            if pwid <= 0 {
                continue;
            }
            let pfields = op_touched_fields_for_overlap(pop);
            for (nk, nwid, nf, ntitle) in &new_keys {
                if nk == pkind && *nwid == pwid && pfields.iter().any(|pf| pf == nf) {
                    let label = if ntitle.is_empty() {
                        format!("#{nwid}.{nf}")
                    } else {
                        format!("《{ntitle}》#{nwid} 的 {nf}")
                    };
                    if !overlapped_desc.contains(&label) {
                        overlapped_desc.push(label);
                    }
                }
            }
        }
        if !overlapped_desc.is_empty() {
            let short_id: String = prev.id.chars().take(8).collect();
            warnings.push(format!(
                "与待确认提案「{}」({short_id}) 存在同字段重叠：{}。建议合并或取消旧提案。",
                prev.title,
                overlapped_desc.into_iter().take(4).collect::<Vec<_>>().join("、")
            ));
        }
    }
    warnings
}

pub fn proposal_has_non_revertible_or_destructive_ops(proposal: &AgentProposal) -> bool {
    let Some(ops) = proposal.ops.as_array() else {
        return false;
    };
    for op in ops {
        let act = op
            .get("action")
            .or_else(|| op.get("op"))
            .and_then(Value::as_str)
            .unwrap_or("");
        if matches!(
            act,
            "soft_delete"
                | "merge"
                | "merge_works"
                | "merge_duplicates"
                | "merge_tag"
                | "delete_tag"
                | "delete_series"
                | "merge_series"
                | "update_member_role"
                | "remove_member"
                | "delete_character"
                | "delete_relation"
                | "unlink_work"
        ) {
            return true;
        }
    }
    false
}

fn settle_proposal_writes(sync: &Connection, local: &Connection) -> Result<()> {
    let mut pending = local.prepare(
        "SELECT id FROM agent_proposal_transitions ORDER BY id",
    )?;
    let rows = pending.query_map([], |row| row.get::<_, String>(0))?;
    let ids = rows.collect::<std::result::Result<Vec<_>, _>>()?;
    if ids.is_empty() {
        return Ok(());
    }
    let tx = local.unchecked_transaction()?;
    for id in &ids {
        let committed: bool = sync.query_row(
            "SELECT EXISTS (SELECT 1 FROM agent_proposal_commits WHERE id = ?1)",
            [id],
            |row| row.get(0),
        )?;
        if committed {
            tx.execute(
                "UPDATE agent_proposals
                 SET last_attempt_failed = 0, last_error = NULL, (status, applied_at, rollback_snapshot_json, applied_count, applied_indices_json) = (
                     SELECT status, applied_at, rollback_snapshot_json,
                            coalesce(applied_count, agent_proposals.applied_count),
                            coalesce(applied_indices_json, agent_proposals.applied_indices_json)
                     FROM agent_proposal_transitions WHERE id = ?1
                 )
                 WHERE id = (SELECT proposal_id FROM agent_proposal_transitions WHERE id = ?1)",
                [id],
            )?;
        }
        tx.execute("DELETE FROM agent_proposal_transitions WHERE id = ?1", [id])?;
    }
    tx.commit()?;
    for id in &ids {
        sync.execute("DELETE FROM agent_proposal_commits WHERE id = ?1", [id])?;
    }
    Ok(())
}

pub fn recover_proposal_writes(sync: &Connection, local: &Connection) -> Result<()> {
    let has_pending: bool = local.query_row(
        "SELECT EXISTS (SELECT 1 FROM agent_proposal_transitions)",
        [],
        |row| row.get(0),
    )?;
    if has_pending {
        let tx = rusqlite::Transaction::new_unchecked(sync, rusqlite::TransactionBehavior::Immediate)?;
        settle_proposal_writes(&tx, local)?;
        tx.commit()?;
    }
    Ok(())
}

/// 延迟到主库 `sync_conn` 事务真正 COMMIT 后再写入 `desktop_local.db` 的本地库副作用。
/// 严禁在 `sync_conn` 事务块内直接写入处于 autocommit 的 `local_conn`，避免主库回滚时产生跨库脏数据。
enum DeferredLocalEffect {
    RecordFieldSource {
        kind: String,
        work_id: i64,
        field: String,
        source: String,
    },
    DeleteFieldSource {
        kind: String,
        work_id: i64,
        field: String,
    },
}

fn apply_deferred_local_effects(local: &Connection, effects: &[DeferredLocalEffect]) {
    if effects.is_empty() {
        return;
    }
    let started_tx = match local.execute_batch("BEGIN IMMEDIATE") {
        Ok(()) => true,
        Err(err) => {
            eprintln!("[agent] 延迟写入本地库开启事务失败，改为逐条提交：{err}");
            false
        }
    };
    for effect in effects {
        match effect {
            DeferredLocalEffect::RecordFieldSource {
                kind,
                work_id,
                field,
                source,
            } => {
                if let Err(err) = crate::agent::storage::record_work_field_source(
                    local, kind, *work_id, field, source,
                ) {
                    eprintln!(
                        "[agent] 记录字段来源失败 ({kind} #{work_id} {field}={source})：{err}"
                    );
                }
            }
            DeferredLocalEffect::DeleteFieldSource {
                kind,
                work_id,
                field,
            } => {
                if let Err(err) =
                    crate::agent::storage::delete_work_field_source(local, kind, *work_id, field)
                {
                    eprintln!("[agent] 清理字段来源失败 ({kind} #{work_id} {field})：{err}");
                }
            }
        }
    }
    if started_tx {
        if let Err(err) = local.execute_batch("COMMIT") {
            eprintln!("[agent] 延迟写入本地库提交事务失败，执行回滚避免锁残留：{err}");
            let _ = local.execute_batch("ROLLBACK");
        }
    }
}

fn atomic_write(
    sync: &mut Connection,
    local: &Connection,
    perform: impl FnOnce(&mut Connection) -> Result<(ProposalApplyResult, Option<Value>, Vec<DeferredLocalEffect>)>,
) -> Result<ProposalApplyResult> {
    if !local.is_autocommit() {
        return Err(StudioError::InvalidInput("提案暂时无法写入，请稍后重试。".into()));
    }
    sync.execute_batch("BEGIN IMMEDIATE")?;
    let staged: Result<(ProposalApplyResult, Vec<DeferredLocalEffect>)> = (|| {
        settle_proposal_writes(sync, local)?;
        let (result, snapshot, local_effects) = perform(sync)?;
        let id = Uuid::new_v4().to_string();
        let proposal = &result.proposal;
        let snapshot_json = snapshot.map(|value| value.to_string());
        let is_applied_status = crate::agent::types::is_proposal_applied_status(&proposal.status);
        let applied_indices_json = (is_applied_status && !result.applied_indices.is_empty())
            .then(|| serde_json::to_string(&result.applied_indices).ok())
            .flatten();
        // 私有库先保存待完成状态，由藏品事务中的凭据决定重开时是否发布。
        sync.execute("INSERT INTO agent_proposal_commits (id) VALUES (?1)", [&id])?;
        local.execute(
            "INSERT INTO agent_proposal_transitions
             (id, proposal_id, status, applied_at, rollback_snapshot_json, applied_count, applied_indices_json)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                id,
                proposal.id,
                proposal.status,
                proposal.applied_at,
                snapshot_json,
                (is_applied_status || proposal.status == "noop").then_some(result.applied_count),
                applied_indices_json
            ],
        )?;
        sync.execute_batch("COMMIT")?;
        Ok((result, local_effects))
    })();
    if staged.is_err() && !sync.is_autocommit() {
        sync.execute_batch("ROLLBACK")?;
    }
    let (result, local_effects) = staged?;
    // 数据已提交。若私有库暂时无法同步，下次读取或执行提案时继续恢复。
    recover_proposal_writes(sync, local).map_err(|_| {
        StudioError::InvalidInput("藏品变更已保存，提案状态暂时无法同步。请重新打开对话查看。".into())
    })?;
    apply_deferred_local_effects(local, &local_effects);
    Ok(result)
}

pub fn create_work_mutations_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<(AgentProposal, bool)> {
    let items = query_curator_items(sync_conn, local_conn)?;
    let selection = args.get("result_id").and_then(Value::as_str);
    let mut batch_unchanged_note: Option<String> = None;
    let derived_ops;
    let ops = if let Some(result_id) = selection {
        let captured = load_library_result(local_conn, result_id, Some(session_id))?;
        let patch = args.get("patch").and_then(Value::as_object)
            .ok_or_else(|| StudioError::InvalidInput("批量提案需要为清单指定同一项修改。".into()))?;
        if args.get("operations").is_some() {
            return Err(StudioError::InvalidInput("批量清单与逐项操作请分别提交。".into()));
        }
        if patch.keys().any(|key| {
            !matches!(
                key.as_str(),
                "status" | "tags" | "add_tags" | "remove_tags" | "personal_score"
            )
        }) {
            return Err(StudioError::InvalidInput(
                "清单批量修改支持状态、个人评分、替换标签(tags)、追加标签(add_tags)和移除标签(remove_tags)。".into(),
            ));
        }
        let has_replace_tags = patch.contains_key("tags");
        let has_delta_tags = patch.contains_key("add_tags") || patch.contains_key("remove_tags");
        if has_replace_tags && has_delta_tags {
            return Err(StudioError::InvalidInput(
                "tags（整组替换）与 add_tags/remove_tags（增量修改）不能同时使用，请二选一。".into(),
            ));
        }
        let add_tags: Vec<String> = patch
            .get("add_tags")
            .and_then(Value::as_array)
            .map(|a| normalize_tag_list(&tag_names(a)))
            .unwrap_or_default();
        let remove_tags: HashSet<String> = patch
            .get("remove_tags")
            .and_then(Value::as_array)
            .map(|a| {
                normalize_tag_list(&tag_names(a))
                    .into_iter()
                    .map(|t| t.to_ascii_lowercase())
                    .collect()
            })
            .unwrap_or_default();
        if patch.contains_key("add_tags") && add_tags.is_empty() && remove_tags.is_empty() {
            return Err(StudioError::InvalidInput("add_tags 不能为空。".into()));
        }
        if patch.contains_key("remove_tags") && remove_tags.is_empty() && add_tags.is_empty() {
            return Err(StudioError::InvalidInput("remove_tags 不能为空。".into()));
        }
        let existing: std::collections::HashMap<_, _> = items.iter()
            .map(|item| ((item.kind.as_str(), item.id), item)).collect();
        let mut built = Vec::new();
        let mut unchanged_cnt = 0usize;
        for target in &captured {
            let live = existing.get(&(target.kind.as_str(), target.id)).copied();
            check_revision(live, Some(&item_revision(target)?))?;
            let live_item = live.unwrap();
            let mut op_map = patch.clone();
            op_map.remove("add_tags");
            op_map.remove("remove_tags");
            if has_delta_tags {
                let mut next_tags: Vec<String> = live_item
                    .tags
                    .iter()
                    .filter(|t| !remove_tags.contains(&t.to_ascii_lowercase()))
                    .cloned()
                    .collect();
                let mut seen_lower: HashSet<String> = next_tags
                    .iter()
                    .map(|t| t.to_ascii_lowercase())
                    .collect();
                for a in &add_tags {
                    if seen_lower.insert(a.to_ascii_lowercase()) {
                        next_tags.push(a.clone());
                    }
                }
                let tags_changed = next_tags != live_item.tags;
                if tags_changed {
                    op_map.insert("tags".into(), json!(next_tags));
                } else if op_map.is_empty() {
                    unchanged_cnt += 1;
                    continue;
                }
            }
            let mut op = Value::Object(op_map);
            op["action"] = json!("update");
            op["work_id"] = json!(target.id);
            op["kind"] = json!(target.kind);
            built.push(op);
        }
        if built.is_empty() {
            return Err(StudioError::InvalidInput(format!(
                "清单中全部 {} 部作品均已满足目标标签/字段，无需修改。",
                captured.len()
            )));
        }
        if unchanged_cnt > 0 {
            batch_unchanged_note = Some(format!("另有 {unchanged_cnt} 部作品无需改动已自动跳过"));
        }
        derived_ops = built;
        &derived_ops
    } else {
        args
        .get("operations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| StudioError::InvalidInput("请选择要修改的作品。".into()))?
    };
    if ops.is_empty() {
        return Err(StudioError::InvalidInput("operations 不能为空".into()));
    }
    if selection.is_none() && ops.len() > 500 {
        return Err(StudioError::InvalidInput("单次提案最多包含 500 条作品变更，请分批整理".into()));
    }

    let indexed: std::collections::HashMap<_, _> = items.iter()
        .map(|item| ((item.kind.as_str(), item.id), item)).collect();
    let mut verified_ops = Vec::<Value>::new();
    let mut diffs = Vec::<ProposalItemDiff>::new();
    let mut targets = HashSet::new();
    let mut claimed_bangumi_ids = HashSet::<String>::new();
    let mut is_single_quick_log = ops.len() == 1 && selection.is_none();

    for (idx, op) in ops.iter().enumerate() {
        if !op.is_object() || op.get("action").is_some_and(|action| !action.is_string()) {
            return Err(StudioError::InvalidInput(format!("第 {} 项的动作格式不正确。", idx + 1)));
        }
        let action = op
            .get("action")
            .and_then(|v| v.as_str())
            .unwrap_or("update")
            .trim();
        match action {
            "create" => {
                is_single_quick_log = false;
                validate_work_create(op)?;
                let title = op
                    .get("title")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .ok_or_else(|| StudioError::InvalidInput(format!("第 {} 项新增作品缺少 title", idx + 1)))?;
                let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                let status = op.get("status").and_then(Value::as_str).map(str::trim).unwrap_or(match kind {
                    "game" => "想玩", "audio" => "想听", _ => "在看",
                });
                validate_status(sync_conn, kind, status, None)?;
                let resolved_mf = resolve_op_media_format(op, kind)?;
                let mut verified = op.clone();
                verified["action"] = json!(action);
                verified["status"] = json!(status);
                if let Some(ref mf) = resolved_mf {
                    verified["media_format"] = json!(mf);
                }
                if let Some(total) = extract_op_progress_total(op) {
                    verified["total_episodes"] = json!(total);
                }
                let mut after_parts = vec![format!("入库 · 状态：{status}")];
                if let Some(ref mf) = resolved_mf.as_deref().filter(|s| !s.is_empty()) {
                    after_parts.push(format!("形态：{mf}"));
                }
                if let Some(primary) = op.get("progress_primary").and_then(Value::as_i64) {
                    let tot = extract_op_progress_total(op).unwrap_or(0);
                    after_parts.push(format!("进度：{}", format_progress_preview(kind, primary, tot)));
                } else if let Some(tot) = extract_op_progress_total(op).filter(|&t| t > 0) {
                    after_parts.push(format!("总进度：{tot}"));
                }
                if let Some(rd) = op.get("release_date").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()) {
                    after_parts.push(format!("日期：{rd}"));
                }
                if let Some(sn) = op.get("series_name").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()) {
                    after_parts.push(format!("系列：{sn}"));
                }
                if let Some(tags) = op.get("tags").and_then(Value::as_array) {
                    let t = normalize_tag_list(&tag_names(tags));
                    if !t.is_empty() {
                        after_parts.push(format!("标签：[{}]", t.join(",")));
                    }
                }
                if op.get("cover_url").and_then(Value::as_str).map(str::trim).is_some_and(|s| !s.is_empty()) {
                    after_parts.push("含封面".into());
                }
                verified_ops.push(verified);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("《{title}》（{}）", carrier_label(kind)),
                    action_label: "新增作品".into(),
                    before: "库中无此条目".into(),
                    after: after_parts.join(" · "),
                });
            }
            "soft_delete" => {
                is_single_quick_log = false;
                let wid = op
                    .get("work_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput(format!("第 {} 项缺少 work_id", idx + 1)))?;
                let item = match op.get("kind") {
                    Some(kind) => kind.as_str().and_then(|kind| indexed.get(&(kind, wid)).copied())
                        .ok_or_else(|| StudioError::InvalidInput("找不到该载体下的作品。".into()))?,
                    None => resolve_work(&items, wid)?,
                };
                check_expected_title(op, item)?;
                let mut vop = op.clone();
                vop["action"] = json!(action);
                vop["kind"] = json!(item.kind);
                vop["resolvedTitle"] = json!(item.title);
                vop["_expectedRevision"] = json!(item_revision(item)?);
                if !targets.insert((item.kind.as_str(), item.id)) {
                    return Err(StudioError::InvalidInput("同一作品在提案中只能出现一次。".into()));
                }
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("《{}》（{} #{}）", item.title, carrier_label(&item.kind), item.id),
                    action_label: "移入回收站".into(),
                    before: format!("状态：{}", item.status),
                    after: "移入回收站（可恢复）".into(),
                });
            }
            "update" => {
                let wid = op
                    .get("work_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput(format!("第 {} 项更新缺少 work_id", idx + 1)))?;
                let item = match op.get("kind") {
                    Some(kind) => kind.as_str().and_then(|kind| indexed.get(&(kind, wid)).copied())
                        .ok_or_else(|| StudioError::InvalidInput("找不到该载体下的作品。".into()))?,
                    None => resolve_work(&items, wid)?,
                };
                check_expected_title(op, item)?;
                validate_work_update(op, item, sync_conn)?;

                let mut before_parts = Vec::new();
                let mut after_parts = Vec::new();
                let mut touched_heavy = false;

                let status = op.get("status").and_then(Value::as_str).map(str::trim);
                if let Some(st) = status {
                    before_parts.push(format!("状态:{}", item.status));
                    after_parts.push(format!("状态:{st}"));
                    touched_heavy = true;
                }
                let new_total = extract_op_progress_total(op);
                let progress = work_progress_preview(sync_conn, op, item)?;
                if progress.show_progress {
                    before_parts.push(format!(
                        "进度:{}",
                        format_progress_preview(&item.kind, item.progress_primary, item.progress_total)
                    ));
                    after_parts.push(format!(
                        "进度:{}",
                        format_progress_preview(&item.kind, progress.primary, progress.total)
                    ));
                    if new_total.is_some() {
                        touched_heavy = true;
                    }
                }
                for (label, before, after) in &progress.effects {
                    before_parts.push(format!("{label}:{before}"));
                    after_parts.push(format!("{label}:{after}"));
                }
                if let Some(sc) = op.get("personal_score").and_then(parse_personal_score_input) {
                    let old_s = item
                        .personal_score
                        .map(|s| format!("{s:.1}"))
                        .unwrap_or_else(|| "未评".into());
                    before_parts.push(format!("评分:{old_s}"));
                    after_parts.push(if score_to_raw(sc) == 0 { "评分:未评".into() } else { format!("评分:{sc:.1}") });
                }
                if let Some(memo) = op.get("memo").and_then(|v| v.as_str()) {
                    before_parts.push(format!("短评:{}", item.memo.as_deref().unwrap_or("空")));
                    after_parts.push(format!("短评:{}", memo.trim()));
                }
                if let Some(rev) = op.get("review").and_then(|v| v.as_str()) {
                    before_parts.push(format!("长评\n{}", if item.review.is_empty() { "未填写" } else { &item.review }));
                    after_parts.push(format!("长评\n{}", if rev.is_empty() { "清空" } else { rev }));
                    touched_heavy = true;
                }
                if let Some(tags) = op.get("tags").and_then(|v| v.as_array()) {
                    let new_tags = normalize_tag_list(&tag_names(tags));
                    before_parts.push(format!("标签:[{}]", item.tags.join(",")));
                    after_parts.push(format!("标签:[{}]", new_tags.join(",")));
                    touched_heavy = true;
                }
                if let Some(external_id) = op["external_id"].as_str() {
                    if !claimed_bangumi_ids.insert(external_id.to_string()) {
                        return Err(StudioError::InvalidInput(format!(
                            "同一份提案中不能将 Bangumi 条目（{external_id}）重复绑定到多部作品。"
                        )));
                    }
                    before_parts.push("Bangumi：未绑定".into());
                    after_parts.push(format!("Bangumi：{external_id}"));
                    touched_heavy = true;
                }
                let mut effective_orig_title: Option<String> = None;
                if let Some(orig) = op["original_title"].as_str().map(str::trim).filter(|s| !s.is_empty()) {
                    if item.original_title.trim().is_empty() {
                        before_parts.push("原名:空".into());
                        after_parts.push(format!("原名:{orig}"));
                        effective_orig_title = Some(orig.to_string());
                        touched_heavy = true;
                    }
                }
                if let Some(rd) = op.get("release_date").and_then(Value::as_str) {
                    let rd_trimmed = rd.trim();
                    let old_rd = if item.release_date.trim().is_empty() { "空" } else { item.release_date.trim() };
                    before_parts.push(format!("日期:{old_rd}"));
                    after_parts.push(format!("日期:{}", if rd_trimmed.is_empty() { "清空" } else { rd_trimmed }));
                    touched_heavy = true;
                }
                if let Some(cov) = op.get("cover_url").and_then(Value::as_str) {
                    let cov_trimmed = cov.trim();
                    let old_cov = if item.cover_url.trim().is_empty() {
                        "无封面"
                    } else if item.cover_url.trim().starts_with("animeow-cover://") {
                        "本地封面"
                    } else {
                        "远程封面"
                    };
                    let strategy_reason = op
                        .get("_coverStrategyReason")
                        .and_then(Value::as_str)
                        .map(str::trim)
                        .filter(|s| !s.is_empty());
                    let new_cov = if cov_trimmed.is_empty() {
                        "清空".to_string()
                    } else if cov_trimmed.starts_with("animeow-cover://") {
                        match strategy_reason {
                            Some(r) => format!("落盘本地（{r}）"),
                            None => "落盘本地".to_string(),
                        }
                    } else {
                        match strategy_reason {
                            Some(r) => format!("远程引用（{r}）"),
                            None => "远程引用".to_string(),
                        }
                    };
                    before_parts.push(format!("封面:{old_cov}"));
                    after_parts.push(format!("封面:{new_cov}"));
                    touched_heavy = true;
                }
                if let Some(st) = op.get("studio_or_circle").and_then(Value::as_str) {
                    let old_st = if item.studio_or_circle.trim().is_empty() { "空" } else { item.studio_or_circle.trim() };
                    before_parts.push(format!("制作/社团:{old_st}"));
                    after_parts.push(format!("制作/社团:{}", if st.trim().is_empty() { "清空" } else { st.trim() }));
                    touched_heavy = true;
                }
                let resolved_update_mf = resolve_op_media_format(op, &item.kind)?;
                if let Some(ref mf) = resolved_update_mf {
                    let old_mf_display = match item.media_format.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                        Some(explicit) => explicit.to_string(),
                        None => format!("空（当前推断:{}）", crate::agent::tools_read::infer_work_type(item)),
                    };
                    before_parts.push(format!("形态:{old_mf_display}"));
                    after_parts.push(format!("形态:{}", if mf.trim().is_empty() { "清空" } else { mf.trim() }));
                    touched_heavy = true;
                }
                if let Some(syn) = op.get("synopsis").and_then(Value::as_str) {
                    let live_syn = read_live_synopsis(sync_conn, &item.kind, item.id);
                    let old_syn = if live_syn.trim().is_empty() {
                        "空".to_string()
                    } else {
                        format!("{}字", live_syn.trim().chars().count())
                    };
                    before_parts.push(format!("简介:{old_syn}"));
                    after_parts.push(format!("简介:{}字", syn.trim().chars().count()));
                    touched_heavy = true;
                }

                if after_parts.is_empty() {
                    return Err(StudioError::InvalidInput(format!(
                        "第 {} 项更新未提供任何有效修改字段。update 支持字段：{WORK_UPDATE_SUPPORTED_FIELDS}。",
                        idx + 1
                    )));
                }
                if touched_heavy {
                    is_single_quick_log = false;
                }

                let mut vop = op.clone();
                vop["action"] = json!(action);
                vop["kind"] = json!(item.kind);
                if let Some(status) = status {
                    vop["status"] = json!(status);
                }
                if let Some(ref mf) = resolved_update_mf {
                    vop["media_format"] = json!(mf);
                }
                vop["resolvedTitle"] = json!(item.title);
                vop["_expectedRevision"] = json!(item_revision(item)?);
                vop["_previewVersion"] = json!(2);
                if let Some(fields) = vop.as_object_mut() {
                    fields.remove("title");
                    fields.remove("_progressEffects");
                    if let Some(orig) = effective_orig_title {
                        fields.insert("original_title".into(), json!(orig));
                    } else {
                        fields.remove("original_title");
                    }
                    if !progress.effects.is_empty() {
                        fields.insert("_progressEffects".into(), json!(progress.effects));
                    }
                }
                if !targets.insert((item.kind.as_str(), item.id)) {
                    return Err(StudioError::InvalidInput("同一作品在提案中只能出现一次。".into()));
                }
                if let Some(t) = new_total {
                    vop["progress_total"] = json!(t);
                }
                let mut field_baselines = capture_work_field_baselines(&vop, item, sync_conn);
                if !field_baselines.is_empty() {
                    field_baselines.insert("_identityTitle".into(), json!(item.title));
                    vop["_fieldBaselines"] = Value::Object(field_baselines);
                }
                if selection.is_some() {
                    vop["_fromResultSnapshot"] = json!(true);
                }
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("《{}》（{} #{}）", item.title, carrier_label(&item.kind), item.id),
                    action_label: "修改作品".into(),
                    before: before_parts.join(" · "),
                    after: after_parts.join(" · "),
                });
            }
            other => {
                return Err(StudioError::InvalidInput(format!(
                    "不支持的作品变更动作 `{other}`，可选 create / update / soft_delete"
                )));
            }
        }
    }

    let title = args
        .get("title")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| format!("藏品条目变更方案（{} 项）", diffs.len()));
    let mut summary = args
        .get("reason")
        .and_then(|v| v.as_str())
        .unwrap_or("由智能体根据当前指令生成的作品修改方案")
        .to_string();
    if let Some(note) = batch_unchanged_note {
        summary = format!("{summary}（{note}）");
    }

    let all_updates_already_satisfied = !verified_ops.is_empty()
        && verified_ops.iter().all(|vop| {
            if vop.get("action").and_then(Value::as_str) != Some("update") {
                return false;
            }
            let k = vop.get("kind").and_then(Value::as_str).unwrap_or("anime");
            let wid = vop.get("work_id").and_then(Value::as_i64).unwrap_or(0);
            indexed
                .get(&(k, wid))
                .copied()
                .is_some_and(|it| is_work_update_already_satisfied(vop, it, sync_conn))
        });

    let (initial_status, initial_error, initial_applied_count, overlap_warnings) = if all_updates_already_satisfied {
        is_single_quick_log = false;
        (
            "noop".to_string(),
            Some("所有目标字段当前值已达成，无需重复执行。".to_string()),
            Some(0usize),
            Vec::new(),
        )
    } else {
        (
            "pending".to_string(),
            None,
            None,
            detect_session_proposal_overlaps(local_conn, session_id, &verified_ops),
        )
    };

    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "work_mutations".into(),
        title,
        summary,
        status: initial_status,
        ops: Value::Array(verified_ops),
        diffs,
        can_revert: false,
        applied_count: initial_applied_count,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: initial_error,
        batch_group_id: None,
        overlap_warnings,
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    if proposal.status == "pending" {
        let existing_id: Option<String> = local_conn.query_row(
            "SELECT id FROM agent_proposals WHERE session_id = ?1 AND kind = ?2
             AND status = 'pending' AND ops_json = ?3 ORDER BY created_at DESC LIMIT 1",
            params![session_id, proposal.kind, proposal.ops.to_string()], |row| row.get(0),
        ).optional()?;
        if let Some(id) = existing_id {
            if let Some((existing, _)) = load_proposal(local_conn, &id)? {
                return Ok((existing, false));
            }
        }
    }
    save_proposal_record(local_conn, &proposal, None)?;
    Ok((proposal, is_single_quick_log))
}

pub struct BulkBindProposalOutcome {
    pub proposal: Option<AgentProposal>,
    pub valid_count: usize,
    pub conflicts: Vec<Value>,
    pub skipped: Vec<Value>,
}

/// 批量 Bangumi 绑定提案：将有效条目归并成单份可确认/可整体撤销的提案，冲突项与跳过项独立报告、绝不阻断其余条目。
/// 支持直接传 `bindings` 数组，或传 `from_verify_ids`（由 `search_remote_catalog` 批量核实返回的 `verifyId` 句柄列表）零拷贝合并精确命中项。
pub fn create_bulk_bind_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<BulkBindProposalOutcome> {
    let exclude_ids: HashSet<i64> = args
        .get("exclude_work_ids")
        .and_then(Value::as_array)
        .map(|arr| arr.iter().filter_map(Value::as_i64).collect())
        .unwrap_or_default();

    let mut combined_bindings: Vec<Value> = Vec::new();
    if let Some(ver_ids) = args.get("from_verify_ids").and_then(Value::as_array) {
        for raw_vid in ver_ids {
            if let Some(vid) = raw_vid.as_str().map(str::trim).filter(|s| !s.is_empty()) {
                let loaded = load_verify_batch_bindings(local_conn, vid, Some(session_id))?;
                for entry in loaded {
                    let wid = entry.get("work_id").and_then(Value::as_i64).unwrap_or(-1);
                    if !exclude_ids.contains(&wid) {
                        combined_bindings.push(entry);
                    }
                }
            }
        }
    } else if let Some(single_vid) = args
        .get("from_verify_id")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        let loaded = load_verify_batch_bindings(local_conn, single_vid, Some(session_id))?;
        for entry in loaded {
            let wid = entry.get("work_id").and_then(Value::as_i64).unwrap_or(-1);
            if !exclude_ids.contains(&wid) {
                combined_bindings.push(entry);
            }
        }
    }
    if let Some(manual) = args.get("bindings").and_then(Value::as_array) {
        for entry in manual {
            let wid = entry.get("work_id").and_then(Value::as_i64).unwrap_or(-1);
            if !exclude_ids.contains(&wid) {
                combined_bindings.push(entry.clone());
            }
        }
    }
    if combined_bindings.is_empty() {
        return Err(StudioError::InvalidInput(
            "bulk_bind 模式需要提供非空的 bindings 数组或有效的 from_verify_ids 句柄列表。".into(),
        ));
    }
    if combined_bindings.len() > 500 {
        return Err(StudioError::InvalidInput(
            "单次 bulk_bind 最多包含 500 条绑定，请分批提交。".into(),
        ));
    }
    let bindings = &combined_bindings;

    let items = query_curator_items(sync_conn, local_conn)?;
    let indexed: std::collections::HashMap<_, _> = items
        .iter()
        .map(|item| ((item.kind.as_str(), item.id), item))
        .collect();

    let mut valid_ops = Vec::<Value>::new();
    let mut conflicts = Vec::<Value>::new();
    let mut skipped = Vec::<Value>::new();
    let mut seen_works = HashSet::<(String, i64)>::new();
    let mut seen_ext_ids = HashSet::<String>::new();

    for entry in bindings {
        let Some(wid) = entry.get("work_id").and_then(Value::as_i64) else {
            skipped.push(json!({
                "entry": entry,
                "code": "VALIDATION_ERROR",
                "reason": "缺少有效的 work_id",
            }));
            continue;
        };
        let kind = entry
            .get("kind")
            .and_then(Value::as_str)
            .map(str::trim)
            .unwrap_or("anime");
        let ext_source = entry
            .get("external_source")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or("bangumi");
        let ext_id = entry
            .get("external_id")
            .and_then(|v| v.as_str().map(str::to_string).or_else(|| v.as_i64().map(|n| n.to_string())))
            .unwrap_or_default();
        let ext_id = ext_id.trim().to_string();

        if !matches!(kind, "anime" | "book")
            || ext_source != "bangumi"
            || !ext_id.parse::<u64>().is_ok_and(|id| id > 0 && id.to_string() == ext_id)
        {
            skipped.push(json!({
                "work_id": wid,
                "kind": kind,
                "external_id": ext_id,
                "code": "VALIDATION_ERROR",
                "reason": "绑定仅支持动画或书籍、Bangumi 来源及正整数条目 ID",
            }));
            continue;
        }
        let Some(item) = indexed.get(&(kind, wid)).copied() else {
            skipped.push(json!({
                "work_id": wid,
                "kind": kind,
                "external_id": ext_id,
                "code": "NOT_FOUND_LOCAL",
                "reason": "在库藏品中未找到该作品",
            }));
            continue;
        };
        if !seen_works.insert((kind.to_string(), wid)) {
            skipped.push(json!({
                "work_id": wid,
                "kind": kind,
                "title": item.title,
                "external_id": ext_id,
                "code": "VALIDATION_ERROR",
                "reason": "同一作品在本次批量中重复出现，已跳过后续项",
            }));
            continue;
        }
        let already_bound: bool = sync_conn
            .query_row(
                "SELECT coalesce(trim(externalId), '') NOT IN ('', 'null') FROM animes WHERE id = ?1",
                [wid],
                |row| row.get(0),
            )
            .unwrap_or(false);
        if already_bound {
            conflicts.push(json!({
                "work_id": wid,
                "kind": kind,
                "title": item.title,
                "external_id": ext_id,
                "code": "ALREADY_BOUND",
                "reason": format!("作品 #{wid}《{}》已存在外部绑定，请先核对详情", item.title),
            }));
            continue;
        }
        if let Some((holder_id, holder_kind, holder_title)) =
            find_active_bangumi_holder(sync_conn, &ext_id, wid)?
        {
            conflicts.push(json!({
                "work_id": wid,
                "kind": kind,
                "title": item.title,
                "external_id": ext_id,
                "code": "CONFLICT_EXTERNAL_ID",
                "conflictWorkId": holder_id,
                "conflictKind": holder_kind,
                "conflictTitle": holder_title,
                "reason": format!(
                    "Bangumi {ext_id} 已被在库作品《{holder_title}》（{} #{holder_id}）占用",
                    carrier_label(&holder_kind)
                ),
            }));
            continue;
        }
        if !seen_ext_ids.insert(ext_id.clone()) {
            conflicts.push(json!({
                "work_id": wid,
                "kind": kind,
                "title": item.title,
                "external_id": ext_id,
                "code": "CONFLICT_EXTERNAL_ID",
                "reason": format!("Bangumi {ext_id} 在本批次内已被前面另一部作品选用"),
            }));
            continue;
        }

        let mut op = json!({
            "action": "update",
            "work_id": wid,
            "kind": kind,
            "external_source": "bangumi",
            "external_id": ext_id,
        });
        if let Some(orig) = entry
            .get("original_title")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            if item.original_title.trim().is_empty() {
                op["original_title"] = json!(orig);
            }
        }
        valid_ops.push(op);
    }

    if valid_ops.is_empty() {
        return Ok(BulkBindProposalOutcome {
            proposal: None,
            valid_count: 0,
            conflicts,
            skipped,
        });
    }

    let title = args
        .get("title")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| format!("批量绑定 Bangumi 条目（{} 项）", valid_ops.len()));
    let reason = args
        .get("reason")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| {
            if conflicts.is_empty() && skipped.is_empty() {
                "批量核实后的 Bangumi 外部条目绑定方案（支持一键整体撤销）".into()
            } else {
                format!(
                    "批量绑定方案：可绑定 {} 项，已自动隔离 {} 项冲突与 {} 项跳过（支持一键整体撤销）",
                    valid_ops.len(),
                    conflicts.len(),
                    skipped.len()
                )
            }
        });

    let delegated = json!({
        "title": title,
        "reason": reason,
        "operations": valid_ops,
    });
    let (proposal, _) = create_work_mutations_proposal(sync_conn, local_conn, session_id, &delegated)?;
    let valid_count = proposal.diffs.len();
    Ok(BulkBindProposalOutcome {
        proposal: Some(proposal),
        valid_count,
        conflicts,
        skipped,
    })
}

pub fn create_series_mutations_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<AgentProposal> {
    let ops = args
        .get("operations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| StudioError::InvalidInput("propose_series_mutations 需要 operations 数组".into()))?;
    if ops.is_empty() {
        return Err(StudioError::InvalidInput("operations 不能为空".into()));
    }

    let items = query_curator_items(sync_conn, local_conn)?;
    let series_list = list_series_manage(sync_conn)?;
    let mut verified_ops = Vec::new();
    let mut diffs = Vec::new();

    for (idx, op) in ops.iter().enumerate() {
        let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("").trim();
        match action {
            "assign_series" => {
                let series_name = op
                    .get("series_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .ok_or_else(|| StudioError::InvalidInput("assign_series 缺少 series_name".into()))?;
                let top_role = op
                    .get("series_role")
                    .or_else(|| op.get("role"))
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty());

                // 同时支持标准 targets 数组（每项可带独立 role）与单部顶层 work_id/kind 简写
                let raw_targets: Vec<Value> = if let Some(arr) = op.get("targets").and_then(|v| v.as_array()) {
                    arr.clone()
                } else if let Some(wid) = op.get("work_id").and_then(|v| v.as_i64()) {
                    let mut single = json!({ "work_id": wid });
                    if let Some(k) = op.get("kind").and_then(|v| v.as_str()) {
                        single["kind"] = json!(k);
                    }
                    if let Some(r) = top_role {
                        single["role"] = json!(r);
                    }
                    vec![single]
                } else {
                    return Err(StudioError::InvalidInput(
                        "assign_series 需要提供 targets 数组（如 [{kind, work_id, role?}]）或单部 work_id".into(),
                    ));
                };

                let mut resolved_names = Vec::new();
                let mut resolved_after_roles = Vec::new();
                let mut resolved_targets = Vec::new();
                for t in &raw_targets {
                    let wid = t.get("work_id").and_then(|v| v.as_i64()).ok_or_else(|| {
                        StudioError::InvalidInput("assign_series target 缺少 work_id".into())
                    })?;
                    let kind = t.get("kind").and_then(|v| v.as_str());
                    let item = resolve_work_by_kind_or_unique(&items, kind, wid)?;
                    let target_role = t
                        .get("role")
                        .or_else(|| t.get("series_role"))
                        .and_then(|v| v.as_str())
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .or(top_role);
                    resolved_names.push(format!(
                        "《{}》({})",
                        item.title,
                        item.series_name.as_deref().unwrap_or("未归类")
                    ));
                    if let Some(r) = target_role {
                        resolved_after_roles.push(format!("《{}》:{r}", item.title));
                    }
                    let mut entry = json!({
                        "kind": item.kind,
                        "work_id": item.id,
                        "_expectedRevision": item_revision(item)?,
                    });
                    if let Some(r) = target_role {
                        entry["role"] = json!(r);
                    }
                    resolved_targets.push(entry);
                }
                if resolved_targets.is_empty() {
                    return Err(StudioError::InvalidInput("assign_series targets 不能为空".into()));
                }
                let mut vop = op.clone();
                vop["targets"] = Value::Array(resolved_targets);
                verified_ops.push(vop);
                let after_text = if resolved_after_roles.is_empty() {
                    format!("统一归入系列「{series_name}」")
                } else {
                    format!(
                        "统一归入系列「{series_name}」（{}）",
                        resolved_after_roles.join("、")
                    )
                };
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("{} 部作品归入「{series_name}」", resolved_names.len()),
                    action_label: "归入系列".into(),
                    before: resolved_names.join("、"),
                    after: after_text,
                });
            }
            "delete_empty_series" => {
                let sid = op["series_id"].as_i64().ok_or_else(|| StudioError::InvalidInput("请选择要删除的空系列。".into()))?;
                let series = series_list.iter().find(|series| series.id == sid && series.member_count == 0)
                    .ok_or_else(|| StudioError::InvalidInput("系列已不存在或还有成员，请先核对成员。".into()))?;
                verified_ops.push(json!({"action": action, "series_id": sid, "expected_name": series.name}));
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("系列「{}」", series.name),
                    action_label: "删除空系列".into(),
                    before: "0 部作品".into(),
                    after: "删除系列记录，保留作品。此操作无法撤销。".into(),
                });
            }
            "rename_series" => {
                let sid = op
                    .get("series_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("rename_series 缺少 series_id".into()))?;
                let new_name = op
                    .get("new_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .ok_or_else(|| StudioError::InvalidInput("rename_series 缺少 new_name".into()))?;
                let s = series_list.iter().find(|x| x.id == sid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("series_id={sid} 不存在"))
                })?;
                let mut vop = op.clone();
                vop["expected_name"] = json!(s.name);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("系列 #{sid}（{} 部作品）", s.member_count),
                    action_label: "重命名系列".into(),
                    before: s.name.clone(),
                    after: new_name.to_string(),
                });
            }
            "merge_series" => {
                let src_id = op
                    .get("source_series_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("merge_series 缺少 source_series_id".into()))?;
                let dst_id = op
                    .get("target_series_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("merge_series 缺少 target_series_id".into()))?;
                let src = series_list.iter().find(|x| x.id == src_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("source_series_id={src_id} 不存在"))
                })?;
                let dst = series_list.iter().find(|x| x.id == dst_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("target_series_id={dst_id} 不存在"))
                })?;
                let mut vop = op.clone();
                vop["expected_source_name"] = json!(src.name);
                vop["expected_target_name"] = json!(dst.name);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("合并系列「{}」→「{}」", src.name, dst.name),
                    action_label: "合并系列".into(),
                    before: format!("「{}」({}部) + 「{}」({}部)", src.name, src.member_count, dst.name, dst.member_count),
                    after: format!("合并入「{}」", dst.name),
                });
            }
            "update_member_role" => {
                let wid = op
                    .get("work_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("update_member_role 缺少 work_id".into()))?;
                let kind = op.get("kind").and_then(|v| v.as_str());
                let role = op
                    .get("role")
                    .or_else(|| op.get("series_role"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let item = resolve_work_by_kind_or_unique(&items, kind, wid)?;
                let mut vop = op.clone();
                vop["kind"] = json!(item.kind);
                vop["role"] = json!(role);
                vop["_expectedRevision"] = json!(item_revision(item)?);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("《{}》", item.title),
                    action_label: "调整系列定位".into(),
                    before: item.series_role.clone().unwrap_or_else(|| "未设置".into()),
                    after: if role.trim().is_empty() { "清除定位".into() } else { role.to_string() },
                });
            }
            "remove_member" => {
                let sid = op
                    .get("series_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("remove_member 缺少 series_id".into()))?;
                let wid = op
                    .get("work_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("remove_member 缺少 work_id".into()))?;
                let kind = op.get("kind").and_then(|v| v.as_str());
                let item = resolve_work_by_kind_or_unique(&items, kind, wid)?;
                let mut vop = op.clone();
                vop["kind"] = json!(item.kind);
                vop["_expectedRevision"] = json!(item_revision(item)?);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("《{}》", item.title),
                    action_label: "移出系列".into(),
                    before: item.series_name.clone().unwrap_or_else(|| format!("系列 #{sid}")),
                    after: "设为未归类".into(),
                });
            }
            other => {
                return Err(StudioError::InvalidInput(format!(
                    "不支持的系列操作 `{other}`"
                )));
            }
        }
    }

    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "series_mutations".into(),
        title: args
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("系列整理方案")
            .to_string(),
        summary: args
            .get("reason")
            .and_then(|v| v.as_str())
            .unwrap_or("智能体生成的系列归并与调整方案")
            .to_string(),
        status: "pending".into(),
        ops: Value::Array(verified_ops),
        diffs,
        can_revert: false,
        applied_count: None,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: None,
        batch_group_id: None,
        overlap_warnings: Vec::new(),
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    save_proposal_record(local_conn, &proposal, None)?;
    Ok(proposal)
}

pub fn create_tag_mutations_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<AgentProposal> {
    let ops = args
        .get("operations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| StudioError::InvalidInput("propose_tag_mutations 需要 operations 数组".into()))?;
    if ops.is_empty() {
        return Err(StudioError::InvalidInput("operations 不能为空".into()));
    }
    let tags = list_tags_manage(sync_conn)?;
    let mut verified_ops = Vec::new();
    let mut diffs = Vec::new();
    let mut seen_purge_ids = HashSet::<i64>::new();

    for op in ops.iter() {
        let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("").trim();
        match action {
            "rename_tag" => {
                let tid = op.get("tag_id").and_then(|v| v.as_i64()).ok_or_else(|| {
                    StudioError::InvalidInput("rename_tag 缺少 tag_id".into())
                })?;
                let new_name = op
                    .get("new_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .ok_or_else(|| StudioError::InvalidInput("rename_tag 缺少 new_name".into()))?;
                let t = tags.iter().find(|x| x.id == tid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("tag_id={tid} 不存在"))
                })?;
                let next_idx = verified_ops.len();
                verified_ops.push(op.clone());
                diffs.push(ProposalItemDiff {
                    index: next_idx,
                    target_label: format!("标签「{}」（{} 次使用）", t.name, t.usage_count),
                    action_label: "重命名标签".into(),
                    before: t.name.clone(),
                    after: new_name.to_string(),
                });
            }
            "merge_tags" => {
                let src_id = op
                    .get("source_tag_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("merge_tags 缺少 source_tag_id".into()))?;
                let dst_id = op
                    .get("target_tag_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("merge_tags 缺少 target_tag_id".into()))?;
                let src = tags.iter().find(|x| x.id == src_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("source_tag_id={src_id} 不存在"))
                })?;
                let dst = tags.iter().find(|x| x.id == dst_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("target_tag_id={dst_id} 不存在"))
                })?;
                let next_idx = verified_ops.len();
                verified_ops.push(op.clone());
                diffs.push(ProposalItemDiff {
                    index: next_idx,
                    target_label: format!("合并标签「{}」→「{}」", src.name, dst.name),
                    action_label: "合并标签".into(),
                    before: format!("「{}」({}条) + 「{}」({}条)", src.name, src.usage_count, dst.name, dst.usage_count),
                    after: format!("统一合并为「{}」", dst.name),
                });
            }
            "purge_unused_tags" => {
                if let Some(tid) = op.get("tag_id").and_then(|v| v.as_i64()) {
                    let t = tags.iter().find(|x| x.id == tid).ok_or_else(|| {
                        StudioError::InvalidInput(format!("tag_id={tid} 不存在"))
                    })?;
                    if t.usage_count > 0 {
                        return Err(StudioError::InvalidInput(format!(
                            "标签「{}」当前被 {} 部作品使用，不属于 0 使用残留标签。",
                            t.name, t.usage_count
                        )));
                    }
                    if seen_purge_ids.insert(t.id) {
                        let next_idx = verified_ops.len();
                        verified_ops.push(json!({
                            "action": "purge_unused_tags",
                            "tag_id": t.id
                        }));
                        diffs.push(ProposalItemDiff {
                            index: next_idx,
                            target_label: format!("标签「{}」", t.name),
                            action_label: "清理 0 使用标签".into(),
                            before: "0 次使用（残留标签）".into(),
                            after: "清理移除".into(),
                        });
                    }
                } else {
                    for t in tags.iter().filter(|t| t.usage_count == 0) {
                        if seen_purge_ids.insert(t.id) {
                            let next_idx = verified_ops.len();
                            verified_ops.push(json!({
                                "action": "purge_unused_tags",
                                "tag_id": t.id
                            }));
                            diffs.push(ProposalItemDiff {
                                index: next_idx,
                                target_label: format!("标签「{}」", t.name),
                                action_label: "清理 0 使用标签".into(),
                                before: "0 次使用（残留标签）".into(),
                                after: "清理移除".into(),
                            });
                        }
                    }
                }
            }
            "toggle_tag_blocked" => {
                let tid = op.get("tag_id").and_then(|v| v.as_i64()).ok_or_else(|| {
                    StudioError::InvalidInput("toggle_tag_blocked 缺少 tag_id".into())
                })?;
                let blocked = op.get("blocked").and_then(|v| v.as_bool()).unwrap_or(true);
                let t = tags.iter().find(|x| x.id == tid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("tag_id={tid} 不存在"))
                })?;
                let next_idx = verified_ops.len();
                verified_ops.push(op.clone());
                diffs.push(ProposalItemDiff {
                    index: next_idx,
                    target_label: format!("标签「{}」", t.name),
                    action_label: if blocked { "设为屏蔽" } else { "取消屏蔽" }.into(),
                    before: if t.blocked { "已屏蔽" } else { "正常" }.into(),
                    after: if blocked { "已屏蔽" } else { "正常" }.into(),
                });
            }
            other => {
                return Err(StudioError::InvalidInput(format!("不支持的标签操作 `{other}`")));
            }
        }
    }
    if verified_ops.is_empty() {
        return Err(StudioError::InvalidInput(
            "当前没有 0 使用的残留标签，无需清理。".into(),
        ));
    }

    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "tag_mutations".into(),
        title: args
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("标签整理方案")
            .to_string(),
        summary: args
            .get("reason")
            .and_then(|v| v.as_str())
            .unwrap_or("智能体生成的标签清理与合并方案")
            .to_string(),
        status: "pending".into(),
        ops: Value::Array(verified_ops),
        diffs,
        can_revert: false,
        applied_count: None,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: None,
        batch_group_id: None,
        overlap_warnings: Vec::new(),
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    save_proposal_record(local_conn, &proposal, None)?;
    Ok(proposal)
}

pub fn cancel_pending_proposal(
    local_conn: &Connection,
    session_id: &str,
    proposal_id: &str,
    reason: Option<&str>,
) -> Result<Value> {
    let pid = proposal_id.trim();
    if pid.is_empty() {
        return Err(StudioError::InvalidInput("cancel_proposal 需要提供 proposal_id。".into()));
    }
    let Some((proposal, _)) = load_proposal(local_conn, pid)? else {
        return Err(StudioError::InvalidInput(format!("未找到提案 `{pid}`。")));
    };
    if proposal.session_id != session_id {
        return Err(StudioError::InvalidInput("不能撤回其他会话的提案。".into()));
    }
    if proposal.status != "pending" {
        return Err(StudioError::InvalidInput(format!(
            "提案 `{pid}` 当前状态为 `{}`，仅可撤回尚未确认的 pending 提案（已落库提案请提示用户点击卡片上的「撤销变更」或提交反向修正提案）。",
            proposal.status
        )));
    }
    reject_proposal(local_conn, pid)?;
    Ok(json!({
        "status": "cancelled",
        "proposalId": pid,
        "title": proposal.title,
        "reason": reason.unwrap_or("智能体已主动撤回该待确认提案"),
        "note": "该待确认提案已标记为已撤回/拒绝，不会写入资料库。",
    }))
}

fn character_link_fields<'a>(
    op: &'a Value,
    previous: Option<&'a (Option<String>, Option<String>)>,
) -> (Option<&'a str>, Option<&'a str>) {
    let field = |key, fallback| op.get(key).map_or(fallback, Value::as_str);
    let role = previous.and_then(|(role, _)| role.as_deref());
    let actor = previous.and_then(|(_, actor)| actor.as_deref());
    (field("role_name", role), field("voice_actor", actor))
}

fn character_profile_undo(id: i64, before: &Value, after: &Value) -> Value {
    let mut previous = serde_json::Map::new();
    let mut applied = serde_json::Map::new();
    if let Some(fields) = before.as_object() {
        for (field, value) in fields {
            if after[field] != *value {
                previous.insert(field.clone(), value.clone());
                applied.insert(field.clone(), after[field].clone());
            }
        }
    }
    json!({"type": "restore_character_fields", "character_id": id, "previous": previous, "applied": applied})
}

pub fn create_character_mutations_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<AgentProposal> {
    let ops = args
        .get("operations")
        .and_then(|v| v.as_array())
        .ok_or_else(|| StudioError::InvalidInput("propose_character_mutations 需要 operations 数组".into()))?;
    if ops.is_empty() {
        return Err(StudioError::InvalidInput("operations 不能为空".into()));
    }
    let items = query_curator_items(sync_conn, local_conn)?;
    let chars = list_characters_manage(sync_conn)?;
    let mut verified_ops = Vec::new();
    let mut diffs = Vec::new();
    let mut affected_works = BTreeMap::<i64, String>::new();

    for (idx, op) in ops.iter().enumerate() {
        let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("").trim();
        match action {
            "upsert_character" => {
                let existing = match op.get("id").and_then(Value::as_i64) {
                    Some(id) => Some(chars.iter().find(|c| c.id == id).ok_or_else(|| {
                        StudioError::InvalidInput(format!("找不到角色 #{id}"))
                    })?),
                    None => None,
                };
                let name = op.get("name").and_then(Value::as_str).map(str::trim)
                    .filter(|name| !name.is_empty())
                    .or_else(|| existing.map(|c| c.name.as_str()))
                    .ok_or_else(|| StudioError::InvalidInput("新建角色需要 name".into()))?;
                let name_cn = op.get("name_cn").map(|value| value.as_str().unwrap_or(""))
                    .or_else(|| existing.map(|c| c.name_cn.as_str())).unwrap_or("");
                let display = if name_cn.trim().is_empty() { name } else { name_cn.trim() };
                let mut vop = op.clone();
                if let Some(aid) = op.get("anime_id").and_then(|v| v.as_i64()) {
                    let item = find_item(&items, None, aid).ok_or_else(|| {
                        StudioError::InvalidInput(format!("作品 anime_id={aid} 不存在"))
                    })?;
                    check_expected_title(op, item)?;
                    vop["_resolvedWorkTitle"] = json!(item.title);
                    affected_works.insert(aid, item.title.clone());
                }
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("角色「{display}」"),
                    action_label: if existing.is_some() { "更新角色档案" } else { "新建角色" }.into(),
                    before: existing
                        .map(|c| format!("已有角色 #{}", c.id))
                        .unwrap_or_else(|| "新建档案".into()),
                    after: if let Some(aid) = op.get("anime_id").and_then(|v| v.as_i64()) {
                        let wtitle = affected_works.get(&aid).map(String::as_str).unwrap_or("");
                        format!("保存角色「{display}」并关联《{wtitle}》(#{aid})")
                    } else {
                        format!("保存角色「{display}」")
                    },
                });
            }
            "link_work" => {
                let anime_id = op
                    .get("anime_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("link_work 缺少 anime_id".into()))?;
                let item = find_item(&items, None, anime_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("作品 anime_id={anime_id} 不存在"))
                })?;
                check_expected_title(op, item)?;
                let char_label = op
                    .get("character_name")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
                    .or_else(|| {
                        op.get("character_id")
                            .and_then(|v| v.as_i64())
                            .and_then(|cid| chars.iter().find(|c| c.id == cid))
                            .map(|c| if c.name_cn.is_empty() { c.name.clone() } else { c.name_cn.clone() })
                    })
                    .unwrap_or_else(|| "指定角色".into());
                let role = op.get("role_name").and_then(|v| v.as_str()).unwrap_or("出场");
                let mut vop = op.clone();
                vop["_resolvedWorkTitle"] = json!(item.title);
                affected_works.insert(anime_id, item.title.clone());
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("角色「{char_label}」↔ 《{}》(#{anime_id})", item.title),
                    action_label: "绑定出场作品".into(),
                    before: "未关联或更新定位".into(),
                    after: format!("定位：{role}"),
                });
            }
            "unlink_work" => {
                let anime_id = op
                    .get("anime_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("unlink_work 缺少 anime_id".into()))?;
                let item = find_item(&items, None, anime_id).ok_or_else(|| {
                    StudioError::InvalidInput(format!("作品 anime_id={anime_id} 不存在"))
                })?;
                check_expected_title(op, item)?;
                let cid = resolve_character_id_by_name_or_id(
                    sync_conn,
                    op.get("character_id").and_then(|v| v.as_i64()),
                    op.get("character_name").and_then(|v| v.as_str()),
                )?;
                let c = chars.iter().find(|x| x.id == cid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("character_id={cid} 不存在"))
                })?;
                let linked = c.works.iter().find(|w| w.anime_id == anime_id);
                if linked.is_none() {
                    return Err(StudioError::InvalidInput(format!(
                        "角色「{}」(#{cid}) 当前未关联作品《{}》(#{anime_id})。",
                        if c.name_cn.is_empty() { &c.name } else { &c.name_cn },
                        item.title
                    )));
                }
                let char_label = if c.name_cn.is_empty() { &c.name } else { &c.name_cn };
                let old_role = linked
                    .map(|w| w.role_name.as_str())
                    .filter(|s| !s.trim().is_empty())
                    .unwrap_or("出场");
                let mut vop = op.clone();
                vop["character_id"] = json!(cid);
                vop["_resolvedWorkTitle"] = json!(item.title);
                affected_works.insert(anime_id, item.title.clone());
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("角色「{char_label}」× 《{}》(#{anime_id})", item.title),
                    action_label: "解除作品关联".into(),
                    before: format!("已关联（定位：{old_role}）"),
                    after: "解除关联（可撤销恢复）".into(),
                });
            }
            "upsert_relation" => {
                let src_label = op
                    .get("source_name")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
                    .or_else(|| {
                        op.get("source_character_id")
                            .and_then(|v| v.as_i64())
                            .and_then(|cid| chars.iter().find(|c| c.id == cid))
                            .map(|c| if c.name_cn.is_empty() { c.name.clone() } else { c.name_cn.clone() })
                    })
                    .ok_or_else(|| StudioError::InvalidInput("upsert_relation 缺少源角色标识".into()))?;
                let dst_label = op
                    .get("target_name")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
                    .or_else(|| {
                        op.get("target_character_id")
                            .and_then(|v| v.as_i64())
                            .and_then(|cid| chars.iter().find(|c| c.id == cid))
                            .map(|c| if c.name_cn.is_empty() { c.name.clone() } else { c.name_cn.clone() })
                    })
                    .ok_or_else(|| StudioError::InvalidInput("upsert_relation 缺少目标角色标识".into()))?;
                let rel_type = op
                    .get("relation_type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("关联");
                verified_ops.push(op.clone());
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("{src_label} ↔ {dst_label}"),
                    action_label: "建立角色关系".into(),
                    before: "新增或更新关系连线".into(),
                    after: format!("关系：{rel_type}"),
                });
            }
            "delete_relation" => {
                let rel_id_opt = op
                    .get("relation_id")
                    .or_else(|| op.get("id"))
                    .and_then(|v| v.as_i64())
                    .filter(|&id| id > 0);
                let resolved_rel: Option<(i64, i64, i64, String)> = if let Some(rid) = rel_id_opt {
                    sync_conn
                        .query_row(
                            "SELECT id, sourceCharacterId, targetCharacterId, COALESCE(relationType, '关联')
                             FROM character_relations WHERE id = ?1",
                            [rid],
                            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                        )
                        .optional()?
                } else {
                    let src_id = resolve_character_id_by_name_or_id(
                        sync_conn,
                        op.get("source_character_id").and_then(|v| v.as_i64()),
                        op.get("source_name").and_then(|v| v.as_str()),
                    )?;
                    let dst_id = resolve_character_id_by_name_or_id(
                        sync_conn,
                        op.get("target_character_id").and_then(|v| v.as_i64()),
                        op.get("target_name").and_then(|v| v.as_str()),
                    )?;
                    let rtype = op
                        .get("relation_type")
                        .and_then(|v| v.as_str())
                        .map(str::trim)
                        .filter(|s| !s.is_empty());
                    sync_conn
                        .query_row(
                            "SELECT id, sourceCharacterId, targetCharacterId, COALESCE(relationType, '关联')
                             FROM character_relations
                             WHERE ((sourceCharacterId = ?1 AND targetCharacterId = ?2)
                                 OR (sourceCharacterId = ?2 AND targetCharacterId = ?1))
                               AND (?3 IS NULL OR relationType = ?3)
                             LIMIT 1",
                            params![src_id, dst_id, rtype],
                            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                        )
                        .optional()?
                };
                let (rid, sid, tid, rtype) = resolved_rel.ok_or_else(|| {
                    StudioError::InvalidInput("delete_relation 未找到匹配的人物关系记录。".into())
                })?;
                let name_of = |cid: i64| -> String {
                    chars
                        .iter()
                        .find(|c| c.id == cid)
                        .map(|c| if c.name_cn.is_empty() { c.name.clone() } else { c.name_cn.clone() })
                        .unwrap_or_else(|| format!("#{cid}"))
                };
                let mut vop = op.clone();
                vop["relation_id"] = json!(rid);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("{} ↔ {}（关系 #{rid}）", name_of(sid), name_of(tid)),
                    action_label: "删除角色关系".into(),
                    before: format!("关系：{rtype}"),
                    after: "删除该关系（可撤销恢复）".into(),
                });
            }
            "delete_character" => {
                let cid = resolve_character_id_by_name_or_id(
                    sync_conn,
                    op.get("character_id").or_else(|| op.get("id")).and_then(|v| v.as_i64()),
                    op.get("character_name").or_else(|| op.get("name")).and_then(|v| v.as_str()),
                )?;
                let c = chars.iter().find(|x| x.id == cid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("character_id={cid} 不存在"))
                })?;
                let char_label = if c.name_cn.is_empty() { &c.name } else { &c.name_cn };
                let mut vop = op.clone();
                vop["character_id"] = json!(cid);
                verified_ops.push(vop);
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!("角色「{char_label}」(#{cid})"),
                    action_label: "删除角色".into(),
                    before: format!("已关联 {} 部作品 · {} 条关系", c.works.len(), c.relations.len()),
                    after: "删除角色及其关联/关系（支持一键撤销完整还原）".into(),
                });
            }
            "set_tags" => {
                let cid = op
                    .get("character_id")
                    .and_then(|v| v.as_i64())
                    .ok_or_else(|| StudioError::InvalidInput("set_tags 缺少 character_id".into()))?;
                let c = chars.iter().find(|x| x.id == cid).ok_or_else(|| {
                    StudioError::InvalidInput(format!("character_id={cid} 不存在"))
                })?;
                let new_tags: Vec<String> = op
                    .get("tags")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .filter_map(|x| x.as_str().map(str::trim))
                            .filter(|s| !s.is_empty())
                            .map(str::to_string)
                            .collect()
                    })
                    .unwrap_or_default();
                verified_ops.push(op.clone());
                diffs.push(ProposalItemDiff {
                    index: idx,
                    target_label: format!(
                        "角色「{}」",
                        if c.name_cn.is_empty() { &c.name } else { &c.name_cn }
                    ),
                    action_label: "设置角色标签".into(),
                    before: format!("[{}]", c.tags.join(", ")),
                    after: format!("[{}]", new_tags.join(", ")),
                });
            }
            other => {
                return Err(StudioError::InvalidInput(format!(
                    "不支持的角色变更动作 `{other}`，可选 upsert_character / link_work / unlink_work / upsert_relation / delete_relation / delete_character / set_tags"
                )));
            }
        }
    }

    let base_summary = args
        .get("reason")
        .and_then(|v| v.as_str())
        .unwrap_or("智能体生成的角色及人物关系写入方案")
        .trim()
        .to_string();
    let final_summary = if affected_works.is_empty() {
        base_summary
    } else {
        let works_text: Vec<String> = affected_works
            .iter()
            .map(|(aid, wtitle)| format!("《{wtitle}》(#{aid})"))
            .collect();
        format!("{base_summary}｜涉及关联作品：{}", works_text.join("、"))
    };

    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "character_mutations".into(),
        title: args
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("角色与关系建档方案")
            .to_string(),
        summary: final_summary,
        status: "pending".into(),
        ops: Value::Array(verified_ops),
        diffs,
        can_revert: false,
        applied_count: None,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: None,
        batch_group_id: None,
        overlap_warnings: Vec::new(),
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    save_proposal_record(local_conn, &proposal, None)?;
    Ok(proposal)
}

pub fn create_merge_duplicates_proposal(
    sync_conn: &Connection,
    local_conn: &Connection,
    session_id: &str,
    args: &Value,
) -> Result<AgentProposal> {
    let groups = args
        .get("merges")
        .and_then(|v| v.as_array())
        .ok_or_else(|| StudioError::InvalidInput("propose_merge_duplicates 需要 merges 数组".into()))?;
    if groups.is_empty() {
        return Err(StudioError::InvalidInput("merges 不能为空".into()));
    }
    let items = query_curator_items(sync_conn, local_conn)?;
    let mut verified = Vec::new();
    let mut diffs = Vec::new();

    for (idx, g) in groups.iter().enumerate() {
        let keep_id = g
            .get("keep_id")
            .and_then(|v| v.as_i64())
            .ok_or_else(|| StudioError::InvalidInput("merge 缺少 keep_id".into()))?;
        let kind = g.get("kind").and_then(|v| v.as_str());
        let keep_item = resolve_work_by_kind_or_unique(&items, kind, keep_id)?;
        let drop_ids: Vec<i64> = g
            .get("drop_ids")
            .and_then(|v| v.as_array())
            .map(|arr| arr.iter().filter_map(|v| v.as_i64()).collect())
            .unwrap_or_default();
        let mut drop_labels = Vec::new();
        let mut expected_drop_revs = serde_json::Map::new();
        let mut merged_primary = keep_item.progress_primary;
        let mut merged_total = keep_item.progress_total;
        let mut merged_score = keep_item.personal_score;
        let mut merged_tags: Vec<String> = keep_item.tags.clone();
        let mut merged_tag_set: HashSet<String> = keep_item
            .tags
            .iter()
            .map(|t| t.to_ascii_lowercase())
            .collect();
        let mut merged_ext = keep_item
            .external_id
            .as_ref()
            .map(|eid| format!("{}:{}", keep_item.external_source.as_deref().unwrap_or("ext"), eid));
        let mut dropped_ext_notes = Vec::new();
        let mut merged_memo_len = keep_item.memo.as_deref().map(|s| s.chars().count()).unwrap_or(0);
        let mut memo_from_drop = false;

        for &did in &drop_ids {
            if did == keep_id {
                continue;
            }
            let d = find_item(&items, Some(&keep_item.kind), did).ok_or_else(|| {
                StudioError::InvalidInput(format!("drop_id={did} 不存在"))
            })?;
            expected_drop_revs.insert(did.to_string(), json!(item_revision(d)?));
            merged_primary = merged_primary.max(d.progress_primary);
            merged_total = merged_total.max(d.progress_total);
            if merged_score.is_none() && d.personal_score.is_some() {
                merged_score = d.personal_score;
            }
            for t in &d.tags {
                if merged_tag_set.insert(t.to_ascii_lowercase()) {
                    merged_tags.push(t.clone());
                }
            }
            let d_memo_len = d.memo.as_deref().map(|s| s.chars().count()).unwrap_or(0);
            if d_memo_len > merged_memo_len {
                merged_memo_len = d_memo_len;
                memo_from_drop = true;
            }
            if let Some(ref deid) = d.external_id {
                let d_ext_label = format!("{}:{}", d.external_source.as_deref().unwrap_or("ext"), deid);
                if merged_ext.is_none() {
                    merged_ext = Some(format!("{d_ext_label}(从#{did}移交)"));
                } else {
                    dropped_ext_notes.push(format!("#{did}外链{d_ext_label}清空释放"));
                }
            }
            let d_ext_str = d
                .external_id
                .as_ref()
                .map(|eid| format!("外链:{}:{}", d.external_source.as_deref().unwrap_or("ext"), eid))
                .unwrap_or_else(|| "无外链".into());
            drop_labels.push(format!(
                "《{}》(#{}, 进度:{}, 标签:{}个, {})",
                d.title,
                d.id,
                format_progress_preview(&d.kind, d.progress_primary, d.progress_total),
                d.tags.len(),
                d_ext_str
            ));
        }
        if drop_labels.is_empty() {
            return Err(StudioError::InvalidInput("merge 至少需要一个有效的 drop_id".into()));
        }
        verified.push(json!({
            "kind": keep_item.kind,
            "keep_id": keep_id,
            "keep_title": keep_item.title,
            "drop_ids": drop_ids,
            "_expectedKeepRevision": item_revision(keep_item)?,
            "_expectedDropRevisions": Value::Object(expected_drop_revs),
        }));
        let keep_ext_str = keep_item
            .external_id
            .as_ref()
            .map(|eid| format!("外链:{}:{}", keep_item.external_source.as_deref().unwrap_or("ext"), eid))
            .unwrap_or_else(|| "无外链".into());
        let before_str = format!(
            "主条目 #{}（进度:{}, 评分:{}, 标签:{}个, {}）｜待合并：{}",
            keep_item.id,
            format_progress_preview(&keep_item.kind, keep_item.progress_primary, keep_item.progress_total),
            keep_item.personal_score.map(|s| format!("{s:.1}")).unwrap_or_else(|| "未评".into()),
            keep_item.tags.len(),
            keep_ext_str,
            drop_labels.join("；")
        );
        let mut after_parts = vec![
            format!("进度取较大值:{}", format_progress_preview(&keep_item.kind, merged_primary, merged_total)),
            format!("评分:{}", merged_score.map(|s| format!("{s:.1}")).unwrap_or_else(|| "未评".into())),
            format!("标签取并集({}个):[{}]", merged_tags.len(), merged_tags.iter().take(6).cloned().collect::<Vec<_>>().join(",")),
            format!("外链:{}", merged_ext.unwrap_or_else(|| "无".into())),
        ];
        if memo_from_drop {
            after_parts.push("短评保留较长方".into());
        }
        if !dropped_ext_notes.is_empty() {
            after_parts.push(dropped_ext_notes.join("、"));
        }
        after_parts.push("多余项清空外链后移入回收站（支持一键撤销还原两条记录）".into());
        diffs.push(ProposalItemDiff {
            index: idx,
            target_label: format!("保留《{}》(#{})", keep_item.title, keep_item.id),
            action_label: "合并重复条目".into(),
            before: before_str,
            after: after_parts.join(" · "),
        });
    }

    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "merge_duplicates".into(),
        title: args
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("重复条目合并方案")
            .to_string(),
        summary: args
            .get("reason")
            .and_then(|v| v.as_str())
            .unwrap_or("智能体生成的重复作品合并方案")
            .to_string(),
        status: "pending".into(),
        ops: Value::Array(verified),
        diffs,
        can_revert: false,
        applied_count: None,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: None,
        batch_group_id: None,
        overlap_warnings: Vec::new(),
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    save_proposal_record(local_conn, &proposal, None)?;
    Ok(proposal)
}

fn resolve_character_id_by_name_or_id(
    sync_conn: &Connection,
    id: Option<i64>,
    name: Option<&str>,
) -> Result<i64> {
    if let Some(cid) = id {
        return Ok(cid);
    }
    let Some(n) = name.map(str::trim).filter(|s| !s.is_empty()) else {
        return Err(StudioError::InvalidInput("缺少角色 ID 或名称".into()));
    };
    let chars = list_characters_manage(sync_conn)?;
    if let Some(found) = chars
        .iter()
        .find(|c| c.name.eq_ignore_ascii_case(n) || c.name_cn.eq_ignore_ascii_case(n))
    {
        return Ok(found.id);
    }
    // 找不到时自动创建一个基础角色节点，保证多步提案内“先建角色再建关系”顺畅执行
    upsert_character(
        sync_conn,
        &UpsertCharacterInput {
            id: None,
            bgm_id: None,
            anilist_id: None,
            name: n.to_string(),
            name_cn: Some(n.to_string()),
            image_url: None,
            summary: None,
            gender: None,
            birth_month: None,
            birth_day: None,
            blood_type: None,
            rating: None,
            review: None,
        },
    )
}

/// 确认执行提案：抓取执行前快照、落地数据库变更并标记 `applied`（或在所有目标字段已达成时标为 `noop`）
pub fn commit_proposal(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
    selected_indices: Option<&[usize]>,
) -> Result<ProposalApplyResult> {
    commit_proposal_with_status(sync_conn, local_conn, proposal_id, selected_indices, "applied")
}

/// 分级自动审批提交：走与手动确认完全相同的事务与撤销快照链路，状态标记为 `auto_applied`（支持随时一键撤销）
pub fn commit_proposal_as_auto(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
) -> Result<ProposalApplyResult> {
    commit_proposal_with_status(sync_conn, local_conn, proposal_id, None, "auto_applied")
}

fn commit_proposal_with_status(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
    selected_indices: Option<&[usize]>,
    target_status: &str,
) -> Result<ProposalApplyResult> {
    let result = atomic_write(sync_conn, local_conn, |sync| {
        commit_proposal_inner(sync, local_conn, proposal_id, selected_indices, target_status)
    });
    if let Err(ref err) = result {
        let err_text = err.to_string();
        let _ = local_conn.execute(
            "UPDATE agent_proposals
             SET last_attempt_failed = 1, last_error = ?2
             WHERE id = ?1 AND status = 'pending'
               AND NOT EXISTS (SELECT 1 FROM agent_proposal_transitions WHERE proposal_id = ?1)",
            params![proposal_id, err_text],
        );
    }
    result
}

/// 多提案一键批量顺序提交：共享 `batch_group_id` 支持整批一键撤销；
/// 配合字段级乐观锁，修改不重叠字段的多份提案可连续成功提交；若某份提案全部目标已达成则标记 `noop`，字段冲突则跳过并保留精确报错。
pub fn commit_proposals_batch(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_ids: &[String],
    safe_only: bool,
) -> Result<crate::agent::types::BatchProposalCommitResult> {
    if proposal_ids.is_empty() {
        return Err(StudioError::InvalidInput("请选择至少一份待确认提案。".into()));
    }
    let batch_group_id = format!("bat_{}", &Uuid::new_v4().simple().to_string()[..12]);
    let mut outcomes = Vec::<crate::agent::types::BatchProposalItemOutcome>::new();
    let mut changed_domains = HashSet::<String>::new();
    let mut applied_proposals = 0usize;
    let mut noop_proposals = 0usize;
    let mut skipped_or_failed_proposals = 0usize;
    let mut total_applied_items = 0usize;

    for pid in proposal_ids {
        let Some((prop, _)) = load_proposal(local_conn, pid)? else {
            skipped_or_failed_proposals += 1;
            outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                proposal_id: pid.clone(),
                title: "未知提案".into(),
                outcome: "failed".into(),
                applied_count: 0,
                message: "提案不存在或已被清理".into(),
                proposal: None,
            });
            continue;
        };
        if prop.status != "pending" {
            let outcome_kind = if prop.status == "noop" {
                noop_proposals += 1;
                "noop"
            } else {
                skipped_or_failed_proposals += 1;
                "skipped"
            };
            outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                proposal_id: prop.id.clone(),
                title: prop.title.clone(),
                outcome: outcome_kind.into(),
                applied_count: 0,
                message: format!("当前状态为 `{}`，已跳过", prop.status),
                proposal: Some(prop),
            });
            continue;
        }
        if safe_only && proposal_has_non_revertible_or_destructive_ops(&prop) {
            skipped_or_failed_proposals += 1;
            outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                proposal_id: prop.id.clone(),
                title: prop.title.clone(),
                outcome: "skipped".into(),
                applied_count: 0,
                message: "含删除或不可撤销操作，已按安全过滤跳过（请单独确认）".into(),
                proposal: Some(prop),
            });
            continue;
        }
        match commit_proposal(sync_conn, local_conn, pid, None) {
            Ok(mut applied) => {
                if applied.proposal.status == "noop" {
                    noop_proposals += 1;
                    outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                        proposal_id: prop.id.clone(),
                        title: prop.title.clone(),
                        outcome: "noop".into(),
                        applied_count: 0,
                        message: applied.message,
                        proposal: Some(applied.proposal),
                    });
                } else {
                    let _ = crate::agent::storage::set_proposal_batch_group(local_conn, pid, &batch_group_id);
                    applied.proposal.batch_group_id = Some(batch_group_id.clone());
                    applied_proposals += 1;
                    total_applied_items += applied.applied_count;
                    for d in &applied.changed_domains {
                        changed_domains.insert(d.clone());
                    }
                    outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                        proposal_id: prop.id.clone(),
                        title: prop.title.clone(),
                        outcome: "applied".into(),
                        applied_count: applied.applied_count,
                        message: applied.message,
                        proposal: Some(applied.proposal),
                    });
                }
            }
            Err(err) => {
                skipped_or_failed_proposals += 1;
                let msg = err.to_string();
                let is_conflict = msg.contains("已被其他操作修改") || msg.contains("已过期") || msg.contains("已变化");
                let refreshed = load_proposal(local_conn, pid).ok().flatten().map(|(p, _)| p);
                outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                    proposal_id: prop.id.clone(),
                    title: prop.title.clone(),
                    outcome: if is_conflict { "conflict_skipped" } else { "failed" }.into(),
                    applied_count: 0,
                    message: msg,
                    proposal: refreshed,
                });
            }
        }
    }

    let mut domains: Vec<String> = changed_domains.into_iter().collect();
    domains.sort();
    let message = format!(
        "批量审批完成：已执行 {applied_proposals} 份提案（共 {total_applied_items} 项改动），无需执行 {noop_proposals} 份，跳过/失败 {skipped_or_failed_proposals} 份。"
    );
    Ok(crate::agent::types::BatchProposalCommitResult {
        batch_group_id,
        applied_proposals,
        noop_proposals,
        skipped_or_failed_proposals,
        total_applied_items,
        changed_domains: domains,
        outcomes,
        message,
    })
}

/// 按逆序整批撤销同属一个 `batch_group_id` 的所有可撤销提案。
pub fn revert_proposals_batch(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    batch_group_id: &str,
) -> Result<crate::agent::types::BatchProposalRevertResult> {
    let list = crate::agent::storage::load_proposals_by_batch_group(local_conn, batch_group_id)?;
    if list.is_empty() {
        return Err(StudioError::InvalidInput("未找到对应的批量提交记录。".into()));
    }
    let mut reverted_proposals = 0usize;
    let mut failed_proposals = 0usize;
    let mut changed_domains = HashSet::<String>::new();
    let mut outcomes = Vec::<crate::agent::types::BatchProposalItemOutcome>::new();

    for prop in list {
        if !crate::agent::types::is_proposal_applied_status(&prop.status) || !prop.can_revert {
            continue;
        }
        match revert_proposal(sync_conn, local_conn, &prop.id) {
            Ok(res) => {
                reverted_proposals += 1;
                for d in &res.changed_domains {
                    changed_domains.insert(d.clone());
                }
                outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                    proposal_id: prop.id.clone(),
                    title: prop.title.clone(),
                    outcome: "reverted".into(),
                    applied_count: res.applied_count,
                    message: res.message,
                    proposal: Some(res.proposal),
                });
            }
            Err(err) => {
                failed_proposals += 1;
                outcomes.push(crate::agent::types::BatchProposalItemOutcome {
                    proposal_id: prop.id.clone(),
                    title: prop.title.clone(),
                    outcome: "failed".into(),
                    applied_count: 0,
                    message: err.to_string(),
                    proposal: Some(prop),
                });
            }
        }
    }

    let mut domains: Vec<String> = changed_domains.into_iter().collect();
    domains.sort();
    let message = format!(
        "整批撤销完成：已撤销 {reverted_proposals} 份提案，失败 {failed_proposals} 份。"
    );
    Ok(crate::agent::types::BatchProposalRevertResult {
        batch_group_id: batch_group_id.to_string(),
        reverted_proposals,
        failed_proposals,
        changed_domains: domains,
        outcomes,
        message,
    })
}

fn commit_proposal_inner(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
    selected_indices: Option<&[usize]>,
    target_status: &str,
) -> Result<(ProposalApplyResult, Option<Value>, Vec<DeferredLocalEffect>)> {
    let (mut proposal, _) = load_proposal(local_conn, proposal_id)?
        .ok_or_else(|| StudioError::InvalidInput("提案不存在或已被清理".into()))?;
    if proposal.status != "pending" {
        return Err(StudioError::InvalidInput(format!(
            "该提案当前状态为 `{}`，无法重复执行",
            proposal.status
        )));
    }

    let ops = proposal
        .ops
        .as_array()
        .cloned()
        .filter(|ops| !ops.is_empty())
        .ok_or_else(|| StudioError::InvalidInput("这份提案没有可执行的项目。".into()))?;
    let selected: Option<HashSet<usize>> =
        selected_indices.map(|sl| sl.iter().copied().collect());
    if selected.as_ref().is_some_and(|indices| indices.is_empty() || indices.iter().any(|&i| i >= ops.len())) {
        return Err(StudioError::InvalidInput("请选择有效的提案项目。".into()));
    }

    let mut applied_count = 0usize;
    let mut noop_skipped_count = 0usize;
    let mut applied_indices = Vec::<usize>::new();
    let mut all_reversible = true;
    let mut changed_domains = HashSet::<String>::new();
    let mut rollback_ops = Vec::<Value>::new();
    let mut local_effects = Vec::<DeferredLocalEffect>::new();

    match proposal.kind.as_str() {
        "work_mutations" => {
            let items = query_curator_items(sync_conn, local_conn)?;
            let indexed: std::collections::HashMap<_, _> = items.iter()
                .map(|item| ((item.kind.as_str(), item.id), item)).collect();
            let modifies_anime_tags = ops.iter().enumerate().any(|(idx, op)| {
                selected.as_ref().is_none_or(|indices| indices.contains(&idx))
                    && matches!(op["kind"].as_str(), Some("anime" | "book"))
                    && op["tags"].is_array()
            });
            let previous_tag_ids = if modifies_anime_tags {
                tag_definitions(sync_conn)?.into_iter().map(|(id, _)| id).collect::<HashSet<_>>()
            } else {
                HashSet::new()
            };
            for (idx, op) in ops.iter().enumerate() {
                if let Some(ref sel) = selected {
                    if !sel.contains(&idx) {
                        continue;
                    }
                }
                let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("update");
                match action {
                    "create" => {
                        validate_work_create(op)?;
                        let kind_str = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let resolved_mf = resolve_op_media_format(op, kind_str)?;
                        let input = NewCuratorItemInput {
                            kind: kind_str.into(),
                            title: op.get("title").and_then(|v| v.as_str()).unwrap_or("").into(),
                            original_title: op.get("original_title").and_then(|v| v.as_str()).map(str::to_string),
                            cover_url: op.get("cover_url").and_then(|v| v.as_str()).map(str::to_string),
                            status: op.get("status").and_then(|v| v.as_str()).map(str::to_string),
                            total_episodes: extract_op_progress_total(op),
                            studio_or_circle: op.get("studio_or_circle").and_then(|v| v.as_str()).map(str::to_string),
                            release_date: op.get("release_date").and_then(|v| v.as_str()).map(str::to_string),
                            public_score: op.get("public_score").and_then(|v| v.as_f64()),
                            external_source: op.get("external_source").and_then(|v| v.as_str()).map(str::to_string),
                            external_id: op.get("external_id").and_then(|v| v.as_str()).map(str::to_string),
                            work_code: None,
                            voice_actors: None,
                            synopsis: op.get("synopsis").and_then(|v| v.as_str()).map(str::to_string),
                            media_format: resolved_mf.clone(),
                        };
                        if let Some(status) = input.status.as_deref() {
                            validate_status(sync_conn, &input.kind, status, None)?;
                        }
                        let new_id = create_new_curator_item(sync_conn, &input)?;
                        let create_primary = op.get("progress_primary").and_then(Value::as_i64);
                        let create_tags = op.get("tags").and_then(Value::as_array).map(|a| tag_names(a));
                        if create_primary.is_some() || create_tags.is_some() {
                            let post_patch = CellPatch {
                                kind: input.kind.clone(),
                                id: new_id,
                                progress_primary: create_primary,
                                tags: create_tags.clone(),
                                ..Default::default()
                            };
                            update_curator_cell(sync_conn, &post_patch)?;
                            if create_tags.is_some() {
                                changed_domains.insert("tags".into());
                            }
                        }
                        let src_tag = op.get("field_source").and_then(Value::as_str).unwrap_or("user");
                        let mut queue_create_src = |fname: &str| {
                            local_effects.push(DeferredLocalEffect::RecordFieldSource {
                                kind: input.kind.clone(),
                                work_id: new_id,
                                field: fname.to_string(),
                                source: src_tag.to_string(),
                            });
                        };
                        if input.cover_url.as_deref().is_some_and(|s| !s.trim().is_empty()) {
                            queue_create_src("cover");
                        }
                        if input.release_date.as_deref().is_some_and(|s| !s.trim().is_empty()) {
                            queue_create_src("release_date");
                        }
                        if create_tags.as_ref().is_some_and(|t| !t.is_empty()) {
                            queue_create_src("tags");
                        }
                        if resolved_mf.as_deref().is_some_and(|s| !s.trim().is_empty()) {
                            queue_create_src("mediaFormat");
                        }
                        if let Some(series_name) = op
                            .get("series_name")
                            .and_then(|v| v.as_str())
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                        {
                            let srole = op
                                .get("series_role")
                                .or_else(|| op.get("role"))
                                .and_then(|v| v.as_str())
                                .map(|s| s.trim().to_string())
                                .filter(|s| !s.is_empty());
                            let _ = batch_assign_series_with_roles(
                                sync_conn,
                                &[(input.kind.clone(), new_id, srole.clone())],
                                series_name,
                                srole.as_deref(),
                            )?;
                            changed_domains.insert("series".into());
                        }
                        rollback_ops.push(json!({
                            "type": "soft_delete_created",
                            "kind": input.kind,
                            "id": new_id,
                            "inserted": true,
                        }));
                        changed_domains.insert("items".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "soft_delete" => {
                        let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let wid = op.get("work_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        check_revision(indexed.get(&(kind, wid)).copied(), op["_expectedRevision"].as_str())?;
                        soft_delete_curator_item(sync_conn, kind, wid)?;
                        rollback_ops.push(json!({
                            "type": "restore_soft_deleted",
                            "kind": kind,
                            "id": wid,
                            "applied": raw_work_fields(sync_conn, kind, wid)?,
                        }));
                        changed_domains.insert("items".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "update" => {
                        let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let wid = op.get("work_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let before = indexed.get(&(kind, wid)).copied();
                        check_work_update_optimistic_lock(op, before, sync_conn)?;
                        let before = before.ok_or_else(|| StudioError::InvalidInput("作品已不存在。".into()))?;
                        if is_work_update_already_satisfied(op, before, sync_conn) {
                            noop_skipped_count += 1;
                            continue;
                        }
                        validate_work_update(op, before, sync_conn)?;
                        let progress = work_progress_preview(sync_conn, op, before)?;
                        if (op.get("_progressEffects").is_some() || !progress.effects.is_empty())
                            && op["_progressEffects"] != json!(progress.effects)
                        {
                            return Err(StudioError::InvalidInput("进度关联的状态或日期预览已过期，请重新生成提案后确认。".into()));
                        }
                        if op["review"].is_string() && op["_previewVersion"] != 2 {
                            return Err(StudioError::InvalidInput("旧提案未预览完整长评。请重新生成后确认。".into()));
                        }
                        let prior_tags = if matches!(kind, "anime" | "book") && op["tags"].is_array() {
                            Some(work_tag_links(sync_conn, wid)?)
                        } else {
                            None
                        };
                        let old = raw_work_fields(sync_conn, kind, wid)?;
                        let fill_original_title = op
                            .get("original_title")
                            .and_then(Value::as_str)
                            .map(str::trim)
                            .filter(|s| !s.is_empty() && before.original_title.trim().is_empty())
                            .map(str::to_string);
                        let resolved_commit_mf = resolve_op_media_format(op, kind)?;
                        let patch = CellPatch {
                            kind: kind.to_string(),
                            id: wid,
                            status: op.get("status").and_then(Value::as_str).map(str::trim).map(str::to_string),
                            progress_primary: op.get("progress_primary").and_then(|v| v.as_i64()),
                            progress_total: extract_op_progress_total(op),
                            raw_rating: op
                                .get("personal_score")
                                .and_then(parse_personal_score_input)
                                .map(score_to_raw),
                            memo: op.get("memo").and_then(|v| v.as_str()).map(str::to_string),
                            review: op.get("review").and_then(|v| v.as_str()).map(str::to_string),
                            original_title: fill_original_title,
                            release_date: op.get("release_date").and_then(Value::as_str).map(str::to_string),
                            cover_url: op.get("cover_url").and_then(Value::as_str).map(str::to_string),
                            studio_or_circle: op.get("studio_or_circle").and_then(Value::as_str).map(str::to_string),
                            media_format: resolved_commit_mf.clone(),
                            synopsis: op.get("synopsis").and_then(Value::as_str).map(str::to_string),
                            tags: op["tags"].as_array().map(|tags| tag_names(tags)),
                            ..Default::default()
                        };
                        if patch.tags.is_some() {
                            changed_domains.insert("tags".into());
                        }
                        let mut existing_fsrc: std::collections::HashMap<String, Option<String>> =
                            crate::agent::storage::load_work_field_sources(local_conn, kind, wid)
                                .unwrap_or_default()
                                .into_iter()
                                .map(|(f, (s, _))| (f, Some(s)))
                                .collect();
                        for eff in &local_effects {
                            match eff {
                                DeferredLocalEffect::RecordFieldSource {
                                    kind: ek,
                                    work_id: ewid,
                                    field,
                                    source,
                                } if ek == kind && *ewid == wid => {
                                    existing_fsrc.insert(field.clone(), Some(source.clone()));
                                }
                                DeferredLocalEffect::DeleteFieldSource {
                                    kind: ek,
                                    work_id: ewid,
                                    field,
                                } if ek == kind && *ewid == wid => {
                                    existing_fsrc.insert(field.clone(), None);
                                }
                                _ => {}
                            }
                        }
                        update_curator_cell(sync_conn, &patch)?;
                        let field_src = if op.get("_rehydrateSource").and_then(Value::as_str).is_some() {
                            "external"
                        } else {
                            op.get("field_source").and_then(Value::as_str).unwrap_or("user")
                        };
                        let mut prev_field_sources = serde_json::Map::<String, Value>::new();
                        let mut record_src = |fname: &str| {
                            let prev_val = existing_fsrc
                                .get(fname)
                                .and_then(|opt| opt.as_ref())
                                .map(|s| json!(s))
                                .unwrap_or(Value::Null);
                            prev_field_sources.insert(fname.to_string(), prev_val);
                            local_effects.push(DeferredLocalEffect::RecordFieldSource {
                                kind: kind.to_string(),
                                work_id: wid,
                                field: fname.to_string(),
                                source: field_src.to_string(),
                            });
                        };
                        if patch.cover_url.is_some() {
                            record_src("cover");
                        }
                        if patch.release_date.is_some() {
                            record_src("release_date");
                        }
                        if patch.tags.is_some() {
                            record_src("tags");
                        }
                        if patch.synopsis.is_some() {
                            record_src("summary");
                        }
                        if patch.studio_or_circle.is_some() {
                            record_src("studio");
                        }
                        if patch.media_format.is_some() {
                            record_src("mediaFormat");
                            record_src("workType");
                        }
                        if patch.progress_total.is_some() {
                            record_src("total_episodes");
                        }
                        if let Some(rt) = patch.raw_rating {
                            if rt > 0 {
                                record_src("personal_score");
                            } else {
                                let prev_val = existing_fsrc
                                    .get("personal_score")
                                    .and_then(|opt| opt.as_ref())
                                    .map(|s| json!(s))
                                    .unwrap_or(Value::Null);
                                prev_field_sources.insert("personal_score".to_string(), prev_val);
                                local_effects.push(DeferredLocalEffect::DeleteFieldSource {
                                    kind: kind.to_string(),
                                    work_id: wid,
                                    field: "personal_score".to_string(),
                                });
                            }
                        }
                        let mut rollback = json!({
                            "type": "restore_work_fields", "kind": kind, "id": wid,
                            "old": old, "applied": raw_work_fields(sync_conn, kind, wid)?,
                            "tagLinks": prior_tags,
                            "previousFieldSources": prev_field_sources,
                        });
                        if let Some(external_id) = op["external_id"].as_str() {
                            rollback["externalLink"] = replace_anime_bangumi_binding(sync_conn, wid, external_id)?;
                        }
                        rollback_ops.push(rollback);
                        changed_domains.insert("items".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    _ => return Err(StudioError::InvalidInput("提案包含无法执行的作品操作。".into())),
                }
            }
            if modifies_anime_tags {
                if let Some(first) = rollback_ops.first_mut() {
                    let created: Vec<_> = tag_definitions(sync_conn)?.into_iter()
                        .filter(|(id, _)| !previous_tag_ids.contains(id)).collect();
                    first["createdTags"] = json!(created);
                }
            }
            let after = query_curator_items(sync_conn, local_conn)?;
            let rows: std::collections::HashMap<_, _> = after.iter()
                .map(|item| ((item.kind.as_str(), item.id), item)).collect();
            for operation in &mut rollback_ops {
                if matches!(operation["type"].as_str(), Some("restore_work_fields" | "soft_delete_created")) {
                    let (rev, content_rev) = {
                        let key = (operation["kind"].as_str().unwrap_or(""), operation["id"].as_i64().unwrap_or(0));
                        let item = rows.get(&key).ok_or_else(|| StudioError::InvalidInput("提案写入后无法核对作品".into()))?;
                        (item_revision(item)?, item_content_revision(item)?)
                    };
                    operation["expectedRevision"] = json!(rev);
                    operation["expectedContentRevision"] = json!(content_rev);
                }
            }
        }
        "series_mutations" => {
            let before_items = query_curator_items(sync_conn, local_conn)?;
            let indexed_items: std::collections::HashMap<_, _> = before_items
                .iter()
                .map(|it| ((it.kind.as_str(), it.id), it))
                .collect();
            for (idx, op) in ops.iter().enumerate() {
                if let Some(ref sel) = selected {
                    if !sel.contains(&idx) {
                        continue;
                    }
                }
                let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("");
                match action {
                    "assign_series" => {
                        let sname = op.get("series_name").and_then(|v| v.as_str()).unwrap_or("");
                        let srole = op
                            .get("series_role")
                            .or_else(|| op.get("role"))
                            .and_then(|v| v.as_str());
                        let raw_targets = op
                            .get("targets")
                            .and_then(|v| v.as_array())
                            .cloned()
                            .unwrap_or_default();
                        let mut targets: Vec<(String, i64, Option<String>)> = Vec::new();
                        let mut prev_map = Vec::new();
                        for t in &raw_targets {
                            let Some(kind) = t.get("kind").and_then(Value::as_str) else { continue; };
                            let Some(wid) = t.get("work_id").and_then(Value::as_i64) else { continue; };
                            let live = indexed_items.get(&(kind, wid)).copied();
                            if let Some(expected_rev) = t.get("_expectedRevision").and_then(Value::as_str) {
                                check_revision(live, Some(expected_rev))?;
                            } else if live.is_none() {
                                return Err(StudioError::InvalidInput("系列目标作品已不存在，请重新生成提案。".into()));
                            }
                            let role = t
                                .get("role")
                                .or_else(|| t.get("series_role"))
                                .and_then(|v| v.as_str())
                                .map(str::trim)
                                .filter(|s| !s.is_empty())
                                .map(str::to_string);
                            if let Some(it) = live {
                                prev_map.push(json!({
                                    "kind": kind,
                                    "work_id": wid,
                                    "prev_series": it.series_name,
                                    "prev_role": it.series_role,
                                }));
                            }
                            targets.push((kind.to_string(), wid, role));
                        }
                        let (series_id, _) =
                            batch_assign_series_with_roles(sync_conn, &targets, sname, srole)?;
                        rollback_ops.push(json!({
                            "type": "restore_series_assignments",
                            "series_id": series_id,
                            "series_name": sname,
                            "entries": prev_map,
                        }));
                        changed_domains.insert("items".into());
                        changed_domains.insert("series".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "delete_empty_series" => {
                        let sid = op["series_id"].as_i64().unwrap_or_default();
                        let still_empty = list_series_manage(sync_conn)?.into_iter().any(|series|
                            series.id == sid && series.member_count == 0 && Some(series.name.as_str()) == op["expected_name"].as_str()
                        );
                        if !still_empty {
                            return Err(StudioError::InvalidInput("系列已有变更，请重新核对后生成提案。".into()));
                        }
                        delete_series(sync_conn, sid)?;
                        all_reversible = false;
                        changed_domains.insert("series".into());
                        changed_domains.insert("items".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "rename_series" => {
                        let sid = op.get("series_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let new_name = op.get("new_name").and_then(|v| v.as_str()).unwrap_or("");
                        let old_name: Option<String> = sync_conn
                            .query_row("SELECT name FROM series WHERE id = ?1", [sid], |r| r.get(0))
                            .optional()?;
                        if let Some(exp) = op.get("expected_name").and_then(Value::as_str) {
                            if old_name.as_deref() != Some(exp) {
                                return Err(StudioError::InvalidInput("系列名称已变化或提案已过期，请重新生成后确认。".into()));
                            }
                        }
                        rename_series(sync_conn, sid, new_name)?;
                        if let Some(old) = old_name {
                            rollback_ops.push(json!({ "type": "rename_series", "series_id": sid, "old_name": old }));
                        } else {
                            all_reversible = false;
                        }
                        changed_domains.insert("items".into());
                        changed_domains.insert("series".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "merge_series" => {
                        let src = op.get("source_series_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let dst = op.get("target_series_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        if let Some(exp_src) = op.get("expected_source_name").and_then(Value::as_str) {
                            let cur: Option<String> = sync_conn.query_row("SELECT name FROM series WHERE id = ?1", [src], |r| r.get(0)).optional()?;
                            if cur.as_deref() != Some(exp_src) {
                                return Err(StudioError::InvalidInput("源系列已变化或提案已过期，请重新生成后确认。".into()));
                            }
                        }
                        if let Some(exp_dst) = op.get("expected_target_name").and_then(Value::as_str) {
                            let cur: Option<String> = sync_conn.query_row("SELECT name FROM series WHERE id = ?1", [dst], |r| r.get(0)).optional()?;
                            if cur.as_deref() != Some(exp_dst) {
                                return Err(StudioError::InvalidInput("目标系列已变化或提案已过期，请重新生成后确认。".into()));
                            }
                        }
                        merge_series(sync_conn, src, dst)?;
                        all_reversible = false;
                        changed_domains.insert("items".into());
                        changed_domains.insert("series".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "update_member_role" => {
                        let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let wid = op.get("work_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        if let Some(exp_rev) = op.get("_expectedRevision").and_then(Value::as_str) {
                            check_revision(indexed_items.get(&(kind, wid)).copied(), Some(exp_rev))?;
                        }
                        let sid = op
                            .get("series_id")
                            .and_then(|v| v.as_i64())
                            .or_else(|| {
                                sync_conn
                                    .query_row("SELECT seriesId FROM animes WHERE id = ?1", [wid], |r| r.get::<_, Option<i64>>(0))
                                    .ok()
                                    .flatten()
                            })
                            .unwrap_or(0);
                        let role = op
                            .get("role")
                            .or_else(|| op.get("series_role"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("");
                        update_series_member_role(sync_conn, sid, kind, wid, role)?;
                        all_reversible = false;
                        changed_domains.insert("items".into());
                        changed_domains.insert("series".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "remove_member" => {
                        let sid = op.get("series_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let wid = op.get("work_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        if let Some(exp_rev) = op.get("_expectedRevision").and_then(Value::as_str) {
                            check_revision(indexed_items.get(&(kind, wid)).copied(), Some(exp_rev))?;
                        }
                        remove_member_from_series(sync_conn, sid, kind, wid)?;
                        all_reversible = false;
                        changed_domains.insert("items".into());
                        changed_domains.insert("series".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    _ => {}
                }
            }
        }
        "tag_mutations" => {
            for (idx, op) in ops.iter().enumerate() {
                if let Some(ref sel) = selected {
                    if !sel.contains(&idx) {
                        continue;
                    }
                }
                let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("");
                match action {
                    "rename_tag" => {
                        let tid = op.get("tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let new_name = op.get("new_name").and_then(|v| v.as_str()).unwrap_or("");
                        let old_name: Option<String> = sync_conn
                            .query_row("SELECT name FROM tags WHERE id = ?1", [tid], |r| r.get(0))
                            .ok();
                        rename_tag(sync_conn, tid, new_name)?;
                        if let Some(old) = old_name {
                            rollback_ops.push(json!({ "type": "rename_tag", "tag_id": tid, "old_name": old }));
                        } else {
                            all_reversible = false;
                        }
                        changed_domains.insert("items".into());
                        changed_domains.insert("tags".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "merge_tags" => {
                        let src = op.get("source_tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let dst = op.get("target_tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        merge_tags(sync_conn, src, dst)?;
                        all_reversible = false;
                        changed_domains.insert("items".into());
                        changed_domains.insert("tags".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "purge_unused_tags" => {
                        if let Some(tid) = op.get("tag_id").and_then(|v| v.as_i64()) {
                            if let Some(snap) = purge_unused_tag_by_id(sync_conn, tid)? {
                                rollback_ops.push(json!({
                                    "type": "restore_purged_tag",
                                    "snapshot": snap
                                }));
                            }
                        } else {
                            let snaps = purge_unused_tags_with_snapshot(sync_conn)?;
                            for snap in snaps {
                                rollback_ops.push(json!({
                                    "type": "restore_purged_tag",
                                    "snapshot": snap
                                }));
                            }
                        }
                        changed_domains.insert("tags".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "toggle_tag_blocked" => {
                        let tid = op.get("tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let blocked = op.get("blocked").and_then(|v| v.as_bool()).unwrap_or(true);
                        toggle_tag_blocked(sync_conn, tid, blocked)?;
                        rollback_ops.push(json!({ "type": "toggle_tag_blocked", "tag_id": tid, "blocked": !blocked }));
                        changed_domains.insert("tags".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    _ => {}
                }
            }
        }
        "character_mutations" => {
            for (idx, op) in ops.iter().enumerate() {
                if let Some(ref sel) = selected {
                    if !sel.contains(&idx) {
                        continue;
                    }
                }
                let action = op.get("action").and_then(|v| v.as_str()).unwrap_or("");
                match action {
                    "upsert_character" => {
                        let existing_id = op.get("id").and_then(Value::as_i64);
                        let found_id = match (existing_id, op.get("bgm_id").and_then(Value::as_i64)) {
                            (Some(id), _) => Some(id),
                            (None, Some(bgm_id)) if bgm_id > 0 => character_id_by_bangumi(sync_conn, bgm_id)?,
                            _ => None,
                        };
                        let before = found_id.map(|id| read_character_fields(sync_conn, id)).transpose()?;
                        let is_new = before.is_none();
                        let input = UpsertCharacterInput {
                            id: existing_id,
                            bgm_id: op.get("bgm_id").and_then(|v| v.as_i64()),
                            anilist_id: op.get("anilist_id").and_then(|v| v.as_i64()),
                            name: op.get("name").and_then(|v| v.as_str()).unwrap_or("").into(),
                            name_cn: op.get("name_cn").and_then(|v| v.as_str()).map(str::to_string),
                            image_url: op.get("image_url").and_then(|v| v.as_str()).map(str::to_string),
                            summary: op.get("summary").and_then(|v| v.as_str()).map(str::to_string),
                            gender: op.get("gender").and_then(|v| v.as_str()).map(str::to_string),
                            birth_month: op.get("birth_month").and_then(|v| v.as_i64()),
                            birth_day: op.get("birth_day").and_then(|v| v.as_i64()),
                            blood_type: op.get("blood_type").and_then(|v| v.as_str()).map(str::to_string),
                            rating: op.get("rating").and_then(|v| v.as_i64()),
                            review: op.get("review").and_then(|v| v.as_str()).map(str::to_string),
                        };
                        let cid = save_character_profile(sync_conn, &input, Some(op))?;
                        if let Some(before) = before {
                            let after = read_character_fields(sync_conn, cid)?;
                            rollback_ops.push(character_profile_undo(cid, &before, &after));
                        } else {
                            rollback_ops.push(json!({ "type": "delete_character", "character_id": cid }));
                        }
                        if let Some(aid) = op.get("anime_id").and_then(|v| v.as_i64()) {
                            let prev_link: Option<(Option<String>, Option<String>)> = sync_conn
                                .query_row(
                                    "SELECT roleName, voiceActor FROM anime_characters WHERE animeId = ?1 AND characterId = ?2",
                                    params![aid, cid],
                                    |r| Ok((r.get(0)?, r.get(1)?)),
                                )
                                .optional()?;
                            let (role, va) = character_link_fields(op, prev_link.as_ref());
                            link_character_work_with_propagation(sync_conn, cid, aid, role, va, false)?;
                            if let Some((old_role, old_va)) = prev_link {
                                rollback_ops.push(json!({
                                    "type": "relink_work",
                                    "character_id": cid,
                                    "anime_id": aid,
                                    "role_name": old_role,
                                    "voice_actor": old_va,
                                }));
                            } else if !is_new {
                                rollback_ops.push(json!({ "type": "unlink_work", "character_id": cid, "anime_id": aid }));
                            }
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "link_work" => {
                        let cid = resolve_character_id_by_name_or_id(
                            sync_conn,
                            op.get("character_id").and_then(|v| v.as_i64()),
                            op.get("character_name").and_then(|v| v.as_str()),
                        )?;
                        let aid = op.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let prev_link: Option<(Option<String>, Option<String>)> = sync_conn
                            .query_row(
                                "SELECT roleName, voiceActor FROM anime_characters WHERE animeId = ?1 AND characterId = ?2",
                                params![aid, cid],
                                |r| Ok((r.get(0)?, r.get(1)?)),
                            )
                            .optional()?;
                        // 提案提交禁用静默「暂定」扩散，保证预览里列了几条就只写几条
                        let (role, va) = character_link_fields(op, prev_link.as_ref());
                        link_character_work_with_propagation(sync_conn, cid, aid, role, va, false)?;
                        if let Some((old_role, old_va)) = prev_link {
                            rollback_ops.push(json!({
                                "type": "relink_work",
                                "character_id": cid,
                                "anime_id": aid,
                                "role_name": old_role,
                                "voice_actor": old_va,
                            }));
                        } else {
                            rollback_ops.push(json!({ "type": "unlink_work", "character_id": cid, "anime_id": aid }));
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "unlink_work" => {
                        let cid = resolve_character_id_by_name_or_id(
                            sync_conn,
                            op.get("character_id").and_then(|v| v.as_i64()),
                            op.get("character_name").and_then(|v| v.as_str()),
                        )?;
                        let aid = op.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let prev_link: Option<(Option<String>, Option<String>)> = sync_conn
                            .query_row(
                                "SELECT roleName, voiceActor FROM anime_characters WHERE animeId = ?1 AND characterId = ?2",
                                params![aid, cid],
                                |r| Ok((r.get(0)?, r.get(1)?)),
                            )
                            .optional()?;
                        unlink_character_work(sync_conn, cid, aid)?;
                        if let Some((old_role, old_va)) = prev_link {
                            rollback_ops.push(json!({
                                "type": "relink_work",
                                "character_id": cid,
                                "anime_id": aid,
                                "role_name": old_role,
                                "voice_actor": old_va,
                            }));
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "upsert_relation" => {
                        let src_id = resolve_character_id_by_name_or_id(
                            sync_conn,
                            op.get("source_character_id").and_then(|v| v.as_i64()),
                            op.get("source_name").and_then(|v| v.as_str()),
                        )?;
                        let dst_id = resolve_character_id_by_name_or_id(
                            sync_conn,
                            op.get("target_character_id").and_then(|v| v.as_i64()),
                            op.get("target_name").and_then(|v| v.as_str()),
                        )?;
                        let explicit_rel_id = op.get("id").and_then(|v| v.as_i64());
                        let prev_rel: Option<Value> = if let Some(erid) = explicit_rel_id {
                            sync_conn
                                .query_row(
                                    "SELECT id, sourceCharacterId, targetCharacterId, relationType,
                                            sourceRole, targetRole, note, strength
                                     FROM character_relations WHERE id = ?1",
                                    [erid],
                                    |r| {
                                        Ok(json!({
                                            "id": r.get::<_, i64>(0)?,
                                            "source_character_id": r.get::<_, i64>(1)?,
                                            "target_character_id": r.get::<_, i64>(2)?,
                                            "relation_type": r.get::<_, String>(3)?,
                                            "source_role": r.get::<_, Option<String>>(4)?,
                                            "target_role": r.get::<_, Option<String>>(5)?,
                                            "note": r.get::<_, Option<String>>(6)?,
                                            "strength": r.get::<_, Option<i64>>(7)?,
                                        }))
                                    },
                                )
                                .optional()?
                        } else {
                            None
                        };
                        if explicit_rel_id.is_some() && prev_rel.is_none() {
                            return Err(StudioError::InvalidInput("待修改的角色关系已不存在。".into()));
                        }
                        let field = |key: &str| op.get(key).or_else(|| prev_rel.as_ref().and_then(|old| old.get(key)));
                        // 提案提交禁用静默「暂定」跨作品扩散
                        let rel_id = upsert_character_relation_with_propagation(
                            sync_conn,
                            &UpsertCharacterRelationInput {
                                id: explicit_rel_id,
                                source_character_id: src_id,
                                target_character_id: dst_id,
                                relation_type: field("relation_type")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("关联")
                                    .into(),
                                source_role: field("source_role").and_then(|v| v.as_str()).map(str::to_string),
                                target_role: field("target_role").and_then(|v| v.as_str()).map(str::to_string),
                                note: field("note").and_then(|v| v.as_str()).map(str::to_string),
                                strength: field("strength").and_then(|v| v.as_i64()),
                            },
                            false,
                        )?;
                        if let Some(prev) = prev_rel {
                            rollback_ops.push(json!({ "type": "restore_relation_exact", "relation": prev }));
                        } else {
                            rollback_ops.push(json!({ "type": "delete_relation", "relation_id": rel_id }));
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "delete_relation" => {
                        let rid = op
                            .get("relation_id")
                            .or_else(|| op.get("id"))
                            .and_then(|v| v.as_i64())
                            .unwrap_or(0);
                        let snapshot: Option<Value> = sync_conn
                            .query_row(
                                "SELECT id, sourceCharacterId, targetCharacterId, relationType,
                                        sourceRole, targetRole, note, strength
                                 FROM character_relations WHERE id = ?1",
                                [rid],
                                |r| {
                                    Ok(json!({
                                        "id": r.get::<_, i64>(0)?,
                                        "source_character_id": r.get::<_, i64>(1)?,
                                        "target_character_id": r.get::<_, i64>(2)?,
                                        "relation_type": r.get::<_, String>(3)?,
                                        "source_role": r.get::<_, Option<String>>(4)?,
                                        "target_role": r.get::<_, Option<String>>(5)?,
                                        "note": r.get::<_, Option<String>>(6)?,
                                        "strength": r.get::<_, Option<i64>>(7)?,
                                    }))
                                },
                            )
                            .optional()?;
                        delete_character_relation(sync_conn, rid)?;
                        if let Some(snap) = snapshot {
                            rollback_ops.push(json!({
                                "type": "restore_relation",
                                "relation": snap,
                            }));
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "delete_character" => {
                        let cid = resolve_character_id_by_name_or_id(
                            sync_conn,
                            op.get("character_id").or_else(|| op.get("id")).and_then(|v| v.as_i64()),
                            op.get("character_name").or_else(|| op.get("name")).and_then(|v| v.as_str()),
                        )?;
                        let char_snap: Option<Value> = sync_conn
                            .query_row(
                                "SELECT id, bgmId, name, nameCn, imageUrl, summary, gender,
                                        birthMonth, birthDay, bloodType, rating, review
                                 FROM characters WHERE id = ?1",
                                [cid],
                                |r| {
                                    Ok(json!({
                                        "id": r.get::<_, i64>(0)?,
                                        "bgm_id": r.get::<_, Option<i64>>(1)?,
                                        "name": r.get::<_, String>(2)?,
                                        "name_cn": r.get::<_, Option<String>>(3)?,
                                        "image_url": r.get::<_, Option<String>>(4)?,
                                        "summary": r.get::<_, Option<String>>(5)?,
                                        "gender": r.get::<_, Option<String>>(6)?,
                                        "birth_month": r.get::<_, Option<i64>>(7)?,
                                        "birth_day": r.get::<_, Option<i64>>(8)?,
                                        "blood_type": r.get::<_, Option<String>>(9)?,
                                        "rating": r.get::<_, Option<i64>>(10)?,
                                        "review": r.get::<_, Option<String>>(11)?,
                                    }))
                                },
                            )
                            .optional()?;
                        let mut work_links = Vec::<Value>::new();
                        {
                            let mut stmt = sync_conn.prepare(
                                "SELECT animeId, roleName, voiceActor FROM anime_characters WHERE characterId = ?1",
                            )?;
                            let rows = stmt.query_map([cid], |r| {
                                Ok(json!({
                                    "anime_id": r.get::<_, i64>(0)?,
                                    "role_name": r.get::<_, Option<String>>(1)?,
                                    "voice_actor": r.get::<_, Option<String>>(2)?,
                                }))
                            })?;
                            for row in rows {
                                work_links.push(row?);
                            }
                        }
                        let mut rel_snaps = Vec::<Value>::new();
                        {
                            let mut stmt = sync_conn.prepare(
                                "SELECT sourceCharacterId, targetCharacterId, relationType, sourceRole, targetRole, note, strength
                                 FROM character_relations WHERE sourceCharacterId = ?1 OR targetCharacterId = ?1",
                            )?;
                            let rows = stmt.query_map([cid], |r| {
                                Ok(json!({
                                    "source_character_id": r.get::<_, i64>(0)?,
                                    "target_character_id": r.get::<_, i64>(1)?,
                                    "relation_type": r.get::<_, String>(2)?,
                                    "source_role": r.get::<_, Option<String>>(3)?,
                                    "target_role": r.get::<_, Option<String>>(4)?,
                                    "note": r.get::<_, Option<String>>(5)?,
                                    "strength": r.get::<_, Option<i64>>(6)?,
                                }))
                            })?;
                            for row in rows {
                                rel_snaps.push(row?);
                            }
                        }
                        let mut tag_names_vec = Vec::<String>::new();
                        {
                            let mut stmt = sync_conn.prepare(
                                "SELECT ct.name FROM character_tags ct
                                 JOIN character_tag_links ctl ON ctl.tagId = ct.id
                                 WHERE ctl.characterId = ?1",
                            )?;
                            let rows = stmt.query_map([cid], |r| r.get::<_, String>(0))?;
                            for t in rows {
                                tag_names_vec.push(t?);
                            }
                        }
                        delete_character(sync_conn, cid)?;
                        if let Some(cs) = char_snap {
                            rollback_ops.push(json!({
                                "type": "restore_character",
                                "character": cs,
                                "works": work_links,
                                "relations": rel_snaps,
                                "tags": tag_names_vec,
                            }));
                        } else {
                            all_reversible = false;
                        }
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    "set_tags" => {
                        let cid = op.get("character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let mut prev_tags = Vec::<String>::new();
                        {
                            let mut stmt = sync_conn.prepare(
                                "SELECT ct.name FROM character_tags ct
                                 JOIN character_tag_links ctl ON ctl.tagId = ct.id
                                 WHERE ctl.characterId = ?1",
                            )?;
                            let rows = stmt.query_map([cid], |r| r.get::<_, String>(0))?;
                            for t in rows {
                                prev_tags.push(t?);
                            }
                        }
                        let new_tags: Vec<String> = op
                            .get("tags")
                            .and_then(|v| v.as_array())
                            .map(|arr| {
                                arr.iter()
                                    .filter_map(|x| x.as_str().map(str::trim))
                                    .filter(|s| !s.is_empty())
                                    .map(str::to_string)
                                    .collect()
                            })
                            .unwrap_or_default();
                        set_character_tags(sync_conn, cid, &new_tags)?;
                        rollback_ops.push(json!({
                            "type": "restore_character_tags",
                            "character_id": cid,
                            "tags": prev_tags,
                        }));
                        changed_domains.insert("characters".into());
                        applied_indices.push(idx);
                        applied_count += 1;
                    }
                    _ => {}
                }
            }
        }
        "merge_duplicates" => {
            let live_items = query_curator_items(sync_conn, local_conn)?;
            let indexed_live: std::collections::HashMap<_, _> = live_items
                .iter()
                .map(|it| ((it.kind.as_str(), it.id), it))
                .collect();
            for (idx, op) in ops.iter().enumerate() {
                if let Some(ref sel) = selected {
                    if !sel.contains(&idx) {
                        continue;
                    }
                }
                let kind = op.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                let keep_id = op.get("keep_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let drop_ids: Vec<i64> = op
                    .get("drop_ids")
                    .and_then(|v| v.as_array())
                    .map(|arr| arr.iter().filter_map(|v| v.as_i64()).collect())
                    .unwrap_or_default();
                // 验证 3 & 第 1 项：确认合并前复核主条目与全部待合并条目的版本指纹，过期或换库直接拦截
                let expected_keep_rev = op.get("_expectedKeepRevision").and_then(Value::as_str);
                check_revision(indexed_live.get(&(kind, keep_id)).copied(), expected_keep_rev)?;
                let drop_revs = op.get("_expectedDropRevisions").and_then(Value::as_object);
                for &did in &drop_ids {
                    if did == keep_id {
                        continue;
                    }
                    let exp_d = drop_revs
                        .and_then(|m| m.get(&did.to_string()))
                        .and_then(Value::as_str);
                    check_revision(indexed_live.get(&(kind, did)).copied(), exp_d)?;
                }

                // 抓取合并前完整快照（含标签与外链），支持一键撤销还原两条记录
                let keep_old_fields = raw_work_fields(sync_conn, kind, keep_id)?;
                let keep_old_tags = if matches!(kind, "anime" | "book") {
                    Some(work_tag_links(sync_conn, keep_id)?)
                } else {
                    None
                };
                let keep_old_ext = read_work_external_tuple(sync_conn, kind, keep_id)?;
                let mut drop_snaps = Vec::<Value>::new();
                for &did in &drop_ids {
                    if did == keep_id {
                        continue;
                    }
                    let d_fields = raw_work_fields(sync_conn, kind, did)?;
                    let d_tags = if matches!(kind, "anime" | "book") {
                        Some(work_tag_links(sync_conn, did)?)
                    } else {
                        None
                    };
                    let d_ext = read_work_external_tuple(sync_conn, kind, did)?;
                    drop_snaps.push(json!({
                        "id": did,
                        "oldFields": d_fields,
                        "tagLinks": d_tags,
                        "ext": d_ext,
                    }));
                }

                merge_duplicate_group(sync_conn, kind, keep_id, &drop_ids)?;

                let keep_applied_fields = raw_work_fields(sync_conn, kind, keep_id)?;
                let keep_applied_ext = read_work_external_tuple(sync_conn, kind, keep_id)?;
                rollback_ops.push(json!({
                    "type": "restore_merged_group",
                    "kind": kind,
                    "keepId": keep_id,
                    "keepOldFields": keep_old_fields,
                    "keepAppliedFields": keep_applied_fields,
                    "keepOldTagLinks": keep_old_tags,
                    "keepOldExt": keep_old_ext,
                    "keepAppliedExt": keep_applied_ext,
                    "drops": drop_snaps,
                }));
                changed_domains.insert("items".into());
                if matches!(kind, "anime" | "book") {
                    changed_domains.insert("tags".into());
                }
                applied_indices.push(idx);
                applied_count += 1;
            }
        }
        _ => {}
    }

    if applied_count == 0 && noop_skipped_count > 0 {
        proposal.status = "noop".into();
        proposal.applied_at = None;
        proposal.applied_count = Some(0);
        proposal.applied_indices = Some(Vec::new());
        proposal.can_revert = false;
        proposal.last_attempt_failed = false;
        proposal.last_error = Some("所有目标字段当前值已达成，无需重复执行。".into());
        return Ok((
            ProposalApplyResult {
                message: "所有目标字段当前值已达成，已标记为无需重复执行（no-op）。".into(),
                proposal,
                applied_count: 0,
                applied_indices: Vec::new(),
                changed_domains: Vec::new(),
            },
            None,
            Vec::new(),
        ));
    }

    proposal.status = target_status.into();
    proposal.applied_at = Some(Utc::now().to_rfc3339());
    proposal.applied_count = Some(applied_count);
    proposal.applied_indices = Some(applied_indices.clone());
    proposal.last_attempt_failed = false;
    proposal.last_error = None;
    let snap = if rollback_ops.is_empty() || !all_reversible {
        None
    } else {
        Some(Value::Array(rollback_ops))
    };
    proposal.can_revert = snap.is_some();

    let mut domains: Vec<String> = changed_domains.into_iter().collect();
    domains.sort();

    Ok((ProposalApplyResult {
        message: if applied_count < ops.len() {
            format!("已执行 {} 项变更，未勾选或已达成 {} 项", applied_count, ops.len() - applied_count)
        } else {
            format!("已执行 {} 项变更", applied_count)
        },
        proposal,
        applied_count,
        applied_indices,
        changed_domains: domains,
    }, snap, local_effects))
}

pub fn reject_proposal(local_conn: &Connection, proposal_id: &str) -> Result<AgentProposal> {
    let (mut proposal, _) = load_proposal(local_conn, proposal_id)?
        .ok_or_else(|| StudioError::InvalidInput("提案不存在".into()))?;
    let changed = local_conn.execute(
        "UPDATE agent_proposals SET status = 'rejected' WHERE id = ?1 AND status = 'pending'",
        [proposal_id],
    )?;
    if changed != 1 {
        return Err(StudioError::InvalidInput("该提案已被处理，请重新打开对话查看。".into()));
    }
    proposal.status = "rejected".into();
    proposal.can_revert = false;
    Ok(proposal)
}

pub fn revert_proposal(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
) -> Result<ProposalApplyResult> {
    atomic_write(sync_conn, local_conn, |sync| revert_proposal_inner(sync, local_conn, proposal_id))
}

fn revert_proposal_inner(
    sync_conn: &mut Connection,
    local_conn: &Connection,
    proposal_id: &str,
) -> Result<(ProposalApplyResult, Option<Value>, Vec<DeferredLocalEffect>)> {
    let (mut proposal, snap_opt) = load_proposal(local_conn, proposal_id)?
        .ok_or_else(|| StudioError::InvalidInput("提案不存在".into()))?;
    if !crate::agent::types::is_proposal_applied_status(&proposal.status) {
        return Err(StudioError::InvalidInput("只有已执行的提案才支持撤销".into()));
    }
    let Some(Value::Array(rollback_ops)) = snap_opt else {
        return Err(StudioError::InvalidInput("该提案未记录可逆快照，无法自动撤销".into()));
    };

    let mut reverted = 0usize;
    let mut changed_domains = HashSet::<String>::new();
    let mut local_effects = Vec::<DeferredLocalEffect>::new();

    let current_items = if proposal.kind == "work_mutations" { query_curator_items(sync_conn, local_conn)? } else { Vec::new() };
    let indexed: std::collections::HashMap<_, _> = current_items.iter()
        .map(|item| ((item.kind.as_str(), item.id), item)).collect();
    for rop in rollback_ops.iter().rev() {
        let typ = rop.get("type").and_then(|v| v.as_str()).unwrap_or("");
        match typ {
            "restore_work_fields" => {
                let kind = rop["kind"].as_str().unwrap_or("");
                let id = rop["id"].as_i64().unwrap_or(0);
                let item = indexed.get(&(kind, id)).copied();
                let current = raw_work_fields(sync_conn, kind, id)?;
                let rev_mismatch = if let Some(expected_content) =
                    rop.get("expectedContentRevision").and_then(Value::as_str)
                {
                    check_content_revision(item, Some(expected_content)).is_err()
                } else if let Some(expected) = rop.get("expectedRevision").and_then(Value::as_str) {
                    check_revision(item, Some(expected)).is_err()
                } else {
                    false
                };
                if current.as_str() != rop["applied"].as_str().unwrap_or("")
                    || item.is_none()
                    || rev_mismatch
                {
                    return Err(StudioError::InvalidInput("作品在提案应用后已有新修改，无法安全撤销。".into()));
                }
                if rop["tagLinks"].is_array() || rop["tags"].is_array() {
                    restore_tag_links(sync_conn, id, &rop["tagLinks"], &rop["tags"])?;
                    changed_domains.insert("tags".into());
                }
                if let Some(link) = rop.get("externalLink") {
                    let current: (Option<String>, Option<String>) = sync_conn.query_row(
                        "SELECT externalSource, externalId FROM animes WHERE id = ?1",
                        [id], |row| Ok((row.get(0)?, row.get(1)?)),
                    )?;
                    if json!(current) != link["applied"] {
                        return Err(StudioError::InvalidInput("外部绑定已有变化，无法安全撤销。".into()));
                    }
                    let previous: (Option<String>, Option<String>) = serde_json::from_value(link["previous"].clone())?;
                    sync_conn.execute(
                        "UPDATE animes SET externalSource = ?1, externalId = ?2, externalUrl = CASE WHEN ?4 THEN ?5 ELSE externalUrl END WHERE id = ?3",
                        params![previous.0, previous.1, id, link.get("previousUrl").is_some(), link["previousUrl"].as_str()],
                    )?;
                    if let Some(released) = link.get("releasedDeletedLinks").and_then(Value::as_array) {
                        for rel in released {
                            if let Some(did) = rel.get("id").and_then(Value::as_i64) {
                                let dsrc = rel.get("externalSource").and_then(Value::as_str);
                                let deid = rel.get("externalId").and_then(Value::as_str);
                                let durl = rel.get("externalUrl").and_then(Value::as_str);
                                let _ = sync_conn.execute(
                                    "UPDATE animes SET externalSource = ?1, externalId = ?2, externalUrl = ?3
                                     WHERE id = ?4 AND deletedAt IS NOT NULL",
                                    params![dsrc, deid, durl, did],
                                );
                            }
                        }
                    }
                }
                let (table, columns) = work_columns(kind);
                let updates = columns.split(',').enumerate()
                    .map(|(i, col)| format!("{col} = json_extract(?1, '$[{i}]')"))
                    .collect::<Vec<_>>().join(", ");
                let now = Utc::now();
                let updated_at = if matches!(kind, "anime" | "book") {
                    json!(now.timestamp_millis())
                } else {
                    json!(now.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
                };
                sync_conn.execute(
                    &format!("UPDATE {table} SET {updates}, updatedAt = json_extract(?3, '$') WHERE id = ?2"),
                    params![rop["old"].as_str(), id, updated_at.to_string()],
                )?;
                if let Some(prev_fsrc) = rop.get("previousFieldSources").and_then(Value::as_object) {
                    for (fname, prev_val) in prev_fsrc {
                        match prev_val.as_str() {
                            Some(src) if !src.trim().is_empty() => {
                                local_effects.push(DeferredLocalEffect::RecordFieldSource {
                                    kind: kind.to_string(),
                                    work_id: id,
                                    field: fname.clone(),
                                    source: src.to_string(),
                                });
                            }
                            _ => {
                                local_effects.push(DeferredLocalEffect::DeleteFieldSource {
                                    kind: kind.to_string(),
                                    work_id: id,
                                    field: fname.clone(),
                                });
                            }
                        }
                    }
                }
                changed_domains.insert("items".into());
                reverted += 1;
            }
            "restore_cell" => {
                return Err(StudioError::InvalidInput("旧版提案缺少用于安全撤销的记录，请在作品详情中核对。".into()));
            }
            "soft_delete_created" => {
                if rop["inserted"] != true {
                    return Err(StudioError::InvalidInput("旧提案无法确认是否新增了此作品。请在藏品详情中核对。".into()));
                }
                let kind = rop.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                let id = rop.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
                if let Some(expected_content) =
                    rop.get("expectedContentRevision").and_then(Value::as_str)
                {
                    check_content_revision(indexed.get(&(kind, id)).copied(), Some(expected_content))?;
                } else {
                    check_revision(indexed.get(&(kind, id)).copied(), rop["expectedRevision"].as_str())?;
                }
                soft_delete_curator_item(sync_conn, kind, id)?;
                changed_domains.insert("items".into());
                reverted += 1;
            }
            "restore_soft_deleted" => {
                let kind = rop.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                let id = rop.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
                if Some(raw_work_fields(sync_conn, kind, id)?.as_str()) != rop["applied"].as_str() {
                    return Err(StudioError::InvalidInput("回收站里的作品已变化，无法安全撤销。".into()));
                }
                let table = match kind {
                    "game" => "games",
                    "audio" => "audio_works",
                    _ => "animes",
                };
                let changed = sync_conn.execute(
                    &format!("UPDATE {table} SET deletedAt = NULL WHERE id = ?1 AND deletedAt IS NOT NULL"),
                    params![id],
                )?;
                if changed != 1 {
                    return Err(StudioError::InvalidInput("回收站里的作品已变化，无法安全撤销。".into()));
                }
                changed_domains.insert("items".into());
                reverted += 1;
            }
            "restore_series_assignments" => {
                if let Some(entries) = rop.get("entries").and_then(|v| v.as_array()) {
                    for e in entries {
                        let kind = e.get("kind").and_then(|v| v.as_str()).unwrap_or("anime");
                        let wid = e.get("work_id").and_then(|v| v.as_i64()).unwrap_or(0);
                        let prev = e.get("prev_series").and_then(|v| v.as_str());
                        let prev_role = e.get("prev_role").and_then(|v| v.as_str());
                        if let Some(ps) = prev {
                            batch_assign_series(
                                sync_conn,
                                &[(kind.to_string(), wid)],
                                ps,
                                prev_role,
                            )?;
                        } else if kind == "anime" || kind == "book" {
                            sync_conn.execute(
                                "UPDATE animes SET seriesId = NULL, seriesRole = NULL WHERE id = ?1",
                                params![wid],
                            )?;
                        }
                    }
                    changed_domains.insert("items".into());
                    changed_domains.insert("series".into());
                    reverted += 1;
                }
            }
            "rename_series" => {
                let sid = rop.get("series_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let old = rop.get("old_name").and_then(|v| v.as_str()).unwrap_or("");
                rename_series(sync_conn, sid, old)?;
                changed_domains.insert("items".into());
                changed_domains.insert("series".into());
                reverted += 1;
            }
            "rename_tag" => {
                let tid = rop.get("tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let old = rop.get("old_name").and_then(|v| v.as_str()).unwrap_or("");
                rename_tag(sync_conn, tid, old)?;
                changed_domains.insert("items".into());
                changed_domains.insert("tags".into());
                reverted += 1;
            }
            "toggle_tag_blocked" => {
                let tid = rop.get("tag_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let blocked = rop.get("blocked").and_then(|v| v.as_bool()).unwrap_or(false);
                toggle_tag_blocked(sync_conn, tid, blocked)?;
                changed_domains.insert("tags".into());
                reverted += 1;
            }
            "restore_purged_tag" => {
                let snap: PurgedTagSnapshot = serde_json::from_value(
                    rop.get("snapshot").cloned().unwrap_or(Value::Null),
                )
                .map_err(|e| StudioError::InvalidInput(format!("无法读取被清理标签快照：{e}")))?;
                restore_purged_tag(sync_conn, &snap)?;
                changed_domains.insert("tags".into());
                reverted += 1;
            }
            "delete_character" => {
                let cid = rop.get("character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                delete_character(sync_conn, cid)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "unlink_work" => {
                let cid = rop.get("character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let aid = rop.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                unlink_character_work(sync_conn, cid, aid)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "relink_work" => {
                let cid = rop.get("character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let aid = rop.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let role = rop.get("role_name").and_then(|v| v.as_str());
                let va = rop.get("voice_actor").and_then(|v| v.as_str());
                link_character_work_with_propagation(sync_conn, cid, aid, role, va, false)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "delete_relation" => {
                let rid = rop.get("relation_id").and_then(|v| v.as_i64()).unwrap_or(0);
                delete_character_relation(sync_conn, rid)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_relation" => {
                if let Some(rel) = rop.get("relation") {
                    let src_id = rel.get("source_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                    let dst_id = rel.get("target_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                    if src_id > 0 && dst_id > 0 {
                        let _ = upsert_character_relation_with_propagation(
                            sync_conn,
                            &UpsertCharacterRelationInput {
                                id: None,
                                source_character_id: src_id,
                                target_character_id: dst_id,
                                relation_type: rel
                                    .get("relation_type")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("关联")
                                    .into(),
                                source_role: rel.get("source_role").and_then(|v| v.as_str()).map(str::to_string),
                                target_role: rel.get("target_role").and_then(|v| v.as_str()).map(str::to_string),
                                note: rel.get("note").and_then(|v| v.as_str()).map(str::to_string),
                                strength: rel.get("strength").and_then(|v| v.as_i64()),
                            },
                            false,
                        )?;
                    }
                }
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_character_row" => {
                let cs = &rop["character"];
                let id = cs["id"].as_i64().ok_or_else(|| StudioError::InvalidInput("缺少待恢复的角色编号".into()))?;
                write_character_fields(sync_conn, id, cs)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_character_fields" => {
                let id = rop["character_id"].as_i64().ok_or_else(|| StudioError::InvalidInput("缺少待恢复的角色编号".into()))?;
                let current = read_character_fields(sync_conn, id)?;
                let applied = rop["applied"].as_object().ok_or_else(|| StudioError::InvalidInput("角色撤销记录不完整".into()))?;
                if applied.iter().any(|(field, value)| current.get(field) != Some(value)) {
                    return Err(StudioError::InvalidInput(format!("角色 #{id} 的提案字段已被再次修改，无法安全撤销。")));
                }
                write_character_fields(sync_conn, id, &rop["previous"])?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_character_tags" => {
                let cid = rop.get("character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                let tvec: Vec<String> = rop
                    .get("tags")
                    .and_then(|v| v.as_array())
                    .map(|arr| arr.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
                    .unwrap_or_default();
                set_character_tags(sync_conn, cid, &tvec)?;
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_relation_exact" => {
                if let Some(rel) = rop.get("relation") {
                    let rid = rel.get("id").and_then(|v| v.as_i64());
                    let src_id = rel.get("source_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                    let dst_id = rel.get("target_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                    if src_id > 0 && dst_id > 0 {
                        let _ = upsert_character_relation_with_propagation(
                            sync_conn,
                            &UpsertCharacterRelationInput {
                                id: rid,
                                source_character_id: src_id,
                                target_character_id: dst_id,
                                relation_type: rel
                                    .get("relation_type")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("关联")
                                    .into(),
                                source_role: rel.get("source_role").and_then(|v| v.as_str()).map(str::to_string),
                                target_role: rel.get("target_role").and_then(|v| v.as_str()).map(str::to_string),
                                note: rel.get("note").and_then(|v| v.as_str()).map(str::to_string),
                                strength: rel.get("strength").and_then(|v| v.as_i64()),
                            },
                            false,
                        )?;
                    }
                }
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            "restore_merged_group" => {
                let kind = rop["kind"].as_str().unwrap_or("anime");
                let keep_id = rop["keepId"].as_i64().unwrap_or(0);
                let cur_keep = raw_work_fields(sync_conn, kind, keep_id)?;
                if cur_keep.as_str() != rop["keepAppliedFields"].as_str().unwrap_or("") {
                    return Err(StudioError::InvalidInput(format!(
                        "主条目 #{keep_id} 在合并后又有新修改，无法安全撤销。"
                    )));
                }
                let cur_keep_ext = read_work_external_tuple(sync_conn, kind, keep_id)?;
                if cur_keep_ext != rop["keepAppliedExt"] {
                    return Err(StudioError::InvalidInput(format!(
                        "主条目 #{keep_id} 的外部绑定在合并后已变化，无法安全撤销。"
                    )));
                }
                // 1) 先清空主条目外链，避免恢复副条目外链时撞唯一索引
                write_work_external_tuple(sync_conn, kind, keep_id, &json!(null))?;
                // 2) 还原主条目字段与标签
                let (table, columns) = work_columns(kind);
                let updates = columns.split(',').enumerate()
                    .map(|(i, col)| format!("{col} = json_extract(?1, '$[{i}]')"))
                    .collect::<Vec<_>>().join(", ");
                let now = Utc::now();
                let updated_at = if matches!(kind, "anime" | "book") {
                    json!(now.timestamp_millis())
                } else {
                    json!(now.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
                };
                sync_conn.execute(
                    &format!("UPDATE {table} SET {updates}, updatedAt = json_extract(?3, '$') WHERE id = ?2"),
                    params![rop["keepOldFields"].as_str(), keep_id, updated_at.to_string()],
                )?;
                if rop["keepOldTagLinks"].is_array() {
                    restore_tag_links(sync_conn, keep_id, &rop["keepOldTagLinks"], &Value::Null)?;
                    changed_domains.insert("tags".into());
                }
                write_work_external_tuple(sync_conn, kind, keep_id, &rop["keepOldExt"])?;
                // 3) 还原每一条被合并的副条目（字段、标签与外链）
                if let Some(drops) = rop["drops"].as_array() {
                    for d in drops {
                        let did = d["id"].as_i64().unwrap_or(0);
                        sync_conn.execute(
                            &format!("UPDATE {table} SET {updates}, updatedAt = json_extract(?3, '$') WHERE id = ?2"),
                            params![d["oldFields"].as_str(), did, updated_at.to_string()],
                        )?;
                        if d["tagLinks"].is_array() {
                            restore_tag_links(sync_conn, did, &d["tagLinks"], &Value::Null)?;
                        }
                        write_work_external_tuple(sync_conn, kind, did, &d["ext"])?;
                    }
                }
                changed_domains.insert("items".into());
                reverted += 1;
            }
            "restore_character" => {
                if let Some(cs) = rop.get("character") {
                    let orig_id = cs.get("id").and_then(|v| v.as_i64()).unwrap_or(0);
                    // 撤销前先核对关联作品与关系对端角色是否仍存在，任何缺失均触发事务回滚并明确提示
                    if let Some(links) = rop.get("works").and_then(|v| v.as_array()) {
                        for lk in links {
                            let aid = lk.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            if aid > 0 {
                                let exists: bool = sync_conn.query_row(
                                    "SELECT EXISTS(SELECT 1 FROM animes WHERE id = ?1 AND deletedAt IS NULL)",
                                    [aid],
                                    |r| r.get(0),
                                )?;
                                if !exists {
                                    return Err(StudioError::InvalidInput(format!(
                                        "无法完整撤销删除角色 #{orig_id}：关联作品 #{aid} 已不存在或已入回收站，请先恢复该作品。"
                                    )));
                                }
                            }
                        }
                    }
                    if let Some(rels) = rop.get("relations").and_then(|v| v.as_array()) {
                        for rel in rels {
                            let sid = rel.get("source_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            let tid = rel.get("target_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            let peer = if sid == orig_id { tid } else { sid };
                            if peer > 0 && peer != orig_id {
                                let exists: bool = sync_conn.query_row(
                                    "SELECT EXISTS(SELECT 1 FROM characters WHERE id = ?1)",
                                    [peer],
                                    |r| r.get(0),
                                )?;
                                if !exists {
                                    let rtype = rel.get("relation_type").and_then(Value::as_str).unwrap_or("关联");
                                    return Err(StudioError::InvalidInput(format!(
                                        "无法完整撤销删除角色 #{orig_id}：关系「{rtype}」的对端角色 #{peer} 已不存在，已回滚本次撤销。"
                                    )));
                                }
                            }
                        }
                    }

                    let now = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
                    sync_conn.execute(
                        "INSERT OR REPLACE INTO characters
                         (id, bgmId, name, nameCn, imageUrl, summary, gender, birthMonth, birthDay, bloodType, rating, review, updatedAt)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
                        params![
                            orig_id,
                            cs.get("bgm_id").and_then(|v| v.as_i64()),
                            cs.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                            cs.get("name_cn").and_then(|v| v.as_str()),
                            cs.get("image_url").and_then(|v| v.as_str()),
                            cs.get("summary").and_then(|v| v.as_str()),
                            cs.get("gender").and_then(|v| v.as_str()),
                            cs.get("birth_month").and_then(|v| v.as_i64()),
                            cs.get("birth_day").and_then(|v| v.as_i64()),
                            cs.get("blood_type").and_then(|v| v.as_str()),
                            cs.get("rating").and_then(|v| v.as_i64()),
                            cs.get("review").and_then(|v| v.as_str()),
                            now,
                        ],
                    )?;
                    if let Some(links) = rop.get("works").and_then(|v| v.as_array()) {
                        for lk in links {
                            let aid = lk.get("anime_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            if aid > 0 {
                                link_character_work_with_propagation(
                                    sync_conn,
                                    orig_id,
                                    aid,
                                    lk.get("role_name").and_then(|v| v.as_str()),
                                    lk.get("voice_actor").and_then(|v| v.as_str()),
                                    false,
                                )?;
                            }
                        }
                    }
                    if let Some(rels) = rop.get("relations").and_then(|v| v.as_array()) {
                        for rel in rels {
                            let sid = rel.get("source_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            let tid = rel.get("target_character_id").and_then(|v| v.as_i64()).unwrap_or(0);
                            if sid > 0 && tid > 0 {
                                upsert_character_relation_with_propagation(
                                    sync_conn,
                                    &UpsertCharacterRelationInput {
                                        id: None,
                                        source_character_id: sid,
                                        target_character_id: tid,
                                        relation_type: rel
                                            .get("relation_type")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("关联")
                                            .into(),
                                        source_role: rel.get("source_role").and_then(|v| v.as_str()).map(str::to_string),
                                        target_role: rel.get("target_role").and_then(|v| v.as_str()).map(str::to_string),
                                        note: rel.get("note").and_then(|v| v.as_str()).map(str::to_string),
                                        strength: rel.get("strength").and_then(|v| v.as_i64()),
                                    },
                                    false,
                                )?;
                            }
                        }
                    }
                    if let Some(tags) = rop.get("tags").and_then(|v| v.as_array()) {
                        let tvec: Vec<String> = tags
                            .iter()
                            .filter_map(|v| v.as_str().map(str::to_string))
                            .collect();
                        if !tvec.is_empty() {
                            set_character_tags(sync_conn, orig_id, &tvec)?;
                        }
                    }
                }
                changed_domains.insert("characters".into());
                reverted += 1;
            }
            _ => return Err(StudioError::InvalidInput("提案包含无法撤销的操作。".into())),
        }
    }

    if let Some(created) = rollback_ops.first().and_then(|op| op.get("createdTags")) {
        let created: Vec<(i64, String)> = serde_json::from_value(created.clone())?;
        let mut stmt = sync_conn.prepare_cached(
            "DELETE FROM tags WHERE id = ?1 AND json_array(name,color,blocked,sortOrder) = ?2
             AND NOT EXISTS (SELECT 1 FROM anime_tags WHERE tagId = ?1)
             AND NOT EXISTS (SELECT 1 FROM games WHERE instr(lower(games.tags), lower(tags.name)) > 0)
             AND NOT EXISTS (SELECT 1 FROM audio_works WHERE instr(lower(audio_works.tags), lower(tags.name)) > 0)",
        )?;
        for (id, definition) in created {
            stmt.execute(params![id, definition])?;
        }
    }

    proposal.status = "reverted".into();
    proposal.can_revert = false;
    let mut domains: Vec<String> = changed_domains.into_iter().collect();
    domains.sort();

    Ok((ProposalApplyResult {
        message: format!("已撤销 {reverted} 项变更并恢复原值"),
        proposal,
        applied_count: reverted,
        applied_indices: vec![],
        changed_domains: domains,
    }, None, local_effects))
}

fn read_work_external_tuple(conn: &Connection, kind: &str, id: i64) -> Result<Value> {
    match kind {
        "game" => {
            let (bgm, vndb): (Option<i64>, Option<String>) = conn.query_row(
                "SELECT bangumiId, vndbId FROM games WHERE id = ?1",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            Ok(json!({ "bangumiId": bgm, "vndbId": vndb }))
        }
        "audio" => {
            let (code, url): (Option<String>, Option<String>) = conn.query_row(
                "SELECT workCode, sourceUrl FROM audio_works WHERE id = ?1",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            Ok(json!({ "workCode": code, "sourceUrl": url }))
        }
        _ => {
            let (src, eid, url): (Option<String>, Option<String>, Option<String>) = conn.query_row(
                "SELECT externalSource, externalId, externalUrl FROM animes WHERE id = ?1",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )?;
            Ok(json!({ "externalSource": src, "externalId": eid, "externalUrl": url }))
        }
    }
}

fn write_work_external_tuple(conn: &Connection, kind: &str, id: i64, ext: &Value) -> Result<()> {
    match kind {
        "game" => {
            let bgm = ext.get("bangumiId").and_then(Value::as_i64);
            let vndb = ext.get("vndbId").and_then(Value::as_str);
            conn.execute(
                "UPDATE games SET bangumiId = ?1, vndbId = ?2 WHERE id = ?3",
                params![bgm, vndb, id],
            )?;
        }
        "audio" => {
            let code = ext.get("workCode").and_then(Value::as_str);
            let url = ext.get("sourceUrl").and_then(Value::as_str);
            conn.execute(
                "UPDATE audio_works SET workCode = ?1, sourceUrl = ?2 WHERE id = ?3",
                params![code, url, id],
            )?;
        }
        _ => {
            let src = ext.get("externalSource").and_then(Value::as_str);
            let eid = ext.get("externalId").and_then(Value::as_str);
            let url = ext.get("externalUrl").and_then(Value::as_str);
            conn.execute(
                "UPDATE animes SET externalSource = ?1, externalId = ?2, externalUrl = ?3 WHERE id = ?4",
                params![src, eid, url, id],
            )?;
        }
    }
    Ok(())
}

/// 归一化制作组/社团名（去多余连点/重复空格，避免 `Production.IMS` 与 `ProductionIMS` 并存）
pub fn normalize_studio_name(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let compact: String = trimmed
        .chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '.' | '·' | '・' | '-' | '_' | '/'))
        .collect::<String>()
        .to_ascii_lowercase();
    match compact.as_str() {
        "productionims" => "Production IMS".to_string(),
        "productionig" => "Production I.G".to_string(),
        "jcstaff" => "J.C.STAFF".to_string(),
        "a1pictures" => "A-1 Pictures".to_string(),
        "paworks" => "P.A.WORKS".to_string(),
        "kyotoanimation" | "京阿尼" => "京都动画".to_string(),
        "witstudio" => "WIT STUDIO".to_string(),
        "ufotable" => "ufotable".to_string(),
        "cloverworks" => "CloverWorks".to_string(),
        "mappa" => "MAPPA".to_string(),
        "madhouse" => "MADHOUSE".to_string(),
        "sunrise" => "SUNRISE".to_string(),
        "bones" => "BONES".to_string(),
        "shaft" => "SHAFT".to_string(),
        "trigger" => "TRIGGER".to_string(),
        "silverlink" => "SILVER LINK.".to_string(),
        "geektoys" => "GEEKTOYS".to_string(),
        "dogakobo" | "动画工房" => "动画工房".to_string(),
        "feel" => "feel.".to_string(),
        "whitefox" => "WHITE FOX".to_string(),
        "8bit" => "8bit".to_string(),
        "bind" | "studiobind" => "Studio Bind".to_string(),
        "lidenfilms" => "LIDENFILMS".to_string(),
        _ => trimmed.split_whitespace().collect::<Vec<_>>().join(" "),
    }
}

fn compact_alnum_key(s: &str) -> String {
    s.chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '.' | '·' | '・' | '-' | '_' | '/' | '!' | '！' | '：' | ':'))
        .collect::<String>()
        .to_ascii_lowercase()
}

/// 过滤外部源（Bangumi 等）返回的大众元数据型标签，避免污染用户的题材标签体系。
/// 返回 `(保留的题材标签, 被过滤的元数据标签)`。
pub fn filter_metadata_tags_for_rehydrate(
    raw_tags: &[String],
    work_title: &str,
    studio_opt: Option<&str>,
) -> (Vec<String>, Vec<String>) {
    const FORMAT_TAGS: &[&str] = &[
        "tv", "tv动画", "剧场版", "劇場版", "剧场版动画", "电影", "映画", "movie",
        "ova", "oad", "web", "web动画", "网络动画", "ona", "sp", "特别篇", "特別編",
        "总集篇", "総集編", "短片", "泡面番", "动画", "番剧", "anime", "漫画", "轻小说",
        "小说", "剧场", "ova/oad",
    ];
    const REGION_TAGS: &[&str] = &[
        "日本", "日本动画", "中国", "国产", "国产动画", "国漫", "欧美", "美国", "韩国",
        "英国", "法国", "港台", "台湾", "香港", "中日合作", "日漫",
    ];
    const ADAPTATION_TAGS: &[&str] = &[
        "轻小说改", "小说改", "漫画改", "游戏改", "漫改", "轻改", "游改", "gal改",
        "轻小说改编", "小说改编", "漫画改编", "游戏改编", "原创", "原创动画", "原創",
        "衍生", "续作", "续篇", "前传", "外传",
    ];
    const KNOWN_STUDIO_KEYS: &[&str] = &[
        "productionims", "productionig", "jcstaff", "a1pictures", "paworks",
        "kyotoanimation", "witstudio", "ufotable", "cloverworks", "mappa",
        "madhouse", "sunrise", "bones", "shaft", "trigger", "silverlink",
        "geektoys", "dogakobo", "whitefox", "8bit", "studiobind", "lidenfilms",
        "京都动画", "动画工房", "节操社", "骨头社", "飞碟社", "霸权社", "疯房子", "日升",
        "小丑社", "白狐社", "大沼心", "新房昭之", "宫崎骏", "新海诚",
    ];

    let title_key = compact_alnum_key(work_title);
    let studio_key = studio_opt
        .map(normalize_studio_name)
        .map(|s| compact_alnum_key(&s))
        .unwrap_or_default();

    let mut kept = Vec::new();
    let mut filtered = Vec::new();
    let mut seen = HashSet::<String>::new();

    for t in raw_tags {
        let trimmed = t.trim();
        if trimmed.is_empty() {
            continue;
        }
        let low = trimmed.to_ascii_lowercase();
        let ckey = compact_alnum_key(trimmed);
        let is_year_or_season = (trimmed.len() == 4 && trimmed.chars().all(|c| c.is_ascii_digit()))
            || trimmed.ends_with('年')
            || trimmed.ends_with('月')
            || trimmed.contains("年1月")
            || trimmed.contains("年4月")
            || trimmed.contains("年7月")
            || trimmed.contains("年10月");
        let is_meta = is_year_or_season
            || FORMAT_TAGS.contains(&low.as_str())
            || REGION_TAGS.contains(&low.as_str())
            || ADAPTATION_TAGS.contains(&low.as_str())
            || KNOWN_STUDIO_KEYS.contains(&ckey.as_str())
            || (!studio_key.is_empty() && ckey == studio_key)
            || (!title_key.is_empty()
                && ckey.len() >= 3
                && (ckey == title_key || title_key.starts_with(&ckey)));

        if is_meta {
            filtered.push(trimmed.to_string());
        } else if seen.insert(low) {
            kept.push(trimmed.to_string());
        }
    }
    (kept, filtered)
}

pub struct BuiltRehydrateOp {
    pub op: Option<Value>,
    pub filled_fields: Vec<String>,
    pub missing_remote_fields: Vec<String>,
    pub skipped_not_in_whitelist: Vec<String>,
    pub filtered_meta_tags: Vec<String>,
    pub cover_mode: &'static str, // "remote" | "local" | "none"
}

/// D4/D6：从外部源（Bangumi）构建“只填空值、绝不覆盖用户手填值”的元数据回填更新操作与跳过原因（兼容旧签名）
pub fn build_rehydrate_op_from_detail(
    item: &CuratorItem,
    detail: &crate::cloud::RemoteSubjectDetail,
    fields_whitelist: &[String],
    existing_synopsis_empty: bool,
) -> (Option<Value>, Vec<String>, Vec<String>) {
    let built = build_rehydrate_op_from_detail_full(
        item,
        detail,
        fields_whitelist,
        existing_synopsis_empty,
        None,
        true,
    );
    (built.op, built.filled_fields, built.missing_remote_fields)
}

/// D6(7)：兼容旧签名（传入外部已决议的封面字符串，默认开启元数据标签过滤）
pub fn build_rehydrate_op_from_detail_with_cover(
    item: &CuratorItem,
    detail: &crate::cloud::RemoteSubjectDetail,
    fields_whitelist: &[String],
    existing_synopsis_empty: bool,
    local_cover_result: Option<std::result::Result<String, String>>,
) -> (Option<Value>, Vec<String>, Vec<String>) {
    let cover_target = local_cover_result.map(|res| {
        res.map(|url| {
            let is_local = url.starts_with("animeow-cover://");
            crate::cloud::ResolvedCoverTarget {
                url,
                is_local,
                reason: if is_local {
                    "落盘本地封面".into()
                } else {
                    "远程封面探测可用".into()
                },
            }
        })
    });
    let built = build_rehydrate_op_from_detail_full(
        item,
        detail,
        fields_whitelist,
        existing_synopsis_empty,
        cover_target,
        true,
    );
    (built.op, built.filled_fields, built.missing_remote_fields)
}

/// 完整版元数据回填构造器：
/// - 严格执行 `fields_whitelist` 硬约束，外部源提供了但不在白名单中的字段显式记入 `skipped_not_in_whitelist`；
/// - 封面按 `resolved_cover`（`remote` 优先 / 失败回落本地）写入，探测与下载均失败时绝不写入坏链接；
/// - `tags` 按 `filter_metadata_tags` 默认剥离形态/地区/改编/制作组类元数据标签；
/// - `studio` 经 `normalize_studio_name` 归一化后再写入。
pub fn build_rehydrate_op_from_detail_full(
    item: &CuratorItem,
    detail: &crate::cloud::RemoteSubjectDetail,
    fields_whitelist: &[String],
    existing_synopsis_empty: bool,
    resolved_cover: Option<std::result::Result<crate::cloud::ResolvedCoverTarget, String>>,
    filter_metadata_tags: bool,
) -> BuiltRehydrateOp {
    let want_all = fields_whitelist.is_empty();
    let wants = |f: &str| -> bool {
        want_all
            || fields_whitelist.iter().any(|w| {
                let lw = w.trim().to_ascii_lowercase();
                match f {
                    "cover" => matches!(lw.as_str(), "cover" | "cover_url" | "coverurl"),
                    "release_date" => matches!(lw.as_str(), "release_date" | "releasedate" | "air_date"),
                    "tags" => lw == "tags",
                    "summary" => matches!(lw.as_str(), "summary" | "synopsis"),
                    "total_episodes" => matches!(lw.as_str(), "total_episodes" | "progress_total" | "episodes"),
                    "workType" => matches!(lw.as_str(), "worktype" | "media_format" | "mediaformat" | "work_type"),
                    "studio" => matches!(lw.as_str(), "studio" | "studio_or_circle"),
                    "original_title" => matches!(lw.as_str(), "original_title" | "originaltitle"),
                    "characters" => matches!(lw.as_str(), "characters" | "character"),
                    _ => lw == f,
                }
            })
    };

    let mut op = serde_json::Map::new();
    let mut filled_fields = Vec::<String>::new();
    let mut missing_remote_fields = Vec::<String>::new();
    let mut skipped_not_in_whitelist = Vec::<String>::new();
    let mut filtered_meta_tags = Vec::<String>::new();
    let mut cover_mode: &'static str = "none";

    // 1. cover
    if wants("cover") {
        if item.cover_url.trim().is_empty() {
            match resolved_cover {
                Some(Ok(target)) => {
                    cover_mode = if target.is_local { "local" } else { "remote" };
                    op.insert("cover_url".into(), json!(target.url));
                    op.insert("_coverIsLocal".into(), json!(target.is_local));
                    if !target.reason.trim().is_empty() {
                        op.insert("_coverStrategyReason".into(), json!(target.reason));
                    }
                    filled_fields.push("cover".into());
                }
                Some(Err(err_reason)) => {
                    missing_remote_fields.push(format!("cover(远端封面探测或下载失败：{err_reason}，已跳过以免写入坏链接)"));
                }
                None => {
                    if let Some(ref c) = detail.cover_url {
                        cover_mode = if c.starts_with("animeow-cover://") { "local" } else { "remote" };
                        op.insert("cover_url".into(), json!(c));
                        filled_fields.push("cover".into());
                    } else {
                        missing_remote_fields.push("cover(外部源无封面)".into());
                    }
                }
            }
        }
    } else if detail.cover_url.is_some() {
        skipped_not_in_whitelist.push("cover(跳过：不在白名单)".into());
    }

    // 2. release_date
    if wants("release_date") {
        if item.release_date.trim().is_empty() {
            if let Some(ref rd) = detail.release_date {
                if validate_iso_release_date(rd).is_ok() {
                    op.insert("release_date".into(), json!(rd));
                    filled_fields.push("release_date".into());
                } else {
                    missing_remote_fields.push(format!("release_date(外部源格式非常规:{rd})"));
                }
            } else {
                missing_remote_fields.push("release_date(外部源缺少开播/发售日)".into());
            }
        }
    } else if detail.release_date.is_some() {
        skipped_not_in_whitelist.push("release_date(跳过：不在白名单)".into());
    }

    // 3. tags
    if wants("tags") {
        if item.tags.is_empty() {
            if !detail.tags.is_empty() {
                let final_tags = if filter_metadata_tags {
                    let (kept, dropped) = filter_metadata_tags_for_rehydrate(
                        &detail.tags,
                        &item.title,
                        detail.studio.as_deref(),
                    );
                    filtered_meta_tags = dropped;
                    kept
                } else {
                    detail.tags.clone()
                };
                if !final_tags.is_empty() {
                    op.insert("tags".into(), json!(final_tags));
                    filled_fields.push("tags".into());
                } else {
                    missing_remote_fields.push("tags(外部源标签均为形态/地区/制作组元数据标签，已按规则过滤)".into());
                }
            } else {
                missing_remote_fields.push("tags(外部源无标签)".into());
            }
        }
    } else if !detail.tags.is_empty() {
        skipped_not_in_whitelist.push("tags(跳过：不在白名单)".into());
    }

    // 4. summary
    if wants("summary") {
        if matches!(item.kind.as_str(), "anime" | "book") && existing_synopsis_empty {
            if let Some(ref syn) = detail.summary {
                op.insert("synopsis".into(), json!(syn));
                filled_fields.push("summary".into());
            } else {
                missing_remote_fields.push("summary(外部源无简介)".into());
            }
        }
    } else if detail.summary.is_some() {
        skipped_not_in_whitelist.push("summary(跳过：不在白名单)".into());
    }

    // 5. total_episodes
    if wants("total_episodes") {
        if matches!(item.kind.as_str(), "anime" | "book") && item.progress_total == 0 {
            if let Some(eps) = detail.total_episodes.filter(|&v| v > 0) {
                op.insert("progress_total".into(), json!(eps));
                filled_fields.push("total_episodes".into());
            } else {
                missing_remote_fields.push("total_episodes(外部源无总集/卷数)".into());
            }
        }
    } else if detail.total_episodes.is_some_and(|v| v > 0) {
        skipped_not_in_whitelist.push("total_episodes(跳过：不在白名单)".into());
    }

    // 6. workType / mediaFormat
    if wants("workType") {
        if matches!(item.kind.as_str(), "anime" | "book")
            && item.media_format.as_deref().map(|s| s.trim().is_empty()).unwrap_or(true)
        {
            if let Some(ref mf) = detail.media_format {
                op.insert("media_format".into(), json!(mf));
                filled_fields.push("workType".into());
            } else {
                missing_remote_fields.push("workType(外部源无形态字段)".into());
            }
        }
    } else if detail.media_format.is_some() {
        skipped_not_in_whitelist.push("workType(跳过：不在白名单)".into());
    }

    // 7. studio
    if wants("studio") {
        if item.studio_or_circle.trim().is_empty() {
            if let Some(ref st) = detail.studio {
                let normalized_st = normalize_studio_name(st);
                if !normalized_st.is_empty() {
                    op.insert("studio_or_circle".into(), json!(normalized_st));
                    filled_fields.push("studio".into());
                } else {
                    missing_remote_fields.push("studio(外部源无制作/社团)".into());
                }
            } else {
                missing_remote_fields.push("studio(外部源无制作/社团)".into());
            }
        }
    } else if detail.studio.is_some() {
        skipped_not_in_whitelist.push("studio(跳过：不在白名单)".into());
    }

    // 8. original_title
    if wants("original_title") {
        if item.original_title.trim().is_empty() {
            if let Some(ref ot) = detail.original_title {
                op.insert("original_title".into(), json!(ot));
                filled_fields.push("original_title".into());
            }
        }
    } else if detail.original_title.is_some() {
        skipped_not_in_whitelist.push("original_title(跳过：不在白名单)".into());
    }

    let op_opt = if filled_fields.is_empty() {
        None
    } else {
        op.insert("action".into(), json!("update"));
        op.insert("kind".into(), json!(item.kind));
        op.insert("work_id".into(), json!(item.id));
        op.insert(
            "_rehydrateSource".into(),
            json!(format!("{}:{}", detail.external_source, detail.external_id)),
        );
        Some(Value::Object(op))
    };

    BuiltRehydrateOp {
        op: op_opt,
        filled_fields,
        missing_remote_fields,
        skipped_not_in_whitelist,
        filtered_meta_tags,
        cover_mode,
    }
}

pub struct RehydrateProposalOutcome {
    pub proposal: Option<AgentProposal>,
    pub character_proposal: Option<AgentProposal>,
    pub filled_count: usize,
    pub remote_cover_count: usize,
    pub local_fallback_cover_count: usize,
    pub skipped_cover_count: usize,
    pub skipped: Vec<Value>,
    pub skipped_not_in_whitelist: Vec<Value>,
}

/// 检索入库自动富化：当 `create` 操作携带外部 ID 时，按 `coverStoragePolicy` 补齐缺失封面（默认远程探测可用直接引用，失败回落本地）、
/// 原名、开播日、归一制作方、形态、简介、总集数与经净化的题材标签，避免新建条目变成残缺骨架记录。
pub fn enrich_create_op_from_detail(
    op: &mut Value,
    detail: &crate::cloud::RemoteSubjectDetail,
    resolved_cover_uri: Option<&str>,
) {
    enrich_create_op_from_detail_with_options(op, detail, resolved_cover_uri, true);
}

pub fn enrich_create_op_from_detail_with_options(
    op: &mut Value,
    detail: &crate::cloud::RemoteSubjectDetail,
    resolved_cover_uri: Option<&str>,
    filter_metadata_tags: bool,
) {
    let Some(map) = op.as_object_mut() else {
        return;
    };
    let is_blank_key = |m: &serde_json::Map<String, Value>, k: &str| -> bool {
        m.get(k)
            .and_then(Value::as_str)
            .map(|s| s.trim().is_empty())
            .unwrap_or(true)
    };
    if is_blank_key(map, "cover_url") {
        if let Some(cov_uri) = resolved_cover_uri {
            map.insert("cover_url".into(), json!(cov_uri));
        }
    }
    if is_blank_key(map, "original_title") {
        if let Some(ref ot) = detail.original_title {
            map.insert("original_title".into(), json!(ot));
        }
    }
    if is_blank_key(map, "release_date") {
        if let Some(ref rd) = detail.release_date {
            if validate_iso_release_date(rd).is_ok() {
                map.insert("release_date".into(), json!(rd));
            }
        }
    }
    if is_blank_key(map, "studio_or_circle") {
        if let Some(ref st) = detail.studio {
            let norm = normalize_studio_name(st);
            if !norm.is_empty() {
                map.insert("studio_or_circle".into(), json!(norm));
            }
        }
    }
    if is_blank_key(map, "synopsis") {
        if let Some(ref syn) = detail.summary {
            map.insert("synopsis".into(), json!(syn));
        }
    }
    let has_explicit_format = !is_blank_key(map, "media_format")
        || !is_blank_key(map, "mediaFormat")
        || !is_blank_key(map, "workType")
        || !is_blank_key(map, "work_type")
        || map.contains_key("movieFormat")
        || map.contains_key("movie_format");
    if !has_explicit_format {
        if let Some(ref mf) = detail.media_format {
            map.insert("media_format".into(), json!(mf));
        }
    }
    let has_explicit_total = map.get("total_episodes").and_then(Value::as_i64).is_some_and(|n| n > 0)
        || map.get("progress_total").and_then(Value::as_i64).is_some_and(|n| n > 0);
    if !has_explicit_total {
        if let Some(ep) = detail.total_episodes.filter(|&n| n > 0) {
            map.insert("total_episodes".into(), json!(ep));
        }
    }
    let has_explicit_tags = map
        .get("tags")
        .and_then(Value::as_array)
        .is_some_and(|arr| !arr.is_empty());
    if !has_explicit_tags && !detail.tags.is_empty() {
        let title_str = map
            .get("title")
            .and_then(Value::as_str)
            .or(detail.title.as_deref())
            .unwrap_or("");
        let clean_tags = if filter_metadata_tags {
            filter_metadata_tags_for_rehydrate(&detail.tags, title_str, detail.studio.as_deref()).0
        } else {
            detail.tags.clone()
        };
        if !clean_tags.is_empty() {
            map.insert("tags".into(), json!(clean_tags));
        }
    }
    if !map.contains_key("public_score") {
        if let Some(sc) = detail.public_score {
            map.insert("public_score".into(), json!(sc));
        }
    }
    if !map.contains_key("field_source") {
        map.insert("field_source".into(), json!("external"));
    }
}

pub async fn enrich_create_args_from_bangumi(
    studio_data_dir: &std::path::Path,
    args: &Value,
) -> Value {
    enrich_create_args_from_bangumi_with_policy(studio_data_dir, args, "remote", true).await
}

pub async fn enrich_create_args_from_bangumi_with_policy(
    studio_data_dir: &std::path::Path,
    args: &Value,
    cover_storage_policy: &str,
    filter_metadata_tags: bool,
) -> Value {
    let Some(ops_arr) = args.get("operations").and_then(Value::as_array) else {
        return args.clone();
    };
    let has_remote_create = ops_arr.iter().any(|op| {
        if op.get("action").and_then(Value::as_str) != Some("create") {
            return false;
        }
        let src = op.get("external_source").and_then(Value::as_str).unwrap_or("");
        let eid = op.get("external_id").and_then(Value::as_str).map(str::trim).unwrap_or("");
        let kind = op.get("kind").and_then(Value::as_str).unwrap_or("anime");
        (src == "bangumi" && !eid.is_empty() && eid.chars().all(|c| c.is_ascii_digit()))
            || ((src == "vndb" || kind == "game") && !eid.is_empty())
            || ((src == "dlsite" || kind == "audio") && !eid.is_empty())
    });
    if !has_remote_create {
        return args.clone();
    }
    let mut cloned = args.clone();
    if let Some(ops) = cloned.get_mut("operations").and_then(Value::as_array_mut) {
        for op in ops.iter_mut() {
            if op.get("action").and_then(Value::as_str) != Some("create") {
                continue;
            }
            let src = op.get("external_source").and_then(Value::as_str).unwrap_or("").to_string();
            let kind = op.get("kind").and_then(Value::as_str).unwrap_or("anime").to_string();
            let eid = op.get("external_id").and_then(Value::as_str).map(str::trim).unwrap_or("").to_string();
            if eid.is_empty() {
                continue;
            }
            let needs_cover = op
                .get("cover_url")
                .and_then(Value::as_str)
                .map(|s| s.trim().is_empty())
                .unwrap_or(true);

            if src == "bangumi" && eid.chars().all(|c| c.is_ascii_digit()) {
                if let Ok(detail) =
                    crate::cloud::fetch_bangumi_subject_detail_via_server_first(studio_data_dir, &eid).await
                {
                    let resolved_cover = if needs_cover {
                        if let Some(ref cov_url) = detail.cover_url {
                            crate::cloud::resolve_remote_cover_by_policy(
                                studio_data_dir,
                                None,
                                cov_url,
                                &format!("bgm_{eid}"),
                                cover_storage_policy,
                            )
                            .await
                            .ok()
                            .map(|t| t.url)
                        } else {
                            None
                        }
                    } else {
                        None
                    };
                    enrich_create_op_from_detail_with_options(
                        op,
                        &detail,
                        resolved_cover.as_deref(),
                        filter_metadata_tags,
                    );
                }
            } else if src == "vndb" || kind == "game" {
                let query = op
                    .get("title")
                    .and_then(Value::as_str)
                    .filter(|s| !s.trim().is_empty())
                    .unwrap_or(&eid)
                    .to_string();
                if let Ok(hits) = crate::cloud::search_vndb_and_cloud_games(studio_data_dir, &query).await {
                    let matched = hits
                        .into_iter()
                        .find(|h| h.external_id.eq_ignore_ascii_case(&eid));
                    if let Some(hit) = matched {
                        let resolved_cover = if needs_cover && !hit.cover_url.trim().is_empty() {
                            crate::cloud::resolve_remote_cover_by_policy(
                                studio_data_dir,
                                None,
                                &hit.cover_url,
                                &format!("vndb_{eid}"),
                                cover_storage_policy,
                            )
                            .await
                            .ok()
                            .map(|t| t.url)
                        } else {
                            None
                        };
                        let detail = crate::cloud::RemoteSubjectDetail {
                            external_source: hit.external_source,
                            external_id: hit.external_id,
                            title: (!hit.title.trim().is_empty()).then_some(hit.title),
                            original_title: (!hit.original_title.trim().is_empty()).then_some(hit.original_title),
                            cover_url: (!hit.cover_url.trim().is_empty()).then_some(hit.cover_url),
                            cover_thumb_url: None,
                            release_date: (!hit.release_date.trim().is_empty()).then_some(hit.release_date),
                            total_episodes: None,
                            summary: (!hit.synopsis.trim().is_empty()).then_some(hit.synopsis),
                            studio: (!hit.studio_or_circle.trim().is_empty()).then_some(hit.studio_or_circle),
                            media_format: None,
                            tags: vec![],
                            public_score: hit.public_score,
                        };
                        enrich_create_op_from_detail_with_options(
                            op,
                            &detail,
                            resolved_cover.as_deref(),
                            filter_metadata_tags,
                        );
                    }
                }
            } else if src == "dlsite" || kind == "audio" {
                if let Ok(hits) = crate::cloud::lookup_dlsite_audio_catalog(studio_data_dir, &eid).await {
                    let matched = hits
                        .into_iter()
                        .find(|h| h.external_id.eq_ignore_ascii_case(&eid));
                    if let Some(hit) = matched {
                        let resolved_cover = if needs_cover && !hit.cover_url.trim().is_empty() {
                            crate::cloud::resolve_remote_cover_by_policy(
                                studio_data_dir,
                                None,
                                &hit.cover_url,
                                &format!("dlsite_{eid}"),
                                cover_storage_policy,
                            )
                            .await
                            .ok()
                            .map(|t| t.url)
                        } else {
                            None
                        };
                        let detail = crate::cloud::RemoteSubjectDetail {
                            external_source: hit.external_source,
                            external_id: hit.external_id,
                            title: (!hit.title.trim().is_empty()).then_some(hit.title),
                            original_title: (!hit.original_title.trim().is_empty()).then_some(hit.original_title),
                            cover_url: (!hit.cover_url.trim().is_empty()).then_some(hit.cover_url),
                            cover_thumb_url: None,
                            release_date: (!hit.release_date.trim().is_empty()).then_some(hit.release_date),
                            total_episodes: None,
                            summary: (!hit.synopsis.trim().is_empty()).then_some(hit.synopsis),
                            studio: (!hit.studio_or_circle.trim().is_empty()).then_some(hit.studio_or_circle),
                            media_format: None,
                            tags: vec![],
                            public_score: hit.public_score,
                        };
                        enrich_create_op_from_detail_with_options(
                            op,
                            &detail,
                            resolved_cover.as_deref(),
                            filter_metadata_tags,
                        );
                    }
                }
            }
        }
    }
    cloned
}

/// D4：批量/单条从外部数据源回填缺失元数据（dry-run 预览 + 待确认提案 + 可整体撤销，最多 500 条）
pub async fn create_rehydrate_metadata_proposal(
    sync_db: &std::sync::Mutex<Connection>,
    local_db: &std::sync::Mutex<Connection>,
    studio_data_dir: &std::path::Path,
    session_id: &str,
    args: &Value,
) -> Result<RehydrateProposalOutcome> {
    let fields: Vec<String> = args
        .get("fields")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(str::trim))
                .filter(|s| !s.is_empty())
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_else(|| {
            vec![
                "cover".into(),
                "release_date".into(),
                "tags".into(),
                "summary".into(),
                "total_episodes".into(),
                "workType".into(),
                "studio".into(),
            ]
        });

    let want_characters = fields.iter().any(|f| {
        let l = f.trim().to_ascii_lowercase();
        l == "characters" || l == "character"
    });
    let kind_filter = args.get("kind").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty());
    let targets: Vec<(CuratorItem, bool, usize)> = {
        let sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let all_items = query_curator_items(&sync, &local)?;
        let selected_items: Vec<CuratorItem> = if let Some(rid) = args.get("result_id").and_then(Value::as_str) {
            let captured = load_library_result(&local, rid, Some(session_id))?;
            let live_map: std::collections::HashMap<_, _> = all_items.iter().map(|it| ((it.kind.as_str(), it.id), it)).collect();
            captured
                .iter()
                .filter_map(|c| live_map.get(&(c.kind.as_str(), c.id)).copied().cloned())
                .collect()
        } else if let Some(arr) = args.get("work_ids").and_then(Value::as_array) {
            let mut list = Vec::new();
            for v in arr {
                if let Some(wid) = v.as_i64() {
                    let it = resolve_work_by_kind_or_unique(&all_items, kind_filter, wid)?;
                    list.push(it.clone());
                }
            }
            list
        } else if let Some(wid) = args.get("work_id").and_then(Value::as_i64) {
            vec![resolve_work_by_kind_or_unique(&all_items, kind_filter, wid)?.clone()]
        } else {
            return Err(StudioError::InvalidInput("mode=\"rehydrate\" 需要提供 work_id、work_ids 或 result_id。".into()));
        };
        if selected_items.len() > 500 {
            return Err(StudioError::InvalidInput("单次回填最多支持 500 部作品，请分批执行。".into()));
        }
        let mut out = Vec::with_capacity(selected_items.len());
        for it in selected_items {
            let syn_empty = if matches!(it.kind.as_str(), "anime" | "book") {
                sync.query_row(
                    "SELECT COALESCE(TRIM(synopsis), '') = '' FROM animes WHERE id = ?1",
                    [it.id],
                    |r| r.get::<_, bool>(0),
                )
                .unwrap_or(true)
            } else {
                false
            };
            let existing_char_cnt: usize = if want_characters && matches!(it.kind.as_str(), "anime" | "book") {
                sync.query_row(
                    "SELECT COUNT(*) FROM anime_characters WHERE animeId = ?1",
                    [it.id],
                    |r| r.get::<_, i64>(0),
                )
                .unwrap_or(0)
                .max(0) as usize
            } else {
                0
            };
            out.push((it, syn_empty, existing_char_cnt));
        }
        out
    };

    if targets.is_empty() {
        return Err(StudioError::InvalidInput("未找到需要回填的目标作品。".into()));
    }

    let (cover_storage_policy, filter_metadata_tags) = {
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let pol = crate::agent::storage::get_policy_config(&local).unwrap_or_default();
        let cov_pol = args
            .get("cover_storage_policy")
            .or_else(|| args.get("coverStoragePolicy"))
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| matches!(*s, "remote" | "local" | "auto"))
            .map(str::to_string)
            .unwrap_or(pol.cover_storage_policy);
        let filter_tags = args
            .get("filter_metadata_tags")
            .or_else(|| args.get("filterMetadataTags"))
            .and_then(Value::as_bool)
            .unwrap_or(pol.filter_metadata_tags);
        (cov_pol, filter_tags)
    };

    let mut operations = Vec::<Value>::new();
    let mut character_ops = Vec::<Value>::new();
    let mut skipped = Vec::<Value>::new();
    let mut skipped_not_in_whitelist_all = Vec::<Value>::new();
    let mut remote_cover_count = 0usize;
    let mut local_fallback_cover_count = 0usize;
    let mut skipped_cover_count = 0usize;

    for (it, syn_empty, existing_char_cnt) in targets {
        let bgm_id = if it.external_source.as_deref() == Some("bangumi") {
            it.external_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        } else {
            None
        };
        let Some(sid) = bgm_id else {
            skipped.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "status": "skipped",
                "reason": "未绑定 Bangumi 外部 ID，无法从外部源回填（请先批量核实并绑定）",
            }));
            continue;
        };
        let detail = match crate::cloud::fetch_bangumi_subject_detail_via_server_first(studio_data_dir, sid).await {
            Ok(d) => d,
            Err(err) => {
                skipped.push(json!({
                    "workId": it.id,
                    "kind": it.kind,
                    "title": it.title,
                    "externalId": sid,
                    "status": "skipped",
                    "reason": format!("外部源抓取失败：{err}"),
                }));
                continue;
            }
        };
        let want_cover = fields.is_empty()
            || fields.iter().any(|f| {
                let l = f.trim().to_ascii_lowercase();
                l == "cover" || l == "cover_url" || l == "coverurl"
            });
        let resolved_cover_res = if want_cover && it.cover_url.trim().is_empty() {
            if let Some(ref remote_cov) = detail.cover_url {
                let res = crate::cloud::resolve_remote_cover_by_policy(
                    studio_data_dir,
                    Some(local_db),
                    remote_cov,
                    &format!("{}_{}", it.kind, it.id),
                    &cover_storage_policy,
                )
                .await;
                Some(res)
            } else {
                skipped_cover_count += 1;
                None
            }
        } else {
            None
        };
        let built = build_rehydrate_op_from_detail_full(
            &it,
            &detail,
            &fields,
            syn_empty,
            resolved_cover_res,
            filter_metadata_tags,
        );
        match built.cover_mode {
            "remote" => remote_cover_count += 1,
            "local" => local_fallback_cover_count += 1,
            _ => {
                if want_cover && it.cover_url.trim().is_empty() && detail.cover_url.is_some() {
                    skipped_cover_count += 1;
                }
            }
        }
        if !built.skipped_not_in_whitelist.is_empty() {
            skipped_not_in_whitelist_all.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "fields": built.skipped_not_in_whitelist,
            }));
        }
        let op_opt = built.op;
        let filled = built.filled_fields;
        let mut missing_remote = built.missing_remote_fields;
        // 若白名单包含 characters 且本地尚无关联角色，顺带拉取主要角色生成伴随角色建档提案（支持预览与一键撤销）
        let mut added_chars_for_work = 0usize;
        if want_characters && matches!(it.kind.as_str(), "anime" | "book") && existing_char_cnt == 0 {
            if let Ok(sid_num) = sid.parse::<i64>() {
                match crate::cloud::fetch_work_characters_via_server_first(studio_data_dir, sid_num).await {
                    Ok(chars) if !chars.is_empty() => {
                        for ch in chars.into_iter().take(10) {
                            if character_ops.len() >= 120 {
                                break;
                            }
                            let display_name = if ch.name.trim().is_empty() {
                                ch.name_cn.clone()
                            } else {
                                ch.name.clone()
                            };
                            if display_name.trim().is_empty() {
                                continue;
                            }
                            let mut cop = serde_json::Map::new();
                            cop.insert("action".into(), json!("upsert_character"));
                            cop.insert("name".into(), json!(display_name));
                            if !ch.name_cn.trim().is_empty() {
                                cop.insert("name_cn".into(), json!(ch.name_cn));
                            }
                            if !ch.image_url.trim().is_empty() {
                                cop.insert("image_url".into(), json!(ch.image_url));
                            }
                            if !ch.role_name.trim().is_empty() {
                                cop.insert("role_name".into(), json!(ch.role_name));
                            }
                            if !ch.voice_actor.trim().is_empty() {
                                cop.insert("voice_actor".into(), json!(ch.voice_actor));
                            }
                            if let Some(bid) = ch.bgm_id {
                                cop.insert("bgm_id".into(), json!(bid));
                            }
                            cop.insert("anime_id".into(), json!(it.id));
                            cop.insert("expected_title".into(), json!(it.title));
                            character_ops.push(Value::Object(cop));
                            added_chars_for_work += 1;
                        }
                    }
                    _ => {
                        missing_remote.push("characters(外部源无角色表)".into());
                    }
                }
            }
        }
        if let Some(op) = op_opt {
            operations.push(op);
            if !missing_remote.is_empty() {
                skipped.push(json!({
                    "workId": it.id,
                    "kind": it.kind,
                    "title": it.title,
                    "externalId": sid,
                    "status": "partial_missing_remote",
                    "filledFields": filled,
                    "reason": format!("其余字段外部源缺失：{}", missing_remote.join("、")),
                }));
            }
        } else if added_chars_for_work == 0 {
            let reason = if !missing_remote.is_empty() {
                format!("外部条目 bangumi:{sid} 缺少所需字段：{}", missing_remote.join("、"))
            } else {
                "本地目标字段均已完整（仅填空值不覆盖已有编辑），无需修改".into()
            };
            skipped.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "externalId": sid,
                "status": "skipped",
                "reason": reason,
            }));
        }
    }

    if operations.is_empty() && character_ops.is_empty() {
        return Ok(RehydrateProposalOutcome {
            proposal: None,
            character_proposal: None,
            filled_count: 0,
            remote_cover_count,
            local_fallback_cover_count,
            skipped_cover_count,
            skipped,
            skipped_not_in_whitelist: skipped_not_in_whitelist_all,
        });
    }

    let filled_count = operations.len();
    let sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
    let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
    let work_proposal = if !operations.is_empty() {
        let title = args
            .get("title")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| format!("外部元数据补全方案（{filled_count} 部）"));
        let default_reason = if cover_storage_policy == "local" {
            "仅在线补全本地缺失的资料与封面（封面按离线设置保存至本机），绝不覆盖已有内容，支持一键撤销。"
        } else {
            "仅在线补全本地缺失的资料与封面（封面优先使用在线图片链接以控制备份体积，不可用时再保存本机），绝不覆盖已有内容，支持一键撤销。"
        };
        let reason = args
            .get("reason")
            .and_then(Value::as_str)
            .unwrap_or(default_reason);
        let proposal_args = json!({
            "title": title,
            "reason": reason,
            "operations": operations,
        });
        let (proposal, _) = create_work_mutations_proposal(&sync, &local, session_id, &proposal_args)?;
        Some(proposal)
    } else {
        None
    };
    let character_proposal = if !character_ops.is_empty() {
        let c_args = json!({
            "title": format!("外部角色表补全方案（{} 位角色）", character_ops.len()),
            "reason": "为缺失角色表的骨架作品补全 Bangumi 主要出场角色与关联，支持一键撤销。",
            "operations": character_ops,
        });
        create_character_mutations_proposal(&sync, &local, session_id, &c_args).ok()
    } else {
        None
    };
    Ok(RehydrateProposalOutcome {
        proposal: work_proposal,
        character_proposal,
        filled_count,
        remote_cover_count,
        local_fallback_cover_count,
        skipped_cover_count,
        skipped,
        skipped_not_in_whitelist: skipped_not_in_whitelist_all,
    })
}

pub struct MigrateCoversOutcome {
    pub proposal: Option<AgentProposal>,
    pub migrated_count: usize,
    pub kept_local: Vec<Value>,
    pub excluded_user_custom_count: usize,
    pub baseline_stats: crate::agent::types::CoverBaselineStats,
}

/// 本地封面转远程引用瘦身：
/// 仅对 `cover.source == "external_native"`（程序自动落盘封面）生效，所有 `user_custom` 用户手传封面强制排除；
/// 先做真实远程可用性探测，仅探测通过的才生成可撤销提案改写为标准远程 URL（仅更新封面，不动任何其他字段）；
/// 外部源无图或探测失败的保留本地并列入 `kept_local` 与原因。
pub async fn create_migrate_covers_to_remote_proposal(
    sync_db: &std::sync::Mutex<Connection>,
    local_db: &std::sync::Mutex<Connection>,
    studio_data_dir: &std::path::Path,
    session_id: &str,
    args: &Value,
) -> Result<MigrateCoversOutcome> {
    let kind_filter = args.get("kind").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty());
    let (candidates, excluded_user_custom_count, baseline_stats) = {
        let sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let baseline = crate::agent::tools_read::compute_cover_baseline_stats(&sync, &local, studio_data_dir)?;
        let all_items = query_curator_items(&sync, &local)?;
        let scope_items: Vec<CuratorItem> = if let Some(rid) = args.get("result_id").and_then(Value::as_str) {
            let captured = load_library_result(&local, rid, Some(session_id))?;
            let live_map: std::collections::HashMap<_, _> = all_items.iter().map(|it| ((it.kind.as_str(), it.id), it)).collect();
            captured
                .iter()
                .filter_map(|c| live_map.get(&(c.kind.as_str(), c.id)).copied().cloned())
                .collect()
        } else if let Some(arr) = args.get("work_ids").and_then(Value::as_array) {
            let mut list = Vec::new();
            for v in arr {
                if let Some(wid) = v.as_i64() {
                    let it = resolve_work_by_kind_or_unique(&all_items, kind_filter, wid)?;
                    list.push(it.clone());
                }
            }
            list
        } else if let Some(wid) = args.get("work_id").and_then(Value::as_i64) {
            vec![resolve_work_by_kind_or_unique(&all_items, kind_filter, wid)?.clone()]
        } else {
            all_items
        };

        let mut auto_native = Vec::<CuratorItem>::new();
        let mut user_custom_cnt = 0usize;
        for it in scope_items {
            if let Some(kf) = kind_filter {
                if it.kind != kf {
                    continue;
                }
            }
            let fsrc = crate::agent::storage::load_work_field_sources(&local, &it.kind, it.id)
                .ok()
                .and_then(|m| m.get("cover").map(|(s, _)| s.clone()));
            let cov = crate::agent::tools_read::inspect_cover_metadata_with_source(
                &it.cover_url,
                fsrc.as_deref(),
                Some(studio_data_dir),
                Some(&local),
            );
            match cov.cover_source {
                "external_native" => auto_native.push(it),
                "user_custom" => user_custom_cnt += 1,
                _ => {}
            }
        }
        (auto_native, user_custom_cnt, baseline)
    };

    if candidates.len() > 500 {
        return Err(StudioError::InvalidInput("单次封面迁移最多处理 500 部作品，请分批执行。".into()));
    }

    let mut operations = Vec::<Value>::new();
    let mut kept_local = Vec::<Value>::new();

    for it in candidates {
        let bgm_id = if it.external_source.as_deref() == Some("bangumi") {
            it.external_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        } else {
            None
        };
        let Some(sid) = bgm_id else {
            kept_local.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "reason": "未绑定 Bangumi 外部 ID，无可用远程封面源，继续保留本地封面",
            }));
            continue;
        };
        let detail = match crate::cloud::fetch_bangumi_subject_detail_via_server_first(studio_data_dir, sid).await {
            Ok(d) => d,
            Err(err) => {
                kept_local.push(json!({
                    "workId": it.id,
                    "kind": it.kind,
                    "title": it.title,
                    "externalId": sid,
                    "reason": format!("外部源抓取失败（{err}），保留本地封面"),
                }));
                continue;
            }
        };
        let Some(ref raw_remote) = detail.cover_url else {
            kept_local.push(json!({
                "workId": it.id,
                "kind": it.kind,
                "title": it.title,
                "externalId": sid,
                "reason": "外部源未提供封面，保留本地封面",
            }));
            continue;
        };
        match crate::cloud::probe_remote_cover_url(raw_remote).await {
            Ok(verified_url) => {
                if let Ok(local) = local_db.lock() {
                    let _ = crate::agent::storage::save_cached_cover_probe(&local, &verified_url, true);
                }
                operations.push(json!({
                    "action": "update",
                    "kind": it.kind,
                    "work_id": it.id,
                    "cover_url": verified_url,
                    "field_source": "external",
                    "_coverIsLocal": false,
                    "_coverStrategyReason": "本地转远程引用（探测可用）",
                }));
            }
            Err(probe_err) => {
                kept_local.push(json!({
                    "workId": it.id,
                    "kind": it.kind,
                    "title": it.title,
                    "externalId": sid,
                    "reason": format!("远程封面探测不可用（{probe_err}），继续保留本地封面以免破图"),
                }));
            }
        }
    }

    let migrated_count = operations.len();
    if operations.is_empty() {
        return Ok(MigrateCoversOutcome {
            proposal: None,
            migrated_count: 0,
            kept_local,
            excluded_user_custom_count,
            baseline_stats,
        });
    }

    let sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
    let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
    let title = args
        .get("title")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| format!("本地封面转远程引用瘦身方案（{migrated_count} 部）"));
    let reason = args
        .get("reason")
        .and_then(Value::as_str)
        .unwrap_or("将程序自动落盘且远程探测可用的本地封面替换为标准远程链接，降低备份包体积；用户自定义封面与探测失败项已自动保留本地，支持整体撤销。");
    let proposal_args = json!({
        "title": title,
        "reason": reason,
        "operations": operations,
    });
    let (proposal, _) = create_work_mutations_proposal(&sync, &local, session_id, &proposal_args)?;
    Ok(MigrateCoversOutcome {
        proposal: Some(proposal),
        migrated_count,
        kept_local,
        excluded_user_custom_count,
        baseline_stats,
    })
}

/// 分级自动审批资格判定（含硬性安全红线守卫）：
/// 任何删除、不可撤销、覆盖用户已有值、清空字段、存在重叠警告、低置信度或超上限提案一律返回 `false` 强制人工确认。
pub fn should_auto_approve_work_proposal(
    proposal: &AgentProposal,
    policy: &crate::agent::types::AgentPolicyConfig,
    session_autopilot: bool,
    category: &str, // "quick_log" | "rehydrate_empty" | "high_confidence_bind" | "create_from_catalog" | "append_tags_only" | "other_safe"
    is_regenerate: bool,
    local_conn: &Connection,
) -> bool {
    if is_regenerate || proposal.status != "pending" {
        return false;
    }
    if policy.trust_mode == "strict" && !session_autopilot {
        return false;
    }
    if !proposal.overlap_warnings.is_empty() {
        return false;
    }
    if proposal_has_non_revertible_or_destructive_ops(proposal) {
        return false;
    }
    let Some(ops) = proposal.ops.as_array() else {
        return false;
    };
    let cap = policy.auto_approve.max_items_per_auto_batch.max(1);
    if ops.is_empty() || ops.len() > cap {
        return false;
    }
    // 硬红线：绝不自动覆盖用户手填字段或清空已有长评/评分/封面/日期
    for op in ops {
        let act = op.get("action").and_then(Value::as_str).unwrap_or("update");
        if act == "update" {
            let kind = op.get("kind").and_then(Value::as_str).unwrap_or("anime");
            let wid = op.get("work_id").and_then(Value::as_i64).unwrap_or(0);
            let fsrc = crate::agent::storage::load_work_field_sources(local_conn, kind, wid)
                .unwrap_or_default();
            for (op_key, src_keys) in [
                ("cover_url", &["cover"][..]),
                ("coverUrl", &["cover"][..]),
                ("release_date", &["release_date"][..]),
                ("releaseDate", &["release_date"][..]),
                ("airDate", &["release_date"][..]),
                ("tags", &["tags"][..]),
                ("synopsis", &["summary"][..]),
                ("summary", &["summary"][..]),
                ("studio_or_circle", &["studio"][..]),
                ("studio", &["studio"][..]),
                ("media_format", &["mediaFormat", "workType"][..]),
                ("mediaFormat", &["mediaFormat", "workType"][..]),
                ("workType", &["workType", "mediaFormat"][..]),
                ("work_type", &["workType", "mediaFormat"][..]),
                ("movieFormat", &["movieFormat", "workType", "mediaFormat"][..]),
                ("movie_format", &["movieFormat", "workType", "mediaFormat"][..]),
                ("progress_total", &["total_episodes"][..]),
                ("total_episodes", &["total_episodes"][..]),
                ("original_title", &["original_title"][..]),
            ] {
                if let Some(val) = op.get(op_key) {
                    // 清空已有字段绝不自动放行
                    let is_clearing = val.as_str().is_some_and(|s| s.trim().is_empty())
                        || val.as_array().is_some_and(|a| a.is_empty());
                    if is_clearing && category != "quick_log" {
                        return false;
                    }
                    // 若该字段已由用户手工编辑，非 quick_log 绝不自动覆盖
                    if category != "quick_log"
                        && src_keys
                            .iter()
                            .any(|sk| fsrc.get(*sk).is_some_and(|(s, _)| s == "user"))
                    {
                        return false;
                    }
                }
            }
            if op.get("review").and_then(Value::as_str).is_some_and(|s| s.trim().is_empty()) {
                return false;
            }
        }
    }

    if session_autopilot {
        return true;
    }
    match policy.trust_mode.as_str() {
        "quick_log" => category == "quick_log",
        "auto_safe" => match category {
            "quick_log" => policy.auto_approve.quick_log,
            "rehydrate_empty" => policy.auto_approve.rehydrate_empty,
            "high_confidence_bind" => policy.auto_approve.high_confidence_bind,
            "create_from_catalog" => policy.auto_approve.create_from_catalog,
            "append_tags_only" => policy.auto_approve.append_tags_only,
            _ => false,
        },
        _ => false,
    }
}

fn apply_direct_rehydrate_patch(
    sync: &mut Connection,
    local: &Connection,
    patch: &CellPatch,
    expected_revision: &str,
    expected_fields: &str,
) -> Result<()> {
    let tx = sync.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let items = query_curator_items(&tx, local)?;
    let current = items.iter().find(|item| item.id == patch.id && item.kind == patch.kind);
    if check_revision(current, Some(expected_revision)).is_err()
        || raw_work_fields(&tx, &patch.kind, patch.id)? != expected_fields
    {
        return Err(StudioError::InvalidInput("联网期间作品资料已变化，已保留现有资料。请重新补全。".into()));
    }
    update_curator_cell(&tx, patch)?;
    tx.commit()?;
    Ok(())
}

/// 桌面端详情抽屉一键直连回填：从服务器/Bangumi 补全单部已绑定作品的缺失封面与元数据（不覆盖用户已填值）
pub async fn rehydrate_single_work_direct(
    sync_db: &std::sync::Mutex<Connection>,
    local_db: &std::sync::Mutex<Connection>,
    studio_data_dir: &std::path::Path,
    kind: &str,
    work_id: i64,
) -> Result<Value> {
    let (item, syn_empty, bgm_id_opt, expected_revision, expected_fields) = {
        let sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let tx = sync.unchecked_transaction()?;
        let all_items = query_curator_items(&tx, &local)?;
        let item = resolve_work_by_kind_or_unique(&all_items, Some(kind), work_id)?.clone();
        let syn_empty = if matches!(item.kind.as_str(), "anime" | "book") {
            tx.query_row(
                "SELECT COALESCE(TRIM(synopsis), '') = '' FROM animes WHERE id = ?1",
                [item.id],
                |r| r.get::<_, bool>(0),
            )?
        } else {
            false
        };
        let bgm_id = if item.external_source.as_deref() == Some("bangumi") {
            item.external_id.clone().filter(|s| !s.trim().is_empty())
        } else {
            item.secondary_bangumi_id.clone()
        };
        let expected_revision = item_revision(&item)?;
        let expected_fields = raw_work_fields(&tx, &item.kind, item.id)?;
        tx.commit()?;
        (item, syn_empty, bgm_id, expected_revision, expected_fields)
    };

    let sid = if let Some(existing_sid) = bgm_id_opt {
        existing_sid
    } else if matches!(item.kind.as_str(), "anime" | "book" | "game") {
        // 尚未绑定外链时，尝试用片名/原名走服务器优先精确匹配一次
        let hits = crate::cloud::search_bangumi_via_server_first(studio_data_dir, &item.title, &item.kind).await?;
        let target = crate::agent::tools_read::LocalWorkVerifyTarget {
            work_id: item.id,
            kind: item.kind.clone(),
            title: item.title.clone(),
            original_title: item.original_title.clone(),
            release_date: item.release_date.clone(),
            total_episodes: item.progress_total,
            media_format: item.media_format.clone(),
            tags: item.tags.clone(),
            existing_bangumi_id: None,
        };
        let eval = crate::agent::tools_read::evaluate_bangumi_matches(&target, hits, 3);
        if eval["matchStatus"] == "exact_match" {
            eval["chosen"]["externalId"]
                .as_str()
                .map(str::to_string)
                .ok_or_else(|| StudioError::InvalidInput("未找到可精确匹配的 Bangumi 条目，请先绑定外部 ID。".into()))?
        } else {
            return Err(StudioError::InvalidInput(
                "该作品尚未绑定 Bangumi ID 且自动检索未能精确唯一命中，请先使用 AI 助手核实或手动绑定后再补全。".into(),
            ));
        }
    } else {
        return Err(StudioError::InvalidInput("当前载体暂不支持直接从 Bangumi 补全元数据。".into()));
    };

    let (cover_storage_policy, filter_metadata_tags) = {
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let pol = crate::agent::storage::get_policy_config(&local).unwrap_or_default();
        (pol.cover_storage_policy, pol.filter_metadata_tags)
    };
    let detail = crate::cloud::fetch_bangumi_subject_detail_via_server_first(studio_data_dir, &sid).await?;
    let all_fields: Vec<String> = vec![
        "cover".into(),
        "release_date".into(),
        "tags".into(),
        "summary".into(),
        "total_episodes".into(),
        "workType".into(),
        "studio".into(),
        "original_title".into(),
    ];
    let resolved_cover_res = if item.cover_url.trim().is_empty() {
        if let Some(ref remote_cov) = detail.cover_url {
            let res = crate::cloud::resolve_remote_cover_by_policy(
                studio_data_dir,
                Some(local_db),
                remote_cov,
                &format!("{}_{}", item.kind, item.id),
                &cover_storage_policy,
            )
            .await;
            Some(res)
        } else {
            None
        }
    } else {
        None
    };
    let built = build_rehydrate_op_from_detail_full(
        &item,
        &detail,
        &all_fields,
        syn_empty,
        resolved_cover_res,
        filter_metadata_tags,
    );
    let op_opt = built.op;
    let filled_fields = built.filled_fields;
    let missing_remote = built.missing_remote_fields;
    let mut skipped_existing = Vec::<String>::new();
    if !item.cover_url.trim().is_empty() {
        skipped_existing.push("cover".into());
    }
    if !item.release_date.trim().is_empty() {
        skipped_existing.push("release_date".into());
    }
    if !item.tags.is_empty() {
        skipped_existing.push("tags".into());
    }
    let Some(op) = op_opt else {
        let summary = if !missing_remote.is_empty() {
            format!("外部条目 bangumi:{sid} 暂无可补充的新字段（{}）", missing_remote.join("、"))
        } else {
            "本地封面与元数据均已完整，无需补全。".to_string()
        };
        return Ok(json!({
            "workId": item.id,
            "kind": item.kind,
            "title": item.title,
            "updated": false,
            "externalId": sid,
            "updatedFields": [],
            "filledFields": [],
            "skippedMissingRemote": missing_remote,
            "missingRemoteFields": missing_remote,
            "skippedExisting": skipped_existing,
            "coverUpdated": false,
            "summary": summary,
            "message": summary,
        }));
    };

    let cover_updated = op.get("cover_url").and_then(Value::as_str).is_some();
    {
        let mut sync = sync_db.lock().map_err(|_| StudioError::InvalidInput("sync_db 锁异常".into()))?;
        let local = local_db.lock().map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let patch = CellPatch {
            kind: item.kind.clone(),
            id: item.id,
            cover_url: op.get("cover_url").and_then(Value::as_str).map(str::to_string),
            release_date: op.get("release_date").and_then(Value::as_str).map(str::to_string),
            studio_or_circle: op.get("studio_or_circle").and_then(Value::as_str).map(str::to_string),
            media_format: op.get("media_format").and_then(Value::as_str).map(str::to_string),
            synopsis: op.get("synopsis").and_then(Value::as_str).map(str::to_string),
            original_title: op.get("original_title").and_then(Value::as_str).map(str::to_string),
            progress_total: op.get("progress_total").and_then(Value::as_i64),
            tags: op.get("tags").and_then(Value::as_array).map(|a| tag_names(a)),
            ..Default::default()
        };
        apply_direct_rehydrate_patch(&mut sync, &local, &patch, &expected_revision, &expected_fields)?;
    }
    if let Ok(local) = local_db.lock() {
        for f in &filled_fields {
            let _ = crate::agent::storage::record_work_field_source(&local, &item.kind, item.id, f, "external");
        }
    }
    let summary = format!("已从 bangumi:{sid} 补全：{}", filled_fields.join("、"));
    Ok(json!({
        "workId": item.id,
        "kind": item.kind,
        "title": item.title,
        "updated": true,
        "externalId": sid,
        "updatedFields": filled_fields,
        "filledFields": filled_fields,
        "skippedMissingRemote": missing_remote,
        "missingRemoteFields": missing_remote,
        "skippedExisting": skipped_existing,
        "coverUpdated": cover_updated,
        "summary": summary,
        "message": summary,
    }))
}


/// 外部 MCP 写操作审批提案（防止 Prompt Injection 绕过用户静默执行外部写动作）
pub fn create_mcp_call_proposal(
    local_conn: &Connection,
    session_id: &str,
    server_name: &str,
    tool_name: &str,
    arguments: &Value,
) -> Result<AgentProposal> {
    let args_preview: String = serde_json::to_string_pretty(arguments)
        .unwrap_or_else(|_| arguments.to_string())
        .chars()
        .take(260)
        .collect();
    let proposal = AgentProposal {
        id: Uuid::new_v4().to_string(),
        session_id: session_id.to_string(),
        kind: "mcp_call".into(),
        title: format!("外部 MCP 写入调用：{server_name} · {tool_name}"),
        summary: "该外部 MCP 工具带有写入或副作用风险，已按安全护栏挂起，经你确认后才会实际调用。".into(),
        status: "pending".into(),
        ops: json!({
            "serverName": server_name,
            "toolName": tool_name,
            "arguments": arguments,
        }),
        diffs: vec![ProposalItemDiff {
            index: 0,
            target_label: format!("MCP 服务「{server_name}」/ 工具 `{tool_name}`"),
            action_label: "外部 MCP 调用".into(),
            before: "尚未发送外部请求".into(),
            after: format!("参数：{args_preview}"),
        }],
        can_revert: false,
        applied_count: None,
        applied_indices: None,
        last_attempt_failed: false,
        last_error: None,
        batch_group_id: None,
        overlap_warnings: Vec::new(),
        created_at: Utc::now().to_rfc3339(),
        applied_at: None,
    };
    save_proposal_record(local_conn, &proposal, None)?;
    Ok(proposal)
}

/// 异步执行经用户确认的 MCP 调用提案（绝不持有 DB 锁期间 await）
pub async fn commit_mcp_call_proposal(
    local_db: &std::sync::Arc<std::sync::Mutex<Connection>>,
    studio_data_dir: &std::path::Path,
    proposal_id: &str,
) -> Result<ProposalApplyResult> {
    const UNCONFIRMED: &str = "外部服务的执行结果还未确认。请先核对外部服务的记录，再决定是否创建新提案。";
    let (mut proposal, server, auth_token, tool_name, arguments) = {
        let local = local_db
            .lock()
            .map_err(|_| StudioError::InvalidInput("local_db 锁异常".into()))?;
        let (mut proposal, _) = load_proposal(&local, proposal_id)?
            .ok_or_else(|| StudioError::InvalidInput("提案不存在".into()))?;
        if proposal.kind != "mcp_call" {
            return Err(StudioError::InvalidInput("该提案不是 MCP 调用类型".into()));
        }
        if proposal.status == "unconfirmed" {
            return Err(StudioError::InvalidInput(UNCONFIRMED.into()));
        }
        if proposal.status != "pending" {
            return Err(StudioError::InvalidInput("该审批已处理，无法重复执行。".into()));
        }
        let server_name = proposal
            .ops
            .get("serverName")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let tool_name = proposal
            .ops
            .get("toolName")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let arguments = proposal
            .ops
            .get("arguments")
            .cloned()
            .unwrap_or_else(|| json!({}));

        let servers = crate::agent::storage::list_mcp_servers(&local, studio_data_dir)?;
        let server = servers
            .into_iter()
            .find(|s| s.name == server_name && s.enabled)
            .ok_or_else(|| {
                StudioError::InvalidInput(format!("MCP 服务「{server_name}」不存在或已禁用"))
            })?;
        let auth_token = crate::agent::storage::load_mcp_auth_token(studio_data_dir, &server.id)
            .ok()
            .flatten();
        if tool_name.trim().is_empty() || server.disabled_tools.contains(&tool_name) {
            return Err(StudioError::InvalidInput("外部工具不存在或已禁用。".into()));
        }
        // 请求发送前持久化占用。即使回包丢失或进程退出，同一审批也不能再次派发。
        let claimed = local.execute(
            "UPDATE agent_proposals SET status = 'unconfirmed', summary = ?2
             WHERE id = ?1 AND kind = 'mcp_call' AND status = 'pending'",
            params![proposal.id, UNCONFIRMED],
        )?;
        if claimed != 1 {
            return Err(StudioError::InvalidInput("该审批已被处理，请重新打开对话核对结果。".into()));
        }
        proposal.status = "unconfirmed".into();
        proposal.summary = UNCONFIRMED.into();
        (proposal, server, auth_token, tool_name, arguments)
    };

    let result = crate::agent::mcp::invoke_mcp_tool(
        &server,
        auth_token.as_deref(),
        &tool_name,
        &arguments,
    )
    .await;
    let (message, applied_count) = if let Ok(raw_output) = result {
        let applied_at = Utc::now().to_rfc3339();
        let recorded = local_db.lock().is_ok_and(|local| {
            local.execute(
                "UPDATE agent_proposals SET status = 'applied', applied_at = ?2, summary = '外部服务已确认成功。'
                 WHERE id = ?1 AND status = 'unconfirmed'",
                params![proposal.id, applied_at],
            ).is_ok()
        });
        if recorded {
            let safe_output = crate::agent::providers::redact_credentials(&raw_output);
            let snippet: String = safe_output.chars().take(240).collect();
            proposal.status = "applied".into();
            proposal.applied_at = Some(applied_at);
            proposal.summary = format!("外部服务已确认成功，返回结果为 {snippet}");
            ("外部工具已执行。".into(), 1)
        } else {
            (UNCONFIRMED.into(), 0)
        }
    } else {
        (UNCONFIRMED.into(), 0)
    };

    Ok(ProposalApplyResult {
        message,
        proposal,
        applied_count,
        applied_indices: if applied_count > 0 { vec![0] } else { vec![] },
        changed_domains: vec![],
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{init_local_db, init_sync_db};
    use tempfile::tempdir;

    #[test]
    fn external_binding_uses_confirmation_and_reports_partial_application() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch("INSERT INTO animes(id,title) VALUES (1,'作品甲'),(2,'作品乙');").unwrap();
        let args = json!({"operations":[
            {"work_id":1,"kind":"anime","title":"作品甲","external_source":"bangumi","external_id":"621073"},
            {"work_id":2,"kind":"anime","status":"搁置"}
        ]});
        let link = |db: &Connection| db.query_row(
            "SELECT externalId FROM animes WHERE id = 1", [], |row| row.get::<_, Option<String>>(0)
        ).unwrap();
        let (proposal, quick) = create_work_mutations_proposal(&sync, &local, "fixture", &args).unwrap();
        assert!(!quick);
        assert!(proposal.diffs[0].after.contains("621073"));
        assert_eq!(link(&sync), None);
        let (repeat, _) = create_work_mutations_proposal(&sync, &local, "fixture", &args).unwrap();
        assert_eq!(repeat.id, proposal.id);
        commit_proposal(&mut sync, &local, &proposal.id, Some(&[0])).unwrap();
        assert_eq!(link(&sync).as_deref(), Some("621073"));
        let state = |session| super::super::storage::read_proposal_statuses(&local, session, Some(&proposal.id)).unwrap();
        assert_eq!(state("fixture")[0]["status"], "partially_applied");
        assert_eq!(state("fixture")[0]["appliedCount"], 1);
        assert!(state("other").is_empty());
        let duplicate = json!({"operations":[{"work_id":2,"kind":"anime","external_source":"bangumi","external_id":"621073"}]});
        assert!(create_work_mutations_proposal(&sync, &local, "fixture", &duplicate).is_err());
        revert_proposal(&mut sync, &local, &proposal.id).unwrap();
        assert_eq!(link(&sync), None);
        assert_eq!(state("fixture")[0]["status"], "reverted");
    }

    #[test]
    fn deletion_rechecks_the_empty_series_and_keeps_works() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO series (id,name) VALUES (1,'空系列');
             INSERT INTO animes (id,title,watchedEpisodes,subjectType) VALUES (1,'继续保留的小说',1,'book');"
        ).unwrap();
        let args = json!({"operations":[{"action":"delete_empty_series","series_id":1}]});
        let proposal = create_series_mutations_proposal(&sync, &local, "fixture", &args).unwrap();
        sync.execute("UPDATE animes SET seriesId = 1 WHERE id = 1", []).unwrap();
        assert!(commit_proposal(&mut sync, &local, &proposal.id, None).is_err());
        assert!(create_series_mutations_proposal(&sync, &local, "fixture", &args).is_err());
        sync.execute("UPDATE animes SET seriesId = NULL WHERE id = 1", []).unwrap();
        commit_proposal(&mut sync, &local, &proposal.id, None).unwrap();
        assert!(list_series_manage(&sync).unwrap().is_empty());
        assert_eq!(sync.query_row("SELECT watchedEpisodes FROM animes WHERE id = 1", [], |row| row.get::<_, i64>(0)).unwrap(), 1);
    }

    #[test]
    fn progress_preview_and_write_agree_on_status_dates_and_limits() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO animes(id,title,status,watchedEpisodes,totalEpisodes,watchStartDate,watchFinishDate)
             VALUES (1,'待完结','在看',11,12,'2026-11-10',NULL),
                    (2,'未知总集数','想看',0,0,NULL,NULL),
                    (3,'已有日期','在看',10,12,'2026-10-10','2026-10-12'),
                    (4,'只改总集数','在看',4,12,NULL,NULL);
             INSERT INTO games(id,syncId,title,status,updatedAt) VALUES (1,'g1','游戏','想玩','2026-11-21');
             INSERT INTO audio_works(id,syncId,title,status,updatedAt,durationSeconds,listenedSeconds)
             VALUES (1,'a1','音声','在听','2026-11-21',100,90);",
        ).unwrap();
        let (proposal, _) = create_work_mutations_proposal(&sync, &local, "fixture", &json!({
            "operations": [
                {"work_id":1,"kind":"anime","progress_primary":12},
                {"work_id":2,"kind":"anime","progress_primary":1},
                {"work_id":3,"kind":"anime","status":"看完"},
                {"work_id":4,"kind":"anime","progress_total":2},
                {"work_id":1,"kind":"game","progress_primary":1},
                {"work_id":1,"kind":"audio","progress_primary":105}
            ]
        })).unwrap();
        let after: Vec<_> = proposal.diffs.iter().map(|diff| diff.after.as_str()).collect();
        assert_eq!(after, [
            "进度:12/12 · 状态:看完 · 完结日期:执行时填写日期",
            "进度:1/未知 · 状态:在看 · 开始日期:执行时填写日期",
            "状态:看完 · 进度:12/12",
            "进度:4/2",
            "进度:1 分钟 · 状态:在玩",
            "进度:100/100 · 状态:已听",
        ]);
        assert_eq!(commit_proposal(&mut sync, &local, &proposal.id, None).unwrap().applied_count, 6);
        let rows: i64 = sync.query_row(
            "SELECT COUNT(*) FROM animes WHERE
             (id=1 AND status='看完' AND watchedEpisodes=12 AND watchStartDate='2026-11-10' AND watchFinishDate IS NOT NULL)
             OR (id=2 AND status='在看' AND watchedEpisodes=1 AND watchStartDate IS NOT NULL AND watchFinishDate IS NULL)
             OR (id=3 AND status='看完' AND watchedEpisodes=12 AND watchFinishDate='2026-10-12')
             OR (id=4 AND status='在看' AND watchedEpisodes=4 AND totalEpisodes=2)",
            [], |row| row.get(0),
        ).unwrap();
        assert_eq!(rows, 4);
        let game: String = sync.query_row("SELECT status FROM games WHERE id=1", [], |row| row.get(0)).unwrap();
        let audio: String = sync.query_row("SELECT status FROM audio_works WHERE id=1", [], |row| row.get(0)).unwrap();
        assert_eq!((game.as_str(), audio.as_str()), ("在玩", "已听"));
        assert_eq!(revert_proposal(&mut sync, &local, &proposal.id).unwrap().applied_count, 6);
        let restored: (String, i64, Option<String>) = sync.query_row(
            "SELECT status,watchedEpisodes,watchFinishDate FROM animes WHERE id=1",
            [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        ).unwrap();
        assert_eq!(restored, ("在看".into(), 11, None));
    }

    #[test]
    fn progress_approval_rejects_legacy_and_changed_date_previews() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute(
            "INSERT INTO animes(id,title,status,watchedEpisodes,totalEpisodes) VALUES (1,'待完结','在看',11,12)",
            [],
        ).unwrap();
        let args = json!({"operations":[{"work_id":1,"kind":"anime","progress_primary":12}]});
        let (old, _) = create_work_mutations_proposal(&sync, &local, "fixture", &args).unwrap();
        local.execute(
            "UPDATE agent_proposals SET ops_json = json_remove(ops_json, '$[0]._progressEffects') WHERE id=?1",
            [&old.id],
        ).unwrap();
        assert!(commit_proposal(&mut sync, &local, &old.id, None).unwrap_err().to_string().contains("预览"));
        let (fresh, _) = create_work_mutations_proposal(&sync, &local, "fixture", &args).unwrap();
        sync.execute("UPDATE animes SET watchFinishDate='2026-11-20' WHERE id=1", []).unwrap();
        assert!(commit_proposal(&mut sync, &local, &fresh.id, None).unwrap_err().to_string().contains("预览"));
        let current: i64 = sync.query_row("SELECT watchedEpisodes FROM animes WHERE id=1", [], |row| row.get(0)).unwrap();
        assert_eq!(current, 11);
    }

    #[test]
    fn work_proposal_checks_all_fields_and_resolves_colliding_ids() {
        let dir = tempdir().unwrap();
        let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO animes(id,title,status) VALUES (1,'动画','在看');
             INSERT INTO games(id,syncId,title,updatedAt,status) VALUES (1,'g1','游戏','2026-11-21','在玩');",
        ).unwrap();
        let create = |operations| create_work_mutations_proposal(
            &sync, &local, "fixture", &json!({"operations":operations}),
        );
        assert!(create(json!([{"work_id":1,"personal_score":8}])).is_err());
        assert!(create(json!([{"work_id":1,"action":"soft_delete"}])).is_err());
        assert!(create(json!([{"work_id":1,"kind":"anime","status":"看完","personal_score":"8.5"}])).is_err());
        assert!(create(json!([{"work_id":1,"kind":"game","status":"看完"}])).is_err());
        assert!(create(json!([{"work_id":1,"kind":"game","tags":["a,b"]}])).is_err());
        assert!(create(json!([{"work_id":1,"kind":"game","tags":["#"]}])).is_err());
        assert!(create(json!([{"work_id":1,"kind":"game","status":"已玩"}])).is_ok());
        assert!(create(json!([{"work_id":1,"kind":"anime","status":"看完","personal_score":8.5}])).is_ok());
    }

    #[test]
    fn work_undo_preserves_historic_links_and_protects_tags_now_in_use() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO animes(id,title,review) VALUES (1,'动画','旧的长评'),(2,'另一部','');
             INSERT INTO tags(id,name,color,blocked,sortOrder) VALUES (1,'#旧标签',123,1,8);
             INSERT INTO anime_tags(animeId,tagId) VALUES (1,1);",
        ).unwrap();
        let (proposal, _) = create_work_mutations_proposal(&sync,&local,"fixture",&json!({
            "operations":[{"work_id":1,"kind":"anime","review":"新的评价","tags":["临时标签","复用标签"]}]
        })).unwrap();
        assert!(proposal.diffs[0].before.contains("旧的长评"));
        assert!(proposal.diffs[0].after.contains("新的评价"));
        commit_proposal(&mut sync, &local, &proposal.id, None).unwrap();
        sync.execute(
            "INSERT INTO anime_tags(animeId,tagId) SELECT 2,id FROM tags WHERE name='复用标签'",
            [],
        ).unwrap();
        revert_proposal(&mut sync, &local, &proposal.id).unwrap();
        assert_eq!(work_tag_links(&sync, 1).unwrap(), vec![(1, "#旧标签".into())]);
        assert_eq!(work_tag_links(&sync, 2).unwrap()[0].1, "复用标签");
        let unused: i64 = sync.query_row("SELECT count(*) FROM tags WHERE name='临时标签'", [], |row| row.get(0)).unwrap();
        assert_eq!(unused, 0);
    }

    #[test]
    fn ai_create_rejects_an_existing_external_binding_without_touching_it() {
        let dir = tempdir().unwrap();
        let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync.execute(
            "INSERT INTO animes(id,title,status,watchedEpisodes,externalSource,externalId)
             VALUES (1,'已收藏','在看',3,'bangumi','123')",
            [],
        ).unwrap();
        let (proposal, _) = create_work_mutations_proposal(&sync,&local,"fixture",&json!({
            "operations":[{"action":"create","kind":"anime","title":"同一来源","status":"看完",
                "external_source":"bangumi","external_id":"123"}]
        })).unwrap();
        assert!(commit_proposal(&mut sync, &local, &proposal.id, None).is_err());
        let current: (String,i64,Option<String>) = sync.query_row(
            "SELECT status,watchedEpisodes,deletedAt FROM animes WHERE id=1",[],
            |row| Ok((row.get(0)?,row.get(1)?,row.get(2)?)),
        ).unwrap();
        assert_eq!(current,("在看".into(),3,None));
        assert_eq!(load_proposal(&local,&proposal.id).unwrap().unwrap().0.status,"pending");
    }

    #[test]
    fn recovery_publishes_only_committed_transitions() {
        let dir = tempdir().unwrap();
        let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        for committed in [false, true] {
            let (proposal, _) = create_work_mutations_proposal(&sync,&local,"fixture",&json!({
                "operations":[{"action":"create","title":"恢复测试"}],
            })).unwrap();
            let transition_id = format!("transition-{committed}");
            local.execute(
                "INSERT INTO agent_proposal_transitions(id,proposal_id,status,rollback_snapshot_json)
                 VALUES (?1,?2,'applied','[]')",
                params![transition_id,proposal.id],
            ).unwrap();
            if committed {
                sync.execute("INSERT INTO agent_proposal_commits(id) VALUES (?1)", [&transition_id]).unwrap();
            }
            recover_proposal_writes(&sync,&local).unwrap();
            recover_proposal_writes(&sync,&local).unwrap();
            assert_eq!(
                load_proposal(&local,&proposal.id).unwrap().unwrap().0.status,
                if committed {"applied"} else {"pending"},
            );
            for (db, table) in [(&sync,"agent_proposal_commits"),(&local,"agent_proposal_transitions")] {
                let rows: i64 = db.query_row(&format!("SELECT count(*) FROM {table}"),[],|row| row.get(0)).unwrap();
                assert_eq!(rows,0);
            }
        }
    }

    #[test]
    fn concurrent_confirmations_only_create_one_item() {
        let dir = tempdir().unwrap();
        let sync_path = dir.path().join("animeow.db");
        let local_path = dir.path().join("desktop_local.db");
        let sync = init_sync_db(&sync_path).unwrap();
        let local = init_local_db(&local_path).unwrap();
        let (proposal, _) = create_work_mutations_proposal(&sync,&local,"fixture",&json!({
            "operations":[{"action":"create","title":"仅创建一次"}],
        })).unwrap();
        let barrier = std::sync::Barrier::new(2);
        let applied = std::thread::scope(|thread| {
            let confirm = || {
                let mut sync = Connection::open(&sync_path).unwrap();
                sync.busy_timeout(std::time::Duration::from_secs(5)).unwrap();
                let local = Connection::open(&local_path).unwrap();
                local.busy_timeout(std::time::Duration::from_secs(5)).unwrap();
                barrier.wait();
                commit_proposal(&mut sync,&local,&proposal.id,None)
            };
            let first = thread.spawn(confirm);
            let second = thread.spawn(confirm);
            [first.join().unwrap(), second.join().unwrap()].iter().filter(|result| result.is_ok()).count()
        });
        assert_eq!(applied,1);
        let inserted: i64 = sync.query_row("SELECT count(*) FROM animes",[],|row| row.get(0)).unwrap();
        assert_eq!(inserted,1);
    }

    #[test]
    fn proposal_preview_shows_unknown_and_updates_progress_total_and_total_episodes() {
        let dir = tempdir().unwrap();
        let mut sync_conn = init_sync_db(&dir.path().join("animeow.db")).unwrap();
        let local_conn = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
        sync_conn
            .execute(
                "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, subjectType)
                 VALUES (101, '测试新番A', '想看', 0, 0, 'anime'),
                        (102, '测试新番B', '在看', 3, 0, 'anime')",
                [],
            )
            .unwrap();

        let args = json!({
            "title": "补全总集数",
            "reason": "核实总集数",
            "operations": [
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 101,
                    "progress_primary": 0,
                    "progress_total": 12
                },
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 102,
                    "total_episodes": 24
                }
            ]
        });

        let (proposal, is_quick) =
            create_work_mutations_proposal(&sync_conn, &local_conn, "sess-1", &args).unwrap();
        assert!(!is_quick);
        assert_eq!(proposal.diffs.len(), 2);
        assert_eq!(proposal.diffs[0].before, "进度:0/未知");
        assert_eq!(proposal.diffs[0].after, "进度:0/12");
        assert_eq!(proposal.diffs[1].before, "进度:3/未知");
        assert_eq!(proposal.diffs[1].after, "进度:3/24");

        let applied = commit_proposal(&mut sync_conn, &local_conn, &proposal.id, None).unwrap();
        assert_eq!(applied.applied_count, 2);
        let t1: i64 = sync_conn
            .query_row("SELECT totalEpisodes FROM animes WHERE id = 101", [], |r| r.get(0))
            .unwrap();
        let t2: i64 = sync_conn
            .query_row("SELECT totalEpisodes FROM animes WHERE id = 102", [], |r| r.get(0))
            .unwrap();
        assert_eq!(t1, 12);
        assert_eq!(t2, 24);
    }
    #[test]
    fn direct_rehydrate_rejects_changes_during_remote_lookup() {
        let dir = tempfile::tempdir().unwrap();
        let mut sync = crate::schema::init_sync_db(&dir.path().join("sync.db")).unwrap();
        let local = crate::schema::init_local_db(&dir.path().join("local.db")).unwrap();
        sync.execute("INSERT INTO animes (id,title) VALUES (1,'作品')", []).unwrap();
        let original = query_curator_items(&sync, &local).unwrap().pop().unwrap();
        let revision = item_revision(&original).unwrap();
        let fields = raw_work_fields(&sync, "anime", 1).unwrap();
        let patch = CellPatch { id: 1, kind: "anime".into(), synopsis: Some("外部简介".into()), ..CellPatch::default() };

        sync.execute("UPDATE animes SET synopsis='用户填的简介' WHERE id=1", []).unwrap();
        assert!(apply_direct_rehydrate_patch(&mut sync, &local, &patch, &revision, &fields).is_err());
        let synopsis = |conn: &Connection| conn.query_row("SELECT synopsis FROM animes WHERE id=1", [], |row| row.get::<_, String>(0)).unwrap();
        assert_eq!(synopsis(&sync), "用户填的简介");

        sync.execute("UPDATE animes SET synopsis=NULL WHERE id=1", []).unwrap();
        apply_direct_rehydrate_patch(&mut sync, &local, &patch, &revision, &fields).unwrap();
        assert_eq!(synopsis(&sync), "外部简介");
    }

}
