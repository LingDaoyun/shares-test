import type { ShortTermCatchUpFollowUpObservation, ShortTermCatchUpSnapshot } from '../types/shortTermCatchUp'

export const CATCH_UP_STORAGE_KEY = 'ai-stock.short-term.catch-up.v1'
export const CATCH_UP_STORAGE_EVENT = 'ai-stock:catch-up-saved'
export const CATCH_UP_OBSERVATION_LIMIT = 100
export const CATCH_UP_FOLLOW_UP_LIMIT = 50
const SNAPSHOT_LIMIT = 60

type LocalStorageAccess = Pick<Storage, 'getItem' | 'setItem'>

export interface CatchUpFirstObservation extends ShortTermCatchUpFollowUpObservation {
  industry: string
  name: string
  leaderName: string
  boardCode: string
  boardName: string
  observedAt: string
  marketTimestamp: string
  nextTradingDate: string | null
  return5: number | null
  returnPercentile: number | null
  quoteSource: string | null
  evidence: string[]
  contraryEvidence: string[]
  dataGaps: string[]
}

export interface CatchUpLocalSnapshot {
  key: string
  marketTimestamp: string
  recordedAt: string
  risingCount: number | null
  sealedCount: number | null
}

export interface CatchUpLocalState {
  observations: CatchUpFirstObservation[]
  snapshots: CatchUpLocalSnapshot[]
}

export function catchUpSnapshotKey(tradeDate: string, leaderSymbol: string, boardCode: string) {
  return [tradeDate, leaderSymbol, boardCode].join(':')
}

export function emptyCatchUpLocalState(): CatchUpLocalState {
  return { observations: [], snapshots: [] }
}

function browserStorage(): LocalStorageAccess | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

function stringList(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === 'string')
}

function nullableNumber(value: unknown) {
  return value === null || (typeof value === 'number' && Number.isFinite(value))
}

function isObservation(value: unknown): value is CatchUpFirstObservation {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  return ['id', 'industry', 'tradeDate', 'symbol', 'name', 'leaderSymbol', 'leaderName', 'boardCode',
    'boardName', 'observedAt', 'marketTimestamp'].every(key => typeof row[key] === 'string')
    && Number.isFinite(Date.parse(row.observedAt as string))
    && Number.isFinite(Date.parse(row.marketTimestamp as string))
    && typeof row.observedPrice === 'number' && Number.isFinite(row.observedPrice) && row.observedPrice > 0
    && (row.nextTradingDate === null || typeof row.nextTradingDate === 'string')
    && (row.quoteSource === null || typeof row.quoteSource === 'string')
    && nullableNumber(row.return5) && nullableNumber(row.returnPercentile)
    && stringList(row.evidence) && stringList(row.contraryEvidence) && stringList(row.dataGaps)
    && row.id === `${catchUpSnapshotKey(row.tradeDate as string, row.leaderSymbol as string, row.boardCode as string)}:${row.symbol}`
}

function isSnapshot(value: unknown): value is CatchUpLocalSnapshot {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  return typeof row.key === 'string' && typeof row.marketTimestamp === 'string'
    && Number.isFinite(Date.parse(row.marketTimestamp)) && typeof row.recordedAt === 'string'
    && nullableNumber(row.risingCount) && nullableNumber(row.sealedCount)
}

export function loadCatchUpLocalState(storage = browserStorage()): CatchUpLocalState {
  if (!storage) return emptyCatchUpLocalState()
  try {
    const raw = storage.getItem(CATCH_UP_STORAGE_KEY)
    if (!raw) return emptyCatchUpLocalState()
    const parsed = JSON.parse(raw) as { version?: number; observations?: unknown; snapshots?: unknown }
    if (parsed.version !== 1 || !Array.isArray(parsed.observations) || !Array.isArray(parsed.snapshots)) {
      return emptyCatchUpLocalState()
    }
    const seen = new Set<string>()
    const observations = parsed.observations.filter(isObservation).filter(row => {
      if (seen.has(row.id)) return false
      seen.add(row.id)
      return true
    }).slice(-CATCH_UP_OBSERVATION_LIMIT)
    return { observations, snapshots: parsed.snapshots.filter(isSnapshot).slice(-SNAPSHOT_LIMIT) }
  } catch {
    return emptyCatchUpLocalState()
  }
}

export function saveCatchUpLocalState(state: CatchUpLocalState, storage = browserStorage()) {
  if (!storage) return false
  try {
    storage.setItem(CATCH_UP_STORAGE_KEY, JSON.stringify({ version: 1, ...state }))
    if (typeof window !== 'undefined') window.dispatchEvent(new Event(CATCH_UP_STORAGE_EVENT))
    return true
  } catch {
    return false
  }
}

/** A retained first observation is immutable; refreshes only append new identities/source times. */
export function rememberCatchUpSnapshot(
  state: CatchUpLocalState,
  snapshot: ShortTermCatchUpSnapshot,
  observedAt: string
): CatchUpLocalState {
  if ((snapshot.status !== 'READY' && snapshot.status !== 'DATA_GAP') || !snapshot.boardCode || !snapshot.leader || !snapshot.marketTimestamp
    || !Number.isFinite(Date.parse(snapshot.marketTimestamp))) return state
  const key = catchUpSnapshotKey(snapshot.tradeDate, snapshot.leaderSymbol, snapshot.boardCode)
  const known = new Set(state.observations.map(row => row.id))
  const additions: CatchUpFirstObservation[] = []
  for (const row of snapshot.candidates) {
    const id = `${key}:${row.symbol}`
    if (known.has(id) || !Number.isFinite(row.latestPrice) || row.latestPrice <= 0
      || !Number.isFinite(Date.parse(row.marketTimestamp))) continue
    known.add(id)
    additions.push({
      id, industry: snapshot.industry, tradeDate: snapshot.tradeDate, symbol: row.symbol, name: row.name,
      leaderSymbol: snapshot.leaderSymbol, leaderName: snapshot.leader.name,
      boardCode: snapshot.boardCode, boardName: snapshot.boardName ?? snapshot.boardCode,
      observedPrice: row.latestPrice, observedAt, marketTimestamp: row.marketTimestamp,
      nextTradingDate: snapshot.nextTradingDate, return5: row.return5, returnPercentile: row.returnPercentile,
      quoteSource: snapshot.quoteSource, evidence: [...snapshot.evidence, ...row.evidence],
      contraryEvidence: [...snapshot.contraryEvidence, ...row.contraryEvidence],
      dataGaps: [...snapshot.dataGaps, ...row.dataGaps]
    })
  }
  const samples = state.snapshots.filter(row => row.key === key)
    .sort((a, b) => Date.parse(a.marketTimestamp) - Date.parse(b.marketTimestamp))
  const latest = samples[samples.length - 1]
  const advanced = !latest || Date.parse(snapshot.marketTimestamp) > Date.parse(latest.marketTimestamp)
  let snapshots = state.snapshots
  if (advanced) {
    const current: CatchUpLocalSnapshot = { key, marketTimestamp: snapshot.marketTimestamp, recordedAt: observedAt,
      risingCount: snapshot.risingCount, sealedCount: snapshot.sealedCount }
    snapshots = [...state.snapshots.filter(row => row.key !== key), ...(latest ? [latest] : []), current]
      .slice(-SNAPSHOT_LIMIT)
  }
  if (!additions.length && !advanced) return state
  return { observations: [...state.observations, ...additions].slice(-CATCH_UP_OBSERVATION_LIMIT), snapshots }
}

export function catchUpSnapshotPair(state: CatchUpLocalState, tradeDate: string, leaderSymbol: string, boardCode: string) {
  return state.snapshots.filter(row => row.key === catchUpSnapshotKey(tradeDate, leaderSymbol, boardCode))
    .sort((a, b) => Date.parse(a.marketTimestamp) - Date.parse(b.marketTimestamp)).slice(-2)
}
