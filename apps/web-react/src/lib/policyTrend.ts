import type { PolicySignal, PolicyTheme } from '../types'

const DAY_MS = 86400000
function beijingDay(timestamp: number) {
  return new Date(timestamp + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

function validDay(day: string) {
  const timestamp = Date.parse(`${day}T00:00:00Z`)
  return Number.isFinite(timestamp) && new Date(timestamp).toISOString().slice(0, 10) === day
}

function publication(value: string | null): { day: string | null; instant: number | null } {
  const text = value?.trim() ?? ''
  if (/^\d{4}-\d{2}-\d{2}$/.test(text) && validDay(text)) return { day: text, instant: null }
  const match = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$/)
  if (!match || !validDay(match[1]) || Number(match[2]) > 23 || Number(match[3]) > 59 || Number(match[4] ?? 0) > 59) {
    return { day: null, instant: null }
  }
  const offset = match[6] ?? '+08:00'
  if (offset !== 'Z') {
    const hours = Number(offset.slice(1, 3))
    const minutes = Number(offset.slice(4))
    if (hours > 18 || minutes > 59 || (hours === 18 && minutes > 0)) return { day: null, instant: null }
  }
  const fraction = match[5] ? `.${match[5].slice(0, 3)}` : ''
  const instant = Date.parse(`${match[1]}T${match[2]}:${match[3]}:${match[4] ?? '00'}${fraction}${offset}`)
  return Number.isFinite(instant) ? { day: beijingDay(instant), instant } : { day: null, instant: null }
}

/** Counts describe the collected sample, never total policy issuance or market heat. */
export function policyEvidence(theme: PolicyTheme, now = new Date()) {
  const today = beijingDay(now.getTime())
  const unique = new Map<string, PolicySignal>()
  for (const signal of theme.signals) {
    const key = signal.summary.replace(/\s/g, '').trim()
    if (key && !unique.has(key)) unique.set(key, signal)
  }
  const signals = [...unique.values()]
  const dayAge = (signal: PolicySignal) => {
    const day = publication(signal.publishedAt).day
    if (!day) return null
    return Math.floor((Date.parse(`${today}T00:00:00Z`) - Date.parse(`${day}T00:00:00Z`)) / DAY_MS)
  }
  const eligible = signals.filter(signal => {
    const time = publication(signal.publishedAt)
    if (time.instant !== null && time.instant > now.getTime()) return false
    const age = dayAge(signal)
    return age === null || age >= 0
  }).sort((a, b) => {
    const first = publication(a.publishedAt)
    const second = publication(b.publishedAt)
    return (second.day ?? '').localeCompare(first.day ?? '') || (second.instant ?? 0) - (first.instant ?? 0)
  })
  const recent = (days: number) => eligible.filter(signal => { const age = dayAge(signal); return age !== null && age <= days }).length
  return {
    signals: eligible,
    recent7: recent(7), recent30: recent(30), recent90: recent(90),
    unknown: eligible.filter(signal => dayAge(signal) === null).length,
    sources: new Set(eligible.map(signal => signal.source)).size,
    latest: eligible.find(signal => dayAge(signal) !== null)?.publishedAt ?? null,
  }
}

export function policySourceUrl(url: string | null) {
  if (!url) return null
  try { const value = new URL(url); return ['https:', 'http:'].includes(value.protocol) ? value.href : null } catch { return null }
}
