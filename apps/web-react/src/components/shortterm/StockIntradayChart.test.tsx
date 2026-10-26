// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { StockIntradayPoint } from '../../types'
import { StockIntradayChart } from './StockIntradayChart'

function point(time: string, overrides: Partial<StockIntradayPoint> = {}): StockIntradayPoint {
  return { minute: `2026-10-26T${time}:00`, price: 10, averagePrice: 10.1, volume: 20, amount: 20000, ...overrides }
}

describe('StockIntradayChart', () => {
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
    vi.restoreAllMocks()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  function render(points: StockIntradayPoint[]) {
    act(() => root.render(<StockIntradayChart points={points} />))
  }

  function details() {
    return host.querySelector('dl')!.textContent!
  }

  function keyboard(key: string) {
    act(() => host.querySelector('svg')!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })))
  }

  it('defaults to the last actual minute and preserves missing values separately from zero', () => {
    render([
      point('09:30', { averagePrice: null, volume: null, amount: null }),
      point('09:31', { price: 10.2, volume: 0, amount: 0 }),
    ])
    expect(details()).toContain('2026-10-26 09:31')
    expect(details()).toContain('10.20 元')
    expect(details()).toContain('单分钟成交量 0 手')
    expect(details()).toContain('成交额 0 元')
    expect(host.querySelectorAll('[data-series="volume"]')).toHaveLength(0)
    keyboard('ArrowLeft')
    expect(details()).toContain('09:30')
    expect(details()).toContain('均价 缺失')
    expect(details()).toContain('单分钟成交量 缺失')
    expect(details()).toContain('成交额 缺失')
    keyboard('End')
    expect(details()).toContain('09:31')
    keyboard('Home')
    expect(details()).toContain('09:30')
  })

  it('keeps the morning point in the same position when afternoon data arrives and aligns its volume', () => {
    render([point('10:30')])
    const x = Number(host.querySelector('[data-series="price-point"]')!.getAttribute('cx'))
    expect(x).toBeLessThan(460)
    const volume = host.querySelector('[data-series="volume"]')!
    expect(Number(volume.getAttribute('x')) + Number(volume.getAttribute('width')) / 2).toBeCloseTo(x)
    render([point('10:30'), point('15:00')])
    expect(Number(host.querySelector('[data-series="price-point"]')!.getAttribute('cx'))).toBe(x)
    expect(host.querySelectorAll('[data-series="price-point"]')).toHaveLength(2)
  })

  it('breaks lines across a missing minute, missing average and the lunch break', () => {
    render([
      point('11:27'), point('11:28', { averagePrice: null }), point('11:30'),
      point('13:00'), point('13:01'),
    ])
    const pricePath = host.querySelector('[data-series="price"]')!.getAttribute('d')!
    const averagePath = host.querySelector('[data-series="average"]')!.getAttribute('d')!
    expect(pricePath.match(/M/g)).toHaveLength(3)
    expect(pricePath.match(/L/g)).toHaveLength(2)
    expect(averagePath.match(/M/g)).toHaveLength(3)
    expect(averagePath.match(/L/g)).toHaveLength(1)
    const beforeLunch = host.querySelector('[data-series="price-point"][data-minute$="11:30:00"]')!
    const afterLunch = host.querySelector('[data-series="price-point"][data-minute$="13:00:00"]')!
    expect(Number(afterLunch.getAttribute('cx'))).toBeGreaterThan(Number(beforeLunch.getAttribute('cx')))
  })

  it('selects the nearest actual minute through mouse and touch pointer events', () => {
    render([point('09:30'), point('10:30'), point('15:00')])
    const svg = host.querySelector('svg')!
    vi.spyOn(svg, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 920, height: 390, right: 920, bottom: 390, x: 0, y: 0, toJSON: () => ({}) })
    for (const [pointerType, clientX, time] of [['mouse', 65, '09:30'], ['touch', 272, '10:30']] as const) {
      const event = new MouseEvent('pointermove', { bubbles: true, clientX })
      Object.defineProperty(event, 'pointerType', { value: pointerType })
      act(() => svg.dispatchEvent(event))
      expect(details()).toContain(time)
      expect(svg.getAttribute('aria-valuetext')).toContain(time)
    }
  })

  it('shows a single point and ignores invalid or out-of-session inputs without inventing bars', () => {
    render([
      point('09:25'), point('12:00'), point('15:01'), point('09:30', { price: Number.NaN }),
      point('09:31', { averagePrice: Number.NaN, volume: -1, amount: Number.NaN }),
    ])
    expect(host.querySelectorAll('[data-series="price-point"]')).toHaveLength(1)
    expect(host.querySelectorAll('[data-series="volume"]')).toHaveLength(0)
    expect(details()).toContain('09:31')
    expect(details()).toContain('单分钟成交量 缺失')
    expect(host.querySelector('[data-series="average"]')!.getAttribute('d')!.trim()).toBe('')
    render([])
    expect(host.textContent).toContain('暂无可用分时数据')
    expect(host.querySelector('svg[role="slider"]')).toBeNull()
  })
})
