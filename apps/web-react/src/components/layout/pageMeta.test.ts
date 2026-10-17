import { describe, expect, it } from 'vitest'
import { navItems, pageMeta, pathToPage } from './pageMeta'

describe('page navigation meta', () => {
  it('removes retired research modules and exposes the policy dashboard', () => {
    const labels = navItems.map((item) => item.label)

    expect(labels).toEqual([
      '涨停看板',
      '短线右侧',
      '政策解读',
      '五日观察',
      '长期价投',
      '外围市场',
      '特别关注',
      '交易复盘',
      '规则目录',
      '配置'
    ])
    expect(labels).not.toContain('错杀估值')
    expect(labels).not.toContain('热门追踪')
    expect(labels).not.toContain('周期试仓')
    expect(labels).not.toContain('每日信号')
    expect(navItems[0]).toEqual({ key: 'limitUp', label: '涨停看板' })
    expect(pathToPage['']).toBe('limitUp')
    expect(pathToPage['limit-up']).toBe('limitUp')
    expect(pathToPage['short-term']).toBe('shortTerm')
    expect(pageMeta[pathToPage['']].title).toBe('涨停看板')
    expect(pathToPage.policy).toBe('policy')
    expect(pathToPage['potential-leader']).toBe('potentialLeader')
    expect(pathToPage['five-day']).toBe('potentialLeader')
    expect(pathToPage['first-board-alert']).toBe('potentialLeader')
    expect(pathToPage.overseas).toBe('overseas')
    expect(pathToPage.mispricing).toBeUndefined()
    expect(pathToPage.tech).toBeUndefined()
    expect(pathToPage.cycle).toBeUndefined()
    expect(pathToPage.signals).toBeUndefined()
  })
})
