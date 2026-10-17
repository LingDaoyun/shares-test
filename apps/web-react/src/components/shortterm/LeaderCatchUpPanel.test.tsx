// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchShortTermCatchUp, fetchShortTermCatchUpFollowUp } from '../../api/client'
import { catchUpSnapshotPair, emptyCatchUpLocalState, loadCatchUpLocalState, rememberCatchUpSnapshot, saveCatchUpLocalState } from '../../lib/shortTermCatchUpStorage'
import type { ShortTermLimitUpStock } from '../../types'
import type { ShortTermCatchUpSnapshot } from '../../types/shortTermCatchUp'
import { LeaderCatchUpPanel, SavedCatchUpObservationsPanel } from './LeaderCatchUpPanel'

vi.mock('../../api/client', () => ({ fetchShortTermCatchUp: vi.fn(), fetchShortTermCatchUpFollowUp: vi.fn() }))

const anchor: ShortTermLimitUpStock = {
  symbol: '600001', name: '封板锚点', industry: '半导体', latestPrice: 11, changePercent: 10,
  amount: 1e8, turnoverRate: 2, consecutiveBoards: 2, statDays: 2, statBoards: 2,
  sealFunds: 2e7, firstSealTime: '09:35:00', lastSealTime: '09:35:00', sealBreakCount: 0
}
function snapshot(overrides: Partial<ShortTermCatchUpSnapshot> = {}): ShortTermCatchUpSnapshot {
  return {
    tradeDate: '2026-10-15', industry: '半导体', leaderSymbol: anchor.symbol,
    boardCode: 'BK1001', boardName: '供应商共同概念', fetchedAt: '2026-10-15T06:00:05Z',
    marketTimestamp: '2026-10-15T06:00:00Z', status: 'READY', leader: anchor,
    relatedBoards: [{ code: 'BK1001', name: '供应商共同概念' }, { code: 'BK1002', name: '供应商共同指数' }],
    quoteSource: '东方财富成分 / 腾讯报价', expectedMemberCount: 10, fetchedMemberCount: 10, validQuoteCount: 10,
    historyValidCount: 8, historyCoveragePercent: 80, risingCount: 5, sealedCount: 1,
    eligibleCandidateCount: 25, displayedCount: 1, truncatedCount: 24,
    historyDates: ['2026-09-30', '2026-10-08', '2026-10-09', '2026-10-12', '2026-10-13', '2026-10-14'],
    nextTradingDate: '2026-10-16', evidence: ['供应商共同板块归属'], contraryEvidence: ['归属不证明事件受益'], dataGaps: [],
    candidates: [{ symbol: '600002', name: '中位观察样本', latestPrice: 10, changePercent: 2,
      amount: null, turnoverRate: null, marketTimestamp: '2026-10-15T06:00:00Z', return5: 3, returnPercentile: 50,
      returnGapToLeader: -7, middle: true, historyStatus: 'VALID', evidence: ['历史收益中位'], contraryEvidence: ['落后不能证明将补涨'], dataGaps: ['换手未知'] }],
    ...overrides
  }
}
const boardsOnly = () => snapshot({ status: 'SELECT_BOARD', boardCode: null, boardName: null, candidates: [], marketTimestamp: null })

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('LeaderCatchUpPanel', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    window.localStorage.clear()
    vi.mocked(fetchShortTermCatchUp).mockImplementation(async (_industry, _date, _leader, boardCode) => boardCode ? snapshot({ boardCode }) : boardsOnly())
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove(); window.localStorage.clear()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function render(stocks = [anchor], date = '2026-10-15') {
    await act(async () => { root.render(<LeaderCatchUpPanel industry="半导体" tradeDate={date} sealedStocks={stocks} />) })
  }
  async function choose(label: string, value: string) {
    await act(async () => {
      const select = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
      select.value = value; select.dispatchEvent(new Event('change', { bubbles: true }))
    })
  }
  async function click(text: string) {
    await act(async () => { Array.from(host.querySelectorAll('button')).find(button => button.textContent === text)?.click() })
  }
  async function selectBoard() {
    await choose('已封板锚点', anchor.symbol)
    await choose('供应商共同板块', 'BK1001')
  }

  it('requires an explicit sealed anchor and verified board, and describes the board relationship without confirming leadership', async () => {
    await render()
    expect(fetchShortTermCatchUp).not.toHaveBeenCalled()
    expect(host.textContent).toContain('锚点不代表已确认的龙头')
    await choose('已封板锚点', anchor.symbol)
    expect(fetchShortTermCatchUp).toHaveBeenLastCalledWith('半导体', '2026-10-15', anchor.symbol, undefined)
    expect(host.querySelector('select[aria-label="供应商共同板块"]')?.textContent).toContain('供应商共同指数')
    expect(host.textContent).not.toContain('中位观察样本')
    await choose('供应商共同板块', 'BK1001')
    expect(fetchShortTermCatchUp).toHaveBeenLastCalledWith('半导体', '2026-10-15', anchor.symbol, 'BK1001')
    expect(host.textContent).toContain('中位观察样本')
    expect(host.textContent).toContain('25 家，展示 1 家，另 24 家未展示')
    expect(host.textContent).toContain('历史覆盖 80.00%')
    expect(host.textContent).toContain('落后不能证明将补涨')
    expect(host.textContent).toContain('下一核实交易日：2026-10-16')
    expect(host.textContent).toContain('不是实际成交收益')
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(0)
  })

  it('does not format an unknown percentile as zero or label a history-shortage candidate as middle', async () => {
    const data = snapshot({ status: 'DATA_GAP', historyValidCount: 3, historyCoveragePercent: 30, historyDates: [], dataGaps: ['连续历史不足'] })
    data.candidates = data.candidates.map(row => ({ ...row, return5: null, returnPercentile: null, returnGapToLeader: null, historyStatus: 'UNKNOWN', middle: false }))
    vi.mocked(fetchShortTermCatchUp).mockImplementation(async (_i, _d, _l, boardCode) => boardCode ? data : boardsOnly())
    await render(); await selectBoard()
    const table = host.querySelector('table[aria-label="共同板块中位优先观察候选"]')!
    expect(table.textContent).toContain('分位未知')
    expect(table.textContent).not.toContain('0.00%')
    expect(host.textContent).toContain('连续历史不足')
    expect(loadCatchUpLocalState().observations[0].returnPercentile).toBeNull()
  })

  it('labels unavailable member and unestablished history counts as unknown instead of operational zeros', async () => {
    const failed = snapshot({ status: 'DATA_GAP', quoteSource: null, marketTimestamp: null,
      expectedMemberCount: 0, fetchedMemberCount: 0, validQuoteCount: 0, historyValidCount: 0,
      historyCoveragePercent: null, historyDates: [], candidates: [], dataGaps: ['板块行情获取失败'] })
    vi.mocked(fetchShortTermCatchUp).mockImplementation(async (_i, _d, _l, boardCode) => boardCode ? failed : boardsOnly())
    await render(); await selectBoard()
    expect(host.textContent).toContain('成分及有效报价未知（未获取全量快照）')
    expect(host.textContent).toContain('连续历史未建立')
    expect(host.textContent).not.toContain('成分 0/0')
    expect(host.textContent).not.toContain('连续历史 0')
    expect(loadCatchUpLocalState().observations).toHaveLength(0)
  })

  it('preserves a real zero history count after six verified history dates were assessed', async () => {
    const assessed = snapshot({ status: 'DATA_GAP', historyValidCount: 0, historyCoveragePercent: 0, candidates: [] })
    vi.mocked(fetchShortTermCatchUp).mockImplementation(async (_i, _d, _l, boardCode) => boardCode ? assessed : boardsOnly())
    await render(); await selectBoard()
    expect(host.textContent).toContain('连续历史 0，历史覆盖 0.00%')
    expect(host.textContent).not.toContain('连续历史未建立')
  })

  it('clears the old board immediately and ignores a late response for the previous board', async () => {
    const pending = deferred<ShortTermCatchUpSnapshot>()
    vi.mocked(fetchShortTermCatchUp).mockImplementation((_i, _d, _l, boardCode) => {
      if (!boardCode) return Promise.resolve(boardsOnly())
      return boardCode === 'BK1001' ? pending.promise : Promise.resolve(snapshot({ boardCode: 'BK1002', boardName: '供应商共同指数', candidates: [] }))
    })
    await render(); await choose('已封板锚点', anchor.symbol); await choose('供应商共同板块', 'BK1001')
    await choose('供应商共同板块', 'BK1002')
    await act(async () => { pending.resolve(snapshot()) })
    expect(host.textContent).not.toContain('中位观察样本')
    expect(loadCatchUpLocalState().observations).toHaveLength(0)
    expect(host.textContent).toContain('共同板块 供应商共同指数')
  })

  it('rejects a mismatched response without exposing or persisting its candidates', async () => {
    vi.mocked(fetchShortTermCatchUp).mockImplementation(async (_i, _d, _l, boardCode) => boardCode ? snapshot({ tradeDate: '2026-10-14' }) : boardsOnly())
    await render(); await selectBoard()
    expect(host.textContent).toContain('日期、锚点或板块不一致')
    expect(host.textContent).not.toContain('中位观察样本')
    expect(loadCatchUpLocalState().observations).toHaveLength(0)
  })

  it('freezes the first observation and samples only advancing source timestamps', async () => {
    await render(); await selectBoard()
    const updated = snapshot({ risingCount: 9, sealedCount: null, fetchedAt: '2026-10-15T06:00:30Z' })
    updated.candidates = updated.candidates.map(row => ({ ...row, latestPrice: 12, evidence: ['后来的证据'] }))
    vi.mocked(fetchShortTermCatchUp).mockResolvedValue(updated)
    await click('刷新关联观察')
    expect(loadCatchUpLocalState().observations[0].observedPrice).toBe(10)
    expect(loadCatchUpLocalState().observations[0].evidence).not.toContain('后来的证据')
    expect(catchUpSnapshotPair(loadCatchUpLocalState(), '2026-10-15', anchor.symbol, 'BK1001')).toHaveLength(1)
    vi.mocked(fetchShortTermCatchUp).mockResolvedValue({ ...updated, marketTimestamp: '2026-10-15T06:01:00Z', risingCount: 6 })
    await click('刷新关联观察')
    expect(host.textContent).toContain('上涨变化 +1')
    expect(host.textContent).toContain('封板变化未知')
    expect(host.textContent).toContain('不能证明撤单或资金轮动')
  })

  it('refreshes the independent saved view after a same-window save and filters across industries without scanning', async () => {
    await act(async () => { root.render(<SavedCatchUpObservationsPanel />) })
    expect(host.textContent).toContain('暂无首次留样')
    const first = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot(), '2026-10-15T06:00:06Z')
    const other = snapshot({ industry: '银行', leaderSymbol: '600010', boardCode: 'BK1002', boardName: '银行共同板块',
      leader: { ...anchor, symbol: '600010', name: '银行锚点' } })
    other.candidates = other.candidates.map(row => ({ ...row, symbol: '600011', name: '银行留样' }))
    const all = rememberCatchUpSnapshot(first, other, '2026-10-15T06:01:06Z')
    await act(async () => { saveCatchUpLocalState(all) })
    expect(host.textContent).toContain('中位观察样本')
    expect(host.textContent).toContain('银行留样')
    await choose('首次留样行业', '银行')
    const table = host.querySelector('table[aria-label="本浏览器首次留样及隔日价格反馈"]')!
    expect(table.textContent).toContain('银行留样')
    expect(table.textContent).not.toContain('中位观察样本')
    expect(fetchShortTermCatchUp).not.toHaveBeenCalled()
    expect(fetchShortTermCatchUpFollowUp).not.toHaveBeenCalled()
  })

  it('queries saved observations independently when the anchor is no longer sealed on the next day', async () => {
    const saved = rememberCatchUpSnapshot(emptyCatchUpLocalState(), snapshot(), '2026-10-15T06:00:06Z')
    saveCatchUpLocalState(saved)
    const first = saved.observations[0]
    vi.mocked(fetchShortTermCatchUpFollowUp).mockResolvedValue({ fetchedAt: '2026-10-16T06:00:05Z', dataGaps: [], results: [{
      id: first.id, tradeDate: first.tradeDate, symbol: first.symbol, leaderSymbol: first.leaderSymbol, observedPrice: first.observedPrice,
      followUpDate: '2026-10-16', status: 'OBSERVED', latestPrice: 10.2, quoteTradeDate: '2026-10-16',
      marketTimestamp: '2026-10-16T06:00:00Z', priceChangePercent: 2, explanation: '相对首次观察价的未复权价格变动，非真实收益。', dataGaps: []
    }] })
    await render([], '2026-10-16')
    await click('查询隔日价格反馈')
    expect(fetchShortTermCatchUp).not.toHaveBeenCalled()
    expect(fetchShortTermCatchUpFollowUp).toHaveBeenCalledWith([{ id: first.id, tradeDate: first.tradeDate, symbol: first.symbol, leaderSymbol: first.leaderSymbol, observedPrice: 10 }])
    expect(host.textContent).toContain('+2.00%')
    expect(host.textContent).toContain('首次观察价的未复权价格变动')
    expect(host.textContent).toContain('锚点隔日无需继续涨停')
  })
})
