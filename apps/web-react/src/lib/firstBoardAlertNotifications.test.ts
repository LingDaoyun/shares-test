// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import type { FirstBoardAlertCandidate, FirstBoardAlertSnapshot } from '../types'
import { collectNewFirstBoardNotifications } from './firstBoardAlertNotifications'

describe('firstBoardAlertNotifications', () => {
  beforeEach(() => window.localStorage.clear())

  it('notifies a strong level once and allows a later level upgrade', () => {
    const strong = snapshot(candidate('STRONG'))
    expect(collectNewFirstBoardNotifications(strong)).toHaveLength(1)
    expect(collectNewFirstBoardNotifications(strong)).toHaveLength(0)
    expect(collectNewFirstBoardNotifications(snapshot(candidate('ACCELERATING')))).toHaveLength(1)
  })

  it('does not notify watch or non-ready snapshots', () => {
    expect(collectNewFirstBoardNotifications(snapshot(candidate('WATCH')))).toHaveLength(0)
    expect(collectNewFirstBoardNotifications({ ...snapshot(candidate('STRONG')), status: 'FAILED' })).toHaveLength(0)
  })

  it('does not notify a downgrade after an accelerating level was already shown', () => {
    expect(collectNewFirstBoardNotifications(snapshot(candidate('ACCELERATING')))).toHaveLength(1)
    expect(collectNewFirstBoardNotifications(snapshot(candidate('STRONG')))).toHaveLength(0)
  })
})

function candidate(level: FirstBoardAlertCandidate['level']): FirstBoardAlertCandidate {
  return {
    symbol: '600000', name: '浦发银行', industry: '银行', board: 'MAIN', lane: 'LATENT', level,
    latestPrice: 10, changePercent: 3, ma10: 9.8, ma20: 9.5, ma20Slope: 1,
    rsi14: 60, macdHistogram: 0.1, technicalPassCount: 5, realtimeVolumeRatio: 1.5,
    previousDayVolumeRatio: 0.9, recentFiveMaxVolumeRatio: 1.6, upperShadowRatio: 1,
    intradayDrawdown: 0.5, threeDayCloseRange: 2
  }
}

function snapshot(item: FirstBoardAlertCandidate): FirstBoardAlertSnapshot {
  return {
    status: 'READY', tradeDate: '2026-08-28', baselineDate: '2026-08-27',
    generatedAt: '2026-08-28T02:00:00Z', quoteFetchedAt: '2026-08-28T02:00:00Z',
    autoRefreshAllowed: true, ruleVersion: 'first-board-alert-v1',
    phase: 'READY', progressCompleted: 1, progressTotal: 1, progressFailed: 0,
    baselinePublishedAt: '2026-08-28T01:20:00Z', nextRetryAt: null,
    coverage: {
      universeCount: 1, supportedCount: 1, baselineCount: 1, scopedBaselineCount: 1,
      quotedCount: 1, eligibleCount: 1, excludedNameCount: 0, insufficientHistoryCount: 0,
      excludedMissingQuoteCount: 0, excludedTouchedLimitCount: 0, excludedVolumeRatioCount: 0
    },
    latentCandidates: [item], previousLimitUpCandidates: [], dataGaps: []
  }
}
