use crate::merger::{
    compute_data_fingerprint, merge_native_snapshots, normalize_anime_memo,
    normalize_series_linked_media_json, normalize_subject_type, BinaryEntry, Row, SERIES_ROLES,
};
use crate::schema::{table_columns, BACKUP_TABLES, CLEAR_ORDER};
use crate::{Result, StudioError};
use base64::{engine::general_purpose::STANDARD as BASE64_STD, Engine};
use chrono::Utc;
use rusqlite::{types::ValueRef, Connection};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs::File;
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use uuid::Uuid;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

pub const NATIVE_BACKUP_FORMAT_TYPE: &str = "animeow-native";
pub const NATIVE_BACKUP_WRITE_VERSION: i64 = 10;
pub const NATIVE_BACKUP_MAX_READ_VERSION: i64 = 11;
pub const COVER_MARKER: &str = "animeow-cover://";

const MAX_ENTRIES: usize = 30_000;
const MAX_ENTRY_BYTES: u64 = 300 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const ANALYSIS_TABLE: &str = "anime_analysis_records";

pub const DESKTOP_SETTINGS_KEY: &str = "desktopStudio";

#[derive(Debug, Clone)]
pub struct BackupPaths {
    pub covers_root: PathBuf,
    pub restore_points_root: PathBuf,
    pub persisted_settings_file: PathBuf,
    pub persisted_profiles_file: PathBuf,
    pub desktop_settings_file: PathBuf,
    pub branding_root: PathBuf,
    pub temp_root: PathBuf,
}

impl BackupPaths {
    pub fn from_studio_root(studio_root: &Path) -> Self {
        Self {
            covers_root: studio_root.join("covers"),
            restore_points_root: studio_root.join("restore_points"),
            persisted_settings_file: studio_root.join("native-backup-payload").join("settings.json"),
            persisted_profiles_file: studio_root
                .join("native-backup-payload")
                .join("configuration_profiles.json"),
            desktop_settings_file: studio_root
                .join("native-backup-payload")
                .join("desktop_settings.json"),
            branding_root: studio_root.join("branding"),
            temp_root: studio_root.join("tmp"),
        }
    }
}

/// 读取当前本机持久化的桌面端可同步设置（若不存在返回 None）。
pub fn load_desktop_settings(paths: &BackupPaths) -> Result<Option<Value>> {
    if !paths.desktop_settings_file.is_file() {
        return Ok(None);
    }
    let text = std::fs::read_to_string(&paths.desktop_settings_file)?;
    let parsed: Value = serde_json::from_str(&text)?;
    if parsed.is_object() {
        Ok(Some(parsed))
    } else {
        Ok(None)
    }
}

/// 持久化桌面端设置到本地（参与云端备份中 `settings.json.desktopStudio` 的跨设备同步）。
pub fn save_desktop_settings(paths: &BackupPaths, settings: &Value) -> Result<()> {
    if !settings.is_object() {
        return Err(crate::StudioError::InvalidInput("桌面端设置必须是 JSON 对象".into()));
    }
    if let Some(parent) = paths.desktop_settings_file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(
        &paths.desktop_settings_file,
        serde_json::to_string_pretty(settings)?,
    )?;
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupSummary {
    pub anime_count: usize,
    pub book_count: usize,
    pub game_count: usize,
    pub audio_count: usize,
    pub tag_count: usize,
    pub character_count: usize,
    pub group_count: usize,
    pub cover_count: usize,
    pub created_at: String,
    pub data_fingerprint: String,
    pub format_version: i64,
    pub fingerprint_matches: Option<bool>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RestoreStrategy {
    Replace,
    MergeKeepLocal,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreSelection {
    pub library_and_characters: bool,
    pub analysis_history: bool,
}

impl Default for RestoreSelection {
    fn default() -> Self {
        Self {
            library_and_characters: true,
            analysis_history: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreResult {
    pub summary: BackupSummary,
    pub restore_point_file: String,
}

/// 去掉封面相对路径前导的 `restored_<id>/` 恢复批次前缀，
/// 与 Android 端保持一致，防止跨端同步路径无限嵌套。
pub fn strip_restored_cover_prefixes(rel: &str) -> String {
    let mut current = rel.replace('\\', "/");
    while current.starts_with('/') {
        current = current[1..].to_string();
    }
    loop {
        if let Some(rest) = current.strip_prefix("restored_") {
            if let Some(idx) = rest.find('/') {
                let next = &rest[idx + 1..];
                if !next.is_empty() {
                    current = next.to_string();
                    continue;
                }
            }
        }
        break;
    }
    current
}

/// 在本机 `covers/` 根目录下按规范相对路径查找现存自定义封面文件（含已有 `restored_*` 目录）。
pub fn resolve_existing_cover_file(covers_root: &Path, raw_rel: &str) -> Option<(String, PathBuf)> {
    let normalized = raw_rel.replace('\\', "/");
    let normalized = normalized.trim_start_matches('/');
    if normalized.is_empty() {
        return None;
    }
    let canonical = strip_restored_cover_prefixes(normalized);

    if let Ok(direct) = safe_child(covers_root, normalized) {
        if direct.is_file() {
            return Some((normalized.to_string(), direct));
        }
    }
    if canonical != normalized {
        if let Ok(direct) = safe_child(covers_root, &canonical) {
            if direct.is_file() {
                return Some((canonical, direct));
            }
        }
    }

    let mut restored_dirs: Vec<PathBuf> = std::fs::read_dir(covers_root)
        .ok()?
        .filter_map(|e| e.ok().map(|entry| entry.path()))
        .filter(|p| {
            p.is_dir()
                && p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with("restored_"))
        })
        .collect();
    restored_dirs.sort_by(|a, b| {
        let ma = std::fs::metadata(a).and_then(|m| m.modified()).ok();
        let mb = std::fs::metadata(b).and_then(|m| m.modified()).ok();
        mb.cmp(&ma)
    });

    for dir in restored_dirs {
        let dir_name = dir.file_name().and_then(|n| n.to_str()).unwrap_or_default();
        if let Ok(target) = safe_child(&dir, &canonical) {
            if target.is_file() {
                return Some((format!("{dir_name}/{canonical}"), target));
            }
        }
        if canonical != normalized {
            if let Ok(target) = safe_child(&dir, normalized) {
                if target.is_file() {
                    return Some((format!("{dir_name}/{normalized}"), target));
                }
            }
        }
    }
    None
}

/// 从本机任意图片文件复制到 `covers/native/`，并返回规范跨端标记 `animeow-cover://native/...`。
pub fn import_local_custom_cover(covers_root: &Path, source_path: &Path) -> Result<String> {
    if !source_path.is_file() {
        return Err(StudioError::InvalidInput("找不到所选封面图片文件".into()));
    }
    let meta = std::fs::metadata(source_path)?;
    if meta.len() == 0 || meta.len() > 15 * 1024 * 1024 {
        return Err(StudioError::InvalidInput(
            "自定义封面图片需在 1 B ~ 15 MB 之间".into(),
        ));
    }
    let ext = source_path
        .extension()
        .and_then(|e| e.to_str())
        .map(|s| s.to_ascii_lowercase())
        .unwrap_or_default();
    let safe_ext = match ext.as_str() {
        "jpg" | "jpeg" => "jpg",
        "png" => "png",
        "webp" => "webp",
        "gif" => "gif",
        _ => {
            return Err(StudioError::InvalidInput(
                "暂仅支持 JPG、PNG、WebP 或 GIF 图片作为封面".into(),
            ));
        }
    };
    let dest_dir = covers_root.join("native");
    std::fs::create_dir_all(&dest_dir)?;
    let filename = format!(
        "cover_{}_{}.{}",
        Utc::now().timestamp_millis(),
        &Uuid::new_v4().simple().to_string()[..8],
        safe_ext
    );
    let dest_path = dest_dir.join(&filename);
    std::fs::copy(source_path, &dest_path)?;
    Ok(format!("{COVER_MARKER}native/{filename}"))
}

/// 从 `file://.../covers/<rel>` 或历史绝对路径中提取 `/covers/` 后的相对路径（兼容手机端/旧桌面端残留路径）。
fn extract_covers_relative_suffix(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") || lower.starts_with("data:") {
        return None;
    }
    let normalized = trimmed
        .strip_prefix("file:///")
        .or_else(|| trimmed.strip_prefix("file://"))
        .unwrap_or(trimmed)
        .replace('\\', "/");
    let marker = "/covers/";
    let pos = normalized.to_ascii_lowercase().rfind(marker)?;
    let tail = &normalized[pos + marker.len()..];
    let canonical = strip_restored_cover_prefixes(tail);
    (!canonical.is_empty()).then_some(canonical)
}

pub fn encode_cover_reference(value: &str, covers_root: &Path) -> String {
    if let Some(rel) = value.strip_prefix(COVER_MARKER) {
        let canonical = strip_restored_cover_prefixes(rel);
        return if canonical.is_empty() {
            value.to_string()
        } else {
            format!("{COVER_MARKER}{canonical}")
        };
    }
    let candidate = if let Some(stripped) = value.strip_prefix("file:///") {
        PathBuf::from(stripped.replace('/', std::path::MAIN_SEPARATOR_STR))
    } else if let Some(stripped) = value.strip_prefix("file://") {
        PathBuf::from(stripped.replace('/', std::path::MAIN_SEPARATOR_STR))
    } else {
        let p = Path::new(value);
        if p.is_absolute() {
            p.to_path_buf()
        } else {
            return value.to_string();
        }
    };

    if let (Ok(canon_root), Ok(canon_target)) = (covers_root.canonicalize(), candidate.canonicalize()) {
        if let Ok(rel) = canon_target.strip_prefix(&canon_root) {
            let posix = strip_restored_cover_prefixes(&to_posix(rel));
            if !posix.is_empty() {
                return format!("{COVER_MARKER}{posix}");
            }
        }
    }
    if let Some(canonical) = extract_covers_relative_suffix(value) {
        return format!("{COVER_MARKER}{canonical}");
    }
    value.to_string()
}

pub fn read_all_tables(conn: &Connection, paths: &BackupPaths) -> Result<Row> {
    let mut root = Row::new();
    for &table in BACKUP_TABLES {
        let mut stmt = conn.prepare(&format!("SELECT * FROM `{table}` ORDER BY rowid"))?;
        let col_names: Vec<String> = stmt.column_names().iter().map(|s| (*s).to_string()).collect();
        let mut rows_iter = stmt.query([])?;
        let mut table_rows = Vec::new();

        while let Some(row) = rows_iter.next()? {
            let mut obj = Row::new();
            for (idx, col) in col_names.iter().enumerate() {
                if table == "book_reading_states" && col == "localFileUri" {
                    obj.insert(col.clone(), Value::Null);
                    continue;
                }
                let val = match row.get_ref(idx)? {
                    ValueRef::Null => Value::Null,
                    ValueRef::Integer(i) => Value::from(i),
                    ValueRef::Real(f) => serde_json::Number::from_f64(f)
                        .map(Value::Number)
                        .unwrap_or(Value::Null),
                    ValueRef::Text(bytes) => {
                        let s = String::from_utf8_lossy(bytes);
                        Value::String(encode_cover_reference(&s, &paths.covers_root))
                    }
                    ValueRef::Blob(bytes) => Value::String(BASE64_STD.encode(bytes)),
                };
                obj.insert(col.clone(), val);
            }
            table_rows.push(Value::Object(obj));
        }
        root.insert(table.to_string(), Value::Array(table_rows));
    }
    Ok(root)
}

pub fn export_native_backup(
    conn: &Connection,
    output_zip_path: &Path,
    paths: &BackupPaths,
    include_covers: bool,
    include_branding_fonts: bool,
) -> Result<BackupSummary> {
    export_native_backup_with_options(
        conn,
        output_zip_path,
        paths,
        include_covers,
        include_branding_fonts,
        false,
    )
}

pub fn export_native_backup_with_options(
    conn: &Connection,
    output_zip_path: &Path,
    paths: &BackupPaths,
    include_covers: bool,
    include_branding_fonts: bool,
    only_referenced_covers: bool,
) -> Result<BackupSummary> {
    let database_json = read_all_tables(conn, paths)?;
    let database_text = serde_json::to_string(&Value::Object(database_json.clone()))?;
    let mut settings_obj = std::fs::read_to_string(&paths.persisted_settings_file)
        .ok()
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    if let Ok(Some(Value::Object(desktop_obj))) = load_desktop_settings(paths) {
        let sync_enabled = desktop_obj
            .get("syncDesktopSettings")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        if sync_enabled {
            settings_obj.insert(DESKTOP_SETTINGS_KEY.to_string(), Value::Object(desktop_obj));
        } else {
            settings_obj.remove(DESKTOP_SETTINGS_KEY);
        }
    }
    let settings_text = serde_json::to_string(&Value::Object(settings_obj))?;
    let profiles_text = std::fs::read_to_string(&paths.persisted_profiles_file)
        .unwrap_or_else(|_| "{}".to_string());

    let mut cover_blobs: Vec<(String, Vec<u8>)> = Vec::new();
    if include_covers {
        let mut by_canonical: std::collections::BTreeMap<String, PathBuf> = std::collections::BTreeMap::new();
        // 优先保证数据库引用的自定义封面以规范相对路径进包
        for &table in BACKUP_TABLES {
            if let Some(Value::Array(rows)) = database_json.get(table) {
                for row_val in rows {
                    if let Some(obj) = row_val.as_object() {
                        for (_, v) in obj {
                            if let Some(s) = v.as_str() {
                                if let Some(rel) = s.strip_prefix(COVER_MARKER) {
                                    let canonical = strip_restored_cover_prefixes(rel);
                                    if !canonical.is_empty() && !by_canonical.contains_key(&canonical) {
                                        if let Some((_, file_path)) = resolve_existing_cover_file(&paths.covers_root, rel) {
                                            by_canonical.insert(canonical, file_path);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if !only_referenced_covers {
            for file in list_files_recursive(&paths.covers_root)? {
                let rel = relative_posix(&paths.covers_root, &file)?;
                let canonical = strip_restored_cover_prefixes(&rel);
                if !canonical.is_empty() {
                    by_canonical.entry(canonical).or_insert(file);
                }
            }
        }
        for (canonical, file) in by_canonical {
            cover_blobs.push((canonical, std::fs::read(&file)?));
        }
    }

    let mut branding_blobs: Vec<(String, Vec<u8>)> = Vec::new();
    for file in list_files_recursive(&paths.branding_root)? {
        let rel = relative_posix(&paths.branding_root, &file)?;
        if !include_branding_fonts && rel.starts_with("fonts/") {
            continue;
        }
        branding_blobs.push((rel, std::fs::read(&file)?));
    }
    let fonts_included = branding_blobs.iter().any(|(rel, _)| rel.starts_with("fonts/"));

    let cover_refs: Vec<BinaryEntry<'_>> = cover_blobs
        .iter()
        .map(|(p, b)| BinaryEntry {
            relative_path: p.as_str(),
            content: b.as_slice(),
        })
        .collect();
    let branding_refs: Vec<BinaryEntry<'_>> = branding_blobs
        .iter()
        .map(|(p, b)| BinaryEntry {
            relative_path: p.as_str(),
            content: b.as_slice(),
        })
        .collect();

    let created_at = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let fingerprint = compute_data_fingerprint(
        &database_text,
        &settings_text,
        &profiles_text,
        &cover_refs,
        &branding_refs,
    );

    let summary = summary_from_database(
        &database_json,
        cover_blobs.len(),
        created_at.clone(),
        fingerprint.clone(),
        NATIVE_BACKUP_WRITE_VERSION,
        Some(true),
    );

    if let Some(parent) = output_zip_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let zip_file = File::create(output_zip_path)?;
    let mut zip = ZipWriter::new(zip_file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let manifest = json!({
        "type": NATIVE_BACKUP_FORMAT_TYPE,
        "version": NATIVE_BACKUP_WRITE_VERSION,
        "createdAt": created_at,
        "animeCount": summary.anime_count + summary.book_count,
        "gameCount": summary.game_count,
        "audioCount": summary.audio_count,
        "coverCount": summary.cover_count,
        "dataFingerprint": fingerprint,
        "fontsIncluded": fonts_included,
    });
    zip.start_file("manifest.json", options)?;
    zip.write_all(serde_json::to_string(&manifest)?.as_bytes())?;

    zip.start_file("database.json", options)?;
    zip.write_all(database_text.as_bytes())?;

    zip.start_file("settings.json", options)?;
    zip.write_all(settings_text.as_bytes())?;

    zip.start_file("configuration_profiles.json", options)?;
    zip.write_all(profiles_text.as_bytes())?;

    for (rel, content) in &cover_blobs {
        zip.start_file(format!("covers/{rel}"), options)?;
        zip.write_all(content)?;
    }
    for (rel, content) in &branding_blobs {
        zip.start_file(format!("branding/{rel}"), options)?;
        zip.write_all(content)?;
    }

    zip.finish()?;
    Ok(summary)
}

pub fn inspect_native_backup(zip_path: &Path, paths: &BackupPaths) -> Result<BackupSummary> {
    let extracted = extract_to_staging(zip_path, paths)?;
    let summary = extracted.summary.clone();
    let _ = std::fs::remove_dir_all(&extracted.staging_dir);
    Ok(summary)
}

pub fn restore_native_backup(
    conn: &mut Connection,
    zip_path: &Path,
    paths: &BackupPaths,
    selection: RestoreSelection,
    strategy: RestoreStrategy,
) -> Result<RestoreResult> {
    if !selection.library_and_characters && !selection.analysis_history {
        return Err(StudioError::InvalidInput("至少选择一类需要恢复的数据".into()));
    }

    let extracted = extract_to_staging(zip_path, paths)?;
    let result = (|| -> Result<RestoreResult> {
        // 1. 先创建自动恢复点，确保失败可回退
        std::fs::create_dir_all(&paths.restore_points_root)?;
        let rp_name = format!("{}_pre-restore.animeow.zip", Utc::now().timestamp_millis());
        let rp_path = paths.restore_points_root.join(&rp_name);
        export_native_backup(conn, &rp_path, paths, true, true)?;

        // 2. 复制封面至独立 restored_<ts> 子目录
        let restored_dir_name = format!("restored_{}_{}", Utc::now().timestamp_millis(), &Uuid::new_v4().to_string()[..8]);
        let dest_covers = paths.covers_root.join(&restored_dir_name);
        if selection.library_and_characters {
            let src_covers = extracted.staging_dir.join("covers");
            for file in list_files_recursive(&src_covers)? {
                let rel = relative_posix(&src_covers, &file)?;
                let target = safe_child(&dest_covers, &rel)?;
                if let Some(p) = target.parent() {
                    std::fs::create_dir_all(p)?;
                }
                std::fs::copy(&file, &target)?;
            }
        }

        // 3. 品牌资产保真中转（含云端缺省 fonts/ 时的本地字体保护）
        let src_branding = extracted.staging_dir.join("branding");
        let incoming_branding = list_files_recursive(&src_branding)?;
        if !incoming_branding.is_empty() {
            let incoming_has_fonts = incoming_branding.iter().any(|f| {
                relative_posix(&src_branding, f)
                    .map(|r| r.starts_with("fonts/"))
                    .unwrap_or(false)
            });
            let stored_fonts = paths.branding_root.join("fonts");
            let preserved_fonts = if !incoming_has_fonts && stored_fonts.is_dir() {
                let keep = paths.branding_root.with_extension("fonts-keep");
                let _ = std::fs::remove_dir_all(&keep);
                std::fs::rename(&stored_fonts, &keep)?;
                Some(keep)
            } else {
                None
            };

            if paths.branding_root.exists() {
                std::fs::remove_dir_all(&paths.branding_root)?;
            }
            for file in &incoming_branding {
                let rel = relative_posix(&src_branding, file)?;
                let target = safe_child(&paths.branding_root, &rel)?;
                if let Some(p) = target.parent() {
                    std::fs::create_dir_all(p)?;
                }
                std::fs::copy(file, target)?;
            }
            if let Some(keep) = preserved_fonts {
                std::fs::rename(&keep, paths.branding_root.join("fonts"))?;
            }
        }

        // 4. 手机端 settings / configuration_profiles 与桌面端 desktopStudio 设置分层保真留存
        if extracted.has_settings {
            if let Some(p) = paths.persisted_settings_file.parent() {
                std::fs::create_dir_all(p)?;
            }
            if let Ok(Value::Object(mut incoming_settings)) =
                serde_json::from_str::<Value>(&extracted.settings_text)
            {
                if let Some(desktop_val) = incoming_settings.remove(DESKTOP_SETTINGS_KEY) {
                    if desktop_val.is_object() {
                        let _ = save_desktop_settings(paths, &desktop_val);
                    }
                }
                // 仅当远端 settings.json 包含手机端自身配置键时更新手机设置留存，
                // 防止另一台空白桌面端传上来的纯 desktopStudio 包抹掉本机已留存的手机设置。
                if !incoming_settings.is_empty() {
                    std::fs::write(
                        &paths.persisted_settings_file,
                        serde_json::to_string(&Value::Object(incoming_settings))?,
                    )?;
                }
            } else {
                std::fs::write(&paths.persisted_settings_file, &extracted.settings_text)?;
            }
            if extracted.profiles_text.trim() != "{}" || !paths.persisted_profiles_file.is_file() {
                std::fs::write(&paths.persisted_profiles_file, &extracted.profiles_text)?;
            }
        }

        // 5. 数据库事务写入（REPLACE 或 MERGE_KEEP_LOCAL）
        let mut selected_tables = HashSet::new();
        if selection.library_and_characters {
            for &t in BACKUP_TABLES {
                if t != ANALYSIS_TABLE {
                    selected_tables.insert(t.to_string());
                }
            }
        }
        if selection.analysis_history {
            selected_tables.insert(ANALYSIS_TABLE.to_string());
        }
        // 旧备份缺表保护：缺 games / audio_works / book_reading_states / book_bookmarks 时不抹除本机已有记录
        for optional_table in ["games", "audio_works", "book_reading_states", "book_bookmarks"] {
            if !extracted.database_json.get(optional_table).is_some_and(|v| v.is_array()) {
                selected_tables.remove(optional_table);
            }
        }
        if !extracted.database_json.get("media_series").is_some_and(|v| v.is_array())
            || !extracted.database_json.get("media_series_members").is_some_and(|v| v.is_array())
        {
            selected_tables.remove("media_series");
            selected_tables.remove("media_series_members");
        }

        let remote_rewritten =
            rewrite_remote_cover_markers(&extracted.database_json, &restored_dir_name, &paths.covers_root);

        let final_json = if strategy == RestoreStrategy::MergeKeepLocal {
            let mut local_snapshot = read_all_tables(conn, paths)?;
            // 若远端备份实际携带封面图片，且本机某条目的自定义封面引用在磁盘上已缺失，
            // 清空本机失效引用以便合并阶段自动采用远端带回的有效封面（不覆盖本机有效封面）。
            if extracted.summary.cover_count > 0 {
                clear_missing_local_cover_refs(&mut local_snapshot, &paths.covers_root);
            }
            merge_native_snapshots(
                &local_snapshot,
                &remote_rewritten,
                selection.library_and_characters,
                selection.analysis_history,
            )
        } else {
            remote_rewritten
        };

        {
            let tx = conn.transaction()?;
            replace_database_in_tx(&tx, &final_json, &selected_tables)?;
            tx.commit()?;
        }

        let final_summary = if strategy == RestoreStrategy::MergeKeepLocal {
            summary_from_database(
                &final_json,
                extracted.summary.cover_count,
                Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true),
                String::new(),
                extracted.summary.format_version,
                extracted.summary.fingerprint_matches,
            )
        } else {
            extracted.summary.clone()
        };

        Ok(RestoreResult {
            summary: final_summary,
            restore_point_file: rp_name,
        })
    })();

    let _ = std::fs::remove_dir_all(&extracted.staging_dir);
    result
}

struct ExtractedBackup {
    staging_dir: PathBuf,
    database_json: Row,
    settings_text: String,
    profiles_text: String,
    has_settings: bool,
    summary: BackupSummary,
}

fn extract_to_staging(zip_path: &Path, paths: &BackupPaths) -> Result<ExtractedBackup> {
    std::fs::create_dir_all(&paths.temp_root)?;
    let staging_dir = paths.temp_root.join(format!("restore_{}", Uuid::new_v4()));
    std::fs::create_dir_all(&staging_dir)?;

    let res = (|| -> Result<ExtractedBackup> {
        let file = File::open(zip_path)?;
        let mut archive = ZipArchive::new(file)?;
        if archive.len() > MAX_ENTRIES {
            return Err(StudioError::BackupFormat("备份条目过多".into()));
        }

        let mut total_bytes = 0u64;
        for i in 0..archive.len() {
            let mut entry = archive.by_index(i)?;
            if entry.is_dir() {
                continue;
            }
            if entry.size() > MAX_ENTRY_BYTES {
                return Err(StudioError::BackupFormat("单个备份条目过大".into()));
            }
            total_bytes += entry.size();
            if total_bytes > MAX_TOTAL_BYTES {
                return Err(StudioError::BackupFormat("备份总体积过大".into()));
            }
            let target = safe_child(&staging_dir, entry.name())?;
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let mut out = File::create(&target)?;
            std::io::copy(&mut entry, &mut out)?;
        }

        let manifest_path = staging_dir.join("manifest.json");
        if !manifest_path.is_file() {
            return Err(StudioError::BackupFormat("检测到旧版或不完整的备份格式".into()));
        }
        let manifest_text = std::fs::read_to_string(&manifest_path)?;
        let manifest: Value = serde_json::from_str(&manifest_text)
            .map_err(|_| StudioError::BackupFormat("备份清单无法读取".into()))?;
        if manifest.get("type").and_then(|v| v.as_str()) != Some(NATIVE_BACKUP_FORMAT_TYPE) {
            return Err(StudioError::BackupFormat("不是 AniMeow 原生备份".into()));
        }
        let version = manifest.get("version").and_then(|v| v.as_i64()).unwrap_or(-1);
        if !(1..=NATIVE_BACKUP_MAX_READ_VERSION).contains(&version) {
            return Err(StudioError::BackupFormat("备份版本暂不支持".into()));
        }

        let db_path = staging_dir.join("database.json");
        if !db_path.is_file() {
            return Err(StudioError::BackupFormat("备份缺少资料库内容".into()));
        }
        let database_text = std::fs::read_to_string(&db_path)?;
        let Value::Object(database_json) = serde_json::from_str::<Value>(&database_text)
            .map_err(|_| StudioError::BackupFormat("备份资料库无法读取".into()))?
        else {
            return Err(StudioError::BackupFormat("备份资料库结构不正确".into()));
        };

        let settings_path = staging_dir.join("settings.json");
        let has_settings = settings_path.is_file();
        let settings_text = if has_settings {
            std::fs::read_to_string(&settings_path)?
        } else {
            "{}".to_string()
        };
        let profiles_path = staging_dir.join("configuration_profiles.json");
        let profiles_text = if profiles_path.is_file() {
            std::fs::read_to_string(&profiles_path)?
        } else {
            "{}".to_string()
        };

        let cover_root = staging_dir.join("covers");
        let mut cover_blobs = Vec::new();
        for f in list_files_recursive(&cover_root)? {
            cover_blobs.push((relative_posix(&cover_root, &f)?, std::fs::read(&f)?));
        }

        let branding_root = staging_dir.join("branding");
        let mut branding_blobs = Vec::new();
        for f in list_files_recursive(&branding_root)? {
            branding_blobs.push((relative_posix(&branding_root, &f)?, std::fs::read(&f)?));
        }

        let cover_refs: Vec<BinaryEntry<'_>> = cover_blobs
            .iter()
            .map(|(p, b)| BinaryEntry {
                relative_path: p.as_str(),
                content: b.as_slice(),
            })
            .collect();
        let branding_refs: Vec<BinaryEntry<'_>> = branding_blobs
            .iter()
            .map(|(p, b)| BinaryEntry {
                relative_path: p.as_str(),
                content: b.as_slice(),
            })
            .collect();

        let recomputed = compute_data_fingerprint(
            &database_text,
            &settings_text,
            &profiles_text,
            &cover_refs,
            &branding_refs,
        );
        let declared = manifest
            .get("dataFingerprint")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let matches = if declared.is_empty() {
            None
        } else {
            Some(declared == recomputed)
        };
        let created_at = manifest
            .get("createdAt")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .unwrap_or("未知")
            .to_string();

        let summary = summary_from_database(
            &database_json,
            cover_blobs.len(),
            created_at,
            if declared.is_empty() {
                recomputed
            } else {
                declared.to_string()
            },
            version,
            matches,
        );

        Ok(ExtractedBackup {
            staging_dir: staging_dir.clone(),
            database_json,
            settings_text,
            profiles_text,
            has_settings,
            summary,
        })
    })();

    if res.is_err() {
        let _ = std::fs::remove_dir_all(&staging_dir);
    }
    res
}

fn replace_database_in_tx(
    conn: &Connection,
    json: &Row,
    selected_tables: &HashSet<String>,
) -> Result<()> {
    for &table in CLEAR_ORDER {
        if selected_tables.contains(table) {
            conn.execute(&format!("DELETE FROM `{table}`"), [])?;
        }
    }

    for &table in BACKUP_TABLES {
        if !selected_tables.contains(table) {
            continue;
        }
        let Some(Value::Array(table_rows)) = json.get(table) else {
            continue;
        };
        let known_columns = table_columns(conn, table)?;
        for raw_row in table_rows {
            let Some(row) = raw_row.as_object() else {
                continue;
            };
            let mut columns: Vec<String> = Vec::new();
            let mut values: Vec<rusqlite::types::Value> = Vec::new();

            for (col, val) in row {
                if !known_columns.contains(col) {
                    // 未知列规则：跳过更高版本手机端新增列，不中断整表恢复
                    continue;
                }
                let normalized = normalize_cell_for_insert(table, col, val, row);
                columns.push(col.clone());
                values.push(json_to_sql_value(&normalized));
            }

            if table == "animes" && !columns.iter().any(|c| c == "subjectType") {
                columns.push("subjectType".into());
                values.push(rusqlite::types::Value::Text("anime".into()));
            }

            if table == "book_reading_states" {
                let defaults: &[(&str, rusqlite::types::Value)] = &[
                    ("chapterIndex", rusqlite::types::Value::Integer(0)),
                    ("totalChapters", rusqlite::types::Value::Integer(0)),
                    ("blockIndex", rusqlite::types::Value::Integer(0)),
                    ("charOffset", rusqlite::types::Value::Integer(0)),
                    ("progressPermille", rusqlite::types::Value::Integer(0)),
                    ("syncProgressToMain", rusqlite::types::Value::Integer(1)),
                    ("updatedAt", rusqlite::types::Value::Text(String::new())),
                ];
                for (col_name, fallback) in defaults {
                    if let Some(pos) = columns.iter().position(|c| c == col_name) {
                        if matches!(values[pos], rusqlite::types::Value::Null) {
                            values[pos] = fallback.clone();
                        }
                    } else {
                        columns.push((*col_name).into());
                        values.push(fallback.clone());
                    }
                }
            }

            if columns.is_empty() {
                continue;
            }
            let col_sql: Vec<String> = columns.iter().map(|c| format!("`{c}`")).collect();
            let placeholders: Vec<&str> = columns.iter().map(|_| "?").collect();
            let sql = format!(
                "INSERT OR REPLACE INTO `{table}` ({}) VALUES ({})",
                col_sql.join(", "),
                placeholders.join(", ")
            );
            // 单行容错：坏行跳过记日志，不拖垮整个恢复
            let _ = conn.execute(&sql, rusqlite::params_from_iter(values));
        }
    }
    Ok(())
}

fn normalize_cell_for_insert(table: &str, col: &str, val: &Value, row: &Row) -> Value {
    if table == "animes" && col == "subjectType" {
        return Value::String(normalize_subject_type(val.as_str()));
    }
    if table == "animes" && col == "seriesRole" {
        let has_series = row.get("seriesId").is_some_and(|v| !v.is_null());
        let ok = val.as_str().is_some_and(|s| SERIES_ROLES.contains(&s));
        return if has_series && ok { val.clone() } else { Value::Null };
    }
    if table == "animes" && col == "memo" {
        return normalize_anime_memo(Some(val));
    }
    if table == "series" && col == "linkedMediaJson" {
        return normalize_series_linked_media_json(Some(val), None);
    }
    if table == "book_reading_states" && col == "localFileUri" {
        return Value::Null;
    }
    val.clone()
}

fn json_to_sql_value(val: &Value) -> rusqlite::types::Value {
    match val {
        Value::Null => rusqlite::types::Value::Null,
        Value::Bool(b) => rusqlite::types::Value::Integer(if *b { 1 } else { 0 }),
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                rusqlite::types::Value::Integer(i)
            } else if let Some(f) = n.as_f64() {
                rusqlite::types::Value::Real(f)
            } else {
                rusqlite::types::Value::Null
            }
        }
        Value::String(s) => rusqlite::types::Value::Text(s.clone()),
        other => rusqlite::types::Value::Text(other.to_string()),
    }
}

fn clear_missing_local_cover_refs(json: &mut Row, covers_root: &Path) {
    for &table in BACKUP_TABLES {
        let Some(Value::Array(rows)) = json.get_mut(table) else {
            continue;
        };
        for row_val in rows {
            let Some(row) = row_val.as_object_mut() else {
                continue;
            };
            for (col, v) in row.iter_mut() {
                if !matches!(
                    col.as_str(),
                    "coverUrl" | "imageUrl" | "customCoverUrl"
                ) {
                    continue;
                }
                let Some(s) = v.as_str() else { continue };
                if let Some(rel) = s.strip_prefix(COVER_MARKER) {
                    if resolve_existing_cover_file(covers_root, rel).is_none() {
                        *v = Value::Null;
                    }
                }
            }
        }
    }
}

fn rewrite_remote_cover_markers(json: &Row, restored_dir_name: &str, covers_root: &Path) -> Row {
    let mut out = json.clone();
    let restored_base = covers_root.join(restored_dir_name);
    for &table in BACKUP_TABLES {
        let Some(Value::Array(rows)) = out.get_mut(table) else {
            continue;
        };
        for row_val in rows {
            let Some(row) = row_val.as_object_mut() else {
                continue;
            };
            for (_, v) in row.iter_mut() {
                let Some(s) = v.as_str() else { continue };
                let owned_rel: String = if let Some(rel) = s.strip_prefix(COVER_MARKER) {
                    rel.to_string()
                } else if let Some(rel) = extract_covers_relative_suffix(s) {
                    rel
                } else {
                    continue;
                };
                let rel = owned_rel.as_str();
                let canonical = strip_restored_cover_prefixes(rel);
                if let Ok(target) = safe_child(&restored_base, rel) {
                    if target.is_file() {
                        *v = Value::String(format!("{COVER_MARKER}{restored_dir_name}/{rel}"));
                        continue;
                    }
                }
                if canonical != rel {
                    if let Ok(target) = safe_child(&restored_base, &canonical) {
                        if target.is_file() {
                            *v = Value::String(format!("{COVER_MARKER}{restored_dir_name}/{canonical}"));
                            continue;
                        }
                    }
                }
                // 远端备份不含封面时，若本机已有同规范路径封面，自动指回现存本地封面
                if let Some((existing_rel, _)) = resolve_existing_cover_file(covers_root, rel) {
                    *v = Value::String(format!("{COVER_MARKER}{existing_rel}"));
                } else if !s.starts_with(COVER_MARKER) {
                    *v = Value::String(format!("{COVER_MARKER}{canonical}"));
                }
            }
        }
    }
    out
}

fn summary_from_database(
    db_json: &Row,
    cover_count: usize,
    created_at: String,
    data_fingerprint: String,
    format_version: i64,
    fingerprint_matches: Option<bool>,
) -> BackupSummary {
    let count_table = |t: &str| db_json.get(t).and_then(|v| v.as_array()).map_or(0, Vec::len);
    let mut anime_count = 0usize;
    let mut book_count = 0usize;
    if let Some(arr) = db_json.get("animes").and_then(|v| v.as_array()) {
        for item in arr {
            let st = item
                .as_object()
                .and_then(|o| o.get("subjectType"))
                .and_then(|v| v.as_str());
            if normalize_subject_type(st) == "book" {
                book_count += 1;
            } else {
                anime_count += 1;
            }
        }
    }
    BackupSummary {
        anime_count,
        book_count,
        game_count: count_table("games"),
        audio_count: count_table("audio_works"),
        tag_count: count_table("tags"),
        character_count: count_table("characters"),
        group_count: count_table("character_groups"),
        cover_count,
        created_at,
        data_fingerprint,
        format_version,
        fingerprint_matches,
    }
}

pub fn safe_child(root: &Path, relative: &str) -> Result<PathBuf> {
    let cleaned = relative.replace('\\', "/");
    let rel_path = Path::new(&cleaned);
    let mut built = root.to_path_buf();
    for comp in rel_path.components() {
        match comp {
            Component::Normal(part) => built.push(part),
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(StudioError::BackupFormat(format!("备份包含不安全路径：{relative}")));
            }
        }
    }
    Ok(built)
}

fn list_files_recursive(root: &Path) -> Result<Vec<PathBuf>> {
    if !root.is_dir() {
        return Ok(Vec::new());
    }
    let mut files = Vec::new();
    fn walk(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let ft = entry.file_type()?;
            if ft.is_symlink() {
                continue;
            }
            let p = entry.path();
            if ft.is_dir() {
                walk(&p, out)?;
            } else if ft.is_file() {
                out.push(p);
            }
        }
        Ok(())
    }
    walk(root, &mut files)?;
    files.sort();
    Ok(files)
}

fn to_posix(path: &Path) -> String {
    path.components()
        .filter_map(|c| match c {
            Component::Normal(s) => Some(s.to_string_lossy().into_owned()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

fn relative_posix(root: &Path, file: &Path) -> Result<String> {
    let rel = file
        .strip_prefix(root)
        .map_err(|_| StudioError::BackupFormat("文件路径越出预期根目录".into()))?;
    Ok(to_posix(rel))
}
