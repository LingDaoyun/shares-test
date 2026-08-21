import { AlertTriangle, CheckCircle2, CircleDashed, Info } from 'lucide-react'
import type { ReactNode } from 'react'
import { formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type {
  ShortTermLeaderRisk,
  ShortTermLeaderRiskBaseline,
  ShortTermLeaderRiskSignal,
  ShortTermLeaderRiskStatus,
  ShortTermLeaderRiskTrack
} from '../../types'
import { Tag } from '../ui/Badge'
import { Card } from '../ui/Card'

interface ShortTermLeaderRiskCardProps {
  risk: ShortTermLeaderRisk | null | undefined
}

const baselineLabels: Record<ShortTermLeaderRiskBaseline, string> = {
  PREVIOUS_SCAN: '当天最近后台观察点',
  PREVIOUS_TRADING_DAY: '上一交易日尾盘观察点',
  INITIAL: '尚无后台观察点'
}

const trackLabels: Record<ShortTermLeaderRiskTrack, string> = {
  WEIGHT: '权重线',
  THEME: '题材线'
}

const statusPresentation: Record<ShortTermLeaderRiskStatus, {
  label: string
  tone: 'warning' | 'sky' | 'success' | 'neutral'
  cardClass: string
  eyebrowClass: string
  icon: ReactNode
}> = {
  WARNING: {
    label: '发现资金切换风险',
    tone: 'warning',
    cardClass: 'border-amber-200 bg-amber-50/60',
    eyebrowClass: 'text-amber-700',
    icon: <AlertTriangle className="h-4 w-4 text-amber-600" aria-hidden="true" />
  },
  BASELINE_BUILDING: {
    label: '等待后台观察',
    tone: 'sky',
    cardClass: 'border-sky-200 bg-sky-50/60',
    eyebrowClass: 'text-sky-700',
    icon: <CircleDashed className="h-4 w-4 text-sky-600" aria-hidden="true" />
  },
  CLEAR: {
    label: '暂未发现明显异动',
    tone: 'success',
    cardClass: 'border-emerald-200 bg-emerald-50/40',
    eyebrowClass: 'text-emerald-700',
    icon: <CheckCircle2 className="h-4 w-4 text-emerald-600" aria-hidden="true" />
  },
  UNAVAILABLE: {
    label: '本次无法判断',
    tone: 'neutral',
    cardClass: 'border-line-soft bg-line-soft/30',
    eyebrowClass: 'text-ink-500',
    icon: <Info className="h-4 w-4 text-ink-400" aria-hidden="true" />
  }
}

export function ShortTermLeaderRiskCard({ risk }: ShortTermLeaderRiskCardProps) {
  if (!risk) return null

  const presentation = statusPresentation[risk.status]
  const hasRecededSignals = risk.signals.some((signal) => signal.movementState === 'RECEDED')
  const statusLabel = risk.status === 'CLEAR' && hasRecededSignals
    ? '今日异动已回落'
    : presentation.label
  const waitingForCheckpoint = risk.status === 'BASELINE_BUILDING'
  const unavailable = risk.status === 'UNAVAILABLE'

  return (
    <div aria-live={risk.status === 'WARNING' ? 'polite' : undefined}>
      <Card
        className={presentation.cardClass}
        title={(
          <span className="inline-flex items-center gap-2">
            {presentation.icon}
            龙头资金切换提醒
          </span>
        )}
        extra={<Tag tone={presentation.tone}>{statusLabel}</Tag>}
      >
        <p className={`text-sm font-semibold leading-relaxed text-ink-800 ${presentation.eyebrowClass}`}>
          {risk.summary}
        </p>
        {risk.evidence ? (
          <p className="mt-1 text-xs leading-relaxed text-ink-600">判断依据：{risk.evidence}</p>
        ) : null}

        <p className="mt-3 rounded-lg bg-white/70 px-3 py-2 text-xs leading-relaxed text-ink-600">
          后台每天在 09:50、11:30、14:40 自动观察，你无需重复点击扫描。
          {waitingForCheckpoint ? ' 当前尚无可比较的后台观察点，这不代表没有风险。' : ''}
        </p>

        <div className="mt-4 grid gap-x-6 gap-y-3 border-y border-line-soft py-3 text-xs md:grid-cols-2 xl:grid-cols-4">
          <RiskFact
            label="最近对比点"
            value={baselineSummary(risk.baselineType, risk.baselineAt)}
          />
          <RiskFact
            label="候选结构"
            value={unavailable
              ? '暂无法评估'
              : `候选集中：${candidateConcentration(risk.dominantCandidateIndustry, risk.candidateConcentrationPercent)}`}
          />
          <RiskFact
            label="与候选方向的关系"
            value={directionRelationship(risk.status, risk.directionConflict)}
            valueClass={isEvaluatedStatus(risk.status) && risk.directionConflict ? 'text-amber-800' : 'text-ink-700'}
          />
          <RiskFact label="评估时间" value={formatDateTime(risk.evaluatedAt)} />
        </div>

        {risk.signals.length ? (
          <div className="mt-4">
            <div className="text-xs font-semibold text-ink-700">今日龙头变化</div>
            <div className="mt-1 divide-y divide-line-soft border-y border-line-soft">
              {risk.signals.map((signal, index) => (
                <LeaderRiskSignalRow
                  key={`${signal.track}-${signal.symbol}-${index}`}
                  signal={signal}
                />
              ))}
            </div>
          </div>
        ) : null}

        {risk.dataGaps.length ? (
          <div className="mt-4 text-xs leading-relaxed text-ink-500">
            <span className="font-semibold text-ink-600">数据缺口：</span>
            {risk.dataGaps.join('；')}
          </div>
        ) : null}

        <p className="mt-4 border-t border-line-soft pt-3 text-xs font-medium leading-relaxed text-ink-600">
          仅作风险提示，不参与候选筛选、评分、排序或交易动作
        </p>
      </Card>
    </div>
  )
}

function LeaderRiskSignalRow({ signal }: { signal: ShortTermLeaderRiskSignal }) {
  const changeFacts = [
    signal.currentChangePercent !== null
      ? `当前涨跌 ${formatSignedPercent(signal.currentChangePercent)}`
      : null,
    signal.baselineChangePercent !== null
      ? `基线涨跌 ${formatSignedPercent(signal.baselineChangePercent)}`
      : null,
    signal.changeDeltaPercentPoints !== null
      ? `涨幅差 ${formatPercentPoints(signal.changeDeltaPercentPoints)}`
      : null
  ].filter((item): item is string => item !== null)

  const rankFact = amountRankSummary(signal.currentAmountRank, signal.baselineAmountRank)
  const movement = movementPresentation(signal.movementState)

  return (
    <div className="py-3 first:pt-2 last:pb-2">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={signal.track === 'WEIGHT' ? 'brand' : 'sky'}>{trackLabels[signal.track]}</Tag>
        <Tag tone={movement.tone}>{movement.label}</Tag>
        <span className="text-sm font-semibold text-ink-800">{signal.name}</span>
        <span className="font-mono text-xs text-ink-500">{signal.symbol}</span>
        {signal.direction ? <Tag tone="neutral">{signal.direction}</Tag> : null}
      </div>

      <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink-600">
        {signal.detectedAt ? (
          <span className="tabular">发现时间 {formatDateTime(signal.detectedAt)}</span>
        ) : null}
        {changeFacts.map((fact) => <span key={fact} className="tabular">{fact}</span>)}
        {rankFact ? <span className="tabular">{rankFact}</span> : null}
        {signal.amountSharePercent !== null ? (
          <span className="tabular">成交额占比 {formatPercent(signal.amountSharePercent)}</span>
        ) : null}
        {signal.totalMarketValue !== null ? (
          <span className="tabular">总市值 {formatAmount(signal.totalMarketValue)}</span>
        ) : null}
      </div>

      {signal.reason ? (
        <p className="mt-2 text-xs leading-relaxed text-ink-700">{signal.reason}</p>
      ) : null}
    </div>
  )
}

function RiskFact({ label, value, valueClass = 'text-ink-700' }: {
  label: string
  value: string
  valueClass?: string
}) {
  return (
    <div>
      <div className="text-ink-400">{label}</div>
      <div className={`mt-1 font-semibold leading-relaxed ${valueClass}`}>{value}</div>
    </div>
  )
}

function baselineSummary(type: ShortTermLeaderRiskBaseline, baselineAt: string | null) {
  const label = baselineLabels[type]
  return baselineAt ? `${label} · ${formatDateTime(baselineAt)}` : label
}

function candidateConcentration(industry: string | null, concentrationPercent: number | null) {
  if (industry && concentrationPercent !== null) {
    return `${industry} ${formatPercent(concentrationPercent)}`
  }
  if (industry) return industry
  if (concentrationPercent !== null) return formatPercent(concentrationPercent)
  return '待补充'
}

function directionRelationship(status: ShortTermLeaderRiskStatus, directionConflict: boolean) {
  if (status === 'UNAVAILABLE') return '暂无法评估'
  if (status === 'BASELINE_BUILDING') return '等待下一个后台观察点'
  return directionConflict ? '候选方向与异动方向冲突' : '未发现候选方向冲突'
}

function isEvaluatedStatus(status: ShortTermLeaderRiskStatus) {
  return status === 'WARNING' || status === 'CLEAR'
}

function amountRankSummary(currentRank: number | null, baselineRank: number | null) {
  if (currentRank !== null && baselineRank !== null) {
    return `成交额排名 #${currentRank}（基线 #${baselineRank}）`
  }
  if (currentRank !== null) return `成交额排名 #${currentRank}`
  if (baselineRank !== null) return `基线成交额排名 #${baselineRank}`
  return null
}

function formatPercentPoints(value: number) {
  return formatSignedPercent(value).replace('%', ' 个百分点')
}

function movementPresentation(state: ShortTermLeaderRiskSignal['movementState']) {
  if (state === 'ONGOING') return { label: '仍在强化', tone: 'warning' as const }
  if (state === 'RECEDED') return { label: '已经回落', tone: 'neutral' as const }
  return { label: '本次发现', tone: 'sky' as const }
}
