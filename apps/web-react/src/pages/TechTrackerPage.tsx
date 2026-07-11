import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { RefreshCw, SlidersHorizontal } from 'lucide-react'
import { fetchTechTrackingReport, type TechTrackingParams } from '../api/client'
import { Card } from '../components/ui/Card'
import { SectionBanner } from '../components/ui/SectionBanner'
import { Button } from '../components/ui/Button'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { WatchButton } from '../components/watchlist/WatchButton'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatSignedPercent } from '../lib/format'
import type { TechTrackedStock, TechTrackingReport } from '../types'

const DEFAULT_PARAMS: Required<TechTrackingParams> = {
  limit: 10,
  coreMaxPe: 80,
  coreMaxPb: 20,
  hardMaxPe: 120,
  hardMaxPb: 40
}

const actionTone: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky'> = {
  WAIT_PULLBACK: 'success',
  SMALL_TREND: 'brand',
  WATCH_CONFIRM: 'warning',
  THEME_ONLY: 'warning',
  WATCH_DATA: 'neutral',
  AVOID_CHASE: 'danger'
}

export function TechTrackerPage() {
  const [params, setParams] = useState<Required<TechTrackingParams>>(DEFAULT_PARAMS)
  const [draft, setDraft] = useState<Required<TechTrackingParams>>(DEFAULT_PARAMS)
  const [report, setReport] = useState<TechTrackingReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    fetchTechTrackingReport(params)
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
    const groups = new Map<string, TechTrackedStock[]>()
    for (const candidate of report?.candidates ?? []) {
      const key = candidate.themeName
      groups.set(key, [...(groups.get(key) ?? []), candidate])
    }
    return Array.from(groups.entries())
  }, [report])

  const applyDraft = () => setParams({ ...draft })

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="HOT SECTOR TRACKER"
        title="热门追踪池"
        description="从全 A 股动态识别热门板块，再结合业绩兑现、估值容错和交易纪律建立追踪队列。"
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
            追涨纪律阈值
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <NumberField label="候选数量" value={draft.limit} min={4} max={21} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="核心 PE 上限" value={draft.coreMaxPe} min={20} max={200} onChange={(value) => setDraft({ ...draft, coreMaxPe: value })} />
          <NumberField label="核心 PB 上限" value={draft.coreMaxPb} min={2} max={80} onChange={(value) => setDraft({ ...draft, coreMaxPb: value })} />
          <NumberField label="硬 PE 上限" value={draft.hardMaxPe} min={40} max={300} onChange={(value) => setDraft({ ...draft, hardMaxPe: value })} />
          <NumberField label="硬 PB 上限" value={draft.hardMaxPb} min={5} max={120} onChange={(value) => setDraft({ ...draft, hardMaxPb: value })} />
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            核心阈值以内才允许进入“回踩重点跟踪”；超过硬阈值的标的只保留主题观察。
          </p>
          <Button variant="secondary" onClick={applyDraft}>应用阈值</Button>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? <Card><Loader text="热门追踪池计算中" /></Card> : null}

      {report ? (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card title="方法">
              <div className="flex flex-col gap-2 text-sm leading-relaxed text-ink-600">
                {report.methodology.map((item) => <p key={item}>{item}</p>)}
              </div>
            </Card>
            <Card title="政策证据">
              <div className="flex flex-col gap-2">
                {report.policySignals.map((item) => (
                  <a
                    key={item.title}
                    href={item.url ?? undefined}
                    target="_blank"
                    rel="noreferrer"
                    className="rounded-lg border border-line-soft px-3 py-2 transition hover:border-brand-300 hover:bg-brand-50"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-ink-900">{item.title}</span>
                      <Tag tone="sky">{item.weight}</Tag>
                    </div>
                    <p className="mt-1 text-xs leading-relaxed text-ink-500">{item.summary}</p>
                  </a>
                ))}
              </div>
            </Card>
            <Card title="快照">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="候选池" value={`${report.candidateCount}/${report.universeCount}`} />
                <Metric label="更新时间" value={formatDateTime(report.generatedAt)} />
                <Metric label="回踩阈值" value={`${formatNumber(report.ruleSet.pullbackWatchPercent)}%`} />
                <Metric label="止损阈值" value={`${formatNumber(report.ruleSet.stopLossPercent)}%`} />
              </div>
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
            </Card>
          </div>

          <div className="flex flex-col gap-4">
            {grouped.map(([theme, stocks]) => (
              <section key={theme} className="flex flex-col gap-3">
                <div className="flex items-end justify-between gap-3">
                  <div>
                    <div className="eyebrow">THEME</div>
                    <h3 className="text-base font-semibold text-ink-900">{theme}</h3>
                  </div>
                  <Tag tone="neutral">{stocks.length} 只</Tag>
                </div>
                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                  {stocks.map((stock) => <TechStockCard key={stock.symbol} stock={stock} />)}
                </div>
              </section>
            ))}
          </div>
        </>
      ) : null}
    </div>
  )
}

function TechStockCard({ stock }: { stock: TechTrackedStock }) {
  return (
    <Card className="transition hover:border-brand-300 hover:shadow-soft">
      <div className="flex flex-col gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow">#{stock.rank} · {stock.themeName}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-lg font-semibold text-ink-900">{stock.name}</h3>
              <span className="font-mono text-xs text-ink-400">{stock.symbol}</span>
            </div>
            <p className="mt-1 text-sm leading-relaxed text-ink-500">{stock.reason}</p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <WatchButton symbol={stock.symbol} />
            <ScoreBadge value={stock.score.finalScore} />
            <Tag tone={actionTone[stock.action] ?? 'neutral'}>{stock.actionLabel}</Tag>
            <Tag tone={adviceTone(stock.todayAdvice.action)}>今日：{stock.todayAdvice.actionLabel}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <Metric label="价格" value={formatNumber(stock.latestPrice)} />
          <Metric label="涨跌幅" value={<span className={changeClass(stock.changePercent)}>{formatSignedPercent(stock.changePercent)}</span>} />
          <Metric label="PE" value={formatNumber(stock.peTtm)} />
          <Metric label="PB" value={formatNumber(stock.pbRatio)} />
          <Metric label="成交额" value={formatAmount(stock.amount)} />
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-4">
          <ScoreMetric label="政策" value={stock.score.policyScore} />
          <ScoreMetric label="业绩" value={stock.score.earningsScore} />
          <ScoreMetric label="估值" value={stock.score.valuationScore} />
          <ScoreMetric label="纪律" value={stock.score.tradingDisciplineScore} />
        </div>

        <TodayAdvicePanel advice={stock.todayAdvice} />

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <ListBlock title="支撑逻辑" items={stock.strengths} tone="success" />
          <ListBlock title="风险约束" items={stock.risks} tone="warning" />
          <ListBlock title="进入规则" items={stock.entryRules} tone="brand" />
          <ListBlock title="退出规则" items={stock.exitRules} tone="danger" />
        </div>
      </div>
    </Card>
  )
}

function TodayAdvicePanel({ advice }: { advice: TechTrackedStock['todayAdvice'] }) {
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
