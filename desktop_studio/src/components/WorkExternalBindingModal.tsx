import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { studioApi } from '../api'
import type {
  CuratorItem, ExternalBindingMode, ExternalMetadataField, RemoteCatalogHit, WorkExternalSource,
} from '../types'
import { StudioSelect } from './StudioSelect'
import { useDraggablePanel, useFloatingLayer } from '../utils/floatingLayer'

const labels: Record<WorkExternalSource, string> = { bangumi: 'Bangumi', vndb: 'VNDB', dlsite: 'DLsite' }
const sourcesByKind: Record<CuratorItem['kind'], WorkExternalSource[]> = {
  anime: ['bangumi'], book: ['bangumi'], game: ['bangumi', 'vndb'], audio: ['dlsite', 'bangumi'],
}
const fieldOptions: [ExternalMetadataField, string][] = [
  ['cover_url', '封面'], ['original_title', '原名'], ['release_date', '日期'], ['studio', '制作方'],
  ['total_episodes', '总集数或话数'], ['tags', '标签'], ['synopsis', '简介'],
]

function bindingUrl(source: WorkExternalSource, id: string) {
  if (source === 'bangumi') return 'https://bgm.tv/subject/' + encodeURIComponent(id)
  if (source === 'vndb') return 'https://vndb.org/' + encodeURIComponent(id)
  return 'https://www.dlsite.com/maniax/work/=/product_id/' + encodeURIComponent(id) + '.html'
}

export function WorkExternalBindingCard({ item, onRefresh }: {
  item: CuratorItem
  onRefresh: () => Promise<void>
}) {
  const [editing, setEditing] = useState<WorkExternalSource | null>(null)
  const [confirm, setConfirm] = useState<WorkExternalSource | null>(null)
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState('')
  const sources = sourcesByKind[item.kind]
  const active = sources.flatMap((source) => {
    const id = item.externalSource === source ? item.externalId : source === 'bangumi' ? item.secondaryBangumiId : null
    return id ? [{ source, id }] : []
  })

  async function save(source: WorkExternalSource, candidate: RemoteCatalogHit | null, mode: ExternalBindingMode, fields: ExternalMetadataField[]) {
    const warning = await studioApi.updateWorkExternalBinding({
      kind: item.kind, id: item.id, source, externalId: candidate?.externalId ?? null, mode, fields,
    }, candidate ?? undefined)
    try {
      await onRefresh()
      setFeedback(warning ?? (candidate ? '外部条目已绑定。' : '外部条目已解绑。'))
    } catch (error) {
      setFeedback([warning, '绑定变更已保存，刷新作品列表失败：' + String(error)].filter(Boolean).join(' '))
    }
  }

  async function unbind(source: WorkExternalSource) {
    setPending(true)
    try {
      await save(source, null, 'none', [])
      setConfirm(null)
    } catch (error) {
      setFeedback(String(error))
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="card-box external-binding-card">
      <strong>外部条目绑定</strong>
      {active.length ? active.map(({ source, id }) => (
        <div className="external-binding-actions" key={source}>
          <a href={bindingUrl(source, id)} target="_blank" rel="noreferrer">{labels[source]} · {id}</a>
          <button type="button" className="btn" disabled={pending} onClick={() => setEditing(source)}>更换条目</button>
          <button type="button" className="btn" disabled={pending} onClick={() => setConfirm(source)}>解绑</button>
          {confirm === source && (
            <div className="external-binding-actions" role="group" aria-label="确认解绑">
              <span>确认解除与 {labels[source]} #{id} 的绑定？</span>
              <button type="button" className="btn" disabled={pending} onClick={() => setConfirm(null)}>取消</button>
              <button type="button" className="btn btn-primary" disabled={pending} onClick={() => void unbind(source)}>确认解绑</button>
            </div>
          )}
        </div>
      )) : <span>未绑定外部条目</span>}
      {sources.some((source) => !active.some((binding) => binding.source === source)) && (
        <button type="button" className="btn" onClick={() => setEditing(sources.find((source) => !active.some((binding) => binding.source === source)) ?? sources[0])}>搜索并绑定条目</button>
      )}
      {feedback && <div role="status">{feedback}</div>}
      {editing && <WorkExternalBindingModal item={item} initialSource={editing} onClose={() => setEditing(null)} onSave={save} />}
    </section>
  )
}

export function WorkExternalBindingModal({ item, initialSource, onClose, onSave }: {
  item: CuratorItem
  initialSource: WorkExternalSource
  onClose: () => void
  onSave: (source: WorkExternalSource, hit: RemoteCatalogHit, mode: ExternalBindingMode, fields: ExternalMetadataField[]) => Promise<void>
}) {
  const { zIndex, bringToFront } = useFloatingLayer(true, 'modal')
  const {
    offset: modalOffset,
    isMoved: modalMoved,
    resetOffset: resetModalOffset,
    dragHandlers: modalDragHandlers
  } = useDraggablePanel({ open: true, resetOnOpen: true, minY: -220 })
  const searchInput = useRef<HTMLInputElement>(null)
  const [source, setSource] = useState(initialSource)
  const [keyword, setKeyword] = useState(item.title)
  const [directInput, setDirectInput] = useState('')
  const [hits, setHits] = useState<RemoteCatalogHit[] | null>(null)
  const [selected, setSelected] = useState<RemoteCatalogHit | null>(null)
  const [mode, setMode] = useState<ExternalBindingMode>('fill_missing')
  const [fields, setFields] = useState<ExternalMetadataField[]>([])
  const [busy, setBusy] = useState<'search' | 'save' | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const origin = document.activeElement instanceof HTMLElement ? document.activeElement : null
    searchInput.current?.focus()
    return () => origin?.focus()
  }, [])

  async function search(event: FormEvent, direct: boolean) {
    event.preventDefault()
    const input = (direct ? directInput : keyword).trim()
    if (!input || busy) return
    setBusy('search')
    setError('')
    setHits(null)
    setSelected(null)
    try {
      let found: RemoteCatalogHit[]
      if (source === 'bangumi' && direct) {
        const detail = await studioApi.fetchBangumiSubject(input)
        found = [{
          kind: item.kind, externalSource: source, externalId: detail.externalId,
          title: detail.title ?? detail.originalTitle ?? '#' + detail.externalId,
          originalTitle: detail.originalTitle ?? '', coverUrl: detail.coverThumbUrl ?? detail.coverUrl ?? '',
          releaseDate: detail.releaseDate ?? '', totalEpisodes: detail.totalEpisodes ?? 0,
          publicScore: detail.publicScore, studioOrCircle: detail.studio ?? '', synopsis: detail.summary ?? '',
        }]
      } else if (source === 'bangumi') {
        found = await studioApi.searchBangumi(input, item.kind)
      } else if (source === 'dlsite') {
        found = await studioApi.lookupDlsiteAudio(input)
      } else {
        const vndbInput = direct ? input.replace(/^https:\/\/vndb\.org\//i, '').replace(/[/?#].*$/, '') : input
        if (direct && !/^v\d+$/i.test(vndbInput)) throw new Error('请输入 VNDB 条目 ID（例如 v17）或条目网址。')
        found = await studioApi.searchVndbGames(direct ? vndbInput.toLowerCase() : vndbInput, direct)
        if (direct) found = found.filter((hit) => hit.externalId === vndbInput.toLowerCase())
      }
      setHits(found)
      if (direct && found.length === 1) setSelected(found[0])
    } catch (err) {
      setError(String(err))
    } finally {
      setBusy(null)
    }
  }

  async function submit() {
    if (!selected || busy) return
    setBusy('save')
    setError('')
    try {
      await onSave(source, selected, mode, fields)
      onClose()
    } catch (err) {
      setError(String(err))
    } finally {
      setBusy(null)
    }
  }

  return createPortal(
    <div
      className="modal-backdrop external-binding-backdrop"
      style={{ zIndex }}
      onPointerDownCapture={bringToFront}
    >
      <section
        className="card-box external-binding-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="binding-dialog-title"
        style={{
          transform: modalMoved ? `translate(${modalOffset.x}px, ${modalOffset.y}px)` : undefined
        }}
        onKeyDown={(event) => {
          if (event.key === 'Escape' && !busy && !event.defaultPrevented) onClose()
          if (event.key !== 'Tab') return
          const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), a[href]'))
          const destination = event.shiftKey ? controls.at(-1) : controls[0]
          const edge = event.shiftKey ? controls[0] : controls.at(-1)
          if (destination && document.activeElement === edge) {
            event.preventDefault()
            destination.focus()
          }
        }}
      >
        <header
          className="external-binding-actions"
          {...modalDragHandlers}
          title="按住标题栏可自由拖动窗口，双击归位"
          style={{ cursor: 'grab', userSelect: 'none' }}
        >
          <h2 id="binding-dialog-title" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.9231rem' }}>⋮⋮</span>
            搜索并绑定外部条目
          </h2>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            {modalMoved && (
              <button type="button" className="btn btn-ghost" onClick={resetModalOffset}>
                归位
              </button>
            )}
            <button className="btn" disabled={Boolean(busy)} onClick={onClose}>关闭</button>
          </div>
        </header>
        <StudioSelect ariaLabel="外部条目来源" value={source} disabled={Boolean(busy)} zIndex={zIndex + 25}
          options={sourcesByKind[item.kind].map((value) => ({ value, label: labels[value] }))}
          onChange={(value) => {
            setSource(value as WorkExternalSource)
            setHits(null)
            setSelected(null)
            setError('')
          }} />
        <form className="external-binding-actions" onSubmit={(event) => void search(event, false)}>
          <label>关键词 <input ref={searchInput} value={keyword} onChange={(event) => setKeyword(event.target.value)} disabled={Boolean(busy)} /></label>
          <button className="btn" type="submit" disabled={Boolean(busy) || !keyword.trim()}>搜索</button>
          {item.originalTitle && <button className="btn" type="button" disabled={Boolean(busy)} onClick={() => {
            setKeyword(item.originalTitle)
            searchInput.current?.focus()
          }}>改用原名</button>}
        </form>
        <form className="external-binding-actions" onSubmit={(event) => void search(event, true)}>
          <label>条目 ID 或网址 <input value={directInput} onChange={(event) => setDirectInput(event.target.value)} disabled={Boolean(busy)} /></label>
          <button className="btn" type="submit" disabled={Boolean(busy) || !directInput.trim()}>直查预览</button>
        </form>
        {busy && <p role="status">{busy === 'search' ? '正在查询条目…' : '正在保存绑定…'}</p>}
        {error && <p role="alert">{error}</p>}
        {hits?.length === 0 && <p>未找到匹配条目。</p>}
        {!!hits?.length && <div className="external-binding-results">
          {hits.map((hit) => <button className="btn external-binding-result" type="button" key={hit.externalId}
            disabled={Boolean(busy)} aria-pressed={selected === hit} onClick={() => setSelected(hit)}>
            {hit.coverUrl && <img src={hit.coverUrl} alt="" loading="lazy" />}
            <span>
              <strong>{hit.title}</strong>
              {hit.originalTitle && hit.originalTitle !== hit.title && <span>{hit.originalTitle}</span>}
              <small>{[hit.releaseDate, hit.totalEpisodes > 0 ? hit.totalEpisodes + ' 集或话' : '', hit.publicScore?.toFixed(1), '#' + hit.externalId].filter(Boolean).join(' · ')}</small>
            </span>
          </button>)}
        </div>}
        {selected && <fieldset disabled={Boolean(busy)}>
          <legend>绑定时写入资料</legend>
          {([
            ['fill_missing', '绑定并仅补全空缺资料'],
            ['none', '仅绑定条目 ID'],
            ['overwrite_selected', '绑定并覆盖所选基础资料'],
          ] as const).map(([value, label]) => <label key={value}>
            <input type="radio" name="binding-mode" checked={mode === value} onChange={() => setMode(value)} /> {label}
          </label>)}
          {mode === 'overwrite_selected' && <div className="external-binding-actions">
            {fieldOptions.filter(([field]) => field !== 'total_episodes' || item.kind === 'anime' || item.kind === 'book')
              .filter(([field]) => field !== 'synopsis' || item.kind !== 'audio').map(([field, label]) => <label key={field}>
                <input type="checkbox" checked={fields.includes(field)} onChange={(event) => setFields((current) =>
                  event.target.checked ? [...current, field] : current.filter((entry) => entry !== field))} /> {label}
              </label>)}
          </div>}
        </fieldset>}
        <button className="btn btn-primary" disabled={!selected || Boolean(busy) || (mode === 'overwrite_selected' && fields.length === 0)}
          onClick={() => void submit()}>确认绑定</button>
      </section>
    </div>,
    document.body,
  )
}
