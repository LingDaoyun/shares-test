import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { CandlestickChart, RefreshCw, SlidersHorizontal } from 'lucide-react'
import type { ShortTermParams } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DetailOverlay, resolveDetailSelection } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { OvernightTradePlanPanel } from '../components/shortterm/OvernightTradePlanPanel'
import { CompositeScoreBadge, MomentumQualityTags, RightSideSignalTag } from '../components/shortterm/ShortTermCandidateIndicators'
import { BuyEntryButton } from '../components/tradefeedback/BuyEntryButton'
import { TradeReviewButton } from '../components/tradefeedback/TradeReviewButton'
import { WatchButton } from '../components/watchlist/WatchButton'
import { V2StrategyBundlePanel } from '../components/recommendation/V2StrategyBundlePanel'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatPercent, formatPerSharePrice, formatRatioPercent, formatSignedPercent, formatValuationState } from '../lib/format'
import { goldenCrossAlignmentLabel, goldenCrossCounterEvidence, goldenCrossCounterEvidenceTone, goldenCrossDisplayLabel, goldenCrossSpreadLabel, goldenCrossSpreadTrendLabel, goldenCrossTone, goldenCrossV2Context } from '../lib/shortTermGoldenCross'
import { loadShortTermViewPreferences, saveShortTermViewPreferences } from '../lib/shortTermViewPreferences'
import type { ShortTermViewPreferences } from '../lib/shortTermViewPreferences'
import { useShortTermScanStore } from '../store/shortTermScanStore'
import type { ShortTermCandidate, ShortTermGoldenCrossSnapshot, ShortTermHotDirection, ShortTermIndustryFundDirection, ShortTermMarketFundDirection, ShortTermReport, ShortTermSupportReversalSignal, ShortTermTailSignal, ShortTermWeightProfile, TradingAdvice, V2StrategyBundleParams } from '../types'

interface DraftParams {
  limit: number
  scanLimit: number
  klineLimit: number
  minAmountYi: number
  maxPe: number
  maxPb: number
  minVolumeRatio: number
  maxEntryRise: number
  maxDistanceToMa20: number
  minFinancialScore: number
  allowStaticCachePreview: boolean
  allowChiNext: boolean
}

const DEFAULT_DRAFT: DraftParams = {
  limit: 8,
  scanLimit: 6000,
  klineLimit: 120,
  minAmountYi: 0.8,
  maxPe: 100,
  maxPb: 15,
  minVolumeRatio: 1.2,
  maxEntryRise: 6.5,
  maxDistanceToMa20: 8,
  minFinancialScore: 55,
  allowStaticCachePreview: true,
  allowChiNext: false
}

const actionTone: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky'> = {
  RIGHT_EARLY_ADD: 'success',
  SUPPORT_REVERSAL_LIGHT_TRIAL: 'success',
  WATCH_RIGHT_SIDE: 'brand',
  WATCH_VALUE_RETURN: 'brand',
  WAIT_PULLBACK: 'warning',
  WAIT_CONFIRM: 'neutral',
  MARKET_RISK_WAIT: 'warning',
  DATA_REVIEW: 'neutral'
}

export function ShortTermPage() {
  const [draft, setDraft] = useState<DraftParams>(DEFAULT_DRAFT)
  const [viewPreferences, setViewPreferences] = useState<ShortTermViewPreferences>(() => loadShortTermViewPreferences())
  const origin = useShortTermScanStore((state) => state.origin)
  const report = useShortTermScanStore((state) => state.report)
  const loading = useShortTermScanStore((state) => state.loading)
  const error = useShortTermScanStore((state) => state.error)
  const scanMessage = useShortTermScanStore((state) => state.scanMessage)
  const activeJobId = useShortTermScanStore((state) => state.activeJobId)
  const runManualScan = useShortTermScanStore((state) => state.runManualScan)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)

  useEffect(() => {
    if (selectedSymbol && !report?.candidates.some((candidate) => candidate.symbol === selectedSymbol)) {
      setSelectedSymbol(null)
    }
  }, [report, selectedSymbol])

  const selected = useMemo(() => {
    return resolveDetailSelection(report?.candidates ?? [], selectedSymbol, (candidate) => candidate.symbol)
  }, [report, selectedSymbol])

  const diagnostics = useMemo(() => shortTermDiagnostics(report), [report])

  useEffect(() => {
    saveShortTermViewPreferences(viewPreferences)
  }, [viewPreferences])

  function updateViewPreference(key: keyof ShortTermViewPreferences, checked: boolean) {
    setViewPreferences((current) => ({ ...current, [key]: checked }))
  }

  return (
    <div className="flex flex-col gap-4">
      <Card
        title={
          <span className="inline-flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-brand-500" />
            右侧启动阈值
          </span>
        }
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="primary"
              icon={<RefreshCw className="h-4 w-4" />}
              loading={origin === 'MANUAL' && loading}
              onClick={() => {
                setSelectedSymbol(null)
                void runManualScan(toApiParams({ ...draft }))
              }}
            >
              重新扫描
            </Button>
            <Button
              variant="secondary"
              disabled={origin === 'MANUAL' && loading}
              onClick={() => {
                setSelectedSymbol(null)
                void runManualScan(toApiParams({ ...draft }))
              }}
            >
              应用阈值
            </Button>
          </div>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <NumberField label="候选数量" value={draft.limit} min={3} max={12} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="扫描数量" value={draft.scanLimit} min={50} max={6000} step={100} onChange={(value) => setDraft({ ...draft, scanLimit: value })} />
          <NumberField label="K线复核数" value={draft.klineLimit} min={10} max={160} step={10} onChange={(value) => setDraft({ ...draft, klineLimit: value })} />
          <NumberField label="成交额下限(亿)" value={draft.minAmountYi} min={0.8} max={30} step={0.05} onChange={(value) => setDraft({ ...draft, minAmountYi: value })} />
          <NumberField label="PE 参考带" value={draft.maxPe} min={4} max={200} onChange={(value) => setDraft({ ...draft, maxPe: value })} />
          <NumberField label="PB 参考带" value={draft.maxPb} min={0.2} max={40} step={0.1} onChange={(value) => setDraft({ ...draft, maxPb: value })} />
          <NumberField label="量比下限" value={draft.minVolumeRatio} min={1} max={3.2} step={0.05} onChange={(value) => setDraft({ ...draft, minVolumeRatio: value })} />
          <NumberField label="追涨上限%" value={draft.maxEntryRise} min={1} max={10} step={0.1} onChange={(value) => setDraft({ ...draft, maxEntryRise: value })} />
          <NumberField label="距20日线%" value={draft.maxDistanceToMa20} min={2} max={20} step={0.5} onChange={(value) => setDraft({ ...draft, maxDistanceToMa20: value })} />
          <NumberField label="财报分下限" value={draft.minFinancialScore} min={30} max={90} onChange={(value) => setDraft({ ...draft, minFinancialScore: value })} />
        </div>
        <label className="mt-3 flex cursor-pointer items-center justify-between gap-3 rounded-lg border border-line-soft bg-white px-3 py-2 text-sm">
          <span>
            <span className="block font-semibold text-ink-800">允许休市缓存预览</span>
            <span className="block text-xs leading-relaxed text-ink-500">
              开启后，手动扫描可用接口返回的静态行情看策略效果；结果会标记为缓存预览，不作为今日买点。
            </span>
          </span>
          <input
            type="checkbox"
            className="h-5 w-5 accent-brand-600"
            checked={draft.allowStaticCachePreview}
            onChange={(event) => setDraft({ ...draft, allowStaticCachePreview: event.target.checked })}
            aria-label="允许休市缓存预览"
          />
        </label>
        <label className="mt-3 flex cursor-pointer items-center justify-between gap-3 rounded-lg border border-line-soft bg-white px-3 py-2 text-sm">
          <span>
            <span className="block font-semibold text-ink-800">允许创业板</span>
            <span className="block text-xs leading-relaxed text-ink-500">
              默认关闭，剔除 300/301 开头股票；开通创业板权限后可开启纳入扫描。
            </span>
          </span>
          <input
            type="checkbox"
            className="h-5 w-5 accent-brand-600"
            checked={draft.allowChiNext}
            onChange={(event) => setDraft({ ...draft, allowChiNext: event.target.checked })}
            aria-label="允许创业板"
          />
        </label>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            参考带只影响估值语境分和风险提示，不决定股票是否入选；低流动性、长期横盘、急拉和离均线过远仍受约束。
          </p>
        </div>
      </Card>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      {loading && !report ? (
        <Card>
          <Loader text={scanMessage || '短线右侧扫描中'} />
          {activeJobId ? <p className="mt-3 text-center font-mono text-xs text-ink-400">任务 {activeJobId}</p> : null}
        </Card>
      ) : null}

      {report ? (
        <>
          <ResultViewControls
            preferences={viewPreferences}
            onChange={updateViewPreference}
          />

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-3" data-testid="short-term-horizontal-summary">
            {viewPreferences.marketSentimentVisible ? (
              <MarketSentimentSummaryCard report={report} />
            ) : null}
            {viewPreferences.snapshotVisible ? (
              <ScanSnapshotSummaryCard report={report} diagnostics={diagnostics} />
            ) : null}
            {viewPreferences.hotDirectionsVisible ? (
              <HotDirectionsCard directions={report.hotDirections} />
            ) : null}
          </div>

          {viewPreferences.methodologyVisible || viewPreferences.fundFlowVisible ? (
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
              {viewPreferences.methodologyVisible ? (
                <MethodologyCard methodology={report.methodology} />
              ) : null}
              {viewPreferences.fundFlowVisible ? (
                <MarketFundDirectionCard direction={report.marketFundDirection} />
              ) : null}
            </div>
          ) : null}

          <Card title={<span className="inline-flex items-center gap-2"><CandlestickChart className="h-4 w-4 text-brand-500" />右侧候选</span>} flush>
            {report.candidates.length ? (
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
            ) : (
              <div className="p-5"><Loader text="暂无候选" /></div>
            )}
          </Card>

          <DetailOverlay
            open={selected !== null}
            title={selected ? `${selected.name} ${selected.symbol}` : '短线候选详情'}
            subtitle={selected ? `${selected.market ?? 'A股'} · ${selected.industry ?? '行业待补'} · 排名 #${selected.rank}` : undefined}
            onClose={() => setSelectedSymbol(null)}
          >
            {selected ? (
              <CandidateDetail
                candidate={selected}
                weightProfile={report.weightProfile}
                generatedAt={report.generatedAt}
                tradeCaptureToken={report.tradeCaptureTokens?.[selected.symbol] ?? null}
              />
            ) : null}
          </DetailOverlay>
        </>
      ) : null}
    </div>
  )
}

const resultViewOptions: Array<{
  key: keyof ShortTermViewPreferences
  label: string
  description: string
}> = [
  { key: 'methodologyVisible', label: '方法', description: '策略口径' },
  { key: 'marketSentimentVisible', label: '市场情绪', description: '今日宽度' },
  { key: 'fundFlowVisible', label: '今日资金去向', description: '行业流向' },
  { key: 'snapshotVisible', label: '扫描快照', description: '覆盖与结果' },
  { key: 'hotDirectionsVisible', label: '热门方向', description: '方向热度' }
]

function ResultViewControls({
  preferences,
  onChange
}: {
  preferences: ShortTermViewPreferences
  onChange: (key: keyof ShortTermViewPreferences, checked: boolean) => void
}) {
  const visibleCount = resultViewOptions.filter((option) => preferences[option.key]).length
  return (
    <Card className="border-brand-100 bg-gradient-to-r from-white via-brand-50/40 to-sky-50/50">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <div className="eyebrow mb-1">RESULT VIEW</div>
          <h3 className="text-base font-semibold text-ink-900">结果视图</h3>
          <p className="mt-1 text-xs leading-relaxed text-ink-500">
            已展示 {visibleCount}/{resultViewOptions.length} 个模块，勾选状态会在本机浏览器记住。
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {resultViewOptions.map((option) => (
            <label
              key={option.key}
              className={`group flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-sm transition ${preferences[option.key] ? 'border-brand-200 bg-white text-ink-900 shadow-sm' : 'border-line-soft bg-white/60 text-ink-500 hover:bg-white'}`}
            >
              <input
                type="checkbox"
                className="h-4 w-4 accent-brand-600"
                checked={preferences[option.key]}
                onChange={(event) => onChange(option.key, event.target.checked)}
                aria-label={`展示${option.label}`}
              />
              <span>
                <span className="block font-semibold">{option.label}</span>
                <span className="block text-[11px] text-ink-400">{option.description}</span>
              </span>
            </label>
          ))}
        </div>
      </div>
    </Card>
  )
}

function MethodologyCard({ methodology }: { methodology: string[] }) {
  return (
    <Card title="方法">
      <div className="grid grid-cols-1 gap-2 text-sm leading-relaxed text-ink-600 sm:grid-cols-2">
        {methodology.map((item) => (
          <p key={item} className="rounded-lg border border-line-soft bg-line-soft/30 px-3 py-2">{item}</p>
        ))}
      </div>
    </Card>
  )
}

function MarketSentimentSummaryCard({ report }: { report: ShortTermReport }) {
  const sentiment = report.marketSentiment
  return (
    <Card className="min-h-full border-red-100 bg-gradient-to-br from-white to-red-50/40">
      <div className="flex h-full flex-col justify-between gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow mb-1 text-red-600">MARKET MOOD</div>
            <h3 className="text-base font-semibold text-ink-900">市场情绪</h3>
          </div>
          <Tag tone={sentiment.score >= 70 ? 'success' : sentiment.score >= 50 ? 'brand' : 'warning'}>
            {sentiment.phase}
          </Tag>
        </div>
        <div className="flex flex-wrap items-end gap-x-5 gap-y-3">
          <div>
            <p className="text-xs text-ink-400">情绪分</p>
            <p className="tabular text-3xl font-semibold text-ink-900">{formatNumber(sentiment.score)}</p>
          </div>
          <div className="flex flex-1 flex-wrap gap-2 text-xs">
            <InlineMetric label="上涨 / 下跌" value={`${sentiment.advancing} / ${sentiment.declining}`} />
            <InlineMetric label="涨停 / 跌停" value={`${sentiment.limitUpLike} / ${sentiment.limitDownLike}`} />
            <InlineMetric label="市场宽度" value={formatPercent(sentiment.breadthPercent)} />
          </div>
        </div>
        <p className="line-clamp-2 text-xs leading-relaxed text-ink-500">{sentiment.explanation}</p>
      </div>
    </Card>
  )
}

function ScanSnapshotSummaryCard({
  report,
  diagnostics
}: {
  report: ShortTermReport
  diagnostics: ReturnType<typeof shortTermDiagnostics>
}) {
  return (
    <Card className="min-h-full border-sky-100 bg-gradient-to-br from-white to-sky-50/50">
      <div className="flex h-full flex-col justify-between gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow mb-1 text-sky-600">SCAN SNAPSHOT</div>
            <h3 className="text-base font-semibold text-ink-900">扫描快照</h3>
          </div>
          <Tag tone={report.ruleSet?.allowChiNext ? 'brand' : 'neutral'}>
            创业板{report.ruleSet?.allowChiNext ? '纳入' : '剔除'}
          </Tag>
        </div>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <div>
            <p className="text-xs text-ink-400">候选数</p>
            <p className="tabular text-3xl font-semibold text-ink-900">{report.candidateCount}</p>
          </div>
          <div className="flex flex-1 flex-wrap gap-2 text-xs">
            <InlineMetric label="全市场样本" value={report.universeCount} />
            <InlineMetric label="K线复核" value={`${report.klineReviewedCount}/${report.reviewedCount}`} />
            <InlineMetric label="尾盘确认" value={diagnostics.tailConfirmedCount} />
            <InlineMetric label="强加仓" value={`${diagnostics.addCount}/${report.candidateCount}`} />
          </div>
        </div>
        <div className="flex flex-wrap gap-1.5">
          <Tag tone="neutral">{report.tradingSession.phaseLabel}</Tag>
          <Tag tone="neutral">{report.tradingSession.decisionTimeLabel}</Tag>
          <Tag tone="neutral">{formatDateTime(report.generatedAt)}</Tag>
        </div>
        {report.tradingSession.warnings.length ? (
          <p className="border-l-2 border-amber-300 pl-3 text-xs leading-relaxed text-amber-700">
            {report.tradingSession.warnings[0]}
          </p>
        ) : (
          <p className="text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
        )}
      </div>
    </Card>
  )
}

function HotDirectionsCard({ directions }: { directions: ShortTermHotDirection[] }) {
  return (
    <Card className="min-h-full border-amber-100 bg-gradient-to-br from-white to-amber-50/50">
      {directions.length ? (
        <div className="flex h-full flex-col gap-4">
          <div className="flex items-start justify-between gap-3">
            <div>
              <div className="eyebrow mb-1 text-amber-600">HOT LANES</div>
              <h3 className="text-base font-semibold text-ink-900">热门方向</h3>
            </div>
            <Tag tone="warning">{directions.length} 组</Tag>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-1">
            {directions.slice(0, 5).map((direction) => (
              <div key={direction.code} className="rounded-xl border border-amber-100 bg-white/80 px-3 py-2">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-semibold text-ink-900">{direction.label}</span>
                  <ScoreBadge value={direction.heatScore} />
                </div>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  <Tag tone="neutral">涨跌 {formatSignedPercent(direction.averageChangePercent)}</Tag>
                  <Tag tone="neutral">上涨 {formatPercent(direction.positiveRatioPercent)}</Tag>
                  <Tag tone="neutral">{direction.sampleCount} 只</Tag>
                </div>
                {direction.leaders.length ? (
                  <p className="mt-1 truncate text-xs text-ink-400">领涨：{direction.leaders.join('、')}</p>
                ) : null}
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="flex h-full flex-col justify-between gap-4">
          <div>
            <div className="eyebrow mb-1 text-amber-600">HOT LANES</div>
            <h3 className="text-base font-semibold text-ink-900">热门方向</h3>
          </div>
          <p className="text-sm leading-relaxed text-ink-500">本轮实时行情没有形成足够集中的热门方向。</p>
        </div>
      )}
    </Card>
  )
}

function toApiParams(params: DraftParams): ShortTermParams {
  return {
    limit: params.limit,
    scanLimit: params.scanLimit,
    klineLimit: params.klineLimit,
    minAmount: Math.round(params.minAmountYi * 100000000),
    maxPe: params.maxPe,
    maxPb: params.maxPb,
    minVolumeRatio: params.minVolumeRatio,
    maxEntryRise: params.maxEntryRise,
    maxDistanceToMa20: params.maxDistanceToMa20,
    minFinancialScore: params.minFinancialScore,
    allowStaticCachePreview: params.allowStaticCachePreview,
    allowChiNext: params.allowChiNext
  }
}

function MarketFundDirectionCard({ direction }: { direction?: ShortTermMarketFundDirection | null }) {
  const topInflows = direction?.topInflows ?? []
  const topOutflows = direction?.topOutflows ?? []
  const hasRows = topInflows.length > 0 || topOutflows.length > 0
  const coverage = direction && direction.expectedIndustryCount > 0
    ? `${direction.coveredIndustryCount}/${direction.expectedIndustryCount}`
    : '待补'

  return (
    <Card title="今日资金去向">
      <div className="grid grid-cols-1 gap-3 text-sm text-ink-600 xl:grid-cols-[220px_1fr]">
        <div className="rounded-xl border border-line-soft bg-line-soft/30 p-3">
          <div className="eyebrow mb-1">FUND FLOW</div>
          <h3 className="text-base font-semibold text-ink-900">行业资金流</h3>
          <div className="mt-3 flex flex-wrap gap-2 text-xs">
            <InlineMetric label="数据日" value={direction?.tradeDate ?? '待确认'} />
            <InlineMetric label="覆盖" value={coverage} />
          </div>
          <p className="mt-3 text-xs leading-relaxed text-ink-500">
            覆盖 {coverage} · {direction?.sourceName ?? '行业资金流未返回'}
          </p>
        </div>
        {hasRows ? (
          <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
            <FundDirectionList title="主力流入" items={topInflows} tone="success" />
            <FundDirectionList title="主力流出" items={topOutflows} tone="danger" />
          </div>
        ) : (
          <p className="rounded-lg border border-line-soft bg-ink-50 px-3 py-2 text-xs leading-relaxed text-ink-500">
            行业资金流暂不可用
          </p>
        )}
        {direction?.dataGaps?.length ? (
          <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800 xl:col-span-2">
            {direction.dataGaps.slice(0, 2).join('；')}
          </div>
        ) : null}
      </div>
    </Card>
  )
}

function FundDirectionList({
  title,
  items,
  tone
}: {
  title: string
  items: ShortTermIndustryFundDirection[]
  tone: 'success' | 'danger'
}) {
  if (!items.length) {
    return null
  }
  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <span className="text-xs font-semibold text-ink-700">{title}</span>
        <Tag tone={tone}>{items.length} 项</Tag>
      </div>
      <div className="flex flex-col gap-1.5">
        {items.map((item) => (
          <div key={`${title}-${item.code}`} className="rounded-lg border border-line-soft px-2.5 py-2">
            <div className="flex items-baseline justify-between gap-2">
              <span className="truncate font-semibold text-ink-900">{item.name}</span>
              <span className={`shrink-0 tabular font-semibold ${changeClass(item.mainNetInflow)}`}>
                {formatAmount(item.mainNetInflow)}
              </span>
            </div>
            <div className="mt-1 flex flex-wrap gap-1.5">
              <Tag tone="neutral">占比 {formatPercent(item.mainNetInflowRatio)}</Tag>
              <Tag tone="neutral">集中 {formatPercent(item.concentrationPercent)}</Tag>
              {item.constituentCount > 0 ? (
                <Tag tone="neutral">涨跌 {item.advancing}/{item.declining}</Tag>
              ) : null}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function shortTermDiagnostics(report: ShortTermReport | null) {
  const candidates = report?.candidates ?? []
  return {
    addCount: candidates.filter((candidate) => candidate.todayAdvice.action === 'ADD').length,
    lightTrialCount: candidates.filter((candidate) => candidate.todayAdvice.action === 'LIGHT_TRIAL').length,
    nextWatchCount: candidates.filter((candidate) => candidate.todayAdvice.action === 'NEXT_WATCH').length,
    pullbackAdviceCount: candidates.filter((candidate) => candidate.todayAdvice.action === 'WAIT_PULLBACK').length,
    tailConfirmedCount: candidates.filter((candidate) => candidate.tailSignal.status === 'CONFIRMED').length,
  }
}

function CandidateRow({
  candidate,
  selected,
  onSelect
}: {
  candidate: ShortTermCandidate
  selected: boolean
  onSelect: () => void
}) {
  const goldenCross = candidate.technical.goldenCross
  const supportReversal = candidate.technical.supportReversal
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`grid w-full grid-cols-1 gap-3 px-5 py-4 text-left transition hover:bg-brand-50/70 md:grid-cols-[minmax(0,1.2fr)_1fr_auto] ${selected ? 'bg-brand-50' : 'bg-white'}`}
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular text-xs font-semibold text-ink-400">#{candidate.rank}</span>
          <h3 className="truncate text-base font-semibold text-ink-900">{candidate.name}</h3>
          <span className="font-mono text-xs text-ink-400">{candidate.symbol}</span>
          <Tag tone="sky">{candidate.phaseLabel}</Tag>
          {supportReversal?.state === 'CONFIRMED' ? (
            <Tag tone="success">{supportReversal.stateLabel}</Tag>
          ) : null}
        </div>
        <p className="mt-1 line-clamp-2 text-sm leading-relaxed text-ink-500">{candidate.reason}</p>
        <div className="mt-2 flex flex-wrap gap-1.5">
          <RightSideSignalTag signal={candidate.technical.rightSideSignal} />
          <Tag tone={goldenCrossTone(goldenCross?.state)}>
            {goldenCrossDisplayLabel(goldenCross)}
            {goldenCross?.tradingDaysSinceCross != null
              ? ` · ${goldenCross.tradingDaysSinceCross}日`
              : ''}
          </Tag>
          <Tag tone="neutral">20日斜率 {formatNumber(candidate.technical.ma20SlopePercent)}%</Tag>
          <MomentumQualityTags quality={candidate.technical.momentumQuality} />
          {candidate.score.marketHeatScore >= 68 ? <Tag tone="brand">热度 {formatNumber(candidate.score.marketHeatScore)}</Tag> : null}
          <Tag tone="neutral">财报 {formatNumber(candidate.financial.qualityScore)}</Tag>
          <Tag tone={tailTone(candidate.tailSignal.status)}>尾盘：{candidate.tailSignal.statusLabel}</Tag>
          <Tag tone={candidate.quoteFreshness.blocksRealtimeDecision ? 'warning' : 'success'}>
            行情：{candidate.quoteFreshness.statusLabel}
          </Tag>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
        <Metric label="每股" value={formatPerSharePrice(candidate.latestPrice)} compact />
        <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} compact />
        <Metric label="距20日" value={`${formatNumber(candidate.technical.distanceToMa20Percent)}%`} compact />
        <Metric label="突破20高" value={`${formatNumber(candidate.technical.breakoutFromPreviousHigh20Percent)}%`} compact />
          <Metric label="20日量比" value={formatNumber(candidate.technical.volumeRatio20)} compact />
          <Metric label="换手率" value={`${formatNumber(candidate.technical.momentumQuality?.turnoverRatePercent)}%`} compact />
      </div>

      <div className="flex items-center justify-between gap-3 md:flex-col md:items-end md:justify-center">
        <CompositeScoreBadge value={candidate.score.rankingScore ?? candidate.score.finalScore} />
        <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag>
        <Tag tone={adviceTone(candidate.todayAdvice.action)}>建议：{candidate.todayAdvice.actionLabel}</Tag>
        <Tag tone={tailTone(candidate.tailSignal.status)}>{candidate.tailSignal.statusLabel}</Tag>
      </div>
    </button>
  )
}

function CandidateDetail({
  candidate,
  weightProfile,
  generatedAt,
  tradeCaptureToken
}: {
  candidate: ShortTermCandidate
  weightProfile: ShortTermWeightProfile
  generatedAt: string
  tradeCaptureToken: string | null
}) {
  return (
    <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-soft pb-4">
          <div className="flex flex-wrap items-center gap-2">
            <ScoreBadge value={candidate.score.rankingScore ?? candidate.score.finalScore} />
            <Tag tone={adviceTone(candidate.todayAdvice.action)}>建议：{candidate.todayAdvice.actionLabel}</Tag>
            <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <BuyEntryButton
              symbol={candidate.symbol}
              companyName={candidate.name}
              latestPrice={candidate.latestPrice}
              recommendedAt={candidate.quoteFreshness.marketTimestamp}
              attestationToken={tradeCaptureToken}
            />
            <WatchButton symbol={candidate.symbol} />
            <TradeReviewButton
              symbol={candidate.symbol}
              sourceModule="SHORT_TERM"
              ruleVersion="short-term-right-side-v4-transparent-ranking"
              recommendedAt={generatedAt}
              attestationToken={tradeCaptureToken}
            />
          </div>
        </div>

        <OvernightTradePlanPanel plan={candidate.tradePlan} />

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
          <Metric label="买入观察区" value={`${formatPrice(candidate.buyZoneLow)} - ${formatPrice(candidate.buyZoneHigh)}`} />
          <Metric label="止损参考" value={formatPrice(candidate.stopPrice)} />
          <Metric label="尾盘状态" value={candidate.tailSignal.statusLabel} />
          <Metric label="最新分时" value={candidate.tailSignal.latestMinute ?? '待补充'} />
          <Metric label="行情时点" value={candidate.quoteFreshness.statusLabel} />
          <Metric label="市场时间" value={formatDateTime(candidate.quoteFreshness.marketTimestamp)} />
          <Metric label="证据完整度" value={`${candidate.evidenceCompleteness.score}`} />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <ScoreMetric label="金叉 45%" value={candidate.score.goldenCrossScore} />
          <ScoreMetric label="量能 30%" value={candidate.score.volumeScore} />
          <ScoreMetric label="换手 15%" value={candidate.score.turnoverScore} />
          <ScoreMetric label="收盘强度 10%" value={candidate.score.closeStrengthScore} />
          {candidate.score.supportReversalScore != null
          && (candidate.technical.supportReversal?.state === 'CONFIRMED'
            || candidate.technical.supportReversal?.state === 'OBSERVATION') ? (
            <ScoreMetric label="承接反转分" value={candidate.score.supportReversalScore} />
          ) : null}
          <ScoreMetric label="四因子原始分" value={candidate.score.finalScore} />
          <ScoreMetric label="阶段校准" value={candidate.score.stageAdjustment ?? 0} />
          {candidate.score.fundFlowAdjustment != null ? (
            <ScoreMetric label="资金流微调（最多±2）" value={candidate.score.fundFlowAdjustment} />
          ) : null}
          <ScoreMetric label="排序分" value={candidate.score.rankingScore ?? candidate.score.finalScore} />
        </div>

        <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-line-soft pt-3 text-xs text-ink-500">
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型技术' : '金叉'} {formatNumber(weightProfile.finalGoldenCross * 100)}%</span>
          <span>量能 {formatNumber(weightProfile.finalVolume * 100)}%</span>
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型热度' : '换手'} {formatNumber(weightProfile.finalTurnover * 100)}%</span>
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型财务与估值' : '收盘强度'} {formatNumber(weightProfile.finalCloseStrength * 100)}%</span>
        </div>

        <SupportReversalDetail signal={candidate.technical.supportReversal} />

        <div className="grid grid-cols-2 gap-2">
          <Metric label="MA5/10/20" value={`${formatNumber(candidate.technical.ma5)} / ${formatNumber(candidate.technical.ma10)} / ${formatNumber(candidate.technical.ma20)}`} />
          <Metric label="20/60日线斜率" value={`${formatNumber(candidate.technical.ma20SlopePercent)}% / ${formatNumber(candidate.technical.ma60SlopePercent)}%`} />
          <Metric label="突破20日高点" value={`${formatNumber(candidate.technical.breakoutFromPreviousHigh20Percent)}%`} />
          <Metric label="60/120日位置" value={`${formatNumber(candidate.technical.rangePosition60)}% / ${formatNumber(candidate.technical.rangePosition120)}%`} />
          <Metric label="120日高点回撤" value={`${formatNumber(candidate.technical.drawdownFrom120HighPercent)}%`} />
          <Metric label="站上20日线天数" value={candidate.technical.consecutiveAboveMa20Days} />
          <Metric label="换手率" value={`${formatNumber(candidate.technical.momentumQuality?.turnoverRatePercent)}% · ${turnoverBandLabel(candidate.technical.momentumQuality?.turnoverBand)}`} />
          <Metric label="最新上影线" value={`${formatNumber(candidate.technical.momentumQuality?.latestUpperShadowPercent)}%`} />
          <Metric label="上影线中位数" value={`${formatNumber(candidate.technical.momentumQuality?.bullishUpperShadowMedian3Percent)}%`} />
          <Metric
            label="收盘位置"
            value={`${formatNumber(candidate.technical.momentumQuality?.closeLocationPercent)}% · ${candidate.technical.momentumQuality?.provisional ? '盘中暂定' : '正式日 K'}`}
          />
          <Metric label="ROE/毛利率" value={`${formatRatioPercent(candidate.financial.roe)} / ${formatRatioPercent(candidate.financial.grossMargin)}`} />
          <Metric label="现金流年数" value={`${candidate.financial.positiveCashFlowYears}/3`} />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <ScoreMetric label="技术结构语境" value={candidate.score.technicalScore} />
          <ScoreMetric label="市场热度语境" value={candidate.score.marketHeatScore} />
          <ScoreMetric label="估值语境" value={candidate.score.valuationScore} />
          <ScoreMetric label="财报语境" value={candidate.score.financialScore} />
          <ScoreMetric label="风险提示分（不计主分）" value={candidate.score.riskPenalty} />
        </div>

        <GoldenCrossDetail snapshot={candidate.technical.goldenCross} />

        <TailSignalPanel signal={candidate.tailSignal} />

        <TodayAdvicePanel advice={candidate.todayAdvice} />
        <V2StrategyBundlePanel
          symbol={candidate.symbol}
          companyName={candidate.name}
          focus="short"
          factorContext={shortTermFactorContext(candidate, tradeCaptureToken)}
        />
        <EvidenceCompletenessPanel completeness={candidate.evidenceCompleteness} />

        <div className="grid grid-cols-1 gap-3">
          <ListBlock title="支撑逻辑" items={candidate.strengths} tone="success" />
          <ListBlock title="风险约束" items={candidate.risks} tone="warning" />
          <ListBlock title="入场规则" items={candidate.entryRules} tone="brand" />
          <ListBlock title="退出规则" items={candidate.exitRules} tone="danger" />
        </div>

        <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
          <Tag tone="neutral">证据链</Tag>
          <div className="mt-3 flex flex-col gap-3">
            {candidate.evidence.map((item) => (
              <div key={item.title} className="border-l-2 border-brand-200 pl-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-semibold text-ink-900">{item.title}</span>
                  {item.url ? (
                    <a className="text-xs font-medium text-brand-600 hover:text-brand-700" href={item.url} target="_blank" rel="noreferrer">
                      来源
                    </a>
                  ) : (
                    <span className="text-xs text-ink-400">权重 {item.weight}</span>
                  )}
                </div>
                <p className="mt-1 text-xs leading-relaxed text-ink-500">{item.summary}</p>
              </div>
            ))}
          </div>
        </div>
    </div>
  )
}

function GoldenCrossDetail({ snapshot }: { snapshot: ShortTermGoldenCrossSnapshot | null | undefined }) {
  const counterEvidence = goldenCrossCounterEvidence(snapshot)
  const counterTone = goldenCrossCounterEvidenceTone(snapshot)
  const counterClassName = counterTone === 'warning'
    ? 'border-amber-300 text-amber-700'
    : 'border-line-soft text-ink-500'

  return (
    <>
      <div className="grid grid-cols-2 gap-2">
        <Metric label="金叉状态" value={<Tag tone={goldenCrossTone(snapshot?.state)}>{goldenCrossDisplayLabel(snapshot)}</Tag>} />
        <Metric label="交叉日期" value={snapshot?.crossDate ?? '数据不足'} />
        <Metric label="完成交易日" value={snapshot?.tradingDaysSinceCross != null ? `${snapshot.tradingDaysSinceCross} 日` : '数据不足'} />
        <Metric label="MA5/10差" value={goldenCrossSpreadLabel(snapshot)} />
        <Metric label="差值趋势" value={goldenCrossSpreadTrendLabel(snapshot?.spreadTrend)} />
        <Metric label="均线关系" value={goldenCrossAlignmentLabel(snapshot?.maAlignment)} />
        <Metric label="优先级" value={snapshot?.priorityTier != null ? `第 ${snapshot.priorityTier} 档` : '数据不足'} />
        <Metric label="规则版本" value={snapshot?.ruleVersion ?? '数据不足'} />
      </div>
      {counterEvidence ? (
        <p className={`border-l-2 pl-3 text-xs leading-relaxed ${counterClassName}`}>
          {counterEvidence}
        </p>
      ) : null}
    </>
  )
}

function SupportReversalDetail({ signal }: { signal: ShortTermSupportReversalSignal | null | undefined }) {
  if (!signal || (signal.state !== 'CONFIRMED' && signal.state !== 'OBSERVATION')) {
    return null
  }
  return (
    <section className="border-t border-line-soft pt-3" aria-label="长下影承接证据">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Tag tone={signal.state === 'CONFIRMED' ? 'success' : 'sky'}>{signal.stateLabel}</Tag>
        <span className="text-xs text-ink-500">独立形态认证，不单独推断主力做多</span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 md:grid-cols-4">
        <Metric label="下影线占比" value={`${formatNumber(signal.lowerShadowPercent)}%`} />
        <Metric label="实体 / 上影" value={`${formatNumber(signal.bodyPercent)}% / ${formatNumber(signal.upperShadowPercent)}%`} />
        <Metric label="收盘位置" value={`${formatNumber(signal.closeLocationPercent)}%`} />
        <Metric label="收复支撑" value={supportReference(signal)} />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">
        失效条件：重新跌破本次承接低点或收复支撑，或次日弱开后不能及时收回；确认信号最多对应轻仓试错。
      </p>
    </section>
  )
}

function supportReference(signal: ShortTermSupportReversalSignal) {
  const label = signal.supportType === 'MA5'
    ? '5 日线'
    : signal.supportType === 'MA10'
      ? '10 日线'
      : signal.supportType === 'MA20'
        ? '20 日线'
        : signal.supportType === 'PREVIOUS_HIGH20'
          ? '前 20 日高点'
          : '关键支撑'
  return signal.supportPrice == null ? label : `${label} ${formatPrice(signal.supportPrice)} 元`
}

function TailSignalPanel({ signal }: { signal: ShortTermTailSignal }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">尾盘确认</Tag>
          <Tag tone={tailTone(signal.status)}>{signal.statusLabel}</Tag>
        </div>
        <span className="tabular text-xs font-semibold text-ink-500">
          {signal.tradeDate ?? '当日'} {signal.latestMinute ?? '待分时'}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <Metric label="最新价" value={formatPerSharePrice(signal.latestPrice)} compact />
        <Metric label="收盘确认价" value={formatPerSharePrice(signal.tailStartPrice)} compact />
        <Metric label="14:57后涨跌" value={<span className={changeClass(signal.changeFromTailConfirmPercent)}>{formatSignedPercent(signal.changeFromTailConfirmPercent)}</span>} compact />
        <Metric label="高点回落" value={formatPercent(signal.drawdownFromTailHighPercent)} compact />
        <Metric label="相对均价线" value={<span className={changeClass(signal.closeVsAveragePricePercent)}>{formatSignedPercent(signal.closeVsAveragePricePercent)}</span>} compact />
        <Metric label="尾盘成交占比" value={formatPercent(signal.tailAmountRatioPercent)} compact />
        <Metric label="尾盘成交额" value={formatAmount(signal.tailAmount)} compact />
        <Metric label="尾盘分" value={formatNumber(signal.score)} compact />
      </div>
      <div className="mt-3 grid grid-cols-1 gap-3">
        <ListBlock title="尾盘依据" items={signal.reasons} tone="brand" />
        <ListBlock title="执行纪律" items={signal.riskControls} tone="warning" />
      </div>
    </div>
  )
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

function EvidenceCompletenessPanel({ completeness }: { completeness: ShortTermCandidate['evidenceCompleteness'] }) {
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone={completeness.allowsBuy ? 'success' : completeness.status === 'PARTIAL' ? 'warning' : 'neutral'}>
            {completeness.statusLabel}
          </Tag>
          <span className="tabular text-xs font-semibold text-ink-500">完整度 {completeness.score}</span>
        </div>
        <span className="text-xs text-ink-400">{completeness.allowsBuy ? '允许加仓建议' : '加仓闸门关闭'}</span>
      </div>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <ListBlock title="已有证据" items={completeness.presentEvidence} tone="success" />
        <ListBlock title="缺失证据" items={completeness.missingEvidence} tone="warning" />
      </div>
    </div>
  )
}

function shortTermFactorContext(
  candidate: ShortTermCandidate,
  recommendationToken: string | null
): Omit<V2StrategyBundleParams, 'symbol' | 'companyName'> {
  const liquidityScore = liquidityScoreFromAmount(candidate.amount)
  const shrinkRiseScore = candidate.tailSignal.score ?? candidate.score.volumeScore
  return {
    industry: candidate.industry ?? '短线候选',
    valuationDiscountScore: candidate.score.valuationScore,
    qualityScore: candidate.score.financialScore,
    moatScore: candidate.score.financialScore,
    profitabilityScore: candidate.score.financialScore,
    cashFlowScore: candidate.financial.positiveCashFlowYears >= 2 ? 78 : 55,
    cyclePositionScore: candidate.technical.rangePosition120 ?? candidate.score.technicalScore,
    cycleRecoveryScore: candidate.score.technicalScore,
    industryLeaderScore: candidate.score.financialScore,
    policyCatalystScore: candidate.score.marketHeatScore,
    liquidityScore,
    hotDirection: candidate.industry ?? '热门方向优先',
    recommendationToken: recommendationToken ?? undefined,
    marketHotScore: candidate.score.marketHeatScore,
    rightSideStructureScore: candidate.score.technicalScore,
    supplyAbsorptionScore: candidate.score.volumeScore,
    volumeBreakoutScore: candidate.score.volumeScore,
    shrinkRiseScore,
    fundamentalFloorScore: candidate.score.financialScore,
    crowdingRiskScore: Math.max(0, Math.min(100, candidate.score.riskPenalty * 2.5)),
    ...goldenCrossV2Context(candidate.technical.goldenCross)
  }
}

function liquidityScoreFromAmount(amount: number | null | undefined) {
  if (amount === null || amount === undefined) return 60
  if (amount >= 2_000_000_000) return 90
  if (amount >= 800_000_000) return 78
  if (amount >= 300_000_000) return 66
  return 50
}

function adviceTone(action: string): 'success' | 'brand' | 'warning' | 'danger' | 'sky' | 'neutral' {
  if (action === 'ADD') return 'success'
  if (action === 'LIGHT_TRIAL') return 'brand'
  if (action === 'NEXT_WATCH') return 'sky'
  if (action === 'WAIT_PULLBACK') return 'warning'
  if (action === 'HOLD') return 'brand'
  if (action === 'BATCH_SELL') return 'warning'
  if (action === 'SELL_ALL') return 'danger'
  return 'neutral'
}

function tailTone(status: string): 'success' | 'brand' | 'warning' | 'danger' | 'sky' | 'neutral' {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'WATCH') return 'brand'
  if (status === 'NOT_READY' || status === 'PENDING') return 'sky'
  if (status === 'WEAK') return 'warning'
  if (status === 'UNAVAILABLE') return 'danger'
  return 'neutral'
}

function turnoverBandLabel(band: string | null | undefined) {
  if (band === 'PREFERRED') return '优选区间'
  if (band === 'OBSERVATION') return '观察区间'
  if (band === 'INSUFFICIENT') return '活跃不足'
  if (band === 'OVERHEATED') return '换手过热'
  return '待补充'
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

function Metric({ label, value, compact = false }: { label: string; value: ReactNode; compact?: boolean }) {
  return (
    <div className={`rounded-lg border border-line-soft bg-line-soft/40 ${compact ? 'px-2.5 py-2' : 'px-3 py-2'}`}>
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 break-words tabular text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function InlineMetric({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="min-w-[120px] rounded-xl border border-line-soft bg-white px-3 py-2">
      <div className="text-[11px] text-ink-400">{label}</div>
      <div className="mt-0.5 break-words tabular text-sm font-semibold text-ink-900">{value}</div>
    </div>
  )
}

function ScoreMetric({ label, value }: { label: string; value: number | null | undefined }) {
  const safeValue = value ?? 0
  const width = label.includes('风险') ? Math.max(0, Math.min(100, safeValue * 2.5)) : Math.max(0, Math.min(100, safeValue))
  return (
    <div className="rounded-lg border border-line-soft px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-ink-400">{label}</span>
        <span className="tabular text-xs font-semibold text-brand-600">{formatNumber(value)}</span>
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-line-soft">
        <div className="h-full rounded-full bg-brand-500" style={{ width: `${width}%` }} />
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
        {items.slice(0, 5).map((item) => <li key={item}>{item}</li>)}
      </ul>
    </div>
  )
}

function formatPrice(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${Number(value).toFixed(2)}`
}
