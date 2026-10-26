// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { LeaderCandlestickChart } from './LeaderCandlestickChart'

describe('daily chart inspection', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('lets the user inspect each day and distinguishes missing volume from a real zero', () => {
    act(() => root.render(<LeaderCandlestickChart interactive bars={[
      { tradeDate: '2026-10-23', open: 10, high: 10, low: 10, close: 10, volume: 0 },
      { tradeDate: '2026-10-26', open: 10, high: 10, low: 10, close: 10, volume: null },
    ]} />))
    const details = host.querySelector('dl')!
    expect(details.textContent).toContain('2026-10-26')
    expect(details.textContent).toContain('成交量（手）缺失')
    const svg = host.querySelector('svg')!
    act(() => svg.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true })))
    expect(details.textContent).toContain('2026-10-23')
    expect(details.textContent).toContain('成交量（手）0')
    expect(host.querySelectorAll('rect[opacity="0.55"]')).toHaveLength(0)
    expect(svg.innerHTML).not.toMatch(/NaN|Infinity/)
  })
})
