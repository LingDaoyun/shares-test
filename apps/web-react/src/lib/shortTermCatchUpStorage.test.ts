import { describe, expect, it } from 'vitest'
import { CATCH_UP_OBSERVATION_LIMIT, CATCH_UP_STORAGE_KEY, catchUpSnapshotPair, emptyCatchUpLocalState, loadCatchUpLocalState, rememberCatchUpSnapshot, saveCatchUpLocalState } from './shortTermCatchUpStorage'
import type { ShortTermCatchUpSnapshot } from '../types/shortTermCatchUp'

function snapshot(overrides: Partial<ShortTermCatchUpSnapshot> = {}): ShortTermCatchUpSnapshot {
  return {
    tradeDate: '2026-10-15', industry: '半导体', leaderSymbol: '600001', boardCode: 'BK1001', boardName: '共同概念',
    fetchedAt: '2026-10-15T06:00:05Z', marketTimestamp: '2026-10-15T06:00:00Z', status: 'READY',
    leader: { symbol: '600001', name: '封板锚点', industry: '半导体', latestPrice: 11, changePercent: 10,
      amount: 1e8, turnoverRate: 2, consecutiveBoards: 1, statDays: 1, statBoards: 1, sealFunds: 2e7,
      firstSealTime: '09:35:00', lastSealTime: '09:35:00', sealBreakCount: 0 },
    relatedBoards: [{ code: 'BK1001', name: '共同概念' }], quoteSource: '供应商成分行情',
    expectedMemberCount: 10, fetchedMemberCount: 10, validQuoteCount: 10, historyValidCount: 8, historyCoveragePercent: 80,
    risingCount: 5, sealedCount: 1, eligibleCandidateCount: 1, displayedCount: 1, truncatedCount: 0,
    historyDates: ['2026-09-30', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'],
    nextTradingDate: '2026-10-16', evidence: ['供应商确认共同板块'], contraryEvidence: ['共同归属不证明事件受益'], dataGaps: [],
    candidates: [{ symbol: '600002', name: '中位样本', latestPrice: 10, changePercent: 2, amount: null, turnoverRate: null,
      marketTimestamp: '2026-10-15T06:00:00Z', return5: 3, returnPercentile: 50, returnGapToLeader: -7,
      middle: true, historyStatus: 'VALID', evidence: ['前五日收益中位'], contraryEvidence: ['涨幅落后不保证补涨'], dataGaps: ['换手未知'] }],
    ...overrides
  }
}

const observedAt = '2026-10-15T06:00:06Z'

describe('catch-up browser first observations', () => {
  it('freezes first price, source time and evidence across later refreshes of the same identity', () => {
    const first = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot(), observedAt)
    const later = snapshot({ marketTimestamp: '2026-10-15T06:01:00Z', evidence: ['后来的证据'] })
    later.candidates = later.candidates.map(row => ({ ...row, latestPrice: 12, marketTimestamp: later.marketTimestamp! }))
    const second = rememberCatchUpSnapshot(first, later, '2026-10-15T06:01:06Z')
    expect(second.observations).toHaveLength(1)
    expect(second.observations[0]).toEqual(first.observations[0])
    expect(second.observations[0].observedPrice).toBe(10)
    expect(second.observations[0].evidence).toContain('供应商确认共同板块')
    expect(second.observations[0].evidence).not.toContain('后来的证据')
  })

  it('separates the date, anchor, board and candidate identities', () => {
    let state = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot(), observedAt)
    state = rememberCatchUpSnapshot(state, snapshot({ tradeDate: '2026-10-16' }), observedAt)
    state = rememberCatchUpSnapshot(state, snapshot({ leaderSymbol: '600003' }), observedAt)
    state = rememberCatchUpSnapshot(state, snapshot({ boardCode: 'BK1002' }), observedAt)
    expect(new Set(state.observations.map(row => row.id)).size).toBe(4)
  })

  it('records only advancing source times and keeps the latest pair without converting unknown counts to zero', () => {
    let state = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot({ sealedCount: null }), observedAt)
    state = rememberCatchUpSnapshot(state, snapshot({ fetchedAt: '2026-10-15T06:01:00Z', risingCount: 9 }), observedAt)
    expect(state.snapshots).toHaveLength(1)
    expect(state.snapshots[0].risingCount).toBe(5)
    state = rememberCatchUpSnapshot(state, snapshot({ marketTimestamp: '2026-10-15T06:01:00Z', risingCount: 6, sealedCount: 0 }), observedAt)
    state = rememberCatchUpSnapshot(state, snapshot({ marketTimestamp: '2026-10-15T05:59:00Z', risingCount: 99 }), observedAt)
    const pair = catchUpSnapshotPair(state, '2026-10-15', '600001', 'BK1001')
    expect(pair).toHaveLength(2)
    expect(pair[0].sealedCount).toBeNull()
    expect(pair[1].sealedCount).toBe(0)
    expect(pair[1].risingCount).toBe(6)
  })

  it('keeps a valid price observation when history is insufficient, while preserving the history gap', () => {
    const data = snapshot({ status: 'DATA_GAP', historyValidCount: 3, historyCoveragePercent: 30, dataGaps: ['连续历史覆盖不足'] })
    data.candidates = data.candidates.map(row => ({ ...row, middle: false, historyStatus: 'UNKNOWN', return5: null, returnPercentile: null }))
    const state = rememberCatchUpSnapshot(emptyCatchUpLocalState(), data, observedAt)
    expect(state.observations[0].returnPercentile).toBeNull()
    expect(state.observations[0].dataGaps).toContain('连续历史覆盖不足')
  })

  it('does not save an unavailable universe or an observation without a valid source time', () => {
    expect(rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot({ status: 'UNAVAILABLE' }), observedAt).observations).toHaveLength(0)
    expect(rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot({ marketTimestamp: null }), observedAt).observations).toHaveLength(0)
    const invalid = snapshot()
    invalid.candidates = invalid.candidates.map(row => ({ ...row, latestPrice: 0 }))
    expect(rememberCatchUpSnapshot(emptyCatchUpLocalState(), invalid, observedAt).observations).toHaveLength(0)
  })

  it('bounds retained first observations and snapshot storage', () => {
    let state = emptyCatchUpLocalState()
    for (let i = 0; i < CATCH_UP_OBSERVATION_LIMIT + 5; i += 1) {
      state = rememberCatchUpSnapshot(state, snapshot({ boardCode: `BK${1000 + i}` }), observedAt)
    }
    expect(state.observations).toHaveLength(CATCH_UP_OBSERVATION_LIMIT)
    expect(state.snapshots.length).toBeLessThanOrEqual(60)
    const retained = state.observations[state.observations.length - 1]
    state = rememberCatchUpSnapshot(state, snapshot({ boardCode: retained.boardCode }), observedAt)
    expect(state.observations[state.observations.length - 1]).toEqual(retained)
  })

  it('round trips retained provenance and tolerates corrupt or unavailable browser storage', () => {
    const values = new Map<string, string>()
    const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } }
    const state = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot(), observedAt)
    expect(saveCatchUpLocalState(state, storage)).toBe(true)
    expect(loadCatchUpLocalState(storage)).toEqual(state)
    values.set(CATCH_UP_STORAGE_KEY, '{')
    expect(loadCatchUpLocalState(storage)).toEqual(emptyCatchUpLocalState())
    expect(loadCatchUpLocalState({ getItem: () => { throw new Error('blocked') }, setItem: () => undefined })).toEqual(emptyCatchUpLocalState())
    expect(saveCatchUpLocalState(state, { getItem: () => null, setItem: () => { throw new Error('quota') } })).toBe(false)
    expect(saveCatchUpLocalState(state, null)).toBe(false)
  })
})
