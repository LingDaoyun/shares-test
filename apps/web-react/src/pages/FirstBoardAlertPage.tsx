import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, RefreshCw } from 'lucide-react'
import { fetchFirstBoardAlertSnapshot, fetchFirstBoardBars } from '../api/client'
import { FirstBoardCandlestickChart } from '../components/firstboard/FirstBoardCandlestickChart'
import { Tag } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { DataTable, type Column } from '../components/ui/DataTable'
import { DetailOverlay } from '../components/ui/DetailOverlay'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { toast } from '../components/ui/Toast'
import { changeClass, extractErrorMessage, formatDateTime, formatSignedPercent } from '../lib/format'
import { collectNewFirstBoardNotifications } from '../lib/firstBoardAlertNotifications'
import {
  loadFirstBoardBoardPreferences,
  saveFirstBoardBoardPreferences,
  type FirstBoardBoardPreferences
} from '../lib/firstBoardAlertPreferences'
import type {
  FirstBoardAlertCandidate,
  FirstBoardAlertLevel,
  FirstBoardAlertSnapshot,
  FirstBoardBarSeries
} from '../types'

const REFRESH_INTERVAL_MS = 60_000

export function FirstBoardAlertPage() {
  const [preferences, setPreferences] = useState<FirstBoardBoardPreferences>(() => loadFirstBoardBoardPreferences())
  const [snapshot, setSnapshot] = useState<FirstBoardAlertSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<FirstBoardAlertCandidate | null>(null)
  const [barSeries, setBarSeries] = useState<FirstBoardBarSeries | null>(null)
  const [barLoading, setBarLoading] = useState(false)
  const [barError, setBarError] = useState<string | null>(null)
  const requestId = useRef(0)

  const load = useCallback(async () => {
    const currentRequest = ++requestId.current
    setLoading(true)
    setError(null)
    try {
      const next = await fetchFirstBoardAlertSnapshot(preferences)
      if (currentRequest !== requestId.current) return
      setSnapshot(next)
      for (const candidate of collectNewFirstBoardNotifications(next)) {
        const message = `${candidate.name} ${levelLabel(candidate.level)} · ${formatNumber(candidate.latestPrice)} / ${formatSignedPercent(candidate.changePercent)}`
        const key = `first-board:${next.tradeDate}:${candidate.symbol}:${candidate.level}`
        if (candidate.level === 'ACCELERATING' || candidate.level === 'REPEAT_ACCELERATING') {
          toast.warning(message, { key, durationMs: 5200 })
        } else {
          toast.success(message, { key, durationMs: 5200 })
        }
      }
    } catch (caught) {
      if (currentRequest === requestId.current) setError(extractErrorMessage(caught))
    } finally {
      if (currentRequest === requestId.current) setLoading(false)
    }
  }, [preferences])

  useEffect(() => {
    void load()
  }, [load])

  const shouldPoll = snapshot?.autoRefreshAllowed === true
    || snapshot?.status === 'INITIALIZING'
    || snapshot?.status === 'UPDATING'
    || snapshot?.status === 'FAILED'

  useEffect(() => {
    if (!shouldPoll) return
    const timer = window.setInterval(() => void load(), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [load, shouldPoll])

  const updatePreferences = (next: FirstBoardBoardPreferences) => {
    requestId.current += 1
    saveFirstBoardBoardPreferences(next)
    setSelected(null)
    setBarSeries(null)
    setSnapshot(null)
    setPreferences(next)
  }

  const openDetails = async (candidate: FirstBoardAlertCandidate) => {
    setSelected(candidate)
    setBarSeries(null)
    setBarError(null)
    setBarLoading(true)
    try {
      setBarSeries(await fetchFirstBoardBars(candidate.symbol))
    } catch (caught) {
      setBarError(extractErrorMessage(caught))
    } finally {
      setBarLoading(false)
    }
  }

  const status = snapshot ? statusPresentation(snapshot) : null
  const latentColumns = useMemo(() => candidateColumns('LATENT'), [])
  const previousColumns = useMemo(() => candidateColumns('PREVIOUS_LIMIT_UP'), [])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="FIRST BOARD ALERT"
        title="首板预警"
        description="T-1 技术基线叠加盘中量比，扫描尚未触及涨停价的首板候选；昨日涨停单独观察，不作为排除项。"
        extra={(
          <div className="flex items-center gap-3">
            {snapshot?.quoteFetchedAt ? (
              <span className="tabular text-xs text-ink-400">行情 {formatDateTime(snapshot.quoteFetchedAt)}</span>
            ) : null}
            <Button
              variant="primary"
              icon={<RefreshCw className="h-4 w-4" />}
              loading={loading}
              onClick={() => void load()}
            >
              刷新
            </Button>
          </div>
        )}
      />

      <Card>
        <div className="grid gap-3 lg:grid-cols-[1fr_auto_auto] lg:items-center">
          <div>
            <div className="text-sm font-semibold text-ink-900">扫描范围</div>
            <p className="mt-1 text-xs leading-relaxed text-ink-500">
              主板始终扫描；ST、退市整理、有效日线不足 60 日以及盘中已经触板的股票始终排除。
            </p>
          </div>
          <BoardSwitch
            label="创业板"
            description="300 / 301"
            checked={preferences.includeGrowth}
            onChange={(checked) => updatePreferences({ ...preferences, includeGrowth: checked })}
          />
          <BoardSwitch
            label="科创板"
            description="688 / 689"
            checked={preferences.includeStar}
            onChange={(checked) => updatePreferences({ ...preferences, includeStar: checked })}
          />
        </div>
      </Card>

      {error ? <Card className="border-red-200 bg-red-50 text-sm text-red-700">{error}</Card> : null}
      {loading && !snapshot ? <Card><Loader text="首板技术基线与盘中行情加载中" /></Card> : null}

      {status ? (
        <Card className={status.className}>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-2">
              <Activity className="h-4 w-4" />
              <span className="text-sm font-semibold">{status.label}</span>
              {snapshot?.tradeDate ? <span className="tabular text-xs opacity-75">交易日 {snapshot.tradeDate}</span> : null}
              {snapshot && snapshot.progressTotal > 0 ? (
                <span className="tabular text-xs opacity-75">
                  {snapshot.progressCompleted} / {snapshot.progressTotal}
                  {snapshot.progressFailed > 0 ? ` · 失败 ${snapshot.progressFailed}` : ''}
                </span>
              ) : null}
            </div>
            <span className="text-xs opacity-75">
              {snapshot?.status !== 'READY'
                ? '候选池准备中，每 60 秒检查'
                : snapshot?.autoRefreshAllowed ? '盘中每 60 秒自动刷新' : '当前仅手动刷新'}
            </span>
          </div>
          {snapshot?.dataGaps.length ? (
            <ul className="mt-2 flex flex-col gap-1 text-xs leading-relaxed">
              {snapshot.dataGaps.map((gap) => <li key={gap}>{gap}</li>)}
            </ul>
          ) : null}
        </Card>
      ) : null}

      {snapshot?.status === 'READY' ? (
        <>
          <CoverageStrip snapshot={snapshot} />
          <CandidateTable
            title="首板潜伏"
            subtitle="未在昨日涨停池中；按级别、技术项数、盘中量比和趋势强度排序。"
            candidates={snapshot.latentCandidates}
            columns={latentColumns}
            onSelect={(candidate) => void openDetails(candidate)}
          />
          <CandidateTable
            title="昨日涨停观察"
            subtitle="昨日涨停继续保留；10:30 后逐级降级，11:00 后最高只显示观察。"
            candidates={snapshot.previousLimitUpCandidates}
            columns={previousColumns}
            onSelect={(candidate) => void openDetails(candidate)}
          />
        </>
      ) : null}

      <DetailOverlay
        open={Boolean(selected)}
        title={selected ? `${selected.name} · ${selected.symbol}` : '技术图表'}
        subtitle={selected ? `${selected.industry ?? '行业待补充'} · T-1 最近 20 个交易日` : undefined}
        onClose={() => {
          setSelected(null)
          setBarSeries(null)
          setBarError(null)
        }}
      >
        {barLoading ? <Loader text="日线图加载中" /> : null}
        {barError ? <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{barError}</div> : null}
        {barSeries ? <FirstBoardCandlestickChart bars={barSeries.bars} /> : null}
      </DetailOverlay>
    </div>
  )
}

function BoardSwitch({
  label,
  description,
  checked,
  onChange
}: {
  label: string
  description: string
  checked: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <label className="flex min-w-[150px] cursor-pointer items-center justify-between gap-4 rounded-xl border border-line-soft bg-line-soft/25 px-3 py-2.5">
      <span>
        <span className="block text-sm font-semibold text-ink-800">{label}</span>
        <span className="tabular block text-[11px] text-ink-400">{description}</span>
      </span>
      <input
        type="checkbox"
        className="h-5 w-5 accent-brand-600"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        aria-label={`允许扫描${label}`}
      />
    </label>
  )
}

function CoverageStrip({ snapshot }: { snapshot: FirstBoardAlertSnapshot }) {
  const coverage = snapshot.coverage
  const metrics = [
    ['T-1 基线', `${coverage.baselineCount} 只`],
    ['当前范围', `${coverage.scopedBaselineCount} 只`],
    ['盘中行情', `${coverage.quotedCount} 只`],
    ['当前候选', `${coverage.eligibleCount} 只`],
    ['已触板排除', `${coverage.excludedTouchedLimitCount} 只`],
    ['量比不足', `${coverage.excludedVolumeRatioCount} 只`]
  ]
  return (
    <Card>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
        {metrics.map(([label, value]) => (
          <div key={label} className="rounded-xl border border-line-soft bg-line-soft/25 px-3 py-2.5">
            <div className="text-[11px] text-ink-400">{label}</div>
            <div className="tabular mt-1 text-base font-semibold text-ink-900">{value}</div>
          </div>
        ))}
      </div>
    </Card>
  )
}

function CandidateTable({
  title,
  subtitle,
  candidates,
  columns,
  onSelect
}: {
  title: string
  subtitle: string
  candidates: FirstBoardAlertCandidate[]
  columns: Column<FirstBoardAlertCandidate>[]
  onSelect: (candidate: FirstBoardAlertCandidate) => void
}) {
  return (
    <Card
      title={(
        <div>
          <div className="flex items-center gap-2">
            <span>{title}</span>
            <Tag tone="neutral">{candidates.length} 只</Tag>
          </div>
          <p className="mt-1 text-xs font-normal leading-relaxed text-ink-500">{subtitle}</p>
        </div>
      )}
      flush
    >
      <DataTable
        columns={columns}
        data={candidates}
        rowKey={(candidate) => candidate.symbol}
        onRowClick={onSelect}
        emptyText={`${title}暂无候选`}
        tableClassName="min-w-[1260px]"
      />
    </Card>
  )
}

function candidateColumns(lane: 'LATENT' | 'PREVIOUS_LIMIT_UP'): Column<FirstBoardAlertCandidate>[] {
  return [
    {
      key: 'stock', title: '股票 / 行业', width: '150px', render: (candidate) => (
        <div>
          <div className="font-semibold text-ink-900">{candidate.name}</div>
          <div className="tabular mt-0.5 text-xs text-ink-400">{candidate.symbol} · {candidate.industry ?? '—'}</div>
        </div>
      )
    },
    {
      key: 'price', title: '现价 / 涨幅', align: 'right', width: '105px', render: (candidate) => (
        <div className="tabular">
          <div className="font-semibold text-ink-900">{formatNumber(candidate.latestPrice)}</div>
          <div className={`mt-0.5 text-xs ${changeClass(candidate.changePercent)}`}>{formatSignedPercent(candidate.changePercent)}</div>
        </div>
      )
    },
    {
      key: 'ma', title: 'MA10 / MA20', align: 'right', width: '120px', render: (candidate) => (
        <MetricStack first={formatNumber(candidate.ma10)} second={formatNumber(candidate.ma20)} />
      )
    },
    {
      key: 'slope', title: 'MA20斜率', align: 'right', width: '90px', render: (candidate) => metricPercent(candidate.ma20Slope)
    },
    {
      key: 'momentum', title: 'RSI14 / MACD柱', align: 'right', width: '125px', render: (candidate) => (
        <MetricStack first={formatNumber(candidate.rsi14)} second={formatNumber(candidate.macdHistogram, 4)} />
      )
    },
    {
      key: 'volume', title: '实时 / 昨日 / 近5日', align: 'right', width: '150px', render: (candidate) => (
        <div className="tabular text-xs leading-5 text-ink-600">
          <div className="font-semibold text-ink-900">{formatNumber(candidate.realtimeVolumeRatio)}x</div>
          <div>{formatNumber(candidate.previousDayVolumeRatio)}x / {formatNumber(candidate.recentFiveMaxVolumeRatio)}x</div>
        </div>
      )
    },
    {
      key: 'shape', title: lane === 'LATENT' ? '上影线' : '盘中回撤', align: 'right', width: '95px', render: (candidate) => (
        metricPercent(lane === 'LATENT' ? candidate.upperShadowRatio : candidate.intradayDrawdown)
      )
    },
    {
      key: 'range', title: '3日收盘区间', align: 'right', width: '105px', render: (candidate) => metricPercent(candidate.threeDayCloseRange)
    },
    {
      key: 'level', title: '技术项 / 级别', align: 'center', width: '130px', render: (candidate) => (
        <div className="flex flex-col items-center gap-1">
          <span className="tabular text-xs text-ink-500">{candidate.technicalPassCount} / 5</span>
          <Tag tone={levelTone(candidate.level)}>{levelLabel(candidate.level)}</Tag>
        </div>
      )
    }
  ]
}

function MetricStack({ first, second }: { first: string; second: string }) {
  return (
    <div className="tabular text-xs leading-5 text-ink-600">
      <div className="font-semibold text-ink-900">{first}</div>
      <div>{second}</div>
    </div>
  )
}

function formatNumber(value: number | null | undefined, digits = 2) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—'
  return Number(value).toFixed(digits)
}

function metricPercent(value: number | null | undefined) {
  return <span className="tabular text-xs font-medium text-ink-700">{formatNumber(value)}%</span>
}

function levelLabel(level: FirstBoardAlertLevel) {
  return {
    WATCH: '观察',
    STRONG: '强',
    ACCELERATING: '加速',
    REPEAT_STRONG: '昨日板·强',
    REPEAT_ACCELERATING: '昨日板·加速'
  }[level]
}

function levelTone(level: FirstBoardAlertLevel): 'neutral' | 'brand' | 'success' | 'warning' {
  if (level === 'ACCELERATING' || level === 'REPEAT_ACCELERATING') return 'warning'
  if (level === 'STRONG' || level === 'REPEAT_STRONG') return 'success'
  return 'neutral'
}

function statusPresentation(snapshot: FirstBoardAlertSnapshot) {
  return {
    INITIALIZING: {
      label: '首次候选池准备中',
      className: 'border-sky-200 bg-sky-50 text-sky-800'
    },
    UPDATING: {
      label: '候选池数据更新中',
      className: 'border-sky-200 bg-sky-50 text-sky-800'
    },
    READY: {
      label: '盘中扫描已就绪',
      className: 'border-emerald-200 bg-emerald-50 text-emerald-800'
    },
    FAILED: {
      label: '候选池更新失败，推荐已暂停',
      className: 'border-red-200 bg-red-50 text-red-800'
    }
  }[snapshot.status]
}
