use animeow_core::agent::storage::{read_proposal_statuses, summarize_session_runtime_stats};
use animeow_core::agent::tools_read::{
    evaluate_bangumi_matches, exec_find_external_id_usage, exec_get_library_overview,
    exec_get_work_detail, exec_query_library, exec_run_library_diagnostics, verify_bangumi_batch,
    LocalWorkVerifyTarget,
};
use animeow_core::agent::tools_write::{
    commit_proposal, create_bulk_bind_proposal, create_merge_duplicates_proposal,
    create_series_mutations_proposal, create_work_mutations_proposal, revert_proposal,
};
use animeow_core::agent::{
    create_session, get_policy_config, load_session_messages, load_session_proposals,
    run_agent_turn, save_policy_config, save_profile, AgentRuntimeHandles, SaveAgentProfileInput,
    SendAgentMessageInput,
};
use animeow_core::cloud::save_cloud_endpoint_config;
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::RemoteCatalogHit;
use axum::{routing::post, Json, Router};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use tempfile::tempdir;
use tokio_util::sync::CancellationToken;

fn mk_hit(kind: &str, id: &str, title: &str, orig: &str, date: &str, eps: i64) -> RemoteCatalogHit {
    RemoteCatalogHit {
        kind: kind.into(),
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
fn a_bangumi_scorer_handles_all_benchmark_cases_and_guardrails() {
    // 1. 1469 【我推的孩子】 -> exact_match / 303186 / >= 0.95
    let t1469 = LocalWorkVerifyTarget {
        work_id: 1469,
        kind: "book".into(),
        title: "【我推的孩子】".into(),
        original_title: "【推しの子】".into(),
        release_date: "2020-07-17".into(),
        total_episodes: 166,
        media_format: Some("漫画".into()),
        tags: vec!["漫画".into()],
        existing_bangumi_id: None,
    };
    let r1469 = evaluate_bangumi_matches(
        &t1469,
        vec![
            mk_hit("book", "303186", "【我推的孩子】", "【推しの子】", "2020-07-17", 166),
            mk_hit("book", "999999", "我推的孩子 番外篇", "【推しの子】 番外", "2022-01-01", 10),
        ],
        3,
    );
    assert_eq!(r1469["matchStatus"], "exact_match");
    assert_eq!(r1469["chosen"]["externalId"], "303186");
    assert!(r1469["confidence"].as_f64().unwrap() >= 0.95);
    assert_eq!(r1469["candidates"].as_array().unwrap().len(), 1); // exact_match 精简返回

    // 2. 1604 我们的重制人生 -> 选 219802，排除 Ver.β (325454)
    let t1604 = LocalWorkVerifyTarget {
        work_id: 1604,
        kind: "book".into(),
        title: "我们的重制人生".into(),
        original_title: "ぼくたちのリメイク".into(),
        release_date: "2017-03-25".into(),
        total_episodes: 13,
        media_format: Some("轻小说".into()),
        tags: vec!["轻小说".into()],
        existing_bangumi_id: None,
    };
    let r1604 = evaluate_bangumi_matches(
        &t1604,
        vec![
            mk_hit("book", "325454", "我们的重制人生 Ver.β", "ぼくたちのリメイク Ver.β", "2019-08-24", 3),
            mk_hit("book", "219802", "我们的重制人生", "ぼくたちのリメイク", "2017-03-25", 0),
        ],
        3,
    );
    assert_eq!(r1604["matchStatus"], "exact_match");
    assert_eq!(r1604["chosen"]["externalId"], "219802");

    // 3. 1384 中二病也要谈恋爱（无原名）-> 选 29648，排除 恋/Lite/剧场版
    let t1384 = LocalWorkVerifyTarget {
        work_id: 1384,
        kind: "anime".into(),
        title: "中二病也要谈恋爱".into(),
        original_title: String::new(),
        release_date: "2012-10-03".into(),
        total_episodes: 13,
        media_format: None,
        tags: vec!["恋爱".into()],
        existing_bangumi_id: None,
    };
    let r1384 = evaluate_bangumi_matches(
        &t1384,
        vec![
            mk_hit("anime", "72942", "中二病也要谈恋爱！恋", "中二病でも恋がしたい！戀", "2014-01-08", 12),
            mk_hit("anime", "50331", "中二病也要谈恋爱！Lite", "中二病でも恋がしたい！Lite", "2012-09-27", 6),
            mk_hit("anime", "75521", "小鸟游六花・改 ～剧场版 中二病也要谈恋爱！～", "劇場版 中二病でも恋がしたい！", "2013-09-14", 1),
            mk_hit("anime", "29648", "中二病也要谈恋爱！", "中二病でも恋がしたい！", "2012-10-03", 13),
        ],
        3,
    );
    assert_eq!(r1384["matchStatus"], "exact_match");
    assert_eq!(r1384["chosen"]["externalId"], "29648");

    // 4. 1210 回复术士的重启人生（无原名 + 译名 1 字差）-> 不得 no_match，选中 295017
    let t1210 = LocalWorkVerifyTarget {
        work_id: 1210,
        kind: "anime".into(),
        title: "回复术士的重启人生".into(),
        original_title: String::new(),
        release_date: "2021-01-13".into(),
        total_episodes: 12,
        media_format: None,
        tags: vec!["异世界".into()],
        existing_bangumi_id: None,
    };
    let r1210 = evaluate_bangumi_matches(
        &t1210,
        vec![mk_hit("anime", "295017", "回复术士的重来人生", "回復術士のやり直し", "2021-01-13", 12)],
        3,
    );
    assert_ne!(r1210["matchStatus"], "no_match");
    assert_eq!(r1210["chosen"]["externalId"], "295017");

    // 5. 同系列不同季防误绑：86 第 2 部分绝不能误绑到第一季
    let t_86_p2 = LocalWorkVerifyTarget {
        work_id: 1405,
        kind: "anime".into(),
        title: "86 -不存在的战区- 第2部分".into(),
        original_title: String::new(),
        release_date: "2021-10-02".into(),
        total_episodes: 12,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r_86 = evaluate_bangumi_matches(
        &t_86_p2,
        vec![mk_hit("anime", "302189", "86 -不存在的战区-", "86-エイティシックス-", "2021-04-10", 11)],
        3,
    );
    assert_eq!(r_86["matchStatus"], "no_match");
    assert!(r_86["chosen"].is_null());

    // 6. 同分候选必须降级 probable_match 且不自动定案
    let t_tied = LocalWorkVerifyTarget {
        work_id: 2001,
        kind: "anime".into(),
        title: "同名测试作".into(),
        original_title: "同名の作品".into(),
        release_date: "2024-04-01".into(),
        total_episodes: 12,
        media_format: None,
        tags: vec![],
        existing_bangumi_id: None,
    };
    let r_tied = evaluate_bangumi_matches(
        &t_tied,
        vec![
            mk_hit("anime", "111", "同名测试作", "同名の作品", "2024-04-01", 12),
            mk_hit("anime", "222", "同名测试作", "同名の作品", "2024-04-01", 12),
        ],
        3,
    );
    assert_eq!(r_tied["matchStatus"], "probable_match");
    assert!(r_tied["chosen"].is_null());
    assert_eq!(r_tied["candidates"].as_array().unwrap().len(), 2);
}

#[tokio::test]
async fn a_batch_verify_30_works_returns_all_30_without_dropping() {
    let dir = tempdir().unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    save_cloud_endpoint_config(dir.path(), &base).unwrap();
    let calls = Arc::new(AtomicUsize::new(0));
    let router = Router::new().route(
        "/api/catalog/public/bangumi",
        post({
            let calls = calls.clone();
            move |Json(req): Json<Value>| {
                calls.fetch_add(1, Ordering::SeqCst);
                let kw = req["body"]["keyword"].as_str().unwrap_or("").to_string();
                async move {
                    let num = kw.strip_prefix("批量测试番").unwrap_or("0");
                    Json(json!({
                        "data": {
                            "data": [{
                                "id": 50000 + num.parse::<i64>().unwrap_or(0),
                                "type": 2,
                                "name": format!("BatchAnime{num}"),
                                "name_cn": kw,
                                "date": "2024-01-01",
                                "eps": 12
                            }]
                        }
                    }))
                }
            }
        }),
    );
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });

    let targets: Vec<LocalWorkVerifyTarget> = (1..=30)
        .map(|i| LocalWorkVerifyTarget {
            work_id: i,
            kind: "anime".into(),
            title: format!("批量测试番{i}"),
            original_title: format!("BatchAnime{i}"),
            release_date: "2024-01-01".into(),
            total_episodes: 12,
            media_format: None,
            tags: vec![],
            existing_bangumi_id: None,
        })
        .collect();

    let (batch, exact_bindings) =
        verify_bangumi_batch(dir.path(), targets, vec![], "anime", 3, true).await;
    server.abort();

    assert_eq!(batch["count"], 30);
    assert_eq!(batch["summary"]["exactMatch"], 30);
    assert_eq!(batch["results"].as_array().unwrap().len(), 30);
    assert_eq!(exact_bindings.len(), 30);
    assert_eq!(calls.load(Ordering::SeqCst), 30);
    // 紧凑投影模式下 30 部输出应远低于 14,000 字符溢写阈值
    assert!(
        batch.to_string().chars().count() < 14_000,
        "batch compact output should stay well below spill threshold"
    );
}

#[test]
fn b_bulk_bind_372_with_conflict_isolation_backfill_and_full_undo() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let session = create_session(&local, None, None, None).unwrap();

    // 插入 372 部未绑动画（第 1 部保留用户手填原名，其余原名为空），加 1 部已在库占着 900005 的作品
    let tx = sync.transaction().unwrap();
    tx.execute_batch(
        "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n < 372)
         INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, subjectType)
         SELECT n, printf('动画-%03d', n), CASE WHEN n = 1 THEN '用户手填原名' ELSE '' END, '看完', 12, 12, 'anime' FROM seq;
         INSERT INTO animes (id, title, status, externalSource, externalId, subjectType)
         VALUES (999, '已占外链作品', '在看', 'bangumi', '900005', 'anime');",
    )
    .unwrap();
    tx.commit().unwrap();

    // 构造 372 条绑定：id=5 故意撞车 900005，其余 371 条正常
    let bindings: Vec<Value> = (1..=372)
        .map(|n| {
            let ext_id = if n == 5 {
                "900005".to_string()
            } else {
                (800000 + n).to_string()
            };
            json!({
                "work_id": n,
                "kind": "anime",
                "external_source": "bangumi",
                "external_id": ext_id,
                "original_title": format!("Orig-{n}"),
                "confidence": 0.96,
            })
        })
        .collect();

    let outcome = create_bulk_bind_proposal(
        &sync,
        &local,
        &session.id,
        &json!({ "mode": "bulk_bind", "bindings": bindings }),
    )
    .unwrap();
    assert_eq!(outcome.valid_count, 371);
    assert_eq!(outcome.conflicts.len(), 1);
    assert_eq!(outcome.conflicts[0]["code"], "CONFLICT_EXTERNAL_ID");
    assert_eq!(outcome.conflicts[0]["conflictWorkId"], 999);
    let proposal = outcome.proposal.unwrap();
    assert_eq!(proposal.diffs.len(), 371);

    let applied = commit_proposal(&mut sync, &local, &proposal.id, None).unwrap();
    assert_eq!(applied.applied_count, 371);

    let bound_cnt: i64 = sync
        .query_row(
            "SELECT COUNT(*) FROM animes WHERE id <= 372 AND externalSource = 'bangumi' AND externalId IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(bound_cnt, 371);

    // 验证：id=1 的手填原名不被覆盖，id=2 的空原名被回填
    let orig1: String = sync
        .query_row("SELECT originalTitle FROM animes WHERE id = 1", [], |r| r.get(0))
        .unwrap();
    let orig2: String = sync
        .query_row("SELECT originalTitle FROM animes WHERE id = 2", [], |r| r.get(0))
        .unwrap();
    assert_eq!(orig1, "用户手填原名");
    assert_eq!(orig2, "Orig-2");

    // 整体撤销后全部归零且原名还原
    let reverted = revert_proposal(&mut sync, &local, &proposal.id).unwrap();
    assert_eq!(reverted.applied_count, 371);
    let bound_after_undo: i64 = sync
        .query_row(
            "SELECT COUNT(*) FROM animes WHERE id <= 372 AND externalId IS NOT NULL",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(bound_after_undo, 0);
    let orig2_after: String = sync
        .query_row("SELECT COALESCE(originalTitle, '') FROM animes WHERE id = 2", [], |r| r.get(0))
        .unwrap();
    assert_eq!(orig2_after, "");
}

#[test]
fn c_merge_transfers_and_clears_external_id_merges_tags_and_releases_trash_binding() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let session = create_session(&local, None, None, None).unwrap();

    sync.execute_batch(
        "INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, memo, externalSource, externalId, subjectType)
         VALUES
           (1210, '回复术士的重启人生', '', '看完', 12, 12, '棍勇，复仇', NULL, NULL, 'anime'),
           (1664, '回复术士的重来人生', '回復術士のやり直し', '看完', 12, 12, '', 'bangumi', '295017', 'anime'),
           (1384, '中二病也要谈恋爱', '', '看完', 13, 13, '', NULL, NULL, 'anime'),
           (1642, '中二病也要谈恋爱！', '', '看完', 13, 13, '', 'bangumi', '29648', 'anime'),
           (2000, '在库冲突者', '', '在看', 1, 12, '', 'bangumi', '88888', 'anime'),
           (2001, '待绑作品', '', '想看', 0, 12, '', NULL, NULL, 'anime'),
           (2002, '历史回收站占位', '', '想看', 0, 12, '', 'bangumi', '77777', 'anime');
         UPDATE animes SET deletedAt = '2026-11-20T00:00:00Z' WHERE id = 2002;
         INSERT INTO tags (id, name) VALUES (1, '卖肉'), (2, '后宫'), (3, '异世界');
         INSERT INTO anime_tags (animeId, tagId) VALUES (1210, 1), (1210, 2), (1664, 3);",
    )
    .unwrap();

    // 1. 合并提案预览包含标签并集与外链移交说明
    let merge_prop = create_merge_duplicates_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "merges": [
                { "kind": "anime", "keep_id": 1210, "drop_ids": [1664] },
                { "kind": "anime", "keep_id": 1384, "drop_ids": [1642] }
            ]
        }),
    )
    .unwrap();
    assert!(merge_prop.diffs[0].after.contains("标签取并集(3个)"));
    assert!(merge_prop.diffs[0].after.contains("295017"));
    commit_proposal(&mut sync, &local, &merge_prop.id, None).unwrap();

    // 合并后 1210 继承了 295017、原名与 3 个并集标签；1664 进回收站且 externalId 已清空
    let (ext1210, orig1210): (Option<String>, Option<String>) = sync
        .query_row("SELECT externalId, originalTitle FROM animes WHERE id = 1210", [], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap();
    assert_eq!(ext1210.as_deref(), Some("295017"));
    assert_eq!(orig1210.as_deref(), Some("回復術士のやり直し"));
    let tag_cnt_1210: i64 = sync
        .query_row("SELECT COUNT(*) FROM anime_tags WHERE animeId = 1210", [], |r| r.get(0))
        .unwrap();
    assert_eq!(tag_cnt_1210, 3);
    let ext1664: Option<String> = sync
        .query_row("SELECT externalId FROM animes WHERE id = 1664", [], |r| r.get(0))
        .unwrap();
    assert_eq!(ext1664, None);

    // 2. get_work_detail(include_deleted=true) 可查回收站，find_external_id_usage 可查回收站 77777
    let del_detail = exec_get_work_detail(&sync, &local, &json!({ "work_id": 1664, "include_deleted": true })).unwrap();
    assert_eq!(del_detail["found"], true);
    assert_eq!(del_detail["deleted"], true);
    let usage = exec_find_external_id_usage(&sync, &json!({ "source": "bangumi", "external_id": "77777" })).unwrap();
    assert_eq!(usage["inUse"], true);
    assert_eq!(usage["holders"][0]["workId"], 2002);
    assert_eq!(usage["holders"][0]["deleted"], true);

    // 3. 在库冲突必须报错并带上占用方 work_id
    let conflict_err = create_work_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({ "operations": [{ "action": "update", "kind": "anime", "work_id": 2001, "external_source": "bangumi", "external_id": "88888" }] }),
    )
    .unwrap_err()
    .to_string();
    assert!(conflict_err.contains("2000"));
    assert!(conflict_err.contains("在库冲突者"));

    // 4. 仅回收站占位（2002 占 77777）时，直接绑定 2001 必须一次成功，且撤销时还原 2002 的旧绑定
    let (bind_prop, _) = create_work_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({ "operations": [{ "action": "update", "kind": "anime", "work_id": 2001, "external_source": "bangumi", "external_id": "77777" }] }),
    )
    .unwrap();
    commit_proposal(&mut sync, &local, &bind_prop.id, None).unwrap();
    let ext2001: Option<String> = sync.query_row("SELECT externalId FROM animes WHERE id = 2001", [], |r| r.get(0)).unwrap();
    let ext2002: Option<String> = sync.query_row("SELECT externalId FROM animes WHERE id = 2002", [], |r| r.get(0)).unwrap();
    assert_eq!(ext2001.as_deref(), Some("77777"));
    assert_eq!(ext2002, None);

    revert_proposal(&mut sync, &local, &bind_prop.id).unwrap();
    let ext2001_undo: Option<String> = sync.query_row("SELECT externalId FROM animes WHERE id = 2001", [], |r| r.get(0)).unwrap();
    let ext2002_undo: Option<String> = sync.query_row("SELECT externalId FROM animes WHERE id = 2002", [], |r| r.get(0)).unwrap();
    assert_eq!(ext2001_undo, None);
    assert_eq!(ext2002_undo.as_deref(), Some("77777"));
}

#[test]
fn d_series_assign_sets_per_member_roles_and_returns_created_series_id() {
    let dir = tempdir().unwrap();
    let mut sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let session = create_session(&local, None, None, None).unwrap();

    sync.execute_batch(
        "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes)
         VALUES
           (1128, 'DOG DAYS', '看完', 13, 13),
           (1410, 'DOG DAYS''', '看完', 13, 13),
           (1411, 'DOG DAYS''''', '看完', 12, 12);",
    )
    .unwrap();

    let prop = create_series_mutations_proposal(
        &sync,
        &local,
        &session.id,
        &json!({
            "operations": [{
                "action": "assign_series",
                "series_name": "DOG DAYS",
                "targets": [
                    { "kind": "anime", "work_id": 1128, "role": "第一季" },
                    { "kind": "anime", "work_id": 1410, "role": "续作" },
                    { "kind": "anime", "work_id": 1411, "role": "续作" }
                ]
            }]
        }),
    )
    .unwrap();
    assert!(prop.diffs[0].after.contains("第一季"));
    commit_proposal(&mut sync, &local, &prop.id, None).unwrap();

    let roles: Vec<(i64, Option<String>)> = [1128, 1410, 1411]
        .iter()
        .map(|&id| {
            let role: Option<String> = sync
                .query_row("SELECT seriesRole FROM animes WHERE id = ?1", [id], |r| r.get(0))
                .unwrap();
            (id, role)
        })
        .collect();
    assert_eq!(
        roles,
        vec![
            (1128, Some("第一季".into())),
            (1410, Some("续作".into())),
            (1411, Some("续作".into())),
        ]
    );

    let statuses = read_proposal_statuses(&local, &session.id, Some(&prop.id)).unwrap();
    let affected = statuses[0]["affectedSeries"].as_array().unwrap();
    assert_eq!(affected.len(), 1);
    assert_eq!(affected[0]["seriesName"], "DOG DAYS");
    assert!(affected[0]["seriesId"].as_i64().unwrap() > 0);
}

#[test]
fn e_query_library_limit_30_fields_missing_source_and_diagnostics() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();

    let tx_sql = "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n < 35)
        INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, airDate, subjectType)
        SELECT n, printf('未绑定动画-%02d', n), printf('OrigAnime-%02d', n), '看完', 12, 12, '2024-04-01', 'anime' FROM seq;
        INSERT INTO animes (id, title, originalTitle, status, watchedEpisodes, totalEpisodes, subjectType)
        VALUES (100, '未绑定小说', 'OrigNovel', '在看', 3, 10, 'book');
        INSERT INTO games (id, syncId, title, originalTitle, status, playMinutes, updatedAt)
        VALUES (1, 'g1', '未绑定游戏', 'OrigGame', '在玩', 60, '2026-11-21');
        INSERT INTO games (id, syncId, title, originalTitle, status, playMinutes, vndbId, updatedAt)
        VALUES (2, 'g2', '已绑VNDB游戏', 'OrigGame2', '已玩', 120, 'v1234', '2026-11-21');";
    sync.execute_batch(tx_sql).unwrap();

    // 1. limit=30 真实返回 30 条，并含 originalTitle / externalSource / externalId / progressUnit / workType
    let page = exec_query_library(
        &sync,
        &local,
        &json!({ "kind": "anime", "missing_external_source": "bangumi", "limit": 30, "offset": 0 }),
    )
    .unwrap();
    assert_eq!(page["total"], 35);
    assert_eq!(page["shown"], 30);
    assert_eq!(page["nextOffset"], 30);
    let first = &page["items"][0];
    assert!(first["originalTitle"].as_str().unwrap().starts_with("OrigAnime-"));
    assert!(first.get("externalSource").is_some());
    assert!(first.get("externalId").is_some());
    assert_eq!(first["progressUnit"], "集");
    assert_eq!(first["workType"], "TV");

    // 2. missing_external_source=bangumi 不混入游戏；vndb 仅筛未绑 VNDB 的游戏
    let bgm_missing = exec_query_library(
        &sync,
        &local,
        &json!({ "missing_external_source": "bangumi", "limit": 30 }),
    )
    .unwrap();
    assert_eq!(bgm_missing["total"], 36); // 35 anime + 1 book, 不含 2 games
    let vndb_missing = exec_query_library(
        &sync,
        &local,
        &json!({ "missing_external_source": "vndb" }),
    )
    .unwrap();
    assert_eq!(vndb_missing["total"], 1);
    assert_eq!(vndb_missing["items"][0]["id"], 1);

    // 3. 互斥校验
    assert!(exec_query_library(
        &sync,
        &local,
        &json!({ "missing_external_id": true, "missing_external_source": "bangumi" }),
    )
    .is_err());

    // 4. 批量 get_work_detail(work_ids) 与 overview 常量表
    let batch_detail = exec_get_work_detail(
        &sync,
        &local,
        &json!({ "work_ids": [1, 2, 100, 9999] }),
    )
    .unwrap();
    assert_eq!(batch_detail["mode"], "batch");
    assert_eq!(batch_detail["foundCount"], 3);
    assert_eq!(batch_detail["missingIds"], json!([9999]));

    let overview = exec_get_library_overview(&sync, &local).unwrap();
    assert_eq!(overview["recommendedExternalSourceByKind"]["anime"], "bangumi");
    assert_eq!(overview["recommendedExternalSourceByKind"]["game"], "vndb");

    // 5. 诊断工具可运行且不报错
    let diag = exec_run_library_diagnostics(&sync, &json!({ "scope": "duplicates" })).unwrap();
    assert!(diag["duplicateGroups"].is_array());
}

#[tokio::test]
async fn f_same_turn_allows_multiple_proposals_and_runtime_stats_works() {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("animeow.db")).unwrap();
    sync.execute_batch(
        "INSERT INTO animes (id, title, status, watchedEpisodes, totalEpisodes)
         VALUES
           (1, '作品甲', '看完', 12, 12),
           (2, '作品甲！', '看完', 12, 12),
           (3, '系列一', '看完', 12, 12),
           (4, '系列二', '看完', 12, 12);",
    )
    .unwrap();
    let local = init_local_db(&dir.path().join("desktop_local.db")).unwrap();
    let mut policy = get_policy_config(&local).unwrap();
    policy.enabled_groups.read = true;
    policy.enabled_groups.write = true;
    save_policy_config(&local, policy).unwrap();

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let profile: SaveAgentProfileInput = serde_json::from_value(json!({
        "name": "fixture",
        "providerId": "custom",
        "model": "fixture",
        "baseUrl": format!("{base}/v1"),
        "supportsTools": true,
        "isDefault": true
    }))
    .unwrap();
    let profile = save_profile(&local, dir.path(), profile).unwrap();
    let session = create_session(&local, None, Some(&profile.id), None).unwrap();

    let step_idx = Arc::new(AtomicUsize::new(0));
    let router = Router::new().route(
        "/v1/chat/completions",
        post({
            let step_idx = step_idx.clone();
            move |Json(_req): Json<Value>| {
                let n = step_idx.fetch_add(1, Ordering::SeqCst);
                async move {
                    let (delta, reason) = if n == 0 {
                        (
                            json!({
                                "tool_calls": [
                                    {
                                        "index": 0, "id": "c_merge", "type": "function",
                                        "function": {
                                            "name": "propose_merge_duplicates",
                                            "arguments": json!({"merges":[{"kind":"anime","keep_id":1,"drop_ids":[2]}]}).to_string()
                                        }
                                    },
                                    {
                                        "index": 1, "id": "c_series", "type": "function",
                                        "function": {
                                            "name": "propose_series_mutations",
                                            "arguments": json!({"operations":[{"action":"assign_series","series_name":"测试系列","targets":[{"kind":"anime","work_id":3,"role":"第一季"},{"kind":"anime","work_id":4,"role":"续作"}]}]}).to_string()
                                        }
                                    },
                                    {
                                        "index": 2, "id": "c_work", "type": "function",
                                        "function": {
                                            "name": "propose_work_mutations",
                                            "arguments": json!({"operations":[{"action":"update","kind":"anime","work_id":3,"personal_score":9.0}]}).to_string()
                                        }
                                    },
                                    {
                                        "index": 3, "id": "c_stats", "type": "function",
                                        "function": {
                                            "name": "get_runtime_stats",
                                            "arguments": "{}"
                                        }
                                    }
                                ]
                            }),
                            "tool_calls",
                        )
                    } else {
                        (json!({ "content": "已一次性生成三份提案。" }), "stop")
                    };
                    let frame = json!({"choices":[{"index":0,"delta":delta,"finish_reason":reason}]});
                    let usage = json!({"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,"prompt_tokens_details":{"cached_tokens":800}}});
                    (
                        [("content-type", "text/event-stream")],
                        format!("data: {frame}\n\ndata: {usage}\n\ndata: [DONE]\n\n"),
                    )
                }
            }
        }),
    );
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let handles = AgentRuntimeHandles {
        studio_data_dir: dir.path().to_owned(),
        sync_db: Arc::new(Mutex::new(sync)),
        local_db: Arc::new(Mutex::new(local)),
    };

    run_agent_turn(
        handles.clone(),
        "multi-prop".into(),
        SendAgentMessageInput {
            session_id: session.id.clone(),
            profile_id: Some(profile.id.clone()),
            user_message: "一次性整理重复、建系列并打分".into(),
            slash_command: None,
            context_chip: None,
            current_settings: None,
            regenerate_message_id: None,
            session_autopilot: false,
        },
        CancellationToken::new(),
        |_| {},
    )
    .await;
    server.abort();

    let local = handles.local_db.lock().unwrap();
    let proposals = load_session_proposals(&local, &session.id).unwrap();
    assert_eq!(proposals.len(), 3);
    let messages = load_session_messages(&local, &session.id).unwrap();
    let final_msg = messages.iter().rev().find(|m| m.role == "assistant").unwrap();
    assert_eq!(final_msg.proposal_ids.len(), 3);
    let stats = summarize_session_runtime_stats(&local, &session.id).unwrap();
    assert_eq!(stats["turns"], 1);
    assert_eq!(stats["totalToolCalls"], 4);
    assert!(stats["overallCacheHitPct"].as_f64().unwrap() >= 70.0);
}
