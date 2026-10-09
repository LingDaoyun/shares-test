// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermLimitUpBoard, fetchShortTermLimitUpIndustry, fetchShortTermMarketBreadth } from '../../api/client'
import { LimitUpBoardPanel } from './LimitUpBoardPanel'
import type { ShortTermLimitUpBoardSnapshot, ShortTermLimitUpStock } from '../../types'

vi.mock('../../api/client', () => ({
  fetchShortTermLimitUpBoard: vi.fn(), fetchShortTermMarketBreadth: vi.fn(), fetchShortTermLimitUpIndustry: vi.fn()
}))

const sealed: ShortTermLimitUpStock = {
  symbol: '600001', name: '封板样本', industry: '半导体', latestPrice: 11, changePercent: 10,
  amount: 100000000, turnoverRate: 2, consecutiveBoards: 2, statDays: 2, statBoards: 2,
  sealFunds: 20000000, firstSealTime: '09:35:00', lastSealTime: '09:35:00', sealBreakCount: 0
}
const board: ShortTermLimitUpBoardSnapshot = {
  tradeDate: '2026-10-08', fetchedAt: '2026-10-08T07:00:00Z', available: true, unavailableReason: null,
  stocks: [sealed], brokenBoardAvailable: true, brokenStocks: [],
  industryStats: [{ industry: '半导体', limitUpCount: 1, maxConsecutiveBoards: 2, totalAmount: 100000000, leaders: ['封板样本'] }],
  sentiment: { limitUpCount: 1, brokenCount: 0, limitDownCount: 0, sealBreakRatioPercent: 0,
    maxConsecutiveBoards: 2, boards2PlusCount: 1, boards3PlusCount: 0, sealedBeforeTenCount: 1,
    sealedMorningCount: 0, sealedAfternoonCount: 0, sealedTailCount: 0, earlySealSharePercent: 100,
    tone: '中性震荡', explanation: '测试快照' }, marketTurnover: null, dataGaps: []
}
const details = {
  tradeDate: board.tradeDate, industry: '半导体', fetchedAt: board.fetchedAt, limitUpCount: 1,
  limitUpStocks: [sealed], risingAvailable: true, dataGaps: [], quoteSource: '东方财富行业行情',
  risingStocks: Array.from({ length: 30 }, (_, i) => ({ symbol: String(600100 + i), name: `上涨样本${i}`,
    latestPrice: 10, changePercent: 8 - i / 10, amount: 100000000, turnoverRate: 2,
    marketTimestamp: '2026-10-08T07:00:00Z' }))
}

describe('Limit-up industry third-level detail', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(null as never)
    vi.mocked(fetchShortTermLimitUpIndustry).mockResolvedValue(details)
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function openIndustry() {
    await act(async () => { root.render(<LimitUpBoardPanel />) })
    await act(async () => { Array.from(host.querySelectorAll('button')).find(b => b.textContent === '查看看板')?.click() })
    const button = document.querySelector<HTMLButtonElement>('button[aria-label="查看半导体涨停及上涨股票"]')
    expect(button).not.toBeNull()
    await act(async () => { button?.focus(); button?.click() })
  }
  function topDialog() {
    const dialogs = document.querySelectorAll<HTMLElement>('[role="dialog"]')
    return dialogs[dialogs.length - 1]
  }

  it('opens the third-level detail with the board date, all sealed stocks and thirty rising stocks', async () => {
    await openIndustry()

    expect(fetchShortTermLimitUpIndustry).toHaveBeenCalledWith('半导体', '2026-10-08')
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(2)
    expect(topDialog().textContent).toContain('半导体 · 涨停与上涨股票')
    expect(topDialog().textContent).toContain('已涨停 · 1 家')
    expect(topDialog().textContent).toContain('未涨停上涨股 · 30 家')
    expect(topDialog().textContent).toContain('最多 30 家')
    expect(topDialog().querySelectorAll('tbody tr')).toHaveLength(31)
    expect(topDialog().textContent).toContain('600001')
    expect(topDialog().textContent).toContain('600129')
  })

  it('returns to the existing board without reloading it when the third-level detail closes', async () => {
    await openIndustry()
    const back = Array.from(topDialog().querySelectorAll('button')).find(b => b.textContent?.includes('返回涨停看板'))
    await act(async () => { back?.click() })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(topDialog().textContent).toContain('行业聚合')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
  })

  it('keeps the sealed stocks on a failed detail request and allows retry', async () => {
    vi.mocked(fetchShortTermLimitUpIndustry).mockRejectedValueOnce(new Error('行业行情超时'))
    await openIndustry()

    expect(topDialog().textContent).toContain('封板样本')
    expect(topDialog().textContent).toContain('行业行情超时')
    const retry = Array.from(topDialog().querySelectorAll('button')).find(b => b.textContent?.includes('重试'))
    expect(retry).toBeDefined()
    await act(async () => { retry?.click() })
    expect(topDialog().textContent).toContain('上涨样本0')
    expect(fetchShortTermLimitUpIndustry).toHaveBeenCalledTimes(2)
  })

  it('Escape closes only the industry layer and restores focus to its count button', async () => {
    await openIndustry()
    await act(async () => { topDialog().dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', bubbles: true, cancelable: true
    })) })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(topDialog().textContent).toContain('行业聚合')
    expect(document.body.style.overflow).toBe('hidden')
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停及上涨股票')
  })

  it('an immediate Escape before focus transfers still closes only the industry layer', async () => {
    vi.mocked(window.requestAnimationFrame).mockImplementation(() => 1)
    await openIndustry()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停及上涨股票')
    await act(async () => { document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', bubbles: true, cancelable: true
    })) })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(topDialog().textContent).toContain('涨停看板 · 市场情绪')
    expect(topDialog().textContent).toContain('行业聚合')
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停及上涨股票')
  })

  it('rejects a response for a different trading date while keeping the selected sealed stocks', async () => {
    vi.mocked(fetchShortTermLimitUpIndustry).mockResolvedValue({ ...details, tradeDate: '2026-10-09' })
    await openIndustry()

    expect(topDialog().textContent).toContain('封板样本')
    expect(topDialog().textContent).toContain('日期不一致')
    expect(topDialog().textContent).not.toContain('上涨样本0')
  })

  it('shows a missing-data message while still displaying the sealed list', async () => {
    vi.mocked(fetchShortTermLimitUpIndustry).mockResolvedValue({ ...details, risingAvailable: false,
      risingStocks: [], dataGaps: ['行业未涨停上涨股暂缺：日期不匹配'] })
    await openIndustry()

    expect(topDialog().textContent).toContain('封板样本')
    expect(topDialog().textContent).toContain('日期不匹配')
    expect(topDialog().textContent).not.toContain('未涨停上涨股 · 0 家')
  })
})
