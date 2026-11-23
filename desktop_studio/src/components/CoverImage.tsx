import React, { useEffect, useMemo, useState } from 'react'
import { resolveCoverCandidates } from '../api'

interface CoverImageProps {
  coverUrl: string
  mediaBaseUrl?: string
  alt: string
  className?: string
  style?: React.CSSProperties
  fallback?: React.ReactNode
}

/**
 * 统一封面图片组件：
 * - 自动走 Bangumi 镜像优先 + 官方回退链；
 * - 默认禁用 Referer 防止外链防盗链拦截；
 * - 所有候选地址均失效时优雅回退为文字封面，不展示破图图标。
 */
export const CoverImage: React.FC<CoverImageProps> = ({
  coverUrl,
  mediaBaseUrl = '',
  alt,
  className,
  style,
  fallback = null,
}) => {
  const candidates = useMemo(
    () => resolveCoverCandidates(coverUrl, mediaBaseUrl),
    [coverUrl, mediaBaseUrl],
  )
  const [idx, setIdx] = useState(0)

  useEffect(() => {
    setIdx(0)
  }, [coverUrl, mediaBaseUrl])

  const src = candidates[idx]
  if (!src) {
    return <>{fallback}</>
  }

  return (
    <img
      src={src}
      alt={alt}
      className={className}
      style={style}
      loading="lazy"
      decoding="async"
      draggable={false}
      referrerPolicy="no-referrer"
      onError={() => {
        setIdx((prev) => prev + 1)
      }}
    />
  )
}
