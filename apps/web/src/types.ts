export interface PolicySignal {
  source: string
  signalType: string
  summary: string
  confidence: number
  url: string | null
  publishedAt: string | null
}

export interface PolicyTheme {
  themeCode: string
  name: string
  policyLevel: string
  timeHorizon: string
  strengthScore: number
  chainSegments: string[]
  signals: PolicySignal[]
  risks: string[]
}

export interface EvidenceItem {
  sourceType: string
  sourceTitle: string
  excerpt: string
  url: string | null
  confidence: number
}

export interface CompanyProfile {
  symbol: string
  name: string
  market: string
  industry: string
  themeCode: string
  themeRelevance: number
  latestPrice: number | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  turnoverRate: number | null
  amount: number | null
  quoteUrl: string | null
  dataSource: string
  fetchedAt: string | null
  financialReportDate: string | null
  financialDataType: string | null
  liveData: boolean
  coreAssets: string[]
  risks: string[]
  factors: Record<string, number>
  evidence: EvidenceItem[]
}

export interface DimensionScore {
  code: string
  name: string
  score: number
  verdict: string
  evidenceRefs: string[]
  nextChecks: string[]
}

export interface EvidenceTier {
  code: string
  label: string
  strength: number
  evidenceRefs: string[]
}

export interface FilingDocument {
  documentId: string
  title: string
  source: string
  category: string
  publishedAt: string | null
  sourceUrl: string | null
  downloadUrl: string | null
  matchedKeywords: string[]
  confidence: number
}

export interface FilingEvent {
  eventType: string
  eventLabel: string
  severity: string
  documentId: string
  documentTitle: string
  evidenceText: string
  sourceUrl: string | null
  confidence: number
}

export interface FilingEvidenceSummary {
  symbol: string
  status: string
  statusLabel: string
  totalDocuments: number
  parsedDocuments: number
  documents: FilingDocument[]
  extractedEvents: FilingEvent[]
  moatSignals: string[]
  riskSignals: string[]
  validationSignals: string[]
  dataGaps: string[]
  updatedAt: string
}

export interface CompanyResearchView {
  company: CompanyProfile
  overallScore: number
  stage: string
  stageLabel: string
  stageReason: string
  dimensions: DimensionScore[]
  evidenceTiers: EvidenceTier[]
  hardBlocks: string[]
  nextActions: string[]
  dataGaps: string[]
  sourcePlan: string[]
  filingEvidence: FilingEvidenceSummary
  analyzedAt: string
}

export interface AgentOpinion {
  agentCode: string
  agentName: string
  perspective: string
  vote: string
  voteLabel: string
  confidence: number
  score: number
  supports: string[]
  objections: string[]
  requiredEvidence: string[]
  evidenceChecks: AgentEvidenceCheck[]
  aiArgument: string | null
  aiCounterEvidence: string | null
  aiConfidenceNote: string | null
}

export interface AgentEvidenceCheck {
  requirement: string
  status: string
  statusLabel: string
  source: string
  evidenceText: string
  url: string | null
  confidence: number
}

export interface AgentConsensusReport {
  symbol: string
  companyName: string
  consensusStage: string
  consensusLabel: string
  consensusScore: number
  consensusReason: string
  supportCount: number
  watchCount: number
  reviewCount: number
  vetoCount: number
  opinions: AgentOpinion[]
  agreements: string[]
  disagreements: string[]
  requiredEvidence: string[]
  generatedAt: string
  aiEnhanced: boolean
  aiProvider: string | null
  aiModel: string | null
  aiSummary: string | null
  aiSuggestedStage: string | null
  aiWarnings: string[]
}

export interface EvidenceReviewItem {
  agentCode: string
  agentName: string
  requirement: string
  originalStatus: string
  originalStatusLabel: string
  reviewStatus: string
  reviewStatusLabel: string
  searchScope: string
  source: string
  evidenceText: string
  url: string | null
  confidence: number
  verdict: string
  nextAction: string
}

export interface EvidenceReviewStep {
  stepCode: string
  actor: string
  conclusion: string
  evidenceRefs: string[]
}

export interface EvidenceReviewReport {
  symbol: string
  companyName: string
  reviewStage: string
  reviewLabel: string
  totalItems: number
  verifiedCount: number
  partialCount: number
  notFoundCount: number
  blockedCount: number
  consensus: AgentConsensusReport
  items: EvidenceReviewItem[]
  steps: EvidenceReviewStep[]
  conclusions: string[]
  generatedAt: string
}

export interface FinancialMetricPoint {
  symbol: string
  companyName: string
  reportDate: string
  dataType: string
  roe: number | null
  operatingCashFlowPerShare: number | null
  grossMargin: number | null
  revenueGrowth: number | null
  netProfitGrowth: number | null
  eps: number | null
  bps: number | null
}

export interface FinancialHistoryReport {
  symbol: string
  companyName: string
  status: string
  statusLabel: string
  annualPointCount: number
  qualityScore: number
  averageRoe: number | null
  averageGrossMargin: number | null
  averageRevenueGrowth: number | null
  averageNetProfitGrowth: number | null
  positiveCashFlowYears: number
  negativeRevenueGrowthYears: number
  points: FinancialMetricPoint[]
  conclusions: string[]
  dataGaps: string[]
  generatedAt: string
}

export interface ValuationHistoryPoint {
  reportDate: string
  tradeDate: string
  closePrice: number | null
  eps: number | null
  bps: number | null
  pe: number | null
  pb: number | null
}

export interface PeerValuationCompany {
  symbol: string
  companyName: string
  industry: string | null
  themeCode: string | null
  peTtm: number | null
  pbRatio: number | null
  latestPrice: number | null
  amount: number | null
  relationType: string
}

export interface PeerValuationReport {
  scope: string
  scopeLabel: string
  peerCount: number
  currentPe: number | null
  currentPb: number | null
  medianPe: number | null
  medianPb: number | null
  averagePe: number | null
  averagePb: number | null
  pePeerPercentile: number | null
  pbPeerPercentile: number | null
  cheaperPeCount: number
  cheaperPbCount: number
  peers: PeerValuationCompany[]
  conclusions: string[]
  dataGaps: string[]
}

export interface ValuationHistoryReport {
  symbol: string
  companyName: string
  status: string
  statusLabel: string
  sampleCount: number
  currentPe: number | null
  currentPb: number | null
  pePercentile: number | null
  pbPercentile: number | null
  averagePe: number | null
  averagePb: number | null
  minPe: number | null
  maxPe: number | null
  minPb: number | null
  maxPb: number | null
  peerValuation: PeerValuationReport
  points: ValuationHistoryPoint[]
  conclusions: string[]
  dataGaps: string[]
  generatedAt: string
}

export interface InvestmentDecisionGate {
  gateCode: string
  gateName: string
  status: string
  statusLabel: string
  scoreImpact: number
  conclusion: string
  evidenceRefs: string[]
}

export interface ExitTrigger {
  triggerCode: string
  triggerName: string
  severity: string
  condition: string
  action: string
  evidenceRefs: string[]
}

export interface InvestmentDecisionReport {
  symbol: string
  companyName: string
  actionStage: string
  actionLabel: string
  decisionScore: number
  actionReason: string
  complianceNote: string
  passCount: number
  watchCount: number
  blockCount: number
  failCount: number
  gates: InvestmentDecisionGate[]
  thesis: string[]
  buyPreconditions: string[]
  holdDisciplines: string[]
  exitTriggers: ExitTrigger[]
  requiredActions: string[]
  financialHistory: FinancialHistoryReport
  valuationHistory: ValuationHistoryReport
  consensus: AgentConsensusReport
  evidenceReview: EvidenceReviewReport
  generatedAt: string
}

export interface StockSelectionTraceStep {
  stepCode: string
  actor: string
  conclusion: string
  evidenceRefs: string[]
}

export interface StockSelectionCandidate {
  rank: number
  symbol: string
  companyName: string
  market: string
  industry: string
  finalScore: number
  selectionLabel: string
  selectionReason: string
  discussion: AgentConsensusReport
  trace: StockSelectionTraceStep[]
}

export interface StockSelectionReport {
  scope: string
  universeCount: number
  reviewedCount: number
  selectedCount: number
  selectionRules: string[]
  candidates: StockSelectionCandidate[]
  generatedAt: string
}

export type RuleAction = 'PASS' | 'REJECT' | 'SCORE' | 'ALERT' | 'DOWN_WEIGHT' | 'REVIEW'
export type Operator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ'

export interface RuleCondition {
  factor: string
  operator: Operator
  value: number
  weight: number | null
}

export interface RuleDefinition {
  ruleCode: string
  name: string
  enabled: boolean
  version: number
  conditions: RuleCondition[]
  action: RuleAction
  description: string
  updatedAt: string
}

export interface FactorSnapshot {
  symbol: string
  factors: Record<string, number>
}

export interface ConditionEvaluation {
  factor: string
  operator: Operator
  expected: number
  actual: number | null
  passed: boolean
  message: string
}

export interface RuleEvaluationResult {
  ruleCode: string
  name: string
  ruleVersion: number
  action: RuleAction
  passed: boolean
  score: number
  conditions: ConditionEvaluation[]
}

export interface WatchlistEntry {
  symbol: string
  companyName: string
  decision: string
  researchStage: string
  researchStageLabel: string
  score: number
  ruleVersion: string
  thesis: string
  nextChecks: string[]
  updatedAt: string
}

export type JsonPrimitive = string | number | boolean | null
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue }

export interface LlmConfigPreview {
  provider: string
  model: string
  baseUrl: string
  responseFormat: string
  strictJsonSchema: boolean
  apiKeyConfigured: boolean
  apiKeySource: string
  thinking: string | null
  maxCompletionTokens: number | null
  temperature: number | null
}

export interface PolicySourceConfig {
  name: string
  type: string
  url: string
  weight: number
}

export interface LlmRuntimeConfig {
  provider: string
  apiKey: string | null
  apiKeyEnv: string
  model: string
  baseUrl: string
  responseFormat: string
  strictJsonSchema: boolean
  thinking: string | null
  maxCompletionTokens: number | null
  temperature: number | null
  apiKeyConfigured: boolean
  apiKeySource: string
}

export interface RuntimeConfigSnapshot {
  dataId: string
  group: string
  llm: LlmRuntimeConfig
  policySources: PolicySourceConfig[]
  updatedAt: string
}

export interface TrendPromptRequest {
  documentTitle: string
  documentType: string
  sourceOrganization: string
  publishedAt: string
  sourceUrl: string
  contentExcerpt: string
  focusThemes: string[]
  knownCompanies: string[]
}

export interface TrendPromptPreview {
  name: string
  version: string
  modelInstruction: string
  userPrompt: string
  outputSchema: Record<string, JsonValue>
  qualityChecklist: string[]
  guardrails: string[]
}

export interface TrendAnalysisResponse {
  recordId: number | null
  cached: boolean
  provider: string
  model: string
  promptName: string
  promptVersion: string
  responseId: string | null
  analysis: Record<string, JsonValue>
  usage: Record<string, JsonValue>
  analyzedAt: string
}

export interface TrendAnalysisHistoryItem {
  recordId: number
  analysisDate: string
  documentTitle: string
  sourceOrganization: string | null
  publishedAt: string | null
  sourceUrl: string | null
  promptVersion: string
  provider: string
  model: string
  overallSummary: string | null
  overallConfidence: string | null
  nextAction: string | null
  analyzedAt: string
}

export interface ApiErrorBody {
  code: string
  message: string
  timestamp: string
  fields: Record<string, string>
}
