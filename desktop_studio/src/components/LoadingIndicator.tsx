import React from 'react'

interface LoadingSpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  label?: string
  inline?: boolean
}

/** 轻量旋转等待环，支持行内与块级两种排布。 */
export const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  size = 'md',
  label,
  inline = false
}) => {
  const cls = `studio-spinner studio-spinner-${size}`
  if (inline) {
    return (
      <span className="studio-inline-loading" role="status" aria-live="polite">
        <span className={cls} aria-hidden="true" />
        {label && <span>{label}</span>}
      </span>
    )
  }
  return (
    <div className="studio-loading-block" role="status" aria-live="polite">
      <span className={cls} aria-hidden="true" />
      {label && <span className="studio-loading-label">{label}</span>}
    </div>
  )
}

interface WaitingBannerProps {
  message: string
  subtext?: string
}

/** 带顶部流动进度条与旋转指示器的任务等待横幅。 */
export const WaitingBanner: React.FC<WaitingBannerProps> = ({ message, subtext }) => (
  <div className="studio-waiting-banner" role="status" aria-live="polite">
    <div className="studio-progress-track" aria-hidden="true">
      <div className="studio-progress-bar" />
    </div>
    <div className="studio-waiting-banner-body">
      <span className="studio-spinner studio-spinner-sm" aria-hidden="true" />
      <div style={{ minWidth: 0 }}>
        <div style={{ fontWeight: 600, fontSize: '1rem' }}>{message}</div>
        {subtext && (
          <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', marginTop: 2 }}>{subtext}</div>
        )}
      </div>
    </div>
  </div>
)

interface SkeletonCardsProps {
  count?: number
  minWidth?: number
}

/** 卡片骨架屏（用于新番表、画册与在线搜索等待状态）。 */
export const SkeletonCards: React.FC<SkeletonCardsProps> = ({ count = 8, minWidth = 220 }) => (
  <div
    className="studio-skeleton-grid"
    style={{ gridTemplateColumns: `repeat(auto-fill, minmax(${minWidth}px, 1fr))` }}
    aria-hidden="true"
  >
    {Array.from({ length: count }).map((_, idx) => (
      <div key={idx} className="studio-skeleton-card">
        <div className="studio-skeleton-thumb" />
        <div className="studio-skeleton-lines">
          <div className="studio-skeleton-line" style={{ width: '78%' }} />
          <div className="studio-skeleton-line" style={{ width: '52%' }} />
          <div className="studio-skeleton-line" style={{ width: '64%', height: 10 }} />
        </div>
      </div>
    ))}
  </div>
)

interface SkeletonRowsProps {
  count?: number
}

/** 列表骨架屏（用于检索结果、线路加载与回收站/查重等待状态）。 */
export const SkeletonRows: React.FC<SkeletonRowsProps> = ({ count = 4 }) => (
  <div className="studio-skeleton-rows" aria-hidden="true">
    {Array.from({ length: count }).map((_, idx) => (
      <div key={idx} className="studio-skeleton-row">
        <div className="studio-skeleton-avatar" />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 7 }}>
          <div className="studio-skeleton-line" style={{ width: `${55 + ((idx * 17) % 30)}%` }} />
          <div className="studio-skeleton-line" style={{ width: `${32 + ((idx * 13) % 28)}%`, height: 10 }} />
        </div>
      </div>
    ))}
  </div>
)
