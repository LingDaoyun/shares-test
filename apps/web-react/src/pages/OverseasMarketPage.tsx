import { useCallback, useEffect, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { fetchOverseasMarketBoard } from '../api/client'
import { Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Loader } from '../components/ui/Loader'
import { changeClass, extractErrorMessage, formatDateTime, formatNumber, formatSignedPercent } from '../lib/format'
import type { OverseasIndexQuote, OverseasMarketBoardSnapshot, OverseasMarketSection } from '../types'

export function OverseasMarketPage() {
  const [snapshot, setSnapshot] = useState<OverseasMarketBoardSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSnapshot(await fetchOverseasMarketBoard())
    } catch (caught) {
      setError(extractErrorMessage(caught))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <div className="flex flex-col gap-4" aria-label="外围市场看板" aria-busy={loading}>
      <h1 className="sr-only">外围市场</h1>
      <section className="overflow-hidden rounded-2xl border border-line bg-white">
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-line-soft px-4 py-3 sm:px-5">
          {snapshot?.available ? <h2 className="text-sm font-semibold text-ink-900">全球指数速览</h2> : <span className="text-sm text-ink-600">行情数据</span>}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            {snapshot?.generatedAt ? (
              <span className="tabular text-xs text-ink-400">更新于 {formatDateTime(snapshot.generatedAt)}</span>
            ) : null}
            <Button
              variant="ghost"
              className="!px-2 !py-1 text-xs"
              icon={<RefreshCw className="h-4 w-4" />}
              loading={loading}
              onClick={() => void load()}
            >
              刷新
            </Button>
          </div>
        </div>

        {error ? <p role="alert" className="m-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</p> : null}
        {loading && !snapshot ? (
          <div className="p-5">
            <Loader text="外围市场行情加载中" />
          </div>
        ) : null}

        {snapshot && !snapshot.available ? (
          <p className="m-4 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-relaxed text-amber-800">
            外围看板暂不可用：{snapshot.unavailableReason ?? '数据源获取失败'}
          </p>
        ) : null}
        {snapshot?.available ? <GlobalIndexStrip indices={snapshot.globalIndices} /> : null}
      </section>

      {snapshot?.available ? (
        <>
          <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-3">
            {snapshot.markets.map((section) => (
              <MarketSectionCard key={section.market} section={section} />
            ))}
          </div>

          {snapshot.dataGaps.length ? (
            <Card className="border-amber-200 bg-amber-50/60">
              <ul className="flex flex-col gap-1 text-xs leading-relaxed text-amber-800">
                {snapshot.dataGaps.map((gap) => (
                  <li key={gap}>{gap}</li>
                ))}
              </ul>
            </Card>
          ) : null}

          <details className="px-1 text-xs leading-relaxed text-ink-400">
            <summary className="w-fit cursor-pointer rounded py-1 transition hover:text-ink-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500">数据来源与口径</summary>
            <p className="mt-2 max-w-5xl text-ink-600">
              口径说明：美股行业为 SPDR Select Sector ETF 涨跌幅；日本行业为 TOPIX-17 系列 ETF、韩国行业为
              KODEX 系列 ETF 的代理口径，涨跌幅即 ETF 最新价相对前收。指数与美股行业来自东方财富，日韩行业来自
              Yahoo Finance（需国际网络，不可达时对应市场仅展示指数并给出提示）。交易状态按交易所当地时段估算，不含节假日。
            </p>
          </details>
        </>
      ) : null}
    </div>
  )
}

function GlobalIndexStrip({ indices }: { indices: OverseasIndexQuote[] }) {
  if (!indices.length) {
    return null
  }
  return (
    <div className="grid grid-cols-2 gap-px bg-line-soft sm:grid-cols-3 lg:grid-cols-5">
      {indices.map((index) => (
        <div key={index.code} className="min-w-0 bg-white px-4 py-4 sm:px-5">
          <div className="truncate text-xs text-ink-600" title={index.name}>{index.name}</div>
          <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <span className={`tabular text-xl font-semibold tracking-tight ${changeClass(index.changePercent)}`}>
              {formatSignedPercent(index.changePercent)}
            </span>
            <span className="tabular text-xs text-ink-400">{formatNumber(index.latestPrice)}</span>
          </div>
        </div>
      ))}
    </div>
  )
}

function MarketSectionCard({ section }: { section: OverseasMarketSection }) {
  const industries = section.industries
  const advances = industries.filter((industry) => industry.changePercent != null && industry.changePercent > 0).length
  const declines = industries.filter((industry) => industry.changePercent != null && industry.changePercent < 0).length
  const maxAbsChange = industries.reduce((max, item) => {
    const value = Math.abs(Number(item.changePercent ?? 0))
    return Number.isFinite(value) ? Math.max(max, value) : max
  }, 0)
  const quoteTimes = [...section.indices, ...industries]
    .map((item) => item.quoteTime)
    .filter((time): time is string => Boolean(time))
    .sort()
  const latestQuoteTime = quoteTimes.length ? quoteTimes[quoteTimes.length - 1] : null

  return (
    <Card
      className="!shadow-none"
      flush
      title={(
        <div className="inline-flex items-center gap-2">
          <h2 className="text-base font-semibold text-ink-900">{section.marketLabel}</h2>
          <Tag tone={section.status === 'OPEN' ? 'success' : 'neutral'}>
            {section.status === 'OPEN' ? '交易中' : '已收盘'}
          </Tag>
        </div>
      )}
      extra={industries.length ? <span className="tabular text-xs text-ink-400">{industries.length} 行业</span> : undefined}
    >
      <div className="flex flex-col">
        <div className="flex flex-col justify-start px-4 py-2 xl:min-h-[164px]">
          {section.indices.map((index) => (
            <IndexRow key={index.code} quote={index} />
          ))}
        </div>

        {section.unavailableReason ? (
          <p className="mx-4 mb-4 rounded-lg bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
            {section.unavailableReason}
          </p>
        ) : industries.length ? (
          <div className="border-t border-line-soft px-4 pb-4 pt-3">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs">
              <h3 className="font-medium text-ink-600">行业表现</h3>
              <span className="flex gap-3 tabular"><span className="text-up">涨 {advances}</span><span className="text-down">跌 {declines}</span></span>
            </div>
            <div className="flex flex-col gap-1" aria-label={`${section.marketLabel}行业涨跌`}>
              {industries.map((industry) => (
                <IndustryBar key={industry.code} quote={industry} maxAbsChange={maxAbsChange} />
              ))}
            </div>
          </div>
        ) : null}

        {latestQuoteTime ? (
          <p className="tabular border-t border-line-soft px-4 py-3 text-[11px] text-ink-400">行情截至 {formatDateTime(latestQuoteTime)}</p>
        ) : null}
      </div>
    </Card>
  )
}

function IndexRow({ quote }: { quote: OverseasIndexQuote }) {
  return (
    <div className="flex min-h-12 flex-wrap items-center justify-between gap-x-3 gap-y-1 border-b border-line-soft py-2 last:border-b-0">
      <span className="min-w-0 truncate text-sm font-semibold text-ink-900">{quote.name}</span>
      <span className="flex shrink-0 items-baseline gap-2">
        <span className="tabular text-xs text-ink-600">{formatNumber(quote.latestPrice)}</span>
        <span className={`tabular min-w-[4.5rem] text-right text-base font-semibold ${changeClass(quote.changePercent)}`}>
          {formatSignedPercent(quote.changePercent)}
        </span>
      </span>
    </div>
  )
}

function IndustryBar({ quote, maxAbsChange }: { quote: OverseasIndexQuote; maxAbsChange: number }) {
  const changePercent = quote.changePercent == null ? null : Number(quote.changePercent)
  const ratio = changePercent != null && maxAbsChange > 0
    ? Math.min(1, Math.abs(changePercent) / maxAbsChange)
    : 0
  return (
    <div className="relative isolate flex min-h-8 items-center justify-between gap-3 overflow-hidden rounded px-2.5 py-1.5">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 bg-line-soft/40">
        {ratio > 0 ? (
          <div
            className={`h-full ${(changePercent ?? 0) > 0 ? 'bg-up/10' : 'bg-down/10'}`}
            style={{ width: `${ratio * 100}%` }}
          />
        ) : null}
      </div>
      <span className="min-w-0 truncate text-xs font-medium text-ink-600" title={quote.name}>{quote.name}</span>
      <span className={`tabular shrink-0 text-right text-xs font-semibold ${changeClass(quote.changePercent)}`}>
        {formatSignedPercent(quote.changePercent)}
      </span>
    </div>
  )
}
