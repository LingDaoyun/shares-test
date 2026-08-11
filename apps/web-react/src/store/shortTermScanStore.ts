import { create } from 'zustand'
import { fetchShortTermScanJob, startShortTermScanJob } from '../api/client'
import type { ShortTermParams } from '../api/client'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage } from '../lib/format'
import type { ShortTermReport, ShortTermScanJobStatus, ShortTermScheduledSnapshot } from '../types'

export type ShortTermScanOrigin = 'SCHEDULED' | 'MANUAL'

interface ShortTermScanState {
  origin: ShortTermScanOrigin
  snapshot: ShortTermScheduledSnapshot | null
  report: ShortTermReport | null
  loading: boolean
  error: string
  scanMessage: string
  activeJobId: string
  runManualScan: (params: ShortTermParams) => Promise<void>
}

let manualRunGeneration = 0
let pollTimer: number | undefined
let completionToastKey = ''

function initialState() {
  return {
    origin: 'MANUAL' as ShortTermScanOrigin,
    snapshot: null,
    report: null,
    loading: false,
    error: '',
    scanMessage: '',
    activeJobId: ''
  }
}

function clearPollTimer() {
  if (pollTimer !== undefined) {
    window.clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

export const useShortTermScanStore = create<ShortTermScanState>((set) => ({
  ...initialState(),

  runManualScan: async (params) => {
    const generation = manualRunGeneration + 1
    manualRunGeneration = generation
    clearPollTimer()

    const ownsRun = () => manualRunGeneration === generation
    set((current) => ({
      origin: 'MANUAL',
      loading: true,
      error: '',
      report: null,
      scanMessage: '提交实时扫描任务',
      activeJobId: '',
      snapshot: {
        tradeDate: current.snapshot?.tradeDate ?? currentShanghaiDate(),
        stage: 'MANUAL',
        status: 'RUNNING',
        strategyVersion: current.snapshot?.strategyVersion ?? '',
        message: '提交实时扫描任务',
        dataCutoffAt: null,
        completedAt: null,
        blockedReasons: [],
        report: null
      }
    }))

    try {
      const started = await startShortTermScanJob(params)
      if (!ownsRun()) return
      const runningMessage = started.message || '短线右侧实时扫描中'
      set((current) => ({
        activeJobId: started.jobId,
        scanMessage: runningMessage,
        snapshot: current.snapshot ? {
          ...current.snapshot,
          tradeDate: started.tradeDate,
          status: started.resultStatus,
          strategyVersion: started.strategyVersion,
          blockedReasons: started.blockedReasons,
          message: runningMessage
        } : current.snapshot
      }))

      const poll = async () => {
        try {
          const job = await fetchShortTermScanJob(started.jobId)
          if (!ownsRun()) return
          const runningJobMessage = job.message || '短线右侧实时扫描中'
          set({ scanMessage: runningJobMessage })
          if (job.status === 'SUCCEEDED') {
            if (job.report) {
              const manualSnapshot = snapshotFromManualJob(job)
              set({
                snapshot: manualSnapshot,
                report: visibleSnapshotReport(manualSnapshot),
                error: '',
                loading: false
              })
              notifySnapshotCompleted('MANUAL', manualSnapshot)
            } else {
              const message = '短线扫描任务已完成，但没有返回报告。'
              set((current) => ({
                snapshot: current.snapshot ? {
                  ...current.snapshot,
                  status: 'FAILED',
                  blockedReasons: job.blockedReasons,
                  message,
                  completedAt: job.finishedAt
                } : current.snapshot,
                error: message,
                loading: false
              }))
            }
            return
          }
          if (job.status === 'FAILED') {
            const message = job.message || '短线右侧实时扫描失败'
            set((current) => ({
              snapshot: current.snapshot ? {
                ...current.snapshot,
                status: 'FAILED',
                strategyVersion: job.strategyVersion,
                blockedReasons: job.blockedReasons,
                message,
                completedAt: job.finishedAt
              } : current.snapshot,
              error: message,
              loading: false
            }))
            return
          }
          set((current) => ({
            snapshot: current.snapshot ? {
              ...current.snapshot,
              status: job.resultStatus,
              strategyVersion: job.strategyVersion,
              blockedReasons: job.blockedReasons,
              message: runningJobMessage
            } : current.snapshot
          }))
          pollTimer = window.setTimeout(() => void poll(), 1500)
        } catch (e) {
          if (ownsRun()) {
            const message = extractErrorMessage(e)
            set((current) => ({
              snapshot: current.snapshot ? {
                ...current.snapshot,
                status: 'FAILED',
                message,
                completedAt: new Date().toISOString()
              } : current.snapshot,
              error: message,
              loading: false
            }))
          }
        }
      }

      await poll()
    } catch (e) {
      if (ownsRun()) {
        const message = extractErrorMessage(e)
        set((current) => ({
          snapshot: current.snapshot ? {
            ...current.snapshot,
            status: 'FAILED',
            message,
            completedAt: new Date().toISOString()
          } : current.snapshot,
          error: message,
          loading: false
        }))
      }
    }
  }
}))

export function resetShortTermScanStoreForTest() {
  manualRunGeneration += 1
  clearPollTimer()
  completionToastKey = ''
  useShortTermScanStore.setState(initialState())
}

function visibleSnapshotReport(snapshot: ShortTermScheduledSnapshot) {
  if (snapshot.status === 'DATA_BLOCKED' || snapshot.status === 'FAILED' || snapshot.status === 'RUNNING') {
    return null
  }
  return snapshot.report
}

function snapshotFromManualJob(job: ShortTermScanJobStatus): ShortTermScheduledSnapshot {
  return {
    tradeDate: job.tradeDate,
    stage: 'MANUAL',
    status: job.resultStatus,
    strategyVersion: job.strategyVersion,
    message: job.message,
    dataCutoffAt: job.report?.dataCutoffAt ?? null,
    completedAt: job.finishedAt,
    blockedReasons: job.blockedReasons,
    report: job.report
  }
}

function notifySnapshotCompleted(origin: ShortTermScanOrigin, snapshot: ShortTermScheduledSnapshot) {
  if (snapshot.status !== 'FINAL_READY') return
  const key = [
    origin,
    snapshot.tradeDate,
    snapshot.completedAt ?? '',
    snapshot.report?.candidateCount ?? -1
  ].join('|')
  if (completionToastKey === key) return
  completionToastKey = key
  toast.success(snapshotCompletionToastMessage(snapshot))
}

function snapshotCompletionToastMessage(snapshot: ShortTermScheduledSnapshot) {
  const count = snapshot.report?.candidateCount
  if (count === null || count === undefined) return snapshot.message
  if (count === 0) return `${snapshot.message}，暂无候选`
  return `${snapshot.message}，已生成 ${count} 个候选`
}

function currentShanghaiDate() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
}
