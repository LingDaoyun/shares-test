import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { DatabaseZap, RefreshCw, SlidersHorizontal } from 'lucide-react'
import { fetchMarketScanReport } from '../api/client'
import type { MarketScanParams } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DetailOverlay, resolveDetailSelection } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { RecommendationEvidenceBundlePanel } from '../components/recommendation/EvidenceBundlePanel'
import { V2StrategyBundlePanel } from '../components/recommendation/V2StrategyBundlePanel'
import { WatchButton } from '../components/watchlist/WatchButton'
import { SectionBanner } from '../components/ui/SectionBanner'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPerSharePrice, formatSignedPercent, formatValuationState } from '../lib/format'
import type { MarketScanCandidate, MarketScanReport, TradingAdvice, V2StrategyBundleParams } from '../types'

interface DraftParams {
  limit: number
  scanLimit: number
  minAmountYi: number
  maxPe: number
  maxPb: number
  minFinancialScore: number
  excludeSideways: boolean
  includeNorthExchange: boolean
  mode: string
}

const DEFAULT_DRAFT: DraftParams = {
  limit: 3,
  scanLimit: 6000,
  minAmountYi: 0.8,
  maxPe: 35,
  maxPb: 4.5,
  minFinancialScore: 45,
  excludeSideways: true,
  includeNorthExchange: true,
  mode: 'VALUE'
}

export function MarketScanPage() {
  const [draft, setDraft] = useState<DraftParams>(DEFAULT_DRAFT)
  const [params, setParams] = useState<DraftParams>(DEFAULT_DRAFT)
  const [report, setReport] = useState<MarketScanReport | null>(null)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    fetchMarketScanReport(toApiParams(params))
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

  useEffect(() => {
    if (selectedSymbol && !report?.candidates.some((candidate) => candidate.symbol === selectedSymbol)) {
      setSelectedSymbol(null)
    }
  }, [report, selectedSymbol])

  const selected = useMemo(() => {
    return resolveDetailSelection(report?.candidates ?? [], selectedSymbol, (candidate) => candidate.symbol)
  }, [report, selectedSymbol])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="LONG VALUE"
        title="长期价投"
        description="覆盖沪深北 A 股并核对数据完整度，默认按长期价值投资模式输出三支候选。"
        extra={
          <Button
            variant="primary"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={loading}
            onClick={() => setParams({ ...draft })}
          >
            重新扫描
          </Button>
        }
      />

      <Card
        title={
          <span className="inline-flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-brand-500" />
            长期价投阈值
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
          <NumberField label="候选数量" value={draft.limit} min={3} max={20} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="扫描数量" value={draft.scanLimit} min={50} max={6000} step={100} onChange={(value) => setDraft({ ...draft, scanLimit: value })} />
          <NumberField label="成交额下限(亿)" value={draft.minAmountYi} min={0.8} max={20} step={0.05} onChange={(value) => setDraft({ ...draft, minAmountYi: value })} />
          <NumberField label="PE 参考带" value={draft.maxPe} min={4} max={120} onChange={(value) => setDraft({ ...draft, maxPe: value })} />
          <NumberField label="PB 参考带" value={draft.maxPb} min={0.2} max={20} step={0.1} onChange={(value) => setDraft({ ...draft, maxPb: value })} />
          <NumberField label="财务分下限" value={draft.minFinancialScore} min={0} max={90} step={1} onChange={(value) => setDraft({ ...draft, minFinancialScore: value })} />
          <SelectField
            label="模式"
            value={draft.mode}
            options={[
              ['ALL', '全量'],
              ['VALUE', '长投'],
              ['CYCLE', '周期'],
              ['SHORT_TERM', '短线']
            ]}
            onChange={(value) => setDraft({ ...draft, mode: value })}
          />
          <div className="grid grid-cols-1 gap-2">
            <ToggleField
              label="短线排除横盘"
              checked={draft.mode === 'SHORT_TERM' && draft.excludeSideways}
              disabled={draft.mode !== 'SHORT_TERM'}
              onChange={(checked) => setDraft({ ...draft, excludeSideways: checked })}
            />
            <ToggleField label="含北交所" checked={draft.includeNorthExchange} onChange={(checked) => setDraft({ ...draft, includeNorthExchange: checked })} />
          </div>
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            长投默认输出三支全市场价投候选；参考带只影响估值语境分和风险提示，不决定股票是否入选。
          </p>
          <Button variant="secondary" onClick={() => setParams({ ...draft })}>应用阈值</Button>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? <Card><Loader text="全市场扫描中" /></Card> : null}

      {report ? (
        <>
          <FunnelStrip report={report} />

          <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
            <Card title="方法">
              <div className="flex flex-col gap-2 text-sm leading-relaxed text-ink-600">
                {report.methodology.map((item) => <p key={item}>{item}</p>)}
              </div>
            </Card>
            <Card title="扫描快照">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="候选池" value={`${report.candidateCount}/${report.universeCount}`} />
                <Metric label="已评分" value={report.reviewedCount} />
                <Metric label="目标覆盖" value={report.coverage.expectedCount} />
                <Metric label="实际覆盖" value={report.coverage.fetchedCount} />
                <Metric label="缺失" value={report.coverage.missingCount} />
                <Metric label="覆盖状态" value={report.coverage.complete ? '完整' : '部分'} />
                <Metric label="行情时间" value={formatDateTime(report.coverage.fetchedAt)} />
                <Metric label="成交额阈值" value={formatAmount(report.ruleSet.minAmount)} />
              </div>
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
            </Card>
            <Card title="规则">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="扫描数量" value={report.ruleSet.scanLimit} />
                <Metric label="PE 参考带" value={formatNumber(report.ruleSet.maxPe)} />
                <Metric label="PB 参考带" value={formatNumber(report.ruleSet.maxPb)} />
                <Metric label="财务分" value={formatNumber(report.ruleSet.minFinancialScore)} />
                <Metric label="模式" value={modeLabel(report.ruleSet.mode)} />
                <Metric label="单票上限" value={`${formatNumber(report.ruleSet.maxSinglePositionPercent)}%`} />
              </div>
            </Card>
          </div>

          <Card title={<span className="inline-flex items-center gap-2"><DatabaseZap className="h-4 w-4 text-brand-500" />候选股票</span>} flush>
            <div className="divide-y divide-line-soft">
              {report.candidates.map((candidate) => (
                <CandidateRow
                  key={candidate.symbol}
                  candidate={candidate}
                  selected={selected?.symbol === candidate.symbol}
                  onSelect={() => setSelectedSymbol(candidate.symbol)}
                />
              ))}
            </div>
          </Card>

          <DetailOverlay
            open={selected !== null}
            title={selected ? `${selected.name} ${selected.symbol}` : '长期价投候选详情'}
            subtitle={selected ? `${selected.market ?? 'A股'} · ${selected.industry ?? '行业待补'} · 排名 #${selected.rank}` : undefined}
            onClose={() => setSelectedSymbol(null)}
          >
            {selected ? <CandidateDetail candidate={selected} /> : null}
          </DetailOverlay>

          <ExclusionPanel report={report} />
        </>
      ) : null}
    </div>
  )
}

function toApiParams(params: DraftParams): MarketScanParams {
  return {
    limit: params.limit,
    scanLimit: params.scanLimit,
    minAmount: Math.round(params.minAmountYi * 100000000),
    maxPe: params.maxPe,
    maxPb: params.maxPb,
    minFinancialScore: params.minFinancialScore,
    excludeSideways: params.excludeSideways,
    includeNorthExchange: params.includeNorthExchange,
    mode: params.mode
  }
}

function FunnelStrip({ report }: { report: MarketScanReport }) {
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
      {report.stageStats.map((stage) => (
        <div key={stage.stage} className="rounded-lg border border-line-soft bg-white px-3 py-3 shadow-sm">
          <div className="text-xs text-ink-400">{stage.label}</div>
          <div className="mt-1 flex items-end justify-between gap-2">
            <span className="tabular text-lg font-semibold text-ink-900">{stage.passedCount}</span>
            <span className="tabular text-xs text-ink-400">
              {stage.excludedCount > 0 ? `排除 ${stage.excludedCount}` : stage.deferredCount > 0 ? `延后 ${stage.deferredCount}` : '无损失'}
            </span>
          </div>
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-line-soft">
            <div
              className="h-full rounded-full bg-brand-500"
              style={{ width: `${stage.inputCount > 0 ? Math.max(4, Math.min(100, (stage.passedCount / stage.inputCount) * 100)) : 0}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

function ExclusionPanel({ report }: { report: MarketScanReport }) {
  if (!report.exclusionsSample.length) return null
  return (
    <Card title="排除样本" flush>
      <div className="divide-y divide-line-soft">
        {report.exclusionsSample.slice(0, 12).map((item, index) => (
          <div key={`${item.symbol ?? 'missing'}-${item.stage}-${index}`} className="grid grid-cols-1 gap-2 px-5 py-3 text-sm md:grid-cols-[160px_120px_minmax(0,1fr)]">
            <div className="min-w-0">
              <div className="truncate font-semibold text-ink-900">{item.name ?? '名称缺失'}</div>
              <div className="font-mono text-xs text-ink-400">{item.symbol ?? '代码缺失'}</div>
            </div>
            <Tag tone="neutral">{stageLabel(item.stage)}</Tag>
            <div className="min-w-0">
              <p className="text-ink-700">{item.reason}</p>
              {item.evidence.length ? <p className="mt-1 truncate text-xs text-ink-400">{item.evidence.join(' / ')}</p> : null}
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}

function CandidateRow({
  candidate,
  selected,
  onSelect
}: {
  candidate: MarketScanCandidate
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`grid w-full grid-cols-1 gap-3 px-5 py-4 text-left transition hover:bg-brand-50/70 md:grid-cols-[minmax(0,1.1fr)_1fr_auto] ${selected ? 'bg-brand-50' : 'bg-white'}`}
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular text-xs font-semibold text-ink-400">#{candidate.rank}</span>
          <h3 className="truncate text-base font-semibold text-ink-900">{candidate.name}</h3>
          <span className="font-mono text-xs text-ink-400">{candidate.symbol}</span>
        </div>
        <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-ink-500">{candidate.reason}</p>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {candidate.tags.slice(0, 4).map((tag) => <Tag key={tag} tone="sky">{tag}</Tag>)}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
        <Metric label="每股" value={formatPerSharePrice(candidate.latestPrice)} compact />
        <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} compact />
        <Metric label="PE" value={formatNumber(candidate.peTtm)} compact />
        <Metric label="PB" value={formatNumber(candidate.pbRatio)} compact />
      </div>

      <div className="flex items-center justify-between gap-3 md:flex-col md:items-end md:justify-center">
        <ScoreBadge value={candidate.score.finalScore} />
        <Tag tone={adviceTone(candidate.todayAdvice.action)}>今日：{candidate.todayAdvice.actionLabel}</Tag>
      </div>
    </button>
  )
}

function CandidateDetail({ candidate }: { candidate: MarketScanCandidate }) {
  return (
    <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-soft pb-4">
          <div className="flex flex-wrap items-center gap-2">
            <ScoreBadge value={candidate.score.finalScore} />
            <Tag tone={adviceTone(candidate.todayAdvice.action)}>今日：{candidate.todayAdvice.actionLabel}</Tag>
          </div>
          <WatchButton symbol={candidate.symbol} />
        </div>

        <p className="text-sm leading-relaxed text-ink-600">{candidate.reason}</p>

        <div className="grid grid-cols-2 gap-2">
          <Metric label="每股价格" value={formatPerSharePrice(candidate.latestPrice)} />
          <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} />
          <Metric label="PE TTM" value={formatNumber(candidate.peTtm)} />
          <Metric label="PB" value={formatNumber(candidate.pbRatio)} />
          <Metric
            label="估值语境"
            value={candidate.valuationContext.applicableModel === 'CYCLICAL' && candidate.valuationContext.state === 'DISTORTED'
              ? '周期盈利失真'
              : formatValuationState(candidate.valuationContext.state)}
          />
          <Metric label="估值参考" value={`PE ${formatNumber(candidate.valuationContext.peReference)} / PB ${formatNumber(candidate.valuationContext.pbReference)}`} />
          <Metric label="成交额" value={formatAmount(candidate.amount)} />
          <Metric label="今日建议" value={candidate.todayAdvice.actionLabel} />
          <Metric label="资格阶段" value={candidate.screeningActionLabel} />
          <Metric label="证据完整度" value={`${candidate.evidenceCompleteness.score}`} />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <ScoreMetric label="估值语境 10%" value={candidate.score.valuationScore} />
          <ScoreMetric label="流动性" value={candidate.score.liquidityScore} />
          <ScoreMetric label="位置" value={candidate.score.priceActionScore} />
          <ScoreMetric label="质量代理" value={candidate.score.qualityProxyScore} />
          <ScoreMetric label="风险" value={candidate.score.riskScore} />
          <ScoreMetric label="综合" value={candidate.score.finalScore} />
        </div>

        <TodayAdvicePanel advice={candidate.todayAdvice} />
        <V2StrategyBundlePanel
          symbol={candidate.symbol}
          companyName={candidate.name}
          focus="long"
          factorContext={marketFactorContext(candidate)}
        />
        <EvidenceCompletenessPanel completeness={candidate.evidenceCompleteness} />
        <RecommendationEvidenceBundlePanel symbol={candidate.symbol} bundle={candidate.evidenceBundle} compact />

        <div className="grid grid-cols-1 gap-3">
          <ListBlock title="支撑逻辑" items={candidate.strengths} tone="success" />
          <ListBlock title="风险约束" items={candidate.risks} tone="warning" />
          <ListBlock title="数据缺口" items={visibleDataGaps(candidate.dataGaps)} tone="brand" />
        </div>

        <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
          <Tag tone="neutral">证据链</Tag>
          <div className="mt-3 flex flex-col gap-3">
            {candidate.trace.map((step) => (
              <div key={step.step} className="border-l-2 border-brand-200 pl-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-ink-900">{step.title}</span>
                  {step.sourceUrl ? (
                    <a className="text-xs font-medium text-brand-600 hover:text-brand-700" href={step.sourceUrl} target="_blank" rel="noreferrer">
                      {step.sourceName ?? '来源'}
                    </a>
                  ) : (
                    <span className="text-xs text-ink-400">{step.sourceName ?? step.step}</span>
                  )}
                </div>
                <p className="mt-1 text-xs leading-relaxed text-ink-500">{step.summary}</p>
                <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed text-ink-600">
                  {step.findings.map((finding, index) => <li key={`${step.step}-${index}`}>{finding}</li>)}
                </ul>
              </div>
            ))}
          </div>
        </div>
    </div>
  )
}

function EvidenceCompletenessPanel({ completeness }: { completeness: MarketScanCandidate['evidenceCompleteness'] }) {
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
      <div className="mt-3 grid grid-cols-1 gap-3">
        <ListBlock title="已有证据" items={completeness.presentEvidence} tone="success" />
        <ListBlock title="缺失证据" items={completeness.missingEvidence} tone="warning" />
      </div>
    </div>
  )
}

function visibleDataGaps(items: string[]) {
  const filtered = items.filter((item) => !item.includes('深度证据复核已进入后台队列'))
  return filtered.length ? filtered : ['暂无新增缺口']
}

function marketFactorContext(candidate: MarketScanCandidate): Omit<V2StrategyBundleParams, 'symbol' | 'companyName'> {
  const qualityScore = candidate.score.qualityProxyScore
  const evidenceScore = candidate.evidenceCompleteness.score
  return {
    industry: candidate.industry ?? '全市场候选',
    valuationDiscountScore: candidate.score.valuationScore,
    qualityScore,
    moatScore: qualityScore,
    profitabilityScore: qualityScore,
    cashFlowScore: evidenceScore,
    cyclePositionScore: candidate.score.priceActionScore,
    cycleRecoveryScore: candidate.score.priceActionScore,
    industryLeaderScore: qualityScore,
    policyCatalystScore: evidenceScore,
    liquidityScore: candidate.score.liquidityScore,
    fundamentalFloorScore: qualityScore,
    marketHotScore: candidate.score.priceActionScore,
    rightSideStructureScore: candidate.score.priceActionScore,
    supplyAbsorptionScore: candidate.score.liquidityScore,
    crowdingRiskScore: Math.max(0, Math.min(100, candidate.score.riskScore))
  }
}

function TodayAdvicePanel({ advice }: { advice: TradingAdvice }) {
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
      <div className="mt-3 grid grid-cols-1 gap-3">
        <ListBlock title="建议依据" items={advice.reasons} tone="brand" />
        <ListBlock title="风控条件" items={advice.riskControls} tone="warning" />
      </div>
    </div>
  )
}

function adviceTone(action: string): 'success' | 'brand' | 'warning' | 'danger' | 'neutral' {
  if (action === 'ADD') return 'success'
  if (action === 'LIGHT_TRIAL') return 'brand'
  if (action === 'HOLD') return 'brand'
  if (action === 'WAIT_PULLBACK') return 'warning'
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

function SelectField({
  label,
  value,
  options,
  onChange
}: {
  label: string
  value: string
  options: Array<[string, string]>
  onChange: (value: string) => void
}) {
  return (
    <label>
      <span className="field-label">{label}</span>
      <select className="field" value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map(([optionValue, optionLabel]) => (
          <option key={optionValue} value={optionValue}>{optionLabel}</option>
        ))}
      </select>
    </label>
  )
}

function ToggleField({
  label,
  checked,
  disabled = false,
  onChange
}: {
  label: string
  checked: boolean
  disabled?: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <label className={`flex min-h-[54px] items-center justify-between gap-2 rounded-lg border border-line-soft bg-white px-3 py-2 text-sm font-medium ${disabled ? 'text-ink-300' : 'text-ink-700'}`}>
      <span>{label}</span>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
    </label>
  )
}

function Metric({ label, value, compact = false }: { label: string; value: ReactNode; compact?: boolean }) {
  return (
    <div className={`rounded-lg border border-line-soft bg-line-soft/40 ${compact ? 'px-2.5 py-2' : 'px-3 py-2'}`}>
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 break-words tabular text-sm font-semibold text-ink-900">{value}</div>
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

function stageLabel(stage: string) {
  const labels: Record<string, string> = {
    TRADABLE: '可交易',
    MODE_ELIGIBILITY: '策略资格',
    LIQUIDITY: '流动性',
    SCORE: '评分资格',
    DEEP_REVIEW: '深度复核',
    SIDEWAYS: '横盘',
    FINAL: '候选'
  }
  return labels[stage] ?? stage
}

function modeLabel(mode: string) {
  const labels: Record<string, string> = {
    ALL: '全量',
    VALUE: '长投',
    CYCLE: '周期',
    SHORT_TERM: '短线'
  }
  return labels[mode] ?? mode
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
        {items.slice(0, 5).map((item) => <li key={item}>{item}</li>)}
      </ul>
    </div>
  )
}
