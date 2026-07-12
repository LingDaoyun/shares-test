import { create } from 'zustand'
import { createTradeCase, fetchTradeCase, fetchTradeCases } from '../api/client'
import type { CreateTradeCaseRequest, TradeCaseDetail, TradeCaseSummary } from '../types'

let loadPromise: Promise<void> | null = null

type TradeCase = TradeCaseSummary | TradeCaseDetail

interface TradeCaseIndex {
  casesById: Record<string, TradeCase>
  caseIdByRecommendation: Record<string, string>
}

function caseKey(caseSummary: Pick<TradeCaseSummary, 'symbol' | 'sourceModule' | 'ruleVersion' | 'recommendedAt'>) {
  return [caseSummary.symbol, caseSummary.sourceModule, caseSummary.ruleVersion, caseSummary.recommendedAt].join('|')
}

function isTradeCaseDetail(tradeCase: TradeCase): tradeCase is TradeCaseDetail {
  return 'fills' in tradeCase
}

function parseIsoTimestamp(updatedAt: string) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(updatedAt)) return null
  const timestamp = Date.parse(updatedAt)
  return Number.isFinite(timestamp) ? timestamp : null
}

function stableSerialize(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'undefined') return 'undefined'
  if (typeof value !== 'object') {
    const serialized = JSON.stringify(value)
    return serialized === undefined ? String(value) : serialized
  }
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(',')}]`

  const record = value as Record<string, unknown>
  return `{${Object.keys(record)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${stableSerialize(record[key])}`)
    .join(',')}}`
}

/**
 * Selects the one record that may represent a case. This is the sole version
 * and summary/detail precedence policy used by single-record and list merges.
 */
export function mergeCase(current: TradeCase | undefined, incoming: TradeCase): TradeCase {
  if (!current) return incoming

  const currentVersion = parseIsoTimestamp(current.updatedAt)
  const incomingVersion = parseIsoTimestamp(incoming.updatedAt)
  if (currentVersion !== null || incomingVersion !== null) {
    if (currentVersion === null) return incoming
    if (incomingVersion === null) return current
    if (incomingVersion > currentVersion) return incoming
    if (incomingVersion < currentVersion) return current
  }

  const currentDetail = isTradeCaseDetail(current)
  const incomingDetail = isTradeCaseDetail(incoming)
  if (currentDetail !== incomingDetail) return incomingDetail ? incoming : current

  // Equal versions of the same shape still need an arrival-order-independent winner.
  return stableSerialize(incoming) > stableSerialize(current) ? incoming : current
}

function buildRecommendationIndex(casesById: Record<string, TradeCase>) {
  const caseIdByRecommendation: Record<string, string> = {}
  for (const tradeCase of Object.values(casesById).sort((left, right) => (left.caseId < right.caseId ? -1 : left.caseId > right.caseId ? 1 : 0))) {
    caseIdByRecommendation[caseKey(tradeCase)] = tradeCase.caseId
  }
  return caseIdByRecommendation
}

function mergeCaseIntoState(state: TradeCaseIndex, incoming: TradeCase): TradeCaseIndex {
  const current = state.casesById[incoming.caseId]
  const winner = mergeCase(current, incoming)
  if (winner === current) return state

  const casesById = { ...state.casesById, [incoming.caseId]: winner }
  return {
    casesById,
    caseIdByRecommendation: buildRecommendationIndex(casesById)
  }
}

function mergeCasesIntoState(state: TradeCaseIndex, incomingCases: TradeCase[]) {
  const casesById = { ...state.casesById }
  let changed = false
  for (const incoming of incomingCases) {
    const winner = mergeCase(casesById[incoming.caseId], incoming)
    if (winner !== casesById[incoming.caseId]) {
      casesById[incoming.caseId] = winner
      changed = true
    }
  }
  if (!changed) return state
  return {
    casesById,
    caseIdByRecommendation: buildRecommendationIndex(casesById)
  }
}

interface TradeFeedbackState {
  casesById: Record<string, TradeCase>
  caseIdByRecommendation: Record<string, string>
  loaded: boolean
  loading: boolean
  loadCases: (force?: boolean) => Promise<void>
  refreshCases: () => Promise<void>
  getCaseId: (request: Pick<CreateTradeCaseRequest, 'symbol' | 'sourceModule' | 'ruleVersion' | 'recommendedAt'>) => string | undefined
  getCase: (caseId: string) => Promise<TradeCaseDetail>
  ensureCase: (request: CreateTradeCaseRequest) => Promise<TradeCaseDetail>
}

export const useTradeFeedbackStore = create<TradeFeedbackState>((set, get) => {
  const mergeCaseResponse = (tradeCase: TradeCase) => {
    set((state) => ({
      ...mergeCaseIntoState(state, tradeCase),
      loaded: true
    }))
  }

  return {
    casesById: {},
    caseIdByRecommendation: {},
    loaded: false,
    loading: false,
    loadCases: async (force = false) => {
      if (get().loaded && !force) return
      if (loadPromise) return loadPromise
      set({ loading: true })
      loadPromise = fetchTradeCases()
        .then((cases) => {
          set((state) => ({
            ...mergeCasesIntoState(state, cases),
            loaded: true
          }))
        })
        .finally(() => {
          set({ loading: false })
          loadPromise = null
        })
      return loadPromise
    },
    refreshCases: async () => get().loadCases(true),
    getCaseId: (request) => get().caseIdByRecommendation[caseKey(request)],
    getCase: async (caseId) => {
      const tradeCase = await fetchTradeCase(caseId)
      mergeCaseResponse(tradeCase)
      return tradeCase
    },
    ensureCase: async (request) => {
      await get().loadCases()
      const existingCaseId = get().getCaseId(request)
      if (existingCaseId) {
        const existingCase = get().casesById[existingCaseId]
        if (existingCase && 'fills' in existingCase) return existingCase
        return get().getCase(existingCaseId)
      }
      const tradeCase = await createTradeCase(request)
      mergeCaseResponse(tradeCase)
      return tradeCase
    }
  }
})
