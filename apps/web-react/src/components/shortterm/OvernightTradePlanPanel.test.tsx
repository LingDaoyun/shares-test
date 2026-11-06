import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermTradePlan } from '../../types'
import { isMorningOvernightPlan, OvernightTradePlanPanel } from './OvernightTradePlanPanel'

const plan: ShortTermTradePlan = {
  strategyLabel: '隔夜早盘退出',
  status: 'ACTIONABLE',
  blockedReasons: [],
  entryWindow: '14:45-14:49',
  validUntil: '2026-07-23T14:49:59+08:00',
  referenceEntryPrice: 10.2,
  entryLow: 10.1,
  entryHigh: 10.3,
  maxPositionRatio: 0.3333,
  maxT2PositionRatio: 0,
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
  normalExitTime: '10:00:00',
  absoluteExitDate: '2026-07-24',
  absoluteExitTime: '10:00:00',
  t2ExtensionConditions: [],
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
  it('rejects missing plans and null exit fields from incomplete cache payloads', () => {
    expect(isMorningOvernightPlan(null)).toBe(false)
    for (const field of ['normalExitDate', 'absoluteExitDate', 'normalExitTime', 'absoluteExitTime']) {
      expect(isMorningOvernightPlan({ ...plan, [field]: null } as unknown as ShortTermTradePlan)).toBe(false)
    }
    expect(isMorningOvernightPlan(plan)).toBe(true)
  })

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
    expect(html).toContain('次日早盘退出截止')
    expect(html).toContain('10:00')
    expect(html).not.toContain('T+2 最晚退出')
    expect(html).not.toContain('T+2 仓位上限')
    expect(html).not.toContain('延长条件')
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

  it('makes the actionable plan cost and risk assumptions available in a collapsed explanation', () => {
    const html = renderToStaticMarkup(
      <OvernightTradePlanPanel plan={{ ...plan, analysisBasis: [
        '按入场区间上沿 10.30 元作为保守成交价计算',
        '往返费用与滑点合计按成交价的 0.20% 作研究假设',
        '净风险收益比 1.3000，研究门槛 1.20'
      ] }} />
    )

    expect(html).toContain('按入场区间上沿 10.30 元作为保守成交价计算')
    expect(html).toContain('往返费用与滑点合计按成交价的 0.20% 作研究假设')
    expect(html).toContain('净风险收益比 1.3000，研究门槛 1.20')
    const explanation = html.match(/<details\b[^>]*>/)?.[0]
    expect(explanation).toBeDefined()
    expect(explanation).not.toContain('open=')
  })

  it.each([
    { normalExitTime: '14:50:00', absoluteExitTime: '14:50:00' },
    { absoluteExitDate: '2026-07-27' },
    { maxT2PositionRatio: 0.5 },
    { t2ExtensionConditions: ['T+1 趋势延续'] },
    { normalExitTime: '09:29:00', absoluteExitTime: '09:29:00' },
    { normalExitTime: '11:30:01', absoluteExitTime: '11:30:01' },
    { normalExitTime: '', absoluteExitTime: '' }
  ])('asks for a fresh scan instead of showing an obsolete cached plan: %j', (legacy) => {
    const html = renderToStaticMarkup(<OvernightTradePlanPanel plan={{ ...plan, ...legacy }} />)
    expect(html).toContain('请重新扫描')
    expect(html).toContain('旧缓存计划')
    expect(html).not.toContain('隔夜交易纪律：可执行')
    expect(html).not.toContain('精确入场区间')
  })
})
