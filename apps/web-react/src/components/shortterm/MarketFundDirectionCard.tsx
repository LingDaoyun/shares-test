import { Tag } from '../ui/Badge'
import { Card } from '../ui/Card'
import { changeClass, formatAmount, formatDateTime, formatPercent, formatRatioPercent } from '../../lib/format'
import type { ShortTermIndustryFundDirection, ShortTermMarketFundDirection } from '../../types'

export function MarketFundDirectionCard({
  direction,
  onSelectIndustry,
  title = '今日资金去向',
  wide = false
}: {
  direction?: ShortTermMarketFundDirection | null
  onSelectIndustry: (item: ShortTermIndustryFundDirection, flow: 'INFLOW' | 'OUTFLOW') => void
  title?: string
  wide?: boolean
}) {
  const topInflows = direction?.topInflows ?? []
  const topOutflows = direction?.topOutflows ?? []
  const hasRows = topInflows.length > 0 || topOutflows.length > 0

  return (
    <Card title={title}>
      <div className="flex flex-col gap-3 text-sm text-ink-600">
        <dl className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-ink-500">
          <div><dt className="inline">来源 </dt><dd className="inline break-all">{direction?.sourceName || '待补'}</dd></div>
          <div><dt className="inline">数据日 </dt><dd className="inline">{direction?.tradeDate || '待补'}</dd></div>
          <div><dt className="inline">抓取时间 </dt><dd className="inline">{direction?.fetchedAt ? formatDateTime(direction.fetchedAt) : '待补'}</dd></div>
          <div>
            <dt className="inline">行业覆盖 </dt>
            <dd className="inline">{direction && direction.expectedIndustryCount > 0 ? (
              <>{direction.coveredIndustryCount ?? '待补'}/{direction.expectedIndustryCount}（{direction.coverageRatio == null ? '待补' : formatRatioPercent(direction.coverageRatio)}）</>
            ) : '待补'}</dd>
          </div>
        </dl>
        {hasRows ? (
          <div className={`grid grid-cols-1 gap-3 ${wide ? 'md:grid-cols-2' : ''}`}>
            <FundDirectionList title="主力流入" items={topInflows} tone="success" onSelectIndustry={onSelectIndustry} />
            <FundDirectionList title="主力流出" items={topOutflows} tone="danger" onSelectIndustry={onSelectIndustry} />
          </div>
        ) : (
          <p className="rounded-lg border border-line-soft bg-ink-50 px-3 py-2 text-xs leading-relaxed text-ink-500">
            行业资金流暂不可用
          </p>
        )}
        {hasRows ? (
          <p className="text-[11px] leading-relaxed text-ink-400">
            点击行业板块可查看板块内个股当日主力净流入 / 流出前 20 的资金排名。
          </p>
        ) : null}
        {direction?.dataGaps?.length ? (
          <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
            {direction.dataGaps.slice(0, 2).join('；')}
          </div>
        ) : null}
      </div>
    </Card>
  )
}

function FundDirectionList({
  title,
  items,
  tone,
  onSelectIndustry
}: {
  title: string
  items: ShortTermIndustryFundDirection[]
  tone: 'success' | 'danger'
  onSelectIndustry: (item: ShortTermIndustryFundDirection, flow: 'INFLOW' | 'OUTFLOW') => void
}) {
  if (!items.length) {
    return <p className="rounded-lg border border-line-soft bg-ink-50 px-3 py-2 text-xs leading-relaxed text-ink-500">{title}暂不可用</p>
  }
  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <span className="text-xs font-semibold text-ink-700">{title}</span>
        <Tag tone={tone}>{items.length} 项</Tag>
      </div>
      <div className="flex flex-col gap-1.5">
        {items.map((item) => (
          <button
            key={`${title}-${item.code}`}
            type="button"
            title={`查看 ${item.name || item.code} 板块内主力资金前 20 个股`}
            aria-label={`查看 ${item.name || item.code} 板块内主力资金前 20 个股`}
            onClick={() => onSelectIndustry(item, tone === 'success' ? 'INFLOW' : 'OUTFLOW')}
            className="rounded-lg border border-line-soft px-2.5 py-2 text-left transition hover:border-brand-200 hover:bg-brand-50/60 focus-visible:border-brand-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-100"
          >
            <div className="flex items-baseline justify-between gap-2">
              <span className="min-w-0 truncate font-semibold text-ink-900">{item.name || item.code || '未知板块'}</span>
              <span className={`shrink-0 tabular font-semibold ${changeClass(item.mainNetInflow)}`}>
                {formatAmount(item.mainNetInflow)}
              </span>
            </div>
            <div className="mt-1 flex flex-wrap gap-1.5">
              <Tag tone="neutral">占比 {formatPercent(item.mainNetInflowRatio)}</Tag>
              <Tag tone="neutral">集中 {formatPercent(item.concentrationPercent)}</Tag>
              {item.constituentCount > 0 ? (
                <Tag tone="neutral">涨跌 {item.advancing}/{item.declining}</Tag>
              ) : null}
            </div>
          </button>
        ))}
      </div>
    </div>
  )
}
