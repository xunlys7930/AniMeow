import React, { useEffect, useMemo, useState } from 'react'
import { formatCarrierLabel, statusOptionsFor, studioApi } from '../api'
import { useStudioDialog } from './StudioDialog'
import { StudioSelect } from './StudioSelect'
import type {
  CanvasCustomEdge,
  CanvasCustomNode,
  CharacterManageItem,
  CuratorItem,
  MediaCarrier
} from '../types'

export type CanvasEditTarget =
  | { kind: 'series'; seriesId: number; name: string }
  | { kind: 'work'; workKind: MediaCarrier; workId: number; parentSeriesId?: number | null }
  | { kind: 'character'; characterId: number }
  | { kind: 'new_character'; defaultWorkId?: number | null; defaultWorkTitle?: string | null }
  | { kind: 'custom_node'; node: CanvasCustomNode }
  | {
      kind: 'char_relation_edge'
      relationId: number
      sourceId: number
      targetId: number
      sourceLabel: string
      targetLabel: string
      rawLabel: string
    }
  | {
      kind: 'work_char_edge'
      animeId: number
      characterId: number
      workLabel: string
      charLabel: string
      rawLabel: string
    }
  | {
      kind: 'series_work_edge'
      seriesId: number
      workKind: MediaCarrier
      workId: number
      seriesLabel: string
      workLabel: string
      rawLabel: string
    }
  | { kind: 'custom_edge'; edge: CanvasCustomEdge }

interface CanvasQuickEditBarProps {
  target: CanvasEditTarget | null
  items: CuratorItem[]
  onClose: () => void
  onChanged: (tip: string) => Promise<void>
  onOpenCharacterManage?: (characterId?: number | null) => void
  onUpdateCustomNode: (node: CanvasCustomNode) => Promise<void>
  onDeleteCustomNode: (id: string) => Promise<void>
  onUpdateCustomEdge: (edge: CanvasCustomEdge) => Promise<void>
  onDeleteCustomEdge: (edgeId: string) => Promise<void>
}

const SERIES_ROLES = ['本篇', '续作', '剧场版', 'OVA / 特别篇', '外传 / 衍生', '原作轻小说', '原作漫画', '游戏关联', '音声关联', '系列成员']
const CHAR_WORK_ROLES = ['主角', '主要角色', '女主', '男主', '配角', '客串', '暂定']
const CHAR_REL_PRESETS = ['恋人', '挚友', '兄妹', '姐弟', '家人', '师徒', '搭档', '对手', '同伴', '主从', '关联']
const CUSTOM_COLORS = ['#2f6d50', '#b5453d', '#2f5978', '#996a2a', '#6b4e9b']

export const CanvasQuickEditBar: React.FC<CanvasQuickEditBarProps> = ({
  target,
  items,
  onClose,
  onChanged,
  onOpenCharacterManage,
  onUpdateCustomNode,
  onDeleteCustomNode,
  onUpdateCustomEdge,
  onDeleteCustomEdge
}) => {
  const dialog = useStudioDialog()
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  // 系列字段
  const [seriesName, setSeriesName] = useState('')
  const [addWorkQuery, setAddWorkQuery] = useState('')
  const [addWorkRole, setAddWorkRole] = useState('系列成员')

  // 作品字段
  const [workTitle, setWorkTitle] = useState('')
  const [workStatus, setWorkStatus] = useState('')
  const [workRating, setWorkRating] = useState('')
  const [workSeriesRole, setWorkSeriesRole] = useState('')

  // 角色字段
  const [charLoaded, setCharLoaded] = useState<CharacterManageItem | null>(null)
  const [charNameCn, setCharNameCn] = useState('')
  const [charName, setCharName] = useState('')
  const [charGender, setCharGender] = useState('')
  const [charRating, setCharRating] = useState('')
  const [charWorkQuery, setCharWorkQuery] = useState('')
  const [charWorkRole, setCharWorkRole] = useState('主角')

  // 边字段
  const [edgeLabel, setEdgeLabel] = useState('')
  const [edgeExtra, setEdgeExtra] = useState('') // CV 或 双方称呼
  const [edgeExtra2, setEdgeExtra2] = useState('')
  const [edgeNote, setEdgeNote] = useState('')
  const [loadedRelation, setLoadedRelation] = useState<{ id: number; strength: number } | null>(null)

  // 自定义节点字段
  const [customLabel, setCustomLabel] = useState('')
  const [customSub, setCustomSub] = useState('')
  const [customColor, setCustomColor] = useState(CUSTOM_COLORS[0])

  const currentWork = useMemo(() => {
    if (!target || target.kind !== 'work') return null
    return items.find((it) => it.kind === target.workKind && it.id === target.workId) ?? null
  }, [target, items])

  useEffect(() => {
    setErr(null)
    if (!target) return
    if (target.kind === 'series') {
      setSeriesName(target.name)
      setAddWorkQuery('')
      setAddWorkRole('系列成员')
    } else if (target.kind === 'work' && currentWork) {
      setWorkTitle(currentWork.title)
      setWorkStatus(currentWork.status)
      setWorkRating(currentWork.rawRating != null ? String(currentWork.rawRating) : '')
      setWorkSeriesRole(currentWork.seriesRole ?? '')
    } else if (target.kind === 'new_character') {
      setCharLoaded(null)
      setCharNameCn('')
      setCharName('')
      setCharGender('')
      setCharRating('')
      setCharWorkRole('主角')
    } else if (target.kind === 'character') {
      setCharLoaded(null)
      let cancelled = false
      studioApi
        .listCharactersManage()
        .then((list) => {
          if (cancelled) return
          const found = list.find((c) => c.id === target.characterId) ?? null
          setCharLoaded(found)
          if (found) {
            setCharNameCn(found.nameCn)
            setCharName(found.name)
            setCharGender(found.gender)
            setCharRating(found.rating != null ? String(found.rating) : '')
          } else {
            setErr('找不到要编辑的角色档案。')
          }
        })
        .catch((error) => {
          if (!cancelled) setErr('读取角色档案失败：' + String(error))
        })
      setCharWorkQuery('')
      setCharWorkRole('主角')
      return () => {
        cancelled = true
      }
    } else if (target.kind === 'custom_node') {
      setCustomLabel(target.node.label)
      setCustomSub(target.node.subtitle)
      setCustomColor(target.node.color)
    } else if (target.kind === 'custom_edge') {
      setEdgeLabel(target.edge.label)
    } else if (target.kind === 'series_work_edge') {
      setEdgeLabel(target.rawLabel || '系列成员')
    } else if (target.kind === 'work_char_edge') {
      const parts = target.rawLabel.split(' · ')
      const vaPart = parts.find((p) => p.startsWith('CV:'))
      const rolePart = parts.find((p) => !p.startsWith('CV:')) ?? '出场'
      setEdgeLabel(rolePart === '出场' ? '主角' : rolePart)
      setEdgeExtra(vaPart ? vaPart.replace(/^CV:\s*/, '') : '')
    } else if (target.kind === 'char_relation_edge') {
      setLoadedRelation(null)
      let cancelled = false
      setEdgeLabel(target.rawLabel.split('（')[0] || '关联')
      setEdgeExtra('')
      setEdgeExtra2('')
      setEdgeNote('')
      studioApi
        .listCharactersManage()
        .then((list) => {
          if (cancelled) return
          const src = list.find((c) => c.id === target.sourceId)
          const rel = src?.relations.find((r) => r.id === target.relationId)
          if (rel) {
            setEdgeLabel(rel.relationType)
            setEdgeExtra(rel.sourceRole)
            setEdgeExtra2(rel.targetRole)
            setEdgeNote(rel.note)
            setLoadedRelation({ id: rel.id, strength: rel.strength })
          } else {
            setErr('找不到要编辑的角色关系。')
          }
        })
        .catch((error) => {
          if (!cancelled) setErr('读取角色关系失败：' + String(error))
        })
      return () => {
        cancelled = true
      }
    }
  }, [target, currentWork?.id, currentWork?.title, currentWork?.status, currentWork?.rawRating, currentWork?.seriesRole])

  const seriesAddCandidates = useMemo(() => {
    const q = addWorkQuery.trim().toLowerCase()
    if (!q || target?.kind !== 'series') return []
    return items
      .filter(
        (it) =>
          it.seriesName !== target.name &&
          (it.title.toLowerCase().includes(q) || it.originalTitle.toLowerCase().includes(q))
      )
      .slice(0, 6)
  }, [items, addWorkQuery, target])

  const charAddWorkCandidates = useMemo(() => {
    const q = charWorkQuery.trim().toLowerCase()
    if (!q || target?.kind !== 'character') return []
    const linked = new Set(charLoaded?.works.map((w) => w.animeId) ?? [])
    return items
      .filter(
        (it) =>
          (it.kind === 'anime' || it.kind === 'book') &&
          !linked.has(it.id) &&
          (it.title.toLowerCase().includes(q) || it.originalTitle.toLowerCase().includes(q))
      )
      .slice(0, 6)
  }, [items, charWorkQuery, target, charLoaded])

  if (!target) return null

  const runAction = async (fn: () => Promise<string>, closeAfter = false) => {
    setBusy(true)
    setErr(null)
    try {
      const tip = await fn()
      await onChanged(tip)
      if (closeAfter) onClose()
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      style={{
        padding: '8px 14px',
        borderBottom: '1px solid var(--border-subtle)',
        background: 'var(--bg-panel)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {target.kind === 'series' && (
          <>
            <span className="badge badge-vault">编辑系列</span>
            <input
              className="inline-input"
              style={{ width: 200, height: 26, fontSize: '0.9615rem', fontWeight: 600 }}
              value={seriesName}
              onChange={(e) => setSeriesName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && seriesName.trim()) {
                  void runAction(async () => {
                    await studioApi.renameSeries(target.seriesId, seriesName.trim())
                    return `已重命名系列为「${seriesName.trim()}」`
                  })
                }
              }}
              placeholder="系列名称"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy || !seriesName.trim() || seriesName.trim() === target.name}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.renameSeries(target.seriesId, seriesName.trim())
                  return `已重命名系列为「${seriesName.trim()}」`
                })
              }
            >
              保存名称
            </button>
            <input
              className="inline-input"
              style={{ width: 150, height: 26, fontSize: '0.9231rem' }}
              value={addWorkQuery}
              onChange={(e) => setAddWorkQuery(e.target.value)}
              placeholder="+ 搜作品加入系列..."
            />
            <StudioSelect
              className="studio-select-inline"
              value={addWorkRole}
              onChange={(val) => setAddWorkRole(val)}
              ariaLabel="加入系列的定位"
              minMenuWidth={132}
              maxMenuWidth={180}
              options={SERIES_ROLES.map((r) => ({
                value: r,
                label: `定位：${r}`
              }))}
            />
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={async () => {
                const ok = await dialog.confirm({
                  title: '解散系列',
                  message: `确定解散系列「${target.name}」吗？`,
                  detail: '仅解除作品的系列归属，不会删除作品本身。',
                  confirmText: '确认解散',
                  tone: 'danger'
                })
                if (!ok) return
                await runAction(async () => {
                  await studioApi.deleteSeries(target.seriesId)
                  return `已解散系列「${target.name}」`
                }, true)
              }}
            >
              解散系列
            </button>
          </>
        )}

        {target.kind === 'work' && currentWork && (
          <>
            <span className={`badge badge-carrier-${currentWork.kind}`}>
              编辑{formatCarrierLabel(currentWork.kind)}
            </span>
            <input
              className="inline-input"
              style={{ width: 200, height: 26, fontSize: '0.9615rem', fontWeight: 600 }}
              value={workTitle}
              onChange={(e) => setWorkTitle(e.target.value)}
              placeholder="作品标题"
            />
            <StudioSelect
              className="studio-select-inline"
              value={workStatus}
              onChange={(val) => setWorkStatus(val)}
              ariaLabel="作品状态"
              minMenuWidth={112}
              maxMenuWidth={160}
              options={statusOptionsFor(currentWork.kind).map((st) => ({
                value: st,
                label: st
              }))}
            />
            <input
              className="inline-input"
              style={{ width: 76, height: 26, fontSize: '0.9231rem' }}
              value={workRating}
              onChange={(e) => setWorkRating(e.target.value)}
              placeholder="评分0-100"
              title="个人评分（0–100，如 85 代表 8.5 分）"
            />
            {currentWork.seriesName && (
              <input
                className="inline-input"
                style={{ width: 96, height: 26, fontSize: '0.9231rem' }}
                value={workSeriesRole}
                onChange={(e) => setWorkSeriesRole(e.target.value)}
                placeholder="系列定位"
                title="该作品在所属系列中的定位"
              />
            )}
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy || !workTitle.trim()}
              onClick={() =>
                void runAction(async () => {
                  const parsedRating = workRating.trim() === '' ? 0 : Number.parseInt(workRating, 10)
                  await studioApi.updateCell({
                    kind: currentWork.kind,
                    id: currentWork.id,
                    title: workTitle.trim(),
                    status: workStatus,
                    rawRating: Number.isNaN(parsedRating)
                      ? undefined
                      : Math.max(0, Math.min(100, parsedRating)),
                    seriesRole: currentWork.seriesName ? workSeriesRole.trim() : undefined
                  })
                  return `已保存《${workTitle.trim()}》修改`
                })
              }
            >
              保存作品
            </button>
            {target.parentSeriesId != null && (
              <button
                className="btn"
                style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
                disabled={busy}
                onClick={() =>
                  void runAction(async () => {
                    await studioApi.removeMemberFromSeries(
                      target.parentSeriesId!,
                      currentWork.kind,
                      currentWork.id
                    )
                    return `已将《${currentWork.title}》移出系列`
                  }, true)
                }
              >
                移出系列
              </button>
            )}
          </>
        )}

        {(target.kind === 'character' || target.kind === 'new_character') && (
          <>
            <span
              className="badge"
              style={{
                background: 'var(--badge-purple-bg)',
                color: 'var(--badge-purple-fg)',
                borderColor: 'var(--badge-purple-border)'
              }}
            >
              {target.kind === 'new_character' ? '画布新建角色' : '编辑角色'}
            </span>
            <input
              className="inline-input"
              style={{ width: 130, height: 26, fontSize: '0.9615rem', fontWeight: 600 }}
              value={charNameCn}
              onChange={(e) => setCharNameCn(e.target.value)}
              placeholder="中文名"
              autoFocus={target.kind === 'new_character'}
            />
            <input
              className="inline-input"
              style={{ width: 130, height: 26, fontSize: '0.9231rem' }}
              value={charName}
              onChange={(e) => setCharName(e.target.value)}
              placeholder="原名（可选）"
            />
            <input
              className="inline-input"
              style={{ width: 64, height: 26, fontSize: '0.9231rem' }}
              value={charGender}
              onChange={(e) => setCharGender(e.target.value)}
              placeholder="性别"
            />
            <input
              className="inline-input"
              style={{ width: 74, height: 26, fontSize: '0.9231rem' }}
              value={charRating}
              onChange={(e) => setCharRating(e.target.value)}
              placeholder="评分1-100"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy || (!charNameCn.trim() && !charName.trim()) || (target.kind === 'character' && charLoaded?.id !== target.characterId)}
              onClick={() => {
                if (target.kind === 'character' && charLoaded?.id !== target.characterId) return
                void runAction(async () => {
                  const parsedRating = Number.parseInt(charRating, 10)
                  const id = await studioApi.upsertCharacter({
                    id: target.kind === 'character' ? target.characterId : null,
                    name: charName.trim() || charNameCn.trim(),
                    nameCn: charNameCn.trim() || null,
                    gender: charGender.trim() || null,
                    rating: Number.isNaN(parsedRating) ? null : parsedRating,
                    imageUrl: charLoaded?.imageUrl || null,
                    summary: charLoaded?.summary || null,
                    review: charLoaded?.review || null,
                    birthMonth: charLoaded?.birthMonth ?? null,
                    birthDay: charLoaded?.birthDay ?? null,
                    bloodType: charLoaded?.bloodType || null
                  })
                  if (target.kind === 'new_character' && target.defaultWorkId) {
                    await studioApi.linkCharacterWork(id, target.defaultWorkId, charWorkRole, null)
                  }
                  return target.kind === 'new_character'
                    ? `已新建角色「${charNameCn.trim() || charName.trim()}」`
                    : `已保存角色「${charNameCn.trim() || charName.trim()}」`
                }, target.kind === 'new_character')
              }}
            >
              {target.kind === 'new_character'
                ? target.defaultWorkTitle
                  ? `创建并绑定《${target.defaultWorkTitle.slice(0, 8)}》`
                  : '创建角色'
                : '保存角色'}
            </button>
            {target.kind === 'character' && (
              <>
                <input
                  className="inline-input"
                  style={{ width: 140, height: 26, fontSize: '0.9231rem' }}
                  value={charWorkQuery}
                  onChange={(e) => setCharWorkQuery(e.target.value)}
                  placeholder="+ 搜动画/书籍绑定..."
                />
                {onOpenCharacterManage && (
                  <button
                    className="btn"
                    style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem' }}
                    disabled={busy}
                    onClick={() => onOpenCharacterManage(target.characterId)}
                    title="在角色管理窗口中打开并选中该角色"
                  >
                    打开角色详情
                  </button>
                )}
                <button
                  className="btn"
                  style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
                  disabled={busy}
                  onClick={async () => {
                    const disp = charNameCn.trim() || charName.trim() || `#${target.characterId}`
                    const ok = await dialog.confirm({
                      title: '删除角色',
                      message: `确定删除角色「${disp}」吗？`,
                      detail: '将同步移除该角色的所有作品出演关联与角色间关系，不可撤销。',
                      confirmText: '确认删除',
                      tone: 'danger'
                    })
                    if (!ok) return
                    await runAction(async () => {
                      await studioApi.deleteCharacter(target.characterId)
                      return `已删除角色「${disp}」`
                    }, true)
                  }}
                >
                  删除角色
                </button>
              </>
            )}
          </>
        )}

        {target.kind === 'char_relation_edge' && (
          <>
            <span
              className="badge"
              style={{
                background: 'var(--badge-purple-bg)',
                color: 'var(--badge-purple-fg)',
                borderColor: 'var(--badge-purple-border)'
              }}
            >
              角色关系：{target.sourceLabel} ↔ {target.targetLabel}
            </span>
            <StudioSelect
              className="studio-select-inline"
              value={CHAR_REL_PRESETS.includes(edgeLabel) ? edgeLabel : '__custom__'}
              onChange={(val) => {
                if (val !== '__custom__') setEdgeLabel(val)
              }}
              ariaLabel="角色关系预设"
              minMenuWidth={120}
              maxMenuWidth={170}
              options={[
                ...CHAR_REL_PRESETS.map((p) => ({ value: p, label: p })),
                ...(!CHAR_REL_PRESETS.includes(edgeLabel)
                  ? [{ value: '__custom__', label: edgeLabel || '自定义' }]
                  : [])
              ]}
            />
            <input
              className="inline-input"
              style={{ width: 92, height: 26, fontSize: '0.9231rem' }}
              value={edgeLabel}
              onChange={(e) => setEdgeLabel(e.target.value)}
              placeholder="关系类型"
            />
            <input
              className="inline-input"
              style={{ width: 92, height: 26, fontSize: '0.9231rem' }}
              value={edgeExtra}
              onChange={(e) => setEdgeExtra(e.target.value)}
              placeholder="源角色称呼"
            />
            <input
              className="inline-input"
              style={{ width: 92, height: 26, fontSize: '0.9231rem' }}
              value={edgeExtra2}
              onChange={(e) => setEdgeExtra2(e.target.value)}
              placeholder="目标称呼"
            />
            <input
              className="inline-input"
              style={{ width: 120, height: 26, fontSize: '0.9231rem' }}
              value={edgeNote}
              onChange={(e) => setEdgeNote(e.target.value)}
              placeholder="备注（可选）"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy || loadedRelation?.id !== target.relationId}
              onClick={() => {
                if (loadedRelation?.id !== target.relationId) return
                void runAction(async () => {
                  await studioApi.upsertCharacterRelation({
                    id: target.relationId,
                    sourceCharacterId: target.sourceId,
                    targetCharacterId: target.targetId,
                    relationType: edgeLabel.trim() || '关联',
                    sourceRole: edgeExtra.trim() || null,
                    targetRole: edgeExtra2.trim() || null,
                    note: edgeNote.trim() || null,
                    strength: loadedRelation.strength
                  })
                  return `已更新角色关系为「${edgeLabel.trim() || '关联'}」`
                })
              }}
            >
              保存关系
            </button>
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={async () => {
                const ok = await dialog.confirm({
                  title: '删除角色关系',
                  message: `确定移除「${target.sourceLabel}」与「${target.targetLabel}」之间的关系连线吗？`,
                  confirmText: '确认移除',
                  tone: 'danger'
                })
                if (!ok) return
                await runAction(async () => {
                  await studioApi.deleteCharacterRelation(target.relationId)
                  return '已删除该角色关系'
                }, true)
              }}
            >
              删除关系
            </button>
          </>
        )}

        {target.kind === 'work_char_edge' && (
          <>
            <span className="badge">
              出演关联：《{target.workLabel}》→ {target.charLabel}
            </span>
            <StudioSelect
              className="studio-select-inline"
              value={CHAR_WORK_ROLES.includes(edgeLabel) ? edgeLabel : '__custom__'}
              onChange={(val) => {
                if (val !== '__custom__') setEdgeLabel(val)
              }}
              ariaLabel="出演角色定位"
              minMenuWidth={132}
              maxMenuWidth={180}
              options={[
                ...CHAR_WORK_ROLES.map((r) => ({ value: r, label: `定位：${r}` })),
                ...(!CHAR_WORK_ROLES.includes(edgeLabel)
                  ? [{ value: '__custom__', label: edgeLabel || '自定义' }]
                  : [])
              ]}
            />
            <input
              className="inline-input"
              style={{ width: 120, height: 26, fontSize: '0.9231rem' }}
              value={edgeExtra}
              onChange={(e) => setEdgeExtra(e.target.value)}
              placeholder="声优 CV（可选）"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.linkCharacterWork(
                    target.characterId,
                    target.animeId,
                    edgeLabel.trim() || '主角',
                    edgeExtra.trim() || null
                  )
                  return `已更新角色出演定位为「${edgeLabel.trim() || '主角'}」`
                })
              }
            >
              保存定位
            </button>
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.unlinkCharacterWork(target.characterId, target.animeId)
                  return `已解除「${target.charLabel}」与《${target.workLabel}》的关联`
                }, true)
              }
            >
              解除关联
            </button>
          </>
        )}

        {target.kind === 'series_work_edge' && (
          <>
            <span className="badge badge-vault">
              系列成员：{target.seriesLabel} → 《{target.workLabel}》
            </span>
            <StudioSelect
              className="studio-select-inline"
              value={SERIES_ROLES.includes(edgeLabel) ? edgeLabel : '__custom__'}
              onChange={(val) => {
                if (val !== '__custom__') setEdgeLabel(val)
              }}
              ariaLabel="系列成员定位"
              minMenuWidth={128}
              maxMenuWidth={180}
              options={[
                ...SERIES_ROLES.map((r) => ({ value: r, label: r })),
                ...(!SERIES_ROLES.includes(edgeLabel)
                  ? [{ value: '__custom__', label: edgeLabel || '自定义' }]
                  : [])
              ]}
            />
            <input
              className="inline-input"
              style={{ width: 100, height: 26, fontSize: '0.9231rem' }}
              value={edgeLabel}
              onChange={(e) => setEdgeLabel(e.target.value)}
              placeholder="自定义定位"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.setSeriesMemberRole(
                    target.seriesId,
                    target.workKind,
                    target.workId,
                    edgeLabel.trim() || '系列成员'
                  )
                  return `已更新系列成员定位为「${edgeLabel.trim() || '系列成员'}」`
                })
              }
            >
              保存定位
            </button>
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.removeMemberFromSeries(
                    target.seriesId,
                    target.workKind,
                    target.workId
                  )
                  return `已将《${target.workLabel}》移出系列「${target.seriesLabel}」`
                }, true)
              }
            >
              移出系列
            </button>
          </>
        )}

        {target.kind === 'custom_node' && (
          <>
            <span className="badge">编辑自定义节点</span>
            <input
              className="inline-input"
              style={{ width: 170, height: 26, fontSize: '0.9615rem', fontWeight: 600 }}
              value={customLabel}
              onChange={(e) => setCustomLabel(e.target.value)}
              placeholder="节点标题"
            />
            <input
              className="inline-input"
              style={{ width: 180, height: 26, fontSize: '0.9231rem' }}
              value={customSub}
              onChange={(e) => setCustomSub(e.target.value)}
              placeholder="说明文字"
            />
            <div style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
              {CUSTOM_COLORS.map((c) => (
                <button
                  key={c}
                  onClick={() => setCustomColor(c)}
                  style={{
                    width: 16,
                    height: 16,
                    borderRadius: '50%',
                    background: c,
                    border: customColor === c ? '2px solid var(--text-primary)' : '1px solid rgba(0,0,0,0.2)',
                    cursor: 'pointer'
                  }}
                />
              ))}
            </div>
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy || !customLabel.trim()}
              onClick={() =>
                void runAction(async () => {
                  await onUpdateCustomNode({
                    ...target.node,
                    label: customLabel.trim(),
                    subtitle: customSub.trim(),
                    color: customColor
                  })
                  return '已更新自定义节点'
                })
              }
            >
              保存
            </button>
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await onDeleteCustomNode(target.node.id)
                  return '已删除自定义节点'
                }, true)
              }
            >
              删除节点
            </button>
          </>
        )}

        {target.kind === 'custom_edge' && (
          <>
            <span className="badge">编辑自定义连线</span>
            <input
              className="inline-input"
              style={{ width: 160, height: 26, fontSize: '0.9231rem' }}
              value={edgeLabel}
              onChange={(e) => setEdgeLabel(e.target.value)}
              placeholder="连线标签"
            />
            <button
              className="btn btn-primary"
              style={{ height: 26, padding: '0 10px', fontSize: '0.8846rem' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await onUpdateCustomEdge({
                    ...target.edge,
                    label: edgeLabel.trim() || '关联'
                  })
                  return '已更新自定义连线标签'
                })
              }
            >
              保存标签
            </button>
            <button
              className="btn"
              style={{ height: 26, padding: '0 9px', fontSize: '0.8846rem', color: 'var(--danger)' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await onDeleteCustomEdge(target.edge.id)
                  return '已删除自定义连线'
                }, true)
              }
            >
              删除连线
            </button>
          </>
        )}

        <button
          className="btn btn-ghost"
          style={{ marginLeft: 'auto', height: 24, fontSize: '0.9231rem' }}
          onClick={onClose}
          title="收起编辑条"
        >
          ✕
        </button>
      </div>

      {/* 系列快速添加候选结果 */}
      {target.kind === 'series' && seriesAddCandidates.length > 0 && (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          {seriesAddCandidates.map((w) => (
            <button
              key={`${w.kind}:${w.id}`}
              className="btn"
              style={{ height: 24, padding: '0 8px', fontSize: '0.8846rem' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.batchSeries([[w.kind, w.id]], target.name, addWorkRole)
                  setAddWorkQuery('')
                  return `已将《${w.title}》加入系列「${target.name}」`
                })
              }
            >
              + [{formatCarrierLabel(w.kind)}] {w.title}
            </button>
          ))}
        </div>
      )}

      {/* 角色快速绑定作品候选结果 */}
      {target.kind === 'character' && charAddWorkCandidates.length > 0 && (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          {charAddWorkCandidates.map((w) => (
            <button
              key={w.id}
              className="btn"
              style={{ height: 24, padding: '0 8px', fontSize: '0.8846rem' }}
              disabled={busy}
              onClick={() =>
                void runAction(async () => {
                  await studioApi.linkCharacterWork(target.characterId, w.id, charWorkRole, null)
                  setCharWorkQuery('')
                  return `已绑定角色与《${w.title}》（相关联角色同步补充为暂定）`
                })
              }
            >
              + [{formatCarrierLabel(w.kind)}] {w.title}
            </button>
          ))}
        </div>
      )}

      {err && <div style={{ fontSize: '0.8846rem', color: 'var(--danger)' }}>{err}</div>}
    </div>
  )
}
