import { useState } from 'react'
import { changeClass, formatAmount, formatDateTime, formatPercent, formatSignedPercent } from '../../lib/format'
import type { MarketTheme, MarketThemeSnapshot } from '../../types/marketThemes'
import { Button } from '../ui/Button'

export function MarketThemePanel({ snapshot, onSelect }: {
  snapshot: MarketThemeSnapshot | null
  onSelect: (theme: MarketTheme) => void
}) {
  const [search, setSearch] = useState('')
  const [visibleCount, setVisibleCount] = useState(24)
  const matching = (snapshot?.themes ?? []).filter(theme => theme.name.toLowerCase().includes(search.trim().toLowerCase()))
  const visible = matching.slice(0, visibleCount)
  if (!snapshot?.themes.length) return null

  return <section aria-label="市场题材" className="space-y-4">
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
  </section>
}
