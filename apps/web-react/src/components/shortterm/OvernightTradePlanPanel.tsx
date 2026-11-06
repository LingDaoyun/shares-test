import { Clock3, ShieldCheck } from 'lucide-react'
import { formatPerSharePrice, formatRatioPercent } from '../../lib/format'
import type { ShortTermTradePlan } from '../../types'

export function OvernightTradePlanPanel({ plan }: { plan: ShortTermTradePlan | null }) {
  if (!plan) {
    return (
      <section className="border border-amber-200 bg-amber-50/70 px-3 py-3 text-sm text-amber-800">
        <div className="font-semibold">隔夜交易纪律待生成</div>
        <p className="mt-1 text-xs leading-relaxed">当前候选没有可执行交易计划，不得据此推断用户持仓或强制入场。</p>
      </section>
    )
  }

  if (plan.status === 'BLOCKED') {
    return (
      <section className="border border-amber-200 bg-amber-50/70 px-3 py-3">
        <div className="flex items-center gap-2 text-amber-900">
          <ShieldCheck className="h-4 w-4" aria-hidden="true" />
          <h3 className="text-sm font-semibold">隔夜交易纪律：不可执行</h3>
        </div>
        <p className="mt-2 text-xs leading-relaxed text-amber-800">
          当前证据未通过执行闸门，本计划不可执行。
        </p>
        {plan.blockedReasons?.length ? (
          <div className="mt-3 border-t border-amber-200 pt-2 text-xs leading-relaxed text-ink-700">
            <div className="font-semibold text-amber-900">风险依据</div>
            <p className="mt-1">{plan.blockedReasons.join('；')}</p>
          </div>
        ) : null}
      </section>
    )
  }

  if (!isMorningOvernightPlan(plan)) {
    return (
      <section className="border border-amber-200 bg-amber-50/70 px-3 py-3 text-sm text-amber-800">
        <div className="font-semibold">隔夜退出规则已更新，请重新扫描</div>
        <p className="mt-1 text-xs leading-relaxed">旧缓存计划未采用下一交易日早盘退出纪律，请重新扫描后查看新的交易计划。</p>
      </section>
    )
  }

  return (
    <section className="border border-emerald-200 bg-emerald-50/50 px-3 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <ShieldCheck className="h-4 w-4 text-emerald-700" aria-hidden="true" />
          <h3 className="text-sm font-semibold text-emerald-900">隔夜交易纪律：可执行</h3>
          <span className="text-xs text-emerald-700">{plan.strategyLabel}</span>
        </div>
        <span className="inline-flex items-center gap-1 text-xs text-emerald-700">
          <Clock3 className="h-3.5 w-3.5" aria-hidden="true" />
          {plan.entryWindow}
        </span>
      </div>

      <dl className="mt-3 grid grid-cols-2 border-t border-emerald-200 text-xs md:grid-cols-4">
        <PlanMetric label="精确入场区间" value={`${price(plan.entryLow)} - ${price(plan.entryHigh)}`} />
        <PlanMetric label="参考入场价" value={price(plan.referenceEntryPrice)} />
        <PlanMetric label="短线额度最大仓位" value={formatRatioPercent(plan.maxPositionRatio)} />
        <PlanMetric label="第一目标价" value={target(plan.firstTargetPrice, plan.firstTargetPercent)} />
        <PlanMetric label="第二目标价" value={target(plan.secondTargetPrice, plan.secondTargetPercent)} />
        <PlanMetric label="硬止损价" value={target(plan.hardStopPrice, plan.hardStopPercent)} />
        <PlanMetric label="次日早盘退出截止" value={`${plan.normalExitDate} ${shortTime(plan.normalExitTime)}`} />
      </dl>

      <div className="mt-3 grid border-t border-emerald-200 md:grid-cols-3">
        {plan.openScenarios.map((scenario) => (
          <div key={scenario.code} className="border-b border-emerald-200 px-3 py-2 md:border-r">
            <div className="text-xs font-semibold text-ink-900">{scenario.label}</div>
            <p className="mt-1 text-xs leading-relaxed text-ink-500">{scenario.condition}</p>
            <p className="mt-1 text-xs font-medium text-emerald-800">{scenario.action}</p>
            {scenario.invalidationRules.length ? (
              <p className="mt-1 text-[11px] leading-relaxed text-amber-700">
                失效：{scenario.invalidationRules.join('；')}
              </p>
            ) : null}
          </div>
        ))}
      </div>

      <div className="mt-3 border-t border-emerald-200 pt-2 text-xs leading-relaxed text-ink-600">
        <p>{plan.trailingStopRule}</p>
        {plan.riskWarnings.length ? <p className="mt-1 text-amber-800">{plan.riskWarnings.join('；')}</p> : null}
      </div>
      {plan.analysisBasis?.length ? (
        <details className="mt-3 border-t border-emerald-200 pt-2 text-xs leading-relaxed text-ink-600">
          <summary className="cursor-pointer font-semibold text-emerald-900">计划测算依据</summary>
          <ul className="mt-2 list-disc space-y-1 pl-4">
            {plan.analysisBasis.map((basis, index) => <li key={`${index}-${basis}`}>{basis}</li>)}
          </ul>
        </details>
      ) : null}
    </section>
  )
}

export function isMorningOvernightPlan(plan: ShortTermTradePlan | null): boolean {
  if (!plan) return false
  const normalTime = morningSeconds(plan.normalExitTime)
  return typeof plan.normalExitDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(plan.normalExitDate)
    && plan.normalExitDate === plan.absoluteExitDate
    && normalTime !== null && normalTime === morningSeconds(plan.absoluteExitTime)
    && plan.maxT2PositionRatio === 0
    && Array.isArray(plan.t2ExtensionConditions) && plan.t2ExtensionConditions.length === 0
}

function morningSeconds(value: string) {
  if (typeof value !== 'string') return null
  const match = /^(\d{2}):(\d{2})(?::(\d{2}(?:\.\d+)?))?$/.exec(value)
  if (!match) return null
  const hours = Number(match[1]), minutes = Number(match[2]), seconds = Number(match[3] ?? 0)
  if (hours > 23 || minutes > 59 || seconds >= 60) return null
  const total = hours * 3600 + minutes * 60 + seconds
  return total >= 9.5 * 3600 && total <= 11.5 * 3600 ? total : null
}

function PlanMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-b border-r border-emerald-200 px-2 py-2">
      <dt className="text-[11px] text-ink-400">{label}</dt>
      <dd className="mt-0.5 break-words font-semibold text-ink-900">{value}</dd>
    </div>
  )
}

function price(value: number | null) {
  return value === null ? '待生成' : formatPerSharePrice(value)
}

function target(value: number | null, percent: number | null) {
  const percentLabel = percent === null ? '' : ` (${percent.toFixed(2)}%)`
  return `${price(value)}${percentLabel}`
}

function shortTime(value: string) {
  return value.slice(0, 5)
}
