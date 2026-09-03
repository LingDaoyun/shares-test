import { Activity, DatabaseZap } from 'lucide-react'
import { formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { PotentialLeaderSnapshot } from '../../types'
import { Tag } from '../ui/Badge'
import { Card } from '../ui/Card'

interface LeaderMarketOverviewProps {
  snapshot: PotentialLeaderSnapshot
}

const statusPresentation = {
  PREPARING: {
    label: '候选池准备中',
    className: 'border-sky-200 bg-sky-50 text-sky-800'
  },
  READY: {
    label: '双通道排名已就绪',
    className: 'border-emerald-200 bg-emerald-50 text-emerald-800'
  },
  STALE: {
    label: '数据已陈旧，仅供查看',
    className: 'border-amber-200 bg-amber-50 text-amber-800'
  },
  UNAVAILABLE: {
    label: '核心数据不可用，未形成排名',
    className: 'border-red-200 bg-red-50 text-red-800'
  }
} as const

export function LeaderMarketOverview({ snapshot }: LeaderMarketOverviewProps) {
  const status = statusPresentation[snapshot.status]
  const market = snapshot.marketSummary

  return (
    <Card
      title={(
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <span>市场与最强方向</span>
            <Tag tone="neutral">{snapshot.ruleVersion}</Tag>
          </div>
          <p className="mt-1 text-xs font-normal text-ink-500">
            行情 {formatDateTime(snapshot.quoteTimestamp)}（交易日 {snapshot.quoteTradeDate ?? '—'}）
            {' · '}龙虎榜披露日 {snapshot.dragonTigerDate ?? '未披露'}
          </p>
        </div>
      )}
    >
      <div className={`rounded-xl border px-3.5 py-3 ${status.className}`}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4" />
            <span className="text-sm font-semibold">{status.label}</span>
            <span className="tabular text-xs opacity-75">扫描日 {snapshot.tradeDate}</span>
          </div>
          <span className="text-xs opacity-75">
            {snapshot.autoRefreshAllowed
              ? '盘中每 60 秒自动刷新'
              : snapshot.status === 'PREPARING' || snapshot.status === 'UNAVAILABLE'
                ? '每 60 秒检查数据是否就绪'
                : '当前仅手动刷新'}
          </span>
        </div>
        {snapshot.dataGaps.length ? (
          <ul className="mt-2 space-y-1 border-t border-current/10 pt-2 text-xs leading-relaxed">
            {snapshot.dataGaps.map((gap, index) => <li key={`${gap}-${index}`}>数据缺口：{gap}</li>)}
          </ul>
        ) : null}
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-5">
        <Metric label="涨停" value={market ? `${market.limitUpCount} 家` : '待补充'} />
        <Metric label="炸板" value={market?.brokenCount === null || !market ? '待补充' : `${market.brokenCount} 家`} />
        <Metric label="最高板" value={market ? `${market.maxBoard} 板` : '待补充'} />
        <Metric
          label="接力情绪"
          value={market?.sentimentTone ?? '待补充'}
          detail={market ? `封板率 ${formatPercent(market.sealRatePercent)} · 2板+ ${formatPercent(market.boards2PlusRatioPercent)}` : undefined}
        />
        <Metric
          label="数据覆盖"
          value={`${snapshot.coverage.liquidCount} / ${snapshot.coverage.scopedCount}`}
          detail={`强方向 ${snapshot.coverage.strongDirectionCount} · 缺字段排除 ${snapshot.coverage.excludedMissingDataCount}`}
        />

        {snapshot.directions.slice(0, 5).map((direction) => (
          <article
            key={direction.industry}
            className="rounded-xl border border-line-soft bg-line-soft/20 px-3.5 py-3"
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold text-ink-900">
                  <span className="tabular mr-1.5 text-brand-600">#{direction.rank}</span>
                  {direction.industry}
                </div>
                <div className="mt-1 text-xs text-ink-500">
                  广度 {formatPercent(direction.breadthPercent)} · 中位涨幅 {formatSignedPercent(direction.medianChangePercent)}
                </div>
              </div>
              <span className="tabular text-base font-semibold text-brand-600">{Number(direction.score).toFixed(1)}</span>
            </div>
            <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-ink-400">
              <span>涨停 {direction.limitUpCount}</span>
              <span>炸板 {direction.brokenCount}</span>
              <span>最高 {direction.maxBoard} 板</span>
              <span>{formatAmount(direction.amount)}</span>
            </div>
          </article>
        ))}

        {!snapshot.directions.length ? (
          <div className="flex min-h-[92px] items-center gap-2 rounded-xl border border-dashed border-line px-3.5 py-3 text-sm text-ink-400 sm:col-span-2">
            <DatabaseZap className="h-4 w-4" />
            最强方向尚未形成
          </div>
        ) : null}
      </div>
    </Card>
  )
}

function Metric({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="rounded-xl border border-line-soft bg-white px-3.5 py-3">
      <div className="text-[11px] text-ink-400">{label}</div>
      <div className="tabular mt-1 text-lg font-semibold text-ink-900">{value}</div>
      {detail ? <div className="mt-1 text-[11px] leading-relaxed text-ink-400">{detail}</div> : null}
    </div>
  )
}
