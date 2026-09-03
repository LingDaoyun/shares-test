import type {
  PotentialLeaderCandidate,
  PotentialLeaderGrade,
  PotentialLeaderSnapshot
} from '../types'

const STORAGE_KEY = 'ai-stock:potential-leader:notified:v1'

interface NotificationState {
  tradeDate: string
  grades: Record<string, PotentialLeaderGrade>
  notifiedA: string[]
}

export function collectPotentialLeaderNotifications(
  snapshot: PotentialLeaderSnapshot,
  storage: Storage | null = browserStorage()
): PotentialLeaderCandidate[] {
  if (snapshot.status !== 'READY' || !storage) return []

  const previous = loadState(storage)
  const state: NotificationState = previous?.tradeDate === snapshot.tradeDate
    ? previous
    : { tradeDate: snapshot.tradeDate, grades: {}, notifiedA: [] }
  const notifiedA = new Set(state.notifiedA)
  const fresh: PotentialLeaderCandidate[] = []

  for (const candidate of [...snapshot.potentialCandidates, ...snapshot.competitionCandidates]) {
    const key = candidateKey(candidate)
    state.grades[key] = candidate.grade
    if (candidate.grade !== 'A' || notifiedA.has(key)) continue
    notifiedA.add(key)
    fresh.push(candidate)
  }

  state.notifiedA = [...notifiedA]
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    return []
  }
  return fresh
}

function candidateKey(candidate: PotentialLeaderCandidate) {
  return `${candidate.lane}:${candidate.symbol}`
}

function loadState(storage: Storage): NotificationState | null {
  try {
    const parsed = JSON.parse(storage.getItem(STORAGE_KEY) ?? 'null') as Partial<NotificationState> | null
    if (!parsed || typeof parsed.tradeDate !== 'string') return null
    const grades = parsed.grades && typeof parsed.grades === 'object' && !Array.isArray(parsed.grades)
      ? Object.fromEntries(Object.entries(parsed.grades).filter((entry): entry is [string, PotentialLeaderGrade] => (
        entry[1] === 'A' || entry[1] === 'B' || entry[1] === 'C'
      )))
      : {}
    const notifiedA = Array.isArray(parsed.notifiedA)
      ? parsed.notifiedA.filter((key): key is string => typeof key === 'string')
      : []
    return { tradeDate: parsed.tradeDate, grades, notifiedA }
  } catch {
    return null
  }
}

function browserStorage() {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}
