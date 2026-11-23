import React from 'react'
import { formatBytes, studioApi } from '../api'
import type { DesktopUpdaterController } from '../hooks/useDesktopUpdater'
import { LoadingSpinner } from './LoadingIndicator'

interface DesktopUpdateCardProps {
  updater: DesktopUpdaterController
  compact?: boolean
  onNotice?: (message: string) => void
}

/**
 * 桌面端应用内自动更新卡片（在「设置 → 数据与关于」与「同步中心」复用）。
 * 支持签名下载与重启安装，官网手动下载由用户单独选择。
 */
export const DesktopUpdateCard: React.FC<DesktopUpdateCardProps> = ({
  updater,
  compact = false,
  onNotice
}) => {
  const { state } = updater
  const isBusy =
    state.status === 'checking' ||
    state.status === 'downloading' ||
    state.status === 'verifying' ||
    state.status === 'installing'

  const percent =
    typeof state.percent === 'number' && Number.isFinite(state.percent)
      ? Math.max(0, Math.min(100, Math.round(state.percent)))
      : null

  return (
    <div
      style={{
        padding: compact ? 12 : 14,
        borderRadius: 'var(--radius-sm)',
        background: 'var(--bg-canvas)',
        border: '1px solid var(--border-subtle)',
        display: 'flex',
        flexDirection: 'column',
        gap: 10
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontWeight: 700, fontSize: compact ? '0.9615rem' : '1.0385rem' }}>
            桌面端应用内更新
          </span>
          {state.status === 'ready' && (
            <span className="badge" style={{ color: 'var(--positive)', fontWeight: 700 }}>
              已就绪
            </span>
          )}
          {state.status === 'available' && (
            <span className="badge" style={{ color: 'var(--accent)', fontWeight: 700 }}>
              有新版本
            </span>
          )}
        </div>
        <span className="badge mono">当前 v{state.currentVersion}</span>
      </div>

      <div style={{ fontSize: '0.9231rem', color: 'var(--text-secondary)', lineHeight: 1.55 }}>
        {state.status === 'idle' &&
          '发现新版本后，可在应用内直接下载并重启安装。'}
        {state.status === 'checking' && (
          <LoadingSpinner size="sm" inline label="正在检查新版本..." />
        )}
        {state.status === 'up_to_date' && (
          <span style={{ color: 'var(--positive)', fontWeight: 600 }}>
            当前已是最新版本。
          </span>
        )}
        {state.status === 'error' && (
          <span role="status" style={{ color: 'var(--danger)' }}>{state.error}</span>
        )}
        {(state.status === 'available' ||
          state.status === 'downloading' ||
          state.status === 'verifying' ||
          state.status === 'ready' ||
          state.status === 'installing') && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ color: 'var(--accent)', fontWeight: 700 }}>
              发现新版本 v{state.targetVersion}
            </div>
            {state.notes && (
              <div
                style={{
                  whiteSpace: 'pre-wrap',
                  color: 'var(--text-primary)',
                  background: 'var(--bg-surface)',
                  border: '1px solid var(--border-subtle)',
                  borderRadius: 'var(--radius-sm)',
                  padding: '8px 10px',
                  fontSize: '0.9231rem',
                  maxHeight: 120,
                  overflowY: 'auto'
                }}
              >
                {state.notes}
              </div>
            )}
          </div>
        )}
      </div>

      {(state.status === 'downloading' || state.status === 'verifying' || state.status === 'ready') && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div
            style={{
              height: 6,
              width: '100%',
              borderRadius: 999,
              background: 'var(--bg-surface-alt)',
              overflow: 'hidden',
              border: '1px solid var(--border-subtle)'
            }}
          >
            <div
              style={{
                height: '100%',
                width: `${percent ?? 100}%`,
                background: state.status === 'ready' ? 'var(--positive)' : 'var(--accent)',
                transition: 'width 180ms ease'
              }}
            />
          </div>
          <div
            className="mono"
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: '0.8462rem',
              color: 'var(--text-secondary)'
            }}
          >
            <span>
              {state.status === 'downloading' &&
                `正在下载新版本：${formatBytes(state.downloadedBytes)}${state.totalBytes ? ` / ${formatBytes(state.totalBytes)}` : ''}`}
              {state.status === 'verifying' && '下载完成，正在校验安装包...'}
              {state.status === 'ready' &&
                '新版本已就绪，点击即可重启安装。'}
            </span>
            {percent !== null && <span>{percent}%</span>}
          </div>
        </div>
      )}

      {state.status === 'installing' && (
        <LoadingSpinner size="sm" inline label="正在重启并安装新版本，软件稍后会自动重新打开..." />
      )}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <button
          type="button"
          className="btn"
          disabled={isBusy}
          onClick={() => void updater.checkNow(false)}
        >
          {state.status === 'checking'
            ? '正在检查...'
            : state.status === 'error'
              ? '重试检查更新'
              : '检查更新'}
        </button>

        {state.status === 'available' && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={isBusy}
            onClick={() => void updater.downloadUpdate()}
          >
            应用内下载更新
          </button>
        )}

        {state.status === 'ready' && (
          <button
            type="button"
            className="btn btn-primary"
            disabled={isBusy}
            onClick={() => void updater.installAndRestart()}
          >
            立即重启并更新
          </button>
        )}

        {state.status === 'error' && (
          <button
            type="button"
            className="btn"
            onClick={() => {
              void studioApi
                .openHttpsUrl('https://www.xunlys.top/#download')
                .then(() => onNotice?.('已在浏览器打开官网的下载区'))
                .catch((err) => onNotice?.(err instanceof Error ? err.message : String(err)))
            }}
          >
            前往官网手动下载
          </button>
        )}
      </div>
    </div>
  )
}
