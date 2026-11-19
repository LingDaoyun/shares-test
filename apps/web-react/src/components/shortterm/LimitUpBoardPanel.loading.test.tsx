// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermLimitBoardContext, fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth, fetchShortTermMarketFundDirection, fetchStockDaily, fetchStockIntraday } from '../../api/client'
import type { ShortTermLimitUpBoardSnapshot, ShortTermMarketBreadth } from '../../types'
import { LimitUpBoardPanel } from './LimitUpBoardPanel'
import { fetchMarketThemes } from '../../api/marketThemes'

vi.mock('../../api/marketThemes', () => ({ fetchMarketThemes: vi.fn(), fetchMarketThemeDetail: vi.fn() }))

vi.mock('../../api/client', () => ({
  fetchShortTermLimitBoardContext: vi.fn(),
  fetchShortTermLimitUpBoard: vi.fn(),
  fetchShortTermMarketBreadth: vi.fn(),
  fetchShortTermMarketFundDirection: vi.fn(),
  fetchStockDaily: vi.fn(),
  fetchStockIntraday: vi.fn()
}))
vi.mock('./CatchUpFollowUpPanel', () => ({
  CatchUpFollowUpPanel: () => <p>观察反馈测试内容</p>,
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
const boardContext = {
  tradeDate: board.tradeDate, source: '复盘测试来源', sourceUrl: 'https://example.com/review', up: {}, down: {}
}

function industryBoard(count: number): ShortTermLimitUpBoardSnapshot {
  return { ...availableBoard, stocks: Array.from({ length: count }, (_, index) => ({
    ...availableBoard.stocks[0], symbol: String(600000 + index), industry: `行业${index}`, name: `封板样本${index}`,
  })) }
}

describe('LimitUpBoardPanel independent breadth loading', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermLimitBoardContext).mockResolvedValue(boardContext)
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(breadth)
    vi.mocked(fetchShortTermMarketFundDirection).mockResolvedValue({
      topInflows: [], topOutflows: [], coveredIndustryCount: 0, expectedIndustryCount: 0,
      coverageRatio: null, tradeDate: null, fetchedAt: null, sourceName: '行业资金流未返回', dataGaps: []
    })
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
    expect(fetchMarketThemes).not.toHaveBeenCalled()
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

  function industryCards() {
    return [...host.querySelectorAll<HTMLButtonElement>('section[aria-label="涨停行业卡片"] button[aria-label$="涨停明细"]')]
  }

  async function jumpToPage(page: number) {
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!
    expect(select).not.toBeNull()
    await act(async () => { select.value = String(page); select.dispatchEvent(new Event('change', { bubbles: true })) })
  }

  it('toggles first-board stocks by industry and keeps industry details within the filter', async () => {
    const stock = availableBoard.stocks[0]
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...availableBoard, stocks: [
      { ...stock, name: '芯片连板', consecutiveBoards: 3 },
      { ...stock, symbol: '600002', name: '芯片首板', industry: ' 半导体 ', consecutiveBoards: 1, statDays: 5, statBoards: 3 },
      { ...stock, symbol: '600003', name: '设备首板', industry: '专用设备', consecutiveBoards: 1 },
      { ...stock, symbol: '600004', name: '未分类首板', industry: ' ', consecutiveBoards: 1 },
      { ...stock, symbol: '600005', name: '连板未知', industry: '未知行业', consecutiveBoards: 0, statDays: 1, statBoards: 1 },
    ] })
    await renderPanel()
    await clickButton('行业涨停')
    const toggle = await clickButton('首板涨停')

    expect(toggle.getAttribute('aria-pressed')).toBe('true')
    expect(industryCards().map(card => card.getAttribute('aria-label'))).toEqual([
      '查看半导体涨停明细', '查看专用设备涨停明细', '查看未分类涨停明细',
    ])
    expect(host.textContent).toContain('3 个行业 · 3 只涨停')
    expect(industryCards()[0].textContent).toContain('芯片首板')
    expect(industryCards()[0].textContent).toContain('涨停 1 家')
    expect(host.textContent).not.toContain('芯片连板')
    expect(host.textContent).not.toContain('连板未知')
    await act(async () => { industryCards()[0].click() })
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('半导体 · 首板涨停明细')
    expect(dialog.querySelectorAll('tbody tr')).toHaveLength(1)
    expect(dialog.textContent).toContain('芯片首板')
    expect(dialog.textContent).not.toContain('芯片连板')
    await clickButton('返回涨停看板')

    await clickButton('涨停梯队')
    expect(host.querySelector('section[aria-label="涨停梯队"]')?.textContent).toContain('芯片连板')
    await clickButton('行业涨停')
    expect(host.textContent).not.toContain('芯片连板')

    const restoredToggle = await clickButton('首板涨停')
    expect(restoredToggle.getAttribute('aria-pressed')).toBe('false')
    expect(industryCards()).toHaveLength(4)
    expect(host.textContent).toContain('芯片连板')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
  })

  it('resets industry pagination when switching scope and retains first-board filtering after refresh', async () => {
    const snapshot = industryBoard(13)
    snapshot.stocks = snapshot.stocks.map((stock, index) => ({ ...stock, consecutiveBoards: index >= 6 ? 1 : 2 }))
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(snapshot)
    await renderPanel()
    await clickButton('行业涨停')
    await jumpToPage(3)
    await clickButton('首板涨停')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('1')
    expect(industryCards()[0].textContent).toContain('封板样本6')
    expect(industryCards()).toHaveLength(6)
    await jumpToPage(2)
    await clickButton('刷新涨停看板')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('2')
    expect(industryCards()).toHaveLength(1)
    expect(industryCards()[0].textContent).toContain('封板样本12')
    await clickButton('首板涨停')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('1')
    expect(industryCards()[0].textContent).toContain('封板样本0')
  })

  it('shows a first-board empty state without losing the full-market snapshot', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    await clickButton('首板涨停')
    expect(industryCards()).toHaveLength(0)
    expect(host.textContent).toContain('当前快照暂无首板涨停个股。')
    await openMarketDialog()
    expect(document.querySelector('[role="dialog"]')!.textContent).toContain('共 1 只涨停')
    await act(async () => { document.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click() })
    await clickButton('首板涨停')
    expect(industryCards()).toHaveLength(1)
    expect(host.textContent).toContain('封板样本')
  })

  it('opens the existing stock chart for the clicked ladder entry', async () => {
    const snapshot = industryBoard(2)
    const stock = snapshot.stocks[1]
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(snapshot)
    vi.mocked(fetchStockIntraday).mockResolvedValue({
      symbol: stock.symbol, tradeDate: snapshot.tradeDate, fetchedAt: snapshot.fetchedAt,
      source: '分时测试', volumeUnit: '手', points: []
    })
    vi.mocked(fetchStockDaily).mockResolvedValue({
      symbol: stock.symbol, adjustMode: 'QFQ', fetchedAt: snapshot.fetchedAt, source: '日线测试', volumeUnit: '手', bars: []
    })
    await renderPanel()
    expect(fetchStockIntraday).not.toHaveBeenCalled()
    expect(fetchStockDaily).not.toHaveBeenCalled()

    const trigger = host.querySelector<HTMLButtonElement>(`button[aria-label="查看${stock.name}分时和日线"]`)
    expect(trigger).not.toBeNull()
    await act(async () => { trigger!.focus(); trigger!.click() })
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain(`${stock.name} · ${stock.symbol} · 行情图`)
    expect(dialog.textContent).toContain(`看板 / 观察日 ${snapshot.tradeDate}`)
    expect(fetchStockIntraday).toHaveBeenCalledExactlyOnceWith(stock.symbol)
    expect(fetchStockDaily).not.toHaveBeenCalled()

    await clickButton('日线')
    expect(fetchStockDaily).toHaveBeenCalledExactlyOnceWith(stock.symbol)
    expect(dialog.querySelector('section[aria-label="日线行情"]')).not.toBeNull()
    await act(async () => { dialog.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!.click() })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })

  it('shows dated board reasons and switches to a descending limit-down ladder with its own themes', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...availableBoard, limitDownAvailable: true, limitDownStocks: [
        { symbol: '600002', name: '首跌样本', industry: '零售', consecutiveBoards: 1, lastSealTime: null },
        { symbol: '600003', name: '高位跌停', industry: '医药', consecutiveBoards: 3, lastSealTime: null },
        { symbol: '600004', name: '板数缺失', industry: null, consecutiveBoards: null, lastSealTime: null }
      ]
    })
    vi.mocked(fetchShortTermLimitBoardContext).mockResolvedValue({
      ...boardContext,
      up: { '600001': { topics: ['算力租赁'], reason: '拟收购算力资产' } },
      down: { '600003': { topics: ['医药流通'], reason: null } }
    })
    await renderPanel()
    expect(fetchShortTermLimitBoardContext).toHaveBeenCalledWith(board.tradeDate, expect.any(AbortSignal))
    expect(host.textContent).toContain('半导体')
    expect(host.textContent).toContain('算力租赁')
    expect(host.textContent).toContain('拟收购算力资产')
    expect(host.querySelector('a[href="https://example.com/review"]')?.textContent).toContain(boardContext.source)

    await clickButton('跌停天梯榜')
    expect(host.textContent).not.toContain('算力租赁')
    expect(Array.from(host.querySelectorAll('h3'), heading => heading.textContent)).toEqual(['3 板', '首跌停', '板数未知'])
    expect(host.textContent).toContain('行业 医药')
    expect(host.textContent).toContain('医药流通')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
  })

  it('never labels a stock with context from another trading date', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    vi.mocked(fetchShortTermLimitBoardContext).mockResolvedValue({
      ...boardContext, tradeDate: '2026-09-10', up: { '600001': { topics: ['昨天的题材'], reason: '昨天的原因' } }
    })
    await renderPanel()
    expect(host.textContent).toContain('暂无当日题材')
    expect(host.textContent).not.toContain('昨天的题材')
  })

  it('pages thirteen industries as six, six and one without reloading data or shrinking the content frame', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(industryBoard(13))
    await renderPanel()
    await clickButton('行业涨停')
    const frame = host.firstElementChild!
    expect(frame.classList.contains('h-full')).toBe(true)
    expect(frame.classList.contains('min-h-0')).toBe(true)
    expect(industryCards()).toHaveLength(6)
    expect(industryCards()[0].textContent).toContain('封板样本0')
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="上一页"]')!.disabled).toBe(true)
    const scrollRegion = host.querySelector<HTMLElement>('[role="region"][aria-label="涨停行业滚动区域"]')!
    expect(scrollRegion.classList.contains('overflow-y-auto')).toBe(true)
    expect(scrollRegion.tabIndex).toBe(0)
    expect(scrollRegion.querySelector('[aria-label="涨停行业分页"]')).toBeNull()
    scrollRegion.scrollTop = 400

    await clickButton('下一页')
    expect(industryCards()).toHaveLength(6)
    expect(industryCards()[0].textContent).toContain('封板样本6')
    expect(scrollRegion.scrollTop).toBe(0)
    await jumpToPage(3)
    expect(industryCards()).toHaveLength(1)
    expect(industryCards()[0].textContent).toContain('封板样本12')
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="下一页"]')!.disabled).toBe(true)
    expect(host.firstElementChild).toBe(frame)
    expect(frame.classList.contains('h-full')).toBe(true)
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    await clickButton('上一页')
    expect(industryCards()[0].textContent).toContain('封板样本6')
  })

  it('keeps the current page for same-day refreshes, clamps shrinking results and resets on a new trade date', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(industryBoard(13))
    await renderPanel()
    await clickButton('行业涨停')
    await jumpToPage(3)
    await clickButton('刷新涨停看板')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('3')

    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(industryBoard(8))
    await clickButton('刷新涨停看板')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('2')
    expect(industryCards()).toHaveLength(2)
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(industryBoard(13))
    await clickButton('刷新涨停看板')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('2')

    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...industryBoard(13), tradeDate: '2026-09-14' })
    await clickButton('刷新涨停看板')
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('1')
    expect(industryCards()[0].textContent).toContain('封板样本0')
  })

  it('returns to the same industry page and keeps the selected detail snapshot during refresh', async () => {
    const snapshot = industryBoard(13)
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(snapshot)
    await renderPanel()
    await clickButton('行业涨停')
    await jumpToPage(2)
    const card = industryCards()[0]
    await act(async () => { card.focus(); card.click() })
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('行业6 · 涨停明细')
    expect(dialog.textContent).toContain('封板样本6')
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...snapshot, stocks: snapshot.stocks.map(stock => ({ ...stock, name: `更新${stock.name}` })) })
    await clickButton('刷新涨停看板')
    expect(dialog.textContent).not.toContain('更新封板样本6')
    expect(industryCards()[0].textContent).toContain('更新封板样本6')
    const close = dialog.querySelector<HTMLButtonElement>('button[aria-label="关闭详情"]')!
    await act(async () => { close.click() })
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(host.querySelector<HTMLSelectElement>('select[aria-label="跳转页码"]')!.value).toBe('2')
    expect(document.activeElement).toBe(card)
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
  })

  it('keeps the same full-height frame for loading and an unavailable snapshot', async () => {
    let resolveBoard!: (value: ShortTermLimitUpBoardSnapshot) => void
    vi.mocked(fetchShortTermLimitUpBoard).mockReturnValueOnce(new Promise(resolve => { resolveBoard = resolve }))
    await renderPanel()
    const frame = host.firstElementChild!
    expect(frame.classList.contains('h-full')).toBe(true)
    expect(host.textContent).toContain('正在拉取涨停池数据')
    await act(async () => { resolveBoard(board) })
    expect(host.firstElementChild).toBe(frame)
    expect(frame.classList.contains('h-full')).toBe(true)
    expect(host.textContent).toContain('当日涨停池为空')
  })

  it('opens industry cards through the view switch without requesting market themes', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    expect(host.querySelector('section[aria-label="涨停行业卡片"]')).toBeNull()
    await clickButton('行业涨停')
    expect(host.querySelector('section[aria-label="涨停行业卡片"]')).not.toBeNull()
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).not.toBeNull()
    expect(host.textContent).toContain('封板样本')
    expect(host.textContent).not.toContain('市场题材')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchMarketThemes).not.toHaveBeenCalled()
  })

  it('keeps market data hidden until the market sentiment button is opened', async () => {
    await renderPanel()

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(fetchShortTermMarketFundDirection).not.toHaveBeenCalled()
    expect(host.textContent).not.toContain('最新可用涨跌数据')
    expect(host.textContent).not.toContain('上涨3123 家')
    const trigger = host.querySelector('button[aria-haspopup="dialog"]')
    expect(trigger?.textContent).toBe('市场情绪与行业分布')
    expect(trigger?.getAttribute('aria-expanded')).toBe('false')
    await openMarketDialog()
    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('资金流向看板')
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

  it('shows the board loading state before displaying and refreshing industry cards', async () => {
    let resolveBoard!: (value: ShortTermLimitUpBoardSnapshot) => void
    vi.mocked(fetchShortTermLimitUpBoard).mockReturnValueOnce(new Promise(resolve => { resolveBoard = resolve }))
    await renderPanel()
    await clickButton('行业涨停')

    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('正在拉取涨停池数据')
    expect(host.querySelector('section[aria-label="涨停行业卡片"]')).toBeNull()
    await act(async () => { resolveBoard(availableBoard) })
    expect(host.textContent).toContain('封板样本')
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).not.toBeNull()

    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...availableBoard,
      stocks: [{ ...availableBoard.stocks[0], name: '持续更新的封板样本' }],
    })
    await clickButton('刷新涨停看板')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('持续更新的封板样本')
    expect(fetchMarketThemes).not.toHaveBeenCalled()
  })

  it('refreshes industry cards and market breadth manually and automatically without requesting themes', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    expect(host.textContent).toContain('封板样本')
    await clickButton('刷新涨停看板')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)

    await openMarketDialog()
    const dialogRefresh = Array.from(document.querySelector('[role="dialog"]')!.querySelectorAll('button'))
      .find(button => button.textContent === '刷新')!
    expect(dialogRefresh.disabled).toBe(false)
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(3)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(3)
    expect(fetchMarketThemes).not.toHaveBeenCalled()
  })

  it.each(['close button', 'Escape'])('keeps the industry view open and restores focus after %s', async closeMethod => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    const card = host.querySelector('button[aria-label="查看半导体涨停明细"]')
    expect(card?.textContent).toContain('封板样本')
    expect(host.textContent).not.toContain('测试市场情绪')
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

  it.each(['homepage', 'dialog'])('refreshes cards and market data from the %s refresh button', async (location) => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    if (location === 'dialog') await openMarketDialog()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...availableBoard,
      fetchedAt: '2026-09-11T02:05:00Z',
      stocks: [...availableBoard.stocks, { ...availableBoard.stocks[0], symbol: '600002', name: '新增封板样本' }],
      sentiment: { ...availableBoard.sentiment!, limitUpCount: 2 },
    })
    await clickButton(location === 'homepage' ? '刷新涨停看板' : '刷新')

    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')?.textContent).toContain('涨停 2 家')
    expect(host.textContent).toContain('新增封板样本')
    if (location === 'homepage') {
      expect(document.querySelector('[role="dialog"]')).toBeNull()
      await openMarketDialog()
    }
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('共 2 只涨停')
    expect(dialog.textContent).toContain('2026-09-11 10:05:00')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(location === 'dialog' ? 2 : 1)
  })

  it('keeps the board refresh usable while fund flows are still loading', async () => {
    vi.mocked(fetchShortTermMarketFundDirection).mockReturnValue(new Promise(() => {}))
    await renderPanel()
    await openMarketDialog()
    expect(document.body.textContent).toContain('资金流向加载中')
    expect(document.body.textContent).toContain('上涨3123 家')

    await clickButton('刷新')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('资金流向加载中')
  })

  it('refreshes industry cards without snapshot status copy', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({ ...availableBoard, dataGaps: ['炸板池暂缺'] })
    await renderPanel()
    await clickButton('行业涨停')

    expect(host.textContent).toContain('封板样本')
    expect(host.textContent).not.toMatch(/数据日|快照获取时间|数据缺口|获取时间不代表源行情时间|已获取/)
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...availableBoard, fetchedAt: '2026-09-11T02:05:00Z',
      stocks: [...availableBoard.stocks, { ...availableBoard.stocks[0], symbol: '600002', name: '新快照样本' }],
    })
    await clickButton('刷新涨停看板')

    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(host.textContent).toContain('新快照样本')
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')?.textContent).toContain('涨停 2 家')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(2)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(2)
  })

  it('keeps the last successful cards and board failure visible until a retry succeeds', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    const card = host.querySelector('button[aria-label="查看半导体涨停明细"]')
    vi.mocked(fetchShortTermLimitUpBoard).mockRejectedValueOnce(new Error('涨停池超时'))
    await clickButton('刷新涨停看板')

    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).toBe(card)
    expect(host.textContent).toContain('封板样本')
    expect(host.textContent).toContain('当前保留上次快照（旧快照）')
    expect(host.textContent).toContain('涨停池超时')

    let resolveBoard!: (value: ShortTermLimitUpBoardSnapshot) => void
    vi.mocked(fetchShortTermLimitUpBoard).mockReturnValueOnce(new Promise(resolve => { resolveBoard = resolve }))
    await clickButton('刷新涨停看板')
    expect(host.textContent).toContain('当前保留上次快照（旧快照）')
    expect(host.textContent).toContain('封板样本')
    await act(async () => { resolveBoard({
      ...availableBoard, fetchedAt: '2026-09-11T02:08:00Z',
      stocks: [{ ...availableBoard.stocks[0], name: '重试后的封板样本' }],
    }) })

    expect(host.textContent).not.toContain('旧快照')
    expect(host.textContent).not.toContain('涨停池超时')
    expect(host.textContent).toContain('重试后的封板样本')
  })

  it('replaces old cards with the reason when a successful new snapshot is unavailable', async () => {
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    await renderPanel()
    await clickButton('行业涨停')
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).not.toBeNull()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...board, tradeDate: '2026-09-14', fetchedAt: '2026-09-14T01:00:00Z', unavailableReason: '当前交易日尚无涨停池',
    })
    await clickButton('刷新涨停看板')

    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).toBeNull()
    expect(host.textContent).toContain('当前交易日尚无涨停池')
    expect(host.textContent).not.toContain('旧快照')
    expect(host.textContent).not.toContain('封板样本')
  })

  it('shares the in-flight lock across homepage and market refresh buttons', async () => {
    let resolveBreadth!: (value: ShortTermMarketBreadth) => void
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(availableBoard)
    vi.mocked(fetchShortTermMarketBreadth).mockReturnValueOnce(new Promise(resolve => { resolveBreadth = resolve }))
    await renderPanel()
    await openMarketDialog()
    const homepageRefresh = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '刷新涨停看板')!
    const dialogRefresh = Array.from(document.querySelector('[role="dialog"]')!.querySelectorAll('button')).find(button => button.textContent === '刷新')!

    expect(homepageRefresh.disabled).toBe(true)
    expect(dialogRefresh.disabled).toBe(true)
    await act(async () => { homepageRefresh.click(); dialogRefresh.click() })
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
    await act(async () => { resolveBreadth(breadth) })
    expect(homepageRefresh.disabled).toBe(false)
    expect(dialogRefresh.disabled).toBe(false)
  })

  it.each(['close button', 'Escape'])('opens observation feedback and restores its trigger focus after %s', async closeMethod => {
    await renderPanel()
    const trigger = await clickButton('观察与隔日反馈')
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    expect(trigger.getAttribute('aria-expanded')).toBe('true')
    expect(dialog.querySelector('h2')?.textContent).toBe('观察与隔日反馈')
    expect(dialog.textContent).toContain('观察反馈测试内容')
    expect(dialog.textContent).not.toContain('最新可用涨跌数据')
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
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
    expect(fetchShortTermMarketBreadth).toHaveBeenCalledTimes(1)
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
    await clickButton('刷新涨停看板')
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
