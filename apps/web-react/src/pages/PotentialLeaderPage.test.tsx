// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchPotentialLeaderBars,
  fetchPotentialLeaderSnapshot,
  requestPotentialLeaderAiReview
} from '../api/client'
import { collectPotentialLeaderNotifications } from '../lib/potentialLeaderNotifications'
import type { LeaderAiReview, PotentialLeaderCandidate, PotentialLeaderSnapshot } from '../types'
import { LegacyFirstBoardRedirect, PotentialLeaderPage } from './PotentialLeaderPage'

vi.mock('../api/client', () => ({
  fetchPotentialLeaderSnapshot: vi.fn(),
  fetchPotentialLeaderBars: vi.fn(),
  requestPotentialLeaderAiReview: vi.fn()
}))

vi.mock('../lib/potentialLeaderNotifications', () => ({
  collectPotentialLeaderNotifications: vi.fn(() => [])
}))

const mockedFetchSnapshot = vi.mocked(fetchPotentialLeaderSnapshot)
const mockedFetchBars = vi.mocked(fetchPotentialLeaderBars)
const mockedAiReview = vi.mocked(requestPotentialLeaderAiReview)
const mockedNotifications = vi.mocked(collectPotentialLeaderNotifications)

describe('PotentialLeaderPage', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    window.localStorage.clear()
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    mockedFetchBars.mockResolvedValue({
      symbol: '600001',
      tradeDate: '2026-09-03',
      baselineDate: '2026-09-02',
      adjustMode: 'QFQ',
      bars: [{ tradeDate: '2026-09-02', open: 10, close: 10.5, high: 10.8, low: 9.9, volume: 1_000_000 }]
    })
    mockedAiReview.mockResolvedValue(aiReview())
  })

  afterEach(() => {
    vi.useRealTimers()
    act(() => root.unmount())
    host.remove()
    document.querySelectorAll('[role="dialog"]').forEach((dialog) => dialog.parentElement?.remove())
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  it('loads the main board by default and renders both formal ranking lanes', async () => {
    mockedFetchSnapshot.mockResolvedValue(readySnapshot())

    await renderPage(root)

    expect(mockedFetchSnapshot).toHaveBeenCalledWith({ includeGrowth: false, includeStar: false })
    const text = host.textContent ?? ''
    expect(text).toContain('潜龙')
    expect(text).toContain('竞龙')
    expect(text).toContain('最强方向')
    expect(text).toContain('正式分')
    expect(text).toContain('龙虎榜调整')
  })

  it('uses distinct score-threshold empty states for both ready lanes', async () => {
    mockedFetchSnapshot.mockResolvedValue({
      ...readySnapshot(),
      potentialCandidates: [],
      competitionCandidates: []
    })

    await renderPage(root)

    expect(host.textContent).toContain('潜龙暂无达到 55 分的候选')
    expect(host.textContent).toContain('竞龙暂无达到 55 分的候选')
  })

  it('keeps 60-second polling while data is preparing outside trading hours', async () => {
    vi.useFakeTimers()
    mockedFetchSnapshot.mockResolvedValue({
      ...readySnapshot(),
      status: 'PREPARING',
      autoRefreshAllowed: false,
      potentialCandidates: [],
      competitionCandidates: []
    })

    await renderPage(root)
    expect(mockedFetchSnapshot).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })

    expect(mockedFetchSnapshot).toHaveBeenCalledTimes(2)
  })

  it('shows the stale quote timestamp without triggering candidate notifications', async () => {
    mockedFetchSnapshot.mockResolvedValue({ ...readySnapshot(), status: 'STALE' })

    await renderPage(root)

    expect(host.textContent).toContain('行情 2026-09-03 10:00:00')
    expect(host.textContent).toContain('数据已陈旧')
    expect(mockedNotifications).not.toHaveBeenCalled()
  })

  it('loads bars and a shadow AI verdict without reordering the formal table', async () => {
    const first = candidate('600001', '潜龙一号', 'COMPETITION', 1, 86)
    const second = candidate('600002', '潜龙二号', 'COMPETITION', 2, 72)
    mockedFetchSnapshot.mockResolvedValue({
      ...readySnapshot(),
      potentialCandidates: [],
      competitionCandidates: [first, second]
    })

    await renderPage(root)
    const before = [...host.querySelectorAll('tbody tr')].map((row) => row.textContent)
    const firstRow = [...host.querySelectorAll('tbody tr')].find((row) => row.textContent?.includes('潜龙一号'))

    await act(async () => {
      firstRow?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedFetchBars).toHaveBeenCalledWith('600001')
    const aiButton = [...document.querySelectorAll('button')].find((button) => button.textContent?.includes('AI 复核'))
    await act(async () => {
      aiButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedAiReview).toHaveBeenCalledWith(first)
    expect(document.body.textContent).toContain('支持正式结论')
    expect(document.body.textContent).toContain('影子复核，不影响正式排名')
    expect([...host.querySelectorAll('tbody tr')].map((row) => row.textContent)).toEqual(before)
  })

  it('redirects the retired first-board route to the potential-leader route', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/first-board-alert']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <Routes>
            <Route path="first-board-alert" element={<LegacyFirstBoardRedirect />} />
            <Route path="potential-leader" element={<CurrentPath />} />
          </Routes>
        </MemoryRouter>
      )
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(host.textContent).toBe('/potential-leader')
  })
})

async function renderPage(root: Root) {
  await act(async () => {
    root.render(<PotentialLeaderPage />)
    await Promise.resolve()
    await Promise.resolve()
  })
}

function CurrentPath() {
  return <>{useLocation().pathname}</>
}

function readySnapshot(): PotentialLeaderSnapshot {
  return {
    status: 'READY',
    tradeDate: '2026-09-03',
    quoteTradeDate: '2026-09-03',
    quoteTimestamp: '2026-09-03T02:00:00Z',
    dragonTigerDate: '2026-09-02',
    generatedAt: '2026-09-03T02:00:01Z',
    autoRefreshAllowed: false,
    ruleVersion: 'potential-leader-v1',
    boardOptions: { includeGrowth: false, includeStar: false },
    marketSummary: {
      limitUpCount: 45,
      brokenCount: 8,
      maxBoard: 4,
      sealRatePercent: 84.91,
      boards2PlusRatioPercent: 20,
      sentimentTone: '强势'
    },
    directions: [{
      industry: '机器人',
      rank: 1,
      score: 82,
      breadthPercent: 68,
      medianChangePercent: 3.2,
      limitUpCount: 6,
      brokenCount: 1,
      maxBoard: 3,
      amount: 12_000_000_000,
      constituentSymbols: ['600001', '600002']
    }],
    potentialCandidates: [candidate('600003', '潜龙一号', 'POTENTIAL', 1, 81)],
    competitionCandidates: [candidate('600001', '竞龙一号', 'COMPETITION', 1, 86)],
    coverage: {
      universeCount: 5200,
      baselineCount: 4900,
      scopedCount: 4100,
      liquidCount: 3200,
      strongDirectionCount: 1,
      potentialEligibleCount: 0,
      competitionEligibleCount: 1,
      hiddenBelowScoreCount: 4,
      excludedMissingDataCount: 18
    },
    dataGaps: []
  }
}

function candidate(
  symbol: string,
  name: string,
  lane: PotentialLeaderCandidate['lane'],
  rank: number,
  score: number
): PotentialLeaderCandidate {
  return {
    lane,
    rank,
    grade: score >= 80 ? 'A' : score >= 65 ? 'B' : 'C',
    score,
    symbol,
    name,
    industry: '机器人',
    board: 'MAIN',
    latestPrice: 12.34,
    changePercent: 9.96,
    realtimeVolumeRatio: 2.1,
    turnoverRate: 12.3,
    amount: 1_200_000_000,
    circulatingMarketValue: 9_500_000_000,
    ma10: 11.4,
    ma20: 10.8,
    ma20Slope: 1.6,
    breakoutPositionPercent: 100,
    currentlySealed: lane === 'COMPETITION',
    consecutiveBoards: lane === 'COMPETITION' ? 2 : 0,
    firstSealTime: lane === 'COMPETITION' ? '09:38:00' : null,
    lastSealTime: lane === 'COMPETITION' ? '10:12:00' : null,
    sealBreakCount: lane === 'COMPETITION' ? 1 : 0,
    sealFunds: lane === 'COMPETITION' ? 95_000_000 : null,
    scoreBreakdown: [{ code: 'DIRECTION', label: '主线共振', rawValue: 82, points: 16.4, maximumPoints: 20 }],
    riskDeductions: [{ code: 'BROKEN', label: '炸板风险', rawValue: 1, threshold: 2, points: 0 }],
    evidence: ['所属机器人方向排名第 1'],
    dragonTiger: {
      status: 'DISCLOSED',
      disclosureDate: '2026-09-02',
      adjustment: 3.5,
      seats: [{
        side: 'BUY',
        branchCode: '10001',
        branchName: '某证券营业部',
        buyAmount: 58_000_000,
        sellAmount: 0,
        netAmount: 58_000_000,
        institution: false,
        stockConnect: false
      }],
      dataGaps: []
    },
    calibration: {
      status: 'COLLECTING',
      sampleSize: 12,
      successRate: null,
      lower95: null,
      upper95: null
    },
    snapshotHash: `hash-${symbol}`
  }
}

function aiReview(): LeaderAiReview {
  return {
    id: '3f7a8e4e-c04e-4be0-88c0-37eb6ea33a21',
    tradeDate: '2026-09-03',
    lane: 'COMPETITION',
    symbol: '600001',
    ruleVersion: 'potential-leader-v1',
    snapshotHash: 'hash-600001',
    status: 'SUCCEEDED',
    verdict: 'SUPPORT',
    phase: 'COMPETING',
    advantages: ['主线排名和板位形成共振'],
    counterEvidence: ['有一次炸板记录'],
    dataGaps: [],
    mustVerify: ['观察次日承接'],
    confidence: 0.78,
    provider: 'deepseek',
    model: 'deepseek-chat',
    promptVersion: 'potential-leader-ai-v1',
    errorSummary: null,
    createdAt: '2026-09-03T02:02:00Z'
  }
}
