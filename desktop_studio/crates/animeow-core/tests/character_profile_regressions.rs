use animeow_core::agent::storage::create_session;
use animeow_core::agent::tools_read::exec_list_taxonomy;
use animeow_core::agent::tools_write::{commit_proposal, create_character_mutations_proposal, revert_proposal};
use animeow_core::schema::{init_local_db, init_sync_db};
use rusqlite::Connection;
use serde_json::{json, Value};
use tempfile::{tempdir, TempDir};

fn fixture() -> (TempDir, Connection, Connection, String) {
    let dir = tempdir().unwrap();
    let sync = init_sync_db(&dir.path().join("sync.db")).unwrap();
    let local = init_local_db(&dir.path().join("local.db")).unwrap();
    let session = create_session(&local, Some("角色回归"), None, Some("curator")).unwrap();
    sync.execute_batch(
        "INSERT INTO characters (id,bgmId,anilistId,name,imageUrl,summary,gender,birthMonth,birthDay,bloodType,rating,review)
         VALUES(87,86246,1001,'フリーレン','portrait','原简介','女',1,2,'A',85,'原长评'),
               (88,86247,1002,'フェルン',NULL,'另一段简介',NULL,NULL,NULL,NULL,NULL,NULL);
         INSERT INTO animes (id,title) VALUES(1372,'葬送的芙莉莲');
         INSERT INTO anime_characters VALUES(1372,87,'主角','原声优',2);
         INSERT INTO character_relations (id,sourceCharacterId,targetCharacterId,relationType,sourceRole,targetRole,note,strength)
         VALUES(6,87,88,'朋友','长辈','晚辈','原关系备注',5);"
    ).unwrap();
    (dir, sync, local, session.id)
}

fn propose(sync: &Connection, local: &Connection, session: &str, operations: Value) -> String {
    create_character_mutations_proposal(sync, local, session, &json!({"operations": operations})).unwrap().id
}

fn profile(sync: &Connection) -> Value {
    let json: String = sync.query_row(
        "SELECT json_array(bgmId, anilistId, name, nameCn, imageUrl, summary, gender,
                           birthMonth, birthDay, bloodType, rating, review)
         FROM characters WHERE id=87",
        [], |r| r.get(0),
    ).unwrap();
    serde_json::from_str(&json).unwrap()
}

#[test]
fn translating_names_preserves_full_profiles_and_undo_leaves_later_profile_edits() {
    let (_dir, mut sync, local, session) = fixture();
    let mut expected = profile(&sync);
    let id = propose(&sync, &local, &session, json!([
        {"action":"upsert_character", "id":87, "name_cn":"芙莉莲"},
        {"action":"upsert_character", "id":88, "name":"フェルン", "name_cn":"菲伦"}
    ]));
    commit_proposal(&mut sync, &local, &id, None).unwrap();
    expected[3] = json!("芙莉莲");
    assert_eq!(profile(&sync), expected);
    let details = exec_list_taxonomy(&sync, &json!({"entity":"character", "keyword":"芙莉莲"})).unwrap();
    assert_eq!(details["items"][0]["hasSummary"], true);
    assert_eq!(details["items"][0]["hasImage"], true);
    assert_eq!(details["items"][0]["works"][0]["voiceActor"], "原声优");
    sync.execute("UPDATE characters SET summary='后来编辑的简介' WHERE id=87", []).unwrap();
    revert_proposal(&mut sync, &local, &id).unwrap();
    expected[3] = Value::Null;
    expected[5] = json!("后来编辑的简介");
    assert_eq!(profile(&sync), expected);
}

#[test]
fn explicit_clear_and_conflicting_undo_respect_transaction_and_field_presence() {
    let (_dir, mut sync, local, session) = fixture();
    let before = profile(&sync);
    let bad = propose(&sync, &local, &session, json!([
        {"action":"upsert_character", "id":87, "summary":""},
        {"action":"upsert_character", "id":88, "bgm_id":86246}
    ]));
    assert!(commit_proposal(&mut sync, &local, &bad, None).is_err());
    assert_eq!(profile(&sync), before);

    let id = propose(&sync, &local, &session, json!([{"action":"upsert_character","id":87,"summary":""}]));
    commit_proposal(&mut sync, &local, &id, None).unwrap();
    assert_eq!(profile(&sync)[5], Value::Null);
    sync.execute("UPDATE characters SET summary='后来编辑' WHERE id=87", []).unwrap();
    assert!(revert_proposal(&mut sync, &local, &id).is_err());
    assert_eq!(profile(&sync)[5], "后来编辑");
}

#[test]
fn importing_existing_character_and_partial_link_relation_edits_undo_safely() {
    let (_dir, mut sync, local, session) = fixture();
    let before = profile(&sync);
    let id = propose(&sync, &local, &session, json!([
        {"action":"upsert_character","bgm_id":86246,"name":"フリーレン","name_cn":"芙莉莲"},
        {"action":"link_work","character_id":87,"anime_id":1372,"role_name":"配角"},
        {"action":"upsert_relation","id":6,"source_character_id":87,"target_character_id":88,"relation_type":"师徒"},
        {"action":"upsert_relation","source_character_id":87,"target_character_id":88,"relation_type":"旅伴"}
    ]));
    commit_proposal(&mut sync, &local, &id, None).unwrap();
    assert_eq!(profile(&sync)[3], "芙莉莲");
    let link = || "SELECT roleName,voiceActor,sortOrder FROM anime_characters WHERE characterId=87";
    let read_link = |db: &Connection| db.query_row(link(), [], |r| Ok((r.get::<_, String>(0)?,r.get::<_, String>(1)?,r.get::<_, i64>(2)?))).unwrap();
    assert_eq!(read_link(&sync), ("配角".into(), "原声优".into(), 2));
    let read_rel = |db: &Connection| db.query_row(
        "SELECT relationType,sourceRole,targetRole,note,strength FROM character_relations WHERE id=6", [],
        |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,String>(3)?,r.get::<_,i64>(4)?))
    ).unwrap();
    assert_eq!(read_rel(&sync), ("师徒".into(), "长辈".into(), "晚辈".into(), "原关系备注".into(), 5));
    revert_proposal(&mut sync, &local, &id).unwrap();
    assert_eq!(profile(&sync), before);
    assert_eq!(read_link(&sync), ("主角".into(), "原声优".into(), 2));
    assert_eq!(read_rel(&sync).0, "朋友");
    assert_eq!(sync.query_row("SELECT COUNT(*) FROM character_relations", [], |r| r.get::<_, i64>(0)).unwrap(), 1);
}

#[test]
fn catalog_pull_preserves_missing_voice_actor_and_manual_editor_can_clear_it() {
    let (_dir, sync, _local, _session) = fixture();
    use animeow_core::studio_features::{link_character_work, link_character_work_from_catalog};
    let voice = || sync.query_row(
        "SELECT voiceActor FROM anime_characters WHERE animeId=1372 AND characterId=87",
        [], |row| row.get::<_, Option<String>>(0),
    ).unwrap();
    link_character_work_from_catalog(&sync, 87, 1372, Some("主要角色"), None).unwrap();
    assert_eq!(voice().as_deref(), Some("原声优"));
    link_character_work(&sync, 87, 1372, Some("主角"), None).unwrap();
    assert_eq!(voice(), None);
}
