// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import type { ShortTermParams } from '../api/client'
import type { ShortTermDailySnapshot, UpperShadowScan } from '../api/dailyShortTerm'
import { toast } from '../components/ui/Toast'
import type {
  ShortTermReport,
  ShortTermScanJobStatus,
  ShortTermScanResultStatus
} from '../types'
import { resetShortTermScanStoreForTest, useShortTermScanStore } from './shortTermScanStore'

vi.mock('../api/client', () => ({
  fetchShortTermScanJob: vi.fn(),
  startShortTermScanJob: vi.fn()
}))

vi.mock('../components/ui/Toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
    loading: vi.fn(),
    dismiss: vi.fn()
  }
}))

const persistentOptions = {
  key: 'short-term-manual-scan',
  persistent: true
}

const successOptions = {
  key: 'short-term-manual-scan',
  durationMs: 5000
}

function report(candidateCount: number): ShortTermReport {
  return {
    candidateCount,
    candidates: Array.from({ length: candidateCount }, (_, index) => ({ symbol: String(600001 + index) })),
    dataCutoffAt: '2026-08-13T14:49:20+08:00'
  } as unknown as ShortTermReport
}

function job(overrides: Partial<ShortTermScanJobStatus> = {}): ShortTermScanJobStatus {
  return {
    jobId: 'manual-1',
    status: 'RUNNING',
    tradeDate: '2026-08-13',
    resultStatus: 'RUNNING',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    blockedReasons: [],
    createdAt: '2026-08-13T14:48:00+08:00',
    startedAt: '2026-08-13T14:48:01+08:00',
    finishedAt: null,
    message: '扫描任务已创建',
    report: null,
    ...overrides
  }
}

async function runWith(completed: ShortTermScanJobStatus) {
  vi.mocked(startShortTermScanJob).mockResolvedValue(job())
  vi.mocked(fetchShortTermScanJob).mockResolvedValue(completed)
  await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)
}

describe('shortTermScanStore manual notifications', () => {
  beforeEach(() => {
    resetShortTermScanStoreForTest()
  })

  afterEach(() => {
    resetShortTermScanStoreForTest()
    vi.clearAllMocks()
  })

  it('clears both sides of the old result when a manual scan fails', async () => {
    const saved = { tradeDate: '2026-08-13', candidates: [] } as unknown as UpperShadowScan
    useShortTermScanStore.setState({ upperShadow: saved })
    vi.mocked(startShortTermScanJob).mockRejectedValue(new Error('手动行情不可用'))
    const pending = useShortTermScanStore.getState().runManualScan({} as ShortTermParams)
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().report).toBeNull()
    await pending
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().error).toBe('手动行情不可用')
  })

  it('applies both branches and their metadata from one manual job with a deduplicated count', async () => {
    const upperShadow = { tradeDate: '2026-08-13', candidates: [{ symbol: '600001' }, { symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(2), upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '联合扫描完成', dataGaps: ['形态数据缺口'] }))
    expect(useShortTermScanStore.getState().upperShadow).toBe(upperShadow)
    expect(useShortTermScanStore.getState().resultMetadata).toMatchObject({ runId: 'manual-1', source: 'manual', dataGaps: ['形态数据缺口'] })
    expect(toast.success).toHaveBeenCalledWith('联合扫描完成，已生成 3 个候选', successOptions)
  })

  it('hides a blocked right-side report but displays this job shapes without reporting zero candidates', async () => {
    const upperShadow = { tradeDate: '2026-08-13', candidates: [{ symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'DATA_BLOCKED', report: report(2), upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '右侧数据阻断', blockedReasons: ['分钟线不足'] }))
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().upperShadow).toBe(upperShadow)
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('已生成 1 个候选'), persistentOptions)
    expect(toast.warning).not.toHaveBeenCalledWith(expect.stringContaining('未生成合格候选'), expect.anything())
  })

  it('reports partial success when the right-side branch fails but this run has shape candidates', async () => {
    const upperShadow = { tradeDate: '2026-08-13', status: 'READY', candidates: [{ symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'FAILED', report: null, upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '右侧异常，形态已完成', dataGaps: ['右侧接口超时'] }))
    expect(useShortTermScanStore.getState().upperShadow).toBe(upperShadow)
    expect(useShortTermScanStore.getState().resultMetadata?.status).toBe('PARTIAL')
    expect(toast.error).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('已生成 1 个候选，右侧未完成'), persistentOptions)
  })

  it('keeps ordinary no-trade explanations out of data-gap metadata', async () => {
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'NO_TRADE', report: report(0),
      blockedReasons: ['没有通过风险过滤的标的'], dataGaps: [] }))
    expect(useShortTermScanStore.getState().resultMetadata?.dataGaps).toEqual([])
  })

  it('clears an absent branch and both branches for a valid empty cache in one store update', () => {
    const base: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: '2026-08-13T06:00:00Z',
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T06:10:00Z', runId: 'cache-1',
      status: 'READY', message: '完成', report: report(1), upperShadow: null, dataGaps: [] }
    useShortTermScanStore.setState({ upperShadow: { tradeDate: base.tradeDate, candidates: [] } as unknown as UpperShadowScan })
    const updates: unknown[] = []
    const unsubscribe = useShortTermScanStore.subscribe(value => updates.push([value.report, value.upperShadow, value.resultMetadata?.runId]))
    useShortTermScanStore.getState().applyScanResult(base)
    expect(updates).toHaveLength(1)
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    useShortTermScanStore.getState().applyScanResult({ ...base, runId: 'cache-2', startedAt: '2026-08-13T07:00:00Z', report: null })
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('cache-2')
    unsubscribe()
  })

  it('does not reapply the same cached run or another run with the same start time', () => {
    const base: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: '2026-08-13T06:00:00Z',
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T06:10:00Z', runId: 'cache-original',
      status: 'READY', message: '完成', report: report(1), upperShadow: null, dataGaps: [] }
    const apply = useShortTermScanStore.getState().applyScanResult
    expect(apply(base)).toBe(true)
    expect(apply({ ...base, report: report(2) })).toBe(false)
    expect(apply({ ...base, runId: 'cache-other', report: report(3) })).toBe(false)
    expect(useShortTermScanStore.getState().report?.candidateCount).toBe(1)
  })

  it('preserves the applied bundle when the result cache is missing, failed or still running', () => {
    const base: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: null,
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T06:10:00Z', runId: 'valid-cache',
      status: 'READY', message: '完成', report: report(1), upperShadow: null, dataGaps: [] }
    const apply = useShortTermScanStore.getState().applyScanResult
    apply(base)
    const previous = useShortTermScanStore.getState().report
    for (const status of ['NOT_STARTED', 'FAILED', 'RUNNING'] as const) {
      expect(apply({ ...base, runId: 'unavailable-cache', status, report: null }, { force: true })).toBe(false)
      expect(useShortTermScanStore.getState().report).toBe(previous)
      expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('valid-cache')
    }
  })

  it('protects a manual run from same-run and older caches but permits a later whole result', async () => {
    const manualReport = { ...report(1), tradeCaptureTokens: { '600001': 'fresh-token' } }
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: manualReport, finishedAt: '2026-08-13T14:49:30+08:00' }))
    const cached: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: '2026-08-13T06:00:00Z',
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T08:00:00Z', runId: 'older-start',
      status: 'READY', message: '旧缓存晚完成', report: report(2), upperShadow: null, dataGaps: [] }
    expect(useShortTermScanStore.getState().applyScanResult(cached)).toBe(false)
    expect(useShortTermScanStore.getState().applyScanResult({ ...cached, runId: 'manual-1', startedAt: '2026-08-13T07:00:00Z' })).toBe(false)
    expect(useShortTermScanStore.getState().report).toBe(manualReport)
    expect(useShortTermScanStore.getState().applyScanResult({ ...cached, runId: 'newer-run', startedAt: '2026-08-13T07:00:00Z' })).toBe(true)
    expect(useShortTermScanStore.getState().report?.tradeCaptureTokens).toEqual({})
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('newer-run')
  })

  it('shows persistent progress then a five-second success with candidate count', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '手动扫描完成',
      report: report(2)
    }))

    expect(toast.loading).toHaveBeenCalledWith(
      '短线扫描已开始，正在获取实时行情…',
      persistentOptions
    )
    expect(toast.success).toHaveBeenCalledWith(
      '手动扫描完成，已生成 2 个候选',
      successOptions
    )
  })

  it('turns a defensive zero-candidate FINAL_READY result into a persistent warning', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '扫描完成但没有候选',
      report: report(0)
    }))

    expect(toast.success).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringContaining('手动扫描完成，未生成合格候选'),
      persistentOptions
    )
    expect(toast.warning).toHaveBeenCalledWith(
      expect.stringContaining('扫描完成但没有候选'),
      persistentOptions
    )
  })

  it.each([
    ['NO_TRADE', '当前没有满足全部条件的标的', [], '未生成合格候选'],
    ['DATA_BLOCKED', '行情覆盖不足', ['覆盖率 82%', '分钟线缺失'], '数据质量已阻断'],
    ['CACHE_PREVIEW', '休市期间使用缓存', [], '不是当前买点']
  ] as Array<[ShortTermScanResultStatus, string, string[], string]>) (
    'publishes a persistent warning for %s with the server explanation',
    async (resultStatus, message, blockedReasons, meaning) => {
      const allowsNoReport = resultStatus === 'DATA_BLOCKED'
      await runWith(job({
        status: 'SUCCEEDED',
        resultStatus,
        finishedAt: '2026-08-13T14:49:30+08:00',
        message,
        blockedReasons,
        report: allowsNoReport ? null : report(0)
      }))

      expect(toast.warning).toHaveBeenCalledWith(
        expect.stringContaining(meaning),
        persistentOptions
      )
      expect(toast.warning).toHaveBeenCalledWith(
        expect.stringContaining(message),
        persistentOptions
      )
      for (const reason of blockedReasons) {
        expect(toast.warning).toHaveBeenCalledWith(
          expect.stringContaining(reason),
          persistentOptions
        )
      }
      expect(toast.error).not.toHaveBeenCalled()
    }
  )

  it('publishes a persistent error when the job reports FAILED', async () => {
    await runWith(job({
      status: 'FAILED',
      resultStatus: 'FAILED',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '行情服务超时',
      blockedReasons: ['新浪行情不可用']
    }))

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('行情服务超时'),
      persistentOptions
    )
    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('新浪行情不可用'),
      persistentOptions
    )
  })

  it('publishes a persistent error when job creation fails', async () => {
    vi.mocked(startShortTermScanJob).mockRejectedValue(new Error('创建任务网络中断'))

    await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)

    expect(toast.error).toHaveBeenCalledWith(
      '手动扫描失败：创建任务网络中断',
      persistentOptions
    )
  })

  it('publishes a persistent error when polling fails', async () => {
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockRejectedValue(new Error('轮询连接断开'))

    await useShortTermScanStore.getState().runManualScan({} as ShortTermParams)

    expect(toast.error).toHaveBeenCalledWith(
      '手动扫描失败：轮询连接断开',
      persistentOptions
    )
  })

  it('treats FINAL_READY without a report as a persistent protocol error', async () => {
    await runWith(job({
      status: 'SUCCEEDED',
      resultStatus: 'FINAL_READY',
      finishedAt: '2026-08-13T14:49:30+08:00',
      message: '服务端标记完成',
      blockedReasons: ['报告字段为空'],
      report: null
    }))

    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('没有返回报告'),
      persistentOptions
    )
    expect(toast.error).toHaveBeenCalledWith(
      expect.stringContaining('报告字段为空'),
      persistentOptions
    )
  })

})
