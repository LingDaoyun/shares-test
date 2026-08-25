// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchOverseasMarketBoard } from '../api/client'
import { OverseasMarketPage } from './OverseasMarketPage'
import type { OverseasMarketBoardSnapshot } from '../types'

vi.mock('../api/client', () => ({
  fetchOverseasMarketBoard: vi.fn()
}))

const mockedFetchOverseasMarketBoard = vi.mocked(fetchOverseasMarketBoard)

describe('OverseasMarketPage', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
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

  async function renderPage() {
    await act(async () => {
      root.render(<OverseasMarketPage />)
      await Promise.resolve()
      await Promise.resolve()
    })
  }

  it('renders global strip and three market sections with sorted industries', async () => {
    mockedFetchOverseasMarketBoard.mockResolvedValue(availableSnapshot() as never)
    await renderPage()

    const text = host.textContent ?? ''
    expect(text).toContain('全球指数速览')
    expect(text).toContain('恒生指数')
    expect(text).toContain('道琼斯')
    expect(text).toContain('日经225')
    expect(text).toContain('韩国KOSPI')
    expect(text).toContain('信息技术')
    expect(text).toContain('半导体')
    expect(text).toContain('交易中')

    // 行业按涨跌幅降序展示：美股块里信息技术(+1.50)排在能源(-0.17)之前
    const usIndustryNames = industryNamesWithin(host, '美股')
    expect(usIndustryNames.indexOf('信息技术')).toBeLessThan(usIndustryNames.indexOf('能源'))
  })

  it('shows degradation note for japan and korea when yahoo industries unavailable', async () => {
    const snapshot = availableSnapshot()
    snapshot.markets = snapshot.markets.map((section) => (
      section.market === 'JP' || section.market === 'KR'
        ? { ...section, industries: [], unavailableReason: 'Yahoo 行情不可达，行业数据暂缺（需国际网络）。' }
        : section
    ))
    snapshot.dataGaps = ['Yahoo Finance 不可达，日本/韩国行业数据缺失（需国际网络）：连接超时']
    mockedFetchOverseasMarketBoard.mockResolvedValue(snapshot as never)
    await renderPage()

    const text = host.textContent ?? ''
    expect(text).toContain('Yahoo 行情不可达')
    expect(text).toContain('Yahoo Finance 不可达')
    // 美股块不受降级影响
    expect(text).toContain('信息技术')
  })

  it('shows unavailable card when the board itself fails', async () => {
    mockedFetchOverseasMarketBoard.mockResolvedValue({
      generatedAt: '2026-08-24T01:00:00Z',
      available: false,
      unavailableReason: '东方财富全球行情获取失败，外围看板暂不可用：连接超时',
      globalIndices: [],
      markets: [],
      dataGaps: []
    } as never)
    await renderPage()

    const text = host.textContent ?? ''
    expect(text).toContain('外围看板暂不可用')
    expect(text).toContain('东方财富全球行情获取失败')
    expect(text).not.toContain('全球指数速览')
  })

  it('surfaces request errors with a retry entry', async () => {
    mockedFetchOverseasMarketBoard.mockRejectedValue(new Error('网络中断'))
    await renderPage()

    const text = host.textContent ?? ''
    expect(text).toContain('网络中断')
    expect(text).toContain('刷新')
  })
})

/** 只统计某个市场卡片内的行业名顺序（领涨/领跌摘要里也会出现行业名，需排除）。 */
function industryNamesWithin(container: HTMLElement, marketLabel: string): string[] {
  const cards = [...container.querySelectorAll('section.card')]
  const marketCard = cards.find((card) => card.textContent?.includes(marketLabel))
  if (!marketCard) return []
  const knownIndustries = ['信息技术', '能源', '银行', '半导体']
  const bars = [...marketCard.querySelectorAll<HTMLElement>('.grid.grid-cols-\\[minmax\\(0\\,7rem\\)_1fr_4\\.2rem\\]')]
  return bars
    .map((bar) => knownIndustries.find((name) => bar.textContent?.includes(name)))
    .filter((name): name is string => Boolean(name))
}

function availableSnapshot(): OverseasMarketBoardSnapshot {
  return {
    generatedAt: '2026-08-24T01:00:00Z',
    available: true,
    unavailableReason: null,
    globalIndices: [
      index('HSI', '恒生指数', 25000, 1.21),
      index('GDAXI', '德国DAX', 24000, 0.59)
    ],
    markets: [
      {
        market: 'US',
        marketLabel: '美股',
        status: 'OPEN',
        indices: [
          index('DJIA', '道琼斯', 53277.01, 0.98),
          index('SPX', '标普500', 7674.37, 0.43)
        ],
        industries: [
          index('XLK', '信息技术', 230, 1.5),
          index('XLE', '能源', 87, -0.17)
        ],
        unavailableReason: null
      },
      {
        market: 'JP',
        marketLabel: '日本',
        status: 'CLOSED',
        indices: [index('N225', '日经225', 65960.3, -0.08)],
        industries: [index('1631.T', '银行', 610, 2.0)],
        unavailableReason: null
      },
      {
        market: 'KR',
        marketLabel: '韩国',
        status: 'CLOSED',
        indices: [index('KS11', '韩国KOSPI', 6847.43, -0.95)],
        industries: [index('091160.KS', '半导体', 41000, 3.3)],
        unavailableReason: null
      }
    ],
    dataGaps: []
  }
}

function index(code: string, name: string, latestPrice: number, changePercent: number) {
  return {
    code,
    name,
    latestPrice,
    changePercent,
    changeValue: null,
    currency: 'USD',
    quoteTime: '2026-08-24T01:00:00Z'
  }
}
