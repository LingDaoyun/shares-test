import { formatAmount, formatNumber, formatPercent } from '../../lib/format'
import { DecisionTag } from './FiveDayContextPanel'
import type { FiveDayCandidate, FiveDayCandidateAssessment } from '../../types/fiveDay'
import { Tag } from '../ui/Badge'
import { DataTable, type Column } from '../ui/DataTable'

interface FiveDayCandidateTableProps {
  candidates: FiveDayCandidate[]
  assessments?: FiveDayCandidateAssessment[]
  selectedSymbol: string | null
  onSelect: (candidate: FiveDayCandidate) => void
  emptyText: string
}

export function flowLabel(candidate: Pick<FiveDayCandidate, 'flowDataComplete' | 'matchesFundFlow'>) {
  if (!candidate.flowDataComplete) return '资金缺失'
  return candidate.matchesFundFlow ? '资金条件合格' : '资金条件未满足'
}

export function FiveDayCandidateTable({ candidates, selectedSymbol, onSelect, emptyText, assessments }: FiveDayCandidateTableProps) {
  const columns: Column<FiveDayCandidate>[] = [
    {
      key: 'stock', title: '观察股',
      render: (candidate) => (
        <div>
          <div className="font-semibold text-ink-900">{candidate.name}</div>
          <div className="mt-1 text-xs text-ink-500">{candidate.symbol} · {candidate.industry || candidate.board}</div>
        </div>
      )
    },
    {
      key: 'range', title: '横盘区间 / 振幅',
      render: (candidate) => (
        <div className="tabular">
          <div>{formatNumber(candidate.rangeLow)}–{formatNumber(candidate.rangeHigh)}</div>
          <div className="mt-1 text-xs text-ink-500">振幅 {formatPercent(candidate.rangePercent)}</div>
        </div>
      )
    },
    {
      key: 'turnover', title: '换手提升',
      render: (candidate) => (
        <div className="tabular">
          <div className="font-semibold">{formatNumber(candidate.turnoverRatio)} 倍</div>
          <div className="mt-1 text-xs text-ink-500">{formatPercent(candidate.priorTurnover)} → {formatPercent(candidate.recentTurnover)}</div>
        </div>
      )
    },
    {
      key: 'flow', title: '主力净额占比 / 净额',
      render: (candidate) => candidate.flowDataComplete ? (
        <div className="tabular">
          <div>{formatPercent(candidate.mainNetInflowRatio)}</div>
          <div className="mt-1 text-xs text-ink-500">{formatAmount(candidate.mainNetInflow)} · {candidate.positiveFlowDays} 日为正</div>
        </div>
      ) : <Tag tone="warning">资金数据缺失</Tag>
    },
    {
      key: 'amount', title: '近 5 日日均成交额',
      render: (candidate) => <span className="tabular">{formatAmount(candidate.averageAmount)}</span>
    },
    {
      key: 'plan', title: '入场 / 第 5 日',
      render: (candidate) => (
        <div className="tabular text-xs leading-6">
          <div>{candidate.entryDate || '日历待核实'}</div>
          <div className="text-ink-500">{candidate.plannedExitDate || '日历待核实'}</div>
        </div>
      )
    },
    {
      key: 'status', title: assessments ? '条件判断' : '资金条件',
      render: (candidate) => assessments ? <DecisionTag decision={assessments.find((item) => item.symbol === candidate.symbol)?.decision} /> : <Tag tone={!candidate.flowDataComplete ? 'warning' : candidate.matchesFundFlow ? 'success' : 'neutral'}>{flowLabel(candidate)}</Tag>
    }
  ]

  return (
    <DataTable
      columns={columns}
      data={candidates}
      rowKey={(candidate) => candidate.symbol}
      isSelected={(candidate) => candidate.symbol === selectedSymbol}
      onRowClick={onSelect}
      emptyText={emptyText}
      tableClassName="min-w-[1040px]"
    />
  )
}
