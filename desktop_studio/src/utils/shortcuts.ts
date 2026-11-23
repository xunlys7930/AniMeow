import type { DesktopShortcutAction, DesktopShortcuts } from '../types'

export const DEFAULT_SHORTCUTS: DesktopShortcuts = {
  commandPalette: 'Ctrl+K',
  toggleAgent: 'Ctrl+J',
  settings: 'Ctrl+,',
  toggleSidebar: 'Ctrl+B',
  toggleInspector: 'Ctrl+I',
  quickCapture: 'Alt+Space',
  manual: 'F1'
}

export interface ShortcutMeta {
  action: DesktopShortcutAction
  label: string
  hint: string
}

export const SHORTCUT_META_LIST: ShortcutMeta[] = [
  { action: 'commandPalette', label: '全局检索与指令面板', hint: '快速搜本地库、Bangumi、VNDB 与 DLsite' },
  { action: 'toggleAgent', label: '唤出 / 收起 AI 智能体', hint: '私人追番策展助手：查库、聊番、防剧透、控设置与整理' },
  { action: 'settings', label: '打开偏好设置', hint: '随时调整主题、超分、启动项与快捷键' },
  { action: 'toggleSidebar', label: '折叠 / 展开左侧栏', hint: '宽屏专注看画册或画布时收起导航栏' },
  { action: 'toggleInspector', label: '显示 / 隐藏右侧详情', hint: '快速查看或收起选中作品的详情抽屉' },
  { action: 'quickCapture', label: '呼出灵感速记', hint: '看番或推游途中随时记一笔笔记' },
  { action: 'manual', label: '打开动态使用手册', hint: '查看功能说明与操作速查' }
]

const MODIFIER_KEYS = new Set(['Control', 'Alt', 'Shift', 'Meta', 'OS'])

function normalizePrimaryKey(e: KeyboardEvent): string | null {
  if (MODIFIER_KEYS.has(e.key)) return null
  if (e.code === 'Space' || e.key === ' ') return 'Space'
  if (/^F([1-9]|1[0-2])$/i.test(e.key)) return e.key.toUpperCase()
  if (e.key.length === 1) {
    const ch = e.key
    if (/^[a-z]$/i.test(ch)) return ch.toUpperCase()
    return ch
  }
  if (e.key === 'Escape' || e.key === 'Tab' || e.key === 'Enter' || e.key === 'Backspace') {
    return null
  }
  return e.key
}

/** 将键盘按下事件格式化为规范快捷键串（例如 Ctrl+K、Alt+Space、F1）；仅按修饰键时返回 null。 */
export function formatShortcutFromEvent(e: KeyboardEvent): string | null {
  const key = normalizePrimaryKey(e)
  if (!key) return null
  const isFnKey = /^F([1-9]|1[0-2])$/.test(key)
  const hasMod = e.ctrlKey || e.metaKey || e.altKey || e.shiftKey
  // 单键字母/符号容易与输入框冲突，除 F1-F12 外要求至少带一个修饰键
  if (!isFnKey && !hasMod) return null
  const parts: string[] = []
  if (e.ctrlKey || e.metaKey) parts.push('Ctrl')
  if (e.altKey) parts.push('Alt')
  if (e.shiftKey) parts.push('Shift')
  parts.push(key)
  return parts.join('+')
}

/** 判断 KeyboardEvent 是否匹配规范快捷键字符串。 */
export function matchesShortcut(e: KeyboardEvent, combo: string | undefined): boolean {
  if (!combo || !combo.trim()) return false
  const tokens = combo
    .split('+')
    .map((t) => t.trim())
    .filter(Boolean)
  if (tokens.length === 0) return false

  const needCtrl = tokens.some((t) => t.toLowerCase() === 'ctrl' || t.toLowerCase() === 'meta')
  const needAlt = tokens.some((t) => t.toLowerCase() === 'alt')
  const needShift = tokens.some((t) => t.toLowerCase() === 'shift')
  const mainToken = tokens[tokens.length - 1]

  if ((e.ctrlKey || e.metaKey) !== needCtrl) return false
  if (e.altKey !== needAlt) return false
  if (e.shiftKey !== needShift) return false

  if (mainToken.toLowerCase() === 'space') {
    return e.code === 'Space' || e.key === ' '
  }
  if (/^F([1-9]|1[0-2])$/i.test(mainToken)) {
    return e.key.toUpperCase() === mainToken.toUpperCase()
  }
  if (mainToken.length === 1) {
    return e.key.toLowerCase() === mainToken.toLowerCase()
  }
  return e.key.toLowerCase() === mainToken.toLowerCase()
}

export function normalizeShortcuts(raw: unknown, fallback: DesktopShortcuts = DEFAULT_SHORTCUTS): DesktopShortcuts {
  if (!raw || typeof raw !== 'object') return { ...fallback }
  const obj = raw as Record<string, unknown>
  const pick = (k: DesktopShortcutAction): string => {
    const val = obj[k]
    return typeof val === 'string' && val.trim().length > 0 ? val.trim() : fallback[k]
  }
  return {
    commandPalette: pick('commandPalette'),
    toggleAgent: pick('toggleAgent'),
    settings: pick('settings'),
    toggleSidebar: pick('toggleSidebar'),
    toggleInspector: pick('toggleInspector'),
    quickCapture: pick('quickCapture'),
    manual: pick('manual')
  }
}
