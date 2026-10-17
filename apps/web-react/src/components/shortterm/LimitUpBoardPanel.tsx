import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronRight, RefreshCw } from 'lucide-react'
import { fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth } from '../../api/client'
import { isEscapeClose } from '../ui/DetailOverlay'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Tag } from '../ui/Badge'
import { LimitUpIndustryOverlay, type LimitUpIndustrySelection } from './LimitUpIndustryOverlay'
import { formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { ShortTermLimitUpBoardSnapshot, ShortTermLimitUpSentiment, ShortTermMarketBreadth, ShortTermMarketTurnover } from '../../types'

const toneLabel: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral'> = {
  接力退潮: 'danger',
  情绪冰点: 'warning',
  情绪强势: 'success',
  情绪偏暖: 'brand',
  中性震荡: 'neutral'
}

const turnoverTone: Record<string, 'success' | 'warning' | 'neutral'> = {
  显著增量: 'success',
  温和增量: 'success',
  平量: 'neutral',
  温和缩量: 'warning',
  显著缩量: 'warning'
}

export function LimitUpBoardPanel() {
  const [snapshot, setSnapshot] = useState<ShortTermLimitUpBoardSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [breadth, setBreadth] = useState<ShortTermMarketBreadth | null>(null)
  const [breadthLoading, setBreadthLoading] = useState(false)
  const [breadthError, setBreadthError] = useState<string | null>(null)
  const [industrySelection, setIndustrySelection] = useState<LimitUpIndustrySelection | null>(null)
  const mounted = useRef(false)
  const refreshing = useRef(false)
  const refreshTimer = useRef<ReturnType<typeof setTimeout>>()

  const refresh = useCallback(async () => {
    if (refreshing.current) return
    refreshing.current = true
    clearTimeout(refreshTimer.current)
    setLoading(true)
    setError(null)
    setBreadthLoading(true)
    setBreadthError(null)
    await Promise.all([
      fetchShortTermLimitUpBoard()
        .then(value => { if (mounted.current) setSnapshot(value) })
        .catch((reason: unknown) => { if (mounted.current) setError(reason instanceof Error ? reason.message : '涨停看板获取失败') })
        .finally(() => { if (mounted.current) setLoading(false) }),
      fetchShortTermMarketBreadth()
        .then(value => { if (mounted.current) setBreadth(value) })
        .catch((reason: unknown) => { if (mounted.current) setBreadthError(reason instanceof Error ? reason.message : '涨跌家数获取失败') })
        .finally(() => { if (mounted.current) setBreadthLoading(false) })
    ])
    refreshing.current = false
    if (mounted.current) refreshTimer.current = setTimeout(() => void refresh(), 60000)
  }, [])

  useEffect(() => {
    mounted.current = true
    void refresh()
    return () => {
      mounted.current = false
      clearTimeout(refreshTimer.current)
    }
  }, [refresh])

  return (
    <div onKeyDown={event => {
      if (industrySelection && isEscapeClose(event.key, event.defaultPrevented)) {
        event.preventDefault()
        event.stopPropagation()
        setIndustrySelection(null)
      }
    }}>
      <Card>
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-base font-semibold text-ink-900">市场情绪与行业分布</h2>
          {snapshot ? <span className="text-xs text-ink-400">{snapshot.tradeDate} · 快照 {formatDateTime(snapshot.fetchedAt)}</span> : null}
        </div>
        <LimitUpBoardDetail
          snapshot={snapshot}
          loading={loading}
          error={error}
          breadth={breadth}
          breadthLoading={breadthLoading}
          breadthError={breadthError}
          onRefresh={() => void refresh()}
          onSelectIndustry={(industry) => {
            if (!snapshot) return
            setIndustrySelection({ industry, tradeDate: snapshot.tradeDate, fetchedAt: snapshot.fetchedAt,
              limitUpStocks: snapshot.stocks.filter(stock => (stock.industry?.trim() || '未分类') === industry) })
          }}
        />
      </Card>
      {industrySelection ? (
        <LimitUpIndustryOverlay key={`${industrySelection.tradeDate}:${industrySelection.industry}`}
          selection={industrySelection} onClose={() => setIndustrySelection(null)} />
      ) : null}
    </div>
  )
}

interface MarketBreadthProps {
  breadth?: ShortTermMarketBreadth | null
  breadthLoading?: boolean
  breadthError?: string | null
}

interface LimitUpBoardDetailProps extends MarketBreadthProps {
  snapshot: ShortTermLimitUpBoardSnapshot | null
  loading: boolean
  error: string | null
  onRefresh: () => void
  onSelectIndustry?: (industry: string) => void
}

/** 首页内的完整看板内容。 */
export function LimitUpBoardDetail({ snapshot, loading, error, breadth, breadthLoading, breadthError, onRefresh, onSelectIndustry }: LimitUpBoardDetailProps) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-2">
        <h4 className="text-sm font-semibold text-ink-900">最新可用涨跌数据</h4>
        <Button
          variant="secondary"
          icon={<RefreshCw className="h-4 w-4" />}
          loading={loading || breadthLoading}
          onClick={onRefresh}
        >
          刷新
        </Button>
      </div>
      {breadth ? (
        <div className="flex flex-col gap-2">
          <dl className="grid grid-cols-2 border-t border-line-soft text-xs sm:grid-cols-4">
            <Metric label="上涨" value={`${breadth.advancing} 家`} />
            <Metric label="下跌" value={`${breadth.declining} 家`} />
            <Metric label="平盘" value={`${breadth.unchanged} 家`} />
            <Metric
              label="跌停"
              value={!error && snapshot?.available && snapshot.sentiment?.limitDownCount != null
                ? `${snapshot.sentiment.limitDownCount} 家` : '待补充'}
            />
          </dl>
          <p className="text-[11px] leading-relaxed text-ink-400">
            {breadth.tradeDate} · {breadthSourceTime(breadth)} · {`获取时间 ${formatDateTime(breadth.fetchedAt)}`} · {breadth.source}。
            {breadth.missingTimestampCount ? ` ${breadth.missingTimestampCount} 只缺少源时间。` : ''}
            本次行情清单 {breadth.totalCount} 只，仅统计当日有效报价。
            {breadth.excludedCount > 0 ? ` 排除无当日报价、价格或涨跌幅缺失的 ${breadth.excludedCount} 只。` : ''}
            {breadthLoading ? ' 正在更新…' : ''}
          </p>
          {!error && snapshot?.available && snapshot.sentiment?.limitDownCount != null ? (
            <p className="text-[11px] leading-relaxed text-ink-400">
              跌停家数取自 {snapshot.tradeDate} 跌停池 · 快照 {formatDateTime(snapshot.fetchedAt)}。
            </p>
          ) : null}
        </div>
      ) : (
        <p className="text-xs text-ink-400">{breadthLoading ? '正在获取涨跌家数…' : '涨跌家数暂无数据，请点击刷新。'}</p>
      )}
      {breadthError ? (
        <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
          涨跌家数更新失败：{breadthError}{breadth ? '；当前保留上次快照，请注意统计时间。' : ''}
        </p>
      ) : null}
      {error ? (
        <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs leading-relaxed text-rose-700">
          涨停看板获取失败：{error}
        </p>
      ) : null}
      {!error && !snapshot ? (
        <p className="py-6 text-center text-sm text-ink-400">
          {loading ? '正在拉取涨停池数据…' : '暂无数据，请点击刷新。'}
        </p>
      ) : null}
      {!error && snapshot && !snapshot.available ? (
        <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
          {snapshot.unavailableReason ?? '涨停池数据不可用'}
        </p>
      ) : null}
      {!error && snapshot?.available && snapshot.sentiment ? (
        <>
          <SentimentSummary
            sentiment={snapshot.sentiment}
            turnover={snapshot.marketTurnover}
            stockCount={snapshot.stocks.length}
          />
          <LimitUpIndustryCards snapshot={snapshot} onSelectIndustry={onSelectIndustry} />
          <p className="text-[11px] leading-relaxed text-ink-400">
            {snapshot.sentiment.explanation}
            {snapshot.marketTurnover ? ` ${snapshot.marketTurnover.explanation}` : ''}
            盘中数据为快照口径，涨停与炸板数量随行情变化，不代表收盘结论。
          </p>
          {snapshot.dataGaps.length ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
              {snapshot.dataGaps.join('；')}
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  )
}

function breadthSourceTime(breadth: ShortTermMarketBreadth) {
  const { marketTimestampFrom: from, marketTimestampTo: to } = breadth
  if (!from || !to) return '源时间 未知'
  return `源时间 ${formatDateTime(from)}${from === to ? '' : ` 至 ${formatDateTime(to)}`}`
}

function SentimentSummary({
  sentiment,
  turnover,
  stockCount
}: {
  sentiment: ShortTermLimitUpSentiment
  turnover: ShortTermMarketTurnover | null
  stockCount: number
}) {
  const tone = toneLabel[sentiment.tone] ?? 'neutral'
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={tone}>{sentiment.tone}</Tag>
        {turnover ? (
          <Tag tone={turnoverTone[turnover.label] ?? 'neutral'}>
            量能 {turnover.label} {formatSignedPercent(turnover.volumeChangePercent)}
          </Tag>
        ) : null}
        <span className="text-xs text-ink-400">共 {stockCount} 只涨停</span>
      </div>
      <dl className="grid grid-cols-2 border-t border-line-soft text-xs md:grid-cols-4">
        <Metric label="涨停" value={`${sentiment.limitUpCount} 家`} />
        <Metric
          label="炸板(炸板率)"
          value={sentiment.brokenCount === null ? '待补充' : `${sentiment.brokenCount} 家（${formatPercent(sentiment.sealBreakRatioPercent)}）`}
        />
        <Metric label="跌停" value={sentiment.limitDownCount === null ? '待补充' : `${sentiment.limitDownCount} 家`} />
        <Metric label="最高连板" value={`${sentiment.maxConsecutiveBoards} 板`} />
        <Metric label="2 板以上" value={`${sentiment.boards2PlusCount} 家`} />
        <Metric label="3 板以上" value={`${sentiment.boards3PlusCount} 家`} />
        <Metric label="10 点前封板占比" value={formatPercent(sentiment.earlySealSharePercent)} />
        <Metric label="尾盘封板" value={`${sentiment.sealedTailCount} 家`} />
        <Metric
          label="量能对比(量)"
          value={turnover ? `${turnover.label} ${formatSignedPercent(turnover.volumeChangePercent)}` : '待补充'}
        />
        <Metric label="今日两市成交额" value={turnover?.todayAmountYuan ? formatAmount(turnover.todayAmountYuan) : '待补充'} />
      </dl>
      <div className="grid grid-cols-4 gap-1 text-center text-[11px] text-ink-500">
        <SealBucket label="10点前" count={sentiment.sealedBeforeTenCount} total={sentiment.limitUpCount} />
        <SealBucket label="10-11:30" count={sentiment.sealedMorningCount} total={sentiment.limitUpCount} />
        <SealBucket label="11:30-14:30" count={sentiment.sealedAfternoonCount} total={sentiment.limitUpCount} />
        <SealBucket label="14:30后" count={sentiment.sealedTailCount} total={sentiment.limitUpCount} />
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-b border-r border-line-soft px-2 py-2">
      <dt className="text-[11px] text-ink-400">{label}</dt>
      <dd className="mt-0.5 break-words font-semibold text-ink-900">{value}</dd>
    </div>
  )
}

function SealBucket({ label, count, total }: { label: string; count: number; total: number }) {
  const ratio = total > 0 ? Math.min(1, count / total) : 0
  return (
    <div className="flex flex-col gap-1">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-line">
        <div className="h-full rounded-full bg-rose-400" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span>
        {label} {count} 家
      </span>
    </div>
  )
}

function LimitUpIndustryCards({ snapshot, onSelectIndustry }: {
  snapshot: ShortTermLimitUpBoardSnapshot
  onSelectIndustry?: (industry: string) => void
}) {
  if (!snapshot.stocks.length) {
    return <p className="text-xs leading-relaxed text-ink-500">涨停明细暂无数据。</p>
  }
  // The API already sorts stocks by board depth and first seal time; keep that order within each industry.
  const byIndustry = new Map<string, ShortTermLimitUpBoardSnapshot['stocks']>()
  for (const stock of snapshot.stocks) {
    const industry = stock.industry?.trim() || '未分类'
    const group = byIndustry.get(industry)
    if (group) group.push(stock)
    else byIndustry.set(industry, [stock])
  }
  const industryGroups = [...byIndustry.entries()].sort((a, b) => b[1].length - a[1].length)

  return (
    <section aria-label="涨停行业卡片" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 className="text-sm font-semibold text-ink-900">涨停明细 · 行业概览</h4>
        <span className="text-xs text-ink-400">{industryGroups.length} 个行业 · {snapshot.stocks.length} 只涨停</span>
      </div>
      <p className="text-[11px] text-ink-500">按行业涨停家数排序，每卡预览前 3 只；点击卡片查看完整明细。</p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {industryGroups.map(([industry, stocks]) => {
          const maxBoards = Math.max(...stocks.map(stock => stock.consecutiveBoards))
          return (
            <button key={industry} type="button" aria-label={`查看${industry}涨停明细`}
              onClick={() => onSelectIndustry?.(industry)}
              className="group flex min-w-0 flex-col rounded-xl border border-line-soft bg-white p-3 text-left outline-none transition hover:border-brand-300 hover:bg-brand-50/30 hover:shadow-sm focus-visible:ring-2 focus-visible:ring-brand-300">
              <span className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate text-sm font-semibold text-ink-900" title={industry}>{industry}</span>
                <Tag tone="warning" className="shrink-0">涨停 {stocks.length} 家</Tag>
              </span>
              <span className="mt-2 flex items-center justify-between gap-2 text-[11px]">
                <span className={maxBoards >= 3 ? 'font-medium text-rose-600' : 'text-ink-500'}>最高 {maxBoards} 板</span>
                <span className="text-ink-400">连板 / 首封</span>
              </span>
              <span className="my-2 flex flex-col divide-y divide-line-soft border-t border-line-soft">
                {stocks.slice(0, 3).map(stock => (
                  <span key={stock.symbol} className="flex items-center justify-between gap-2 py-2">
                    <span className="min-w-0">
                      <span className="block truncate text-xs font-medium text-ink-900" title={stock.name}>{stock.name}</span>
                      <span className="block text-[10px] text-ink-400">{stock.symbol}</span>
                    </span>
                    <span className="flex shrink-0 items-center gap-2">
                      <Tag tone={stock.consecutiveBoards >= 3 ? 'danger' : stock.consecutiveBoards >= 2 ? 'warning' : 'neutral'}>
                        {stock.statDays > 0 ? `${stock.statDays}天${stock.statBoards > 0 ? stock.statBoards : stock.consecutiveBoards}板` : `${stock.consecutiveBoards} 板`}
                      </Tag>
                      <span className="tabular text-[11px] text-ink-500">{shortTime(stock.firstSealTime)}</span>
                    </span>
                  </span>
                ))}
              </span>
              <span className="mt-auto flex items-center justify-between gap-2 border-t border-line-soft pt-2 text-[11px]">
                <span className="text-ink-400">{stocks.length > 3 ? `还有 ${stocks.length - 3} 只` : '已展示全部预览'}</span>
                <span className="inline-flex items-center gap-1 font-medium text-brand-600">查看明细<ChevronRight className="h-3.5 w-3.5" /></span>
              </span>
            </button>
          )
        })}
      </div>
    </section>
  )
}

function shortTime(value: string | null) {
  return value ? value.slice(0, 5) : '—'
}
