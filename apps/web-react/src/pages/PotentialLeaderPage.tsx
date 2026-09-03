import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import {
  fetchPotentialLeaderBars,
  fetchPotentialLeaderSnapshot,
  requestPotentialLeaderAiReview
} from '../api/client'
import { LeaderCandidateTable } from '../components/leader/LeaderCandidateTable'
import { LeaderDetailOverlay } from '../components/leader/LeaderDetailOverlay'
import { LeaderMarketOverview } from '../components/leader/LeaderMarketOverview'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Loader } from '../components/ui/Loader'
import { SectionBanner } from '../components/ui/SectionBanner'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage, formatDateTime, formatNumber, formatSignedPercent } from '../lib/format'
import { collectPotentialLeaderNotifications } from '../lib/potentialLeaderNotifications'
import {
  loadPotentialLeaderPreferences,
  savePotentialLeaderPreferences
} from '../lib/potentialLeaderPreferences'
import type {
  LeaderAiReview,
  LeaderBarSeries,
  PotentialLeaderCandidate,
  PotentialLeaderPreferences,
  PotentialLeaderSnapshot
} from '../types'

const REFRESH_INTERVAL_MS = 60_000

export function PotentialLeaderPage() {
  const [preferences, setPreferences] = useState<PotentialLeaderPreferences>(() => loadPotentialLeaderPreferences())
  const [snapshot, setSnapshot] = useState<PotentialLeaderSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<PotentialLeaderCandidate | null>(null)
  const [barSeries, setBarSeries] = useState<LeaderBarSeries | null>(null)
  const [barLoading, setBarLoading] = useState(false)
  const [barError, setBarError] = useState<string | null>(null)
  const [aiReview, setAiReview] = useState<LeaderAiReview | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiError, setAiError] = useState<string | null>(null)
  const requestId = useRef(0)
  const barRequestId = useRef(0)
  const aiRequestId = useRef(0)

  const load = useCallback(async () => {
    const currentRequest = ++requestId.current
    setLoading(true)
    setError(null)
    try {
      const next = await fetchPotentialLeaderSnapshot(preferences)
      if (currentRequest !== requestId.current) return
      setSnapshot(next)
      if (next.status === 'READY') {
        for (const candidate of collectPotentialLeaderNotifications(next)) {
          const lane = candidate.lane === 'POTENTIAL' ? '潜龙' : '竞龙'
          toast.success(
            `${lane} A级 · ${candidate.name} ${formatNumber(candidate.latestPrice)} / ${formatSignedPercent(candidate.changePercent)}`,
            {
              key: `potential-leader:${next.tradeDate}:${candidate.lane}:${candidate.symbol}`,
              durationMs: 5200
            }
          )
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
    || snapshot?.status === 'PREPARING'
    || snapshot?.status === 'UNAVAILABLE'

  useEffect(() => {
    if (!shouldPoll) return
    const timer = window.setInterval(() => void load(), REFRESH_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [load, shouldPoll])

  const updatePreferences = (next: PotentialLeaderPreferences) => {
    requestId.current += 1
    barRequestId.current += 1
    aiRequestId.current += 1
    savePotentialLeaderPreferences(next)
    setPreferences(next)
    setSnapshot(null)
    closeDetails()
  }

  const openDetails = async (candidate: PotentialLeaderCandidate) => {
    const currentRequest = ++barRequestId.current
    aiRequestId.current += 1
    setSelected(candidate)
    setBarSeries(null)
    setBarError(null)
    setBarLoading(true)
    setAiReview(null)
    setAiError(null)
    setAiLoading(false)
    try {
      const bars = await fetchPotentialLeaderBars(candidate.symbol)
      if (currentRequest === barRequestId.current) setBarSeries(bars)
    } catch (caught) {
      if (currentRequest === barRequestId.current) setBarError(extractErrorMessage(caught))
    } finally {
      if (currentRequest === barRequestId.current) setBarLoading(false)
    }
  }

  const requestAiReview = async () => {
    if (!selected || snapshot?.status !== 'READY') return
    const candidate = selected
    const currentRequest = ++aiRequestId.current
    setAiLoading(true)
    setAiReview(null)
    setAiError(null)
    try {
      const review = await requestPotentialLeaderAiReview(candidate)
      if (currentRequest === aiRequestId.current) setAiReview(review)
    } catch (caught) {
      if (currentRequest === aiRequestId.current) setAiError(extractErrorMessage(caught))
    } finally {
      if (currentRequest === aiRequestId.current) setAiLoading(false)
    }
  }

  const closeDetails = () => {
    barRequestId.current += 1
    aiRequestId.current += 1
    setSelected(null)
    setBarSeries(null)
    setBarError(null)
    setBarLoading(false)
    setAiReview(null)
    setAiError(null)
    setAiLoading(false)
  }

  const selectedDirection = useMemo(() => {
    if (!selected || !snapshot) return null
    return snapshot.directions.find((direction) => direction.industry === selected.industry) ?? null
  }, [selected, snapshot])

  return (
    <div className="flex flex-col gap-4">
      <SectionBanner
        eyebrow="POTENTIAL LEADER"
        title="潜力龙头"
        description="主线共振与板块辨识度形成正式双通道排名；龙虎榜仅作竞龙软增强，AI 与历史结果保持影子展示。"
        extra={(
          <div className="flex items-center gap-3">
            {snapshot?.quoteTimestamp ? (
              <span className="tabular text-xs text-ink-400">行情 {formatDateTime(snapshot.quoteTimestamp)}</span>
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
            <div className="text-sm font-semibold text-ink-900">候选范围</div>
            <p className="mt-1 text-xs leading-relaxed text-ink-500">
              主板始终启用；创业板、科创板可按需加入。ST、退市整理、停牌、关键数据不完整和非强方向股票均排除。
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
      {loading && !snapshot ? <Card><Loader text="主线与双通道候选加载中" /></Card> : null}

      {snapshot ? (
        <>
          <LeaderMarketOverview snapshot={snapshot} />
          <div className="grid min-w-0 grid-cols-1 gap-4 2xl:grid-cols-2">
            <LeaderCandidateTable
              lane="POTENTIAL"
              status={snapshot.status}
              candidates={snapshot.potentialCandidates}
              directions={snapshot.directions}
              selected={selected}
              onSelect={(candidate) => void openDetails(candidate)}
            />
            <LeaderCandidateTable
              lane="COMPETITION"
              status={snapshot.status}
              candidates={snapshot.competitionCandidates}
              directions={snapshot.directions}
              selected={selected}
              onSelect={(candidate) => void openDetails(candidate)}
            />
          </div>
          <Card className="text-xs leading-relaxed text-ink-500">
            本模块用于研究与候选排序，不执行自动交易、不承诺收益。龙虎榜是触发披露后的局部视图，营业部名称不等于特定游资身份。
          </Card>
        </>
      ) : null}

      <LeaderDetailOverlay
        candidate={selected}
        direction={selectedDirection}
        snapshotStatus={snapshot?.status ?? null}
        barSeries={barSeries}
        barLoading={barLoading}
        barError={barError}
        aiReview={aiReview}
        aiLoading={aiLoading}
        aiError={aiError}
        onRequestAi={() => void requestAiReview()}
        onClose={closeDetails}
      />
    </div>
  )
}

export function LegacyFirstBoardRedirect() {
  return <Navigate to="/potential-leader" replace />
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
