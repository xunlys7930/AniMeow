import { useEffect, useMemo, useRef, useState } from 'react'
import { formatCarrierLabel, studioApi } from '../api'
import { WorkReferencesPanel } from './WorkReferencesPanel'
import type {
  CellPatch,
  CommunityPublishResult,
  CuratorItem,
  PublicCloudSessionInfo
} from '../types'

interface ReviewStudioTabProps {
  item: CuratorItem
  mediaBaseUrl: string
  captureRevision?: number
  onUpdateCell: (patch: CellPatch) => Promise<void>
  onOpenCloudAccount?: () => void
}

/** 轻量无依赖 Markdown 预览渲染（支持标题、引用、无序列表、粗体、行内代码与段落） */
function renderMarkdownBlocks(src: string) {
  const lines = src.replace(/\r\n/g, '\n').split('\n')
  return lines.map((line, idx) => {
    const trimmed = line.trim()
    if (!trimmed) {
      return <div key={idx} style={{ height: 8 }} />
    }
    if (trimmed.startsWith('### ')) {
      return (
        <h4 key={idx} style={{ margin: '10px 0 4px', fontSize: '1.0769rem' }}>
          {trimmed.slice(4)}
        </h4>
      )
    }
    if (trimmed.startsWith('## ')) {
      return (
        <h3 key={idx} style={{ margin: '12px 0 6px', fontSize: '1.2308rem' }}>
          {trimmed.slice(3)}
        </h3>
      )
    }
    if (trimmed.startsWith('# ')) {
      return (
        <h2 key={idx} style={{ margin: '14px 0 8px', fontSize: '1.3846rem' }}>
          {trimmed.slice(2)}
        </h2>
      )
    }
    if (trimmed.startsWith('> ')) {
      return (
        <blockquote
          key={idx}
          style={{
            margin: '6px 0',
            padding: '4px 10px',
            borderLeft: '3px solid var(--accent)',
            background: 'var(--bg-elevated)',
            color: 'var(--text-secondary)'
          }}
        >
          {trimmed.slice(2)}
        </blockquote>
      )
    }
    if (trimmed.startsWith('- ') || trimmed.startsWith('* ')) {
      return (
        <div key={idx} style={{ paddingLeft: 14, margin: '3px 0' }}>
          • {trimmed.slice(2)}
        </div>
      )
    }
    return (
      <p key={idx} style={{ margin: '5px 0', lineHeight: 1.65 }}>
        {line}
      </p>
    )
  })
}

export function ReviewStudioTab({
  item,
  mediaBaseUrl,
  captureRevision = 0,
  onUpdateCell,
  onOpenCloudAccount
}: ReviewStudioTabProps) {
  const [draftReview, setDraftReview] = useState<string>(item.review)
  const [draftMemo, setDraftMemo] = useState<string>(item.memo ?? '')
  const [postTitle, setPostTitle] = useState<string>(`《${item.title}》观后漫评`)
  const [spoilerWarning, setSpoilerWarning] = useState<boolean>(false)
  const [attachScore, setAttachScore] = useState<boolean>(item.personalScore != null)
  const [showPublishSheet, setShowPublishSheet] = useState<boolean>(false)
  const [cloudSession, setCloudSession] = useState<PublicCloudSessionInfo | null>(null)
  const [publishing, setPublishing] = useState<boolean>(false)
  const [publishResult, setPublishResult] = useState<CommunityPublishResult | null>(null)
  const [publishError, setPublishError] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [showLeftPanel, setShowLeftPanel] = useState<boolean>(true)
  const [showPreviewPanel, setShowPreviewPanel] = useState<boolean>(true)
  const editorRef = useRef<HTMLTextAreaElement | null>(null)

  useEffect(() => {
    setDraftReview(item.review)
    setDraftMemo(item.memo ?? '')
    setPostTitle(`《${item.title}》观后漫评`)
    setAttachScore(item.personalScore != null)
    setPublishResult(null)
    setPublishError(null)
  }, [item.kind, item.id])

  useEffect(() => {
    if (!showPublishSheet) return
    let cancelled = false
    studioApi
      .getCloudSession()
      .then((info) => {
        if (!cancelled) setCloudSession(info)
      })
      .catch(() => {
        if (!cancelled) setCloudSession(null)
      })
    return () => {
      cancelled = true
    }
  }, [showPublishSheet])

  const charCount = useMemo(() => draftReview.replace(/\s/g, '').length, [draftReview])

  const showTempStatus = (msg: string, durationMs = 2800) => {
    setStatusMessage(msg)
    window.setTimeout(() => setStatusMessage(null), durationMs)
  }

  const handleInsertQuote = (snippet: string) => {
    const el = editorRef.current
    if (!el) {
      setDraftReview((prev) => (prev.trim() ? `${prev.trimEnd()}\n\n${snippet}\n` : `${snippet}\n`))
      showTempStatus('已将考据引用插入漫评末尾')
      return
    }
    const start = el.selectionStart ?? draftReview.length
    const end = el.selectionEnd ?? draftReview.length
    const before = draftReview.slice(0, start)
    const after = draftReview.slice(end)
    const leadingBreak = before.length > 0 && !before.endsWith('\n\n') ? (before.endsWith('\n') ? '\n' : '\n\n') : ''
    const trailingBreak = after.length > 0 && !after.startsWith('\n') ? '\n\n' : '\n'
    const inserted = `${leadingBreak}${snippet}${trailingBreak}`
    const nextText = `${before}${inserted}${after}`
    setDraftReview(nextText)
    showTempStatus('已将考据引用插入漫评正文')
    window.requestAnimationFrame(() => {
      el.focus()
      const caret = before.length + inserted.length
      el.setSelectionRange(caret, caret)
    })
  }

  const handleSaveToVault = async () => {
    const patch: CellPatch = {
      kind: item.kind,
      id: item.id,
      review: draftReview
    }
    if (item.kind === 'anime' || item.kind === 'book') {
      patch.memo = draftMemo
    }
    await onUpdateCell(patch)
    showTempStatus('已保存长评与备注')
  }

  const handleExportMarkdown = async () => {
    const safeName = item.title.replace(/[\\/:*?"<>|]/g, '_')
    const targetPath = await studioApi.pickSaveMarkdown(`${safeName}_漫评手稿.md`)
    if (!targetPath) return

    const mdPayload = [
      '---',
      `title: "${item.title.replace(/"/g, '\\"')}"`,
      `carrier: "${formatCarrierLabel(item.kind)}"`,
      `status: "${item.status}"`,
      `personal_score: ${item.personalScore != null ? item.personalScore.toFixed(1) : 'null'}`,
      `exported_from: "AniMeow"`,
      '---',
      '',
      `# 《${item.title}》漫评手稿`,
      '',
      draftReview || '（暂无正文）',
      ''
    ].join('\n')

    await studioApi.exportMarkdownReview(targetPath, mdPayload)
    showTempStatus(`已导出 Markdown 手稿：${targetPath}`, 3400)
  }

  const handlePrintCard = () => {
    window.print()
  }

  const handlePublishToCommunity = async () => {
    const trimmedTitle = postTitle.trim()
    const trimmedBody = draftReview.trim()
    if (!trimmedTitle) {
      setPublishError('请先填写社区帖子标题')
      return
    }
    if (!trimmedBody) {
      setPublishError('请先撰写漫评正文内容后再发布')
      return
    }

    setPublishing(true)
    setPublishError(null)
    setPublishResult(null)
    try {
      const res = await studioApi.publishCommunityReview({
        headline: trimmedTitle,
        workTitle: item.title,
        carrierLabel: formatCarrierLabel(item.kind),
        personalScore: attachScore ? item.personalScore : null,
        spoilerWarning,
        reviewMarkdown: trimmedBody
      })
      setPublishResult(res)
      showTempStatus(`漫评已投递至追番喵社区（帖子 #${res.postId}）`, 4000)
    } catch (err) {
      setPublishError(err instanceof Error ? err.message : String(err))
    } finally {
      setPublishing(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div className="sheet-toolbar">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <span style={{ fontWeight: 600 }}>三栏漫评室</span>
          <span className="badge">{formatCarrierLabel(item.kind)}</span>
          <span style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {item.title}
          </span>
          <span className="badge tabular">净字数 {charCount}</span>
          {statusMessage && (
            <span style={{ color: 'var(--positive)', fontSize: '0.9231rem', fontWeight: 600 }}>
              {statusMessage}
            </span>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0, flexWrap: 'wrap' }}>
          <button
            className={`btn ${showLeftPanel ? '' : 'btn-ghost'}`}
            onClick={() => setShowLeftPanel((v) => !v)}
            title="收起或展开左侧素材与参考栏，给正文留出更多空间"
          >
            {showLeftPanel ? '收起素材栏' : '展开素材栏'}
          </button>
          <button
            className={`btn ${showPreviewPanel ? '' : 'btn-ghost'}`}
            onClick={() => setShowPreviewPanel((v) => !v)}
            title="收起或展开右侧实时排版预览栏"
          >
            {showPreviewPanel ? '收起预览栏' : '展开预览栏'}
          </button>
          <button className="btn" onClick={() => void handleSaveToVault()}>
            保存手稿
          </button>
          <button className="btn" onClick={() => void handleExportMarkdown()}>
            导出 Markdown
          </button>
          <button className="btn" onClick={handlePrintCard} title="调用系统打印或另存为 PDF 分享长卡">
            打印 / PDF
          </button>
          <button
            className="btn btn-primary"
            onClick={() => setShowPublishSheet((prev) => !prev)}
          >
            {showPublishSheet ? '收起发布' : '发布到社区'}
          </button>
        </div>
      </div>

      {showPublishSheet && (
        <div
          style={{
            padding: '12px 16px',
            background: 'var(--bg-elevated)',
            borderBottom: '1px solid var(--border-strong)',
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
            userSelect: 'text'
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
            <div style={{ fontWeight: 700, fontSize: '1rem' }}>
              单向发布到追番喵社区（桌面端不内置刷帖信息流，仅作为深度长评投递出口）
            </div>
            <button className="btn btn-ghost" onClick={() => setShowPublishSheet(false)}>
              收起
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 10, alignItems: 'center' }}>
            <input
              className="inline-input"
              value={postTitle}
              onChange={(e) => setPostTitle(e.target.value)}
              placeholder="社区帖子标题（例如：《作品名》观后深度漫评）"
            />
            <span className="badge">
              携带作品卡片：{item.title} · 评分{' '}
              {attachScore && item.personalScore != null ? `${item.personalScore.toFixed(1)} / 10` : '不附带评分'}
            </span>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 10 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', fontSize: '0.9231rem' }}>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={spoilerWarning}
                  onChange={(e) => setSpoilerWarning(e.target.checked)}
                />
                包含关键剧透（社区内自动折叠剧透提示）
              </label>

              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={attachScore}
                  disabled={item.personalScore == null}
                  onChange={(e) => setAttachScore(e.target.checked)}
                />
                附带个人评分（{item.personalScore != null ? item.personalScore.toFixed(1) : '未评分'}）
              </label>

              <span style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                注：私密备注 (memo) 绝不会随帖上传
              </span>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {cloudSession?.loggedIn ? (
                <>
                  <span className="badge badge-vault">
                    已登录：{cloudSession.username ?? `UID ${cloudSession.userId}`}
                  </span>
                  <button
                    className="btn btn-primary"
                    disabled={publishing || !draftReview.trim() || !postTitle.trim()}
                    onClick={() => void handlePublishToCommunity()}
                  >
                    {publishing ? '正在投递漫评...' : '确认发布到社区'}
                  </button>
                </>
              ) : (
                <>
                  <span style={{ color: 'var(--warning)', fontSize: '0.9231rem' }}>
                    尚未登录追番喵云账号，请先登录后发布
                  </span>
                  {onOpenCloudAccount && (
                    <button className="btn btn-primary" onClick={onOpenCloudAccount}>
                      前往「05 · 媒体与对账」登录云账号
                    </button>
                  )}
                </>
              )}
            </div>
          </div>

          {publishError && (
            <div style={{ color: 'var(--danger)', fontSize: '0.9231rem', fontWeight: 600 }}>
              发布失败：{publishError}
            </div>
          )}

          {publishResult && (
            <div
              style={{
                padding: '7px 10px',
                borderRadius: 'var(--radius-sm)',
                background: 'var(--bg-canvas)',
                border: '1px solid var(--positive)',
                color: 'var(--positive)',
                fontSize: '0.9231rem',
                fontWeight: 600
              }}
            >
              发布成功：帖子编号 <span className="mono">#{publishResult.postId}</span>
              {publishResult.statusNote ? ` · ${publishResult.statusNote}` : ''}
            </div>
          )}
        </div>
      )}

      <div
        style={{
          flex: 1,
          minHeight: 0,
          display: 'grid',
          gridTemplateColumns:
            showLeftPanel && showPreviewPanel
              ? 'minmax(250px, 290px) minmax(0, 1.15fr) minmax(0, 1fr)'
              : showLeftPanel
                ? 'minmax(250px, 290px) minmax(0, 1fr)'
                : showPreviewPanel
                  ? 'minmax(0, 1.15fr) minmax(0, 1fr)'
                  : 'minmax(0, 1fr)'
        }}
      >
        {/* 左栏：作品参照、私密考据与跨端素材引用（可独立收起） */}
        {showLeftPanel && (
        <div
          style={{
            borderRight: '1px solid var(--border-subtle)',
            padding: 14,
            overflowY: 'auto',
            display: 'flex',
            flexDirection: 'column',
            gap: 14,
            background: 'var(--bg-panel)',
            userSelect: 'text'
          }}
        >
          <div>
            <div style={{ fontWeight: 600, marginBottom: 6 }}>作品参照卡片</div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', lineHeight: 1.6 }}>
              <div>原名：{item.originalTitle || '—'}</div>
              <div>制作/社团：{item.studioOrCircle || '—'}</div>
              <div>当前状态：{item.status}</div>
              <div>
                评分对照：个人 {item.personalScore != null ? item.personalScore.toFixed(1) : '—'} / 均分{' '}
                {item.publicScore != null ? item.publicScore.toFixed(1) : '—'}
              </div>
            </div>
          </div>

          {(item.kind === 'anime' || item.kind === 'book') && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontWeight: 600 }}>私密考据速记 (memo)</div>
              <div style={{ color: 'var(--text-muted)', fontSize: '0.8846rem' }}>
                此栏仅保存在私人备份库中，导出公开漫评或发布到社区时不会附带。
              </div>
              <textarea
                className="inline-input"
                style={{ minHeight: 108, padding: 8, resize: 'vertical', lineHeight: 1.55 }}
                value={draftMemo}
                onChange={(e) => setDraftMemo(e.target.value)}
                placeholder="分集作画监督、台词出处、剧情时间线梳理..."
              />
            </div>
          )}

          <div style={{ borderTop: '1px solid var(--border-subtle)', paddingTop: 12 }}>
            <WorkReferencesPanel
              workKind={item.kind}
              workId={item.id}
              mediaBaseUrl={mediaBaseUrl}
              onInsertQuote={handleInsertQuote}
              revision={captureRevision}
            />
          </div>
        </div>
        )}

        {/* 中栏：沉浸式 Markdown 正文编辑器 */}
        <div
          style={{
            borderRight: showPreviewPanel ? '1px solid var(--border-subtle)' : 'none',
            display: 'flex',
            flexDirection: 'column',
            minHeight: 0
          }}
        >
          <div
            style={{
              padding: '6px 12px',
              borderBottom: '1px solid var(--border-subtle)',
              fontSize: '0.8846rem',
              color: 'var(--text-muted)'
            }}
          >
            Markdown 正文编辑区（支持 # 标题、&gt; 引用、- 列表；左侧素材可一键插入引用）
          </div>
          <textarea
            ref={editorRef}
            value={draftReview}
            onChange={(e) => setDraftReview(e.target.value)}
            placeholder={`在此撰写《${item.title}》的深度长评...`}
            style={{
              flex: 1,
              width: '100%',
              border: 'none',
              outline: 'none',
              resize: 'none',
              padding: 18,
              background: 'var(--bg-canvas)',
              color: 'var(--text-primary)',
              fontSize: '1.0385rem',
              lineHeight: 1.7
            }}
          />
        </div>

        {/* 右栏：实时排版预览区（可独立收起；打印/PDF 时自动输出） */}
        {showPreviewPanel && (
        <div
          className="review-print-sheet"
          style={{
            padding: 22,
            overflowY: 'auto',
            background: 'var(--bg-canvas)',
            userSelect: 'text'
          }}
        >
          <div
            style={{
              paddingBottom: 12,
              marginBottom: 14,
              borderBottom: '1px solid var(--border-strong)'
            }}
          >
            <div className="heading-font" style={{ fontSize: '1.4615rem', fontWeight: 700 }}>
              《{item.title}》
            </div>
            <div style={{ color: 'var(--text-secondary)', fontSize: '0.9231rem', marginTop: 4 }}>
              {formatCarrierLabel(item.kind)} · 状态：{item.status} · 个人评分：
              {item.personalScore != null ? `${item.personalScore.toFixed(1)} / 10` : '未评分'}
            </div>
          </div>
          {draftReview.trim() ? (
            <div>{renderMarkdownBlocks(draftReview)}</div>
          ) : (
            <div style={{ color: 'var(--text-muted)' }}>在左侧输入 Markdown 正文后，此处实时渲染排版效果。</div>
          )}
        </div>
        )}
      </div>
    </div>
  )
}
