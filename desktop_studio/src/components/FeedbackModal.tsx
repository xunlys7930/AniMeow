import React, { useEffect, useState } from 'react'
import { STUDIO_DESKTOP_VERSION, studioApi } from '../api'
import qqGroupQrImg from '../assets/qq-group-qr.png'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'

export const FEEDBACK_QQ_GROUP_NAME = '追番喵喵喵用户交流群'
export const FEEDBACK_QQ_GROUP_NUMBER = '1073623448'
export const FEEDBACK_QQ_GROUP_URL = 'https://qm.qq.com/q/c0EM1RFudq'

interface FeedbackModalProps {
  open: boolean
  onClose: () => void
  onToast?: (msg: string) => void
}

async function copyTextSafe(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 回退到临时 textarea 复制
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

export const FeedbackModal: React.FC<FeedbackModalProps> = ({ open, onClose, onToast }) => {
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const {
    offset: modalOffset,
    isMoved: modalMoved,
    resetOffset: resetModalOffset,
    dragHandlers: modalDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })

  const [copiedKind, setCopiedKind] = useState<'number' | 'link' | null>(null)
  const [statusHint, setStatusHint] = useState<string | null>(null)

  useEffect(() => {
    if (!open) {
      setCopiedKind(null)
      setStatusHint(null)
      return
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  const flashHint = (msg: string, kind: 'number' | 'link' | null = null) => {
    setStatusHint(msg)
    setCopiedKind(kind)
    onToast?.(msg)
    window.setTimeout(() => {
      setCopiedKind((cur) => (cur === kind ? null : cur))
    }, 2200)
  }

  const handleCopyGroupNumber = async () => {
    const ok = await copyTextSafe(FEEDBACK_QQ_GROUP_NUMBER)
    if (ok) {
      flashHint(`已复制 QQ 群号：${FEEDBACK_QQ_GROUP_NUMBER}`, 'number')
    } else {
      flashHint(`复制失败，请手动输入群号：${FEEDBACK_QQ_GROUP_NUMBER}`)
    }
  }

  const handleCopyGroupLink = async () => {
    const ok = await copyTextSafe(FEEDBACK_QQ_GROUP_URL)
    if (ok) {
      flashHint('已复制加群邀请链接，可粘贴至浏览器或 QQ 打开', 'link')
    } else {
      flashHint(`复制失败，请手动访问：${FEEDBACK_QQ_GROUP_URL}`)
    }
  }

  const handleOpenJoinLink = async () => {
    try {
      await studioApi.openHttpsUrl(FEEDBACK_QQ_GROUP_URL)
      flashHint('已在系统浏览器打开加群页面，按提示确认加入即可')
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      flashHint(`打开链接失败：${msg}（可先复制群号 ${FEEDBACK_QQ_GROUP_NUMBER} 手动加入）`)
    }
  }

  if (!open) return null

  return (
    <div
      className="modal-backdrop"
      style={{ zIndex: modalZ, paddingTop: 48 }}
      onPointerDownCapture={bringToFront}
      onClick={onClose}
    >
      <div
        className="feedback-modal"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="问题反馈与用户交流群"
      >
        <div
          className="settings-modal-header"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
        >
          <div>
            <div className="heading-font" style={{ fontSize: '1.1538rem', fontWeight: 700 }}>
              问题反馈与用户交流群
            </div>
            <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', marginTop: 2 }}>
              追番喵桌面版 v{STUDIO_DESKTOP_VERSION} · 欢迎进群反馈 Bug、建议与新想法
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {modalMoved && (
              <button type="button" className="btn btn-ghost" onClick={resetModalOffset}>
                归位
              </button>
            )}
            <button type="button" className="btn" onClick={onClose}>
              关闭 (Esc)
            </button>
          </div>
        </div>

        <div className="feedback-modal-body">
          <div className="feedback-info-col">
            <div className="feedback-group-card">
              <div className="feedback-group-label">官方 QQ 交流与反馈群</div>
              <div className="heading-font feedback-group-name">{FEEDBACK_QQ_GROUP_NAME}</div>

              <div className="feedback-number-row">
                <div>
                  <div className="feedback-field-caption">群号</div>
                  <div className="mono feedback-number-value">{FEEDBACK_QQ_GROUP_NUMBER}</div>
                </div>
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => void handleCopyGroupNumber()}
                >
                  {copiedKind === 'number' ? '已复制群号' : '复制 QQ 群号'}
                </button>
              </div>

              <div className="feedback-action-row">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => void handleOpenJoinLink()}
                >
                  点击链接加入群聊
                </button>
                <button
                  type="button"
                  className="btn"
                  onClick={() => void handleCopyGroupLink()}
                >
                  {copiedKind === 'link' ? '已复制链接' : '复制加群链接'}
                </button>
              </div>

              <div className="mono feedback-link-preview" title={FEEDBACK_QQ_GROUP_URL}>
                {FEEDBACK_QQ_GROUP_URL}
              </div>
            </div>

            {statusHint && <div className="feedback-status-banner">{statusHint}</div>}

            <div className="settings-note-box">
              <div style={{ fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>
                反馈问题小贴士
              </div>
              <div>
                1. 遇到界面错位、同步或播放异常时，进群反馈并附上截图与版本号（v
                {STUDIO_DESKTOP_VERSION}）会更容易复现。
              </div>
              <div style={{ marginTop: 3 }}>
                2. 如遇 AI 助手报错，可在对话顶部「运行记录」里点击「导出脱敏反馈诊断」或「复制报告」发到群里，不含藏品正文与密钥。
              </div>
            </div>
          </div>

          <div className="feedback-qr-col">
            <div className="feedback-qr-frame">
              <img
                src={qqGroupQrImg}
                alt={`${FEEDBACK_QQ_GROUP_NAME} 二维码（群号 ${FEEDBACK_QQ_GROUP_NUMBER}）`}
                className="feedback-qr-img"
              />
            </div>
            <div className="feedback-qr-caption">
              打开手机 QQ 扫一扫，直接加入【{FEEDBACK_QQ_GROUP_NAME}】
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
