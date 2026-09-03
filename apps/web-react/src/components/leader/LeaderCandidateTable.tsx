import type { ReactNode } from 'react'
import { changeClass, formatAmount, formatNumber, formatPercent, formatSignedPercent } from '../../lib/format'
import type {
  PotentialLeaderCandidate,
  PotentialLeaderDirection,
  PotentialLeaderGrade,
  PotentialLeaderLane,
  PotentialLeaderStatus
} from '../../types'
import { ScoreBadge, Tag } from '../ui/Badge'
import { Card } from '../ui/Card'
import { DataTable, type Column } from '../ui/DataTable'

interface LeaderCandidateTableProps {
  lane: PotentialLeaderLane
  status: PotentialLeaderStatus
  candidates: PotentialLeaderCandidate[]
  directions: PotentialLeaderDirection[]
  selected?: PotentialLeaderCandidate | null
  onSelect: (candidate: PotentialLeaderCandidate) => void
}

export function LeaderCandidateTable({
  lane,
  status,
  candidates,
  directions,
  selected,
  onSelect
}: LeaderCandidateTableProps) {
  const potential = lane === 'POTENTIAL'
  const title = potential ? '潜龙' : '竞龙'
  const subtitle = potential
    ? '尚未触板的主线早期候选；正式分不含龙虎榜与 AI。'
    : '涨停与炸板池中的辨识度竞争；龙虎榜只作 -5～+10 软调整。'
  const directionRanks = new Map(directions.map((direction) => [direction.industry, direction.rank]))

  return (
    <Card
      title={(
        <div>
          <div className="flex items-center gap-2">
            <span>{title}</span>
            <Tag tone={potential ? 'sky' : 'brand'}>{candidates.length} 只</Tag>
          </div>
          <p className="mt-1 text-xs font-normal leading-relaxed text-ink-500">{subtitle}</p>
        </div>
      )}
      flush
      className="min-w-0"
    >
      <DataTable
        columns={potential ? potentialColumns(directionRanks) : competitionColumns(directionRanks)}
        data={candidates}
        rowKey={(candidate) => `${candidate.lane}:${candidate.symbol}`}
        isSelected={(candidate) => selected?.lane === candidate.lane && selected.symbol === candidate.symbol}
        onRowClick={onSelect}
        emptyText={emptyText(title, status)}
        tableClassName={potential ? 'min-w-[1020px]' : 'min-w-[1130px]'}
      />
    </Card>
  )
}

function potentialColumns(directionRanks: Map<string, number>): Column<PotentialLeaderCandidate>[] {
  return [
    rankGradeColumn(),
    scoreColumn(),
    stockColumn(directionRanks),
    {
      key: 'momentum', title: '涨幅 / 量比', align: 'right', width: '105px', render: (candidate) => (
        <MetricStack
          first={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>}
          second={`${formatNumber(candidate.realtimeVolumeRatio)}x`}
        />
      )
    },
    {
      key: 'liquidity', title: '换手 / 成交额', align: 'right', width: '120px', render: (candidate) => (
        <MetricStack first={formatPercent(candidate.turnoverRate)} second={formatAmount(candidate.amount)} />
      )
    },
    {
      key: 'market-cap', title: '流通市值', align: 'right', width: '105px', render: (candidate) => (
        <span className="tabular text-xs font-medium text-ink-700">{formatAmount(candidate.circulatingMarketValue)}</span>
      )
    },
    {
      key: 'breakout', title: '突破位置', align: 'right', width: '100px', render: (candidate) => (
        <MetricStack
          first={formatPercent(candidate.breakoutPositionPercent)}
          second={`MA20斜率 ${formatPercent(candidate.ma20Slope)}`}
        />
      )
    },
    riskColumn()
  ]
}

function competitionColumns(directionRanks: Map<string, number>): Column<PotentialLeaderCandidate>[] {
  return [
    rankGradeColumn(),
    scoreColumn(),
    stockColumn(directionRanks),
    {
      key: 'boards', title: '板位', align: 'center', width: '82px', render: (candidate) => (
        <div className="flex flex-col items-center gap-1">
          <span className="tabular font-semibold text-ink-900">{candidate.consecutiveBoards} 板</span>
          <Tag tone={candidate.currentlySealed ? 'success' : 'warning'}>
            {candidate.currentlySealed ? '封板' : '炸板'}
          </Tag>
        </div>
      )
    },
    {
      key: 'seal-time', title: '首封 / 炸板', align: 'right', width: '105px', render: (candidate) => (
        <MetricStack first={candidate.firstSealTime ?? '—'} second={`${candidate.sealBreakCount} 次`} />
      )
    },
    {
      key: 'seal-strength', title: '封板强度', align: 'right', width: '105px', render: (candidate) => (
        <MetricStack first={formatSealRatio(candidate)} second={formatAmount(candidate.sealFunds)} />
      )
    },
    {
      key: 'turnover', title: '换手 / 成交额', align: 'right', width: '120px', render: (candidate) => (
        <MetricStack first={formatPercent(candidate.turnoverRate)} second={formatAmount(candidate.amount)} />
      )
    },
    {
      key: 'dragon-tiger', title: '龙虎榜调整', align: 'right', width: '115px', render: (candidate) => (
        <MetricStack
          first={signedPoints(candidate.dragonTiger.adjustment)}
          second={disclosureLabel(candidate.dragonTiger.status)}
        />
      )
    },
    riskColumn()
  ]
}

function rankGradeColumn(): Column<PotentialLeaderCandidate> {
  return {
    key: 'rank-grade', title: '名次 / 等级', align: 'center', width: '88px', render: (candidate) => (
      <div className="flex flex-col items-center gap-1">
        <span className="tabular text-xs font-semibold text-ink-500">#{candidate.rank}</span>
        <Tag tone={gradeTone(candidate.grade)}>{candidate.grade} · {gradeLabel(candidate.grade)}</Tag>
      </div>
    )
  }
}

function scoreColumn(): Column<PotentialLeaderCandidate> {
  return {
    key: 'score', title: '正式分', align: 'center', width: '82px', render: (candidate) => (
      <ScoreBadge value={candidate.score} />
    )
  }
}

function stockColumn(directionRanks: Map<string, number>): Column<PotentialLeaderCandidate> {
  return {
    key: 'stock', title: '股票 / 行业', width: '160px', render: (candidate) => (
      <div>
        <div className="font-semibold text-ink-900">{candidate.name}</div>
        <div className="tabular mt-0.5 text-xs text-ink-400">{candidate.symbol} · {candidate.industry}</div>
        <div className="mt-0.5 text-[11px] text-brand-600">方向 #{directionRanks.get(candidate.industry) ?? '—'}</div>
      </div>
    )
  }
}

function riskColumn(): Column<PotentialLeaderCandidate> {
  return {
    key: 'risk', title: '风险扣分', width: '170px', render: (candidate) => (
      candidate.riskDeductions.length ? (
        <div className="flex flex-wrap gap-1">
          {candidate.riskDeductions.map((risk) => (
            <Tag key={risk.code} tone="warning">-{formatCompact(risk.points)} {risk.label}</Tag>
          ))}
        </div>
      ) : <span className="text-xs text-ink-400">无触发项</span>
    )
  }
}

function MetricStack({ first, second }: { first: ReactNode; second: ReactNode }) {
  return (
    <div className="tabular text-xs leading-5 text-ink-500">
      <div className="font-semibold text-ink-900">{first}</div>
      <div>{second}</div>
    </div>
  )
}

function emptyText(title: string, status: PotentialLeaderStatus) {
  if (status === 'READY') return `${title}暂无达到 55 分的候选`
  if (status === 'STALE') return `${title}陈旧快照中没有候选`
  if (status === 'UNAVAILABLE') return `${title}数据不可用，未形成正式候选`
  return `${title}数据准备中，尚未形成正式候选`
}

function gradeLabel(grade: PotentialLeaderGrade) {
  return { A: '领涨', B: '竞争', C: '观察' }[grade]
}

function gradeTone(grade: PotentialLeaderGrade): 'success' | 'brand' | 'neutral' {
  return grade === 'A' ? 'success' : grade === 'B' ? 'brand' : 'neutral'
}

function disclosureLabel(status: PotentialLeaderCandidate['dragonTiger']['status']) {
  return {
    DISCLOSED: '已披露',
    NOT_DISCLOSED: '未披露',
    FETCH_FAILED: '抓取失败',
    STALE_DISCLOSURE: '历史披露'
  }[status]
}

function signedPoints(value: number) {
  const number = Number(value)
  return `${number > 0 ? '+' : ''}${number.toFixed(2)} 分`
}

function formatSealRatio(candidate: PotentialLeaderCandidate) {
  if (!candidate.currentlySealed || !candidate.sealFunds || !candidate.amount) return '0.00%'
  return formatPercent((candidate.sealFunds / candidate.amount) * 100)
}

function formatCompact(value: number | null | undefined) {
  if (value === null || value === undefined) return '0'
  return Number(value).toFixed(2).replace(/\.00$/, '')
}
