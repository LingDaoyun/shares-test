import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createTradeCase, fetchTradeCases } from '../api/client'
import type { TradeCaseDetail, TradeCaseSummary } from '../types'
import { useTradeFeedbackStore } from './tradeFeedbackStore'

vi.mock('../api/client', () => ({
  createTradeCase: vi.fn(),
  fetchTradeCase: vi.fn(),
  fetchTradeCases: vi.fn()
}))

const mockedCreateTradeCase = vi.mocked(createTradeCase)
const mockedFetchTradeCases = vi.mocked(fetchTradeCases)

describe('tradeFeedbackStore pagination', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useTradeFeedbackStore.setState({
      casesById: {},
      caseIdByRecommendation: {},
      loaded: false,
      loading: false,
      loadingMore: false,
      hasMore: true,
      nextCursor: null
    })
  })

  it('does not treat a captured entity as an initialized first page', async () => {
    mockedCreateTradeCase.mockResolvedValue(detail('captured-old', '2020-01-01T00:00:00Z'))
    mockedFetchTradeCases.mockResolvedValue([summary('server-new', '2026-07-12T00:00:00Z')])

    await useTradeFeedbackStore.getState().ensureCase({ attestationToken: 'issued-token' })

    expect(useTradeFeedbackStore.getState().loaded).toBe(false)
    await useTradeFeedbackStore.getState().loadCases()
    expect(mockedFetchTradeCases).toHaveBeenCalledTimes(1)
  })

  it('continues from the last server page instead of the oldest cached entity', async () => {
    mockedCreateTradeCase.mockResolvedValue(detail('captured-old', '2020-01-01T00:00:00Z'))
    const firstPage = Array.from({ length: 50 }, (_, index) => summary(
      `page-${String(index).padStart(2, '0')}`,
      new Date(Date.UTC(2026, 6, 12, 0, 0, 50 - index)).toISOString()
    ))
    mockedFetchTradeCases.mockResolvedValueOnce(firstPage).mockResolvedValueOnce([])

    await useTradeFeedbackStore.getState().ensureCase({ attestationToken: 'issued-token' })
    await useTradeFeedbackStore.getState().loadCases()
    await useTradeFeedbackStore.getState().loadMoreCases()

    expect(mockedFetchTradeCases).toHaveBeenNthCalledWith(2, {
      beforeCreatedAt: firstPage[49].createdAt,
      beforeCaseId: firstPage[49].caseId,
      limit: 50
    })
  })
})

function summary(caseId: string, createdAt: string): TradeCaseSummary {
  return {
    caseId,
    symbol: caseId === 'captured-old' ? '002714' : '600519',
    companyName: caseId,
    sourceModule: 'MISPRICING',
    recommendationAction: '观察',
    recommendationScore: 70,
    ruleVersion: 'test-v1',
    recommendedPrice: 10,
    recommendedAt: createdAt,
    recommendationVerified: true,
    status: 'PLANNED',
    ledger: {
      latestPrice: null,
      positionQuantity: 0,
      averageCost: 0,
      realizedProfit: 0,
      unrealizedProfit: null,
      totalProfit: null
    },
    outcomes: [],
    createdAt,
    updatedAt: createdAt
  }
}

function detail(caseId: string, createdAt: string): TradeCaseDetail {
  return {
    ...summary(caseId, createdAt),
    decisionId: null,
    recommendationPayload: {},
    fills: [],
    outcomeWarnings: []
  }
}
