import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermTradePlan } from '../../types'
import { OvernightTradePlanPanel } from './OvernightTradePlanPanel'

const plan: ShortTermTradePlan = {
  strategyLabel: '隔夜超短波段',
  status: 'ACTIONABLE',
  blockedReasons: [],
  entryWindow: '14:45-14:49',
  validUntil: '2026-07-23T14:49:59+08:00',
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
  it('renders executable details only for an actionable plan in one definition grid', () => {
    const html = renderToStaticMarkup(<OvernightTradePlanPanel plan={plan} />)

    expect(html).toContain('隔夜交易纪律')
    expect(html).toContain('可执行')
    expect(html).toContain('<dl')
    expect(html).toContain('14:45-14:49')
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

  it('renders a blocked plan as non-executable evidence without buy or position actions', () => {
    const html = renderToStaticMarkup(
      <OvernightTradePlanPanel
        plan={{
          ...plan,
          status: 'BLOCKED',
          blockedReasons: ['行情覆盖不足', '尾盘报价已过期'],
          analysisBasis: ['参考入场价 10.20 元', 'ATR14 波动率 3%'],
          riskWarnings: [
            '普通 A 股新买仓位遵循 T+1：买入当日无法卖出，盘中急跌也只能等次一交易日处理。',
            '最大仓位比例是相对于短线资金分配，不是总账户资产。',
            'T+2 只允许保留不超过计划仓位的 50.00%，并必须在 14:50 前退出。'
          ]
        }}
      />
    )

    expect(html).toContain('不可执行')
    expect(html).toContain('当前证据未通过执行闸门，本计划不可执行。')
    expect(html).toContain('行情覆盖不足')
    expect(html).toContain('尾盘报价已过期')
    expect(html).not.toContain('精确入场区间')
    expect(html).not.toContain('参考入场价')
    expect(html).not.toContain('短线额度最大仓位')
    expect(html).not.toContain('第一目标价')
    expect(html).not.toContain('第二目标价')
    expect(html).not.toContain('ATR14 波动率')
    for (const executionTerm of ['T+1', 'T+2', '新买仓位', '最大仓位', '计划仓位', '退出', '截止', '目标', '入场']) {
      expect(html).not.toContain(executionTerm)
    }
  })
})
