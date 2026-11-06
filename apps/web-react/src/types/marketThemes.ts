import type { ShortTermLimitUpStock } from '../types'

export interface MarketTheme {
  code: string
  name: string
  changePercent: number | null
  amount: number | null
  advancing: number | null
  declining: number | null
  unchanged: number | null
  advancingRatioPercent: number | null
  marketTimestamp: string | null
}

export interface MarketThemeSnapshot {
  tradeDate: string | null
  fetchedAt: string
  status: 'READY' | 'PARTIAL' | 'STALE' | 'UNAVAILABLE'
  source: string
  totalConceptCount: number
  excludedStructuralCount: number
  validQuoteCount: number
  themes: MarketTheme[]
  dataGaps: string[]
}

export interface MarketThemeMember {
  symbol: string
  name: string
  latestPrice: number | null
  changePercent: number | null
  amount: number | null
  turnoverRate: number | null
  marketTimestamp: string | null
}

export interface MarketThemeDetail {
  tradeDate: string | null
  fetchedAt: string
  theme: MarketTheme
  membersSource: string
  membershipComplete: boolean
  memberCount: number | null
  limitUpCount: number | null
  maxConsecutiveBoards: number | null
  limitUpFetchedAt?: string | null
  limitUpStocks: ShortTermLimitUpStock[]
  members: MarketThemeMember[]
  dataGaps: string[]
}
