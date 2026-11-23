import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Hls from 'hls.js'
import { studioApi } from '../api'
import { LoadingSpinner, SkeletonRows } from './LoadingIndicator'
import { StudioSelect } from './StudioSelect'
import { UpscaledVideoStage } from './UpscaledVideoStage'
import {
  DESKTOP_SETTINGS_EVENT,
  loadLocalDesktopSettings,
  persistLocalDesktopSettings
} from '../utils/desktopSettings'
import {
  VIDEO_UPSCALE_OPTIONS,
  VideoUpscaleMode
} from '../utils/videoUpscaler'
import type {
  CuratorItem,
  PlaybackEpisode,
  PlaybackLine,
  PlaybackSource,
  PlaybackWork,
  RemoteStreamTicket
} from '../types'

interface OnlineAnimePlayerProps {
  targetAnime: CuratorItem | null
  animeItems: CuratorItem[]
  initialSearchTitle?: string | null
  onSelectTargetAnime: (item: CuratorItem | null) => void
  onSyncAnimeEpisodeWithUndo: (animeId: number, nextEpisode: number) => Promise<void>
}

interface SourceSearchGroup {
  source: PlaybackSource
  loading: boolean
  results: PlaybackWork[]
  error: string | null
}

interface ResumeCheckpoint {
  sourceId: string
  sourceName: string
  work: PlaybackWork
  lineId: string
  episode: PlaybackEpisode
  currentTime: number
  updatedAt: number
}

const RESUME_STORAGE_KEY = 'animeow_studio_online_resume_v1'

function loadResumeMap(): Record<string, ResumeCheckpoint> {
  try {
    const raw = localStorage.getItem(RESUME_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function saveResumeEntry(key: string, cp: ResumeCheckpoint): void {
  try {
    const map = loadResumeMap()
    map[key] = cp
    // 最多保留最近 80 条追番续播记录
    const entries = Object.entries(map).sort((a, b) => b[1].updatedAt - a[1].updatedAt).slice(0, 80)
    localStorage.setItem(RESUME_STORAGE_KEY, JSON.stringify(Object.fromEntries(entries)))
  } catch {
    // 忽略存储空间受限
  }
}

function formatClock(sec: number): string {
  if (!Number.isFinite(sec) || sec <= 0) return '00:00'
  const total = Math.floor(sec)
  const mins = Math.floor(total / 60)
  const rem = total % 60
  return `${String(mins).padStart(2, '0')}:${String(rem).padStart(2, '0')}`
}

export const OnlineAnimePlayer: React.FC<OnlineAnimePlayerProps> = ({
  targetAnime,
  animeItems,
  initialSearchTitle,
  onSelectTargetAnime,
  onSyncAnimeEpisodeWithUndo
}) => {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const hlsRef = useRef<Hls | null>(null)
  const searchSerialRef = useRef(0)
  const epLoadSerialRef = useRef(0)
  const resolveSerialRef = useRef(0)
  const pendingSeekSecRef = useRef<number | null>(null)
  const lastAutoSearchKeyRef = useRef<string>('')
  const lastSavedClockRef = useRef<number>(0)

  const [sources, setSources] = useState<PlaybackSource[]>([])
  const [loadingSources, setLoadingSources] = useState(false)
  const [sourcesError, setSourcesError] = useState<string | null>(null)

  const [query, setQuery] = useState<string>(
    targetAnime?.title ?? initialSearchTitle ?? ''
  )
  const [searchGroups, setSearchGroups] = useState<SourceSearchGroup[]>([])
  const [activeSourceFilter, setActiveSourceFilter] = useState<string>('ALL')

  const [selectedSourceId, setSelectedSourceId] = useState<string>('')
  const [selectedWork, setSelectedWork] = useState<PlaybackWork | null>(null)
  const [lines, setLines] = useState<PlaybackLine[]>([])
  const [loadingLines, setLoadingLines] = useState(false)
  const [selectedLineId, setSelectedLineId] = useState<string>('')

  const [activeEpisode, setActiveEpisode] = useState<PlaybackEpisode | null>(null)
  const [resolving, setResolving] = useState(false)
  const [resolvingHint, setResolvingHint] = useState<string>('')
  const initialDesktopPrefs = useMemo(() => loadLocalDesktopSettings(), [])
  const [streamTicket, setStreamTicket] = useState<RemoteStreamTicket | null>(null)
  const [playbackRate, setPlaybackRate] = useState<number>(initialDesktopPrefs.defaultPlaybackRate)
  const [upscaleMode, setUpscaleMode] = useState<VideoUpscaleMode>(initialDesktopPrefs.upscaleMode)
  const [upscaleStatus, setUpscaleStatus] = useState<string>('')
  const [upscaleCanvasActive, setUpscaleCanvasActive] = useState<boolean>(false)
  const [autoFallbackLine, setAutoFallbackLine] = useState<boolean>(initialDesktopPrefs.autoFallbackLine)
  const [autoNextEpisode, setAutoNextEpisode] = useState<boolean>(initialDesktopPrefs.autoNextEpisode)
  const [statusMsg, setStatusMsg] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [resumeBanner, setResumeBanner] = useState<ResumeCheckpoint | null>(null)
  const upscaleCanvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const onSettingsChanged = (e: Event) => {
      const detail = (e as CustomEvent).detail
      if (!detail || typeof detail !== 'object') return
      if (detail.upscaleMode) setUpscaleMode(detail.upscaleMode)
      if (typeof detail.defaultPlaybackRate === 'number') setPlaybackRate(detail.defaultPlaybackRate)
      if (typeof detail.autoNextEpisode === 'boolean') setAutoNextEpisode(detail.autoNextEpisode)
      if (typeof detail.autoFallbackLine === 'boolean') setAutoFallbackLine(detail.autoFallbackLine)
    }
    window.addEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
    return () => window.removeEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
  }, [])

  const resumeKey = useMemo(() => {
    if (targetAnime) return `anime:${targetAnime.id}`
    if (selectedWork) return `work:${selectedSourceId}:${selectedWork.id}`
    return ''
  }, [targetAnime, selectedSourceId, selectedWork])

  // 加载对应动画的续播记忆
  useEffect(() => {
    if (!targetAnime) {
      setResumeBanner(null)
      return
    }
    const saved = loadResumeMap()[`anime:${targetAnime.id}`] ?? null
    setResumeBanner(saved)
  }, [targetAnime])

  const loadSources = useCallback(async () => {
    setLoadingSources(true)
    setSourcesError(null)
    try {
      const list = await studioApi.playbackSources()
      setSources(list)
      if (list.length === 0) {
        setSourcesError('播放源列表为空，请稍后重试')
      }
    } catch (err) {
      setSourcesError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoadingSources(false)
    }
  }, [])

  useEffect(() => {
    void loadSources()
  }, [loadSources])

  const loadEpisodesForWork = useCallback(
    async (sourceId: string, work: PlaybackWork, preferredLineId?: string) => {
      const serial = ++epLoadSerialRef.current
      setSelectedSourceId(sourceId)
      setSelectedWork(work)
      setLoadingLines(true)
      setErrorMsg(null)
      setLines([])
      setSelectedLineId('')
      try {
        const fetched = await studioApi.playbackEpisodes(sourceId, work.id)
        if (serial !== epLoadSerialRef.current) return null
        setLines(fetched)
        if (fetched.length > 0) {
          const picked =
            preferredLineId && fetched.some((l) => l.id === preferredLineId)
              ? preferredLineId
              : fetched[0].id
          setSelectedLineId(picked)
        } else {
          setErrorMsg('该来源此作品暂无可用线路/集数，请直接点击上方其他来源切换')
        }
        return fetched
      } catch (err) {
        if (serial !== epLoadSerialRef.current) return null
        setErrorMsg(
          `读取线路失败（${err instanceof Error ? err.message : String(err)}），可直接点击其他来源切换`
        )
        return null
      } finally {
        if (serial === epLoadSerialRef.current) {
          setLoadingLines(false)
        }
      }
    },
    []
  )

  // 全源并发检索（与 Android PlaybackViewModel 对齐）：同时搜索所有来源，逐个回填结果
  const runMultiSourceSearch = useCallback(
    async (keyword: string, onlySourceId?: string) => {
      const clean = keyword.trim()
      if (!clean || sources.length === 0) return

      const serial = ++searchSerialRef.current
      setErrorMsg(null)
      const targets = onlySourceId
        ? sources.filter((s) => s.id === onlySourceId)
        : sources

      setSearchGroups((prev) =>
        sources.map((src) => {
          if (targets.some((t) => t.id === src.id)) {
            return { source: src, loading: true, results: [], error: null }
          }
          const existing = prev.find((g) => g.source.id === src.id)
          return existing ?? { source: src, loading: false, results: [], error: null }
        })
      )
      if (!onlySourceId) {
        setActiveSourceFilter('ALL')
      }

      let autoPicked = Boolean(onlySourceId)

      await Promise.allSettled(
        targets.map(async (src) => {
          try {
            const hits = await studioApi.playbackSearch(src.id, clean)
            if (serial !== searchSerialRef.current) return
            setSearchGroups((prev) =>
              prev.map((g) =>
                g.source.id === src.id
                  ? { source: src, loading: false, results: hits, error: null }
                  : g
              )
            )
            // 首个返回有效匹配的来源自动展开其线路和集数
            if (!autoPicked && hits.length > 0) {
              autoPicked = true
              const exact = hits.find(
                (w) => w.title.trim().toLowerCase() === clean.toLowerCase()
              )
              void loadEpisodesForWork(src.id, exact ?? hits[0])
            }
          } catch (err) {
            if (serial !== searchSerialRef.current) return
            const msg = err instanceof Error ? err.message : String(err)
            setSearchGroups((prev) =>
              prev.map((g) =>
                g.source.id === src.id
                  ? {
                      source: src,
                      loading: false,
                      results: [],
                      error: msg || '来源暂时无法读取'
                    }
                  : g
              )
            )
          }
        })
      )
    },
    [sources, loadEpisodesForWork]
  )

  // 从画册/检视器/新番放送点击「看番/试看」进入时自动全源并发检索
  useEffect(() => {
    const autoTitle = targetAnime?.title ?? initialSearchTitle?.trim() ?? ''
    if (!autoTitle) return
    setQuery(autoTitle)
    if (sources.length === 0) return
    const key = `${targetAnime?.id ?? 'raw'}:${autoTitle}:${sources.length}`
    if (lastAutoSearchKeyRef.current === key) return
    lastAutoSearchKeyRef.current = key
    void runMultiSourceSearch(autoTitle)
  }, [targetAnime, initialSearchTitle, sources.length, runMultiSourceSearch])

  const currentLine = useMemo(
    () => lines.find((l) => l.id === selectedLineId) ?? lines[0] ?? null,
    [lines, selectedLineId]
  )

  const selectedSourceName = useMemo(
    () => sources.find((s) => s.id === selectedSourceId)?.name ?? selectedSourceId,
    [sources, selectedSourceId]
  )

  const cancelResolve = useCallback(() => {
    resolveSerialRef.current += 1
    setResolving(false)
    setResolvingHint('')
    setStatusMsg('已取消解析，请直接点击其他集数、线路或来源')
  }, [])

  const handleExitPlayback = useCallback(() => {
    const video = videoRef.current
    if (resumeKey && selectedWork && activeEpisode && video && Number.isFinite(video.currentTime) && video.currentTime > 3) {
      saveResumeEntry(resumeKey, {
        sourceId: selectedSourceId,
        sourceName: selectedSourceName,
        work: selectedWork,
        lineId: selectedLineId,
        episode: activeEpisode,
        currentTime: video.currentTime,
        updatedAt: Date.now()
      })
    }
    resolveSerialRef.current += 1
    if (hlsRef.current) {
      hlsRef.current.destroy()
      hlsRef.current = null
    }
    if (video) {
      try {
        video.pause()
        video.removeAttribute('src')
        video.load()
      } catch {
        // 忽略释放流时的非致命异常
      }
    }
    pendingSeekSecRef.current = null
    setResolving(false)
    setResolvingHint('')
    setStreamTicket(null)
    setActiveEpisode(null)
    setUpscaleCanvasActive(false)
    setErrorMsg(null)
    setStatusMsg('已退出当前播放')
  }, [resumeKey, selectedWork, activeEpisode, selectedSourceId, selectedSourceName, selectedLineId])

  // 非阻塞解析：点击任意集数或线路立刻作废上一次解析请求；可选自动顺延尝试同作品其他线路
  const playEpisodeInternal = useCallback(
    async (
      sourceId: string,
      work: PlaybackWork,
      allLines: PlaybackLine[],
      lineId: string,
      ep: PlaybackEpisode,
      allowFallback: boolean,
      seekSeconds?: number
    ) => {
      const serial = ++resolveSerialRef.current
      const lineObj = allLines.find((l) => l.id === lineId) ?? allLines[0]
      const srcName = sources.find((s) => s.id === sourceId)?.name ?? sourceId

      setSelectedSourceId(sourceId)
      setSelectedWork(work)
      if (lineObj) setSelectedLineId(lineObj.id)
      setActiveEpisode(ep)
      setResolving(true)
      setErrorMsg(null)

      // 构造待尝试队列：当前线路优先，若开启自动切线则顺延同作品其他线路中同集号/同位置的集
      const candidates: Array<{ line: PlaybackLine; episode: PlaybackEpisode }> = []
      if (lineObj) {
        candidates.push({ line: lineObj, episode: ep })
      }
      if (allowFallback && allLines.length > 1 && lineObj) {
        const epIdx = lineObj.episodes.findIndex((e) => e.id === ep.id)
        for (const other of allLines) {
          if (other.id === lineObj.id || other.episodes.length === 0) continue
          const matched =
            (ep.number != null
              ? other.episodes.find((e) => e.number === ep.number)
              : undefined) ??
            other.episodes.find((e) => e.label === ep.label) ??
            (epIdx >= 0 && epIdx < other.episodes.length ? other.episodes[epIdx] : other.episodes[0])
          if (matched) {
            candidates.push({ line: other, episode: matched })
          }
        }
      }

      let lastErr = '来源暂时无法读取'
      for (let i = 0; i < candidates.length; i += 1) {
        if (serial !== resolveSerialRef.current) return
        const cand = candidates[i]
        setResolvingHint(
          i === 0
            ? `正在从 [${srcName} · ${cand.line.name}] 解析「${cand.episode.label}」...`
            : `主线路不可用，自动尝试 [${srcName} · ${cand.line.name}]「${cand.episode.label}」...`
        )
        try {
          const ticket = await studioApi.playbackResolve(sourceId, work.id, cand.episode.id)
          if (serial !== resolveSerialRef.current) return
          if (seekSeconds && seekSeconds > 3) {
            pendingSeekSecRef.current = seekSeconds
          } else {
            pendingSeekSecRef.current = null
          }
          setSelectedLineId(cand.line.id)
          setActiveEpisode(cand.episode)
          setStreamTicket(ticket)
          setResolving(false)
          setResolvingHint('')
          setErrorMsg(null)
          setStatusMsg(
            `正在播放：${work.title} · ${cand.episode.label}（${srcName} / ${cand.line.name}）`
          )
          if (resumeKey) {
            saveResumeEntry(resumeKey, {
              sourceId,
              sourceName: srcName,
              work,
              lineId: cand.line.id,
              episode: cand.episode,
              currentTime: seekSeconds ?? 0,
              updatedAt: Date.now()
            })
          }
          return
        } catch (err) {
          lastErr = err instanceof Error ? err.message : String(err)
        }
      }

      if (serial !== resolveSerialRef.current) return
      setResolving(false)
      setResolvingHint('')
      setErrorMsg(
        `解析失败（${lastErr}）。可点击下方「切换下一来源」或直接点右侧其他来源/线路，无需等待。`
      )
    },
    [sources, resumeKey]
  )

  const handlePlayEpisode = useCallback(
    (ep: PlaybackEpisode) => {
      if (!selectedSourceId || !selectedWork) return
      void playEpisodeInternal(
        selectedSourceId,
        selectedWork,
        lines,
        selectedLineId || lines[0]?.id || '',
        ep,
        autoFallbackLine
      )
    },
    [selectedSourceId, selectedWork, lines, selectedLineId, autoFallbackLine, playEpisodeInternal]
  )

  // 一键尝试同作品下一条线路，若线路已用完则跳到下一个有结果的来源
  const handleTryNextLineOrSource = useCallback(async () => {
    if (lines.length > 1 && currentLine) {
      const idx = lines.findIndex((l) => l.id === currentLine.id)
      const nextLine = lines[(idx + 1) % lines.length]
      if (nextLine && nextLine.id !== currentLine.id && nextLine.episodes.length > 0) {
        const epIdx = activeEpisode
          ? currentLine.episodes.findIndex((e) => e.id === activeEpisode.id)
          : 0
        const targetEp =
          (activeEpisode?.number != null
            ? nextLine.episodes.find((e) => e.number === activeEpisode.number)
            : undefined) ??
          nextLine.episodes[Math.max(0, Math.min(epIdx, nextLine.episodes.length - 1))] ??
          nextLine.episodes[0]
        if (targetEp && selectedWork) {
          void playEpisodeInternal(
            selectedSourceId,
            selectedWork,
            lines,
            nextLine.id,
            targetEp,
            false
          )
          return
        }
      }
    }

    // 尝试切换到下一个有匹配结果的来源
    const workingGroups = searchGroups.filter((g) => g.results.length > 0)
    if (workingGroups.length === 0) return
    const curGroupIdx = workingGroups.findIndex((g) => g.source.id === selectedSourceId)
    const nextGroup = workingGroups[(curGroupIdx + 1) % workingGroups.length]
    if (!nextGroup) return
    const nextWork = nextGroup.results[0]
    const fetched = await loadEpisodesForWork(nextGroup.source.id, nextWork)
    if (fetched && fetched.length > 0 && fetched[0].episodes.length > 0) {
      const targetEp =
        (activeEpisode?.number != null
          ? fetched[0].episodes.find((e) => e.number === activeEpisode.number)
          : undefined) ?? fetched[0].episodes[0]
      void playEpisodeInternal(
        nextGroup.source.id,
        nextWork,
        fetched,
        fetched[0].id,
        targetEp,
        autoFallbackLine
      )
    }
  }, [
    lines,
    currentLine,
    activeEpisode,
    selectedSourceId,
    selectedWork,
    searchGroups,
    autoFallbackLine,
    playEpisodeInternal,
    loadEpisodesForWork
  ])

  const handleResumeSaved = useCallback(async () => {
    if (!resumeBanner) return
    const fetched = await loadEpisodesForWork(
      resumeBanner.sourceId,
      resumeBanner.work,
      resumeBanner.lineId
    )
    if (fetched && fetched.length > 0) {
      const targetLine = fetched.find((l) => l.id === resumeBanner.lineId) ?? fetched[0]
      const targetEp =
        targetLine.episodes.find((e) => e.id === resumeBanner.episode.id) ??
        (resumeBanner.episode.number != null
          ? targetLine.episodes.find((e) => e.number === resumeBanner.episode.number)
          : undefined) ??
        resumeBanner.episode
      void playEpisodeInternal(
        resumeBanner.sourceId,
        resumeBanner.work,
        fetched,
        targetLine.id,
        targetEp,
        autoFallbackLine,
        resumeBanner.currentTime
      )
    }
  }, [resumeBanner, loadEpisodesForWork, playEpisodeInternal, autoFallbackLine])

  // 挂载 hls.js 或原生 <video>
  useEffect(() => {
    const video = videoRef.current
    if (hlsRef.current) {
      hlsRef.current.destroy()
      hlsRef.current = null
    }
    if (!video || !streamTicket) return

    const url = streamTicket.proxyUrl
    const isHls =
      streamTicket.mimeType.toLowerCase().includes('mpegurl') ||
      streamTicket.rawUrl.toLowerCase().includes('.m3u8')

    const seekAndPlay = () => {
      video.playbackRate = playbackRate
      if (pendingSeekSecRef.current && pendingSeekSecRef.current > 3) {
        try {
          video.currentTime = pendingSeekSecRef.current
        } catch {
          // 忽略不支持立即 seek 的流
        }
        pendingSeekSecRef.current = null
      }
      video.play().catch(() => {})
    }

    if (isHls && Hls.isSupported()) {
      const hls = new Hls({
        enableWorker: true,
        lowLatencyMode: false,
        maxBufferLength: 30,
        fragLoadingTimeOut: 20000,
        manifestLoadingTimeOut: 15000
      })
      hlsRef.current = hls
      hls.loadSource(url)
      hls.attachMedia(video)
      hls.on(Hls.Events.MANIFEST_PARSED, seekAndPlay)
      hls.on(Hls.Events.ERROR, (_ev, data) => {
        if (data.fatal) {
          setErrorMsg(
            `HLS 流加载失败（${data.details}），请点击「切换下一线路/来源」重试`
          )
        }
      })
    } else {
      video.src = url
      const onMeta = () => {
        seekAndPlay()
      }
      video.addEventListener('loadedmetadata', onMeta, { once: true })
      video.load()
      return () => {
        video.removeEventListener('loadedmetadata', onMeta)
      }
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy()
        hlsRef.current = null
      }
    }
  }, [streamTicket])

  useEffect(() => {
    if (videoRef.current) {
      videoRef.current.playbackRate = playbackRate
    }
  }, [playbackRate])

  // 播放器快捷键（Space 播放/暂停, ←/→ 快进快退 5s, F 全屏）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.tagName === 'SELECT' ||
          target.isContentEditable)
      ) {
        return
      }
      const video = videoRef.current
      if (!video || !streamTicket) return
      if (e.code === 'Space') {
        e.preventDefault()
        if (video.paused) video.play().catch(() => {})
        else video.pause()
      } else if (e.key === 'ArrowRight') {
        e.preventDefault()
        video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 5)
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault()
        video.currentTime = Math.max(0, video.currentTime - 5)
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault()
        if (document.fullscreenElement) {
          document.exitFullscreen().catch(() => {})
        } else {
          video.requestFullscreen().catch(() => {})
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [streamTicket])

  const activeEpIndex = useMemo(() => {
    if (!currentLine || !activeEpisode) return -1
    return currentLine.episodes.findIndex((e) => e.id === activeEpisode.id)
  }, [currentLine, activeEpisode])

  const handleSyncWatched = async () => {
    if (!targetAnime) return
    const nextEp =
      activeEpisode?.number != null && activeEpisode.number > 0
        ? activeEpisode.number
        : targetAnime.progressPrimary + 1
    await onSyncAnimeEpisodeWithUndo(targetAnime.id, nextEp)
    setStatusMsg(`已同步《${targetAnime.title}》进度至第 ${nextEp} 集`)
  }

  const handleCaptureFrame = async () => {
    const video = videoRef.current
    if (!video || video.videoWidth === 0 || video.videoHeight === 0) {
      setErrorMsg('当前画面尚未就绪，请等待视频开始播放后再截帧')
      return
    }
    if (!targetAnime) {
      setErrorMsg('请先在下方「关联进度藏品」中选中对应动画，再将分镜截帧归档到该作品')
      return
    }
    try {
      const glCanvas = upscaleCanvasRef.current
      const useUpscaled =
        upscaleMode !== 'off' && glCanvas && glCanvas.width > 0 && glCanvas.height > 0
      const canvas = document.createElement('canvas')
      canvas.width = useUpscaled ? glCanvas.width : video.videoWidth
      canvas.height = useUpscaled ? glCanvas.height : video.videoHeight
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      ctx.drawImage(useUpscaled ? glCanvas : video, 0, 0, canvas.width, canvas.height)
      const dataUrl = canvas.toDataURL('image/png')
      const stamp = formatClock(video.currentTime)
      await studioApi.saveCapture({
        workKind: 'anime',
        workId: targetAnime.id,
        workTitle: targetAnime.title,
        note: `${selectedWork?.title ?? targetAnime.title} ${activeEpisode?.label ?? ''} (${stamp})`.trim(),
        episodeHint: activeEpisode?.number ?? null,
        imageBase64: dataUrl,
        appendToMemo: false
      })
      setStatusMsg('已截取当前画面并存入分镜截帧夹，可在漫评室左栏一键引用')
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  // 汇总搜索结果列表（支持按全部或单源筛选）
  const flattenedMatches = useMemo(() => {
    const out: Array<{ source: PlaybackSource; work: PlaybackWork }> = []
    for (const grp of searchGroups) {
      if (activeSourceFilter !== 'ALL' && grp.source.id !== activeSourceFilter) continue
      for (const w of grp.results) {
        out.push({ source: grp.source, work: w })
      }
    }
    return out
  }, [searchGroups, activeSourceFilter])

  const anySearching = useMemo(
    () => searchGroups.some((g) => g.loading),
    [searchGroups]
  )
  const totalHitCount = useMemo(
    () => searchGroups.reduce((acc, g) => acc + g.results.length, 0),
    [searchGroups]
  )

  return (
    <div className="online-player-layout">
      {/* 左侧：视频主舞台 + 控制栏 */}
      <div className="online-player-stage">
        {resumeBanner && !streamTicket && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 10,
              padding: '7px 12px',
              borderRadius: 'var(--radius-sm)',
              background: 'var(--accent-soft)',
              border: '1px solid var(--accent)',
              fontSize: '0.9231rem'
            }}
          >
            <span>
              上次看到：<strong>{resumeBanner.work.title}</strong> · {resumeBanner.episode.label}（
              {resumeBanner.sourceName} · {formatClock(resumeBanner.currentTime)}）
            </span>
            <button className="btn btn-primary" style={{ padding: '4px 10px', fontSize: '0.8846rem' }} onClick={() => void handleResumeSaved()}>
              继续上次观看
            </button>
          </div>
        )}

        <div className="online-video-frame" style={{ position: 'relative' }}>
          {streamTicket ? (
            <UpscaledVideoStage
              videoRef={videoRef}
              canvasRef={upscaleCanvasRef}
              mode={upscaleMode}
              active={Boolean(streamTicket)}
              onStatusChange={setUpscaleStatus}
              onRenderActiveChange={setUpscaleCanvasActive}
              style={{ width: '100%', height: '100%', borderRadius: 8 }}
            >
              <video
                ref={videoRef}
                controls={upscaleMode === 'off' || !upscaleCanvasActive}
                crossOrigin="anonymous"
                style={{ width: '100%', height: '100%', background: '#000', borderRadius: 8 }}
                onTimeUpdate={(e) => {
                  const nowSec = e.currentTarget.currentTime
                  if (
                    resumeKey &&
                    selectedWork &&
                    activeEpisode &&
                    Math.abs(nowSec - lastSavedClockRef.current) >= 5
                  ) {
                    lastSavedClockRef.current = nowSec
                    saveResumeEntry(resumeKey, {
                      sourceId: selectedSourceId,
                      sourceName: selectedSourceName,
                      work: selectedWork,
                      lineId: selectedLineId,
                      episode: activeEpisode,
                      currentTime: nowSec,
                      updatedAt: Date.now()
                    })
                  }
                }}
                onError={() => {
                  setErrorMsg(
                    '当前播放线路连接中断或无法解码，请点击下方「切换下一线路/来源」'
                  )
                }}
                onEnded={() => {
                  if (
                    autoNextEpisode &&
                    currentLine &&
                    activeEpIndex >= 0 &&
                    activeEpIndex < currentLine.episodes.length - 1
                  ) {
                    handlePlayEpisode(currentLine.episodes[activeEpIndex + 1])
                  }
                }}
              />
            </UpscaledVideoStage>
          ) : (
            <div className="online-video-placeholder">
              <div className="heading-font" style={{ fontSize: '1.1923rem', fontWeight: 700, marginBottom: 8, color: '#f5f6fa' }}>
                全源并发在线追番放映厅
              </div>
              <div className="online-video-placeholder-sub">
                右侧输入番剧名将<strong>同时检索全部播放源</strong>；解析期间随时可自由切换任意集数、线路或来源，无需等待。
              </div>
            </div>
          )}

          {/* 非阻塞解析浮层：随时可取消或换线 */}
          {resolving && (
            <div
              style={{
                position: 'absolute',
                left: 12,
                right: 12,
                bottom: 14,
                padding: '9px 12px',
                borderRadius: 8,
                background: 'rgba(18, 19, 24, 0.88)',
                color: '#f5f6fa',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                fontSize: '0.9231rem',
                backdropFilter: 'blur(6px)',
                border: '1px solid rgba(255,255,255,0.14)'
              }}
            >
              <LoadingSpinner size="sm" inline />
              <span style={{ flex: 1 }}>{resolvingHint || '正在解析视频地址...'}</span>
              <button
                className="btn"
                style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                onClick={() => void handleTryNextLineOrSource()}
              >
                换下一线路
              </button>
              <button
                className="btn"
                style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                onClick={cancelResolve}
              >
                取消解析
              </button>
            </div>
          )}
        </div>

        {/* 下方控制条：选集步进 + 倍速 + 换线 + 截帧 + 同步进度 */}
        <div className="online-control-bar card-box">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <button
              className="btn"
              disabled={!currentLine || activeEpIndex <= 0}
              onClick={() => {
                if (currentLine && activeEpIndex > 0) {
                  handlePlayEpisode(currentLine.episodes[activeEpIndex - 1])
                }
              }}
            >
              上一集
            </button>
            <button
              className="btn"
              disabled={
                !currentLine ||
                activeEpIndex < 0 ||
                activeEpIndex >= currentLine.episodes.length - 1
              }
              onClick={() => {
                if (currentLine && activeEpIndex >= 0 && activeEpIndex < currentLine.episodes.length - 1) {
                  handlePlayEpisode(currentLine.episodes[activeEpIndex + 1])
                }
              }}
            >
              下一集
            </button>

            {(streamTicket || resolving) && (
              <button
                className="btn"
                style={{
                  color: 'var(--danger)',
                  borderColor: 'color-mix(in srgb, var(--danger) 48%, var(--border-strong))'
                }}
                onClick={handleExitPlayback}
                title="停止播放并释放当前视频流，已保留续播进度"
              >
                退出播放
              </button>
            )}

            <button
              className="btn"
              disabled={!selectedWork && totalHitCount === 0}
              onClick={() => void handleTryNextLineOrSource()}
              title="当前线路卡顿或报错时，一键切至下一条线路或下一个可用来源"
            >
              切换下一线路/来源
            </button>

            <StudioSelect
              className="studio-select-inline tabular"
              value={String(playbackRate)}
              onChange={(val) => setPlaybackRate(Number(val))}
              title="倍速播放"
              ariaLabel="在线播放倍速"
              minMenuWidth={112}
              maxMenuWidth={150}
              options={[0.75, 1, 1.25, 1.5, 2].map((r) => ({
                value: String(r),
                label: `${r}x 倍速`
              }))}
            />

            <StudioSelect
              className="studio-select-inline"
              value={upscaleMode}
              onChange={(val) => {
                const next = val as VideoUpscaleMode
                setUpscaleMode(next)
                const updated = { ...loadLocalDesktopSettings(), upscaleMode: next }
                persistLocalDesktopSettings(updated)
                void studioApi
                  .saveDesktopSettings(updated as unknown as Record<string, unknown>)
                  .catch(() => {})
              }}
              triggerLabel={`画质：${VIDEO_UPSCALE_OPTIONS.find((o) => o.value === upscaleMode)?.shortLabel ?? '原画'}`}
              title={
                upscaleStatus
                  ? `画质增强：${upscaleStatus}`
                  : '实时 GPU 视频超分与锐化（WebGL2 + Anime4K）'
              }
              ariaLabel="在线播放画质增强"
              minMenuWidth={270}
              maxMenuWidth={350}
              options={VIDEO_UPSCALE_OPTIONS.map((opt) => ({
                value: opt.value,
                label: opt.label,
                sublabel: opt.description
              }))}
            />

            <button className="btn" disabled={!streamTicket} onClick={handleCaptureFrame}>
              截帧存至漫评
            </button>

            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.8846rem', color: 'var(--text-secondary)', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={autoFallbackLine}
                onChange={(e) => setAutoFallbackLine(e.target.checked)}
              />
              失败自动换线
            </label>

            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: '0.8846rem', color: 'var(--text-secondary)', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={autoNextEpisode}
                onChange={(e) => setAutoNextEpisode(e.target.checked)}
              />
              自动连播
            </label>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>关联藏品：</span>
            <StudioSelect
              className="studio-select-inline"
              value={targetAnime?.id != null ? String(targetAnime.id) : ''}
              onChange={(val) => {
                const nextId = val ? Number(val) : null
                const picked = nextId != null ? animeItems.find((a) => a.id === nextId) ?? null : null
                onSelectTargetAnime(picked)
              }}
              ariaLabel="关联藏品动画"
              minMenuWidth={220}
              maxMenuWidth={320}
              options={[
                { value: '', label: '不关联（纯在线观看）' },
                ...animeItems.map((a) => ({
                  value: String(a.id),
                  label: a.title,
                  sublabel: `进度 ${a.progressPrimary}/${a.progressTotal || '?'}`
                }))
              ]}
            />

            {targetAnime && (
              <button className="btn btn-primary" onClick={handleSyncWatched}>
                {activeEpisode?.number
                  ? `标记看到第 ${activeEpisode.number} 集`
                  : `看完本集 · 进度 +1`}
              </button>
            )}

            {statusMsg && (
              <span style={{ fontSize: '0.9231rem', color: 'var(--positive)', marginLeft: 'auto' }}>
                {statusMsg}
              </span>
            )}
          </div>

          {errorMsg && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: '0.9231rem', color: 'var(--danger)' }}>
              <span style={{ flex: 1 }}>{errorMsg}</span>
              {activeEpisode && (
                <button
                  className="btn"
                  style={{ padding: '2px 8px', fontSize: '0.8846rem' }}
                  onClick={() => handlePlayEpisode(activeEpisode)}
                >
                  重试本集
                </button>
              )}
              <button
                className="btn"
                style={{ padding: '2px 8px', fontSize: '0.8846rem' }}
                onClick={() => void handleTryNextLineOrSource()}
              >
                切下一线路/来源
              </button>
            </div>
          )}
        </div>
      </div>

      {/* 右侧：全源并发检索 + 多来源结果切换 + 线路分集抽屉 */}
      <aside className="online-player-panel card-box">
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <input
            className="inline-input"
            style={{ flex: 1, minWidth: 0 }}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                void runMultiSourceSearch(query)
              }
            }}
            placeholder="输入番剧名，并发搜索全部来源..."
          />
          <button
            className="btn btn-primary"
            disabled={loadingSources || sources.length === 0 || !query.trim()}
            onClick={() => void runMultiSourceSearch(query)}
          >
            {anySearching ? '搜索中' : '全源搜索'}
          </button>
        </div>

        {(sourcesError || (!loadingSources && sources.length === 0)) && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, padding: '8px 10px', borderRadius: 6, background: 'var(--bg-muted)', fontSize: '0.9231rem', color: 'var(--danger)' }}>
            <span>{sourcesError || '播放源列表加载失败或为空'}</span>
            <button className="btn" style={{ padding: '3px 8px', fontSize: '0.8846rem' }} onClick={() => void loadSources()}>
              重试加载
            </button>
          </div>
        )}

        {/* 多源状态胶囊栏：一目了然哪些源搜到了、哪些源失败可单源重试 */}
        {searchGroups.length > 0 && (
          <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            <button
              className={`btn ${activeSourceFilter === 'ALL' ? 'btn-primary' : ''}`}
              style={{ padding: '3px 8px', fontSize: '0.8462rem' }}
              onClick={() => setActiveSourceFilter('ALL')}
            >
              全部 ({totalHitCount})
            </button>
            {searchGroups.map((g) => {
              const isAct = activeSourceFilter === g.source.id
              const failed = Boolean(g.error)
              return (
                <button
                  key={g.source.id}
                  className={`btn ${isAct ? 'btn-primary' : ''}`}
                  style={{
                    padding: '3px 8px',
                    fontSize: '0.8462rem',
                    borderColor: failed ? 'var(--danger)' : undefined,
                    color: !isAct && failed ? 'var(--danger)' : undefined
                  }}
                  title={
                    failed
                      ? `${g.source.name} 读取失败：${g.error}（点击单源重试）`
                      : `仅看 ${g.source.name} 的搜索结果`
                  }
                  onClick={() => {
                    if (failed) {
                      void runMultiSourceSearch(query, g.source.id)
                    } else {
                      setActiveSourceFilter(g.source.id)
                    }
                  }}
                >
                  {g.source.name}{' '}
                  {g.loading ? <LoadingSpinner size="sm" inline /> : failed ? '重试' : `(${g.results.length})`}
                </button>
              )
            })}
          </div>
        )}

        {/* 聚合匹配结果（带来源徽标，点一下立即切换来源与线路） */}
        {flattenedMatches.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', fontWeight: 600 }}>
              多源搜索匹配（{flattenedMatches.length}）
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 150, overflowY: 'auto' }}>
              {flattenedMatches.map(({ source, work }) => {
                const isSel = selectedSourceId === source.id && selectedWork?.id === work.id
                return (
                  <button
                    key={`${source.id}:${work.id}`}
                    className={`online-work-row ${isSel ? 'active' : ''}`}
                    onClick={() => void loadEpisodesForWork(source.id, work)}
                  >
                    {work.coverUrl && (
                      <img
                        src={work.coverUrl}
                        alt={work.title}
                        className="online-work-thumb"
                        referrerPolicy="no-referrer"
                        onError={(e) => {
                          e.currentTarget.style.display = 'none'
                        }}
                      />
                    )}
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ fontSize: '0.9231rem', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {work.title}
                      </div>
                      <div style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                        来源：{source.name}
                      </div>
                    </div>
                  </button>
                )
              })}
            </div>
          </div>
        )}

        {/* 线路切换 + 集数网格（解析期间绝不禁用，随时可秒切） */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, flex: 1, minHeight: 0 }}>
          {loadingLines ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '8px 0' }}>
              <LoadingSpinner size="sm" inline label={`正在拉取 [${selectedSourceName}] 线路与分集...`} />
              <SkeletonRows count={3} />
            </div>
          ) : lines.length > 0 ? (
            <>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
                <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', fontWeight: 600 }}>
                  {selectedSourceName} · 可用线路（{lines.length}）
                </span>
              </div>
              <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
                {lines.map((line) => (
                  <button
                    key={line.id}
                    className={`btn ${currentLine?.id === line.id ? 'btn-primary' : ''}`}
                    style={{ padding: '4px 9px', fontSize: '0.8846rem' }}
                    onClick={() => {
                      setSelectedLineId(line.id)
                      // 如果当前正在播/解析某集，切线路后顺手自动按同集号切换
                      if (activeEpisode && selectedWork && line.id !== currentLine?.id && line.episodes.length > 0) {
                        const matched =
                          (activeEpisode.number != null
                            ? line.episodes.find((e) => e.number === activeEpisode.number)
                            : undefined) ??
                          line.episodes.find((e) => e.label === activeEpisode.label) ??
                          line.episodes[0]
                        if (matched) {
                          void playEpisodeInternal(
                            selectedSourceId,
                            selectedWork,
                            lines,
                            line.id,
                            matched,
                            false
                          )
                        }
                      }
                    }}
                  >
                    {line.name} ({line.episodes.length})
                  </button>
                ))}
              </div>
              <div className="online-episode-grid">
                {(currentLine?.episodes ?? []).map((ep) => {
                  const isPlaying =
                    activeEpisode?.id === ep.id &&
                    selectedLineId === (currentLine?.id ?? '')
                  return (
                    <button
                      key={`${currentLine?.id}:${ep.id}`}
                      className={`online-ep-btn ${isPlaying ? 'active' : ''}`}
                      onClick={() => handlePlayEpisode(ep)}
                      title={`点击立即解析播放 ${ep.label}（解析中也可随时切换）`}
                    >
                      {ep.label || `第 ${ep.number ?? '?'} 集`}
                    </button>
                  )
                })}
              </div>
            </>
          ) : (
            <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', padding: '16px 4px', lineHeight: 1.6 }}>
              {anySearching
                ? '正在并发检索全部播放源，结果将逐个显示...'
                : '输入番剧名点击「全源搜索」后，所有可用来源、线路与分集将在此展示。'}
            </div>
          )}
        </div>
      </aside>
    </div>
  )
}
