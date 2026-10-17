import { useEffect, useState } from 'react'
import { fetchDailyShortTermSnapshot, fetchShortTermResultCache, retryDailyShortTermSnapshot, type ShortTermDailySnapshot } from '../../api/dailyShortTerm'
import { useShortTermScanStore } from '../../store/shortTermScanStore'
import { formatDateTime } from '../../lib/format'
import { Button } from '../ui/Button'

function marketDate() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}

export function DailyShortTermPanel() {
  const [snapshot, setSnapshot] = useState<ShortTermDailySnapshot | null>(null)
  const [cachedResult, setCachedResult] = useState<ShortTermDailySnapshot | null>(null)
  const [error, setError] = useState('')
  const [reading, setReading] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const manualLoading = useShortTermScanStore(state => state.loading)
  const metadata = useShortTermScanStore(state => state.resultMetadata)
  const upperShadow = useShortTermScanStore(state => state.upperShadow)
  const applyScanResult = useShortTermScanStore(state => state.applyScanResult)

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      setReading(true)
      let nextDelay = 60000
      try {
        const [task, cache] = await Promise.allSettled([fetchDailyShortTermSnapshot(), fetchShortTermResultCache()])
        if (cancelled) return
        const errors: string[] = []
        if (task.status === 'fulfilled') {
          setSnapshot(task.value)
          if (task.value.status === 'RUNNING') nextDelay = 15000
        } else errors.push(task.reason instanceof Error ? task.reason.message : '定时任务状态读取失败')
        if (cache.status === 'fulfilled') {
          setCachedResult(cache.value)
          if (cache.value.tradeDate === marketDate()) applyScanResult(cache.value)
        } else errors.push(cache.reason instanceof Error ? cache.reason.message : '结果缓存读取失败')
        setError(errors.join('；'))
      } catch (reason) {
        if (!cancelled) setError(reason instanceof Error ? reason.message : '定时结果读取失败')
      } finally {
        if (!cancelled) { setReading(false); timer = setTimeout(() => void load(), nextDelay) }
      }
    }
    void load()
    return () => { cancelled = true; if (timer) clearTimeout(timer) }
  }, [applyScanResult])

  const historical = cachedResult !== null && cachedResult.tradeDate !== marketDate()
  const dataGaps = [...new Set([...(metadata?.dataGaps ?? []), ...(upperShadow?.dataGaps ?? [])])]
  return (
    <div aria-label="已保存候选状态" className="flex flex-col gap-2 border-b border-line-soft px-5 py-3 text-xs">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {metadata ? (
          <div className="flex flex-wrap gap-x-3 gap-y-1 text-ink-600" role="status">
            <span className="font-semibold">{metadata.tradeDate !== marketDate() ? '历史结果 · ' : ''}{metadata.tradeDate}</span>
            <span>{metadata.message}</span>
            {metadata.finishedAt ? <span>完成 {formatDateTime(metadata.finishedAt)}</span> : null}
          </div>
        ) : <p className="text-ink-500" role="status">{reading ? '正在读取已保存结果…' : '尚无已加载扫描结果'}</p>}
        <div className="flex flex-wrap gap-2">
          {snapshot && snapshot.tradeDate === marketDate() && ['FAILED', 'PARTIAL', 'INTERRUPTED'].includes(snapshot.status) ?
            <Button variant="secondary" className="!px-2 !py-1 !text-xs" disabled={retrying || reading} onClick={async () => {
              setRetrying(true); setError('')
              try { setSnapshot(await retryDailyShortTermSnapshot()) }
              catch (reason) { setError(reason instanceof Error ? reason.message : '今日扫描重试失败') }
              finally { setRetrying(false) }
            }}>{retrying ? '正在启动…' : '重试今日扫描'}</Button> : null}
          {cachedResult && ['READY', 'PARTIAL'].includes(cachedResult.status) ?
            <Button variant="secondary" className="!px-2 !py-1 !text-xs" disabled={manualLoading || reading || retrying}
              onClick={async () => {
                setReading(true); setError('')
                try {
                  const latest = await fetchShortTermResultCache()
                  setCachedResult(latest)
                  applyScanResult(latest, { force: true })
                } catch (reason) { setError(reason instanceof Error ? reason.message : '结果缓存读取失败') }
                finally { setReading(false) }
              }}>加载已保存结果</Button> : null}
        </div>
      </div>
      {snapshot && (!metadata || snapshot.status === 'RUNNING') ? <p className="text-ink-500">定时任务：{snapshot.message}</p> : null}
      {historical ? <p className="text-amber-700">历史缓存 {cachedResult.tradeDate}，主动加载后查看。</p> : null}
      {error ? <p className="text-amber-700">结果读取失败：{error}{metadata ? '；保留当前结果。' : ''}</p> : null}
      {dataGaps.length ? <details className="text-amber-700">
        <summary className="cursor-pointer">扫描时数据缺口 {dataGaps.length} 项</summary>
        <ul className="mt-2 space-y-1 leading-relaxed">{dataGaps.map(gap => <li key={gap}>{gap}</li>)}</ul>
      </details> : null}
    </div>
  )
}
