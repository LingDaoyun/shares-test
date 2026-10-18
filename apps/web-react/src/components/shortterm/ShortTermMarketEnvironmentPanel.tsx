import type { ShortTermMarketRegime, ShortTermReport } from '../../types'
import { formatAmount, formatDateTime, formatNumber, formatPercent, formatSignedPercent } from '../../lib/format'
import { Card } from '../ui/Card'
import { Tag } from '../ui/Badge'

export function ShortTermMarketEnvironmentPanel({ report }: { report: ShortTermReport }) {
  const sentiment = report.marketSentiment
  const regime = report.marketRegime
  const liquidity = regime?.liquidity
  const comparable = liquidity?.status === 'SAME_TIME' || liquidity?.status === 'CLOSE'
  const comparisonLabel = liquidity?.status === 'CLOSE' ? '两市指数收盘成交额 / 量' : '两市指数同刻成交额 / 量'
  const legacyCounts = !regime?.limitCountSource && regime?.limitUpCount === undefined && regime?.limitDownCount === undefined
  const countsLabel = legacyCounts || regime?.limitCountSource === 'PRICE_CHANGE_PROXY' ? '近似涨停 / 跌停'
    : regime?.limitCountSource === 'MEASURED_POOL' ? '涨停 / 跌停（涨跌停池）'
      : regime?.limitCountSource === 'MIXED' ? '涨停池 / 近似跌停' : '涨停 / 跌停（来源待补）'
  const upCount = legacyCounts ? sentiment.limitUpLike : regime?.limitUpCount
  const downCount = legacyCounts ? sentiment.limitDownLike : regime?.limitDownCount
  const dataGaps = [...new Set([...(regime?.dataGaps ?? []), ...(liquidity?.dataGaps ?? [])])]

  return (
    <Card className="min-h-full border-red-100 bg-gradient-to-br from-white to-red-50/40">
      <div aria-label="市场环境" className="flex h-full flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h3 className="text-base font-semibold text-ink-900">扫描时市场情绪</h3>
            <p className="mt-1 text-xs text-ink-500">行情采集 {report.coverage?.fetchedAt ? formatDateTime(report.coverage.fetchedAt) : '时间未知'}</p>
          </div>
          <MarketActionTag regime={regime} />
        </div>
        <div className={regime?.maxAction === 'NO_TRADE' ? 'border-l-2 border-red-400 bg-red-50 px-3 py-2 text-sm text-red-800' : 'text-sm text-ink-700'}>
          <strong>{regime?.label ?? '市场状态待补'}</strong>
          <span className="ml-2 text-xs">{sentiment.phase} · 情绪分 {formatNumber(finite(sentiment.score))}</span>
        </div>
        <div className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
          <MarketMetric label="沪深A股样本成交额" value={formatAmount(finite(liquidity?.sampleAmountYuan))} />
          <MarketMetric label="上涨 / 下跌" value={`${count(sentiment.advancing)} / ${count(sentiment.declining)}`} />
          <MarketMetric label="市场宽度" value={formatPercent(finite(regime ? regime.breadthPercent : sentiment.breadthPercent))} />
          <MarketMetric label="上涨侧成交额占比" value={formatPercent(finite(regime?.advancingTurnoverSharePercent))} />
          <MarketMetric label={countsLabel} value={`${count(upCount)} / ${count(downCount)}`} />
          <MarketMetric label="炸板率" value={formatPercent(finite(regime?.sealBreakRatioPercent))} />
        </div>
        <div className="border-t border-line-soft pt-2 text-xs text-ink-600">
          <p>{comparisonLabel}</p>
          <p className="mt-1 tabular font-semibold text-ink-900">
            {comparable
              ? `额 ${formatSignedPercent(finite(liquidity.amountChangePercent))} / 量 ${formatSignedPercent(finite(liquidity.volumeChangePercent))}`
              : '量额比较不可用'}
          </p>
        </div>
        <p className={`text-xs leading-relaxed ${regime?.maxAction === 'NO_TRADE' ? 'font-semibold text-red-700' : 'text-ink-500'}`}>
          {regime?.explanation || sentiment.explanation}
        </p>
        <details className="text-xs text-ink-500">
          <summary className="cursor-pointer font-medium text-ink-600">口径与数据缺口</summary>
          <div className="mt-2 space-y-1 leading-relaxed">
            <p>样本 {count(regime?.sampleCount)} 只 · 成交额覆盖 {formatPercent(finite(liquidity?.amountCoveragePercent))}</p>
            <p>沪深A股样本成交额与两市指数比较采用不同统计范围，指数量额仅作为市场活跃度代理。</p>
            <p>行情来源：{report.coverage?.source || '待补充'} · 量额来源：{liquidity?.source || '待补充'}</p>
            <p>报告截止 {formatDateTime(report.dataCutoffAt)} · 量额截止 {formatDateTime(liquidity?.dataCutoffAt)}</p>
            {liquidity ? <>
              <p>量额交易日 {liquidity.tradeDate || '待补充'} · {liquidity.label}</p>
              <p>比较基准 {liquidity.comparisonDate || '待补充'} {liquidity.comparisonTime || ''}</p>
              {comparable ? <>
                <p>本期 / 基期成交额 {formatAmount(finite(liquidity.currentComparableAmountYuan))} / {formatAmount(finite(liquidity.previousComparableAmountYuan))}</p>
                <p>本期 / 基期成交量 {volume(liquidity.currentComparableVolumeHands)} / {volume(liquidity.previousComparableVolumeHands)}</p>
              </> : null}
              {liquidity.explanation ? <p>{liquidity.explanation}</p> : null}
            </> : <p>历史报告未保存可比量额，无法判断同刻放量或缩量。</p>}
            <p>{countsLabel} · 炸板 {count(regime?.brokenCount)} 只</p>
            {dataGaps.map((gap) => <p key={gap} className="text-amber-700">{gap}</p>)}
          </div>
        </details>
      </div>
    </Card>
  )
}

export function ShortTermMarketConstraint({ regime }: { regime: ShortTermMarketRegime | null | undefined }) {
  const stopped = regime?.maxAction === 'NO_TRADE'
  return (
    <div aria-label="市场执行约束" className={`border-l-2 px-3 py-2 text-xs leading-relaxed ${stopped ? 'border-red-400 bg-red-50 text-red-800' : 'border-amber-300 bg-amber-50/50 text-ink-600'}`}>
      <div className="flex flex-wrap items-center gap-2"><span>大盘：{regime?.label ?? '市场状态待补'}</span><MarketActionTag regime={regime} /></div>
      <p className="mt-1">{regime?.explanation || '历史报告未保存市场约束，无法确认当时的执行上限。'}</p>
      <p className="mt-1">市场约束只限制执行动作，个股研究分保持原值。</p>
    </div>
  )
}

function MarketActionTag({ regime }: { regime: ShortTermMarketRegime | null | undefined }) {
  if (regime?.maxAction === 'NO_TRADE') return <Tag tone="danger">暂停执行</Tag>
  if (regime?.maxAction === 'LIGHT_TRIAL') return <Tag tone="warning">仅轻仓</Tag>
  if (regime?.maxAction === 'NORMAL') return <Tag tone="success">正常试错</Tag>
  return <Tag tone="neutral">执行上限待补</Tag>
}

function MarketMetric({ label, value }: { label: string; value: string }) {
  return <div><p className="text-ink-400">{label}</p><p className="mt-0.5 tabular font-semibold text-ink-800">{value}</p></div>
}

function finite(value: number | null | undefined) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function count(value: number | null | undefined) {
  return finite(value) === null ? '待补充' : String(value)
}

function volume(value: number | null | undefined) {
  const number = finite(value)
  return number === null ? '待补充' : `${(number / 10_000).toFixed(2)} 万手`
}
