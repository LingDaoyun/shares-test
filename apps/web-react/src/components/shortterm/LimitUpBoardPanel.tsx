import { useCallback, useEffect, useRef, useState } from 'react'
import { BarChart3, ChevronRight, Loader2, RefreshCw } from 'lucide-react'
import { fetchShortTermLimitBoardContext, fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth } from '../../api/client'
import { DetailOverlay, isEscapeClose } from '../ui/DetailOverlay'
import { Button } from '../ui/Button'
import { CardPagination } from '../ui/CardPagination'
import { Tag } from '../ui/Badge'
import { CatchUpFollowUpPanel } from './CatchUpFollowUpPanel'
import { LimitUpIndustryOverlay, type LimitUpIndustrySelection } from './LimitUpIndustryOverlay'
import { MarketFundFlowPanel } from './MarketFundFlowPanel'
import { StockKlineDialog, type StockKlineSelection } from './StockKlineButton'
import { formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { ShortTermLimitBoardContext, ShortTermLimitDownStock, ShortTermLimitUpBoardSnapshot, ShortTermLimitUpSentiment, ShortTermLimitUpStock, ShortTermMarketBreadth, ShortTermMarketTurnover } from '../../types'

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
  const [view, setView] = useState<'ladder' | 'industry' | 'down'>('ladder')
  const [snapshot, setSnapshot] = useState<ShortTermLimitUpBoardSnapshot | null>(null)
  const [boardContext, setBoardContext] = useState<ShortTermLimitBoardContext | null>(null)
  const [contextLoading, setContextLoading] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [breadth, setBreadth] = useState<ShortTermMarketBreadth | null>(null)
  const [breadthLoading, setBreadthLoading] = useState(false)
  const [breadthError, setBreadthError] = useState<string | null>(null)
  const [marketOpen, setMarketOpen] = useState(false)
  const [followUpOpen, setFollowUpOpen] = useState(false)
  const [firstBoardOnly, setFirstBoardOnly] = useState(false)
  const [fundRefreshKey, setFundRefreshKey] = useState(0)
  const [industrySelection, setIndustrySelection] = useState<LimitUpIndustrySelection | null>(null)
  const [chartSelection, setChartSelection] = useState<StockKlineSelection | null>(null)
  const mounted = useRef(false)
  const refreshing = useRef(false)
  const refreshTimer = useRef<ReturnType<typeof setTimeout>>()

  const refresh = useCallback(async () => {
    if (refreshing.current) return
    refreshing.current = true
    setFundRefreshKey(key => key + 1)
    clearTimeout(refreshTimer.current)
    setLoading(true)
    setBreadthLoading(true)
    setBreadthError(null)
    await Promise.all([
      fetchShortTermLimitUpBoard()
        .then(value => {
          if (mounted.current) {
            setSnapshot(value)
            setError(null)
          }
        })
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

  useEffect(() => {
    if (!snapshot?.stocks.length && !snapshot?.limitDownStocks?.length) {
      setContextLoading(false)
      return
    }
    const controller = new AbortController()
    setContextLoading(true)
    void fetchShortTermLimitBoardContext(snapshot.tradeDate, controller.signal)
      .then(context => {
        if (!controller.signal.aborted) setBoardContext(context.tradeDate === snapshot.tradeDate ? context : null)
      })
      .catch(() => { if (!controller.signal.aborted) setBoardContext(null) })
      .finally(() => { if (!controller.signal.aborted) setContextLoading(false) })
    return () => controller.abort()
  }, [snapshot])

  const isDown = view === 'down'
  const boardAvailable = isDown ? snapshot?.limitDownAvailable : snapshot?.available
  const visibleSnapshot = snapshot && view === 'industry' && firstBoardOnly
    ? { ...snapshot, stocks: snapshot.stocks.filter(stock => stock.consecutiveBoards === 1) }
    : snapshot

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden" onKeyDown={event => {
      if ((chartSelection || industrySelection || marketOpen || followUpOpen) && isEscapeClose(event.key, event.defaultPrevented)) {
        event.preventDefault()
        event.stopPropagation()
        setChartSelection(null)
        setIndustrySelection(null)
        setMarketOpen(false)
        setFollowUpOpen(false)
      }
    }}>
      <div className="mb-4 flex shrink-0 flex-wrap items-center justify-between gap-2">
        <div role="group" aria-label="涨停看板视图" className="flex flex-wrap gap-2">
          <Button type="button" variant={view === 'ladder' ? 'primary' : 'secondary'}
            aria-pressed={view === 'ladder'} onClick={() => setView('ladder')}>涨停梯队</Button>
          <Button type="button" variant={view === 'industry' ? 'primary' : 'secondary'}
            aria-pressed={view === 'industry'} onClick={() => setView('industry')}>行业涨停</Button>
          <Button type="button" variant={isDown ? 'primary' : 'secondary'}
            aria-pressed={isDown} onClick={() => setView('down')}>跌停天梯榜</Button>
        </div>
        <div className="flex flex-wrap gap-2">
          {view === 'industry' ? (
            <Button type="button" variant={firstBoardOnly ? 'primary' : 'secondary'} aria-pressed={firstBoardOnly}
              title="仅查看首板涨停个股，再次点击恢复全部涨停"
              onClick={() => { setFirstBoardOnly(value => !value); setIndustrySelection(null) }}>
              首板涨停
            </Button>
          ) : null}
          <Button type="button" icon={<RefreshCw aria-hidden="true" className="h-4 w-4" />}
            loading={loading || breadthLoading} onClick={() => void refresh()}>
            刷新涨停看板
          </Button>
          <Button type="button" icon={<BarChart3 aria-hidden="true" className="h-4 w-4" />}
            aria-haspopup="dialog" aria-expanded={marketOpen} onClick={() => setMarketOpen(true)}>
            市场情绪与行业分布
          </Button>
          <Button type="button" aria-haspopup="dialog" aria-expanded={followUpOpen} onClick={() => setFollowUpOpen(true)}>
            观察与隔日反馈
          </Button>
        </div>
      </div>
      <div className={!snapshot && loading && !error ? 'flex min-h-0 flex-1 items-center justify-center' : 'shrink-0'}>
        <BoardSnapshotStatus snapshot={snapshot} loading={loading} error={error} isDown={isDown} />
      </div>
      {!loading && (error || !boardAvailable) ? (
        <div className="mb-3 shrink-0"><Button type="button" variant="ghost" loading={breadthLoading} onClick={() => void refresh()}>重新加载</Button></div>
      ) : null}
      {snapshot && boardAvailable && view !== 'industry' ? (
        <LimitBoardLadder key={view} snapshot={snapshot} isDown={isDown}
          context={boardContext?.tradeDate === snapshot.tradeDate ? boardContext : null} contextLoading={contextLoading}
          selectedSymbol={chartSelection?.symbol}
          onSelectStock={({ symbol, name }) => setChartSelection({ symbol, name, tradeDate: snapshot.tradeDate })} />
      ) : null}
      {visibleSnapshot?.available && view === 'industry' ? (
        <LimitUpIndustryCards key={`${visibleSnapshot.tradeDate}:${firstBoardOnly}`} snapshot={visibleSnapshot}
          firstBoardOnly={firstBoardOnly} onSelectIndustry={(industry) => {
          setIndustrySelection({ industry, tradeDate: visibleSnapshot.tradeDate, fetchedAt: visibleSnapshot.fetchedAt, firstBoardOnly,
            limitUpStocks: visibleSnapshot.stocks.filter(stock => (stock.industry?.trim() || '未分类') === industry) })
        }} />
      ) : null}
      {marketOpen ? (
        <DetailOverlay open title="市场情绪与行业分布"
          subtitle={snapshot ? `${snapshot.tradeDate} · 快照 ${formatDateTime(snapshot.fetchedAt)}` : undefined}
          onClose={() => setMarketOpen(false)}>
          <LimitUpBoardDetail
            snapshot={snapshot}
            loading={loading}
            error={error}
            breadth={breadth}
            breadthLoading={breadthLoading}
            breadthError={breadthError}
            onRefresh={() => void refresh()}
          />
          <MarketFundFlowPanel refreshKey={fundRefreshKey} />
        </DetailOverlay>
      ) : null}
      {industrySelection ? (
        <LimitUpIndustryOverlay key={`${industrySelection.tradeDate}:${industrySelection.industry}`}
          selection={industrySelection} onClose={() => setIndustrySelection(null)} />
      ) : null}
      {chartSelection ? <StockKlineDialog {...chartSelection} onClose={() => setChartSelection(null)} /> : null}
      {followUpOpen ? (
        <DetailOverlay open title="观察与隔日反馈" onClose={() => setFollowUpOpen(false)}>
          <CatchUpFollowUpPanel />
        </DetailOverlay>
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
}

function BoardSnapshotStatus({ snapshot, loading, error, isDown = false }: Pick<LimitUpBoardDetailProps, 'snapshot' | 'loading' | 'error'> & { isDown?: boolean }) {
  if (error) {
    return <p role="alert" className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs leading-relaxed text-rose-700">
      涨停看板获取失败：{error}{snapshot ? '；当前保留上次快照（旧快照），请注意数据日和获取时间。' : ''}
    </p>
  }
  if (!snapshot) {
    if (loading) return <div role="status" aria-live="polite" className="flex flex-col items-center gap-4 py-6 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-brand-50">
        <Loader2 aria-hidden="true" className="h-8 w-8 animate-spin text-brand-500 motion-reduce:animate-none" />
      </div>
      <p className="text-sm font-medium text-ink-600">正在拉取{isDown ? '跌停' : '涨停'}池数据…</p>
    </div>
    return <p className="py-6 text-center text-sm text-ink-400">暂无数据，请稍后重试。</p>
  }
  if (isDown ? !snapshot.limitDownAvailable : !snapshot.available) {
    return <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
      {isDown ? '跌停池数据暂不可用' : snapshot.unavailableReason ?? '涨停池数据不可用'}
    </p>
  }
  return null
}

/** 市场情绪弹窗内的指标与行情数据。 */
export function LimitUpBoardDetail({ snapshot, loading, error, breadth, breadthLoading, breadthError, onRefresh }: LimitUpBoardDetailProps) {
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
              value={snapshot?.available && snapshot.sentiment?.limitDownCount != null
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
          {snapshot?.available && snapshot.sentiment?.limitDownCount != null ? (
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
      <BoardSnapshotStatus snapshot={snapshot} loading={loading} error={error} />
      {snapshot?.available && snapshot.sentiment ? (
        <>
          <SentimentSummary
            sentiment={snapshot.sentiment}
            turnover={snapshot.marketTurnover}
            stockCount={snapshot.stocks.length}
          />
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

type LadderStock = ShortTermLimitUpStock | ShortTermLimitDownStock

function LimitBoardLadder({ snapshot, isDown, context, contextLoading, selectedSymbol, onSelectStock }: {
  snapshot: ShortTermLimitUpBoardSnapshot
  isDown: boolean
  context: ShortTermLimitBoardContext | null
  contextLoading: boolean
  selectedSymbol?: string
  onSelectStock: (stock: LadderStock) => void
}) {
  const title = isDown ? '跌停天梯榜' : '涨停梯队'
  const direction = isDown ? '跌停' : '涨停'
  const stocks = isDown ? snapshot.limitDownStocks ?? [] : snapshot.stocks
  const details = isDown ? context?.down : context?.up
  const matchingContext = contextLoading && !context && stocks.length > 0
  const byBoardCount = new Map<number, LadderStock[]>()
  for (const stock of stocks) {
    const count = isKnownBoardCount(stock.consecutiveBoards) ? stock.consecutiveBoards : 0
    const group = byBoardCount.get(count)
    if (group) group.push(stock)
    else byBoardCount.set(count, [stock])
  }
  const groups = [...byBoardCount.entries()].sort(([a], [b]) => b - a)

  return (
    <section aria-label={title} className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex shrink-0 flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-ink-900">{title}</h2>
        <p className="text-xs text-ink-500">
          <time dateTime={snapshot.tradeDate}>{snapshot.tradeDate}</time> · {stocks.length} 只{direction} · 行业分类来自东方财富{direction}池
          {context && details && Object.keys(details).length > 0 ? (
            <> · 复盘来源 <a href={context.sourceUrl} target="_blank" rel="noreferrer" className="text-brand-700 hover:underline">{context.source}</a></>
          ) : null}
          {matchingContext ? <span role="status"> · 正在匹配当日题材</span> : null}
        </p>
      </div>
      {groups.length ? (
        <div role="region" aria-label={`${direction}梯队明细`} tabIndex={0}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain rounded-lg border border-line-soft bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-300">
          <div className="divide-y divide-line-soft">
            {groups.map(([count, stocks]) => (
              <div key={count} className="grid sm:grid-cols-[7rem_minmax(0,1fr)]">
                <div className="flex items-baseline gap-x-3 bg-line-soft/50 px-4 py-3 sm:flex-col sm:border-r sm:border-line-soft">
                  <h3 className={`text-lg font-semibold ${isDown ? 'text-emerald-700' : 'text-rose-600'}`}>
                    {count === 0 ? (isDown ? '板数未知' : '连板未知') : count === 1 ? (isDown ? '首跌停' : '首板') : `${count} 板`}
                  </h3>
                  <span className="text-xs text-ink-500">{stocks.length} 家</span>
                </div>
                <ul className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,17rem),1fr))] gap-2 p-2">
                  {stocks.map(stock => {
                    const detail = details?.[stock.symbol]
                    const seal = 'firstSealTime' in stock ? `首封 ${shortTime(stock.firstSealTime)}` : `末封 ${shortTime(stock.lastSealTime)}`
                    return <li key={stock.symbol} className="min-w-0 break-words">
                      <Button type="button" variant="ghost" className="h-full w-full flex-col !items-start !justify-start !border-line-soft !px-3 !py-2 text-left"
                        aria-label={`查看${stock.name}分时和日线`} aria-haspopup="dialog" aria-expanded={stock.symbol === selectedSymbol}
                        title="查看分时和日线" onClick={() => onSelectStock(stock)}>
                        <span className="flex flex-wrap items-baseline gap-x-2">
                          <strong className="text-sm font-semibold text-brand-700">{stock.name}</strong>
                          <span className="tabular text-xs text-ink-500">{stock.symbol}</span>
                        </span>
                        <span className="text-xs font-normal text-ink-500">
                          行业 {stock.industry?.trim() || '未分类'} · {seal}
                        </span>
                        {detail?.topics.length ? (
                          <span className="flex flex-wrap items-center gap-1">
                            {detail.topics.map(topic => <Tag key={topic} tone="brand">{topic}</Tag>)}
                          </span>
                        ) : matchingContext ? null : <span className="text-xs font-normal text-ink-500">暂无当日题材</span>}
                        {detail?.reason || (!isDown && !matchingContext) ? (
                          <span className="text-xs font-normal leading-relaxed text-ink-700">
                            {detail?.reason ? <><strong>复盘原因</strong> {detail.reason}</> : '暂无当日原因'}
                          </span>
                        ) : null}
                      </Button>
                    </li>
                  })}
                </ul>
              </div>
            ))}
          </div>
        </div>
      ) : <p className="text-xs leading-relaxed text-ink-500">当日{direction}池暂无个股。</p>}
    </section>
  )
}

export function LimitUpIndustryCards({ snapshot, onSelectIndustry, firstBoardOnly = false }: {
  snapshot: ShortTermLimitUpBoardSnapshot
  onSelectIndustry?: (industry: string) => void
  firstBoardOnly?: boolean
}) {
  const [page, setPage] = useState(1)
  const scrollRegion = useRef<HTMLDivElement>(null)
  // The API already sorts stocks by board depth and first seal time; keep that order within each industry.
  const byIndustry = new Map<string, ShortTermLimitUpBoardSnapshot['stocks']>()
  for (const stock of snapshot.stocks) {
    const industry = stock.industry?.trim() || '未分类'
    const group = byIndustry.get(industry)
    if (group) group.push(stock)
    else byIndustry.set(industry, [stock])
  }
  const industryGroups = [...byIndustry.entries()].sort((a, b) => b[1].length - a[1].length)
  const pageSize = 6
  const pageCount = Math.max(1, Math.ceil(industryGroups.length / pageSize))
  const currentPage = Math.min(page, pageCount)
  useEffect(() => { setPage(value => Math.min(value, pageCount)) }, [pageCount])
  useEffect(() => { if (scrollRegion.current) scrollRegion.current.scrollTop = 0 }, [currentPage])
  if (!snapshot.stocks.length) {
    return <p className="flex-1 text-xs leading-relaxed text-ink-500">{firstBoardOnly ? '当前快照暂无首板涨停个股。' : '涨停明细暂无数据。'}</p>
  }
  const visibleGroups = industryGroups.slice((currentPage - 1) * pageSize, currentPage * pageSize)

  return (
    <section aria-label="涨停行业卡片" className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex shrink-0 flex-wrap items-baseline justify-between gap-2">
        <h4 className="text-sm font-semibold text-ink-900">{firstBoardOnly ? '首板涨停' : '涨停明细'} · 行业概览</h4>
        <span className="text-xs text-ink-400">{industryGroups.length} 个行业 · {snapshot.stocks.length} 只涨停</span>
      </div>
      <p className="shrink-0 text-[11px] text-ink-500">按行业涨停家数排序，每卡预览前 3 只；梯队按连续涨停板数统计，与“几天几板”分开。点击卡片查看完整明细。</p>
      <div ref={scrollRegion} role="region" aria-label="涨停行业滚动区域" tabIndex={0}
        className="flex min-h-0 flex-1 flex-wrap content-stretch gap-3 overflow-y-auto overscroll-contain rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-300">
        {visibleGroups.map(([industry, stocks]) => {
          const knownBoards = stocks.map(stock => stock.consecutiveBoards).filter(isKnownBoardCount)
          const maxBoards = knownBoards.length ? Math.max(...knownBoards) : null
          const firstBoards = knownBoards.filter(count => count === 1).length
          const secondBoards = knownBoards.filter(count => count === 2).length
          const thirdPlusBoards = knownBoards.filter(count => count >= 3).length
          const unknownBoards = stocks.length - knownBoards.length
          return (
            <button key={industry} type="button" aria-label={`查看${industry}涨停明细`}
              onClick={() => onSelectIndustry?.(industry)}
              className="group flex min-w-0 flex-[1_1_22rem] flex-col rounded-xl border border-line-soft bg-white p-3 text-left outline-none transition hover:border-brand-300 hover:bg-brand-50/30 hover:shadow-sm focus-visible:ring-2 focus-visible:ring-brand-300">
              <span className="flex items-center justify-between gap-2">
                <span className="min-w-0 truncate text-sm font-semibold text-ink-900" title={industry}>{industry}</span>
                <Tag tone="warning" className="shrink-0">涨停 {stocks.length} 家</Tag>
              </span>
              <span className="mt-2 grid grid-cols-3 gap-1 text-[11px] text-ink-600">
                <span>首板 {firstBoards} 家</span>
                <span>二板 {secondBoards} 家</span>
                <span>三板及以上 {thirdPlusBoards} 家</span>
              </span>
              {unknownBoards > 0 ? <span className="mt-1 text-[11px] text-amber-700">连板未知 {unknownBoards} 家</span> : null}
              <span className="mt-2 flex items-center justify-between gap-2 text-[11px]">
                <span className={maxBoards !== null && maxBoards >= 3 ? 'font-medium text-rose-600' : 'text-ink-500'}>
                  {maxBoards === null ? '最高连板未知' : `最高 ${maxBoards} 板`}
                </span>
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
                        {stock.statDays > 0 && stock.statBoards > 0 ? `${stock.statDays}天${stock.statBoards}板`
                          : isKnownBoardCount(stock.consecutiveBoards) ? `${stock.consecutiveBoards} 板` : '连板未知'}
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
      <CardPagination page={currentPage} pageSize={pageSize} total={industryGroups.length} onPageChange={setPage}
        ariaLabel="涨停行业分页" unit="个行业" />
    </section>
  )
}

function isKnownBoardCount(value: number | null): value is number {
  return value !== null && Number.isInteger(value) && value > 0
}

function shortTime(value: string | null) {
  return value ? value.slice(0, 5) : '—'
}
