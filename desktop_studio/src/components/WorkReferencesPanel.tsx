import { useEffect, useState } from 'react'
import { resolveCaptureSrc, studioApi } from '../api'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import type {
  BookBookmarkRef,
  EpisodeNoteRef,
  LocalCaptureRow,
  MediaCarrier,
  WorkReferenceBundle
} from '../types'

interface WorkReferencesPanelProps {
  workKind: MediaCarrier
  workId: number
  mediaBaseUrl: string
  /** 传入此回调时（如三栏漫评室左栏）显示「插入引用」按钮，将对应素材作为 Markdown 引用块注入正文 */
  onInsertQuote?: (markdownSnippet: string) => void
  /** 紧凑模式（悬浮属性检视器内使用） */
  compact?: boolean
  /** 外部刷新触发版本号（例如速记刚落盘时刷新） */
  revision?: number
}

const EMPTY_BUNDLE: WorkReferenceBundle = {
  episodeNotes: [],
  bookmarks: [],
  captures: []
}

function formatEpisodeQuote(note: EpisodeNoteRef): string {
  const head = note.title ? `EP${note.episode} · ${note.title}` : `EP${note.episode}`
  const scorePart = note.rating != null ? `（单集评分 ${(note.rating / 10).toFixed(1)}）` : ''
  const body = note.review.trim() || '（该集仅记录了单集评分）'
  return `> **[${head}]**${scorePart} ${body.replace(/\r?\n/g, '\n> ')}`
}

function formatBookmarkQuote(bm: BookBookmarkRef): string {
  const lines = [`> **[手机书签 · ${bm.chapterTitle || '未命名章节'}]**`]
  if (bm.snippet.trim()) {
    lines.push(`> ${bm.snippet.trim().replace(/\r?\n/g, '\n> ')}`)
  }
  if (bm.note.trim()) {
    lines.push(`> —— 批注：${bm.note.trim().replace(/\r?\n/g, '\n> ')}`)
  }
  return lines.join('\n')
}

function formatCaptureQuote(cap: LocalCaptureRow): string {
  const ep = cap.episodeHint != null ? ` · EP${cap.episodeHint}` : ''
  const stamp = cap.createdAt.slice(0, 16).replace('T', ' ')
  const imgHint = cap.imageRelPath ? `（附本机分镜截图：\`${cap.imageRelPath}\`）` : ''
  const note = cap.note.trim() || '分镜截帧考据记录'
  return `> **[分镜手帐${ep} · ${stamp}]** ${note.replace(/\r?\n/g, '\n> ')}${imgHint}`
}

/**
 * 作品跨端考据引用聚合面板（单集笔记 + 手机书签只读摘录 + 本机分镜截图手帐）
 * 复用在三栏漫评室左栏与悬浮属性检视器中。
 */
export function WorkReferencesPanel({
  workKind,
  workId,
  mediaBaseUrl,
  onInsertQuote,
  compact = false,
  revision = 0
}: WorkReferencesPanelProps) {
  const [bundle, setBundle] = useState<WorkReferenceBundle>(EMPTY_BUNDLE)
  const [loading, setLoading] = useState<boolean>(true)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [previewSrc, setPreviewSrc] = useState<{ src: string; caption: string } | null>(null)
  const { zIndex: previewZ, bringToFront: bringPreviewToFront } = useFloatingLayer(
    Boolean(previewSrc),
    'modal'
  )
  const {
    offset: previewOffset,
    isMoved: previewMoved,
    resetOffset: resetPreviewOffset,
    dragHandlers: previewDragHandlers
  } = useDraggablePanel({ open: Boolean(previewSrc), resetOnOpen: true, minY: -220 })

  const loadReferences = () => {
    let cancelled = false
    setLoading(true)
    setErrorMsg(null)
    studioApi
      .queryWorkReferences(workKind, workId)
      .then((res) => {
        if (!cancelled) {
          setBundle(res)
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setBundle(EMPTY_BUNDLE)
          setErrorMsg(err instanceof Error ? err.message : String(err))
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }

  useEffect(() => {
    return loadReferences()
  }, [workKind, workId, revision])

  const handleDeleteCapture = async (captureId: number) => {
    try {
      await studioApi.deleteCapture(captureId)
      setBundle((prev) => ({
        ...prev,
        captures: prev.captures.filter((c) => c.id !== captureId)
      }))
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    }
  }

  const totalCount =
    bundle.episodeNotes.length + bundle.bookmarks.length + bundle.captures.length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontWeight: 600, fontSize: '0.9231rem' }}>
          跨端考据素材库
        </span>
        <span className="badge mono">
          {loading ? '读取中...' : `${totalCount} 条`}
        </span>
      </div>

      {errorMsg && (
        <div
          style={{
            padding: '6px 8px',
            borderRadius: 'var(--radius-sm)',
            border: '1px solid var(--danger)',
            color: 'var(--danger)',
            fontSize: '0.8846rem'
          }}
        >
          素材读取失败：{errorMsg}
        </div>
      )}

      {!loading && !errorMsg && totalCount === 0 && (
        <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', lineHeight: 1.55 }}>
          暂无关联的单集笔记、手机书签或分镜截图手帐。可随时按 <code>Alt+Space</code> 呼出速记微窗并粘贴截图帧。
        </div>
      )}

      {/* 1. 本机分镜截图与灵感手帐 */}
      {bundle.captures.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
              分镜截图与手帐 ({bundle.captures.length})
            </span>
          </div>
          {bundle.captures.map((cap) => {
            const imgSrc = resolveCaptureSrc(cap.imageRelPath, mediaBaseUrl)
            return (
              <div
                key={cap.id}
                style={{
                  padding: 8,
                  borderRadius: 'var(--radius-sm)',
                  background: 'var(--bg-canvas)',
                  border: '1px solid var(--border-subtle)',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 6
                }}
              >
                {imgSrc && (
                  <img
                    src={imgSrc}
                    alt={cap.note || '分镜截图'}
                    onClick={() =>
                      setPreviewSrc({
                        src: imgSrc,
                        caption: cap.note || `${cap.workTitle} 分镜截图`
                      })
                    }
                    style={{
                      width: '100%',
                      maxHeight: compact ? 110 : 140,
                      objectFit: 'cover',
                      borderRadius: 'var(--radius-sm)',
                      border: '1px solid var(--border-subtle)',
                      cursor: 'zoom-in',
                      background: 'var(--bg-panel)'
                    }}
                  />
                )}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 4 }}>
                  <div style={{ display: 'flex', gap: 4, alignItems: 'center', flexWrap: 'wrap' }}>
                    {cap.episodeHint != null && (
                      <span className="badge badge-accent mono">EP {cap.episodeHint}</span>
                    )}
                    <span className="mono" style={{ fontSize: '0.8077rem', color: 'var(--text-muted)' }}>
                      {cap.createdAt.slice(0, 16).replace('T', ' ')}
                    </span>
                  </div>
                  <div style={{ display: 'inline-flex', gap: 4 }}>
                    {onInsertQuote && (
                      <button
                        className="btn btn-ghost"
                        style={{ height: 21, fontSize: '0.8462rem' }}
                        onClick={() => onInsertQuote(formatCaptureQuote(cap))}
                        title="将此条手帐作为引用插入漫评正文"
                      >
                        插入引用
                      </button>
                    )}
                    <button
                      className="btn btn-ghost"
                      style={{ height: 21, fontSize: '0.8462rem', color: 'var(--danger)' }}
                      onClick={() => void handleDeleteCapture(cap.id)}
                      title="删除此条本机手帐及截图文件"
                    >
                      删除
                    </button>
                  </div>
                </div>
                {cap.note && (
                  <div style={{ fontSize: '0.9231rem', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>
                    {cap.note}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}

      {/* 2. 单集笔记 (episode_details) */}
      {bundle.episodeNotes.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span style={{ fontWeight: 600, fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
            单集笔记与分集评分 ({bundle.episodeNotes.length})
          </span>
          {bundle.episodeNotes.map((ep) => (
            <div
              key={ep.episode}
              style={{
                padding: 8,
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 5, minWidth: 0 }}>
                  <span className="badge mono">EP {ep.episode}</span>
                  {ep.rating != null && (
                    <span className="badge badge-accent mono">
                      {(ep.rating / 10).toFixed(1)}
                    </span>
                  )}
                  {ep.title && (
                    <span
                      style={{
                        fontSize: '0.8846rem',
                        fontWeight: 600,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap'
                      }}
                      title={ep.title}
                    >
                      {ep.title}
                    </span>
                  )}
                </div>
                {onInsertQuote && (
                  <button
                    className="btn btn-ghost"
                    style={{ height: 21, fontSize: '0.8462rem', flexShrink: 0 }}
                    onClick={() => onInsertQuote(formatEpisodeQuote(ep))}
                  >
                    插入引用
                  </button>
                )}
              </div>
              {ep.review && (
                <div
                  style={{
                    marginTop: 5,
                    fontSize: '0.9231rem',
                    color: 'var(--text-secondary)',
                    lineHeight: 1.5,
                    whiteSpace: 'pre-wrap'
                  }}
                >
                  {ep.review}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 3. 手机阅读书签摘录 (book_bookmarks · 只读跨端保真) */}
      {bundle.bookmarks.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontWeight: 600, fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
              手机阅读书签摘录 ({bundle.bookmarks.length})
            </span>
            <span className="badge badge-mobile">手机端功能</span>
          </div>
          <div style={{ color: 'var(--text-muted)', fontSize: '0.8462rem' }}>
            该内容来自手机端功能，电脑端暂不支持编辑
          </div>
          {bundle.bookmarks.map((bm) => (
            <div
              key={bm.id}
              style={{
                padding: 8,
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--border-subtle)'
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6 }}>
                <span style={{ fontWeight: 600, fontSize: '0.8846rem' }}>
                  {bm.chapterTitle || '未命名章节'}
                </span>
                {onInsertQuote && (
                  <button
                    className="btn btn-ghost"
                    style={{ height: 21, fontSize: '0.8462rem', flexShrink: 0 }}
                    onClick={() => onInsertQuote(formatBookmarkQuote(bm))}
                  >
                    插入引用
                  </button>
                )}
              </div>
              {bm.snippet && (
                <div
                  style={{
                    marginTop: 4,
                    paddingLeft: 8,
                    borderLeft: '2px solid var(--border-strong)',
                    fontSize: '0.8846rem',
                    color: 'var(--text-secondary)',
                    lineHeight: 1.5
                  }}
                >
                  {bm.snippet}
                </div>
              )}
              {bm.note && (
                <div style={{ marginTop: 4, fontSize: '0.8846rem', color: 'var(--text-primary)' }}>
                  批注：{bm.note}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 截图放大灯箱 */}
      {previewSrc && (
        <div
          className="modal-backdrop"
          style={{ alignItems: 'center', paddingTop: 0, zIndex: previewZ }}
          onPointerDownCapture={bringPreviewToFront}
          onClick={() => setPreviewSrc(null)}
        >
          <div
            className="card-box"
            onClick={(e) => e.stopPropagation()}
            style={{
              maxWidth: '86vw',
              maxHeight: '86vh',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              border: '1px solid var(--border-strong)',
              transform: previewMoved ? `translate(${previewOffset.x}px, ${previewOffset.y}px)` : undefined
            }}
          >
            <div
              {...previewDragHandlers}
              title="按住标题栏可拖动预览窗，双击归位"
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: 12,
                cursor: 'grab',
                userSelect: 'none'
              }}
            >
              <span className="heading-font" style={{ fontWeight: 700, fontSize: '1rem', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <span style={{ color: 'var(--text-muted)' }}>⋮⋮</span>
                {previewSrc.caption}
              </span>
              <div style={{ display: 'inline-flex', gap: 6 }}>
                {previewMoved && (
                  <button type="button" className="btn btn-ghost" onClick={resetPreviewOffset}>
                    归位
                  </button>
                )}
                <button type="button" className="btn btn-ghost" onClick={() => setPreviewSrc(null)}>
                  关闭
                </button>
              </div>
            </div>
            <img
              src={previewSrc.src}
              alt={previewSrc.caption}
              style={{
                maxWidth: '82vw',
                maxHeight: '74vh',
                objectFit: 'contain',
                borderRadius: 'var(--radius-sm)'
              }}
            />
          </div>
        </div>
      )}
    </div>
  )
}
