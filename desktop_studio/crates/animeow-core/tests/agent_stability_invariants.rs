use animeow_core::agent::knowledge::search_app_knowledge;
use animeow_core::agent::registry::build_stable_tool_schemas;
use animeow_core::agent::tools_read::{exec_get_library_overview, exec_query_library};
use animeow_core::agent::storage::{
    create_session, create_verify_job_from_items, get_verify_job_detail, load_proposal,
    load_work_field_sources, read_proposal_statuses_paged, record_work_field_source,
    refresh_verify_job_counters, resolve_verify_job_item_decision, save_tool_artifact,
    update_verify_job_item_result,
};
use animeow_core::agent::tools_write::{
    commit_proposal, commit_proposal_as_auto, create_bulk_bind_proposal,
    create_tag_mutations_proposal, create_work_mutations_proposal, revert_proposal,
};
use animeow_core::agent::types::{
    collect_verify_job_bindable_entries, is_proposal_applied_status, is_verify_item_ready_to_bind,
    normalize_verify_decision, EnabledToolGroups,
};
use animeow_core::repository::query_curator_items;
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::list_tags_manage;
use serde_json::json;
use tempfile::tempdir;

fn seed_anime(sync: &rusqlite::Connection, id: i64, title: &str, air_date: &str, cover: &str) {
    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            subjectType, createdAt, airDate, updatedAt
        ) VALUES (?1, ?2, '', ?3, '在看', 1, 12, 'anime', '2026-11-20', ?4, 1763596800000)",
        rusqlite::params![id, title, cover, air_date],
    )
    .unwrap();
}

#[test]
fn commit_rollback_leaves_local_work_field_sources_untouched() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("跨库回滚隔离测试"), None, Some("curator")).unwrap();

    seed_anime(&sync, 101, "作品甲", "", "");
    seed_anime(&sync, 102, "作品乙", "", "");

    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "批量补全两部作品日期",
            "reason": "测试主库回滚时 local_conn 零脏写",
            "operations": [
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 101,
                    "release_date": "2024-04-01",
                    "field_source": "external"
                },
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 102,
                    "release_date": "2024-07-01",
                    "field_source": "external"
                }
            ]
        }),
    )
    .unwrap();

    // 在确认提交前，模拟外部修改作品 102 的 airDate，使批内第 2 项触发字段级乐观锁冲突
    sync.execute(
        "UPDATE animes SET airDate = '2025-01-01', updatedAt = 1763600000000 WHERE id = 102",
        [],
    )
    .unwrap();

    let err = commit_proposal(&mut sync, &local, &prop.id, None).unwrap_err();
    assert!(
        err.to_string().contains("已被其他操作修改") || err.to_string().contains("已变化"),
        "应因第 2 项冲突报错回滚，实际报错：{err}"
    );

    // 主库作品 101 必须完整回滚
    let date_101: String = sync
        .query_row("SELECT airDate FROM animes WHERE id = 101", [], |r| r.get(0))
        .unwrap();
    assert_eq!(date_101, "");

    // 关键不变式：desktop_local.db 中作品 101 不得残留脏的 work_field_sources 记录
    let fsrc_101 = load_work_field_sources(&local, "anime", 101).unwrap();
    assert!(
        fsrc_101.is_empty(),
        "主库事务回滚后，local_db 的 work_field_sources 不得残留脏记录，实际：{fsrc_101:?}"
    );
}

#[test]
fn revert_rollback_preserves_local_work_field_sources() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("撤销回滚隔离测试"), None, Some("curator")).unwrap();

    seed_anime(&sync, 201, "撤销甲", "", "");
    seed_anime(&sync, 202, "撤销乙", "", "");
    record_work_field_source(&local, "anime", 202, "release_date", "user").unwrap();

    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "批量更新两部日期",
            "reason": "验证撤销中途失败时不提前破坏 local_db 来源记录",
            "operations": [
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 201,
                    "release_date": "2023-10-01",
                    "field_source": "external"
                },
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 202,
                    "release_date": "2023-10-02",
                    "field_source": "external"
                }
            ]
        }),
    )
    .unwrap();

    commit_proposal(&mut sync, &local, &prop.id, None).unwrap();
    assert_eq!(
        load_work_field_sources(&local, "anime", 202)
            .unwrap()
            .get("release_date")
            .map(|(s, _)| s.as_str()),
        Some("external")
    );

    // 篡改作品 201（逆序撤销时第 202 先执行、201 后执行并触发整批撤销回滚）
    sync.execute(
        "UPDATE animes SET airDate = '2026-01-01', updatedAt = 1763700000000 WHERE id = 201",
        [],
    )
    .unwrap();

    assert!(revert_proposal(&mut sync, &local, &prop.id).is_err());

    // 作品 202 的主库值与本地来源均应保持撤销失败前的已提交状态（external）
    let date_202: String = sync
        .query_row("SELECT airDate FROM animes WHERE id = 202", [], |r| r.get(0))
        .unwrap();
    assert_eq!(date_202, "2023-10-02");
    assert_eq!(
        load_work_field_sources(&local, "anime", 202)
            .unwrap()
            .get("release_date")
            .map(|(s, _)| s.as_str()),
        Some("external"),
        "撤销事务回滚后，local_db 中作品 202 的来源标记不得被提前回退"
    );
}

#[test]
fn auto_applied_status_invariants_across_details_and_revert() {
    assert!(is_proposal_applied_status("applied"));
    assert!(is_proposal_applied_status("auto_applied"));
    assert!(!is_proposal_applied_status("pending"));
    assert!(!is_proposal_applied_status("reverted"));
    assert!(!is_proposal_applied_status("noop"));
    assert!(!is_proposal_applied_status("expired"));

    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("自动审批一致性测试"), None, Some("curator")).unwrap();

    seed_anime(&sync, 301, "自动审批番", "", "");

    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "自动补全开播日期",
            "reason": "验证 auto_applied 全链路一致",
            "operations": [
                {
                    "action": "update",
                    "kind": "anime",
                    "work_id": 301,
                    "release_date": "2024-01-05",
                    "field_source": "external"
                }
            ]
        }),
    )
    .unwrap();

    let applied = commit_proposal_as_auto(&mut sync, &local, &prop.id).unwrap();
    assert_eq!(applied.proposal.status, "auto_applied");
    assert!(applied.proposal.can_revert);

    let (loaded, _) = load_proposal(&local, &prop.id).unwrap().unwrap();
    assert_eq!(loaded.status, "auto_applied");
    assert!(loaded.can_revert);

    // 清空 applied_indices_json 模拟无索引记录的兼容行，验证明细仍正确判为 applied
    local
        .execute(
            "UPDATE agent_proposals SET applied_indices_json = NULL WHERE id = ?1",
            [&prop.id],
        )
        .unwrap();
    let statuses = read_proposal_statuses_paged(&local, &sess.id, Some(&prop.id), 0, 20).unwrap();
    assert_eq!(statuses.len(), 1);
    assert_eq!(statuses[0]["status"], "auto_applied");
    assert_eq!(statuses[0]["confirmedBy"], "auto");
    assert_eq!(statuses[0]["canRevert"], true);
    assert_eq!(statuses[0]["items"][0]["executionStatus"], "applied");

    // 正常撤销后，主库值与本地来源标记一并恢复
    let reverted = revert_proposal(&mut sync, &local, &prop.id).unwrap();
    assert_eq!(reverted.proposal.status, "reverted");
    assert!(!reverted.proposal.can_revert);
    assert!(load_work_field_sources(&local, "anime", 301).unwrap().is_empty());
}

#[test]
fn schema_create_external_id_description_matches_remote_first_cover_policy() {
    let schemas = build_stable_tool_schemas(&EnabledToolGroups::default(), false, false);
    let work_tool = schemas
        .iter()
        .find(|s| s["function"]["name"] == "propose_work_mutations")
        .expect("应包含 propose_work_mutations");
    let ext_desc = work_tool["function"]["parameters"]["properties"]["operations"]["items"]
        ["properties"]["external_id"]["description"]
        .as_str()
        .unwrap_or("");
    assert!(
        !ext_desc.contains("并下载本地封面"),
        "external_id 描述不得与远程优先封面策略自相矛盾：{ext_desc}"
    );
    assert!(ext_desc.contains("远程"));
}

#[test]
fn verify_job_decisions_and_bindable_collection_stay_consistent() {
    assert_eq!(normalize_verify_decision("select"), Some("select"));
    assert_eq!(normalize_verify_decision("selected"), Some("select"));
    assert_eq!(normalize_verify_decision("reject_all"), Some("reject_all"));
    assert_eq!(normalize_verify_decision("rejected"), Some("reject_all"));
    assert_eq!(normalize_verify_decision("defer"), Some("defer"));
    assert_eq!(normalize_verify_decision("deferred"), Some("defer"));
    assert_eq!(
        normalize_verify_decision("clear_rejection"),
        Some("clear_rejection")
    );
    assert_eq!(normalize_verify_decision("invalid_action"), None);

    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("分批核实状态一致性测试"), None, Some("curator")).unwrap();

    seed_anime(&sync, 401, "精确命中番", "2024-04-01", "");
    seed_anime(&sync, 402, "待选手动确认番", "2024-07-01", "");
    seed_anime(&sync, 403, "排除与稍后处理番", "2024-10-01", "");

    let items = query_curator_items(&sync, &local).unwrap();
    let job = create_verify_job_from_items(&local, &sess.id, "art_test_verify", "anime", &items)
        .unwrap();

    // 模拟 run_verify_job_batch 真实落库值：status="exact"/"probable"，match_type="exact_match"/"probable_match"
    update_verify_job_item_result(
        &local,
        &job.id,
        "anime",
        401,
        "exact",
        Some("exact_match"),
        0.96,
        Some("100401"),
        Some("Exact Original"),
        &json!([{ "externalId": "100401", "title": "精确命中番" }]),
        None,
    )
    .unwrap();
    update_verify_job_item_result(
        &local,
        &job.id,
        "anime",
        402,
        "probable",
        Some("probable_match"),
        0.84,
        None,
        None,
        &json!([{ "externalId": "100402", "title": "待选手动确认番", "originalTitle": "Probable Orig" }]),
        None,
    )
    .unwrap();
    update_verify_job_item_result(
        &local,
        &job.id,
        "anime",
        403,
        "probable",
        Some("probable_match"),
        0.78,
        None,
        None,
        &json!([{ "externalId": "100403", "title": "错误候选" }]),
        None,
    )
    .unwrap();
    refresh_verify_job_counters(&local, &job.id, None).unwrap();

    // 未做任何人工决策前，exact_match（401）必须已被纳入可绑定列表
    let detail_before = get_verify_job_detail(&local, &job.id).unwrap().unwrap();
    let bindings_before = collect_verify_job_bindable_entries(&detail_before);
    assert_eq!(bindings_before.len(), 1);
    assert_eq!(bindings_before[0]["work_id"], 401);
    assert_eq!(bindings_before[0]["external_id"], "100401");

    // 人工手选 402（验证规范值 "select"）+ 排除 403（验证兼容别名 "rejected"）再撤销排除并标记 "defer"
    resolve_verify_job_item_decision(
        &local,
        &job.id,
        "anime",
        402,
        "select",
        Some("100402"),
        Some("Probable Orig"),
    )
    .unwrap();
    resolve_verify_job_item_decision(&local, &job.id, "anime", 403, "rejected", None, None)
        .unwrap();
    resolve_verify_job_item_decision(
        &local,
        &job.id,
        "anime",
        403,
        "clear_rejection",
        None,
        None,
    )
    .unwrap();
    let detail_after =
        resolve_verify_job_item_decision(&local, &job.id, "anime", 403, "defer", None, None)
            .unwrap();

    // 非法决策值依然必须报错
    assert!(
        resolve_verify_job_item_decision(&local, &job.id, "anime", 403, "bogus", None, None)
            .is_err()
    );

    let item_401 = detail_after.items.iter().find(|i| i.work_id == 401).unwrap();
    let item_402 = detail_after.items.iter().find(|i| i.work_id == 402).unwrap();
    let item_403 = detail_after.items.iter().find(|i| i.work_id == 403).unwrap();
    assert!(is_verify_item_ready_to_bind(item_401));
    assert!(is_verify_item_ready_to_bind(item_402));
    assert!(!is_verify_item_ready_to_bind(item_403));

    // 生成绑定提案必须同时包含 exact_match (401) 和手选 (402)，共 2 条
    let bindings_after = collect_verify_job_bindable_entries(&detail_after);
    assert_eq!(bindings_after.len(), 2);
    let outcome = create_bulk_bind_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "批量绑定测试",
            "bindings": bindings_after,
        }),
    )
    .unwrap();
    assert_eq!(outcome.valid_count, 2);
    assert!(outcome.proposal.is_some());
}

#[test]
fn purge_unused_tags_deduplicates_names_supports_partial_select_and_reverts() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("孤儿标签清理回归"), None, Some("curator")).unwrap();

    seed_anime(&sync, 501, "在库作品", "2024-01-01", "");
    sync.execute(
        "INSERT INTO tags (id, name, color, blocked, sortOrder) VALUES (1, '在用标签', 100, 0, 1)",
        [],
    )
    .unwrap();
    sync.execute(
        "INSERT INTO tags (id, name, color, blocked, sortOrder) VALUES (2, '残留甲', 200, 1, 2)",
        [],
    )
    .unwrap();
    sync.execute(
        "INSERT INTO tags (id, name, color, blocked, sortOrder) VALUES (3, '残留乙', 300, 0, 3)",
        [],
    )
    .unwrap();
    sync.execute("INSERT INTO anime_tags (animeId, tagId) VALUES (501, 1)", [])
        .unwrap();

    // 1. 重复传多条不带 tag_id 的 purge_unused_tags，必须去重并逐条展示真实标签名
    let prop_global = create_tag_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "清理孤儿标签",
            "operations": [
                { "action": "purge_unused_tags" },
                { "action": "purge_unused_tags" },
                { "action": "purge_unused_tags" }
            ]
        }),
    )
    .unwrap();
    assert_eq!(prop_global.diffs.len(), 2);
    assert!(prop_global.diffs.iter().any(|d| d.target_label.contains("残留甲")));
    assert!(prop_global.diffs.iter().any(|d| d.target_label.contains("残留乙")));
    assert_eq!(prop_global.diffs[0].index, 0);
    assert_eq!(prop_global.diffs[1].index, 1);

    // 2. 尝试按 tag_id 清理在用标签必须报错拒收
    assert!(create_tag_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "误删在用标签",
            "operations": [{ "action": "purge_unused_tags", "tag_id": 1 }]
        }),
    )
    .is_err());

    // 3. 逐条勾选只执行第 0 项（残留甲），残留乙与在用标签都应保留
    let first_idx = prop_global
        .diffs
        .iter()
        .find(|d| d.target_label.contains("残留甲"))
        .unwrap()
        .index;
    let res = commit_proposal(&mut sync, &local, &prop_global.id, Some(&[first_idx])).unwrap();
    assert_eq!(res.applied_count, 1);
    let remaining: Vec<String> = list_tags_manage(&sync).unwrap().into_iter().map(|t| t.name).collect();
    assert!(remaining.contains(&"在用标签".to_string()));
    assert!(remaining.contains(&"残留乙".to_string()));
    assert!(!remaining.contains(&"残留甲".to_string()));

    // 4. 一键撤销必须完整恢复「残留甲」及其原 id / color / blocked / sortOrder
    let rev = revert_proposal(&mut sync, &local, &prop_global.id).unwrap();
    assert_eq!(rev.applied_count, 1);
    let restored: (i64, String, Option<i64>, i64, Option<i64>) = sync
        .query_row(
            "SELECT id, name, color, blocked, sortOrder FROM tags WHERE name = '残留甲'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .unwrap();
    assert_eq!(restored, (2, "残留甲".to_string(), Some(200), 1, Some(2)));
}

#[test]
fn personal_score_null_and_zero_clear_score_clean_field_source_and_revert() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("评分清空契约回归"), None, Some("curator")).unwrap();

    // 1. 准备动画（8.5 分）与游戏（7.0 分），并标记 field_source = user
    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            rating, subjectType, createdAt, airDate, updatedAt
        ) VALUES (601, '动画甲', '', '', '看完', 12, 12, 85, 'anime', '2026-11-20', '2024-04-01', 1763596800000)",
        [],
    )
    .unwrap();
    sync.execute(
        "INSERT INTO games (
            id, syncId, title, originalTitle, coverUrl, status, playMinutes, rating, updatedAt
        ) VALUES (602, 'game-602', '游戏乙', '', '', '已玩', 120, 70, '2026-11-20T00:00:00Z')",
        [],
    )
    .unwrap();
    record_work_field_source(&local, "anime", 601, "personal_score", "user").unwrap();
    record_work_field_source(&local, "game", 602, "personal_score", "user").unwrap();

    // 2. 非法空字符串仍应拒收并带清晰清空提示
    let bad = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "非法空串",
            "operations": [{ "action": "update", "kind": "anime", "work_id": 601, "personal_score": "" }]
        }),
    )
    .unwrap_err();
    assert!(
        bad.to_string().contains("null") && bad.to_string().contains("未评"),
        "错误提示应明确告知传 0 或 null 可清空，实际：{bad}"
    );

    // 3. 动画传 null、游戏传 0，均应生成「评分:未评」预览
    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "清空两部作品评分",
            "operations": [
                { "action": "update", "kind": "anime", "work_id": 601, "personal_score": null },
                { "action": "update", "kind": "game", "work_id": 602, "personal_score": 0 }
            ]
        }),
    )
    .unwrap();
    assert_eq!(prop.diffs.len(), 2);
    assert!(prop.diffs[0].after.contains("评分:未评"));
    assert!(prop.diffs[1].after.contains("评分:未评"));

    // 4. 提交落库后：两者评分均为空（None），且 fieldSources.personal_score 干净移除
    let res = commit_proposal(&mut sync, &local, &prop.id, None).unwrap();
    assert_eq!(res.applied_count, 2);
    let items_after = query_curator_items(&sync, &local).unwrap();
    let a_after = items_after.iter().find(|i| i.kind == "anime" && i.id == 601).unwrap();
    let g_after = items_after.iter().find(|i| i.kind == "game" && i.id == 602).unwrap();
    assert_eq!(a_after.personal_score, None);
    assert_eq!(g_after.personal_score, None);
    assert!(!load_work_field_sources(&local, "anime", 601).unwrap().contains_key("personal_score"));
    assert!(!load_work_field_sources(&local, "game", 602).unwrap().contains_key("personal_score"));

    // 5. 撤销后：原评分与 fieldSources.personal_score = "user" 完整恢复
    let rev = revert_proposal(&mut sync, &local, &prop.id).unwrap();
    assert_eq!(rev.applied_count, 2);
    let items_rev = query_curator_items(&sync, &local).unwrap();
    let a_rev = items_rev.iter().find(|i| i.kind == "anime" && i.id == 601).unwrap();
    let g_rev = items_rev.iter().find(|i| i.kind == "game" && i.id == 602).unwrap();
    assert_eq!(a_rev.personal_score, Some(8.5));
    assert_eq!(g_rev.personal_score, Some(7.0));
    assert_eq!(
        load_work_field_sources(&local, "anime", 601)
            .unwrap()
            .get("personal_score")
            .map(|(s, _)| s.as_str()),
        Some("user")
    );
    assert_eq!(
        load_work_field_sources(&local, "game", 602)
            .unwrap()
            .get("personal_score")
            .map(|(s, _)| s.as_str()),
        Some("user")
    );
}

#[test]
fn get_work_detail_schema_exposes_opt_in_probe_cover_flag() {
    let schemas = build_stable_tool_schemas(&EnabledToolGroups::default(), false, false);
    let detail = schemas
        .iter()
        .find(|s| s["function"]["name"] == "get_work_detail")
        .expect("get_work_detail schema 应存在");
    let prop = &detail["function"]["parameters"]["properties"]["probe_cover"];
    assert_eq!(prop["type"], "boolean");
    let desc = prop["description"].as_str().unwrap_or("");
    assert!(desc.contains("默认 false"), "probe_cover 应声明默认不阻塞：{desc}");
}

#[test]
fn app_knowledge_matches_score_guide_and_compacts_unmatched_payload() {
    let hit = search_app_knowledge("评分", false, None);
    assert!(
        hit["matchedGuides"]
            .as_array()
            .is_some_and(|arr| arr.iter().any(|g| g["topic"] == "personal_score")),
        "查「评分」应命中 personal_score 指南：{hit}"
    );

    let miss = search_app_knowledge("zz不存在词条qq", true, Some(&json!({"theme": "cassette"})));
    assert_eq!(miss["unmatched"], true);
    assert!(miss["matchedSettings"].as_array().is_some_and(|a| a.is_empty()));
    assert!(miss.get("currentSettingsSnapshot").is_none());
    let serialized = miss.to_string();
    assert!(
        !serialized.contains("allowedValues"),
        "未命中回退不应回吐冗长 allowedValues：{serialized}"
    );
}

#[test]
fn proposal_status_omits_applied_indices_when_large_proposal_fully_applied() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("提案回执精简回归"), None, Some("curator")).unwrap();

    let mut ops = Vec::new();
    for id in 701..=715 {
        seed_anime(&sync, id, &format!("番剧{id}"), "2024-01-01", "");
        ops.push(json!({
            "action": "update",
            "kind": "anime",
            "work_id": id,
            "personal_score": 8.0
        }));
    }
    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({ "title": "批量评分 15 项", "operations": ops }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &prop.id, None).unwrap();

    let statuses = read_proposal_statuses_paged(&local, &sess.id, Some(&prop.id), 0, 5).unwrap();
    assert_eq!(statuses.len(), 1);
    assert_eq!(statuses[0]["allItemsApplied"], true);
    assert!(
        statuses[0]["appliedIndices"].is_null(),
        "超过 10 项全量执行时应省略完整 appliedIndices 数组以节省上下文"
    );
}

#[test]
fn query_library_and_overview_expose_and_filter_personal_score_source() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();

    // 801: 个人分 7.8 与公开分 7.8 一致，无 user 标记 -> suspected_external
    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            rating, sourceRatingJson, subjectType, createdAt, airDate, updatedAt
        ) VALUES (801, '历史导入分动画', '', '', '看完', 12, 12, 78, '{\"score\":7.8}', 'anime', '2026-11-20', '2024-04-01', 1763596800000)",
        [],
    )
    .unwrap();
    // 802: 个人分 7.0 与公开分 5.2 差异明显 -> user
    sync.execute(
        "INSERT INTO games (
            id, syncId, title, originalTitle, coverUrl, status, playMinutes, score, rating, updatedAt
        ) VALUES (802, 'game-802', '本人打分游戏', '', '', '弃坑', 600, 5.2, 70, '2026-11-20T00:00:00Z')",
        [],
    )
    .unwrap();
    // 803: 个人分 8.0 恰好等于公开分 8.0，但已显式记为 user -> user
    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            rating, sourceRatingJson, subjectType, createdAt, airDate, updatedAt
        ) VALUES (803, '本人同分动画', '', '', '看完', 12, 12, 80, '{\"score\":8.0}', 'anime', '2026-11-20', '2024-07-01', 1763596800000)",
        [],
    )
    .unwrap();
    record_work_field_source(&local, "anime", 803, "personal_score", "user").unwrap();

    let overview = exec_get_library_overview(&sync, &local).unwrap();
    assert_eq!(overview["scoredCount"], 3);
    assert_eq!(overview["suspectedExternalScoreCount"], 1);

    let ext = exec_query_library(&sync, &local, &json!({ "score_source": "suspected_external" })).unwrap();
    assert_eq!(ext["total"], 1);
    assert_eq!(ext["items"][0]["id"], 801);
    assert_eq!(ext["items"][0]["publicScore"], 7.8);
    assert_eq!(ext["items"][0]["personalScoreSource"], "suspected_external");

    let usr = exec_query_library(&sync, &local, &json!({ "score_source": "user" })).unwrap();
    assert_eq!(usr["total"], 2);
    let usr_ids: Vec<i64> = usr["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|it| it["id"].as_i64().unwrap())
        .collect();
    assert!(usr_ids.contains(&802));
    assert!(usr_ids.contains(&803));
}

#[test]
fn batch_result_id_patch_personal_score_null_clears_and_reverts_cleanly() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let sess = create_session(&local, Some("清单批量清空评分回归"), None, Some("curator")).unwrap();

    sync.execute(
        "INSERT INTO animes (
            id, title, originalTitle, coverUrl, status, watchedEpisodes, totalEpisodes,
            rating, sourceRatingJson, subjectType, createdAt, airDate, updatedAt
        ) VALUES (901, '动画A', '', '', '看完', 12, 12, 76, '{\"score\":7.6}', 'anime', '2026-11-20', '2024-04-01', 1763596800000)",
        [],
    )
    .unwrap();
    sync.execute(
        "INSERT INTO games (
            id, syncId, title, originalTitle, coverUrl, status, playMinutes, score, rating, updatedAt
        ) VALUES (902, 'game-902', '游戏B', '', '', '已玩', 300, 8.2, 82, '2026-11-20T00:00:00Z')",
        [],
    )
    .unwrap();
    record_work_field_source(&local, "anime", 901, "personal_score", "user").unwrap();

    let all = query_curator_items(&sync, &local).unwrap();
    let (res_id, _) = save_tool_artifact(
        &local,
        &sess.id,
        "library_result",
        &serde_json::to_string(&all).unwrap(),
        0,
    )
    .unwrap();

    let (prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &sess.id,
        &json!({
            "title": "批量清空历史评分",
            "result_id": res_id,
            "patch": { "personal_score": null }
        }),
    )
    .unwrap();
    assert_eq!(prop.diffs.len(), 2);
    assert!(prop.diffs.iter().all(|d| d.after.contains("评分:未评")));

    commit_proposal(&mut sync, &local, &prop.id, None).unwrap();
    let after = query_curator_items(&sync, &local).unwrap();
    assert!(after.iter().all(|i| i.personal_score.is_none()));
    assert!(!load_work_field_sources(&local, "anime", 901).unwrap().contains_key("personal_score"));

    revert_proposal(&mut sync, &local, &prop.id).unwrap();
    let restored = query_curator_items(&sync, &local).unwrap();
    assert_eq!(
        restored.iter().find(|i| i.id == 901).unwrap().personal_score,
        Some(7.6)
    );
    assert_eq!(
        restored.iter().find(|i| i.id == 902).unwrap().personal_score,
        Some(8.2)
    );
    assert_eq!(
        load_work_field_sources(&local, "anime", 901)
            .unwrap()
            .get("personal_score")
            .map(|(s, _)| s.as_str()),
        Some("user")
    );
}
