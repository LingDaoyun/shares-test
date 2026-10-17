// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchDailyShortTermSnapshot, fetchShortTermResultCache, retryDailyShortTermSnapshot } from '../../api/dailyShortTerm'
import { fetchShortTermScanJob, startShortTermScanJob } from '../../api/client'
import type { ShortTermDailySnapshot } from '../../api/dailyShortTerm'
import type { ShortTermReport } from '../../types'
import { resetShortTermScanStoreForTest, useShortTermScanStore } from '../../store/shortTermScanStore'
import { DailyShortTermPanel } from './DailyShortTermPanel'

vi.mock('../../api/dailyShortTerm', () => ({ fetchDailyShortTermSnapshot: vi.fn(), fetchShortTermResultCache: vi.fn(), retryDailyShortTermSnapshot: vi.fn() }))
vi.mock('../../api/client', () => ({ fetchShortTermScanJob: vi.fn(), startShortTermScanJob: vi.fn() }))

function savedShape(tradeDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())): ShortTermDailySnapshot {
  return {
    tradeDate, scheduledAt: `${tradeDate}T06:00:00Z`, startedAt: `${tradeDate}T06:00:00Z`,
    finishedAt: `${tradeDate}T06:10:00Z`, status: 'PARTIAL', message: '形态完成，右侧数据不足', report: null,
    upperShadow: {
      tradeDate, assessedAt: `${tradeDate}T06:00:00Z`, status: 'READY', source: '保存行情', expectedCount: 5500,
      validQuoteCount: 5500, shapeCount: 1, historyMissingCount: 0, matchedCount: 1, omittedCount: 0,
      dataGaps: [], ruleDescription: '20日箱体规则', candidates: [{
        symbol: '600001', name: '形态股票', industry: '机械设备', label: '横盘长上影', latestPrice: 12,
        changePercent: 1, amount: 300000000, turnoverRate: 4, marketTimestamp: `${tradeDate}T06:00:00Z`,
        boxRangePercent: 10, boxNetChangePercent: 2, boxHigh: 12, boxLow: 11,
        upperShadowRatioPercent: 65, upperShadowPercent: 3, evidence: ['箱体整理'], counterEvidence: ['抛压待观察']
      }]
    }, dataGaps: ['右侧报告数据不足']
  }
}

describe('Saved 14:00 short-term observation', () => {
  const unmounts: Array<() => void> = []
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    resetShortTermScanStoreForTest()
    vi.resetAllMocks()
    const pending = { ...savedShape(), status: 'NOT_STARTED' as const, report: null, upperShadow: null, message: '今日任务尚未启动', dataGaps: [] }
    vi.mocked(fetchDailyShortTermSnapshot).mockResolvedValue(pending)
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(pending)
  })
  afterEach(() => {
    unmounts.splice(0).forEach(unmount => unmount()); vi.restoreAllMocks()
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  async function renderPanel() {
    const host = document.createElement('div'); document.body.append(host)
    const root = createRoot(host)
    unmounts.push(() => { act(() => root.unmount()); host.remove() })
    await act(async () => { root.render(<DailyShortTermPanel />) })
    return host
  }

  it('automatically publishes current-day shape results even when the right-side report is absent', async () => {
    const saved = savedShape()
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(saved)
    const host = await renderPanel()
    expect(useShortTermScanStore.getState().upperShadow).toEqual(saved.upperShadow)
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(host.querySelector('table')).toBeNull()
    expect(host.textContent).not.toContain('形态股票')
    expect(host.querySelector('.card')).toBeNull()
    expect(host.textContent).not.toContain('14:00 定时短线候选')
    expect(host.textContent).not.toContain('20日箱体规则')
    expect(host.textContent).not.toContain('横盘长上影观察')
  })

  it('only loads historical shapes explicitly and replaces a different-day right-side result atomically', async () => {
    const manualReport = { generatedAt: '2026-07-23T06:00:00Z', tradeCaptureTokens: { '600002': 'manual-token' } } as unknown as ShortTermReport
    useShortTermScanStore.setState({ report: manualReport })
    const saved = savedShape('2020-01-02')
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(saved)
    const host = await renderPanel()
    expect(useShortTermScanStore.getState().report).toBe(manualReport)
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    const load = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '加载已保存结果')!
    expect(load.disabled).toBe(false)
    await act(async () => { load.click() })
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().upperShadow).toEqual(saved.upperShadow)
  })

  it.each(['loading', 'error', 'report'] as const)('does not inject either cached branch while preserving manual %s ownership', async (owner) => {
    const manualReport = { tradeCaptureTokens: {} } as ShortTermReport
    useShortTermScanStore.setState({ loading: owner === 'loading', error: owner === 'error' ? '手动扫描失败' : '', report: owner === 'report' ? manualReport : null })
    const saved = savedShape()
    saved.report = { tradeCaptureTokens: { '600001': 'saved-token' } } as unknown as ShortTermReport
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(saved)
    const host = await renderPanel()
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    if (owner === 'report') expect(useShortTermScanStore.getState().report).toBe(manualReport)
    if (owner === 'error') expect(useShortTermScanStore.getState().error).toBe('手动扫描失败')
    if (owner === 'loading') expect(Array.from(host.querySelectorAll('button')).find(button => button.textContent === '加载已保存结果')?.disabled).toBe(true)
  })

  it('does not let the next saved-result poll replace a completed manual no-trade outcome', async () => {
    vi.useFakeTimers()
    const saved = savedShape()
    saved.report = { tradeCaptureTokens: { '600001': 'saved-token' } } as unknown as ShortTermReport
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(saved)
    await renderPanel()
    expect(useShortTermScanStore.getState().report?.tradeCaptureTokens).toEqual({})
    vi.mocked(startShortTermScanJob).mockResolvedValue({ jobId: 'manual-current' } as never)
    vi.mocked(fetchShortTermScanJob).mockResolvedValue({
      status: 'SUCCEEDED', resultStatus: 'NO_TRADE', report: null, message: '当前右侧无匹配', blockedReasons: []
    } as never)
    await act(async () => { await useShortTermScanStore.getState().runManualScan({} as never) })
    expect(useShortTermScanStore.getState().report).toBeNull()
    const manualError = useShortTermScanStore.getState().error
    expect(manualError).toContain('当前右侧无匹配')
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().error).toBe(manualError)
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
  })

  it('strips saved trade tokens when loading a historical report explicitly', async () => {
    const saved = savedShape('2020-01-02')
    saved.report = { tradeCaptureTokens: { '600001': 'historical-token' } } as unknown as ShortTermReport
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(saved)
    const host = await renderPanel()
    expect(useShortTermScanStore.getState().report).toBeNull()
    const load = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '加载已保存结果')!
    await act(async () => { load.click() })
    expect(useShortTermScanStore.getState().report?.tradeCaptureTokens).toEqual({})
    expect(useShortTermScanStore.getState().upperShadow).toEqual(saved.upperShadow)
  })

  it('shows the applied manual bundle time and gaps instead of the older daily task status', async () => {
    const cached = { ...savedShape(), runId: 'older-daily', message: '旧定时扫描', dataGaps: ['旧批次缺口'] }
    const manual = { ...cached, runId: 'new-manual', startedAt: `${cached.tradeDate}T07:00:00Z`,
      finishedAt: `${cached.tradeDate}T07:10:00Z`, message: '本次联合扫描完成', dataGaps: ['当前批次缺口'] }
    useShortTermScanStore.getState().applyScanResult(manual, { source: 'manual' })
    vi.mocked(fetchDailyShortTermSnapshot).mockResolvedValue(cached)
    vi.mocked(fetchShortTermResultCache).mockResolvedValue(cached)
    const host = await renderPanel()
    expect(host.querySelector('[role="status"]')?.textContent).toContain('本次联合扫描完成')
    expect(host.querySelector('[role="status"]')?.textContent).toContain('15:10:00')
    expect(host.textContent).not.toContain('旧定时扫描')
    expect(host.querySelector('details')?.textContent).toContain('当前批次缺口')
    expect(host.querySelector('details')?.textContent).not.toContain('旧批次缺口')
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('new-manual')
  })

  it('applies a completed empty cache and clears an older bundle without requiring a candidate', async () => {
    const previous = { ...savedShape(), runId: 'previous' }
    useShortTermScanStore.getState().applyScanResult(previous)
    vi.mocked(fetchShortTermResultCache).mockResolvedValue({ ...previous, runId: 'empty-run', startedAt: `${previous.tradeDate}T07:00:00Z`, status: 'READY',
      report: null, upperShadow: null, message: '联合扫描完成，无匹配', dataGaps: [] })
    const host = await renderPanel()
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('empty-run')
    expect(host.textContent).toContain('联合扫描完成，无匹配')
  })

  it('fetches the latest complete cache when loading explicitly instead of replaying the earlier polled bundle', async () => {
    const old = { ...savedShape(), runId: 'old-poll', message: '旧轮询结果' }
    vi.mocked(fetchShortTermResultCache).mockResolvedValueOnce(old)
    const host = await renderPanel()
    const latest = { ...old, runId: 'latest-manual', startedAt: `${old.tradeDate}T07:00:00Z`, message: '最新整包结果', upperShadow: null }
    vi.mocked(fetchShortTermResultCache).mockResolvedValueOnce(latest)
    const load = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '加载已保存结果')!
    await act(async () => { load.click() })
    expect(fetchShortTermResultCache).toHaveBeenCalledTimes(2)
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('latest-manual')
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(host.textContent).toContain('最新整包结果')
  })

  it('preserves the applied complete result when an explicit cache reload fails', async () => {
    const saved = { ...savedShape(), runId: 'preserved-result' }
    vi.mocked(fetchShortTermResultCache).mockResolvedValueOnce(saved)
    const host = await renderPanel()
    vi.mocked(fetchShortTermResultCache).mockRejectedValueOnce(new Error('缓存读取超时'))
    const load = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '加载已保存结果')!
    await act(async () => { load.click() })
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('preserved-result')
    expect(useShortTermScanStore.getState().upperShadow).toEqual(saved.upperShadow)
    expect(host.textContent).toContain('缓存读取超时；保留当前结果。')
  })
  it('loads saved results and labels an incomplete scan instead of implying zero candidates', async () => {
    vi.mocked(fetchShortTermResultCache).mockResolvedValue({
      ...savedShape(), message: '部分完成', report: null,
      upperShadow: null, dataGaps: ['日线历史不足']
    })
    const host = document.createElement('div'); document.body.append(host)
    const root = createRoot(host)
    unmounts.push(() => { act(() => root.unmount()); host.remove() })
    await act(async () => { root.render(<DailyShortTermPanel />) })
    expect(fetchShortTermResultCache).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('部分完成')
    const gaps = host.querySelector('details')
    expect(gaps).not.toBeNull()
    expect(gaps?.open).toBe(false)
    expect(gaps?.querySelector('summary')?.textContent).toContain('数据缺口 1 项')
    expect(gaps?.textContent).toContain('日线历史不足')
    expect(host.textContent).not.toContain('形态扫描尚无可展示结果')
    expect(host.textContent).not.toContain('主力试盘确认')
  })

  it('shows a compact read error without adding another card', async () => {
    vi.mocked(fetchShortTermResultCache).mockRejectedValue(new Error('读取超时'))
    const host = await renderPanel()
    expect(host.textContent).toContain('结果读取失败：读取超时')
    expect(host.querySelector('.card')).toBeNull()
  })

  it('does not erase a manual failure when the page remounts and a saved report exists', async () => {
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    useShortTermScanStore.setState({ report: null, error: '当前手动扫描数据不足', loading: false })
    vi.mocked(fetchShortTermResultCache).mockResolvedValue({
      tradeDate: date, scheduledAt: `${date}T06:00:00Z`, startedAt: `${date}T06:00:00Z`,
      finishedAt: `${date}T06:10:00Z`, status: 'READY', message: '已保存',
      report: { tradeCaptureTokens: {} } as ShortTermReport, upperShadow: null, dataGaps: []
    } satisfies ShortTermDailySnapshot)
    const host = document.createElement('div'); document.body.append(host)
    const root = createRoot(host)
    unmounts.push(() => { act(() => root.unmount()); host.remove() })
    await act(async () => { root.render(<DailyShortTermPanel />) })
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().error).toBe('当前手动扫描数据不足')
  })

  it('retries a failed current-day task only after an explicit click', async () => {
    const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    const failed: ShortTermDailySnapshot = {
      tradeDate: date, scheduledAt: `${date}T06:00:00Z`, startedAt: `${date}T06:00:00Z`,
      finishedAt: `${date}T06:01:00Z`, status: 'FAILED', message: '失败',
      report: null, upperShadow: null, dataGaps: ['来源断开']
    }
    vi.mocked(fetchDailyShortTermSnapshot).mockResolvedValue(failed)
    vi.mocked(retryDailyShortTermSnapshot).mockClear()
    vi.mocked(retryDailyShortTermSnapshot).mockResolvedValue({ ...failed, status: 'RUNNING', message: '重试中', finishedAt: null })
    const host = document.createElement('div'); document.body.append(host)
    const root = createRoot(host)
    unmounts.push(() => { act(() => root.unmount()); host.remove() })
    await act(async () => { root.render(<DailyShortTermPanel />) })
    expect(retryDailyShortTermSnapshot).not.toHaveBeenCalled()
    const retry = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '重试今日扫描')
    expect(retry).toBeDefined()
    await act(async () => { retry!.click() })
    expect(retryDailyShortTermSnapshot).toHaveBeenCalledTimes(1)
    expect(host.textContent).toContain('重试中')
    expect(host.textContent).not.toContain('重试今日扫描')
  })
})
