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
const availableBoard: ShortTermLimitUpBoardSnapshot = {
  ...board,
  available: true,
  unavailableReason: null,
  stocks: [{
    symbol: '600001', name: '封板样本', industry: '半导体', latestPrice: 11,
    changePercent: 10, amount: 100000000, turnoverRate: 2, consecutiveBoards: 2,
    statDays: 2, statBoards: 2, sealFunds: 10000000, firstSealTime: '09:25:00',
    lastSealTime: '09:25:00', sealBreakCount: 0,
  }],
  sentiment: {
    limitUpCount: 1, brokenCount: 0, limitDownCount: 0, sealBreakRatioPercent: 0,
    maxConsecutiveBoards: 2, boards2PlusCount: 1, boards3PlusCount: 0,
    sealedBeforeTenCount: 1, sealedMorningCount: 0, sealedAfternoonCount: 0,
    sealedTailCount: 0, earlySealSharePercent: 100, tone: '中性震荡', explanation: '测试市场情绪',
  },
}

describe('LimitUpBoardPanel independent breadth loading', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(breadth)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
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
    const button = Array.from(document.body.querySelectorAll('button')).find((item) => item.textContent === label)
    expect(button).toBeDefined()
    expect(button?.disabled).toBe(false)
    await act(async () => { button?.focus(); button?.click() })
    return button!
  }

  async function openMarketDialog() {
    return clickButton('市场情绪与行业分布')
  }

  it('keeps market data hidden until the market sentiment button is opened', async () => {
    await renderPanel()

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(host.textContent).not.toContain('最新可用涨跌数据')
    expect(host.textContent).not.toContain('上涨3123 家')
    expect(host.textContent).not.toContain('2026-09-11')
    const trigger = host.querySelector('button[aria-haspopup="dialog"]')
    expect(trigger?.textContent).toBe('市场情绪与行业分布')
    expect(trigger?.getAttribute('aria-expanded')).toBe('false')
    await openMarketDialog()
    expect(trigger?.getAttribute('aria-expanded')).toBe('true')
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).toContain('下跌2100 家')
    expect(document.body.textContent).toContain('平盘81 家')
    expect(document.body.textContent).toContain('本次行情清单 5314 只')
    expect(document.body.textContent).not.toContain('全 A 股')
    expect(document.body.textContent).toContain('排除无当日报价、价格或涨跌幅缺失的 10 只')
    expect(document.body.textContent).toContain('2026-09-11')
    expect(document.body.textContent).toContain('东方财富行情')
  })

  it.each(['close button', 'Escape'])('keeps industry cards on the homepage and restores focus after %s', async closeMethod => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    const card = host.querySelector('button[aria-label="查看半导体涨停明细"]')
    expect(card?.textContent).toContain('封板样本')
    expect(host.textContent).not.toContain('测试市场情绪')
    expect(host.textContent).not.toContain('2026-09-11')
    const trigger = await openMarketDialog()
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    expect(dialog.querySelector('h2')?.textContent).toBe('市场情绪与行业分布')
    expect(dialog.textContent).toContain('测试市场情绪')
    expect(dialog.textContent).toContain('2026-09-11 10:00:00')
    expect(dialog.querySelector('section[aria-label="涨停行业卡片"]')).toBeNull()
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).toBe(card)
    expect(document.body.style.overflow).toBe('hidden')

    await act(async () => {
      if (closeMethod === 'Escape') {
        dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
      } else {
        dialog.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click()
      }
    })

    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    expect(document.body.style.overflow).not.toBe('hidden')
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).toBe(card)
    expect(host.textContent).not.toContain('测试市场情绪')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
  })

  it('refreshes the homepage cards and market dialog from the same snapshot', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await openMarketDialog()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...availableBoard,
      fetchedAt: '2026-09-11T02:05:00Z',
      stocks: [...availableBoard.stocks, { ...availableBoard.stocks[0], symbol: '600002', name: '新增封板样本' }],
      sentiment: { ...availableBoard.sentiment!, limitUpCount: 2 },
    })
    await clickButton('刷新')

    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')?.textContent).toContain('涨停 2 家')
    expect(host.textContent).toContain('新增封板样本')
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('共 2 只涨停')
    expect(dialog.textContent).toContain('2026-09-11 10:05:00')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
  })

  it('shows the board while breadth is pending and then updates the counts', async () => {
    let resolveBreadth!: (value: ShortTermMarketBreadth) => void
    vi.mocked(fetchShortTermMarketBreadth).mockReturnValue(new Promise((resolve) => { resolveBreadth = resolve }))
    await renderPanel()
    await openMarketDialog()

    expect(document.body.textContent).toContain('当日涨停池为空')
    expect(document.body.textContent).toContain('正在获取涨跌家数')
    await act(async () => { resolveBreadth(breadth) })
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).not.toContain('正在获取涨跌家数')
  })

  it('keeps breadth available when the limit-up pool request fails', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockRejectedValue(new Error('涨停池超时'))
    await renderPanel()

    expect(host.textContent).toContain('获取失败：涨停池超时')
    await openMarketDialog()
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).toContain('本次行情清单 5314 只')
  })

  it('refreshes both sources and labels preserved counts when the refresh fails', async () => {
    await renderPanel()
    await openMarketDialog()
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValueOnce(new Error('全市场行情覆盖不足'))
    await clickButton('刷新')

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).toContain('保留上次快照')
    expect(document.body.textContent).toContain('全市场行情覆盖不足')
    expect(document.body.textContent).toContain('请注意统计时间')
  })

  it('does not display missing breadth as zero counts', async () => {
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValue(new Error('全市场行情覆盖不足'))
    await renderPanel()
    await openMarketDialog()

    expect(document.body.textContent).toContain('涨跌家数更新失败')
    expect(document.body.textContent).not.toContain('上涨0 家')
    expect(document.body.textContent).not.toContain('下跌0 家')
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
    await openMarketDialog()
    expect(document.body.textContent).toContain('上涨429 家')
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
  })

  it('reuses the in-flight request during StrictMode effect replay', async () => {
    await act(async () => { root.render(<StrictMode><LimitUpBoardPanel /></StrictMode>) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    await openMarketDialog()
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
  })

  it('restarts the auto-refresh delay after a manual refresh and preserves data when auto-refresh fails', async () => {
    vi.useFakeTimers()
    await renderPanel()
    await openMarketDialog()
    await act(async () => { await vi.advanceTimersByTimeAsync(50000) })
    await clickButton('刷新')
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    vi.mocked(fetchShortTermMarketBreadth).mockRejectedValueOnce(new Error('刷新超时'))
    await act(async () => { await vi.advanceTimersByTimeAsync(50000) })
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(3)
    expect(document.body.textContent).toContain('上涨3123 家')
    expect(document.body.textContent).toContain('涨跌家数更新失败：刷新超时')
    expect(document.body.textContent).toContain('当前保留上次快照')
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
