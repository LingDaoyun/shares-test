import { create } from 'zustand'
import { createTradeCase, fetchTradeCase, fetchTradeCases } from '../api/client'
import type { CreateTradeCaseRequest, TradeCaseDetail, TradeCaseSummary } from '../types'

let loadPromise: Promise<void> | null = null

function caseKey(caseSummary: Pick<TradeCaseSummary, 'symbol' | 'sourceModule' | 'ruleVersion' | 'recommendedAt'>) {
  return [caseSummary.symbol, caseSummary.sourceModule, caseSummary.ruleVersion, caseSummary.recommendedAt].join('|')
}

interface TradeFeedbackState {
  casesById: Record<string, TradeCaseSummary | TradeCaseDetail>
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
  const mergeCase = (tradeCase: TradeCaseSummary | TradeCaseDetail) => {
    set((state) => ({
      casesById: { ...state.casesById, [tradeCase.caseId]: tradeCase },
      caseIdByRecommendation: {
        ...state.caseIdByRecommendation,
        [caseKey(tradeCase)]: tradeCase.caseId
      },
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
            casesById: cases.reduce<Record<string, TradeCaseSummary | TradeCaseDetail>>(
              (byId, tradeCase) => ({ ...byId, [tradeCase.caseId]: tradeCase }),
              state.casesById
            ),
            caseIdByRecommendation: cases.reduce<Record<string, string>>(
              (byRecommendation, tradeCase) => ({ ...byRecommendation, [caseKey(tradeCase)]: tradeCase.caseId }),
              state.caseIdByRecommendation
            ),
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
      mergeCase(tradeCase)
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
      mergeCase(tradeCase)
      return tradeCase
    }
  }
})
