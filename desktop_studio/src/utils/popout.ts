import { emit, listen, type UnlistenFn } from '@tauri-apps/api/event'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { studioApi } from '../api'
import type {
  AgentActiveContextChip,
  AgentUiActionCommand,
  MediaCarrier,
  StudioThemeId
} from '../types'

export const POPOUT_SYNC_EVENT = 'studio://popout-sync'

export const AGENT_POPOUT_LABEL = 'popout-agent'
export const QUICK_CAPTURE_POPOUT_LABEL = 'popout-quick-capture'
export const COMMAND_PALETTE_POPOUT_LABEL = 'popout-command-palette'

export function workEditorPopoutLabel(kind: MediaCarrier, id: number): string {
  return `popout-work-${kind}-${id}`
}

export type PopoutKind = 'work-editor' | 'agent' | 'quick-capture' | 'command-palette'

export interface PopoutDockPanel {
  kind: PopoutKind
  workKind?: MediaCarrier
  workId?: number
  initialTab?: string
}

export interface PopoutSyncPayload {
  domain: 'items' | 'appearance' | 'all' | 'dock' | 'window-open' | 'context' | 'ui-action' | 'popout-params'
  sourceWindow?: string
  theme?: StudioThemeId
  fontScale?: 'compact' | 'normal' | 'comfortable'
  accentHex?: string
  reduceMotion?: boolean
  selectWork?: { kind: MediaCarrier; id: number } | null
  dockPanel?: PopoutDockPanel
  closeInlinePanel?: PopoutKind
  contextChip?: AgentActiveContextChip | null
  filterLabel?: string
  filteredWorkKeys?: string[]
  requestContext?: boolean
  uiAction?: AgentUiActionCommand
  commandTab?: string
  quickCaptureWork?: { kind: MediaCarrier; id: number } | null
}

export interface ParsedPopoutRoute {
  kind: PopoutKind
  workKind?: MediaCarrier
  workId?: number
  initialTab?: string
}

export function parsePopoutRouteFromLocation(): ParsedPopoutRoute | null {
  if (typeof window === 'undefined') return null
  const params = new URLSearchParams(window.location.search)
  const raw = params.get('popout')
  if (
    raw !== 'work-editor' &&
    raw !== 'agent' &&
    raw !== 'quick-capture' &&
    raw !== 'command-palette'
  ) {
    return null
  }
  if (raw === 'work-editor') {
    const wk = params.get('kind')
    const id = Number.parseInt(params.get('id') ?? '', 10)
    if (
      (wk === 'anime' || wk === 'book' || wk === 'game' || wk === 'audio') &&
      Number.isFinite(id) &&
      id > 0
    ) {
      return { kind: 'work-editor', workKind: wk, workId: id }
    }
    return null
  }
  if (raw === 'command-palette') {
    return { kind: 'command-palette', initialTab: params.get('tab') ?? 'online' }
  }
  return { kind: raw }
}

export function getCurrentWindowLabelSafe(): string {
  try {
    return getCurrentWindow().label
  } catch {
    return 'main'
  }
}

export async function emitPopoutSync(payload: Omit<PopoutSyncPayload, 'sourceWindow'>): Promise<void> {
  try {
    await emit(POPOUT_SYNC_EVENT, {
      ...payload,
      sourceWindow: getCurrentWindowLabelSafe()
    } satisfies PopoutSyncPayload)
  } catch {
    // 非 Tauri 浏览器预览环境忽略跨窗口广播
  }
}

export async function listenPopoutSync(
  handler: (payload: PopoutSyncPayload) => void
): Promise<UnlistenFn> {
  try {
    const myLabel = getCurrentWindowLabelSafe()
    return await listen<PopoutSyncPayload>(POPOUT_SYNC_EVENT, (ev) => {
      if (ev.payload?.sourceWindow && ev.payload.sourceWindow === myLabel) {
        return
      }
      handler(ev.payload)
    })
  } catch {
    return () => {}
  }
}

export function startNativeWindowDragFromEvent(e: React.MouseEvent<HTMLElement>): void {
  if (e.button !== 0) return
  const target = e.target as HTMLElement | null
  if (target?.closest('button, input, select, textarea, a, [role="button"]')) {
    return
  }
  try {
    const win = getCurrentWindow()
    if (e.detail === 2) {
      void win.toggleMaximize().catch(() => {})
    } else {
      void win.startDragging().catch(() => {})
    }
  } catch {
    // 非 Tauri 环境忽略
  }
}

export async function focusPopoutIfOpenSafe(label: string): Promise<boolean> {
  try {
    return await studioApi.focusPopoutIfOpen(label)
  } catch {
    return false
  }
}

export async function openWorkEditorPopout(
  kind: MediaCarrier,
  id: number,
  title: string
): Promise<void> {
  const query = new URLSearchParams({
    popout: 'work-editor',
    kind,
    id: String(id)
  }).toString()
  await studioApi.openPopoutWindow({
    label: workEditorPopoutLabel(kind, id),
    title: `编辑作品 · ${title}`,
    query,
    width: 620,
    height: 700
  })
}

export async function openAgentPopout(): Promise<void> {
  await studioApi.openPopoutWindow({
    label: AGENT_POPOUT_LABEL,
    title: '追番喵 · AI 助手',
    query: 'popout=agent',
    width: 580,
    height: 760
  })
}

export async function openQuickCapturePopout(defaultItem?: {
  kind: MediaCarrier
  id: number
} | null): Promise<void> {
  const sp = new URLSearchParams({ popout: 'quick-capture' })
  if (defaultItem) {
    sp.set('kind', defaultItem.kind)
    sp.set('id', String(defaultItem.id))
  }
  await studioApi.openPopoutWindow({
    label: QUICK_CAPTURE_POPOUT_LABEL,
    title: '追番喵 · 灵感速记',
    query: sp.toString(),
    width: 450,
    height: 410
  })
}

export async function openCommandPalettePopout(initialTab = 'online'): Promise<void> {
  const sp = new URLSearchParams({ popout: 'command-palette', tab: initialTab })
  await studioApi.openPopoutWindow({
    label: COMMAND_PALETTE_POPOUT_LABEL,
    title: '追番喵 · 检索与在线入库',
    query: sp.toString(),
    width: 820,
    height: 640
  })
}
