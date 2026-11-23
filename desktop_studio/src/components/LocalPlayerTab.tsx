import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { open } from '@tauri-apps/plugin-dialog'
import { studioApi } from '../api'
import { OnlineAnimePlayer } from './OnlineAnimePlayer'
import { StudioSelect } from './StudioSelect'
import { UpscaledVideoStage } from './UpscaledVideoStage'
import {
  DESKTOP_SETTINGS_EVENT,
  loadLocalDesktopSettings,
  persistLocalDesktopSettings
} from '../utils/desktopSettings'
import {
  VIDEO_UPSCALE_OPTIONS,
  VideoUpscaleMode
} from '../utils/videoUpscaler'
import type {
  CellPatch,
  CuratorItem,
  LocalPlaybackTicket,
  VaultFileRow,
  VaultRootRow
} from '../types'

interface LocalPlayerTabProps {
  ticket: LocalPlaybackTicket | null
  boundItem: CuratorItem | null
  items: CuratorItem[]
  animeItems: CuratorItem[]
  vaultRoots: VaultRootRow[]
  vaultRevision: number
  onlineTargetAnime: CuratorItem | null
  onlineSearchTitle?: string | null
  preferredMode: 'online' | 'local'
  onSelectOnlineAnime: (item: CuratorItem | null) => void
  onTicketChange: (ticket: LocalPlaybackTicket | null, item: CuratorItem | null) => void
  onPlayVaultFile: (file: VaultFileRow, item: CuratorItem | null) => Promise<void>
  onVaultChanged: () => Promise<void>
  onUpdateCell: (patch: CellPatch) => Promise<void>
  onSyncAnimeEpisodeWithUndo: (animeId: number, targetEpisode: number) => Promise<void>
}

interface ParsedLyricLine {
  timeSec: number | null
  text: string
}

function parseLrcOrScript(raw: string | null): ParsedLyricLine[] {
  if (!raw) return []
  const lines = raw.replace(/\r\n/g, '\n').split('\n')
  const re = /^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\](.*)$/
  return lines
    .map((line) => {
      const m = line.trim().match(re)
      if (!m) {
        return { timeSec: null, text: line }
      }
      const mins = Number.parseInt(m[1], 10)
      const secs = Number.parseInt(m[2], 10)
      const ms = m[3] ? Number.parseInt(m[3].padEnd(3, '0'), 10) : 0
      return {
        timeSec: mins * 60 + secs + ms / 1000,
        text: m[4].trim() || '...'
      }
    })
    .filter((item) => item.text.trim().length > 0)
}

export function LocalPlayerTab({
  ticket,
  boundItem,
  items,
  animeItems,
  vaultRoots,
  vaultRevision,
  onlineTargetAnime,
  onlineSearchTitle,
  preferredMode,
  onSelectOnlineAnime,
  onTicketChange,
  onPlayVaultFile,
  onVaultChanged,
  onUpdateCell,
  onSyncAnimeEpisodeWithUndo
}: LocalPlayerTabProps) {
  const initialDesktopPrefs = useMemo(() => loadLocalDesktopSettings(), [])
  const mediaRef = useRef<HTMLVideoElement | HTMLAudioElement | null>(null)
  const videoOnlyRef = useRef<HTMLVideoElement | null>(null)
  const [playerMode, setPlayerMode] = useState<'online' | 'local'>(preferredMode)
  const [playbackRate, setPlaybackRate] = useState<number>(initialDesktopPrefs.defaultPlaybackRate)
  const [upscaleMode, setUpscaleMode] = useState<VideoUpscaleMode>(initialDesktopPrefs.upscaleMode)
  const [upscaleStatus, setUpscaleStatus] = useState<string>('')
  const [upscaleCanvasActive, setUpscaleCanvasActive] = useState<boolean>(false)

  useEffect(() => {
    const onSettingsChanged = (e: Event) => {
      const detail = (e as CustomEvent).detail
      if (!detail || typeof detail !== 'object') return
      if (detail.upscaleMode) setUpscaleMode(detail.upscaleMode)
      if (typeof detail.defaultPlaybackRate === 'number') setPlaybackRate(detail.defaultPlaybackRate)
    }
    window.addEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
    return () => window.removeEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
  }, [])
  const [currentTime, setCurrentTime] = useState<number>(0)
  const [syncNotice, setSyncNotice] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [vaultFiles, setVaultFiles] = useState<VaultFileRow[]>([])
  const [vaultFilter, setVaultFilter] = useState<string>('')
  const [scanningFolder, setScanningFolder] = useState<boolean>(false)
  const [showVaultDrawer, setShowVaultDrawer] = useState<boolean>(false)

  const refreshLocalVaultFiles = useCallback(async () => {
    try {
      const rows = await studioApi.listVaultFiles()
      setVaultFiles(rows)
    } catch {
      setVaultFiles([])
    }
  }, [])

  useEffect(() => {
    void refreshLocalVaultFiles()
  }, [refreshLocalVaultFiles, vaultRevision, vaultRoots.length])

  const filteredVaultFiles = useMemo(() => {
    const q = vaultFilter.trim().toLowerCase()
    if (!q) return vaultFiles
    return vaultFiles.filter((f) =>
      `${f.fileName} ${f.parsedTitle ?? ''} ${f.parsedRjCode ?? ''}`.toLowerCase().includes(q)
    )
  }, [vaultFiles, vaultFilter])

  const itemByKey = useMemo(
    () => new Map(items.map((it) => [`${it.kind}:${it.id}`, it])),
    [items]
  )

  const handleAddMediaFolder = async () => {
    setErrorMsg(null)
    try {
      const dir = await open({
        directory: true,
        multiple: false,
        title: '选择本机番剧或音声媒体文件夹'
      })
      if (typeof dir !== 'string') return
      setScanningFolder(true)
      const root = await studioApi.mountScanVault(dir, 'mixed')
      await onVaultChanged()
      await refreshLocalVaultFiles()
      setSyncNotice(`已扫描「${root.label}」，共索引 ${root.fileCount} 个本机媒体文件`)
      window.setTimeout(() => setSyncNotice(null), 3200)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setScanningFolder(false)
    }
  }

  useEffect(() => {
    setPlayerMode(preferredMode)
  }, [preferredMode, onlineTargetAnime?.id, ticket?.token])

  useEffect(() => {
    setUpscaleMode(loadLocalDesktopSettings().upscaleMode)
  }, [playerMode])

  const isAudio = ticket?.mimeType.startsWith('audio/') ?? false
  const parsedScript = useMemo(() => parseLrcOrScript(ticket?.scriptText ?? null), [ticket?.scriptText])

  const activeLyricIndex = useMemo(() => {
    let idx = -1
    for (let i = 0; i < parsedScript.length; i++) {
      const t = parsedScript[i].timeSec
      if (t != null && t <= currentTime + 0.15) {
        idx = i
      }
    }
    return idx
  }, [parsedScript, currentTime])

  useEffect(() => {
    if (mediaRef.current) {
      mediaRef.current.playbackRate = playbackRate
    }
  }, [playbackRate, ticket?.token])

  const handlePickDiskMedia = async () => {
    setErrorMsg(null)
    try {
      const mediaFile = await open({
        multiple: false,
        title: '选择本机视频或音声文件',
        filters: [
          {
            name: '本地媒体文件',
            extensions: ['mp4', 'mkv', 'webm', 'm4v', 'mp3', 'flac', 'wav', 'ogg', 'm4a']
          }
        ]
      })
      if (typeof mediaFile !== 'string') return

      const nextTicket = await studioApi.openLocalPlayer(mediaFile, null, null)
      onTicketChange(nextTicket, null)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  const handleAttachSubtitleOrScript = async (kind: 'subtitle' | 'script') => {
    if (!ticket) return
    setErrorMsg(null)
    try {
      const picked = await open({
        multiple: false,
        title: kind === 'subtitle' ? '挂载外挂字幕 (.srt / .vtt)' : '挂载音声台本或歌词 (.lrc / .txt)',
        filters:
          kind === 'subtitle'
            ? [{ name: '字幕文件', extensions: ['srt', 'vtt'] }]
            : [{ name: '台本/LRC', extensions: ['lrc', 'txt'] }]
      })
      if (typeof picked !== 'string') return
      const nextTicket = await studioApi.openLocalPlayer(
        ticket.filePath,
        kind === 'subtitle' ? picked : null,
        kind === 'script' ? picked : null
      )
      onTicketChange(nextTicket, boundItem)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  const handleSyncProgress = async () => {
    if (!boundItem) return
    if (boundItem.kind === 'anime') {
      const nextEp =
        boundItem.progressTotal > 0
          ? Math.min(boundItem.progressPrimary + 1, boundItem.progressTotal)
          : boundItem.progressPrimary + 1
      await onUpdateCell({ kind: 'anime', id: boundItem.id, progressPrimary: nextEp })
      setSyncNotice(`已将《${boundItem.title}》进度更新至第 ${nextEp} 集`)
    } else if (boundItem.kind === 'audio') {
      const addSecs = Math.max(60, Math.round(currentTime))
      const nextSecs = boundItem.progressPrimary + addSecs
      await onUpdateCell({ kind: 'audio', id: boundItem.id, progressPrimary: nextSecs })
      setSyncNotice(`已为《${boundItem.title}》累加收听进度 ${Math.round(addSecs / 60)} 分钟`)
    }
    window.setTimeout(() => setSyncNotice(null), 2600)
  }

  const seekBy = (deltaSeconds: number) => {
    const el = mediaRef.current
    if (!el) return
    el.currentTime = Math.max(0, Math.min(el.duration || Infinity, el.currentTime + deltaSeconds))
  }

  const handleExitPlayback = () => {
    const el = mediaRef.current
    if (el) {
      try {
        el.pause()
        el.removeAttribute('src')
        el.load()
      } catch {
        // 忽略释放媒体元素时的非致命异常
      }
    }
    mediaRef.current = null
    videoOnlyRef.current = null
    setCurrentTime(0)
    setUpscaleCanvasActive(false)
    setErrorMsg(null)
    setSyncNotice(null)
    onTicketChange(null, null)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="sheet-toolbar">
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
            width: '100%',
            minWidth: 0
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0, flex: 1 }}>
            <div className="segmented-group" style={{ flexShrink: 0 }}>
              <button
                className={`segmented-btn ${playerMode === 'online' ? 'active' : ''}`}
                onClick={() => setPlayerMode('online')}
              >
                在线追番
              </button>
              <button
                className={`segmented-btn ${playerMode === 'local' ? 'active' : ''}`}
                onClick={() => setPlayerMode('local')}
              >
                本地媒体
              </button>
            </div>
            {playerMode === 'local' && ticket && (
              <span
                className="mono"
                style={{
                  color: 'var(--text-secondary)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  minWidth: 0
                }}
                title={ticket.filePath}
              >
                {ticket.fileName}
              </span>
            )}
          </div>
          {playerMode === 'local' && ticket && (
            <button
              className="btn"
              style={{
                flexShrink: 0,
                color: 'var(--danger)',
                borderColor: 'color-mix(in srgb, var(--danger) 48%, var(--border-strong))'
              }}
              onClick={handleExitPlayback}
              title="停止播放并释放当前媒体，返回本地媒体库"
            >
              退出播放
            </button>
          )}
        </div>

        {playerMode === 'local' && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              width: '100%',
              minWidth: 0,
              flexWrap: 'wrap'
            }}
          >
            <button className="btn" onClick={handlePickDiskMedia}>
              打开文件
            </button>
            <button
              className="btn"
              disabled={scanningFolder}
              onClick={() => void handleAddMediaFolder()}
              title="选择本机番剧或音声文件夹，自动识别集数、字幕与台本"
            >
              {scanningFolder ? '正在扫描...' : '添加媒体文件夹'}
            </button>
            {ticket && vaultFiles.length > 0 && (
              <button
                className={`btn ${showVaultDrawer ? 'btn-primary' : ''}`}
                onClick={() => setShowVaultDrawer((v) => !v)}
              >
                {showVaultDrawer ? '收起媒体库' : `媒体库 (${vaultFiles.length})`}
              </button>
            )}
            {ticket && !isAudio && (
              <button className="btn" onClick={() => handleAttachSubtitleOrScript('subtitle')}>
                加载字幕 (.srt/.vtt)
              </button>
            )}
            {ticket && (
              <button className="btn" onClick={() => handleAttachSubtitleOrScript('script')}>
                加载台本 (.lrc/.txt)
              </button>
            )}
            {ticket && (
              <>
                <StudioSelect
                  className="studio-select-inline tabular"
                  value={String(playbackRate)}
                  onChange={(val) => setPlaybackRate(Number.parseFloat(val))}
                  title="倍速控制"
                  ariaLabel="播放倍速"
                  minMenuWidth={104}
                  maxMenuWidth={140}
                  options={[
                    { value: '0.75', label: '0.75x' },
                    { value: '1', label: '1.0x' },
                    { value: '1.25', label: '1.25x' },
                    { value: '1.5', label: '1.5x' },
                    { value: '2', label: '2.0x' }
                  ]}
                />
                {!isAudio && (
                  <StudioSelect
                    className="studio-select-inline"
                    value={upscaleMode}
                    onChange={(val) => {
                      const next = val as VideoUpscaleMode
                      setUpscaleMode(next)
                      const updated = { ...loadLocalDesktopSettings(), upscaleMode: next }
                      persistLocalDesktopSettings(updated)
                      void studioApi
                        .saveDesktopSettings(updated as unknown as Record<string, unknown>)
                        .catch(() => {})
                    }}
                    triggerLabel={`画质：${VIDEO_UPSCALE_OPTIONS.find((o) => o.value === upscaleMode)?.shortLabel ?? '原画'}`}
                    title={
                      upscaleStatus
                        ? `画质增强：${upscaleStatus}`
                        : '实时 GPU 视频超分与锐化（WebGL2 + Anime4K）'
                    }
                    ariaLabel="视频画质增强档位"
                    minMenuWidth={270}
                    maxMenuWidth={350}
                    options={VIDEO_UPSCALE_OPTIONS.map((opt) => ({
                      value: opt.value,
                      label: opt.label,
                      sublabel: opt.description
                    }))}
                  />
                )}
                <button
                  className="btn"
                  onClick={() => studioApi.openExternalPlayer(ticket.filePath, false)}
                  title="使用系统默认外部播放器（如 PotPlayer / MPV）打开，支持杜比视界与复杂 ASS 特效字幕"
                >
                  外部播放器打开
                </button>
                <button
                  className="btn btn-ghost"
                  onClick={() => studioApi.openExternalPlayer(ticket.filePath, true)}
                >
                  定位文件
                </button>
              </>
            )}
          </div>
        )}
      </div>

      <div
        className={`workspace-pane ${playerMode === 'online' ? 'is-active' : 'is-hidden'}`}
      >
        <OnlineAnimePlayer
          targetAnime={onlineTargetAnime}
          animeItems={animeItems}
          initialSearchTitle={onlineSearchTitle}
          onSelectTargetAnime={onSelectOnlineAnime}
          onSyncAnimeEpisodeWithUndo={onSyncAnimeEpisodeWithUndo}
        />
      </div>

      <div
        className={`workspace-pane ${playerMode === 'local' ? 'is-active' : 'is-hidden'}`}
      >
      {errorMsg && (
        <div style={{ padding: '8px 14px', background: 'rgba(242,109,109,0.14)', color: 'var(--danger)' }}>
          播放准备失败：{errorMsg}
        </div>
      )}

      {boundItem && (
        <div
          style={{
            padding: '7px 14px',
            background: 'var(--bg-panel)',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10
          }}
        >
          <div>
            关联藏品：<strong>{boundItem.title}</strong>
            <span style={{ color: 'var(--text-secondary)', marginLeft: 10 }}>
              当前记录状态：{boundItem.status}
            </span>
            {syncNotice && (
              <span style={{ color: 'var(--positive)', marginLeft: 12, fontWeight: 600 }}>
                {syncNotice}
              </span>
            )}
          </div>
          {(boundItem.kind === 'anime' || boundItem.kind === 'audio') && (
            <button className="btn btn-primary" onClick={handleSyncProgress}>
              {boundItem.kind === 'anime' ? '看完本集 · 进度 +1' : '同步收听时长到藏品库'}
            </button>
          )}
        </div>
      )}

      {syncNotice && !boundItem && (
        <div
          style={{
            padding: '7px 14px',
            background: 'var(--bg-panel)',
            borderBottom: '1px solid var(--border-subtle)',
            color: 'var(--positive)',
            fontSize: '0.9231rem',
            fontWeight: 600
          }}
        >
          {syncNotice}
        </div>
      )}

      {!ticket ? (
        <div
          style={{
            flex: 1,
            minHeight: 0,
            overflowY: 'auto',
            padding: 24,
            display: 'flex',
            flexDirection: 'column',
            gap: 16,
            alignItems: 'center'
          }}
        >
          <div
            className="card-box"
            style={{
              width: '100%',
              maxWidth: 760,
              padding: 24,
              textAlign: 'center',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 10
            }}
          >
            <div className="heading-font" style={{ fontSize: '1.1923rem', fontWeight: 700, color: 'var(--text-primary)' }}>
              本机视频与音声播放器
            </div>
            <div style={{ maxWidth: 540, color: 'var(--text-secondary)', fontSize: '0.9615rem', lineHeight: 1.65 }}>
              可直接打开单个本机文件，或添加硬盘中的番剧／音声文件夹自动扫描集数、同名字幕（.srt / .vtt）与台本（.lrc / .txt）；遇到特殊封装或特效字幕时也可一键交由 PotPlayer / MPV 播放。
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center', marginTop: 4 }}>
              <button className="btn btn-primary" onClick={handlePickDiskMedia}>
                打开文件
              </button>
              <button
                className="btn"
                disabled={scanningFolder}
                onClick={() => void handleAddMediaFolder()}
              >
                {scanningFolder ? '正在扫描文件夹...' : '添加媒体文件夹'}
              </button>
            </div>
          </div>

          <div
            className="card-box"
            style={{
              width: '100%',
              maxWidth: 760,
              padding: 16,
              display: 'flex',
              flexDirection: 'column',
              gap: 10
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
              <div>
                <div className="heading-font" style={{ fontSize: '1.0385rem', fontWeight: 700 }}>
                  本机媒体库（已挂载 {vaultRoots.length} 个文件夹 · {vaultFiles.length} 个文件）
                </div>
                {vaultRoots.length > 0 && (
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
                    {vaultRoots.map((r) => (
                      <span key={r.id} className="badge mono" title={r.path}>
                        {r.label} ({r.fileCount})
                      </span>
                    ))}
                  </div>
                )}
              </div>
              {vaultFiles.length > 0 && (
                <input
                  className="inline-input"
                  style={{ width: 220, height: 28 }}
                  value={vaultFilter}
                  onChange={(e) => setVaultFilter(e.target.value)}
                  placeholder="筛选文件名或番剧名..."
                />
              )}
            </div>

            {vaultFiles.length === 0 ? (
              <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem', padding: '8px 0' }}>
                尚未添加媒体文件夹。点击上方「添加媒体文件夹」即可自动扫描并关联藏品。
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 340, overflowY: 'auto' }}>
                {filteredVaultFiles.slice(0, 80).map((f) => {
                  const matched =
                    f.boundKind && f.boundWorkId != null
                      ? itemByKey.get(`${f.boundKind}:${f.boundWorkId}`) ?? null
                      : null
                  return (
                    <div
                      key={f.id}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 10,
                        padding: '7px 10px',
                        borderRadius: 'var(--radius-sm)',
                        background: 'var(--bg-canvas)',
                        border: '1px solid var(--border-subtle)'
                      }}
                    >
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div
                          style={{
                            fontSize: '0.9615rem',
                            fontWeight: 600,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis'
                          }}
                          title={f.filePath}
                        >
                          {f.fileName}
                        </div>
                        <div style={{ fontSize: '0.8462rem', color: 'var(--text-muted)', marginTop: 2 }}>
                          {matched ? `已关联：${matched.title}` : f.parsedTitle || '未关联藏品'}
                          {f.parsedEpisode != null ? ` · 第 ${f.parsedEpisode} 集` : ''}
                          {f.subtitlePath ? ' · 含字幕' : ''}
                          {f.scriptPath ? ' · 含台本' : ''}
                        </div>
                      </div>
                      <button
                        className="btn btn-primary"
                        style={{ height: 26 }}
                        onClick={() => void onPlayVaultFile(f, matched)}
                      >
                        播放
                      </button>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 0 }}>
          {showVaultDrawer && vaultFiles.length > 0 && (
            <div
              style={{
                width: 280,
                flexShrink: 0,
                borderRight: '1px solid var(--border-subtle)',
                background: 'var(--bg-panel)',
                display: 'flex',
                flexDirection: 'column',
                minHeight: 0
              }}
            >
              <div style={{ padding: 10, borderBottom: '1px solid var(--border-subtle)' }}>
                <input
                  className="inline-input"
                  style={{ width: '100%', height: 28 }}
                  value={vaultFilter}
                  onChange={(e) => setVaultFilter(e.target.value)}
                  placeholder="快速切换本机文件..."
                />
              </div>
              <div style={{ flex: 1, overflowY: 'auto', padding: 8, display: 'flex', flexDirection: 'column', gap: 5 }}>
                {filteredVaultFiles.slice(0, 100).map((f) => {
                  const matched =
                    f.boundKind && f.boundWorkId != null
                      ? itemByKey.get(`${f.boundKind}:${f.boundWorkId}`) ?? null
                      : null
                  const isCurrent = ticket.filePath === f.filePath
                  return (
                    <button
                      key={f.id}
                      className={`vault-drawer-item ${isCurrent ? 'active' : ''}`}
                      style={{ textAlign: 'left', flexDirection: 'column', alignItems: 'flex-start', gap: 2, padding: '6px 8px' }}
                      onClick={() => void onPlayVaultFile(f, matched)}
                      title={f.filePath}
                    >
                      <span className="vault-drawer-item-title">
                        {f.fileName}
                      </span>
                      <span className="vault-drawer-item-sub">
                        {matched ? matched.title : f.parsedTitle || '本机媒体'}
                        {f.parsedEpisode != null ? ` · EP ${f.parsedEpisode}` : ''}
                      </span>
                    </button>
                  )
                })}
              </div>
            </div>
          )}
          <div
            style={{
              flex: 1,
              minWidth: 0,
              display: 'flex',
              flexDirection: 'column',
              background: '#050608',
              justifyContent: 'center',
              alignItems: 'center',
              padding: 16,
              position: 'relative'
            }}
          >
            {isAudio ? (
              <div
                className="card-box"
                style={{ width: '100%', maxWidth: 560, display: 'flex', flexDirection: 'column', gap: 14 }}
              >
                <div style={{ fontWeight: 700, fontSize: '1.1538rem' }}>{ticket.fileName}</div>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem' }}>
                  音声专注模式 · 支持右侧 LRC 台本逐句点击定位
                </div>
                <audio
                  key={ticket.token}
                  ref={(el) => {
                    mediaRef.current = el
                  }}
                  src={ticket.streamUrl}
                  controls
                  autoPlay
                  onTimeUpdate={(e) => setCurrentTime(e.currentTarget.currentTime)}
                  style={{ width: '100%' }}
                />
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <button className="btn" onClick={() => seekBy(-5)}>
                    后退 5 秒
                  </button>
                  <button className="btn" onClick={() => seekBy(5)}>
                    前进 5 秒
                  </button>
                  <button
                    className="btn"
                    style={{ marginLeft: 'auto', color: 'var(--danger)' }}
                    onClick={handleExitPlayback}
                  >
                    退出播放
                  </button>
                </div>
              </div>
            ) : (
              <UpscaledVideoStage
                videoRef={videoOnlyRef}
                mode={upscaleMode}
                active={Boolean(ticket && !isAudio)}
                onStatusChange={setUpscaleStatus}
                onRenderActiveChange={setUpscaleCanvasActive}
                style={{ width: '100%', height: '100%', maxHeight: '100%' }}
              >
                <video
                  key={ticket.token}
                  ref={(el) => {
                    mediaRef.current = el
                    videoOnlyRef.current = el
                  }}
                  src={ticket.streamUrl}
                  controls={upscaleMode === 'off' || !upscaleCanvasActive}
                  autoPlay
                  crossOrigin="anonymous"
                  onTimeUpdate={(e) => setCurrentTime(e.currentTarget.currentTime)}
                  style={{ width: '100%', height: '100%', maxHeight: '100%', outline: 'none' }}
                >
                  {ticket.subtitleUrl && (
                    <track
                      kind="subtitles"
                      src={ticket.subtitleUrl}
                      srcLang="zh"
                      label="外挂字幕"
                      default
                    />
                  )}
                </video>
              </UpscaledVideoStage>
            )}
          </div>

          {parsedScript.length > 0 && (
            <div
              style={{
                width: 320,
                borderLeft: '1px solid var(--border-subtle)',
                background: 'var(--bg-panel)',
                overflowY: 'auto',
                padding: 12,
                display: 'flex',
                flexDirection: 'column',
                gap: 4
              }}
            >
              <div style={{ fontWeight: 600, fontSize: '0.9231rem', marginBottom: 6, color: 'var(--text-secondary)' }}>
                同步台本 / LRC 轴 ({parsedScript.length} 行)
              </div>
              {parsedScript.map((line, idx) => {
                const active = idx === activeLyricIndex
                return (
                  <div
                    key={idx}
                    onClick={() => {
                      if (line.timeSec != null && mediaRef.current) {
                        mediaRef.current.currentTime = line.timeSec
                      }
                    }}
                    style={{
                      padding: '5px 8px',
                      borderRadius: 5,
                      cursor: line.timeSec != null ? 'pointer' : 'default',
                      background: active ? 'var(--accent-soft)' : 'transparent',
                      color: active ? 'var(--accent)' : 'var(--text-secondary)',
                      fontWeight: active ? 600 : 400,
                      fontSize: '0.9615rem'
                    }}
                  >
                    {line.timeSec != null && (
                      <span className="mono" style={{ opacity: 0.65, marginRight: 6, fontSize: '0.8462rem' }}>
                        {Math.floor(line.timeSec / 60)
                          .toString()
                          .padStart(2, '0')}
                        :
                        {Math.floor(line.timeSec % 60)
                          .toString()
                          .padStart(2, '0')}
                      </span>
                    )}
                    <span>{line.text}</span>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}
      </div>
    </div>
  )
}
