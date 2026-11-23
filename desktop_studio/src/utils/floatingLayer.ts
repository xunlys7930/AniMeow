import { useCallback, useEffect, useRef, useState } from 'react'
import type React from 'react'

export type FloatingLayerKind = 'panel' | 'modal'

const PANEL_BASE = 32
const PANEL_MAX = 480
const MODAL_BASE = 520
const MODAL_MAX = 980

let nextPanelZ = PANEL_BASE
let nextModalZ = MODAL_BASE

/**
 * 统一的悬浮窗口与弹窗层级管理：
 * - 悬浮面板（详情面板、AI 助手）共享 32..480 层级池，最后打开或最后点击/操作的永远置顶；
 * - 模态弹窗（配置中心、轨迹检视器、设置、库维护、使用手册、检索面板）共享 520..980 层级池，永远位于悬浮面板之上、系统确认框(9999)之下。
 */
export function allocateFloatingZ(kind: FloatingLayerKind): number {
  if (kind === 'panel') {
    if (nextPanelZ >= PANEL_MAX) nextPanelZ = PANEL_BASE
    nextPanelZ += 1
    return nextPanelZ
  }
  if (nextModalZ >= MODAL_MAX) nextModalZ = MODAL_BASE
  nextModalZ += 1
  return nextModalZ
}

export function useFloatingLayer(open: boolean, kind: FloatingLayerKind) {
  const [zIndex, setZIndex] = useState<number>(() => allocateFloatingZ(kind))

  const bringToFront = useCallback(() => {
    setZIndex(allocateFloatingZ(kind))
  }, [kind])

  useEffect(() => {
    if (open) {
      setZIndex(allocateFloatingZ(kind))
    }
  }, [open, kind])

  return { zIndex, bringToFront }
}

export interface DraggablePanelOptions {
  /** 每次打开时是否重置回默认居中/停靠位置 */
  resetOnOpen?: boolean
  open?: boolean
  minY?: number
}

/**
 * 通用标题栏拖拽 Hook：支持所有悬浮面板与模态弹窗自由拖拽，双击标题栏快速归位。
 */
export function useDraggablePanel(options: DraggablePanelOptions = {}) {
  const { resetOnOpen = false, open = true, minY = -280 } = options
  const [offset, setOffset] = useState<{ x: number; y: number }>({ x: 0, y: 0 })
  const dragStateRef = useRef<{
    pointerId: number
    startX: number
    startY: number
    origX: number
    origY: number
  } | null>(null)

  useEffect(() => {
    if (resetOnOpen && open) {
      setOffset({ x: 0, y: 0 })
    }
  }, [resetOnOpen, open])

  const resetOffset = useCallback(() => {
    setOffset({ x: 0, y: 0 })
  }, [])

  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLElement>) => {
      if (e.button !== 0) return
      const target = e.target as HTMLElement | null
      if (target?.closest('button, input, select, textarea, a, label, [data-no-drag]')) {
        return
      }
      try {
        e.currentTarget.setPointerCapture(e.pointerId)
      } catch {
        // ignore if pointer capture unavailable
      }
      dragStateRef.current = {
        pointerId: e.pointerId,
        startX: e.clientX,
        startY: e.clientY,
        origX: offset.x,
        origY: offset.y
      }
    },
    [offset.x, offset.y]
  )

  const onPointerMove = useCallback(
    (e: React.PointerEvent<HTMLElement>) => {
      const st = dragStateRef.current
      if (!st || st.pointerId !== e.pointerId) return
      const dx = e.clientX - st.startX
      const dy = e.clientY - st.startY
      const maxX = Math.max(240, window.innerWidth - 140)
      const maxY = Math.max(240, window.innerHeight - 100)
      setOffset({
        x: Math.max(-maxX, Math.min(maxX, st.origX + dx)),
        y: Math.max(minY, Math.min(maxY, st.origY + dy))
      })
    },
    [minY]
  )

  const onPointerUp = useCallback((e: React.PointerEvent<HTMLElement>) => {
    if (dragStateRef.current?.pointerId === e.pointerId) {
      dragStateRef.current = null
    }
  }, [])

  const isMoved = offset.x !== 0 || offset.y !== 0

  return {
    offset,
    isMoved,
    resetOffset,
    dragHandlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp,
      onPointerCancel: onPointerUp,
      onDoubleClick: (e: React.MouseEvent<HTMLElement>) => {
        const target = e.target as HTMLElement | null
        if (target?.closest('button, input, select, textarea, a, label')) return
        resetOffset()
      }
    }
  }
}
