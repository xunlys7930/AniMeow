use animeow_core::backup::{
    export_native_backup, restore_native_backup, BackupPaths, RestoreSelection, RestoreStrategy,
};
use animeow_core::repository::{update_curator_cell, CellPatch};
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::{create_curator_item, record_play_session, NewCuratorItemInput};
use std::fs;
use tempfile::TempDir;
use zip::ZipArchive;

#[test]
fn progress_increment_auto_transitions_status_across_all_media_kinds() {
    let tmp = TempDir::new().unwrap();
    let sync_conn = init_sync_db(&tmp.path().join("animeow.db")).unwrap();
    let local_conn = init_local_db(&tmp.path().join("desktop_local.db")).unwrap();

    let book_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "book".into(),
            title: "转生史莱姆轻小说".into(),
            original_title: None,
            cover_url: None,
            status: Some("未看".into()),
            total_episodes: Some(12),
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    let anime_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "anime".into(),
            title: "败犬女主太多了！".into(),
            original_title: None,
            cover_url: None,
            status: Some("未看".into()),
            total_episodes: Some(12),
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    let game_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "game".into(),
            title: "命运石之门".into(),
            original_title: None,
            cover_url: None,
            status: Some("未玩".into()),
            total_episodes: None,
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    let game_session_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "game".into(),
            title: "白箱视觉小说".into(),
            original_title: None,
            cover_url: None,
            status: Some("想玩".into()),
            total_episodes: None,
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    let audio_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "audio".into(),
            title: "耳语音声测试".into(),
            original_title: None,
            cover_url: None,
            status: Some("未听".into()),
            total_episodes: Some(600),
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    // 1) 小说从「未看」记录 1 话 -> 自动变为「在看」
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "book".into(),
            id: book_id,
            progress_primary: Some(1),
            ..Default::default()
        },
    )
    .unwrap();
    let book_status: String = sync_conn
        .query_row("SELECT status FROM animes WHERE id = ?1", [book_id], |r| r.get(0))
        .unwrap();
    assert_eq!(book_status, "在看");

    // 2) 动画直接记录满总集数 -> 自动变为「看完」
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "anime".into(),
            id: anime_id,
            progress_primary: Some(12),
            ..Default::default()
        },
    )
    .unwrap();
    let anime_status: String = sync_conn
        .query_row("SELECT status FROM animes WHERE id = ?1", [anime_id], |r| r.get(0))
        .unwrap();
    assert_eq!(anime_status, "看完");

    // 3) 游戏从「未玩」累加时长 -> 自动变为「在玩」
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "game".into(),
            id: game_id,
            progress_primary: Some(30),
            ..Default::default()
        },
    )
    .unwrap();
    let game_status: String = sync_conn
        .query_row("SELECT status FROM games WHERE id = ?1", [game_id], |r| r.get(0))
        .unwrap();
    assert_eq!(game_status, "在玩");

    // 4) 进程伴侣记录游玩会话 -> 「想玩」自动变为「在玩」
    record_play_session(
        &sync_conn,
        &local_conn,
        "game",
        game_session_id,
        None,
        "2026-11-19T10:00:00Z",
        "2026-11-19T10:25:00Z",
        1500,
        None,
    )
    .unwrap();
    let game_session_status: String = sync_conn
        .query_row("SELECT status FROM games WHERE id = ?1", [game_session_id], |r| r.get(0))
        .unwrap();
    assert_eq!(game_session_status, "在玩");

    // 5) 音声从「未听」记录收听时长 -> 自动变为「在听」
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "audio".into(),
            id: audio_id,
            progress_primary: Some(120),
            ..Default::default()
        },
    )
    .unwrap();
    let audio_status: String = sync_conn
        .query_row(
            "SELECT status FROM audio_works WHERE id = ?1",
            [audio_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(audio_status, "在听");
}

#[test]
fn custom_cover_survives_no_cover_restore_and_avoids_nested_restored_prefixes() {
    let tmp = TempDir::new().unwrap();
    let db_path = tmp.path().join("animeow.db");
    let covers_dir = tmp.path().join("covers");
    let audio_dir = tmp.path().join("audio");
    fs::create_dir_all(covers_dir.join("restored_prev").join("custom")).unwrap();
    fs::create_dir_all(&audio_dir).unwrap();

    let local_cover = covers_dir
        .join("restored_prev")
        .join("custom")
        .join("my_novel.jpg");
    fs::write(&local_cover, b"JPEG-CUSTOM-COVER-BYTES").unwrap();

    let mut sync_conn = init_sync_db(&db_path).unwrap();
    let item_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "book".into(),
            title: "自定义封面轻小说".into(),
            original_title: None,
            cover_url: Some("animeow-cover://restored_prev/custom/my_novel.jpg".into()),
            status: Some("在看".into()),
            total_episodes: Some(10),
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    let paths = BackupPaths::from_studio_root(tmp.path());

    // 1) 导出不含封面的轻量云同步包，然后覆盖恢复回本机（本机磁盘已有 restored_prev 下的自定义封面）
    let no_cover_zip = tmp.path().join("cloud_no_covers.animeow.zip");
    export_native_backup(&sync_conn, &no_cover_zip, &paths, false, false).unwrap();

    restore_native_backup(
        &mut sync_conn,
        &no_cover_zip,
        &paths,
        RestoreSelection::default(),
        RestoreStrategy::Replace,
    )
    .unwrap();

    let restored_url: String = sync_conn
        .query_row("SELECT coverUrl FROM animes WHERE id = ?1", [item_id], |r| r.get(0))
        .unwrap();
    assert_eq!(
        restored_url, "animeow-cover://restored_prev/custom/my_novel.jpg",
        "无封面恢复后应自动定位回本机现存的 restored_prev 自定义封面"
    );
    let resolved = animeow_core::backup::resolve_existing_cover_file(
        &paths.covers_root,
        restored_url.strip_prefix("animeow-cover://").unwrap(),
    );
    assert!(
        resolved.as_ref().is_some_and(|(_, p)| p.is_file()),
        "恢复后的封面路径必须能解析到磁盘上真实存在的文件"
    );

    // 2) 带封面连续导出 -> 恢复 -> 再导出两轮，确认不会产生 restored_xxx/restored_xxx 嵌套
    let with_cover_zip1 = tmp.path().join("round1.animeow.zip");
    export_native_backup(&sync_conn, &with_cover_zip1, &paths, true, false).unwrap();
    restore_native_backup(
        &mut sync_conn,
        &with_cover_zip1,
        &paths,
        RestoreSelection::default(),
        RestoreStrategy::Replace,
    )
    .unwrap();

    let with_cover_zip2 = tmp.path().join("round2.animeow.zip");
    export_native_backup(&sync_conn, &with_cover_zip2, &paths, true, false).unwrap();

    let archive = ZipArchive::new(fs::File::open(&with_cover_zip2).unwrap()).unwrap();
    let cover_entries: Vec<String> = (0..archive.len())
        .filter_map(|i| {
            let name = archive.name_for_index(i)?.to_string();
            if name.starts_with("covers/") {
                Some(name)
            } else {
                None
            }
        })
        .collect();
    assert!(!cover_entries.is_empty(), "第二轮导出应包含封面文件");
    for entry in &cover_entries {
        assert!(
            !entry.contains("restored_"),
            "导出封面路径不应包含或嵌套 restored_ 前缀，实际为: {entry}"
        );
    }
}

#[test]
fn cross_device_status_changed_at_tag_sort_order_and_settings_passthrough() {
    let tmp = TempDir::new().unwrap();
    let db_path = tmp.path().join("animeow.db");
    let mut sync_conn = init_sync_db(&db_path).unwrap();
    let paths = BackupPaths::from_studio_root(tmp.path());

    // 构造来自手机端的备份包（含 animes.statusChangedAt、tags.sortOrder、手机端外观设置与桌面端设置）
    let mobile_zip = tmp.path().join("mobile_origin.animeow.zip");
    {
        let file = fs::File::create(&mobile_zip).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let opts = zip::write::SimpleFileOptions::default();
        let db_json = serde_json::json!({
            "watch_statuses": [{ "id": 1, "name": "在看", "color": 4280391411_i64, "sortOrder": 0 }],
            "animes": [{
                "id": 1,
                "title": "跨端同步验证动画",
                "status": "在看",
                "watchedEpisodes": 3,
                "totalEpisodes": 12,
                "subjectType": "anime",
                "statusChangedAt": 1763500000123_i64
            }],
            "tags": [{
                "id": 1,
                "name": "治愈",
                "color": 4283215696_i64,
                "blocked": 0,
                "sortOrder": 7
            }],
            "anime_tags": [{ "animeId": 1, "tagId": 1 }]
        });
        let settings_json = serde_json::json!({
            "appearance": { "themeMode": "DARK", "homeLayout": "GRID" },
            "desktopStudio": { "theme": "cassette", "upscaleMode": "ai_efficiency", "syncDesktopSettings": true }
        });
        let manifest_json = serde_json::json!({
            "type": animeow_core::backup::NATIVE_BACKUP_FORMAT_TYPE,
            "version": 11,
            "createdAt": "2026-11-19T06:00:00Z"
        });
        use std::io::Write;
        zip.start_file("manifest.json", opts).unwrap();
        zip.write_all(manifest_json.to_string().as_bytes()).unwrap();
        zip.start_file("database.json", opts).unwrap();
        zip.write_all(db_json.to_string().as_bytes()).unwrap();
        zip.start_file("settings.json", opts).unwrap();
        zip.write_all(settings_json.to_string().as_bytes()).unwrap();
        zip.start_file("configuration_profiles.json", opts).unwrap();
        zip.write_all(b"{}").unwrap();
        zip.finish().unwrap();
    }

    // 1) 桌面端恢复手机备份
    restore_native_backup(
        &mut sync_conn,
        &mobile_zip,
        &paths,
        RestoreSelection::default(),
        RestoreStrategy::Replace,
    )
    .unwrap();

    let status_changed_at: Option<i64> = sync_conn
        .query_row("SELECT statusChangedAt FROM animes WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    let tag_sort_order: Option<i64> = sync_conn
        .query_row("SELECT sortOrder FROM tags WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    assert_eq!(status_changed_at, Some(1763500000123));
    assert_eq!(tag_sort_order, Some(7));

    let loaded_desktop = animeow_core::backup::load_desktop_settings(&paths).unwrap().unwrap();
    assert_eq!(loaded_desktop["theme"], "cassette");
    assert_eq!(loaded_desktop["upscaleMode"], "ai_efficiency");

    // 2) 桌面端修改自己的设置并重新导出，确认手机端 appearance、statusChangedAt、tags.sortOrder 和新桌面设置全保留
    animeow_core::backup::save_desktop_settings(
        &paths,
        &serde_json::json!({
            "theme": "hinoki",
            "upscaleMode": "ai_quality",
            "syncDesktopSettings": true
        }),
    )
    .unwrap();

    let desktop_reexport = tmp.path().join("desktop_reexport.animeow.zip");
    export_native_backup(&sync_conn, &desktop_reexport, &paths, false, false).unwrap();

    let mut archive = ZipArchive::new(fs::File::open(&desktop_reexport).unwrap()).unwrap();
    let mut db_text = String::new();
    std::io::Read::read_to_string(&mut archive.by_name("database.json").unwrap(), &mut db_text)
        .unwrap();
    let mut settings_text = String::new();
    std::io::Read::read_to_string(
        &mut archive.by_name("settings.json").unwrap(),
        &mut settings_text,
    )
    .unwrap();

    let exported_db: serde_json::Value = serde_json::from_str(&db_text).unwrap();
    let exported_settings: serde_json::Value = serde_json::from_str(&settings_text).unwrap();

    assert_eq!(exported_db["animes"][0]["statusChangedAt"], 1763500000123_i64);
    assert_eq!(exported_db["tags"][0]["sortOrder"], 7);
    assert_eq!(exported_settings["appearance"]["themeMode"], "DARK");
    assert_eq!(exported_settings["desktopStudio"]["theme"], "hinoki");
    assert_eq!(exported_settings["desktopStudio"]["upscaleMode"], "ai_quality");
}

#[test]
fn completed_status_auto_fills_total_episodes() {
    use animeow_core::repository::{batch_update_status, update_curator_cell, CellPatch};
    use animeow_core::studio_features::{create_curator_item, NewCuratorItemInput};

    let tmp = tempfile::tempdir().unwrap();
    let mut sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();

    let make_input = |title: &str, status: &str, total: i64| NewCuratorItemInput {
        kind: "anime".into(),
        title: title.into(),
        original_title: None,
        cover_url: None,
        status: Some(status.into()),
        total_episodes: Some(total),
        studio_or_circle: None,
        release_date: None,
        public_score: None,
        external_source: None,
        external_id: None,
        work_code: None,
        voice_actors: None,
        synopsis: None,
        media_format: None,
    };

    // 1. 新建「看完」直接自动满集 28/28
    let created_id = create_curator_item(&sync_conn, &make_input("葬送的芙莉莲", "看完", 28)).unwrap();
    let ep_after_create: i64 = sync_conn
        .query_row("SELECT watchedEpisodes FROM animes WHERE id = ?1", [created_id], |r| r.get(0))
        .unwrap();
    assert_eq!(ep_after_create, 28);

    // 2. 在看(3/12) -> 单条改为「看完」自动补满为 12/12
    let id2 = create_curator_item(&sync_conn, &make_input("孤独摇滚！", "在看", 12)).unwrap();
    sync_conn
        .execute("UPDATE animes SET watchedEpisodes = 3 WHERE id = ?1", [id2])
        .unwrap();
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "anime".into(),
            id: id2,
            status: Some("看完".into()),
            ..Default::default()
        },
    )
    .unwrap();
    let ep_after_single: i64 = sync_conn
        .query_row("SELECT watchedEpisodes FROM animes WHERE id = ?1", [id2], |r| r.get(0))
        .unwrap();
    assert_eq!(ep_after_single, 12);

    // 3. 批量改为「看完」自动补满为 24/24
    let id3 = create_curator_item(&sync_conn, &make_input("命运石之门", "在看", 24)).unwrap();
    sync_conn
        .execute("UPDATE animes SET watchedEpisodes = 5 WHERE id = ?1", [id3])
        .unwrap();
    batch_update_status(&mut sync_conn, &[("anime".into(), id3)], "看完").unwrap();
    let ep_after_batch: i64 = sync_conn
        .query_row("SELECT watchedEpisodes FROM animes WHERE id = ?1", [id3], |r| r.get(0))
        .unwrap();
    assert_eq!(ep_after_batch, 24);
}

#[test]
fn duplicate_detection_catches_punctuation_without_season_false_positives() {
    use animeow_core::studio_features::query_duplicate_groups;

    let tmp = tempfile::tempdir().unwrap();
    let sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();

    // 应该被识别为疑似重复：孤独摇滚（手动） vs 孤独摇滚！（Bangumi）
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, subjectType, watchedEpisodes, totalEpisodes)
             VALUES ('孤独摇滚', '看完', 'anime', 12, 12)",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, subjectType, watchedEpisodes, totalEpisodes, externalSource, externalId)
             VALUES ('孤独摇滚！', '在看', 'anime', 2, 12, 'bangumi', '328609')",
            [],
        )
        .unwrap();

    // 不应该误报重复：一季感叹号 vs 二季双感叹号、问号续季（如辉夜? / 点兔??）、撇号感叹号、不同季、TV vs 剧场版、同源不同 ID
    let non_dup_pairs: [(&str, Option<&str>, Option<&str>); 16] = [
        ("轻音少女！", None, None),
        ("轻音少女！！", None, None),
        ("辉夜大小姐想让我告白～天才们的恋爱头脑战～", None, None),
        ("辉夜大小姐想让我告白?～天才们的恋爱头脑战～", None, None),
        ("请问您今天要来点兔子吗？", None, None),
        ("请问您今天要来点兔子吗？？", None, None),
        ("K-ON!", None, None),
        ("K-ON!!", None, None),
        ("WORKING!!", None, None),
        ("WORKING'!!", None, None),
        ("咒术回战", None, None),
        ("咒术回战 第二季", None, None),
        ("鬼灭之刃", None, None),
        ("鬼灭之刃 剧场版", None, None),
        ("同源作品A", Some("bangumi"), Some("10001")),
        ("同源作品A！", Some("bangumi"), Some("10002")),
    ];
    for (title, src, eid) in non_dup_pairs {
        sync_conn
            .execute(
                "INSERT INTO animes (title, status, subjectType, externalSource, externalId)
                 VALUES (?1, '在看', 'anime', ?2, ?3)",
                rusqlite::params![title, src, eid],
            )
            .unwrap();
    }

    let groups = query_duplicate_groups(&sync_conn).unwrap();
    assert_eq!(
        groups.len(),
        1,
        "expected only 1 duplicate group (孤独摇滚), got: {:?}",
        groups.iter().map(|g| (&g.title, &g.reason)).collect::<Vec<_>>()
    );
    assert_eq!(groups[0].confidence, "suspected");
    assert_eq!(groups[0].candidates.len(), 2);
}

#[test]
fn series_auto_suggestions_clusters_seasons_cross_media_and_existing_series() {
    use animeow_core::studio_features::{batch_assign_series, query_series_suggestions};

    let tmp = tempfile::tempdir().unwrap();
    let mut sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();

    // 1. 已有系列「进击的巨人」+ 未归类续季
    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('进击的巨人', '看完', 'anime')", [])
        .unwrap();
    let aot1 = sync_conn.last_insert_rowid();
    batch_assign_series(&mut sync_conn, &[("anime".into(), aot1)], "进击的巨人", Some("本篇")).unwrap();
    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('进击的巨人 第三季', '看完', 'anime')", [])
        .unwrap();

    // 2. 感叹号续季 + 剧场版聚类为「轻音少女」
    for t in ["轻音少女！", "轻音少女！！", "轻音少女 剧场版"] {
        sync_conn
            .execute("INSERT INTO animes (title, status, subjectType) VALUES (?1, '看完', 'anime')", [t])
            .unwrap();
    }

    // 3. 所有格系列名「凉宫春日」
    for t in ["凉宫春日的忧郁", "凉宫春日的消失", "凉宫春日系列"] {
        sync_conn
            .execute("INSERT INTO animes (title, status, subjectType) VALUES (?1, '看完', 'anime')", [t])
            .unwrap();
    }

    // 4. 附带卷号/小数「安达与岛村」
    for t in ["安达与岛村", "安达与岛村99.9"] {
        sync_conn
            .execute("INSERT INTO animes (title, status, subjectType) VALUES (?1, '在读', 'book')", [t])
            .unwrap();
    }

    // 5. 跨载体（动画 + 书籍）「无职转生」
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, subjectType) VALUES ('无职转生 ～到了异世界就拿出真本事～', '看完', 'anime')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, subjectType) VALUES ('无职转生 ～到了异世界就拿出真本事～', '在读', 'book')",
            [],
        )
        .unwrap();

    // 6. 纯重复（孤独摇滚 vs 孤独摇滚！）不应被误推成新系列
    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('孤独摇滚', '看完', 'anime')", [])
        .unwrap();
    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('孤独摇滚！', '在看', 'anime')", [])
        .unwrap();

    let groups = query_series_suggestions(&sync_conn).unwrap();
    let names: Vec<&str> = groups.iter().map(|g| g.series_name.as_str()).collect();
    assert!(names.contains(&"进击的巨人"), "missing 进击的巨人 in {names:?}");
    assert!(names.contains(&"轻音少女"), "missing 轻音少女 in {names:?}");
    assert!(names.contains(&"凉宫春日"), "missing 凉宫春日 in {names:?}");
    assert!(names.contains(&"安达与岛村"), "missing 安达与岛村 in {names:?}");
    assert!(names.iter().any(|n| n.starts_with("无职转生")), "missing 无职转生 in {names:?}");
    assert!(!names.contains(&"孤独摇滚"), "pure duplicate 孤独摇滚 should not be suggested as series: {names:?}");

    let aot = groups.iter().find(|g| g.series_name == "进击的巨人").unwrap();
    assert!(aot.existing_series);
    assert_eq!(aot.unassigned_count, 1);
    assert_eq!(aot.members.len(), 2);

    let kon = groups.iter().find(|g| g.series_name == "轻音少女").unwrap();
    assert_eq!(kon.members.len(), 3);
    assert_eq!(kon.unassigned_count, 3);
}

#[test]
fn cloud_export_skips_orphan_covers_and_fonts() {
    use animeow_core::backup::export_native_backup_with_options;

    let tmp = tempfile::tempdir().unwrap();
    let studio_root = tmp.path().join("studio");
    let paths = BackupPaths::from_studio_root(&studio_root);
    fs::create_dir_all(paths.covers_root.join("legacy_12345")).unwrap();
    fs::create_dir_all(paths.branding_root.join("fonts")).unwrap();

    let referenced_cover = paths.covers_root.join("used_cover.jpg");
    let orphan_cover = paths.covers_root.join("legacy_12345").join("orphan.jpg");
    let font_file = paths.branding_root.join("fonts").join("custom.ttf");
    fs::write(&referenced_cover, b"referenced-jpg-bytes").unwrap();
    fs::write(&orphan_cover, vec![0xABu8; 64 * 1024]).unwrap();
    fs::write(&font_file, vec![0xCDu8; 128 * 1024]).unwrap();

    let sync_conn = init_sync_db(&studio_root.join("animeow.db")).unwrap();
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, coverUrl) VALUES ('有封面作品', '在看', ?1)",
            [referenced_cover.to_string_lossy().to_string()],
        )
        .unwrap();

    // 1) 云端模式：仅打包数据库实际引用的封面，跳过孤儿封面和字体
    let cloud_zip = tmp.path().join("cloud_referenced_only.animeow.zip");
    let cloud_summary = export_native_backup_with_options(
        &sync_conn,
        &cloud_zip,
        &paths,
        true,
        false,
        true,
    )
    .unwrap();
    assert_eq!(cloud_summary.cover_count, 1);

    let archive = ZipArchive::new(fs::File::open(&cloud_zip).unwrap()).unwrap();
    let names: Vec<String> = (0..archive.len())
        .map(|i| archive.name_for_index(i).unwrap().to_string())
        .collect();
    assert!(names.contains(&"covers/used_cover.jpg".to_string()), "missing referenced cover: {names:?}");
    assert!(!names.iter().any(|n| n.contains("orphan.jpg")), "orphan cover leaked into cloud zip: {names:?}");
    assert!(!names.iter().any(|n| n.starts_with("branding/fonts/")), "font leaked into cloud zip: {names:?}");

    // 2) 当数据库无任何本地封面引用时，即使 covers/ 残留孤儿图也应打包 0 张封面
    sync_conn
        .execute("UPDATE animes SET coverUrl = 'https://lain.bgm.tv/pic/cover/c/example.jpg'", [])
        .unwrap();
    let zero_ref_zip = tmp.path().join("cloud_zero_refs.animeow.zip");
    let zero_summary = export_native_backup_with_options(
        &sync_conn,
        &zero_ref_zip,
        &paths,
        true,
        false,
        true,
    )
    .unwrap();
    assert_eq!(zero_summary.cover_count, 0);
}

#[test]
fn series_clustering_merges_split_series_and_handles_mid_season_titles() {
    use animeow_core::studio_features::{batch_assign_series, query_series_suggestions};

    let tmp = tempfile::tempdir().unwrap();
    let mut sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();

    // 1. 复现用户真实场景：无职转生已被拆成两个系列（带中段「第二季」）
    fn insert_anime(conn: &rusqlite::Connection, title: &str, subj: &str) -> i64 {
        conn.execute(
            "INSERT INTO animes (title, status, subjectType) VALUES (?1, '看完', ?2)",
            rusqlite::params![title, subj],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    let m1 = insert_anime(&sync_conn, "无职转生～到了异世界就拿出真本事～", "anime");
    let m2 = insert_anime(&sync_conn, "无职转生～到了异世界就拿出真本事～ 第2部分", "anime");
    let m3 = insert_anime(&sync_conn, "无职转生 第二季 ～到了异世界就拿出真本事～", "anime");
    let m4 = insert_anime(&sync_conn, "无职转生 第二季 ～到了异世界就拿出真本事～ 第2部分", "anime");
    batch_assign_series(
        &mut sync_conn,
        &[("anime".into(), m1), ("anime".into(), m2)],
        "无职转生～到了异世界就拿出真本事",
        Some("本篇"),
    )
    .unwrap();
    batch_assign_series(
        &mut sync_conn,
        &[("anime".into(), m3), ("anime".into(), m4)],
        "无职转生 第二季 ～到了异世界就拿出真本事",
        Some("续作"),
    )
    .unwrap();

    // 2. 凉宫春日 vs 凉宫春日的忧郁
    let h1 = insert_anime(&sync_conn, "凉宫春日的消失", "anime");
    let h2 = insert_anime(&sync_conn, "凉宫春日的忧郁", "anime");
    batch_assign_series(&mut sync_conn, &[("anime".into(), h1)], "凉宫春日", Some("剧场版")).unwrap();
    batch_assign_series(&mut sync_conn, &[("anime".into(), h2)], "凉宫春日的忧郁", Some("本篇")).unwrap();

    // 3. fate vs Fate/stay night [Unlimited Blade Works]
    let f1 = insert_anime(&sync_conn, "Fate/Zero", "anime");
    let f2 = insert_anime(&sync_conn, "Fate/stay night [Unlimited Blade Works]", "anime");
    batch_assign_series(&mut sync_conn, &[("anime".into(), f1)], "fate", Some("前传")).unwrap();
    batch_assign_series(
        &mut sync_conn,
        &[("anime".into(), f2)],
        "Fate/stay night [Unlimited Blade Works]",
        Some("本篇"),
    )
    .unwrap();

    let groups = query_series_suggestions(&sync_conn).unwrap();

    let mushoku = groups
        .iter()
        .find(|g| g.series_name.starts_with("无职转生"))
        .expect("should suggest merging split 无职转生 series");
    assert_eq!(mushoku.members.len(), 4);
    assert_eq!(mushoku.unassigned_count, 2);

    let haruhi = groups
        .iter()
        .find(|g| g.series_name == "凉宫春日")
        .expect("should suggest merging 凉宫春日的忧郁 into 凉宫春日");
    assert_eq!(haruhi.members.len(), 2);
    assert_eq!(haruhi.unassigned_count, 1);

    let fate = groups
        .iter()
        .find(|g| g.series_name.eq_ignore_ascii_case("fate"))
        .expect("should suggest merging Fate/stay night UBW into fate");
    assert_eq!(fate.members.len(), 2);
    assert_eq!(fate.unassigned_count, 1);

    // 4. 应用归类合并后，被搬空的旧拆分系列应自动清理，不再残留空系列
    batch_assign_series(
        &mut sync_conn,
        &[("anime".into(), m3), ("anime".into(), m4)],
        &mushoku.series_name,
        Some("续作"),
    )
    .unwrap();
    let old_split_exists: i64 = sync_conn
        .query_row(
            "SELECT COUNT(*) FROM series WHERE name = '无职转生 第二季 ～到了异世界就拿出真本事'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(old_split_exists, 0);
}

#[test]
fn series_tags_and_characters_management_with_canvas_graph() {
    use animeow_core::schema::init_local_db;
    use animeow_core::studio_features::{
        batch_assign_series, batch_delete_characters, delete_character, delete_character_relation,
        delete_series, delete_tag, link_character_work, list_characters_manage, list_series_manage,
        list_tags_manage, list_work_characters, merge_series, merge_tags, purge_unused_tags,
        query_universe_graph, remove_member_from_series, rename_series, rename_tag,
        set_character_tags, toggle_tag_blocked, update_series_member_role, upsert_character,
        upsert_character_relation, UpsertCharacterInput, UpsertCharacterRelationInput,
    };

    let tmp = tempfile::tempdir().unwrap();
    let mut sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();
    let local_conn = init_local_db(&tmp.path().join("local.db")).unwrap();

    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('葬送的芙莉莲', '看完', 'anime')", [])
        .unwrap();
    let a1 = sync_conn.last_insert_rowid();
    sync_conn
        .execute("INSERT INTO animes (title, status, subjectType) VALUES ('葬送的芙莉莲 第二季', '想看', 'anime')", [])
        .unwrap();
    let a2 = sync_conn.last_insert_rowid();

    // 1. 系列管理：创建、重命名、修改成员关系、合并、移除成员、解散
    batch_assign_series(&mut sync_conn, &[("anime".into(), a1)], "芙莉莲A", Some("本篇")).unwrap();
    batch_assign_series(&mut sync_conn, &[("anime".into(), a2)], "芙莉莲B", Some("续作")).unwrap();
    let s_list = list_series_manage(&sync_conn).unwrap();
    assert_eq!(s_list.len(), 2);
    let sid_a = s_list.iter().find(|s| s.name == "芙莉莲A").unwrap().id;
    let sid_b = s_list.iter().find(|s| s.name == "芙莉莲B").unwrap().id;

    rename_series(&mut sync_conn, sid_a, "葬送的芙莉莲").unwrap();
    let moved = merge_series(&mut sync_conn, sid_b, sid_a).unwrap();
    assert_eq!(moved, 1);
    update_series_member_role(&mut sync_conn, sid_a, "anime", a2, "第二季续作").unwrap();

    let s_after = list_series_manage(&sync_conn).unwrap();
    assert_eq!(s_after.len(), 1);
    assert_eq!(s_after[0].name, "葬送的芙莉莲");
    assert_eq!(s_after[0].members.len(), 2);
    assert!(s_after[0].members.iter().any(|m| m.id == a2 && m.series_role.as_deref() == Some("第二季续作")));

    remove_member_from_series(&mut sync_conn, sid_a, "anime", a2).unwrap();
    assert_eq!(list_series_manage(&sync_conn).unwrap()[0].members.len(), 1);
    delete_series(&mut sync_conn, sid_a).unwrap();
    assert!(list_series_manage(&sync_conn).unwrap().is_empty());

    // 2. 标签管理：列出、重命名、合并、屏蔽、删除、清理未使用
    sync_conn.execute("INSERT INTO tags (name) VALUES ('奇幻'), ('魔幻'), ('闲置标签')", []).unwrap();
    let t1: i64 = sync_conn.query_row("SELECT id FROM tags WHERE name = '奇幻'", [], |r| r.get(0)).unwrap();
    let t2: i64 = sync_conn.query_row("SELECT id FROM tags WHERE name = '魔幻'", [], |r| r.get(0)).unwrap();
    sync_conn.execute("INSERT INTO anime_tags (animeId, tagId) VALUES (?1, ?2), (?3, ?4)", rusqlite::params![a1, t1, a2, t2]).unwrap();

    toggle_tag_blocked(&sync_conn, t1, true).unwrap();
    let tags = list_tags_manage(&sync_conn).unwrap();
    assert_eq!(tags.len(), 3);
    assert!(tags.iter().find(|t| t.id == t1).unwrap().blocked);

    rename_tag(&mut sync_conn, t2, "奇幻冒险").unwrap();
    let merged_tags = merge_tags(&mut sync_conn, t2, t1).unwrap();
    assert_eq!(merged_tags, 1);
    let purged = purge_unused_tags(&mut sync_conn).unwrap();
    assert_eq!(purged, 1);
    let tags_after = list_tags_manage(&sync_conn).unwrap();
    assert_eq!(tags_after.len(), 1);
    assert_eq!(tags_after[0].usage_count, 2);
    delete_tag(&mut sync_conn, t1).unwrap();
    assert!(list_tags_manage(&sync_conn).unwrap().is_empty());

    // 3. 角色管理 + 角色↔动画 + 角色↔角色 + 画布「角色关系图谱」
    let c1 = upsert_character(
        &sync_conn,
        &UpsertCharacterInput {
            id: None,
            bgm_id: None,
            anilist_id: None,
            name: "フリーレン".into(),
            name_cn: Some("芙莉莲".into()),
            image_url: None,
            summary: Some("精灵魔法使".into()),
            gender: Some("女".into()),
            birth_month: None,
            birth_day: None,
            blood_type: None,
            rating: Some(98),
            review: None,
        },
    )
    .unwrap();
    let c2 = upsert_character(
        &sync_conn,
        &UpsertCharacterInput {
            id: None,
            bgm_id: None,
            anilist_id: None,
            name: "フェルン".into(),
            name_cn: Some("菲伦".into()),
            image_url: None,
            summary: None,
            gender: Some("女".into()),
            birth_month: None,
            birth_day: None,
            blood_type: None,
            rating: Some(92),
            review: None,
        },
    )
    .unwrap();

    link_character_work(&sync_conn, c1, a1, Some("主角"), Some("种崎敦美")).unwrap();
    link_character_work(&sync_conn, c2, a1, Some("主要角色"), Some("市之濑加那")).unwrap();
    let rel_id = upsert_character_relation(
        &sync_conn,
        &UpsertCharacterRelationInput {
            id: None,
            source_character_id: c1,
            target_character_id: c2,
            relation_type: "师徒".into(),
            source_role: Some("师父".into()),
            target_role: Some("弟子".into()),
            note: Some("同行旅伴".into()),
            strength: Some(5),
        },
    )
    .unwrap();

    let chars = list_characters_manage(&sync_conn).unwrap();
    assert_eq!(chars.len(), 2);
    assert_eq!(chars[0].name_cn, "芙莉莲");
    assert_eq!(chars[0].works.len(), 1);
    assert_eq!(chars[0].relations.len(), 1);

    let graph = query_universe_graph(&sync_conn, &local_conn, "characters").unwrap();
    assert_eq!(graph.mode, "characters");
    // 1 部作品 + 2 位角色 = 3 节点；2 条作品出演边 + 1 条师徒关系边 = 3 条边
    assert_eq!(graph.nodes.len(), 3);
    assert_eq!(graph.edges.len(), 3);
    assert!(graph.nodes.iter().any(|n| n.node_type == "character" && n.label == "芙莉莲"));
    assert!(graph.edges.iter().any(|e| e.from_id == format!("char:{c1}") && e.to_id == format!("char:{c2}") && e.relation.contains("师徒")));

    // 验证关联角色自动联动绑定作品（定位为「暂定」，且不覆盖已有定位）
    let c3 = upsert_character(
        &sync_conn,
        &UpsertCharacterInput {
            id: None,
            bgm_id: None,
            anilist_id: None,
            name: "シュタルク".into(),
            name_cn: Some("休塔尔克".into()),
            image_url: None,
            summary: None,
            gender: Some("男".into()),
            birth_month: None,
            birth_day: None,
            blood_type: None,
            rating: None,
            review: None,
        },
    )
    .unwrap();
    // 1) 建立 c1 <-> c3 关系时，c1 已绑定 a1，应自动把 c3 也关联到 a1（定位「暂定」）
    upsert_character_relation(
        &sync_conn,
        &UpsertCharacterRelationInput {
            id: None,
            source_character_id: c1,
            target_character_id: c3,
            relation_type: "同伴".into(),
            source_role: None,
            target_role: None,
            note: None,
            strength: Some(4),
        },
    )
    .unwrap();
    let chars_after_rel = list_characters_manage(&sync_conn).unwrap();
    let c3_item = chars_after_rel.iter().find(|c| c.id == c3).unwrap();
    assert!(
        c3_item.works.iter().any(|w| w.anime_id == a1 && w.role_name == "暂定"),
        "related char c3 should auto-link to a1 with role 暂定: {:?}",
        c3_item.works
    );
    // 2) 将 c1 绑定到新作品 a2 时，已有关系的 c2 与 c3 均自动关联到 a2（定位「暂定」），且 c2 在 a1 的原定位不被覆盖
    link_character_work(&sync_conn, c1, a2, Some("主角"), Some("种崎敦美")).unwrap();
    let chars_after_a2 = list_characters_manage(&sync_conn).unwrap();
    let c2_item = chars_after_a2.iter().find(|c| c.id == c2).unwrap();
    assert!(
        c2_item.works.iter().any(|w| w.anime_id == a1 && w.role_name == "主要角色"),
        "existing role on a1 must not be overwritten"
    );
    assert!(
        c2_item.works.iter().any(|w| w.anime_id == a2 && w.role_name == "暂定"),
        "related char c2 should auto-link to a2 with role 暂定: {:?}",
        c2_item.works
    );

    // 验证角色标签与单作品角色查询、批量删除
    set_character_tags(&mut sync_conn, c1, &["精灵".into(), "魔法使".into()]).unwrap();
    let chars_with_tags = list_characters_manage(&sync_conn).unwrap();
    let c1_tagged = chars_with_tags.iter().find(|c| c.id == c1).unwrap();
    assert_eq!(c1_tagged.tags.len(), 2);
    assert!(c1_tagged.tags.contains(&"精灵".to_string()));

    let work_chars = list_work_characters(&sync_conn, a1).unwrap();
    assert_eq!(work_chars.len(), 3);

    delete_character_relation(&sync_conn, rel_id).unwrap();
    let batch_removed = batch_delete_characters(&mut sync_conn, &[c3, c2]).unwrap();
    assert_eq!(batch_removed, 2);
    assert_eq!(list_characters_manage(&sync_conn).unwrap().len(), 1);
    delete_character(&mut sync_conn, c1).unwrap();
    assert!(list_characters_manage(&sync_conn).unwrap().is_empty());
}
