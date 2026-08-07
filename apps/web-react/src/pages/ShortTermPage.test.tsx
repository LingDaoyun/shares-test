// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLatestShortTermScheduledSnapshot,
  fetchOvernightBacktest,
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import { ScheduledSnapshotStatus } from '../components/shortterm/ScheduledSnapshotStatus'
import type { OvernightBacktestReport, ShortTermScheduledSnapshot, ShortTermSnapshotStatus } from '../types'
import { ScheduledScanPulse, ShortTermPage } from './ShortTermPage'

vi.mock('../api/client', () => ({
  fetchLatestShortTermScheduledSnapshot: vi.fn(),
  fetchOvernightBacktest: vi.fn(),
  fetchShortTermScanJob: vi.fn(),
  startShortTermScanJob: vi.fn(),
  fetchV2StrategyBundle: vi.fn().mockRejectedValue(new Error('测试中不加载 V2 策略束'))
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
}

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
) {
  return {
    ...emptyReport,
    ruleSet: { ...emptyReport.ruleSet, ...technicalRules },
    candidateCount: symbols.length,
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
  } as never
}

function overnightReport(
  symbols: string[],
  sampleCount: number,
  status: 'OK' | 'PARTIAL' | 'DATA_BLOCKED' = 'OK',
  message = '技术信号历史样本已生成'
): OvernightBacktestReport {
  return {
    scope: '短线 T+1/T+2 技术信号历史验证',
    validationScope: ['生产同源 K 线技术信号'],
    unreplayedGates: ['财报质量门禁', '市场情绪门禁', '尾盘分钟确认门禁', '实时行情新鲜度门禁'],
    methodology: [],
    ruleSet: {
      lookbackDays: 900,
      firstTargetPercent: 2.5,
      secondTargetPercent: 4.5,
      hardStopPercent: 3.5,
      maxHoldingTradingDays: 2,
      commissionPercent: 0.03,
      stampDutyPercent: 0.05,
      slippagePercent: 0.05,
      limitMovePercent: 9.8,
      minVolumeRatio: 1.2,
      maxDistanceToMa20Percent: 8,
      trailingDrawdownPercent: 2
    },
    symbols,
    status,
    message,
    summary: {
      symbolCount: symbols.length,
      sampleCount,
      positiveRatePercent: 58.33,
      averageReturnPercent: 0.82,
      medianReturnPercent: 0.55,
      averageRunupPercent: 2.1,
      averageDrawdownPercent: -1.4,
      firstTargetRatePercent: 25,
      secondTargetRatePercent: 16.67,
      hardStopRatePercent: 8.33,
      timeStopRatePercent: 50,
      gapDownRatePercent: 33.33,
      sampleStart: '2025-01-01',
      sampleEnd: '2026-07-01',
      conclusion: '技术样本正收益但波动需复核'
    },
    results: symbols.map((symbol) => ({
      symbol,
      status: status === 'OK' ? 'OK' : 'SOURCE_FAILED',
      klineCount: status === 'OK' ? 900 : 0,
      sampleCount: status === 'OK' ? sampleCount : 0,
      dataGaps: status === 'OK' ? [] : [`${symbol} K 线数据源失败`]
    })),
    trades: [],
    generatedAt: '2026-07-23T14:53:00+08:00'
  }
}

describe('ShortTermPage prepared snapshot mount', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(finalReadySnapshot)
    vi.mocked(fetchOvernightBacktest).mockResolvedValue(overnightReport([], 0))
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.useRealTimers()
    vi.clearAllMocks()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('loads the prepared snapshot without starting a scan job', async () => {
    await renderPage(root)

    expect(fetchLatestShortTermScheduledSnapshot).toHaveBeenCalledTimes(1)
    expect(startShortTermScanJob).not.toHaveBeenCalled()
    expect(fetchOvernightBacktest).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('尾盘最终结果已就绪')
    expect(document.body.textContent).toContain('计划任务')
  })

  it('keeps the current-rule card removed from the summary area', async () => {
    await renderPage(root)

    expect(document.body.textContent).toContain('方法')
    expect(document.body.textContent).toContain('市场情绪')
    expect(document.body.textContent).toContain('扫描快照')
    expect(document.body.textContent).toContain('热门方向')
    expect(document.body.textContent).not.toContain('当前规则')
  })

  it('renders today market fund direction with explicit inflow and outflow sections', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: {
        ...emptyReport,
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
      } as never
    })

    await renderPage(root)

    expect(document.body.textContent).toContain('今日资金去向')
    expect(document.body.textContent).toContain('主力流入')
    expect(document.body.textContent).toContain('电子')
    expect(document.body.textContent).toContain('主力流出')
    expect(document.body.textContent).toContain('银行')
    expect(document.body.textContent).toContain('覆盖 496/496')
  })

  it('renders an explicit unavailable state for legacy reports without market fund direction', async () => {
    await renderPage(root)

    expect(document.body.textContent).toContain('今日资金去向')
    expect(document.body.textContent).toContain('行业资金流暂不可用')
  })

  it('shows the four core signals and candle-strength evidence in the candidate detail', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600795'])
    })

    await renderPage(root)
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

  it('shows verified chip diagnostics as standalone evidence in the row and detail', async () => {
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
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: {
        ...baseReport,
        candidates: [verifiedCandidate]
      } as never
    })

    await renderPage(root)
    expect(document.body.textContent).toContain('筹码核验')
    expect(document.body.textContent).toContain('排序贡献 21.50')
    expect(document.body.textContent).toContain('距成本 +3.17%')

    await clickButton('候选600795')
    expect(document.body.textContent).toContain('筹码结构与外部认证')
    expect(document.body.textContent).toContain('本地估算 · 外部数据已核验')
    expect(document.body.textContent).toContain('筹码排序贡献')
    expect(document.body.textContent).toContain('主排序关系')
    expect(document.body.textContent).toContain('参与同层排序')
    expect(document.body.textContent).not.toContain('V2 / V3 排名')
    expect(document.body.textContent).toContain('主筹码峰')
    expect(document.body.textContent).toContain('主要集中区')
    expect(document.body.textContent).toContain('43.60%')
    expect(document.body.textContent).toContain('最近上方筹码区')
    expect(document.body.textContent).toContain('前高区残余筹码')
    expect(document.body.textContent).toContain('7.50%')
  })

  it('labels legacy candidates whose historical report has no chip snapshot', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600795'])
    })

    await renderPage(root)
    await clickButton('候选600795')

    expect(document.body.textContent).toContain('筹码结构与外部认证')
    expect(document.body.textContent).toContain('历史版本未计算')
  })

  it('opens a partial legacy report without ruleSet or chip dataGaps', async () => {
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
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: legacyReport as never
    })

    await renderPage(root)
    await clickButton('候选600795')

    expect(document.body.textContent).toContain('筹码结构与外部认证')
    expect(document.body.textContent).toContain('仅本地模型')
    expect(document.body.textContent).toContain('历史版本未计算完整筹码峰')
  })

  it('requests the T1/T2 overnight contract without a legacy 20-day holding window', async () => {
    vi.mocked(fetchOvernightBacktest).mockResolvedValue(overnightReport(['600795'], 12))
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600795'])
    })

    await renderPage(root)

    expect(fetchOvernightBacktest).toHaveBeenCalledWith(expect.objectContaining({
      symbols: '600795',
      lookbackDays: 900,
      firstTargetPercent: 2.5,
      secondTargetPercent: 4.5,
      hardStopPercent: 3.5,
      maxHoldingTradingDays: 2,
      minVolumeRatio: 1.2,
      maxDistanceToMa20Percent: 8,
      trailingDrawdownPercent: 2
    }))
    expect(fetchOvernightBacktest).not.toHaveBeenCalledWith(expect.objectContaining({
      holdingDays: 20
    }))
    expect(document.body.textContent).toContain('12 笔隔夜样本')
    expect(document.body.textContent).toContain('正收益率')
    expect(document.body.textContent).toContain('中位收益')
    expect(document.body.textContent).toContain('第一目标')
    expect(document.body.textContent).toContain('第二目标')
    expect(document.body.textContent).toContain('硬止损')
    expect(document.body.textContent).toContain('次日低开')
    expect(document.body.textContent).toContain('技术信号历史验证')
    expect(document.body.textContent).toContain('未回放')
    expect(document.body.textContent).not.toContain('完整生产策略胜率')
    expect(document.body.textContent).not.toContain('历史验证：可参考')
  })

  it('renders all-source failure as data blocked instead of a normal zero-sample result', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600701'])
    })
    vi.mocked(fetchOvernightBacktest).mockResolvedValue(overnightReport(
      ['600701'],
      0,
      'DATA_BLOCKED',
      '全部候选的 K 线数据源失败'
    ))

    await renderPage(root)

    expect(document.body.textContent).toContain('技术验证数据阻断')
    expect(document.body.textContent).toContain('全部候选的 K 线数据源失败')
    expect(document.body.textContent).not.toContain('0 笔隔夜样本')
  })

  it('shows partial symbol gaps at batch level', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600702', '600703'])
    })
    vi.mocked(fetchOvernightBacktest).mockResolvedValue({
      ...overnightReport(['600702', '600703'], 8, 'PARTIAL', '1 只候选数据缺失'),
      results: [
        { symbol: '600702', status: 'OK', klineCount: 900, sampleCount: 8, dataGaps: [] },
        { symbol: '600703', status: 'SOURCE_FAILED', klineCount: 0, sampleCount: 0, dataGaps: ['K 线数据源失败'] }
      ]
    })

    await renderPage(root)

    expect(document.body.textContent).toContain('1 只候选数据缺失')
    expect(document.body.textContent).toContain('600703')
    expect(document.body.textContent).toContain('K 线数据源失败')
  })

  it('ignores a late old result when the same-size candidate batch changes symbols', async () => {
    const oldBacktest = deferred<OvernightBacktestReport>()
    const newBacktest = deferred<OvernightBacktestReport>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600801', '600800'])
    })
    vi.mocked(fetchOvernightBacktest)
      .mockReturnValueOnce(oldBacktest.promise)
      .mockReturnValueOnce(newBacktest.promise)
    mockManualReport(reportWithCandidates(['600802', '600799']), 'switch-symbol')

    await renderPagePlain(root)
    await clickButton('重新扫描')
    await act(async () => {
      newBacktest.resolve(overnightReport(['600799', '600802'], 22))
      await flushPromises()
      oldBacktest.resolve(overnightReport(['600800', '600801'], 11))
      await flushPromises()
    })

    expect(fetchOvernightBacktest).toHaveBeenNthCalledWith(1, expect.objectContaining({ symbols: '600800,600801' }))
    expect(fetchOvernightBacktest).toHaveBeenNthCalledWith(2, expect.objectContaining({ symbols: '600799,600802' }))
    expect(document.body.textContent).toContain('22 笔隔夜样本')
    expect(document.body.textContent).not.toContain('11 笔隔夜样本')
  })

  it('refetches and owns the latest request when production thresholds change for the same symbols', async () => {
    const oldBacktest = deferred<OvernightBacktestReport>()
    const newBacktest = deferred<OvernightBacktestReport>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600806'], '2026-07-23T14:53:00+08:00', {
        minVolumeRatio: 1.15,
        maxDistanceToMa20Percent: 8
      })
    })
    vi.mocked(fetchOvernightBacktest)
      .mockReturnValueOnce(oldBacktest.promise)
      .mockReturnValueOnce(newBacktest.promise)
    mockManualReport(reportWithCandidates(['600806'], '2026-07-23T14:53:00+08:00', {
      minVolumeRatio: 1.45,
      maxDistanceToMa20Percent: 5.5
    }, 1.6), 'switch-thresholds')

    await renderPagePlain(root)
    await clickButton('重新扫描')
    await act(async () => {
      newBacktest.resolve(overnightReport(['600806'], 26))
      await flushPromises()
      oldBacktest.resolve(overnightReport(['600806'], 12))
      await flushPromises()
    })

    expect(fetchOvernightBacktest).toHaveBeenNthCalledWith(1, expect.objectContaining({
      symbols: '600806',
      minVolumeRatio: 1.15,
      maxDistanceToMa20Percent: 8
    }))
    expect(fetchOvernightBacktest).toHaveBeenNthCalledWith(2, expect.objectContaining({
      symbols: '600806',
      minVolumeRatio: 1.45,
      maxDistanceToMa20Percent: 5.5,
      trailingDrawdownPercent: 1.6
    }))
    expect(document.body.textContent).toContain('26 笔隔夜样本')
    expect(document.body.textContent).not.toContain('12 笔隔夜样本')
  })

  it('clears loading and old results when the candidate batch becomes empty', async () => {
    const oldBacktest = deferred<OvernightBacktestReport>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600803'])
    })
    vi.mocked(fetchOvernightBacktest).mockReturnValue(oldBacktest.promise)
    mockManualReport(reportWithCandidates([], '2026-07-23T14:54:00+08:00'), 'switch-empty')

    await renderPagePlain(root)
    await clickButton('重新扫描')
    await act(async () => {
      oldBacktest.resolve(overnightReport(['600803'], 33))
      await flushPromises()
    })

    expect(fetchOvernightBacktest).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).not.toContain('验证中')
    expect(document.body.textContent).not.toContain('33 笔隔夜样本')
  })

  it('ignores late old rejection and finally after a newer candidate batch succeeds', async () => {
    const oldBacktest = deferred<OvernightBacktestReport>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: reportWithCandidates(['600804'])
    })
    vi.mocked(fetchOvernightBacktest)
      .mockReturnValueOnce(oldBacktest.promise)
      .mockResolvedValueOnce(overnightReport(['600805'], 44))
    mockManualReport(reportWithCandidates(['600805']), 'switch-reject')

    await renderPagePlain(root)
    await clickButton('重新扫描')
    await act(async () => {
      oldBacktest.reject(new Error('旧批次失败'))
      await flushPromises()
    })

    expect(document.body.textContent).toContain('44 笔隔夜样本')
    expect(document.body.textContent).not.toContain('旧批次失败')
    expect(document.body.textContent).not.toContain('验证中')
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
    expect(fetchLatestShortTermScheduledSnapshot).toHaveBeenCalledTimes(1)
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

  it('keeps the manual result when the older scheduled request resolves later', async () => {
    const prepared = deferred<ShortTermScheduledSnapshot>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockReturnValue(prepared.promise)
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-2',
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
      jobId: 'manual-2',
      status: 'SUCCEEDED',
      tradeDate: '2026-07-23',
      resultStatus: 'DATA_BLOCKED',
      strategyVersion: 'short-term-right-side-v3-chip-verified',
      blockedReasons: ['QUOTE_STALE'],
      createdAt: '2026-07-23T14:54:00+08:00',
      startedAt: '2026-07-23T14:54:00+08:00',
      finishedAt: '2026-07-23T14:55:00+08:00',
      message: '尾盘行情已经过期',
      report: emptyReport
    })
    await renderPage(root)

    await clickButton('重新扫描')
    await act(async () => {
      prepared.resolve(finalReadySnapshot)
      await flushPromises()
    })

    expect(document.body.textContent).toContain('手动重算')
    expect(document.body.textContent).toContain('数据质量阻断')
    expect(document.body.textContent).toContain('QUOTE_STALE')
    expect(document.body.textContent).not.toContain('计划任务')
  })

  it('keeps manual loading and error ownership when the older scheduled request rejects', async () => {
    const prepared = deferred<ShortTermScheduledSnapshot>()
    const manualStart = deferred<Awaited<ReturnType<typeof startShortTermScanJob>>>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockReturnValue(prepared.promise)
    vi.mocked(startShortTermScanJob).mockReturnValue(manualStart.promise)
    await renderPage(root)

    await clickButton('重新扫描')
    await act(async () => {
      prepared.reject(new Error('旧计划快照失败'))
      await flushPromises()
    })

    expect(document.body.textContent).toContain('提交实时扫描任务')
    expect(document.body.textContent).toContain('手动重算')
    expect(document.body.textContent).not.toContain('旧计划快照失败')
  })

  it('shows scheduled running animation during background polling without replacing manual results', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(finalReadySnapshot)
    mockManualReport(reportWithCandidates(['600900']), 'manual-scheduled-pulse')

    await renderPagePlain(root)
    await clickButton('重新扫描')
    expect(document.body.textContent).toContain('手动重算')
    expect(document.body.textContent).toContain('候选600900')

    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      stage: 'FINAL',
      status: 'RUNNING',
      message: '短线右侧实时扫描中',
      report: null
    })

    await act(async () => {
      vi.advanceTimersByTime(10_000)
      await flushPromises()
    })

    expect(document.body.textContent).toContain('14:45 自动扫描正在执行')
    expect(document.body.textContent).toContain('短线右侧实时扫描中')
    expect(document.body.textContent).toContain('手动重算')
    expect(document.body.textContent).toContain('候选600900')
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

  it('shows scheduled scan animation while backend task is running', () => {
    const html = renderToStaticMarkup(
      <ScheduledScanPulse
        snapshot={{
          ...finalReadySnapshot,
          stage: 'FINAL',
          status: 'RUNNING',
          message: '短线右侧实时扫描中',
          report: null
        }}
      />
    )

    expect(html).toContain('14:45 自动扫描正在执行')
    expect(html).toContain('短线右侧实时扫描中')
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

function mockManualReport(report: typeof emptyReport, jobId: string) {
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
    message: '手动扫描完成',
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

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}
