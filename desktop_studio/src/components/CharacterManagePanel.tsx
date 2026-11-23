import React, { useEffect, useMemo, useState } from 'react'
import { formatCarrierLabel, studioApi } from '../api'
import { CoverImage } from './CoverImage'
import { LoadingSpinner } from './LoadingIndicator'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import type { CharacterManageItem, CuratorItem, RemoteCharacterHit } from '../types'

interface CharacterManagePanelProps {
  characters: CharacterManageItem[]
  allItems: CuratorItem[]
  mediaBaseUrl: string
  busy: boolean
  focusCharacterId?: number | null
  focusSeq?: number
  onBusy: (msg: string | null) => void
  onNotice: (msg: string) => void
  onError: (msg: string) => void
  onRefresh: () => Promise<void>
  onOpenCanvasCharacters?: () => void
}

const RELATION_PRESETS = ['恋人', '挚友', '兄妹', '姐弟', '家人', '师徒', '搭档', '对手', '同伴', '主从', '关联']
const WORK_ROLE_PRESETS = ['主角', '主要角色', '女主', '男主', '配角', '客串', '暂定']

interface CharacterDraft {
  id: number | null
  bgmId: number | null
  anilistId: number | null
  name: string
  nameCn: string
  gender: string
  bloodType: string
  birthMonth: string
  birthDay: string
  rating: string
  imageUrl: string
  summary: string
  review: string
}

function emptyDraft(): CharacterDraft {
  return {
    id: null,
    bgmId: null,
    anilistId: null,
    name: '',
    nameCn: '',
    gender: '',
    bloodType: '',
    birthMonth: '',
    birthDay: '',
    rating: '',
    imageUrl: '',
    summary: '',
    review: ''
  }
}

function toDraft(c: CharacterManageItem): CharacterDraft {
  return {
    id: c.id,
    bgmId: c.bgmId,
    anilistId: null,
    name: c.name,
    nameCn: c.nameCn,
    gender: c.gender,
    bloodType: c.bloodType,
    birthMonth: c.birthMonth != null ? String(c.birthMonth) : '',
    birthDay: c.birthDay != null ? String(c.birthDay) : '',
    rating: c.rating != null ? String(c.rating) : '',
    imageUrl: c.imageUrl,
    summary: c.summary,
    review: c.review
  }
}

function extractBangumiSubjectId(item: CuratorItem): number | null {
  const m = item.syncKey.match(/^bangumi:(\d+)$/i)
  if (!m) return null
  const n = Number.parseInt(m[1], 10)
  return Number.isNaN(n) || n <= 0 ? null : n
}

export const CharacterManagePanel: React.FC<CharacterManagePanelProps> = ({
  characters,
  allItems,
  mediaBaseUrl,
  busy,
  focusCharacterId = null,
  focusSeq = 0,
  onBusy,
  onNotice,
  onError,
  onRefresh,
  onOpenCanvasCharacters
}) => {
  const dialog = useStudioDialog()
  const [search, setSearch] = useState('')
  const [visibleLimit, setVisibleLimit] = useState(80)
  const [relSearch, setRelSearch] = useState('')
  const [selectedId, setSelectedId] = useState<number | 'new' | null>(() => {
    if (focusCharacterId != null && characters.some((c) => c.id === focusCharacterId)) {
      return focusCharacterId
    }
    return characters.length > 0 ? characters[0].id : 'new'
  })
  const [draft, setDraft] = useState<CharacterDraft>(() => {
    const initial =
      focusCharacterId != null
        ? characters.find((c) => c.id === focusCharacterId) ?? characters[0]
        : characters[0]
    return initial ? toDraft(initial) : emptyDraft()
  })

  // 联网获取角色面板状态（按角色名搜索 / 按库内作品一键拉取）
  const [fetchOpen, setFetchOpen] = useState(false)
  const [fetchMode, setFetchMode] = useState<'search' | 'work'>('search')
  const [remoteQuery, setRemoteQuery] = useState('')
  const [remoteWorkFilter, setRemoteWorkFilter] = useState('')
  const [selectedFetchWorkId, setSelectedFetchWorkId] = useState<number | null>(null)
  const [remoteLoading, setRemoteLoading] = useState(false)
  const [remoteHits, setRemoteHits] = useState<RemoteCharacterHit[]>([])

  // 关联作品输入状态
  const [workSearch, setWorkSearch] = useState('')
  const [workRole, setWorkRole] = useState('主角')
  const [workVa, setWorkVa] = useState('')

  // 角色标签 & 批量选择状态
  const [newCharTag, setNewCharTag] = useState('')
  const [batchMode, setBatchMode] = useState(false)
  const [batchSelectedIds, setBatchSelectedIds] = useState<Set<number>>(() => new Set())

  // 角色关系输入状态
  const [relTargetId, setRelTargetId] = useState<string>('')
  const [relType, setRelType] = useState('挚友')
  const [relSourceRole, setRelSourceRole] = useState('')
  const [relTargetRole, setRelTargetRole] = useState('')
  const [relNote, setRelNote] = useState('')
  const [relStrength, setRelStrength] = useState(3)

  const selectedChar = useMemo(
    () => (typeof selectedId === 'number' ? characters.find((c) => c.id === selectedId) ?? null : null),
    [characters, selectedId]
  )

  useEffect(() => {
    if (focusCharacterId == null || characters.length === 0) return
    const idx = characters.findIndex((c) => c.id === focusCharacterId)
    if (idx < 0) return
    const target = characters[idx]
    setSearch('')
    setVisibleLimit((prev) => (idx >= prev ? idx + 40 : prev))
    setSelectedId(target.id)
    setDraft(toDraft(target))
  }, [focusCharacterId, focusSeq, characters])

  useEffect(() => {
    if (selectedId === 'new') return
    if (selectedChar) {
      setDraft(toDraft(selectedChar))
    } else if (characters.length > 0) {
      setSelectedId(characters[0].id)
      setDraft(toDraft(characters[0]))
    } else {
      setSelectedId('new')
      setDraft(emptyDraft())
    }
  }, [
    selectedChar?.id,
    selectedChar?.name,
    selectedChar?.nameCn,
    selectedChar?.imageUrl,
    selectedChar?.rating,
    characters.length
  ])

  useEffect(() => {
    setVisibleLimit(80)
  }, [search])

  const filteredChars = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return characters
    return characters.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.nameCn.toLowerCase().includes(q) ||
        c.tags.some((t) => t.toLowerCase().includes(q)) ||
        c.works.some((w) => w.title.toLowerCase().includes(q) || w.voiceActor.toLowerCase().includes(q))
    )
  }, [characters, search])

  const renderedChars = useMemo(
    () => filteredChars.slice(0, visibleLimit),
    [filteredChars, visibleLimit]
  )

  const relCandidates = useMemo(() => {
    if (!selectedChar) return []
    const q = relSearch.trim().toLowerCase()
    return characters
      .filter(
        (o) =>
          o.id !== selectedChar.id &&
          (!q || o.name.toLowerCase().includes(q) || o.nameCn.toLowerCase().includes(q))
      )
      .slice(0, 60)
  }, [characters, selectedChar, relSearch])

  const animeBookItems = useMemo(
    () => allItems.filter((it) => it.kind === 'anime' || it.kind === 'book'),
    [allItems]
  )

  const bangumiBoundWorks = useMemo(() => {
    const q = remoteWorkFilter.trim().toLowerCase()
    return animeBookItems
      .filter((it) => {
        if (extractBangumiSubjectId(it) == null) return false
        if (!q) return true
        return it.title.toLowerCase().includes(q) || it.originalTitle.toLowerCase().includes(q)
      })
      .slice(0, 40)
  }, [animeBookItems, remoteWorkFilter])

  const selectedFetchWork = useMemo(
    () => animeBookItems.find((w) => w.id === selectedFetchWorkId) ?? null,
    [animeBookItems, selectedFetchWorkId]
  )

  const candidateWorks = useMemo(() => {
    const q = workSearch.trim().toLowerCase()
    const linked = new Set(selectedChar?.works.map((w) => w.animeId) ?? [])
    return animeBookItems
      .filter((it) => {
        if (linked.has(it.id)) return false
        if (!q) return true
        return it.title.toLowerCase().includes(q) || it.originalTitle.toLowerCase().includes(q)
      })
      .slice(0, 8)
  }, [animeBookItems, workSearch, selectedChar])

  const existingBgmIds = useMemo(
    () => new Set(characters.map((c) => c.bgmId).filter((v): v is number => v != null && v > 0)),
    [characters]
  )

  const handleRemoteSearch = async () => {
    const q = remoteQuery.trim()
    if (!q) return
    setRemoteLoading(true)
    onError('')
    try {
      const hits = await studioApi.searchRemoteCharacters(q)
      setRemoteHits(hits)
      if (hits.length === 0) {
        onNotice(`未搜索到包含「${q}」的角色`)
      }
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      setRemoteLoading(false)
    }
  }

  const handleFetchWorkCharacters = async (work: CuratorItem) => {
    const subjectId = extractBangumiSubjectId(work)
    if (!subjectId) {
      onError('该作品未绑定 Bangumi 编号，无法拉取角色')
      return
    }
    setSelectedFetchWorkId(work.id)
    setRemoteLoading(true)
    onError('')
    try {
      const hits = await studioApi.fetchWorkCharacters(subjectId)
      setRemoteHits(hits)
      if (hits.length === 0) {
        onNotice(`《${work.title}》暂无公开角色资料`)
      } else {
        onNotice(`已拉取《${work.title}》的 ${hits.length} 位角色资料`)
      }
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      setRemoteLoading(false)
    }
  }

  const handleImportRemoteHit = async (hit: RemoteCharacterHit, bindWorkId?: number | null) => {
    const disp = hit.nameCn || hit.name
    onBusy(`正在导入角色「${disp}」...`)
    onError('')
    try {
      const id = await studioApi.upsertCharacter({
        id: null,
        bgmId: hit.bgmId,
        anilistId: hit.anilistId,
        name: hit.name || hit.nameCn,
        nameCn: hit.nameCn || null,
        imageUrl: hit.imageUrl || null,
        summary: hit.summary || null,
        gender: hit.gender || null,
        birthMonth: hit.birthMonth,
        birthDay: hit.birthDay,
        bloodType: hit.bloodType || null
      })
      if (bindWorkId) {
        await studioApi.linkCharacterWork(
          id,
          bindWorkId,
          hit.roleName || '主要角色',
          hit.voiceActor || null,
          true
        )
      }
      await onRefresh()
      setSelectedId(id)
      onNotice(
        bindWorkId
          ? `已导入角色「${disp}」并绑定至作品`
          : `已导入角色「${disp}」`
      )
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleBatchImportMainFromWork = async () => {
    if (!selectedFetchWork || remoteHits.length === 0) return
    const mainHits = remoteHits.filter((h) => h.roleName === '主角' || h.roleName === '主要角色')
    const list = mainHits.length > 0 ? mainHits.slice(0, 12) : remoteHits.slice(0, 8)
    onBusy(`正在批量导入《${selectedFetchWork.title}》的 ${list.length} 位主要角色...`)
    onError('')
    try {
      let lastId: number | null = null
      for (const hit of list) {
        const id = await studioApi.upsertCharacter({
          id: null,
          bgmId: hit.bgmId,
          anilistId: hit.anilistId,
          name: hit.name || hit.nameCn,
          nameCn: hit.nameCn || null,
          imageUrl: hit.imageUrl || null,
          summary: hit.summary || null,
          gender: hit.gender || null,
          birthMonth: hit.birthMonth,
          birthDay: hit.birthDay,
          bloodType: hit.bloodType || null
        })
        await studioApi.linkCharacterWork(
          id,
          selectedFetchWork.id,
          hit.roleName || '主角',
          hit.voiceActor || null,
          true
        )
        lastId = id
      }
      await onRefresh()
      if (lastId != null) setSelectedId(lastId)
      onNotice(`已批量导入并绑定《${selectedFetchWork.title}》的 ${list.length} 位角色`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const fillDraftFromRemoteHit = (hit: RemoteCharacterHit) => {
    setSelectedId('new')
    setDraft({
      id: null,
      bgmId: hit.bgmId,
      anilistId: hit.anilistId,
      name: hit.name,
      nameCn: hit.nameCn,
      gender: hit.gender,
      bloodType: hit.bloodType,
      birthMonth: hit.birthMonth != null ? String(hit.birthMonth) : '',
      birthDay: hit.birthDay != null ? String(hit.birthDay) : '',
      rating: '',
      imageUrl: hit.imageUrl,
      summary: hit.summary,
      review: ''
    })
    setFetchOpen(false)
  }

  const handleSaveCharacter = async () => {
    if (!draft.name.trim() && !draft.nameCn.trim()) {
      onError('请至少填写角色原名或中文名')
      return
    }
    onBusy(draft.id ? '正在保存角色档案...' : '正在创建新角色...')
    onError('')
    try {
      const parsedMonth = Number.parseInt(draft.birthMonth, 10)
      const parsedDay = Number.parseInt(draft.birthDay, 10)
      const parsedRating = Number.parseInt(draft.rating, 10)
      const id = await studioApi.upsertCharacter({
        id: draft.id,
        bgmId: draft.bgmId,
        anilistId: draft.anilistId,
        name: draft.name.trim() || draft.nameCn.trim(),
        nameCn: draft.nameCn.trim() || null,
        gender: draft.gender.trim() || null,
        bloodType: draft.bloodType.trim() || null,
        birthMonth: Number.isNaN(parsedMonth) ? null : parsedMonth,
        birthDay: Number.isNaN(parsedDay) ? null : parsedDay,
        rating: Number.isNaN(parsedRating) ? null : parsedRating,
        imageUrl: draft.imageUrl.trim() || null,
        summary: draft.summary.trim() || null,
        review: draft.review.trim() || null
      })
      await onRefresh()
      setSelectedId(id)
      onNotice(draft.id ? '角色档案已更新' : `已创建新角色「${draft.nameCn || draft.name}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleDeleteCharacter = async (c: CharacterManageItem) => {
    const disp = c.nameCn || c.name
    const ok = await dialog.confirm({
      title: '删除角色',
      message: `确定删除角色「${disp}」吗？`,
      detail: `将同步移除该角色的 ${c.works.length} 条作品关联与 ${c.relations.length} 条角色关系，此操作不可撤销。`,
      confirmText: '确认删除',
      tone: 'danger'
    })
    if (!ok) return
    onBusy(`正在删除角色「${disp}」...`)
    onError('')
    try {
      await studioApi.deleteCharacter(c.id)
      await onRefresh()
      setSelectedId('new')
      setDraft(emptyDraft())
      onNotice(`已删除角色「${disp}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleLinkWork = async (animeId: number, title: string) => {
    if (!selectedChar) return
    onBusy(`正在关联《${title}》...`)
    onError('')
    try {
      await studioApi.linkCharacterWork(
        selectedChar.id,
        animeId,
        workRole.trim() || '主角',
        workVa.trim() || null
      )
      await onRefresh()
      setWorkSearch('')
      setWorkVa('')
      onNotice(
        selectedChar.relations.length > 0
          ? `已关联角色与《${title}》，相关联角色也已自动补充至该作品（定位：暂定）`
          : `已关联角色与《${title}》`
      )
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleUpdateWorkRole = async (
    animeId: number,
    title: string,
    nextRole: string,
    currentVa: string
  ) => {
    if (!selectedChar) return
    onBusy(`正在更新《${title}》中的角色定位...`)
    onError('')
    try {
      await studioApi.linkCharacterWork(
        selectedChar.id,
        animeId,
        nextRole.trim() || '暂定',
        currentVa.trim() || null
      )
      await onRefresh()
      onNotice(`已将《${title}》中的角色定位更新为「${nextRole}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleUnlinkWork = async (animeId: number, title: string) => {
    if (!selectedChar) return
    onBusy(`正在取消与《${title}》的关联...`)
    onError('')
    try {
      await studioApi.unlinkCharacterWork(selectedChar.id, animeId)
      await onRefresh()
      onNotice(`已取消与《${title}》的关联`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleAddRelation = async () => {
    if (!selectedChar) return
    const target = Number.parseInt(relTargetId, 10)
    if (Number.isNaN(target) || target === selectedChar.id) {
      onError('请选择要建立关系的另一个角色')
      return
    }
    onBusy('正在保存角色关系...')
    onError('')
    try {
      await studioApi.upsertCharacterRelation({
        sourceCharacterId: selectedChar.id,
        targetCharacterId: target,
        relationType: relType.trim() || '关联',
        sourceRole: relSourceRole.trim() || null,
        targetRole: relTargetRole.trim() || null,
        note: relNote.trim() || null,
        strength: relStrength
      })
      await onRefresh()
      setRelTargetId('')
      setRelSourceRole('')
      setRelTargetRole('')
      setRelNote('')
      onNotice(`已新增角色关系「${relType.trim() || '关联'}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleDeleteRelation = async (relId: number) => {
    onBusy('正在移除角色关系...')
    onError('')
    try {
      await studioApi.deleteCharacterRelation(relId)
      await onRefresh()
      onNotice('已移除该角色关系')
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleAddCharTags = async () => {
    if (!selectedChar) return
    const pieces = newCharTag
      .split(/[,，;；\n]+/)
      .map((s) => s.trim().replace(/^#+/, '').trim())
      .filter(Boolean)
    if (pieces.length === 0) return
    const existingLower = new Set(selectedChar.tags.map((t) => t.toLowerCase()))
    const next = [...selectedChar.tags]
    for (const p of pieces) {
      if (!existingLower.has(p.toLowerCase())) {
        existingLower.add(p.toLowerCase())
        next.push(p)
      }
    }
    onBusy('正在保存角色标签...')
    onError('')
    try {
      await studioApi.setCharacterTags(selectedChar.id, next)
      setNewCharTag('')
      await onRefresh()
      onNotice('角色标签已更新')
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleRemoveCharTag = async (tagToRemove: string) => {
    if (!selectedChar) return
    const next = selectedChar.tags.filter((t) => t !== tagToRemove)
    onBusy('正在移除角色标签...')
    onError('')
    try {
      await studioApi.setCharacterTags(selectedChar.id, next)
      await onRefresh()
      onNotice(`已移除角色标签「${tagToRemove}」`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  const handleBatchDeleteSelected = async () => {
    const ids = Array.from(batchSelectedIds)
    if (ids.length === 0) return
    const ok = await dialog.confirm({
      title: '批量删除角色',
      message: `确定批量删除选中的 ${ids.length} 位角色吗？`,
      detail: '将同步移除这些角色的所有作品出演关联、角色关系与角色标签，此操作不可撤销。',
      confirmText: `确认删除 (${ids.length})`,
      tone: 'danger'
    })
    if (!ok) return
    onBusy(`正在批量删除 ${ids.length} 位角色...`)
    onError('')
    try {
      const removed = await studioApi.batchDeleteCharacters(ids)
      setBatchSelectedIds(new Set())
      setBatchMode(false)
      await onRefresh()
      onNotice(`已批量删除 ${removed} 位角色`)
    } catch (err) {
      onError(err instanceof Error ? err.message : String(err))
    } finally {
      onBusy(null)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <input
          className="input-field"
          style={{ width: 220, padding: '5px 10px', fontSize: '0.9615rem' }}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="搜索本地角色名、作品名或声优..."
        />
        <button
          className={`btn ${fetchOpen ? 'btn-primary' : ''}`}
          onClick={() => setFetchOpen((v) => !v)}
          title="像手机端一样联网搜索角色，或按作品一键拉取全部出场角色及头像"
        >
          {fetchOpen ? '收起联网获取' : '联网获取角色'}
        </button>
        <button
          className="btn btn-primary"
          onClick={() => {
            setFetchOpen(false)
            setSelectedId('new')
            setDraft(emptyDraft())
          }}
        >
          + 手动新建
        </button>
        {characters.length > 0 && (
          <button
            className={`btn ${batchMode ? 'btn-primary' : ''}`}
            onClick={() => {
              setBatchMode((v) => !v)
              setBatchSelectedIds(new Set())
            }}
          >
            {batchMode ? '退出批量' : '批量删除'}
          </button>
        )}
        {batchMode && batchSelectedIds.size > 0 && (
          <button
            className="btn btn-danger"
            disabled={busy}
            onClick={() => void handleBatchDeleteSelected()}
          >
            删除选中 ({batchSelectedIds.size})
          </button>
        )}
        {onOpenCanvasCharacters && (
          <button
            className="btn"
            onClick={onOpenCanvasCharacters}
            title="跳转到画布「角色关系图谱」可视化排版与连线"
          >
            画布角色图谱 →
          </button>
        )}
        <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)', marginLeft: 'auto' }}>
          共 {characters.length} 位角色 · 与移动端互通
        </span>
      </div>

      {/* 联网获取角色面板（与手机端同源：服务端缓存 + Bangumi + AniList，免梯子） */}
      {fetchOpen && (
        <div
          style={{
            border: '1px solid var(--accent)',
            borderRadius: 'var(--radius-md)',
            background: 'var(--bg-panel)',
            padding: 12,
            display: 'flex',
            flexDirection: 'column',
            gap: 10
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <div className="segmented-group">
              <button
                className={`segmented-btn ${fetchMode === 'search' ? 'active' : ''}`}
                onClick={() => {
                  setFetchMode('search')
                  setRemoteHits([])
                }}
              >
                按角色名联网搜索
              </button>
              <button
                className={`segmented-btn ${fetchMode === 'work' ? 'active' : ''}`}
                onClick={() => {
                  setFetchMode('work')
                  setRemoteHits([])
                }}
              >
                从库内作品拉取角色
              </button>
            </div>

            {fetchMode === 'search' ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 240 }}>
                <input
                  className="input-field"
                  style={{ flex: 1, padding: '5px 9px', fontSize: '0.9615rem' }}
                  value={remoteQuery}
                  onChange={(e) => setRemoteQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void handleRemoteSearch()
                  }}
                  placeholder="输入角色中文名或日文原名（如 芙莉莲 / 辉夜 / 远坂凛）..."
                  autoFocus
                />
                <button
                  className="btn btn-primary"
                  disabled={remoteLoading || !remoteQuery.trim()}
                  onClick={() => void handleRemoteSearch()}
                >
                  {remoteLoading ? <LoadingSpinner size="sm" inline label="搜索中" /> : '联网搜索'}
                </button>
              </div>
            ) : (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1, minWidth: 260, flexWrap: 'wrap' }}>
                <input
                  className="input-field"
                  style={{ width: 170, padding: '4px 8px', fontSize: '0.9231rem' }}
                  value={remoteWorkFilter}
                  onChange={(e) => setRemoteWorkFilter(e.target.value)}
                  placeholder="筛选已绑定 Bangumi 的作品..."
                />
                <StudioSelect
                  className="studio-select-inline studio-select-flex studio-select-cap-lg"
                  value={selectedFetchWorkId != null ? String(selectedFetchWorkId) : ''}
                  placeholder="选择一部动画/书籍拉取角色..."
                  ariaLabel="选择拉取角色的 Bangumi 绑定作品"
                  minMenuWidth={240}
                  maxMenuWidth={340}
                  onChange={(val) => {
                    const id = Number.parseInt(val, 10)
                    const w = bangumiBoundWorks.find((it) => it.id === id)
                    if (w) void handleFetchWorkCharacters(w)
                  }}
                  options={[
                    { value: '', label: '选择一部动画/书籍拉取角色...' },
                    ...bangumiBoundWorks.map((w) => ({
                      value: String(w.id),
                      label: w.title,
                      badges: [formatCarrierLabel(w.kind)]
                    }))
                  ]}
                />
                {selectedFetchWork && remoteHits.length > 0 && (
                  <button
                    className="btn btn-primary"
                    style={{ fontSize: '0.8846rem' }}
                    disabled={busy || remoteLoading}
                    onClick={() => void handleBatchImportMainFromWork()}
                  >
                    一键导入主要角色
                  </button>
                )}
              </div>
            )}
          </div>

          {remoteLoading ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: 12, fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
              <LoadingSpinner size="sm" />
              <span>正在搜索角色与头像资料...</span>
            </div>
          ) : remoteHits.length > 0 ? (
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))',
                gap: 8,
                maxHeight: 260,
                overflowY: 'auto',
                paddingRight: 2
              }}
            >
              {remoteHits.map((hit, idx) => {
                const disp = hit.nameCn || hit.name
                const alreadyInLib = hit.bgmId != null && existingBgmIds.has(hit.bgmId)
                return (
                  <div
                    key={`${hit.source}:${hit.bgmId ?? hit.anilistId ?? idx}`}
                    style={{
                      display: 'flex',
                      gap: 8,
                      padding: 8,
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-surface)',
                      border: '1px solid var(--border-subtle)',
                      alignItems: 'center'
                    }}
                  >
                    <div
                      style={{
                        width: 44,
                        height: 58,
                        borderRadius: 'var(--radius-sm)',
                        overflow: 'hidden',
                        flexShrink: 0,
                        background: 'var(--bg-canvas)',
                        border: '1px solid var(--border-subtle)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '0.9231rem',
                        color: 'var(--text-muted)'
                      }}
                    >
                      <CoverImage
                        coverUrl={hit.imageUrl}
                        mediaBaseUrl={mediaBaseUrl}
                        alt={disp}
                        style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center' }}
                        fallback={<span>{disp.slice(0, 2)}</span>}
                      />
                    </div>
                    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 3 }}>
                      <div
                        style={{
                          fontWeight: 700,
                          fontSize: '0.9615rem',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }}
                        title={disp}
                      >
                        {disp}
                      </div>
                      <div
                        style={{
                          fontSize: '0.8462rem',
                          color: 'var(--text-muted)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }}
                      >
                        {[
                          hit.roleName,
                          hit.voiceActor ? `CV: ${hit.voiceActor}` : '',
                          hit.nameCn && hit.name && hit.name !== hit.nameCn ? hit.name : ''
                        ]
                          .filter(Boolean)
                          .join(' · ') || (hit.gender || hit.source)}
                      </div>
                      <div style={{ display: 'flex', gap: 4, marginTop: 2 }}>
                        <button
                          className="btn btn-primary"
                          style={{ padding: '2px 7px', fontSize: '0.8462rem' }}
                          disabled={busy}
                          onClick={() =>
                            void handleImportRemoteHit(
                              hit,
                              fetchMode === 'work' ? selectedFetchWorkId : null
                            )
                          }
                        >
                          {alreadyInLib
                            ? fetchMode === 'work'
                              ? '补全并绑定'
                              : '补全资料'
                            : fetchMode === 'work'
                              ? '导入并绑定'
                              : '一键导入'}
                        </button>
                        <button
                          className="btn"
                          style={{ padding: '2px 7px', fontSize: '0.8462rem' }}
                          disabled={busy}
                          onClick={() => fillDraftFromRemoteHit(hit)}
                        >
                          填入表单
                        </button>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          ) : (
            <div style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
              {fetchMode === 'search'
                ? '输入角色名回车搜索，支持一键导入完整头像、性别、生日、血型与简介。'
                : '选择库中已绑定 Bangumi 的动画或书籍，可直接拉取全部角色头像、定位与声优 CV。'}
            </div>
          )}
        </div>
      )}

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(220px, 270px) 1fr',
          gap: 12,
          minHeight: 420
        }}
      >
        {/* 左侧角色列表（带头像缩略图） */}
        <div
          style={{
            border: '1px solid var(--border-subtle)',
            borderRadius: 'var(--radius-md)',
            background: 'var(--bg-surface)',
            overflowY: 'auto',
            maxHeight: 540,
            padding: 6,
            display: 'flex',
            flexDirection: 'column',
            gap: 4
          }}
        >
          {filteredChars.length === 0 ? (
            <div style={{ padding: 20, textAlign: 'center', fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
              {characters.length === 0 ? '暂无角色，点击「联网获取角色」或「+ 手动新建」开始记录。' : '未找到匹配角色'}
            </div>
          ) : (
            <>
              {renderedChars.map((c) => {
                const active = selectedId === c.id
                const display = c.nameCn || c.name
                const isBatchChecked = batchSelectedIds.has(c.id)
                return (
                  <button
                    key={c.id}
                    onClick={() => {
                      if (batchMode) {
                        setBatchSelectedIds((prev) => {
                          const next = new Set(prev)
                          if (next.has(c.id)) next.delete(c.id)
                          else next.add(c.id)
                          return next
                        })
                        return
                      }
                      setSelectedId(c.id)
                      setDraft(toDraft(c))
                    }}
                    style={{
                      textAlign: 'left',
                      padding: '6px 8px',
                      borderRadius: 'var(--radius-sm)',
                      border: `1px solid ${
                        isBatchChecked || active ? 'var(--accent)' : 'transparent'
                      }`,
                      background: isBatchChecked || active ? 'var(--accent-soft)' : 'transparent',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8
                    }}
                  >
                    {batchMode && (
                      <input
                        type="checkbox"
                        checked={isBatchChecked}
                        readOnly
                        style={{ pointerEvents: 'none', flexShrink: 0 }}
                      />
                    )}
                    <div
                      style={{
                        width: 34,
                        height: 34,
                        borderRadius: '50%',
                        overflow: 'hidden',
                        flexShrink: 0,
                        background: 'var(--bg-panel)',
                        border: '1px solid var(--border-subtle)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '0.8462rem',
                        fontWeight: 700,
                        color: 'var(--text-muted)'
                      }}
                    >
                      <CoverImage
                        coverUrl={c.imageUrl}
                        mediaBaseUrl={mediaBaseUrl}
                        alt={display}
                        style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center' }}
                        fallback={<span>{display.slice(0, 1)}</span>}
                      />
                    </div>
                    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span
                          style={{
                            fontWeight: 700,
                            fontSize: '0.9615rem',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            flex: 1
                          }}
                        >
                          {display}
                        </span>
                        {c.rating != null && (
                          <span className="mono" style={{ fontSize: '0.8462rem', color: 'var(--accent)' }}>
                            {(c.rating / 10).toFixed(1)}
                          </span>
                        )}
                      </div>
                      <div style={{ fontSize: '0.8462rem', color: 'var(--text-muted)' }}>
                        {c.works.length} 部作品 · {c.relations.length} 条关系
                        {c.gender ? ` · ${c.gender}` : ''}
                      </div>
                    </div>
                  </button>
                )
              })}
              {visibleLimit < filteredChars.length && (
                <button
                  className="btn"
                  style={{ marginTop: 4, fontSize: '0.8846rem' }}
                  onClick={() => setVisibleLimit((v) => v + 80)}
                >
                  加载更多 ({renderedChars.length}/{filteredChars.length})
                </button>
              )}
            </>
          )}
        </div>

        {/* 右侧角色详情（带高清头像预览）、关联作品与角色关系 */}
        <div
          style={{
            border: '1px solid var(--border-subtle)',
            borderRadius: 'var(--radius-md)',
            background: 'var(--bg-surface)',
            padding: 14,
            display: 'flex',
            flexDirection: 'column',
            gap: 14,
            maxHeight: 540,
            overflowY: 'auto'
          }}
        >
          {/* 1. 角色基本档案 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <strong style={{ fontSize: '1.0385rem' }}>
                {draft.id ? `编辑角色 #${draft.id}` : '新建角色档案'}
              </strong>
              <div style={{ display: 'flex', gap: 6 }}>
                {selectedChar && (
                  <button
                    className="btn"
                    style={{ fontSize: '0.8846rem', color: 'var(--danger)' }}
                    disabled={busy}
                    onClick={() => void handleDeleteCharacter(selectedChar)}
                  >
                    删除角色
                  </button>
                )}
                <button
                  className="btn btn-primary"
                  style={{ fontSize: '0.8846rem' }}
                  disabled={busy || (!draft.name.trim() && !draft.nameCn.trim())}
                  onClick={() => void handleSaveCharacter()}
                >
                  {draft.id ? '保存档案' : '创建角色'}
                </button>
              </div>
            </div>

            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
              <div
                style={{
                  width: 72,
                  height: 94,
                  borderRadius: 'var(--radius-sm)',
                  overflow: 'hidden',
                  flexShrink: 0,
                  background: 'var(--bg-panel)',
                  border: '1px solid var(--border-strong)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  fontSize: '0.8462rem',
                  color: 'var(--text-muted)'
                }}
              >
                <CoverImage
                  coverUrl={draft.imageUrl}
                  mediaBaseUrl={mediaBaseUrl}
                  alt={draft.nameCn || draft.name || '角色头像'}
                  style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center' }}
                  fallback={<span>无头像</span>}
                />
              </div>

              <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                  <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                    中文名 / 常用名
                    <input
                      className="input-field"
                      style={{ padding: '5px 8px', fontSize: '0.9615rem' }}
                      value={draft.nameCn}
                      onChange={(e) => setDraft((d) => ({ ...d, nameCn: e.target.value }))}
                      placeholder="例如：芙莉莲"
                    />
                  </label>
                  <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                    原名（日文 / 英文）
                    <input
                      className="input-field"
                      style={{ padding: '5px 8px', fontSize: '0.9615rem' }}
                      value={draft.name}
                      onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                      placeholder="例如：フリーレン"
                    />
                  </label>
                </div>

                <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                  角色头像链接（支持联网获取自动填入，或手动粘贴图片 URL）
                  <input
                    className="input-field"
                    style={{ padding: '4px 8px', fontSize: '0.9231rem' }}
                    value={draft.imageUrl}
                    onChange={(e) => setDraft((d) => ({ ...d, imageUrl: e.target.value }))}
                    placeholder="https://..."
                  />
                </label>
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr', gap: 8 }}>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                性别
                <input
                  className="input-field"
                  style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
                  value={draft.gender}
                  onChange={(e) => setDraft((d) => ({ ...d, gender: e.target.value }))}
                  placeholder="女/男"
                />
              </label>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                血型
                <input
                  className="input-field"
                  style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
                  value={draft.bloodType}
                  onChange={(e) => setDraft((d) => ({ ...d, bloodType: e.target.value }))}
                  placeholder="A/B/O/AB"
                />
              </label>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                生日(月)
                <input
                  className="input-field"
                  style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
                  value={draft.birthMonth}
                  onChange={(e) => setDraft((d) => ({ ...d, birthMonth: e.target.value }))}
                  placeholder="1-12"
                />
              </label>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                生日(日)
                <input
                  className="input-field"
                  style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
                  value={draft.birthDay}
                  onChange={(e) => setDraft((d) => ({ ...d, birthDay: e.target.value }))}
                  placeholder="1-31"
                />
              </label>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                喜爱评分(1-100)
                <input
                  className="input-field"
                  style={{ padding: '4px 7px', fontSize: '0.9231rem' }}
                  value={draft.rating}
                  onChange={(e) => setDraft((d) => ({ ...d, rating: e.target.value }))}
                  placeholder="如 95"
                />
              </label>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                角色简介
                <textarea
                  className="input-field"
                  rows={2}
                  style={{ padding: '5px 8px', fontSize: '0.9231rem', resize: 'vertical' }}
                  value={draft.summary}
                  onChange={(e) => setDraft((d) => ({ ...d, summary: e.target.value }))}
                  placeholder="角色设定、背景简介..."
                />
              </label>
              <label style={{ fontSize: '0.8846rem', display: 'flex', flexDirection: 'column', gap: 3 }}>
                个人印象 / 评价
                <textarea
                  className="input-field"
                  rows={2}
                  style={{ padding: '5px 8px', fontSize: '0.9231rem', resize: 'vertical' }}
                  value={draft.review}
                  onChange={(e) => setDraft((d) => ({ ...d, review: e.target.value }))}
                  placeholder="记录喜欢这个角色的理由..."
                />
              </label>
            </div>
          </div>

          {selectedChar && (
            <>
              {/* 1.5 角色标签管理（character_tags / character_tag_links） */}
              <div
                style={{
                  borderTop: '1px solid var(--border-subtle)',
                  paddingTop: 10,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 6
                }}
              >
                <strong style={{ fontSize: '0.9615rem' }}>
                  角色标签 ({selectedChar.tags.length})
                </strong>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, alignItems: 'center' }}>
                  {selectedChar.tags.map((tg) => (
                    <span
                      key={tg}
                      className="badge"
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 4,
                        background: 'var(--badge-purple-bg)',
                        color: 'var(--badge-purple-fg)',
                        borderColor: 'var(--badge-purple-border)'
                      }}
                    >
                      <span>#{tg}</span>
                      <button
                        type="button"
                        style={{
                          border: 'none',
                          background: 'transparent',
                          color: 'inherit',
                          cursor: 'pointer',
                          padding: 0,
                          fontSize: '0.9231rem',
                          lineHeight: 1
                        }}
                        disabled={busy}
                        onClick={() => void handleRemoveCharTag(tg)}
                        title={`移除标签 ${tg}`}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                  <input
                    className="inline-input"
                    style={{ width: 170, height: 24, fontSize: '0.8846rem' }}
                    value={newCharTag}
                    onChange={(e) => setNewCharTag(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void handleAddCharTags()
                    }}
                    placeholder="+ 添加特征标签（回车，逗号分隔）"
                  />
                  {newCharTag.trim() && (
                    <button
                      className="btn btn-primary"
                      style={{ height: 24, padding: '0 8px', fontSize: '0.8462rem' }}
                      disabled={busy}
                      onClick={() => void handleAddCharTags()}
                    >
                      添加
                    </button>
                  )}
                </div>
              </div>

              {/* 2. 角色 ↔ 动画/书籍关联 */}
              <div
                style={{
                  borderTop: '1px solid var(--border-subtle)',
                  paddingTop: 10,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8
                }}
              >
                <strong style={{ fontSize: '0.9615rem' }}>
                  角色 ↔ 动画 / 书籍关联 ({selectedChar.works.length})
                </strong>
                {selectedChar.works.length === 0 ? (
                  <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
                    尚未关联任何动画或书籍，可在下方搜索作品快速绑定。
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                    {selectedChar.works.map((w) => {
                      const isTentative = w.roleName === '暂定'
                      return (
                        <div
                          key={w.animeId}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '5px 8px',
                            borderRadius: 'var(--radius-sm)',
                            background: 'var(--bg-canvas)',
                            border: `1px solid ${
                              isTentative ? 'var(--badge-amber-border)' : 'var(--border-subtle)'
                            }`,
                            fontSize: '0.9231rem',
                            flexWrap: 'wrap'
                          }}
                        >
                          <span className={`badge badge-carrier-${w.kind}`}>
                            {formatCarrierLabel(w.kind)}
                          </span>
                          <span style={{ fontWeight: 600, flex: '1 1 150px' }}>{w.title}</span>
                          <StudioSelect
                            className={`studio-select-inline${isTentative ? ' studio-select-amber' : ''}`}
                            value={
                              WORK_ROLE_PRESETS.includes(w.roleName)
                                ? w.roleName
                                : w.roleName || '暂定'
                            }
                            disabled={busy}
                            onChange={(val) =>
                              void handleUpdateWorkRole(
                                w.animeId,
                                w.title,
                                val,
                                w.voiceActor
                              )
                            }
                            title="修改该角色在此作品中的定位（由关联角色自动补充的默认为「暂定」）"
                            ariaLabel={`${w.title} 角色定位`}
                            minMenuWidth={112}
                            maxMenuWidth={160}
                            options={[
                              ...WORK_ROLE_PRESETS.map((r) => ({ value: r, label: r })),
                              ...(w.roleName && !WORK_ROLE_PRESETS.includes(w.roleName)
                                ? [{ value: w.roleName, label: w.roleName }]
                                : [])
                            ]}
                          />
                          {w.voiceActor && (
                            <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                              CV: {w.voiceActor}
                            </span>
                          )}
                          <button
                            className="btn"
                            style={{ padding: '2px 7px', fontSize: '0.8462rem', color: 'var(--danger)' }}
                            disabled={busy}
                            onClick={() => void handleUnlinkWork(w.animeId, w.title)}
                          >
                            解绑
                          </button>
                        </div>
                      )
                    })}
                  </div>
                )}

                <div
                  style={{
                    padding: 8,
                    borderRadius: 'var(--radius-sm)',
                    background: 'var(--bg-panel)',
                    border: '1px dashed var(--border-subtle)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 6
                  }}
                >
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    <input
                      className="inline-input"
                      style={{ flex: '1 1 180px', height: 26, fontSize: '0.9231rem' }}
                      value={workSearch}
                      onChange={(e) => setWorkSearch(e.target.value)}
                      placeholder="搜索动画或书籍标题..."
                    />
                    <StudioSelect
                      className="studio-select-inline"
                      value={WORK_ROLE_PRESETS.includes(workRole) ? workRole : '主角'}
                      onChange={(val) => setWorkRole(val)}
                      ariaLabel="新增出演作品角色定位"
                      minMenuWidth={128}
                      maxMenuWidth={170}
                      options={WORK_ROLE_PRESETS.map((r) => ({
                        value: r,
                        label: `定位：${r}`
                      }))}
                    />
                    <input
                      className="inline-input"
                      style={{ width: 120, height: 26, fontSize: '0.9231rem' }}
                      value={workVa}
                      onChange={(e) => setWorkVa(e.target.value)}
                      placeholder="声优 CV（可选）"
                    />
                  </div>
                  {workSearch.trim() && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                      {candidateWorks.length === 0 ? (
                        <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                          未找到可关联的动画或书籍
                        </span>
                      ) : (
                        candidateWorks.map((w) => (
                          <div
                            key={w.id}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 8,
                              fontSize: '0.9231rem',
                              padding: '3px 6px',
                              borderRadius: 'var(--radius-sm)',
                              background: 'var(--bg-surface)'
                            }}
                          >
                            <span className={`badge badge-carrier-${w.kind}`}>
                              {formatCarrierLabel(w.kind)}
                            </span>
                            <span style={{ flex: 1 }}>{w.title}</span>
                            <button
                              className="btn btn-primary"
                              style={{ padding: '2px 8px', fontSize: '0.8462rem' }}
                              disabled={busy}
                              onClick={() => void handleLinkWork(w.id, w.title)}
                            >
                              绑定
                            </button>
                          </div>
                        ))
                      )}
                    </div>
                  )}
                </div>
              </div>

              {/* 3. 角色 ↔ 角色关系 */}
              <div
                style={{
                  borderTop: '1px solid var(--border-subtle)',
                  paddingTop: 10,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8
                }}
              >
                <strong style={{ fontSize: '0.9615rem' }}>
                  角色 ↔ 角色关系 ({selectedChar.relations.length})
                </strong>
                {selectedChar.relations.length === 0 ? (
                  <div style={{ fontSize: '0.9231rem', color: 'var(--text-muted)' }}>
                    尚未建立与其他角色的关系（建立后会自动同步显示在画布「角色关系图谱」中）。
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                    {selectedChar.relations.map((rel) => (
                      <div
                        key={rel.id}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 8,
                          padding: '5px 8px',
                          borderRadius: 'var(--radius-sm)',
                          background: 'var(--bg-canvas)',
                          border: '1px solid var(--border-subtle)',
                          fontSize: '0.9231rem',
                          flexWrap: 'wrap'
                        }}
                      >
                        <span style={{ fontWeight: 600 }}>{rel.sourceName}</span>
                        <span
                          className="badge"
                          style={{
                            background: 'var(--badge-purple-bg)',
                            color: 'var(--badge-purple-fg)',
                            borderColor: 'var(--badge-purple-border)'
                          }}
                        >
                          {rel.relationType}
                        </span>
                        <span style={{ fontWeight: 600 }}>{rel.targetName}</span>
                        {(rel.sourceRole || rel.targetRole) && (
                          <span style={{ fontSize: '0.8846rem', color: 'var(--text-muted)' }}>
                            ({rel.sourceRole || '?'} ↔ {rel.targetRole || '?'})
                          </span>
                        )}
                        {rel.note && (
                          <span style={{ fontSize: '0.8846rem', color: 'var(--text-secondary)' }}>
                            · {rel.note}
                          </span>
                        )}
                        <button
                          className="btn"
                          style={{
                            marginLeft: 'auto',
                            padding: '2px 7px',
                            fontSize: '0.8462rem',
                            color: 'var(--danger)'
                          }}
                          disabled={busy}
                          onClick={() => void handleDeleteRelation(rel.id)}
                        >
                          删除
                        </button>
                      </div>
                    ))}
                  </div>
                )}

                {characters.length >= 2 && (
                  <div
                    style={{
                      padding: 8,
                      borderRadius: 'var(--radius-sm)',
                      background: 'var(--bg-panel)',
                      border: '1px dashed var(--border-subtle)',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 6
                    }}
                  >
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                      {characters.length > 30 && (
                        <input
                          className="inline-input"
                          style={{ width: 120, height: 26, fontSize: '0.9231rem' }}
                          value={relSearch}
                          onChange={(e) => setRelSearch(e.target.value)}
                          placeholder="筛选角色..."
                        />
                      )}
                      <StudioSelect
                        className="studio-select-inline studio-select-cap-lg"
                        value={relTargetId}
                        onChange={(val) => setRelTargetId(val)}
                        ariaLabel="选择关联角色"
                        minMenuWidth={176}
                        maxMenuWidth={260}
                        options={[
                          { value: '', label: '选择关联角色...' },
                          ...relCandidates.map((o) => ({
                            value: String(o.id),
                            label: o.nameCn || o.name
                          }))
                        ]}
                      />
                      <StudioSelect
                        className="studio-select-inline"
                        value={RELATION_PRESETS.includes(relType) ? relType : '__custom__'}
                        onChange={(val) => {
                          if (val !== '__custom__') setRelType(val)
                        }}
                        ariaLabel="预设角色关系类型"
                        minMenuWidth={132}
                        maxMenuWidth={180}
                        options={[
                          ...RELATION_PRESETS.map((p) => ({
                            value: p,
                            label: `关系：${p}`
                          })),
                          ...(!RELATION_PRESETS.includes(relType)
                            ? [{ value: '__custom__', label: relType || '自定义' }]
                            : [])
                        ]}
                      />
                      <input
                        className="inline-input"
                        style={{ width: 95, height: 26, fontSize: '0.9231rem' }}
                        value={relType}
                        onChange={(e) => setRelType(e.target.value)}
                        placeholder="关系类型"
                      />
                      <StudioSelect
                        className="studio-select-inline"
                        value={String(relStrength)}
                        onChange={(val) => setRelStrength(Number.parseInt(val, 10))}
                        title="关系紧密程度 (1-5)"
                        ariaLabel="关系紧密程度"
                        minMenuWidth={104}
                        maxMenuWidth={140}
                        options={[5, 4, 3, 2, 1].map((lv) => ({
                          value: String(lv),
                          label: `强度 ${lv}`
                        }))}
                      />
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                      <input
                        className="inline-input"
                        style={{ width: 110, height: 26, fontSize: '0.8846rem' }}
                        value={relSourceRole}
                        onChange={(e) => setRelSourceRole(e.target.value)}
                        placeholder="本角色身份（可选）"
                      />
                      <input
                        className="inline-input"
                        style={{ width: 110, height: 26, fontSize: '0.8846rem' }}
                        value={relTargetRole}
                        onChange={(e) => setRelTargetRole(e.target.value)}
                        placeholder="对方身份（可选）"
                      />
                      <input
                        className="inline-input"
                        style={{ flex: '1 1 130px', height: 26, fontSize: '0.8846rem' }}
                        value={relNote}
                        onChange={(e) => setRelNote(e.target.value)}
                        placeholder="关系备注（可选）"
                      />
                      <button
                        className="btn btn-primary"
                        style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
                        disabled={busy || !relTargetId}
                        onClick={() => void handleAddRelation()}
                      >
                        + 添加关系
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
