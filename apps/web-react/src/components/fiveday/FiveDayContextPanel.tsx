import { formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { FiveDayCandidateAssessment, FiveDayContext, FiveDayEventReview, FiveDayEvidence, FiveDaySector } from '../../types/fiveDay'
import { Tag } from '../ui/Badge'
import { Card } from '../ui/Card'
import { DataTable, type Column } from '../ui/DataTable'

const MARKET_LABELS: Record<string, string> = {
  NORMAL: '市场环境正常', CAUTION: '市场谨慎观察', RISK_OFF: '市场风险偏高', UNKNOWN: '市场数据未知'
}
const SECTOR_LABELS: Record<string, string> = { STRONG: '强势', WEAK: '弱势', UNKNOWN: '数据未知' }
const DECISION_LABELS: Record<string, string> = {
  ELIGIBLE: '执行条件齐备', WATCH: '继续观察', AVOID: '风险线索待核实', UNKNOWN: '数据未知'
}
const SOURCE_LABELS: Record<string, string> = {
  READY: '来源可用', AVAILABLE: '来源可用', COMPLETE: '来源可用', OK: '来源可用', PARTIAL: '来源部分可用',
  UNKNOWN: '来源状态未知', UNAVAILABLE: '来源不可用', EMPTY: '未取得相关原文',
  RECENT: '近期公开', IN_WINDOW: '近期公开', DATE_UNKNOWN: '公开日期未知', UNKNOWN_DATE: '公开日期未知',
  FUTURE: '未来日期 · 不作当前催化', FUTURE_DATE: '未来日期 · 不作当前催化',
  OLD: '窗口外旧信息', STALE: '窗口外旧信息', OUTSIDE_WINDOW: '窗口外旧信息'
}

export function decisionLabel(decision?: string | null) {
  return DECISION_LABELS[decision || 'UNKNOWN'] || '状态待核实'
}

export function DecisionTag({ decision }: { decision?: string | null }) {
  return <Tag tone={decision === 'ELIGIBLE' ? 'success' : decision === 'AVOID' ? 'danger' : decision === 'UNKNOWN' || !decision ? 'warning' : 'neutral'}>{decisionLabel(decision)}</Tag>
}

export function FiveDayContextPanel({ context, signalDate }: { context: FiveDayContext | null | undefined; signalDate: string | null }) {
  if (!context) return <Card title="市场环境与行业主线"><p className="text-sm text-amber-800">上下文数据未知，暂不能确认执行条件齐备。请读取 v2 快照或手动扫描。</p></Card>
  const market = context.market
  const sectorColumns: Column<FiveDaySector>[] = [
    { key: 'industry', title: '行业 / 状态', render: (sector) => <div><div className="mb-1 font-semibold">{sector.industry}</div><Tag tone={sector.state === 'STRONG' ? 'success' : sector.state === 'UNKNOWN' ? 'warning' : 'neutral'}>{SECTOR_LABELS[sector.state] || '状态待核实'}</Tag></div> },
    { key: 'rank', title: '5 日收益排名', render: (sector) => sector.rank > 0 ? `第 ${sector.rank} 名` : '未排名' },
    { key: 'return', title: '3 / 5 日等权收益', render: (sector) => `${formatSignedPercent(sector.return3Percent)} / ${formatSignedPercent(sector.return5Percent)}` },
    { key: 'excess', title: '3 / 5 日超额', render: (sector) => `${formatSignedPercent(sector.excess3Percent)} / ${formatSignedPercent(sector.excess5Percent)}` },
    { key: 'participation', title: '5 日上涨 / 放量参与', render: (sector) => `${formatPercent(sector.advancing5Percent)} / ${formatPercent(sector.volumeExpansionPercent)}` },
    { key: 'coverage', title: '完整 25 日样本覆盖', render: (sector) => `${sector.coveredCount} / ${sector.memberCount} · ${formatPercent(sector.coveragePercent)}` },
    { key: 'reasons', title: '判断原因', render: (sector) => <ul className="max-w-[320px] text-xs leading-6">{sector.reasons.map((reason, index) => <li key={index}>{reason}</li>)}</ul> }
  ]
  return (
    <>
      <Card title="市场环境与行业主线">
        <div className="flex flex-wrap items-center gap-3">
          <Tag tone={market.state === 'NORMAL' ? 'success' : market.state === 'RISK_OFF' ? 'danger' : 'warning'}>{MARKET_LABELS[market.state] || '市场状态待核实'}</Tag>
          <span className="text-xs text-ink-500">环境判断采集于 {formatDateTime(context.assessedAt)}</span>
        </div>
        <p className="mt-2 text-xs leading-6 text-ink-500">市场日线信号日 {signalDate || '待核实'} · 行业日线信号日 {signalDate || '待核实'}。消息为采集时点可见的信息，不能当作历史信号日已知证据。</p>
        <div className="mt-4 grid grid-cols-2 gap-4 text-sm lg:grid-cols-4">
          <ContextMetric label="市场样本覆盖" value={`${market.coveredCount} / ${market.expectedCount} · ${formatPercent(market.coveragePercent)}`} />
          <ContextMetric label="上涨广度" value={formatPercent(market.advancingPercent)} />
          <ContextMetric label="收益中位数" value={formatSignedPercent(market.medianChangePercent)} />
          <ContextMetric label="单日跌幅至少 7% 的样本" value={formatPercent(market.severeDeclinePercent)} />
        </div>
        <Notes notes={market.reasons} />
        <p className="mt-3 text-xs leading-6 text-ink-500">风险偏高或数据未知不升级为执行条件齐备；谨慎环境只观察。市场正常也不代表个股已满足执行条件。</p>
        <div className="mt-4 max-h-[28rem] overflow-auto border-t border-line-soft pt-4" tabIndex={0} role="region" aria-label="行业主线数据，可滚动查看全部行业">
          <DataTable columns={sectorColumns} data={context.sectors} rowKey={(sector) => sector.industry} tableClassName="min-w-[1120px]" emptyText="行业样本数据未知，暂无法确认强势主线" />
        </div>
        <p className="mt-3 text-xs leading-6 text-ink-500">行业使用所选板块范围及冻结基线成员，至少 5 只且完整 25 日覆盖不少于 80%。3 / 5 日超额相对于同范围、同覆盖样本的等权基准。放量参与是同股近 5 日 / 此前 20 日平均成交量比 ≥ 1.1 的成员占比，不是板块净流入，也不是跨股票累加原始成交量。</p>
        <Notes notes={context.notes} />
      </Card>
      <Card title="公开政策线索">
        <p className="text-xs leading-6 text-ink-500">来源状态：{sourceLabel(context.policyStatus)}。展示配置来源近期公开政策；个股详情按细分行业关联，不证明公司主营受益。来源失败不代表没有风险，日期未知单列；政策不作为强制利好门槛。</p>
        <div className="max-h-[24rem] overflow-y-auto" tabIndex={0} role="region" aria-label="政策原文，可滚动查看全部线索">
          <EvidenceList evidence={context.policies} emptyText="当前未取得可展示的政策线索；不据此推断不存在相关政策或风险。" />
        </div>
      </Card>
    </>
  )
}

export function FiveDayAssessmentPanel({ assessment }: { assessment: FiveDayCandidateAssessment | undefined }) {
  return (
    <Card title="冻结条件判断与消息证据">
      <DecisionTag decision={assessment?.decision} />
      <p className="mt-2 text-xs leading-6 text-ink-500">执行条件齐备仍非下单指令；完整资金条件只是一项门槛。公告覆盖限最近公开公告，政策覆盖限配置来源，不表示覆盖全网新闻或传闻。</p>
      {assessment ? <>
        <Notes notes={assessment.reasons} />
        <div className="mt-4 border-t border-line-soft pt-4">
          <h4 className="text-sm font-semibold text-ink-900">个股转强 · {assessment.turning ? assessment.turning.confirmed ? '已确认' : '未确认' : '数据未知'}</h4>
          {assessment.turning ? <>
            <p className="mt-2 text-xs text-ink-600">收盘较近 5 日均价 {formatSignedPercent(assessment.turning.closeVsMa5Percent)} · 收盘在当日振幅位置 {formatPercent(assessment.turning.closePositionPercent)}</p>
            <Notes notes={assessment.turning.reasons} />
          </> : null}
          <p className="mt-2 text-xs leading-6 text-ink-500">信号日收盘高于前收盘、收盘不低于近 5 日均价、振幅位置至少 60% 才确认；平价日不确认转强，全部使用已完成日线。</p>
        </div>
        <div className="mt-4 border-t border-line-soft pt-4"><EventReview review={assessment.events} /></div>
        <div className="mt-4 border-t border-line-soft pt-4">
          <h4 className="text-sm font-semibold text-ink-900">相关政策原文</h4>
          <p className="mt-2 text-xs leading-6 text-ink-500">仅为与细分行业关键词直接关联的原文线索，不证明公司主营受益。</p>
          <EvidenceList evidence={assessment.policies} emptyText="未取得可关联的政策原文，不假设存在催化。" />
        </div>
      </> : <p className="mt-2 text-sm text-amber-800">该股条件判断数据未知，不能从资金合格推断执行资格。</p>}
    </Card>
  )
}

export function EventReview({ review }: { review: FiveDayEventReview | null }) {
  const noTitleRisk = review?.status === 'NO_TITLE_RISK'
  const risk = review?.status === 'TITLE_RISK' || review?.status === 'RISK_CLUE' || review?.status === 'RISK_FOUND'
  return <div>
    <h4 className="text-sm font-semibold text-ink-900">公司公告 · {noTitleRisk ? '标题未发现风险线索' : risk ? '风险线索待核实' : '状态待核实'}</h4>
    <p className="mt-2 text-xs leading-6 text-ink-500">标题未发现风险线索，不表示无风险。风险词只提示需要阅读原文，不能证明利空事实；来源不可用、日期不明或窗口覆盖不足均为未知。</p>
    {review ? <><Notes notes={review.notes} /><EvidenceList evidence={review.evidence} emptyText="当前没有可展示的公告原文，来源状态以本次审阅为准。" /></> : <p className="mt-2 text-xs text-amber-800">公告数据未知。</p>}
  </div>
}

export function EvidenceList({ evidence, emptyText }: { evidence: FiveDayEvidence[]; emptyText: string }) {
  if (!evidence.length) return <p className="mt-2 text-xs leading-6 text-ink-500">{emptyText}</p>
  const undated = evidence.filter((item) => !item.publishedAt || item.temporalStatus === 'DATE_UNKNOWN' || item.temporalStatus === 'UNKNOWN_DATE')
  const dated = evidence.filter((item) => !undated.includes(item))
  return <>
    <EvidenceItems evidence={dated} />
    {undated.length ? <div className="mt-3"><h5 className="text-xs font-semibold text-amber-800">公开日期未知的线索 · 不作已确认的新催化</h5><EvidenceItems evidence={undated} /></div> : null}
  </>
}

function EvidenceItems({ evidence }: { evidence: FiveDayEvidence[] }) {
  if (!evidence.length) return null
  return <ul className="mt-3 space-y-3 text-xs leading-6">
    {evidence.map((item, index) => {
      const url = safeEvidenceUrl(item.url)
      return <li key={`${item.url}-${index}`} className="border-l-2 border-line-soft pl-3">
        {url ? <a href={url} target="_blank" rel="noreferrer" className="font-semibold text-brand-600 underline">{item.title}</a> : <span className="font-semibold text-ink-700">{item.title} · 原文链接不可用</span>}
        <div className="text-ink-500">来源 {item.source || '待核实'} · {sourceLabel(item.temporalStatus)}</div>
        <div className="text-ink-500">公开时间 {item.publishedAt || '日期未知'} · 采集时间 {formatDateTime(item.observedAt)}</div>
        <div className="text-ink-600">{item.interpretation}</div>
      </li>
    })}
  </ul>
}

function safeEvidenceUrl(value: string | null) {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null
  } catch { return null }
}

function sourceLabel(status: string) { return SOURCE_LABELS[status] || status || '状态未知' }
function ContextMetric({ label, value }: { label: string; value: string }) { return <div><div className="text-xs text-ink-500">{label}</div><div className="tabular mt-1 font-semibold text-ink-900">{value}</div></div> }
function Notes({ notes }: { notes: string[] }) { return notes.length ? <ul className="mt-2 list-disc space-y-1 pl-5 text-xs leading-6 text-ink-600">{notes.map((note, index) => <li key={index}>{note}</li>)}</ul> : null }
