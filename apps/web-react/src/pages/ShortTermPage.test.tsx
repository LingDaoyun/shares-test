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
import type { ShortTermScheduledSnapshot, ShortTermSnapshotStatus } from '../types'
import { ShortTermPage } from './ShortTermPage'

vi.mock('../api/client', () => ({
  fetchLatestShortTermScheduledSnapshot: vi.fn(),
  fetchOvernightBacktest: vi.fn(),
  fetchShortTermScanJob: vi.fn(),
  startShortTermScanJob: vi.fn()
}))

const emptyReport = {
  scope: '短线右侧',
  universeCount: 5500,
  reviewedCount: 60,
  klineReviewedCount: 60,
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
    limit: 3,
    scanLimit: 6000,
    klineLimit: 60,
    minAmount: 80000000,
    maxPe: 100,
    maxPb: 15,
    minVolumeRatio: 1.15,
    maxEntryRisePercent: 4,
    maxDistanceToMa20Percent: 8,
    minFinancialScore: 58
  },
  weightProfile: {
    preliminaryValuation: 0.2,
    preliminaryLiquidity: 0.3,
    preliminaryNonChase: 0.3,
    preliminaryHeat: 0.2,
    finalTechnical: 0.4,
    finalVolume: 0.2,
    finalHeat: 0.15,
    finalFinancial: 0.2,
    finalValuation: 0.05
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
  strategyVersion: 'short-term-right-side-v2',
  message: '尾盘最终结果已就绪',
  dataCutoffAt: '2026-07-23T14:52:00+08:00',
  completedAt: '2026-07-23T14:53:00+08:00',
  blockedReasons: [],
  report: emptyReport
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
    vi.mocked(fetchOvernightBacktest).mockResolvedValue({
      scope: 'OVERNIGHT',
      methodology: [],
      ruleSet: {} as never,
      symbols: [],
      summary: {} as never,
      trades: [],
      generatedAt: '2026-07-23T14:53:00+08:00'
    })
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
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

  it('requests the T1/T2 overnight contract without a legacy 20-day holding window', async () => {
    vi.mocked(fetchOvernightBacktest).mockResolvedValue({
      scope: '短线隔夜 T+1/T+2 验证',
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
        limitMovePercent: 9.8
      },
      symbols: ['600795'],
      summary: {
        symbolCount: 1,
        sampleCount: 12,
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
        conclusion: '隔夜正收益但波动需复核'
      },
      trades: [],
      generatedAt: '2026-07-23T14:53:00+08:00'
    })
    const candidate = {
      rank: 1,
      symbol: '600795',
      name: '国电电力',
      market: '沪市',
      industry: '电力',
      latestPrice: 5.21,
      changePercent: 1.2,
      amount: 900_000_000,
      phaseLabel: '右侧早期',
      action: 'WATCH_RIGHT_SIDE',
      actionLabel: '观察',
      reason: '量价结构确认',
      todayAdvice: { action: 'NEXT_WATCH', actionLabel: '次日关注' },
      tailSignal: { status: 'CONFIRMED', statusLabel: '尾盘确认', score: 75 },
      score: {
        technicalScore: 80,
        volumeScore: 75,
        marketHeatScore: 70,
        valuationScore: 60,
        financialScore: 65,
        riskPenalty: 5,
        finalScore: 74
      },
      technical: {
        goldenCross: null,
        rightSideSignal: 'EARLY_CONFIRMED',
        ma20SlopePercent: 0.8,
        distanceToMa20Percent: 2.1,
        breakoutFromPreviousHigh20Percent: 1.3,
        volumeRatio20: 1.5
      },
      financial: { qualityScore: 65 },
      quoteFreshness: { blocksRealtimeDecision: false, statusLabel: '新鲜' }
    }
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
      ...finalReadySnapshot,
      report: {
        ...emptyReport,
        candidateCount: 1,
        candidates: [candidate]
      } as never
    })

    await renderPage(root)

    expect(fetchOvernightBacktest).toHaveBeenCalledWith(expect.objectContaining({
      symbols: '600795',
      lookbackDays: 900,
      firstTargetPercent: 2.5,
      secondTargetPercent: 4.5,
      hardStopPercent: 3.5,
      maxHoldingTradingDays: 2
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
  })

  it.each(['重新扫描', '应用阈值'])(
    'starts the existing scan flow only after the explicit %s action',
    async (actionLabel) => {
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v2',
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
      strategyVersion: 'short-term-right-side-v2',
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
    expect(fetchShortTermScanJob).toHaveBeenCalledWith('manual-1')
    expect(fetchLatestShortTermScheduledSnapshot).toHaveBeenCalledTimes(1)
    }
  )

  it('keeps the manual result when the older scheduled request resolves later', async () => {
    const prepared = deferred<ShortTermScheduledSnapshot>()
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockReturnValue(prepared.promise)
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-2',
      status: 'RUNNING',
      tradeDate: '2026-07-23',
      resultStatus: 'RUNNING',
      strategyVersion: 'short-term-right-side-v2',
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
      strategyVersion: 'short-term-right-side-v2',
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
})

describe('ScheduledSnapshotStatus', () => {
  const expectations: Array<[ShortTermSnapshotStatus, string, string]> = [
    ['FINAL_READY', '尾盘最终结果已就绪', 'emerald'],
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
    expect(html).toContain('short-term-right-side-v2')
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

    expect(html).toContain('等待 0 30 14 * * MON-FRI 自动预选')
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

async function flushPromises() {
  await new Promise((resolve) => window.setTimeout(resolve, 0))
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
