import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermTradePlan } from '../../types'
import { OvernightTradePlanPanel } from './OvernightTradePlanPanel'

const plan: ShortTermTradePlan = {
  strategyLabel: '隔夜超短波段',
  status: 'EXECUTABLE',
  entryWindow: '14:45-14:56',
  validUntil: '2026-07-23T14:56:59+08:00',
  referenceEntryPrice: 10.2,
  entryLow: 10.1,
  entryHigh: 10.3,
  maxPositionRatio: 0.3333,
  maxT2PositionRatio: 0.5,
  firstTargetPercent: 3,
  firstTargetPrice: 10.51,
  firstReductionRatio: 0.5,
  secondTargetPercent: 5,
  secondTargetPrice: 10.71,
  hardStopPercent: 3,
  hardStopPrice: 9.89,
  trailingDrawdownPercent: 2,
  trailingStopRule: '高点回撤 2% 触发保护',
  normalExitDate: '2026-07-24',
  normalExitTime: '14:50:00',
  absoluteExitDate: '2026-07-27',
  absoluteExitTime: '14:50:00',
  t2ExtensionConditions: ['T+1 趋势延续且未触发止损'],
  openScenarios: [
    {
      code: 'GAP_UP',
      label: '高开',
      condition: '高开 2% 以上',
      action: '分批止盈',
      invalidationRules: ['跌回参考价']
    },
    {
      code: 'FLAT',
      label: '平开',
      condition: '涨跌幅在 2% 内',
      action: '按计划观察',
      invalidationRules: ['跌破硬止损']
    },
    {
      code: 'GAP_DOWN',
      label: '低开',
      condition: '低开 2% 以上',
      action: '优先风控',
      invalidationRules: ['不得补仓摊薄']
    }
  ],
  analysisBasis: ['尾盘数据'],
  riskWarnings: ['推荐不等于持仓']
}

describe('OvernightTradePlanPanel', () => {
  it('renders exact prices, position limit, deadlines, and all open scenarios', () => {
    const html = renderToStaticMarkup(<OvernightTradePlanPanel plan={plan} />)

    expect(html).toContain('隔夜交易纪律')
    expect(html).toContain('14:45-14:56')
    expect(html).toContain('10.20')
    expect(html).toContain('10.10')
    expect(html).toContain('10.30')
    expect(html).toContain('10.51')
    expect(html).toContain('10.71')
    expect(html).toContain('9.89')
    expect(html).toContain('33.33%')
    expect(html).toContain('短线额度最大仓位')
    expect(html).toContain('2026-07-24')
    expect(html).toContain('2026-07-27')
    expect(html).toContain('高开')
    expect(html).toContain('平开')
    expect(html).toContain('低开')
    expect(html).toContain('推荐不等于持仓')
  })
})
