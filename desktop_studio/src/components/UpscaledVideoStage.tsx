import React, { useEffect, useRef, useState } from 'react'
import { VIDEO_UPSCALE_OPTIONS, VideoUpscaleMode, WebGlVideoUpscaler } from '../utils/videoUpscaler'

interface UpscaledVideoStageProps {
  videoRef: React.RefObject<HTMLVideoElement | null>
  canvasRef?: React.RefObject<HTMLCanvasElement | null>
  mode: VideoUpscaleMode
  active: boolean
  onStatusChange?: (status: string) => void
  onRenderActiveChange?: (renderActive: boolean) => void
  children: React.ReactNode
  className?: string
  style?: React.CSSProperties
}

function formatClock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00'
  const total = Math.floor(seconds)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (v: number) => String(v).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/**
 * 统一视频画质增强舞台：
 * - 当 `mode === 'off'` 时完全交回原生 `<video controls>`，零额外开销；
 * - 当启用锐化放大或 Anime4K AI 超分时，以 WebGL2 实时渲染到叠加 Canvas，
 *   并提供与全屏兼容的简洁控制栏（全屏作用在舞台容器，确保全屏观看仍保持超分画质）。
 */
export const UpscaledVideoStage: React.FC<UpscaledVideoStageProps> = ({
  videoRef,
  canvasRef: externalCanvasRef,
  mode,
  active,
  onStatusChange,
  onRenderActiveChange,
  children,
  className,
  style
}) => {
  const stageRef = useRef<HTMLDivElement | null>(null)
  const internalCanvasRef = useRef<HTMLCanvasElement | null>(null)
  const upscalerRef = useRef<WebGlVideoUpscaler | null>(null)
  const [renderActive, setRenderActive] = useState(false)
  const [enhancingActive, setEnhancingActive] = useState(false)
  const [fallbackActive, setFallbackActive] = useState(false)
  const [comparingOriginal, setComparingOriginal] = useState(false)
  const [statusLabel, setStatusLabel] = useState('')
  const [controlsVisible, setControlsVisible] = useState(true)
  const [transientNoticeVisible, setTransientNoticeVisible] = useState(false)
  const hideTimerRef = useRef<number | null>(null)
  const transientTimerRef = useRef<number | null>(null)

  // 播放器状态同步（用于超分激活时的自绘控制栏）
  const [paused, setPaused] = useState(true)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [isFullscreen, setIsFullscreen] = useState(false)

  const enhanced = active && mode !== 'off'

  const setCanvasNode = (node: HTMLCanvasElement | null) => {
    internalCanvasRef.current = node
    if (externalCanvasRef && 'current' in externalCanvasRef) {
      ;(externalCanvasRef as React.MutableRefObject<HTMLCanvasElement | null>).current = node
    }
  }

  // 同步全屏状态
  useEffect(() => {
    const onFsChange = () => {
      setIsFullscreen(document.fullscreenElement === stageRef.current)
    }
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  // 监听 <video> 状态以驱动超分控制栏
  useEffect(() => {
    const video = videoRef.current
    if (!video || !enhanced) return

    const syncState = () => {
      setPaused(video.paused)
      setCurrentTime(video.currentTime || 0)
      setDuration(Number.isFinite(video.duration) ? video.duration : 0)
      setVolume(video.volume)
      setMuted(video.muted)
    }
    syncState()
    const events = ['play', 'pause', 'timeupdate', 'durationchange', 'loadedmetadata', 'volumechange', 'seeking', 'seeked']
    events.forEach((ev) => video.addEventListener(ev, syncState))
    return () => {
      events.forEach((ev) => video.removeEventListener(ev, syncState))
    }
  }, [videoRef, enhanced])

  useEffect(() => {
    onRenderActiveChange?.(enhanced && renderActive)
  }, [enhanced, renderActive, onRenderActiveChange])

  // WebGL2 实时渲染循环（优先 requestVideoFrameCallback，按视频原生帧率渲染不浪费 GPU）
  useEffect(() => {
    if (!enhanced) {
      if (upscalerRef.current) {
        upscalerRef.current.dispose()
        upscalerRef.current = null
      }
      setRenderActive(false)
      setEnhancingActive(false)
      setFallbackActive(false)
      setComparingOriginal(false)
      setStatusLabel('')
      onStatusChange?.('')
      return
    }

    const canvas = internalCanvasRef.current
    const video = videoRef.current
    if (!canvas || !video) return

    let upscaler = upscalerRef.current
    if (!upscaler) {
      try {
        upscaler = new WebGlVideoUpscaler(canvas)
        upscalerRef.current = upscaler
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err)
        setRenderActive(false)
        setEnhancingActive(false)
        setFallbackActive(true)
        setStatusLabel(msg)
        onStatusChange?.(msg)
        return
      }
    }

    const initialStatus = upscaler.setMode(mode)
    setStatusLabel(initialStatus)
    onStatusChange?.(initialStatus)

    let cancelled = false
    let rafId: number | null = null
    let rvfcId: number | null = null
    let lastReported = initialStatus

    const drawOnce = () => {
      if (cancelled || !upscalerRef.current || !videoRef.current) return
      const res = upscalerRef.current.renderFrame(videoRef.current)
      if (res.status !== lastReported) {
        lastReported = res.status
        setStatusLabel(res.status)
        onStatusChange?.(res.status)
      }
      setRenderActive(res.rendered)
      setEnhancingActive(res.enhancing)
      setFallbackActive(res.fallback)
    }

    const anyVideo = video as HTMLVideoElement & {
      requestVideoFrameCallback?: (cb: () => void) => number
      cancelVideoFrameCallback?: (id: number) => void
    }

    const scheduleNext = () => {
      if (cancelled) return
      if (typeof anyVideo.requestVideoFrameCallback === 'function') {
        rvfcId = anyVideo.requestVideoFrameCallback(() => {
          drawOnce()
          scheduleNext()
        })
      } else {
        rafId = window.requestAnimationFrame(() => {
          if (!video.paused && !video.ended) {
            drawOnce()
          }
          scheduleNext()
        })
      }
    }

    // 暂停态切换超分档位或拖动进度条时立即刷新单帧，方便实时对比画质
    drawOnce()
    scheduleNext()

    const onFrameTrigger = () => drawOnce()
    video.addEventListener('loadeddata', onFrameTrigger)
    video.addEventListener('seeked', onFrameTrigger)
    video.addEventListener('play', onFrameTrigger)

    return () => {
      cancelled = true
      if (rafId != null) window.cancelAnimationFrame(rafId)
      if (rvfcId != null && typeof anyVideo.cancelVideoFrameCallback === 'function') {
        anyVideo.cancelVideoFrameCallback(rvfcId)
      }
      video.removeEventListener('loadeddata', onFrameTrigger)
      video.removeEventListener('seeked', onFrameTrigger)
      video.removeEventListener('play', onFrameTrigger)
    }
  }, [enhanced, mode, videoRef, onStatusChange])

  // 仅在开启或切换画质增强档位时短暂显示 2.5 秒提示，播放期间不常驻遮挡右上角画面
  useEffect(() => {
    if (transientTimerRef.current != null) {
      window.clearTimeout(transientTimerRef.current)
      transientTimerRef.current = null
    }
    if (!enhanced) {
      setTransientNoticeVisible(false)
      return
    }
    setTransientNoticeVisible(true)
    transientTimerRef.current = window.setTimeout(() => {
      setTransientNoticeVisible(false)
      transientTimerRef.current = null
    }, 2500)
    return () => {
      if (transientTimerRef.current != null) {
        window.clearTimeout(transientTimerRef.current)
        transientTimerRef.current = null
      }
    }
  }, [enhanced, mode])

  // 组件彻底卸载时释放 WebGL 资源
  useEffect(() => {
    return () => {
      if (upscalerRef.current) {
        upscalerRef.current.dispose()
        upscalerRef.current = null
      }
      if (hideTimerRef.current != null) {
        window.clearTimeout(hideTimerRef.current)
      }
      if (transientTimerRef.current != null) {
        window.clearTimeout(transientTimerRef.current)
      }
    }
  }, [])

  const bumpControlsVisibility = () => {
    setControlsVisible(true)
    if (hideTimerRef.current != null) {
      window.clearTimeout(hideTimerRef.current)
    }
    hideTimerRef.current = window.setTimeout(() => {
      if (videoRef.current && !videoRef.current.paused) {
        setControlsVisible(false)
      }
    }, 2600)
  }

  const togglePlay = () => {
    const v = videoRef.current
    if (!v) return
    if (v.paused) {
      v.play().catch(() => {})
    } else {
      v.pause()
    }
    bumpControlsVisibility()
  }

  const toggleFullscreen = () => {
    const stage = stageRef.current
    if (!stage) return
    if (document.fullscreenElement === stage) {
      document.exitFullscreen().catch(() => {})
    } else {
      stage.requestFullscreen().catch(() => {})
    }
  }

  const shortModeLabel =
    VIDEO_UPSCALE_OPTIONS.find((opt) => opt.value === mode)?.shortLabel ?? '超分'
  const showFloatingStatusPill =
    enhanced && Boolean(statusLabel) && (transientNoticeVisible || fallbackActive || comparingOriginal)

  return (
    <div
      ref={stageRef}
      className={className ? `upscale-video-stage ${className}` : 'upscale-video-stage'}
      style={{
        position: 'relative',
        ...style
      }}
      onMouseMove={enhanced ? bumpControlsVisibility : undefined}
      onMouseLeave={
        enhanced
          ? () => {
              setComparingOriginal(false)
              if (videoRef.current && !videoRef.current.paused) {
                setControlsVisible(false)
              }
            }
          : undefined
      }
    >
      {children}

      {enhanced && (
        <canvas
          ref={setCanvasNode}
          className="upscale-video-canvas"
          style={{
            opacity: renderActive && !comparingOriginal ? 1 : 0,
            transition: comparingOriginal ? 'none' : undefined
          }}
        />
      )}

      {/* 超分激活时：点击视频区播放/暂停，双击切换舞台全屏（保留超分画质） */}
      {enhanced && renderActive && (
        <div
          className="upscale-video-hit-area"
          onClick={togglePlay}
          onDoubleClick={toggleFullscreen}
        />
      )}

      {showFloatingStatusPill && (
        <div
          className={`upscale-status-pill ${
            comparingOriginal
              ? 'is-comparing'
              : fallbackActive
                ? 'is-fallback'
                : enhancingActive
                  ? 'is-active'
                  : ''
          }`}
        >
          <span className="upscale-status-dot" aria-hidden="true" />
          <span>
            {comparingOriginal ? '原画对比中 · 松手恢复画质增强' : statusLabel}
          </span>
        </div>
      )}

      {enhanced && renderActive && (
        <div className={`upscale-control-overlay ${controlsVisible || paused ? 'is-visible' : ''}`}>
          <input
            type="range"
            className="upscale-seek-slider"
            min={0}
            max={duration > 0 ? duration : 0}
            step={0.1}
            value={Math.min(currentTime, duration || 0)}
            onChange={(e) => {
              const v = videoRef.current
              if (!v) return
              const next = Number(e.target.value)
              v.currentTime = next
              setCurrentTime(next)
            }}
          />
          <div className="upscale-control-row">
            <button
              type="button"
              className="upscale-ctrl-btn"
              onClick={togglePlay}
              title={paused ? '播放' : '暂停'}
            >
              {paused ? '播放' : '暂停'}
            </button>
            <button
              type="button"
              className="upscale-ctrl-btn"
              onClick={() => {
                const v = videoRef.current
                if (v) v.currentTime = Math.max(0, v.currentTime - 5)
              }}
              title="快退 5 秒"
            >
              -5s
            </button>
            <button
              type="button"
              className="upscale-ctrl-btn"
              onClick={() => {
                const v = videoRef.current
                if (v) v.currentTime = Math.min(v.duration || v.currentTime + 5, v.currentTime + 5)
              }}
              title="快进 5 秒"
            >
              +5s
            </button>
            <span className="upscale-time-text">
              {formatClock(currentTime)} / {formatClock(duration)}
            </span>
            <div style={{ flex: 1 }} />
            <button
              type="button"
              className="upscale-ctrl-btn"
              onClick={() => {
                const v = videoRef.current
                if (v) v.muted = !v.muted
              }}
              title={muted ? '取消静音' : '静音'}
            >
              {muted || volume === 0 ? '静音' : '音量'}
            </button>
            <input
              type="range"
              className="upscale-volume-slider"
              min={0}
              max={1}
              step={0.05}
              value={muted ? 0 : volume}
              onChange={(e) => {
                const v = videoRef.current
                if (!v) return
                const val = Number(e.target.value)
                v.volume = val
                v.muted = val === 0
              }}
            />
            {enhancingActive && (
              <span className="upscale-ctrl-badge" title={statusLabel || `${shortModeLabel}已启用`}>
                {shortModeLabel}
              </span>
            )}
            {enhancingActive && (
              <button
                type="button"
                className={`upscale-ctrl-btn ${comparingOriginal ? 'is-comparing' : ''}`}
                onPointerDown={(e) => {
                  e.preventDefault()
                  setComparingOriginal(true)
                }}
                onPointerUp={() => setComparingOriginal(false)}
                onPointerLeave={() => setComparingOriginal(false)}
                onPointerCancel={() => setComparingOriginal(false)}
                onBlur={() => setComparingOriginal(false)}
                title="按住即时显示未超分的原始画面，松手恢复画质增强"
              >
                {comparingOriginal ? '原画中' : '按住对比原画'}
              </button>
            )}
            <button
              type="button"
              className="upscale-ctrl-btn"
              onClick={toggleFullscreen}
              title={isFullscreen ? '退出全屏' : '全屏播放（保持超分）'}
            >
              {isFullscreen ? '退出全屏' : '全屏'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
