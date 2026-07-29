// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketScanReport } from '../api/client'
import { MarketScanPage } from './MarketScanPage'

vi.mock('../api/client', () => ({
  fetchMarketScanReport: vi.fn()
}))

vi.mock('../components/watchlist/WatchButton', () => ({
  WatchButton: () => <button type="button">特别关注</button>
}))

vi.mock('../components/recommendation/V2StrategyBundlePanel', () => ({
  V2StrategyBundlePanel: ({ factorContext }: { factorContext: { moatScore: number; industryLeaderScore: number } }) => (
    <div>V2策略 护城河 {factorContext.moatScore} 行业地位 {factorContext.industryLeaderScore}</div>
  )
}))

vi.mock('../components/recommendation/EvidenceBundlePanel', () => ({
  RecommendationEvidenceBundlePanel: () => <div>证据复核</div>
}))

const mockedFetchMarketScanReport = vi.mocked(fetchMarketScanReport)

describe('MarketScanPage long-term assessment', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    mockedFetchMarketScanReport.mockResolvedValue(report as never)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    document.body.querySelectorAll('[role="dialog"]').forEach((element) => element.parentElement?.remove())
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  it('shows model, implied expectation, valuation range and non-mechanical add discipline', async () => {
    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const candidateButton = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('航民股份'))
    expect(candidateButton).toBeTruthy()

    await act(async () => {
      candidateButton?.click()
      await Promise.resolve()
    })

    const text = document.body.textContent ?? ''
    expect(text).toContain('行业估值模板')
    expect(text).toContain('普通企业经营者收益代理')
    expect(text).toContain('市场隐含增长')
    expect(text).toContain('经营者收益代理')
    expect(text).toContain('悲观 / 基准 / 乐观')
    expect(text).toContain('下跌15%只触发强制复核，不自动加仓')
    expect(text).toContain('规则尚未自动触发')
    expect(text).toContain('数据缺失')
    expect(text).not.toContain('金融行业不适用')
    expect(text).toContain('第六项关键缺口')
    expect(text).toContain('V2策略 护城河 88 行业地位 88')
    expect(text).toContain('季度轻审计')
  })
})

const report = {
  scope: '沪深北 A 股全市场扫描',
  universeCount: 5884,
  reviewedCount: 3,
  candidateCount: 1,
  quoteNote: '全市场行情覆盖完整',
  coverage: {
    requestedCount: 6000,
    expectedCount: 5884,
    fetchedCount: 5884,
    missingCount: 0,
    complete: true,
    source: '东方财富行情',
    fetchedAt: '2026-07-29T07:00:00Z'
  },
  methodology: ['长期质量与估值纪律'],
  ruleSet: {
    scanLimit: 6000,
    minAmount: 80000000,
    maxPe: 35,
    maxPb: 4.5,
    maxRiseForEntry: 4,
    maxSinglePositionPercent: 10,
    minFinancialScore: 45,
    excludeSideways: false,
    includeNorthExchange: true,
    mode: 'VALUE'
  },
  stageStats: [],
  exclusionsSample: [],
  generatedAt: '2026-07-29T07:01:00Z',
  candidates: [{
    rank: 1,
    symbol: '600987',
    name: '航民股份',
    market: '沪市',
    industry: '纺织制造',
    latestPrice: 7.2,
    marketTimestamp: '2026-07-29T07:00:00Z',
    changePercent: 0.2,
    peTtm: 11.8,
    pbRatio: 1.02,
    amount: 190000000,
    valuationContext: {
      score: 78,
      state: 'CHEAP',
      applicableModel: 'STANDARD',
      rawPe: 11.8,
      rawPb: 1.02,
      peReference: 35,
      pbReference: 4.5,
      industryPercentile: null,
      historyPercentile: null,
      normalizedEarningsUsed: false,
      warnings: [],
      evidence: []
    },
    longTermAssessment: {
      strategyVersion: 'long-term-value-discipline-v1',
      modelCode: 'STANDARD',
      modelLabel: '普通企业经营者收益代理',
      status: 'BUILD_ZONE_REVIEW',
      statusLabel: '进入建仓复核',
      factorScores: {
        financialQualityScore: 82,
        moatAndIndustryScore: 88,
        valuationExpectationScore: 76,
        capitalAllocationScore: 79,
        evidenceRiskScore: 75,
        overallScore: 81
      },
      financialQuality: {
        sampleYears: 5,
        medianRoe: 0.13,
        roeReference: 0.12,
        roeReferenceMetYears: 4,
        positiveCashFlowYears: 5,
        cumulativeCashToProfitRatio: null,
        grossMarginRange: 0.04,
        status: 'DURABLE',
        statusLabel: '多年质量较稳',
        evidence: ['经营现金流为正年份 5/5。'],
        dataGaps: []
      },
      valuation: {
        metricCode: 'IMPLIED_GROWTH',
        metricLabel: '市场隐含增长',
        impliedExpectationPercent: 2.8,
        evidenceExpectationPercent: 5.2,
        pessimisticValue: 8.1,
        baseValue: 10.4,
        optimisticValue: 13.2,
        discountToBasePercent: 30.77,
        targetMarginOfSafetyPercent: 25,
        entryReferencePrice: 7.8,
        normalizedEarningsUsed: false,
        confidence: 'MEDIUM',
        confidenceLabel: '代理模型可用',
        evidence: ['五年中位数经营者收益代理 0.68 元/股。'],
        dataGaps: ['缺少资本开支，当前不是严格自由现金流。']
      },
      positionDiscipline: {
        maxSinglePositionPercent: 10,
        maxTopFivePositionPercent: 50,
        trancheCount: 3,
        declineReviewTriggerPercent: 15,
        entryConditions: ['当前价格进入模型安全边际研究区间'],
        addConditions: ['原投资逻辑未被证伪'],
        reviewTriggers: ['买入后股价下跌15%只触发强制复核，不自动加仓']
      },
      logicAudit: {
        quarterlyReview: '季度轻审计：盈利、现金流、毛利率、负债压力和行业数据。',
        annualReview: '年度深审计：护城河、资本配置、竞争格局和估值正常化。',
        eventTriggers: ['业绩预告显著偏离原假设'],
        invalidationConditions: ['核心壁垒不可逆恶化'],
        reentryRule: '逻辑重新成立且安全边际恢复后才可重新进入。'
      },
      evidence: ['行业地位靠前'],
      risks: ['价值区间对参数敏感'],
      dataGaps: [
        '缺少资本开支',
        '缺少完整资产负债率',
        '缺少护城河证据',
        '缺少市场份额证据',
        '缺少管理层资本配置证据',
        '第六项关键缺口'
      ]
    },
    score: {
      valuationScore: 76,
      liquidityScore: 70,
      priceActionScore: 60,
      qualityProxyScore: 82,
      riskScore: 75,
      finalScore: 81
    },
    screeningAction: 'WATCH_BUY_ZONE',
    screeningActionLabel: '建仓复核',
    reason: '五维长期评估进入建仓研究区。',
    todayAdvice: {
      action: 'WAIT',
      actionLabel: '等确认',
      confidence: 76,
      summary: '仍需公告反证。',
      reasons: ['长期评估通过'],
      riskControls: ['不机械补仓']
    },
    tags: ['价值观察'],
    strengths: ['盈利质量较稳'],
    risks: ['价值区间对参数敏感'],
    dataGaps: ['缺少资本开支'],
    evidenceCompleteness: {
      score: 75,
      status: 'PARTIAL',
      statusLabel: '证据待补',
      allowsBuy: false,
      presentEvidence: ['实时行情'],
      missingEvidence: ['公告反证'],
      riskControls: ['补齐公告反证']
    },
    evidenceBundle: {
      symbol: '600987',
      generatedAt: '2026-07-29T07:01:00Z',
      peerValuation: { available: false, statusLabel: '待补', peers: [], dataGaps: [] },
      agentConsensus: { available: false, statusLabel: '待补', arguments: [], objections: [], dataGaps: [] },
      dataGaps: []
    },
    trace: []
  }]
}
