import { Fragment, useEffect, useRef, useState } from 'react'
import { fetchShortTermCatchUp } from '../../api/client'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatPercent, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import { catchUpSnapshotPair, loadCatchUpLocalState, rememberCatchUpSnapshot, saveCatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { CatchUpLocalSnapshot } from '../../lib/shortTermCatchUpStorage'
import type { ShortTermLimitUpStock } from '../../types'
import type { ShortTermCatchUpCandidate, ShortTermCatchUpSnapshot, ShortTermRelatedBoard } from '../../types/shortTermCatchUp'
import { Button } from '../ui/Button'
import { Loader } from '../ui/Loader'

export function LeaderCatchUpPanel({ industry, tradeDate, sealedStocks }: {
  industry: string
  tradeDate: string
  sealedStocks: ShortTermLimitUpStock[]
}) {
  const [leaderSymbol, setLeaderSymbol] = useState('')
  const [boardCode, setBoardCode] = useState('')
  const [relatedBoards, setRelatedBoards] = useState<ShortTermRelatedBoard[]>([])
  const [snapshot, setSnapshot] = useState<ShortTermCatchUpSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [local, setLocal] = useState(loadCatchUpLocalState)
  const localRef = useRef(local)
  const [storageAvailable, setStorageAvailable] = useState(true)
  useEffect(() => {
    if (!leaderSymbol) return
    let cancelled = false
    setLoading(true)
    setSnapshot(null)
    setError(null)
    fetchShortTermCatchUp(industry, tradeDate, leaderSymbol, boardCode || undefined)
      .then(data => {
        if (cancelled) return
        if (data.tradeDate !== tradeDate || data.industry !== industry || data.leaderSymbol !== leaderSymbol
          || (boardCode ? data.boardCode !== boardCode : data.boardCode !== null)) {
          throw new Error('关联观察的日期、锚点或板块不一致，请重新选择。')
        }
        setRelatedBoards(data.relatedBoards)
        setSnapshot(data)
        const next = rememberCatchUpSnapshot(localRef.current, data, new Date().toISOString())
        if (next !== localRef.current) {
          localRef.current = next
          setLocal(next)
          setStorageAvailable(saveCatchUpLocalState(next))
        }
      })
      .catch(reason => { if (!cancelled) setError(extractErrorMessage(reason)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [industry, tradeDate, leaderSymbol, boardCode, refresh])

  const samples = boardCode ? catchUpSnapshotPair(local, tradeDate, leaderSymbol, boardCode) : []

  return (
    <section className="flex flex-col gap-3 border-t border-line-soft pt-5" aria-label="关联补涨观察">
      <div>
        <h3 className="text-sm font-semibold text-ink-900">关联补涨观察 · 中位优先</h3>
        <p className="mt-1 text-xs leading-relaxed text-ink-500">
          选择本行业已封板股票作为观察锚点，再选择供应商确认的共同板块。锚点不代表已确认的龙头；所属板块可能包含行业、概念或指数，归属不能证明事件受益或资金转移。
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-[190px] flex-1 flex-col gap-1 text-xs text-ink-600">
          已封板锚点
          <select className="rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink-900 focus:outline-none focus:ring-2 focus:ring-brand-200"
            aria-label="已封板锚点" value={leaderSymbol} onChange={event => {
              setLeaderSymbol(event.target.value); setBoardCode(''); setRelatedBoards([]); setSnapshot(null); setError(null); setLoading(false)
            }}>
            <option value="">{sealedStocks.length ? '请选择封板锚点' : '当前行业无可选封板锚点'}</option>
            {sealedStocks.map(stock => <option key={stock.symbol} value={stock.symbol}>{stock.name} · {stock.symbol}</option>)}
          </select>
        </label>
        <label className="flex min-w-[190px] flex-1 flex-col gap-1 text-xs text-ink-600">
          供应商共同板块
          <select className="rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink-900 focus:outline-none focus:ring-2 focus:ring-brand-200 disabled:text-ink-400"
            aria-label="供应商共同板块" value={boardCode} disabled={!leaderSymbol || !relatedBoards.length}
            onChange={event => { setBoardCode(event.target.value); setSnapshot(null); setError(null) }}>
            <option value="">请选择已核实归属的板块</option>
            {relatedBoards.map(board => <option key={board.code} value={board.code}>{board.name} · {board.code}</option>)}
          </select>
        </label>
        <Button disabled={!leaderSymbol} loading={loading} onClick={() => setRefresh(value => value + 1)}>刷新关联观察</Button>
      </div>
      {!leaderSymbol ? <p className="text-xs text-ink-400">选择锚点后读取共同板块。</p> : null}
      {loading ? <Loader text={boardCode ? '共同板块观察加载中' : '核对锚点所属板块中'} /> : null}
      {error ? <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">关联观察获取失败：{error}</p> : null}
      {snapshot ? <CatchUpSnapshotView snapshot={snapshot} samples={samples} /> : null}
      {!storageAvailable ? <p role="status" className="text-xs text-amber-800">浏览器存储不可用，本次留样仅保留在当前页面，关闭后无法恢复。</p> : null}
    </section>
  )
}

function CatchUpSnapshotView({ snapshot, samples }: { snapshot: ShortTermCatchUpSnapshot; samples: CatchUpLocalSnapshot[] }) {
  const percentileAvailable = snapshot.historyValidCount >= 5 && snapshot.historyCoveragePercent !== null
    && snapshot.historyCoveragePercent >= 80 && snapshot.historyDates.length === 6
  const leader = snapshot.leader
  const quoteSnapshotAvailable = snapshot.quoteSource !== null
  const historyEstablished = snapshot.historyDates.length === 6
  return (
    <div className="flex flex-col gap-2 text-xs">
      {leader ? <p className="leading-relaxed text-ink-600">
        封板锚点：<strong className="text-ink-900">{leader.name} · {leader.symbol}</strong> · {leader.consecutiveBoards} 板 · 首次封板 {leader.firstSealTime ?? '未知'} · 封板金额 {formatAmount(leader.sealFunds)}
      </p> : null}
      {snapshot.status === 'SELECT_BOARD' ? <p className="text-ink-500">请选择共同板块后查看成分股观察；以上归属由供应商提供。</p> : null}
      {snapshot.boardCode ? <>
        <p className="leading-relaxed text-ink-600">
          共同板块 {snapshot.boardName ?? snapshot.boardCode} · {snapshot.boardCode}；
          {quoteSnapshotAvailable ? `成分 ${snapshot.fetchedMemberCount}/${snapshot.expectedMemberCount > 0 ? snapshot.expectedMemberCount : '未知'}，有效报价 ${snapshot.validQuoteCount}` : '成分及有效报价未知（未获取全量快照）'}；
          {historyEstablished ? `连续历史 ${snapshot.historyValidCount}，历史覆盖 ${formatPercent(snapshot.historyCoveragePercent)}` : '连续历史未建立'}。
        </p>
        <p className="text-[11px] leading-relaxed text-ink-500">
          中位口径：截至看板日前一交易日，连续六个同口径收盘价计算前五日收益；至少 5 个有效样本且历史覆盖 ≥80% 才展示收益分位，25%–75% 为中位。
          {snapshot.historyDates.length ? ` 历史区间 ${snapshot.historyDates[0]} 至 ${snapshot.historyDates[snapshot.historyDates.length - 1]}。` : ' 连续历史日期未知。'}
          {!percentileAvailable ? ' 当前分位条件不足，中位未知。' : ''}
        </p>
        <p className="text-[11px] text-ink-400">行情来源 {snapshot.quoteSource ?? '未知'} · 源行情时间 {formatDateTime(snapshot.marketTimestamp)} · 拉取于 {formatDateTime(snapshot.fetchedAt)}</p>
        <p className="text-[11px] text-ink-500">下一核实交易日：{snapshot.nextTradingDate ?? '未知'}</p>
        <SnapshotComparison samples={samples} />
      </> : null}
      <EvidenceLine label="已知证据" values={snapshot.evidence} />
      <EvidenceLine label="反证 / 限制" values={snapshot.contraryEvidence} />
      <EvidenceLine label="数据缺口" values={snapshot.dataGaps} />
      {(snapshot.status === 'READY' || snapshot.status === 'DATA_GAP') && snapshot.boardCode && (snapshot.status === 'READY' || snapshot.candidates.length > 0) ? <>
        <p className="text-xs text-ink-600">未涨停上涨候选 · {snapshot.eligibleCandidateCount} 家，展示 {snapshot.displayedCount} 家{snapshot.truncatedCount > 0 ? `，另 ${snapshot.truncatedCount} 家未展示` : ''}；按中位优先排列。</p>
        {snapshot.candidates.length ? <CandidateTable candidates={snapshot.candidates} percentileAvailable={percentileAvailable} />
          : <p className="py-2 text-ink-500">该共同板块暂无符合条件的未涨停上涨候选。</p>}
      </> : null}
      {snapshot.status === 'UNAVAILABLE' || snapshot.status === 'DATA_GAP' ? <p className="text-amber-800">{snapshot.candidates.length ? '当前部分证据待补；仅展示可核对的价格观察，请结合上方缺口判断。' : '当前证据不足，无法形成可信的关联观察。'}</p> : null}
    </div>
  )
}

function EvidenceLine({ label, values }: { label: string; values: string[] }) {
  if (!values.length) return null
  return <p className={`text-[11px] leading-relaxed ${label.includes('缺口') ? 'text-amber-800' : 'text-ink-500'}`}><span className="font-medium">{label}：</span>{values.join('；')}</p>
}

function SnapshotComparison({ samples }: { samples: CatchUpLocalSnapshot[] }) {
  const current = samples[samples.length - 1]
  const previous = samples.length > 1 ? samples[0] : null
  const count = (value: number | null) => value === null ? '未知' : String(value)
  const delta = (now: number | null, before: number | null) => now === null || before === null ? '变化未知' : `变化 ${now - before > 0 ? '+' : ''}${now - before}`
  return <p className="border-l-2 border-line pl-2 text-[11px] leading-relaxed text-ink-500">
    本浏览器按需采样，仅源行情时间推进时记新快照。
    {current ? ` 最近样本 ${formatDateTime(current.marketTimestamp)}：上涨 ${count(current.risingCount)} / 封板 ${count(current.sealedCount)}。` : ' 暂无有效源时间样本。'}
    {current && previous ? ` 对比前次 ${formatDateTime(previous.marketTimestamp)}：上涨${delta(current.risingCount, previous.risingCount)}，封板${delta(current.sealedCount, previous.sealedCount)}。` : ' 尚无更早样本，无法比较。'}
    家数与封板变化不能证明撤单或资金轮动。
  </p>
}

function CandidateTable({ candidates, percentileAvailable }: { candidates: ShortTermCatchUpCandidate[]; percentileAvailable: boolean }) {
  return <div className="overflow-x-auto rounded-xl border border-line-soft">
    <table className="w-full min-w-[850px] text-left text-xs" aria-label="共同板块中位优先观察候选">
      <thead className="bg-line-soft/70 text-ink-500"><tr>
        {['名称 / 代码', '现价 / 当日涨幅', '成交额 / 换手', '前五日收益', '收益分位', '与锚点收益差', '行情时间'].map(label => <th key={label} className="whitespace-nowrap px-3 py-2 font-medium">{label}</th>)}
      </tr></thead>
      <tbody>{candidates.map(row => {
        const percentile = percentileAvailable && row.historyStatus === 'VALID' ? row.returnPercentile : null
        const middle = percentile !== null && percentile >= 25 && percentile <= 75 && row.middle
        return <Fragment key={row.symbol}>
          <tr className="border-t border-line-soft">
            <td className="px-3 py-2"><span className="font-semibold text-ink-900">{row.name}</span><div className="mt-0.5 text-[11px] text-ink-400">{row.symbol}{middle ? ' · 中位' : percentile === null ? ' · 分位未知' : ' · 非中位'}</div></td>
            <td className="whitespace-nowrap px-3 py-2 tabular">{formatPerSharePrice(row.latestPrice)}<div className={changeClass(row.changePercent)}>{formatSignedPercent(row.changePercent)}</div></td>
            <td className="whitespace-nowrap px-3 py-2 tabular">{formatAmount(row.amount)}<div className="text-ink-500">{formatPercent(row.turnoverRate)}</div></td>
            <td className="px-3 py-2 tabular">{row.historyStatus === 'VALID' ? formatSignedPercent(row.return5) : '未知'}</td>
            <td className="px-3 py-2 tabular">{percentile === null ? '未知' : formatPercent(percentile)}</td>
            <td className="px-3 py-2 tabular">{row.returnGapToLeader === null ? '未知' : `${row.returnGapToLeader > 0 ? '+' : ''}${row.returnGapToLeader.toFixed(2)} 个百分点`}</td>
            <td className="whitespace-nowrap px-3 py-2 text-[11px] tabular">{formatDateTime(row.marketTimestamp)}</td>
          </tr>
          <tr><td colSpan={7} className="px-3 pb-2"><details className="text-[11px] text-ink-500"><summary className="cursor-pointer focus:outline-none focus:ring-2 focus:ring-brand-200">{row.name}的证据、反证与缺口</summary>
            <EvidenceLine label="已知证据" values={row.evidence} /><EvidenceLine label="反证 / 限制" values={row.contraryEvidence} /><EvidenceLine label="数据缺口" values={row.dataGaps} />
            {!row.evidence.length && !row.contraryEvidence.length && !row.dataGaps.length ? <p>未提供额外证据。</p> : null}
          </details></td></tr>
        </Fragment>
      })}</tbody>
    </table>
  </div>
}
