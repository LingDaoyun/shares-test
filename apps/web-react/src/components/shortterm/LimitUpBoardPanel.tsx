import { useCallback, useEffect, useState } from 'react'
import { Flame, RefreshCw } from 'lucide-react'
import { fetchShortTermLimitUpBoard } from '../../api/client'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Loader } from '../ui/Loader'
import { Tag } from '../ui/Badge'
import { formatAmount, formatDateTime, formatPercent } from '../../lib/format'
import type { ShortTermLimitUpBoardSnapshot, ShortTermLimitUpSentiment } from '../../types'

const toneLabel: Record<string, { tone: 'success' | 'brand' | 'warning' | 'danger' | 'neutral'; label: string }> = {
  接力退潮: { tone: 'danger', label: '接力退潮' },
  情绪冰点: { tone: 'warning', label: '情绪冰点' },
  情绪强势: { tone: 'success', label: '情绪强势' },
  情绪偏暖: { tone: 'brand', label: '情绪偏暖' },
  中性震荡: { tone: 'neutral', label: '中性震荡' }
}

export function LimitUpBoardPanel() {
  const [snapshot, setSnapshot] = useState<ShortTermLimitUpBoardSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSnapshot(await fetchShortTermLimitUpBoard())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '涨停看板获取失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return (
    <LimitUpBoardView
      snapshot={snapshot}
      loading={loading}
      error={error}
      onRefresh={() => void refresh()}
    />
  )
}

interface LimitUpBoardViewProps {
  snapshot: ShortTermLimitUpBoardSnapshot | null
  loading: boolean
  error: string | null
  onRefresh: () => void
}

export function LimitUpBoardView({ snapshot, loading, error, onRefresh }: LimitUpBoardViewProps) {
  return (
    <Card
      title={
        <span className="inline-flex items-center gap-2">
          <Flame className="h-4 w-4 text-rose-500" />
          涨停看板 · 市场情绪
        </span>
      }
      extra={
        <div className="flex items-center gap-2">
          <span className="text-xs text-ink-400">
            {snapshot ? `${snapshot.tradeDate} · 快照 ${formatDateTime(snapshot.fetchedAt)}` : '未加载'}
          </span>
          <Button variant="secondary" icon={<RefreshCw className="h-4 w-4" />} loading={loading} onClick={onRefresh}>
            刷新
          </Button>
        </div>
      }
    >
      {error ? (
        <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs leading-relaxed text-rose-700">
          涨停看板获取失败：{error}
        </p>
      ) : null}
      {!error && !snapshot && loading ? <Loader text="正在拉取涨停池数据" /> : null}
      {!error && snapshot && !snapshot.available ? (
        <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
          {snapshot.unavailableReason ?? '涨停池数据不可用'}
        </p>
      ) : null}
      {!error && snapshot?.available && snapshot.sentiment ? (
        <div className="flex flex-col gap-4">
          <SentimentSummary sentiment={snapshot.sentiment} stockCount={snapshot.stocks.length} />
          <IndustryBoard snapshot={snapshot} />
          <LimitUpTable snapshot={snapshot} />
          <p className="text-[11px] leading-relaxed text-ink-400">
            {snapshot.sentiment.explanation}盘中数据为快照口径，涨停与炸板数量随行情变化，不代表收盘结论。
          </p>
          {snapshot.dataGaps.length ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
              {snapshot.dataGaps.join('；')}
            </div>
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}

function SentimentSummary({ sentiment, stockCount }: { sentiment: ShortTermLimitUpSentiment; stockCount: number }) {
  const tone = toneLabel[sentiment.tone] ?? { tone: 'neutral' as const, label: sentiment.tone }
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={tone.tone}>{tone.label}</Tag>
        <span className="text-xs text-ink-400">共 {stockCount} 只涨停</span>
      </div>
      <dl className="grid grid-cols-2 border-t border-line-soft text-xs md:grid-cols-4">
        <Metric label="涨停" value={`${sentiment.limitUpCount} 家`} />
        <Metric
          label="炸板(炸板率)"
          value={sentiment.brokenCount === null ? '待补充' : `${sentiment.brokenCount} 家（${formatPercent(sentiment.sealBreakRatioPercent)}）`}
        />
        <Metric label="跌停" value={sentiment.limitDownCount === null ? '待补充' : `${sentiment.limitDownCount} 家`} />
        <Metric label="最高连板" value={`${sentiment.maxConsecutiveBoards} 板`} />
        <Metric label="2 板以上" value={`${sentiment.boards2PlusCount} 家`} />
        <Metric label="3 板以上" value={`${sentiment.boards3PlusCount} 家`} />
        <Metric label="10 点前封板占比" value={formatPercent(sentiment.earlySealSharePercent)} />
        <Metric label="尾盘封板" value={`${sentiment.sealedTailCount} 家`} />
      </dl>
      <div className="grid grid-cols-4 gap-1 text-center text-[11px] text-ink-500">
        <SealBucket label="10点前" count={sentiment.sealedBeforeTenCount} total={sentiment.limitUpCount} />
        <SealBucket label="10-11:30" count={sentiment.sealedMorningCount} total={sentiment.limitUpCount} />
        <SealBucket label="11:30-14:30" count={sentiment.sealedAfternoonCount} total={sentiment.limitUpCount} />
        <SealBucket label="14:30后" count={sentiment.sealedTailCount} total={sentiment.limitUpCount} />
      </div>
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-b border-r border-line-soft px-2 py-2">
      <dt className="text-[11px] text-ink-400">{label}</dt>
      <dd className="mt-0.5 break-words font-semibold text-ink-900">{value}</dd>
    </div>
  )
}

function SealBucket({ label, count, total }: { label: string; count: number; total: number }) {
  const ratio = total > 0 ? Math.min(1, count / total) : 0
  return (
    <div className="flex flex-col gap-1">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-line">
        <div className="h-full rounded-full bg-rose-400" style={{ width: `${ratio * 100}%` }} />
      </div>
      <span>
        {label} {count} 家
      </span>
    </div>
  )
}

function IndustryBoard({ snapshot }: { snapshot: ShortTermLimitUpBoardSnapshot }) {
  if (!snapshot.industryStats.length) {
    return <p className="text-xs leading-relaxed text-ink-500">行业聚合暂无数据。</p>
  }
  const max = Math.max(...snapshot.industryStats.map((stat) => stat.limitUpCount))
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-ink-900">行业聚合（当日主线）</h4>
      <div className="grid grid-cols-1 gap-1.5 md:grid-cols-2">
        {snapshot.industryStats.slice(0, 8).map((stat) => (
          <div key={stat.industry} className="rounded-xl border border-line-soft bg-white/80 px-3 py-2">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate text-sm font-semibold text-ink-900">{stat.industry}</span>
              <Tag tone={stat.maxConsecutiveBoards >= 3 ? 'danger' : 'warning'}>
                {stat.limitUpCount} 家 · 最高 {stat.maxConsecutiveBoards} 板
              </Tag>
            </div>
            <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-line">
              <div
                className="h-full rounded-full bg-amber-400"
                style={{ width: `${max > 0 ? (stat.limitUpCount / max) * 100 : 0}%` }}
              />
            </div>
            {stat.leaders.length ? (
              <p className="mt-1 truncate text-xs text-ink-400">领涨：{stat.leaders.join('、')}</p>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  )
}

function LimitUpTable({ snapshot }: { snapshot: ShortTermLimitUpBoardSnapshot }) {
  if (!snapshot.stocks.length) {
    return <p className="text-xs leading-relaxed text-ink-500">涨停明细暂无数据。</p>
  }
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-ink-900">涨停明细（按连板高度 → 首次封板时间排序）</h4>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[760px] border-collapse text-left text-xs">
          <thead>
            <tr className="border-b border-line-soft text-[11px] text-ink-400">
              <th className="px-2 py-1.5 font-medium">名称/代码</th>
              <th className="px-2 py-1.5 font-medium">行业</th>
              <th className="px-2 py-1.5 font-medium">连板</th>
              <th className="px-2 py-1.5 font-medium">首封</th>
              <th className="px-2 py-1.5 font-medium">末封</th>
              <th className="px-2 py-1.5 font-medium">炸板</th>
              <th className="px-2 py-1.5 font-medium">封板资金</th>
              <th className="px-2 py-1.5 font-medium">成交额</th>
              <th className="px-2 py-1.5 font-medium">换手率</th>
            </tr>
          </thead>
          <tbody>
            {snapshot.stocks.map((stock) => (
              <tr key={stock.symbol} className="border-b border-line-soft last:border-b-0">
                <td className="px-2 py-1.5">
                  <span className="font-semibold text-ink-900">{stock.name}</span>
                  <span className="ml-1.5 text-[11px] text-ink-400">{stock.symbol}</span>
                </td>
                <td className="px-2 py-1.5 text-ink-600">{stock.industry ?? '未分类'}</td>
                <td className="px-2 py-1.5">
                  <Tag tone={stock.consecutiveBoards >= 3 ? 'danger' : stock.consecutiveBoards >= 2 ? 'warning' : 'neutral'}>
                    {stock.statDays > 0 ? `${stock.statDays}天${stock.statBoards > 0 ? stock.statBoards : stock.consecutiveBoards}板` : `${stock.consecutiveBoards} 板`}
                  </Tag>
                </td>
                <td className="px-2 py-1.5 tabular text-ink-600">{shortTime(stock.firstSealTime)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{shortTime(stock.lastSealTime)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{stock.sealBreakCount > 0 ? `${stock.sealBreakCount} 次` : '—'}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatAmount(stock.sealFunds)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatAmount(stock.amount)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatPercent(stock.turnoverRate)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function shortTime(value: string | null) {
  return value ? value.slice(0, 5) : '—'
}
