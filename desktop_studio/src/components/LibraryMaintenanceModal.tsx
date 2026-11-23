import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { studioApi, formatCarrierLabel } from '../api'
import { LoadingSpinner, SkeletonRows } from './LoadingIndicator'
import { SeriesManagePanel } from './SeriesManagePanel'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import { TagManagePanel } from './TagManagePanel'
import { CharacterManagePanel } from './CharacterManagePanel'
import type {
  CharacterManageItem,
  CsvImportReport,
  CuratorItem,
  DuplicateGroup,
  MediaCarrier,
  SeriesManageItem,
  SeriesSuggestionGroup,
  TagManageItem,
  TrashItem
} from '../types'

export type MaintenanceSection =
  | 'trash'
  | 'duplicates'
  | 'series'
  | 'series_manage'
  | 'tags_manage'
  | 'characters_manage'
  | 'csv'

interface LibraryMaintenanceModalProps {
  open: boolean
  initialSection?: MaintenanceSection
  initialCharacterId?: number | null
  initialCharacterSeq?: number
  allItems: CuratorItem[]
  mediaBaseUrl: string
  onClose: () => void
  onDataChanged: () => Promise<void>
  onOpenCanvasCharacters?: () => void
}

const IGNORED_DUP_KEY = 'animeow.studio.ignoredDupGroups.v1'
const IGNORED_SERIES_KEY = 'animeow.studio.ignoredSeriesGroups.v1'

function loadIgnoredSet(storageKey: string): string[] {
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return []
  }
}

function saveIgnoredSet(storageKey: string, keys: string[]): void {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(keys))
  } catch {
    // 忽略本地存储空间异常
  }
}

function longestCommonSubstring(strings: string[]): string {
  if (strings.length === 0) return ''
  let best = ''
  const first = [...strings[0]]
  const rest = strings.slice(1)
  for (let i = 0; i < first.length; i++) {
    for (let len = first.length - i; len > best.length; len--) {
      const sub = first.slice(i, i + len).join('')
      if (rest.every((s) => s.includes(sub))) {
        best = sub
        break
      }
    }
  }
  return best
}

function suggestSeriesNameForDupGroup(
  group: DuplicateGroup,
  allItems: CuratorItem[],
  seriesList: SeriesManageItem[]
): { name: string; fromExisting: boolean } {
  const itemMap = new Map(allItems.map((it) => [`${it.kind}:${it.id}`, it]))

  // 1. 优先复用组内已有作品所属系列
  const existingOnMember = new Map<string, number>()
  for (const c of group.candidates) {
    const it = itemMap.get(`${c.kind}:${c.id}`)
    if (it?.seriesName) {
      existingOnMember.set(it.seriesName, (existingOnMember.get(it.seriesName) ?? 0) + 1)
    }
  }
  if (existingOnMember.size > 0) {
    const best = [...existingOnMember.entries()].sort((a, b) => b[1] - a[1])[0][0]
    return { name: best, fromExisting: true }
  }

  // 2. 匹配现有系列名（大小写不敏感，要求全部候选标题包含该系列名，取最长）
  const titles = group.candidates.map((c) => c.title.trim()).filter(Boolean)
  const lowerTitles = titles.map((t) => t.toLowerCase())
  const matchedExisting = seriesList
    .filter((s) => {
      const sn = s.name.trim().toLowerCase()
      return [...sn].length >= 2 && lowerTitles.every((t) => t.includes(sn))
    })
    .sort((a, b) => [...b.name].length - [...a.name].length)
  if (matchedExisting.length > 0) {
    return { name: matchedExisting[0].name, fromExisting: true }
  }

  // 3. 提取全部候选标题的最大公共字部分（去除首尾残留标点/连接词）
  const rawLcs = longestCommonSubstring(titles)
  const cleaned = rawLcs
    .replace(/^[\s　:：\-—～~·!！?？(（\[【「『]+/, '')
    .replace(/[\s　:：\-—～~·!！?？(（\[【「『第的之]+$/, '')
    .trim()
  if ([...cleaned].length >= 2) {
    return { name: cleaned, fromExisting: false }
  }
  return { name: group.title.trim(), fromExisting: false }
}

export const LibraryMaintenanceModal: React.FC<LibraryMaintenanceModalProps> = ({
  open,
  initialSection = 'trash',
  initialCharacterId = null,
  initialCharacterSeq = 0,
  allItems,
  mediaBaseUrl,
  onClose,
  onDataChanged,
  onOpenCanvasCharacters
}) => {
  const dialog = useStudioDialog()
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const { offset: modalOffset, isMoved: modalMoved, dragHandlers: modalDragHandlers } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })
  const [section, setSection] = useState<MaintenanceSection>(initialSection)
  const [trash, setTrash] = useState<TrashItem[]>([])
  const [duplicates, setDuplicates] = useState<DuplicateGroup[]>([])
  const [seriesSuggestions, setSeriesSuggestions] = useState<SeriesSuggestionGroup[]>([])
  const [seriesManageList, setSeriesManageList] = useState<SeriesManageItem[]>([])
  const [tagsManageList, setTagsManageList] = useState<TagManageItem[]>([])
  const [charactersManageList, setCharactersManageList] = useState<CharacterManageItem[]>([])
  const [selectedKeepMap, setSelectedKeepMap] = useState<Record<string, number>>({})
  const [dupFilter, setDupFilter] = useState<'all' | 'exact' | 'suspected'>('all')
  const [ignoredDupKeys, setIgnoredDupKeys] = useState<string[]>(() =>
    loadIgnoredSet(IGNORED_DUP_KEY)
  )
  const [ignoredSeriesKeys, setIgnoredSeriesKeys] = useState<string[]>(() =>
    loadIgnoredSet(IGNORED_SERIES_KEY)
  )
  const [seriesNameEdits, setSeriesNameEdits] = useState<Record<string, string>>({})
  const [excludedSeriesMembers, setExcludedSeriesMembers] = useState<Record<string, string[]>>({})
  const [dupSeriesOpenKey, setDupSeriesOpenKey] = useState<string | null>(null)
  const [dupSeriesNameEdits, setDupSeriesNameEdits] = useState<Record<string, string>>({})
  const [trashLimit, setTrashLimit] = useState(50)
  const [dupLimit, setDupLimit] = useState(30)
  const [seriesSugLimit, setSeriesSugLimit] = useState(30)
  const [loadedSections, setLoadedSections] = useState<Record<string, boolean>>({})
  const loadedSectionsRef = useRef<Record<string, boolean>>({})
  const [loading, setLoading] = useState(false)
  const [busyMsg, setBusyMsg] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [csvReport, setCsvReport] = useState<CsvImportReport | null>(null)

  useEffect(() => {
    if (open) {
      setSection(initialSection)
      setTrashLimit(50)
      setDupLimit(30)
      setSeriesSugLimit(30)
      // 每次重新打开弹窗时标记缓存过期，按需加载当前页签，避免一次性全量扫库卡顿
      loadedSectionsRef.current = {}
      setLoadedSections({})
    }
  }, [open, initialSection])

  const loadSection = useCallback(async (target: MaintenanceSection, force = false) => {
    if (target === 'csv') return
    if (!force && loadedSectionsRef.current[target]) return
    setLoading(true)
    setErrorMsg(null)
    try {
      if (target === 'trash') {
        const tList = await studioApi.listTrash()
        setTrash(tList)
      } else if (target === 'duplicates') {
        const [dList, smList] = await Promise.all([
          studioApi.listDuplicates(),
          loadedSectionsRef.current.series_manage
            ? Promise.resolve(null)
            : studioApi.listSeriesManage()
        ])
        setDuplicates(dList)
        const keepInit: Record<string, number> = {}
        dList.forEach((g) => {
          keepInit[g.groupKey] = g.recommendedKeepId
        })
        setSelectedKeepMap(keepInit)
        if (smList) {
          setSeriesManageList(smList)
          loadedSectionsRef.current = { ...loadedSectionsRef.current, series_manage: true }
        }
      } else if (target === 'series') {
        const sList = await studioApi.listSeriesSuggestions()
        setSeriesSuggestions(sList)
        setSeriesNameEdits((prev) => {
          const next: Record<string, string> = {}
          sList.forEach((g) => {
            next[g.groupKey] = prev[g.groupKey] ?? g.seriesName
          })
          return next
        })
      } else if (target === 'series_manage') {
        const smList = await studioApi.listSeriesManage()
        setSeriesManageList(smList)
      } else if (target === 'tags_manage') {
        const tmList = await studioApi.listTagsManage()
        setTagsManageList(tmList)
      } else if (target === 'characters_manage') {
        const cmList = await studioApi.listCharactersManage()
        setCharactersManageList(cmList)
      }
      loadedSectionsRef.current = { ...loadedSectionsRef.current, [target]: true }
      setLoadedSections({ ...loadedSectionsRef.current })
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }, [])

  // 写入变更后：仅立即刷新当前页签，其它关联页签标脏待切换时按需加载
  const loadAll = useCallback(async () => {
    loadedSectionsRef.current = {}
    setLoadedSections({})
    await loadSection(section, true)
  }, [loadSection, section])

  useEffect(() => {
    if (open) {
      void loadSection(section, false)
    }
  }, [open, section, loadSection])

  useEffect(() => {
    setDupLimit(30)
  }, [dupFilter])

  const ignoredDupSet = useMemo(() => new Set(ignoredDupKeys), [ignoredDupKeys])
  const ignoredSeriesSet = useMemo(() => new Set(ignoredSeriesKeys), [ignoredSeriesKeys])

  const visibleDuplicates = useMemo(
    () => duplicates.filter((g) => !ignoredDupSet.has(g.groupKey)),
    [duplicates, ignoredDupSet]
  )
  const exactDupCount = useMemo(
    () => visibleDuplicates.filter((g) => g.confidence === 'exact').length,
    [visibleDuplicates]
  )
  const suspectedDupCount = useMemo(
    () => visibleDuplicates.filter((g) => g.confidence === 'suspected').length,
    [visibleDuplicates]
  )
  const filteredDuplicates = useMemo(() => {
    if (dupFilter === 'all') return visibleDuplicates
    return visibleDuplicates.filter((g) => g.confidence === dupFilter)
  }, [visibleDuplicates, dupFilter])
  const ignoredDupCount = duplicates.length - visibleDuplicates.length

  const visibleSeriesSuggestions = useMemo(
    () => seriesSuggestions.filter((g) => !ignoredSeriesSet.has(g.groupKey)),
    [seriesSuggestions, ignoredSeriesSet]
  )
  const ignoredSeriesCount = seriesSuggestions.length - visibleSeriesSuggestions.length

  if (!open) return null

  const handleRestoreOne = async (item: TrashItem) => {
    setBusyMsg(`正在还原《${item.title}》...`)
    setErrorMsg(null)
    try {
      await studioApi.restoreTrash([[item.kind, item.id]])
      await onDataChanged()
      await loadAll()
      setNotice(`已还原《${item.title}》至主藏品库`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleRestoreAll = async () => {
    if (trash.length === 0) return
    setBusyMsg('正在批量还原全部回收站条目...')
    setErrorMsg(null)
    try {
      const count = await studioApi.restoreTrash(trash.map((t) => [t.kind, t.id]))
      await onDataChanged()
      await loadAll()
      setNotice(`已全部还原 ${count} 项藏品`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handlePurgeOne = async (item: TrashItem) => {
    setBusyMsg(`正在彻底删除《${item.title}》...`)
    setErrorMsg(null)
    try {
      await studioApi.purgeTrash([[item.kind, item.id]])
      await loadAll()
      setNotice(`已彻底清除《${item.title}》`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handlePurgeAll = async () => {
    if (trash.length === 0) return
    const ok = await dialog.confirm({
      title: '彻底清空回收站',
      message: `确定要彻底清空回收站中的 ${trash.length} 条记录吗？`,
      detail: '此操作不可撤销，清空后相关条目将从本地藏品库永久移除。',
      confirmText: '彻底清空',
      tone: 'danger'
    })
    if (!ok) {
      return
    }
    setBusyMsg('正在清空回收站...')
    setErrorMsg(null)
    try {
      const count = await studioApi.purgeTrash([])
      await loadAll()
      setNotice(`已彻底清空回收站（共移除 ${count} 条）`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleMergeGroup = async (group: DuplicateGroup) => {
    const keepId = selectedKeepMap[group.groupKey] ?? group.recommendedKeepId
    const dropIds = group.candidates.filter((c) => c.id !== keepId).map((c) => c.id)
    setBusyMsg(`正在合并重复条目《${group.title}》...`)
    setErrorMsg(null)
    try {
      const dropped = await studioApi.mergeDuplicate(group.kind, keepId, dropIds)
      await onDataChanged()
      await loadAll()
      setNotice(
        `已合并《${group.title}》：保留 #${keepId} 并吸收更高进度/评分/长评，${dropped} 条重复项移入回收站`
      )
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleDismissDuplicate = (group: DuplicateGroup) => {
    const next = Array.from(new Set([...ignoredDupKeys, group.groupKey]))
    setIgnoredDupKeys(next)
    saveIgnoredSet(IGNORED_DUP_KEY, next)
    setNotice(`已将「${group.title}」标记为非重复并隐藏`)
  }

  const handleAssignDupGroupAsSeries = async (group: DuplicateGroup, seriesNameRaw: string) => {
    const finalName = seriesNameRaw.trim()
    if (!finalName) {
      setErrorMsg('系列名称不能为空')
      return
    }
    setBusyMsg(`正在将 ${group.candidates.length} 部作品归入系列「${finalName}」...`)
    setErrorMsg(null)
    try {
      const targets: [MediaCarrier, number][] = group.candidates.map((c) => [c.kind, c.id])
      const count = await studioApi.batchSeries(targets, finalName, '系列成员')
      const nextIgnored = Array.from(new Set([...ignoredDupKeys, group.groupKey]))
      setIgnoredDupKeys(nextIgnored)
      saveIgnoredSet(IGNORED_DUP_KEY, nextIgnored)
      setDupSeriesOpenKey((prev) => (prev === group.groupKey ? null : prev))
      await onDataChanged()
      await loadAll()
      setNotice(`已将 ${count} 部作品归入系列「${finalName}」并移出重复提示`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleRestoreIgnoredDups = () => {
    setIgnoredDupKeys([])
    saveIgnoredSet(IGNORED_DUP_KEY, [])
    setNotice('已恢复所有被忽略的疑似重复提示')
  }

  const toggleSeriesMember = (groupKey: string, memberKey: string) => {
    setExcludedSeriesMembers((prev) => {
      const cur = new Set(prev[groupKey] ?? [])
      if (cur.has(memberKey)) {
        cur.delete(memberKey)
      } else {
        cur.add(memberKey)
      }
      return { ...prev, [groupKey]: Array.from(cur) }
    })
  }

  const applyOneSeriesGroup = async (group: SeriesSuggestionGroup): Promise<number> => {
    const finalName = (seriesNameEdits[group.groupKey] ?? group.seriesName).trim()
    if (!finalName) {
      throw new Error('系列名称不能为空')
    }
    const excluded = new Set(excludedSeriesMembers[group.groupKey] ?? [])
    const pending = group.members.filter(
      (m) => !m.alreadyGrouped && !excluded.has(`${m.kind}:${m.id}`)
    )
    if (pending.length === 0) return 0

    // 按建议定位分批归入同一系列，让本篇/续作/剧场版/原作书籍自动带上清晰角色标记
    const byRole = new Map<string, [MediaCarrier, number][]>()
    for (const m of pending) {
      const role = m.suggestedRole || '系列成员'
      const list = byRole.get(role) ?? []
      list.push([m.kind, m.id])
      byRole.set(role, list)
    }
    let totalAssigned = 0
    for (const [role, targets] of byRole.entries()) {
      totalAssigned += await studioApi.batchSeries(targets, finalName, role)
    }
    return totalAssigned
  }

  const handleApplySeriesGroup = async (group: SeriesSuggestionGroup) => {
    const finalName = (seriesNameEdits[group.groupKey] ?? group.seriesName).trim()
    setBusyMsg(`正在归类至系列「${finalName}」...`)
    setErrorMsg(null)
    try {
      const count = await applyOneSeriesGroup(group)
      if (count === 0) {
        setNotice('该建议组中暂无勾选的待归类作品')
        return
      }
      await onDataChanged()
      await loadAll()
      setNotice(`已将 ${count} 部作品归入系列「${finalName}」`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleApplyAllSeries = async () => {
    if (visibleSeriesSuggestions.length === 0) return
    setBusyMsg(`正在批量归类 ${visibleSeriesSuggestions.length} 组同系列作品...`)
    setErrorMsg(null)
    try {
      let totalWorks = 0
      let appliedGroups = 0
      for (const group of visibleSeriesSuggestions) {
        const c = await applyOneSeriesGroup(group)
        if (c > 0) {
          totalWorks += c
          appliedGroups += 1
        }
      }
      await onDataChanged()
      await loadAll()
      setNotice(`一键归类完成：共处理 ${appliedGroups} 个系列，归入 ${totalWorks} 部作品`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  const handleDismissSeries = (group: SeriesSuggestionGroup) => {
    const next = Array.from(new Set([...ignoredSeriesKeys, group.groupKey]))
    setIgnoredSeriesKeys(next)
    saveIgnoredSet(IGNORED_SERIES_KEY, next)
    setNotice(`已忽略「${group.seriesName}」的自动归类建议`)
  }

  const handleRestoreIgnoredSeries = () => {
    setIgnoredSeriesKeys([])
    saveIgnoredSet(IGNORED_SERIES_KEY, [])
    setNotice('已恢复全部被忽略的同系列归类建议')
  }

  const handleImportCsv = async () => {
    const path = await studioApi.pickCsvFile()
    if (!path) return
    setBusyMsg('正在解析并批量导入 CSV...')
    setErrorMsg(null)
    setCsvReport(null)
    try {
      const report = await studioApi.importCsv(path)
      setCsvReport(report)
      await onDataChanged()
      await loadAll()
      setNotice(
        `CSV 导入完成：新增 ${report.imported} 项，自动跳过重复 ${report.skippedDuplicates} 项`
      )
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyMsg(null)
    }
  }

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: modalZ,
        background: 'rgba(15, 16, 20, 0.56)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 20
      }}
      onPointerDownCapture={bringToFront}
      onClick={onClose}
    >
      <div
        className="card-box"
        style={{
          width: 'min(1040px, 96vw)',
          maxHeight: '88vh',
          display: 'flex',
          flexDirection: 'column',
          padding: 0,
          overflow: 'hidden',
          boxShadow: '0 24px 60px rgba(0, 0, 0, 0.35)',
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 顶栏 */}
        <div
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
          style={{
            padding: '12px 16px',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            flexWrap: 'wrap',
            background: 'var(--bg-surface)',
            cursor: 'grab',
            userSelect: 'none'
          }}
        >
          <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
            藏品库整理与管理
          </div>
          <div className="segmented-group" style={{ flexWrap: 'wrap' }}>
            <button
              className={`segmented-btn ${section === 'series_manage' ? 'active' : ''}`}
              onClick={() => setSection('series_manage')}
            >
              系列管理{loadedSections.series_manage ? ` (${seriesManageList.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'series' ? 'active' : ''}`}
              onClick={() => setSection('series')}
            >
              智能归类{loadedSections.series ? ` (${visibleSeriesSuggestions.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'tags_manage' ? 'active' : ''}`}
              onClick={() => setSection('tags_manage')}
            >
              标签管理{loadedSections.tags_manage ? ` (${tagsManageList.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'characters_manage' ? 'active' : ''}`}
              onClick={() => setSection('characters_manage')}
            >
              角色管理{loadedSections.characters_manage ? ` (${charactersManageList.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'duplicates' ? 'active' : ''}`}
              onClick={() => setSection('duplicates')}
            >
              查重{loadedSections.duplicates ? ` (${visibleDuplicates.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'trash' ? 'active' : ''}`}
              onClick={() => setSection('trash')}
            >
              回收站{loadedSections.trash ? ` (${trash.length})` : ''}
            </button>
            <button
              className={`segmented-btn ${section === 'csv' ? 'active' : ''}`}
              onClick={() => setSection('csv')}
            >
              CSV 导入
            </button>
          </div>
          <div style={{ flex: 1 }} />
          <button
            className="btn"
            style={{ fontSize: '0.8846rem' }}
            disabled={loading}
            onClick={() => void loadAll()}
          >
            重新扫描
          </button>
          <button className="btn" onClick={onClose}>
            关闭
          </button>
        </div>

        {/* 提示栏 */}
        {(busyMsg || notice || errorMsg) && (
          <div
            style={{
              padding: '8px 16px',
              fontSize: '0.9231rem',
              borderBottom: '1px solid var(--border-subtle)',
              background: errorMsg ? 'rgba(181, 61, 47, 0.1)' : 'var(--accent-soft)',
              color: errorMsg ? 'var(--danger)' : 'var(--accent)',
              display: 'flex',
              alignItems: 'center',
              gap: 8
            }}
          >
            {busyMsg && <LoadingSpinner size="sm" />}
            <span>{errorMsg || busyMsg || notice}</span>
          </div>
        )}

        {/* 主内容区 */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
          {loading ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '0.9615rem', color: 'var(--text-muted)' }}>
                <LoadingSpinner size="sm" />
                <span>正在扫描藏品库...</span>
              </div>
              <SkeletonRows count={4} />
            </div>
          ) : section === 'trash' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
                  删除藏品时仅移入回收站，随时可在此无损还原；彻底清除后将永久移除。
                </span>
                {trash.length > 0 && (
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button
                      className="btn btn-primary"
                      disabled={Boolean(busyMsg)}
                      onClick={() => void handleRestoreAll()}
                    >
                      全部还原
                    </button>
                    <button
                      className="btn"
                      style={{ color: 'var(--danger)' }}
                      disabled={Boolean(busyMsg)}
                      onClick={() => void handlePurgeAll()}
                    >
                      清空回收站
                    </button>
                  </div>
                )}
              </div>

              {trash.length === 0 ? (
                <div className="empty-state" style={{ padding: 48 }}>
                  回收站空空如也。
                </div>
              ) : (
                <>
                  {trash.slice(0, trashLimit).map((item) => (
                    <div
                      key={`${item.kind}:${item.id}`}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        padding: '8px 12px',
                        borderRadius: 'var(--radius-sm)',
                        border: '1px solid var(--border-subtle)',
                        background: 'var(--bg-surface)'
                      }}
                    >
                      <span className={`badge badge-carrier-${item.kind}`}>
                        {formatCarrierLabel(item.kind)}
                      </span>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, fontSize: '1rem' }}>{item.title}</div>
                        <div style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                          原状态：{item.status}
                          {item.rating != null ? ` · 评分 ${item.rating.toFixed(1)}` : ''}
                          {' · 删除于 '}
                          {item.deletedAt.slice(0, 16).replace('T', ' ')}
                        </div>
                      </div>
                      <button
                        className="btn btn-primary"
                        style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                        disabled={Boolean(busyMsg)}
                        onClick={() => void handleRestoreOne(item)}
                      >
                        还原
                      </button>
                      <button
                        className="btn"
                        style={{ padding: '4px 10px', fontSize: '0.8846rem', color: 'var(--danger)' }}
                        disabled={Boolean(busyMsg)}
                        onClick={() => void handlePurgeOne(item)}
                      >
                        彻底删除
                      </button>
                    </div>
                  ))}
                  {trashLimit < trash.length && (
                    <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0' }}>
                      <button className="btn" onClick={() => setTrashLimit((v) => v + 50)}>
                        加载更多 ({Math.min(trashLimit, trash.length)} / {trash.length})
                      </button>
                      <button className="btn" onClick={() => setTrashLimit(trash.length)}>
                        显示全部
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          ) : section === 'duplicates' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 10,
                  flexWrap: 'wrap'
                }}
              >
                <div className="segmented-group">
                  <button
                    className={`segmented-btn ${dupFilter === 'all' ? 'active' : ''}`}
                    onClick={() => setDupFilter('all')}
                  >
                    全部 ({visibleDuplicates.length})
                  </button>
                  <button
                    className={`segmented-btn ${dupFilter === 'exact' ? 'active' : ''}`}
                    onClick={() => setDupFilter('exact')}
                  >
                    精确重复 ({exactDupCount})
                  </button>
                  <button
                    className={`segmented-btn ${dupFilter === 'suspected' ? 'active' : ''}`}
                    onClick={() => setDupFilter('suspected')}
                  >
                    疑似重复 ({suspectedDupCount})
                  </button>
                </div>
                {ignoredDupCount > 0 && (
                  <button
                    className="btn"
                    style={{ fontSize: '0.8846rem' }}
                    onClick={handleRestoreIgnoredDups}
                  >
                    恢复已忽略的 {ignoredDupCount} 组
                  </button>
                )}
              </div>

              <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                可自动识别不同平台命名差异（例如「孤独摇滚」与「孤独摇滚！」、全半角标点或原名一致），同时严格规避续季命名（如「xxx！」与「xxx！！」「第2季」「剧场版」「OVA」）。合并时会自动保留更高进度、评分、更长感想与封面，被淘汰项移入回收站，绝不擅自删除。
              </div>

              {filteredDuplicates.length === 0 ? (
                <div className="empty-state" style={{ padding: 48 }}>
                  {visibleDuplicates.length === 0
                    ? '未检测到重复或疑似重复藏品，藏品库非常整洁。'
                    : '当前筛选条件下没有重复组。'}
                </div>
              ) : (
                <>
                {filteredDuplicates.slice(0, dupLimit).map((group) => {
                  const keepId = selectedKeepMap[group.groupKey] ?? group.recommendedKeepId
                  const isSuspected = group.confidence === 'suspected'
                  const suggested = suggestSeriesNameForDupGroup(group, allItems, seriesManageList)
                  const dupSeriesDraft = dupSeriesNameEdits[group.groupKey] ?? suggested.name
                  const isDupSeriesOpen = dupSeriesOpenKey === group.groupKey
                  return (
                    <div
                      key={group.groupKey}
                      style={{
                        border: `1px solid ${isSuspected ? 'rgba(202, 138, 4, 0.38)' : 'var(--border-subtle)'}`,
                        borderRadius: 'var(--radius-md)',
                        padding: 12,
                        background: 'var(--bg-surface)',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 8
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span className={`badge badge-carrier-${group.kind}`}>
                          {formatCarrierLabel(group.kind)}
                        </span>
                        <span
                          className="badge"
                          style={
                            isSuspected
                              ? {
                                  background: 'var(--badge-amber-bg)',
                                  color: 'var(--badge-amber-fg)',
                                  borderColor: 'var(--badge-amber-border)'
                                }
                              : {
                                  background: 'rgba(181, 61, 47, 0.12)',
                                  color: 'var(--danger)',
                                  borderColor: 'rgba(181, 61, 47, 0.3)'
                                }
                          }
                        >
                          {isSuspected ? '疑似重复' : '精确重复'}
                        </span>
                        <strong style={{ fontSize: '1.0385rem' }}>{group.title}</strong>
                        <span className="badge">{group.reason}</span>
                        <div style={{ flex: 1 }} />
                        <button
                          className="btn"
                          style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                          disabled={Boolean(busyMsg)}
                          onClick={() => {
                            if (isDupSeriesOpen) {
                              setDupSeriesOpenKey(null)
                            } else {
                              setDupSeriesNameEdits((prev) => ({
                                ...prev,
                                [group.groupKey]: prev[group.groupKey] ?? suggested.name
                              }))
                              setDupSeriesOpenKey(group.groupKey)
                            }
                          }}
                          title="它们并非重复条目，而是同一系列的不同作品：按最大公共字部分或已有系列归为同系列"
                        >
                          {isDupSeriesOpen ? '收起归类' : '归为同系列'}
                        </button>
                        <button
                          className="btn"
                          style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                          disabled={Boolean(busyMsg)}
                          onClick={() => handleDismissDuplicate(group)}
                          title="确认它们不是同一部作品，以后不再提示这组"
                        >
                          并非重复
                        </button>
                        <button
                          className="btn btn-primary"
                          style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                          disabled={Boolean(busyMsg)}
                          onClick={() => void handleMergeGroup(group)}
                        >
                          合并保留 #{keepId}
                        </button>
                      </div>
                      {isDupSeriesOpen && (
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            flexWrap: 'wrap',
                            padding: '8px 10px',
                            borderRadius: 'var(--radius-sm)',
                            background: 'var(--bg-panel)',
                            border: '1px dashed var(--accent)'
                          }}
                        >
                          <span className="badge" style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}>
                            {suggested.fromExisting ? '匹配已有系列' : '最大公共字部分'}
                          </span>
                          <input
                            className="input-field"
                            style={{ width: 220, padding: '4px 8px', fontSize: '0.9615rem', fontWeight: 600 }}
                            value={dupSeriesDraft}
                            onChange={(e) =>
                              setDupSeriesNameEdits((prev) => ({
                                ...prev,
                                [group.groupKey]: e.target.value
                              }))
                            }
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' && dupSeriesDraft.trim()) {
                                void handleAssignDupGroupAsSeries(group, dupSeriesDraft)
                              }
                            }}
                            placeholder="输入或选择系列名称..."
                          />
                          {seriesManageList.length > 0 && (
                            <StudioSelect
                              className="studio-select-inline studio-select-tag-cap"
                              value={
                                seriesManageList.some((s) => s.name === dupSeriesDraft)
                                  ? dupSeriesDraft
                                  : ''
                              }
                              placeholder="选择已有系列..."
                              ariaLabel="选择已有系列"
                              minMenuWidth={180}
                              maxMenuWidth={280}
                              onChange={(val) => {
                                if (val) {
                                  setDupSeriesNameEdits((prev) => ({
                                    ...prev,
                                    [group.groupKey]: val
                                  }))
                                }
                              }}
                              options={[
                                { value: '', label: '或选择已有系列...' },
                                ...seriesManageList.map((s) => ({
                                  value: s.name,
                                  label: `${s.name} (${s.memberCount})`
                                }))
                              ]}
                            />
                          )}
                          <button
                            className="btn btn-primary"
                            style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                            disabled={Boolean(busyMsg) || !dupSeriesDraft.trim()}
                            onClick={() => void handleAssignDupGroupAsSeries(group, dupSeriesDraft)}
                          >
                            确认归入系列 ({group.candidates.length} 部)
                          </button>
                        </div>
                      )}
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                        {group.candidates.map((cand) => (
                          <label
                            key={cand.id}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 8,
                              padding: '6px 8px',
                              borderRadius: 'var(--radius-sm)',
                              border: `1px solid ${
                                keepId === cand.id ? 'var(--accent)' : 'var(--border-subtle)'
                              }`,
                              background:
                                keepId === cand.id ? 'var(--accent-soft)' : 'var(--bg-canvas)',
                              cursor: 'pointer',
                              fontSize: '0.9231rem'
                            }}
                          >
                            <input
                              type="radio"
                              name={`keep-${group.groupKey}`}
                              checked={keepId === cand.id}
                              onChange={() =>
                                setSelectedKeepMap((prev) => ({
                                  ...prev,
                                  [group.groupKey]: cand.id
                                }))
                              }
                            />
                            <span className="mono" style={{ color: 'var(--text-muted)' }}>
                              #{cand.id}
                            </span>
                            <span style={{ fontWeight: 600 }}>{cand.title}</span>
                            {cand.originalTitle && cand.originalTitle !== cand.title && (
                              <span
                                style={{
                                  fontSize: '0.8462rem',
                                  color: 'var(--text-muted)',
                                  maxWidth: 180,
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  whiteSpace: 'nowrap'
                                }}
                                title={cand.originalTitle}
                              >
                                ({cand.originalTitle})
                              </span>
                            )}
                            <span className="badge">{cand.status}</span>
                            <span className="mono" style={{ fontSize: '0.8846rem' }}>
                              进度 {cand.progressPrimary}
                              {cand.progressTotal > 0 ? `/${cand.progressTotal}` : ''}
                            </span>
                            {cand.rating != null && (
                              <span className="mono" style={{ color: 'var(--accent)' }}>
                                ★ {cand.rating.toFixed(1)}
                              </span>
                            )}
                            {cand.reviewLen > 0 && (
                              <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                                感想 {cand.reviewLen} 字
                              </span>
                            )}
                            {cand.externalId ? (
                              <span className="badge mono" style={{ fontSize: '0.8077rem' }}>
                                {cand.externalId}
                              </span>
                            ) : (
                              <span style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                                手动/本地条目
                              </span>
                            )}
                            {cand.id === group.recommendedKeepId && (
                              <span className="badge badge-vault" style={{ marginLeft: 'auto' }}>
                                推荐保留
                              </span>
                            )}
                          </label>
                        ))}
                      </div>
                    </div>
                  )
                })}
                {dupLimit < filteredDuplicates.length && (
                  <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0' }}>
                    <button className="btn" onClick={() => setDupLimit((v) => v + 30)}>
                      加载更多 ({Math.min(dupLimit, filteredDuplicates.length)} / {filteredDuplicates.length})
                    </button>
                    <button className="btn" onClick={() => setDupLimit(filteredDuplicates.length)}>
                      显示全部
                    </button>
                  </div>
                )}
                </>
              )}
            </div>
          ) : section === 'series' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 10,
                  flexWrap: 'wrap'
                }}
              >
                <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', lineHeight: 1.6, flex: 1 }}>
                  自动扫描全库动画、书籍、游戏与音声，识别同一系列的多季续作（含感叹号续季如「轻音少女！/！！」、第X季、剧场版、原作轻小说与已有系列延伸）。归类前可自由修改系列名或取消勾选任意作品。
                </span>
                <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                  {ignoredSeriesCount > 0 && (
                    <button
                      className="btn"
                      style={{ fontSize: '0.8846rem' }}
                      onClick={handleRestoreIgnoredSeries}
                    >
                      恢复已忽略 ({ignoredSeriesCount})
                    </button>
                  )}
                  {visibleSeriesSuggestions.length > 0 && (
                    <button
                      className="btn btn-primary"
                      style={{ fontSize: '0.9231rem' }}
                      disabled={Boolean(busyMsg)}
                      onClick={() => void handleApplyAllSeries()}
                    >
                      一键应用全部建议 ({visibleSeriesSuggestions.length} 组)
                    </button>
                  )}
                </div>
              </div>

              {visibleSeriesSuggestions.length === 0 ? (
                <div className="empty-state" style={{ padding: 48 }}>
                  暂未发现待自动归类的同系列作品，您的系列企划已经很完整。
                </div>
              ) : (
                <>
                {visibleSeriesSuggestions.slice(0, seriesSugLimit).map((group) => {
                  const excluded = new Set(excludedSeriesMembers[group.groupKey] ?? [])
                  const editableName = seriesNameEdits[group.groupKey] ?? group.seriesName
                  const selectedPendingCount = group.members.filter(
                    (m) => !m.alreadyGrouped && !excluded.has(`${m.kind}:${m.id}`)
                  ).length
                  return (
                    <div
                      key={group.groupKey}
                      style={{
                        border: `1px solid ${
                          group.existingSeries ? 'var(--accent)' : 'var(--border-subtle)'
                        }`,
                        borderRadius: 'var(--radius-md)',
                        padding: 12,
                        background: 'var(--bg-surface)',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 8
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span
                          className={`badge ${group.existingSeries ? 'badge-vault' : ''}`}
                          style={
                            group.existingSeries
                              ? undefined
                              : { background: 'var(--accent-soft)', color: 'var(--accent)' }
                          }
                        >
                          {group.existingSeries ? '并入已有系列' : '新建同系列'}
                        </span>
                        <input
                          className="input-field"
                          style={{ width: 220, maxWidth: '100%', padding: '4px 8px', fontSize: '0.9615rem', fontWeight: 600 }}
                          value={editableName}
                          onChange={(e) =>
                            setSeriesNameEdits((prev) => ({
                              ...prev,
                              [group.groupKey]: e.target.value
                            }))
                          }
                          placeholder="输入系列名称"
                        />
                        <span className="badge">
                          共 {group.members.length} 部 · 待归入 {selectedPendingCount} 部
                        </span>
                        <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', overflowWrap: 'anywhere' }}>
                          {group.reason}
                        </span>
                        <div style={{ display: 'flex', gap: 8, flexShrink: 0, marginLeft: 'auto' }}>
                          <button
                            className="btn"
                            style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                            disabled={Boolean(busyMsg)}
                            onClick={() => handleDismissSeries(group)}
                          >
                            忽略
                          </button>
                          <button
                            className="btn btn-primary"
                            style={{ padding: '4px 10px', fontSize: '0.8846rem' }}
                            disabled={Boolean(busyMsg) || selectedPendingCount === 0 || !editableName.trim()}
                            onClick={() => void handleApplySeriesGroup(group)}
                          >
                            归入此系列 ({selectedPendingCount})
                          </button>
                        </div>
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                        {group.members.map((m) => {
                          const mKey = `${m.kind}:${m.id}`
                          const checked = !m.alreadyGrouped && !excluded.has(mKey)
                          return (
                            <label
                              key={mKey}
                              style={{
                                display: 'flex',
                                alignItems: 'center',
                                gap: 8,
                                padding: '5px 8px',
                                borderRadius: 'var(--radius-sm)',
                                border: '1px solid var(--border-subtle)',
                                background: m.alreadyGrouped
                                  ? 'transparent'
                                  : checked
                                  ? 'var(--bg-canvas)'
                                  : 'transparent',
                                opacity: m.alreadyGrouped ? 0.72 : checked ? 1 : 0.55,
                                cursor: m.alreadyGrouped ? 'default' : 'pointer',
                                fontSize: '0.9231rem'
                              }}
                            >
                              {m.alreadyGrouped ? (
                                <span
                                  className="badge"
                                  style={{ fontSize: '0.8077rem' }}
                                  title={`已在系列「${m.currentSeriesName}」中`}
                                >
                                  已在系列中
                                </span>
                              ) : (
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={() => toggleSeriesMember(group.groupKey, mKey)}
                                />
                              )}
                              <span className={`badge badge-carrier-${m.kind}`}>
                                {formatCarrierLabel(m.kind)}
                              </span>
                              <span style={{ fontWeight: 600 }}>{m.title}</span>
                              <span className="badge">{m.status}</span>
                              {!m.alreadyGrouped && m.currentSeriesName && (
                                <span
                                  className="badge"
                                  style={{
                                    background: 'var(--badge-amber-bg)',
                                    color: 'var(--badge-amber-fg)',
                                    borderColor: 'var(--badge-amber-border)',
                                    fontSize: '0.8077rem'
                                  }}
                                  title="此前被拆分到了不同系列，勾选后将合并入当前目标系列并自动清理空系列"
                                >
                                  原系列：{m.currentSeriesName}
                                </span>
                              )}
                              {!m.alreadyGrouped && (
                                <span
                                  className="badge"
                                  style={{
                                    marginLeft: 'auto',
                                    background: 'var(--accent-soft)',
                                    color: 'var(--accent)',
                                    fontSize: '0.8077rem'
                                  }}
                                >
                                  建议定位：{m.suggestedRole}
                                </span>
                              )}
                            </label>
                          )
                        })}
                      </div>
                    </div>
                  )
                })}
                {seriesSugLimit < visibleSeriesSuggestions.length && (
                  <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0' }}>
                    <button className="btn" onClick={() => setSeriesSugLimit((v) => v + 30)}>
                      加载更多 ({Math.min(seriesSugLimit, visibleSeriesSuggestions.length)} / {visibleSeriesSuggestions.length})
                    </button>
                    <button
                      className="btn"
                      onClick={() => setSeriesSugLimit(visibleSeriesSuggestions.length)}
                    >
                      显示全部
                    </button>
                  </div>
                )}
                </>
              )}
            </div>
          ) : section === 'series_manage' ? (
            <SeriesManagePanel
              seriesList={seriesManageList}
              allItems={allItems}
              busy={Boolean(busyMsg)}
              onBusy={setBusyMsg}
              onNotice={(msg) => setNotice(msg)}
              onError={(msg) => setErrorMsg(msg || null)}
              onRefresh={async () => {
                await onDataChanged()
                await loadAll()
              }}
            />
          ) : section === 'tags_manage' ? (
            <TagManagePanel
              tags={tagsManageList}
              busy={Boolean(busyMsg)}
              onBusy={setBusyMsg}
              onNotice={(msg) => setNotice(msg)}
              onError={(msg) => setErrorMsg(msg || null)}
              onRefresh={async () => {
                await onDataChanged()
                await loadAll()
              }}
            />
          ) : section === 'characters_manage' ? (
            <CharacterManagePanel
              characters={charactersManageList}
              allItems={allItems}
              mediaBaseUrl={mediaBaseUrl}
              busy={Boolean(busyMsg)}
              focusCharacterId={initialCharacterId}
              focusSeq={initialCharacterSeq}
              onBusy={setBusyMsg}
              onNotice={(msg) => setNotice(msg)}
              onError={(msg) => setErrorMsg(msg || null)}
              onRefresh={async () => {
                await onDataChanged()
                await loadAll()
              }}
              onOpenCanvasCharacters={onOpenCanvasCharacters}
            />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ fontSize: '0.9615rem', color: 'var(--text-secondary)', lineHeight: 1.65 }}>
                支持导入追番喵导出的 CSV 报表，或自定义表头的 UTF-8 CSV 表格（自动识别「标题 / 载体 / 状态 / 主进度 / 总进度 / 个人评分 / 社团 / 日期」等列）。导入时若库中已存在同载体同名藏品将自动跳过，不会产生重复项。
              </div>
              <div>
                <button
                  className="btn btn-primary"
                  disabled={Boolean(busyMsg)}
                  onClick={() => void handleImportCsv()}
                >
                  选择 CSV 文件并批量导入...
                </button>
              </div>
              {csvReport && (
                <div
                  style={{
                    padding: 12,
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--border-subtle)',
                    background: 'var(--bg-surface)',
                    display: 'flex',
                    gap: 10,
                    flexWrap: 'wrap'
                  }}
                >
                  <span className="badge badge-vault mono">成功导入 {csvReport.imported} 项</span>
                  <span className="badge mono">跳过重复 {csvReport.skippedDuplicates} 项</span>
                  {csvReport.failedRows > 0 && (
                    <span className="badge mono" style={{ color: 'var(--danger)' }}>
                      解析异常 {csvReport.failedRows} 行
                    </span>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
