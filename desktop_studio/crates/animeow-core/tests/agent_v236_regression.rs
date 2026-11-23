use animeow_core::agent::prompt::{build_static_system_prompt, build_trailing_runtime_reminder};
use animeow_core::agent::storage::{
    insert_message, list_memories, load_verify_batch_bindings, read_proposal_statuses_paged,
    save_verify_batch_result,
};
use animeow_core::agent::tools_read::{
    exec_get_work_detail, exec_query_library, score_bangumi_candidate, LocalWorkVerifyTarget,
};
use animeow_core::agent::tools_write::{
    build_rehydrate_op_from_detail, create_character_mutations_proposal,
    create_merge_duplicates_proposal, create_series_mutations_proposal,
    create_work_mutations_proposal,
};
use animeow_core::agent::{
    commit_proposal, create_session, export_session_feedback_diagnostics,
    fork_session_from_message, get_policy_config, invalidate_pending_proposals_on_overwrite,
    load_proposal, record_run_event, revert_proposal, save_tool_artifact, upsert_memory,
    ActiveContextChip, AgentMessage, AgentPolicyConfig,
};
use animeow_core::cloud::RemoteSubjectDetail;
use animeow_core::repository::query_curator_items;
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::RemoteCatalogHit;
use rusqlite::Connection;
use serde_json::json;

fn setup_dbs() -> (tempfile::TempDir, Connection, Connection) {
    let dir = tempfile::tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    (dir, sync, local)
}

fn insert_anime(
    sync: &Connection,
    id: i64,
    title: &str,
    original_title: &str,
    release_date: &str,
    cover_url: &str,
    total_ep: i64,
    bgm_id: Option<&str>,
    review: &str,
    tags: &[&str],
) {
    sync.execute(
        "INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, airDate, coverUrl, review, externalSource, externalId, updatedAt)
         VALUES (?1, ?2, ?3, '在看', 1, ?4, ?5, ?6, ?7, ?8, ?9, 1700000000000)",
        rusqlite::params![
            id,
            title,
            original_title,
            total_ep,
            release_date,
            cover_url,
            review,
            bgm_id.map(|_| "bangumi"),
            bgm_id,
        ],
    )
    .unwrap();
    for tag in tags {
        sync.execute(
            "INSERT OR IGNORE INTO tags (name) VALUES (?1)",
            rusqlite::params![tag],
        )
        .unwrap();
        let tid: i64 = sync
            .query_row("SELECT id FROM tags WHERE name = ?1", [tag], |r| r.get(0))
            .unwrap();
        sync.execute(
            "INSERT OR IGNORE INTO anime_tags (animeId, tagId) VALUES (?1, ?2)",
            rusqlite::params![id, tid],
        )
        .unwrap();
    }
}

#[test]
fn d1_update_supports_release_date_clear_validation_and_revert() {
    let (_dir, mut sync, local) = setup_dbs();
    let sess = create_session(&local, Some("D1测试"), None, None).unwrap();
    insert_anime(
        &sync,
        1679,
        "约会大作战 第二季",
        "デート・ア・ライブII",
        "",
        "",
        10,
        Some("76325"),
        "",
        &[],
    );

    // 1. 非法日期必须拒绝
    let bad = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "坏日期",
            "operations": [{"action": "update", "kind": "anime", "work_id": 1679, "release_date": "2014/99/99"}]
        }),
    );
    assert!(bad.is_err());

    // 2. 合法日期写入 + 撤销
    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "补全开播日期",
            "operations": [{"action": "update", "kind": "anime", "work_id": 1679, "release_date": "2014-04-11"}]
        }),
    )
    .unwrap();
    let applied = commit_proposal(&mut sync, &local, &prop.id, None).unwrap();
    assert_eq!(applied.applied_count, 1);
    let date_after: String = sync
        .query_row("SELECT airDate FROM animes WHERE id = 1679", [], |r| r.get(0))
        .unwrap();
    assert_eq!(date_after, "2014-04-11");

    // 3. 传空字符串清空
    let (clear_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "清空日期",
            "operations": [{"action": "update", "kind": "anime", "work_id": 1679, "release_date": ""}]
        }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &clear_prop.id, None).unwrap();
    let date_cleared: Option<String> = sync
        .query_row("SELECT airDate FROM animes WHERE id = 1679", [], |r| r.get(0))
        .unwrap();
    assert!(date_cleared.unwrap_or_default().is_empty());

    // 4. 撤销清空恢复 2014-04-11
    revert_proposal(&mut sync, &local, &clear_prop.id).unwrap();
    let date_restored: String = sync
        .query_row("SELECT airDate FROM animes WHERE id = 1679", [], |r| r.get(0))
        .unwrap();
    assert_eq!(date_restored, "2014-04-11");
}

#[test]
fn d3_d5_d7_detail_exposes_cover_and_updated_at_and_query_catches_skeletons() {
    let (_dir, sync, local) = setup_dbs();
    insert_anime(
        &sync,
        1369,
        "约会大作战",
        "デート・ア・ライブ",
        "2013-04-05",
        "https://lain.bgm.tv/pic/cover/l/11/22/49131.jpg",
        12,
        Some("49131"),
        "经典后宫轻改",
        &["科幻", "恋爱"],
    );
    insert_anime(
        &sync,
        1679,
        "约会大作战 第二季",
        "デート・ア・ライブII",
        "",
        "",
        10,
        Some("76325"),
        "",
        &[],
    );
    insert_anime(
        &sync,
        1680,
        "约会大作战 第三季",
        "デート・ア・ライブIII",
        "",
        "",
        12,
        Some("228254"),
        "",
        &[],
    );
    insert_anime(
        &sync,
        1670,
        "剧场版 想要成为影之实力者！ 残响篇",
        "劇場版 陰の実力者になりたくて！ 残響編",
        "",
        "",
        0,
        Some("471345"),
        "",
        &[],
    );

    // D3 + D7: get_work_detail 暴露封面存在性、来源与 updatedAt
    let detail_full = exec_get_work_detail(&sync, &local, &json!({"kind": "anime", "work_id": 1369})).unwrap();
    assert_eq!(detail_full["missingCover"], false);
    assert_eq!(detail_full["cover"]["type"], "remote_url");
    assert_eq!(detail_full["cover"]["source"], "external_remote");
    // 未探测过的远程 URL 严禁假标 true，须返回 null，直至写入探测缓存后才返回 true/false
    assert!(detail_full["cover"]["available"].is_null());
    assert!(detail_full["updatedAt"].as_i64().unwrap() > 0);

    let detail_skel = exec_get_work_detail(&sync, &local, &json!({"kind": "anime", "work_id": 1679})).unwrap();
    assert_eq!(detail_skel["missingCover"], true);
    assert_eq!(detail_skel["cover"]["type"], "none");
    assert_eq!(detail_skel["cover"]["available"], false);

    // D5: missing_cover 和 incomplete_metadata 一次捞出三条骨架，不误捞 1369
    let missing_cov = exec_query_library(&sync, &local, &json!({"missing_cover": true, "limit": 20})).unwrap();
    let ids: Vec<i64> = missing_cov["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|it| it["id"].as_i64().unwrap())
        .collect();
    assert_eq!(ids.len(), 3);
    assert!(ids.contains(&1679) && ids.contains(&1680) && ids.contains(&1670));
    assert!(!ids.contains(&1369));

    let incomplete = exec_query_library(
        &sync,
        &local,
        &json!({"incomplete_metadata": true, "incomplete_fields": ["cover", "release_date", "tags"], "limit": 20}),
    )
    .unwrap();
    assert_eq!(incomplete["total"], 3);
}

#[test]
fn d4_rehydrate_fills_blanks_only_preserves_complete_and_reports_remote_gaps() {
    let (_dir, sync, local) = setup_dbs();
    insert_anime(
        &sync,
        1369,
        "约会大作战",
        "デート・ア・ライブ",
        "2013-04-05",
        "https://lain.bgm.tv/pic/cover/l/11/22/49131.jpg",
        12,
        Some("49131"),
        "用户手写长评",
        &["用户自定义标签"],
    );
    insert_anime(
        &sync,
        1679,
        "约会大作战 第二季",
        "デート・ア・ライブII",
        "",
        "",
        10,
        Some("76325"),
        "",
        &[],
    );
    insert_anime(
        &sync,
        1670,
        "剧场版 想要成为影之实力者！ 残响篇",
        "劇場版 陰の実力者になりたくて！ 残響編",
        "",
        "",
        0,
        Some("471345"),
        "",
        &[],
    );

    let items = query_curator_items(&sync, &local).unwrap();
    let item_1369 = items.iter().find(|i| i.id == 1369).unwrap();
    let item_1679 = items.iter().find(|i| i.id == 1679).unwrap();
    let item_1670 = items.iter().find(|i| i.id == 1670).unwrap();

    let whitelist = vec![
        "cover".to_string(),
        "release_date".to_string(),
        "tags".to_string(),
        "total_episodes".to_string(),
    ];

    // 1369 已有完整字段：回填 diff 必须为空，绝不覆盖用户标签和日期
    let detail_1369 = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "49131".into(),
        title: Some("约会大作战".into()),
        original_title: Some("デート・ア・ライブ".into()),
        release_date: Some("2099-01-01".into()),
        total_episodes: Some(99),
        cover_url: Some("https://lain.bgm.tv/new.jpg".into()),
        cover_thumb_url: None,
        summary: Some("远端简介".into()),
        studio: Some("AIC PLUS+".into()),
        media_format: Some("TV".into()),
        tags: vec!["远端标签".into()],
        public_score: Some(7.1),
    };
    let (op_1369, filled_1369, _) =
        build_rehydrate_op_from_detail(item_1369, &detail_1369, &whitelist, false);
    assert!(op_1369.is_none());
    assert!(filled_1369.is_empty());

    // 1679 骨架记录：填补 cover + release_date + tags
    let detail_1679 = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "76325".into(),
        title: Some("约会大作战 第二季".into()),
        original_title: Some("デート・ア・ライブII".into()),
        release_date: Some("2014-04-11".into()),
        total_episodes: Some(10),
        cover_url: Some("https://lain.bgm.tv/pic/cover/l/76325.jpg".into()),
        cover_thumb_url: None,
        summary: Some("第二季简介".into()),
        studio: Some("Production IMS".into()),
        media_format: Some("TV".into()),
        tags: vec!["轻小说改".into(), "后宫".into()],
        public_score: Some(6.8),
    };
    let (op_1679, filled_1679, missing_1679) =
        build_rehydrate_op_from_detail(item_1679, &detail_1679, &whitelist, true);
    let op_1679 = op_1679.unwrap();
    assert_eq!(op_1679["release_date"], "2014-04-11");
    assert_eq!(op_1679["cover_url"], "https://lain.bgm.tv/pic/cover/l/76325.jpg");
    // 默认开启元数据标签过滤：「轻小说改」被过滤，仅保留纯净题材标签「后宫」
    assert_eq!(op_1679["tags"], json!(["后宫"]));
    assert!(filled_1679.contains(&"release_date".to_string()));
    assert!(missing_1679.is_empty());

    // 1670 远端缺日期与集数：显式报告 missing_remote，不静默成功
    let detail_1670 = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "471345".into(),
        title: Some("剧场版 想要成为影之实力者！ 残响篇".into()),
        original_title: Some("劇場版 陰の実力者になりたくて！ 残響編".into()),
        release_date: None,
        total_episodes: None,
        cover_url: None,
        cover_thumb_url: None,
        summary: None,
        studio: None,
        media_format: Some("剧场版".into()),
        tags: vec![],
        public_score: None,
    };
    let (op_1670, filled_1670, missing_1670) =
        build_rehydrate_op_from_detail(item_1670, &detail_1670, &whitelist, true);
    assert!(op_1670.is_none());
    assert!(filled_1670.is_empty());
    assert!(missing_1670.iter().any(|s| s.starts_with("release_date(")));
    assert!(missing_1670.iter().any(|s| s.starts_with("total_episodes(")));
    assert!(missing_1670.iter().any(|s| s.starts_with("cover(")));
}

#[test]
fn d6_score_bangumi_candidate_never_hides_date_or_episode_mismatch_or_missing() {
    let target = LocalWorkVerifyTarget {
        kind: "anime".into(),
        work_id: 1670,
        title: "剧场版 想要成为影之实力者！ 残响篇".into(),
        original_title: "劇場版 陰の実力者になりたくて！ 残響編".into(),
        release_date: "2025-01-01".into(),
        total_episodes: 1,
        media_format: Some("剧场版".into()),
        tags: vec![],
        existing_bangumi_id: Some("471345".into()),
    };
    let hit = RemoteCatalogHit {
        kind: "anime".into(),
        external_source: "bangumi".into(),
        external_id: "471345".into(),
        title: "剧场版 想要成为影之实力者！ 残响篇".into(),
        original_title: "劇場版 陰の実力者になりたくて！ 残響編".into(),
        cover_url: "".into(),
        release_date: "".into(),
        total_episodes: 0,
        studio_or_circle: "".into(),
        public_score: None,
        synopsis: "".into(),
    };
    let scored = score_bangumi_candidate(&target, &hit);
    assert_eq!(scored.evidence["date_eq"], false);
    assert_eq!(scored.evidence["episodes_eq"], false);
    assert_eq!(scored.evidence["date_status"], "missing_remote");
    assert_eq!(scored.evidence["episodes_status"], "missing_remote");
    assert!(scored.reason.contains("日期"), "reason={}", scored.reason);
    assert!(scored.reason.contains("集数"), "reason={}", scored.reason);
}

#[test]
fn cross_carrier_id_collision_overwrite_invalidation_and_merge_full_undo() {
    let (_dir, mut sync, local) = setup_dbs();
    let sess = create_session(&local, Some("安全测试"), None, None).unwrap();

    // 同一 id=1 同时存在于 animes 和 games
    insert_anime(&sync, 1, "命运石之门 动画", "Steins;Gate", "2011-04-06", "", 24, Some("10380"), "", &["科幻"]);
    insert_anime(&sync, 2, "命运石之门 重复条目", "Steins;Gate", "", "", 24, None, "", &["时间旅行"]);
    sync.execute(
        "INSERT INTO games (id, syncId, title, originalTitle, status, coverUrl, updatedAt) VALUES (1, 'game-sg-1', '命运石之门 游戏', 'STEINS;GATE', '已通关', '', '2026-11-21T00:00:00Z')",
        [],
    )
    .unwrap();

    // 系列提案不传 kind 时必须拒绝跨载体同 ID 歧义
    let ambig_series = create_series_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "归并系列",
            "operations": [{"op": "assign_series", "series_name": "科学ADV", "work_ids": [1]}]
        }),
    );
    assert!(ambig_series.is_err());

    // 重复合并 + 完整撤销恢复主/从条目及各自标签
    let merge_prop = create_merge_duplicates_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "合并命运石之门重复项",
            "merges": [{"kind": "anime", "keep_id": 1, "drop_ids": [2]}]
        }),
    )
    .unwrap();
    let applied = commit_proposal(&mut sync, &local, &merge_prop.id, None).unwrap();
    assert_eq!(applied.applied_count, 1);
    assert!(applied.proposal.can_revert);
    let drop_deleted: Option<String> = sync
        .query_row("SELECT deletedAt FROM animes WHERE id = 2", [], |r| r.get(0))
        .unwrap();
    assert!(drop_deleted.is_some());

    // 撤销合并：被删条目恢复活跃，原标签恢复
    revert_proposal(&mut sync, &local, &merge_prop.id).unwrap();
    let drop_restored: Option<String> = sync
        .query_row("SELECT deletedAt FROM animes WHERE id = 2", [], |r| r.get(0))
        .unwrap();
    assert!(drop_restored.is_none());

    // 覆盖恢复使所有 pending 提案失效为 expired
    let (pending_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "待过期提案",
            "operations": [{"action": "update", "kind": "anime", "work_id": 1, "status": "已看"}]
        }),
    )
    .unwrap();
    let expired_cnt = invalidate_pending_proposals_on_overwrite(&local).unwrap();
    assert_eq!(expired_cnt, 1);
    let (loaded, _) = load_proposal(&local, &pending_prop.id).unwrap().unwrap();
    assert_eq!(loaded.status, "expired");
}

#[test]
fn partial_execution_receipt_character_undo_batch_tags_and_privacy_checks() {
    let (_dir, mut sync, local) = setup_dbs();
    let sess = create_session(&local, Some("综合验收"), None, None).unwrap();
    insert_anime(&sync, 10, "作品A", "WorkA", "2020-01-01", "", 12, None, "", &["校园"]);
    insert_anime(&sync, 11, "作品B", "WorkB", "2020-01-01", "", 12, None, "", &["校园"]);
    insert_anime(&sync, 12, "作品C", "WorkC", "2020-01-01", "", 12, None, "", &["校园"]);

    // 1. 部分勾选回执 applied_indices + get_proposal_status 优先返回已执行项
    let (multi_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "改三部状态",
            "operations": [
                {"action": "update", "kind": "anime", "work_id": 10, "status": "已看"},
                {"action": "update", "kind": "anime", "work_id": 11, "status": "已看"},
                {"action": "update", "kind": "anime", "work_id": 12, "status": "已看"}
            ]
        }),
    )
    .unwrap();
    let part = commit_proposal(&mut sync, &local, &multi_prop.id, Some(&[1])).unwrap();
    assert_eq!(part.applied_count, 1);
    assert_eq!(part.applied_indices, vec![1]);
    let statuses = read_proposal_statuses_paged(&local, &sess.id, Some(&multi_prop.id), 0, 10).unwrap();
    assert_eq!(statuses[0]["appliedIndices"], json!([1]));
    assert_eq!(statuses[0]["items"][0]["index"], 1);
    assert_eq!(statuses[0]["items"][0]["executionStatus"], "applied");

    // 2. 批量清单 add_tags / remove_tags 不破坏原标签并支持撤销
    let all_items = query_curator_items(&sync, &local).unwrap();
    let (res_id, _) = save_tool_artifact(
        &local,
        &sess.id,
        "library_result",
        &serde_json::to_string(&all_items).unwrap(),
        200,
    )
    .unwrap();
    let (tag_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "批量加标签",
            "result_id": res_id,
            "patch": {"add_tags": ["必看"]}
        }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &tag_prop.id, None).unwrap();
    let after_tags = query_curator_items(&sync, &local)
        .unwrap()
        .into_iter()
        .find(|i| i.id == 10)
        .unwrap()
        .tags;
    assert!(after_tags.contains(&"校园".to_string()) && after_tags.contains(&"必看".to_string()));
    revert_proposal(&mut sync, &local, &tag_prop.id).unwrap();
    let reverted_tags = query_curator_items(&sync, &local)
        .unwrap()
        .into_iter()
        .find(|i| i.id == 10)
        .unwrap()
        .tags;
    assert!(reverted_tags.contains(&"校园".to_string()) && !reverted_tags.contains(&"必看".to_string()));

    // 3. 角色创建+绑作、打标签均可撤销
    let char_prop = create_character_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "建角色并绑作",
            "operations": [
                {"action": "upsert_character", "name": "牧濑红莉栖", "name_cn": "牧濑红莉栖", "anime_id": 10, "role_name": "主角"}
            ]
        }),
    )
    .unwrap();
    let c_applied = commit_proposal(&mut sync, &local, &char_prop.id, None).unwrap();
    assert!(c_applied.proposal.can_revert);
    revert_proposal(&mut sync, &local, &char_prop.id).unwrap();
    let char_cnt: i64 = sync
        .query_row("SELECT COUNT(*) FROM characters WHERE name = '牧濑红莉栖'", [], |r| r.get(0))
        .unwrap();
    assert_eq!(char_cnt, 0);

    // 4. 分叉会话复制 ver_ 句柄
    let ver_id = save_verify_batch_result(
        &local,
        &sess.id,
        &[json!({"kind": "anime", "work_id": 10, "external_source": "bangumi", "external_id": "123"})],
    )
    .unwrap();
    let msg = AgentMessage {
        id: "msg_fork_ver_1".into(),
        session_id: sess.id.clone(),
        role: "assistant".into(),
        content: format!("已保存核实结果 {ver_id}"),
        reasoning_content: None,
        tool_calls: None,
        tool_call_id: None,
        tool_name: None,
        tool_traces: vec![],
        ui_actions: vec![],
        settings_undo: None,
        memory_events: vec![],
        proposal_id: None,
        proposal_ids: vec![],
        diagnostic_error: None,
        token_usage: None,
        stopped: false,
        web_sources: vec![],
        runtime_context: None,
        versions: vec![],
        active_version_idx: 0,
        created_at: "2026-11-21T00:00:00Z".into(),
    };
    insert_message(&local, &msg).unwrap();
    let forked = fork_session_from_message(&local, &sess.id, Some(&msg.id), Some("分叉测试")).unwrap();
    let forked_msgs = animeow_core::agent::load_session_messages(&local, &forked.id).unwrap();
    let forked_ver_id = forked_msgs[0]
        .content
        .split_whitespace()
        .find(|w| w.starts_with("ver_"))
        .expect("分叉消息应包含重映射后的 ver_ 句柄");
    assert_ne!(forked_ver_id, ver_id);
    let copied = load_verify_batch_bindings(&local, forked_ver_id, Some(&forked.id)).unwrap();
    assert_eq!(copied.len(), 1);

    // 5. 脱敏诊断导出不含书名和对话正文
    record_run_event(
        &local,
        "run_1",
        &sess.id,
        1,
        "tool_call",
        "查询藏品：绝密私人书名XYZ",
        "ok",
        42,
        &json!({"tool": "query_library", "keyword": "绝密私人书名XYZ", "userMessage": "不要泄露这句话"}),
    )
    .unwrap();
    let diag = export_session_feedback_diagnostics(&local, &sess.id, "2.3.6").unwrap();
    assert!(!diag.contains("绝密私人书名XYZ"));
    assert!(!diag.contains("不要泄露这句话"));
    assert!(diag.contains("query_library"));

    // 6. 静态前缀不含动态归档记忆数 & 防剧透开关尊重用户关闭
    let mut pol: AgentPolicyConfig = get_policy_config(&local).unwrap();
    pol.default_anti_spoiler = true;
    let mems_before = list_memories(&local).unwrap();
    let p1 = build_static_system_prompt(&pol, Some("curator"), &mems_before, &[], true);
    let _ = upsert_memory(&local, None, "preference", Some("archive"), "喜欢科幻", true).unwrap();
    let mems_after = list_memories(&local).unwrap();
    let p2 = build_static_system_prompt(&pol, Some("curator"), &mems_after, &[], true);
    assert_eq!(p1, p2);

    let chip_off = ActiveContextChip {
        active_tab: "curator".into(),
        active_sub_view: Some("shelf".into()),
        work_kind: Some("anime".into()),
        work_id: Some(10),
        work_title: Some("作品A".into()),
        work_status: Some("在看".into()),
        media_format: Some("TV".into()),
        progress_unit: Some("集".into()),
        progress_primary: Some(3),
        progress_total: Some(12),
        anti_spoiler_enabled: false,
        filter_result_id: None,
        filter_count: None,
        filter_label: None,
    };
    let reminder_off = build_trailing_runtime_reminder(&pol, Some(&chip_off), "动画3部");
    assert!(!reminder_off.contains("防剧透锁已开启"));

    // 7. 回收站漫画保留「话」单位
    sync.execute(
        "INSERT INTO animes (id, title, originalTitle, subjectType, mediaFormat, status, watchedEpisodes, totalEpisodes, bookReadChapters, deletedAt, updatedAt)
         VALUES (99, '测试漫画', 'Test Manga', 'book', '漫画', '在看', 5, 50, 5, '2026-11-20T00:00:00Z', 1700000000000)",
        [],
    )
    .unwrap();
    let deleted_manga = exec_get_work_detail(
        &sync,
        &local,
        &json!({"kind": "book", "work_id": 99, "include_deleted": true}),
    )
    .unwrap();
    assert_eq!(deleted_manga["progressUnit"], "话");
}

#[test]
fn d2_d3_d5_d6_d7_d8_d9_d10_full_acceptance_and_auto_enriched_create() {
    use animeow_core::agent::tools_read::exec_get_work_detail_with_dir;
    use animeow_core::agent::tools_write::{
        build_rehydrate_op_from_detail_with_cover, enrich_create_op_from_detail,
    };

    let (dir, mut sync, local) = setup_dbs();
    let sess = create_session(&local, Some("D1-D11验收"), None, None).unwrap();

    // 验收 3 (D2 + D7): create 剧场版同时传 progress_primary=1, progress_total=1, workType="剧场版", tags
    let bad_create = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "越界进度",
            "operations": [{
                "action": "create",
                "kind": "anime",
                "title": "剧场版 越界测试",
                "progress_primary": 2,
                "progress_total": 1
            }]
        }),
    );
    assert!(bad_create.is_err());

    let (create_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "一次新建已看剧场版",
            "operations": [{
                "action": "create",
                "kind": "anime",
                "title": "剧场版 约会大作战 万由里裁决",
                "status": "看完",
                "workType": "剧场版",
                "movieFormat": true,
                "progress_primary": 1,
                "progress_total": 1,
                "release_date": "2015-08-22",
                "tags": ["剧场版", "轻改"]
            }]
        }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &create_prop.id, None).unwrap();
    let created_id: i64 = sync
        .query_row(
            "SELECT id FROM animes WHERE title = '剧场版 约会大作战 万由里裁决'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    let created_detail = exec_get_work_detail_with_dir(
        &sync,
        &local,
        Some(dir.path()),
        &json!({"kind": "anime", "work_id": created_id}),
    )
    .unwrap();
    assert_eq!(created_detail["progressPrimary"], 1);
    assert_eq!(created_detail["progressTotal"], 1);
    assert_eq!(created_detail["workType"], "剧场版");
    assert_eq!(created_detail["movieFormat"], true);
    assert_eq!(created_detail["coverType"], "none");
    assert_eq!(created_detail["missingCover"], true);

    // 验收 8 (D3 + D7): update 修正 workType=剧场版/mediaFormat=剧场版动画 & workType=OAD & cover_url 本地落盘
    insert_anime(&sync, 1683, "约会大作战 赤黑新章：虚或实", "デート・ア・バレット デッド・オア・バレット", "2020-08-14", "", 1, Some("290980"), "", &[]);
    insert_anime(&sync, 1682, "约会大作战 OAD", "デート・ア・ライブ OAD", "2013-12-06", "", 1, Some("76326"), "", &[]);
    let cover_rel = "native/rehydrate_anime_1683_test.jpg";
    let cover_disk_path = dir.path().join("covers").join(cover_rel);
    std::fs::create_dir_all(cover_disk_path.parent().unwrap()).unwrap();
    std::fs::write(&cover_disk_path, b"\xFF\xD8\xFF\xE0testjpegbytes").unwrap();
    let local_cover_uri = format!("animeow-cover://{cover_rel}");

    let (fix_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "修正剧场版与OAD形态并写本地封面",
            "operations": [
                {"action": "update", "kind": "anime", "work_id": 1683, "workType": "剧场版", "mediaFormat": "剧场版动画", "cover_url": local_cover_uri},
                {"action": "update", "kind": "anime", "work_id": 1682, "workType": "OAD"}
            ]
        }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &fix_prop.id, None).unwrap();

    let det_1683 = exec_get_work_detail_with_dir(
        &sync,
        &local,
        Some(dir.path()),
        &json!({"kind": "anime", "work_id": 1683}),
    )
    .unwrap();
    assert_eq!(det_1683["workType"], "剧场版");
    assert_eq!(det_1683["movieFormat"], true);
    assert_eq!(det_1683["coverType"], "local");
    assert_eq!(det_1683["coverAvailable"], true);
    assert_eq!(det_1683["missingCover"], false);
    assert!(det_1683["coverLocalPath"].as_str().unwrap().contains("rehydrate_anime_1683_test.jpg"));

    let det_1682 = exec_get_work_detail_with_dir(
        &sync,
        &local,
        Some(dir.path()),
        &json!({"kind": "anime", "work_id": 1682}),
    )
    .unwrap();
    assert_eq!(det_1682["workType"], "OAD");
    assert_eq!(det_1682["movieFormat"], false);

    // D6(7): 封面下载失败不得写入坏 URL，必须回落为 none 并标明原因
    let items = query_curator_items(&sync, &local).unwrap();
    let item_1682 = items.iter().find(|i| i.id == 1682).unwrap();
    let det_remote = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "76326".into(),
        title: Some("约会大作战 OAD".into()),
        original_title: Some("デート・ア・ライブ OAD".into()),
        cover_url: Some("https://lain.bgm.tv/broken.jpg".into()),
        cover_thumb_url: None,
        release_date: Some("2013-12-06".into()),
        total_episodes: Some(1),
        summary: None,
        studio: None,
        media_format: Some("OAD".into()),
        tags: vec![],
        public_score: None,
    };
    let (failed_op, filled, missing) = build_rehydrate_op_from_detail_with_cover(
        item_1682,
        &det_remote,
        &["cover".into()],
        true,
        Some(Err("HTTP 403 防盗链".into())),
    );
    assert!(failed_op.is_none());
    assert!(filled.is_empty());
    assert!(missing.iter().any(|m| m.contains("探测或下载失败") && m.contains("403")));

    // 检索入库自动富化：薄 create 带 external_id 自动补齐本地封面、原名、开播日、标签、形态、简介
    let mut thin_create = json!({
        "action": "create",
        "kind": "anime",
        "title": "约会大作战 第二季",
        "status": "看完",
        "progress_primary": 10,
        "external_source": "bangumi",
        "external_id": "76325"
    });
    let det_s2 = RemoteSubjectDetail {
        external_source: "bangumi".into(),
        external_id: "76325".into(),
        title: Some("约会大作战 第二季".into()),
        original_title: Some("デート・ア・ライブII".into()),
        cover_url: Some("https://lain.bgm.tv/pic/cover/l/76325.jpg".into()),
        cover_thumb_url: None,
        release_date: Some("2014-04-11".into()),
        total_episodes: Some(10),
        summary: Some("官方简介".into()),
        studio: Some("Production IMS".into()),
        media_format: Some("TV".into()),
        tags: vec!["轻小说改".into(), "恋爱".into()],
        public_score: Some(6.8),
    };
    enrich_create_op_from_detail(&mut thin_create, &det_s2, Some("animeow-cover://native/rehydrate_bgm_76325.jpg"));
    assert_eq!(thin_create["cover_url"], "animeow-cover://native/rehydrate_bgm_76325.jpg");
    assert_eq!(thin_create["release_date"], "2014-04-11");
    assert_eq!(thin_create["total_episodes"], 10);
    assert_eq!(thin_create["tags"], json!(["恋爱"]));
    assert_eq!(thin_create["studio_or_circle"], "Production IMS");
    assert_eq!(thin_create["field_source"], "external");

    // D8: incomplete_metadata 支持 characters 必填字段过滤
    let inc_chars = exec_query_library(
        &sync,
        &local,
        &json!({"incomplete_metadata": true, "incomplete_fields": ["characters"], "kind": "anime"}),
    )
    .unwrap();
    assert!(inc_chars["total"].as_u64().unwrap() >= 3);

    // D9: 真正日期/集数 mismatch（双侧非空且不符）必须降级 confidence，绝不保持 1.0
    let target_mismatch = LocalWorkVerifyTarget {
        kind: "anime".into(),
        work_id: 1682,
        title: "约会大作战 OAD".into(),
        original_title: "デート・ア・ライブ OAD".into(),
        release_date: "2013-12-06".into(),
        total_episodes: 1,
        media_format: Some("OAD".into()),
        tags: vec![],
        existing_bangumi_id: Some("76326".into()),
    };
    let hit_mismatch = RemoteCatalogHit {
        kind: "anime".into(),
        external_source: "bangumi".into(),
        external_id: "76326".into(),
        title: "约会大作战 OAD".into(),
        original_title: "デート・ア・ライブ OAD".into(),
        cover_url: "".into(),
        release_date: "2020-01-01".into(),
        total_episodes: 12,
        studio_or_circle: "".into(),
        public_score: None,
        synopsis: "".into(),
    };
    let scored_mismatch = score_bangumi_candidate(&target_mismatch, &hit_mismatch);
    assert_eq!(scored_mismatch.evidence["date_status"], "mismatch");
    assert_eq!(scored_mismatch.evidence["episodes_status"], "mismatch");
    assert!(scored_mismatch.confidence < 0.85, "mismatch must downgrade confidence, got {}", scored_mismatch.confidence);

    // D10: 非法字段错误信息必须回显被拒字段名与支持字段清单
    let bad_field = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "未知字段测试",
            "operations": [{"action": "update", "kind": "anime", "work_id": 1682, "unknown_magic_field": "abc"}]
        }),
    );
    let err_msg = bad_field.unwrap_err().to_string();
    assert!(err_msg.contains("unknown_magic_field"), "err={err_msg}");
    assert!(err_msg.contains("支持字段"), "err={err_msg}");
}
