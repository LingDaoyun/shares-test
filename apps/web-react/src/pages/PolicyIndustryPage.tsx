import { useCallback, useEffect, useRef, useState } from 'react'
import { Bot, ChevronRight, RefreshCw, Star } from 'lucide-react'
import { fetchPolicyDaily, fetchPolicyReviewHistory, refreshPolicyDaily, reviewPolicyTheme } from '../api/client'
import { useAppStore } from '../store/appStore'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { DetailOverlay } from '../components/ui/DetailOverlay'
import { PolicyResearchDetail, PolicySources } from '../components/policy/PolicyResearchDetail'
import { extractErrorMessage, formatDateTime } from '../lib/format'
import { policyEvidence } from '../lib/policyTrend'
import type { PolicyAiReview, PolicyDailySnapshot } from '../types'

const TRACKED_KEY = 'ai-stock.policy-tracked.v1'
const statusLabel: Record<PolicyDailySnapshot['status'], string> = { IDLE: '等待首次采集', RUNNING: '正在采集与研判', READY: '本轮完成', PARTIAL: '部分完成', FAILED: '本轮更新失败' }

function mergeReviewHistory(previous: PolicyAiReview[], incoming: PolicyAiReview[]) {
  const unique = new Map<string | null, PolicyAiReview>()
  for (const review of [...previous, ...incoming]) {
    unique.set(review.reviewedAt, review)
  }
  const time = (review: PolicyAiReview) => {
    const value = review.reviewedAt ? Date.parse(review.reviewedAt) : NaN
    return Number.isFinite(value) ? value : -Infinity
  }
  return [...unique.values()].sort((a, b) => time(b) - time(a)).slice(0, 20)
}

export function PolicyIndustryPage() {
  const [snapshot, setSnapshot] = useState<PolicyDailySnapshot | null>(null)
  const [history, setHistory] = useState<Record<string, PolicyAiReview[]>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [reviewErrors, setReviewErrors] = useState<Record<string, string>>({})
  const [reviewing, setReviewing] = useState<Record<string, boolean>>({})
  const [selected, setSelected] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('全部产业')
  const [tracked, setTracked] = useState<string[]>(() => {
    try { const value = JSON.parse(localStorage.getItem(TRACKED_KEY) ?? '[]'); return Array.isArray(value) ? value.filter(item => typeof item === 'string') : [] } catch { return [] }
  })
  const llmConfig = useAppStore(state => state.llmConfig)
  const mounted = useRef(false)
  const requestId = useRef(0)

  const load = useCallback(async () => {
    const id = ++requestId.current
    setLoading(true)
    try {
      const next = await fetchPolicyDaily()
      if (!mounted.current || id !== requestId.current) return
      setSnapshot(next)
      setError('')
      const histories = await Promise.allSettled(next.themes.map(theme => fetchPolicyReviewHistory(theme.themeCode)))
      if (!mounted.current || id !== requestId.current) return
      setHistory(previous => {
        const updated = { ...previous }
        histories.forEach((result, index) => {
          if (result.status !== 'fulfilled') return
          const code = next.themes[index].themeCode
          updated[code] = mergeReviewHistory(previous[code] ?? [], result.value)
        })
        return updated
      })
      if (histories.some(result => result.status === 'rejected')) setError('部分历史研判暂时未读到，可重新读取结果。')
    } catch (reason) {
      if (mounted.current && id === requestId.current) setError(`政策看板读取失败：${extractErrorMessage(reason)}`)
    } finally {
      if (mounted.current && id === requestId.current) setLoading(false)
    }
  }, [])

  useEffect(() => { mounted.current = true; void load(); return () => { mounted.current = false; requestId.current++ } }, [load])
  useEffect(() => {
    if (snapshot?.status !== 'RUNNING') return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        const next = await fetchPolicyDaily()
        if (cancelled) return
        setSnapshot(next)
        if (next.status !== 'RUNNING') { void load(); return }
      } catch { if (!cancelled) setError('任务状态暂时读取失败，正在重试。') }
      if (!cancelled) timer = setTimeout(() => void poll(), 5000)
    }
    timer = setTimeout(() => void poll(), 5000)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [snapshot?.status, load])

  const refresh = async () => {
    setLoading(true)
    try { const next = await refreshPolicyDaily(); if (mounted.current) { setSnapshot(next); setError('') } }
    catch (reason) { if (mounted.current) setError(`启动采集失败：${extractErrorMessage(reason)}`) }
    finally { if (mounted.current) setLoading(false) }
  }
  const review = async (code: string) => {
    setReviewing(previous => ({ ...previous, [code]: true }))
    setReviewErrors(previous => ({ ...previous, [code]: '' }))
    try {
      const next = await reviewPolicyTheme(code)
      if (!mounted.current) return
      if (!next.reviewedAt || !next.trendPlan) {
        setReviewErrors(previous => ({ ...previous, [code]: next.dataGaps.join('；') || '未得到有效研判，已保留上次结果。' }))
      } else {
        setHistory(previous => ({ ...previous, [code]: mergeReviewHistory(previous[code] ?? [], [next]) }))
      }
    } catch (reason) { if (mounted.current) setReviewErrors(previous => ({ ...previous, [code]: `AI 研判失败：${extractErrorMessage(reason)}；已保留上次结果。` })) }
    finally { if (mounted.current) setReviewing(previous => ({ ...previous, [code]: false })) }
  }
  const toggleTracked = (code: string) => {
    const next = tracked.includes(code) ? tracked.filter(item => item !== code) : [...tracked, code]
    setTracked(next)
    try { localStorage.setItem(TRACKED_KEY, JSON.stringify(next)) } catch { setError('本浏览器无法保存关注列表，本次页面内仍可使用。') }
  }

  if (loading && !snapshot) return <Loader text="正在读取每日产业情报" />
  const themes = snapshot?.themes ?? []
  const active = themes.find(theme => theme.themeCode === selected)
  const cards = themes.map(theme => ({ theme, evidence: policyEvidence(theme) }))
    .filter(({ theme, evidence }) => (filter !== '我的关注' || tracked.includes(theme.themeCode)) && (filter !== '近30天有线索' || evidence.recent30 > 0))
    .filter(({ theme }) => !search.trim() || [theme.name, ...theme.chainSegments, ...theme.signals.flatMap(item => [item.summary, item.excerpt, item.source, item.sourceInfo?.publisher])].join(' ').toLowerCase().includes(search.trim().toLowerCase()))
    .sort((a, b) => b.evidence.recent30 - a.evidence.recent30 || b.theme.strengthScore - a.theme.strengthScore)
  const uniqueSources = new Set(themes.flatMap(theme => policyEvidence(theme).signals.map(signal => signal.summary.replace(/\s/g, ''))))

  return <div className="flex flex-col gap-4">
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div><h2 className="text-lg font-semibold text-ink-900">每日产业情报</h2><p className="mt-1 text-xs text-ink-500">{snapshot?.scheduledTime ?? '每天08:30（北京时间）'} · 2–12 周趋势观察</p></div>
        <div className="flex flex-wrap gap-2"><Button variant="ghost" disabled={loading} onClick={() => void load()}>读取结果</Button><Button icon={<RefreshCw className="h-4 w-4" />} loading={loading || snapshot?.status === 'RUNNING'} onClick={() => void refresh()}>立即采集与研判</Button></div>
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2 text-xs"><Tag tone={snapshot?.status === 'FAILED' ? 'warning' : 'brand'}>{snapshot ? statusLabel[snapshot.status] : '暂无结果'}</Tag><span className="text-ink-400">{snapshot?.completedAt ? `最近完成 ${formatDateTime(snapshot.completedAt)}` : '完成后将自动保存结果'}</span><span className="ml-auto inline-flex items-center gap-1 text-ink-500"><Bot className="h-3.5 w-3.5" />{llmConfig?.apiKeyConfigured ? `${llmConfig.provider} / ${llmConfig.model}` : '模型待配置'}</span></div>
      <div className="mt-4 grid grid-cols-3 gap-2"><Stat label="产业方向" value={themes.length} /><Stat label="去重后的政策线索" value={uniqueSources.size} /><Stat label="本轮已研判" value={snapshot?.reviewedThemeCodes.length ?? 0} /></div>
      <p className="mt-3 text-[11px] leading-relaxed text-ink-500">官方来源优先。已取得正文的来源展示对应片段，部分来源仅有标题，原文可点击核验；长期规划按来源发布日期展示。Agent 负责提出产业逻辑与验证条件，实际资金与订单兑现需另外验证。</p>
      {snapshot?.dataGaps.length ? <details className="mt-3 text-xs text-amber-700"><summary className="cursor-pointer">本轮采集说明 · {snapshot.dataGaps.length} 项</summary><ul className="mt-2 space-y-1">{snapshot.dataGaps.map((gap, index) => <li key={index}>{gap}</li>)}</ul></details> : null}
      {error ? <p role="alert" className="mt-3 text-xs text-amber-700">{error}</p> : null}
    </Card>
    {snapshot?.unclassifiedSignals?.length ? <Card><details>
      <summary className="cursor-pointer text-sm font-semibold text-ink-800">待归类政策 · {snapshot.unclassifiedSignals.length} 条</summary>
      <p className="my-3 text-xs leading-relaxed text-ink-500">这些文章尚未对应到产业方向，保留来源和原文供核对，不计入已完成产业研判。</p>
      <PolicySources signals={snapshot.unclassifiedSignals} />
    </details></Card> : null}
    <div className="flex flex-wrap items-center gap-2"><input aria-label="搜索产业或政策" value={search} onChange={event => setSearch(event.target.value)} placeholder="搜索产业、政策或产业链环节" className="min-w-0 flex-1 rounded-lg border border-line bg-white px-3 py-2 text-sm outline-none focus:border-brand-400" />
      {['全部产业', '我的关注', '近30天有线索'].map(value => <button key={value} type="button" aria-pressed={filter === value} onClick={() => setFilter(value)} className={`rounded-lg border px-3 py-2 text-xs ${filter === value ? 'border-brand-200 bg-brand-50 text-brand-700' : 'border-line bg-white text-ink-500'}`}>{value}</button>)}
    </div>
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">{cards.map(({ theme, evidence }) => {
      const latest = history[theme.themeCode]?.[0]
      const planningCount = evidence.signals.filter(signal => signal.planningBaseline).length
      return <article key={theme.themeCode} className="card flex min-w-0 flex-col p-5">
        <div className="flex items-start justify-between gap-3"><div><h3 className="text-base font-semibold text-ink-900">{theme.name}</h3><div className="mt-2 flex flex-wrap gap-1.5">{theme.chainSegments.slice(0, 4).map(segment => <Tag key={segment}>{segment}</Tag>)}</div></div><button type="button" aria-label={`${tracked.includes(theme.themeCode) ? '取消关注' : '关注'}${theme.name}`} aria-pressed={tracked.includes(theme.themeCode)} onClick={() => toggleTracked(theme.themeCode)} className="rounded-lg p-2 text-amber-500 hover:bg-amber-50"><Star className="h-4 w-4" fill={tracked.includes(theme.themeCode) ? 'currentColor' : 'none'} /></button></div>
        <div className="mt-4 flex flex-wrap items-center gap-2 text-xs text-ink-400"><Tag tone={latest ? 'brand' : 'neutral'}>{latest ? `AI · ${latest.phaseLabel ?? '阶段待定'}` : '待 Agent 研判'}</Tag>{planningCount ? <Tag>长期规划 {planningCount} 篇</Tag> : null}<span>近30天 {evidence.recent30} 条 · {evidence.sources} 个来源</span></div>
        <p className="mt-3 line-clamp-3 text-sm leading-relaxed text-ink-600">{latest?.trendPlan?.thesis || latest?.logicSummary || (evidence.signals.length ? '已采集相关政策线索，等待 Agent 提炼产业传导与后续验证节点。' : '本次没有直接匹配的产业政策线索。')}</p>
        <div className="my-4 space-y-3 border-t border-line-soft pt-3">{evidence.signals.slice(0, 2).map((signal, index) => <div key={index}><div className="mb-1 flex flex-wrap items-center gap-1.5 text-[11px]"><Tag tone={(signal.sourceInfo?.authorityRank ?? 0) >= 80 ? 'brand' : 'neutral'}>{signal.sourceInfo?.authorityLabel ?? '来源级别待核验'}</Tag><span className="font-medium text-ink-700">{signal.sourceInfo?.publisher ?? signal.source}</span></div><p className="truncate text-xs text-ink-500" title={signal.summary}>{signal.planningBaseline ? '长期规划 · ' : ''}{signal.publishedAt?.slice(0, 10) ?? '日期未知'} · {signal.summary}</p></div>)}</div>
        <div className="mt-auto flex flex-wrap items-center justify-between gap-2"><span className="text-[11px] text-ink-400">关联公司 {theme.companyPool.length} 家{latest?.reviewedAt ? ` · 研判 ${formatDateTime(latest.reviewedAt)}` : ''}</span><Button variant="ghost" onClick={() => setSelected(theme.themeCode)} aria-label={`查看${theme.name}趋势详情`}>查看趋势<ChevronRight className="h-4 w-4" /></Button></div>
      </article>
    })}</div>
    {!cards.length ? <Card><p className="py-5 text-center text-sm text-ink-500">{themes.length ? '没有符合筛选条件的产业。' : '尚无每日快照，点击“立即采集与研判”开始。'}</p></Card> : null}
    {active ? <DetailOverlay open title={active.name} subtitle="政策事实 · Agent 推演 · 上市公司观察" onClose={() => setSelected(null)}>
      <PolicyResearchDetail key={active.themeCode} theme={active} history={history[active.themeCode] ?? []} aiEnabled={llmConfig?.apiKeyConfigured === true} reviewing={reviewing[active.themeCode] === true} error={reviewErrors[active.themeCode]} onReview={() => void review(active.themeCode)} />
    </DetailOverlay> : null}
  </div>
}

function Stat({ label, value }: { label: string; value: number }) { return <div className="rounded-lg bg-line-soft/60 px-3 py-3"><div className="tabular text-xl font-semibold text-ink-900">{value}</div><div className="mt-1 text-[11px] text-ink-400">{label}</div></div> }
