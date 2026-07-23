import { formatSignedPercent } from './format'
import type { ShortTermGoldenCrossSnapshot, ShortTermGoldenCrossState, V2StrategyBundleParams } from '../types'

export type GoldenCrossTone = 'success' | 'warning' | 'neutral'

export function goldenCrossLabel(state: ShortTermGoldenCrossState | null | undefined) {
  switch (state) {
    case 'NONE':
      return '未形成金叉'
    case 'APPROACHING':
      return '临界交汇'
    case 'FORMING':
      return '金叉形成中'
    case 'CONFIRMED':
      return '金叉已确认'
    case 'ESTABLISHED':
      return '金叉已形成'
    case 'UNAVAILABLE':
    case null:
    case undefined:
      return '金叉数据不足'
  }
}

export function goldenCrossDisplayLabel(snapshot: ShortTermGoldenCrossSnapshot | null | undefined) {
  const backendLabel = snapshot?.stateLabel?.trim()
  return backendLabel || goldenCrossLabel(snapshot?.state)
}

export function goldenCrossTone(state: ShortTermGoldenCrossState | null | undefined): GoldenCrossTone {
  switch (state) {
    case 'CONFIRMED':
      return 'success'
    case 'FORMING':
      return 'warning'
    case 'APPROACHING':
    case 'ESTABLISHED':
    case 'NONE':
    case 'UNAVAILABLE':
    case null:
    case undefined:
      return 'neutral'
  }
}

export function goldenCrossSpreadLabel(snapshot: ShortTermGoldenCrossSnapshot | null | undefined) {
  if (snapshot?.ma5Ma10SpreadPercent === null || snapshot?.ma5Ma10SpreadPercent === undefined) {
    return '数据不足'
  }
  return formatSignedPercent(snapshot.ma5Ma10SpreadPercent)
}

export function goldenCrossSpreadTrendLabel(trend: ShortTermGoldenCrossSnapshot['spreadTrend'] | null | undefined) {
  switch (trend) {
    case 'NARROWING':
      return '收敛'
    case 'WIDENING':
      return '扩大'
    case 'FLAT':
      return '平稳'
    case 'UNAVAILABLE':
    case null:
    case undefined:
      return '数据不足'
  }
}

export function goldenCrossAlignmentLabel(alignment: ShortTermGoldenCrossSnapshot['maAlignment'] | null | undefined) {
  switch (alignment) {
    case 'BEARISH':
      return 'MA5低于MA10'
    case 'CONVERGING':
      return '均线收敛'
    case 'MA5_ABOVE_MA10':
      return 'MA5高于MA10'
    case 'BULLISH_STACK':
      return '多头排列'
    case 'UNAVAILABLE':
    case null:
    case undefined:
      return '数据不足'
  }
}

export function goldenCrossCounterEvidence(snapshot: ShortTermGoldenCrossSnapshot | null | undefined) {
  switch (snapshot?.state) {
    case 'CONFIRMED':
      return null
    case 'NONE':
      return 'MA5 尚未上穿 MA10，当前不构成金叉。'
    case 'APPROACHING':
      return 'MA5 接近 MA10，但尚未完成上穿，仅作观察。'
    case 'FORMING':
      return '当前K线尚未收盘，交叉仍在形成，不能作为执行信号。'
    case 'ESTABLISHED':
      return '交叉已超过近期确认窗口，需结合新的右侧证据。'
    case 'UNAVAILABLE':
    case undefined:
      return '金叉数据不足，等待完整K线复核。'
  }
}

export function goldenCrossCounterEvidenceTone(snapshot: ShortTermGoldenCrossSnapshot | null | undefined): GoldenCrossTone {
  return snapshot?.state === 'FORMING' ? 'warning' : 'neutral'
}

export function goldenCrossV2Context(snapshot: ShortTermGoldenCrossSnapshot | null | undefined): Pick<
  V2StrategyBundleParams,
  'goldenCrossState' | 'goldenCrossTradingDays' | 'goldenCrossPriorityTier'
> {
  return {
    goldenCrossState: snapshot?.state,
    goldenCrossTradingDays: snapshot?.tradingDaysSinceCross ?? undefined,
    goldenCrossPriorityTier: snapshot?.priorityTier
  }
}
