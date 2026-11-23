import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { formatBytes, studioApi } from '../api'
import type { DesktopUpdaterController } from '../hooks/useDesktopUpdater'
import { DesktopUpdateCard } from './DesktopUpdateCard'
import { LoadingSpinner } from './LoadingIndicator'
import type {
  BangumiAuthorizeStart,
  BangumiDiffRow,
  CloudBackupRow,
  CuratorItem,
  DesktopRestoreStrategy,
  PublicCloudSessionInfo
} from '../types'

interface CloudSyncPanelsProps {
  items: CuratorItem[]
  includeCovers: boolean
  onToggleIncludeCovers: (next: boolean) => void
  defaultRestoreStrategy: DesktopRestoreStrategy
  busyMessage: string | null
  updater: DesktopUpdaterController
  runTask: (label: string, fn: () => Promise<void>) => Promise<void>
  onNotice: (message: string) => void
  onRefreshAll: () => Promise<void>
  /** 将云端下载的 .animeow.zip 暂存路径直接喂给页面现有的三栏差异对账器 */
  onFeedBackupToReconciler: (zipPath: string, sourceLabel: string) => Promise<void>
}

const LOGGED_OUT_SESSION: PublicCloudSessionInfo = {
  loggedIn: false,
  userId: null,
  username: null,
  expiresAt: null,
  bangumi: null
}

function formatUnixExpiry(expiresAt: number | null): string {
  if (!expiresAt) return '未记录有效期'
  const ms = expiresAt > 1e12 ? expiresAt : expiresAt * 1000
  const d = new Date(ms)
  if (Number.isNaN(d.getTime())) return '未记录有效期'
  return d.toLocaleString('zh-CN', { hour12: false })
}

function formatBackupTime(row: CloudBackupRow): string {
  return (row.createdAt || row.uploadDate || '上传时间未知').slice(0, 19).replace('T', ' ')
}

function bangumiDiffBadge(kind: BangumiDiffRow['diffKind']): { label: string; className: string } {
  switch (kind) {
    case 'changed':
      return { label: '进度/评分差异', className: 'badge badge-accent' }
    case 'only_remote':
      return { label: '仅 Bangumi 收录', className: 'badge badge-vault' }
    case 'only_local':
      return { label: '仅本机收录', className: 'badge badge-mobile' }
  }
}

/**
 * M3 云同步三大工作面：追番喵云账号与更新检查、云端 .animeow.zip 备份列表、Bangumi OAuth 双向对比同步。
 */
export function CloudSyncPanels({
  items,
  includeCovers,
  onToggleIncludeCovers,
  defaultRestoreStrategy,
  busyMessage,
  updater,
  runTask,
  onNotice,
  onRefreshAll,
  onFeedBackupToReconciler
}: CloudSyncPanelsProps) {
  const [apiBase, setApiBase] = useState<string>('https://www.xunlys.top')
  const [apiBaseDraft, setApiBaseDraft] = useState<string>('https://www.xunlys.top')
  const [showAdvancedEndpoint, setShowAdvancedEndpoint] = useState<boolean>(false)

  const [session, setSession] = useState<PublicCloudSessionInfo>(LOGGED_OUT_SESSION)
  const [authMode, setAuthMode] = useState<'login' | 'register'>('login')
  const [usernameInput, setUsernameInput] = useState<string>('')
  const [passwordInput, setPasswordInput] = useState<string>('')

  const [cloudBackups, setCloudBackups] = useState<CloudBackupRow[]>([])
  const [backupsLoaded, setBackupsLoaded] = useState<boolean>(false)
  const [backupsLoadError, setBackupsLoadError] = useState<string | null>(null)
  const [refreshingSession, setRefreshingSession] = useState<boolean>(false)
  const [confirmDeleteBackupId, setConfirmDeleteBackupId] = useState<number | null>(null)

  // Bangumi OAuth & 双向同步状态
  const [bgmAuthStart, setBgmAuthStart] = useState<BangumiAuthorizeStart | null>(null)
  const [bgmCallbackInput, setBgmCallbackInput] = useState<string>('')
  const [confirmUnbindBgm, setConfirmUnbindBgm] = useState<boolean>(false)
  const [bgmDiffs, setBgmDiffs] = useState<BangumiDiffRow[]>([])
  const [bgmPreviewed, setBgmPreviewed] = useState<boolean>(false)
  const [bgmFilter, setBgmFilter] = useState<'all' | BangumiDiffRow['diffKind']>('all')
  const [selectedBgmSubjects, setSelectedBgmSubjects] = useState<Set<number>>(new Set())
  const [preferRemoteOnConflict, setPreferRemoteOnConflict] = useState<boolean>(
    () => defaultRestoreStrategy === 'REPLACE'
  )
  const bgmConflictManuallyToggledRef = useRef(false)

  useEffect(() => {
    if (!bgmConflictManuallyToggledRef.current) {
      setPreferRemoteOnConflict(defaultRestoreStrategy === 'REPLACE')
    }
  }, [defaultRestoreStrategy])

  const animeById = useMemo(() => {
    const map = new Map<number, CuratorItem>()
    for (const it of items) {
      if (it.kind === 'anime') map.set(it.id, it)
    }
    return map
  }, [items])

  const loadCloudBackups = useCallback(async () => {
    const rows = await studioApi.listCloudBackups()
    setCloudBackups(rows)
    setBackupsLoadError(null)
    setBackupsLoaded(true)
  }, [])

  useEffect(() => {
    let cancelled = false
    Promise.all([studioApi.getCloudConfig(), studioApi.getCloudSession()])
      .then(([cfg, sess]) => {
        if (cancelled) return
        setApiBase(cfg.apiBase)
        setApiBaseDraft(cfg.apiBase)
        setSession(sess)
        if (sess.loggedIn) {
          // 后台同步 /api/user/me，确保在手机端新绑定/解绑的 Bangumi 账号立即在桌面端生效
          setRefreshingSession(true)
          studioApi
            .refreshCloudSession()
            .then((fresh) => {
              if (!cancelled) setSession(fresh)
            })
            .catch((err) => {
              console.warn('[CloudSyncPanels] 刷新云端会话失败:', err)
            })
            .finally(() => {
              if (!cancelled) setRefreshingSession(false)
            })
          studioApi
            .listCloudBackups()
            .then((rows) => {
              if (!cancelled) {
                setCloudBackups(rows)
                setBackupsLoadError(null)
                setBackupsLoaded(true)
              }
            })
            .catch((err) => {
              console.warn('[CloudSyncPanels] 初始加载云端备份列表失败:', err)
              if (!cancelled) {
                setBackupsLoadError(err instanceof Error ? err.message : String(err))
                setBackupsLoaded(true)
              }
            })
        }
      })
      .catch((err) => {
        console.warn('[CloudSyncPanels] 读取本地云配置或会话失败:', err)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const handleSaveEndpoint = () =>
    runTask('正在校验并保存云端地址（公网强制 HTTPS）...', async () => {
      const updated = await studioApi.setCloudConfig(apiBaseDraft)
      setApiBase(updated.apiBase)
      setApiBaseDraft(updated.apiBase)
      setShowAdvancedEndpoint(false)
      onNotice(`云端服务地址已更新为：${updated.apiBase}`)
    })

  const handlePasswordAuth = () =>
    runTask(
      authMode === 'login' ? '正在登录追番喵云账号...' : '正在注册追番喵云账号...',
      async () => {
        const user = usernameInput.trim()
        if (!user || !passwordInput) {
          throw new Error('请填写完整的用户名与密码')
        }
        const info =
          authMode === 'login'
            ? await studioApi.cloudLogin(user, passwordInput)
            : await studioApi.cloudRegister(user, passwordInput)
        setSession(info)
        setPasswordInput('')
        onNotice(
          authMode === 'login'
            ? `已登录云账号「${info.username ?? user}」`
            : `账号「${info.username ?? user}」注册并登录成功`
        )
        await loadCloudBackups().catch((err) => {
          console.warn('[CloudSyncPanels] 登录后拉取云端备份失败:', err)
          setBackupsLoadError(err instanceof Error ? err.message : String(err))
          setBackupsLoaded(true)
        })
      }
    )

  const handleDeviceLogin = () =>
    runTask('正在使用本机设备登录追番喵云服务...', async () => {
      const info = await studioApi.cloudDeviceLogin()
      setSession(info)
      onNotice(`本机登录成功：${info.username ?? `UID ${info.userId}`}`)
      await loadCloudBackups().catch((err) => {
        console.warn('[CloudSyncPanels] 本机登录后拉取云端备份失败:', err)
        setBackupsLoadError(err instanceof Error ? err.message : String(err))
        setBackupsLoaded(true)
      })
    })

  const handleRefreshSession = () =>
    runTask('正在同步云账号与 Bangumi 绑定状态...', async () => {
      const info = await studioApi.refreshCloudSession()
      setSession(info)
      await loadCloudBackups()
      onNotice('云端会话与备份列表已刷新')
    })

  const handleLogout = () =>
    runTask('正在退出登录...', async () => {
      await studioApi.cloudLogout()
      setSession(LOGGED_OUT_SESSION)
      setCloudBackups([])
      setBackupsLoadError(null)
      setBackupsLoaded(false)
      setBgmDiffs([])
      setBgmPreviewed(false)
      setBgmAuthStart(null)
      onNotice('已退出云账号')
    })

  const handleUploadCloudBackup = () =>
    runTask(
      includeCovers
        ? '正在打包备份（含本地自定义封面）并上传至追番喵云端...'
        : '正在打包备份并上传至追番喵云端...',
      async () => {
        const uploaded = await studioApi.uploadCloudBackup(includeCovers)
        await loadCloudBackups()
        const covers = uploaded.coverCount ?? 0
        if (uploaded.coversDroppedDueToSize) {
          onNotice(
            `已上传云端备份（${formatBytes(uploaded.payloadSize)}）—— 因自定义封面总体积超过 28 MB 上限，已自动回退为不含封面上传；如需迁移完整封面请改用下方本地备份包`
          )
        } else if (includeCovers) {
          onNotice(
            `已上传云端备份（${formatBytes(uploaded.payloadSize)}，含 ${covers} 张自定义封面）`
          )
        } else {
          onNotice(`已上传云端备份（${formatBytes(uploaded.payloadSize)}，不含封面）`)
        }
      }
    )

  const handleDownloadAndDiff = (row: CloudBackupRow) =>
    runTask('正在下载云备份并对比差异...', async () => {
      const localZipPath = await studioApi.downloadCloudBackup(row.id, row.contentHash)
      await onFeedBackupToReconciler(localZipPath, `云端备份（${formatBackupTime(row)}）`)
    })

  const handleDeleteCloudBackup = (row: CloudBackupRow) =>
    runTask('正在删除云端备份...', async () => {
      await studioApi.deleteCloudBackup(row.id)
      setConfirmDeleteBackupId(null)
      await loadCloudBackups()
      onNotice(`已删除云端备份（${formatBackupTime(row)}）`)
    })

  const handleStartBgmOAuth = () =>
    runTask('正在准备 Bangumi 授权页面...', async () => {
      const start = await studioApi.startBangumiOAuth()
      setBgmAuthStart(start)
      await studioApi.openHttpsUrl(start.authorizeUrl)
      onNotice('已在系统浏览器打开 Bangumi 授权页，授权完成后请将回调链接或票据粘贴到下方输入框')
    })

  const handleCompleteBgmOAuth = () =>
    runTask('正在提交 Bangumi 授权回调并完成账号绑定...', async () => {
      const ticket = bgmCallbackInput.trim()
      if (!ticket) throw new Error('请粘贴浏览器授权后的完整回调 URL 或授权票据')
      const updated = await studioApi.completeBangumiOAuth(ticket, bgmAuthStart?.state ?? null)
      setSession(updated)
      setBgmCallbackInput('')
      setBgmAuthStart(null)
      onNotice(
        `Bangumi 账号已绑定：${updated.bangumi?.nickname || updated.bangumi?.username || '已连接'}`
      )
    })

  const handleUnbindBgm = () =>
    runTask('正在解除 Bangumi 账号绑定...', async () => {
      const updated = await studioApi.unbindBangumiOAuth()
      setSession(updated)
      setConfirmUnbindBgm(false)
      setBgmDiffs([])
      setBgmPreviewed(false)
      onNotice('已解除 Bangumi OAuth 绑定')
    })

  const handlePreviewBgmSync = () =>
    runTask('正在拉取 Bangumi 动画收藏并与本机库生成双向差异表...', async () => {
      const rows = await studioApi.previewBangumiSync()
      setBgmDiffs(rows)
      setBgmPreviewed(true)
      const defaultSelected = new Set<number>()
      for (const r of rows) {
        if (r.diffKind === 'only_remote' || r.diffKind === 'changed') {
          defaultSelected.add(r.subjectId)
        }
      }
      setSelectedBgmSubjects(defaultSelected)
      onNotice(`Bangumi 对比完成：共检出 ${rows.length} 项差异`)
    })

  const handlePullBgmSelected = () =>
    runTask('正在将选中的 Bangumi 收藏变更合并至本机...', async () => {
      const targetRows = bgmDiffs.filter(
        (r) => selectedBgmSubjects.has(r.subjectId) && r.diffKind !== 'only_local'
      )
      if (targetRows.length === 0) {
        throw new Error('请至少勾选一条「仅 Bangumi 收录」或「进度/评分差异」条目')
      }
      const affected = await studioApi.pullBangumiSync(targetRows, preferRemoteOnConflict)
      await onRefreshAll()
      const nextRows = await studioApi.previewBangumiSync()
      setBgmDiffs(nextRows)
      setSelectedBgmSubjects(new Set())
      onNotice(`已合并 ${affected} 条 Bangumi 动画记录至本机库，并刷新差异列表`)
    })

  const handlePushOneBgm = (row: BangumiDiffRow) =>
    runTask(`正在将《${row.title}》的本机进度与评分上报至 Bangumi...`, async () => {
      if (row.localAnimeId == null) {
        throw new Error('该条目尚未在本地建档，请先拉取到本地')
      }
      const localItem = animeById.get(row.localAnimeId)
      if (!localItem) {
        throw new Error('未在当前藏品列表中找到对应本地动画，请刷新后重试')
      }
      await studioApi.pushOneBangumiAnime(
        row.subjectId,
        localItem.status,
        localItem.progressPrimary,
        localItem.rawRating
      )
      const nextRows = await studioApi.previewBangumiSync()
      setBgmDiffs(nextRows)
      onNotice(`已将《${row.title}》上报同步至 Bangumi`)
    })

  const filteredBgmDiffs = useMemo(() => {
    if (bgmFilter === 'all') return bgmDiffs
    return bgmDiffs.filter((r) => r.diffKind === bgmFilter)
  }, [bgmDiffs, bgmFilter])

  const bgmCounts = useMemo(() => {
    let changed = 0
    let onlyRemote = 0
    let onlyLocal = 0
    for (const r of bgmDiffs) {
      if (r.diffKind === 'changed') changed++
      else if (r.diffKind === 'only_remote') onlyRemote++
      else onlyLocal++
    }
    return { changed, onlyRemote, onlyLocal }
  }, [bgmDiffs])

  const toggleBgmCheck = (subjectId: number) => {
    setSelectedBgmSubjects((prev) => {
      const next = new Set(prev)
      if (next.has(subjectId)) next.delete(subjectId)
      else next.add(subjectId)
      return next
    })
  }

  return (
    <>
      {/* 模块 A：追番喵云账号（Win32 DPAPI）与桌面端更新检查 */}
      <section className="card-box">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10 }}>
          <div>
            <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
              追番喵云账号与版本更新
            </div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', marginTop: 2 }}>
              登录后可与手机端同步云备份、发布长评到社区，以及使用 Bangumi 双向同步；登录凭据会在本机安全加密保存。
            </div>
          </div>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
            <span className={`badge ${session.loggedIn ? 'badge-vault' : ''}`}>
              {session.loggedIn ? `已登录 · ${session.username ?? `UID ${session.userId}`}` : '未登录'}
            </span>
            <button
              className="btn btn-ghost"
              onClick={() => setShowAdvancedEndpoint((v) => !v)}
              title="配置自定义云端服务地址（仅高级调试使用）"
            >
              {showAdvancedEndpoint ? '收起地址设置' : '服务地址'}
            </button>
          </div>
        </div>

        <div className="mono" style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', marginTop: 6 }}>
          当前云端服务源：{apiBase}
        </div>

        {showAdvancedEndpoint && (
          <div
            style={{
              marginTop: 10,
              padding: 10,
              borderRadius: 'var(--radius-sm)',
              background: 'var(--bg-canvas)',
              border: '1px solid var(--border-subtle)',
              display: 'flex',
              flexDirection: 'column',
              gap: 8
            }}
          >
            <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)' }}>
              公网域名/IP 强制要求 <code>https://</code>，仅本机（<code>localhost / 127.0.0.1</code>）或局域网调试环境允许 <code>http://</code>。
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <input
                className="inline-input mono"
                style={{ flex: 1, minWidth: 240 }}
                value={apiBaseDraft}
                onChange={(e) => setApiBaseDraft(e.target.value)}
                placeholder="https://www.xunlys.top"
              />
              <button
                className="btn btn-primary"
                disabled={Boolean(busyMessage)}
                onClick={() => void handleSaveEndpoint()}
              >
                保存服务地址
              </button>
              <button
                className="btn"
                disabled={Boolean(busyMessage)}
                onClick={() => setApiBaseDraft('https://www.xunlys.top')}
              >
                恢复默认
              </button>
            </div>
          </div>
        )}

        <div
          style={{
            marginTop: 12,
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1.35fr) minmax(0, 1fr)',
            gap: 12
          }}
        >
          {/* 左侧：账号登录/会话卡片 */}
          <div
            style={{
              padding: 12,
              borderRadius: 'var(--radius-sm)',
              background: 'var(--bg-canvas)',
              border: '1px solid var(--border-subtle)',
              display: 'flex',
              flexDirection: 'column',
              gap: 10
            }}
          >
            {session.loggedIn ? (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <div className="heading-font" style={{ fontWeight: 700, fontSize: '1.0769rem' }}>
                      {session.username ?? `用户 #${session.userId}`}
                    </div>
                    <div className="mono" style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', marginTop: 2 }}>
                      UID: {session.userId ?? '—'} · 会话有效期至：{formatUnixExpiry(session.expiresAt)}
                    </div>
                  </div>
                  <span className="badge badge-vault">已安全登录</span>
                </div>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <button
                    className="btn"
                    disabled={Boolean(busyMessage)}
                    onClick={() => void handleRefreshSession()}
                  >
                    刷新会话与云备份
                  </button>
                  <button
                    className="btn"
                    style={{ color: 'var(--danger)' }}
                    disabled={Boolean(busyMessage)}
                    onClick={() => void handleLogout()}
                  >
                    退出登录
                  </button>
                </div>
              </>
            ) : (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <div className="segmented-group">
                    <button
                      className={`segmented-btn ${authMode === 'login' ? 'active' : ''}`}
                      onClick={() => setAuthMode('login')}
                    >
                      账号密码登录
                    </button>
                    <button
                      className={`segmented-btn ${authMode === 'register' ? 'active' : ''}`}
                      onClick={() => setAuthMode('register')}
                    >
                      注册新账号
                    </button>
                  </div>
                  <button
                    className="btn btn-primary"
                    disabled={Boolean(busyMessage)}
                    onClick={() => void handleDeviceLogin()}
                    title="自动继承旧版桌面设备号，无需单独记忆密码"
                  >
                    用本机登录
                  </button>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 8 }}>
                  <input
                    className="inline-input"
                    value={usernameInput}
                    onChange={(e) => setUsernameInput(e.target.value)}
                    placeholder="用户名"
                  />
                  <input
                    className="inline-input"
                    type="password"
                    value={passwordInput}
                    onChange={(e) => setPasswordInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !busyMessage) void handlePasswordAuth()
                    }}
                    placeholder="密码"
                  />
                  <button
                    className="btn btn-primary"
                    disabled={Boolean(busyMessage) || !usernameInput.trim() || !passwordInput}
                    onClick={() => void handlePasswordAuth()}
                  >
                    {authMode === 'login' ? '登录' : '注册'}
                  </button>
                </div>
                <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                  提示：「用本机登录」会自动只读兼容旧版 <code>%APPDATA%\animeow-desktop\config.json</code> 中的设备标识。
                </div>
              </>
            )}
          </div>

          {/* 右侧：桌面端应用内签名更新卡片 */}
          <DesktopUpdateCard updater={updater} compact onNotice={onNotice} />
        </div>
      </section>

      <section className="card-box">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginBottom: 8 }}>
          <div>
            <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
              云端备份
            </div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.923rem', marginTop: 2 }}>
              下载后先对比备份与本机资料再执行恢复；当前默认策略：
              <strong>
                {defaultRestoreStrategy === 'REPLACE' ? '覆盖本地记录' : '保留本地并合并'}
              </strong>
              （可在「设置 → 同步与云端」调整）。
            </div>
          </div>
          {session.loggedIn && (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <label
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 5,
                  fontSize: '0.9231rem',
                  color: 'var(--text-secondary)',
                  cursor: 'pointer'
                }}
              >
                <input
                  type="checkbox"
                  checked={includeCovers}
                  disabled={Boolean(busyMessage)}
                  onChange={(e) => onToggleIncludeCovers(e.target.checked)}
                />
                含自定义封面
              </label>
              <button
                className="btn"
                disabled={Boolean(busyMessage)}
                onClick={() => void runTask('正在获取云端备份列表...', loadCloudBackups)}
              >
                刷新列表
              </button>
              <button
                className="btn"
                disabled={Boolean(busyMessage)}
                onClick={() => void handleUploadCloudBackup()}
              >
                上传备份（{includeCovers ? '含封面' : '不含封面'}）
              </button>
            </div>
          )}
        </div>

        {!session.loggedIn ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
            请先在上方登录追番喵云账号（或点击「用本机登录」）以查看和管理云端备份。
          </div>
        ) : !backupsLoaded ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
            正在读取云端备份记录...
          </div>
        ) : backupsLoadError ? (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 10,
              flexWrap: 'wrap',
              padding: '8px 10px',
              borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--danger)',
              color: 'var(--danger)',
              fontSize: '0.9231rem'
            }}
          >
            <span>读取云端备份列表失败：{backupsLoadError}</span>
            <button
              type="button"
              className="btn"
              disabled={Boolean(busyMessage)}
              onClick={() => void runTask('正在重新获取云端备份列表...', loadCloudBackups)}
            >
              重新加载
            </button>
          </div>
        ) : cloudBackups.length === 0 ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
            暂无云端备份，点击「上传备份」即可创建。
          </div>
        ) : (
          <ul
            aria-label="云端备份列表"
            style={{
              maxHeight: 240,
              overflowY: 'auto',
              listStyle: 'none',
              margin: 0,
              padding: 0,
              border: '1px solid var(--border-subtle)',
              borderRadius: 'var(--radius-sm)'
            }}
          >
            {cloudBackups.map((row) => (
              <li key={row.id} className="cloud-backup-row">
                <button
                  className="btn btn-primary"
                  aria-label={`下载并预览 ${formatBackupTime(row)} 的云端备份`}
                  disabled={Boolean(busyMessage)}
                  onClick={() => void handleDownloadAndDiff(row)}
                >
                  下载并预览
                </button>
                <div style={{ flex: '1 1 160px', minWidth: 0, fontSize: '0.9231rem', color: 'var(--text-secondary)' }}>
                  <strong style={{ color: 'var(--text-primary)' }}>{formatBackupTime(row)}</strong>
                  <span style={{ marginLeft: 8 }}>{formatBytes(row.payloadSize)}</span>
                </div>
                <div style={{ display: 'flex', gap: 6, marginLeft: 'auto' }}>
                  {confirmDeleteBackupId === row.id ? (
                    <>
                      <button
                        className="btn"
                        style={{ color: 'var(--danger)', borderColor: 'var(--danger)' }}
                        disabled={Boolean(busyMessage)}
                        onClick={() => void handleDeleteCloudBackup(row)}
                      >
                        确认删除
                      </button>
                      <button className="btn btn-ghost" onClick={() => setConfirmDeleteBackupId(null)}>
                        取消
                      </button>
                    </>
                  ) : (
                    <button
                      className="btn btn-ghost"
                      style={{ color: 'var(--danger)' }}
                      aria-label={`删除 ${formatBackupTime(row)} 的云端备份`}
                      disabled={Boolean(busyMessage)}
                      onClick={() => setConfirmDeleteBackupId(row.id)}
                    >
                      删除
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* 模块 C：Bangumi OAuth 授权与双向对比同步 */}
      <section className="card-box">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <div>
            <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
              Bangumi OAuth 授权与双向对比同步
            </div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', marginTop: 2 }}>
              支持双向同步：下行从 Bangumi 拉取动画收藏与进度对比合并，上行将本机进度与评分逐部上报到 Bangumi。
            </div>
          </div>
          {session.bangumi ? (
            <span className="badge badge-vault">
              已绑定 Bangumi：{session.bangumi.nickname || session.bangumi.username} (#{session.bangumi.id})
            </span>
          ) : refreshingSession ? (
            <span className="badge">
              <LoadingSpinner size="sm" inline label="正在核对绑定状态..." />
            </span>
          ) : (
            <span className="badge">未绑定 Bangumi</span>
          )}
        </div>

        {!session.loggedIn ? (
          <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
            同步 Bangumi 前请先在上方登录追番喵账号。
          </div>
        ) : !session.bangumi ? (
          <div
            style={{
              padding: 12,
              borderRadius: 'var(--radius-sm)',
              background: 'var(--bg-canvas)',
              border: '1px solid var(--border-subtle)',
              display: 'flex',
              flexDirection: 'column',
              gap: 10
            }}
          >
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <button
                className="btn btn-primary"
                disabled={Boolean(busyMessage)}
                onClick={() => void handleStartBgmOAuth()}
              >
                1. 在浏览器打开 Bangumi OAuth 授权
              </button>
              {bgmAuthStart && (
                <button
                  className="btn"
                  onClick={() => void studioApi.openHttpsUrl(bgmAuthStart.authorizeUrl)}
                >
                  重新打开授权页
                </button>
              )}
              {bgmAuthStart && (
                <span className="mono" style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                  state: {bgmAuthStart.state.slice(0, 12)}…
                </span>
              )}
            </div>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <input
                className="inline-input mono"
                style={{ flex: 1, minWidth: 260 }}
                value={bgmCallbackInput}
                onChange={(e) => setBgmCallbackInput(e.target.value)}
                placeholder="2. 授权完成后，粘贴回调 URL（含 ticket / code）或授权票据..."
              />
              <button
                className="btn btn-primary"
                disabled={Boolean(busyMessage) || !bgmCallbackInput.trim()}
                onClick={() => void handleCompleteBgmOAuth()}
              >
                完成绑定
              </button>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                <button
                  className="btn btn-primary"
                  disabled={Boolean(busyMessage)}
                  onClick={() => void handlePreviewBgmSync()}
                >
                  对比本机与 Bangumi 差异
                </button>
                {bgmPreviewed && (
                  <>
                    <div className="segmented-group">
                      {(
                        [
                          ['all', `全部 (${bgmDiffs.length})`],
                          ['changed', `差异 (${bgmCounts.changed})`],
                          ['only_remote', `仅 Bangumi (${bgmCounts.onlyRemote})`],
                          ['only_local', `仅本机 (${bgmCounts.onlyLocal})`]
                        ] as const
                      ).map(([k, lbl]) => (
                        <button
                          key={k}
                          className={`segmented-btn ${bgmFilter === k ? 'active' : ''}`}
                          onClick={() => setBgmFilter(k)}
                        >
                          {lbl}
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>

              <div style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                {confirmUnbindBgm ? (
                  <>
                    <button
                      className="btn"
                      style={{ color: 'var(--danger)', borderColor: 'var(--danger)' }}
                      disabled={Boolean(busyMessage)}
                      onClick={() => void handleUnbindBgm()}
                    >
                      确认解除绑定
                    </button>
                    <button className="btn btn-ghost" onClick={() => setConfirmUnbindBgm(false)}>
                      取消
                    </button>
                  </>
                ) : (
                  <button
                    className="btn btn-ghost"
                    style={{ color: 'var(--danger)' }}
                    disabled={Boolean(busyMessage)}
                    onClick={() => setConfirmUnbindBgm(true)}
                  >
                    解除 Bangumi 绑定
                  </button>
                )}
              </div>
            </div>

            {bgmPreviewed && (
              bgmDiffs.length === 0 ? (
                <div
                  style={{
                    padding: '8px 10px',
                    border: '1px solid var(--border-subtle)',
                    color: 'var(--positive)',
                    fontSize: '0.9231rem'
                  }}
                >
                  当前本机已关联的 Bangumi 动画条目与 Bangumi 收藏状态、集数和评分完全一致。
                </div>
              ) : (
                <>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      flexWrap: 'wrap',
                      gap: 8,
                      padding: '6px 10px',
                      background: 'var(--bg-canvas)',
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-sm)'
                    }}
                  >
                    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: '0.923rem' }}>
                      <input
                        type="checkbox"
                        checked={preferRemoteOnConflict}
                        onChange={(e) => {
                          bgmConflictManuallyToggledRef.current = true
                          setPreferRemoteOnConflict(e.target.checked)
                        }}
                      />
                      拉取时遇冲突以 Bangumi 记录的状态/评分为准（不勾选则保留本机已有进度优先）
                    </label>
                    <button
                      className="btn btn-primary"
                      disabled={Boolean(busyMessage) || selectedBgmSubjects.size === 0}
                      onClick={() => void handlePullBgmSelected()}
                    >
                      拉取已选 {selectedBgmSubjects.size} 项合并到本地
                    </button>
                  </div>

                  <div
                    style={{
                      maxHeight: 280,
                      overflow: 'auto',
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-sm)'
                    }}
                  >
                    <table className="curator-table">
                      <thead>
                        <tr>
                          <th style={{ width: 34 }}>选</th>
                          <th style={{ width: '32%' }}>动画标题 (Subject ID)</th>
                          <th style={{ width: '24%' }}>本机状态与进度</th>
                          <th style={{ width: '24%' }}>Bangumi 记录</th>
                          <th style={{ width: 118 }}>上行同步</th>
                        </tr>
                      </thead>
                      <tbody>
                        {filteredBgmDiffs.map((row) => {
                          const badge = bangumiDiffBadge(row.diffKind)
                          const canPull = row.diffKind !== 'only_local'
                          const canPush = row.localAnimeId != null && animeById.has(row.localAnimeId)
                          return (
                            <tr key={row.subjectId}>
                              <td>
                                <input
                                  type="checkbox"
                                  disabled={!canPull}
                                  checked={canPull && selectedBgmSubjects.has(row.subjectId)}
                                  onChange={() => toggleBgmCheck(row.subjectId)}
                                  title={canPull ? '勾选后随本次拉取合并到本地' : '仅本机条目请使用右侧单部上报'}
                                />
                              </td>
                              <td title={`${row.title} (#${row.subjectId})`}>
                                <span className={badge.className} style={{ marginRight: 6 }}>
                                  {badge.label}
                                </span>
                                <strong className="heading-font">{row.title}</strong>
                                <span className="mono" style={{ marginLeft: 5, fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                                  #{row.subjectId}
                                </span>
                              </td>
                              <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                                {row.localSummary}
                              </td>
                              <td className="mono" style={{ fontWeight: 600 }}>
                                {row.remoteSummary}
                              </td>
                              <td>
                                {canPush ? (
                                  <button
                                    className="btn"
                                    disabled={Boolean(busyMessage)}
                                    onClick={() => void handlePushOneBgm(row)}
                                    title="将本机状态、已看集数与评分单部上报至 Bangumi"
                                  >
                                    单部上报
                                  </button>
                                ) : (
                                  <span style={{ color: 'var(--text-muted)', fontSize: '0.8462rem' }}>需先拉取建档</span>
                                )}
                              </td>
                            </tr>
                          )
                        })}
                      </tbody>
                    </table>
                  </div>
                </>
              )
            )}
          </div>
        )}
      </section>
    </>
  )
}
