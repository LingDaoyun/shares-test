import { useEffect, useState } from 'react'
import { fetchShortTermIndustryLeaders } from '../../api/client'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Loader } from '../ui/Loader'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import type { ShortTermIndustryLeaderSnapshot } from '../../types'

export interface IndustryLeaderSelection {
  code: string
  name: string
  flow: 'INFLOW' | 'OUTFLOW'
}

type LeaderLoadState = 'LOADING' | 'READY' | 'FAILED'

// 今日资金去向的二级弹窗：点击行业板块后按当日主力净流入/流出展示板块内前 10 只个股。
export function IndustryLeaderOverlay({
  selection,
  onClose
}: {
  selection: IndustryLeaderSelection | null
  onClose: () => void
}) {
  const [snapshot, setSnapshot] = useState<ShortTermIndustryLeaderSnapshot | null>(null)
  const [state, setState] = useState<LeaderLoadState>('LOADING')
  const [retryCount, setRetryCount] = useState(0)

  useEffect(() => {
    if (!selection) return
    let cancelled = false
    setState('LOADING')
    setSnapshot(null)
    fetchShortTermIndustryLeaders(selection.code, {
      name: selection.name,
      flow: selection.flow,
      limit: 10
    })
      .then((data) => {
        if (cancelled) return
        setSnapshot(data)
        setState('READY')
      })
      .catch(() => {
        if (cancelled) return
        setSnapshot(null)
        setState('FAILED')
      })
    return () => {
      cancelled = true
    }
  }, [selection, retryCount])

  if (!selection) return null

  const rankLabel = selection.flow === 'OUTFLOW' ? '主力净流出前 10' : '主力净流入前 10'

  return (
    <DetailOverlay
      open
      title={`${selection.name} · 龙头股`}
      subtitle={`板块内个股按今日${selection.flow === 'OUTFLOW' ? '主力净流出' : '主力净流入'}排名 · ${rankLabel}`}
      onClose={onClose}
    >
      <div className="flex flex-col gap-3">
        {state === 'LOADING' ? <Loader text="板块龙头股资金流加载中" /> : null}

        {state === 'FAILED' ? (
          <div className="flex flex-col items-start gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            <span>板块个股资金流获取失败，请稍后重试。</span>
            <Button variant="secondary" onClick={() => setRetryCount((count) => count + 1)}>
              重试
            </Button>
          </div>
        ) : null}

        {state === 'READY' && snapshot ? (
          snapshot.stocks.length ? (
            <>
              {snapshot.dataGaps.length ? (
                <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
                  {snapshot.dataGaps.join('；')}
                </div>
              ) : null}
              <p className="text-xs leading-relaxed text-ink-500">
                排名口径：{selection.name} 板块成分股按当日主力净流入{selection.flow === 'OUTFLOW' ? '从低到高' : '从高到低'}排序，展示相对资金龙头，不构成买入建议。
                {snapshot.tradeDate ? ` 数据日 ${snapshot.tradeDate}。` : ''}
              </p>
              <div className="flex flex-col divide-y divide-line-soft rounded-xl border border-line-soft">
                {snapshot.stocks.map((stock) => (
                  <div
                    key={stock.symbol}
                    className="grid grid-cols-1 gap-2 px-3 py-2.5 md:grid-cols-[auto_minmax(0,1.2fr)_repeat(3,minmax(0,1fr))] md:items-center"
                  >
                    <span className="tabular text-sm font-semibold text-ink-400">#{stock.rank}</span>
                    <div className="min-w-0">
                      <div className="flex items-baseline gap-2">
                        <span className="truncate text-sm font-semibold text-ink-900">{stock.name}</span>
                        <span className="font-mono text-xs text-ink-400">{stock.symbol}</span>
                      </div>
                      <span className="text-[11px] text-ink-400">{formatDateTime(stock.marketTimestamp)}</span>
                    </div>
                    <div className="tabular text-sm">
                      <span className="text-xs text-ink-400">每股 </span>
                      <span className="font-semibold text-ink-900">{formatPerSharePrice(stock.latestPrice)}</span>
                    </div>
                    <div className="tabular text-sm">
                      <span className="text-xs text-ink-400">涨跌 </span>
                      <span className={`font-semibold ${changeClass(stock.changePercent)}`}>
                        {formatSignedPercent(stock.changePercent)}
                      </span>
                    </div>
                    <div className="tabular text-sm md:text-right">
                      <span className="text-xs text-ink-400">主力净流入 </span>
                      <span className={`font-semibold ${changeClass(stock.mainNetInflow)}`}>
                        {formatAmount(stock.mainNetInflow)}
                      </span>
                      <span className="ml-1 text-[11px] text-ink-400">占比 {formatNumber(stock.mainNetInflowRatio)}%</span>
                    </div>
                  </div>
                ))}
              </div>
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-ink-400">
                <span>来源：{snapshot.sourceName}{snapshot.fetchedAt ? ` · 拉取于 ${formatDateTime(snapshot.fetchedAt)}` : ''}</span>
                <Tag tone="neutral">{snapshot.stocks.length} 只</Tag>
              </div>
            </>
          ) : (
            <div className="rounded-lg border border-line-soft bg-ink-50 px-4 py-3 text-sm leading-relaxed text-ink-500">
              {snapshot.dataGaps.length ? snapshot.dataGaps.join('；') : '该板块暂未返回个股资金流数据。'}
            </div>
          )
        ) : null}
      </div>
    </DetailOverlay>
  )
}
