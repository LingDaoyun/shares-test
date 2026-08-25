import { useCallback, useEffect, useState } from 'react'
import { Globe2, RefreshCw } from 'lucide-react'
import { fetchOverseasMarketBoard } from '../api/client'
import { Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
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
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="GLOBAL MARKETS"
        title="外围市场"
        description="美股、日本、韩国的主要指数与行业涨跌，用于观察隔夜外围情绪对 A 股的传导。"
        extra={(
          <div className="flex items-center gap-3">
            {snapshot?.generatedAt ? (
              <span className="tabular text-xs text-ink-400">更新于 {formatDateTime(snapshot.generatedAt)}</span>
            ) : null}
            <Button
              variant="primary"
              icon={<RefreshCw className="h-4 w-4" />}
              loading={loading}
              onClick={() => void load()}
            >
              刷新
            </Button>
          </div>
        )}
      />

      {error ? <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card> : null}
      {loading && !snapshot ? (
        <Card>
          <Loader text="外围市场行情加载中" />
        </Card>
      ) : null}

      {snapshot && !snapshot.available ? (
        <Card className="border-amber-200 bg-amber-50 text-sm leading-relaxed text-amber-800">
          外围看板暂不可用：{snapshot.unavailableReason ?? '数据源获取失败'}
        </Card>
      ) : null}

      {snapshot?.available ? (
        <>
          <GlobalIndexStrip indices={snapshot.globalIndices} />

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
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

          <Card className="border-line-soft bg-line-soft/30">
            <p className="text-xs leading-relaxed text-ink-500">
              口径说明：美股行业为 SPDR Select Sector ETF 涨跌幅；日本行业为 TOPIX-17 系列 ETF、韩国行业为
              KODEX 系列 ETF 的代理口径，涨跌幅即 ETF 最新价相对前收。指数与美股行业来自东方财富，日韩行业来自
              Yahoo Finance（需国际网络，不可达时对应市场仅展示指数并给出提示）。交易状态按交易所当地时段估算，不含节假日。
            </p>
          </Card>
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
    <Card
      title={(
        <span className="inline-flex items-center gap-2">
          <Globe2 className="h-4 w-4 text-brand-500" />
          全球指数速览
        </span>
      )}
    >
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
        {indices.map((index) => (
          <div key={index.code} className="rounded-xl border border-line-soft bg-white px-3 py-2.5">
            <div className="truncate text-sm font-semibold text-ink-900">{index.name}</div>
            <div className="mt-1 flex items-baseline justify-between gap-2">
              <span className="tabular text-sm text-ink-600">{formatNumber(index.latestPrice)}</span>
              <span className={`tabular text-sm font-semibold ${changeClass(index.changePercent)}`}>
                {formatSignedPercent(index.changePercent)}
              </span>
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}

function MarketSectionCard({ section }: { section: OverseasMarketSection }) {
  const industries = section.industries
  const leader = industries[0]
  const laggard = industries.length > 1 ? industries[industries.length - 1] : null
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
      title={(
        <span className="inline-flex items-center gap-2">
          <span className="text-base font-semibold text-ink-900">{section.marketLabel}</span>
          <Tag tone={section.status === 'OPEN' ? 'success' : 'neutral'}>
            {section.status === 'OPEN' ? '交易中' : '已收盘'}
          </Tag>
        </span>
      )}
      extra={industries.length ? <Tag tone="neutral">{industries.length} 行业</Tag> : undefined}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          {section.indices.map((index) => (
            <IndexRow key={index.code} quote={index} />
          ))}
        </div>

        {section.unavailableReason ? (
          <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
            {section.unavailableReason}
          </p>
        ) : industries.length ? (
          <div className="flex flex-col gap-2">
            {leader && laggard ? (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-500">
                <span className="inline-flex items-center gap-1">
                  领涨
                  <span className={`font-semibold ${changeClass(leader.changePercent)}`}>
                    {leader.name} {formatSignedPercent(leader.changePercent)}
                  </span>
                </span>
                <span className="inline-flex items-center gap-1">
                  领跌
                  <span className={`font-semibold ${changeClass(laggard.changePercent)}`}>
                    {laggard.name} {formatSignedPercent(laggard.changePercent)}
                  </span>
                </span>
              </div>
            ) : null}
            <div className="flex flex-col gap-1.5">
              {industries.map((industry) => (
                <IndustryBar key={industry.code} quote={industry} maxAbsChange={maxAbsChange} />
              ))}
            </div>
          </div>
        ) : null}

        {latestQuoteTime ? (
          <p className="tabular text-[11px] text-ink-400">行情截至 {formatDateTime(latestQuoteTime)}</p>
        ) : null}
      </div>
    </Card>
  )
}

function IndexRow({ quote }: { quote: OverseasIndexQuote }) {
  return (
    <div className="flex items-baseline justify-between gap-2 rounded-lg border border-line-soft bg-line-soft/30 px-3 py-2">
      <span className="min-w-0 truncate text-sm font-semibold text-ink-900">{quote.name}</span>
      <span className="flex shrink-0 items-baseline gap-2">
        <span className="tabular text-sm text-ink-600">{formatNumber(quote.latestPrice)}</span>
        <span className={`tabular text-sm font-semibold ${changeClass(quote.changePercent)}`}>
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
    <div className="grid grid-cols-[minmax(0,7rem)_1fr_4.2rem] items-center gap-2">
      <span className="truncate text-xs font-medium text-ink-700">{quote.name}</span>
      <div className="h-1.5 overflow-hidden rounded-full bg-line-soft">
        {ratio > 0 ? (
          <div
            className={`h-full rounded-full ${(changePercent ?? 0) > 0 ? 'bg-[#DC2626]' : 'bg-[#16A34A]'}`}
            style={{ width: `${ratio * 100}%` }}
          />
        ) : null}
      </div>
      <span className={`tabular text-right text-xs font-semibold ${changeClass(quote.changePercent)}`}>
        {formatSignedPercent(quote.changePercent)}
      </span>
    </div>
  )
}
