import { describe, expect, it } from 'vitest'
import { navItems, pageMeta, pathToPage } from './pageMeta'

describe('page navigation meta', () => {
  it('keeps only the active research and configuration modules', () => {
    const labels = navItems.map((item) => item.label)

    expect(labels).toEqual([
      '涨停看板',
      '批量搜股',
      '短线右侧',
      '消息面',
      '外围市场',
      '配置'
    ])
    expect(labels).not.toContain('错杀估值')
    expect(labels).not.toContain('热门追踪')
    expect(labels).not.toContain('周期试仓')
    expect(labels).not.toContain('每日信号')
    expect(labels).not.toContain('特别关注')
    expect(navItems[0]).toEqual({ key: 'limitUp', label: '涨停看板' })
    expect(pathToPage['']).toBe('limitUp')
    expect(pathToPage['limit-up']).toBe('limitUp')
    expect(pathToPage['batch-search']).toBe('batchSearch')
    expect(pathToPage['short-term']).toBe('shortTerm')
    expect(labels).not.toContain('长期价投')
    expect(pageMeta[pathToPage['']].title).toBe('涨停看板')
    expect(pathToPage.policy).toBe('policy')
    expect(pathToPage.overseas).toBe('overseas')
    expect(pathToPage.mispricing).toBeUndefined()
    expect(pathToPage.tech).toBeUndefined()
    expect(pathToPage.cycle).toBeUndefined()
    expect(pathToPage.signals).toBeUndefined()
  })

  it('removes metadata and path aliases for the retired modules', () => {
    for (const key of ['hotspots', 'potentialLeader', 'tradeReview', 'rules']) {
      expect(pageMeta).not.toHaveProperty(key)
    }
    for (const path of ['hotspots', 'market', 'potential-leader', 'five-day', 'first-board-alert', 'trade-review', 'rules']) {
      expect(pathToPage[path]).toBeUndefined()
    }
  })
})
