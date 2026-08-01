import { describe, expect, it } from 'vitest'
import { navItems, pathToPage } from './pageMeta'

describe('page navigation meta', () => {
  it('removes retired research modules and exposes the policy dashboard', () => {
    const labels = navItems.map((item) => item.label)

    expect(labels).toEqual([
      '短线右侧',
      '长期价投',
      '政策解读',
      '特别关注',
      '交易复盘',
      '规则目录',
      '配置'
    ])
    expect(labels).not.toContain('错杀估值')
    expect(labels).not.toContain('热门追踪')
    expect(labels).not.toContain('周期试仓')
    expect(labels).not.toContain('每日信号')
    expect(pathToPage.policy).toBe('policy')
    expect(pathToPage.mispricing).toBeUndefined()
    expect(pathToPage.tech).toBeUndefined()
    expect(pathToPage.cycle).toBeUndefined()
    expect(pathToPage.signals).toBeUndefined()
  })
})
