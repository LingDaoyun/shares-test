// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth } from '../../api/client'
import type { ShortTermLimitUpBoardSnapshot, ShortTermMarketBreadth } from '../../types'
import { LimitUpBoardPanel } from './LimitUpBoardPanel'

vi.mock('../../api/client', () => ({
  fetchShortTermLimitUpBoard: vi.fn(),
  fetchShortTermMarketBreadth: vi.fn()
}))

const board: ShortTermLimitUpBoardSnapshot = {
  tradeDate: '2026-09-11', fetchedAt: '2026-09-11T02:00:00Z',
  available: false, unavailableReason: '当日涨停池为空',
  stocks: [], brokenBoardAvailable: false, brokenStocks: [], industryStats: [],
  sentiment: null, marketTurnover: null, dataGaps: []
}
const breadth: ShortTermMarketBreadth = {
  tradeDate: '2026-09-11', fetchedAt: '2026-09-11T02:01:00Z',
  advancing: 3123, declining: 2100, unchanged: 81, excludedCount: 10, totalCount: 5314,
  source: '东方财富行情'
}

describe('LimitUpBoardPanel independent breadth loading', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(breadth)
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  async function renderPanel() {
    await act(async () => { root.render(<LimitUpBoardPanel />) })
  }

  async function clickButton(label: string) {
    const button = Array.from(host.querySelectorAll('button')).find((item) => item.textContent === label)
    expect(button).toBeDefined()
    expect(button?.disabled).toBe(false)
    await act(async () => { button?.click() })
  }

  it('shows the complete board on mount without an entry button or scan even if the pool is empty', async () => {
    await renderPanel()

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
    expect(host.textContent).toContain('下跌2100 家')
    expect(host.textContent).toContain('平盘81 家')
    expect(document.body.textContent).toContain('本次行情清单 5314 只')
    expect(document.body.textContent).not.toContain('全 A 股')
    expect(document.body.textContent).toContain('排除无当日报价、价格或涨跌幅缺失的 10 只')
    expect(document.body.textContent).toContain('2026-09-11')
    expect(document.body.textContent).toContain('东方财富行情')
  })

  it('shows the board while breadth is pending and then updates the counts', async () => {
    let resolveBreadth!: (value: ShortTermMarketBreadth) => void
    vi.mocked(fetchShortTermMarketBreadth).mockReturnValue(new Promise((resolve) => { resolveBreadth = resolve }))
    await renderPanel()

    expect(host.textContent).toContain('当日涨停池为空')
    expect(host.textContent).toContain('正在获取涨跌家数')
    await act(async () => { resolveBreadth(breadth) })
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
    expect(host.textContent).not.toContain('正在获取涨跌家数')
  })

  it('keeps breadth available when the limit-up pool request fails', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockRejectedValue(new Error('涨停池超时'))
    await renderPanel()

    expect(host.textContent).toContain('获取失败：涨停池超时')
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).toContain('本次行情清单 5314 只')
  })

  it('refreshes both sources and labels preserved counts when the refresh fails', async () => {
    await renderPanel()
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValueOnce(new Error('全市场行情覆盖不足'))
    await clickButton('刷新')

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
    expect(host.textContent).toContain('保留上次快照')
    expect(document.body.textContent).toContain('全市场行情覆盖不足')
    expect(document.body.textContent).toContain('请注意统计时间')
  })

  it('does not display missing breadth as zero counts', async () => {
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValue(new Error('全市场行情覆盖不足'))
    await renderPanel()

    expect(host.textContent).toContain('涨跌家数更新失败')
    expect(host.textContent).not.toContain('上涨0 家')
    expect(host.textContent).not.toContain('下跌0 家')
    expect(document.body.textContent).toContain('全市场行情覆盖不足')
  })

  it('refreshes 60 seconds after completion and does not overlap a slow request', async () => {
    vi.useFakeTimers()
    let resolveBreadth!: (value: ShortTermMarketBreadth) => void
    vi.mocked(fetchShortTermMarketBreadth).mockReturnValueOnce(new Promise(resolve => { resolveBreadth = resolve }))
    await renderPanel()
    await act(async () => { await vi.advanceTimersByTimeAsync(120000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)

    await act(async () => { resolveBreadth(breadth) })
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue({ ...breadth, advancing: 429 })
    await act(async () => { await vi.advanceTimersByTimeAsync(59999) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(1) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('上涨429 家')
  })

  it('reuses the in-flight request during StrictMode effect replay', async () => {
    await act(async () => { root.render(<StrictMode><LimitUpBoardPanel /></StrictMode>) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
  })

  it('restarts the auto-refresh delay after a manual refresh and preserves data when auto-refresh fails', async () => {
    vi.useFakeTimers()
    await renderPanel()
    await act(async () => { await vi.advanceTimersByTimeAsync(50000) })
    await clickButton('刷新')
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValueOnce(new Error('刷新超时'))
    await act(async () => { await vi.advanceTimersByTimeAsync(50000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(3)
    expect(host.textContent).not.toContain('查看看板')
    expect(host.textContent).toContain('上涨3123 家')
    expect(host.textContent).toContain('涨跌家数更新失败：刷新超时')
    expect(host.textContent).toContain('当前保留上次快照')
  })

  it('stops polling after unmount even when the pending request resolves later', async () => {
    vi.useFakeTimers()
    let resolveBreadth!: (value: ShortTermMarketBreadth) => void
    vi.mocked(fetchShortTermMarketBreadth).mockReturnValueOnce(new Promise(resolve => { resolveBreadth = resolve }))
    await renderPanel()
    await act(async () => { root.unmount() })
    root = createRoot(host)
    await act(async () => { resolveBreadth(breadth); await vi.advanceTimersByTimeAsync(120000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(host.textContent).toBe('')
  })
})
