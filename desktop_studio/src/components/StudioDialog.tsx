import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'

export type DialogTone = 'danger' | 'warning' | 'info'

export interface ConfirmDialogOptions {
  title: string
  message: string
  detail?: string
  confirmText?: string
  cancelText?: string
  tone?: DialogTone
}

export interface AlertDialogOptions {
  title?: string
  message: string
  detail?: string
  confirmText?: string
  tone?: DialogTone
}

interface StudioDialogContextValue {
  confirm: (options: ConfirmDialogOptions) => Promise<boolean>
  alert: (options: AlertDialogOptions | string) => Promise<void>
}

interface ActiveDialogState {
  kind: 'confirm' | 'alert'
  title: string
  message: string
  detail?: string
  confirmText: string
  cancelText: string
  tone: DialogTone
  resolve: (result: boolean) => void
}

const StudioDialogContext = createContext<StudioDialogContextValue | null>(null)

export const StudioDialogProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [active, setActive] = useState<ActiveDialogState | null>(null)
  const queueRef = useRef<ActiveDialogState[]>([])
  const activeRef = useRef<ActiveDialogState | null>(null)
  const cancelBtnRef = useRef<HTMLButtonElement | null>(null)
  const confirmBtnRef = useRef<HTMLButtonElement | null>(null)

  const pumpNext = useCallback(() => {
    if (activeRef.current) return
    const next = queueRef.current.shift() ?? null
    activeRef.current = next
    setActive(next)
  }, [])

  const finishCurrent = useCallback(
    (result: boolean) => {
      const current = activeRef.current
      if (!current) return
      activeRef.current = null
      setActive(null)
      current.resolve(result)
      queueMicrotask(() => pumpNext())
    },
    [pumpNext]
  )

  const confirm = useCallback(
    (options: ConfirmDialogOptions): Promise<boolean> =>
      new Promise<boolean>((resolve) => {
        queueRef.current.push({
          kind: 'confirm',
          title: options.title,
          message: options.message,
          detail: options.detail,
          confirmText: options.confirmText ?? (options.tone === 'danger' ? '确认删除' : '确定'),
          cancelText: options.cancelText ?? '取消',
          tone: options.tone ?? 'warning',
          resolve
        })
        pumpNext()
      }),
    [pumpNext]
  )

  const alert = useCallback(
    (options: AlertDialogOptions | string): Promise<void> =>
      new Promise<void>((resolve) => {
        const opts: AlertDialogOptions =
          typeof options === 'string' ? { message: options } : options
        queueRef.current.push({
          kind: 'alert',
          title: opts.title ?? '提示',
          message: opts.message,
          detail: opts.detail,
          confirmText: opts.confirmText ?? '知道了',
          cancelText: '',
          tone: opts.tone ?? 'info',
          resolve: () => resolve()
        })
        pumpNext()
      }),
    [pumpNext]
  )

  // 自动聚焦：危险操作默认聚焦「取消」防误按回车，普通提示聚焦主按钮
  useEffect(() => {
    if (!active) return
    const timer = window.setTimeout(() => {
      if (active.kind === 'confirm' && active.tone === 'danger') {
        cancelBtnRef.current?.focus()
      } else {
        confirmBtnRef.current?.focus()
      }
    }, 10)
    return () => window.clearTimeout(timer)
  }, [active])

  // 捕获阶段拦截 Esc，仅关闭本确认框而不误关底层弹窗
  useEffect(() => {
    if (!active) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        finishCurrent(false)
      }
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [active, finishCurrent])

  const toneColor =
    active?.tone === 'danger'
      ? 'var(--danger)'
      : active?.tone === 'warning'
        ? 'var(--warning)'
        : 'var(--accent)'

  const toneSoftBg =
    active?.tone === 'danger'
      ? 'rgba(185, 59, 48, 0.13)'
      : active?.tone === 'warning'
        ? 'rgba(180, 118, 28, 0.14)'
        : 'var(--accent-soft)'

  return (
    <StudioDialogContext.Provider value={{ confirm, alert }}>
      {children}
      {active && (
        <div
          className="studio-dialog-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="studio-dialog-title"
          onClick={() => finishCurrent(false)}
        >
          <div
            className="studio-dialog-card"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="studio-dialog-accent" style={{ background: toneColor }} />
            <div className="studio-dialog-body">
              <div className="studio-dialog-head">
                <div
                  className="studio-dialog-icon"
                  style={{ background: toneSoftBg, color: toneColor, borderColor: toneColor }}
                  aria-hidden="true"
                >
                  {active.tone === 'danger' ? '!' : active.tone === 'warning' ? '⚠' : 'i'}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div
                    id="studio-dialog-title"
                    className="heading-font"
                    style={{
                      fontSize: '1.1538rem',
                      fontWeight: 700,
                      color: 'var(--text-primary)',
                      lineHeight: 1.35
                    }}
                  >
                    {active.title}
                  </div>
                  <div
                    style={{
                      marginTop: 6,
                      fontSize: '1rem',
                      lineHeight: 1.6,
                      color: 'var(--text-secondary)',
                      wordBreak: 'break-word'
                    }}
                  >
                    {active.message}
                  </div>
                  {active.detail && (
                    <div
                      style={{
                        marginTop: 8,
                        padding: '7px 10px',
                        borderRadius: 'var(--radius-sm)',
                        background: 'var(--bg-surface)',
                        border: '1px solid var(--border-subtle)',
                        fontSize: '0.8846rem',
                        lineHeight: 1.55,
                        color: 'var(--text-muted)'
                      }}
                    >
                      {active.detail}
                    </div>
                  )}
                </div>
              </div>

              <div className="studio-dialog-actions">
                {active.kind === 'confirm' && (
                  <button
                    ref={cancelBtnRef}
                    type="button"
                    className="btn"
                    style={{ minWidth: 78, height: 32 }}
                    onClick={() => finishCurrent(false)}
                  >
                    {active.cancelText}
                  </button>
                )}
                <button
                  ref={confirmBtnRef}
                  type="button"
                  className={`btn ${active.tone === 'danger' ? 'btn-danger' : 'btn-primary'}`}
                  style={{ minWidth: 88, height: 32 }}
                  onClick={() => finishCurrent(true)}
                >
                  {active.confirmText}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </StudioDialogContext.Provider>
  )
}

export function useStudioDialog(): StudioDialogContextValue {
  const ctx = useContext(StudioDialogContext)
  if (!ctx) {
    throw new Error('useStudioDialog must be used inside StudioDialogProvider')
  }
  return ctx
}
