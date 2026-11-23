import React, { useEffect, useMemo, useState } from 'react'
import { studioApi } from '../../api'
import { useStudioDialog } from '../StudioDialog'
import { StudioSelect } from '../StudioSelect'
import { useDraggablePanel, useFloatingLayer } from '../../utils/floatingLayer'
import {
  IconClipboard,
  IconPlug,
  IconSearch,
  IconSpark,
  IconWarning
} from './AgentIcons'
import type {
  AgentDiagnosticError,
  AgentMcpServerConfig,
  AgentMcpTestResult,
  AgentMemory,
  AgentMemoryDraft,
  AgentPolicyConfig,
  AgentProbeResult,
  AgentProfile,
  AgentProviderPreset,
  AgentRemoteModelInfo,
  AgentSlashCommand
} from '../../types'

export type AgentConfigTab = 'models' | 'policy' | 'memory' | 'commands' | 'mcp'

interface AgentConfigModalProps {
  open: boolean
  initialTab?: AgentConfigTab
  onClose: () => void
  onChanged: () => void
  onToast?: (msg: string) => void
}

const REGION_TABS: { id: 'cn' | 'global' | 'local' | 'custom'; label: string }[] = [
  { id: 'cn', label: '国内直连（推荐）' },
  { id: 'global', label: '国际与聚合（需代理）' },
  { id: 'local', label: '本地免 Key' },
  { id: 'custom', label: '自定义中转' }
]

const MODEL_ROLE_LABELS: Record<string, string> = {
  'deepseek-flash': '主力首选',
  'deepseek-v4-pro': '复杂对照',
  'qwen3.8-flash': '常用推荐',
  'qwen3.7-flash': '轻量低价',
  'ZHIPU/GLM-5.3-Flash': '托管低价',
  'qwen3.7-max': '进阶',
  'glm-5.3-flash': '常用推荐',
  'glm-5.3': '进阶',
  'doubao-seed-2-1-lite-260915': '批量优先',
  hy3: '低价补充',
  'step-3.7-flash': '性价比',
  'gemini-3.8-flash': '常用首选',
  'gemini-3.5-flash-lite': '轻量查库',
  'google/gemini-3.8-flash': '常用首选',
  'google/gemini-3.5-flash-lite': '轻量查库',
  'minimax/minimax-m3.1-flash': '低价网关',
  'stepfun/step-3.7-flash': '低价网关',
  'mistral-large-4': '中价备选'
}

const PERSONA_CARDS: { id: string; name: string; desc: string }[] = [
  { id: 'curator', name: '温和策展官', desc: '条理清晰、通俗自然，默认不输出晦涩编码，适合日常整理与聊番' },
  { id: 'otaku_friend', name: '二次元老友', desc: '像多年一起追番的朋友一样轻松聊天、接梗吐槽，查数据时依然靠谱' },
  { id: 'efficient', name: '极简效率派', desc: '直奔重点、分点/表格输出，不寒暄不废话' },
  { id: 'spoiler_guard', name: '防剧透伴看官', desc: '绝不剧透后续情节，严格按你当前看到的集数一起聊伏笔和演出' },
  { id: 'diagnostic', name: '专业诊断模式', desc: '面向排障与测试：完整输出作品 ID、提案号、字段名与数据异常细节' },
  { id: 'custom', name: '自定义人设', desc: '完全由你来设定助手的性格、称呼方式、语气口癖与聊天习惯' }
]

const CUSTOM_PERSONA_TEMPLATES: { label: string; text: string }[] = [
  {
    label: '毒舌老二次元',
    text: '称呼我为老铁。你是一个阅番无数的资深老二次元，点评作品一针见血、带点幽默吐槽，不爱说假大空的官话，但推荐好番时非常真诚。'
  },
  {
    label: '温柔陪聊搭子',
    text: '语气温柔亲切、有共鸣感，像一起深夜补番的好朋友。每次聊剧情先顺着我的感受展开，回答自然口语化，不要像说明书一样生硬。'
  },
  {
    label: '分镜/演出硬核评委',
    text: '聊动画和漫画时重点分析作画、分镜节奏、配乐与剧本结构，打分和评价标准偏专业理性，尽量给出具体桥段佐证。'
  },
  {
    label: '追番喵贴心管家',
    text: '自称「小喵」，语气轻快友善、简洁礼貌，不卖萌过头。帮我管追番进度、整理系列和推荐新番时条理清晰。'
  }
]

const MEMORY_CATEGORIES: { id: string; label: string }[] = [
  { id: 'preference', label: '口味偏好' },
  { id: 'rating_rule', label: '评分标准' },
  { id: 'spoiler_rule', label: '防剧透约定' },
  { id: 'style', label: '交流习惯' },
  { id: 'avoid', label: '雷区避坑' },
  { id: 'profile', label: '个人背景' }
]

const TOOL_SCOPE_OPTIONS: { id: string; label: string }[] = [
  { id: 'read', label: '查库与手册' },
  { id: 'online', label: '在线搜番搜角色' },
  { id: 'ui', label: '操控界面与设置' },
  { id: 'write', label: '数据整理提案' },
  { id: 'memory', label: '记录偏好记忆' }
]

export const AgentConfigModal: React.FC<AgentConfigModalProps> = ({
  open,
  initialTab = 'models',
  onClose,
  onChanged,
  onToast
}) => {
  const dialog = useStudioDialog()
  const { zIndex: modalZ, bringToFront } = useFloatingLayer(open, 'modal')
  const {
    offset: modalOffset,
    isMoved: modalMoved,
    resetOffset: resetModalOffset,
    dragHandlers: modalDragHandlers
  } = useDraggablePanel({ open, resetOnOpen: true, minY: -220 })
  const [tab, setTab] = useState<AgentConfigTab>(initialTab)
  const [providers, setProviders] = useState<AgentProviderPreset[]>([])
  const [profiles, setProfiles] = useState<AgentProfile[]>([])
  const [policy, setPolicy] = useState<AgentPolicyConfig | null>(null)
  const [savedPolicyJson, setSavedPolicyJson] = useState<string>('')
  const [memories, setMemories] = useState<AgentMemory[]>([])
  const [commands, setCommands] = useState<AgentSlashCommand[]>([])

  // Model editor state
  const [regionTab, setRegionTab] = useState<'cn' | 'global' | 'local' | 'custom'>('cn')
  const [editingProfileId, setEditingProfileId] = useState<string | null>(null)
  const [providerId, setProviderId] = useState('deepseek')
  const [profileName, setProfileName] = useState('DeepSeek 主力')
  const [baseUrl, setBaseUrl] = useState('https://api.deepseek.com/v1')
  const [apiKey, setApiKey] = useState('')
  const [hasSavedKey, setHasSavedKey] = useState(false)
  const [savedKeyHint, setSavedKeyHint] = useState<string | null>(null)
  const [modelName, setModelName] = useState('deepseek-flash')
  const [temperature, setTemperature] = useState(0.4)
  const [maxSteps, setMaxSteps] = useState(8)
  const [maxTurnTokens, setMaxTurnTokens] = useState(60000)
  const [supportsTools, setSupportsTools] = useState(true)
  const [supportsReasoning, setSupportsReasoning] = useState(false)
  const [enableWebSearch, setEnableWebSearch] = useState(false)
  const [isDefault, setIsDefault] = useState(true)

  const [fetchedModels, setFetchedModels] = useState<AgentRemoteModelInfo[]>([])
  const [modelFilter, setModelFilter] = useState('')
  const [fetchingModels, setFetchingModels] = useState(false)
  const [suggestedUrl, setSuggestedUrl] = useState<string | null>(null)
  const [probing, setProbing] = useState(false)
  const [probeResult, setProbeResult] = useState<AgentProbeResult | null>(null)
  const [modelErr, setModelErr] = useState<AgentDiagnosticError | null>(null)
  const [savingModel, setSavingModel] = useState(false)

  // Memory state
  const [memFilterCat, setMemFilterCat] = useState<string>('all')
  const [memSearch, setMemSearch] = useState('')
  const [newMemCat, setNewMemCat] = useState('preference')
  const [newMemTier, setNewMemTier] = useState<'core' | 'archive'>('core')
  const [newMemContent, setNewMemContent] = useState('')
  const [editingMemId, setEditingMemId] = useState<string | null>(null)
  const [drafts, setDrafts] = useState<AgentMemoryDraft[]>([])
  const [selectedDrafts, setSelectedDrafts] = useState<Set<number>>(new Set())

  // Slash command / Skill editor state
  const [cmdId, setCmdId] = useState<string | null>(null)
  const [cmdName, setCmdName] = useState('')
  const [cmdLabel, setCmdLabel] = useState('')
  const [cmdDesc, setCmdDesc] = useState('')
  const [cmdTemplate, setCmdTemplate] = useState('')
  const [cmdGuide, setCmdGuide] = useState('')
  const [cmdScope, setCmdScope] = useState<string[]>(['read'])
  const [skillImportText, setSkillImportText] = useState('')
  const [showSkillImport, setShowSkillImport] = useState(false)

  // 外部扩展插件（MCP）状态
  const [mcpServers, setMcpServers] = useState<AgentMcpServerConfig[]>([])
  const [mcpEditId, setMcpEditId] = useState<string | null>(null)
  const [mcpName, setMcpName] = useState('')
  const [mcpTransport, setMcpTransport] = useState<'http' | 'stdio'>('http')
  const [mcpEndpoint, setMcpEndpoint] = useState('')
  const [mcpArgsText, setMcpArgsText] = useState('')
  const [mcpToken, setMcpToken] = useState('')
  const [mcpTestingId, setMcpTestingId] = useState<string | null>(null)
  const [mcpTestMap, setMcpTestMap] = useState<Record<string, AgentMcpTestResult>>({})
  const [showMcpJsonImport, setShowMcpJsonImport] = useState(false)
  const [mcpImportJson, setMcpImportJson] = useState('')

  useEffect(() => {
    if (!open) return
    setTab(initialTab)
    loadAll(true)
  }, [open, initialTab])

  const resetFormForNewProfile = (
    provList: AgentProviderPreset[],
    existingProfiles: AgentProfile[],
    preferredPreset?: AgentProviderPreset
  ) => {
    const preset =
      preferredPreset ?? provList.find((p) => p.id === 'deepseek') ?? provList[0] ?? null
    setEditingProfileId(null)
    setApiKey('')
    setHasSavedKey(false)
    setSavedKeyHint(null)
    setProbeResult(null)
    setModelErr(null)
    setSuggestedUrl(null)
    setFetchedModels([])
    setIsDefault(existingProfiles.length === 0)
    setTemperature(0.4)
    setMaxSteps(8)
    setMaxTurnTokens(60000)
    setSupportsTools(true)
    setSupportsReasoning(false)
    setEnableWebSearch(false)
    if (preset) {
      setProviderId(preset.id)
      setRegionTab(preset.region)
      setProfileName(preset.name)
      setBaseUrl(preset.baseUrl)
      setModelName(preset.defaultModel)
    } else {
      setProviderId('deepseek')
      setRegionTab('cn')
      setProfileName('')
      setBaseUrl('')
      setModelName('')
    }
  }

  const loadAll = async (resetToNew = false) => {
    try {
      const [prov, profs, pol, mems, cmds, mcps] = await Promise.all([
        studioApi.agentListProviders(),
        studioApi.agentListProfiles(),
        studioApi.agentGetPolicy(),
        studioApi.agentListMemories(),
        studioApi.agentListCommands(),
        studioApi.agentMcpList()
      ])
      setProviders(prov)
      setProfiles(profs)
      setPolicy(pol)
      setSavedPolicyJson(JSON.stringify(pol))
      setMemories(mems)
      setCommands(cmds)
      setMcpServers(mcps)
      if (resetToNew) {
        resetFormForNewProfile(prov, profs)
      }
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const loadProfileIntoForm = (p: AgentProfile, provList: AgentProviderPreset[] = providers) => {
    setEditingProfileId(p.id)
    setProviderId(p.providerId)
    const matched = provList.find((x) => x.id === p.providerId)
    if (matched) setRegionTab(matched.region)
    setProfileName(p.name)
    setBaseUrl(p.baseUrl)
    setApiKey('')
    setHasSavedKey(p.hasApiKey)
    setSavedKeyHint(p.apiKeyHint ?? null)
    setModelName(p.model)
    setTemperature(p.temperature)
    setMaxSteps(p.maxSteps)
    setMaxTurnTokens(p.maxTurnTokens)
    setSupportsTools(p.supportsTools)
    setSupportsReasoning(p.supportsReasoning)
    setEnableWebSearch(Boolean(p.enableWebSearch))
    setIsDefault(p.isDefault)
    setProbeResult(p.lastProbe ?? null)
    setModelErr(null)
    setSuggestedUrl(null)
    setFetchedModels([])
  }

  const startNewProfile = (preferredPreset?: AgentProviderPreset) => {
    resetFormForNewProfile(providers, profiles, preferredPreset)
  }

  const editingProfile = useMemo(
    () => (editingProfileId ? profiles.find((p) => p.id === editingProfileId) ?? null : null),
    [editingProfileId, profiles]
  )

  const applyPreset = async (preset: AgentProviderPreset) => {
    if (editingProfileId && preset.id !== providerId) {
      const targetName = editingProfile?.name ?? (profileName || '当前档案')
      const switchToNew = await dialog.confirm({
        title: `要接入新的「${preset.name}」模型吗？`,
        message: `你当前正在修改已有档案「${targetName}」。为防止误覆盖原有配置与密钥，建议切换为「接入新模型」模式。`,
        detail: '选「作为新模型接入」会保留原档案不动，并用新服务商信息新建一条档案；选「留在原档案」将取消本次切换。',
        confirmText: '作为新模型接入',
        cancelText: '留在原档案',
        tone: 'warning'
      })
      if (!switchToNew) return
      startNewProfile(preset)
      onToast?.(`已切换为「接入新模型」并填入 ${preset.name} 预设`)
      return
    }
    setProviderId(preset.id)
    setRegionTab(preset.region)
    if (preset.baseUrl) setBaseUrl(preset.baseUrl)
    if (preset.defaultModel) setModelName(preset.defaultModel)
    if (!editingProfileId) {
      setProfileName(preset.name)
    }
    if (!preset.webSearchMode || preset.webSearchMode === 'none') {
      setEnableWebSearch(false)
    }
    setModelErr(null)
    setSuggestedUrl(null)
    setProbeResult(null)
    setFetchedModels([])
  }

  const handleSaveAsNewProfile = () => {
    const baseName = profileName.trim() || currentPreset?.name || '新模型'
    setEditingProfileId(null)
    setProfileName(baseName.endsWith('副本') ? baseName : `${baseName} 副本`)
    setHasSavedKey(false)
    setSavedKeyHint(null)
    setIsDefault(false)
    setProbeResult(null)
    setModelErr(null)
    if (!currentPreset?.isLocal && !apiKey.trim()) {
      onToast?.('已切换为「接入新模型」并保留当前参数，请重新填写 API Key 后保存')
    } else {
      onToast?.('已切换为「接入新模型」并保留当前参数，可直接点击「新增并保存模型」')
    }
  }

  const currentPreset = useMemo(
    () => providers.find((p) => p.id === providerId) ?? null,
    [providers, providerId]
  )

  const regionProviders = useMemo(
    () => providers.filter((p) => p.region === regionTab),
    [providers, regionTab]
  )

  const filteredRemoteModels = useMemo(() => {
    const q = modelFilter.trim().toLowerCase()
    const list = q
      ? fetchedModels.filter((m) => m.id.toLowerCase().includes(q))
      : fetchedModels
    return list.slice(0, 60)
  }, [fetchedModels, modelFilter])

  const handleFetchModels = async () => {
    setFetchingModels(true)
    setModelErr(null)
    setSuggestedUrl(null)
    try {
      const res = await studioApi.agentFetchModels(baseUrl, apiKey || null, editingProfileId)
      setBaseUrl(res.suggestedBaseUrl ?? res.normalizedBaseUrl)
      if (res.suggestedBaseUrl) {
        setSuggestedUrl(res.suggestedBaseUrl)
        onToast?.(`已自动检测到兼容地址 ${res.suggestedBaseUrl} 并拉取 ${res.models.length} 个对话模型`)
      } else {
        onToast?.(
          `已获取 ${res.models.length} 个对话模型` +
            (res.filteredOutCount > 0 ? `（已自动过滤 ${res.filteredOutCount} 个非对话模型）` : '')
        )
      }
      setFetchedModels(res.models)
      if (!modelName.trim() && res.models.length > 0) {
        setModelName(res.models[0].id)
      }
    } catch (e) {
      const raw = e instanceof Error ? e.message : String(e)
      try {
        const parsed = JSON.parse(raw) as AgentDiagnosticError
        setModelErr(parsed)
        if (parsed.suggestedBaseUrl) setSuggestedUrl(parsed.suggestedBaseUrl)
      } catch {
        setModelErr({
          code: 'FETCH_MODELS_FAILED',
          title: '拉取模型列表失败',
          explanation: raw,
          suggestion: '部分服务商（如火山方舟或私有中转）可能不开放 /models 接口，你可以直接手动填写模型名。',
          technicalDetail: raw
        })
      }
    } finally {
      setFetchingModels(false)
    }
  }

  const handleProbe = async () => {
    setProbing(true)
    setModelErr(null)
    try {
      const res = await studioApi.agentProbeProfile(
        baseUrl,
        modelName,
        apiKey || null,
        editingProfileId
      )
      setProbeResult(res)
      setBaseUrl(res.normalizedBaseUrl)
      if (res.suggestedBaseUrl) setSuggestedUrl(res.suggestedBaseUrl)
      if (res.ok) {
        setSupportsTools(res.supportsTools)
        setSupportsReasoning(res.supportsReasoning)
        onToast?.(
          res.supportsTools
            ? '三段体检全部通过：支持完整智能体工具调用！'
            : '连通正常，但该模型不支持工具调用（已自动设为纯对话模式）'
        )
      } else if (res.diagnostic) {
        setModelErr(res.diagnostic)
      }
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setProbing(false)
    }
  }

  const handleSaveProfile = async () => {
    setSavingModel(true)
    setModelErr(null)
    try {
      const supportsNativeWeb = Boolean(
        currentPreset?.webSearchMode && currentPreset.webSearchMode !== 'none'
      )
      const saved = await studioApi.agentSaveProfile({
        id: editingProfileId,
        name: profileName,
        providerId,
        baseUrl,
        model: modelName,
        apiKey: apiKey.trim() ? apiKey.trim() : null,
        temperature,
        maxSteps,
        maxTurnTokens,
        supportsTools,
        supportsReasoning,
        enableWebSearch: supportsNativeWeb ? enableWebSearch : false,
        lastProbe: probeResult,
        isDefault
      })
      const nextList = await studioApi.agentListProfiles()
      setProfiles(nextList)
      loadProfileIntoForm(saved)
      onChanged()
      onToast?.(`模型档案「${saved.name}」已加密保存`)
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setSavingModel(false)
    }
  }

  const handleDeleteProfile = async (p: AgentProfile) => {
    const ok = await dialog.confirm({
      title: `删除模型档案「${p.name}」？`,
      message: '将同时清除本机加密保存的该档案密钥，不影响已有历史对话记录。',
      confirmText: '确认删除',
      tone: 'danger'
    })
    if (!ok) return
    await studioApi.agentDeleteProfile(p.id)
    const nextList = await studioApi.agentListProfiles()
    setProfiles(nextList)
    if (editingProfileId === p.id || nextList.length === 0) {
      resetFormForNewProfile(providers, nextList)
    }
    onChanged()
    onToast?.('已删除模型档案')
  }

  const handleSavePolicy = async (next: AgentPolicyConfig) => {
    setPolicy(next)
    try {
      const saved = await studioApi.agentSavePolicy(next)
      setPolicy(saved)
      setSavedPolicyJson(JSON.stringify(saved))
      onChanged()
      onToast?.('智能体策略与人设已保存')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleSaveMemory = async () => {
    if (!newMemContent.trim()) return
    try {
      await studioApi.agentUpsertMemory(
        newMemCat,
        newMemContent.trim(),
        true,
        editingMemId,
        newMemTier
      )
      setNewMemContent('')
      setEditingMemId(null)
      setMemories(await studioApi.agentListMemories())
      onChanged()
      onToast?.(editingMemId ? '已更新记忆' : '已添加到长期记忆')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleDraftMemories = async () => {
    try {
      const list = await studioApi.agentDraftMemoriesFromLibrary()
      if (list.length === 0) {
        onToast?.('藏品库评分或标签数据还比较少，暂未能提炼出画像草稿')
        return
      }
      setDrafts(list)
      setSelectedDrafts(new Set(list.map((_, i) => i)))
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleImportSelectedDrafts = async () => {
    let added = 0
    for (let i = 0; i < drafts.length; i++) {
      if (!selectedDrafts.has(i)) continue
      await studioApi.agentUpsertMemory(drafts[i].category, drafts[i].content, true)
      added += 1
    }
    setDrafts([])
    setSelectedDrafts(new Set())
    setMemories(await studioApi.agentListMemories())
    onChanged()
    onToast?.(`已导入 ${added} 条口味画像记忆`)
  }

  const handleExportMemoriesJson = async () => {
    const payload = JSON.stringify(
      memories.map((m) => ({ category: m.category, content: m.content, enabled: m.enabled })),
      null,
      2
    )
    try {
      await navigator.clipboard.writeText(payload)
      onToast?.('已复制记忆 JSON 到剪贴板，可用于备份或迁移')
    } catch {
      onToast?.('复制失败')
    }
  }

  const handleSaveCommand = async () => {
    if (!cmdName.trim() || !cmdTemplate.trim()) {
      onToast?.('请填写指令名和提示词模板')
      return
    }
    try {
      await studioApi.agentSaveCommand({
        id: cmdId,
        command: cmdName,
        label: cmdLabel || cmdName,
        description: cmdDesc,
        promptTemplate: cmdTemplate,
        skillGuide: cmdGuide.trim() || null,
        toolScope: cmdScope.length > 0 ? cmdScope : null
      })
      setCmdId(null)
      setCmdName('')
      setCmdLabel('')
      setCmdDesc('')
      setCmdTemplate('')
      setCmdGuide('')
      setCmdScope(['read'])
      setCommands(await studioApi.agentListCommands())
      onChanged()
      onToast?.('追番技能 / 快捷指令已保存')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleImportSkillsJson = async () => {
    try {
      const parsed = JSON.parse(skillImportText.trim())
      const arr = Array.isArray(parsed) ? parsed : [parsed]
      let count = 0
      for (const item of arr) {
        if (!item?.command || !item?.promptTemplate) continue
        await studioApi.agentSaveCommand({
          command: String(item.command),
          label: String(item.label ?? item.command),
          description: String(item.description ?? ''),
          promptTemplate: String(item.promptTemplate),
          skillGuide: item.skillGuide ? String(item.skillGuide) : null,
          toolScope: Array.isArray(item.toolScope) ? item.toolScope.map(String) : null
        })
        count += 1
      }
      if (count === 0) {
        onToast?.('JSON 中未找到包含 command 与 promptTemplate 的有效技能')
        return
      }
      setSkillImportText('')
      setShowSkillImport(false)
      setCommands(await studioApi.agentListCommands())
      onChanged()
      onToast?.(`已成功导入 ${count} 个追番技能`)
    } catch (e) {
      onToast?.(`技能 JSON 解析失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const handleSaveMcp = async () => {
    if (!mcpName.trim() || !mcpEndpoint.trim()) {
      onToast?.('请填写 MCP 服务名称与地址/命令')
      return
    }
    try {
      const args =
        mcpTransport === 'stdio'
          ? mcpArgsText
              .split(/\s+/)
              .map((s) => s.trim())
              .filter(Boolean)
          : []
      const existing = mcpServers.find((s) => s.id === mcpEditId)
      await studioApi.agentMcpSave({
        id: mcpEditId,
        name: mcpName.trim(),
        transport: mcpTransport,
        endpointOrCmd: mcpEndpoint.trim(),
        args,
        authToken: mcpToken.trim() ? mcpToken.trim() : null,
        enabled: existing?.enabled ?? true,
        disabledTools: existing?.disabledTools ?? [],
        requireConfirmTools: existing?.requireConfirmTools ?? []
      })
      setMcpEditId(null)
      setMcpName('')
      setMcpEndpoint('')
      setMcpArgsText('')
      setMcpToken('')
      setMcpServers(await studioApi.agentMcpList())
      // 保存后自动询问/提示在策略里开启 MCP 开关
      if (policy && !policy.enabledGroups.mcp) {
        const nextPol = await studioApi.agentSavePolicy({
          ...policy,
          enabledGroups: { ...policy.enabledGroups, mcp: true }
        })
        setPolicy(nextPol)
        setSavedPolicyJson(JSON.stringify(nextPol))
      }
      onChanged()
      onToast?.('扩展服务配置已保存（访问令牌已本机加密）')
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    }
  }

  const handleTestMcp = async (srv: AgentMcpServerConfig) => {
    setMcpTestingId(srv.id)
    try {
      const res = await studioApi.agentMcpTest(srv.id)
      setMcpTestMap((prev) => ({ ...prev, [srv.id]: res }))
      setMcpServers(await studioApi.agentMcpList())
      onToast?.(
        res.ok
          ? `MCP「${srv.name}」连通正常，发现 ${res.tools.length} 个工具（${res.latencyMs}ms）`
          : `MCP「${srv.name}」测试失败：${res.error ?? '未知错误'}`
      )
    } catch (e) {
      onToast?.(e instanceof Error ? e.message : String(e))
    } finally {
      setMcpTestingId(null)
    }
  }

  const handleToggleMcpToolFlag = async (
    srv: AgentMcpServerConfig,
    toolName: string,
    field: 'disabled' | 'confirm',
    checked: boolean
  ) => {
    const nextDisabled =
      field === 'disabled'
        ? checked
          ? Array.from(new Set([...srv.disabledTools, toolName]))
          : srv.disabledTools.filter((t) => t !== toolName)
        : srv.disabledTools
    const nextConfirm =
      field === 'confirm'
        ? checked
          ? Array.from(new Set([...srv.requireConfirmTools, toolName]))
          : srv.requireConfirmTools.filter((t) => t !== toolName)
        : srv.requireConfirmTools

    await studioApi.agentMcpSave({
      id: srv.id,
      name: srv.name,
      transport: srv.transport === 'stdio' ? 'stdio' : 'http',
      endpointOrCmd: srv.endpointOrCmd,
      args: srv.args,
      headers: srv.headers,
      enabled: srv.enabled,
      disabledTools: nextDisabled,
      requireConfirmTools: nextConfirm
    })
    setMcpServers(await studioApi.agentMcpList())
    setMcpTestMap((prev) => {
      const cur = prev[srv.id]
      if (!cur) return prev
      return {
        ...prev,
        [srv.id]: {
          ...cur,
          tools: cur.tools.map((t) =>
            t.name === toolName
              ? {
                  ...t,
                  disabled: field === 'disabled' ? checked : t.disabled,
                  requiresConfirm: field === 'confirm' ? checked : t.requiresConfirm
                }
              : t
          )
        }
      }
    })
  }

  const filteredMemories = useMemo(() => {
    const q = memSearch.trim().toLowerCase()
    return memories.filter((m) => {
      if (memFilterCat !== 'all' && m.category !== memFilterCat) return false
      if (q && !m.content.toLowerCase().includes(q)) return false
      return true
    })
  }, [memories, memFilterCat, memSearch])

  const unsavedAreas = useMemo(() => {
    const dirty: string[] = []
    // 1. 模型接入表单
    const modelDirty = (() => {
      if (!editingProfileId) {
        return apiKey.trim().length > 0
      }
      const orig = profiles.find((p) => p.id === editingProfileId)
      if (!orig) return false
      return (
        apiKey.trim().length > 0 ||
        profileName.trim() !== orig.name.trim() ||
        baseUrl.trim() !== orig.baseUrl.trim() ||
        modelName.trim() !== orig.model.trim() ||
        providerId !== orig.providerId ||
        Math.abs(temperature - orig.temperature) > 1e-6 ||
        maxSteps !== orig.maxSteps ||
        maxTurnTokens !== orig.maxTurnTokens ||
        supportsTools !== orig.supportsTools ||
        supportsReasoning !== orig.supportsReasoning ||
        enableWebSearch !== orig.enableWebSearch ||
        isDefault !== orig.isDefault
      )
    })()
    if (modelDirty) dirty.push('模型接入')

    // 2. 策略与人设
    if (policy && savedPolicyJson && JSON.stringify(policy) !== savedPolicyJson) {
      dirty.push('策略与人设')
    }

    // 3. 长期记忆编辑草稿
    if (newMemContent.trim().length > 0) {
      dirty.push('长期记忆')
    }

    // 4. 快捷指令 / 技能编辑草稿
    if (
      cmdName.trim().length > 0 ||
      cmdTemplate.trim().length > 0 ||
      skillImportText.trim().length > 0
    ) {
      dirty.push('快捷指令')
    }

    // 5. 扩展插件（MCP）表单草稿
    if (
      mcpName.trim().length > 0 ||
      mcpEndpoint.trim().length > 0 ||
      mcpToken.trim().length > 0 ||
      mcpImportJson.trim().length > 0
    ) {
      dirty.push('扩展插件')
    }

    return dirty
  }, [
    editingProfileId,
    profiles,
    apiKey,
    profileName,
    baseUrl,
    modelName,
    providerId,
    temperature,
    maxSteps,
    maxTurnTokens,
    supportsTools,
    supportsReasoning,
    enableWebSearch,
    isDefault,
    policy,
    savedPolicyJson,
    newMemContent,
    cmdName,
    cmdTemplate,
    skillImportText,
    mcpName,
    mcpEndpoint,
    mcpToken,
    mcpImportJson
  ])

  const handleRequestClose = async () => {
    if (unsavedAreas.length > 0) {
      const ok = await dialog.confirm({
        title: '有尚未保存的配置草稿',
        message: `「${unsavedAreas.join('、')}」中有尚未保存的修改或草稿，直接关闭会丢失这些内容，确定放弃并关闭吗？`,
        confirmText: '放弃并关闭',
        cancelText: '继续编辑',
        tone: 'warning'
      })
      if (!ok) return
    }
    onClose()
  }

  if (!open) return null

  return (
    <div
      className="modal-backdrop agent-modal-backdrop"
      style={{ zIndex: modalZ }}
      onPointerDownCapture={bringToFront}
      onClick={() => void handleRequestClose()}
    >
      <div
        className="agent-config-modal"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="AI 智能体配置中心"
      >
        <div
          className="agent-config-header"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口位置，双击归位"
        >
          <div>
            <h3>AI 助手设置</h3>
            <p className="agent-muted">
              使用你自己的大模型账号直连 · 密钥仅加密保存在本机，不会上传云端
            </p>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {modalMoved && (
              <button type="button" className="btn-ghost agent-btn-sm" onClick={resetModalOffset}>
                归位
              </button>
            )}
            <button type="button" className="btn-ghost" onClick={() => void handleRequestClose()}>
              ✕ 关闭
            </button>
          </div>
        </div>

        <div className="agent-config-tabs">
          <button
            type="button"
            className={tab === 'models' ? 'is-active' : ''}
            onClick={() => setTab('models')}
          >
            模型接入
          </button>
          <button
            type="button"
            className={tab === 'policy' ? 'is-active' : ''}
            onClick={() => setTab('policy')}
          >
            人设与习惯
          </button>
          <button
            type="button"
            className={tab === 'memory' ? 'is-active' : ''}
            onClick={() => setTab('memory')}
          >
            长期记忆（{memories.filter((m) => m.enabled).length}）
          </button>
          <button
            type="button"
            className={tab === 'commands' ? 'is-active' : ''}
            onClick={() => setTab('commands')}
          >
            快捷指令
          </button>
          <button
            type="button"
            className={tab === 'mcp' ? 'is-active' : ''}
            onClick={() => setTab('mcp')}
          >
            <IconPlug size={13} /> 扩展插件（{mcpServers.filter((s) => s.enabled).length}）
          </button>
        </div>

        <div className="agent-config-body">
          {tab === 'models' && (
            <div className="agent-models-layout">
              <div className="agent-profiles-rail">
                <button
                  type="button"
                  className={`agent-new-profile-card ${editingProfileId === null ? 'is-active' : ''}`}
                  onClick={() => startNewProfile()}
                >
                  <span className="agent-new-profile-title">+ 接入新模型</span>
                  <span className="agent-new-profile-sub">新建独立档案，不覆盖已有配置</span>
                </button>
                <div className="agent-rail-head">
                  <strong>已保存档案（{profiles.length}）</strong>
                  <span className="agent-muted" style={{ fontSize: '0.8462rem' }}>
                    点击修改
                  </span>
                </div>
                {profiles.length === 0 ? (
                  <div className="agent-empty-hint">还没有模型档案，在右侧选一家服务商即可 30 秒配好</div>
                ) : (
                  profiles.map((p) => (
                    <div
                      key={p.id}
                      className={`agent-profile-item ${editingProfileId === p.id ? 'is-active' : ''}`}
                      onClick={() => loadProfileIntoForm(p)}
                    >
                      <div className="agent-profile-item-top">
                        <strong>{p.name}</strong>
                        {p.isDefault && <span className="agent-tag-pill">默认</span>}
                      </div>
                      <div className="agent-profile-item-sub">{p.model}</div>
                      <div className="agent-profile-item-meta">
                        <span>{p.supportsTools ? '全能工具' : '仅对话'}</span>
                        {p.enableWebSearch && <span>已开联网</span>}
                        <span>{p.hasApiKey ? p.apiKeyHint ?? '已存 Key' : '本地免 Key'}</span>
                        <button
                          type="button"
                          className="agent-link-btn is-danger"
                          onClick={(e) => {
                            e.stopPropagation()
                            handleDeleteProfile(p)
                          }}
                        >
                          删
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </div>

              <div className="agent-model-form">
                <div
                  className={`agent-model-mode-banner ${
                    editingProfileId ? 'is-editing' : 'is-creating'
                  }`}
                >
                  <div className="agent-model-mode-text">
                    <strong>
                      {editingProfileId
                        ? `正在修改已有档案：${editingProfile?.name ?? profileName}`
                        : '当前模式：接入新模型'}
                    </strong>
                    <span>
                      {editingProfileId
                        ? '保存将更新该档案；若想新增一个模型而不覆盖原配置，请切换到「接入新模型」。'
                        : '保存后会新增一条独立模型档案，不会覆盖左侧任何已保存模型。'}
                    </span>
                  </div>
                  {editingProfileId && (
                    <button
                      type="button"
                      className="btn-ghost agent-btn-xs"
                      onClick={() => startNewProfile()}
                    >
                      + 改为接入新模型
                    </button>
                  )}
                </div>

                <div className="agent-section-label">1. 一键选择服务商（自动填入 Base URL 与推荐模型）</div>
                <div className="agent-region-tabs">
                  {REGION_TABS.map((rt) => (
                    <button
                      key={rt.id}
                      type="button"
                      className={regionTab === rt.id ? 'is-active' : ''}
                      onClick={() => setRegionTab(rt.id)}
                    >
                      {rt.label}
                    </button>
                  ))}
                </div>
                {regionTab === 'global' && (
                  <div className="agent-network-notice">
                    <strong>国内网络环境提示：</strong>
                    <span>
                      Google Gemini、OpenAI、xAI、Groq、Mistral 等海外官方接口在国内网络下通常无法直连，需要稳定代理环境（OpenRouter 部分线路亦可能偏慢）。若无代理，建议优先使用「国内直连」中的 DeepSeek、阿里云百炼、智谱 AI 或「本地免 Key」。
                    </span>
                  </div>
                )}

                <div className="agent-provider-grid">
                  {regionProviders.map((prov) => (
                    <button
                      key={prov.id}
                      type="button"
                      className={`agent-provider-card ${providerId === prov.id ? 'is-active' : ''}`}
                      onClick={() => applyPreset(prov)}
                    >
                      <span className="agent-provider-name">{prov.name}</span>
                      {prov.badge && <span className="agent-provider-badge">{prov.badge}</span>}
                    </button>
                  ))}
                </div>

                {currentPreset && (
                  <div className="agent-provider-note">
                    <span>{currentPreset.helpNote}</span>
                    {currentPreset.consoleUrl && (
                      <button
                        type="button"
                        className="agent-link-btn"
                        onClick={() => studioApi.openHttpsUrl(currentPreset.consoleUrl!)}
                      >
                        获取 API Key ↗
                      </button>
                    )}
                  </div>
                )}

                <div className="agent-form-grid">
                  <label>
                    <span>档案名称</span>
                    <input
                      value={profileName}
                      onChange={(e) => setProfileName(e.target.value)}
                      placeholder="例如：DeepSeek 主力 / 本地 Ollama"
                    />
                  </label>
                  <label>
                    <span>接口地址（Base URL，支持自动清洗）</span>
                    <input
                      value={baseUrl}
                      onChange={(e) => setBaseUrl(e.target.value)}
                      placeholder="https://api.deepseek.com/v1"
                    />
                  </label>
                </div>

                {suggestedUrl && (
                  <div className="agent-suggest-bar">
                    <span>检测到更准确的兼容路径：{suggestedUrl}</span>
                    <button
                      type="button"
                      className="btn-primary agent-btn-xs"
                      onClick={() => {
                        setBaseUrl(suggestedUrl)
                        setSuggestedUrl(null)
                      }}
                    >
                      一键修正
                    </button>
                  </div>
                )}

                <div className="agent-form-grid">
                  <label>
                    <span>
                      API Key{' '}
                      {currentPreset?.isLocal
                        ? '（本地服务可留空）'
                        : hasSavedKey
                          ? `（已在本机加密保存 ${savedKeyHint ?? ''}，留空保持不变）`
                          : '（仅在本机加密保存，绝不上传云端）'}
                    </span>
                    <input
                      type="password"
                      value={apiKey}
                      onChange={(e) => setApiKey(e.target.value)}
                      placeholder={
                        currentPreset?.isLocal
                          ? '本地模型无需填写'
                          : hasSavedKey
                            ? '如需更换 Key 请在此输入新的'
                            : '粘贴你的 API Key (sk-...)'
                      }
                    />
                  </label>

                  <label>
                    <span>模型名称（可点右侧拉取最新列表，也可直接手填）</span>
                    <div className="agent-inline-input-row">
                      <input
                        value={modelName}
                        onChange={(e) => setModelName(e.target.value)}
                        placeholder="如 deepseek-flash / qwen3.8-flash / glm-5.3-flash"
                      />
                      <button
                        type="button"
                        className="btn-ghost agent-btn-sm"
                        disabled={fetchingModels}
                        onClick={handleFetchModels}
                      >
                        {fetchingModels ? '拉取中…' : '获取模型'}
                      </button>
                    </div>
                  </label>
                </div>

                {currentPreset && currentPreset.recommendedModels.length > 0 && (
                  <div className="agent-quick-models">
                    <span className="agent-muted">推荐：</span>
                    {currentPreset.recommendedModels.map((m) => {
                      const roleTag = MODEL_ROLE_LABELS[m]
                      return (
                        <button
                          key={m}
                          type="button"
                          className={`agent-chip-btn ${modelName === m ? 'is-active' : ''}`}
                          onClick={() => setModelName(m)}
                        >
                          <span>{m}</span>
                          {roleTag && <span className="agent-chip-role">{roleTag}</span>}
                        </button>
                      )
                    })}
                  </div>
                )}

                {fetchedModels.length > 0 && (
                  <div className="agent-fetched-models">
                    <input
                      value={modelFilter}
                      onChange={(e) => setModelFilter(e.target.value)}
                      placeholder={`筛选已拉取的 ${fetchedModels.length} 个对话模型…`}
                    />
                    <div className="agent-fetched-models-list">
                      {filteredRemoteModels.map((m) => (
                        <button
                          key={m.id}
                          type="button"
                          className={`agent-chip-btn ${modelName === m.id ? 'is-active' : ''}`}
                          onClick={() => setModelName(m.id)}
                        >
                          {m.id}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                <div className="agent-form-grid agent-form-grid-3">
                  <label>
                    <span>回复灵活度 ({temperature.toFixed(1)})</span>
                    <input
                      type="range"
                      min={0}
                      max={1.5}
                      step={0.1}
                      value={temperature}
                      onChange={(e) => setTemperature(Number(e.target.value))}
                    />
                  </label>
                  <label>
                    <span>单次最多自动查询步数</span>
                    <input
                      type="number"
                      min={2}
                      max={12}
                      value={maxSteps}
                      onChange={(e) => setMaxSteps(Number(e.target.value) || 8)}
                    />
                  </label>
                  <label>
                    <span>单次回复额度上限（未缓存输入和输出 Token）</span>
                    <input
                      type="number"
                      min={8000}
                      max={300000}
                      step={10000}
                      value={maxTurnTokens}
                      onChange={(e) => setMaxTurnTokens(Number(e.target.value) || 60000)}
                    />
                  </label>
                </div>

                <div className="agent-checkbox-row">
                  <label>
                    <input
                      type="checkbox"
                      checked={supportsTools}
                      onChange={(e) => setSupportsTools(e.target.checked)}
                    />
                    <span>允许 AI 自动查库、搜番与操作界面（不支持工具调用的模型取消勾选即可纯聊天）</span>
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={
                        Boolean(
                          currentPreset?.webSearchMode && currentPreset.webSearchMode !== 'none'
                        ) && enableWebSearch
                      }
                      disabled={
                        !currentPreset?.webSearchMode || currentPreset.webSearchMode === 'none'
                      }
                      onChange={(e) => setEnableWebSearch(e.target.checked)}
                    />
                    <span>
                      开启全网搜索与网页阅读
                      {!currentPreset?.webSearchMode || currentPreset.webSearchMode === 'none'
                        ? '（当前服务商暂不支持原生联网，内置发现页榜单与番剧库仍免费可用）'
                        : ''}
                    </span>
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      checked={isDefault}
                      onChange={(e) => setIsDefault(e.target.checked)}
                    />
                    <span>设为默认模型</span>
                  </label>
                </div>
                {currentPreset?.webSearchNote && (
                  <div className="agent-provider-note" style={{ marginTop: 4 }}>
                    <span>联网说明：{currentPreset.webSearchNote}</span>
                  </div>
                )}

                {probeResult && (
                  <div className="agent-probe-panel">
                    <div className="agent-probe-step">
                      <span className={probeResult.connectivity.ok ? 'is-ok' : 'is-fail'}>
                        {probeResult.connectivity.ok ? '●' : '✕'} 账号连通
                      </span>
                      <span>{probeResult.connectivity.detail}</span>
                    </div>
                    <div className="agent-probe-step">
                      <span className={probeResult.streaming.ok ? 'is-ok' : 'is-fail'}>
                        {probeResult.streaming.ok ? '●' : '✕'} 逐字输出
                      </span>
                      <span>{probeResult.streaming.detail}</span>
                    </div>
                    <div className="agent-probe-step">
                      <span className={probeResult.toolCalling.ok ? 'is-ok' : 'is-warn'}>
                        {probeResult.toolCalling.ok ? '●' : '!'} 查库/工具能力
                      </span>
                      <span>{probeResult.toolCalling.detail}</span>
                    </div>
                  </div>
                )}

                {modelErr && (
                  <div className="agent-diag-card">
                    <div className="agent-diag-title">
                      <IconWarning size={14} /> {modelErr.title}
                    </div>
                    <div className="agent-diag-desc">{modelErr.explanation}</div>
                    <div className="agent-diag-tip">建议：{modelErr.suggestion}</div>
                  </div>
                )}

                <div className="agent-form-footer">
                  <button
                    type="button"
                    className="btn-ghost"
                    disabled={probing}
                    onClick={handleProbe}
                  >
                    {probing ? (
                      '正在测试连接…'
                    ) : (
                      <>
                        <IconSearch size={13} /> 测试连接
                      </>
                    )}
                  </button>
                  <div className="agent-form-footer-actions">
                    {editingProfileId && (
                      <button
                        type="button"
                        className="btn-ghost"
                        disabled={savingModel}
                        title="保留当前表单参数并切换为新建模式，不覆盖原档案"
                        onClick={handleSaveAsNewProfile}
                      >
                        另存为新模型
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn-primary"
                      disabled={savingModel}
                      onClick={handleSaveProfile}
                    >
                      {savingModel
                        ? '保存中…'
                        : editingProfileId
                          ? '保存修改'
                          : '新增并保存模型'}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          {tab === 'policy' && policy && (
            <div className="agent-policy-panel">
              <div className="agent-section-label">1. 选择助手性格与人设</div>
              <div className="agent-persona-grid">
                {PERSONA_CARDS.map((pc) => (
                  <button
                    key={pc.id}
                    type="button"
                    className={`agent-persona-card ${policy.personaId === pc.id ? 'is-active' : ''}`}
                    onClick={() => setPolicy({ ...policy, personaId: pc.id })}
                  >
                    <strong>{pc.name}</strong>
                    <span>{pc.desc}</span>
                  </button>
                ))}
              </div>

              <div className="agent-persona-custom-box">
                <div className="agent-rail-head">
                  <div className="agent-section-label" style={{ margin: 0 }}>
                    {policy.personaId === 'custom'
                      ? '编写你的专属人设（怎么称呼你、什么性格、怎么说话）'
                      : '补充个人要求（选填 · 例如怎么称呼你、偏好的回答方式）'}
                  </div>
                  {policy.customInstructions.trim() && (
                    <button
                      type="button"
                      className="agent-link-btn"
                      onClick={() => setPolicy({ ...policy, customInstructions: '' })}
                    >
                      清空内容
                    </button>
                  )}
                </div>
                <div className="agent-model-chips">
                  <span className="agent-muted">快速套用模板：</span>
                  {CUSTOM_PERSONA_TEMPLATES.map((tpl) => (
                    <button
                      key={tpl.label}
                      type="button"
                      className="agent-chip-btn"
                      onClick={() =>
                        setPolicy({
                          ...policy,
                          personaId: 'custom',
                          customInstructions: tpl.text
                        })
                      }
                    >
                      {tpl.label}
                    </button>
                  ))}
                </div>
                <textarea
                  className="agent-textarea"
                  rows={4}
                  value={policy.customInstructions}
                  onChange={(e) => setPolicy({ ...policy, customInstructions: e.target.value })}
                  placeholder={
                    policy.personaId === 'custom'
                      ? '写下你想要的助手人设，例如：叫我阿喵；像多年一起追番的老友一样聊天，点评动画时重点聊分镜和配乐，说话自然简短，不要套话。'
                      : '选填：想额外叮嘱助手什么？比如怎么称呼你、回答尽量简洁还是详细、点评时更关注剧情还是画风。'
                  }
                />
              </div>

              <div className="agent-section-label">2. 改动数据时的确认与自动审批方式</div>
              <div className="agent-trust-grid">
                {(
                  [
                    {
                      id: 'strict',
                      name: '每次改动都先问我',
                      desc: '无论修改藏品还是调整软件设置，都先弹出确认卡片再执行'
                    },
                    {
                      id: 'balanced',
                      name: '均衡省心（默认）',
                      desc: '写藏品库统一走确认卡片，多份方案支持底部一键全部审批与整批撤销'
                    },
                    {
                      id: 'auto_safe',
                      name: '安全自动审批',
                      desc: '仅自动放行下方勾选的低风险且 100% 可撤销改动（删除、合并与覆盖手写值仍强制确认）'
                    }
                  ] as const
                ).map((tm) => {
                  const isActive =
                    policy.trustMode === tm.id ||
                    (tm.id === 'auto_safe' && policy.trustMode === 'quick_log')
                  return (
                    <button
                      key={tm.id}
                      type="button"
                      className={`agent-persona-card ${isActive ? 'is-active' : ''}`}
                      onClick={() => setPolicy({ ...policy, trustMode: tm.id })}
                    >
                      <strong>{tm.name}</strong>
                      <span>{tm.desc}</span>
                    </button>
                  )
                })}
              </div>

              {(policy.trustMode === 'auto_safe' || policy.trustMode === 'quick_log') && (
                <div className="agent-persona-custom-box" style={{ marginTop: 8 }}>
                  <div className="agent-section-label" style={{ marginTop: 0 }}>
                    安全自动放行范围（仅可撤销项生效；删除/合并/覆盖手写值永不自动放行）
                  </div>
                  <div className="agent-checkbox-row">
                    {(
                      [
                        ['quickLog', '单条记进度、状态、个人评分与短评'],
                        ['rehydrateEmpty', '仅填空值的封面与外部元数据回填'],
                        ['highConfidenceBind', '高置信度（无冲突）外部 ID 自动绑定'],
                        ['createFromCatalog', '外部检索入库新增作品（支持软删撤销）'],
                        ['appendTagsOnly', '仅追加标签（不删除或替换旧标签）']
                      ] as const
                    ).map(([k, lbl]) => {
                      const curAuto = policy.autoApprove ?? {
                        quickLog: true,
                        rehydrateEmpty: true,
                        highConfidenceBind: true,
                        createFromCatalog: false,
                        appendTagsOnly: false,
                        maxItemsPerAutoBatch: 20
                      }
                      return (
                        <label key={k}>
                          <input
                            type="checkbox"
                            checked={Boolean(curAuto[k])}
                            onChange={(e) =>
                              setPolicy({
                                ...policy,
                                autoApprove: { ...curAuto, [k]: e.target.checked }
                              })
                            }
                          />
                          <span>{lbl}</span>
                        </label>
                      )
                    })}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 8, fontSize: '0.9231rem' }}>
                    <span className="agent-muted">单次自动放行最大作品数上限：</span>
                    <input
                      type="number"
                      min={1}
                      max={200}
                      className="input"
                      style={{ width: 76, height: 28 }}
                      value={policy.autoApprove?.maxItemsPerAutoBatch ?? 20}
                      onChange={(e) => {
                        const val = Math.max(1, Math.min(200, Number(e.target.value) || 20))
                        const curAuto = policy.autoApprove ?? {
                          quickLog: true,
                          rehydrateEmpty: true,
                          highConfidenceBind: true,
                          createFromCatalog: false,
                          appendTagsOnly: false,
                          maxItemsPerAutoBatch: 20
                        }
                        setPolicy({
                          ...policy,
                          autoApprove: { ...curAuto, maxItemsPerAutoBatch: val }
                        })
                      }}
                    />
                    <span className="agent-muted">部（超出上限自动转为手动确认）</span>
                  </div>
                </div>
              )}

              <div className="agent-section-label">2.5 回填与封面存储策略</div>
              <div className="agent-checkbox-row" style={{ alignItems: 'center', flexWrap: 'wrap', gap: 14 }}>
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                  <span>外部封面策略：</span>
                  <StudioSelect
                    className="studio-select-inline"
                    value={policy.coverStoragePolicy ?? 'remote'}
                    onChange={(val) =>
                      setPolicy({
                        ...policy,
                        coverStoragePolicy: val as 'remote' | 'local' | 'auto'
                      })
                    }
                    ariaLabel="外部封面策略"
                    minMenuWidth={240}
                    maxMenuWidth={320}
                    options={[
                      {
                        value: 'remote',
                        label: '远程优先（推荐）',
                        sublabel: '探测可用即直接引用，探测失败再落盘本地'
                      },
                      {
                        value: 'local',
                        label: '全部落盘本地（离线归档）',
                        sublabel: '备份包体积会相应增大'
                      }
                    ]}
                  />
                </div>
                <label>
                  <input
                    type="checkbox"
                    checked={policy.filterMetadataTags ?? true}
                    onChange={(e) =>
                      setPolicy({ ...policy, filterMetadataTags: e.target.checked })
                    }
                  />
                  <span>回填标签默认过滤形态/地区/改编/制作组元数据标签</span>
                </label>
              </div>

              <div className="agent-section-label">3. 允许 AI 帮你做哪些事</div>
              <div className="agent-checkbox-row">
                {(
                  [
                    ['read', '查看我的藏品库与软件说明'],
                    ['online', '联网查番剧、游戏、角色与放送表'],
                    ['ui', '帮我切换页面、筛选作品和调桌面设置'],
                    ['write', '帮我整理系列、标签和修改记录（改前会确认）'],
                    ['memory', '记住我的追番口味与避雷习惯'],
                    ['mcp', '调用我额外安装的扩展插件（MCP）']
                  ] as const
                ).map(([key, label]) => (
                  <label key={key}>
                    <input
                      type="checkbox"
                      checked={policy.enabledGroups[key]}
                      onChange={(e) =>
                        setPolicy({
                          ...policy,
                          enabledGroups: { ...policy.enabledGroups, [key]: e.target.checked }
                        })
                      }
                    />
                    <span>{label}</span>
                  </label>
                ))}
              </div>

              <div className="agent-checkbox-row">
                <label>
                  <input
                    type="checkbox"
                    checked={policy.defaultAntiSpoiler}
                    onChange={(e) =>
                      setPolicy({ ...policy, defaultAntiSpoiler: e.target.checked })
                    }
                  />
                  <span>聊在看作品时默认开启「防剧透」，绝不提我还没看到的集数和结局</span>
                </label>
              </div>

              <details className="agent-advanced-fold">
                <summary>高级设置：长对话自动整理前文（聊很久也不会忘事或超额）</summary>
                <div className="agent-form-grid" style={{ marginTop: 10 }}>
                  <label>
                    <span>对话多长后自动整理前文（Token，默认 32000）</span>
                    <input
                      type="number"
                      min={8000}
                      max={200000}
                      step={4000}
                      value={policy.autoCompactTokens}
                      onChange={(e) =>
                        setPolicy({ ...policy, autoCompactTokens: Number(e.target.value) || 32000 })
                      }
                    />
                  </label>
                  <label>
                    <span>整理后原文保留最近几轮对话（默认 8 轮）</span>
                    <input
                      type="number"
                      min={4}
                      max={30}
                      value={policy.maxHistoryTurns}
                      onChange={(e) =>
                        setPolicy({ ...policy, maxHistoryTurns: Number(e.target.value) || 8 })
                      }
                    />
                  </label>
                </div>
              </details>

              <div className="agent-form-footer">
                <button
                  type="button"
                  className="btn-primary"
                  onClick={() => handleSavePolicy(policy)}
                >
                  保存人设与习惯
                </button>
              </div>
            </div>
          )}

          {tab === 'memory' && (
            <div className="agent-memory-panel">
              <div className="agent-memory-toolbar">
                <input
                  value={memSearch}
                  onChange={(e) => setMemSearch(e.target.value)}
                  placeholder="搜索记忆内容…"
                />
                <StudioSelect
                  className="studio-select-inline"
                  value={memFilterCat}
                  onChange={(val) => setMemFilterCat(val)}
                  ariaLabel="按分类筛选记忆"
                  minMenuWidth={136}
                  maxMenuWidth={200}
                  options={[
                    { value: 'all', label: '全部分类' },
                    ...MEMORY_CATEGORIES.map((c) => ({ value: c.id, label: c.label }))
                  ]}
                />
                <button type="button" className="btn-primary agent-btn-sm" onClick={handleDraftMemories}>
                  <IconSpark size={13} /> 按藏品库生成口味画像（免费本地分析）
                </button>
                <button type="button" className="btn-ghost agent-btn-sm" onClick={handleExportMemoriesJson}>
                  复制 JSON 备份
                </button>
              </div>

              {drafts.length > 0 && (
                <div className="agent-draft-box">
                  <div className="agent-draft-head">
                    <strong>基于你的藏品库本地提炼的口味画像草稿（勾选导入）</strong>
                    <button
                      type="button"
                      className="btn-ghost agent-btn-xs"
                      onClick={() => setDrafts([])}
                    >
                      关闭
                    </button>
                  </div>
                  {drafts.map((d, i) => (
                    <label key={i} className="agent-draft-row">
                      <input
                        type="checkbox"
                        checked={selectedDrafts.has(i)}
                        onChange={() => {
                          setSelectedDrafts((prev) => {
                            const next = new Set(prev)
                            if (next.has(i)) next.delete(i)
                            else next.add(i)
                            return next
                          })
                        }}
                      />
                      <span className="agent-tag-pill">
                        {MEMORY_CATEGORIES.find((c) => c.id === d.category)?.label ?? d.category}
                      </span>
                      <span>{d.content}</span>
                    </label>
                  ))}
                  <div className="agent-form-footer">
                    <button
                      type="button"
                      className="btn-primary agent-btn-sm"
                      disabled={selectedDrafts.size === 0}
                      onClick={handleImportSelectedDrafts}
                    >
                      导入已选 {selectedDrafts.size} 条记忆
                    </button>
                  </div>
                </div>
              )}

              <div className="agent-memory-add-row">
                <StudioSelect
                  className="studio-select-inline"
                  value={newMemCat}
                  onChange={(val) => setNewMemCat(val)}
                  ariaLabel="记忆分类"
                  minMenuWidth={130}
                  maxMenuWidth={180}
                  options={MEMORY_CATEGORIES.map((c) => ({ value: c.id, label: c.label }))}
                />
                <StudioSelect
                  className="studio-select-inline"
                  value={newMemTier}
                  onChange={(val) => setNewMemTier(val as 'core' | 'archive')}
                  ariaLabel="记忆生效层级"
                  title="始终记住：每次聊天都会带上（最多 12 条）；备忘归档：平时不占额度，聊到相关话题时自动想起"
                  minMenuWidth={186}
                  maxMenuWidth={250}
                  options={[
                    {
                      value: 'core',
                      label: '始终记住',
                      sublabel: '每次对话都会带上（最多 12 条）'
                    },
                    {
                      value: 'archive',
                      label: '备忘归档',
                      sublabel: '聊到相关话题时自动召回，省额度'
                    }
                  ]}
                />
                <input
                  value={newMemContent}
                  onChange={(e) => setNewMemContent(e.target.value)}
                  placeholder="手动添加一条记忆，例如：我打分偏严格，8.5 分以上才算神作 / 讨厌后宫番"
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleSaveMemory()
                  }}
                />
                <button type="button" className="btn-primary agent-btn-sm" onClick={handleSaveMemory}>
                  {editingMemId ? '保存修改' : '添加记忆'}
                </button>
                {editingMemId && (
                  <button
                    type="button"
                    className="btn-ghost agent-btn-sm"
                    onClick={() => {
                      setEditingMemId(null)
                      setNewMemContent('')
                    }}
                  >
                    取消
                  </button>
                )}
              </div>

              <div className="agent-memory-list">
                {filteredMemories.length === 0 ? (
                  <div className="agent-empty-hint">
                    还没有长期记忆。你可以手动添加、点上方「从藏品库提炼初始口味」，或者在聊天里直接告诉 AI「记住我喜欢悬疑推理番」。
                  </div>
                ) : (
                  filteredMemories.map((m) => (
                    <div key={m.id} className={`agent-memory-row ${m.enabled ? '' : 'is-disabled'}`}>
                      <input
                        type="checkbox"
                        checked={m.enabled}
                        onChange={async (e) => {
                          await studioApi.agentUpsertMemory(
                            m.category,
                            m.content,
                            e.target.checked,
                            m.id,
                            m.tier === 'archive' ? 'archive' : 'core'
                          )
                          setMemories(await studioApi.agentListMemories())
                          onChanged()
                        }}
                      />
                      <span className="agent-tag-pill">
                        {MEMORY_CATEGORIES.find((c) => c.id === m.category)?.label ?? m.category}
                      </span>
                      <button
                        type="button"
                        className={`agent-tier-pill ${m.tier === 'archive' ? 'is-archive' : 'is-core'}`}
                        title="点击切换：始终记住（每次聊天都带上） / 备忘归档（聊到相关话题时自动想起）"
                        onClick={async () => {
                          const nextTier = m.tier === 'archive' ? 'core' : 'archive'
                          await studioApi.agentUpsertMemory(
                            m.category,
                            m.content,
                            m.enabled,
                            m.id,
                            nextTier
                          )
                          setMemories(await studioApi.agentListMemories())
                          onChanged()
                        }}
                      >
                        {m.tier === 'archive' ? '备忘归档' : '始终记住'}
                      </button>
                      <span className="agent-memory-row-text">{m.content}</span>
                      <button
                        type="button"
                        className="agent-link-btn"
                        onClick={() => {
                          setEditingMemId(m.id)
                          setNewMemCat(m.category)
                          setNewMemTier(m.tier === 'archive' ? 'archive' : 'core')
                          setNewMemContent(m.content)
                        }}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        className="agent-link-btn is-danger"
                        onClick={async () => {
                          await studioApi.agentDeleteMemory(m.id)
                          setMemories(await studioApi.agentListMemories())
                          onChanged()
                        }}
                      >
                        删除
                      </button>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          {tab === 'commands' && (
            <div className="agent-commands-panel">
              <div className="agent-rail-head">
                <div className="agent-section-label">
                  常用追番快捷指令（在聊天框输入 / 即可一键调用，也支持自定义常用模板）
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button
                    type="button"
                    className="btn-ghost agent-btn-xs"
                    onClick={() => setShowSkillImport((v) => !v)}
                  >
                    导入技能 JSON
                  </button>
                  <button
                    type="button"
                    className="btn-ghost agent-btn-xs"
                    onClick={async () => {
                      const custom = commands.filter((c) => !c.isBuiltin)
                      const data = JSON.stringify(custom.length > 0 ? custom : commands, null, 2)
                      await navigator.clipboard.writeText(data)
                      onToast?.('已复制技能包 JSON 到剪贴板，可分享给其他追番喵用户')
                    }}
                  >
                    导出分享 JSON
                  </button>
                </div>
              </div>

              {showSkillImport && (
                <div className="agent-draft-box">
                  <textarea
                    className="agent-textarea"
                    rows={4}
                    value={skillImportText}
                    onChange={(e) => setSkillImportText(e.target.value)}
                    placeholder='粘贴他人分享的技能 JSON，例如 { "command": "/漫评", "label": "深度漫评", "promptTemplate": "...", "skillGuide": "## 步骤..." }'
                  />
                  <div className="agent-form-footer">
                    <button
                      type="button"
                      className="btn-primary agent-btn-xs"
                      onClick={handleImportSkillsJson}
                    >
                      确认导入
                    </button>
                  </div>
                </div>
              )}

              <div className="agent-command-list">
                {commands.map((c) => (
                  <div key={c.id} className="agent-command-row">
                    <div className="agent-command-top">
                      <strong>{c.command}</strong>
                      <span>{c.label}</span>
                      {c.skillGuide && <span className="agent-tier-pill is-core">含 Skill 规程</span>}
                      {c.isBuiltin ? (
                        <span className="agent-tag-pill">内置</span>
                      ) : (
                        <>
                          <button
                            type="button"
                            className="agent-link-btn"
                            onClick={() => {
                              setCmdId(c.id)
                              setCmdName(c.command)
                              setCmdLabel(c.label)
                              setCmdDesc(c.description)
                              setCmdTemplate(c.promptTemplate)
                              setCmdGuide(c.skillGuide ?? '')
                              setCmdScope(c.toolScope ?? ['read'])
                            }}
                          >
                            编辑
                          </button>
                          <button
                            type="button"
                            className="agent-link-btn is-danger"
                            onClick={async () => {
                              await studioApi.agentDeleteCommand(c.id)
                              setCommands(await studioApi.agentListCommands())
                              onChanged()
                            }}
                          >
                            删除
                          </button>
                        </>
                      )}
                    </div>
                    <div className="agent-muted">{c.description || c.promptTemplate}</div>
                  </div>
                ))}
              </div>

              <div className="agent-section-label">
                {cmdId ? '编辑自定义技能 / 指令' : '新建自定义追番技能（Skill）'}
              </div>
              <div className="agent-form-grid">
                <label>
                  <span>指令名（如 /季度总结）</span>
                  <input
                    value={cmdName}
                    onChange={(e) => setCmdName(e.target.value)}
                    placeholder="/季度总结"
                  />
                </label>
                <label>
                  <span>显示标题</span>
                  <input
                    value={cmdLabel}
                    onChange={(e) => setCmdLabel(e.target.value)}
                    placeholder="生成本季度追番总结"
                  />
                </label>
              </div>
              <label className="agent-full-label">
                <span>一句话触发简介（常驻于技能目录，方便 AI 自动识别场景）</span>
                <input
                  value={cmdDesc}
                  onChange={(e) => setCmdDesc(e.target.value)}
                  placeholder="汇总本季度在看/已看作品并给出最佳推荐"
                />
              </label>
              <label className="agent-full-label">
                <span>用户输入 `/` 时的快捷提示词模板</span>
                <textarea
                  className="agent-textarea"
                  rows={2}
                  value={cmdTemplate}
                  onChange={(e) => setCmdTemplate(e.target.value)}
                  placeholder="请加载 /季度总结 技能规程，帮我回顾本季度看过的作品…"
                />
              </label>
              <label className="agent-full-label">
                <span>渐进式完整 Skill 规程（Markdown，仅在 AI 调用 load_skill 时才消耗 Token）</span>
                <textarea
                  className="agent-textarea"
                  rows={4}
                  value={cmdGuide}
                  onChange={(e) => setCmdGuide(e.target.value)}
                  placeholder={'## 操作步骤\n1. 先调用 query_library 获取近三个月看完的作品\n2. 按评分排序并分析题材变化\n3. 输出年度金榜与避雷总结'}
                />
              </label>
              <div className="agent-checkbox-row">
                <span className="agent-muted">推荐工具组：</span>
                {TOOL_SCOPE_OPTIONS.map((opt) => (
                  <label key={opt.id}>
                    <input
                      type="checkbox"
                      checked={cmdScope.includes(opt.id)}
                      onChange={(e) => {
                        setCmdScope((prev) =>
                          e.target.checked ? [...prev, opt.id] : prev.filter((x) => x !== opt.id)
                        )
                      }}
                    />
                    <span>{opt.label}</span>
                  </label>
                ))}
              </div>
              <div className="agent-form-footer">
                {cmdId && (
                  <button
                    type="button"
                    className="btn-ghost"
                    onClick={() => {
                      setCmdId(null)
                      setCmdName('')
                      setCmdLabel('')
                      setCmdDesc('')
                      setCmdTemplate('')
                      setCmdGuide('')
                      setCmdScope(['read'])
                    }}
                  >
                    取消编辑
                  </button>
                )}
                <button type="button" className="btn-primary" onClick={handleSaveCommand}>
                  保存技能 / 指令
                </button>
              </div>
            </div>
          )}

          {tab === 'mcp' && (
            <div className="agent-commands-panel">
              <div className="agent-provider-note" style={{ alignItems: 'flex-start' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <strong>什么是扩展插件（MCP）？（完全可选，不装也能正常使用）</strong>
                  <span>
                    你可以把 MCP 理解成「给 AI 装的外接工具插槽」。追番喵已经自带了查藏品库、推番、防剧透、整理标签/系列、在线搜 Bangumi/VNDB 和调桌面设置等全部能力；只有当你还想让 AI 额外连上外部工具（例如全网搜索引擎、Notion/Obsidian 笔记、本地文件夹）时，才需要在这里添加。
                  </span>
                  <span className="agent-muted">
                    智能按需加载：装再多插件也不会拖慢速度或白耗额度；涉及修改/写入的外部操作都会先弹卡片让你确认，不经同意绝不会私自执行。
                  </span>
                </div>
                <button
                  type="button"
                  className="btn-ghost agent-btn-xs"
                  style={{ flexShrink: 0 }}
                  onClick={() => setShowMcpJsonImport((v) => !v)}
                >
                  <IconClipboard size={12} /> 粘贴配置导入
                </button>
              </div>

              {showMcpJsonImport && (
                <div className="agent-draft-box">
                  <div className="agent-draft-head">
                    <strong>直接粘贴别人分享的 MCP 配置文件（兼容 Claude / Cursor 格式）</strong>
                    <button
                      type="button"
                      className="btn-ghost agent-btn-xs"
                      onClick={() => setShowMcpJsonImport(false)}
                    >
                      收起
                    </button>
                  </div>
                  <textarea
                    className="agent-textarea"
                    rows={5}
                    value={mcpImportJson}
                    onChange={(e) => setMcpImportJson(e.target.value)}
                    placeholder={'{\n  "mcpServers": {\n    "my-search": { "url": "https://example.com/mcp", "headers": { "Authorization": "Bearer xxx" } }\n  }\n}'}
                  />
                  <div className="agent-form-footer">
                    <button
                      type="button"
                      className="btn-primary agent-btn-xs"
                      onClick={async () => {
                        try {
                          const list = await studioApi.agentMcpImportJson(mcpImportJson)
                          setMcpImportJson('')
                          setShowMcpJsonImport(false)
                          setMcpServers(await studioApi.agentMcpList())
                          if (policy && !policy.enabledGroups.mcp) {
                            const nextPol = await studioApi.agentSavePolicy({
                              ...policy,
                              enabledGroups: { ...policy.enabledGroups, mcp: true }
                            })
                            setPolicy(nextPol)
                            setSavedPolicyJson(JSON.stringify(nextPol))
                          }
                          onChanged()
                          onToast?.(`已成功导入 ${list.length} 个扩展插件`)
                        } catch (e) {
                          onToast?.(e instanceof Error ? e.message : String(e))
                        }
                      }}
                    >
                      一键解析并导入
                    </button>
                  </div>
                </div>
              )}

              <div className="agent-command-list">
                {mcpServers.length === 0 ? (
                  <div className="agent-empty-hint">
                    当前还没有添加任何外部扩展插件（不影响追番喵内置的查库、推番和在线搜番功能）。如果你有外部 MCP 服务网址或 JSON 配置，可在上方导入或在下方填写。
                  </div>
                ) : (
                  mcpServers.map((srv) => {
                    const testRes = mcpTestMap[srv.id]
                    return (
                      <div key={srv.id} className="agent-command-row">
                        <div className="agent-command-top">
                          <input
                            type="checkbox"
                            checked={srv.enabled}
                            onChange={async (e) => {
                              await studioApi.agentMcpSave({
                                id: srv.id,
                                name: srv.name,
                                transport: srv.transport === 'stdio' ? 'stdio' : 'http',
                                endpointOrCmd: srv.endpointOrCmd,
                                args: srv.args,
                                headers: srv.headers,
                                enabled: e.target.checked,
                                disabledTools: srv.disabledTools,
                                requireConfirmTools: srv.requireConfirmTools
                              })
                              setMcpServers(await studioApi.agentMcpList())
                              onChanged()
                            }}
                          />
                          <strong>{srv.name}</strong>
                          <span className="agent-tag-pill">
                            {srv.transport === 'stdio' ? '本机程序' : '网络链接'}
                          </span>
                          {srv.lastStatus && (
                            <span className="agent-muted">
                              {srv.lastStatus}
                              {srv.lastLatencyMs ? ` (${srv.lastLatencyMs}ms)` : ''}
                            </span>
                          )}
                          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
                            <button
                              type="button"
                              className="agent-link-btn"
                              disabled={mcpTestingId === srv.id}
                              onClick={() => handleTestMcp(srv)}
                            >
                              {mcpTestingId === srv.id ? '检测中…' : '测试连接并查看工具'}
                            </button>
                            <button
                              type="button"
                              className="agent-link-btn"
                              onClick={() => {
                                setMcpEditId(srv.id)
                                setMcpName(srv.name)
                                setMcpTransport(srv.transport === 'stdio' ? 'stdio' : 'http')
                                setMcpEndpoint(srv.endpointOrCmd)
                                setMcpArgsText(srv.args.join(' '))
                                setMcpToken('')
                              }}
                            >
                              编辑
                            </button>
                            <button
                              type="button"
                              className="agent-link-btn is-danger"
                              onClick={async () => {
                                await studioApi.agentMcpDelete(srv.id)
                                setMcpServers(await studioApi.agentMcpList())
                                onChanged()
                                onToast?.(`已删除扩展插件「${srv.name}」`)
                              }}
                            >
                              删除
                            </button>
                          </div>
                        </div>
                        <div className="agent-trace-args">
                          {srv.endpointOrCmd} {srv.args.join(' ')}
                        </div>
                        {testRes && testRes.tools.length > 0 && (
                          <div className="agent-mcp-tools-box">
                            <div className="agent-muted">
                              已发现 {testRes.tools.length} 个可用工具（可单独开关，或勾选「执行前需确认」）：
                            </div>
                            {testRes.tools.map((t) => (
                              <div key={t.name} className="agent-mcp-tool-row">
                                <label>
                                  <input
                                    type="checkbox"
                                    checked={!t.disabled}
                                    onChange={(e) =>
                                      handleToggleMcpToolFlag(
                                        srv,
                                        t.name,
                                        'disabled',
                                        !e.target.checked
                                      )
                                    }
                                  />
                                  <strong>{t.name}</strong>
                                </label>
                                <span className="agent-muted" style={{ flex: 1 }}>
                                  {t.description.slice(0, 70)}
                                </span>
                                <label>
                                  <input
                                    type="checkbox"
                                    checked={t.requiresConfirm}
                                    onChange={(e) =>
                                      handleToggleMcpToolFlag(
                                        srv,
                                        t.name,
                                        'confirm',
                                        e.target.checked
                                      )
                                    }
                                  />
                                  <span>执行前需确认</span>
                                </label>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    )
                  })
                )}
              </div>

              <div className="agent-section-label">
                {mcpEditId ? '编辑扩展插件' : '手动添加扩展插件'}
              </div>
              <div className="agent-form-grid">
                <label>
                  <span>插件名称（起个好记的短名字，如 web-search）</span>
                  <input
                    value={mcpName}
                    onChange={(e) => setMcpName(e.target.value)}
                    placeholder="tavily-search"
                  />
                </label>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span style={{ fontSize: '0.885rem', color: 'var(--text-secondary)' }}>接入方式</span>
                  <StudioSelect
                    className="studio-select-full"
                    value={mcpTransport}
                    onChange={(val) => setMcpTransport(val as 'http' | 'stdio')}
                    ariaLabel="MCP 插件接入方式"
                    minMenuWidth={260}
                    maxMenuWidth={360}
                    options={[
                      {
                        value: 'http',
                        label: '网络链接接入（推荐新手）',
                        sublabel: '填网址即可，无需本机安装运行环境'
                      },
                      {
                        value: 'stdio',
                        label: '本机程序接入（进阶）',
                        sublabel: '通过命令行运行，需本机已装 Node/Python'
                      }
                    ]}
                  />
                </div>
              </div>

              {mcpTransport === 'http' ? (
                <div className="agent-form-grid">
                  <label>
                    <span>插件服务网址（https:// 开头，或本机 127.0.0.1）</span>
                    <input
                      value={mcpEndpoint}
                      onChange={(e) => setMcpEndpoint(e.target.value)}
                      placeholder="https://example.com/mcp"
                    />
                  </label>
                  <label>
                    <span>访问密钥 / 令牌（选填，本机加密保存，无密钥留空）</span>
                    <input
                      type="password"
                      value={mcpToken}
                      onChange={(e) => setMcpToken(e.target.value)}
                      placeholder="不需要密码可留空"
                    />
                  </label>
                </div>
              ) : (
                <div className="agent-form-grid">
                  <label>
                    <span>本机启动命令（后台静默运行，关软件自动退出）</span>
                    <input
                      value={mcpEndpoint}
                      onChange={(e) => setMcpEndpoint(e.target.value)}
                      placeholder="npx 或 uvx 或 python"
                    />
                  </label>
                  <label>
                    <span>启动参数（多个参数用空格隔开）</span>
                    <input
                      value={mcpArgsText}
                      onChange={(e) => setMcpArgsText(e.target.value)}
                      placeholder="-y @modelcontextprotocol/server-memory"
                    />
                  </label>
                </div>
              )}

              <div className="agent-form-footer">
                {mcpEditId && (
                  <button
                    type="button"
                    className="btn-ghost"
                    onClick={() => {
                      setMcpEditId(null)
                      setMcpName('')
                      setMcpEndpoint('')
                      setMcpArgsText('')
                      setMcpToken('')
                    }}
                  >
                    取消编辑
                  </button>
                )}
                <button type="button" className="btn-primary" onClick={handleSaveMcp}>
                  保存扩展插件
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
