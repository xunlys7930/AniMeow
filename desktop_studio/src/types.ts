export type MediaCarrier = 'anime' | 'book' | 'game' | 'audio'

export type StudioThemeId = 'bunkobon' | 'sakuga' | 'halide' | 'cassette' | 'hinoki'

export type CuratorSubView = 'table' | 'kanban' | 'shelf'

export type WorkspaceTab =
  | 'curator'
  | 'calendar'
  | 'player'
  | 'companion'
  | 'universe'
  | 'vault'
  | 'yearbook'

export type DesktopUpscaleMode = 'off' | 'spatial' | 'ai-s' | 'ai-m'

export type DesktopRestoreStrategy = 'MERGE_KEEP_LOCAL' | 'REPLACE'

export type CoverStoragePolicy = 'remote' | 'local' | 'auto'

export type DesktopShortcutAction =
  | 'commandPalette'
  | 'toggleAgent'
  | 'settings'
  | 'toggleSidebar'
  | 'toggleInspector'
  | 'quickCapture'
  | 'manual'

export type DesktopShortcuts = Record<DesktopShortcutAction, string>

export interface DesktopSettings {
  theme: StudioThemeId
  accentHex: string
  fontScale: 'compact' | 'normal' | 'comfortable'
  wallpaperOpacity: number
  defaultSubView: CuratorSubView
  tabOrder: WorkspaceTab[]
  upscaleMode: DesktopUpscaleMode
  defaultPlaybackRate: number
  autoNextEpisode: boolean
  autoFallbackLine: boolean
  cloudIncludeCovers: boolean
  coverStoragePolicy: CoverStoragePolicy
  defaultRestoreStrategy: DesktopRestoreStrategy
  syncDesktopSettings: boolean
  closeToTray: boolean
  reduceMotion: boolean
  autoCheckUpdate: boolean
  shortcuts: DesktopShortcuts
}

export interface DataDirInfo {
  dataDir: string
  appVersion: string
}

export interface MobilePassThroughInfo {
  broadcastSchedule: string | null
  mergedSourceCount: number
  readingChapterTitle: string | null
  readingPermille: number | null
  legadoChapterTitle: string | null
  bookmarkCount: number
}

export interface CuratorItem {
  kind: MediaCarrier
  id: number
  syncKey: string
  title: string
  originalTitle: string
  coverUrl: string
  status: string
  progressPrimary: number
  progressTotal: number
  bookVolumes: number | null
  bookChapters: number | null
  personalScore: number | null
  rawRating: number | null
  publicScore: number | null
  scoreDelta: number | null
  studioOrCircle: string
  releaseDate: string
  mediaFormat: string | null
  lastModifiedAt: number
  seriesName: string | null
  seriesRole: string | null
  tags: string[]
  memo: string | null
  review: string
  localFileCount: number
  secondaryBangumiId?: string | null
  externalSource?: string | null
  externalId?: string | null
  mobilePassThrough: MobilePassThroughInfo
}

export interface LegacyDiscovery {
  defaultUserDataDir: string
  pointerTargetDir: string | null
  effectiveDataDir: string
  databaseExists: boolean
  animeCount: number
  bookCount: number
  gameCount: number
  audioCount: number
  characterCount: number
  coverFileCount: number
}

export interface LegacyImportReport {
  sourceDir: string
  studioDir: string
  animeCount: number
  bookCount: number
  gameCount: number
  audioCount: number
  characterCount: number
  copiedCovers: number
  copiedBranding: number
}

export interface VaultRootRow {
  id: number
  path: string
  mediaKind: string
  label: string
  lastScannedAt: string | null
  fileCount: number
}

export interface VaultFileRow {
  id: number
  rootId: number | null
  filePath: string
  fileName: string
  extension: string
  sizeBytes: number
  parsedTitle: string | null
  parsedEpisode: number | null
  parsedRjCode: string | null
  boundKind: string | null
  boundWorkId: number | null
  subtitlePath: string | null
  scriptPath: string | null
}

export interface LocalPlaybackTicket {
  token: string
  streamUrl: string
  subtitleUrl: string | null
  scriptText: string | null
  mimeType: string
  fileName: string
  filePath: string
}

export interface BackupSummary {
  animeCount: number
  bookCount: number
  gameCount: number
  audioCount: number
  tagCount: number
  characterCount: number
  groupCount: number
  coverCount: number
  createdAt: string
  dataFingerprint: string
  formatVersion: number
  fingerprintMatches: boolean | null
}

export interface BackupDiffEntry {
  kind: string
  title: string
  diffType: 'changed' | 'only_remote' | 'only_local'
  localSummary: string
  remoteSummary: string
}

export interface RestoreResult {
  summary: BackupSummary
  restorePointFile: string
}

export interface ActiveCompanionInfo {
  workId: number
  workTitle: string
  exePath: string
  pid: number
  elapsedSeconds: number
  startedAt: string
}

export interface PlaySessionRow {
  id: number
  workKind: string
  workId: number
  workTitle: string
  launcherPath: string | null
  startedAt: string
  endedAt: string
  durationSeconds: number
  note: string | null
}

export interface StudioBootstrap {
  studioDataDir: string
  mediaBaseUrl: string
  legacyDiscovery: LegacyDiscovery
  items: CuratorItem[]
  vaultRoots: VaultRootRow[]
  activeCompanion: ActiveCompanionInfo | null
}

export interface CellPatch {
  kind: MediaCarrier
  id: number
  status?: string
  progressPrimary?: number
  progressTotal?: number
  bookVolumes?: number
  bookChapters?: number
  rawRating?: number
  memo?: string
  review?: string
  title?: string
  originalTitle?: string
  coverUrl?: string
  studioOrCircle?: string
  releaseDate?: string
  mediaFormat?: string
  seriesName?: string
  seriesRole?: string
  tags?: string[]
}

export interface NewCuratorItemInput {
  kind: MediaCarrier
  title: string
  originalTitle?: string
  coverUrl?: string
  status?: string
  totalEpisodes?: number
  studioOrCircle?: string
  releaseDate?: string
  publicScore?: number
  externalSource?: string
  externalId?: string
  workCode?: string
  voiceActors?: string
  synopsis?: string
  mediaFormat?: string
}

export type WorkExternalSource = 'bangumi' | 'vndb' | 'dlsite'
export type ExternalBindingMode = 'none' | 'fill_missing' | 'overwrite_selected'
export type ExternalMetadataField = 'cover_url' | 'original_title' | 'release_date' | 'studio' | 'total_episodes' | 'tags' | 'synopsis'

export interface ExternalBindingRequest {
  kind: MediaCarrier
  id: number
  source: WorkExternalSource
  externalId: string | null
  mode?: ExternalBindingMode
  fields?: ExternalMetadataField[]
}

export interface RemoteSubjectDetail {
  externalSource: string
  externalId: string
  title: string | null
  originalTitle: string | null
  coverUrl: string | null
  coverThumbUrl: string | null
  releaseDate: string | null
  totalEpisodes: number | null
  summary: string | null
  studio: string | null
  mediaFormat: string | null
  tags: string[]
  publicScore: number | null
}

export interface RemoteCatalogHit {
  kind: MediaCarrier
  externalSource: string
  externalId: string
  title: string
  originalTitle: string
  coverUrl: string
  releaseDate: string
  totalEpisodes: number
  publicScore: number | null
  studioOrCircle: string
  synopsis: string
}

export interface CanvasNode {
  nodeId: string
  nodeType: string
  label: string
  sublabel: string
  badge: string
  workKind: MediaCarrier | null
  workId: number | null
  imageUrl?: string | null
  x: number
  y: number
}

export interface CanvasEdge {
  fromId: string
  toId: string
  relation: string
  relationId?: number | null
}

export interface UniverseGraph {
  mode: string
  nodes: CanvasNode[]
  edges: CanvasEdge[]
}

export interface LocalCaptureRow {
  id: number
  workKind: MediaCarrier
  workId: number
  workTitle: string
  imageRelPath: string | null
  note: string
  episodeHint: number | null
  createdAt: string
}

export interface EpisodeNoteRef {
  episode: number
  title: string
  review: string
  rating: number | null
  updatedAt: string
}

export interface BookBookmarkRef {
  id: number
  chapterTitle: string
  snippet: string
  note: string
  createdAt: string
}

export interface WorkReferenceBundle {
  episodeNotes: EpisodeNoteRef[]
  bookmarks: BookBookmarkRef[]
  captures: LocalCaptureRow[]
}

export interface TierListEntry {
  workKind: MediaCarrier
  workId: number
  tier: 'S' | 'A' | 'B' | 'C' | 'D'
  sortOrder: number
}

export interface ExternalPlayerDetection {
  playerExe: string
  windowTitle: string
  parsedTitle: string
  parsedEpisode: number
  matchedAnimeId: number
  matchedAnimeTitle: string
  currentWatchedEpisodes: number
  totalEpisodes: number
}

export interface PublicBangumiProfile {
  id: number
  username: string
  nickname?: string | null
  avatar?: string | null
}

export interface PublicCloudSessionInfo {
  loggedIn: boolean
  userId: number | null
  username: string | null
  expiresAt: number | null
  bangumi: PublicBangumiProfile | null
}

export interface CloudEndpointConfig {
  apiBase: string
}

export interface CloudBackupRow {
  id: number
  fileName: string
  payloadSize: number
  contentHash: string | null
  deviceId: string | null
  clientModifiedAt: string | null
  uploadDate: string | null
  createdAt: string
  coverCount?: number
  coversDroppedDueToSize?: boolean
  requestedIncludeCovers?: boolean
}

export interface DesktopUpdaterCheckResult {
  available: boolean
  currentVersion: string
  version: string | null
  notes: string | null
  pubDate: string | null
  downloaded: boolean
  endpoint: string
}

export interface DesktopUpdaterProgress {
  downloaded: number
  total: number | null
  percent: number | null
  phase: 'downloading' | 'verifying' | 'ready'
}

export interface DesktopUpdaterDownloadResult {
  version: string
  totalBytes: number
}

export interface CommunityPublishResult {
  postId: number
  statusNote: string
}

export interface BangumiAuthorizeStart {
  authorizeUrl: string
  state: string
}

export interface BangumiDiffRow {
  subjectId: number
  localAnimeId: number | null
  title: string
  diffKind: 'changed' | 'only_remote' | 'only_local'
  localSummary: string
  remoteSummary: string
  remoteStatus: string
  remoteWatchedEps: number
  remoteTotalEps: number
  remoteRate10: number | null
  remoteCoverUrl: string
  remoteAirDate: string
  remoteComment: string
}

export interface PlaybackSource {
  id: string
  name: string
  version: string
  rulesVersion: string
}

export interface PlaybackWork {
  id: string
  title: string
  coverUrl: string
}

export interface PlaybackEpisode {
  id: string
  label: string
  number: number | null
}

export interface PlaybackLine {
  id: string
  name: string
  episodes: PlaybackEpisode[]
}

export interface RemoteStreamTicket {
  token: string
  proxyUrl: string
  rawUrl: string
  mimeType: string
  headers: Record<string, string>
}

export interface BangumiCalendarItem {
  id: number
  title: string
  originalTitle: string
  coverUrl: string
  airDate: string
  score: number | null
  ratingCount: number
  rank: number | null
}

export interface BangumiCalendarDay {
  weekdayId: number
  weekdayCn: string
  weekdayEn: string
  items: BangumiCalendarItem[]
}

export interface BangumiCalendarResponse {
  days: BangumiCalendarDay[]
  sourceLabel: string
  updatedAt: string | null
  stale: boolean
}

export interface DiscoveryFeedRequest {
  kind: 'anime' | 'book' | 'game'
  feed: 'recommended' | 'ranking' | 'seasonal'
  sort?: 'heat' | 'rank' | 'score' | 'date'
  query?: string
  year?: number
  month?: number
  page?: number
  tag?: string
}

export interface DiscoveryItem {
  kind: 'anime' | 'book' | 'game'
  externalSource: string
  externalId: string
  title: string
  originalTitle: string
  coverUrl: string
  airDate: string
  score: number | null
  ratingCount: number
  rank: number | null
  totalEpisodes: number
  summary: string
  tags: string[]
  studioOrDev: string
  mediaFormat?: string | null
}

export interface DiscoveryPage {
  items: DiscoveryItem[]
  page: number
  hasMore: boolean
  total: number
  sourceLabel: string
  updatedAt: string | null
  stale: boolean
}

export interface TrashItem {
  kind: MediaCarrier
  id: number
  title: string
  status: string
  progressText: string
  rating: number | null
  deletedAt: string
}

export interface DuplicateCandidate {
  id: number
  kind: MediaCarrier
  title: string
  originalTitle: string
  status: string
  progressPrimary: number
  progressTotal: number
  rating: number | null
  reviewLen: number
  externalId: string
  createdAt: string
}

export interface DuplicateGroup {
  groupKey: string
  kind: MediaCarrier
  confidence: 'exact' | 'suspected'
  reason: string
  title: string
  recommendedKeepId: number
  candidates: DuplicateCandidate[]
}

export interface SeriesSuggestionMember {
  id: number
  kind: MediaCarrier
  title: string
  status: string
  currentSeriesName: string | null
  suggestedRole: string
  alreadyGrouped: boolean
}

export interface SeriesSuggestionGroup {
  groupKey: string
  seriesName: string
  existingSeries: boolean
  reason: string
  unassignedCount: number
  members: SeriesSuggestionMember[]
}

export interface CsvImportReport {
  imported: number
  skippedDuplicates: number
  failedRows: number
}

export interface CanvasCustomNode {
  id: string
  label: string
  subtitle: string
  color: string
  x: number
  y: number
}

export interface CanvasCustomEdge {
  id: string
  from: string
  to: string
  label: string
}

export interface CanvasCustomState {
  nodes: CanvasCustomNode[]
  edges: CanvasCustomEdge[]
}

export type UniverseMode = 'ip' | 'seiyuu' | 'characters'

export interface SeriesManageMember {
  id: number
  kind: MediaCarrier
  title: string
  status: string
  seriesRole: string | null
  coverUrl: string
}

export interface SeriesManageItem {
  id: number
  name: string
  description: string | null
  memberCount: number
  members: SeriesManageMember[]
}

export interface TagManageItem {
  id: number
  name: string
  blocked: boolean
  usageCount: number
}

export interface CharacterWorkLink {
  animeId: number
  kind: MediaCarrier
  title: string
  roleName: string
  voiceActor: string
}

export interface CharacterRelationItem {
  id: number
  sourceCharacterId: number
  targetCharacterId: number
  sourceName: string
  targetName: string
  relationType: string
  sourceRole: string
  targetRole: string
  note: string
  strength: number
}

export interface CharacterManageItem {
  id: number
  bgmId: number | null
  name: string
  nameCn: string
  imageUrl: string
  summary: string
  gender: string
  birthMonth: number | null
  birthDay: number | null
  bloodType: string
  rating: number | null
  review: string
  tags: string[]
  works: CharacterWorkLink[]
  relations: CharacterRelationItem[]
}

export interface WorkCharacterBrief {
  id: number
  name: string
  nameCn: string
  imageUrl: string
  roleName: string
  voiceActor: string
}

export interface RemoteCharacterHit {
  bgmId: number | null
  anilistId: number | null
  name: string
  nameCn: string
  imageUrl: string
  summary: string
  gender: string
  birthMonth: number | null
  birthDay: number | null
  bloodType: string
  roleName: string
  voiceActor: string
  source: string
}

export interface UpsertCharacterInput {
  id?: number | null
  bgmId?: number | null
  anilistId?: number | null
  name: string
  nameCn?: string | null
  imageUrl?: string | null
  summary?: string | null
  gender?: string | null
  birthMonth?: number | null
  birthDay?: number | null
  bloodType?: string | null
  rating?: number | null
  review?: string | null
}

export interface UpsertCharacterRelationInput {
  id?: number | null
  sourceCharacterId: number
  targetCharacterId: number
  relationType: string
  sourceRole?: string | null
  targetRole?: string | null
  note?: string | null
  strength?: number | null
}

// ============================================================================
// 桌面端独有 AI 智能体（AniMeow Agent Harness）类型
// ============================================================================

export interface AgentProviderPreset {
  id: string
  name: string
  region: 'cn' | 'global' | 'local' | 'custom'
  baseUrl: string
  defaultModel: string
  recommendedModels: string[]
  requiresApiKey: boolean
  isLocal: boolean
  badge?: string | null
  helpNote: string
  consoleUrl?: string | null
  webSearchMode?: string
  webSearchNote?: string
}

export interface AgentProbeStepResult {
  ok: boolean
  latencyMs: number
  detail: string
}

export interface AgentDiagnosticError {
  code: string
  title: string
  explanation: string
  suggestion: string
  quickFix?: string | null
  suggestedBaseUrl?: string | null
  technicalDetail: string
}

export interface AgentProbeResult {
  ok: boolean
  normalizedBaseUrl: string
  suggestedBaseUrl?: string | null
  connectivity: AgentProbeStepResult
  streaming: AgentProbeStepResult
  toolCalling: AgentProbeStepResult
  supportsTools: boolean
  supportsReasoning: boolean
  diagnostic?: AgentDiagnosticError | null
  probedAt: string
}

export interface AgentProfile {
  id: string
  name: string
  providerId: string
  baseUrl: string
  model: string
  temperature: number
  maxSteps: number
  maxTurnTokens: number
  supportsTools: boolean
  supportsReasoning: boolean
  enableWebSearch: boolean
  hasApiKey: boolean
  apiKeyHint?: string | null
  lastProbe?: AgentProbeResult | null
  isDefault: boolean
  updatedAt: string
}

export interface SaveAgentProfileInput {
  id?: string | null
  name: string
  providerId: string
  baseUrl: string
  model: string
  apiKey?: string | null
  clearApiKey?: boolean
  temperature?: number
  maxSteps?: number
  maxTurnTokens?: number
  supportsTools?: boolean
  supportsReasoning?: boolean
  enableWebSearch?: boolean
  lastProbe?: AgentProbeResult | null
  isDefault?: boolean
}

export interface AgentRemoteModelInfo {
  id: string
  ownedBy?: string | null
}

export interface AgentFetchModelsResult {
  normalizedBaseUrl: string
  suggestedBaseUrl?: string | null
  models: AgentRemoteModelInfo[]
  filteredOutCount: number
}

export interface AgentEnabledToolGroups {
  read: boolean
  online: boolean
  ui: boolean
  write: boolean
  memory: boolean
  mcp: boolean
}

export interface AgentAutoApprovePolicy {
  quickLog: boolean
  rehydrateEmpty: boolean
  highConfidenceBind: boolean
  createFromCatalog: boolean
  appendTagsOnly: boolean
  maxItemsPerAutoBatch: number
}

export interface AgentPolicyConfig {
  trustMode: 'strict' | 'balanced' | 'auto_safe' | 'quick_log'
  enabledGroups: AgentEnabledToolGroups
  defaultAntiSpoiler: boolean
  autoCompactTokens: number
  maxHistoryTurns: number
  personaId: string
  customInstructions: string
  autoApprove?: AgentAutoApprovePolicy
  coverStoragePolicy?: CoverStoragePolicy
  filterMetadataTags?: boolean
}

export interface AgentTokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cachedTokens?: number
  estimated: boolean
  usageSource?: 'reported' | 'estimated' | 'mixed' | 'missing' | string | null
  requestCount?: number | null
  compactionTokens?: number | null
}

export interface AgentWebSourceCitation {
  title: string
  url: string
  site?: string | null
  publishedDate?: string | null
  fetchedAt: string
}

export interface AgentVerifyJobSummary {
  id: string
  sessionId: string
  resultId: string
  kind: string
  status: string
  totalCount: number
  processedCount: number
  exactCount: number
  probableCount: number
  noMatchCount: number
  failedCount: number
  verifyIds: string[]
  createdAt: string
  updatedAt: string
}

export interface AgentVerifyJobItem {
  jobId: string
  kind: string
  workId: number
  title: string
  originalTitle: string
  localMeta: Record<string, unknown>
  status: string
  matchType?: string | null
  confidence: number
  chosenExternalId?: string | null
  chosenOriginalTitle?: string | null
  userDecision?: string | null
  candidates: Array<Record<string, unknown>>
  errorMessage?: string | null
  updatedAt: string
}

export interface AgentVerifyJobDetail {
  job: AgentVerifyJobSummary
  items: AgentVerifyJobItem[]
}

export interface WorkRehydrateResult {
  workId: number
  kind: string
  title: string
  externalId: string
  updatedFields: string[]
  skippedMissingRemote: string[]
  skippedExisting: string[]
  coverUpdated: boolean
  summary: string
}

export interface AgentToolTraceItem {
  id: string
  name: string
  label: string
  group: string
  argsPreview: string
  ok: boolean
  summary: string
  durationMs: number
  truncated: boolean
}

export interface AgentUiActionCommand {
  action: 'navigate' | 'apply_filter' | 'focus_canvas' | 'library_result'
  label: string
  payload: Record<string, unknown>
}

export interface AgentProposalItemDiff {
  index: number
  targetLabel: string
  actionLabel: string
  before: string
  after: string
}

export interface AgentSettingsUndoRecord {
  summary: string
  patch: Record<string, unknown>
  revertPatch: Record<string, unknown>
  changes: AgentProposalItemDiff[]
}

export interface AgentMemoryChangeEvent {
  action: 'added' | 'updated' | 'deleted'
  id: string
  category: string
  content: string
}

export interface AgentProposal {
  id: string
  sessionId: string
  kind: string
  title: string
  summary: string
  status: 'pending' | 'applied' | 'auto_applied' | 'noop' | 'rejected' | 'reverted' | 'unconfirmed' | 'expired'
  ops: unknown
  diffs: AgentProposalItemDiff[]
  canRevert: boolean
  appliedCount?: number | null
  appliedIndices?: number[] | null
  lastAttemptFailed?: boolean
  lastError?: string | null
  batchGroupId?: string | null
  overlapWarnings?: string[]
  createdAt: string
  appliedAt?: string | null
}

export interface AgentProposalApplyResult {
  proposal: AgentProposal
  appliedCount: number
  appliedIndices?: number[]
  changedDomains: string[]
  message: string
}

export interface BatchProposalItemOutcome {
  proposalId: string
  title: string
  outcome: 'applied' | 'noop' | 'conflict_skipped' | 'failed' | 'skipped' | 'reverted'
  appliedCount: number
  message: string
  proposal?: AgentProposal | null
}

export interface BatchProposalCommitResult {
  batchGroupId: string
  appliedProposals: number
  noopProposals: number
  skippedOrFailedProposals: number
  totalAppliedItems: number
  changedDomains: string[]
  outcomes: BatchProposalItemOutcome[]
  message: string
}

export interface BatchProposalRevertResult {
  batchGroupId: string
  revertedProposals: number
  failedProposals: number
  changedDomains: string[]
  outcomes: BatchProposalItemOutcome[]
  message: string
}

export interface CoverBaselineStats {
  totalWorks: number
  remoteCount: number
  localUserCustomCount: number
  localExternalNativeCount: number
  noneCount: number
  nativeDirFileCount: number
  nativeDirTotalBytes: number
  rehydrateFileCount: number
  rehydrateTotalBytes: number
  rehydrateAvgBytes: number
  orphanFileCount: number
  orphanTotalBytes: number
}

export interface OrphanCoverCleanupResult {
  dryRun: boolean
  scannedFiles: number
  protectedFiles: number
  orphanFiles: string[]
  removedCount: number
  freedBytes: number
  message: string
}

export interface AgentSession {
  id: string
  title: string
  profileId?: string | null
  personaId: string
  pinned: boolean
  archived: boolean
  rollingSummary?: string | null
  compactedUpToMsgId?: string | null
  messageCount: number
  totalPromptTokens: number
  totalCompletionTokens: number
  createdAt: string
  updatedAt: string
}

export interface AgentMessageVersion {
  content: string
  reasoningContent?: string | null
  toolTraces: AgentToolTraceItem[]
  uiActions: AgentUiActionCommand[]
  settingsUndo?: AgentSettingsUndoRecord | null
  memoryEvents: AgentMemoryChangeEvent[]
  proposalId?: string | null
  proposalIds?: string[]
  diagnosticError?: AgentDiagnosticError | null
  tokenUsage?: AgentTokenUsage | null
  stopped?: boolean
  webSources?: AgentWebSourceCitation[]
  createdAt: string
}

export interface AgentMessage {
  id: string
  sessionId: string
  role: 'user' | 'assistant' | 'tool' | string
  content: string
  reasoningContent?: string | null
  toolCalls?: { id: string; name: string; arguments: string }[] | null
  toolTraces: AgentToolTraceItem[]
  uiActions: AgentUiActionCommand[]
  settingsUndo?: AgentSettingsUndoRecord | null
  memoryEvents: AgentMemoryChangeEvent[]
  proposalId?: string | null
  proposalIds?: string[]
  diagnosticError?: AgentDiagnosticError | null
  tokenUsage?: AgentTokenUsage | null
  stopped?: boolean
  webSources?: AgentWebSourceCitation[]
  versions?: AgentMessageVersion[]
  activeVersionIdx?: number
  createdAt: string
}

export interface AgentMemory {
  id: string
  category: 'preference' | 'rating_rule' | 'spoiler_rule' | 'style' | 'avoid' | 'profile' | string
  tier: 'core' | 'archive' | string
  content: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface AgentMemoryDraft {
  category: string
  content: string
}

export interface AgentSlashCommand {
  id: string
  command: string
  label: string
  description: string
  promptTemplate: string
  skillGuide?: string | null
  toolScope?: string[] | null
  isBuiltin: boolean
  updatedAt: string
}

export interface AgentMcpServerConfig {
  id: string
  name: string
  transport: 'http' | 'stdio' | string
  endpointOrCmd: string
  args: string[]
  headers: Record<string, string>
  hasAuthToken: boolean
  authTokenHint?: string | null
  enabled: boolean
  disabledTools: string[]
  requireConfirmTools: string[]
  lastStatus?: string | null
  lastLatencyMs?: number | null
  updatedAt: string
}

export interface SaveAgentMcpServerInput {
  id?: string | null
  name: string
  transport: 'http' | 'stdio'
  endpointOrCmd: string
  args?: string[]
  headers?: Record<string, string>
  authToken?: string | null
  clearAuthToken?: boolean
  enabled?: boolean
  disabledTools?: string[]
  requireConfirmTools?: string[]
}

export interface AgentMcpToolInfo {
  serverName: string
  name: string
  description: string
  inputSchema: Record<string, unknown>
  requiresConfirm: boolean
  disabled: boolean
}

export interface AgentMcpTestResult {
  ok: boolean
  latencyMs: number
  serverInfo?: string | null
  tools: AgentMcpToolInfo[]
  error?: string | null
}

export interface AgentRunEvent {
  id: number
  runId: string
  sessionId: string
  step: number
  eventType:
    | 'run_start'
    | 'llm_step'
    | 'tool_call'
    | 'guard'
    | 'compaction'
    | 'run_end'
    | 'run_error'
    | string
  title: string
  status: 'ok' | 'warn' | 'error' | string
  durationMs: number
  payload: Record<string, unknown>
  createdAt: string
}

export interface AgentTrajectorySummary {
  sessionId: string
  sessionTitle: string
  totalRuns: number
  totalLlmSteps: number
  totalToolCalls: number
  failedToolCalls: number
  guardTriggers: number
  totalPromptTokens: number
  totalCachedTokens: number
  totalCompletionTokens: number
  totalDurationMs: number
  events: AgentRunEvent[]
}

export interface AgentActiveContextChip {
  activeTab: string
  activeSubView?: string | null
  workKind?: string | null
  workId?: number | null
  workTitle?: string | null
  workStatus?: string | null
  mediaFormat?: string | null
  progressUnit?: string | null
  progressPrimary?: number | null
  progressTotal?: number | null
  antiSpoilerEnabled: boolean
  filterResultId?: string | null
  filterCount?: number | null
  filterLabel?: string | null
}

export interface SendAgentMessageInput {
  sessionId: string
  profileId?: string | null
  userMessage: string
  slashCommand?: string | null
  contextChip?: AgentActiveContextChip | null
  currentSettings?: Record<string, unknown> | null
  regenerateMessageId?: string | null
  sessionAutopilot?: boolean
}

export type AgentStreamEvent =
  | { type: 'started'; userMessage: AgentMessage; activeGroups: string[] }
  | { type: 'stepStart'; step: number; maxSteps: number }
  | { type: 'delta'; text: string }
  | { type: 'reasoningDelta'; text: string }
  | { type: 'toolStart'; id: string; name: string; label: string; group: string; argsPreview: string }
  | { type: 'toolResult'; trace: AgentToolTraceItem }
  | { type: 'uiAction'; action: AgentUiActionCommand }
  | { type: 'settingsApplied'; undo: AgentSettingsUndoRecord }
  | { type: 'memoryChanged'; event: AgentMemoryChangeEvent }
  | { type: 'contextCompacted'; summary: string; compactedMessages: number }
  | { type: 'proposalCreated'; proposal: AgentProposal }
  | { type: 'quickApplied'; changedDomains: string[]; summary: string; proposal: AgentProposal }
  | { type: 'autoApplied'; changedDomains: string[]; summary: string; proposal: AgentProposal }
  | { type: 'usage'; usage: AgentTokenUsage }
  | { type: 'done'; assistantMessage: AgentMessage; cancelled: boolean }
  | { type: 'diagnosticError'; error: AgentDiagnosticError; partialMessage?: AgentMessage | null }

export interface AgentStreamEnvelope {
  runId: string
  sessionId: string
  event: AgentStreamEvent
}
