// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermCatchUpFollowUp } from '../../api/client'
import { CATCH_UP_STORAGE_KEY, loadCatchUpLocalState, saveCatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { CatchUpFirstObservation } from '../../lib/shortTermCatchUpStorage'
import type { ShortTermCatchUpFollowUpResult, ShortTermCatchUpFollowUpSnapshot } from '../../types/shortTermCatchUp'
import { CatchUpFollowUpPanel } from './CatchUpFollowUpPanel'

vi.mock('../../api/client', () => ({ fetchShortTermCatchUpFollowUp: vi.fn() }))
vi.mock('./StockKlineButton', () => ({ StockKlineButton: ({ symbol }: { symbol: string }) => <button>日线 {symbol}</button> }))

function observation(index: number): CatchUpFirstObservation {
  const symbol = String(600100 + index)
  return {
    id: `2026-10-15:600001:BK1001:${symbol}`, tradeDate: '2026-10-15', symbol,
    name: `观察样本${index}`, industry: '半导体', leaderSymbol: '600001', leaderName: '封板锚点',
    boardCode: 'BK1001', boardName: '共同概念', observedPrice: 10,
    observedAt: new Date(Date.parse('2026-10-15T06:00:00Z') + index * 60000).toISOString(),
    marketTimestamp: '2026-10-15T06:00:00Z', nextTradingDate: '2026-10-16',
    return5: null, returnPercentile: null, quoteSource: '腾讯报价',
    evidence: [], contraryEvidence: [], dataGaps: ['原始观察换手未知']
  }
}

function result(row: CatchUpFirstObservation, overrides: Partial<ShortTermCatchUpFollowUpResult> = {}): ShortTermCatchUpFollowUpResult {
  return {
    id: row.id, tradeDate: row.tradeDate, symbol: row.symbol, leaderSymbol: row.leaderSymbol, observedPrice: row.observedPrice,
    followUpDate: '2026-10-16', status: 'OBSERVED', latestPrice: 11, quoteTradeDate: '2026-10-16',
    marketTimestamp: '2026-10-16T06:00:00Z', priceChangePercent: 10, explanation: '下一交易日报价观察', dataGaps: [],
    ...overrides
  }
}

function feedback(results: ShortTermCatchUpFollowUpResult[]): ShortTermCatchUpFollowUpSnapshot {
  return { fetchedAt: '2026-10-16T06:00:05Z', results, dataGaps: ['反馈供应商缺口'] }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

describe('CatchUpFollowUpPanel', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    window.localStorage.clear()
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove(); window.localStorage.clear()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })
  async function render(rows: CatchUpFirstObservation[] = []) {
    saveCatchUpLocalState({ observations: rows, snapshots: [] })
    await act(async () => { root.render(<CatchUpFollowUpPanel />) })
  }
  async function click(label: string) {
    await act(async () => { Array.from(host.querySelectorAll('button')).find(button => button.textContent === label)?.click() })
  }
  function rowFor(row: CatchUpFirstObservation) {
    return host.querySelector(`tr[data-symbol="${row.symbol}"]`)!
  }

  it('explains how to establish browser observations without fetching on mount', async () => {
    await render()
    expect(host.textContent).toContain('本浏览器首次观察')
    expect(host.textContent).toContain('行业明细')
    expect(host.textContent).toContain('锚点')
    expect(host.textContent).toContain('共同板块')
    expect(host.textContent).toContain('观察价变化不是实际交易收益')
    expect(fetchShortTermCatchUpFollowUp).not.toHaveBeenCalled()
    expect(host.querySelector('table')).toBeNull()
  })

  it('shows all 100 retained observations newest first in two user-requested batches', async () => {
    const rows = Array.from({ length: 100 }, (_, index) => observation(index))
    vi.mocked(fetchShortTermCatchUpFollowUp).mockImplementation(async requested => feedback(requested.map(row => result(rows.find(saved => saved.id === row.id)!))))
    await render(rows)
    expect(host.querySelectorAll('tbody tr')).toHaveLength(50)
    expect(host.querySelector('tbody tr')?.textContent).toContain('观察样本99')
    expect(host.textContent).toContain('第 1 / 2 页')
    expect(fetchShortTermCatchUpFollowUp).not.toHaveBeenCalled()
    await click('更新隔日反馈')
    const firstBatch = vi.mocked(fetchShortTermCatchUpFollowUp).mock.calls[0][0]
    expect(firstBatch).toHaveLength(50)
    expect(firstBatch[0].id).toBe(rows[99].id)
    expect(firstBatch[49].id).toBe(rows[50].id)
    expect(rowFor(rows[99]).textContent).toContain('+10.00%')
    await click('下一页')
    expect(host.textContent).toContain('第 2 / 2 页')
    expect(host.textContent).not.toContain('+10.00%')
    expect(fetchShortTermCatchUpFollowUp).toHaveBeenCalledTimes(1)
    await click('更新隔日反馈')
    const secondBatch = vi.mocked(fetchShortTermCatchUpFollowUp).mock.calls[1][0]
    expect(secondBatch).toHaveLength(50)
    expect(secondBatch[0].id).toBe(rows[49].id)
    expect(secondBatch[49].id).toBe(rows[0].id)
    expect(loadCatchUpLocalState().observations).toEqual(rows)
  })

  it('aligns reordered feedback by identity and retains original observation prices', async () => {
    const rows = [observation(0), observation(1)]
    vi.mocked(fetchShortTermCatchUpFollowUp).mockResolvedValue(feedback([
      result(rows[0], { priceChangePercent: -3, latestPrice: 9.7 }),
      result(rows[1], { priceChangePercent: 9, latestPrice: 10.9 })
    ]))
    await render(rows); await click('更新隔日反馈')
    expect(rowFor(rows[0]).textContent).toContain('-3.00%')
    expect(rowFor(rows[1]).textContent).toContain('+9.00%')
    expect(rowFor(rows[0]).textContent).toContain('10.00 元/股')
    expect(host.textContent).toContain('源行情时间')
    expect(host.textContent).toContain('反馈获取时间')
    expect(host.textContent).toContain('原始观察换手未知')
    expect(host.textContent).toContain('反馈供应商缺口')
    expect(loadCatchUpLocalState().observations).toEqual(rows)
  })

  it('keeps waiting, expired, unknown and invalid observed changes distinct from zero', async () => {
    const rows = Array.from({ length: 5 }, (_, index) => observation(index))
    vi.mocked(fetchShortTermCatchUpFollowUp).mockResolvedValue(feedback([
      result(rows[0], { status: 'WAITING', priceChangePercent: 0, latestPrice: 10 }),
      result(rows[1], { status: 'EXPIRED', priceChangePercent: 0, latestPrice: 10 }),
      result(rows[2], { status: 'UNKNOWN', priceChangePercent: 0, latestPrice: 10 }),
      result(rows[3], { priceChangePercent: Number.NaN }),
      result(rows[4], { priceChangePercent: 1.5, latestPrice: 10.15 })
    ]))
    await render(rows); await click('更新隔日反馈')
    expect(rowFor(rows[0]).textContent).toContain('未到反馈日')
    expect(rowFor(rows[1]).textContent).toContain('已过反馈日')
    expect(rowFor(rows[2]).textContent).toContain('行情未知')
    expect(rowFor(rows[3]).textContent).toContain('价格变化未知')
    expect(rowFor(rows[4]).textContent).toContain('已观察')
    expect(rowFor(rows[4]).textContent).toContain('+1.50%')
    expect(host.textContent).not.toContain('0.00%')
    expect(host.textContent).not.toContain('NaN')
  })

  it.each(['missing', 'duplicate', 'foreign', 'tradeDate', 'symbol', 'leaderSymbol', 'observedPrice'] as const)(
    'rejects %s feedback identities without partially pairing rows', async fault => {
      const rows = [observation(0), observation(1)]
      const results = rows.map(row => result(row))
      if (fault === 'missing') results.pop()
      else if (fault === 'duplicate') results[1] = results[0]
      else if (fault === 'foreign') results[0] = result(observation(99))
      else if (fault === 'observedPrice') results[0].observedPrice = 12
      else results[0][fault] = '不匹配'
      vi.mocked(fetchShortTermCatchUpFollowUp).mockResolvedValue(feedback(results))
      await render(rows); await click('更新隔日反馈')
      expect(host.querySelector('[role="alert"]')?.textContent).toContain('反馈身份不完整或不一致')
      expect(host.textContent).not.toContain('+10.00%')
      expect(rowFor(rows[0]).textContent).toContain('10.00 元/股')
    }
  )

  it('retains first observations after an error and supports retry', async () => {
    const rows = [observation(0)]
    vi.mocked(fetchShortTermCatchUpFollowUp).mockRejectedValueOnce(new Error('行情服务暂不可用'))
      .mockResolvedValueOnce(feedback(rows.map(row => result(row))))
    await render(rows); await click('更新隔日反馈')
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('行情服务暂不可用')
    expect(rowFor(rows[0]).textContent).toContain('10.00 元/股')
    await click('更新隔日反馈')
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(rowFor(rows[0]).textContent).toContain('+10.00%')
    expect(loadCatchUpLocalState().observations).toEqual(rows)
  })

  it('ignores a late page response after another page has been requested', async () => {
    const rows = Array.from({ length: 51 }, (_, index) => observation(index))
    const pending = deferred<ShortTermCatchUpFollowUpSnapshot>()
    vi.mocked(fetchShortTermCatchUpFollowUp).mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(feedback([result(rows[0], { priceChangePercent: 2, latestPrice: 10.2 })]))
    await render(rows); await click('更新隔日反馈'); await click('下一页'); await click('更新隔日反馈')
    await act(async () => { pending.resolve(feedback(rows.slice(1).map(row => result(row)))) })
    expect(rowFor(rows[0]).textContent).toContain('+2.00%')
    expect(host.textContent).not.toContain('+10.00%')
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })

  it('reloads same-page and cross-tab observations and ignores stale errors', async () => {
    const first = observation(0)
    const next = observation(1)
    const pending = deferred<ShortTermCatchUpFollowUpSnapshot>()
    vi.mocked(fetchShortTermCatchUpFollowUp).mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(feedback([result(next, { priceChangePercent: 2, latestPrice: 10.2 })]))
    await render([first]); await click('更新隔日反馈')
    await act(async () => { saveCatchUpLocalState({ observations: [next], snapshots: [] }) })
    expect(host.textContent).not.toContain(first.name)
    expect(host.textContent).toContain(next.name)
    await click('更新隔日反馈')
    await act(async () => { pending.reject(new Error('旧请求失败')) })
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(rowFor(next).textContent).toContain('+2.00%')
    await act(async () => {
      window.localStorage.setItem(CATCH_UP_STORAGE_KEY, JSON.stringify({ version: 1, observations: [first], snapshots: [] }))
      window.dispatchEvent(new StorageEvent('storage', { key: CATCH_UP_STORAGE_KEY }))
    })
    expect(host.textContent).toContain(first.name)
    expect(host.textContent).not.toContain(next.name)
    expect(host.textContent).not.toContain('+2.00%')
    expect(fetchShortTermCatchUpFollowUp).toHaveBeenCalledTimes(2)
  })
})
