import { useState } from 'react'
import { changeClass, formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { MarketTheme, MarketThemeSnapshot } from '../../types/marketThemes'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Loader } from '../ui/Loader'

export function MarketThemePanel({ snapshot, loading, error, onRefresh, onSelect }: {
  snapshot: MarketThemeSnapshot | null
  loading: boolean
  error: string | null
  onRefresh: () => void
  onSelect: (theme: MarketTheme) => void
}) {
  const [search, setSearch] = useState('')
  const [visibleCount, setVisibleCount] = useState(24)
  const matching = (snapshot?.themes ?? []).filter(theme => theme.name.toLowerCase().includes(search.trim().toLowerCase()))
  const visible = matching.slice(0, visibleCount)
  const status = error && snapshot ? '更新失败 · 保留上次快照' : snapshot ? {
    READY: '行情可用', PARTIAL: '部分行情缺失', STALE: '保留上次快照', UNAVAILABLE: '行情暂不可用',
  }[snapshot.status] : null

  return <section aria-label="市场题材" aria-busy={loading} className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="space-y-1">
        <h3 className="text-sm font-semibold text-ink-900">市场题材</h3>
        <p className="text-xs text-ink-500">以东方财富概念板块为归属依据，按板块涨幅、上涨占比、成交额排列。点开查看成分股与涨停梯队。</p>
      </div>
      <Button type="button" variant="ghost" disabled={loading} onClick={onRefresh} className="text-xs">{loading ? '更新中' : '刷新题材'}</Button>
    </div>
    {snapshot ? <div className="space-y-2 rounded-xl border border-line-soft bg-line-soft/30 px-3 py-3 text-xs text-ink-500">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Tag tone={!error && snapshot.status === 'READY' ? 'brand' : 'warning'}>{status}</Tag>
        <span>行情日 {snapshot.tradeDate ?? '未知'}</span>
        <span>{snapshot.themes.length} 个题材 · {snapshot.validQuoteCount} 个有有效涨幅</span>
        <span>已排除 {snapshot.excludedStructuralCount} 个交易 / 持仓等标签</span>
      </div>
      <p>{snapshot.source} · 获取于 {formatDateTime(snapshot.fetchedAt)}</p>
      {error || snapshot.status === 'STALE' ? <p className="text-amber-800">本次更新失败，以下为上次快照，请注意原始行情时间。</p> : null}
      {snapshot.dataGaps.length ? <ul className="space-y-1 text-amber-800">{snapshot.dataGaps.map((gap, index) => <li key={`${index}:${gap}`}>{gap}</li>)}</ul> : null}
    </div> : null}
    {error ? <p role="alert" className="text-sm text-amber-800">题材获取失败：{error}</p> : null}
    {loading && !snapshot ? <Loader text="市场题材加载中" /> : null}
    {!loading && !snapshot && !error ? <p className="py-4 text-sm text-ink-500">暂无可用题材快照，请刷新获取。</p> : null}
    {snapshot?.themes.length ? <>
      <div className="flex flex-wrap items-center gap-3">
        <input value={search} aria-label="搜索市场题材" placeholder="搜索题材，如机器人、半导体"
          onChange={event => { setSearch(event.target.value); setVisibleCount(24) }}
          className="w-full rounded-lg border border-line-soft bg-white px-3 py-2 text-sm text-ink-900 outline-none focus:border-brand-300 focus:ring-2 focus:ring-brand-100 sm:w-80" />
        <span className="text-xs text-ink-400">{search.trim() ? `匹配 ${matching.length} 个题材` : '一只股票可属于多个题材，题材之间不可相加。'}</span>
      </div>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
        {visible.map(theme => <button key={theme.code} type="button" aria-haspopup="dialog" aria-label={`查看${theme.name}题材详情`}
          onClick={() => onSelect(theme)} className="rounded-xl border border-line-soft bg-white p-4 text-left transition hover:border-brand-200 hover:bg-brand-50/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-300">
          <div className="flex items-start justify-between gap-3">
            <span className="text-sm font-semibold text-ink-900">{theme.name}</span>
            <span className={`shrink-0 text-base font-semibold tabular ${changeClass(theme.changePercent)}`}>{formatSignedPercent(theme.changePercent)}</span>
          </div>
          <dl className="mt-3 grid grid-cols-2 gap-3 text-xs">
            <div><dt className="text-ink-400">上涨占比</dt><dd className="mt-1 tabular text-ink-700">{formatPercent(theme.advancingRatioPercent)}</dd></div>
            <div><dt className="text-ink-400">成交额</dt><dd className="mt-1 tabular text-ink-700">{formatAmount(theme.amount)}</dd></div>
          </dl>
          <p className="mt-3 text-xs text-ink-500">上涨 {theme.advancing ?? '未知'} · 下跌 {theme.declining ?? '未知'} · 平盘 {theme.unchanged ?? '未知'}</p>
          <p className="mt-2 text-[11px] text-ink-400">{theme.marketTimestamp ? `行情截至 ${formatDateTime(theme.marketTimestamp)}` : '行情时间未知'}</p>
        </button>)}
      </div>
      {!matching.length ? <p className="py-4 text-center text-sm text-ink-500">未找到匹配题材，可换个名称搜索。</p> : null}
      {matching.length > visibleCount ? <div className="text-center"><Button type="button" variant="secondary" onClick={() => setVisibleCount(value => value + 24)}>展开更多（已显示 {visible.length} / {matching.length}）</Button></div> : null}
    </> : snapshot && !loading ? <p className="py-4 text-sm text-ink-500">当前没有可展示的题材数据，请查看来源状态后重试。</p> : null}
  </section>
}
