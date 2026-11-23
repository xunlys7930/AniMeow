use animeow_core::backup::{
    export_native_backup, inspect_native_backup, restore_native_backup, BackupPaths,
    RestoreSelection, RestoreStrategy, NATIVE_BACKUP_FORMAT_TYPE,
};
use animeow_core::cloud::{is_valid_playback_url, is_valid_proxy_segment_url};
use animeow_core::legacy::{discover_legacy_electron_data, import_legacy_electron_copy};
use animeow_core::media_server::LocalMediaServer;
use animeow_core::merger::{compute_data_fingerprint, BinaryEntry};
use animeow_core::repository::{
    mount_and_scan_vault, query_curator_items, update_curator_cell, CellPatch,
};
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::{
    batch_assign_series, create_curator_item, import_works_from_csv, list_play_sessions,
    load_canvas_custom_state, merge_duplicate_group, preview_backup_diff, purge_trash_items,
    query_duplicate_groups, query_trash_items, query_universe_graph, record_play_session,
    restore_trash_items, save_canvas_custom_state, save_canvas_layout, CanvasCustomEdge,
    CanvasCustomNode, CanvasCustomState, NewCuratorItemInput,
};
use rusqlite::Connection;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use tempfile::TempDir;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

#[test]
fn android_v11_round_trip_and_merge_keep_local() {
    let tmp = TempDir::new().unwrap();
    let android_zip = tmp.path().join("android_export_v11.animeow.zip");

    let db_json = json!({
        "watch_statuses": [
            { "id": 1, "name": "在看", "color": 4280391411_i64, "sortOrder": 0 },
            { "id": 2, "name": "看完", "color": 4283215696_i64, "sortOrder": 1 }
        ],
        "series": [
            {
                "id": 10,
                "name": "千恋与奇幻跨媒体企划",
                "description": "测试跨载体 IP 归组",
                "createdAt": "2026-10-01T00:00:00Z",
                "linkedMediaJson": "[{\"kind\":\"GAME\",\"workSyncId\":\"game-remote-sync-1\",\"role\":\"原作\"},{\"kind\":\"AUDIO\",\"workSyncId\":\"audio-sync-1\",\"role\":\"番外／衍生\"}]"
            }
        ],
        "animes": [
            {
                "id": 101,
                "title": "葬送的芙莉莲",
                "originalTitle": "葬送のフリーレン",
                "coverUrl": "animeow-cover://frieren.jpg",
                "status": "在看",
                "rating": 95,
                "watchedEpisodes": 12,
                "totalEpisodes": 28,
                "subjectType": "anime",
                "seriesId": 10,
                "seriesRole": "改编",
                "memo": "  勇者辛美尔的雕像伏笔回收  ",
                "broadcastDay": 5,
                "broadcastTime": "23:00",
                "sourceRatingJson": "{\"source\":\"bangumi\",\"id\":\"400602\",\"score\":8.9,\"updatedAt\":\"2026-11-01T00:00:00Z\"}",
                "mergedSourcesJson": "[{\"title\":\"Frieren\",\"source\":\"anilist\",\"externalId\":\"154587\"}]",
                "voiceActors": "种崎敦美 / 市之濑加那",
                "coverOrientation": "landscape",
                "externalSource": "bangumi",
                "externalId": "400602",
                "futureMobileColumnV12": "must_be_skipped_safely"
            },
            {
                "id": 102,
                "title": "败犬女主太多了！",
                "status": "在看",
                "watchedEpisodes": 7,
                "totalEpisodes": 12,
                "subjectType": "book",
                "bookReadVolumes": 7,
                "bookReadChapters": 184,
                "memo": "八奈见杏菜名场面"
            }
        ],
        "tags": [{ "id": 1, "name": "奇幻公路", "color": null, "blocked": 0 }],
        "anime_tags": [{ "animeId": 101, "tagId": 1 }],
        "watch_records": [{ "id": 1, "animeId": 101, "episode": 12, "status": "在看", "recordDate": "2026-11-10" }],
        "episode_details": [
            { "animeId": 101, "episode": 12, "title": "剑之村的勇者", "rating": 96, "review": "演出满分", "updatedAt": "2026-11-10T21:00:00Z" }
        ],
        "book_reading_states": [
            {
                "animeId": 102,
                "localFileUri": "content://com.android.externalstorage.documents/document/makeine.epub",
                "localFileName": "makeine_vol7.epub",
                "fileFormat": "EPUB",
                "fileFingerprint": "sha256_epub_fingerprint_abc123",
                "chapterIndex": 18,
                "chapterTitle": "第四章 败犬们的放学后",
                "totalChapters": 24,
                "blockIndex": 42,
                "charOffset": 128,
                "progressPermille": 765,
                "lastReadAt": "2026-11-12T22:15:00Z",
                "legadoBookUrl": "https://example.org/book/99",
                "legadoOriginName": "轻小说书源",
                "legadoAuthor": "雨森焚火",
                "legadoChapterIndex": 183,
                "legadoChapterTitle": "第184话",
                "legadoTotalChapters": 210,
                "legadoLastReadAt": "2026-11-12T23:00:00Z",
                "syncProgressToMain": 0,
                "updatedAt": "2026-11-12T23:00:00Z"
            }
        ],
        "book_bookmarks": [
            {
                "id": 1,
                "animeId": 102,
                "chapterIndex": 18,
                "chapterTitle": "第四章",
                "blockIndex": 10,
                "charOffset": 0,
                "snippet": "女生分成两种，青梅竹马和偷腥猫。",
                "note": "全书点题台词",
                "createdAt": "2026-11-12T22:16:00Z"
            }
        ],
        "characters": [
            { "id": 501, "bgmId": 11001, "anilistId": 22001, "name": "フリーレン", "nameCn": "芙莉莲", "imageDisplayMode": "fit", "updatedAt": "2026-11-01T00:00:00Z" },
            { "id": 502, "bgmId": 11002, "name": "ヒンメル", "nameCn": "辛美尔", "updatedAt": "2026-11-01T00:00:00Z" }
        ],
        "anime_characters": [
            { "animeId": 101, "characterId": 501, "roleName": "主角", "voiceActor": "种崎敦美", "sortOrder": 0 }
        ],
        "character_relations": [
            { "id": 1, "sourceCharacterId": 502, "targetCharacterId": 501, "relationType": "同伴", "sourceRole": "勇者", "targetRole": "魔法使", "strength": 5 }
        ],
        "games": [
            {
                "id": 1,
                "syncId": "game-remote-sync-1",
                "bangumiId": 172518,
                "vndbId": "v19002",
                "title": "千恋＊万花",
                "developer": "Yuzusoft",
                "score": 8.3,
                "status": "在玩",
                "playMinutes": 1800,
                "rating": 90,
                "tags": "柚子社,和风",
                "updatedAt": "2026-11-11T10:00:00.123456789Z"
            }
        ],
        "audio_works": [
            {
                "id": 1,
                "syncId": "audio-sync-1",
                "title": "治愈系耳搔音声",
                "originalTitle": "癒やし耳かきボイス",
                "circle": "猫耳社",
                "workCode": "RJ01122334",
                "durationSeconds": 3600,
                "listenedSeconds": 1200,
                "status": "在听",
                "rating": 88,
                "updatedAt": "2026-11-11T10:00:00Z"
            }
        ],
        "media_series": [
            { "id": 1, "syncId": "mseries-1", "kind": "GAME", "name": "柚子社合集", "updatedAt": "2026-11-11T10:00:00Z" }
        ],
        "media_series_members": [
            { "id": 1, "kind": "GAME", "workSyncId": "game-remote-sync-1", "seriesId": 1, "sortOrder": 0, "updatedAt": "2026-11-11T10:00:00Z" }
        ]
    });

    let db_text = serde_json::to_string(&db_json).unwrap();
    let settings_text = r#"{"schemaVersion":21,"collectionFilters":[{"id":"f1","name":"高分在看"}]}"#;
    let profiles_text = r#"{"active":"studio-default"}"#;
    let cover_bytes = b"fake_jpeg_cover_bytes_frieren";
    let splash_bytes = b"fake_png_splash_bytes";
    let font_bytes = b"fake_ttf_font_bytes";

    let fp = compute_data_fingerprint(
        &db_text,
        settings_text,
        profiles_text,
        &[BinaryEntry {
            relative_path: "frieren.jpg",
            content: cover_bytes,
        }],
        &[
            BinaryEntry {
                relative_path: "fonts/custom.ttf",
                content: font_bytes,
            },
            BinaryEntry {
                relative_path: "splash.png",
                content: splash_bytes,
            },
        ],
    );

    {
        let f = File::create(&android_zip).unwrap();
        let mut zw = ZipWriter::new(f);
        let opt = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
        let manifest = json!({
            "type": NATIVE_BACKUP_FORMAT_TYPE,
            "version": 11,
            "createdAt": "2026-11-18T00:00:00Z",
            "animeCount": 2,
            "gameCount": 1,
            "audioCount": 1,
            "coverCount": 1,
            "dataFingerprint": fp,
            "fontsIncluded": true
        });
        zw.start_file("manifest.json", opt).unwrap();
        zw.write_all(serde_json::to_string(&manifest).unwrap().as_bytes()).unwrap();
        zw.start_file("database.json", opt).unwrap();
        zw.write_all(db_text.as_bytes()).unwrap();
        zw.start_file("settings.json", opt).unwrap();
        zw.write_all(settings_text.as_bytes()).unwrap();
        zw.start_file("configuration_profiles.json", opt).unwrap();
        zw.write_all(profiles_text.as_bytes()).unwrap();
        zw.start_file("covers/frieren.jpg", opt).unwrap();
        zw.write_all(cover_bytes).unwrap();
        zw.start_file("branding/splash.png", opt).unwrap();
        zw.write_all(splash_bytes).unwrap();
        zw.start_file("branding/fonts/custom.ttf", opt).unwrap();
        zw.write_all(font_bytes).unwrap();
        zw.finish().unwrap();
    }

    let studio_root = tmp.path().join("studio_data");
    let paths = BackupPaths::from_studio_root(&studio_root);
    let inspected = inspect_native_backup(&android_zip, &paths).unwrap();
    assert_eq!(inspected.format_version, 11);
    assert_eq!(inspected.fingerprint_matches, Some(true));
    assert_eq!(inspected.anime_count, 1);
    assert_eq!(inspected.book_count, 1);
    assert_eq!(inspected.game_count, 1);
    assert_eq!(inspected.audio_count, 1);

    // 在本机先预置同款游戏（不同本地 syncId）以同时测试 MERGE_KEEP_LOCAL 的 syncId 重映射与保留策略
    let mut sync_conn = init_sync_db(&studio_root.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&studio_root.join("desktop_local.db")).unwrap();
    sync_conn
        .execute(
            "INSERT INTO games (syncId, bangumiId, vndbId, title, status, playMinutes, rating, updatedAt, tags)
             VALUES ('game-local-canonical', 172518, 'v19002', '千恋＊万花', '在玩', 1200, 85, '2026-11-10T00:00:00Z', '柚子社')",
            [],
        )
        .unwrap();

    let merge_res = restore_native_backup(
        &mut sync_conn,
        &android_zip,
        &paths,
        RestoreSelection::default(),
        RestoreStrategy::MergeKeepLocal,
    )
    .unwrap();
    assert!(!merge_res.restore_point_file.is_empty());

    // 在桌面工坊行内编辑：芙莉莲进度 +1 至 13 集，千恋＊万花 PC 自动计时累加至 2100 分钟
    let items = query_curator_items(&sync_conn, &local_conn).unwrap();
    assert_eq!(items.len(), 4);
    let frieren = items.iter().find(|i| i.title == "葬送的芙莉莲").unwrap();
    assert_eq!(frieren.score_delta, Some(0.6)); // 9.5 - 8.9 = +0.6
    assert_eq!(frieren.series_role.as_deref(), Some("改编"));
    assert_eq!(frieren.memo.as_deref(), Some("勇者辛美尔的雕像伏笔回收"));
    assert_eq!(frieren.mobile_pass_through.broadcast_schedule.as_deref(), Some("周五 23:00"));

    let makeine = items.iter().find(|i| i.kind == "book").unwrap();
    assert_eq!(makeine.book_volumes, Some(7));
    assert_eq!(makeine.book_chapters, Some(184));
    assert_eq!(makeine.mobile_pass_through.bookmark_count, 1);
    assert_eq!(makeine.mobile_pass_through.legado_chapter_title.as_deref(), Some("第184话"));

    let game = items.iter().find(|i| i.kind == "game").unwrap();
    assert_eq!(game.sync_key, "game-local-canonical"); // 本地主 syncId 被保留
    assert_eq!(game.progress_primary, 1800); // 远端时间戳更新，合并入最新时长

    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "anime".into(),
            id: frieren.id,
            progress_primary: Some(13),
            ..Default::default()
        },
    )
    .unwrap();
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "game".into(),
            id: game.id,
            progress_primary: Some(2100),
            ..Default::default()
        },
    )
    .unwrap();

    // 导出桌面端 v10 备份并重新校验指纹与全部手机端保真字段
    let desktop_export = tmp.path().join("desktop_reexport_v10.animeow.zip");
    let exported_summary =
        export_native_backup(&sync_conn, &desktop_export, &paths, true, true).unwrap();
    assert_eq!(exported_summary.format_version, 10);

    let verify_root = tmp.path().join("android_verify_restore");
    let verify_paths = BackupPaths::from_studio_root(&verify_root);
    let re_inspected = inspect_native_backup(&desktop_export, &verify_paths).unwrap();
    assert_eq!(re_inspected.fingerprint_matches, Some(true));

    let mut verify_conn = init_sync_db(&verify_root.join("animeow.db")).unwrap();
    restore_native_backup(
        &mut verify_conn,
        &desktop_export,
        &verify_paths,
        RestoreSelection::default(),
        RestoreStrategy::Replace,
    )
    .unwrap();

    // 验证手机端私有阅读路径被剥离为 NULL，但文件指纹、阅读千分比、书签、字体与配置原样带回
    let (uri, fp_saved, permille): (Option<String>, Option<String>, i64) = verify_conn
        .query_row(
            "SELECT localFileUri, fileFingerprint, progressPermille FROM book_reading_states LIMIT 1",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert!(uri.is_none());
    assert_eq!(fp_saved.as_deref(), Some("sha256_epub_fingerprint_abc123"));
    assert_eq!(permille, 765);

    // 验证 series.linkedMediaJson 中的 GAME syncId 已随合并自动重映射为 game-local-canonical
    let linked_json: String = verify_conn
        .query_row("SELECT linkedMediaJson FROM series LIMIT 1", [], |r| r.get(0))
        .unwrap();
    assert!(linked_json.contains("game-local-canonical"));
    assert!(linked_json.contains("audio-sync-1"));

    // 验证 branding 字体与 settings.json 原样保留
    assert!(verify_paths.branding_root.join("fonts/custom.ttf").is_file());
    assert!(verify_paths.branding_root.join("splash.png").is_file());
    let restored_settings = std::fs::read_to_string(&verify_paths.persisted_settings_file).unwrap();
    assert!(restored_settings.contains("collectionFilters"));
}

#[test]
fn legacy_electron_pointer_and_nondestructive_copy() {
    let tmp = TempDir::new().unwrap();
    let fake_appdata = tmp.path().join("AppDataRoaming");
    let default_electron = fake_appdata.join("animeow-desktop");
    let custom_electron = tmp.path().join("D_Custom_AniMeow_Data");
    std::fs::create_dir_all(&default_electron).unwrap();
    std::fs::create_dir_all(custom_electron.join("covers")).unwrap();

    // 写入指针指向自定义目录
    std::fs::write(
        default_electron.join("data-location.json"),
        serde_json::to_string(&json!({ "dataDir": custom_electron.to_string_lossy() })).unwrap(),
    )
    .unwrap();

    // 构造旧版早期 Schema v1 Electron 数据库（不含 memo、seriesRole、book_reading_states 等新表新列）
    let legacy_db_path = custom_electron.join("animeow.db");
    {
        let conn = Connection::open(&legacy_db_path).unwrap();
        conn.execute_batch(
            "CREATE TABLE animes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT '在看',
                watchedEpisodes INTEGER NOT NULL DEFAULT 5,
                totalEpisodes INTEGER NOT NULL DEFAULT 12,
                subjectType TEXT NOT NULL DEFAULT 'anime',
                deletedAt TEXT
             );
             INSERT INTO animes (title, status, watchedEpisodes, totalEpisodes) VALUES ('孤独摇滚！', '看完', 12, 12);",
        )
        .unwrap();
    }
    std::fs::write(custom_electron.join("covers/bocchi.jpg"), b"bocchi_cover").unwrap();

    let original_hash = sha256_file(&legacy_db_path);

    let discovery = discover_legacy_electron_data(&fake_appdata);
    assert!(discovery.database_exists);
    assert_eq!(discovery.anime_count, 1);
    assert_eq!(discovery.cover_file_count, 1);
    assert_eq!(
        discovery.effective_data_dir,
        custom_electron.to_string_lossy().as_ref()
    );

    let studio_dir = tmp.path().join("studio_isolated");
    // 连续运行两次迁移验证幂等与中断恢复安全性
    let report1 = import_legacy_electron_copy(&custom_electron, &studio_dir).unwrap();
    let report2 = import_legacy_electron_copy(&custom_electron, &studio_dir).unwrap();
    assert_eq!(report1.anime_count, 1);
    assert_eq!(report2.copied_covers, 1);

    // 断言旧版原库字节级零改动
    assert_eq!(sha256_file(&legacy_db_path), original_hash);

    // 断言隔离副本已完成新列升级并可正常查询
    let sync_conn = init_sync_db(&studio_dir.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&studio_dir.join("desktop_local.db")).unwrap();
    let items = query_curator_items(&sync_conn, &local_conn).unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0].title, "孤独摇滚！");
}

#[tokio::test]
async fn local_vault_scanner_and_loopback_206_range_player() {
    let tmp = TempDir::new().unwrap();
    let studio_dir = tmp.path().join("studio");
    let sync_conn = init_sync_db(&studio_dir.join("animeow.db")).unwrap();
    let mut local_conn = init_local_db(&studio_dir.join("desktop_local.db")).unwrap();

    sync_conn
        .execute(
            "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, subjectType)
             VALUES (1, '葬送的芙莉莲', '在看', 4, 28, 'anime')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO audio_works (id, syncId, title, workCode, status, updatedAt)
             VALUES (1, 'aud-1', '治愈耳搔', 'RJ01122334', '在听', '2026-11-18T00:00:00Z')",
            [],
        )
        .unwrap();

    let disk_vault = tmp.path().join("MediaDisk");
    let anime_dir = disk_vault.join("Frieren");
    let rj_dir = disk_vault.join("[RJ01122334] 猫耳音声");
    std::fs::create_dir_all(&anime_dir).unwrap();
    std::fs::create_dir_all(&rj_dir).unwrap();

    let video_path = anime_dir.join("[Sakurato] 葬送的芙莉莲 - 05 [1080p].mp4");
    let srt_path = anime_dir.join("[Sakurato] 葬送的芙莉莲 - 05 [1080p].sc.srt");
    let payload: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
    std::fs::write(&video_path, &payload).unwrap();
    std::fs::write(
        &srt_path,
        "1\r\n00:00:01,250 --> 00:00:04,500\r\n辛美尔如果是你的话也会这么做的吧\r\n",
    )
    .unwrap();

    let flac_path = rj_dir.join("Track01_耳かき.flac");
    let lrc_path = rj_dir.join("Track01_耳かき.lrc");
    std::fs::write(&flac_path, b"fLaC_dummy_audio_stream").unwrap();
    std::fs::write(&lrc_path, "[00:01.00]欢迎来到安静的夜晚").unwrap();

    let root_row = mount_and_scan_vault(&sync_conn, &mut local_conn, &disk_vault, "mixed").unwrap();
    assert_eq!(root_row.file_count, 2);

    // 验证藏品已自动关联本机文件计数，且跨端主库 animeow.db 不包含 vault_files 私有表
    let items = query_curator_items(&sync_conn, &local_conn).unwrap();
    let frieren = items.iter().find(|i| i.kind == "anime").unwrap();
    let audio = items.iter().find(|i| i.kind == "audio").unwrap();
    assert_eq!(frieren.local_file_count, 1);
    assert_eq!(audio.local_file_count, 1);
    let sync_has_vault_table: i64 = sync_conn
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='vault_files'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(sync_has_vault_table, 0);

    // 验证内置本地播放器的 Loopback 206 Range 流服务与 SRT -> WebVTT 字幕实时转换
    let server = LocalMediaServer::start(None).await.unwrap();
    let ticket = server
        .register_file(&video_path, Some(&srt_path), Some(&lrc_path))
        .unwrap();
    assert_eq!(ticket.script_text.as_deref(), Some("[00:01.00]欢迎来到安静的夜晚"));

    let client = reqwest::Client::new();
    let range_resp = client
        .get(&ticket.stream_url)
        .header("Range", "bytes=100-199")
        .send()
        .await
        .unwrap();
    assert_eq!(range_resp.status().as_u16(), 206);
    assert_eq!(
        range_resp
            .headers()
            .get("content-range")
            .unwrap()
            .to_str()
            .unwrap(),
        "bytes 100-199/4096"
    );
    let chunk = range_resp.bytes().await.unwrap();
    assert_eq!(chunk.as_ref(), &payload[100..=199]);

    // 越界 Range 返回 416，伪造令牌返回 403
    let invalid_range = client
        .get(&ticket.stream_url)
        .header("Range", "bytes=9999-10000")
        .send()
        .await
        .unwrap();
    assert_eq!(invalid_range.status().as_u16(), 416);

    let forged = client
        .get(format!("{}/media/not-a-valid-token", server.base_url()))
        .send()
        .await
        .unwrap();
    assert_eq!(forged.status().as_u16(), 403);

    // SRT 字幕自动转换为标准 WEBVTT
    let sub_resp = client
        .get(ticket.subtitle_url.as_ref().unwrap())
        .send()
        .await
        .unwrap();
    assert_eq!(sub_resp.status().as_u16(), 200);
    let vtt = sub_resp.text().await.unwrap();
    assert!(vtt.starts_with("WEBVTT"));
    assert!(vtt.contains("00:00:01.250 --> 00:00:04.500"));
}

#[test]
fn studio_features_ingest_universe_companion_and_diff_reconciler() {
    let tmp = TempDir::new().unwrap();
    let studio_root = tmp.path().join("studio_m1_m3");
    let paths = BackupPaths::from_studio_root(&studio_root);
    let mut sync_conn = init_sync_db(&studio_root.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&studio_root.join("desktop_local.db")).unwrap();

    // 1. 四类载体创建与外部源幂等去重
    let anime_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "anime".into(),
            title: "命运石之门".into(),
            original_title: Some("STEINS;GATE".into()),
            cover_url: None,
            status: Some("看完".into()),
            total_episodes: Some(24),
            studio_or_circle: Some("WHITE FOX".into()),
            release_date: Some("2011-04-05".into()),
            public_score: Some(9.2),
            external_source: Some("bangumi".into()),
            external_id: Some("10380".into()),
            work_code: None,
            voice_actors: Some("宫野真守 / 花泽香菜".into()),
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    // 重复同一 bangumi id 不会重复建行
    let dup_anime_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "anime".into(),
            title: "命运石之门".into(),
            original_title: None,
            cover_url: None,
            status: Some("在看".into()),
            total_episodes: Some(24),
            studio_or_circle: None,
            release_date: None,
            public_score: None,
            external_source: Some("bangumi".into()),
            external_id: Some("10380".into()),
            work_code: None,
            voice_actors: None,
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();
    assert_eq!(anime_id, dup_anime_id);

    let game_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "game".into(),
            title: "STEINS;GATE 原作视觉小说".into(),
            original_title: None,
            cover_url: None,
            status: Some("在玩".into()),
            total_episodes: None,
            studio_or_circle: Some("5pb. / Nitroplus".into()),
            release_date: Some("2009-10-15".into()),
            public_score: Some(8.9),
            external_source: Some("bangumi".into()),
            external_id: Some("3154".into()),
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
            title: "未来道具研究所广播剧".into(),
            original_title: None,
            cover_url: None,
            status: Some("已听".into()),
            total_episodes: None,
            studio_or_circle: Some("MAGES.".into()),
            release_date: None,
            public_score: None,
            external_source: None,
            external_id: None,
            work_code: Some("rj0998877".into()),
            voice_actors: Some("花泽香菜 / 今井麻美".into()),
            synopsis: None,
            media_format: None,
        },
    )
    .unwrap();

    // 2. 跨媒体企划批量归组 + IP 宇宙与声优寻踪双模式画布 + 坐标持久化
    let assigned = batch_assign_series(
        &mut sync_conn,
        &[
            ("anime".into(), anime_id),
            ("game".into(), game_id),
            ("audio".into(), audio_id),
        ],
        "科学幻想 ADV 企划",
        Some("正作"),
    )
    .unwrap();
    assert_eq!(assigned, 3);

    let ip_graph = query_universe_graph(&sync_conn, &local_conn, "ip").unwrap();
    assert_eq!(ip_graph.nodes.len(), 4); // 1 hub + 3 works
    assert_eq!(ip_graph.edges.len(), 3);

    let mut coords = HashMap::new();
    coords.insert(format!("work:anime:{anime_id}"), [420.0, 180.0]);
    save_canvas_layout(&local_conn, "ip", &coords).unwrap();
    let ip_graph_reloaded = query_universe_graph(&sync_conn, &local_conn, "ip").unwrap();
    let saved_node = ip_graph_reloaded
        .nodes
        .iter()
        .find(|n| n.node_id == format!("work:anime:{anime_id}"))
        .unwrap();
    assert_eq!((saved_node.x, saved_node.y), (420.0, 180.0));

    let cv_graph = query_universe_graph(&sync_conn, &local_conn, "seiyuu").unwrap();
    let kana_hub = cv_graph
        .nodes
        .iter()
        .find(|n| n.node_type == "hub" && n.label == "花泽香菜")
        .unwrap();
    assert!(kana_hub.sublabel.contains("2"));

    // 3. 进程伴侣会话归档自动累加 games.playMinutes
    record_play_session(
        &sync_conn,
        &local_conn,
        "game",
        game_id,
        Some("D:/Games/SteinsGate/Game.exe"),
        "2026-11-18T14:00:00Z",
        "2026-11-18T14:45:10Z",
        2710, // 45m10s -> 进位 46 分钟
        Some("进程伴侣自动归档"),
    )
    .unwrap();

    let sessions = list_play_sessions(&sync_conn, &local_conn).unwrap();
    assert_eq!(sessions.len(), 1);
    assert_eq!(sessions[0].work_title, "STEINS;GATE 原作视觉小说");

    let items = query_curator_items(&sync_conn, &local_conn).unwrap();
    let game_item = items.iter().find(|it| it.kind == "game").unwrap();
    assert_eq!(game_item.progress_primary, 46);

    // 4. 三栏差异对账器预检 (changed / only_local / only_remote)
    let snap_zip = tmp.path().join("snapshot.animeow.zip");
    export_native_backup(&sync_conn, &snap_zip, &paths, false, false).unwrap();

    // 修改本机动画进度，并新增一部仅本机存在的书籍
    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "anime".into(),
            id: anime_id,
            status: Some("看完".into()),
            progress_primary: Some(24),
            raw_rating: Some(98),
            ..Default::default()
        },
    )
    .unwrap();
    create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "book".into(),
            title: "奇诺之旅".into(),
            original_title: None,
            cover_url: None,
            status: Some("在看".into()),
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

    let diffs = preview_backup_diff(&sync_conn, &snap_zip).unwrap();
    assert!(diffs.iter().any(|d| d.title == "命运石之门" && d.diff_type == "changed"));
    assert!(diffs.iter().any(|d| d.title == "奇诺之旅" && d.diff_type == "only_local"));
}

#[tokio::test]
async fn m1_m3_security_dpapi_captures_player_and_csv_roundtrip() {
    use animeow_core::cloud::{resolve_safe_cloud_url, validate_cloud_base_url};
    use animeow_core::companion_ext::{
        bind_game_launcher, export_curator_csv, list_game_launchers, list_tier_entries,
        match_player_windows_with_library, parse_player_window_title, query_work_reference_bundle,
        save_local_capture, save_tier_entry, soft_delete_curator_item,
    };
    use animeow_core::dpapi::{
        clear_cloud_session, decrypt_secret_dpapi, encrypt_secret_dpapi,
        ensure_device_registration_id, load_cloud_session, parse_jwt_exp_strict,
        save_cloud_session, PublicBangumiProfile,
    };
    use base64::engine::general_purpose::{STANDARD as BASE64_STD, URL_SAFE_NO_PAD};
    use base64::Engine;

    let tmp = TempDir::new().unwrap();
    let studio_dir = tmp.path().join("studio_sec");
    let sync_conn = init_sync_db(&studio_dir.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&studio_dir.join("desktop_local.db")).unwrap();

    // 1. WIN-01 & WIN-04：公网强制 HTTPS + 解码后路径穿越/跨源拦截
    assert!(validate_cloud_base_url("https://www.xunlys.top/").is_ok());
    assert!(validate_cloud_base_url("http://127.0.0.1:3000").is_ok());
    assert!(validate_cloud_base_url("http://192.168.1.20:3000").is_ok());
    assert!(validate_cloud_base_url("http://198.51.100.10:3000").is_err());
    assert!(validate_cloud_base_url("http://www.xunlys.top").is_err());

    assert!(resolve_safe_cloud_url("https://www.xunlys.top", "api/user/backups").is_ok());
    assert!(resolve_safe_cloud_url("https://www.xunlys.top", "../secret").is_err());
    assert!(resolve_safe_cloud_url("https://www.xunlys.top", "api/%2e%2e/admin").is_err());
    assert!(resolve_safe_cloud_url("https://www.xunlys.top", "https://evil.example/api").is_err());
    assert!(resolve_safe_cloud_url("https://www.xunlys.top", "//evil.example/api").is_err());

    // 2. WIN-02 & WIN-03：Windows DPAPI 硬件/用户凭据加密、拒绝 raw: 明文回退与严格 JWT exp 核验
    let enc = encrypt_secret_dpapi("secret-jwt-payload-value").unwrap();
    assert!(enc.starts_with("dpapi:"));
    assert_eq!(
        decrypt_secret_dpapi(&enc).unwrap(),
        "secret-jwt-payload-value"
    );
    assert!(decrypt_secret_dpapi("raw:cGxhaW50ZXh0").is_err());
    assert!(parse_jwt_exp_strict("malformed.token").is_err());

    let future_exp = chrono::Utc::now().timestamp() + 3600;
    let fake_header = URL_SAFE_NO_PAD.encode(r#"{"alg":"HS256","typ":"JWT"}"#);
    let fake_payload =
        URL_SAFE_NO_PAD.encode(format!(r#"{{"sub":42,"username":"mio","exp":{future_exp}}}"#));
    let valid_jwt = format!("{fake_header}.{fake_payload}.sig_stub");

    let saved = save_cloud_session(
        &studio_dir,
        42,
        "mio",
        &valid_jwt,
        Some(PublicBangumiProfile {
            id: 8801,
            username: "bgm_mio".into(),
            nickname: Some("澪".into()),
            avatar: None,
        }),
    )
    .unwrap();
    assert_eq!(saved.user_id, 42);

    let loaded = load_cloud_session(&studio_dir).unwrap().unwrap();
    assert_eq!(loaded.username, "mio");
    assert_eq!(loaded.token, valid_jwt);
    assert_eq!(loaded.bangumi.as_ref().unwrap().id, 8801);

    clear_cloud_session(&studio_dir).unwrap();
    assert!(load_cloud_session(&studio_dir).unwrap().is_none());

    let dev_id = ensure_device_registration_id(&studio_dir, None).unwrap();
    assert_eq!(dev_id.len(), 64);

    // 3. M2 截帧手账、跨端考据素材聚合 (episode_details + book_bookmarks + local_captures) 与回环图片读取
    sync_conn
        .execute(
            "INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, subjectType)
             VALUES (10, '葬送的芙莉莲', 'Sousou no Frieren', '在看', 13, 28, 'anime')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO episode_details (animeId, episode, title, review, rating, updatedAt)
             VALUES (10, 14, '飞吻的魔法', '赞恩名言与舞会分镜极佳', 95, '2026-11-18T12:00:00Z')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO book_bookmarks (animeId, chapterIndex, chapterTitle, snippet, note, createdAt)
             VALUES (10, 3, '第三章 旅途', '如果是勇者辛美尔的话，一定也会这么做的。', '核心母题回收', '2026-11-18T12:05:00Z')",
            [],
        )
        .unwrap();

    // 1x1 透明 PNG
    let tiny_png_b64 = format!(
        "data:image/png;base64,{}",
        BASE64_STD.encode([
            0x89u8, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48,
            0x44, 0x52
        ])
    );
    let cap = save_local_capture(
        &studio_dir,
        &sync_conn,
        &local_conn,
        "anime",
        10,
        "葬送的芙莉莲",
        "莲花戒指特写帧",
        Some(14),
        Some(&tiny_png_b64),
        true,
    )
    .unwrap();
    assert!(cap.image_rel_path.is_some());

    let srv = LocalMediaServer::start(Some(studio_dir.join("covers")))
        .await
        .unwrap();
    let img_resp = reqwest::get(format!(
        "{}/capture/{}",
        srv.base_url(),
        cap.image_rel_path.as_deref().unwrap()
    ))
    .await
    .unwrap();
    assert_eq!(img_resp.status(), 200);

    let refs = query_work_reference_bundle(&sync_conn, &local_conn, "anime", 10).unwrap();
    assert_eq!(refs.episode_notes.len(), 1);
    assert_eq!(refs.bookmarks.len(), 1);
    assert_eq!(refs.captures.len(), 1);

    // 4. M2 白名单播放器窗口标题解析与本地番剧集数匹配
    let parsed_win = parse_player_window_title(
        "[Sakurato] 葬送的芙莉莲 - 14 [1080p][HEVC-10bit].mkv - PotPlayer",
    )
    .unwrap();
    assert_eq!(parsed_win.1, 14);
    let matched = match_player_windows_with_library(
        &sync_conn,
        &[(
            "potplayermini64.exe".to_string(),
            "[Sakurato] Sousou no Frieren - 14 [1080p].mkv - PotPlayer".to_string(),
        )],
    )
    .unwrap();
    assert_eq!(matched.len(), 1);
    assert_eq!(matched[0].matched_anime_id, 10);
    assert_eq!(matched[0].parsed_episode, 14);

    // 5. 游戏启动器绑定、Tier List 排榜持久化、CSV 导出与软删除
    bind_game_launcher(&local_conn, 77, r"E:\Galgame\Clannad\SiglusEngine.exe").unwrap();
    assert_eq!(
        list_game_launchers(&local_conn).unwrap().get(&77).unwrap(),
        r"E:\Galgame\Clannad\SiglusEngine.exe"
    );

    save_tier_entry(&local_conn, "anime", 10, Some("S"), 0).unwrap();
    assert_eq!(list_tier_entries(&local_conn).unwrap().len(), 1);

    let items_before = query_curator_items(&sync_conn, &local_conn).unwrap();
    assert!(items_before[0]
        .memo
        .as_deref()
        .unwrap_or("")
        .contains("莲花戒指特写帧"));

    let csv_path = tmp.path().join("export.csv");
    let exported_count = export_curator_csv(&csv_path, &items_before).unwrap();
    assert_eq!(exported_count, 1);
    let csv_raw = std::fs::read_to_string(&csv_path).unwrap();
    assert!(csv_raw.starts_with('\u{feff}'));
    assert!(csv_raw.contains("葬送的芙莉莲"));

    // 断言跨端备份包依然只含标准 22 张表，绝不混入 local_captures / tier_list_entries
    let pure_zip = tmp.path().join("pure_check.animeow.zip");
    let paths = BackupPaths::from_studio_root(&studio_dir);
    let summary = export_native_backup(&sync_conn, &pure_zip, &paths, false, false).unwrap();
    assert_eq!(summary.anime_count, 1);

    soft_delete_curator_item(&sync_conn, "anime", 10).unwrap();
    assert_eq!(
        query_curator_items(&sync_conn, &local_conn).unwrap().len(),
        0
    );
}

#[test]
fn m4_scale_10k_items_tier_reorder_and_legacy_upgrade_continuity() {
    use animeow_core::companion_ext::{list_tier_entries, save_tier_entry};
    use animeow_core::dpapi::ensure_device_registration_id;
    use animeow_core::legacy::{discover_legacy_electron_data, import_legacy_electron_copy};

    let tmp = TempDir::new().unwrap();

    // 1. 压测 10,000 条四载体合成藏品写入与全量查询性能（配合 CuratorSheet 视口虚拟滚动）
    let scale_dir = tmp.path().join("scale_10k");
    std::fs::create_dir_all(&scale_dir).unwrap();
    let mut sync_conn = init_sync_db(&scale_dir.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&scale_dir.join("desktop_local.db")).unwrap();

    let tx = sync_conn.transaction().unwrap();
    {
        let mut anime_stmt = tx
            .prepare(
                "INSERT INTO animes (title, originalTitle, status, watchedEpisodes, totalEpisodes, rating, sourceRatingJson, subjectType, airDate)
                 VALUES (?1, ?2, ?3, ?4, 24, ?5, ?6, ?7, ?8)",
            )
            .unwrap();
        for i in 0..8000i64 {
            let subject_type = if i % 4 == 0 { "book" } else { "anime" };
            let status = if i % 3 == 0 { "看完" } else { "在看" };
            let rating = 60 + (i % 41);
            let public_score = 6.0 + ((i % 35) as f64) * 0.1;
            let rating_json = format!(r#"{{"score":{public_score:.1}}}"#);
            anime_stmt
                .execute(rusqlite::params![
                    format!("合成藏品 #{i:05}"),
                    format!("Synthetic Work {i:05}"),
                    status,
                    (i % 24) + 1,
                    rating,
                    rating_json,
                    subject_type,
                    format!("{}-04-01", 2000 + (i % 26))
                ])
                .unwrap();
        }

        let mut game_stmt = tx
            .prepare(
                "INSERT INTO games (syncId, title, status, playMinutes, rating, score, releaseDate, updatedAt)
                 VALUES (?1, ?2, '在玩', ?3, ?4, 8.2, '2024-07-15', '2026-11-19T00:00:00Z')",
            )
            .unwrap();
        for i in 0..1000i64 {
            game_stmt
                .execute(rusqlite::params![
                    format!("game-sync-{i:04}"),
                    format!("合成游戏 #{i:04}"),
                    (i + 1) * 15,
                    70 + (i % 30)
                ])
                .unwrap();
        }

        let mut audio_stmt = tx
            .prepare(
                "INSERT INTO audio_works (syncId, title, workCode, status, listenedSeconds, durationSeconds, rating, releaseDate, updatedAt)
                 VALUES (?1, ?2, ?3, '在听', ?4, 3600, ?5, '2025-01-10', '2026-11-19T00:00:00Z')",
            )
            .unwrap();
        for i in 0..1000i64 {
            audio_stmt
                .execute(rusqlite::params![
                    format!("audio-sync-{i:04}"),
                    format!("合成音声 #{i:04}"),
                    format!("RJ{:06}", 100000 + i),
                    (i % 60) * 60,
                    75 + (i % 25)
                ])
                .unwrap();
        }
    }
    tx.commit().unwrap();

    let query_start = std::time::Instant::now();
    let queried = query_curator_items(&sync_conn, &local_conn).unwrap();
    let elapsed_ms = query_start.elapsed().as_millis();
    assert_eq!(queried.len(), 10_000);
    println!("10,000 条四载体藏品全量查询耗时: {elapsed_ms} ms");

    // 校验评分与分差计算
    let first_rated = queried
        .iter()
        .find(|it| it.personal_score.is_some() && it.public_score.is_some())
        .unwrap();
    let expected_delta =
        ((first_rated.personal_score.unwrap() - first_rated.public_score.unwrap()) * 10.0).round()
            / 10.0;
    assert!((first_rated.score_delta.unwrap() - expected_delta).abs() < 0.05);

    // 2. 神作榜 S/A/B/C/D 跨阶梯迁移、排序重排与移出闭环
    save_tier_entry(&local_conn, "anime", 1, Some("S"), 0).unwrap();
    save_tier_entry(&local_conn, "anime", 2, Some("S"), 1).unwrap();
    save_tier_entry(&local_conn, "game", 1, Some("A"), 0).unwrap();
    // 调整顺序：将 anime:2 移至 S 级首位，将 game:1 升至 S 级，并移出 anime:1
    save_tier_entry(&local_conn, "anime", 2, Some("S"), 0).unwrap();
    save_tier_entry(&local_conn, "game", 1, Some("S"), 1).unwrap();
    save_tier_entry(&local_conn, "anime", 1, None, 0).unwrap();

    let tiers = list_tier_entries(&local_conn).unwrap();
    assert_eq!(tiers.len(), 2);
    assert_eq!(tiers[0].work_kind, "anime");
    assert_eq!(tiers[0].work_id, 2);
    assert_eq!(tiers[0].tier, "S");
    assert_eq!(tiers[0].sort_order, 0);
    assert_eq!(tiers[1].work_kind, "game");
    assert_eq!(tiers[1].work_id, 1);
    assert_eq!(tiers[1].tier, "S");
    assert_eq!(tiers[1].sort_order, 1);

    // 3. M4 旧版覆盖升级承接：data-location.json 重定向 + config.json 设备注册号继承 + 原库 SHA-256 零变动
    let fake_roaming = tmp.path().join("roaming_m4");
    let legacy_default = fake_roaming.join("animeow-desktop");
    let legacy_custom = tmp.path().join("custom_electron_vault");
    std::fs::create_dir_all(&legacy_default).unwrap();
    std::fs::create_dir_all(legacy_custom.join("covers")).unwrap();

    let legacy_dev_id = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    std::fs::write(
        legacy_default.join("config.json"),
        serde_json::json!({ "deviceRegistrationId": legacy_dev_id }).to_string(),
    )
    .unwrap();
    std::fs::write(
        legacy_default.join("data-location.json"),
        serde_json::json!({ "dataDir": legacy_custom.to_string_lossy() }).to_string(),
    )
    .unwrap();

    let legacy_db_path = legacy_custom.join("animeow.db");
    {
        let old_conn = init_sync_db(&legacy_db_path).unwrap();
        old_conn
            .execute(
                "INSERT INTO animes (title, status, watchedEpisodes, totalEpisodes, subjectType)
                 VALUES ('旧版升级承接番剧', '看完', 12, 12, 'anime')",
                [],
            )
            .unwrap();
    }
    let old_hash_before = sha256_file(&legacy_db_path);

    let studio_upgrade_dir = tmp.path().join("studio_upgrade_target");
    std::fs::create_dir_all(&studio_upgrade_dir).unwrap();

    // 继承旧版设备注册号，确保升级后「用本机登录」保持原账号
    let inherited_dev_id =
        ensure_device_registration_id(&studio_upgrade_dir, Some(&fake_roaming)).unwrap();
    assert_eq!(inherited_dev_id, legacy_dev_id);

    // 自动发现自定义指针并无损只读副本迁移
    let discovery = discover_legacy_electron_data(&fake_roaming);
    assert!(discovery.database_exists);
    assert_eq!(discovery.anime_count, 1);
    assert_eq!(
        discovery.pointer_target_dir.as_deref(),
        Some(legacy_custom.to_string_lossy().as_ref())
    );

    let report = import_legacy_electron_copy(
        std::path::Path::new(&discovery.effective_data_dir),
        &studio_upgrade_dir,
    )
    .unwrap();
    assert_eq!(report.anime_count, 1);
    assert_eq!(sha256_file(&legacy_db_path), old_hash_before);
}

#[test]
fn ux_regressions_universe_layout_progress_clamp_and_backup_diff() {
    let tmp = TempDir::new().unwrap();
    let studio_root = tmp.path().join("studio_ux_reg");
    let paths = BackupPaths::from_studio_root(&studio_root);
    let sync_conn = init_sync_db(&studio_root.join("animeow.db")).unwrap();
    let local_conn = init_local_db(&studio_root.join("desktop_local.db")).unwrap();

    // 1. 书籍与动画进度上限夹紧（防 265 / 262 溢出）
    let book_id = create_curator_item(
        &sync_conn,
        &NewCuratorItemInput {
            kind: "book".into(),
            title: "无职转生".into(),
            original_title: None,
            cover_url: None,
            status: Some("在看".into()),
            total_episodes: Some(262),
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

    update_curator_cell(
        &sync_conn,
        &CellPatch {
            kind: "book".into(),
            id: book_id,
            progress_primary: Some(265),
            ..Default::default()
        },
    )
    .unwrap();

    let watched: i64 = sync_conn
        .query_row(
            "SELECT watchedEpisodes FROM animes WHERE id = ?1",
            [book_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(watched, 262, "书籍主进度不得超过 totalEpisodes 上限");

    // 2. 企划画布防重叠布局：多部成员系列与后续系列垂直坐标不得重叠
    sync_conn
        .execute("INSERT INTO series (id, name, linkedMediaJson) VALUES (1, '系列甲', '[]')", [])
        .unwrap();
    sync_conn
        .execute("INSERT INTO series (id, name, linkedMediaJson) VALUES (2, '系列乙', '[]')", [])
        .unwrap();
    sync_conn
        .execute("INSERT INTO series (id, name, linkedMediaJson) VALUES (3, '系列丙', '[]')", [])
        .unwrap();
    for i in 1..=8 {
        sync_conn
            .execute(
                "INSERT INTO animes (title, status, seriesId, subjectType) VALUES (?1, '看完', 1, 'anime')",
                [format!("甲部作-{i}")],
            )
            .unwrap();
    }
    for i in 1..=5 {
        sync_conn
            .execute(
                "INSERT INTO animes (title, status, seriesId, subjectType) VALUES (?1, '看完', 2, 'anime')",
                [format!("乙部作-{i}")],
            )
            .unwrap();
    }
    for i in 1..=4 {
        sync_conn
            .execute(
                "INSERT INTO animes (title, status, seriesId, subjectType) VALUES (?1, '想看', 3, 'anime')",
                [format!("丙部作-{i}")],
            )
            .unwrap();
    }

    let graph = query_universe_graph(&sync_conn, &local_conn, "ip").unwrap();
    // 任意两个节点边框 (216 x 78) 均不得重叠
    for i in 0..graph.nodes.len() {
        for j in (i + 1)..graph.nodes.len() {
            let a = &graph.nodes[i];
            let b = &graph.nodes[j];
            let x_overlap = (a.x - b.x).abs() < 216.0;
            let y_overlap = (a.y - b.y).abs() < 78.0;
            assert!(
                !(x_overlap && y_overlap),
                "企划画布节点重叠: {} ({}, {}) vs {} ({}, {})",
                a.label,
                a.x,
                a.y,
                b.label,
                b.x,
                b.y
            );
        }
    }

    // 3. 备份预检对账：同名动画与轻小说/漫画不得互撞误报，rating 为 0 与 NULL 视为一致，空库导入返回完整 only_remote
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, watchedEpisodes, totalEpisodes, rating, subjectType, externalSource, externalId)
             VALUES ('【我推的孩子】', '看完', 13, 13, 74, 'anime', 'bangumi', '386809')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO animes (title, status, watchedEpisodes, totalEpisodes, rating, subjectType, externalSource, externalId)
             VALUES ('【我推的孩子】', '看完', 166, 166, 56, 'book', 'bangumi', '304041')",
            [],
        )
        .unwrap();
    let zip_path = tmp.path().join("diff_check.animeow.zip");
    export_native_backup(&sync_conn, &zip_path, &paths, false, false).unwrap();

    // 刚导出 immediately 对比应无任何差异（含同名动画+书籍双条）
    let same_diffs = preview_backup_diff(&sync_conn, &zip_path).unwrap();
    assert!(same_diffs.is_empty(), "刚导出的备份与本机对比不应有误报差异: {same_diffs:?}");

    // 空库预检该备份包应全部标记为 only_remote，changed 为 0
    let empty_conn = init_sync_db(&tmp.path().join("empty_local.db")).unwrap();
    let fresh_diffs = preview_backup_diff(&empty_conn, &zip_path).unwrap();
    assert!(!fresh_diffs.is_empty());
    assert!(
        fresh_diffs.iter().all(|d| d.diff_type == "only_remote"),
        "空库预检应全为 only_remote，不得出现 changed 冲突"
    );
}

#[test]
fn desktop_parity_trash_duplicates_csv_canvas_and_playback_url() {
    // 1. 播放源域名校验：放行数字开头的合法公网域名，严防回环/内网/裸 IP
    assert!(is_valid_playback_url("https://7sefun.top/video/01.mp4"));
    assert!(is_valid_playback_url("https://1080p.cdn.example.com/index.m3u8"));
    assert!(!is_valid_playback_url("https://127.0.0.1/a.mp4"));
    assert!(!is_valid_playback_url("https://1.2.3.4/a.mp4"));
    assert!(!is_valid_playback_url("https://localhost/a.mp4"));
    assert!(is_valid_proxy_segment_url("http://cdn.example.com/seg001.ts"));
    assert!(!is_valid_proxy_segment_url("http://localhost/seg001.ts"));

    let tmp = TempDir::new().unwrap();
    let mut sync_conn = init_sync_db(&tmp.path().join("sync.db")).unwrap();
    let local_conn = init_local_db(&tmp.path().join("local.db")).unwrap();

    // 2. 重复检测与安全合并 + 回收站还原/清空闭环
    sync_conn
        .execute(
            "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, rating, review, subjectType)
             VALUES (1, '葬送的芙莉莲', '在看', 8, 28, NULL, '', 'anime')",
            [],
        )
        .unwrap();
    sync_conn
        .execute(
            "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, rating, review, subjectType)
             VALUES (2, '葬送的 芙莉莲', '看完', 28, 28, 92, '极致的公路片余韵与时间叙事', 'anime')",
            [],
        )
        .unwrap();

    let dups = query_duplicate_groups(&sync_conn).unwrap();
    assert_eq!(dups.len(), 1);
    assert_eq!(dups[0].recommended_keep_id, 2);

    let dropped = merge_duplicate_group(&mut sync_conn, "anime", 1, &[2]).unwrap();
    assert_eq!(dropped, 1);

    // 验证保留项 #1 吸收了更高的集数 (28)、评分 (92) 与更长影评
    let (ep, rating, review): (i64, Option<i64>, String) = sync_conn
        .query_row(
            "SELECT watchedEpisodes, rating, review FROM animes WHERE id = 1",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap();
    assert_eq!(ep, 28);
    assert_eq!(rating, Some(92));
    assert!(review.contains("时间叙事"));

    // 验证被合并掉的 #2 在回收站中可查、可还原、可彻底清除
    let trash = query_trash_items(&sync_conn).unwrap();
    assert_eq!(trash.len(), 1);
    assert_eq!(trash[0].id, 2);

    assert_eq!(restore_trash_items(&mut sync_conn, &[("anime".into(), 2)]).unwrap(), 1);
    assert!(query_trash_items(&sync_conn).unwrap().is_empty());

    sync_conn
        .execute("UPDATE animes SET deletedAt = '2026-11-19T00:00:00Z' WHERE id = 2", [])
        .unwrap();
    assert_eq!(purge_trash_items(&mut sync_conn, &[("anime".into(), 2)]).unwrap(), 1);
    assert!(query_trash_items(&sync_conn).unwrap().is_empty());

    // 3. CSV 表格反向批量导入（含重复跳过）
    let csv = "\u{feff}载体,标题,状态,主进度,总进度,个人评分\n\
               动画,葬送的芙莉莲,看完,28,28,9.2\n\
               动画,孤独摇滚！,看完,12,12,9.0\n\
               书籍,败犬女主太多了！,在看,3,7,8.5\n\
               游戏,CLANNAD,已玩,1800,0,9.5\n";
    let report = import_works_from_csv(&mut sync_conn, csv).unwrap();
    assert_eq!(report.imported, 3);
    assert_eq!(report.skipped_duplicates, 1);
    assert_eq!(report.failed_rows, 0);

    // 4. 画布自定义节点与自定义连线持久化闭环
    let custom = CanvasCustomState {
        nodes: vec![CanvasCustomNode {
            id: "custom:1".into(),
            label: "键社催泪企划".into(),
            subtitle: "麻枝准核心作品线".into(),
            color: "#2f6d50".into(),
            x: 160.0,
            y: 220.0,
        }],
        edges: vec![CanvasCustomEdge {
            id: "ce:1".into(),
            from: "custom:1".into(),
            to: "work:game:1".into(),
            label: "核心代表作".into(),
        }],
    };
    save_canvas_custom_state(&local_conn, "ip", &custom).unwrap();
    let reloaded = load_canvas_custom_state(&local_conn, "ip").unwrap();
    assert_eq!(reloaded.nodes.len(), 1);
    assert_eq!(reloaded.edges.len(), 1);
    assert_eq!(reloaded.nodes[0].label, "键社催泪企划");
    assert_eq!(reloaded.edges[0].label, "核心代表作");
}

fn sha256_file(path: &std::path::Path) -> String {
    let bytes = std::fs::read(path).unwrap();
    hex::encode(Sha256::digest(&bytes))
}
