import { useEffect, useMemo, useRef, useState } from 'react'
import { formatCarrierLabel, statusOptionsFor, studioApi } from '../api'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import { startNativeWindowDragFromEvent } from '../utils/popout'
import { matchLocalWorkWithSuspicion } from '../utils/titleMatch'
import { LoadingSpinner, SkeletonRows } from './LoadingIndicator'
import { StudioSelect } from './StudioSelect'
import type {
  CuratorItem,
  MediaCarrier,
  RemoteCatalogHit,
  StudioThemeId
} from '../types'

export type CommandPaletteTab = 'local' | 'online' | 'bangumi' | 'vndb' | 'dlsite' | 'manual'
type PaletteMode = 'local' | 'online' | 'manual'
type CarrierScope = 'all' | MediaCarrier
type UnifiedIntent = 'want' | 'doing' | 'done'

interface CommandPaletteModalProps {
  open: boolean
  initialTab: CommandPaletteTab
  items: CuratorItem[]
  onClose: () => void
  onSelectLocalItem: (item: CuratorItem) => void
  onSwitchTheme: (theme: StudioThemeId) => void
  onOpenQuickCapture: () => void
  onItemCreated: () => Promise<void>
  onPopOut?: () => void
  standalone?: boolean
}

const CARRIER_SCOPE_TABS: Array<{ value: CarrierScope; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'anime', label: '动画' },
  { value: 'book', label: '书籍' },
  { value: 'game', label: '游戏' },
  { value: 'audio', label: '音声' }
]

function resolveStatusForCarrier(kind: MediaCarrier, intent: UnifiedIntent): string {
  if (kind === 'game') {
    return intent === 'doing' ? '在玩' : intent === 'done' ? '已通关' : '想玩'
  }
  if (kind === 'audio') {
    return intent === 'doing' ? '在听' : intent === 'done' ? '听完' : '想听'
  }
  return intent === 'doing' ? '在看' : intent === 'done' ? '看完' : '想看'
}

function sourceDisplayLabel(src: string): string {
  const s = src.toLowerCase()
  if (s === 'bangumi') return 'Bangumi'
  if (s === 'vndb') return 'VNDB'
  if (s === 'dlsite') return 'DLsite'
  return src.toUpperCase()
}

function hitMatchScore(hit: RemoteCatalogHit, kwLower: string): number {
  const t = hit.title.trim().toLowerCase()
  const o = hit.originalTitle.trim().toLowerCase()
  if (t === kwLower || o === kwLower) return 100
  if (t.startsWith(kwLower) || o.startsWith(kwLower)) return 80
  if (t.includes(kwLower) || o.includes(kwLower)) return 60
  return 20
}

function normalizeInitialTab(initialTab: CommandPaletteTab): {
  mode: PaletteMode
  scope: CarrierScope
} {
  if (initialTab === 'local') return { mode: 'local', scope: 'all' }
  if (initialTab === 'manual') return { mode: 'manual', scope: 'all' }
  if (initialTab === 'vndb') return { mode: 'online', scope: 'game' }
  if (initialTab === 'dlsite') return { mode: 'online', scope: 'audio' }
  return { mode: 'online', scope: 'all' }
}

export function CommandPaletteModal({
  open,
  initialTab,
  items,
  onClose,
  onSelectLocalItem,
  onSwitchTheme,
  onOpenQuickCapture,
  onItemCreated,
  onPopOut,
  standalone = false
}: CommandPaletteModalProps) {
  // 归入 panel 浮动池：不再全屏遮罩锁死，可与作品详情、AI 助手、速记自由互点置顶
  const { zIndex: panelZ, bringToFront } = useFloatingLayer(open, 'panel')
  const {
    offset: panelOffset,
    isMoved: panelMoved,
    resetOffset: resetPanelOffset,
    dragHandlers: panelDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: true, minY: -50 })

  const [mode, setMode] = useState<PaletteMode>('local')
  const [carrierScope, setCarrierScope] = useState<CarrierScope>('all')
  const [query, setQuery] = useState<string>('')
  const [unifiedIntent, setUnifiedIntent] = useState<UnifiedIntent>('want')
  const [singleCarrierStatus, setSingleCarrierStatus] = useState<string>('想看')

  const [onlineHits, setOnlineHits] = useState<RemoteCatalogHit[]>([])
  const [searching, setSearching] = useState<boolean>(false)
  const [addingHitKey, setAddingHitKey] = useState<string | null>(null)
  const [ingestedMap, setIngestedMap] = useState<Record<string, string>>({})
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  // 手动离线建档表单
  const [manualKind, setManualKind] = useState<MediaCarrier>('anime')
  const [manualBookFormat, setManualBookFormat] = useState<string>('小说')
  const [manualTitle, setManualTitle] = useState<string>('')
  const [manualOrig, setManualOrig] = useState<string>('')
  const [manualCode, setManualCode] = useState<string>('')
  const [manualMaker, setManualMaker] = useState<string>('')
  const [manualTotal, setManualTotal] = useState<string>('')
  const [manualStatus, setManualStatus] = useState<string>('想看')

  const inputRef = useRef<HTMLInputElement | null>(null)
  const searchSeqRef = useRef<number>(0)

  useEffect(() => {
    if (open) {
      const init = normalizeInitialTab(initialTab)
      setMode(init.mode)
      setCarrierScope(init.scope)
      setErrorMsg(null)
      setNotice(null)
      window.setTimeout(() => inputRef.current?.focus(), 20)
    }
  }, [open, initialTab])

  useEffect(() => {
    if (carrierScope !== 'all') {
      const opts = statusOptionsFor(carrierScope)
      const preferred = resolveStatusForCarrier(carrierScope, unifiedIntent)
      setSingleCarrierStatus(opts.includes(preferred) ? preferred : opts[0])
    }
  }, [carrierScope, unifiedIntent])

  useEffect(() => {
    setManualStatus(statusOptionsFor(manualKind)[0])
  }, [manualKind])

  const localMatches = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q || q.startsWith('/')) return items.slice(0, 8)
    return items
      .filter((it) =>
        [it.title, it.originalTitle, it.studioOrCircle, it.seriesName ?? '', it.memo ?? '', ...it.tags]
          .join(' ')
          .toLowerCase()
          .includes(q)
      )
      .slice(0, 10)
  }, [items, query])

  const findLocalMatchForHit = (hit: RemoteCatalogHit): CuratorItem | null => {
    const hitId = hit.externalId.trim()
    const hitSrc = hit.externalSource.toLowerCase()
    const byId = items.find(
      (it) =>
        it.kind === hit.kind &&
        it.externalId &&
        it.externalId.trim().toLowerCase() === hitId.toLowerCase() &&
        (!it.externalSource || it.externalSource.toLowerCase() === hitSrc)
    )
    if (byId) return byId
    if (hit.kind === 'anime' || hit.kind === 'book' || hit.kind === 'game') {
      const numId = /^\d+$/.test(hitId) ? Number(hitId) : 0
      const res = matchLocalWorkWithSuspicion(items, hit.kind, numId, hit.title, hit.originalTitle)
      return res.exact
    }
    const normTitle = hit.title.trim().toLowerCase()
    return (
      items.find(
        (it) =>
          it.kind === hit.kind &&
          (it.title.trim().toLowerCase() === normTitle ||
            (hit.originalTitle.trim() &&
              it.originalTitle.trim().toLowerCase() === hit.originalTitle.trim().toLowerCase()))
      ) ?? null
    )
  }

  const runOnlineSearch = async (kwRaw: string, scopeOverride?: CarrierScope) => {
    const kw = kwRaw.trim()
    const targetScope = scopeOverride ?? carrierScope
    if (!kw) return

    const seq = ++searchSeqRef.current
    setSearching(true)
    setErrorMsg(null)
    setNotice(null)

    const isRjLike = /^rj\d{5,10}$/i.test(kw)
    const tasks: Array<Promise<RemoteCatalogHit[]>> = []

    if (targetScope === 'all') {
      tasks.push(studioApi.searchBangumi(kw, 'anime'))
      tasks.push(studioApi.searchBangumi(kw, 'book'))
      tasks.push(studioApi.searchBangumi(kw, 'game'))
      tasks.push(studioApi.searchVndbGames(kw))
      tasks.push(studioApi.searchBangumi(kw, 'audio'))
      if (isRjLike || /rj\d{5,10}/i.test(kw)) {
        tasks.push(studioApi.lookupDlsiteAudio(kw))
      }
    } else if (targetScope === 'anime') {
      tasks.push(studioApi.searchBangumi(kw, 'anime'))
    } else if (targetScope === 'book') {
      tasks.push(studioApi.searchBangumi(kw, 'book'))
    } else if (targetScope === 'game') {
      tasks.push(studioApi.searchBangumi(kw, 'game'))
      tasks.push(studioApi.searchVndbGames(kw))
    } else if (targetScope === 'audio') {
      tasks.push(studioApi.lookupDlsiteAudio(kw))
      if (!isRjLike) {
        tasks.push(studioApi.searchBangumi(kw, 'audio'))
      }
    }

    try {
      const settled = await Promise.allSettled(tasks)
      if (seq !== searchSeqRef.current) return

      const kwLower = kw.toLowerCase()
      const merged: RemoteCatalogHit[] = []
      const seen = new Set<string>()
      let failedCount = 0

      for (const res of settled) {
        if (res.status === 'fulfilled') {
          for (const h of res.value) {
            const dedupKey = `${h.kind}:${h.externalSource.toLowerCase()}:${h.externalId.toLowerCase()}`
            if (seen.has(dedupKey)) continue
            seen.add(dedupKey)
            merged.push(h)
          }
        } else {
          failedCount += 1
        }
      }

      // 在「全部」模式下按相关度与各载体均衡排序，避免某一载体把结果占满
      if (targetScope === 'all') {
        merged.sort((a, b) => {
          const sa = hitMatchScore(a, kwLower)
          const sb = hitMatchScore(b, kwLower)
          if (sb !== sa) return sb - sa
          const pa = a.publicScore ?? 0
          const pb = b.publicScore ?? 0
          return pb - pa
        })
      }

      setOnlineHits(merged)
      if (merged.length === 0) {
        if (failedCount === settled.length) {
          setErrorMsg('在线检索暂时失败，请检查网络后重试')
        } else {
          setNotice('没有找到匹配的作品，可尝试换原名搜索、切换载体或使用「手动离线建档」')
        }
      }
    } catch (err) {
      if (seq !== searchSeqRef.current) return
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      if (seq === searchSeqRef.current) {
        setSearching(false)
      }
    }
  }

  const handleLocalEnter = () => {
    const trimmed = query.trim()
    if (trimmed.toLowerCase().startsWith('/bgm ')) {
      const kw = trimmed.slice(5).trim()
      setMode('online')
      setCarrierScope('all')
      setQuery(kw)
      void runOnlineSearch(kw, 'all')
      return
    }
    if (trimmed.toLowerCase().startsWith('/vndb ')) {
      const kw = trimmed.slice(6).trim()
      setMode('online')
      setCarrierScope('game')
      setQuery(kw)
      void runOnlineSearch(kw, 'game')
      return
    }
    if (trimmed.toLowerCase().startsWith('/rj ') || /^rj\d{5,10}$/i.test(trimmed)) {
      const kw = trimmed.toLowerCase().startsWith('/rj ') ? trimmed.slice(4).trim() : trimmed
      setMode('online')
      setCarrierScope('audio')
      setQuery(kw)
      void runOnlineSearch(kw, 'audio')
      return
    }
    if (localMatches[0]) {
      onSelectLocalItem(localMatches[0])
      onClose()
    }
  }

  const getTargetStatusForHit = (hit: RemoteCatalogHit): string => {
    if (carrierScope !== 'all' && carrierScope === hit.kind) {
      return singleCarrierStatus
    }
    return resolveStatusForCarrier(hit.kind, unifiedIntent)
  }

  const handleIngestHit = async (hit: RemoteCatalogHit) => {
    const hitKey = `${hit.kind}:${hit.externalSource}:${hit.externalId}`
    if (addingHitKey) return
    const chosenStatus = getTargetStatusForHit(hit)
    setAddingHitKey(hitKey)
    setErrorMsg(null)
    try {
      const isRj = hit.kind === 'audio' && /^RJ\d+/i.test(hit.externalId)
      await studioApi.createItem({
        kind: hit.kind,
        title: hit.title,
        originalTitle: hit.originalTitle,
        coverUrl: hit.coverUrl,
        status: chosenStatus,
        totalEpisodes: hit.totalEpisodes,
        studioOrCircle: hit.studioOrCircle,
        releaseDate: hit.releaseDate,
        publicScore: hit.publicScore ?? undefined,
        externalSource: hit.externalSource,
        externalId: hit.externalId,
        workCode: isRj ? hit.externalId.toUpperCase() : undefined,
        voiceActors: isRj && hit.originalTitle ? hit.originalTitle : undefined,
        synopsis: hit.synopsis
      })
      setIngestedMap((prev) => ({ ...prev, [hitKey]: chosenStatus }))
      await onItemCreated()
      setNotice(`已将《${hit.title}》入库（${formatCarrierLabel(hit.kind)} · ${chosenStatus}）`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setAddingHitKey(null)
    }
  }

  const handleManualCreate = async () => {
    if (!manualTitle.trim()) return
    setErrorMsg(null)
    try {
      await studioApi.createItem({
        kind: manualKind,
        title: manualTitle.trim(),
        originalTitle: manualOrig.trim() || undefined,
        workCode: manualKind === 'audio' ? manualCode.trim() || undefined : undefined,
        studioOrCircle: manualMaker.trim() || undefined,
        totalEpisodes: Number.parseInt(manualTotal, 10) || 0,
        status: manualStatus,
        mediaFormat: manualKind === 'book' && manualBookFormat ? manualBookFormat : undefined
      })
      await onItemCreated()
      const createdTitle = manualTitle.trim()
      setManualTitle('')
      setManualOrig('')
      setManualCode('')
      setNotice(`已新建《${createdTitle}》并加入藏品库`)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  const hitCarrierSummary = useMemo(() => {
    if (onlineHits.length === 0) return ''
    const counts: Record<MediaCarrier, number> = { anime: 0, book: 0, game: 0, audio: 0 }
    for (const h of onlineHits) {
      counts[h.kind] = (counts[h.kind] ?? 0) + 1
    }
    const parts = (['anime', 'book', 'game', 'audio'] as MediaCarrier[])
      .filter((k) => counts[k] > 0)
      .map((k) => `${formatCarrierLabel(k)} ${counts[k]}`)
    return `共 ${onlineHits.length} 条（${parts.join(' · ')}）`
  }, [onlineHits])

  if (!open) return null

  return (
    <div
      className="card-box floating-tool-window"
      onPointerDownCapture={standalone ? undefined : bringToFront}
      style={
        standalone
          ? {
              position: 'relative',
              width: '100%',
              height: '100%',
              padding: 0,
              borderRadius: 0,
              border: 'none',
              boxShadow: 'none',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden'
            }
          : {
              position: 'fixed',
              top: 64,
              left: '50%',
              width: 'min(780px, calc(100vw - 32px))',
              padding: 0,
              overflow: 'hidden',
              zIndex: panelZ,
              border: '1px solid var(--border-strong)',
              boxShadow: '0 18px 46px rgba(0, 0, 0, 0.28)',
              transform: `translate(calc(-50% + ${panelOffset.x}px), ${panelOffset.y}px)`
            }
      }
    >
      {/* 独立顶部拖拽标题栏：任意空白处可拖动，双击归位 */}
      <div
        {...(standalone ? {} : panelDragHandlers)}
        onMouseDown={standalone ? startNativeWindowDragFromEvent : undefined}
        title={
          standalone
            ? '按住标题栏可拖到桌面任意位置或副屏，双击最大化'
            : '按住标题栏可自由拖动窗口，双击归位'
        }
        style={{
          padding: '7px 12px',
          background: 'var(--bg-panel)',
          borderBottom: '1px solid var(--border-subtle)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 8,
          cursor: 'grab',
          userSelect: 'none',
          flexShrink: 0
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>⋮⋮</span>
          <strong className="heading-font" style={{ fontSize: '0.9615rem' }}>
            检索与多源入库
          </strong>
          <span className="badge badge-accent mono">Ctrl+K</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {!standalone && onPopOut && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 24, fontSize: '0.8462rem' }}
              title="弹出为独立系统窗口，可拖出应用外或副屏"
              onClick={onPopOut}
            >
              独立窗口
            </button>
          )}
          {!standalone && panelMoved && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 24, fontSize: '0.8462rem' }}
              onClick={resetPanelOffset}
            >
              归位
            </button>
          )}
          <button type="button" className="btn btn-ghost mono" style={{ height: 24 }} onClick={onClose}>
            {standalone ? '关闭' : 'ESC 关闭'}
          </button>
        </div>
      </div>

      {/* 模式切换与载体切换条 */}
      <div
        style={{
          padding: '8px 12px',
          background: 'var(--bg-canvas)',
          borderBottom: '1px solid var(--border-subtle)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap'
        }}
      >
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
          <button
            type="button"
            className={`btn ${mode === 'local' ? 'btn-primary' : ''}`}
            onClick={() => setMode('local')}
          >
            本机定位 & 指令
          </button>
          <button
            type="button"
            className={`btn ${mode === 'online' ? 'btn-primary' : ''}`}
            onClick={() => setMode('online')}
          >
            在线检索入库
          </button>
          <button
            type="button"
            className={`btn ${mode === 'manual' ? 'btn-primary' : ''}`}
            onClick={() => setMode('manual')}
          >
            手动离线建档
          </button>
        </div>

        {mode === 'online' && (
          <div className="view-seg" role="group" aria-label="检索载体范围">
            {CARRIER_SCOPE_TABS.map((c) => (
              <button
                key={c.value}
                type="button"
                className={`view-seg-btn ${carrierScope === c.value ? 'active' : ''}`}
                onClick={() => {
                  setCarrierScope(c.value)
                  if (query.trim()) {
                    void runOnlineSearch(query, c.value)
                  }
                }}
              >
                {c.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {mode !== 'manual' && (
        <div
          style={{
            padding: '10px 12px',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            gap: 8,
            alignItems: 'center',
            flexWrap: 'wrap'
          }}
        >
          <input
            ref={inputRef}
            className="inline-input"
            style={{ flex: 1, minWidth: 200, height: 30 }}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return
              if (e.key !== 'Enter') return
              if (mode === 'online') void runOnlineSearch(query)
              else handleLocalEnter()
            }}
            placeholder={
              mode === 'local'
                ? '输入本地作品名跳转，或输入 /bgm 、/vndb 、RJ01122334 按 Enter 直达在线检索...'
                : carrierScope === 'all'
                  ? '输入作品名或 RJ 编号，按 Enter 同时搜索动画、书籍、游戏与音声...'
                  : carrierScope === 'audio'
                    ? '输入 RJ 编号（如 RJ01122334）或音声标题，按 Enter 搜索...'
                    : `输入${formatCarrierLabel(carrierScope)}标题或原名，按 Enter 在线搜索...`
            }
          />

          {mode === 'online' && (
            <>
              {carrierScope === 'all' ? (
                <StudioSelect
                  className="studio-select-inline"
                  value={unifiedIntent}
                  onChange={(val) => setUnifiedIntent(val as UnifiedIntent)}
                  ariaLabel="默认入库状态"
                  zIndex={panelZ + 40}
                  minMenuWidth={148}
                  maxMenuWidth={200}
                  options={[
                    { value: 'want', label: '入库为：想看/想玩/想听' },
                    { value: 'doing', label: '入库为：在看/在玩/在听' },
                    { value: 'done', label: '入库为：看完/通关/听完' }
                  ]}
                />
              ) : (
                <StudioSelect
                  className="studio-select-inline"
                  value={singleCarrierStatus}
                  onChange={(val) => setSingleCarrierStatus(val)}
                  ariaLabel="入库初始状态"
                  zIndex={panelZ + 40}
                  minMenuWidth={128}
                  maxMenuWidth={170}
                  options={statusOptionsFor(carrierScope).map((st) => ({
                    value: st,
                    label: `入库为：${st}`
                  }))}
                />
              )}
              <button
                type="button"
                className="btn btn-primary"
                disabled={searching || !query.trim()}
                onClick={() => void runOnlineSearch(query)}
              >
                {searching ? '检索中...' : '检索'}
              </button>
            </>
          )}
        </div>
      )}

      {(errorMsg || notice) && (
        <div
          style={{
            padding: '7px 12px',
            fontSize: '0.9231rem',
            borderBottom: '1px solid var(--border-subtle)',
            background: errorMsg ? 'rgba(181, 51, 41, 0.08)' : 'rgba(61, 105, 80, 0.08)',
            color: errorMsg ? 'var(--danger)' : 'var(--positive)',
            fontWeight: 600
          }}
        >
          {errorMsg || notice}
        </div>
      )}

      <div style={{ maxHeight: 410, overflowY: 'auto', padding: 12 }}>
        {mode === 'local' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              {localMatches.map((it) => (
                <div
                  key={`${it.kind}:${it.id}`}
                  onClick={() => {
                    onSelectLocalItem(it)
                    onClose()
                  }}
                  style={{
                    padding: '7px 10px',
                    border: '1px solid var(--border-subtle)',
                    borderRadius: 'var(--radius-sm)',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    cursor: 'pointer'
                  }}
                >
                  <div>
                    <span className="badge" style={{ marginRight: 6 }}>
                      {formatCarrierLabel(it.kind)}
                    </span>
                    <strong className="heading-font">{it.title}</strong>
                    <span style={{ marginLeft: 8, fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                      {it.status} · {it.studioOrCircle || '—'}
                    </span>
                  </div>
                  <span className="mono" style={{ fontSize: '0.8462rem', color: 'var(--text-secondary)' }}>
                    定位检视 ↵
                  </span>
                </div>
              ))}
            </div>

            <div style={{ borderTop: '1px dashed var(--border-subtle)', paddingTop: 8 }}>
              <div style={{ fontSize: '0.8462rem', color: 'var(--text-muted)', marginBottom: 6 }}>
                快捷前缀：<code>/bgm 葬送的芙莉莲</code> · <code>/vndb 白色相簿2</code> ·{' '}
                <code>/rj RJ01000000</code> · 多主题直切
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    onOpenQuickCapture()
                    onClose()
                  }}
                >
                  &gt; 呼出灵感速记与截图微窗 (Alt+Space)
                </button>
                {(
                  [
                    ['bunkobon', '主题：文库活版'],
                    ['sakuga', '主题：作画律表'],
                    ['halide', '主题：银盐暗房'],
                    ['cassette', '主题：秋叶原磁带机'],
                    ['hinoki', '主题：桧木书斋']
                  ] as const
                ).map(([tid, label]) => (
                  <button key={tid} type="button" className="btn" onClick={() => onSwitchTheme(tid)}>
                    {label}
                  </button>
                ))}
              </div>
            </div>
          </div>
        )}

        {mode === 'online' && (
          <>
            {searching && onlineHits.length === 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, padding: '10px 4px' }}>
                <LoadingSpinner
                  size="sm"
                  inline
                  label={
                    carrierScope === 'all'
                      ? '正在同时检索动画、书籍、游戏与音声资料...'
                      : `正在检索${formatCarrierLabel(carrierScope)}资料...`
                  }
                />
                <SkeletonRows count={4} />
              </div>
            ) : onlineHits.length === 0 ? (
              <div style={{ padding: 26, textAlign: 'center', color: 'var(--text-muted)', lineHeight: 1.7 }}>
                {carrierScope === 'all'
                  ? '输入作品关键词后按 Enter，将同时搜索动画、书籍、游戏与音声条目并汇总展示，一键即可入库。'
                  : carrierScope === 'audio'
                    ? '输入 RJ 编号（如 RJ01122334）或音声标题后按 Enter，自动拉取标题、社团、声优与封面。'
                    : `输入${formatCarrierLabel(carrierScope)}中文或外文标题后按 Enter，即可拉取封面、原名、日期与评分。`}
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {hitCarrierSummary && (
                  <div style={{ fontSize: '0.8462rem', color: 'var(--text-muted)', padding: '0 2px' }}>
                    {hitCarrierSummary}
                  </div>
                )}
                {onlineHits.map((hit) => {
                  const hitKey = `${hit.kind}:${hit.externalSource}:${hit.externalId}`
                  const localExisting = findLocalMatchForHit(hit)
                  const justStatus = ingestedMap[hitKey]
                  const isCollected = Boolean(localExisting || justStatus)
                  const collectedStatus = localExisting?.status ?? justStatus ?? ''
                  const isAddingThis = addingHitKey === hitKey
                  const targetStatus = getTargetStatusForHit(hit)

                  return (
                    <div
                      key={`${hitKey}:${hit.title}`}
                      style={{
                        padding: 10,
                        border: `1px solid ${isCollected ? 'var(--positive)' : 'var(--border-subtle)'}`,
                        borderRadius: 'var(--radius-sm)',
                        background: isCollected
                          ? 'color-mix(in srgb, var(--positive) 6%, var(--bg-surface))'
                          : undefined,
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 12,
                        transition: 'border-color 160ms ease, background 160ms ease'
                      }}
                    >
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                          <span className={`badge badge-carrier-${hit.kind}`}>
                            {formatCarrierLabel(hit.kind)}
                          </span>
                          <span className="badge mono">
                            {sourceDisplayLabel(hit.externalSource)} · {hit.externalId}
                          </span>
                          <strong className="heading-font" style={{ fontSize: '1.0385rem' }}>
                            {hit.title}
                          </strong>
                          {hit.publicScore != null && (
                            <span className="badge badge-accent mono">
                              均分 {hit.publicScore.toFixed(1)}
                            </span>
                          )}
                        </div>
                        <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', marginTop: 2 }}>
                          {hit.studioOrCircle ? `${hit.studioOrCircle} · ` : ''}
                          {hit.originalTitle}
                          {hit.releaseDate ? ` · ${hit.releaseDate}` : ''}
                          {hit.totalEpisodes > 0 && hit.kind !== 'audio'
                            ? ` · 共 ${hit.totalEpisodes} 话`
                            : ''}
                        </div>
                      </div>

                      {isCollected ? (
                        <button
                          type="button"
                          className="btn"
                          style={{
                            flexShrink: 0,
                            borderColor: 'var(--positive)',
                            color: 'var(--positive)',
                            fontWeight: 600
                          }}
                          title={localExisting ? '点击在右侧查看该作品详情' : '已加入本地藏品库'}
                          onClick={() => {
                            if (localExisting) {
                              onSelectLocalItem(localExisting)
                            }
                          }}
                        >
                          ✓ 已入库{collectedStatus ? ` · ${collectedStatus}` : ''}
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="btn btn-primary"
                          style={{ flexShrink: 0 }}
                          disabled={Boolean(addingHitKey)}
                          onClick={() => void handleIngestHit(hit)}
                        >
                          {isAddingThis ? (
                            <LoadingSpinner size="sm" inline label="入库中..." />
                          ) : (
                            `+ 入库 (${targetStatus})`
                          )}
                        </button>
                      )}
                    </div>
                  )
                })}
              </div>
            )}
          </>
        )}

        {mode === 'manual' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontSize: '0.885rem', color: 'var(--text-secondary)' }}>藏品载体</span>
              <StudioSelect
                className="studio-select-inline studio-select-full"
                value={manualKind}
                onChange={(val) => setManualKind(val as MediaCarrier)}
                ariaLabel="手动入库藏品载体"
                zIndex={panelZ + 40}
                minMenuWidth={150}
                maxMenuWidth={220}
                options={[
                  { value: 'anime', label: '动画番剧' },
                  { value: 'book', label: '轻小说 / 漫画' },
                  { value: 'game', label: 'Galgame / 游戏' },
                  { value: 'audio', label: '音声 / ASMR' }
                ]}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontSize: '0.885rem', color: 'var(--text-secondary)' }}>初始状态</span>
              <StudioSelect
                className="studio-select-inline studio-select-full"
                value={manualStatus}
                onChange={(val) => setManualStatus(val)}
                ariaLabel="手动入库初始状态"
                zIndex={panelZ + 40}
                minMenuWidth={140}
                maxMenuWidth={220}
                options={statusOptionsFor(manualKind).map((st) => ({
                  value: st,
                  label: st
                }))}
              />
            </div>

            {manualKind === 'book' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, gridColumn: '1 / -1' }}>
                <span style={{ fontSize: '0.885rem', color: 'var(--text-secondary)' }}>
                  书籍类型（与手机端互通）
                </span>
                <StudioSelect
                  className="studio-select-inline studio-select-full"
                  value={manualBookFormat}
                  onChange={(val) => setManualBookFormat(val)}
                  ariaLabel="书籍类型"
                  zIndex={panelZ + 40}
                  minMenuWidth={180}
                  maxMenuWidth={260}
                  options={[
                    { value: '小说', label: '小说 / 轻小说' },
                    { value: '漫画', label: '漫画' },
                    { value: '', label: '暂不指定' }
                  ]}
                />
              </div>
            )}

            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, gridColumn: '1 / -1' }}>
              <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>作品标题（必填）</span>
              <input
                className="inline-input"
                value={manualTitle}
                onChange={(e) => setManualTitle(e.target.value)}
                placeholder="输入中文或常用标题"
              />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>原名（可选）</span>
              <input
                className="inline-input"
                value={manualOrig}
                onChange={(e) => setManualOrig(e.target.value)}
                placeholder="日文或英文原名"
              />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
                {manualKind === 'audio' ? 'RJ 作品编号（可选）' : '总集数 / 总章数（可选）'}
              </span>
              {manualKind === 'audio' ? (
                <input
                  className="inline-input mono"
                  value={manualCode}
                  onChange={(e) => setManualCode(e.target.value)}
                  placeholder="如 RJ01122334"
                />
              ) : (
                <input
                  className="inline-input mono"
                  type="number"
                  min={0}
                  value={manualTotal}
                  onChange={(e) => setManualTotal(e.target.value)}
                  placeholder="如 12 或 24"
                />
              )}
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: 4, gridColumn: '1 / -1' }}>
              <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
                动画制作 / 游戏会社 / 音声社团（可选）
              </span>
              <input
                className="inline-input"
                value={manualMaker}
                onChange={(e) => setManualMaker(e.target.value)}
                placeholder="如 Madhouse / Key / 猫耳社"
              />
            </label>

            <div style={{ gridColumn: '1 / -1', display: 'flex', justifyContent: 'flex-end', marginTop: 4 }}>
              <button
                type="button"
                className="btn btn-primary"
                disabled={!manualTitle.trim()}
                onClick={handleManualCreate}
              >
                添加到藏品库
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
