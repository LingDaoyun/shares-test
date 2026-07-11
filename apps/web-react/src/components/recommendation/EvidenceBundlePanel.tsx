import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { RefreshCw } from 'lucide-react'
import { fetchRecommendationEvidence } from '../../api/client'
import { extractErrorMessage, formatNumber } from '../../lib/format'
import type { RecommendationEvidenceBundle } from '../../types'
import { Tag } from '../ui/Badge'

interface RecommendationEvidenceBundlePanelProps {
  symbol: string
  bundle: RecommendationEvidenceBundle
  compact?: boolean
}

export function RecommendationEvidenceBundlePanel({
  symbol,
  bundle,
  compact = false
}: RecommendationEvidenceBundlePanelProps) {
  const [resolvedBundle, setResolvedBundle] = useState(bundle)
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')

  useEffect(() => {
    let alive = true
    let retryTimer: number | undefined
    setResolvedBundle(bundle)
    setLoadError('')
    if (!symbol || !needsInteractiveRefresh(bundle)) {
      setLoading(false)
      return () => {
        alive = false
        if (retryTimer) window.clearTimeout(retryTimer)
      }
    }

    const loadEvidence = (attempt: number) => {
      setLoading(true)
      fetchRecommendationEvidence(symbol)
        .then((data) => {
          if (!alive) return
          setResolvedBundle(data)
          if (needsInteractiveRefresh(data) && attempt < 2) {
            retryTimer = window.setTimeout(() => loadEvidence(attempt + 1), 12_000)
          }
        })
        .catch((error) => {
          if (!alive) return
          setLoadError(extractErrorMessage(error))
          if (attempt < 2) {
            retryTimer = window.setTimeout(() => loadEvidence(attempt + 1), 12_000)
          }
        })
        .finally(() => {
          if (alive) setLoading(false)
        })
    }

    loadEvidence(0)

    return () => {
      alive = false
      if (retryTimer) window.clearTimeout(retryTimer)
    }
  }, [symbol, bundle])

  const peerItems = resolvedBundle.peerValuation.peers.slice(0, 4).map((peer) => (
    `${peer.symbol} ${peer.companyName} · PE ${formatNumber(peer.peTtm)} · PB ${formatNumber(peer.pbRatio)}`
  ))
  const agentItems = [
    resolvedBundle.agentConsensus.contrarianSummary,
    ...resolvedBundle.agentConsensus.objections.slice(0, 3)
  ].filter(Boolean)
  const gapItems = useMemo(() => {
    if (loading && !bundleHasEvidence(resolvedBundle)) {
      return ['正在拉取同业估值、财报质量和多 Agent 共识证据。']
    }
    return uniqueItems([
      ...resolvedBundle.dataGaps,
      ...resolvedBundle.agentConsensus.requiredEvidence,
      loadError ? `证据接口异常：${loadError}` : ''
    ])
  }, [loading, loadError, resolvedBundle])
  const peerLabel = loading && !resolvedBundle.peerValuation.available
    ? '同业估值复核中'
    : normalizeUnavailableLabel(resolvedBundle.peerValuation.scopeLabel, '同业估值缺口')
  const consensusLabel = loading && !resolvedBundle.agentConsensus.available
    ? 'Agent 共识复核中'
    : normalizeUnavailableLabel(resolvedBundle.agentConsensus.consensusLabel, 'Agent 共识缺口')

  return (
    <div className="rounded-lg border border-line-soft bg-line-soft/30 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Tag tone="neutral">交叉验证</Tag>
          <Tag tone={resolvedBundle.peerValuation.available ? 'success' : 'warning'}>{peerLabel}</Tag>
          <Tag tone={resolvedBundle.agentConsensus.vetoCount > 0 ? 'danger' : resolvedBundle.agentConsensus.available ? 'success' : 'warning'}>
            {consensusLabel}
          </Tag>
          {loading ? (
            <span className="inline-flex items-center gap-1 text-xs font-medium text-brand-600">
              <RefreshCw className="h-3.5 w-3.5 animate-spin" />
              证据复核中
            </span>
          ) : null}
        </div>
        <span className="text-xs text-ink-400">
          支持 {resolvedBundle.agentConsensus.supportCount} · 观察 {resolvedBundle.agentConsensus.watchCount} · 复核 {resolvedBundle.agentConsensus.reviewCount} · 否决 {resolvedBundle.agentConsensus.vetoCount}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2 md:grid-cols-4">
        <Metric label="可比公司" value={resolvedBundle.peerValuation.peerCount} compact={compact} />
        <Metric label="同业PE中位" value={formatNumber(resolvedBundle.peerValuation.medianPe)} compact={compact} />
        <Metric label="同业PB中位" value={formatNumber(resolvedBundle.peerValuation.medianPb)} compact={compact} />
        <Metric label="Agent共识" value={formatNumber(resolvedBundle.agentConsensus.consensusScore)} compact={compact} />
      </div>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-3">
        <ListBlock
          title="可比样本"
          items={peerItems.length ? peerItems : peerFallbackItems(resolvedBundle, loading)}
          tone="brand"
        />
        <ListBlock title="反方意见" items={agentItems.length ? agentItems : ['暂无反方硬性否决']} tone="warning" />
        <ListBlock title="证据缺口" items={gapItems.length ? gapItems : ['当前交叉验证已补齐']} tone={loadError ? 'danger' : 'warning'} />
      </div>
    </div>
  )
}

function needsInteractiveRefresh(bundle: RecommendationEvidenceBundle) {
  return !bundle.peerValuation.available
    || !bundle.agentConsensus.available
    || hasQueuedGap(bundle.dataGaps)
}

function bundleHasEvidence(bundle: RecommendationEvidenceBundle) {
  return bundle.peerValuation.available || bundle.agentConsensus.available
}

function normalizeUnavailableLabel(label: string, fallback: string) {
  return label.includes('待补') ? fallback : label
}

function hasQueuedGap(items: string[]) {
  return items.some((item) => item.includes('后台队列') || item.includes('刷新后显示结果'))
}

function peerFallbackItems(bundle: RecommendationEvidenceBundle, loading: boolean) {
  if (loading) {
    return ['正在拉取同行业可比公司的 PE/PB 样本。']
  }
  if (bundle.peerValuation.dataGaps.length) {
    return bundle.peerValuation.dataGaps
  }
  return ['同业样本不足，需补充可比公司估值。']
}

function uniqueItems(items: string[]) {
  return Array.from(new Set(items.filter((item) => item && item.trim().length > 0)))
}

function Metric({ label, value, compact = false }: { label: string; value: ReactNode; compact?: boolean }) {
  return (
    <div className={`rounded-lg border border-line-soft bg-line-soft/40 ${compact ? 'px-2.5 py-2' : 'px-3 py-2'}`}>
      <div className="text-xs text-ink-400">{label}</div>
      <div className="mt-1 break-words tabular text-sm font-semibold text-ink-900">{value}</div>
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
