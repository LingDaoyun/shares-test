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
    expect(svg.classList.contains('min-w-[760px]')).toBe(true)
    expect(svg.getAttribute('viewBox')).toBe('0 0 920 390')
  })

  it('fits sixty candles in a compact card with readable endpoint dates and volume', () => {
    const bars = Array.from({ length: 60 }, (_, index) => ({
      tradeDate: new Date(Date.UTC(2026, 7, 3 + Math.floor(index / 5) * 7 + index % 5)).toISOString().slice(0, 10),
      open: 10, high: 12, low: 8, close: index % 2 === 0 ? 11 : 9, volume: 100 + index,
    }))
    act(() => root.render(<LeaderCandlestickChart compact bars={bars} />))
    const svg = host.querySelector('svg')!
    expect(svg.classList.contains('min-w-[760px]')).toBe(false)
    expect(Number(svg.getAttribute('viewBox')!.split(' ')[2])).toBeLessThanOrEqual(440)
    expect(host.querySelectorAll('rect[opacity="0.55"]')).toHaveLength(60)
    expect(host.querySelectorAll('line[stroke="#DC2626"]')).toHaveLength(30)
    expect(host.querySelectorAll('line[stroke="#16A34A"]')).toHaveLength(30)
    const dates = [...svg.querySelectorAll('text')].filter((text) => /^\d{2}-\d{2}$/.test(text.textContent ?? ''))
    expect(dates.length).toBeLessThanOrEqual(4)
    expect(dates[0].textContent).toBe(bars[0].tradeDate.slice(5))
    expect(dates[dates.length - 1].textContent).toBe(bars[bars.length - 1].tradeDate.slice(5))
    expect(dates[dates.length - 1].getAttribute('text-anchor')).toBe('end')
    expect(svg.innerHTML).not.toMatch(/NaN|Infinity/)
  })
})
