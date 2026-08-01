import { useEffect, useMemo, useState } from 'react'
import { BrainCircuit, RefreshCw, SlidersHorizontal } from 'lucide-react'
import { fetchDailySignalReport, type DailySignalParams } from '../api/client'
import { Card } from '../components/ui/Card'
import { SectionBanner } from '../components/ui/SectionBanner'
import { Button } from '../components/ui/Button'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { TradeReviewButton } from '../components/tradefeedback/TradeReviewButton'
import { WatchButton } from '../components/watchlist/WatchButton'
import { V2StrategyBundlePanel } from '../components/recommendation/V2StrategyBundlePanel'
import { changeClass, extractErrorMessage, formatDateTime, formatNumber } from '../lib/format'
import type { DailyDecisionSignal, DailySignalReport, StrategyPlaybook, V2StrategyBundleParams } from '../types'

const DEFAULT_PARAMS: Required<DailySignalParams> = {
  limit: 18,
  techLimit: 10,
  mispricingLimit: 10,
  hotHeat: 82
}

export function DailySignalsPage() {
  const [params, setParams] = useState<Required<DailySignalParams>>(DEFAULT_PARAMS)
  const [draft, setDraft] = useState<Required<DailySignalParams>>(DEFAULT_PARAMS)
  const [report, setReport] = useState<DailySignalReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    fetchDailySignalReport(params)
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
    const groups = new Map<string, DailyDecisionSignal[]>()
    for (const signal of report?.signals ?? []) {
      groups.set(signal.action, [...(groups.get(signal.action) ?? []), signal])
    }
    return Array.from(groups.entries())
  }, [report])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="DAILY SIGNALS"
        title="每日决策信号"
        description="融合 daily_stock_analysis 的决策信号、策略包和每日市场上下文思想，把热门追踪池与错杀估值池沉淀成当天可复核的操作清单。"
        extra={
          <Button
            variant="primary"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={loading}
            onClick={() => setParams({ ...draft })}
          >
            重新生成
          </Button>
        }
      />

      <Card
        title={
          <span className="inline-flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-brand-500" />
            信号生成范围
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <NumberField label="总信号数" value={draft.limit} min={4} max={50} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="热门候选数" value={draft.techLimit} min={4} max={30} onChange={(value) => setDraft({ ...draft, techLimit: value })} />
          <NumberField label="错杀候选数" value={draft.mispricingLimit} min={4} max={30} onChange={(value) => setDraft({ ...draft, mispricingLimit: value })} />
          <NumberField label="热门过热分" value={draft.hotHeat} min={0} max={100} onChange={(value) => setDraft({ ...draft, hotHeat: value })} />
        </div>
        <div className="mt-3 flex items-center justify-between gap-3 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            信号不是下单指令；它把今日建议、策略标签、风险条件和证据来源统一成可追溯结构。
          </p>
          <Button variant="secondary" onClick={() => setParams({ ...draft })}>应用范围</Button>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? <Card><Loader text="每日信号生成中" /></Card> : null}

      {report ? (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card title="市场上下文">
              <div className="flex flex-col gap-3">
                <div className="flex items-center justify-between gap-3">
                  <Tag tone="sky">{report.marketContext.region.toUpperCase()}</Tag>
                  <span className="text-xs text-ink-400">{report.marketContext.tradeDate}</span>
                </div>
                <p className="text-sm leading-relaxed text-ink-700">{report.marketContext.summary}</p>
                <div className="flex flex-wrap gap-2">
                  {report.marketContext.riskTags.map((tag) => <Tag key={tag} tone="warning">{tag}</Tag>)}
                </div>
                <div className="rounded-lg border border-line-soft px-3 py-2 text-sm text-ink-700">
                  {report.marketContext.positionCap}
                </div>
              </div>
            </Card>

            <Card title="动作分布">
              <div className="grid grid-cols-2 gap-3">
                {Object.entries(report.actionCounts).map(([action, count]) => (
                  <div key={action} className="rounded-lg border border-line-soft px-3 py-2">
                    <Tag tone={actionTone(action)}>{actionLabel(action)}</Tag>
                    <div className="mt-2 tabular text-xl font-semibold text-ink-900">{count}</div>
                  </div>
                ))}
              </div>
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">
                来源：{report.sourceProject}@{report.sourceCommit}，生成时间 {formatDateTime(report.generatedAt)}
              </p>
            </Card>

            <Card
              title={
                <span className="inline-flex items-center gap-2">
                  <BrainCircuit className="h-4 w-4 text-brand-500" />
                  策略包
                </span>
              }
            >
              <div className="flex flex-col gap-2">
                {report.strategyPlaybooks.map((strategy) => (
                  <StrategyMini key={strategy.name} strategy={strategy} />
                ))}
              </div>
            </Card>
          </div>

          {report.strategyPlaybooks.length ? (
            <Card title="策略规则来源">
              <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
                {report.strategyPlaybooks.map((strategy) => <StrategyCard key={strategy.name} strategy={strategy} />)}
              </div>
            </Card>
          ) : null}

          <div className="flex flex-col gap-4">
            {grouped.map(([action, signals]) => (
              <section key={action} className="flex flex-col gap-3">
                <div className="flex items-end justify-between gap-3">
                  <div>
                    <div className="eyebrow">ACTION</div>
                    <h3 className="text-base font-semibold text-ink-900">{actionLabel(action)}</h3>
                  </div>
                  <Tag tone={actionTone(action)}>{signals.length} 条</Tag>
                </div>
                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                  {signals.map((signal) => (
                    <SignalCard
                      key={`${signal.sourceType}-${signal.symbol}`}
                      signal={signal}
                      tradeCaptureToken={report.tradeCaptureTokens?.[`${signal.sourceType}|${signal.symbol}`] ?? null}
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

function SignalCard({
  signal,
  tradeCaptureToken
}: {
  signal: DailyDecisionSignal
  tradeCaptureToken: string | null
}) {
  return (
    <Card className="transition hover:border-brand-300 hover:shadow-soft">
      <div className="flex flex-col gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow">#{signal.rank} · {signal.sourceLabel}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-lg font-semibold text-ink-900">{signal.name}</h3>
              <span className="font-mono text-xs text-ink-400">{signal.symbol}</span>
              <Tag tone="neutral">{signal.horizon}</Tag>
            </div>
            <p className="mt-1 text-sm leading-relaxed text-ink-500">{signal.reason}</p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <WatchButton symbol={signal.symbol} />
            <TradeReviewButton
              symbol={signal.symbol}
              sourceModule="DAILY_SIGNAL"
              ruleVersion="daily-signal-v1"
              recommendedAt={signal.marketTimestamp}
              attestationToken={tradeCaptureToken}
            />
            <ScoreBadge value={signal.score ?? signal.confidence} />
            <Tag tone={actionTone(signal.action)}>{signal.actionLabel}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
          <Metric label="置信度" value={formatNumber(signal.confidence)} />
          <Metric label="市场" value={signal.market.toUpperCase()} />
          <Metric label="阶段" value={signal.marketPhase} />
          <Metric label="今日动作" value={signal.todayAdvice.actionLabel} />
        </div>

        <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Tag tone={actionTone(signal.action)}>今日建议</Tag>
            <span className="tabular text-xs font-semibold text-ink-500">置信度 {signal.todayAdvice.confidence}</span>
          </div>
          <p className="mt-2 text-sm leading-relaxed text-ink-700">{signal.todayAdvice.summary}</p>
        </div>

        <V2StrategyBundlePanel
          symbol={signal.symbol}
          companyName={signal.name}
          focus="daily"
          factorContext={dailyFactorContext(signal)}
        />

        <div className="flex flex-wrap gap-2">
          {signal.strategyTags.map((tag) => <Tag key={tag} tone="sky">{strategyLabel(tag)}</Tag>)}
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <ListBlock title="催化/支撑" items={splitSummary(signal.catalystSummary)} tone="success" />
          <ListBlock title="风险摘要" items={splitSummary(signal.riskSummary)} tone="warning" />
          <ListBlock title="观察条件" items={signal.watchConditions} tone="brand" />
          <ListBlock title="证据来源" items={signal.evidence.map((item) => item.title)} tone="warning" />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <Metric label="最新建议" value={signal.todayAdvice.actionLabel} />
          <Metric label="建议变化" value={<span className={changeClass(signal.action === 'add' ? -1 : signal.action === 'reduce' ? 1 : 0)}>{signal.action === 'add' ? '偏进攻' : signal.action === 'reduce' || signal.action === 'sell' ? '偏防守' : '中性'}</span>} />
        </div>
      </div>
    </Card>
  )
}

function StrategyMini({ strategy }: { strategy: StrategyPlaybook }) {
  return (
    <div className="rounded-lg border border-line-soft px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold text-ink-900">{strategy.displayName}</span>
        <Tag tone="neutral">{strategy.category}</Tag>
      </div>
      <p className="mt-1 text-xs leading-relaxed text-ink-500">{strategy.description}</p>
    </div>
  )
}

function StrategyCard({ strategy }: { strategy: StrategyPlaybook }) {
  return (
    <div className="rounded-lg border border-line-soft p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-semibold text-ink-900">{strategy.displayName}</div>
          <div className="mt-1 font-mono text-xs text-ink-400">{strategy.name}</div>
        </div>
        <Tag tone="sky">规则 {strategy.coreRules.join('/')}</Tag>
      </div>
      <p className="mt-2 text-sm leading-relaxed text-ink-600">{strategy.scoringImpact}</p>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <ListBlock title="触发条件" items={strategy.triggerRules} tone="brand" />
        <ListBlock title="退出条件" items={strategy.exitRules} tone="danger" />
      </div>
    </div>
  )
}

function dailyFactorContext(signal: DailyDecisionSignal): Omit<V2StrategyBundleParams, 'symbol' | 'companyName'> {
  const score = signal.score ?? signal.confidence
  const buyLike = signal.action === 'add' || signal.action === 'trial'
  const defensive = signal.action === 'reduce' || signal.action === 'sell'
  return {
    industry: signal.marketPhase,
    valuationDiscountScore: score,
    qualityScore: signal.confidence,
    moatScore: signal.confidence,
    profitabilityScore: signal.confidence,
    cashFlowScore: signal.confidence,
    cyclePositionScore: signal.horizon.includes('周期') ? score : signal.confidence,
    cycleRecoveryScore: buyLike ? score : Math.max(35, signal.confidence - 15),
    industryLeaderScore: signal.confidence,
    policyCatalystScore: buyLike ? Math.max(score, 72) : score,
    liquidityScore: 72,
    hotDirection: signal.sourceLabel,
    marketHotScore: buyLike ? Math.max(score, 72) : score,
    rightSideStructureScore: buyLike ? score : Math.max(35, score - 20),
    supplyAbsorptionScore: buyLike ? signal.confidence : Math.max(30, signal.confidence - 20),
    volumeBreakoutScore: score,
    shrinkRiseScore: signal.confidence,
    fundamentalFloorScore: signal.confidence,
    crowdingRiskScore: defensive ? 78 : buyLike ? 35 : 55
  }
}

function NumberField({
  label,
  value,
  min,
  max,
  onChange
}: {
  label: string
  value: number
  min: number
  max: number
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
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
      />
    </label>
  )
}

function Metric({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/40 px-3 py-2">
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 tabular text-sm font-semibold text-ink-900">{value}</div>
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

function actionTone(action: string): 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky' {
  if (action === 'add') return 'success'
  if (action === 'trial') return 'brand'
  if (action === 'hold') return 'brand'
  if (action === 'reduce') return 'warning'
  if (action === 'sell') return 'danger'
  return 'neutral'
}

function actionLabel(action: string) {
  if (action === 'add') return '加仓'
  if (action === 'trial') return '试仓'
  if (action === 'hold') return '持有'
  if (action === 'reduce') return '分批卖出'
  if (action === 'sell') return '全仓卖出'
  return '观察'
}

function strategyLabel(name: string) {
  const labels: Record<string, string> = {
    shrink_pullback: '缩量回踩',
    expectation_repricing: '预期重估',
    growth_quality: '成长质量',
    event_driven: '事件驱动'
  }
  return labels[name] ?? name
}

function splitSummary(summary: string) {
  return summary ? summary.split('；').filter(Boolean) : ['暂无']
}
