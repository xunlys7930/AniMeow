import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { open, save } from '@tauri-apps/plugin-dialog'
import { version } from '../package.json'
import type {
  ActiveCompanionInfo,
  ExternalBindingRequest,
  RemoteSubjectDetail,
  AgentFetchModelsResult,
  AgentMcpServerConfig,
  AgentMcpTestResult,
  AgentMemory,
  AgentMemoryDraft,
  AgentMessage,
  AgentPolicyConfig,
  AgentProbeResult,
  AgentProfile,
  AgentProposal,
  AgentProposalApplyResult,
  AgentProviderPreset,
  AgentSession,
  AgentSlashCommand,
  AgentStreamEnvelope,
  AgentTrajectorySummary,
  AgentVerifyJobDetail,
  AgentVerifyJobSummary,
  BackupDiffEntry,
  BackupSummary,
  BangumiAuthorizeStart,
  BangumiCalendarResponse,
  BangumiDiffRow,
  BatchProposalCommitResult,
  BatchProposalRevertResult,
  CanvasCustomState,
  CellPatch,
  CharacterManageItem,
  CloudBackupRow,
  CloudEndpointConfig,
  CommunityPublishResult,
  CoverBaselineStats,
  CsvImportReport,
  CuratorItem,
  DesktopUpdaterCheckResult,
  DesktopUpdaterDownloadResult,
  DesktopUpdaterProgress,
  DiscoveryFeedRequest,
  DiscoveryPage,
  DuplicateGroup,
  SeriesManageItem,
  SeriesSuggestionGroup,
  ExternalPlayerDetection,
  LegacyImportReport,
  LocalCaptureRow,
  LocalPlaybackTicket,
  MediaCarrier,
  NewCuratorItemInput,
  OrphanCoverCleanupResult,
  PlaybackLine,
  PlaybackSource,
  PlaybackWork,
  PlaySessionRow,
  PublicCloudSessionInfo,
  RemoteCatalogHit,
  RemoteCharacterHit,
  RemoteStreamTicket,
  RestoreResult,
  StudioBootstrap,
  TagManageItem,
  TierListEntry,
  TrashItem,
  UniverseGraph,
  UniverseMode,
  UpsertCharacterInput,
  UpsertCharacterRelationInput,
  VaultFileRow,
  VaultRootRow,
  WorkCharacterBrief,
  WorkReferenceBundle,
  WorkRehydrateResult,
  SaveAgentMcpServerInput,
  SaveAgentProfileInput,
  SendAgentMessageInput
} from './types'

export const studioApi = {
  bootstrap: (): Promise<StudioBootstrap> => invoke('studio_bootstrap'),
  listCurator: (): Promise<CuratorItem[]> => invoke('curator_list'),
  createItem: (input: NewCuratorItemInput): Promise<number> =>
    invoke('curator_create_item', { input }),
  updateCell: (patch: CellPatch): Promise<void> => invoke('curator_update_cell', { patch }),
  batchStatus: (targets: [MediaCarrier, number][], status: string): Promise<number> =>
    invoke('curator_batch_status', { targets, status }),
  batchSeries: (
    targets: [MediaCarrier, number][],
    seriesName: string,
    seriesRole?: string | null
  ): Promise<number> =>
    invoke('curator_batch_series', {
      targets,
      seriesName,
      seriesRole: seriesRole ?? null
    }),
  searchBangumi: (keyword: string, kind: MediaCarrier): Promise<RemoteCatalogHit[]> =>
    invoke('catalog_search_bangumi', { keyword, kind }),
  searchRemoteCharacters: (keyword: string): Promise<RemoteCharacterHit[]> =>
    invoke('catalog_search_characters', { keyword }),
  fetchWorkCharacters: (subjectId: number): Promise<RemoteCharacterHit[]> =>
    invoke('catalog_fetch_work_characters', { subjectId }),
  queryUniverseGraph: (mode: UniverseMode): Promise<UniverseGraph> =>
    invoke('universe_query_graph', { mode }),
  saveUniverseLayout: (
    mode: UniverseMode,
    coords: Record<string, [number, number]>
  ): Promise<void> => invoke('universe_save_layout', { mode, coords }),
  importLegacyCopy: (): Promise<LegacyImportReport> => invoke('legacy_import_copy'),
  inspectBackup: (zipPath: string): Promise<BackupSummary> => invoke('backup_inspect', { zipPath }),
  previewBackupDiff: (zipPath: string): Promise<BackupDiffEntry[]> =>
    invoke('backup_preview_diff', { zipPath }),
  restoreBackup: (
    zipPath: string,
    strategy: 'REPLACE' | 'MERGE_KEEP_LOCAL'
  ): Promise<RestoreResult> => invoke('backup_restore', { zipPath, strategy }),
  exportBackup: (outputPath: string, includeCovers: boolean): Promise<BackupSummary> =>
    invoke('backup_export', { outputPath, includeCovers }),
  mountScanVault: (dirPath: string, mediaKind: string): Promise<VaultRootRow> =>
    invoke('vault_mount_scan', { dirPath, mediaKind }),
  listVaultFiles: (boundKind?: string, boundWorkId?: number): Promise<VaultFileRow[]> =>
    invoke('vault_list_files', {
      boundKind: boundKind ?? null,
      boundWorkId: boundWorkId ?? null
    }),
  bindVaultFile: (
    vaultFileId: number,
    boundKind: string | null,
    boundWorkId: number | null
  ): Promise<void> =>
    invoke('vault_bind_file', { vaultFileId, boundKind, boundWorkId }),
  openLocalPlayer: (
    filePath: string,
    subtitlePath?: string | null,
    scriptPath?: string | null
  ): Promise<LocalPlaybackTicket> =>
    invoke('player_open_local', {
      filePath,
      subtitlePath: subtitlePath ?? null,
      scriptPath: scriptPath ?? null
    }),
  openExternalPlayer: (filePath: string, revealInFolder = false): Promise<void> =>
    invoke('player_open_external', { filePath, revealInFolder }),
  launchCompanionGame: (
    workId: number,
    workTitle: string,
    exePath: string
  ): Promise<ActiveCompanionInfo> =>
    invoke('companion_launch_game', { workId, workTitle, exePath }),
  getCompanionStatus: (): Promise<ActiveCompanionInfo | null> => invoke('companion_status'),
  listPlaySessions: (): Promise<PlaySessionRow[]> => invoke('companion_list_sessions'),
  exportMarkdownReview: (outputPath: string, content: string): Promise<void> =>
    invoke('review_export_markdown', { outputPath, content }),
  softDeleteItem: (kind: MediaCarrier, id: number): Promise<void> =>
    invoke('curator_soft_delete', { kind, id }),
  exportCuratorCsv: (outputPath: string): Promise<number> =>
    invoke('curator_export_csv', { outputPath }),
  fetchBangumiSubject: (input: string): Promise<RemoteSubjectDetail> =>
    invoke('catalog_fetch_bangumi_subject', { input }),
  updateWorkExternalBinding: (request: ExternalBindingRequest, candidate?: RemoteCatalogHit): Promise<string | null> =>
    invoke('work_update_external_binding', { request, candidate }),
  searchVndbGames: (keyword: string, direct?: boolean): Promise<RemoteCatalogHit[]> =>
    invoke('catalog_search_vndb', { keyword, direct }),
  lookupDlsiteAudio: (input: string): Promise<RemoteCatalogHit[]> =>
    invoke('catalog_lookup_dlsite', { input }),
  saveCapture: (input: {
    workKind: MediaCarrier
    workId: number
    workTitle: string
    note: string
    episodeHint?: number | null
    imageBase64?: string | null
    appendToMemo: boolean
  }): Promise<LocalCaptureRow> =>
    invoke('capture_save', {
      workKind: input.workKind,
      workId: input.workId,
      workTitle: input.workTitle,
      note: input.note,
      episodeHint: input.episodeHint ?? null,
      imageBase64: input.imageBase64 ?? null,
      appendToMemo: input.appendToMemo
    }),
  listCaptures: (workKind?: MediaCarrier, workId?: number): Promise<LocalCaptureRow[]> =>
    invoke('capture_list', { workKind: workKind ?? null, workId: workId ?? null }),
  deleteCapture: (captureId: number): Promise<void> => invoke('capture_delete', { captureId }),
  queryWorkReferences: (workKind: MediaCarrier, workId: number): Promise<WorkReferenceBundle> =>
    invoke('work_query_references', { workKind, workId }),
  bindGameLauncher: (workId: number, exePath: string): Promise<void> =>
    invoke('companion_bind_launcher', { workId, exePath }),
  listGameLaunchers: (): Promise<Record<number, string>> => invoke('companion_list_launchers'),
  scanExternalPlayers: (): Promise<ExternalPlayerDetection[]> =>
    invoke('companion_scan_external_players'),
  getTierList: (): Promise<TierListEntry[]> => invoke('tier_list_get'),
  saveTierEntry: (
    workKind: MediaCarrier,
    workId: number,
    tier: TierListEntry['tier'] | null,
    sortOrder = 0
  ): Promise<void> => invoke('tier_list_save', { workKind, workId, tier, sortOrder }),
  getCloudConfig: (): Promise<CloudEndpointConfig> => invoke('cloud_get_config'),
  setCloudConfig: (apiBase: string): Promise<CloudEndpointConfig> =>
    invoke('cloud_set_config', { apiBase }),
  getCloudSession: (): Promise<PublicCloudSessionInfo> => invoke('cloud_session_status'),
  refreshCloudSession: (): Promise<PublicCloudSessionInfo> => invoke('cloud_session_refresh'),
  cloudLogin: (username: string, password: string): Promise<PublicCloudSessionInfo> =>
    invoke('cloud_login', { username, password }),
  cloudRegister: (username: string, password: string): Promise<PublicCloudSessionInfo> =>
    invoke('cloud_register', { username, password }),
  cloudDeviceLogin: (): Promise<PublicCloudSessionInfo> => invoke('cloud_device_auth'),
  cloudLogout: (): Promise<void> => invoke('cloud_logout'),
  listCloudBackups: (): Promise<CloudBackupRow[]> => invoke('cloud_backups_list'),
  uploadCloudBackup: (includeCovers: boolean): Promise<CloudBackupRow> =>
    invoke('cloud_backups_upload', { includeCovers }),
  importCustomCover: (sourcePath: string): Promise<string> =>
    invoke('import_custom_cover', { sourcePath }),
  pickCoverImage: async (): Promise<string | null> => {
    const selected = await open({
      multiple: false,
      title: '选择自定义封面图片（JPG / PNG / WebP / GIF）',
      filters: [{ name: '图片文件', extensions: ['jpg', 'jpeg', 'png', 'webp', 'gif'] }]
    })
    return typeof selected === 'string' ? selected : null
  },
  downloadCloudBackup: (backupId: number, expectedHash?: string | null): Promise<string> =>
    invoke('cloud_backups_download', { backupId, expectedHash: expectedHash ?? null }),
  deleteCloudBackup: (backupId: number): Promise<void> =>
    invoke('cloud_backups_delete', { backupId }),
  publishCommunityReview: (input: {
    headline: string
    workTitle: string
    carrierLabel: string
    personalScore: number | null
    spoilerWarning: boolean
    reviewMarkdown: string
  }): Promise<CommunityPublishResult> => invoke('cloud_publish_review', input),
  startBangumiOAuth: (): Promise<BangumiAuthorizeStart> => invoke('bangumi_oauth_start'),
  completeBangumiOAuth: (
    callbackOrTicket: string,
    expectedState?: string | null
  ): Promise<PublicCloudSessionInfo> =>
    invoke('bangumi_oauth_complete', {
      callbackOrTicket,
      expectedState: expectedState ?? null
    }),
  unbindBangumiOAuth: (): Promise<PublicCloudSessionInfo> => invoke('bangumi_oauth_unbind'),
  previewBangumiSync: (): Promise<BangumiDiffRow[]> => invoke('bangumi_sync_preview'),
  pullBangumiSync: (rows: BangumiDiffRow[], preferRemoteOnConflict: boolean): Promise<number> =>
    invoke('bangumi_sync_pull', { rows, preferRemoteOnConflict }),
  pushOneBangumiAnime: (
    subjectId: number,
    localStatus: string,
    localWatchedEps: number,
    localRating100?: number | null
  ): Promise<void> =>
    invoke('bangumi_sync_push_one', {
      subjectId,
      localStatus,
      localWatchedEps,
      localRating100: localRating100 ?? null
    }),
  openHttpsUrl: (url: string): Promise<void> => invoke('system_open_https_url', { url }),
  getAutostart: (): Promise<boolean> => invoke('system_get_autostart'),
  setAutostart: (enabled: boolean): Promise<boolean> =>
    invoke('system_set_autostart', { enabled }),
  setCloseToTray: (enabled: boolean): Promise<void> =>
    invoke('system_set_close_to_tray', { enabled }),
  getDesktopSettings: (): Promise<Record<string, unknown> | null> => invoke('settings_get_desktop'),
  saveDesktopSettings: (settings: Record<string, unknown>): Promise<void> =>
    invoke('settings_save_desktop', { settings }),
  getDataDirInfo: (): Promise<{ dataDir: string; appVersion: string }> =>
    invoke('system_get_data_dir_info'),
  openDataDir: (): Promise<void> => invoke('system_open_data_dir'),
  checkSignedDesktopUpdate: (): Promise<DesktopUpdaterCheckResult> => invoke('updater_check'),
  downloadSignedDesktopUpdate: (): Promise<DesktopUpdaterDownloadResult> =>
    invoke('updater_download'),
  installAndRestartDesktopUpdate: (): Promise<void> => invoke('updater_install_and_restart'),
  onDesktopUpdaterProgress: (
    handler: (payload: DesktopUpdaterProgress) => void
  ): Promise<UnlistenFn> =>
    listen<DesktopUpdaterProgress>('updater://progress', (event) => handler(event.payload)),
  pickSaveCsv: async (defaultName: string): Promise<string | null> => {
    return save({
      title: '导出策展工作表 (.csv)',
      defaultPath: defaultName,
      filters: [{ name: 'CSV 电子表格', extensions: ['csv'] }]
    })
  },

  pickDirectory: async (title: string): Promise<string | null> => {
    const selected = await open({ directory: true, multiple: false, title })
    return typeof selected === 'string' ? selected : null
  },
  pickExecutable: async (title: string): Promise<string | null> => {
    const selected = await open({
      multiple: false,
      title,
      filters: [{ name: 'Windows 可执行程序', extensions: ['exe', 'bat', 'lnk'] }]
    })
    return typeof selected === 'string' ? selected : null
  },
  pickBackupZip: async (): Promise<string | null> => {
    const selected = await open({
      multiple: false,
      title: '选择 AniMeow 原生备份包 (.animeow.zip)',
      filters: [{ name: 'AniMeow Backup', extensions: ['zip'] }]
    })
    return typeof selected === 'string' ? selected : null
  },
  pickSaveZip: async (defaultName: string): Promise<string | null> => {
    return save({
      title: '导出 AniMeow 原生备份包',
      defaultPath: defaultName,
      filters: [{ name: 'AniMeow Backup', extensions: ['zip'] }]
    })
  },
  pickSaveMarkdown: async (defaultName: string): Promise<string | null> => {
    return save({
      title: '导出 Markdown 漫评手稿',
      defaultPath: defaultName,
      filters: [{ name: 'Markdown 文档', extensions: ['md'] }]
    })
  },
  playbackSources: () => invoke<PlaybackSource[]>('playback_sources'),
  playbackSearch: (source: string, query: string) =>
    invoke<PlaybackWork[]>('playback_search', { source, query }),
  playbackEpisodes: (source: string, workId: string) =>
    invoke<PlaybackLine[]>('playback_episodes', { source, workId }),
  playbackResolve: (source: string, workId: string, episodeId: string) =>
    invoke<RemoteStreamTicket>('playback_resolve', { source, workId, episodeId }),
  fetchBangumiCalendar: () => invoke<BangumiCalendarResponse>('calendar_fetch_bangumi'),
  fetchDiscoveryFeed: (req: DiscoveryFeedRequest) =>
    invoke<DiscoveryPage>('discovery_fetch', { req }),
  listTrash: () => invoke<TrashItem[]>('trash_list'),
  restoreTrash: (items: [MediaCarrier, number][]) =>
    invoke<number>('trash_restore', { items }),
  purgeTrash: (items: [MediaCarrier, number][]) =>
    invoke<number>('trash_purge', { items }),
  listDuplicates: () => invoke<DuplicateGroup[]>('duplicates_list'),
  mergeDuplicate: (kind: MediaCarrier, keepId: number, dropIds: number[]) =>
    invoke<number>('duplicates_merge', { kind, keepId, dropIds }),
  listSeriesSuggestions: () =>
    invoke<SeriesSuggestionGroup[]>('series_auto_suggestions'),
  listSeriesManage: () => invoke<SeriesManageItem[]>('series_manage_list'),
  renameSeries: (seriesId: number, newName: string) =>
    invoke<void>('series_rename', { seriesId, newName }),
  mergeSeries: (sourceSeriesId: number, targetSeriesId: number) =>
    invoke<number>('series_merge', { sourceSeriesId, targetSeriesId }),
  deleteSeries: (seriesId: number) => invoke<void>('series_delete', { seriesId }),
  removeMemberFromSeries: (seriesId: number, kind: MediaCarrier, workId: number) =>
    invoke<void>('series_remove_member', { seriesId, kind, workId }),
  setSeriesMemberRole: (seriesId: number, kind: MediaCarrier, workId: number, role: string) =>
    invoke<void>('series_set_member_role', { seriesId, kind, workId, role }),
  listTagsManage: () => invoke<TagManageItem[]>('tags_manage_list'),
  renameTag: (tagId: number, newName: string) =>
    invoke<void>('tag_rename', { tagId, newName }),
  mergeTags: (sourceTagId: number, targetTagId: number) =>
    invoke<number>('tag_merge', { sourceTagId, targetTagId }),
  deleteTag: (tagId: number) => invoke<void>('tag_delete', { tagId }),
  purgeUnusedTags: () => invoke<number>('tag_purge_unused'),
  toggleTagBlocked: (tagId: number, blocked: boolean) =>
    invoke<void>('tag_toggle_blocked', { tagId, blocked }),
  listCharactersManage: () => invoke<CharacterManageItem[]>('characters_manage_list'),
  upsertCharacter: (input: UpsertCharacterInput) =>
    invoke<number>('character_upsert', { input }),
  deleteCharacter: (characterId: number) =>
    invoke<void>('character_delete', { characterId }),
  linkCharacterWork: (
    characterId: number,
    animeId: number,
    roleName?: string | null,
    voiceActor?: string | null,
    preserveMissingVoiceActor = false
  ) =>
    invoke<void>('character_link_work', {
      characterId,
      animeId,
      roleName: roleName ?? null,
      voiceActor: voiceActor ?? null,
      preserveMissingVoiceActor
    }),
  unlinkCharacterWork: (characterId: number, animeId: number) =>
    invoke<void>('character_unlink_work', { characterId, animeId }),
  upsertCharacterRelation: (input: UpsertCharacterRelationInput) =>
    invoke<number>('character_relation_upsert', { input }),
  deleteCharacterRelation: (relationId: number) =>
    invoke<void>('character_relation_delete', { relationId }),
  listWorkCharacters: (animeId: number) =>
    invoke<WorkCharacterBrief[]>('work_list_characters', { animeId }),
  setCharacterTags: (characterId: number, tags: string[]) =>
    invoke<void>('character_set_tags', { characterId, tags }),
  batchDeleteCharacters: (characterIds: number[]) =>
    invoke<number>('character_batch_delete', { characterIds }),
  pickSavePng: async (defaultName: string): Promise<string | null> => {
    return save({
      title: '导出高清 PNG 图片',
      defaultPath: defaultName,
      filters: [{ name: 'PNG 图片', extensions: ['png'] }]
    })
  },
  savePngBase64: (outputPath: string, dataUrl: string) =>
    invoke<void>('system_save_png_base64', { outputPath, dataUrl }),
  importCsv: (csvPath: string) =>
    invoke<CsvImportReport>('curator_import_csv', { csvPath }),
  pickCsvFile: async (): Promise<string | null> => {
    const selected = await open({
      multiple: false,
      title: '选择要批量导入的 CSV 表格文件',
      filters: [{ name: 'CSV 表格', extensions: ['csv'] }]
    })
    return typeof selected === 'string' ? selected : null
  },
  loadCanvasCustom: (mode: UniverseMode) =>
    invoke<CanvasCustomState>('universe_load_custom', { mode }),
  saveCanvasCustom: (mode: UniverseMode, custom: CanvasCustomState) =>
    invoke<void>('universe_save_custom', { mode, custom }),

  // 桌面端独有 AI 智能体（AniMeow Agent Harness）
  agentListProviders: (): Promise<AgentProviderPreset[]> => invoke('agent_list_providers'),
  agentListProfiles: (): Promise<AgentProfile[]> => invoke('agent_list_profiles'),
  agentSaveProfile: (input: SaveAgentProfileInput): Promise<AgentProfile> =>
    invoke('agent_save_profile', { input }),
  agentDeleteProfile: (profileId: string): Promise<void> =>
    invoke('agent_delete_profile', { profileId }),
  agentFetchModels: (
    baseUrl: string,
    apiKey?: string | null,
    profileId?: string | null
  ): Promise<AgentFetchModelsResult> =>
    invoke('agent_fetch_models', {
      baseUrl,
      apiKey: apiKey ?? null,
      profileId: profileId ?? null
    }),
  agentProbeProfile: (
    baseUrl: string,
    model: string,
    apiKey?: string | null,
    profileId?: string | null
  ): Promise<AgentProbeResult> =>
    invoke('agent_probe_profile', {
      baseUrl,
      model,
      apiKey: apiKey ?? null,
      profileId: profileId ?? null
    }),
  agentGetPolicy: (): Promise<AgentPolicyConfig> => invoke('agent_get_policy'),
  agentSavePolicy: (config: AgentPolicyConfig): Promise<AgentPolicyConfig> =>
    invoke('agent_save_policy', { config }),
  agentListSessions: (
    includeArchived = false,
    keyword?: string | null
  ): Promise<AgentSession[]> =>
    invoke('agent_list_sessions', {
      includeArchived,
      keyword: keyword?.trim() || null
    }),
  agentCreateSession: (
    title?: string | null,
    profileId?: string | null,
    personaId?: string | null
  ): Promise<AgentSession> =>
    invoke('agent_create_session', {
      title: title ?? null,
      profileId: profileId ?? null,
      personaId: personaId ?? null
    }),
  agentUpdateSession: (params: {
    sessionId: string
    title?: string | null
    pinned?: boolean | null
    archived?: boolean | null
    personaId?: string | null
    profileId?: string | null
    rollingSummary?: string | null
  }): Promise<AgentSession> =>
    invoke('agent_update_session', {
      sessionId: params.sessionId,
      title: params.title ?? null,
      pinned: params.pinned ?? null,
      archived: params.archived ?? null,
      personaId: params.personaId ?? null,
      profileId: params.profileId ?? null,
      rollingSummary: params.rollingSummary === undefined ? null : params.rollingSummary
    }),
  agentClearSession: (sessionId: string): Promise<void> =>
    invoke('agent_clear_session', { sessionId }),
  agentTruncateSessionFrom: (sessionId: string, messageId: string): Promise<number> =>
    invoke('agent_truncate_session_from', { sessionId, messageId }),
  agentDeleteSession: (sessionId: string): Promise<void> =>
    invoke('agent_delete_session', { sessionId }),
  agentLoadSessionData: (
    sessionId: string
  ): Promise<{ messages: AgentMessage[]; proposals: AgentProposal[] }> =>
    invoke('agent_load_session_data', { sessionId }),
  agentExportSessionMarkdown: async (
    sessionId: string,
    defaultFileName: string
  ): Promise<string | null> => {
    const outPath = await save({
      defaultPath: `${defaultFileName.replace(/[\\/:*?"<>|]/g, '_')}.md`,
      filters: [{ name: 'Markdown 对话记录', extensions: ['md'] }]
    })
    if (!outPath) return null
    await invoke<string>('agent_export_session_markdown', {
      sessionId,
      outputPath: outPath
    })
    return outPath
  },
  agentCompactSession: (
    sessionId: string,
    profileId?: string | null,
    keepRecentTurns?: number | null
  ): Promise<{ compacted: boolean; summary?: string | null; compactedMessages: number }> =>
    invoke('agent_compact_session', {
      sessionId,
      profileId: profileId ?? null,
      keepRecentTurns: keepRecentTurns ?? null
    }),
  agentSendMessage: (input: SendAgentMessageInput): Promise<string> =>
    invoke('agent_send_message', { input }),
  agentCancelRun: (runId: string): Promise<boolean> =>
    invoke('agent_cancel_run', { runId }),
  agentForkSession: (
    sourceSessionId: string,
    upToMessageId?: string | null,
    title?: string | null
  ): Promise<AgentSession> =>
    invoke('agent_fork_session', {
      sourceSessionId,
      upToMessageId: upToMessageId ?? null,
      title: title ?? null
    }),
  agentSwitchMessageVersion: (
    sessionId: string,
    messageId: string,
    versionIdx: number
  ): Promise<AgentMessage> =>
    invoke('agent_switch_message_version', {
      sessionId,
      messageId,
      versionIdx
    }),
  agentCommitProposal: (
    proposalId: string,
    selectedIndices?: number[] | null
  ): Promise<AgentProposalApplyResult> =>
    invoke('agent_commit_proposal', {
      proposalId,
      selectedIndices: selectedIndices ?? null
    }),
  agentRejectProposal: (proposalId: string): Promise<AgentProposal> =>
    invoke('agent_reject_proposal', { proposalId }),
  agentRevertProposal: (proposalId: string): Promise<AgentProposalApplyResult> =>
    invoke('agent_revert_proposal', { proposalId }),
  agentCommitProposalsBatch: (
    proposalIds: string[],
    safeOnly = false
  ): Promise<BatchProposalCommitResult> =>
    invoke('agent_commit_proposals_batch', { proposalIds, safeOnly }),
  agentRevertProposalsBatch: (batchGroupId: string): Promise<BatchProposalRevertResult> =>
    invoke('agent_revert_proposals_batch', { batchGroupId }),
  getCoverBaselineStats: (): Promise<CoverBaselineStats> =>
    invoke('get_cover_baseline_stats'),
  cleanupOrphanCovers: (dryRun = true): Promise<OrphanCoverCleanupResult> =>
    invoke('cleanup_orphan_covers', { dryRun }),
  agentListMemories: (): Promise<AgentMemory[]> => invoke('agent_list_memories'),
  agentUpsertMemory: (
    category: string,
    content: string,
    enabled = true,
    id?: string | null,
    tier: 'core' | 'archive' = 'core'
  ): Promise<AgentMemory> =>
    invoke('agent_upsert_memory', {
      id: id ?? null,
      category,
      tier,
      content,
      enabled
    }),
  agentDeleteMemory: (id: string): Promise<void> => invoke('agent_delete_memory', { id }),
  agentDraftMemoriesFromLibrary: (): Promise<AgentMemoryDraft[]> =>
    invoke('agent_draft_memories_from_library'),
  agentListCommands: (): Promise<AgentSlashCommand[]> => invoke('agent_list_commands'),
  agentSaveCommand: (params: {
    id?: string | null
    command: string
    label: string
    description: string
    promptTemplate: string
    skillGuide?: string | null
    toolScope?: string[] | null
  }): Promise<AgentSlashCommand> =>
    invoke('agent_save_command', {
      id: params.id ?? null,
      command: params.command,
      label: params.label,
      description: params.description,
      promptTemplate: params.promptTemplate,
      skillGuide: params.skillGuide ?? null,
      toolScope: params.toolScope ?? null
    }),
  agentDeleteCommand: (id: string): Promise<void> => invoke('agent_delete_command', { id }),
  agentMcpList: (): Promise<AgentMcpServerConfig[]> => invoke('agent_mcp_list'),
  agentMcpSave: (input: SaveAgentMcpServerInput): Promise<AgentMcpServerConfig> =>
    invoke('agent_mcp_save', { input }),
  agentMcpDelete: (serverId: string): Promise<void> => invoke('agent_mcp_delete', { serverId }),
  agentMcpTest: (serverId: string): Promise<AgentMcpTestResult> =>
    invoke('agent_mcp_test', { serverId }),
  agentMcpImportJson: (rawJson: string): Promise<AgentMcpServerConfig[]> =>
    invoke('agent_mcp_import_json', { rawJson }),
  agentLoadTrajectory: (sessionId: string): Promise<AgentTrajectorySummary> =>
    invoke('agent_load_trajectory', { sessionId }),
  agentExportTrajectoryText: (sessionId: string, format: 'jsonl' | 'markdown'): Promise<string> =>
    invoke('agent_export_trajectory', { sessionId, format, outputPath: null }),
  agentExportTrajectoryFile: async (
    sessionId: string,
    defaultBaseName: string,
    format: 'jsonl' | 'markdown'
  ): Promise<string | null> => {
    const ext = format === 'jsonl' ? 'jsonl' : 'md'
    const outPath = await save({
      defaultPath: `${defaultBaseName.replace(/[\\/:*?"<>|]/g, '_')}_trajectory.${ext}`,
      filters: [
        format === 'jsonl'
          ? { name: 'AI 运行记录原始文件 (JSONL)', extensions: ['jsonl'] }
          : { name: 'AI 运行分析报告 (Markdown)', extensions: ['md'] }
      ]
    })
    if (!outPath) return null
    await invoke<string>('agent_export_trajectory', {
      sessionId,
      format,
      outputPath: outPath
    })
    return outPath
  },
  agentLibraryResult: (resultId: string, offset: number): Promise<{
    total: number
    items: { id: number; kind: string; title: string; status: string; progress: string }[]
  }> => invoke('agent_library_result', { resultId, offset }),
  agentExportLibraryResult: (resultId: string, outputPath: string): Promise<number> =>
    invoke('agent_export_library_result', { resultId, outputPath }),
  agentReadArtifact: (
    artifactId: string,
    offset = 0,
    limit = 4500
  ): Promise<{
    artifactId: string
    toolName: string
    totalChars: number
    offset: number
    returnedChars: number
    hasMore: boolean
    nextOffset: number | null
    content: string
  }> => invoke('agent_read_artifact', { artifactId, offset, limit }),
  rehydrateWorkFromRemote: (kind: MediaCarrier, workId: number): Promise<WorkRehydrateResult> =>
    invoke('rehydrate_work_from_remote', { kind, workId }),
  agentSaveFilterSnapshot: (
    sessionId: string,
    items: CuratorItem[],
    label: string
  ): Promise<string> => invoke('agent_save_filter_snapshot', { sessionId, items, label }),
  agentExportFeedbackDiagnostics: async (
    sessionId: string,
    defaultBaseName: string
  ): Promise<string | null> => {
    const outPath = await save({
      defaultPath: `${defaultBaseName.replace(/[\\/:*?"<>|]/g, '_')}_feedback_diag.json`,
      filters: [{ name: '脱敏反馈诊断报告 (JSON)', extensions: ['json'] }]
    })
    if (!outPath) return null
    await invoke<string>('agent_export_feedback_diagnostics', {
      sessionId,
      outputPath: outPath
    })
    return outPath
  },
  agentListVerifyJobs: (sessionId: string): Promise<AgentVerifyJobSummary[]> =>
    invoke('agent_list_verify_jobs', { sessionId }),
  agentGetVerifyJob: (jobId: string): Promise<AgentVerifyJobDetail | null> =>
    invoke('agent_get_verify_job', { jobId }),
  agentRunVerifyJobStep: (
    jobId: string,
    batchSize = 20,
    retryFailed = false
  ): Promise<Record<string, unknown>> =>
    invoke('agent_run_verify_job_step', { jobId, batchSize, retryFailed }),
  agentResolveVerifyItem: (params: {
    jobId: string
    kind: string
    workId: number
    decision: 'select' | 'reject_all' | 'defer' | 'clear_rejection'
    externalId?: string | null
    originalTitle?: string | null
  }): Promise<AgentVerifyJobDetail> =>
    invoke('agent_resolve_verify_item', {
      jobId: params.jobId,
      kind: params.kind,
      workId: params.workId,
      decision: params.decision,
      externalId: params.externalId ?? null,
      originalTitle: params.originalTitle ?? null
    }),
  agentCreateProposalFromVerifyJob: (
    sessionId: string,
    jobId: string
  ): Promise<{
    proposal?: AgentProposal | null
    validCount: number
    conflicts: unknown[]
    skipped: unknown[]
  }> => invoke('agent_create_proposal_from_verify_job', { sessionId, jobId }),
  onAgentStream: (handler: (env: AgentStreamEnvelope) => void): Promise<UnlistenFn> =>
    listen<AgentStreamEnvelope>('agent://stream', (ev) => handler(ev.payload)),
  openPopoutWindow: (params: {
    label: string
    title: string
    query: string
    width?: number
    height?: number
  }): Promise<void> =>
    invoke('studio_open_popout_window', {
      label: params.label,
      title: params.title,
      query: params.query,
      width: params.width ?? null,
      height: params.height ?? null
    }),
  focusPopoutIfOpen: (label: string): Promise<boolean> =>
    invoke('studio_focus_popout_if_open', { label }),
  showMainWindow: (): Promise<void> => invoke('studio_show_main_window')
}

export const STUDIO_DESKTOP_VERSION = version

/**
 * 生成封面加载候选地址链：
 * 1. 将 `http(s)://lain.bgm.tv/...` 优先切到镜像 `https://img-bgm.xunlys.top/...`，失败再回退官方 HTTPS；
 * 2. 其余 `http://` 封面优先尝试 HTTPS，再回退原地址；
 * 3. `animeow-cover://` 映射到本机媒体服务 `/cover/*`。
 */
export function resolveCoverCandidates(coverUrl: string, mediaBaseUrl: string): string[] {
  const raw = (coverUrl || '').trim()
  if (!raw) return []

  if (raw.startsWith('animeow-cover://')) {
    if (!mediaBaseUrl) return []
    const rel = raw.slice('animeow-cover://'.length).replace(/^\/+/, '')
    if (!rel) return []
    const encoded = rel
      .split('/')
      .filter((seg) => seg.length > 0)
      .map((seg) => encodeURIComponent(seg))
      .join('/')
    return [`${mediaBaseUrl}/cover/${encoded}`]
  }

  // 兼容历史上从手机端/旧版桌面残留的 file://.../covers/... 本地路径
  if (/^file:\/\//i.test(raw) || /^[a-zA-Z]:[\\/]/.test(raw) || raw.startsWith('/')) {
    const normalized = raw.replace(/^file:\/\/\/?/i, '').replace(/\\/g, '/')
    const markerIdx = normalized.toLowerCase().lastIndexOf('/covers/')
    if (markerIdx >= 0 && mediaBaseUrl) {
      const rel = normalized.slice(markerIdx + '/covers/'.length).replace(/^\/+/, '')
      if (rel) {
        const encoded = rel
          .split('/')
          .filter((seg) => seg.length > 0)
          .map((seg) => encodeURIComponent(seg))
          .join('/')
        return [`${mediaBaseUrl}/cover/${encoded}`]
      }
    }
    return []
  }

  const bgmMatch = raw.match(/^https?:\/\/lain\.bgm\.tv(\/.*)$/i)
  if (bgmMatch) {
    const path = bgmMatch[1]
    return [
      `https://img-bgm.xunlys.top${path}`,
      `https://lain.bgm.tv${path}`,
    ]
  }

  const mirrorMatch = raw.match(/^https?:\/\/img-bgm\.xunlys\.top(\/.*)$/i)
  if (mirrorMatch) {
    const path = mirrorMatch[1]
    return [
      `https://img-bgm.xunlys.top${path}`,
      `https://lain.bgm.tv${path}`,
    ]
  }

  if (raw.startsWith('https://')) {
    return [raw]
  }

  if (raw.startsWith('http://')) {
    return [`https://${raw.slice('http://'.length)}`, raw]
  }

  return []
}

export function resolveCoverSrc(coverUrl: string, mediaBaseUrl: string): string | null {
  return resolveCoverCandidates(coverUrl, mediaBaseUrl)[0] ?? null
}

export function resolveCaptureSrc(
  imageRelPath: string | null | undefined,
  mediaBaseUrl: string
): string | null {
  if (!imageRelPath || !mediaBaseUrl) return null
  const clean = imageRelPath.replace(/^\/+/, '')
  if (!clean) return null
  return `${mediaBaseUrl}/capture/${encodeURI(clean)}`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

export type BookSubkind = 'novel' | 'manga' | 'other'

export function classifyBookSubkind(
  item: Pick<CuratorItem, 'kind' | 'mediaFormat' | 'tags'>
): BookSubkind {
  if (item.kind !== 'book') return 'other'
  const fmt = (item.mediaFormat ?? '').trim().toLowerCase()
  if (fmt && fmt !== 'null') {
    if (/(漫画|manga|comic|条漫|四格)/i.test(fmt)) return 'manga'
    if (/(轻小说|小说|novel|轻文学)/i.test(fmt)) return 'novel'
  }
  const tags = item.tags ?? []
  const hasMangaTag = tags.some((raw) => {
    const t = raw.trim().toLowerCase()
    if (!t || /(漫画改|漫改)/.test(t)) return false
    return t === '漫画' || t === 'manga' || t === 'comic' || t.includes('漫画') || t.includes('四格') || t.includes('条漫')
  })
  const hasNovelTag = tags.some((raw) => {
    const t = raw.trim().toLowerCase()
    if (!t || /(小说改|轻改|轻小说改)/.test(t)) return false
    return (
      t === '小说' ||
      t === '轻小说' ||
      t === 'novel' ||
      t === 'web' ||
      t.includes('轻小说') ||
      t.includes('小说') ||
      t.includes('轻文学')
    )
  })
  if (hasNovelTag && !hasMangaTag) return 'novel'
  if (hasMangaTag && !hasNovelTag) return 'manga'
  if (hasNovelTag) return 'novel'
  return 'other'
}

export function formatCarrierLabel(kind: MediaCarrier): string {
  switch (kind) {
    case 'anime':
      return '动画'
    case 'book':
      return '书籍'
    case 'game':
      return '游戏'
    case 'audio':
      return '音声'
  }
}

export function formatItemCarrierLabel(
  item: Pick<CuratorItem, 'kind' | 'mediaFormat' | 'tags'>
): string {
  if (item.kind === 'book') {
    const sub = classifyBookSubkind(item)
    if (sub === 'novel') return '小说'
    if (sub === 'manga') return '漫画'
    return '书籍'
  }
  return formatCarrierLabel(item.kind)
}

export function formatArchiveCode(item: CuratorItem): string {
  if (item.kind === 'audio') {
    const rj = item.tags.find((t) => /^RJ\d+/i.test(t))
    if (rj) return rj.toUpperCase()
    return `AUD-${String(item.id).padStart(3, '0')}`
  }
  const prefix = item.kind === 'anime' ? 'ANM' : item.kind === 'book' ? 'BOK' : 'GAM'
  return `${prefix}-${String(item.id).padStart(3, '0')}`
}

export function spinePaletteColor(item: CuratorItem): string {
  const palette = ['#3d6950', '#8c3b32', '#2f5978', '#6b5344', '#5c4d73', '#7a6332']
  return palette[(item.id + item.title.length) % palette.length]
}

export type StatusLane = 'active' | 'done' | 'planned' | 'hold'

const ACTIVE_STATUS_SET = new Set([
  '在看',
  '在读',
  '在玩',
  '在听',
  '追番',
  '追更',
  '观看中',
  '游玩中',
  '收听中',
  '进行中'
])
const DONE_STATUS_SET = new Set([
  '看完',
  '已看',
  '读完',
  '已读',
  '已玩',
  '已听',
  '通关',
  '已完成',
  '完结'
])
const PLANNED_STATUS_SET = new Set([
  '想看',
  '想读',
  '未看',
  '未读',
  '待看',
  '想玩',
  '想听',
  '计划',
  '计划看',
  '愿单'
])
const HOLD_STATUS_SET = new Set(['搁置', '暂搁', '暂停', '弃坑', '抛弃', '已弃'])

export function classifyStatusLane(rawStatus: string | null | undefined): StatusLane {
  const s = (rawStatus ?? '').trim()
  if (ACTIVE_STATUS_SET.has(s)) return 'active'
  if (DONE_STATUS_SET.has(s)) return 'done'
  if (PLANNED_STATUS_SET.has(s)) return 'planned'
  if (HOLD_STATUS_SET.has(s)) return 'hold'
  if (s.includes('想') || s.includes('未') || s.includes('待') || s.includes('计划')) return 'planned'
  if (s.includes('在') || s.includes('追') || s.includes('中')) return 'active'
  if (s.includes('完') || s.includes('已') || s.includes('通关')) return 'done'
  return 'hold'
}

export function statusOptionsFor(kind: MediaCarrier, currentStatus?: string): string[] {
  let base: string[]
  switch (kind) {
    case 'anime':
    case 'book':
      base = ['在看', '看完', '想看', '未看', '搁置', '弃坑']
      break
    case 'game':
      base = ['想玩', '在玩', '已玩', '搁置', '弃坑']
      break
    case 'audio':
      base = ['想听', '在听', '已听', '搁置', '弃坑']
      break
  }
  const cur = currentStatus?.trim()
  if (cur && !base.includes(cur)) {
    return [cur, ...base]
  }
  return base
}
