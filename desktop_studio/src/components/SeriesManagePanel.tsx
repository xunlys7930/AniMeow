import React, { useEffect, useMemo, useState } from 'react'
import { formatCarrierLabel, studioApi } from '../api'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import type { CuratorItem, MediaCarrier, SeriesManageItem, SeriesManageMember } from '../types'

interface SeriesManagePanelProps {
  seriesList: SeriesManageItem[]
  allItems: CuratorItem[]
  busy: boolean
  onBusy: (msg: string | null) => void
  onNotice: (msg: string) => void
  onError: (msg: string) => void
  onRefresh: () => Promise<void>
}

const SERIES_PAGE_SIZE = 40

const ROLE_PRESETS = [
  '本篇',
  '续作',
  '剧场版',
  'OVA / 特别篇',
  '外传 / 衍生',
  '原作轻小说',
  '原作漫画',
  '游戏关联',
  '音声关联',
  '系列成员'
]

export const SeriesManagePanel: React.FC<SeriesManagePanelProps> = ({
  seriesList,
  allItems,
  busy,
  onBusy,
  onNotice,
  onError,
  onRefresh
}) => {
  const dialog = useStudioDialog()
  const [search, setSearch] = useState('')
  const [visibleLimit, setVisibleLimit] = useState(SERIES_PAGE_SIZE)
  const [expandedIds, setExpandedIds] = useState<Set<number>>(() => new Set())
  const [renameDrafts, setRenameDrafts] = useState<Record<number, string>>({})
  const [mergingSeriesId, setMergingSeriesId] = useState<number | null>(null)
  const [mergeSearch, setMergeSearch] = useState('')
  const [mergeTargetId, setMergeTargetId] = useState<number | ''>('')
  const [roleDrafts, setRoleDrafts] = useState<Record<string, string>>({})
  const [addSearchBySeries, setAddSearchBySeries] = useState<Record<number, string>>({})
  const [addRoleBySeries, setAddRoleBySeries] = useState<Record<number, string>>({})

  useEffect(() => {
    setVisibleLimit(SERIES_PAGE_SIZE)
  }, [search])

  const filteredSeries = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return seriesList
    return seriesList.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.members.some(
          (m) =>
            m.title.toLowerCase().includes(q) ||
            (m.seriesRole ?? '').toLowerCase().includes(q)
        )
    )
  }, [seriesList, search])

  const renderedSeries = useMemo(
    () => filteredSeries.slice(0, visibleLimit),
    [filteredSeries, visibleLimit]
  )

  const mergeCandidates = useMemo(() => {
    if (mergingSeriesId == null) return []
    const q = mergeSearch.trim().toLowerCase()
    return seriesList
      .filter((o) => o.id !== mergingSeriesId && (!q || o.name.toLowerCase().includes(q)))
      .slice(0, 60)
  }, [seriesList, mergingSeriesId, mergeSearch])

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleRename = async (s: SeriesManageItem) => {
    const nextName = (renameDrafts[s.id] ?? s.name).trim()
    if (!nextName || nextName === s.name) return
    onBusy(`正在重命名系列「${s.name}」...`)
    onError('')
    try {
      await studioApi.renameSeries(s.id, nextName)
      await onRefresh()
      onNotice(`已将系列「${s.name}」重命名为「${nextName}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleMerge = async (source: SeriesManageItem, targetId: number) => {
    const target = seriesList.find((x) => x.id === targetId)
    if (!target || target.id === source.id) return
    const ok = await dialog.confirm({
      title: '合并系列',
      message: `确定将系列「${source.name}」（${source.memberCount} 部）合并入「${target.name}」吗？`,
      detail: '合并后原系列名称将被移除，所有作品成员及定位关系会完整保留至目标系列。',
      confirmText: '确认合并',
      tone: 'warning'
    })
    if (!ok) {
      return
    }
    onBusy(`正在合并系列「${source.name}」→「${target.name}」...`)
    onError('')
    try {
      const moved = await studioApi.mergeSeries(source.id, target.id)
      setMergingSeriesId(null)
      setMergeTargetId('')
      setMergeSearch('')
      await onRefresh()
      onNotice(`已将「${source.name}」合并到「${target.name}」（迁移 ${moved} 项关联）`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleDelete = async (s: SeriesManageItem) => {
    const ok = await dialog.confirm({
      title: '解散系列',
      message: `确定解散系列「${s.name}」吗？`,
      detail: `这只会解除 ${s.memberCount} 部作品的系列归属，不会删除作品本身的进度、评分与评论。`,
      confirmText: '确认解散',
      tone: 'danger'
    })
    if (!ok) {
      return
    }
    onBusy(`正在解散系列「${s.name}」...`)
    onError('')
    try {
      await studioApi.deleteSeries(s.id)
      await onRefresh()
      onNotice(`已解散系列「${s.name}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleUpdateRole = async (seriesId: number, m: SeriesManageMember, nextRole: string) => {
    onBusy(`正在更新《${m.title}》的系列关系...`)
    onError('')
    try {
      await studioApi.setSeriesMemberRole(seriesId, m.kind, m.id, nextRole)
      await onRefresh()
      onNotice(`已更新《${m.title}》在系列中的关系为「${nextRole || '系列成员'}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleRemoveMember = async (seriesId: number, m: SeriesManageMember) => {
    onBusy(`正在将《${m.title}》移出系列...`)
    onError('')
    try {
      await studioApi.removeMemberFromSeries(seriesId, m.kind, m.id)
      await onRefresh()
      onNotice(`已将《${m.title}》移出系列`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleAddMember = async (s: SeriesManageItem, work: CuratorItem) => {
    const role = (addRoleBySeries[s.id] ?? '').trim() || '系列成员'
    onBusy(`正在将《${work.title}》加入系列「${s.name}」...`)
    onError('')
    try {
      await studioApi.batchSeries([[work.kind as MediaCarrier, work.id]], s.name, role)
      await onRefresh()
      setAddSearchBySeries((prev) => ({ ...prev, [s.id]: '' }))
      onNotice(`已将《${work.title}》加入系列「${s.name}」（${role}）`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <input
          className="input-field"
          style={{ width: 280, padding: '6px 10px', fontSize: '0.9615rem' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="搜索系列名或成员作品名..."
        />
        <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
          共 {seriesList.length} 个系列 · 支持重命名、合并拆分系列、调整作品与系列关系（本篇/续作/剧场版/原作等）及增删成员
        </span>
      </div>

      {filteredSeries.length === 0 ? (
        <div className="empty-state" style={{ padding: 48 }}>
          {seriesList.length === 0
            ? '当前藏品库还没有任何系列，可先使用「同系列自动归类」快速建立。'
            : '未匹配到符合条件的系列。'}
        </div>
      ) : (
        renderedSeries.map((s) => {
          const isExpanded = expandedIds.has(s.id) || search.trim().length > 0
          const draftName = renameDrafts[s.id] ?? s.name
          const isMerging = mergingSeriesId === s.id
          const memberKeySet = new Set(s.members.map((m) => `${m.kind}:${m.id}`))
          const addQ = (addSearchBySeries[s.id] ?? '').trim().toLowerCase()
          const candidateWorks = addQ
            ? allItems
                .filter(
                  (it) =>
                    !memberKeySet.has(`${it.kind}:${it.id}`) &&
                    (it.title.toLowerCase().includes(addQ) ||
                      it.originalTitle.toLowerCase().includes(addQ))
                )
                .slice(0, 8)
            : []

          return (
            <div
              key={s.id}
              style={{
                border: `1px solid ${isMerging ? 'var(--accent)' : 'var(--border-subtle)'}`,
                borderRadius: 'var(--radius-md)',
                padding: 12,
                background: 'var(--bg-surface)',
                display: 'flex',
                flexDirection: 'column',
                gap: 8
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <button
                  className="btn"
                  style={{ padding: '3px 8px', fontSize: '0.8846rem' }}
                  onClick={() => toggleExpand(s.id)}
                  title={isExpanded ? '折叠成员' : '展开管理成员与关系'}
                >
                  {isExpanded ? '▾' : '▸'} {s.memberCount} 部
                </button>
                <input
                  className="input-field"
                  style={{ width: 220, padding: '4px 8px', fontSize: '0.9615rem', fontWeight: 600 }}
                  value={draftName}
                  onChange={(e) =>
                    setRenameDrafts((prev) => ({ ...prev, [s.id]: e.target.value }))
                  }
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void handleRename(s)
                  }}
                />
                {draftName.trim() && draftName.trim() !== s.name && (
                  <button
                    className="btn btn-primary"
                    style={{ padding: '4px 9px', fontSize: '0.8846rem' }}
                    disabled={busy}
                    onClick={() => void handleRename(s)}
                  >
                    保存重命名
                  </button>
                )}

                {seriesList.length > 1 && (
                  <button
                    className={`btn ${isMerging ? 'btn-primary' : ''}`}
                    style={{ padding: '4px 9px', fontSize: '0.8846rem' }}
                    disabled={busy}
                    onClick={() => {
                      if (isMerging) {
                        setMergingSeriesId(null)
                      } else {
                        setMergingSeriesId(s.id)
                        setMergeSearch('')
                        setMergeTargetId('')
                      }
                    }}
                  >
                    {isMerging ? '收起合并' : '合并到…'}
                  </button>
                )}

                <div style={{ flex: 1 }} />
                <button
                  className="btn"
                  style={{ padding: '4px 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
                  disabled={busy}
                  onClick={() => void handleDelete(s)}
                >
                  解散系列
                </button>
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
                    合并入目标系列：
                  </span>
                  <input
                    className="inline-input"
                    style={{ width: 150, height: 26, fontSize: '0.9231rem' }}
                    value={mergeSearch}
                    onChange={(e) => {
                      setMergeSearch(e.target.value)
                      setMergeTargetId('')
                    }}
                    placeholder="搜索目标系列..."
                    autoFocus
                  />
                  <StudioSelect
                    className="studio-select-inline studio-select-cap-lg"
                    value={mergeTargetId === '' ? '' : String(mergeTargetId)}
                    onChange={(val) =>
                      setMergeTargetId(val ? Number.parseInt(val, 10) : '')
                    }
                    ariaLabel="选择合并目标系列"
                    minMenuWidth={220}
                    maxMenuWidth={320}
                    options={[
                      { value: '', label: `选择目标系列（显示前 ${mergeCandidates.length} 项）...` },
                      ...mergeCandidates.map((o) => ({
                        value: String(o.id),
                        label: `${o.name} (${o.memberCount})`
                      }))
                    ]}
                  />
                  <button
                    className="btn btn-primary"
                    style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
                    disabled={busy || mergeTargetId === ''}
                    onClick={() => {
                      if (typeof mergeTargetId === 'number') {
                        void handleMerge(s, mergeTargetId)
                      }
                    }}
                  >
                    确认合并
                  </button>
                </div>
              )}

              {isExpanded && (
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 6,
                    paddingTop: 6,
                    borderTop: '1px dashed var(--border-subtle)'
                  }}
                >
                  {s.members.length === 0 ? (
                    <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', padding: '4px 2px' }}>
                      该系列暂无作品成员，可在下方搜索添加，或直接解散系列。
                    </div>
                  ) : (
                    s.members.map((m) => {
                      const rk = `${s.id}:${m.kind}:${m.id}`
                      const baseRole = m.seriesRole ?? '系列成员'
                      const roleVal = roleDrafts[rk] ?? baseRole
                      return (
                        <div
                          key={`${m.kind}:${m.id}`}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '5px 8px',
                            borderRadius: 'var(--radius-sm)',
                            background: 'var(--bg-canvas)',
                            border: '1px solid var(--border-subtle)',
                            fontSize: '0.9231rem',
                            flexWrap: 'wrap'
                          }}
                        >
                          <span className={`badge badge-carrier-${m.kind}`}>
                            {formatCarrierLabel(m.kind)}
                          </span>
                          <span style={{ fontWeight: 600, flex: '1 1 200px', minWidth: 140 }}>
                            {m.title}
                          </span>
                          <span className="badge">{m.status}</span>
                          <StudioSelect
                            className="studio-select-inline"
                            value={ROLE_PRESETS.includes(roleVal) ? roleVal : '__custom__'}
                            onChange={(v) => {
                              if (v !== '__custom__') {
                                setRoleDrafts((prev) => ({ ...prev, [rk]: v }))
                                void handleUpdateRole(s.id, m, v)
                              }
                            }}
                            ariaLabel={`${m.title} 系列定位`}
                            minMenuWidth={132}
                            maxMenuWidth={190}
                            options={[
                              ...ROLE_PRESETS.map((p) => ({ value: p, label: p })),
                              ...(!ROLE_PRESETS.includes(roleVal)
                                ? [{ value: '__custom__', label: roleVal || '自定义' }]
                                : [])
                            ]}
                          />
                          <input
                            className="inline-input"
                            style={{ width: 96, height: 24, fontSize: '0.8846rem' }}
                            value={roleVal}
                            onChange={(e) =>
                              setRoleDrafts((prev) => ({ ...prev, [rk]: e.target.value }))
                            }
                            onBlur={() => {
                              if (roleVal.trim() !== baseRole.trim()) {
                                void handleUpdateRole(s.id, m, roleVal)
                              }
                            }}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' && roleVal.trim() !== baseRole.trim()) {
                                void handleUpdateRole(s.id, m, roleVal)
                              }
                            }}
                            placeholder="自定义定位"
                            title="作品与该系列的关系（如 本篇 / 续作 / 剧场版 / 原作）"
                          />
                          <button
                            className="btn"
                            style={{ padding: '2px 8px', fontSize: '0.8462rem', color: 'var(--danger)' }}
                            disabled={busy}
                            onClick={() => void handleRemoveMember(s.id, m)}
                          >
                            移出
                          </button>
                        </div>
                      )
                    })
                  )}

                  {/* 向当前系列快速添加作品 */}
                  <div
                    style={{
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 6,
                      marginTop: 4,
                      padding: '8px 10px',
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-panel)',
                      border: '1px dashed var(--border-subtle)'
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span style={{ fontSize: '0.8846rem', fontWeight: 600 }}>+ 添加作品到此系列：</span>
                      <input
                        className="inline-input"
                        style={{ flex: '1 1 200px', height: 26, fontSize: '0.9231rem' }}
                        value={addSearchBySeries[s.id] ?? ''}
                        onChange={(e) =>
                          setAddSearchBySeries((prev) => ({ ...prev, [s.id]: e.target.value }))
                        }
                        placeholder="输入藏品标题关键字搜索..."
                      />
                      <StudioSelect
                        className="studio-select-inline"
                        value={addRoleBySeries[s.id] ?? '系列成员'}
                        onChange={(val) =>
                          setAddRoleBySeries((prev) => ({ ...prev, [s.id]: val }))
                        }
                        ariaLabel="新增成员的系列定位"
                        minMenuWidth={146}
                        maxMenuWidth={200}
                        options={ROLE_PRESETS.map((p) => ({
                          value: p,
                          label: `定位：${p}`
                        }))}
                      />
                    </div>
                    {candidateWorks.length > 0 && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                        {candidateWorks.map((w) => (
                          <div
                            key={`${w.kind}:${w.id}`}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 8,
                              fontSize: '0.9231rem',
                              padding: '4px 6px',
                              borderRadius: 'var(--radius-sm)',
                              background: 'var(--bg-surface)'
                            }}
                          >
                            <span className={`badge badge-carrier-${w.kind}`}>
                              {formatCarrierLabel(w.kind)}
                            </span>
                            <span style={{ flex: 1, fontWeight: 500 }}>{w.title}</span>
                            {w.seriesName && (
                              <span style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                                原属：{w.seriesName}
                              </span>
                            )}
                            <button
                              className="btn btn-primary"
                              style={{ padding: '2px 8px', fontSize: '0.8462rem' }}
                              disabled={busy}
                              onClick={() => void handleAddMember(s, w)}
                            >
                              加入
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )
        })
      )}

      {visibleLimit < filteredSeries.length && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 10,
            padding: '8px 0'
          }}
        >
          <span style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
            已显示 {renderedSeries.length} / {filteredSeries.length} 个系列
          </span>
          <button
            className="btn"
            onClick={() => setVisibleLimit((v) => v + SERIES_PAGE_SIZE)}
          >
            加载更多 (+{Math.min(SERIES_PAGE_SIZE, filteredSeries.length - visibleLimit)})
          </button>
          <button
            className="btn"
            onClick={() => setVisibleLimit(filteredSeries.length)}
          >
            显示全部
          </button>
        </div>
      )}
    </div>
  )
}
