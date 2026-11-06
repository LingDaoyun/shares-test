import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronRight, Newspaper, Radar, RefreshCw, Search } from 'lucide-react'
import { Link } from 'react-router-dom'
import { fetchHotspotSnapshot, refreshHotspots } from '../api/hotspots'
import { addToWatchlist } from '../api/client'
import { Button } from '../components/ui/Button'
import { Tag } from '../components/ui/Badge'
import { Card } from '../components/ui/Card'
import { DetailOverlay } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { NewsSources, RecommendationEvidence, eventTypeLabel, relationLabel } from '../components/hotspot/HotspotEvidence'
import { changeClass, extractErrorMessage, formatDateTime, formatSignedPercent } from '../lib/format'
import type { HotspotEvent, HotspotRecommendation, HotspotSnapshot } from '../types/hotspot'

const statusLabels: Record<HotspotSnapshot['status'], string> = {
  IDLE: '等待首次采集', RUNNING: '采集与研判中', READY: '本轮已更新', PARTIAL: '部分资料待补齐', FAILED: '本轮更新失败'
}

export function HotspotPage() {
  const [snapshot, setSnapshot] = useState<HotspotSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [view, setView] = useState<'events' | 'news'>('events')
  const [onlyRecommendations, setOnlyRecommendations] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [added, setAdded] = useState<string[]>([])
  const [adding, setAdding] = useState<Record<string, boolean>>({})
  const [addErrors, setAddErrors] = useState<Record<string, string>>({})
  const [now, setNow] = useState(() => Date.now())
  const mounted = useRef(false)
  const requestId = useRef(0)

  const load = useCallback(async () => {
    const id = ++requestId.current
    try {
      const next = await fetchHotspotSnapshot()
      if (!mounted.current || id !== requestId.current) return
      setSnapshot(next)
      setError('')
    } catch (reason) {
      if (mounted.current && id === requestId.current) setError(`热点结果读取失败：${extractErrorMessage(reason)}`)
    } finally {
      if (mounted.current && id === requestId.current) { setLoading(false); setNow(Date.now()) }
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; requestId.current++ }
  }, [load])

  useEffect(() => {
    if (starting) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      await load()
      if (!stopped) timer = setTimeout(() => void poll(), snapshot?.status === 'RUNNING' ? 5000 : 60000)
    }
    timer = setTimeout(() => void poll(), snapshot?.status === 'RUNNING' ? 5000 : 60000)
    return () => { stopped = true; clearTimeout(timer) }
  }, [load, snapshot?.status, starting])

  const refresh = async () => {
    const id = ++requestId.current
    setStarting(true)
    setError('')
    try {
      const next = await refreshHotspots()
      if (mounted.current && id === requestId.current) setSnapshot(next)
    } catch (reason) {
      if (mounted.current && id === requestId.current) setError(`热点更新未能启动：${extractErrorMessage(reason)}。可读取结果确认任务状态。`)
    } finally {
      if (mounted.current) { setStarting(false); setLoading(false); setNow(Date.now()) }
    }
  }

  const addStock = async (event: HotspotEvent, stock: HotspotRecommendation) => {
    if (adding[stock.symbol] || added.includes(stock.symbol)) return
    setAdding(previous => ({ ...previous, [stock.symbol]: true }))
    setAddErrors(previous => ({ ...previous, [stock.symbol]: '' }))
    try {
      await addToWatchlist(stock.symbol, `热点追踪：${event.title}；${relationLabel(stock.relationType)}；${stock.recommendationReason}`.slice(0, 500))
      if (mounted.current) setAdded(previous => [...new Set([...previous, stock.symbol])])
    } catch (reason) {
      if (mounted.current) setAddErrors(previous => ({ ...previous, [stock.symbol]: extractErrorMessage(reason) }))
    } finally {
      if (mounted.current) setAdding(previous => ({ ...previous, [stock.symbol]: false }))
    }
  }

  const events = snapshot?.events ?? []
  const news = snapshot?.news ?? []
  const active = events.find(event => event.id === selectedId)
  const query = search.trim().toLowerCase()
  const visibleEvents = events.filter(event => (!onlyRecommendations || event.recommendations.length > 0)
    && (!query || [event.title, event.summary, ...event.industries, ...event.products, ...event.recommendations.flatMap(stock => [stock.symbol, stock.name, stock.industry ?? ''])].join(' ').toLowerCase().includes(query)))
  const visibleNews = news.filter(item => !query || [item.title, item.source, item.excerpt].join(' ').toLowerCase().includes(query))
  const running = snapshot?.status === 'RUNNING'
  const refreshAt = snapshot?.refreshAvailableAt ? Date.parse(snapshot.refreshAvailableAt) : 0
  const coolingDown = Number.isFinite(refreshAt) && refreshAt > now

  return <div className="flex flex-col gap-4">
    <header className="card px-5 py-5 sm:px-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div><div className="mb-1 flex items-center gap-2 text-xs font-medium text-brand-600"><Radar aria-hidden="true" className="h-4 w-4" />产业 · 产品 · A 股</div><h1 className="text-2xl font-bold tracking-tight text-ink-900">热点追踪</h1><p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-600">从产业与产品新闻发现催化，沿产业链寻找相关股票，保留每一次推荐的依据。</p></div>
        <div className="flex flex-wrap gap-2"><Button variant="ghost" disabled={loading || starting} onClick={() => void load()}>读取结果</Button><Button variant="primary" loading={starting || running} disabled={coolingDown} icon={<RefreshCw aria-hidden="true" className="h-4 w-4" />} onClick={() => void refresh()}>{running ? '正在追踪' : '更新热点与推荐'}</Button></div>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line-soft pt-3 text-xs text-ink-500">
        <Tag tone={snapshot?.status === 'FAILED' ? 'warning' : running ? 'brand' : 'neutral'}>{snapshot ? statusLabels[snapshot.status] : '读取中'}</Tag>
        <span>热点 {events.length} 个</span><span>推荐股票 {snapshot?.companyCount ?? 0} 只</span><span>新闻样本 {snapshot?.newsCount ?? 0} 条</span>
        <span className="sm:ml-auto">{snapshot?.generatedAt ? `结果更新 ${formatDateTime(snapshot.generatedAt)}` : '尚无已保存的研判结果'}</span>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-ink-400">
        {snapshot?.nextAutomaticRefreshAt ? <span>下次自动更新 {formatDateTime(snapshot.nextAutomaticRefreshAt)}</span> : <span>服务运行时自动追踪；首次使用也可立即更新。</span>}
        {coolingDown && !running ? <span>手动更新可用时间 {formatDateTime(snapshot?.refreshAvailableAt)}</span> : null}
        <Link to="/settings" className="text-brand-600 hover:underline">模型配置</Link>
      </div>
    </header>

    {error ? <div role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">{error}</div> : null}
    {running ? <p role="status" className="text-xs leading-relaxed text-brand-700">正在采集新闻、识别产业机会并核对公司资料。已有结果继续保留，完成后自动更新。</p> : null}
    {snapshot?.summary ? <p className="px-1 text-sm leading-relaxed text-ink-600">{snapshot.summary}</p> : null}

    <div className="flex flex-wrap items-center gap-3">
      <div className="flex gap-1 rounded-lg border border-line bg-white p-1" role="group" aria-label="热点内容切换">
        <button type="button" aria-pressed={view === 'events'} onClick={() => setView('events')} className={`rounded-md px-3 py-1.5 text-sm font-medium ${view === 'events' ? 'bg-brand-50 text-brand-700' : 'text-ink-500'}`}>热点与推荐</button>
        <button type="button" aria-pressed={view === 'news'} onClick={() => setView('news')} className={`rounded-md px-3 py-1.5 text-sm font-medium ${view === 'news' ? 'bg-brand-50 text-brand-700' : 'text-ink-500'}`}>原始新闻</button>
      </div>
      <div className="relative min-w-[200px] flex-1"><Search aria-hidden="true" className="absolute left-3 top-2.5 h-4 w-4 text-ink-400" /><input value={search} onChange={event => setSearch(event.target.value)} aria-label="搜索热点、产品或股票" placeholder="搜索热点、产品、产业或股票" className="w-full rounded-lg border border-line bg-white py-2 pl-9 pr-3 text-sm outline-none focus:border-brand-400" /></div>
      {view === 'events' ? <label className="flex items-center gap-2 text-xs text-ink-500"><input type="checkbox" checked={onlyRecommendations} onChange={event => setOnlyRecommendations(event.target.checked)} className="accent-brand-600" />有股票推荐</label> : null}
    </div>

    {loading && !snapshot ? <Loader text="正在读取热点与股票推荐" /> : view === 'events' ? <>
      <div className="flex flex-col gap-4">{visibleEvents.map(event => <EventCard key={event.id} event={event} onOpen={() => setSelectedId(event.id)} />)}</div>
      {!visibleEvents.length ? <Card><div className="py-7 text-center"><Radar aria-hidden="true" className="mx-auto mb-3 h-7 w-7 text-brand-400" /><h2 className="text-base font-semibold text-ink-800">{events.length ? '没有符合条件的热点' : news.length ? '新闻已收集，等待形成有依据的推荐' : '从产业和产品新闻开始追踪'}</h2><p className="mx-auto mt-2 max-w-lg text-sm leading-relaxed text-ink-500">{events.length ? '尝试更换关键词，或取消“有股票推荐”筛选。' : news.length ? '可先查看原始新闻。模型配置、公司资料或关联依据不足时，页面会保留新闻并说明缺口。' : '更新后会展示新闻事件、受影响的产业链，以及对应股票的推荐理由。'}</p>{news.length && !events.length ? <Button className="mt-4" onClick={() => setView('news')}>查看已采集新闻</Button> : null}</div></Card> : null}
    </> : <Card title={<span className="flex items-center gap-2"><Newspaper aria-hidden="true" className="h-4 w-4" />近期新闻 · {visibleNews.length} 条</span>}>
      <p className="mb-4 text-xs leading-relaxed text-ink-400">保留媒体提供的发布时间与摘要。新闻样本包括尚未形成股票推荐的线索；标题与摘要不等于全文核验。</p>
      {visibleNews.length ? <NewsSources news={visibleNews} excerpts /> : <p className="py-6 text-center text-sm text-ink-500">{news.length ? '没有匹配关键词的新闻。' : '本轮暂无可用新闻，请查看采集说明或重新更新。'}</p>}
    </Card>}

    {snapshot?.dataGaps.length ? <details className="rounded-lg border border-line bg-white px-4 py-3 text-xs text-ink-500">
      <summary className="cursor-pointer font-medium">采集与推荐说明 · {snapshot.dataGaps.length} 项</summary><ul className="mt-3 list-disc space-y-2 pl-4 leading-relaxed">{snapshot.dataGaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul>
    </details> : null}

    {active ? <DetailOverlay open title={active.title} subtitle="新闻事实 · 产业传导 · 关联股票推荐" onClose={() => setSelectedId(null)}>
      <div className="flex flex-col gap-5">
        <div><div className="flex flex-wrap gap-1.5"><Tag tone="brand">{eventTypeLabel(active.eventType)}</Tag>{[...new Set([...active.products, ...active.industries])].map(item => <Tag key={item}>{item}</Tag>)}</div><p className="mt-3 text-sm leading-relaxed text-ink-700">{active.summary}</p><div className="mt-3 rounded-lg bg-brand-50 p-3"><p className="text-xs font-medium text-brand-700">产业传导 · AI 推演</p><p className="mt-1 text-sm leading-relaxed text-ink-700">{active.catalyst}</p></div><p className="mt-2 text-[11px] text-ink-400">首次发现 {formatDateTime(active.firstSeenAt)} · 事件更新 {formatDateTime(active.updatedAt)}</p></div>
        <section><h3 className="mb-3 text-sm font-semibold text-ink-900">关联股票推荐 · {active.recommendations.length} 只</h3><p className="mb-3 text-xs leading-relaxed text-ink-500">推荐依据是新闻和产业关联；新闻提及与主营匹配均不自动代表已取得该产品订单。</p><div className="flex flex-col gap-3">{active.recommendations.map(stock => <RecommendationEvidence key={stock.symbol} recommendation={stock} added={added.includes(stock.symbol)} saving={adding[stock.symbol] === true} error={addErrors[stock.symbol]} onAdd={() => void addStock(active, stock)} />)}</div>{!active.recommendations.length ? <p className="rounded-lg bg-line-soft p-4 text-sm text-ink-500">暂未取得足够公司关联依据，保留热点继续跟踪。</p> : null}</section>
        <section><h3 className="mb-3 text-sm font-semibold text-ink-900">新闻来源</h3><NewsSources news={active.sources} excerpts /></section>
        {active.risks.length ? <section className="rounded-lg border border-amber-100 bg-amber-50/60 p-4"><h3 className="text-xs font-semibold text-amber-900">后续关注与风险</h3><ul className="mt-2 list-disc space-y-1 pl-4 text-xs leading-relaxed text-amber-800">{active.risks.map((risk, index) => <li key={index}>{risk}</li>)}</ul></section> : null}
      </div>
    </DetailOverlay> : null}
  </div>
}

function EventCard({ event, onOpen }: { event: HotspotEvent; onOpen: () => void }) {
  return <article className="card overflow-hidden">
    <div className="grid lg:grid-cols-[minmax(0,1fr)_minmax(280px,0.8fr)]">
      <div className="min-w-0 p-5">
        <div className="flex flex-wrap items-center gap-2"><Tag tone="brand">{eventTypeLabel(event.eventType)}</Tag><span className="text-[11px] text-ink-400">{event.sources.length} 条新闻依据</span></div>
        <h2 className="mt-3 text-lg font-semibold leading-snug text-ink-900"><button type="button" onClick={onOpen} className="text-left hover:text-brand-600 focus-visible:rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400">{event.title}</button></h2>
        <div className="mt-2 flex flex-wrap gap-1.5">{[...new Set([...event.products, ...event.industries])].slice(0, 6).map(item => <Tag key={item}>{item}</Tag>)}</div>
        <p className="mt-3 text-sm leading-relaxed text-ink-600">{event.summary}</p>
        <div className="mt-3 border-l-2 border-brand-200 pl-3"><p className="text-[11px] font-medium text-brand-600">产业传导 · AI 推演</p><p className="mt-1 text-xs leading-relaxed text-ink-600">{event.catalyst}</p></div>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-2"><span className="text-[11px] text-ink-400">首次发现 {formatDateTime(event.firstSeenAt)}</span><Button variant="ghost" onClick={onOpen} aria-label={`查看${event.title}的推荐与依据`}>查看推荐与依据<ChevronRight aria-hidden="true" className="h-4 w-4" /></Button></div>
      </div>
      <div className="min-w-0 border-t border-line-soft bg-line-soft/35 p-5 lg:border-l lg:border-t-0">
        <h3 className="mb-3 text-xs font-semibold text-ink-500">关联股票推荐</h3>
        {event.recommendations.length ? <ul className="space-y-3">{event.recommendations.map(stock => <li key={stock.symbol} className="rounded-lg border border-line-soft bg-white p-3">
          <button type="button" onClick={onOpen} className="flex w-full flex-wrap items-center gap-x-2 gap-y-1 text-left focus-visible:rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400"><span className="text-sm font-semibold text-ink-900">{stock.name}</span><span className="tabular text-[11px] text-ink-400">{stock.symbol}</span><span className={`tabular ml-auto text-sm font-semibold ${changeClass(stock.changePercent)}`}>{formatSignedPercent(stock.changePercent)}</span></button>
          <p className="mt-1.5 text-[11px] text-brand-600">{relationLabel(stock.relationType)}</p><p className="mt-1 text-xs leading-relaxed text-ink-600">{stock.recommendationReason}</p><p className="mt-2 truncate text-[10px] text-ink-400" title={stock.quoteAsOf || '报价时间待补充'}>报价 {stock.quoteAsOf || '时间待补充'}</p>
        </li>)}</ul> : <p className="text-sm leading-relaxed text-ink-500">关联公司资料尚不足，先保留新闻线索继续跟踪。</p>}
      </div>
    </div>
  </article>
}
