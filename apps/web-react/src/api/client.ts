import axios from 'axios'
import type { ShortTermCatchUpFollowUpObservation, ShortTermCatchUpFollowUpSnapshot, ShortTermCatchUpSnapshot } from '../types/shortTermCatchUp'
import type {
  BacktestReport,
  LeaderAiReview,
  LeaderBarSeries,
  PotentialLeaderCandidate,
  PotentialLeaderPreferences,
  PotentialLeaderSnapshot,
  OvernightBacktestReport,
  CycleTrialReport,
  DailySignalReport,
  DecisionHistoryEntry,
  LlmConfigPreview,
  LlmRuntimeConfig,
  InvestmentDecisionReport,
  LongTermCandidateContext,
  HighDividendBoardSnapshot,
  OverseasMarketBoardSnapshot,
  MarketScanReport,
  ManualTradeFillRequest,
  MispricingReport,
  PolicyAiReview,
  PolicyTheme,
  RecommendationEvidenceBundle,
  ShortTermReport,
  ShortTermScanJobStatus,
  ShortTermIndustryLeaderSnapshot,
  ShortTermLimitUpBoardSnapshot,
  ShortTermLimitUpIndustrySnapshot,
  ShortTermMarketBreadth,
  ShortTermValidationBatchRequest,
  ShortTermValidationSummary,
  RuleDefinition,
  PolicySourceConfig,
  RuntimeConfigSnapshot,
  TechTrackingReport,
  CreateTradeCaseRequest,
  StrategyFeedbackSummary,
  TradeCaseDetail,
  TradeCaseStatus,
  TradeCaseSummary,
  UpsertTradeFillRequest,
  WatchlistEntry,
  V2SampleSignalParams,
  V2StrategyBundleParams,
  V2StrategyBundleResponse,
  V2SignalResponse
} from '../types'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

export function fetchRules() {
  return http.get<RuleDefinition[]>('/rules').then((res) => res.data)
}

export function fetchPotentialLeaderSnapshot(params: PotentialLeaderPreferences) {
  return http.get<PotentialLeaderSnapshot>('/potential-leaders/snapshot', { params }).then((res) => res.data)
}

export function fetchPotentialLeaderBars(symbol: string) {
  return http
    .get<LeaderBarSeries>(`/potential-leaders/bars/${encodeURIComponent(symbol)}`)
    .then((res) => res.data)
}

export function requestPotentialLeaderAiReview(candidate: PotentialLeaderCandidate) {
  return http
    .post<LeaderAiReview>(
      `/potential-leaders/ai-review/${candidate.lane.toLowerCase()}/${encodeURIComponent(candidate.symbol)}`,
      { snapshotHash: candidate.snapshotHash }
    )
    .then((res) => res.data)
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

export function fetchLlmRuntimeConfig() {
  return http.get<LlmRuntimeConfig>('/runtime-config/llm').then((res) => res.data)
}

export function updateLlmRuntimeConfig(request: LlmRuntimeConfig) {
  return http.put<LlmRuntimeConfig>('/runtime-config/llm', request).then((res) => res.data)
}

export function reviewPolicyTheme(themeCode: string) {
  return http
    .post<PolicyAiReview>(`/policy/themes/${encodeURIComponent(themeCode)}/ai-review`, undefined, { timeout: 360000 })
    .then((res) => res.data)
}

export function fetchPolicySources() {
  return http.get<PolicySourceConfig[]>('/runtime-config/policy-sources').then((res) => res.data)
}

export function updatePolicySources(request: PolicySourceConfig[]) {
  return http.put<PolicySourceConfig[]>('/runtime-config/policy-sources', request).then((res) => res.data)
}

export function fetchPolicyThemes() {
  return http.get<PolicyTheme[]>('/policy/themes').then((res) => res.data)
}

export function fetchPolicyReviewHistory(themeCode: string) {
  return http.get<PolicyAiReview[]>(`/policy/themes/${encodeURIComponent(themeCode)}/ai-reviews`).then(res => res.data)
}

export function fetchPolicyDaily() {
  return http.get<import('../types').PolicyDailySnapshot>('/policy/daily').then(res => res.data)
}

export function refreshPolicyDaily() {
  return http.post<import('../types').PolicyDailySnapshot>('/policy/daily/refresh').then(res => res.data)
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
  allowChiNext?: boolean
  mode?: string
}

export function fetchMarketScanReport(params: MarketScanParams = {}) {
  return http.get<MarketScanReport>('/market-scan/report', { params }).then((res) => res.data)
}

export function fetchLongTermCandidateContext(symbol: string, industry?: string | null) {
  return http.get<LongTermCandidateContext>(`/market-scan/candidates/${symbol}/context`, {
    params: industry ? { industry } : undefined
  }).then((res) => res.data)
}

export function fetchHighDividendBoard(minYieldPercent?: number) {
  return http
    .get<HighDividendBoardSnapshot>('/market-scan/high-dividend-board', {
      params: minYieldPercent ? { minYieldPercent } : undefined
    })
    .then((res) => res.data)
}

export function fetchOverseasMarketBoard() {
  return http.get<OverseasMarketBoardSnapshot>('/overseas-market/board').then((res) => res.data)
}

export interface ShortTermParams {
  limit?: number
  scanLimit?: number
  klineLimit?: number
  minAmount?: number
  maxPricePerShare?: number
  minVolumeRatio?: number
  maxEntryRise?: number
  maxDistanceToMa20?: number
  minFinancialScore?: number
  allowStaticCachePreview?: boolean
  allowChiNext?: boolean
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

export function fetchShortTermLimitUpBoard(date?: string) {
  return http
    .get<ShortTermLimitUpBoardSnapshot>('/short-term/limit-up-board', {
      params: date ? { date } : undefined
    })
    .then((res) => res.data)
}

export function fetchShortTermLimitUpIndustry(industry: string, date: string) {
  return http.get<ShortTermLimitUpIndustrySnapshot>('/short-term/limit-up-board/industry', {
    params: { industry, date }
  }).then((res) => res.data)
}

export function fetchShortTermCatchUp(industry: string, date: string, leaderSymbol: string, boardCode?: string) {
  return http.get<ShortTermCatchUpSnapshot>('/short-term/limit-up-board/industry/catch-up', {
    params: { industry, date, leaderSymbol, ...(boardCode ? { boardCode } : {}) }
  }).then((res) => res.data)
}

export function fetchShortTermCatchUpFollowUp(observations: ShortTermCatchUpFollowUpObservation[]) {
  return http.post<ShortTermCatchUpFollowUpSnapshot>('/short-term/catch-up/follow-up', { observations })
    .then((res) => res.data)
}

export function fetchShortTermMarketBreadth() {
  return http.get<ShortTermMarketBreadth>('/short-term/market-breadth').then((res) => res.data)
}

export function fetchShortTermIndustryLeaders(
  code: string,
  options: { name?: string; flow?: 'INFLOW' | 'OUTFLOW'; limit?: number } = {}
) {
  return http
    .get<ShortTermIndustryLeaderSnapshot>('/short-term/industry-fund-leaders', {
      params: {
        code,
        name: options.name,
        flow: options.flow,
        limit: options.limit
      }
    })
    .then((res) => res.data)
}

export function fetchShortTermValidationSummaries(request: ShortTermValidationBatchRequest) {
  return http.post<ShortTermValidationSummary[]>('/short-term/validation/summaries', request).then((res) => res.data)
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

export interface OvernightBacktestParams {
  symbols?: string
  lookbackDays?: number
  firstTargetPercent?: number
  secondTargetPercent?: number
  hardStopPercent?: number
  maxHoldingTradingDays?: number
  commissionPercent?: number
  stampDutyPercent?: number
  slippagePercent?: number
  limitMovePercent?: number
  minVolumeRatio?: number
  maxDistanceToMa20Percent?: number
  trailingDrawdownPercent?: number
}

export function fetchOvernightBacktest(params: OvernightBacktestParams = {}) {
  return http.get<OvernightBacktestReport>('/backtests/overnight', { params }).then((res) => res.data)
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

export function fetchTradeCases(params: {
  status?: TradeCaseStatus
  symbol?: string
  beforeCreatedAt?: string
  beforeCaseId?: string
  limit?: number
} = {}) {
  return http.get<TradeCaseSummary[]>('/trade-cases', { params }).then((res) => res.data)
}

export function fetchTradeCase(caseId: string) {
  return http.get<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}`).then((res) => res.data)
}

export function createTradeCase(request: CreateTradeCaseRequest) {
  return http.post<TradeCaseDetail>('/trade-cases', request).then((res) => res.data)
}

export function recordManualTradeFill(request: ManualTradeFillRequest) {
  return http.post<TradeCaseDetail>('/trade-cases/manual-fills', request).then((res) => res.data)
}

export function addTradeFill(caseId: string, request: UpsertTradeFillRequest) {
  return http
    .post<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/fills`, request)
    .then((res) => res.data)
}

export function updateTradeFill(caseId: string, fillId: string, request: UpsertTradeFillRequest) {
  return http
    .put<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/fills/${encodeURIComponent(fillId)}`, request)
    .then((res) => res.data)
}

export function deleteTradeFill(caseId: string, fillId: string) {
  return http
    .delete<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/fills/${encodeURIComponent(fillId)}`)
    .then((res) => res.data)
}

export function deleteTradeCase(caseId: string) {
  return http.delete<void>(`/trade-cases/${encodeURIComponent(caseId)}`).then((res) => res.data)
}

export function cancelTradeCase(caseId: string) {
  return http.post<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/cancel`).then((res) => res.data)
}

export function refreshTradeCase(caseId: string) {
  return http.post<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/refresh`).then((res) => res.data)
}

export function fetchStrategyFeedback() {
  return http.get<StrategyFeedbackSummary[]>('/strategy-feedback').then((res) => res.data)
}

export async function fetchV2SampleSignal(params: V2SampleSignalParams): Promise<V2SignalResponse> {
  const search = new URLSearchParams()
  search.set('symbol', params.symbol)
  if (params.companyName) search.set('companyName', params.companyName)
  if (params.strategyCode) search.set('strategyCode', params.strategyCode)
  return http.get<V2SignalResponse>(`/v2/signals/sample?${search.toString()}`).then((res) => res.data)
}

export async function fetchV2StrategyBundle(params: V2StrategyBundleParams): Promise<V2StrategyBundleResponse> {
  const { symbol, companyName, ...factorParams } = params
  const search = new URLSearchParams()
  search.set('symbol', symbol)
  if (companyName) search.set('companyName', companyName)
  Object.entries(factorParams).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  })
  return http.get<V2StrategyBundleResponse>(`/v2/signals/strategy-bundle?${search.toString()}`).then((res) => res.data)
}
