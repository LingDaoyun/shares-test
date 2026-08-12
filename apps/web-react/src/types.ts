// 当前 React 前端消费的后端数据契约。

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
  storage: 'database'
  llmRevision: number
  policySourcesRevision: number
  llm: LlmRuntimeConfig
  policySources: PolicySourceConfig[]
  updatedAt: string
}

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
  companyPool: PolicyCompanyCandidate[]
}

export interface PolicyCompanyCandidate {
  symbol: string
  companyName: string
  industry: string | null
  chainSegment: string
  researchRole: string
  leadershipRationale: string[]
  financialQualityScore: number
  financialQualityLabel: string
  latestPrice: number | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  actionLabel: string
  dataGaps: string[]
}

export interface TechEvidenceItem {
  title: string
  summary: string
  url: string | null
  weight: number
}

export interface TechTrackingRuleSet {
  coreMaxPe: number
  coreMaxPb: number
  hardMaxPe: number
  hardMaxPb: number
  pullbackWatchPercent: number
  stopLossPercent: number
  maxSinglePositionPercent: number
}

export interface TechScoreBreakdown {
  policyScore: number
  earningsScore: number
  valuationScore: number
  tradingDisciplineScore: number
  finalScore: number
}

export type TradingAdviceAction = 'HOLD' | 'ADD' | 'LIGHT_TRIAL' | 'NEXT_WATCH' | 'WAIT_PULLBACK' | 'BATCH_SELL' | 'SELL_ALL' | 'WAIT'

export interface TradingAdvice {
  action: TradingAdviceAction
  actionLabel: string
  confidence: number
  summary: string
  reasons: string[]
  riskControls: string[]
}

export interface EvidenceCompleteness {
  score: number
  status: string
  statusLabel: string
  allowsBuy: boolean
  presentEvidence: string[]
  missingEvidence: string[]
  riskControls: string[]
}

export interface PeerValuationBriefPeer {
  symbol: string
  companyName: string
  relationType: string
  peTtm: number | null
  pbRatio: number | null
  latestPrice: number | null
}

export interface PeerValuationBrief {
  available: boolean
  scopeLabel: string
  peerCount: number
  currentPe: number | null
  currentPb: number | null
  medianPe: number | null
  medianPb: number | null
  pePeerPercentile: number | null
  pbPeerPercentile: number | null
  peers: PeerValuationBriefPeer[]
  conclusions: string[]
  dataGaps: string[]
}

export interface AgentConsensusBrief {
  available: boolean
  consensusLabel: string
  consensusScore: number | null
  supportCount: number
  watchCount: number
  reviewCount: number
  vetoCount: number
  contrarianSummary: string
  requiredEvidence: string[]
  objections: string[]
  dataGaps: string[]
}

export interface RecommendationEvidenceBundle {
  symbol: string
  peerValuation: PeerValuationBrief
  agentConsensus: AgentConsensusBrief
  dataGaps: string[]
}

export interface WatchlistEntry {
  symbol: string
  companyName: string
  note: string
  lastActionLabel: string | null
  lastDecisionScore: number | null
  lastAnalyzedAt: string | null
  createdAt: string
  updatedAt: string
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
  generatedAt: string
}

export interface DecisionHistoryEntry {
  decisionId: string
  analysisId: string
  symbol: string
  sourceType: string
  actionStage: string
  actionLabel: string
  decisionScore: number | null
  ruleVersion: string
  dataAsOf: string
  recordedAt: string
}

export interface TradingSessionSnapshot {
  phase: string
  phaseLabel: string
  regularAuctionOpen: boolean
  closingDecisionWindow: boolean
  postCloseFixedPrice: boolean
  decisionTimeLabel: string
  rules: string[]
  warnings: string[]
}

export type ValuationContextState = 'CHEAP' | 'FAIR' | 'STRETCHED' | 'DISTORTED' | 'MISSING'
export type ValuationModel = 'STANDARD' | 'FINANCIAL' | 'CYCLICAL' | 'EARLY_GROWTH'

export interface ValuationContext {
  score: number
  state: ValuationContextState
  applicableModel: ValuationModel
  rawPe: number | null
  rawPb: number | null
  peReference: number
  pbReference: number
  industryPercentile: number | null
  historyPercentile: number | null
  normalizedEarningsUsed: boolean
  warnings: string[]
  evidence: string[]
}

export interface MarketScanRuleSet {
  scanLimit: number
  minAmount: number
  maxPe: number
  maxPb: number
  maxRiseForEntry: number
  maxSinglePositionPercent: number
  minFinancialScore: number
  excludeSideways: boolean
  includeNorthExchange: boolean
  allowChiNext: boolean
  mode: string
}

export interface MarketScanScoreBreakdown {
  valuationScore: number
  liquidityScore: number
  priceActionScore: number
  qualityProxyScore: number
  riskScore: number
  finalScore: number
}

export interface MarketScanTraceStep {
  step: string
  title: string
  summary: string
  findings: string[]
  sourceName: string | null
  sourceUrl: string | null
}

export interface LongTermFactorScores {
  financialQualityScore: number
  moatAndIndustryScore: number
  valuationExpectationScore: number
  capitalAllocationScore: number
  evidenceRiskScore: number
  overallScore: number
}

export interface LongTermFinancialQuality {
  sampleYears: number
  medianRoe: number | null
  roeReference: number | null
  roeReferenceMetYears: number
  positiveCashFlowYears: number
  cumulativeCashToProfitRatio: number | null
  grossMarginRange: number | null
  status: string
  statusLabel: string
  evidence: string[]
  dataGaps: string[]
}

export interface LongTermValuationExpectation {
  metricCode: 'IMPLIED_GROWTH' | 'IMPLIED_ROE'
  metricLabel: string
  impliedExpectationPercent: number | null
  evidenceExpectationPercent: number | null
  pessimisticValue: number | null
  baseValue: number | null
  optimisticValue: number | null
  discountToBasePercent: number | null
  targetMarginOfSafetyPercent: number
  entryReferencePrice: number | null
  normalizedEarningsUsed: boolean
  confidence: 'MEDIUM' | 'LOW'
  confidenceLabel: string
  evidence: string[]
  dataGaps: string[]
}

export interface LongTermPositionDiscipline {
  maxSinglePositionPercent: number
  maxTopFivePositionPercent: number
  trancheCount: number
  declineReviewTriggerPercent: number
  entryConditions: string[]
  addConditions: string[]
  reviewTriggers: string[]
}

export interface LongTermLogicAudit {
  quarterlyReview: string
  annualReview: string
  eventTriggers: string[]
  invalidationConditions: string[]
  reentryRule: string
}

export interface LongTermInvestmentAssessment {
  strategyVersion: string
  modelCode: 'STANDARD' | 'CYCLICAL' | 'FINANCIAL'
  modelLabel: string
  status: string
  statusLabel: string
  factorScores: LongTermFactorScores
  financialQuality: LongTermFinancialQuality
  valuation: LongTermValuationExpectation
  positionDiscipline: LongTermPositionDiscipline
  logicAudit: LongTermLogicAudit
  evidence: string[]
  risks: string[]
  dataGaps: string[]
}

export interface LongTermIndustryContext {
  industry: string
  modelCode: string
  modelLabel: string
  cycleType: string
  cycleTypeLabel: string
  evidence: string[]
  dataGaps: string[]
}

export interface LongTermPolicyDocument {
  title: string
  source: string
  publishedAt: string
  url: string
  impact: 'SUPPORT' | 'CONSTRAINT' | 'NEUTRAL'
  relevanceScore: number
  matchedKeywords: string[]
  rationale: string
}

export interface LongTermPolicyEvidence {
  documents: LongTermPolicyDocument[]
  dataGaps: string[]
}

export interface LongTermCycleContext {
  businessStage: string
  businessStageLabel: string
  priceStage: string
  priceStageLabel: string
  confidence: number
  provisional: boolean
  supportingEvidence: string[]
  contraryEvidence: string[]
  dataGaps: string[]
}

export interface LongTermCandidateContext {
  symbol: string
  companyName: string
  market: string
  industry: string
  industryContext: LongTermIndustryContext
  policyEvidence: LongTermPolicyEvidence
  cycleContext: LongTermCycleContext
  generatedAt: string
  dataGaps: string[]
}

export interface MarketScanCandidate {
  rank: number
  symbol: string
  name: string
  market: string | null
  industry: string | null
  latestPrice: number | null
  marketTimestamp: string | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  valuationContext: ValuationContext
  longTermAssessment: LongTermInvestmentAssessment | null
  score: MarketScanScoreBreakdown
  screeningAction: string
  screeningActionLabel: string
  reason: string
  todayAdvice: TradingAdvice
  tags: string[]
  strengths: string[]
  risks: string[]
  dataGaps: string[]
  evidenceCompleteness: EvidenceCompleteness
  evidenceBundle: RecommendationEvidenceBundle
  trace: MarketScanTraceStep[]
}

export interface UniversalScreenStageStats {
  stage: string
  label: string
  inputCount: number
  passedCount: number
  excludedCount: number
  deferredCount: number
}

export interface UniversalScreenCoverage {
  requestedCount: number
  expectedCount: number
  fetchedCount: number
  missingCount: number
  complete: boolean
  source: string
  fetchedAt: string
}

export interface UniversalScreenExclusion {
  symbol: string | null
  name: string | null
  stage: string
  reason: string
  evidence: string[]
}

export interface MarketScanReport {
  scope: string
  universeCount: number
  reviewedCount: number
  candidateCount: number
  quoteNote: string
  coverage: UniversalScreenCoverage
  methodology: string[]
  ruleSet: MarketScanRuleSet
  stageStats: UniversalScreenStageStats[]
  candidates: MarketScanCandidate[]
  exclusionsSample: UniversalScreenExclusion[]
  tradeCaptureTokens: Record<string, string>
  generatedAt: string
}

export interface ShortTermRuleSet {
  scanLimit: number
  klineLimit: number
  minAmount: number
  maxPe: number
  maxPb: number
  minVolumeRatio: number
  maxEntryRisePercent: number
  maxDistanceToMa20Percent: number
  minFinancialScore: number
  allowChiNext: boolean
}

export type ShortTermGoldenCrossState =
  | 'NONE'
  | 'APPROACHING'
  | 'FORMING'
  | 'CONFIRMED'
  | 'ESTABLISHED'
  | 'UNAVAILABLE'

export interface ShortTermGoldenCrossSnapshot {
  ruleVersion: string
  state: ShortTermGoldenCrossState
  stateLabel: string
  crossDate: string | null
  tradingDaysSinceCross: number | null
  ma5Ma10SpreadPercent: number | null
  spreadTrend: 'NARROWING' | 'WIDENING' | 'FLAT' | 'UNAVAILABLE'
  maAlignment: 'BEARISH' | 'CONVERGING' | 'MA5_ABOVE_MA10' | 'BULLISH_STACK' | 'UNAVAILABLE'
  priorityTier: number
  evidenceStatus: 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE'
}

export interface ShortTermTechnicalSnapshot {
  tradeDate: string | null
  ma5: number | null
  ma10: number | null
  ma20: number | null
  ma60: number | null
  ma20SlopePercent: number | null
  ma60SlopePercent: number | null
  previousHigh20: number | null
  previousHigh60: number | null
  breakoutFromPreviousHigh20Percent: number | null
  previousRange20Percent: number | null
  high120: number | null
  low120: number | null
  volumeRatio5: number | null
  volumeRatio20: number | null
  rangePosition60: number | null
  rangePosition120: number | null
  distanceToMa20Percent: number | null
  drawdownFrom120HighPercent: number | null
  todayAmplitudePercent: number | null
  consecutiveAboveMa20Days: number
  rightSideSignal: string
  goldenCross?: ShortTermGoldenCrossSnapshot | null
  momentumQuality?: ShortTermMomentumQuality | null
  supportReversal?: ShortTermSupportReversalSignal | null
}

export interface ShortTermSupportReversalSignal {
  state: 'CONFIRMED' | 'OBSERVATION' | 'NONE' | 'UNAVAILABLE'
  stateLabel: string
  score: number
  lowerShadowPercent: number | null
  bodyPercent: number | null
  upperShadowPercent: number | null
  closeLocationPercent: number | null
  supportType: 'MA5' | 'MA10' | 'MA20' | 'PREVIOUS_HIGH20' | null
  supportPrice: number | null
  supportReclaimed: boolean
  trendQualified: boolean
  volumeQualified: boolean
  turnoverQualified: boolean
  provisional: boolean
  reasons: string[]
  dataGaps: string[]
}

export interface ShortTermMomentumQuality {
  turnoverRatePercent: number | null
  turnoverBand: 'PREFERRED' | 'OBSERVATION' | 'INSUFFICIENT' | 'OVERHEATED' | 'UNAVAILABLE'
  turnoverScore: number
  latestUpperShadowPercent: number | null
  bullishUpperShadowMedian3Percent: number | null
  closeLocationPercent: number | null
  closeStrengthLabel: string
  closeStrengthScore: number
  provisional: boolean
  extremeUpperShadow: boolean
  dataGaps: string[]
}

export interface ShortTermFinancialSnapshot {
  reportDate: string | null
  dataType: string | null
  roe: number | null
  operatingCashFlowPerShare: number | null
  grossMargin: number | null
  revenueGrowth: number | null
  netProfitGrowth: number | null
  averageRoe: number | null
  positiveCashFlowYears: number
  qualityScore: number
  statusLabel: string
  dataGaps: string[]
}

export interface ShortTermScoreBreakdown {
  technicalScore: number
  goldenCrossScore: number
  volumeScore: number
  turnoverScore: number
  closeStrengthScore: number
  supportReversalScore?: number
  marketHeatScore: number
  valuationScore: number
  financialScore: number
  riskPenalty: number
  finalScore: number
  stageAdjustment?: number
  mainNetInflowRatio?: number | null
  largeOrderNetInflowRatio?: number | null
  buyPressureScore?: number | null
  fundFlowAdjustment?: number | null
  overheadPressureReliefScore?: number | null
  technicalRankingScore?: number | null
  v2RankingScore?: number | null
  chipContributionScore?: number | null
  v3RankingScore?: number | null
  v2Rank?: number | null
  v3Rank?: number | null
  rankDelta?: number | null
  relativeStrengthContribution?: number | null
  industryLeadershipContribution?: number | null
  marketHeatContribution?: number | null
  crossSectionAdjustment?: number | null
  rankingScore?: number
  volatilityContribution?: number | null
  visibleRankingAdjustment?: number | null
}

export interface ShortTermRelativeStrength {
  return5: number | null
  return10: number | null
  return20: number | null
  marketPercentile5: number | null
  marketPercentile10: number | null
  marketPercentile20: number | null
  industryPercentile5: number | null
  industryPercentile10: number | null
  industryPercentile20: number | null
  marketSampleCount: number
  industrySampleCount: number
  compositeScore: number | null
  contribution: number
  dataGaps: string[]
}

export interface ShortTermIndustryLeadership {
  industry: string | null
  cohortSize: number
  amountRank: number
  percentile: number | null
  contribution: number
  evidence: string
}

export interface ShortTermVolatilityQuality {
  atrPercent: number | null
  distanceToMa20Atr: number | null
  contractionRatio5To20: number | null
  breakoutExpansionRatio: number | null
  breakoutFromHigh20Atr: number | null
  state: string
  label: string
  contractionBreakout: boolean
  contribution: number
  dataGaps: string[]
}

export interface ShortTermSignalProfile {
  primaryFamily: string
  primaryLabel: string
  activeFamilies: string[]
  evidence: string[]
  dataGaps: string[]
}

export interface ShortTermMarketRegime {
  state: string
  label: string
  breadthPercent: number | null
  medianChangePercent: number | null
  averageAbsoluteChangePercent: number | null
  advancingTurnoverSharePercent: number | null
  limitUpRatioPercent: number | null
  limitDownRatioPercent: number | null
  sampleCount: number
  maxAction: string
  explanation: string
  dataGaps: string[]
}

export type ChipVerificationStatus =
  | 'VERIFIED'
  | 'SINGLE_SOURCE'
  | 'CONFLICT'
  | 'STALE'
  | 'INSUFFICIENT'

export type ChipPricePosition = 'BELOW' | 'AROUND' | 'ABOVE'

export interface ChipDistributionBucket {
  lowPrice: number
  highPrice: number
  price: number
  chipRatioPercent: number
  normalizedHeight: number
}

export interface ChipConcentrationZone {
  rank: number
  lowPrice: number
  highPrice: number
  peakPrice: number
  chipRatioPercent: number
  distanceToCurrentPricePercent: number
  positionToCurrentPrice: ChipPricePosition
}

export interface ShortTermChipSnapshot {
  dataQuality: 'VALID' | 'INSUFFICIENT'
  calculationMode: 'COMPLETED_BAR' | 'INTRADAY_ESTIMATE'
  localTradeDate: string | null
  externalTradeDate: string | null
  averageCost: number | null
  cost5: number | null
  cost15: number | null
  cost50: number | null
  cost85: number | null
  cost95: number | null
  winnerRatePercent: number | null
  overheadChipRatioPercent: number | null
  cost70Low: number | null
  cost70High: number | null
  cost70ConcentrationPercent: number | null
  cost90Low: number | null
  cost90High: number | null
  cost90ConcentrationPercent: number | null
  distanceToAverageCostPercent: number | null
  priorHighPrice: number | null
  priorHighZoneResidualRatioPercent: number | null
  turnoverSincePriorHighPercent: number | null
  costPositionScore: number | null
  concentrationScore: number | null
  overheadReliefScore: number | null
  priorHighDigestionScore: number | null
  chipStructureScore: number | null
  verificationStatus: ChipVerificationStatus
  verificationLabel: string
  verificationCoefficient: number | null
  contributionScore: number | null
  averageCostDeviation: number | null
  cost70BandOverlap: number | null
  winnerRateDeviation: number | null
  distributionBuckets?: ChipDistributionBucket[] | null
  concentrationZones?: ChipConcentrationZone[] | null
  dominantPeakPrice?: number | null
  dominantZoneLow?: number | null
  dominantZoneHigh?: number | null
  dominantZoneChipRatioPercent?: number | null
  currentPricePosition?: ChipPricePosition | null
  nearestOverheadZone?: ChipConcentrationZone | null
  modelVersion: string
  dataGaps: string[]
}

export interface ShortTermWeightProfile {
  preliminaryValuation: number
  preliminaryLiquidity: number
  preliminaryNonChase: number
  preliminaryHeat: number
  finalGoldenCross: number
  finalVolume: number
  finalTurnover: number
  finalCloseStrength: number
  modelVersion?: string
  weightMeaning?: string
}

export interface ShortTermEvidence {
  title: string
  summary: string
  url: string | null
  weight: number
}

export interface ShortTermTailSignal {
  status: string
  statusLabel: string
  afterTailConfirm: boolean
  tradeDate: string | null
  latestMinute: string | null
  latestPrice: number | null
  tailStartPrice: number | null
  changeFromTailConfirmPercent: number | null
  drawdownFromTailHighPercent: number | null
  closeVsAveragePricePercent: number | null
  tailAmount: number | null
  tailAmountRatioPercent: number | null
  score: number | null
  reasons: string[]
  riskControls: string[]
}

export interface ShortTermOpenScenario {
  code: string
  label: string
  condition: string
  action: string
  invalidationRules: string[]
}

export interface ShortTermTradePlan {
  strategyLabel: string
  status: 'ACTIONABLE' | 'BLOCKED'
  blockedReasons: string[]
  entryWindow: string
  validUntil: string
  referenceEntryPrice: number | null
  entryLow: number | null
  entryHigh: number | null
  maxPositionRatio: number | null
  maxT2PositionRatio: number | null
  firstTargetPercent: number | null
  firstTargetPrice: number | null
  firstReductionRatio: number | null
  secondTargetPercent: number | null
  secondTargetPrice: number | null
  hardStopPercent: number | null
  hardStopPrice: number | null
  trailingDrawdownPercent: number | null
  trailingStopRule: string
  normalExitDate: string
  normalExitTime: string
  absoluteExitDate: string
  absoluteExitTime: string
  t2ExtensionConditions: string[]
  openScenarios: ShortTermOpenScenario[]
  analysisBasis: string[]
  riskWarnings: string[]
}

export interface ShortTermCoverageSnapshot {
  expectedCount: number
  fetchedCount: number
  missingCount: number
  coverageRatio: number
  executionReliable: boolean
  source: string
  fetchedAt: string | null
}

export interface QuoteFreshnessSnapshot {
  status: string
  statusLabel: string
  realtimeSession: boolean
  blocksRealtimeDecision: boolean
  tradeDate: string | null
  marketTimestamp: string | null
  ageSeconds: number | null
  reason: string
}

export interface ShortTermCandidate {
  rank: number
  symbol: string
  name: string
  market: string | null
  industry: string | null
  latestPrice: number | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  quoteFreshness: QuoteFreshnessSnapshot
  valuationContext: ValuationContext
  phase: string
  phaseLabel: string
  action: string
  actionLabel: string
  reason: string
  todayAdvice: TradingAdvice
  tailSignal: ShortTermTailSignal
  score: ShortTermScoreBreakdown
  technical: ShortTermTechnicalSnapshot
  financial: ShortTermFinancialSnapshot
  buyZoneLow: number | null
  buyZoneHigh: number | null
  stopPrice: number | null
  strengths: string[]
  risks: string[]
  entryRules: string[]
  exitRules: string[]
  evidenceCompleteness: EvidenceCompleteness
  evidence: ShortTermEvidence[]
  tradePlan: ShortTermTradePlan | null
  chip?: ShortTermChipSnapshot | null
  relativeStrength?: ShortTermRelativeStrength | null
  industryLeadership?: ShortTermIndustryLeadership | null
  volatilityQuality?: ShortTermVolatilityQuality | null
  signalProfile?: ShortTermSignalProfile | null
}

export interface ShortTermTechnicalReviewCoverage {
  quotePreselectedCount: number
  requestedCount: number
  sufficientCount: number
  missingCount: number
  coverageRatio: number
}

export interface ShortTermCrossSectionContext {
  marketUniverseCount: number
  industryCount: number
  relativeStrengthSampleCount: number
  basis: string
  dataGaps: string[]
}

export interface ShortTermReport {
  scope: string
  universeCount: number
  reviewedCount: number
  klineReviewedCount: number
  candidateCount: number
  quoteNote: string
  tradingSession: TradingSessionSnapshot
  methodology: string[]
  ruleSet: ShortTermRuleSet
  weightProfile: ShortTermWeightProfile
  candidates: ShortTermCandidate[]
  hotDirections: ShortTermHotDirection[]
  marketSentiment: ShortTermMarketSentiment
  marketFundDirection?: ShortTermMarketFundDirection | null
  exclusions: ShortTermRiskExclusion[]
  tradeCaptureTokens: Record<string, string>
  coverage: ShortTermCoverageSnapshot
  reviewedSymbols: string[]
  dataCutoffAt: string | null
  generatedAt: string
  technicalReviewCoverage?: ShortTermTechnicalReviewCoverage | null
  crossSectionContext?: ShortTermCrossSectionContext | null
  marketRegime?: ShortTermMarketRegime | null
}

export interface ShortTermValidationCohortRequest {
  signalFamily: string
  marketRegime: string
  horizon: 'T1' | 'T2'
}

export interface ShortTermValidationBatchRequest {
  cohorts: ShortTermValidationCohortRequest[]
}

export interface ShortTermValidationSummary {
  ruleVersion: string
  signalFamily: string
  marketRegime: string
  horizon: 'T1' | 'T2'
  status: 'AVAILABLE' | 'INSUFFICIENT_SAMPLE' | 'VALIDATION_DISABLED'
  minimumSampleCount: number
  sampleCount: number
  positiveRatePercent: number | null
  averageNetReturnPercent: number | null
  medianNetReturnPercent: number | null
  averageMfePercent: number | null
  averageMaePercent: number | null
}

export type ShortTermSnapshotStatus =
  | 'RUNNING'
  | 'PRESELECT_READY'
  | 'FINAL_PENDING'
  | 'FINAL_READY'
  | 'CACHE_PREVIEW'
  | 'NO_TRADE'
  | 'DATA_BLOCKED'
  | 'FAILED'

export interface ShortTermScheduledSnapshot {
  tradeDate: string
  stage: 'PRESELECT' | 'FINAL' | 'READINESS_GUARD' | 'MANUAL'
  status: ShortTermSnapshotStatus
  strategyVersion: string
  message: string
  dataCutoffAt: string | null
  startedAt?: string | null
  completedAt: string | null
  blockedReasons: string[]
  report: ShortTermReport | null
  reportPayloadHash?: string | null
  payloadCommittedByAt?: string | null
}

export interface ShortTermMarketSentiment {
  phase: string
  score: number
  advancing: number
  declining: number
  limitUpLike: number
  limitDownLike: number
  breadthPercent: number
  explanation: string
}

export interface ShortTermIndustryFundDirection {
  code: string
  name: string
  mainNetInflow: number | null
  mainNetInflowRatio: number | null
  superLargeNetInflow: number | null
  largeNetInflow: number | null
  advancing: number
  declining: number
  constituentCount: number
  concentrationPercent: number | null
  sourceUrl: string | null
}

export interface ShortTermMarketFundDirection {
  topInflows: ShortTermIndustryFundDirection[]
  topOutflows: ShortTermIndustryFundDirection[]
  coveredIndustryCount: number
  expectedIndustryCount: number
  coverageRatio: number | null
  tradeDate: string | null
  fetchedAt: string | null
  sourceName: string
  dataGaps: string[]
}

export type ShortTermScanJobState = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface ShortTermScanJobStatus {
  jobId: string
  status: ShortTermScanJobState
  tradeDate: string
  resultStatus: ShortTermSnapshotStatus
  strategyVersion: string
  blockedReasons: string[]
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  message: string
  report: ShortTermReport | null
}

export interface ShortTermHotDirection {
  code: string
  label: string
  heatScore: number
  averageChangePercent: number | null
  positiveRatioPercent: number | null
  totalAmount: number | null
  sampleCount: number
  leaders: string[]
  evidence: string
}

export interface ShortTermRiskExclusion {
  symbol: string
  name: string
  market: string | null
  industry: string | null
  latestPrice: number | null
  marketTimestamp: string | null
  changePercent: number | null
  amount: number | null
  peTtm: number | null
  pbRatio: number | null
  category: string
  reason: string
  evidence: string
  sourceUrl: string | null
}

export interface BacktestRuleSet {
  lookbackDays: number
  holdingDays: number
  minVolumeRatio: number
  maxVolumeRatio: number
  maxDistanceToMa20Percent: number
  minMa20SlopePercent: number
  stopLossPercent: number
  takeProfitPercent: number
  commissionPercent: number
  stampDutyPercent: number
  slippagePercent: number
  limitMovePercent: number
  minRange60Percent: number
  maxRange60Percent: number
}

export interface BacktestTrade {
  symbol: string
  signalDate: string
  entryDate: string
  exitDate: string
  entryPrice: number | null
  exitPrice: number | null
  grossReturnPercent: number
  returnPercent: number
  maxDrawdownPercent: number
  totalCostPercent: number
  holdingDays: number
  exitReason: string
  signalEvidence: string[]
}

export interface BacktestSummary {
  symbolCount: number
  tradeCount: number
  winCount: number
  winRatePercent: number
  averageReturnPercent: number
  averageMaxDrawdownPercent: number
  bestReturnPercent: number | null
  worstReturnPercent: number | null
  profitFactor: number | null
  conclusion: string
}

export interface BacktestSymbolResult {
  symbol: string
  klineCount: number
  tradeCount: number
  summary: BacktestSummary
  trades: BacktestTrade[]
  dataGaps: string[]
}

export interface BacktestReport {
  scope: string
  methodology: string[]
  ruleSet: BacktestRuleSet
  symbols: string[]
  summary: BacktestSummary
  results: BacktestSymbolResult[]
  generatedAt: string
}

export interface OvernightBacktestRuleSet {
  lookbackDays: number
  firstTargetPercent: number
  secondTargetPercent: number
  hardStopPercent: number
  maxHoldingTradingDays: number
  commissionPercent: number
  stampDutyPercent: number
  slippagePercent: number
  limitMovePercent: number
  minVolumeRatio: number
  maxDistanceToMa20Percent: number
  trailingDrawdownPercent: number
}

export interface OvernightBacktestTrade {
  symbol: string
  signalDate: string
  proxyEntryPrice: number | null
  t1Date: string
  t2Date: string | null
  exitDate: string
  exitPrice: number | null
  firstTargetHit: boolean
  secondTargetHit: boolean
  exitLegs: OvernightBacktestExitLeg[]
  weightedExitPrice: number | null
  netReturnPercent: number
  maxRunupPercent: number
  maxDrawdownPercent: number
  gapPercent: number | null
  holdingTradingDays: number
  commissionCostPercent: number
  stampDutyCostPercent: number
  slippageCostPercent: number
  totalCostPercent: number
  exitReason: string
}

export interface OvernightBacktestExitLeg {
  exitDate: string
  positionRatio: number
  executablePrice: number
  reason: string
}

export type OvernightBacktestSymbolStatus =
  | 'SOURCE_FAILED'
  | 'INSUFFICIENT_HISTORY'
  | 'NO_SIGNAL'
  | 'OK'

export interface OvernightBacktestSymbolResult {
  symbol: string
  status: OvernightBacktestSymbolStatus
  klineCount: number
  sampleCount: number
  dataGaps: string[]
}

export interface OvernightBacktestSummary {
  symbolCount: number
  sampleCount: number
  positiveRatePercent: number
  averageReturnPercent: number
  medianReturnPercent: number
  averageRunupPercent: number
  averageDrawdownPercent: number
  firstTargetRatePercent: number
  secondTargetRatePercent: number
  hardStopRatePercent: number
  timeStopRatePercent: number
  gapDownRatePercent: number
  sampleStart: string | null
  sampleEnd: string | null
  conclusion: string
}

export interface OvernightBacktestReport {
  scope: string
  validationScope: string[]
  unreplayedGates: string[]
  methodology: string[]
  ruleSet: OvernightBacktestRuleSet
  symbols: string[]
  status: 'OK' | 'PARTIAL' | 'DATA_BLOCKED'
  message: string
  summary: OvernightBacktestSummary
  results: OvernightBacktestSymbolResult[]
  trades: OvernightBacktestTrade[]
  generatedAt: string
}

export interface TechTrackedStock {
  rank: number
  symbol: string
  name: string
  themeCode: string
  themeName: string
  industry: string | null
  latestPrice: number | null
  marketTimestamp: string | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  score: TechScoreBreakdown
  action: string
  actionLabel: string
  reason: string
  todayAdvice: TradingAdvice
  strengths: string[]
  risks: string[]
  entryRules: string[]
  exitRules: string[]
  evidence: TechEvidenceItem[]
}

export interface TechTrackingReport {
  scope: string
  universeCount: number
  candidateCount: number
  quoteNote: string
  methodology: string[]
  policySignals: TechEvidenceItem[]
  ruleSet: TechTrackingRuleSet
  candidates: TechTrackedStock[]
  tradeCaptureTokens: Record<string, string>
  generatedAt: string
}

export interface MispricingEvidenceItem {
  title: string
  summary: string
  url: string | null
  weight: number
}

export interface MispricingRuleSet {
  hotOverheatThreshold: number
  maxPeForValue: number
  maxPbForValue: number
  minQualityScore: number
  preferredPullbackPercent: number
  stopLossPercent: number
  scanLimit: number
}

export interface StyleHeatSnapshot {
  hotThemeName: string
  heatScore: number
  valuationPressure: number
  crowdingPressure: number
  riskLabel: string
  signals: string[]
}

export interface MispricingScoreBreakdown {
  hotOverheatScore: number
  qualityScore: number
  valuationDiscountScore: number
  cashflowDefenseScore: number
  rotationTimingScore: number
  finalScore: number
}

export interface MispricingReviewResult {
  status: string
  statusLabel: string
  conclusion: string
  verifiedFindings: string[]
  blockers: string[]
  sources: MispricingEvidenceItem[]
}

export interface MispricedAsset {
  rank: number
  symbol: string
  name: string
  assetGroup: string
  industry: string | null
  latestPrice: number | null
  marketTimestamp: string | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  score: MispricingScoreBreakdown
  action: string
  actionLabel: string
  reason: string
  todayAdvice: TradingAdvice
  strengths: string[]
  risks: string[]
  entryRules: string[]
  exitRules: string[]
  evidence: MispricingEvidenceItem[]
  evidenceCompleteness: EvidenceCompleteness
  evidenceBundle: RecommendationEvidenceBundle
  review: MispricingReviewResult
}

export interface MispricingReport {
  scope: string
  universeCount: number
  candidateCount: number
  quoteNote: string
  methodology: string[]
  styleHeat: StyleHeatSnapshot
  ruleSet: MispricingRuleSet
  policySignals: MispricingEvidenceItem[]
  candidates: MispricedAsset[]
  tradeCaptureTokens: Record<string, string>
  generatedAt: string
}

export interface CycleTrialEvidence {
  title: string
  summary: string
  url: string | null
  weight: number
}

export interface CycleTrialRuleSet {
  leftTrialScoreThreshold: number
  rightAddScoreThreshold: number
  maxChaseRisePercent: number
  minVolumeRatioForBreakout: number
  stopLossPercent: number
  pullbackZonePercent: number
}

export interface CycleTechnicalSnapshot {
  tradeDate: string | null
  ma5: number | null
  ma10: number | null
  ma20: number | null
  ma60: number | null
  previousHigh20: number | null
  previousHigh60: number | null
  low20: number | null
  low60: number | null
  volumeRatio5: number | null
  volumeRatio20: number | null
  rangePosition60: number | null
  closeNearHigh: number | null
  reboundFrom20LowPercent: number | null
  distanceToMa20Percent: number | null
}

export interface CycleTrialScoreBreakdown {
  catalystScore: number
  priceLocationScore: number
  reversalScore: number
  volumeScore: number
  valuationScore: number
  finalScore: number
}

export interface CyclePeerValuationCompany {
  symbol: string
  name: string
  industry: string | null
  peTtm: number | null
  pbRatio: number | null
  amount: number | null
  quoteUrl: string | null
}

export interface CyclePeerValuationSnapshot {
  industry: string | null
  averagePeTtm: number | null
  averagePbRatio: number | null
  candidatePeDiscountPercent: number | null
  candidatePbDiscountPercent: number | null
  valuationAdvantage: boolean
  conclusion: string
  peers: CyclePeerValuationCompany[]
}

export interface CycleTrialCandidate {
  rank: number
  symbol: string
  name: string
  assetGroup: string
  cycleDriver: string
  industry: string | null
  latestPrice: number | null
  marketTimestamp: string | null
  changePercent: number | null
  peTtm: number | null
  pbRatio: number | null
  peerValuation: CyclePeerValuationSnapshot | null
  amount: number | null
  phase: string
  phaseLabel: string
  action: string
  actionLabel: string
  reason: string
  todayAdvice: TradingAdvice
  score: CycleTrialScoreBreakdown
  technical: CycleTechnicalSnapshot
  trialBuyZoneLow: number | null
  trialBuyZoneHigh: number | null
  stopPrice: number | null
  catalysts: string[]
  risks: string[]
  entryRules: string[]
  exitRules: string[]
  evidence: CycleTrialEvidence[]
}

export interface CycleTrialReport {
  scope: string
  universeCount: number
  candidateCount: number
  quoteNote: string
  methodology: string[]
  ruleSet: CycleTrialRuleSet
  candidates: CycleTrialCandidate[]
  tradeCaptureTokens: Record<string, string>
  generatedAt: string
}

export interface DailySignalEvidence {
  title: string
  summary: string
  url: string | null
  weight: number
}

export interface DailyMarketContext {
  region: string
  tradeDate: string
  summary: string
  riskTags: string[]
  positionCap: string
  source: string
}

export interface StrategyPlaybook {
  name: string
  displayName: string
  category: string
  description: string
  coreRules: number[]
  requiredTools: string[]
  triggerRules: string[]
  exitRules: string[]
  scoringImpact: string
}

export interface DailyDecisionSignal {
  rank: number
  symbol: string
  name: string
  market: string
  sourceType: string
  sourceLabel: string
  action: string
  actionLabel: string
  confidence: number
  score: number | null
  recommendedPrice: number | null
  marketTimestamp: string | null
  horizon: string
  marketPhase: string
  todayAdvice: TradingAdvice
  strategyTags: string[]
  reason: string
  riskSummary: string
  catalystSummary: string
  watchConditions: string[]
  evidence: DailySignalEvidence[]
}

export interface DailySignalReport {
  scope: string
  sourceProject: string
  sourceCommit: string
  marketContext: DailyMarketContext
  actionCounts: Record<string, number>
  strategyPlaybooks: StrategyPlaybook[]
  signals: DailyDecisionSignal[]
  tradeCaptureTokens: Record<string, string>
  generatedAt: string
}

export interface ApiErrorBody {
  code: string
  message: string
  timestamp: string
  fields: Record<string, string>
}

export type TradeSide = 'BUY' | 'SELL'
export type TradeCaseStatus = 'PLANNED' | 'HOLDING' | 'CLOSED' | 'CANCELLED'

export interface CreateTradeCaseRequest {
  attestationToken: string
}

export interface ManualTradeFillRequest {
  symbol: string
  companyName: string
  fill: UpsertTradeFillRequest
}

export interface UpsertTradeFillRequest {
  side: TradeSide
  executedAt: string
  price: number
  quantity: number
}

export interface TradeFill {
  fillId: string
  side: TradeSide
  executedAt: string
  price: number
  quantity: number
}

export interface TradeFillView extends TradeFill {
  createdAt: string
  updatedAt: string
}

export interface TradeLedgerSummary {
  latestPrice: number | null
  positionQuantity: number
  averageCost: number
  realizedProfit: number
  unrealizedProfit: number | null
  totalProfit: number | null
}

export interface TradeOutcomeView {
  baselineType: string
  horizon: string
  baselinePrice: number | null
  evaluationPrice: number | null
  evaluationDate: string | null
  returnPct: number | null
  maxRunupPct: number | null
  maxDrawdownPct: number | null
  status: string
  sourceName: string | null
  marketTimestamp: string | null
  calculatedAt: string
}

export type TradeOutcomeSnapshot = TradeOutcomeView

export interface TradeCaseSummary {
  caseId: string
  symbol: string
  companyName: string
  sourceModule: string
  recommendationAction: string
  recommendationScore: number | null
  ruleVersion: string
  recommendedPrice: number
  recommendedAt: string
  recommendationVerified: boolean
  status: TradeCaseStatus
  ledger: TradeLedgerSummary
  outcomes: TradeOutcomeView[]
  createdAt: string
  updatedAt: string
}

export interface TradeCaseDetail extends TradeCaseSummary {
  decisionId: string | null
  recommendationPayload: unknown
  fills: TradeFillView[]
  outcomeWarnings: string[]
}

export interface StrategyFeedbackSummary {
  sourceModule: string
  ruleVersion: string
  horizon: string
  sampleCount: number
  positiveCount: number
  positiveRate: number | null
  averageReturn: number | null
  medianReturn: number | null
  averageRunup: number | null
  averageDrawdown: number | null
  averageExecutionDeviation: number | null
  executionDeviationSampleCount: number
  sampleStart: string | null
  sampleEnd: string | null
  promptEligible: boolean
  adjustmentEligible: boolean
  reliabilityAdjustment: number | null
}

export interface V2SignalResponse {
  ledgerId: string
  strategyCode: string
  strategyVersion: string
  symbol: string
  companyName: string
  decisionAt: string
  dataCutoffAt: string
  candidateStage: string
  action: string
  positionLimit: number | null
  entryCondition: string
  invalidCondition: string
  rankScore: number | null
  dataConfidence: number | null
  historicalHitRate: number | null
  riskReward: number | null
  evidenceSummary: string[]
  blockedReasons: string[]
  context: Record<string, string>
  sourceQuality: string
  signalProvenance: string
  replayPayload: Record<string, unknown>
}

export interface V2SampleSignalParams {
  symbol: string
  companyName?: string
  strategyCode?: string
}

export interface AgentEvidenceFinding {
  agentName: string
  role: string
  vote: 'SUPPORT' | 'OPPOSE' | 'ABSTAIN'
  sourceUrl: string
  sourceTitle: string
  publishedAt: string | null
  evidenceHash: string
  claim: string
}

export interface AgentEvidenceReview {
  findings: AgentEvidenceFinding[]
  supportCount: number
  opposeCount: number
  abstainCount: number
  sourceOverlapCount: number
  hasConflict: boolean
  warnings: string[]
}

export interface V2StrategyBundleResponse {
  symbol: string
  companyName: string
  generatedAt: string
  longTermSignals: V2SignalResponse[]
  shortRightSideSignal: V2SignalResponse
  agentEvidenceReview: AgentEvidenceReview
}

export interface V2StrategyBundleParams {
  symbol: string
  companyName?: string
  industry?: string
  valuationDiscountScore?: number
  qualityScore?: number
  moatScore?: number
  profitabilityScore?: number
  cashFlowScore?: number
  cyclePositionScore?: number
  cycleRecoveryScore?: number
  industryLeaderScore?: number
  policyCatalystScore?: number
  liquidityScore?: number
  hotDirection?: string
  tradingCheckpoint?: string
  marketHotScore?: number
  rightSideStructureScore?: number
  supplyAbsorptionScore?: number
  volumeBreakoutScore?: number
  shrinkRiseScore?: number
  fundamentalFloorScore?: number
  crowdingRiskScore?: number
  goldenCrossState?: ShortTermGoldenCrossState
  goldenCrossTradingDays?: number
  goldenCrossPriorityTier?: number
  recommendationToken?: string
}
