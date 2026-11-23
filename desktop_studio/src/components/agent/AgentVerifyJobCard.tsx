import React, { useMemo, useState } from 'react'
import { studioApi } from '../../api'
import type {
  AgentProposal,
  AgentVerifyJobDetail,
  AgentVerifyJobItem,
  AgentVerifyJobSummary
} from '../../types'

interface AgentVerifyJobCardProps {
  sessionId: string
  summary: AgentVerifyJobSummary
  onChanged: () => Promise<void> | void
  onProposalCreated?: (proposal: AgentProposal) => void
  onToast?: (msg: string) => void
}

function formatMetaStr(val: unknown, fallback = '缺失'): string {
  if (val == null) return fallback
  const s = String(val).trim()
  return s ? s : fallback
}

function numVal(val: unknown): number {
  if (typeof val === 'number' && Number.isFinite(val)) return val
  if (typeof val === 'string' && val.trim()) {
    const n = Number(val)
    if (Number.isFinite(n)) return n
  }
  return 0
}

type VerifyDecisionInput = 'select' | 'reject_all' | 'defer' | 'clear_rejection'

/** 规范化用户决策值，同时兼容旧数据可能存下的别名 */
function normalizeDecision(
  raw: string | null | undefined
): 'select' | 'reject_all' | 'defer' | null {
  if (raw === 'select' || raw === 'selected') return 'select'
  if (raw === 'reject_all' || raw === 'rejected') return 'reject_all'
  if (raw === 'defer' || raw === 'deferred') return 'defer'
  return null
}

/** 单一前端判定源：匹配后端 match_type（exact_match / exact）与 status */
function isExactMatchItem(item: AgentVerifyJobItem): boolean {
  return item.matchType === 'exact_match' || item.matchType === 'exact' || item.status === 'exact'
}

function isProbableMatchItem(item: AgentVerifyJobItem): boolean {
  return (
    item.matchType === 'probable_match' ||
    item.matchType === 'probable' ||
    item.status === 'probable'
  )
}

function isReadyToBindItem(item: AgentVerifyJobItem): boolean {
  if (!item.chosenExternalId?.trim()) return false
  const dec = normalizeDecision(item.userDecision)
  if (dec === 'select') return true
  if (dec === 'reject_all' || dec === 'defer') return false
  return isExactMatchItem(item)
}

export const AgentVerifyJobCard: React.FC<AgentVerifyJobCardProps> = ({
  sessionId,
  summary,
  onChanged,
  onProposalCreated,
  onToast
}) => {
  const [detail, setDetail] = useState<AgentVerifyJobDetail | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [filterMode, setFilterMode] = useState<'review' | 'all' | 'failed'>('review')

  const job = detail?.job ?? summary
  const remaining = Math.max(0, job.totalCount - job.processedCount)
  const pct = job.totalCount > 0 ? Math.min(100, Math.round((job.processedCount / job.totalCount) * 100)) : 0
  const reviewableCount = job.probableCount + job.noMatchCount

  const loadDetail = async () => {
    const res = await studioApi.agentGetVerifyJob(job.id)
    if (res) setDetail(res)
  }

  const handleToggleExpand = async () => {
    const next = !expanded
    setExpanded(next)
    if (next) {
      try {
        await loadDetail()
      } catch (e) {
        onToast?.(e instanceof Error ? e.message : String(e))
      }
    }
  }

  const handleRunStep = async (retryFailed: boolean) => {
    setBusyAction(retryFailed ? 'retry' : 'next')
    try {
      await studioApi.agentRunVerifyJobStep(job.id, 20, retryFailed)
      await loadDetail()
      await onChanged()
      onToast?.(retryFailed ? '已完成本批失败项重试' : '已完成本批核实')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyAction(null)
    }
  }

  const handleResolve = async (
    item: AgentVerifyJobItem,
    decision: VerifyDecisionInput,
    externalId?: string | null,
    originalTitle?: string | null
  ) => {
    const key = `${item.kind}:${item.workId}`
    setBusyAction(key)
    try {
      const updated = await studioApi.agentResolveVerifyItem({
        jobId: job.id,
        kind: item.kind,
        workId: item.workId,
        decision,
        externalId: externalId ?? null,
        originalTitle: originalTitle ?? null
      })
      setDetail(updated)
      await onChanged()
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyAction(null)
    }
  }

  const handleCreateProposal = async () => {
    setBusyAction('propose')
    try {
      const res = await studioApi.agentCreateProposalFromVerifyJob(sessionId, job.id)
      if (res.proposal) {
        onProposalCreated?.(res.proposal)
        const extraSkip =
          res.conflicts.length > 0 ? `（另有 ${res.conflicts.length} 条已存在绑定或冲突已跳过）` : ''
        onToast?.(`已生成 ${res.validCount} 条外部 ID 绑定提案${extraSkip}，请在下方卡片核对确认`)
      } else {
        onToast?.(`当前有 ${res.skipped.length} 条已跳过、${res.conflicts.length} 条冲突，无新增待绑定项`)
      }
      await onChanged()
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyAction(null)
    }
  }

  const visibleItems = useMemo(() => {
    const all = detail?.items ?? []
    if (filterMode === 'all') return all
    if (filterMode === 'failed') return all.filter((i) => i.status === 'failed')
    return all.filter((i) => {
      const dec = normalizeDecision(i.userDecision)
      return (
        isProbableMatchItem(i) ||
        i.matchType === 'no_match' ||
        i.status === 'no_match' ||
        i.status === 'failed' ||
        dec !== null
      )
    })
  }, [detail, filterMode])

  const readyToBindCount = useMemo(() => {
    if (!detail) return job.exactCount
    return detail.items.filter(isReadyToBindItem).length
  }, [detail, job.exactCount])

  return (
    <div
      className="agent-proposal-card"
      style={{
        margin: '8px 12px',
        padding: 10,
        border: '1px solid var(--border-strong)',
        borderRadius: 'var(--radius-md)',
        background: 'var(--bg-panel)'
      }}
    >
      <div className="agent-proposal-head" style={{ marginBottom: 6 }}>
        <div className="agent-proposal-title-wrap">
          <span className="agent-proposal-badge is-pending">分批核实</span>
          <strong>
            已核实 {job.processedCount} / {job.totalCount} 部（{pct}%）
          </strong>
        </div>
        <span className="agent-proposal-count" style={{ fontSize: '0.8462rem' }}>
          任务 {job.id.slice(-8)}
        </span>
      </div>

      <div
        style={{
          height: 6,
          borderRadius: 999,
          background: 'var(--bg-canvas)',
          overflow: 'hidden',
          marginBottom: 8
        }}
      >
        <div
          style={{
            width: `${pct}%`,
            height: '100%',
            background: 'var(--accent)',
            transition: 'width 180ms ease'
          }}
        />
      </div>

      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 10,
          fontSize: '0.8846rem',
          color: 'var(--text-secondary)',
          marginBottom: 8
        }}
      >
        <span>
          精确命中 <strong style={{ color: 'var(--positive)' }}>{job.exactCount}</strong>
        </span>
        <span>
          待人工对比 <strong>{reviewableCount}</strong>
        </span>
        {job.failedCount > 0 && (
          <span>
            请求失败 <strong style={{ color: 'var(--danger)' }}>{job.failedCount}</strong>
          </span>
        )}
        {remaining > 0 && <span>待推进 {remaining}</span>}
      </div>

      <div className="agent-proposal-actions" style={{ flexWrap: 'wrap', gap: 6 }}>
        {remaining > 0 && (
          <button
            type="button"
            className="btn-primary agent-btn-sm"
            disabled={busyAction !== null}
            onClick={() => void handleRunStep(false)}
          >
            {busyAction === 'next' ? '核实中…' : `继续余下（每批 20，剩 ${remaining}）`}
          </button>
        )}
        {job.failedCount > 0 && (
          <button
            type="button"
            className="btn-ghost agent-btn-sm"
            disabled={busyAction !== null}
            onClick={() => void handleRunStep(true)}
          >
            {busyAction === 'retry' ? '重试中…' : `仅重试失败项 (${job.failedCount})`}
          </button>
        )}
        <button
          type="button"
          className="btn-ghost agent-btn-sm"
          disabled={busyAction !== null}
          onClick={() => void handleToggleExpand()}
        >
          {expanded ? '收起对比面板' : `查看候选对比 (${reviewableCount + job.failedCount})`}
        </button>
        {readyToBindCount > 0 && (
          <button
            type="button"
            className="btn-primary agent-btn-sm"
            disabled={busyAction !== null}
            onClick={() => void handleCreateProposal()}
          >
            {busyAction === 'propose'
              ? '生成中…'
              : `生成绑定方案 (${readyToBindCount})`}
          </button>
        )}
      </div>

      {expanded && detail && (
        <div style={{ marginTop: 10, borderTop: '1px solid var(--border-subtle)', paddingTop: 8 }}>
          <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
            {(
              [
                ['review', `待确认/异常 (${reviewableCount + job.failedCount})`],
                ['failed', `仅失败 (${job.failedCount})`],
                ['all', `全部 (${detail.items.length})`]
              ] as const
            ).map(([k, lbl]) => (
              <button
                key={k}
                type="button"
                className={`btn-ghost agent-btn-xs ${filterMode === k ? 'is-active' : ''}`}
                style={{
                  borderColor: filterMode === k ? 'var(--accent)' : undefined,
                  color: filterMode === k ? 'var(--accent)' : undefined
                }}
                onClick={() => setFilterMode(k)}
              >
                {lbl}
              </button>
            ))}
          </div>

          {visibleItems.length === 0 ? (
            <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)', padding: '6px 2px' }}>
              当前分组下暂无条目。
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 340, overflowY: 'auto' }}>
              {visibleItems.slice(0, 40).map((item) => {
                const localDate = formatMetaStr(item.localMeta?.releaseDate)
                const localEp = numVal(item.localMeta?.totalEpisodes ?? item.localMeta?.progressTotal)
                const localFormat = formatMetaStr(item.localMeta?.mediaFormat, item.kind)
                const existingSource = formatMetaStr(item.localMeta?.existingExternalSource, '')
                const existingId = formatMetaStr(item.localMeta?.existingExternalId, '')
                const decision = normalizeDecision(item.userDecision)
                const itemBusy = busyAction === `${item.kind}:${item.workId}`
                const cands = Array.isArray(item.candidates) ? item.candidates : []
                return (
                  <div
                    key={`${item.kind}:${item.workId}`}
                    style={{
                      padding: 8,
                      borderRadius: 'var(--radius-sm)',
                      border: '1px solid var(--border-subtle)',
                      background: 'var(--bg-canvas)',
                      fontSize: '0.8846rem'
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        gap: 6,
                        marginBottom: 6
                      }}
                    >
                      <div style={{ fontWeight: 700, minWidth: 0 }}>
                        {item.title}{' '}
                        <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
                          #{item.workId}
                        </span>
                      </div>
                      <span style={{ fontSize: '0.8462rem', color: 'var(--text-secondary)', flexShrink: 0 }}>
                        {decision === 'select'
                          ? `已手选 bgm:${item.chosenExternalId}`
                          : decision === 'reject_all'
                            ? '已标记都不匹配'
                            : decision === 'defer'
                              ? '稍后处理'
                              : item.status === 'failed'
                                ? '核实失败'
                                : isExactMatchItem(item)
                                  ? `精确命中 (${Math.round(item.confidence * 100)}%)`
                                  : isProbableMatchItem(item)
                                    ? `待选 (${Math.round(item.confidence * 100)}%)`
                                    : '未命中'}
                      </span>
                    </div>

                    <div
                      style={{
                        display: 'grid',
                        gridTemplateColumns: '1fr 1.35fr',
                        gap: 8,
                        alignItems: 'start'
                      }}
                    >
                      <div
                        style={{
                          padding: 6,
                          borderRadius: 6,
                          background: 'var(--bg-panel)',
                          border: '1px solid var(--border-subtle)',
                          lineHeight: 1.5
                        }}
                      >
                        <div style={{ fontSize: '0.8077rem', color: 'var(--text-muted)', marginBottom: 2 }}>
                          本地藏品记录
                        </div>
                        <div>标题：{item.title}</div>
                        <div>原名：{item.originalTitle || '未填写'}</div>
                        <div>日期：{localDate}</div>
                        <div>集/卷数：{localEp > 0 ? localEp : '未知'}</div>
                        <div>形态：{localFormat}</div>
                        {existingId && (
                          <div style={{ color: 'var(--positive)' }}>
                            已有绑定：{existingSource || 'external'}:{existingId}
                          </div>
                        )}
                        {item.errorMessage && (
                          <div style={{ color: 'var(--danger)', marginTop: 4 }}>
                            错误：{item.errorMessage}
                          </div>
                        )}
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                        {cands.length === 0 ? (
                          <div style={{ color: 'var(--text-muted)', padding: 6 }}>
                            无可用候选条目（可能原名或关键词差异较大，或已全部排除）。
                          </div>
                        ) : (
                          cands.slice(0, 3).map((cand, idx) => {
                            const cid = formatMetaStr(cand.externalId, '')
                            const cTitle = formatMetaStr(cand.title)
                            const cOrig = formatMetaStr(cand.originalTitle, '')
                            const cDate = formatMetaStr(cand.releaseDate)
                            const cEp = numVal(cand.totalEpisodes)
                            const cConf = Math.round(numVal(cand.confidence) * 100)
                            const dateDiff =
                              localDate !== '缺失' && cDate !== '缺失' && localDate.slice(0, 7) !== cDate.slice(0, 7)
                            const epDiff = localEp > 0 && cEp > 0 && localEp !== cEp
                            const isSelected =
                              item.chosenExternalId === cid && isReadyToBindItem(item)
                            return (
                              <div
                                key={`${cid}_${idx}`}
                                style={{
                                  padding: 6,
                                  borderRadius: 6,
                                  border: `1px solid ${isSelected ? 'var(--accent)' : 'var(--border-subtle)'}`,
                                  background: 'var(--bg-panel)',
                                  lineHeight: 1.45
                                }}
                              >
                                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 6 }}>
                                  <strong style={{ fontSize: '0.8846rem' }}>{cTitle}</strong>
                                  <span style={{ color: 'var(--text-muted)', fontSize: '0.8077rem' }}>
                                    bgm:{cid} · {cConf}%
                                  </span>
                                </div>
                                {cOrig && (
                                  <div style={{ color: 'var(--text-secondary)', fontSize: '0.8462rem' }}>
                                    原名：{cOrig}
                                  </div>
                                )}
                                <div style={{ display: 'flex', gap: 10, fontSize: '0.8462rem', marginTop: 2 }}>
                                  <span style={{ color: dateDiff ? 'var(--danger)' : 'var(--text-secondary)' }}>
                                    日期：{cDate}
                                    {dateDiff ? '（不一致）' : ''}
                                  </span>
                                  <span style={{ color: epDiff ? 'var(--danger)' : 'var(--text-secondary)' }}>
                                    集数：{cEp > 0 ? cEp : '缺失'}
                                    {epDiff ? '（不一致）' : ''}
                                  </span>
                                </div>
                                <div style={{ marginTop: 4, display: 'flex', justifyContent: 'flex-end' }}>
                                  <button
                                    type="button"
                                    className={isSelected ? 'btn-primary agent-btn-xs' : 'btn-ghost agent-btn-xs'}
                                    disabled={itemBusy || !cid}
                                    onClick={() =>
                                      void handleResolve(item, 'select', cid, cOrig || null)
                                    }
                                  >
                                    {isSelected ? '已选定' : '选这个'}
                                  </button>
                                </div>
                              </div>
                            )
                          })
                        )}

                        <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                          {decision === 'reject_all' ? (
                            <button
                              type="button"
                              className="btn-ghost agent-btn-xs"
                              disabled={itemBusy}
                              onClick={() => void handleResolve(item, 'clear_rejection')}
                            >
                              撤销排除
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="btn-ghost agent-btn-xs"
                              disabled={itemBusy}
                              onClick={() => void handleResolve(item, 'reject_all')}
                            >
                              都不匹配
                            </button>
                          )}
                          <button
                            type="button"
                            className="btn-ghost agent-btn-xs"
                            disabled={itemBusy}
                            onClick={() => void handleResolve(item, 'defer')}
                          >
                            稍后处理
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
