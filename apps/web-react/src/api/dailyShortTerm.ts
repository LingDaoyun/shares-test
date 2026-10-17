import axios from 'axios'
import type { ShortTermReport } from '../types'

export interface UpperShadowCandidate {
  symbol: string
  name: string
  industry: string | null
  label: string
  latestPrice: number
  changePercent: number
  amount: number
  turnoverRate: number | null
  marketTimestamp: string
  boxRangePercent: number
  boxNetChangePercent: number
  boxHigh: number
  boxLow: number
  upperShadowRatioPercent: number
  upperShadowPercent: number
  evidence: string[]
  counterEvidence: string[]
}

export interface UpperShadowScan {
  tradeDate: string
  assessedAt: string
  status: 'READY' | 'PARTIAL' | 'DATA_BLOCKED'
  source: string
  expectedCount: number
  validQuoteCount: number
  shapeCount: number
  historyMissingCount: number
  matchedCount: number
  omittedCount: number
  candidates: UpperShadowCandidate[]
  dataGaps: string[]
  ruleDescription: string
}

export interface ShortTermDailySnapshot {
  tradeDate: string
  scheduledAt: string | null
  startedAt: string | null
  finishedAt: string | null
  status: 'NOT_STARTED' | 'RUNNING' | 'READY' | 'PARTIAL' | 'FAILED' | 'INTERRUPTED'
  message: string
  report: ShortTermReport | null
  upperShadow: UpperShadowScan | null
  dataGaps: string[]
  runId?: string
}

export async function fetchDailyShortTermSnapshot(): Promise<ShortTermDailySnapshot> {
  const response = await axios.get<ShortTermDailySnapshot>('/api/short-term/daily-scan/latest', { timeout: 15000 })
  return response.data
}

export async function fetchShortTermResultCache(): Promise<ShortTermDailySnapshot> {
  const response = await axios.get<ShortTermDailySnapshot>('/api/short-term/daily-scan/result-cache', { timeout: 15000 })
  return response.data
}

export async function retryDailyShortTermSnapshot(): Promise<ShortTermDailySnapshot> {
  const response = await axios.post<ShortTermDailySnapshot>('/api/short-term/daily-scan/retry', undefined, { timeout: 15000 })
  return response.data
}
