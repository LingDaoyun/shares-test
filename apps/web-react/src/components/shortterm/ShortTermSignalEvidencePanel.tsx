import { Tag } from '../ui/Badge'
import { formatNumber, formatPercent, formatSignedPercent } from '../../lib/format'
import type {
  ShortTermCandidate,
  ShortTermMarketRegime,
  ShortTermValidationSummary
} from '../../types'

export type ShortTermValidationViewState = 'IDLE' | 'LOADING' | 'READY' | 'FAILED'

interface ShortTermSignalEvidencePanelProps {
  candidate: ShortTermCandidate
  marketRegime: ShortTermMarketRegime | null | undefined
  summaries: ShortTermValidationSummary[]
  validationState: ShortTermValidationViewState
}

interface ContributionItem {
  label: string
  value: number
  subtotal?: boolean
}

export function ShortTermSignalEvidencePanel({
  candidate,
  marketRegime,
  summaries,
  validationState
}: ShortTermSignalEvidencePanelProps) {
  const signalProfile = candidate.signalProfile
  const family = signalProfile?.primaryFamily
  const regime = marketRegime?.state
  const cohortSummaries = summaries.filter((summary) => (
    summary.signalFamily === family && summary.marketRegime === regime
  ))
  const baseScore = candidate.score.finalScore
  const scoreSnapshotClosed = hasClosedShortTermScoreSnapshot(candidate)
  const setupScore = scoreSnapshotClosed ? candidate.score.technicalRankingScore as number : null
  const rankingScore = scoreSnapshotClosed ? candidate.score.rankingScore as number : null
  const stageAdjustment = setupScore === null ? null : setupScore - baseScore
  const visibleAdjustment = setupScore === null || rankingScore === null ? null : rankingScore - setupScore
  const contributions = scoreSnapshotClosed ? activeContributions(candidate) : []

  return (
    <section className="border-y border-line-soft py-4" aria-label="信号解释与历史验证">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-ink-900">信号解释与历史验证</h3>
          <p className="mt-1 text-xs leading-relaxed text-ink-500">
            推荐排序只使用当前时点可见证据；历史分组仅用于校准，不代表未来收益。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Tag tone={family && family !== 'UNAVAILABLE' ? 'brand' : 'neutral'}>
            {signalProfile?.primaryLabel ?? '信号族待补'}
          </Tag>
          <Tag tone={marketRegime?.state === 'RISK_OFF' ? 'warning' : marketRegime ? 'sky' : 'neutral'}>
            {marketRegime?.label ?? '市场状态待补'}
          </Tag>
          {candidate.volatilityQuality ? (
            <Tag tone={candidate.volatilityQuality.contractionBreakout ? 'success' : 'neutral'}>
              {candidate.volatilityQuality.label}
            </Tag>
          ) : null}
        </div>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line-soft bg-line-soft sm:grid-cols-5">
        <SignalMetric label="四因子底分" value={formatNumber(baseScore)} />
        <SignalMetric label="阶段结构分" value={setupScore === null ? '待补充' : formatNumber(setupScore)} />
        <SignalMetric label="排序分" value={rankingScore === null ? '待补充' : formatNumber(rankingScore)} />
        <SignalMetric label="市场宽度" value={formatPercent(marketRegime?.breadthPercent)} />
        <SignalMetric label="ATR 波动" value={formatPercent(candidate.volatilityQuality?.atrPercent)} />
      </div>

      {scoreSnapshotClosed && setupScore !== null && stageAdjustment !== null && visibleAdjustment !== null ? (
        <div className="mt-3 flex flex-col gap-1 border-l-2 border-line-soft pl-3 text-xs leading-relaxed text-ink-600 sm:flex-row sm:flex-wrap sm:gap-x-5">
          <span>四因子底分 {formatNumber(baseScore)} + 阶段校准 {signedScore(stageAdjustment)} = 结构分 {formatNumber(setupScore)}</span>
          <span>结构分 {formatNumber(setupScore)} + 可见调整 {signedScore(visibleAdjustment)} = 排序分 {formatNumber(rankingScore)}</span>
        </div>
      ) : (
        <div className="mt-3 border-l-2 border-amber-300 bg-amber-50/60 px-3 py-2 text-xs leading-relaxed text-amber-800">
          历史报告缺少阶段结构分或可见调整快照，无法闭合解释；不反推缺失贡献。
        </div>
      )}

      {contributions.length ? (
        <div className="mt-4">
          <p className="text-xs font-semibold text-ink-700">当前排序贡献</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {contributions.map((item) => (
              <span
                key={item.label}
                className={`inline-flex items-center gap-1 border px-2.5 py-1.5 text-xs ${item.subtotal ? 'border-line-soft bg-line-soft/40 text-ink-500' : 'border-brand-100 bg-brand-50/50 text-ink-700'}`}
              >
                <span>{item.label}</span>
                <strong className={item.value < 0 ? 'text-emerald-700' : 'text-brand-700'}>
                  {signedScore(item.value)}
                </strong>
              </span>
            ))}
          </div>
        </div>
      ) : null}

      {signalProfile?.evidence?.length ? (
        <div className="mt-4 border-l-2 border-brand-200 pl-3 text-xs leading-relaxed text-ink-600">
          {signalProfile.evidence.slice(0, 3).map((item) => <p key={item}>{item}</p>)}
        </div>
      ) : null}

      <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
        {(['T1', 'T2'] as const).map((horizon) => (
          <ValidationHorizon
            key={horizon}
            horizon={horizon}
            summary={cohortSummaries.find((item) => item.horizon === horizon)}
            state={validationState}
            cohortAvailable={Boolean(family && family !== 'UNAVAILABLE' && regime && regime !== 'UNAVAILABLE')}
          />
        ))}
      </div>
    </section>
  )
}

function ValidationHorizon({
  horizon,
  summary,
  state,
  cohortAvailable
}: {
  horizon: 'T1' | 'T2'
  summary: ShortTermValidationSummary | undefined
  state: ShortTermValidationViewState
  cohortAvailable: boolean
}) {
  if (!cohortAvailable) {
    return <ValidationShell title={`${horizon} · 分组依据待补`} detail="信号族或市场状态不足，未发起历史分组查询。" />
  }
  if (state === 'LOADING') {
    return <ValidationShell title={`${horizon} · 正在读取成熟样本`} detail="只统计已到达对应交易日的观测。" />
  }
  if (state === 'FAILED') {
    return <ValidationShell title={`${horizon} · 验证数据不可用`} detail="验证接口失败不改变当前候选排序，也不补造统计值。" />
  }
  if (!summary) {
    return <ValidationShell title={`${horizon} · 尚无分组结果`} detail="没有可展示的已成熟观测。" />
  }
  if (summary.status === 'VALIDATION_DISABLED') {
    return (
      <ValidationShell
        title={`${horizon} · 历史验证已关闭`}
        detail="后台未启用观测校准，不展示样本数、胜率或收益统计。"
      />
    )
  }
  if (summary.status !== 'AVAILABLE' || summary.sampleCount < summary.minimumSampleCount) {
    return (
      <ValidationShell
        title={`${horizon} · 样本积累中 ${summary.sampleCount}/${summary.minimumSampleCount}`}
        detail="达到最低样本门槛前不展示胜率或收益统计。"
      />
    )
  }
  return (
    <div className="border border-line-soft bg-white px-3 py-3">
      <p className="text-xs font-semibold text-ink-800">{horizon} · {summary.sampleCount} 个已成熟样本</p>
      <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-ink-600">
        <span>正收益占比 {formatPercent(summary.positiveRatePercent)}</span>
        <span>平均净收益 {formatSignedPercent(summary.averageNetReturnPercent)}</span>
        <span>中位净收益 {formatSignedPercent(summary.medianNetReturnPercent)}</span>
        <span>平均 MFE {formatSignedPercent(summary.averageMfePercent)}</span>
        <span>平均 MAE {formatSignedPercent(summary.averageMaePercent)}</span>
      </div>
    </div>
  )
}

function ValidationShell({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="border border-line-soft bg-line-soft/25 px-3 py-3">
      <p className="text-xs font-semibold text-ink-700">{title}</p>
      <p className="mt-1 text-xs leading-relaxed text-ink-500">{detail}</p>
    </div>
  )
}

function SignalMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white px-3 py-2.5">
      <p className="text-[11px] text-ink-400">{label}</p>
      <p className="mt-1 tabular text-sm font-semibold text-ink-900">{value}</p>
    </div>
  )
}

function activeContributions(candidate: ShortTermCandidate): ContributionItem[] {
  const score = candidate.score
  return [
    contribution('资金流', score.fundFlowAdjustment),
    contribution('相对强度', score.relativeStrengthContribution),
    contribution('行业地位', score.industryLeadershipContribution),
    contribution('热门方向', score.marketHeatContribution),
    contribution('波动质量', score.volatilityContribution),
    contribution('横截面小计', score.crossSectionAdjustment, true),
    contribution('可见调整合计', score.visibleRankingAdjustment, true)
  ].filter((item): item is ContributionItem => item !== null)
}

export function hasClosedShortTermScoreSnapshot(candidate: ShortTermCandidate) {
  const score = candidate.score
  return Number.isFinite(score.technicalRankingScore)
    && Number.isFinite(score.rankingScore)
    && Number.isFinite(score.visibleRankingAdjustment)
}

function contribution(label: string, value: number | null | undefined, subtotal = false): ContributionItem | null {
  if (value === null || value === undefined || Math.abs(value) < 0.005) return null
  return { label, value, subtotal }
}

function signedScore(value: number) {
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(2)}`
}
