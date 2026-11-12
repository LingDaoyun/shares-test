import { useCallback, useEffect, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import { fetchShortTermMarketFundDirection } from '../../api/client'
import type { ShortTermMarketFundDirection } from '../../types'
import { Button } from '../ui/Button'
import { isEscapeClose } from '../ui/DetailOverlay'
import { Loader } from '../ui/Loader'
import { IndustryLeaderOverlay, type IndustryLeaderSelection } from './IndustryLeaderOverlay'
import { MarketFundDirectionCard } from './MarketFundDirectionCard'

export function MarketFundFlowPanel({ refreshKey }: { refreshKey: number }) {
  const [direction, setDirection] = useState<ShortTermMarketFundDirection | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [leaderSelection, setLeaderSelection] = useState<IndustryLeaderSelection | null>(null)
  const mounted = useRef(false)
  const refreshing = useRef(false)

  const refresh = useCallback(async () => {
    if (refreshing.current) return
    refreshing.current = true
    setLoading(true)
    setError(null)
    try {
      const next = await fetchShortTermMarketFundDirection()
      if (mounted.current) {
        if (next.coveredIndustryCount === 0 && !next.topInflows.length && !next.topOutflows.length) {
          setError(next.dataGaps.join('；') || '来源未返回可用的行业资金流')
          setDirection(previous => previous ?? next)
        } else {
          setDirection(next)
        }
      }
    } catch (reason: unknown) {
      if (mounted.current) setError(reason instanceof Error ? reason.message : '请稍后重试')
    } finally {
      refreshing.current = false
      if (mounted.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  // Run independently of the limit-up pool and breadth requests; one slow source must not block the board.
  useEffect(() => { void refresh() }, [refreshKey, refresh])

  return (
    <section aria-label="资金流向看板" className="mt-5 flex flex-col gap-3 border-t border-line-soft pt-4"
      onKeyDown={event => {
        // Escape can arrive on the industry row before the nested dialog receives focus.
        if (leaderSelection && isEscapeClose(event.key, event.defaultPrevented)) {
          event.preventDefault()
          event.stopPropagation()
          setLeaderSelection(null)
        }
      }}>
      <div className="flex justify-end">
        <Button type="button" loading={loading} icon={<RefreshCw aria-hidden="true" className="h-4 w-4" />}
          onClick={() => void refresh()}>刷新资金流向</Button>
      </div>
      {loading ? <Loader text="资金流向加载中" /> : null}
      {error ? (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
          资金流向更新失败：{error}{direction && direction.coveredIndustryCount > 0 ? '；当前保留上次快照，请注意数据日期。' : ''}
        </p>
      ) : null}
      <MarketFundDirectionCard title="资金流向看板" wide direction={direction}
        onSelectIndustry={(item, flow) => setLeaderSelection({ code: item.code, name: item.name || item.code || '未知板块', flow })} />
      <IndustryLeaderOverlay selection={leaderSelection} onClose={() => setLeaderSelection(null)} />
    </section>
  )
}
