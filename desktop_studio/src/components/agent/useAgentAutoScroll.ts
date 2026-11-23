import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import type React from 'react'

export interface UseAgentAutoScrollOptions {
  feedRef: React.RefObject<HTMLDivElement | null>
  /** 抽屉当前是否处于可见展开态 */
  visible: boolean
  /** 当前会话 ID */
  sessionKey: string | null
  /** 每次旧会话消息加载完毕自增，用于首帧前瞬时定格到底部 */
  sessionLoadSeq: number
  /** 当前是否处于流式生成中 */
  isStreaming: boolean
  /** 消息与流式内容更新触发信号 */
  messageCount: number
  streamingContentLen: number
  streamingReasoningLen: number
  streamingTracesCount: number
  streamingPendingTool: string | null
  streamingStepText: string | null
}

const PIN_THRESHOLD_PX = 40
const UNPIN_THRESHOLD_PX = 76

export function useAgentAutoScroll({
  feedRef,
  visible,
  sessionKey,
  sessionLoadSeq,
  isStreaming,
  messageCount,
  streamingContentLen,
  streamingReasoningLen,
  streamingTracesCount,
  streamingPendingTool,
  streamingStepText
}: UseAgentAutoScrollOptions) {
  const isPinnedRef = useRef(true)
  const programmaticGuardUntilRef = useRef(0)
  const prevMessageCountRef = useRef(messageCount)

  const [showScrollToBottom, setShowScrollToBottom] = useState(false)
  const [hasUnreadBelow, setHasUnreadBelow] = useState(false)

  const snapToBottomInstant = useCallback(() => {
    const el = feedRef.current
    if (!el) return
    programmaticGuardUntilRef.current = performance.now() + 90
    el.scrollTop = el.scrollHeight
  }, [feedRef])

  const scrollToBottom = useCallback(
    (smooth = true) => {
      const el = feedRef.current
      isPinnedRef.current = true
      setShowScrollToBottom(false)
      setHasUnreadBelow(false)
      if (!el) return
      programmaticGuardUntilRef.current = performance.now() + (smooth ? 280 : 90)
      if (smooth) {
        el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
      } else {
        el.scrollTop = el.scrollHeight
      }
    },
    [feedRef]
  )

  // 进入旧对话 / 切换会话 / 从悬浮或关闭态展开抽屉：在首帧绘制前直接落位到底部，零晃眼滚动
  useLayoutEffect(() => {
    if (!visible) return
    isPinnedRef.current = true
    setShowScrollToBottom(false)
    setHasUnreadBelow(false)
    prevMessageCountRef.current = messageCount
    snapToBottomInstant()

    // 兜底一次 rAF，防止复杂卡片/表格二次排版微增高度后没完全贴底
    const rafId = window.requestAnimationFrame(() => {
      if (isPinnedRef.current) {
        snapToBottomInstant()
      }
    })
    return () => window.cancelAnimationFrame(rafId)
  }, [visible, sessionKey, sessionLoadSeq, snapToBottomInstant])

  // 流式输出或新消息到来：只有当用户仍处于底部吸附态时才跟随；若已往上翻看，绝不抢回滚动条
  useLayoutEffect(() => {
    if (!visible) return
    const grew = messageCount > prevMessageCountRef.current
    prevMessageCountRef.current = messageCount

    if (isPinnedRef.current) {
      snapToBottomInstant()
      return
    }
    if (isStreaming || grew) {
      setHasUnreadBelow(true)
      setShowScrollToBottom(true)
    }
  }, [
    visible,
    isStreaming,
    messageCount,
    streamingContentLen,
    streamingReasoningLen,
    streamingTracesCount,
    streamingPendingTool,
    streamingStepText,
    snapToBottomInstant
  ])

  const handleFeedWheel = useCallback(
    (e: React.WheelEvent<HTMLDivElement>) => {
      if (e.deltaY < 0) {
        isPinnedRef.current = false
        const el = feedRef.current
        if (el) {
          const distance = el.scrollHeight - el.scrollTop - el.clientHeight
          if (distance > 16) {
            setShowScrollToBottom(true)
          }
        }
      }
    },
    [feedRef]
  )

  const handleFeedScroll = useCallback(() => {
    const el = feedRef.current
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    if (distance <= PIN_THRESHOLD_PX) {
      isPinnedRef.current = true
      setShowScrollToBottom(false)
      setHasUnreadBelow(false)
      return
    }
    if (performance.now() < programmaticGuardUntilRef.current) {
      return
    }
    if (distance > UNPIN_THRESHOLD_PX) {
      isPinnedRef.current = false
      setShowScrollToBottom(true)
    }
  }, [feedRef])

  return {
    showScrollToBottom,
    hasUnreadBelow,
    scrollToBottom,
    handleFeedScroll,
    handleFeedWheel
  }
}
