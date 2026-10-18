import { useState } from 'react'
import { Bot, ExternalLink } from 'lucide-react'
import { Button } from '../ui/Button'
import { Tag } from '../ui/Badge'
import { formatDateTime, formatScore } from '../../lib/format'
import { isHistoricalPolicySignal, policyEvidence, policySourceUrl } from '../../lib/policyTrend'
import type { PolicyAiReview, PolicySignal, PolicyTheme } from '../../types'

export function PolicyResearchDetail({ theme, history, aiEnabled, reviewing, error, onReview }: {
  theme: PolicyTheme; history: PolicyAiReview[]; aiEnabled: boolean; reviewing: boolean; error?: string; onReview: () => void
}) {
  const [tab, setTab] = useState('趋势研判')
  const [sourceFilter, setSourceFilter] = useState('全部')
  const latest = history[0]
  const evidence = policyEvidence(theme)
  const sourceSignals = sourceFilter === '近90天' ? evidence.recent90Signals
    : sourceFilter === '历史线索' ? evidence.historicalSignals
      : sourceFilter === '长期规划' ? evidence.planningSignals : evidence.signals
  return <div className="flex flex-col gap-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-xs text-ink-500">2–12 周主题趋势 · 兼看 1–2 周催化</p>
      <Button icon={<Bot className="h-4 w-4" />} loading={reviewing} disabled={!aiEnabled || !evidence.signals.length} onClick={onReview}>
        {latest ? '更新 AI 研判' : 'AI 趋势研判'}
      </Button>
    </div>
    {!aiEnabled ? <p className="text-xs text-ink-500">模型尚未就绪，可前往 <a href="#/settings" className="text-brand-600 underline">系统配置</a> 检查。</p> : null}
    {error ? <p role="alert" className="rounded-lg bg-amber-50 p-3 text-xs text-amber-800">{error}</p> : null}
    <div role="tablist" aria-label="产业研究内容" className="flex flex-wrap gap-2 border-b border-line-soft pb-3">
      {['趋势研判', '政策与新闻', '上市公司', '研判记录'].map(label => <button key={label} type="button" role="tab"
        aria-selected={tab === label} onClick={() => setTab(label)}
        className={`rounded-lg px-3 py-2 text-sm ${tab === label ? 'bg-brand-50 font-semibold text-brand-700' : 'text-ink-500 hover:bg-line-soft'}`}>
        {label}{label === '研判记录' ? ` · ${history.length}` : ''}
      </button>)}
    </div>
    {tab === '趋势研判' ? <>
      {latest ? <TrendReview review={latest} /> : <Empty text="尚无 AI 研判。每日任务会优先分析近期有证据的产业，也可以手动运行。" />}
      <ResearchList title="产业观察风险" items={theme.risks} />
    </> : null}
    {tab === '政策与新闻' ? <>
      <p className="text-xs leading-relaxed text-ink-500">普通新闻回溯近两年，长期规划持续保留。每个产业最多展示 24 条代表线索，属于已采样范围，不是近两年的全量新闻。所有时间统计均按来源发布日期，日期未知另列；新采集不等于新发布，历史文件仍需核验现行效力。已取得正文的来源展示对应片段，部分来源仅有标题，请点击原文核对完整条款、适用对象及落实时间。</p>
      <div className="flex flex-wrap gap-2"><Tag>近7天 {evidence.recent7}</Tag><Tag>近30天 {evidence.recent30}</Tag><Tag>近90天 {evidence.recent90}</Tag><Tag>近两年 {evidence.recent730}</Tag><Tag>历史线索 {evidence.historicalSignals.length}</Tag><Tag>长期规划 {evidence.planningSignals.length}</Tag><Tag>日期未知 {evidence.unknown}</Tag></div>
      <div className="flex flex-wrap gap-2" role="group" aria-label="政策新闻时间范围">{['全部', '近90天', '历史线索', '长期规划'].map(value => <button key={value} type="button" aria-pressed={sourceFilter === value} onClick={() => setSourceFilter(value)} className={`rounded-lg border px-3 py-2 text-xs ${sourceFilter === value ? 'border-brand-200 bg-brand-50 text-brand-700' : 'border-line text-ink-500'}`}>{value}</button>)}</div>
      {sourceFilter === '历史线索' ? <p className="text-xs text-ink-500">历史线索指发布于 91–730 天前的普通政策或产业新闻，长期规划单独列示。</p> : null}
      {sourceSignals.length ? <PolicySources signals={sourceSignals} /> : <Empty text="已采集的线索中，暂无符合这个时间范围的文章。" />}
    </> : null}
    {tab === '上市公司' ? <>
      <p className="text-xs leading-relaxed text-ink-500">从已接入的 A 股公司样本中筛选产业相关候选；行业关键词关联仍需主营收入、公告和订单验证。此处未提供实时买卖信号。</p>
      {theme.companyPool.length ? theme.companyPool.map(company => {
        const assessment = latest?.trendPlan?.companyAssessments?.find(item => item.symbol === company.symbol)
        return <article key={company.symbol} className="rounded-xl border border-line-soft p-4">
          <div className="flex flex-wrap items-center gap-2"><h3 className="font-semibold text-ink-900">{company.companyName}</h3><span className="tabular text-xs text-ink-400">{company.symbol}</span><Tag>{company.industry ?? '行业待核验'}</Tag><Tag tone="brand">{company.chainSegment}</Tag></div>
          <p className="mt-2 text-xs leading-relaxed text-ink-500">候选依据：{company.leadershipRationale.join('；') || '行业关键词匹配，待核验主营业务'}</p>
          {assessment ? <div className="mt-3 rounded-lg bg-brand-50/50 p-3 text-xs leading-relaxed">
            <span className="font-semibold text-brand-700">Agent 关联研判 · {assessment.relevance === 'DIRECT' ? '有直接关联材料' : assessment.relevance === 'INDUSTRY' ? '产业相关' : '关联待核验'}</span>
            <p className="mt-1 text-ink-700">{assessment.rationale}</p><p className="mt-1 text-ink-500">还需验证：{assessment.verificationNeeded}</p>
          </div> : <p className="mt-2 text-xs text-ink-400">尚无该公司的 Agent 关联研判。</p>}
          <div className="mt-3 flex flex-wrap gap-2"><Tag>{company.financialQualityLabel}</Tag><Tag>财务参考 {formatScore(company.financialQualityScore)}</Tag><Tag>PE {formatScore(company.peTtm)}</Tag><Tag>PB {formatScore(company.pbRatio)}</Tag></div>
          {company.dataGaps.length ? <p className="mt-2 text-xs text-amber-700">待核验：{company.dataGaps.join('；')}</p> : null}
        </article>
      }) : <Empty text="当前公司样本未匹配到可核验的相关上市公司，暂不补入推测名单。" />}
    </> : null}
    {tab === '研判记录' ? <>
      <p className="text-xs text-ink-500">保留最近 20 次成功研判及当时的证据；模型判断变化不等于股价趋势变化。</p>
      {history.length > 1 ? <p className="rounded-lg bg-line-soft/60 p-3 text-sm text-ink-700">最近两次：{history[1].phaseLabel ?? '阶段待定'} → {latest.phaseLabel ?? '阶段待定'}；{history[1].directionLabel ?? '方向待定'} → {latest.directionLabel ?? '方向待定'}</p> : null}
      {history.length ? history.map((review, index) => <details key={`${review.reviewedAt}-${index}`} className="rounded-xl border border-line-soft p-4">
        <summary className="cursor-pointer text-sm font-medium text-ink-700">{review.reviewedAt ? formatDateTime(review.reviewedAt) : '时间未知'} · {review.phaseLabel ?? '阶段待定'} · {review.directionLabel ?? '方向待定'}</summary>
        <div className="mt-4 flex flex-col gap-4"><TrendReview review={review} /><h4 className="text-sm font-semibold">研判时的政策来源</h4><PolicySources signals={review.evidenceSnapshot ?? []} /></div>
      </details>) : <Empty text="还没有已保存的成功研判。" />}
    </> : null}
  </div>
}

function TrendReview({ review }: { review: PolicyAiReview }) {
  const plan = review.trendPlan
  return <section aria-label="AI 趋势研判" className="flex flex-col gap-4">
    <div className="flex flex-wrap items-center gap-2"><Tag tone="brand">Agent 推演</Tag><Tag tone={review.direction === 'POSITIVE' ? 'success' : review.direction === 'NEGATIVE' ? 'danger' : 'neutral'}>{review.directionLabel ?? '方向待定'}</Tag><Tag>{review.phaseLabel ?? '阶段待定'}</Tag>
      <span className="text-[11px] text-ink-400">{review.provider} / {review.model} · {review.reviewedAt ? formatDateTime(review.reviewedAt) : '时间未知'}</span></div>
    <div className="rounded-xl border border-brand-100 bg-brand-50/40 p-4"><h3 className="text-sm font-semibold text-brand-700">未来 2–12 周主线假设</h3><p className="mt-2 text-sm leading-relaxed text-ink-800">{plan?.thesis || review.logicSummary || '未形成有效假设'}</p>
      {plan?.policyIncrement ? <p className="mt-3 text-xs leading-relaxed text-ink-600">政策增量：{plan.policyIncrement}</p> : null}</div>
    {plan ? <>
      <ResearchList title="政策 → 产业链 → 经营影响" items={plan.benefitChain} />
      <div className="grid gap-3 md:grid-cols-2"><ResearchList title="1–2 周催化观察" items={plan.shortTermCatalysts} /><ResearchList title="2–12 周兑现观察" items={plan.mediumTermCatalysts} /><ResearchList title="什么出现，支持这条逻辑" items={plan.validationSignals} /><ResearchList title="什么出现，应下调判断" items={plan.invalidationSignals} /></div>
      <p className="rounded-lg bg-line-soft/60 p-3 text-xs leading-relaxed text-ink-600">市场验证：{plan.marketConfirmation || '板块量价与持续性待验证'}</p>
    </> : null}
    <div className="grid gap-3 md:grid-cols-2"><ResearchList title="异议与反证" items={review.disagreements} /><ResearchList title="模型提示风险" items={review.risks} /><ResearchList title="待核实事项" items={review.mustVerify} /></div>
    {review.dataGaps.length ? <p className="text-xs leading-relaxed text-amber-700">证据缺口：{review.dataGaps.join('；')}</p> : null}
    <p className="text-[11px] text-ink-400">模型置信度 {review.confidence ?? '未知'} / 100，仅描述本次分析把握，不是上涨概率。</p>
  </section>
}

export function PolicySources({ signals }: { signals: PolicySignal[] }) {
  if (!signals.length) return <Empty text="本次采集未找到直接匹配的政策或产业新闻。" />
  return <div className="flex flex-col gap-3">{signals.map((signal, index) => {
    const url = policySourceUrl(signal.url)
    const excerpt = signal.excerpt?.trim()
    const historical = isHistoricalPolicySignal(signal)
    return <article key={`${signal.url}-${index}`} className="rounded-xl border border-line-soft p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs text-ink-500"><div className="flex flex-wrap items-center gap-2"><Tag tone={(signal.sourceInfo?.authorityRank ?? 0) >= 80 ? 'brand' : 'neutral'}>{signal.sourceInfo?.authorityLabel ?? '来源级别待核验'}</Tag><strong className="font-semibold text-ink-800">{signal.sourceInfo?.publisher ?? signal.source}</strong></div><span>来源发布日期：{signal.publishedAt || '未知'}</span></div>
      {signal.sourceInfo ? <p className="mb-2 text-[11px] text-ink-400">采集来源：{signal.source} · {signal.sourceInfo.siteHost}{signal.sourceInfo.attributedSource ? ` · 网页标注来源：${signal.sourceInfo.attributedSource}` : ''}</p> : null}
      <div className="mb-2 flex flex-wrap gap-2"><Tag tone={excerpt ? 'brand' : 'neutral'}>{excerpt ? '已取得正文片段' : '仅标题线索'}</Tag>{signal.planningBaseline ? <Tag>长期规划</Tag> : historical ? <Tag>历史线索</Tag> : null}</div>
      {url ? <a href={url} target="_blank" rel="noopener noreferrer" className="text-sm font-medium leading-relaxed text-brand-700 hover:underline">{signal.summary}<ExternalLink aria-hidden="true" className="ml-1 inline h-3.5 w-3.5" /><span className="sr-only">（查看原文）</span></a> : <p className="text-sm text-ink-700">{signal.summary}</p>}
      {excerpt ? <blockquote className="mt-3 whitespace-pre-wrap break-words rounded-lg border-l-2 border-brand-200 bg-brand-50/40 p-3 text-xs leading-relaxed text-ink-700">{excerpt}</blockquote> : <p className="mt-2 text-xs text-ink-400">尚未取得正文片段，请点击原文核验。</p>}
      {signal.planningBaseline ? <p className="mt-2 text-xs leading-relaxed text-ink-500">长期规划作为产业背景，按来源发布日期展示；转载日期不等于文件印发日，转载也可能有删减。每日重新采集不代表新增催化。</p> : null}
      {historical ? <p className="mt-2 text-xs leading-relaxed text-ink-500">这是历史线索，新采集不等于新发布；请核验文件是否仍有效，以及后续落实、修订或替代情况。</p> : null}
      <p className="mt-2 text-[11px] text-ink-400">{signal.signalType}</p>
    </article>
  })}</div>
}

function ResearchList({ title, items }: { title: string; items: string[] }) {
  if (!items.length) return null
  return <section className="rounded-xl border border-line-soft p-4"><h4 className="mb-2 text-sm font-semibold text-ink-800">{title}</h4><ul className="space-y-2 text-xs leading-relaxed text-ink-600">{items.map((item, index) => <li key={index}>{index + 1}. {item}</li>)}</ul></section>
}
function Empty({ text }: { text: string }) { return <p className="rounded-xl border border-dashed border-line p-5 text-sm leading-relaxed text-ink-500">{text}</p> }
