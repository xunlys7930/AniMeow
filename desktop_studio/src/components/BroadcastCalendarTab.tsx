import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { studioApi } from '../api'
import { CoverImage } from './CoverImage'
import { LoadingSpinner, SkeletonCards } from './LoadingIndicator'
import { StudioSelect } from './StudioSelect'
import { matchLocalWorkWithSuspicion } from '../utils/titleMatch'
import type {
  BangumiCalendarDay,
  BangumiCalendarItem,
  CuratorItem,
  DiscoveryItem
} from '../types'

interface BroadcastCalendarTabProps {
  items: CuratorItem[]
  mediaBaseUrl?: string
  onDataChanged: () => Promise<void>
  onOpenOnlinePlay: (work: CuratorItem | null, titleFallback?: string) => void
  onSelectWork: (kind: string, id: number) => void
}

type DiscoveryKind = 'anime' | 'book' | 'game'
type DiscoveryFeedTab = 'calendar' | 'recommended' | 'ranking' | 'seasonal'

interface DiscoveryMetaInfo {
  sourceLabel: string
  updatedAt: string | null
  stale: boolean
}

interface CachedDiscoveryFeed {
  items: DiscoveryItem[]
  page: number
  hasMore: boolean
  total: number
  meta: DiscoveryMetaInfo | null
}

// 模块级会话缓存：切换发现子标签或切出再切回「发现」时不重复拉取；点击当前已激活标签或「刷新」时主动更新
let cachedCalendarData: { days: BangumiCalendarDay[]; meta: DiscoveryMetaInfo | null } | null = null
const discoveryFeedCache = new Map<string, CachedDiscoveryFeed>()
let lastDiscoveryKind: DiscoveryKind = 'anime'
let lastDiscoveryFeed: DiscoveryFeedTab = 'recommended'

function getTodayWeekdayId(): number {
  const jsDay = new Date().getDay()
  return jsDay === 0 ? 7 : jsDay
}

function getCurrentSeasonDefaults(): { year: number; month: number } {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth() + 1
  const seasonMonth = m >= 10 ? 10 : m >= 7 ? 7 : m >= 4 ? 4 : 1
  return { year: y, month: seasonMonth }
}

function formatUpdatedAt(iso: string | null): string {
  if (!iso) return ''
  const dt = new Date(iso)
  if (Number.isNaN(dt.getTime())) return ''
  const mm = String(dt.getMonth() + 1).padStart(2, '0')
  const dd = String(dt.getDate()).padStart(2, '0')
  const hh = String(dt.getHours()).padStart(2, '0')
  const mi = String(dt.getMinutes()).padStart(2, '0')
  return `${mm}-${dd} ${hh}:${mi}`
}

const IGNORED_SUSPECTED_STORAGE_KEY = 'animeow.studio.ignoredDiscoverySuspected.v1'

function loadIgnoredSuspectedSet(): Set<string> {
  try {
    const raw = localStorage.getItem(IGNORED_SUSPECTED_STORAGE_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? new Set(arr.filter((v): v is string => typeof v === 'string')) : new Set()
  } catch {
    return new Set()
  }
}

function saveIgnoredSuspectedSet(set: Set<string>): void {
  try {
    localStorage.setItem(IGNORED_SUSPECTED_STORAGE_KEY, JSON.stringify([...set]))
  } catch {
    // ignore storage errors
  }
}

function getCarrierAddStatuses(kind: DiscoveryKind): string[] {
  if (kind === 'book') return ['在读', '想读', '读完', '搁置', '弃坑']
  if (kind === 'game') return ['在玩', '想玩', '已玩', '搁置', '弃坑']
  return ['在看', '想看', '看完', '搁置', '弃坑']
}

const WEEKDAY_LABELS: [number, string][] = [
  [0, '全部七日'],
  [1, '周一'],
  [2, '周二'],
  [3, '周三'],
  [4, '周四'],
  [5, '周五'],
  [6, '周六'],
  [7, '周日']
]

const SEASON_MONTHS: [number, string][] = [
  [1, '1月冬番'],
  [4, '4月春番'],
  [7, '7月夏番'],
  [10, '10月秋番']
]

/**
 * 统一「发现」工作区（合并每日放送与作品探索）：
 * 全量优先经追番喵服务器公共资料网关与新番/游戏快照拉取，国内免梯子并由服务端自动刷新缓存。
 */
export const BroadcastCalendarTab: React.FC<BroadcastCalendarTabProps> = ({
  items,
  mediaBaseUrl = '',
  onDataChanged,
  onOpenOnlinePlay,
  onSelectWork
}) => {
  const todayId = useMemo(() => getTodayWeekdayId(), [])
  const currentSeason = useMemo(() => getCurrentSeasonDefaults(), [])
  const yearChoices = useMemo(() => {
    const start = currentSeason.year + 1
    return Array.from({ length: 18 }, (_, i) => start - i)
  }, [currentSeason.year])

  const [kind, setKind] = useState<DiscoveryKind>(lastDiscoveryKind)
  const [feed, setFeed] = useState<DiscoveryFeedTab>(lastDiscoveryFeed)

  // 每日放送状态（优先从模块级缓存恢复，避免切标签重拉）
  const [days, setDays] = useState<BangumiCalendarDay[]>(() => cachedCalendarData?.days ?? [])
  const [selectedDay, setSelectedDay] = useState<number>(todayId)
  const [onlyFollowing, setOnlyFollowing] = useState(false)
  const [calendarLoading, setCalendarLoading] = useState(false)
  const [calendarMeta, setCalendarMeta] = useState<DiscoveryMetaInfo | null>(
    () => cachedCalendarData?.meta ?? null
  )

  // 发现列表状态（推荐 / 排行榜 / 新番总览 / 搜索）
  const [feedItems, setFeedItems] = useState<DiscoveryItem[]>([])
  const [feedPage, setFeedPage] = useState<number>(1)
  const [feedHasMore, setFeedHasMore] = useState<boolean>(false)
  const [feedTotal, setFeedTotal] = useState<number>(0)
  const [feedLoading, setFeedLoading] = useState<boolean>(false)
  const [loadingMore, setLoadingMore] = useState<boolean>(false)
  const [feedMeta, setFeedMeta] = useState<DiscoveryMetaInfo | null>(null)

  // 筛选与排序
  const [searchInput, setSearchInput] = useState<string>('')
  const [submittedQuery, setSubmittedQuery] = useState<string>('')
  const [rankSort, setRankSort] = useState<'rank' | 'score'>('rank')
  const [gameSort, setGameSort] = useState<'heat' | 'score' | 'date'>('heat')
  const [seasonYear, setSeasonYear] = useState<number>(currentSeason.year)
  const [seasonMonth, setSeasonMonth] = useState<number>(currentSeason.month)
  const [seasonSort, setSeasonSort] = useState<'heat' | 'score' | 'date'>('heat')
  const [bookTag, setBookTag] = useState<string>('')
  const [hideCollected, setHideCollected] = useState<boolean>(false)
  const [hideTargetCount, setHideTargetCount] = useState<number>(18)

  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [addingKey, setAddingKey] = useState<string | null>(null)
  const [addingStatus, setAddingStatus] = useState<string | null>(null)
  const [justAddedMap, setJustAddedMap] = useState<Record<string, { status: string; leaving?: boolean }>>({})
  const [notice, setNotice] = useState<string | null>(null)
  const [ignoredSuspectedKeys, setIgnoredSuspectedKeys] = useState<Set<string>>(() =>
    loadIgnoredSuspectedSet()
  )
  const requestSeqRef = useRef<number>(0)
  const autoFetchStreakRef = useRef<number>(0)
  const noticeTimerRef = useRef<number | null>(null)

  const dismissSuspected = useCallback((key: string) => {
    setIgnoredSuspectedKeys((prev) => {
      const next = new Set(prev)
      next.add(key)
      saveIgnoredSuspectedSet(next)
      return next
    })
  }, [])

  useEffect(() => {
    lastDiscoveryKind = kind
    lastDiscoveryFeed = feed
  }, [kind, feed])

  const buildFeedCacheKey = useCallback((): string => {
    const effectiveFeed = feed === 'calendar' ? 'recommended' : feed
    const sort =
      kind === 'game'
        ? gameSort
        : effectiveFeed === 'ranking'
          ? rankSort
          : effectiveFeed === 'seasonal'
            ? seasonSort
            : 'heat'
    const y = effectiveFeed === 'seasonal' ? seasonYear : ''
    const m = effectiveFeed === 'seasonal' ? seasonMonth : ''
    const bt = kind === 'book' ? bookTag : ''
    return `${kind}|${effectiveFeed}|${sort}|${y}|${m}|${bt}|${submittedQuery}`
  }, [kind, feed, gameSort, rankSort, seasonSort, submittedQuery, seasonYear, seasonMonth, bookTag])

  const matchLocalStatus = useCallback(
    (itemKind: DiscoveryKind, subjectId: number, title: string, originalTitle: string) =>
      matchLocalWorkWithSuspicion(items, itemKind, subjectId, title, originalTitle),
    [items]
  )

  const loadCalendar = useCallback(async (force = false) => {
    if (!force && cachedCalendarData && cachedCalendarData.days.length > 0) {
      setDays(cachedCalendarData.days)
      setCalendarMeta(cachedCalendarData.meta)
      return
    }
    setCalendarLoading(true)
    setErrorMsg(null)
    try {
      const resp = await studioApi.fetchBangumiCalendar()
      const meta: DiscoveryMetaInfo = {
        sourceLabel: resp.sourceLabel,
        updatedAt: resp.updatedAt,
        stale: resp.stale
      }
      cachedCalendarData = { days: resp.days, meta }
      setDays(resp.days)
      setCalendarMeta(meta)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setCalendarLoading(false)
    }
  }, [])

  const loadDiscoveryPage = useCallback(
    async (targetPage: number, append: boolean, force = false) => {
      const cacheKey = buildFeedCacheKey()
      if (!append && !force && targetPage === 1) {
        const cached = discoveryFeedCache.get(cacheKey)
        if (cached) {
          requestSeqRef.current += 1
          setFeedItems(cached.items)
          setFeedPage(cached.page)
          setFeedHasMore(cached.hasMore)
          setFeedTotal(cached.total)
          setFeedMeta(cached.meta)
          setFeedLoading(false)
          setErrorMsg(null)
          return
        }
      }

      const seq = ++requestSeqRef.current
      if (append) {
        setLoadingMore(true)
      } else {
        setFeedLoading(true)
        setErrorMsg(null)
      }
      try {
        const effectiveFeed = feed === 'calendar' ? 'recommended' : feed
        const sort =
          kind === 'game'
            ? gameSort
            : effectiveFeed === 'ranking'
              ? rankSort
              : effectiveFeed === 'seasonal'
                ? seasonSort
                : 'heat'
        const resp = await studioApi.fetchDiscoveryFeed({
          kind,
          feed: effectiveFeed,
          sort,
          query: submittedQuery || undefined,
          year: effectiveFeed === 'seasonal' ? seasonYear : undefined,
          month: effectiveFeed === 'seasonal' ? seasonMonth : undefined,
          tag: kind === 'book' && bookTag ? bookTag : undefined,
          page: targetPage
        })
        if (seq !== requestSeqRef.current) return
        const meta: DiscoveryMetaInfo = {
          sourceLabel: resp.sourceLabel,
          updatedAt: resp.updatedAt,
          stale: resp.stale
        }
        setFeedPage(resp.page)
        setFeedHasMore(resp.hasMore)
        setFeedTotal(resp.total)
        setFeedMeta(meta)
        if (append) {
          setFeedItems((prev) => {
            const seen = new Set(prev.map((x) => `${x.externalSource}:${x.externalId}`))
            const extra = resp.items.filter((x) => !seen.has(`${x.externalSource}:${x.externalId}`))
            const merged = [...prev, ...extra]
            discoveryFeedCache.set(cacheKey, {
              items: merged,
              page: resp.page,
              hasMore: resp.hasMore,
              total: resp.total,
              meta
            })
            return merged
          })
        } else {
          setFeedItems(resp.items)
          discoveryFeedCache.set(cacheKey, {
            items: resp.items,
            page: resp.page,
            hasMore: resp.hasMore,
            total: resp.total,
            meta
          })
        }
      } catch (err) {
        if (seq !== requestSeqRef.current) return
        setErrorMsg(err instanceof Error ? err.message : String(err))
      } finally {
        if (seq === requestSeqRef.current) {
          setFeedLoading(false)
          setLoadingMore(false)
        }
      }
    },
    [
      buildFeedCacheKey,
      kind,
      feed,
      gameSort,
      rankSort,
      seasonSort,
      submittedQuery,
      seasonYear,
      seasonMonth,
      bookTag
    ]
  )

  const handleKindChange = (nextKind: DiscoveryKind) => {
    if (nextKind === kind && !submittedQuery) {
      // 再次点击当前已激活的载体标签 → 主动刷新数据
      if (kind === 'anime' && feed === 'calendar') {
        void loadCalendar(true)
      } else {
        void loadDiscoveryPage(1, false, true)
      }
      return
    }
    setKind(nextKind)
    setSearchInput('')
    setSubmittedQuery('')
    setErrorMsg(null)
    if (nextKind === 'book' && (feed === 'calendar' || feed === 'seasonal')) {
      setFeed('recommended')
    } else if (nextKind === 'game') {
      setFeed('recommended')
    }
  }

  useEffect(() => {
    if (kind === 'anime' && feed === 'calendar' && !submittedQuery) {
      void loadCalendar(false)
      return
    }
    void loadDiscoveryPage(1, false, false)
  }, [kind, feed, submittedQuery, loadCalendar, loadDiscoveryPage])

  const flashNotice = useCallback((msg: string) => {
    setNotice(msg)
    if (noticeTimerRef.current != null) {
      window.clearTimeout(noticeTimerRef.current)
    }
    noticeTimerRef.current = window.setTimeout(() => {
      setNotice(null)
      noticeTimerRef.current = null
    }, 3400)
  }, [])

  useEffect(() => {
    return () => {
      if (noticeTimerRef.current != null) {
        window.clearTimeout(noticeTimerRef.current)
      }
    }
  }, [])

  // 切换频道、关键词或隐藏开关时重置补页目标与连翻计数
  useEffect(() => {
    setHideTargetCount(18)
    autoFetchStreakRef.current = 0
  }, [kind, feed, submittedQuery, bookTag, rankSort, gameSort, seasonYear, seasonMonth, seasonSort, hideCollected])

  const markJustAddedFeedback = useCallback(
    (key: string, status: string, willHide: boolean) => {
      setJustAddedMap((prev) => ({ ...prev, [key]: { status, leaving: false } }))
      if (willHide) {
        window.setTimeout(() => {
          setJustAddedMap((prev) =>
            prev[key] ? { ...prev, [key]: { ...prev[key], leaving: true } } : prev
          )
        }, 720)
        window.setTimeout(() => {
          setJustAddedMap((prev) => {
            if (!prev[key]) return prev
            const next = { ...prev }
            delete next[key]
            return next
          })
        }, 1050)
      } else {
        window.setTimeout(() => {
          setJustAddedMap((prev) => {
            if (!prev[key]) return prev
            const next = { ...prev }
            delete next[key]
            return next
          })
        }, 2800)
      }
    },
    []
  )

  const handleQuickAddDiscovery = async (item: DiscoveryItem, status: string) => {
    const key = `${item.externalSource}:${item.externalId}`
    setAddingKey(key)
    setAddingStatus(status)
    setErrorMsg(null)
    try {
      const inferredBookFormat =
        item.kind === 'book'
          ? item.mediaFormat?.trim() ||
            (bookTag === '漫画'
              ? '漫画'
              : bookTag === '小说' || bookTag === '轻小说'
                ? '小说'
                : undefined)
          : item.mediaFormat?.trim() || undefined
      await studioApi.createItem({
        kind: item.kind,
        title: item.title,
        originalTitle: item.originalTitle || undefined,
        status,
        totalEpisodes: item.totalEpisodes > 0 ? item.totalEpisodes : 0,
        coverUrl: item.coverUrl || undefined,
        releaseDate: item.airDate || undefined,
        publicScore: item.score ?? undefined,
        studioOrCircle: item.studioOrDev || undefined,
        synopsis: item.summary || undefined,
        externalSource: item.externalSource,
        externalId: item.externalId,
        mediaFormat: inferredBookFormat
      })
      markJustAddedFeedback(key, status, hideCollected)
      await onDataChanged()
      flashNotice(`已将《${item.title}》加入「${status}」`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setAddingKey(null)
      setAddingStatus(null)
    }
  }

  const handleQuickAddCalendar = async (cal: BangumiCalendarItem, status: string) => {
    const key = `cal:${cal.id}`
    setAddingKey(key)
    setAddingStatus(status)
    setErrorMsg(null)
    try {
      await studioApi.createItem({
        kind: 'anime',
        title: cal.title,
        originalTitle: cal.originalTitle || undefined,
        status,
        totalEpisodes: 0,
        coverUrl: cal.coverUrl || undefined,
        releaseDate: cal.airDate || undefined,
        publicScore: cal.score ?? undefined,
        externalSource: 'bangumi',
        externalId: String(cal.id)
      })
      markJustAddedFeedback(key, status, false)
      await onDataChanged()
      flashNotice(`已将《${cal.title}》加入「${status}」`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setAddingKey(null)
      setAddingStatus(null)
    }
  }

  const isCalendarView = kind === 'anime' && feed === 'calendar' && !submittedQuery

  const visibleDays = useMemo(() => {
    const kw = searchInput.trim().toLowerCase()
    const slice = selectedDay === 0 ? days : days.filter((d) => d.weekdayId === selectedDay)
    return slice.map((d) => ({
      ...d,
      items: d.items.filter((it) => {
        if (kw && !it.title.toLowerCase().includes(kw) && !it.originalTitle.toLowerCase().includes(kw)) {
          return false
        }
        if (onlyFollowing && !matchLocalStatus('anime', it.id, it.title, it.originalTitle).exact) {
          return false
        }
        return true
      })
    }))
  }, [days, selectedDay, searchInput, onlyFollowing, matchLocalStatus])

  const totalCalendarVisible = useMemo(
    () => visibleDays.reduce((acc, d) => acc + d.items.length, 0),
    [visibleDays]
  )

  const visibleFeedItems = useMemo(() => {
    if (!hideCollected) return feedItems
    return feedItems.filter((it) => {
      const key = `${it.externalSource}:${it.externalId}`
      // 刚入库的卡片先短暂保留展示成功反馈，再自动淡出移走
      if (justAddedMap[key]) return true
      const numId = Number(it.externalId) || 0
      return !matchLocalStatus(it.kind, numId, it.title, it.originalTitle).exact
    })
  }, [feedItems, hideCollected, justAddedMap, matchLocalStatus])

  const skippedCollectedCount = Math.max(0, feedItems.length - visibleFeedItems.length)

  // 开启「隐藏已收录」时，若当前未收录作品不足一批（18 部）且仍有更多页，自动向后补页（单轮最多连翻 5 页）
  useEffect(() => {
    if (!hideCollected || isCalendarView) return
    if (feedLoading || loadingMore || !feedHasMore) return
    if (feedItems.length === 0) return
    if (visibleFeedItems.length >= hideTargetCount) {
      autoFetchStreakRef.current = 0
      return
    }
    if (autoFetchStreakRef.current >= 5) return
    autoFetchStreakRef.current += 1
    void loadDiscoveryPage(feedPage + 1, true)
  }, [
    hideCollected,
    isCalendarView,
    feedLoading,
    loadingMore,
    feedHasMore,
    feedItems.length,
    visibleFeedItems.length,
    hideTargetCount,
    feedPage,
    loadDiscoveryPage
  ])

  const activeMeta = isCalendarView ? calendarMeta : feedMeta
  const isRefreshing = isCalendarView ? calendarLoading : feedLoading
  const addStatuses = getCarrierAddStatuses(kind)
  const animeStatuses = getCarrierAddStatuses('anime')

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const q = searchInput.trim()
    setSubmittedQuery(q)
    if (q && feed === 'calendar') {
      setFeed('recommended')
    }
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* 第一排：载体切换 + 频道切换 + 服务端实时搜索 */}
      <div
        style={{
          padding: '10px 16px',
          borderBottom: '1px solid var(--border-subtle)',
          background: 'var(--bg-panel)',
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          flexWrap: 'wrap'
        }}
      >
        <div className="view-seg" role="group" aria-label="发现分类">
          {(
            [
              ['anime', '动画'],
              ['book', '书籍'],
              ['game', '游戏']
            ] as const
          ).map(([k, label]) => (
            <button
              key={k}
              type="button"
              className={`view-seg-btn ${kind === k ? 'active' : ''}`}
              title={kind === k ? '再次点击刷新当前列表' : `切换到${label}`}
              onClick={() => handleKindChange(k)}
            >
              {label}
            </button>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
          {kind === 'anime' && (
            <>
              {(
                [
                  ['recommended', '热门推荐'],
                  ['ranking', '排行榜'],
                  ['seasonal', '新番总览'],
                  ['calendar', '每日放送']
                ] as const
              ).map(([fid, flabel]) => (
                <button
                  key={fid}
                  type="button"
                  className={`btn ${feed === fid && !submittedQuery ? 'btn-primary' : ''}`}
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  title={feed === fid && !submittedQuery ? '再次点击可刷新' : `切换到${flabel}`}
                  onClick={() => {
                    if (feed === fid && !submittedQuery) {
                      if (fid === 'calendar') void loadCalendar(true)
                      else void loadDiscoveryPage(1, false, true)
                      return
                    }
                    setSubmittedQuery('')
                    setSearchInput('')
                    setFeed(fid)
                  }}
                >
                  {flabel}
                </button>
              ))}
            </>
          )}

          {kind === 'book' && (
            <>
              {(
                [
                  ['recommended', '热门推荐'],
                  ['ranking', '排行榜']
                ] as const
              ).map(([fid, flabel]) => (
                <button
                  key={fid}
                  type="button"
                  className={`btn ${feed === fid && !submittedQuery ? 'btn-primary' : ''}`}
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  title={feed === fid && !submittedQuery ? '再次点击可刷新' : `切换到${flabel}`}
                  onClick={() => {
                    if (feed === fid && !submittedQuery) {
                      void loadDiscoveryPage(1, false, true)
                      return
                    }
                    setSubmittedQuery('')
                    setSearchInput('')
                    setFeed(fid)
                  }}
                >
                  {flabel}
                </button>
              ))}
              <span style={{ color: 'var(--border-strong)', margin: '0 2px' }}>|</span>
              {(
                [
                  ['', '全部书籍'],
                  ['小说', '小说'],
                  ['轻小说', '轻小说'],
                  ['漫画', '漫画']
                ] as const
              ).map(([tval, tlabel]) => (
                <button
                  key={tlabel}
                  type="button"
                  className={`btn ${bookTag === tval ? 'btn-primary' : ''}`}
                  style={{ padding: '4px 9px', fontSize: '0.8846rem' }}
                  onClick={() => setBookTag(tval)}
                >
                  {tlabel}
                </button>
              ))}
            </>
          )}

          {kind === 'game' && (
            <>
              {(
                [
                  ['heat', '热门游戏'],
                  ['score', '高分榜'],
                  ['date', '最新发售']
                ] as const
              ).map(([gs, glabel]) => (
                <button
                  key={gs}
                  type="button"
                  className={`btn ${gameSort === gs && !submittedQuery ? 'btn-primary' : ''}`}
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  title={gameSort === gs && !submittedQuery ? '再次点击可刷新' : `切换到${glabel}`}
                  onClick={() => {
                    if (gameSort === gs && !submittedQuery) {
                      void loadDiscoveryPage(1, false, true)
                      return
                    }
                    setSubmittedQuery('')
                    setSearchInput('')
                    setGameSort(gs)
                  }}
                >
                  {glabel}
                </button>
              ))}
            </>
          )}
        </div>

        <form
          onSubmit={handleSearchSubmit}
          style={{ display: 'flex', alignItems: 'center', gap: 5, marginLeft: 'auto' }}
        >
          <input
            className="inline-input"
            style={{ width: 210 }}
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder={
              kind === 'game'
                ? '搜游戏 / Galgame（含 VNDB）...'
                : kind === 'book'
                  ? '搜轻小说 / 漫画...'
                  : '搜在线动画资料...'
            }
          />
          <button type="submit" className="btn btn-primary" style={{ padding: '4px 10px', fontSize: '0.9231rem' }}>
            搜索
          </button>
          {(submittedQuery || searchInput) && (
            <button
              type="button"
              className="btn"
              style={{ padding: '4px 8px', fontSize: '0.9231rem' }}
              onClick={() => {
                setSearchInput('')
                setSubmittedQuery('')
              }}
            >
              清空
            </button>
          )}
        </form>
      </div>

      {/* 第二排：频道专属筛选条 + 服务器数据源与缓存新鲜度提示 */}
      <div
        style={{
          padding: '8px 16px',
          borderBottom: '1px solid var(--border-subtle)',
          background: 'var(--bg-canvas)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap'
        }}
      >
        {isCalendarView ? (
          <>
            <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
              {WEEKDAY_LABELS.map(([id, label]) => (
                <button
                  key={id}
                  type="button"
                  className={`btn ${selectedDay === id ? 'btn-primary' : ''}`}
                  style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                  onClick={() => setSelectedDay(id)}
                >
                  {label}
                  {id === todayId ? ' · 今' : ''}
                </button>
              ))}
            </div>
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: '0.9231rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={onlyFollowing}
                onChange={(e) => setOnlyFollowing(e.target.checked)}
              />
              仅看库中已收录
            </label>
          </>
        ) : (
          <>
            {kind === 'anime' && feed === 'seasonal' && !submittedQuery && (
              <>
                <StudioSelect
                  className="studio-select-inline"
                  value={String(seasonYear)}
                  onChange={(val) => setSeasonYear(Number(val))}
                  ariaLabel="新番年份"
                  minMenuWidth={108}
                  maxMenuWidth={140}
                  options={yearChoices.map((y) => ({
                    value: String(y),
                    label: `${y} 年`
                  }))}
                />
                <div style={{ display: 'flex', gap: 4 }}>
                  {SEASON_MONTHS.map(([m, label]) => (
                    <button
                      key={m}
                      type="button"
                      className={`btn ${seasonMonth === m ? 'btn-primary' : ''}`}
                      style={{ padding: '3px 9px', fontSize: '0.885rem' }}
                      onClick={() => setSeasonMonth(m)}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                <StudioSelect
                  className="studio-select-inline"
                  value={seasonSort}
                  onChange={(val) => setSeasonSort(val as 'heat' | 'score' | 'date')}
                  ariaLabel="季度新番排序"
                  minMenuWidth={124}
                  maxMenuWidth={160}
                  options={[
                    { value: 'heat', label: '按热度' },
                    { value: 'score', label: '按评分' },
                    { value: 'date', label: '按放送日期' }
                  ]}
                />
              </>
            )}

            {kind !== 'game' && feed === 'ranking' && !submittedQuery && (
              <div style={{ display: 'flex', gap: 4 }}>
                <button
                  type="button"
                  className={`btn ${rankSort === 'rank' ? 'btn-primary' : ''}`}
                  style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                  title={rankSort === 'rank' ? '再次点击可刷新' : '切换到综合排名榜'}
                  onClick={() => {
                    if (rankSort === 'rank') void loadDiscoveryPage(1, false, true)
                    else setRankSort('rank')
                  }}
                >
                  综合排名榜
                </button>
                <button
                  type="button"
                  className={`btn ${rankSort === 'score' ? 'btn-primary' : ''}`}
                  style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                  title={rankSort === 'score' ? '再次点击可刷新' : '切换到高分口碑榜'}
                  onClick={() => {
                    if (rankSort === 'score') void loadDiscoveryPage(1, false, true)
                    else setRankSort('score')
                  }}
                >
                  高分口碑榜（≥100人评）
                </button>
              </div>
            )}

            {submittedQuery && (
              <span className="badge badge-accent">搜索「{submittedQuery}」· 共 {feedTotal} 条</span>
            )}

            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: '0.9231rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={hideCollected}
                onChange={(e) => setHideCollected(e.target.checked)}
              />
              隐藏已收录
            </label>
          </>
        )}

        <button
          type="button"
          className="btn"
          style={{ padding: '3px 10px', fontSize: '0.8846rem' }}
          disabled={isRefreshing}
          onClick={() => {
            if (isCalendarView) void loadCalendar(true)
            else void loadDiscoveryPage(1, false, true)
          }}
        >
          {isRefreshing ? <LoadingSpinner size="sm" inline label="刷新中..." /> : '刷新'}
        </button>

        <div
          style={{
            marginLeft: 'auto',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            fontSize: '0.8846rem',
            color: notice ? 'var(--positive)' : 'var(--text-muted)'
          }}
        >
          {notice ? (
            <span style={{ fontWeight: 600 }}>{notice}</span>
          ) : (
            <>
              {activeMeta?.updatedAt && (
                <span className="badge">
                  更新于 {formatUpdatedAt(activeMeta.updatedAt)}
                </span>
              )}
              <span>
                {isCalendarView
                  ? `展示 ${totalCalendarVisible} 部`
                  : hideCollected
                    ? `展示 ${visibleFeedItems.length} 部未收录（已跳过 ${skippedCollectedCount} 部${feedTotal > 0 ? ` · 共 ${feedTotal} 部` : ''}）`
                    : `已加载 ${visibleFeedItems.length}${feedTotal > 0 ? ` / ${feedTotal}` : ''} 部`}
              </span>
            </>
          )}
        </div>
      </div>

      {/* 主体展示区 */}
      <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
        {errorMsg && (
          <div
            className="card-box"
            style={{
              marginBottom: 14,
              color: 'var(--danger)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              gap: 10
            }}
          >
            <span>{errorMsg}</span>
            <button
              className="btn"
              onClick={() => {
                if (isCalendarView) void loadCalendar(true)
                else void loadDiscoveryPage(1, false, true)
              }}
            >
              重试
            </button>
          </div>
        )}

        {isCalendarView ? (
          calendarLoading && days.length === 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <LoadingSpinner size="md" label="正在加载数据..." />
              <SkeletonCards count={8} minWidth={250} />
            </div>
          ) : totalCalendarVisible === 0 ? (
            <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)' }}>
              当前筛选条件下暂无放送番剧。
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
              {visibleDays
                .filter((d) => d.items.length > 0)
                .map((d) => (
                  <section key={d.weekdayId}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                      <h3 className="heading-font" style={{ margin: 0, fontSize: '1.1538rem' }}>
                        {d.weekdayCn}
                      </h3>
                      {d.weekdayId === todayId && <span className="badge badge-accent">今日放送</span>}
                      <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                        {d.items.length} 部
                      </span>
                    </div>

                    <div
                      style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(270px, 1fr))',
                        gap: 12
                      }}
                    >
                      {d.items.map((cal) => {
                        const localMatch = matchLocalStatus('anime', cal.id, cal.title, cal.originalTitle)
                        const existing = localMatch.exact
                        const suspected = localMatch.suspected
                        const isAdding = addingKey === `cal:${cal.id}`
                        const justAdded = justAddedMap[`cal:${cal.id}`]
                        return (
                          <div
                            key={`${d.weekdayId}:${cal.id}`}
                            className="card-box"
                            style={{
                              padding: 10,
                              display: 'flex',
                              gap: 10,
                              alignItems: 'stretch',
                              borderColor: justAdded ? 'var(--positive)' : undefined,
                              transition: 'border-color 180ms ease'
                            }}
                          >
                            <div
                              style={{
                                width: 68,
                                height: 96,
                                borderRadius: 'var(--radius-sm)',
                                overflow: 'hidden',
                                background: 'var(--bg-canvas)',
                                flexShrink: 0,
                                border: '1px solid var(--border-subtle)',
                                cursor: existing ? 'pointer' : 'default'
                              }}
                              onClick={() => existing && onSelectWork(existing.kind, existing.id)}
                            >
                              <CoverImage
                                coverUrl={cal.coverUrl}
                                mediaBaseUrl={mediaBaseUrl}
                                alt={cal.title}
                                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                                fallback={
                                  <div
                                    style={{
                                      width: '100%',
                                      height: '100%',
                                      display: 'flex',
                                      alignItems: 'center',
                                      justifyContent: 'center',
                                      fontSize: '0.8462rem',
                                      color: 'var(--text-muted)',
                                      padding: 4,
                                      textAlign: 'center'
                                    }}
                                  >
                                    {cal.title.slice(0, 8)}
                                  </div>
                                }
                              />
                            </div>

                            <div
                              style={{
                                flex: 1,
                                minWidth: 0,
                                display: 'flex',
                                flexDirection: 'column',
                                justifyContent: 'space-between',
                                gap: 6
                              }}
                            >
                              <div>
                                <div
                                  className="heading-font"
                                  style={{
                                    fontSize: '1.0385rem',
                                    fontWeight: 700,
                                    lineHeight: 1.3,
                                    display: '-webkit-box',
                                    WebkitLineClamp: 2,
                                    WebkitBoxOrient: 'vertical',
                                    overflow: 'hidden'
                                  }}
                                  title={cal.title}
                                >
                                  {cal.title}
                                </div>
                                <div
                                  style={{
                                    display: 'flex',
                                    gap: 6,
                                    flexWrap: 'wrap',
                                    marginTop: 4,
                                    fontSize: '0.8462rem',
                                    color: 'var(--text-muted)'
                                  }}
                                >
                                  {cal.score != null && (
                                    <span className="badge badge-accent mono">
                                      评分 {cal.score.toFixed(1)}
                                    </span>
                                  )}
                                  {cal.rank != null && <span className="mono">#{cal.rank}</span>}
                                  {cal.airDate && <span className="mono">{cal.airDate}</span>}
                                </div>
                              </div>

                              <div style={{ display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0, maxWidth: '100%' }}>
                                {suspected &&
                                  !existing &&
                                  !ignoredSuspectedKeys.has(`cal:${cal.id}:${suspected.item.id}`) && (
                                    <div
                                      style={{
                                        display: 'flex',
                                        alignItems: 'center',
                                        gap: 4,
                                        minWidth: 0,
                                        maxWidth: '100%'
                                      }}
                                    >
                                      <button
                                        type="button"
                                        className="btn"
                                        style={{
                                          flex: '1 1 auto',
                                          minWidth: 0,
                                          maxWidth: '100%',
                                          overflow: 'hidden',
                                          textOverflow: 'ellipsis',
                                          whiteSpace: 'nowrap',
                                          textAlign: 'left',
                                          display: 'block',
                                          padding: '2px 7px',
                                          fontSize: '0.8462rem',
                                          background: 'var(--badge-amber-bg)',
                                          color: 'var(--badge-amber-fg)',
                                          borderColor: 'var(--badge-amber-border)'
                                        }}
                                        title={`疑似与本地《${suspected.item.title}》重复（${suspected.reason}），点击查看本地条目`}
                                        onClick={() => onSelectWork(suspected.item.kind, suspected.item.id)}
                                      >
                                        疑似已有：《{suspected.item.title}》· {suspected.item.status}
                                      </button>
                                      <button
                                        type="button"
                                        className="btn"
                                        style={{
                                          flexShrink: 0,
                                          padding: '2px 6px',
                                          fontSize: '0.8077rem',
                                          color: 'var(--text-muted)'
                                        }}
                                        title="确认不是同一部作品，忽略此疑似提示"
                                        onClick={() =>
                                          dismissSuspected(`cal:${cal.id}:${suspected.item.id}`)
                                        }
                                      >
                                        忽略
                                      </button>
                                    </div>
                                  )}
                                <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
                                  {existing || justAdded ? (
                                    <>
                                      <button
                                        className="btn"
                                        style={{
                                          padding: '3px 8px',
                                          fontSize: '0.8846rem',
                                          borderColor: justAdded ? 'var(--positive)' : undefined,
                                          color: justAdded ? 'var(--positive)' : undefined,
                                          fontWeight: justAdded ? 600 : undefined
                                        }}
                                        onClick={() => existing && onSelectWork(existing.kind, existing.id)}
                                      >
                                        {justAdded
                                          ? `✓ 已入库 · ${justAdded.status}`
                                          : `已收录 · ${existing?.status}`}
                                      </button>
                                      <button
                                        className="btn btn-primary"
                                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                        onClick={() => onOpenOnlinePlay(existing, cal.title)}
                                      >
                                        在线看番
                                      </button>
                                    </>
                                  ) : (
                                    <>
                                      <button
                                        className="btn btn-primary"
                                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                        disabled={isAdding}
                                        onClick={() => void handleQuickAddCalendar(cal, animeStatuses[0])}
                                      >
                                        {isAdding ? (
                                          <LoadingSpinner size="sm" inline label={`加入「${addingStatus ?? animeStatuses[0]}」中`} />
                                        ) : (
                                          `+ ${animeStatuses[0]}`
                                        )}
                                      </button>
                                      <StudioSelect
                                        className="studio-select-inline"
                                        disabled={isAdding}
                                        value=""
                                        placeholder="其他状态"
                                        ariaLabel="选择入库状态"
                                        minMenuWidth={112}
                                        maxMenuWidth={150}
                                        onChange={(st) => {
                                          if (st) void handleQuickAddCalendar(cal, st)
                                        }}
                                        options={animeStatuses.map((st) => ({
                                          value: st,
                                          label: `+ ${st}`
                                        }))}
                                      />
                                      <button
                                        className="btn"
                                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                        onClick={() => onOpenOnlinePlay(null, cal.title)}
                                      >
                                        在线看
                                      </button>
                                    </>
                                  )}
                                </div>
                              </div>
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  </section>
                ))}
            </div>
          )
        ) : feedLoading && feedItems.length === 0 ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            <LoadingSpinner size="md" label="正在加载数据..." />
            <SkeletonCards count={10} minWidth={260} />
          </div>
        ) : visibleFeedItems.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)' }}>
            当前筛选条件下暂无发现结果，可尝试更换关键词或季度。
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                gap: 12
              }}
            >
              {visibleFeedItems.map((item) => {
                const key = `${item.externalSource}:${item.externalId}`
                const localMatch = matchLocalStatus(
                  item.kind,
                  Number(item.externalId) || 0,
                  item.title,
                  item.originalTitle
                )
                const existing = localMatch.exact
                const suspected = localMatch.suspected
                const isAdding = addingKey === key
                const justAdded = justAddedMap[key]
                return (
                  <div
                    key={key}
                    className="card-box"
                    style={{
                      padding: 10,
                      display: 'flex',
                      gap: 10,
                      alignItems: 'stretch',
                      borderColor: justAdded ? 'var(--positive)' : undefined,
                      opacity: justAdded?.leaving ? 0.2 : 1,
                      transform: justAdded?.leaving ? 'scale(0.98)' : undefined,
                      transition: 'opacity 280ms ease, transform 280ms ease, border-color 180ms ease'
                    }}
                  >
                    <div
                      style={{
                        width: 72,
                        height: 102,
                        borderRadius: 'var(--radius-sm)',
                        overflow: 'hidden',
                        background: 'var(--bg-canvas)',
                        flexShrink: 0,
                        border: '1px solid var(--border-subtle)',
                        cursor: existing ? 'pointer' : 'default'
                      }}
                      onClick={() => existing && onSelectWork(existing.kind, existing.id)}
                    >
                      <CoverImage
                        coverUrl={item.coverUrl}
                        mediaBaseUrl={mediaBaseUrl}
                        alt={item.title}
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        fallback={
                          <div
                            style={{
                              width: '100%',
                              height: '100%',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              fontSize: '0.8462rem',
                              color: 'var(--text-muted)',
                              padding: 4,
                              textAlign: 'center'
                            }}
                          >
                            {item.title.slice(0, 8)}
                          </div>
                        }
                      />
                    </div>

                    <div
                      style={{
                        flex: 1,
                        minWidth: 0,
                        display: 'flex',
                        flexDirection: 'column',
                        justifyContent: 'space-between',
                        gap: 6
                      }}
                    >
                      <div>
                        <div
                          className="heading-font"
                          style={{
                            fontSize: '1.0385rem',
                            fontWeight: 700,
                            lineHeight: 1.3,
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden'
                          }}
                          title={item.title}
                        >
                          {item.title}
                        </div>

                        <div
                          style={{
                            display: 'flex',
                            gap: 5,
                            flexWrap: 'wrap',
                            marginTop: 4,
                            fontSize: '0.8462rem',
                            color: 'var(--text-muted)',
                            alignItems: 'center'
                          }}
                        >
                          {item.score != null && (
                            <span className="badge badge-accent mono">
                              {item.score.toFixed(1)}
                              {item.ratingCount > 0 ? ` (${item.ratingCount})` : ''}
                            </span>
                          )}
                          {item.kind === 'book' && item.mediaFormat && (
                            <span className="badge">{item.mediaFormat}</span>
                          )}
                          {item.rank != null && <span className="badge mono">#{item.rank}</span>}
                          {item.airDate && <span className="mono">{item.airDate}</span>}
                          {item.totalEpisodes > 0 && item.kind === 'anime' && (
                            <span className="mono">{item.totalEpisodes}集</span>
                          )}
                        </div>

                        {(item.studioOrDev || item.tags.length > 0) && (
                          <div
                            style={{
                              fontSize: '0.8462rem',
                              color: 'var(--text-muted)',
                              marginTop: 3,
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis'
                            }}
                            title={[item.studioOrDev, ...item.tags].filter(Boolean).join(' · ')}
                          >
                            {[item.studioOrDev, ...item.tags].filter(Boolean).join(' · ')}
                          </div>
                        )}
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: 5, minWidth: 0, maxWidth: '100%' }}>
                        {suspected &&
                          !existing &&
                          !ignoredSuspectedKeys.has(
                            `disc:${item.kind}:${item.externalId}:${suspected.item.id}`
                          ) && (
                            <div
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: 4,
                                minWidth: 0,
                                maxWidth: '100%'
                              }}
                            >
                              <button
                                type="button"
                                className="btn"
                                style={{
                                  flex: '1 1 auto',
                                  minWidth: 0,
                                  maxWidth: '100%',
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  whiteSpace: 'nowrap',
                                  textAlign: 'left',
                                  display: 'block',
                                  padding: '2px 7px',
                                  fontSize: '0.8462rem',
                                  background: 'var(--badge-amber-bg)',
                                  color: 'var(--badge-amber-fg)',
                                  borderColor: 'var(--badge-amber-border)'
                                }}
                                title={`疑似与本地《${suspected.item.title}》重复（${suspected.reason}），点击查看本地条目`}
                                onClick={() => onSelectWork(suspected.item.kind, suspected.item.id)}
                              >
                                疑似已有：《{suspected.item.title}》· {suspected.item.status}
                              </button>
                              <button
                                type="button"
                                className="btn"
                                style={{
                                  flexShrink: 0,
                                  padding: '2px 6px',
                                  fontSize: '0.8077rem',
                                  color: 'var(--text-muted)'
                                }}
                                title="确认不是同一部作品，忽略此疑似提示"
                                onClick={() =>
                                  dismissSuspected(
                                    `disc:${item.kind}:${item.externalId}:${suspected.item.id}`
                                  )
                                }
                              >
                                忽略
                              </button>
                            </div>
                          )}
                        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
                          {existing || justAdded ? (
                            <>
                              <button
                                className="btn"
                                style={{
                                  padding: '3px 8px',
                                  fontSize: '0.8846rem',
                                  borderColor: justAdded ? 'var(--positive)' : undefined,
                                  color: justAdded ? 'var(--positive)' : undefined,
                                  fontWeight: justAdded ? 600 : undefined
                                }}
                                onClick={() => existing && onSelectWork(existing.kind, existing.id)}
                              >
                                {justAdded
                                  ? `✓ 已入库 · ${justAdded.status}`
                                  : `已收录 · ${existing?.status}`}
                              </button>
                              {item.kind === 'anime' && (
                                <button
                                  className="btn btn-primary"
                                  style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                  onClick={() => onOpenOnlinePlay(existing, item.title)}
                                >
                                  在线看番
                                </button>
                              )}
                            </>
                          ) : (
                            <>
                              <button
                                className="btn btn-primary"
                                style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                disabled={isAdding}
                                onClick={() => void handleQuickAddDiscovery(item, addStatuses[0])}
                              >
                                {isAdding ? (
                                  <LoadingSpinner size="sm" inline label={`加入「${addingStatus ?? addStatuses[0]}」中`} />
                                ) : (
                                  `+ ${addStatuses[0]}`
                                )}
                              </button>
                              <StudioSelect
                                className="studio-select-inline"
                                disabled={isAdding}
                                value=""
                                placeholder="其他状态"
                                ariaLabel="选择入库状态"
                                minMenuWidth={112}
                                maxMenuWidth={150}
                                onChange={(st) => {
                                  if (st) void handleQuickAddDiscovery(item, st)
                                }}
                                options={addStatuses.map((st) => ({
                                  value: st,
                                  label: `+ ${st}`
                                }))}
                              />
                              {item.kind === 'anime' && (
                                <button
                                  className="btn"
                                  style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                                  onClick={() => onOpenOnlinePlay(null, item.title)}
                                >
                                  在线看
                                </button>
                              )}
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>

            {feedHasMore && (
              <div style={{ display: 'flex', justifyContent: 'center', paddingBottom: 12 }}>
                <button
                  type="button"
                  className="btn"
                  disabled={loadingMore}
                  onClick={() => {
                    if (hideCollected) {
                      autoFetchStreakRef.current = 0
                      setHideTargetCount(visibleFeedItems.length + 18)
                    }
                    void loadDiscoveryPage(feedPage + 1, true)
                  }}
                >
                  {loadingMore ? (
                    <LoadingSpinner
                      size="sm"
                      inline
                      label={hideCollected ? '正在跳过已收录并加载更多...' : '正在加载下一页...'}
                    />
                  ) : (
                    '加载更多作品'
                  )}
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {notice && (
        <div
          role="status"
          style={{
            position: 'fixed',
            left: '50%',
            bottom: 24,
            transform: 'translateX(-50%)',
            zIndex: 490,
            padding: '7px 14px',
            borderRadius: 999,
            background: 'var(--bg-elevated)',
            border: '1px solid var(--positive)',
            color: 'var(--positive)',
            fontWeight: 600,
            fontSize: '0.9231rem',
            boxShadow: '0 8px 24px rgba(0, 0, 0, 0.22)',
            pointerEvents: 'none'
          }}
        >
          ✓ {notice}
        </div>
      )}
    </div>
  )
}
