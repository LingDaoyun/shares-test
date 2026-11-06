import { ExternalLink, Plus, Check } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '../ui/Button'
import { Tag } from '../ui/Badge'
import { changeClass, formatDateTime, formatNumber, formatSignedPercent } from '../../lib/format'
import type { HotspotNewsItem, HotspotRecommendation } from '../../types/hotspot'

export function EvidenceLink({ url, children }: { url: string | null; children: ReactNode }) {
  let safe = false
  try { safe = !!url && ['http:', 'https:'].includes(new URL(url).protocol) } catch { /* Keep the source text when its link is unavailable. */ }
  return safe ? <a href={url!} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-brand-600 hover:underline focus-visible:rounded focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-400">{children}<ExternalLink aria-hidden="true" className="h-3 w-3 shrink-0" /></a> : <span>{children}</span>
}

export function relationLabel(value: string) {
  return ({ NEWS_MENTION: '新闻直接涉及', BUSINESS_MATCH: '主营业务匹配', INDUSTRY_INFERENCE: '产业链推演' } as Record<string, string>)[value] ?? '关联待核实'
}

export function eventTypeLabel(value: string) {
  return ({ PRODUCT: '产品动态', PRODUCT_LAUNCH: '产品发布', TECHNOLOGY: '技术进展', INDUSTRY: '产业动态', CAPACITY: '产能进展', ORDER: '订单合作', POLICY: '产业政策', PRICE: '供需价格', OTHER: '其他催化' } as Record<string, string>)[value] ?? value
}

export function NewsSources({ news, excerpts = false }: { news: HotspotNewsItem[]; excerpts?: boolean }) {
  return <ul className="divide-y divide-line-soft">{news.map(item => <li key={item.id} className="py-3 first:pt-0 last:pb-0">
    <div className="mb-1.5 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-ink-400"><span className="font-medium text-ink-600">{item.source}</span><span>发布于 {formatDateTime(item.publishedAt)}</span></div>
    <div className="text-sm leading-relaxed"><EvidenceLink url={item.url}>{item.title}</EvidenceLink></div>
    {excerpts && item.excerpt ? <p className="mt-2 whitespace-pre-line text-xs leading-relaxed text-ink-500">{item.excerpt}</p> : null}
  </li>)}</ul>
}

interface RecommendationProps {
  recommendation: HotspotRecommendation
  added: boolean
  saving: boolean
  error?: string
  onAdd: () => void
}

export function RecommendationEvidence({ recommendation: stock, added, saving, error, onAdd }: RecommendationProps) {
  const firstPrice = stock.firstSeenPrice
  const currentPrice = stock.latestPrice
  const observedChange = firstPrice != null && currentPrice != null && firstPrice > 0 && currentPrice > 0
    ? (currentPrice / firstPrice - 1) * 100 : null
  return <article className="rounded-xl border border-line bg-white p-4 sm:p-5">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div><div className="flex flex-wrap items-center gap-2"><h4 className="text-base font-semibold text-ink-900">{stock.name}</h4><span className="tabular text-xs text-ink-400">{stock.symbol}</span><Tag tone={stock.relationType === 'INDUSTRY_INFERENCE' ? 'neutral' : 'brand'}>{relationLabel(stock.relationType)}</Tag></div><p className="mt-1 text-xs text-ink-400">{stock.industry || '行业待补充'}</p></div>
      <Button type="button" variant={added ? 'ghost' : 'secondary'} disabled={added} loading={saving} icon={added ? <Check className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />} onClick={onAdd}>{added ? '已加入特别关注' : '加入特别关注'}</Button>
    </div>
    {error ? <p role="alert" className="mt-2 text-xs text-amber-700">{error}</p> : null}
    <div className="mt-4 grid gap-3 sm:grid-cols-2">
      <div><p className="text-[11px] font-medium text-ink-400">推荐理由</p><p className="mt-1 text-sm leading-relaxed text-ink-800">{stock.recommendationReason}</p></div>
      <div><p className="text-[11px] font-medium text-ink-400">产业传导 · AI 推演</p><p className="mt-1 text-sm leading-relaxed text-ink-600">{stock.benefitMechanism}</p></div>
    </div>
    <div className="mt-4 grid grid-cols-2 gap-3 rounded-lg bg-line-soft/70 p-3 sm:grid-cols-3">
      <div><p className="text-[11px] text-ink-400">最近报价 / 当日涨跌</p><p className="tabular mt-1 text-sm font-semibold">{formatNumber(currentPrice)} <span className={`ml-1 ${changeClass(stock.changePercent)}`}>{formatSignedPercent(stock.changePercent)}</span></p></div>
      <div><p className="text-[11px] text-ink-400">首次观测价</p><p className="tabular mt-1 text-sm font-semibold">{formatNumber(firstPrice)}</p></div>
      <div><p className="text-[11px] text-ink-400">较首次观测</p><p className={`tabular mt-1 text-sm font-semibold ${changeClass(observedChange)}`}>{formatSignedPercent(observedChange)}</p></div>
    </div>
    <div className="mt-2 space-y-1 text-[11px] leading-relaxed text-ink-400"><p>报价时间：{stock.quoteAsOf || '待补充'} · <EvidenceLink url={stock.quoteUrl}>行情来源</EvidenceLink></p><p>首次推荐：{formatDateTime(stock.firstSeenAt)} · 初始报价时间：{stock.firstSeenQuoteAsOf || '待补充'}</p><p>价格变化是观测记录，未计成交条件与交易费用。</p></div>
    <details className="mt-4 border-t border-line-soft pt-3">
      <summary className="cursor-pointer text-xs font-medium text-brand-600">查看关联依据 · 新闻 {stock.evidence.length} 条 / 公司资料 {stock.companyEvidence.length} 条</summary>
      <div className="mt-3 space-y-4">
        {stock.evidence.length ? <NewsSources news={stock.evidence} excerpts /> : <p className="text-xs text-ink-500">新闻未直接涉及这家公司，关联来自主营资料与产业链推演。</p>}
        {stock.companyEvidence.map((item, index) => <div key={`${item.url}-${index}`} className="border-l-2 border-brand-200 pl-3"><p className="text-xs font-medium"><EvidenceLink url={item.url}>{item.sourceTitle}</EvidenceLink></p><p className="mt-1 whitespace-pre-line text-xs leading-relaxed text-ink-600">{item.excerpt}</p></div>)}
      </div>
    </details>
    {stock.risks.length ? <div className="mt-3 text-xs leading-relaxed text-amber-800"><p className="font-medium">需要关注</p><ul className="mt-1 list-disc space-y-1 pl-4">{stock.risks.map((risk, index) => <li key={index}>{risk}</li>)}</ul></div> : null}
  </article>
}
