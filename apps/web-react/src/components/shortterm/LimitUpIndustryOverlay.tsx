import { ArrowLeft } from 'lucide-react'
import { Button } from '../ui/Button'
import { Tag } from '../ui/Badge'
import { DetailOverlay } from '../ui/DetailOverlay'
import { LeaderCatchUpPanel } from './LeaderCatchUpPanel'
import { changeClass, formatAmount, formatDateTime, formatPercent, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import type { ShortTermLimitUpStock } from '../../types'

export interface LimitUpIndustrySelection {
  industry: string
  tradeDate: string
  fetchedAt: string
  limitUpStocks: ShortTermLimitUpStock[]
}

export function LimitUpIndustryOverlay({ selection, onClose }: {
  selection: LimitUpIndustrySelection
  onClose: () => void
}) {
  const sealed = selection.limitUpStocks

  return (
    <DetailOverlay
      open
      title={`${selection.industry} · 涨停明细`}
      subtitle={`${selection.tradeDate} · 已涨停 ${sealed.length} 家`}
      onClose={onClose}
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Button variant="ghost" icon={<ArrowLeft className="h-4 w-4" />} onClick={onClose}>返回涨停看板</Button>
          <span className="text-xs text-ink-400">涨停快照 {formatDateTime(selection.fetchedAt)}</span>
        </div>
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold text-ink-900">已涨停 · {sealed.length} 家</h3>
          <p className="text-xs text-ink-500">全部涨停股票，按连板高度及首次封板时间排列。</p>
          <StockTable stocks={sealed} />
        </section>
        <LeaderCatchUpPanel key={`${selection.tradeDate}:${selection.industry}`} industry={selection.industry} tradeDate={selection.tradeDate} sealedStocks={sealed} />
      </div>
    </DetailOverlay>
  )
}

function StockTable({ stocks }: { stocks: ShortTermLimitUpStock[] }) {
  if (!stocks.length) return <p className="py-3 text-xs text-ink-500">该行业暂无涨停股票。</p>
  return (
    <div className="overflow-x-auto rounded-xl border border-line-soft">
      <table className="w-full min-w-[900px] text-left text-xs" aria-label="已涨停股票">
        <thead className="bg-line-soft/70 text-ink-500">
          <tr>
            {['名称 / 代码', '现价', '涨幅', '成交额', '换手率', '连板', '首次封板', '末次封板', '炸板次数', '封板资金'].map(label => (
              <th key={label} className="whitespace-nowrap px-3 py-2 font-medium">{label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {stocks.map(stock => (
            <tr key={stock.symbol} className="border-t border-line-soft">
              <td className="px-3 py-2"><span className="font-semibold text-ink-900">{stock.name}</span><span className="ml-2 text-ink-400">{stock.symbol}</span></td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{formatPerSharePrice(stock.latestPrice)}</td>
              <td className={`px-3 py-2 tabular ${changeClass(stock.changePercent)}`}>{formatSignedPercent(stock.changePercent)}</td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{formatAmount(stock.amount)}</td>
              <td className="px-3 py-2 tabular">{formatPercent(stock.turnoverRate)}</td>
              <td className="whitespace-nowrap px-3 py-2">
                <Tag tone={stock.consecutiveBoards >= 3 ? 'danger' : stock.consecutiveBoards >= 2 ? 'warning' : 'neutral'}>
                  {stock.statDays > 0 ? `${stock.statDays}天${stock.statBoards > 0 ? stock.statBoards : stock.consecutiveBoards}板` : `${stock.consecutiveBoards} 板`}
                </Tag>
              </td>
              <td className="px-3 py-2 tabular">{stock.firstSealTime?.slice(0, 5) ?? '—'}</td>
              <td className="px-3 py-2 tabular">{stock.lastSealTime?.slice(0, 5) ?? '—'}</td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{stock.sealBreakCount > 0 ? `${stock.sealBreakCount} 次` : '—'}</td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{formatAmount(stock.sealFunds)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
