// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLatestShortTermScheduledSnapshot,
  fetchShortTermScanJob,
  startShortTermScanJob
} from '../api/client'
import type { ShortTermParams } from '../api/client'
import { toast } from '../components/ui/Toast'
import type {
  ShortTermReport,
  ShortTermScanJobStatus,
  ShortTermScheduledSnapshot,
  ShortTermSnapshotStatus
} from '../types'
import { resetShortTermScanStoreForTest, useShortTermScanStore } from './shortTermScanStore'

vi.mock('../api/client', () => ({
  fetchLatestShortTermScheduledSnapshot: vi.fn(),
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
    candidates: [],
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

function scheduledFinal(): ShortTermScheduledSnapshot {
  return {
    tradeDate: '2026-08-13',
    stage: 'FINAL',
    status: 'FINAL_READY',
    strategyVersion: 'short-term-right-side-v4-transparent-ranking',
    message: '计划任务完成',
    dataCutoffAt: '2026-08-13T14:49:20+08:00',
    startedAt: '2026-08-13T14:47:00+08:00',
    completedAt: '2026-08-13T14:49:30+08:00',
    blockedReasons: [],
    report: report(1)
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
    ['CACHE_PREVIEW', '休市期间使用缓存', [], '不是当前买点'],
    ['FINAL_PENDING', '尾盘结果仍待认证', [], '暂未形成可操作结果'],
    ['PRESELECT_READY', '当前仅完成预选', [], '暂未形成可操作结果']
  ] as Array<[ShortTermSnapshotStatus, string, string[], string]>) (
    'publishes a persistent warning for %s with the server explanation',
    async (resultStatus, message, blockedReasons, meaning) => {
      const allowsNoReport = resultStatus === 'DATA_BLOCKED'
        || resultStatus === 'FINAL_PENDING'
        || resultStatus === 'PRESELECT_READY'
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

  it('dismisses only an active manual progress Toast when a newer scheduled result takes control', async () => {
    useShortTermScanStore.setState({
      origin: 'MANUAL',
      loading: true,
      snapshot: {
        ...scheduledFinal(),
        stage: 'MANUAL',
        status: 'RUNNING',
        startedAt: '2026-08-13T10:00:00+08:00',
        completedAt: null,
        report: null
      },
      report: null
    })
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.dismiss).toHaveBeenCalledWith('short-term-manual-scan')
    expect(useShortTermScanStore.getState().origin).toBe('SCHEDULED')
  })

  it('does not dismiss a completed persistent manual warning during later scheduled refresh', async () => {
    useShortTermScanStore.setState({
      origin: 'MANUAL',
      loading: false,
      snapshot: {
        ...scheduledFinal(),
        stage: 'MANUAL',
        status: 'DATA_BLOCKED',
        startedAt: '2026-08-13T10:00:00+08:00',
        completedAt: '2026-08-13T10:01:00+08:00',
        report: null
      },
      report: null
    })
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.dismiss).not.toHaveBeenCalled()
  })

  it('keeps scheduled completion on the existing unkeyed Toast contract', async () => {
    vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(scheduledFinal())

    await useShortTermScanStore.getState().refreshScheduledSnapshot()

    expect(toast.success).toHaveBeenCalledWith('计划任务完成，已生成 1 个候选')
    expect(toast.success).not.toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ key: 'short-term-manual-scan' })
    )
  })
})
