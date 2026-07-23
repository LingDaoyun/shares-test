import { useEffect, useMemo, useState } from 'react'
import { BrainCircuit, ShieldCheck, TriangleAlert } from 'lucide-react'
import { fetchV2StrategyBundle } from '../../api/client'
import { formatDateTime, formatNumber, formatPercent, formatRatioPercent } from '../../lib/format'
import type { V2SignalResponse, V2StrategyBundleParams, V2StrategyBundleResponse } from '../../types'
import { ScoreBadge, Tag } from '../ui/Badge'
import { Spinner } from '../ui/Loader'

type PanelFocus = 'long' | 'short' | 'daily'
type Tone = 'brand' | 'success' | 'warning' | 'danger' | 'sky' | 'neutral'

interface V2StrategyBundlePanelProps {
  symbol: string
  companyName?: string
  focus?: PanelFocus
  factorContext?: Omit<V2StrategyBundleParams, 'symbol' | 'companyName'>
}

const STRATEGY_LABELS: Record<string, string> = {
  VALUE_REVERSION: '低估回归',
  QUALITY_COMPOUNDER: '长期质量',
  CYCLE_REVERSAL: '周期反转',
  SHORT_RIGHT_SIDE: '短线右侧'
}

const ACTION_LABELS: Record<string, string> = {
  ADD: '加仓',
  LIGHT_TRIAL: '试仓',
  HOLD: '持有',
  NEXT_WATCH: '观察',
  WAIT_PULLBACK: '等回踩',
  WAIT: '等待',
  REDUCE: '减仓',
  EXIT: '退出',
  DATA_BLOCKED: '数据阻断',
  RISK_BLOCKED: '风险阻断'
}

export function V2StrategyBundlePanel({
  symbol,
  companyName,
  focus = 'daily',
  factorContext = {}
}: V2StrategyBundlePanelProps) {
  const [bundle, setBundle] = useState<V2StrategyBundleResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const factorKey = useMemo(() => JSON.stringify(factorContext), [factorContext])

  useEffect(() => {
    if (!symbol) {
      setBundle(null)
      return
    }
    let alive = true
    setLoading(true)
    setError('')
    const parsedFactorContext = JSON.parse(factorKey) as Omit<V2StrategyBundleParams, 'symbol' | 'companyName'>
    fetchV2StrategyBundle({ symbol, companyName, ...parsedFactorContext })
      .then((data) => {
        if (alive) setBundle(data)
      })
      .catch((e) => {
        if (alive) setError(e?.message ?? 'V2 策略内核加载失败')
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [symbol, companyName, factorKey])

  const primarySignals = useMemo(() => {
    if (!bundle) return []
    if (focus === 'short') return [bundle.shortRightSideSignal]
    if (focus === 'long') return bundle.longTermSignals
    return [...bundle.longTermSignals, bundle.shortRightSideSignal]
  }, [bundle, focus])

  const showLong = focus !== 'short'
  const showShort = focus !== 'long'

  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">
            <span className="inline-flex items-center gap-1">
              <BrainCircuit className="h-3.5 w-3.5" />
              V2 策略内核
            </span>
          </Tag>
          <Tag tone={bundleTone(primarySignals)}>规则 + 验证 + Agent</Tag>
        </div>
        <span className="text-xs text-ink-400">
          {bundle ? formatDateTime(bundle.generatedAt) : loading ? '同步中' : '待同步'}
        </span>
      </div>

      {loading && !bundle ? (
        <div className="mt-3 flex items-center gap-2 text-xs text-ink-500">
          <Spinner className="text-brand-500" />
          <span>同步策略束</span>
        </div>
      ) : null}

      {error ? (
        <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-700">
          {error}
        </div>
      ) : null}

      {bundle ? (
        <div className="mt-3 flex flex-col gap-3">
          {showLong ? <LongTermSignals signals={bundle.longTermSignals} /> : null}
          {showShort ? <ShortSignal signal={bundle.shortRightSideSignal} /> : null}
          <AgentReview bundle={bundle} />
        </div>
      ) : null}
    </div>
  )
}

function LongTermSignals({ signals }: { signals: V2SignalResponse[] }) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold text-ink-900">长期价投三策略</span>
        <Tag tone="sky">PE/PB 只作语境</Tag>
      </div>
      {signals.map((signal) => (
        <SignalRow key={signal.ledgerId} signal={signal} />
      ))}
    </div>
  )
}

function ShortSignal({ signal }: { signal: V2SignalResponse }) {
  return (
    <div className="rounded-lg border border-line-soft bg-white px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">短线右侧闸门</Tag>
          <Tag tone={actionTone(signal.action)}>{actionLabel(signal.action)}</Tag>
          <Tag tone={validationTone(signal.context.validationStatus)}>{validationLabel(signal.context.validationStatus)}</Tag>
        </div>
        <ScoreBadge value={signal.rankScore} />
      </div>
      <div className="mt-2 grid grid-cols-2 gap-2">
        <Metric label="仓位上限" value={formatRatioPercent(signal.positionLimit)} />
        <Metric label="样本胜率" value={formatPercent(signal.historicalHitRate)} />
        <Metric label="数据置信" value={formatNumber(signal.dataConfidence)} />
        <Metric label="盈亏结构" value={formatNumber(signal.riskReward)} />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-600">{signal.entryCondition}</p>
      <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed text-ink-500">
        {signal.evidenceSummary.slice(0, 3).map((item, index) => <li key={`${signal.ledgerId}-short-${index}`}>{item}</li>)}
      </ul>
    </div>
  )
}

function SignalRow({ signal }: { signal: V2SignalResponse }) {
  return (
    <div className="rounded-lg border border-line-soft bg-white px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">{strategyLabel(signal.strategyCode)}</Tag>
          <Tag tone={actionTone(signal.action)}>{actionLabel(signal.action)}</Tag>
        </div>
        <ScoreBadge value={signal.rankScore} />
      </div>
      <div className="mt-2 grid grid-cols-2 gap-2">
        <Metric label="仓位上限" value={formatRatioPercent(signal.positionLimit)} />
        <Metric label="数据置信" value={formatNumber(signal.dataConfidence)} />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-600">{signal.entryCondition}</p>
      <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed text-ink-500">
        {signal.evidenceSummary.slice(0, 2).map((item, index) => <li key={`${signal.ledgerId}-${index}`}>{item}</li>)}
      </ul>
    </div>
  )
}

function AgentReview({ bundle }: { bundle: V2StrategyBundleResponse }) {
  const review = bundle.agentEvidenceReview
  return (
    <div className="rounded-lg border border-line-soft bg-white px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">
            <span className="inline-flex items-center gap-1">
              <ShieldCheck className="h-3.5 w-3.5" />
              Agent 证据复核
            </span>
          </Tag>
          <Tag tone={review.hasConflict ? 'warning' : 'success'}>支持 {review.supportCount}</Tag>
          <Tag tone={review.opposeCount > 0 ? 'danger' : 'neutral'}>反对 {review.opposeCount}</Tag>
          <Tag tone={review.abstainCount > 0 ? 'warning' : 'neutral'}>弃权 {review.abstainCount}</Tag>
        </div>
        <span className="text-xs text-ink-400">来源重合 {review.sourceOverlapCount}</span>
      </div>
      {review.warnings.length ? (
        <div className="mt-2 flex flex-col gap-1">
          {review.warnings.map((warning) => (
            <div key={warning} className="flex items-start gap-2 rounded-md bg-amber-50 px-2 py-1.5 text-xs leading-relaxed text-amber-700">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>{warning}</span>
            </div>
          ))}
        </div>
      ) : null}
      <div className="mt-2 flex flex-col gap-1.5">
        {review.findings.map((finding) => (
          <div key={`${finding.agentName}-${finding.role}`} className="border-l-2 border-brand-200 pl-2 text-xs leading-relaxed">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-ink-800">{finding.agentName}</span>
              <Tag tone={voteTone(finding.vote)}>{voteLabel(finding.vote)}</Tag>
              <span className="text-ink-400">{finding.role}</span>
            </div>
            <p className="mt-1 text-ink-500">{finding.claim}</p>
          </div>
        ))}
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border border-line-soft bg-line-soft/40 px-2 py-1.5">
      <div className="text-[11px] text-ink-400">{label}</div>
      <div className="mt-0.5 tabular text-xs font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function bundleTone(signals: V2SignalResponse[]): Tone {
  if (signals.some((signal) => signal.action === 'ADD')) return 'success'
  if (signals.some((signal) => signal.action === 'LIGHT_TRIAL')) return 'brand'
  if (signals.some((signal) => signal.action === 'RISK_BLOCKED' || signal.action === 'DATA_BLOCKED')) return 'danger'
  return 'neutral'
}

function actionTone(action: string): Tone {
  if (action === 'ADD') return 'success'
  if (action === 'LIGHT_TRIAL' || action === 'HOLD') return 'brand'
  if (action === 'NEXT_WATCH') return 'sky'
  if (action === 'WAIT_PULLBACK' || action === 'REDUCE') return 'warning'
  if (action === 'EXIT' || action === 'RISK_BLOCKED' || action === 'DATA_BLOCKED') return 'danger'
  return 'neutral'
}

function validationTone(status: string | undefined): Tone {
  if (status === 'PASSED_OOS') return 'success'
  if (status === 'INSUFFICIENT_OOS') return 'warning'
  if (status === 'VALIDATION_MISSING') return 'danger'
  return 'neutral'
}

function voteTone(vote: string): Tone {
  if (vote === 'SUPPORT') return 'success'
  if (vote === 'OPPOSE') return 'danger'
  return 'warning'
}

function strategyLabel(strategyCode: string) {
  return STRATEGY_LABELS[strategyCode] ?? strategyCode
}

function actionLabel(action: string) {
  return ACTION_LABELS[action] ?? action
}

function validationLabel(status: string | undefined) {
  if (status === 'PASSED_OOS') return '样本外通过'
  if (status === 'INSUFFICIENT_OOS') return '验证不足'
  if (status === 'VALIDATION_MISSING') return '缺验证'
  return '未验证'
}

function voteLabel(vote: string) {
  if (vote === 'SUPPORT') return '支持'
  if (vote === 'OPPOSE') return '反对'
  return '弃权'
}
