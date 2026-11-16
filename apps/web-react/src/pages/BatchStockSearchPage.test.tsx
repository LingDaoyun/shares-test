// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchStockDaily, fetchStockIntraday, searchStocks } from '../api/client'
import type { StockDailySnapshot, StockSearchSnapshot } from '../types'
import { BatchStockSearchPage } from './BatchStockSearchPage'
import { toast } from '../components/ui/Toast'

vi.mock('../api/client', () => ({ searchStocks: vi.fn(), fetchStockDaily: vi.fn(), fetchStockIntraday: vi.fn() }))
vi.mock('../components/ui/Toast', () => ({ toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(), loading: vi.fn(), dismiss: vi.fn() } }))

const defaults = { excludeChiNext: true, excludeStar: true, excludeSt: true }

function snapshot(count = 2): StockSearchSnapshot {
  return {
    keyword: '东方', matchedCount: count, universeCount: 5500, expectedCount: 5500,
    complete: true, source: '测试行情源', fetchedAt: '2026-11-12T07:00:00Z',
    stocks: Array.from({ length: count }, (_, i) => ({
      symbol: String(600000 + i), name: '东方' + i, market: 'SH', industry: '电气设备',
      latestPrice: 12.34, changePercent: 2.5, turnoverRate: 3.21, totalMarketValue: 1e10,
      circulatingMarketValue: null, amount: 2e8, tradeDate: '2026-11-12',
      marketTimestamp: '2026-11-12T07:00:00Z', fetchedAt: '2026-11-12T07:00:01Z', sourceName: '测试行情源'
    }))
  }
}
function daily(symbol: string): StockDailySnapshot {
  return { symbol, source: '测试日线', adjustMode: 'QFQ', volumeUnit: '手', fetchedAt: '2026-11-12T07:00:00Z',
    bars: [{ tradeDate: '2026-11-12', open: 12, close: 12.34, high: 13, low: 11, volume: 100 }] }
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('batch stock search filters and pages', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(searchStocks).mockResolvedValue(snapshot())
    vi.mocked(fetchStockDaily).mockImplementation(async symbol => daily(symbol))
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })
  async function render() { await act(async () => { root.render(<BatchStockSearchPage />) }) }
  async function fill(selector: string, value: string) {
    await act(async () => {
      const input = document.querySelector<HTMLInputElement>(selector)!
      expect(input, selector).not.toBeNull()
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }
  async function search(keyword = '东方') {
    await fill('input[type="search"]', keyword)
    await act(async () => { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) })
  }
  async function click(label: string, scope: ParentNode = document) {
    const button = Array.from(scope.querySelectorAll<HTMLButtonElement>('button')).find(item => item.getAttribute('aria-label') === label || item.textContent === label)!
    expect(button, label).toBeDefined()
    await act(async () => { button.click() })
  }

  function mockCardLayout(initial: { columns: number; height: number; cardHeight?: number }) {
    let layout = { cardHeight: 146.5, ...initial }
    const callbacks = new Set<() => void>()
    vi.stubGlobal('ResizeObserver', class {
      notify: () => void
      constructor(callback: ResizeObserverCallback) {
        this.notify = () => callback([], this as unknown as ResizeObserver)
        callbacks.add(this.notify)
      }
      observe() {}
      unobserve() {}
      disconnect() { callbacks.delete(this.notify) }
    })
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
      return this.getAttribute('aria-label') === '股票列表滚动区域' ? layout.height : 0
    })
    const originalStyle = window.getComputedStyle.bind(window)
    vi.spyOn(window, 'getComputedStyle').mockImplementation(element => element.getAttribute('aria-label') === '股票搜索结果'
      ? { gridTemplateColumns: Array(layout.columns).fill('300px').join(' '), rowGap: '8px' } as CSSStyleDeclaration
      : originalStyle(element))
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      return { width: 300, height: this.tagName === 'ARTICLE' ? layout.cardHeight : layout.height } as DOMRect
    })
    return async (next: Partial<typeof layout>) => {
      layout = { ...layout, ...next }
      await act(async () => { [...callbacks].forEach(callback => callback()) })
    }
  }

  it('fills complete viewport rows and adapts pagination on resize without refetching', async () => {
    const resize = mockCardLayout({ columns: 5, height: 1120 })
    vi.mocked(searchStocks).mockResolvedValue(snapshot(67))
    await render()
    await search()
    expect(host.querySelectorAll('article')).toHaveLength(35)
    expect(host.querySelector('[aria-label="股票结果分页"]')?.textContent).toContain('显示 1–35 / 67 只')
    expect(toast.success).not.toHaveBeenCalledWith(expect.stringContaining('每页 12 只'), expect.anything())
    await click('下一页')
    expect(host.querySelector('article')?.textContent).toContain('东方35')
    await resize({ columns: 3, height: 520 })
    expect(host.querySelectorAll('article')).toHaveLength(9)
    expect(host.querySelector('article')?.textContent).toContain('东方27')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')?.value).toBe('4')
    await act(async () => {
      const select = host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!
      select.value = '8'
      select.dispatchEvent(new Event('change', { bubbles: true }))
    })
    expect(host.querySelectorAll('article')).toHaveLength(4)
    await resize({ columns: 5, height: 1120 })
    expect(host.querySelectorAll('article')).toHaveLength(32)
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')?.value).toBe('2')
    expect(host.querySelector('article')?.textContent).toContain('东方35')
    expect(searchStocks).toHaveBeenCalledTimes(1)
    expect(fetchStockDaily).not.toHaveBeenCalled()
    expect(fetchStockIntraday).not.toHaveBeenCalled()
  })

  it('retains a safe page for hidden or very short viewports and cleans up resize observers', async () => {
    const resize = mockCardLayout({ columns: 1, height: 0 })
    vi.mocked(searchStocks).mockResolvedValue(snapshot(25))
    await render()
    await search()
    expect(host.querySelectorAll('article')).toHaveLength(12)
    await resize({ height: 100 })
    expect(host.querySelectorAll('article')).toHaveLength(1)
    await resize({ height: 520 })
    expect(host.querySelectorAll('article')).toHaveLength(3)
    await resize({ height: 0 })
    expect(host.querySelectorAll('article')).toHaveLength(3)
    await act(async () => { root.render(null) })
    await resize({ columns: 5, height: 1120 })
    expect(host.querySelectorAll('article')).toHaveLength(0)
  })

  it('shows twelve compact cards per page and never loads charts while listing or paging', async () => {
    vi.mocked(searchStocks).mockResolvedValue(snapshot(25))
    await render()
    expect(searchStocks).not.toHaveBeenCalled()
    expect(host.querySelector('input[type="checkbox"]')).toBeNull()
    expect(host.querySelector('input[type="number"]')).toBeNull()
    await search(' 东方 ')
    expect(searchStocks).toHaveBeenCalledWith('东方', true, expect.any(AbortSignal), defaults)
    expect(host.querySelectorAll('article')).toHaveLength(12)
    expect(host.textContent).not.toContain('共 25 只')
    expect(host.textContent).not.toContain('更新于')
    expect(host.querySelector('[aria-label="刷新搜索结果"]')).toBeNull()
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('共 25 只'), expect.objectContaining({ key: 'batch-stock-search' }))
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('剔除创业板 · 剔除科创板 · 剔除 ST'), expect.any(Object))
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('更新于 2026-11-12 15:00:00'), expect.any(Object))
    expect(host.textContent).toContain('+2.50%')
    expect(host.textContent).toContain('换手率3.21%')
    expect(host.textContent).toContain('总市值100.00 亿')
    expect(host.textContent).toContain('流通市值待补充')
    expect(host.textContent).toContain('成交额2.00 亿')
    await click('下一页')
    expect(host.querySelectorAll('article')).toHaveLength(12)
    expect(host.querySelector('article')?.textContent).toContain('东方12')
    await click('下一页')
    expect(host.querySelectorAll('article')).toHaveLength(1)
    expect(host.querySelector('article')?.textContent).toContain('东方24')
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="下一页"]')?.disabled).toBe(true)
    expect(searchStocks).toHaveBeenCalledTimes(1)
    expect(fetchStockDaily).not.toHaveBeenCalled()
    expect(fetchStockIntraday).not.toHaveBeenCalled()
    expect(host.querySelector('svg[role="img"]')).toBeNull()
  })

  it('opens only the selected stock daily chart and keeps the page after closing', async () => {
    vi.mocked(searchStocks).mockResolvedValue(snapshot(25))
    await render()
    await search()
    await click('下一页')
    await click('查看东方12详情')
    expect(fetchStockDaily).toHaveBeenCalledExactlyOnceWith('600012')
    expect(fetchStockIntraday).not.toHaveBeenCalled()
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('东方12')
    expect(document.querySelector('[role="dialog"] svg[role="img"]')).not.toBeNull()
    await click('关闭详情', document)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(host.querySelector('article')?.textContent).toContain('东方12')
  })

  it('submits exclusions and inclusive ranges in displayed units, including a negative change', async () => {
    await render()
    await click('高级搜索')
    expect(document.querySelector<HTMLInputElement>('input[name="excludeChiNext"]')?.checked).toBe(true)
    expect(document.querySelector<HTMLInputElement>('input[name="excludeStar"]')?.checked).toBe(true)
    expect(document.querySelector<HTMLInputElement>('input[name="excludeSt"]')?.checked).toBe(true)
    expect(document.querySelector<HTMLInputElement>('input[name="excludeBeijing"]')?.checked).toBe(false)
    await fill('input[name="minChangePercent"]', '-2')
    await fill('input[name="maxChangePercent"]', '5')
    await fill('input[name="minMarketCap"]', '50')
    await fill('input[name="maxMarketCap"]', '300')
    await fill('input[name="minTurnoverRate"]', '1')
    await click('应用条件')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(searchStocks).not.toHaveBeenCalled()
    await search()
    expect(searchStocks).toHaveBeenLastCalledWith('东方', true, expect.any(AbortSignal), {
      ...defaults, minChangePercent: -2, maxChangePercent: 5,
      minMarketCap: 50, maxMarketCap: 300, minTurnoverRate: 1
    })
    expect(host.querySelector('[aria-label="已应用筛选"]')).toBeNull()
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('总市值 50～300亿元'), expect.any(Object))
  })

  it('allows conditions without a keyword and rejects invalid or empty searches before requesting', async () => {
    await render()
    await click('高级搜索')
    await click('清空筛选')
    await click('应用条件')
    await search('')
    expect(searchStocks).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('至少一个筛选条件'), expect.any(Object))
    await click('高级搜索')
    await fill('input[name="minMarketCap"]', '300')
    await fill('input[name="maxMarketCap"]', '50')
    await click('应用条件')
    expect(searchStocks).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('总市值下限不能大于上限'))
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
    await fill('input[name="maxMarketCap"]', '')
    await click('应用条件')
    vi.mocked(searchStocks).mockResolvedValue({ ...snapshot(), keyword: '' })
    await search('')
    expect(searchStocks).toHaveBeenCalledWith('', true, expect.any(AbortSignal), { minMarketCap: 300 })
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('条件筛选'), expect.any(Object))
  })

  it('discards cancelled advanced edits and refreshes on every new search', async () => {
    vi.mocked(searchStocks).mockResolvedValue(snapshot(25))
    await render()
    await click('高级搜索')
    await fill('input[name="minMarketCap"]', '50')
    await click('应用条件')
    await search()
    await click('下一页')
    await click('高级搜索')
    await fill('input[name="minMarketCap"]', '100')
    await click('取消')
    await search('东方')
    expect(searchStocks).toHaveBeenLastCalledWith('东方', true, expect.any(AbortSignal), { ...defaults, minMarketCap: 50 })
    expect(host.querySelector('article')?.textContent).toContain('东方0')
    await click('高级搜索')
    await click('清空筛选')
    await click('应用条件')
    await search('科技')
    expect(searchStocks).toHaveBeenLastCalledWith('科技', true, expect.any(AbortSignal), {})
  })

  it('explains incomplete directories and missing metrics rather than presenting a definitive empty result', async () => {
    vi.mocked(searchStocks).mockResolvedValue({ ...snapshot(0), complete: false, universeCount: 120, expectedCount: 5500, unverifiedCount: 3 })
    await render()
    await search()
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('股票清单尚未完整'), expect.any(Object))
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('120 / 5500'), expect.any(Object))
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('3 只股票缺少筛选所需指标'), expect.any(Object))
    expect(host.textContent).not.toContain('股票清单尚未完整')
    expect(host.textContent).toContain('当前可核实的数据中没有匹配股票')
  })

  it('ignores an older search response and reports failures separately from empty results', async () => {
    const previous = deferred<StockSearchSnapshot>()
    vi.mocked(searchStocks).mockReturnValueOnce(previous.promise).mockResolvedValueOnce({ ...snapshot(0), keyword: '不存在' })
    await render()
    await search()
    await search('不存在')
    await act(async () => { previous.resolve(snapshot()) })
    expect(host.querySelectorAll('article')).toHaveLength(0)
    expect(toast.info).toHaveBeenCalledWith(expect.stringContaining('“不存在”'), expect.any(Object))
    expect(toast.success).not.toHaveBeenCalled()
    vi.mocked(searchStocks).mockRejectedValueOnce(new Error('行情源超时'))
    await search()
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('行情源超时'), expect.any(Object))
    expect(host.textContent).not.toContain('没有找到匹配股票')
  })
})
