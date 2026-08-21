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
  PREVIOUS_SCAN: '盘中上次扫描',
  PREVIOUS_TRADING_DAY: '上一交易日',
  INITIAL: '基线建立中'
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
    label: '异动警示',
    tone: 'warning',
    cardClass: 'border-amber-200 bg-amber-50/60',
    eyebrowClass: 'text-amber-700',
    icon: <AlertTriangle className="h-4 w-4 text-amber-600" aria-hidden="true" />
  },
  BASELINE_BUILDING: {
    label: '基线建立中',
    tone: 'sky',
    cardClass: 'border-sky-200 bg-sky-50/60',
    eyebrowClass: 'text-sky-700',
    icon: <CircleDashed className="h-4 w-4 text-sky-600" aria-hidden="true" />
  },
  CLEAR: {
    label: '当前平稳',
    tone: 'success',
    cardClass: 'border-emerald-200 bg-emerald-50/40',
    eyebrowClass: 'text-emerald-700',
    icon: <CheckCircle2 className="h-4 w-4 text-emerald-600" aria-hidden="true" />
  },
  UNAVAILABLE: {
    label: '暂不可用',
    tone: 'neutral',
    cardClass: 'border-line-soft bg-line-soft/30',
    eyebrowClass: 'text-ink-500',
    icon: <Info className="h-4 w-4 text-ink-400" aria-hidden="true" />
  }
}

export function ShortTermLeaderRiskCard({ risk }: ShortTermLeaderRiskCardProps) {
  if (!risk) return null

  const presentation = statusPresentation[risk.status]

  return (
    <div aria-live={risk.status === 'WARNING' ? 'polite' : undefined}>
      <Card
        className={presentation.cardClass}
        title={(
          <span className="inline-flex items-center gap-2">
            {presentation.icon}
            龙头异动风险
          </span>
        )}
        extra={<Tag tone={presentation.tone}>{presentation.label}</Tag>}
      >
        <div className={`eyebrow ${presentation.eyebrowClass}`}>LEADER ROTATION WATCH</div>
        <p className="mt-2 text-sm font-semibold leading-relaxed text-ink-800">{risk.summary}</p>
        {risk.evidence ? (
          <p className="mt-1 text-xs leading-relaxed text-ink-600">依据：{risk.evidence}</p>
        ) : null}

        <div className="mt-4 grid gap-x-6 gap-y-3 border-y border-line-soft py-3 text-xs md:grid-cols-2 xl:grid-cols-4">
          <RiskFact
            label="对比基线"
            value={baselineSummary(risk.baselineType, risk.baselineAt)}
          />
          <RiskFact
            label="候选结构"
            value={risk.status === 'UNAVAILABLE'
              ? '暂无法评估'
              : `候选集中：${candidateConcentration(risk.dominantCandidateIndustry, risk.candidateConcentrationPercent)}`}
          />
          <RiskFact
            label="方向关系"
            value={directionRelationship(risk.status, risk.directionConflict)}
            valueClass={isEvaluatedStatus(risk.status) && risk.directionConflict ? 'text-amber-800' : 'text-ink-700'}
          />
          <RiskFact label="评估时间" value={formatDateTime(risk.evaluatedAt)} />
        </div>

        {risk.signals.length ? (
          <div className="mt-4">
            <div className="text-xs font-semibold text-ink-700">异动信号</div>
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

  return (
    <div className="py-3 first:pt-2 last:pb-2">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={signal.track === 'WEIGHT' ? 'brand' : 'sky'}>{trackLabels[signal.track]}</Tag>
        <span className="text-sm font-semibold text-ink-800">{signal.name}</span>
        <span className="font-mono text-xs text-ink-500">{signal.symbol}</span>
        {signal.direction ? <Tag tone="neutral">{signal.direction}</Tag> : null}
      </div>

      <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink-600">
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
  if (status === 'BASELINE_BUILDING') return '待下一次可靠扫描确认'
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
