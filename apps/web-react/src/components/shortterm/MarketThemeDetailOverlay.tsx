import { useEffect, useState, type ReactNode } from 'react'
import { fetchMarketThemeDetail } from '../../api/marketThemes'
import { changeClass, extractErrorMessage, formatAmount, formatDateTime, formatPercent, formatPerSharePrice, formatSignedPercent } from '../../lib/format'
import type { MarketTheme, MarketThemeDetail, MarketThemeMember } from '../../types/marketThemes'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Loader } from '../ui/Loader'
import { StockKlineButton } from './StockKlineButton'

export interface MarketThemeSelection {
  theme: MarketTheme
  tradeDate: string | null
}

export function MarketThemeDetailOverlay({ selection, onClose, children }: {
  selection: MarketThemeSelection | null
  onClose: () => void
  children?: ReactNode | ((theme: MarketTheme) => ReactNode)
}) {
  if (!selection) return null
  return <DetailOverlay open title={`${selection.theme.name} · 市场题材`} subtitle={`行情日 ${selection.tradeDate ?? '未知'}`} onClose={onClose}>
    <ThemeDetail key={`${selection.theme.code}:${selection.tradeDate}`} selection={selection}>{children}</ThemeDetail>
  </DetailOverlay>
}

function ThemeDetail({ selection, children }: { selection: MarketThemeSelection; children?: ReactNode | ((theme: MarketTheme) => ReactNode) }) {
  const [detail, setDetail] = useState<MarketThemeDetail | null>(null)
  const [loading, setLoading] = useState(Boolean(selection.tradeDate))
  const [error, setError] = useState<string | null>(null)
  const [retry, setRetry] = useState(0)
  const [page, setPage] = useState(1)
  const { theme, tradeDate } = selection

  useEffect(() => {
    if (!tradeDate) return
    let cancelled = false
    setLoading(true)
    setError(null)
    setDetail(null)
    setPage(1)
    fetchMarketThemeDetail(theme.code, tradeDate).then(result => {
      if (cancelled) return
      if (result.theme.code !== theme.code || result.tradeDate !== tradeDate) {
        throw new Error('返回数据与所选题材或行情日期不一致，请关闭详情并刷新看板。')
      }
      setDetail(result)
    }).catch(reason => { if (!cancelled) setError(extractErrorMessage(reason)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [theme.code, tradeDate, retry])

  const members = detail?.members ?? []
  const pages = Math.max(1, Math.ceil(members.length / 20))
  const visibleMembers = members.slice((page - 1) * 20, page * 20)

  return <div className="space-y-5">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <p className="max-w-3xl text-xs leading-relaxed text-ink-500">题材归属依据东方财富概念板块成分。成分归属不代表股票当日上涨原因；涨停统计取同日涨停池与完整成分的交集。同一股票可能属于多个题材。</p>
      {tradeDate ? <Button type="button" variant="ghost" className="text-xs" disabled={loading} onClick={() => setRetry(value => value + 1)}>{loading ? '核对中' : '刷新题材详情'}</Button> : null}
    </div>
    {!tradeDate ? <p role="status" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-800">缺少有效行情日期，暂不能核对同日涨停数据。请关闭详情并刷新看板。</p> : null}
    {loading ? <Loader text="正在核对题材成分与同日涨停池" /> : null}
    {error ? <p role="alert" className="text-sm text-amber-800">题材详情获取失败：{error}</p> : null}
    {detail ? <>
      <div className="space-y-2 rounded-xl border border-line-soft bg-line-soft/30 p-3 text-xs text-ink-500">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <Tag tone={detail.membershipComplete ? 'brand' : 'warning'}>{detail.membershipComplete ? '成分完整' : '成分未核实完整'}</Tag>
          <span>{detail.membersSource}</span>
          <span>获取于 {formatDateTime(detail.fetchedAt)}</span>
        </div>
        <p>板块行情截至 {detail.theme.marketTimestamp ? formatDateTime(detail.theme.marketTimestamp) : '未知'} · 涨幅 <span className={changeClass(detail.theme.changePercent)}>{formatSignedPercent(detail.theme.changePercent)}</span> · 上涨占比 {formatPercent(detail.theme.advancingRatioPercent)} · 成交额 {formatAmount(detail.theme.amount)}</p>
        {detail.dataGaps.length ? <ul className="space-y-1 text-amber-800">{detail.dataGaps.map((gap, index) => <li key={`${index}:${gap}`}>{gap}</li>)}</ul> : null}
      </div>
      <dl className="grid grid-cols-3 gap-3">
        <Summary label="成分家数" value={detail.memberCount == null ? '未知' : `${detail.memberCount} 家`} />
        <Summary label="涨停家数" value={detail.limitUpCount == null ? '待核实' : `${detail.limitUpCount} 家`} />
        <Summary label="最高连板" value={detail.maxConsecutiveBoards == null ? '待核实' : `${detail.maxConsecutiveBoards} 板`} />
      </dl>
      <section className="space-y-2" aria-label="题材涨停梯队">
        <h3 className="text-sm font-semibold text-ink-900">涨停梯队</h3>
        {detail.limitUpFetchedAt ? <p className="text-xs text-ink-400">涨停池快照获取时间 {formatDateTime(detail.limitUpFetchedAt)}</p> : null}
        {detail.limitUpCount == null ? <p className="text-xs text-amber-800">完整成分或同日涨停池暂不可用，涨停家数与连板高度待核实。</p> : detail.limitUpStocks.length ? <div className="overflow-x-auto rounded-xl border border-line-soft">
          <table className="w-full min-w-[680px] text-left text-xs" aria-label="题材涨停股票">
            <thead className="bg-line-soft/70 text-ink-500"><tr>{['名称 / 代码', '现价', '涨幅', '成交额', '连板', '首次封板', '行情'].map(label => <th className="whitespace-nowrap px-3 py-2 font-medium" key={label}>{label}</th>)}</tr></thead>
            <tbody>{detail.limitUpStocks.map(stock => <tr key={stock.symbol} className="border-t border-line-soft">
              <td className="px-3 py-2"><span className="font-semibold text-ink-900">{stock.name}</span><span className="ml-2 text-ink-400">{stock.symbol}</span></td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{formatPerSharePrice(stock.latestPrice)}</td>
              <td className={`px-3 py-2 tabular ${changeClass(stock.changePercent)}`}>{formatSignedPercent(stock.changePercent)}</td>
              <td className="whitespace-nowrap px-3 py-2 tabular">{formatAmount(stock.amount)}</td>
              <td className="whitespace-nowrap px-3 py-2"><Tag tone={stock.consecutiveBoards >= 3 ? 'danger' : stock.consecutiveBoards >= 2 ? 'warning' : 'neutral'}>{stock.consecutiveBoards} 板</Tag></td>
              <td className="px-3 py-2 tabular">{stock.firstSealTime?.slice(0, 5) ?? '—'}</td>
              <td className="whitespace-nowrap px-3 py-2">{tradeDate ? <StockKlineButton symbol={stock.symbol} name={stock.name} tradeDate={tradeDate} /> : null}</td>
            </tr>)}</tbody>
          </table>
        </div> : <p className="py-2 text-xs text-ink-500">该题材在 {tradeDate} 的完整成分中暂无涨停股票。</p>}
      </section>
      <section className="space-y-2" aria-label="题材全部成分">
        <h3 className="text-sm font-semibold text-ink-900">全部成分 · {detail.memberCount ?? '未知'} 家</h3>
        <p className="text-xs text-ink-500">成分行情仅显示与题材行情日一致的数据，缺失或异日数据标为待补充。</p>
        {members.length ? <>
          <MemberTable members={visibleMembers} tradeDate={tradeDate} />
          {members.length > 20 ? <div className="flex items-center justify-between gap-3 text-xs text-ink-500">
            <span>第 {page} / {pages} 页 · {members.length} 家</span>
            <div className="flex gap-2"><Button type="button" variant="ghost" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</Button><Button type="button" variant="ghost" disabled={page >= pages} onClick={() => setPage(value => value + 1)}>下一页</Button></div>
          </div> : null}
        </> : <p className="py-2 text-xs text-ink-500">{detail.membershipComplete ? '数据源返回完整空成分列表。' : '成分数据暂不可用。'}</p>}
      </section>
    </> : null}
    {typeof children === 'function' ? children(detail?.theme ?? theme) : children}
  </div>
}

function Summary({ label, value }: { label: string; value: string }) {
  return <div className="rounded-xl border border-line-soft p-3"><dt className="text-xs text-ink-500">{label}</dt><dd className="mt-1 text-lg font-semibold tabular text-ink-900">{value}</dd></div>
}

function MemberTable({ members, tradeDate }: { members: MarketThemeMember[]; tradeDate: string | null }) {
  return <div className="overflow-x-auto rounded-xl border border-line-soft">
    <table className="w-full min-w-[760px] text-left text-xs" aria-label="题材全部成分股票">
      <thead className="bg-line-soft/70 text-ink-500"><tr>{['名称 / 代码', '现价', '涨幅', '成交额', '换手率', '行情时间', '行情'].map(label => <th className="whitespace-nowrap px-3 py-2 font-medium" key={label}>{label}</th>)}</tr></thead>
      <tbody>{members.map(member => <tr key={member.symbol} className="border-t border-line-soft">
        <td className="px-3 py-2"><span className="font-semibold text-ink-900">{member.name}</span><span className="ml-2 text-ink-400">{member.symbol}</span></td>
        <td className="whitespace-nowrap px-3 py-2 tabular">{formatPerSharePrice(member.latestPrice)}</td>
        <td className={`px-3 py-2 tabular ${changeClass(member.changePercent)}`}>{formatSignedPercent(member.changePercent)}</td>
        <td className="whitespace-nowrap px-3 py-2 tabular">{formatAmount(member.amount)}</td>
        <td className="px-3 py-2 tabular">{formatPercent(member.turnoverRate)}</td>
        <td className="whitespace-nowrap px-3 py-2 text-ink-400">{member.marketTimestamp ? formatDateTime(member.marketTimestamp) : '未知'}</td>
        <td className="whitespace-nowrap px-3 py-2">{tradeDate ? <StockKlineButton symbol={member.symbol} name={member.name} tradeDate={tradeDate} /> : null}</td>
      </tr>)}</tbody>
    </table>
  </div>
}
