import { useMemo, useState } from 'react'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'
import {
  DEFAULT_TAB_ORDER,
  MANUAL_META,
  MANUAL_SECTIONS,
  MANUAL_SHORTCUTS,
  WORKSPACE_TAB_DEFS,
  type ManualJumpTarget,
} from '../manualData'
import type { CuratorItem, WorkspaceTab } from '../types'

interface UserManualModalProps {
  open: boolean
  items: CuratorItem[]
  vaultRootCount: number
  tabOrder: WorkspaceTab[]
  themeName: string
  onClose: () => void
  onJump: (target: ManualJumpTarget) => void
  onResetTabOrder: () => void
}

/**
 * 动态更新的用户使用手册弹窗：
 * - 自动读取当前藏品统计、页签排列顺序与主题状态，生成个性化状态提示；
 * - 支持全文检索与一键跳转对应功能区；
 * - 所有内容由 manualData.ts 驱动，功能演进时只需维护数据源即可同步更新。
 */
export function UserManualModal({
  open,
  items,
  vaultRootCount,
  tabOrder,
  themeName,
  onClose,
  onJump,
  onResetTabOrder,
}: UserManualModalProps) {
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const {
    offset: modalOffset,
    isMoved: modalMoved,
    resetOffset: resetModalOffset,
    dragHandlers: modalDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })
  const [activeSectionId, setActiveSectionId] = useState<string>(MANUAL_SECTIONS[0].id)
  const [query, setQuery] = useState<string>('')

  const stats = useMemo(() => {
    let anime = 0
    let book = 0
    let game = 0
    let audio = 0
    let tagged = 0
    for (const it of items) {
      if (it.kind === 'anime') anime += 1
      else if (it.kind === 'book') book += 1
      else if (it.kind === 'game') game += 1
      else if (it.kind === 'audio') audio += 1
      if (it.tags.length > 0) tagged += 1
    }
    return { total: items.length, anime, book, game, audio, tagged }
  }, [items])

  const isCustomTabOrder = useMemo(
    () => tabOrder.join(',') !== DEFAULT_TAB_ORDER.join(','),
    [tabOrder],
  )

  const orderedTabLabels = useMemo(() => {
    const map = new Map(WORKSPACE_TAB_DEFS.map((t) => [t.id, t.label]))
    return tabOrder.map((id) => map.get(id) ?? id)
  }, [tabOrder])

  const normalizedQuery = query.trim().toLowerCase()

  const filteredSections = useMemo(() => {
    if (!normalizedQuery) return MANUAL_SECTIONS
    return MANUAL_SECTIONS.map((sec) => ({
      ...sec,
      items: sec.items.filter((item) => {
        const hay = [
          sec.title,
          item.title,
          item.summary,
          ...item.steps,
          ...(item.tips ?? []),
        ]
          .join(' ')
          .toLowerCase()
        return hay.includes(normalizedQuery)
      }),
    })).filter((sec) => sec.items.length > 0)
  }, [normalizedQuery])

  const filteredShortcuts = useMemo(() => {
    if (!normalizedQuery) return MANUAL_SHORTCUTS
    return MANUAL_SHORTCUTS.filter((sc) =>
      `${sc.keys} ${sc.desc} ${sc.scope}`.toLowerCase().includes(normalizedQuery),
    )
  }, [normalizedQuery])

  if (!open) return null

  const currentSection =
    filteredSections.find((s) => s.id === activeSectionId) ?? filteredSections[0] ?? null

  const triggerJump = (target: ManualJumpTarget) => {
    onClose()
    onJump(target)
  }

  return (
    <div
      className="modal-backdrop"
      onClick={onClose}
      onPointerDownCapture={bringToFront}
      style={{ zIndex: modalZ }}
    >
      <div
        className="card-box manual-modal"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 顶栏 */}
        <div
          className="manual-header"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
        >
          <div>
            <div className="heading-font" style={{ fontSize: '1.2692rem', fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8 }}>
              <span>追番喵 · 使用手册</span>
              <span className="badge badge-accent">{MANUAL_META.version}</span>
              <span className="badge mono">更新于 {MANUAL_META.updatedAt}</span>
            </div>
            <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginTop: 3 }}>
              随功能持续更新的交互指南 — 点任意卡片的「立即前往」可直接跳转到对应页面。
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              className="inline-input"
              style={{ width: 220, height: 30, fontSize: '0.9231rem' }}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索功能、标签、看番、快捷键..."
              autoFocus
            />
            {modalMoved && (
              <button className="btn btn-ghost" onClick={resetModalOffset} title="恢复居中位置">
                归位
              </button>
            )}
            <button className="btn btn-ghost" onClick={onClose} title="关闭手册 (Esc / F1)">
              ✕
            </button>
          </div>
        </div>

        {/* 动态实时状态概览条 */}
        <div className="manual-live-bar">
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', fontSize: '0.9231rem' }}>
            <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>当前工作台：</span>
            <span className="badge mono">藏品 {stats.total}</span>
            <span className="badge mono">动画 {stats.anime} · 书籍 {stats.book} · 游戏 {stats.game} · 音声 {stats.audio}</span>
            <span className="badge">主题：{themeName}</span>
            <span className="badge">本机媒体目录 {vaultRootCount} 个</span>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center', fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
            <span>顶部页签顺序：{orderedTabLabels.join(' → ')}</span>
            {isCustomTabOrder && (
              <button
                className="btn btn-ghost"
                style={{ height: 22, fontSize: '0.8462rem', padding: '0 6px' }}
                onClick={onResetTabOrder}
              >
                恢复默认顺序
              </button>
            )}
          </div>
        </div>

        {/* 主体：左目录 + 右内容 */}
        <div className="manual-body">
          {!normalizedQuery && (
            <nav className="manual-nav">
              {MANUAL_SECTIONS.map((sec) => (
                <button
                  key={sec.id}
                  className={`manual-nav-item ${activeSectionId === sec.id ? 'active' : ''}`}
                  onClick={() => setActiveSectionId(sec.id)}
                >
                  <span>{sec.title}</span>
                  <span className="manual-nav-badge">{sec.badge}</span>
                </button>
              ))}
              <button
                className={`manual-nav-item ${activeSectionId === '__shortcuts' ? 'active' : ''}`}
                onClick={() => setActiveSectionId('__shortcuts')}
              >
                <span>快捷键速查</span>
                <span className="manual-nav-badge">{MANUAL_SHORTCUTS.length}</span>
              </button>
              <button
                className={`manual-nav-item ${activeSectionId === '__changelog' ? 'active' : ''}`}
                onClick={() => setActiveSectionId('__changelog')}
              >
                <span>近期改进亮点</span>
                <span className="manual-nav-badge">新</span>
              </button>
            </nav>
          )}

          <div className="manual-content">
            {normalizedQuery ? (
              <>
                {filteredSections.length === 0 && filteredShortcuts.length === 0 ? (
                  <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-muted)' }}>
                    没有找到包含「{query}」的说明，换个关键词试试？
                  </div>
                ) : (
                  <>
                    {filteredSections.map((sec) => (
                      <div key={sec.id} style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 16 }}>
                        <div style={{ fontSize: '0.9231rem', fontWeight: 700, color: 'var(--text-muted)' }}>
                          {sec.title}
                        </div>
                        {sec.items.map((item) => (
                          <div key={item.id} className="manual-card">
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                              <div className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700 }}>
                                {item.title}
                              </div>
                              {item.action && (
                                <button
                                  className="btn btn-primary"
                                  style={{ height: 26, fontSize: '0.8846rem' }}
                                  onClick={() => triggerJump(item.action!.target)}
                                >
                                  {item.action.label} →
                                </button>
                              )}
                            </div>
                            <div style={{ fontSize: '0.9615rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                              {item.summary}
                            </div>
                            <ol className="manual-steps">
                              {item.steps.map((st, i) => (
                                <li key={i}>{st}</li>
                              ))}
                            </ol>
                          </div>
                        ))}
                      </div>
                    ))}
                    {filteredShortcuts.length > 0 && (
                      <div className="manual-card">
                        <div className="heading-font" style={{ fontSize: '1.0769rem', fontWeight: 700, marginBottom: 8 }}>
                          匹配的快捷键
                        </div>
                        <div className="manual-shortcut-grid">
                          {filteredShortcuts.map((sc) => (
                            <div key={sc.keys} className="manual-shortcut-row">
                              <kbd className="manual-kbd">{sc.keys}</kbd>
                              <span style={{ flex: 1, fontSize: '0.9615rem' }}>{sc.desc}</span>
                              <span className="badge">{sc.scope}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </>
                )}
              </>
            ) : activeSectionId === '__shortcuts' ? (
              <div className="manual-card">
                <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700, marginBottom: 4 }}>
                  快捷键与鼠标操作速查
                </div>
                <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginBottom: 12 }}>
                  掌握这几个高频按键，日常追番和整理藏品会顺手很多。
                </div>
                <div className="manual-shortcut-grid">
                  {MANUAL_SHORTCUTS.map((sc) => (
                    <div key={sc.keys} className="manual-shortcut-row">
                      <kbd className="manual-kbd">{sc.keys}</kbd>
                      <span style={{ flex: 1, fontSize: '0.9615rem' }}>{sc.desc}</span>
                      <span className="badge">{sc.scope}</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : activeSectionId === '__changelog' ? (
              <div className="manual-card">
                <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700, marginBottom: 4 }}>
                  近期改进亮点（{MANUAL_META.updatedAt}）
                </div>
                <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', marginBottom: 10 }}>
                  手册内容会随着版本迭代持续同步，记录你最常用的新能力：
                </div>
                <ul className="manual-steps" style={{ listStyle: 'disc' }}>
                  {MANUAL_META.highlights.map((h, idx) => (
                    <li key={idx}>{h}</li>
                  ))}
                </ul>
              </div>
            ) : currentSection ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {currentSection.items.map((item) => (
                  <div key={item.id} className="manual-card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                      <div className="heading-font" style={{ fontSize: '1.1154rem', fontWeight: 700 }}>
                        {item.title}
                      </div>
                      {item.action && (
                        <button
                          className="btn btn-primary"
                          style={{ height: 26, fontSize: '0.8846rem' }}
                          onClick={() => triggerJump(item.action!.target)}
                        >
                          {item.action.label} →
                        </button>
                      )}
                    </div>
                    <div style={{ fontSize: '0.9615rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                      {item.summary}
                    </div>
                    <ol className="manual-steps">
                      {item.steps.map((st, i) => (
                        <li key={i}>{st}</li>
                      ))}
                    </ol>
                    {item.tips && item.tips.length > 0 && (
                      <div className="manual-tips">
                        {item.tips.map((tip, i) => (
                          <div key={i}>提示：{tip}</div>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  )
}
