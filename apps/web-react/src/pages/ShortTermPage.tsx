import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { CandlestickChart, RefreshCw, SlidersHorizontal } from 'lucide-react'
import { fetchRightSideBacktest, fetchShortTermScanJob, startShortTermScanJob } from '../api/client'
import type { ShortTermParams } from '../api/client'
import { ScoreBadge, Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { WatchButton } from '../components/watchlist/WatchButton'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatNumber, formatPercent, formatPerSharePrice, formatRatioPercent, formatSignedPercent, formatValuationState } from '../lib/format'
import type { BacktestReport, BacktestSummary, ShortTermCandidate, ShortTermHotDirection, ShortTermReport, ShortTermTailSignal, ShortTermWeightProfile, TradingAdvice } from '../types'

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
}

const DEFAULT_DRAFT: DraftParams = {
  limit: 8,
  scanLimit: 6000,
  klineLimit: 60,
  minAmountYi: 0.8,
  maxPe: 100,
  maxPb: 15,
  minVolumeRatio: 1.15,
  maxEntryRise: 4,
  maxDistanceToMa20: 8,
  minFinancialScore: 58
}

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
  const [params, setParams] = useState<DraftParams>(DEFAULT_DRAFT)
  const [report, setReport] = useState<ShortTermReport | null>(null)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [backtestReport, setBacktestReport] = useState<BacktestReport | null>(null)
  const [backtestLoading, setBacktestLoading] = useState(false)
  const [backtestError, setBacktestError] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [scanMessage, setScanMessage] = useState('')
  const [activeJobId, setActiveJobId] = useState('')

  useEffect(() => {
    let alive = true
    let timer: number | undefined
    setLoading(true)
    setError('')
    setReport(null)
    setSelectedSymbol(null)
    setScanMessage('提交实时扫描任务')
    setActiveJobId('')

    async function runScan() {
      try {
        const started = await startShortTermScanJob(toApiParams(params))
        if (!alive) return
        setActiveJobId(started.jobId)
        setScanMessage(started.message || '短线右侧实时扫描中')

        const poll = async () => {
          try {
            const job = await fetchShortTermScanJob(started.jobId)
            if (!alive) return
            setScanMessage(job.message || '短线右侧实时扫描中')
            if (job.status === 'SUCCEEDED') {
              if (job.report) {
                setReport(job.report)
                setError('')
              } else {
                setError('短线扫描任务已完成，但没有返回报告。')
              }
              setLoading(false)
              return
            }
            if (job.status === 'FAILED') {
              setError(job.message || '短线右侧实时扫描失败')
              setLoading(false)
              return
            }
            timer = window.setTimeout(poll, 1500)
          } catch (e) {
            if (alive) {
              setError(extractErrorMessage(e))
              setLoading(false)
            }
          }
        }

        await poll()
      } catch (e) {
        if (alive) {
          setError(extractErrorMessage(e))
          setLoading(false)
        }
      }
    }

    runScan()
    return () => {
      alive = false
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [params])

  useEffect(() => {
    if (!report?.candidates.length) return
    if (!selectedSymbol || !report.candidates.some((candidate) => candidate.symbol === selectedSymbol)) {
      setSelectedSymbol(report.candidates[0].symbol)
    }
  }, [report, selectedSymbol])

  useEffect(() => {
    if (!report?.candidates.length) {
      setBacktestReport(null)
      setBacktestError('')
      return
    }
    let alive = true
    setBacktestLoading(true)
    setBacktestError('')
    const symbols = report.candidates.map((candidate) => candidate.symbol).join(',')
    fetchRightSideBacktest({
      symbols,
      lookbackDays: 900,
      holdingDays: 20,
      minVolumeRatio: params.minVolumeRatio,
      maxDistanceToMa20: params.maxDistanceToMa20,
      stopLossPercent: 6,
      takeProfitPercent: 18
    })
      .then((data) => {
        if (alive) setBacktestReport(data)
      })
      .catch((e) => {
        if (alive) {
          setBacktestReport(null)
          setBacktestError(extractErrorMessage(e))
        }
      })
      .finally(() => {
        if (alive) setBacktestLoading(false)
      })
    return () => {
      alive = false
    }
  }, [report?.generatedAt, report?.candidateCount, params.minVolumeRatio, params.maxDistanceToMa20])

  const selected = useMemo(() => {
    if (!report?.candidates.length) return null
    return report.candidates.find((candidate) => candidate.symbol === selectedSymbol) ?? report.candidates[0]
  }, [report, selectedSymbol])

  const backtestBySymbol = useMemo(() => {
    return new Map((backtestReport?.results ?? []).map((result) => [result.symbol, result.summary]))
  }, [backtestReport])
  const diagnostics = useMemo(() => shortTermDiagnostics(report), [report])

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
            右侧启动阈值
          </span>
        }
      >
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <NumberField label="候选数量" value={draft.limit} min={4} max={40} onChange={(value) => setDraft({ ...draft, limit: value })} />
          <NumberField label="扫描数量" value={draft.scanLimit} min={50} max={6000} step={100} onChange={(value) => setDraft({ ...draft, scanLimit: value })} />
          <NumberField label="K线复核数" value={draft.klineLimit} min={10} max={160} step={10} onChange={(value) => setDraft({ ...draft, klineLimit: value })} />
          <NumberField label="成交额下限(亿)" value={draft.minAmountYi} min={0.8} max={30} step={0.05} onChange={(value) => setDraft({ ...draft, minAmountYi: value })} />
          <NumberField label="PE 参考带" value={draft.maxPe} min={4} max={200} onChange={(value) => setDraft({ ...draft, maxPe: value })} />
          <NumberField label="PB 参考带" value={draft.maxPb} min={0.2} max={40} step={0.1} onChange={(value) => setDraft({ ...draft, maxPb: value })} />
          <NumberField label="量比下限" value={draft.minVolumeRatio} min={0.8} max={3} step={0.05} onChange={(value) => setDraft({ ...draft, minVolumeRatio: value })} />
          <NumberField label="追涨上限%" value={draft.maxEntryRise} min={1} max={10} step={0.1} onChange={(value) => setDraft({ ...draft, maxEntryRise: value })} />
          <NumberField label="距20日线%" value={draft.maxDistanceToMa20} min={2} max={20} step={0.5} onChange={(value) => setDraft({ ...draft, maxDistanceToMa20: value })} />
          <NumberField label="财报分下限" value={draft.minFinancialScore} min={30} max={90} onChange={(value) => setDraft({ ...draft, minFinancialScore: value })} />
        </div>
        <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-line-soft pt-3">
          <p className="text-xs leading-relaxed text-ink-500">
            参考带只影响估值语境分和风险提示，不决定股票是否入选；低流动性、长期横盘、急拉和离均线过远仍受约束。
          </p>
          <Button variant="secondary" onClick={() => setParams({ ...draft })}>应用阈值</Button>
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
            <Card title="当前规则">
              <div className="grid grid-cols-2 gap-3 text-sm">
                <Metric label="扫描/复核" value={`${report.ruleSet.scanLimit}/${report.ruleSet.klineLimit}`} />
                <Metric label="成交额" value={formatAmount(report.ruleSet.minAmount)} />
                <Metric label="量比下限" value={formatNumber(report.ruleSet.minVolumeRatio)} />
                <Metric label="财报分" value={formatNumber(report.ruleSet.minFinancialScore)} />
                <Metric label="PE 参考带" value={formatNumber(report.ruleSet.maxPe)} />
                <Metric label="PB 参考带" value={formatNumber(report.ruleSet.maxPb)} />
              </div>
            </Card>
            <HotDirectionsCard directions={report.hotDirections} />
          </div>

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_450px]">
            <Card title={<span className="inline-flex items-center gap-2"><CandlestickChart className="h-4 w-4 text-brand-500" />右侧候选</span>} flush>
              {report.candidates.length ? (
                <div className="divide-y divide-line-soft">
                {report.candidates.map((candidate) => (
                    <CandidateRow
                      key={candidate.symbol}
                      candidate={candidate}
                      selected={selected?.symbol === candidate.symbol}
                      backtestSummary={backtestBySymbol.get(candidate.symbol)}
                      backtestLoading={backtestLoading}
                      onSelect={() => setSelectedSymbol(candidate.symbol)}
                    />
                  ))}
                </div>
              ) : (
                <div className="p-5"><Loader text="暂无候选" /></div>
              )}
            </Card>

            <div className="xl:sticky xl:top-4 xl:self-start">
              {selected ? (
                <CandidateDetail
                  candidate={selected}
                  backtestSummary={backtestBySymbol.get(selected.symbol)}
                  backtestLoading={backtestLoading}
                  backtestError={backtestError}
                  weightProfile={report.weightProfile}
                />
              ) : <Card><Loader text="等待候选数据" /></Card>}
            </div>
          </div>
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
    minFinancialScore: params.minFinancialScore
  }
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
  backtestSummary,
  backtestLoading,
  onSelect
}: {
  candidate: ShortTermCandidate
  selected: boolean
  backtestSummary?: BacktestSummary
  backtestLoading: boolean
  onSelect: () => void
}) {
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
          <Tag tone="neutral">{candidate.technical.rightSideSignal}</Tag>
          <Tag tone="neutral">20日斜率 {formatNumber(candidate.technical.ma20SlopePercent)}%</Tag>
          {backtestSummary ? (
            <Tag tone={backtestTone(backtestSummary)}>历史验证：{backtestSupportLabel(backtestSummary)}</Tag>
          ) : backtestLoading ? (
            <Tag tone="neutral">历史验证中</Tag>
          ) : null}
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
      </div>

      <div className="flex items-center justify-between gap-3 md:flex-col md:items-end md:justify-center">
        <ScoreBadge value={candidate.score.finalScore} />
        <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag>
        <Tag tone={adviceTone(candidate.todayAdvice.action)}>建议：{candidate.todayAdvice.actionLabel}</Tag>
        <Tag tone={tailTone(candidate.tailSignal.status)}>{candidate.tailSignal.statusLabel}</Tag>
      </div>
    </button>
  )
}

function CandidateDetail({
  candidate,
  backtestSummary,
  backtestLoading,
  backtestError,
  weightProfile
}: {
  candidate: ShortTermCandidate
  backtestSummary?: BacktestSummary
  backtestLoading: boolean
  backtestError: string
  weightProfile: ShortTermWeightProfile
}) {
  return (
    <Card className="transition hover:border-brand-300">
      <div className="flex flex-col gap-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="eyebrow">#{candidate.rank} · {candidate.market ?? 'A股'} · {candidate.industry ?? '行业待补'}</div>
            <h2 className="mt-1 text-xl font-semibold text-ink-900">{candidate.name}</h2>
            <div className="mt-1 font-mono text-xs text-ink-400">{candidate.symbol}</div>
          </div>
          <div className="flex shrink-0 flex-col items-end gap-2">
            <WatchButton symbol={candidate.symbol} />
            <ScoreBadge value={candidate.score.finalScore} />
            <Tag tone={adviceTone(candidate.todayAdvice.action)}>建议：{candidate.todayAdvice.actionLabel}</Tag>
            <Tag tone={actionTone[candidate.action] ?? 'neutral'}>{candidate.actionLabel}</Tag>
          </div>
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
          <Metric label="买入观察区" value={`${formatPrice(candidate.buyZoneLow)} - ${formatPrice(candidate.buyZoneHigh)}`} />
          <Metric label="止损参考" value={formatPrice(candidate.stopPrice)} />
          <Metric label="尾盘状态" value={candidate.tailSignal.statusLabel} />
          <Metric label="最新分时" value={candidate.tailSignal.latestMinute ?? '待补充'} />
          <Metric label="行情时点" value={candidate.quoteFreshness.statusLabel} />
          <Metric label="市场时间" value={formatDateTime(candidate.quoteFreshness.marketTimestamp)} />
          <Metric label="证据完整度" value={`${candidate.evidenceCompleteness.score}`} />
        </div>

        <div className="grid grid-cols-2 gap-2">
          <ScoreMetric label="K线" value={candidate.score.technicalScore} />
          <ScoreMetric label="量能" value={candidate.score.volumeScore} />
          <ScoreMetric label="热度" value={candidate.score.marketHeatScore} />
          <ScoreMetric label="估值语境 5%" value={candidate.score.valuationScore} />
          <ScoreMetric label="财报" value={candidate.score.financialScore} />
          <ScoreMetric label="扣分" value={candidate.score.riskPenalty} />
          <ScoreMetric label="综合" value={candidate.score.finalScore} />
        </div>

        <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-line-soft pt-3 text-xs text-ink-500">
          <span>K线 {formatNumber(weightProfile.finalTechnical * 100)}%</span>
          <span>量能 {formatNumber(weightProfile.finalVolume * 100)}%</span>
          <span>热度 {formatNumber(weightProfile.finalHeat * 100)}%</span>
          <span>财报 {formatNumber(weightProfile.finalFinancial * 100)}%</span>
          <span>估值语境 {formatNumber(weightProfile.finalValuation * 100)}%</span>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <Metric label="MA5/10/20" value={`${formatNumber(candidate.technical.ma5)} / ${formatNumber(candidate.technical.ma10)} / ${formatNumber(candidate.technical.ma20)}`} />
          <Metric label="20/60日线斜率" value={`${formatNumber(candidate.technical.ma20SlopePercent)}% / ${formatNumber(candidate.technical.ma60SlopePercent)}%`} />
          <Metric label="突破20日高点" value={`${formatNumber(candidate.technical.breakoutFromPreviousHigh20Percent)}%`} />
          <Metric label="60/120日位置" value={`${formatNumber(candidate.technical.rangePosition60)}% / ${formatNumber(candidate.technical.rangePosition120)}%`} />
          <Metric label="120日高点回撤" value={`${formatNumber(candidate.technical.drawdownFrom120HighPercent)}%`} />
          <Metric label="站上20日线天数" value={candidate.technical.consecutiveAboveMa20Days} />
          <Metric label="ROE/毛利率" value={`${formatRatioPercent(candidate.financial.roe)} / ${formatRatioPercent(candidate.financial.grossMargin)}`} />
          <Metric label="现金流年数" value={`${candidate.financial.positiveCashFlowYears}/3`} />
        </div>

        <TailSignalPanel signal={candidate.tailSignal} />

        <TodayAdvicePanel advice={candidate.todayAdvice} />
        <EvidenceCompletenessPanel completeness={candidate.evidenceCompleteness} />

        <BacktestSummaryPanel summary={backtestSummary} loading={backtestLoading} error={backtestError} />

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
    </Card>
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
  summary,
  loading,
  error
}: {
  summary?: BacktestSummary
  loading: boolean
  error: string
}) {
  if (loading && !summary) {
    return (
      <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
        <Loader text="历史验证中" />
      </div>
    )
  }
  if (error && !summary) {
    return (
      <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs leading-relaxed text-amber-700">
        历史验证暂不可用：{error}
      </div>
    )
  }
  if (!summary) {
    return (
      <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
        <Tag tone="neutral">历史验证</Tag>
        <p className="mt-2 text-xs leading-relaxed text-ink-500">暂无可用历史样本，当前只看实时形态和风控条件。</p>
      </div>
    )
  }
  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">历史验证</Tag>
          <Tag tone={backtestTone(summary)}>{backtestSupportLabel(summary)}</Tag>
        </div>
        <span className="tabular text-xs font-semibold text-ink-500">{summary.tradeCount} 笔信号</span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <Metric label="胜率" value={formatPercent(summary.winRatePercent)} compact />
        <Metric label="均值收益" value={<span className={changeClass(summary.averageReturnPercent)}>{formatPercent(summary.averageReturnPercent)}</span>} compact />
        <Metric label="均值回撤" value={formatPercent(summary.averageMaxDrawdownPercent)} compact />
        <Metric label="盈亏比" value={formatNumber(summary.profitFactor)} compact />
      </div>
      <p className="mt-2 text-xs leading-relaxed text-ink-500">
        这不是给你单独看的回测表，而是系统把过去相似信号做成的可信度支撑；样本少时只做降权参考。
      </p>
    </div>
  )
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

function backtestTone(summary: BacktestSummary): 'success' | 'brand' | 'warning' | 'neutral' {
  if (summary.conclusion.includes('支持')) return 'success'
  if (summary.conclusion.includes('正收益')) return 'brand'
  if (summary.conclusion.includes('偏弱')) return 'warning'
  return 'neutral'
}

function backtestSupportLabel(summary: BacktestSummary) {
  if (summary.tradeCount < 5) return '样本少'
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

function ScoreMetric({ label, value }: { label: string; value: number }) {
  const width = label === '扣分' ? Math.max(0, Math.min(100, value * 2.5)) : Math.max(0, Math.min(100, value))
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
