// @vitest-environment jsdom

import { act, StrictMode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { checkFiveDayExecution, createFiveDayAttestation, fetchFiveDayHistory, fetchFiveDaySnapshot, refreshFiveDayHistory, startFiveDayScan } from '../api/fiveDay'
import { useTradeFeedbackStore } from '../store/tradeFeedbackStore'
import type { FiveDayCandidate, FiveDaySnapshot } from '../types/fiveDay'
import { FiveDayPage } from './FiveDayPage'

vi.mock('../api/fiveDay', () => ({
  checkFiveDayExecution: vi.fn(),
  createFiveDayAttestation: vi.fn(),
  fetchFiveDayHistory: vi.fn(),
  fetchFiveDaySnapshot: vi.fn(),
  refreshFiveDayHistory: vi.fn(),
  startFiveDayScan: vi.fn()
}))

describe('FiveDayPage observation workflow', () => {
  let host: HTMLDivElement
  let root: Root
  const originalEnsureCase = useTradeFeedbackStore.getState().ensureCase

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-09-08T00:00:00Z'))
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    vi.mocked(fetchFiveDaySnapshot).mockImplementation(async (scope) => scope.strategy === 'v1' ? snapshot() : contextSnapshot())
    vi.mocked(fetchFiveDayHistory).mockResolvedValue({ rows: [], cohorts: [], notes: [] })
    vi.mocked(refreshFiveDayHistory).mockResolvedValue({ rows: [], cohorts: [], notes: ['本地日 K 重算完成'] })
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    useTradeFeedbackStore.setState({ ensureCase: originalEnsureCase })
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('defaults to v2, shows context and only lists eligible candidates without scanning', async () => {
    await renderPage(root)
    expect(fetchFiveDaySnapshot).toHaveBeenCalledWith({ strategy: 'v2', includeGrowth: false, includeStar: false })
    expect(fetchFiveDayHistory).toHaveBeenCalledWith('v2')
    expect(startFiveDayScan).not.toHaveBeenCalled()
    expect(candidateSymbols()).toEqual(['600001'])
    expect(button('执行条件齐备 · 1')).toBeDefined()
    expect(document.body.textContent).toContain('市场环境正常')
    expect(document.body.textContent).toContain('来源状态：来源可用')
    expect(document.body.textContent).toContain('展示配置来源近期公开政策；个股详情按细分行业关联')
    expect(document.body.textContent).toContain('收益中位数')
    expect(document.body.textContent).toContain('强势')
    expect(document.body.textContent).toContain('同股近 5 日 / 此前 20 日平均成交量')
    expect(document.body.textContent).toContain('不是板块净流入')
    await click(button('全部主线量价观察 · 3'))
    expect(candidateSymbols()).toEqual(['600001', '600002', '600003'])
  })

  it('isolates v1 snapshots and history from late v2 responses', async () => {
    let resolveSnapshot: (value: FiveDaySnapshot) => void = () => undefined
    let resolveHistory: (value: { rows: []; cohorts: []; notes: string[] }) => void = () => undefined
    vi.mocked(fetchFiveDaySnapshot).mockReturnValue(new Promise((resolve) => { resolveSnapshot = resolve }))
    vi.mocked(fetchFiveDayHistory).mockReturnValue(new Promise((resolve) => { resolveHistory = resolve }))
    await renderPage(root)
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue(snapshot())
    vi.mocked(fetchFiveDayHistory).mockResolvedValue({ rows: [], cohorts: [], notes: ['v1 历史'] })
    await click(button('v1 纯量价对照'))
    await act(async () => {
      resolveSnapshot({ ...contextSnapshot(), message: '不应出现的旧 v2 快照' })
      resolveHistory({ rows: [], cohorts: [], notes: ['不应出现的旧 v2 历史'] })
      await Promise.resolve()
    })
    expect(document.body.textContent).toContain('v1 历史')
    expect(document.body.textContent).not.toContain('不应出现的旧 v2')
    expect(button('资金条件合格 · 1')).toBeDefined()
    expect(fetchFiveDayHistory).toHaveBeenLastCalledWith('v1')
    expect(startFiveDayScan).not.toHaveBeenCalled()
  })

  it.each(['RISK_OFF', 'UNKNOWN', 'CAUTION'])('keeps %s conditions out of the execution group', async (state) => {
    const next = contextSnapshot()
    next.context!.market.state = state
    next.context!.market.reasons = [state === 'UNKNOWN' ? '市场覆盖不足 80%' : '市场尚未满足执行环境']
    next.context!.assessments.forEach((item) => { item.decision = state === 'UNKNOWN' ? 'UNKNOWN' : 'WATCH' })
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue(next)
    await renderPage(root)
    expect(button('执行条件齐备 · 0')).toBeDefined()
    expect(document.body.textContent).toContain(state === 'UNKNOWN' ? '市场数据未知' : state === 'RISK_OFF' ? '市场风险偏高' : '市场谨慎观察')
    expect(document.body.textContent).toContain('本次没有执行条件齐备的观察股')
    await click(button('全部主线量价观察 · 3'))
    await click(candidateRow())
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('仅执行条件齐备的前瞻观察股')
  })

  it('treats absent context as unknown even with complete fund data', async () => {
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...snapshot(), ruleVersion: 'five-day-context-v2' })
    await renderPage(root)
    expect(button('执行条件齐备 · 0')).toBeDefined()
    expect(document.body.textContent).toContain('上下文数据未知')
  })

  it('displays announcement risk and separate evidence publication and collection times', async () => {
    const next = contextSnapshot()
    next.context!.assessments[0].decision = 'AVOID'
    next.context!.assessments[0].events = { status: 'TITLE_RISK', evidence: [evidence('关于股东减持计划的公告')], notes: ['标题出现风险线索，需阅读原文核实'] }
    next.context!.assessments[0].reasons = ['公告风险线索待核实']
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue(next)
    await renderPage(root)
    await click(button('全部主线量价观察 · 3'))
    await click(candidateRow())
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('公告风险线索待核实')
    expect(document.body.textContent).toContain('关于股东减持计划的公告')
    expect(document.body.textContent).toContain('公开时间 2026-09-07')
    expect(document.body.textContent).toContain('采集时间')
    expect(document.body.textContent).toContain('不证明公司主营受益')
    expect(document.querySelector('a[href="https://www.cninfo.com.cn/evidence.pdf"]')).toBeTruthy()
  })

  it('checks execution on request without changing the snapshot or creating a trade review', async () => {
    const ensureCase = vi.fn()
    useTradeFeedbackStore.setState({ ensureCase })
    vi.mocked(checkFiveDayExecution).mockResolvedValue({ symbol: '600001', status: 'BLOCKED', checkedAt: '2026-09-08T01:45:00Z', priceState: 'ABOVE_ENTRY_LIMIT', events: null, notes: ['现价高于入场上限'] })
    await renderPage(root)
    await click(candidateRow())
    expect(checkFiveDayExecution).not.toHaveBeenCalled()
    await click(button('检查入场条件'))
    expect(checkFiveDayExecution).toHaveBeenCalledWith('600001', { strategy: 'v2', includeGrowth: false, includeStar: false })
    expect(document.body.textContent).toContain('执行检查未通过')
    expect(document.body.textContent).toContain('现价高于入场上限')
    expect(document.body.textContent).toContain('2026-09-08 09:45:00')
    expect(document.body.textContent).toContain('本次检查不覆盖冻结观察')
    expect(document.body.textContent).toContain('标题未发现风险线索，不表示无风险')
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
    expect(ensureCase).not.toHaveBeenCalled()
  })

  it('passes v2 to attestation only for an eligible forward candidate', async () => {
    const ensureCase = vi.fn().mockResolvedValue({ caseId: 'v2-review' })
    useTradeFeedbackStore.setState({ ensureCase })
    vi.mocked(createFiveDayAttestation).mockResolvedValue({ symbol: '600001', recommendedAt: '2026-09-07T08:00:00Z', attestationToken: 'v2-token' })
    await renderPage(root)
    await click(candidateRow())
    await click(button('加入交易复盘'))
    expect(createFiveDayAttestation).toHaveBeenCalledWith('600001', { strategy: 'v2', includeGrowth: false, includeStar: false })
    expect(ensureCase).toHaveBeenCalledWith({ attestationToken: 'v2-token' })
  })

  it('does not claim a future entry date has passed its execution checks', async () => {
    const next = contextSnapshot()
    next.candidates[0].entryDate = '2026-09-09'
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue(next)
    vi.mocked(checkFiveDayExecution).mockResolvedValue({ symbol: '600001', status: 'PASSED', checkedAt: '2026-09-08T01:45:00Z', priceState: 'WITHIN_LIMITS', events: null, notes: [] })
    await renderPage(root)
    await click(candidateRow())
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('计划入场日尚未到来')
    await click(button('检查入场条件'))
    expect(document.body.textContent).not.toContain('当日执行条件已通过')
    expect(document.body.textContent).toContain('尚不能确认计划入场日的执行条件')
  })

  it('uses backend v2 cohorts and displays decisions and scenario boundaries', async () => {
    vi.mocked(fetchFiveDayHistory).mockResolvedValue({
      rows: [{ observationId: 'v2-sample', signalDate: '2026-09-07', capturedAt: '2026-09-07T08:00:00Z', forwardEligible: true, symbol: '600008', name: '历史观察', flowDataComplete: true, matchesFundFlow: true, decision: 'WATCH', outcome: null }],
      cohorts: [{ label: '后端主线量价对照组', samples: 1, completed: 0, unavailable: 1, meanFixedNetReturnPercent: null, profitablePercent: null, meanRiskNetReturnPercent: null }], notes: []
    })
    await renderPage(root)
    expect(document.body.textContent).toContain('后端主线量价对照组')
    expect(document.body.textContent).toContain('继续观察')
    expect(document.body.textContent).toContain('对照情景可能包含不可执行观察')
    await click(button('按本地日 K 重算'))
    expect(refreshFiveDayHistory).toHaveBeenLastCalledWith('v2')
  })

  it('prevents eligible but non-forward v2 candidates from joining trade review', async () => {
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...contextSnapshot(), forwardEligible: false })
    await renderPage(root)
    await click(candidateRow())
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('仅前瞻观察快照可以关联本策略成交记录')
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
  })

  it('separates undated policies and exposes unknown announcement coverage without unsafe links', async () => {
    const next = contextSnapshot()
    next.context!.policyStatus = 'UNKNOWN'
    next.context!.policies = [{ ...evidence('日期未知政策线索'), publishedAt: null, temporalStatus: 'DATE_UNKNOWN', url: 'javascript:alert(1)' }]
    next.context!.assessments[0].decision = 'UNKNOWN'
    next.context!.assessments[0].events = { status: 'UNKNOWN', evidence: [], notes: ['最近 30 条不足以覆盖最近 7 日公告窗口'] }
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue(next)
    await renderPage(root)
    expect(document.body.textContent).toContain('公开日期未知的线索')
    expect(document.body.textContent).toContain('来源状态未知')
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
    await click(button('全部主线量价观察 · 3'))
    await click(candidateRow())
    expect(document.body.textContent).toContain('最近 30 条不足以覆盖最近 7 日公告窗口')
    expect(button('加入交易复盘')).toBeUndefined()
  })

  it('shows independent execution failures and can retry without acquiring an attestation', async () => {
    vi.mocked(checkFiveDayExecution).mockRejectedValueOnce(new Error('报价源不可用')).mockResolvedValueOnce({ symbol: '600001', status: 'UNKNOWN', checkedAt: '2026-09-08T01:45:00Z', priceState: 'UNKNOWN', events: null, notes: ['当前报价时点未核实'] })
    await renderPage(root)
    await click(candidateRow())
    await click(button('检查入场条件'))
    expect(document.body.textContent).toContain('执行检查未完成：报价源不可用')
    await click(button('检查入场条件'))
    expect(document.body.textContent).toContain('执行检查数据未知')
    expect(document.body.textContent).toContain('当前报价时点未核实')
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
  })

  it('discards a late execution check when switching versions', async () => {
    let resolveCheck: (value: { symbol: string; status: 'BLOCKED'; checkedAt: string; priceState: string; events: null; notes: string[] }) => void = () => undefined
    vi.mocked(checkFiveDayExecution).mockReturnValue(new Promise((resolve) => { resolveCheck = resolve }))
    await renderPage(root)
    await click(candidateRow())
    await click(button('检查入场条件'))
    await click(button('v1 纯量价对照'))
    await act(async () => { resolveCheck({ symbol: '600001', status: 'BLOCKED', checkedAt: '2026-09-08T01:45:00Z', priceState: 'UNKNOWN', events: null, notes: ['旧版本迟到的检查结果'] }); await Promise.resolve() })
    expect(document.body.textContent).not.toContain('旧版本迟到的检查结果')
    expect(button('检查入场条件')).toBeUndefined()
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
  })

  it('reads the main-board snapshot and keeps the complete-flow control group overlapping the enhanced group', async () => {
    await renderV1(root)
    expect(fetchFiveDaySnapshot).toHaveBeenCalledWith({ strategy: 'v1', includeGrowth: false, includeStar: false })
    expect(startFiveDayScan).not.toHaveBeenCalled()
    expect(candidateSymbols()).toEqual(['600001'])
    expect(button('资金条件合格 · 1')).toBeDefined()
    await click(button('量价对照（资金完整） · 2'))
    expect(candidateSymbols()).toEqual(['600001', '600002'])
    await click(button('资金缺失 · 1'))
    expect(candidateSymbols()).toEqual(['600003'])
  })

  it('ignores an old scope response after the user selects the growth board', async () => {
    let resolveOld: (value: FiveDaySnapshot) => void = () => undefined
    await renderV1(root)
    vi.mocked(fetchFiveDaySnapshot).mockReturnValue(new Promise((resolve) => { resolveOld = resolve }))
    await click(button('读取状态'))
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...snapshot(), includeGrowth: true, candidates: [candidate('300001')] })
    await click(host.querySelector<HTMLInputElement>('input[type="checkbox"]'))
    expect(fetchFiveDaySnapshot).toHaveBeenLastCalledWith({ strategy: 'v1', includeGrowth: true, includeStar: false })
    await act(async () => { resolveOld(snapshot()); await Promise.resolve() })
    expect(candidateSymbols()).toEqual(['300001'])
    expect(startFiveDayScan).not.toHaveBeenCalled()
  })

  it('starts only on request and polls scanning until preparation requires an explicit retry', async () => {
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...snapshot(), status: 'IDLE', candidates: [] })
    vi.mocked(startFiveDayScan).mockResolvedValue({ ...snapshot(), status: 'SCANNING', candidates: [] })
    await renderV1(root)
    await click(button('开始扫描'))
    expect(startFiveDayScan).toHaveBeenCalledTimes(1)
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...snapshot(), status: 'PREPARING', candidates: [] })
    const readsBeforePoll = vi.mocked(fetchFiveDaySnapshot).mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(fetchFiveDaySnapshot).toHaveBeenCalledTimes(readsBeforePoll + 1)
    expect(button('重试扫描')).toBeDefined()
    await act(async () => { await vi.advanceTimersByTimeAsync(30000) })
    expect(fetchFiveDaySnapshot).toHaveBeenCalledTimes(readsBeforePoll + 1)
    expect(startFiveDayScan).toHaveBeenCalledTimes(1)
  })

  it('requires an explicit review action and leaves the actual buy price blank', async () => {
    const ensureCase = vi.fn().mockResolvedValue({ caseId: 'review-1' })
    useTradeFeedbackStore.setState({ ensureCase })
    vi.mocked(createFiveDayAttestation).mockResolvedValue({ symbol: '600001', recommendedAt: '2026-09-07T08:00:00Z', attestationToken: 'five-day-token' })
    await renderV1(root)
    await click(candidateRow())
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
    await click(button('加入交易复盘'))
    expect(createFiveDayAttestation).toHaveBeenCalledWith('600001', { strategy: 'v1', includeGrowth: false, includeStar: false })
    expect(ensureCase).toHaveBeenCalledWith({ attestationToken: 'five-day-token' })
    await click(document.querySelector('button[aria-label="买入 观察600001 600001"]'))
    expect(document.querySelector<HTMLInputElement>('input[aria-label="买入价格"]')?.value).toBe('')
    expect(document.body.textContent).toContain('实际成交')
  })

  it('keeps non-forward research snapshots out of the strategy review entry', async () => {
    vi.mocked(fetchFiveDaySnapshot).mockResolvedValue({ ...snapshot(), forwardEligible: false })
    await renderV1(root)
    await click(candidateRow())
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('仅前瞻观察快照可以关联本策略成交记录')
    expect(createFiveDayAttestation).not.toHaveBeenCalled()
  })

  it('keeps control-only candidates out of strategy review', async () => {
    await renderV1(root)
    await click(button('量价对照（资金完整） · 2'))
    await click(candidateRow(1))
    expect(button('加入交易复盘')).toBeUndefined()
    expect(document.body.textContent).toContain('仅资金条件合格的观察股')
  })

  it('recalculates history only after the user requests it', async () => {
    await renderV1(root)
    expect(refreshFiveDayHistory).not.toHaveBeenCalled()
    await click(button('按本地日 K 重算'))
    expect(refreshFiveDayHistory).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('本地日 K 重算完成')
  })
})

async function renderV1(root: Root) {
  await renderPage(root)
  await click(button('v1 纯量价对照'))
}

async function renderPage(root: Root) {
  await act(async () => {
    root.render(<StrictMode><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><FiveDayPage /></MemoryRouter></StrictMode>)
    await Promise.resolve()
  })
}

async function click(element: Element | null | undefined) {
  expect(element).toBeTruthy()
  await act(async () => {
    element?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await Promise.resolve()
  })
}

function button(label: string) {
  return [...document.querySelectorAll<HTMLButtonElement>('button')].find((item) => item.textContent === label)
}

function candidateRow(index = 0) {
  return document.querySelectorAll('table.min-w-\\[1040px\\] tbody tr')[index]
}

function candidateSymbols() {
  return [...document.querySelectorAll('table.min-w-\\[1040px\\] tbody tr')].map((row) => row.textContent?.match(/\d{6}/)?.[0])
}

function snapshot(): FiveDaySnapshot {
  return {
    status: 'READY', ruleVersion: 'five-day-observation-v1', signalDate: '2026-09-07', generatedAt: '2026-09-07T08:00:00Z',
    forwardEligible: true, includeGrowth: false, includeStar: false, message: '扫描完成', rules: null, coverage: null,
    candidates: [candidate('600001'), { ...candidate('600002'), matchesFundFlow: false }, { ...candidate('600003'), flowDataComplete: false, matchesFundFlow: false }], dataGaps: []
  }
}

function candidate(symbol: string): FiveDayCandidate {
  return {
    symbol, name: `观察${symbol}`, industry: '测试行业', board: 'MAIN', signalClose: 10, rangeHigh: 10.3, rangeLow: 9.8,
    rangePercent: 5.1, priceChangePercent: 1, centerChangePercent: 0.5, recentTurnover: 2, priorTurnover: 1, turnoverRatio: 2,
    averageAmount: 80000000, mainNetInflow: 4000000, mainNetInflowRatio: 1, positiveFlowDays: 3,
    flowDataComplete: true, matchesFundFlow: true, flowSourceUrl: null, stopPrice: 9.75, maxEntryPrice: 10.2,
    entryDate: '2026-09-08', plannedExitDate: '2026-09-14', evidence: ['日线数据齐全'], dataGaps: [], bars: []
  }
}

function evidence(title = '测试产业政策原文') {
  return { kind: 'POLICY', title, url: 'https://www.cninfo.com.cn/evidence.pdf', source: '公开来源', publishedAt: '2026-09-07', observedAt: '2026-09-07T08:00:00Z', interpretation: '仅为细分行业相关线索，不证明公司主营受益', temporalStatus: 'RECENT' }
}

function contextSnapshot(): FiveDaySnapshot {
  return {
    ...snapshot(), ruleVersion: 'five-day-context-v2', context: {
      assessedAt: '2026-09-07T08:00:00Z',
      market: { state: 'NORMAL', expectedCount: 1000, coveredCount: 900, coveragePercent: 90, advancingPercent: 60, medianChangePercent: 0.8, severeDeclinePercent: 1, reasons: ['覆盖与市场广度满足研究初值'] },
      sectors: [{ industry: '测试行业', state: 'STRONG', rank: 1, memberCount: 10, coveredCount: 9, coveragePercent: 90, return3Percent: 2, return5Percent: 3, excess3Percent: 1, excess5Percent: 2, advancing5Percent: 70, volumeExpansionPercent: 60, symbols: ['600001', '600002', '600003'], reasons: ['3/5 日超额持续为正'] }],
      assessments: ['600001', '600002', '600003'].map((symbol, index) => ({ symbol, industry: '测试行业', decision: index === 0 ? 'ELIGIBLE' : index === 1 ? 'WATCH' : 'UNKNOWN', turning: { confirmed: true, closeVsMa5Percent: 1, closePositionPercent: 80, reasons: ['已收盘日线确认转强'] }, events: { status: 'NO_TITLE_RISK', evidence: [], notes: [] }, policies: [evidence()], reasons: [index === 0 ? '各项研究条件齐备，仍非下单指令' : '条件尚未齐备'] })),
      policies: [evidence()], policyStatus: 'READY', notes: ['所有阈值为待验证研究初值']
    }
  }
}
