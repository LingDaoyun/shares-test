import { useState } from 'react'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatPercent, formatSignedPercent } from '../../lib/format'
import type { StockSearchQuote } from '../../types'
import { StockChartContent } from '../shortterm/StockKlineButton'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'

export function StockSearchDetail({ stock, onClose }: { stock: StockSearchQuote; onClose: () => void }) {
  const [kind, setKind] = useState<'daily' | 'intraday'>('daily')
  const quoteTime = stock.marketTimestamp ? formatDateTime(stock.marketTimestamp) : (stock.tradeDate ?? '时间待补充')

  return <DetailOverlay open title={`${stock.name} · ${stock.symbol}`}
    subtitle={`行情截至 ${quoteTime}${stock.sourceName ? ` · ${stock.sourceName}` : ''}`} onClose={onClose}>
    <div className="space-y-4">
      {stock.industry ? <p className="text-sm text-ink-500">{stock.industry}</p> : null}
      <dl aria-label="股票行情指标" className="grid grid-cols-2 gap-4 rounded-xl bg-line-soft/50 p-4 text-sm tabular sm:grid-cols-3 lg:grid-cols-6">
        {([
          ['最新价', formatNumber(stock.latestPrice), changeClass(stock.changePercent)],
          ['涨跌幅', formatSignedPercent(stock.changePercent), changeClass(stock.changePercent)],
          ['换手率', formatPercent(stock.turnoverRate), 'text-ink-900'],
          ['总市值', formatAmount(stock.totalMarketValue), 'text-ink-900'],
          ['流通市值', formatAmount(stock.circulatingMarketValue), 'text-ink-900'],
          ['成交额', formatAmount(stock.amount), 'text-ink-900'],
        ] as const).map(([label, value, color]) => <div key={label}>
          <dt className="text-xs text-ink-400">{label}</dt>
          <dd className={`mt-1 font-semibold ${color}`}>{value}</dd>
        </div>)}
      </dl>
      <div role="group" aria-label="行情类型" className="flex gap-2">
        <Button type="button" variant={kind === 'daily' ? 'primary' : 'secondary'}
          aria-pressed={kind === 'daily'} onClick={() => setKind('daily')}>日线</Button>
        <Button type="button" variant={kind === 'intraday' ? 'primary' : 'secondary'}
          aria-pressed={kind === 'intraday'} onClick={() => setKind('intraday')}>分时</Button>
      </div>
      <StockChartContent key={`${stock.symbol}:${kind}`} symbol={stock.symbol} kind={kind} observationDate={stock.tradeDate ?? '未知'} />
    </div>
  </DetailOverlay>
}
