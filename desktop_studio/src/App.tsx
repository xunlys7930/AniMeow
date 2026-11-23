import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { classifyBookSubkind, classifyStatusLane, studioApi } from './api'
import { AnalyticsYearbookTab } from './components/AnalyticsYearbookTab'
import { AgentDrawer } from './components/agent/AgentDrawer'
import { IconSliders, IconSpark } from './components/agent/AgentIcons'
import { BroadcastCalendarTab } from './components/BroadcastCalendarTab'
import {
  CommandPaletteModal,
  type CommandPaletteTab
} from './components/CommandPaletteModal'
import { CompanionTab } from './components/CompanionTab'
import { CuratorSheet, formatProgressText } from './components/CuratorSheet'
import { FeedbackModal } from './components/FeedbackModal'
import { InspectorDrawer } from './components/InspectorDrawer'
import { IpUniverseTab } from './components/IpUniverseTab'
import {
  LibraryMaintenanceModal,
  type MaintenanceSection
} from './components/LibraryMaintenanceModal'
import { LocalPlayerTab } from './components/LocalPlayerTab'
import { QuickCaptureHud } from './components/QuickCaptureHud'
import { ReviewStudioTab } from './components/ReviewStudioTab'
import { SettingsModal, type SettingsSection } from './components/SettingsModal'
import { useStudioDialog } from './components/StudioDialog'
import { UserManualModal } from './components/UserManualModal'
import { VaultAndSyncTab } from './components/VaultAndSyncTab'
import { WorkEditorWindow } from './components/WorkEditorWindow'
import { useDesktopUpdater } from './hooks/useDesktopUpdater'
import {
  DEFAULT_TAB_ORDER,
  WORKSPACE_TAB_DEFS,
  type ManualJumpTarget
} from './manualData'
import {
  DEFAULT_DESKTOP_SETTINGS,
  DESKTOP_SETTINGS_EVENT,
  loadLocalDesktopSettings,
  normalizeDesktopSettings,
  persistLocalDesktopSettings
} from './utils/desktopSettings'
import {
  AGENT_POPOUT_LABEL,
  COMMAND_PALETTE_POPOUT_LABEL,
  QUICK_CAPTURE_POPOUT_LABEL,
  emitPopoutSync,
  focusPopoutIfOpenSafe,
  listenPopoutSync,
  openAgentPopout,
  openCommandPalettePopout,
  openQuickCapturePopout,
  openWorkEditorPopout,
  workEditorPopoutLabel
} from './utils/popout'
import { matchesShortcut } from './utils/shortcuts'
import type {
  ActiveCompanionInfo,
  AgentActiveContextChip,
  AgentUiActionCommand,
  CellPatch,
  CuratorItem,
  CuratorSubView,
  DesktopSettings,
  LegacyDiscovery,
  LocalPlaybackTicket,
  MediaCarrier,
  StudioThemeId,
  UniverseMode,
  VaultFileRow,
  VaultRootRow,
  WorkspaceTab
} from './types'

type CarrierFilter = 'all' | MediaCarrier | 'book:novel' | 'book:manga' | 'book:other'
type SmartFilter = 'none' | 'active' | 'with_vault' | 'with_review' | 'delta'

interface OpenReviewTab {
  tabId: string
  itemKey: string
  title: string
}

interface UndoAction {
  message: string
  revertPatch: CellPatch
}

interface ThemeDescriptor {
  id: StudioThemeId
  name: string
  craftNote: string
  swatchBg: string
  swatchAccent: string
}

const STUDIO_THEMES: ThemeDescriptor[] = [
  {
    id: 'bunkobon',
    name: '文库活版 · Bunkobon',
    craftNote: '未漂白棉纸 · 宋体书眉 · 朱砂方印',
    swatchBg: '#f3eee2',
    swatchAccent: '#b53329'
  },
  {
    id: 'sakuga',
    name: '作画律表 · Sakuga',
    craftNote: '暖碳灰 · 24fps 帧尺 · 0px 制表线',
    swatchBg: '#151514',
    swatchAccent: '#e09b3d'
  },
  {
    id: 'halide',
    name: '银盐暗房 · 35mm',
    craftNote: '纯黑底片台 · 胶片齿孔 · 安全灯绯红',
    swatchBg: '#080808',
    swatchAccent: '#c93b35'
  },
  {
    id: 'cassette',
    name: '秋叶原磁带机 · Deck 98',
    craftNote: '复古暖灰机壳 · 机械硬阴影 · 荧光绿 VFD',
    swatchBg: '#dbd6ca',
    swatchAccent: '#e05224'
  },
  {
    id: 'hinoki',
    name: '桧木书斋 · Hinoki',
    craftNote: '烟熏胡桃侧栏 · 亚麻书桌 · 鼠尾草绿',
    swatchBg: '#ede8df',
    swatchAccent: '#3d6950'
  }
]

const THEME_STORAGE_KEY = 'animeow.studio.theme'
const APPEARANCE_STORAGE_KEY = 'animeow.studio.appearance.v1'

interface CustomAppearance {
  accentHex: string
  fontScale: 'compact' | 'normal' | 'comfortable'
  wallpaperDataUrl: string
  wallpaperOpacity: number
}

const DEFAULT_APPEARANCE: CustomAppearance = {
  accentHex: '',
  fontScale: 'normal',
  wallpaperDataUrl: '',
  wallpaperOpacity: 0.16
}

const ACCENT_PRESETS = [
  { name: '跟随主题', hex: '' },
  { name: '松石绿', hex: '#2e7d5b' },
  { name: '绯樱粉', hex: '#d14d72' },
  { name: '晴空蓝', hex: '#2b6cb0' },
  { name: '琥珀金', hex: '#c07a24' },
  { name: '紫阳花', hex: '#6c5ce7' },
  { name: '朱砂红', hex: '#b83b32' },
  { name: '青瓷蓝', hex: '#1f8a96' }
]

function loadInitialAppearance(): CustomAppearance {
  try {
    const raw = localStorage.getItem(APPEARANCE_STORAGE_KEY)
    if (!raw) return DEFAULT_APPEARANCE
    const p = JSON.parse(raw)
    return {
      accentHex: typeof p.accentHex === 'string' ? p.accentHex : '',
      fontScale:
        p.fontScale === 'compact' || p.fontScale === 'comfortable' ? p.fontScale : 'normal',
      wallpaperDataUrl: typeof p.wallpaperDataUrl === 'string' ? p.wallpaperDataUrl : '',
      wallpaperOpacity:
        typeof p.wallpaperOpacity === 'number'
          ? Math.max(0.04, Math.min(0.45, p.wallpaperOpacity))
          : 0.16
    }
  } catch {
    return DEFAULT_APPEARANCE
  }
}

function hexToRgba(hex: string, alpha: number): string {
  const clean = hex.replace('#', '')
  if (clean.length !== 6) return `rgba(61, 105, 80, ${alpha})`
  const r = Number.parseInt(clean.slice(0, 2), 16)
  const g = Number.parseInt(clean.slice(2, 4), 16)
  const b = Number.parseInt(clean.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function loadInitialTheme(): StudioThemeId {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY)
    if (saved && STUDIO_THEMES.some((t) => t.id === saved)) {
      return saved as StudioThemeId
    }
  } catch {
    // ignore
  }
  return 'bunkobon'
}

const EMPTY_DISCOVERY: LegacyDiscovery = {
  defaultUserDataDir: '',
  pointerTargetDir: null,
  effectiveDataDir: '',
  databaseExists: false,
  animeCount: 0,
  bookCount: 0,
  gameCount: 0,
  audioCount: 0,
  characterCount: 0,
  coverFileCount: 0
}

const TAB_ORDER_STORAGE_KEY = 'animeow.studio.tabOrder.v1'

function loadInitialTabOrder(): WorkspaceTab[] {
  try {
    const raw = localStorage.getItem(TAB_ORDER_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        const validSet = new Set<string>(DEFAULT_TAB_ORDER)
        const deduped: WorkspaceTab[] = []
        for (const id of parsed) {
          if (typeof id === 'string' && validSet.has(id) && !deduped.includes(id as WorkspaceTab)) {
            deduped.push(id as WorkspaceTab)
          }
        }
        for (const id of DEFAULT_TAB_ORDER) {
          if (!deduped.includes(id)) deduped.push(id)
        }
        return deduped
      }
    }
  } catch {
    // ignore
  }
  return [...DEFAULT_TAB_ORDER]
}

export function App() {
  const [studioDataDir, setStudioDataDir] = useState<string>('')
  const [mediaBaseUrl, setMediaBaseUrl] = useState<string>('')
  const [legacyDiscovery, setLegacyDiscovery] = useState<LegacyDiscovery>(EMPTY_DISCOVERY)
  const [items, setItems] = useState<CuratorItem[]>([])
  const [vaultRoots, setVaultRoots] = useState<VaultRootRow[]>([])
  const [vaultRevision, setVaultRevision] = useState<number>(0)
  const [activeCompanion, setActiveCompanion] = useState<ActiveCompanionInfo | null>(null)
  const [bootError, setBootError] = useState<string | null>(null)
  const [legacyBannerDismissed, setLegacyBannerDismissed] = useState<boolean>(() => {
    try {
      return localStorage.getItem('animeow.studio.legacyBannerDismissed.v1') === '1'
    } catch {
      return false
    }
  })
  const [legacyImporting, setLegacyImporting] = useState<boolean>(false)

  const [desktopSettings, setDesktopSettings] = useState<DesktopSettings>(() => {
    const base = loadLocalDesktopSettings()
    const legacyTheme = loadInitialTheme()
    const legacyAppearance = loadInitialAppearance()
    const legacyTabOrder = loadInitialTabOrder()
    const hasUnified = Boolean(localStorage.getItem('animeow.studio.desktopSettings.v1'))
    if (hasUnified) return base
    let legacySubView: CuratorSubView = base.defaultSubView
    try {
      const sv = localStorage.getItem('animeow.studio.curatorView')
      if (sv === 'shelf' || sv === 'table' || sv === 'kanban') legacySubView = sv
    } catch {
      // ignore
    }
    return {
      ...base,
      theme: legacyTheme,
      accentHex: legacyAppearance.accentHex,
      fontScale: legacyAppearance.fontScale,
      wallpaperOpacity: legacyAppearance.wallpaperOpacity,
      defaultSubView: legacySubView,
      tabOrder: legacyTabOrder
    }
  })
  const [theme, setTheme] = useState<StudioThemeId>(() => desktopSettings.theme)
  const [appearance, setAppearance] = useState<CustomAppearance>(() => ({
    ...loadInitialAppearance(),
    accentHex: desktopSettings.accentHex,
    fontScale: desktopSettings.fontScale,
    wallpaperOpacity: desktopSettings.wallpaperOpacity
  }))
  const [settingsOpen, setSettingsOpen] = useState<boolean>(false)
  const [settingsSection, setSettingsSection] = useState<SettingsSection>('appearance')
  const desktopUpdater = useDesktopUpdater(desktopSettings.autoCheckUpdate)
  const openSettingsAt = useCallback((sec: SettingsSection = 'appearance') => {
    setSettingsSection(sec)
    setSettingsOpen(true)
  }, [])
  const [showSidebar, setShowSidebar] = useState<boolean>(true)
  const [inspectorByTab, setInspectorByTab] = useState<Record<string, boolean>>({})
  const [maintenanceOpen, setMaintenanceOpen] = useState<boolean>(false)
  const [maintenanceSection, setMaintenanceSection] =
    useState<MaintenanceSection>('duplicates')
  const [maintenanceCharFocus, setMaintenanceCharFocus] = useState<{
    id: number | null
    seq: number
  }>({ id: null, seq: 0 })
  const openCharacterManage = useCallback((characterId?: number | null) => {
    setMaintenanceSection('characters_manage')
    setMaintenanceCharFocus((prev) => ({
      id: characterId ?? null,
      seq: prev.seq + 1
    }))
    setMaintenanceOpen(true)
  }, [])
  const [universeRevision, setUniverseRevision] = useState<number>(0)
  const [universeRequestedMode, setUniverseRequestedMode] = useState<{
    mode: UniverseMode
    focusNodeId?: string | null
    seq: number
  } | null>(null)
  const [manualOpen, setManualOpen] = useState<boolean>(false)
  const [feedbackOpen, setFeedbackOpen] = useState<boolean>(false)
  const [agentMode, setAgentMode] = useState<'closed' | 'drawer' | 'floating'>('closed')
  const agentOpen = agentMode === 'drawer'
  const agentFloating = agentMode === 'floating'
  const [agentToast, setAgentToast] = useState<string | null>(null)
  const agentToastTimerRef = useRef<number | null>(null)
  const showAgentToast = useCallback((msg: string) => {
    setAgentToast(msg)
    if (agentToastTimerRef.current) window.clearTimeout(agentToastTimerRef.current)
    agentToastTimerRef.current = window.setTimeout(() => setAgentToast(null), 3200)
  }, [])
  const [isMaximized, setIsMaximized] = useState<boolean>(false)
  const desktopSettingsRef = useRef<DesktopSettings>(desktopSettings)
  desktopSettingsRef.current = desktopSettings

  const [activeTab, setActiveTab] = useState<string>('curator')
  const showInspector = Boolean(inspectorByTab[activeTab])
  const setShowInspector = useCallback(
    (next: boolean | ((prev: boolean) => boolean), targetTab?: string) => {
      const tabKey = targetTab ?? activeTab
      setInspectorByTab((prev) => {
        const current = Boolean(prev[tabKey])
        const resolved = typeof next === 'function' ? next(current) : next
        if (current === resolved) return prev
        return { ...prev, [tabKey]: resolved }
      })
    },
    [activeTab]
  )
  const [visitedTabs, setVisitedTabs] = useState<Set<string>>(() => new Set(['curator']))
  const [tabOrder, setTabOrder] = useState<WorkspaceTab[]>(() => desktopSettings.tabOrder)
  const [draggingTabId, setDraggingTabId] = useState<WorkspaceTab | null>(null)
  const [dragOverTabId, setDragOverTabId] = useState<WorkspaceTab | null>(null)
  const tabDragRef = useRef<{
    tabId: WorkspaceTab
    pointerId: number
    startX: number
    dragging: boolean
  } | null>(null)
  const suppressTabClickRef = useRef<boolean>(false)

  useEffect(() => {
    setVisitedTabs((prev) => {
      if (prev.has(activeTab)) return prev
      const next = new Set(prev)
      next.add(activeTab)
      return next
    })
  }, [activeTab])

  useEffect(() => {
    document.documentElement.setAttribute(
      'data-reduce-motion',
      desktopSettings.reduceMotion ? 'true' : 'false'
    )
  }, [desktopSettings.reduceMotion])

  const applyDesktopSettings = useCallback((next: DesktopSettings, syncDisk = true) => {
    setDesktopSettings(next)
    desktopSettingsRef.current = next
    setTheme(next.theme)
    setAppearance((prev) => ({
      ...prev,
      accentHex: next.accentHex,
      fontScale: next.fontScale,
      wallpaperOpacity: next.wallpaperOpacity
    }))
    setTabOrder(next.tabOrder)
    setCuratorSubView(next.defaultSubView)
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next.theme)
      localStorage.setItem(TAB_ORDER_STORAGE_KEY, JSON.stringify(next.tabOrder))
      localStorage.setItem('animeow.studio.curatorView', next.defaultSubView)
    } catch {
      // ignore
    }
    persistLocalDesktopSettings(next)
    void emitPopoutSync({
      domain: 'appearance',
      theme: next.theme,
      fontScale: next.fontScale,
      accentHex: next.accentHex,
      reduceMotion: next.reduceMotion
    })
    if (syncDisk) {
      studioApi.setCloseToTray(next.closeToTray).catch((err) => {
        console.warn('[App] 同步托盘关闭策略失败:', err)
      })
      studioApi
        .saveDesktopSettings(next as unknown as Record<string, unknown>)
        .catch((err) => {
          console.warn('[App] 保存桌面端设置到磁盘失败:', err)
        })
    }
  }, [])

  const updateDesktopSettings = useCallback(
    (patch: Partial<DesktopSettings>) => {
      const next: DesktopSettings = { ...desktopSettingsRef.current, ...patch }
      applyDesktopSettings(next, true)
    },
    [applyDesktopSettings]
  )

  useEffect(() => {
    const onExternalSettings = (e: Event) => {
      const detail = (e as CustomEvent<DesktopSettings>).detail
      if (!detail || typeof detail !== 'object') return
      const cur = desktopSettingsRef.current
      if (
        cur.upscaleMode === detail.upscaleMode &&
        cur.cloudIncludeCovers === detail.cloudIncludeCovers &&
        cur.defaultRestoreStrategy === detail.defaultRestoreStrategy
      ) {
        return
      }
      const merged = normalizeDesktopSettings(detail as unknown as Record<string, unknown>, cur)
      desktopSettingsRef.current = merged
      setDesktopSettings(merged)
    }
    window.addEventListener(DESKTOP_SETTINGS_EVENT, onExternalSettings)
    return () => window.removeEventListener(DESKTOP_SETTINGS_EVENT, onExternalSettings)
  }, [])

  const persistTabOrder = useCallback(
    (nextOrder: WorkspaceTab[]) => {
      updateDesktopSettings({ tabOrder: nextOrder })
    },
    [updateDesktopSettings]
  )

  const resetTabOrder = useCallback(() => {
    persistTabOrder([...DEFAULT_TAB_ORDER])
  }, [persistTabOrder])

  const handleManualJump = useCallback((target: ManualJumpTarget) => {
    if (target.type === 'tab') {
      setActiveTab(target.tab)
    } else if (target.type === 'universe') {
      if (target.mode) {
        setUniverseRequestedMode((prev) => ({
          mode: target.mode!,
          focusNodeId: null,
          seq: (prev?.seq ?? 0) + 1
        }))
      }
      setActiveTab('universe')
    } else if (target.type === 'palette') {
      void focusPopoutIfOpenSafe(COMMAND_PALETTE_POPOUT_LABEL).then((focused) => {
        if (focused) {
          showAgentToast('检索窗口已在独立窗口打开，已为你切换过去')
        } else {
          setCommandInitialTab('bangumi')
          setCommandOpen(true)
        }
      })
    } else if (target.type === 'maintenance') {
      setMaintenanceSection(target.section ?? 'duplicates')
      setMaintenanceOpen(true)
    } else if (target.type === 'capture') {
      void focusPopoutIfOpenSafe(QUICK_CAPTURE_POPOUT_LABEL).then((focused) => {
        if (focused) {
          showAgentToast('灵感速记已在独立窗口打开，已为你切换过去')
        } else {
          setQuickCaptureOpen(true)
        }
      })
    } else if (target.type === 'inspector') {
      setActiveTab('curator')
      setInspectorByTab((prev) => ({ ...prev, curator: true }))
    } else if (target.type === 'settings') {
      setSettingsOpen(true)
    } else if (target.type === 'agent') {
      void focusPopoutIfOpenSafe(AGENT_POPOUT_LABEL).then((focused) => {
        if (focused) {
          setAgentMode('closed')
          showAgentToast('AI 助手已在独立窗口打开，已为你切换过去')
        } else {
          setAgentMode('drawer')
        }
      })
    }
  }, [showAgentToast])

  const handleTabPointerDown = (e: React.PointerEvent<HTMLButtonElement>, tabId: WorkspaceTab) => {
    if (e.button !== 0) return
    e.stopPropagation()
    e.currentTarget.setPointerCapture(e.pointerId)
    tabDragRef.current = {
      tabId,
      pointerId: e.pointerId,
      startX: e.clientX,
      dragging: false
    }
  }

  const handleTabPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    const st = tabDragRef.current
    if (!st || st.pointerId !== e.pointerId) return
    if (!st.dragging && Math.abs(e.clientX - st.startX) >= 6) {
      st.dragging = true
      setDraggingTabId(st.tabId)
    }
    if (!st.dragging) return
    const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null
    const targetBtn = el?.closest<HTMLElement>('[data-workspace-tab-id]')
    const targetId = targetBtn?.dataset.workspaceTabId as WorkspaceTab | undefined
    if (targetId && targetId !== st.tabId) {
      setDragOverTabId(targetId)
      setTabOrder((prev) => {
        const fromIdx = prev.indexOf(st.tabId)
        const toIdx = prev.indexOf(targetId)
        if (fromIdx === -1 || toIdx === -1 || fromIdx === toIdx) return prev
        const next = [...prev]
        const [moved] = next.splice(fromIdx, 1)
        next.splice(toIdx, 0, moved)
        try {
          localStorage.setItem(TAB_ORDER_STORAGE_KEY, JSON.stringify(next))
        } catch {
          // ignore
        }
        const updatedSettings: DesktopSettings = { ...desktopSettingsRef.current, tabOrder: next }
        setDesktopSettings(updatedSettings)
        desktopSettingsRef.current = updatedSettings
        persistLocalDesktopSettings(updatedSettings)
        studioApi
          .saveDesktopSettings(updatedSettings as unknown as Record<string, unknown>)
          .catch(() => {})
        return next
      })
    } else {
      setDragOverTabId(null)
    }
  }

  const handleTabPointerEnd = (e: React.PointerEvent<HTMLButtonElement>) => {
    const st = tabDragRef.current
    if (!st || st.pointerId !== e.pointerId) return
    if (st.dragging) {
      suppressTabClickRef.current = true
      window.setTimeout(() => {
        suppressTabClickRef.current = false
      }, 60)
    }
    tabDragRef.current = null
    setDraggingTabId(null)
    setDragOverTabId(null)
  }
  const [curatorSubView, setCuratorSubView] = useState<CuratorSubView>(
    () => desktopSettings.defaultSubView
  )
  const handleCuratorSubViewChange = useCallback(
    (view: CuratorSubView) => {
      updateDesktopSettings({ defaultSubView: view })
    },
    [updateDesktopSettings]
  )
  const [reviewTabs, setReviewTabs] = useState<OpenReviewTab[]>([])

  const [carrierFilter, setCarrierFilter] = useState<CarrierFilter>('all')
  const [smartFilter, setSmartFilter] = useState<SmartFilter>('none')
  const [seriesFilter, setSeriesFilter] = useState<string | null>(null)
  const [seriesSearch, setSeriesSearch] = useState<string>('')
  const [seriesSectionOpen, setSeriesSectionOpen] = useState<boolean>(true)
  const [smartSectionOpen, setSmartSectionOpen] = useState<boolean>(true)
  const [seriesSidebarLimit, setSeriesSidebarLimit] = useState<number>(8)
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [tagFilter, setTagFilter] = useState<string>('all')
  const [ratingFilter, setRatingFilter] = useState<'all' | 'rated' | 'unrated' | 'high'>('all')
  const [searchQuery, setSearchQuery] = useState<string>('')
  const [selectedKey, setSelectedKey] = useState<string | null>(null)

  const [commandOpen, setCommandOpen] = useState<boolean>(false)
  const [commandInitialTab, setCommandInitialTab] = useState<CommandPaletteTab>('local')
  const [quickCaptureOpen, setQuickCaptureOpen] = useState<boolean>(false)
  const [openWorkEditors, setOpenWorkEditors] = useState<
    Array<{ key: string; kind: MediaCarrier; id: number; focusSeq: number }>
  >([])

  const openWorkEditorWindowInline = useCallback((kind: MediaCarrier, id: number) => {
    const key = `${kind}:${id}`
    setOpenWorkEditors((prev) => {
      const idx = prev.findIndex((w) => w.key === key)
      if (idx >= 0) {
        return prev.map((w, i) => (i === idx ? { ...w, focusSeq: w.focusSeq + 1 } : w))
      }
      return [...prev, { key, kind, id, focusSeq: 1 }]
    })
  }, [])

  const openWorkEditorWindow = useCallback(
    (target: CuratorItem) => {
      void focusPopoutIfOpenSafe(workEditorPopoutLabel(target.kind, target.id)).then((focused) => {
        if (focused) {
          showAgentToast(`《${target.title}》正在独立窗口编辑，已为你切换过去`)
          return
        }
        openWorkEditorWindowInline(target.kind, target.id)
      })
    },
    [openWorkEditorWindowInline, showAgentToast]
  )

  const selectedItemRef = useRef<CuratorItem | null>(null)
  const agentContextSnapshotRef = useRef<{
    contextChip: AgentActiveContextChip | null
    filterLabel: string
    filteredWorkKeys: string[]
  }>({
    contextChip: null,
    filterLabel: '当前全部藏品',
    filteredWorkKeys: []
  })
  const agentUiActionHandlerRef = useRef<((cmd: AgentUiActionCommand) => void) | null>(null)

  const openCommandPalette = useCallback(
    (tab: CommandPaletteTab = 'local', toggleIfInline = false) => {
      void focusPopoutIfOpenSafe(COMMAND_PALETTE_POPOUT_LABEL).then((focused) => {
        if (focused) {
          setCommandOpen(false)
          void emitPopoutSync({ domain: 'popout-params', commandTab: tab })
          showAgentToast('检索窗口已在独立窗口打开，已为你切换过去')
          return
        }
        setCommandInitialTab(tab)
        setCommandOpen((v) => (toggleIfInline ? !v : true))
      })
    },
    [showAgentToast]
  )

  const requestOpenQuickCapture = useCallback(
    (toggleIfInline = false) => {
      void focusPopoutIfOpenSafe(QUICK_CAPTURE_POPOUT_LABEL).then((focused) => {
        if (focused) {
          setQuickCaptureOpen(false)
          const sel = selectedItemRef.current
          if (sel) {
            void emitPopoutSync({
              domain: 'popout-params',
              quickCaptureWork: { kind: sel.kind, id: sel.id }
            })
          }
          showAgentToast('灵感速记已在独立窗口打开，已为你切换过去')
          return
        }
        setQuickCaptureOpen((v) => (toggleIfInline ? !v : true))
      })
    },
    [showAgentToast]
  )

  const requestToggleAgent = useCallback(() => {
    void focusPopoutIfOpenSafe(AGENT_POPOUT_LABEL).then((focused) => {
      if (focused) {
        setAgentMode('closed')
        showAgentToast('AI 助手已在独立窗口打开，已为你切换过去')
        return
      }
      setAgentMode((m) => (m === 'drawer' ? 'closed' : 'drawer'))
    })
  }, [showAgentToast])

  const closeWorkEditorWindow = useCallback((key: string) => {
    setOpenWorkEditors((prev) => prev.filter((w) => w.key !== key))
  }, [])

  const focusWorkInCanvas = useCallback((target: CuratorItem) => {
    const preferredMode: UniverseMode = target.seriesName
      ? 'ip'
      : target.kind === 'anime' || target.kind === 'book'
        ? 'characters'
        : 'seiyuu'
    setUniverseRequestedMode((prev) => ({
      mode: preferredMode,
      focusNodeId: `work:${target.kind}:${target.id}`,
      seq: (prev?.seq ?? 0) + 1
    }))
    setActiveTab('universe')
  }, [])

  const [playerTicket, setPlayerTicket] = useState<LocalPlaybackTicket | null>(null)
  const [playerBoundKey, setPlayerBoundKey] = useState<string | null>(null)
  const [onlineWatchAnimeId, setOnlineWatchAnimeId] = useState<number | null>(null)
  const [onlineSearchTitle, setOnlineSearchTitle] = useState<string | null>(null)
  const [playerPreferredMode, setPlayerPreferredMode] = useState<'online' | 'local'>('online')

  const [undoAction, setUndoAction] = useState<UndoAction | null>(null)
  const undoTimerRef = useRef<number | null>(null)

  const tabStripRef = useRef<HTMLElement | null>(null)
  const [tabOverflow, setTabOverflow] = useState<{ left: boolean; right: boolean }>({
    left: false,
    right: false
  })

  const syncTabOverflow = useCallback(() => {
    const el = tabStripRef.current
    if (!el) return
    const left = el.scrollLeft > 2
    const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 2
    setTabOverflow((prev) =>
      prev.left === left && prev.right === right ? prev : { left, right }
    )
  }, [])

  useEffect(() => {
    const el = tabStripRef.current
    if (!el) return
    syncTabOverflow()
    const ro = new ResizeObserver(() => syncTabOverflow())
    ro.observe(el)
    window.addEventListener('resize', syncTabOverflow)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', syncTabOverflow)
    }
  }, [reviewTabs.length, syncTabOverflow])

  useEffect(() => {
    const el = tabStripRef.current
    if (!el) return
    const activeEl = el.querySelector('.workspace-tab.active') as HTMLElement | null
    activeEl?.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
    syncTabOverflow()
  }, [activeTab, reviewTabs.length, syncTabOverflow])

  const scrollTabsBy = (delta: number) => {
    tabStripRef.current?.scrollBy({ left: delta, behavior: 'smooth' })
  }

  // 一体化无边框窗口状态同步与拖拽支持
  const syncMaximizedState = useCallback(() => {
    try {
      getCurrentWindow()
        .isMaximized()
        .then(setIsMaximized)
        .catch(() => {})
    } catch {
      // 非 Tauri 浏览器预览环境忽略
    }
  }, [])

  useEffect(() => {
    syncMaximizedState()
    window.addEventListener('resize', syncMaximizedState)
    return () => window.removeEventListener('resize', syncMaximizedState)
  }, [syncMaximizedState])

  const handleDragMouseDown = useCallback(
    (e: React.MouseEvent<HTMLElement>) => {
      if (e.button !== 0) return
      const target = e.target as HTMLElement | null
      if (
        target?.closest(
          'button, input, select, textarea, a, .workspace-tab, .theme-popover, .vfd-readout'
        )
      ) {
        return
      }
      try {
        const win = getCurrentWindow()
        if (e.detail === 2) {
          win
            .toggleMaximize()
            .then(syncMaximizedState)
            .catch(() => {})
        } else {
          win.startDragging().catch(() => {})
        }
      } catch {
        // ignore
      }
    },
    [syncMaximizedState]
  )

  const handleWinMinimize = () => {
    try {
      getCurrentWindow().minimize().catch(() => {})
    } catch {
      // ignore
    }
  }

  const handleWinToggleMaximize = () => {
    try {
      getCurrentWindow()
        .toggleMaximize()
        .then(syncMaximizedState)
        .catch(() => {})
    } catch {
      // ignore
    }
  }

  const handleWinCloseToTray = () => {
    try {
      getCurrentWindow().close().catch(() => {})
    } catch {
      // ignore
    }
  }

  const switchTheme = useCallback(
    (nextTheme: StudioThemeId) => {
      updateDesktopSettings({ theme: nextTheme })
    },
    [updateDesktopSettings]
  )

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
  }, [theme])

  useEffect(() => {
    try {
      localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(appearance))
    } catch {
      // ignore
    }
    const rootStyle = document.documentElement.style
    if (appearance.accentHex) {
      rootStyle.setProperty('--accent', appearance.accentHex)
      rootStyle.setProperty('--accent-soft', hexToRgba(appearance.accentHex, 0.14))
    } else {
      rootStyle.removeProperty('--accent')
      rootStyle.removeProperty('--accent-soft')
    }
    const baseFont =
      appearance.fontScale === 'compact'
        ? '11.5px'
        : appearance.fontScale === 'comfortable'
          ? '15px'
          : '13px'
    document.documentElement.setAttribute('data-font-scale', appearance.fontScale)
    rootStyle.setProperty('font-size', baseFont)
  }, [appearance])

  const dialog = useStudioDialog()

  const handleWallpaperPick = (file: File | null) => {
    if (!file) return
    if (file.size > 4 * 1024 * 1024) {
      void dialog.alert({
        title: '壁纸图片过大',
        message: '壁纸图片请控制在 4 MB 以内，以保证启动流畅。',
        tone: 'warning'
      })
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        setAppearance((prev) => ({ ...prev, wallpaperDataUrl: reader.result as string }))
      }
    }
    reader.readAsDataURL(file)
  }

  const refreshAll = useCallback(async () => {
    try {
      const [boot, diskSettings] = await Promise.all([
        studioApi.bootstrap(),
        studioApi.getDesktopSettings().catch((err) => {
          console.warn('[App] 读取磁盘桌面设置失败，将回退本地缓存:', err)
          return null
        })
      ])
      setStudioDataDir(boot.studioDataDir)
      setMediaBaseUrl(boot.mediaBaseUrl)
      setLegacyDiscovery(boot.legacyDiscovery)
      setItems(boot.items)
      setVaultRoots(boot.vaultRoots)
      setActiveCompanion(boot.activeCompanion)
      setVaultRevision((v) => v + 1)
      setBootError(null)
      if (diskSettings && Object.keys(diskSettings).length > 0) {
        const normalized = normalizeDesktopSettings(diskSettings, desktopSettingsRef.current)
        applyDesktopSettings(normalized, false)
      } else {
        studioApi
          .saveDesktopSettings(desktopSettingsRef.current as unknown as Record<string, unknown>)
          .catch((err) => {
            console.warn('[App] 初始化同步桌面设置到磁盘失败:', err)
          })
      }
    } catch (err) {
      setBootError(err instanceof Error ? err.message : String(err))
    }
  }, [applyDesktopSettings])

  useEffect(() => {
    refreshAll()
  }, [refreshAll])

  const refreshItemsOnly = useCallback(async () => {
    const list = await studioApi.listCurator()
    setItems(list)
  }, [])

  // 监听来自独立弹出窗口（编辑窗 / AI 助手 / 速记 / 检索入库）的数据与外观同步事件
  useEffect(() => {
    let unlisten: (() => void) | null = null
    let cancelled = false
    void listenPopoutSync((payload) => {
      if (payload.domain === 'context' && payload.requestContext) {
        const snap = agentContextSnapshotRef.current
        void emitPopoutSync({
          domain: 'context',
          contextChip: snap.contextChip,
          filterLabel: snap.filterLabel,
          filteredWorkKeys: snap.filteredWorkKeys
        })
      }
      if (payload.domain === 'ui-action' && payload.uiAction) {
        agentUiActionHandlerRef.current?.(payload.uiAction)
      }
      if (payload.domain === 'dock' && payload.dockPanel) {
        const dp = payload.dockPanel
        if (dp.kind === 'agent') {
          setAgentMode('drawer')
        } else if (dp.kind === 'quick-capture') {
          setQuickCaptureOpen(true)
        } else if (dp.kind === 'command-palette') {
          setCommandInitialTab((dp.initialTab as CommandPaletteTab) || 'online')
          setCommandOpen(true)
        } else if (dp.kind === 'work-editor' && dp.workKind && dp.workId) {
          openWorkEditorWindowInline(dp.workKind, dp.workId)
        }
      }
      if (payload.closeInlinePanel) {
        if (payload.closeInlinePanel === 'agent') setAgentMode('closed')
        else if (payload.closeInlinePanel === 'quick-capture') setQuickCaptureOpen(false)
        else if (payload.closeInlinePanel === 'command-palette') setCommandOpen(false)
      }
      if (payload.domain === 'items' || payload.domain === 'all') {
        void refreshItemsOnly()
        setVaultRevision((v) => v + 1)
        setUniverseRevision((v) => v + 1)
      }
      if (payload.selectWork) {
        setSelectedKey(`${payload.selectWork.kind}:${payload.selectWork.id}`)
        setActiveTab('curator')
        setShowInspector(true, 'curator')
      }
      if (payload.domain === 'appearance' || payload.domain === 'all') {
        const cur = desktopSettingsRef.current
        const next: DesktopSettings = {
          ...cur,
          theme: payload.theme ?? cur.theme,
          fontScale: payload.fontScale ?? cur.fontScale,
          accentHex: payload.accentHex !== undefined ? payload.accentHex : cur.accentHex,
          reduceMotion:
            payload.reduceMotion !== undefined ? payload.reduceMotion : cur.reduceMotion
        }
        applyDesktopSettings(next, false)
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
  }, [applyDesktopSettings, openWorkEditorWindowInline, refreshItemsOnly, setShowInspector])

  const refreshCompanion = useCallback(async () => {
    const status = await studioApi.getCompanionStatus()
    setActiveCompanion((prev) => {
      if (prev && !status) {
        // 游戏刚刚退出并完成自动归档，刷新主库藏品时长
        refreshItemsOnly().catch((err) => {
          console.warn('[App] 游戏退出后刷新藏品列表失败:', err)
        })
      }
      return status
    })
  }, [refreshItemsOnly])

  useEffect(() => {
    if (!activeCompanion) return
    const timer = window.setInterval(() => {
      refreshCompanion().catch(() => {})
    }, 2500)
    return () => window.clearInterval(timer)
  }, [activeCompanion?.pid, refreshCompanion])

  const handleUpdateCell = useCallback(
    async (patch: CellPatch) => {
      await studioApi.updateCell(patch)
      await refreshItemsOnly()
      await emitPopoutSync({ domain: 'items' })
    },
    [refreshItemsOnly]
  )

  const handleBatchStatus = useCallback(
    async (targets: [MediaCarrier, number][], status: string) => {
      await studioApi.batchStatus(targets, status)
      await refreshItemsOnly()
    },
    [refreshItemsOnly]
  )

  const handleBatchSeries = useCallback(
    async (targets: [MediaCarrier, number][], seriesName: string, seriesRole: string) => {
      await studioApi.batchSeries(targets, seriesName, seriesRole)
      await refreshItemsOnly()
    },
    [refreshItemsOnly]
  )

  const handleStepProgressWithUndo = useCallback(
    async (item: CuratorItem, deltaSign: 1 | -1) => {
      const revertPatch: CellPatch = {
        kind: item.kind,
        id: item.id,
        status: item.status,
        progressPrimary: item.progressPrimary
      }
      const nextPatch: CellPatch = {
        kind: item.kind,
        id: item.id
      }

      if (item.kind === 'book' && item.bookChapters != null) {
        revertPatch.bookChapters = item.bookChapters
        let nextCh = Math.max(0, item.bookChapters + deltaSign)
        if (item.progressTotal > 0) {
          nextCh = Math.min(nextCh, item.progressTotal)
        }
        if (nextCh === item.bookChapters) return
        nextPatch.bookChapters = nextCh
      } else if (item.kind === 'book' && item.bookVolumes != null) {
        revertPatch.bookVolumes = item.bookVolumes
        const nextVol = Math.max(0, item.bookVolumes + deltaSign)
        if (nextVol === item.bookVolumes) return
        nextPatch.bookVolumes = nextVol
      } else {
        const unit = item.kind === 'game' ? 30 : item.kind === 'audio' ? 300 : 1
        let nextVal = Math.max(0, item.progressPrimary + deltaSign * unit)
        if ((item.kind === 'anime' || item.kind === 'book' || item.kind === 'audio') && item.progressTotal > 0) {
          nextVal = Math.min(nextVal, item.progressTotal)
        }
        if (nextVal === item.progressPrimary) return
        nextPatch.progressPrimary = nextVal
      }

      await studioApi.updateCell(nextPatch)
      const list = await studioApi.listCurator()
      setItems(list)

      const updated = list.find((it) => it.kind === item.kind && it.id === item.id)
      const summary = updated ? formatProgressText(updated) : ''

      if (undoTimerRef.current != null) {
        window.clearTimeout(undoTimerRef.current)
      }
      setUndoAction({
        message: `已更新《${item.title}》进度 → ${summary}`,
        revertPatch
      })
      undoTimerRef.current = window.setTimeout(() => {
        setUndoAction(null)
      }, 4500)
    },
    []
  )

  const handleUndoClick = async () => {
    if (!undoAction) return
    const patch = undoAction.revertPatch
    setUndoAction(null)
    if (undoTimerRef.current != null) {
      window.clearTimeout(undoTimerRef.current)
    }
    await handleUpdateCell(patch)
  }

  const handleSoftDeleteItem = useCallback(
    async (item: CuratorItem) => {
      await studioApi.softDeleteItem(item.kind, item.id)
      const itemKey = `${item.kind}:${item.id}`
      if (selectedKey === itemKey) {
        setSelectedKey(null)
        setShowInspector(false)
      }
      await refreshItemsOnly()
    },
    [refreshItemsOnly, selectedKey]
  )

  const handleSyncAnimeEpisodeWithUndo = useCallback(
    async (animeId: number, targetEpisode: number) => {
      const target = items.find((it) => it.kind === 'anime' && it.id === animeId)
      if (!target) return
      const revertPatch: CellPatch = {
        kind: 'anime',
        id: animeId,
        status: target.status,
        progressPrimary: target.progressPrimary
      }
      const boundedEp =
        target.progressTotal > 0 ? Math.min(targetEpisode, target.progressTotal) : targetEpisode
      const nextStatus =
        target.progressTotal > 0 && boundedEp >= target.progressTotal
          ? '看完'
          : target.status === '未看'
            ? '在看'
            : target.status
      await studioApi.updateCell({
        kind: 'anime',
        id: animeId,
        status: nextStatus,
        progressPrimary: boundedEp
      })
      await refreshItemsOnly()
      if (undoTimerRef.current != null) {
        window.clearTimeout(undoTimerRef.current)
      }
      setUndoAction({
        message: `已同步《${target.title}》进度 → 第 ${boundedEp} 集（${nextStatus}）`,
        revertPatch
      })
      undoTimerRef.current = window.setTimeout(() => {
        setUndoAction(null)
      }, 5000)
    },
    [items, refreshItemsOnly]
  )

  const openReviewStudio = useCallback((item: CuratorItem) => {
    const itemKey = `${item.kind}:${item.id}`
    const tabId = `review:${itemKey}`
    setReviewTabs((prev) => {
      if (prev.some((t) => t.tabId === tabId)) return prev
      return [...prev, { tabId, itemKey, title: item.title }]
    })
    setSelectedKey(itemKey)
    setActiveTab(tabId)
  }, [])

  const closeReviewTab = (tabId: string) => {
    setReviewTabs((prev) => prev.filter((t) => t.tabId !== tabId))
    if (activeTab === tabId) {
      setActiveTab('curator')
    }
  }

  const handlePlayVaultFile = useCallback(
    async (file: VaultFileRow, boundItem: CuratorItem | null) => {
      const ticket = await studioApi.openLocalPlayer(
        file.filePath,
        file.subtitlePath,
        file.scriptPath
      )
      setPlayerTicket(ticket)
      setPlayerBoundKey(boundItem ? `${boundItem.kind}:${boundItem.id}` : null)
      setPlayerPreferredMode('local')
      setActiveTab('player')
    },
    []
  )

  const handleWatchAnimeOnline = useCallback((item: CuratorItem) => {
    if (item.kind !== 'anime') return
    setOnlineWatchAnimeId(item.id)
    setPlayerPreferredMode('online')
    setActiveTab('player')
  }, [])

  const counts = useMemo(() => {
    let anime = 0
    let book = 0
    let bookNovel = 0
    let bookManga = 0
    let bookOther = 0
    let game = 0
    let audio = 0
    let active = 0
    let withVault = 0
    let withReview = 0
    let delta = 0
    const seriesMap = new Map<string, number>()

    for (const it of items) {
      if (it.kind === 'anime') anime++
      else if (it.kind === 'book') {
        book++
        const sub = classifyBookSubkind(it)
        if (sub === 'novel') bookNovel++
        else if (sub === 'manga') bookManga++
        else bookOther++
      } else if (it.kind === 'game') game++
      else if (it.kind === 'audio') audio++

      if (classifyStatusLane(it.status) === 'active') active++
      if (it.localFileCount > 0) withVault++
      if (it.review.trim().length > 0) withReview++
      if (it.scoreDelta != null && Math.abs(it.scoreDelta) >= 1.0) delta++
      if (it.seriesName) {
        seriesMap.set(it.seriesName, (seriesMap.get(it.seriesName) ?? 0) + 1)
      }
    }

    const allSeries = [...seriesMap.entries()].sort((a, b) =>
      b[1] !== a[1] ? b[1] - a[1] : a[0].localeCompare(b[0], 'zh-CN')
    )
    const statusMap = new Map<string, number>()
    const tagMap = new Map<string, number>()
    for (const it of items) {
      if (carrierFilter === 'book:novel') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'novel') continue
      } else if (carrierFilter === 'book:manga') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'manga') continue
      } else if (carrierFilter === 'book:other') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'other') continue
      } else if (carrierFilter !== 'all' && it.kind !== carrierFilter) {
        continue
      }
      const st = it.status.trim()
      if (st) statusMap.set(st, (statusMap.get(st) ?? 0) + 1)
      for (const tg of it.tags) {
        const clean = tg.trim()
        if (clean) tagMap.set(clean, (tagMap.get(clean) ?? 0) + 1)
      }
    }
    const availableStatuses = [...statusMap.entries()].sort((a, b) => b[1] - a[1])
    const availableTags = [...tagMap.entries()].sort((a, b) =>
      b[1] !== a[1] ? b[1] - a[1] : a[0].localeCompare(b[0], 'zh-CN')
    )
    return {
      total: items.length,
      anime,
      book,
      bookNovel,
      bookManga,
      bookOther,
      game,
      audio,
      active,
      withVault,
      withReview,
      delta,
      allSeries,
      availableStatuses,
      availableTags
    }
  }, [items, carrierFilter])

  const matchedSidebarSeries = useMemo(() => {
    const q = seriesSearch.trim().toLowerCase()
    if (!q) return counts.allSeries
    return counts.allSeries.filter(([name]) => name.toLowerCase().includes(q))
  }, [counts.allSeries, seriesSearch])

  const visibleSidebarSeries = useMemo(() => {
    const limit = seriesSearch.trim() ? 30 : seriesSidebarLimit
    const slice = matchedSidebarSeries.slice(0, limit)
    if (seriesFilter && !slice.some(([n]) => n === seriesFilter)) {
      const found = counts.allSeries.find(([n]) => n === seriesFilter)
      if (found) return [found, ...slice]
    }
    return slice
  }, [matchedSidebarSeries, seriesSearch, seriesSidebarLimit, seriesFilter, counts.allSeries])

  const filteredItems = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    return items.filter((it) => {
      if (carrierFilter === 'book:novel') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'novel') return false
      } else if (carrierFilter === 'book:manga') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'manga') return false
      } else if (carrierFilter === 'book:other') {
        if (it.kind !== 'book' || classifyBookSubkind(it) !== 'other') return false
      } else if (carrierFilter !== 'all' && it.kind !== carrierFilter) {
        return false
      }
      if (seriesFilter && it.seriesName !== seriesFilter) return false
      if (statusFilter !== 'all' && it.status.trim() !== statusFilter) return false
      if (tagFilter !== 'all' && !it.tags.some((t) => t.trim() === tagFilter)) return false
      if (ratingFilter === 'rated' && it.personalScore == null) return false
      if (ratingFilter === 'unrated' && it.personalScore != null) return false
      if (ratingFilter === 'high' && (it.personalScore == null || it.personalScore < 8.0)) return false
      if (smartFilter === 'active') {
        if (classifyStatusLane(it.status) !== 'active') return false
      } else if (smartFilter === 'with_vault') {
        if (it.localFileCount === 0) return false
      } else if (smartFilter === 'with_review') {
        if (it.review.trim().length === 0) return false
      } else if (smartFilter === 'delta') {
        if (it.scoreDelta == null || Math.abs(it.scoreDelta) < 1.0) return false
      }

      if (q) {
        const hay = [
          it.title,
          it.originalTitle,
          it.studioOrCircle,
          it.seriesName ?? '',
          it.mediaFormat ?? '',
          it.memo ?? '',
          it.review,
          ...it.tags
        ]
          .join(' ')
          .toLowerCase()
        if (!hay.includes(q)) return false
      }
      return true
    })
  }, [
    items,
    carrierFilter,
    smartFilter,
    seriesFilter,
    statusFilter,
    tagFilter,
    ratingFilter,
    searchQuery
  ])

  const selectedItem = useMemo(
    () => items.find((it) => `${it.kind}:${it.id}` === selectedKey) ?? null,
    [items, selectedKey]
  )

  const playerBoundItem = useMemo(
    () => items.find((it) => `${it.kind}:${it.id}` === playerBoundKey) ?? null,
    [items, playerBoundKey]
  )

  const currentThemeInfo = useMemo(
    () => STUDIO_THEMES.find((t) => t.id === theme) ?? STUDIO_THEMES[0],
    [theme]
  )

  // 全局键盘流：支持在「偏好设置 -> 快捷键」中自定义的全局组合键，以及画册内 J/K / [ / ] 浏览流
  useEffect(() => {
    const sc = desktopSettings.shortcuts
    const onKeyDown = (e: KeyboardEvent) => {
      if (matchesShortcut(e, sc.commandPalette)) {
        e.preventDefault()
        openCommandPalette('local', true)
        return
      }
      if (matchesShortcut(e, sc.toggleAgent)) {
        e.preventDefault()
        requestToggleAgent()
        return
      }
      if (matchesShortcut(e, sc.quickCapture)) {
        e.preventDefault()
        requestOpenQuickCapture(true)
        return
      }
      if (matchesShortcut(e, sc.toggleSidebar)) {
        e.preventDefault()
        setShowSidebar((v) => !v)
        return
      }
      if (matchesShortcut(e, sc.toggleInspector)) {
        e.preventDefault()
        setShowInspector((v) => !v)
        return
      }
      if (matchesShortcut(e, sc.settings)) {
        e.preventDefault()
        setSettingsOpen((v) => !v)
        return
      }
      if (matchesShortcut(e, sc.manual)) {
        e.preventDefault()
        setManualOpen((v) => !v)
        return
      }
      if (e.key === 'Escape') {
        if (manualOpen || maintenanceOpen || commandOpen || quickCaptureOpen || settingsOpen) {
          setManualOpen(false)
          setMaintenanceOpen(false)
          setCommandOpen(false)
          setQuickCaptureOpen(false)
          setSettingsOpen(false)
        } else if (showInspector) {
          setShowInspector(false)
        }
        return
      }

      const tag = (e.target as HTMLElement | null)?.tagName
      if (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        commandOpen ||
        quickCaptureOpen ||
        manualOpen ||
        maintenanceOpen ||
        settingsOpen
      ) {
        return
      }

      if (activeTab === 'curator' && filteredItems.length > 0) {
        if (e.key === 'j' || e.key === 'ArrowDown') {
          e.preventDefault()
          const idx = filteredItems.findIndex((it) => `${it.kind}:${it.id}` === selectedKey)
          const next = filteredItems[Math.min(filteredItems.length - 1, idx + 1)] ?? filteredItems[0]
          setSelectedKey(`${next.kind}:${next.id}`)
        } else if (e.key === 'k' || e.key === 'ArrowUp') {
          e.preventDefault()
          const idx = filteredItems.findIndex((it) => `${it.kind}:${it.id}` === selectedKey)
          const prev = filteredItems[Math.max(0, idx <= 0 ? 0 : idx - 1)]
          setSelectedKey(`${prev.kind}:${prev.id}`)
        } else if ((e.key === ']' || e.key === '=') && selectedItem) {
          e.preventDefault()
          handleStepProgressWithUndo(selectedItem, 1)
        } else if ((e.key === '[' || e.key === '-') && selectedItem) {
          e.preventDefault()
          handleStepProgressWithUndo(selectedItem, -1)
        } else if (e.key === 'Enter' && selectedItem) {
          e.preventDefault()
          openReviewStudio(selectedItem)
        }
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [
    activeTab,
    commandOpen,
    desktopSettings.shortcuts,
    filteredItems,
    handleStepProgressWithUndo,
    maintenanceOpen,
    manualOpen,
    openCommandPalette,
    openReviewStudio,
    quickCaptureOpen,
    requestOpenQuickCapture,
    requestToggleAgent,
    selectedItem,
    selectedKey,
    settingsOpen,
    showInspector
  ])

  const workspaceTabMap = useMemo(
    () => new Map(WORKSPACE_TAB_DEFS.map((t) => [t.id, t])),
    []
  )

  selectedItemRef.current = selectedItem

  const agentContextChip = useMemo<AgentActiveContextChip>(() => {
    const sel = selectedKey ? items.find((i) => i.syncKey === selectedKey) ?? null : null
    const inferUnit = (it: CuratorItem | null): string | null => {
      if (!it) return null
      if (it.kind === 'anime') return '集'
      if (it.kind === 'game') return '小时'
      const fmt = (it.mediaFormat ?? '').toLowerCase()
      if (fmt.includes('漫画') || fmt.includes('manga') || fmt.includes('comic')) {
        return it.bookChapters != null && it.bookChapters > 0 ? '话' : '卷'
      }
      if (fmt.includes('小说') || fmt.includes('novel')) return '卷'
      return it.bookVolumes != null && it.bookVolumes > 0 ? '卷' : '话'
    }
    return {
      activeTab,
      activeSubView: activeTab === 'curator' ? curatorSubView : null,
      workKind: sel?.kind ?? null,
      workId: sel?.id ?? null,
      workTitle: sel?.title ?? null,
      workStatus: sel?.status ?? null,
      mediaFormat: sel?.mediaFormat ?? null,
      progressUnit: inferUnit(sel),
      progressPrimary: sel?.progressPrimary ?? null,
      progressTotal: sel?.progressTotal ?? null,
      antiSpoilerEnabled: true
    }
  }, [activeTab, curatorSubView, items, selectedKey])

  const agentFilterLabel = useMemo(() => {
    const parts: string[] = []
    if (carrierFilter !== 'all') parts.push(`类型:${carrierFilter}`)
    if (statusFilter !== 'all') parts.push(`状态:${statusFilter}`)
    if (seriesFilter) parts.push(`系列:${seriesFilter}`)
    if (tagFilter !== 'all') parts.push(`标签:${tagFilter}`)
    if (searchQuery.trim()) parts.push(`关键词:${searchQuery.trim()}`)
    return parts.length > 0 ? parts.join(' / ') : '当前全部藏品'
  }, [carrierFilter, searchQuery, seriesFilter, statusFilter, tagFilter])

  const filteredWorkKeys = useMemo(() => filteredItems.map((i) => i.syncKey), [filteredItems])

  useEffect(() => {
    agentContextSnapshotRef.current = {
      contextChip: agentContextChip,
      filterLabel: agentFilterLabel,
      filteredWorkKeys
    }
    void emitPopoutSync({
      domain: 'context',
      contextChip: agentContextChip,
      filterLabel: agentFilterLabel,
      filteredWorkKeys
    })
  }, [agentContextChip, agentFilterLabel, filteredWorkKeys])

  const handleAgentUiAction = useCallback(
    (cmd: AgentUiActionCommand) => {
      const p = cmd.payload ?? {}
      if (cmd.action === 'navigate') {
        const target = String(p.target ?? 'tab')
        if (target === 'settings') {
          const sec = (p.section as SettingsSection | undefined) ?? 'appearance'
          openSettingsAt(sec)
        } else if (target === 'manual') {
          setManualOpen(true)
        } else if (target === 'maintenance') {
          setMaintenanceSection('duplicates')
          setMaintenanceOpen(true)
        } else if (target === 'series_mgr') {
          setMaintenanceSection('series_manage')
          setMaintenanceOpen(true)
        } else if (target === 'tag_mgr') {
          setMaintenanceSection('tags_manage')
          setMaintenanceOpen(true)
        } else if (target === 'character_mgr') {
          setMaintenanceSection('characters_manage')
          setMaintenanceOpen(true)
        } else if (target === 'sync') {
          setActiveTab('vault')
        } else if (target === 'quick_capture') {
          requestOpenQuickCapture(false)
        } else {
          const tab = (p.tab as string | undefined) ?? 'curator'
          setActiveTab(tab)
          if (tab === 'curator' && typeof p.subView === 'string') {
            const sv = p.subView as CuratorSubView
            if (sv === 'table' || sv === 'kanban' || sv === 'shelf') {
              handleCuratorSubViewChange(sv)
            }
          }
        }
        showAgentToast(`AI 操作：${cmd.label}`)
      } else if (cmd.action === 'apply_filter') {
        setActiveTab('curator')
        if (p.clear) {
          setSearchQuery('')
          setCarrierFilter('all')
          setStatusFilter('all')
          setSeriesFilter(null)
          setTagFilter('all')
        } else {
          if (typeof p.keyword === 'string') setSearchQuery(p.keyword)
          if (typeof p.kind === 'string') setCarrierFilter(p.kind as CarrierFilter)
          if (typeof p.status === 'string') setStatusFilter(p.status)
          if (typeof p.series === 'string') setSeriesFilter(p.series)
          if (typeof p.tag === 'string') setTagFilter(p.tag)
        }
        if (typeof p.selectWorkId === 'number') {
          const found = items.find(
            (i) =>
              i.id === p.selectWorkId && (!p.selectWorkKind || i.kind === p.selectWorkKind)
          )
          if (found) {
            setSelectedKey(found.syncKey)
            if (p.openInspector !== false) {
              setShowInspector(true, 'curator')
            }
          }
        }
        showAgentToast(`AI 操作：${cmd.label}`)
      } else if (cmd.action === 'focus_canvas') {
        const rawMode = String(p.mode ?? 'ip')
        const mode: UniverseMode =
          rawMode === 'characters' ? 'characters' : rawMode === 'seiyuu' ? 'seiyuu' : 'ip'
        const nodeId = typeof p.nodeId === 'string' ? p.nodeId : null
        setUniverseRequestedMode((prev) => ({
          mode,
          focusNodeId: nodeId,
          seq: (prev?.seq ?? 0) + 1
        }))
        setUniverseRevision((v) => v + 1)
        setActiveTab('universe')
        showAgentToast(`AI 操作：${cmd.label}`)
      }
    },
    [
      handleCuratorSubViewChange,
      items,
      openSettingsAt,
      requestOpenQuickCapture,
      setShowInspector,
      showAgentToast
    ]
  )

  agentUiActionHandlerRef.current = handleAgentUiAction

  return (
    <div className={`studio-shell ${isMaximized ? 'is-maximized' : ''}`}>
      {/* 一体化双区主结构：左侧藏品筛选树，右侧顶栏可拖拽页签与主内容区 */}
      <div className="studio-workspace">
        <aside
          className={`studio-sidebar ${showSidebar ? 'is-open' : 'is-collapsed'}`}
          aria-hidden={!showSidebar}
        >
          <div className="studio-sidebar-inner">
            <div
              className="sidebar-masthead"
              onMouseDown={handleDragMouseDown}
              title="按住可拖动窗口 · 双击切换最大化"
            >
              <div className="brand-lockup">
                <span className="brand-seal">喵</span>
                <div className="brand-titles">
                  <span className="brand-cn">追番喵</span>
                  <span className="brand-en">ANIMEOW</span>
                </div>
              </div>
              <button
                className="sidebar-collapse-btn"
                onClick={() => setShowSidebar(false)}
                title="折叠侧边栏 (Ctrl+B)"
              >
                «
              </button>
            </div>

            <div className="sidebar-scroll">
              <div>
                <div className="tree-group-title">
                  <span>藏品分类</span>
                </div>
                {(
                  [
                    ['all', '全部藏品', counts.total, false],
                    ['anime', '动画番剧', counts.anime, false],
                    ['book', '轻小说与漫画', counts.book, false],
                    ['book:novel', '小说 / 轻小说', counts.bookNovel, true],
                    ['book:manga', '漫画', counts.bookManga, true],
                    ['book:other', '其他', counts.bookOther, true],
                    ['game', 'Galgame 与游戏', counts.game, false],
                    ['audio', '音声与 ASMR', counts.audio, false]
                  ] as const
                ).map(([key, label, cnt, indented]) => (
                  <button
                    key={key}
                    className={`tree-item ${activeTab === 'curator' && carrierFilter === key && !seriesFilter ? 'active' : ''}`}
                    style={
                      indented
                        ? { paddingLeft: 24, fontSize: '0.9231rem', opacity: 0.92 }
                        : undefined
                    }
                    onClick={() => {
                      setCarrierFilter(key)
                      setSeriesFilter(null)
                      setActiveTab('curator')
                    }}
                  >
                    <span>{indented ? `└ ${label}` : label}</span>
                    <span className="tree-count">{cnt}</span>
                  </button>
                ))}
              </div>

              <div>
                <div className="tree-group-title">
                  <span
                    style={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => setSmartSectionOpen((v) => !v)}
                    title={smartSectionOpen ? '折叠快捷筛选' : '展开快捷筛选'}
                  >
                    {smartSectionOpen ? '▾' : '▸'} 快捷筛选
                  </span>
                  {smartFilter !== 'none' && (
                    <span
                      style={{ cursor: 'pointer', color: 'var(--accent)' }}
                      onClick={() => setSmartFilter('none')}
                    >
                      重置
                    </span>
                  )}
                </div>
                {smartSectionOpen &&
                  (
                    [
                      ['active', '正在追看 / 游玩 / 收听', counts.active],
                      ['with_vault', '已关联本机媒体', counts.withVault],
                      ['with_review', '已写长评', counts.withReview],
                      ['delta', '个人评分与均分差异大', counts.delta]
                    ] as const
                  ).map(([key, label, cnt]) => (
                    <button
                      key={key}
                      className={`tree-item ${smartFilter === key ? 'active' : ''}`}
                      onClick={() => {
                        setSmartFilter((prev) => (prev === key ? 'none' : key))
                        setActiveTab('curator')
                      }}
                    >
                      <span>{label}</span>
                      <span className="tree-count">{cnt}</span>
                    </button>
                  ))}
              </div>

              {(counts.allSeries.length > 0 || items.length > 0) && (
                <div>
                  <div className="tree-group-title">
                    <span
                      style={{ cursor: 'pointer', userSelect: 'none' }}
                      onClick={() => setSeriesSectionOpen((v) => !v)}
                      title={seriesSectionOpen ? '折叠系列列表' : '展开系列列表'}
                    >
                      {seriesSectionOpen ? '▾' : '▸'} 系列 ({counts.allSeries.length})
                    </span>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                      <span
                        style={{ cursor: 'pointer', color: 'var(--accent)' }}
                        title="自动扫描全库，将同系列多季/剧场版/原作轻小说一键归类或合并拆分系列"
                        onClick={() => {
                          setMaintenanceSection('series')
                          setMaintenanceOpen(true)
                        }}
                      >
                        归类
                      </span>
                      {seriesFilter && (
                        <span
                          style={{ cursor: 'pointer', color: 'var(--text-muted)' }}
                          onClick={() => setSeriesFilter(null)}
                        >
                          清除
                        </span>
                      )}
                    </span>
                  </div>
                  {seriesSectionOpen && (
                    <>
                      {counts.allSeries.length > 6 && (
                        <div style={{ padding: '2px 4px 6px' }}>
                          <input
                            className="inline-input"
                            style={{ width: '100%', height: 24, fontSize: '0.8846rem' }}
                            value={seriesSearch}
                            onChange={(e) => {
                              setSeriesSearch(e.target.value)
                              setSeriesSidebarLimit(8)
                            }}
                            placeholder={`搜全部 ${counts.allSeries.length} 个系列...`}
                          />
                        </div>
                      )}
                      {visibleSidebarSeries.map(([sName, cnt]) => (
                        <button
                          key={sName}
                          className={`tree-item ${seriesFilter === sName ? 'active' : ''}`}
                          onClick={() => {
                            setSeriesFilter((prev) => (prev === sName ? null : sName))
                            setCarrierFilter('all')
                            setActiveTab('curator')
                          }}
                        >
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {sName}
                          </span>
                          <span className="tree-count">{cnt}</span>
                        </button>
                      ))}
                      {seriesSearch.trim() && visibleSidebarSeries.length === 0 && (
                        <div style={{ padding: '4px 10px', fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                          未匹配到系列「{seriesSearch.trim()}」
                        </div>
                      )}
                      {!seriesSearch.trim() && matchedSidebarSeries.length > seriesSidebarLimit && (
                        <div style={{ display: 'flex', gap: 4, padding: '4px 4px 0' }}>
                          <button
                            className="sidebar-tool-btn"
                            style={{ flex: 1, fontSize: '0.8462rem' }}
                            onClick={() => setSeriesSidebarLimit((v) => v + 20)}
                          >
                            展开更多 ({visibleSidebarSeries.length}/{matchedSidebarSeries.length})
                          </button>
                          <button
                            className="sidebar-tool-btn"
                            style={{ fontSize: '0.8462rem', padding: '0 6px' }}
                            onClick={() => {
                              setMaintenanceSection('series_manage')
                              setMaintenanceOpen(true)
                            }}
                            title="打开系列管理面板查看与编辑全部系列"
                          >
                            全部
                          </button>
                        </div>
                      )}
                      {!seriesSearch.trim() && seriesSidebarLimit > 8 && (
                        <button
                          className="sidebar-tool-btn"
                          style={{ width: '100%', fontSize: '0.8462rem', marginTop: 2 }}
                          onClick={() => setSeriesSidebarLimit(8)}
                        >
                          收起列表
                        </button>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>

            {/* 固定底部快捷坞：无论系列多少，管理入口与设置永远一键触达、无需滚动 */}
            <div className="sidebar-footer">
              <div className="sidebar-dock-grid">
                <button
                  className="sidebar-dock-btn"
                  onClick={() => {
                    setMaintenanceSection('series_manage')
                    setMaintenanceOpen(true)
                  }}
                  title="管理全部系列：重命名、合并、解散、成员定位与增删作品"
                >
                  系列管理
                </button>
                <button
                  className="sidebar-dock-btn"
                  onClick={() => {
                    setMaintenanceSection('tags_manage')
                    setMaintenanceOpen(true)
                  }}
                  title="统一管理全库标签：重命名、合并、屏蔽与清理未使用标签"
                >
                  标签管理
                </button>
                <button
                  className="sidebar-dock-btn"
                  onClick={() => {
                    setMaintenanceSection('characters_manage')
                    setMaintenanceOpen(true)
                  }}
                  title="管理角色档案、角色与作品关联及角色间关系"
                >
                  角色管理
                </button>
                <button
                  className="sidebar-dock-btn"
                  onClick={() => {
                    setMaintenanceSection('duplicates')
                    setMaintenanceOpen(true)
                  }}
                  title="智能查重、同系列归类、回收站还原与 CSV 批量导入"
                >
                  查重 / 整理
                </button>
              </div>
              <div className="sidebar-tool-row">
                <button
                  className="sidebar-tool-btn"
                  onClick={() => requestOpenQuickCapture(false)}
                  title={`随时记录追番笔记或灵感 (${desktopSettings.shortcuts.quickCapture})`}
                >
                  速记
                </button>
                <button
                  className="sidebar-tool-btn"
                  onClick={() => setManualOpen(true)}
                  title={`查看动态使用手册与快捷键速查 (${desktopSettings.shortcuts.manual})`}
                >
                  手册
                </button>
                <button
                  className={`sidebar-tool-btn ${feedbackOpen ? 'active' : ''}`}
                  onClick={() => setFeedbackOpen(true)}
                  title="问题反馈 · 复制 QQ 群号、点击链接或扫码加入追番喵用户交流群"
                >
                  反馈
                </button>
                <button
                  className={`sidebar-tool-btn ${settingsOpen ? 'active' : ''}`}
                  onClick={() => setSettingsOpen(true)}
                  title={`偏好设置：外观、开机自启、快捷键、超分与云同步 (${desktopSettings.shortcuts.settings})`}
                >
                  设置
                </button>
              </div>
            </div>
          </div>
        </aside>

        <div className="studio-main-column">
          {/* 顶部可拖拽排序工作区页签 + 工具按钮 + 窗口控制 */}
          <header className="studio-topbar" onMouseDown={handleDragMouseDown}>
            <button
              className={`topbar-collapsed-brand ${showSidebar ? 'is-hidden' : 'is-visible'}`}
              onClick={() => setShowSidebar(true)}
              tabIndex={showSidebar ? -1 : 0}
              aria-hidden={showSidebar}
              title="展开侧边栏 (Ctrl+B)"
            >
              <span className="brand-seal" style={{ width: 20, height: 20, fontSize: '0.8462rem' }}>
                喵
              </span>
              <span className="heading-font" style={{ fontWeight: 700, fontSize: '1.0385rem' }}>
                追番喵
              </span>
            </button>

            <div className="tab-strip-wrap">
              <nav
                ref={tabStripRef}
                className="tab-strip"
                onScroll={syncTabOverflow}
                onWheel={(e) => {
                  if (e.deltaY !== 0 && tabStripRef.current) {
                    tabStripRef.current.scrollLeft += e.deltaY
                  }
                }}
              >
                {tabOrder.map((tabId) => {
                  const def = workspaceTabMap.get(tabId)
                  if (!def) return null
                  const badgeText =
                    tabId === 'player' && playerTicket
                      ? '●'
                      : tabId === 'companion' && activeCompanion
                        ? `${Math.floor(activeCompanion.elapsedSeconds / 60)}m`
                        : null
                  const isDragging = draggingTabId === tabId
                  const isDragOver = dragOverTabId === tabId && draggingTabId !== tabId
                  return (
                    <button
                      key={tabId}
                      data-workspace-tab-id={tabId}
                      className={`workspace-tab ${activeTab === tabId ? 'active' : ''} ${isDragging ? 'is-dragging' : ''} ${isDragOver ? 'is-drag-over' : ''}`}
                      title={`${def.fullTitle} — ${def.hint}（按住可左右拖动调整顺序）`}
                      onPointerDown={(e) => handleTabPointerDown(e, tabId)}
                      onPointerMove={handleTabPointerMove}
                      onPointerUp={handleTabPointerEnd}
                      onPointerCancel={handleTabPointerEnd}
                      onClick={() => {
                        if (suppressTabClickRef.current) return
                        setActiveTab(tabId)
                      }}
                    >
                      <span>{def.label}</span>
                      {badgeText && (
                        <span
                          className="mono"
                          style={{
                            fontSize: '0.7692rem',
                            color: 'var(--accent)',
                            marginLeft: 3
                          }}
                        >
                          {badgeText}
                        </span>
                      )}
                    </button>
                  )
                })}

                {reviewTabs.map((rt) => (
                  <div
                    key={rt.tabId}
                    className={`workspace-tab ${activeTab === rt.tabId ? 'active' : ''}`}
                    onClick={() => setActiveTab(rt.tabId)}
                  >
                    <span className="heading-font">漫评 · {rt.title.slice(0, 8)}</span>
                    <span
                      className="tab-close"
                      onClick={(e) => {
                        e.stopPropagation()
                        closeReviewTab(rt.tabId)
                      }}
                      title="关闭此漫评标签页"
                    >
                      ×
                    </span>
                  </div>
                ))}
              </nav>

              {(tabOverflow.left || tabOverflow.right) && (
                <div className="tab-scroll-nav">
                  <button
                    className="tab-scroll-btn"
                    disabled={!tabOverflow.left}
                    onClick={() => scrollTabsBy(-150)}
                    title="向左翻阅标签"
                  >
                    ‹
                  </button>
                  <button
                    className="tab-scroll-btn"
                    disabled={!tabOverflow.right}
                    onClick={() => scrollTabsBy(150)}
                    title="向右翻阅标签"
                  >
                    ›
                  </button>
                </div>
              )}
            </div>

            <div className="topbar-drag-spacer" />

            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
              {activeCompanion && (
                <div
                  className="vfd-readout"
                  title="点击进入进程无感伴侣"
                  style={{ cursor: 'pointer' }}
                  onClick={() => setActiveTab('companion')}
                >
                  <span>
                    ● {activeCompanion.workTitle.slice(0, 6)}{' '}
                    {Math.floor(activeCompanion.elapsedSeconds / 60)}m
                  </span>
                </div>
              )}

              {(desktopUpdater.state.status === 'available' ||
                desktopUpdater.state.status === 'downloading' ||
                desktopUpdater.state.status === 'verifying' ||
                desktopUpdater.state.status === 'ready') && (
                <button
                  className="btn btn-primary"
                  style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem' }}
                  onClick={() => openSettingsAt('storage')}
                  title="点击查看更新详情并完成应用内更新"
                >
                  {desktopUpdater.state.status === 'ready'
                    ? '更新就绪 · 重启'
                    : desktopUpdater.state.status === 'downloading'
                      ? `更新下载中 ${desktopUpdater.state.percent !== null ? `${Math.round(desktopUpdater.state.percent)}%` : ''}`
                      : desktopUpdater.state.status === 'verifying'
                        ? '正在验签...'
                        : `新版本 v${desktopUpdater.state.targetVersion ?? ''}`}
                </button>
              )}

              <button
                className="topbar-cmd-trigger"
                onClick={() => openCommandPalette('local')}
                title={`呼出全局检索、主题指令与 Bangumi 入库面板 (${desktopSettings.shortcuts.commandPalette})`}
              >
                <span>检索</span>
                <span className="topbar-kbd">{desktopSettings.shortcuts.commandPalette}</span>
              </button>

              <button
                className={`btn ${agentOpen ? 'btn-primary' : agentFloating ? 'is-soft-active' : ''}`}
                onClick={requestToggleAgent}
                title={
                  agentFloating
                    ? `AI 助手正在悬浮模式运行，点击展开完整窗口 (${desktopSettings.shortcuts.toggleAgent})`
                    : `追番喵 AI 智能体（桌面端独有 · ${desktopSettings.shortcuts.toggleAgent}）`
                }
              >
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <IconSpark size={13} />
                  <span>{agentFloating ? 'AI 悬浮中' : 'AI 助手'}</span>
                </span>
              </button>

              <button
                className={`btn ${showInspector ? 'btn-primary' : ''}`}
                onClick={() => setShowInspector((v) => !v)}
                title={`显示/隐藏右侧作品详情面板 (${desktopSettings.shortcuts.toggleInspector} / Esc)`}
              >
                详情
              </button>

              <button
                className={`btn ${settingsOpen ? 'btn-primary' : ''}`}
                onClick={() => setSettingsOpen(true)}
                title={`偏好设置：主题、开机自启、快捷键、超分与跨设备同步 (${desktopSettings.shortcuts.settings}) · 当前主题：${currentThemeInfo.name}`}
              >
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <IconSliders size={13} />
                  <span>设置</span>
                </span>
              </button>
            </div>

            {/* 随主题一体化的原生窗口控制组 */}
            <div className="win-controls">
              <button
                className="win-ctrl-btn"
                onClick={handleWinMinimize}
                title="最小化"
              >
                —
              </button>
              <button
                className="win-ctrl-btn"
                onClick={handleWinToggleMaximize}
                title={isMaximized ? '向下还原' : '最大化'}
              >
                {isMaximized ? '❐' : '▢'}
              </button>
              <button
                className="win-ctrl-btn win-close"
                onClick={handleWinCloseToTray}
                title="关闭主窗口并退驻系统托盘守护"
              >
                ✕
              </button>
            </div>
          </header>

          {bootError && (
            <div style={{ padding: '8px 14px', background: 'var(--accent-soft)', color: 'var(--danger)' }}>
              追番喵初始化异常：{bootError}
            </div>
          )}

          {!bootError &&
            items.length === 0 &&
            legacyDiscovery?.databaseExists &&
            !legacyBannerDismissed && (
              <div
                style={{
                  padding: '8px 14px',
                  background: 'var(--accent-soft)',
                  borderBottom: '1px solid var(--border-subtle)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  flexWrap: 'wrap',
                  fontSize: '0.9615rem'
                }}
              >
                <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
                  检测到本机旧版追番喵数据（共{' '}
                  {legacyDiscovery.animeCount +
                    legacyDiscovery.bookCount +
                    legacyDiscovery.gameCount +
                    legacyDiscovery.audioCount}{' '}
                  部藏品），是否直接导入只读副本？（绝不修改旧版原数据）
                </span>
                <div style={{ display: 'inline-flex', gap: 6, marginLeft: 'auto' }}>
                  <button
                    className="btn btn-primary"
                    style={{ height: 26, fontSize: '0.8846rem' }}
                    disabled={legacyImporting}
                    onClick={async () => {
                      setLegacyImporting(true)
                      try {
                        await studioApi.importLegacyCopy()
                        await refreshAll()
                      } catch (err) {
                        void dialog.alert({
                          title: '旧版数据导入失败',
                          message: err instanceof Error ? err.message : String(err),
                          tone: 'warning'
                        })
                      } finally {
                        setLegacyImporting(false)
                      }
                    }}
                  >
                    {legacyImporting ? '正在导入...' : '一键导入旧版副本'}
                  </button>
                  <button
                    className="btn"
                    style={{ height: 26, fontSize: '0.8846rem' }}
                    onClick={() => setActiveTab('vault')}
                  >
                    登录云同步
                  </button>
                  <button
                    className="btn btn-ghost"
                    style={{ height: 26, fontSize: '0.8846rem' }}
                    onClick={() => {
                      setLegacyBannerDismissed(true)
                      try {
                        localStorage.setItem('animeow.studio.legacyBannerDismissed.v1', '1')
                      } catch {
                        // ignore
                      }
                    }}
                  >
                    稍后再说
                  </button>
                </div>
              </div>
            )}

          <div className="studio-content-row">
            <main className="studio-stage" style={{ position: 'relative' }}>
              {appearance.wallpaperDataUrl && (
                <div
                  aria-hidden="true"
                  style={{
                    position: 'absolute',
                    inset: 0,
                    backgroundImage: `url(${appearance.wallpaperDataUrl})`,
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    opacity: appearance.wallpaperOpacity,
                    pointerEvents: 'none',
                    zIndex: 0
                  }}
                />
              )}
              <div style={{ position: 'relative', zIndex: 1, height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
              {/* 保活工作区：首次访问后保持挂载，切换页签不重新加载、不打断看番播放与筛选状态 */}
              {visitedTabs.has('curator') && (
                <div className={`workspace-pane ${activeTab === 'curator' ? 'is-active' : 'is-hidden'}`}>
                  <CuratorSheet
                    items={filteredItems}
                    mediaBaseUrl={mediaBaseUrl}
                    selectedKey={selectedKey}
                    subView={curatorSubView}
                    searchQuery={searchQuery}
                    onSearchQueryChange={setSearchQuery}
                    statusFilter={statusFilter}
                    onStatusFilterChange={setStatusFilter}
                    availableStatuses={counts.availableStatuses}
                    tagFilter={tagFilter}
                    onTagFilterChange={setTagFilter}
                    availableTags={counts.availableTags}
                    ratingFilter={ratingFilter}
                    onRatingFilterChange={setRatingFilter}
                    activeSeriesFilter={seriesFilter}
                    onClearSeriesFilter={() => setSeriesFilter(null)}
                    hasExtraFilters={
                      statusFilter !== 'all' ||
                      tagFilter !== 'all' ||
                      ratingFilter !== 'all' ||
                      smartFilter !== 'none' ||
                      seriesFilter !== null ||
                      searchQuery.trim().length > 0
                    }
                    onClearAllFilters={() => {
                      setStatusFilter('all')
                      setTagFilter('all')
                      setRatingFilter('all')
                      setSmartFilter('none')
                      setSeriesFilter(null)
                      setSearchQuery('')
                    }}
                    onSubViewChange={handleCuratorSubViewChange}
                    onSelectItem={(item) => {
                      setSelectedKey(`${item.kind}:${item.id}`)
                      setShowInspector(true)
                    }}
                    onOpenEditor={openWorkEditorWindow}
                    onFocusInCanvas={focusWorkInCanvas}
                    onOpenReviewStudio={openReviewStudio}
                    onWatchAnimeOnline={handleWatchAnimeOnline}
                    onOpenCommandPalette={openCommandPalette}
                    onStepProgressWithUndo={handleStepProgressWithUndo}
                    onUpdateCell={handleUpdateCell}
                    onBatchStatus={handleBatchStatus}
                    onBatchSeries={handleBatchSeries}
                    onSoftDeleteItem={handleSoftDeleteItem}
                  />
                </div>
              )}

              {visitedTabs.has('calendar') && (
                <div className={`workspace-pane ${activeTab === 'calendar' ? 'is-active' : 'is-hidden'}`}>
                  <BroadcastCalendarTab
                    items={items}
                    mediaBaseUrl={mediaBaseUrl}
                    onDataChanged={refreshItemsOnly}
                    onOpenOnlinePlay={(work, titleFallback) => {
                      if (work) {
                        setOnlineSearchTitle(null)
                        handleWatchAnimeOnline(work)
                      } else {
                        setOnlineWatchAnimeId(null)
                        setOnlineSearchTitle(titleFallback ?? null)
                        setPlayerPreferredMode('online')
                        setActiveTab('player')
                      }
                    }}
                    onSelectWork={(kind, id) => {
                      setSelectedKey(`${kind}:${id}`)
                      setShowInspector(true)
                    }}
                  />
                </div>
              )}

              {visitedTabs.has('player') && (
                <div className={`workspace-pane ${activeTab === 'player' ? 'is-active' : 'is-hidden'}`}>
                  <LocalPlayerTab
                    ticket={playerTicket}
                    boundItem={playerBoundItem}
                    items={items}
                    animeItems={items.filter((it) => it.kind === 'anime')}
                    vaultRoots={vaultRoots}
                    vaultRevision={vaultRevision}
                    onlineTargetAnime={
                      onlineWatchAnimeId != null
                        ? items.find((it) => it.kind === 'anime' && it.id === onlineWatchAnimeId) ?? null
                        : null
                    }
                    onlineSearchTitle={onlineSearchTitle}
                    preferredMode={playerPreferredMode}
                    onSelectOnlineAnime={(it) => setOnlineWatchAnimeId(it ? it.id : null)}
                    onTicketChange={(ticket, item) => {
                      setPlayerTicket(ticket)
                      setPlayerBoundKey(item ? `${item.kind}:${item.id}` : null)
                      if (ticket) setPlayerPreferredMode('local')
                    }}
                    onPlayVaultFile={handlePlayVaultFile}
                    onVaultChanged={refreshAll}
                    onUpdateCell={handleUpdateCell}
                    onSyncAnimeEpisodeWithUndo={handleSyncAnimeEpisodeWithUndo}
                  />
                </div>
              )}

              {visitedTabs.has('companion') && (
                <div className={`workspace-pane ${activeTab === 'companion' ? 'is-active' : 'is-hidden'}`}>
                  <CompanionTab
                    items={items}
                    activeCompanion={activeCompanion}
                    onRefreshCompanion={refreshCompanion}
                    onOpenQuickCapture={() => requestOpenQuickCapture(false)}
                    onSyncAnimeEpisodeWithUndo={handleSyncAnimeEpisodeWithUndo}
                    onUpdateCell={handleUpdateCell}
                  />
                </div>
              )}

              {visitedTabs.has('universe') && (
                <div className={`workspace-pane ${activeTab === 'universe' ? 'is-active' : 'is-hidden'}`}>
                  <IpUniverseTab
                    items={items}
                    mediaBaseUrl={mediaBaseUrl}
                    refreshRevision={universeRevision}
                    requestedMode={universeRequestedMode}
                    onSelectWork={(kind, id) => {
                      setSelectedKey(`${kind}:${id}`)
                      setShowInspector(true)
                    }}
                    onOpenReview={(kind, id) => {
                      const target = items.find((it) => it.kind === kind && it.id === id)
                      if (target) openReviewStudio(target)
                    }}
                    onOpenCharacterManage={(charId) => {
                      openCharacterManage(charId)
                    }}
                    onOpenSeriesManage={() => {
                      setMaintenanceSection('series_manage')
                      setMaintenanceOpen(true)
                    }}
                    onDataChanged={async () => {
                      await refreshItemsOnly()
                      setUniverseRevision((v) => v + 1)
                    }}
                  />
                </div>
              )}

              {visitedTabs.has('vault') && (
                <div className={`workspace-pane ${activeTab === 'vault' ? 'is-active' : 'is-hidden'}`}>
                  <VaultAndSyncTab
                    studioDataDir={studioDataDir}
                    legacyDiscovery={legacyDiscovery}
                    vaultRoots={vaultRoots}
                    items={items}
                    updater={desktopUpdater}
                    onRefreshAll={refreshAll}
                    onPlayVaultFile={handlePlayVaultFile}
                  />
                </div>
              )}

              {visitedTabs.has('yearbook') && (
                <div className={`workspace-pane ${activeTab === 'yearbook' ? 'is-active' : 'is-hidden'}`}>
                  <AnalyticsYearbookTab
                    items={items}
                    mediaBaseUrl={mediaBaseUrl}
                    onSelectWork={(kind, id) => {
                      setSelectedKey(`${kind}:${id}`)
                      setShowInspector(true)
                    }}
                  />
                </div>
              )}

              {reviewTabs.map((rt) => {
                const reviewItem = items.find((it) => `${it.kind}:${it.id}` === rt.itemKey)
                if (!reviewItem) return null
                return (
                  <div
                    key={rt.tabId}
                    className={`workspace-pane ${activeTab === rt.tabId ? 'is-active' : 'is-hidden'}`}
                  >
                    <ReviewStudioTab
                      item={reviewItem}
                      mediaBaseUrl={mediaBaseUrl}
                      captureRevision={vaultRevision}
                      onUpdateCell={handleUpdateCell}
                      onOpenCloudAccount={() => setActiveTab('vault')}
                    />
                  </div>
                )
              })}
              </div>
            </main>

            {showInspector &&
              (activeTab === 'curator' ||
                activeTab === 'calendar' ||
                activeTab === 'universe' ||
                activeTab === 'yearbook') && (
              <InspectorDrawer
                item={selectedItem}
                mediaBaseUrl={mediaBaseUrl}
                vaultRevision={vaultRevision}
                onClose={() => setShowInspector(false)}
                onUpdateCell={handleUpdateCell}
                onOpenEditor={openWorkEditorWindow}
                onOpenReviewStudio={openReviewStudio}
                onWatchAnimeOnline={handleWatchAnimeOnline}
                onFocusInCanvas={focusWorkInCanvas}
                onOpenCharacterManage={(charId) => {
                  openCharacterManage(charId)
                }}
                onCharactersChanged={() => {
                  setUniverseRevision((v) => v + 1)
                }}
                onPlayVaultFile={handlePlayVaultFile}
                onVaultChanged={refreshAll}
                onRefreshItems={refreshItemsOnly}
              />
            )}
          </div>
        </div>
      </div>

      {/* 进度步进撤销浮条（遵循产品必读规范：快捷 +1 操作提供一键撤销） */}
      {undoAction && (
        <div className="undo-toast">
          <span style={{ fontWeight: 600 }}>{undoAction.message}</span>
          <button className="btn btn-primary" onClick={handleUndoClick}>
            撤销
          </button>
          <button className="btn btn-ghost" onClick={() => setUndoAction(null)}>
            ×
          </button>
        </div>
      )}

      {/* Ctrl+K 全局命令与 Bangumi 入库面板 */}
      <CommandPaletteModal
        open={commandOpen}
        initialTab={commandInitialTab}
        items={items}
        onClose={() => setCommandOpen(false)}
        onPopOut={() => {
          void openCommandPalettePopout(commandInitialTab)
            .then(() => setCommandOpen(false))
            .catch((err) => showAgentToast(err instanceof Error ? err.message : '打开独立窗口失败'))
        }}
        onSelectLocalItem={(item) => {
          setSelectedKey(`${item.kind}:${item.id}`)
          setActiveTab('curator')
          setShowInspector(true, 'curator')
        }}
        onSwitchTheme={switchTheme}
        onOpenQuickCapture={() => requestOpenQuickCapture(false)}
        onItemCreated={async () => {
          await refreshItemsOnly()
          await emitPopoutSync({ domain: 'items' })
        }}
      />

      {/* Alt+Space 灵感速记微窗 */}
      <QuickCaptureHud
        open={quickCaptureOpen}
        items={items}
        defaultItem={selectedItem}
        activeCompanion={activeCompanion}
        onClose={() => setQuickCaptureOpen(false)}
        onPopOut={() => {
          void openQuickCapturePopout(
            selectedItem ? { kind: selectedItem.kind, id: selectedItem.id } : null
          )
            .then(() => setQuickCaptureOpen(false))
            .catch((err) => showAgentToast(err instanceof Error ? err.message : '打开独立窗口失败'))
        }}
        onSaved={async () => {
          await refreshItemsOnly()
          setVaultRevision((v) => v + 1)
          await emitPopoutSync({ domain: 'items' })
        }}
      />

      {/* 可多开的独立作品编辑窗口 */}
      {openWorkEditors.map((win, idx) => {
        const liveItem = items.find((it) => it.kind === win.kind && it.id === win.id)
        if (!liveItem) return null
        return (
          <WorkEditorWindow
            key={win.key}
            item={liveItem}
            mediaBaseUrl={mediaBaseUrl}
            windowIndex={idx}
            focusSeq={win.focusSeq}
            onClose={() => closeWorkEditorWindow(win.key)}
            onPopOut={() => {
              void openWorkEditorPopout(liveItem.kind, liveItem.id, liveItem.title)
                .then(() => closeWorkEditorWindow(win.key))
                .catch((err) => showAgentToast(err instanceof Error ? err.message : '打开独立窗口失败'))
            }}
            onUpdateCell={handleUpdateCell}
            onRefreshItems={async () => {
              await refreshItemsOnly()
              await emitPopoutSync({ domain: 'items' })
            }}
          />
        )
      })}

      {/* 藏品库整理与管理（系列管理 / 智能归类 / 标签管理 / 角色管理 / 查重 / 回收站 / CSV） */}
      <LibraryMaintenanceModal
        open={maintenanceOpen}
        initialSection={maintenanceSection}
        initialCharacterId={maintenanceCharFocus.id}
        initialCharacterSeq={maintenanceCharFocus.seq}
        allItems={items}
        mediaBaseUrl={mediaBaseUrl}
        onClose={() => setMaintenanceOpen(false)}
        onDataChanged={async () => {
          await refreshItemsOnly()
          setUniverseRevision((v) => v + 1)
        }}
        onOpenCanvasCharacters={() => {
          setMaintenanceOpen(false)
          setUniverseRequestedMode((prev) => ({
            mode: 'characters',
            seq: (prev?.seq ?? 0) + 1
          }))
          setUniverseRevision((v) => v + 1)
          setActiveTab('universe')
        }}
      />

      {/* 动态使用手册 */}
      <UserManualModal
        open={manualOpen}
        items={items}
        vaultRootCount={vaultRoots.length}
        tabOrder={tabOrder}
        themeName={currentThemeInfo.name}
        onClose={() => setManualOpen(false)}
        onJump={handleManualJump}
        onResetTabOrder={resetTabOrder}
      />

      {/* 桌面端统一偏好设置中心 (Ctrl+,) */}
      <SettingsModal
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        initialSection={settingsSection}
        updater={desktopUpdater}
        settings={desktopSettings}
        onChange={updateDesktopSettings}
        onResetAll={() => {
          setAppearance(DEFAULT_APPEARANCE)
          applyDesktopSettings({ ...DEFAULT_DESKTOP_SETTINGS }, true)
        }}
        onResetTabOrder={resetTabOrder}
        themes={STUDIO_THEMES}
        accentPresets={ACCENT_PRESETS}
        wallpaperDataUrl={appearance.wallpaperDataUrl}
        onPickWallpaper={handleWallpaperPick}
        onClearWallpaper={() => setAppearance((prev) => ({ ...prev, wallpaperDataUrl: '' }))}
        libraryStats={{
          total: counts.total,
          anime: counts.anime,
          book: counts.book,
          game: counts.game,
          audio: counts.audio
        }}
        onJumpToSync={() => setActiveTab('vault')}
        onOpenManual={() => setManualOpen(true)}
        onOpenFeedback={() => setFeedbackOpen(true)}
        onOpenMaintenance={() => {
          setMaintenanceSection('trash')
          setMaintenanceOpen(true)
        }}
      />

      {/* 问题反馈与 QQ 用户交流群弹窗 */}
      <FeedbackModal
        open={feedbackOpen}
        onClose={() => setFeedbackOpen(false)}
        onToast={showAgentToast}
      />

      {/* 桌面端独有 AI 智能体抽屉与悬浮模式 (Ctrl+J) */}
      <AgentDrawer
        open={agentOpen}
        floating={agentFloating}
        onClose={() => setAgentMode('closed')}
        onToggleFloating={(next) => setAgentMode(next ? 'floating' : 'drawer')}
        onPopOut={() => {
          void openAgentPopout()
            .then(() => setAgentMode('closed'))
            .catch((err) => showAgentToast(err instanceof Error ? err.message : '打开独立窗口失败'))
        }}
        contextChip={agentContextChip}
        filteredItems={filteredItems}
        filterLabel={agentFilterLabel}
        onOpenWork={(kind, id) => {
          const found = items.find((i) => i.kind === kind && i.id === id)
          if (found) {
            setActiveTab('curator')
            setSelectedKey(found.syncKey)
            setShowInspector(true, 'curator')
          } else {
            showAgentToast(`未找到对应作品 #${id}`)
          }
        }}
        settings={desktopSettings}
        onApplySettingsPatch={(patch) => updateDesktopSettings(patch)}
        onExecuteUiAction={handleAgentUiAction}
        onDomainsChanged={async (domains) => {
          if (domains.includes('items') || domains.includes('series') || domains.includes('tags')) {
            await refreshItemsOnly()
          }
          if (
            domains.includes('series') ||
            domains.includes('characters') ||
            domains.includes('items')
          ) {
            setUniverseRevision((v) => v + 1)
          }
        }}
        onToast={showAgentToast}
      />

      {agentToast && <div className="agent-floating-toast">{agentToast}</div>}
    </div>
  )
}
