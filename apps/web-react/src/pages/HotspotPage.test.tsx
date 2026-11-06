// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchHotspotSnapshot, refreshHotspots } from '../api/hotspots'
import { addToWatchlist } from '../api/client'
import type { HotspotEvent, HotspotNewsItem, HotspotRecommendation, HotspotSnapshot } from '../types/hotspot'
import { HotspotPage } from './HotspotPage'

vi.mock('../api/hotspots', () => ({ fetchHotspotSnapshot: vi.fn(), refreshHotspots: vi.fn() }))
vi.mock('../api/client', () => ({ addToWatchlist: vi.fn() }))

const article: HotspotNewsItem = {
  id: 'news-1', title: '新型储能产品发布', source: '测试新闻源', url: 'https://example.com/news/1',
  publishedAt: '2026-10-19T01:00:00Z', excerpt: '新闻原始摘要：新产品进入验证阶段。'
}
const stock: HotspotRecommendation = {
  symbol: '600001', name: '示例电气', industry: '电气设备', recommendationReason: '公司主营覆盖储能设备。',
  benefitMechanism: '新产品放量可能增加设备需求。', relationType: 'BUSINESS_MATCH', evidence: [],
  companyEvidence: [{ sourceType: 'COMPANY_PROFILE', sourceTitle: '公司主营资料', excerpt: '主营储能变流器。', url: 'https://example.com/company/600001', confidence: 85 }],
  risks: ['仍需核实实际订单。'], confidence: 'MEDIUM', latestPrice: 12, changePercent: 2,
  quoteUrl: 'https://example.com/quote/600001', quoteAsOf: '2026-10-19 15:00:00',
  firstSeenAt: '2026-10-18T03:00:00Z', firstSeenPrice: 10, firstSeenQuoteAsOf: '2026-10-16 15:00:00'
}
const event: HotspotEvent = {
  id: 'event-1', title: '储能产品产业化', eventType: 'PRODUCT_LAUNCH', industries: ['电气设备'], products: ['储能'],
  summary: '产品验证带来产业链观察线索。', catalyst: '需求传导仍需后续验证。', heatScore: 80, confidence: 'MEDIUM',
  sources: [article], recommendations: [stock], risks: ['产品放量进度存在不确定性。'],
  firstSeenAt: '2026-10-18T03:00:00Z', updatedAt: '2026-10-19T02:00:00Z'
}
const snapshot: HotspotSnapshot = {
  status: 'READY', generatedAt: '2026-10-19T02:00:00Z', startedAt: null, lastAttemptAt: '2026-10-19T01:59:00Z',
  nextAutomaticRefreshAt: '2026-10-19T04:00:00Z', refreshAvailableAt: null, summary: '已保存的产业研判。',
  newsCount: 1, companyCount: 1, news: [article], events: [event], dataGaps: []
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('HotspotPage snapshot and evidence workflow', () => {
  let host: HTMLDivElement
  let root: Root
  let unmounted: boolean

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-19T03:00:00Z'))
    vi.mocked(fetchHotspotSnapshot).mockResolvedValue(snapshot)
    vi.mocked(refreshHotspots).mockResolvedValue({ ...snapshot, status: 'RUNNING' })
    vi.mocked(addToWatchlist).mockResolvedValue(undefined as never)
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    unmounted = false
  })

  afterEach(() => {
    if (!unmounted) act(() => root.unmount())
    host.remove()
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  async function renderPage() {
    await act(async () => {
      root.render(<MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><HotspotPage /></MemoryRouter>)
    })
  }

  function button(label: string, scope: ParentNode = document) {
    const found = Array.from(scope.querySelectorAll<HTMLButtonElement>('button')).find(item => item.textContent === label || item.getAttribute('aria-label') === label)
    expect(found, `button ${label}`).toBeDefined()
    return found!
  }

  async function click(label: string, scope: ParentNode = document) {
    await act(async () => { button(label, scope).click() })
  }

  async function advance(milliseconds: number) {
    await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds) })
  }

  it('reads saved results without starting work, and preserves partial coverage with raw news', async () => {
    const pending = deferred<HotspotSnapshot>()
    vi.mocked(fetchHotspotSnapshot).mockReturnValueOnce(pending.promise)
    await renderPage()
    expect(host.textContent).toContain('正在读取热点与股票推荐')
    expect(refreshHotspots).not.toHaveBeenCalled()

    await act(async () => pending.resolve({ ...snapshot, status: 'PARTIAL', dataGaps: ['一个新闻源暂不可用。'] }))
    expect(host.textContent).toContain('部分资料待补齐')
    expect(host.textContent).toContain(event.title)
    expect(host.textContent).toContain('一个新闻源暂不可用。')
    await click('原始新闻')
    expect(host.textContent).toContain(article.excerpt)
    expect(host.querySelector<HTMLAnchorElement>(`a[href="${article.url}"]`)?.textContent).toBe(article.title)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(1)
    expect(refreshHotspots).not.toHaveBeenCalled()
  })

  it('starts explicitly, retains prior results while running, then polls for completion without another refresh', async () => {
    const start = deferred<HotspotSnapshot>()
    vi.mocked(refreshHotspots).mockReturnValueOnce(start.promise)
    await renderPage()
    await click('更新热点与推荐')
    expect(button('读取结果').disabled).toBe(true)
    expect(button('更新热点与推荐').disabled).toBe(true)
    expect(host.textContent).toContain(event.title)

    await act(async () => start.resolve({ ...snapshot, status: 'RUNNING' }))
    expect(host.querySelector('[role="status"]')?.textContent).toContain('已有结果继续保留')
    expect(button('正在追踪').disabled).toBe(true)
    vi.mocked(fetchHotspotSnapshot).mockResolvedValue({ ...snapshot, summary: '新一轮已完成。' })
    await advance(4999)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(2)
    expect(host.textContent).toContain('新一轮已完成。')
    expect(host.querySelector('[role="status"]')).toBeNull()
    await advance(59999)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(2)
    await advance(1)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(3)
    expect(refreshHotspots).toHaveBeenCalledTimes(1)
  })

  it('does not allow an older read response to overwrite an accepted refresh', async () => {
    const oldRead = deferred<HotspotSnapshot>()
    vi.mocked(fetchHotspotSnapshot).mockReturnValueOnce(oldRead.promise)
    await renderPage()
    await click('更新热点与推荐')
    expect(host.textContent).toContain('采集与研判中')
    await act(async () => oldRead.resolve({ ...snapshot, summary: '过时读取结果' }))
    expect(host.textContent).not.toContain('过时读取结果')
    expect(button('正在追踪').disabled).toBe(true)
  })

  it('shows initial read errors and lets reading recover without submitting a refresh', async () => {
    vi.mocked(fetchHotspotSnapshot).mockRejectedValueOnce(new Error('网络暂不可用'))
    await renderPage()
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('热点结果读取失败：网络暂不可用')
    await click('读取结果')
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.textContent).toContain(event.title)
    expect(refreshHotspots).not.toHaveBeenCalled()
  })

  it('keeps saved results on a failed refresh and can confirm running state with a read', async () => {
    vi.mocked(refreshHotspots).mockRejectedValueOnce(new Error('请求超时'))
    await renderPage()
    await click('更新热点与推荐')
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('可读取结果确认任务状态')
    expect(host.textContent).toContain(event.title)
    vi.mocked(fetchHotspotSnapshot).mockResolvedValueOnce({ ...snapshot, status: 'RUNNING' })
    await click('读取结果')
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.textContent).toContain('采集与研判中')
    expect(refreshHotspots).toHaveBeenCalledTimes(1)
  })

  it('retains prior results when background reads fail and retries on the next poll', async () => {
    await renderPage()
    vi.mocked(fetchHotspotSnapshot).mockRejectedValueOnce(new Error('临时断线'))
    await advance(60000)
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('临时断线')
    expect(host.textContent).toContain(event.title)
    await advance(60000)
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(3)
  })

  it('shows company evidence and separate quote times, and retries a failed watchlist addition', async () => {
    await renderPage()
    await click(`查看${event.title}的推荐与依据`)
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    expect(dialog.textContent).toContain(stock.recommendationReason)
    expect(dialog.textContent).toContain('新闻未直接涉及这家公司')
    expect(dialog.textContent).toContain(stock.companyEvidence[0].excerpt)
    expect(dialog.textContent).toContain(stock.quoteAsOf)
    expect(dialog.textContent).toContain(stock.firstSeenQuoteAsOf)
    expect(dialog.textContent).toContain('+20.00%')
    expect(dialog.textContent).toContain(stock.risks[0])
    expect(dialog.querySelector<HTMLAnchorElement>(`a[href="${stock.companyEvidence[0].url}"]`)?.rel).toBe('noopener noreferrer')

    vi.mocked(addToWatchlist).mockRejectedValueOnce(new Error('关注保存失败'))
    await click('加入特别关注', dialog)
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe('关注保存失败')
    const save = deferred<Awaited<ReturnType<typeof addToWatchlist>>>()
    vi.mocked(addToWatchlist).mockReturnValueOnce(save.promise)
    await click('加入特别关注', dialog)
    expect(button('加入特别关注', dialog).disabled).toBe(true)
    await act(async () => save.resolve(undefined as never))
    expect(button('已加入特别关注', dialog).disabled).toBe(true)
    expect(dialog.querySelector('[role="alert"]')).toBeNull()
    expect(addToWatchlist).toHaveBeenLastCalledWith('600001', `热点追踪：${event.title}；主营业务匹配；${stock.recommendationReason}`)
    await click('关闭详情', dialog)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('keeps collected news accessible when company evidence is insufficient for recommendations', async () => {
    vi.mocked(fetchHotspotSnapshot).mockResolvedValue({ ...snapshot, status: 'PARTIAL', companyCount: 0, events: [], dataGaps: ['公司资料缺失。'] })
    await renderPage()
    expect(host.textContent).toContain('新闻已收集，等待形成有依据的推荐')
    await click('查看已采集新闻')
    expect(host.textContent).toContain(article.title)
    expect(host.textContent).toContain('公司资料缺失。')
  })

  it('filters events by stock and allows narrowing to events with supported recommendations', async () => {
    const noStocks = { ...event, id: 'event-2', title: '无公司关联线索', recommendations: [] }
    vi.mocked(fetchHotspotSnapshot).mockResolvedValue({ ...snapshot, events: [event, noStocks] })
    await renderPage()
    const checkbox = host.querySelector<HTMLInputElement>('input[type="checkbox"]')!
    await act(async () => { checkbox.click() })
    expect(host.textContent).toContain(event.title)
    expect(host.textContent).not.toContain(noStocks.title)
    const search = host.querySelector<HTMLInputElement>('input[aria-label="搜索热点、产品或股票"]')!
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(search, '不存在的股票')
      search.dispatchEvent(new Event('input', { bubbles: true }))
    })
    expect(host.textContent).toContain('没有符合条件的热点')
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(search, stock.symbol)
      search.dispatchEvent(new Event('input', { bubbles: true }))
    })
    expect(host.textContent).toContain(event.title)
  })

  it('honors the server refresh cooldown and enables refresh once a later read passes it', async () => {
    vi.mocked(fetchHotspotSnapshot).mockResolvedValue({ ...snapshot, refreshAvailableAt: '2026-10-19T03:00:30Z' })
    await renderPage()
    expect(button('更新热点与推荐').disabled).toBe(true)
    expect(host.textContent).toContain('手动更新可用时间')
    await advance(60000)
    expect(button('更新热点与推荐').disabled).toBe(false)
    expect(refreshHotspots).not.toHaveBeenCalled()
  })

  it('stops polling after leaving the page even when an in-flight response arrives later', async () => {
    await renderPage()
    const pendingRead = deferred<HotspotSnapshot>()
    vi.mocked(fetchHotspotSnapshot).mockReturnValueOnce(pendingRead.promise)
    await advance(60000)
    act(() => root.unmount())
    unmounted = true
    await act(async () => pendingRead.resolve(snapshot))
    await advance(120000)
    expect(fetchHotspotSnapshot).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })
})
