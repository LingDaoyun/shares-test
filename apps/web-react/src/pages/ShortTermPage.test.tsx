// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLatestShortTermScheduledSnapshot,
  fetchRightSideBacktest,
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import { ScheduledSnapshotStatus } from '../components/shortterm/ScheduledSnapshotStatus'
import type { ShortTermScheduledSnapshot, ShortTermSnapshotStatus } from '../types'
import { ShortTermPage } from './ShortTermPage'

vi.mock('../api/client', () => ({
  fetchLatestShortTermScheduledSnapshot: vi.fn(),
  fetchRightSideBacktest: vi.fn(),
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
    vi.mocked(fetchRightSideBacktest).mockResolvedValue({
      scope: 'RIGHT_SIDE',
      methodology: [],
      ruleSet: {} as never,
      symbols: [],
      summary: {} as never,
      results: [],
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
    expect(fetchRightSideBacktest).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('尾盘最终结果已就绪')
    expect(document.body.textContent).toContain('计划任务')
  })

  it.each(['重新扫描', '应用阈值'])(
    'starts the existing scan flow only after the explicit %s action',
    async (actionLabel) => {
    vi.mocked(startShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'RUNNING',
      createdAt: '2026-07-23T14:54:00+08:00',
      startedAt: null,
      finishedAt: null,
      message: '手动扫描中',
      report: null
    })
    vi.mocked(fetchShortTermScanJob).mockResolvedValue({
      jobId: 'manual-1',
      status: 'SUCCEEDED',
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
