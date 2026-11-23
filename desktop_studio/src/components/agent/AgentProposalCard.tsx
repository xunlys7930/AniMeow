import React, { useMemo, useState } from 'react'
import { studioApi } from '../../api'
import type { AgentProposal, AgentProposalApplyResult } from '../../types'

interface AgentProposalCardProps {
  proposal: AgentProposal
  cardIndex?: number
  cardTotal?: number
  onUpdated: (proposal: AgentProposal, changedDomains?: string[]) => void
  onToast?: (msg: string) => void
}

const STATUS_META: Record<string, { label: string; cls: string }> = {
  pending: { label: '待确认', cls: 'is-pending' },
  applied: { label: '已执行', cls: 'is-applied' },
  auto_applied: { label: '已自动执行', cls: 'is-applied' },
  noop: { label: '已无需执行', cls: 'is-reverted' },
  rejected: { label: '已放弃', cls: 'is-rejected' },
  reverted: { label: '已撤销', cls: 'is-reverted' },
  unconfirmed: { label: '结果待核对', cls: 'is-pending' },
  expired: { label: '已过期', cls: 'is-rejected' }
}

const NON_REVERTIBLE_OPS = new Set([
  'delete_empty_series',
  'merge_series',
  'update_member_role',
  'remove_member',
  'merge_tags',
  'purge_unused_tags',
  'delete_tag'
])

function isProposalNonRevertible(proposal: AgentProposal): boolean {
  if (proposal.kind === 'mcp_call') return true
  const ops = proposal.ops
  if (Array.isArray(ops)) {
    return ops.some((op) => {
      if (!op || typeof op !== 'object') return false
      const rec = op as Record<string, unknown>
      const name = String(rec.action ?? rec.op ?? '')
      return NON_REVERTIBLE_OPS.has(name)
    })
  }
  return false
}

export const AgentProposalCard: React.FC<AgentProposalCardProps> = ({
  proposal,
  cardIndex,
  cardTotal,
  onUpdated,
  onToast
}) => {
  const [selected, setSelected] = useState<Set<number>>(
    () => new Set(proposal.diffs.map((d) => d.index))
  )
  const [busy, setBusy] = useState(false)
  const [expanded, setExpanded] = useState(proposal.diffs.length <= 6)
  const [page, setPage] = useState(0)
  const [showSkipped, setShowSkipped] = useState(false)
  const [errMsg, setErrMsg] = useState<string | null>(null)

  const statusMeta = STATUS_META[proposal.status] ?? STATUS_META.pending
  const isPending = proposal.status === 'pending'
  const isApplied =
    proposal.status === 'applied' ||
    proposal.status === 'auto_applied' ||
    proposal.status === 'reverted'
  const nonRevertibleWarning = useMemo(() => isProposalNonRevertible(proposal), [proposal])

  const appliedSet = useMemo(() => {
    if (!proposal.appliedIndices || proposal.appliedIndices.length === 0) return null
    return new Set(proposal.appliedIndices)
  }, [proposal.appliedIndices])

  const isPartialApplied = Boolean(
    isApplied && appliedSet && appliedSet.size > 0 && appliedSet.size < proposal.diffs.length
  )

  const orderedDiffs = useMemo(() => {
    if (!appliedSet || !isApplied) return proposal.diffs
    const executed = proposal.diffs.filter((d) => appliedSet.has(d.index))
    if (!showSkipped && isPartialApplied) return executed
    const skipped = proposal.diffs.filter((d) => !appliedSet.has(d.index))
    return [...executed, ...skipped]
  }, [appliedSet, isApplied, isPartialApplied, proposal.diffs, showSkipped])

  const visibleDiffs = useMemo(
    () => (expanded ? orderedDiffs.slice(page * 30, page * 30 + 30) : orderedDiffs.slice(0, 5)),
    [expanded, page, orderedDiffs]
  )

  const toggleIndex = (idx: number) => {
    if (!isPending || busy) return
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(idx)) next.delete(idx)
      else next.add(idx)
      return next
    })
  }

  const handleCommit = async () => {
    if (selected.size === 0) {
      setErrMsg('请至少勾选一项要执行的变更')
      return
    }
    setBusy(true)
    setErrMsg(null)
    try {
      const res: AgentProposalApplyResult = await studioApi.agentCommitProposal(
        proposal.id,
        Array.from(selected).sort((a, b) => a - b)
      )
      onUpdated(res.proposal, res.changedDomains)
      onToast?.(res.message)
    } catch (e) {
      setErrMsg(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const handleReject = async () => {
    setBusy(true)
    setErrMsg(null)
    try {
      const next = await studioApi.agentRejectProposal(proposal.id)
      onUpdated(next)
      onToast?.('已放弃该变更方案')
    } catch (e) {
      setErrMsg(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const handleRevert = async () => {
    setBusy(true)
    setErrMsg(null)
    try {
      const res = await studioApi.agentRevertProposal(proposal.id)
      onUpdated(res.proposal, res.changedDomains)
      onToast?.(res.message)
    } catch (e) {
      setErrMsg(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className={`agent-proposal-card ${statusMeta.cls}`}>
      <div className="agent-proposal-head">
        <div className="agent-proposal-title-wrap">
          {cardIndex != null && cardTotal != null && cardTotal > 1 && (
            <span className="agent-proposal-badge" style={{ opacity: 0.9 }}>
              方案 {cardIndex}/{cardTotal}
            </span>
          )}
          <span className={`agent-proposal-badge ${statusMeta.cls}`}>{statusMeta.label}</span>
          <strong>{proposal.title}</strong>
        </div>
        <span className="agent-proposal-count">
          {isPartialApplied && appliedSet
            ? `已执行 ${appliedSet.size} / 共 ${proposal.diffs.length} 项`
            : `共 ${proposal.diffs.length} 项`}
        </span>
      </div>

      {proposal.summary && <div className="agent-proposal-summary">{proposal.summary}</div>}

      {isPending && proposal.overlapWarnings && proposal.overlapWarnings.length > 0 && (
        <div
          className="agent-inline-error"
          style={{
            background: 'color-mix(in srgb, var(--accent) 9%, transparent)',
            borderColor: 'var(--accent)',
            color: 'var(--text-primary)'
          }}
        >
          {proposal.overlapWarnings.map((w, i) => (
            <div key={i}>重叠提醒：{w}</div>
          ))}
        </div>
      )}

      {isPending && nonRevertibleWarning && (
        <div
          className="agent-inline-error"
          style={{
            background: 'color-mix(in srgb, var(--accent) 10%, transparent)',
            borderColor: 'var(--accent)',
            color: 'var(--text-primary)'
          }}
        >
          提示：此方案包含不可自动逆转的操作，执行后暂不支持一键撤销，请确认无误后再执行。
        </div>
      )}

      {proposal.status === 'noop' && (
        <div className="agent-proposal-summary" style={{ color: 'var(--text-muted)' }}>
          {proposal.lastError || '所有目标字段当前真实值已达成，无需重复执行。'}
        </div>
      )}

      {isPending && proposal.lastAttemptFailed && proposal.lastError && !errMsg && (
        <div className="agent-inline-error">上次执行未通过：{proposal.lastError}</div>
      )}

      {proposal.status === 'expired' && (
        <div className="agent-proposal-summary" style={{ color: 'var(--text-muted)' }}>
          该方案因备份覆写恢复已自动失效，请按当前库状态重新核对后再操作。
        </div>
      )}

      {isPartialApplied && appliedSet && (
        <div
          className="agent-proposal-summary"
          style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}
        >
          <span>
            已执行 {appliedSet.size} 项，未勾选 {proposal.diffs.length - appliedSet.size} 项
          </span>
          <button
            type="button"
            className="agent-link-btn"
            style={{ margin: 0 }}
            onClick={() => {
              setShowSkipped((v) => !v)
              setPage(0)
            }}
          >
            {showSkipped ? '仅看已执行' : '展开全部（含未勾选）'}
          </button>
        </div>
      )}

      <div className="agent-proposal-list">
        {visibleDiffs.map((d) => {
          const checked = selected.has(d.index)
          const wasSkipped = Boolean(isPartialApplied && appliedSet && !appliedSet.has(d.index))
          return (
            <label
              key={d.index}
              className={`agent-diff-row ${(isPending && !checked) || wasSkipped ? 'is-unchecked' : ''}`}
            >
              {isPending && (
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={busy}
                  onChange={() => toggleIndex(d.index)}
                />
              )}
              <div className="agent-diff-body">
                <div className="agent-diff-target">
                  <span className="agent-diff-action">{d.actionLabel}</span>
                  <span>{d.targetLabel}</span>
                  {isPartialApplied && appliedSet && (
                    <span
                      style={{
                        marginLeft: 'auto',
                        fontSize: '0.8077rem',
                        color: wasSkipped ? 'var(--text-muted)' : 'var(--positive)'
                      }}
                    >
                      {wasSkipped ? '未勾选' : '已执行'}
                    </span>
                  )}
                </div>
                <div className="agent-diff-values">
                  <span className="agent-diff-before">{d.before}</span>
                  <span className="agent-diff-arrow">→</span>
                  <span className="agent-diff-after">{d.after}</span>
                </div>
              </div>
            </label>
          )
        })}
      </div>

      {orderedDiffs.length > 5 && (
        <button
          type="button"
          className="agent-link-btn"
          onClick={() => setExpanded((v) => !v)}
        >
          {expanded ? '收起部分条目' : `展开其余 ${orderedDiffs.length - 5} 项`}
        </button>
      )}
      {expanded && orderedDiffs.length > 30 && (
        <div className="agent-proposal-actions">
          <button type="button" className="btn-ghost agent-btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button>
          <span>{page + 1} / {Math.ceil(orderedDiffs.length / 30)} 页</span>
          <button type="button" className="btn-ghost agent-btn-sm" disabled={(page + 1) * 30 >= orderedDiffs.length} onClick={() => setPage(page + 1)}>下一页</button>
        </div>
      )}

      {errMsg && <div className="agent-inline-error">{errMsg}</div>}

      <div className="agent-proposal-actions">
        {isPending && (
          <>
            <button
              type="button"
              className="btn-primary agent-btn-sm"
              disabled={busy || selected.size === 0}
              onClick={handleCommit}
            >
              {busy ? '正在写入…' : `确认执行 (${selected.size})`}
            </button>
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              disabled={busy}
              onClick={handleReject}
            >
              放弃方案
            </button>
          </>
        )}
        {(proposal.status === 'applied' || proposal.status === 'auto_applied') && proposal.canRevert && (
          <button
            type="button"
            className="btn-ghost agent-btn-sm"
            disabled={busy}
            onClick={handleRevert}
          >
            {busy ? '正在撤销…' : '↶ 撤销本次修改'}
          </button>
        )}
      </div>
    </div>
  )
}
