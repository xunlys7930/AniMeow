import { useEffect, useRef, useState } from 'react'
import { formatBytes, formatCarrierLabel, studioApi } from '../api'
import type { DesktopUpdaterController } from '../hooks/useDesktopUpdater'
import { CloudSyncPanels } from './CloudSyncPanels'
import { WaitingBanner } from './LoadingIndicator'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import {
  DESKTOP_SETTINGS_EVENT,
  loadLocalDesktopSettings
} from '../utils/desktopSettings'
import type {
  BackupDiffEntry,
  BackupSummary,
  CoverBaselineStats,
  CuratorItem,
  DesktopRestoreStrategy,
  LegacyDiscovery,
  LegacyImportReport,
  VaultFileRow,
  VaultRootRow
} from '../types'

interface VaultAndSyncTabProps {
  studioDataDir: string
  legacyDiscovery: LegacyDiscovery
  vaultRoots: VaultRootRow[]
  items: CuratorItem[]
  updater: DesktopUpdaterController
  onRefreshAll: () => Promise<void>
  onPlayVaultFile: (file: VaultFileRow, item: CuratorItem | null) => Promise<void>
}

function diffBadgeLabel(diffType: BackupDiffEntry['diffType']): { label: string; className: string } {
  switch (diffType) {
    case 'changed':
      return { label: '内容冲突', className: 'badge badge-accent' }
    case 'only_remote':
      return { label: '备份包新增', className: 'badge badge-vault' }
    case 'only_local':
      return { label: '仅本机存在', className: 'badge badge-mobile' }
  }
}

function summarizeDiffNotice(sourceLabel: string, diffs: BackupDiffEntry[]): string {
  const changed = diffs.filter((d) => d.diffType === 'changed').length
  const onlyRemote = diffs.filter((d) => d.diffType === 'only_remote').length
  const onlyLocal = diffs.filter((d) => d.diffType === 'only_local').length
  if (changed === 0 && onlyLocal === 0 && onlyRemote === 0) {
    return `${sourceLabel} 对比完成，本机藏品与备份一致`
  }
  if (changed === 0 && onlyLocal === 0) {
    return `${sourceLabel} 对比完成，备份中有 ${onlyRemote} 项可以导入`
  }
  if (changed === 0) {
    return `${sourceLabel} 对比完成，备份中独有 ${onlyRemote} 项，本机独有 ${onlyLocal} 项`
  }
  return `${sourceLabel} 对比完成，发现 ${changed} 项差异（备份中独有 ${onlyRemote} 项，本机独有 ${onlyLocal} 项）`
}

export function VaultAndSyncTab({
  studioDataDir,
  legacyDiscovery,
  vaultRoots,
  items,
  updater,
  onRefreshAll,
  onPlayVaultFile
}: VaultAndSyncTabProps) {
  const dialog = useStudioDialog()
  const [allFiles, setAllFiles] = useState<VaultFileRow[]>([])
  const [coverStats, setCoverStats] = useState<CoverBaselineStats | null>(null)
  const [cleaningCovers, setCleaningCovers] = useState(false)
  const [mountKind, setMountKind] = useState<string>('mixed')

  useEffect(() => {
    studioApi.getCoverBaselineStats().then(setCoverStats).catch((err) => {
      console.warn('[VaultAndSyncTab] 获取封面存储统计失败:', err)
    })
  }, [items])

  const handleCleanupOrphanCovers = async () => {
    if (cleaningCovers) return
    setCleaningCovers(true)
    try {
      const scan = await studioApi.cleanupOrphanCovers(true)
      if (scan.orphanFiles.length === 0) {
        setNotice(scan.message)
        return
      }
      const ok = await dialog.confirm({
        title: '确认清理无引用本地封面',
        message: `${scan.message}\n仍被作品、回收站或可撤销提案引用的 ${scan.protectedFiles} 张封面已受保护，不会被删除。`,
        confirmText: '确认清理',
        cancelText: '取消'
      })
      if (!ok) return
      const res = await studioApi.cleanupOrphanCovers(false)
      setNotice(res.message)
      const nextStats = await studioApi.getCoverBaselineStats().catch(() => null)
      if (nextStats) setCoverStats(nextStats)
    } catch (e) {
      setErrorMsg(e instanceof Error ? e.message : String(e))
    } finally {
      setCleaningCovers(false)
    }
  }
  const [includeCovers, setIncludeCovers] = useState<boolean>(
    () => loadLocalDesktopSettings().cloudIncludeCovers
  )
  const [defaultRestoreStrategy, setDefaultRestoreStrategy] = useState<DesktopRestoreStrategy>(
    () => loadLocalDesktopSettings().defaultRestoreStrategy
  )

  const handleToggleIncludeCovers = (next: boolean) => {
    setIncludeCovers(next)
    try {
      const current = loadLocalDesktopSettings()
      const updated = { ...current, cloudIncludeCovers: next }
      localStorage.setItem('animeow.studio.desktopSettings.v1', JSON.stringify(updated))
      window.dispatchEvent(new CustomEvent(DESKTOP_SETTINGS_EVENT, { detail: updated }))
      void studioApi.saveDesktopSettings(updated).catch((err) => {
        console.warn('[VaultAndSyncTab] 保存云同步封面偏好失败:', err)
      })
    } catch {
      // ignore localStorage errors
    }
  }

  useEffect(() => {
    const onSettingsChanged = (e: Event) => {
      const detail = (e as CustomEvent).detail
      if (detail && typeof detail.cloudIncludeCovers === 'boolean') {
        setIncludeCovers(detail.cloudIncludeCovers)
      }
      if (
        detail &&
        (detail.defaultRestoreStrategy === 'MERGE_KEEP_LOCAL' ||
          detail.defaultRestoreStrategy === 'REPLACE')
      ) {
        setDefaultRestoreStrategy(detail.defaultRestoreStrategy)
      }
    }
    window.addEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
    return () => window.removeEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
  }, [])
  const [inspectedZipPath, setInspectedZipPath] = useState<string | null>(null)
  const [inspectedSourceLabel, setInspectedSourceLabel] = useState<string | null>(null)
  const [inspectedSummary, setInspectedSummary] = useState<BackupSummary | null>(null)
  const [diffEntries, setDiffEntries] = useState<BackupDiffEntry[]>([])
  const [diffFilter, setDiffFilter] = useState<'all' | 'changed' | 'only_remote' | 'only_local'>('all')
  const [showAllDiffRows, setShowAllDiffRows] = useState<boolean>(false)
  const [legacyReport, setLegacyReport] = useState<LegacyImportReport | null>(null)
  const [busyMessage, setBusyMessage] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const reconcilerSectionRef = useRef<HTMLElement | null>(null)

  const reloadFiles = async () => {
    const rows = await studioApi.listVaultFiles()
    setAllFiles(rows)
  }

  useEffect(() => {
    reloadFiles().catch((err) => {
      console.warn('[VaultAndSyncTab] 读取本机媒体文件索引失败:', err)
      setErrorMsg(`读取本机媒体文件索引失败：${err instanceof Error ? err.message : String(err)}`)
    })
  }, [vaultRoots])

  const runTask = async (label: string, fn: () => Promise<void>) => {
    setBusyMessage(label)
    setErrorMsg(null)
    try {
      await fn()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMessage(null)
    }
  }

  const handleImportLegacy = () =>
    runTask('正在以只读快照方式复制旧版 Electron 资料库...', async () => {
      const report = await studioApi.importLegacyCopy()
      setLegacyReport(report)
      await onRefreshAll()
      setNotice(
        `已完成旧版只读副本迁移：动画 ${report.animeCount} · 书籍 ${report.bookCount} · 游戏 ${report.gameCount} · 音声 ${report.audioCount} · 封面 ${report.copiedCovers} 张`
      )
    })

  const inspectAndDiffZipPath = async (zipPath: string, sourceLabel: string) => {
    const [summary, diffs] = await Promise.all([
      studioApi.inspectBackup(zipPath),
      studioApi.previewBackupDiff(zipPath)
    ])
    setInspectedZipPath(zipPath)
    setInspectedSourceLabel(sourceLabel)
    setInspectedSummary(summary)
    setDiffEntries(diffs)
    const hasConflict = diffs.some((d) => d.diffType === 'changed')
    setDiffFilter(hasConflict ? 'changed' : 'all')
    setShowAllDiffRows(false)
    setNotice(summarizeDiffNotice(sourceLabel, diffs))
    window.requestAnimationFrame(() => {
      reconcilerSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }

  const handleInspectZip = () =>
    runTask('正在校验备份并对比差异...', async () => {
      const picked = await studioApi.pickBackupZip()
      if (!picked) return
      await inspectAndDiffZipPath(picked, '本地备份')
    })

  const handleRestoreZip = async (strategy: 'MERGE_KEEP_LOCAL' | 'REPLACE') => {
    if (!inspectedZipPath) return
    if (strategy === 'REPLACE') {
      const ok = await dialog.confirm({
        title: '确认按备份包全量覆盖本地藏品库？',
        message:
          '将使用该备份包完整覆盖本地藏品库（覆盖前会自动生成一份本机回滚快照）。如需保留本机现有进度并只补充新增项，请选择「保留本地并合并」。',
        confirmText: '确认全量覆盖',
        cancelText: '取消',
        tone: 'warning'
      })
      if (!ok) return
    }
    await runTask(
      strategy === 'MERGE_KEEP_LOCAL'
        ? '正在执行保留本机优先合并导入...'
        : '正在创建回滚快照并执行全量覆盖恢复...',
      async () => {
        const res = await studioApi.restoreBackup(inspectedZipPath, strategy)
        await onRefreshAll()
        const updatedDiffs = await studioApi.previewBackupDiff(inspectedZipPath)
        setDiffEntries(updatedDiffs)
        setNotice(
          `恢复完成，已自动保存恢复前的备份。目前有 ${res.summary.animeCount} 部动画、${res.summary.bookCount} 本书籍、${res.summary.gameCount} 个游戏和 ${res.summary.audioCount} 部音声。`
        )
      }
    )
  }

  const handleExportZip = () =>
    runTask('正在生成跨端纯净 .animeow.zip 原生备份...', async () => {
      const dateTag = new Date().toISOString().slice(0, 10)
      const savePath = await studioApi.pickSaveZip(`animeow-backup-${dateTag}.animeow.zip`)
      if (!savePath) return
      const summary = await studioApi.exportBackup(savePath, includeCovers)
      setNotice(`已导出备份，包含 ${summary.coverCount} 张封面`)
    })

  const handleMountDirectory = () =>
    runTask('正在扫描本机目录并执行正则番剧/RJ号关联...', async () => {
      const dir = await studioApi.pickDirectory('选择要挂载到追番喵的本机番剧或音声目录')
      if (!dir) return
      const root = await studioApi.mountScanVault(dir, mountKind)
      await onRefreshAll()
      await reloadFiles()
      setNotice(`已挂载并索引目录「${root.label}」：共发现 ${root.fileCount} 个媒体文件`)
    })

  const handleRescanRoot = (root: VaultRootRow) =>
    runTask(`正在增量重扫「${root.label}」...`, async () => {
      const updated = await studioApi.mountScanVault(root.path, root.mediaKind)
      await onRefreshAll()
      await reloadFiles()
      setNotice(`重扫完成：目录内共 ${updated.fileCount} 个媒体文件`)
    })

  const handleBindChange = async (file: VaultFileRow, encodedTarget: string) => {
    if (!encodedTarget) {
      await studioApi.bindVaultFile(file.id, null, null)
    } else {
      const [kind, rawId] = encodedTarget.split(':')
      await studioApi.bindVaultFile(file.id, kind, Number.parseInt(rawId, 10))
    }
    await reloadFiles()
    await onRefreshAll()
  }

  return (
    <div style={{ height: '100%', overflowY: 'auto', padding: 18, userSelect: 'text' }}>
      <div style={{ maxWidth: 1080, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
        {busyMessage && <WaitingBanner message={busyMessage} />}
        {!busyMessage && (notice || errorMsg) && (
          <div
            className="card-box"
            style={{
              borderColor: errorMsg ? 'var(--danger)' : 'var(--positive)'
            }}
          >
            {notice && !errorMsg && (
              <div style={{ color: 'var(--positive)', fontWeight: 600 }}>{notice}</div>
            )}
            {errorMsg && <div style={{ color: 'var(--danger)', fontWeight: 600 }}>操作未完成：{errorMsg}</div>}
          </div>
        )}

        {/* M3：追番喵云账号、云端备份列表与 Bangumi OAuth 双向对比同步 */}
        <CloudSyncPanels
          items={items}
          includeCovers={includeCovers}
          onToggleIncludeCovers={handleToggleIncludeCovers}
          busyMessage={busyMessage}
          updater={updater}
          runTask={runTask}
          defaultRestoreStrategy={defaultRestoreStrategy}
          onNotice={setNotice}
          onRefreshAll={onRefreshAll}
          onFeedBackupToReconciler={inspectAndDiffZipPath}
        />

        {/* 模块一：Android / 跨端原生备份三栏差异对账与互通 */}
        <section className="card-box" ref={reconcilerSectionRef}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <div>
              <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
                本地备份与恢复 (.animeow.zip)
              </div>
              <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', marginTop: 2 }}>
                与 Android 手机端备份包完全互通；导入前会清晰列出差异供你确认，恢复前自动留存回滚点。
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            <button className="btn btn-primary" disabled={Boolean(busyMessage)} onClick={handleInspectZip}>
              导入 .animeow.zip 备份包...
            </button>
            <button className="btn" disabled={Boolean(busyMessage)} onClick={handleExportZip}>
              导出备份包 (.animeow.zip)...
            </button>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: '0.9231rem' }}>
              <input
                type="checkbox"
                checked={includeCovers}
                onChange={(e) => handleToggleIncludeCovers(e.target.checked)}
              />
              导出时打包本地封面图片
            </label>
          </div>

          {coverStats && (
            <div
              style={{
                marginTop: 10,
                padding: '8px 10px',
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--border-subtle)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 10,
                flexWrap: 'wrap',
                fontSize: '0.9231rem',
                color: 'var(--text-secondary)'
              }}
            >
              <span>
                本地封面图片：<strong>{coverStats.nativeDirFileCount}</strong> 张（占用{' '}
                <strong>{formatBytes(coverStats.nativeDirTotalBytes)}</strong>）· 远程引用{' '}
                {coverStats.remoteCount} · 用户手传 {coverStats.localUserCustomCount} · 自动落盘{' '}
                {coverStats.localExternalNativeCount}
                {coverStats.orphanFileCount > 0
                  ? ` · 可清理孤儿 ${coverStats.orphanFileCount} 张（${formatBytes(coverStats.orphanTotalBytes)}）`
                  : ''}
              </span>
              {coverStats.orphanFileCount > 0 && (
                <button
                  type="button"
                  className="btn"
                  style={{ height: 26, fontSize: '0.8846rem' }}
                  disabled={cleaningCovers}
                  onClick={handleCleanupOrphanCovers}
                >
                  {cleaningCovers ? '清理中…' : '清理孤儿封面'}
                </button>
              )}
            </div>
          )}

          {inspectedSummary && inspectedZipPath && (
            <div
              style={{
                marginTop: 14,
                padding: 12,
                borderRadius: 'var(--radius-card)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--border-strong)'
              }}
            >
              <div className="heading-font" style={{ fontWeight: 700, marginBottom: 4 }}>
                {inspectedSourceLabel || '备份'}预览
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
                <span className="badge mono">动画 {inspectedSummary.animeCount}</span>
                <span className="badge mono">书籍 {inspectedSummary.bookCount}</span>
                <span className="badge mono">游戏 {inspectedSummary.gameCount}</span>
                <span className="badge mono">音声 {inspectedSummary.audioCount}</span>
                <span className="badge mono">角色 {inspectedSummary.characterCount}</span>
                <span className="badge mono">封面 {inspectedSummary.coverCount}</span>
                <span className={`badge${inspectedSummary.fingerprintMatches === true ? ' badge-vault' : ''}`}>
                  {inspectedSummary.fingerprintMatches === true
                    ? '备份校验通过'
                    : inspectedSummary.fingerprintMatches === false
                      ? '备份校验失败'
                      : '旧备份缺少校验信息'}
                </span>
              </div>

              {/* 三栏对账预览：明确区分「内容冲突」与「本机未收录的新增导入」 */}
              {(() => {
                const changedCount = diffEntries.filter((d) => d.diffType === 'changed').length
                const onlyRemoteCount = diffEntries.filter((d) => d.diffType === 'only_remote').length
                const onlyLocalCount = diffEntries.filter((d) => d.diffType === 'only_local').length
                const visibleDiffs =
                  diffFilter === 'all'
                    ? diffEntries
                    : diffEntries.filter((d) => d.diffType === diffFilter)
                const slicedDiffs = showAllDiffRows ? visibleDiffs : visibleDiffs.slice(0, 120)

                return (
                  <div style={{ marginBottom: 12 }}>
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 8,
                        flexWrap: 'wrap',
                        marginBottom: 8
                      }}
                    >
                      <div style={{ fontWeight: 700, fontSize: '0.9615rem' }}>
                        {diffEntries.length === 0
                          ? '对账结果：本机与备份包完全一致'
                          : changedCount === 0 && onlyLocalCount === 0
                            ? `导入预览：无内容冲突 · 本机将新增 ${onlyRemoteCount} 项藏品`
                            : changedCount === 0
                              ? `对账结果：无内容冲突（备份包新增 ${onlyRemoteCount} 项 · 仅本机 ${onlyLocalCount} 项）`
                              : `三栏对账单：检出 ${changedCount} 项内容冲突（另含 ${onlyRemoteCount} 项新增 · ${onlyLocalCount} 项仅本机）`}
                      </div>
                      {diffEntries.length > 0 && (
                        <div className="segmented-group">
                          <button
                            className={`segmented-btn ${diffFilter === 'all' ? 'active' : ''}`}
                            onClick={() => setDiffFilter('all')}
                            style={{ padding: '3px 8px', fontSize: '0.8462rem' }}
                          >
                            全部 {diffEntries.length}
                          </button>
                          <button
                            className={`segmented-btn ${diffFilter === 'changed' ? 'active' : ''}`}
                            onClick={() => setDiffFilter('changed')}
                            style={{ padding: '3px 8px', fontSize: '0.8462rem' }}
                          >
                            内容冲突 {changedCount}
                          </button>
                          <button
                            className={`segmented-btn ${diffFilter === 'only_remote' ? 'active' : ''}`}
                            onClick={() => setDiffFilter('only_remote')}
                            style={{ padding: '3px 8px', fontSize: '0.8462rem' }}
                          >
                            备份包新增 {onlyRemoteCount}
                          </button>
                          <button
                            className={`segmented-btn ${diffFilter === 'only_local' ? 'active' : ''}`}
                            onClick={() => setDiffFilter('only_local')}
                            style={{ padding: '3px 8px', fontSize: '0.8462rem' }}
                          >
                            仅本机 {onlyLocalCount}
                          </button>
                        </div>
                      )}
                    </div>

                    {changedCount === 0 && onlyRemoteCount > 0 && (
                      <div
                        style={{
                          padding: '8px 10px',
                          marginBottom: 8,
                          borderRadius: 'var(--radius-sm)',
                          background: 'var(--bg-surface)',
                          border: '1px solid var(--border-subtle)',
                          color: 'var(--positive)',
                          fontSize: '0.9231rem'
                        }}
                      >
                        {onlyLocalCount === 0
                          ? `本机当前未收录这 ${onlyRemoteCount} 项藏品，属于安全的新增导入，不存在数据冲突，可直接点击下方按钮导入。`
                          : `双方共有条目完全一致，无内容冲突。点击「合并导入」会安全补充这 ${onlyRemoteCount} 项新增并保留本机独有的 ${onlyLocalCount} 项。`}
                      </div>
                    )}

                    {diffEntries.length === 0 ? (
                      <div
                        style={{
                          padding: '8px 10px',
                          border: '1px solid var(--border-subtle)',
                          borderRadius: 'var(--radius-sm)',
                          color: 'var(--positive)',
                          fontSize: '0.9231rem'
                        }}
                      >
                        本机桌面库与该备份包的核心藏品进度、状态及评分完全一致。
                      </div>
                    ) : visibleDiffs.length === 0 ? (
                      <div
                        style={{
                          padding: '8px 10px',
                          border: '1px solid var(--border-subtle)',
                          borderRadius: 'var(--radius-sm)',
                          color: 'var(--text-muted)',
                          fontSize: '0.9231rem'
                        }}
                      >
                        当前筛选分类下暂无条目。
                      </div>
                    ) : (
                      <div
                        style={{
                          maxHeight: 240,
                          overflowY: 'auto',
                          border: '1px solid var(--border-subtle)',
                          borderRadius: 'var(--radius-sm)'
                        }}
                      >
                        <table className="curator-table">
                          <thead>
                            <tr>
                              <th style={{ width: '36%' }}>藏品条目</th>
                              <th style={{ width: '32%' }}>本机桌面库</th>
                              <th style={{ width: '32%' }}>传入备份包</th>
                            </tr>
                          </thead>
                          <tbody>
                            {slicedDiffs.map((d, idx) => {
                              const tag = diffBadgeLabel(d.diffType)
                              return (
                                <tr key={`${d.kind}:${d.title}:${idx}`}>
                                  <td title={d.title}>
                                    <span className={tag.className} style={{ marginRight: 6 }}>
                                      {tag.label}
                                    </span>
                                    <span className="badge" style={{ marginRight: 6 }}>
                                      {formatCarrierLabel(d.kind as Parameters<typeof formatCarrierLabel>[0])}
                                    </span>
                                    <strong className="heading-font">{d.title}</strong>
                                  </td>
                                  <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                                    {d.localSummary}
                                  </td>
                                  <td className="mono" style={{ color: 'var(--text-primary)', fontWeight: 600 }}>
                                    {d.remoteSummary}
                                  </td>
                                </tr>
                              )
                            })}
                          </tbody>
                        </table>
                        {!showAllDiffRows && visibleDiffs.length > 120 && (
                          <div style={{ padding: 8, textAlign: 'center', borderTop: '1px solid var(--border-subtle)' }}>
                            <button className="btn" onClick={() => setShowAllDiffRows(true)}>
                              展开剩余 {visibleDiffs.length - 120} 条预览
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )
              })()}

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
                  gap: 10,
                  marginBottom: 10
                }}
              >
                <div
                  style={{
                    padding: '9px 11px',
                    borderRadius: 'var(--radius-sm)',
                    border:
                      defaultRestoreStrategy === 'MERGE_KEEP_LOCAL'
                        ? '1px solid var(--accent)'
                        : '1px solid var(--border-subtle)',
                    background:
                      defaultRestoreStrategy === 'MERGE_KEEP_LOCAL'
                        ? 'var(--accent-soft)'
                        : 'var(--bg-surface)',
                    fontSize: '0.923rem',
                    lineHeight: 1.55
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 8,
                      fontWeight: 700,
                      color: 'var(--accent)',
                      marginBottom: 3
                    }}
                  >
                    <span>方案一 · 本地优先（安全合并）</span>
                    {defaultRestoreStrategy === 'MERGE_KEEP_LOCAL' && (
                      <span className="badge badge-accent">默认策略</span>
                    )}
                  </div>
                  <div style={{ color: 'var(--text-secondary)' }}>
                    发生「内容冲突」时以<strong>本机现有进度、状态与评分为准</strong>（不覆盖本机）；仅补充「备份包新增」条目与缺失字段，保留本机独有藏品。
                  </div>
                </div>
                <div
                  style={{
                    padding: '9px 11px',
                    borderRadius: 'var(--radius-sm)',
                    border:
                      defaultRestoreStrategy === 'REPLACE'
                        ? '1px solid var(--warning)'
                        : '1px solid var(--border-subtle)',
                    background:
                      defaultRestoreStrategy === 'REPLACE'
                        ? 'var(--badge-amber-bg)'
                        : 'var(--bg-surface)',
                    fontSize: '0.923rem',
                    lineHeight: 1.55
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 8,
                      fontWeight: 700,
                      color: 'var(--warning)',
                      marginBottom: 3
                    }}
                  >
                    <span>方案二 · 云端 / 备份覆盖本机（全量覆盖）</span>
                    {defaultRestoreStrategy === 'REPLACE' && (
                      <span className="badge badge-accent">默认策略</span>
                    )}
                  </div>
                  <div style={{ color: 'var(--text-secondary)' }}>
                    发生「内容冲突」时以<strong>传入备份包（云端）为准</strong>覆盖本机，并将本机同步为与备份包完全一致（覆盖前自动生成本机回滚快照）。
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                {defaultRestoreStrategy === 'REPLACE' ? (
                  <>
                    <button
                      className="btn btn-primary"
                      disabled={Boolean(busyMessage)}
                      onClick={() => void handleRestoreZip('REPLACE')}
                    >
                      备份优先 · 覆盖本机（默认策略）
                    </button>
                    <button
                      className="btn"
                      disabled={Boolean(busyMessage)}
                      onClick={() => void handleRestoreZip('MERGE_KEEP_LOCAL')}
                    >
                      本地优先 · 合并导入（保留本机进度）
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      className="btn btn-primary"
                      disabled={Boolean(busyMessage)}
                      onClick={() => void handleRestoreZip('MERGE_KEEP_LOCAL')}
                    >
                      本地优先 · 合并导入（默认策略）
                    </button>
                    <button
                      className="btn"
                      disabled={Boolean(busyMessage)}
                      onClick={() => void handleRestoreZip('REPLACE')}
                    >
                      备份优先 · 覆盖本机（自动生成回滚快照）
                    </button>
                  </>
                )}
                <span style={{ fontSize: '0.846rem', color: 'var(--text-muted)' }}>
                  默认策略可在「设置 → 同步与云端」更改
                </span>
              </div>
            </div>
          )}
        </section>

        {/* 模块二：本机媒体文件夹 */}
        <section className="card-box">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <div>
              <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
                本机媒体文件夹
              </div>
              <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', marginTop: 2 }}>
                添加电脑硬盘中的番剧下载文件夹或音声目录，自动识别集数、同名字幕 (<code>.srt/.vtt</code>) 与台本 (<code>.lrc/.txt</code>)，便于一键播放。
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 10, marginBottom: 12 }}>
            <StudioSelect
              className="studio-select-inline"
              value={mountKind}
              onChange={(val) => setMountKind(val)}
              ariaLabel="本机媒体目录类型"
              minMenuWidth={230}
              maxMenuWidth={300}
              options={[
                {
                  value: 'mixed',
                  label: '混合目录（番剧视频 + 音声）',
                  sublabel: '自动扫描视频、音声、同名字幕与台本'
                },
                {
                  value: 'anime',
                  label: '番剧视频目录 (.mp4/.mkv/.webm)'
                },
                {
                  value: 'audio',
                  label: '音声仓库目录 (.flac/.mp3/.wav)'
                }
              ]}
            />
            <button className="btn btn-primary" disabled={Boolean(busyMessage)} onClick={handleMountDirectory}>
              挂载本机媒体文件夹并扫描...
            </button>
          </div>

          {vaultRoots.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 14 }}>
              {vaultRoots.map((r) => (
                <div
                  key={r.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '7px 10px',
                    borderRadius: 'var(--radius-sm)',
                    background: 'var(--bg-canvas)',
                    border: '1px solid var(--border-subtle)'
                  }}
                >
                  <div>
                    <strong className="heading-font">{r.label}</strong>
                    <span className="mono" style={{ marginLeft: 8, color: 'var(--text-muted)' }}>
                      {r.path}
                    </span>
                    <span className="badge badge-vault mono" style={{ marginLeft: 8 }}>
                      {r.fileCount} 个媒体文件
                    </span>
                  </div>
                  <button
                    className="btn"
                    disabled={Boolean(busyMessage)}
                    onClick={() => handleRescanRoot(r)}
                  >
                    重新扫描
                  </button>
                </div>
              ))}
            </div>
          )}

          {allFiles.length > 0 && (
            <div
              style={{
                maxHeight: 340,
                overflow: 'auto',
                border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-sm)'
              }}
            >
              <table className="curator-table">
                <thead>
                  <tr>
                    <th style={{ width: '34%' }}>本地文件名</th>
                    <th style={{ width: 76 }}>体积</th>
                    <th style={{ width: '20%' }}>自动解析线索</th>
                    <th style={{ width: '26%' }}>绑定至藏品条目</th>
                    <th style={{ width: 96 }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {allFiles.map((f) => {
                    const boundValue =
                      f.boundKind && f.boundWorkId != null ? `${f.boundKind}:${f.boundWorkId}` : ''
                    const matchedItem =
                      items.find((it) => `${it.kind}:${it.id}` === boundValue) ?? null
                    return (
                      <tr key={f.id}>
                        <td className="mono" title={f.filePath}>
                          {f.fileName}
                        </td>
                        <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                          {formatBytes(f.sizeBytes)}
                        </td>
                        <td>
                          {f.parsedRjCode && <span className="badge mono">{f.parsedRjCode}</span>}{' '}
                          {f.parsedEpisode != null && <span className="badge mono">EP {f.parsedEpisode}</span>}{' '}
                          {f.subtitlePath && <span className="badge badge-vault">字幕</span>}{' '}
                          {f.scriptPath && <span className="badge badge-vault">台本</span>}
                          {!f.parsedRjCode && f.parsedEpisode == null && (
                            <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                              {f.parsedTitle || '—'}
                            </span>
                          )}
                        </td>
                        <td>
                          <StudioSelect
                            className="studio-select-inline studio-select-full"
                            value={boundValue}
                            onChange={(val) => void handleBindChange(f, val)}
                            ariaLabel={`绑定 ${f.fileName} 至藏品`}
                            minMenuWidth={220}
                            maxMenuWidth={320}
                            options={[
                              { value: '', label: '— 未绑定藏品 —' },
                              ...items.map((it) => ({
                                value: `${it.kind}:${it.id}`,
                                label: it.title,
                                badges: [formatCarrierLabel(it.kind)]
                              }))
                            ]}
                          />
                        </td>
                        <td>
                          <button
                            className="btn btn-primary"
                            onClick={() => onPlayVaultFile(f, matchedItem)}
                          >
                            内置播放
                          </button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* 高级工具（默认折叠）：数据目录与旧版桌面端迁移 */}
        <details className="card-box" style={{ padding: '10px 14px' }}>
          <summary
            style={{
              cursor: 'pointer',
              fontSize: '0.9615rem',
              fontWeight: 600,
              color: 'var(--text-secondary)',
              userSelect: 'none'
            }}
          >
            更多选项：数据存储位置与旧版桌面端迁移
            {legacyDiscovery.databaseExists && (
              <span className="badge badge-vault" style={{ marginLeft: 8 }}>
                检测到旧版资料库
              </span>
            )}
          </summary>
          <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 10, fontSize: '0.9231rem' }}>
            <div style={{ color: 'var(--text-muted)' }}>
              当前数据目录：<span className="mono">{studioDataDir}</span>
            </div>
            <div
              style={{
                padding: 10,
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ fontWeight: 600, marginBottom: 4 }}>从旧版追番喵桌面端导入资料</div>
              <div style={{ color: 'var(--text-secondary)', marginBottom: 8 }}>
                只读复制旧版数据，不会改动原版软件中的任何内容。
              </div>
              {legacyDiscovery.databaseExists ? (
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
                  <span className="badge mono">动画 {legacyDiscovery.animeCount}</span>
                  <span className="badge mono">书籍 {legacyDiscovery.bookCount}</span>
                  <span className="badge mono">游戏 {legacyDiscovery.gameCount}</span>
                  <span className="badge mono">音声 {legacyDiscovery.audioCount}</span>
                  <span className="badge mono">封面 {legacyDiscovery.coverFileCount}</span>
                </div>
              ) : (
                <div style={{ color: 'var(--text-muted)', marginBottom: 8 }}>
                  当前电脑未检测到旧版桌面端资料库。
                </div>
              )}
              <button
                className="btn"
                disabled={!legacyDiscovery.databaseExists || Boolean(busyMessage)}
                onClick={handleImportLegacy}
              >
                一键导入旧版资料
              </button>
              {legacyReport && (
                <span style={{ marginLeft: 10, color: 'var(--positive)' }}>
                  已同步封面 {legacyReport.copiedCovers} 张
                </span>
              )}
            </div>
          </div>
        </details>
      </div>
    </div>
  )
}
