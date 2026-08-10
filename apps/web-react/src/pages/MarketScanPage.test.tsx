// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchLongTermCandidateContext, fetchMarketScanReport } from '../api/client'
import { MarketScanPage } from './MarketScanPage'

vi.mock('../api/client', () => ({
  fetchMarketScanReport: vi.fn(),
  fetchLongTermCandidateContext: vi.fn()
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
const mockedFetchLongTermCandidateContext = vi.mocked(fetchLongTermCandidateContext)

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
    mockedFetchLongTermCandidateContext.mockResolvedValue(candidateContext as never)
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

  it('shows a confirmed buy entry action in the long-term candidate detail', async () => {
    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const candidateButton = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('航民股份'))

    await act(async () => {
      candidateButton?.click()
      await Promise.resolve()
    })

    expect(document.querySelector('button[aria-label="买入 航民股份 600987"]')).not.toBeNull()
  })

  it('defaults long-term value scanning to twelve quality undervaluation candidates', async () => {
    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedFetchMarketScanReport).toHaveBeenCalledWith(expect.objectContaining({
      limit: 12,
      allowChiNext: false
    }))
    expect(document.body.textContent).toContain('默认输出十二只候选')
    expect(document.body.textContent).toContain('低估且基本面较好的股票')
    expect(document.body.textContent).not.toContain('排除样本')
  })

  it('lets long-term scanning opt in to ChiNext stocks when the account has permission', async () => {
    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const switchLabel = [...host.querySelectorAll<HTMLLabelElement>('label')]
      .find((label) => label.textContent?.includes('允许创业板'))
    const switchInput = switchLabel?.querySelector<HTMLInputElement>('input')
    expect(switchInput).toBeTruthy()
    expect(switchInput?.checked).toBe(false)

    await act(async () => {
      switchInput?.click()
      await Promise.resolve()
    })
    const applyButton = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('应用阈值'))
    await act(async () => {
      applyButton?.click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedFetchMarketScanReport).toHaveBeenLastCalledWith(expect.objectContaining({
      allowChiNext: true
    }))
  })

  it('loads industry policy and cycle context when a candidate is opened', async () => {
    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const candidateButton = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('航民股份'))

    await act(async () => {
      candidateButton?.click()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedFetchLongTermCandidateContext).toHaveBeenCalledWith('600987', '纺织制造')
    const text = document.body.textContent ?? ''
    expect(text).toContain('行业环境')
    expect(text).toContain('纺织制造')
    expect(text).toContain('最近政策')
    expect(text).toContain('纺织行业绿色低碳转型实施意见')
    expect(text).toContain('当前周期')
    expect(text).toContain('经营早期修复')
    expect(text).toContain('价格修复')
  })

  it('ignores a late context response after switching candidates', async () => {
    const secondCandidate = {
      ...report.candidates[0],
      rank: 2,
      symbol: '600588',
      name: '用友网络',
      industry: '软件开发'
    }
    mockedFetchMarketScanReport.mockResolvedValue({
      ...report,
      candidateCount: 2,
      candidates: [report.candidates[0], secondCandidate]
    } as never)
    let resolveFirst: (value: unknown) => void = () => undefined
    let resolveSecond: (value: unknown) => void = () => undefined
    mockedFetchLongTermCandidateContext
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }) as never)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve }) as never)

    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const findCandidate = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes(name))

    await act(async () => {
      findCandidate('航民股份')?.click()
      await Promise.resolve()
    })
    await act(async () => {
      findCandidate('用友网络')?.click()
      await Promise.resolve()
    })
    await act(async () => {
      resolveSecond({
        ...candidateContext,
        symbol: '600588',
        companyName: '用友网络',
        industry: '软件开发',
        industryContext: {
          ...candidateContext.industryContext,
          industry: '软件开发',
          modelCode: 'GROWTH',
          modelLabel: '成长企业模型',
          cycleType: 'GROWTH',
          cycleTypeLabel: '成长行业'
        },
        policyEvidence: { documents: [], dataGaps: ['最近两年未匹配到可靠官方政策文件'] }
      })
      await Promise.resolve()
    })
    await act(async () => {
      resolveFirst(candidateContext)
      await Promise.resolve()
    })

    expect(document.body.textContent).toContain('软件开发')
    expect(document.body.textContent).not.toContain('纺织行业绿色低碳转型实施意见')
  })

  it('does not flash a previously loaded context after switching candidates', async () => {
    const secondCandidate = {
      ...report.candidates[0],
      rank: 2,
      symbol: '600588',
      name: '用友网络',
      industry: '软件开发'
    }
    mockedFetchMarketScanReport.mockResolvedValue({
      ...report,
      candidateCount: 2,
      candidates: [report.candidates[0], secondCandidate]
    } as never)
    let resolveSecond: (value: unknown) => void = () => undefined
    mockedFetchLongTermCandidateContext
      .mockResolvedValueOnce(candidateContext as never)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve }) as never)

    await act(async () => {
      root.render(<MarketScanPage />)
      await Promise.resolve()
      await Promise.resolve()
    })
    const findCandidate = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes(name))

    await act(async () => {
      findCandidate('航民股份')?.click()
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(document.body.textContent).toContain('纺织行业绿色低碳转型实施意见')

    await act(async () => {
      findCandidate('用友网络')?.click()
      await Promise.resolve()
    })
    expect(document.body.textContent).not.toContain('纺织行业绿色低碳转型实施意见')
    expect(document.body.textContent).toContain('正在核对行业、政策与周期证据')

    await act(async () => {
      resolveSecond({
        ...candidateContext,
        symbol: '600588',
        companyName: '用友网络',
        industry: '软件开发'
      })
      await Promise.resolve()
    })
  })
})

const candidateContext = {
  symbol: '600987',
  companyName: '航民股份',
  market: '沪A',
  industry: '纺织制造',
  industryContext: {
    industry: '纺织制造',
    modelCode: 'STANDARD',
    modelLabel: '普通企业模型',
    cycleType: 'STANDARD',
    cycleTypeLabel: '一般行业',
    evidence: ['依据东方财富行业分类匹配长期估值与周期模板'],
    dataGaps: []
  },
  policyEvidence: {
    documents: [{
      title: '纺织行业绿色低碳转型实施意见',
      source: '工业和信息化部',
      publishedAt: '2026-06-18',
      url: 'https://www.miit.gov.cn/zwgk/zcwj/a.html',
      impact: 'SUPPORT',
      relevanceScore: 84,
      matchedKeywords: ['纺织', '绿色制造'],
      rationale: '标题命中行业关键词'
    }],
    dataGaps: []
  },
  cycleContext: {
    businessStage: 'EARLY_RECOVERY',
    businessStageLabel: '经营早期修复',
    priceStage: 'RECOVERY',
    priceStageLabel: '价格修复',
    confidence: 68,
    provisional: true,
    supportingEvidence: ['营收增速改善'],
    contraryEvidence: ['原材料成本仍有压力'],
    dataGaps: ['缺少行业库存月度数据']
  },
  generatedAt: '2026-07-30T04:00:00Z',
  dataGaps: ['缺少行业库存月度数据']
}

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
    allowChiNext: false,
    mode: 'VALUE'
  },
  stageStats: [],
  exclusionsSample: [],
  generatedAt: '2026-07-29T07:01:00Z',
  tradeCaptureTokens: {
    '600987': 'token-long-term-600987'
  },
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
