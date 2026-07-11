import axios from 'axios'
import type {
  AgentConsensusReport,
  CompanyProfile,
  EvidenceReviewReport,
  FilingEvidenceSummary,
  CompanyResearchView,
  FactorSnapshot,
  InvestmentDecisionReport,
  LlmConfigPreview,
  PolicyTheme,
  RuntimeConfigSnapshot,
  RuleDefinition,
  RuleEvaluationResult,
  StockSelectionReport,
  TrendAnalysisHistoryItem,
  TrendAnalysisResponse,
  TrendPromptPreview,
  TrendPromptRequest,
  WatchlistEntry
} from '../types'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

export async function fetchPolicyThemes(): Promise<PolicyTheme[]> {
  const { data } = await http.get<PolicyTheme[]>('/policy/themes')
  return data
}

export async function fetchCompanies(): Promise<CompanyProfile[]> {
  const { data } = await http.get<CompanyProfile[]>('/companies')
  return data
}

export async function fetchCompanyResearch(symbol: string): Promise<CompanyResearchView> {
  const { data } = await http.get<CompanyResearchView>(`/companies/${symbol}/research`)
  return data
}

export async function fetchCompanyConsensus(symbol: string): Promise<AgentConsensusReport> {
  const { data } = await http.get<AgentConsensusReport>(`/companies/${symbol}/agent-consensus`)
  return data
}

export async function enhanceCompanyConsensus(symbol: string): Promise<AgentConsensusReport> {
  const { data } = await http.post<AgentConsensusReport>(`/companies/${symbol}/agent-consensus/ai`, undefined, {
    timeout: 150000
  })
  return data
}

export async function fetchEvidenceReview(symbol: string): Promise<EvidenceReviewReport> {
  const { data } = await http.post<EvidenceReviewReport>(`/companies/${symbol}/evidence-review/run`, undefined, {
    timeout: 180000
  })
  return data
}

export async function fetchInvestmentDecision(symbol: string): Promise<InvestmentDecisionReport> {
  const { data } = await http.post<InvestmentDecisionReport>(`/companies/${symbol}/investment-decision/run`, undefined, {
    timeout: 240000
  })
  return data
}

export async function fetchCompanyFilings(symbol: string): Promise<FilingEvidenceSummary> {
  const { data } = await http.get<FilingEvidenceSummary>(`/companies/${symbol}/filings`)
  return data
}

export async function fetchRules(): Promise<RuleDefinition[]> {
  const { data } = await http.get<RuleDefinition[]>('/rules')
  return data
}

export async function evaluateRules(snapshot: FactorSnapshot): Promise<RuleEvaluationResult[]> {
  const { data } = await http.post<RuleEvaluationResult[]>('/rules/evaluate', snapshot)
  return data
}

export async function fetchWatchlist(): Promise<WatchlistEntry[]> {
  const { data } = await http.get<WatchlistEntry[]>('/watchlist')
  return data
}

export async function fetchAgentShortlist(limit = 5, reviewLimit = 18): Promise<StockSelectionReport> {
  const { data } = await http.get<StockSelectionReport>('/selection/agent-shortlist', {
    params: { limit, reviewLimit },
    timeout: 180000
  })
  return data
}

export async function fetchLlmConfig(): Promise<LlmConfigPreview> {
  const { data } = await http.get<LlmConfigPreview>('/ai/llm-config')
  return data
}

export async function fetchRuntimeConfig(): Promise<RuntimeConfigSnapshot> {
  const { data } = await http.get<RuntimeConfigSnapshot>('/runtime-config')
  return data
}

export async function updateRuntimeConfig(request: RuntimeConfigSnapshot): Promise<RuntimeConfigSnapshot> {
  const { data } = await http.put<RuntimeConfigSnapshot>('/runtime-config', request)
  return data
}

export async function fetchTrendPromptSample(): Promise<TrendPromptPreview> {
  const { data } = await http.get<TrendPromptPreview>('/ai/trend-prompts/sample')
  return data
}

export async function previewTrendPrompt(request: TrendPromptRequest): Promise<TrendPromptPreview> {
  const { data } = await http.post<TrendPromptPreview>('/ai/trend-prompts/preview', request)
  return data
}

export async function analyzeTrend(request: TrendPromptRequest): Promise<TrendAnalysisResponse> {
  const { data } = await http.post<TrendAnalysisResponse>('/ai/trend-analysis', request, {
    timeout: 120000
  })
  return data
}

export async function fetchLatestTrendAnalysis(request: TrendPromptRequest): Promise<TrendAnalysisResponse | null> {
  const response = await http.post<TrendAnalysisResponse>('/ai/trend-analysis/latest', request, {
    validateStatus: (status) => status === 200 || status === 404
  })
  return response.status === 404 ? null : response.data
}

export async function fetchTrendAnalysisHistory(limit = 20): Promise<TrendAnalysisHistoryItem[]> {
  const { data } = await http.get<TrendAnalysisHistoryItem[]>('/ai/trend-analysis/history', {
    params: { limit }
  })
  return data
}
