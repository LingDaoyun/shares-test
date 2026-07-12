import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { RefreshCw, SlidersHorizontal, ThermometerSun } from 'lucide-react'
import { fetchMispricingReport, type MispricingParams } from '../api/client'
import { Card } from '../components/ui/Card'
import { SectionBanner } from '../components/ui/SectionBanner'
import { Button } from '../components/ui/Button'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Loader } from '../components/ui/Loader'
import { RecommendationEvidenceBundlePanel } from '../components/recommendation/EvidenceBundlePanel'
import { TradeReviewButton } from '../components/tradefeedback/TradeReviewButton'
import { WatchButton } from '../components/watchlist/WatchButton'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPerSharePrice, formatSignedPercent } from '../lib/format'
import type { MispricedAsset, MispricingReport } from '../types'

const DEFAULT_PARAMS: Required<MispricingParams> = {
  limit: 10,
  scanLimit: 1200,
  hotHeat: 82,
  maxPe: 18,
  maxPb: 2.5,
  minQuality: 78
}

const actionTone: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky'> = {
  ACCUMULATE_WEAKNESS: 'success',
  WAIT_WEAK_DAY: 'brand',
  WATCH_PULLBACK: 'brand',
  WAIT_HOT_OVERHEAT: 'warning',
  WAIT_CONFIRM: 'neutral',
  DATA_REVIEW: 'neutral',
  CYCLICAL_OBSERVE: 'warning',
  VALUE_TRAP_EXCLUDED: 'danger',
  VALUE_TRAP_REVIEW: 'danger'
}

export function MispricingPage() {
  const [params, setParams] = useState<Required<MispricingParams>>(DEFAULT_PARAMS)
  const [draft, setDraft] = useState<Required<MispricingParams>>(DEFAULT_PARAMS)
  const [report, setReport] = useState<MispricingReport | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    fetchMispricingReport(params)
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
    const groups = new Map<string, MispricedAsset[]>()
    for (const candidate of report?.candidates ?? []) {
      const key = candidate.assetGroup
      groups.set(key, [...(groups.get(key) ?? []), candidate])
    }
    return Array.from(groups.entries())
  }, [report])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="MISPRICED ASSETS"
        title="错杀估值池"
        description="从全 A 股行情估值池里做动态漏斗，再结合核心资产种子、热门拥挤度和价格纪律，找可能被市场风格错杀的候选。"
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
            错杀筛选阈值
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
          <NumberField label="候选数量" value={draft.limit} min={4} max={22} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="扫描数量" value={draft.scanLimit} min={50} max={5000} step={100} onChange={(value) => setDraft({ ...draft, scanLimit: value })} />
          <NumberField label="热门过热分" value={draft.hotHeat} min={0} max={100} onChange={(value) => setDraft({ ...draft, hotHeat: value })} />
          <NumberField label="PE 上限" value={draft.maxPe} min={4} max={40} onChange={(value) => setDraft({ ...draft, maxPe: value })} />
          <NumberField label="PB 上限" value={draft.maxPb} min={0.5} max={8} step={0.1} onChange={(value) => setDraft({ ...draft, maxPb: value })} />
          <NumberField label="质量分下限" value={draft.minQuality} min={40} max={95} onChange={(value) => setDraft({ ...draft, minQuality: value })} />
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            热门过热分填 0 表示自动引用科技追踪池；分数越高只提高观察优先级，仍需质量、估值和弱势日共同触发。
            扫描数量越大，覆盖越接近全市场，计算耗时也会增加。
          </p>
          <Button variant="secondary" onClick={() => setParams({ ...draft })}>应用阈值</Button>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? <Card><Loader text="错杀估值池计算中" /></Card> : null}

      {report ? (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card
              title={
                <span className="inline-flex items-center gap-2">
                  <ThermometerSun className="h-4 w-4 text-brand-500" />
                  热门拥挤度
                </span>
              }
            >
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="text-sm font-semibold text-ink-900">{report.styleHeat.hotThemeName}</div>
                  <div className="mt-1 text-xs text-ink-500">{report.styleHeat.riskLabel}</div>
                </div>
                <ScoreBadge value={report.styleHeat.heatScore} />
              </div>
              <div className="mt-3 grid grid-cols-2 gap-2">
                <Metric label="估值压力" value={formatNumber(report.styleHeat.valuationPressure)} />
                <Metric label="拥挤压力" value={formatNumber(report.styleHeat.crowdingPressure)} />
              </div>
              <div className="mt-3 flex flex-col gap-1.5 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">
                {report.styleHeat.signals.map((signal) => <span key={signal}>{signal}</span>)}
              </div>
            </Card>

            <Card title="方法">
              <div className="flex flex-col gap-2 text-sm leading-relaxed text-ink-600">
                {report.methodology.map((item) => <p key={item}>{item}</p>)}
              </div>
            </Card>

            <Card title="快照">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="候选池" value={`${report.candidateCount}/${report.universeCount}`} />
                <Metric label="更新时间" value={formatDateTime(report.generatedAt)} />
                <Metric label="扫描数量" value={report.ruleSet.scanLimit} />
                <Metric label="优先回撤" value={`${formatNumber(report.ruleSet.preferredPullbackPercent)}%`} />
                <Metric label="止损阈值" value={`${formatNumber(report.ruleSet.stopLossPercent)}%`} />
              </div>
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
            </Card>
          </div>

          <Card title="政策与资产线索">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
              {report.policySignals.map((item) => (
                <div key={item.title} className="rounded-lg border border-line-soft p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-ink-900">{item.title}</span>
                    <Tag tone="sky">{item.weight}</Tag>
                  </div>
                  <p className="mt-1 text-xs leading-relaxed text-ink-500">{item.summary}</p>
                </div>
              ))}
            </div>
          </Card>

          <div className="flex flex-col gap-4">
            {grouped.map(([group, assets]) => (
              <section key={group} className="flex flex-col gap-3">
                <div className="flex items-end justify-between gap-3">
                  <div>
                    <div className="eyebrow">ASSET GROUP</div>
                    <h3 className="text-base font-semibold text-ink-900">{group}</h3>
                  </div>
                  <Tag tone="neutral">{assets.length} 只</Tag>
                </div>
                <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                  {assets.map((asset) => (
                    <MispricedAssetCard
                      key={asset.symbol}
                      asset={asset}
                      generatedAt={report.generatedAt}
                      tradeCaptureToken={report.tradeCaptureTokens?.[asset.symbol] ?? null}
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

function MispricedAssetCard({
  asset,
  generatedAt,
  tradeCaptureToken
}: {
  asset: MispricedAsset
  generatedAt: string
  tradeCaptureToken: string | null
}) {
  return (
    <Card className="transition hover:border-brand-300 hover:shadow-soft">
      <div className="flex flex-col gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow">#{asset.rank} · {asset.assetGroup}</div>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <h3 className="text-lg font-semibold text-ink-900">{asset.name}</h3>
              <span className="font-mono text-xs text-ink-400">{asset.symbol}</span>
            </div>
            <p className="mt-1 text-sm leading-relaxed text-ink-500">{asset.reason}</p>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <WatchButton symbol={asset.symbol} />
            <TradeReviewButton
              symbol={asset.symbol}
              sourceModule="MISPRICING"
              ruleVersion="mispricing-v2"
              recommendedAt={generatedAt}
              attestationToken={tradeCaptureToken}
            />
            <ScoreBadge value={asset.score.finalScore} />
            <Tag tone={actionTone[asset.action] ?? 'neutral'}>{asset.actionLabel}</Tag>
            <Tag tone={adviceTone(asset.todayAdvice.action)}>今日：{asset.todayAdvice.actionLabel}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <Metric label="每股价格" value={formatPerSharePrice(asset.latestPrice)} />
          <Metric label="涨跌幅" value={<span className={changeClass(asset.changePercent)}>{formatSignedPercent(asset.changePercent)}</span>} />
          <Metric label="PE" value={formatNumber(asset.peTtm)} />
          <Metric label="PB" value={formatNumber(asset.pbRatio)} />
          <Metric label="成交额" value={formatAmount(asset.amount)} />
          <Metric label="证据完整度" value={`${asset.evidenceCompleteness.score}`} />
        </div>

        <div className="grid grid-cols-2 gap-2 md:grid-cols-5">
          <ScoreMetric label="热门" value={asset.score.hotOverheatScore} />
          <ScoreMetric label="质量" value={asset.score.qualityScore} />
          <ScoreMetric label="估值" value={asset.score.valuationDiscountScore} />
          <ScoreMetric label="现金流" value={asset.score.cashflowDefenseScore} />
          <ScoreMetric label="时机" value={asset.score.rotationTimingScore} />
        </div>

        <TodayAdvicePanel advice={asset.todayAdvice} />
        <EvidenceCompletenessPanel completeness={asset.evidenceCompleteness} />
        <RecommendationEvidenceBundlePanel symbol={asset.symbol} bundle={asset.evidenceBundle} />

        <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <Tag tone={reviewTone(asset.review.status)}>{asset.review.statusLabel}</Tag>
            <span className="text-xs text-ink-400">{asset.review.sources.length} 个来源</span>
          </div>
          <p className="mt-2 text-sm leading-relaxed text-ink-700">{asset.review.conclusion}</p>
          <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
            <ListBlock title="已核验证据" items={asset.review.verifiedFindings} tone="success" />
            <ListBlock title="阻断条件" items={asset.review.blockers.length ? asset.review.blockers : ['暂无阻断条件']} tone="warning" />
          </div>
          {asset.review.sources.length ? (
            <div className="mt-3 flex flex-wrap gap-2">
              {asset.review.sources.map((source) => source.url ? (
                <a
                  key={`${source.title}-${source.url}`}
                  className="rounded-full border border-line-soft px-2.5 py-1 text-xs font-medium text-brand-600 transition hover:border-brand-300 hover:bg-brand-50"
                  href={source.url}
                  target="_blank"
                  rel="noreferrer"
                >
                  {source.title}
                </a>
              ) : (
                <span key={source.title} className="rounded-full border border-line-soft px-2.5 py-1 text-xs font-medium text-ink-500">
                  {source.title}
                </span>
              ))}
            </div>
          ) : null}
        </div>

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <ListBlock title="错杀逻辑" items={asset.strengths} tone="success" />
          <ListBlock title="风险约束" items={asset.risks} tone="warning" />
          <ListBlock title="进入规则" items={asset.entryRules} tone="brand" />
          <ListBlock title="退出规则" items={asset.exitRules} tone="danger" />
        </div>
      </div>
    </Card>
  )
}

function EvidenceCompletenessPanel({ completeness }: { completeness: MispricedAsset['evidenceCompleteness'] }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone={completeness.allowsBuy ? 'success' : completeness.status === 'PARTIAL' ? 'warning' : 'neutral'}>
            {completeness.statusLabel}
          </Tag>
          <span className="tabular text-xs font-semibold text-ink-500">完整度 {completeness.score}</span>
        </div>
        <span className="text-xs text-ink-400">{completeness.allowsBuy ? '允许买入建议' : '买入闸门关闭'}</span>
      </div>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <ListBlock title="已有证据" items={completeness.presentEvidence} tone="success" />
        <ListBlock title="缺失证据" items={completeness.missingEvidence} tone="warning" />
      </div>
    </div>
  )
}

function TodayAdvicePanel({ advice }: { advice: MispricedAsset['todayAdvice'] }) {
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

function reviewTone(status: string): 'brand' | 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'PASSED') return 'success'
  if (status === 'WAIT_PRICE_CONFIRM') return 'warning'
  if (status === 'WAIT_TRIGGER') return 'warning'
  if (status === 'CYCLICAL_ONLY') return 'warning'
  if (status === 'REJECT_QUALITY_MISPRICING') return 'danger'
  return 'neutral'
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
