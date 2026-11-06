import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ArrowDown, ExternalLink, RefreshCw, Search } from 'lucide-react'
import { fetchFinancialNews, refreshFinancialNews, analyzeFinancialNews } from '../../api/news'
import { useAppStore } from '../../store/appStore'
import { extractErrorMessage } from '../../lib/format'
import { policySourceUrl } from '../../lib/policyTrend'
import { Button } from '../ui/Button'
import { Tag } from '../ui/Badge'
import { Loader } from '../ui/Loader'
import { DetailOverlay } from '../ui/DetailOverlay'
import { NewsItemDetail } from './NewsItemDetail'
import { newsCategory, newsDate, newsDay, newsTimestamp } from './newsFormat'
import { NEWS_TOPICS, classifyNewsTopic, type NewsTopicId } from './newsTopics'
import type { FinancialNewsItem, FinancialNewsSnapshot, NewsAnalysis, NewsCategory } from '../../types/news'

const statusLabels: Record<FinancialNewsSnapshot['status'], string> = { IDLE: '等待首次采集', RUNNING: '正在更新消息', READY: '已更新', PARTIAL: '部分来源待更新', FAILED: '本轮更新失败' }
const sourceLabels = { READY: '已读取', EMPTY: '暂无有效消息', FAILED: '更新失败' }

function sameNews(first: FinancialNewsItem | undefined, second: FinancialNewsItem | undefined) {
  return !!first && !!second && first.id === second.id && first.url === second.url && first.title === second.title && first.summary === second.summary
}

function mergeAnalyses(previous: Record<string, NewsAnalysis>, incoming: Record<string, NewsAnalysis>) {
  const merged = { ...previous }
  for (const [id, analysis] of Object.entries(incoming)) {
    if (!analysis.analyzedAt) continue
    const stored = merged[id]
    if (!stored?.analyzedAt || Date.parse(analysis.analyzedAt) >= Date.parse(stored.analyzedAt)) merged[id] = analysis
  }
  return merged
}

export function FinancialNewsPanel({ heading, navigation }: { heading?: ReactNode; navigation?: ReactNode }) {
  const [snapshot, setSnapshot] = useState<FinancialNewsSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState<NewsCategory | 'ALL'>('ALL')
  const [source, setSource] = useState('ALL')
  const [days, setDays] = useState(30)
  const [topic, setTopic] = useState<NewsTopicId | 'ALL'>('ALL')
  const [displayLimit, setDisplayLimit] = useState(60)
  const [selected, setSelected] = useState<string | null>(null)
  const [analyzing, setAnalyzing] = useState<Record<string, boolean>>({})
  const [analysisErrors, setAnalysisErrors] = useState<Record<string, string>>({})
  const aiEnabled = useAppStore(state => state.llmConfig?.apiKeyConfigured === true)
  const mounted = useRef(false)
  const requestId = useRef(0)
  const listRef = useRef<HTMLDivElement>(null)

  const acceptSnapshot = useCallback((next: FinancialNewsSnapshot) => {
    setSnapshot(previous => {
      const previousItems = new Map(previous?.items.map(item => [item.id, item]))
      const retained: Record<string, NewsAnalysis> = {}
      const incoming: Record<string, NewsAnalysis> = {}
      for (const item of next.items) {
        const cached = previous?.analyses[item.id]
        if (cached && sameNews(previousItems.get(item.id), item)) retained[item.id] = cached
        if (next.analyses?.[item.id]) incoming[item.id] = next.analyses[item.id]
      }
      return { ...next, analyses: mergeAnalyses(retained, incoming) }
    })
  }, [])

  const load = useCallback(async () => {
    const id = ++requestId.current
    try {
      const next = await fetchFinancialNews()
      if (!mounted.current || id !== requestId.current) return
      acceptSnapshot(next)
      setError('')
    } catch (reason) {
      if (mounted.current && id === requestId.current) setError(`消息读取失败：${extractErrorMessage(reason)}`)
    } finally {
      if (mounted.current && id === requestId.current) setLoading(false)
    }
  }, [acceptSnapshot])

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
      const next = await refreshFinancialNews()
      if (mounted.current && id === requestId.current) acceptSnapshot(next)
    } catch (reason) {
      if (mounted.current && id === requestId.current) setError(`消息更新未能启动：${extractErrorMessage(reason)}。可读取结果确认状态。`)
    } finally {
      if (mounted.current) { setStarting(false); setLoading(false) }
    }
  }

  const analyze = async (id: string) => {
    if (analyzing[id] || snapshot?.analyses[id]) return
    const requestedItem = snapshot?.items.find(item => item.id === id)
    setAnalyzing(previous => ({ ...previous, [id]: true }))
    setAnalysisErrors(previous => ({ ...previous, [id]: '' }))
    try {
      const next = await analyzeFinancialNews(id)
      if (!mounted.current) return
      if (!next.analyzedAt) {
        setAnalysisErrors(previous => ({ ...previous, [id]: next.dataGaps.join('；') || '未得到有效解读，可以重试。' }))
      } else {
        setSnapshot(previous => previous && sameNews(requestedItem, previous.items.find(item => item.id === id))
          ? { ...previous, analyses: mergeAnalyses(previous.analyses, { [id]: next }) } : previous)
      }
    } catch (reason) {
      if (mounted.current) setAnalysisErrors(previous => ({ ...previous, [id]: `解读失败：${extractErrorMessage(reason)}` }))
    } finally {
      if (mounted.current) setAnalyzing(previous => ({ ...previous, [id]: false }))
    }
  }

  const items = snapshot?.items ?? []
  const classified = useMemo(() => items.map(item => ({ item, topic: classifyNewsTopic(item) })), [items])
  const active = items.find(item => item.id === selected)
  const today = newsDay(new Date().toISOString())!
  const yesterday = new Date(Date.parse(today) - 86400000).toISOString().slice(0, 10)
  const query = search.trim().toLowerCase()
  const filtered = classified.filter(({ item }) => {
    const day = newsDay(item.publishedAt)
    const age = day ? (Date.parse(today) - Date.parse(day)) / 86400000 : null
    return (age === null ? days === 30 : days === -1 ? age === 1 : age >= 0 && age < days)
      && (category === 'ALL' || item.category === category)
      && (source === 'ALL' || item.source === source)
      && (!query || [item.title, item.summary, item.source].join(' ').toLowerCase().includes(query))
  })
  const topicCounts = new Map<NewsTopicId, number>()
  filtered.forEach(row => topicCounts.set(row.topic, (topicCounts.get(row.topic) ?? 0) + 1))
  const visible = filtered.filter(row => topic === 'ALL' || row.topic === topic)
    .sort((a, b) => (newsTimestamp(b.item.publishedAt) || -Infinity) - (newsTimestamp(a.item.publishedAt) || -Infinity))
  const groups = new Map<string, typeof visible>()
  visible.slice(0, displayLimit).forEach(row => {
    const day = newsDay(row.item.publishedAt) ?? 'unknown'
    const group = groups.get(day) ?? []
    group.push(row); groups.set(day, group)
  })
  useEffect(() => {
    setDisplayLimit(60)
    if (listRef.current) listRef.current.scrollTop = 0
  }, [topic, days, category, source, search])
  const sources = [...new Set([...items.map(item => item.source), ...(snapshot?.sources.map(item => item.name) ?? [])])]
  const running = snapshot?.status === 'RUNNING'
  const currentTopic = NEWS_TOPICS.find(item => item.id === topic)
  const healthySources = snapshot?.sources.filter(item => item.status === 'READY').length ?? 0

  return <div className="flex min-w-0 flex-col gap-4 lg:h-[calc(100dvh-2rem)] lg:min-h-[36rem]">
    <header className="grid shrink-0 gap-4 border-b border-line-soft pb-4 lg:grid-cols-[12.5rem_minmax(0,1fr)] lg:gap-5">
      <div className="min-w-0">{heading}{navigation ? <div className="mt-3">{navigation}</div> : null}</div>
      <section aria-label="消息筛选与刷新" className="min-w-0">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div><div className="flex items-baseline gap-2.5"><h2 className="text-base font-semibold text-ink-900">{currentTopic?.label ?? '全部主题'}</h2><span className="text-xs tabular-nums text-ink-400">{visible.length} 条消息</span></div><p className="mt-1 text-[11px] text-ink-400">按原始发布时间倒序 · 北京时间</p></div>
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="text-[11px] text-ink-400">{snapshot ? statusLabels[snapshot.status] : '读取中'}{snapshot?.completedAt ? ` · ${newsDate(snapshot.completedAt)}` : ''}</span>
            <div className="flex items-center gap-1.5"><Button variant="ghost" className="text-xs" disabled={loading || starting} onClick={() => void load()}>读取结果</Button><Button loading={starting || running} className="text-xs" icon={<RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />} onClick={() => void refresh()}>{running ? '正在更新' : '刷新消息'}</Button></div>
          </div>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <div role="group" aria-label="消息时间范围" className="flex shrink-0 flex-wrap gap-1 rounded-lg bg-white p-1">{[{ value: 1, label: '今日' }, { value: -1, label: '昨天' }, { value: 7, label: '近7天' }, { value: 30, label: '近30天' }].map(range => <button type="button" key={range.value} aria-pressed={days === range.value} onClick={() => setDays(range.value)} className={`rounded-md px-3 py-1.5 text-xs transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400 ${days === range.value ? 'bg-brand-50 font-semibold text-brand-700' : 'text-ink-600 hover:bg-canvas'}`}>{range.label}</button>)}</div>
          <label className="flex min-w-[180px] flex-1 items-center gap-2 rounded-lg border border-line bg-white px-3 focus-within:border-brand-400"><Search aria-hidden="true" className="h-3.5 w-3.5 shrink-0 text-ink-400" /><input aria-label="搜索消息" placeholder="搜索标题、摘要或来源" value={search} onChange={event => setSearch(event.target.value)} className="min-w-0 flex-1 bg-transparent py-2 text-xs text-ink-900 outline-none placeholder:text-ink-400" /></label>
          <select aria-label="消息类别" value={category} onChange={event => setCategory(event.target.value as NewsCategory | 'ALL')} className="min-w-0 rounded-lg border border-line bg-white px-2 py-2 text-xs text-ink-600 focus:outline-brand-400"><option value="ALL">全部类别</option><option value="MEDIA">财经新闻</option><option value="OFFICIAL">官方发布</option></select>
          <select aria-label="消息来源" value={source} onChange={event => setSource(event.target.value)} className="min-w-0 max-w-[180px] rounded-lg border border-line bg-white px-2 py-2 text-xs text-ink-600 focus:outline-brand-400"><option value="ALL">全部来源</option>{sources.map(name => <option key={name} value={name}>{name}</option>)}</select>
        </div>
      </section>
    </header>
    <div className="grid min-h-0 min-w-0 items-start gap-5 lg:flex-1 lg:grid-cols-[12.5rem_minmax(0,1fr)] lg:grid-rows-[minmax(0,1fr)] lg:items-stretch">
    <aside className="min-h-0 min-w-0 lg:overflow-y-auto lg:overscroll-contain">
      <nav aria-label="主题分类" className="rounded-xl border border-line bg-white p-2">
        <div className="hidden px-3 pb-3 pt-2 text-[11px] font-semibold tracking-widest text-ink-400 lg:block">消息主题</div>
        <div className="flex gap-1 overflow-x-auto pb-1 lg:flex-col lg:overflow-visible lg:pb-0">
          {[{ id: 'ALL' as const, label: '全部主题', description: '财经新闻与官方发布' }, ...NEWS_TOPICS].map(option => {
            const count = option.id === 'ALL' ? filtered.length : topicCounts.get(option.id) ?? 0
            const selectedTopic = topic === option.id
            return <button key={option.id} type="button" aria-label={option.label} aria-pressed={selectedTopic}
              onClick={() => setTopic(option.id)}
              className={`flex shrink-0 items-center gap-3 rounded-lg px-3 py-2.5 text-left transition focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400 lg:w-full ${selectedTopic ? 'bg-brand-50 text-brand-700' : 'text-ink-600 hover:bg-canvas'}`}>
              <span className="min-w-0 flex-1"><span className={`block whitespace-nowrap text-sm ${selectedTopic ? 'font-semibold' : 'font-medium'}`}>{option.label}</span><span className={`mt-1 hidden text-[10px] leading-4 lg:block ${selectedTopic ? 'text-brand-500' : 'text-ink-400'}`}>{option.description}</span></span>
              <span className={`tabular-nums text-xs ${selectedTopic ? 'font-semibold' : 'text-ink-400'}`}>{count}</span>
            </button>
          })}
        </div>
      </nav>
      <details aria-label="来源状态" className="mt-3 rounded-xl border border-line bg-white p-3 text-xs text-ink-600">
        <summary className="cursor-pointer font-medium">来源状态 <span className="ml-1 tabular-nums text-ink-400">{healthySources}/{snapshot?.sources.length ?? 0}</span></summary>
        <div className="mt-3 max-h-80 overflow-y-auto pr-1">
          <p className="mb-2 text-[11px] leading-relaxed text-ink-400">媒体每 10 分钟、官方每 30 分钟更新；异常时保留已保存消息。</p>
          <div className="divide-y divide-line-soft">{snapshot?.sources.map(item => {
            const url = policySourceUrl(item.url)
            return <div key={item.id} className="space-y-2 py-3">
              <div className="flex flex-wrap items-center justify-between gap-1"><strong className="font-medium text-ink-900">{item.name}</strong><Tag tone={item.status === 'FAILED' ? 'warning' : 'neutral'}>{sourceLabels[item.status]}</Tag></div>
              <p className="text-[11px] leading-relaxed">最近尝试 {item.lastAttemptAt ? newsDate(item.lastAttemptAt) : '尚未尝试'}<br />上次成功 {item.lastSuccessAt ? newsDate(item.lastSuccessAt) : '尚无成功记录'}</p>
              <div className="flex justify-between text-[11px]"><span>本次 {item.itemCount} 条</span>{url ? <a href={url} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">来源页面</a> : null}</div>
              {item.message ? <p className="break-words text-[10px] leading-relaxed text-ink-400">{item.message}</p> : null}
            </div>
          })}</div>
          {snapshot?.dataGaps.length ? <ul className="mt-2 space-y-2 text-[11px] leading-relaxed text-amber-700">{snapshot.dataGaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul> : null}
        </div>
      </details>
    </aside>

    <section aria-label="按发布时间浏览消息" className="flex h-[min(40rem,85dvh)] min-h-[30rem] min-w-0 flex-col overflow-hidden rounded-xl border border-line bg-white lg:h-full lg:min-h-0">
      {error ? <p role="alert" className="shrink-0 border-b border-line bg-amber-50 px-4 py-3 text-xs text-amber-700 sm:px-6">{error}</p> : null}
      {running ? <p role="status" className="shrink-0 border-b border-line bg-brand-50 px-4 py-3 text-xs text-brand-700 sm:px-6">正在读取各来源，已有消息继续保留，完成后自动更新。</p> : null}
      <div ref={listRef} role="region" aria-label="消息列表滚动区域" tabIndex={0} className="min-h-0 flex-1 overflow-y-auto overscroll-contain focus-visible:outline focus-visible:-outline-offset-2 focus-visible:outline-brand-400">
      {loading && !snapshot ? <div className="p-8"><Loader text="正在读取财经新闻与官方发布" /></div> : <>
        {[...groups].map(([day, rows]) => <section key={day} data-news-day={day}>
          <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-line-soft bg-canvas px-4 py-2.5 sm:px-6">
            <h3 className="text-xs font-semibold text-ink-900">{day === today ? '今天' : day === yesterday ? '昨天' : day === 'unknown' ? '日期待核验' : day}</h3>
            {(day === today || day === yesterday) ? <span className="text-[11px] tabular-nums text-ink-400">{day}</span> : null}
            <span className="ml-auto text-[10px] tabular-nums text-ink-400">已显示 {rows.length} 条</span>
          </header>
          <div className="px-4 sm:px-6">{rows.map(({ item, topic: itemTopic }) => {
            const url = policySourceUrl(item.url)
            const topicLabel = NEWS_TOPICS.find(option => option.id === itemTopic)?.label
            const timestamp = newsTimestamp(item.publishedAt)
            const clockLabel = !Number.isFinite(timestamp) ? '待核验' : /^\d{4}-\d{2}-\d{2}$/.test(item.publishedAt ?? '') ? '仅日期' : newsDate(item.publishedAt).slice(11)
            return <article key={item.id} data-news-id={item.id} className="grid min-w-0 grid-cols-[3.25rem_minmax(0,1fr)] gap-2 border-b border-line-soft py-4 last:border-b-0 sm:grid-cols-[4rem_minmax(0,1fr)] sm:gap-4">
              <time dateTime={item.publishedAt ?? undefined} title={newsDate(item.publishedAt)} className={`pt-0.5 font-medium tabular-nums ${clockLabel === '仅日期' || clockLabel === '待核验' ? 'text-[10px] text-ink-400' : 'text-xs text-ink-600'}`}>{clockLabel}</time>
              <div className="min-w-0">
                <h4 className="text-sm font-medium leading-6 text-ink-900"><button type="button" data-news-title aria-label={`查看消息：${item.title}`} onClick={() => setSelected(item.id)} className="break-words text-left transition hover:text-brand-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400">{item.title}</button></h4>
                {item.summary ? <p className="mt-1 line-clamp-1 break-words text-xs leading-5 text-ink-400">{item.summary}</p> : null}
                <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-ink-400">
                  <span className="font-medium text-ink-600">{item.source}</span>
                  <span className={item.category === 'OFFICIAL' ? 'text-brand-600' : ''}>{newsCategory(item.category)}</span>
                  <span className="rounded bg-canvas px-1.5 py-0.5 text-ink-600">{topicLabel}</span>
                  <span className="ml-auto flex items-center gap-3">{url ? <a href={url} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 hover:text-brand-600">原文<ExternalLink className="h-3 w-3" aria-hidden="true" /></a> : null}<button type="button" aria-label={`查看${item.title}的原文与解读`} onClick={() => setSelected(item.id)} className="text-brand-600 hover:underline">{snapshot?.analyses[item.id] ? '查看解读' : '原文详情'}</button></span>
                </div>
              </div>
            </article>
          })}</div>
        </section>)}
        {!visible.length ? <div className="px-6 py-16 text-center"><Search aria-hidden="true" className="mx-auto mb-3 h-6 w-6 text-ink-400" /><p className="text-sm text-ink-600">{items.length ? '没有符合筛选条件的消息。' : '暂无已保存消息，可刷新消息并查看来源状态。'}</p>{items.length ? <p className="mt-2 text-xs text-ink-400">尝试切换主题、发布时间或搜索词。</p> : null}</div> : null}
      </>}
      </div>
      {visible.length > 0 ? <div className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-t border-line px-4 py-4 sm:px-6"><span className="text-[11px] tabular-nums text-ink-400">已展示 {Math.min(displayLimit, visible.length)} / {visible.length} 条 · 主题仅用于内容归类</span>{displayLimit < visible.length ? <Button aria-label="加载更多消息" className="text-xs" icon={<ArrowDown className="h-3.5 w-3.5" />} onClick={() => setDisplayLimit(limit => limit + 60)}>加载更多</Button> : <span className="text-[11px] text-ink-400">已展示全部结果</span>}</div> : null}
    </section>
    </div>
    {active ? <DetailOverlay open title={active.title} subtitle="原文网页 · 按需解读" onClose={() => setSelected(null)}><NewsItemDetail item={active} analysis={snapshot?.analyses[active.id]} aiEnabled={aiEnabled} analyzing={analyzing[active.id] === true} error={analysisErrors[active.id]} onAnalyze={() => void analyze(active.id)} /></DetailOverlay> : null}
  </div>
}
