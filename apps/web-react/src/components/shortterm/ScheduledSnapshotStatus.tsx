import { CalendarClock, Database, ShieldCheck } from 'lucide-react'
import { formatDateTime, formatRatioPercent } from '../../lib/format'
import type { ShortTermScheduledSnapshot, ShortTermSnapshotStatus } from '../../types'

export type ReportOrigin = 'SCHEDULED' | 'MANUAL'

interface ScheduledSnapshotStatusProps {
  snapshot: ShortTermScheduledSnapshot
  origin: ReportOrigin
}

const toneClasses: Record<ShortTermSnapshotStatus, string> = {
  FINAL_READY: 'border-emerald-200 bg-emerald-50/60 text-emerald-900',
  PRESELECT_READY: 'border-line bg-white text-ink-800',
  RUNNING: 'border-line bg-white text-ink-800',
  NO_TRADE: 'border-amber-200 bg-amber-50/70 text-amber-900',
  DATA_BLOCKED: 'border-red-200 bg-red-50/70 text-red-900',
  FAILED: 'border-red-200 bg-red-50/70 text-red-900'
}

export function ScheduledSnapshotStatus({ snapshot, origin }: ScheduledSnapshotStatusProps) {
  const coverage = snapshot.report?.coverage
  const label = statusLabel(snapshot.status, origin)

  return (
    <section className={`border px-4 py-3 ${toneClasses[snapshot.status]}`} aria-live="polite">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <ShieldCheck className="h-4 w-4" aria-hidden="true" />
            <h2 className="text-sm font-semibold">{label}</h2>
            <span className="border border-current/20 px-2 py-0.5 text-xs">
              {origin === 'SCHEDULED' ? '计划任务' : '手动重算'}
            </span>
          </div>
          {snapshot.message && snapshot.message !== label ? (
            <p className="mt-1 text-xs leading-relaxed opacity-80">{snapshot.message}</p>
          ) : null}
          {snapshot.blockedReasons.length ? (
            <p className="mt-1 text-xs leading-relaxed opacity-80">{snapshot.blockedReasons.join('；')}</p>
          ) : null}
        </div>
        <div className="flex items-center gap-1 text-xs opacity-75">
          <CalendarClock className="h-3.5 w-3.5" aria-hidden="true" />
          <span>{snapshot.tradeDate}</span>
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-current/10 pt-3 text-xs md:grid-cols-5">
        <StatusMetric label="数据截止" value={formatDateTime(snapshot.dataCutoffAt)} />
        <StatusMetric label="完成时间" value={formatDateTime(snapshot.completedAt)} />
        <StatusMetric
          label="市场覆盖"
          value={coverage
            ? `${formatRatioPercent(coverage.coverageRatio)} · ${coverage.fetchedCount}/${coverage.expectedCount}`
            : '待生成'}
        />
        <StatusMetric label="数据来源" value={coverage?.source ?? '待生成'} icon={<Database className="h-3 w-3" />} />
        <StatusMetric label="策略版本" value={snapshot.strategyVersion || '待生成'} />
      </div>
    </section>
  )
}

function StatusMetric({ label, value, icon }: { label: string; value: string; icon?: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="opacity-60">{label}</div>
      <div className="mt-0.5 flex items-center gap-1 break-words font-medium">
        {icon}
        <span>{value}</span>
      </div>
    </div>
  )
}

function statusLabel(status: ShortTermSnapshotStatus, origin: ReportOrigin) {
  if (origin === 'MANUAL') {
    switch (status) {
      case 'FINAL_READY':
        return '手动最终结果已就绪'
      case 'PRESELECT_READY':
        return '手动预选已就绪'
      case 'RUNNING':
        return '手动扫描执行中'
      case 'NO_TRADE':
        return '手动扫描：今日不交易'
      case 'DATA_BLOCKED':
        return '手动扫描：数据质量阻断'
      case 'FAILED':
        return '手动扫描失败'
    }
  }
  switch (status) {
    case 'FINAL_READY':
      return '尾盘最终结果已就绪'
    case 'PRESELECT_READY':
      return '自动预选已就绪'
    case 'RUNNING':
      return '自动任务执行中'
    case 'NO_TRADE':
      return '今日不交易'
    case 'DATA_BLOCKED':
      return '数据质量阻断'
    case 'FAILED':
      return '自动任务失败'
  }
}
