// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchOvernightBacktest,
  fetchShortTermIndustryLeaders,
  fetchShortTermMarketBreadth,
  fetchShortTermScanJob,
  fetchShortTermValidationSummaries,
  startShortTermScanJob
} from '../api/client'
import { toast } from '../components/ui/Toast'
import { CATCH_UP_STORAGE_KEY } from '../lib/shortTermCatchUpStorage'
import { SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY } from '../lib/shortTermViewPreferences'
import { resetShortTermScanStoreForTest, useShortTermScanStore } from '../store/shortTermScanStore'
import { useTradeFeedbackStore } from '../store/tradeFeedbackStore'
import type { ShortTermReport } from '../types'
import { fetchDailyShortTermSnapshot, fetchShortTermResultCache, type ShortTermDailySnapshot, type UpperShadowScan } from '../api/dailyShortTerm'
import { ShortTermPage } from './ShortTermPage'

vi.mock('../api/client', () => ({
  fetchOvernightBacktest: vi.fn(),
  fetchShortTermIndustryLeaders: vi.fn(),
  fetchShortTermCatchUp: vi.fn(),
  fetchShortTermCatchUpFollowUp: vi.fn(),
  fetchShortTermLimitUpBoard: vi.fn().mockResolvedValue(null),
  fetchShortTermMarketBreadth: vi.fn().mockResolvedValue(null),
  fetchShortTermScanJob: vi.fn(),
  fetchShortTermValidationSummaries: vi.fn(),
  startShortTermScanJob: vi.fn(),
  fetchV2StrategyBundle: vi.fn().mockRejectedValue(new Error('测试中不加载 V2 策略束'))
}))

vi.mock('../api/dailyShortTerm', () => ({ fetchDailyShortTermSnapshot: vi.fn(), fetchShortTermResultCache: vi.fn(), retryDailyShortTermSnapshot: vi.fn() }))

vi.mock('../components/ui/Toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    loading: vi.fn(),
    dismiss: vi.fn()
  }
}))

const manualSuccessToastOptions = {
  key: 'short-term-manual-scan',
  durationMs: 5000
}

const emptyReport = {
  scope: '短线右侧',
  universeCount: 5500,
  reviewedCount: 120,
  klineReviewedCount: 120,
  candidateCount: 0,
  quoteNote: '全市场覆盖可靠',
  tradingSession: {
    phase: 'AFTERNOON_CONTINUOUS',
    phaseLabel: '下午连续竞价',
    regularAuctionOpen: true,
    closingDecisionWindow: true,
    postCloseFixedPrice: false,
    decisionTimeLabel: '14:45-14:49',
    rules: [],
    warnings: [],
    status: 'OPEN',
    sourceName: 'SINA',
    marketTimestamp: '2026-07-23T14:49:20+08:00',
    calculatedAt: '2026-07-23T14:49:30+08:00'
  },
  methodology: ['只使用当天行情'],
  ruleSet: {
    limit: 8,
    scanLimit: 6000,
    klineLimit: 120,
    minAmount: 80000000,
    maxPricePerShare: 100,
    minVolumeRatio: 1.2,
    maxEntryRisePercent: 6.5,
    maxDistanceToMa20Percent: 8,
    minFinancialScore: 58,
    allowChiNext: false
  },
  weightProfile: {
    preliminaryValuation: 0.2,
    preliminaryLiquidity: 0.3,
    preliminaryNonChase: 0.3,
    preliminaryHeat: 0.2,
    finalGoldenCross: 0.45,
    finalVolume: 0.3,
    finalTurnover: 0.15,
    finalCloseStrength: 0.1
  },
  candidates: [],
  hotDirections: [],
  marketSentiment: {
    phase: '平稳',
    score: 60,
    advancing: 2800,
    declining: 2400,
    limitUpLike: 20,
    limitDownLike: 5,
    breadthPercent: 53.8,
    explanation: '市场宽度正常'
  },
  exclusions: [],
  tradeCaptureTokens: {},
  coverage: {
    expectedCount: 5500,
    fetchedCount: 5450,
    missingCount: 50,
    coverageRatio: 0.9909,
    executionReliable: true,
    source: 'SINA',
    fetchedAt: '2026-07-23T14:49:20+08:00',
    rawExpectedCount: 5895,
    rawFetchedCount: 5895,
    excludedNoPriceCount: 395,
    rawComplete: true
  },
  reviewedSymbols: [],
  dataCutoffAt: '2026-07-23T14:49:20+08:00',
  generatedAt: '2026-07-23T14:49:30+08:00',
  marketRegime: {
    state: 'RISK_ON',
    label: '风险偏好回升',
    breadthPercent: 58.4,
    medianChangePercent: 0.62,
    averageAbsoluteChangePercent: 1.85,
    advancingTurnoverSharePercent: 61.2,
    limitUpRatioPercent: 1.1,
    limitDownRatioPercent: 0.08,
    sampleCount: 5388,
    maxAction: 'LIGHT_TRIAL',
    explanation: '市场宽度和成交额结构允许轻仓试错。',
    dataGaps: []
  }
} as ShortTermReport

const candidate = (symbol: string, name = `候选${symbol}`) => ({
  rank: 1,
  symbol,
  name,
  market: '沪市',
  industry: '电力',
  latestPrice: 5.21,
  changePercent: 1.2,
  peTtm: 18,
  pbRatio: 1.6,
  amount: 900_000_000,
  valuationContext: {
    score: 70,
    state: 'FAIR',
    applicableModel: 'STANDARD',
    rawPe: 18,
    rawPb: 1.6,
    peReference: 100,
    pbReference: 15,
    warnings: [],
    evidence: []
  },
  phase: 'RIGHT_EARLY',
  phaseLabel: '右侧早期',
  action: 'WATCH_RIGHT_SIDE',
  actionLabel: '观察',
  reason: '量价结构确认',
  todayAdvice: {
    action: 'NEXT_WATCH',
    actionLabel: '次日关注',
    confidence: 80,
    summary: '次日继续观察',
    reasons: [],
    riskControls: []
  },
  tailSignal: {
    status: 'CONFIRMED',
    statusLabel: '尾盘确认',
    score: 75,
    latestMinute: '14:50',
    reasons: [],
    riskControls: []
  },
  score: {
    technicalScore: 80,
    goldenCrossScore: 100,
    volumeScore: 75,
    turnoverScore: 100,
    closeStrengthScore: 95,
    marketHeatScore: 70,
    valuationScore: 60,
    financialScore: 65,
    riskPenalty: 5,
    finalScore: 74,
    stageAdjustment: 6,
    fundFlowAdjustment: 1.2,
    marketHeatContribution: 1,
    relativeStrengthContribution: 2.4,
    industryLeadershipContribution: -0.4,
    crossSectionAdjustment: 3,
    technicalRankingScore: 80,
    volatilityContribution: 0.8,
    visibleRankingAdjustment: 5,
    rankingScore: 85
  },
  technical: {
    goldenCross: null,
    rightSideSignal: 'EARLY_CONFIRMED',
    ma20SlopePercent: 0.8,
    distanceToMa20Percent: 2.1,
    breakoutFromPreviousHigh20Percent: 1.3,
    volumeRatio20: 1.5,
    todayVolume: 1_284_000,
    averageVolume3: 962_000,
    volumeRatio3: 1.3347,
    momentumQuality: {
      turnoverRatePercent: 3,
      turnoverBand: 'PREFERRED',
      turnoverScore: 100,
      latestUpperShadowPercent: 12,
      bullishUpperShadowMedian3Percent: 18,
      closeLocationPercent: 88,
      closeStrengthLabel: '上攻收盘强',
      closeStrengthScore: 95,
      provisional: true,
      extremeUpperShadow: false,
      dataGaps: []
    }
  },
  financial: {
    qualityScore: 65,
    statusLabel: '财报无红旗',
    positiveCashFlowYears: 3,
    roe: 0.12,
    grossMargin: 0.28,
    dataGaps: []
  },
  quoteFreshness: {
    blocksRealtimeDecision: false,
    statusLabel: '新鲜',
    marketTimestamp: '2026-07-23T14:50:00+08:00'
  },
  buyZoneLow: 5.1,
  buyZoneHigh: 5.3,
  stopPrice: 4.98,
  strengths: [],
  risks: [],
  entryRules: [],
  exitRules: [],
  evidenceCompleteness: {
    score: 90,
    status: 'COMPLETE',
    statusLabel: '证据完整',
    allowsBuy: true,
    presentEvidence: [],
    missingEvidence: [],
    riskControls: []
  },
  evidence: [],
  tradePlan: null,
  relativeStrength: {
    return5: 3.2,
    return10: 6.8,
    return20: 12.5,
    marketPercentile5: 72,
    marketPercentile10: 80,
    marketPercentile20: 88,
    industryPercentile5: 66,
    industryPercentile10: 75,
    industryPercentile20: 84,
    marketSampleCount: 120,
    industrySampleCount: 12,
    compositeScore: 82,
    contribution: 2.4,
    dataGaps: []
  },
  industryLeadership: {
    industry: '电力',
    cohortSize: 20,
    amountRank: 7,
    percentile: 68,
    contribution: -0.4,
    evidence: '当前成交额排名 7/20'
  },
  volatilityQuality: {
    atrPercent: 2.36,
    distanceToMa20Atr: 0.82,
    contractionRatio5To20: 0.76,
    breakoutExpansionRatio: 1.28,
    breakoutFromHigh20Atr: 0.41,
    state: 'CONTRACTION_BREAKOUT',
    label: '缩量整理后扩张',
    contractionBreakout: true,
    contribution: 0.8,
    dataGaps: []
  },
  signalProfile: {
    primaryFamily: 'GOLDEN_CROSS_BREAKOUT',
    primaryLabel: '金叉放量突破',
    activeFamilies: ['GOLDEN_CROSS_BREAKOUT'],
    evidence: ['MA5 上穿 MA10，量比 1.5'],
    dataGaps: []
  }
})

function reportWithCandidates(
  symbols: string[],
  generatedAt = '2026-07-23T14:49:30+08:00',
  technicalRules: Partial<typeof emptyReport.ruleSet> = {},
  trailingDrawdownPercent = 2
): ShortTermReport {
  return {
    ...emptyReport,
    ruleSet: { ...emptyReport.ruleSet, ...technicalRules },
    candidateCount: symbols.length,
    tradeCaptureTokens: Object.fromEntries(symbols.map((symbol) => [symbol, `token-${symbol}`])),
    candidates: symbols.map((symbol) => ({
      ...candidate(symbol),
      tradePlan: {
        status: 'BLOCKED',
        strategyLabel: '隔夜超短波段',
        blockedReasons: ['测试候选仅作观察'],
        trailingDrawdownPercent
      }
    })),
    generatedAt
  } as unknown as ShortTermReport
}

const researchSetup = (setupType = 'CONTRACTION_BREAKOUT', setupLabel = '收缩突破') => ({
  ruleVersion: 'short-term-research-v6',
  setupType,
  setupLabel,
  state: 'CONFIRMED',
  score: 81.25,
  trendScore: 80,
  relativeStrengthScore: 75,
  setupScore: 75,
  volumePriceScore: 100,
  trendContribution: 20,
  relativeStrengthContribution: 15,
  setupContribution: 26.25,
  volumePriceContribution: 20,
  triggerPrice: 5.32,
  invalidationPrice: 5.05,
  evidence: ['波动收缩后放量突破'],
  risks: ['冲高回落会使形态失效'],
  dataGaps: [] as string[]
})

function reportWithFundDirection(): ShortTermReport {
  return {
    ...reportWithCandidates(['600795']),
    marketFundDirection: {
      topInflows: [{
        code: 'BK1201',
        name: '电子',
        mainNetInflow: 25470566400,
        mainNetInflowRatio: 3.75,
        superLargeNetInflow: 18464870400,
        largeNetInflow: 7005696000,
        advancing: 411,
        declining: 102,
        constituentCount: 513,
        concentrationPercent: 18.4,
        sourceUrl: 'https://push2delay.eastmoney.com/api/qt/clist/get'
      }],
      topOutflows: [{
        code: 'BK0475',
        name: '银行',
        mainNetInflow: -9000000000,
        mainNetInflowRatio: -2.5,
        superLargeNetInflow: -6000000000,
        largeNetInflow: -3000000000,
        advancing: 4,
        declining: 28,
        constituentCount: 32,
        concentrationPercent: 7.2,
        sourceUrl: 'https://push2delay.eastmoney.com/api/qt/clist/get'
      }],
      coveredIndustryCount: 496,
      expectedIndustryCount: 496,
      coverageRatio: 1,
      tradeDate: '2026-08-07',
      fetchedAt: '2026-08-07T14:15:00+08:00',
      sourceName: '东方财富行业资金流',
      dataGaps: []
    }
  } as unknown as ShortTermReport
}

function shadowScan(symbols = ['600001'], tradeDate = '2026-07-23'): UpperShadowScan {
  return {
    tradeDate, assessedAt: `${tradeDate}T06:00:00Z`, status: 'READY', source: '已保存行情',
    expectedCount: 5500, validQuoteCount: 5500, shapeCount: symbols.length, historyMissingCount: 0,
    matchedCount: symbols.length, omittedCount: 0, dataGaps: [], ruleDescription: '20日箱体与长上影规则',
    candidates: symbols.map(symbol => ({
      symbol, name: `形态${symbol}`, industry: '专用设备', label: '横盘长上影', latestPrice: 12.34,
      changePercent: 1.5, amount: 450000000, turnoverRate: 4.1, marketTimestamp: `${tradeDate}T06:00:00Z`,
      boxRangePercent: 11.2, boxNetChangePercent: 2.1, boxHigh: 13.1, boxLow: 11.8,
      upperShadowRatioPercent: 68.5, upperShadowPercent: 3.4,
      evidence: ['20日箱体窄幅整理'], counterEvidence: ['冲高回落，也可能存在抛压']
    }))
  }
}

describe('ShortTermPage manual scan flow', () => {
  it('keeps raw quote acquisition audit fields in the report contract', () => {
    expect(emptyReport.coverage.rawExpectedCount).toBe(5895)
    expect(emptyReport.coverage.rawFetchedCount).toBe(5895)
    expect(emptyReport.coverage.excludedNoPriceCount).toBe(395)
    expect(emptyReport.coverage.rawComplete).toBe(true)
  })

  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    window.localStorage.removeItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
    window.localStorage.removeItem(CATCH_UP_STORAGE_KEY)
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    resetShortTermScanStoreForTest()
    vi.mocked(fetchDailyShortTermSnapshot).mockReset().mockResolvedValue({
      tradeDate: '2026-07-23', scheduledAt: '2026-07-23T06:00:00Z', startedAt: null,
      finishedAt: null, status: 'NOT_STARTED', message: '当日结果尚未生成',
      report: null, upperShadow: null, dataGaps: []
    })
    vi.mocked(fetchShortTermResultCache).mockReset().mockResolvedValue({
      tradeDate: '2026-07-23', scheduledAt: null, startedAt: null,
      finishedAt: null, status: 'NOT_STARTED', message: '尚无扫描结果缓存',
      report: null, upperShadow: null, dataGaps: []
    })
    vi.mocked(fetchOvernightBacktest).mockResolvedValue({} as never)
    vi.mocked(fetchShortTermValidationSummaries).mockResolvedValue([])
  })

  afterEach(() => {
    act(() => root.unmount())
    resetShortTermScanStoreForTest()
    useTradeFeedbackStore.setState({ casesById: {}, caseIdByRecommendation: {} })
    host.remove()
    vi.useRealTimers()
    vi.clearAllMocks()
    window.localStorage.removeItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
    window.localStorage.removeItem(CATCH_UP_STORAGE_KEY)
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it.each([false, true])('preserves server order and ranks with research present=%s', async (withResearch) => {
    const report = reportWithCandidates(['600002', '600001'])
    report.candidates = report.candidates.map((item, index) => ({
      ...item,
      rank: index === 0 ? 4 : 8,
      score: { ...item.score, rankingScore: index === 0 ? 81 : 99, visibleRankingAdjustment: index === 0 ? 1 : 19 },
      signalProfile: { ...item.signalProfile!, ...(withResearch ? { research: researchSetup() } : {}) }
    }))
    useShortTermScanStore.setState({ report })
    await renderPage(root)

    const rows = [...document.querySelectorAll('[aria-label="短线候选列表"] > button')]
    expect(rows.map((row) => row.querySelector('h3')?.textContent)).toEqual(['候选600002', '候选600001'])
    expect(rows[0].textContent).toContain('#4')
    expect(rows[1].textContent).toContain('#8')
  })

  it('filters research setups while keeping legacy and upper-shadow observations accessible', async () => {
    const report = reportWithCandidates(['600101', '600102', '600103'])
    report.candidates[0].signalProfile = { ...report.candidates[0].signalProfile!, research: researchSetup() }
    report.candidates[1].signalProfile = { ...report.candidates[1].signalProfile!, research: researchSetup('TREND_PULLBACK', '趋势回踩') }
    useShortTermScanStore.setState({ report, upperShadow: shadowScan(['600104']) })
    await renderPage(root)

    await clickButton('趋势回踩')
    const list = document.querySelector('[aria-label="短线候选列表"]')!
    expect(list.textContent).not.toContain('候选600101')
    expect(list.textContent).toContain('候选600102')
    expect(list.textContent).toContain('候选600103')
    expect(list.textContent).toContain('形态600104')
    expect(document.querySelector('[aria-label="研究买点筛选"] button[aria-pressed="true"]')?.textContent).toContain('趋势回踩')

    await clickButton('全部形态')
    expect(list.textContent).toContain('候选600101')
  })

  it('labels execution from the trade plan and advice rather than research confirmation', async () => {
    const report = reportWithCandidates(['600201', '600202', '600203', '600204', '600205'])
    report.candidates = report.candidates.map((item, index) => ({
      ...item,
      signalProfile: { ...item.signalProfile!, research: researchSetup() },
      tradePlan: { ...item.tradePlan!, status: index === 0 || index === 3 || index === 4 ? 'ACTIONABLE' : 'BLOCKED' },
      todayAdvice: { ...item.todayAdvice, action: (['ADD', 'ADD', 'WAIT_PULLBACK', 'LIGHT_TRIAL', 'WAIT'] as const)[index] }
    }))
    useShortTermScanStore.setState({ report })
    await renderPage(root)

    const rows = [...document.querySelectorAll('[aria-label="短线候选列表"] > button')]
    expect(rows.map((row) => row.querySelector('[data-testid="candidate-execution-status"]')?.textContent))
      .toEqual(['可执行', '观察', '等回踩', '可执行', '观察'])
    expect(rows[0].textContent).toContain('收缩突破')
    expect(rows[0].textContent).toContain('研究分 81.25')
  })

  it('separates research components and observation levels from the collapsed legacy score', async () => {
    const report = reportWithCandidates(['600301'])
    report.candidates[0].signalProfile = { ...report.candidates[0].signalProfile!, research: researchSetup() }
    useShortTermScanStore.setState({ report })
    await renderPage(root)
    await clickButton('候选600301')

    const panel = document.querySelector('[aria-label="研究买点详情"]')!
    expect(panel).not.toBeNull()
    expect(panel.textContent).toContain('趋势结构')
    expect(panel.textContent).toContain('相对强度')
    expect(panel.textContent).toContain('买点形态')
    expect(panel.textContent).toContain('量价确认')
    expect(panel.querySelector('[data-component="setup"]')?.textContent).toContain('75.00')
    expect(panel.querySelector('[data-component="setup"]')?.textContent).toContain('26.25')
    expect(panel.textContent).toContain('5.32')
    expect(panel.textContent).toContain('5.05')
    expect(panel.textContent).toContain('波动收缩后放量突破')
    expect(panel.textContent).toContain('冲高回落会使形态失效')
    expect(panel.querySelectorAll('a[href^="https://"]')).toHaveLength(3)
    const legacy = document.querySelector('details[aria-label="旧版评分对照"]') as HTMLDetailsElement
    expect(legacy).not.toBeNull()
    expect(legacy.open).toBe(false)
    expect(legacy.textContent).toContain('四因子原始分')
    const validationPanel = document.querySelector('[aria-label="信号解释与历史验证"]')!
    const oldContributions = validationPanel.querySelector('details') as HTMLDetailsElement
    expect(oldContributions).not.toBeNull()
    expect(oldContributions.open).toBe(false)
    expect(oldContributions.textContent).not.toContain('T1')
    expect(validationPanel.textContent).toContain('T1')
    expect(validationPanel.textContent).toContain('T2')
  })

  it('keeps unavailable research metrics missing instead of borrowing the legacy score', async () => {
    const report = reportWithCandidates(['600401'])
    report.candidates[0].signalProfile = {
      ...report.candidates[0].signalProfile!,
      research: {
        ...researchSetup('UNAVAILABLE', '数据待补'), state: 'UNAVAILABLE', score: null,
        trendScore: null, relativeStrengthScore: null, setupScore: null, volumePriceScore: null,
        trendContribution: null, relativeStrengthContribution: null, setupContribution: null, volumePriceContribution: null,
        triggerPrice: null, invalidationPrice: null, evidence: [], risks: [], dataGaps: ['历史 K 线不足']
      }
    }
    useShortTermScanStore.setState({ report })
    await renderPage(root)
    const list = document.querySelector('[aria-label="短线候选列表"]')!
    expect(list.textContent).toContain('研究分待补')
    expect(list.textContent).not.toContain('研究分 85')
    await clickButton('候选600401')
    const panel = document.querySelector('[aria-label="研究买点详情"]')!
    expect(panel).not.toBeNull()
    expect(panel.textContent).toContain('历史 K 线不足')
    expect(panel.querySelector('[data-component="trend"]')?.textContent).toContain('待补充')
    expect(panel.querySelector('[data-component="trend"]')?.textContent).not.toContain('0.00')
    expect(panel.textContent).not.toContain('85.00')
  })

  it('does not repeat a pre-gate research action when its plan is blocked', async () => {
    const report = reportWithCandidates(['600402'])
    report.candidates[0] = {
      ...report.candidates[0],
      action: 'RESEARCH_LIGHT_TRIAL',
      actionLabel: '收缩突破-待尾盘确认',
      todayAdvice: { ...report.candidates[0].todayAdvice, action: 'WAIT', actionLabel: '计划未通过' },
      signalProfile: { ...report.candidates[0].signalProfile!, research: researchSetup() }
    }
    useShortTermScanStore.setState({ report })
    await renderPage(root)
    await clickButton('候选600402')

    const detail = document.querySelector('[role="dialog"]')!
    expect(detail.textContent).toContain('计划未通过')
    expect(detail.textContent).not.toContain('收缩突破-待尾盘确认')
    expect(detail.querySelector('[data-testid="candidate-execution-status"]')?.textContent).toBe('观察')
  })

  it.each([
    ['short-term-research-v6', true],
    ['short-term-right-side-v4-transparent-ranking', false]
  ] as const)('recognizes an existing review case under %s', async (version, withResearch) => {
    const report = reportWithCandidates(['600403'])
    if (withResearch) report.candidates[0].signalProfile = { ...report.candidates[0].signalProfile!, research: researchSetup() }
    useTradeFeedbackStore.setState({
      caseIdByRecommendation: { [`600403|SHORT_TERM|${version}|2026-07-23T14:49:30+08:00`]: 'saved-case' }
    })
    useShortTermScanStore.setState({ report })
    await renderPage(root)
    await clickButton('候选600403')

    expect(document.querySelector('button[aria-label="已加入复盘 600403"]')?.getAttribute('aria-pressed')).toBe('true')
  })

  it('keeps the unified candidate card mounted through pending, running and empty results, then loads every completed candidate', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-23T06:00:00Z'))
    const pending: ShortTermDailySnapshot = {
      tradeDate: '2026-07-23', scheduledAt: '2026-07-23T06:00:00Z', startedAt: null,
      finishedAt: null, status: 'NOT_STARTED', message: '当日结果尚未生成',
      report: null, upperShadow: null, dataGaps: []
    }
    const symbols = Array.from({ length: 35 }, (_, index) => String(600001 + index))
    vi.mocked(fetchDailyShortTermSnapshot)
      .mockResolvedValueOnce(pending)
      .mockResolvedValueOnce({ ...pending, status: 'RUNNING', message: '扫描进行中' })
      .mockResolvedValueOnce({ ...pending, status: 'READY', message: '已完成，暂无匹配', report: emptyReport })
      .mockResolvedValueOnce({ ...pending, status: 'READY', message: '已完成', report: reportWithCandidates(['600795']), upperShadow: shadowScan(symbols) })
    vi.mocked(fetchShortTermResultCache)
      .mockResolvedValueOnce(pending)
      .mockResolvedValueOnce(pending)
      .mockResolvedValueOnce({ ...pending, runId: 'empty-run', startedAt: '2026-07-23T06:00:00Z', finishedAt: '2026-07-23T06:01:00Z', status: 'READY', message: '已完成，暂无匹配', report: emptyReport })
      .mockResolvedValueOnce({ ...pending, runId: 'full-run', startedAt: '2026-07-23T06:01:00Z', finishedAt: '2026-07-23T06:02:00Z', status: 'READY', message: '已完成', report: reportWithCandidates(['600795']), upperShadow: shadowScan(symbols) })

    await renderPagePlain(root)
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    expect(list).not.toBeNull()
    const card = list.closest('section')!
    expect(card.querySelector('[aria-label="已保存候选状态"]')).not.toBeNull()
    expect(card.textContent).toContain('当日结果尚未生成')
    expect(host.textContent).not.toContain('14:00 定时短线候选')
    expect(fetchDailyShortTermSnapshot).toHaveBeenCalledTimes(1)

    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
    expect(card.textContent).toContain('扫描进行中')
    expect(fetchDailyShortTermSnapshot).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(15000) })
    expect(card.textContent).toContain('已完成，暂无匹配')
    expect(fetchDailyShortTermSnapshot).toHaveBeenCalledTimes(3)
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })

    expect(host.querySelector('[aria-label="短线候选列表"]')).toBe(list)
    expect(list.querySelectorAll('button')).toHaveLength(36)
    expect(list.textContent).toContain('形态600035')
    expect(list.textContent).toContain('候选600795')
    expect(fetchDailyShortTermSnapshot).toHaveBeenCalledTimes(4)
    expect(startShortTermScanJob).not.toHaveBeenCalled()
  })

  it.each([{ label: 'absent', report: null }, { label: 'zero-candidate', report: emptyReport }])('shows saved upper-shadow candidates in the unified list with $label right-side report', async ({ report }) => {
    useShortTermScanStore.setState({ report, upperShadow: shadowScan() })
    await renderPage(root)
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    expect(list).not.toBeNull()
    expect(list.textContent).toContain('形态600001')
    expect(list.textContent).toContain('专用设备')
    expect(list.textContent).toContain('横盘长上影')
    expect(list.textContent).not.toContain('暂无候选')
    expect(list.textContent).not.toContain('排序分')
    expect(list.textContent).not.toContain('建议：')
    await clickButton('形态600001')
    const detail = document.querySelector('[role="dialog"]')!
    expect(detail.textContent).toContain('20日箱体')
    expect(detail.textContent).toContain('68.50%')
    expect(detail.textContent).toContain('2026-07-23 14:00:00')
    expect(detail.textContent).toContain('20日箱体窄幅整理')
    expect(detail.textContent).toContain('冲高回落，也可能存在抛压')
    expect(detail.textContent).not.toContain('今日建议')
    expect(detail.querySelector('button[aria-label^="买入 "]')).toBeNull()
    expect(detail.textContent).not.toContain('排序分')
  })

  it('deduplicates matching symbols, shows both source badges and industry on all rows, and keeps normal detail', async () => {
    useShortTermScanStore.setState({ report: reportWithCandidates(['600795', '600002']), upperShadow: shadowScan(['600795', '600001']) })
    await renderPage(root)
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    const rows = Array.from(list.querySelectorAll('button'))
    expect(rows).toHaveLength(3)
    const overlap = rows.find(row => row.textContent?.includes('600795'))!
    expect(overlap.textContent).toContain('右侧候选')
    expect(overlap.textContent).toContain('横盘长上影')
    expect(overlap.textContent).toContain('电力')
    expect(rows.find(row => row.textContent?.includes('600002'))?.textContent).toContain('电力')
    expect(rows.find(row => row.textContent?.includes('600001'))?.textContent).toContain('专用设备')
    expect(list.textContent).not.toContain('形态600795')
    await clickButton('候选600795')
    const detail = document.querySelector('[role="dialog"]')!
    expect(detail.textContent).toContain('今日建议')
    expect(detail.textContent).toContain('横盘长上影证据')
    expect(detail.textContent).toContain('冲高回落，也可能存在抛压')
  })

  it('does not merge a shape scan from a different market date into the current report', async () => {
    useShortTermScanStore.setState({ report: reportWithCandidates(['600795']), upperShadow: shadowScan(['600001'], '2026-07-22') })
    await renderPage(root)
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    expect(list.textContent).toContain('候选600795')
    expect(list.textContent).not.toContain('形态600001')
  })

  it('removes old same-day shapes when the new manual bundle contains no shapes', async () => {
    useShortTermScanStore.setState({ upperShadow: shadowScan() })
    await renderWithManualReport(root, emptyReport)
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    expect(list.textContent).not.toContain('形态600001')
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().report).toBe(emptyReport)
  })

  it('shows new manual shapes when its right-side report is blocked without retaining the previous run', async () => {
    useShortTermScanStore.setState({ report: reportWithCandidates(['600795']), upperShadow: shadowScan(['600001']) })
    mockManualReport(emptyReport, 'manual-shape-only')
    vi.mocked(fetchShortTermScanJob).mockResolvedValue({
      jobId: 'manual-shape-only', status: 'SUCCEEDED', tradeDate: '2026-07-23',
      resultStatus: 'DATA_BLOCKED', strategyVersion: 'short-term-right-side-v4-transparent-ranking',
      blockedReasons: ['右侧行情覆盖不足'], createdAt: '2026-07-23T06:40:00Z',
      startedAt: '2026-07-23T06:40:01Z', finishedAt: '2026-07-23T06:49:00Z',
      message: '右侧数据未通过，长上影观察已保存', report: reportWithCandidates(['600900']),
      upperShadow: shadowScan(['600002']), dataGaps: ['右侧行情覆盖不足']
    })
    await renderPage(root)
    await clickButton('重新扫描')
    const list = host.querySelector('[aria-label="短线候选列表"]')!
    expect(list.textContent).toContain('形态600002')
    expect(list.textContent).not.toContain('形态600001')
    expect(list.textContent).not.toContain('候选600795')
    expect(list.textContent).not.toContain('候选600900')
    expect(useShortTermScanStore.getState().report).toBeNull()
  })

  it('waits for an explicit manual action when the page mounts', async () => {
    await renderPage(root)

    expect(startShortTermScanJob).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
    expect(document.querySelector('section[aria-live="polite"]')).toBeNull()
    expect(document.body.textContent).not.toContain('SHORT TERM')
    expect(document.body.textContent).not.toContain('短线右侧')
    expect(document.body.textContent).not.toContain('deepseek / deepseek-v4-pro')
    expect(document.body.textContent).not.toContain('计划任务')
    expect(document.body.textContent).not.toContain('等待自动扫描')
  })

  it('does not expose the removed Agent discussion feature', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    expect(document.body.textContent).not.toContain(['Agent', '讨论'].join(''))
    expect(document.body.textContent).not.toContain(['Agent', '推荐'].join(''))
  })

  it('shows today volume against the previous three-day average on each candidate row', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    expect(document.body.textContent).toContain('今日量 / 前3日均')
    expect(document.body.textContent).toContain('128.4万手 / 96.2万手 · 1.33×')
  })

  it('shows a fail-closed placeholder for historical candidates without the new fields', async () => {
    const report = reportWithCandidates(['600795'])
    Object.assign(report.candidates[0].technical, {
      todayVolume: null,
      averageVolume3: null,
      volumeRatio3: null
    })
    await renderWithManualReport(root, report)

    expect(document.body.textContent).toContain('今日量 / 前3日均')
    expect(document.body.textContent).toContain('成交量待补')
  })

  it('keeps scan-time sentiment separate from the independent limit-up homepage', async () => {
    const report = {
      ...emptyReport,
      marketSentiment: { ...emptyReport.marketSentiment, advancing: 370, declining: 5176, score: 6.47, phase: '冰点/混沌' },
      coverage: { ...emptyReport.coverage, fetchedAt: '2026-10-15T06:29:19Z' }
    }
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue({
      tradeDate: '2026-10-15', fetchedAt: '2026-10-15T07:59:00Z', advancing: 429, declining: 5108,
      unchanged: 38, excludedCount: 0, totalCount: 5575, source: '独立行情'
    })
    useShortTermScanStore.setState({ report })
    await renderPage(root)

    const heading = [...host.querySelectorAll('h3')].find(node => node.textContent === '扫描时市场情绪')
    expect(heading).toBeDefined()
    const sentiment = heading!.closest('section')!
    expect(sentiment.textContent).toContain('行情采集 2026-10-15 14:29:19')
    expect(sentiment.textContent).toContain('370 / 5176')
    expect(sentiment.textContent).toContain('6.47')
    expect(sentiment.textContent).not.toContain('429')
    expect(host.textContent).not.toContain('涨停看板')
    expect(fetchShortTermMarketBreadth).not.toHaveBeenCalled()
    expect(host.querySelector('[aria-label="扫描结果时间"]')?.textContent).toContain('行情采集 2026-10-15 14:29:19')
    expect(useShortTermScanStore.getState().report).toBe(report)
    expect(startShortTermScanJob).not.toHaveBeenCalled()
  })

  it('defaults to the remembered compact result view after a manual scan result loads', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    expect((document.querySelector('input[aria-label="展示今日资金去向"]') as HTMLInputElement | null)?.checked).toBe(false)
    expect((document.querySelector('input[aria-label="展示市场情绪"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect((document.querySelector('input[aria-label="展示热门方向"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect(document.querySelector('[data-testid="short-term-horizontal-summary"]')).not.toBeNull()
    expect(document.body.textContent).not.toContain('只使用当天行情')
    expect(document.body.textContent).not.toContain('行业资金流暂不可用')
    expect(document.body.textContent).toContain('市场情绪')
    expect(document.body.textContent).toContain('热门方向')
    expect(document.body.textContent).not.toContain('方法')
    expect(document.body.textContent).not.toContain('扫描快照')
    expect(document.body.textContent).not.toContain('当前规则')
  })

  it('persists result view toggles across remounts', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    const fundFlowToggle = document.querySelector('input[aria-label="展示今日资金去向"]') as HTMLInputElement | null
    expect(fundFlowToggle?.checked).toBe(false)
    await act(async () => {
      fundFlowToggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(document.body.textContent).toContain('今日资金去向')
    expect(JSON.parse(window.localStorage.getItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY) ?? '{}')).toEqual(expect.objectContaining({
      fundFlowVisible: true
    }))

    act(() => root.unmount())
    root = createRoot(host)
    await renderWithManualReport(root, reportWithCandidates(['600795']), 'manual-2')

    expect((document.querySelector('input[aria-label="展示今日资金去向"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect(document.body.textContent).toContain('今日资金去向')
  })

  it('renders today market fund direction with explicit inflow and outflow sections', async () => {
    await renderWithManualReport(root, {
      ...reportWithCandidates(['600795']),
      marketFundDirection: {
        topInflows: [{
          code: 'BK1201',
          name: '电子',
          mainNetInflow: 25470566400,
          mainNetInflowRatio: 3.75,
          superLargeNetInflow: 18464870400,
          largeNetInflow: 7005696000,
          advancing: 411,
          declining: 102,
          constituentCount: 513,
          concentrationPercent: 18.4,
          sourceUrl: 'https://push2delay.eastmoney.com/api/qt/clist/get'
        }],
        topOutflows: [{
          code: 'BK0475',
          name: '银行',
          mainNetInflow: -9000000000,
          mainNetInflowRatio: -2.5,
          superLargeNetInflow: -6000000000,
          largeNetInflow: -3000000000,
          advancing: 4,
          declining: 28,
          constituentCount: 32,
          concentrationPercent: 7.2,
          sourceUrl: 'https://push2delay.eastmoney.com/api/qt/clist/get'
        }],
        coveredIndustryCount: 496,
        expectedIndustryCount: 496,
        coverageRatio: 1,
        tradeDate: '2026-08-07',
        fetchedAt: '2026-08-07T14:15:00+08:00',
        sourceName: '东方财富行业资金流',
        dataGaps: []
      }
    })
    await toggleResultView('展示今日资金去向')

    expect(document.body.textContent).toContain('今日资金去向')
    expect(document.body.textContent).toContain('主力流入')
    expect(document.body.textContent).toContain('电子')
    expect(document.body.textContent).toContain('主力流出')
    expect(document.body.textContent).toContain('银行')
  })

  it('renders an explicit unavailable state for legacy reports without market fund direction', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    expect(document.body.textContent).not.toContain('行业资金流暂不可用')
    await toggleResultView('展示今日资金去向')

    expect(document.body.textContent).toContain('今日资金去向')
    expect(document.body.textContent).toContain('行业资金流暂不可用')
  })

  it('opens the industry leader popup with top 10 stocks when a fund direction item is clicked', async () => {
    vi.mocked(fetchShortTermIndustryLeaders).mockResolvedValue({
      code: 'BK1201',
      name: '电子',
      flowDirection: 'INFLOW',
      tradeDate: '2026-08-07',
      fetchedAt: '2026-08-07T14:15:00+08:00',
      sourceName: '东方财富板块个股资金流',
      dataGaps: [],
      stocks: [
        {
          rank: 1,
          symbol: '002475',
          name: '立讯精密',
          latestPrice: 41.2,
          changePercent: 5.6,
          mainNetInflow: 1200000000,
          mainNetInflowRatio: 8.4,
          superLargeNetInflow: 800000000,
          largeNetInflow: 400000000,
          tradeDate: '2026-08-07',
          marketTimestamp: '2026-08-07T14:15:00+08:00',
          sourceUrl: null
        },
        {
          rank: 2,
          symbol: '600703',
          name: '三安光电',
          latestPrice: 15.8,
          changePercent: 3.2,
          mainNetInflow: 600000000,
          mainNetInflowRatio: 5.1,
          superLargeNetInflow: 400000000,
          largeNetInflow: 200000000,
          tradeDate: '2026-08-07',
          marketTimestamp: '2026-08-07T14:15:00+08:00',
          sourceUrl: null
        }
      ]
    })
    await renderWithManualReport(root, reportWithFundDirection())
    await toggleResultView('展示今日资金去向')

    const inflowButton = document.querySelector('button[aria-label="查看 电子 板块内主力资金前 20 个股"]') as HTMLButtonElement | null
    expect(inflowButton).not.toBeNull()
    await act(async () => {
      inflowButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(fetchShortTermIndustryLeaders).toHaveBeenCalledWith('BK1201', { name: '电子', flow: 'INFLOW', limit: 20 })
    expect(document.body.textContent).toContain('电子 · 行业资金排名')
    expect(document.body.textContent).toContain('立讯精密')
    expect(document.body.textContent).toContain('三安光电')
    expect(document.body.textContent).toContain('主力净流入前 20')

    const popupCloseButton = document.querySelector('button[aria-label="关闭详情"]') as HTMLButtonElement | null
    expect(popupCloseButton).not.toBeNull()
    await act(async () => {
      popupCloseButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })
    expect(document.body.textContent).not.toContain('电子 · 行业资金排名')

    const outflowButton = document.querySelector('button[aria-label="查看 银行 板块内主力资金前 20 个股"]') as HTMLButtonElement | null
    expect(outflowButton).not.toBeNull()
    await act(async () => {
      outflowButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(fetchShortTermIndustryLeaders).toHaveBeenLastCalledWith('BK0475', { name: '银行', flow: 'OUTFLOW', limit: 20 })
    expect(document.body.textContent).toContain('银行 · 行业资金排名')
    expect(document.body.textContent).toContain('主力净流出前 20')
  })

  it('shows a retry hint when the industry leader popup request fails', async () => {
    vi.mocked(fetchShortTermIndustryLeaders).mockRejectedValue(new Error('网络异常'))
    await renderWithManualReport(root, reportWithFundDirection())
    await toggleResultView('展示今日资金去向')

    const inflowButton = document.querySelector('button[aria-label="查看 电子 板块内主力资金前 20 个股"]') as HTMLButtonElement | null
    await act(async () => {
      inflowButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(document.body.textContent).toContain('板块个股资金流获取失败，请稍后重试。')
    expect(document.body.textContent).toContain('重试')
  })

  it('shows the four core signals and candle-strength evidence in the candidate detail', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    await clickButton('候选600795')

    expect(document.body.textContent).toContain('金叉 45%')
    expect(document.body.textContent).toContain('量能 30%')
    expect(document.body.textContent).toContain('换手 15%')
    expect(document.body.textContent).toContain('收盘强度 10%')
    expect(document.body.textContent).toContain('四因子原始分')
    expect(document.body.textContent).toContain('阶段校准')
    expect(document.body.textContent).toContain('资金流微调（最多±2）')
    expect(document.body.textContent).toContain('热点方向修正（最多±2）')
    expect(document.body.textContent).toContain('相对强度修正（最多±4）')
    expect(document.body.textContent).toContain('行业成交额地位修正（最多±2）')
    expect(document.body.textContent).toContain('横截面合计（最多±8）')
    expect(document.body.textContent).toContain('排序分')
    expect(document.body.textContent).not.toContain('综合分')
    expect(document.body.textContent).toContain('换手率')
    expect(document.body.textContent).toContain('3%')
    expect(document.body.textContent).toContain('上影线中位数')
    expect(document.body.textContent).toContain('18.00%')
    expect(document.body.textContent).toContain('盘中暂定')
    expect(document.body.textContent).toContain('5/10/20日涨幅')
    expect(document.body.textContent).toContain('市场5/10/20分位')
    expect(document.body.textContent).toContain('同行5/10/20分位')
    expect(document.body.textContent).toContain('行业成交额地位')
    expect(document.body.textContent).toContain('7/20')
  })

  it('shows lower-shadow support confirmation and its independent evidence', async () => {
    const supportCandidate = {
      ...candidate('600041', '承接股份'),
      phase: 'SUPPORT_REVERSAL',
      phaseLabel: '长下影承接',
      action: 'SUPPORT_REVERSAL_LIGHT_TRIAL',
      actionLabel: '长下影承接-轻仓',
      score: {
        ...candidate('600041').score,
        supportReversalScore: 91,
        rankingScore: 86
      },
      technical: {
        ...candidate('600041').technical,
        supportReversal: {
          state: 'CONFIRMED',
          stateLabel: '长下影承接确认',
          score: 91,
          lowerShadowPercent: 81.48,
          bodyPercent: 14.81,
          upperShadowPercent: 3.7,
          closeLocationPercent: 81.48,
          supportType: 'MA5',
          supportPrice: 10.6,
          supportReclaimed: true,
          trendQualified: true,
          volumeQualified: true,
          turnoverQualified: true,
          provisional: false,
          reasons: [],
          dataGaps: []
        }
      }
    }
    const report = reportWithCandidates(['600041'])
    await renderWithManualReport(root, {
      ...report,
      candidates: [supportCandidate]
    } as unknown as ShortTermReport)

    expect(document.body.textContent).toContain('长下影承接确认')
    await clickButton('承接股份')

    expect(document.body.textContent).toContain('下影线占比')
    expect(document.body.textContent).toContain('收复支撑')
    expect(document.body.textContent).toContain('5 日线 10.60 元')
    expect(document.body.textContent).toContain('承接反转分')
  })

  it('hides lower-shadow support score when the signal is not certified', async () => {
    const ordinaryCandidate = {
      ...candidate('600042', '普通上涨'),
      score: {
        ...candidate('600042').score,
        supportReversalScore: 65
      },
      technical: {
        ...candidate('600042').technical,
        supportReversal: {
          state: 'NONE',
          stateLabel: '长下影承接未确认',
          score: 65,
          lowerShadowPercent: 20,
          bodyPercent: 45,
          upperShadowPercent: 35,
          closeLocationPercent: 60,
          supportType: null,
          supportPrice: null,
          supportReclaimed: false,
          trendQualified: true,
          volumeQualified: true,
          turnoverQualified: true,
          provisional: false,
          reasons: ['下影线占比不足 50%'],
          dataGaps: []
        }
      }
    }
    const report = reportWithCandidates(['600042'])
    await renderWithManualReport(root, {
      ...report,
      candidates: [ordinaryCandidate]
    } as unknown as ShortTermReport)

    await clickButton('普通上涨')
    expect(document.body.textContent).not.toContain('承接反转分')
    expect(document.body.textContent).not.toContain('长下影承接未确认')
  })

  it('shows a confirmed buy entry action in the short-term candidate detail', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    await clickButton('候选600795')

    expect(document.querySelector('button[aria-label="买入 候选600795 600795"]')).not.toBeNull()
  })

  it('does not render chip data even when the candidate response contains a complete snapshot', async () => {
    const verifiedCandidate = {
      ...candidate('600795'),
      score: {
        ...candidate('600795').score,
        v2RankingScore: 82,
        chipContributionScore: 21.5,
        v3RankingScore: 88.4,
        v2Rank: 5,
        v3Rank: 2,
        rankDelta: 3,
        rankingScore: 88.4
      },
      chip: {
        dataQuality: 'VALID',
        calculationMode: 'COMPLETED_BAR',
        localTradeDate: '2026-07-22',
        externalTradeDate: '2026-07-22',
        averageCost: 5.05,
        cost5: 4.72,
        cost15: 4.86,
        cost50: 5.03,
        cost85: 5.28,
        cost95: 5.46,
        winnerRatePercent: 68.2,
        overheadChipRatioPercent: 31.8,
        cost70Low: 4.86,
        cost70High: 5.28,
        cost70ConcentrationPercent: 8.31,
        cost90Low: 4.72,
        cost90High: 5.46,
        cost90ConcentrationPercent: 14.65,
        distanceToAverageCostPercent: 3.17,
        priorHighPrice: 5.8,
        priorHighZoneResidualRatioPercent: 7.5,
        turnoverSincePriorHighPercent: 128,
        costPositionScore: 85,
        concentrationScore: 78,
        overheadReliefScore: 68.2,
        priorHighDigestionScore: 92,
        chipStructureScore: 86,
        verificationStatus: 'VERIFIED',
        verificationLabel: '本地估算 · 外部数据已核验',
        verificationCoefficient: 1,
        contributionScore: 21.5,
        averageCostDeviation: 0.018,
        cost70BandOverlap: 0.82,
        winnerRateDeviation: 0.04,
        distributionBuckets: [
          { lowPrice: 4.72, highPrice: 4.9, price: 4.82, chipRatioPercent: 18.2, normalizedHeight: 42 },
          { lowPrice: 4.9, highPrice: 5.18, price: 5.05, chipRatioPercent: 43.6, normalizedHeight: 100 },
          { lowPrice: 5.3, highPrice: 5.46, price: 5.38, chipRatioPercent: 12.4, normalizedHeight: 28 }
        ],
        concentrationZones: [
          {
            rank: 1,
            lowPrice: 4.9,
            highPrice: 5.18,
            peakPrice: 5.05,
            chipRatioPercent: 43.6,
            distanceToCurrentPricePercent: -3.07,
            positionToCurrentPrice: 'BELOW'
          },
          {
            rank: 2,
            lowPrice: 5.3,
            highPrice: 5.46,
            peakPrice: 5.38,
            chipRatioPercent: 12.4,
            distanceToCurrentPricePercent: 3.26,
            positionToCurrentPrice: 'ABOVE'
          }
        ],
        dominantPeakPrice: 5.05,
        dominantZoneLow: 4.9,
        dominantZoneHigh: 5.18,
        dominantZoneChipRatioPercent: 43.6,
        currentPricePosition: 'ABOVE',
        nearestOverheadZone: {
          rank: 2,
          lowPrice: 5.3,
          highPrice: 5.46,
          peakPrice: 5.38,
          chipRatioPercent: 12.4,
          distanceToCurrentPricePercent: 3.26,
          positionToCurrentPrice: 'ABOVE'
        },
        modelVersion: 'short-term-chip-v2-peaks',
        dataGaps: []
      }
    }
    const baseReport = reportWithCandidates(['600795']) as unknown as Record<string, unknown>
    await renderWithManualReport(root, {
      ...baseReport,
      candidates: [verifiedCandidate]
    } as never)
    expect(document.body.textContent).not.toContain('筹码')
    expect(document.body.textContent).not.toContain('排序贡献 21.50')
    expect(document.body.textContent).not.toContain('距成本 +3.17%')

    await clickButton('候选600795')
    expect(document.body.textContent).not.toContain('筹码')
    expect(document.body.textContent).not.toContain('本地估算 · 外部数据已核验')
    expect(document.body.textContent).not.toContain('主筹码峰')
    expect(document.body.textContent).not.toContain('最近上方筹码区')
  })

  it('does not render a chip placeholder for legacy candidates', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    await clickButton('候选600795')

    expect(document.body.textContent).not.toContain('筹码')
    expect(document.body.textContent).not.toContain('历史版本未计算')
  })

  it('opens a partial legacy report without rendering its chip data', async () => {
    const legacyReport = {
      ...emptyReport,
      candidateCount: 1,
      candidates: [{
        ...candidate('600795'),
        chip: {
          dataQuality: 'OK',
          calculationMode: 'COMPLETED_BAR',
          verificationStatus: 'SINGLE_SOURCE',
          verificationLabel: '仅本地模型',
          modelVersion: 'short-term-chip-v1'
        }
      }]
    } as Record<string, unknown>
    const legacyScore = (legacyReport.candidates as Array<Record<string, unknown>>)[0].score as Record<string, unknown>
    delete legacyScore.technicalRankingScore
    delete legacyScore.rankingScore
    delete legacyScore.visibleRankingAdjustment
    delete legacyScore.stageAdjustment
    delete legacyScore.marketHeatContribution
    delete legacyScore.relativeStrengthContribution
    delete legacyScore.industryLeadershipContribution
    delete legacyScore.crossSectionAdjustment
    delete legacyReport.ruleSet
    await renderWithManualReport(root, legacyReport as never)

    expect(document.body.textContent).toContain('结构分待补')
    expect(document.body.textContent).not.toContain('结构分 74.00')
    expect(document.body.textContent).toContain('排序分待补')
    expect(document.body.textContent).not.toContain('排序分 74.00')
    await clickButton('候选600795')

    expect(document.body.textContent).not.toContain('筹码')
    expect(document.body.textContent).not.toContain('仅本地模型')
    expect(document.body.textContent).not.toContain('历史版本未计算完整筹码峰')
    expect(document.body.textContent).not.toContain('阶段校准')
    expect(document.body.textContent).not.toContain('横截面合计')
  })

  it('loads honest T1/T2 cohort validation without reviving the legacy backtest panel', async () => {
    vi.mocked(fetchShortTermValidationSummaries).mockResolvedValue([{
      ruleVersion: 'short-term-right-side-v4-transparent-ranking',
      signalFamily: 'GOLDEN_CROSS_BREAKOUT',
      marketRegime: 'RISK_ON',
      horizon: 'T1',
      status: 'INSUFFICIENT_SAMPLE',
      minimumSampleCount: 30,
      sampleCount: 2,
      positiveRatePercent: null,
      averageNetReturnPercent: null,
      medianNetReturnPercent: null,
      averageMfePercent: null,
      averageMaePercent: null
    }, {
      ruleVersion: 'short-term-right-side-v4-transparent-ranking',
      signalFamily: 'GOLDEN_CROSS_BREAKOUT',
      marketRegime: 'RISK_ON',
      horizon: 'T2',
      status: 'INSUFFICIENT_SAMPLE',
      minimumSampleCount: 30,
      sampleCount: 0,
      positiveRatePercent: null,
      averageNetReturnPercent: null,
      medianNetReturnPercent: null,
      averageMfePercent: null,
      averageMaePercent: null
    }])
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    await flushPromises()

    expect(fetchOvernightBacktest).not.toHaveBeenCalled()
    expect(fetchShortTermValidationSummaries).toHaveBeenCalledWith({
      cohorts: [
        { signalFamily: 'GOLDEN_CROSS_BREAKOUT', marketRegime: 'RISK_ON', horizon: 'T1' },
        { signalFamily: 'GOLDEN_CROSS_BREAKOUT', marketRegime: 'RISK_ON', horizon: 'T2' }
      ]
    })
    expect(toast.success).toHaveBeenCalledWith(
      '手动分析已完成，已生成当前时点候选，已生成 1 个候选',
      manualSuccessToastOptions
    )
    expect(document.querySelector('section[aria-live="polite"]')).toBeNull()
    expect(document.body.textContent).not.toContain('手动最终结果已就绪')
    await clickButton('候选600795')
    expect(document.body.textContent).toContain('信号解释与历史验证')
    expect(document.body.textContent).toContain('T1 · 样本积累中 2/30')
    expect(document.body.textContent).toContain('T2 · 样本积累中 0/30')
    expect(document.body.textContent).not.toContain('正收益占比 0.00%')
    expect(document.body.textContent).not.toContain('未回放')
  })

  it('reuses one in-flight validation request during the StrictMode effect replay', async () => {
    useShortTermScanStore.setState({ report: reportWithCandidates(['600795']) })

    await renderPage(root)

    expect(fetchShortTermValidationSummaries).toHaveBeenCalledTimes(1)
  })

  it('keeps candidate order unchanged when cohort validation is unavailable', async () => {
    vi.mocked(fetchShortTermValidationSummaries).mockRejectedValue(new Error('验证接口暂不可用'))
    useShortTermScanStore.setState({
      report: reportWithCandidates(['600795', '600900'])
    })

    await renderPage(root)

    const rows = [...document.querySelectorAll('button')]
      .filter((item) => item.textContent?.includes('候选600'))
      .map((item) => item.textContent)
    expect(rows[0]).toContain('候选600795')
    expect(rows[1]).toContain('候选600900')
  })

  it.each(['重新扫描', '应用阈值'])(
    'starts the existing scan flow only after the explicit %s action',
    async (actionLabel) => {
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v4-transparent-ranking',
      blockedReasons: [],
      createdAt: '2026-07-23T14:54:00+08:00',
      startedAt: null,
      finishedAt: null,
      message: '手动扫描中',
      report: null
    })
    vi.mocked(fetchShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'SUCCEEDED',
      tradeDate: '2026-07-23',
      resultStatus: 'NO_TRADE',
      strategyVersion: 'short-term-right-side-v4-transparent-ranking',
      blockedReasons: [],
      createdAt: '2026-07-23T14:54:00+08:00',
      startedAt: '2026-07-23T14:54:00+08:00',
      finishedAt: '2026-07-23T14:55:00+08:00',
      message: '手动扫描完成',
      report: emptyReport
    })
    await renderPage(root)

    const button = [...document.querySelectorAll('button')]
      .find((item) => item.textContent?.includes(actionLabel))
    expect(button).toBeDefined()
    await act(async () => {
      button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(startShortTermScanJob).toHaveBeenCalledTimes(1)
    expect(startShortTermScanJob).toHaveBeenCalledWith(expect.objectContaining({
      limit: 0,
      scanLimit: 0,
      klineLimit: 0,
      minVolumeRatio: 1.2,
      allowStaticCachePreview: false,
      allowChiNext: false
    }))
    expect(document.body.textContent).toContain('候选数量（0=全部）')
    expect(document.body.textContent).toContain('扫描数量（0=全部）')
    expect(document.body.textContent).toContain('K线复核数（0=全部）')
    expect(document.body.textContent).toContain('财报风险提示线')
    expect(document.body.textContent).toContain('默认完整复核并展示全部；这些参数用于手动扫描，定时任务使用默认参数。')
    expect(fetchShortTermScanJob).toHaveBeenCalledWith('manual-1')
    }
  )

  it('lets manual scans explicitly enable static cache preview from the page switch', async () => {
    mockManualReport(emptyReport, 'manual-cache-toggle')
    await renderPage(root)

    const toggle = document.querySelector('input[aria-label="允许休市缓存预览"]') as HTMLInputElement | null
    expect(toggle).not.toBeNull()
    expect(toggle?.checked).toBe(false)
    await act(async () => {
      toggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    await clickButton('重新扫描')

    expect(startShortTermScanJob).toHaveBeenCalledWith(expect.objectContaining({
      allowStaticCachePreview: true
    }))
  })

  it('lets manual scans opt in to ChiNext stocks when the account has permission', async () => {
    mockManualReport(emptyReport, 'manual-chinext-toggle')
    await renderPage(root)

    const toggle = document.querySelector('input[aria-label="允许创业板"]') as HTMLInputElement | null
    expect(toggle).not.toBeNull()
    expect(toggle?.checked).toBe(false)
    await act(async () => {
      toggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    await clickButton('重新扫描')

    expect(startShortTermScanJob).toHaveBeenCalledWith(expect.objectContaining({
      allowChiNext: true
    }))
  })

  it('does not show a scheduled pulse during manual scans', async () => {
    mockManualReport(reportWithCandidates(['600900']), 'manual-scheduled-pulse')

    await renderPagePlain(root)
    await clickButton('重新扫描')

    expect(document.body.textContent).toContain('候选600900')
    expect(document.body.textContent).not.toContain('自动扫描正在执行')
    expect(document.body.textContent).not.toContain('计划任务')
  })

  it('does not render the top status card after a manual scan result loads', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']), 'manual-status-hidden')

    expect(document.body.textContent).toContain('候选600795')
    expect(toast.success).toHaveBeenCalledWith(
      expect.stringContaining('已生成 1 个候选'),
      manualSuccessToastOptions
    )
    expect(document.body.textContent).not.toContain('手动最终结果已就绪')
    expect(document.body.textContent).not.toContain('手动重算')
  })

  it('keeps polling a manual scan after the short-term page unmounts and restores the result on return', async () => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-background',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v4-transparent-ranking',
      blockedReasons: [],
      createdAt: '2026-07-23T14:54:00+08:00',
      startedAt: null,
      finishedAt: null,
      message: '手动扫描中',
      report: null
    })
    vi.mocked(fetchShortTermScanJob)
      .mockResolvedValueOnce({
        jobId: 'manual-background',
        status: 'RUNNING',
        tradeDate: '2026-07-23',
        resultStatus: 'RUNNING',
        strategyVersion: 'short-term-right-side-v4-transparent-ranking',
        blockedReasons: [],
        createdAt: '2026-07-23T14:54:00+08:00',
        startedAt: '2026-07-23T14:54:01+08:00',
        finishedAt: null,
        message: '后台扫描中',
        report: null
      })
      .mockResolvedValueOnce({
        jobId: 'manual-background',
        status: 'SUCCEEDED',
        tradeDate: '2026-07-23',
        resultStatus: 'FINAL_READY',
        strategyVersion: 'short-term-right-side-v4-transparent-ranking',
        blockedReasons: [],
        createdAt: '2026-07-23T14:48:00+08:00',
        startedAt: '2026-07-23T14:48:01+08:00',
        finishedAt: '2026-07-23T14:49:30+08:00',
        message: '手动扫描完成',
        report: reportWithCandidates(['600901'])
      })

    await renderPagePlain(root)
    await clickButton('重新扫描')
    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(1)

    act(() => root.unmount())
    root = createRoot(host)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1500)
      await flushPromises()
    })

    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(2)
    await renderPagePlain(root)
    expect(document.body.textContent).toContain('候选600901')
    expect(toast.success).toHaveBeenCalledWith(
      '手动扫描完成，已生成 1 个候选',
      manualSuccessToastOptions
    )
    vi.useRealTimers()
  })
})

async function renderPage(root: Root) {
  await act(async () => {
    root.render(
      <StrictMode>
        <ShortTermPage />
      </StrictMode>
    )
    await flushPromises()
  })
}

async function renderPagePlain(root: Root) {
  await act(async () => {
    root.render(<ShortTermPage />)
    await flushPromises()
  })
}

async function renderWithManualReport(root: Root, report: ShortTermReport, jobId = 'manual-1') {
  mockManualReport(report, jobId)
  await renderPage(root)
  await clickButton('重新扫描')
}

function mockManualReport(report: ShortTermReport, jobId: string) {
  vi.mocked(startShortTermScanJob).mockResolvedValue({
    jobId,
    status: 'RUNNING',
    tradeDate: '2026-07-23',
    resultStatus: 'RUNNING',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    blockedReasons: [],
    createdAt: '2026-07-23T14:48:00+08:00',
    startedAt: null,
    finishedAt: null,
    message: '手动扫描中',
    report: null
  })
  vi.mocked(fetchShortTermScanJob).mockResolvedValue({
    jobId,
    status: 'SUCCEEDED',
    tradeDate: '2026-07-23',
    resultStatus: report.candidateCount ? 'FINAL_READY' : 'NO_TRADE',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    blockedReasons: [],
    createdAt: '2026-07-23T14:48:00+08:00',
    startedAt: '2026-07-23T14:48:00+08:00',
    finishedAt: '2026-07-23T14:49:30+08:00',
    message: report.candidateCount
      ? '手动分析已完成，已生成当前时点候选'
      : '手动分析已完成，当前无合格候选',
    report: report as never
  })
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}

async function clickButton(label: string) {
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.textContent?.includes(label))
  expect(button).toBeDefined()
  await act(async () => {
    button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
  })
}

async function toggleResultView(label: string) {
  const checkbox = document.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement | null
  expect(checkbox).not.toBeNull()
  await act(async () => {
    checkbox?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
  })
}
