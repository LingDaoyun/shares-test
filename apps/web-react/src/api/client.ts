import axios from 'axios'
import type {
  BacktestReport,
  CycleTrialReport,
  DailySignalReport,
  DecisionHistoryEntry,
  LlmConfigPreview,
  InvestmentDecisionReport,
  MarketScanReport,
  MispricingReport,
  RecommendationEvidenceBundle,
  ShortTermReport,
  ShortTermScanJobStatus,
  RuleDefinition,
  RuntimeConfigSnapshot,
  TechTrackingReport,
  WatchlistEntry
} from '../types'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

export function fetchRules() {
  return http.get<RuleDefinition[]>('/rules').then((res) => res.data)
}

export function fetchLlmConfig() {
  return http.get<LlmConfigPreview>('/ai/llm-config').then((res) => res.data)
}

export function fetchRuntimeConfig() {
  return http.get<RuntimeConfigSnapshot>('/runtime-config').then((res) => res.data)
}

export function updateRuntimeConfig(request: RuntimeConfigSnapshot) {
  return http.put<RuntimeConfigSnapshot>('/runtime-config', request).then((res) => res.data)
}

export interface TechTrackingParams {
  limit?: number
  coreMaxPe?: number
  coreMaxPb?: number
  hardMaxPe?: number
  hardMaxPb?: number
}

export function fetchTechTrackingReport(params: TechTrackingParams = {}) {
  return http.get<TechTrackingReport>('/tech-tracker/report', { params }).then((res) => res.data)
}

export interface MarketScanParams {
  limit?: number
  scanLimit?: number
  minAmount?: number
  maxPe?: number
  maxPb?: number
  minFinancialScore?: number
  excludeSideways?: boolean
  includeNorthExchange?: boolean
  mode?: string
}

export function fetchMarketScanReport(params: MarketScanParams = {}) {
  return http.get<MarketScanReport>('/market-scan/report', { params }).then((res) => res.data)
}

export interface ShortTermParams {
  limit?: number
  scanLimit?: number
  klineLimit?: number
  minAmount?: number
  maxPe?: number
  maxPb?: number
  minVolumeRatio?: number
  maxEntryRise?: number
  maxDistanceToMa20?: number
  minFinancialScore?: number
}

export function fetchShortTermReport(params: ShortTermParams = {}) {
  return http.get<ShortTermReport>('/short-term/report', { params }).then((res) => res.data)
}

export function startShortTermScanJob(params: ShortTermParams = {}) {
  return http.post<ShortTermScanJobStatus>('/short-term/scan-jobs', params).then((res) => res.data)
}

export function fetchShortTermScanJob(jobId: string) {
  return http.get<ShortTermScanJobStatus>(`/short-term/scan-jobs/${jobId}`).then((res) => res.data)
}

export interface BacktestParams {
  symbols?: string
  lookbackDays?: number
  holdingDays?: number
  minVolumeRatio?: number
  maxVolumeRatio?: number
  maxDistanceToMa20?: number
  stopLossPercent?: number
  takeProfitPercent?: number
  commissionPercent?: number
  stampDutyPercent?: number
  slippagePercent?: number
  limitMovePercent?: number
}

export function fetchRightSideBacktest(params: BacktestParams = {}) {
  return http.get<BacktestReport>('/backtests/right-side', { params }).then((res) => res.data)
}

export interface MispricingParams {
  limit?: number
  scanLimit?: number
  hotHeat?: number
  maxPe?: number
  maxPb?: number
  minQuality?: number
}

export function fetchMispricingReport(params: MispricingParams = {}) {
  return http.get<MispricingReport>('/mispricing/report', { params }).then((res) => res.data)
}

export interface CycleTrialParams {
  limit?: number
  leftTrialScore?: number
  rightAddScore?: number
  maxChaseRise?: number
  minVolumeRatio?: number
}

export function fetchCycleTrialReport(params: CycleTrialParams = {}) {
  return http.get<CycleTrialReport>('/cycle-trials/report', { params }).then((res) => res.data)
}

export interface DailySignalParams {
  limit?: number
  techLimit?: number
  mispricingLimit?: number
  hotHeat?: number
}

export function fetchDailySignalReport(params: DailySignalParams = {}) {
  return http.get<DailySignalReport>('/daily-signals/report', { params }).then((res) => res.data)
}

export function fetchRecommendationEvidence(symbol: string) {
  return http
    .get<RecommendationEvidenceBundle>(`/companies/${encodeURIComponent(symbol)}/recommendation-evidence`)
    .then((res) => res.data)
}

export function fetchWatchlist() {
  return http.get<WatchlistEntry[]>('/watchlist').then((res) => res.data)
}

export function addToWatchlist(symbol: string, note = '') {
  return http.post<WatchlistEntry>('/watchlist', { symbol, note }).then((res) => res.data)
}

export function removeFromWatchlist(symbol: string) {
  return http.delete(`/watchlist/${encodeURIComponent(symbol)}`)
}

export function analyzeWatchlistSymbol(symbol: string) {
  return http
    .post<InvestmentDecisionReport>(`/watchlist/${encodeURIComponent(symbol)}/analyze`)
    .then((res) => res.data)
}

export function fetchWatchlistHistory(symbol: string, limit = 20) {
  return http
    .get<DecisionHistoryEntry[]>(`/watchlist/${encodeURIComponent(symbol)}/history`, { params: { limit } })
    .then((res) => res.data)
}
