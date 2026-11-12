// @vitest-environment jsdom
import { act, useState } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchStockDaily, fetchStockIntraday } from '../../api/client'
import type { StockDailySnapshot, StockSearchQuote } from '../../types'
import { StockSearchDetail } from './StockSearchDetail'

vi.mock('../../api/client', () => ({ fetchStockDaily: vi.fn(), fetchStockIntraday: vi.fn() }))

const stock: StockSearchQuote = {
  symbol: '600001', name: '搜索样本', market: 'SH', industry: '电子', latestPrice: 11.23,
  changePercent: 1.2, turnoverRate: 2.3, totalMarketValue: 12e8, circulatingMarketValue: 10e8,
  amount: 3e8, tradeDate: '2026-11-12', marketTimestamp: '2026-11-12T02:00:00Z',
  fetchedAt: '2026-11-12T02:01:00Z', sourceName: '行情来源',
}
const daily: StockDailySnapshot = {
  symbol: stock.symbol, adjustMode: 'QFQ', volumeUnit: '手', source: '日线来源', fetchedAt: stock.fetchedAt!,
  bars: [{ tradeDate: '2026-11-11', open: 10, close: 11, high: 11.1, low: 9.8, volume: 1000 }],
}

describe('StockSearchDetail', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchStockDaily).mockResolvedValue(daily)
    vi.mocked(fetchStockIntraday).mockResolvedValue({
      symbol: stock.symbol, tradeDate: stock.tradeDate, source: '分时来源', volumeUnit: '手',
      fetchedAt: stock.fetchedAt!, points: [],
    })
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  it('shows quote metrics and loads daily data on mount, loading intraday only when selected', async () => {
    expect(fetchStockDaily).not.toHaveBeenCalled()
    await act(async () => root.render(<StockSearchDetail stock={stock} onClose={vi.fn()} />))
    expect(fetchStockDaily).toHaveBeenCalledExactlyOnceWith(stock.symbol)
    expect(fetchStockIntraday).not.toHaveBeenCalled()
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('搜索样本 · 600001')
    expect(dialog.textContent).toContain('行情截至 2026-11-12 10:00:00')
    expect(dialog.textContent).toContain('最新价11.23')
    expect(dialog.textContent).toContain('涨跌幅+1.20%')
    expect(dialog.textContent).toContain('换手率2.30%')
    expect(dialog.textContent).toContain('总市值12.00 亿')
    expect(dialog.textContent).toContain('流通市值10.00 亿')
    expect(dialog.textContent).toContain('成交额3.00 亿')
    expect(dialog.textContent).toContain('日线截至 2026-11-11')
    await act(async () => { Array.from(dialog.querySelectorAll('button')).find(button => button.textContent === '分时')!.click() })
    expect(fetchStockIntraday).toHaveBeenCalledExactlyOnceWith(stock.symbol)
    expect(fetchStockDaily).toHaveBeenCalledTimes(1)
    expect(dialog.textContent).not.toContain('日线截至')
    expect(dialog.textContent).toContain('暂无可用分时数据')
  })

  it('ignores a pending response for the previous stock after selection changes', async () => {
    let resolve!: (value: StockDailySnapshot) => void
    vi.mocked(fetchStockDaily).mockReturnValueOnce(new Promise(done => { resolve = done }))
      .mockResolvedValueOnce({ ...daily, symbol: '600002', source: '新股票日线' })
    await act(async () => root.render(<StockSearchDetail stock={stock} onClose={vi.fn()} />))
    await act(async () => root.render(<StockSearchDetail stock={{ ...stock, symbol: '600002', name: '下一只股票' }} onClose={vi.fn()} />))
    await act(async () => { resolve({ ...daily, source: '旧股票日线' }) })
    expect(document.body.textContent).toContain('下一只股票 · 600002')
    expect(document.body.textContent).toContain('新股票日线')
    expect(document.body.textContent).not.toContain('旧股票日线')
    expect(fetchStockIntraday).not.toHaveBeenCalled()
  })

  it('stays closed when its pending daily request finishes', async () => {
    let resolve!: (value: StockDailySnapshot) => void
    vi.mocked(fetchStockDaily).mockReturnValueOnce(new Promise(done => { resolve = done }))
    function Selection() {
      const [selected, setSelected] = useState(true)
      return selected ? <StockSearchDetail stock={stock} onClose={() => setSelected(false)} /> : null
    }
    await act(async () => root.render(<Selection />))
    await act(async () => { document.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click() })
    await act(async () => { resolve(daily) })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.body.style.overflow).not.toBe('hidden')
  })
})
