// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { fetchShortTermLimitBoardContext, fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth } from './api/client'
import { fetchMarketThemes } from './api/marketThemes'
import type { ShortTermLimitUpBoardSnapshot, ShortTermLimitUpStock } from './types'

vi.mock('./hooks/useBootstrap', () => ({ useBootstrap: vi.fn() }))
vi.mock('./api/marketThemes', () => ({ fetchMarketThemes: vi.fn(), fetchMarketThemeDetail: vi.fn() }))
vi.mock('./pages/ShortTermPage', () => ({
  ShortTermPage: () => <section aria-label="独立短线页面">短线扫描内容</section>
}))
vi.mock('./pages/SettingsPage', () => ({
  SettingsPage: () => <section aria-label="配置页面">配置内容</section>
}))
vi.mock('./api/client', () => ({
  fetchShortTermLimitBoardContext: vi.fn(),
  fetchShortTermLimitUpBoard: vi.fn(),
  fetchShortTermMarketBreadth: vi.fn(),
  fetchShortTermCatchUp: vi.fn(),
  fetchLlmConfig: vi.fn(),
  fetchRuntimeConfig: vi.fn()
}))

const board: ShortTermLimitUpBoardSnapshot = {
  tradeDate: '2026-10-16',
  fetchedAt: '2026-10-16T07:00:00Z',
  available: false,
  unavailableReason: '路由测试的涨停快照',
  stocks: [],
  brokenBoardAvailable: false,
  brokenStocks: [],
  industryStats: [],
  sentiment: null,
  marketTurnover: null,
  dataGaps: []
}

function CurrentPath() {
  return <output aria-label="当前路由">{useLocation().pathname}</output>
}

describe('App home routes and navigation', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue(board)
    vi.mocked(fetchShortTermLimitBoardContext).mockResolvedValue({ tradeDate: board.tradeDate, source: '', sourceUrl: '', up: {}, down: {} })
    vi.mocked(fetchShortTermMarketBreadth).mockResolvedValue(null as never)
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    window.sessionStorage.clear()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    vi.unstubAllGlobals()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  async function settleRoutes() {
    await act(async () => { await vi.dynamicImportSettled() })
  }

  async function renderRoute(path: string) {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <App />
          <CurrentPath />
        </MemoryRouter>
      )
    })
    await settleRoutes()
  }

  function expectHome() {
    expect(host.querySelector('output[aria-label="当前路由"]')?.textContent).toBe('/limit-up')
    expect(host.querySelector('header')).toBeNull()
    expect(host.querySelector('main')?.textContent).toContain('路由测试的涨停快照')
    expect(host.querySelector('section[aria-label="独立短线页面"]')).toBeNull()
    expect(host.querySelector('nav button[aria-current="page"]')?.textContent).toContain('涨停看板')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalled()
    expect(fetchMarketThemes).not.toHaveBeenCalled()
  }

  it.each(['/', '/limit-up', '/unknown-page'])('opens the real limit-up home from %s', async path => {
    await renderRoute(path)
    expectHome()
  })

  it('opens the descending consecutive-board ladder and switches to the industry view using the same snapshot', async () => {
    const stock: ShortTermLimitUpStock = {
      symbol: '600001', name: '首板样本', industry: '半导体', latestPrice: 11, changePercent: 10,
      amount: 100000000, turnoverRate: 2, consecutiveBoards: 1, statDays: 5, statBoards: 3,
      sealFunds: 20000000, firstSealTime: '09:35:00', lastSealTime: '09:35:00', sealBreakCount: 0
    }
    vi.mocked(fetchShortTermLimitUpBoard).mockResolvedValue({
      ...board, available: true, stocks: [
        stock,
        { ...stock, symbol: '600002', name: '三板样本', consecutiveBoards: 3 },
        { ...stock, symbol: '600003', name: '最高板样本', consecutiveBoards: 10 },
        { ...stock, symbol: '600004', name: '连板缺失样本', consecutiveBoards: 0 },
        { ...stock, symbol: '600005', name: '同高度样本', consecutiveBoards: 10 }
      ]
    })
    await renderRoute('/')

    const button = (label: string) => Array.from(host.querySelectorAll<HTMLButtonElement>('main button'))
      .find(node => node.textContent === label)!
    const ladder = host.querySelector('section[aria-label="涨停梯队"]')
    expect(button('涨停梯队')?.getAttribute('aria-pressed')).toBe('true')
    expect(Array.from(ladder!.querySelectorAll('h3'), heading => heading.textContent))
      .toEqual(['10 板', '3 板', '首板', '连板未知'])
    expect(Array.from(ladder!.querySelectorAll('ul'), list => Array.from(list.querySelectorAll('li strong'), name => name.textContent)))
      .toEqual([['最高板样本', '同高度样本'], ['三板样本'], ['首板样本'], ['连板缺失样本']])
    expect(host.querySelector('section[aria-label="涨停行业卡片"]')).toBeNull()

    await act(async () => { button('行业涨停').click() })
    expect(button('行业涨停').getAttribute('aria-pressed')).toBe('true')
    expect(host.querySelector('section[aria-label="涨停梯队"]')).toBeNull()
    expect(host.querySelector('button[aria-label="查看半导体涨停明细"]')).not.toBeNull()

    await act(async () => { button('涨停梯队').click() })
    expect(host.querySelector('section[aria-label="涨停梯队"]')).not.toBeNull()
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalledTimes(1)
  })

  it('keeps the short-term route independent of the home board', async () => {
    await renderRoute('/short-term')
    expect(host.querySelector('output[aria-label="当前路由"]')?.textContent).toBe('/short-term')
    expect(host.querySelector('header')).toBeNull()
    expect(host.querySelector('section[aria-label="独立短线页面"]')).not.toBeNull()
    expect(host.querySelector('nav button[aria-current="page"]')?.textContent).toContain('短线右侧')
    expect(fetchShortTermLimitUpBoard).not.toHaveBeenCalled()
  })

  it.each(['/hotspots', '/market', '/potential-leader', '/five-day', '/first-board-alert', '/trade-review', '/rules'])('returns retired module path %s to the home board', async path => {
    await renderRoute(path)
    expectHome()
  })

  it('removes the retired modules from the navigation menu', async () => {
    await renderRoute('/settings')
    const labels = Array.from(host.querySelectorAll<HTMLButtonElement>('nav[aria-label="模块菜单"] button'))
      .map(button => button.getAttribute('aria-label'))
    expect(labels).toEqual(['涨停看板', '批量搜股', '短线右侧', '消息面', '外围市场', '配置'])
  })

  it('navigates between the first home item and the existing short-term item', async () => {
    await renderRoute('/short-term')
    const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('nav[aria-label="模块菜单"] button'))
    expect(buttons).toHaveLength(6)
    expect(buttons[0].textContent).toContain('涨停看板')
    expect(buttons[0].querySelector('small')?.textContent).toBe('首页')
    await act(async () => { buttons[0].click() })
    await settleRoutes()
    expectHome()

    const shortTerm = Array.from(host.querySelectorAll<HTMLButtonElement>('nav button'))
      .find(button => button.textContent?.includes('短线右侧'))
    expect(shortTerm).toBeDefined()
    await act(async () => { shortTerm!.click() })
    await settleRoutes()
    expect(host.querySelector('output[aria-label="当前路由"]')?.textContent).toBe('/short-term')
    expect(host.querySelector('section[aria-label="独立短线页面"]')).not.toBeNull()
    expect(host.querySelectorAll('nav button[aria-current="page"]')).toHaveLength(1)
  })

  it('returns from settings to the home board using the header action', async () => {
    await renderRoute('/settings')
    expect(host.querySelector('header h1')?.textContent).toBe('系统配置')
    const back = Array.from(host.querySelectorAll<HTMLButtonElement>('header button'))
      .find(button => button.textContent === '返回首页')
    expect(back).toBeDefined()
    await act(async () => { back!.click() })
    await settleRoutes()
    expectHome()
  })

  it('opens the batch search page from its menu without loading the home board', async () => {
    await renderRoute('/short-term')
    const search = host.querySelector<HTMLButtonElement>('nav button[aria-label="批量搜股"]')!
    await act(async () => { search.click() })
    await settleRoutes()
    expect(host.querySelector('output[aria-label="当前路由"]')?.textContent).toBe('/batch-search')
    expect(host.querySelector('main h1')?.textContent).toBe('批量搜股')
    expect(host.querySelector('header')).toBeNull()
    expect(host.querySelector('nav button[aria-current="page"]')?.getAttribute('aria-label')).toBe('批量搜股')
    expect(fetchShortTermLimitUpBoard).not.toHaveBeenCalled()
  })
})
