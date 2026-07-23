import { describe, expect, it } from 'vitest'
import {
  goldenCrossCounterEvidence,
  goldenCrossCounterEvidenceTone,
  goldenCrossDisplayLabel,
  goldenCrossLabel,
  goldenCrossSpreadLabel,
  goldenCrossTone,
  goldenCrossV2Context
} from './shortTermGoldenCross'
import type { ShortTermGoldenCrossSnapshot, ShortTermGoldenCrossState } from '../types'

const snapshot = (state: ShortTermGoldenCrossState): ShortTermGoldenCrossSnapshot => ({
  ruleVersion: 'short-golden-cross-v1.0.0',
  state,
  stateLabel: goldenCrossLabel(state),
  crossDate: state === 'CONFIRMED' ? '2026-07-22' : null,
  tradingDaysSinceCross: state === 'CONFIRMED' ? 0 : null,
  ma5Ma10SpreadPercent: state === 'APPROACHING' ? -0.35 : state === 'UNAVAILABLE' ? null : 0.22,
  spreadTrend: state === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'NARROWING',
  maAlignment: state === 'APPROACHING' ? 'CONVERGING' : state === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'BULLISH_STACK',
  priorityTier: state === 'CONFIRMED' ? 3 : state === 'APPROACHING' ? 2 : 0,
  evidenceStatus: state === 'UNAVAILABLE' ? 'UNAVAILABLE' : 'COMPLETE'
})

describe('short-term golden-cross view model', () => {
  it('labels every golden-cross state explicitly', () => {
    expect(goldenCrossLabel('NONE')).toBe('未形成金叉')
    expect(goldenCrossLabel('APPROACHING')).toBe('临界交汇')
    expect(goldenCrossLabel('FORMING')).toBe('金叉形成中')
    expect(goldenCrossLabel('CONFIRMED')).toBe('金叉已确认')
    expect(goldenCrossLabel('ESTABLISHED')).toBe('金叉已形成')
    expect(goldenCrossLabel('UNAVAILABLE')).toBe('金叉数据不足')
  })

  it('prefers the backend state label and keeps a legacy fallback', () => {
    expect(goldenCrossDisplayLabel({ ...snapshot('ESTABLISHED'), stateLabel: '多头延续' })).toBe('多头延续')
    expect(goldenCrossDisplayLabel({ ...snapshot('ESTABLISHED'), stateLabel: '' })).toBe('金叉已形成')
    expect(goldenCrossDisplayLabel(undefined)).toBe('金叉数据不足')
  })

  it('uses success only for confirmed and warning only for forming', () => {
    expect(goldenCrossTone('CONFIRMED')).toBe('success')
    expect(goldenCrossTone('FORMING')).toBe('warning')
    for (const state of ['APPROACHING', 'ESTABLISHED', 'NONE', 'UNAVAILABLE'] as const) {
      expect(goldenCrossTone(state)).toBe('neutral')
      expect(goldenCrossCounterEvidenceTone(snapshot(state))).toBe('neutral')
    }
    expect(goldenCrossTone(undefined)).toBe('neutral')
    expect(goldenCrossCounterEvidenceTone(snapshot('FORMING'))).toBe('warning')
    expect(goldenCrossCounterEvidenceTone(undefined)).toBe('neutral')
  })

  it('explains counter-evidence for non-confirmed states without inventing unavailable values', () => {
    expect(goldenCrossCounterEvidence(snapshot('APPROACHING'))).toContain('尚未完成上穿')
    expect(goldenCrossCounterEvidence(snapshot('FORMING'))).toContain('当前K线尚未收盘')
    expect(goldenCrossCounterEvidence(snapshot('UNAVAILABLE'))).toContain('数据不足')
    expect(goldenCrossCounterEvidence(snapshot('UNAVAILABLE'))).not.toContain('0.00%')
    expect(goldenCrossCounterEvidence(snapshot('CONFIRMED'))).toBeNull()
  })

  it('keeps a missing nested snapshot explicit without fabricating numeric values', () => {
    expect(goldenCrossLabel(undefined)).toBe('金叉数据不足')
    expect(goldenCrossSpreadLabel(undefined)).toBe('数据不足')
    expect(goldenCrossSpreadLabel(undefined)).not.toContain('0.00%')
  })

  it('formats signed spread values for detail metrics', () => {
    expect(goldenCrossSpreadLabel({ ...snapshot('CONFIRMED'), ma5Ma10SpreadPercent: 0.22 })).toBe('+0.22%')
    expect(goldenCrossSpreadLabel({ ...snapshot('APPROACHING'), ma5Ma10SpreadPercent: -0.35 })).toBe('-0.35%')
  })

  it('forwards the nested state, tier, and nullable trading days to V2', () => {
    expect(goldenCrossV2Context(snapshot('CONFIRMED'))).toEqual({
      goldenCrossState: 'CONFIRMED',
      goldenCrossTradingDays: 0,
      goldenCrossPriorityTier: 3
    })
    expect(goldenCrossV2Context({ ...snapshot('FORMING'), priorityTier: 2 })).toEqual({
      goldenCrossState: 'FORMING',
      goldenCrossTradingDays: undefined,
      goldenCrossPriorityTier: 2
    })
  })
})
