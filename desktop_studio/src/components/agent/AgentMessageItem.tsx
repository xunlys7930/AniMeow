import React, { useState } from 'react'
import { studioApi } from '../../api'
import { formatAgentFullTimestamp, formatAgentMessageTime } from '../../utils/agentTime'
import type {
  AgentDiagnosticError,
  AgentMemoryChangeEvent,
  AgentMessage,
  AgentProposal,
  AgentSettingsUndoRecord,
  AgentToolTraceItem
} from '../../types'
import { IconBolt, IconBrain, IconSliders, IconWarning } from './AgentIcons'
import { AgentProposalCard } from './AgentProposalCard'

function LibraryResultCard({ resultId, count, total, onOpenWork, onToast }: {
  resultId: string
  count: number
  total: number
  onOpenWork?: (kind: string, id: number) => void
  onToast?: (message: string) => void
}) {
  const [page, setPage] = useState<Awaited<ReturnType<typeof studioApi.agentLibraryResult>> | null>(null)
  const [offset, setOffset] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const load = async (start: number) => {
    setBusy(true)
    setError('')
    try {
      setPage(await studioApi.agentLibraryResult(resultId, start))
      setOffset(start)
    } catch (err) {
      setError(String(err))
    } finally {
      setBusy(false)
    }
  }
  const exportCsv = async () => {
    setBusy(true)
    setError('')
    try {
      const path = await studioApi.pickSaveCsv('追番喵筛选清单.csv')
      if (path) onToast?.(`已导出 ${await studioApi.agentExportLibraryResult(resultId, path)} 条`)
    } catch (err) {
      setError(String(err))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="agent-draft-box agent-library-result" aria-label="藏品清单">
      <div className="agent-draft-head">
        <strong>藏品清单 · {count} 条{count < total ? `（匹配 ${total} 条）` : ''}</strong>
        <button type="button" className="btn-ghost agent-btn-sm" disabled={busy} onClick={() => page ? setPage(null) : void load(0)}>
          {page ? '收起' : '查看清单'}
        </button>
        <button type="button" className="btn-ghost agent-btn-sm" disabled={busy} onClick={exportCsv}>导出 CSV</button>
      </div>
      {error && <p role="alert">{error}</p>}
      {page?.total === 0 && <p>没有符合条件的作品。</p>}
      {page && page.total > 0 && <>
        <ol start={offset + 1}>
          {page.items.map(item => (
            <li key={`${item.kind}:${item.id}`} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
              <span>{item.title} · {item.status} · {item.progress}</span>
              {onOpenWork && (
                <button
                  type="button"
                  className="agent-mini-btn"
                  style={{ flexShrink: 0 }}
                  onClick={() => onOpenWork(item.kind, item.id)}
                >
                  打开作品
                </button>
              )}
            </li>
          ))}
        </ol>
        <button type="button" className="btn-ghost agent-btn-sm" disabled={busy || offset === 0} onClick={() => load(offset - 30)}>上一页</button>
        <span>{offset + 1}–{offset + page.items.length} / {page.total}</span>
        <button type="button" className="btn-ghost agent-btn-sm" disabled={busy || offset + 30 >= page.total} onClick={() => load(offset + 30)}>下一页</button>
      </>}
    </section>
  )
}

interface AgentMessageItemProps {
  message: AgentMessage
  proposal?: AgentProposal | null
  proposals?: AgentProposal[]
  isLastAssistant?: boolean
  busy?: boolean
  onProposalUpdated: (proposal: AgentProposal, changedDomains?: string[]) => void
  onUndoSettings: (undo: AgentSettingsUndoRecord) => void
  onDeleteMemory: (id: string) => void
  onOpenMemoryTab: () => void
  onOpenWork?: (kind: string, id: number) => void
  onQuickFix: (error: AgentDiagnosticError) => void
  onRegenerate?: (message: AgentMessage) => void
  onEditResend?: (message: AgentMessage) => void
  onForkFrom?: (message: AgentMessage) => void
  onSwitchVersion?: (message: AgentMessage, versionIdx: number) => void
  onToast?: (msg: string) => void
}

const MEMORY_CATEGORY_LABELS: Record<string, string> = {
  preference: '口味偏好',
  rating_rule: '评分标准',
  spoiler_rule: '防剧透约定',
  style: '交流习惯',
  avoid: '雷区避坑',
  profile: '个人背景'
}

function isSafeHttpUrl(url: string): boolean {
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:'
  } catch {
    return false
  }
}

function openExternalSafe(url: string) {
  if (!isSafeHttpUrl(url)) return
  void studioApi.openHttpsUrl(url).catch(() => {
    window.open(url, '_blank', 'noopener,noreferrer')
  })
}

function renderInlineMarkdown(text: string): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  const regex = /(`[^`]+`|\[[^\]]+\]\(https?:\/\/[^)\s]+\)|\*\*[^*]+\*\*|\*[^*]+\*|https?:\/\/[^\s<>"'`）)】」』]+)/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  let key = 0
  while ((match = regex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(text.slice(lastIndex, match.index))
    }
    const token = match[0]
    if (token.startsWith('`')) {
      parts.push(
        <code key={key++} className="agent-inline-code">
          {token.slice(1, -1)}
        </code>
      )
    } else if (token.startsWith('[')) {
      const m = token.match(/^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/)
      if (m && isSafeHttpUrl(m[2])) {
        const href = m[2]
        parts.push(
          <a
            key={key++}
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            style={{ color: 'var(--accent)', textDecoration: 'underline', cursor: 'pointer' }}
            onClick={(e) => {
              e.preventDefault()
              openExternalSafe(href)
            }}
          >
            {m[1]}
          </a>
        )
      } else {
        parts.push(token)
      }
    } else if (token.startsWith('http://') || token.startsWith('https://')) {
      const cleaned = token.replace(/[.,;:!?，。；：！？]+$/, '')
      const suffix = token.slice(cleaned.length)
      if (isSafeHttpUrl(cleaned)) {
        parts.push(
          <a
            key={key++}
            href={cleaned}
            target="_blank"
            rel="noopener noreferrer"
            style={{ color: 'var(--accent)', textDecoration: 'underline', cursor: 'pointer' }}
            onClick={(e) => {
              e.preventDefault()
              openExternalSafe(cleaned)
            }}
          >
            {cleaned}
          </a>
        )
        if (suffix) parts.push(suffix)
      } else {
        parts.push(token)
      }
    } else if (token.startsWith('**')) {
      parts.push(<strong key={key++}>{token.slice(2, -2)}</strong>)
    } else if (token.startsWith('*')) {
      parts.push(<em key={key++}>{token.slice(1, -1)}</em>)
    }
    lastIndex = regex.lastIndex
  }
  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex))
  }
  return parts
}

function splitTableRow(line: string): string[] {
  let trimmed = line.trim()
  if (trimmed.startsWith('|')) trimmed = trimmed.slice(1)
  if (trimmed.endsWith('|') && !trimmed.endsWith('\\|')) trimmed = trimmed.slice(0, -1)
  const cells: string[] = []
  let cur = ''
  for (let i = 0; i < trimmed.length; i++) {
    if (trimmed[i] === '\\' && trimmed[i + 1] === '|') {
      cur += '|'
      i++
    } else if (trimmed[i] === '|') {
      cells.push(cur.trim())
      cur = ''
    } else {
      cur += trimmed[i]
    }
  }
  cells.push(cur.trim())
  return cells
}

function isTableSeparatorLine(line: string): boolean {
  const t = line.trim()
  if (!t.includes('-') || !t.includes('|')) return false
  const cells = splitTableRow(t)
  if (cells.length === 0) return false
  return cells.every((c) => /^:?-{2,}:?$/.test(c.trim()))
}

function parseTableAlignments(sepLine: string): Array<'left' | 'center' | 'right'> {
  return splitTableRow(sepLine).map((c) => {
    const s = c.trim()
    if (s.startsWith(':') && s.endsWith(':')) return 'center'
    if (s.endsWith(':')) return 'right'
    return 'left'
  })
}

export const SimpleMarkdown: React.FC<{ text: string }> = ({ text }) => {
  const blocks = text.split(/```/)
  return (
    <div className="agent-md">
      {blocks.map((block, bIdx) => {
        if (bIdx % 2 === 1) {
          const firstNl = block.indexOf('\n')
          const code = firstNl >= 0 ? block.slice(firstNl + 1) : block
          return (
            <pre key={bIdx} className="agent-code-block">
              <code>{code.trimEnd()}</code>
            </pre>
          )
        }
        const lines = block.split('\n')
        const nodes: React.ReactNode[] = []
        let i = 0
        while (i < lines.length) {
          const line = lines[i]
          const trimmed = line.trim()
          if (!trimmed) {
            nodes.push(<div key={i} className="agent-md-spacer" />)
            i++
            continue
          }
          // GFM Markdown 表格：标题行含 | 且下一行是 |---|---| 分隔行
          if (trimmed.includes('|') && i + 1 < lines.length && isTableSeparatorLine(lines[i + 1])) {
            const headers = splitTableRow(lines[i])
            const aligns = parseTableAlignments(lines[i + 1])
            const bodyRows: string[][] = []
            let j = i + 2
            while (j < lines.length) {
              const rowTrim = lines[j].trim()
              if (!rowTrim || !rowTrim.includes('|')) break
              bodyRows.push(splitTableRow(lines[j]))
              j++
            }
            nodes.push(
              <div key={i} className="agent-md-table-wrap">
                <table className="agent-md-table">
                  <thead>
                    <tr>
                      {headers.map((h, ci) => (
                        <th key={ci} style={{ textAlign: aligns[ci] ?? 'left' }}>
                          {renderInlineMarkdown(h)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {bodyRows.map((row, ri) => (
                      <tr key={ri}>
                        {headers.map((_, ci) => (
                          <td key={ci} style={{ textAlign: aligns[ci] ?? 'left' }}>
                            {renderInlineMarkdown(row[ci] ?? '')}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )
            i = j
            continue
          }
          if (/^([-*_])\1{2,}$/.test(trimmed)) {
            nodes.push(<hr key={i} className="agent-md-hr" />)
            i++
            continue
          }
          if (trimmed.startsWith('### ')) {
            nodes.push(<h4 key={i}>{renderInlineMarkdown(trimmed.slice(4))}</h4>)
          } else if (trimmed.startsWith('## ')) {
            nodes.push(<h3 key={i}>{renderInlineMarkdown(trimmed.slice(3))}</h3>)
          } else if (trimmed.startsWith('# ')) {
            nodes.push(<h3 key={i}>{renderInlineMarkdown(trimmed.slice(2))}</h3>)
          } else if (trimmed.startsWith('> ')) {
            nodes.push(<blockquote key={i}>{renderInlineMarkdown(trimmed.slice(2))}</blockquote>)
          } else if (/^[-*•]\s+/.test(trimmed)) {
            nodes.push(
              <div key={i} className="agent-md-li">
                <span className="agent-md-bullet">•</span>
                <span>{renderInlineMarkdown(trimmed.replace(/^[-*•]\s+/, ''))}</span>
              </div>
            )
          } else {
            const numMatch = trimmed.match(/^(\d+\.)\s+(.*)$/)
            if (numMatch) {
              nodes.push(
                <div key={i} className="agent-md-li">
                  <span className="agent-md-num">{numMatch[1]}</span>
                  <span>{renderInlineMarkdown(numMatch[2])}</span>
                </div>
              )
            } else {
              nodes.push(<p key={i}>{renderInlineMarkdown(line)}</p>)
            }
          }
          i++
        }
        return <React.Fragment key={bIdx}>{nodes}</React.Fragment>
      })}
    </div>
  )
}

export const AgentMessageItem: React.FC<AgentMessageItemProps> = ({
  message,
  proposal,
  proposals,
  isLastAssistant,
  busy,
  onProposalUpdated,
  onUndoSettings,
  onDeleteMemory,
  onOpenMemoryTab,
  onOpenWork,
  onQuickFix,
  onRegenerate,
  onEditResend,
  onForkFrom,
  onSwitchVersion,
  onToast
}) => {
  const [showReasoning, setShowReasoning] = useState(false)
  const [showTraces, setShowTraces] = useState(false)
  const [showSources, setShowSources] = useState(false)
  const [showTechErr, setShowTechErr] = useState(false)
  const [settingsReverted, setSettingsReverted] = useState(false)

  if (message.role === 'tool') return null

  const isUser = message.role === 'user'
  const activeVersion =
    !isUser && message.versions && message.versions.length > 0
      ? message.versions[message.activeVersionIdx ?? 0] ?? null
      : null
  const displayCreatedAt = activeVersion?.createdAt || message.createdAt
  const timeLabel = formatAgentMessageTime(displayCreatedAt)
  const fullTimeLabel = formatAgentFullTimestamp(displayCreatedAt)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(message.content)
      onToast?.('已复制到剪贴板')
    } catch {
      onToast?.('复制失败')
    }
  }

  return (
    <div className={`agent-msg ${isUser ? 'is-user' : 'is-assistant'}`}>
      <div className="agent-msg-bubble">
        {!isUser && message.reasoningContent && (
          <div className="agent-collapse-box">
            <button
              type="button"
              className="agent-collapse-trigger"
              onClick={() => setShowReasoning((v) => !v)}
            >
              <span>{showReasoning ? '▾' : '▸'} 深度思考过程</span>
              <span className="agent-muted">{message.reasoningContent.length} 字</span>
            </button>
            {showReasoning && (
              <div className="agent-reasoning-body">{message.reasoningContent}</div>
            )}
          </div>
        )}

        {!isUser && message.toolTraces.length > 0 && (
          <div className="agent-collapse-box">
            <button
              type="button"
              className="agent-collapse-trigger"
              onClick={() => setShowTraces((v) => !v)}
            >
              <span>
                {showTraces ? '▾' : '▸'} 执行轨迹（{message.toolTraces.length} 步工具）
              </span>
              <span className="agent-muted">
                {message.toolTraces.reduce((s, t) => s + t.durationMs, 0)} ms
              </span>
            </button>
            {showTraces && (
              <div className="agent-trace-list">
                {message.toolTraces.map((tr: AgentToolTraceItem) => (
                  <div key={tr.id} className={`agent-trace-row ${tr.ok ? 'is-ok' : 'is-fail'}`}>
                    <span className="agent-trace-dot">{tr.ok ? '✓' : '!'}</span>
                    <div className="agent-trace-info">
                      <div className="agent-trace-top">
                        <strong>{tr.label}</strong>
                        <span className="agent-trace-meta">
                          {tr.summary} · {tr.durationMs}ms
                          {tr.truncated ? ' · 已截断' : ''}
                        </span>
                      </div>
                      {tr.argsPreview && tr.argsPreview !== '{}' && (
                        <div className="agent-trace-args">{tr.argsPreview}</div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {!isUser && message.uiActions.length > 0 && (
          <div className="agent-ui-action-strip">
            {message.uiActions.map((act, idx) => act.action === 'library_result' ? (
              <LibraryResultCard
                key={String(act.payload.resultId)}
                resultId={String(act.payload.resultId)}
                count={Number(act.payload.count)}
                total={Number(act.payload.total)}
                onOpenWork={onOpenWork}
                onToast={onToast}
              />
            ) : (
              <span key={idx} className="agent-ui-pill">
                <IconBolt size={12} /> {act.label}
              </span>
            ))}
          </div>
        )}

        {message.content && <SimpleMarkdown text={message.content} />}

        {!isUser && message.stopped && (
          <div
            style={{
              marginTop: 6,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '2px 8px',
              borderRadius: 999,
              fontSize: '0.8462rem',
              background: 'color-mix(in srgb, var(--text-muted) 14%, transparent)',
              color: 'var(--text-secondary)'
            }}
          >
            回答已手动停止
          </div>
        )}

        {!isUser && message.webSources && message.webSources.length > 0 && (
          <div className="agent-collapse-box" style={{ marginTop: 8 }}>
            <button
              type="button"
              className="agent-collapse-trigger"
              onClick={() => setShowSources((v) => !v)}
            >
              <span>
                {showSources ? '▾' : '▸'} 参考来源（{message.webSources.length} 条）
              </span>
              <span className="agent-muted">联网抓取核验</span>
            </button>
            {showSources && (
              <div className="agent-trace-list" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {message.webSources.map((src, sIdx) => (
                  <div key={`${src.url}-${sIdx}`} style={{ fontSize: '0.8846rem', lineHeight: 1.45 }}>
                    <a
                      href={src.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ color: 'var(--accent)', textDecoration: 'underline', fontWeight: 600 }}
                      onClick={(e) => {
                        e.preventDefault()
                        openExternalSafe(src.url)
                      }}
                    >
                      {src.title || src.url}
                    </a>
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.8077rem' }}>
                      {src.site ? `${src.site} · ` : ''}
                      {src.publishedDate ? `发布日期：${src.publishedDate}` : '发布日期未知'}
                      {src.fetchedAt ? ` · 抓取于 ${src.fetchedAt.slice(0, 16).replace('T', ' ')}` : ''}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {!isUser && message.settingsUndo && (
          <div className="agent-settings-undo-bar">
            <div className="agent-settings-undo-text">
              <strong>
                <IconSliders size={13} /> 已应用桌面设置
              </strong>
              <span>{message.settingsUndo.summary}</span>
            </div>
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              disabled={settingsReverted}
              onClick={() => {
                onUndoSettings(message.settingsUndo!)
                setSettingsReverted(true)
              }}
            >
              {settingsReverted ? '已恢复原设置' : '↶ 撤销设置'}
            </button>
          </div>
        )}

        {!isUser && message.memoryEvents && message.memoryEvents.length > 0 && (
          <div className="agent-memory-event-list">
            {message.memoryEvents.map((ev: AgentMemoryChangeEvent, idx: number) => (
              <div key={`${ev.id}-${idx}`} className="agent-memory-event-card">
                <div className="agent-memory-event-body">
                  <span className="agent-memory-badge">
                    <IconBrain size={12} /> {ev.action === 'deleted' ? '已移除记忆' : '已记住长期偏好'} ·{' '}
                    {MEMORY_CATEGORY_LABELS[ev.category] ?? ev.category}
                  </span>
                  <div className="agent-memory-text">{ev.content}</div>
                </div>
                <div className="agent-memory-event-actions">
                  <button type="button" className="agent-link-btn" onClick={onOpenMemoryTab}>
                    管理记忆
                  </button>
                  {ev.action !== 'deleted' && (
                    <button
                      type="button"
                      className="agent-link-btn is-danger"
                      onClick={() => onDeleteMemory(ev.id)}
                    >
                      撤销
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}

        {!isUser &&
          (() => {
            const list =
              proposals && proposals.length > 0
                ? proposals
                : proposal
                  ? [proposal]
                  : []
            return list.map((p, idx) => (
              <AgentProposalCard
                key={p.id}
                proposal={p}
                cardIndex={list.length > 1 ? idx + 1 : undefined}
                cardTotal={list.length > 1 ? list.length : undefined}
                onUpdated={onProposalUpdated}
                onToast={onToast}
              />
            ))
          })()}

        {!isUser && message.diagnosticError && (
          <div className="agent-diag-card">
            <div className="agent-diag-title">
              <IconWarning size={14} /> {message.diagnosticError.title}
            </div>
            <div className="agent-diag-desc">{message.diagnosticError.explanation}</div>
            <div className="agent-diag-tip">建议：{message.diagnosticError.suggestion}</div>
            <div className="agent-diag-actions">
              {message.diagnosticError.quickFix && (
                <button
                  type="button"
                  className="btn-primary agent-btn-sm"
                  onClick={() => onQuickFix(message.diagnosticError!)}
                >
                  {message.diagnosticError.quickFix === 'open_config'
                    ? '打开模型接入配置'
                    : message.diagnosticError.quickFix === 'apply_suggested_url'
                      ? `补全地址为 ${message.diagnosticError.suggestedBaseUrl}`
                      : message.diagnosticError.quickFix === 'disable_tools'
                        ? '一键切为纯对话模式'
                        : '重试'}
                </button>
              )}
              <button
                type="button"
                className="agent-link-btn"
                onClick={() => setShowTechErr((v) => !v)}
              >
                {showTechErr ? '收起技术详情' : '查看脱敏技术详情'}
              </button>
            </div>
            {showTechErr && (
              <pre className="agent-tech-detail">{message.diagnosticError.technicalDetail}</pre>
            )}
          </div>
        )}
      </div>

      <div className="agent-msg-footer">
        {!isUser && timeLabel && (
          <time
            className="agent-msg-time"
            dateTime={displayCreatedAt}
            title={fullTimeLabel ? `生成时间：${fullTimeLabel}` : undefined}
          >
            {timeLabel}
          </time>
        )}
        {!isUser && message.versions && message.versions.length > 1 && onSwitchVersion && (
          <div className="agent-version-switcher" title="无损微版本切换">
            <button
              type="button"
              className="agent-mini-btn"
              disabled={busy || (message.activeVersionIdx ?? 0) <= 0}
              onClick={() => onSwitchVersion(message, (message.activeVersionIdx ?? 0) - 1)}
            >
              ◀
            </button>
            <span>
              {(message.activeVersionIdx ?? 0) + 1}/{message.versions.length}
            </span>
            <button
              type="button"
              className="agent-mini-btn"
              disabled={busy || (message.activeVersionIdx ?? 0) >= message.versions.length - 1}
              onClick={() => onSwitchVersion(message, (message.activeVersionIdx ?? 0) + 1)}
            >
              ▶
            </button>
          </div>
        )}
        {!isUser && message.tokenUsage && (
          <span
            className="agent-usage-pill"
            title={
              message.tokenUsage.usageSource === 'reported'
                ? '本轮模型实际返回的输入/输出 Token 用量与缓存命中率'
                : message.tokenUsage.usageSource === 'mixed'
                  ? '本轮多步调用中部分由模型返回、部分为本机估算'
                  : '本轮为本机按请求内容估算的 Token 用量'
            }
          >
            {message.tokenUsage.estimated || message.tokenUsage.usageSource === 'estimated'
              ? '~'
              : ''}
            {message.tokenUsage.promptTokens}↑ / {message.tokenUsage.completionTokens}↓ tok
            {message.tokenUsage.requestCount && message.tokenUsage.requestCount > 1
              ? ` · ${message.tokenUsage.requestCount} 步`
              : ''}
            {message.tokenUsage.cachedTokens &&
            message.tokenUsage.cachedTokens > 0 &&
            message.tokenUsage.promptTokens > 0 ? (
              <span className="agent-cache-badge">
                {' '}
                · <IconBolt size={11} />{' '}
                {Math.min(
                  100,
                  Math.round(
                    (message.tokenUsage.cachedTokens / message.tokenUsage.promptTokens) * 100
                  )
                )}
                % 缓存命中
              </span>
            ) : null}
          </span>
        )}
        <div className="agent-msg-actions">
          {message.content && (
            <button type="button" className="agent-mini-btn" onClick={handleCopy}>
              复制
            </button>
          )}
          {!busy && onForkFrom && (
            <button
              type="button"
              className="agent-mini-btn"
              title="会话分叉：从此处复制上文开启一条新的平行对话，原对话保留不变"
              onClick={() => onForkFrom(message)}
            >
              分叉
            </button>
          )}
          {isUser && !busy && onEditResend && (
            <button
              type="button"
              className="agent-mini-btn"
              onClick={() => onEditResend(message)}
            >
              修改重问
            </button>
          )}
          {!isUser && isLastAssistant && !busy && onRegenerate && (
            <button
              type="button"
              className="agent-mini-btn"
              title="无损重新生成：保留当前回复并追加为新版本，可随时左右切换对比"
              onClick={() => onRegenerate(message)}
            >
              重新生成
            </button>
          )}
        </div>
        {isUser && timeLabel && (
          <time
            className="agent-msg-time"
            dateTime={displayCreatedAt}
            title={fullTimeLabel ? `发送时间：${fullTimeLabel}` : undefined}
          >
            {timeLabel}
          </time>
        )}
      </div>
    </div>
  )
}
