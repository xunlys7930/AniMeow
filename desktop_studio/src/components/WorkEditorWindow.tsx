import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getCurrentWindow } from '@tauri-apps/api/window'
import {
  formatArchiveCode,
  formatItemCarrierLabel,
  statusOptionsFor,
  studioApi
} from '../api'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import { startNativeWindowDragFromEvent } from '../utils/popout'
import { CoverImage } from './CoverImage'
import { StudioSelect } from './StudioSelect'
import { WorkExternalBindingCard } from './WorkExternalBindingModal'
import type { CellPatch, CuratorItem } from '../types'

interface WorkEditorWindowProps {
  item: CuratorItem
  mediaBaseUrl: string
  windowIndex: number
  focusSeq: number
  onClose: () => void
  onUpdateCell: (patch: CellPatch) => Promise<void>
  onRefreshItems: () => Promise<void>
  onPopOut?: () => void
  onDockToMain?: () => void
  standalone?: boolean
}

interface EditorDraft {
  title: string
  originalTitle: string
  status: string
  rawRating: string
  progressPrimary: string
  progressTotal: string
  bookVolumes: string
  bookChapters: string
  mediaFormat: string
  studioOrCircle: string
  releaseDate: string
  seriesName: string
  seriesRole: string
  coverUrl: string
  memo: string
  tags: string[]
}

function draftFromItem(item: CuratorItem): EditorDraft {
  return {
    title: item.title ?? '',
    originalTitle: item.originalTitle ?? '',
    status: item.status ?? '',
    rawRating: item.rawRating != null ? String(item.rawRating) : '',
    progressPrimary: String(item.progressPrimary ?? 0),
    progressTotal: String(item.progressTotal ?? 0),
    bookVolumes: item.bookVolumes != null ? String(item.bookVolumes) : '',
    bookChapters: item.bookChapters != null ? String(item.bookChapters) : '',
    mediaFormat: item.mediaFormat ?? '',
    studioOrCircle: item.studioOrCircle ?? '',
    releaseDate: item.releaseDate ?? '',
    seriesName: item.seriesName ?? '',
    seriesRole: item.seriesRole ?? '',
    coverUrl: item.coverUrl ?? '',
    memo: item.memo ?? '',
    tags: [...(item.tags ?? [])]
  }
}

function isSameDraft(a: EditorDraft, b: EditorDraft): boolean {
  return (
    a.title.trim() === b.title.trim() &&
    a.originalTitle.trim() === b.originalTitle.trim() &&
    a.status === b.status &&
    a.rawRating.trim() === b.rawRating.trim() &&
    a.progressPrimary.trim() === b.progressPrimary.trim() &&
    a.progressTotal.trim() === b.progressTotal.trim() &&
    a.bookVolumes.trim() === b.bookVolumes.trim() &&
    a.bookChapters.trim() === b.bookChapters.trim() &&
    a.mediaFormat.trim() === b.mediaFormat.trim() &&
    a.studioOrCircle.trim() === b.studioOrCircle.trim() &&
    a.releaseDate.trim() === b.releaseDate.trim() &&
    a.seriesName.trim() === b.seriesName.trim() &&
    a.seriesRole.trim() === b.seriesRole.trim() &&
    a.coverUrl.trim() === b.coverUrl.trim() &&
    a.memo === b.memo &&
    a.tags.join('\u0000') === b.tags.join('\u0000')
  )
}

/**
 * 独立可多开的作品资料编辑浮动窗口：
 * - 支持同时打开多个作品窗口对照修改，互不覆盖；
 * - 全字段脏检查：修改后直接关闭或按 Esc 时提示「保存并关闭 / 放弃修改 / 继续编辑」。
 */
export function WorkEditorWindow({
  item,
  mediaBaseUrl,
  windowIndex,
  focusSeq,
  onClose,
  onUpdateCell,
  onRefreshItems,
  onPopOut,
  onDockToMain,
  standalone = false
}: WorkEditorWindowProps) {
  const { zIndex: winZ, bringToFront } = useFloatingLayer(true, 'panel')
  const {
    offset,
    isMoved,
    resetOffset,
    dragHandlers
  } = useDraggablePanel({ open: true, resetOnOpen: false, minY: -50 })

  const [baseline, setBaseline] = useState<EditorDraft>(() => draftFromItem(item))
  const [draft, setDraft] = useState<EditorDraft>(() => draftFromItem(item))
  const [newTagInput, setNewTagInput] = useState('')
  const [saving, setSaving] = useState(false)
  const [rehydrating, setRehydrating] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [pendingGuardAction, setPendingGuardAction] = useState<null | 'close' | 'popout' | 'dock'>(null)

  const isDirty = useMemo(() => !isSameDraft(draft, baseline), [draft, baseline])
  const isDirtyRef = useRef(isDirty)
  isDirtyRef.current = isDirty
  const allowNativeCloseRef = useRef(false)

  // 独立窗口模式下拦截 Alt+F4 / 任务栏关窗，避免未保存草稿被绕过提醒静默丢失
  useEffect(() => {
    if (!standalone) return
    let unlisten: (() => void) | null = null
    let disposed = false
    try {
      void getCurrentWindow()
        .onCloseRequested((event) => {
          if (isDirtyRef.current && !allowNativeCloseRef.current) {
            event.preventDefault()
            setPendingGuardAction('close')
          }
        })
        .then((fn) => {
          if (disposed) {
            fn()
          } else {
            unlisten = fn
          }
        })
        .catch(() => {})
    } catch {
      // 非 Tauri 环境忽略
    }
    return () => {
      disposed = true
      unlisten?.()
    }
  }, [standalone])

  // 当用户再次触发编辑同一作品时将其置顶
  useEffect(() => {
    if (focusSeq > 0) bringToFront()
  }, [focusSeq, bringToFront])

  // 外部数据更新（如在线补全或绑定外部条目）且当前未手改时同步刷新表单
  const itemSnapshotKey = useMemo(
    () => JSON.stringify(draftFromItem(item)),
    [item]
  )
  useEffect(() => {
    const next = draftFromItem(item)
    setBaseline(next)
    setDraft((prev) => (isSameDraft(prev, baseline) ? next : prev))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemSnapshotKey])

  const patchDraft = <K extends keyof EditorDraft>(key: K, val: EditorDraft[K]) => {
    setDraft((prev) => ({ ...prev, [key]: val }))
    setErrorMsg(null)
  }

  const handleAddTag = () => {
    const pieces = newTagInput
      .split(/[,，;；\n]+/)
      .map((s) => s.trim().replace(/^#+/, '').trim())
      .filter(Boolean)
    if (pieces.length === 0) return
    const existingLower = new Set(draft.tags.map((t) => t.toLowerCase()))
    const merged = [...draft.tags]
    for (const p of pieces) {
      if (!existingLower.has(p.toLowerCase())) {
        existingLower.add(p.toLowerCase())
        merged.push(p)
      }
    }
    setNewTagInput('')
    patchDraft('tags', merged)
  }

  const handleSave = useCallback(async (): Promise<boolean> => {
    if (saving) return false
    const cleanTitle = draft.title.trim()
    if (!cleanTitle) {
      setErrorMsg('作品标题不能为空')
      return false
    }
    if (isSameDraft(draft, baseline)) {
      setFeedback('当前内容无改动')
      window.setTimeout(() => setFeedback(null), 1800)
      return true
    }
    setSaving(true)
    setErrorMsg(null)
    setFeedback(null)
    try {
      // 字段级最小化写入：仅提交用户实际修改的字段，避免覆盖未动字段的来源标记或刷新 statusChangedAt
      const patch: CellPatch = {
        kind: item.kind,
        id: item.id
      }
      if (cleanTitle !== baseline.title.trim()) {
        patch.title = cleanTitle
      }
      if (draft.originalTitle.trim() !== baseline.originalTitle.trim()) {
        patch.originalTitle = draft.originalTitle.trim()
      }
      if (draft.status !== baseline.status) {
        patch.status = draft.status || item.status
      }
      if (draft.rawRating.trim() !== baseline.rawRating.trim()) {
        const parsedRating =
          draft.rawRating.trim() === '' ? 0 : Number.parseInt(draft.rawRating, 10)
        if (!Number.isNaN(parsedRating)) {
          patch.rawRating = Math.max(0, Math.min(100, parsedRating))
        }
      }
      if (draft.progressPrimary.trim() !== baseline.progressPrimary.trim()) {
        const parsedProgress = Number.parseInt(draft.progressPrimary, 10)
        if (!Number.isNaN(parsedProgress)) {
          patch.progressPrimary = Math.max(0, parsedProgress)
        }
      }
      if (item.kind !== 'game' && draft.progressTotal.trim() !== baseline.progressTotal.trim()) {
        const parsedTotal = Number.parseInt(draft.progressTotal, 10)
        if (!Number.isNaN(parsedTotal)) {
          patch.progressTotal = Math.max(0, parsedTotal)
        }
      }
      if (draft.studioOrCircle.trim() !== baseline.studioOrCircle.trim()) {
        patch.studioOrCircle = draft.studioOrCircle.trim()
      }
      if (draft.releaseDate.trim() !== baseline.releaseDate.trim()) {
        patch.releaseDate = draft.releaseDate.trim()
      }
      if (draft.coverUrl.trim() !== baseline.coverUrl.trim()) {
        patch.coverUrl = draft.coverUrl.trim()
      }
      if (draft.tags.join('\u0000') !== baseline.tags.join('\u0000')) {
        patch.tags = draft.tags
      }
      if (item.kind === 'book') {
        if (draft.bookVolumes.trim() !== baseline.bookVolumes.trim()) {
          patch.bookVolumes =
            draft.bookVolumes.trim() === ''
              ? -1
              : Math.max(0, Number.parseInt(draft.bookVolumes, 10) || 0)
        }
        if (draft.bookChapters.trim() !== baseline.bookChapters.trim()) {
          patch.bookChapters =
            draft.bookChapters.trim() === ''
              ? -1
              : Math.max(0, Number.parseInt(draft.bookChapters, 10) || 0)
        }
        if (draft.mediaFormat.trim() !== baseline.mediaFormat.trim()) {
          patch.mediaFormat = draft.mediaFormat.trim()
        }
      }
      if (item.kind === 'anime' || item.kind === 'book') {
        if (draft.seriesName.trim() !== baseline.seriesName.trim()) {
          patch.seriesName = draft.seriesName.trim()
        }
        if (draft.seriesRole.trim() !== baseline.seriesRole.trim()) {
          patch.seriesRole = draft.seriesRole.trim()
        }
        if (draft.memo !== baseline.memo) {
          patch.memo = draft.memo
        }
      }
      await onUpdateCell(patch)
      const normalizedRating =
        patch.rawRating === 0
          ? ''
          : patch.rawRating !== undefined
            ? String(patch.rawRating)
            : draft.rawRating.trim()
      const committed: EditorDraft = {
        ...draft,
        title: cleanTitle,
        originalTitle: draft.originalTitle.trim(),
        rawRating: normalizedRating,
        studioOrCircle: draft.studioOrCircle.trim(),
        releaseDate: draft.releaseDate.trim(),
        coverUrl: draft.coverUrl.trim(),
        mediaFormat: draft.mediaFormat.trim(),
        seriesName: draft.seriesName.trim(),
        seriesRole: draft.seriesRole.trim()
      }
      setBaseline(committed)
      setDraft(committed)
      setFeedback('已保存修改')
      window.setTimeout(() => setFeedback(null), 2400)
      return true
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
      return false
    } finally {
      setSaving(false)
    }
  }, [baseline, draft, item.id, item.kind, item.status, onUpdateCell, saving])

  const proceedAction = useCallback(
    (act: 'close' | 'popout' | 'dock') => {
      setPendingGuardAction(null)
      if (act === 'popout') {
        onPopOut?.()
        return
      }
      allowNativeCloseRef.current = true
      if (act === 'dock') {
        onDockToMain?.()
        return
      }
      onClose()
    },
    [onClose, onDockToMain, onPopOut]
  )

  const requestClose = useCallback(() => {
    if (isDirty) {
      setPendingGuardAction('close')
      return
    }
    proceedAction('close')
  }, [isDirty, proceedAction])

  const requestPopOut = useCallback(() => {
    if (!onPopOut) return
    if (isDirty) {
      setPendingGuardAction('popout')
      return
    }
    proceedAction('popout')
  }, [isDirty, onPopOut, proceedAction])

  const requestDockToMain = useCallback(() => {
    if (!onDockToMain) return
    if (isDirty) {
      setPendingGuardAction('dock')
      return
    }
    proceedAction('dock')
  }, [isDirty, onDockToMain, proceedAction])

  const handleRehydrate = async () => {
    if (rehydrating) return
    setRehydrating(true)
    setErrorMsg(null)
    setFeedback(null)
    try {
      const res = await studioApi.rehydrateWorkFromRemote(item.kind, item.id)
      await onRefreshItems()
      if (res.updatedFields.length > 0) {
        setFeedback(`已补全：${res.updatedFields.join('、')}`)
      } else {
        setFeedback(res.summary || '当前资料已完整，无需补全')
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setRehydrating(false)
    }
  }

  const baseTop = 62 + (windowIndex % 6) * 26
  const baseLeft = Math.max(24, Math.round(window.innerWidth * 0.22) + (windowIndex % 6) * 30)

  return (
    <section
      className="card-box floating-editor-window"
      role="dialog"
      aria-label={`编辑作品：${item.title}`}
      onPointerDownCapture={standalone ? undefined : bringToFront}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.stopPropagation()
          if (pendingGuardAction) {
            setPendingGuardAction(null)
          } else {
            requestClose()
          }
        } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
          e.preventDefault()
          void handleSave()
        }
      }}
      style={
        standalone
          ? {
              position: 'relative',
              width: '100%',
              height: '100%',
              maxHeight: '100%',
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
              top: baseTop,
              left: baseLeft,
              width: 'min(580px, calc(100vw - 40px))',
              maxHeight: 'calc(100vh - 84px)',
              padding: 0,
              zIndex: winZ,
              border: `1px solid ${isDirty ? 'var(--accent)' : 'var(--border-strong)'}`,
              boxShadow: '0 18px 48px rgba(0, 0, 0, 0.28)',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
              transform: isMoved ? `translate(${offset.x}px, ${offset.y}px)` : undefined
            }
      }
    >
      {/* 拖拽标题栏 */}
      <div
        {...(standalone ? {} : dragHandlers)}
        onMouseDown={standalone ? startNativeWindowDragFromEvent : undefined}
        title={
          standalone
            ? '按住标题栏可拖到桌面任意位置或副屏，双击最大化'
            : '按住标题栏自由拖动窗口，双击归位；可同时打开多个作品编辑窗'
        }
        style={{
          padding: '8px 12px',
          background: 'var(--bg-panel)',
          borderBottom: '1px solid var(--border-subtle)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          cursor: 'grab',
          userSelect: 'none',
          flexShrink: 0
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>⋮⋮</span>
          <span className={`badge badge-carrier-${item.kind}`}>{formatItemCarrierLabel(item)}</span>
          <strong
            className="heading-font"
            style={{
              fontSize: '0.9615rem',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap'
            }}
            title={item.title}
          >
            编辑 · {item.title}
          </strong>
          <span className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
            {formatArchiveCode(item)}
          </span>
          {isDirty && (
            <span className="badge badge-accent" style={{ fontSize: '0.7692rem' }}>
              未保存
            </span>
          )}
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
          {!standalone && onPopOut && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 24, fontSize: '0.8462rem' }}
              title="弹出为独立系统窗口，可拖出应用到副屏"
              onClick={requestPopOut}
            >
              独立窗口
            </button>
          )}
          {standalone && onDockToMain && (
            <button
              type="button"
              className="btn btn-ghost"
              style={{ height: 24, fontSize: '0.8462rem' }}
              title="将此作品编辑窗收回主窗口内显示"
              onClick={requestDockToMain}
            >
              收回主窗
            </button>
          )}
          {!standalone && isMoved && (
            <button type="button" className="btn btn-ghost" style={{ height: 24, fontSize: '0.8462rem' }} onClick={resetOffset}>
              归位
            </button>
          )}
          <button
            type="button"
            className="btn btn-ghost"
            style={{ height: 24 }}
            onClick={requestClose}
            title="关闭编辑窗口 (Esc)"
          >
            ✕
          </button>
        </div>
      </div>

      {/* 未保存关闭/弹出/收回三选提醒条 */}
      {pendingGuardAction && (
        <div
          role="alertdialog"
          style={{
            padding: '10px 14px',
            background: 'color-mix(in srgb, var(--accent) 12%, var(--bg-panel))',
            borderBottom: '1px solid var(--accent)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
            flexWrap: 'wrap',
            flexShrink: 0
          }}
        >
          <span style={{ fontSize: '0.9231rem', fontWeight: 600 }}>
            {pendingGuardAction === 'popout'
              ? '当前有未保存的修改，弹出独立窗口前要先保存吗？'
              : pendingGuardAction === 'dock'
                ? '当前有未保存的修改，收回主窗口前要先保存吗？'
                : '当前有未保存的修改，关闭前要保存吗？'}
          </span>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              type="button"
              className="btn btn-primary"
              disabled={saving}
              onClick={async () => {
                const ok = await handleSave()
                if (ok) proceedAction(pendingGuardAction)
              }}
            >
              {pendingGuardAction === 'popout'
                ? '保存并弹出'
                : pendingGuardAction === 'dock'
                  ? '保存并收回'
                  : '保存并关闭'}
            </button>
            <button
              type="button"
              className="btn"
              style={{ color: 'var(--danger)' }}
              onClick={() => proceedAction(pendingGuardAction)}
            >
              {pendingGuardAction === 'popout'
                ? '放弃修改并弹出'
                : pendingGuardAction === 'dock'
                  ? '放弃修改并收回'
                  : '放弃修改'}
            </button>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => setPendingGuardAction(null)}
            >
              继续编辑
            </button>
          </div>
        </div>
      )}

      {/* 表单主体 */}
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: 14,
          display: 'flex',
          flexDirection: 'column',
          gap: 12
        }}
      >
        {/* 顶部封面预览 + 标题/原名 */}
        <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
          <div
            style={{
              width: 72,
              height: 100,
              borderRadius: 'var(--radius-sm)',
              overflow: 'hidden',
              background: 'var(--bg-canvas)',
              border: '1px solid var(--border-strong)',
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: '0.8077rem',
              color: 'var(--text-muted)'
            }}
          >
            <CoverImage
              coverUrl={draft.coverUrl}
              mediaBaseUrl={mediaBaseUrl}
              alt={draft.title || item.title}
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              fallback={<span>无封面</span>}
            />
          </div>
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>作品标题</span>
              <input
                className="inline-input"
                value={draft.title}
                onChange={(e) => patchDraft('title', e.target.value)}
              />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>原名 / 外文名</span>
              <input
                className="inline-input"
                value={draft.originalTitle}
                onChange={(e) => patchDraft('originalTitle', e.target.value)}
                placeholder="可选"
              />
            </label>
          </div>
        </div>

        {/* 状态、评分、进度 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>状态</span>
            <StudioSelect
              className="studio-select-inline studio-select-full"
              value={draft.status}
              onChange={(val) => patchDraft('status', val)}
              zIndex={winZ + 40}
              ariaLabel="作品状态"
              minMenuWidth={140}
              maxMenuWidth={220}
              options={[
                ...(!statusOptionsFor(item.kind).includes(draft.status)
                  ? [{ value: draft.status, label: draft.status }]
                  : []),
                ...statusOptionsFor(item.kind).map((st) => ({ value: st, label: st }))
              ]}
            />
          </div>

          <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
              个人评分 (1–100，如 92 = 9.2)
            </span>
            <input
              className="inline-input tabular"
              type="number"
              min={0}
              max={100}
              value={draft.rawRating}
              onChange={(e) => patchDraft('rawRating', e.target.value)}
              placeholder="空或 0 为未评"
            />
          </label>

          <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
              {item.kind === 'game'
                ? '游玩分钟数'
                : item.kind === 'audio'
                  ? '已收听秒数'
                  : '当前进度 (集/章)'}
            </span>
            <input
              className="inline-input tabular"
              type="number"
              min={0}
              value={draft.progressPrimary}
              onChange={(e) => patchDraft('progressPrimary', e.target.value)}
            />
          </label>

          {item.kind !== 'game' && (
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
                {item.kind === 'audio' ? '总时长 (秒)' : '总集数 / 总话数'}
              </span>
              <input
                className="inline-input tabular"
                type="number"
                min={0}
                value={draft.progressTotal}
                onChange={(e) => patchDraft('progressTotal', e.target.value)}
              />
            </label>
          )}
        </div>

        {/* 书籍专属字段 */}
        {item.kind === 'book' && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>书籍类型</span>
              <StudioSelect
                className="studio-select-inline studio-select-full"
                value={draft.mediaFormat}
                onChange={(val) => patchDraft('mediaFormat', val)}
                zIndex={winZ + 40}
                ariaLabel="书籍类型"
                minMenuWidth={110}
                maxMenuWidth={180}
                options={[
                  { value: '', label: '未指定' },
                  { value: '小说', label: '小说' },
                  { value: '漫画', label: '漫画' }
                ]}
              />
            </div>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>已读卷数</span>
              <input
                className="inline-input tabular"
                type="number"
                min={0}
                value={draft.bookVolumes}
                onChange={(e) => patchDraft('bookVolumes', e.target.value)}
                placeholder="可选"
              />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>已读话数</span>
              <input
                className="inline-input tabular"
                type="number"
                min={0}
                value={draft.bookChapters}
                onChange={(e) => patchDraft('bookChapters', e.target.value)}
                placeholder="可选"
              />
            </label>
          </div>
        )}

        {/* 制作方、日期、系列 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
              {item.kind === 'game' ? '开发商' : item.kind === 'audio' ? '社团' : '制作方 / 作者'}
            </span>
            <input
              className="inline-input"
              value={draft.studioOrCircle}
              onChange={(e) => patchDraft('studioOrCircle', e.target.value)}
              placeholder="可选"
            />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>放送 / 发售日期</span>
            <input
              className="inline-input"
              value={draft.releaseDate}
              onChange={(e) => patchDraft('releaseDate', e.target.value)}
              placeholder="YYYY-MM-DD"
            />
          </label>
        </div>

        {(item.kind === 'anime' || item.kind === 'book') && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>所属系列</span>
              <input
                className="inline-input"
                value={draft.seriesName}
                onChange={(e) => patchDraft('seriesName', e.target.value)}
                placeholder="留空则移出系列"
              />
            </label>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>系列定位</span>
              <input
                className="inline-input"
                value={draft.seriesRole}
                onChange={(e) => patchDraft('seriesRole', e.target.value)}
                placeholder="如 正传 / 剧场版 / 外传"
              />
            </label>
          </div>
        )}

        {/* 封面链接与本地选图 */}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
            封面图片链接（支持网络 URL 或选择本地图片）
          </span>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <input
              className="inline-input"
              style={{ flex: 1, minWidth: 0 }}
              value={draft.coverUrl}
              onChange={(e) => patchDraft('coverUrl', e.target.value)}
              placeholder="https://... 或点击右侧选择本地图片"
            />
            <button
              type="button"
              className="btn"
              style={{ whiteSpace: 'nowrap' }}
              onClick={async () => {
                try {
                  const picked = await studioApi.pickCoverImage()
                  if (!picked) return
                  const marker = await studioApi.importCustomCover(picked)
                  patchDraft('coverUrl', marker)
                  setFeedback('已导入本地封面，点击保存生效')
                } catch (err) {
                  setErrorMsg(err instanceof Error ? err.message : String(err))
                }
              }}
            >
              选本地图
            </button>
          </div>
        </label>

        {/* 标签编辑 */}
        <div
          style={{
            padding: 10,
            borderRadius: 'var(--radius-sm)',
            background: 'var(--bg-canvas)',
            border: '1px solid var(--border-subtle)',
            display: 'flex',
            flexDirection: 'column',
            gap: 6
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8846rem' }}>标签 ({draft.tags.length})</span>
            <span style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>回车添加，点 × 移除</span>
          </div>
          <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            {draft.tags.length === 0 ? (
              <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>暂无标签</span>
            ) : (
              draft.tags.map((tag) => (
                <span
                  key={tag}
                  className="badge"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 4, paddingRight: 4 }}
                >
                  #{tag}
                  <button
                    type="button"
                    onClick={() => patchDraft('tags', draft.tags.filter((t) => t !== tag))}
                    style={{
                      border: 'none',
                      background: 'transparent',
                      color: 'inherit',
                      cursor: 'pointer',
                      padding: '0 2px',
                      lineHeight: 1
                    }}
                  >
                    ×
                  </button>
                </span>
              ))
            )}
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <input
              className="inline-input"
              style={{ flex: 1, height: 28 }}
              value={newTagInput}
              onChange={(e) => setNewTagInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.nativeEvent.isComposing || e.keyCode === 229) return
                if (e.key === 'Enter') {
                  e.preventDefault()
                  handleAddTag()
                }
              }}
              placeholder="输入标签名（逗号可一次加多个）..."
            />
            <button type="button" className="btn" style={{ height: 28 }} onClick={handleAddTag}>
              + 添加
            </button>
          </div>
        </div>

        {(item.kind === 'anime' || item.kind === 'book') && (
          <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
              私人备注（随备份同步，不公开）
            </span>
            <textarea
              className="inline-input"
              style={{ height: 68, padding: 6, resize: 'vertical' }}
              maxLength={10000}
              value={draft.memo}
              onChange={(e) => patchDraft('memo', e.target.value)}
              placeholder="记录个人感受、购入信息、追更备注..."
            />
          </label>
        )}

        {/* 外部条目绑定卡片 */}
        <WorkExternalBindingCard
          key={`ed:${item.kind}:${item.id}`}
          item={item}
          onRefresh={onRefreshItems}
        />
      </div>

      {/* 底部操作栏 */}
      <div
        style={{
          padding: '8px 14px',
          background: 'var(--bg-panel)',
          borderTop: '1px solid var(--border-subtle)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          flexWrap: 'wrap',
          flexShrink: 0
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <button
            type="button"
            className="btn"
            disabled={rehydrating}
            onClick={() => void handleRehydrate()}
            title="在线补全缺失的封面、开播/发售日、标签、制作方与总集数，仅填空值"
          >
            {rehydrating ? '正在补全…' : '在线补全空缺资料'}
          </button>
          {errorMsg ? (
            <span style={{ fontSize: '0.8846rem', color: 'var(--danger)' }}>{errorMsg}</span>
          ) : feedback ? (
            <span style={{ fontSize: '0.8846rem', color: 'var(--positive)', fontWeight: 600 }}>
              {feedback}
            </span>
          ) : null}
        </div>
        <div style={{ display: 'flex', gap: 6 }}>
          <button type="button" className="btn" onClick={requestClose}>
            关闭
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={saving || !isDirty}
            onClick={() => void handleSave()}
          >
            {saving ? '保存中…' : '保存修改 (Ctrl+S)'}
          </button>
        </div>
      </div>
    </section>
  )
}
