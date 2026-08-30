import type { FirstBoardAlertCandidate, FirstBoardAlertSnapshot } from '../types'

const STORAGE_KEY = 'ai-stock:first-board-alert:notified:v1'
const NOTIFIABLE_LEVELS = new Set([
  'STRONG',
  'ACCELERATING',
  'REPEAT_STRONG',
  'REPEAT_ACCELERATING'
])
const LEVEL_RANK: Record<string, number> = {
  STRONG: 1,
  REPEAT_STRONG: 1,
  ACCELERATING: 2,
  REPEAT_ACCELERATING: 2
}

export function collectNewFirstBoardNotifications(
  snapshot: FirstBoardAlertSnapshot,
  storage: Storage | null = browserStorage()
): FirstBoardAlertCandidate[] {
  if (snapshot.status !== 'READY' || !storage) return []
  const stored = loadKeys(storage).filter((key) => key.startsWith(`${snapshot.tradeDate}:`))
  const known = new Set(stored)
  const fresh: FirstBoardAlertCandidate[] = []
  const candidates = [...snapshot.latentCandidates, ...snapshot.previousLimitUpCandidates]
  for (const candidate of candidates) {
    if (!NOTIFIABLE_LEVELS.has(candidate.level)) continue
    const key = `${snapshot.tradeDate}:${candidate.symbol}:${candidate.level}`
    if (known.has(key)) continue
    const symbolPrefix = `${snapshot.tradeDate}:${candidate.symbol}:`
    const highestNotifiedRank = [...known]
      .filter((storedKey) => storedKey.startsWith(symbolPrefix))
      .reduce((highest, storedKey) => {
        const storedLevel = storedKey.slice(symbolPrefix.length)
        return Math.max(highest, LEVEL_RANK[storedLevel] ?? 0)
      }, 0)
    if (highestNotifiedRank >= (LEVEL_RANK[candidate.level] ?? 0)) continue
    known.add(key)
    fresh.push(candidate)
  }
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify([...known]))
  } catch {
    return []
  }
  return fresh
}

function loadKeys(storage: Storage): string[] {
  try {
    const parsed = JSON.parse(storage.getItem(STORAGE_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

function browserStorage() {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}
