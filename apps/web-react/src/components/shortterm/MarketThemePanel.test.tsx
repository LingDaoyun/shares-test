// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MarketTheme, MarketThemeSnapshot } from '../../types/marketThemes'
import { MarketThemePanel } from './MarketThemePanel'

const theme: MarketTheme = {
  code: 'BK1090', name: '机器人概念', changePercent: 0, amount: 0,
  advancing: 0, declining: 0, unchanged: 8, advancingRatioPercent: 0,
  marketTimestamp: '2026-10-26T06:00:00Z',
}
const snapshot: MarketThemeSnapshot = {
  tradeDate: '2026-10-26', fetchedAt: '2026-10-26T06:01:00Z', status: 'READY',
  source: '东方财富概念板块', totalConceptCount: 30, excludedStructuralCount: 1,
  validQuoteCount: 29, themes: [theme], dataGaps: [],
}

describe('MarketThemePanel', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })
  function render(data: MarketThemeSnapshot | null, onSelect = vi.fn()) {
    act(() => root.render(<MarketThemePanel snapshot={data} onSelect={onSelect} />))
    return onSelect
  }

  it('keeps actual zeros distinct from missing quotes and opens the selected concept', () => {
    const onSelect = render({ ...snapshot, themes: [theme, { ...theme, code: 'BK1184', name: '人形机器人', changePercent: null, amount: null, advancing: null, declining: null, unchanged: null, advancingRatioPercent: null, marketTimestamp: null }] })
    const zero = host.querySelector<HTMLButtonElement>('button[aria-label="查看机器人概念题材详情"]')!
    expect(zero.textContent).toContain('0.00%')
    expect(zero.textContent).toContain('0.00 亿')
    const unknown = host.querySelector<HTMLButtonElement>('button[aria-label="查看人形机器人题材详情"]')!
    expect(unknown.textContent).toContain('待补充')
    expect(unknown.textContent).not.toContain('0.00%')
    expect(unknown.textContent).toContain('行情时间未知')
    act(() => zero.click())
    expect(onSelect).toHaveBeenCalledWith(theme)
    expect(host.textContent).toContain('2026-10-26')
    expect(host.textContent).not.toContain('东方财富概念板块')
    expect(host.querySelector('h3')).toBeNull()
    expect(host.textContent).not.toContain('刷新题材')
  })

  it('searches beyond the initial 24 themes even if its quote is missing', () => {
    render({ ...snapshot, themes: [
      ...Array.from({ length: 25 }, (_, i) => ({ ...theme, code: `BK${i}`, name: `示例题材${i}` })),
      { ...theme, changePercent: null },
    ] })
    expect(host.querySelectorAll('button[aria-haspopup="dialog"]')).toHaveLength(24)
    const input = host.querySelector<HTMLInputElement>('input[aria-label="搜索市场题材"]')!
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, '机器人')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    expect(host.querySelectorAll('button[aria-haspopup="dialog"]')).toHaveLength(1)
    expect(host.textContent).toContain('机器人概念')
  })

  it('leaves no empty status block when theme data is unavailable', () => {
    render(null)
    expect(host.textContent).toBe('')
    render({ ...snapshot, status: 'UNAVAILABLE', themes: [], dataGaps: ['源请求失败'] })
    expect(host.textContent).toBe('')
    expect(host.querySelector('section')).toBeNull()
  })
})
