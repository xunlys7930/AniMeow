import { useEffect, useState } from 'react'
import { formatCarrierLabel, studioApi } from '../api'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import { startNativeWindowDragFromEvent } from '../utils/popout'
import { StudioSelect } from './StudioSelect'
import type { ActiveCompanionInfo, CuratorItem } from '../types'

interface QuickCaptureHudProps {
  open: boolean
  items: CuratorItem[]
  defaultItem: CuratorItem | null
  activeCompanion?: ActiveCompanionInfo | null
  onClose: () => void
  onSaved: () => Promise<void>
  onPopOut?: () => void
  standalone?: boolean
}

export function QuickCaptureHud({
  open,
  items,
  defaultItem,
  activeCompanion,
  onClose,
  onSaved,
  onPopOut,
  standalone = false
}: QuickCaptureHudProps) {
  const { zIndex: panelZ, bringToFront } = useFloatingLayer(open, 'panel')
  const {
    offset: panelOffset,
    isMoved: panelMoved,
    resetOffset: resetPanelOffset,
    dragHandlers: panelDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: false, minY: -540 })

  const [targetKey, setTargetKey] = useState<string>('')
  const [noteText, setNoteText] = useState<string>('')
  const [episodeInput, setEpisodeInput] = useState<string>('')
  const [pastedImage, setPastedImage] = useState<string | null>(null)
  const [saving, setSaving] = useState<boolean>(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setErrorMsg(null)
    if (activeCompanion) {
      const runningGame = items.find(
        (it) => it.kind === 'game' && it.id === activeCompanion.workId
      )
      if (runningGame) {
        setTargetKey(`game:${runningGame.id}`)
        return
      }
    }
    if (defaultItem) {
      setTargetKey(`${defaultItem.kind}:${defaultItem.id}`)
    } else if (items[0]) {
      setTargetKey(`${items[0].kind}:${items[0].id}`)
    }
  }, [open, defaultItem?.id, defaultItem?.kind, activeCompanion?.workId, items.length])

  if (!open) return null

  const handlePaste = (e: React.ClipboardEvent<HTMLElement>) => {
    const clipItems = e.clipboardData?.items
    if (!clipItems) return
    for (let i = 0; i < clipItems.length; i++) {
      const item = clipItems[i]
      if (item.type.startsWith('image/')) {
        const blob = item.getAsFile()
        if (!blob) continue
        e.preventDefault()
        const reader = new FileReader()
        reader.onload = () => {
          if (typeof reader.result === 'string') {
            setPastedImage(reader.result)
          }
        }
        reader.readAsDataURL(blob)
        break
      }
    }
  }

  const handleSaveCapture = async () => {
    const clean = noteText.trim()
    if ((!clean && !pastedImage) || !targetKey) return
    const target = items.find((it) => `${it.kind}:${it.id}` === targetKey)
    if (!target) return

    const epNum = Number.parseInt(episodeInput, 10)
    setSaving(true)
    setErrorMsg(null)
    try {
      await studioApi.saveCapture({
        workKind: target.kind,
        workId: target.id,
        workTitle: target.title,
        note: clean,
        episodeHint: Number.isFinite(epNum) && epNum > 0 ? epNum : null,
        imageBase64: pastedImage,
        appendToMemo: true
      })
      await onSaved()
      setNoteText('')
      setEpisodeInput('')
      setPastedImage(null)
      onClose()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="card-box"
      onPointerDownCapture={standalone ? undefined : bringToFront}
      onPaste={handlePaste}
      style={
        standalone
          ? {
              position: 'relative',
              width: '100%',
              height: '100%',
              borderRadius: 0,
              border: 'none',
              boxShadow: 'none',
              padding: 12,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              overflowY: 'auto'
            }
          : {
              position: 'fixed',
              right: 20,
              bottom: 36,
              width: 410,
              zIndex: panelZ,
              border: '1px solid var(--border-strong)',
              boxShadow: '0 14px 36px rgba(0, 0, 0, 0.24)',
              padding: 12,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              transform: panelMoved ? `translate(${panelOffset.x}px, ${panelOffset.y}px)` : undefined
            }
      }
    >
      <div
        {...(standalone ? {} : panelDragHandlers)}
        onMouseDown={standalone ? startNativeWindowDragFromEvent : undefined}
        title={
          standalone
            ? '按住标题栏可拖到桌面任意位置或副屏，双击最大化'
            : '按住标题栏可自由拖动速记窗口，双击归位'
        }
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 6,
          cursor: 'grab',
          userSelect: 'none'
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>⋮⋮</span>
          <span className="badge badge-accent mono">Alt+Space</span>
          <strong className="heading-font">灵感速记与分镜截图微窗</strong>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
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
            <button type="button" className="btn btn-ghost" style={{ height: 24 }} onClick={resetPanelOffset}>
              归位
            </button>
          )}
          <button type="button" className="btn btn-ghost" style={{ height: 24 }} onClick={onClose}>
            {standalone ? '关闭' : '收起'}
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
          先入库任意一部动画、书籍、Galgame 或音声藏品后，即可随时呼出此微窗归档分镜考据或粘贴剪贴板截图。
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', gap: 6 }}>
            <StudioSelect
              className="studio-select-inline studio-select-flex"
              value={targetKey}
              onChange={(val) => setTargetKey(val)}
              ariaLabel="快捷归档目标藏品"
              zIndex={panelZ + 40}
              minMenuWidth={240}
              maxMenuWidth={340}
              options={items.map((it) => ({
                value: `${it.kind}:${it.id}`,
                label: it.title,
                badges: [formatCarrierLabel(it.kind)]
              }))}
            />
            <input
              className="inline-input mono"
              style={{ width: 82 }}
              type="number"
              min={1}
              value={episodeInput}
              onChange={(e) => setEpisodeInput(e.target.value)}
              placeholder="集/章(选填)"
              title="关联集数或章节号（选填）"
            />
          </div>

          <textarea
            className="inline-input"
            style={{ height: 74, padding: 8, resize: 'none', lineHeight: 1.5 }}
            value={noteText}
            onChange={(e) => setNoteText(e.target.value)}
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                void handleSaveCapture()
              }
            }}
            placeholder="输入神回分镜考据、CG 台词或声优演技细节；按 Ctrl+V 可直接粘贴剪贴板截图（Ctrl+Enter 立即归档）..."
            autoFocus
          />

          {pastedImage ? (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 8,
                padding: 6,
                border: '1px solid var(--border-subtle)',
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-panel)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <img
                  src={pastedImage}
                  alt="剪贴板截图预览"
                  style={{ width: 58, height: 36, objectFit: 'cover', borderRadius: 2 }}
                />
                <span style={{ fontSize: '0.8846rem', color: 'var(--positive)' }}>
                  已附带剪贴板截图帧（仅存本机隔离库）
                </span>
              </div>
              <button className="btn btn-ghost" onClick={() => setPastedImage(null)}>
                移除
              </button>
            </div>
          ) : (
            <div
              style={{
                fontSize: '0.8462rem',
                color: 'var(--text-muted)',
                border: '1px dashed var(--border-subtle)',
                padding: '4px 8px',
                borderRadius: 'var(--radius-sm)'
              }}
            >
              提示：截图后在此窗口按 <code>Ctrl+V</code> 可直接附带画面帧写入本机手帐库
            </div>
          )}

          {errorMsg && (
            <div style={{ fontSize: '0.8846rem', color: 'var(--danger)' }}>{errorMsg}</div>
          )}

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
              私密归档至本机，绝不自动上传社区
            </span>
            <button
              className="btn btn-primary"
              disabled={saving || (!noteText.trim() && !pastedImage)}
              onClick={() => void handleSaveCapture()}
            >
              {saving ? '归档中...' : '归档手帐速记'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
