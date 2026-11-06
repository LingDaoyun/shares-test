import { formatNumber, formatPerSharePrice } from '../../lib/format'
import type { ShortTermCandidate, ShortTermResearchSetup } from '../../types'
import { Tag } from '../ui/Badge'
import { isMorningOvernightPlan } from './OvernightTradePlanPanel'

export const researchSetupFilters = [
  { value: 'ALL', label: '全部形态' },
  { value: 'CONTRACTION_BREAKOUT', label: '收缩突破' },
  { value: 'TREND_PULLBACK', label: '分时回踩' },
  { value: 'SUPPORT_REVERSAL', label: '支撑反转' },
  { value: 'GOLDEN_CROSS_MOMENTUM', label: '金叉动量' }
] as const

export type ResearchSetupFilter = typeof researchSetupFilters[number]['value']

export function ResearchScoreTag({ research }: { research: ShortTermResearchSetup }) {
  return (
    <Tag tone={Number.isFinite(research.score) ? 'brand' : 'neutral'}>
      {Number.isFinite(research.score) ? `研究分 ${formatNumber(research.score)}` : '研究分待补'}
    </Tag>
  )
}

export function CandidateExecutionTag({ candidate }: { candidate: ShortTermCandidate }) {
  const executable = candidate.tradePlan?.status === 'ACTIONABLE'
    && isMorningOvernightPlan(candidate.tradePlan)
    && (candidate.signalProfile?.research?.setupType !== 'TREND_PULLBACK'
      || candidate.tailSignal?.intradayPullback?.status === 'CONFIRMED')
    && (candidate.todayAdvice.action === 'ADD' || candidate.todayAdvice.action === 'LIGHT_TRIAL')
  const pullback = candidate.todayAdvice.action === 'WAIT_PULLBACK'
  return (
    <span data-testid="candidate-execution-status">
      <Tag tone={executable ? 'success' : pullback ? 'warning' : 'neutral'}>
        {executable ? '可执行' : pullback ? '等回踩' : '观察'}
      </Tag>
    </span>
  )
}

export function ShortTermResearchPanel({ research }: { research: ShortTermResearchSetup }) {
  const components = [
    { key: 'trend', label: '趋势结构', weight: 25, score: research.trendScore, contribution: research.trendContribution },
    { key: 'relative-strength', label: '相对强度', weight: 20, score: research.relativeStrengthScore, contribution: research.relativeStrengthContribution },
    { key: 'setup', label: '买点形态', weight: 35, score: research.setupScore, contribution: research.setupContribution },
    { key: 'volume-price', label: '量价确认', weight: 20, score: research.volumePriceScore, contribution: research.volumePriceContribution }
  ]
  return (
    <section aria-label="研究买点详情" className="rounded-lg border border-brand-100 bg-brand-50/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-sm font-semibold text-ink-900">研究买点 · {research.setupLabel}</h3>
          <Tag tone={research.state === 'CONFIRMED' ? 'brand' : 'neutral'}>
            {research.setupType === 'TREND_PULLBACK' && research.state === 'WATCH'
              ? '日线背景通过'
              : research.state === 'CONFIRMED' ? '形态确认' : research.state === 'WATCH' ? '形态待确认' : '数据待补'}
          </Tag>
        </div>
        <ResearchScoreTag research={research} />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">
        研究分是规则匹配度，并非上涨概率；尾盘入场、下一交易日早盘退出，初始研究参数待前瞻验证。
      </p>
      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {components.map((item) => (
          <div key={item.key} data-component={item.key} className="rounded border border-line-soft bg-white px-2.5 py-2 text-xs">
            <div className="text-ink-600">{item.label} · {item.weight}%</div>
            <div className="mt-1 font-semibold text-ink-900">得分 {metric(item.score)}</div>
            <div className="mt-1 text-ink-500">贡献 {metric(item.contribution)} 分</div>
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">各项得分满分 100，按权重折算贡献后相加。缺失数据保留待补充，不以旧版分数替代。</p>
      <dl className="mt-3 grid grid-cols-2 gap-2 text-xs">
        <div className="border-l-2 border-brand-200 pl-2.5">
          <dt className="text-ink-500">{research.setupType === 'TREND_PULLBACK' ? '日线背景观察价' : '触发观察价'}</dt>
          <dd className="mt-1 font-semibold text-ink-900">{price(research.triggerPrice)}</dd>
        </div>
        <div className="border-l-2 border-amber-200 pl-2.5">
          <dt className="text-ink-500">{research.setupType === 'TREND_PULLBACK' ? '日线背景失效价' : '形态失效参考价'}</dt>
          <dd className="mt-1 font-semibold text-ink-900">{price(research.invalidationPrice)}</dd>
        </div>
      </dl>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">以上价格仅用于观察形态触发与失效；分时回踩确认后以实际回踩低点作为失效参考。是否可执行以分时证据、尾盘确认及隔夜交易计划为准。</p>
      <ResearchNotes label="形态证据" items={research.evidence} />
      <ResearchNotes label="风险与反证" items={research.risks} warning />
      <ResearchNotes label="数据缺口" items={research.dataGaps} warning />
      <details className="mt-3 border-t border-brand-100 pt-2 text-xs text-ink-500">
        <summary className="cursor-pointer font-medium text-ink-700">方法来源与参数说明</summary>
        <p className="mt-2 leading-relaxed">回踩分支先筛五日整理与低点抬高，再以真实分钟数据确认下午10分钟缩量回踩均价线、放量突破小平台；其他形态保留各自规则。具体阈值属于初始研究参数，尚未证明有效。</p>
        <ul className="mt-2 flex flex-col gap-1.5">
          <li><a className="text-brand-700 underline" href="https://qullamaggie.com/my-3-timeless-setups-that-have-made-me-tens-of-millions/" target="_blank" rel="noreferrer">Qullamaggie：突破形态与整理</a></li>
          <li><a className="text-brand-700 underline" href="https://get.investors.com/wp-content/uploads/2023/05/MS-How-to-Read-Stock-Charts.pdf" target="_blank" rel="noreferrer">IBD：图表、趋势与量价基础</a></li>
          <li><a className="text-brand-700 underline" href="https://www.smbtraining.com/blog/how-to-use-vwap-to-buy-a-pullback-in-a-meme-stock" target="_blank" rel="noreferrer">SMB：VWAP 回踩观察方法</a></li>
        </ul>
        <p className="mt-2 font-mono text-[11px]">规则版本 {research.ruleVersion}</p>
      </details>
    </section>
  )
}

function ResearchNotes({ label, items, warning = false }: { label: string; items: string[]; warning?: boolean }) {
  if (!items?.length) return null
  return (
    <div className={`mt-3 text-xs leading-relaxed ${warning ? 'text-amber-800' : 'text-ink-600'}`}>
      <h4 className="font-semibold">{label}</h4>
      <ul className="mt-1 list-disc space-y-0.5 pl-4">
        {items.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}
      </ul>
    </div>
  )
}

function metric(value: number | null | undefined) {
  return Number.isFinite(value) ? formatNumber(value) : '待补充'
}

function price(value: number | null | undefined) {
  return Number.isFinite(value) ? formatPerSharePrice(value) : '待补充'
}
