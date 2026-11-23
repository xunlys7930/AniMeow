use animeow_core::agent::{
    cleanup_orphan_native_covers, clear_session_messages as agent_clear_session_core,
    collect_verify_job_bindable_entries, commit_mcp_call_proposal,
    commit_proposal as agent_commit_proposal_core,
    commit_proposals_batch as agent_commit_proposals_batch_core, compact_session_context,
    compute_cover_baseline_stats, create_bulk_bind_proposal,
    create_session as agent_create_session_core, delete_mcp_server as agent_delete_mcp_core,
    delete_memory as agent_delete_memory_core, delete_profile as agent_delete_profile_core,
    delete_session as agent_delete_session_core,
    delete_slash_command as agent_delete_slash_command_core, draft_memories_from_library,
    export_session_feedback_diagnostics, export_session_markdown, export_session_trajectory,
    fetch_provider_models, fork_session_from_message as agent_fork_session_core,
    get_policy_config as agent_get_policy_core, get_verify_job_detail,
    invalidate_pending_proposals_on_overwrite, list_mcp_servers as agent_list_mcp_core,
    list_memories as agent_list_memories_core, list_profiles as agent_list_profiles_core,
    list_provider_presets, list_session_verify_jobs, list_sessions as agent_list_sessions_core,
    list_slash_commands as agent_list_commands_core, load_mcp_auth_token,
    load_proposal as agent_load_proposal_core, load_session_messages as agent_load_messages_core,
    load_session_proposals as agent_load_proposals_core, load_session_trajectory,
    parse_mcp_servers_json_snippet, probe_agent_profile, read_tool_artifact,
    recover_proposal_writes, rehydrate_single_work_direct, reject_proposal as agent_reject_proposal_core,
    resolve_verify_job_item_decision, revert_proposal as agent_revert_proposal_core,
    revert_proposals_batch as agent_revert_proposals_batch_core, run_agent_turn,
    run_verify_job_batch, save_filter_snapshot, save_mcp_server as agent_save_mcp_core,
    save_policy_config as agent_save_policy_core, save_profile as agent_save_profile_core,
    save_slash_command as agent_save_command_core,
    storage::{load_library_result, load_profile_api_key, update_profile_probe},
    tools_read::library_item_summary,
    switch_message_version as agent_switch_version_core, test_and_list_mcp_tools,
    truncate_session_from_message, update_mcp_server_status, update_session_meta,
    upsert_memory as agent_upsert_memory_core, AgentMemory, AgentMessage, AgentPolicyConfig,
    AgentProfile, AgentProposal, AgentRuntimeHandles, AgentSession, AgentSlashCommand,
    AgentTrajectorySummary, BatchProposalCommitResult, BatchProposalRevertResult,
    CoverBaselineStats, FetchModelsResult, McpServerConfig, McpTestResult,
    OrphanCoverCleanupResult, ProbeResult, ProposalApplyResult, ProviderPreset,
    SaveAgentProfileInput, SaveMcpServerInput, SendAgentMessageInput, VerifyJobDetail,
    VerifyJobSummary,
};
use animeow_core::backup::{
    export_native_backup, export_native_backup_with_options, inspect_native_backup,
    load_desktop_settings, restore_native_backup, save_desktop_settings, BackupPaths,
    BackupSummary, RestoreResult, RestoreSelection, RestoreStrategy,
};
use animeow_core::cloud::{
    bangumi_apply_pull_to_local, bangumi_complete_oauth, bangumi_preview_diff,
    bangumi_push_local_anime, bangumi_start_authorize, bangumi_unbind_account,
    cloud_delete_backup, cloud_download_backup_to_staging,
    cloud_list_backups, cloud_login_device, cloud_login_password, cloud_publish_review_post,
    cloud_refresh_session_profile, cloud_register_account, cloud_upload_backup_file,
    fetch_bangumi_calendar, fetch_discovery_feed, fetch_playback_episodes, fetch_playback_sources,
    fetch_work_characters_via_server_first, load_cloud_endpoint_config, lookup_dlsite_audio_catalog,
    resolve_playback_stream, save_cloud_endpoint_config, search_bangumi_via_server_first,
    search_characters_via_server_first, search_playback_works, search_vndb_and_cloud_games,
    BangumiAuthorizeStart, BangumiCalendarResponse, BangumiDiffRow, CloudBackupRow,
    CloudEndpointConfig, CommunityPublishResult, DiscoveryFeedRequest,
    DiscoveryPage, PlaybackLine, PlaybackSource, PlaybackWork, RemoteCharacterHit,
};
use animeow_core::companion_ext::{
    bind_game_launcher, delete_local_capture, export_curator_csv, list_game_launchers,
    list_local_captures, list_tier_entries, query_work_reference_bundle, save_local_capture,
    save_tier_entry, scan_external_players, soft_delete_curator_item, ExternalPlayerDetection,
    LocalCaptureRow, TierListEntry, WorkReferenceBundle,
};
use animeow_core::dpapi::{
    clear_cloud_session, ensure_device_registration_id, load_cloud_session,
    PublicCloudSessionInfo,
};
use animeow_core::legacy::{
    discover_legacy_electron_data, import_legacy_electron_copy, LegacyDiscovery, LegacyImportReport,
};
use animeow_core::media_server::{LocalMediaServer, LocalPlaybackTicket, RemoteStreamTicket};
use animeow_core::repository::external_binding::{
    normalize_external_id, update_work_external_binding, BindingMode, ExternalBindingRequest, ExternalSource,
};
use animeow_core::repository::{
    batch_update_status, bind_vault_file, list_vault_files, list_vault_roots, mount_and_scan_vault,
    query_curator_items, update_curator_cell, CellPatch, CuratorItem, VaultFileRow, VaultRootRow,
};
use animeow_core::schema::{init_local_db, init_sync_db};
use animeow_core::studio_features::{
    batch_assign_series, batch_delete_characters, create_curator_item, delete_character,
    delete_character_relation, delete_series, delete_tag, import_works_from_csv,
    link_character_work, link_character_work_from_catalog, list_characters_manage, list_play_sessions, list_series_manage,
    list_tags_manage, list_work_characters, load_canvas_custom_state, merge_duplicate_group,
    merge_series, merge_tags, preview_backup_diff, purge_trash_items, purge_unused_tags,
    query_duplicate_groups, query_series_suggestions, query_trash_items, query_universe_graph,
    record_play_session, remove_member_from_series, rename_series, rename_tag, restore_trash_items,
    save_canvas_custom_state, save_canvas_layout, save_png_data_url, set_character_tags,
    toggle_tag_blocked, unlink_character_work, update_series_member_role, upsert_character,
    upsert_character_relation, BackupDiffEntry, CanvasCustomState, CharacterManageItem,
    CsvImportReport, DuplicateGroup, NewCuratorItemInput, PlaySessionRow, RemoteCatalogHit,
    SeriesManageItem, SeriesSuggestionGroup, TagManageItem, TrashItem, UniverseGraph,
    UpsertCharacterInput, UpsertCharacterRelationInput, WorkCharacterBrief,
};
use chrono::Utc;
use rusqlite::Connection;
use serde::Serialize;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio_util::sync::CancellationToken;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WebviewWindowBuilder};
use tauri_plugin_updater::UpdaterExt;

struct ActiveCompanionTracker {
    work_id: i64,
    work_title: String,
    exe_path: String,
    pid: u32,
    started_instant: Instant,
    started_iso: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ActiveCompanionInfo {
    work_id: i64,
    work_title: String,
    exe_path: String,
    pid: u32,
    elapsed_seconds: u64,
    started_at: String,
}

struct PendingUpdaterPayload {
    update: tauri_plugin_updater::Update,
    downloaded_bytes: Option<Vec<u8>>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUpdaterCheckResult {
    available: bool,
    current_version: String,
    version: Option<String>,
    notes: Option<String>,
    pub_date: Option<String>,
    downloaded: bool,
    endpoint: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUpdaterProgress {
    downloaded: u64,
    total: Option<u64>,
    percent: Option<f64>,
    phase: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopUpdaterDownloadResult {
    version: String,
    total_bytes: u64,
}

struct StudioState {
    studio_data_dir: PathBuf,
    appdata_roaming_dir: PathBuf,
    sync_db: Arc<Mutex<Connection>>,
    local_db: Arc<Mutex<Connection>>,
    media_server: LocalMediaServer,
    active_companion: Mutex<Option<ActiveCompanionTracker>>,
    pending_update: Mutex<Option<PendingUpdaterPayload>>,
    agent_runs: Arc<Mutex<HashMap<String, CancellationToken>>>,
    explicit_quit: AtomicBool,
    minimize_to_tray: AtomicBool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StudioBootstrap {
    studio_data_dir: String,
    media_base_url: String,
    legacy_discovery: LegacyDiscovery,
    items: Vec<CuratorItem>,
    vault_roots: Vec<VaultRootRow>,
    active_companion: Option<ActiveCompanionInfo>,
}

fn map_err<E: std::fmt::Display>(err: E) -> String {
    err.to_string()
}

fn snapshot_companion(state: &StudioState) -> Option<ActiveCompanionInfo> {
    let guard = state.active_companion.lock().ok()?;
    guard.as_ref().map(|c| ActiveCompanionInfo {
        work_id: c.work_id,
        work_title: c.work_title.clone(),
        exe_path: c.exe_path.clone(),
        pid: c.pid,
        elapsed_seconds: c.started_instant.elapsed().as_secs(),
        started_at: c.started_iso.clone(),
    })
}

#[tauri::command]
fn studio_bootstrap(state: State<'_, StudioState>) -> Result<StudioBootstrap, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    let items = query_curator_items(&sync_conn, &local_conn).map_err(map_err)?;
    let vault_roots = list_vault_roots(&local_conn).map_err(map_err)?;
    let legacy_discovery = discover_legacy_electron_data(&state.appdata_roaming_dir);
    let active_companion = snapshot_companion(&state);

    Ok(StudioBootstrap {
        studio_data_dir: state.studio_data_dir.to_string_lossy().into_owned(),
        media_base_url: state.media_server.base_url(),
        legacy_discovery,
        items,
        vault_roots,
        active_companion,
    })
}

#[tauri::command]
fn curator_list(state: State<'_, StudioState>) -> Result<Vec<CuratorItem>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    query_curator_items(&sync_conn, &local_conn).map_err(map_err)
}

#[tauri::command]
fn curator_create_item(
    state: State<'_, StudioState>,
    input: NewCuratorItemInput,
) -> Result<i64, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    create_curator_item(&sync_conn, &input).map_err(map_err)
}

#[tauri::command]
fn curator_update_cell(state: State<'_, StudioState>, patch: CellPatch) -> Result<(), String> {
    {
        let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
        update_curator_cell(&sync_conn, &patch).map_err(map_err)?;
    }
    if let Ok(local) = state.local_db.lock() {
        let mark = |field: &str| {
            let _ = animeow_core::agent::storage::record_work_field_source(
                &local,
                &patch.kind,
                patch.id,
                field,
                "user",
            );
        };
        if patch.cover_url.is_some() { mark("cover"); }
        if patch.release_date.is_some() { mark("release_date"); }
        if patch.tags.is_some() { mark("tags"); }
        if patch.synopsis.is_some() { mark("summary"); }
        if patch.studio_or_circle.is_some() { mark("studio"); }
        if patch.media_format.is_some() { mark("mediaFormat"); }
        if patch.progress_total.is_some() { mark("total_episodes"); }
        if patch.original_title.is_some() { mark("original_title"); }
        if let Some(rt) = patch.raw_rating {
            if rt > 0 {
                mark("personal_score");
            } else {
                let _ = animeow_core::agent::storage::delete_work_field_source(
                    &local,
                    &patch.kind,
                    patch.id,
                    "personal_score",
                );
            }
        }
    }
    Ok(())
}

#[tauri::command]
fn curator_batch_status(
    state: State<'_, StudioState>,
    targets: Vec<(String, i64)>,
    status: String,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    batch_update_status(&mut sync_conn, &targets, &status).map_err(map_err)
}

#[tauri::command]
fn curator_batch_series(
    state: State<'_, StudioState>,
    targets: Vec<(String, i64)>,
    series_name: String,
    series_role: Option<String>,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    batch_assign_series(&mut sync_conn, &targets, &series_name, series_role.as_deref())
        .map_err(map_err)
}

#[tauri::command]
async fn catalog_search_bangumi(
    state: State<'_, StudioState>,
    keyword: String,
    kind: String,
) -> Result<Vec<RemoteCatalogHit>, String> {
    let dir = state.studio_data_dir.clone();
    search_bangumi_via_server_first(&dir, &keyword, &kind)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn catalog_fetch_bangumi_subject(
    state: State<'_, StudioState>,
    input: String,
) -> Result<animeow_core::cloud::RemoteSubjectDetail, String> {
    let id = normalize_external_id(ExternalSource::Bangumi, &input).map_err(map_err)?;
    animeow_core::cloud::fetch_bangumi_subject_detail_via_server_first(&state.studio_data_dir, &id)
        .await.map_err(map_err)
}

#[tauri::command]
async fn work_update_external_binding(
    state: State<'_, StudioState>,
    request: ExternalBindingRequest,
    candidate: Option<RemoteCatalogHit>,
) -> Result<Option<String>, String> {
    use animeow_core::cloud::{fetch_bangumi_subject_detail_via_server_first, resolve_remote_cover_by_policy, RemoteSubjectDetail};

    let mut metadata = match (&request.external_id, &request.mode, request.source) {
        (Some(input), mode, source) if !matches!(mode, BindingMode::None) => {
            let id = normalize_external_id(source, input).map_err(map_err)?;
            match source {
                ExternalSource::Bangumi => Some(fetch_bangumi_subject_detail_via_server_first(&state.studio_data_dir, &id).await.map_err(map_err)?),
                _ => candidate.filter(|hit| hit.external_id == id && hit.kind == request.kind).map(|hit| RemoteSubjectDetail {
                    external_source: hit.external_source,
                    external_id: hit.external_id,
                    title: Some(hit.title),
                    original_title: Some(hit.original_title),
                    cover_url: Some(hit.cover_url),
                    cover_thumb_url: None,
                    release_date: Some(hit.release_date),
                    total_episodes: None,
                    summary: Some(hit.synopsis),
                    studio: Some(hit.studio_or_circle),
                    media_format: None,
                    tags: Vec::new(),
                    public_score: hit.public_score,
                }),
            }
        }
        _ => None,
    };
    if let Some(detail) = &mut metadata {
        let policy = {
            let local = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
            agent_get_policy_core(&local).map_err(map_err)?
        };
        if policy.filter_metadata_tags {
            detail.tags = animeow_core::agent::tools_write::filter_metadata_tags_for_rehydrate(
                &detail.tags, detail.title.as_deref().unwrap_or_default(), detail.studio.as_deref(),
            ).0;
        }
        let selected = !matches!(request.mode, BindingMode::OverwriteSelected)
            || request.fields.iter().any(|field| field == "cover_url");
        if let Some(cover) = detail.cover_url.as_deref().filter(|cover| selected && !cover.trim().is_empty()) {
            detail.cover_url = Some(resolve_remote_cover_by_policy(
                &state.studio_data_dir, Some(&state.local_db), cover, &detail.external_id,
                &policy.cover_storage_policy,
            ).await?.url);
        }
    }
    let mut sync = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let mut local = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    update_work_external_binding(&mut sync, &mut local, &request, metadata.as_ref()).map_err(map_err)
}

#[tauri::command]
fn universe_query_graph(
    state: State<'_, StudioState>,
    mode: String,
) -> Result<UniverseGraph, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    query_universe_graph(&sync_conn, &local_conn, &mode).map_err(map_err)
}

#[tauri::command]
fn universe_save_layout(
    state: State<'_, StudioState>,
    mode: String,
    coords: HashMap<String, [f64; 2]>,
) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    save_canvas_layout(&local_conn, &mode, &coords).map_err(map_err)
}

#[tauri::command]
fn legacy_import_copy(state: State<'_, StudioState>) -> Result<LegacyImportReport, String> {
    let discovery = discover_legacy_electron_data(&state.appdata_roaming_dir);
    if !discovery.database_exists {
        return Err("未检测到可用的旧版 Electron 资料库".into());
    }
    let source_dir = PathBuf::from(&discovery.effective_data_dir);

    let mut sync_guard = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let placeholder = Connection::open_in_memory().map_err(map_err)?;
    let old_conn = std::mem::replace(&mut *sync_guard, placeholder);
    drop(old_conn);

    let report = import_legacy_electron_copy(&source_dir, &state.studio_data_dir);
    let reopened = init_sync_db(&state.studio_data_dir.join("animeow.db")).map_err(map_err)?;
    *sync_guard = reopened;
    state
        .media_server
        .set_covers_root(state.studio_data_dir.join("covers"));

    report.map_err(map_err)
}

#[tauri::command]
fn backup_inspect(
    state: State<'_, StudioState>,
    zip_path: String,
) -> Result<BackupSummary, String> {
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    inspect_native_backup(Path::new(&zip_path), &paths).map_err(map_err)
}

#[tauri::command]
fn backup_preview_diff(
    state: State<'_, StudioState>,
    zip_path: String,
) -> Result<Vec<BackupDiffEntry>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    preview_backup_diff(&sync_conn, Path::new(&zip_path)).map_err(map_err)
}

#[tauri::command]
fn backup_restore(
    state: State<'_, StudioState>,
    zip_path: String,
    strategy: RestoreStrategy,
) -> Result<RestoreResult, String> {
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    let result = {
        let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
        restore_native_backup(
            &mut sync_conn,
            Path::new(&zip_path),
            &paths,
            RestoreSelection::default(),
            strategy,
        )
        .map_err(map_err)?
    };
    // 覆盖恢复会替换整份主库，立刻将尚未执行的 AI 提案标记为已过期，防止误点旧提案写错恢复后的条目
    if matches!(strategy, RestoreStrategy::Replace) {
        if let Ok(local) = state.local_db.lock() {
            let _ = invalidate_pending_proposals_on_overwrite(&local);
        }
    }
    Ok(result)
}

#[tauri::command]
fn backup_export(
    state: State<'_, StudioState>,
    output_path: String,
    include_covers: bool,
) -> Result<BackupSummary, String> {
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    export_native_backup(
        &sync_conn,
        Path::new(&output_path),
        &paths,
        include_covers,
        true,
    )
    .map_err(map_err)
}

#[tauri::command]
fn vault_mount_scan(
    state: State<'_, StudioState>,
    dir_path: String,
    media_kind: String,
) -> Result<VaultRootRow, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let mut local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    mount_and_scan_vault(&sync_conn, &mut local_conn, Path::new(&dir_path), &media_kind)
        .map_err(map_err)
}

#[tauri::command]
fn vault_list_files(
    state: State<'_, StudioState>,
    bound_kind: Option<String>,
    bound_work_id: Option<i64>,
) -> Result<Vec<VaultFileRow>, String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    list_vault_files(&local_conn, bound_kind.as_deref(), bound_work_id).map_err(map_err)
}

#[tauri::command]
fn vault_bind_file(
    state: State<'_, StudioState>,
    vault_file_id: i64,
    bound_kind: Option<String>,
    bound_work_id: Option<i64>,
) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    bind_vault_file(
        &local_conn,
        vault_file_id,
        bound_kind.as_deref(),
        bound_work_id,
    )
    .map_err(map_err)
}

#[tauri::command]
fn player_open_local(
    state: State<'_, StudioState>,
    file_path: String,
    subtitle_path: Option<String>,
    script_path: Option<String>,
) -> Result<LocalPlaybackTicket, String> {
    let media = Path::new(&file_path);
    let sub = subtitle_path.as_deref().map(Path::new);
    let scr = script_path.as_deref().map(Path::new);
    state
        .media_server
        .register_file(media, sub, scr)
        .map_err(map_err)
}

#[tauri::command]
fn player_open_external(file_path: String, reveal_in_folder: bool) -> Result<(), String> {
    let target = Path::new(&file_path);
    if !target.exists() {
        return Err(format!("本机文件不存在：{file_path}"));
    }
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        if reveal_in_folder {
            std::process::Command::new("explorer.exe")
                .arg(format!("/select,{}", target.display()))
                .creation_flags(CREATE_NO_WINDOW)
                .spawn()
                .map_err(map_err)?;
        } else {
            std::process::Command::new("cmd.exe")
                .args(["/C", "start", "", &file_path])
                .creation_flags(CREATE_NO_WINDOW)
                .spawn()
                .map_err(map_err)?;
        }
    }
    Ok(())
}

#[tauri::command]
fn companion_launch_game(
    app: AppHandle,
    state: State<'_, StudioState>,
    work_id: i64,
    work_title: String,
    exe_path: String,
) -> Result<ActiveCompanionInfo, String> {
    let target = Path::new(&exe_path);
    if !target.is_file() {
        return Err("指定的游戏启动器文件不存在，请检查本机路径".into());
    }
    {
        let active_guard = state.active_companion.lock().map_err(|_| "伴侣状态锁失败")?;
        if let Some(existing) = active_guard.as_ref() {
            return Err(format!("当前已有游戏「{}」正在后台计时中", existing.work_title));
        }
    }

    let mut cmd = std::process::Command::new(target);
    if let Some(parent) = target.parent() {
        cmd.current_dir(parent);
    }
    let mut child = cmd.spawn().map_err(|e| format!("启动游戏进程失败：{e}"))?;
    let pid = child.id();
    let started_iso = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    let started_instant = Instant::now();

    let info = ActiveCompanionInfo {
        work_id,
        work_title: work_title.clone(),
        exe_path: exe_path.clone(),
        pid,
        elapsed_seconds: 0,
        started_at: started_iso.clone(),
    };

    {
        let mut active_guard = state.active_companion.lock().map_err(|_| "伴侣状态锁失败")?;
        *active_guard = Some(ActiveCompanionTracker {
            work_id,
            work_title,
            exe_path: exe_path.clone(),
            pid,
            started_instant,
            started_iso: started_iso.clone(),
        });
    }

    let app_clone = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let _ = child.wait();
        let duration_secs = started_instant.elapsed().as_secs() as i64;
        let ended_iso = Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
        if let Some(st) = app_clone.try_state::<StudioState>() {
            if let (Ok(sync_conn), Ok(local_conn)) = (st.sync_db.lock(), st.local_db.lock()) {
                let _ = record_play_session(
                    &sync_conn,
                    &local_conn,
                    "game",
                    work_id,
                    Some(&exe_path),
                    &started_iso,
                    &ended_iso,
                    duration_secs.max(1),
                    Some("进程伴侣自动归档"),
                );
            }
            if let Ok(mut guard) = st.active_companion.lock() {
                *guard = None;
            }
        }
    });

    Ok(info)
}

#[tauri::command]
fn companion_status(state: State<'_, StudioState>) -> Result<Option<ActiveCompanionInfo>, String> {
    Ok(snapshot_companion(&state))
}

#[tauri::command]
fn companion_list_sessions(state: State<'_, StudioState>) -> Result<Vec<PlaySessionRow>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    list_play_sessions(&sync_conn, &local_conn).map_err(map_err)
}

#[tauri::command]
fn review_export_markdown(output_path: String, content: String) -> Result<(), String> {
    if let Some(parent) = Path::new(&output_path).parent() {
        std::fs::create_dir_all(parent).map_err(map_err)?;
    }
    std::fs::write(&output_path, content).map_err(map_err)
}

#[tauri::command]
fn curator_soft_delete(
    state: State<'_, StudioState>,
    kind: String,
    id: i64,
) -> Result<(), String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    soft_delete_curator_item(&sync_conn, &kind, id).map_err(map_err)
}

#[tauri::command]
fn curator_export_csv(
    state: State<'_, StudioState>,
    output_path: String,
) -> Result<usize, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    let items = query_curator_items(&sync_conn, &local_conn).map_err(map_err)?;
    export_curator_csv(Path::new(&output_path), &items).map_err(map_err)
}

#[tauri::command]
fn agent_library_result(
    state: State<'_, StudioState>,
    result_id: String,
    offset: usize,
) -> Result<serde_json::Value, String> {
    let local = state.local_db.lock().map_err(|_| "本地数据暂不可用")?;
    let items = load_library_result(&local, &result_id, None).map_err(map_err)?;
    let rows: Vec<_> = items.iter().skip(offset).take(30).map(library_item_summary).collect();
    Ok(serde_json::json!({ "total": items.len(), "items": rows }))
}

#[tauri::command]
fn agent_export_library_result(
    state: State<'_, StudioState>,
    result_id: String,
    output_path: String,
) -> Result<usize, String> {
    let items = {
        let local = state.local_db.lock().map_err(|_| "本地数据暂不可用")?;
        load_library_result(&local, &result_id, None).map_err(map_err)?
    };
    export_curator_csv(Path::new(&output_path), &items).map_err(map_err)
}

#[tauri::command]
async fn catalog_search_vndb(
    state: State<'_, StudioState>,
    keyword: String,
    direct: Option<bool>,
) -> Result<Vec<RemoteCatalogHit>, String> {
    if let Some(direct) = direct {
        let query = if direct {
            normalize_external_id(ExternalSource::Vndb, &keyword).map_err(map_err)?
        } else {
            keyword.trim().to_owned()
        };
        return animeow_core::cloud::search_vndb_catalog(&query, direct).await.map_err(map_err);
    }
    let dir = state.studio_data_dir.clone();
    search_vndb_and_cloud_games(&dir, &keyword)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn catalog_lookup_dlsite(
    state: State<'_, StudioState>,
    input: String,
) -> Result<Vec<RemoteCatalogHit>, String> {
    let dir = state.studio_data_dir.clone();
    lookup_dlsite_audio_catalog(&dir, &input)
        .await
        .map_err(map_err)
}

#[tauri::command]
fn capture_save(
    state: State<'_, StudioState>,
    work_kind: String,
    work_id: i64,
    work_title: String,
    note: String,
    episode_hint: Option<i64>,
    image_base64: Option<String>,
    append_to_memo: bool,
) -> Result<LocalCaptureRow, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    save_local_capture(
        &state.studio_data_dir,
        &sync_conn,
        &local_conn,
        &work_kind,
        work_id,
        &work_title,
        &note,
        episode_hint,
        image_base64.as_deref(),
        append_to_memo,
    )
    .map_err(map_err)
}

#[tauri::command]
fn capture_list(
    state: State<'_, StudioState>,
    work_kind: Option<String>,
    work_id: Option<i64>,
) -> Result<Vec<LocalCaptureRow>, String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    list_local_captures(&local_conn, work_kind.as_deref(), work_id).map_err(map_err)
}

#[tauri::command]
fn capture_delete(state: State<'_, StudioState>, capture_id: i64) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    delete_local_capture(&state.studio_data_dir, &local_conn, capture_id).map_err(map_err)
}

#[tauri::command]
fn work_query_references(
    state: State<'_, StudioState>,
    work_kind: String,
    work_id: i64,
) -> Result<WorkReferenceBundle, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    query_work_reference_bundle(&sync_conn, &local_conn, &work_kind, work_id).map_err(map_err)
}

#[tauri::command]
fn companion_bind_launcher(
    state: State<'_, StudioState>,
    work_id: i64,
    exe_path: String,
) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    bind_game_launcher(&local_conn, work_id, &exe_path).map_err(map_err)
}

#[tauri::command]
fn companion_list_launchers(
    state: State<'_, StudioState>,
) -> Result<HashMap<i64, String>, String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    list_game_launchers(&local_conn).map_err(map_err)
}

#[tauri::command]
fn companion_scan_external_players(
    state: State<'_, StudioState>,
) -> Result<Vec<ExternalPlayerDetection>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    scan_external_players(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn tier_list_get(state: State<'_, StudioState>) -> Result<Vec<TierListEntry>, String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    list_tier_entries(&local_conn).map_err(map_err)
}

#[tauri::command]
fn tier_list_save(
    state: State<'_, StudioState>,
    work_kind: String,
    work_id: i64,
    tier: Option<String>,
    sort_order: i64,
) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本地扩展库锁获取失败")?;
    save_tier_entry(&local_conn, &work_kind, work_id, tier.as_deref(), sort_order).map_err(map_err)
}

#[tauri::command]
fn cloud_get_config(state: State<'_, StudioState>) -> CloudEndpointConfig {
    load_cloud_endpoint_config(&state.studio_data_dir)
}

#[tauri::command]
fn cloud_set_config(
    state: State<'_, StudioState>,
    api_base: String,
) -> Result<CloudEndpointConfig, String> {
    save_cloud_endpoint_config(&state.studio_data_dir, &api_base).map_err(map_err)
}

#[tauri::command]
fn cloud_session_status(state: State<'_, StudioState>) -> Result<PublicCloudSessionInfo, String> {
    match load_cloud_session(&state.studio_data_dir).map_err(map_err)? {
        Some(s) => Ok(PublicCloudSessionInfo::from_stored(&s)),
        None => Ok(PublicCloudSessionInfo::logged_out()),
    }
}

#[tauri::command]
async fn cloud_session_refresh(
    state: State<'_, StudioState>,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    cloud_refresh_session_profile(&dir).await.map_err(map_err)
}

#[tauri::command]
async fn cloud_login(
    state: State<'_, StudioState>,
    username: String,
    password: String,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    cloud_login_password(&dir, &username, &password)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn cloud_register(
    state: State<'_, StudioState>,
    username: String,
    password: String,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    let roaming = state.appdata_roaming_dir.clone();
    let dev_id = ensure_device_registration_id(&dir, Some(&roaming)).map_err(map_err)?;
    cloud_register_account(&dir, &username, &password, &dev_id)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn cloud_device_auth(
    state: State<'_, StudioState>,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    let roaming = state.appdata_roaming_dir.clone();
    let dev_id = ensure_device_registration_id(&dir, Some(&roaming)).map_err(map_err)?;
    cloud_login_device(&dir, &dev_id).await.map_err(map_err)
}

#[tauri::command]
fn cloud_logout(state: State<'_, StudioState>) -> Result<(), String> {
    clear_cloud_session(&state.studio_data_dir).map_err(map_err)
}

#[tauri::command]
async fn cloud_backups_list(
    state: State<'_, StudioState>,
) -> Result<Vec<CloudBackupRow>, String> {
    let dir = state.studio_data_dir.clone();
    cloud_list_backups(&dir).await.map_err(map_err)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CloudBackupUploadOutcome {
    #[serde(flatten)]
    backup: CloudBackupRow,
    cover_count: usize,
    covers_dropped_due_to_size: bool,
    requested_include_covers: bool,
}

#[tauri::command]
async fn cloud_backups_upload(
    state: State<'_, StudioState>,
    include_covers: bool,
) -> Result<CloudBackupUploadOutcome, String> {
    let dir = state.studio_data_dir.clone();
    let roaming = state.appdata_roaming_dir.clone();
    let dev_id = ensure_device_registration_id(&dir, Some(&roaming)).map_err(map_err)?;
    let staging_dir = dir.join("cloud_staging");
    std::fs::create_dir_all(&staging_dir).map_err(map_err)?;
    let out_zip = staging_dir.join(format!(
        "AniMeow_Studio_{}.animeow.zip",
        Utc::now().format("%Y%m%d_%H%M%S")
    ));
    let (actual_cover_count, covers_dropped_due_to_size) = {
        // 后端 USER_BACKUP_MAX_BYTES 默认 30 MiB，这里取 28 MiB 给 JSON/Base64 留足余量
        const MAX_CLOUD_BACKUP_BYTES: u64 = 28 * 1024 * 1024;
        let paths = BackupPaths::from_studio_root(&dir);
        let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
        let mut summary = export_native_backup_with_options(
            &sync_conn,
            &out_zip,
            &paths,
            include_covers,
            false,
            true,
        )
        .map_err(map_err)?;
        let mut dropped = false;
        if include_covers {
            if let Ok(meta) = std::fs::metadata(&out_zip) {
                if meta.len() > MAX_CLOUD_BACKUP_BYTES {
                    let _ = std::fs::remove_file(&out_zip);
                    summary = export_native_backup_with_options(
                        &sync_conn,
                        &out_zip,
                        &paths,
                        false,
                        false,
                        true,
                    )
                    .map_err(map_err)?;
                    dropped = true;
                }
            }
        }
        (summary.cover_count, dropped)
    };
    let result = cloud_upload_backup_file(&dir, &out_zip, &dev_id)
        .await
        .map_err(map_err);
    let _ = std::fs::remove_file(&out_zip);
    let backup = result?;
    Ok(CloudBackupUploadOutcome {
        backup,
        cover_count: actual_cover_count,
        covers_dropped_due_to_size,
        requested_include_covers: include_covers,
    })
}

#[tauri::command]
fn import_custom_cover(
    state: State<'_, StudioState>,
    source_path: String,
) -> Result<String, String> {
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    animeow_core::backup::import_local_custom_cover(&paths.covers_root, Path::new(&source_path))
        .map_err(map_err)
}

#[tauri::command]
async fn cloud_backups_download(
    state: State<'_, StudioState>,
    backup_id: i64,
    expected_hash: Option<String>,
) -> Result<String, String> {
    let dir = state.studio_data_dir.clone();
    cloud_download_backup_to_staging(&dir, backup_id, expected_hash.as_deref())
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn cloud_backups_delete(
    state: State<'_, StudioState>,
    backup_id: i64,
) -> Result<(), String> {
    let dir = state.studio_data_dir.clone();
    cloud_delete_backup(&dir, backup_id).await.map_err(map_err)
}

#[tauri::command]
async fn cloud_publish_review(
    state: State<'_, StudioState>,
    headline: String,
    work_title: String,
    carrier_label: String,
    personal_score: Option<f64>,
    spoiler_warning: bool,
    review_markdown: String,
) -> Result<CommunityPublishResult, String> {
    let dir = state.studio_data_dir.clone();
    cloud_publish_review_post(
        &dir,
        &headline,
        &work_title,
        &carrier_label,
        personal_score,
        spoiler_warning,
        &review_markdown,
    )
    .await
    .map_err(map_err)
}

#[tauri::command]
async fn playback_sources(state: State<'_, StudioState>) -> Result<Vec<PlaybackSource>, String> {
    let dir = state.studio_data_dir.clone();
    fetch_playback_sources(&dir).await.map_err(map_err)
}

#[tauri::command]
async fn playback_search(
    state: State<'_, StudioState>,
    source: String,
    query: String,
) -> Result<Vec<PlaybackWork>, String> {
    let dir = state.studio_data_dir.clone();
    search_playback_works(&dir, &source, &query)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn playback_episodes(
    state: State<'_, StudioState>,
    source: String,
    work_id: String,
) -> Result<Vec<PlaybackLine>, String> {
    let dir = state.studio_data_dir.clone();
    fetch_playback_episodes(&dir, &source, &work_id)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn playback_resolve(
    state: State<'_, StudioState>,
    source: String,
    work_id: String,
    episode_id: String,
) -> Result<RemoteStreamTicket, String> {
    let dir = state.studio_data_dir.clone();
    let stream = resolve_playback_stream(&dir, &source, &work_id, &episode_id)
        .await
        .map_err(map_err)?;
    state
        .media_server
        .register_remote_stream(stream.url, stream.mime_type, stream.headers)
        .map_err(map_err)
}

#[tauri::command]
async fn bangumi_oauth_start(
    state: State<'_, StudioState>,
) -> Result<BangumiAuthorizeStart, String> {
    let dir = state.studio_data_dir.clone();
    bangumi_start_authorize(&dir).await.map_err(map_err)
}

#[tauri::command]
async fn bangumi_oauth_complete(
    state: State<'_, StudioState>,
    callback_or_ticket: String,
    expected_state: Option<String>,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    bangumi_complete_oauth(&dir, &callback_or_ticket, expected_state.as_deref())
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn bangumi_oauth_unbind(
    state: State<'_, StudioState>,
) -> Result<PublicCloudSessionInfo, String> {
    let dir = state.studio_data_dir.clone();
    bangumi_unbind_account(&dir).await.map_err(map_err)
}

#[tauri::command]
async fn bangumi_sync_preview(
    state: State<'_, StudioState>,
) -> Result<Vec<BangumiDiffRow>, String> {
    let dir = state.studio_data_dir.clone();
    let local_animes = {
        let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
        let mut stmt = sync_conn
            .prepare(
                "SELECT id, title, status, watchedEpisodes, totalEpisodes, rating, externalId
                 FROM animes
                 WHERE deletedAt IS NULL AND subjectType = 'anime' AND externalSource = 'bangumi'",
            )
            .map_err(map_err)?;
        let rows = stmt
            .query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, i64>(4)?,
                    r.get::<_, Option<i64>>(5)?,
                    r.get::<_, Option<String>>(6)?,
                ))
            })
            .map_err(map_err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(map_err)?;
        rows
    };
    bangumi_preview_diff(&dir, local_animes)
        .await
        .map_err(map_err)
}

#[tauri::command]
fn bangumi_sync_pull(
    state: State<'_, StudioState>,
    rows: Vec<BangumiDiffRow>,
    prefer_remote_on_conflict: bool,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    bangumi_apply_pull_to_local(&mut sync_conn, &rows, prefer_remote_on_conflict).map_err(map_err)
}

#[tauri::command]
async fn bangumi_sync_push_one(
    state: State<'_, StudioState>,
    subject_id: i64,
    local_status: String,
    local_watched_eps: i64,
    local_rating_100: Option<i64>,
) -> Result<(), String> {
    let dir = state.studio_data_dir.clone();
    bangumi_push_local_anime(
        &dir,
        subject_id,
        &local_status,
        local_watched_eps,
        local_rating_100,
    )
    .await
    .map_err(map_err)
}

#[tauri::command]
async fn calendar_fetch_bangumi(
    state: State<'_, StudioState>,
) -> Result<BangumiCalendarResponse, String> {
    let dir = state.studio_data_dir.clone();
    fetch_bangumi_calendar(&dir).await.map_err(map_err)
}

#[tauri::command]
async fn discovery_fetch(
    state: State<'_, StudioState>,
    req: DiscoveryFeedRequest,
) -> Result<DiscoveryPage, String> {
    let dir = state.studio_data_dir.clone();
    fetch_discovery_feed(&dir, req).await.map_err(map_err)
}

#[tauri::command]
fn trash_list(state: State<'_, StudioState>) -> Result<Vec<TrashItem>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    query_trash_items(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn trash_restore(state: State<'_, StudioState>, items: Vec<(String, i64)>) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    restore_trash_items(&mut sync_conn, &items).map_err(map_err)
}

#[tauri::command]
fn trash_purge(state: State<'_, StudioState>, items: Vec<(String, i64)>) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    purge_trash_items(&mut sync_conn, &items).map_err(map_err)
}

#[tauri::command]
fn duplicates_list(state: State<'_, StudioState>) -> Result<Vec<DuplicateGroup>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    query_duplicate_groups(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn duplicates_merge(
    state: State<'_, StudioState>,
    kind: String,
    keep_id: i64,
    drop_ids: Vec<i64>,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    merge_duplicate_group(&mut sync_conn, &kind, keep_id, &drop_ids).map_err(map_err)
}

#[tauri::command]
fn series_auto_suggestions(
    state: State<'_, StudioState>,
) -> Result<Vec<SeriesSuggestionGroup>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    query_series_suggestions(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn curator_import_csv(
    state: State<'_, StudioState>,
    csv_path: String,
) -> Result<CsvImportReport, String> {
    let content = std::fs::read_to_string(&csv_path)
        .map_err(|e| format!("读取 CSV 文件失败：{e}"))?;
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    import_works_from_csv(&mut sync_conn, &content).map_err(map_err)
}

#[tauri::command]
fn universe_load_custom(
    state: State<'_, StudioState>,
    mode: String,
) -> Result<CanvasCustomState, String> {
    let local_conn = state.local_db.lock().map_err(|_| "本机库锁获取失败")?;
    load_canvas_custom_state(&local_conn, &mode).map_err(map_err)
}

#[tauri::command]
fn universe_save_custom(
    state: State<'_, StudioState>,
    mode: String,
    custom: CanvasCustomState,
) -> Result<(), String> {
    let local_conn = state.local_db.lock().map_err(|_| "本机库锁获取失败")?;
    save_canvas_custom_state(&local_conn, &mode, &custom).map_err(map_err)
}

#[tauri::command]
fn series_manage_list(state: State<'_, StudioState>) -> Result<Vec<SeriesManageItem>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    list_series_manage(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn series_rename(
    state: State<'_, StudioState>,
    series_id: i64,
    new_name: String,
) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    rename_series(&mut sync_conn, series_id, &new_name).map_err(map_err)
}

#[tauri::command]
fn series_merge(
    state: State<'_, StudioState>,
    source_series_id: i64,
    target_series_id: i64,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    merge_series(&mut sync_conn, source_series_id, target_series_id).map_err(map_err)
}

#[tauri::command]
fn series_delete(state: State<'_, StudioState>, series_id: i64) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    delete_series(&mut sync_conn, series_id).map_err(map_err)
}

#[tauri::command]
fn series_remove_member(
    state: State<'_, StudioState>,
    series_id: i64,
    kind: String,
    work_id: i64,
) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    remove_member_from_series(&mut sync_conn, series_id, &kind, work_id).map_err(map_err)
}

#[tauri::command]
fn series_set_member_role(
    state: State<'_, StudioState>,
    series_id: i64,
    kind: String,
    work_id: i64,
    role: String,
) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    update_series_member_role(&mut sync_conn, series_id, &kind, work_id, &role).map_err(map_err)
}

#[tauri::command]
fn tags_manage_list(state: State<'_, StudioState>) -> Result<Vec<TagManageItem>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    list_tags_manage(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn tag_rename(
    state: State<'_, StudioState>,
    tag_id: i64,
    new_name: String,
) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    rename_tag(&mut sync_conn, tag_id, &new_name).map_err(map_err)
}

#[tauri::command]
fn tag_merge(
    state: State<'_, StudioState>,
    source_tag_id: i64,
    target_tag_id: i64,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    merge_tags(&mut sync_conn, source_tag_id, target_tag_id).map_err(map_err)
}

#[tauri::command]
fn tag_delete(state: State<'_, StudioState>, tag_id: i64) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    delete_tag(&mut sync_conn, tag_id).map_err(map_err)
}

#[tauri::command]
fn tag_purge_unused(state: State<'_, StudioState>) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    purge_unused_tags(&mut sync_conn).map_err(map_err)
}

#[tauri::command]
fn tag_toggle_blocked(
    state: State<'_, StudioState>,
    tag_id: i64,
    blocked: bool,
) -> Result<(), String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    toggle_tag_blocked(&sync_conn, tag_id, blocked).map_err(map_err)
}

#[tauri::command]
fn characters_manage_list(
    state: State<'_, StudioState>,
) -> Result<Vec<CharacterManageItem>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    list_characters_manage(&sync_conn).map_err(map_err)
}

#[tauri::command]
fn character_upsert(
    state: State<'_, StudioState>,
    input: UpsertCharacterInput,
) -> Result<i64, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    upsert_character(&sync_conn, &input).map_err(map_err)
}

#[tauri::command]
async fn catalog_search_characters(
    state: State<'_, StudioState>,
    keyword: String,
) -> Result<Vec<RemoteCharacterHit>, String> {
    let dir = state.studio_data_dir.clone();
    search_characters_via_server_first(&dir, &keyword)
        .await
        .map_err(map_err)
}

#[tauri::command]
async fn catalog_fetch_work_characters(
    state: State<'_, StudioState>,
    subject_id: i64,
) -> Result<Vec<RemoteCharacterHit>, String> {
    let dir = state.studio_data_dir.clone();
    fetch_work_characters_via_server_first(&dir, subject_id)
        .await
        .map_err(map_err)
}

#[tauri::command]
fn character_delete(state: State<'_, StudioState>, character_id: i64) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    delete_character(&mut sync_conn, character_id).map_err(map_err)
}

#[tauri::command]
fn character_link_work(
    state: State<'_, StudioState>,
    character_id: i64,
    anime_id: i64,
    role_name: Option<String>,
    voice_actor: Option<String>,
    preserve_missing_voice_actor: Option<bool>,
) -> Result<(), String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    let save = if preserve_missing_voice_actor.unwrap_or(false) {
        link_character_work_from_catalog
    } else {
        link_character_work
    };
    save(
        &sync_conn,
        character_id,
        anime_id,
        role_name.as_deref(),
        voice_actor.as_deref(),
    )
    .map_err(map_err)
}

#[tauri::command]
fn character_unlink_work(
    state: State<'_, StudioState>,
    character_id: i64,
    anime_id: i64,
) -> Result<(), String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    unlink_character_work(&sync_conn, character_id, anime_id).map_err(map_err)
}

#[tauri::command]
fn character_relation_upsert(
    state: State<'_, StudioState>,
    input: UpsertCharacterRelationInput,
) -> Result<i64, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    upsert_character_relation(&sync_conn, &input).map_err(map_err)
}

#[tauri::command]
fn character_relation_delete(
    state: State<'_, StudioState>,
    relation_id: i64,
) -> Result<(), String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    delete_character_relation(&sync_conn, relation_id).map_err(map_err)
}

#[tauri::command]
fn work_list_characters(
    state: State<'_, StudioState>,
    anime_id: i64,
) -> Result<Vec<WorkCharacterBrief>, String> {
    let sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    list_work_characters(&sync_conn, anime_id).map_err(map_err)
}

#[tauri::command]
fn character_set_tags(
    state: State<'_, StudioState>,
    character_id: i64,
    tags: Vec<String>,
) -> Result<(), String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    set_character_tags(&mut sync_conn, character_id, &tags).map_err(map_err)
}

#[tauri::command]
fn character_batch_delete(
    state: State<'_, StudioState>,
    character_ids: Vec<i64>,
) -> Result<usize, String> {
    let mut sync_conn = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试")?;
    batch_delete_characters(&mut sync_conn, &character_ids).map_err(map_err)
}

#[tauri::command]
fn system_save_png_base64(output_path: String, data_url: String) -> Result<(), String> {
    save_png_data_url(Path::new(&output_path), &data_url).map_err(map_err)
}

#[cfg(target_os = "windows")]
fn open_url_windows_shell(url: &str) -> Result<(), String> {
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStrExt;
    use std::ptr::null;

    #[link(name = "shell32")]
    extern "system" {
        fn ShellExecuteW(
            hwnd: *mut c_void,
            lp_operation: *const u16,
            lp_file: *const u16,
            lp_parameters: *const u16,
            lp_directory: *const u16,
            n_show_cmd: i32,
        ) -> isize;
    }

    let to_wide = |s: &str| -> Vec<u16> {
        std::ffi::OsStr::new(s)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect()
    };

    let op = to_wide("open");
    let target = to_wide(url);
    const SW_SHOWNORMAL: i32 = 1;

    // 直接调用 Win32 ShellExecuteW 交给默认浏览器打开完整 URL，
    // 避免 explorer.exe 解析含 & / = / % 的 OAuth 链接失败后退化打开“文档”文件夹。
    let rc = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            op.as_ptr(),
            target.as_ptr(),
            null(),
            null(),
            SW_SHOWNORMAL,
        )
    };
    if rc <= 32 {
        return Err(format!("无法调用系统默认浏览器打开链接（ShellExecuteW={rc}）"));
    }
    Ok(())
}

#[tauri::command]
fn system_open_https_url(url: String) -> Result<(), String> {
    let trimmed = url.trim();
    if !trimmed.starts_with("https://") {
        return Err("安全限制：仅允许在浏览器打开 HTTPS 链接".into());
    }
    #[cfg(target_os = "windows")]
    {
        open_url_windows_shell(trimmed)?;
    }
    Ok(())
}

#[cfg(target_os = "windows")]
const AUTOSTART_REG_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
#[cfg(target_os = "windows")]
const AUTOSTART_REG_VALUE: &str = "AniMeow Studio";

#[tauri::command]
fn system_get_autostart() -> Result<bool, String> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let out = std::process::Command::new("reg.exe")
            .args(["query", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(map_err)?;
        Ok(out.status.success())
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(false)
    }
}

#[tauri::command]
fn system_set_autostart(enabled: bool) -> Result<bool, String> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        if enabled {
            let exe = std::env::current_exe().map_err(map_err)?;
            let exe_quoted = format!("\"{}\"", exe.to_string_lossy());
            let out = std::process::Command::new("reg.exe")
                .args([
                    "add",
                    AUTOSTART_REG_KEY,
                    "/v",
                    AUTOSTART_REG_VALUE,
                    "/t",
                    "REG_SZ",
                    "/d",
                    &exe_quoted,
                    "/f",
                ])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
                .map_err(map_err)?;
            if !out.status.success() {
                return Err("写入 Windows 开机自启动注册表项失败".into());
            }
            Ok(true)
        } else {
            let _ = std::process::Command::new("reg.exe")
                .args(["delete", AUTOSTART_REG_KEY, "/v", AUTOSTART_REG_VALUE, "/f"])
                .creation_flags(CREATE_NO_WINDOW)
                .output();
            Ok(false)
        }
    }
    #[cfg(not(target_os = "windows"))]
    {
        let _ = enabled;
        Ok(false)
    }
}

#[tauri::command]
fn system_set_close_to_tray(
    state: State<'_, StudioState>,
    enabled: bool,
) -> Result<(), String> {
    state.minimize_to_tray.store(enabled, Ordering::SeqCst);
    Ok(())
}

#[tauri::command]
fn settings_get_desktop(state: State<'_, StudioState>) -> Result<Option<serde_json::Value>, String> {
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    load_desktop_settings(&paths).map_err(map_err)
}

#[tauri::command]
fn settings_save_desktop(
    state: State<'_, StudioState>,
    settings: serde_json::Value,
) -> Result<(), String> {
    if let Some(close_to_tray) = settings.get("closeToTray").and_then(|v| v.as_bool()) {
        state.minimize_to_tray.store(close_to_tray, Ordering::SeqCst);
    }
    if let Some(cov_pol) = settings
        .get("coverStoragePolicy")
        .and_then(|v| v.as_str())
        .filter(|s| matches!(*s, "remote" | "local" | "auto"))
    {
        if let Ok(local) = state.local_db.lock() {
            if let Ok(mut cfg) = agent_get_policy_core(&local) {
                cfg.cover_storage_policy = cov_pol.to_string();
                let _ = agent_save_policy_core(&local, cfg);
            }
        }
    }
    let paths = BackupPaths::from_studio_root(&state.studio_data_dir);
    save_desktop_settings(&paths, &settings).map_err(map_err)
}

#[tauri::command]
fn system_get_data_dir_info(
    app: AppHandle,
    state: State<'_, StudioState>,
) -> Result<serde_json::Value, String> {
    Ok(serde_json::json!({
        "dataDir": state.studio_data_dir.to_string_lossy().to_string(),
        "appVersion": app.package_info().version.to_string(),
    }))
}

#[tauri::command]
fn system_open_data_dir(state: State<'_, StudioState>) -> Result<(), String> {
    let dir = state.studio_data_dir.clone();
    std::fs::create_dir_all(&dir).map_err(map_err)?;
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        std::process::Command::new("explorer.exe")
            .arg(&dir)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map_err(map_err)?;
    }
    Ok(())
}

fn build_desktop_updater(
    app: &AppHandle,
    studio_data_dir: &Path,
) -> Result<(tauri_plugin_updater::Updater, String), String> {
    let cfg = load_cloud_endpoint_config(studio_data_dir);
    let base = cfg.api_base.trim().trim_end_matches('/');
    let endpoint_str = if base.starts_with("https://") || base.starts_with("http://") {
        format!("{base}/api/desktop-updater/latest.json")
    } else {
        "https://www.xunlys.top/api/desktop-updater/latest.json".to_string()
    };

    let mut builder = app.updater_builder();
    if let Ok(parsed) = url::Url::parse(&endpoint_str) {
        if parsed.scheme() == "https" {
            builder = builder.endpoints(vec![parsed]).map_err(map_err)?;
        }
    }
    let updater = builder.build().map_err(map_err)?;
    Ok((updater, endpoint_str))
}

#[tauri::command]
async fn updater_check(
    app: AppHandle,
    state: State<'_, StudioState>,
) -> Result<DesktopUpdaterCheckResult, String> {
    let dir = state.studio_data_dir.clone();
    let current_version = app.package_info().version.to_string();
    let (updater, endpoint) = build_desktop_updater(&app, &dir)?;

    let found = updater.check().await.map_err(|err| format!("检查新版本失败：{err}"))?;
    let mut guard = state
        .pending_update
        .lock()
        .map_err(|_| "更新状态异常，请稍后重试".to_string())?;

    if let Some(update) = found {
        let already_downloaded = guard
            .as_ref()
            .is_some_and(|p| p.update.version == update.version && p.downloaded_bytes.is_some());
        if !already_downloaded {
            *guard = Some(PendingUpdaterPayload {
                update: update.clone(),
                downloaded_bytes: None,
            });
        }
        return Ok(DesktopUpdaterCheckResult {
            available: true,
            current_version,
            version: Some(update.version),
            notes: update.body,
            pub_date: update.date.map(|d| d.to_string()),
            downloaded: already_downloaded,
            endpoint,
        });
    }

    *guard = None;
    Ok(DesktopUpdaterCheckResult {
        available: false,
        current_version,
        version: None,
        notes: None,
        pub_date: None,
        downloaded: false,
        endpoint,
    })
}

#[tauri::command]
async fn updater_download(
    app: AppHandle,
    state: State<'_, StudioState>,
) -> Result<DesktopUpdaterDownloadResult, String> {
    let update = {
        let guard = state
            .pending_update
            .lock()
            .map_err(|_| "更新状态异常，请稍后重试".to_string())?;
        if let Some(payload) = guard.as_ref() {
            if let Some(ref bytes) = payload.downloaded_bytes {
                return Ok(DesktopUpdaterDownloadResult {
                    version: payload.update.version.clone(),
                    total_bytes: bytes.len() as u64,
                });
            }
            payload.update.clone()
        } else {
            return Err("尚未检测到可下载的新版本，请先检查更新".to_string());
        }
    };

    let _ = app.emit(
        "updater://progress",
        DesktopUpdaterProgress {
            downloaded: 0,
            total: None,
            percent: Some(0.0),
            phase: "downloading".to_string(),
        },
    );

    let app_for_chunk = app.clone();
    let app_for_finish = app.clone();
    let mut downloaded_so_far: u64 = 0;
    let mut last_emitted: u64 = 0;

    let bytes = update
        .download(
            move |chunk_len, content_length| {
                downloaded_so_far += chunk_len as u64;
                let should_emit = downloaded_so_far.saturating_sub(last_emitted) >= 131_072
                    || content_length.is_some_and(|total| downloaded_so_far >= total);
                if should_emit {
                    last_emitted = downloaded_so_far;
                    let percent = content_length.and_then(|t| {
                        if t > 0 {
                            Some(((downloaded_so_far as f64 / t as f64) * 100.0).min(100.0))
                        } else {
                            None
                        }
                    });
                    let _ = app_for_chunk.emit(
                        "updater://progress",
                        DesktopUpdaterProgress {
                            downloaded: downloaded_so_far,
                            total: content_length,
                            percent,
                            phase: "downloading".to_string(),
                        },
                    );
                }
            },
            move || {
                let _ = app_for_finish.emit(
                    "updater://progress",
                    DesktopUpdaterProgress {
                        downloaded: downloaded_so_far,
                        total: Some(downloaded_so_far),
                        percent: Some(100.0),
                        phase: "verifying".to_string(),
                    },
                );
            },
        )
        .await
        .map_err(|err| format!("下载或校验更新包失败：{err}"))?;

    let total_bytes = bytes.len() as u64;
    let version = update.version.clone();
    {
        let mut guard = state
            .pending_update
            .lock()
            .map_err(|_| "更新状态异常，请稍后重试".to_string())?;
        *guard = Some(PendingUpdaterPayload {
            update,
            downloaded_bytes: Some(bytes),
        });
    }

    let _ = app.emit(
        "updater://progress",
        DesktopUpdaterProgress {
            downloaded: total_bytes,
            total: Some(total_bytes),
            percent: Some(100.0),
            phase: "ready".to_string(),
        },
    );

    Ok(DesktopUpdaterDownloadResult {
        version,
        total_bytes,
    })
}

#[tauri::command]
async fn updater_install_and_restart(
    app: AppHandle,
    state: State<'_, StudioState>,
) -> Result<(), String> {
    let (update, bytes) = {
        let mut guard = state
            .pending_update
            .lock()
            .map_err(|_| "更新状态异常，请稍后重试".to_string())?;
        let payload = guard
            .take()
            .ok_or_else(|| "尚未下载更新包，请先完成下载".to_string())?;
        let bytes = payload
            .downloaded_bytes
            .ok_or_else(|| "更新包尚未下载完成，请先下载".to_string())?;
        (payload.update, bytes)
    };

    // 解除托盘常驻拦截，确保静默安装器能顺利接管并重启新版
    state.explicit_quit.store(true, Ordering::SeqCst);

    if let Err(err) = update.install(&bytes) {
        state.explicit_quit.store(false, Ordering::SeqCst);
        if let Ok(mut guard) = state.pending_update.lock() {
            *guard = Some(PendingUpdaterPayload {
                update,
                downloaded_bytes: Some(bytes),
            });
        }
        return Err(format!("启动更新安装程序失败：{err}"));
    }

    #[cfg(not(windows))]
    {
        app.restart();
    }
    #[cfg(windows)]
    {
        let _ = app;
    }
    Ok(())
}

// ============================================================================
// 桌面端独有 AI 智能体（AniMeow Agent Harness）命令
// ============================================================================

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AgentSessionDataPayload {
    messages: Vec<AgentMessage>,
    proposals: Vec<AgentProposal>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MemoryDraftItem {
    category: String,
    content: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CompactSessionPayload {
    compacted: bool,
    summary: Option<String>,
    compacted_messages: usize,
}

#[tauri::command]
fn agent_list_providers() -> Vec<ProviderPreset> {
    list_provider_presets()
}

#[tauri::command]
fn agent_list_profiles(state: State<'_, StudioState>) -> Result<Vec<AgentProfile>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_list_profiles_core(&local, &state.studio_data_dir).map_err(map_err)
}

#[tauri::command]
fn agent_save_profile(
    state: State<'_, StudioState>,
    input: SaveAgentProfileInput,
) -> Result<AgentProfile, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_save_profile_core(&local, &state.studio_data_dir, input).map_err(map_err)
}

#[tauri::command]
fn agent_delete_profile(
    state: State<'_, StudioState>,
    profile_id: String,
) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_delete_profile_core(&local, &state.studio_data_dir, &profile_id).map_err(map_err)
}

#[tauri::command]
async fn agent_fetch_models(
    state: State<'_, StudioState>,
    base_url: String,
    api_key: Option<String>,
    profile_id: Option<String>,
) -> Result<FetchModelsResult, String> {
    let effective_key = match api_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(k) => Some(k.to_string()),
        None => {
            if let Some(pid) = profile_id.as_deref().filter(|s| !s.trim().is_empty()) {
                load_profile_api_key(&state.studio_data_dir, pid).ok().flatten()
            } else {
                None
            }
        }
    };
    fetch_provider_models(&base_url, effective_key.as_deref())
        .await
        .map_err(|diag| serde_json::to_string(&diag).unwrap_or(diag.title))
}

#[tauri::command]
async fn agent_probe_profile(
    state: State<'_, StudioState>,
    base_url: String,
    model: String,
    api_key: Option<String>,
    profile_id: Option<String>,
) -> Result<ProbeResult, String> {
    let effective_key = match api_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(k) => Some(k.to_string()),
        None => {
            if let Some(pid) = profile_id.as_deref().filter(|s| !s.trim().is_empty()) {
                load_profile_api_key(&state.studio_data_dir, pid).ok().flatten()
            } else {
                None
            }
        }
    };
    let probe = probe_agent_profile(&base_url, effective_key.as_deref(), &model).await;
    if let Some(pid) = profile_id.as_deref().filter(|s| !s.trim().is_empty()) {
        if probe.ok {
            if let Ok(local) = state.local_db.lock() {
                let _ = update_profile_probe(&local, pid, &probe);
            }
        }
    }
    Ok(probe)
}

#[tauri::command]
fn agent_get_policy(state: State<'_, StudioState>) -> Result<AgentPolicyConfig, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_get_policy_core(&local).map_err(map_err)
}

#[tauri::command]
fn agent_save_policy(
    state: State<'_, StudioState>,
    config: AgentPolicyConfig,
) -> Result<AgentPolicyConfig, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_save_policy_core(&local, config).map_err(map_err)
}

#[tauri::command]
fn agent_list_sessions(
    state: State<'_, StudioState>,
    include_archived: Option<bool>,
    keyword: Option<String>,
) -> Result<Vec<AgentSession>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_list_sessions_core(&local, include_archived.unwrap_or(false), keyword.as_deref())
        .map_err(map_err)
}

#[tauri::command]
fn agent_create_session(
    state: State<'_, StudioState>,
    title: Option<String>,
    profile_id: Option<String>,
    persona_id: Option<String>,
) -> Result<AgentSession, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_create_session_core(&local, title.as_deref(), profile_id.as_deref(), persona_id.as_deref())
        .map_err(map_err)
}

#[tauri::command]
fn agent_update_session(
    state: State<'_, StudioState>,
    session_id: String,
    title: Option<String>,
    pinned: Option<bool>,
    archived: Option<bool>,
    persona_id: Option<String>,
    profile_id: Option<String>,
    rolling_summary: Option<Option<String>>,
) -> Result<AgentSession, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let summary_ref = rolling_summary
        .as_ref()
        .map(|opt| opt.as_deref());
    update_session_meta(
        &local,
        &session_id,
        title.as_deref(),
        pinned,
        archived,
        persona_id.as_deref(),
        profile_id.as_deref(),
        summary_ref,
    )
    .map_err(map_err)
}

#[tauri::command]
fn agent_clear_session(state: State<'_, StudioState>, session_id: String) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_clear_session_core(&local, &session_id).map_err(map_err)
}

#[tauri::command]
fn agent_truncate_session_from(
    state: State<'_, StudioState>,
    session_id: String,
    message_id: String,
) -> Result<usize, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    truncate_session_from_message(&local, &session_id, &message_id).map_err(map_err)
}

#[tauri::command]
fn agent_delete_session(state: State<'_, StudioState>, session_id: String) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_delete_session_core(&local, &session_id).map_err(map_err)
}

#[tauri::command]
fn agent_load_session_data(
    state: State<'_, StudioState>,
    session_id: String,
) -> Result<AgentSessionDataPayload, String> {
    let sync = state.sync_db.lock().map_err(|_| "藏品库暂不可用")?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    recover_proposal_writes(&sync, &local).map_err(map_err)?;
    let messages = agent_load_messages_core(&local, &session_id).map_err(map_err)?;
    let proposals = agent_load_proposals_core(&local, &session_id).map_err(map_err)?;
    Ok(AgentSessionDataPayload { messages, proposals })
}

#[tauri::command]
fn agent_export_session_markdown(
    state: State<'_, StudioState>,
    session_id: String,
    output_path: Option<String>,
) -> Result<String, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let md = export_session_markdown(&local, &session_id).map_err(map_err)?;
    if let Some(path) = output_path.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        std::fs::write(path, &md).map_err(map_err)?;
    }
    Ok(md)
}

#[tauri::command]
async fn agent_compact_session(
    state: State<'_, StudioState>,
    session_id: String,
    profile_id: Option<String>,
    keep_recent_turns: Option<usize>,
) -> Result<CompactSessionPayload, String> {
    let handles = AgentRuntimeHandles {
        studio_data_dir: state.studio_data_dir.clone(),
        sync_db: Arc::clone(&state.sync_db),
        local_db: Arc::clone(&state.local_db),
    };
    let keep = keep_recent_turns.unwrap_or(6).clamp(2, 20);
    match compact_session_context(&handles, &session_id, profile_id.as_deref(), keep).await {
        Ok(Some((summary, _, cnt))) => Ok(CompactSessionPayload {
            compacted: true,
            summary: Some(summary),
            compacted_messages: cnt,
        }),
        Ok(None) => Ok(CompactSessionPayload {
            compacted: false,
            summary: None,
            compacted_messages: 0,
        }),
        Err(diag) => Err(diag.title),
    }
}

#[tauri::command]
fn agent_send_message(
    app: AppHandle,
    state: State<'_, StudioState>,
    input: SendAgentMessageInput,
) -> Result<String, String> {
    let run_id = uuid::Uuid::new_v4().to_string();
    let cancel = CancellationToken::new();
    {
        let mut runs = state
            .agent_runs
            .lock()
            .map_err(|_| "运行表锁获取失败".to_string())?;
        runs.insert(run_id.clone(), cancel.clone());
    }

    let handles = AgentRuntimeHandles {
        studio_data_dir: state.studio_data_dir.clone(),
        sync_db: Arc::clone(&state.sync_db),
        local_db: Arc::clone(&state.local_db),
    };
    let runs_arc = Arc::clone(&state.agent_runs);
    let run_id_bg = run_id.clone();
    let app_bg = app.clone();

    tauri::async_runtime::spawn(async move {
        let app_emit = app_bg.clone();
        run_agent_turn(handles, run_id_bg.clone(), input, cancel, move |env| {
            let _ = app_emit.emit("agent://stream", &env);
        })
        .await;
        if let Ok(mut runs) = runs_arc.lock() {
            runs.remove(&run_id_bg);
        }
    });

    Ok(run_id)
}

#[tauri::command]
fn agent_cancel_run(state: State<'_, StudioState>, run_id: String) -> Result<bool, String> {
    let runs = state
        .agent_runs
        .lock()
        .map_err(|_| "运行表锁获取失败".to_string())?;
    if let Some(tok) = runs.get(&run_id) {
        tok.cancel();
        return Ok(true);
    }
    Ok(false)
}

#[tauri::command]
fn agent_fork_session(
    state: State<'_, StudioState>,
    source_session_id: String,
    up_to_message_id: Option<String>,
    title: Option<String>,
) -> Result<AgentSession, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_fork_session_core(
        &local,
        &source_session_id,
        up_to_message_id.as_deref(),
        title.as_deref(),
    )
    .map_err(map_err)
}

#[tauri::command]
fn agent_switch_message_version(
    state: State<'_, StudioState>,
    session_id: String,
    message_id: String,
    version_idx: usize,
) -> Result<AgentMessage, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_switch_version_core(&local, &session_id, &message_id, version_idx).map_err(map_err)
}

#[tauri::command]
async fn agent_commit_proposal(
    state: State<'_, StudioState>,
    proposal_id: String,
    selected_indices: Option<Vec<usize>>,
) -> Result<ProposalApplyResult, String> {
    let is_mcp = {
        let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
        agent_load_proposal_core(&local, &proposal_id)
            .map_err(map_err)?
            .map(|(p, _)| p.kind == "mcp_call")
            .unwrap_or(false)
    };
    if is_mcp {
        return commit_mcp_call_proposal(&state.local_db, &state.studio_data_dir, &proposal_id)
            .await
            .map_err(map_err);
    }
    let mut sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_commit_proposal_core(&mut sync, &local, &proposal_id, selected_indices.as_deref())
        .map_err(map_err)
}

#[tauri::command]
fn agent_reject_proposal(
    state: State<'_, StudioState>,
    proposal_id: String,
) -> Result<AgentProposal, String> {
    let sync = state.sync_db.lock().map_err(|_| "藏品库暂不可用")?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    recover_proposal_writes(&sync, &local).map_err(map_err)?;
    agent_reject_proposal_core(&local, &proposal_id).map_err(map_err)
}

#[tauri::command]
fn agent_revert_proposal(
    state: State<'_, StudioState>,
    proposal_id: String,
) -> Result<ProposalApplyResult, String> {
    let mut sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_revert_proposal_core(&mut sync, &local, &proposal_id).map_err(map_err)
}

#[tauri::command]
fn agent_commit_proposals_batch(
    state: State<'_, StudioState>,
    proposal_ids: Vec<String>,
    safe_only: Option<bool>,
) -> Result<BatchProposalCommitResult, String> {
    let mut sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_commit_proposals_batch_core(&mut sync, &local, &proposal_ids, safe_only.unwrap_or(false))
        .map_err(map_err)
}

#[tauri::command]
fn agent_revert_proposals_batch(
    state: State<'_, StudioState>,
    batch_group_id: String,
) -> Result<BatchProposalRevertResult, String> {
    let mut sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_revert_proposals_batch_core(&mut sync, &local, &batch_group_id).map_err(map_err)
}

#[tauri::command]
fn get_cover_baseline_stats(state: State<'_, StudioState>) -> Result<CoverBaselineStats, String> {
    let sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    compute_cover_baseline_stats(&sync, &local, &state.studio_data_dir).map_err(map_err)
}

#[tauri::command]
fn cleanup_orphan_covers(
    state: State<'_, StudioState>,
    dry_run: Option<bool>,
) -> Result<OrphanCoverCleanupResult, String> {
    let sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    cleanup_orphan_native_covers(&sync, &local, &state.studio_data_dir, dry_run.unwrap_or(true))
        .map_err(map_err)
}

#[tauri::command]
fn agent_list_memories(state: State<'_, StudioState>) -> Result<Vec<AgentMemory>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_list_memories_core(&local).map_err(map_err)
}

#[tauri::command]
fn agent_upsert_memory(
    state: State<'_, StudioState>,
    id: Option<String>,
    category: String,
    tier: Option<String>,
    content: String,
    enabled: bool,
) -> Result<AgentMemory, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_upsert_memory_core(
        &local,
        id.as_deref(),
        &category,
        tier.as_deref(),
        &content,
        enabled,
    )
    .map_err(map_err)
}

#[tauri::command]
fn agent_delete_memory(state: State<'_, StudioState>, id: String) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_delete_memory_core(&local, &id).map_err(map_err)
}

#[tauri::command]
fn agent_draft_memories_from_library(
    state: State<'_, StudioState>,
) -> Result<Vec<MemoryDraftItem>, String> {
    let sync = state.sync_db.lock().map_err(|_| "sync_db 锁获取失败".to_string())?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let raw = draft_memories_from_library(&sync, &local).map_err(map_err)?;
    Ok(raw
        .into_iter()
        .map(|(category, content)| MemoryDraftItem { category, content })
        .collect())
}

#[tauri::command]
fn agent_list_commands(state: State<'_, StudioState>) -> Result<Vec<AgentSlashCommand>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_list_commands_core(&local).map_err(map_err)
}

#[tauri::command]
fn agent_save_command(
    state: State<'_, StudioState>,
    id: Option<String>,
    command: String,
    label: String,
    description: String,
    prompt_template: String,
    skill_guide: Option<String>,
    tool_scope: Option<Vec<String>>,
) -> Result<AgentSlashCommand, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_save_command_core(
        &local,
        id.as_deref(),
        &command,
        &label,
        &description,
        &prompt_template,
        skill_guide.as_deref(),
        tool_scope,
    )
    .map_err(map_err)
}

#[tauri::command]
fn agent_delete_command(state: State<'_, StudioState>, id: String) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_delete_slash_command_core(&local, &id).map_err(map_err)
}

#[tauri::command]
fn agent_mcp_list(state: State<'_, StudioState>) -> Result<Vec<McpServerConfig>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_list_mcp_core(&local, &state.studio_data_dir).map_err(map_err)
}

#[tauri::command]
fn agent_mcp_save(
    state: State<'_, StudioState>,
    input: SaveMcpServerInput,
) -> Result<McpServerConfig, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_save_mcp_core(&local, &state.studio_data_dir, input).map_err(map_err)
}

#[tauri::command]
fn agent_mcp_delete(state: State<'_, StudioState>, server_id: String) -> Result<(), String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    agent_delete_mcp_core(&local, &state.studio_data_dir, &server_id).map_err(map_err)
}

#[tauri::command]
async fn agent_mcp_test(
    state: State<'_, StudioState>,
    server_id: String,
) -> Result<McpTestResult, String> {
    let (server, auth_token) = {
        let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
        let srv = agent_list_mcp_core(&local, &state.studio_data_dir)
            .map_err(map_err)?
            .into_iter()
            .find(|s| s.id == server_id)
            .ok_or_else(|| "MCP 服务不存在".to_string())?;
        let tok = load_mcp_auth_token(&state.studio_data_dir, &srv.id)
            .ok()
            .flatten();
        (srv, tok)
    };
    let result = test_and_list_mcp_tools(&server, auth_token.as_deref()).await;
    if let Ok(local) = state.local_db.lock() {
        let status_text = if result.ok {
            format!("正常 · {} 个工具", result.tools.len())
        } else {
            format!("异常 · {}", result.error.as_deref().unwrap_or("连接失败"))
        };
        let _ = update_mcp_server_status(&local, &server.id, &status_text, result.latency_ms);
    }
    Ok(result)
}

#[tauri::command]
fn agent_mcp_import_json(
    state: State<'_, StudioState>,
    raw_json: String,
) -> Result<Vec<McpServerConfig>, String> {
    let parsed = parse_mcp_servers_json_snippet(&raw_json).map_err(map_err)?;
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let mut saved = Vec::new();
    for item in parsed {
        saved.push(agent_save_mcp_core(&local, &state.studio_data_dir, item).map_err(map_err)?);
    }
    Ok(saved)
}

#[tauri::command]
fn agent_load_trajectory(
    state: State<'_, StudioState>,
    session_id: String,
) -> Result<AgentTrajectorySummary, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    load_session_trajectory(&local, &session_id).map_err(map_err)
}

#[tauri::command]
fn agent_export_trajectory(
    state: State<'_, StudioState>,
    session_id: String,
    format: String,
    output_path: Option<String>,
) -> Result<String, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let content = export_session_trajectory(&local, &session_id, &format).map_err(map_err)?;
    if let Some(path) = output_path.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        std::fs::write(path, &content).map_err(map_err)?;
    }
    Ok(content)
}

#[tauri::command]
fn agent_read_artifact(
    state: State<'_, StudioState>,
    artifact_id: String,
    offset: Option<usize>,
    limit: Option<usize>,
) -> Result<serde_json::Value, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    read_tool_artifact(&local, &artifact_id, offset.unwrap_or(0), limit.unwrap_or(4500))
        .map_err(map_err)
}

#[tauri::command]
async fn rehydrate_work_from_remote(
    state: State<'_, StudioState>,
    kind: String,
    work_id: i64,
) -> Result<serde_json::Value, String> {
    let sync_db = state.sync_db.clone();
    let local_db = state.local_db.clone();
    let studio_data_dir = state.studio_data_dir.clone();
    rehydrate_single_work_direct(&sync_db, &local_db, &studio_data_dir, &kind, work_id)
        .await
        .map_err(map_err)
}

#[tauri::command]
fn agent_save_filter_snapshot(
    state: State<'_, StudioState>,
    session_id: String,
    items: Vec<CuratorItem>,
    label: String,
) -> Result<String, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    save_filter_snapshot(&local, &session_id, &items, &label).map_err(map_err)
}

#[tauri::command]
fn agent_export_feedback_diagnostics(
    state: State<'_, StudioState>,
    session_id: String,
    output_path: Option<String>,
) -> Result<String, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let content =
        export_session_feedback_diagnostics(&local, &session_id, env!("CARGO_PKG_VERSION"))
            .map_err(map_err)?;
    if let Some(path) = output_path.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        std::fs::write(path, &content).map_err(map_err)?;
    }
    Ok(content)
}

#[tauri::command]
fn agent_list_verify_jobs(
    state: State<'_, StudioState>,
    session_id: String,
) -> Result<Vec<VerifyJobSummary>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    list_session_verify_jobs(&local, &session_id).map_err(map_err)
}

#[tauri::command]
fn agent_get_verify_job(
    state: State<'_, StudioState>,
    job_id: String,
) -> Result<Option<VerifyJobDetail>, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    get_verify_job_detail(&local, &job_id).map_err(map_err)
}

#[tauri::command]
async fn agent_run_verify_job_step(
    state: State<'_, StudioState>,
    job_id: String,
    batch_size: Option<usize>,
    retry_failed: Option<bool>,
) -> Result<serde_json::Value, String> {
    let local_db = state.local_db.clone();
    let studio_data_dir = state.studio_data_dir.clone();
    run_verify_job_batch(
        &studio_data_dir,
        &local_db,
        &job_id,
        batch_size.unwrap_or(20),
        retry_failed.unwrap_or(false),
    )
    .await
    .map_err(map_err)
}

#[tauri::command]
fn agent_resolve_verify_item(
    state: State<'_, StudioState>,
    job_id: String,
    kind: String,
    work_id: i64,
    decision: String,
    external_id: Option<String>,
    original_title: Option<String>,
) -> Result<VerifyJobDetail, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    resolve_verify_job_item_decision(
        &local,
        &job_id,
        &kind,
        work_id,
        &decision,
        external_id.as_deref(),
        original_title.as_deref(),
    )
    .map_err(map_err)
}

#[tauri::command]
fn agent_create_proposal_from_verify_job(
    state: State<'_, StudioState>,
    session_id: String,
    job_id: String,
) -> Result<serde_json::Value, String> {
    let local = state.local_db.lock().map_err(|_| "local_db 锁获取失败".to_string())?;
    let sync = state.sync_db.lock().map_err(|_| "本地数据正忙，请稍后重试".to_string())?;
    let detail = get_verify_job_detail(&local, &job_id)
        .map_err(map_err)?
        .ok_or_else(|| format!("未找到核实任务 {job_id}"))?;
    let bindings = collect_verify_job_bindable_entries(&detail);
    if bindings.is_empty() {
        return Err("该核实任务当前没有可直接绑定的条目（请先核实或手动点选候选）".to_string());
    }
    let args = serde_json::json!({
        "title": format!("批量绑定 Bangumi 外部 ID（{} 条）", bindings.len()),
        "bindings": bindings,
    });
    let outcome = create_bulk_bind_proposal(&sync, &local, &session_id, &args).map_err(map_err)?;
    Ok(serde_json::json!({
        "proposal": outcome.proposal,
        "validCount": outcome.valid_count,
        "conflicts": outcome.conflicts,
        "skipped": outcome.skipped,
    }))
}

#[tauri::command]
async fn studio_open_popout_window(
    app: AppHandle,
    label: String,
    title: String,
    query: String,
    width: Option<f64>,
    height: Option<f64>,
) -> Result<(), String> {
    let clean_label = label.trim();
    if !clean_label.starts_with("popout-")
        || clean_label.len() > 64
        || !clean_label
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err("无效的独立窗口标识".to_string());
    }
    if let Some(existing) = app.get_webview_window(clean_label) {
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(());
    }
    let w = width.unwrap_or(680.0).clamp(380.0, 1440.0);
    let h = height.unwrap_or(640.0).clamp(320.0, 1080.0);
    let safe_query = query.trim().trim_start_matches('?');
    let rel = if safe_query.is_empty() {
        "index.html".to_string()
    } else {
        format!("index.html?{safe_query}")
    };
    let url = tauri::WebviewUrl::App(PathBuf::from(rel));
    let win_title = if title.trim().is_empty() {
        "追番喵"
    } else {
        title.trim()
    };
    let win = WebviewWindowBuilder::new(&app, clean_label, url)
        .title(win_title)
        .inner_size(w, h)
        .min_inner_size(380.0, 320.0)
        .resizable(true)
        .decorations(false)
        .shadow(true)
        .center()
        .build()
        .map_err(|e| format!("无法打开独立窗口：{e}"))?;
    let _ = win.show();
    let _ = win.set_focus();
    Ok(())
}

#[tauri::command]
fn studio_focus_popout_if_open(app: AppHandle, label: String) -> Result<bool, String> {
    let clean_label = label.trim();
    if !clean_label.starts_with("popout-")
        || clean_label.len() > 64
        || !clean_label
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Ok(false);
    }
    if let Some(existing) = app.get_webview_window(clean_label) {
        let _ = existing.unminimize();
        let _ = existing.show();
        let _ = existing.set_focus();
        return Ok(true);
    }
    Ok(false)
}

#[tauri::command]
fn studio_show_main_window(app: AppHandle) -> Result<(), String> {
    show_or_recreate_main_window(&app);
    Ok(())
}

fn show_or_recreate_main_window(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
        return;
    }
    if let Some(cfg) = app.config().app.windows.first() {
        if let Ok(builder) = WebviewWindowBuilder::from_config(app, cfg) {
            if let Ok(win) = builder.build() {
                let _ = win.show();
                let _ = win.set_focus();
            }
        }
    }
}

pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_or_recreate_main_window(app);
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            let studio_data_dir = app
                .path()
                .app_data_dir()
                .unwrap_or_else(|_| std::env::temp_dir().join("com.animeow.studio.dev"));
            let appdata_roaming_dir = app
                .path()
                .data_dir()
                .unwrap_or_else(|_| studio_data_dir.parent().unwrap_or(&studio_data_dir).to_path_buf());

            std::fs::create_dir_all(&studio_data_dir)?;
            let sync_conn = init_sync_db(&studio_data_dir.join("animeow.db"))?;
            let local_conn = init_local_db(&studio_data_dir.join("desktop_local.db"))?;

            let covers_dir = studio_data_dir.join("covers");
            let media_server = tauri::async_runtime::block_on(LocalMediaServer::start(Some(covers_dir)))?;

            let initial_close_to_tray = load_desktop_settings(&BackupPaths::from_studio_root(&studio_data_dir))
                .ok()
                .flatten()
                .and_then(|v| v.get("closeToTray").and_then(|b| b.as_bool()))
                .unwrap_or(true);

            app.manage(StudioState {
                studio_data_dir,
                appdata_roaming_dir,
                sync_db: Arc::new(Mutex::new(sync_conn)),
                local_db: Arc::new(Mutex::new(local_conn)),
                media_server,
                active_companion: Mutex::new(None),
                pending_update: Mutex::new(None),
                agent_runs: Arc::new(Mutex::new(HashMap::new())),
                explicit_quit: AtomicBool::new(false),
                minimize_to_tray: AtomicBool::new(initial_close_to_tray),
            });

            let open_item = MenuItem::with_id(app, "open_studio", "打开追番喵", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit_studio", "退出追番喵", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_item, &quit_item])?;

            let mut tray_builder = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("追番喵 (后台运行中)")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open_studio" => show_or_recreate_main_window(app),
                    "quit_studio" => {
                        if let Some(st) = app.try_state::<StudioState>() {
                            st.explicit_quit.store(true, Ordering::SeqCst);
                        }
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_or_recreate_main_window(tray.app_handle());
                    }
                });

            if let Some(icon) = app.default_window_icon().cloned() {
                tray_builder = tray_builder.icon(icon);
            }
            let _tray = tray_builder.build(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            studio_bootstrap,
            curator_list,
            curator_create_item,
            curator_update_cell,
            curator_batch_status,
            curator_batch_series,
            curator_soft_delete,
            curator_export_csv,
            catalog_search_bangumi,
            catalog_fetch_bangumi_subject,
            work_update_external_binding,
            catalog_search_vndb,
            catalog_lookup_dlsite,
            catalog_search_characters,
            catalog_fetch_work_characters,
            universe_query_graph,
            universe_save_layout,
            legacy_import_copy,
            backup_inspect,
            backup_preview_diff,
            backup_restore,
            backup_export,
            vault_mount_scan,
            vault_list_files,
            vault_bind_file,
            player_open_local,
            player_open_external,
            playback_sources,
            playback_search,
            playback_episodes,
            playback_resolve,
            companion_launch_game,
            companion_status,
            companion_list_sessions,
            companion_bind_launcher,
            companion_list_launchers,
            companion_scan_external_players,
            capture_save,
            capture_list,
            capture_delete,
            work_query_references,
            tier_list_get,
            tier_list_save,
            cloud_get_config,
            cloud_set_config,
            cloud_session_status,
            cloud_session_refresh,
            cloud_login,
            cloud_register,
            cloud_device_auth,
            cloud_logout,
            cloud_backups_list,
            cloud_backups_upload,
            import_custom_cover,
            cloud_backups_download,
            cloud_backups_delete,
            cloud_publish_review,
            bangumi_oauth_start,
            bangumi_oauth_complete,
            bangumi_oauth_unbind,
            bangumi_sync_preview,
            bangumi_sync_pull,
            bangumi_sync_push_one,
            review_export_markdown,
            calendar_fetch_bangumi,
            discovery_fetch,
            trash_list,
            trash_restore,
            trash_purge,
            duplicates_list,
            duplicates_merge,
            series_auto_suggestions,
            series_manage_list,
            series_rename,
            series_merge,
            series_delete,
            series_remove_member,
            series_set_member_role,
            tags_manage_list,
            tag_rename,
            tag_merge,
            tag_delete,
            tag_purge_unused,
            tag_toggle_blocked,
            characters_manage_list,
            character_upsert,
            character_delete,
            character_link_work,
            character_unlink_work,
            character_relation_upsert,
            character_relation_delete,
            work_list_characters,
            character_set_tags,
            character_batch_delete,
            system_save_png_base64,
            curator_import_csv,
            universe_load_custom,
            universe_save_custom,
            system_open_https_url,
            system_get_autostart,
            system_set_autostart,
            system_set_close_to_tray,
            settings_get_desktop,
            settings_save_desktop,
            system_get_data_dir_info,
            system_open_data_dir,
            updater_check,
            updater_download,
            updater_install_and_restart,
            agent_list_providers,
            agent_list_profiles,
            agent_save_profile,
            agent_delete_profile,
            agent_fetch_models,
            agent_probe_profile,
            agent_get_policy,
            agent_save_policy,
            agent_list_sessions,
            agent_create_session,
            agent_update_session,
            agent_clear_session,
            agent_truncate_session_from,
            agent_delete_session,
            agent_load_session_data,
            agent_export_session_markdown,
            agent_compact_session,
            agent_send_message,
            agent_cancel_run,
            agent_fork_session,
            agent_switch_message_version,
            agent_commit_proposal,
            agent_reject_proposal,
            agent_revert_proposal,
            agent_commit_proposals_batch,
            agent_revert_proposals_batch,
            get_cover_baseline_stats,
            cleanup_orphan_covers,
            agent_list_memories,
            agent_upsert_memory,
            agent_delete_memory,
            agent_draft_memories_from_library,
            agent_list_commands,
            agent_save_command,
            agent_delete_command,
            agent_mcp_list,
            agent_mcp_save,
            agent_mcp_delete,
            agent_mcp_test,
            agent_mcp_import_json,
            agent_load_trajectory,
            agent_export_trajectory,
            agent_export_feedback_diagnostics,
            agent_read_artifact,
            agent_library_result,
            agent_export_library_result,
            agent_save_filter_snapshot,
            agent_list_verify_jobs,
            agent_get_verify_job,
            agent_run_verify_job_step,
            agent_resolve_verify_item,
            agent_create_proposal_from_verify_job,
            rehydrate_work_from_remote,
            studio_open_popout_window,
            studio_focus_popout_if_open,
            studio_show_main_window,
        ])
        .build(tauri::generate_context!())
        .expect("追番喵启动失败");

    app.run(|app_handle, event| {
        if let RunEvent::WindowEvent {
            label,
            event: tauri::WindowEvent::Destroyed,
            ..
        } = &event
        {
            if label == "main" {
                let keep_in_tray = app_handle
                    .try_state::<StudioState>()
                    .map(|s| s.minimize_to_tray.load(Ordering::SeqCst))
                    .unwrap_or(false);
                if !keep_in_tray {
                    for (win_label, win) in app_handle.webview_windows() {
                        if win_label.starts_with("popout-") {
                            let _ = win.close();
                        }
                    }
                }
            }
        }
        if let RunEvent::ExitRequested { api, code, .. } = event {
            let (explicit, keep_in_tray) = app_handle
                .try_state::<StudioState>()
                .map(|s| {
                    (
                        s.explicit_quit.load(Ordering::SeqCst),
                        s.minimize_to_tray.load(Ordering::SeqCst),
                    )
                })
                .unwrap_or((true, false));
            if code.is_none() && !explicit && keep_in_tray {
                api.prevent_exit();
            }
        }
    });
}
