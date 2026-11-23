use crate::schema::init_sync_db;
use crate::{Result, StudioError};
use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use uuid::Uuid;

pub const DATA_POINTER_FILE: &str = "data-location.json";
pub const LEGACY_ELECTRON_DIR_NAME: &str = "animeow-desktop";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyDiscovery {
    pub default_user_data_dir: String,
    pub pointer_target_dir: Option<String>,
    pub effective_data_dir: String,
    pub database_exists: bool,
    pub anime_count: i64,
    pub book_count: i64,
    pub game_count: i64,
    pub audio_count: i64,
    pub character_count: i64,
    pub cover_file_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyImportReport {
    pub source_dir: String,
    pub studio_dir: String,
    pub anime_count: i64,
    pub book_count: i64,
    pub game_count: i64,
    pub audio_count: i64,
    pub character_count: i64,
    pub copied_covers: usize,
    pub copied_branding: usize,
}

#[derive(Deserialize)]
struct DataLocationPointer {
    #[serde(rename = "dataDir")]
    data_dir: Option<String>,
}

/// 解析旧版 Electron `data-location.json` 指针文件；缺失、损坏、相对路径或目标不可读时安全回退默认目录
pub fn read_data_pointer(default_user_data_dir: &Path) -> Option<PathBuf> {
    let pointer_path = default_user_data_dir.join(DATA_POINTER_FILE);
    let raw = std::fs::read_to_string(pointer_path).ok()?;
    let parsed: DataLocationPointer = serde_json::from_str(&raw).ok()?;
    let target_str = parsed.data_dir?.trim().to_string();
    if target_str.is_empty() {
        return None;
    }
    let target = PathBuf::from(&target_str);
    if !target.is_absolute() {
        return None;
    }
    Some(target)
}

/// 在不修改原目录任何字节（严格 READ_ONLY）的前提下，发现并统计旧版 Electron 数据目录
pub fn discover_legacy_electron_data(appdata_roaming_dir: &Path) -> LegacyDiscovery {
    let default_dir = appdata_roaming_dir.join(LEGACY_ELECTRON_DIR_NAME);
    let pointer = read_data_pointer(&default_dir);
    let effective = match &pointer {
        Some(target) if target.exists() => target.clone(),
        _ => default_dir.clone(),
    };

    let db_path = effective.join("animeow.db");
    let mut anime_count = 0;
    let mut book_count = 0;
    let mut game_count = 0;
    let mut audio_count = 0;
    let mut character_count = 0;
    let mut db_exists = false;

    if db_path.is_file() {
        // 使用 SQLITE_OPEN_READ_ONLY + immutable URI 或只读连接，绝不在旧目录写入或改动日志
        if let Ok(conn) = Connection::open_with_flags(&db_path, OpenFlags::SQLITE_OPEN_READ_ONLY) {
            db_exists = true;
            anime_count = count_where(&conn, "animes", "deletedAt IS NULL AND COALESCE(subjectType, 'anime') = 'anime'");
            book_count = count_where(&conn, "animes", "deletedAt IS NULL AND subjectType IN ('book', 'manga', 'novel')");
            game_count = count_where(&conn, "games", "deletedAt IS NULL");
            audio_count = count_where(&conn, "audio_works", "deletedAt IS NULL");
            character_count = count_where(&conn, "characters", "1=1");
        }
    }

    let cover_file_count = count_files_recursive(&effective.join("covers"));

    LegacyDiscovery {
        default_user_data_dir: default_dir.to_string_lossy().into_owned(),
        pointer_target_dir: pointer.map(|p| p.to_string_lossy().into_owned()),
        effective_data_dir: effective.to_string_lossy().into_owned(),
        database_exists: db_exists,
        anime_count,
        book_count,
        game_count,
        audio_count,
        character_count,
        cover_file_count,
    }
}

/// 将旧版 Electron 数据目录以只读副本方式安全迁移到隔离的 Studio 目录。
/// 全程先写入临时暂存区，校验通过后再原子替换目标区；中断、报错或重复执行均绝不触碰原库与原封面。
pub fn import_legacy_electron_copy(source_dir: &Path, studio_dir: &Path) -> Result<LegacyImportReport> {
    return_if_same_dir(source_dir, studio_dir)?;
    let source_db = source_dir.join("animeow.db");
    if !source_db.is_file() {
        return Err(StudioError::InvalidInput(format!(
            "未在旧版目录找到数据库文件：{}",
            source_db.display()
        )));
    }

    std::fs::create_dir_all(studio_dir)?;
    let staging_dir = studio_dir.join(format!(".legacy_import_staging_{}", Uuid::new_v4()));
    std::fs::create_dir_all(&staging_dir)?;

    let result = (|| -> Result<LegacyImportReport> {
        let staged_db = staging_dir.join("animeow.db");

        // 使用 SQLite Online Backup API 从只读源连接安全复制一致性快照（兼容源库存在未合并 WAL 的情况，且不改源库）
        {
            let src_conn = Connection::open_with_flags(&source_db, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
            let mut dst_conn = Connection::open(&staged_db)?;
            {
                let backup = rusqlite::backup::Backup::new(&src_conn, &mut dst_conn)?;
                backup.run_to_completion(256, std::time::Duration::from_millis(5), None)?;
            }
        }

        // 在暂存副本上执行增量列补齐与完整性核验
        let (anime_count, book_count, game_count, audio_count, character_count) = {
            let upgraded = init_sync_db(&staged_db)?;
            let check: String = upgraded.query_row("PRAGMA quick_check", [], |r| r.get(0))?;
            if check.to_lowercase() != "ok" {
                return Err(StudioError::BackupFormat(format!("旧库副本完整性检查未通过：{check}")));
            }
            (
                count_where(&upgraded, "animes", "deletedAt IS NULL AND COALESCE(subjectType, 'anime') = 'anime'"),
                count_where(&upgraded, "animes", "deletedAt IS NULL AND subjectType IN ('book', 'manga', 'novel')"),
                count_where(&upgraded, "games", "deletedAt IS NULL"),
                count_where(&upgraded, "audio_works", "deletedAt IS NULL"),
                count_where(&upgraded, "characters", "1=1"),
            )
        };

        let staged_covers = staging_dir.join("covers");
        let copied_covers = copy_dir_recursive(&source_dir.join("covers"), &staged_covers)?;

        let staged_branding = staging_dir.join("branding");
        let copied_branding = copy_dir_recursive(&source_dir.join("branding"), &staged_branding)?;

        let staged_payload = staging_dir.join("native-backup-payload");
        let _ = copy_dir_recursive(&source_dir.join("native-backup-payload"), &staged_payload)?;

        // 提交切换至 Studio 隔离目录（清理目标旧 WAL/SHM 后原子替换）
        let target_db = studio_dir.join("animeow.db");
        let _ = std::fs::remove_file(studio_dir.join("animeow.db-wal"));
        let _ = std::fs::remove_file(studio_dir.join("animeow.db-shm"));
        if target_db.exists() {
            std::fs::remove_file(&target_db)?;
        }
        std::fs::rename(&staged_db, &target_db)?;

        replace_dir_if_present(&staged_covers, &studio_dir.join("covers"))?;
        replace_dir_if_present(&staged_branding, &studio_dir.join("branding"))?;
        replace_dir_if_present(&staged_payload, &studio_dir.join("native-backup-payload"))?;

        Ok(LegacyImportReport {
            source_dir: source_dir.to_string_lossy().into_owned(),
            studio_dir: studio_dir.to_string_lossy().into_owned(),
            anime_count,
            book_count,
            game_count,
            audio_count,
            character_count,
            copied_covers,
            copied_branding,
        })
    })();

    let _ = std::fs::remove_dir_all(&staging_dir);
    result
}

fn return_if_same_dir(a: &Path, b: &Path) -> Result<()> {
    if let (Ok(ca), Ok(cb)) = (a.canonicalize(), b.canonicalize()) {
        if ca == cb {
            return Err(StudioError::InvalidInput("源目录与 Studio 目标目录不能相同".into()));
        }
    }
    Ok(())
}

fn count_where(conn: &Connection, table: &str, clause: &str) -> i64 {
    conn.query_row(
        &format!("SELECT COUNT(*) FROM `{table}` WHERE {clause}"),
        [],
        |r| r.get(0),
    )
    .unwrap_or(0)
}

fn count_files_recursive(dir: &Path) -> usize {
    if !dir.is_dir() {
        return 0;
    }
    let mut total = 0;
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                total += count_files_recursive(&path);
            } else if path.is_file() {
                total += 1;
            }
        }
    }
    total
}

fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<usize> {
    if !src.is_dir() {
        return Ok(0);
    }
    std::fs::create_dir_all(dst)?;
    let mut count = 0;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        if file_type.is_symlink() {
            continue;
        }
        let target = dst.join(entry.file_name());
        if file_type.is_dir() {
            count += copy_dir_recursive(&entry.path(), &target)?;
        } else if file_type.is_file() {
            std::fs::copy(entry.path(), target)?;
            count += 1;
        }
    }
    Ok(count)
}

fn replace_dir_if_present(staged: &Path, target: &Path) -> Result<()> {
    if !staged.is_dir() {
        return Ok(());
    }
    if target.exists() {
        std::fs::remove_dir_all(target)?;
    }
    std::fs::rename(staged, target)?;
    Ok(())
}
