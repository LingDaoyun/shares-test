import type { UpperShadowCandidate, UpperShadowScan } from '../../api/dailyShortTerm'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import { Tag } from '../ui/Badge'

export function UpperShadowCandidateRow({ candidate, selected, onSelect }: {
  candidate: UpperShadowCandidate
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button type="button" onClick={onSelect}
      className={`grid w-full grid-cols-1 gap-3 px-5 py-4 text-left transition hover:bg-brand-50/70 md:grid-cols-[minmax(0,1.2fr)_1fr_auto] ${selected ? 'bg-brand-50' : 'bg-white'}`}>
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="truncate text-base font-semibold text-ink-900">{candidate.name}</h3>
          <span className="font-mono text-xs text-ink-400">{candidate.symbol}</span>
          <Tag tone="warning">横盘长上影</Tag>
        </div>
        <p className="mt-1 text-xs text-ink-500">行业：{candidate.industry || '行业待补'}</p>
        <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-ink-500">{candidate.evidence.join('；')}</p>
        <p className="mt-2 text-xs text-ink-400">行情 {formatDateTime(candidate.marketTimestamp)}</p>
      </div>
      <div className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
        <ShapeMetric label="每股" value={formatPerSharePrice(candidate.latestPrice)} />
        <ShapeMetric label="涨跌幅" value={formatSignedPercent(candidate.changePercent)} className={changeClass(candidate.changePercent)} />
        <ShapeMetric label="20日箱体振幅" value={`${formatNumber(candidate.boxRangePercent)}%`} />
        <ShapeMetric label="上影占比" value={`${formatNumber(candidate.upperShadowRatioPercent)}%`} />
      </div>
      <div className="flex items-center md:justify-end"><Tag tone="neutral">形态观察</Tag></div>
    </button>
  )
}

export function UpperShadowCandidateDetail({ candidate, scan }: { candidate: UpperShadowCandidate; scan: UpperShadowScan }) {
  return (
    <section className="flex flex-col gap-3" aria-label="横盘长上影证据">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-semibold text-ink-900">横盘长上影证据</h3>
        <Tag tone="warning">横盘长上影</Tag>
        <span className="text-xs text-ink-500">{scan.tradeDate} · 形态观察</span>
      </div>
      <p className="text-xs leading-relaxed text-ink-500">{scan.ruleDescription}</p>
      <p className="text-xs leading-relaxed text-ink-500">行业：{candidate.industry || '行业待补'} · 行情 {formatDateTime(candidate.marketTimestamp)} · 扫描 {formatDateTime(scan.assessedAt)} · {scan.source}</p>
      <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
        <ShapeMetric label="每股价格" value={formatPerSharePrice(candidate.latestPrice)} />
        <ShapeMetric label="涨跌幅" value={formatSignedPercent(candidate.changePercent)} className={changeClass(candidate.changePercent)} />
        <ShapeMetric label="20日箱体振幅" value={`${formatNumber(candidate.boxRangePercent)}%`} />
        <ShapeMetric label="箱体首尾涨跌" value={formatSignedPercent(candidate.boxNetChangePercent)} />
        <ShapeMetric label="箱体低点 / 高点" value={`${formatPerSharePrice(candidate.boxLow)} / ${formatPerSharePrice(candidate.boxHigh)}`} />
        <ShapeMetric label="上影占比 / 较昨收" value={`${formatNumber(candidate.upperShadowRatioPercent)}% / ${formatNumber(candidate.upperShadowPercent)}%`} />
        <ShapeMetric label="成交额" value={formatAmount(candidate.amount)} />
        <ShapeMetric label="换手率" value={candidate.turnoverRate == null ? '换手待补' : `${formatNumber(candidate.turnoverRate)}%`} />
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <EvidenceList title="形态依据" items={candidate.evidence} />
        <EvidenceList title="反证与风险" items={candidate.counterEvidence} warning />
      </div>
      {scan.dataGaps.length > 0 ? <p className="text-xs leading-relaxed text-amber-700">{scan.dataGaps.join('；')}</p> : null}
    </section>
  )
}

function ShapeMetric({ label, value, className = '' }: { label: string; value: string; className?: string }) {
  return <div className="rounded-lg border border-line-soft bg-line-soft/40 px-2.5 py-2">
    <div className="text-xs text-ink-400">{label}</div>
    <div className={`mt-1 break-words tabular text-sm font-semibold text-ink-900 ${className}`}>{value}</div>
  </div>
}

function EvidenceList({ title, items, warning = false }: { title: string; items: string[]; warning?: boolean }) {
  return <div className="rounded-lg border border-line-soft p-3">
    <Tag tone={warning ? 'warning' : 'brand'}>{title}</Tag>
    <ul className={`mt-2 flex flex-col gap-1.5 text-xs leading-relaxed ${warning ? 'text-amber-700' : 'text-ink-600'}`}>
      {items.map(item => <li key={item}>{item}</li>)}
    </ul>
  </div>
}
