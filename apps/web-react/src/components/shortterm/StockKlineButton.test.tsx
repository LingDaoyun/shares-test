// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchStockDaily, fetchStockIntraday } from '../../api/client'
import type { StockDailySnapshot, StockIntradaySnapshot } from '../../types'
import { StockKlineButton } from './StockKlineButton'

vi.mock('../../api/client', () => ({ fetchStockDaily: vi.fn(), fetchStockIntraday: vi.fn() }))

const daily: StockDailySnapshot = {
  symbol: '600001', adjustMode: 'QFQ', volumeUnit: '手', source: '日线来源', fetchedAt: '2026-10-26T07:00:00Z',
  bars: [{ tradeDate: '2026-10-23', open: 10, close: 11, high: 11.1, low: 9.8, volume: 1000 }]
}
const intraday: StockIntradaySnapshot = {
  symbol: '600001', tradeDate: '2026-10-26', source: '东方财富', volumeUnit: '手', fetchedAt: '2026-10-26T07:00:00Z',
  points: [{ minute: '2026-10-26T14:59:00', price: 11, averagePrice: 10.5, volume: 100, amount: 110000 }]
}

describe('StockKlineButton', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchStockDaily).mockResolvedValue(daily)
    vi.mocked(fetchStockIntraday).mockResolvedValue(intraday)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function render(symbol = '600001') {
    await act(async () => { root.render(<StockKlineButton symbol={symbol} name="观察样本" tradeDate="2026-10-22" />) })
  }
  async function click(text: string) {
    await act(async () => { Array.from(document.querySelectorAll('button')).find(button => button.textContent === text)!.click() })
  }
  async function open() {
    await act(async () => { host.querySelector<HTMLButtonElement>('button')!.click() })
  }
  it('loads only the selected chart and distinguishes source dates from the observation date', async () => {
    await render()
    expect(fetchStockIntraday).not.toHaveBeenCalled()
    expect(fetchStockDaily).not.toHaveBeenCalled()
    await open()
    expect(fetchStockIntraday).toHaveBeenCalledWith('600001')
    expect(fetchStockDaily).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('看板 / 观察日 2026-10-22')
    expect(document.body.textContent).toContain('分时行情日 2026-10-26')
    expect(document.body.textContent).toContain('与看板 / 观察日不同')
    expect(document.body.textContent).toContain('行情截至 14:59')
    await click('日线')
    expect(fetchStockDaily).toHaveBeenCalledWith('600001')
    expect(document.body.textContent).toContain('日线截至 2026-10-23')
    expect(document.body.textContent).toContain('前复权')
    expect(document.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('1 个交易日')
    expect(document.body.textContent).not.toContain('分时行情日')
  })
  it('rejects a different stock and retries without displaying the wrong chart', async () => {
    vi.mocked(fetchStockIntraday).mockResolvedValueOnce({ ...intraday, symbol: '600002' })
    await render(); await open()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('股票不一致')
    expect(document.querySelector('svg[role]')).toBeNull()
    await click('重新加载分时')
    expect(document.querySelector('svg[role="slider"]')).not.toBeNull()
  })
  it('allows daily data to load when the intraday source fails', async () => {
    vi.mocked(fetchStockIntraday).mockRejectedValue(new Error('分时数据暂不可用'))
    await render(); await open()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('分时数据暂不可用')
    await click('日线')
    expect(document.querySelector('[role="alert"]')).toBeNull()
    expect(document.body.textContent).toContain('日线截至 2026-10-23')
  })
  it('does not let a late intraday response replace the selected daily chart', async () => {
    let resolve!: (value: StockIntradaySnapshot) => void
    vi.mocked(fetchStockIntraday).mockReturnValue(new Promise(done => { resolve = done }))
    await render(); await open(); await click('日线')
    await act(async () => { resolve(intraday) })
    expect(document.body.textContent).toContain('日线截至 2026-10-23')
    expect(document.body.textContent).not.toContain('分时行情日')
  })
  it('does not display a previous stock response after changing stocks', async () => {
    let resolve!: (value: StockIntradaySnapshot) => void
    vi.mocked(fetchStockIntraday).mockReturnValueOnce(new Promise(done => { resolve = done }))
      .mockResolvedValueOnce({ ...intraday, symbol: '600002', points: [] })
    await render(); await open(); await render('600002')
    await act(async () => { resolve(intraday) })
    expect(document.querySelector('svg[role="slider"]')).toBeNull()
    expect(document.body.textContent).toContain('暂无可用分时数据')
  })
  it('keeps a closed chart closed when its pending request finishes', async () => {
    let resolve!: (value: StockIntradaySnapshot) => void
    vi.mocked(fetchStockIntraday).mockReturnValue(new Promise(done => { resolve = done }))
    await render(); await open()
    await act(async () => { document.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click() })
    await act(async () => { resolve(intraday) })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.body.style.overflow).not.toBe('hidden')
  })
})
