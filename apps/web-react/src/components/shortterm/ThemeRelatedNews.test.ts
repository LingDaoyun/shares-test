import { describe, expect, it } from 'vitest'
import type { HotspotNewsItem } from '../../types/hotspot'
import type { MarketTheme } from '../../types/marketThemes'
import { matchingThemeNews } from './ThemeRelatedNews'

const theme: MarketTheme = { code: 'BK1090', name: '机器人概念', marketTimestamp: '2026-10-26T07:00:00Z',
  changePercent: 3, amount: null, advancing: null, declining: null, unchanged: null, advancingRatioPercent: null }
const news: HotspotNewsItem = { id: '1', title: '机器人新品发布', source: '测试来源', url: 'https://example.com/robot',
  publishedAt: '2026-10-26T02:00:00Z', excerpt: null }

describe('related theme news evidence', () => {
  it('matches explicit names in titles or excerpts and deduplicates links', () => {
    expect(matchingThemeNews(theme, [news, { ...news, id: '2' }, { ...news, id: '3',
      title: '新品发布', excerpt: '人形机器人正式发布', url: 'https://example.com/humanoid' }])).toHaveLength(2)
    expect(matchingThemeNews(theme, [{ ...news, title: '机械装备利好' }])).toEqual([])
  })
  it('rejects future, missing, old dates and unsafe links rather than implying a causal connection', () => {
    expect(matchingThemeNews(theme, [
      { ...news, publishedAt: '2026-10-26T08:00:00Z' }, { ...news, publishedAt: null },
      { ...news, publishedAt: '2026-10-01T08:00:00Z' }, { ...news, url: 'javascript:alert(1)' },
    ])).toEqual([])
    expect(matchingThemeNews({ ...theme, marketTimestamp: null }, [news])).toEqual([])
  })
  it('matches short Chinese themes and requires word boundaries for Latin acronyms', () => {
    expect(matchingThemeNews({ ...theme, name: '液冷概念' }, [{ ...news, title: '液冷服务器发布' }])).toHaveLength(1)
    expect(matchingThemeNews({ ...theme, name: '储能' }, [{ ...news, title: '储能订单增长' }])).toHaveLength(1)
    expect(matchingThemeNews({ ...theme, name: 'AI' }, [{ ...news, title: 'TRAIN 发布' }])).toEqual([])
    expect(matchingThemeNews({ ...theme, name: 'AI' }, [{ ...news, title: 'AI 算力发布' }])).toHaveLength(1)
  })
})
