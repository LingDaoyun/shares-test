// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import * as apiClient from '../api/client'
import {
  addTradeFill,
  cancelTradeCase,
  deleteTradeCase,
  deleteTradeFill,
  fetchTradeCase,
  fetchTradeCases,
  refreshTradeCase,
  updateTradeFill
} from '../api/client'
import { useTradeFeedbackStore } from '../store/tradeFeedbackStore'
import type { TradeCaseSummary } from '../types'
import { TradeReviewPage } from './TradeReviewPage'

vi.mock('../api/client', () => ({
  addTradeFill: vi.fn(),
  cancelTradeCase: vi.fn(),
  createTradeCase: vi.fn(),
  deleteTradeCase: vi.fn(),
  deleteTradeFill: vi.fn(),
  fetchTradeCase: vi.fn(),
  fetchTradeCases: vi.fn(),
  recordManualTradeFill: vi.fn(),
  refreshTradeCase: vi.fn(),
  updateTradeFill: vi.fn()
}))

describe('TradeReviewPage case removal', () => {
  let host: HTMLDivElement
  let root: Root
  let confirmSpy: MockInstance<typeof window.confirm>
  const mockedRecordManualTradeFill = () => vi.mocked((apiClient as unknown as { recordManualTradeFill: ReturnType<typeof vi.fn> }).recordManualTradeFill)

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    vi.clearAllMocks()
    useTradeFeedbackStore.setState({
      casesById: {},
      caseIdByRecommendation: {},
      loaded: false,
      loading: false,
      loadingMore: false,
      hasMore: true,
      nextCursor: null
    })
    vi.mocked(fetchTradeCases).mockResolvedValue([tradeCase('case-delete', '600519', '贵州茅台')])
    vi.mocked(fetchTradeCase).mockRejectedValue(new Error('详情测试中不加载'))
    vi.mocked(deleteTradeCase).mockResolvedValue(undefined)
    vi.mocked(refreshTradeCase).mockRejectedValue(new Error('刷新测试中不调用'))
    vi.mocked(cancelTradeCase).mockRejectedValue(new Error('取消测试中不调用'))
    vi.mocked(addTradeFill).mockRejectedValue(new Error('新增测试中不调用'))
    vi.mocked(updateTradeFill).mockRejectedValue(new Error('更新测试中不调用'))
    vi.mocked(deleteTradeFill).mockRejectedValue(new Error('成交删除测试中不调用'))
    mockedRecordManualTradeFill().mockRejectedValue(new Error('手工录入测试中不调用'))
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    confirmSpy.mockRestore()
    vi.clearAllMocks()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('deletes a planned watched case from the review list after confirmation', async () => {
    await renderPage(root)

    expect(document.body.textContent).toContain('600519')
    const deleteButton = document.querySelector<HTMLButtonElement>('button[aria-label="删除关注 600519"]')
    expect(deleteButton).not.toBeNull()

    await act(async () => {
      deleteButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('贵州茅台'))
    expect(deleteTradeCase).toHaveBeenCalledWith('case-delete')
    expect(document.body.textContent).not.toContain('600519')
    expect(document.body.textContent).toContain('暂无复盘单')
  })

  it('deletes a cancelled zero-position case from the review list', async () => {
    vi.mocked(fetchTradeCases).mockResolvedValue([tradeCase('case-cancelled', '601318', '中国平安', 'CANCELLED')])

    await renderPage(root)

    const deleteButton = document.querySelector<HTMLButtonElement>('button[aria-label="删除关注 601318"]')
    expect(deleteButton).not.toBeNull()
    expect(deleteButton?.disabled).toBe(false)

    await act(async () => {
      deleteButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await flushPromises()
    })

    expect(deleteTradeCase).toHaveBeenCalledWith('case-cancelled')
    expect(document.body.textContent).not.toContain('601318')
  })

  it('uses the trade review grid table style for aligned divided cells', async () => {
    await renderPage(root)

    const table = document.querySelector('table')
    expect(table?.className).toContain('trade-review-table')
  })

  it('opens a standalone manual trade entry and submits a buy fill', async () => {
    vi.mocked(fetchTradeCases).mockResolvedValue([])
    mockedRecordManualTradeFill().mockResolvedValue(tradeCaseDetail('manual-case', '600367', '红星发展'))

    await renderPage(root)

    await act(async () => {
      document.querySelector<HTMLButtonElement>('button[aria-label="独立录入成交"]')?.click()
      await flushPromises()
    })

    expect(document.body.textContent).toContain('独立录入成交')
    expect(document.body.textContent).toContain('股票代码')

    await act(async () => {
      setInputValue('股票代码', '600367')
      setInputValue('公司名称', '红星发展')
      setInputValue('成交时间', '2026-08-11T10:35:00')
      setInputValue('成交价', '18.80')
      setInputValue('成交股数', '100')
      await flushPromises()
    })

    await act(async () => {
      document.querySelector<HTMLButtonElement>('button[type="submit"]')?.click()
      await flushPromises()
    })

    expect(mockedRecordManualTradeFill()).toHaveBeenCalledWith({
      symbol: '600367',
      companyName: '红星发展',
      fill: {
        side: 'BUY',
        executedAt: '2026-08-11T02:35:00.000Z',
        price: 18.8,
        quantity: 100
      }
    })
    expect(document.body.textContent).toContain('600367')
  })
})

async function renderPage(root: Root) {
  await act(async () => {
    root.render(
      <StrictMode>
        <TradeReviewPage />
      </StrictMode>
    )
    await flushPromises()
  })
}

async function flushPromises() {
  await new Promise((resolve) => window.setTimeout(resolve, 0))
}

function setInputValue(label: string, value: string) {
  const input = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)
  if (!input) throw new Error(`missing input: ${label}`)
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  if (!setter) throw new Error('missing input value setter')
  setter.call(input, value)
  input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }))
  input.dispatchEvent(new Event('change', { bubbles: true }))
}

function tradeCase(
  caseId: string,
  symbol: string,
  companyName: string,
  status: TradeCaseSummary['status'] = 'PLANNED'
): TradeCaseSummary {
  return {
    caseId,
    symbol,
    companyName,
    sourceModule: '短线推荐',
    recommendationAction: '观察',
    recommendationScore: 80,
    ruleVersion: 'test-v1',
    recommendedPrice: 100,
    recommendedAt: '2026-07-30T14:50:00+08:00',
    recommendationVerified: true,
    status,
    ledger: {
      latestPrice: null,
      positionQuantity: 0,
      averageCost: 0,
      realizedProfit: 0,
      unrealizedProfit: null,
      totalProfit: null
    },
    outcomes: [],
    createdAt: '2026-07-30T14:50:00+08:00',
    updatedAt: '2026-07-30T14:50:00+08:00'
  }
}

function tradeCaseDetail(caseId: string, symbol: string, companyName: string): TradeCaseSummary & {
  decisionId: string | null
  recommendationPayload: unknown
  fills: { fillId: string; side: 'BUY' | 'SELL'; executedAt: string; price: number; quantity: number; createdAt: string; updatedAt: string }[]
  outcomeWarnings: string[]
} {
  return {
    ...tradeCase(caseId, symbol, companyName),
    decisionId: null,
    recommendationPayload: { source: 'manual' },
    fills: [],
    outcomeWarnings: []
  }
}
