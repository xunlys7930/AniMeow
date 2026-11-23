use chrono::DateTime;
use regex::Regex;
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::sync::LazyLock;

pub type Row = Map<String, Value>;

const RATING_GRADES: &[&str] = &["A", "B", "C", "D"];
pub const SERIES_ROLES: &[&str] = &["原作", "改编", "前传", "续作", "番外／衍生"];

static REMINDER_TIME_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$").unwrap());
static VNDB_ID_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^v[1-9]\d{0,8}$").unwrap());
static SUB_MS_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\.(\d+)(?:Z|[+-]\d{2}:\d{2})$").unwrap());

pub struct BinaryEntry<'a> {
    pub relative_path: &'a str,
    pub content: &'a [u8],
}

/// 按《原生备份格式契约》对原始文本字节与排序后的资产增量计算 SHA-256 指纹
pub fn compute_data_fingerprint(
    database_json_text: &str,
    settings_json_text: &str,
    configuration_profiles_json_text: &str,
    covers: &[BinaryEntry<'_>],
    branding_files: &[BinaryEntry<'_>],
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(database_json_text.as_bytes());
    hasher.update([0u8]);
    hasher.update(settings_json_text.as_bytes());
    hasher.update([0u8]);
    hasher.update(b"configuration_profiles.json");
    hasher.update([0u8]);
    hasher.update(configuration_profiles_json_text.as_bytes());

    let mut sorted_covers: Vec<&BinaryEntry<'_>> = covers.iter().collect();
    sorted_covers.sort_by(|a, b| a.relative_path.cmp(b.relative_path));
    for cover in sorted_covers {
        hasher.update([0u8]);
        hasher.update(cover.relative_path.as_bytes());
        hasher.update(cover.content);
    }

    let mut sorted_branding: Vec<&BinaryEntry<'_>> = branding_files.iter().collect();
    sorted_branding.sort_by(|a, b| a.relative_path.cmp(b.relative_path));
    for file in sorted_branding {
        hasher.update([0u8]);
        hasher.update(format!("branding/{}", file.relative_path).as_bytes());
        hasher.update(file.content);
    }

    hex::encode(hasher.finalize())
}

pub fn normalize_subject_type(raw: Option<&str>) -> String {
    match raw.unwrap_or("").trim().to_ascii_lowercase().as_str() {
        "book" | "manga" | "novel" => "book".to_string(),
        _ => "anime".to_string(),
    }
}

pub fn normalize_anime_memo(val: Option<&Value>) -> Value {
    match val.and_then(|v| v.as_str()) {
        Some(s) => {
            let trimmed = s.trim();
            if trimmed.is_empty() {
                Value::Null
            } else {
                let truncated: String = trimmed.chars().take(10_000).collect();
                Value::String(truncated)
            }
        }
        None => Value::Null,
    }
}

#[derive(Clone, Debug)]
struct LinkedMediaItem {
    kind: String,
    work_sync_id: String,
    role: Option<String>,
}

fn parse_series_linked_media(raw: Option<&Value>) -> Vec<LinkedMediaItem> {
    let Some(s) = raw.and_then(|v| v.as_str()) else {
        return Vec::new();
    };
    if s.trim().is_empty() {
        return Vec::new();
    }
    let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(s) else {
        return Vec::new();
    };
    let mut order: Vec<String> = Vec::new();
    let mut by_key: HashMap<String, LinkedMediaItem> = HashMap::new();
    for item in arr {
        let Some(obj) = item.as_object() else { continue };
        let kind = obj
            .get("kind")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_ascii_uppercase();
        if kind != "GAME" && kind != "AUDIO" {
            continue;
        }
        let sync_id = obj
            .get("workSyncId")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_string();
        if sync_id.is_empty() || sync_id.len() > 128 {
            continue;
        }
        let role_raw = obj
            .get("role")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim();
        let role = if SERIES_ROLES.contains(&role_raw) {
            Some(role_raw.to_string())
        } else {
            None
        };
        let key = format!("{kind}:{sync_id}");
        if let Some(existing) = by_key.get_mut(&key) {
            if existing.role.is_none() && role.is_some() {
                existing.role = role;
            }
        } else {
            order.push(key.clone());
            by_key.insert(
                key,
                LinkedMediaItem {
                    kind,
                    work_sync_id: sync_id,
                    role,
                },
            );
        }
    }
    order.into_iter().filter_map(|k| by_key.remove(&k)).collect()
}

fn encode_series_linked_media(items: Vec<LinkedMediaItem>) -> Value {
    let mut order: Vec<String> = Vec::new();
    let mut by_key: HashMap<String, LinkedMediaItem> = HashMap::new();
    for item in items {
        let key = format!("{}:{}", item.kind, item.work_sync_id);
        if let Some(existing) = by_key.get_mut(&key) {
            if existing.role.is_none() && item.role.is_some() {
                existing.role = item.role;
            }
        } else {
            order.push(key.clone());
            by_key.insert(key, item);
        }
    }
    if order.is_empty() {
        return Value::Null;
    }
    let encoded: Vec<Value> = order
        .into_iter()
        .filter_map(|k| by_key.remove(&k))
        .map(|it| {
            let mut m = Map::new();
            m.insert("kind".into(), Value::String(it.kind));
            m.insert("workSyncId".into(), Value::String(it.work_sync_id));
            if let Some(role) = it.role {
                m.insert("role".into(), Value::String(role));
            }
            Value::Object(m)
        })
        .collect();
    serde_json::to_string(&encoded)
        .map(Value::String)
        .unwrap_or(Value::Null)
}

pub fn normalize_series_linked_media_json(
    raw: Option<&Value>,
    game_sync_remap: Option<&HashMap<String, String>>,
) -> Value {
    let items: Vec<LinkedMediaItem> = parse_series_linked_media(raw)
        .into_iter()
        .map(|mut item| {
            if item.kind == "GAME" {
                if let Some(remap) = game_sync_remap {
                    if let Some(mapped) = remap.get(&item.work_sync_id) {
                        if !mapped.trim().is_empty() {
                            item.work_sync_id = mapped.trim().to_string();
                        }
                    }
                }
            }
            item
        })
        .collect();
    encode_series_linked_media(items)
}

pub fn merge_series_linked_media_json(
    local_raw: Option<&Value>,
    incoming_raw: Option<&Value>,
) -> Value {
    let mut items = parse_series_linked_media(local_raw);
    items.extend(parse_series_linked_media(incoming_raw));
    encode_series_linked_media(items)
}

fn merge_anime_source_references(local_raw: Option<&Value>, incoming_raw: Option<&Value>) -> Value {
    fn parse_refs(raw: Option<&Value>) -> Vec<[Option<String>; 5]> {
        let Some(s) = raw.and_then(|v| v.as_str()) else {
            return Vec::new();
        };
        let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(s) else {
            return Vec::new();
        };
        let field = |obj: &Row, k: &str| -> Option<String> {
            obj.get(k)
                .and_then(|v| match v {
                    Value::String(st) => Some(st.trim().to_string()),
                    Value::Number(n) => Some(n.to_string()),
                    _ => None,
                })
                .filter(|st| !st.is_empty())
        };
        let mut out = Vec::new();
        for item in arr {
            let Some(obj) = item.as_object() else { continue };
            out.push([
                Some(field(obj, "title").unwrap_or_default()),
                field(obj, "originalTitle"),
                field(obj, "source"),
                field(obj, "externalId"),
                field(obj, "url"),
            ]);
        }
        out
    }

    let mut all = parse_refs(local_raw);
    all.extend(parse_refs(incoming_raw));

    let mut seen = HashSet::new();
    let mut distinct = Vec::new();
    for r in all {
        let key = serde_json::to_string(&r).unwrap_or_default();
        if seen.insert(key) {
            distinct.push(r);
        }
    }
    if distinct.is_empty() {
        return Value::Null;
    }
    let arr: Vec<Value> = distinct
        .into_iter()
        .map(|r| {
            let mut m = Map::new();
            m.insert("title".into(), Value::String(r[0].clone().unwrap_or_default()));
            if let Some(v) = &r[1] {
                m.insert("originalTitle".into(), Value::String(v.clone()));
            }
            if let Some(v) = &r[2] {
                m.insert("source".into(), Value::String(v.clone()));
            }
            if let Some(v) = &r[3] {
                m.insert("externalId".into(), Value::String(v.clone()));
            }
            if let Some(v) = &r[4] {
                m.insert("url".into(), Value::String(v.clone()));
            }
            Value::Object(m)
        })
        .collect();
    serde_json::to_string(&arr)
        .map(Value::String)
        .unwrap_or(Value::Null)
}

/// 执行与 Android / TypeScript 一致的 `MERGE_KEEP_LOCAL` 22 表快照合并
pub fn merge_native_snapshots(
    local: &Row,
    remote: &Row,
    include_core: bool,
    include_analysis: bool,
) -> Row {
    let mut result = local.clone();
    if include_core {
        merge_core(&mut result, remote);
    }
    if include_analysis {
        merge_analysis(&mut result, remote);
    }
    result
}

fn text(row: &Row, field: &str) -> Option<String> {
    let s = row.get(field)?.as_str()?.trim();
    if s.is_empty() {
        None
    } else {
        Some(s.to_string())
    }
}

fn normalized_text(row: &Row, field: &str) -> Option<String> {
    let t = text(row, field)?;
    Some(
        t.to_lowercase()
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" "),
    )
}

fn nullable_long(row: &Row, field: &str) -> Option<i64> {
    match row.get(field)? {
        Value::Null => None,
        Value::Bool(false) => None,
        Value::Bool(true) => Some(1),
        Value::Number(n) => n.as_i64(),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

fn positive_long(row: &Row, field: &str) -> Option<i64> {
    nullable_long(row, field).filter(|&v| v > 0)
}

fn nullable_int(row: &Row, field: &str) -> Option<i64> {
    nullable_long(row, field).filter(|&v| (i32::MIN as i64..=i32::MAX as i64).contains(&v))
}

fn non_negative_int(row: &Row, field: &str) -> i64 {
    nullable_int(row, field).filter(|&v| v >= 0).unwrap_or(0)
}

fn long_value(row: &Row, field: &str) -> i64 {
    nullable_long(row, field).unwrap_or(0)
}

fn has_meaningful(row: &Row, field: &str) -> bool {
    match row.get(field) {
        None | Some(Value::Null) => false,
        Some(Value::String(s)) => !s.trim().is_empty(),
        _ => true,
    }
}

fn fill_missing_from(local: &mut Row, incoming: &Row, fields: &[&str]) {
    for &field in fields {
        if !has_meaningful(local, field) && has_meaningful(incoming, field) {
            if let Some(v) = incoming.get(field) {
                local.insert(field.into(), v.clone());
            }
        }
    }
}

fn put_max_non_neg_int(local: &mut Row, field: &str, incoming: &Row) {
    let m = non_negative_int(local, field).max(non_negative_int(incoming, field));
    local.insert(field.into(), Value::from(m));
}

fn put_min_non_neg_int(local: &mut Row, field: &str, incoming: &Row) {
    let a = nullable_int(local, field).filter(|&v| v >= 0);
    let b = nullable_int(incoming, field).filter(|&v| v >= 0);
    let m = match (a, b) {
        (Some(x), Some(y)) => x.min(y),
        (Some(x), None) => x,
        (None, Some(y)) => y,
        (None, None) => 0,
    };
    local.insert(field.into(), Value::from(m));
}

fn put_earliest(local: &mut Row, field: &str, incoming: &Row) {
    match (text(local, field), text(incoming, field)) {
        (None, Some(b)) => {
            local.insert(field.into(), Value::String(b));
        }
        (Some(a), Some(b)) if b < a => {
            local.insert(field.into(), Value::String(b));
        }
        _ => {}
    }
}

fn put_latest(local: &mut Row, field: &str, incoming: &Row) {
    match (text(local, field), text(incoming, field)) {
        (None, Some(b)) => {
            local.insert(field.into(), Value::String(b));
        }
        (Some(a), Some(b)) if b > a => {
            local.insert(field.into(), Value::String(b));
        }
        _ => {}
    }
}

fn remap_required(row: &mut Row, field: &str, mapping: &HashMap<i64, i64>) -> bool {
    let Some(cur) = nullable_long(row, field) else {
        return false;
    };
    let Some(&mapped) = mapping.get(&cur) else {
        return false;
    };
    row.insert(field.into(), Value::from(mapped));
    true
}

fn remap_nullable(row: &mut Row, field: &str, mapping: &HashMap<i64, i64>) {
    if !row.contains_key(field) || row.get(field) == Some(&Value::Null) {
        return;
    }
    let mapped = nullable_long(row, field).and_then(|c| mapping.get(&c).copied());
    row.insert(
        field.into(),
        mapped.map(Value::from).unwrap_or(Value::Null),
    );
}

fn normalize_required_int(
    row: &mut Row,
    field: &str,
    min: i64,
    max: i64,
    default_val: Option<i64>,
) -> bool {
    if let Some(v) = nullable_long(row, field) {
        if (min..=max).contains(&v) {
            row.insert(field.into(), Value::from(v));
            return true;
        }
    }
    if let Some(d) = default_val {
        row.insert(field.into(), Value::from(d));
        return true;
    }
    false
}

fn normalize_optional_int(row: &mut Row, field: &str, min: i64, max: i64) {
    let val = nullable_int(row, field).filter(|v| (min..=max).contains(v));
    row.insert(field.into(), val.map(Value::from).unwrap_or(Value::Null));
}

fn allocate_id(preferred: i64, used: &HashSet<i64>) -> i64 {
    if preferred > 0 && !used.contains(&preferred) {
        return preferred;
    }
    if used.is_empty() {
        return 1;
    }
    used.iter().copied().max().unwrap_or(0) + 1
}

fn parse_iso_millis(s: &str) -> Option<(i64, String)> {
    let dt = DateTime::parse_from_rfc3339(s).ok()?;
    let sub = SUB_MS_RE
        .captures(s)
        .and_then(|c| c.get(1))
        .map(|m| {
            let mut st = m.as_str().to_string();
            while st.len() < 9 {
                st.push('0');
            }
            st[3..9].to_string()
        })
        .unwrap_or_else(|| "000000".into());
    Some((dt.timestamp_millis(), sub))
}

fn incoming_is_newer(local: &Row, incoming: &Row) -> bool {
    let inc_s = incoming.get("updatedAt").and_then(|v| v.as_str()).unwrap_or("");
    let loc_s = local.get("updatedAt").and_then(|v| v.as_str()).unwrap_or("");
    let Some((inc_ms, inc_sub)) = parse_iso_millis(inc_s) else {
        return false;
    };
    let Some((loc_ms, loc_sub)) = parse_iso_millis(loc_s) else {
        return true;
    };
    inc_ms > loc_ms || (inc_ms == loc_ms && inc_sub > loc_sub)
}

fn normalize_anime_values(row: &mut Row) {
    let role_ok = row
        .get("seriesRole")
        .and_then(|v| v.as_str())
        .is_some_and(|r| SERIES_ROLES.contains(&r));
    let has_series = row.get("seriesId").is_some_and(|v| !v.is_null());
    if !role_ok || !has_series {
        row.insert("seriesRole".into(), Value::Null);
    }
    let memo = normalize_anime_memo(row.get("memo"));
    row.insert("memo".into(), memo);
    normalize_optional_int(row, "bookReadVolumes", 0, 10_000);
    normalize_optional_int(row, "bookReadChapters", 0, 10_000);
    let subj = normalize_subject_type(row.get("subjectType").and_then(|v| v.as_str()));
    row.insert("subjectType".into(), Value::String(subj));
    for f in ["watchedEpisodes", "totalEpisodes", "tvEpisodes", "spEpisodes"] {
        normalize_required_int(row, f, 0, i32::MAX as i64, Some(0));
    }

    // rating / ratingGrade
    let grade = text(row, "ratingGrade")
        .map(|g| g.to_ascii_uppercase())
        .filter(|g| RATING_GRADES.contains(&g.as_str()));
    let score = nullable_int(row, "rating").filter(|&s| (1..=100).contains(&s));
    if let Some(g) = grade {
        row.insert("ratingGrade".into(), Value::String(g));
        row.insert("rating".into(), Value::Null);
    } else if let Some(s) = score {
        row.insert("rating".into(), Value::from(s));
        row.insert("ratingGrade".into(), Value::Null);
    } else {
        row.insert("rating".into(), Value::Null);
        row.insert("ratingGrade".into(), Value::Null);
    }

    // reminder
    let rem_day = nullable_int(row, "reminderDay").filter(|&d| (1..=7).contains(&d));
    let rem_time = text(row, "reminderTime").filter(|t| REMINDER_TIME_RE.is_match(t));
    if let (Some(d), Some(t)) = (rem_day, rem_time) {
        row.insert("reminderDay".into(), Value::from(d));
        row.insert("reminderTime".into(), Value::String(t));
    } else {
        row.insert("reminderDay".into(), Value::Null);
        row.insert("reminderTime".into(), Value::Null);
    }

    if text(row, "status").as_deref() == Some("看完") {
        let total = non_negative_int(row, "totalEpisodes");
        if total > 0 {
            row.insert("watchedEpisodes".into(), Value::from(total));
        }
    }
}

fn anime_keys(row: &Row) -> Vec<String> {
    let subj = normalize_subject_type(row.get("subjectType").and_then(|v| v.as_str()));
    let year = text(row, "airDate").and_then(|d| {
        let y = d.get(0..4)?;
        y.chars().all(|c| c.is_ascii_digit()).then(|| y.to_string())
    });
    let mut keys = Vec::new();
    if let (Some(src), Some(id)) = (text(row, "externalSource"), text(row, "externalId")) {
        keys.push(format!("external:{}:{}", src.to_lowercase(), id.to_lowercase()));
    }
    if let Some(title) = normalized_text(row, "title") {
        if let Some(y) = &year {
            keys.push(format!("title:{subj}:{y}:{title}"));
        }
        keys.push(format!("title:{subj}:*:{title}"));
    }
    if let Some(orig) = normalized_text(row, "originalTitle") {
        if let Some(y) = &year {
            keys.push(format!("original:{subj}:{y}:{orig}"));
        }
        keys.push(format!("original:{subj}:*:{orig}"));
    }
    keys
}

fn can_match_anime(local: &Row, incoming: &Row) -> bool {
    if let (Some(ls), Some(li), Some(is), Some(ii)) = (
        text(local, "externalSource"),
        text(local, "externalId"),
        text(incoming, "externalSource"),
        text(incoming, "externalId"),
    ) {
        return ls.eq_ignore_ascii_case(&is) && li.eq_ignore_ascii_case(&ii);
    }
    let lt = normalize_subject_type(local.get("subjectType").and_then(|v| v.as_str()));
    let it = normalize_subject_type(incoming.get("subjectType").and_then(|v| v.as_str()));
    if lt != it {
        return false;
    }
    let year_of = |r: &Row| {
        text(r, "airDate").and_then(|d| {
            let y = d.get(0..4)?;
            y.chars().all(|c| c.is_ascii_digit()).then(|| y.to_string())
        })
    };
    match (year_of(local), year_of(incoming)) {
        (Some(ly), Some(iy)) => ly == iy,
        _ => true,
    }
}

fn merge_anime(local: &mut Row, incoming: &mut Row) {
    normalize_anime_values(local);
    normalize_anime_values(incoming);

    fill_missing_from(
        local,
        incoming,
        &[
            "coverUrl",
            "funRatingTier",
            "review",
            "memo",
            "seriesId",
            "airDate",
            "studio",
            "voiceActors",
            "coverOrientation",
            "watchStartDate",
            "watchFinishDate",
            "originalTitle",
            "synopsis",
            "mediaFormat",
            "sourceRatingJson",
        ],
    );

    let local_has_rating = text(local, "ratingGrade").is_some()
        || nullable_int(local, "rating").is_some_and(|s| (1..=100).contains(&s));
    let inc_has_rating = text(incoming, "ratingGrade").is_some()
        || nullable_int(incoming, "rating").is_some_and(|s| (1..=100).contains(&s));
    if !local_has_rating && inc_has_rating {
        local.insert(
            "rating".into(),
            incoming.get("rating").cloned().unwrap_or(Value::Null),
        );
        local.insert(
            "ratingGrade".into(),
            incoming.get("ratingGrade").cloned().unwrap_or(Value::Null),
        );
    }

    if nullable_int(local, "reminderDay").is_none() && nullable_int(incoming, "reminderDay").is_some()
    {
        local.insert(
            "reminderDay".into(),
            incoming.get("reminderDay").cloned().unwrap_or(Value::Null),
        );
        local.insert(
            "reminderTime".into(),
            incoming.get("reminderTime").cloned().unwrap_or(Value::Null),
        );
    }

    if nullable_int(local, "broadcastDay").filter(|&d| (1..=7).contains(&d)).is_none() {
        if let Some(bd) = nullable_int(incoming, "broadcastDay").filter(|&d| (1..=7).contains(&d)) {
            local.insert("broadcastDay".into(), Value::from(bd));
            local.insert(
                "broadcastTime".into(),
                incoming.get("broadcastTime").cloned().unwrap_or(Value::Null),
            );
        }
    }

    if text(local, "externalSource").is_none() || text(local, "externalId").is_none() {
        if let (Some(src), Some(id)) = (text(incoming, "externalSource"), text(incoming, "externalId"))
        {
            local.insert("externalSource".into(), Value::String(src));
            local.insert("externalId".into(), Value::String(id));
            local.insert(
                "externalUrl".into(),
                incoming.get("externalUrl").cloned().unwrap_or(Value::Null),
            );
        }
    } else if !has_meaningful(local, "externalUrl") && has_meaningful(incoming, "externalUrl") {
        local.insert(
            "externalUrl".into(),
            incoming.get("externalUrl").cloned().unwrap_or(Value::Null),
        );
    }

    let merged_sources =
        merge_anime_source_references(local.get("mergedSourcesJson"), incoming.get("mergedSourcesJson"));
    local.insert("mergedSourcesJson".into(), merged_sources);

    for f in ["watchedEpisodes", "totalEpisodes", "tvEpisodes", "spEpisodes"] {
        put_max_non_neg_int(local, f, incoming);
    }

    let local_status = text(local, "status");
    if local_status.is_none()
        || (matches!(local_status.as_deref(), Some("想看" | "未看"))
            && non_negative_int(incoming, "watchedEpisodes") > 0)
    {
        if let Some(st) = text(incoming, "status") {
            local.insert("status".into(), Value::String(st));
        }
    }

    put_earliest(local, "createdAt", incoming);

    if !has_meaningful(local, "seriesRole") && local.get("seriesId") == incoming.get("seriesId") {
        if let Some(role) = text(incoming, "seriesRole").filter(|r| SERIES_ROLES.contains(&r.as_str()))
        {
            local.insert("seriesRole".into(), Value::String(role));
        }
    }

    for f in ["bookReadVolumes", "bookReadChapters"] {
        if local.get(f).is_none_or(|v| v.is_null()) {
            if let Some(iv) = nullable_int(incoming, f).filter(|&x| (0..=10_000).contains(&x)) {
                local.insert(f.into(), Value::from(iv));
            }
        }
    }

    put_earliest(local, "watchStartDate", incoming);
    put_latest(local, "watchFinishDate", incoming);

    match (text(local, "deletedAt"), text(incoming, "deletedAt")) {
        (Some(ld), Some(id)) => {
            local.insert("deletedAt".into(), Value::String(ld.max(id)));
        }
        _ => {
            local.insert("deletedAt".into(), Value::Null);
        }
    }

    if text(local, "status").as_deref() == Some("看完") {
        let total = non_negative_int(local, "totalEpisodes");
        if total > 0 {
            local.insert("watchedEpisodes".into(), Value::from(total));
        }
    }
}

fn take_rows(root: &Row, table: &str) -> Vec<Row> {
    root.get(table)
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_object().cloned())
                .collect()
        })
        .unwrap_or_default()
}

fn put_rows(root: &mut Row, table: &str, rows: Vec<Row>) {
    root.insert(
        table.into(),
        Value::Array(rows.into_iter().map(Value::Object).collect()),
    );
}

fn merge_simple_entity_table<FKeys, FPrep, FCan, FMerge>(
    result: &mut Row,
    remote: &Row,
    table: &str,
    keys_fn: FKeys,
    mut prep_fn: FPrep,
    can_match_fn: FCan,
    mut merge_fn: FMerge,
) -> HashMap<i64, i64>
where
    FKeys: Fn(&Row) -> Vec<String>,
    FPrep: FnMut(&mut Row) -> bool,
    FCan: Fn(&Row, &Row) -> bool,
    FMerge: FnMut(&mut Row, &mut Row),
{
    let mut local_rows = take_rows(result, table);
    let remote_rows = take_rows(remote, table);
    let mut used_ids: HashSet<i64> = local_rows
        .iter()
        .filter_map(|r| positive_long(r, "id"))
        .collect();
    let mut id_map = HashMap::new();

    for mut incoming in remote_rows {
        let Some(remote_id) = nullable_long(&incoming, "id") else {
            continue;
        };
        if !prep_fn(&mut incoming) {
            continue;
        }
        let inc_keys: HashSet<String> = keys_fn(&incoming).into_iter().collect();
        let matched_idx = local_rows.iter().position(|cand| {
            let cand_keys: HashSet<String> = keys_fn(cand).into_iter().collect();
            !inc_keys.is_disjoint(&cand_keys) && can_match_fn(cand, &incoming)
        });

        if let Some(idx) = matched_idx {
            let target_id = long_value(&local_rows[idx], "id");
            id_map.insert(remote_id, target_id);
            merge_fn(&mut local_rows[idx], &mut incoming);
        } else {
            let target_id = allocate_id(remote_id, &used_ids);
            incoming.insert("id".into(), Value::from(target_id));
            used_ids.insert(target_id);
            id_map.insert(remote_id, target_id);
            local_rows.push(incoming);
        }
    }

    put_rows(result, table, local_rows);
    id_map
}

fn merge_composite_table<FPrep, FKey, FMerge>(
    result: &mut Row,
    remote: &Row,
    table: &str,
    mut prep_fn: FPrep,
    key_fn: FKey,
    mut merge_fn: FMerge,
) where
    FPrep: FnMut(&mut Row) -> bool,
    FKey: Fn(&Row) -> String,
    FMerge: FnMut(&mut Row, &Row),
{
    let mut local_rows = take_rows(result, table);
    let remote_rows = take_rows(remote, table);
    let mut index_by_key: HashMap<String, usize> = local_rows
        .iter()
        .enumerate()
        .map(|(i, r)| (key_fn(r), i))
        .collect();

    for mut incoming in remote_rows {
        if !prep_fn(&mut incoming) {
            continue;
        }
        let k = key_fn(&incoming);
        if let Some(&idx) = index_by_key.get(&k) {
            merge_fn(&mut local_rows[idx], &incoming);
        } else {
            let idx = local_rows.len();
            local_rows.push(incoming);
            index_by_key.insert(k, idx);
        }
    }
    put_rows(result, table, local_rows);
}

fn merge_id_entity_table<FPrep, FKey, FMerge>(
    result: &mut Row,
    remote: &Row,
    table: &str,
    mut prep_fn: FPrep,
    key_fn: FKey,
    mut merge_fn: FMerge,
) where
    FPrep: FnMut(&mut Row) -> bool,
    FKey: Fn(&Row) -> String,
    FMerge: FnMut(&mut Row, &Row),
{
    let mut local_rows = take_rows(result, table);
    let remote_rows = take_rows(remote, table);
    let mut used_ids: HashSet<i64> = local_rows
        .iter()
        .filter_map(|r| positive_long(r, "id"))
        .collect();
    let mut index_by_key: HashMap<String, usize> = local_rows
        .iter()
        .enumerate()
        .map(|(i, r)| (key_fn(r), i))
        .collect();

    for mut incoming in remote_rows {
        if !prep_fn(&mut incoming) {
            continue;
        }
        let k = key_fn(&incoming);
        if let Some(&idx) = index_by_key.get(&k) {
            merge_fn(&mut local_rows[idx], &incoming);
        } else {
            let pref = nullable_long(&incoming, "id").unwrap_or(0);
            let target_id = allocate_id(pref, &used_ids);
            incoming.insert("id".into(), Value::from(target_id));
            used_ids.insert(target_id);
            let idx = local_rows.len();
            local_rows.push(incoming);
            index_by_key.insert(k, idx);
        }
    }
    put_rows(result, table, local_rows);
}

fn merge_core(result: &mut Row, remote: &Row) {
    // 1. audio_works & games
    merge_simple_entity_table(
        result,
        remote,
        "audio_works",
        |r| normalized_text(r, "syncId").map(|s| vec![format!("sync:{s}")]).unwrap_or_default(),
        |_| true,
        |_, _| true,
        |local, incoming| {
            if incoming_is_newer(local, incoming) {
                for (k, v) in incoming.iter() {
                    if k != "id" && k != "syncId" {
                        local.insert(k.clone(), v.clone());
                    }
                }
            }
        },
    );

    let game_map = merge_simple_entity_table(
        result,
        remote,
        "games",
        |r| {
            let mut ks = Vec::new();
            if let Some(s) = normalized_text(r, "syncId") {
                ks.push(format!("sync:{s}"));
            }
            if let Some(b) = positive_long(r, "bangumiId") {
                ks.push(format!("bgm:{b}"));
            }
            if let Some(vn) = normalized_text(r, "vndbId").filter(|v| VNDB_ID_RE.is_match(v)) {
                ks.push(format!("vndb:{vn}"));
            }
            ks
        },
        |_| true,
        |_, _| true,
        |local, incoming| {
            if incoming_is_newer(local, incoming) {
                for (k, v) in incoming.iter() {
                    if k != "id" && k != "syncId" {
                        local.insert(k.clone(), v.clone());
                    }
                }
            }
        },
    );

    let games_by_id: HashMap<i64, String> = take_rows(result, "games")
        .into_iter()
        .filter_map(|r| Some((long_value(&r, "id"), text(&r, "syncId")?)))
        .collect();
    let mut game_sync_map: HashMap<String, String> = HashMap::new();
    for rg in take_rows(remote, "games") {
        if let (Some(orig_sync), Some(&mapped_id)) =
            (text(&rg, "syncId"), game_map.get(&long_value(&rg, "id")))
        {
            if let Some(canon_sync) = games_by_id.get(&mapped_id) {
                game_sync_map.insert(orig_sync, canon_sync.clone());
            }
        }
    }

    // 2. media_series & media_series_members
    let media_series_map = merge_simple_entity_table(
        result,
        remote,
        "media_series",
        |r| text(r, "syncId").map(|s| vec![format!("sync:{s}")]).unwrap_or_default(),
        |r| matches!(text(r, "kind").as_deref(), Some("GAME" | "AUDIO")) && normalized_text(r, "syncId").is_some(),
        |l, i| text(l, "kind") == text(i, "kind"),
        |local, incoming| {
            if incoming_is_newer(local, incoming) {
                for (k, v) in incoming.iter() {
                    if k != "id" && k != "syncId" {
                        local.insert(k.clone(), v.clone());
                    }
                }
            }
        },
    );

    let media_series_kinds: HashMap<i64, String> = take_rows(result, "media_series")
        .into_iter()
        .filter_map(|r| Some((long_value(&r, "id"), text(&r, "kind")?)))
        .collect();

    merge_simple_entity_table(
        result,
        remote,
        "media_series_members",
        |r| {
            vec![format!(
                "{}:{}",
                text(r, "kind").unwrap_or_default(),
                text(r, "workSyncId").unwrap_or_default()
            )]
        },
        |r| {
            if text(r, "kind").as_deref() == Some("GAME") {
                if let Some(ws) = text(r, "workSyncId") {
                    if let Some(canon) = game_sync_map.get(&ws) {
                        r.insert("workSyncId".into(), Value::String(canon.clone()));
                    }
                }
            }
            let mapped = positive_long(r, "seriesId").and_then(|sid| media_series_map.get(&sid).copied());
            let valid_series = r.get("seriesId").is_none_or(|v| v.is_null())
                || mapped.is_some_and(|mid| media_series_kinds.get(&mid) == text(r, "kind").as_ref());
            if valid_series {
                r.insert("seriesId".into(), mapped.map(Value::from).unwrap_or(Value::Null));
            }
            matches!(text(r, "kind").as_deref(), Some("GAME" | "AUDIO"))
                && normalized_text(r, "workSyncId").is_some()
                && valid_series
                && normalize_required_int(r, "sortOrder", 0, i32::MAX as i64, Some(0))
        },
        |_, _| true,
        |local, incoming| {
            if incoming_is_newer(local, incoming) {
                for (k, v) in incoming.iter() {
                    if k != "id" && k != "syncId" {
                        local.insert(k.clone(), v.clone());
                    }
                }
            }
        },
    );

    // 3. watch_statuses, series, tags, characters, character_tags, character_groups
    merge_simple_entity_table(
        result,
        remote,
        "watch_statuses",
        |r| normalized_text(r, "name").map(|n| vec![format!("name:{n}")]).unwrap_or_default(),
        |r| normalize_required_int(r, "sortOrder", 0, i32::MAX as i64, Some(0)),
        |_, _| true,
        |local, incoming| {
            fill_missing_from(local, incoming, &["color"]);
            put_min_non_neg_int(local, "sortOrder", incoming);
        },
    );

    let series_map = merge_simple_entity_table(
        result,
        remote,
        "series",
        |r| normalized_text(r, "name").map(|n| vec![format!("name:{n}")]).unwrap_or_default(),
        |r| {
            let norm = normalize_series_linked_media_json(r.get("linkedMediaJson"), Some(&game_sync_map));
            r.insert("linkedMediaJson".into(), norm);
            true
        },
        |_, _| true,
        |local, incoming| {
            fill_missing_from(local, incoming, &["description", "customCoverUrl"]);
            put_earliest(local, "createdAt", incoming);
            let merged_links =
                merge_series_linked_media_json(local.get("linkedMediaJson"), incoming.get("linkedMediaJson"));
            local.insert("linkedMediaJson".into(), merged_links);
        },
    );

    let tag_map = merge_simple_entity_table(
        result,
        remote,
        "tags",
        |r| normalized_text(r, "name").map(|n| vec![format!("name:{n}")]).unwrap_or_default(),
        |_| true,
        |_, _| true,
        |local, incoming| fill_missing_from(local, incoming, &["color", "blocked"]),
    );

    let character_map = merge_simple_entity_table(
        result,
        remote,
        "characters",
        |r| {
            let mut ks = Vec::new();
            if let Some(b) = positive_long(r, "bgmId") {
                ks.push(format!("bgm:{b}"));
            }
            if let Some(a) = positive_long(r, "anilistId") {
                ks.push(format!("anilist:{a}"));
            }
            if let Some(nc) = normalized_text(r, "nameCn") {
                ks.push(format!("name:{nc}"));
            }
            if let Some(n) = normalized_text(r, "name") {
                ks.push(format!("name:{n}"));
            }
            ks
        },
        |r| {
            let bgm = positive_long(r, "bgmId").map(Value::from).unwrap_or(Value::Null);
            r.insert("bgmId".into(), bgm);
            normalize_optional_int(r, "birthYear", 1, 9999);
            normalize_optional_int(r, "birthMonth", 1, 12);
            normalize_optional_int(r, "birthDay", 1, 31);
            normalize_optional_int(r, "rating", 1, 10);
            true
        },
        |l, i| {
            let bgm_ok = match (positive_long(l, "bgmId"), positive_long(i, "bgmId")) {
                (Some(a), Some(b)) => a == b,
                _ => true,
            };
            let ani_ok = match (positive_long(l, "anilistId"), positive_long(i, "anilistId")) {
                (Some(a), Some(b)) => a == b,
                _ => true,
            };
            bgm_ok && ani_ok
        },
        |local, incoming| {
            fill_missing_from(
                local,
                incoming,
                &[
                    "bgmId",
                    "anilistId",
                    "nameCn",
                    "imageUrl",
                    "imageDisplayMode",
                    "summary",
                    "gender",
                    "birthYear",
                    "birthMonth",
                    "birthDay",
                    "bloodType",
                    "infoboxJson",
                    "rating",
                    "review",
                ],
            );
            put_latest(local, "updatedAt", incoming);
        },
    );

    let char_tag_map = merge_simple_entity_table(
        result,
        remote,
        "character_tags",
        |r| normalized_text(r, "name").map(|n| vec![format!("name:{n}")]).unwrap_or_default(),
        |_| true,
        |_, _| true,
        |local, incoming| {
            fill_missing_from(local, incoming, &["color"]);
            put_earliest(local, "createdAt", incoming);
        },
    );

    let group_map = merge_simple_entity_table(
        result,
        remote,
        "character_groups",
        |r| {
            let mut ks = Vec::new();
            if let Some(c) = normalized_text(r, "communityId") {
                ks.push(format!("community:{c}"));
            }
            if let Some(s) = normalized_text(r, "shareCode") {
                ks.push(format!("share:{s}"));
            }
            if let Some(n) = normalized_text(r, "name") {
                ks.push(format!("name:{n}"));
            }
            ks
        },
        |_| true,
        |l, i| {
            if let (Some(lc), Some(ic)) = (normalized_text(l, "communityId"), normalized_text(i, "communityId")) {
                return lc == ic;
            }
            match (normalized_text(l, "shareCode"), normalized_text(i, "shareCode")) {
                (Some(ls), Some(is)) => ls == is,
                _ => true,
            }
        },
        |local, incoming| {
            fill_missing_from(
                local,
                incoming,
                &["description", "coverUrl", "communityId", "shareCode", "source", "isPublic", "extraJson", "createdAt"],
            );
            put_latest(local, "updatedAt", incoming);
        },
    );

    // 4. animes
    let anime_map = merge_simple_entity_table(
        result,
        remote,
        "animes",
        anime_keys,
        |r| {
            normalize_anime_values(r);
            remap_nullable(r, "seriesId", &series_map);
            true
        },
        can_match_anime,
        merge_anime,
    );

    // 5. child & relation tables
    merge_id_entity_table(
        result,
        remote,
        "watch_records",
        |r| normalize_required_int(r, "episode", 0, i32::MAX as i64, None) && remap_required(r, "animeId", &anime_map),
        |r| format!("{}|{}|{}", long_value(r, "animeId"), non_negative_int(r, "episode"), text(r, "recordDate").unwrap_or_default()),
        |local, incoming| fill_missing_from(local, incoming, &["status", "recordDate"]),
    );

    merge_composite_table(
        result,
        remote,
        "anime_tags",
        |r| remap_required(r, "animeId", &anime_map) && remap_required(r, "tagId", &tag_map),
        |r| format!("{}|{}", long_value(r, "animeId"), long_value(r, "tagId")),
        |_, _| {},
    );

    merge_composite_table(
        result,
        remote,
        "anime_characters",
        |r| {
            normalize_required_int(r, "sortOrder", 0, i32::MAX as i64, Some(0))
                && remap_required(r, "animeId", &anime_map)
                && remap_required(r, "characterId", &character_map)
        },
        |r| format!("{}|{}", long_value(r, "animeId"), long_value(r, "characterId")),
        |local, incoming| {
            fill_missing_from(local, incoming, &["roleName", "voiceActor"]);
            put_min_non_neg_int(local, "sortOrder", incoming);
        },
    );

    merge_id_entity_table(
        result,
        remote,
        "character_relations",
        |r| {
            normalize_required_int(r, "strength", 1, 5, Some(3));
            if !remap_required(r, "sourceCharacterId", &character_map)
                || !remap_required(r, "targetCharacterId", &character_map)
            {
                return false;
            }
            let first = long_value(r, "sourceCharacterId");
            let second = long_value(r, "targetCharacterId");
            if first == second {
                return false;
            }
            if first > second {
                let sr = r.get("sourceRole").cloned().unwrap_or(Value::Null);
                let tr = r.get("targetRole").cloned().unwrap_or(Value::Null);
                r.insert("sourceRole".into(), tr);
                r.insert("targetRole".into(), sr);
            }
            r.insert("sourceCharacterId".into(), Value::from(first.min(second)));
            r.insert("targetCharacterId".into(), Value::from(first.max(second)));
            true
        },
        |r| format!("{}|{}", long_value(r, "sourceCharacterId"), long_value(r, "targetCharacterId")),
        |local, incoming| {
            for f in ["sourceRole", "targetRole"] {
                if local.get(f).is_none_or(|v| v.is_null()) && incoming.get(f).is_some_and(|v| !v.is_null()) {
                    local.insert(f.into(), incoming.get(f).cloned().unwrap_or(Value::Null));
                }
            }
            fill_missing_from(local, incoming, &["relationType", "note", "strength", "createdAt"]);
            put_latest(local, "updatedAt", incoming);
        },
    );

    merge_composite_table(
        result,
        remote,
        "character_tag_links",
        |r| remap_required(r, "characterId", &character_map) && remap_required(r, "tagId", &char_tag_map),
        |r| format!("{}|{}", long_value(r, "characterId"), long_value(r, "tagId")),
        |_, _| {},
    );

    merge_composite_table(
        result,
        remote,
        "character_group_characters",
        |r| {
            normalize_required_int(r, "sortOrder", 0, i32::MAX as i64, Some(0))
                && remap_required(r, "groupId", &group_map)
                && remap_required(r, "characterId", &character_map)
        },
        |r| format!("{}|{}", long_value(r, "groupId"), long_value(r, "characterId")),
        |local, incoming| {
            fill_missing_from(local, incoming, &["roleName"]);
            put_min_non_neg_int(local, "sortOrder", incoming);
        },
    );

    merge_composite_table(
        result,
        remote,
        "character_group_works",
        |r| {
            normalize_required_int(r, "sortOrder", 0, i32::MAX as i64, Some(0))
                && remap_required(r, "groupId", &group_map)
                && remap_required(r, "animeId", &anime_map)
        },
        |r| format!("{}|{}", long_value(r, "groupId"), long_value(r, "animeId")),
        |local, incoming| put_min_non_neg_int(local, "sortOrder", incoming),
    );

    merge_composite_table(
        result,
        remote,
        "episode_details",
        |r| {
            normalize_optional_int(r, "durationMinutes", 1, 1440);
            normalize_optional_int(r, "rating", 1, 100);
            normalize_required_int(r, "episode", 1, i32::MAX as i64, None)
                && remap_required(r, "animeId", &anime_map)
        },
        |r| format!("{}|{}", long_value(r, "animeId"), non_negative_int(r, "episode")),
        |local, incoming| {
            fill_missing_from(local, incoming, &["title", "durationMinutes", "synopsis", "review", "rating"]);
            put_latest(local, "updatedAt", incoming);
        },
    );

    merge_composite_table(
        result,
        remote,
        "book_reading_states",
        |r| {
            r.insert("localFileUri".into(), Value::Null);
            normalize_required_int(r, "chapterIndex", 0, 1_000_000, Some(0));
            normalize_required_int(r, "totalChapters", 0, 1_000_000, Some(0));
            normalize_required_int(r, "blockIndex", 0, 10_000_000, Some(0));
            normalize_required_int(r, "charOffset", 0, 100_000_000, Some(0));
            normalize_required_int(r, "progressPermille", 0, 1000, Some(0));
            normalize_optional_int(r, "legadoChapterIndex", 0, 1_000_000);
            normalize_optional_int(r, "legadoTotalChapters", 0, 1_000_000);
            if let Some(Value::Bool(b)) = r.get("syncProgressToMain") {
                r.insert("syncProgressToMain".into(), Value::from(if *b { 1 } else { 0 }));
            } else {
                normalize_required_int(r, "syncProgressToMain", 0, 1, Some(1));
            }
            if text(r, "updatedAt").is_none() {
                r.insert("updatedAt".into(), Value::String(String::new()));
            }
            remap_required(r, "animeId", &anime_map)
        },
        |r| format!("{}", long_value(r, "animeId")),
        |local, incoming| {
            fill_missing_from(
                local,
                incoming,
                &[
                    "localFileName",
                    "fileFormat",
                    "fileFingerprint",
                    "charsetName",
                    "legadoBookUrl",
                    "legadoOriginName",
                    "legadoAuthor",
                ],
            );
            let inc_ahead = non_negative_int(incoming, "progressPermille") > non_negative_int(local, "progressPermille")
                || (non_negative_int(incoming, "progressPermille") == non_negative_int(local, "progressPermille")
                    && non_negative_int(incoming, "chapterIndex") > non_negative_int(local, "chapterIndex"));
            if inc_ahead {
                local.insert("chapterIndex".into(), Value::from(non_negative_int(incoming, "chapterIndex")));
                if has_meaningful(incoming, "chapterTitle") {
                    local.insert("chapterTitle".into(), incoming.get("chapterTitle").cloned().unwrap_or(Value::Null));
                }
                local.insert("blockIndex".into(), Value::from(non_negative_int(incoming, "blockIndex")));
                local.insert("charOffset".into(), Value::from(non_negative_int(incoming, "charOffset")));
                local.insert("progressPermille".into(), Value::from(non_negative_int(incoming, "progressPermille")));
            } else {
                fill_missing_from(local, incoming, &["chapterTitle"]);
            }
            put_max_non_neg_int(local, "totalChapters", incoming);
            put_latest(local, "lastReadAt", incoming);

            let loc_leg = nullable_int(local, "legadoChapterIndex").unwrap_or(-1);
            let inc_leg = nullable_int(incoming, "legadoChapterIndex").unwrap_or(-1);
            if inc_leg > loc_leg {
                local.insert("legadoChapterIndex".into(), Value::from(inc_leg));
                for f in ["legadoChapterTitle", "legadoBookUrl", "legadoOriginName", "legadoAuthor"] {
                    if has_meaningful(incoming, f) {
                        local.insert(f.into(), incoming.get(f).cloned().unwrap_or(Value::Null));
                    }
                }
            } else {
                fill_missing_from(local, incoming, &["legadoChapterTitle"]);
            }
            let leg_total = match (nullable_int(local, "legadoTotalChapters"), nullable_int(incoming, "legadoTotalChapters")) {
                (Some(a), Some(b)) => Some(a.max(b)),
                (Some(a), None) => Some(a),
                (None, Some(b)) => Some(b),
                (None, None) => None,
            };
            local.insert("legadoTotalChapters".into(), leg_total.map(Value::from).unwrap_or(Value::Null));
            put_latest(local, "legadoLastReadAt", incoming);
            put_latest(local, "updatedAt", incoming);
        },
    );

    merge_id_entity_table(
        result,
        remote,
        "book_bookmarks",
        |r| {
            normalize_required_int(r, "chapterIndex", 0, 1_000_000, Some(0));
            normalize_required_int(r, "blockIndex", 0, 10_000_000, Some(0));
            normalize_required_int(r, "charOffset", 0, 100_000_000, Some(0));
            for f in ["chapterTitle", "snippet", "createdAt"] {
                if text(r, f).is_none() {
                    r.insert(f.into(), Value::String(String::new()));
                }
            }
            remap_required(r, "animeId", &anime_map)
        },
        |r| {
            format!(
                "{}|{}|{}|{}",
                long_value(r, "animeId"),
                non_negative_int(r, "chapterIndex"),
                non_negative_int(r, "blockIndex"),
                non_negative_int(r, "charOffset")
            )
        },
        |local, incoming| fill_missing_from(local, incoming, &["chapterTitle", "snippet", "note", "createdAt"]),
    );
}

fn merge_analysis(result: &mut Row, remote: &Row) {
    merge_id_entity_table(
        result,
        remote,
        "anime_analysis_records",
        |_| true,
        |r| {
            if let Some(sid) = nullable_long(r, "serverRecordId") {
                format!("server:{sid}")
            } else {
                format!(
                    "local:{}|{}|{}",
                    text(r, "createdAt").unwrap_or_default(),
                    text(r, "model").unwrap_or_default(),
                    text(r, "analysis").unwrap_or_default()
                )
            }
        },
        |local, incoming| {
            fill_missing_from(local, incoming, &["userId", "username", "model", "analysis", "statsJson", "createdAt"])
        },
    );
}
