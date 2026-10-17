import { create } from 'zustand'
import { fetchShortTermScanJob, startShortTermScanJob } from '../api/client'
import type { ShortTermParams } from '../api/client'
import type { ShortTermDailySnapshot, UpperShadowScan } from '../api/dailyShortTerm'
import { toast } from '../components/ui/Toast'
import { extractErrorMessage } from '../lib/format'
import type { ShortTermReport, ShortTermScanJobStatus, ShortTermScanResultStatus } from '../types'

export type ShortTermAppliedResult = Omit<ShortTermDailySnapshot, 'report' | 'upperShadow'> & {
  source: 'manual' | 'cache'
}

interface ShortTermScanState {
  report: ShortTermReport | null
  upperShadow: UpperShadowScan | null
  resultMetadata: ShortTermAppliedResult | null
  loading: boolean
  error: string
  scanMessage: string
  activeJobId: string
  runManualScan: (params: ShortTermParams) => Promise<void>
  applyScanResult: (result: ShortTermDailySnapshot, options?: { source?: 'manual' | 'cache'; force?: boolean }) => boolean
}

let manualRunGeneration = 0
export function getShortTermManualScanGeneration() { return manualRunGeneration }
let pollTimer: number | undefined
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
    report: null,
    upperShadow: null,
    resultMetadata: null,
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

  applyScanResult: (result, { source = 'cache', force = false } = {}) => {
    const state = get()
    if (!['READY', 'PARTIAL'].includes(result.status)) return false
    if (source === 'cache') {
      if (state.loading) return false
      if (!force) {
        if (state.error || (state.report && !state.resultMetadata)) return false
        const current = state.resultMetadata
        if (current) {
          if (result.runId && result.runId === current.runId) return false
          const incomingTime = Date.parse(result.startedAt ?? result.scheduledAt ?? '')
          const currentTime = Date.parse(current.startedAt ?? current.scheduledAt ?? '')
          if (Number.isFinite(currentTime) && (!Number.isFinite(incomingTime) || incomingTime < currentTime
            || (incomingTime === currentTime && (current.source === 'manual' || (result.runId && current.runId))))) return false
        }
      }
    }
    const report = result.report && source === 'cache' ? { ...result.report, tradeCaptureTokens: {} } : result.report ?? null
    const upperShadow = result.upperShadow && result.upperShadow.tradeDate === result.tradeDate ? result.upperShadow : null
    const { report: _report, upperShadow: _upperShadow, ...metadata } = result
    set({ report, upperShadow, resultMetadata: { ...metadata, source }, error: '' })
    return true
  },

  runManualScan: async (params) => {
    const generation = manualRunGeneration + 1
    manualRunGeneration = generation
    clearPollTimer()
    toast.loading(
      '短线扫描已开始，正在获取实时行情…',
      MANUAL_SCAN_PERSISTENT_TOAST
    )

    const ownsRun = () => manualRunGeneration === generation
    set({
      loading: true,
      error: '',
      report: null,
      upperShadow: null,
      resultMetadata: null,
      scanMessage: '提交实时扫描任务',
      activeJobId: ''
    })

    try {
      const started = await startShortTermScanJob(params)
      if (!ownsRun()) return
      const runningMessage = started.message || '短线右侧实时扫描中'
      set({
        activeJobId: started.jobId,
        scanMessage: runningMessage
      })

      const poll = async () => {
        try {
          const job = await fetchShortTermScanJob(started.jobId)
          if (!ownsRun()) return
          const runningJobMessage = job.message || '短线右侧实时扫描中'
          set({ scanMessage: runningJobMessage })

          if (job.status === 'SUCCEEDED') {
            if (!job.report && !job.upperShadow && !manualResultMayOmitReport(job.resultStatus)) {
              const message = missingReportMessage(job)
              set({
                report: null,
                upperShadow: null,
                resultMetadata: null,
                error: message,
                loading: false,
                activeJobId: ''
              })
              publishManualScanFailure(message)
              return
            }

            const visibleReport = visibleManualReport(job)
            const outcome = manualScanOutcome(job)
            get().applyScanResult({
              runId: job.jobId ?? started.jobId,
              tradeDate: job.tradeDate,
              scheduledAt: null,
              startedAt: job.startedAt ?? job.createdAt,
              finishedAt: job.finishedAt,
              status: job.resultStatus === 'DATA_BLOCKED' || job.resultStatus === 'CACHE_PREVIEW' || job.resultStatus === 'FAILED'
                || (job.dataGaps?.length ?? 0) > 0 || job.upperShadow?.status === 'PARTIAL' || job.upperShadow?.status === 'DATA_BLOCKED' ? 'PARTIAL' : 'READY',
              message: job.message,
              report: visibleReport,
              upperShadow: job.upperShadow ?? null,
              dataGaps: job.dataGaps ?? []
            }, { source: 'manual' })
            set({
              error: outcome.tone === 'error' || (visibleReport === null && !job.upperShadow) ? outcome.message : '',
              loading: false,
              activeJobId: ''
            })
            publishManualScanOutcome(job)
            return
          }

          if (job.status === 'FAILED') {
            const message = job.message || '短线右侧实时扫描失败'
            set({
              report: null,
              upperShadow: null,
              resultMetadata: null,
              error: message,
              loading: false,
              activeJobId: ''
            })
            publishManualScanFailure(message, job.blockedReasons)
            return
          }

          pollTimer = window.setTimeout(() => void poll(), 1500)
        } catch (error) {
          if (!ownsRun()) return
          const message = extractErrorMessage(error)
          set({
            report: null,
            upperShadow: null,
            resultMetadata: null,
            error: message,
            loading: false,
            activeJobId: ''
          })
          publishManualScanFailure(message)
        }
      }

      await poll()
    } catch (error) {
      if (!ownsRun()) return
      const message = extractErrorMessage(error)
      set({
        report: null,
        upperShadow: null,
        resultMetadata: null,
        error: message,
        loading: false,
        activeJobId: ''
      })
      publishManualScanFailure(message)
    }
  }
}))

export function resetShortTermScanStoreForTest() {
  manualRunGeneration += 1
  clearPollTimer()
  useShortTermScanStore.setState(initialState())
}

function visibleManualReport(job: ShortTermScanJobStatus) {
  if (job.resultStatus === 'DATA_BLOCKED'
    || job.resultStatus === 'FAILED'
    || job.resultStatus === 'RUNNING') {
    return null
  }
  return job.report
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

function manualScanOutcome(job: ShortTermScanJobStatus): ManualScanOutcome {
  const candidateCount = combinedCandidateCount(job)
  if ((job.resultStatus === 'FINAL_READY' || job.resultStatus === 'NO_TRADE') && candidateCount > 0) {
    return {
      tone: 'success',
      message: scanCompletionToastMessage(job),
      options: MANUAL_SCAN_SUCCESS_TOAST
    }
  }

  if (job.resultStatus === 'FAILED') {
    if (job.upperShadow && job.upperShadow.status !== 'DATA_BLOCKED') {
      return {
        tone: 'warning',
        message: outcomeMessage(`手动扫描已生成 ${candidateCount} 个候选，右侧未完成`, job.message, job.dataGaps ?? [], '形态扫描已完成'),
        options: MANUAL_SCAN_PERSISTENT_TOAST
      }
    }
    return {
      tone: 'error',
      message: outcomeMessage(
        '手动扫描失败',
        job.message,
        job.blockedReasons,
        '扫描任务未完成'
      ),
      options: MANUAL_SCAN_PERSISTENT_TOAST
    }
  }

  const warning = warningCopy(job.resultStatus)
  return {
    tone: 'warning',
    message: outcomeMessage(
      candidateCount > 0 ? `手动扫描已生成 ${candidateCount} 个候选，${job.resultStatus === 'DATA_BLOCKED' ? '右侧数据质量已阻断' : warning[0]}` : warning[0],
      job.message,
      job.blockedReasons,
      warning[1]
    ),
    options: MANUAL_SCAN_PERSISTENT_TOAST
  }
}

function warningCopy(status: ShortTermScanResultStatus): readonly [string, string] {
  switch (status) {
    case 'FINAL_READY':
    case 'NO_TRADE':
      return ['手动扫描完成，未生成合格候选', '当前条件下没有满足全部规则的标的']
    case 'DATA_BLOCKED':
      return ['手动扫描未生成结果，数据质量已阻断', '行情数据质量未通过']
    case 'CACHE_PREVIEW':
      return ['手动扫描仅返回缓存预览，不是当前买点', '当前实时行情不可用']
    case 'RUNNING':
      return ['手动扫描返回了非终态结果', '请稍后重新扫描']
    case 'FAILED':
      return ['手动扫描失败', '扫描任务未完成']
  }
}

function publishManualScanOutcome(job: ShortTermScanJobStatus) {
  const outcome = manualScanOutcome(job)
  toast[outcome.tone](outcome.message, outcome.options)
}

function manualResultMayOmitReport(status: ShortTermScanResultStatus) {
  return status === 'NO_TRADE' || status === 'DATA_BLOCKED'
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

function scanCompletionToastMessage(job: ShortTermScanJobStatus) {
  const count = combinedCandidateCount(job)
  if (count === 0) return `${job.message}，暂无候选`
  return `${job.message}，已生成 ${count} 个候选`
}

function combinedCandidateCount(job: ShortTermScanJobStatus) {
  return new Set([
    ...(visibleManualReport(job)?.candidates ?? []).map(candidate => candidate.symbol),
    ...(job.upperShadow && job.upperShadow.tradeDate === job.tradeDate ? job.upperShadow.candidates : []).map(candidate => candidate.symbol)
  ]).size
}
