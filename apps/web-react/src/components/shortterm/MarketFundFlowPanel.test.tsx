// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermIndustryLeaders, fetchShortTermMarketFundDirection } from '../../api/client'
import type { ShortTermIndustryLeaderSnapshot, ShortTermMarketFundDirection } from '../../types'
import { DetailOverlay } from '../ui/DetailOverlay'
import { MarketFundFlowPanel } from './MarketFundFlowPanel'

vi.mock('../../api/client', () => ({
  fetchShortTermMarketFundDirection: vi.fn(),
  fetchShortTermIndustryLeaders: vi.fn()
}))

const direction: ShortTermMarketFundDirection = {
  topInflows: [{
    code: 'BK1036', name: '半导体', mainNetInflow: 1230000000, mainNetInflowRatio: 6.5,
    superLargeNetInflow: 900000000, largeNetInflow: 330000000,
    advancing: 32, declining: 8, constituentCount: 40, concentrationPercent: 25,
    sourceUrl: null
  }],
  topOutflows: [{
    code: 'BK0475', name: '银行', mainNetInflow: -450000000, mainNetInflowRatio: -2.4,
    superLargeNetInflow: -300000000, largeNetInflow: -150000000,
    advancing: 3, declining: 37, constituentCount: 40, concentrationPercent: 20,
    sourceUrl: null
  }],
  coveredIndustryCount: 3,
  expectedIndustryCount: 6,
  coverageRatio: 0.5,
  tradeDate: '2026-10-20',
  fetchedAt: '2026-10-21T09:00:00+08:00',
  sourceName: 'EASTMONEY_INDUSTRY_FUND_FLOW',
  dataGaps: []
}

const leaders: ShortTermIndustryLeaderSnapshot = {
  code: 'BK1036', name: '半导体', flowDirection: 'INFLOW',
  tradeDate: '2026-10-20', fetchedAt: '2026-10-21T09:01:00+08:00',
  sourceName: 'EASTMONEY_INDUSTRY_STOCK_FUND_FLOW', dataGaps: [],
  stocks: [{
    rank: 1, symbol: '688981', name: '中芯国际', latestPrice: 110, changePercent: 3.5,
    mainNetInflow: 300000000, mainNetInflowRatio: 8,
    superLargeNetInflow: 200000000, largeNetInflow: 100000000,
    tradeDate: '2026-10-20', marketTimestamp: '2026-10-20T15:00:00+08:00', sourceUrl: null
  }]
}

describe('MarketFundFlowPanel', () => {
  let host: HTMLDivElement
  let root: Root
  const closeMarket = vi.fn()

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.mocked(fetchShortTermMarketFundDirection).mockResolvedValue(direction)
    vi.mocked(fetchShortTermIndustryLeaders).mockResolvedValue(leaders)
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

  async function renderPanel(refreshKey = 0) {
    await act(async () => {
      root.render(
        <DetailOverlay open title="市场情绪与行业分布" onClose={closeMarket}>
          <MarketFundFlowPanel refreshKey={refreshKey} />
        </DetailOverlay>
      )
    })
  }

  function refreshButton() {
    const button = Array.from(document.body.querySelectorAll('button'))
      .find(item => item.textContent === '刷新资金流向')
    expect(button).toBeDefined()
    return button!
  }

  it('loads its own snapshot and displays the source and data date when it arrives', async () => {
    let resolveDirection!: (value: ShortTermMarketFundDirection) => void
    vi.mocked(fetchShortTermMarketFundDirection).mockReturnValueOnce(new Promise(resolve => { resolveDirection = resolve }))

    await renderPanel()

    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('资金流向看板')
    expect(document.body.textContent).toContain('资金流向加载中')
    expect(refreshButton().disabled).toBe(true)
    expect(document.body.textContent).not.toContain('半导体')

    await act(async () => { resolveDirection(direction) })

    expect(document.body.textContent).not.toContain('资金流向加载中')
    expect(refreshButton().disabled).toBe(false)
    expect(document.body.textContent).toContain('半导体')
    expect(document.body.textContent).toContain('12.30 亿')
    expect(document.body.textContent).toContain('银行')
    expect(document.body.textContent).toContain('-4.50 亿')
    expect(document.body.textContent).toContain('EASTMONEY_INDUSTRY_FUND_FLOW')
    expect(document.body.textContent).toContain('数据日 2026-10-20')
    expect(document.body.textContent).toContain('抓取时间 2026-10-21 09:00:00')
    expect(document.body.textContent).toContain('3/6（50.00%）')
  })

  it('refreshes when the parent refresh key changes and keeps the new snapshot', async () => {
    await renderPanel()
    await renderPanel()
    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(1)
    vi.mocked(fetchShortTermMarketFundDirection).mockResolvedValue({
      ...direction,
      topInflows: [{ ...direction.topInflows[0], mainNetInflow: 2340000000 }],
      fetchedAt: '2026-10-21T09:05:00+08:00'
    })

    await renderPanel(1)

    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('23.40 亿')
    expect(document.body.textContent).not.toContain('12.30 亿')
    expect(document.body.textContent).toContain('2026-10-21 09:05:00')
  })

  it('keeps the last snapshot and reports a failed manual refresh', async () => {
    await renderPanel()
    vi.mocked(fetchShortTermMarketFundDirection).mockRejectedValueOnce(new Error('资金来源超时'))

    await act(async () => { refreshButton().click() })

    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('资金流向更新失败')
    expect(document.body.textContent).toContain('资金来源超时')
    expect(document.body.textContent).toContain('半导体')
    expect(document.body.textContent).toContain('12.30 亿')
    expect(document.body.textContent).toContain('2026-10-21 09:00:00')
    expect(refreshButton().disabled).toBe(false)
  })

  it('does not invent zero amounts or coverage when the first request fails', async () => {
    vi.mocked(fetchShortTermMarketFundDirection).mockRejectedValueOnce(new Error('来源未返回行业资金流'))

    await renderPanel()

    expect(document.body.textContent).toContain('资金流向更新失败')
    expect(document.body.textContent).toContain('来源未返回行业资金流')
    expect(document.body.textContent).toContain('行业资金流暂不可用')
    expect(document.body.textContent).toContain('待补')
    expect(document.body.textContent).not.toContain('0.00 亿')
    expect(document.body.textContent).not.toContain('0/0')
    expect(refreshButton().disabled).toBe(false)
  })

  it('preserves the last snapshot when the API returns an unavailable result with HTTP 200', async () => {
    await renderPanel()
    vi.mocked(fetchShortTermMarketFundDirection).mockResolvedValueOnce({
      topInflows: [], topOutflows: [], coveredIndustryCount: 0, expectedIndustryCount: 0,
      coverageRatio: 0, tradeDate: null, fetchedAt: null, sourceName: '行业资金流未返回',
      dataGaps: ['行业资金流获取失败：来源超时']
    })

    await act(async () => { refreshButton().click() })

    expect(document.body.textContent).toContain('资金流向更新失败')
    expect(document.body.textContent).toContain('来源超时')
    expect(document.body.textContent).toContain('当前保留上次快照')
    expect(document.body.textContent).toContain('12.30 亿')
    expect(document.body.textContent).toContain('2026-10-21 09:00:00')
  })

  it('does not overlap an in-flight request when parent refreshes or the disabled button is clicked', async () => {
    let resolveDirection!: (value: ShortTermMarketFundDirection) => void
    vi.mocked(fetchShortTermMarketFundDirection).mockReturnValueOnce(new Promise(resolve => { resolveDirection = resolve }))
    await renderPanel()

    await renderPanel(1)
    await renderPanel(2)
    expect(refreshButton().disabled).toBe(true)
    await act(async () => { refreshButton().click() })

    expect(fetchShortTermMarketFundDirection).toHaveBeenCalledTimes(1)
    await act(async () => { resolveDirection(direction) })
    expect(document.body.textContent).toContain('12.30 亿')
    expect(refreshButton().disabled).toBe(false)
  })

  it.each([
    { code: 'BK1036', name: '半导体', flow: 'INFLOW' as const, rankLabel: '主力净流入前 20' },
    { code: 'BK0475', name: '银行', flow: 'OUTFLOW' as const, rankLabel: '主力净流出前 20' }
  ])('opens $flow stock rankings and closes only the nested overlay on Escape', async ({ code, name, flow, rankLabel }) => {
    vi.mocked(fetchShortTermIndustryLeaders).mockResolvedValue({ ...leaders, code, name, flowDirection: flow })
    await renderPanel()
    const outerDialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    const industryButton = outerDialog.querySelector<HTMLButtonElement>(`button[aria-label="查看 ${name} 板块内主力资金前 20 个股"]`)
    expect(industryButton).not.toBeNull()

    await act(async () => { industryButton!.focus(); industryButton!.click() })

    expect(fetchShortTermIndustryLeaders).toHaveBeenCalledWith(code, { name, flow, limit: 20 })
    const dialogs = Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"]'))
    expect(dialogs).toHaveLength(2)
    const childDialog = dialogs.find(dialog => dialog !== outerDialog)!
    expect(childDialog.querySelector('h2')?.textContent).toBe(`${name} · 行业资金排名`)
    expect(childDialog.textContent).toContain(rankLabel)
    expect(childDialog.textContent).toContain('中芯国际')
    expect(childDialog.textContent).toContain('688981')

    await act(async () => {
      childDialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    })

    expect(closeMarket).not.toHaveBeenCalled()
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(document.querySelector('[role="dialog"]')).toBe(outerDialog)
    expect(outerDialog.querySelector('h2')?.textContent).toBe('市场情绪与行业分布')
    expect(document.activeElement).toBe(industryButton)
    expect(document.body.style.overflow).toBe('hidden')
  })
})
