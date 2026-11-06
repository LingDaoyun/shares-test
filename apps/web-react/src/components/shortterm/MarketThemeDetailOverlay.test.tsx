// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketThemeDetail } from '../../api/marketThemes'
import type { MarketTheme, MarketThemeDetail } from '../../types/marketThemes'
import { MarketThemeDetailOverlay } from './MarketThemeDetailOverlay'

vi.mock('../../api/marketThemes', () => ({ fetchMarketThemeDetail: vi.fn() }))
vi.mock('./StockKlineButton', () => ({ StockKlineButton: ({ name }: { name: string }) => <button>查看{name}分时和日线</button> }))

const theme: MarketTheme = {
  code: 'BK1090', name: '机器人概念', changePercent: 1, amount: 100000000,
  advancing: 1, declining: 0, unchanged: 0, advancingRatioPercent: 100,
  marketTimestamp: '2026-10-26T06:00:00Z',
}
const detail: MarketThemeDetail = {
  tradeDate: '2026-10-26', fetchedAt: '2026-10-26T06:01:00Z', theme,
  membersSource: '东方财富概念成分', membershipComplete: true, memberCount: 1,
  limitUpCount: 0, maxConsecutiveBoards: 0, limitUpStocks: [],
  members: [{ symbol: '300024', name: '机器人', latestPrice: 11, changePercent: 1, amount: 0, turnoverRate: 0, marketTimestamp: theme.marketTimestamp }],
  dataGaps: [],
}

describe('MarketThemeDetailOverlay', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchMarketThemeDetail).mockResolvedValue(detail)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function render(tradeDate: string | null = '2026-10-26') {
    await act(async () => root.render(<MarketThemeDetailOverlay selection={{ theme, tradeDate }} onClose={vi.fn()}><p>相关新闻线索</p></MarketThemeDetailOverlay>))
  }

  it('loads one selected theme, shows valid zero and offers member charts', async () => {
    await render()
    expect(fetchMarketThemeDetail).toHaveBeenCalledOnce()
    expect(fetchMarketThemeDetail).toHaveBeenCalledWith('BK1090', '2026-10-26')
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('涨停家数0 家')
    expect(dialog.textContent).toContain('最高连板0 板')
    expect(dialog.textContent).toContain('暂无涨停股票')
    expect(dialog.textContent).toContain('查看机器人分时和日线')
    expect(dialog.textContent).toContain('相关新闻线索')
    expect(dialog.textContent).toContain('不代表股票当日上涨原因')
  })

  it('does not turn an incomplete membership into zero limit-up stocks', async () => {
    vi.mocked(fetchMarketThemeDetail).mockResolvedValue({ ...detail, membershipComplete: false, memberCount: null, limitUpCount: null, maxConsecutiveBoards: null, members: [], dataGaps: ['成分接口未返回完整分页'] })
    await render()
    const text = document.querySelector('[role="dialog"]')!.textContent
    expect(text).toContain('成分家数未知')
    expect(text).toContain('涨停家数待核实')
    expect(text).toContain('最高连板待核实')
    expect(text).toContain('成分接口未返回完整分页')
    expect(text).not.toContain('涨停家数0 家')
    expect(text).not.toContain('暂无涨停股票')
  })

  it('does not request a detail without a valid source date', async () => {
    await render(null)
    expect(fetchMarketThemeDetail).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('缺少有效行情日期')
  })

  it('rejects data returned for another date or concept', async () => {
    vi.mocked(fetchMarketThemeDetail).mockResolvedValue({ ...detail, tradeDate: '2026-10-23' })
    await render()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('与所选题材或行情日期不一致')
    expect(document.querySelector('table')).toBeNull()
  })

  it('ignores an outstanding response after the selection closes', async () => {
    let resolve!: (value: MarketThemeDetail) => void
    vi.mocked(fetchMarketThemeDetail).mockReturnValue(new Promise(done => { resolve = done }))
    await render()
    await act(async () => root.render(<MarketThemeDetailOverlay selection={null} onClose={vi.fn()} />))
    await act(async () => resolve(detail))
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.body.textContent).not.toContain('机器人')
  })

  it('paginates the actual members without changing the complete member count', async () => {
    vi.mocked(fetchMarketThemeDetail).mockResolvedValue({ ...detail, memberCount: 21, members: Array.from({ length: 21 }, (_, i) => ({ ...detail.members[0], symbol: `6000${i.toString().padStart(2, '0')}`, name: `成分${i}` })) })
    await render()
    expect(document.querySelector('table[aria-label="题材全部成分股票"] tbody')?.children).toHaveLength(20)
    expect(document.body.textContent).toContain('全部成分 · 21 家')
    const next = Array.from(document.querySelectorAll('button')).find(button => button.textContent === '下一页')!
    act(() => next.click())
    expect(document.querySelector('table[aria-label="题材全部成分股票"] tbody')?.children).toHaveLength(1)
    expect(document.body.textContent).toContain('成分20')
    expect(document.body.textContent).toContain('第 2 / 2 页')
  })
})
