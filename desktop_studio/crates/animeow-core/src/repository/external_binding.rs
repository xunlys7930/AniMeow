use super::{update_curator_cell, CellPatch};
use crate::agent::storage::record_work_field_source;
use crate::cloud::RemoteSubjectDetail;
use crate::{Result, StudioError};
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ExternalSource {
    Bangumi,
    Vndb,
    Dlsite,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BindingMode {
    None,
    #[default]
    FillMissing,
    OverwriteSelected,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExternalBindingRequest {
    pub kind: String,
    pub id: i64,
    pub source: ExternalSource,
    pub external_id: Option<String>,
    #[serde(default)]
    pub mode: BindingMode,
    #[serde(default)]
    pub fields: Vec<String>,
}

pub fn normalize_external_id(source: ExternalSource, input: &str) -> Result<String> {
    let input = input.trim();
    let valid_number = |s: &str| {
        !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
            && s.parse::<i64>().is_ok_and(|id| id > 0)
    };
    let id = match source {
        ExternalSource::Bangumi => {
            let id = input.strip_prefix("https://bgm.tv/subject/")
                .or_else(|| input.strip_prefix("https://bangumi.tv/subject/"))
                .map(|rest| rest.split(['/', '?', '#']).next().unwrap_or_default())
                .unwrap_or(input);
            valid_number(id).then(|| id.trim_start_matches('0').to_owned())
        }
        ExternalSource::Vndb => {
            let id = input.strip_prefix("https://vndb.org/").unwrap_or(input);
            id.strip_prefix('v').filter(|number| valid_number(number)).map(|_| id.to_owned())
        }
        ExternalSource::Dlsite => {
            let id = input.to_ascii_uppercase();
            super::RJ_CODE_RE.find(&id).filter(|m| {
                m.as_str() == id || (input.starts_with("https://www.dlsite.com/") && id.contains("/PRODUCT_ID/"))
            }).map(|m| m.as_str().to_owned())
        }
    };
    id.ok_or_else(|| StudioError::InvalidInput("请输入有效的外部条目 ID 或原站网址。".into()))
}

fn conflict(title: &str, id: &str) -> StudioError {
    StudioError::InvalidInput(format!("外部条目 #{id} 已由「{title}」绑定，请合并重复作品或先解绑。"))
}

pub(crate) fn replace_anime_bangumi_binding(conn: &Connection, id: i64, external_id: &str) -> Result<Value> {
    if let Some((_, _, title)) = find_active_bangumi_holder(conn, external_id, id)? {
        return Err(conflict(&title, external_id));
    }
    let mut stmt = conn.prepare(
        "SELECT id, externalSource, externalId, externalUrl FROM animes
         WHERE externalSource = 'bangumi' AND externalId = ?1 AND id <> ?2 AND deletedAt IS NOT NULL",
    )?;
    let released = stmt.query_map(params![external_id, id], |row| {
        Ok(json!({
            "id": row.get::<_, i64>(0)?,
            "externalSource": row.get::<_, Option<String>>(1)?,
            "externalId": row.get::<_, Option<String>>(2)?,
            "externalUrl": row.get::<_, Option<String>>(3)?,
        }))
    })?.collect::<rusqlite::Result<Vec<_>>>()?;
    let (previous_source, previous_id, previous_url): (Option<String>, Option<String>, Option<String>) = conn.query_row(
        "SELECT externalSource, externalId, externalUrl FROM animes WHERE id = ?1", [id],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )?;
    conn.execute(
        "UPDATE animes SET externalSource = NULL, externalId = NULL, externalUrl = NULL, updatedAt = ?3
         WHERE externalSource = 'bangumi' AND externalId = ?1 AND id <> ?2 AND deletedAt IS NOT NULL",
        params![external_id, id, Utc::now().timestamp_millis()],
    )?;
    conn.execute(
        "UPDATE animes SET externalSource = 'bangumi', externalId = ?1, externalUrl = ?2, updatedAt = ?3 WHERE id = ?4",
        params![external_id, format!("https://bgm.tv/subject/{external_id}"), Utc::now().timestamp_millis(), id],
    )?;
    Ok(json!({"previous": [previous_source, previous_id], "previousUrl": previous_url, "applied": ["bangumi", external_id], "releasedDeletedLinks": released}))
}

pub fn update_work_external_binding(
    sync: &mut Connection,
    local: &mut Connection,
    request: &ExternalBindingRequest,
    metadata: Option<&RemoteSubjectDetail>,
) -> Result<Option<String>> {
    let id = request.id;
    let external = request.external_id.as_deref()
        .map(|input| normalize_external_id(request.source, input)).transpose()?;
    let tx = sync.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let (table, kind_clause) = match request.kind.as_str() {
        "anime" => ("animes", "AND lower(subjectType) NOT IN ('book', 'manga', 'novel')"),
        "book" => ("animes", "AND lower(subjectType) IN ('book', 'manga', 'novel')"),
        "game" => ("games", ""),
        "audio" => ("audio_works", ""),
        _ => return Err(StudioError::InvalidInput("不支持的作品载体。".into())),
    };
    let active: bool = tx.query_row(
        &format!("SELECT EXISTS(SELECT 1 FROM {table} WHERE id = ?1 AND deletedAt IS NULL {kind_clause})"),
        [id], |row| row.get(0),
    )?;
    if !active {
        return Err(StudioError::InvalidInput("该作品已删除或不存在。".into()));
    }
    match (table, request.source) {
        ("animes", ExternalSource::Bangumi) => {
            if let Some(external) = external.as_deref() {
                replace_anime_bangumi_binding(&tx, id, external)?;
            } else {
                tx.execute("UPDATE animes SET externalSource = NULL, externalId = NULL, externalUrl = NULL, updatedAt = ?2
                            WHERE id = ?1 AND externalSource = 'bangumi'", params![id, Utc::now().timestamp_millis()])?;
            }
        }
        ("games", source @ (ExternalSource::Bangumi | ExternalSource::Vndb)) => {
            let column = match source { ExternalSource::Bangumi => "bangumiId", _ => "vndbId" };
            let now = Utc::now().to_rfc3339();
            if let Some(external) = external.as_deref() {
                release_holder(&tx, table, column, "NULL", external, id, &now)?;
            }
            tx.execute(&format!("UPDATE games SET {column} = ?1, updatedAt = ?2 WHERE id = ?3"), params![external, now, id])?;
        }
        ("audio_works", source @ (ExternalSource::Dlsite | ExternalSource::Bangumi)) => {
            let now = Utc::now().to_rfc3339();
            let bangumi = matches!(source, ExternalSource::Bangumi);
            if let Some(external) = external.as_deref() {
                let (code, url) = if bangumi {
                    ("", format!("https://bgm.tv/subject/{external}"))
                } else {
                    (external, format!("https://www.dlsite.com/maniax/work/=/product_id/{external}.html"))
                };
                let (column, value) = if bangumi { ("sourceUrl", url.as_str()) } else { ("upper(workCode)", code) };
                release_holder(&tx, table, column, "''", value, id, &now)?;
                tx.execute("UPDATE audio_works SET workCode = ?1, sourceUrl = ?2, updatedAt = ?3 WHERE id = ?4",
                           params![code, url, now, id])?;
            } else {
                tx.execute("UPDATE audio_works SET workCode = '', sourceUrl = '', updatedAt = ?2 WHERE id = ?1
                            AND ((?3 AND workCode = '' AND sourceUrl LIKE 'https://bgm.tv/subject/%')
                              OR (NOT ?3 AND workCode <> ''))", params![id, now, bangumi])?;
            }
        }
        _ => return Err(StudioError::InvalidInput("该作品载体不支持此来源。".into())),
    }
    let mut fields = Vec::new();
    if external.is_some() && !matches!(request.mode, BindingMode::None) {
        if let Some(metadata) = metadata {
            fields = write_metadata(&tx, request, table, metadata)?;
        }
    }
    tx.commit()?;
    let record = || -> Result<()> {
        if !fields.is_empty() {
            let tx = local.unchecked_transaction()?;
            for field in fields {
                record_work_field_source(&tx, &request.kind, id, field, "external")?;
            }
            tx.commit()?;
        }
        Ok(())
    };
    Ok(record().err().map(|err| format!("绑定已保存，字段来源记录失败：{err}")))
}

fn release_holder(
    tx: &Connection, table: &str, column: &str, empty: &str, value: &str, id: i64, now: &str,
) -> Result<()> {
    let title: Option<String> = tx.query_row(
        &format!("SELECT title FROM {table} WHERE {column} = ?1 AND id <> ?2 AND deletedAt IS NULL LIMIT 1"),
        params![value, id], |row| row.get(0),
    ).optional()?;
    if let Some(title) = title {
        return Err(conflict(&title, value.rsplit('/').next().unwrap_or(value)));
    }
    let assignment = if table == "audio_works" { "workCode = '', sourceUrl = ''".to_owned() }
        else { format!("{column} = {empty}") };
    tx.execute(
        &format!("UPDATE {table} SET {assignment}, updatedAt = ?3 WHERE {column} = ?1 AND id <> ?2 AND deletedAt IS NOT NULL"),
        params![value, id, now],
    )?;
    Ok(())
}

fn write_metadata(
    tx: &Connection, request: &ExternalBindingRequest, table: &str, metadata: &RemoteSubjectDetail,
) -> Result<Vec<&'static str>> {
    let (date, studio, synopsis, total, tags) = match table {
        "animes" => ("airDate", "studio", "synopsis", "totalEpisodes", "EXISTS(SELECT 1 FROM anime_tags WHERE animeId = animes.id)"),
        "games" => ("releaseDate", "developer", "synopsis", "0", "trim(tags) <> ''"),
        _ => ("releaseDate", "circle", "NULL", "0", "trim(tags) <> ''"),
    };
    let exists: (bool, bool, bool, bool, bool, bool, bool) = tx.query_row(
        &format!("SELECT trim(coalesce(coverUrl,'')) <> '', trim(coalesce(originalTitle,'')) <> '',
                         trim(coalesce({date},'')) <> '', trim(coalesce({studio},'')) <> '',
                         trim(coalesce({synopsis},'')) <> '', {total} > 0, {tags} FROM {table} WHERE id = ?1"),
        [request.id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?, row.get(5)?, row.get(6)?)),
    )?;
    let mut written = Vec::new();
    let mut take = |field: &'static str, existing: bool, available: bool| {
        let selected = match request.mode {
            BindingMode::None => false,
            BindingMode::FillMissing => !existing,
            BindingMode::OverwriteSelected => request.fields.iter().any(|f| f == field),
        };
        if selected && available {
            written.push(field);
        }
        selected && available
    };
    let valid = |value: &Option<String>| value.as_deref().is_some_and(|v| !v.trim().is_empty());
    let patch = CellPatch {
        id: request.id,
        kind: request.kind.clone(),
        cover_url: take("cover_url", exists.0, valid(&metadata.cover_url)).then(|| metadata.cover_url.clone()).flatten(),
        original_title: take("original_title", exists.1, valid(&metadata.original_title)).then(|| metadata.original_title.clone()).flatten(),
        release_date: take("release_date", exists.2, valid(&metadata.release_date)).then(|| metadata.release_date.clone()).flatten(),
        studio_or_circle: take("studio", exists.3, valid(&metadata.studio)).then(|| metadata.studio.clone()).flatten(),
        synopsis: take("synopsis", exists.4, table != "audio_works" && valid(&metadata.summary)).then(|| metadata.summary.clone()).flatten(),
        progress_total: take("total_episodes", exists.5, table == "animes" && metadata.total_episodes.is_some_and(|n| n > 0))
            .then_some(metadata.total_episodes).flatten(),
        tags: take("tags", exists.6, !metadata.tags.is_empty()).then(|| metadata.tags.clone()),
        ..CellPatch::default()
    };
    if !written.is_empty() {
        update_curator_cell(tx, &patch)?;
    }
    Ok(written)
}

pub(crate) fn find_active_bangumi_holder(
    conn: &Connection,
    external_id: &str,
    exclude_work_id: i64,
) -> Result<Option<(i64, String, String)>> {
    let row = conn
        .query_row(
            "SELECT id, COALESCE(subjectType, 'anime'), title
             FROM animes
             WHERE externalSource = 'bangumi' AND externalId = ?1 AND id <> ?2 AND deletedAt IS NULL
             LIMIT 1",
            params![external_id, exclude_work_id],
            |row| {
                let id: i64 = row.get(0)?;
                let subj: String = row.get(1)?;
                let kind = if matches!(subj.to_ascii_lowercase().as_str(), "book" | "manga" | "novel") {
                    "book".to_string()
                } else {
                    "anime".to_string()
                };
                let title: String = row.get(2)?;
                Ok((id, kind, title))
            },
        )
        .optional()?;
    Ok(row)
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::{init_local_db, init_sync_db};

    #[test]
    fn binding_rebind_unbind_and_release_trashed_holders() {
        let temp = tempfile::tempdir().unwrap();
        let mut sync = init_sync_db(&temp.path().join("sync.db")).unwrap();
        let mut local = init_local_db(&temp.path().join("local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO animes(id,title,subjectType) VALUES(1,'动画','anime'),(2,'书籍','book');
             INSERT INTO animes(id,title,deletedAt,externalSource,externalId)
             VALUES(3,'回收站动画','2026-11-22','bangumi','10');
             INSERT INTO games(id,syncId,title,updatedAt) VALUES(1,'game','游戏','2026-11-22');
             INSERT INTO audio_works(id,syncId,title,updatedAt) VALUES(1,'audio','音声','2026-11-22');"
        ).unwrap();
        for (kind, id, source, first, second, select) in [
            ("anime", 1, ExternalSource::Bangumi, "10", "11", "SELECT externalId FROM animes WHERE id=1"),
            ("book", 2, ExternalSource::Bangumi, "20", "21", "SELECT externalId FROM animes WHERE id=2"),
            ("game", 1, ExternalSource::Bangumi, "10", "11", "SELECT CAST(bangumiId AS TEXT) FROM games WHERE id=1"),
            ("game", 1, ExternalSource::Vndb, "v10", "v11", "SELECT vndbId FROM games WHERE id=1"),
            ("audio", 1, ExternalSource::Dlsite, "RJ01122334", "RJ01122335", "SELECT nullif(workCode,'') FROM audio_works WHERE id=1"),
            ("audio", 1, ExternalSource::Bangumi, "20", "21", "SELECT nullif(replace(sourceUrl,'https://bgm.tv/subject/',''),'') FROM audio_works WHERE id=1"),
        ] {
            for expected in [Some(first), Some(second), None] {
                let request = ExternalBindingRequest {
                    kind: kind.into(), id, source, external_id: expected.map(str::to_owned),
                    mode: BindingMode::None, fields: Vec::new(),
                };
                assert_eq!(update_work_external_binding(&mut sync, &mut local, &request, None).unwrap(), None);
                let actual: Option<String> = sync.query_row(select, [], |row| row.get(0)).unwrap();
                assert_eq!(actual.as_deref(), expected, "{kind}");
            }
        }
        let released: Option<String> = sync.query_row("SELECT externalId FROM animes WHERE id=3", [], |r| r.get(0)).unwrap();
        assert_eq!(released, None);
    }

    #[test]
    fn conflict_rolls_back_and_fill_missing_preserves_local_metadata() {
        let temp = tempfile::tempdir().unwrap();
        let mut sync = init_sync_db(&temp.path().join("sync.db")).unwrap();
        let mut local = init_local_db(&temp.path().join("local.db")).unwrap();
        sync.execute_batch(
            "INSERT INTO animes(id,title,originalTitle,watchedEpisodes,externalSource,externalId)
             VALUES(1,'当前作品','手填原名',3,NULL,NULL),(2,'冲突作品',NULL,0,'bangumi','11');"
        ).unwrap();
        let mut request = ExternalBindingRequest {
            kind: "anime".into(), id: 1, source: ExternalSource::Bangumi,
            external_id: Some("11".into()), mode: BindingMode::FillMissing, fields: Vec::new(),
        };
        let detail: RemoteSubjectDetail = serde_json::from_value(json!({
            "externalSource": "bangumi", "externalId": "12", "originalTitle": "远端原名",
            "totalEpisodes": 12, "summary": "远端简介", "tags": ["科幻"],
        })).unwrap();
        let err = update_work_external_binding(&mut sync, &mut local, &request, Some(&detail)).unwrap_err().to_string();
        assert!(err.contains("冲突作品") && err.contains("#11"));
        let unchanged: Option<String> = sync.query_row("SELECT synopsis FROM animes WHERE id=1", [], |r| r.get(0)).unwrap();
        assert_eq!(unchanged, None);
        request.external_id = Some("12".into());
        assert_eq!(update_work_external_binding(&mut sync, &mut local, &request, Some(&detail)).unwrap(), None);
        let current: (String,String,i64,i64) = sync.query_row(
            "SELECT originalTitle,synopsis,watchedEpisodes,totalEpisodes FROM animes WHERE id=1", [],
            |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?)),
        ).unwrap();
        assert_eq!(current, ("手填原名".into(), "远端简介".into(), 3, 12));
        let fields = crate::agent::storage::load_work_field_sources(&local, "anime", 1).unwrap();
        assert!(fields.contains_key("synopsis"));
        assert!(!fields.contains_key("original_title"));
    }
}
