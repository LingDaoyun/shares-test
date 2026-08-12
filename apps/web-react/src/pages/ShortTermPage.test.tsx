// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchOvernightBacktest,
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import { ScheduledSnapshotStatus } from '../components/shortterm/ScheduledSnapshotStatus'
import { toast } from '../components/ui/Toast'
import { SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY } from '../lib/shortTermViewPreferences'
import { resetShortTermScanStoreForTest } from '../store/shortTermScanStore'
import type { ShortTermReport, ShortTermScheduledSnapshot, ShortTermSnapshotStatus } from '../types'
import { ShortTermPage } from './ShortTermPage'

vi.mock('../api/client', () => ({
  fetchOvernightBacktest: vi.fn(),
  fetchShortTermScanJob: vi.fn(),
  startShortTermScanJob: vi.fn(),
  fetchV2StrategyBundle: vi.fn().mockRejectedValue(new Error('测试中不加载 V2 策略束'))
}))

vi.mock('../components/ui/Toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn()
  }
}))

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
    decisionTimeLabel: '14:45-14:56',
    rules: [],
    warnings: [],
    status: 'OPEN',
    sourceName: 'SINA',
    marketTimestamp: '2026-07-23T14:52:00+08:00',
    calculatedAt: '2026-07-23T14:53:00+08:00'
  },
  methodology: ['只使用当天行情'],
  ruleSet: {
    limit: 8,
    scanLimit: 6000,
    klineLimit: 120,
    minAmount: 80000000,
    maxPe: 100,
    maxPb: 15,
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
    fetchedAt: '2026-07-23T14:52:00+08:00'
  },
  reviewedSymbols: [],
  dataCutoffAt: '2026-07-23T14:52:00+08:00',
  generatedAt: '2026-07-23T14:53:00+08:00'
} as ShortTermReport

const finalReadySnapshot: ShortTermScheduledSnapshot = {
  tradeDate: '2026-07-23',
  stage: 'FINAL',
  status: 'FINAL_READY',
  strategyVersion: 'short-term-right-side-v3-chip-verified',
  message: '尾盘最终结果已就绪',
  dataCutoffAt: '2026-07-23T14:52:00+08:00',
  completedAt: '2026-07-23T14:53:00+08:00',
  blockedReasons: [],
  report: emptyReport
}

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
    stageAdjustment: 11,
    rankingScore: 85
  },
  technical: {
    goldenCross: null,
    rightSideSignal: 'EARLY_CONFIRMED',
    ma20SlopePercent: 0.8,
    distanceToMa20Percent: 2.1,
    breakoutFromPreviousHigh20Percent: 1.3,
    volumeRatio20: 1.5,
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
  tradePlan: null
})

function reportWithCandidates(
  symbols: string[],
  generatedAt = '2026-07-23T14:53:00+08:00',
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

describe('ShortTermPage manual scan flow', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    window.localStorage.removeItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    resetShortTermScanStoreForTest()
    vi.mocked(fetchOvernightBacktest).mockResolvedValue({} as never)
  })

  afterEach(() => {
    act(() => root.unmount())
    resetShortTermScanStoreForTest()
    host.remove()
    vi.useRealTimers()
    vi.clearAllMocks()
    window.localStorage.removeItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('does not fetch a scheduled snapshot or render the plan task on mount', async () => {
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

  it('defaults to the remembered compact result view after a manual scan result loads', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    expect((document.querySelector('input[aria-label="展示方法"]') as HTMLInputElement | null)?.checked).toBe(false)
    expect((document.querySelector('input[aria-label="展示今日资金去向"]') as HTMLInputElement | null)?.checked).toBe(false)
    expect((document.querySelector('input[aria-label="展示市场情绪"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect((document.querySelector('input[aria-label="展示扫描快照"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect((document.querySelector('input[aria-label="展示热门方向"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect(document.querySelector('[data-testid="short-term-horizontal-summary"]')).not.toBeNull()
    expect(document.body.textContent).not.toContain('只使用当天行情')
    expect(document.body.textContent).not.toContain('行业资金流暂不可用')
    expect(document.body.textContent).toContain('方法')
    expect(document.body.textContent).toContain('市场情绪')
    expect(document.body.textContent).toContain('扫描快照')
    expect(document.body.textContent).toContain('热门方向')
    expect(document.body.textContent).not.toContain('当前规则')
  })

  it('persists result view toggles across remounts', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    const methodologyToggle = document.querySelector('input[aria-label="展示方法"]') as HTMLInputElement | null
    expect(methodologyToggle?.checked).toBe(false)
    await act(async () => {
      methodologyToggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(document.body.textContent).toContain('只使用当天行情')
    expect(JSON.parse(window.localStorage.getItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY) ?? '{}')).toEqual(expect.objectContaining({
      methodologyVisible: true
    }))

    act(() => root.unmount())
    root = createRoot(host)
    await renderWithManualReport(root, reportWithCandidates(['600795']), 'manual-2')

    expect((document.querySelector('input[aria-label="展示方法"]') as HTMLInputElement | null)?.checked).toBe(true)
    expect(document.body.textContent).toContain('只使用当天行情')
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
    expect(document.body.textContent).toContain('覆盖 496/496')
  })

  it('renders an explicit unavailable state for legacy reports without market fund direction', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))
    expect(document.body.textContent).not.toContain('行业资金流暂不可用')
    await toggleResultView('展示今日资金去向')

    expect(document.body.textContent).toContain('今日资金去向')
    expect(document.body.textContent).toContain('行业资金流暂不可用')
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
    expect(document.body.textContent).toContain('排序分')
    expect(document.body.textContent).not.toContain('综合分')
    expect(document.body.textContent).toContain('换手率')
    expect(document.body.textContent).toContain('3%')
    expect(document.body.textContent).toContain('上影线中位数')
    expect(document.body.textContent).toContain('18.00%')
    expect(document.body.textContent).toContain('盘中暂定')
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
    delete legacyReport.ruleSet
    await renderWithManualReport(root, legacyReport as never)
    await clickButton('候选600795')

    expect(document.body.textContent).not.toContain('筹码')
    expect(document.body.textContent).not.toContain('仅本地模型')
    expect(document.body.textContent).not.toContain('历史版本未计算完整筹码峰')
  })

  it('does not render or request the short-term overnight validation panel', async () => {
    await renderWithManualReport(root, reportWithCandidates(['600795']))

    expect(fetchOvernightBacktest).not.toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledWith('手动分析已完成，已生成当前时点候选，已生成 1 个候选')
    expect(document.querySelector('section[aria-live="polite"]')).toBeNull()
    expect(document.body.textContent).not.toContain('技术信号历史验证')
    expect(document.body.textContent).not.toContain('隔夜样本')
    expect(document.body.textContent).not.toContain('未回放')
  })

  it.each(['重新扫描', '应用阈值'])(
    'starts the existing scan flow only after the explicit %s action',
    async (actionLabel) => {
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v3-chip-verified',
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
      strategyVersion: 'short-term-right-side-v3-chip-verified',
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
      limit: 8,
      klineLimit: 120,
      minVolumeRatio: 1.2,
      allowStaticCachePreview: true,
      allowChiNext: false
    }))
    expect(fetchShortTermScanJob).toHaveBeenCalledWith('manual-1')
    }
  )

  it('lets manual scans disable static cache preview from the page switch', async () => {
    mockManualReport(emptyReport, 'manual-cache-toggle')
    await renderPage(root)

    const toggle = document.querySelector('input[aria-label="允许休市缓存预览"]') as HTMLInputElement | null
    expect(toggle).not.toBeNull()
    expect(toggle?.checked).toBe(true)
    await act(async () => {
      toggle?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    await clickButton('重新扫描')

    expect(startShortTermScanJob).toHaveBeenCalledWith(expect.objectContaining({
      allowStaticCachePreview: false
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

  it('keeps polling a manual scan after the short-term page unmounts and restores the result on return', async () => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-background',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v3-chip-verified',
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
        strategyVersion: 'short-term-right-side-v3-chip-verified',
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
        strategyVersion: 'short-term-right-side-v3-chip-verified',
        blockedReasons: [],
        createdAt: '2026-07-23T14:54:00+08:00',
        startedAt: '2026-07-23T14:54:01+08:00',
        finishedAt: '2026-07-23T14:55:00+08:00',
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
    expect(toast.success).toHaveBeenCalledWith('手动扫描完成，已生成 1 个候选')
    vi.useRealTimers()
  })
})

describe('ScheduledSnapshotStatus', () => {
  const expectations: Array<[ShortTermSnapshotStatus, string, string]> = [
    ['FINAL_READY', '14:55 前买入确认已就绪', 'emerald'],
    ['CACHE_PREVIEW', '缓存行情预览', 'sky'],
    ['PRESELECT_READY', '自动预选已就绪', 'border-line'],
    ['RUNNING', '自动任务执行中', 'border-line'],
    ['NO_TRADE', '今日不交易', 'amber'],
    ['DATA_BLOCKED', '数据质量阻断', 'red'],
    ['FAILED', '自动任务失败', 'red']
  ]

  it.each(expectations)('renders %s with its status discipline', (status, label, tone) => {
    const html = renderToStaticMarkup(
      <ScheduledSnapshotStatus
        snapshot={{
          ...finalReadySnapshot,
          status,
          message: label,
          report: status === 'FINAL_READY' ? emptyReport : null
        }}
        origin="SCHEDULED"
      />
    )

    expect(html).toContain(label)
    expect(html).toContain(tone)
    expect(html).toContain('short-term-right-side-v3-chip-verified')
    if (status === 'FINAL_READY') {
      expect(html).not.toContain('尾盘最终结果已就绪')
    }
  })

  it.each([
    ['RUNNING', '手动扫描执行中'],
    ['FAILED', '手动扫描失败'],
    ['FINAL_READY', '手动最终结果已就绪']
  ] as const)('uses manual copy for %s', (status, label) => {
    const html = renderToStaticMarkup(
      <ScheduledSnapshotStatus
        snapshot={{ ...finalReadySnapshot, status, message: '' }}
        origin="MANUAL"
      />
    )

    expect(html).toContain(label)
    expect(html).not.toContain('自动任务')
    expect(html).not.toContain('自动预选')
  })

  it('shows the configured waiting message when there is no same-day record', () => {
    const html = renderToStaticMarkup(
      <ScheduledSnapshotStatus
        snapshot={{
          ...finalReadySnapshot,
          stage: 'PRESELECT',
          status: 'RUNNING',
          message: '等待 0 30 14 * * MON-FRI 自动预选',
          dataCutoffAt: null,
          completedAt: null,
          report: null
        }}
        origin="SCHEDULED"
      />
    )

    expect(html).toContain('等待自动扫描')
    expect(html).toContain('等待 0 30 14 * * MON-FRI 自动预选')
    expect(html).not.toContain('自动任务执行中')
    expect(html).toContain('计划任务')
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
    strategyVersion: 'short-term-right-side-v3-chip-verified',
    blockedReasons: [],
    createdAt: '2026-07-23T14:54:00+08:00',
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
    strategyVersion: 'short-term-right-side-v3-chip-verified',
    blockedReasons: [],
    createdAt: '2026-07-23T14:54:00+08:00',
    startedAt: '2026-07-23T14:54:00+08:00',
    finishedAt: '2026-07-23T14:55:00+08:00',
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
