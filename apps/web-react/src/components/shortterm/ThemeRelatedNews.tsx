import { useEffect, useState } from 'react'
import { fetchHotspotSnapshot } from '../../api/hotspots'
import { formatDateTime } from '../../lib/format'
import type { HotspotNewsItem } from '../../types/hotspot'
import type { MarketTheme } from '../../types/marketThemes'

/** 只关联明确名称；来源归属与新闻线索均不能证明当日上涨原因。 */
export function matchingThemeNews(theme: MarketTheme, news: HotspotNewsItem[]) {
  const keyword = theme.name.replace(/概念$/, '').trim()
  const cutoff = theme.marketTimestamp ? Date.parse(theme.marketTimestamp) : NaN
  if (!keyword || !Number.isFinite(cutoff)) return []
  const matches = (text: string) => /^[a-z0-9]+$/i.test(keyword)
    ? new RegExp(`\\b${keyword}\\b`, 'i').test(text) : text.includes(keyword)
  const earliest = cutoff - 7 * 24 * 60 * 60 * 1000
  const seen = new Set<string>()
  return news.filter(item => {
    const published = item.publishedAt ? Date.parse(item.publishedAt) : NaN
    if (!Number.isFinite(published) || published < earliest || published > cutoff
      || !matches(`${item.title} ${item.excerpt ?? ''}`)) return false
    try {
      const url = new URL(item.url)
      if (!['https:', 'http:'].includes(url.protocol) || seen.has(url.href)) return false
      seen.add(url.href)
      return true
    } catch { return false }
  }).sort((a, b) => Date.parse(b.publishedAt!) - Date.parse(a.publishedAt!)).slice(0, 5)
}

export function ThemeRelatedNews({ theme }: { theme: MarketTheme }) {
  const [news, setNews] = useState<HotspotNewsItem[] | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let active = true
    setNews(null)
    setFailed(false)
    if (!theme.marketTimestamp) return () => { active = false }
    void fetchHotspotSnapshot()
      .then(snapshot => { if (active) setNews(matchingThemeNews(theme, snapshot.news)) })
      .catch(() => { if (active) setFailed(true) })
    return () => { active = false }
  }, [theme.code, theme.name, theme.marketTimestamp])

  return <section className="mt-4 border-t border-line-soft pt-4" aria-label="题材相关新闻线索">
    <h3 className="text-sm font-semibold text-ink-900">相关新闻线索</h3>
    <p className="mt-1 text-xs leading-relaxed text-ink-500">读取已保存新闻，匹配题材名称，范围为源行情时间前 7 天{theme.marketTimestamp ? `（截至 ${formatDateTime(theme.marketTimestamp)}）` : ''}。新闻覆盖有限，相关报道不代表当日上涨原因。</p>
    {!theme.marketTimestamp ? <p className="mt-2 text-xs text-ink-400">缺少源行情时间，暂不匹配新闻。</p>
      : failed ? <p className="mt-2 text-xs text-amber-700">已保存新闻暂不可用。</p>
      : news === null ? <p className="mt-2 text-xs text-ink-400">正在读取相关新闻…</p>
      : news.length === 0 ? <p className="mt-2 text-xs text-ink-400">当前已保存新闻中没有符合时间与名称条件的报道。</p>
      : <ul className="mt-2 space-y-2">{news.map(item => <li key={item.url}>
        <a href={item.url} target="_blank" rel="noopener noreferrer" className="text-sm text-brand-600 hover:underline">{item.title}</a>
        <p className="text-xs text-ink-400">{item.source} · {formatDateTime(item.publishedAt!)}</p>
      </li>)}</ul>}
  </section>
}
