import type { ShortTermLimitUpStock } from '../types'

export interface ShortTermRelatedBoard {
  code: string
  name: string
}

export interface ShortTermCatchUpCandidate {
  symbol: string
  name: string
  latestPrice: number
  changePercent: number
  amount: number | null
  turnoverRate: number | null
  marketTimestamp: string
  return5: number | null
  returnPercentile: number | null
  returnGapToLeader: number | null
  middle: boolean
  historyStatus: 'VALID' | 'UNKNOWN'
  evidence: string[]
  contraryEvidence: string[]
  dataGaps: string[]
}

export interface ShortTermCatchUpSnapshot {
  tradeDate: string
  industry: string
  leaderSymbol: string
  boardCode: string | null
  boardName: string | null
  fetchedAt: string
  marketTimestamp: string | null
  status: 'SELECT_BOARD' | 'READY' | 'DATA_GAP' | 'UNAVAILABLE'
  leader: ShortTermLimitUpStock | null
  relatedBoards: ShortTermRelatedBoard[]
  quoteSource: string | null
  expectedMemberCount: number
  fetchedMemberCount: number
  validQuoteCount: number
  historyValidCount: number
  historyCoveragePercent: number | null
  risingCount: number | null
  sealedCount: number | null
  eligibleCandidateCount: number
  displayedCount: number
  truncatedCount: number
  historyDates: string[]
  nextTradingDate: string | null
  candidates: ShortTermCatchUpCandidate[]
  evidence: string[]
  contraryEvidence: string[]
  dataGaps: string[]
}

export interface ShortTermCatchUpFollowUpObservation {
  id: string
  tradeDate: string
  symbol: string
  leaderSymbol: string
  observedPrice: number
}

export interface ShortTermCatchUpFollowUpResult extends ShortTermCatchUpFollowUpObservation {
  followUpDate: string | null
  status: 'WAITING' | 'EXPIRED' | 'UNKNOWN' | 'OBSERVED'
  latestPrice: number | null
  quoteTradeDate: string | null
  marketTimestamp: string | null
  priceChangePercent: number | null
  explanation: string
  dataGaps: string[]
}

export interface ShortTermCatchUpFollowUpSnapshot {
  fetchedAt: string
  results: ShortTermCatchUpFollowUpResult[]
  dataGaps: string[]
}
