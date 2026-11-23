import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { studioApi } from '../../api'
import { StudioSelect, type StudioSelectOption } from '../StudioSelect'
import { useStudioDialog } from '../StudioDialog'
import { useDraggablePanel, useFloatingLayer } from '../../utils/floatingLayer'
import { startNativeWindowDragFromEvent } from '../../utils/popout'
import {
  formatAgentDateDivider,
  formatAgentFullTimestamp,
  formatAgentSessionTime,
  getLocalDayKey
} from '../../utils/agentTime'
import type {
  AgentActiveContextChip,
  AgentDiagnosticError,
  AgentMemoryChangeEvent,
  AgentMessage,
  AgentPolicyConfig,
  AgentProfile,
  AgentProposal,
  AgentSession,
  AgentSettingsUndoRecord,
  AgentSlashCommand,
  AgentStreamEnvelope,
  AgentToolTraceItem,
  AgentUiActionCommand,
  AgentVerifyJobSummary,
  CuratorItem,
  DesktopSettings
} from '../../types'
import { AgentConfigModal, type AgentConfigTab } from './AgentConfigModal'
import {
  IconActivity,
  IconBolt,
  IconBrain,
  IconPin,
  IconShield,
  IconSliders,
  IconSpark,
  IconSummary
} from './AgentIcons'
import { AgentFloatingPill, type AgentFloatingStatus, type MorphRect } from './AgentFloatingPill'
import { AgentMessageItem } from './AgentMessageItem'
import { AgentProposalCard } from './AgentProposalCard'
import { AgentTrajectoryModal } from './AgentTrajectoryModal'
import { AgentVerifyJobCard } from './AgentVerifyJobCard'
import { useAgentAutoScroll } from './useAgentAutoScroll'

function ProviderSwitchNotice({
  profileName,
  onCreateSession
}: {
  profileName: string
  onCreateSession: () => void
}) {
  const [dismissed, setDismissed] = useState(false)

  if (dismissed) return null

  return (
    <div
      className="agent-context-bar"
      style={{
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        borderColor: 'var(--accent)',
        background: 'color-mix(in srgb, var(--accent) 8%, transparent)'
      }}
    >
      <span style={{ fontSize: '0.8846rem' }}>
        已切换至新服务商「{profileName}」，发送前将确认是否携带本对话历史
      </span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        <button type="button" className="agent-mini-btn" onClick={onCreateSession}>
          用新模型新开对话
        </button>
        <button type="button" className="agent-mini-btn" onClick={() => setDismissed(true)}>
          关闭提示
        </button>
      </div>
    </div>
  )
}

interface AgentDrawerProps {
  open: boolean
  floating?: boolean
  onClose: () => void
  onToggleFloating?: (floating: boolean) => void
  onPopOut?: () => void
  onRunningChange?: (running: boolean) => void
  standalone?: boolean
  contextChip: AgentActiveContextChip | null
  filteredItems?: CuratorItem[]
  filterLabel?: string | null
  settings: DesktopSettings
  onApplySettingsPatch: (patch: Partial<DesktopSettings>) => void
  onExecuteUiAction: (action: AgentUiActionCommand) => void
  onOpenWork?: (kind: string, id: number) => void
  onDomainsChanged: (domains: string[]) => void
  onToast: (msg: string) => void
}

const STARTER_PROMPTS = [
  {
    tag: '体检整理',
    title: '扫描可整理数据',
    desc: '检查待归并同系列、疑似重复条目与未绑外部 ID',
    prompt: '/整理',
    slash: '/整理'
  },
  {
    tag: '补全资料',
    title: '排查缺封面与骨架条目',
    desc: '筛出缺失封面、开播日或标签的作品并从官方源补全',
    prompt: '帮我筛出库里所有缺封面或者缺少开播日期、标签的动画，列成清单，再看看哪些可以从 Bangumi 补全',
    slash: null
  },
  {
    tag: '个性推荐',
    title: '按我口味推 5 部番',
    desc: '结合高分作品与标签画像避雷已看并推荐',
    prompt: '/推番',
    slash: '/推番'
  },
  {
    tag: '防剧透聊番',
    title: '查在看进度与防剧透复盘',
    desc: '按当前已看集数梳理线索，绝不剧透后续剧情',
    prompt: '列出我正在看的动画和当前进度，挑最近更新的一部帮我无剧透回顾前面的看点',
    slash: null
  },
  {
    tag: '软件调教',
    title: '调整桌面外观或超分',
    desc: '切暗色护眼主题、调大字号或优化播放流畅度',
    prompt: '帮我看看现在的主题、字号和视频超分设置，给我推荐一套舒服的配置',
    slash: null
  },
  {
    tag: '使用指南',
    title: '本地封面与手机端同步',
    desc: '查阅当前版本指南并直达对应入口',
    prompt: '桌面端怎么和手机端同步数据和本地封面，怎么绑定 Bangumi？顺便帮我打开同步页面',
    slash: null
  }
]

const isProposalAppliedStatus = (status: string): boolean =>
  status === 'applied' || status === 'auto_applied'

const PERSONA_LABELS: Record<string, string> = {
  curator: '温和策展官',
  otaku_friend: '二次元老友',
  efficient: '极简效率派',
  spoiler_guard: '防剧透伴看',
  diagnostic: '专业诊断',
  custom: '自定义人设'
}

export const AgentDrawer: React.FC<AgentDrawerProps> = ({
  open,
  floating = false,
  onClose,
  onToggleFloating,
  onPopOut,
  onRunningChange,
  standalone = false,
  contextChip,
  filteredItems,
  filterLabel,
  settings,
  onApplySettingsPatch,
  onExecuteUiAction,
  onOpenWork,
  onDomainsChanged,
  onToast
}) => {
  const dialog = useStudioDialog()
  const { zIndex: drawerZ, bringToFront: bringDrawerToFront } = useFloatingLayer(
    open || floating,
    'panel'
  )
  const {
    offset: drawerOffset,
    isMoved: drawerMoved,
    resetOffset: resetDrawerOffset,
    dragHandlers: drawerDragHandlers
  } = useDraggablePanel({ open, minY: -8 })
  const drawerAsideRef = useRef<HTMLElement | null>(null)
  const [pillMorphOrigin, setPillMorphOrigin] = useState<MorphRect | null>(null)
  const drawerMorphOriginRef = useRef<MorphRect | null>(null)
  const [wideMode, setWideMode] = useState(false)
  const [showSessions, setShowSessions] = useState(false)
  const [showSummaryBox, setShowSummaryBox] = useState(false)
  const [summaryDraft, setSummaryDraft] = useState('')
  const [compacting, setCompacting] = useState(false)

  const [configOpen, setConfigOpen] = useState(false)
  const [configTab, setConfigTab] = useState<AgentConfigTab>('models')
  const [trajectoryOpen, setTrajectoryOpen] = useState(false)

  const [profiles, setProfiles] = useState<AgentProfile[]>([])
  const [selectedProfileId, setSelectedProfileId] = useState<string>('')
  const [policy, setPolicy] = useState<AgentPolicyConfig | null>(null)
  const [commands, setCommands] = useState<AgentSlashCommand[]>([])
  const [memoryCount, setMemoryCount] = useState(0)

  // Session management state
  const [sessions, setSessions] = useState<AgentSession[]>([])
  const [sessionSearch, setSessionSearch] = useState('')
  const [showArchivedSessions, setShowArchivedSessions] = useState(false)
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null)
  const [renameText, setRenameText] = useState('')

  // Messages & proposals
  const [messages, setMessages] = useState<AgentMessage[]>([])
  const [proposalsMap, setProposalsMap] = useState<Record<string, AgentProposal>>({})
  const [verifyJobs, setVerifyJobs] = useState<AgentVerifyJobSummary[]>([])
  /** 记录从某张分批核实卡片当场点击生成的提案锚点（propId -> jobId），确保提案紧贴在对应核实卡片下方出现 */
  const [proposalJobAnchors, setProposalJobAnchors] = useState<Record<string, string>>({})

  // Streaming state
  const [activeRunId, setActiveRunId] = useState<string | null>(null)
  useEffect(() => {
    onRunningChange?.(activeRunId !== null)
  }, [activeRunId, onRunningChange])
  const [streamingContent, setStreamingContent] = useState('')
  const [streamingReasoning, setStreamingReasoning] = useState('')
  const [streamingTraces, setStreamingTraces] = useState<AgentToolTraceItem[]>([])
  const [streamingPendingTool, setStreamingPendingTool] = useState<string | null>(null)
  const [streamingStepText, setStreamingStepText] = useState<string | null>(null)
  const [streamingUiActions, setStreamingUiActions] = useState<AgentUiActionCommand[]>([])
  const [streamingMemEvents, setStreamingMemEvents] = useState<AgentMemoryChangeEvent[]>([])
  const [streamingSettingsUndo, setStreamingSettingsUndo] =
    useState<AgentSettingsUndoRecord | null>(null)

  // Composer state
  const [draft, setDraft] = useState('')
  const [attachContext, setAttachContext] = useState(true)
  const [attachFilterSnapshot, setAttachFilterSnapshot] = useState(false)
  const [antiSpoiler, setAntiSpoiler] = useState(true)
  const [slashIndex, setSlashIndex] = useState(0)
  const [confirmedCrossProviderKey, setConfirmedCrossProviderKey] = useState<string | null>(null)
  const [sessionAutopilot, setSessionAutopilot] = useState(false)
  const [batchBusy, setBatchBusy] = useState(false)
  const [lastBatchUndo, setLastBatchUndo] = useState<{
    batchGroupId: string
    summary: string
  } | null>(null)

  const feedRef = useRef<HTMLDivElement | null>(null)
  const activeRunRef = useRef<string | null>(null)
  const activeSessionRef = useRef<string | null>(null)
  const sendingRef = useRef(false)
  const imeComposingRef = useRef(false)
  const earlyEventsRef = useRef<Map<string, AgentStreamEnvelope[]>>(new Map())
  const loadSeqRef = useRef(0)
  const drawerVisibleRef = useRef(open && !floating)
  const [sessionLoadSeq, setSessionLoadSeq] = useState(0)
  const [bgFinishState, setBgFinishState] = useState<'none' | 'completed' | 'error'>('none')

  const isDrawerVisible = open && !floating
  useEffect(() => {
    drawerVisibleRef.current = isDrawerVisible
    if (isDrawerVisible) {
      setBgFinishState('none')
    }
  }, [isDrawerVisible])

  useEffect(() => {
    activeRunRef.current = activeRunId
  }, [activeRunId])

  useEffect(() => {
    activeSessionRef.current = activeSessionId
    setSessionAutopilot(false)
    setLastBatchUndo(null)
  }, [activeSessionId])

  const personaOptions = useMemo<StudioSelectOption[]>(
    () =>
      Object.entries(PERSONA_LABELS).map(([k, lbl]) => ({
        value: k,
        label: lbl
      })),
    []
  )

  const profileSelectOptions = useMemo<StudioSelectOption[]>(
    () =>
      profiles.map((p) => {
        const badges: string[] = []
        if (p.isDefault) badges.push('默认')
        if (p.enableWebSearch) badges.push('联网')
        if (!p.supportsTools) badges.push('纯聊')
        return {
          value: p.id,
          label: p.name,
          sublabel: p.model,
          badges,
          title: `${p.name} (${p.model})`
        }
      }),
    [profiles]
  )

  const activeSession = useMemo(
    () => sessions.find((s) => s.id === activeSessionId) ?? null,
    [sessions, activeSessionId]
  )

  const activeProfile = useMemo(
    () => profiles.find((p) => p.id === selectedProfileId) ?? profiles[0] ?? null,
    [profiles, selectedProfileId]
  )

  const refreshMetadata = useCallback(async () => {
    try {
      const [profs, pol, cmds, mems] = await Promise.all([
        studioApi.agentListProfiles(),
        studioApi.agentGetPolicy(),
        studioApi.agentListCommands(),
        studioApi.agentListMemories()
      ])
      setProfiles(profs)
      setPolicy(pol)
      setAntiSpoiler(pol.defaultAntiSpoiler)
      setCommands(cmds)
      setMemoryCount(mems.filter((m) => m.enabled).length)
      if (profs.length > 0) {
        setSelectedProfileId((prev) =>
          prev && profs.some((p) => p.id === prev) ? prev : profs[0].id
        )
      } else {
        setSelectedProfileId('')
      }
    } catch (err) {
      console.warn('[AgentDrawer] 加载助手配置失败:', err)
      onToast(`加载 AI 助手配置失败：${err instanceof Error ? err.message : String(err)}`)
    }
  }, [onToast])

  const refreshSessions = useCallback(
    async (ensureOne = false) => {
      try {
        let list = await studioApi.agentListSessions(showArchivedSessions, sessionSearch)
        if (ensureOne && list.length === 0 && !sessionSearch.trim() && !showArchivedSessions) {
          const created = await studioApi.agentCreateSession('新对话', null, policy?.personaId ?? 'curator')
          list = [created]
        }
        setSessions(list)
        if (!activeSessionRef.current && list.length > 0) {
          setActiveSessionId(list[0].id)
        }
      } catch (err) {
        console.warn('[AgentDrawer] 加载会话列表失败:', err)
        onToast(`加载对话列表失败：${err instanceof Error ? err.message : String(err)}`)
      }
    },
    [showArchivedSessions, sessionSearch, policy?.personaId, onToast]
  )

  const refreshVerifyJobs = useCallback(async (sid: string | null) => {
    if (!sid) {
      setVerifyJobs([])
      return
    }
    try {
      const jobs = await studioApi.agentListVerifyJobs(sid)
      setVerifyJobs(jobs)
    } catch (err) {
      console.warn('[AgentDrawer] 加载后台核查任务失败:', err)
      setVerifyJobs([])
    }
  }, [])

  const loadSessionContent = useCallback(
    async (sid: string) => {
      const seq = ++loadSeqRef.current
      try {
        const [data, jobs] = await Promise.all([
          studioApi.agentLoadSessionData(sid),
          studioApi.agentListVerifyJobs(sid).catch((err) => {
            console.warn('[AgentDrawer] 读取核查任务失败，已降级为空列表:', err)
            return [] as AgentVerifyJobSummary[]
          })
        ])
        if (seq !== loadSeqRef.current) return
        setMessages(data.messages)
        const map: Record<string, AgentProposal> = {}
        for (const p of data.proposals) {
          map[p.id] = p
        }
        setProposalsMap(map)
        setVerifyJobs(jobs)
        setProposalJobAnchors({})
        setSessionLoadSeq((n) => n + 1)
      } catch (err) {
        console.warn('[AgentDrawer] 加载对话历史失败:', err)
        onToast(`读取历史对话失败：${err instanceof Error ? err.message : String(err)}`)
      }
    },
    [onToast]
  )

  useEffect(() => {
    if (!open && !floating) return
    refreshMetadata()
    refreshSessions(true)
  }, [open, floating, refreshMetadata, refreshSessions])

  useEffect(() => {
    if (!open) return
    const timer = window.setTimeout(() => {
      refreshSessions(false)
    }, 180)
    return () => window.clearTimeout(timer)
  }, [sessionSearch, showArchivedSessions, open, refreshSessions])

  useEffect(() => {
    if (isDrawerVisible && activeSessionId) {
      loadSessionContent(activeSessionId)
      const found = sessions.find((s) => s.id === activeSessionId)
      setSummaryDraft(found?.rollingSummary ?? '')
    }
  }, [isDrawerVisible, activeSessionId, loadSessionContent])

  // 智能滚动控制：进入旧对话首帧前瞬时定格最新内容；流式回复时支持随时上翻浏览历史，不抢滚动条
  const {
    showScrollToBottom,
    hasUnreadBelow,
    scrollToBottom,
    handleFeedScroll,
    handleFeedWheel
  } = useAgentAutoScroll({
    feedRef,
    visible: isDrawerVisible,
    sessionKey: activeSessionId,
    sessionLoadSeq,
    isStreaming: Boolean(activeRunId),
    messageCount: messages.length,
    streamingContentLen: streamingContent.length,
    streamingReasoningLen: streamingReasoning.length,
    streamingTracesCount: streamingTraces.length,
    streamingPendingTool,
    streamingStepText
  })

  const resetStreamingBuffers = useCallback(() => {
    setStreamingContent('')
    setStreamingReasoning('')
    setStreamingTraces([])
    setStreamingPendingTool(null)
    setStreamingStepText(null)
    setStreamingUiActions([])
    setStreamingMemEvents([])
    setStreamingSettingsUndo(null)
  }, [])

  const processStreamEnvelope = useCallback(
    (env: AgentStreamEnvelope) => {
      const ev = env.event
      switch (ev.type) {
        case 'started':
          if (ev.userMessage) {
            setMessages((prev) =>
              prev.some((m) => m && m.id === ev.userMessage.id)
                ? prev
                : [...prev, ev.userMessage]
            )
          }
          break
        case 'stepStart':
          setStreamingStepText(ev.step > 1 ? `第 ${ev.step}/${ev.maxSteps} 步推理中…` : '思考中…')
          break
        case 'delta':
          setStreamingPendingTool(null)
          setStreamingContent((prev) => prev + ev.text)
          break
        case 'reasoningDelta':
          setStreamingReasoning((prev) => prev + ev.text)
          break
        case 'toolStart':
          setStreamingPendingTool(`${ev.label}…`)
          break
        case 'toolResult':
          setStreamingPendingTool(null)
          setStreamingTraces((prev) =>
            prev.some((t) => t.id === ev.trace.id) ? prev : [...prev, ev.trace]
          )
          break
        case 'uiAction':
          setStreamingUiActions((prev) => [...prev, ev.action])
          onExecuteUiAction(ev.action)
          break
        case 'settingsApplied':
          setStreamingSettingsUndo(ev.undo)
          onApplySettingsPatch(ev.undo.patch as Partial<DesktopSettings>)
          onToast(ev.undo.summary)
          break
        case 'memoryChanged':
          setStreamingMemEvents((prev) => [...prev, ev.event])
          refreshMetadata()
          break
        case 'contextCompacted':
          setSummaryDraft(ev.summary)
          onToast(`长对话已自动整理压缩（归档 ${ev.compactedMessages} 条前文，关键记忆完整保留）`)
          refreshSessions(false)
          void refreshVerifyJobs(env.sessionId)
          break
        case 'proposalCreated':
          setProposalsMap((prev) => ({ ...prev, [ev.proposal.id]: ev.proposal }))
          break
        case 'quickApplied':
        case 'autoApplied':
          setProposalsMap((prev) => ({ ...prev, [ev.proposal.id]: ev.proposal }))
          onDomainsChanged(ev.changedDomains)
          onToast(ev.summary)
          break
        case 'done':
          activeRunRef.current = null
          setActiveRunId(null)
          resetStreamingBuffers()
          if (!drawerVisibleRef.current) {
            setBgFinishState('completed')
          }
          if (ev.assistantMessage) {
            setMessages((prev) => {
              const idx = prev.findIndex((m) => m && m.id === ev.assistantMessage.id)
              if (idx >= 0) {
                const next = [...prev]
                next[idx] = ev.assistantMessage
                return next
              }
              return [...prev, ev.assistantMessage]
            })
          }
          refreshSessions(false)
          void refreshVerifyJobs(env.sessionId)
          break
        case 'diagnosticError':
          activeRunRef.current = null
          setActiveRunId(null)
          resetStreamingBuffers()
          if (!drawerVisibleRef.current) {
            setBgFinishState('error')
          }
          if (ev.partialMessage) {
            setMessages((prev) => {
              const idx = prev.findIndex((m) => m.id === ev.partialMessage!.id)
              if (idx >= 0) {
                const next = [...prev]
                next[idx] = ev.partialMessage!
                return next
              }
              return [...prev, ev.partialMessage!]
            })
          } else {
            const errMsg: AgentMessage = {
              id: `err-${Date.now()}`,
              sessionId: env.sessionId,
              role: 'assistant',
              content: '',
              toolTraces: [],
              uiActions: [],
              memoryEvents: [],
              diagnosticError: ev.error,
              createdAt: new Date().toISOString()
            }
            setMessages((prev) => [...prev, errMsg])
          }
          refreshSessions(false)
          void refreshVerifyJobs(env.sessionId)
          break
      }
    },
    [
      onApplySettingsPatch,
      onDomainsChanged,
      onExecuteUiAction,
      onToast,
      refreshMetadata,
      refreshSessions,
      refreshVerifyJobs,
      resetStreamingBuffers
    ]
  )

  const activateRunAndFlush = useCallback(
    (runId: string) => {
      activeRunRef.current = runId
      setActiveRunId(runId)
      const buffered = earlyEventsRef.current.get(runId)
      if (buffered && buffered.length > 0) {
        earlyEventsRef.current.delete(runId)
        for (const env of buffered) {
          processStreamEnvelope(env)
        }
      }
    },
    [processStreamEnvelope]
  )

  const processStreamRef = useRef(processStreamEnvelope)
  useEffect(() => {
    processStreamRef.current = processStreamEnvelope
  }, [processStreamEnvelope])

  // 单例订阅 Rust 实时流事件（仅挂载注册一次，异步销毁加 disposed 守卫杜绝重复监听导致提问/输出翻倍）
  useEffect(() => {
    let disposed = false
    let unlisten: (() => void) | null = null
    studioApi
      .onAgentStream((env: AgentStreamEnvelope) => {
        if (disposed) return
        if (env.runId !== activeRunRef.current) {
          const list = earlyEventsRef.current.get(env.runId) ?? []
          list.push(env)
          earlyEventsRef.current.set(env.runId, list)
          if (earlyEventsRef.current.size > 16) {
            const oldestKey = earlyEventsRef.current.keys().next().value
            if (oldestKey) earlyEventsRef.current.delete(oldestKey)
          }
          return
        }
        processStreamRef.current(env)
      })
      .then((fn) => {
        if (disposed) {
          fn()
        } else {
          unlisten = fn
        }
      })
      .catch(() => {})
    return () => {
      disposed = true
      if (unlisten) {
        unlisten()
        unlisten = null
      }
    }
  }, [])

  const slashMatches = useMemo(() => {
    if (!draft.startsWith('/')) return []
    const token = draft.split(/\s+/)[0].toLowerCase()
    if (draft.includes(' ')) return []
    return commands.filter(
      (c) =>
        c.command.toLowerCase().startsWith(token) ||
        c.label.toLowerCase().includes(token.slice(1))
    )
  }, [draft, commands])

  const pushNoModelDiagnostic = useCallback(
    (sessionId: string | null) => {
      const errMsg: AgentMessage = {
        id: `err-no-model-${Date.now()}`,
        sessionId: sessionId ?? 'local',
        role: 'assistant',
        content: '',
        toolTraces: [],
        uiActions: [],
        memoryEvents: [],
        diagnosticError: {
          code: 'NO_MODEL_CONFIGURED',
          title: '尚未接入大模型',
          explanation: '当前还没有已保存的大模型档案，因此无法发起对话或调用智能体工具。',
          suggestion:
            '请点击下方「打开模型接入配置」，选择 DeepSeek、硅基流动、阿里百炼、OpenAI 或本地 Ollama（免 Key）完成 30 秒接入后再发送。',
          quickFix: 'open_config',
          technicalDetail: 'activeProfile is null (0 profiles configured)'
        },
        createdAt: new Date().toISOString()
      }
      setMessages((prev) => [...prev, errMsg])
      onToast('尚未接入大模型，请先配置一个模型档案')
    },
    [onToast]
  )

  const sessionBoundProfile = useMemo(() => {
    if (!activeSession?.profileId) return null
    return profiles.find((p) => p.id === activeSession.profileId) ?? null
  }, [activeSession?.profileId, profiles])

  const isCrossProviderSwitch = useMemo(() => {
    if (!activeSession || !activeProfile || !sessionBoundProfile) return false
    if (messages.filter((m) => m && (m.role === 'user' || m.role === 'assistant')).length === 0) {
      return false
    }
    const sameProvider =
      sessionBoundProfile.providerId === activeProfile.providerId &&
      sessionBoundProfile.baseUrl.trim().replace(/\/+$/, '') ===
        activeProfile.baseUrl.trim().replace(/\/+$/, '')
    return !sameProvider
  }, [activeSession, activeProfile, sessionBoundProfile, messages])

  const handleSend = async (overrideText?: string, overrideSlash?: string | null) => {
    const rawText = (overrideText ?? draft).trim()
    if (!rawText || activeRunId || activeRunRef.current || sendingRef.current) return

    if (!activeProfile) {
      pushNoModelDiagnostic(activeSessionId)
      return
    }

    let sid = activeSessionId
    if (sid && isCrossProviderSwitch) {
      const switchKey = `${sid}:${activeProfile.id}`
      if (confirmedCrossProviderKey !== switchKey) {
        const carryHistory = await dialog.confirm({
          title: '切换了不同的模型服务商',
          message: `当前对话之前使用的是「${sessionBoundProfile?.name ?? '原模型'}」，现在将切换为「${activeProfile.name}」。继续发送会把当前对话历史发给新服务商。`,
          detail: '选「带上历史继续」将把这段聊天记录交给新模型；选「新开空白对话」将保留原对话，用新模型另开一条干净对话。',
          confirmText: '带上历史继续',
          cancelText: '新开空白对话',
          tone: 'warning'
        })
        if (carryHistory) {
          setConfirmedCrossProviderKey(switchKey)
        } else {
          const created = await studioApi.agentCreateSession(
            rawText.slice(0, 24),
            activeProfile.id,
            policy?.personaId ?? 'curator'
          )
          sid = created.id
          setActiveSessionId(sid)
          setSessions((prev) => [created, ...prev])
          setMessages([])
          setProposalsMap({})
          setVerifyJobs([])
          setSummaryDraft('')
        }
      }
    }

    sendingRef.current = true
    try {
      if (!sid) {
        const created = await studioApi.agentCreateSession(
          rawText.slice(0, 24),
          selectedProfileId || null,
          policy?.personaId ?? 'curator'
        )
        sid = created.id
        setActiveSessionId(sid)
        setSessions((prev) => [created, ...prev])
      }

      let slashCmd = overrideSlash ?? null
      if (!slashCmd && rawText.startsWith('/')) {
        const firstToken = rawText.split(/\s+/)[0]
        if (commands.some((c) => c.command.toLowerCase() === firstToken.toLowerCase())) {
          slashCmd = firstToken
        }
      }

      setDraft('')
      resetStreamingBuffers()
      scrollToBottom(false)

      let filterResultId: string | null = null
      let filterCount: number | null = null
      let effectiveFilterLabel: string | null = null
      if (attachFilterSnapshot && filteredItems && filteredItems.length > 0) {
        try {
          effectiveFilterLabel = filterLabel || `当前筛选结果 (${filteredItems.length} 部)`
          filterResultId = await studioApi.agentSaveFilterSnapshot(
            sid,
            filteredItems,
            effectiveFilterLabel
          )
          filterCount = filteredItems.length
        } catch (err) {
          console.warn('[AgentDrawer] 保存筛选结果快照失败，已降级为不附带筛选快照:', err)
          onToast('附加当前筛选列表快照失败，本次将直接发送消息')
        }
      }

      const baseChip: AgentActiveContextChip | null =
        attachContext && contextChip
          ? { ...contextChip, antiSpoilerEnabled: antiSpoiler }
          : filterResultId
            ? {
                activeTab: contextChip?.activeTab ?? 'curator',
                activeSubView: contextChip?.activeSubView ?? null,
                antiSpoilerEnabled: antiSpoiler
              }
            : null

      const chipPayload: AgentActiveContextChip | null = baseChip
        ? {
            ...baseChip,
            filterResultId,
            filterCount,
            filterLabel: effectiveFilterLabel
          }
        : null

      const runId = await studioApi.agentSendMessage({
        sessionId: sid,
        profileId: selectedProfileId || null,
        userMessage: rawText,
        slashCommand: slashCmd,
        contextChip: chipPayload,
        currentSettings: settings as unknown as Record<string, unknown>,
        sessionAutopilot
      })
      activateRunAndFlush(runId)
    } catch (e) {
      activeRunRef.current = null
      setActiveRunId(null)
      onToast(e instanceof Error ? e.message : String(e))
    } finally {
      sendingRef.current = false
    }
  }

  const handleStop = async () => {
    if (!activeRunId) return
    try {
      const cancelled = await studioApi.agentCancelRun(activeRunId)
      if (!cancelled) {
        activeRunRef.current = null
        setActiveRunId(null)
        resetStreamingBuffers()
      }
      onToast('已停止生成')
    } catch (err) {
      console.warn('[AgentDrawer] 停止生成请求返回异常:', err)
      activeRunRef.current = null
      setActiveRunId(null)
      resetStreamingBuffers()
      onToast(`停止请求出现异常，已重置本地运行状态：${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const handleCreateSession = async () => {
    if (activeRunId) return
    // 当前已经是空对话时不再重复新建
    if (
      activeSessionId &&
      visibleMessages.length === 0 &&
      !activeSession?.rollingSummary
    ) {
      setShowSessions(false)
      onToast('当前已经是空的新对话')
      return
    }
    // 若对话列表中已有未归档的空对话，直接复用切换而不重复创建
    const existingEmpty = sessions.find(
      (s) => !s.archived && s.messageCount === 0 && !s.rollingSummary
    )
    if (existingEmpty) {
      setActiveSessionId(existingEmpty.id)
      setMessages([])
      setProposalsMap({})
      setSummaryDraft('')
      setShowSessions(false)
      onToast('已切换到已有的空对话')
      return
    }
    const created = await studioApi.agentCreateSession(
      '新对话',
      selectedProfileId || null,
      policy?.personaId ?? 'curator'
    )
    setSessions((prev) => [created, ...prev])
    setActiveSessionId(created.id)
    setMessages([])
    setProposalsMap({})
    setSummaryDraft('')
    setShowSessions(false)
  }

  const handleCompactNow = async () => {
    if (!activeSessionId || compacting || activeRunId) return
    setCompacting(true)
    try {
      const res = await studioApi.agentCompactSession(
        activeSessionId,
        selectedProfileId || null,
        policy?.maxHistoryTurns ?? 6
      )
      if (res.compacted && res.summary) {
        setSummaryDraft(res.summary)
        setShowSummaryBox(true)
        onToast(`已将较早的 ${res.compactedMessages} 条对话压缩为滚动摘要，后续长对话更省 Token`)
        refreshSessions(false)
      } else {
        onToast('当前对话还比较精简，暂无需压缩')
      }
    } catch (e) {
      onToast(e instanceof Error ? e.message : String(e))
    } finally {
      setCompacting(false)
    }
  }

  const handleSaveSummaryManual = async () => {
    if (!activeSessionId) return
    await studioApi.agentUpdateSession({
      sessionId: activeSessionId,
      rollingSummary: summaryDraft
    })
    refreshSessions(false)
    onToast(summaryDraft.trim() ? '已更新本对话滚动摘要' : '已清空滚动摘要')
  }

  const handleExportSession = async () => {
    if (!activeSessionId || !activeSession) return
    try {
      const savedPath = await studioApi.agentExportSessionMarkdown(
        activeSessionId,
        activeSession.title || '追番喵AI对话'
      )
      if (savedPath) onToast('对话已导出为 Markdown 文件')
    } catch (e) {
      onToast(e instanceof Error ? e.message : String(e))
    }
  }

  const handleClearSession = async () => {
    if (!activeSessionId || activeRunId) return
    const revertibleCount = Object.values(proposalsMap).filter(
      (p) => isProposalAppliedStatus(p.status) && p.canRevert
    ).length
    const ok = await dialog.confirm({
      title: '清空当前对话消息？',
      message:
        revertibleCount > 0
          ? `清空后，本对话里最近 ${revertibleCount} 次藏品修改将无法再从这里一键撤销。`
          : '会清除当前对话的消息和滚动摘要，但保留你的长期记忆和藏品库数据。',
      detail:
        revertibleCount > 0
          ? '如果之后可能还要撤销修改，建议改为在左侧「对话」列表里点「归档」保留撤销记录。'
          : undefined,
      confirmText: '确认清空',
      tone: 'danger'
    })
    if (!ok) return
    await studioApi.agentClearSession(activeSessionId)
    setMessages([])
    setProposalsMap({})
    setVerifyJobs([])
    setSummaryDraft('')
    refreshSessions(false)
    onToast('已清空当前对话')
  }

  const handleDeleteSession = async (sess: AgentSession) => {
    let revertibleCount = 0
    try {
      if (sess.id === activeSessionId) {
        revertibleCount = Object.values(proposalsMap).filter(
          (p) => isProposalAppliedStatus(p.status) && p.canRevert
        ).length
      } else {
        const data = await studioApi.agentLoadSessionData(sess.id)
        revertibleCount = data.proposals.filter(
          (p) => isProposalAppliedStatus(p.status) && p.canRevert
        ).length
      }
    } catch {
      revertibleCount = 0
    }
    const ok = await dialog.confirm({
      title: `删除对话「${sess.title}」？`,
      message:
        revertibleCount > 0
          ? `删除后，该对话里最近 ${revertibleCount} 次已执行的藏品修改将无法再从这里一键撤销。`
          : '该对话记录将被永久移除，不影响长期记忆与藏品库。',
      detail:
        revertibleCount > 0
          ? '如需保留撤销入口，建议取消删除并点击「归档」按钮。'
          : undefined,
      confirmText: '确认删除',
      tone: 'danger'
    })
    if (!ok) return
    await studioApi.agentDeleteSession(sess.id)
    if (activeSessionId === sess.id) {
      setActiveSessionId(null)
      setMessages([])
      setProposalsMap({})
      setVerifyJobs([])
    }
    await refreshSessions(true)
    onToast('已删除对话')
  }

  const handleRegenerateMessage = async (targetMsg?: AgentMessage) => {
    if (!activeSessionId || activeRunId || activeRunRef.current || sendingRef.current) return
    if (!activeProfile) {
      pushNoModelDiagnostic(activeSessionId)
      return
    }
    const visible = messages.filter((m) => m.role === 'user' || m.role === 'assistant')
    const assistantMsg =
      targetMsg ?? [...visible].reverse().find((m) => m.role === 'assistant')
    if (!assistantMsg) return
    const lastUser = [...visible].reverse().find((m) => m.role === 'user')
    sendingRef.current = true
    resetStreamingBuffers()
    scrollToBottom(false)
    const chipPayload: AgentActiveContextChip | null =
      attachContext && contextChip
        ? { ...contextChip, antiSpoilerEnabled: antiSpoiler }
        : null
    try {
      const runId = await studioApi.agentSendMessage({
        sessionId: activeSessionId,
        profileId: selectedProfileId || null,
        userMessage: lastUser?.content ?? '',
        slashCommand: null,
        contextChip: chipPayload,
        currentSettings: settings as unknown as Record<string, unknown>,
        regenerateMessageId: assistantMsg.id
      })
      activateRunAndFlush(runId)
    } catch (e) {
      activeRunRef.current = null
      setActiveRunId(null)
      onToast(e instanceof Error ? e.message : String(e))
    } finally {
      sendingRef.current = false
    }
  }

  const handleForkFromMessage = async (msg: AgentMessage) => {
    if (!activeSessionId || activeRunId) return
    try {
      const forked = await studioApi.agentForkSession(activeSessionId, msg.id)
      setSessions((prev) => [forked, ...prev])
      setActiveSessionId(forked.id)
      onToast(`已从该节点分叉出平行对话「${forked.title}」`)
    } catch (e) {
      onToast(e instanceof Error ? e.message : String(e))
    }
  }

  const handleSwitchVersion = async (msg: AgentMessage, versionIdx: number) => {
    if (!activeSessionId || activeRunId) return
    try {
      const updated = await studioApi.agentSwitchMessageVersion(
        activeSessionId,
        msg.id,
        versionIdx
      )
      setMessages((prev) => prev.map((m) => (m.id === updated.id ? updated : m)))
    } catch (e) {
      onToast(e instanceof Error ? e.message : String(e))
    }
  }

  const handleEditResend = async (msg: AgentMessage) => {
    if (!activeSessionId || activeRunId) return
    await studioApi.agentTruncateSessionFrom(activeSessionId, msg.id)
    await loadSessionContent(activeSessionId)
    setDraft(msg.content)
  }

  const handleQuickFix = async (err: AgentDiagnosticError) => {
    if (err.quickFix === 'open_config') {
      setConfigTab('models')
      setConfigOpen(true)
      return
    }
    if (err.quickFix === 'apply_suggested_url' && err.suggestedBaseUrl && activeProfile) {
      await studioApi.agentSaveProfile({
        id: activeProfile.id,
        name: activeProfile.name,
        providerId: activeProfile.providerId,
        baseUrl: err.suggestedBaseUrl,
        model: activeProfile.model,
        temperature: activeProfile.temperature,
        maxSteps: activeProfile.maxSteps,
        maxTurnTokens: activeProfile.maxTurnTokens,
        supportsTools: activeProfile.supportsTools,
        supportsReasoning: activeProfile.supportsReasoning,
        enableWebSearch: activeProfile.enableWebSearch,
        isDefault: activeProfile.isDefault
      })
      await refreshMetadata()
      onToast(`已自动修正接口地址为 ${err.suggestedBaseUrl}`)
      return
    }
    if (err.quickFix === 'disable_tools' && activeProfile) {
      await studioApi.agentSaveProfile({
        id: activeProfile.id,
        name: activeProfile.name,
        providerId: activeProfile.providerId,
        baseUrl: activeProfile.baseUrl,
        model: activeProfile.model,
        temperature: activeProfile.temperature,
        maxSteps: activeProfile.maxSteps,
        maxTurnTokens: activeProfile.maxTurnTokens,
        supportsTools: false,
        supportsReasoning: activeProfile.supportsReasoning,
        enableWebSearch: activeProfile.enableWebSearch,
        isDefault: activeProfile.isDefault
      })
      await refreshMetadata()
      onToast('已切换当前模型档案为「仅对话模式」，现在可重试发送')
      return
    }
    if (err.quickFix === 'retry') {
      await handleRegenerateMessage()
    }
  }

  const visibleMessages = useMemo(
    () =>
      messages.filter((m) => {
        if (!m) return false
        if (m.role === 'user') return true
        if (m.role !== 'assistant') return false
        const isIntermediateToolCall =
          Boolean(m.toolCalls && m.toolCalls.length > 0) &&
          !m.content.trim() &&
          !m.diagnosticError &&
          !m.proposalId &&
          (!m.proposalIds || m.proposalIds.length === 0) &&
          m.toolTraces.length === 0
        return !isIntermediateToolCall
      }),
    [messages]
  )

  const lastAssistantId = useMemo(() => {
    for (let i = visibleMessages.length - 1; i >= 0; i--) {
      if (visibleMessages[i].role === 'assistant') return visibleMessages[i].id
    }
    return null
  }, [visibleMessages])

  /**
   * 按对话时间线归位「分批核实卡片」与「游离提案（例如从核实卡片生成的绑定方案）」，
   * 避免历史核实任务和旧提案一直堆叠在最新对话的最底部。
   */
  const timelineAnchors = useMemo(() => {
    const parseIsoMs = (iso: string | null | undefined): number => {
      if (!iso) return 0
      const ms = Date.parse(iso)
      return Number.isFinite(ms) ? ms : 0
    }

    const jobsAfterMsg = new Map<string, AgentVerifyJobSummary[]>()
    const trailingJobs: AgentVerifyJobSummary[] = []

    const sortedJobs = [...verifyJobs].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    const assistantMessages = visibleMessages.filter((m) => m.role === 'assistant')

    for (const job of sortedJobs) {
      let anchorMsgId: string | null = null
      // 1) 优先通过句柄精确锚定到创建该核实任务的助手回复
      for (const msg of assistantMessages) {
        const hitTrace = msg.toolTraces.some(
          (tr) =>
            tr.name === 'search_remote_catalog' &&
            ((job.id && tr.argsPreview.includes(job.id)) ||
              (job.resultId && tr.argsPreview.includes(job.resultId)))
        )
        const hitAction = msg.uiActions.some(
          (act) =>
            act.action === 'library_result' &&
            typeof act.payload?.resultId === 'string' &&
            act.payload.resultId === job.resultId
        )
        if (hitTrace || hitAction) {
          anchorMsgId = msg.id
          break
        }
      }
      // 2) 回退按时间戳锚定到紧邻该核实任务创建后的第一条助手回复
      if (!anchorMsgId) {
        const jobMs = parseIsoMs(job.createdAt)
        const byTime = assistantMessages.find((m) => parseIsoMs(m.createdAt) >= jobMs - 1000)
        if (byTime) anchorMsgId = byTime.id
      }

      if (anchorMsgId) {
        const list = jobsAfterMsg.get(anchorMsgId) ?? []
        list.push(job)
        jobsAfterMsg.set(anchorMsgId, list)
      } else {
        trailingJobs.push(job)
      }
    }

    const attachedIds = new Set<string>()
    for (const msg of visibleMessages) {
      if (msg.proposalId) attachedIds.add(msg.proposalId)
      if (msg.proposalIds) {
        for (const pid of msg.proposalIds) attachedIds.add(pid)
      }
    }

    const orphanProposals = Object.values(proposalsMap)
      .filter((p) => !attachedIds.has(p.id))
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))

    const existingJobIds = new Set(verifyJobs.map((j) => j.id))
    const proposalsByJob = new Map<string, AgentProposal[]>()
    const proposalsAfterMsg = new Map<string, AgentProposal[]>()
    const trailingProposals: AgentProposal[] = []

    for (const prop of orphanProposals) {
      const explicitJobId = proposalJobAnchors[prop.id]
      if (explicitJobId && existingJobIds.has(explicitJobId)) {
        const list = proposalsByJob.get(explicitJobId) ?? []
        list.push(prop)
        proposalsByJob.set(explicitJobId, list)
        continue
      }

      // 历史游离提案按创建时间插入到对应时间线消息下方
      const propMs = parseIsoMs(prop.createdAt)
      let anchorMsgId: string | null = null
      for (let i = visibleMessages.length - 1; i >= 0; i--) {
        if (parseIsoMs(visibleMessages[i].createdAt) <= propMs + 1000) {
          anchorMsgId = visibleMessages[i].id
          break
        }
      }

      if (anchorMsgId) {
        const list = proposalsAfterMsg.get(anchorMsgId) ?? []
        list.push(prop)
        proposalsAfterMsg.set(anchorMsgId, list)
      } else {
        trailingProposals.push(prop)
      }
    }

    return {
      jobsAfterMsg,
      trailingJobs,
      proposalsByJob,
      proposalsAfterMsg,
      trailingProposals
    }
  }, [verifyJobs, visibleMessages, proposalsMap, proposalJobAnchors])

  const pendingProposals = useMemo(
    () =>
      Object.values(proposalsMap)
        .filter((p) => p.status === 'pending')
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
    [proposalsMap]
  )

  const pendingTotalDiffs = useMemo(
    () => pendingProposals.reduce((acc, p) => acc + p.diffs.length, 0),
    [pendingProposals]
  )

  const handleBatchCommitProposals = async (safeOnly: boolean) => {
    if (batchBusy || pendingProposals.length === 0) return
    setBatchBusy(true)
    try {
      const res = await studioApi.agentCommitProposalsBatch(
        pendingProposals.map((p) => p.id),
        safeOnly
      )
      setProposalsMap((prev) => {
        const next = { ...prev }
        for (const item of res.outcomes) {
          if (item.proposal) next[item.proposal.id] = item.proposal
        }
        return next
      })
      if (res.changedDomains.length > 0) {
        onDomainsChanged(res.changedDomains)
      }
      if (res.appliedProposals > 0) {
        setLastBatchUndo({
          batchGroupId: res.batchGroupId,
          summary: `已批量执行 ${res.appliedProposals} 份方案（共 ${res.totalAppliedItems} 项）`
        })
      }
      onToast(res.message)
    } catch (e) {
      onToast(`批量审批失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBatchBusy(false)
    }
  }

  const handleBatchRejectProposals = async () => {
    if (batchBusy || pendingProposals.length === 0) return
    setBatchBusy(true)
    try {
      let rejectedCount = 0
      for (const p of pendingProposals) {
        try {
          const nextProp = await studioApi.agentRejectProposal(p.id)
          setProposalsMap((prev) => ({ ...prev, [nextProp.id]: nextProp }))
          rejectedCount += 1
        } catch {
          // 忽略单条状态已变化的提案
        }
      }
      onToast(`已放弃 ${rejectedCount} 份待确认方案`)
    } finally {
      setBatchBusy(false)
    }
  }

  const handleRevertLastBatch = async () => {
    if (batchBusy || !lastBatchUndo) return
    setBatchBusy(true)
    try {
      const res = await studioApi.agentRevertProposalsBatch(lastBatchUndo.batchGroupId)
      setProposalsMap((prev) => {
        const next = { ...prev }
        for (const item of res.outcomes) {
          if (item.proposal) next[item.proposal.id] = item.proposal
        }
        return next
      })
      if (res.changedDomains.length > 0) {
        onDomainsChanged(res.changedDomains)
      }
      setLastBatchUndo(null)
      onToast(res.message)
    } catch (e) {
      onToast(`整批撤销失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBatchBusy(false)
    }
  }

  const handleSelectApprovalMode = async (mode: 'balanced' | 'auto_safe' | 'autopilot') => {
    if (mode === 'autopilot') {
      setSessionAutopilot(true)
      onToast('已开启本会话临时托管（切会话或关闭后自动恢复；删除/合并/覆盖手写值仍强制确认）')
      return
    }
    setSessionAutopilot(false)
    if (!policy) return
    try {
      const saved = await studioApi.agentSavePolicy({
        ...policy,
        trustMode: mode
      })
      setPolicy(saved)
      onToast(
        mode === 'auto_safe'
          ? '已切为「安全自动审批」（记账、空值补全与高置信绑定自动生效并可一键撤销）'
          : '已切为「手动确认」模式'
      )
    } catch (e) {
      onToast(`切换审批模式失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const activeApprovalMode: 'balanced' | 'auto_safe' | 'autopilot' = sessionAutopilot
    ? 'autopilot'
    : policy?.trustMode === 'auto_safe' || policy?.trustMode === 'quick_log'
      ? 'auto_safe'
      : 'balanced'

  const floatingStatus: AgentFloatingStatus = activeRunId
    ? 'running'
    : bgFinishState === 'error'
      ? 'error'
      : bgFinishState === 'completed'
        ? 'completed'
        : 'idle'

  const floatingTitle =
    floatingStatus === 'running'
      ? '助手运行中'
      : floatingStatus === 'error'
        ? '执行异常'
        : floatingStatus === 'completed'
          ? '任务完成'
          : 'AI 助手'

  const floatingDetail =
    floatingStatus === 'running'
      ? streamingPendingTool ?? streamingStepText ?? '思考中…'
      : floatingStatus === 'error'
        ? '查看异常'
        : ''

  useLayoutEffect(() => {
    if (!isDrawerVisible) return
    const fromRect = drawerMorphOriginRef.current
    if (!fromRect) return
    drawerMorphOriginRef.current = null
    const el = drawerAsideRef.current
    if (!el || typeof el.animate !== 'function') return
    const reduce =
      document.documentElement.dataset.reduceMotion === 'true' ||
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    if (reduce) return
    const target = el.getBoundingClientRect()
    if (target.width <= 0 || target.height <= 0) return
    const dx = fromRect.left + fromRect.width / 2 - (target.left + target.width / 2)
    const dy = fromRect.top + fromRect.height / 2 - (target.top + target.height / 2)
    const sx = Math.max(0.24, Math.min(1, fromRect.width / target.width))
    const sy = Math.max(0.12, Math.min(1, fromRect.height / target.height))
    const endTransform = drawerMoved
      ? `translate(${drawerOffset.x}px, ${drawerOffset.y}px)`
      : 'translate(0px, 0px)'
    el.animate(
      [
        {
          transform: `translate(${drawerOffset.x + dx}px, ${drawerOffset.y + dy}px) scale(${sx}, ${sy})`,
          opacity: 0.4,
          borderRadius: '28px'
        },
        {
          transform: `${endTransform} scale(1, 1)`,
          opacity: 1,
          borderRadius: '14px'
        }
      ],
      {
        duration: 240,
        easing: 'cubic-bezier(0.22, 1, 0.36, 1)'
      }
    )
  }, [isDrawerVisible, drawerMoved, drawerOffset.x, drawerOffset.y])

  const clearPillMorphOrigin = useCallback(() => {
    setPillMorphOrigin(null)
  }, [])

  if (!open && !floating) return null

  return (
    <>
      {floating && (
        <AgentFloatingPill
          status={floatingStatus}
          title={floatingTitle}
          detail={floatingDetail}
          pendingProposalCount={pendingProposals.length}
          zIndex={drawerZ}
          morphFromRect={pillMorphOrigin}
          onMorphConsumed={clearPillMorphOrigin}
          onBringToFront={bringDrawerToFront}
          onExpand={(originRect) => {
            drawerMorphOriginRef.current = originRect ?? null
            onToggleFloating?.(false)
          }}
          onClose={onClose}
          onStop={activeRunId ? handleStop : undefined}
        />
      )}

      {isDrawerVisible && (
        <aside
        ref={drawerAsideRef}
        className={`agent-drawer ${wideMode ? 'is-wide' : ''}`}
        aria-label="追番喵 AI 智能体"
        onPointerDownCapture={standalone ? undefined : bringDrawerToFront}
        style={
          standalone
            ? {
                position: 'relative',
                inset: 'auto',
                width: '100%',
                maxWidth: '100%',
                height: '100%',
                border: 'none',
                borderRadius: 0,
                boxShadow: 'none'
              }
            : {
                zIndex: drawerZ,
                transform: drawerMoved ? `translate(${drawerOffset.x}px, ${drawerOffset.y}px)` : undefined
              }
        }
      >
        <div
          className="agent-drawer-header"
          {...(standalone ? {} : drawerDragHandlers)}
          onMouseDown={standalone ? startNativeWindowDragFromEvent : undefined}
          title={
            standalone
              ? '按住空白处可拖到桌面任意位置或副屏，双击最大化'
              : '按住空白处可自由拖动 AI 助手窗口，双击归位'
          }
        >
          <div className="agent-drawer-title-row">
            <span className="agent-drag-grip" aria-hidden="true">⋮⋮</span>
            <button
              type="button"
              className={`agent-icon-btn ${showSessions ? 'is-active' : ''}`}
              title="对话管理（搜索/置顶/归档/切换）"
              onClick={() => setShowSessions((v) => !v)}
            >
              ☰{wideMode ? ' 对话' : ''}
            </button>
            <div className="agent-session-heading" title={activeSession?.title ?? '新对话'}>
              <strong>{activeSession?.title ?? '追番喵智能体'}</strong>
              {activeSession && (
                <span className="agent-session-sub">
                  {activeSession.messageCount} 条 ·{' '}
                  {Math.round(
                    (activeSession.totalPromptTokens + activeSession.totalCompletionTokens) / 100
                  ) / 10}
                  k tok
                </span>
              )}
            </div>
          </div>

          <div className="agent-header-actions">
            <button
              type="button"
              className="agent-icon-btn"
              title="新建对话"
              disabled={Boolean(activeRunId)}
              onClick={handleCreateSession}
            >
              +{wideMode ? ' 新对话' : ''}
            </button>
            <button
              type="button"
              className="agent-icon-btn"
              title="管理长期口味与偏好记忆"
              onClick={() => {
                setConfigTab('memory')
                setConfigOpen(true)
              }}
            >
              <IconBrain size={13} />
              {wideMode ? ` 记忆${memoryCount > 0 ? `(${memoryCount})` : ''}` : memoryCount > 0 ? ` ${memoryCount}` : ''}
            </button>
            <button
              type="button"
              className="agent-icon-btn"
              title="模型接入、人设、自动审批、扩展(MCP)与技能配置"
              onClick={() => {
                setConfigTab('models')
                setConfigOpen(true)
              }}
            >
              <IconSliders size={13} />
              {wideMode ? ' 配置' : ''}
            </button>
            {!standalone && onPopOut && (
              <button
                type="button"
                className="agent-icon-btn"
                title="弹出为独立系统窗口，可拖出应用外或副屏"
                onClick={onPopOut}
              >
                独立窗口
              </button>
            )}
            {!standalone && drawerMoved && (
              <button
                type="button"
                className="agent-icon-btn"
                title="恢复停靠到窗口右侧默认位置"
                onClick={resetDrawerOffset}
              >
                归位
              </button>
            )}
            {!standalone && onToggleFloating && (
              <button
                type="button"
                className="agent-icon-btn"
                title="收起为悬浮球（后台保持运行并显示状态）"
                onClick={() => {
                  const el = drawerAsideRef.current
                  if (el) {
                    const r = el.getBoundingClientRect()
                    setPillMorphOrigin({ left: r.left, top: r.top, width: r.width, height: r.height })
                  }
                  onToggleFloating(true)
                }}
              >
                悬浮
              </button>
            )}
            {!standalone && (
              <button
                type="button"
                className="agent-icon-btn"
                title={wideMode ? '收窄为侧边抽屉' : '展开为宽屏工作台'}
                onClick={() => setWideMode((v) => !v)}
              >
                {wideMode ? '◧' : '◨'}
              </button>
            )}
            <button type="button" className="agent-icon-btn" title="关闭窗口 (Ctrl+J)" onClick={onClose}>
              ✕
            </button>
          </div>
        </div>

        {/* 二级工具条：对话人设、长对话前文摘要与导出 */}
        <div className="agent-subbar">
          <div className="agent-subbar-left">
            {activeSession && (
              <StudioSelect
                className="agent-compact-select"
                value={activeSession.personaId}
                options={personaOptions}
                triggerLabel={`人设：${PERSONA_LABELS[activeSession.personaId] ?? activeSession.personaId}`}
                ariaLabel="当前对话人设风格"
                title="当前对话人设风格"
                placement="bottom"
                zIndex={drawerZ + 25}
                minMenuWidth={168}
                maxMenuWidth={220}
                onChange={async (nextPersona) => {
                  await studioApi.agentUpdateSession({
                    sessionId: activeSession.id,
                    personaId: nextPersona
                  })
                  refreshSessions(false)
                }}
              />
            )}
            <button
              type="button"
              className={`agent-mini-pill ${activeSession?.rollingSummary ? 'is-highlight' : ''}`}
              title="查看或编辑本对话的前文摘要"
              onClick={() => setShowSummaryBox((v) => !v)}
            >
              <IconSummary size={12} />{' '}
              {wideMode
                ? activeSession?.rollingSummary
                  ? '已生成摘要'
                  : '前文摘要'
                : activeSession?.rollingSummary
                  ? '有摘要'
                  : '摘要'}
            </button>
          </div>

          <div className="agent-subbar-right">
            <button
              type="button"
              className="agent-mini-btn"
              disabled={!activeSessionId}
              title="查看当前对话的工具调用、耗时和异常"
              onClick={() => setTrajectoryOpen(true)}
            >
              <IconActivity size={12} /> {wideMode ? '运行记录' : '记录'}
            </button>
            <button
              type="button"
              className="agent-mini-btn"
              disabled={compacting || Boolean(activeRunId) || visibleMessages.length < 4}
              title="把较早的聊天整理成一份简明摘要，既不断片又能省额度"
              onClick={handleCompactNow}
            >
              {compacting ? '整理中…' : wideMode ? '整理前文' : '整理'}
            </button>
            <button
              type="button"
              className="agent-mini-btn"
              disabled={visibleMessages.length === 0}
              title="导出当前对话为 Markdown"
              onClick={handleExportSession}
            >
              {wideMode ? '导出 MD' : '导出'}
            </button>
            <button
              type="button"
              className="agent-mini-btn"
              disabled={visibleMessages.length === 0 || Boolean(activeRunId)}
              onClick={handleClearSession}
            >
              清空
            </button>
          </div>
        </div>

        {showSummaryBox && (
          <div className="agent-summary-drawer">
            <div className="agent-summary-head">
              <strong>长对话前文摘要（对话变长时自动携带，可自由编辑）</strong>
              <button
                type="button"
                className="agent-link-btn"
                onClick={() => setShowSummaryBox(false)}
              >
                收起
              </button>
            </div>
            <textarea
              rows={4}
              value={summaryDraft}
              onChange={(e) => setSummaryDraft(e.target.value)}
              placeholder="对话变长后系统会自动生成滚动摘要，你也可以手动写入本对话的长期背景约束…"
            />
            <div className="agent-summary-actions">
              <button type="button" className="btn-primary agent-btn-xs" onClick={handleSaveSummaryManual}>
                保存摘要
              </button>
              <button
                type="button"
                className="btn-ghost agent-btn-xs"
                onClick={() => {
                  setSummaryDraft('')
                }}
              >
                清空文本
              </button>
            </div>
          </div>
        )}

        <div className="agent-drawer-body">
          {/* 左侧可折叠对话管理面板 */}
          {showSessions && (
            <div className="agent-sessions-pane">
              <div className="agent-sessions-search">
                <input
                  value={sessionSearch}
                  onChange={(e) => setSessionSearch(e.target.value)}
                  placeholder="搜标题或聊天内容…"
                />
                <label className="agent-archived-toggle">
                  <input
                    type="checkbox"
                    checked={showArchivedSessions}
                    onChange={(e) => setShowArchivedSessions(e.target.checked)}
                  />
                  <span>含归档</span>
                </label>
              </div>
              <div className="agent-sessions-list">
                {sessions.length === 0 ? (
                  <div className="agent-empty-hint">没有匹配的对话</div>
                ) : (
                  sessions.map((s) => (
                    <div
                      key={s.id}
                      className={`agent-session-item ${s.id === activeSessionId ? 'is-active' : ''}`}
                      onClick={() => {
                        if (activeRunId) return
                        setActiveSessionId(s.id)
                        if (!wideMode) setShowSessions(false)
                      }}
                    >
                      {renamingSessionId === s.id ? (
                        <div
                          className="agent-session-rename-row"
                          onClick={(e) => e.stopPropagation()}
                        >
                          <input
                            autoFocus
                            value={renameText}
                            onChange={(e) => setRenameText(e.target.value)}
                            onKeyDown={async (e) => {
                              if (e.nativeEvent.isComposing || e.keyCode === 229) return
                              if (e.key === 'Enter' && renameText.trim()) {
                                await studioApi.agentUpdateSession({
                                  sessionId: s.id,
                                  title: renameText.trim()
                                })
                                setRenamingSessionId(null)
                                refreshSessions(false)
                              } else if (e.key === 'Escape') {
                                setRenamingSessionId(null)
                              }
                            }}
                          />
                          <button
                            type="button"
                            className="agent-link-btn"
                            onClick={async () => {
                              if (renameText.trim()) {
                                await studioApi.agentUpdateSession({
                                  sessionId: s.id,
                                  title: renameText.trim()
                                })
                              }
                              setRenamingSessionId(null)
                              refreshSessions(false)
                            }}
                          >
                            存
                          </button>
                        </div>
                      ) : (
                        <>
                          <div className="agent-session-item-top">
                            <span className="agent-session-title">
                              {s.pinned ? (
                                <>
                                  <IconPin size={11} style={{ marginRight: 4 }} />
                                </>
                              ) : null}
                              {s.archived ? '[归档] ' : ''}
                              {s.title}
                            </span>
                          </div>
                          <div className="agent-session-item-meta">
                            {(() => {
                              const sessionTimeIso = s.updatedAt || s.createdAt
                              const sessionTimeLabel = formatAgentSessionTime(sessionTimeIso)
                              const sessionFullTime = formatAgentFullTimestamp(sessionTimeIso)
                              return (
                                <span
                                  className="agent-session-meta-text"
                                  title={sessionFullTime ? `最后活跃：${sessionFullTime}` : undefined}
                                >
                                  {s.messageCount} 条{sessionTimeLabel ? ` · ${sessionTimeLabel}` : ''}
                                </span>
                              )
                            })()}
                            <div
                              className="agent-session-item-ops"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <button
                                type="button"
                                title={s.pinned ? '取消置顶' : '置顶'}
                                onClick={async () => {
                                  await studioApi.agentUpdateSession({
                                    sessionId: s.id,
                                    pinned: !s.pinned
                                  })
                                  refreshSessions(false)
                                }}
                              >
                                {s.pinned ? '取消置顶' : '置顶'}
                              </button>
                              <button
                                type="button"
                                title="重命名"
                                onClick={() => {
                                  setRenamingSessionId(s.id)
                                  setRenameText(s.title)
                                }}
                              >
                                改名
                              </button>
                              <button
                                type="button"
                                title={s.archived ? '取消归档' : '归档'}
                                onClick={async () => {
                                  await studioApi.agentUpdateSession({
                                    sessionId: s.id,
                                    archived: !s.archived
                                  })
                                  refreshSessions(false)
                                }}
                              >
                                {s.archived ? '恢复' : '归档'}
                              </button>
                              <button
                                type="button"
                                className="is-danger"
                                title="删除"
                                onClick={() => handleDeleteSession(s)}
                              >
                                删
                              </button>
                            </div>
                          </div>
                        </>
                      )}
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          {/* 主对话流 */}
          <div className="agent-main-pane">
            {profiles.length === 0 && (
              <div className="agent-onboarding-banner">
                <div>
                  <strong>先接入你的大模型即可开聊</strong>
                  <p>
                    支持 DeepSeek、硅基流动、阿里百炼、智谱、Kimi、Gemini、OpenAI 及本地 Ollama（免费免密钥）。密钥仅加密保存在你的电脑本地，不会上传。
                  </p>
                </div>
                <button
                  type="button"
                  className="btn-primary agent-btn-sm"
                  onClick={() => {
                    setConfigTab('models')
                    setConfigOpen(true)
                  }}
                >
                  立即 30 秒配置
                </button>
              </div>
            )}

            <div className="agent-feed-wrap">
              <div
                className="agent-feed"
                ref={feedRef}
                onScroll={handleFeedScroll}
                onWheel={handleFeedWheel}
              >
              {visibleMessages.length === 0 && !activeRunId ? (
                <div className="agent-empty-stage">
                  <div className="agent-empty-hero">
                    <div className="agent-hero-emblem" aria-hidden="true">
                      <IconSpark size={18} />
                    </div>
                    <div className="agent-hero-copy">
                      <h4>追番喵私人智能体</h4>
                      <p className="agent-muted">
                        读库不剧透、检索一次完整入库、批量补全缺封面与骨架资料、分批核实并排对比——所有写库均先预览确认且可撤销。
                      </p>
                    </div>
                  </div>
                  <div className="agent-starter-grid">
                    {STARTER_PROMPTS.map((sp, idx) => (
                      <button
                        key={sp.title}
                        type="button"
                        className="agent-starter-card"
                        style={{ animationDelay: `${idx * 35}ms` }}
                        onClick={() => handleSend(sp.prompt, sp.slash)}
                      >
                        <span className="agent-starter-tag">{sp.tag}</span>
                        <strong>{sp.title}</strong>
                        <span>{sp.desc}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <>
                  {(() => {
                    const renderProposalCard = (p: AgentProposal) => (
                      <AgentProposalCard
                        key={p.id}
                        proposal={p}
                        onUpdated={(nextProp, domains) => {
                          setProposalsMap((prev) => ({ ...prev, [nextProp.id]: nextProp }))
                          if (domains && domains.length > 0) onDomainsChanged(domains)
                        }}
                        onToast={onToast}
                      />
                    )

                    const renderVerifyJobBlock = (job: AgentVerifyJobSummary) => {
                      if (!activeSessionId) return null
                      const jobProps = timelineAnchors.proposalsByJob.get(job.id) ?? []
                      return (
                        <React.Fragment key={job.id}>
                          <AgentVerifyJobCard
                            sessionId={activeSessionId}
                            summary={job}
                            onChanged={() => refreshVerifyJobs(activeSessionId)}
                            onProposalCreated={(prop) => {
                              setProposalsMap((prev) => ({ ...prev, [prop.id]: prop }))
                              setProposalJobAnchors((prev) => ({ ...prev, [prop.id]: job.id }))
                            }}
                            onToast={onToast}
                          />
                          {jobProps.length > 0 && (
                            <div
                              style={{
                                display: 'flex',
                                flexDirection: 'column',
                                gap: 8,
                                padding: '4px 12px 6px'
                              }}
                            >
                              {jobProps.map(renderProposalCard)}
                            </div>
                          )}
                        </React.Fragment>
                      )
                    }

                    let lastDayKey: string | null = null

                    return (
                      <>
                        {visibleMessages.map((msg) => {
                          const pidList =
                            msg.proposalIds && msg.proposalIds.length > 0
                              ? msg.proposalIds
                              : msg.proposalId
                                ? [msg.proposalId]
                                : []
                          const msgProposals = pidList
                            .map((id) => proposalsMap[id])
                            .filter((p): p is AgentProposal => Boolean(p))
                          const msgJobs = timelineAnchors.jobsAfterMsg.get(msg.id) ?? []
                          const msgOrphanProps = timelineAnchors.proposalsAfterMsg.get(msg.id) ?? []
                          const dayKey = getLocalDayKey(msg.createdAt)
                          const showDateDivider = Boolean(dayKey && dayKey !== lastDayKey)
                          if (dayKey) lastDayKey = dayKey
                          const dateDividerLabel = showDateDivider
                            ? formatAgentDateDivider(msg.createdAt)
                            : ''

                          return (
                            <React.Fragment key={msg.id}>
                              {showDateDivider && dateDividerLabel && (
                                <div className="agent-date-divider" role="separator">
                                  <span>{dateDividerLabel}</span>
                                </div>
                              )}
                              <AgentMessageItem
                                message={msg}
                                proposal={msgProposals[0] ?? null}
                                proposals={msgProposals}
                                isLastAssistant={msg.id === lastAssistantId}
                                busy={Boolean(activeRunId)}
                                onProposalUpdated={(nextProp, domains) => {
                                  setProposalsMap((prev) => ({ ...prev, [nextProp.id]: nextProp }))
                                  if (domains && domains.length > 0) onDomainsChanged(domains)
                                }}
                                onUndoSettings={(undo) => {
                                  onApplySettingsPatch(undo.revertPatch as Partial<DesktopSettings>)
                                  onToast('已恢复修改前的桌面设置')
                                }}
                                onDeleteMemory={async (id) => {
                                  await studioApi.agentDeleteMemory(id)
                                  refreshMetadata()
                                  onToast('已撤销该条记忆')
                                }}
                                onOpenMemoryTab={() => {
                                  setConfigTab('memory')
                                  setConfigOpen(true)
                                }}
                                onOpenWork={onOpenWork}
                                onQuickFix={handleQuickFix}
                                onRegenerate={handleRegenerateMessage}
                                onEditResend={handleEditResend}
                                onForkFrom={handleForkFromMessage}
                                onSwitchVersion={handleSwitchVersion}
                                onToast={onToast}
                              />

                              {msgJobs.length > 0 && (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 4 }}>
                                  {msgJobs.map(renderVerifyJobBlock)}
                                </div>
                              )}

                              {msgOrphanProps.length > 0 && (
                                <div
                                  style={{
                                    display: 'flex',
                                    flexDirection: 'column',
                                    gap: 8,
                                    padding: '4px 12px 6px'
                                  }}
                                >
                                  {msgOrphanProps.map(renderProposalCard)}
                                </div>
                              )}
                            </React.Fragment>
                          )
                        })}

                        {activeSessionId && timelineAnchors.trailingJobs.length > 0 && (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8 }}>
                            {timelineAnchors.trailingJobs.map(renderVerifyJobBlock)}
                          </div>
                        )}

                        {timelineAnchors.trailingProposals.length > 0 && (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 12px' }}>
                            {timelineAnchors.trailingProposals.map(renderProposalCard)}
                          </div>
                        )}
                      </>
                    )
                  })()}

                  {activeRunId && (
                    <div className="agent-msg is-assistant is-streaming">
                      <div className="agent-msg-bubble">
                        <div className="agent-stream-status">
                          <span className="agent-pulse-dot" />
                          <span>{streamingPendingTool ?? streamingStepText ?? '思考中…'}</span>
                        </div>
                        {streamingReasoning && (
                          <div className="agent-reasoning-body is-live">{streamingReasoning}</div>
                        )}
                        {streamingTraces.length > 0 && (
                          <div className="agent-trace-live-strip">
                            {streamingTraces.map((tr) => (
                              <span key={tr.id} className={`agent-ui-pill ${tr.ok ? '' : 'is-fail'}`}>
                                {tr.ok ? '✓' : '!'} {tr.label}（{tr.summary}）
                              </span>
                            ))}
                          </div>
                        )}
                        {streamingUiActions.length > 0 && (
                          <div className="agent-ui-action-strip">
                            {streamingUiActions.map((act, i) => (
                              <span key={i} className="agent-ui-pill">
                                <IconBolt size={12} /> {act.label}
                              </span>
                            ))}
                          </div>
                        )}
                        {streamingMemEvents.length > 0 && (
                          <div className="agent-ui-action-strip">
                            {streamingMemEvents.map((me, i) => (
                              <span key={i} className="agent-ui-pill">
                                <IconBrain size={12} /> 已记住：{me.content.slice(0, 22)}
                              </span>
                            ))}
                          </div>
                        )}
                        {streamingSettingsUndo && (
                          <div className="agent-ui-pill">
                            <IconSliders size={12} /> {streamingSettingsUndo.summary}
                          </div>
                        )}
                        {streamingContent && (
                          <div className="agent-stream-text">{streamingContent}</div>
                        )}
                      </div>
                    </div>
                  )}
                </>
              )}
              </div>
              {showScrollToBottom && (
                <button
                  type="button"
                  className={`agent-scroll-bottom-btn ${hasUnreadBelow ? 'has-unread' : ''}`}
                  title="回到对话底部查看最新消息"
                  onClick={() => scrollToBottom(true)}
                >
                  <span>{hasUnreadBelow ? '最新回复' : '回到底部'}</span>
                  <svg
                    width="12"
                    height="12"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.3"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
              )}
            </div>

            {/* 多提案一键批量审批总控条 / 整批撤销条 */}
            {pendingProposals.length >= 2 && (
              <div className="agent-batch-bar">
                <div className="agent-batch-bar-info">
                  <strong>待确认 {pendingProposals.length} 份方案</strong>
                  <span>共 {pendingTotalDiffs} 项改动</span>
                </div>
                <div className="agent-batch-bar-actions">
                  <button
                    type="button"
                    className="btn-primary agent-btn-sm"
                    disabled={batchBusy}
                    onClick={() => handleBatchCommitProposals(false)}
                  >
                    {batchBusy ? '正在审批…' : '全部确认执行'}
                  </button>
                  <button
                    type="button"
                    className="agent-mini-btn"
                    disabled={batchBusy}
                    title="仅执行可撤销的低风险方案，自动跳过含删除或合并的方案"
                    onClick={() => handleBatchCommitProposals(true)}
                  >
                    仅确认安全项
                  </button>
                  <button
                    type="button"
                    className="agent-mini-btn"
                    disabled={batchBusy}
                    onClick={handleBatchRejectProposals}
                  >
                    全部放弃
                  </button>
                </div>
              </div>
            )}
            {pendingProposals.length < 2 && lastBatchUndo && (
              <div className="agent-batch-bar is-undo">
                <div className="agent-batch-bar-info">
                  <span>{lastBatchUndo.summary}</span>
                </div>
                <div className="agent-batch-bar-actions">
                  <button
                    type="button"
                    className="agent-mini-btn"
                    disabled={batchBusy}
                    onClick={handleRevertLastBatch}
                  >
                    {batchBusy ? '撤销中…' : '↶ 撤销整批'}
                  </button>
                  <button
                    type="button"
                    className="agent-mini-btn"
                    onClick={() => setLastBatchUndo(null)}
                  >
                    知道了
                  </button>
                </div>
              </div>
            )}

            {/* 输入与上下文区 */}
            <div className="agent-composer">
              {isCrossProviderSwitch && activeSessionId && activeProfile && (
                <ProviderSwitchNotice
                  key={JSON.stringify([activeSessionId, activeProfile.id])}
                  profileName={activeProfile.name}
                  onCreateSession={handleCreateSession}
                />
              )}

              {(contextChip?.workTitle || (filteredItems && filteredItems.length > 0)) && (
                <div className="agent-context-bar" style={{ flexWrap: 'wrap', gap: 8 }}>
                  {contextChip?.workTitle && (
                    <>
                      <label className="agent-context-chip">
                        <input
                          type="checkbox"
                          checked={attachContext}
                          onChange={(e) => setAttachContext(e.target.checked)}
                        />
                        <span>
                          当前关注：《{contextChip.workTitle}》
                          {contextChip.mediaFormat ? ` · ${contextChip.mediaFormat}` : ''}
                          {contextChip.progressPrimary != null
                            ? `（进度 ${contextChip.progressPrimary}${
                                contextChip.progressTotal ? `/${contextChip.progressTotal}` : ''
                              }${contextChip.progressUnit ? ` ${contextChip.progressUnit}` : ''}）`
                            : ''}
                        </span>
                      </label>
                      {attachContext && (
                        <label className="agent-spoiler-toggle">
                          <input
                            type="checkbox"
                            checked={antiSpoiler}
                            onChange={(e) => setAntiSpoiler(e.target.checked)}
                          />
                          <span>
                            <IconShield size={12} /> 防剧透锁
                          </span>
                        </label>
                      )}
                    </>
                  )}
                  {filteredItems && filteredItems.length > 0 && (
                    <label
                      className="agent-context-chip"
                      title="开启后将把你当前筛选出的藏品清单快照直接附带给助手，方便它直接批量整理、核实或筛选"
                    >
                      <input
                        type="checkbox"
                        checked={attachFilterSnapshot}
                        onChange={(e) => setAttachFilterSnapshot(e.target.checked)}
                      />
                      <span>使用当前筛选结果（{filteredItems.length} 部）</span>
                    </label>
                  )}
                </div>
              )}

              {slashMatches.length > 0 && (
                <div className="agent-slash-popup">
                  {slashMatches.map((cmd, idx) => (
                    <button
                      key={cmd.id}
                      type="button"
                      className={`agent-slash-item ${idx === slashIndex ? 'is-active' : ''}`}
                      onClick={() => {
                        setDraft(`${cmd.command} `)
                        setSlashIndex(0)
                      }}
                    >
                      <strong>{cmd.command}</strong>
                      <span>{cmd.label}</span>
                      <small>{cmd.description}</small>
                    </button>
                  ))}
                </div>
              )}

              <textarea
                className="agent-input"
                rows={3}
                value={draft}
                onChange={(e) => {
                  setDraft(e.target.value)
                  setSlashIndex(0)
                }}
                onCompositionStart={() => {
                  imeComposingRef.current = true
                }}
                onCompositionEnd={() => {
                  window.setTimeout(() => {
                    imeComposingRef.current = false
                  }, 30)
                }}
                onKeyDown={(e) => {
                  if (imeComposingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return
                  if (slashMatches.length > 0) {
                    if (e.key === 'ArrowDown') {
                      e.preventDefault()
                      setSlashIndex((i) => (i + 1) % slashMatches.length)
                      return
                    }
                    if (e.key === 'ArrowUp') {
                      e.preventDefault()
                      setSlashIndex((i) => (i - 1 + slashMatches.length) % slashMatches.length)
                      return
                    }
                    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
                      e.preventDefault()
                      const picked = slashMatches[slashIndex] ?? slashMatches[0]
                      if (picked) setDraft(`${picked.command} `)
                      return
                    }
                  }
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault()
                    handleSend()
                  }
                }}
                placeholder="输入问题，或输入 / 呼出快捷工作流（Enter 发送，Shift+Enter 换行）"
              />

              <div className="agent-composer-footer">
                <div className="agent-composer-left">
                  <StudioSelect
                    className="agent-profile-select"
                    value={selectedProfileId}
                    options={profileSelectOptions}
                    placeholder="未配置模型"
                    emptyText="尚未接入大模型档案"
                    triggerLabel={
                      activeProfile
                        ? wideMode
                          ? `${activeProfile.name} · ${activeProfile.model}`
                          : activeProfile.name
                        : '未配置模型'
                    }
                    ariaLabel="选择对话模型"
                    title={
                      activeProfile
                        ? `${activeProfile.name} (${activeProfile.model})`
                        : '点击接入大模型'
                    }
                    disabled={Boolean(activeRunId)}
                    placement="top"
                    zIndex={drawerZ + 25}
                    minMenuWidth={248}
                    maxMenuWidth={330}
                    onChange={(nextId) => setSelectedProfileId(nextId)}
                    footerAction={{
                      label: '+ 接入或管理模型',
                      onClick: () => {
                        setConfigTab('models')
                        setConfigOpen(true)
                      }
                    }}
                  />
                  <button
                    type="button"
                    className="agent-mini-btn"
                    title="输入 / 查看全部快捷指令"
                    onClick={() => setDraft('/')}
                  >
                    / 指令
                  </button>
                  <div className="agent-approval-pills" role="group" aria-label="提案审批模式">
                    <button
                      type="button"
                      className={`agent-approval-pill ${activeApprovalMode === 'balanced' ? 'is-active' : ''}`}
                      title="手动确认：写库先展示预览卡片，多提案支持一键批量确认"
                      onClick={() => handleSelectApprovalMode('balanced')}
                    >
                      手动
                    </button>
                    <button
                      type="button"
                      className={`agent-approval-pill ${activeApprovalMode === 'auto_safe' ? 'is-active' : ''}`}
                      title="安全自动：记账、空值补全、高置信绑定自动生效并可一键撤销；删除与合并仍强制确认"
                      onClick={() => handleSelectApprovalMode('auto_safe')}
                    >
                      安全自动
                    </button>
                    <button
                      type="button"
                      className={`agent-approval-pill ${activeApprovalMode === 'autopilot' ? 'is-active is-autopilot' : ''}`}
                      title="本会话临时托管：仅本会话放行所有可撤销非破坏性改动，切会话自动关闭"
                      onClick={() => handleSelectApprovalMode('autopilot')}
                    >
                      托管
                    </button>
                  </div>
                </div>

                {activeRunId ? (
                  <button type="button" className="btn-danger agent-btn-sm" onClick={handleStop}>
                    ■ 停止生成
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn-primary agent-btn-sm"
                    disabled={!draft.trim()}
                    onClick={() => handleSend()}
                  >
                    发送 ↵
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
        </aside>
      )}

      <AgentConfigModal
        open={configOpen}
        initialTab={configTab}
        onClose={() => setConfigOpen(false)}
        onChanged={() => {
          refreshMetadata()
          refreshSessions(false)
        }}
        onToast={onToast}
      />

      <AgentTrajectoryModal
        open={trajectoryOpen}
        sessionId={activeSessionId}
        sessionTitle={activeSession?.title ?? '当前对话'}
        onClose={() => setTrajectoryOpen(false)}
        onToast={onToast}
      />
    </>
  )
}
