import type { CuratorItem, MediaCarrier } from '../types'

interface SeasonFingerprint {
  bangTier: number
  questionCount: number
  seasonMarker: string | null
  derivativeKind: string | null
}

function toHalfwidthLower(raw: string): string {
  let out = ''
  for (const ch of raw) {
    const code = ch.charCodeAt(0)
    if (code >= 0xff01 && code <= 0xff5e) {
      out += String.fromCharCode(code - 0xfee0).toLowerCase()
    } else if (ch === '　') {
      out += ' '
    } else if (ch === '’' || ch === '‘') {
      out += "'"
    } else if (ch === '—' || ch === '–') {
      out += '-'
    } else if (ch === '〜') {
      out += '~'
    } else {
      out += ch.toLowerCase()
    }
  }
  return out
}

export function normalizeExactTitle(raw: string): string {
  return toHalfwidthLower(raw).replace(/\s+/g, '')
}

export function normalizeLooseTitle(raw: string): string {
  return toHalfwidthLower(raw).replace(/[\s\p{P}\p{S}]+/gu, '')
}

function parseCnOrArabicNum(token: string): number | null {
  const t = token.trim()
  if (/^\d+$/.test(t)) {
    const n = Number(t)
    return n >= 1 && n <= 20 ? n : null
  }
  const map: Record<string, number> = {
    一: 1,
    二: 2,
    两: 2,
    三: 3,
    四: 4,
    五: 5,
    六: 6,
    七: 7,
    八: 8,
    九: 9,
    十: 10,
  }
  return map[t] ?? null
}

function extractSeasonFingerprint(raw: string): SeasonFingerprint {
  const norm = toHalfwidthLower(raw).trim()

  // 1. 尾部连续感叹号/撇号分层：0/1 个尾感叹号归为 0，允许「孤独摇滚」与「孤独摇滚！」互认；
  //    2 个及以上（如「xxx！」vs「xxx！！」「K-ON!」vs「K-ON!!」）或撇号+感叹号（「WORKING'!!」）严格隔离。
  const tailMatch = norm.match(/(['!?]+)$/)
  const tail = tailMatch ? tailMatch[1] : ''
  const bangCount = (tail.match(/!/g) ?? []).length
  const hasAposBang = tail.includes("'") && bangCount > 0
  const bangTier = hasAposBang ? 10 + bangCount : bangCount >= 2 ? bangCount : 0
  // 问号数量隔离（如「辉夜大小姐想让我告白～…」vs「辉夜大小姐想让我告白?～…」、「点兔？」vs「点兔？？」）
  const questionCount = (norm.match(/\?/g) ?? []).length

  // 2. 衍生形态识别
  let derivativeKind: string | null = null
  if (norm.includes('剧场版') || norm.includes('电影版') || /\bmovie\b/.test(norm)) {
    derivativeKind = 'movie'
  } else if (/\b(ova|oad)\b/.test(norm)) {
    derivativeKind = 'ova'
  } else if (norm.includes('总集篇') || /\brecap\b/.test(norm)) {
    derivativeKind = 'recap'
  } else if (norm.includes('特别篇') || norm.includes('番外') || /\b(special|sp)\b/.test(norm)) {
    derivativeKind = 'sp'
  } else if (norm.includes('外传') || norm.includes('前传')) {
    derivativeKind = 'spin'
  }

  // 3. 季数/续篇标记
  let seasonMarker: string | null = null
  const cnSeason = norm.match(/第([一二三四五六七八九十两\d]+)(?:部分|季|部|期|章|弹)/)
  if (cnSeason) {
    const n = parseCnOrArabicNum(cnSeason[1])
    if (n != null && n >= 2) seasonMarker = `s${n}`
  }
  if (!seasonMarker) {
    const enSeason = norm.match(/\b(?:season|part|cour|act)\s*(\d{1,2})\b/)
    if (enSeason) {
      const n = Number(enSeason[1])
      if (n >= 2 && n <= 20) seasonMarker = `s${n}`
    }
  }
  if (!seasonMarker) {
    const ord = norm.match(/\b([2-9])(?:nd|rd|th)\b/)
    if (ord) seasonMarker = `s${ord[1]}`
  }
  if (!seasonMarker) {
    const shortS = norm.match(/(?:^|\s)s([2-9])(?:\s|$)/)
    if (shortS) seasonMarker = `s${shortS[1]}`
  }
  if (!seasonMarker) {
    const roman = norm.match(/(?:^|\s)(ii|iii|iv|vi)(?:\s|$)|([ⅱⅲⅳ])$/)
    if (roman) {
      const r = roman[1] || roman[2]
      const map: Record<string, string> = {
        ii: 's2',
        'ⅱ': 's2',
        iii: 's3',
        'ⅲ': 's3',
        iv: 's4',
        'ⅳ': 's4',
        vi: 's6',
      }
      seasonMarker = map[r] ?? null
    }
  }
  if (!seasonMarker) {
    const mul = norm.match(/[×x]\s*([2-9])(?:$|\s)/)
    if (mul) seasonMarker = `x${mul[1]}`
  }
  if (!seasonMarker) {
    if (
      norm.includes('最终季') ||
      norm.includes('最终章') ||
      norm.includes('完结篇') ||
      norm.includes('final season')
    ) {
      seasonMarker = 'final'
    } else if (norm.includes('前篇')) {
      seasonMarker = 'part_first'
    } else if (norm.includes('后篇')) {
      seasonMarker = 'part_second'
    } else if (norm.includes('续篇') || norm.startsWith('续 ') || norm.startsWith('续·')) {
      seasonMarker = 'sequel'
    } else {
      const trailingDigit = norm.replace(/[!? ]+$/, '').match(/\s([2-9])$/)
      if (trailingDigit) seasonMarker = `n${trailingDigit[1]}`
    }
  }

  return { bangTier, questionCount, seasonMarker, derivativeKind }
}

function sameFingerprint(a: SeasonFingerprint, b: SeasonFingerprint): boolean {
  return (
    a.bangTier === b.bangTier &&
    a.questionCount === b.questionCount &&
    a.seasonMarker === b.seasonMarker &&
    a.derivativeKind === b.derivativeKind
  )
}

function bigramDice(a: string, b: string): number {
  if (a.length < 2 || b.length < 2) return 0
  const counts = new Map<string, number>()
  for (let i = 0; i < a.length - 1; i++) {
    const bg = a.slice(i, i + 2)
    counts.set(bg, (counts.get(bg) ?? 0) + 1)
  }
  let inter = 0
  for (let i = 0; i < b.length - 1; i++) {
    const bg = b.slice(i, i + 2)
    const c = counts.get(bg) ?? 0
    if (c > 0) {
      counts.set(bg, c - 1)
      inter++
    }
  }
  return (2 * inter) / (a.length - 1 + (b.length - 1))
}

export interface LocalWorkMatchResult {
  exact: CuratorItem | null
  suspected: { item: CuratorItem; reason: string } | null
}

/**
 * 发现页 / 放送表实时匹配本地藏品库：
 * - `exact`: 外部 ID 相同，或忽略空白/大小写完全同名
 * - `suspected`: 标点/单尾感叹号/原名/高相似度命中，且严格规避 xxx! vs xxx!!、不同季数与剧场版误判
 */
export function matchLocalWorkWithSuspicion(
  localItems: CuratorItem[],
  kind: MediaCarrier,
  subjectId: number,
  title: string,
  originalTitle?: string
): LocalWorkMatchResult {
  const sameKind = localItems.filter((item) => item.kind === kind)
  const targetSyncKey = subjectId > 0 ? `bangumi:${subjectId}` : ''

  // 1. 精确匹配外部 ID（通过 syncKey）
  if (targetSyncKey) {
    const byId = sameKind.find(
      (item) => item.syncKey.toLowerCase() === targetSyncKey.toLowerCase()
    )
    if (byId) return { exact: byId, suspected: null }
  }

  const exactTarget = normalizeExactTitle(title)
  const exactOrig = normalizeExactTitle(originalTitle ?? '')
  if (exactTarget) {
    const byExactTitle = sameKind.find((item) => {
      const itExact = normalizeExactTitle(item.title)
      return itExact === exactTarget || (exactOrig && itExact === exactOrig)
    })
    if (byExactTitle) return { exact: byExactTitle, suspected: null }
  }

  // 2. 疑似重复匹配（带季数/双感叹号/衍生作防误判守卫）
  const targetFp = extractSeasonFingerprint(title)
  const looseTarget = normalizeLooseTitle(title)
  const looseOrig = normalizeLooseTitle(originalTitle ?? '')

  for (const item of sameKind) {
    // 若本地条目已绑定同一个来源的不同 Bangumi ID，则明确是不同作品，不误报
    const itemKeyLower = item.syncKey.toLowerCase()
    if (targetSyncKey && itemKeyLower.startsWith('bangumi:') && itemKeyLower !== targetSyncKey) {
      continue
    }

    const itemFp = extractSeasonFingerprint(item.title)
    if (!sameFingerprint(targetFp, itemFp)) continue

    const itemLoose = normalizeLooseTitle(item.title)
    const itemLooseOrig = normalizeLooseTitle(item.originalTitle ?? '')

    if (looseTarget && itemLoose === looseTarget) {
      return {
        exact: null,
        suspected: { item, reason: '标点/符号差异（如尾感叹号、全半角或空格）' },
      }
    }
    if (
      (looseOrig && itemLoose === looseOrig) ||
      (itemLooseOrig && (itemLooseOrig === looseTarget || (looseOrig && itemLooseOrig === looseOrig)))
    ) {
      return {
        exact: null,
        suspected: { item, reason: '原名或别名与库中作品一致' },
      }
    }
    if (looseTarget.length >= 4 && itemLoose.length >= 4) {
      const ratio =
        Math.min(looseTarget.length, itemLoose.length) /
        Math.max(looseTarget.length, itemLoose.length)
      if (
        (ratio >= 0.72 &&
          (looseTarget.includes(itemLoose) || itemLoose.includes(looseTarget))) ||
        bigramDice(looseTarget, itemLoose) >= 0.82
      ) {
        return {
          exact: null,
          suspected: { item, reason: '名称高度相似（疑似不同平台命名差异）' },
        }
      }
    }
  }

  return { exact: null, suspected: null }
}
