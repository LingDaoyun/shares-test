import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { CandlestickChart, RefreshCw, SlidersHorizontal } from 'lucide-react'
import { fetchLatestShortTermScheduledSnapshot, fetchOvernightBacktest, fetchShortTermScanJob, startShortTermScanJob } from '../api/client'
import type { ShortTermParams } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DetailOverlay, resolveDetailSelection } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { OvernightTradePlanPanel } from '../components/shortterm/OvernightTradePlanPanel'
import { ScheduledSnapshotStatus } from '../components/shortterm/ScheduledSnapshotStatus'
import type { ReportOrigin } from '../components/shortterm/ScheduledSnapshotStatus'
import { CompositeScoreBadge, MomentumQualityTags, RightSideSignalTag } from '../components/shortterm/ShortTermCandidateIndicators'
import { TradeReviewButton } from '../components/tradefeedback/TradeReviewButton'
import { WatchButton } from '../components/watchlist/WatchButton'
import { V2StrategyBundlePanel } from '../components/recommendation/V2StrategyBundlePanel'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPercent, formatPerSharePrice, formatRatioPercent, formatSignedPercent, formatValuationState } from '../lib/format'
import { goldenCrossAlignmentLabel, goldenCrossCounterEvidence, goldenCrossCounterEvidenceTone, goldenCrossDisplayLabel, goldenCrossSpreadLabel, goldenCrossSpreadTrendLabel, goldenCrossTone, goldenCrossV2Context } from '../lib/shortTermGoldenCross'
import type { ChipVerificationStatus, OvernightBacktestReport, OvernightBacktestSummary, ShortTermCandidate, ShortTermChipSnapshot, ShortTermGoldenCrossSnapshot, ShortTermHotDirection, ShortTermReport, ShortTermScanJobStatus, ShortTermScheduledSnapshot, ShortTermTailSignal, ShortTermWeightProfile, TradingAdvice, V2StrategyBundleParams } from '../types'

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
}

const DEFAULT_DRAFT: DraftParams = {
  limit: 8,
  scanLimit: 6000,
  klineLimit: 60,
  minAmountYi: 0.8,
  maxPe: 100,
  maxPb: 15,
  minVolumeRatio: 1.2,
  maxEntryRise: 4.5,
  maxDistanceToMa20: 8,
  minFinancialScore: 55,
  allowStaticCachePreview: true
}

const SCHEDULED_SCAN_POLL_MS = 10_000

const OVERNIGHT_DEFAULT_RULES = {
  lookbackDays: 900,
  firstTargetPercent: 2.5,
  secondTargetPercent: 4.5,
  hardStopPercent: 3.5,
  maxHoldingTradingDays: 2,
  commissionPercent: 0.03,
  stampDutyPercent: 0.05,
  slippagePercent: 0.05,
  limitMovePercent: 9.8,
  minVolumeRatio: 1.2,
  maxDistanceToMa20Percent: 8,
  trailingDrawdownPercent: 2
} as const

const actionTone: Record<string, 'success' | 'brand' | 'warning' | 'danger' | 'neutral' | 'sky'> = {
  RIGHT_EARLY_ADD: 'success',
  WATCH_RIGHT_SIDE: 'brand',
  WATCH_VALUE_RETURN: 'brand',
  WAIT_PULLBACK: 'warning',
  WAIT_CONFIRM: 'neutral',
  MARKET_RISK_WAIT: 'warning',
  DATA_REVIEW: 'neutral'
}

export function ShortTermPage() {
  const [draft, setDraft] = useState<DraftParams>(DEFAULT_DRAFT)
  const [snapshot, setSnapshot] = useState<ShortTermScheduledSnapshot | null>(null)
  const [scheduledSnapshot, setScheduledSnapshot] = useState<ShortTermScheduledSnapshot | null>(null)
  const [origin, setOrigin] = useState<ReportOrigin>('SCHEDULED')
  const [report, setReport] = useState<ShortTermReport | null>(null)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [backtestReport, setBacktestReport] = useState<OvernightBacktestReport | null>(null)
  const [backtestLoading, setBacktestLoading] = useState(false)
  const [backtestError, setBacktestError] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [scanMessage, setScanMessage] = useState('')
  const [activeJobId, setActiveJobId] = useState('')
  const manualRunGeneration = useRef(0)
  const backtestRequestGeneration = useRef(0)
  const pollTimer = useRef<number | undefined>(undefined)
  const preparedSnapshotRequest = useRef<ReturnType<typeof fetchLatestShortTermScheduledSnapshot> | null>(null)
  const manualScanRequested = useRef(false)
  const overnightSymbols = useMemo(
    () => [...new Set((report?.candidates ?? []).map((candidate) => candidate.symbol))].sort().join(','),
    [report?.candidates]
  )
  const overnightRules = useMemo(() => ({
    ...OVERNIGHT_DEFAULT_RULES,
    minVolumeRatio: report?.ruleSet?.minVolumeRatio ?? OVERNIGHT_DEFAULT_RULES.minVolumeRatio,
    maxDistanceToMa20Percent: report?.ruleSet?.maxDistanceToMa20Percent
      ?? OVERNIGHT_DEFAULT_RULES.maxDistanceToMa20Percent,
    trailingDrawdownPercent: report?.candidates
      .map((candidate) => candidate.tradePlan?.trailingDrawdownPercent)
      .find((value): value is number => value !== null && value !== undefined)
      ?? OVERNIGHT_DEFAULT_RULES.trailingDrawdownPercent
  }), [
    report?.ruleSet?.minVolumeRatio,
    report?.ruleSet?.maxDistanceToMa20Percent,
    report?.candidates
  ])
  const overnightRequestKey = `${overnightSymbols}|${Object.entries(overnightRules)
    .map(([key, value]) => `${key}:${value}`)
    .join('|')}`

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError('')
    setScanMessage('读取当日计划快照')
    const request = preparedSnapshotRequest.current ?? fetchLatestShortTermScheduledSnapshot()
    const requestGeneration = manualRunGeneration.current
    const ownsRequest = () => alive
      && manualRunGeneration.current === requestGeneration
      && !manualScanRequested.current
    preparedSnapshotRequest.current = request
    request
      .then((prepared) => {
        if (alive) setScheduledSnapshot(prepared)
        if (!ownsRequest()) return
        setSnapshot(prepared)
        setOrigin('SCHEDULED')
        setReport(visibleSnapshotReport(prepared))
      })
      .catch((e) => {
        if (ownsRequest()) setError(extractErrorMessage(e))
      })
      .finally(() => {
        if (ownsRequest()) setLoading(false)
      })
    return () => {
      alive = false
      manualRunGeneration.current += 1
      if (pollTimer.current !== undefined) window.clearTimeout(pollTimer.current)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    const refresh = async () => {
      try {
        const prepared = await fetchLatestShortTermScheduledSnapshot()
        if (cancelled) return
        setScheduledSnapshot(prepared)
        if (!manualScanRequested.current && origin === 'SCHEDULED') {
          setSnapshot(prepared)
          setReport(visibleSnapshotReport(prepared))
        }
      } catch {
        // Background polling should not replace the explicit page error.
      }
    }
    const interval = window.setInterval(() => void refresh(), SCHEDULED_SCAN_POLL_MS)
    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [origin])

  async function runManualScan(nextParams: DraftParams) {
    manualScanRequested.current = true
    const generation = manualRunGeneration.current + 1
    manualRunGeneration.current = generation
    if (pollTimer.current !== undefined) window.clearTimeout(pollTimer.current)

    setOrigin('MANUAL')
    setLoading(true)
    setError('')
    setReport(null)
    setSelectedSymbol(null)
    setScanMessage('提交实时扫描任务')
    setActiveJobId('')
    setSnapshot((current) => ({
      tradeDate: current?.tradeDate ?? currentShanghaiDate(),
      stage: 'MANUAL',
      status: 'RUNNING',
      strategyVersion: current?.strategyVersion ?? '',
      message: '提交实时扫描任务',
      dataCutoffAt: null,
      completedAt: null,
      blockedReasons: [],
      report: null
    }))

    try {
      const started = await startShortTermScanJob(toApiParams(nextParams))
      if (manualRunGeneration.current !== generation) return
      setActiveJobId(started.jobId)
      setScanMessage(started.message || '短线右侧实时扫描中')
      setSnapshot((current) => current ? {
        ...current,
        tradeDate: started.tradeDate,
        status: started.resultStatus,
        strategyVersion: started.strategyVersion,
        blockedReasons: started.blockedReasons,
        message: started.message || '短线右侧实时扫描中'
      } : current)

      const poll = async () => {
        try {
          const job = await fetchShortTermScanJob(started.jobId)
          if (manualRunGeneration.current !== generation) return
          setScanMessage(job.message || '短线右侧实时扫描中')
          if (job.status === 'SUCCEEDED') {
            if (job.report) {
              const manualSnapshot = snapshotFromManualJob(job)
              setSnapshot(manualSnapshot)
              setReport(visibleSnapshotReport(manualSnapshot))
              setError('')
            } else {
              setSnapshot((current) => current ? {
                ...current,
                status: 'FAILED',
                blockedReasons: job.blockedReasons,
                message: '短线扫描任务已完成，但没有返回报告。',
                completedAt: job.finishedAt
              } : current)
              setError('短线扫描任务已完成，但没有返回报告。')
            }
            setLoading(false)
            return
          }
          if (job.status === 'FAILED') {
            const message = job.message || '短线右侧实时扫描失败'
            setSnapshot((current) => current ? {
              ...current,
              status: 'FAILED',
              strategyVersion: job.strategyVersion,
              blockedReasons: job.blockedReasons,
              message,
              completedAt: job.finishedAt
            } : current)
            setError(message)
            setLoading(false)
            return
          }
          setSnapshot((current) => current ? {
            ...current,
            status: job.resultStatus,
            strategyVersion: job.strategyVersion,
            blockedReasons: job.blockedReasons,
            message: job.message || '短线右侧实时扫描中'
          } : current)
          pollTimer.current = window.setTimeout(() => void poll(), 1500)
        } catch (e) {
          if (manualRunGeneration.current === generation) {
            const message = extractErrorMessage(e)
            setSnapshot((current) => current ? {
              ...current,
              status: 'FAILED',
              message,
              completedAt: new Date().toISOString()
            } : current)
            setError(message)
            setLoading(false)
          }
        }
      }

      await poll()
    } catch (e) {
      if (manualRunGeneration.current === generation) {
        const message = extractErrorMessage(e)
        setSnapshot((current) => current ? {
          ...current,
          status: 'FAILED',
          message,
          completedAt: new Date().toISOString()
        } : current)
        setError(message)
        setLoading(false)
      }
    }
  }

  useEffect(() => {
    if (selectedSymbol && !report?.candidates.some((candidate) => candidate.symbol === selectedSymbol)) {
      setSelectedSymbol(null)
    }
  }, [report, selectedSymbol])

  useEffect(() => {
    const generation = backtestRequestGeneration.current + 1
    backtestRequestGeneration.current = generation
    const ownsRequest = () => backtestRequestGeneration.current === generation
    if (!overnightSymbols) {
      setBacktestReport(null)
      setBacktestError('')
      setBacktestLoading(false)
      return
    }
    setBacktestLoading(true)
    setBacktestError('')
    setBacktestReport(null)
    fetchOvernightBacktest({
      symbols: overnightSymbols,
      ...overnightRules
    })
      .then((data) => {
        if (ownsRequest()) setBacktestReport(data)
      })
      .catch((e) => {
        if (ownsRequest()) {
          setBacktestReport(null)
          setBacktestError(extractErrorMessage(e))
        }
      })
      .finally(() => {
        if (ownsRequest()) setBacktestLoading(false)
      })
    return () => {
      if (ownsRequest()) backtestRequestGeneration.current += 1
    }
  }, [overnightRequestKey])

  const selected = useMemo(() => {
    return resolveDetailSelection(report?.candidates ?? [], selectedSymbol, (candidate) => candidate.symbol)
  }, [report, selectedSymbol])

  const diagnostics = useMemo(() => shortTermDiagnostics(report), [report])
  const runningScheduledSnapshot = scheduledSnapshot && isScheduledScanRunning(scheduledSnapshot)
    ? scheduledSnapshot
    : null

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="SHORT TERM"
        title="短线右侧"
        description="从全 A 股里寻找右侧启动前期、热门方向优先、流动性充足且财报质量不拖后腿的候选。"
        extra={
          <Button
            variant="primary"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={origin === 'MANUAL' && loading}
            onClick={() => void runManualScan({ ...draft })}
          >
            重新扫描
          </Button>
        }
      />

      {snapshot ? <ScheduledSnapshotStatus snapshot={snapshot} origin={origin} /> : null}
      {runningScheduledSnapshot ? (
        <ScheduledScanPulse snapshot={runningScheduledSnapshot} />
      ) : null}

      <Card
        title={
          <span className="inline-flex items-center gap-2">
            <SlidersHorizontal className="h-4 w-4 text-brand-500" />
            右侧启动阈值
          </span>
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
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            参考带只影响估值语境分和风险提示，不决定股票是否入选；低流动性、长期横盘、急拉和离均线过远仍受约束。
          </p>
          <Button variant="secondary" disabled={origin === 'MANUAL' && loading} onClick={() => void runManualScan({ ...draft })}>应用阈值</Button>
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
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-4">
            <Card title="方法">
              <div className="flex flex-col gap-2 text-sm leading-relaxed text-ink-600">
                {report.methodology.map((item) => <p key={item}>{item}</p>)}
              </div>
            </Card>
            <Card title="市场情绪">
              <div className="flex flex-col gap-2 text-sm text-ink-600">
                <div className="flex items-baseline justify-between"><span>阶段</span><b>{report.marketSentiment.phase}</b></div>
                <div className="flex items-baseline justify-between"><span>情绪分</span><b>{formatNumber(report.marketSentiment.score)}</b></div>
                <div className="flex items-baseline justify-between"><span>上涨/下跌</span><b>{report.marketSentiment.advancing} / {report.marketSentiment.declining}</b></div>
                <div className="flex items-baseline justify-between"><span>涨停近似/跌停近似</span><b>{report.marketSentiment.limitUpLike} / {report.marketSentiment.limitDownLike}</b></div>
                <p className="text-xs leading-relaxed text-ink-500">{report.marketSentiment.explanation}</p>
              </div>
            </Card>
            <Card title="扫描快照">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="全市场样本" value={report.universeCount} />
                <Metric label="K线复核" value={`${report.klineReviewedCount}/${report.reviewedCount}`} />
                <Metric label="候选数" value={report.candidateCount} />
                <Metric label="更新时间" value={formatDateTime(report.generatedAt)} />
                <Metric label="交易时钟" value={report.tradingSession.phaseLabel} />
                <Metric label="决策窗口" value={report.tradingSession.decisionTimeLabel} />
                <Metric label="强加仓" value={`${diagnostics.addCount}/${report.candidateCount}`} />
                <Metric label="轻仓试错" value={diagnostics.lightTrialCount} />
                <Metric label="次日关注" value={diagnostics.nextWatchCount} />
                <Metric label="尾盘确认" value={diagnostics.tailConfirmedCount} />
                <Metric label="等回踩" value={diagnostics.pullbackAdviceCount} />
              </div>
              {report.tradingSession.warnings.length ? (
                <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-amber-700">
                  {report.tradingSession.warnings[0]}
                </p>
              ) : null}
              <p className="mt-3 border-t border-line-soft pt-3 text-xs leading-relaxed text-ink-500">{report.quoteNote}</p>
            </Card>
            <HotDirectionsCard directions={report.hotDirections} />
          </div>

          {report.candidates.length ? (
            <BacktestSummaryPanel
              report={backtestReport}
              loading={backtestLoading}
              error={backtestError}
            />
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

function HotDirectionsCard({ directions }: { directions: ShortTermHotDirection[] }) {
  return (
    <Card title="热门方向">
      {directions.length ? (
        <div className="flex flex-col gap-2">
          {directions.slice(0, 5).map((direction) => (
            <div key={direction.code} className="rounded-lg border border-line-soft px-3 py-2">
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
      ) : (
        <p className="text-sm leading-relaxed text-ink-500">本轮实时行情没有形成足够集中的热门方向。</p>
      )}
    </Card>
  )
}

export function ScheduledScanPulse({ snapshot }: { snapshot: ShortTermScheduledSnapshot }) {
  return (
    <section className="overflow-hidden rounded-lg border border-sky-200 bg-sky-50/70 px-4 py-3 text-sky-900">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="relative flex h-9 w-9 items-center justify-center rounded-full border border-sky-300 bg-white">
            <span className="absolute h-9 w-9 animate-ping rounded-full bg-sky-200 opacity-40" />
            <RefreshCw className="relative h-4 w-4 animate-spin" aria-hidden="true" />
          </span>
          <div>
            <p className="text-sm font-semibold">14:45 自动扫描正在执行</p>
            <p className="mt-0.5 text-xs leading-relaxed text-sky-700">
              页面正在跟随后台计划任务刷新，完成后会自动切换到最新候选。
            </p>
          </div>
        </div>
        <div className="text-right text-xs text-sky-700">
          <p className="font-mono">{snapshot.tradeDate}</p>
          <p>{snapshot.message || '后台扫描中'}</p>
        </div>
      </div>
      <div className="mt-3 h-1 overflow-hidden rounded-full bg-sky-100">
        <div className="h-full w-1/3 animate-pulse rounded-full bg-sky-500" />
      </div>
    </section>
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
    allowStaticCachePreview: params.allowStaticCachePreview
  }
}

function isScheduledScanRunning(snapshot: ShortTermScheduledSnapshot) {
  return snapshot.status === 'RUNNING' && !snapshot.message.includes('等待 ')
}

function visibleSnapshotReport(snapshot: ShortTermScheduledSnapshot) {
  if (snapshot.status === 'DATA_BLOCKED' || snapshot.status === 'FAILED' || snapshot.status === 'RUNNING') {
    return null
  }
  return snapshot.report
}

function snapshotFromManualJob(job: ShortTermScanJobStatus): ShortTermScheduledSnapshot {
  return {
    tradeDate: job.tradeDate,
    stage: 'MANUAL',
    status: job.resultStatus,
    strategyVersion: job.strategyVersion,
    message: job.message,
    dataCutoffAt: job.report?.dataCutoffAt ?? null,
    completedAt: job.finishedAt,
    blockedReasons: job.blockedReasons,
    report: job.report
  }
}

function currentShanghaiDate() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
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
          <ChipSummaryTags chip={candidate.chip} />
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
            <WatchButton symbol={candidate.symbol} />
            <TradeReviewButton
              symbol={candidate.symbol}
              sourceModule="SHORT_TERM"
              ruleVersion="short-term-right-side-v3-chip-verified"
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
          <ScoreMetric label="四因子原始分" value={candidate.score.finalScore} />
          <ScoreMetric label="阶段校准" value={candidate.score.stageAdjustment ?? 0} />
          <ScoreMetric label="排序分" value={candidate.score.rankingScore ?? candidate.score.finalScore} />
          <ScoreMetric label="V2 排序分" value={candidate.score.v2RankingScore} />
        </div>

        <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-line-soft pt-3 text-xs text-ink-500">
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型技术' : '金叉'} {formatNumber(weightProfile.finalGoldenCross * 100)}%</span>
          <span>量能 {formatNumber(weightProfile.finalVolume * 100)}%</span>
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型热度' : '换手'} {formatNumber(weightProfile.finalTurnover * 100)}%</span>
          <span>{weightProfile.modelVersion === 'legacy-short-term-v1' ? '旧模型财务与估值' : '收盘强度'} {formatNumber(weightProfile.finalCloseStrength * 100)}%</span>
        </div>

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

        <ChipStructurePanel candidate={candidate} />

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

function ChipSummaryTags({ chip }: { chip: ShortTermChipSnapshot | null | undefined }) {
  if (!chip) return <Tag tone="neutral">筹码：历史未算</Tag>
  return (
    <>
      <Tag tone={chipVerificationTone(chip.verificationStatus)}>
        {chip.verificationStatus === 'VERIFIED' ? '筹码认证' : `筹码：${chip.verificationLabel}`}
      </Tag>
      {chip.contributionScore != null ? <Tag tone="neutral">诊断分 {formatNumber(chip.contributionScore)}</Tag> : null}
      {chip.distanceToAverageCostPercent != null ? (
        <Tag tone="neutral">距成本 {formatSignedPercent(chip.distanceToAverageCostPercent)}</Tag>
      ) : null}
    </>
  )
}

function ChipStructurePanel({ candidate }: { candidate: ShortTermCandidate }) {
  const chip = candidate.chip
  const chipDataGaps = chip?.dataGaps ?? []
  return (
    <section className="border-t border-line-soft pt-4" aria-label="筹码结构与外部认证">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">筹码结构与外部认证</h3>
        {chip ? (
          <Tag tone={chipVerificationTone(chip.verificationStatus)}>{chip.verificationLabel}</Tag>
        ) : null}
      </div>
      {!chip ? (
        <p className="mt-3 border-l-2 border-line-soft pl-3 text-sm leading-relaxed text-ink-500">
          历史版本未计算。该报告保留原排序，不用后验筹码数据改写当时结论。
        </p>
      ) : (
        <>
          <div className="mt-3 grid grid-cols-2 gap-2 lg:grid-cols-3">
            <Metric label="平均成本" value={formatPerSharePrice(chip.averageCost)} />
            <Metric label="距平均成本" value={formatSignedPercent(chip.distanceToAverageCostPercent)} />
            <Metric label="获利筹码" value={formatPercent(chip.winnerRatePercent)} />
            <Metric label="上方筹码" value={formatPercent(chip.overheadChipRatioPercent)} />
            <Metric label="70% 成本区间" value={`${formatPrice(chip.cost70Low)} - ${formatPrice(chip.cost70High)}`} />
            <Metric label="70% 成本带宽" value={formatPercent(chip.cost70ConcentrationPercent)} />
            <Metric label="前高参考" value={formatPerSharePrice(chip.priorHighPrice)} />
            <Metric label="前高区残余筹码" value={formatPercent(chip.priorHighZoneResidualRatioPercent)} />
            <Metric label="前高后累计换手" value={formatPercent(chip.turnoverSincePriorHighPercent)} />
            <Metric label="筹码结构分" value={formatNumber(chip.chipStructureScore)} />
            <Metric label="认证系数" value={formatNumber(chip.verificationCoefficient)} />
            <Metric label="独立诊断分" value={formatNumber(chip.contributionScore)} />
            <Metric label="主排序关系" value="不参与主排序" />
            <Metric label="本地 / 外部日期" value={`${chip.localTradeDate ?? '待补'} / ${chip.externalTradeDate ?? '待补'}`} />
            <Metric label="计算口径" value={chip.calculationMode === 'INTRADAY_ESTIMATE' ? '盘中估算' : '完整日 K'} />
          </div>
          <p className="mt-3 text-xs leading-relaxed text-ink-500">
            筹码是基于换手率与价格区间估算的成本分布画像，用来观察大部分筹码在低位还是高位、上方套牢盘是否已经消化；它不参与金叉、量能、换手率和收盘强度的主分计算。
          </p>
          {chipDataGaps.length ? (
            <div className="mt-3 border-l-2 border-amber-300 pl-3 text-xs leading-relaxed text-amber-700">
              {chipDataGaps.join('；')}
            </div>
          ) : null}
        </>
      )}
    </section>
  )
}

function chipVerificationTone(status: ChipVerificationStatus) {
  if (status === 'VERIFIED') return 'success' as const
  if (status === 'SINGLE_SOURCE') return 'sky' as const
  if (status === 'CONFLICT' || status === 'STALE') return 'warning' as const
  return 'neutral' as const
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

function BacktestSummaryPanel({
  report,
  loading,
  error
}: {
  report: OvernightBacktestReport | null
  loading: boolean
  error: string
}) {
  if (loading && !report) {
    return (
      <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
        <Loader text="技术信号历史验证中" />
      </div>
    )
  }
  if (error && !report) {
    return (
      <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs leading-relaxed text-amber-700">
        技术信号历史验证暂不可用：{error}
      </div>
    )
  }
  if (!report) {
    return (
      <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
        <Tag tone="neutral">技术信号历史验证</Tag>
        <p className="mt-2 text-xs leading-relaxed text-ink-500">暂无可用技术信号历史样本。</p>
      </div>
    )
  }
  if (report.status === 'DATA_BLOCKED') {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-3">
        <Tag tone="danger">技术验证数据阻断</Tag>
        <p className="mt-2 text-xs leading-relaxed text-red-700">{report.message}</p>
      </div>
    )
  }
  const { summary } = report
  const gaps = report.results.filter((result) =>
    result.status === 'SOURCE_FAILED' || result.status === 'INSUFFICIENT_HISTORY')
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">技术信号历史验证</Tag>
          <Tag tone={backtestTone(summary)}>{backtestSupportLabel(summary)}</Tag>
        </div>
        <span className="tabular text-xs font-semibold text-ink-500">{summary.sampleCount} 笔隔夜样本</span>
      </div>
      {report.status === 'PARTIAL' ? (
        <div className="mt-3 border-l-2 border-amber-300 pl-3 text-xs leading-relaxed text-amber-700">
          <p>{report.message}</p>
          {gaps.map((result) => (
            <p key={result.symbol} className="mt-1">
              <span className="font-mono font-semibold">{result.symbol}</span>
              {' · '}
              {result.dataGaps[0] ?? result.status}
            </p>
          ))}
        </div>
      ) : null}
      <div className="mt-3 grid grid-cols-2 gap-2 md:grid-cols-3">
        <Metric label="正收益率" value={formatPercent(summary.positiveRatePercent)} compact />
        <Metric label="均值收益" value={<span className={changeClass(summary.averageReturnPercent)}>{formatPercent(summary.averageReturnPercent)}</span>} compact />
        <Metric label="中位收益" value={<span className={changeClass(summary.medianReturnPercent)}>{formatPercent(summary.medianReturnPercent)}</span>} compact />
        <Metric label="均值回撤" value={formatPercent(summary.averageDrawdownPercent)} compact />
        <Metric label="第一目标" value={formatPercent(summary.firstTargetRatePercent)} compact />
        <Metric label="第二目标" value={formatPercent(summary.secondTargetRatePercent)} compact />
        <Metric label="硬止损" value={formatPercent(summary.hardStopRatePercent)} compact />
        <Metric label="时间退出" value={formatPercent(summary.timeStopRatePercent)} compact />
        <Metric label="次日低开" value={formatPercent(summary.gapDownRatePercent)} compact />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">
        已回放：{report.validationScope.join('；')}。仅表示技术信号历史验证，不代表完整生产策略表现。
      </p>
      <p className="mt-1 text-xs leading-relaxed text-ink-500">
        未回放：{report.unreplayedGates.join('、')}。
      </p>
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

function backtestTone(summary: OvernightBacktestSummary): 'success' | 'brand' | 'warning' | 'neutral' {
  if (summary.conclusion.includes('支持')) return 'success'
  if (summary.conclusion.includes('正收益')) return 'brand'
  if (summary.conclusion.includes('偏弱')) return 'warning'
  return 'neutral'
}

function backtestSupportLabel(summary: OvernightBacktestSummary) {
  if (summary.sampleCount < 5) return '样本少'
  if (summary.conclusion.includes('支持')) return '支持'
  if (summary.conclusion.includes('正收益')) return '可参考'
  if (summary.conclusion.includes('偏弱')) return '偏弱'
  return '波动大'
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
