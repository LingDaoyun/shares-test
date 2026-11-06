import { useEffect, useRef, useState } from 'react'
import { fetchShortTermCatchUpFollowUp } from '../../api/client'
import { changeClass, extractErrorMessage, formatDateTime, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import { CATCH_UP_FOLLOW_UP_LIMIT, CATCH_UP_STORAGE_EVENT, CATCH_UP_STORAGE_KEY, loadCatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { CatchUpFirstObservation } from '../../lib/shortTermCatchUpStorage'
import type { ShortTermCatchUpFollowUpResult, ShortTermCatchUpFollowUpSnapshot } from '../../types/shortTermCatchUp'
import { Button } from '../ui/Button'
import { StockKlineButton } from './StockKlineButton'

function loadObservations() {
  return loadCatchUpLocalState().observations.slice().sort((a, b) => Date.parse(b.observedAt) - Date.parse(a.observedAt) || b.id.localeCompare(a.id))
}

const statusLabels: Record<ShortTermCatchUpFollowUpResult['status'], string> = {
  WAITING: '未到反馈日', EXPIRED: '已过反馈日', UNKNOWN: '行情未知', OBSERVED: '已观察'
}

export function CatchUpFollowUpPanel() {
  const [observations, setObservations] = useState(loadObservations)
  const observationsRef = useRef(observations)
  const [page, setPage] = useState(0)
  const [snapshot, setSnapshot] = useState<ShortTermCatchUpFollowUpSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const requestId = useRef(0)
  const busy = useRef(false)

  function clearFeedback() {
    requestId.current += 1
    busy.current = false
    setSnapshot(null); setError(null); setLoading(false)
  }

  useEffect(() => {
    const reload = () => {
      const next = loadObservations()
      if (JSON.stringify(next) === JSON.stringify(observationsRef.current)) return
      observationsRef.current = next
      clearFeedback()
      setObservations(next); setPage(0)
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === CATCH_UP_STORAGE_KEY || event.key === null) reload()
    }
    window.addEventListener(CATCH_UP_STORAGE_EVENT, reload)
    window.addEventListener('storage', onStorage)
    return () => {
      requestId.current += 1
      window.removeEventListener(CATCH_UP_STORAGE_EVENT, reload)
      window.removeEventListener('storage', onStorage)
    }
  }, [])

  const pageCount = Math.max(1, Math.ceil(observations.length / CATCH_UP_FOLLOW_UP_LIMIT))
  const rows = observations.slice(page * CATCH_UP_FOLLOW_UP_LIMIT, (page + 1) * CATCH_UP_FOLLOW_UP_LIMIT)
  const results = new Map(snapshot?.results.map(row => [row.id, row]))

  async function updateFeedback() {
    if (busy.current || !rows.length) return
    const currentRequest = ++requestId.current
    const batch = rows.map(({ id, tradeDate, symbol, leaderSymbol, observedPrice }) => ({ id, tradeDate, symbol, leaderSymbol, observedPrice }))
    busy.current = true
    setLoading(true); setError(null); setSnapshot(null)
    try {
      const data = await fetchShortTermCatchUpFollowUp(batch)
      if (currentRequest !== requestId.current) return
      const expected = new Map(batch.map(row => [row.id, row]))
      const seen = new Set<string>()
      if (!Array.isArray(data.results) || data.results.length !== batch.length) throw new Error('反馈身份不完整或不一致，请重试。')
      for (const row of data.results) {
        const original = expected.get(row.id)
        if (!original || seen.has(row.id) || row.tradeDate !== original.tradeDate || row.symbol !== original.symbol
          || row.leaderSymbol !== original.leaderSymbol || row.observedPrice !== original.observedPrice) {
          throw new Error('反馈身份不完整或不一致，请重试。')
        }
        seen.add(row.id)
      }
      setSnapshot(data)
    } catch (reason) {
      if (currentRequest === requestId.current) setError(extractErrorMessage(reason))
    } finally {
      if (currentRequest === requestId.current) { busy.current = false; setLoading(false) }
    }
  }

  return <section className="flex flex-col gap-3" aria-label="本浏览器首次观察与隔日反馈">
    <p className="text-xs leading-relaxed text-ink-500">
      本浏览器首次观察：在行业明细选择封板锚点和共同板块后，已有候选会自动留样，首次观察价不会被后续刷新覆盖。
      观察价变化不是实际交易收益；仅比较首次观察价与下一交易日有效报价，不包含成交、费用或滑点。
    </p>
    {!observations.length ? <p className="rounded-xl border border-line-soft bg-line-soft/40 px-4 py-6 text-sm text-ink-500">
      暂无本浏览器首次观察。请打开涨停行业明细，选择观察锚点，再选择供应商确认的共同板块，读取候选后自动留样。
    </p> : <>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-ink-500">按最近首次观察排序 · 第 {page + 1} / {pageCount} 页 · 共 {observations.length} 条 · 每页最多 {CATCH_UP_FOLLOW_UP_LIMIT} 条</p>
        <div className="flex flex-wrap gap-2">
          <Button disabled={page === 0} onClick={() => { clearFeedback(); setPage(value => value - 1) }}>上一页</Button>
          <Button disabled={page + 1 >= pageCount} onClick={() => { clearFeedback(); setPage(value => value + 1) }}>下一页</Button>
          <Button loading={loading} onClick={updateFeedback}>更新隔日反馈</Button>
        </div>
      </div>
      {loading ? <p role="status" className="text-xs text-ink-500">正在更新本页隔日反馈…</p> : null}
      {error ? <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">隔日反馈获取失败：{error} 可点击“更新隔日反馈”重试。</p> : null}
      {snapshot ? <p className="text-[11px] text-ink-500">反馈获取时间 {formatDateTime(snapshot.fetchedAt)}{snapshot.dataGaps.length ? ` · 数据缺口：${snapshot.dataGaps.join('；')}` : ''}</p> : null}
      <div className="overflow-x-auto rounded-xl border border-line-soft">
        <table className="w-full min-w-[1100px] text-left text-xs" aria-label="本浏览器首次观察隔日反馈">
          <thead className="bg-line-soft/70 text-ink-500"><tr>
            {['股票 / 日线', '首次观察', '板块 / 锚点', '下一交易日 / 状态', '观察价变化', '反馈行情', '数据缺口'].map(label => <th key={label} className="whitespace-nowrap px-3 py-2 font-medium">{label}</th>)}
          </tr></thead>
          <tbody>{rows.map(row => <ObservationRow key={row.id} row={row} feedback={results.get(row.id)} />)}</tbody>
        </table>
      </div>
    </>}
  </section>
}

function ObservationRow({ row, feedback }: { row: CatchUpFirstObservation; feedback?: ShortTermCatchUpFollowUpResult }) {
  const change = feedback?.status === 'OBSERVED' && typeof feedback.priceChangePercent === 'number'
    && Number.isFinite(feedback.priceChangePercent) && typeof feedback.latestPrice === 'number'
    && Number.isFinite(feedback.latestPrice) && feedback.latestPrice > 0 ? feedback.priceChangePercent : null
  const gaps = [...row.dataGaps, ...(feedback?.dataGaps ?? [])]
  return <tr className="border-t border-line-soft align-top" data-symbol={row.symbol}>
    <td className="px-3 py-3"><strong className="text-ink-900">{row.name}</strong><div className="mb-2 mt-0.5 text-ink-400">{row.symbol}</div><StockKlineButton symbol={row.symbol} name={row.name} tradeDate={row.tradeDate} /></td>
    <td className="whitespace-nowrap px-3 py-3 tabular text-ink-600">
      <div>{row.tradeDate} · {formatPerSharePrice(row.observedPrice)}</div>
      <div className="mt-1 text-[11px]">记录于 {formatDateTime(row.observedAt)}</div>
      <div className="mt-1 text-[11px]">源行情时间 {formatDateTime(row.marketTimestamp)}</div>
      <div className="mt-1 text-[11px] text-ink-400">{row.quoteSource ?? '行情来源未知'}</div>
    </td>
    <td className="px-3 py-3 text-ink-600"><div>{row.industry} · {row.boardName}</div><div className="mt-1 text-[11px]">{row.boardCode}</div><div className="mt-1">锚点 {row.leaderName} · {row.leaderSymbol}</div></td>
    <td className="px-3 py-3 text-ink-600"><div>{feedback ? feedback.followUpDate ?? '未知' : row.nextTradingDate ?? '未知'}</div><div className="mt-1 font-medium">{feedback ? statusLabels[feedback.status] ?? '行情未知' : '尚未更新'}</div>{feedback?.explanation ? <p className="mt-1 text-[11px] leading-relaxed text-ink-500">{feedback.explanation}</p> : null}</td>
    <td className={`whitespace-nowrap px-3 py-3 tabular ${changeClass(change)}`}>{change === null ? '价格变化未知' : formatSignedPercent(change)}</td>
    <td className="whitespace-nowrap px-3 py-3 text-[11px] text-ink-500">
      <div>{change !== null ? formatPerSharePrice(feedback?.latestPrice) : '有效反馈价格未知'}</div>
      <div className="mt-1">报价交易日 {feedback?.quoteTradeDate ?? '未知'}</div>
      <div className="mt-1">源行情时间 {feedback?.marketTimestamp ? formatDateTime(feedback.marketTimestamp) : '未知'}</div>
    </td>
    <td className="max-w-[230px] px-3 py-3 text-[11px] leading-relaxed text-ink-500">{gaps.length ? gaps.join('；') : feedback ? '未报告额外缺口' : '隔日反馈尚未获取'}</td>
  </tr>
}
