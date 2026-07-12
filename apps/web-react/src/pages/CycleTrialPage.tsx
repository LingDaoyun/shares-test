import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Gauge, RefreshCw, ShieldCheck, SlidersHorizontal } from 'lucide-react'
import { fetchCycleTrialReport, type CycleTrialParams } from '../api/client'
import { Card } from '../components/ui/Card'
import { SectionBanner } from '../components/ui/SectionBanner'
import { Button } from '../components/ui/Button'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { TradeReviewButton } from '../components/tradefeedback/TradeReviewButton'
import { WatchButton } from '../components/watchlist/WatchButton'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatSignedPercent } from '../lib/format'
import type { CycleTrialCandidate, CycleTrialReport } from '../types'

const DEFAULT_PARAMS: Required<CycleTrialParams> = {
  limit: 10,
  leftTrialScore: 65,
  rightAddScore: 78,
  maxChaseRise: 6,
  minVolumeRatio: 1.5
}

const actionTone: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky'> = {
  LEFT_TRIAL: 'success',
  RIGHT_ADD: 'brand',
  RIGHT_START_WAIT_PULLBACK: 'warning',
  WATCH_CONFIRM: 'neutral',
  DATA_REVIEW: 'neutral',
  AVOID: 'danger'
}

export function CycleTrialPage() {
  const [params, setParams] = useState<Required<CycleTrialParams>>(DEFAULT_PARAMS)
  const [draft, setDraft] = useState<Required<CycleTrialParams>>(DEFAULT_PARAMS)
  const [report, setReport] = useState<CycleTrialReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    fetchCycleTrialReport(params)
      .then((data) => {
        if (alive) setReport(data)
      })
      .catch((e) => {
        if (alive) setError(extractErrorMessage(e))
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [params])

  const grouped = useMemo(() => {
    const groups = new Map<string, CycleTrialCandidate[]>()
    for (const candidate of report?.candidates ?? []) {
      groups.set(candidate.assetGroup, [...(groups.get(candidate.assetGroup) ?? []), candidate])
    }
    return Array.from(groups.entries())
  }, [report])

  const actionCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const candidate of report?.candidates ?? []) {
      counts.set(candidate.actionLabel, (counts.get(candidate.actionLabel) ?? 0) + 1)
    }
    return Array.from(counts.entries())
  }, [report])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="CYCLE TRIAL"
        title="周期试仓池"
        description="把周期底部赔率、左侧试仓、右侧加仓和急拉回避拆成独立信号。"
        extra={
          <Button
            variant="primary"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={loading}
            onClick={() => setParams({ ...draft })}
          >
            重新计算
          </Button>
        }
      />

      <Card
        title={
          <span className="inline-flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-brand-500" />
            周期交易阈值
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <NumberField label="候选数量" value={draft.limit} min={4} max={10} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="左侧试仓分" value={draft.leftTrialScore} min={45} max={90} onChange={(value) => setDraft({ ...draft, leftTrialScore: value })} />
          <NumberField label="右侧加仓分" value={draft.rightAddScore} min={60} max={95} onChange={(value) => setDraft({ ...draft, rightAddScore: value })} />
          <NumberField label="追涨上限" value={draft.maxChaseRise} min={2} max={10} step={0.1} onChange={(value) => setDraft({ ...draft, maxChaseRise: value })} />
          <NumberField label="放量倍数" value={draft.minVolumeRatio} min={0.8} max={3} step={0.1} onChange={(value) => setDraft({ ...draft, minVolumeRatio: value })} />
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            左侧只允许试仓；右侧只有放量站上关键位且未触发追涨上限，才允许加第二笔。
          </p>
          <Button variant="secondary" onClick={() => setParams({ ...draft })}>应用阈值</Button>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? <Card><Loader text="周期试仓池计算中" /></Card> : null}

      {report ? (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card
              title={
                <span className="inline-flex items-center gap-2">
                  <Gauge className="h-4 w-4 text-brand-500" />
                  动作分布
                </span>
              }
            >
              <div className="flex flex-wrap gap-2">
                {actionCounts.map(([label, count]) => <Tag key={label} tone="neutral">{label} {count}</Tag>)}
              </div>
              <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
                <Metric label="候选池" value={`${report.candidateCount}/${report.universeCount}`} />
                <Metric label="更新时间" value={formatDateTime(report.generatedAt)} />
              </div>
            </Card>

            <Card title="方法">
              <div className="flex flex-col gap-2 text-sm leading-relaxed text-ink-600">
                {report.methodology.map((item) => <p key={item}>{item}</p>)}
              </div>
            </Card>

            <Card
              title={
                <span className="inline-flex items-center gap-2">
                  <ShieldCheck className="h-4 w-4 text-brand-500" />
                  风控线
                </span>
              }
            >
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="追涨上限" value={`${formatNumber(report.ruleSet.maxChaseRisePercent)}%`} />
                <Metric label="放量确认" value={`${formatNumber(report.ruleSet.minVolumeRatioForBreakout)} 倍`} />
                <Metric label="试仓止损" value={`${formatNumber(report.ruleSet.stopLossPercent)}%`} />
                <Metric label="回踩区间" value={`${formatNumber(report.ruleSet.pullbackZonePercent)}%`} />
              </div>
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
            </Card>
          </div>

          <div className="flex flex-col gap-4">
            {grouped.map(([group, candidates]) => (
              <section key={group} className="flex flex-col gap-3">
                <div className="flex items-end justify-between gap-3">
                  <div>
                    <div className="eyebrow">CYCLE GROUP</div>
                    <h3 className="text-base font-semibold text-ink-900">{group}</h3>
                  </div>
                  <Tag tone="neutral">{candidates.length} 只</Tag>
                </div>
                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                  {candidates.map((candidate) => (
                    <CycleCard
                      key={candidate.symbol}
                      candidate={candidate}
                      generatedAt={report.generatedAt}
                      tradeCaptureToken={report.tradeCaptureTokens?.[candidate.symbol] ?? null}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>
        </>
      ) : null}
    </div>
  )
}

function CycleCard({
  candidate,
  generatedAt,
  tradeCaptureToken
}: {
  candidate: CycleTrialCandidate
  generatedAt: string
  tradeCaptureToken: string | null
}) {
  return (
    <Card className="transition hover:border-brand-300 hover:bg-brand-50/30 hover:shadow-soft">
      <div className="flex flex-col gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow">#{candidate.rank} · {candidate.phaseLabel}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-lg font-semibold text-ink-900">{candidate.name}</h3>
              <span className="font-mono text-xs text-ink-400">{candidate.symbol}</span>
              <Tag tone="sky">{candidate.assetGroup}</Tag>
            </div>
            <p className="mt-1 text-sm leading-relaxed text-ink-500">{candidate.reason}</p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <WatchButton symbol={candidate.symbol} />
            <TradeReviewButton
              symbol={candidate.symbol}
              sourceModule="CYCLE_TRIAL"
              ruleVersion="cycle-trial-v2"
              recommendedAt={generatedAt}
              attestationToken={tradeCaptureToken}
            />
            <ScoreBadge value={candidate.score.finalScore} />
            <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag>
            <Tag tone={adviceTone(candidate.todayAdvice.action)}>今日：{candidate.todayAdvice.actionLabel}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <Metric label="价格" value={formatNumber(candidate.latestPrice)} />
          <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} />
          <Metric label="PE" value={formatNumber(candidate.peTtm)} />
          <Metric label="PB" value={formatNumber(candidate.pbRatio)} />
          <Metric label="成交额" value={formatAmount(candidate.amount)} />
        </div>

        {candidate.peerValuation ? <PeerValuationPanel snapshot={candidate.peerValuation} /> : null}

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <ScoreMetric label="催化" value={candidate.score.catalystScore} />
          <ScoreMetric label="低位" value={candidate.score.priceLocationScore} />
          <ScoreMetric label="反转" value={candidate.score.reversalScore} />
          <ScoreMetric label="量能/资金" value={candidate.score.volumeScore} />
          <ScoreMetric label="估值" value={candidate.score.valuationScore} />
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
          <Metric label="5/20 日量比" value={`${formatNumber(candidate.technical.volumeRatio5)} / ${formatNumber(candidate.technical.volumeRatio20)}`} />
          <Metric label="距 20 日线" value={`${formatNumber(candidate.technical.distanceToMa20Percent)}%`} />
          <Metric label="60 日位置" value={`${formatNumber(candidate.technical.rangePosition60)}%`} />
          <Metric label="前 20 日高点" value={formatNumber(candidate.technical.previousHigh20)} />
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          <Metric label="试仓区间" value={`${formatNumber(candidate.trialBuyZoneLow)} - ${formatNumber(candidate.trialBuyZoneHigh)}`} />
          <Metric label="止损价" value={formatNumber(candidate.stopPrice)} />
          <Metric label="周期驱动" value={candidate.cycleDriver} />
        </div>

        <TodayAdvicePanel advice={candidate.todayAdvice} />

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <ListBlock title="催化证据" items={candidate.catalysts} tone="success" />
          <ListBlock title="风险约束" items={candidate.risks} tone="warning" />
          <ListBlock title="进入规则" items={candidate.entryRules} tone="brand" />
          <ListBlock title="退出规则" items={candidate.exitRules} tone="danger" />
        </div>

        {candidate.evidence.length ? (
          <div className="flex flex-wrap gap-2">
            {candidate.evidence.map((item) => item.url ? (
              <a
                key={`${item.title}-${item.url}`}
                href={item.url}
                target="_blank"
                rel="noreferrer"
                className="rounded-full border border-line-soft px-2.5 py-1 text-xs font-medium text-brand-600 transition hover:border-brand-300 hover:bg-brand-50"
              >
                {item.title}
              </a>
            ) : (
              <span key={item.title} className="rounded-full border border-line-soft px-2.5 py-1 text-xs font-medium text-ink-500">
                {item.title}
              </span>
            ))}
          </div>
        ) : null}
      </div>
    </Card>
  )
}

function PeerValuationPanel({ snapshot }: { snapshot: NonNullable<CycleTrialCandidate['peerValuation']> }) {
  const hasPeers = snapshot.peers.length > 0
  const tone = snapshot.valuationAdvantage ? 'success' : hasPeers ? 'neutral' : 'warning'
  return (
    <div className={`rounded-lg border p-3 ${snapshot.valuationAdvantage ? 'border-emerald-200 bg-emerald-50/60' : 'border-line-soft bg-line-soft/30'}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone={tone}>{snapshot.valuationAdvantage ? '同业估值优势' : hasPeers ? '同业估值对比' : '同业样本不足'}</Tag>
          <span className="text-xs font-medium text-ink-500">{snapshot.industry ?? '行业待补充'}</span>
        </div>
        <div className="flex flex-wrap gap-2 text-xs">
          <span className="tabular text-ink-500">PE 折价 <b className={discountClass(snapshot.candidatePeDiscountPercent)}>{formatDiscount(snapshot.candidatePeDiscountPercent)}</b></span>
          <span className="tabular text-ink-500">PB 折价 <b className={discountClass(snapshot.candidatePbDiscountPercent)}>{formatDiscount(snapshot.candidatePbDiscountPercent)}</b></span>
        </div>
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-600">{snapshot.conclusion}</p>
      <div className="mt-3 grid grid-cols-2 gap-2 md:grid-cols-4">
        <Metric label="头部均值 PE" value={formatNumber(snapshot.averagePeTtm)} />
        <Metric label="头部均值 PB" value={formatNumber(snapshot.averagePbRatio)} />
        <Metric label="PE 折价" value={<span className={discountClass(snapshot.candidatePeDiscountPercent)}>{formatDiscount(snapshot.candidatePeDiscountPercent)}</span>} />
        <Metric label="PB 折价" value={<span className={discountClass(snapshot.candidatePbDiscountPercent)}>{formatDiscount(snapshot.candidatePbDiscountPercent)}</span>} />
      </div>
      {hasPeers ? (
        <div className="mt-3 overflow-hidden rounded-lg border border-line-soft bg-white/70">
          <div className="grid grid-cols-[1.4fr_.7fr_.7fr_.9fr] gap-2 border-b border-line-soft px-3 py-2 text-xs font-semibold text-ink-400">
            <span>对比公司</span>
            <span className="text-right">PE</span>
            <span className="text-right">PB</span>
            <span className="text-right">成交额</span>
          </div>
          {snapshot.peers.map((peer) => {
            const content = (
              <>
                <span className="min-w-0">
                  <span className="block truncate font-medium text-ink-700">{peer.name}</span>
                  <span className="font-mono text-[11px] text-ink-400">{peer.symbol}</span>
                </span>
                <span className="text-right tabular text-ink-600">{formatNumber(peer.peTtm)}</span>
                <span className="text-right tabular text-ink-600">{formatNumber(peer.pbRatio)}</span>
                <span className="text-right tabular text-ink-600">{formatAmount(peer.amount)}</span>
              </>
            )
            const className = "grid grid-cols-[1.4fr_.7fr_.7fr_.9fr] gap-2 px-3 py-2 text-xs transition hover:bg-brand-50/70"
            return peer.quoteUrl ? (
              <a key={peer.symbol} href={peer.quoteUrl} target="_blank" rel="noreferrer" className={className}>{content}</a>
            ) : (
              <div key={peer.symbol} className={className}>{content}</div>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}

function formatDiscount(value: number | null | undefined) {
  return formatSignedPercent(value)
}

function discountClass(value: number | null | undefined) {
  if (value === null || value === undefined) return 'text-ink-400'
  if (value >= 20) return 'text-emerald-600'
  if (value >= 0) return 'text-brand-600'
  return 'text-red-600'
}

function TodayAdvicePanel({ advice }: { advice: CycleTrialCandidate['todayAdvice'] }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">今日建议</Tag>
          <Tag tone={adviceTone(advice.action)}>{advice.actionLabel}</Tag>
        </div>
        <span className="tabular text-xs font-semibold text-ink-500">置信度 {advice.confidence}</span>
      </div>
      <p className="mt-2 text-sm leading-relaxed text-ink-700">{advice.summary}</p>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <ListBlock title="建议依据" items={advice.reasons} tone="brand" />
        <ListBlock title="风控条件" items={advice.riskControls} tone="warning" />
      </div>
    </div>
  )
}

function adviceTone(action: string): 'success' | 'brand' | 'warning' | 'danger' | 'neutral' {
  if (action === 'ADD') return 'success'
  if (action === 'HOLD') return 'brand'
  if (action === 'BATCH_SELL') return 'warning'
  if (action === 'SELL_ALL') return 'danger'
  return 'neutral'
}

function NumberField({
  label,
  value,
  min,
  max,
  step,
  onChange
}: {
  label: string
  value: number
  min: number
  max: number
  step?: number
  onChange: (value: number) => void
}) {
  return (
    <label>
      <span className="field-label">{label}</span>
      <input
        className="field tabular"
        type="number"
        min={min}
        max={max}
        step={step ?? 1}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
      />
    </label>
  )
}

function Metric({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/40 px-3 py-2">
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 tabular text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function ScoreMetric({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-line-soft px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-ink-400">{label}</span>
        <span className="tabular text-xs font-semibold text-brand-600">{formatNumber(value)}</span>
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-line-soft">
        <div className="h-full rounded-full bg-brand-500" style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
      </div>
    </div>
  )
}

function ListBlock({
  title,
  items,
  tone
}: {
  title: string
  items: string[]
  tone: 'brand' | 'success' | 'warning' | 'danger'
}) {
  return (
    <div className="rounded-lg border border-line-soft p-3">
      <Tag tone={tone}>{title}</Tag>
      <ul className="mt-2 flex flex-col gap-1.5 text-xs leading-relaxed text-ink-600">
        {items.slice(0, 4).map((item) => <li key={item}>{item}</li>)}
      </ul>
    </div>
  )
}
