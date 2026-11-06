import { Bot, ExternalLink } from 'lucide-react'
import { Button } from '../ui/Button'
import { Tag } from '../ui/Badge'
import { policySourceUrl } from '../../lib/policyTrend'
import { newsCategory, newsDate } from './newsFormat'
import type { FinancialNewsItem, NewsAnalysis } from '../../types/news'

export function NewsItemDetail({ item, analysis, aiEnabled, analyzing, error, onAnalyze }: {
  item: FinancialNewsItem
  analysis?: NewsAnalysis
  aiEnabled: boolean
  analyzing: boolean
  error?: string
  onAnalyze: () => void
}) {
  const url = policySourceUrl(item.url)
  const evidenceUrl = policySourceUrl(analysis?.evidenceUrl ?? null)
  // NFRA article pages explicitly return X-Frame-Options: DENY.
  const externalOnly = url ? /(^|\.)nfra\.gov\.cn$/i.test(new URL(url).hostname) : false
  return <div className="flex flex-col gap-5">
    <section aria-label="新闻原文">
      <div className="flex flex-wrap items-center gap-2 text-xs text-ink-500"><Tag tone={item.category === 'OFFICIAL' ? 'brand' : 'neutral'}>{newsCategory(item.category)}</Tag><span>{item.source}</span><time dateTime={item.publishedAt ?? undefined}>{newsDate(item.publishedAt)}</time>
        {url ? <a data-original-page href={url} target="_blank" rel="noopener noreferrer" className="ml-auto inline-flex items-center gap-1 text-brand-600 hover:underline">新窗口打开原文<ExternalLink className="h-3 w-3" aria-hidden="true" /></a> : null}
      </div>
      {url ? externalOnly ? <div className="mt-3 rounded-lg border border-line bg-canvas px-5 py-10 text-center">
        <h3 className="text-sm font-semibold text-ink-900">在来源网站阅读原文</h3>
        <p className="mt-2 text-xs text-ink-500">该来源不支持在此内嵌显示，请打开原文网页阅读。</p>
        <a href={url} target="_blank" rel="noopener noreferrer" className="mt-5 inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2 text-sm text-brand-600 hover:bg-brand-50">打开原文网页<ExternalLink className="h-4 w-4" aria-hidden="true" /></a>
      </div> : <div className="mt-3 overflow-hidden rounded-lg border border-line">
        <iframe src={url} title={`${item.source}原文：${item.title}`} tabIndex={0}
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox"
          referrerPolicy="no-referrer" className="h-[60dvh] min-h-[320px] w-full border-0 bg-white" />
        <p className="border-t border-line-soft bg-canvas px-3 py-2 text-[11px] leading-relaxed text-ink-500">原文由来源网站提供；若未正常显示，可<a href={url} target="_blank" rel="noopener noreferrer" className="ml-1 text-brand-600 underline">在新窗口打开原文</a>。</p>
      </div> : <p className="mt-4 text-sm text-ink-500">这条消息的原文链接不可用。</p>}
      {item.summary?.trim() ? <div className="mt-4"><h3 className="text-xs font-medium text-ink-500">已采集摘要</h3><p className="mt-2 whitespace-pre-wrap break-words text-sm leading-relaxed text-ink-700">{item.summary}</p></div> : null}
    </section>
    <section className="border-t border-line-soft pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-semibold text-ink-900">AI 解读 · 推演</h3>
        {analysis ? <span className="text-[11px] text-ink-400">已保存 · {newsDate(analysis.analyzedAt)}</span> : <Button icon={<Bot className="h-4 w-4" aria-hidden="true" />} loading={analyzing} disabled={!aiEnabled} onClick={onAnalyze}>AI 解读</Button>}
      </div>
      {!analysis ? <p className="mt-2 text-xs leading-relaxed text-ink-500">解读依据已采集标题和摘要，不包含网页全文。{!aiEnabled ? <>模型尚未配置，可前往 <a href="#/settings" className="text-brand-600 underline">系统配置</a>。</> : null}</p> : null}
      {error ? <p role="alert" className="mt-3 text-xs text-amber-700">{error}</p> : null}
      {analysis ? <div className="mt-4 space-y-4 text-sm leading-relaxed">
        <p className="whitespace-pre-wrap text-ink-700">{analysis.summary}</p>
        {analysis.affectedSectors.length ? <div className="flex flex-wrap items-center gap-2"><span className="text-xs text-ink-500">可能影响的行业</span>{analysis.affectedSectors.map(sector => <Tag key={sector}>{sector}</Tag>)}</div> : null}
        {analysis.transmission ? <div><h4 className="text-xs font-semibold text-ink-600">影响路径 · AI 推演</h4><p className="mt-1 whitespace-pre-wrap text-ink-700">{analysis.transmission}</p></div> : null}
        <AnalysisList title="后续观察" items={analysis.watchPoints} />
        <AnalysisList title="风险与不确定性" items={analysis.risks} />
        <AnalysisList title="资料缺口" items={analysis.dataGaps} />
        {evidenceUrl ? <a href={evidenceUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-xs text-brand-600 hover:underline">解读依据<ExternalLink className="h-3 w-3" aria-hidden="true" /></a> : null}
      </div> : null}
    </section>
  </div>
}

function AnalysisList({ title, items }: { title: string; items: string[] }) {
  return items.length ? <div><h4 className="text-xs font-semibold text-ink-600">{title}</h4><ul className="mt-1 list-disc space-y-1 pl-4 text-sm text-ink-700">{items.map((item, index) => <li key={index}>{item}</li>)}</ul></div> : null
}
