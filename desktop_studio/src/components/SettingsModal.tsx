import React, { useEffect, useState } from 'react'
import { formatBytes, STUDIO_DESKTOP_VERSION, studioApi } from '../api'
import type { DesktopUpdaterController } from '../hooks/useDesktopUpdater'
import { DesktopUpdateCard } from './DesktopUpdateCard'
import { FEEDBACK_QQ_GROUP_NAME, FEEDBACK_QQ_GROUP_NUMBER } from './FeedbackModal'
import { LoadingSpinner } from './LoadingIndicator'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import type {
  CoverBaselineStats,
  CoverStoragePolicy,
  CuratorSubView,
  DataDirInfo,
  DesktopRestoreStrategy,
  DesktopSettings,
  DesktopShortcutAction,
  StudioThemeId
} from '../types'
import {
  DEFAULT_SHORTCUTS,
  SHORTCUT_META_LIST,
  formatShortcutFromEvent
} from '../utils/shortcuts'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import { VIDEO_UPSCALE_OPTIONS, type VideoUpscaleMode } from '../utils/videoUpscaler'

export interface ThemeOptionItem {
  id: StudioThemeId
  name: string
  craftNote: string
  swatchBg: string
  swatchAccent: string
}

interface SettingsModalProps {
  open: boolean
  onClose: () => void
  settings: DesktopSettings
  onChange: (patch: Partial<DesktopSettings>) => void
  onResetAll: () => void
  onResetTabOrder: () => void
  themes: ThemeOptionItem[]
  accentPresets: { name: string; hex: string }[]
  wallpaperDataUrl: string
  onPickWallpaper: (file: File | null) => void
  onClearWallpaper: () => void
  libraryStats: {
    total: number
    anime: number
    book: number
    game: number
    audio: number
  }
  onJumpToSync: () => void
  onOpenManual: () => void
  onOpenFeedback: () => void
  onOpenMaintenance: () => void
  updater: DesktopUpdaterController
  initialSection?: SettingsSection
}

export type SettingsSection = 'appearance' | 'system' | 'shortcuts' | 'playback' | 'sync' | 'storage'

const SECTION_ITEMS: { id: SettingsSection; label: string; desc: string }[] = [
  { id: 'appearance', label: '外观与界面', desc: '主题、字号、壁纸与默认视图' },
  { id: 'system', label: '系统与启动', desc: '开机自启动、托盘、动效与自动更新' },
  { id: 'shortcuts', label: '快捷键', desc: '自定义全局快捷键与速查' },
  { id: 'playback', label: '播放与超分', desc: 'WebGL2 超分、倍速与连播偏好' },
  { id: 'sync', label: '云端与跨设备', desc: '桌面设置漫游与封面备份策略' },
  { id: 'storage', label: '更新与关于', desc: '应用内更新、本地目录与重置' }
]

/**
 * 统一桌面端设置中心（快捷键 Ctrl+,）：
 * 聚合主题外观、播放与超分默认档位、跨设备云同步偏好及本地数据目录管理。
 */
export const SettingsModal: React.FC<SettingsModalProps> = ({
  open,
  onClose,
  settings,
  onChange,
  onResetAll,
  onResetTabOrder,
  themes,
  accentPresets,
  wallpaperDataUrl,
  onPickWallpaper,
  onClearWallpaper,
  libraryStats,
  onJumpToSync,
  onOpenManual,
  onOpenFeedback,
  onOpenMaintenance,
  updater,
  initialSection
}) => {
  const dialog = useStudioDialog()
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const { offset: modalOffset, isMoved: modalMoved, resetOffset: resetModalOffset, dragHandlers: modalDragHandlers } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })
  const [section, setSection] = useState<SettingsSection>(initialSection ?? 'appearance')
  const [dataInfo, setDataInfo] = useState<DataDirInfo | null>(null)
  const [coverStats, setCoverStats] = useState<CoverBaselineStats | null>(null)
  const [cleaningCovers, setCleaningCovers] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [autostartEnabled, setAutostartEnabled] = useState<boolean>(false)

  useEffect(() => {
    if (!open) return
    studioApi.getCoverBaselineStats().then(setCoverStats).catch(() => {})
  }, [open, section])

  const handleCleanupOrphanCovers = async () => {
    if (cleaningCovers) return
    setCleaningCovers(true)
    try {
      const scan = await studioApi.cleanupOrphanCovers(true)
      if (scan.orphanFiles.length === 0) {
        flashNotice(scan.message)
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
      flashNotice(res.message)
      const nextStats = await studioApi.getCoverBaselineStats().catch(() => null)
      if (nextStats) setCoverStats(nextStats)
    } catch (e) {
      flashNotice(`清理失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setCleaningCovers(false)
    }
  }
  const [autostartLoading, setAutostartLoading] = useState<boolean>(false)
  const [recordingAction, setRecordingAction] = useState<DesktopShortcutAction | null>(null)

  useEffect(() => {
    if (!open) {
      setRecordingAction(null)
      return
    }
    if (initialSection) {
      setSection(initialSection)
    }
    studioApi
      .getDataDirInfo()
      .then((info) => setDataInfo(info))
      .catch(() => {})
    setAutostartLoading(true)
    studioApi
      .getAutostart()
      .then((enabled) => setAutostartEnabled(enabled))
      .catch(() => {})
      .finally(() => setAutostartLoading(false))
  }, [open])

  useEffect(() => {
    if (!open || !recordingAction) return
    const handleRecord = (e: KeyboardEvent) => {
      e.preventDefault()
      e.stopPropagation()
      if (e.key === 'Escape') {
        setRecordingAction(null)
        return
      }
      const combo = formatShortcutFromEvent(e)
      if (!combo) return
      const conflict = SHORTCUT_META_LIST.find(
        (m) => m.action !== recordingAction && settings.shortcuts[m.action].toLowerCase() === combo.toLowerCase()
      )
      if (conflict) {
        setNotice(`「${combo}」已被「${conflict.label}」占用，请换一个组合键`)
        window.setTimeout(() => setNotice(null), 2800)
        return
      }
      onChange({
        shortcuts: {
          ...settings.shortcuts,
          [recordingAction]: combo
        }
      })
      setRecordingAction(null)
      setNotice(`已更新快捷键为 ${combo}`)
      window.setTimeout(() => setNotice(null), 2200)
    }
    window.addEventListener('keydown', handleRecord, true)
    return () => window.removeEventListener('keydown', handleRecord, true)
  }, [open, recordingAction, settings.shortcuts, onChange])

  if (!open) return null

  const currentTheme = themes.find((t) => t.id === settings.theme) ?? themes[0]

  const flashNotice = (msg: string) => {
    setNotice(msg)
    window.setTimeout(() => setNotice(null), 2600)
  }

  const handleToggleAutostart = async (next: boolean) => {
    setAutostartLoading(true)
    try {
      const actual = await studioApi.setAutostart(next)
      setAutostartEnabled(actual)
      flashNotice(actual ? '已开启 Windows 开机自动启动' : '已关闭 Windows 开机自动启动')
    } catch (err) {
      flashNotice(err instanceof Error ? err.message : String(err))
    } finally {
      setAutostartLoading(false)
    }
  }

  return (
    <div
      className="modal-backdrop"
      style={{ zIndex: modalZ }}
      onPointerDownCapture={bringToFront}
      onClick={onClose}
    >
      <div
        className="settings-modal"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="桌面端设置"
      >
        <div
          className="settings-modal-header"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
        >
          <div>
            <div className="heading-font" style={{ fontSize: '1.23rem', fontWeight: 700 }}>
              偏好设置
            </div>
            <div style={{ fontSize: '0.885rem', color: 'var(--text-muted)', marginTop: 2 }}>
              修改即时生效；开启云端同步后，换电脑登录即可无缝恢复此处的主题、页签排序与播放偏好
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {notice && <span className="badge">{notice}</span>}
            {modalMoved && (<button className="btn btn-ghost" onClick={resetModalOffset}>归位</button>)}
            <button className="btn" onClick={onClose}>
              关闭 (Esc)
            </button>
          </div>
        </div>

        <div className="settings-modal-body">
          <nav className="settings-nav">
            {SECTION_ITEMS.map((item) => (
              <button
                key={item.id}
                type="button"
                className={`settings-nav-item ${section === item.id ? 'active' : ''}`}
                onClick={() => setSection(item.id)}
              >
                <span className="settings-nav-label">{item.label}</span>
                <span className="settings-nav-desc">{item.desc}</span>
              </button>
            ))}
          </nav>

          <div className="settings-content">
            {section === 'appearance' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div className="settings-group-title">视觉主题</div>
                  <div className="settings-theme-grid">
                    {themes.map((t) => (
                      <button
                        key={t.id}
                        type="button"
                        className={`settings-theme-card ${settings.theme === t.id ? 'active' : ''}`}
                        onClick={() => onChange({ theme: t.id })}
                      >
                        <div className="settings-theme-swatches">
                          <span style={{ background: t.swatchBg }} />
                          <span style={{ background: t.swatchAccent }} />
                        </div>
                        <div style={{ minWidth: 0, textAlign: 'left' }}>
                          <div style={{ fontWeight: 700, fontSize: '0.96rem' }}>{t.name}</div>
                          <div style={{ fontSize: '0.846rem', color: 'var(--text-muted)', marginTop: 2 }}>
                            {t.craftNote}
                          </div>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">个性化强调色与排版</div>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">强调色覆盖</div>
                      <div className="settings-row-hint">覆盖当前主题默认高亮色，随云端同步</div>
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                      {accentPresets.map((p) => (
                        <button
                          key={p.name}
                          type="button"
                          onClick={() => onChange({ accentHex: p.hex })}
                          title={p.name}
                          style={{
                            width: 22,
                            height: 22,
                            borderRadius: '50%',
                            background:
                              p.hex ||
                              'conic-gradient(#b53329, #3d6950, #2b6cb0, #e09b3d, #b53329)',
                            border:
                              settings.accentHex === p.hex
                                ? '2px solid var(--text-primary)'
                                : '1px solid var(--border-strong)',
                            cursor: 'pointer'
                          }}
                        />
                      ))}
                      <input
                        type="color"
                        value={settings.accentHex || currentTheme.swatchAccent}
                        onChange={(e) => onChange({ accentHex: e.target.value })}
                        title="自由拾色器"
                        style={{
                          width: 26,
                          height: 24,
                          padding: 0,
                          border: 'none',
                          background: 'transparent',
                          cursor: 'pointer'
                        }}
                      />
                    </div>
                  </div>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">界面字号密度</div>
                      <div className="settings-row-hint">
                        同时调节全站字号、按钮尺寸、列表行高与画册卡片密度（当前：
                        {settings.fontScale === 'compact'
                          ? '紧凑 11.5px · 一屏显示更多条目'
                          : settings.fontScale === 'comfortable'
                            ? '舒适 15px · 宽松大字号阅读'
                            : '标准 13px · 默认均衡排版'}
                        ）
                      </div>
                    </div>
                    <div className="segmented-group">
                      {(
                        [
                          ['compact', '紧凑'],
                          ['normal', '标准'],
                          ['comfortable', '舒适']
                        ] as const
                      ).map(([scale, label]) => (
                        <button
                          key={scale}
                          type="button"
                          className={`segmented-btn ${settings.fontScale === scale ? 'active' : ''}`}
                          onClick={() => onChange({ fontScale: scale })}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">藏品默认视图</div>
                      <div className="settings-row-hint">启动后进入藏品页签时的默认展示方式</div>
                    </div>
                    <div className="segmented-group">
                      {(
                        [
                          ['shelf', '画册视图'],
                          ['kanban', '状态看板'],
                          ['table', '列表视图']
                        ] as [CuratorSubView, string][]
                      ).map(([view, label]) => (
                        <button
                          key={view}
                          type="button"
                          className={`segmented-btn ${settings.defaultSubView === view ? 'active' : ''}`}
                          onClick={() => onChange({ defaultSubView: view })}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">背景壁纸（仅本机生效）</div>
                      <div className="settings-row-hint">
                        本地图片仅存于本机不占云同步额度；壁纸透明度随云端保留
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <label className="btn" style={{ cursor: 'pointer' }}>
                        选择本地图片
                        <input
                          type="file"
                          accept="image/*"
                          style={{ display: 'none' }}
                          onChange={(e) => onPickWallpaper(e.target.files?.[0] ?? null)}
                        />
                      </label>
                      {wallpaperDataUrl && (
                        <button type="button" className="btn" onClick={onClearWallpaper}>
                          清除壁纸
                        </button>
                      )}
                    </div>
                  </div>

                  {wallpaperDataUrl && (
                    <div className="settings-row">
                      <div>
                        <div className="settings-row-label">壁纸透明度</div>
                        <div className="settings-row-hint">建议保持在 10% ~ 24% 以保证文字清晰</div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, width: 200 }}>
                        <input
                          type="range"
                          min={0.04}
                          max={0.42}
                          step={0.02}
                          value={settings.wallpaperOpacity}
                          onChange={(e) =>
                            onChange({ wallpaperOpacity: Number(e.target.value) })
                          }
                          style={{ flex: 1 }}
                        />
                        <span className="mono" style={{ fontSize: '0.9231rem' }}>
                          {Math.round(settings.wallpaperOpacity * 100)}%
                        </span>
                      </div>
                    </div>
                  )}

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">顶部工作区页签顺序</div>
                      <div className="settings-row-hint">
                        可直接按住顶部页签左右拖动排序，排序会自动随云端同步
                      </div>
                    </div>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        onResetTabOrder()
                        flashNotice('已恢复默认页签顺序')
                      }}
                    >
                      恢复默认顺序
                    </button>
                  </div>
                </div>
              </div>
            )}

            {section === 'system' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div className="settings-group-title">开机启动与托盘驻留</div>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">Windows 开机自动启动</div>
                      <div className="settings-row-hint">
                        登录系统后自动启动追番喵（写入当前用户启动项，不申请管理员权限）
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      {autostartLoading && <LoadingSpinner size="sm" inline />}
                      <input
                        type="checkbox"
                        disabled={autostartLoading}
                        checked={autostartEnabled}
                        onChange={(e) => void handleToggleAutostart(e.target.checked)}
                      />
                    </div>
                  </label>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">关闭窗口时最小化到系统托盘</div>
                      <div className="settings-row-hint">
                        开启后点击右上角关闭会驻留后台托盘，保持游戏伴侣计时不断开；关闭此项则直接退出程序
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.closeToTray}
                      onChange={(e) => {
                        const next = e.target.checked
                        onChange({ closeToTray: next })
                        void studioApi.setCloseToTray(next).catch(() => {})
                        flashNotice(next ? '关闭窗口时将最小化到系统托盘' : '关闭窗口时将直接退出追番喵')
                      }}
                    />
                  </label>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">启动时静默检查新版本</div>
                      <div className="settings-row-hint">
                        仅在发现新版本时于顶栏轻提示，无更新或离线时完全不弹窗打扰；可随时手动检查
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.autoCheckUpdate}
                      onChange={(e) => {
                        onChange({ autoCheckUpdate: e.target.checked })
                        flashNotice(
                          e.target.checked
                            ? '已开启启动时静默检查更新'
                            : '已关闭自动检查更新（仍可在「更新与关于」手动检查）'
                        )
                      }}
                    />
                  </label>
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">界面动效与过渡</div>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">减弱界面动画效果</div>
                      <div className="settings-row-hint">
                        默认启用柔和的弹窗过渡、切页淡入与等待旋转/骨架动画；勾选后将尽量减少界面动效
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.reduceMotion}
                      onChange={(e) => onChange({ reduceMotion: e.target.checked })}
                    />
                  </label>
                </div>
              </div>
            )}

            {section === 'shortcuts' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div
                    className="settings-group-title"
                    style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                  >
                    <span>自定义全局快捷键</span>
                    <button
                      type="button"
                      className="btn"
                      style={{ padding: '2px 8px', fontSize: '0.8846rem' }}
                      onClick={() => {
                        setRecordingAction(null)
                        onChange({ shortcuts: { ...DEFAULT_SHORTCUTS } })
                        flashNotice('已恢复全部默认快捷键')
                      }}
                    >
                      全部恢复默认
                    </button>
                  </div>
                  <div className="settings-note-box" style={{ marginBottom: 8 }}>
                    点击右侧按键框后直接按下新的组合键（如 Ctrl+Shift+F、Alt+K、F2）即可生效，按 Esc 取消录制；快捷键配置同样支持随云端备份漫游。
                  </div>

                  {SHORTCUT_META_LIST.map((item) => {
                    const isRecording = recordingAction === item.action
                    const currentCombo = settings.shortcuts[item.action]
                    const isCustom = currentCombo !== DEFAULT_SHORTCUTS[item.action]
                    return (
                      <div key={item.action} className="settings-row">
                        <div>
                          <div className="settings-row-label">{item.label}</div>
                          <div className="settings-row-hint">{item.hint}</div>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                          <button
                            type="button"
                            className={`shortcut-recorder-btn ${isRecording ? 'is-recording' : ''}`}
                            onClick={() =>
                              setRecordingAction((prev) => (prev === item.action ? null : item.action))
                            }
                            title="点击后按下新组合键以修改"
                          >
                            {isRecording ? '请按下组合键...' : currentCombo}
                          </button>
                          {isCustom && (
                            <button
                              type="button"
                              className="btn"
                              style={{ padding: '2px 7px', fontSize: '0.8462rem' }}
                              onClick={() => {
                                onChange({
                                  shortcuts: {
                                    ...settings.shortcuts,
                                    [item.action]: DEFAULT_SHORTCUTS[item.action]
                                  }
                                })
                                flashNotice(`已恢复「${item.label}」默认快捷键`)
                              }}
                            >
                              默认
                            </button>
                          )}
                        </div>
                      </div>
                    )
                  })}
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">画册内浏览快捷键（固定）</div>
                  <div className="settings-note-box">
                    在「画册」工作区未聚焦输入框时：<code>J / ↓</code> 下一部 · <code>K / ↑</code> 上一部 ·{' '}
                    <code>] / =</code> 进度 +1 · <code>[ / -</code> 进度 -1 · <code>Enter</code> 打开漫评手稿 ·{' '}
                    <code>Esc</code> 关闭弹窗或收起右侧详情。
                  </div>
                </div>
              </div>
            )}

            {section === 'playback' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div className="settings-group-title">实时画质增强（WebGL2 + Anime4K）</div>
                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">默认超分档位</div>
                      <div className="settings-row-hint">
                        同时应用于「在线追番」与「本机视频」，播放期间也可随时单独切换
                      </div>
                    </div>
                    <StudioSelect
                      className="studio-select-inline"
                      value={settings.upscaleMode}
                      onChange={(val) => onChange({ upscaleMode: val as VideoUpscaleMode })}
                      options={VIDEO_UPSCALE_OPTIONS.map((opt) => ({
                        value: opt.value,
                        label: opt.label,
                        sublabel: opt.description
                      }))}
                      ariaLabel="默认超分档位"
                      minMenuWidth={280}
                      maxMenuWidth={360}
                    />
                  </div>
                  <div className="settings-note-box">
                    {VIDEO_UPSCALE_OPTIONS.find((o) => o.value === settings.upscaleMode)?.description}
                    。480p / 720p 片源或全屏观看时线条锐化差异最明显；播放期间可在底部控制栏长按「按住对比原画」即时对照。若片源超过 1080p、受跨域限制或显卡不支持半精度缓冲，会自动回退原画并在画面右上角常驻提示原因。
                  </div>
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">在线追番与本机播放习惯</div>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">默认播放倍速</div>
                      <div className="settings-row-hint">进入播放器时的初始播放速度</div>
                    </div>
                    <StudioSelect
                      className="studio-select-inline"
                      value={String(settings.defaultPlaybackRate)}
                      onChange={(val) => onChange({ defaultPlaybackRate: Number(val) })}
                      options={[0.75, 1, 1.25, 1.5, 2].map((r) => ({
                        value: String(r),
                        label: `${r}x 倍速`
                      }))}
                      ariaLabel="默认播放倍速"
                      minMenuWidth={132}
                      maxMenuWidth={180}
                    />
                  </div>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">播完自动连播下一集</div>
                      <div className="settings-row-hint">当前集播放结束时自动加载同线路下一集</div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.autoNextEpisode}
                      onChange={(e) => onChange({ autoNextEpisode: e.target.checked })}
                    />
                  </label>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">播放失败自动切换下一线路</div>
                      <div className="settings-row-hint">遇到失效片源或 CDN 超时时自动尝试备用线路</div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.autoFallbackLine}
                      onChange={(e) => onChange({ autoFallbackLine: e.target.checked })}
                    />
                  </label>
                </div>
              </div>
            )}

            {section === 'sync' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div className="settings-group-title">无缝跨设备同步偏好</div>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">桌面端设置随云端备份同步</div>
                      <div className="settings-row-hint">
                        将主题、强调色、字号、页签顺序、默认视图、超分与播放偏好存入云端独立分区；
                        与手机端外观设置互不冲突，经手机中转后再传回新电脑仍可完整还原
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.syncDesktopSettings}
                      onChange={(e) => onChange({ syncDesktopSettings: e.target.checked })}
                    />
                  </label>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">外部封面存储策略</div>
                      <div className="settings-row-hint">
                        默认「远程优先」：联网回填与检索入库先探测外部 HTTPS 链接，可用则直接引用（不占备份体积），不可达时再回落落盘本地；用户手传封面永远保持本地
                      </div>
                    </div>
                    <StudioSelect
                      className="studio-select-inline"
                      value={settings.coverStoragePolicy ?? 'remote'}
                      onChange={(val) =>
                        onChange({ coverStoragePolicy: val as CoverStoragePolicy })
                      }
                      options={[
                        {
                          value: 'remote',
                          label: '远程优先（推荐）',
                          sublabel: '可用外链直接引用，显著控制备份体积'
                        },
                        {
                          value: 'local',
                          label: '全部落盘本地（离线归档）',
                          sublabel: '将新增封面完整保存到本机目录'
                        }
                      ]}
                      ariaLabel="外部封面存储策略"
                      minMenuWidth={250}
                      maxMenuWidth={320}
                    />
                  </div>

                  <label className="settings-row settings-toggle-row">
                    <div>
                      <div className="settings-row-label">上传云端默认包含本地封面</div>
                      <div className="settings-row-hint">
                        开启后打包本机 covers/native/ 中的手传封面与回落本地图（远程引用封面不占备份体积）；关闭可进一步减小备份大小
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={settings.cloudIncludeCovers}
                      onChange={(e) => onChange({ cloudIncludeCovers: e.target.checked })}
                    />
                  </label>

                  <div className="settings-row">
                    <div>
                      <div className="settings-row-label">云端下载默认恢复策略</div>
                      <div className="settings-row-hint">
                        多设备并行使用时推荐「保留本地并合并」；换新设备首次拉取可选「完整覆盖」
                      </div>
                    </div>
                    <StudioSelect
                      className="studio-select-inline"
                      value={settings.defaultRestoreStrategy}
                      onChange={(val) =>
                        onChange({ defaultRestoreStrategy: val as DesktopRestoreStrategy })
                      }
                      options={[
                        {
                          value: 'MERGE_KEEP_LOCAL',
                          label: '保留本地并合并（推荐）',
                          sublabel: '冲突以本机进度为准，仅补充新增条目'
                        },
                        {
                          value: 'REPLACE',
                          label: '覆盖本地记录',
                          sublabel: '以备份为准覆盖本机，覆盖前自动留存回滚点'
                        }
                      ]}
                      ariaLabel="云端下载默认恢复策略"
                      minMenuWidth={250}
                      maxMenuWidth={320}
                    />
                  </div>
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">跨端兼容说明</div>
                  <div className="settings-note-box">
                    手机端与桌面端共用同一套跨端藏品库（含动画/书籍、标签与排序、状态与变更时间、观看记录、单集笔记、系列、角色、游戏、音声、AI 分析与电子书书签）。
                    手机端主题与布局配置会原样保存在本机中转区并随上传带回，不会被桌面端改动覆盖。
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() => {
                        onClose()
                        onJumpToSync()
                      }}
                    >
                      前往同步中心管理云端快照
                    </button>
                  </div>
                </div>
              </div>
            )}

            {section === 'storage' && (
              <div className="settings-section">
                <div className="settings-group">
                  <div className="settings-group-title">软件更新</div>
                  <DesktopUpdateCard updater={updater} onNotice={flashNotice} />
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">本地存储与版本</div>

                  <div className="settings-row">
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div className="settings-row-label">数据存储目录</div>
                      <div
                        className="mono"
                        style={{
                          fontSize: '0.8846rem',
                          color: 'var(--text-secondary)',
                          wordBreak: 'break-all',
                          marginTop: 2
                        }}
                      >
                        {dataInfo?.dataDir || '正在读取...'}
                      </div>
                    </div>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        studioApi
                          .openDataDir()
                          .then(() => flashNotice('已打开本地数据目录'))
                          .catch((err) =>
                            flashNotice(err instanceof Error ? err.message : String(err))
                          )
                      }}
                    >
                      打开文件夹
                    </button>
                  </div>

                  <div className="settings-row">
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div className="settings-row-label">当前资料库规模</div>
                      <div className="settings-row-hint">
                        共 {libraryStats.total} 项（动画 {libraryStats.anime} · 书籍{' '}
                        {libraryStats.book} · 游戏 {libraryStats.game} · 音声 {libraryStats.audio}）
                      </div>
                      {coverStats && (
                        <div className="settings-row-hint" style={{ marginTop: 4 }}>
                          本地封面图片：{coverStats.nativeDirFileCount} 张（占用{' '}
                          {formatBytes(coverStats.nativeDirTotalBytes)}）· 远程封面{' '}
                          {coverStats.remoteCount} · 用户手传 {coverStats.localUserCustomCount} · 自动落盘{' '}
                          {coverStats.localExternalNativeCount}
                          {coverStats.orphanFileCount > 0
                            ? ` · 可清理孤儿 ${coverStats.orphanFileCount} 张（${formatBytes(coverStats.orphanTotalBytes)}）`
                            : ''}
                        </div>
                      )}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                      {coverStats && coverStats.orphanFileCount > 0 && (
                        <button
                          type="button"
                          className="btn"
                          disabled={cleaningCovers}
                          onClick={handleCleanupOrphanCovers}
                        >
                          {cleaningCovers ? '清理中…' : '清理孤儿图'}
                        </button>
                      )}
                      <span className="badge mono">v{dataInfo?.appVersion ?? STUDIO_DESKTOP_VERSION}</span>
                    </div>
                  </div>
                </div>

                <div className="settings-group">
                  <div className="settings-group-title">快捷维护与帮助</div>
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        onClose()
                        onOpenMaintenance()
                      }}
                    >
                      打开整理与回收站
                    </button>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        onClose()
                        onOpenManual()
                      }}
                    >
                      打开动态使用手册 (F1)
                    </button>
                    <button
                      type="button"
                      className="btn btn-primary"
                      onClick={() => {
                        onClose()
                        onOpenFeedback()
                      }}
                    >
                      问题反馈 / QQ 交流群
                    </button>
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        onResetAll()
                        flashNotice('已恢复全部桌面端默认设置')
                      }}
                    >
                      恢复全部默认设置
                    </button>
                  </div>
                  <div className="settings-row-hint">
                    官方交流群：{FEEDBACK_QQ_GROUP_NAME}（QQ 群号{' '}
                    <span className="mono">{FEEDBACK_QQ_GROUP_NUMBER}</span>
                    ），点击上方按钮可一键复制群号、通过链接加群或扫码入群反馈。
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
