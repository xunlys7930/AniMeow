import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  classifyStatusLane,
  formatCarrierLabel,
  resolveCoverSrc,
  spinePaletteColor,
  studioApi
} from '../api'
import { CoverImage } from './CoverImage'
import { LoadingSpinner } from './LoadingIndicator'
import { StudioSelect } from './StudioSelect'
import { useStudioDialog } from './StudioDialog'
import type {
  CuratorItem,
  MediaCarrier,
  PlaySessionRow,
  TierListEntry
} from '../types'

interface AnalyticsYearbookTabProps {
  items: CuratorItem[]
  mediaBaseUrl: string
  onSelectWork: (kind: MediaCarrier, id: number) => void
}

type TierRank = TierListEntry['tier']

const TIER_DEFS: Array<{
  tier: TierRank
  label: string
  sublabel: string
  accentBg: string
  accentFg: string
}> = [
  { tier: 'S', label: 'S', sublabel: '殿堂神作', accentBg: '#b53329', accentFg: '#faf7f0' },
  { tier: 'A', label: 'A', sublabel: '强推佳作', accentBg: '#c96f1d', accentFg: '#faf7f0' },
  { tier: 'B', label: 'B', sublabel: '品质良作', accentBg: '#3d6950', accentFg: '#faf7f0' },
  { tier: 'C', label: 'C', sublabel: '尚可一观', accentBg: '#3a627d', accentFg: '#faf7f0' },
  { tier: 'D', label: 'D', sublabel: '个人遗憾', accentBg: '#6c665d', accentFg: '#faf7f0' }
]

function itemKey(kind: MediaCarrier, id: number): string {
  return `${kind}:${id}`
}

function formatDurationMinutes(totalSeconds: number): string {
  const mins = Math.round(totalSeconds / 60)
  if (mins < 60) return `${mins} 分钟`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m > 0 ? `${h}h ${m}m` : `${h}h`
}

function toIsoDateLocal(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function heatCellLevel(seconds: number): number {
  if (seconds <= 0) return 0
  if (seconds < 30 * 60) return 1
  if (seconds < 90 * 60) return 2
  if (seconds < 180 * 60) return 3
  return 4
}

/**
 * 06 · 数据透视年鉴：评分分布与分差散点图、时长热力日历与可拖拽 S-D 个人神作榜。
 */
export function AnalyticsYearbookTab({
  items,
  mediaBaseUrl,
  onSelectWork
}: AnalyticsYearbookTabProps) {
  const dialog = useStudioDialog()
  const [carrierFilter, setCarrierFilter] = useState<'all' | MediaCarrier>('all')
  const [sessions, setSessions] = useState<PlaySessionRow[]>([])
  const [tierEntries, setTierEntries] = useState<TierListEntry[]>([])
  const [loadingAux, setLoadingAux] = useState<boolean>(true)
  const [auxError, setAuxError] = useState<string | null>(null)

  const [hoveredScatterKey, setHoveredScatterKey] = useState<string | null>(null)
  const [tierSearch, setTierSearch] = useState<string>('')
  const [tierDensity, setTierDensity] = useState<'compact' | 'standard'>('compact')
  const [poolFilter, setPoolFilter] = useState<'all' | 'rated' | 'completed'>('all')
  const [poolSort, setPoolSort] = useState<'score_desc' | 'score_asc' | 'title'>('score_desc')
  const [poolLimit, setPoolLimit] = useState<number>(60)
  const [collapsedTiers, setCollapsedTiers] = useState<Set<TierRank>>(() => new Set())
  const [autoFillingTiers, setAutoFillingTiers] = useState<boolean>(false)
  const [tierBatchMode, setTierBatchMode] = useState<boolean>(false)
  const [selectedTierKeys, setSelectedTierKeys] = useState<Set<string>>(() => new Set())
  const [batchTierBusy, setBatchTierBusy] = useState<boolean>(false)
  const [pointerDrag, setPointerDrag] = useState<{
    item: CuratorItem
    fromTier: TierRank | null
    startX: number
    startY: number
    curX: number
    curY: number
    dragging: boolean
  } | null>(null)
  const pointerDragRef = useRef<{
    item: CuratorItem
    fromTier: TierRank | null
    startX: number
    startY: number
    curX: number
    curY: number
    dragging: boolean
  } | null>(null)
  const lastDragEndAtRef = useRef<number>(0)
  const [dragOverTier, setDragOverTier] = useState<TierRank | 'pool' | null>(null)
  const [dragOverBeforeKey, setDragOverBeforeKey] = useState<string | null>(null)
  const dragOverTierRef = useRef<TierRank | 'pool' | null>(null)
  const dragOverBeforeKeyRef = useRef<string | null>(null)
  const [tierNotice, setTierNotice] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoadingAux(true)
    setAuxError(null)
    Promise.all([studioApi.listPlaySessions(), studioApi.getTierList()])
      .then(([sess, tiers]) => {
        if (cancelled) return
        setSessions(sess)
        setTierEntries(tiers)
      })
      .catch((err) => {
        if (!cancelled) setAuxError(err instanceof Error ? err.message : String(err))
      })
      .finally(() => {
        if (!cancelled) setLoadingAux(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const scopedItems = useMemo(
    () => (carrierFilter === 'all' ? items : items.filter((it) => it.kind === carrierFilter)),
    [items, carrierFilter]
  )

  const itemMap = useMemo(() => {
    const map = new Map<string, CuratorItem>()
    for (const it of items) {
      map.set(itemKey(it.kind, it.id), it)
    }
    return map
  }, [items])

  // 顶部 KPI 汇总
  const metrics = useMemo(() => {
    let completed = 0
    let ratedCount = 0
    let ratingSum = 0
    let deltaCount = 0
    let deltaSum = 0
    let totalGameMinutes = 0
    let totalAudioMinutes = 0
    let totalWatchedEpisodes = 0

    for (const it of scopedItems) {
      if (classifyStatusLane(it.status) === 'done') completed++
      if (it.personalScore != null) {
        ratedCount++
        ratingSum += it.personalScore
      }
      if (it.scoreDelta != null) {
        deltaCount++
        deltaSum += it.scoreDelta
      }
      if (it.kind === 'game') totalGameMinutes += it.progressPrimary
      else if (it.kind === 'audio') totalAudioMinutes += Math.floor(it.progressPrimary / 60)
      else if (it.kind === 'anime') totalWatchedEpisodes += it.progressPrimary
    }

    const sessionSeconds = sessions.reduce((acc, s) => acc + Math.max(0, s.durationSeconds), 0)
    return {
      total: scopedItems.length,
      completed,
      ratedCount,
      avgPersonal: ratedCount > 0 ? ratingSum / ratedCount : null,
      avgDelta: deltaCount > 0 ? deltaSum / deltaCount : null,
      totalGameMinutes,
      totalAudioMinutes,
      totalWatchedEpisodes,
      sessionSeconds
    }
  }, [scopedItems, sessions])

  // 1-10 分直方图分桶
  const scoreBuckets = useMemo(() => {
    const counts = Array.from({ length: 10 }, (_, idx) => ({
      bucket: idx + 1,
      label: `${idx + 1}分`,
      count: 0
    }))
    for (const it of scopedItems) {
      if (it.personalScore == null) continue
      const idx = Math.max(0, Math.min(9, Math.round(it.personalScore) - 1))
      counts[idx].count++
    }
    const maxCount = Math.max(1, ...counts.map((c) => c.count))
    return { counts, maxCount }
  }, [scopedItems])

  // 分差散点数据与极值榜单
  const scatterData = useMemo(() => {
    const points = scopedItems.filter(
      (it) => it.personalScore != null && it.publicScore != null && it.scoreDelta != null
    )
    const sortedByDelta = [...points].sort((a, b) => (b.scoreDelta ?? 0) - (a.scoreDelta ?? 0))
    const mostLoved = sortedByDelta.filter((it) => (it.scoreDelta ?? 0) > 0).slice(0, 5)
    const mostStrict = [...sortedByDelta]
      .reverse()
      .filter((it) => (it.scoreDelta ?? 0) < 0)
      .slice(0, 5)
    return { points, mostLoved, mostStrict }
  }, [scopedItems])

  const hoveredScatterItem = useMemo(
    () => (hoveredScatterKey ? itemMap.get(hoveredScatterKey) ?? null : null),
    [hoveredScatterKey, itemMap]
  )

  // 最近 26 周（182 天）游玩/陪伴时长热力图 + 月度统计
  const heatmap = useMemo(() => {
    const byDate = new Map<string, number>()
    const byMonth = new Map<string, number>()
    for (const s of sessions) {
      const dateStr = (s.startedAt || '').slice(0, 10)
      if (!/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) continue
      const secs = Math.max(0, s.durationSeconds)
      byDate.set(dateStr, (byDate.get(dateStr) ?? 0) + secs)
      const ym = dateStr.slice(0, 7)
      byMonth.set(ym, (byMonth.get(ym) ?? 0) + secs)
    }

    const today = new Date()
    today.setHours(0, 0, 0, 0)
    // 对齐到本周六（或周日），展示 26 周 * 7 天
    const totalDays = 26 * 7
    const start = new Date(today)
    start.setDate(today.getDate() - (totalDays - 1))

    const weeks: Array<Array<{ date: string; seconds: number; level: number }>> = []
    let currentWeek: Array<{ date: string; seconds: number; level: number }> = []
    for (let i = 0; i < totalDays; i++) {
      const d = new Date(start)
      d.setDate(start.getDate() + i)
      const iso = toIsoDateLocal(d)
      const secs = byDate.get(iso) ?? 0
      currentWeek.push({ date: iso, seconds: secs, level: heatCellLevel(secs) })
      if (currentWeek.length === 7) {
        weeks.push(currentWeek)
        currentWeek = []
      }
    }
    if (currentWeek.length > 0) weeks.push(currentWeek)

    // 最近 6 个月时长汇总
    const recentMonths: Array<{ ym: string; seconds: number }> = []
    for (let i = 5; i >= 0; i--) {
      const d = new Date(today.getFullYear(), today.getMonth() - i, 1)
      const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
      recentMonths.push({ ym, seconds: byMonth.get(ym) ?? 0 })
    }
    const maxMonthSec = Math.max(1, ...recentMonths.map((m) => m.seconds))

    return { weeks, recentMonths, maxMonthSec }
  }, [sessions])

  // 藏品发售年份谱系（基于真实 releaseDate）
  const releaseDecades = useMemo(() => {
    const map = new Map<string, number>()
    for (const it of scopedItems) {
      const match = /^(\d{4})/.exec(it.releaseDate.trim())
      const key = match ? match[1] : '未填年份'
      map.set(key, (map.get(key) ?? 0) + 1)
    }
    const entries = [...map.entries()].sort((a, b) => {
      if (a[0] === '未填年份') return 1
      if (b[0] === '未填年份') return -1
      return b[0].localeCompare(a[0])
    })
    const top = entries.slice(0, 12)
    const maxCnt = Math.max(1, ...top.map((e) => e[1]))
    return { top, maxCnt }
  }, [scopedItems])

  // S / A / B / C / D 榜单分组映射
  const tierGroups = useMemo(() => {
    const map: Record<TierRank, CuratorItem[]> = { S: [], A: [], B: [], C: [], D: [] }
    const assignedKeys = new Set<string>()
    const sorted = [...tierEntries].sort((a, b) => a.sortOrder - b.sortOrder)
    for (const entry of sorted) {
      const k = itemKey(entry.workKind, entry.workId)
      const found = itemMap.get(k)
      if (!found) continue
      map[entry.tier].push(found)
      assignedKeys.add(k)
    }
    return { map, assignedKeys }
  }, [tierEntries, itemMap])

  const poolItems = useMemo(() => {
    const q = tierSearch.trim().toLowerCase()
    const filtered = scopedItems
      .filter((it) => !tierGroups.assignedKeys.has(itemKey(it.kind, it.id)))
      .filter((it) => {
        if (poolFilter === 'rated' && it.personalScore == null) return false
        if (poolFilter === 'completed' && classifyStatusLane(it.status) !== 'done') return false
        if (!q) return true
        return (
          it.title.toLowerCase().includes(q) ||
          it.originalTitle.toLowerCase().includes(q) ||
          (it.seriesName ?? '').toLowerCase().includes(q)
        )
      })
    return [...filtered].sort((a, b) => {
      if (poolSort === 'score_asc') {
        return (a.personalScore ?? 999) - (b.personalScore ?? 999)
      }
      if (poolSort === 'title') {
        return a.title.localeCompare(b.title, 'zh-Hans-CN')
      }
      return (b.personalScore ?? -1) - (a.personalScore ?? -1)
    })
  }, [scopedItems, tierGroups.assignedKeys, tierSearch, poolFilter, poolSort])

  useEffect(() => {
    setPoolLimit(60)
  }, [tierSearch, poolFilter, poolSort, carrierFilter])

  const ratedUnassignedCount = useMemo(
    () => poolItems.filter((it) => it.personalScore != null).length,
    [poolItems]
  )

  const handleAutoFillByScore = async () => {
    const targets = poolItems.filter((it) => it.personalScore != null)
    if (targets.length === 0 || autoFillingTiers) return
    setAutoFillingTiers(true)
    try {
      const nextMap: Record<TierRank, TierListEntry[]> = { S: [], A: [], B: [], C: [], D: [] }
      for (const e of [...tierEntries].sort((a, b) => a.sortOrder - b.sortOrder)) {
        nextMap[e.tier].push({ ...e })
      }
      const added: TierListEntry[] = []
      for (const it of targets) {
        const s = it.personalScore ?? 0
        const tier: TierRank =
          s >= 9 ? 'S' : s >= 8 ? 'A' : s >= 7 ? 'B' : s >= 6 ? 'C' : 'D'
        const entry: TierListEntry = {
          workKind: it.kind,
          workId: it.id,
          tier,
          sortOrder: nextMap[tier].length
        }
        nextMap[tier].push(entry)
        added.push(entry)
      }
      const merged = [
        ...nextMap.S,
        ...nextMap.A,
        ...nextMap.B,
        ...nextMap.C,
        ...nextMap.D
      ]
      setTierEntries(merged)
      // 分批写入避免 400+ 并发占用过多 IPC
      const CHUNK = 30
      for (let i = 0; i < added.length; i += CHUNK) {
        const slice = added.slice(i, i + CHUNK)
        await Promise.all(
          slice.map((e) => studioApi.saveTierEntry(e.workKind, e.workId, e.tier, e.sortOrder))
        )
      }
      setTierNotice(`已按个人评分自动将 ${added.length} 部未排位作品填入对应阶梯`)
      window.setTimeout(() => setTierNotice(null), 3200)
    } finally {
      setAutoFillingTiers(false)
    }
  }

  const toggleTierCollapse = (tier: TierRank) => {
    setCollapsedTiers((prev) => {
      const next = new Set(prev)
      if (next.has(tier)) next.delete(tier)
      else next.add(tier)
      return next
    })
  }

  const persistTierOrder = useCallback(
    async (
      nextTier: TierRank | null,
      targetKind: MediaCarrier,
      targetId: number,
      insertBeforeKey?: string | null
    ) => {
      const targetKey = itemKey(targetKind, targetId)
      let nextEntries = tierEntries.filter((e) => itemKey(e.workKind, e.workId) !== targetKey)

      if (nextTier == null) {
        setTierEntries(nextEntries)
        await studioApi.saveTierEntry(targetKind, targetId, null, 0)
        setTierNotice('已将该作品移出神作榜')
        window.setTimeout(() => setTierNotice(null), 2200)
        return
      }

      const inTarget = nextEntries
        .filter((e) => e.tier === nextTier)
        .sort((a, b) => a.sortOrder - b.sortOrder)

      let insertIndex = inTarget.length
      if (insertBeforeKey) {
        const idx = inTarget.findIndex((e) => itemKey(e.workKind, e.workId) === insertBeforeKey)
        if (idx >= 0) insertIndex = idx
      }

      const inserted: TierListEntry = {
        workKind: targetKind,
        workId: targetId,
        tier: nextTier,
        sortOrder: insertIndex
      }
      inTarget.splice(insertIndex, 0, inserted)
      const reindexed = inTarget.map((e, idx) => ({ ...e, sortOrder: idx }))
      const others = nextEntries.filter((e) => e.tier !== nextTier)
      nextEntries = [...others, ...reindexed]
      setTierEntries(nextEntries)

      await Promise.all(
        reindexed.map((e) => studioApi.saveTierEntry(e.workKind, e.workId, e.tier, e.sortOrder))
      )
      setTierNotice(`已保存至 ${nextTier} 级榜单`)
      window.setTimeout(() => setTierNotice(null), 2000)
    },
    [tierEntries]
  )

  const handleMoveWithinTier = async (tier: TierRank, kind: MediaCarrier, id: number, dir: -1 | 1) => {
    const inTier = tierEntries
      .filter((e) => e.tier === tier)
      .sort((a, b) => a.sortOrder - b.sortOrder)
    const idx = inTier.findIndex((e) => e.workKind === kind && e.workId === id)
    const targetIdx = idx + dir
    if (idx < 0 || targetIdx < 0 || targetIdx >= inTier.length) return
    const copy = [...inTier]
    const [moved] = copy.splice(idx, 1)
    copy.splice(targetIdx, 0, moved)
    const reindexed = copy.map((e, i) => ({ ...e, sortOrder: i }))
    const others = tierEntries.filter((e) => e.tier !== tier)
    setTierEntries([...others, ...reindexed])
    await Promise.all(
      reindexed.map((e) => studioApi.saveTierEntry(e.workKind, e.workId, e.tier, e.sortOrder))
    )
  }

  const tierNoticeTimerRef = useRef<number | null>(null)
  useEffect(() => {
    return () => {
      if (tierNoticeTimerRef.current != null) {
        window.clearTimeout(tierNoticeTimerRef.current)
      }
    }
  }, [])

  const flashTierNotice = useCallback((msg: string, ms = 2600) => {
    setTierNotice(msg)
    if (tierNoticeTimerRef.current != null) {
      window.clearTimeout(tierNoticeTimerRef.current)
    }
    tierNoticeTimerRef.current = window.setTimeout(() => {
      setTierNotice(null)
      tierNoticeTimerRef.current = null
    }, ms)
  }, [])

  const saveTierBatchOps = useCallback(
    async (ops: Array<{ workKind: MediaCarrier; workId: number; tier: TierRank | null; sortOrder: number }>) => {
      const CHUNK = 30
      for (let i = 0; i < ops.length; i += CHUNK) {
        const slice = ops.slice(i, i + CHUNK)
        await Promise.all(
          slice.map((op) => studioApi.saveTierEntry(op.workKind, op.workId, op.tier, op.sortOrder))
        )
      }
    },
    []
  )

  const toggleSelectTierKey = useCallback((key: string) => {
    setSelectedTierKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  const handleBatchMoveSelected = async (targetTier: TierRank | null) => {
    if (selectedTierKeys.size === 0 || batchTierBusy) return
    setBatchTierBusy(true)
    try {
      const selectedItems: CuratorItem[] = []
      for (const key of selectedTierKeys) {
        const found = itemMap.get(key)
        if (found) selectedItems.push(found)
      }
      if (selectedItems.length === 0) return

      const selectedSet = new Set(selectedItems.map((it) => itemKey(it.kind, it.id)))
      const remaining = tierEntries.filter((e) => !selectedSet.has(itemKey(e.workKind, e.workId)))

      if (targetTier == null) {
        setTierEntries(remaining)
        await saveTierBatchOps(
          selectedItems.map((it) => ({
            workKind: it.kind,
            workId: it.id,
            tier: null,
            sortOrder: 0
          }))
        )
        setSelectedTierKeys(new Set())
        flashTierNotice(`已将 ${selectedItems.length} 部作品移出榜单`)
        return
      }

      const inTarget = remaining
        .filter((e) => e.tier === targetTier)
        .sort((a, b) => a.sortOrder - b.sortOrder)
      const appended: TierListEntry[] = selectedItems.map((it, idx) => ({
        workKind: it.kind,
        workId: it.id,
        tier: targetTier,
        sortOrder: inTarget.length + idx
      }))
      const reindexed = [...inTarget, ...appended].map((e, idx) => ({ ...e, sortOrder: idx }))
      const others = remaining.filter((e) => e.tier !== targetTier)
      setTierEntries([...others, ...reindexed])
      await saveTierBatchOps(
        reindexed.map((e) => ({
          workKind: e.workKind,
          workId: e.workId,
          tier: e.tier,
          sortOrder: e.sortOrder
        }))
      )
      setSelectedTierKeys(new Set())
      flashTierNotice(`已将 ${selectedItems.length} 部作品批量移至 ${targetTier} 级`)
    } catch (err) {
      flashTierNotice(`批量调整失败：${err instanceof Error ? err.message : String(err)}`, 4000)
    } finally {
      setBatchTierBusy(false)
    }
  }

  const handleBatchReorderRule = async (rule: string) => {
    if (!rule || batchTierBusy || tierEntries.length === 0) return
    if (rule === 'regrade_score') {
      const ok = await dialog.confirm({
        title: '按个人评分重新分档？',
        message: '将按个人评分（≥9 S / ≥8 A / ≥7 B / ≥6 C / <6 D）重新划分所有已上榜且已评分作品的档位，未评分作品保留在原档位末尾。',
        confirmText: '确认重排',
        cancelText: '取消'
      })
      if (!ok) return
    } else if (rule === 'clear_all') {
      const ok = await dialog.confirm({
        title: '清空个人神作榜？',
        message: `确认将当前已上榜的 ${tierEntries.length} 部作品全部移回待排位池？此操作仅清空榜单排位，不影响藏品记录与评分。`,
        confirmText: '确认清空',
        cancelText: '取消',
        tone: 'danger'
      })
      if (!ok) return
    }

    setBatchTierBusy(true)
    try {
      if (rule === 'clear_all') {
        const snapshot = [...tierEntries]
        setTierEntries([])
        setSelectedTierKeys(new Set())
        await saveTierBatchOps(
          snapshot.map((e) => ({
            workKind: e.workKind,
            workId: e.workId,
            tier: null,
            sortOrder: 0
          }))
        )
        flashTierNotice('已清空榜单排位')
        return
      }

      const buckets: Record<TierRank, TierListEntry[]> = { S: [], A: [], B: [], C: [], D: [] }
      for (const e of tierEntries) {
        if (rule === 'regrade_score') {
          const it = itemMap.get(itemKey(e.workKind, e.workId))
          const s = it?.personalScore
          const nextTier: TierRank =
            s == null ? e.tier : s >= 9 ? 'S' : s >= 8 ? 'A' : s >= 7 ? 'B' : s >= 6 ? 'C' : 'D'
          buckets[nextTier].push({ ...e, tier: nextTier })
        } else {
          buckets[e.tier].push({ ...e })
        }
      }

      const cmpEntry = (a: TierListEntry, b: TierListEntry): number => {
        const ia = itemMap.get(itemKey(a.workKind, a.workId))
        const ib = itemMap.get(itemKey(b.workKind, b.workId))
        if (rule === 'within_title') {
          return (ia?.title ?? '').localeCompare(ib?.title ?? '', 'zh-CN')
        }
        if (rule === 'within_date') {
          const da = ia?.releaseDate ?? ''
          const db = ib?.releaseDate ?? ''
          if (da !== db) return db.localeCompare(da)
          return (ia?.title ?? '').localeCompare(ib?.title ?? '', 'zh-CN')
        }
        const sa = ia?.personalScore ?? -1
        const sb = ib?.personalScore ?? -1
        if (sb !== sa) return sb - sa
        return (ia?.title ?? '').localeCompare(ib?.title ?? '', 'zh-CN')
      }

      const updatedAll: TierListEntry[] = []
      for (const def of TIER_DEFS) {
        const list = buckets[def.tier]
        list.sort(cmpEntry)
        list.forEach((e, idx) => {
          updatedAll.push({ ...e, tier: def.tier, sortOrder: idx })
        })
      }

      setTierEntries(updatedAll)
      await saveTierBatchOps(
        updatedAll.map((e) => ({
          workKind: e.workKind,
          workId: e.workId,
          tier: e.tier,
          sortOrder: e.sortOrder
        }))
      )
      const labelMap: Record<string, string> = {
        within_score: '已按个人评分完成各档位内批量重排',
        within_date: '已按发行日期完成各档位内批量重排',
        within_title: '已按标题顺序完成各档位内批量重排',
        regrade_score: '已按个人评分完成全榜重新分档与排序'
      }
      flashTierNotice(labelMap[rule] ?? '已完成批量重排')
    } catch (err) {
      flashTierNotice(`批量重排失败：${err instanceof Error ? err.message : String(err)}`, 4000)
    } finally {
      setBatchTierBusy(false)
    }
  }

  const persistTierOrderRef = useRef(persistTierOrder)
  useEffect(() => {
    persistTierOrderRef.current = persistTierOrder
  }, [persistTierOrder])

  const isTrackingPointer = pointerDrag !== null

  useEffect(() => {
    if (!isTrackingPointer) return
    const handleMove = (e: PointerEvent) => {
      const cur = pointerDragRef.current
      if (!cur) return
      const dist = Math.hypot(e.clientX - cur.startX, e.clientY - cur.startY)
      const isNowDragging = cur.dragging || dist >= 5
      const nextState = {
        ...cur,
        curX: e.clientX,
        curY: e.clientY,
        dragging: isNowDragging
      }
      pointerDragRef.current = nextState
      setPointerDrag(nextState)
      if (!isNowDragging) return

      const els = document.elementsFromPoint(e.clientX, e.clientY)
      let hitTier: TierRank | 'pool' | null = null
      let hitBeforeKey: string | null = null
      for (const el of els) {
        if (!hitBeforeKey) {
          const cardAttr = el.getAttribute('data-tier-card-key')
          if (cardAttr) hitBeforeKey = cardAttr
        }
        if (!hitTier) {
          const laneAttr = el.getAttribute('data-tier-lane')
          if (
            laneAttr === 'S' ||
            laneAttr === 'A' ||
            laneAttr === 'B' ||
            laneAttr === 'C' ||
            laneAttr === 'D'
          ) {
            hitTier = laneAttr
          } else if (el.getAttribute('data-tier-pool') === '1') {
            hitTier = 'pool'
          }
        }
      }
      dragOverTierRef.current = hitTier
      dragOverBeforeKeyRef.current = hitBeforeKey
      setDragOverTier(hitTier)
      setDragOverBeforeKey(hitBeforeKey)
    }

    const finishDrag = (cancelled: boolean) => {
      const current = pointerDragRef.current
      const targetTier = dragOverTierRef.current
      const beforeKey = dragOverBeforeKeyRef.current
      pointerDragRef.current = null
      setPointerDrag(null)
      setDragOverTier(null)
      setDragOverBeforeKey(null)
      dragOverTierRef.current = null
      dragOverBeforeKeyRef.current = null

      if (!current || !current.dragging) return
      lastDragEndAtRef.current = Date.now()
      if (cancelled || !targetTier) return
      const selfKey = itemKey(current.item.kind, current.item.id)
      if (targetTier === 'pool') {
        if (current.fromTier != null) {
          void persistTierOrderRef.current(null, current.item.kind, current.item.id, null)
        }
      } else {
        const cleanBefore = beforeKey && beforeKey !== selfKey ? beforeKey : null
        void persistTierOrderRef.current(targetTier, current.item.kind, current.item.id, cleanBefore)
      }
    }

    const handleUp = () => finishDrag(false)
    const handleCancel = () => finishDrag(true)

    window.addEventListener('pointermove', handleMove)
    window.addEventListener('pointerup', handleUp)
    window.addEventListener('pointercancel', handleCancel)
    return () => {
      window.removeEventListener('pointermove', handleMove)
      window.removeEventListener('pointerup', handleUp)
      window.removeEventListener('pointercancel', handleCancel)
    }
  }, [isTrackingPointer])

  const startPointerDrag = (
    e: React.PointerEvent,
    item: CuratorItem,
    fromTier: TierRank | null
  ) => {
    if (tierBatchMode) return
    if (e.button !== 0) return
    if ((e.target as HTMLElement).closest('button')) return
    e.preventDefault()
    const nextState = {
      item,
      fromTier,
      startX: e.clientX,
      startY: e.clientY,
      curX: e.clientX,
      curY: e.clientY,
      dragging: false
    }
    pointerDragRef.current = nextState
    setPointerDrag(nextState)
  }

  const handleExportTierPng = async () => {
    const width = 1160
    const pad = 28
    const labelW = 104
    const cardW = 188
    const cardH = 66
    const cardGap = 10
    const cardsPerRow = Math.floor((width - pad * 2 - labelW - 24) / (cardW + cardGap))

    const rowHeights = TIER_DEFS.map((def) => {
      const cnt = tierGroups.map[def.tier].length
      const lines = Math.max(1, Math.ceil(cnt / Math.max(1, cardsPerRow)))
      return Math.max(84, lines * (cardH + cardGap) + 18)
    })
    const headerH = 76
    const footerH = 42
    const totalH = headerH + rowHeights.reduce((a, b) => a + b + 10, 0) + footerH + pad

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = totalH
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    ctx.fillStyle = '#f5f1e8'
    ctx.fillRect(0, 0, width, totalH)

    ctx.fillStyle = '#1f1d1a'
    ctx.font = 'bold 24px "Microsoft YaHei", sans-serif'
    ctx.fillText('AniMeow · 个人 S / A / B / C / D 神作榜', pad, 42)
    ctx.fillStyle = '#6b655c'
    ctx.font = '13px "Microsoft YaHei", sans-serif'
    const todayIso = new Date().toISOString().slice(0, 10)
    ctx.fillText(`共上榜 ${tierGroups.assignedKeys.size} 部作品 · 生成于 ${todayIso}`, pad, 64)

    let y = headerH
    for (let i = 0; i < TIER_DEFS.length; i++) {
      const def = TIER_DEFS[i]
      const h = rowHeights[i]
      const list = tierGroups.map[def.tier]

      ctx.fillStyle = '#fffdf8'
      ctx.strokeStyle = '#d5cec0'
      ctx.lineWidth = 1.5
      ctx.fillRect(pad, y, width - pad * 2, h)
      ctx.strokeRect(pad, y, width - pad * 2, h)

      ctx.fillStyle = def.accentBg
      ctx.fillRect(pad, y, labelW, h)
      ctx.fillStyle = def.accentFg
      ctx.font = 'bold 30px "Microsoft YaHei", sans-serif'
      ctx.textAlign = 'center'
      ctx.fillText(def.tier, pad + labelW / 2, y + h / 2 + 4)
      ctx.font = '11px "Microsoft YaHei", sans-serif'
      ctx.fillText(def.sublabel, pad + labelW / 2, y + h / 2 + 22)
      ctx.textAlign = 'left'

      for (let j = 0; j < list.length; j++) {
        const it = list[j]
        const col = j % Math.max(1, cardsPerRow)
        const row = Math.floor(j / Math.max(1, cardsPerRow))
        const cx = pad + labelW + 12 + col * (cardW + cardGap)
        const cy = y + 10 + row * (cardH + cardGap)

        ctx.fillStyle = '#f8f5ee'
        ctx.strokeStyle = '#ddd6c7'
        ctx.lineWidth = 1
        ctx.fillRect(cx, cy, cardW, cardH)
        ctx.strokeRect(cx, cy, cardW, cardH)

        ctx.fillStyle = spinePaletteColor(it)
        ctx.fillRect(cx + 6, cy + 7, 36, 52)
        const cover = resolveCoverSrc(it.coverUrl, mediaBaseUrl)
        if (cover && cover.startsWith('http://127.0.0.1')) {
          try {
            const img = await new Promise<HTMLImageElement>((resolve, reject) => {
              const el = new Image()
              el.crossOrigin = 'anonymous'
              el.onload = () => resolve(el)
              el.onerror = () => reject(new Error('load fail'))
              el.src = cover
            })
            ctx.drawImage(img, cx + 6, cy + 7, 36, 52)
          } catch {
            // 降级为色块
          }
        }

        ctx.fillStyle = '#1f1d1a'
        ctx.font = 'bold 12.5px "Microsoft YaHei", sans-serif'
        const shortTitle = it.title.length > 10 ? `${it.title.slice(0, 10)}…` : it.title
        ctx.fillText(shortTitle, cx + 50, cy + 28)

        ctx.fillStyle = '#6b655c'
        ctx.font = '11px "Microsoft YaHei", sans-serif'
        const meta = `${formatCarrierLabel(it.kind)} · ${it.personalScore != null ? `${it.personalScore.toFixed(1)}分` : '未评'}`
        ctx.fillText(meta, cx + 50, cy + 48)
      }

      y += h + 10
    }

    ctx.fillStyle = '#8b8479'
    ctx.font = '11.5px "Microsoft YaHei", sans-serif'
    ctx.fillText('追番喵 · AniMeow', pad, totalH - 16)

    const link = document.createElement('a')
    link.download = `AniMeow-TierList-${todayIso}.png`
    link.href = canvas.toDataURL('image/png')
    link.click()
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="sheet-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span className="heading-font" style={{ fontWeight: 700, fontSize: '1.0769rem' }}>
            数据透视年鉴 · Yearbook & Tier List
          </span>
          <div className="segmented-group">
            {(
              [
                ['all', '全载体'],
                ['anime', '动画'],
                ['book', '书籍'],
                ['game', '游戏'],
                ['audio', '音声']
              ] as const
            ).map(([k, lbl]) => (
              <button
                key={k}
                className={`segmented-btn ${carrierFilter === k ? 'active' : ''}`}
                onClick={() => setCarrierFilter(k)}
              >
                {lbl}
              </button>
            ))}
          </div>
          {tierNotice && (
            <span style={{ color: 'var(--positive)', fontSize: '0.9231rem', fontWeight: 600 }}>
              {tierNotice}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <button className="btn" onClick={() => window.print()}>
            打印 / 另存为 PDF 年鉴
          </button>
        </div>
      </div>

      <div style={{ flex: 1, overflowY: 'auto', padding: 18, userSelect: 'text' }}>
        <div style={{ maxWidth: 1180, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          {auxError && (
            <div className="card-box" style={{ borderColor: 'var(--danger)', color: 'var(--danger)' }}>
              部分本地统计数据加载失败：{auxError}
            </div>
          )}

          {/* 1. 顶部 KPI 概览条 */}
          <section
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))',
              gap: 10
            }}
          >
            <div className="card-box" style={{ padding: 12 }}>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>当前范围藏品总数</div>
              <div className="mono" style={{ fontSize: '1.6923rem', fontWeight: 700, marginTop: 4 }}>
                {metrics.total}
              </div>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                已完成归档 {metrics.completed} 部
              </div>
            </div>

            <div className="card-box" style={{ padding: 12 }}>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>个人平均评分</div>
              <div className="mono" style={{ fontSize: '1.6923rem', fontWeight: 700, marginTop: 4, color: 'var(--accent)' }}>
                {metrics.avgPersonal != null ? metrics.avgPersonal.toFixed(2) : '—'}
              </div>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                已评分作品 {metrics.ratedCount} 部
              </div>
            </div>

            <div className="card-box" style={{ padding: 12 }}>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>相对外部均分平均分差 (Δ)</div>
              <div
                className="mono"
                style={{
                  fontSize: '1.6923rem',
                  fontWeight: 700,
                  marginTop: 4,
                  color:
                    metrics.avgDelta == null
                      ? 'var(--text-primary)'
                      : metrics.avgDelta >= 0
                        ? 'var(--positive)'
                        : 'var(--danger)'
                }}
              >
                {metrics.avgDelta != null
                  ? `${metrics.avgDelta > 0 ? '+' : ''}${metrics.avgDelta.toFixed(2)}`
                  : '—'}
              </div>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                {metrics.avgDelta == null
                  ? '暂无双评分对照'
                  : metrics.avgDelta >= 0.2
                    ? '整体偏宽容/热爱'
                    : metrics.avgDelta <= -0.2
                      ? '整体偏严苛/毒舌'
                      : '与社区均分接近'}
              </div>
            </div>

            <div className="card-box" style={{ padding: 12 }}>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>累计消耗进度</div>
              <div className="mono" style={{ fontSize: '1.3846rem', fontWeight: 700, marginTop: 6 }}>
                {metrics.totalWatchedEpisodes} 集 · {Math.round(metrics.totalGameMinutes / 60)}h 游戏
              </div>
              <div style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                音声 {metrics.totalAudioMinutes}m · 伴侣计时 {formatDurationMinutes(metrics.sessionSeconds)}
              </div>
            </div>
          </section>

          {/* 2. 评分直方图 + 分差散点图 */}
          <section
            style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.25fr)',
              gap: 14
            }}
          >
            {/* 个人评分分布柱状图 */}
            <div className="card-box" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700 }}>
                  个人评分分布（1–10 分）
                </div>
                <span className="badge mono">样本 {metrics.ratedCount}</span>
              </div>

              {metrics.ratedCount === 0 ? (
                <div style={{ padding: 28, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
                  当前筛选范围内尚无已评分作品。
                </div>
              ) : (
                <div
                  style={{
                    height: 190,
                    display: 'grid',
                    gridTemplateColumns: 'repeat(10, 1fr)',
                    gap: 6,
                    alignItems: 'end',
                    padding: '16px 4px 4px'
                  }}
                >
                  {scoreBuckets.counts.map((b) => {
                    const pct = Math.round((b.count / scoreBuckets.maxCount) * 100)
                    return (
                      <div
                        key={b.bucket}
                        style={{
                          height: '100%',
                          display: 'flex',
                          flexDirection: 'column',
                          justifyContent: 'flex-end',
                          alignItems: 'center',
                          gap: 4
                        }}
                        title={`${b.label}：${b.count} 部`}
                      >
                        <span className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-secondary)' }}>
                          {b.count > 0 ? b.count : ''}
                        </span>
                        <div
                          style={{
                            width: '100%',
                            height: `${Math.max(b.count > 0 ? 8 : 2, pct)}%`,
                            background: b.count > 0 ? 'var(--accent)' : 'var(--border-subtle)',
                            opacity: b.count > 0 ? 0.88 : 0.35,
                            borderRadius: 'var(--radius-sm)'
                          }}
                        />
                        <span className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                          {b.bucket}
                        </span>
                      </div>
                    )
                  })}
                </div>
              )}

              {/* 偏爱 / 严苛 Top 5 */}
              {(scatterData.mostLoved.length > 0 || scatterData.mostStrict.length > 0) && (
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr',
                    gap: 8,
                    paddingTop: 8,
                    borderTop: '1px solid var(--border-subtle)',
                    fontSize: '0.8846rem'
                  }}
                >
                  <div>
                    <div style={{ fontWeight: 700, color: 'var(--positive)', marginBottom: 4 }}>
                      最偏爱 Top 5（高于均分）
                    </div>
                    {scatterData.mostLoved.length === 0 ? (
                      <div style={{ color: 'var(--text-muted)' }}>暂无正分差条目</div>
                    ) : (
                      scatterData.mostLoved.map((it) => (
                        <div
                          key={itemKey(it.kind, it.id)}
                          onClick={() => onSelectWork(it.kind, it.id)}
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            gap: 6,
                            padding: '2px 0',
                            cursor: 'pointer'
                          }}
                          title={`点击检视《${it.title}》`}
                        >
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {it.title}
                          </span>
                          <span className="mono" style={{ color: 'var(--positive)', fontWeight: 700, flexShrink: 0 }}>
                            +{it.scoreDelta!.toFixed(1)}
                          </span>
                        </div>
                      ))
                    )}
                  </div>

                  <div>
                    <div style={{ fontWeight: 700, color: 'var(--danger)', marginBottom: 4 }}>
                      最严苛 Top 5（低于均分）
                    </div>
                    {scatterData.mostStrict.length === 0 ? (
                      <div style={{ color: 'var(--text-muted)' }}>暂无负分差条目</div>
                    ) : (
                      scatterData.mostStrict.map((it) => (
                        <div
                          key={itemKey(it.kind, it.id)}
                          onClick={() => onSelectWork(it.kind, it.id)}
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            gap: 6,
                            padding: '2px 0',
                            cursor: 'pointer'
                          }}
                          title={`点击检视《${it.title}》`}
                        >
                          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {it.title}
                          </span>
                          <span className="mono" style={{ color: 'var(--danger)', fontWeight: 700, flexShrink: 0 }}>
                            {it.scoreDelta!.toFixed(1)}
                          </span>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>

            {/* 个人评分 vs 外部均分分差散点图 */}
            <div className="card-box" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700 }}>
                    个人分 vs 外部均分 · 分差散点图 (Δ)
                  </div>
                  <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                    对角线之上为高于均分（绿点），之下为低于均分（红点）；点击点位查看作品详情
                  </div>
                </div>
                <span className="badge mono">有效点位 {scatterData.points.length}</span>
              </div>

              {scatterData.points.length === 0 ? (
                <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
                  需要作品同时具备「个人评分」与「外部均分」才能绘制分差散点图。
                </div>
              ) : (
                <>
                  <svg
                    viewBox="0 0 420 230"
                    style={{
                      width: '100%',
                      height: 230,
                      background: 'var(--bg-canvas)',
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-sm)'
                    }}
                  >
                    {/* 网格与刻度 */}
                    {[2, 4, 6, 8, 10].map((v) => {
                      const x = 40 + (v / 10) * 355
                      const y = 200 - (v / 10) * 180
                      return (
                        <g key={v}>
                          <line
                            x1={x}
                            y1={20}
                            x2={x}
                            y2={200}
                            stroke="var(--border-subtle)"
                            strokeDasharray="2 3"
                          />
                          <line
                            x1={40}
                            y1={y}
                            x2={395}
                            y2={y}
                            stroke="var(--border-subtle)"
                            strokeDasharray="2 3"
                          />
                          <text x={x} y={215} textAnchor="middle" fill="var(--text-muted)" fontSize="9.5">
                            {v}
                          </text>
                          <text x={32} y={y + 3} textAnchor="end" fill="var(--text-muted)" fontSize="9.5">
                            {v}
                          </text>
                        </g>
                      )
                    })}
                    {/* 坐标轴 */}
                    <line x1={40} y1={200} x2={395} y2={200} stroke="var(--border-strong)" />
                    <line x1={40} y1={20} x2={40} y2={200} stroke="var(--border-strong)" />
                    {/* y = x 对角基准线 */}
                    <line
                      x1={40}
                      y1={200}
                      x2={395}
                      y2={20}
                      stroke="var(--text-muted)"
                      strokeWidth="1.2"
                      strokeDasharray="4 4"
                    />
                    <text x={392} y={226} textAnchor="end" fill="var(--text-muted)" fontSize="9.5">
                      外部均分 →
                    </text>
                    <text x={44} y={14} fill="var(--text-muted)" fontSize="9.5">
                      ↑ 个人评分
                    </text>

                    {/* 散点 */}
                    {scatterData.points.map((it) => {
                      const k = itemKey(it.kind, it.id)
                      const pub = Math.max(0, Math.min(10, it.publicScore!))
                      const per = Math.max(0, Math.min(10, it.personalScore!))
                      const cx = 40 + (pub / 10) * 355
                      const cy = 200 - (per / 10) * 180
                      const isHovered = hoveredScatterKey === k
                      const fill =
                        (it.scoreDelta ?? 0) > 0.3
                          ? 'var(--positive)'
                          : (it.scoreDelta ?? 0) < -0.3
                            ? 'var(--danger)'
                            : 'var(--accent)'
                      return (
                        <circle
                          key={k}
                          cx={cx}
                          cy={cy}
                          r={isHovered ? 6 : 4}
                          fill={fill}
                          fillOpacity={isHovered ? 1 : 0.78}
                          stroke={isHovered ? 'var(--text-primary)' : 'none'}
                          strokeWidth={1.5}
                          style={{ cursor: 'pointer' }}
                          onMouseEnter={() => setHoveredScatterKey(k)}
                          onMouseLeave={() => setHoveredScatterKey(null)}
                          onClick={() => onSelectWork(it.kind, it.id)}
                        />
                      )
                    })}
                  </svg>
                  <div style={{ minHeight: 20, fontSize: '0.9231rem', color: 'var(--text-secondary)' }}>
                    {hoveredScatterItem ? (
                      <span>
                        <strong className="heading-font" style={{ color: 'var(--text-primary)' }}>
                          《{hoveredScatterItem.title}》
                        </strong>{' '}
                        · 个人 <span className="mono">{hoveredScatterItem.personalScore?.toFixed(1)}</span> / 均分{' '}
                        <span className="mono">{hoveredScatterItem.publicScore?.toFixed(1)}</span> · 分差{' '}
                        <span className="mono" style={{ fontWeight: 700 }}>
                          {(hoveredScatterItem.scoreDelta ?? 0) > 0 ? '+' : ''}
                          {hoveredScatterItem.scoreDelta?.toFixed(1)}
                        </span>
                      </span>
                    ) : (
                      <span style={{ color: 'var(--text-muted)' }}>
                        将鼠标移到任意散点可查看作品与分差，点击即可查看作品详情。
                      </span>
                    )}
                  </div>
                </>
              )}
            </div>
          </section>

          {/* 3. 月度陪伴与游玩时长热力日历 + 藏品发售年份谱系 */}
          <section
            style={{
              display: 'grid',
              gridTemplateColumns: 'minmax(0, 1.45fr) minmax(0, 1fr)',
              gap: 14
            }}
          >
            <div className="card-box" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700 }}>
                    近 26 周游玩时长热力日历
                  </div>
                  <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                    基于本机实际游玩记录统计（共 {sessions.length} 次会话）
                  </div>
                </div>
                <span className="badge mono">
                  {loadingAux ? '读取中...' : formatDurationMinutes(metrics.sessionSeconds)}
                </span>
              </div>

              <div style={{ overflowX: 'auto', paddingBottom: 4 }}>
                <div style={{ display: 'inline-flex', gap: 3 }}>
                  {heatmap.weeks.map((week, wIdx) => (
                    <div key={wIdx} style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                      {week.map((day) => {
                        const opacities = [0.1, 0.35, 0.58, 0.8, 1]
                        return (
                          <div
                            key={day.date}
                            title={
                              day.seconds > 0
                                ? `${day.date} · 累计 ${formatDurationMinutes(day.seconds)}`
                                : `${day.date} · 无计时记录`
                            }
                            style={{
                              width: 13,
                              height: 13,
                              borderRadius: 'var(--radius-sm)',
                              background: day.level === 0 ? 'var(--border-subtle)' : 'var(--accent)',
                              opacity: opacities[day.level],
                              border: '1px solid rgba(128,128,128,0.12)'
                            }}
                          />
                        )
                      })}
                    </div>
                  ))}
                </div>
              </div>

              {/* 近 6 个月时长条 */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 8, paddingTop: 6, borderTop: '1px solid var(--border-subtle)' }}>
                {heatmap.recentMonths.map((m) => (
                  <div key={m.ym} style={{ fontSize: '0.8462rem' }}>
                    <div className="mono" style={{ color: 'var(--text-muted)' }}>{m.ym}</div>
                    <div
                      style={{
                        height: 6,
                        marginTop: 4,
                        background: 'var(--bg-canvas)',
                        border: '1px solid var(--border-subtle)'
                      }}
                    >
                      <div
                        style={{
                          width: `${m.seconds > 0 ? Math.max(8, Math.round((m.seconds / heatmap.maxMonthSec) * 100)) : 0}%`,
                          height: '100%',
                          background: 'var(--accent)'
                        }}
                      />
                    </div>
                    <div className="mono" style={{ marginTop: 2, color: 'var(--text-secondary)' }}>
                      {m.seconds > 0 ? formatDurationMinutes(m.seconds) : '0 分钟'}
                    </div>
                  </div>
                ))}
              </div>

              {sessions.length === 0 && !loadingAux && (
                <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                  当前尚无自动游玩计时会话；可在「03 · 进程伴侣」绑定 Galgame / 游戏 <code>.exe</code> 启动后自动积累热力记录。
                </div>
              )}
            </div>

            <div className="card-box" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700 }}>
                藏品放送 / 发售年份谱系（前 12 年份）
              </div>
              {releaseDecades.top.length === 0 ? (
                <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>暂无藏品数据。</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {releaseDecades.top.map(([year, cnt]) => (
                    <div key={year} style={{ display: 'grid', gridTemplateColumns: '64px 1fr 34px', gap: 8, alignItems: 'center' }}>
                      <span className="mono" style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
                        {year}
                      </span>
                      <div
                        style={{
                          height: 11,
                          background: 'var(--bg-canvas)',
                          border: '1px solid var(--border-subtle)',
                          borderRadius: 'var(--radius-sm)',
                          overflow: 'hidden'
                        }}
                      >
                        <div
                          style={{
                            width: `${Math.round((cnt / releaseDecades.maxCnt) * 100)}%`,
                            height: '100%',
                            background: 'var(--accent)',
                            opacity: 0.85
                          }}
                        />
                      </div>
                      <span className="mono" style={{ fontSize: '0.8846rem', textAlign: 'right' }}>
                        {cnt}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </section>

          {/* 4. 可拖拽 S / A / B / C / D 个人神作榜生成器 */}
          <section className="card-box" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
              <div>
                <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
                  个人 S / A / B / C / D 神作榜
                </div>
                <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginTop: 2 }}>
                  右侧待排位池支持一键点选 S/A/B/C/D 直入榜单，也支持短距离拖拽到左侧任意阶梯与同行重排。
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <span className="badge mono">已上榜 {tierGroups.assignedKeys.size} 部</span>
                <div className="view-seg" role="group" aria-label="榜单卡片密度">
                  <button
                    type="button"
                    className={`view-seg-btn ${tierDensity === 'compact' ? 'active' : ''}`}
                    onClick={() => setTierDensity('compact')}
                    title="紧凑封面砖模式：适合作品多时一屏总览"
                  >
                    紧凑封面
                  </button>
                  <button
                    type="button"
                    className={`view-seg-btn ${tierDensity === 'standard' ? 'active' : ''}`}
                    onClick={() => setTierDensity('standard')}
                    title="标准卡片模式：显示完整标题与排序微调按钮"
                  >
                    标准卡片
                  </button>
                </div>
                <button
                  type="button"
                  className={`btn ${tierBatchMode ? 'btn-primary' : ''}`}
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  onClick={() => {
                    setTierBatchMode((prev) => {
                      if (prev) setSelectedTierKeys(new Set())
                      return !prev
                    })
                  }}
                  title="开启后可勾选多部已排或待排作品，批量移档或批量移出榜单"
                >
                  {tierBatchMode ? `退出批量 (${selectedTierKeys.size})` : '批量管理'}
                </button>
                <StudioSelect
                  className="studio-select-inline"
                  value=""
                  disabled={tierGroups.assignedKeys.size === 0 || batchTierBusy}
                  placeholder={batchTierBusy ? '重排中...' : '批量重排'}
                  ariaLabel="批量重排个人神作榜"
                  minMenuWidth={210}
                  maxMenuWidth={280}
                  onChange={(val) => {
                    if (val) void handleBatchReorderRule(val)
                  }}
                  options={[
                    { value: 'within_score', label: '档位内按个人评分重排' },
                    { value: 'within_date', label: '档位内按发行日期重排' },
                    { value: 'within_title', label: '档位内按标题顺序重排' },
                    { value: 'regrade_score', label: '全榜按个人评分重新分档' },
                    { value: 'clear_all', label: '清空全部已上榜排位' }
                  ]}
                />
                <button
                  className="btn"
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  disabled={ratedUnassignedCount === 0 || autoFillingTiers}
                  onClick={() => void handleAutoFillByScore()}
                  title="仅针对右侧待排位池中已有个人评分的作品自动填入（≥9 S / ≥8 A / ≥7 B / ≥6 C / <6 D），不覆盖已手动排位的作品"
                >
                  {autoFillingTiers ? (
                    <LoadingSpinner size="sm" inline label="初排中..." />
                  ) : (
                    `按评分初排 (${ratedUnassignedCount})`
                  )}
                </button>
                <button
                  className="btn btn-primary"
                  style={{ padding: '4px 10px', fontSize: '0.9231rem' }}
                  disabled={tierGroups.assignedKeys.size === 0}
                  onClick={() => void handleExportTierPng()}
                  title="将当前 S–D 神作榜渲染为高清 PNG 长图并保存"
                >
                  导出 PNG 长图
                </button>
              </div>
            </div>

            {tierBatchMode && (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: 8,
                  padding: '8px 10px',
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg-canvas)',
                  border: '1px solid var(--accent)'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                  <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>
                    已选 {selectedTierKeys.size} 部作品
                  </span>
                  <button
                    type="button"
                    className="btn"
                    style={{ height: 24, padding: '0 8px', fontSize: '0.8462rem' }}
                    disabled={tierGroups.assignedKeys.size === 0}
                    onClick={() => setSelectedTierKeys(new Set(tierGroups.assignedKeys))}
                  >
                    全选已上榜
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost"
                    style={{ height: 24, padding: '0 8px', fontSize: '0.8462rem' }}
                    disabled={selectedTierKeys.size === 0}
                    onClick={() => setSelectedTierKeys(new Set())}
                  >
                    清空选择
                  </button>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>批量移至：</span>
                  {TIER_DEFS.map((td) => (
                    <button
                      key={td.tier}
                      type="button"
                      className="btn"
                      disabled={selectedTierKeys.size === 0 || batchTierBusy}
                      style={{
                        height: 24,
                        padding: '0 9px',
                        fontSize: '0.8462rem',
                        fontWeight: 700,
                        borderColor: td.accentBg,
                        color: td.accentBg
                      }}
                      onClick={() => void handleBatchMoveSelected(td.tier)}
                    >
                      {td.tier} 级
                    </button>
                  ))}
                  <button
                    type="button"
                    className="btn"
                    disabled={selectedTierKeys.size === 0 || batchTierBusy}
                    style={{ height: 24, padding: '0 8px', fontSize: '0.8462rem', color: 'var(--danger)' }}
                    onClick={() => void handleBatchMoveSelected(null)}
                  >
                    批量移出榜单
                  </button>
                </div>
              </div>
            )}

            {/* 宽屏双栏工作台：左侧 S-D 阶梯 + 右侧固定待排位池，告别上下长距离拖拽 */}
            <div className="tier-workbench">
              {/* 5 条阶梯行 */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, userSelect: 'none', minWidth: 0 }}>
                {TIER_DEFS.map((def) => {
                  const rowItems = tierGroups.map[def.tier]
                  const isOver = Boolean(pointerDrag?.dragging) && dragOverTier === def.tier
                  const isCollapsed = collapsedTiers.has(def.tier) && rowItems.length > 0 && !isOver
                  return (
                    <div
                      key={def.tier}
                      data-tier-lane={def.tier}
                      style={{
                        display: 'grid',
                        gridTemplateColumns: '82px 1fr',
                        minHeight: tierDensity === 'compact' ? 84 : 74,
                        border: `1.5px solid ${isOver ? 'var(--accent)' : 'var(--border-strong)'}`,
                        borderRadius: 'var(--radius-sm)',
                        background: isOver ? 'var(--accent-soft)' : 'var(--bg-canvas)',
                        overflow: 'hidden',
                        transition: 'border-color 120ms ease, background 120ms ease'
                      }}
                    >
                      <button
                        type="button"
                        onClick={() => toggleTierCollapse(def.tier)}
                        title={
                          rowItems.length > 0
                            ? `${isCollapsed ? '展开' : '折叠'} ${def.tier} 级（共 ${rowItems.length} 部）`
                            : `${def.tier} 级 · ${def.sublabel}`
                        }
                        style={{
                          background: def.accentBg,
                          color: def.accentFg,
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'center',
                          justifyContent: 'center',
                          padding: 6,
                          textAlign: 'center',
                          border: 'none',
                          cursor: rowItems.length > 0 ? 'pointer' : 'default'
                        }}
                      >
                        <span className="heading-font" style={{ fontSize: '1.6923rem', fontWeight: 800, lineHeight: 1 }}>
                          {def.label}
                        </span>
                        <span style={{ fontSize: '0.8077rem', opacity: 0.9, marginTop: 3 }}>{def.sublabel}</span>
                        <span className="mono" style={{ fontSize: '0.7692rem', opacity: 0.85, marginTop: 3 }}>
                          {rowItems.length} 部{isCollapsed ? ' ▸' : ''}
                        </span>
                      </button>

                      <div
                        style={{
                          padding: tierDensity === 'compact' ? 7 : 8,
                          display: 'flex',
                          flexWrap: 'wrap',
                          gap: tierDensity === 'compact' ? 6 : 8,
                          alignItems: 'center',
                          alignContent: 'flex-start'
                        }}
                      >
                        {rowItems.length === 0 ? (
                          <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                            从右侧点击「{def.tier}」或直接拖拽至此归入 {def.tier} 级…
                          </span>
                        ) : isCollapsed ? (
                          <button
                            type="button"
                            className="btn"
                            style={{ fontSize: '0.8846rem', padding: '3px 10px' }}
                            onClick={() => toggleTierCollapse(def.tier)}
                          >
                            已折叠 {rowItems.length} 部作品（点击展开）
                          </button>
                        ) : (
                          rowItems.map((it, idx) => {
                            const k = itemKey(it.kind, it.id)
                            const isBeingDragged =
                              Boolean(pointerDrag?.dragging) &&
                              pointerDrag?.item.kind === it.kind &&
                              pointerDrag?.item.id === it.id
                            const isInsertTarget =
                              Boolean(pointerDrag?.dragging) &&
                              dragOverBeforeKey === k &&
                              !isBeingDragged
                            const isBatchSelected = tierBatchMode && selectedTierKeys.has(k)

                            if (tierDensity === 'compact') {
                              return (
                                <div
                                  key={k}
                                  data-tier-card-key={k}
                                  className={`tier-compact-tile ${isBatchSelected ? 'is-selected' : ''}`}
                                  onPointerDown={(e) => startPointerDrag(e, it, def.tier)}
                                  onClick={() => {
                                    if (tierBatchMode) toggleSelectTierKey(k)
                                  }}
                                  style={{
                                    border: isBatchSelected
                                      ? '2px solid var(--accent)'
                                      : isInsertTarget
                                        ? '1.5px solid var(--accent)'
                                        : '1px solid var(--border-subtle)',
                                    boxShadow: isInsertTarget ? '-3px 0 0 var(--accent)' : undefined,
                                    opacity: isBeingDragged ? 0.35 : 1,
                                    cursor: tierBatchMode
                                      ? 'pointer'
                                      : pointerDrag?.dragging
                                        ? 'grabbing'
                                        : 'grab',
                                    background: spinePaletteColor(it)
                                  }}
                                  title={
                                    tierBatchMode
                                      ? `点击勾选/取消勾选《${it.title}》`
                                      : `${it.title}（${formatCarrierLabel(it.kind)} · ${it.personalScore != null ? `${it.personalScore.toFixed(1)}分` : '未评'}）\n按住封面拖拽可换档或调序`
                                  }
                                >
                                  <CoverImage
                                    coverUrl={it.coverUrl}
                                    mediaBaseUrl={mediaBaseUrl}
                                    alt={it.title}
                                    style={{ width: '100%', height: '100%', objectFit: 'cover', pointerEvents: 'none' }}
                                    fallback={
                                      <div className="tier-compact-fallback">{it.title.slice(0, 6)}</div>
                                    }
                                  />
                                  {tierBatchMode && (
                                    <span className={`tier-batch-check ${isBatchSelected ? 'is-checked' : ''}`}>
                                      {isBatchSelected ? '✓' : ''}
                                    </span>
                                  )}
                                  {it.personalScore != null && !tierBatchMode && (
                                    <span className="tier-compact-score mono">
                                      {it.personalScore.toFixed(1)}
                                    </span>
                                  )}
                                  <div className="tier-compact-title">{it.title}</div>
                                  {!tierBatchMode && (
                                    <div className="tier-compact-actions">
                                      <button
                                        type="button"
                                        disabled={idx === 0}
                                        onClick={() => void handleMoveWithinTier(def.tier, it.kind, it.id, -1)}
                                        title="左移一位"
                                      >
                                        ‹
                                      </button>
                                      <button
                                        type="button"
                                        onClick={() => onSelectWork(it.kind, it.id)}
                                        title="查看详情"
                                      >
                                        详
                                      </button>
                                      <button
                                        type="button"
                                        disabled={idx === rowItems.length - 1}
                                        onClick={() => void handleMoveWithinTier(def.tier, it.kind, it.id, 1)}
                                        title="右移一位"
                                      >
                                        ›
                                      </button>
                                      <button
                                        type="button"
                                        className="danger"
                                        onClick={() => void persistTierOrder(null, it.kind, it.id, null)}
                                        title="移出榜单"
                                      >
                                        ×
                                      </button>
                                    </div>
                                  )}
                                </div>
                              )
                            }

                            return (
                              <div
                                key={k}
                                data-tier-card-key={k}
                                onPointerDown={(e) => startPointerDrag(e, it, def.tier)}
                                onClick={() => {
                                  if (tierBatchMode) toggleSelectTierKey(k)
                                }}
                                style={{
                                  width: 176,
                                  display: 'flex',
                                  gap: 7,
                                  padding: 5,
                                  borderRadius: 'var(--radius-sm)',
                                  background: isBatchSelected ? 'var(--accent-soft)' : 'var(--bg-elevated)',
                                  border: isBatchSelected
                                    ? '1.5px solid var(--accent)'
                                    : isInsertTarget
                                      ? '1.5px solid var(--accent)'
                                      : '1px solid var(--border-subtle)',
                                  boxShadow: isInsertTarget ? '-3px 0 0 var(--accent)' : undefined,
                                  opacity: isBeingDragged ? 0.35 : 1,
                                  cursor: tierBatchMode
                                    ? 'pointer'
                                    : pointerDrag?.dragging
                                      ? 'grabbing'
                                      : 'grab',
                                  alignItems: 'center',
                                  touchAction: 'none'
                                }}
                              >
                                <div
                                  onClick={() => {
                                    if (tierBatchMode) return
                                    if (Date.now() - lastDragEndAtRef.current < 180) return
                                    onSelectWork(it.kind, it.id)
                                  }}
                                  style={{
                                    width: 34,
                                    height: 48,
                                    borderRadius: 'var(--radius-sm)',
                                    overflow: 'hidden',
                                    background: spinePaletteColor(it),
                                    flexShrink: 0,
                                    cursor: 'pointer'
                                  }}
                                  title={`点击查看《${it.title}》详情`}
                                >
                                  <CoverImage
                                    coverUrl={it.coverUrl}
                                    mediaBaseUrl={mediaBaseUrl}
                                    alt={it.title}
                                    style={{ width: '100%', height: '100%', objectFit: 'cover', pointerEvents: 'none' }}
                                    fallback={
                                      <div style={{ padding: 3, color: '#fff', fontSize: '0.7308rem', fontWeight: 700 }}>
                                        {it.title.slice(0, 4)}
                                      </div>
                                    }
                                  />
                                </div>
                                <div style={{ minWidth: 0, flex: 1 }}>
                                  <div
                                    className="heading-font"
                                    style={{
                                      fontSize: '0.9231rem',
                                      fontWeight: 700,
                                      whiteSpace: 'nowrap',
                                      overflow: 'hidden',
                                      textOverflow: 'ellipsis'
                                    }}
                                    title={it.title}
                                  >
                                    {it.title}
                                  </div>
                                  <div className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                                    {formatCarrierLabel(it.kind)} ·{' '}
                                    {it.personalScore != null ? `${it.personalScore.toFixed(1)}分` : '未评'}
                                  </div>
                                  <div
                                    style={{ display: 'flex', gap: 3, marginTop: 3 }}
                                    onPointerDown={(e) => e.stopPropagation()}
                                  >
                                    <button
                                      className="step-btn"
                                      style={{ width: 18, height: 18, fontSize: '0.7692rem' }}
                                      disabled={idx === 0}
                                      onClick={() => void handleMoveWithinTier(def.tier, it.kind, it.id, -1)}
                                      title="左移一位"
                                    >
                                      ‹
                                    </button>
                                    <button
                                      className="step-btn"
                                      style={{ width: 18, height: 18, fontSize: '0.7692rem' }}
                                      disabled={idx === rowItems.length - 1}
                                      onClick={() => void handleMoveWithinTier(def.tier, it.kind, it.id, 1)}
                                      title="右移一位"
                                    >
                                      ›
                                    </button>
                                    <button
                                      className="step-btn"
                                      style={{ width: 18, height: 18, fontSize: '0.7692rem', color: 'var(--danger)' }}
                                      onClick={() => void persistTierOrder(null, it.kind, it.id, null)}
                                      title="移出神作榜"
                                    >
                                      ×
                                    </button>
                                  </div>
                                </div>
                              </div>
                            )
                          })
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>

              {/* 右侧固定待排位藏品池：独立滚动 + 筛选排序 + 一键 S/A/B/C/D 归档 */}
              <div
                data-tier-pool="1"
                className="tier-pool-pane"
                style={{
                  background:
                    Boolean(pointerDrag?.dragging) && dragOverTier === 'pool'
                      ? 'var(--accent-soft)'
                      : 'var(--bg-panel)',
                  border: `1.5px dashed ${
                    Boolean(pointerDrag?.dragging) && dragOverTier === 'pool'
                      ? 'var(--accent)'
                      : 'var(--border-subtle)'
                  }`
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 7, marginBottom: 8 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                    <span style={{ fontWeight: 700, fontSize: '0.9615rem' }}>
                      待排位池（{poolItems.length}）
                    </span>
                    <StudioSelect
                      className="studio-select-inline"
                      value={poolSort}
                      onChange={(val) =>
                        setPoolSort(val as 'score_desc' | 'score_asc' | 'title')
                      }
                      title="待排位作品排序方式"
                      ariaLabel="待排位作品排序方式"
                      minMenuWidth={128}
                      maxMenuWidth={168}
                      options={[
                        { value: 'score_desc', label: '评分 高→低' },
                        { value: 'score_asc', label: '评分 低→高' },
                        { value: 'title', label: '标题排序' }
                      ]}
                    />
                  </div>

                  <input
                    className="inline-input"
                    style={{ width: '100%' }}
                    value={tierSearch}
                    onChange={(e) => setTierSearch(e.target.value)}
                    placeholder="检索待排位作品 / 系列名..."
                  />

                  <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                    {(
                      [
                        ['all', '全部'],
                        ['rated', '仅已评分'],
                        ['completed', '仅已完成']
                      ] as const
                    ).map(([fid, flabel]) => (
                      <button
                        key={fid}
                        type="button"
                        className={`btn ${poolFilter === fid ? 'btn-primary' : ''}`}
                        style={{ padding: '2px 8px', fontSize: '0.8462rem' }}
                        onClick={() => setPoolFilter(fid)}
                      >
                        {flabel}
                      </button>
                    ))}
                  </div>
                </div>

                {poolItems.length === 0 ? (
                  <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', padding: '20px 6px', textAlign: 'center' }}>
                    当前筛选条件下没有待排位藏品。
                  </div>
                ) : (
                  <div className="tier-pool-list">
                    {poolItems.slice(0, poolLimit).map((it) => {
                      const k = itemKey(it.kind, it.id)
                      const isDraggingThis =
                        Boolean(pointerDrag?.dragging) &&
                        pointerDrag?.item.kind === it.kind &&
                        pointerDrag?.item.id === it.id
                      return (
                        <div
                          key={k}
                          className="tier-pool-row"
                          onPointerDown={(e) => startPointerDrag(e, it, null)}
                          onClick={() => {
                            if (tierBatchMode) toggleSelectTierKey(k)
                          }}
                          style={{
                            opacity: isDraggingThis ? 0.35 : 1,
                            cursor: tierBatchMode
                              ? 'pointer'
                              : pointerDrag?.dragging
                                ? 'grabbing'
                                : 'grab',
                            borderColor: tierBatchMode && selectedTierKeys.has(k) ? 'var(--accent)' : undefined,
                            background: tierBatchMode && selectedTierKeys.has(k) ? 'var(--accent-soft)' : undefined
                          }}
                          title={
                            tierBatchMode
                              ? `点击勾选/取消勾选《${it.title}》`
                              : '可直接点右侧 S/A/B/C/D 一键上榜，或按住拖到左侧任意阶梯'
                          }
                        >
                          {tierBatchMode && (
                            <input
                              type="checkbox"
                              checked={selectedTierKeys.has(k)}
                              readOnly
                              style={{ pointerEvents: 'none', flexShrink: 0 }}
                            />
                          )}
                          <div
                            className="tier-pool-thumb"
                            style={{ background: spinePaletteColor(it) }}
                            onClick={(e) => {
                              e.stopPropagation()
                              onSelectWork(it.kind, it.id)
                            }}
                          >
                            <CoverImage
                              coverUrl={it.coverUrl}
                              mediaBaseUrl={mediaBaseUrl}
                              alt={it.title}
                              style={{ width: '100%', height: '100%', objectFit: 'cover', pointerEvents: 'none' }}
                              fallback={<span>{it.title.slice(0, 2)}</span>}
                            />
                          </div>
                          <div style={{ minWidth: 0, flex: 1 }}>
                            <div
                              className="heading-font"
                              style={{
                                fontSize: '0.9231rem',
                                fontWeight: 600,
                                whiteSpace: 'nowrap',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis'
                              }}
                              title={it.title}
                            >
                              {it.title}
                            </div>
                            <div className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                              {formatCarrierLabel(it.kind)}
                              {it.personalScore != null ? ` · ${it.personalScore.toFixed(1)}分` : ' · 未评'}
                            </div>
                          </div>
                          <div
                            className="tier-quick-btns"
                            onPointerDown={(e) => e.stopPropagation()}
                          >
                            {TIER_DEFS.map((td) => (
                              <button
                                key={td.tier}
                                type="button"
                                className="tier-quick-btn"
                                style={{ '--tier-color': td.accentBg } as React.CSSProperties}
                                onClick={() => void persistTierOrder(td.tier, it.kind, it.id, null)}
                                title={`一键归入 ${td.tier} 级（${td.sublabel}）`}
                              >
                                {td.tier}
                              </button>
                            ))}
                          </div>
                        </div>
                      )
                    })}

                    {poolItems.length > poolLimit && (
                      <button
                        type="button"
                        className="btn"
                        style={{ width: '100%', marginTop: 4, fontSize: '0.8846rem' }}
                        onClick={() => setPoolLimit((n) => n + 60)}
                      >
                        加载更多（还有 {poolItems.length - poolLimit} 项）
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>
          </section>
        </div>
      </div>

      {/* 跟随鼠标的实时拖拽幽灵卡片 */}
      {pointerDrag?.dragging && (
        <div
          className="drag-ghost-pill"
          style={{
            position: 'fixed',
            left: pointerDrag.curX + 14,
            top: pointerDrag.curY + 14,
            pointerEvents: 'none',
            zIndex: 9999
          }}
        >
          <span className="badge badge-accent">{formatCarrierLabel(pointerDrag.item.kind)}</span>
          <strong className="heading-font">{pointerDrag.item.title}</strong>
          {dragOverTier && dragOverTier !== 'pool' && (
            <span className="badge mono">→ {dragOverTier} 级</span>
          )}
          {dragOverTier === 'pool' && <span className="badge">→ 移出榜单</span>}
        </div>
      )}
    </div>
  )
}
