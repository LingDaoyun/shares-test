// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { fetchShortTermLimitUpBoard, fetchShortTermMarketBreadth } from './api/client'
import type { ShortTermLimitUpBoardSnapshot } from './types'

vi.mock('./hooks/useBootstrap', () => ({ useBootstrap: vi.fn() }))
vi.mock('./pages/ShortTermPage', () => ({
  ShortTermPage: () => <section aria-label="独立短线页面">短线扫描内容</section>
}))
vi.mock('./pages/SettingsPage', () => ({
  SettingsPage: () => <section aria-label="配置页面">配置内容</section>
}))
vi.mock('./api/client', () => ({
  fetchShortTermLimitUpBoard: vi.fn(),
  fetchShortTermMarketBreadth: vi.fn(),
  fetchShortTermCatchUp: vi.fn(),
  fetchRules: vi.fn(),
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
    expect(host.querySelector('header h1')?.textContent).toBe('涨停看板')
    expect(host.querySelector('main')?.textContent).toContain('路由测试的涨停快照')
    expect(host.querySelector('section[aria-label="独立短线页面"]')).toBeNull()
    expect(host.querySelector('nav button[aria-current="page"]')?.textContent).toContain('涨停看板')
    expect(fetchShortTermLimitUpBoard).toHaveBeenCalled()
  }

  it.each(['/', '/limit-up', '/unknown-page'])('opens the real limit-up home from %s', async path => {
    await renderRoute(path)
    expectHome()
  })

  it('keeps the short-term route independent of the home board', async () => {
    await renderRoute('/short-term')
    expect(host.querySelector('output[aria-label="当前路由"]')?.textContent).toBe('/short-term')
    expect(host.querySelector('header h1')?.textContent).toBe('短线右侧')
    expect(host.querySelector('section[aria-label="独立短线页面"]')).not.toBeNull()
    expect(host.querySelector('nav button[aria-current="page"]')?.textContent).toContain('短线右侧')
    expect(fetchShortTermLimitUpBoard).not.toHaveBeenCalled()
  })

  it('navigates between the first home item and the existing short-term item', async () => {
    await renderRoute('/short-term')
    const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>('nav[aria-label="模块菜单"] button'))
    expect(buttons).toHaveLength(10)
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
})
