import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { CandlestickChart, Check, RefreshCw, SlidersHorizontal } from 'lucide-react'
import type { ShortTermParams } from '../api/client'
import { fetchShortTermValidationSummaries } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DetailOverlay, resolveDetailSelection } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { DailyShortTermPanel } from '../components/shortterm/DailyShortTermPanel'
import { ShortTermMarketConstraint, ShortTermMarketEnvironmentPanel } from '../components/shortterm/ShortTermMarketEnvironmentPanel'
import { IndustryLeaderOverlay, type IndustryLeaderSelection } from '../components/shortterm/IndustryLeaderOverlay'
import { MarketFundDirectionCard } from '../components/shortterm/MarketFundDirectionCard'
import { isMorningOvernightPlan, OvernightTradePlanPanel } from '../components/shortterm/OvernightTradePlanPanel'
import { CompositeScoreBadge, MomentumQualityTags, RightSideSignalTag } from '../components/shortterm/ShortTermCandidateIndicators'
import {
  hasClosedShortTermScoreSnapshot,
  ShortTermSignalEvidencePanel
} from '../components/shortterm/ShortTermSignalEvidencePanel'
import type { ShortTermValidationViewState } from '../components/shortterm/ShortTermSignalEvidencePanel'
import { CandidateExecutionTag, ResearchScoreTag, researchSetupFilters, ShortTermResearchPanel } from '../components/shortterm/ShortTermResearchPanel'
import type { ResearchSetupFilter } from '../components/shortterm/ShortTermResearchPanel'
import { WatchButton } from '../components/watchlist/WatchButton'
import { V2StrategyBundlePanel } from '../components/recommendation/V2StrategyBundlePanel'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatPercent, formatPerSharePrice, formatRatioPercent, formatSignedPercent } from '../lib/format'
import { goldenCrossAlignmentLabel, goldenCrossCounterEvidence, goldenCrossCounterEvidenceTone, goldenCrossDisplayLabel, goldenCrossSpreadLabel, goldenCrossSpreadTrendLabel, goldenCrossTone, goldenCrossV2Context } from '../lib/shortTermGoldenCross'
import { formatThreeDayVolumeComparison } from '../lib/shortTermVolume'
import { loadShortTermViewPreferences, saveShortTermViewPreferences } from '../lib/shortTermViewPreferences'
import type { ShortTermViewPreferences } from '../lib/shortTermViewPreferences'
import { useShortTermScanStore } from '../store/shortTermScanStore'
import type { ShortTermCandidate, ShortTermGoldenCrossSnapshot, ShortTermHotDirection, ShortTermReport, ShortTermSupportReversalSignal, ShortTermTailSignal, ShortTermValidationBatchRequest, ShortTermValidationSummary, ShortTermWeightProfile, TradingAdvice, V2StrategyBundleParams } from '../types'

interface DraftParams {
  limit: number
  scanLimit: number
  klineLimit: number
  minAmountYi: number
  maxPricePerShare: number
  minVolumeRatio: number
  maxEntryRise: number
  maxDistanceToMa20: number
  minFinancialScore: number
  allowStaticCachePreview: boolean
  allowChiNext: boolean
}

const DEFAULT_DRAFT: DraftParams = {
  limit: 0,
  scanLimit: 0,
  klineLimit: 0,
  minAmountYi: 0.8,
  maxPricePerShare: 100,
  minVolumeRatio: 1.2,
  maxEntryRise: 6.5,
  maxDistanceToMa20: 8,
  minFinancialScore: 55,
  allowStaticCachePreview: false,
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
  const [thresholdsOpen, setThresholdsOpen] = useState(false)
  const [viewPreferences, setViewPreferences] = useState<ShortTermViewPreferences>(() => loadShortTermViewPreferences())
  const report = useShortTermScanStore((state) => state.report)
  const resultMetadata = useShortTermScanStore((state) => state.resultMetadata)
  const loading = useShortTermScanStore((state) => state.loading)
  const error = useShortTermScanStore((state) => state.error)
  const scanMessage = useShortTermScanStore((state) => state.scanMessage)
  const runManualScan = useShortTermScanStore((state) => state.runManualScan)
  const [selection, setSelection] = useState<string | null>(null)
  const [setupFilter, setSetupFilter] = useState<ResearchSetupFilter>('ALL')
  const [leaderSelection, setLeaderSelection] = useState<IndustryLeaderSelection | null>(null)
  const [validationSummaries, setValidationSummaries] = useState<ShortTermValidationSummary[]>([])
  const [validationState, setValidationState] = useState<ShortTermValidationViewState>('IDLE')
  const validationRequests = useRef(new Map<string, Promise<ShortTermValidationSummary[]>>())

  const hasLoadedResults = Boolean(resultMetadata || report)
  const rightCandidates = report?.candidates ?? []
  const hasResearch = rightCandidates.some((candidate) => candidate.signalProfile?.research)
  const hasUnclassified = rightCandidates.some((candidate) => !candidate.signalProfile?.research)
  const visibleRightCandidates = rightCandidates.filter((candidate) => {
    const research = candidate.signalProfile?.research
    return setupFilter === 'ALL' || !research || research.setupType === setupFilter
  })
  const selected = resolveDetailSelection(rightCandidates, selection, candidate => candidate.symbol)

  useEffect(() => {
    setSetupFilter('ALL')
  }, [report])

  useEffect(() => {
    if (selection && !selected) setSelection(null)
  }, [selection, selected])

  useEffect(() => {
    saveShortTermViewPreferences(viewPreferences)
  }, [viewPreferences])

  useEffect(() => {
    const request = validationRequest(report)
    if (!request.cohorts.length) {
      setValidationSummaries([])
      setValidationState('IDLE')
      return
    }
    let cancelled = false
    setValidationState('LOADING')
    const requestKey = JSON.stringify(request.cohorts)
    let validationPromise = validationRequests.current.get(requestKey)
    if (!validationPromise) {
      validationPromise = fetchShortTermValidationSummaries(request)
      validationRequests.current.set(requestKey, validationPromise)
    }
    void validationPromise
      .then((summaries) => {
        if (cancelled) return
        setValidationSummaries(summaries)
        setValidationState('READY')
      })
      .catch(() => {
        if (cancelled) return
        setValidationSummaries([])
        setValidationState('FAILED')
      })
      .finally(() => {
        if (validationRequests.current.get(requestKey) === validationPromise) {
          validationRequests.current.delete(requestKey)
        }
      })
    return () => {
      cancelled = true
    }
  }, [report])

  function updateViewPreference(key: keyof ShortTermViewPreferences, checked: boolean) {
    setViewPreferences((current) => ({ ...current, [key]: checked }))
  }

  return (
    <div className="flex flex-col gap-4">
      <ResultViewControls
        preferences={viewPreferences}
        onChange={updateViewPreference}
        hasReport={Boolean(report)}
        loading={loading}
        scanMessage={scanMessage}
        onScan={() => {
          setSelection(null)
          void runManualScan(toApiParams({ ...draft }))
        }}
        onOpenThresholds={() => setThresholdsOpen(true)}
      />

      <DetailOverlay
        open={thresholdsOpen}
        title="右侧启动阈值"
        onClose={() => setThresholdsOpen(false)}
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
          <NumberField label="候选数量（0=全部）" value={draft.limit} min={0} max={12} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="扫描数量（0=全部）" value={draft.scanLimit} min={0} max={6000} step={100} onChange={(value) => setDraft({ ...draft, scanLimit: value })} />
          <NumberField label="K线复核数（0=全部）" value={draft.klineLimit} min={0} max={160} step={10} onChange={(value) => setDraft({ ...draft, klineLimit: value })} />
          <NumberField label="成交额下限(亿)" value={draft.minAmountYi} min={0.8} max={30} step={0.05} onChange={(value) => setDraft({ ...draft, minAmountYi: value })} />
          <NumberField label="每股价格上限(元)" value={draft.maxPricePerShare} min={1} max={2000} step={1} onChange={(value) => setDraft({ ...draft, maxPricePerShare: value })} />
          <NumberField label="量比下限" value={draft.minVolumeRatio} min={1} max={3.2} step={0.05} onChange={(value) => setDraft({ ...draft, minVolumeRatio: value })} />
          <NumberField label="追涨上限%" value={draft.maxEntryRise} min={1} max={10} step={0.1} onChange={(value) => setDraft({ ...draft, maxEntryRise: value })} />
          <NumberField label="距20日线%" value={draft.maxDistanceToMa20} min={2} max={20} step={0.5} onChange={(value) => setDraft({ ...draft, maxDistanceToMa20: value })} />
          <NumberField label="财报风险提示线" value={draft.minFinancialScore} min={30} max={90} onChange={(value) => setDraft({ ...draft, minFinancialScore: value })} />
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
            默认完整复核并展示全部；这些参数用于手动扫描，定时任务使用默认参数。
            {' '}
            低流动性、长期横盘、急拉和离均线过远仍受约束。
          </p>
          <Button
            variant="primary"
            disabled={loading}
            onClick={() => {
              setThresholdsOpen(false)
            }}
          >
            保存阈值
          </Button>
        </div>
      </DetailOverlay>

      {error && <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card>}
      <div
        className="grid auto-rows-[420px] grid-cols-1 gap-4 md:grid-cols-3 [&>.card]:min-h-0 [&>.card]:min-w-0 [&>.card]:overflow-y-auto [&>.card]:overscroll-contain [&>.card]:[scrollbar-gutter:stable]"
        data-testid="short-term-horizontal-summary"
        aria-busy={loading}
      >
        {viewPreferences.marketSentimentVisible ? (
          report ? <ShortTermMarketEnvironmentPanel report={report} /> : <SummaryPlaceholder title="扫描时市场情绪" loading={loading} />
        ) : null}
        {viewPreferences.hotDirectionsVisible ? (
          report ? <HotDirectionsCard directions={report.hotDirections} /> : <SummaryPlaceholder title="热门方向" loading={loading} />
        ) : null}
        {viewPreferences.fundFlowVisible ? (
          report ? <MarketFundDirectionCard
            direction={report.marketFundDirection}
            onSelectIndustry={(item, flow) => setLeaderSelection({
              code: item.code,
              name: item.name || item.code || '未知板块',
              flow
            })}
          /> : <SummaryPlaceholder title="今日资金去向" loading={loading} />
        ) : null}
      </div>

      <div hidden>
        <Card flush>
          <DailyShortTermPanel />
        </Card>
      </div>

      <div hidden={!hasLoadedResults}>
        <Card className="min-w-0" title={<span className="inline-flex items-center gap-2"><CandlestickChart className="h-4 w-4 text-brand-500" />右侧启动</span>}
          extra={<span className="text-xs text-ink-500">{visibleRightCandidates.length === rightCandidates.length ? rightCandidates.length : `${visibleRightCandidates.length} / ${rightCandidates.length}`} 只</span>} flush>
          {!viewPreferences.marketSentimentVisible && report?.marketRegime?.maxAction === 'NO_TRADE' ? (
            <div className="px-5 py-3"><ShortTermMarketConstraint regime={report.marketRegime} /></div>
          ) : null}
          {hasResearch ? (
            <div className="border-b border-line-soft px-5 py-3">
              <div aria-label="研究买点筛选" className="flex flex-wrap gap-1.5">
                {researchSetupFilters.map((filter) => (
                  <button
                    key={filter.value}
                    type="button"
                    aria-pressed={setupFilter === filter.value}
                    onClick={() => setSetupFilter(filter.value)}
                    className={`rounded-full border px-3 py-1 text-xs transition ${setupFilter === filter.value ? 'border-brand-200 bg-brand-50 font-semibold text-brand-700' : 'border-line-soft bg-white text-ink-500 hover:bg-line-soft/40'}`}
                  >
                    {filter.label}
                  </button>
                ))}
              </div>
              {setupFilter !== 'ALL' && hasUnclassified ? <p className="mt-2 text-xs text-ink-400">历史及未分类右侧候选继续保留展示。</p> : null}
            </div>
          ) : null}
          <div aria-label="右侧启动候选列表" className="divide-y divide-line-soft">
            {visibleRightCandidates.length ? visibleRightCandidates.map((candidate) => (
              <CandidateRow
                key={candidate.symbol}
                candidate={candidate}
                industry={candidate.industry || ''}
                selected={selected?.symbol === candidate.symbol}
                onSelect={() => setSelection(candidate.symbol)}
                marketRegimeLabel={report?.marketRegime?.label}
              />
            )) : (
              <p className="p-5 text-sm text-ink-500">{setupFilter !== 'ALL' ? '当前形态暂无右侧启动候选'
                : report ? '暂无右侧启动候选'
                  : resultMetadata?.status === 'PARTIAL' ? '本次未发布右侧候选，扫描数据未完整就绪。'
                    : resultMetadata ? '暂无右侧启动候选' : '尚无已加载的右侧启动结果'}</p>
            )}
          </div>
        </Card>

      </div>

      <DetailOverlay
        open={selected !== null}
        title={selected ? `${selected.name} ${selected.symbol}` : '短线候选详情'}
        subtitle={selected ? `${selected.market ?? 'A股'} · ${selected.industry || '行业待补'} · 排名 #${selected.rank}` : undefined}
        onClose={() => setSelection(null)}
      >
        <div className="flex flex-col gap-5">
          {selected && report ? (
            <CandidateDetail
              candidate={selected}
              weightProfile={report.weightProfile}
              tradeCaptureToken={report.tradeCaptureTokens?.[selected.symbol] ?? null}
              marketRegime={report.marketRegime}
              validationSummaries={validationSummaries}
              validationState={validationState}
            />
          ) : null}
        </div>
      </DetailOverlay>

      <IndustryLeaderOverlay
        selection={leaderSelection}
        onClose={() => setLeaderSelection(null)}
      />
    </div>
  )
}

const resultViewOptions: Array<{
  key: keyof ShortTermViewPreferences
  label: string
  description: string
}> = [
  { key: 'marketSentimentVisible', label: '市场情绪', description: '扫描时宽度' },
  { key: 'fundFlowVisible', label: '今日资金去向', description: '扫描时行业流向' },
  { key: 'hotDirectionsVisible', label: '热门方向', description: '扫描时热度' }
]

function ResultViewControls({
  preferences,
  onChange,
  hasReport,
  loading,
  scanMessage,
  onScan,
  onOpenThresholds
}: {
  preferences: ShortTermViewPreferences
  onChange: (key: keyof ShortTermViewPreferences, checked: boolean) => void
  hasReport: boolean
  loading: boolean
  scanMessage: string
  onScan: () => void
  onOpenThresholds: () => void
}) {
  const visibleCount = resultViewOptions.filter((option) => preferences[option.key]).length
  return (
    <Card flush className="border-line bg-white p-3 shadow-none sm:px-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-semibold text-ink-900">结果视图</h3>
            <Button
              variant="ghost"
              className="h-8 w-8 !p-0"
              aria-label="右侧启动阈值"
              title="右侧启动阈值"
              aria-haspopup="dialog"
              onClick={onOpenThresholds}
              icon={<SlidersHorizontal className="h-4 w-4" />}
            />
          </div>
          <p className="mt-1 text-[11px] leading-relaxed text-ink-400">
            {hasReport ? '已展示' : '已选择'} {visibleCount}/{resultViewOptions.length} 个模块，勾选状态会在本机浏览器记住。
          </p>
          {!hasReport ? <p aria-label="扫描状态" className="mt-1 text-[11px] leading-relaxed text-ink-500">
            {loading ? scanMessage || '扫描中，请稍候。' : '暂无扫描结果，请点击重新扫描。'}
          </p> : null}
        </div>
        <div className="grid w-full grid-cols-1 gap-2 min-[380px]:grid-cols-2 sm:flex sm:flex-wrap sm:items-center lg:w-auto" aria-label="结果视图与扫描操作">
          {resultViewOptions.map((option) => (
            <label
              key={option.key}
              title={option.description}
              className="relative min-w-0 cursor-pointer"
            >
              <input
                type="checkbox"
                className="peer sr-only"
                checked={preferences[option.key]}
                onChange={(event) => onChange(option.key, event.target.checked)}
                aria-label={`展示${option.label}`}
              />
              <span className="flex h-9 items-center justify-center gap-1.5 whitespace-nowrap rounded-md border border-line bg-white px-2.5 text-xs font-medium text-ink-500 transition-colors hover:border-brand-200 hover:bg-brand-50/50 peer-checked:border-brand-200 peer-checked:bg-brand-50 peer-checked:text-brand-700 peer-focus-visible:outline-none peer-focus-visible:ring-2 peer-focus-visible:ring-brand-300 peer-focus-visible:ring-offset-2">
                <Check aria-hidden="true" className={`h-3.5 w-3.5 shrink-0 ${preferences[option.key] ? 'opacity-100' : 'opacity-0'}`} />
                {option.label}
              </span>
            </label>
          ))}
          <Button
            variant="primary"
            className="h-9 shrink-0 whitespace-nowrap !rounded-md !px-3 !py-0 !text-xs !shadow-none sm:ml-1"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={loading}
            onClick={onScan}
          >
            重新扫描
          </Button>
        </div>
      </div>
    </Card>
  )
}

function SummaryPlaceholder({ title, loading }: { title: string; loading: boolean }) {
  return (
    <Card className="[&>div]:flex [&>div]:h-full [&>div]:flex-col">
      <h3 className="text-base font-semibold text-ink-900">{title}</h3>
      <div className="flex flex-1 items-center justify-center">
        {loading ? <Loader text="扫描中" /> : <p className="text-sm text-ink-400">暂无扫描结果</p>}
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
          <div className="grid grid-cols-1 gap-2">
            {directions.map((direction) => (
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
                  <p className="mt-1 text-xs text-ink-400">领涨：{direction.leaders.join('、')}</p>
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
    maxPricePerShare: params.maxPricePerShare,
    minVolumeRatio: params.minVolumeRatio,
    maxEntryRise: params.maxEntryRise,
    maxDistanceToMa20: params.maxDistanceToMa20,
    minFinancialScore: params.minFinancialScore,
    allowStaticCachePreview: params.allowStaticCachePreview,
    allowChiNext: params.allowChiNext
  }
}

function adviceForOvernightPlan(candidate: ShortTermCandidate): TradingAdvice {
  if ((candidate.todayAdvice.action === 'ADD' || candidate.todayAdvice.action === 'LIGHT_TRIAL')
    && (!isMorningOvernightPlan(candidate.tradePlan)
      || (candidate.signalProfile?.research?.setupType === 'TREND_PULLBACK'
        && candidate.tailSignal?.intradayPullback?.status !== 'CONFIRMED'))) {
    return { ...candidate.todayAdvice, action: 'WAIT', actionLabel: '待重新扫描',
      summary: '当前结果缺少新的隔夜退出计划或分时回踩证据，请重新扫描。',
      reasons: ['旧规则买入建议不能替代当前规则确认'], riskControls: [] }
  }
  return candidate.todayAdvice
}

function CandidateRow({
  candidate,
  industry,
  selected,
  onSelect,
  marketRegimeLabel
}: {
  candidate: ShortTermCandidate
  industry: string
  selected: boolean
  onSelect: () => void
  marketRegimeLabel: string | undefined
}) {
  const goldenCross = candidate.technical.goldenCross
  const supportReversal = candidate.technical.supportReversal
  const scoreSnapshotClosed = hasClosedShortTermScoreSnapshot(candidate)
  const rankingScore = scoreSnapshotClosed ? candidate.score.rankingScore : null
  const research = candidate.signalProfile?.research
  const advice = adviceForOvernightPlan(candidate)
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`grid w-full grid-cols-1 gap-3 px-5 py-4 text-left transition hover:bg-brand-50/70 ${selected ? 'bg-brand-50' : 'bg-white'}`}
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className="tabular text-xs font-semibold text-ink-400">#{candidate.rank}</span>
          <h3 className="truncate text-base font-semibold text-ink-900">{candidate.name}</h3>
          <span className="font-mono text-xs text-ink-400">{candidate.symbol}</span>
          <Tag tone="brand">右侧候选</Tag>
          <Tag tone="sky">{candidate.phaseLabel}</Tag>
          {supportReversal?.state === 'CONFIRMED' ? (
            <Tag tone="success">{supportReversal.stateLabel}</Tag>
          ) : null}
          {research ? <Tag tone="brand">{research.setupLabel}</Tag> : candidate.signalProfile && candidate.signalProfile.primaryFamily !== 'UNAVAILABLE' ? (
            <Tag tone="brand">{candidate.signalProfile.primaryLabel}</Tag>
          ) : null}
        </div>
        <p className="mt-1 text-xs text-ink-500">行业：{industry || '行业待补'}</p>
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
          {marketRegimeLabel ? <Tag tone="sky">大盘：{marketRegimeLabel}</Tag> : null}
          {!research && (Number.isFinite(candidate.score.technicalRankingScore) ? (
            <Tag tone="neutral">结构分 {formatNumber(candidate.score.technicalRankingScore)}</Tag>
          ) : (
            <Tag tone="warning">结构分待补</Tag>
          ))}
          {!research && (rankingScore === null ? (
            <Tag tone="warning">排序分待补</Tag>
          ) : (
            <Tag tone="neutral">排序分 {formatNumber(rankingScore)}</Tag>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
        <Metric label="每股" value={formatPerSharePrice(candidate.latestPrice)} compact />
        <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} compact />
        <Metric label="距20日" value={`${formatNumber(candidate.technical.distanceToMa20Percent)}%`} compact />
        <Metric label="突破20高" value={`${formatNumber(candidate.technical.breakoutFromPreviousHigh20Percent)}%`} compact />
        <Metric label="今日量 / 前3日均" value={formatThreeDayVolumeComparison(candidate.technical)} compact />
          <Metric label="20日量比" value={formatNumber(candidate.technical.volumeRatio20)} compact />
          <Metric label="换手率" value={`${formatNumber(candidate.technical.momentumQuality?.turnoverRatePercent)}%`} compact />
      </div>

      <div className="flex flex-wrap items-center gap-3">
        {research ? <ResearchScoreTag research={research} /> : <CompositeScoreBadge value={rankingScore} />}
        <CandidateExecutionTag candidate={candidate} />
        {!research ? <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag> : null}
        <Tag tone={adviceTone(advice.action)}>建议：{advice.actionLabel}</Tag>
        <Tag tone={tailTone(candidate.tailSignal.status)}>{candidate.tailSignal.statusLabel}</Tag>
      </div>
    </button>
  )
}

function legacyWeightLabel(label: string, weight: number | null | undefined) {
  return weight != null && Number.isFinite(weight) ? `${label} ${Number((weight * 100).toFixed(2))}%` : label
}

function CandidateDetail({
  candidate,
  weightProfile,
  tradeCaptureToken,
  marketRegime,
  validationSummaries,
  validationState
}: {
  candidate: ShortTermCandidate
  weightProfile: ShortTermWeightProfile
  tradeCaptureToken: string | null
  marketRegime: ShortTermReport['marketRegime']
  validationSummaries: ShortTermValidationSummary[]
  validationState: ShortTermValidationViewState
}) {
  const scoreSnapshotClosed = hasClosedShortTermScoreSnapshot(candidate)
  const rankingScore = scoreSnapshotClosed ? candidate.score.rankingScore : null
  const research = candidate.signalProfile?.research
  const advice = adviceForOvernightPlan(candidate)
  return (
    <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-soft pb-4">
          <div className="flex flex-wrap items-center gap-2">
            {research ? <ResearchScoreTag research={research} /> : <ScoreBadge value={rankingScore} />}
            <CandidateExecutionTag candidate={candidate} />
            <Tag tone={adviceTone(advice.action)}>建议：{advice.actionLabel}</Tag>
            {!research ? <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag> : null}
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <WatchButton symbol={candidate.symbol} />
          </div>
        </div>

        <ShortTermMarketConstraint regime={marketRegime} />

        {research ? <ShortTermResearchPanel research={research} /> : null}

        <OvernightTradePlanPanel plan={candidate.tradePlan} />

        <p className="text-sm leading-relaxed text-ink-600">{candidate.reason}</p>

        <ShortTermSignalEvidencePanel
          candidate={candidate}
          marketRegime={marketRegime}
          summaries={validationSummaries}
          validationState={validationState}
        />

        <div className="grid grid-cols-2 gap-2">
          <Metric label="每股价格" value={formatPerSharePrice(candidate.latestPrice)} />
          <Metric label="涨跌幅" value={<span className={changeClass(candidate.changePercent)}>{formatSignedPercent(candidate.changePercent)}</span>} />
          <Metric label="PE TTM" value={formatNumber(candidate.peTtm)} />
          <Metric label="PB" value={formatNumber(candidate.pbRatio)} />
          <Metric label="买入观察区" value={`${formatPrice(candidate.buyZoneLow)} - ${formatPrice(candidate.buyZoneHigh)}`} />
          <Metric label="止损参考" value={formatPrice(candidate.stopPrice)} />
          <Metric label="尾盘状态" value={candidate.tailSignal.statusLabel} />
          <Metric label="最新分时" value={candidate.tailSignal.latestMinute ?? '待补充'} />
          <Metric label="行情时点" value={candidate.quoteFreshness.statusLabel} />
          <Metric label="市场时间" value={formatDateTime(candidate.quoteFreshness.marketTimestamp)} />
          <Metric label="证据完整度" value={`${candidate.evidenceCompleteness.score}`} />
        </div>

        <details aria-label="旧版评分对照" open={!research} className="rounded-lg border border-line-soft p-3">
          <summary className="cursor-pointer text-sm font-semibold text-ink-700">旧版评分对照</summary>
          <p className="mt-2 text-xs leading-relaxed text-ink-500">保留报告原有评分及权重，独立于研究分展示。</p>
        <div className="mt-3 grid grid-cols-2 gap-2">
          <ScoreMetric label={legacyWeightLabel(weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型技术' : '金叉', weightProfile.finalGoldenCross)} value={candidate.score.goldenCrossScore} />
          <ScoreMetric label={legacyWeightLabel('量能', weightProfile.finalVolume)} value={candidate.score.volumeScore} />
          <ScoreMetric label={legacyWeightLabel(weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型热度' : '换手', weightProfile.finalTurnover)} value={candidate.score.turnoverScore} />
          <ScoreMetric label={legacyWeightLabel(weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型财务与估值' : '收盘强度', weightProfile.finalCloseStrength)} value={candidate.score.closeStrengthScore} />
          {candidate.score.supportReversalScore != null
          && (candidate.technical.supportReversal?.state === 'CONFIRMED'
            || candidate.technical.supportReversal?.state === 'OBSERVATION') ? (
            <ScoreMetric label="承接反转分" value={candidate.score.supportReversalScore} />
          ) : null}
          <ScoreMetric label="四因子原始分" value={candidate.score.finalScore} />
          {scoreSnapshotClosed ? (
            <>
              <ScoreMetric
                label="阶段校准"
                value={(candidate.score.technicalRankingScore as number) - candidate.score.finalScore}
              />
              {candidate.score.fundFlowAdjustment != null ? (
                <ScoreMetric label="资金流微调（最多±2）" value={candidate.score.fundFlowAdjustment} />
              ) : null}
              {candidate.score.marketHeatContribution != null ? (
                <ScoreMetric label="热点方向修正（最多±2）" value={candidate.score.marketHeatContribution} />
              ) : null}
              {candidate.score.relativeStrengthContribution != null ? (
                <ScoreMetric label="相对强度修正（最多±4）" value={candidate.score.relativeStrengthContribution} />
              ) : null}
              {candidate.score.industryLeadershipContribution != null ? (
                <ScoreMetric label="行业成交额地位修正（最多±2）" value={candidate.score.industryLeadershipContribution} />
              ) : null}
              {candidate.score.crossSectionAdjustment != null ? (
                <ScoreMetric label="横截面合计（最多±8）" value={candidate.score.crossSectionAdjustment} />
              ) : null}
              <ScoreMetric label="排序分" value={rankingScore as number} />
            </>
          ) : (
            <div className="col-span-2 border border-amber-200 bg-amber-50/60 px-3 py-2 text-xs leading-relaxed text-amber-800">
              历史报告缺少完整排序快照，不展示推算后的阶段项、贡献项或排序分。
            </div>
          )}
        </div>

        </details>

        <SupportReversalDetail signal={candidate.technical.supportReversal} />

        <div className="grid grid-cols-2 gap-2">
          <Metric label="MA5/10/20" value={`${formatNumber(candidate.technical.ma5)} / ${formatNumber(candidate.technical.ma10)} / ${formatNumber(candidate.technical.ma20)}`} />
          <Metric label="20/60日线斜率" value={`${formatNumber(candidate.technical.ma20SlopePercent)}% / ${formatNumber(candidate.technical.ma60SlopePercent)}%`} />
          <Metric label="突破20日高点" value={`${formatNumber(candidate.technical.breakoutFromPreviousHigh20Percent)}%`} />
          <Metric label="60/120日位置" value={`${formatNumber(candidate.technical.rangePosition60)}% / ${formatNumber(candidate.technical.rangePosition120)}%`} />
          <Metric label="120日高点回撤" value={`${formatNumber(candidate.technical.drawdownFrom120HighPercent)}%`} />
          <Metric label="站上20日线天数" value={candidate.technical.consecutiveAboveMa20Days} />
          <Metric
            label="5/10/20日涨幅"
            value={candidate.relativeStrength
              ? `${formatSignedPercent(candidate.relativeStrength.return5)} / ${formatSignedPercent(candidate.relativeStrength.return10)} / ${formatSignedPercent(candidate.relativeStrength.return20)}`
              : '待补充'}
          />
          <Metric
            label="市场5/10/20分位"
            value={candidate.relativeStrength
              ? `${formatNumber(candidate.relativeStrength.marketPercentile5)} / ${formatNumber(candidate.relativeStrength.marketPercentile10)} / ${formatNumber(candidate.relativeStrength.marketPercentile20)}`
              : '待补充'}
          />
          <Metric
            label="同行5/10/20分位"
            value={candidate.relativeStrength
              ? `${formatNumber(candidate.relativeStrength.industryPercentile5)} / ${formatNumber(candidate.relativeStrength.industryPercentile10)} / ${formatNumber(candidate.relativeStrength.industryPercentile20)}`
              : '待补充'}
          />
          <Metric
            label="行业成交额地位"
            value={candidate.industryLeadership && candidate.industryLeadership.amountRank > 0
              ? `${candidate.industryLeadership.amountRank}/${candidate.industryLeadership.cohortSize} · 分位 ${formatNumber(candidate.industryLeadership.percentile)}`
              : '同行样本不足'}
          />
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
          <ScoreMetric label="财报语境" value={candidate.score.financialScore} />
          <ScoreMetric label="风险提示分（不计主分）" value={candidate.score.riskPenalty} />
        </div>

        <GoldenCrossDetail snapshot={candidate.technical.goldenCross} />

        <TailSignalPanel signal={candidate.tailSignal} />

        <TodayAdvicePanel advice={advice} />
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
        <Metric label="尾盘起始价" value={formatPerSharePrice(signal.tailStartPrice)} compact />
        <Metric label="14:45—14:49涨跌" value={<span className={changeClass(signal.changeFromActionableTailPercent)}>{formatSignedPercent(signal.changeFromActionableTailPercent)}</span>} compact />
        <Metric label="高点回落" value={formatPercent(signal.drawdownFromTailHighPercent)} compact />
        <Metric label="相对均价线" value={<span className={changeClass(signal.closeVsAveragePricePercent)}>{formatSignedPercent(signal.closeVsAveragePricePercent)}</span>} compact />
        <Metric label="尾盘成交占比" value={formatPercent(signal.tailAmountRatioPercent)} compact />
        <Metric label="尾盘成交额" value={formatAmount(signal.tailAmount)} compact />
        <Metric label="尾盘分" value={formatNumber(signal.score)} compact />
      </div>
      {signal.intradayPullback ? (
        <div aria-label="分时回踩证据" className="mt-3 border-t border-line-soft pt-3">
          <div className="flex items-center gap-2 text-xs font-semibold">
            <span>下午10分钟回踩</span>
            <Tag tone={signal.intradayPullback.status === 'CONFIRMED' ? 'success' : 'warning'}>{signal.intradayPullback.statusLabel}</Tag>
          </div>
          <div className="mt-2 grid grid-cols-2 gap-2">
            <Metric label="回踩K线结束" value={signal.intradayPullback.pullbackMinute?.slice(11, 16) ?? '待确认'} compact />
            <Metric label="突破K线结束" value={signal.intradayPullback.breakoutMinute?.slice(11, 16) ?? '待确认'} compact />
            <Metric label="平台上沿" value={formatPerSharePrice(signal.intradayPullback.platformHigh)} compact />
            <Metric label="回踩低点" value={formatPerSharePrice(signal.intradayPullback.pullbackLow)} compact />
            <Metric label="回踩量/平台均量" value={signal.intradayPullback.pullbackVolumeRatio == null ? '待确认' : `${signal.intradayPullback.pullbackVolumeRatio.toFixed(2)}×`} compact />
            <Metric label="突破量/前3根均量" value={signal.intradayPullback.breakoutVolumeRatio == null ? '待确认' : `${signal.intradayPullback.breakoutVolumeRatio.toFixed(2)}×`} compact />
          </div>
          {signal.intradayPullback.dataGaps.length ? <p className="mt-2 text-xs text-amber-700">{signal.intradayPullback.dataGaps.join('；')}</p> : null}
        </div>
      ) : null}
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
    // 短线策略已移除估值分，此处传 V2 信号服务自身的默认中性值 62
    valuationDiscountScore: 62,
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

function validationRequest(report: ShortTermReport | null): ShortTermValidationBatchRequest {
  const marketRegime = report?.marketRegime?.state
  if (!report || !marketRegime || marketRegime === 'UNAVAILABLE') {
    return { cohorts: [] }
  }
  const families = [...new Set(report.candidates
    .map((candidate) => candidate.signalProfile?.primaryFamily)
    .filter((family): family is string => Boolean(family && family !== 'UNAVAILABLE')))]
  return {
    cohorts: families.flatMap((signalFamily) => ([
      { signalFamily, marketRegime, horizon: 'T1' as const },
      { signalFamily, marketRegime, horizon: 'T2' as const }
    ]))
  }
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
  const [draft, setDraft] = useState(String(value))
  const [focused, setFocused] = useState(false)

  // 未聚焦时跟随外部值，聚焦编辑期间不打断用户（清空、逐位输入都允许）
  useEffect(() => {
    if (!focused) setDraft(String(value))
  }, [value, focused])

  const commit = (raw: string) => {
    if (raw.trim() === '') return
    const parsed = Number(raw)
    if (Number.isFinite(parsed)) {
      onChange(parsed)
    }
  }

  const settle = () => {
    setFocused(false)
    const parsed = Number(draft)
    if (draft.trim() === '' || !Number.isFinite(parsed)) {
      setDraft(String(value))
      return
    }
    const clamped = Math.min(max, Math.max(min, parsed))
    onChange(clamped)
    setDraft(String(clamped))
  }

  return (
    <label className="group flex flex-col gap-1 rounded-xl border border-line bg-line-soft/40 px-3.5 py-2.5 transition hover:border-brand-300 hover:bg-white focus-within:border-brand-400 focus-within:bg-white focus-within:ring-2 focus-within:ring-brand-100">
      <span className="text-xs font-medium text-ink-500 group-focus-within:text-brand-600">{label}</span>
      <input
        className="w-full border-0 bg-transparent p-0 text-base font-semibold tabular text-ink-900 outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
        type="number"
        min={min}
        max={max}
        step={step ?? 1}
        value={draft}
        onFocus={() => setFocused(true)}
        onBlur={settle}
        onChange={(e) => {
          setDraft(e.target.value)
          commit(e.target.value)
        }}
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
