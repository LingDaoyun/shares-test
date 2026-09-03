import { describe, expect, it } from 'vitest'
import type { PotentialLeaderCandidate, PotentialLeaderSnapshot } from '../types'
import { collectPotentialLeaderNotifications } from './potentialLeaderNotifications'

describe('potentialLeaderNotifications', () => {
  it('notifies each visible A candidate once even after leaving and re-entering', () => {
    const storage = memoryStorage()

    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'A')), storage)).toHaveLength(1)
    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'A')), storage)).toHaveLength(0)
    collectPotentialLeaderNotifications(readyWith(candidate('600002', 'A')), storage)
    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'A')), storage)).toHaveLength(0)
  })

  it('notifies a B-to-A upgrade once', () => {
    const storage = memoryStorage()

    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'B')), storage)).toHaveLength(0)
    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'A')), storage)).toHaveLength(1)
    expect(collectPotentialLeaderNotifications(readyWith(candidate('600001', 'A')), storage)).toHaveLength(0)
  })

  it('keeps the same symbol independent across lanes', () => {
    const storage = memoryStorage()
    const potential = candidate('600001', 'A', 'POTENTIAL')
    const competition = candidate('600001', 'A', 'COMPETITION')

    expect(collectPotentialLeaderNotifications(readyWith(potential), storage)).toEqual([potential])
    expect(collectPotentialLeaderNotifications(readyWith(competition), storage)).toEqual([competition])
  })

  it('does not read or mutate state for non-ready snapshots', () => {
    const storage = memoryStorage()
    storage.setItem('ai-stock:potential-leader:notified:v1', 'preserve-me')

    expect(collectPotentialLeaderNotifications({ ...readyWith(candidate('600001', 'A')), status: 'STALE' }, storage)).toHaveLength(0)
    expect(storage.getItem('ai-stock:potential-leader:notified:v1')).toBe('preserve-me')
  })
})

function candidate(
  symbol: string,
  grade: PotentialLeaderCandidate['grade'],
  lane: PotentialLeaderCandidate['lane'] = 'POTENTIAL'
): PotentialLeaderCandidate {
  return {
    lane,
    rank: 1,
    grade,
    score: grade === 'A' ? 82 : 72,
    symbol,
    name: `候选${symbol}`,
    industry: '机器人',
    board: 'MAIN',
    latestPrice: 12.34,
    changePercent: 5.6,
    realtimeVolumeRatio: 1.8,
    turnoverRate: 8.2,
    amount: 800_000_000,
    circulatingMarketValue: 8_000_000_000,
    ma10: 11.8,
    ma20: 11.2,
    ma20Slope: 1.5,
    breakoutPositionPercent: 92,
    currentlySealed: false,
    consecutiveBoards: 0,
    firstSealTime: null,
    lastSealTime: null,
    sealBreakCount: 0,
    sealFunds: null,
    scoreBreakdown: [],
    riskDeductions: [],
    evidence: [],
    dragonTiger: {
      status: 'NOT_DISCLOSED',
      disclosureDate: null,
      adjustment: 0,
      seats: [],
      dataGaps: []
    },
    calibration: {
      status: 'COLLECTING',
      sampleSize: 0,
      successRate: null,
      lower95: null,
      upper95: null
    },
    snapshotHash: `${lane}-${symbol}-${grade}`
  }
}

function readyWith(...candidates: PotentialLeaderCandidate[]): PotentialLeaderSnapshot {
  return {
    status: 'READY',
    tradeDate: '2026-09-03',
    quoteTradeDate: '2026-09-03',
    quoteTimestamp: '2026-09-03T02:00:00Z',
    dragonTigerDate: '2026-09-02',
    generatedAt: '2026-09-03T02:00:01Z',
    autoRefreshAllowed: true,
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
    directions: [],
    potentialCandidates: candidates.filter((item) => item.lane === 'POTENTIAL'),
    competitionCandidates: candidates.filter((item) => item.lane === 'COMPETITION'),
    coverage: {
      universeCount: candidates.length,
      baselineCount: candidates.length,
      scopedCount: candidates.length,
      liquidCount: candidates.length,
      strongDirectionCount: 1,
      potentialEligibleCount: candidates.filter((item) => item.lane === 'POTENTIAL').length,
      competitionEligibleCount: candidates.filter((item) => item.lane === 'COMPETITION').length,
      hiddenBelowScoreCount: 0,
      excludedMissingDataCount: 0
    },
    dataGaps: []
  }
}

function memoryStorage(): Storage {
  const values = new Map<string, string>()
  return {
    get length() {
      return values.size
    },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => [...values.keys()][index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value)
  }
}
