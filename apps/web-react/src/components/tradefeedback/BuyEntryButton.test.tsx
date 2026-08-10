// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { addTradeFill } from '../../api/client'
import { useToastStore } from '../ui/Toast'
import { useTradeFeedbackStore } from '../../store/tradeFeedbackStore'
import type { TradeCaseDetail } from '../../types'
import { BuyEntryButton } from './BuyEntryButton'

vi.mock('../../api/client', () => ({
  addTradeFill: vi.fn(),
  createTradeCase: vi.fn(),
  fetchTradeCase: vi.fn(),
  fetchTradeCases: vi.fn()
}))

const mockedAddTradeFill = vi.mocked(addTradeFill)

describe('BuyEntryButton', () => {
  let host: HTMLDivElement
  let root: Root
  let ensureCase: ReturnType<typeof vi.fn>
  let upsertCase: ReturnType<typeof vi.fn>

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-10T06:52:03Z'))
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    ensureCase = vi.fn().mockResolvedValue(tradeCaseDetail({ fills: [] }))
    upsertCase = vi.fn()
    useTradeFeedbackStore.setState({
      casesById: {},
      caseIdByRecommendation: {},
      loaded: false,
      loading: false,
      loadingMore: false,
      hasMore: true,
      nextCursor: null,
      ensureCase,
      upsertCase
    })
    useToastStore.setState({ toasts: [] })
    mockedAddTradeFill.mockResolvedValue(tradeCaseDetail({
      status: 'HOLDING',
      ledger: {
        latestPrice: 7.2,
        positionQuantity: 200,
        averageCost: 7.2,
        realizedProfit: 0,
        unrealizedProfit: null,
        totalProfit: null
      },
      fills: [{
        fillId: 'fill-buy',
        side: 'BUY',
        executedAt: '2026-08-10T06:52:03.000Z',
        price: 7.2,
        quantity: 200,
        createdAt: '2026-08-10T06:52:04Z',
        updatedAt: '2026-08-10T06:52:04Z'
      }]
    }))
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    document.body.querySelectorAll('[role="dialog"]').forEach((element) => element.parentElement?.remove())
    vi.useRealTimers()
    vi.clearAllMocks()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('disables the buy action when the recommendation has no attestation token', async () => {
    await renderButton(root, { attestationToken: null })

    const buy = button('买入 航民股份 600987')

    expect(buy?.disabled).toBe(true)
    expect(buy?.title).toContain('缺少可验证的实时价格或时间')
  })

  it('opens with default price, quantity and current Shanghai time without persisting immediately', async () => {
    await renderButton(root)

    await act(async () => {
      button('买入 航民股份 600987')?.click()
      await flushPromises()
    })

    expect(dialogText()).toContain('确认买入')
    expect(input('买入价格')?.value).toBe('7.2')
    expect(input('买入股数')?.value).toBe('100')
    expect(input('买入时间')?.value.startsWith('2026-08-10T14:52:03')).toBe(true)
    expect(ensureCase).not.toHaveBeenCalled()
    expect(mockedAddTradeFill).not.toHaveBeenCalled()
  })

  it('confirms a BUY fill, merges the returned trade case and closes the dialog', async () => {
    await renderButton(root)
    await openDialog()
    setInput('买入股数', '200')

    await act(async () => {
      button('确认买入')?.click()
      await flushPromises()
    })

    expect(ensureCase).toHaveBeenCalledWith({ attestationToken: 'token-long' })
    expect(mockedAddTradeFill).toHaveBeenCalledWith('case-600987', {
      side: 'BUY',
      executedAt: '2026-08-10T06:52:03.000Z',
      price: 7.2,
      quantity: 200
    })
    expect(upsertCase).toHaveBeenCalledWith(expect.objectContaining({
      caseId: 'case-600987',
      status: 'HOLDING'
    }))
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    const toasts = useToastStore.getState().toasts
    expect(toasts[toasts.length - 1]?.message).toContain('买入已记录')
  })

  it('keeps user input and reports validation before touching the network', async () => {
    await renderButton(root)
    await openDialog()
    setInput('买入价格', '-1')
    setInput('买入股数', '10.5')

    await act(async () => {
      button('确认买入')?.click()
      await flushPromises()
    })

    expect(dialogText()).toContain('买入价格必须大于 0')
    expect(input('买入价格')?.value).toBe('-1')
    expect(input('买入股数')?.value).toBe('10.5')
    expect(ensureCase).not.toHaveBeenCalled()
    expect(mockedAddTradeFill).not.toHaveBeenCalled()
  })

  it('keeps the modal open with field-aware API errors after save failure', async () => {
    ensureCase.mockResolvedValue(tradeCaseDetail({ fills: [] }))
    mockedAddTradeFill.mockRejectedValue({
      response: {
        data: {
          fields: {
            executedAt: '成交时间不能早于推荐时间'
          }
        }
      }
    })
    await renderButton(root)
    await openDialog()
    setInput('买入股数', '300')

    await act(async () => {
      button('确认买入')?.click()
      await flushPromises()
    })

    expect(dialogText()).toContain('成交时间不能早于推荐时间')
    expect(input('买入股数')?.value).toBe('300')
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
  })

  it('prevents duplicate confirmations while a save is in flight', async () => {
    let resolveFill: (value: TradeCaseDetail) => void = () => undefined
    mockedAddTradeFill.mockImplementation(() => new Promise((resolve) => {
      resolveFill = resolve
    }) as never)
    await renderButton(root)
    await openDialog()

    await act(async () => {
      button('确认买入')?.click()
      button('确认买入')?.click()
      await Promise.resolve()
    })

    expect(ensureCase).toHaveBeenCalledTimes(1)
    expect(mockedAddTradeFill).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveFill(tradeCaseDetail({ status: 'HOLDING', fills: [] }))
      await flushPromises()
    })
  })
})

async function renderButton(
  root: Root,
  props: Partial<React.ComponentProps<typeof BuyEntryButton>> = {}
) {
  await act(async () => {
    root.render(
      <BuyEntryButton
        symbol="600987"
        companyName="航民股份"
        latestPrice={7.2}
        recommendedAt="2026-08-10T14:50:00+08:00"
        attestationToken="token-long"
        {...props}
      />
    )
    await flushPromises()
  })
}

async function openDialog() {
  await act(async () => {
    button('买入 航民股份 600987')?.click()
    await flushPromises()
  })
}

function button(name: string) {
  return [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((item) => item.getAttribute('aria-label') === name || item.textContent?.trim() === name)
}

function input(label: string) {
  return document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)
}

function setInput(label: string, value: string) {
  const field = input(label)
  if (!field) throw new Error(`Missing input ${label}`)
  act(() => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    setter?.call(field, value)
    field.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

function dialogText() {
  return document.querySelector('[role="dialog"]')?.textContent ?? ''
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}

function tradeCaseDetail(overrides: Partial<TradeCaseDetail>): TradeCaseDetail {
  return {
    caseId: 'case-600987',
    symbol: '600987',
    companyName: '航民股份',
    sourceModule: 'LONG_TERM',
    recommendationAction: '分批加仓',
    recommendationScore: 88,
    ruleVersion: 'long-term-value-v1',
    recommendedPrice: 7.2,
    recommendedAt: '2026-08-10T14:50:00+08:00',
    recommendationVerified: true,
    status: 'PLANNED',
    ledger: {
      latestPrice: null,
      positionQuantity: 0,
      averageCost: 0,
      realizedProfit: 0,
      unrealizedProfit: null,
      totalProfit: null
    },
    outcomes: [],
    decisionId: null,
    recommendationPayload: {},
    fills: [],
    outcomeWarnings: [],
    createdAt: '2026-08-10T14:50:00+08:00',
    updatedAt: '2026-08-10T14:50:00+08:00',
    ...overrides
  }
}
