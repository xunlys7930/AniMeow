import React, { useEffect, useMemo, useState } from 'react'
import { studioApi } from '../api'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import type { TagManageItem } from '../types'

interface TagManagePanelProps {
  tags: TagManageItem[]
  busy: boolean
  onBusy: (msg: string | null) => void
  onNotice: (msg: string) => void
  onError: (msg: string) => void
  onRefresh: () => Promise<void>
}

const PAGE_SIZE = 60

export const TagManagePanel: React.FC<TagManagePanelProps> = ({
  tags,
  busy,
  onBusy,
  onNotice,
  onError,
  onRefresh
}) => {
  const dialog = useStudioDialog()
  const [search, setSearch] = useState('')
  const [filterMode, setFilterMode] = useState<'all' | 'unused' | 'blocked'>('all')
  const [sortBy, setSortBy] = useState<'usage' | 'name'>('usage')
  const [visibleLimit, setVisibleLimit] = useState(PAGE_SIZE)

  // 仅对当前处于编辑/合并状态的行挂载输入框与候选列表，彻底消除 O(N^2) DOM 卡顿
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editingName, setEditingName] = useState('')
  const [mergingId, setMergingId] = useState<number | null>(null)
  const [mergeSearch, setMergeSearch] = useState('')
  const [mergeTargetId, setMergeTargetId] = useState<number | ''>('')

  useEffect(() => {
    setVisibleLimit(PAGE_SIZE)
  }, [search, filterMode, sortBy])

  const unusedCount = useMemo(() => tags.filter((t) => t.usageCount === 0).length, [tags])
  const blockedCount = useMemo(() => tags.filter((t) => t.blocked).length, [tags])

  const visibleTags = useMemo(() => {
    const q = search.trim().toLowerCase()
    const list = tags.filter((t) => {
      if (filterMode === 'unused' && t.usageCount > 0) return false
      if (filterMode === 'blocked' && !t.blocked) return false
      if (q && !t.name.toLowerCase().includes(q)) return false
      return true
    })
    return [...list].sort((a, b) =>
      sortBy === 'name'
        ? a.name.localeCompare(b.name, 'zh-CN')
        : b.usageCount !== a.usageCount
          ? b.usageCount - a.usageCount
          : a.name.localeCompare(b.name, 'zh-CN')
    )
  }, [tags, search, filterMode, sortBy])

  const renderedTags = useMemo(
    () => visibleTags.slice(0, visibleLimit),
    [visibleTags, visibleLimit]
  )

  const mergeCandidates = useMemo(() => {
    if (mergingId == null) return []
    const q = mergeSearch.trim().toLowerCase()
    return tags
      .filter((o) => o.id !== mergingId && (!q || o.name.toLowerCase().includes(q)))
      .slice(0, 60)
  }, [tags, mergingId, mergeSearch])

  const handleRename = async (t: TagManageItem) => {
    const next = editingName.trim().replace(/^#+/, '').trim()
    if (!next || next === t.name) {
      setEditingId(null)
      return
    }
    onBusy(`正在重命名标签「${t.name}」...`)
    onError('')
    try {
      await studioApi.renameTag(t.id, next)
      setEditingId(null)
      await onRefresh()
      onNotice(`已将标签「${t.name}」更新为「${next}」（如与已有标签同名将自动合并）`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleMerge = async (src: TagManageItem, targetId: number) => {
    const dst = tags.find((x) => x.id === targetId)
    if (!dst || dst.id === src.id) return
    onBusy(`正在合并标签「${src.name}」→「${dst.name}」...`)
    onError('')
    try {
      await studioApi.mergeTags(src.id, dst.id)
      setMergingId(null)
      setMergeTargetId('')
      setMergeSearch('')
      await onRefresh()
      onNotice(`已将标签「${src.name}」合并到「${dst.name}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleToggleBlocked = async (t: TagManageItem) => {
    onBusy(`正在${t.blocked ? '取消屏蔽' : '屏蔽'}标签「${t.name}」...`)
    onError('')
    try {
      await studioApi.toggleTagBlocked(t.id, !t.blocked)
      await onRefresh()
      onNotice(`已${t.blocked ? '取消屏蔽' : '屏蔽'}标签「${t.name}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleDelete = async (t: TagManageItem) => {
    if (t.usageCount > 0) {
      const ok = await dialog.confirm({
        title: '删除正在使用的标签',
        message: `确定彻底删除标签「${t.name}」吗？`,
        detail: `该标签当前正被 ${t.usageCount} 部作品使用，删除后将从这些作品上同步移除。`,
        confirmText: '确认删除',
        tone: 'danger'
      })
      if (!ok) {
        return
      }
    }
    onBusy(`正在删除标签「${t.name}」...`)
    onError('')
    try {
      await studioApi.deleteTag(t.id)
      if (editingId === t.id) setEditingId(null)
      if (mergingId === t.id) setMergingId(null)
      await onRefresh()
      onNotice(`已删除标签「${t.name}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handlePurgeUnused = async () => {
    if (unusedCount === 0) return
    onBusy(`正在清理 ${unusedCount} 个未使用标签...`)
    onError('')
    try {
      const removed = await studioApi.purgeUnusedTags()
      await onRefresh()
      onNotice(`已清理 ${removed} 个 0 使用的闲置标签`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <input
          className="input-field"
          style={{ width: 220, padding: '5px 10px', fontSize: '0.9615rem' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={`搜索全部 ${tags.length} 个标签...`}
        />
        <div className="segmented-group">
          <button
            className={`segmented-btn ${filterMode === 'all' ? 'active' : ''}`}
            onClick={() => setFilterMode('all')}
          >
            全部 ({tags.length})
          </button>
          <button
            className={`segmented-btn ${filterMode === 'unused' ? 'active' : ''}`}
            onClick={() => setFilterMode('unused')}
          >
            未使用 ({unusedCount})
          </button>
          <button
            className={`segmented-btn ${filterMode === 'blocked' ? 'active' : ''}`}
            onClick={() => setFilterMode('blocked')}
          >
            已屏蔽 ({blockedCount})
          </button>
        </div>
        <StudioSelect
          className="studio-select-inline"
          value={sortBy}
          onChange={(val) => setSortBy(val as 'usage' | 'name')}
          ariaLabel="标签排序方式"
          minMenuWidth={144}
          maxMenuWidth={190}
          options={[
            { value: 'usage', label: '按使用次数排序' },
            { value: 'name', label: '按标签名称排序' }
          ]}
        />
        <div style={{ flex: 1 }} />
        {unusedCount > 0 && (
          <button
            className="btn"
            style={{ fontSize: '0.8846rem' }}
            disabled={busy}
            onClick={() => void handlePurgeUnused()}
          >
            一键清理未使用 ({unusedCount})
          </button>
        )}
      </div>

      <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
        修改、合并或删除标签会同步更新动画、书籍、游戏与音声上的标签记录；重命名若与已有标签同名将自动合并去重。
      </div>

      {visibleTags.length === 0 ? (
        <div className="empty-state" style={{ padding: 48 }}>
          当前筛选条件下暂无标签。
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {renderedTags.map((t) => {
            const isEditing = editingId === t.id
            const isMerging = mergingId === t.id
            return (
              <div
                key={t.id}
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 6,
                  padding: '6px 10px',
                  borderRadius: 'var(--radius-sm)',
                  border: `1px solid ${isMerging || isEditing ? 'var(--accent)' : 'var(--border-subtle)'}`,
                  background: 'var(--bg-surface)'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span className="badge mono" style={{ minWidth: 46, textAlign: 'center' }}>
                    {t.usageCount} 部
                  </span>

                  {isEditing ? (
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                      <input
                        className="inline-input"
                        style={{ width: 170, height: 26, fontSize: '0.9615rem', fontWeight: 600 }}
                        value={editingName}
                        autoFocus
                        onChange={(e) => setEditingName(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') void handleRename(t)
                          if (e.key === 'Escape') setEditingId(null)
                        }}
                      />
                      <button
                        className="btn btn-primary"
                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                        disabled={busy || !editingName.trim()}
                        onClick={() => void handleRename(t)}
                      >
                        保存
                      </button>
                      <button
                        className="btn"
                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                        onClick={() => setEditingId(null)}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <span
                      style={{
                        fontSize: '1rem',
                        fontWeight: 600,
                        cursor: 'pointer'
                      }}
                      title="点击重命名标签"
                      onClick={() => {
                        setEditingId(t.id)
                        setEditingName(t.name)
                      }}
                    >
                      #{t.name}
                    </span>
                  )}

                  {t.blocked && (
                    <span
                      className="badge"
                      style={{ background: 'rgba(181, 61, 47, 0.12)', color: 'var(--danger)' }}
                    >
                      已屏蔽
                    </span>
                  )}

                  <div style={{ display: 'inline-flex', alignItems: 'center', gap: 5, marginLeft: 'auto' }}>
                    {!isEditing && (
                      <button
                        className="btn"
                        style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                        disabled={busy}
                        onClick={() => {
                          setEditingId(t.id)
                          setEditingName(t.name)
                        }}
                      >
                        重命名
                      </button>
                    )}
                    <button
                      className={`btn ${isMerging ? 'btn-primary' : ''}`}
                      style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                      disabled={busy || tags.length < 2}
                      onClick={() => {
                        if (isMerging) {
                          setMergingId(null)
                        } else {
                          setMergingId(t.id)
                          setMergeSearch('')
                          setMergeTargetId('')
                        }
                      }}
                    >
                      {isMerging ? '收起合并' : '合并到…'}
                    </button>
                    <button
                      className="btn"
                      style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                      disabled={busy}
                      onClick={() => void handleToggleBlocked(t)}
                    >
                      {t.blocked ? '取消屏蔽' : '屏蔽'}
                    </button>
                    <button
                      className="btn"
                      style={{ padding: '3px 8px', fontSize: '0.8846rem', color: 'var(--danger)' }}
                      disabled={busy}
                      onClick={() => void handleDelete(t)}
                    >
                      删除
                    </button>
                  </div>
                </div>

                {isMerging && (
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 6,
                      flexWrap: 'wrap',
                      padding: '6px 8px',
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-panel)',
                      border: '1px dashed var(--accent)'
                    }}
                  >
                    <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
                      将 #{t.name} 合并入：
                    </span>
                    <input
                      className="inline-input"
                      style={{ width: 140, height: 26, fontSize: '0.9231rem' }}
                      value={mergeSearch}
                      onChange={(e) => {
                        setMergeSearch(e.target.value)
                        setMergeTargetId('')
                      }}
                      placeholder="搜索目标标签..."
                      autoFocus
                    />
                    <StudioSelect
                      className="studio-select-inline studio-select-cap-lg"
                      value={mergeTargetId === '' ? '' : String(mergeTargetId)}
                      onChange={(val) =>
                        setMergeTargetId(val ? Number.parseInt(val, 10) : '')
                      }
                      ariaLabel="选择合并目标标签"
                      minMenuWidth={210}
                      maxMenuWidth={300}
                      options={[
                        {
                          value: '',
                          label: `选择目标标签（显示前 ${mergeCandidates.length} 项）...`
                        },
                        ...mergeCandidates.map((o) => ({
                          value: String(o.id),
                          label: `#${o.name} (${o.usageCount})`
                        }))
                      ]}
                    />
                    <button
                      className="btn btn-primary"
                      style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
                      disabled={busy || mergeTargetId === ''}
                      onClick={() => {
                        if (typeof mergeTargetId === 'number') {
                          void handleMerge(t, mergeTargetId)
                        }
                      }}
                    >
                      确认合并
                    </button>
                  </div>
                )}
              </div>
            )
          })}

          {visibleLimit < visibleTags.length && (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 10,
                padding: '10px 0'
              }}
            >
              <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
                已显示 {renderedTags.length} / {visibleTags.length} 个标签
              </span>
              <button
                className="btn"
                onClick={() => setVisibleLimit((v) => v + PAGE_SIZE)}
              >
                加载更多 (+{Math.min(PAGE_SIZE, visibleTags.length - visibleLimit)})
              </button>
              <button
                className="btn"
                onClick={() => setVisibleLimit(visibleTags.length)}
              >
                显示全部
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
