import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { studioApi } from '../api'
import type {
  AgentActiveContextChip,
  AgentUiActionCommand,
  CellPatch,
  CuratorItem,
  DesktopSettings,
  MediaCarrier,
  StudioThemeId
} from '../types'
import {
  DEFAULT_DESKTOP_SETTINGS,
  loadLocalDesktopSettings,
  normalizeDesktopSettings,
  persistLocalDesktopSettings
} from '../utils/desktopSettings'
import {
  emitPopoutSync,
  listenPopoutSync,
  openQuickCapturePopout,
  startNativeWindowDragFromEvent,
  type ParsedPopoutRoute,
  type PopoutDockPanel
} from '../utils/popout'
import { AgentDrawer } from './agent/AgentDrawer'
import { CommandPaletteModal, type CommandPaletteTab } from './CommandPaletteModal'
import { QuickCaptureHud } from './QuickCaptureHud'
import { WorkEditorWindow } from './WorkEditorWindow'

function hexToRgba(hex: string, alpha: number): string {
  const clean = hex.replace('#', '')
  if (clean.length !== 6) return `rgba(185, 59, 48, ${alpha})`
  const r = Number.parseInt(clean.slice(0, 2), 16)
  const g = Number.parseInt(clean.slice(2, 4), 16)
  const b = Number.parseInt(clean.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function applyAppearanceToDocument(settings: DesktopSettings) {
  document.documentElement.setAttribute('data-theme', settings.theme)
  document.documentElement.setAttribute('data-font-scale', settings.fontScale)
  document.documentElement.setAttribute(
    'data-reduce-motion',
    settings.reduceMotion ? 'true' : 'false'
  )
  const rootStyle = document.documentElement.style
  if (settings.accentHex) {
    rootStyle.setProperty('--accent', settings.accentHex)
    rootStyle.setProperty('--accent-soft', hexToRgba(settings.accentHex, 0.14))
  } else {
    rootStyle.removeProperty('--accent')
    rootStyle.removeProperty('--accent-soft')
  }
  const baseFont =
    settings.fontScale === 'compact'
      ? '11.5px'
      : settings.fontScale === 'comfortable'
        ? '15px'
        : '13px'
  rootStyle.setProperty('font-size', baseFont)
}

interface PopoutWindowHostProps {
  route: ParsedPopoutRoute
}

export function PopoutWindowHost({ route }: PopoutWindowHostProps) {
  const [settings, setSettings] = useState<DesktopSettings>(() => loadLocalDesktopSettings())
  const [items, setItems] = useState<CuratorItem[]>([])
  const [mediaBaseUrl, setMediaBaseUrl] = useState<string>('')
  const [loading, setLoading] = useState<boolean>(true)
  const [toastMsg, setToastMsg] = useState<string | null>(null)
  const [isMaximized, setIsMaximized] = useState<boolean>(false)
  const [agentRunning, setAgentRunning] = useState<boolean>(false)
  const [agentGuardAction, setAgentGuardAction] = useState<null | 'close' | 'dock'>(null)
  const [syncedContextChip, setSyncedContextChip] = useState<AgentActiveContextChip | null>(null)
  const [syncedFilterLabel, setSyncedFilterLabel] = useState<string>('当前全部藏品')
  const [syncedFilteredKeys, setSyncedFilteredKeys] = useState<string[] | null>(null)
  const [activePaletteTab, setActivePaletteTab] = useState<CommandPaletteTab>(
    () => (route.initialTab as CommandPaletteTab) || 'online'
  )
  const [overrideQuickWork, setOverrideQuickWork] = useState<{
    kind: MediaCarrier
    id: number
  } | null>(null)
  const agentRunningRef = useRef(false)
  agentRunningRef.current = agentRunning
  const allowAgentCloseRef = useRef(false)

  const showToast = useCallback((msg: string) => {
    setToastMsg(msg)
    window.setTimeout(() => {
      setToastMsg((cur) => (cur === msg ? null : cur))
    }, 2600)
  }, [])

  const closeSelfWindow = useCallback(() => {
    allowAgentCloseRef.current = true
    try {
      void getCurrentWindow().close().catch(() => {
        window.close()
      })
    } catch {
      window.close()
    }
  }, [])

  const dockBackToMain = useCallback(async () => {
    const dockPanel: PopoutDockPanel = {
      kind: route.kind,
      workKind: route.workKind,
      workId: route.workId,
      initialTab: route.initialTab
    }
    try {
      await studioApi.showMainWindow()
      await emitPopoutSync({ domain: 'dock', dockPanel })
    } catch {
      // 忽略环境回退错误，继续关闭当前独立窗
    }
    closeSelfWindow()
  }, [closeSelfWindow, route.initialTab, route.kind, route.workId, route.workKind])

  const requestCloseNonEditor = useCallback(() => {
    if (route.kind === 'agent' && agentRunningRef.current) {
      setAgentGuardAction('close')
      return
    }
    closeSelfWindow()
  }, [closeSelfWindow, route.kind])

  const requestDockNonEditor = useCallback(() => {
    if (route.kind === 'agent' && agentRunningRef.current) {
      setAgentGuardAction('dock')
      return
    }
    void dockBackToMain()
  }, [dockBackToMain, route.kind])

  // 独立 AI 助手流式运行中拦截系统关窗防手误
  useEffect(() => {
    if (route.kind !== 'agent') return
    let unlisten: (() => void) | null = null
    let disposed = false
    try {
      void getCurrentWindow()
        .onCloseRequested((event) => {
          if (agentRunningRef.current && !allowAgentCloseRef.current) {
            event.preventDefault()
            setAgentGuardAction('close')
          }
        })
        .then((fn) => {
          if (disposed) {
            fn()
          } else {
            unlisten = fn
          }
        })
        .catch(() => {})
    } catch {
      // ignore
    }
    return () => {
      disposed = true
      unlisten?.()
    }
  }, [route.kind])

  const minimizeSelfWindow = useCallback(() => {
    try {
      void getCurrentWindow().minimize().catch(() => {})
    } catch {
      // ignore
    }
  }, [])

  const toggleMaximizeSelfWindow = useCallback(() => {
    try {
      const win = getCurrentWindow()
      void win
        .toggleMaximize()
        .then(() => win.isMaximized())
        .then((m) => setIsMaximized(m))
        .catch(() => {})
    } catch {
      // ignore
    }
  }, [])

  const refreshItems = useCallback(async () => {
    const list = await studioApi.listCurator()
    setItems(list)
  }, [])

  // 同步文档主题与字号
  useEffect(() => {
    applyAppearanceToDocument(settings)
  }, [settings])

  // 启动拉取基础数据与最新设置
  useEffect(() => {
    let active = true
    ;(async () => {
      try {
        const [boot, list, remoteSettings] = await Promise.all([
          studioApi.bootstrap(),
          studioApi.listCurator(),
          studioApi.getDesktopSettings().catch(() => null)
        ])
        if (!active) return
        setMediaBaseUrl(boot.mediaBaseUrl)
        setItems(list)
        if (remoteSettings && typeof remoteSettings === 'object') {
          const normalized = normalizeDesktopSettings(
            remoteSettings,
            loadLocalDesktopSettings()
          )
          setSettings(normalized)
          persistLocalDesktopSettings(normalized)
        }
      } catch (err) {
        if (active) {
          showToast(err instanceof Error ? err.message : '加载本地藏品失败')
        }
      } finally {
        if (active) setLoading(false)
      }
    })()
    return () => {
      active = false
    }
  }, [showToast])

  // 独立 AI 助手打开后主动向主窗拉取一次最新上下文与筛选范围
  useEffect(() => {
    if (route.kind !== 'agent') return
    void emitPopoutSync({ domain: 'context', requestContext: true })
  }, [route.kind])

  // 监听来自主窗口和其他独立窗口的数据/外观/上下文/参数同步事件
  useEffect(() => {
    let unlisten: (() => void) | null = null
    let cancelled = false
    void listenPopoutSync((payload) => {
      if (payload.domain === 'items' || payload.domain === 'all') {
        void refreshItems()
      }
      if (payload.domain === 'context' && !payload.requestContext) {
        if (payload.contextChip !== undefined) {
          setSyncedContextChip(payload.contextChip)
        }
        if (typeof payload.filterLabel === 'string') {
          setSyncedFilterLabel(payload.filterLabel)
        }
        if (Array.isArray(payload.filteredWorkKeys)) {
          setSyncedFilteredKeys(payload.filteredWorkKeys)
        }
      }
      if (payload.domain === 'popout-params') {
        if (route.kind === 'command-palette' && typeof payload.commandTab === 'string') {
          setActivePaletteTab(payload.commandTab as CommandPaletteTab)
        }
        if (route.kind === 'quick-capture' && payload.quickCaptureWork) {
          setOverrideQuickWork(payload.quickCaptureWork)
        }
      }
      if (payload.domain === 'appearance' || payload.domain === 'all') {
        setSettings((prev) => {
          const next: DesktopSettings = {
            ...prev,
            theme: payload.theme ?? prev.theme,
            fontScale: payload.fontScale ?? prev.fontScale,
            accentHex: payload.accentHex !== undefined ? payload.accentHex : prev.accentHex,
            reduceMotion:
              payload.reduceMotion !== undefined ? payload.reduceMotion : prev.reduceMotion
          }
          persistLocalDesktopSettings(next)
          return next
        })
      }
    }).then((fn) => {
      if (cancelled) {
        fn()
      } else {
        unlisten = fn
      }
    })
    return () => {
      cancelled = true
      unlisten?.()
    }
  }, [refreshItems, route.kind])

  const handleUpdateCell = useCallback(
    async (patch: CellPatch) => {
      await studioApi.updateCell(patch)
      await refreshItems()
      await emitPopoutSync({ domain: 'items' })
    },
    [refreshItems]
  )

  const handleItemsMutated = useCallback(async () => {
    await refreshItems()
    await emitPopoutSync({ domain: 'items' })
  }, [refreshItems])

  const handleSwitchTheme = useCallback((nextTheme: StudioThemeId) => {
    setSettings((prev) => {
      const next = { ...prev, theme: nextTheme }
      persistLocalDesktopSettings(next)
      void studioApi.saveDesktopSettings(next as unknown as Record<string, unknown>).catch(() => {})
      void emitPopoutSync({
        domain: 'appearance',
        theme: next.theme,
        fontScale: next.fontScale,
        accentHex: next.accentHex,
        reduceMotion: next.reduceMotion
      })
      return next
    })
  }, [])

  const handleApplySettingsPatch = useCallback((patch: Partial<DesktopSettings>) => {
    setSettings((prev) => {
      const next = normalizeDesktopSettings(
        { ...prev, ...patch } as unknown as Record<string, unknown>,
        DEFAULT_DESKTOP_SETTINGS
      )
      persistLocalDesktopSettings(next)
      void studioApi.saveDesktopSettings(next as unknown as Record<string, unknown>).catch(() => {})
      void emitPopoutSync({
        domain: 'appearance',
        theme: next.theme,
        fontScale: next.fontScale,
        accentHex: next.accentHex,
        reduceMotion: next.reduceMotion
      })
      return next
    })
  }, [])

  const targetWorkItem =
    route.kind === 'work-editor' && route.workKind && route.workId
      ? items.find((it) => it.kind === route.workKind && it.id === route.workId) ?? null
      : null

  const defaultQuickItem = (() => {
    if (route.kind !== 'quick-capture') return null
    if (overrideQuickWork) {
      return (
        items.find((it) => it.kind === overrideQuickWork.kind && it.id === overrideQuickWork.id) ??
        null
      )
    }
    const params = new URLSearchParams(window.location.search)
    const wk = params.get('kind')
    const id = Number.parseInt(params.get('id') ?? '', 10)
    if (wk && Number.isFinite(id)) {
      return items.find((it) => it.kind === wk && it.id === id) ?? null
    }
    return items[0] ?? null
  })()

  const agentFilteredItems = useMemo(() => {
    if (!syncedFilteredKeys) return items
    const keySet = new Set(syncedFilteredKeys)
    return items.filter((it) => keySet.has(it.syncKey))
  }, [items, syncedFilteredKeys])

  const headerLabel =
    route.kind === 'work-editor'
      ? targetWorkItem
        ? `独立窗口 · 编辑《${targetWorkItem.title}》`
        : '独立窗口 · 编辑作品资料'
      : route.kind === 'agent'
        ? '独立窗口 · 追番喵 AI 助手'
        : route.kind === 'quick-capture'
          ? '独立窗口 · 灵感速记微窗'
          : '独立窗口 · 检索与多源入库'

  return (
    <div
      style={{
        width: '100vw',
        height: '100vh',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--bg-canvas)',
        color: 'var(--text-primary)',
        overflow: 'hidden',
        border: '1px solid var(--border-strong)',
        boxSizing: 'border-box'
      }}
    >
      {/* 极简原生窗口拖拽栏：可拖出主应用到副屏，双击最大化 */}
      <div
        onMouseDown={startNativeWindowDragFromEvent}
        title="按住拖动到桌面任意位置或副屏，双击切换最大化"
        style={{
          height: 28,
          padding: '0 8px 0 10px',
          background: 'var(--bg-surface)',
          borderBottom: '1px solid var(--border-subtle)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          userSelect: 'none',
          cursor: 'grab',
          flexShrink: 0
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            minWidth: 0,
            fontSize: '0.8462rem',
            color: 'var(--text-secondary)'
          }}
        >
          <span
            style={{
              width: 7,
              height: 7,
              borderRadius: '50%',
              background: 'var(--accent)',
              flexShrink: 0
            }}
          />
          <span
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontWeight: 600
            }}
          >
            {headerLabel}
          </span>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
          {route.kind !== 'work-editor' && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 22, padding: '0 6px', fontSize: '0.7692rem' }}
              title="将当前窗口收回主窗口内显示"
              onClick={requestDockNonEditor}
            >
              收回主窗
            </button>
          )}
          <button
            type="button"
            className="btn btn-ghost"
            style={{ width: 26, height: 22, padding: 0, fontSize: '0.8462rem' }}
            title="最小化窗口"
            onClick={minimizeSelfWindow}
          >
            −
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            style={{ width: 26, height: 22, padding: 0, fontSize: '0.8462rem' }}
            title={isMaximized ? '还原窗口' : '最大化窗口'}
            onClick={toggleMaximizeSelfWindow}
          >
            {isMaximized ? '❐' : '□'}
          </button>
          {route.kind !== 'work-editor' && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ width: 26, height: 22, padding: 0, fontSize: '0.8462rem' }}
              title="关闭独立窗口"
              onClick={requestCloseNonEditor}
            >
              ✕
            </button>
          )}
        </div>
      </div>

      {/* AI 独立窗口运行中关窗/收回轻提醒 */}
      {agentGuardAction && (
        <div
          role="alertdialog"
          style={{
            padding: '8px 12px',
            background: 'color-mix(in srgb, var(--accent) 12%, var(--bg-panel))',
            borderBottom: '1px solid var(--accent)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
            flexWrap: 'wrap',
            flexShrink: 0,
            fontSize: '0.8462rem'
          }}
        >
          <span>
            {agentGuardAction === 'dock'
              ? 'AI 助手仍在生成回复，现在收回主窗口将中断当前流式视图，确定继续吗？'
              : 'AI 助手仍在生成回复，确定直接关闭此独立窗口吗？'}
          </span>
          <div style={{ display: 'inline-flex', gap: 6 }}>
            <button
              type="button"
              className="btn btn-primary"
              style={{ height: 24, fontSize: '0.8077rem' }}
              onClick={() => {
                const act = agentGuardAction
                setAgentGuardAction(null)
                if (act === 'dock') {
                  void dockBackToMain()
                } else {
                  closeSelfWindow()
                }
              }}
            >
              {agentGuardAction === 'dock' ? '仍要收回' : '仍要关闭'}
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 24, fontSize: '0.8077rem' }}
              onClick={() => setAgentGuardAction(null)}
            >
              继续等待
            </button>
          </div>
        </div>
      )}

      {/* 独立窗口内容区 */}
      <div style={{ flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden' }}>
        {loading ? (
          <div
            style={{
              height: '100%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-muted)',
              fontSize: '0.9231rem'
            }}
          >
            正在加载窗口内容...
          </div>
        ) : route.kind === 'work-editor' ? (
          targetWorkItem ? (
            <WorkEditorWindow
              item={targetWorkItem}
              mediaBaseUrl={mediaBaseUrl}
              windowIndex={0}
              focusSeq={1}
              standalone
              onClose={closeSelfWindow}
              onDockToMain={() => void dockBackToMain()}
              onUpdateCell={handleUpdateCell}
              onRefreshItems={handleItemsMutated}
            />
          ) : (
            <div
              style={{
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 10,
                color: 'var(--text-muted)'
              }}
            >
              <div>未找到对应的藏品条目，可能已被移除。</div>
              <button type="button" className="btn" onClick={closeSelfWindow}>
                关闭窗口
              </button>
            </div>
          )
        ) : route.kind === 'quick-capture' ? (
          <QuickCaptureHud
            open
            standalone
            items={items}
            defaultItem={defaultQuickItem}
            activeCompanion={null}
            onClose={closeSelfWindow}
            onSaved={handleItemsMutated}
          />
        ) : route.kind === 'command-palette' ? (
          <CommandPaletteModal
            open
            standalone
            initialTab={activePaletteTab}
            items={items}
            onClose={closeSelfWindow}
            onSelectLocalItem={(item) => {
              void studioApi
                .showMainWindow()
                .then(() =>
                  emitPopoutSync({
                    domain: 'items',
                    selectWork: { kind: item.kind, id: item.id }
                  })
                )
                .catch(() => {})
              showToast(`已在主窗口定位《${item.title}》`)
            }}
            onSwitchTheme={handleSwitchTheme}
            onOpenQuickCapture={() => {
              void emitPopoutSync({ domain: 'window-open', closeInlinePanel: 'quick-capture' })
              void openQuickCapturePopout(null)
            }}
            onItemCreated={handleItemsMutated}
          />
        ) : (
          <AgentDrawer
            open
            standalone
            floating={false}
            onClose={requestCloseNonEditor}
            onRunningChange={setAgentRunning}
            contextChip={syncedContextChip}
            filteredItems={agentFilteredItems}
            filterLabel={syncedFilterLabel}
            settings={settings}
            onApplySettingsPatch={handleApplySettingsPatch}
            onExecuteUiAction={(cmd: AgentUiActionCommand) => {
              void studioApi
                .showMainWindow()
                .then(() => emitPopoutSync({ domain: 'ui-action', uiAction: cmd }))
                .catch(() => {})
              showToast(`已在主窗口执行：${cmd.label}`)
            }}
            onOpenWork={(kind, id) => {
              if (kind === 'anime' || kind === 'book' || kind === 'game' || kind === 'audio') {
                void studioApi
                  .showMainWindow()
                  .then(() => emitPopoutSync({ domain: 'items', selectWork: { kind, id } }))
                  .catch(() => {})
              }
            }}
            onDomainsChanged={() => {
              void handleItemsMutated()
            }}
            onToast={showToast}
          />
        )}

        {toastMsg && (
          <div
            style={{
              position: 'fixed',
              left: '50%',
              bottom: 14,
              transform: 'translateX(-50%)',
              padding: '6px 14px',
              borderRadius: 999,
              background: 'var(--bg-elevated)',
              border: '1px solid var(--border-strong)',
              boxShadow: '0 8px 24px rgba(0,0,0,0.22)',
              fontSize: '0.8462rem',
              zIndex: 999
            }}
          >
            {toastMsg}
          </div>
        )}
      </div>
    </div>
  )
}
