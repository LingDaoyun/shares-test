// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermLimitUpBoard, fetchShortTermLimitUpIndustry, fetchShortTermMarketBreadth } from '../../api/client'
import { LimitUpBoardPanel } from './LimitUpBoardPanel'
import type { ShortTermLimitUpBoardSnapshot, ShortTermLimitUpStock } from '../../types'

vi.mock('../../api/client', () => ({
  fetchShortTermLimitUpBoard: vi.fn(), fetchShortTermMarketBreadth: vi.fn(), fetchShortTermLimitUpIndustry: vi.fn(),
  fetchShortTermCatchUp: vi.fn(), fetchShortTermCatchUpFollowUp: vi.fn()
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
describe('Limit-up industry third-level detail', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(null as never)
    vi.mocked(fetchShortTermLimitUpIndustry).mockResolvedValue({
      tradeDate: board.tradeDate, industry: '半导体', fetchedAt: board.fetchedAt,
      limitUpCount: 1, limitUpStocks: [sealed], risingStocks: [], risingAvailable: true,
      dataGaps: [], quoteSource: null,
    })
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function openBoard() {
    await act(async () => { root.render(<LimitUpBoardPanel />) })
  }
  async function openIndustry() {
    await openBoard()
    const button = document.querySelector<HTMLButtonElement>('section[aria-label="涨停行业卡片"] button')
    expect(button).not.toBeNull()
    await act(async () => { button?.focus(); button?.click() })
  }
  function topDialog() {
    const dialogs = document.querySelectorAll<HTMLElement>('[role="dialog"]')
    return dialogs[dialogs.length - 1]
  }

  it('opens all industry stocks from a three-stock card preview and preserves full seal details', async () => {
    const stocks = Array.from({ length: 4 }, (_, index) => ({
      ...sealed, symbol: `60000${index}`, name: `行业样本${index}`,
      consecutiveBoards: 4 - index, statDays: 5, statBoards: 4,
      lastSealTime: '14:15:00', sealBreakCount: 2,
    }))
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...board, stocks, industryStats: [] })
    await openBoard()

    const card = host.querySelector<HTMLButtonElement>('button[aria-label="查看半导体涨停明细"]')
    expect(card).not.toBeNull()
    expect(card?.textContent).toContain('涨停 4 家')
    expect(card?.textContent).toContain('行业样本2')
    expect(card?.textContent).not.toContain('行业样本3')
    expect(host.textContent).not.toContain('查看看板')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(host.querySelector('table')).toBeNull()
    await act(async () => { card?.focus(); card?.click() })

    const table = topDialog().querySelector('table[aria-label="已涨停股票"]')
    expect(Array.from(table!.querySelectorAll('tbody tr')).map(row => row.querySelector('td')?.textContent))
      .toEqual(stocks.map(stock => `${stock.name}${stock.symbol}`))
    expect(table?.textContent).toContain('5天4板')
    expect(table?.textContent).toContain('末次封板')
    expect(table?.textContent).toContain('14:15')
    expect(table?.textContent).toContain('炸板次数')
    expect(table?.textContent).toContain('2 次')
    expect(table?.textContent).toContain('封板资金')
    expect(table?.textContent).toContain('0.20 亿')

    const back = Array.from(topDialog().querySelectorAll('button')).find(button => button.textContent?.includes('返回涨停看板'))
    await act(async () => { back?.click() })
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(0)
    expect(document.activeElement).toBe(card)
    expect(host.querySelector('table')).toBeNull()
  })

  it('shows the sealed snapshot without the removed rising and saved-feedback modules or a detail request', async () => {
    await openIndustry()

    expect(fetchShortTermLimitUpIndustry).not.toHaveBeenCalled()
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(topDialog().textContent).toContain('半导体 · 涨停明细')
    expect(topDialog().textContent).toContain('已涨停 · 1 家')
    expect(topDialog().textContent).not.toContain('未涨停上涨股')
    expect(topDialog().textContent).not.toContain('首次留样与隔日价格反馈')
    expect(topDialog().textContent).toContain('关联补涨观察')
    expect(topDialog().querySelectorAll('tbody tr')).toHaveLength(1)
    expect(topDialog().textContent).toContain('600001')
  })

  it('keeps the complete selected snapshot including normalized industry members', async () => {
    const stocks = [sealed, {
      ...sealed, symbol: '600002', name: '空格行业样本', industry: ' 半导体 ',
    }]
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...board, stocks, industryStats: [] })
    await openIndustry()

    const table = topDialog().querySelector('table[aria-label="已涨停股票"]')
    expect(table?.querySelectorAll('tbody tr')).toHaveLength(2)
    expect(table?.textContent).toContain('空格行业样本')
    expect(topDialog().textContent).toContain('已涨停 · 2 家')
    expect(topDialog().textContent).toContain('涨停快照 2026-10-08 15:00:00')
    expect(fetchShortTermLimitUpIndustry).not.toHaveBeenCalled()
  })

  it('returns to the existing board without reloading it when the third-level detail closes', async () => {
    await openIndustry()
    const back = Array.from(topDialog().querySelectorAll('button')).find(b => b.textContent?.includes('返回涨停看板'))
    await act(async () => { back?.click() })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(0)
    expect(host.textContent).toContain('涨停明细')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
  })

  it('opens industry details after closing market sentiment without reloading the home board', async () => {
    await openBoard()
    const marketButton = Array.from(host.querySelectorAll('button'))
      .find(button => button.textContent === '市场情绪与行业分布')
    expect(marketButton).toBeDefined()
    await act(async () => { marketButton!.focus(); marketButton!.click() })
    expect(topDialog().textContent).toContain('最新可用涨跌数据')
    await act(async () => { topDialog().querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click() })

    const industryCard = host.querySelector<HTMLButtonElement>('button[aria-label="查看半导体涨停明细"]')!
    await act(async () => { industryCard.focus(); industryCard.click() })
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(topDialog().textContent).toContain('半导体 · 涨停明细')
    expect(topDialog().textContent).not.toContain('最新可用涨跌数据')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
  })

  it('Escape closes the industry layer and restores focus to its homepage card', async () => {
    await openIndustry()
    await act(async () => { topDialog().dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', bubbles: true, cancelable: true
    })) })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(0)
    expect(host.textContent).toContain('涨停明细')
    expect(document.body.style.overflow).not.toBe('hidden')
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停明细')
  })

  it('an immediate Escape before focus transfers still closes only the industry layer', async () => {
    vi.mocked(window.requestAnimationFrame).mockImplementation(() => 1)
    await openIndustry()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停明细')
    await act(async () => { document.activeElement?.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Escape', bubbles: true, cancelable: true
    })) })

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(0)
    expect(host.textContent).toContain('市场情绪与行业分布')
    expect(host.textContent).toContain('涨停明细')
    expect(document.activeElement?.getAttribute('aria-label')).toBe('查看半导体涨停明细')
  })
})
