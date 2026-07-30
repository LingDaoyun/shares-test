import { Activity, Building2, ExternalLink, FileText } from 'lucide-react'
import { formatDateTime } from '../../lib/format'
import type { LongTermCandidateContext, LongTermPolicyDocument } from '../../types'
import { Tag } from '../ui/Badge'
import { Loader } from '../ui/Loader'

interface LongTermCandidateContextPanelProps {
  context: LongTermCandidateContext | null
  loading: boolean
  error: string
}

export function LongTermCandidateContextPanel({
  context,
  loading,
  error
}: LongTermCandidateContextPanelProps) {
  if (loading) {
    return (
      <section className="border-y border-line-soft py-5">
        <Loader text="正在核对行业、政策与周期证据" />
      </section>
    )
  }

  if (error) {
    return (
      <section className="border-y border-red-100 bg-red-50/50 px-3 py-4">
        <h3 className="text-sm font-semibold text-red-800">行业背景暂未加载</h3>
        <p className="mt-1 text-xs leading-relaxed text-red-700">{error}</p>
      </section>
    )
  }

  if (!context) return null

  const industry = context.industryContext
  const cycle = context.cycleContext

  return (
    <section className="border-y border-line-soft py-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <Building2 className="h-4 w-4 text-brand-600" />
            <h3 className="text-sm font-semibold text-ink-900">行业环境</h3>
          </div>
          <p className="mt-1 text-xs text-ink-400">证据更新于 {formatDateTime(context.generatedAt)}</p>
        </div>
        <div className="flex flex-wrap gap-1.5">
          <Tag tone="sky">{context.industry}</Tag>
          <Tag tone="neutral">{industry.modelLabel}</Tag>
          <Tag tone="neutral">{industry.cycleTypeLabel}</Tag>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
        <ContextMetric label="所属行业" value={context.industry} />
        <ContextMetric label="适用模型" value={industry.modelLabel} />
      </div>
      <EvidenceLines items={industry.evidence} emptyText="行业分类依据待补" />

      <div className="mt-5 border-t border-line-soft pt-4">
        <div className="flex items-center gap-2">
          <FileText className="h-4 w-4 text-brand-600" />
          <h3 className="text-sm font-semibold text-ink-900">最近政策</h3>
          <span className="text-xs text-ink-400">近两年官方文件</span>
        </div>
        {context.policyEvidence.documents.length ? (
          <div className="mt-3 divide-y divide-line-soft border-y border-line-soft">
            {context.policyEvidence.documents.map((document) => (
              <PolicyRow key={document.url} document={document} />
            ))}
          </div>
        ) : (
          <p className="mt-3 text-xs leading-relaxed text-ink-500">
            {context.policyEvidence.dataGaps[0] ?? '最近两年未匹配到可靠官方政策文件'}
          </p>
        )}
      </div>

      <div className="mt-5 border-t border-line-soft pt-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-brand-600" />
            <h3 className="text-sm font-semibold text-ink-900">当前周期</h3>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <Tag tone={cycle.provisional ? 'warning' : 'success'}>
              {cycle.provisional ? '暂定判断' : '证据可用'}
            </Tag>
            <Tag tone="neutral">置信度 {cycle.confidence}</Tag>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
          <ContextMetric label="行业经营周期" value={cycle.businessStageLabel} />
          <ContextMetric label="股票价格周期" value={cycle.priceStageLabel} />
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
          <EvidenceGroup title="支持依据" items={cycle.supportingEvidence} tone="success" />
          <EvidenceGroup title="反向证据" items={cycle.contraryEvidence} tone="warning" />
        </div>
      </div>

      {context.dataGaps.length ? (
        <div className="mt-4 border-t border-line-soft pt-3">
          <div className="text-xs font-semibold text-ink-700">仍需补齐</div>
          <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed text-ink-500">
            {context.dataGaps.map((gap) => <li key={gap}>{gap}</li>)}
          </ul>
        </div>
      ) : null}
    </section>
  )
}

function PolicyRow({ document }: { document: LongTermPolicyDocument }) {
  return (
    <div className="py-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <a
          href={document.url}
          target="_blank"
          rel="noreferrer"
          className="inline-flex min-w-0 items-start gap-1.5 text-sm font-semibold leading-relaxed text-ink-900 hover:text-brand-700"
        >
          <span>{document.title}</span>
          <ExternalLink className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        </a>
        <Tag tone={policyTone(document.impact)}>{policyImpactLabel(document.impact)}</Tag>
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-ink-400">
        <span>{document.source}</span>
        <span>{document.publishedAt}</span>
        <span>相关性 {document.relevanceScore}</span>
      </div>
      <p className="mt-1.5 text-xs leading-relaxed text-ink-500">{document.rationale}</p>
      {document.matchedKeywords.length ? (
        <div className="mt-2 flex flex-wrap gap-1">
          {document.matchedKeywords.map((keyword) => <Tag key={keyword} tone="sky">{keyword}</Tag>)}
        </div>
      ) : null}
    </div>
  )
}

function ContextMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-l-2 border-brand-200 bg-line-soft/30 px-3 py-2">
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function EvidenceLines({ items, emptyText }: { items: string[]; emptyText: string }) {
  return (
    <p className="mt-2 text-xs leading-relaxed text-ink-500">
      {items.length ? items.join('；') : emptyText}
    </p>
  )
}

function EvidenceGroup({
  title,
  items,
  tone
}: {
  title: string
  items: string[]
  tone: 'success' | 'warning'
}) {
  return (
    <div>
      <Tag tone={tone}>{title}</Tag>
      <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed text-ink-500">
        {items.length
          ? items.map((item) => <li key={item}>{item}</li>)
          : <li>暂无明确{title}</li>}
      </ul>
    </div>
  )
}

function policyTone(impact: LongTermPolicyDocument['impact']) {
  if (impact === 'SUPPORT') return 'success' as const
  if (impact === 'CONSTRAINT') return 'warning' as const
  return 'neutral' as const
}

function policyImpactLabel(impact: LongTermPolicyDocument['impact']) {
  if (impact === 'SUPPORT') return '支持'
  if (impact === 'CONSTRAINT') return '约束'
  return '中性'
}
