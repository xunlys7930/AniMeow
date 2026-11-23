import { useCallback, useEffect, useState } from 'react'
import { studioApi } from '../api'
import type {
  ActiveCompanionInfo,
  CellPatch,
  CuratorItem,
  ExternalPlayerDetection,
  PlaySessionRow
} from '../types'

interface CompanionTabProps {
  items: CuratorItem[]
  activeCompanion: ActiveCompanionInfo | null
  onRefreshCompanion: () => Promise<void>
  onOpenQuickCapture: () => void
  onSyncAnimeEpisodeWithUndo: (animeId: number, targetEpisode: number) => Promise<void>
  onUpdateCell: (patch: CellPatch) => Promise<void>
}

const LEGACY_LAUNCHER_MAP_KEY = 'animeow.studio.gameLaunchers'
const PLAYER_WATCH_OPT_IN_KEY = 'animeow.studio.playerWatchOptIn'

function loadInitialPlayerOptIn(): boolean {
  try {
    return localStorage.getItem(PLAYER_WATCH_OPT_IN_KEY) === '1'
  } catch {
    return false
  }
}

export function CompanionTab({
  items,
  activeCompanion,
  onRefreshCompanion,
  onOpenQuickCapture,
  onSyncAnimeEpisodeWithUndo,
  onUpdateCell
}: CompanionTabProps) {
  const [launcherMap, setLauncherMap] = useState<Record<number, string>>({})
  const [sessions, setSessions] = useState<PlaySessionRow[]>([])
  const [editingTimeGameId, setEditingTimeGameId] = useState<number | null>(null)
  const [editHoursInput, setEditHoursInput] = useState<string>('0')
  const [editMinutesInput, setEditMinutesInput] = useState<string>('0')
  const [savingTimeId, setSavingTimeId] = useState<number | null>(null)
  const [playerWatchEnabled, setPlayerWatchEnabled] = useState<boolean>(loadInitialPlayerOptIn)
  const [playerDetections, setPlayerDetections] = useState<ExternalPlayerDetection[]>([])
  const [scanningPlayers, setScanningPlayers] = useState<boolean>(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const gameItems = items.filter((it) => it.kind === 'game')

  const reloadLaunchersAndSessions = useCallback(async () => {
    const [dbMap, rows] = await Promise.all([
      studioApi.listGameLaunchers(),
      studioApi.listPlaySessions()
    ])
    const merged: Record<number, string> = { ...dbMap }
    try {
      const legacyRaw = localStorage.getItem(LEGACY_LAUNCHER_MAP_KEY)
      if (legacyRaw) {
        const parsed = JSON.parse(legacyRaw) as Record<string, string>
        for (const [k, exe] of Object.entries(parsed)) {
          const gid = Number(k)
          if (Number.isFinite(gid) && exe && !merged[gid]) {
            merged[gid] = exe
            await studioApi.bindGameLauncher(gid, exe)
          }
        }
        localStorage.removeItem(LEGACY_LAUNCHER_MAP_KEY)
      }
    } catch {
      // ignore legacy parse error
    }
    setLauncherMap(merged)
    setSessions(rows)
  }, [])

  useEffect(() => {
    reloadLaunchersAndSessions().catch(() => {})
  }, [activeCompanion?.pid, reloadLaunchersAndSessions])

  const runPlayerScan = useCallback(async (manual: boolean) => {
    if (manual) setScanningPlayers(true)
    try {
      const hits = await studioApi.scanExternalPlayers()
      setPlayerDetections(hits)
      if (manual) {
        setNotice(
          hits.length > 0
            ? `已在白名单播放器中识别到 ${hits.length} 条本地番剧播放记录`
            : '当前未检测到正在播放匹配番剧的 PotPlayer / mpv / VLC / MPC-HC 窗口'
        )
      }
    } catch (err) {
      if (manual) {
        setErrorMsg(err instanceof Error ? err.message : String(err))
      }
    } finally {
      if (manual) setScanningPlayers(false)
    }
  }, [])

  useEffect(() => {
    try {
      localStorage.setItem(PLAYER_WATCH_OPT_IN_KEY, playerWatchEnabled ? '1' : '0')
    } catch {
      // ignore
    }
    if (!playerWatchEnabled) return
    void runPlayerScan(false)
    const timer = window.setInterval(() => {
      void runPlayerScan(false)
    }, 6000)
    return () => window.clearInterval(timer)
  }, [playerWatchEnabled, runPlayerScan])

  const handleBindExe = async (game: CuratorItem) => {
    const picked = await studioApi.pickExecutable(`为《${game.title}》绑定本机启动程序 (.exe)`)
    if (!picked) return
    await studioApi.bindGameLauncher(game.id, picked)
    setLauncherMap((prev) => ({ ...prev, [game.id]: picked }))
    setNotice(`已为《${game.title}》持久化绑定启动器：${picked}`)
  }

  const handleLaunchGame = async (game: CuratorItem) => {
    const exe = launcherMap[game.id]
    if (!exe) {
      await handleBindExe(game)
      return
    }
    setErrorMsg(null)
    try {
      await studioApi.launchCompanionGame(game.id, game.title, exe)
      await onRefreshCompanion()
      setNotice(
        `已拉起《${game.title}》并开始后台自动计时；即使关闭主窗口退驻托盘，退出游戏时也会自动累加进 playMinutes`
      )
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  const formatDuration = (secs: number) => {
    const mins = Math.floor(secs / 60)
    const rem = secs % 60
    if (mins >= 60) {
      return `${Math.floor(mins / 60)}h ${mins % 60}m`
    }
    return `${mins}m ${rem}s`
  }

  return (
    <div style={{ height: '100%', overflowY: 'auto', padding: 18, userSelect: 'text' }}>
      <div
        style={{
          maxWidth: 1040,
          margin: '0 auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 16
        }}
      >
        <section className="card-box">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
            <div>
              <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
                Windows 进程无感伴侣 & 灵感速记守护
              </div>
              <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginTop: 3 }}>
                严格遵循隐私最小化原则：绝不读取浏览器、聊天软件或任意非白名单窗口标题。游戏计时仅跟踪你在下方显式绑定的 <code>.exe</code> 子进程句柄；看番感知需手动开启，且在内核读取标题前先校验进程名是否属于 <code>PotPlayer / mpv / VLC / MPC-HC</code>。
              </div>
            </div>
            <button className="btn btn-primary" onClick={onOpenQuickCapture}>
              呼出灵感速记微窗 (Alt+Space)
            </button>
          </div>

          {(errorMsg || notice) && (
            <div
              style={{
                marginTop: 10,
                padding: '7px 10px',
                border: '1px solid var(--border-subtle)',
                color: errorMsg ? 'var(--danger)' : 'var(--positive)',
                fontSize: '0.9231rem'
              }}
            >
              {errorMsg || notice}
            </div>
          )}

          {activeCompanion && (
            <div
              style={{
                marginTop: 12,
                padding: 12,
                background: 'var(--accent-soft)',
                border: '1px solid var(--border-strong)',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center'
              }}
            >
              <div>
                <span className="badge badge-accent mono" style={{ marginRight: 8 }}>
                  ● PID {activeCompanion.pid} 正在运行
                </span>
                <strong className="heading-font">{activeCompanion.workTitle}</strong>
                <div className="mono" style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)', marginTop: 3 }}>
                  启动器：{activeCompanion.exePath}
                </div>
              </div>
              <div className="vfd-readout">
                已计时 {formatDuration(activeCompanion.elapsedSeconds)}
              </div>
            </div>
          )}
        </section>

        {/* 白名单外部播放器看番集数感知 */}
        <section className="card-box">
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              gap: 12,
              flexWrap: 'wrap'
            }}
          >
            <div>
              <div className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700 }}>
                白名单外部播放器看番集数感知（PotPlayer / mpv / VLC / MPC-HC）
              </div>
              <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                识别正在播放的番剧文件名与集数并提示一键点亮进度（支持撤销），不会未经确认静默改动追番集数。
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: '0.9231rem', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={playerWatchEnabled}
                  onChange={(e) => setPlayerWatchEnabled(e.target.checked)}
                />
                <span>开启每 6 秒自动感知</span>
              </label>
              <button
                className="btn"
                disabled={scanningPlayers}
                onClick={() => void runPlayerScan(true)}
              >
                {scanningPlayers ? '检测中...' : '立即检测播放器窗口'}
              </button>
            </div>
          </div>

          {playerDetections.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 10 }}>
              {playerDetections.map((det) => {
                const alreadyReached = det.currentWatchedEpisodes >= det.parsedEpisode
                return (
                  <div
                    key={`${det.playerExe}:${det.matchedAnimeId}:${det.parsedEpisode}`}
                    style={{
                      padding: 10,
                      border: '1px solid var(--border-strong)',
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-panel)',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      gap: 12
                    }}
                  >
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                        <span className="badge badge-vault mono">{det.playerExe}</span>
                        <strong className="heading-font">{det.matchedAnimeTitle}</strong>
                        <span className="badge badge-accent mono">
                          正在播放 第 {det.parsedEpisode} 集
                        </span>
                        <span className="mono" style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                          当前档案已看：{det.currentWatchedEpisodes}
                          {det.totalEpisodes > 0 ? ` / ${det.totalEpisodes}` : ''} 集
                        </span>
                      </div>
                      <div
                        className="mono"
                        style={{
                          fontSize: '0.8462rem',
                          color: 'var(--text-muted)',
                          marginTop: 3,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }}
                      >
                        窗口标题：{det.windowTitle}
                      </div>
                    </div>
                    <button
                      className="btn btn-primary"
                      disabled={alreadyReached}
                      onClick={() =>
                        void onSyncAnimeEpisodeWithUndo(det.matchedAnimeId, det.parsedEpisode)
                      }
                    >
                      {alreadyReached
                        ? `已记录至第 ${det.currentWatchedEpisodes} 集`
                        : `同步记录至第 ${det.parsedEpisode} 集`}
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </section>

        {/* 游戏启动器绑定列表 */}
        <section className="card-box">
          <div className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700, marginBottom: 8 }}>
            本地游戏启动器绑定（共 {gameItems.length} 部）
          </div>
          {gameItems.length === 0 ? (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
              当前藏品库中暂无「游戏」条目。可按 <code>Ctrl+K</code> 切换至「VNDB Galgame」检索入库后在此绑定本地 <code>.exe</code>。
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {gameItems.map((g) => {
                const boundExe = launcherMap[g.id]
                const isRunning = activeCompanion?.workId === g.id
                const isEditingTime = editingTimeGameId === g.id
                const openTimeEditor = () => {
                  setEditingTimeGameId(g.id)
                  setEditHoursInput(String(Math.floor(g.progressPrimary / 60)))
                  setEditMinutesInput(String(g.progressPrimary % 60))
                }
                const commitPlayMinutes = async (nextTotalMinutes: number) => {
                  const safeMins = Math.max(0, Math.round(nextTotalMinutes))
                  setSavingTimeId(g.id)
                  try {
                    await onUpdateCell({
                      kind: 'game',
                      id: g.id,
                      progressPrimary: safeMins
                    })
                    setEditingTimeGameId(null)
                    setNotice(
                      `已手动更新《${g.title}》累计游玩时长为 ${Math.floor(safeMins / 60)}h ${safeMins % 60}m`
                    )
                  } catch (err) {
                    setNotice(err instanceof Error ? err.message : String(err))
                  } finally {
                    setSavingTimeId(null)
                  }
                }
                const handleSaveEditedTime = () => {
                  const h = Math.max(0, Number.parseInt(editHoursInput || '0', 10) || 0)
                  const m = Math.max(0, Number.parseInt(editMinutesInput || '0', 10) || 0)
                  void commitPlayMinutes(h * 60 + m)
                }

                return (
                  <div
                    key={g.id}
                    style={{
                      padding: 12,
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-canvas)',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 10
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 10,
                        flexWrap: 'wrap'
                      }}
                    >
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                          <strong className="heading-font">{g.title}</strong>
                          <span className="badge mono">
                            累计 {Math.floor(g.progressPrimary / 60)}h {g.progressPrimary % 60}m
                          </span>
                        </div>
                        <div
                          className="mono"
                          style={{
                            fontSize: '0.8846rem',
                            color: boundExe ? 'var(--positive)' : 'var(--text-muted)',
                            marginTop: 3,
                            overflow: 'hidden',
                            textOverflow: 'ellipsis'
                          }}
                        >
                          {boundExe ? `已绑启动器：${boundExe}` : '尚未绑定本机可执行文件 (.exe)'}
                        </div>
                      </div>

                      <div style={{ display: 'flex', gap: 6, flexShrink: 0, flexWrap: 'wrap' }}>
                        <button
                          className="btn"
                          onClick={() =>
                            isEditingTime ? setEditingTimeGameId(null) : openTimeEditor()
                          }
                        >
                          {isEditingTime ? '收起调时' : '修改时长'}
                        </button>
                        <button className="btn" onClick={() => void handleBindExe(g)}>
                          {boundExe ? '更换 .exe' : '绑定 .exe...'}
                        </button>
                        <button
                          className="btn btn-primary"
                          disabled={Boolean(activeCompanion)}
                          onClick={() => void handleLaunchGame(g)}
                        >
                          {isRunning ? '正在计时中' : '启动并自动计时'}
                        </button>
                      </div>
                    </div>

                    {isEditingTime && (
                      <div
                        style={{
                          padding: '9px 10px',
                          borderRadius: 'var(--radius-sm)',
                          background: 'var(--bg-surface)',
                          border: '1px solid var(--border-subtle)',
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          flexWrap: 'wrap'
                        }}
                      >
                        <span style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)' }}>
                          手动校正总时长：
                        </span>
                        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.9231rem' }}>
                          <input
                            className="cell-input mono"
                            type="number"
                            min={0}
                            max={9999}
                            value={editHoursInput}
                            onChange={(e) => setEditHoursInput(e.target.value)}
                            style={{ width: 64 }}
                          />
                          小时
                        </label>
                        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.9231rem' }}>
                          <input
                            className="cell-input mono"
                            type="number"
                            min={0}
                            max={59}
                            value={editMinutesInput}
                            onChange={(e) => setEditMinutesInput(e.target.value)}
                            style={{ width: 56 }}
                          />
                          分钟
                        </label>
                        <button
                          className="btn"
                          disabled={savingTimeId === g.id}
                          onClick={() => void commitPlayMinutes(Math.max(0, g.progressPrimary - 30))}
                        >
                          -30m
                        </button>
                        <button
                          className="btn"
                          disabled={savingTimeId === g.id}
                          onClick={() => void commitPlayMinutes(g.progressPrimary + 30)}
                        >
                          +30m
                        </button>
                        <button
                          className="btn"
                          disabled={savingTimeId === g.id}
                          onClick={() => void commitPlayMinutes(g.progressPrimary + 60)}
                        >
                          +1h
                        </button>
                        <button
                          className="btn btn-primary"
                          disabled={savingTimeId === g.id}
                          onClick={handleSaveEditedTime}
                        >
                          {savingTimeId === g.id ? '保存中...' : '保存时长'}
                        </button>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </section>

        {/* 本机游玩会话流水 */}
        <section className="card-box">
          <div className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700, marginBottom: 8 }}>
            近期游玩记录
          </div>
          {sessions.length === 0 ? (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
              暂无历史会话流水。通过上方「启动并自动计时」运行任意游戏并退出后，此处将自动记录起止时间与净游玩时长。
            </div>
          ) : (
            <table className="curator-table">
              <thead>
                <tr>
                  <th>作品标题</th>
                  <th style={{ width: 110 }}>本次时长</th>
                  <th style={{ width: 190 }}>结束时间</th>
                  <th>备注说明</th>
                </tr>
              </thead>
              <tbody>
                {sessions.map((s) => (
                  <tr key={s.id}>
                    <td className="heading-font" style={{ fontWeight: 700 }}>
                      {s.workTitle}
                    </td>
                    <td className="mono">{formatDuration(s.durationSeconds)}</td>
                    <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                      {s.endedAt.slice(0, 19).replace('T', ' ')}
                    </td>
                    <td style={{ color: 'var(--text-secondary)' }}>{s.note || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </div>
  )
}
