import { Fragment, useEffect, useRef, useState } from 'react'
import { fetchShortTermCatchUp, fetchShortTermCatchUpFollowUp } from '../../api/client'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatPercent, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import { CATCH_UP_FOLLOW_UP_LIMIT, CATCH_UP_OBSERVATION_LIMIT, CATCH_UP_STORAGE_EVENT, catchUpSnapshotPair, loadCatchUpLocalState, rememberCatchUpSnapshot, saveCatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { CatchUpFirstObservation, CatchUpLocalSnapshot, CatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { ShortTermLimitUpStock } from '../../types'
import type { ShortTermCatchUpCandidate, ShortTermCatchUpFollowUpSnapshot, ShortTermCatchUpSnapshot, ShortTermRelatedBoard } from '../../types/shortTermCatchUp'
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
      {!leaderSymbol ? <p className="text-xs text-ink-400">选择锚点后读取共同板块；既有首次留样可在下方独立查询隔日价格反馈。</p> : null}
      {loading ? <Loader text={boardCode ? '共同板块观察加载中' : '核对锚点所属板块中'} /> : null}
      {error ? <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">关联观察获取失败：{error}</p> : null}
      {snapshot ? <CatchUpSnapshotView snapshot={snapshot} samples={samples} /> : null}
      {!storageAvailable ? <p role="status" className="text-xs text-amber-800">浏览器存储不可用，本次留样仅保留在当前页面，关闭后无法恢复。</p> : null}
      <SavedCatchUpObservationsPanel industry={industry} localState={local} />
    </section>
  )
}

/** Independent of today's limit-up pool, so yesterday's observations remain reachable. */
export function SavedCatchUpObservationsPanel({ industry, localState }: {
  industry?: string
  localState?: CatchUpLocalState
}) {
  const [local, setLocal] = useState(() => localState ?? loadCatchUpLocalState())
  const [selectedIndustry, setSelectedIndustry] = useState('')
  const [followUp, setFollowUp] = useState<ShortTermCatchUpFollowUpSnapshot | null>(null)
  const [followUpLoading, setFollowUpLoading] = useState(false)
  const [followUpError, setFollowUpError] = useState<string | null>(null)
  const followUpRequest = useRef(0)
  const filter = industry ?? selectedIndustry

  useEffect(() => { if (localState) setLocal(localState) }, [localState])
  useEffect(() => {
    const refreshSaved = () => setLocal(loadCatchUpLocalState())
    window.addEventListener(CATCH_UP_STORAGE_EVENT, refreshSaved)
    return () => window.removeEventListener(CATCH_UP_STORAGE_EVENT, refreshSaved)
  }, [])
  useEffect(() => {
    followUpRequest.current += 1
    setFollowUp(null); setFollowUpError(null); setFollowUpLoading(false)
    return () => { followUpRequest.current += 1 }
  }, [filter])

  const filtered = local.observations.filter(row => !filter || row.industry === filter)
  const stored = filtered.slice(-CATCH_UP_FOLLOW_UP_LIMIT).reverse()
  const industries = [...new Set(local.observations.map(row => row.industry))].sort()

  async function queryFollowUp() {
    const request = ++followUpRequest.current
    setFollowUpLoading(true); setFollowUp(null); setFollowUpError(null)
    try {
      const result = await fetchShortTermCatchUpFollowUp(stored.map(({ id, tradeDate: date, symbol, leaderSymbol: anchor, observedPrice }) => ({
        id, tradeDate: date, symbol, leaderSymbol: anchor, observedPrice
      })))
      if (request !== followUpRequest.current) return
      const originals = new Map(stored.map(row => [row.id, row]))
      const valid = result.results.filter(row => {
        const original = originals.get(row.id)
        return original && row.tradeDate === original.tradeDate && row.symbol === original.symbol
          && row.leaderSymbol === original.leaderSymbol && row.observedPrice === original.observedPrice
      })
      setFollowUp({ ...result, results: valid })
    } catch (reason) {
      if (request === followUpRequest.current) setFollowUpError(extractErrorMessage(reason))
    } finally {
      if (request === followUpRequest.current) setFollowUpLoading(false)
    }
  }

  return <section className="flex flex-col gap-2 border-t border-line-soft pt-3" aria-label="首次留样与隔日价格反馈">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h4 className="text-xs font-semibold text-ink-800">本浏览器首次留样与隔日价格反馈 · {filtered.length} 条</h4>
      <div className="flex flex-wrap items-center gap-2">
        {industry === undefined ? <label className="flex items-center gap-2 text-xs text-ink-600">
          留样行业
          <select aria-label="首次留样行业" value={selectedIndustry} onChange={event => setSelectedIndustry(event.target.value)}
            className="rounded-lg border border-line bg-white px-3 py-2 text-xs text-ink-900 focus:outline-none focus:ring-2 focus:ring-brand-200">
            <option value="">全部行业</option>
            {industries.map(name => <option key={name} value={name}>{name}</option>)}
          </select>
        </label> : null}
        <Button disabled={!stored.length} loading={followUpLoading} onClick={queryFollowUp}>查询隔日价格反馈</Button>
      </div>
    </div>
    <p className="text-[11px] leading-relaxed text-ink-500">
      按日期、锚点、板块、候选保存首次价格和证据，刷新不覆盖保留中的首次记录。仅此浏览器保留最近 {CATCH_UP_OBSERVATION_LIMIT} 条；当前范围展示并查询最近 {CATCH_UP_FOLLOW_UP_LIMIT} 条。锚点隔日无需继续涨停，当前涨停池不可用时仍可查询。
      反馈仅限下一经日历核实的交易日，观察价与隔日报价的未复权变动不是实际成交收益；除权除息会影响比较。
    </p>
    {followUpError ? <p role="alert" className="text-xs text-amber-800">隔日反馈查询失败：{followUpError}</p> : null}
    {followUp?.dataGaps.length ? <EvidenceLine label="反馈缺口" values={followUp.dataGaps} /> : null}
    {followUp ? <p className="text-[11px] text-ink-400">反馈查询于 {formatDateTime(followUp.fetchedAt)}</p> : null}
    {stored.length ? <FirstObservationTable observations={stored} followUp={followUp} />
      : <p className="text-xs text-ink-400">暂无首次留样。选定共同板块并取得有效行情后，自动保存本次展示的候选。</p>}
  </section>
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
        <p className="text-[11px] text-ink-500">下一核实交易日：{snapshot.nextTradingDate ?? '未知，暂不能安排隔日反馈'}</p>
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

function FirstObservationTable({ observations, followUp }: { observations: CatchUpFirstObservation[]; followUp: ShortTermCatchUpFollowUpSnapshot | null }) {
  const results = new Map(followUp?.results.map(row => [row.id, row]) ?? [])
  const labels = { WAITING: '等待下一交易日', EXPIRED: '已过精确隔日', UNKNOWN: '反馈未知', OBSERVED: '隔日价格反馈' }
  return <div className="overflow-x-auto rounded-xl border border-line-soft">
    <table className="w-full min-w-[800px] text-left text-xs" aria-label="本浏览器首次留样及隔日价格反馈">
      <thead className="bg-line-soft/70 text-ink-500"><tr>{['首次留样 / 候选', '锚点 / 共同板块', '首次观察价', '下一核实交易日', '隔日价格反馈'].map(label => <th key={label} className="whitespace-nowrap px-3 py-2 font-medium">{label}</th>)}</tr></thead>
      <tbody>{observations.map(row => {
        const result = results.get(row.id)
        return <Fragment key={row.id}>
          <tr className="border-t border-line-soft">
            <td className="px-3 py-2"><span className="font-semibold text-ink-900">{row.name} · {row.symbol}</span><div className="mt-0.5 text-[11px] text-ink-400">{row.tradeDate} · 留样 {formatDateTime(row.observedAt)}</div></td>
            <td className="px-3 py-2">{row.leaderName} · {row.leaderSymbol}<div className="mt-0.5 text-[11px] text-ink-400">{row.boardName} · {row.boardCode}</div></td>
            <td className="whitespace-nowrap px-3 py-2 tabular">{formatPerSharePrice(row.observedPrice)}</td>
            <td className="whitespace-nowrap px-3 py-2">{result?.followUpDate ?? row.nextTradingDate ?? '未知'}</td>
            <td className="px-3 py-2">{result ? <>
              <span>{labels[result.status]}</span>{result.status === 'OBSERVED' ? <div className={`mt-0.5 tabular ${changeClass(result.priceChangePercent)}`}>{formatPerSharePrice(result.latestPrice)} · {formatSignedPercent(result.priceChangePercent)}</div> : null}
              <p className="mt-0.5 text-[11px] leading-relaxed text-ink-500">{result.explanation}</p>
              <p className="text-[11px] text-ink-400">报价日 {result.quoteTradeDate ?? '未知'} · {formatDateTime(result.marketTimestamp)}</p>
              <EvidenceLine label="数据缺口" values={result.dataGaps} />
            </> : followUp ? '未返回可核对的反馈' : '尚未查询'}</td>
          </tr>
          <tr><td colSpan={5} className="px-3 pb-2"><details className="text-[11px] text-ink-500"><summary className="cursor-pointer focus:outline-none focus:ring-2 focus:ring-brand-200">首次证据 · {row.name} · {row.tradeDate}</summary>
            <p>首次源行情 {formatDateTime(row.marketTimestamp)} · 来源 {row.quoteSource ?? '未知'} · 前五日 {formatSignedPercent(row.return5)} · 分位 {row.returnPercentile === null ? '未知' : formatPercent(row.returnPercentile)}</p>
            <EvidenceLine label="首次证据" values={row.evidence} /><EvidenceLine label="首次反证 / 限制" values={row.contraryEvidence} /><EvidenceLine label="首次数据缺口" values={row.dataGaps} />
          </details></td></tr>
        </Fragment>
      })}</tbody>
    </table>
  </div>
}
