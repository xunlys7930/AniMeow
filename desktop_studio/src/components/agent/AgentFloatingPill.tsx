import React, { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { IconSpark } from './AgentIcons'

export type AgentFloatingStatus = 'running' | 'completed' | 'error' | 'idle'

export interface MorphRect {
  left: number
  top: number
  width: number
  height: number
}

export interface AgentFloatingPillProps {
  status: AgentFloatingStatus
  title: string
  detail: string
  pendingProposalCount: number
  zIndex: number
  morphFromRect?: MorphRect | null
  onMorphConsumed?: () => void
  onBringToFront?: () => void
  onExpand: (originRect?: MorphRect) => void
  onClose: () => void
  onStop?: () => void
}

const DEFAULT_MARGIN_RIGHT = 18
const DEFAULT_MARGIN_BOTTOM = 26

export const AgentFloatingPill: React.FC<AgentFloatingPillProps> = ({
  status,
  title,
  detail,
  pendingProposalCount,
  zIndex,
  morphFromRect,
  onMorphConsumed,
  onBringToFront,
  onExpand,
  onClose,
  onStop
}) => {
  const pillRef = useRef<HTMLDivElement | null>(null)
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const [dragging, setDragging] = useState(false)

  useLayoutEffect(() => {
    if (!morphFromRect) return
    onMorphConsumed?.()
    const el = pillRef.current
    if (!el || typeof el.animate !== 'function') return
    const reduce =
      document.documentElement.dataset.reduceMotion === 'true' ||
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduce) return
    const target = el.getBoundingClientRect()
    if (target.width <= 0 || target.height <= 0) return
    const dx = morphFromRect.left + morphFromRect.width / 2 - (target.left + target.width / 2)
    const dy = morphFromRect.top + morphFromRect.height / 2 - (target.top + target.height / 2)
    const sx = Math.min(2.6, Math.max(0.4, morphFromRect.width / target.width))
    const sy = Math.min(3.2, Math.max(0.4, morphFromRect.height / target.height))
    el.animate(
      [
        {
          transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})`,
          opacity: 0.45,
          borderRadius: '14px'
        },
        {
          transform: 'translate(0px, 0px) scale(1, 1)',
          opacity: 1,
          borderRadius: '999px'
        }
      ],
      {
        duration: 240,
        easing: 'cubic-bezier(0.22, 1, 0.36, 1)'
      }
    )
  }, [morphFromRect, onMorphConsumed])

  const triggerExpand = () => {
    const el = pillRef.current
    if (el) {
      const r = el.getBoundingClientRect()
      onExpand({ left: r.left, top: r.top, width: r.width, height: r.height })
    } else {
      onExpand()
    }
  }

  const dragRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    origLeft: number
    origTop: number
    moved: boolean
  } | null>(null)

  // 窗口缩放时自动把悬浮球钳制回可见视口内
  useEffect(() => {
    if (!pos) return
    const handleResize = () => {
      const el = pillRef.current
      if (!el) return
      const w = el.offsetWidth || 240
      const h = el.offsetHeight || 52
      setPos((prev) => {
        if (!prev) return prev
        return {
          x: Math.min(Math.max(8, prev.x), Math.max(8, window.innerWidth - w - 8)),
          y: Math.min(Math.max(44, prev.y), Math.max(44, window.innerHeight - h - 8))
        }
      })
    }
    window.addEventListener('resize', handleResize)
    return () => window.removeEventListener('resize', handleResize)
  }, [pos])

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    const target = e.target as HTMLElement | null
    if (target?.closest('button[data-pill-action]')) return
    const el = pillRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // pointer capture unavailable
    }
    dragRef.current = {
      pointerId: e.pointerId,
      startX: e.clientX,
      startY: e.clientY,
      origLeft: rect.left,
      origTop: rect.top,
      moved: false
    }
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const st = dragRef.current
    if (!st || st.pointerId !== e.pointerId) return
    const dx = e.clientX - st.startX
    const dy = e.clientY - st.startY
    if (!st.moved && Math.hypot(dx, dy) > 5) {
      st.moved = true
      setDragging(true)
    }
    if (!st.moved) return
    const el = pillRef.current
    const w = el?.offsetWidth || 240
    const h = el?.offsetHeight || 52
    const nextX = Math.min(Math.max(8, st.origLeft + dx), Math.max(8, window.innerWidth - w - 8))
    const nextY = Math.min(Math.max(44, st.origTop + dy), Math.max(44, window.innerHeight - h - 8))
    setPos({ x: nextX, y: nextY })
  }

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const st = dragRef.current
    if (!st || st.pointerId !== e.pointerId) return
    const wasMoved = st.moved
    dragRef.current = null
    setDragging(false)
    try {
      if (e.currentTarget.hasPointerCapture(e.pointerId)) {
        e.currentTarget.releasePointerCapture(e.pointerId)
      }
    } catch {
      // ignore
    }
    if (!wasMoved) {
      triggerExpand()
    }
  }

  const hasDetail = detail.trim().length > 0

  const style: React.CSSProperties = pos
    ? {
        left: pos.x,
        top: pos.y,
        right: 'auto',
        bottom: 'auto',
        zIndex
      }
    : {
        right: DEFAULT_MARGIN_RIGHT,
        bottom: DEFAULT_MARGIN_BOTTOM,
        zIndex
      }

  return (
    <div
      ref={pillRef}
      className={`agent-floating-pill is-${status} ${hasDetail ? '' : 'is-compact'} ${dragging ? 'is-dragging' : ''}`}
      style={style}
      role="region"
      aria-label="追番喵 AI 助手悬浮球"
      title="点击展开 AI 助手，按住可自由拖动位置"
      onPointerDownCapture={onBringToFront}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
    >
      <div className="agent-floating-pill-indicator" aria-hidden="true">
        <span className={`agent-floating-dot is-${status}`} />
        <IconSpark size={14} />
      </div>

      <div className="agent-floating-pill-body">
        <div className="agent-floating-pill-top">
          <strong className="agent-floating-pill-title">{title}</strong>
          {pendingProposalCount > 0 && (
            <span className="agent-floating-badge">{pendingProposalCount} 待确认</span>
          )}
        </div>
        {hasDetail && <div className="agent-floating-pill-detail">{detail}</div>}
      </div>

      <div className="agent-floating-pill-actions">
        {status === 'running' && onStop && (
          <button
            type="button"
            data-pill-action="stop"
            className="agent-floating-action-btn is-danger"
            title="停止当前生成"
            aria-label="停止当前生成"
            onClick={(e) => {
              e.stopPropagation()
              onStop()
            }}
          >
            <svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <rect x="5" y="5" width="14" height="14" rx="2" />
            </svg>
          </button>
        )}
        <button
          type="button"
          data-pill-action="expand"
          className="agent-floating-action-btn"
          title="展开完整 AI 助手窗口"
          aria-label="展开完整 AI 助手窗口"
          onClick={(e) => {
            e.stopPropagation()
            triggerExpand()
          }}
        >
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.1"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <polyline points="15 3 21 3 21 9" />
            <polyline points="9 21 3 21 3 15" />
            <line x1="21" y1="3" x2="14" y2="10" />
            <line x1="3" y1="21" x2="10" y2="14" />
          </svg>
        </button>
        <button
          type="button"
          data-pill-action="close"
          className="agent-floating-action-btn"
          title="关闭悬浮模式（可随时按 Ctrl+J 或点顶栏 AI 助手重新打开）"
          aria-label="关闭悬浮模式"
          onClick={(e) => {
            e.stopPropagation()
            onClose()
          }}
        >
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.1"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
    </div>
  )
}
