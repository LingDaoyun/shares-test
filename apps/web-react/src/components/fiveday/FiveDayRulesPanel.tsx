import type { ReactNode } from 'react'
import { formatAmount, formatNumber, formatPercent } from '../../lib/format'
import type { FiveDayRules, FiveDayStrategy } from '../../types/fiveDay'
import { Card } from '../ui/Card'

const INITIAL_RULES: FiveDayRules = {
  holdingDays: 5,
  rangeDays: 10,
  recentTurnoverDays: 5,
  priorTurnoverDays: 20,
  flowDays: 5,
  maxRangePercent: 8,
  maxAbsPriceChangePercent: 3,
  minCenterChangePercent: -1,
  minTurnoverRatio: 1.2,
  minPositiveFlowDays: 3,
  minAverageAmount: 50000000,
  maxEntryPremiumPercent: 2,
  maxInitialRiskPercent: 5,
  stopBufferPercent: 0.5,
  roundTripCostBps: 20,
  maximumReviewed: 100
}

export function FiveDayRulesPanel({ rules, ruleVersion, strategy = 'v1' }: { rules: FiveDayRules | null; ruleVersion?: string; strategy?: FiveDayStrategy }) {
  const values = rules ?? INITIAL_RULES
  return (
    <Card title="研究初值与执行口径">
      <p className="mb-4 text-xs leading-relaxed text-ink-500">
        {ruleVersion || (strategy === 'v2' ? 'five-day-context-v2' : 'five-day-observation-v1')} · {rules ? '本次快照参数' : '研究初始参数，实际以扫描快照为准'}。
        这些是待观察的初始阈值，尚未证明是最优参数或盈利策略。
      </p>
      {strategy === 'v2' ? <div className="mb-4 text-xs leading-6 text-ink-500">
        <p>市场至少 200 只、覆盖不少于 80%。上涨广度不足 35% 且收益中位数为负，或单日跌幅至少 7% 的样本占比达到 8%，为风险偏高；广度至少 50% 且中位数非负才是正常，其余谨慎观察。</p>
        <p>行业至少 5 只、完整 25 日覆盖不少于 80%；5 日收益为正、3 / 5 日超额均为正、5 日上涨占比至少 55%、放量成员占比至少 50%，且在数据合格行业的 5 日收益排名前 25%，才确认强势。</p>
        <p>市场正常、行业强势、个股转强、完整且合格的资金以及公告标题未发现风险线索，才列为执行条件齐备。消息来源未知不作通过；标题无风险词不表示无风险，政策线索不证明公司受益。入场前另做当日独立执行检查。</p>
      </div> : null}
      <div className="grid gap-x-6 gap-y-4 text-sm leading-6 md:grid-cols-2 xl:grid-cols-3">
        <Rule title="完整收盘数据">
          15:40 前使用上一交易日；需要连续 {values.recentTurnoverDays + values.priorTurnoverDays} 个市场交易日的完整日 K。
          {strategy === 'v2' ? `先筛强势行业，再按 ${values.rangeDays} 日形态预选；行业间轮询分配，行业内优先转强，再按 3 日收益、振幅与代码复核最多 ${values.maximumReviewed} 只。` : `按 ${values.rangeDays} 日价格形态预选，再按区间振幅、股票代码顺序复核最多 ${values.maximumReviewed} 只。`}
        </Rule>
        <Rule title="横盘与换手">
          {values.rangeDays} 日最高价 / 最低价 − 1 ≤ {formatPercent(values.maxRangePercent)}；首尾收盘变化绝对值 ≤ {formatPercent(values.maxAbsPriceChangePercent)}；
          后半段均价较前半段变化 ≥ {formatPercent(values.minCenterChangePercent)}。
          近 {values.recentTurnoverDays} 日平均换手 / 此前 {values.priorTurnoverDays} 日平均换手 ≥ {formatNumber(values.minTurnoverRatio)} 倍；近 {values.recentTurnoverDays} 日日均成交额 ≥ {formatAmount(values.minAverageAmount)}。
        </Rule>
        <Rule title="资金条件">
          连续 {values.flowDays} 日资金与成交额齐全，累计主力净额 &gt; 0，至少 {values.minPositiveFlowDays} 日为正。
          净额占比 = 累计主力净额 / 累计成交额，不平均每日占比。资金缺失单列，不纳入配对统计；不预设净流入越多，五日表现就越好。
        </Rule>
        <Rule title="入场参考">
          下一交易日开盘为情景入场基准。参考失效价 = 区间下沿 × (1 − {formatPercent(values.stopBufferPercent)})；
          入场上限取“信号收盘价 × (1 + {formatPercent(values.maxEntryPremiumPercent)})”与“失效价 / (1 − {formatPercent(values.maxInitialRiskPercent)})”中的较低值。
          高于上限、低于或等于失效价、开盘涨停附近均不假设成交，不顺延挑选入场日。
          {formatPercent(values.maxInitialRiskPercent)} 只是入场价与失效位的初始价差筛选上限，不是最大亏损保证；下一日开盘可能越过失效位。
        </Rule>
        <Rule title="买入日是第 1 日">
          第 {values.holdingDays} 个交易日收盘计划退出；风险情景为此前收盘跌破失效价后，下一个可卖出交易日开盘退出。
          这不是盘中止损；买入排除涨停附近，卖出排除跌停附近，零成交量均不确认成交。
          停牌、成交方向受限或缺数据可使结果未完成或延期。日历未核实则日期留空。
        </Rule>
        <Rule title="两种净收益都计成本">
          固定五日和风险退出独立计算，统一扣除 {values.roundTripCostBps} bps（{formatPercent(values.roundTripCostBps / 100)}）往返成本假设。
          费用和滑点均为研究假设；真实成交由用户录入账本，五日计划从实际首次买入日计算。
        </Rule>
      </div>
      <div className="mt-4 border-t border-line-soft pt-4 text-xs leading-6 text-ink-500">
        日 K 使用现有存储；腾讯日 K 缺成交额、换手时按东方财富同股同日补齐，并保持原始 OHLCV。
        资金使用东方财富 daykline 的“主力”大单分类，不代表所有主动买卖差，不能据此识别账户或断言主力吸筹。
        日线发生前复权重基时，历史边界与入场上限按信号日锚点同步缩放；具体原始数值及资金来源链接见候选详情。
      </div>
    </Card>
  )
}

function Rule({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="mb-1 font-semibold text-ink-900">{title}</h3>
      <p className="text-ink-600">{children}</p>
    </div>
  )
}
