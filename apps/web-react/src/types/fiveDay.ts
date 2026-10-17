export type FiveDayStrategy = 'v1' | 'v2'

export interface FiveDayScope {
  strategy?: FiveDayStrategy
  includeGrowth: boolean
  includeStar: boolean
}

export interface FiveDayRules {
  holdingDays: number
  rangeDays: number
  recentTurnoverDays: number
  priorTurnoverDays: number
  flowDays: number
  maxRangePercent: number
  maxAbsPriceChangePercent: number
  minCenterChangePercent: number
  minTurnoverRatio: number
  minPositiveFlowDays: number
  minAverageAmount: number
  maxEntryPremiumPercent: number
  maxInitialRiskPercent: number
  stopBufferPercent: number
  roundTripCostBps: number
  maximumReviewed: number
}

export interface FiveDayBar {
  symbol: string
  tradeDate: string
  open: number
  close: number
  high: number
  low: number
  volume: number
  amount: number | null
  turnoverRate: number | null
}

export interface FiveDayCandidate {
  symbol: string
  name: string
  industry: string | null
  board: string
  signalClose: number
  rangeHigh: number
  rangeLow: number
  rangePercent: number
  priceChangePercent: number
  centerChangePercent: number
  recentTurnover: number
  priorTurnover: number
  turnoverRatio: number
  averageAmount: number
  mainNetInflow: number | null
  mainNetInflowRatio: number | null
  positiveFlowDays: number
  flowDataComplete: boolean
  matchesFundFlow: boolean
  flowSourceUrl: string | null
  stopPrice: number
  maxEntryPrice: number
  entryDate: string | null
  plannedExitDate: string | null
  evidence: string[]
  dataGaps: string[]
  bars: FiveDayBar[]
}

export interface FiveDayCoverage {
  universeCount: number
  priceQualifiedCount: number
  reviewedCount: number
  technicalQualifiedCount: number
  fundFlowQualifiedCount: number
  missingDataCount: number
  unreviewedCount: number
}

export interface FiveDaySnapshot extends FiveDayScope {
  status: 'IDLE' | 'SCANNING' | 'PREPARING' | 'READY' | 'FAILED'
  ruleVersion: string
  signalDate: string | null
  generatedAt: string | null
  forwardEligible: boolean
  message: string | null
  rules: FiveDayRules | null
  coverage: FiveDayCoverage | null
  candidates: FiveDayCandidate[]
  dataGaps: string[]
  context?: FiveDayContext | null
}

export interface FiveDayOutcome {
  fixedStatus: string
  riskStatus: string
  entryDate: string | null
  entryPrice: number | null
  plannedExitDate: string | null
  fixedExitDate: string | null
  fixedExitPrice: number | null
  fixedGrossReturnPercent: number | null
  fixedNetReturnPercent: number | null
  riskExitDate: string | null
  riskExitPrice: number | null
  riskNetReturnPercent: number | null
  maxAdverseExcursionPercent: number | null
  details: string[]
}

export interface FiveDayHistoryRow {
  observationId: string
  decision?: string | null
  signalDate: string
  capturedAt: string
  forwardEligible: boolean
  symbol: string
  name: string
  flowDataComplete: boolean
  matchesFundFlow: boolean
  outcome: FiveDayOutcome | null
}

export interface FiveDayCohort {
  label: string
  samples: number
  completed: number
  unavailable: number
  meanFixedNetReturnPercent: number | null
  profitablePercent: number | null
  meanRiskNetReturnPercent: number | null
}

export interface FiveDayHistory {
  rows: FiveDayHistoryRow[]
  cohorts: FiveDayCohort[]
  notes: string[]
}

export interface FiveDayTradeSchedule {
  entryDate: string
  plannedExitDate: string | null
  holdingDay: number | null
  status: string
  detail: string
}

export interface FiveDayAttestation {
  symbol: string
  recommendedAt: string
  attestationToken: string
}

export interface FiveDayMarket {
  state: string
  expectedCount: number
  coveredCount: number
  coveragePercent: number | null
  advancingPercent: number | null
  medianChangePercent: number | null
  severeDeclinePercent: number | null
  reasons: string[]
}

export interface FiveDaySector {
  industry: string
  state: string
  rank: number
  memberCount: number
  coveredCount: number
  coveragePercent: number | null
  return3Percent: number | null
  return5Percent: number | null
  excess3Percent: number | null
  excess5Percent: number | null
  advancing5Percent: number | null
  volumeExpansionPercent: number | null
  symbols: string[]
  reasons: string[]
}

export interface FiveDayTurning {
  confirmed: boolean
  closeVsMa5Percent: number | null
  closePositionPercent: number | null
  reasons: string[]
}

export interface FiveDayEvidence {
  kind: string
  title: string
  url: string | null
  source: string
  publishedAt: string | null
  observedAt: string | null
  interpretation: string
  temporalStatus: string
}

export interface FiveDayEventReview {
  status: string
  evidence: FiveDayEvidence[]
  notes: string[]
}

export interface FiveDayCandidateAssessment {
  symbol: string
  industry: string | null
  decision: string
  turning: FiveDayTurning | null
  events: FiveDayEventReview | null
  policies: FiveDayEvidence[]
  reasons: string[]
}

export interface FiveDayContext {
  assessedAt: string | null
  market: FiveDayMarket
  sectors: FiveDaySector[]
  assessments: FiveDayCandidateAssessment[]
  policies: FiveDayEvidence[]
  policyStatus: string
  notes: string[]
}

export interface FiveDayExecutionCheck {
  symbol: string
  status: 'PASSED' | 'WATCH' | 'BLOCKED' | 'UNKNOWN'
  checkedAt: string | null
  priceState: string
  events: FiveDayEventReview | null
  notes: string[]
}
