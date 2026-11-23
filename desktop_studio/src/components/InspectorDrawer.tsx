import { useEffect, useState } from 'react'
import {
  formatArchiveCode,
  formatItemCarrierLabel,
  statusOptionsFor,
  studioApi
} from '../api'
import { CoverImage } from './CoverImage'
import { StudioSelect } from './StudioSelect'
import { WorkExternalBindingCard } from './WorkExternalBindingModal'
import { WorkReferencesPanel } from './WorkReferencesPanel'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import type { CellPatch, CuratorItem, VaultFileRow, WorkCharacterBrief } from '../types'

interface InspectorDrawerProps {
  item: CuratorItem | null
  mediaBaseUrl: string
  vaultRevision: number
  onClose: () => void
  onUpdateCell: (patch: CellPatch) => Promise<void>
  onOpenEditor?: (item: CuratorItem) => void
  onOpenReviewStudio: (item: CuratorItem) => void
  onWatchAnimeOnline?: (item: CuratorItem) => void
  onFocusInCanvas?: (item: CuratorItem) => void
  onOpenCharacterManage?: (characterId?: number | null) => void
  onCharactersChanged?: () => void
  onPlayVaultFile: (file: VaultFileRow, item: CuratorItem) => Promise<void>
  onVaultChanged: () => Promise<void>
  onRefreshItems: () => Promise<void>
}

export function InspectorDrawer({
  item,
  mediaBaseUrl,
  vaultRevision,
  onClose,
  onUpdateCell,
  onOpenEditor,
  onOpenReviewStudio,
  onWatchAnimeOnline,
  onFocusInCanvas,
  onOpenCharacterManage,
  onCharactersChanged,
  onPlayVaultFile,
  onVaultChanged,
  onRefreshItems
}: InspectorDrawerProps) {
  const [boundFiles, setBoundFiles] = useState<VaultFileRow[]>([])
  const [workCharacters, setWorkCharacters] = useState<WorkCharacterBrief[]>([])
  const [fetchingChars, setFetchingChars] = useState<boolean>(false)
  const [rehydrating, setRehydrating] = useState<boolean>(false)
  const [ratingInput, setRatingInput] = useState<string>('')
  const [progressInput, setProgressInput] = useState<string>('0')
  const [progressTotalInput, setProgressTotalInput] = useState<string>('0')
  const [volumesInput, setVolumesInput] = useState<string>('')
  const [chaptersInput, setChaptersInput] = useState<string>('')
  const [mediaFormatInput, setMediaFormatInput] = useState<string>('')
  const [memoInput, setMemoInput] = useState<string>('')
  const [tagsDraft, setTagsDraft] = useState<string[]>([])
  const [newTagInput, setNewTagInput] = useState<string>('')
  const [showMetaEdit, setShowMetaEdit] = useState<boolean>(false)
  const [titleInput, setTitleInput] = useState<string>('')
  const [originalTitleInput, setOriginalTitleInput] = useState<string>('')
  const [studioInput, setStudioInput] = useState<string>('')
  const [releaseDateInput, setReleaseDateInput] = useState<string>('')
  const [seriesNameInput, setSeriesNameInput] = useState<string>('')
  const [seriesRoleInput, setSeriesRoleInput] = useState<string>('')
  const [coverUrlInput, setCoverUrlInput] = useState<string>('')
  const [feedback, setFeedback] = useState<string | null>(null)

  // 悬浮窗层级与自由拖拽（复用统一 Hook，支持双击归位与视口防拖丢边界钳制）
  const { zIndex: panelZ, bringToFront } = useFloatingLayer(true, 'panel')
  const {
    offset,
    isMoved,
    resetOffset,
    dragHandlers: panelDragHandlers
  } = useDraggablePanel({ open: true, minY: -8 })

  useEffect(() => {
    if (item) {
      bringToFront()
    }
  }, [item?.kind, item?.id, bringToFront])

  useEffect(() => {
    if (!item) {
      setBoundFiles([])
      setWorkCharacters([])
      return
    }
    setRatingInput(item.rawRating != null ? String(item.rawRating) : '')
    setProgressInput(String(item.progressPrimary))
    setProgressTotalInput(String(item.progressTotal ?? 0))
    setVolumesInput(item.bookVolumes != null ? String(item.bookVolumes) : '')
    setChaptersInput(item.bookChapters != null ? String(item.bookChapters) : '')
    setMediaFormatInput(item.mediaFormat ?? '')
    setMemoInput(item.memo ?? '')
    setTagsDraft(item.tags ?? [])
    setNewTagInput('')
    setTitleInput(item.title ?? '')
    setOriginalTitleInput(item.originalTitle ?? '')
    setStudioInput(item.studioOrCircle ?? '')
    setReleaseDateInput(item.releaseDate ?? '')
    setSeriesNameInput(item.seriesName ?? '')
    setSeriesRoleInput(item.seriesRole ?? '')
    setCoverUrlInput(item.coverUrl ?? '')
    setFeedback(null)

    let cancelled = false
    studioApi
      .listVaultFiles(item.kind, item.id)
      .then((rows) => {
        if (!cancelled) setBoundFiles(rows)
      })
      .catch(() => {
        if (!cancelled) setBoundFiles([])
      })
    if (item.kind === 'anime' || item.kind === 'book') {
      studioApi
        .listWorkCharacters(item.id)
        .then((list) => {
          if (!cancelled) setWorkCharacters(list)
        })
        .catch(() => {
          if (!cancelled) setWorkCharacters([])
        })
    } else {
      setWorkCharacters([])
    }
    return () => {
      cancelled = true
    }
  }, [
    item?.kind,
    item?.id,
    item?.rawRating,
    item?.progressPrimary,
    item?.progressTotal,
    item?.bookVolumes,
    item?.bookChapters,
    item?.mediaFormat,
    item?.memo,
    item?.title,
    item?.originalTitle,
    item?.studioOrCircle,
    item?.releaseDate,
    item?.seriesName,
    item?.seriesRole,
    item?.coverUrl,
    item?.tags?.join('\u0000'),
    vaultRevision
  ])

  if (!item) {
    return (
      <aside
        className="studio-inspector"
        onPointerDownCapture={bringToFront}
        style={{
          zIndex: panelZ,
          transform: isMoved ? `translate(${offset.x}px, ${offset.y}px)` : undefined
        }}
      >
        <div
          className="inspector-drag-bar"
          {...panelDragHandlers}
          title="按住可自由拖动详情面板位置，双击归位"
        >
          <span style={{ fontSize: '0.8846rem', fontWeight: 700, color: 'var(--text-secondary)' }}>
            ⋮⋮ 作品详情
          </span>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            {isMoved && (
              <button className="btn btn-ghost" style={{ height: 22 }} onClick={resetOffset}>
                归位
              </button>
            )}
            <button className="btn btn-ghost" style={{ height: 22 }} onClick={onClose} title="关闭详情面板 (Esc / Ctrl+I)">
              ✕
            </button>
          </div>
        </div>
        <div style={{ padding: 24, color: 'var(--text-muted)', textAlign: 'center' }}>
          <div className="heading-font" style={{ fontWeight: 700, color: 'var(--text-primary)', marginBottom: 6 }}>
            尚未选中作品
          </div>
          <div style={{ fontSize: '0.9231rem', lineHeight: 1.6 }}>
            单击画册、看板或列表中的任意作品，即可在此快速修改标题、标签、评分、进度、备注与本机文件。
          </div>
        </div>
      </aside>
    )
  }

  const mp = item.mobilePassThrough
  const hasMobilePassThrough =
    Boolean(mp.broadcastSchedule) ||
    mp.mergedSourceCount > 0 ||
    Boolean(mp.readingChapterTitle) ||
    Boolean(mp.legadoChapterTitle) ||
    mp.bookmarkCount > 0

  const flashSaved = (msg = '已保存修改') => {
    setFeedback(msg)
    window.setTimeout(() => setFeedback(null), 2200)
  }

  const commitTags = async (nextTags: string[]) => {
    setTagsDraft(nextTags)
    await onUpdateCell({ kind: item.kind, id: item.id, tags: nextTags })
    flashSaved('标签已更新')
  }

  const handleAddTag = async () => {
    const pieces = newTagInput
      .split(/[,，;；\n]+/)
      .map((s) => s.trim().replace(/^#+/, '').trim())
      .filter(Boolean)
    if (pieces.length === 0) return
    const existingLower = new Set(tagsDraft.map((t) => t.toLowerCase()))
    const merged = [...tagsDraft]
    for (const p of pieces) {
      if (!existingLower.has(p.toLowerCase())) {
        existingLower.add(p.toLowerCase())
        merged.push(p)
      }
    }
    setNewTagInput('')
    await commitTags(merged)
  }

  const handleRemoveTag = async (tagToRemove: string) => {
    const next = tagsDraft.filter((t) => t !== tagToRemove)
    await commitTags(next)
  }

  const saveQuickFields = async () => {
    const parsedRating = ratingInput.trim() === '' ? 0 : Number.parseInt(ratingInput, 10)
    const parsedProgress = Number.parseInt(progressInput, 10)
    const parsedTotal = Number.parseInt(progressTotalInput, 10)
    const patch: CellPatch = {
      kind: item.kind,
      id: item.id,
      rawRating: Number.isNaN(parsedRating) ? undefined : Math.max(0, Math.min(100, parsedRating)),
      progressPrimary: Number.isNaN(parsedProgress) ? undefined : Math.max(0, parsedProgress),
      tags: tagsDraft,
    }
    if (item.kind !== 'game' && !Number.isNaN(parsedTotal)) {
      patch.progressTotal = Math.max(0, parsedTotal)
    }
    if (item.kind === 'book') {
      patch.bookVolumes =
        volumesInput.trim() === '' ? -1 : Math.max(0, Number.parseInt(volumesInput, 10) || 0)
      patch.bookChapters =
        chaptersInput.trim() === '' ? -1 : Math.max(0, Number.parseInt(chaptersInput, 10) || 0)
      patch.mediaFormat = mediaFormatInput.trim()
    }
    if (item.kind === 'anime' || item.kind === 'book') {
      patch.memo = memoInput
    }
    if (showMetaEdit) {
      if (titleInput.trim()) patch.title = titleInput.trim()
      patch.originalTitle = originalTitleInput.trim()
      patch.studioOrCircle = studioInput.trim()
      patch.releaseDate = releaseDateInput.trim()
      patch.coverUrl = coverUrlInput.trim()
      if (item.kind === 'anime' || item.kind === 'book') {
        patch.seriesName = seriesNameInput.trim()
        patch.seriesRole = seriesRoleInput.trim()
      }
    }
    await onUpdateCell(patch)
    flashSaved('已保存修改')
  }

  const handleUnbind = async (fileId: number) => {
    await studioApi.bindVaultFile(fileId, null, null)
    await onVaultChanged()
  }

  const missingCoreHints: string[] = []
  if (!item.coverUrl?.trim()) missingCoreHints.push('封面')
  if (!item.releaseDate?.trim()) missingCoreHints.push('日期')
  if (!item.tags || item.tags.length === 0) missingCoreHints.push('标签')
  if (!item.studioOrCircle?.trim()) missingCoreHints.push('制作方')
  if (item.kind !== 'game' && (!item.progressTotal || item.progressTotal <= 0)) {
    missingCoreHints.push(item.kind === 'book' ? '总卷/话数' : '总集数')
  }

  const handleRehydrateFromRemote = async () => {
    if (rehydrating) return
    setRehydrating(true)
    setFeedback(null)
    try {
      const res = await studioApi.rehydrateWorkFromRemote(item.kind, item.id)
      await onVaultChanged()
      if (res.updatedFields.length > 0) {
        setFeedback(`已补全：${res.updatedFields.join('、')}`)
      } else if (res.skippedMissingRemote.length > 0) {
        setFeedback(`暂未查到可补充的新资料（仍缺：${res.skippedMissingRemote.join('、')}）`)
      } else {
        setFeedback(res.summary || '当前资料已完整，无需补全')
      }
      window.setTimeout(() => setFeedback(null), 4200)
    } catch (err) {
      setFeedback(`补全失败：${String(err)}`)
      window.setTimeout(() => setFeedback(null), 4800)
    } finally {
      setRehydrating(false)
    }
  }

  return (
    <aside
      className="studio-inspector"
      onPointerDownCapture={bringToFront}
      style={{
        zIndex: panelZ,
        transform: isMoved ? `translate(${offset.x}px, ${offset.y}px)` : undefined
      }}
    >
      {/* 顶部拖拽握把栏：可任意拖动位置防遮挡，双击归位 */}
      <div
        className="inspector-drag-bar"
        {...panelDragHandlers}
        title="按住可自由拖动详情面板位置，双击归位"
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>⋮⋮</span>
          <span style={{ fontSize: '0.8846rem', fontWeight: 700, color: 'var(--text-secondary)' }}>
            作品详情 · {formatArchiveCode(item)}
          </span>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
          {onFocusInCanvas && (
            <button
              className="btn btn-ghost"
              style={{ height: 22, fontSize: '0.8462rem' }}
              onClick={() => onFocusInCanvas(item)}
              title="在画布中聚焦查看此作品的相关系列、角色或声优关联"
            >
              关联图谱
            </button>
          )}
          {isMoved && (
            <button
              className="btn btn-ghost"
              style={{ height: 22, fontSize: '0.8462rem' }}
              onClick={resetOffset}
              title="恢复右上角默认位置"
            >
              归位
            </button>
          )}
          <button
            className="btn btn-ghost"
            style={{ height: 22 }}
            onClick={onClose}
            title="收起作品详情 (Esc / Ctrl+I)"
          >
            ✕
          </button>
        </div>
      </div>

      <div className="inspector-scroll">
        <div style={{ padding: 14, borderBottom: '1px solid var(--border-subtle)', display: 'flex', gap: 12 }}>
          <div
            style={{
              width: 68,
              height: 94,
              borderRadius: 'var(--radius-sm)',
              background: 'var(--bg-panel)',
              border: '1px solid var(--border-strong)',
              overflow: 'hidden',
              flexShrink: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-muted)',
              fontSize: '0.8462rem'
            }}
          >
            <CoverImage
              coverUrl={item.coverUrl}
              mediaBaseUrl={mediaBaseUrl}
              alt={item.title}
              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
              fallback={<span>无封面</span>}
            />
          </div>

          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 4 }}>
              <span className="badge">{formatItemCarrierLabel(item)}</span>
              {item.seriesRole && <span className="badge badge-accent">{item.seriesRole}</span>}
            </div>
            <div
              className="heading-font"
              style={{ fontWeight: 700, fontSize: '1.1538rem', lineHeight: 1.35, wordBreak: 'break-word' }}
            >
              {item.title}
            </div>
            {item.originalTitle && (
              <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', marginTop: 2, wordBreak: 'break-word' }}>
                {item.originalTitle}
              </div>
            )}
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem', marginTop: 6 }}>
              {item.studioOrCircle || '未知制作方'} {item.releaseDate ? `· ${item.releaseDate}` : ''}
            </div>
          </div>
        </div>

        <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            {item.kind === 'anime' && onWatchAnimeOnline && (
              <button
                className="btn btn-primary"
                style={{ flex: 1 }}
                onClick={() => onWatchAnimeOnline(item)}
              >
                在线看番
              </button>
            )}
            <button
              className={`btn ${item.kind === 'anime' && onWatchAnimeOnline ? '' : 'btn-primary'}`}
              style={{ flex: 1 }}
              onClick={() => onOpenReviewStudio(item)}
            >
              撰写长评
            </button>
          </div>

          <button
            className={`btn ${!onOpenEditor && showMetaEdit ? 'btn-primary' : ''}`}
            style={{ width: '100%', justifyContent: 'space-between', height: 32, padding: '0 12px' }}
            onClick={() => {
              if (onOpenEditor) {
                onOpenEditor(item)
              } else {
                setShowMetaEdit((v) => !v)
              }
            }}
            title={
              onOpenEditor
                ? '在独立可拖动窗口中编辑作品完整资料（支持同时打开多个作品窗口，关闭前提示未保存修改）'
                : undefined
            }
          >
            <span style={{ fontWeight: 600 }}>
              {onOpenEditor
                ? '编辑作品资料'
                : showMetaEdit
                  ? '收起作品资料编辑'
                  : '编辑作品资料'}
            </span>
            <span style={{ fontSize: '0.8462rem', opacity: 0.82 }}>
              {onOpenEditor
                ? '独立窗口 · 支持多开'
                : showMetaEdit
                  ? '点击收起'
                  : '标题 / 原名 / 封面 / 系列 / 制作方'}
            </span>
          </button>

          <button
            className="btn"
            disabled={rehydrating}
            style={{
              width: '100%',
              justifyContent: 'space-between',
              height: 32,
              padding: '0 12px',
              borderColor: missingCoreHints.length > 0 ? 'var(--accent)' : undefined
            }}
            onClick={() => void handleRehydrateFromRemote()}
            title="在线补全缺失的封面、开播/发售日、标签、制作方与总集数，仅填空值，绝不覆盖你已手写的内容"
          >
            <span style={{ fontWeight: 600 }}>
              {rehydrating ? '正在补全资料与封面…' : '在线补全资料与封面'}
            </span>
            <span style={{ fontSize: '0.8462rem', opacity: 0.85 }}>
              {missingCoreHints.length > 0
                ? `缺 ${missingCoreHints.slice(0, 3).join('/')}`
                : '仅填空值'}
            </span>
          </button>

          {showMetaEdit && (
            <div
              className="card-box"
              style={{
                background: 'var(--bg-canvas)',
                padding: 12,
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
                borderColor: 'var(--accent)'
              }}
            >
              <div style={{ fontWeight: 600, fontSize: '0.9231rem' }}>作品资料编辑</div>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>作品标题</span>
                <input
                  className="inline-input"
                  value={titleInput}
                  onChange={(e) => setTitleInput(e.target.value)}
                />
              </label>
              <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>原名 / 外文名</span>
                <input
                  className="inline-input"
                  value={originalTitleInput}
                  onChange={(e) => setOriginalTitleInput(e.target.value)}
                  placeholder="可选"
                />
              </label>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
                    {item.kind === 'game' ? '开发商' : item.kind === 'audio' ? '社团' : '制作方 / 作者'}
                  </span>
                  <input
                    className="inline-input"
                    value={studioInput}
                    onChange={(e) => setStudioInput(e.target.value)}
                    placeholder="可选"
                  />
                </label>
                <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>放送 / 发售日期</span>
                  <input
                    className="inline-input"
                    value={releaseDateInput}
                    onChange={(e) => setReleaseDateInput(e.target.value)}
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
                      value={seriesNameInput}
                      onChange={(e) => setSeriesNameInput(e.target.value)}
                      placeholder="留空则移出系列"
                    />
                  </label>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>系列角色</span>
                    <input
                      className="inline-input"
                      value={seriesRoleInput}
                      onChange={(e) => setSeriesRoleInput(e.target.value)}
                      placeholder="如 正传 / 剧场版 / 外传"
                    />
                  </label>
                </div>
              )}
              <label style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>封面图片链接（支持网络 URL 或本地自定义封面）</span>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  <input
                    className="inline-input"
                    style={{ flex: 1, minWidth: 0 }}
                    value={coverUrlInput}
                    onChange={(e) => setCoverUrlInput(e.target.value)}
                    placeholder="https://... 或点击右侧选择本地图片"
                  />
                  <button
                    type="button"
                    className="btn"
                    style={{ padding: '4px 8px', fontSize: '0.8846rem', whiteSpace: 'nowrap' }}
                    onClick={async () => {
                      try {
                        const picked = await studioApi.pickCoverImage()
                        if (!picked) return
                        const marker = await studioApi.importCustomCover(picked)
                        setCoverUrlInput(marker)
                        await onUpdateCell({ kind: item.kind, id: item.id, coverUrl: marker })
                        setFeedback('已导入本地自定义封面并保存')
                      } catch (err) {
                        setFeedback(err instanceof Error ? err.message : String(err))
                      }
                    }}
                  >
                    选本地图
                  </button>
                </div>
              </label>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 2 }}>
                <span style={{ fontSize: '0.8846rem', color: 'var(--positive)' }}>{feedback ?? ''}</span>
                <button className="btn btn-primary" onClick={saveQuickFields}>
                  保存作品资料
                </button>
              </div>
            </div>
          )}

          {/* 标签管理：随时新增/删除，实时生效 */}
          <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 10 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
              <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>标签 ({tagsDraft.length})</span>
              <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>回车添加，点 × 移除</span>
            </div>
            <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap', marginBottom: 8 }}>
              {tagsDraft.length === 0 ? (
                <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>暂无标签，在下方输入即可添加</span>
              ) : (
                tagsDraft.map((tag) => (
                  <span
                    key={tag}
                    className="badge"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 4, paddingRight: 4 }}
                  >
                    #{tag}
                    <button
                      type="button"
                      onClick={() => handleRemoveTag(tag)}
                      title={`移除标签 ${tag}`}
                      style={{
                        border: 'none',
                        background: 'transparent',
                        color: 'inherit',
                        cursor: 'pointer',
                        padding: '0 2px',
                        fontSize: '0.8462rem',
                        lineHeight: 1,
                        opacity: 0.75
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
                style={{ flex: 1, height: 28, fontSize: '0.9231rem' }}
                value={newTagInput}
                onChange={(e) => setNewTagInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    void handleAddTag()
                  }
                }}
                placeholder="输入标签名（逗号可一次加多个）..."
              />
              <button className="btn" style={{ height: 28 }} onClick={() => void handleAddTag()}>
                + 添加
              </button>
            </div>
          </div>

          <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 12 }}>
            <div style={{ fontWeight: 600, fontSize: '0.9231rem', marginBottom: 8 }}>进度与评分</div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.885rem' }}>状态</span>
                <StudioSelect
                  className="studio-select-inline studio-select-full"
                  value={item.status}
                  onChange={(val) => onUpdateCell({ kind: item.kind, id: item.id, status: val })}
                  options={[
                    ...(!statusOptionsFor(item.kind).includes(item.status)
                      ? [{ value: item.status, label: item.status }]
                      : []),
                    ...statusOptionsFor(item.kind).map((st) => ({ value: st, label: st }))
                  ]}
                  ariaLabel="作品状态"
                  minMenuWidth={140}
                  maxMenuWidth={220}
                />
              </div>

              <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
                  个人评分 (1–100，如 92 = 9.2)
                </span>
                <input
                  className="inline-input tabular"
                  type="number"
                  min={0}
                  max={100}
                  value={ratingInput}
                  onChange={(e) => setRatingInput(e.target.value)}
                  placeholder="空或 0 为未评"
                />
              </label>

              <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
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
                  value={progressInput}
                  onChange={(e) => setProgressInput(e.target.value)}
                />
              </label>

              {item.kind !== 'game' ? (
                <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
                    {item.kind === 'audio' ? '总时长 (秒)' : '总集数 / 总话数'}
                  </span>
                  <input
                    className="inline-input tabular"
                    type="number"
                    min={0}
                    value={progressTotalInput}
                    onChange={(e) => setProgressTotalInput(e.target.value)}
                  />
                </label>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                  <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                    外部均分：{item.publicScore != null ? item.publicScore.toFixed(1) : '暂无'}
                    {item.scoreDelta != null
                      ? `（分差 ${item.scoreDelta > 0 ? '+' : ''}${item.scoreDelta.toFixed(1)}）`
                      : ''}
                  </span>
                </div>
              )}
            </div>

            {item.kind !== 'game' && item.publicScore != null && (
              <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', marginTop: 6 }}>
                外部均分：{item.publicScore.toFixed(1)}
                {item.scoreDelta != null
                  ? `（分差 ${item.scoreDelta > 0 ? '+' : ''}${item.scoreDelta.toFixed(1)}）`
                  : ''}
              </div>
            )}

            {item.kind === 'book' && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 8 }}>
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>书籍类型（与手机端互通）</span>
                  <div style={{ display: 'flex', gap: 5 }}>
                    {(
                      [
                        ['小说', '小说'],
                        ['漫画', '漫画'],
                        ['', '未指定']
                      ] as const
                    ).map(([val, label]) => {
                      const active =
                        val === ''
                          ? !mediaFormatInput.trim()
                          : mediaFormatInput.trim() === val
                      return (
                        <button
                          key={label}
                          type="button"
                          className={`btn ${active ? 'btn-primary' : ''}`}
                          style={{ padding: '3px 9px', fontSize: '0.8846rem' }}
                          onClick={async () => {
                            setMediaFormatInput(val)
                            await onUpdateCell({
                              kind: item.kind,
                              id: item.id,
                              mediaFormat: val
                            })
                            flashSaved(val ? `已标记为「${val}」` : '已清除书籍类型')
                          }}
                        >
                          {label}
                        </button>
                      )
                    })}
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 8 }}>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>已读卷数</span>
                    <input
                      className="inline-input tabular"
                      type="number"
                      min={0}
                      value={volumesInput}
                      onChange={(e) => setVolumesInput(e.target.value)}
                      placeholder="可选"
                    />
                  </label>
                  <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>已读话数</span>
                    <input
                      className="inline-input tabular"
                      type="number"
                      min={0}
                      value={chaptersInput}
                      onChange={(e) => setChaptersInput(e.target.value)}
                      placeholder="可选"
                    />
                  </label>
                </div>
              </>
            )}

            {(item.kind === 'anime' || item.kind === 'book') && (
              <label style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 10 }}>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8846rem' }}>
                  私人备注（随备份同步，不公开）
                </span>
                <textarea
                  className="inline-input"
                  style={{ height: 64, padding: 6, resize: 'vertical' }}
                  maxLength={10000}
                  value={memoInput}
                  onChange={(e) => setMemoInput(e.target.value)}
                  placeholder="记录个人感受、购入信息、追更备注..."
                />
              </label>
            )}

            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 10 }}>
              <span style={{ fontSize: '0.8846rem', color: 'var(--positive)' }}>{feedback ?? ''}</span>
              <button className="btn btn-primary" onClick={saveQuickFields}>
                保存修改
              </button>
            </div>
          </div>

          <WorkExternalBindingCard key={item.kind + ':' + item.id} item={item} onRefresh={onRefreshItems} />

          {(item.kind === 'anime' || item.kind === 'book') && (() => {
            const bgmSubjectId = item.externalSource === 'bangumi' && item.externalId && /^\d+$/.test(item.externalId)
              ? Number.parseInt(item.externalId, 10) : null
            const handleQuickFetchChars = async () => {
              if (!bgmSubjectId) return
              setFetchingChars(true)
              try {
                const hits = await studioApi.fetchWorkCharacters(bgmSubjectId)
                const main = hits.filter((h) => h.roleName === '主角' || h.roleName === '主要角色')
                const pick = main.length > 0 ? main.slice(0, 10) : hits.slice(0, 8)
                for (const h of pick) {
                  const cid = await studioApi.upsertCharacter({
                    id: null,
                    bgmId: h.bgmId,
                    anilistId: h.anilistId,
                    name: h.name || h.nameCn,
                    nameCn: h.nameCn || null,
                    imageUrl: h.imageUrl || null,
                    summary: h.summary || null,
                    gender: h.gender || null,
                    birthMonth: h.birthMonth,
                    birthDay: h.birthDay,
                    bloodType: h.bloodType || null
                  })
                  await studioApi.linkCharacterWork(
                    cid,
                    item.id,
                    h.roleName || '主要角色',
                    h.voiceActor || null,
                    true
                  )
                }
                const updated = await studioApi.listWorkCharacters(item.id)
                setWorkCharacters(updated)
                onCharactersChanged?.()
                flashSaved(pick.length > 0 ? `已拉取并关联 ${pick.length} 位角色` : '暂无公开角色资料')
              } catch (err) {
                flashSaved(err instanceof Error ? err.message : '拉取角色失败')
              } finally {
                setFetchingChars(false)
              }
            }
            return (
              <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 12 }}>
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 6,
                    marginBottom: 8
                  }}
                >
                  <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>
                    出场角色 ({workCharacters.length})
                  </span>
                  <div style={{ display: 'inline-flex', gap: 5 }}>
                    {bgmSubjectId != null && (
                      <button
                        className="btn"
                        style={{ height: 22, padding: '0 7px', fontSize: '0.8462rem' }}
                        disabled={fetchingChars}
                        onClick={() => void handleQuickFetchChars()}
                        title="从 Bangumi 一键拉取主要角色及头像并绑定到本作品"
                      >
                        {fetchingChars ? '拉取中...' : '一键拉取'}
                      </button>
                    )}
                    {onOpenCharacterManage && (
                      <button
                        className="btn"
                        style={{ height: 22, padding: '0 7px', fontSize: '0.8462rem' }}
                        onClick={() => onOpenCharacterManage(null)}
                      >
                        管理角色
                      </button>
                    )}
                  </div>
                </div>
                {workCharacters.length === 0 ? (
                  <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                    {bgmSubjectId != null
                      ? '暂未关联角色，点击右上角「一键拉取」可自动导入主要角色与头像。'
                      : '暂未关联角色，可在「管理角色」中联网搜索或手动关联。'}
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {workCharacters.slice(0, 12).map((ch) => {
                      const disp = ch.nameCn || ch.name
                      const isTentative = ch.roleName === '暂定'
                      return (
                        <div
                          key={ch.id}
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 6,
                            padding: '3px 8px 3px 4px',
                            borderRadius: 999,
                            background: 'var(--bg-surface)',
                            border: `1px solid ${
                              isTentative ? 'var(--badge-amber-border)' : 'var(--border-subtle)'
                            }`,
                            maxWidth: '100%'
                          }}
                          title={[disp, ch.roleName, ch.voiceActor ? `CV: ${ch.voiceActor}` : '']
                            .filter(Boolean)
                            .join(' · ')}
                        >
                          <div
                            style={{
                              width: 24,
                              height: 24,
                              borderRadius: '50%',
                              overflow: 'hidden',
                              flexShrink: 0,
                              background: 'var(--bg-panel)',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              fontSize: '0.7692rem',
                              color: 'var(--text-muted)'
                            }}
                          >
                            <CoverImage
                              coverUrl={ch.imageUrl}
                              mediaBaseUrl={mediaBaseUrl}
                              alt={disp}
                              style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center' }}
                              fallback={<span>{disp.slice(0, 1)}</span>}
                            />
                          </div>
                          <span
                            style={{
                              fontSize: '0.8846rem',
                              fontWeight: 600,
                              maxWidth: 96,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap'
                            }}
                          >
                            {disp}
                          </span>
                          {ch.roleName && (
                            <span
                              style={{
                                fontSize: '0.7692rem',
                                color: isTentative ? 'var(--badge-amber-fg)' : 'var(--text-muted)'
                              }}
                            >
                              {ch.roleName}
                            </span>
                          )}
                        </div>
                      )
                    })}
                    {workCharacters.length > 12 && (
                      <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)', alignSelf: 'center' }}>
                        +{workCharacters.length - 12} 位
                      </span>
                    )}
                  </div>
                )}
              </div>
            )
          })()}

          <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>已关联的本机媒体文件</span>
              <span className="badge badge-vault tabular">{boundFiles.length} 个文件</span>
            </div>
            {boundFiles.length === 0 ? (
              <div style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>
                暂未绑定本机视频或音声文件。可在「同步」页的本机媒体文件夹中添加目录并扫描关联。
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {boundFiles.map((f) => (
                  <div
                    key={f.id}
                    style={{
                      padding: 8,
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-panel)',
                      border: '1px solid var(--border-subtle)'
                    }}
                  >
                    <div
                      className="mono"
                      style={{ fontSize: '0.8846rem', wordBreak: 'break-all', marginBottom: 5 }}
                      title={f.filePath}
                    >
                      {f.fileName}
                    </div>
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 }}>
                      {f.parsedEpisode != null && <span className="badge">EP {f.parsedEpisode}</span>}
                      {f.parsedRjCode && <span className="badge">{f.parsedRjCode}</span>}
                      {f.subtitlePath && <span className="badge badge-vault">外挂字幕</span>}
                      {f.scriptPath && <span className="badge badge-vault">台本/LRC</span>}
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      <button className="btn btn-primary" onClick={() => onPlayVaultFile(f, item)}>
                        内置播放
                      </button>
                      <button
                        className="btn"
                        onClick={() => studioApi.openExternalPlayer(f.filePath, false)}
                      >
                        外部播放器
                      </button>
                      <button
                        className="btn btn-ghost"
                        onClick={() => studioApi.openExternalPlayer(f.filePath, true)}
                      >
                        定位
                      </button>
                      <button className="btn btn-ghost" onClick={() => handleUnbind(f.id)}>
                        解绑
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 12 }}>
            <WorkReferencesPanel
              workKind={item.kind}
              workId={item.id}
              mediaBaseUrl={mediaBaseUrl}
              compact
              revision={vaultRevision}
            />
          </div>

          {hasMobilePassThrough && (
            <div className="card-box" style={{ background: 'var(--bg-canvas)', padding: 12 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>跨端保真元数据</span>
                <span className="badge badge-mobile">手机端功能</span>
              </div>
              <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', marginBottom: 8 }}>
                该内容来自手机端功能，电脑端暂不支持编辑
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: '0.9231rem', color: 'var(--text-secondary)' }}>
                {mp.broadcastSchedule && <div>放送提醒时段：{mp.broadcastSchedule}</div>}
                {mp.mergedSourceCount > 0 && <div>多源合并条目：{mp.mergedSourceCount} 个外部源</div>}
                {mp.readingChapterTitle && (
                  <div>
                    手机本地阅读章节：{mp.readingChapterTitle}
                    {mp.readingPermille != null ? ` (${(mp.readingPermille / 10).toFixed(1)}%)` : ''}
                  </div>
                )}
                {mp.legadoChapterTitle && <div>书源阅读进度：{mp.legadoChapterTitle}</div>}
                {mp.bookmarkCount > 0 && <div>手机端阅读书签：{mp.bookmarkCount} 条（导出备份时原样保留）</div>}
              </div>
            </div>
          )}
        </div>
      </div>
    </aside>
  )
}
