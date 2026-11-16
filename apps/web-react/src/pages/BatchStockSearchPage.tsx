import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ChevronRight, Search, SlidersHorizontal } from 'lucide-react'
import { searchStocks } from '../api/client'
import { StockSearchDetail } from '../components/stocksearch/StockSearchDetail'
import { AdvancedStockSearchDialog } from '../components/stocksearch/AdvancedStockSearchDialog'
import { describeStockSearchFilters, parseStockSearchFilters, type StockSearchFilterDraft } from '../components/stocksearch/StockSearchFilterFields'
import { Button } from '../components/ui/Button'
import { CardPagination } from '../components/ui/CardPagination'
import { toast } from '../components/ui/Toast'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPercent, formatSignedPercent } from '../lib/format'
import type { StockSearchFilters, StockSearchQuote, StockSearchSnapshot } from '../types'

const INITIAL_PAGE_SIZE = 12
const SEARCH_TOAST_KEY = 'batch-stock-search'
const DEFAULT_FILTERS: StockSearchFilterDraft = { excludeChiNext: true, excludeStar: true, excludeSt: true }
type SearchQuery = { keyword: string; filters: StockSearchFilters }

export function BatchStockSearchPage() {
  const [keyword, setKeyword] = useState('')
  const [draft, setDraft] = useState<StockSearchFilterDraft>(() => ({ ...DEFAULT_FILTERS }))
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [result, setResult] = useState<StockSearchSnapshot | null>(null)
  const [{ page, pageSize }, setPagination] = useState({ page: 1, pageSize: INITIAL_PAGE_SIZE })
  const [selected, setSelected] = useState<StockSearchQuote | null>(null)
  const [loading, setLoading] = useState(false)
  const request = useRef<AbortController | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const gridRef = useRef<HTMLElement | null>(null)
  const cardMeasurement = useRef({ width: 0, height: 0 })
  const setPage = (nextPage: number) => setPagination(current => ({ ...current, page: nextPage }))
  useEffect(() => () => { request.current?.abort(); toast.dismiss(SEARCH_TOAST_KEY) }, [])
  useEffect(() => { if (listRef.current) listRef.current.scrollTop = 0 }, [page, pageSize, result])
  useLayoutEffect(() => { cardMeasurement.current = { width: 0, height: 0 } }, [result])
  useLayoutEffect(() => {
    const viewport = listRef.current
    const grid = gridRef.current
    if (!viewport || !grid || !result?.stocks.length) return
    const cards = Array.from(grid.children)
    const measure = () => {
      const style = window.getComputedStyle(grid)
      const tracks = style.gridTemplateColumns
      const rects = cards.map(card => card.getBoundingClientRect())
      const width = rects[0]?.width ?? 0
      const measuredHeight = Math.max(0, ...rects.map(rect => rect.height))
      if (!tracks || tracks === 'none' || viewport.clientHeight <= 0 || width <= 0 || measuredHeight <= 0) return
      // Keep the tallest measured card at this width so paging cannot oscillate as wrapped cards enter/leave.
      const previous = cardMeasurement.current
      const cardHeight = Math.abs(previous.width - width) < 0.5 ? Math.max(previous.height, measuredHeight) : measuredHeight
      cardMeasurement.current = { width, height: cardHeight }
      const columns = tracks.trim().split(/\s+/).length
      const gap = Number.parseFloat(style.rowGap) || 0
      const rows = Math.max(1, Math.floor((viewport.clientHeight + gap) / (cardHeight + gap)))
      const nextSize = columns * rows
      setPagination(current => {
        if (current.pageSize === nextSize) return current
        const firstIndex = (current.page - 1) * current.pageSize
        const lastPage = Math.max(1, Math.ceil(result.stocks.length / nextSize))
        return { pageSize: nextSize, page: Math.min(lastPage, Math.floor(firstIndex / nextSize) + 1) }
      })
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    let frame = 0
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(measure)
    })
    observer.observe(viewport)
    observer.observe(grid)
    cards.forEach(card => observer.observe(card))
    return () => { observer.disconnect(); cancelAnimationFrame(frame) }
  }, [result, page, pageSize])

  async function search(query: SearchQuery) {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setResult(null)
    setSelected(null)
    setPage(1)
    setLoading(true)
    toast.loading('正在搜索 A 股，请稍候…', { key: SEARCH_TOAST_KEY })
    try {
      const data = await searchStocks(query.keyword, true, controller.signal, query.filters)
      if (!controller.signal.aborted) {
        setResult(data)
        const label = data.keyword ? `“${data.keyword}”` : '条件筛选'
        const summary = `${label} 共 ${data.matchedCount} 只。${describeStockSearchFilters(query.filters)}。更新于 ${formatDateTime(data.fetchedAt)} · 按股票代码排序。`
        const gaps: string[] = []
        if (!data.complete) gaps.push(`股票清单尚未完整（${data.universeCount} / ${data.expectedCount > 0 ? data.expectedCount : '未知'} 只），可能有遗漏`)
        if ((data.unverifiedCount ?? 0) > 0) gaps.push(`${data.unverifiedCount} 只股票缺少筛选所需指标，暂未纳入结果`)
        if (gaps.length) toast.warning(`${summary}${gaps.join('；')}。可重新搜索。`, { key: SEARCH_TOAST_KEY, durationMs: 8000 })
        else if (!data.stocks.length) toast.info(`${summary}没有找到匹配股票，请调整条件。`, { key: SEARCH_TOAST_KEY, durationMs: 6000 })
        else toast.success(summary, { key: SEARCH_TOAST_KEY, durationMs: 6000 })
      }
    } catch (reason) {
      if (!controller.signal.aborted) toast.error('搜索失败：' + extractErrorMessage(reason) + '。请重新搜索。', { key: SEARCH_TOAST_KEY, durationMs: 6000 })
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }

  function submit() {
    try {
      const filters = parseStockSearchFilters(draft)
      const term = keyword.trim()
      if (!term && !Object.keys(filters).length) throw new Error('请输入关键词或设置至少一个筛选条件')
      void search({ keyword: term, filters })
    } catch (reason) {
      toast.warning(extractErrorMessage(reason), { key: SEARCH_TOAST_KEY })
    }
  }

  const visibleStocks = result?.stocks.slice((page - 1) * pageSize, page * pageSize) ?? []
  const incomplete = result && (!result.complete || (result.unverifiedCount ?? 0) > 0)

  return <div className="flex h-full min-h-0 flex-col gap-4">
    <section className="flex shrink-0 flex-col gap-4 border-b border-line-soft pb-4 lg:flex-row lg:items-center lg:gap-6" aria-label="批量搜股">
      <h1 className="shrink-0 text-lg font-bold text-ink-900">批量搜股</h1>
      <form className="min-w-0 flex-1" onSubmit={event => { event.preventDefault(); submit() }}>
        <div className="flex flex-col gap-2 sm:flex-row">
          <label className="relative min-w-0 flex-1">
            <span className="sr-only">股票名称、代码或拼音</span>
            <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-ink-400" />
            <input type="search" className="field pl-9" value={keyword} maxLength={80}
              placeholder="名称、代码或拼音，如羊 / yang" onChange={event => setKeyword(event.target.value)} />
          </label>
          <div className="grid grid-cols-2 gap-2 sm:flex sm:shrink-0">
            <Button type="button" aria-haspopup="dialog" aria-expanded={advancedOpen}
              icon={<SlidersHorizontal className="h-4 w-4" />} onClick={() => setAdvancedOpen(true)}>高级搜索</Button>
            <Button type="submit" variant="primary" loading={loading} icon={<Search className="h-4 w-4" />}>
              {loading ? '搜索中' : '搜索股票'}
            </Button>
          </div>
        </div>
      </form>
    </section>

    <div ref={listRef} role="region" aria-label="股票列表滚动区域" aria-busy={loading} tabIndex={0}
      className="min-h-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-gutter:stable_both-edges] focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-brand-400">
      {!loading && !result ? <div className="flex h-full flex-col items-center justify-center px-4 text-center">
        <Search aria-hidden="true" className="h-7 w-7 text-ink-300" />
        <p className="mt-3 text-sm text-ink-600">输入关键词，或通过高级搜索设置条件</p>
      </div> : null}
      {result ? !result.stocks.length ? <p className="flex h-full items-center justify-center px-4 text-center text-sm text-ink-500">
        {incomplete ? '当前可核实的数据中没有匹配股票，请重新搜索或调整条件。' : '没有找到匹配股票，请调整关键词或筛选条件。'}
      </p> : <section ref={gridRef} aria-label="股票搜索结果" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,16rem),1fr))] items-start gap-2">
        {visibleStocks.map(stock => <StockCard key={stock.symbol} stock={stock} onOpen={() => setSelected(stock)} />)}
      </section> : null}
    </div>
    {result?.stocks.length ? <CardPagination page={page} pageSize={pageSize} total={result.stocks.length}
      onPageChange={setPage} ariaLabel="股票结果分页" /> : null}
    {advancedOpen ? <AdvancedStockSearchDialog initialValue={draft} onClose={() => setAdvancedOpen(false)}
      onApply={value => { setDraft(value); setAdvancedOpen(false) }} /> : null}
    {selected ? <StockSearchDetail key={selected.symbol} stock={selected} onClose={() => setSelected(null)} /> : null}
  </div>
}

function StockCard({ stock, onOpen }: { stock: StockSearchQuote; onOpen: () => void }) {
  return <article aria-label={stock.name + ' ' + stock.symbol} className="group relative flex min-w-0 flex-col rounded-xl border border-line bg-white p-2.5 transition-colors hover:border-brand-300 hover:bg-brand-50/20 focus-within:border-brand-400">
    <div className="flex items-start justify-between gap-2">
      <div className="min-w-0">
        <h3 className="break-words text-sm font-semibold text-ink-900 group-hover:text-brand-700">
          <button type="button" aria-label={'查看' + stock.name + '详情'} aria-haspopup="dialog" onClick={onOpen}
            className="text-left outline-none after:absolute after:inset-0 after:rounded-xl after:content-[''] focus-visible:after:ring-2 focus-visible:after:ring-brand-400">
            {stock.name}
          </button>
        </h3>
        <p className="mt-0.5 text-[11px] text-ink-400"><span className="font-mono">{stock.symbol}</span>{stock.market ? ' · ' + stock.market : ''}</p>
      </div>
      <div className={'shrink-0 text-right tabular ' + changeClass(stock.changePercent)}>
        <p className="text-lg font-semibold leading-5" aria-label="最新价">{formatNumber(stock.latestPrice)}</p>
        <p className="text-xs font-semibold" aria-label="涨跌幅">{formatSignedPercent(stock.changePercent)}</p>
      </div>
    </div>
    <dl className="my-2 grid grid-cols-2 gap-x-3 gap-y-1 border-t border-line-soft pt-2 text-[11px] tabular">
      {([
        ['换手率', formatPercent(stock.turnoverRate)], ['总市值', formatAmount(stock.totalMarketValue)],
        ['流通市值', formatAmount(stock.circulatingMarketValue)], ['成交额', formatAmount(stock.amount)]
      ] as const).map(([label, value]) => <div key={label} className="flex flex-wrap items-baseline justify-between gap-1">
        <dt className="text-ink-600">{label}</dt><dd className="font-semibold text-ink-900">{value}</dd>
      </div>)}
    </dl>
    <div className="flex items-center justify-between gap-2 border-t border-line-soft pt-1.5 text-[10px] text-ink-400">
      <span>行情 {stock.marketTimestamp ? formatDateTime(stock.marketTimestamp).slice(5) : stock.tradeDate ?? '时间待补充'}</span>
      <span className="inline-flex shrink-0 items-center text-brand-600">查看详情<ChevronRight aria-hidden="true" className="h-3 w-3" /></span>
    </div>
  </article>
}
