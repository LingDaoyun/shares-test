import { useEffect, useState } from 'react'
import { fetchStockDaily, fetchStockIntraday } from '../../api/client'
import { extractErrorMessage, formatDateTime } from '../../lib/format'
import type { StockDailySnapshot, StockIntradaySnapshot } from '../../types'
import { LeaderCandlestickChart } from '../leader/LeaderCandlestickChart'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Empty } from '../ui/Empty'
import { Loader } from '../ui/Loader'
import { StockIntradayChart } from './StockIntradayChart'

type ChartKind = 'intraday' | 'daily'
type ChartData = { kind: 'intraday'; data: StockIntradaySnapshot } | { kind: 'daily'; data: StockDailySnapshot }

export function StockKlineButton({ symbol, name, tradeDate }: {
  symbol: string
  name: string
  tradeDate: string
}) {
  const [open, setOpen] = useState(false)
  const [kind, setKind] = useState<ChartKind>('intraday')

  return <>
    <Button type="button" variant="ghost" className="px-2 py-1 text-xs"
      aria-label={`查看${name}分时和日线`} aria-haspopup="dialog" aria-expanded={open}
      onClick={() => { setKind('intraday'); setOpen(true) }}>
      分时 / 日线
    </Button>
    {open ? <DetailOverlay open title={`${name} · ${symbol} · 行情图`}
      subtitle={`看板 / 观察日 ${tradeDate}`} onClose={() => setOpen(false)}>
      <div className="space-y-4">
        <div role="group" aria-label="行情类型" className="flex gap-2">
          <Button type="button" variant={kind === 'intraday' ? 'primary' : 'secondary'}
            aria-pressed={kind === 'intraday'} onClick={() => setKind('intraday')}>分时</Button>
          <Button type="button" variant={kind === 'daily' ? 'primary' : 'secondary'}
            aria-pressed={kind === 'daily'} onClick={() => setKind('daily')}>日线</Button>
        </div>
        <StockChartContent key={`${symbol}:${kind}`} symbol={symbol} kind={kind} observationDate={tradeDate} />
      </div>
    </DetailOverlay> : null}
  </>
}

function StockChartContent({ symbol, kind, observationDate }: { symbol: string; kind: ChartKind; observationDate: string }) {
  const [chart, setChart] = useState<ChartData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const label = kind === 'intraday' ? '分时' : '日线'

  useEffect(() => {
    let cancelled = false
    setChart(null)
    setError(null)
    setLoading(true)
    async function load() {
      const result: ChartData = kind === 'intraday'
        ? { kind, data: await fetchStockIntraday(symbol) }
        : { kind, data: await fetchStockDaily(symbol) }
      if (cancelled) return
      if (result.data.symbol !== symbol) throw new Error('返回行情与所选股票不一致，请重新加载。')
      setChart(result)
    }
    load().catch(reason => { if (!cancelled) setError(extractErrorMessage(reason)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [symbol, kind, retry])

  return <section aria-label={`${label}行情`} aria-busy={loading} className="space-y-3">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <p className="text-xs text-ink-500">{kind === 'intraday' ? '分时价格、均价与每分钟成交量' : '最近最多 120 根可用前复权日线与成交量'}</p>
      <Button type="button" variant="ghost" className="text-xs" disabled={loading} onClick={() => setRetry(value => value + 1)}>
        {error ? `重新加载${label}` : `刷新${label}`}
      </Button>
    </div>
    {loading ? <Loader text={`${label}图加载中`} /> : null}
    {error ? <p role="alert" className="text-sm text-amber-800">{label}获取失败：{error}</p> : null}
    {chart?.kind === 'intraday' ? <>
      <p className="text-xs text-ink-500">{chart.data.source} · 分时行情日 {chart.data.tradeDate ?? '未知'} · 行情截至 {chart.data.points[chart.data.points.length - 1]?.minute.slice(11, 16) ?? '未知'} · 获取于 {formatDateTime(chart.data.fetchedAt)}</p>
      {chart.data.tradeDate && chart.data.tradeDate !== observationDate
        ? <p className="text-xs text-amber-800">分时行情日与看板 / 观察日不同，以下显示数据源返回日期的行情。</p> : null}
      {chart.data.points.length ? <StockIntradayChart points={chart.data.points} /> : <Empty text="暂无可用分时数据，可稍后刷新或切换日线。" />}
    </> : null}
    {chart?.kind === 'daily' ? <>
      <p className="text-xs text-ink-500">前复权日线 · 日线截至 {chart.data.bars[chart.data.bars.length - 1]?.tradeDate ?? '未知'} · 起始 {chart.data.bars[0]?.tradeDate ?? '未知'} · {chart.data.bars.length} 个有效交易日 · 成交量（手） · {chart.data.source} · 获取于 {formatDateTime(chart.data.fetchedAt)}</p>
      <p className="text-xs text-ink-500">若包含当日日线，收盘前的 K 线与成交量会随行情变化；点击刷新获取更新。</p>
      <LeaderCandlestickChart bars={chart.data.bars} interactive />
    </> : null}
  </section>
}
