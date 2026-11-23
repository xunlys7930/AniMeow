use animeow_core::agent::storage::{
    create_session, delete_memory, load_proposal, load_work_field_sources,
    recall_memories_local_with_tier, record_work_field_source, save_cached_cover_probe,
    upsert_memory_checked,
};
use animeow_core::agent::tools_read::{
    cleanup_orphan_native_covers, compute_cover_baseline_stats, exec_get_work_detail_with_dir,
    scan_nonstandard_release_dates,
};
use animeow_core::agent::tools_write::{
    build_rehydrate_op_from_detail_full, commit_proposal, commit_proposal_as_auto,
    commit_proposals_batch, create_work_mutations_proposal, normalize_studio_name, revert_proposal,
    revert_proposals_batch, should_auto_approve_work_proposal,
};
use animeow_core::agent::types::{AgentPolicyConfig, AutoApprovePolicy};
use animeow_core::cloud::RemoteSubjectDetail;
use animeow_core::repository::query_curator_items;
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::query_duplicate_groups;
use serde_json::json;
use tempfile::tempdir;

fn seed_anime(
    sync: &rusqlite::Connection,
    id: i64,
    title: &str,
    orig: &str,
    cover: &str,
    air_date: &str,
    watched: i64,
    total: i64,
    ext_src: Option<&str>,
    ext_id: Option<&str>,
    media_format: Option<&str>,
    rating: Option<i64>,
    public_score: Option<f64>,
) {
    let source_rating_json = public_score.map(|sc| json!({ "source": "bangumi", "score": sc }).to_string());
    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            subjectType, createdAt, airDate, externalSource, externalId, mediaFormat,
            rating, sourceRatingJson, updatedAt
        ) VALUES (?1, ?2, ?3, ?4, '在看', ?5, ?6, 'anime', '2026-11-20', ?7, ?8, ?9, ?10, ?11, ?12, 1763596800000)",
        rusqlite::params![
            id,
            title,
            orig,
            cover,
            watched,
            total,
            air_date,
            ext_src,
            ext_id,
            media_format,
            rating,
            source_rating_json,
        ],
    )
    .unwrap();
}

#[test]
fn v237_rehydrate_whitelist_tag_filter_studio_norm_and_no_format_leak() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();

    seed_anime(
        &sync,
        1682,
        "约会大作战 OVA",
        "デート・ア・ライブ OVA",
        "",
        "",
        0,
        1,
        Some("bangumi"),
        Some("85374"),
        None,
        None,
        None,
    );
    let items = query_curator_items(&sync, &local).unwrap();
    let item = items.iter().find(|i| i.id == 1682).unwrap();

    let detail = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "85374".into(),
        title: Some("约会大作战 OVA".into()),
        original_title: Some("デート・ア・ライブ OVA".into()),
        cover_url: Some("https://img-bgm.xunlys.top/pic/cover/l/85374.jpg".into()),
        cover_thumb_url: None,
        release_date: Some("2013-12-06".into()),
        total_episodes: Some(1),
        summary: Some("OVA 简介".into()),
        studio: Some("Production.IMS".into()),
        media_format: Some("OAD".into()),
        tags: vec![
            "OAD".into(),
            "OVA".into(),
            "TV".into(),
            "剧场版".into(),
            "日本".into(),
            "Production.IMS".into(),
            "ProductionIMS".into(),
            "轻小说改".into(),
            "小说改".into(),
            "约会大作战".into(),
            "后宫".into(),
            "恋爱".into(),
        ],
        public_score: Some(7.0),
    };

    assert_eq!(normalize_studio_name("Production.IMS"), "Production IMS");
    assert_eq!(normalize_studio_name("ProductionIMS"), "Production IMS");

    // 白名单仅含 cover + tags：严禁写入 workType/studio/release_date/summary，且默认过滤形态/地区/制作组/改编标签
    let built = build_rehydrate_op_from_detail_full(
        item,
        &detail,
        &["cover".into(), "tags".into()],
        true,
        None,
        true,
    );
    let op = built.op.expect("should produce op");
    assert!(op.get("media_format").is_none());
    assert!(op.get("studio_or_circle").is_none());
    assert!(op.get("release_date").is_none());
    assert_eq!(op["tags"], json!(["后宫", "恋爱"]));
    assert!(built.skipped_not_in_whitelist.iter().any(|s| s.starts_with("workType")));
    assert!(built.skipped_not_in_whitelist.iter().any(|s| s.starts_with("studio")));
    assert!(built.skipped_not_in_whitelist.iter().any(|s| s.starts_with("release_date")));

    // 落库后检查：1682 仍保持原推断形态 OVA（不被 OAD 标签劫持！），且 fieldSources 无 mediaFormat/workType
    let session = create_session(&local, None, None, None).unwrap();
    let (prop, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [op]
    }))
    .unwrap();
    let mut sync_mut = sync;
    commit_proposal(&mut sync_mut, &local, &prop.id, None).unwrap();

    let det = exec_get_work_detail_with_dir(&sync_mut, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 1682})).unwrap();
    assert_eq!(det["workType"], "OVA");
    assert_eq!(det["workTypeSource"], "inferred");
    assert_eq!(det["tags"], json!(["后宫", "恋爱"]));
    assert!(det["fieldSources"].get("mediaFormat").is_none());
    assert!(det["fieldSources"].get("workType").is_none());
    assert_eq!(det["fieldSources"]["tags"], "external");
    assert_eq!(det["fieldSources"]["cover"], "external");
    assert_eq!(det["cover"]["source"], "external_remote");

    // 撤销后，fieldSources 新增键同步移除
    revert_proposal(&mut sync_mut, &local, &prop.id).unwrap();
    let fsrc_after = load_work_field_sources(&local, "anime", 1682).unwrap();
    assert!(!fsrc_after.contains_key("tags"));
    assert!(!fsrc_after.contains_key("cover"));
}

#[test]
fn v237_field_level_lock_batch_commit_revert_overlap_noop_and_last_error() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();

    seed_anime(&sync, 1683, "约会大作战 万由里裁决", "", "", "", 0, 1, None, None, None, None, None);
    let session = create_session(&local, None, None, None).unwrap();

    // 提案 1 改 tags，提案 2 改 workType=剧场版（互不重叠字段）
    let (p1, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "title": "补全标签",
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1683, "tags": ["恋爱", "战斗"] }]
    }))
    .unwrap();
    let (p2, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "title": "修正形态",
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1683, "workType": "剧场版" }]
    }))
    .unwrap();
    assert!(p2.overlap_warnings.is_empty());
    // 形态 before 明确同时展示真值与当前推断
    assert!(p2.diffs[0].before.contains("形态:空（当前推断:TV）"));

    // 批量顺序审批：两份不重叠字段提案应全部成功（不再被粗粒度行锁误杀）
    let batch_res = commit_proposals_batch(&mut sync, &local, &[p1.id.clone(), p2.id.clone()], false).unwrap();
    assert_eq!(batch_res.applied_proposals, 2);
    assert_eq!(batch_res.total_applied_items, 2);

    let det = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 1683})).unwrap();
    assert_eq!(det["workType"], "剧场版");
    assert_eq!(det["workTypeSource"], "explicit");
    assert_eq!(det["movieFormat"], true);

    // 创建目标已达成的提案 → 自动标为 noop 而非 pending/failed
    let (p_noop, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1683, "workType": "剧场版" }]
    }))
    .unwrap();
    assert_eq!(p_noop.status, "noop");
    assert!(!p_noop.last_attempt_failed);

    // 整批撤销：逆序回滚两份提案
    let rev_res = revert_proposals_batch(&mut sync, &local, &batch_res.batch_group_id).unwrap();
    assert_eq!(rev_res.reverted_proposals, 2);
    let det_rev = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 1683})).unwrap();
    assert_eq!(det_rev["workTypeSource"], "inferred");
    assert_eq!(det_rev["tags"], json!([]));

    // 重叠字段测试：两份提案同时改 release_date
    let (pa, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "title": "写日期A",
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1683, "release_date": "2015-08-22" }]
    }))
    .unwrap();
    let (pb, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "title": "写日期B",
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1683, "release_date": "2016-01-01" }]
    }))
    .unwrap();
    assert!(!pb.overlap_warnings.is_empty());

    commit_proposal(&mut sync, &local, &pa.id, None).unwrap();
    let err = commit_proposal(&mut sync, &local, &pb.id, None).unwrap_err();
    assert!(err.to_string().contains("已被其他操作修改"));
    let (pb_reloaded, _) = load_proposal(&local, &pb.id).unwrap().unwrap();
    assert!(pb_reloaded.last_attempt_failed);
    assert!(pb_reloaded.last_error.as_deref().unwrap_or("").contains("release_date"));
}

#[test]
fn v237_work_type_conflict_progress_guard_and_date_normalization() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    seed_anime(&sync, 1598, "女神大人", "", "", "2026-10-10T16:00:00.000Z", 5, 1, None, None, None, None, None);
    let session = create_session(&local, None, None, None).unwrap();

    // workType=剧场版 与 movieFormat=false 显式冲突 → 报错同时含两个字段名与原因
    let err_conflict = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1598, "workType": "剧场版", "movieFormat": false }]
    }))
    .unwrap_err()
    .to_string();
    assert!(err_conflict.contains("workType") && err_conflict.contains("movieFormat"));

    // 修改 workType 时检查既有 progressPrimary(5) > progressTotal(1) 冲突
    let err_prog = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1598, "workType": "剧场版" }]
    }))
    .unwrap_err()
    .to_string();
    assert!(err_prog.contains("progress_primary") && err_prog.contains("progress_total"));

    // 写入带 UTC 时间戳的 release_date 被拒绝并给出东八区建议 2026-10-11
    let err_date = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 1598, "release_date": "2026-10-10T16:00:00.000Z" }]
    }))
    .unwrap_err()
    .to_string();
    assert!(err_date.contains("2026-10-11"));

    // 存量扫描只报不改
    let scan = scan_nonstandard_release_dates(&sync, &local).unwrap();
    assert_eq!(scan["nonstandardCount"], 1);
    assert_eq!(scan["items"][0]["suggestedReleaseDate"], "2026-10-11");
}

#[test]
fn v237_cover_source_semantics_baseline_stats_and_orphan_cleanup() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let native_dir = dir.path().join("covers").join("native");
    std::fs::create_dir_all(&native_dir).unwrap();

    std::fs::write(native_dir.join("rehydrate_anime_1_aaa.jpg"), vec![0xFFu8; 4096]).unwrap();
    std::fs::write(native_dir.join("custom_user_2.jpg"), vec![0xAAu8; 2048]).unwrap();
    std::fs::write(native_dir.join("rehydrate_orphan_999.jpg"), vec![0xBBu8; 8192]).unwrap();

    seed_anime(&sync, 1, "自动落盘番", "", "animeow-cover://native/rehydrate_anime_1_aaa.jpg", "2020-01-01", 0, 12, None, None, None, None, None);
    seed_anime(&sync, 2, "手传封面番", "", "animeow-cover://native/custom_user_2.jpg", "2020-01-01", 0, 12, None, None, None, None, None);
    seed_anime(&sync, 3, "远程封面番", "", "https://img-bgm.xunlys.top/pic/cover/l/3.jpg", "2020-01-01", 0, 12, None, None, None, None, None);
    record_work_field_source(&local, "anime", 1, "cover", "external").unwrap();
    record_work_field_source(&local, "anime", 2, "cover", "user").unwrap();

    let d1 = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 1})).unwrap();
    let d2 = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 2})).unwrap();
    let d3 = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 3})).unwrap();

    assert_eq!(d1["cover"]["source"], "external_native");
    assert_eq!(d1["fieldSources"]["cover"], "external");
    assert_eq!(d2["cover"]["source"], "user_custom");
    assert_eq!(d2["fieldSources"]["cover"], "user");
    assert_eq!(d3["cover"]["source"], "external_remote");
    assert_eq!(d3["fieldSources"]["cover"], "external");
    assert!(d3["coverAvailable"].is_null());

    // 写入缓存探测结果后，coverAvailable 变为 true
    save_cached_cover_probe(&local, "https://img-bgm.xunlys.top/pic/cover/l/3.jpg", true).unwrap();
    let d3_probed = exec_get_work_detail_with_dir(&sync, &local, Some(dir.path()), &json!({"kind": "anime", "work_id": 3})).unwrap();
    assert_eq!(d3_probed["coverAvailable"], true);

    let stats = compute_cover_baseline_stats(&sync, &local, dir.path()).unwrap();
    assert_eq!(stats.remote_count, 1);
    assert_eq!(stats.local_user_custom_count, 1);
    assert_eq!(stats.local_external_native_count, 1);
    assert_eq!(stats.native_dir_file_count, 3);
    assert_eq!(stats.orphan_file_count, 1);
    assert_eq!(stats.orphan_total_bytes, 8192);

    // dry_run 不删文件；正式清理仅删孤儿并保护在库文件
    let dry = cleanup_orphan_native_covers(&sync, &local, dir.path(), true).unwrap();
    assert_eq!(dry.removed_count, 0);
    assert_eq!(dry.orphan_files, vec!["rehydrate_orphan_999.jpg"]);
    assert!(native_dir.join("rehydrate_orphan_999.jpg").exists());

    let real = cleanup_orphan_native_covers(&sync, &local, dir.path(), false).unwrap();
    assert_eq!(real.removed_count, 1);
    assert_eq!(real.freed_bytes, 8192);
    assert!(!native_dir.join("rehydrate_orphan_999.jpg").exists());
    assert!(native_dir.join("rehydrate_anime_1_aaa.jpg").exists());
    assert!(native_dir.join("custom_user_2.jpg").exists());
}

#[test]
fn v237_auto_approve_redlines_and_auto_applied_revert() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    seed_anime(&sync, 10, "自动审批测试番", "", "", "", 0, 12, None, None, None, None, None);
    let session = create_session(&local, None, None, None).unwrap();

    let policy = AgentPolicyConfig {
        trust_mode: "auto_safe".into(),
        auto_approve: AutoApprovePolicy {
            quick_log: true,
            rehydrate_empty: true,
            high_confidence_bind: true,
            create_from_catalog: false,
            append_tags_only: false,
            max_items_per_auto_batch: 20,
        },
        ..Default::default()
    };

    // 1. 删除操作（红线）：即便 session_autopilot=true 也必须拦截
    let (p_del, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "soft_delete", "kind": "anime", "work_id": 10 }]
    }))
    .unwrap();
    assert!(!should_auto_approve_work_proposal(&p_del, &policy, true, "other_safe", false, &local));

    // 2. 覆盖用户手填字段（红线）：拒绝自动放行
    record_work_field_source(&local, "anime", 10, "release_date", "user").unwrap();
    let (p_overwrite, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 10, "release_date": "2021-01-01" }]
    }))
    .unwrap();
    assert!(!should_auto_approve_work_proposal(&p_overwrite, &policy, true, "rehydrate_empty", false, &local));

    // 3. 安全可撤销回填：auto_safe 允许放行，落库为 auto_applied，且可完整一键撤销
    let (p_safe, _) = create_work_mutations_proposal(&sync, &local, &session.id, &json!({
        "operations": [{ "action": "update", "kind": "anime", "work_id": 10, "tags": ["悬疑"] }]
    }))
    .unwrap();
    assert!(should_auto_approve_work_proposal(&p_safe, &policy, false, "rehydrate_empty", false, &local));
    let applied = commit_proposal_as_auto(&mut sync, &local, &p_safe.id).unwrap();
    assert_eq!(applied.proposal.status, "auto_applied");
    assert!(applied.proposal.can_revert);

    let reverted = revert_proposal(&mut sync, &local, &p_safe.id).unwrap();
    assert_eq!(reverted.proposal.status, "reverted");
}

#[test]
fn v237_p0_p1_p2_duplicates_memory_book_unit_and_score_source() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();

    // P1-3：5 组真实续作/外传（零误判） + 1 组同 externalId 真重复（精确命中）
    seed_anime(&sync, 1431, "Fate/kaleid liner 魔法少女☆伊莉雅 2wei!", "", "", "2014-07-09", 0, 10, Some("bangumi"), Some("83402"), None, None, None);
    seed_anime(&sync, 1432, "Fate/kaleid liner 魔法少女☆伊莉雅 2wei Herz!", "", "", "2015-07-24", 0, 10, Some("bangumi"), Some("113134"), None, None, None);
    seed_anime(&sync, 1401, "我的青春恋爱物语果然有问题 续", "", "", "2015-04-02", 0, 13, Some("bangumi"), Some("102134"), None, None, None);
    seed_anime(&sync, 1402, "我的青春恋爱物语果然有问题 完", "", "", "2020-07-09", 0, 12, Some("bangumi"), Some("277554"), None, None, None);
    seed_anime(&sync, 1409, "CLANNAD 〜AFTER STORY〜", "", "", "2008-10-02", 0, 24, Some("bangumi"), Some("876"), None, None, None);
    seed_anime(&sync, 1408, "CLANNAD 〜AFTER STORY〜 另一个世界 杏篇", "", "", "2009-07-01", 0, 1, Some("bangumi"), Some("18580"), None, None, None);
    seed_anime(&sync, 1423, "Re:从零开始的异世界生活 第二季", "", "", "2020-07-08", 0, 13, Some("bangumi"), Some("278826"), None, None, None);
    seed_anime(&sync, 1424, "Re:从零开始的异世界生活 第二季 后半部分", "", "", "2021-01-06", 0, 12, Some("bangumi"), Some("308961"), None, None, None);
    seed_anime(&sync, 1443, "刀剑神域 爱丽丝篇 异界战争", "", "", "2019-10-12", 0, 12, Some("bangumi"), Some("279457"), None, None, None);
    seed_anime(&sync, 1444, "刀剑神域 爱丽丝篇 异界战争 -终章-", "", "", "2020-07-11", 0, 11, Some("bangumi"), Some("292238"), None, None, None);

    // 真重复（未绑定外部ID但同一年且同名标点差）
    seed_anime(&sync, 2001, "孤独摇滚", "", "", "2022-10-08", 12, 12, None, None, None, None, None);
    seed_anime(&sync, 2002, "孤独摇滚！", "", "", "2022-10-08", 0, 12, None, None, None, None, None);

    let dups = query_duplicate_groups(&sync).unwrap();
    assert_eq!(dups.len(), 1, "expected only the true duplicate group, got: {dups:?}");
    let dup_ids: Vec<i64> = dups[0].candidates.iter().map(|c| c.id).collect();
    assert!(dup_ids.contains(&2001) && dup_ids.contains(&2002));

    // P0-1：区分 suspected_external 与 user 评分来源
    seed_anime(&sync, 1634, "安达与岛村", "", "", "2020-10-08", 0, 12, None, None, None, Some(91), Some(9.1));
    seed_anime(&sync, 1535, "与你相恋到生命尽头", "", "", "2021-01-01", 0, 12, None, None, None, Some(78), None);
    let d1634 = exec_get_work_detail_with_dir(&sync, &local, None, &json!({"kind": "anime", "work_id": 1634})).unwrap();
    let d1535 = exec_get_work_detail_with_dir(&sync, &local, None, &json!({"kind": "anime", "work_id": 1535})).unwrap();
    assert_eq!(d1634["personalScoreSource"], "suspected_external");
    assert_eq!(d1535["personalScoreSource"], "user");

    // P1-4：仅有 bookReadChapters 而无 bookReadVolumes 的书籍自动推断为「漫画」「话」
    sync.execute(
        "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes, subjectType, bookReadChapters, createdAt, updatedAt)
         VALUES (1600, '无职转生', '看完', 262, 262, 'book', 262, '2026-01-01', 1763596800000)",
        [],
    )
    .unwrap();
    let d1600 = exec_get_work_detail_with_dir(&sync, &local, None, &json!({"kind": "book", "work_id": 1600})).unwrap();
    assert_eq!(d1600["workType"], "漫画");
    assert_eq!(d1600["progressUnit"], "话");

    // P1-2 + P2-5：core 单一数据源去重、tier 过滤、删除即时同步、中文意图同义与多词 AND
    let (m1, dup1) = upsert_memory_checked(&local, None, "avoid", Some("core"), "讨厌慢热和强行刀人", true).unwrap();
    assert!(!dup1);
    let (m1_again, dup2) = upsert_memory_checked(&local, None, "avoid", Some("core"), "讨厌慢热和强行刀人", true).unwrap();
    assert!(dup2);
    assert_eq!(m1.id, m1_again.id);

    let (_m2, _) = upsert_memory_checked(&local, None, "preference", Some("archive"), "最喜欢紫色独角兽主题", true).unwrap();

    // 自然中文问句命中 avoid
    let hit_avoid = recall_memories_local_with_tier(&local, "推荐时应该避开什么", None, Some("all"), "and").unwrap();
    assert_eq!(hit_avoid.len(), 1);
    assert_eq!(hit_avoid[0].id, m1.id);

    // 多词 AND 模式："紫色 棒球" 不误召回仅含 "紫色" 的条目
    let hit_and = recall_memories_local_with_tier(&local, "紫色 棒球", None, Some("all"), "and").unwrap();
    assert!(hit_and.is_empty());

    // tier="core" 过滤只返回 core，删除后立即消失
    let core_only = recall_memories_local_with_tier(&local, "", None, Some("core"), "and").unwrap();
    assert_eq!(core_only.len(), 1);
    assert_eq!(core_only[0].id, m1.id);

    delete_memory(&local, &m1.id).unwrap();
    let core_after_del = recall_memories_local_with_tier(&local, "", None, Some("core"), "and").unwrap();
    assert!(core_after_del.is_empty());
}

#[test]
fn v237_prompt_plain_chinese_vs_diagnostic_and_worktype_auto_approve_redline() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, None, None, None).unwrap();
    seed_anime(&sync, 1800, "魔法少女伊莉雅", "", "", "", 3, 10, None, None, None, None, None);

    // 1. 系统提示词在普通人设下不暴露内部 ID 与英文记忆类别；专业诊断模式完整保留
    let (mem, _) = upsert_memory_checked(&local, None, "avoid", Some("core"), "不看强行胃痛剧情", true).unwrap();
    let policy = AgentPolicyConfig::default();
    let normal_prompt = animeow_core::agent::prompt::build_static_system_prompt(
        &policy,
        Some("curator"),
        std::slice::from_ref(&mem),
        &[],
        true,
    );
    let short_id: String = mem.id.chars().take(8).collect();
    assert!(normal_prompt.contains("[避雷项] 不看强行胃痛剧情"));
    assert!(!normal_prompt.contains(&format!("id:{short_id}")));
    assert!(!normal_prompt.contains("[avoid|"));

    let diag_prompt = animeow_core::agent::prompt::build_static_system_prompt(
        &policy,
        Some("diagnostic"),
        std::slice::from_ref(&mem),
        &[],
        true,
    );
    assert!(diag_prompt.contains("专业诊断模式"));
    assert!(diag_prompt.contains(&format!("[avoid|id:{short_id}] 不看强行胃痛剧情")));

    // 2. 自动审批红线：即使 op 使用 workType 别名，只要 fieldSources.workType == "user" 就严禁自动覆盖；
    //    且 is_regenerate == true 时任何自动审批一律拦截。
    record_work_field_source(&local, "anime", 1800, "workType", "user").unwrap();
    let mut auto_policy = AgentPolicyConfig::default();
    auto_policy.trust_mode = "auto_safe".into();
    auto_policy.auto_approve.rehydrate_empty = true;
    auto_policy.auto_approve.quick_log = true;

    let (p_wt, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "operations": [{"action": "update", "kind": "anime", "work_id": 1800, "workType": "OVA"}]
        }),
    )
    .unwrap();
    assert!(
        !should_auto_approve_work_proposal(&p_wt, &auto_policy, true, "rehydrate_empty", false, &local),
        "用户手填的 workType 即使在会话托管下也不得被自动审批覆盖"
    );

    let (p_ql, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "operations": [{"action": "update", "kind": "anime", "work_id": 1800, "progress_primary": 4}]
        }),
    )
    .unwrap();
    assert!(should_auto_approve_work_proposal(&p_ql, &auto_policy, false, "quick_log", false, &local));
    assert!(
        !should_auto_approve_work_proposal(&p_ql, &auto_policy, true, "quick_log", true, &local),
        "重生成模式下绝不允许自动审批执行写库"
    );
}
