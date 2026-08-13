import { create } from 'zustand'
import { fetchLatestShortTermScheduledSnapshot, fetchShortTermScanJob, startShortTermScanJob } from '../api/client'
import type { ShortTermParams } from '../api/client'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage } from '../lib/format'
import type { ShortTermReport, ShortTermScanJobStatus, ShortTermScheduledSnapshot } from '../types'

export type ShortTermScanOrigin = 'SCHEDULED' | 'MANUAL'

interface ShortTermScanState {
  origin: ShortTermScanOrigin
  scheduledSnapshot: ShortTermScheduledSnapshot | null
  snapshot: ShortTermScheduledSnapshot | null
  report: ShortTermReport | null
  loading: boolean
  error: string
  scanMessage: string
  activeJobId: string
  refreshScheduledSnapshot: () => Promise<void>
  runManualScan: (params: ShortTermParams) => Promise<void>
}

let manualRunGeneration = 0
let pollTimer: number | undefined
let completionToastKey = ''
let scheduledLoadGeneration = 0
let scheduledSnapshotRequest: Promise<ShortTermScheduledSnapshot> | null = null
const MANUAL_SCAN_TOAST_KEY = 'short-term-manual-scan'
const MANUAL_SCAN_PERSISTENT_TOAST = {
  key: MANUAL_SCAN_TOAST_KEY,
  persistent: true
} as const
const MANUAL_SCAN_SUCCESS_TOAST = {
  key: MANUAL_SCAN_TOAST_KEY,
  durationMs: 5000
} as const

function initialState() {
  return {
    origin: 'SCHEDULED' as ShortTermScanOrigin,
    scheduledSnapshot: null,
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

export const useShortTermScanStore = create<ShortTermScanState>((set, get) => ({
  ...initialState(),

  refreshScheduledSnapshot: async () => {
    const generation = scheduledLoadGeneration
    const request = scheduledSnapshotRequest ?? fetchLatestShortTermScheduledSnapshot()
    scheduledSnapshotRequest = request
    try {
      const scheduledSnapshot = await request
      if (generation !== scheduledLoadGeneration) return
      const current = get()
      const takesControl = scheduledSnapshotTakesControl(current, scheduledSnapshot)
      if (!takesControl) {
        set({ scheduledSnapshot })
        return
      }
      if (current.origin === 'MANUAL' && current.loading) {
        toast.dismiss(MANUAL_SCAN_TOAST_KEY)
      }
      manualRunGeneration += 1
      clearPollTimer()
      set({
        origin: 'SCHEDULED',
        scheduledSnapshot,
        snapshot: scheduledSnapshot,
        report: visibleSnapshotReport(scheduledSnapshot),
        loading: false,
        error: '',
        scanMessage: scheduledSnapshot.message,
        activeJobId: ''
      })
      notifySnapshotCompleted('SCHEDULED', scheduledSnapshot)
    } catch {
      // The lightweight background refresh must not replace a usable manual result.
    } finally {
      if (scheduledSnapshotRequest === request) {
        scheduledSnapshotRequest = null
      }
    }
  },

  runManualScan: async (params) => {
    const generation = manualRunGeneration + 1
    manualRunGeneration = generation
    clearPollTimer()
    toast.info(
      '短线扫描已开始，正在获取实时行情…',
      MANUAL_SCAN_PERSISTENT_TOAST
    )

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
        startedAt: new Date().toISOString(),
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
          message: runningMessage,
          startedAt: started.startedAt ?? started.createdAt ?? current.snapshot.startedAt
        } : current.snapshot
      }))

      const poll = async () => {
        try {
          const job = await fetchShortTermScanJob(started.jobId)
          if (!ownsRun()) return
          const runningJobMessage = job.message || '短线右侧实时扫描中'
          set({ scanMessage: runningJobMessage })
          if (job.status === 'SUCCEEDED') {
            if (!job.report && !manualResultMayOmitReport(job.resultStatus)) {
              const message = missingReportMessage(job)
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
              publishManualScanFailure(message)
              return
            }

            const manualSnapshot = snapshotFromManualJob(job)
            const visibleReport = visibleSnapshotReport(manualSnapshot)
            const outcome = manualScanOutcome(manualSnapshot)
            set({
              snapshot: manualSnapshot,
              report: visibleReport,
              error: visibleReport === null ? outcome.message : '',
              loading: false
            })
            notifySnapshotCompleted('MANUAL', manualSnapshot)
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
            publishManualScanFailure(message, job.blockedReasons)
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
            publishManualScanFailure(message)
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
        publishManualScanFailure(message)
      }
    }
  }
}))

export function resetShortTermScanStoreForTest() {
  manualRunGeneration += 1
  scheduledLoadGeneration += 1
  scheduledSnapshotRequest = null
  clearPollTimer()
  completionToastKey = ''
  useShortTermScanStore.setState(initialState())
}

function visibleSnapshotReport(snapshot: ShortTermScheduledSnapshot) {
  if (snapshot.status === 'DATA_BLOCKED'
    || snapshot.status === 'FAILED'
    || snapshot.status === 'RUNNING'
    || snapshot.status === 'FINAL_PENDING'
    || snapshot.status === 'PRESELECT_READY') {
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
    startedAt: job.startedAt ?? job.createdAt,
    completedAt: job.finishedAt,
    blockedReasons: job.blockedReasons,
    report: job.report
  }
}

type ManualScanOutcome = {
  tone: 'success' | 'error' | 'warning'
  message: string
  options: typeof MANUAL_SCAN_PERSISTENT_TOAST | typeof MANUAL_SCAN_SUCCESS_TOAST
}

function uniqueNonBlank(values: string[]) {
  const seen = new Set<string>()
  return values
    .map((value) => value.trim())
    .filter((value) => {
      if (!value || seen.has(value)) return false
      seen.add(value)
      return true
    })
}

function outcomeMessage(
  prefix: string,
  serverMessage: string,
  blockedReasons: string[],
  fallback: string
) {
  const details = uniqueNonBlank([serverMessage, ...blockedReasons])
    .filter((detail) => !prefix.includes(detail) && !detail.includes(prefix))
  return `${prefix}：${details.length > 0 ? details.join('；') : fallback}`
}

function manualScanOutcome(snapshot: ShortTermScheduledSnapshot): ManualScanOutcome {
  const candidateCount = snapshot.report?.candidateCount
  if (snapshot.status === 'FINAL_READY' && candidateCount !== undefined && candidateCount > 0) {
    return {
      tone: 'success',
      message: snapshotCompletionToastMessage(snapshot),
      options: MANUAL_SCAN_SUCCESS_TOAST
    }
  }

  if (snapshot.status === 'FAILED') {
    return {
      tone: 'error',
      message: outcomeMessage(
        '手动扫描失败',
        snapshot.message,
        snapshot.blockedReasons,
        '扫描任务未完成'
      ),
      options: MANUAL_SCAN_PERSISTENT_TOAST
    }
  }

  const warning = (() => {
    switch (snapshot.status) {
      case 'FINAL_READY':
      case 'NO_TRADE':
        return ['手动扫描完成，未生成合格候选', '当前条件下没有满足全部规则的标的'] as const
      case 'DATA_BLOCKED':
        return ['手动扫描未生成结果，数据质量已阻断', '行情数据质量未通过'] as const
      case 'CACHE_PREVIEW':
        return ['手动扫描仅返回缓存预览，不是当前买点', '当前实时行情不可用'] as const
      case 'FINAL_PENDING':
        return ['手动扫描暂未形成可操作结果', '最终结果仍待截止认证'] as const
      case 'PRESELECT_READY':
        return ['手动扫描暂未形成可操作结果', '当前仅完成预选'] as const
      case 'RUNNING':
        return ['手动扫描返回了非终态结果', '请稍后重新扫描'] as const
    }
  })()

  return {
    tone: 'warning',
    message: outcomeMessage(
      warning[0],
      snapshot.message,
      snapshot.blockedReasons,
      warning[1]
    ),
    options: MANUAL_SCAN_PERSISTENT_TOAST
  }
}

function publishManualScanOutcome(snapshot: ShortTermScheduledSnapshot) {
  const outcome = manualScanOutcome(snapshot)
  toast[outcome.tone](outcome.message, outcome.options)
  return outcome
}

function manualResultMayOmitReport(status: ShortTermScheduledSnapshot['status']) {
  return status === 'NO_TRADE'
    || status === 'DATA_BLOCKED'
    || status === 'FINAL_PENDING'
    || status === 'PRESELECT_READY'
}

function missingReportMessage(job: ShortTermScanJobStatus) {
  return outcomeMessage(
    '短线扫描任务已完成，但没有返回报告',
    job.message,
    job.blockedReasons,
    '服务端未返回报告数据'
  )
}

function publishManualScanFailure(message: string, blockedReasons: string[] = []) {
  toast.error(
    outcomeMessage('手动扫描失败', message, blockedReasons, '扫描任务未完成'),
    MANUAL_SCAN_PERSISTENT_TOAST
  )
}

function notifySnapshotCompleted(origin: ShortTermScanOrigin, snapshot: ShortTermScheduledSnapshot) {
  if (origin === 'MANUAL') {
    publishManualScanOutcome(snapshot)
    return
  }
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

function scheduledSnapshotTakesControl(
  current: Pick<ShortTermScanState, 'origin' | 'snapshot' | 'report'>,
  scheduled: ShortTermScheduledSnapshot
) {
  if (current.origin === 'SCHEDULED') return true
  if (!current.snapshot) return current.report === null
  if (scheduled.tradeDate > current.snapshot.tradeDate) return true
  if (scheduled.tradeDate < current.snapshot.tradeDate) return false
  if (scheduled.stage !== 'FINAL') return false

  const scheduledTime = snapshotActivityTime(scheduled)
  const currentTime = snapshotActivityTime(current.snapshot)
  return scheduledTime !== null && (currentTime === null || scheduledTime > currentTime)
}

function snapshotActivityTime(snapshot: ShortTermScheduledSnapshot) {
  const value = snapshot.completedAt ?? snapshot.startedAt ?? snapshot.dataCutoffAt
  if (!value) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}
