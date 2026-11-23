import { useCallback, useEffect, useRef, useState } from 'react'
import { STUDIO_DESKTOP_VERSION, studioApi } from '../api'

export type DesktopUpdaterStatus =
  | 'idle'
  | 'checking'
  | 'up_to_date'
  | 'available'
  | 'downloading'
  | 'verifying'
  | 'ready'
  | 'installing'
  | 'error'

export interface DesktopUpdaterState {
  status: DesktopUpdaterStatus
  currentVersion: string
  targetVersion: string | null
  notes: string
  pubDate: string | null
  downloadedBytes: number
  totalBytes: number | null
  percent: number | null
  error: string | null
}

export interface DesktopUpdaterController {
  state: DesktopUpdaterState
  checkNow: (silent?: boolean) => Promise<void>
  downloadUpdate: () => Promise<void>
  installAndRestart: () => Promise<void>
}

const INITIAL_STATE: DesktopUpdaterState = {
  status: 'idle',
  currentVersion: STUDIO_DESKTOP_VERSION,
  targetVersion: null,
  notes: '',
  pubDate: null,
  downloadedBytes: 0,
  totalBytes: null,
  percent: null,
  error: null
}

export function useDesktopUpdater(autoCheckOnStartup: boolean): DesktopUpdaterController {
  const [state, setState] = useState<DesktopUpdaterState>(INITIAL_STATE)
  const busyRef = useRef(false)
  const autoCheckedRef = useRef(false)

  useEffect(() => {
    studioApi
      .getDataDirInfo()
      .then((info) => {
        if (info?.appVersion) {
          setState((prev) => ({ ...prev, currentVersion: info.appVersion }))
        }
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    let unlisten: (() => void) | null = null
    studioApi
      .onDesktopUpdaterProgress((progress) => {
        setState((prev) => ({
          ...prev,
          status:
            progress.phase === 'ready'
              ? 'ready'
              : progress.phase === 'verifying'
                ? 'verifying'
                : 'downloading',
          downloadedBytes: progress.downloaded,
          totalBytes: progress.total,
          percent: progress.percent
        }))
      })
      .then((fn) => {
        unlisten = fn
      })
      .catch(() => {})

    return () => {
      if (unlisten) unlisten()
    }
  }, [])

  const checkNow = useCallback(async (silent = false) => {
    if (busyRef.current) return
    busyRef.current = true
    if (!silent) {
      setState((prev) => ({ ...prev, status: 'checking', error: null }))
    }

    try {
      const res = await studioApi.checkSignedDesktopUpdate()
      const targetVersion = res.available ? res.version : null
      const downloaded = targetVersion !== null && res.downloaded
      setState((prev) => ({
        ...INITIAL_STATE,
        currentVersion: res.currentVersion || prev.currentVersion,
        status: targetVersion ? (downloaded ? 'ready' : 'available') : 'up_to_date',
        targetVersion,
        notes: targetVersion ? res.notes?.trim() ?? '' : '',
        pubDate: targetVersion ? res.pubDate ?? null : null,
        downloadedBytes: downloaded && prev.targetVersion === targetVersion ? prev.downloadedBytes : 0,
        totalBytes: downloaded && prev.targetVersion === targetVersion ? prev.totalBytes : null,
        percent: downloaded ? 100 : null
      }))
    } catch {
      if (!silent) {
        setState((prev) => ({
          ...INITIAL_STATE,
          currentVersion: prev.currentVersion,
          status: 'error',
          error: '暂时无法检查更新，请稍后重试。'
        }))
      }
    } finally {
      busyRef.current = false
    }
  }, [])

  // 启动后延迟静默检查，不抢占首屏加载，也不在无更新/断网时打扰用户
  useEffect(() => {
    if (!autoCheckOnStartup || autoCheckedRef.current) return
    const timer = window.setTimeout(() => {
      autoCheckedRef.current = true
      void checkNow(true)
    }, 3200)
    return () => window.clearTimeout(timer)
  }, [autoCheckOnStartup, checkNow])

  const downloadUpdate = useCallback(async () => {
    if (busyRef.current) return
    busyRef.current = true
    setState((prev) => ({
      ...prev,
      status: 'downloading',
      downloadedBytes: 0,
      totalBytes: null,
      percent: 0,
      error: null
    }))
    try {
      const result = await studioApi.downloadSignedDesktopUpdate()
      setState((prev) => ({
        ...prev,
        status: 'ready',
        targetVersion: result.version || prev.targetVersion,
        downloadedBytes: result.totalBytes,
        totalBytes: result.totalBytes,
        percent: 100,
        error: null
      }))
    } catch (err) {
      setState((prev) => ({
        ...prev,
        status: 'error',
        error: err instanceof Error ? err.message : String(err)
      }))
    } finally {
      busyRef.current = false
    }
  }, [])

  const installAndRestart = useCallback(async () => {
    if (busyRef.current) return
    busyRef.current = true
    setState((prev) => ({ ...prev, status: 'installing', error: null }))
    try {
      await studioApi.installAndRestartDesktopUpdate()
    } catch (err) {
      busyRef.current = false
      setState((prev) => ({
        ...prev,
        status: 'error',
        error: err instanceof Error ? err.message : String(err)
      }))
    }
  }, [])

  return {
    state,
    checkNow,
    downloadUpdate,
    installAndRestart
  }
}
