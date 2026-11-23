import type { SVGProps } from 'react'

export interface AgentIconProps extends SVGProps<SVGSVGElement> {
  size?: number
}

function baseProps(size: number, props: AgentIconProps): SVGProps<SVGSVGElement> {
  const { size: _size, style, ...rest } = props
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.85,
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
    'aria-hidden': true,
    style: { display: 'inline-block', verticalAlign: '-0.16em', flexShrink: 0, ...style },
    ...rest
  }
}

/** 四芒星：AI 助手 / 提炼 */
export function IconSpark({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M12 3l2.2 6.8L21 12l-6.8 2.2L12 21l-2.2-6.8L3 12l6.8-2.2L12 3z" />
    </svg>
  )
}

/** 神经网络节点：长期记忆 / LLM 推理 */
export function IconBrain({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M9.5 4.5a3 3 0 0 0-5.4 1.8A3.2 3.2 0 0 0 3 8.8a3.2 3.2 0 0 0 1.4 2.7A3 3 0 0 0 6.8 17a2.8 2.8 0 0 0 2.7 2.5h.5v-15h-.5z" />
      <path d="M14.5 4.5a3 3 0 0 1 5.4 1.8A3.2 3.2 0 0 1 21 8.8a3.2 3.2 0 0 1-1.4 2.7A3 3 0 0 1 17.2 17a2.8 2.8 0 0 1-2.7 2.5H14v-15h.5z" />
    </svg>
  )
}

/** 插头接口：MCP 外部扩展 */
export function IconPlug({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M9 3v5" />
      <path d="M15 3v5" />
      <path d="M6 8h12v3a6 6 0 0 1-12 0V8z" />
      <path d="M12 17v4" />
    </svg>
  )
}

/** 调节滑块：模型与策略配置 / 桌面设置 */
export function IconSliders({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <line x1="4" y1="6" x2="20" y2="6" />
      <circle cx="9" cy="6" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <circle cx="15" cy="12" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="18" x2="20" y2="18" />
      <circle cx="11" cy="18" r="2" fill="currentColor" stroke="none" />
    </svg>
  )
}

/** 心电脉冲波形：AI 运行记录 */
export function IconActivity({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <polyline points="3 12 7 12 10 5 14 19 17 12 21 12" />
    </svg>
  )
}

/** 摘要文档：滚动摘要 */
export function IconSummary({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M6 3h9l4 4v14H6z" />
      <polyline points="15 3 15 7 19 7" />
      <line x1="9" y1="12" x2="16" y2="12" />
      <line x1="9" y1="16" x2="14" y2="16" />
    </svg>
  )
}

/** 盾牌：防剧透锁 / 护栏熔断 */
export function IconShield({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6l7-3z" />
    </svg>
  )
}

/** 闪电折线：UI 动作 / KV 缓存命中 */
export function IconBolt({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <polygon points="13 2 4 14 12 14 11 22 20 10 12 10 13 2" />
    </svg>
  )
}

/** 三角警示：错误 / 异常诊断 */
export function IconWarning({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M10.3 3.6L2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.6a2 2 0 0 0-3.4 0z" />
      <line x1="12" y1="9" x2="12" y2="13.5" />
      <circle cx="12" cy="17" r="0.8" fill="currentColor" stroke="none" />
    </svg>
  )
}

/** 归档盒：滚动压缩 / 溢写 Artifact */
export function IconBox({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M3 7l9-4 9 4-9 4-9-4z" />
      <path d="M3 7v10l9 4 9-4V7" />
      <line x1="12" y1="11" x2="12" y2="21" />
    </svg>
  )
}

/** 扳手：工具调用 */
export function IconWrench({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M14.7 6.3a4.5 4.5 0 0 0-5.8 5.8L4 17l3 3 4.9-4.9a4.5 4.5 0 0 0 5.8-5.8l-2.6 2.6-2.2-2.2 1.8-3.4z" />
    </svg>
  )
}

/** 剪贴板：复制报告 / 粘贴 JSON */
export function IconClipboard({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <rect x="8" y="2.5" width="8" height="3.5" rx="1" />
      <path d="M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2" />
    </svg>
  )
}

/** 图钉：置顶对话 */
export function IconPin({ size = 13, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <path d="M15 4l5 5-3 2-2 4-4-4-5 5 5-5-4-4 4-2 2-3z" />
    </svg>
  )
}

/** 放大镜：一键检测可用性 */
export function IconSearch({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <circle cx="11" cy="11" r="6.5" />
      <line x1="16" y1="16" x2="21" y2="21" />
    </svg>
  )
}

/** 对勾：完成 */
export function IconCheck({ size = 14, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <polyline points="4 12.5 9.5 18 20 6" />
    </svg>
  )
}

/** 播放三角：轮次启动 */
export function IconPlay({ size = 13, ...props }: AgentIconProps) {
  return (
    <svg {...baseProps(size, props)}>
      <polygon points="7 4 19 12 7 20 7 4" />
    </svg>
  )
}
