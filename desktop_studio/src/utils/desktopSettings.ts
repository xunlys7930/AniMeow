import { DEFAULT_TAB_ORDER } from '../manualData'
import type {
  CuratorSubView,
  DesktopRestoreStrategy,
  DesktopSettings,
  DesktopUpscaleMode,
  StudioThemeId,
  WorkspaceTab
} from '../types'
import { DEFAULT_SHORTCUTS, normalizeShortcuts } from './shortcuts'
import { loadSavedUpscaleMode, saveUpscaleMode } from './videoUpscaler'

export const DESKTOP_SETTINGS_STORAGE_KEY = 'animeow.studio.desktopSettings.v1'
export const DESKTOP_SETTINGS_EVENT = 'animeow:desktop-settings-changed'

const VALID_THEMES: StudioThemeId[] = ['bunkobon', 'sakuga', 'halide', 'cassette', 'hinoki']
const VALID_SUBVIEWS: CuratorSubView[] = ['shelf', 'kanban', 'table']
const VALID_UPSCALE: DesktopUpscaleMode[] = ['off', 'spatial', 'ai-s', 'ai-m']
const VALID_RATES = [0.75, 1, 1.25, 1.5, 2]

export const DEFAULT_DESKTOP_SETTINGS: DesktopSettings = {
  theme: 'bunkobon',
  accentHex: '',
  fontScale: 'normal',
  wallpaperOpacity: 0.16,
  defaultSubView: 'shelf',
  tabOrder: [...DEFAULT_TAB_ORDER],
  upscaleMode: 'off',
  defaultPlaybackRate: 1,
  autoNextEpisode: true,
  autoFallbackLine: true,
  cloudIncludeCovers: true,
  coverStoragePolicy: 'remote',
  defaultRestoreStrategy: 'MERGE_KEEP_LOCAL',
  syncDesktopSettings: true,
  closeToTray: true,
  reduceMotion: false,
  autoCheckUpdate: true,
  shortcuts: { ...DEFAULT_SHORTCUTS }
}

function normalizeTabOrder(raw: unknown): WorkspaceTab[] {
  if (!Array.isArray(raw)) return [...DEFAULT_TAB_ORDER]
  const valid = raw.filter((x): x is WorkspaceTab =>
    typeof x === 'string' && DEFAULT_TAB_ORDER.includes(x as WorkspaceTab)
  )
  const dedup = Array.from(new Set(valid))
  for (const id of DEFAULT_TAB_ORDER) {
    if (!dedup.includes(id)) dedup.push(id)
  }
  return dedup
}

/** 将本地或云端读取到的原始配置对象归一化为完整的 DesktopSettings。 */
export function normalizeDesktopSettings(
  raw: Record<string, unknown> | null | undefined,
  fallback: DesktopSettings = DEFAULT_DESKTOP_SETTINGS
): DesktopSettings {
  if (!raw || typeof raw !== 'object') return { ...fallback }
  const theme =
    typeof raw.theme === 'string' && VALID_THEMES.includes(raw.theme as StudioThemeId)
      ? (raw.theme as StudioThemeId)
      : fallback.theme
  const accentHex = typeof raw.accentHex === 'string' ? raw.accentHex : fallback.accentHex
  const fontScale =
    raw.fontScale === 'compact' || raw.fontScale === 'normal' || raw.fontScale === 'comfortable'
      ? raw.fontScale
      : fallback.fontScale
  const wallpaperOpacity =
    typeof raw.wallpaperOpacity === 'number' && Number.isFinite(raw.wallpaperOpacity)
      ? Math.max(0.04, Math.min(0.42, raw.wallpaperOpacity))
      : fallback.wallpaperOpacity
  const defaultSubView =
    typeof raw.defaultSubView === 'string' && VALID_SUBVIEWS.includes(raw.defaultSubView as CuratorSubView)
      ? (raw.defaultSubView as CuratorSubView)
      : fallback.defaultSubView
  const tabOrder = raw.tabOrder ? normalizeTabOrder(raw.tabOrder) : [...fallback.tabOrder]
  const upscaleMode =
    typeof raw.upscaleMode === 'string' && VALID_UPSCALE.includes(raw.upscaleMode as DesktopUpscaleMode)
      ? (raw.upscaleMode as DesktopUpscaleMode)
      : fallback.upscaleMode
  const defaultPlaybackRate =
    typeof raw.defaultPlaybackRate === 'number' && VALID_RATES.includes(raw.defaultPlaybackRate)
      ? raw.defaultPlaybackRate
      : fallback.defaultPlaybackRate
  const autoNextEpisode =
    typeof raw.autoNextEpisode === 'boolean' ? raw.autoNextEpisode : fallback.autoNextEpisode
  const autoFallbackLine =
    typeof raw.autoFallbackLine === 'boolean' ? raw.autoFallbackLine : fallback.autoFallbackLine
  const cloudIncludeCovers =
    typeof raw.cloudIncludeCovers === 'boolean' ? raw.cloudIncludeCovers : fallback.cloudIncludeCovers
  const coverStoragePolicy =
    raw.coverStoragePolicy === 'remote' || raw.coverStoragePolicy === 'local' || raw.coverStoragePolicy === 'auto'
      ? raw.coverStoragePolicy
      : fallback.coverStoragePolicy
  const defaultRestoreStrategy: DesktopRestoreStrategy =
    raw.defaultRestoreStrategy === 'REPLACE' || raw.defaultRestoreStrategy === 'MERGE_KEEP_LOCAL'
      ? raw.defaultRestoreStrategy
      : fallback.defaultRestoreStrategy
  const syncDesktopSettings =
    typeof raw.syncDesktopSettings === 'boolean'
      ? raw.syncDesktopSettings
      : fallback.syncDesktopSettings
  const closeToTray =
    typeof raw.closeToTray === 'boolean' ? raw.closeToTray : fallback.closeToTray
  const reduceMotion =
    typeof raw.reduceMotion === 'boolean' ? raw.reduceMotion : fallback.reduceMotion
  const autoCheckUpdate =
    typeof raw.autoCheckUpdate === 'boolean' ? raw.autoCheckUpdate : fallback.autoCheckUpdate
  const shortcuts = normalizeShortcuts(raw.shortcuts, fallback.shortcuts)

  return {
    theme,
    accentHex,
    fontScale,
    wallpaperOpacity,
    defaultSubView,
    tabOrder,
    upscaleMode,
    defaultPlaybackRate,
    autoNextEpisode,
    autoFallbackLine,
    cloudIncludeCovers,
    coverStoragePolicy,
    defaultRestoreStrategy,
    syncDesktopSettings,
    closeToTray,
    reduceMotion,
    autoCheckUpdate,
    shortcuts
  }
}

export function loadLocalDesktopSettings(): DesktopSettings {
  try {
    const rawText = localStorage.getItem(DESKTOP_SETTINGS_STORAGE_KEY)
    const fallbackWithLegacy: DesktopSettings = {
      ...DEFAULT_DESKTOP_SETTINGS,
      upscaleMode: loadSavedUpscaleMode()
    }
    if (!rawText) return fallbackWithLegacy
    const parsed = JSON.parse(rawText) as Record<string, unknown>
    return normalizeDesktopSettings(parsed, fallbackWithLegacy)
  } catch {
    return { ...DEFAULT_DESKTOP_SETTINGS }
  }
}

export function persistLocalDesktopSettings(settings: DesktopSettings): void {
  try {
    localStorage.setItem(DESKTOP_SETTINGS_STORAGE_KEY, JSON.stringify(settings))
    saveUpscaleMode(settings.upscaleMode)
    window.dispatchEvent(new CustomEvent<DesktopSettings>(DESKTOP_SETTINGS_EVENT, { detail: settings }))
  } catch {
    // ignore storage errors
  }
}
