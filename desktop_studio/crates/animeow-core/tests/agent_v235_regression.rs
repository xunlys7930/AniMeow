use animeow_core::agent::registry::ARTIFACT_SPILL_THRESHOLD_CHARS;
use animeow_core::agent::storage::{
    create_session, read_proposal_statuses, save_verify_batch_result,
};
use animeow_core::agent::tools_read::{
    compare_dates, evaluate_bangumi_matches, evaluate_bangumi_matches_with_mode,
    LocalWorkVerifyTarget,
};
use animeow_core::agent::tools_write::{
    cancel_pending_proposal, commit_proposal, create_bulk_bind_proposal,
    create_character_mutations_proposal, create_work_mutations_proposal, revert_proposal,
};
use animeow_core::backup::{
    encode_cover_reference, export_native_backup_with_options, import_local_custom_cover,
    restore_native_backup, BackupPaths, RestoreSelection, RestoreStrategy,
};
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::RemoteCatalogHit;
use serde_json::json;
use tempfile::tempdir;

fn hit(id: &str, title: &str, orig: &str, date: &str, eps: i64) -> RemoteCatalogHit {
    RemoteCatalogHit {
        kind: "anime".into(),
        external_source: "bangumi".into(),
        external_id: id.into(),
        title: title.into(),
        original_title: orig.into(),
        cover_url: String::new(),
        release_date: date.into(),
        total_episodes: eps,
        public_score: None,
        studio_or_circle: String::new(),
        synopsis: String::new(),
    }
}

#[test]
fn v235_timezone_date_comparison_handles_utc_and_boundaries() {
    // 1554: 本地 2016-07-06 vs 线上 2016-07-05 (或 UTC+8 零点序列化为 2016-07-05T16:00:00.000Z)
    assert_eq!(compare_dates("2016-07-06", "2016-07-05"), "exact");
    assert_eq!(compare_dates("2016-07-05T16:00:00.000Z", "2016-07-06"), "exact");
    // 跨月 1 号边界
    assert_eq!(compare_dates("2016-06-30T16:00:00.000Z", "2016-07-01"), "exact");
    assert_eq!(compare_dates("2016-06-30", "2016-07-01"), "exact");
    // 跨年 1 月 1 日边界（过去会被误判 mismatch 倒扣 -0.15）
    assert_eq!(compare_dates("2016-12-31T16:00:00.000Z", "2017-01-01"), "exact");
    assert_eq!(compare_dates("2016-12-31", "2017-01-01"), "exact");
    // 真正跨年不符仍为 mismatch
    assert_eq!(compare_dates("2016-07-06", "2019-07-06"), "mismatch");
}

#[test]
fn v235_scorer_fixes_five_real_false_negatives_while_protecting_conflicts() {
    // 1. 案例 1554：原名精确一致 + 集数一致 + 日期差一天 + 中文译名不同 -> exact_match (>=0.95)
    let t1554 = LocalWorkVerifyTarget {
        work_id: 1554,
        kind: "anime".into(),
        title: "某部译名略有不同的番".into(),
        original_title: "モブサイコ100".into(),
        release_date: "2016-07-06".into(),
        total_episodes: 12,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r1554 = evaluate_bangumi_matches(
        &t1554,
        vec![hit("159690", "灵能百分百", "モブサイコ100", "2016-07-05", 12)],
        3,
    );
    assert_eq!(r1554["matchStatus"], "exact_match", "1554 should be exact_match: {r1554}");
    assert!(r1554["confidence"].as_f64().unwrap() >= 0.95);

    // 2. 案例 1335：无职转生（短主标题无原名 vs 带副标题完整标题）-> exact_match
    let t1335 = LocalWorkVerifyTarget {
        work_id: 1335,
        kind: "anime".into(),
        title: "无职转生".into(),
        original_title: String::new(),
        release_date: "2021-01-10".into(),
        total_episodes: 11,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r1335 = evaluate_bangumi_matches(
        &t1335,
        vec![hit(
            "277554",
            "无职转生 ～到了异世界就拿出真本事～",
            "無職転生 ～異世界行ったら本気だす～",
            "2021-01-10",
            11,
        )],
        3,
    );
    assert_eq!(r1335["matchStatus"], "exact_match", "1335 should be exact_match: {r1335}");

    // 3. 案例 1413：Fate/stay night (2006) 带括号备注 -> 去括号精确匹配
    let t1413 = LocalWorkVerifyTarget {
        work_id: 1413,
        kind: "anime".into(),
        title: "Fate/stay night (2006)".into(),
        original_title: String::new(),
        release_date: "2006-01-07".into(),
        total_episodes: 24,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r1413 = evaluate_bangumi_matches(
        &t1413,
        vec![hit("290", "命运之夜", "Fate/stay night", "2006-01-06", 24)],
        3,
    );
    assert_eq!(r1413["matchStatus"], "exact_match", "1413 should be exact_match: {r1413}");

    // 4. 案例 1417：Little Busters!~Refrain~ 英文标题 + Refrain 副标题锚点 + 日期集数双中 -> exact_match
    let t1417 = LocalWorkVerifyTarget {
        work_id: 1417,
        kind: "anime".into(),
        title: "Little Busters!~Refrain~".into(),
        original_title: String::new(),
        release_date: "2013-10-05".into(),
        total_episodes: 13,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r1417 = evaluate_bangumi_matches(
        &t1417,
        vec![hit("69484", "小小克星！～Refrain～", "リトルバスターズ! 〜Refrain〜", "2013-10-05", 13)],
        3,
    );
    assert_eq!(r1417["matchStatus"], "exact_match", "1417 should be exact_match: {r1417}");

    // 5. 案例 1209：辉夜大小姐想让我告白 第三季 vs 辉夜大小姐想让我告白 -究极浪漫-（单侧季数 + 日期集数双中）-> exact_match
    let t1209 = LocalWorkVerifyTarget {
        work_id: 1209,
        kind: "anime".into(),
        title: "辉夜大小姐想让我告白 第三季".into(),
        original_title: String::new(),
        release_date: "2022-04-08".into(),
        total_episodes: 13,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r1209 = evaluate_bangumi_matches(
        &t1209,
        vec![hit(
            "317613",
            "辉夜大小姐想让我告白 -究极浪漫-",
            "かぐや様は告らせたい-ウルトラロマンティック-",
            "2022-04-08",
            13,
        )],
        3,
    );
    assert_eq!(r1209["matchStatus"], "exact_match", "1209 should be exact_match: {r1209}");

    // 6. 防误绑底线仍完好：显式不同季（第2季 vs 第3季）绝不给 exact_match
    let t_conflict = LocalWorkVerifyTarget {
        work_id: 2001,
        kind: "anime".into(),
        title: "约会大作战 第二季".into(),
        original_title: String::new(),
        release_date: "2014-04-11".into(),
        total_episodes: 10,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r_conflict = evaluate_bangumi_matches(
        &t_conflict,
        vec![hit("228254", "约会大作战 第三季", "デート・ア・ライブⅢ", "2019-01-11", 12)],
        3,
    );
    assert_ne!(r_conflict["matchStatus"], "exact_match");
}

#[test]
fn v235_compact_50_batch_stays_under_spill_and_from_verify_ids_binds_cleanly() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let session = create_session(&local, None, None, None).unwrap();

    // 构造 50 条紧凑结果并检验总字符数远低于 14,000 阈值
    let mut compact_rows = Vec::new();
    let mut exact_bindings = Vec::new();
    for i in 1..=50i64 {
        sync.execute(
            "INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, subjectType)
             VALUES (?1, ?2, '', '看完', 12, 12, 'anime')",
            rusqlite::params![i, format!("测试番剧-{i:02}")],
        )
        .unwrap();
        let target = LocalWorkVerifyTarget {
            work_id: i,
            kind: "anime".into(),
            title: format!("测试番剧-{i:02}"),
            original_title: String::new(),
            release_date: "2024-01-01".into(),
            total_episodes: 12,
            media_format: None,
            tags: vec![],
            existing_bangumi_id: None,
        };
        let row = evaluate_bangumi_matches_with_mode(
            &target,
            vec![hit(
                &format!("{}", 500000 + i),
                &format!("测试番剧-{i:02}"),
                &format!("TestAnime-{i:02}"),
                "2024-01-01",
                12,
            )],
            3,
            true,
        );
        assert_eq!(row["matchStatus"], "exact_match");
        compact_rows.push(row);
        exact_bindings.push(json!({
            "work_id": i,
            "kind": "anime",
            "external_source": "bangumi",
            "external_id": format!("{}", 500000 + i),
            "original_title": format!("TestAnime-{i:02}"),
        }));
    }
    let payload = json!({
        "mode": "batch_verify",
        "compact": true,
        "count": 50,
        "results": compact_rows,
    });
    assert!(
        payload.to_string().chars().count() < ARTIFACT_SPILL_THRESHOLD_CHARS,
        "50-item compact batch ({} chars) must not spill",
        payload.to_string().chars().count()
    );

    // 零拷贝通过 from_verify_ids 直接生成批量绑定提案（并排除 work_id=50）
    let ver_id = save_verify_batch_result(&local, &session.id, &exact_bindings).unwrap();
    let outcome = create_bulk_bind_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "mode": "bulk_bind",
            "title": "零拷贝句柄批量绑定测试",
            "from_verify_ids": [ver_id],
            "exclude_work_ids": [50]
        }),
    )
    .unwrap();
    assert_eq!(outcome.valid_count, 49);
    let prop = outcome.proposal.unwrap();
    let applied = commit_proposal(&mut sync, &local, &prop.id, None).unwrap();
    assert_eq!(applied.applied_count, 49);

    let bound_cnt: i64 = sync
        .query_row("SELECT COUNT(*) FROM animes WHERE externalId IS NOT NULL", [], |r| r.get(0))
        .unwrap();
    assert_eq!(bound_cnt, 49);

    // 整体撤销
    revert_proposal(&mut sync, &local, &prop.id).unwrap();
    let after_revert: i64 = sync
        .query_row("SELECT COUNT(*) FROM animes WHERE externalId IS NOT NULL", [], |r| r.get(0))
        .unwrap();
    assert_eq!(after_revert, 0);
}

#[test]
fn v235_character_proposals_no_provisional_infection_plus_reverse_ops_and_audit() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let session = create_session(&local, None, None, None).unwrap();

    sync.execute_batch(
        "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, subjectType)
         VALUES (1329, '【我推的孩子】', '看完', 13, 13, 'anime'),
                (1369, '约会大作战', '看完', 12, 12, 'anime');",
    )
    .unwrap();

    // 1. expected_title 不一致立即拦截 TITLE_MISMATCH
    let bad_err = create_character_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "title": "错误作品ID防呆测试",
            "operations": [
                { "action": "link_work", "character_name": "五河士道", "anime_id": 1329, "expected_title": "约会大作战", "role_name": "主角" }
            ]
        }),
    )
    .unwrap_err();
    assert!(bad_err.to_string().contains("TITLE_MISMATCH"));

    // 2. 创建角色并把士道关联到 1329 + 建立士道↔琴里关系：确认提交时绝不把 1329 「暂定」传染给琴里！
    let p_create = create_character_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "title": "角色与关系隔离测试",
            "operations": [
                { "action": "upsert_character", "name": "五河士道", "name_cn": "五河士道" },
                { "action": "upsert_character", "name": "五河琴里", "name_cn": "五河琴里" },
                { "action": "link_work", "character_name": "五河士道", "anime_id": 1329, "role_name": "主角" },
                { "action": "upsert_relation", "source_name": "五河士道", "target_name": "五河琴里", "relation_type": "兄妹" }
            ]
        }),
    )
    .unwrap();
    assert!(p_create.summary.contains("【我推的孩子】"));
    assert!(p_create.summary.contains("#1329"));
    commit_proposal(&mut sync, &local, &p_create.id, None).unwrap();

    let kotori_links: i64 = sync
        .query_row(
            "SELECT COUNT(*) FROM anime_characters ac
             JOIN characters c ON c.id = ac.characterId
             WHERE c.name = '五河琴里'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(kotori_links, 0, "琴里绝不应被静默传染 1329 暂定关联");

    // 3. 验证审计字段与明细（get_proposal_status）
    let statuses = read_proposal_statuses(&local, &session.id, Some(&p_create.id)).unwrap();
    assert_eq!(statuses.len(), 1);
    assert_eq!(statuses[0]["confirmedBy"], "user");
    assert!(statuses[0]["appliedAt"].as_str().is_some());
    assert_eq!(statuses[0]["canRevert"], true);
    assert_eq!(statuses[0]["items"].as_array().unwrap().len(), 4);

    // 4. cancel_proposal 可撤回 pending 提案，但拒绝撤回已生效提案
    let p_pending = create_work_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "title": "待撤回提案",
            "operations": [
                { "action": "create", "kind": "anime", "title": "约会大作战 第二季", "status": "想看", "release_date": "2014-04-11", "series_name": "约会大作战", "series_role": "第二季" }
            ]
        }),
    )
    .unwrap()
    .0;
    let cancelled = cancel_pending_proposal(&local, &session.id, &p_pending.id, Some("测试撤回")).unwrap();
    assert_eq!(cancelled["status"], "cancelled");
    assert!(cancel_pending_proposal(&local, &session.id, &p_create.id, None).is_err());

    // 5. unlink_work + delete_relation + delete_character 反向纠错与一键撤销恢复
    let p_fix = create_character_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "title": "反向纠错测试",
            "operations": [
                { "action": "unlink_work", "character_name": "五河士道", "anime_id": 1329 },
                { "action": "delete_relation", "source_name": "五河士道", "target_name": "五河琴里" },
                { "action": "delete_character", "character_name": "五河琴里" }
            ]
        }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &p_fix.id, None).unwrap();

    let shido_links: i64 = sync
        .query_row("SELECT COUNT(*) FROM anime_characters WHERE animeId = 1329", [], |r| r.get(0))
        .unwrap();
    let rel_cnt: i64 = sync
        .query_row("SELECT COUNT(*) FROM character_relations", [], |r| r.get(0))
        .unwrap();
    let char_cnt: i64 = sync
        .query_row("SELECT COUNT(*) FROM characters", [], |r| r.get(0))
        .unwrap();
    assert_eq!(shido_links, 0);
    assert_eq!(rel_cnt, 0);
    assert_eq!(char_cnt, 1);

    // 撤销反向纠错提案：关联、关系、被删角色全部完整还原
    revert_proposal(&mut sync, &local, &p_fix.id).unwrap();
    let shido_links_back: i64 = sync
        .query_row("SELECT COUNT(*) FROM anime_characters WHERE animeId = 1329", [], |r| r.get(0))
        .unwrap();
    let rel_cnt_back: i64 = sync
        .query_row("SELECT COUNT(*) FROM character_relations", [], |r| r.get(0))
        .unwrap();
    let char_cnt_back: i64 = sync
        .query_row("SELECT COUNT(*) FROM characters", [], |r| r.get(0))
        .unwrap();
    assert_eq!(shido_links_back, 1);
    assert_eq!(rel_cnt_back, 1);
    assert_eq!(char_cnt_back, 2);
}

#[test]
fn v235_custom_cover_import_export_and_merge_heal() {
    let dir = tempdir().unwrap();
    let src_root = dir.path().join("src_studio");
    let dst_root = dir.path().join("dst_studio");
    std::fs::create_dir_all(&src_root).unwrap();
    std::fs::create_dir_all(&dst_root).unwrap();

    let src_paths = BackupPaths::from_studio_root(&src_root);
    let dst_paths = BackupPaths::from_studio_root(&dst_root);
    let src_sync = init_sync_db(&src_root.join("animeow.db")).unwrap();
    let mut dst_sync = init_sync_db(&dst_root.join("animeow.db")).unwrap();

    // 1. 测试遗留手机/旧桌面路径归一化
    assert_eq!(
        encode_cover_reference(
            "file:///data/user/0/com.animeow.app/files/covers/restored_123/native/cover_abc.jpg",
            &src_paths.covers_root
        ),
        "animeow-cover://native/cover_abc.jpg"
    );

    // 2. 导入本地自定义封面并写入源库
    let sample_img = dir.path().join("my_cover.png");
    std::fs::write(&sample_img, b"\x89PNG\r\n\x1a\nFAKE_COVER_BYTES").unwrap();
    let marker = import_local_custom_cover(&src_paths.covers_root, &sample_img).unwrap();
    assert!(marker.starts_with("animeow-cover://native/cover_"));
    src_sync
        .execute(
            "INSERT INTO animes (id, title, status, coverUrl, watchedEpisodes, totalEpisodes, subjectType)
             VALUES (1, '测试封面番', '在看', ?1, 3, 12, 'anime')",
            [&marker],
        )
        .unwrap();

    // 3. 导出仅含引用封面的云端格式备份包
    let zip_path = dir.path().join("cloud.animeow.zip");
    let summary = export_native_backup_with_options(&src_sync, &zip_path, &src_paths, true, false, true).unwrap();
    assert_eq!(summary.cover_count, 1);

    // 4. 目标库存在同一部作品，但原本的自定义封面文件已丢失；MERGE_KEEP_LOCAL 应自动用云端封面文件修复它
    dst_sync
        .execute(
            "INSERT INTO animes (id, title, status, coverUrl, watchedEpisodes, totalEpisodes, subjectType)
             VALUES (1, '测试封面番', '在看', 'animeow-cover://native/missing_old.jpg', 3, 12, 'anime')",
            [],
        )
        .unwrap();
    let restored = restore_native_backup(
        &mut dst_sync,
        &zip_path,
        &dst_paths,
        RestoreSelection::default(),
        RestoreStrategy::MergeKeepLocal,
    )
    .unwrap();
    assert_eq!(restored.summary.cover_count, 1);

    let healed_cover: String = dst_sync
        .query_row("SELECT coverUrl FROM animes WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    assert!(
        healed_cover.starts_with("animeow-cover://restored_"),
        "broken local cover ref should be healed by incoming cover file, got: {healed_cover}"
    );
    let rel = healed_cover.strip_prefix("animeow-cover://").unwrap();
    assert!(dst_paths.covers_root.join(rel).is_file());
}
