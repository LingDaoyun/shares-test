// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchFirstBoardAlertSnapshot } from '../api/client'
import type { FirstBoardAlertSnapshot } from '../types'
import { FirstBoardAlertPage } from './FirstBoardAlertPage'

vi.mock('../api/client', () => ({
  fetchFirstBoardAlertSnapshot: vi.fn(),
  fetchFirstBoardBars: vi.fn()
}))

const mockedFetchSnapshot = vi.mocked(fetchFirstBoardAlertSnapshot)

describe('FirstBoardAlertPage', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    window.localStorage.clear()
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })

  afterEach(() => {
    vi.useRealTimers()
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('loads main board by default and shows two technical lanes without reason or score columns', async () => {
    mockedFetchSnapshot.mockResolvedValue(readySnapshot() as never)

    await act(async () => {
      root.render(<FirstBoardAlertPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedFetchSnapshot).toHaveBeenCalledWith({ includeGrowth: false, includeStar: false })
    const text = host.textContent ?? ''
    expect(text).toContain('首板潜伏')
    expect(text).toContain('昨日涨停观察')
    expect(text).toContain('MA10 / MA20')
    expect(text).toContain('实时 / 昨日 / 近5日')
    expect(text).not.toContain('触发原因')
    expect(text).not.toContain('综合评分')
  })

  it('keeps polling while a non-trading-day baseline is initializing', async () => {
    vi.useFakeTimers()
    mockedFetchSnapshot.mockResolvedValue({
      ...readySnapshot(),
      status: 'INITIALIZING',
      autoRefreshAllowed: false,
      latentCandidates: [],
      previousLimitUpCandidates: []
    } as never)

    await act(async () => {
      root.render(<FirstBoardAlertPage />)
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(mockedFetchSnapshot).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(mockedFetchSnapshot).toHaveBeenCalledTimes(2)
  })
})

function readySnapshot(): FirstBoardAlertSnapshot {
  return {
    status: 'READY', tradeDate: '2026-08-28', baselineDate: '2026-08-27',
    generatedAt: '2026-08-28T02:00:00Z', quoteFetchedAt: '2026-08-28T02:00:00Z',
    autoRefreshAllowed: false, ruleVersion: 'first-board-alert-v1',
    phase: 'READY', progressCompleted: 2, progressTotal: 3900, progressFailed: 0,
    baselinePublishedAt: '2026-08-28T01:20:00Z', nextRetryAt: null,
    coverage: {
      universeCount: 5000, supportedCount: 4800, baselineCount: 4700, scopedBaselineCount: 3900,
      quotedCount: 5000, eligibleCount: 2, excludedNameCount: 100, insufficientHistoryCount: 100,
      excludedMissingQuoteCount: 0, excludedTouchedLimitCount: 12, excludedVolumeRatioCount: 30
    },
    latentCandidates: [{
      symbol: '600000', name: '浦发银行', industry: '银行', board: 'MAIN', lane: 'LATENT', level: 'STRONG',
      latestPrice: 10.5, changePercent: 5, ma10: 9.8, ma20: 9.5, ma20Slope: 1.2,
      rsi14: 61, macdHistogram: 0.12, technicalPassCount: 5, realtimeVolumeRatio: 1.5,
      previousDayVolumeRatio: 0.9, recentFiveMaxVolumeRatio: 1.8, upperShadowRatio: 1.1,
      intradayDrawdown: 0.5, threeDayCloseRange: 2.1
    }],
    previousLimitUpCandidates: [], dataGaps: []
  }
}
