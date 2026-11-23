const pad2 = (n: number): string => String(n).padStart(2, '0')

export function parseAgentTimestamp(iso?: string | null): Date | null {
  if (!iso) return null
  const trimmed = iso.trim()
  if (!trimmed) return null
  let date = new Date(trimmed)
  if (Number.isNaN(date.getTime()) && trimmed.includes(' ') && !trimmed.includes('T')) {
    date = new Date(trimmed.replace(' ', 'T'))
  }
  return Number.isNaN(date.getTime()) ? null : date
}

export function getLocalDayKey(iso?: string | null): string | null {
  const date = parseAgentTimestamp(iso)
  if (!date) return null
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

function getRelativeDayOffset(target: Date, now: Date): number {
  const targetStart = new Date(target.getFullYear(), target.getMonth(), target.getDate()).getTime()
  const nowStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return Math.round((nowStart - targetStart) / 86_400_000)
}

export function formatAgentFullTimestamp(iso?: string | null): string {
  const date = parseAgentTimestamp(iso)
  if (!date) return ''
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
}

export function formatAgentMessageTime(iso?: string | null, now: Date = new Date()): string {
  const date = parseAgentTimestamp(iso)
  if (!date) return ''
  const hhmm = `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  const dayDiff = getRelativeDayOffset(date, now)
  if (dayDiff === 0) {
    return hhmm
  }
  if (dayDiff === 1) {
    return `昨天 ${hhmm}`
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${hhmm}`
  }
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${hhmm}`
}

export function formatAgentDateDivider(iso?: string | null, now: Date = new Date()): string {
  const date = parseAgentTimestamp(iso)
  if (!date) return ''
  const dayDiff = getRelativeDayOffset(date, now)
  const monthDay = `${date.getMonth() + 1}月${date.getDate()}日`
  if (dayDiff === 0) {
    return `今天 · ${monthDay}`
  }
  if (dayDiff === 1) {
    return `昨天 · ${monthDay}`
  }
  if (date.getFullYear() === now.getFullYear()) {
    return monthDay
  }
  return `${date.getFullYear()}年${monthDay}`
}

export function formatAgentSessionTime(iso?: string | null, now: Date = new Date()): string {
  const date = parseAgentTimestamp(iso)
  if (!date) return ''
  const dayDiff = getRelativeDayOffset(date, now)
  if (dayDiff === 0) {
    return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  }
  if (dayDiff === 1) {
    return '昨天'
  }
  if (date.getFullYear() === now.getFullYear()) {
    return `${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
  }
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}
