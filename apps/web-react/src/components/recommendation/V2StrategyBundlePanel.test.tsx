// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchV2StrategyBundle } from '../../api/client'
import { V2StrategyBundlePanel } from './V2StrategyBundlePanel'

vi.mock('../../api/client', () => ({
  fetchV2StrategyBundle: vi.fn()
}))

const mockedFetchV2StrategyBundle = vi.mocked(fetchV2StrategyBundle)

describe('V2StrategyBundlePanel execution gate', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    mockedFetchV2StrategyBundle.mockResolvedValue(bundle as never)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.clearAllMocks()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('suppresses raw add labels when the canonical recommendation is evidence review', async () => {
    await act(async () => {
      root.render(
        <V2StrategyBundlePanel
          symbol="300979"
          companyName="华利集团"
          focus="long"
          canonicalAdvice={{
            action: 'WAIT',
            actionLabel: '证据待补',
            summary: '公告反证或行业证据仍有缺口。'
          }}
        />
      )
      await Promise.resolve()
      await Promise.resolve()
    })

    const text = host.textContent ?? ''
    expect(text).toContain('最终动作闸门')
    expect(text).toContain('证据待补')
    expect(text).toContain('内部策略信号已被压制')
    expect(text).not.toContain('加仓')
  })
})

const signal = {
  ledgerId: 'signal-1',
  strategyCode: 'VALUE_REVERSION',
  strategyVersion: 'v2',
  symbol: '300979',
  companyName: '华利集团',
  decisionAt: '2026-07-29T07:00:00Z',
  dataCutoffAt: '2026-07-29T07:00:00Z',
  candidateStage: 'ELIGIBLE',
  action: 'ADD',
  positionLimit: 0.1,
  entryCondition: '分批执行',
  invalidCondition: '逻辑证伪',
  rankScore: 90,
  dataConfidence: 90,
  historicalHitRate: null,
  riskReward: null,
  evidenceSummary: ['估值与质量支持'],
  blockedReasons: [],
  context: {},
  sourceQuality: 'COMPLETE',
  signalProvenance: 'RULE',
  replayPayload: {}
}

const bundle = {
  symbol: '300979',
  companyName: '华利集团',
  generatedAt: '2026-07-29T07:00:00Z',
  longTermSignals: [
    signal,
    { ...signal, ledgerId: 'signal-2', strategyCode: 'QUALITY_COMPOUNDER' },
    { ...signal, ledgerId: 'signal-3', strategyCode: 'CYCLE_REVERSAL' }
  ],
  shortRightSideSignal: { ...signal, ledgerId: 'signal-4', strategyCode: 'SHORT_RIGHT_SIDE' },
  agentEvidenceReview: {
    findings: [],
    supportCount: 0,
    opposeCount: 0,
    abstainCount: 0,
    sourceOverlapCount: 0,
    hasConflict: false,
    warnings: []
  }
}
