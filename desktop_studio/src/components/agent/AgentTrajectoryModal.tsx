import React, { useEffect, useMemo, useState } from 'react'
import { studioApi } from '../../api'
import { FeedbackModal } from '../FeedbackModal'
import { StudioSelect } from '../StudioSelect'
import type { AgentRunEvent, AgentTrajectorySummary } from '../../types'
import { useDraggablePanel, useFloatingLayer } from '../../utils/floatingLayer'
import {
  IconActivity,
  IconBolt,
  IconBox,
  IconBrain,
  IconCheck,
  IconClipboard,
  IconPlay,
  IconShield,
  IconWarning,
  IconWrench
} from './AgentIcons'

interface AgentTrajectoryModalProps {
  open: boolean
  sessionId: string | null
  sessionTitle: string
  onClose: () => void
  onToast?: (msg: string) => void
}

const EVENT_TYPE_LABELS: Record<string, { label: string; icon: React.ReactNode }> = {
  run_start: { label: '开始轮次', icon: <IconPlay size={13} /> },
  llm_step: { label: 'LLM 推理', icon: <IconBrain size={14} /> },
  tool_call: { label: '工具调用', icon: <IconWrench size={14} /> },
  compaction: { label: '滚动压缩', icon: <IconBox size={14} /> },
  guard: { label: '护栏熔断', icon: <IconShield size={14} /> },
  run_end: { label: '轮次结束', icon: <IconCheck size={14} /> },
  run_error: { label: '运行异常', icon: <IconWarning size={14} /> }
}

export const AgentTrajectoryModal: React.FC<AgentTrajectoryModalProps> = ({
  open,
  sessionId,
  sessionTitle,
  onClose,
  onToast
}) => {
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const {
    offset: modalOffset,
    isMoved: modalMoved,
    resetOffset: resetModalOffset,
    dragHandlers: modalDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })
  const [data, setData] = useState<AgentTrajectorySummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [selectedRunId, setSelectedRunId] = useState<string>('all')
  const [typeFilter, setTypeFilter] = useState<string>('all')
  const [eventPage, setEventPage] = useState(0)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set())
  const [artifactView, setArtifactView] = useState<{
    id: string
    toolName: string
    totalChars: number
    content: string
    offset: number
    nextOffset: number | null
  } | null>(null)

  const refresh = async () => {
    if (!sessionId) return
    setLoading(true)
    try {
      const res = await studioApi.agentLoadTrajectory(sessionId)
      setData(res)
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!open || !sessionId) return
    setSelectedRunId('all')
    setTypeFilter('all')
    setEventPage(0)
    setArtifactView(null)
    refresh()
  }, [open, sessionId])

  const runs = useMemo(() => {
    if (!data) return []
    const map = new Map<string, { runId: string; startAt: string; label: string; count: number }>()
    for (const ev of data.events) {
      const cur = map.get(ev.runId)
      if (!cur) {
        const userMsg =
          typeof ev.payload?.userMessage === 'string' && ev.payload.userMessage.trim()
            ? ev.payload.userMessage.trim().slice(0, 22)
            : ev.title.slice(0, 22)
        map.set(ev.runId, {
          runId: ev.runId,
          startAt: ev.createdAt,
          label: userMsg,
          count: 1
        })
      } else {
        cur.count += 1
      }
    }
    return Array.from(map.values()).reverse()
  }, [data])

  const filteredEvents = useMemo(() => {
    if (!data) return []
    return data.events.filter((ev) => {
      if (selectedRunId !== 'all' && ev.runId !== selectedRunId) return false
      if (typeFilter === 'llm' && ev.eventType !== 'llm_step') return false
      if (typeFilter === 'tool' && ev.eventType !== 'tool_call') return false
      if (typeFilter === 'guard' && ev.eventType !== 'guard' && ev.eventType !== 'compaction') {
        return false
      }
      if (typeFilter === 'error' && ev.status === 'ok') return false
      return true
    })
  }, [data, selectedRunId, typeFilter])

  useEffect(() => {
    setEventPage(0)
  }, [selectedRunId, typeFilter])

  const eventsPerPage = 50
  const pageCount = Math.ceil(filteredEvents.length / eventsPerPage)
  const currentPage = Math.min(eventPage, Math.max(0, pageCount - 1))
  const pageEnd = filteredEvents.length - currentPage * eventsPerPage
  const pageStart = Math.max(0, pageEnd - eventsPerPage)

  const maxStepMs = useMemo(() => {
    let m = 1
    for (const ev of filteredEvents) {
      if (ev.durationMs > m) m = ev.durationMs
    }
    return m
  }, [filteredEvents])

  const cacheHitPct = useMemo(() => {
    if (!data || data.totalPromptTokens <= 0) return 0
    return Math.min(100, Math.round((data.totalCachedTokens / data.totalPromptTokens) * 1000) / 10)
  }, [data])

  const toggleExpand = (id: number) => {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleExport = async (format: 'jsonl' | 'markdown') => {
    if (!sessionId) return
    try {
      const saved = await studioApi.agentExportTrajectoryFile(
        sessionId,
        sessionTitle || 'animeow_agent',
        format
      )
      if (saved) {
        onToast?.(
          format === 'jsonl'
            ? '已导出 AI 运行记录原始文件 (.jsonl)'
            : '已导出运行报告'
        )
      }
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleExportFeedbackDiagnostics = async () => {
    if (!sessionId) return
    try {
      const saved = await studioApi.agentExportFeedbackDiagnostics(
        sessionId,
        sessionTitle || 'animeow_agent'
      )
      if (saved) {
        onToast?.('已导出脱敏反馈诊断报告（不含书名、漫评与对话正文）')
      }
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleCopyReport = async () => {
    if (!sessionId) return
    try {
      const md = await studioApi.agentExportTrajectoryText(sessionId, 'markdown')
      await navigator.clipboard.writeText(md)
      onToast?.('已复制报告')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleInspectArtifact = async (artifactId: string, offset = 0) => {
    try {
      const art = await studioApi.agentReadArtifact(artifactId, offset, 4500)
      setArtifactView({
        id: art.artifactId,
        toolName: art.toolName,
        totalChars: art.totalChars,
        content: art.content,
        offset: art.offset,
        nextOffset: art.nextOffset ?? null
      })
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  if (!open) return null

  return (
    <>
    <div
      className="modal-backdrop agent-modal-backdrop"
      style={{ zIndex: modalZ }}
      onPointerDownCapture={bringToFront}
      onClick={onClose}
    >
      <div
        className="agent-config-modal agent-traj-modal"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="AI 运行记录与耗时分析"
      >
        <div
          className="agent-config-header"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
        >
          <div>
            <h3>
              <IconActivity size={16} style={{ marginRight: 6 }} />
              AI 运行记录（{sessionTitle}）
            </h3>
            <p className="agent-muted">
              查看当前对话的工具调用、耗时和异常。复制或导出的报告可提供给开发者排查问题。
            </p>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            {modalMoved && (
              <button type="button" className="btn-ghost agent-btn-sm" onClick={resetModalOffset}>
                归位
              </button>
            )}
            <button type="button" className="btn-ghost agent-btn-sm" onClick={refresh}>
              ↻ 刷新
            </button>
            <button type="button" className="btn-ghost agent-btn-sm" onClick={onClose}>
              ✕ 关闭
            </button>
          </div>
        </div>

        {data && (
          <div className="agent-traj-kpis">
            <div className="agent-traj-kpi">
              <span>运行轮数 / 推理步</span>
              <strong>
                {data.totalRuns} / {data.totalLlmSteps}
              </strong>
            </div>
            <div className="agent-traj-kpi">
              <span>工具调用 / 失败</span>
              <strong>
                {data.totalToolCalls} /{' '}
                <span style={{ color: data.failedToolCalls > 0 ? 'var(--danger)' : 'inherit' }}>
                  {data.failedToolCalls}
                </span>
              </strong>
            </div>
            <div className="agent-traj-kpi">
              <span>提示词缓存命中</span>
              <strong style={{ color: cacheHitPct >= 50 ? 'var(--positive)' : 'inherit' }}>
                <IconBolt size={14} style={{ marginRight: 3 }} />
                {cacheHitPct}%
              </strong>
            </div>
            <div className="agent-traj-kpi">
              <span>累计输入 / 输出 Token</span>
              <strong>
                {Math.round(data.totalPromptTokens / 100) / 10}k /{' '}
                {Math.round(data.totalCompletionTokens / 100) / 10}k
              </strong>
            </div>
            <div className="agent-traj-kpi">
              <span>护栏与压缩触发</span>
              <strong>{data.guardTriggers}</strong>
            </div>
            <div className="agent-traj-kpi">
              <span>累计耗时</span>
              <strong>{(data.totalDurationMs / 1000).toFixed(1)}s</strong>
            </div>
          </div>
        )}

        <div className="agent-traj-toolbar">
          <div className="agent-traj-filters">
            <StudioSelect
              className="studio-select-inline"
              value={selectedRunId}
              onChange={(val) => setSelectedRunId(val)}
              options={[
                { value: 'all', label: `全部运行轮次（${runs.length}）` },
                ...runs.map((r, i) => ({
                  value: r.runId,
                  label: `#${runs.length - i} · ${r.label}`,
                  sublabel: `${r.count} 条事件`
                }))
              ]}
              ariaLabel="筛选运行轮次"
              minMenuWidth={220}
              maxMenuWidth={320}
            />

            <div className="agent-region-tabs">
              {(
                [
                  ['all', '全部'],
                  ['llm', '模型回复'],
                  ['tool', '工具调用'],
                  ['guard', '护栏/压缩'],
                  ['error', '异常/警告']
                ] as const
              ).map(([k, lbl]) => (
                <button
                  key={k}
                  type="button"
                  className={typeFilter === k ? 'is-active' : ''}
                  onClick={() => setTypeFilter(k)}
                >
                  {lbl}
                </button>
              ))}
            </div>
          </div>

          <div className="agent-traj-exports">
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              onClick={() => setFeedbackOpen(true)}
              title="复制 QQ 群号、点击链接或扫码加入追番喵用户交流群反馈问题"
            >
              进群反馈
            </button>
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              disabled={!data || data.events.length === 0}
              onClick={handleExportFeedbackDiagnostics}
              title="仅保留应用版本、耗时、错误码、工具名与 Token 统计，完全移除书名、漫评与对话正文，适合发给开发者排查问题"
            >
              导出脱敏反馈诊断
            </button>
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              disabled={!data || data.events.length === 0}
              onClick={handleCopyReport}
            >
              <IconClipboard size={13} style={{ marginRight: 4 }} />
              复制报告
            </button>
            <button
              type="button"
              className="btn-ghost agent-btn-sm"
              disabled={!data || data.events.length === 0}
              onClick={() => handleExport('markdown')}
            >
              导出报告 .md
            </button>
            <button
              type="button"
              className="btn-primary agent-btn-sm"
              disabled={!data || data.events.length === 0}
              onClick={() => handleExport('jsonl')}
            >
              导出原始记录 .jsonl
            </button>
          </div>
        </div>

        <div className="agent-config-body">
          {artifactView && (
            <div className="agent-draft-box" style={{ marginBottom: 12 }}>
              <div className="agent-draft-head">
                <strong>
                  <IconBox size={14} style={{ marginRight: 5 }} />
                  完整工具返回原文：`{artifactView.id}`（{artifactView.toolName} · 共{' '}
                  {artifactView.totalChars} 字符）
                </strong>
                <button
                  type="button"
                  className="btn-ghost agent-btn-xs"
                  onClick={() => setArtifactView(null)}
                >
                  关闭预览
                </button>
              </div>
              <pre className="agent-tech-detail" style={{ maxHeight: 240, overflowY: 'auto' }}>
                {artifactView.content}
              </pre>
              {(artifactView.offset > 0 || artifactView.nextOffset !== null) && (
                <div className="agent-proposal-actions">
                  <button type="button" className="btn-ghost agent-btn-sm" disabled={artifactView.offset === 0}
                    onClick={() => handleInspectArtifact(artifactView.id, Math.max(0, artifactView.offset - 4500))}>
                    上一页
                  </button>
                  <span>{artifactView.offset + 1}–{artifactView.nextOffset ?? artifactView.totalChars} / {artifactView.totalChars} 字符</span>
                  <button type="button" className="btn-ghost agent-btn-sm" disabled={artifactView.nextOffset === null}
                    onClick={() => handleInspectArtifact(artifactView.id, artifactView.nextOffset ?? 0)}>
                    下一页
                  </button>
                </div>
              )}
            </div>
          )}

          {loading ? (
            <div className="agent-empty-hint">正在加载执行轨迹…</div>
          ) : filteredEvents.length === 0 ? (
            <div className="agent-empty-hint">
              当前对话还没有可检视的轨迹事件。发一条消息后，AI 的每一步推理、KV 缓存命中和工具入出参都会自动记录在这里。
            </div>
          ) : (
            <div className="agent-traj-timeline">
              {pageCount > 1 && (
                <div className="agent-proposal-actions">
                  <button type="button" className="btn-ghost agent-btn-sm" disabled={currentPage === 0} onClick={() => setEventPage(currentPage - 1)}>
                    较新
                  </button>
                  <span>{pageStart + 1}–{pageEnd} / {filteredEvents.length}</span>
                  <button type="button" className="btn-ghost agent-btn-sm" disabled={currentPage + 1 >= pageCount} onClick={() => setEventPage(currentPage + 1)}>
                    较早
                  </button>
                </div>
              )}
              {filteredEvents.slice(pageStart, pageEnd).map((ev: AgentRunEvent) => {
                const meta = EVENT_TYPE_LABELS[ev.eventType] ?? {
                  label: ev.eventType,
                  icon: '•'
                }
                const expanded = expandedIds.has(ev.id)
                const barWidth = Math.max(
                  4,
                  Math.min(100, Math.round((ev.durationMs / maxStepMs) * 100))
                )
                const spilledId =
                  typeof ev.payload?.spilledArtifactId === 'string'
                    ? ev.payload.spilledArtifactId
                    : null
                return (
                  <div key={ev.id} className={`agent-traj-card is-${ev.status}`}>
                    <div
                      className="agent-traj-card-head"
                      onClick={() => toggleExpand(ev.id)}
                    >
                      <div className="agent-traj-card-left">
                        <span className="agent-traj-icon">{meta.icon}</span>
                        <span className="agent-tag-pill">
                          {ev.step > 0 ? `Step ${ev.step}` : meta.label}
                        </span>
                        <strong>{ev.title}</strong>
                      </div>
                      <div className="agent-traj-card-right">
                        {ev.eventType === 'llm_step' &&
                          typeof ev.payload?.promptTokens === 'number' && (
                            <span className="agent-usage-pill">
                              {String(ev.payload.promptTokens)}↑ /{' '}
                              {String(ev.payload.completionTokens ?? 0)}↓
                              {typeof ev.payload?.cacheHitPct === 'number' &&
                              ev.payload.cacheHitPct > 0 ? (
                                <>
                                  {' '}
                                  · <IconBolt size={11} /> {ev.payload.cacheHitPct}%
                                </>
                              ) : (
                                ''
                              )}
                            </span>
                          )}
                        <div className="agent-traj-bar-wrap" title={`耗时 ${ev.durationMs} ms`}>
                          <div className="agent-traj-bar" style={{ width: `${barWidth}%` }} />
                        </div>
                        <span className="agent-traj-ms">{ev.durationMs}ms</span>
                        <span className="agent-muted">{expanded ? '▾' : '▸'}</span>
                      </div>
                    </div>

                    {expanded && (
                      <div className="agent-traj-card-body">
                        {spilledId && (
                          <div style={{ marginBottom: 6 }}>
                            <button
                              type="button"
                              className="btn-ghost agent-btn-xs"
                              onClick={() => handleInspectArtifact(spilledId)}
                            >
                              <IconBox size={12} style={{ marginRight: 4 }} />
                              查看完整工具返回原文 ({spilledId})
                            </button>
                          </div>
                        )}
                        <pre className="agent-tech-detail">
                          {JSON.stringify(ev.payload, null, 2)}
                        </pre>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </div>

    <FeedbackModal
      open={feedbackOpen}
      onClose={() => setFeedbackOpen(false)}
      onToast={onToast}
    />
    </>
  )
}
