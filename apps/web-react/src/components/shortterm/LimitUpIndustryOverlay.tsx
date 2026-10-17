import { useEffect, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { fetchShortTermLimitUpIndustry } from '../../api/client'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Loader } from '../ui/Loader'
import { LeaderCatchUpPanel } from './LeaderCatchUpPanel'
import { changeClass, formatAmount, formatDateTime, formatPercent, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import type { ShortTermIndustryRisingStock, ShortTermLimitUpIndustrySnapshot, ShortTermLimitUpStock } from '../../types'

export interface LimitUpIndustrySelection {
  industry: string
  tradeDate: string
  limitUpStocks: ShortTermLimitUpStock[]
}

export function LimitUpIndustryOverlay({ selection, onClose }: {
  selection: LimitUpIndustrySelection
  onClose: () => void
}) {
  const [snapshot, setSnapshot] = useState<ShortTermLimitUpIndustrySnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retryCount, setRetryCount] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    fetchShortTermLimitUpIndustry(selection.industry, selection.tradeDate)
      .then((data) => {
        if (data.tradeDate !== selection.tradeDate || data.industry !== selection.industry) {
          throw new Error('行业详情与看板的行业或日期不一致，请重试。')
        }
        if (!cancelled) setSnapshot(data)
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : '行业详情获取失败')
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [selection.industry, selection.tradeDate, retryCount])

  const sealed = snapshot?.limitUpStocks ?? selection.limitUpStocks

  return (
    <DetailOverlay
      open
      title={`${selection.industry} · 涨停与上涨股票`}
      subtitle={`${selection.tradeDate} · 已涨停 ${snapshot?.limitUpCount ?? sealed.length} 家`}
      onClose={onClose}
    >
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Button variant="ghost" icon={<ArrowLeft className="h-4 w-4" />} onClick={onClose}>返回涨停看板</Button>
          {snapshot ? <span className="text-xs text-ink-400">涨停快照 {formatDateTime(snapshot.fetchedAt)}</span> : null}
        </div>
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold text-ink-900">已涨停 · {snapshot?.limitUpCount ?? sealed.length} 家</h3>
          <p className="text-xs text-ink-500">全部涨停股票，按连板高度及首次封板时间排列。</p>
          <StockTable stocks={sealed} sealed />
        </section>
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold text-ink-900">
            未涨停上涨股{snapshot?.risingAvailable ? ` · ${snapshot.risingStocks.length} 家` : ''}
          </h3>
          <p className="text-xs text-ink-500">同一行业内按涨幅从高到低排列，最多 30 家。</p>
          {loading ? <Loader text="行业上涨股票加载中" /> : null}
          {error ? (
            <div className="flex flex-col items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
              <span>行业详情更新失败：{error}</span>
              <Button onClick={() => setRetryCount(count => count + 1)}>重试</Button>
            </div>
          ) : null}
          {!loading && !error && snapshot ? (
            snapshot.risingAvailable ? (
              <>
                <StockTable stocks={snapshot.risingStocks} />
                <p className="text-[11px] text-ink-400">{snapshot.tradeDate} · {snapshot.quoteSource}</p>
              </>
            ) : (
              <div className="flex flex-col items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
                <span>{snapshot.dataGaps.join('；') || '行业未涨停上涨股暂缺。'}</span>
                <Button onClick={() => setRetryCount(count => count + 1)}>重试</Button>
              </div>
            )
          ) : null}
        </section>
        <LeaderCatchUpPanel key={`${selection.tradeDate}:${selection.industry}`} industry={selection.industry} tradeDate={selection.tradeDate} sealedStocks={sealed} />
      </div>
    </DetailOverlay>
  )
}

function StockTable({ stocks, sealed = false }: {
  stocks: (ShortTermLimitUpStock | ShortTermIndustryRisingStock)[]
  sealed?: boolean
}) {
  if (!stocks.length) return <p className="py-3 text-xs text-ink-500">{sealed ? '该行业暂无涨停股票。' : '该行业暂无符合条件的未涨停上涨股。'}</p>
  return (
    <div className="overflow-x-auto rounded-xl border border-line-soft">
      <table className="w-full min-w-[600px] text-left text-xs" aria-label={sealed ? '已涨停股票' : '未涨停上涨股票'}>
        <thead className="bg-line-soft/70 text-ink-500">
          <tr>
            {['名称 / 代码', '现价', '涨幅', '成交额', '换手率', ...(sealed ? ['连板', '首次封板'] : ['行情时间'])].map(label => (
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
              {sealed && 'consecutiveBoards' in stock ? (
                <><td className="px-3 py-2">{stock.consecutiveBoards} 板</td><td className="px-3 py-2 tabular">{stock.firstSealTime?.slice(0, 5) ?? '—'}</td></>
              ) : 'marketTimestamp' in stock ? <td className="whitespace-nowrap px-3 py-2 tabular">{formatDateTime(stock.marketTimestamp)}</td> : null}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
