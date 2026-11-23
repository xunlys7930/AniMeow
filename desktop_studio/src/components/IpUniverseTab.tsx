import React, { useEffect, useMemo, useRef, useState } from 'react'
import { studioApi } from '../api'
import { CanvasQuickEditBar, type CanvasEditTarget } from './CanvasQuickEditBar'
import { CoverImage } from './CoverImage'
import { StudioSelect } from './StudioSelect'
import type {
  CanvasCustomEdge,
  CanvasCustomNode,
  CanvasCustomState,
  CanvasEdge,
  CanvasNode,
  CuratorItem,
  MediaCarrier,
  UniverseGraph,
  UniverseMode
} from '../types'

interface IpUniverseTabProps {
  items: CuratorItem[]
  mediaBaseUrl?: string
  refreshRevision?: number
  requestedMode?: { mode: UniverseMode; focusNodeId?: string | null; seq: number } | null
  onSelectWork: (kind: string, id: number) => void
  onOpenReview?: (kind: string, id: number) => void
  onOpenCharacterManage?: (characterId?: number | null) => void
  onOpenSeriesManage?: () => void
  onDataChanged?: () => Promise<void>
}

const NODE_WIDTH = 216
const NODE_HEIGHT = 86
const MIN_ZOOM = 0.3
const MAX_ZOOM = 2.0

type GridStyle = 'dots' | 'grid' | 'plain'
type EdgeStyle = 'bezier' | 'step' | 'straight'

const CUSTOM_COLORS = ['#2f6d50', '#b5453d', '#2f5978', '#996a2a', '#6b4e9b']
const STYLE_PREF_KEY = 'animeow_studio_canvas_prefs_v1'

function clampZoom(v: number): number {
  return Math.round(Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, v)) * 100) / 100
}

function loadCanvasPrefs(): { gridStyle: GridStyle; edgeStyle: EdgeStyle } {
  try {
    const raw = localStorage.getItem(STYLE_PREF_KEY)
    if (!raw) return { gridStyle: 'dots', edgeStyle: 'bezier' }
    const p = JSON.parse(raw)
    return {
      gridStyle: p.gridStyle === 'grid' || p.gridStyle === 'plain' ? p.gridStyle : 'dots',
      edgeStyle: p.edgeStyle === 'step' || p.edgeStyle === 'straight' ? p.edgeStyle : 'bezier'
    }
  } catch {
    return { gridStyle: 'dots', edgeStyle: 'bezier' }
  }
}

export const IpUniverseTab: React.FC<IpUniverseTabProps> = ({
  items,
  mediaBaseUrl = '',
  refreshRevision = 0,
  requestedMode = null,
  onSelectWork,
  onOpenReview,
  onOpenCharacterManage,
  onOpenSeriesManage,
  onDataChanged
}) => {
  const [mode, setMode] = useState<UniverseMode>('ip')
  const [scopeWorkNodeId, setScopeWorkNodeId] = useState<string | null>(null)
  const [focusNodeId, setFocusNodeId] = useState<string | null>(null)
  const [focusSearch, setFocusSearch] = useState<string>('')
  // 环形星图默认关闭，避免点击作品时节点凭空跳位；由工具栏显式切换
  const [starLayoutEnabled, setStarLayoutEnabled] = useState<boolean>(false)
  const [starDragOverrides, setStarDragOverrides] = useState<Record<string, { x: number; y: number }>>({})
  const [edgesOnlyOnHover, setEdgesOnlyOnHover] = useState<boolean>(false)
  const [editTarget, setEditTarget] = useState<CanvasEditTarget | null>(null)

  useEffect(() => {
    if (requestedMode) {
      setMode(requestedMode.mode)
      if (requestedMode.focusNodeId !== undefined) {
        const nextId = requestedMode.focusNodeId
        setFocusNodeId(nextId)
        setScopeWorkNodeId(nextId && nextId.startsWith('work:') ? nextId : null)
        setStarLayoutEnabled(false)
        setStarDragOverrides({})
        pendingCameraFocusRef.current = nextId || '__fit__'
      }
    }
  }, [requestedMode?.seq])
  const [graph, setGraph] = useState<UniverseGraph | null>(null)
  const [customState, setCustomState] = useState<CanvasCustomState>({ nodes: [], edges: [] })
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [statusTip, setStatusTip] = useState<string | null>(null)
  // 纯 GPU 2D 相机（替代滚动条 + 异步 rAF，彻底消除缩放抖动与漂移）
  const [camera, setCamera] = useState<{ x: number; y: number; zoom: number }>({ x: 24, y: 24, zoom: 1 })
  const zoom = camera.zoom
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null)

  const initialPrefs = useMemo(() => loadCanvasPrefs(), [])
  const [gridStyle, setGridStyle] = useState<GridStyle>(initialPrefs.gridStyle)
  const [edgeStyle, setEdgeStyle] = useState<EdgeStyle>(initialPrefs.edgeStyle)

  // 自定义节点与连线面板状态
  const [showAddNode, setShowAddNode] = useState(false)
  const [newNodeTitle, setNewNodeTitle] = useState('')
  const [newNodeSub, setNewNodeSub] = useState('')
  const [newNodeColor, setNewNodeColor] = useState(CUSTOM_COLORS[0])

  const [connectMode, setConnectMode] = useState(false)
  const [connectFromId, setConnectFromId] = useState<string | null>(null)
  const [connectLabel, setConnectLabel] = useState('关联')

  // 节点拖拽
  const [dragState, setDragState] = useState<{
    nodeId: string
    isCustom: boolean
    isStarTemp: boolean
    startClientX: number
    startClientY: number
    origX: number
    origY: number
  } | null>(null)

  // 画布平移拖拽（支持右键拖拽、中键拖拽、空白处左键拖拽）
  const [panState, setPanState] = useState<{
    startClientX: number
    startClientY: number
    origCamX: number
    origCamY: number
  } | null>(null)

  const latestNodesRef = useRef<CanvasNode[]>([])
  const latestCustomRef = useRef<CanvasCustomState>({ nodes: [], edges: [] })
  const movedRef = useRef(false)
  const panMovedRef = useRef(false)
  const viewportRef = useRef<HTMLDivElement | null>(null)
  const cameraRef = useRef(camera)
  const cameraAnimRef = useRef<number | null>(null)
  const pendingCameraFocusRef = useRef<string | null>(null)

  useEffect(() => {
    cameraRef.current = camera
  }, [camera])

  useEffect(() => {
    return () => {
      if (cameraAnimRef.current != null) {
        cancelAnimationFrame(cameraAnimRef.current)
      }
    }
  }, [])

  const stopCameraAnim = () => {
    if (cameraAnimRef.current != null) {
      cancelAnimationFrame(cameraAnimRef.current)
      cameraAnimRef.current = null
    }
  }

  const commitCamera = (next: { x: number; y: number; zoom: number }) => {
    stopCameraAnim()
    cameraRef.current = next
    setCamera(next)
  }

  const animateCameraTo = (target: { x: number; y: number; zoom: number }, durationMs = 200) => {
    stopCameraAnim()
    const reduced =
      typeof window !== 'undefined' &&
      (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ||
        document.documentElement.dataset.reduceMotion === 'true' ||
        document.documentElement.dataset.reduceMotion === '1')
    if (reduced || durationMs <= 0) {
      cameraRef.current = target
      setCamera(target)
      return
    }
    const start = { ...cameraRef.current }
    const t0 = performance.now()
    const tick = (now: number) => {
      const p = Math.min(1, (now - t0) / durationMs)
      const ease = 1 - Math.pow(1 - p, 3)
      const cur = {
        x: start.x + (target.x - start.x) * ease,
        y: start.y + (target.y - start.y) * ease,
        zoom: start.zoom + (target.zoom - start.zoom) * ease
      }
      cameraRef.current = cur
      setCamera(cur)
      if (p < 1) {
        cameraAnimRef.current = requestAnimationFrame(tick)
      } else {
        cameraAnimRef.current = null
      }
    }
    cameraAnimRef.current = requestAnimationFrame(tick)
  }

  const zoomAtViewportCenter = (nextZoomRaw: number) => {
    const prev = cameraRef.current
    const nextZoom = clampZoom(nextZoomRaw)
    if (nextZoom === prev.zoom) return
    const el = viewportRef.current
    const cx = el ? el.clientWidth / 2 : 400
    const cy = el ? el.clientHeight / 2 : 300
    const worldX = (cx - prev.x) / prev.zoom
    const worldY = (cy - prev.y) / prev.zoom
    commitCamera({
      x: cx - worldX * nextZoom,
      y: cy - worldY * nextZoom,
      zoom: nextZoom
    })
  }

  useEffect(() => {
    latestCustomRef.current = customState
  }, [customState])

  useEffect(() => {
    try {
      localStorage.setItem(STYLE_PREF_KEY, JSON.stringify({ gridStyle, edgeStyle }))
    } catch {
      // 忽略本地偏好写入异常
    }
  }, [gridStyle, edgeStyle])

  // 滚轮指针锚点缩放（同帧同步计算相机位移与缩放，零抖动；Shift+滚轮可平移）
  useEffect(() => {
    const el = viewportRef.current
    if (!el) return
    const handleWheel = (e: WheelEvent) => {
      e.preventDefault()
      const prev = cameraRef.current
      if (e.shiftKey && !(e.ctrlKey || e.metaKey)) {
        commitCamera({
          x: prev.x - e.deltaY * 0.85,
          y: prev.y - e.deltaX * 0.85,
          zoom: prev.zoom
        })
        return
      }
      const clampedDelta = Math.max(-140, Math.min(140, e.deltaY))
      const factor = Math.exp(-clampedDelta * 0.0016)
      let nextZoom = clampZoom(prev.zoom * factor)
      if (nextZoom === prev.zoom && clampedDelta !== 0) {
        nextZoom = clampZoom(prev.zoom + (clampedDelta < 0 ? 0.02 : -0.02))
      }
      if (nextZoom === prev.zoom) return

      const rect = el.getBoundingClientRect()
      const cursorX = e.clientX - rect.left
      const cursorY = e.clientY - rect.top
      const worldX = (cursorX - prev.x) / prev.zoom
      const worldY = (cursorY - prev.y) / prev.zoom

      commitCamera({
        x: cursorX - worldX * nextZoom,
        y: cursorY - worldY * nextZoom,
        zoom: nextZoom
      })
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => el.removeEventListener('wheel', handleWheel)
  }, [])

  // Ctrl + +/-/0 快捷缩放 & Esc 退出连线模式（仅当画布页签真实可见时拦截，避免后台保活态误劫持其他页签快捷键）
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const vp = viewportRef.current
      if (!vp || vp.clientWidth === 0 || vp.clientHeight === 0) return
      if (e.key === 'Escape') {
        setConnectMode(false)
        setConnectFromId(null)
        setShowAddNode(false)
        setEditTarget(null)
        return
      }
      if (!(e.ctrlKey || e.metaKey)) return
      const target = e.target as HTMLElement | null
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      ) {
        return
      }
      if (e.key === '=' || e.key === '+') {
        e.preventDefault()
        zoomAtViewportCenter(cameraRef.current.zoom + 0.12)
      } else if (e.key === '-') {
        e.preventDefault()
        zoomAtViewportCenter(cameraRef.current.zoom - 0.12)
      } else if (e.key === '0') {
        e.preventDefault()
        zoomAtViewportCenter(1)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [])

  const loadGraph = async (targetMode: UniverseMode) => {
    setLoading(true)
    try {
      const [g, custom] = await Promise.all([
        studioApi.queryUniverseGraph(targetMode),
        studioApi.loadCanvasCustom(targetMode).catch(() => ({ nodes: [], edges: [] }))
      ])
      latestNodesRef.current = g.nodes
      latestCustomRef.current = custom
      setGraph(g)
      setCustomState(custom)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    setConnectMode(false)
    setConnectFromId(null)
    setEditTarget(null)
    setStarLayoutEnabled(false)
    setStarDragOverrides({})
    pendingCameraFocusRef.current = '__fit__'
    void loadGraph(mode)
  }, [mode, items.length, refreshRevision])

  const persistCustom = async (next: CanvasCustomState) => {
    latestCustomRef.current = next
    setCustomState(next)
    setSaving(true)
    try {
      await studioApi.saveCanvasCustom(mode, next)
    } finally {
      setSaving(false)
    }
  }

  // 画布平移（右键/中键/空白左键拖拽）
  useEffect(() => {
    if (!panState) return
    const onMove = (e: MouseEvent) => {
      const dx = e.clientX - panState.startClientX
      const dy = e.clientY - panState.startClientY
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        panMovedRef.current = true
      }
      commitCamera({
        x: panState.origCamX + dx,
        y: panState.origCamY + dy,
        zoom: cameraRef.current.zoom
      })
    }
    const onUp = () => {
      setPanState(null)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
  }, [panState])

  // 节点拖拽
  useEffect(() => {
    if (!dragState) return
    const onMove = (e: MouseEvent) => {
      const curZoom = cameraRef.current.zoom
      const dx = (e.clientX - dragState.startClientX) / curZoom
      const dy = (e.clientY - dragState.startClientY) / curZoom
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        movedRef.current = true
      }
      const nextX = Math.round(dragState.origX + dx)
      const nextY = Math.round(dragState.origY + dy)
      if (dragState.isStarTemp) {
        setStarDragOverrides((prev) => ({
          ...prev,
          [dragState.nodeId]: { x: nextX, y: nextY }
        }))
        return
      }
      if (dragState.isCustom) {
        setCustomState((prev) => {
          const next = {
            ...prev,
            nodes: prev.nodes.map((n) =>
              n.id === dragState.nodeId ? { ...n, x: nextX, y: nextY } : n
            )
          }
          latestCustomRef.current = next
          return next
        })
      } else {
        setGraph((prev) => {
          if (!prev) return prev
          const updatedNodes = prev.nodes.map((n) =>
            n.nodeId === dragState.nodeId ? { ...n, x: nextX, y: nextY } : n
          )
          latestNodesRef.current = updatedNodes
          return { ...prev, nodes: updatedNodes }
        })
      }
    }
    const onUp = async () => {
      const wasCustom = dragState.isCustom
      const wasStarTemp = dragState.isStarTemp
      setDragState(null)
      if (!movedRef.current || wasStarTemp) return
      setSaving(true)
      try {
        if (wasCustom) {
          await studioApi.saveCanvasCustom(mode, latestCustomRef.current)
        } else {
          const positions: Record<string, [number, number]> = {}
          latestNodesRef.current.forEach((n) => {
            positions[n.nodeId] = [n.x, n.y]
          })
          await studioApi.saveUniverseLayout(mode, positions)
        }
      } finally {
        setSaving(false)
      }
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
  }, [dragState, mode])

  const handleResetLayout = async () => {
    setSaving(true)
    try {
      setStarLayoutEnabled(false)
      setStarDragOverrides({})
      await studioApi.saveUniverseLayout(mode, {})
      pendingCameraFocusRef.current = '__fit__'
      await loadGraph(mode)
      flashTip('已恢复按作品自动分区排版')
    } finally {
      setSaving(false)
    }
  }

  const handleExportCanvasPng = async () => {
    if (totalNodes === 0) return
    const focusName = effectiveFocusId
      ? graph?.nodes.find((n) => n.nodeId === effectiveFocusId)?.label ?? mode
      : mode === 'ip'
        ? 'IP企划谱系'
        : mode === 'seiyuu'
          ? '声优星图'
          : '角色关系图谱'
    const safeName = focusName.replace(/[\\/:*?"<>|]/g, '_').slice(0, 32)
    const today = new Date().toISOString().slice(0, 10)
    const savePath = await studioApi.pickSavePng(`AniMeow-画布-${safeName}-${today}.png`)
    if (!savePath) return

    setSaving(true)
    try {
      const pad = 48
      let minX = Number.POSITIVE_INFINITY
      let minY = Number.POSITIVE_INFINITY
      let maxX = 0
      let maxY = 0
      allPositionMap.forEach((p) => {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x + NODE_WIDTH > maxX) maxX = p.x + NODE_WIDTH
        if (p.y + NODE_HEIGHT > maxY) maxY = p.y + NODE_HEIGHT
      })
      if (!Number.isFinite(minX)) {
        minX = 0
        minY = 0
      }
      const headerH = 44
      const footerH = 34
      const w = Math.max(640, Math.min(4800, Math.ceil(maxX - minX + pad * 2)))
      const h = Math.max(420, Math.min(4800, Math.ceil(maxY - minY + pad * 2 + headerH + footerH)))
      const scale = w * h > 4_000_000 ? 1.5 : 2

      const canvas = document.createElement('canvas')
      canvas.width = Math.round(w * scale)
      canvas.height = Math.round(h * scale)
      const ctx = canvas.getContext('2d')
      if (!ctx) return
      ctx.scale(scale, scale)

      const cs = getComputedStyle(document.documentElement)
      const cssVar = (name: string, fallback: string) => cs.getPropertyValue(name).trim() || fallback
      const bgCanvas = cssVar('--bg-canvas', '#f6f2ea')
      const bgElevated = cssVar('--bg-elevated', '#fdfbf7')
      const accent = cssVar('--accent', '#b93b30')
      const accentSoft = cssVar('--accent-soft', 'rgba(185, 59, 48, 0.12)')
      const borderStrong = cssVar('--border-strong', '#9e917d')
      const borderSubtle = cssVar('--border-subtle', '#ded5c4')
      const textPrimary = cssVar('--text-primary', '#211d18')
      const textSecondary = cssVar('--text-secondary', '#595248')
      const textMuted = cssVar('--text-muted', '#8a8072')
      const purpleStroke = cssVar('--badge-purple-stroke', '#8b5cf6')
      const purpleBg = cssVar('--badge-purple-surface', 'rgba(139, 92, 246, 0.1)')
      const purpleFg = cssVar('--badge-purple-fg', '#6d28d9')

      ctx.fillStyle = bgCanvas
      ctx.fillRect(0, 0, w, h)

      // 标题头
      ctx.fillStyle = textPrimary
      ctx.font = 'bold 17px "Microsoft YaHei UI", sans-serif'
      ctx.fillText(`追番喵 · ${focusName}`, pad, 30)
      ctx.fillStyle = textMuted
      ctx.font = '12px "Microsoft YaHei UI", sans-serif'
      ctx.fillText(`节点 ${totalNodes} · 连线 ${totalEdges} · ${today}`, w - pad - 180, 30)

      const ox = pad - minX
      const oy = pad + headerH - minY

      const drawRoundRect = (x: number, y: number, rw: number, rh: number, r: number) => {
        ctx.beginPath()
        ctx.moveTo(x + r, y)
        ctx.arcTo(x + rw, y, x + rw, y + rh, r)
        ctx.arcTo(x + rw, y + rh, x, y + rh, r)
        ctx.arcTo(x, y + rh, x, y, r)
        ctx.arcTo(x, y, x + rw, y, r)
        ctx.closePath()
      }

      const drawEdge = (
        a: { x: number; y: number },
        b: { x: number; y: number },
        stroke: string,
        dashed: boolean,
        label: string
      ) => {
        const ax = a.x + ox
        const ay = a.y + oy
        const bx = b.x + ox
        const by = b.y + oy
        const aRightToBLeft = bx >= ax + NODE_WIDTH - 20
        const bRightToALeft = ax >= bx + NODE_WIDTH - 20
        let x1 = ax + NODE_WIDTH / 2
        let y1 = ay + NODE_HEIGHT / 2
        let x2 = bx + NODE_WIDTH / 2
        let y2 = by + NODE_HEIGHT / 2
        if (aRightToBLeft) {
          x1 = ax + NODE_WIDTH
          x2 = bx
        } else if (bRightToALeft) {
          x1 = ax
          x2 = bx + NODE_WIDTH
        }
        ctx.save()
        ctx.strokeStyle = stroke
        ctx.lineWidth = 1.8
        if (dashed) ctx.setLineDash([6, 4])
        ctx.beginPath()
        ctx.moveTo(x1, y1)
        if (edgeStyle === 'straight') {
          ctx.lineTo(x2, y2)
        } else if (edgeStyle === 'step') {
          const midX = (x1 + x2) / 2
          ctx.lineTo(midX, y1)
          ctx.lineTo(midX, y2)
          ctx.lineTo(x2, y2)
        } else {
          const dx = Math.max(48, Math.abs(x2 - x1) * 0.45)
          const c1x = aRightToBLeft ? x1 + dx : bRightToALeft ? x1 - dx : x1
          const c1y = aRightToBLeft || bRightToALeft ? y1 : y1 + (y2 > y1 ? 48 : -48)
          const c2x = aRightToBLeft ? x2 - dx : bRightToALeft ? x2 + dx : x2
          const c2y = aRightToBLeft || bRightToALeft ? y2 : y2 + (y2 > y1 ? -48 : 48)
          ctx.bezierCurveTo(c1x, c1y, c2x, c2y, x2, y2)
        }
        ctx.stroke()
        ctx.restore()

        if (label) {
          const mx = (x1 + x2) / 2
          const my = (y1 + y2) / 2
          const txt = label.length > 10 ? `${label.slice(0, 10)}…` : label
          ctx.font = '600 10.5px "Microsoft YaHei UI", sans-serif'
          const pillW = Math.max(46, ctx.measureText(txt).width + 16)
          drawRoundRect(mx - pillW / 2, my - 9, pillW, 18, 6)
          ctx.fillStyle = bgElevated
          ctx.fill()
          ctx.strokeStyle = stroke
          ctx.lineWidth = 1
          ctx.stroke()
          ctx.fillStyle = stroke
          ctx.textAlign = 'center'
          ctx.fillText(txt, mx, my + 3.5)
          ctx.textAlign = 'left'
        }
      }

      // 绘制边
      visibleAutoEdges.forEach((edge) => {
        const a = allPositionMap.get(edge.fromId)
        const b = allPositionMap.get(edge.toId)
        if (!a || !b) return
        const isCharRel = edge.fromId.startsWith('char:') && edge.toId.startsWith('char:')
        drawEdge(
          a,
          b,
          isCharRel ? purpleStroke : textMuted,
          isCharRel,
          edge.relation
        )
      })
      visibleCustomEdges.forEach((edge) => {
        const a = allPositionMap.get(edge.from)
        const b = allPositionMap.get(edge.to)
        if (!a || !b) return
        drawEdge(a, b, accent, true, edge.label)
      })

      const clipText = (txt: string, maxW: number) => {
        if (ctx.measureText(txt).width <= maxW) return txt
        let out = txt
        while (out.length > 1 && ctx.measureText(`${out}…`).width > maxW) {
          out = out.slice(0, -1)
        }
        return `${out}…`
      }

      // 绘制自动节点
      visibleAutoNodes.forEach((node) => {
        const pos = allPositionMap.get(node.nodeId)
        if (!pos) return
        const nx = pos.x + ox
        const ny = pos.y + oy
        const isHub =
          node.nodeType === 'hub' || node.nodeType === 'series' || node.nodeType === 'seiyuu'
        const isChar = node.nodeType === 'character'
        drawRoundRect(nx, ny, NODE_WIDTH, NODE_HEIGHT, isChar ? 12 : 7)
        ctx.fillStyle = isHub ? accentSoft : isChar ? purpleBg : bgElevated
        ctx.fill()
        ctx.strokeStyle = isHub ? accent : isChar ? purpleStroke : borderStrong
        ctx.lineWidth = 1.6
        ctx.stroke()

        // 徽章
        const badgeText = node.badge || (isChar ? '角色' : '作品')
        ctx.font = '600 10.5px "Microsoft YaHei UI", sans-serif'
        const bw = Math.min(NODE_WIDTH - 24, ctx.measureText(badgeText).width + 12)
        drawRoundRect(nx + 10, ny + 9, bw, 17, 4)
        ctx.fillStyle = isChar ? purpleBg : bgCanvas
        ctx.fill()
        ctx.strokeStyle = isChar ? purpleStroke : borderSubtle
        ctx.lineWidth = 0.8
        ctx.stroke()
        ctx.fillStyle = isChar ? purpleFg : textSecondary
        ctx.fillText(clipText(badgeText, bw - 8), nx + 15, ny + 21)

        // 标题与副标题
        ctx.fillStyle = textPrimary
        ctx.font = 'bold 13px "Microsoft YaHei UI", sans-serif'
        ctx.fillText(clipText(node.label, NODE_WIDTH - 22), nx + 11, ny + 46)
        ctx.fillStyle = textMuted
        ctx.font = '11px "Microsoft YaHei UI", sans-serif'
        ctx.fillText(clipText(node.sublabel, NODE_WIDTH - 22), nx + 11, ny + 64)
      })

      // 绘制自定义节点
      visibleCustomNodes.forEach((cn) => {
        const pos = allPositionMap.get(cn.id)
        if (!pos) return
        const nx = pos.x + ox
        const ny = pos.y + oy
        drawRoundRect(nx, ny, NODE_WIDTH, NODE_HEIGHT, 7)
        ctx.fillStyle = bgElevated
        ctx.fill()
        ctx.strokeStyle = cn.color || accent
        ctx.lineWidth = 2
        ctx.stroke()

        drawRoundRect(nx + 10, ny + 9, 62, 17, 4)
        ctx.fillStyle = cn.color || accent
        ctx.fill()
        ctx.fillStyle = '#ffffff'
        ctx.font = '600 10.5px "Microsoft YaHei UI", sans-serif'
        ctx.fillText('自定义节点', nx + 14, ny + 21)

        ctx.fillStyle = textPrimary
        ctx.font = 'bold 13px "Microsoft YaHei UI", sans-serif'
        ctx.fillText(clipText(cn.label, NODE_WIDTH - 22), nx + 11, ny + 46)
        ctx.fillStyle = textSecondary
        ctx.font = '11px "Microsoft YaHei UI", sans-serif'
        ctx.fillText(clipText(cn.subtitle, NODE_WIDTH - 22), nx + 11, ny + 64)
      })

      // 底部水印
      ctx.fillStyle = textMuted
      ctx.font = '11px "Microsoft YaHei UI", sans-serif'
      ctx.fillText('追番喵 · AniMeow', pad, h - 14)

      const dataUrl = canvas.toDataURL('image/png')
      await studioApi.savePngBase64(savePath, dataUrl)
      flashTip('已导出高清画布 PNG 图片')
    } finally {
      setSaving(false)
    }
  }

  const handleAddCustomNode = async () => {
    const label = newNodeTitle.trim()
    if (!label) return
    const el = viewportRef.current
    const cam = cameraRef.current
    const cx = el
      ? Math.round((el.clientWidth / 2 - cam.x) / cam.zoom - NODE_WIDTH / 2)
      : 120
    const cy = el
      ? Math.round((el.clientHeight / 2 - cam.y) / cam.zoom - NODE_HEIGHT / 2)
      : 120
    const node: CanvasCustomNode = {
      id: `custom:${Date.now()}`,
      label,
      subtitle: newNodeSub.trim() || '自定义备注节点',
      color: newNodeColor,
      x: cx,
      y: cy
    }
    await persistCustom({
      ...customState,
      nodes: [...customState.nodes, node]
    })
    setNewNodeTitle('')
    setNewNodeSub('')
    setShowAddNode(false)
  }

  const handleDeleteCustomNode = async (id: string) => {
    await persistCustom({
      nodes: customState.nodes.filter((n) => n.id !== id),
      edges: customState.edges.filter((e) => e.from !== id && e.to !== id)
    })
  }

  const handleDeleteCustomEdge = async (edgeId: string) => {
    await persistCustom({
      ...customState,
      edges: customState.edges.filter((e) => e.id !== edgeId)
    })
  }

  const handleUpdateCustomNode = async (updated: CanvasCustomNode) => {
    await persistCustom({
      ...customState,
      nodes: customState.nodes.map((n) => (n.id === updated.id ? updated : n))
    })
  }

  const handleUpdateCustomEdge = async (updated: CanvasCustomEdge) => {
    await persistCustom({
      ...customState,
      edges: customState.edges.map((e) => (e.id === updated.id ? updated : e))
    })
  }

  const flashTip = (msg: string) => {
    setStatusTip(msg)
    window.setTimeout(() => setStatusTip(null), 2600)
  }

  const selectAutoEdgeForEdit = (edge: CanvasEdge) => {
    const labelOf = (id: string) =>
      graph?.nodes.find((n) => n.nodeId === id)?.label ?? id
    if (edge.fromId.startsWith('char:') && edge.toId.startsWith('char:') && edge.relationId != null) {
      const src = Number.parseInt(edge.fromId.slice(5), 10)
      const dst = Number.parseInt(edge.toId.slice(5), 10)
      if (!Number.isNaN(src) && !Number.isNaN(dst)) {
        setEditTarget({
          kind: 'char_relation_edge',
          relationId: edge.relationId,
          sourceId: src,
          targetId: dst,
          sourceLabel: labelOf(edge.fromId),
          targetLabel: labelOf(edge.toId),
          rawLabel: edge.relation
        })
        return
      }
    }
    if (edge.fromId.startsWith('work:') && edge.toId.startsWith('char:')) {
      const parts = edge.fromId.split(':')
      const aid = Number.parseInt(parts[2] ?? '', 10)
      const cid = Number.parseInt(edge.toId.slice(5), 10)
      if (!Number.isNaN(aid) && !Number.isNaN(cid)) {
        setEditTarget({
          kind: 'work_char_edge',
          animeId: aid,
          characterId: cid,
          workLabel: labelOf(edge.fromId),
          charLabel: labelOf(edge.toId),
          rawLabel: edge.relation
        })
        return
      }
    }
    if (edge.fromId.startsWith('series:') && edge.toId.startsWith('work:')) {
      const sid = Number.parseInt(edge.fromId.slice(7), 10)
      const parts = edge.toId.split(':')
      const wkind = parts[1] as MediaCarrier
      const wid = Number.parseInt(parts[2] ?? '', 10)
      if (!Number.isNaN(sid) && !Number.isNaN(wid)) {
        setEditTarget({
          kind: 'series_work_edge',
          seriesId: sid,
          workKind: wkind,
          workId: wid,
          seriesLabel: labelOf(edge.fromId),
          workLabel: labelOf(edge.toId),
          rawLabel: edge.relation
        })
      }
    }
  }

  const handleNodeConnectClick = async (nodeId: string) => {
    if (!connectFromId) {
      setConnectFromId(nodeId)
      return
    }
    if (connectFromId === nodeId) {
      setConnectFromId(null)
      return
    }

    const fromId = connectFromId
    const label = connectLabel.trim() || '关联'
    setConnectFromId(null)

    // IP 模式下：连接系列中枢与作品直接将该作品归入该系列
    if (mode === 'ip') {
      const parseSeriesId = (s: string) =>
        s.startsWith('series:') ? Number.parseInt(s.slice(7), 10) : NaN
      const parseWork = (s: string): [MediaCarrier, number] | null => {
        if (!s.startsWith('work:')) return null
        const parts = s.split(':')
        const wid = Number.parseInt(parts[2] ?? '', 10)
        if (Number.isNaN(wid)) return null
        return [parts[1] as MediaCarrier, wid]
      }
      const sFrom = parseSeriesId(fromId)
      const sTo = parseSeriesId(nodeId)
      const wFrom = parseWork(fromId)
      const wTo = parseWork(nodeId)
      const seriesNodeId = !Number.isNaN(sFrom) ? fromId : !Number.isNaN(sTo) ? nodeId : null
      const workTarget = !Number.isNaN(sFrom) ? wTo : !Number.isNaN(sTo) ? wFrom : null
      if (seriesNodeId && workTarget) {
        const seriesName = graph?.nodes.find((n) => n.nodeId === seriesNodeId)?.label
        if (seriesName) {
          setSaving(true)
          try {
            await studioApi.batchSeries([workTarget], seriesName, label)
            await loadGraph(mode)
            flashTip(`已将作品归入系列「${seriesName}」（${label}）`)
            if (onDataChanged) await onDataChanged()
          } finally {
            setSaving(false)
          }
          return
        }
      }
    }

    // 角色图谱模式下：连接两个角色直接写入 character_relations；连接角色与动画/书籍直接写入 anime_characters（双端同步兼容）
    if (mode === 'characters') {
      const parseCharId = (s: string) =>
        s.startsWith('char:') ? Number.parseInt(s.slice(5), 10) : NaN
      const parseAnimeWorkId = (s: string) => {
        if (s.startsWith('work:anime:')) return Number.parseInt(s.slice(11), 10)
        if (s.startsWith('work:book:')) return Number.parseInt(s.slice(10), 10)
        return NaN
      }
      const cFrom = parseCharId(fromId)
      const cTo = parseCharId(nodeId)
      const wFrom = parseAnimeWorkId(fromId)
      const wTo = parseAnimeWorkId(nodeId)

      if (!Number.isNaN(cFrom) && !Number.isNaN(cTo)) {
        setSaving(true)
        try {
          await studioApi.upsertCharacterRelation({
            sourceCharacterId: cFrom,
            targetCharacterId: cTo,
            relationType: label
          })
          await loadGraph(mode)
          setStatusTip(`已写入角色关系「${label}」`)
          window.setTimeout(() => setStatusTip(null), 2600)
          if (onDataChanged) await onDataChanged()
        } finally {
          setSaving(false)
        }
        return
      }
      if ((!Number.isNaN(cFrom) && !Number.isNaN(wTo)) || (!Number.isNaN(wFrom) && !Number.isNaN(cTo))) {
        const charId = !Number.isNaN(cFrom) ? cFrom : cTo
        const animeId = !Number.isNaN(wTo) ? wTo : wFrom
        setSaving(true)
        try {
          await studioApi.linkCharacterWork(charId, animeId, label, null)
          await loadGraph(mode)
          setStatusTip(`已关联角色与作品（${label}）`)
          window.setTimeout(() => setStatusTip(null), 2600)
          if (onDataChanged) await onDataChanged()
        } finally {
          setSaving(false)
        }
        return
      }
    }

    const edge: CanvasCustomEdge = {
      id: `ce:${Date.now()}`,
      from: fromId,
      to: nodeId,
      label
    }
    await persistCustom({
      ...customState,
      edges: [...customState.edges, edge]
    })
  }

  // 合并自动节点 + 用户自定义节点坐标映射（含按作品/节点聚焦子图筛选）
  const focusOptions = useMemo(() => {
    const list = (graph?.nodes ?? []).map((n) => ({
      id: n.nodeId,
      label: n.label,
      badge: n.badge || (n.nodeType === 'character' ? '角色' : '作品'),
      isWork: Boolean(n.workKind && n.workId != null)
    }))
    const q = focusSearch.trim().toLowerCase()
    const filtered = q
      ? list.filter((it) => it.label.toLowerCase().includes(q) || it.badge.toLowerCase().includes(q))
      : list
    return filtered.sort((a, b) =>
      a.isWork !== b.isWork ? (a.isWork ? -1 : 1) : a.label.localeCompare(b.label, 'zh-CN')
    )
  }, [graph, focusSearch])

  const effectiveScopeWorkId = useMemo(() => {
    if (!scopeWorkNodeId || !graph || mode !== 'characters') return null
    return graph.nodes.some((n) => n.nodeId === scopeWorkNodeId) ? scopeWorkNodeId : null
  }, [scopeWorkNodeId, graph, mode])

  const effectiveFocusId = useMemo(() => {
    if (!focusNodeId || !graph) return null
    const exists =
      graph.nodes.some((n) => n.nodeId === focusNodeId) ||
      customState.nodes.some((n) => n.id === focusNodeId)
    return exists ? focusNodeId : null
  }, [focusNodeId, graph, customState.nodes])

  const visibleNodeIdSet = useMemo(() => {
    if (!effectiveFocusId && !effectiveScopeWorkId) return null
    if (!graph) return null
    const adj = new Map<string, Set<string>>()
    const addEdge = (a: string, b: string) => {
      if (!adj.has(a)) adj.set(a, new Set())
      if (!adj.has(b)) adj.set(b, new Set())
      adj.get(a)!.add(b)
      adj.get(b)!.add(a)
    }
    graph.edges.forEach((e) => addEdge(e.fromId, e.toId))
    customState.edges.forEach((e) => addEdge(e.from, e.to))

    // 若当前在「单部作品的角色关系星图」作用域内，先限定该作品及其出场角色集合，绝不带出无关作品
    if (mode === 'characters' && effectiveScopeWorkId) {
      const workScoped = new Set<string>([effectiveScopeWorkId])
      for (const nxt of adj.get(effectiveScopeWorkId) ?? []) {
        if (nxt.startsWith('char:') || customState.nodes.some((cn) => cn.id === nxt)) {
          workScoped.add(nxt)
        }
      }
      if (!effectiveFocusId || effectiveFocusId === effectiveScopeWorkId) {
        return workScoped
      }
      // 在该作品作用域内进一步聚焦某位角色：只展示该作品 + 该角色 + 与其有直接关系的本作品角色
      const subVisited = new Set<string>([effectiveScopeWorkId, effectiveFocusId])
      for (const nxt of adj.get(effectiveFocusId) ?? []) {
        if (workScoped.has(nxt)) {
          subVisited.add(nxt)
        }
      }
      return subVisited
    }

    if (!effectiveFocusId) return null
    const visited = new Set<string>([effectiveFocusId])
    if (mode === 'ip') {
      // IP 模式：展示整个连通企划簇（聚焦某作品时自动带出所属系列与同系列全部成员）
      const queue = [effectiveFocusId]
      while (queue.length > 0) {
        const cur = queue.shift()!
        for (const nxt of adj.get(cur) ?? []) {
          if (!visited.has(nxt)) {
            visited.add(nxt)
            queue.push(nxt)
          }
        }
      }
    } else if (mode === 'characters') {
      // 角色图谱模式：聚焦作品 -> 作品 + 出场角色；聚焦角色 -> 角色 + 出演作品 + 直接关系角色
      const hop1 = Array.from(adj.get(effectiveFocusId) ?? [])
      hop1.forEach((id) => visited.add(id))
      if (effectiveFocusId.startsWith('work:')) {
        hop1
          .filter((id) => id.startsWith('char:'))
          .forEach((cid) => {
            for (const nxt of adj.get(cid) ?? []) {
              if (nxt.startsWith('char:')) {
                visited.add(nxt)
              }
            }
          })
      }
    } else {
      // 声优星图模式：聚焦 1 跳邻域（聚焦作品仅看其声优，聚焦声优看其关联作品）
      for (const nxt of adj.get(effectiveFocusId) ?? []) {
        visited.add(nxt)
      }
    }
    return visited
  }, [effectiveFocusId, effectiveScopeWorkId, graph, customState.edges, customState.nodes, mode])

  const visibleAutoNodes = useMemo(() => {
    const list = graph?.nodes ?? []
    if (!visibleNodeIdSet) return list
    return list.filter((n) => visibleNodeIdSet.has(n.nodeId))
  }, [graph, visibleNodeIdSet])

  const visibleAutoEdges = useMemo(() => {
    const list = graph?.edges ?? []
    if (!visibleNodeIdSet) return list
    return list.filter((e) => visibleNodeIdSet.has(e.fromId) && visibleNodeIdSet.has(e.toId))
  }, [graph, visibleNodeIdSet])

  const visibleCustomNodes = useMemo(() => {
    if (!visibleNodeIdSet) return customState.nodes
    return customState.nodes.filter((n) => visibleNodeIdSet.has(n.id))
  }, [customState.nodes, visibleNodeIdSet])

  const visibleCustomEdges = useMemo(() => {
    if (!visibleNodeIdSet) return customState.edges
    return customState.edges.filter((e) => visibleNodeIdSet.has(e.from) && visibleNodeIdSet.has(e.to))
  }, [customState.edges, visibleNodeIdSet])

  // 单作品环形星图排布：默认关闭，避免点击作品或角色时卡片凭空跳换位置；仅当用户主动开启星图排布时生效
  const canUseStarLayout = useMemo(() => {
    if (mode !== 'characters' || !effectiveScopeWorkId) return false
    const charCount = visibleAutoNodes.filter((n) => n.nodeType === 'character').length
    return charCount >= 4
  }, [mode, effectiveScopeWorkId, visibleAutoNodes])

  const starMapOverride = useMemo(() => {
    if (!starLayoutEnabled || !canUseStarLayout || !effectiveScopeWorkId) {
      return null
    }
    const workNode = visibleAutoNodes.find((n) => n.nodeId === effectiveScopeWorkId)
    const charNodes = visibleAutoNodes.filter((n) => n.nodeType === 'character')
    if (!workNode || charNodes.length < 4) return null

    const relDeg = new Map<string, number>()
    visibleAutoEdges.forEach((e) => {
      if (e.fromId.startsWith('char:') && e.toId.startsWith('char:')) {
        relDeg.set(e.fromId, (relDeg.get(e.fromId) ?? 0) + 1)
        relDeg.set(e.toId, (relDeg.get(e.toId) ?? 0) + 1)
      }
    })
    const workRoleRank = new Map<string, number>()
    visibleAutoEdges.forEach((e) => {
      if (e.fromId === effectiveScopeWorkId && e.toId.startsWith('char:')) {
        const r = e.relation
        const rank =
          r.includes('主角') || r.includes('女主') || r.includes('男主')
            ? 0
            : r.includes('主要')
              ? 1
              : r.includes('配角')
                ? 2
                : 3
        workRoleRank.set(e.toId, rank)
      }
    })
    const sorted = [...charNodes].sort((a, b) => {
      const ra = workRoleRank.get(a.nodeId) ?? 3
      const rb = workRoleRank.get(b.nodeId) ?? 3
      if (ra !== rb) return ra - rb
      const da = relDeg.get(a.nodeId) ?? 0
      const db = relDeg.get(b.nodeId) ?? 0
      if (da !== db) return db - da
      return a.label.localeCompare(b.label, 'zh-CN')
    })

    const map = new Map<string, { x: number; y: number }>()
    const innerCount = sorted.length <= 8 ? sorted.length : Math.min(8, Math.ceil(sorted.length * 0.42))
    const inner = sorted.slice(0, innerCount)
    const outer = sorted.slice(innerCount)

    const r1 = Math.max(300, (inner.length * 265) / (2 * Math.PI))
    const r2 = outer.length > 0 ? Math.max(r1 + 250, (outer.length * 265) / (2 * Math.PI)) : r1
    // 以作品卡片当前世界坐标为中心展开环形星图，减少视角割裂
    const centerX = Math.round(workNode.x)
    const centerY = Math.round(workNode.y)

    const setWithDrag = (id: string, defX: number, defY: number) => {
      const dragged = starDragOverrides[id]
      map.set(id, dragged ? { x: dragged.x, y: dragged.y } : { x: defX, y: defY })
    }

    setWithDrag(workNode.nodeId, centerX, centerY)
    inner.forEach((n, idx) => {
      const angle = (2 * Math.PI * idx) / Math.max(inner.length, 1) - Math.PI / 2
      setWithDrag(
        n.nodeId,
        Math.round(centerX + Math.cos(angle) * r1),
        Math.round(centerY + Math.sin(angle) * r1 * 0.85)
      )
    })
    outer.forEach((n, idx) => {
      const angle =
        (2 * Math.PI * idx) / Math.max(outer.length, 1) -
        Math.PI / 2 +
        Math.PI / Math.max(outer.length, 1)
      setWithDrag(
        n.nodeId,
        Math.round(centerX + Math.cos(angle) * r2),
        Math.round(centerY + Math.sin(angle) * r2 * 0.85)
      )
    })
    return map
  }, [
    starLayoutEnabled,
    canUseStarLayout,
    effectiveScopeWorkId,
    visibleAutoNodes,
    visibleAutoEdges,
    starDragOverrides
  ])

  // 所有节点始终保持自身世界坐标（移除旧版 viewOffset 强制挪到左上角，点击「仅看相关」卡片原地纹丝不动）
  const allPositionMap = useMemo(() => {
    const map = new Map<string, { x: number; y: number; label: string }>()
    visibleAutoNodes.forEach((n) => {
      const ov = starMapOverride?.get(n.nodeId)
      map.set(n.nodeId, { x: ov ? ov.x : n.x, y: ov ? ov.y : n.y, label: n.label })
    })
    visibleCustomNodes.forEach((n) => map.set(n.id, { x: n.x, y: n.y, label: n.label }))
    return map
  }, [visibleAutoNodes, visibleCustomNodes, starMapOverride])

  // 角色模式下的「作品快速切换」与按作品分组信息（用于视觉分区底框 & 跨作品连线降噪）
  const characterWorkMeta = useMemo(() => {
    if (mode !== 'characters' || !graph) {
      return {
        chips: [] as Array<{ nodeId: string; label: string; charCount: number }>,
        charPrimaryWork: new Map<string, string>(),
        workMembers: new Map<string, string[]>()
      }
    }
    const workNodes = graph.nodes.filter((n) => n.nodeId.startsWith('work:'))
    const membersMap = new Map<string, Set<string>>()
    workNodes.forEach((w) => membersMap.set(w.nodeId, new Set<string>()))
    graph.edges.forEach((e) => {
      if (e.fromId.startsWith('work:') && e.toId.startsWith('char:')) {
        if (!membersMap.has(e.fromId)) membersMap.set(e.fromId, new Set<string>())
        membersMap.get(e.fromId)!.add(e.toId)
      }
    })
    const chips = workNodes
      .map((w) => ({
        nodeId: w.nodeId,
        label: w.label,
        charCount: membersMap.get(w.nodeId)?.size ?? 0
      }))
      .sort((a, b) => b.charCount - a.charCount || a.label.localeCompare(b.label, 'zh-CN'))

    // 按角色数量最多的作品优先归属，与后端分簇逻辑一致
    const charPrimaryWork = new Map<string, string>()
    const workMembers = new Map<string, string[]>()
    chips.forEach((chip) => {
      const assigned: string[] = []
      const set = membersMap.get(chip.nodeId)
      if (set) {
        set.forEach((cid) => {
          if (!charPrimaryWork.has(cid)) {
            charPrimaryWork.set(cid, chip.nodeId)
            assigned.push(cid)
          }
        })
      }
      workMembers.set(chip.nodeId, assigned)
    })
    return { chips, charPrimaryWork, workMembers }
  }, [mode, graph])

  // 为角色图谱的每部作品计算视觉分区底框（仅在非环形星图时显示，彻底区分不同作品避免视觉混杂）
  const workClusterFrames = useMemo(() => {
    if (mode !== 'characters' || starMapOverride || !graph) return []
    const frames: Array<{
      workNodeId: string
      title: string
      charCount: number
      x: number
      y: number
      width: number
      height: number
    }> = []
    for (const chip of characterWorkMeta.chips) {
      const wPos = allPositionMap.get(chip.nodeId)
      if (!wPos) continue
      const memberIds = characterWorkMeta.workMembers.get(chip.nodeId) ?? []
      let minX = wPos.x
      let minY = wPos.y
      let maxX = wPos.x + NODE_WIDTH
      let maxY = wPos.y + NODE_HEIGHT
      let visibleCharCnt = 0
      for (const cid of memberIds) {
        const cPos = allPositionMap.get(cid)
        if (!cPos) continue
        visibleCharCnt += 1
        if (cPos.x < minX) minX = cPos.x
        if (cPos.y < minY) minY = cPos.y
        if (cPos.x + NODE_WIDTH > maxX) maxX = cPos.x + NODE_WIDTH
        if (cPos.y + NODE_HEIGHT > maxY) maxY = cPos.y + NODE_HEIGHT
      }
      const padX = 24
      const padTop = 44
      const padBottom = 22
      frames.push({
        workNodeId: chip.nodeId,
        title: chip.label,
        charCount: visibleCharCnt || chip.charCount,
        x: minX - padX,
        y: minY - padTop,
        width: maxX - minX + padX * 2,
        height: maxY - minY + padTop + padBottom
      })
    }
    return frames
  }, [mode, starMapOverride, graph, characterWorkMeta, allPositionMap])

  // 检测历史保存坐标是否造成作品分区严重重叠，提示用户一键「重置排版」清理
  const hasClusterOverlap = useMemo(() => {
    if (workClusterFrames.length < 2) return false
    for (let i = 0; i < workClusterFrames.length; i += 1) {
      const a = workClusterFrames[i]
      for (let j = i + 1; j < workClusterFrames.length; j += 1) {
        const b = workClusterFrames[j]
        const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x)
        const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y)
        if (overlapX > 36 && overlapY > 36) return true
      }
    }
    return false
  }, [workClusterFrames])

  const fitCameraToPositions = (
    positions: Array<{ x: number; y: number }>,
    animate = true,
    maxAutoZoom = 1
  ) => {
    const el = viewportRef.current
    if (!el || positions.length === 0) return
    let minX = Number.POSITIVE_INFINITY
    let minY = Number.POSITIVE_INFINITY
    let maxX = Number.NEGATIVE_INFINITY
    let maxY = Number.NEGATIVE_INFINITY
    positions.forEach((p) => {
      if (p.x < minX) minX = p.x
      if (p.y < minY) minY = p.y
      if (p.x + NODE_WIDTH > maxX) maxX = p.x + NODE_WIDTH
      if (p.y + NODE_HEIGHT > maxY) maxY = p.y + NODE_HEIGHT
    })
    if (!Number.isFinite(minX)) return
    const pad = 64
    const contentW = Math.max(260, maxX - minX + pad * 2)
    const contentH = Math.max(200, maxY - minY + pad * 2)
    const vw = Math.max(320, el.clientWidth)
    const vh = Math.max(240, el.clientHeight)
    const targetZoom = clampZoom(Math.min(vw / contentW, vh / contentH, maxAutoZoom))
    const centerWorldX = (minX + maxX) / 2
    const centerWorldY = (minY + maxY) / 2
    const targetCam = {
      x: Math.round(vw / 2 - centerWorldX * targetZoom),
      y: Math.round(vh / 2 - centerWorldY * targetZoom),
      zoom: targetZoom
    }
    if (animate) {
      animateCameraTo(targetCam, 220)
    } else {
      commitCamera(targetCam)
    }
  }

  const handleFitView = () => {
    fitCameraToPositions(Array.from(allPositionMap.values()), true, 1)
  }

  // 当切模式、重置排版、或通过顶部下拉/作品胶囊切换焦点时，待图数据就绪自动适配镜头
  useEffect(() => {
    const req = pendingCameraFocusRef.current
    if (!req || allPositionMap.size === 0) return
    pendingCameraFocusRef.current = null
    if (req === '__fit__') {
      fitCameraToPositions(Array.from(allPositionMap.values()), false, 1)
    } else {
      fitCameraToPositions(Array.from(allPositionMap.values()), true, 1)
    }
  }, [allPositionMap])

  const highlightedAutoEdgeSet = useMemo(() => {
    const set = new Set<number>()
    if (!hoveredNodeId) return set
    visibleAutoEdges.forEach((e, idx) => {
      if (e.fromId === hoveredNodeId || e.toId === hoveredNodeId) {
        set.add(idx)
      }
    })
    return set
  }, [hoveredNodeId, visibleAutoEdges])

  const buildEdgePath = (a: { x: number; y: number }, b: { x: number; y: number }) => {
    const aRightToBLeft = b.x >= a.x + NODE_WIDTH - 20
    const bRightToALeft = a.x >= b.x + NODE_WIDTH - 20
    let x1 = a.x + NODE_WIDTH / 2
    let y1 = a.y + NODE_HEIGHT / 2
    let x2 = b.x + NODE_WIDTH / 2
    let y2 = b.y + NODE_HEIGHT / 2

    if (aRightToBLeft) {
      x1 = a.x + NODE_WIDTH
      x2 = b.x
    } else if (bRightToALeft) {
      x1 = a.x
      x2 = b.x + NODE_WIDTH
    }

    if (edgeStyle === 'straight') {
      return { d: `M ${x1} ${y1} L ${x2} ${y2}`, mx: (x1 + x2) / 2, my: (y1 + y2) / 2 }
    }
    if (edgeStyle === 'step') {
      const midX = (x1 + x2) / 2
      return {
        d: `M ${x1} ${y1} L ${midX} ${y1} L ${midX} ${y2} L ${x2} ${y2}`,
        mx: midX,
        my: (y1 + y2) / 2
      }
    }
    const dx = Math.max(48, Math.abs(x2 - x1) * 0.45)
    const c1x = aRightToBLeft ? x1 + dx : bRightToALeft ? x1 - dx : x1
    const c1y = aRightToBLeft || bRightToALeft ? y1 : y1 + (y2 > y1 ? 48 : -48)
    const c2x = aRightToBLeft ? x2 - dx : bRightToALeft ? x2 + dx : x2
    const c2y = aRightToBLeft || bRightToALeft ? y2 : y2 + (y2 > y1 ? -48 : 48)
    return {
      d: `M ${x1} ${y1} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${x2} ${y2}`,
      mx: (x1 + x2) / 2,
      my: (y1 + y2) / 2
    }
  }

  const viewportBgStyle: React.CSSProperties = useMemo(() => {
    if (gridStyle === 'plain') {
      return { background: 'var(--bg-canvas)' }
    }
    const baseStep = gridStyle === 'grid' ? 28 : 24
    const scaled = Math.max(10, baseStep * camera.zoom)
    const pos = `${camera.x}px ${camera.y}px`
    if (gridStyle === 'grid') {
      return {
        backgroundColor: 'var(--bg-canvas)',
        backgroundImage:
          'linear-gradient(to right, var(--border-subtle) 1px, transparent 1px), linear-gradient(to bottom, var(--border-subtle) 1px, transparent 1px)',
        backgroundSize: `${scaled}px ${scaled}px`,
        backgroundPosition: pos
      }
    }
    return {
      backgroundColor: 'var(--bg-canvas)',
      backgroundImage: 'radial-gradient(var(--border-subtle) 1px, transparent 1px)',
      backgroundSize: `${scaled}px ${scaled}px`,
      backgroundPosition: pos
    }
  }, [gridStyle, camera.x, camera.y, camera.zoom])

  const totalNodes = visibleAutoNodes.length + visibleCustomNodes.length
  const totalEdges = visibleAutoEdges.length + visibleCustomEdges.length
  const allGraphNodesCount = (graph?.nodes.length ?? 0) + customState.nodes.length

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div
        className="toolbar-strip"
        style={{
          padding: '8px 14px',
          borderBottom: '1px solid var(--border-subtle)',
          background: 'var(--bg-surface)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          flexWrap: 'wrap'
        }}
      >
        <div className="segmented-group">
          <button
            className={`segmented-btn ${mode === 'ip' ? 'active' : ''}`}
            onClick={() => setMode('ip')}
          >
            IP 企划谱系
          </button>
          <button
            className={`segmented-btn ${mode === 'seiyuu' ? 'active' : ''}`}
            onClick={() => setMode('seiyuu')}
          >
            声优星图
          </button>
          <button
            className={`segmented-btn ${mode === 'characters' ? 'active' : ''}`}
            onClick={() => setMode('characters')}
          >
            角色关系图谱
          </button>
        </div>

        {mode === 'characters' && (
          <>
            <button
              className="btn btn-primary"
              onClick={() => {
                let defaultWorkId: number | null = null
                let defaultWorkTitle: string | null = null
                if (effectiveFocusId && (effectiveFocusId.startsWith('work:anime:') || effectiveFocusId.startsWith('work:book:'))) {
                  const parts = effectiveFocusId.split(':')
                  const idNum = Number.parseInt(parts[2] ?? '', 10)
                  if (!Number.isNaN(idNum)) {
                    defaultWorkId = idNum
                    defaultWorkTitle =
                      graph?.nodes.find((n) => n.nodeId === effectiveFocusId)?.label ?? null
                  }
                }
                setEditTarget({ kind: 'new_character', defaultWorkId, defaultWorkTitle })
              }}
              title="直接在画布中创建新角色（若正聚焦某作品将自动绑定）"
            >
              + 新建角色
            </button>
            {onOpenCharacterManage && (
              <button
                className="btn"
                onClick={() => onOpenCharacterManage(null)}
                title="打开完整角色与关系管理窗口"
              >
                角色管理
              </button>
            )}
          </>
        )}

        {mode === 'ip' && onOpenSeriesManage && (
          <button
            className="btn"
            onClick={onOpenSeriesManage}
            title="重命名、合并、解散系列及维护作品成员定位"
          >
            管理系列
          </button>
        )}

        {/* 按单个作品 / 系列 / 角色 / 声优聚焦局部图谱 */}
        {(graph?.nodes.length ?? 0) > 0 && (
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <input
              className="inline-input"
              style={{ width: 112, height: 26, fontSize: '0.8846rem', padding: '2px 7px' }}
              value={focusSearch}
              onChange={(e) => setFocusSearch(e.target.value)}
              placeholder="搜作品/节点..."
              title="过滤下方聚焦下拉选项"
            />
            <StudioSelect
              className={`studio-select-inline studio-select-cap-lg${effectiveFocusId ? ' studio-select-accent' : ''}`}
              value={effectiveFocusId ?? ''}
              onChange={(v) => {
                setFocusNodeId(v || null)
                setScopeWorkNodeId(v && v.startsWith('work:') ? v : null)
                setStarLayoutEnabled(false)
                setStarDragOverrides({})
                pendingCameraFocusRef.current = v || '__fit__'
              }}
              title="只显示指定作品/系列/角色/声优的相关关联图谱"
              ariaLabel="聚焦指定图谱节点"
              minMenuWidth={220}
              maxMenuWidth={320}
              options={[
                { value: '', label: `聚焦：全部图谱 (${graph?.nodes.length ?? 0})` },
                ...focusOptions.map((opt) => ({
                  value: opt.id,
                  label: opt.label,
                  badges: [opt.badge]
                }))
              ]}
            />
            {(effectiveFocusId || effectiveScopeWorkId) && (
              <button
                className="btn"
                style={{ height: 26, padding: '0 8px', fontSize: '0.8846rem' }}
                onClick={() => {
                  setFocusNodeId(null)
                  setScopeWorkNodeId(null)
                  setStarLayoutEnabled(false)
                  setStarDragOverrides({})
                  setFocusSearch('')
                }}
                title="取消聚焦，恢复显示全部图谱"
              >
                显示全部
              </button>
            )}
          </div>
        )}

        {canUseStarLayout && (
          <button
            className={`btn ${starLayoutEnabled ? 'btn-primary' : ''}`}
            onClick={() => {
              const next = !starLayoutEnabled
              setStarLayoutEnabled(next)
              setStarDragOverrides({})
              pendingCameraFocusRef.current = effectiveScopeWorkId || '__fit__'
            }}
            title={
              starLayoutEnabled
                ? '恢复各节点在全图中的原始位置'
                : '将当前作品的角色临时按同心环形星图展开（不污染全图坐标）'
            }
          >
            {starLayoutEnabled ? '恢复原位排布' : '环形星图排布'}
          </button>
        )}

        {mode === 'characters' && totalEdges > 12 && (
          <button
            className={`btn ${edgesOnlyOnHover ? 'btn-primary' : ''}`}
            onClick={() => setEdgesOnlyOnHover((v) => !v)}
            title="开启后默认隐藏复杂关系连线，鼠标移到任意卡片时才清晰显示其连线"
          >
            {edgesOnlyOnHover ? '连线：仅悬停显示' : '连线：智能降噪'}
          </button>
        )}

        <button
          className={`btn ${showAddNode ? 'btn-primary' : ''}`}
          onClick={() => setShowAddNode((v) => !v)}
          title="在画布上新增自定义卡片节点（如企划说明、世界观分组、待补清单）"
        >
          + 自定义节点
        </button>

        <button
          className={`btn ${connectMode ? 'btn-primary' : ''}`}
          onClick={() => {
            setConnectMode((v) => !v)
            setConnectFromId(null)
          }}
          title="依次点击任意两个节点，创建自定义关联连线"
        >
          {connectMode ? '退出连线模式' : '+ 自定义连线'}
        </button>

        {connectMode && (
          <input
            className="inline-input"
            style={{ width: 110, padding: '4px 8px', fontSize: '0.9231rem' }}
            value={connectLabel}
            onChange={(e) => setConnectLabel(e.target.value)}
            placeholder="连线标签..."
            title="新建自定义连线时显示的标签"
          />
        )}

        <div className="segmented-group">
          <button
            className="segmented-btn"
            onClick={() => zoomAtViewportCenter(cameraRef.current.zoom - 0.12)}
            style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
            title="缩小画布 (滚轮 / Ctrl + -)"
          >
            −
          </button>
          <button
            className={`segmented-btn ${Math.abs(zoom - 1) < 0.01 ? 'active' : ''}`}
            onClick={() => zoomAtViewportCenter(1)}
            style={{ padding: '4px 7px', fontSize: '0.8846rem', minWidth: 46 }}
            title="复位 100% (Ctrl + 0)"
          >
            {Math.round(zoom * 100)}%
          </button>
          <button
            className="segmented-btn"
            onClick={() => zoomAtViewportCenter(cameraRef.current.zoom + 0.12)}
            style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
            title="放大画布 (滚轮 / Ctrl + +)"
          >
            +
          </button>
          <button
            className="segmented-btn"
            onClick={handleFitView}
            style={{ padding: '4px 8px', fontSize: '0.8846rem' }}
            title="自适应缩放居中展示全部节点"
          >
            适配
          </button>
        </div>

        <StudioSelect
          className="studio-select-inline"
          value={gridStyle}
          onChange={(val) => setGridStyle(val as GridStyle)}
          title="画布背景样式"
          ariaLabel="画布背景样式"
          minMenuWidth={120}
          maxMenuWidth={160}
          options={[
            { value: 'dots', label: '点阵底纹' },
            { value: 'grid', label: '方格坐标' },
            { value: 'plain', label: '纯净画纸' }
          ]}
        />

        <StudioSelect
          className="studio-select-inline"
          value={edgeStyle}
          onChange={(val) => setEdgeStyle(val as EdgeStyle)}
          title="连线风格"
          ariaLabel="画布连线风格"
          minMenuWidth={120}
          maxMenuWidth={160}
          options={[
            { value: 'bezier', label: '曲线连线' },
            { value: 'step', label: '直角折线' },
            { value: 'straight', label: '直线连线' }
          ]}
        />

        <button
          className="btn"
          disabled={loading || saving}
          onClick={() => void handleResetLayout()}
          title="恢复按作品自动分区排版，消除不同作品卡片的重叠"
        >
          重置排版
        </button>

        <button
          className="btn"
          disabled={loading || saving || totalNodes === 0}
          onClick={() => void handleExportCanvasPng()}
          title="将当前展示的完整或聚焦关系图谱导出为高清 PNG 图片"
        >
          导出 PNG
        </button>

        <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem', marginLeft: 'auto' }}>
          {statusTip
            ? statusTip
            : connectMode
              ? connectFromId
                ? mode === 'characters'
                  ? '请点击目标节点（角色↔角色写入关系，角色↔作品写入出演；Esc 取消）'
                  : '请点击目标节点完成连线（Esc 取消）'
                : '请先点击起点节点（Esc 取消）'
              : loading
                ? '正在构图...'
                : saving
                  ? '已自动保存'
                  : `节点 ${totalNodes} · 连线 ${totalEdges} · 滚轮缩放 · 拖拽空白/右键平移`}
        </span>
      </div>

      {/* 角色关系图谱：按作品快速筛选隔离栏 */}
      {mode === 'characters' && characterWorkMeta.chips.length > 0 && (
        <div
          style={{
            padding: '6px 14px',
            borderBottom: '1px solid var(--border-subtle)',
            background: 'var(--bg-panel)',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            overflowX: 'auto',
            flexShrink: 0
          }}
        >
          <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)', flexShrink: 0 }}>
            作品分区：
          </span>
          <button
            type="button"
            className={`btn ${!effectiveScopeWorkId && !effectiveFocusId ? 'btn-primary' : ''}`}
            style={{ height: 24, padding: '0 9px', fontSize: '0.8462rem', flexShrink: 0 }}
            onClick={() => {
              setFocusNodeId(null)
              setScopeWorkNodeId(null)
              setStarLayoutEnabled(false)
              setStarDragOverrides({})
              pendingCameraFocusRef.current = '__fit__'
            }}
          >
            全部作品 ({characterWorkMeta.chips.length})
          </button>
          {characterWorkMeta.chips.map((chip) => {
            const active = effectiveScopeWorkId === chip.nodeId
            return (
              <button
                key={chip.nodeId}
                type="button"
                className={`btn ${active ? 'btn-primary' : ''}`}
                style={{
                  height: 24,
                  padding: '0 9px',
                  fontSize: '0.8462rem',
                  flexShrink: 0,
                  maxWidth: 200,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap'
                }}
                title={`只查看《${chip.label}》的角色图谱（共 ${chip.charCount} 位角色）`}
                onClick={() => {
                  if (active && effectiveFocusId === chip.nodeId) {
                    setFocusNodeId(null)
                    setScopeWorkNodeId(null)
                    setStarLayoutEnabled(false)
                    setStarDragOverrides({})
                    pendingCameraFocusRef.current = '__fit__'
                  } else {
                    setScopeWorkNodeId(chip.nodeId)
                    setFocusNodeId(chip.nodeId)
                    setStarLayoutEnabled(false)
                    setStarDragOverrides({})
                    pendingCameraFocusRef.current = chip.nodeId
                  }
                }}
              >
                {chip.label} · {chip.charCount}
              </button>
            )
          })}
          {hasClusterOverlap && !effectiveFocusId && (
            <button
              type="button"
              className="btn"
              style={{
                height: 24,
                padding: '0 10px',
                fontSize: '0.8462rem',
                marginLeft: 'auto',
                flexShrink: 0,
                borderColor: 'var(--accent)',
                color: 'var(--accent)'
              }}
              onClick={() => void handleResetLayout()}
              title="检测到旧版保存坐标导致不同作品重叠，点击即可按作品重新整齐分区"
            >
              检测到作品重叠 · 一键整理分区
            </button>
          )}
        </div>
      )}

      {/* 画布内直接数据编辑条（点击任意节点或连线即可就地改名、改定位、改关系或删除） */}
      <CanvasQuickEditBar
        target={editTarget}
        items={items}
        onClose={() => setEditTarget(null)}
        onOpenCharacterManage={onOpenCharacterManage}
        onChanged={async (tip) => {
          await loadGraph(mode)
          flashTip(tip)
          if (onDataChanged) await onDataChanged()
        }}
        onUpdateCustomNode={handleUpdateCustomNode}
        onDeleteCustomNode={handleDeleteCustomNode}
        onUpdateCustomEdge={handleUpdateCustomEdge}
        onDeleteCustomEdge={handleDeleteCustomEdge}
      />

      {/* 新增自定义节点快捷条 */}
      {showAddNode && (
        <div
          style={{
            padding: '8px 14px',
            borderBottom: '1px solid var(--border-subtle)',
            background: 'var(--bg-panel)',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            flexWrap: 'wrap'
          }}
        >
          <input
            className="inline-input"
            style={{ width: 190 }}
            value={newNodeTitle}
            onChange={(e) => setNewNodeTitle(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleAddCustomNode()
            }}
            placeholder="节点标题（如：键社三部曲 / 2026 必补）"
            autoFocus
          />
          <input
            className="inline-input"
            style={{ width: 220 }}
            value={newNodeSub}
            onChange={(e) => setNewNodeSub(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void handleAddCustomNode()
            }}
            placeholder="简短说明（可选）"
          />
          <div style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
            {CUSTOM_COLORS.map((c) => (
              <button
                key={c}
                onClick={() => setNewNodeColor(c)}
                style={{
                  width: 18,
                  height: 18,
                  borderRadius: '50%',
                  background: c,
                  border: newNodeColor === c ? '2px solid var(--text-primary)' : '1px solid rgba(0,0,0,0.2)',
                  cursor: 'pointer'
                }}
                title={`选择色标 ${c}`}
              />
            ))}
          </div>
          <button
            className="btn btn-primary"
            disabled={!newNodeTitle.trim()}
            onClick={() => void handleAddCustomNode()}
          >
            放到画布中央
          </button>
          <button className="btn" onClick={() => setShowAddNode(false)}>
            取消
          </button>
        </div>
      )}

      <div
        ref={viewportRef}
        onContextMenu={(e) => {
          // 画布内右键用于平移拖拽，不弹浏览器默认右键菜单
          e.preventDefault()
        }}
        onMouseDown={(e) => {
          // 右键(2)、中键(1) 在画布任意位置都能拖动画布；左键(0)在空白区域也可拖动
          const isBlank = e.target === e.currentTarget || (e.target as HTMLElement).dataset.canvasBlank === '1'
          if (e.button === 1 || e.button === 2 || (e.button === 0 && isBlank)) {
            e.preventDefault()
            panMovedRef.current = false
            setPanState({
              startClientX: e.clientX,
              startClientY: e.clientY,
              origCamX: cameraRef.current.x,
              origCamY: cameraRef.current.y
            })
          }
        }}
        style={{
          flex: 1,
          overflow: 'hidden',
          position: 'relative',
          cursor: panState ? 'grabbing' : connectMode ? 'crosshair' : 'grab',
          userSelect: panState || dragState ? 'none' : undefined,
          ...viewportBgStyle
        }}
      >
        {allGraphNodesCount === 0 ? (
          <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-muted)' }}>
            <div className="heading-font" style={{ fontSize: '1.1538rem', marginBottom: 6 }}>
              {mode === 'ip'
                ? '当前库中尚未建立跨媒体系列企划'
                : mode === 'seiyuu'
                  ? '当前库中尚未提取到声优关联'
                  : '当前库中尚未添加角色或角色关系'}
            </div>
            <div style={{ fontSize: '0.9615rem', marginBottom: 12 }}>
              {mode === 'characters'
                ? '你可以点击上方「角色管理」创建角色、关联动画/书籍并建立角色间关系，系统会自动生成可自由排版的角色图谱。'
                : '你可以点击上方「+ 自定义节点」自由搭建个人作品关系板，或在藏品中维护系列与声优后自动成图。'}
            </div>
            {mode === 'characters' && onOpenCharacterManage && (
              <button className="btn btn-primary" onClick={() => onOpenCharacterManage(null)}>
                立即打开角色与关系管理
              </button>
            )}
          </div>
        ) : (
          <div
            data-canvas-blank="1"
            style={{
              position: 'absolute',
              left: 0,
              top: 0,
              width: 1,
              height: 1,
              transform: `translate3d(${camera.x}px, ${camera.y}px, 0) scale(${camera.zoom})`,
              transformOrigin: '0 0',
              willChange: 'transform'
            }}
          >
            {/* 角色关系图谱：按作品绘制视觉分区底框，让不同作品清晰隔离不再混杂 */}
            {workClusterFrames.map((frame) => {
              const isScoped = effectiveScopeWorkId === frame.workNodeId
              return (
                <div
                  key={`cluster-${frame.workNodeId}`}
                  data-canvas-blank="1"
                  style={{
                    position: 'absolute',
                    left: frame.x,
                    top: frame.y,
                    width: frame.width,
                    height: frame.height,
                    borderRadius: 16,
                    background: isScoped
                      ? 'color-mix(in srgb, var(--accent) 4%, var(--bg-surface))'
                      : 'color-mix(in srgb, var(--bg-elevated) 55%, transparent)',
                    border: `1.5px dashed ${isScoped ? 'var(--accent)' : 'var(--border-strong)'}`,
                    pointerEvents: 'none',
                    zIndex: 0
                  }}
                >
                  <div
                    style={{
                      position: 'absolute',
                      left: 14,
                      top: 10,
                      right: 14,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 8,
                      pointerEvents: 'auto'
                    }}
                  >
                    <span
                      className="heading-font"
                      style={{
                        fontSize: '0.8846rem',
                        fontWeight: 700,
                        color: isScoped ? 'var(--accent)' : 'var(--text-secondary)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap'
                      }}
                      title={frame.title}
                    >
                      {frame.title} · {frame.charCount} 位角色
                    </span>
                    {!isScoped && (
                      <button
                        type="button"
                        className="btn-ghost"
                        style={{
                          padding: '0 6px',
                          height: 20,
                          fontSize: '0.7692rem',
                          color: 'var(--text-muted)',
                          flexShrink: 0
                        }}
                        onMouseDown={(ev) => ev.stopPropagation()}
                        onClick={(ev) => {
                          ev.stopPropagation()
                          setScopeWorkNodeId(frame.workNodeId)
                          setFocusNodeId(frame.workNodeId)
                          setStarLayoutEnabled(false)
                          setStarDragOverrides({})
                        }}
                        title={`只看《${frame.title}》分区（不移动卡片位置）`}
                      >
                        只看此作品
                      </button>
                    )}
                  </div>
                </div>
              )
            })}

            <svg
              width={1}
              height={1}
              style={{
                position: 'absolute',
                left: 0,
                top: 0,
                pointerEvents: 'none',
                overflow: 'visible',
                zIndex: 1
              }}
            >
              {visibleAutoEdges.map((edge, idx) => {
                const a = allPositionMap.get(edge.fromId)
                const b = allPositionMap.get(edge.toId)
                if (!a || !b) return null
                const isHi = highlightedAutoEdgeSet.has(idx)
                const isCharRel = edge.fromId.startsWith('char:') && edge.toId.startsWith('char:')
                const isDenseFullView =
                  mode === 'characters' && !effectiveFocusId && !effectiveScopeWorkId && visibleAutoNodes.length > 16
                const isCrossWorkEdge =
                  mode === 'characters' &&
                  isCharRel &&
                  characterWorkMeta.charPrimaryWork.get(edge.fromId) !==
                    characterWorkMeta.charPrimaryWork.get(edge.toId)

                // 若开启「仅悬停显示」，未悬停且未聚焦时仅保留极淡轮廓或隐藏复杂线
                if (edgesOnlyOnHover && !hoveredNodeId && !effectiveFocusId) {
                  return null
                }

                const { d, mx, my } = buildEdgePath(a, b)
                const showEdgeLabel =
                  Boolean(edge.relation) &&
                  (isHi ||
                    (!isDenseFullView && isCharRel && visibleAutoNodes.length <= 24) ||
                    (Boolean(effectiveFocusId) && visibleAutoNodes.length <= 12) ||
                    (mode === 'characters' && visibleAutoEdges.length <= 24))
                const labelText = edge.relation.length > 10 ? `${edge.relation.slice(0, 10)}…` : edge.relation
                const pillW = Math.max(46, labelText.length * 10 + 16)

                const baseOpacity = hoveredNodeId
                  ? isHi
                    ? 0.96
                    : 0.12
                  : isDenseFullView
                    ? isCrossWorkEdge
                      ? 0.14
                      : isCharRel
                        ? 0.45
                        : 0.28
                    : isCharRel
                      ? 0.78
                      : 0.56

                return (
                  <g key={`auto-${idx}`}>
                    <path
                      d={d}
                      fill="none"
                      stroke="transparent"
                      strokeWidth={12}
                      style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                      onClick={(ev) => {
                        ev.stopPropagation()
                        selectAutoEdgeForEdit(edge)
                      }}
                    >
                      <title>点击编辑此关联（{edge.relation || '关联'}）</title>
                    </path>
                    <path
                      d={d}
                      fill="none"
                      stroke={isHi ? 'var(--accent)' : isCharRel ? 'var(--badge-purple-stroke)' : 'var(--text-muted)'}
                      strokeWidth={isHi ? 2.6 : isCrossWorkEdge && isDenseFullView ? 1.2 : isCharRel ? 1.85 : 1.35}
                      strokeDasharray={isCharRel ? '5 3' : undefined}
                      strokeOpacity={baseOpacity}
                    />
                    {showEdgeLabel && (
                      <g
                        opacity={hoveredNodeId && !isHi ? 0.2 : 0.95}
                        style={{ pointerEvents: 'auto', cursor: 'pointer' }}
                        onClick={(ev) => {
                          ev.stopPropagation()
                          selectAutoEdgeForEdit(edge)
                        }}
                      >
                        <title>点击编辑或解除此关联（{edge.relation}）</title>
                        <rect
                          x={mx - pillW / 2}
                          y={my - 9}
                          width={pillW}
                          height={18}
                          rx={6}
                          fill="var(--bg-elevated)"
                          stroke={isCharRel ? 'var(--badge-purple-stroke)' : 'var(--border-strong)'}
                          strokeWidth={1}
                        />
                        <text
                          x={mx}
                          y={my + 3.5}
                          textAnchor="middle"
                          fontSize={10}
                          fill={isCharRel ? 'var(--badge-purple-fg)' : 'var(--text-secondary)'}
                          fontWeight={600}
                        >
                          {labelText}
                        </text>
                      </g>
                    )}
                  </g>
                )
              })}

              {visibleCustomEdges.map((edge) => {
                const a = allPositionMap.get(edge.from)
                const b = allPositionMap.get(edge.to)
                if (!a || !b) return null
                const { d, mx, my } = buildEdgePath(a, b)
                return (
                  <g key={edge.id}>
                    <path
                      d={d}
                      fill="none"
                      stroke="transparent"
                      strokeWidth={12}
                      style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                      onClick={(ev) => {
                        ev.stopPropagation()
                        setEditTarget({ kind: 'custom_edge', edge })
                      }}
                    />
                    <path
                      d={d}
                      fill="none"
                      stroke="var(--accent)"
                      strokeWidth={2.2}
                      strokeDasharray="6 4"
                    />
                    {edge.label && (
                      <g
                        style={{ pointerEvents: 'auto', cursor: 'pointer' }}
                        onClick={(ev) => {
                          ev.stopPropagation()
                          setEditTarget({ kind: 'custom_edge', edge })
                        }}
                      >
                        <title>点击编辑或删除此自定义连线（{edge.label}）</title>
                        <rect
                          x={mx - 30}
                          y={my - 10}
                          width={60}
                          height={20}
                          rx={6}
                          fill="var(--bg-elevated)"
                          stroke="var(--accent)"
                          strokeWidth={1}
                        />
                        <text
                          x={mx}
                          y={my + 4}
                          textAnchor="middle"
                          fontSize={10.5}
                          fill="var(--accent)"
                          fontWeight={600}
                        >
                          {edge.label.slice(0, 6)}
                        </text>
                      </g>
                    )}
                  </g>
                )
              })}
            </svg>

            {/* 自动生成节点 */}
            {visibleAutoNodes.map((node) => {
              const isHub =
                node.nodeType === 'hub' || node.nodeType === 'series' || node.nodeType === 'seiyuu'
              const isCharacter = node.nodeType === 'character'
              const isHovered = hoveredNodeId === node.nodeId
              const isConnectSrc = connectFromId === node.nodeId
              const isFocusedNode = effectiveFocusId === node.nodeId
              const posOverride = starMapOverride?.get(node.nodeId)
              const renderX = posOverride ? posOverride.x : node.x
              const renderY = posOverride ? posOverride.y : node.y
              return (
                <div
                  key={node.nodeId}
                  onMouseEnter={() => setHoveredNodeId(node.nodeId)}
                  onMouseLeave={() =>
                    setHoveredNodeId((prev) => (prev === node.nodeId ? null : prev))
                  }
                  onMouseDown={(e) => {
                    if (e.button !== 0) return
                    e.stopPropagation()
                    if (connectMode) {
                      void handleNodeConnectClick(node.nodeId)
                      return
                    }
                    movedRef.current = false
                    setDragState({
                      nodeId: node.nodeId,
                      isCustom: false,
                      isStarTemp: Boolean(starMapOverride),
                      startClientX: e.clientX,
                      startClientY: e.clientY,
                      origX: renderX,
                      origY: renderY
                    })
                  }}
                  onClick={() => {
                    if (connectMode || movedRef.current) return
                    if (node.nodeId.startsWith('series:')) {
                      const sid = Number.parseInt(node.nodeId.slice(7), 10)
                      if (!Number.isNaN(sid)) {
                        setEditTarget({ kind: 'series', seriesId: sid, name: node.label })
                      }
                    } else if (node.nodeId.startsWith('char:')) {
                      const cid = Number.parseInt(node.nodeId.slice(5), 10)
                      if (!Number.isNaN(cid)) {
                        setEditTarget({ kind: 'character', characterId: cid })
                      }
                    } else if (node.workKind && node.workId != null) {
                      const parentSeriesEdge = graph?.edges.find(
                        (e) => e.fromId.startsWith('series:') && e.toId === node.nodeId
                      )
                      const parentSeriesId = parentSeriesEdge
                        ? Number.parseInt(parentSeriesEdge.fromId.slice(7), 10)
                        : null
                      setEditTarget({
                        kind: 'work',
                        workKind: node.workKind,
                        workId: node.workId,
                        parentSeriesId:
                          parentSeriesId != null && !Number.isNaN(parentSeriesId)
                            ? parentSeriesId
                            : null
                      })
                      onSelectWork(node.workKind, node.workId)
                    }
                  }}
                  onDoubleClick={() => {
                    if (connectMode) return
                    if (isCharacter && onOpenCharacterManage) {
                      const parsedCid =
                        node.workId ?? Number.parseInt(node.nodeId.replace(/^char:/, ''), 10)
                      onOpenCharacterManage(Number.isNaN(parsedCid) ? null : parsedCid)
                      return
                    }
                    if (node.workKind && node.workId != null && onOpenReview) {
                      onOpenReview(node.workKind, node.workId)
                    }
                  }}
                  style={{
                    position: 'absolute',
                    left: renderX,
                    top: renderY,
                    width: NODE_WIDTH,
                    minHeight: NODE_HEIGHT,
                    padding: '10px 12px',
                    borderRadius: isCharacter ? '12px' : 'var(--radius-sm)',
                    background: isHub
                      ? 'var(--accent-soft)'
                      : isCharacter
                        ? 'var(--badge-purple-surface)'
                        : 'var(--bg-elevated)',
                    border: `${isFocusedNode ? 2 : 1.5}px solid ${
                      isConnectSrc
                        ? 'var(--warning)'
                        : isFocusedNode || isHovered || isHub
                          ? 'var(--accent)'
                          : isCharacter
                            ? 'var(--badge-purple-border)'
                            : 'var(--border-strong)'
                    }`,
                    boxShadow: isHovered
                      ? '0 6px 18px rgba(0,0,0,0.14)'
                      : 'var(--shadow-card)',
                    cursor: connectMode ? 'crosshair' : 'grab',
                    userSelect: 'none',
                    zIndex: isHovered ? 5 : isHub ? 3 : 2
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 6,
                      marginBottom: 4
                    }}
                  >
                    <span
                      className={`badge ${isHub ? 'badge-vault' : ''}`}
                      style={{
                        fontSize: '0.8077rem',
                        ...(isCharacter
                          ? {
                              background: 'var(--badge-purple-bg)',
                              color: 'var(--badge-purple-fg)',
                              borderColor: 'var(--badge-purple-border)'
                            }
                          : undefined)
                      }}
                    >
                      {node.badge ||
                        (isCharacter
                          ? '角色'
                          : node.nodeType === 'series'
                            ? '企划核心'
                            : node.nodeType === 'seiyuu'
                              ? '声优枢纽'
                              : node.workKind === 'game'
                                ? '游戏'
                                : node.workKind === 'book'
                                  ? '书籍'
                                  : node.workKind === 'audio'
                                    ? '音声'
                                    : '动画')}
                    </span>
                    <button
                      className="btn-ghost"
                      style={{
                        padding: '0 5px',
                        height: 18,
                        fontSize: '0.8077rem',
                        color: isFocusedNode ? 'var(--accent)' : 'var(--text-muted)',
                        fontWeight: isFocusedNode ? 700 : 500
                      }}
                      title={
                        isFocusedNode
                          ? effectiveScopeWorkId && node.nodeId !== effectiveScopeWorkId
                            ? '取消聚焦角色，返回当前作品（卡片原地不动）'
                            : '取消聚焦，恢复显示全部图谱（卡片原地不动）'
                          : '只看此节点的相关关联（保持卡片在原地不动）'
                      }
                      onMouseDown={(ev) => ev.stopPropagation()}
                      onClick={(ev) => {
                        ev.stopPropagation()
                        // 仅筛选可见节点，保持所有卡片世界坐标与相机位置完全不变，零跳位割裂感
                        if (isFocusedNode) {
                          if (effectiveScopeWorkId && node.nodeId !== effectiveScopeWorkId) {
                            setFocusNodeId(effectiveScopeWorkId)
                          } else {
                            setFocusNodeId(null)
                            setScopeWorkNodeId(null)
                            setStarLayoutEnabled(false)
                            setStarDragOverrides({})
                          }
                        } else {
                          if (node.nodeId.startsWith('work:')) {
                            setScopeWorkNodeId(node.nodeId)
                          }
                          setFocusNodeId(node.nodeId)
                        }
                      }}
                    >
                      {isFocusedNode
                        ? effectiveScopeWorkId && node.nodeId !== effectiveScopeWorkId
                          ? '返回本作品'
                          : '取消聚焦'
                        : '仅看相关'}
                    </button>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                    {isCharacter && node.imageUrl && (
                      <div
                        style={{
                          width: 34,
                          height: 34,
                          borderRadius: '50%',
                          overflow: 'hidden',
                          flexShrink: 0,
                          border: '1px solid var(--badge-purple-border)',
                          background: 'var(--bg-panel)'
                        }}
                      >
                        <CoverImage
                          coverUrl={node.imageUrl}
                          mediaBaseUrl={mediaBaseUrl}
                          alt={node.label}
                          style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center' }}
                          fallback={null}
                        />
                      </div>
                    )}
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div
                        className="heading-font"
                        style={{
                          fontWeight: 700,
                          fontSize: '0.9615rem',
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis'
                        }}
                        title={node.label}
                      >
                        {node.label}
                      </div>
                      <div
                        style={{
                          fontSize: '0.8462rem',
                          color: 'var(--text-muted)',
                          marginTop: 3,
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis'
                        }}
                      >
                        {node.sublabel}
                      </div>
                    </div>
                  </div>
                </div>
              )
            })}

            {/* 用户自定义节点 */}
            {visibleCustomNodes.map((cnode) => {
              const isHovered = hoveredNodeId === cnode.id
              const isConnectSrc = connectFromId === cnode.id
              return (
                <div
                  key={cnode.id}
                  onMouseEnter={() => setHoveredNodeId(cnode.id)}
                  onMouseLeave={() => setHoveredNodeId((prev) => (prev === cnode.id ? null : prev))}
                  onMouseDown={(e) => {
                    if (e.button !== 0) return
                    e.stopPropagation()
                    if (connectMode) {
                      void handleNodeConnectClick(cnode.id)
                      return
                    }
                    movedRef.current = false
                    setDragState({
                      nodeId: cnode.id,
                      isCustom: true,
                      isStarTemp: false,
                      startClientX: e.clientX,
                      startClientY: e.clientY,
                      origX: cnode.x,
                      origY: cnode.y
                    })
                  }}
                  onClick={() => {
                    if (connectMode || movedRef.current) return
                    setEditTarget({ kind: 'custom_node', node: cnode })
                  }}
                  style={{
                    position: 'absolute',
                    left: cnode.x,
                    top: cnode.y,
                    width: NODE_WIDTH,
                    minHeight: NODE_HEIGHT,
                    padding: '10px 12px',
                    borderRadius: 'var(--radius-sm)',
                    background: 'var(--bg-elevated)',
                    border: `2px solid ${isConnectSrc ? 'var(--warning)' : cnode.color}`,
                    boxShadow: isHovered
                      ? '0 6px 18px rgba(0,0,0,0.16)'
                      : 'var(--shadow-card)',
                    cursor: connectMode ? 'crosshair' : 'grab',
                    userSelect: 'none',
                    zIndex: isHovered ? 6 : 4
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
                    <span
                      className="badge"
                      style={{
                        fontSize: '0.8077rem',
                        background: cnode.color,
                        color: '#fff',
                        borderColor: cnode.color
                      }}
                    >
                      自定义节点
                    </span>
                    <button
                      className="btn-ghost"
                      style={{ padding: '0 4px', fontSize: '0.9231rem', color: 'var(--text-muted)' }}
                      title="删除自定义节点"
                      onMouseDown={(ev) => ev.stopPropagation()}
                      onClick={(ev) => {
                        ev.stopPropagation()
                        void handleDeleteCustomNode(cnode.id)
                      }}
                    >
                      ×
                    </button>
                  </div>
                  <div
                    className="heading-font"
                    style={{
                      fontWeight: 700,
                      fontSize: '0.9615rem',
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis'
                    }}
                    title={cnode.label}
                  >
                    {cnode.label}
                  </div>
                  <div
                    style={{
                      fontSize: '0.8462rem',
                      color: 'var(--text-secondary)',
                      marginTop: 3,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis'
                    }}
                    title={cnode.subtitle}
                  >
                    {cnode.subtitle}
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
