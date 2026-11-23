import { useEffect, useMemo, useRef, useState } from 'react'
import {
  classifyStatusLane,
  formatArchiveCode,
  formatItemCarrierLabel,
  spinePaletteColor,
  statusOptionsFor,
  studioApi
} from '../api'
import { CoverImage } from './CoverImage'
import { StudioSelect } from './StudioSelect'
import type { CommandPaletteTab } from './CommandPaletteModal'
import { DESKTOP_SETTINGS_EVENT, loadLocalDesktopSettings } from '../utils/desktopSettings'
import type { CellPatch, CuratorItem, CuratorSubView, DesktopSettings, MediaCarrier } from '../types'

type SortField =
  | 'lastModifiedAt'
  | 'code'
  | 'title'
  | 'kind'
  | 'status'
  | 'progress'
  | 'personalScore'
  | 'publicScore'
  | 'scoreDelta'
  | 'localFileCount'
  | 'releaseDate'

interface CuratorSheetProps {
  items: CuratorItem[]
  mediaBaseUrl: string
  selectedKey: string | null
  subView: CuratorSubView
  searchQuery: string
  onSearchQueryChange: (q: string) => void
  statusFilter: string
  onStatusFilterChange: (v: string) => void
  availableStatuses: [string, number][]
  tagFilter: string
  onTagFilterChange: (v: string) => void
  availableTags: [string, number][]
  ratingFilter: 'all' | 'rated' | 'unrated' | 'high'
  onRatingFilterChange: (v: 'all' | 'rated' | 'unrated' | 'high') => void
  activeSeriesFilter: string | null
  onClearSeriesFilter: () => void
  hasExtraFilters: boolean
  onClearAllFilters: () => void
  onSubViewChange: (view: CuratorSubView) => void
  onSelectItem: (item: CuratorItem) => void
  onOpenEditor?: (item: CuratorItem) => void
  onFocusInCanvas?: (item: CuratorItem) => void
  onOpenReviewStudio: (item: CuratorItem) => void
  onWatchAnimeOnline?: (item: CuratorItem) => void
  onOpenCommandPalette: (initialTab?: CommandPaletteTab) => void
  onStepProgressWithUndo: (item: CuratorItem, deltaSign: 1 | -1) => Promise<void>
  onUpdateCell: (patch: CellPatch) => Promise<void>
  onBatchStatus: (targets: [MediaCarrier, number][], status: string) => Promise<void>
  onBatchSeries: (
    targets: [MediaCarrier, number][],
    seriesName: string,
    seriesRole: string
  ) => Promise<void>
  onSoftDeleteItem: (item: CuratorItem) => Promise<void>
}

export function formatProgressText(item: CuratorItem): string {
  switch (item.kind) {
    case 'anime':
      return item.progressTotal > 0
        ? `${item.progressPrimary} / ${item.progressTotal} 集`
        : `${item.progressPrimary} 集`
    case 'book': {
      const parts: string[] = []
      if (item.bookVolumes != null) parts.push(`${item.bookVolumes} 卷`)
      if (item.bookChapters != null) parts.push(`${item.bookChapters} 话`)
      if (parts.length > 0) return parts.join(' · ')
      return item.progressTotal > 0
        ? `${item.progressPrimary} / ${item.progressTotal}`
        : `${item.progressPrimary}`
    }
    case 'game': {
      const hrs = Math.floor(item.progressPrimary / 60)
      const mins = item.progressPrimary % 60
      return hrs > 0 ? `${hrs}h ${mins}m` : `${mins}m`
    }
    case 'audio': {
      const doneMin = Math.floor(item.progressPrimary / 60)
      const totalMin = Math.floor(item.progressTotal / 60)
      return totalMin > 0 ? `${doneMin} / ${totalMin} min` : `${doneMin} min`
    }
  }
}

function defaultStatusForColumn(kind: MediaCarrier, colKey: string): string {
  if (colKey === 'active') {
    return kind === 'game' ? '在玩' : kind === 'audio' ? '在听' : '在看'
  }
  if (colKey === 'done') {
    return kind === 'game' ? '已玩' : kind === 'audio' ? '已听' : '看完'
  }
  if (colKey === 'planned') {
    return kind === 'game' ? '想玩' : kind === 'audio' ? '想听' : '想看'
  }
  return '搁置'
}

function calcProgressRatio(item: CuratorItem): number | null {
  if ((item.kind === 'anime' || item.kind === 'book' || item.kind === 'audio') && item.progressTotal > 0) {
    return Math.min(1, Math.max(0, item.progressPrimary / item.progressTotal))
  }
  if (classifyStatusLane(item.status) === 'done') return 1
  return null
}

function rowHeightForFontScale(scale: DesktopSettings['fontScale']): number {
  if (scale === 'compact') return 30
  if (scale === 'comfortable') return 44
  return 36
}

const TABLE_HEADER_HEIGHT = 31
const TABLE_OVERSCAN = 12
const CARD_VIEW_PAGE_STEP = 240

export function CuratorSheet({
  items,
  mediaBaseUrl,
  selectedKey,
  subView,
  searchQuery,
  onSearchQueryChange,
  statusFilter,
  onStatusFilterChange,
  availableStatuses,
  tagFilter,
  onTagFilterChange,
  availableTags,
  ratingFilter,
  onRatingFilterChange,
  activeSeriesFilter,
  onClearSeriesFilter,
  hasExtraFilters,
  onClearAllFilters,
  onSubViewChange,
  onSelectItem,
  onOpenEditor,
  onFocusInCanvas,
  onOpenReviewStudio,
  onWatchAnimeOnline,
  onOpenCommandPalette,
  onStepProgressWithUndo,
  onUpdateCell,
  onBatchStatus,
  onBatchSeries,
  onSoftDeleteItem
}: CuratorSheetProps) {
  const [sortField, setSortField] = useState<SortField>('personalScore')
  const [sortAsc, setSortAsc] = useState(false)
  const [checkedKeys, setCheckedKeys] = useState<Set<string>>(new Set())
  const [batchStatusValue, setBatchStatusValue] = useState<string>('在看')
  const [seriesInput, setSeriesInput] = useState<string>('')
  const [seriesRoleInput, setSeriesRoleInput] = useState<string>('正作')
  const [kanbanDrag, setKanbanDrag] = useState<{
    item: CuratorItem
    startX: number
    startY: number
    curX: number
    curY: number
    dragging: boolean
  } | null>(null)
  const [kanbanHoverCol, setKanbanHoverCol] = useState<string | null>(null)
  const kanbanHoverColRef = useRef<string | null>(null)
  const [sheetNotice, setSheetNotice] = useState<string | null>(null)
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number; item: CuratorItem } | null>(null)
  const lastCheckedIndexRef = useRef<number | null>(null)

  // 万行律表视口虚拟滚动状态
  const sheetScrollRef = useRef<HTMLDivElement | null>(null)
  const rafScrollRef = useRef<number | null>(null)
  const [scrollTop, setScrollTop] = useState<number>(0)
  const [viewportHeight, setViewportHeight] = useState<number>(640)
  const [cardDisplayLimit, setCardDisplayLimit] = useState<number>(CARD_VIEW_PAGE_STEP)

  useEffect(() => {
    const el = sheetScrollRef.current
    if (!el) return
    const syncHeight = () => setViewportHeight(el.clientHeight || 640)
    syncHeight()
    const ro = new ResizeObserver(syncHeight)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    setCardDisplayLimit(CARD_VIEW_PAGE_STEP)
  }, [subView, searchQuery, sortField, sortAsc])

  useEffect(() => {
    return () => {
      if (rafScrollRef.current != null) {
        window.cancelAnimationFrame(rafScrollRef.current)
      }
    }
  }, [])

  const handleSheetScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const nextTop = e.currentTarget.scrollTop
    if (rafScrollRef.current != null) return
    rafScrollRef.current = window.requestAnimationFrame(() => {
      rafScrollRef.current = null
      setScrollTop(nextTop)
    })
  }

  useEffect(() => {
    if (!ctxMenu) return
    const close = () => setCtxMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [ctxMenu])

  const sortedItems = useMemo(() => {
    const copy = [...items]
    const dir = sortAsc ? 1 : -1
    copy.sort((a, b) => {
      switch (sortField) {
        case 'lastModifiedAt':
          return ((a.lastModifiedAt || 0) - (b.lastModifiedAt || 0)) * dir || (a.id - b.id) * dir
        case 'code':
          return (a.id - b.id) * dir
        case 'title':
          return a.title.localeCompare(b.title, 'zh-CN') * dir
        case 'kind':
          return a.kind.localeCompare(b.kind) * dir
        case 'status':
          return a.status.localeCompare(b.status, 'zh-CN') * dir
        case 'progress':
          return (a.progressPrimary - b.progressPrimary) * dir
        case 'personalScore':
          return ((a.personalScore ?? -999) - (b.personalScore ?? -999)) * dir
        case 'publicScore':
          return ((a.publicScore ?? -999) - (b.publicScore ?? -999)) * dir
        case 'scoreDelta':
          return ((a.scoreDelta ?? -999) - (b.scoreDelta ?? -999)) * dir
        case 'localFileCount':
          return (a.localFileCount - b.localFileCount) * dir
        case 'releaseDate':
          return a.releaseDate.localeCompare(b.releaseDate) * dir
      }
    })
    return copy
  }, [items, sortField, sortAsc])

  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortAsc((prev) => !prev)
    } else {
      setSortField(field)
      setSortAsc(field === 'title' || field === 'status' || field === 'code')
    }
  }

  const sortIndicator = (field: SortField) => {
    if (sortField !== field) return ''
    return sortAsc ? ' ▲' : ' ▼'
  }

  const rowKey = (item: CuratorItem) => `${item.kind}:${item.id}`

  const handleCheckboxClick = (
    idx: number,
    key: string,
    shiftKey: boolean
  ) => {
    setCheckedKeys((prev) => {
      const next = new Set(prev)
      const willCheck = !next.has(key)
      if (shiftKey && lastCheckedIndexRef.current != null) {
        const start = Math.min(lastCheckedIndexRef.current, idx)
        const end = Math.max(lastCheckedIndexRef.current, idx)
        for (let i = start; i <= end; i++) {
          const rk = rowKey(sortedItems[i])
          if (willCheck) next.add(rk)
          else next.delete(rk)
        }
      } else {
        if (willCheck) next.add(key)
        else next.delete(key)
      }
      lastCheckedIndexRef.current = idx
      return next
    })
  }

  const handleExportCsv = async () => {
    const stamp = new Date().toISOString().slice(0, 10)
    const outPath = await studioApi.pickSaveCsv(`animeow-curator-${stamp}.csv`)
    if (!outPath) return
    const count = await studioApi.exportCuratorCsv(outPath)
    setSheetNotice(`已导出 ${count} 条藏品报表至：${outPath}`)
    window.setTimeout(() => setSheetNotice(null), 5000)
  }

  const openItemContextMenu = (e: React.MouseEvent, item: CuratorItem) => {
    e.preventDefault()
    const x = Math.min(e.clientX, window.innerWidth - 210)
    const y = Math.min(e.clientY, window.innerHeight - 175)
    setCtxMenu({ x, y, item })
  }

  const toggleCheckAllVisible = () => {
    const visibleKeys = sortedItems.map(rowKey)
    const allSelected = visibleKeys.length > 0 && visibleKeys.every((k) => checkedKeys.has(k))
    setCheckedKeys((prev) => {
      const next = new Set(prev)
      if (allSelected) visibleKeys.forEach((k) => next.delete(k))
      else visibleKeys.forEach((k) => next.add(k))
      return next
    })
  }

  const selectedTargets = useMemo(() => {
    const out: [MediaCarrier, number][] = []
    for (const item of items) {
      if (checkedKeys.has(rowKey(item))) out.push([item.kind, item.id])
    }
    return out
  }, [items, checkedKeys])

  const applyBatchStatus = async () => {
    if (selectedTargets.length === 0) return
    await onBatchStatus(selectedTargets, batchStatusValue)
    setCheckedKeys(new Set())
  }

  const applyBatchSeries = async () => {
    if (selectedTargets.length === 0 || !seriesInput.trim()) return
    await onBatchSeries(selectedTargets, seriesInput.trim(), seriesRoleInput)
    setSeriesInput('')
    setCheckedKeys(new Set())
  }

  const allVisibleChecked =
    sortedItems.length > 0 && sortedItems.every((item) => checkedKeys.has(rowKey(item)))

  const [tableRowHeight, setTableRowHeight] = useState<number>(() =>
    rowHeightForFontScale(loadLocalDesktopSettings().fontScale)
  )

  useEffect(() => {
    const onSettingsChanged = (e: Event) => {
      const detail = (e as CustomEvent<DesktopSettings>).detail
      if (detail?.fontScale) {
        setTableRowHeight(rowHeightForFontScale(detail.fontScale))
      }
    }
    window.addEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
    return () => window.removeEventListener(DESKTOP_SETTINGS_EVENT, onSettingsChanged)
  }, [])

  // 视口虚拟滚动窗口计算（仅挂载可见行 ± OVERSCAN，万行大库保持 60fps）
  const virtualWindow = useMemo(() => {
    const total = sortedItems.length
    const effectiveScroll = Math.max(0, scrollTop - TABLE_HEADER_HEIGHT)
    const startIdx = Math.max(0, Math.floor(effectiveScroll / tableRowHeight) - TABLE_OVERSCAN)
    const visibleRows = Math.ceil(viewportHeight / tableRowHeight) + TABLE_OVERSCAN * 2
    const endIdx = Math.min(total, startIdx + visibleRows)
    return {
      startIdx,
      endIdx,
      topSpacer: startIdx * tableRowHeight,
      bottomSpacer: Math.max(0, (total - endIdx) * tableRowHeight),
      slice: sortedItems.slice(startIdx, endIdx)
    }
  }, [sortedItems, scrollTop, viewportHeight, tableRowHeight])

  // J / K 键盘选行超出视口时自动滚动跟随
  useEffect(() => {
    if (subView !== 'table' || !selectedKey) return
    const el = sheetScrollRef.current
    if (!el) return
    const idx = sortedItems.findIndex((it) => `${it.kind}:${it.id}` === selectedKey)
    if (idx < 0) return
    const rowTop = TABLE_HEADER_HEIGHT + idx * tableRowHeight
    const rowBottom = rowTop + tableRowHeight
    const viewTop = el.scrollTop + TABLE_HEADER_HEIGHT
    const viewBottom = el.scrollTop + el.clientHeight
    if (rowTop < viewTop) {
      el.scrollTop = Math.max(0, rowTop - TABLE_HEADER_HEIGHT)
    } else if (rowBottom > viewBottom) {
      el.scrollTop = Math.max(0, rowBottom - el.clientHeight)
    }
  }, [selectedKey, subView, sortedItems, tableRowHeight])

  const kanbanColumns = useMemo(() => {
    const active: CuratorItem[] = []
    const done: CuratorItem[] = []
    const planned: CuratorItem[] = []
    const hold: CuratorItem[] = []
    for (const it of sortedItems) {
      const lane = classifyStatusLane(it.status)
      if (lane === 'active') active.push(it)
      else if (lane === 'done') done.push(it)
      else if (lane === 'planned') planned.push(it)
      else hold.push(it)
    }
    return [
      { key: 'active', title: '正在进行', hint: '在看 / 在玩 / 在听', list: active },
      { key: 'planned', title: '想看 / 待开启', hint: '想看 / 未看 / 想玩 / 想听', list: planned },
      { key: 'done', title: '已完成归档', hint: '看完 / 已玩 / 已听', list: done },
      { key: 'hold', title: '搁置与弃坑', hint: '搁置 / 弃坑 / 暂搁', list: hold }
    ]
  }, [sortedItems])

  useEffect(() => {
    if (!kanbanDrag) return
    const handleMove = (e: PointerEvent) => {
      const dist = Math.hypot(e.clientX - kanbanDrag.startX, e.clientY - kanbanDrag.startY)
      const isNowDragging = kanbanDrag.dragging || dist >= 5
      setKanbanDrag((prev) =>
        prev
          ? {
              ...prev,
              curX: e.clientX,
              curY: e.clientY,
              dragging: prev.dragging || dist >= 5
            }
          : null
      )
      if (!isNowDragging) return
      const els = document.elementsFromPoint(e.clientX, e.clientY)
      let hitCol: string | null = null
      for (const el of els) {
        const attr = el.getAttribute('data-kanban-col')
        if (attr) {
          hitCol = attr
          break
        }
      }
      kanbanHoverColRef.current = hitCol
      setKanbanHoverCol(hitCol)
    }
    const handleUp = () => {
      const current = kanbanDrag
      const colKey = kanbanHoverColRef.current
      setKanbanDrag(null)
      setKanbanHoverCol(null)
      kanbanHoverColRef.current = null
      if (!current.dragging || !colKey) return
      const currentLane = classifyStatusLane(current.item.status)
      if (currentLane === colKey) return
      const nextStatus = defaultStatusForColumn(current.item.kind, colKey)
      if (nextStatus !== current.item.status) {
        void onUpdateCell({ kind: current.item.kind, id: current.item.id, status: nextStatus })
      }
    }
    window.addEventListener('pointermove', handleMove)
    window.addEventListener('pointerup', handleUp)
    return () => {
      window.removeEventListener('pointermove', handleMove)
      window.removeEventListener('pointerup', handleUp)
    }
  }, [kanbanDrag, onUpdateCell])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="sheet-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <div className="segmented-group">
            <button
              className={`segmented-btn ${subView === 'shelf' ? 'active' : ''}`}
              onClick={() => onSubViewChange('shelf')}
            >
              画册视图
            </button>
            <button
              className={`segmented-btn ${subView === 'kanban' ? 'active' : ''}`}
              onClick={() => onSubViewChange('kanban')}
            >
              状态看板
            </button>
            <button
              className={`segmented-btn ${subView === 'table' ? 'active' : ''}`}
              onClick={() => onSubViewChange('table')}
            >
              列表视图
            </button>
          </div>
          <span className="badge mono">{sortedItems.length} 项</span>

          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <StudioSelect
              className="studio-select-inline"
              value={sortField}
              onChange={(val) => setSortField(val as SortField)}
              title="排序维度"
              ariaLabel="排序维度"
              minMenuWidth={144}
              maxMenuWidth={190}
              options={[
                { value: 'lastModifiedAt', label: '按最后修改' },
                { value: 'personalScore', label: '按个人评分' },
                { value: 'publicScore', label: '按公共均分' },
                { value: 'scoreDelta', label: '按评分分差' },
                { value: 'progress', label: '按观看进度' },
                { value: 'releaseDate', label: '按放送日期' },
                { value: 'title', label: '按标题名称' },
                { value: 'code', label: '按归档编号' }
              ]}
            />
            <button
              className="btn"
              style={{ padding: '4px 8px' }}
              onClick={() => setSortAsc((v) => !v)}
              title="切换升序 / 降序"
            >
              {sortAsc ? '↑ 升序' : '↓ 降序'}
            </button>
          </div>

          <StudioSelect
            className="studio-select-inline"
            value={statusFilter}
            onChange={(val) => onStatusFilterChange(val)}
            title="按观看/阅读/游玩状态筛选"
            ariaLabel="按状态筛选"
            minMenuWidth={136}
            maxMenuWidth={200}
            options={[
              { value: 'all', label: '全部状态' },
              ...availableStatuses.map(([st, cnt]) => ({
                value: st,
                label: `${st} (${cnt})`
              }))
            ]}
          />

          <StudioSelect
            className="studio-select-inline studio-select-tag-cap"
            value={tagFilter}
            onChange={(val) => onTagFilterChange(val)}
            title="按标签筛选"
            ariaLabel="按标签筛选"
            minMenuWidth={168}
            maxMenuWidth={250}
            options={[
              { value: 'all', label: '全部标签' },
              ...availableTags.slice(0, 120).map(([tg, cnt]) => ({
                value: tg,
                label: `#${tg} (${cnt})`
              }))
            ]}
          />

          <StudioSelect
            className="studio-select-inline"
            value={ratingFilter}
            onChange={(val) =>
              onRatingFilterChange(val as 'all' | 'rated' | 'unrated' | 'high')
            }
            title="按评分状态筛选"
            ariaLabel="按评分筛选"
            minMenuWidth={132}
            maxMenuWidth={180}
            options={[
              { value: 'all', label: '全部评分' },
              { value: 'high', label: '8 分及以上' },
              { value: 'rated', label: '已评分' },
              { value: 'unrated', label: '未评分' }
            ]}
          />

          <div className="command-search" style={{ height: 25 }}>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>筛选</span>
            <input
              style={{ width: 150 }}
              value={searchQuery}
              onChange={(e) => onSearchQueryChange(e.target.value)}
              placeholder="标题 / 原名 / 标签 / 备注..."
            />
            {searchQuery && (
              <button className="btn btn-ghost" onClick={() => onSearchQueryChange('')}>
                ×
              </button>
            )}
          </div>

          {activeSeriesFilter && (
            <button
              className="btn"
              style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
              onClick={onClearSeriesFilter}
              title="点击取消当前系列筛选"
            >
              系列：{activeSeriesFilter} ×
            </button>
          )}

          {hasExtraFilters && (
            <button
              className="btn btn-ghost"
              style={{ padding: '3px 8px', fontSize: '0.8846rem', color: 'var(--accent)' }}
              onClick={onClearAllFilters}
            >
              清空筛选
            </button>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <button
            className="btn"
            disabled={sortedItems.length === 0}
            onClick={() => void handleExportCsv()}
            title="导出全库 UTF-8 BOM CSV 报表（Excel 可直接打开）"
          >
            导出 CSV 报表
          </button>
          <button className="btn btn-primary" onClick={() => onOpenCommandPalette('bangumi')}>
            + 新增 / 多源入库
          </button>
        </div>
      </div>

      {sheetNotice && (
        <div
          style={{
            padding: '5px 12px',
            background: 'var(--bg-panel)',
            borderBottom: '1px solid var(--border-subtle)',
            fontSize: '0.9231rem',
            color: 'var(--positive)'
          }}
        >
          {sheetNotice}
        </div>
      )}

      {selectedTargets.length > 0 && (
        <div
          style={{
            padding: '6px 12px',
            background: 'var(--accent-soft)',
            borderBottom: '1px solid var(--border-strong)',
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: 8
          }}
        >
          <span className="badge badge-accent">已勾选 {selectedTargets.length} 项</span>

          <StudioSelect
            className="studio-select-inline"
            value={batchStatusValue}
            onChange={(val) => setBatchStatusValue(val)}
            ariaLabel="批量目标状态"
            minMenuWidth={136}
            maxMenuWidth={180}
            options={[
              { value: '在看', label: '状态 → 在看' },
              { value: '看完', label: '状态 → 看完' },
              { value: '想看', label: '状态 → 想看' },
              { value: '未看', label: '状态 → 未看' },
              { value: '在玩', label: '状态 → 在玩' },
              { value: '已玩', label: '状态 → 已玩' },
              { value: '在听', label: '状态 → 在听' },
              { value: '已听', label: '状态 → 已听' },
              { value: '搁置', label: '状态 → 搁置' },
              { value: '弃坑', label: '状态 → 弃坑' }
            ]}
          />
          <button className="btn" onClick={applyBatchStatus}>
            批量改状态
          </button>

          <span style={{ opacity: 0.35 }}>|</span>

          <input
            className="inline-input"
            style={{ width: 150 }}
            value={seriesInput}
            onChange={(e) => setSeriesInput(e.target.value)}
            placeholder="输入跨媒体企划名称..."
          />
          <StudioSelect
            className="studio-select-inline"
            value={seriesRoleInput}
            onChange={(val) => setSeriesRoleInput(val)}
            ariaLabel="企划系列定位"
            minMenuWidth={146}
            maxMenuWidth={190}
            options={[
              { value: '原作', label: '角色：原作' },
              { value: '正作', label: '角色：正作' },
              { value: '改编', label: '角色：改编' },
              { value: '续作', label: '角色：续作' },
              { value: '番外／衍生', label: '角色：番外／衍生' }
            ]}
          />
          <button
            className="btn btn-primary"
            disabled={!seriesInput.trim()}
            onClick={applyBatchSeries}
          >
            批量归入企划系列
          </button>

          <button className="btn btn-ghost" onClick={() => setCheckedKeys(new Set())}>
            取消勾选
          </button>
        </div>
      )}

      <div className="sheet-scroll" ref={sheetScrollRef} onScroll={handleSheetScroll}>
        {sortedItems.length === 0 ? (
          <div style={{ padding: 52, textAlign: 'center', color: 'var(--text-secondary)' }}>
            <div className="heading-font" style={{ fontSize: '1.2308rem', fontWeight: 700, marginBottom: 8 }}>
              当前视图暂无藏品档案
            </div>
            <div style={{ maxWidth: 500, margin: '0 auto 16px' }}>
              可按 <code>Ctrl+K</code> 从 Bangumi / VNDB / DLsite 搜索入库，或前往「同步」页从云端账号与本地 <code>.animeow.zip</code> 备份包恢复藏品。
            </div>
            <div style={{ display: 'inline-flex', gap: 8 }}>
              <button className="btn btn-primary" onClick={() => onOpenCommandPalette('bangumi')}>
                从 Bangumi 检索入库
              </button>
              <button className="btn" onClick={() => onOpenCommandPalette('manual')}>
                手动离线建档
              </button>
            </div>
          </div>
        ) : subView === 'table' ? (
          <table className="curator-table">
            <thead>
              <tr>
                <th style={{ width: 34, minWidth: 34, cursor: 'default' }}>
                  <input
                    type="checkbox"
                    checked={allVisibleChecked}
                    onChange={toggleCheckAllVisible}
                    title="全选/取消当前视图"
                  />
                </th>
                <th style={{ width: 280, minWidth: 240 }} onClick={() => toggleSort('title')}>
                  作品标题 / 跨媒体企划{sortIndicator('title')}
                </th>
                <th style={{ width: 92, minWidth: 92 }} onClick={() => toggleSort('status')}>
                  状态{sortIndicator('status')}
                </th>
                <th style={{ width: 156, minWidth: 156 }} onClick={() => toggleSort('progress')}>
                  进度与快捷步进{sortIndicator('progress')}
                </th>
                <th style={{ width: 64, minWidth: 64 }} onClick={() => toggleSort('kind')}>
                  载体{sortIndicator('kind')}
                </th>
                <th style={{ width: 72, minWidth: 72 }} onClick={() => toggleSort('personalScore')}>
                  个人分{sortIndicator('personalScore')}
                </th>
                <th style={{ width: 66, minWidth: 66 }} onClick={() => toggleSort('publicScore')}>
                  均分{sortIndicator('publicScore')}
                </th>
                <th style={{ width: 68, minWidth: 68 }} onClick={() => toggleSort('scoreDelta')}>
                  分差{sortIndicator('scoreDelta')}
                </th>
                <th style={{ width: 92, minWidth: 92 }} onClick={() => toggleSort('localFileCount')}>
                  本机媒体{sortIndicator('localFileCount')}
                </th>
                <th style={{ width: 132, minWidth: 120 }}>制作 / 社团</th>
                <th style={{ width: 96, minWidth: 96 }} onClick={() => toggleSort('releaseDate')}>
                  放送/发售{sortIndicator('releaseDate')}
                </th>
                <th style={{ width: 88, minWidth: 88 }} onClick={() => toggleSort('code')}>
                  编号{sortIndicator('code')}
                </th>
              </tr>
            </thead>
            <tbody>
              {virtualWindow.topSpacer > 0 && (
                <tr className="curator-vspacer" aria-hidden="true">
                  <td colSpan={12} style={{ height: virtualWindow.topSpacer, padding: 0, border: 'none' }} />
                </tr>
              )}
              {virtualWindow.slice.map((item, localIdx) => {
                const rowIdx = virtualWindow.startIdx + localIdx
                const key = rowKey(item)
                const isSelected = selectedKey === key
                const isChecked = checkedKeys.has(key)
                return (
                  <tr
                    key={key}
                    className={`curator-vrow ${isSelected ? 'selected' : ''}`}
                    style={{ height: tableRowHeight }}
                    onClick={() => onSelectItem(item)}
                    onDoubleClick={(e) => {
                      if ((e.target as HTMLElement)?.closest('button, input, select, textarea, a, [role="button"]')) return
                      onOpenReviewStudio(item)
                    }}
                    onContextMenu={(e) => openItemContextMenu(e, item)}
                  >
                    <td onClick={(e) => e.stopPropagation()} onDoubleClick={(e) => e.stopPropagation()}>
                      <input
                        type="checkbox"
                        checked={isChecked}
                        onChange={() => {}}
                        onClick={(e) => handleCheckboxClick(rowIdx, key, e.shiftKey)}
                        title="按住 Shift 点击可连选区间"
                      />
                    </td>
                    <td
                      className="curator-title-cell"
                      title={item.originalTitle ? `${item.title} (${item.originalTitle})` : item.title}
                    >
                      <span className="heading-font" style={{ fontWeight: 700 }}>
                        {item.title}
                      </span>
                      {item.seriesName && (
                        <span
                          className="badge badge-accent"
                          style={{ marginLeft: 6 }}
                          title={`所属企划：${item.seriesName}`}
                        >
                          {item.seriesName}
                          {item.seriesRole ? ` · ${item.seriesRole}` : ''}
                        </span>
                      )}
                      {item.memo && (
                        <span
                          style={{ marginLeft: 6, color: 'var(--text-muted)', fontSize: '0.8462rem' }}
                          title={`私人备注：${item.memo}`}
                        >
                          [备注]
                        </span>
                      )}
                    </td>
                    <td onClick={(e) => e.stopPropagation()} onDoubleClick={(e) => e.stopPropagation()}>
                      <StudioSelect
                        className="studio-select-inline"
                        value={item.status}
                        onChange={(val) =>
                          onUpdateCell({ kind: item.kind, id: item.id, status: val })
                        }
                        ariaLabel={`${item.title} 状态`}
                        minMenuWidth={112}
                        maxMenuWidth={160}
                        options={[
                          ...(!statusOptionsFor(item.kind).includes(item.status)
                            ? [{ value: item.status, label: item.status }]
                            : []),
                          ...statusOptionsFor(item.kind).map((st) => ({
                            value: st,
                            label: st
                          }))
                        ]}
                      />
                    </td>
                    <td className="mono" onClick={(e) => e.stopPropagation()} onDoubleClick={(e) => e.stopPropagation()}>
                      <div className="inline-step">
                        <button
                          className="step-btn"
                          onClick={() => onStepProgressWithUndo(item, -1)}
                          onDoubleClick={(e) => e.stopPropagation()}
                          title="减少一步进度 ([)"
                        >
                          -
                        </button>
                        <span style={{ minWidth: 82, display: 'inline-block', textAlign: 'center' }}>
                          {formatProgressText(item)}
                        </span>
                        <button
                          className="step-btn"
                          onClick={() => onStepProgressWithUndo(item, 1)}
                          onDoubleClick={(e) => e.stopPropagation()}
                          title="推进进度 (]) · 支持撤销"
                        >
                          +
                        </button>
                      </div>
                    </td>
                    <td>
                      <span className="badge">{formatItemCarrierLabel(item)}</span>
                    </td>
                    <td className="mono" style={{ fontWeight: 700 }}>
                      {item.personalScore != null ? item.personalScore.toFixed(1) : '—'}
                    </td>
                    <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                      {item.publicScore != null ? item.publicScore.toFixed(1) : '—'}
                    </td>
                    <td className="mono">
                      {item.scoreDelta != null ? (
                        <span
                          style={{
                            color:
                              item.scoreDelta > 0
                                ? 'var(--positive)'
                                : item.scoreDelta < 0
                                  ? 'var(--danger)'
                                  : 'var(--text-secondary)',
                            fontWeight: 700
                          }}
                        >
                          {item.scoreDelta > 0 ? `+${item.scoreDelta.toFixed(1)}` : item.scoreDelta.toFixed(1)}
                        </span>
                      ) : (
                        <span style={{ color: 'var(--text-muted)' }}>—</span>
                      )}
                    </td>
                    <td>
                      {item.localFileCount > 0 ? (
                        <span className="badge badge-vault mono">本机 {item.localFileCount}</span>
                      ) : (
                        <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>未绑定</span>
                      )}
                    </td>
                    <td style={{ color: 'var(--text-secondary)' }} title={item.studioOrCircle}>
                      {item.studioOrCircle || '—'}
                    </td>
                    <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                      {item.releaseDate || '—'}
                    </td>
                    <td className="mono" style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                      {formatArchiveCode(item)}
                    </td>
                  </tr>
                )
              })}
              {virtualWindow.bottomSpacer > 0 && (
                <tr className="curator-vspacer" aria-hidden="true">
                  <td colSpan={12} style={{ height: virtualWindow.bottomSpacer, padding: 0, border: 'none' }} />
                </tr>
              )}
            </tbody>
          </table>
        ) : subView === 'kanban' ? (
          <div className="kanban-board">
            {kanbanColumns.map((col) => {
              const isDropHover = Boolean(kanbanDrag?.dragging) && kanbanHoverCol === col.key
              return (
                <div
                  key={col.key}
                  data-kanban-col={col.key}
                  className={`kanban-lane ${isDropHover ? 'is-drop-hover' : ''}`}
                >
                  <div className="kanban-lane-header" title={col.hint}>
                    <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {col.title}
                    </span>
                    <span className="badge mono">{col.list.length}</span>
                  </div>
                  <div className="kanban-lane-body">
                    {col.list.slice(0, cardDisplayLimit).map((it) => {
                      const key = rowKey(it)
                      const isDraggingThis =
                        Boolean(kanbanDrag?.dragging) &&
                        kanbanDrag?.item.kind === it.kind &&
                        kanbanDrag?.item.id === it.id
                      return (
                        <div
                          key={key}
                          onPointerDown={(e) => {
                            if (e.button !== 0) return
                            if ((e.target as HTMLElement).closest('button')) return
                            setKanbanDrag({
                              item: it,
                              startX: e.clientX,
                              startY: e.clientY,
                              curX: e.clientX,
                              curY: e.clientY,
                              dragging: false
                            })
                          }}
                          onClick={() => onSelectItem(it)}
                          onDoubleClick={(e) => {
                            if ((e.target as HTMLElement)?.closest('button, input, select, textarea, a, [role="button"]')) return
                            onOpenReviewStudio(it)
                          }}
                          onContextMenu={(e) => openItemContextMenu(e, it)}
                          className={`kanban-card ${selectedKey === key ? 'is-selected' : ''}`}
                          style={{
                            opacity: isDraggingThis ? 0.35 : 1,
                            cursor: kanbanDrag?.dragging ? 'grabbing' : 'grab',
                            touchAction: 'none'
                          }}
                        >
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                            <span className="badge">{formatItemCarrierLabel(it)}</span>
                            <span className="mono" style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                              {it.status} · {formatArchiveCode(it)}
                            </span>
                          </div>
                          <div className="heading-font" style={{ fontWeight: 700, fontSize: '1.0385rem', lineHeight: 1.35 }}>
                            {it.title}
                          </div>
                          <div
                            style={{
                              display: 'flex',
                              justifyContent: 'space-between',
                              alignItems: 'center',
                              marginTop: 9,
                              fontSize: '0.8846rem',
                              color: 'var(--text-secondary)'
                            }}
                          >
                            <span className="mono">{formatProgressText(it)}</span>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                              {it.personalScore != null && (
                                <span className="mono" style={{ fontWeight: 700, color: 'var(--accent)' }}>
                                  {it.personalScore.toFixed(1)}
                                </span>
                              )}
                              <button
                                className="step-btn"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  onStepProgressWithUndo(it, 1)
                                }}
                                onDoubleClick={(e) => e.stopPropagation()}
                                title="进度 +1"
                              >
                                +
                              </button>
                            </div>
                          </div>
                        </div>
                      )
                    })}
                    {col.list.length > cardDisplayLimit && (
                      <button
                        className="btn"
                        onClick={() => setCardDisplayLimit((v) => v + CARD_VIEW_PAGE_STEP)}
                      >
                        展开更多（剩余 {col.list.length - cardDisplayLimit} 项）
                      </button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="shelf-grid">
            {sortedItems.slice(0, cardDisplayLimit).map((it) => {
              const key = rowKey(it)
              const ratio = calcProgressRatio(it)
              const isSelected = selectedKey === key
              return (
                <article
                  key={key}
                  onClick={() => onSelectItem(it)}
                  onDoubleClick={(e) => {
                    if ((e.target as HTMLElement)?.closest('button, input, select, textarea, a, [role="button"]')) return
                    onOpenReviewStudio(it)
                  }}
                  onContextMenu={(e) => openItemContextMenu(e, it)}
                  className={`shelf-card ${isSelected ? 'is-selected' : ''}`}
                >
                  <div
                    className="shelf-cover"
                    style={{
                      background: spinePaletteColor(it)
                    }}
                  >
                    <CoverImage
                      coverUrl={it.coverUrl}
                      mediaBaseUrl={mediaBaseUrl}
                      alt={it.title}
                      fallback={
                        <div className="shelf-cover-fallback">
                          <span className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700, lineHeight: 1.35 }}>
                            {it.title.slice(0, 16)}
                          </span>
                        </div>
                      }
                    />

                    <div className="shelf-cover-top">
                      <span className="shelf-glass-pill">{formatItemCarrierLabel(it)}</span>
                      {it.personalScore != null && (
                        <span className="shelf-glass-pill shelf-score-pill mono">
                          {it.personalScore.toFixed(1)} 分
                        </span>
                      )}
                    </div>

                    <div className="shelf-cover-actions" onDoubleClick={(e) => e.stopPropagation()}>
                      {it.kind === 'anime' && onWatchAnimeOnline && (
                        <button
                          className="shelf-quick-btn shelf-watch-btn"
                          onClick={(e) => {
                            e.stopPropagation()
                            onWatchAnimeOnline(it)
                          }}
                          onDoubleClick={(e) => e.stopPropagation()}
                          title="打开在线追番播放器"
                        >
                          ▶ 看番
                        </button>
                      )}
                      <button
                        className="shelf-quick-btn"
                        onClick={(e) => {
                          e.stopPropagation()
                          onStepProgressWithUndo(it, 1)
                        }}
                        onDoubleClick={(e) => e.stopPropagation()}
                        title="进度 +1"
                      >
                        +1
                      </button>
                      <button
                        className="shelf-quick-btn"
                        onClick={(e) => {
                          e.stopPropagation()
                          onOpenReviewStudio(it)
                        }}
                        onDoubleClick={(e) => e.stopPropagation()}
                        title="打开三栏漫评室"
                      >
                        漫评
                      </button>
                    </div>

                    {ratio != null && (
                      <div className="shelf-progress-track">
                        <div
                          className="shelf-progress-fill"
                          style={{ width: `${Math.round(ratio * 100)}%` }}
                        />
                      </div>
                    )}
                  </div>

                  <div className="shelf-card-body">
                    <div className="shelf-card-title heading-font" title={it.title}>
                      {it.title}
                    </div>
                    <div className="shelf-card-meta">
                      <span className="badge" style={{ fontSize: '0.8077rem' }}>
                        {it.status}
                      </span>
                      <span className="mono" style={{ fontSize: '0.8462rem', color: 'var(--text-secondary)' }}>
                        {formatProgressText(it)}
                      </span>
                    </div>
                  </div>
                </article>
              )
            })}
            {sortedItems.length > cardDisplayLimit && (
              <div style={{ gridColumn: '1 / -1', textAlign: 'center', padding: 14 }}>
                <button
                  className="btn btn-primary"
                  onClick={() => setCardDisplayLimit((v) => v + CARD_VIEW_PAGE_STEP)}
                >
                  展开更多画册卡片（已展示 {cardDisplayLimit} / 共 {sortedItems.length} 项）
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {kanbanDrag?.dragging && (
        <div
          className="drag-ghost-pill"
          style={{
            position: 'fixed',
            left: kanbanDrag.curX + 14,
            top: kanbanDrag.curY + 14,
            pointerEvents: 'none',
            zIndex: 9999
          }}
        >
          <span className="badge badge-accent">{formatItemCarrierLabel(kanbanDrag.item)}</span>
          <strong className="heading-font">{kanbanDrag.item.title}</strong>
        </div>
      )}

      {ctxMenu && (() => {
        const menuW = 240
        const menuEstH = 340
        const clampedX = Math.min(ctxMenu.x, Math.max(8, window.innerWidth - menuW - 8))
        const clampedY = Math.min(ctxMenu.y, Math.max(44, window.innerHeight - menuEstH - 8))
        const statuses = statusOptionsFor(ctxMenu.item.kind)
        return (
          <div
            className="card-box"
            onClick={(e) => e.stopPropagation()}
            style={{
              position: 'fixed',
              left: clampedX,
              top: clampedY,
              width: menuW,
              padding: 6,
              zIndex: 1050,
              border: '1px solid var(--border-strong)',
              boxShadow: '0 12px 32px rgba(0, 0, 0, 0.28)',
              display: 'flex',
              flexDirection: 'column',
              gap: 3
            }}
          >
            <div
              className="heading-font"
              style={{
                padding: '4px 6px 6px',
                fontSize: '0.8846rem',
                fontWeight: 700,
                borderBottom: '1px solid var(--border-subtle)',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis'
              }}
              title={ctxMenu.item.title}
            >
              {ctxMenu.item.title}
            </div>

            {onOpenEditor && (
              <button
                className="btn btn-primary"
                style={{ justifyContent: 'flex-start', height: 28 }}
                onClick={() => {
                  const target = ctxMenu.item
                  setCtxMenu(null)
                  onOpenEditor(target)
                }}
              >
                编辑作品信息（独立窗口）
              </button>
            )}
            <button
              className="btn btn-ghost"
              style={{ justifyContent: 'flex-start' }}
              onClick={() => {
                onSelectItem(ctxMenu.item)
                setCtxMenu(null)
              }}
            >
              查看作品详情
            </button>

            <div
              style={{
                padding: '4px 6px',
                borderTop: '1px solid var(--border-subtle)',
                borderBottom: '1px solid var(--border-subtle)',
                display: 'flex',
                flexDirection: 'column',
                gap: 4
              }}
            >
              <span style={{ fontSize: '0.7692rem', color: 'var(--text-muted)' }}>快捷修改状态</span>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                {statuses.map((st) => {
                  const active = ctxMenu.item.status === st
                  return (
                    <button
                      key={st}
                      type="button"
                      className={`btn ${active ? 'btn-primary' : ''}`}
                      style={{ height: 22, padding: '0 7px', fontSize: '0.8077rem' }}
                      onClick={() => {
                        const target = ctxMenu.item
                        setCtxMenu(null)
                        if (!active) {
                          void onUpdateCell({ kind: target.kind, id: target.id, status: st })
                        }
                      }}
                    >
                      {st}
                    </button>
                  )
                })}
              </div>
            </div>

            <div style={{ display: 'flex', gap: 4 }}>
              <button
                className="btn btn-ghost"
                style={{ flex: 1, justifyContent: 'center' }}
                onClick={() => {
                  const target = ctxMenu.item
                  setCtxMenu(null)
                  void onStepProgressWithUndo(target, 1)
                }}
              >
                进度 +1
              </button>
              <button
                className="btn btn-ghost"
                style={{ flex: 1, justifyContent: 'center' }}
                onClick={() => {
                  const target = ctxMenu.item
                  setCtxMenu(null)
                  void onStepProgressWithUndo(target, -1)
                }}
              >
                进度 -1
              </button>
            </div>

            <button
              className="btn btn-ghost"
              style={{ justifyContent: 'flex-start' }}
              onClick={() => {
                onOpenReviewStudio(ctxMenu.item)
                setCtxMenu(null)
              }}
            >
              写长评与笔记
            </button>
            {ctxMenu.item.kind === 'anime' && onWatchAnimeOnline && (
              <button
                className="btn btn-ghost"
                style={{ justifyContent: 'flex-start' }}
                onClick={() => {
                  const target = ctxMenu.item
                  setCtxMenu(null)
                  onWatchAnimeOnline(target)
                }}
              >
                在线看番
              </button>
            )}
            {onFocusInCanvas && (
              <button
                className="btn btn-ghost"
                style={{ justifyContent: 'flex-start' }}
                onClick={() => {
                  const target = ctxMenu.item
                  setCtxMenu(null)
                  onFocusInCanvas(target)
                }}
              >
                在画布中查看关联图谱
              </button>
            )}
            <button
              className="btn btn-ghost"
              style={{
                justifyContent: 'flex-start',
                color: 'var(--danger)',
                borderTop: '1px solid var(--border-subtle)',
                marginTop: 2,
                paddingTop: 6
              }}
              onClick={() => {
                const target = ctxMenu.item
                setCtxMenu(null)
                void onSoftDeleteItem(target)
              }}
            >
              移入回收站
            </button>
          </div>
        )
      })()}
    </div>
  )
}
