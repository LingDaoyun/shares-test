// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, CanceledError } from 'axios'
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
    vi.useRealTimers()
    vi.resetAllMocks()
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

  it('uses only right-side candidates and status when an old job also returns shape results', async () => {
    const upperShadow = { tradeDate: '2026-08-13', status: 'PARTIAL', candidates: [{ symbol: '600001' }, { symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(2), upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '扫描完成', dataGaps: [] }))
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().resultMetadata).toMatchObject({ runId: 'manual-1', source: 'manual', status: 'READY', dataGaps: [] })
    expect(toast.success).toHaveBeenCalledWith('扫描完成，已生成 2 个候选', successOptions)
  })

  it('keeps a blocked right-side report unavailable despite legacy shape candidates', async () => {
    const upperShadow = { tradeDate: '2026-08-13', candidates: [{ symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'DATA_BLOCKED', report: report(2), upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '右侧数据阻断', blockedReasons: ['分钟线不足'] }))
    expect(useShortTermScanStore.getState().report).toBeNull()
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().error).toContain('数据质量已阻断')
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('手动扫描未生成结果，数据质量已阻断'), persistentOptions)
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('does not treat legacy shape candidates as partial success when the right-side scan fails', async () => {
    const upperShadow = { tradeDate: '2026-08-13', status: 'READY', candidates: [{ symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'FAILED', report: null, upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '右侧异常', dataGaps: ['右侧接口超时'] }))
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(useShortTermScanStore.getState().resultMetadata).toBeNull()
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('手动扫描失败'), persistentOptions)
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('keeps a no-trade outcome when only legacy shape candidates exist', async () => {
    const upperShadow = { tradeDate: '2026-08-13', status: 'READY', candidates: [{ symbol: '600003' }] } as UpperShadowScan
    await runWith(job({ status: 'SUCCEEDED', resultStatus: 'NO_TRADE', report: report(0), upperShadow,
      finishedAt: '2026-08-13T14:49:30+08:00', message: '没有右侧候选' }))
    expect(useShortTermScanStore.getState().upperShadow).toBeNull()
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('未生成合格候选'), persistentOptions)
    expect(toast.success).not.toHaveBeenCalled()
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

  it.each(['request', 'job', 'data-blocked'] as const)('accepts a newer cache after a manual %s failure without restoring older results', async (failure) => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-13T06:48:00Z'))
    if (failure === 'request') {
      vi.mocked(startShortTermScanJob).mockRejectedValue(new Error('创建任务失败'))
      await useShortTermScanStore.getState().runManualScan({})
    } else {
      await runWith(job({ status: failure === 'job' ? 'FAILED' : 'SUCCEEDED',
        resultStatus: failure === 'job' ? 'FAILED' : 'DATA_BLOCKED',
        message: '手动扫描数据不足', finishedAt: '2026-08-13T06:49:00Z', report: null }))
    }
    expect(useShortTermScanStore.getState().error).not.toBe('')
    const cached: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: null,
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T06:10:00Z', runId: 'older-cache',
      status: 'READY', message: '后台完成结果', report: { ...report(1), tradeCaptureTokens: { '600001': 'cached-token' } },
      upperShadow: null, dataGaps: [] }
    const apply = useShortTermScanStore.getState().applyScanResult
    expect(apply(cached)).toBe(false)
    expect(apply({ ...cached, runId: 'unknown-time', startedAt: null })).toBe(false)
    const newer = { ...cached, runId: 'newer-periodic', startedAt: '2026-08-13T08:00:00Z', finishedAt: '2026-08-13T08:10:00Z' }
    useShortTermScanStore.setState({ loading: true })
    expect(apply(newer)).toBe(false)
    useShortTermScanStore.setState({ loading: false })
    expect(apply(newer)).toBe(true)
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('newer-periodic')
    expect(useShortTermScanStore.getState().report?.tradeCaptureTokens).toEqual({})
    expect(useShortTermScanStore.getState().error).toBe('')
    expect(startShortTermScanJob).toHaveBeenCalledTimes(1)
    expect(toast.dismiss).toHaveBeenCalledExactlyOnceWith('short-term-manual-scan')
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

  it.each([
    new AxiosError('网络暂时中断', 'ERR_NETWORK'),
    new AxiosError('请求超时', 'ECONNABORTED'),
    new AxiosError('请求超时', 'ETIMEDOUT'),
    Object.assign(new AxiosError('网关暂时不可用'), { response: { status: 502 } }),
    Object.assign(new AxiosError('服务暂时不可用'), { response: { status: 503 } }),
    Object.assign(new AxiosError('网关超时'), { response: { status: 504 } })
  ])('recovers the same job after a temporary polling failure: $message', async (error) => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob)
      .mockRejectedValueOnce(error)
      .mockResolvedValueOnce(job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(2) }))

    await useShortTermScanStore.getState().runManualScan({})

    expect(useShortTermScanStore.getState()).toMatchObject({ loading: true, activeJobId: 'manual-1', error: '' })
    expect(useShortTermScanStore.getState().scanMessage).toContain('重试')
    expect(toast.error).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1500)

    expect(startShortTermScanJob).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchShortTermScanJob).mock.calls).toEqual([['manual-1'], ['manual-1']])
    expect(useShortTermScanStore.getState()).toMatchObject({ loading: false, activeJobId: '', error: '' })
    expect(useShortTermScanStore.getState().report?.candidateCount).toBe(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('recovers a gateway outage lasting longer than a few polling intervals', async () => {
    vi.useFakeTimers()
    const startedAt = Date.now()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockImplementation(async () => {
      if (Date.now() - startedAt < 15000) {
        throw Object.assign(new AxiosError('API正在恢复'), { response: { status: 502 } })
      }
      return job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(1) })
    })

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(15000)

    expect(useShortTermScanStore.getState().report?.candidateCount).toBe(1)
    expect(startShortTermScanJob).toHaveBeenCalledTimes(1)
    expect(toast.error).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('replaces the temporary failure notification when the same job is running again', async () => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob)
      .mockRejectedValueOnce(new AxiosError('网络中断', 'ERR_NETWORK'))
      .mockResolvedValueOnce(job({ message: '日线复核中' }))

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(1500)

    expect(useShortTermScanStore.getState()).toMatchObject({ loading: true, activeJobId: 'manual-1', scanMessage: '日线复核中' })
    expect(toast.loading).toHaveBeenLastCalledWith('日线复核中', persistentOptions)
  })

  it('stops retrying after a minute of continuous polling failures', async () => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockRejectedValue(new AxiosError('网络中断', 'ERR_NETWORK'))

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(59000)
    expect(useShortTermScanStore.getState()).toMatchObject({ loading: true, activeJobId: 'manual-1' })
    await vi.advanceTimersByTimeAsync(1000)

    expect(useShortTermScanStore.getState()).toMatchObject({ loading: false, activeJobId: '', report: null })
    expect(useShortTermScanStore.getState().error).toContain('尚未确认扫描结果')
    expect(toast.error).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
    const callsAtFailure = vi.mocked(fetchShortTermScanJob).mock.calls.length
    await vi.advanceTimersByTimeAsync(60000)
    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(callsAtFailure)
  })

  it('resets the recovery window after a successful status read', async () => {
    vi.useFakeTimers()
    const startedAt = Date.now()
    const temporary = new AxiosError('网络中断', 'ERR_NETWORK')
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockImplementation(async () => {
      const elapsed = Date.now() - startedAt
      if (elapsed === 45000) return job()
      if (elapsed < 90000) throw temporary
      return job({ status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(1) })
    })

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(90000)

    expect(useShortTermScanStore.getState().report?.candidateCount).toBe(1)
    expect(toast.error).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    Object.assign(new AxiosError('短线扫描任务不存在或已过期'), { response: { status: 400 } }),
    Object.assign(new AxiosError('任务不存在'), { response: { status: 404 } }),
    new CanceledError('请求已取消')
  ])('does not retry a missing task or cancellation: $message', async (error) => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob).mockRejectedValue(error)

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(6000)

    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(1)
    expect(useShortTermScanStore.getState()).toMatchObject({ loading: false, activeJobId: '', error: error.message })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('stops retrying when the recovered task explicitly reports failure', async () => {
    vi.useFakeTimers()
    vi.mocked(startShortTermScanJob).mockResolvedValue(job())
    vi.mocked(fetchShortTermScanJob)
      .mockRejectedValueOnce(new AxiosError('网络中断', 'ERR_NETWORK'))
      .mockResolvedValueOnce(job({ status: 'FAILED', resultStatus: 'FAILED', message: '行情源不可用' }))

    await useShortTermScanStore.getState().runManualScan({})
    await vi.advanceTimersByTimeAsync(6000)

    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(2)
    expect(useShortTermScanStore.getState()).toMatchObject({ loading: false, activeJobId: '', error: '行情源不可用' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not retry a late failure from a replaced manual run', async () => {
    vi.useFakeTimers()
    let rejectOld!: (error: unknown) => void
    vi.mocked(startShortTermScanJob).mockResolvedValueOnce(job()).mockResolvedValueOnce(job({ jobId: 'manual-2' }))
    vi.mocked(fetchShortTermScanJob)
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectOld = reject }))
      .mockResolvedValueOnce(job({ jobId: 'manual-2', status: 'SUCCEEDED', resultStatus: 'FINAL_READY', report: report(2) }))

    const oldRun = useShortTermScanStore.getState().runManualScan({})
    await Promise.resolve()
    await useShortTermScanStore.getState().runManualScan({})
    rejectOld(new AxiosError('旧请求断开', 'ERR_NETWORK'))
    await oldRun
    await vi.advanceTimersByTimeAsync(6000)

    expect(fetchShortTermScanJob).toHaveBeenCalledTimes(2)
    expect(useShortTermScanStore.getState().resultMetadata?.runId).toBe('manual-2')
    expect(useShortTermScanStore.getState().report?.candidateCount).toBe(2)
    expect(toast.error).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('dismisses the manual failure notification only after accepting a saved result', async () => {
    await runWith(job({ status: 'FAILED', resultStatus: 'FAILED', message: '行情源不可用' }))
    const cached: ShortTermDailySnapshot = { tradeDate: '2026-08-13', scheduledAt: null,
      startedAt: '2026-08-13T06:00:00Z', finishedAt: '2026-08-13T06:10:00Z', runId: 'saved-run',
      status: 'READY', message: '历史完成结果', report: report(1), upperShadow: null, dataGaps: [] }
    const apply = useShortTermScanStore.getState().applyScanResult

    expect(apply(cached)).toBe(false)
    expect(apply({ ...cached, status: 'FAILED' }, { force: true })).toBe(false)
    useShortTermScanStore.setState({ loading: true })
    expect(apply(cached, { force: true })).toBe(false)
    expect(toast.dismiss).not.toHaveBeenCalled()

    useShortTermScanStore.setState({ loading: false })
    expect(apply(cached, { force: true })).toBe(true)
    expect(useShortTermScanStore.getState().error).toBe('')
    expect(toast.dismiss).toHaveBeenCalledExactlyOnceWith('short-term-manual-scan')
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
