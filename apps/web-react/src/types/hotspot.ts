export interface HotspotNewsItem {
  id: string
  title: string
  source: string
  url: string
  publishedAt: string | null
  excerpt: string | null
}

export interface HotspotCompanyEvidence {
  sourceType: string
  sourceTitle: string
  excerpt: string
  url: string | null
  confidence: number
}

export interface HotspotRecommendation {
  symbol: string
  name: string
  industry: string | null
  recommendationReason: string
  benefitMechanism: string
  relationType: string
  evidence: HotspotNewsItem[]
  companyEvidence: HotspotCompanyEvidence[]
  risks: string[]
  confidence: string
  latestPrice: number | null
  changePercent: number | null
  quoteUrl: string | null
  quoteAsOf: string | null
  firstSeenAt: string | null
  firstSeenPrice: number | null
  firstSeenQuoteAsOf: string | null
}

export interface HotspotEvent {
  id: string
  title: string
  eventType: string
  industries: string[]
  products: string[]
  summary: string
  catalyst: string
  heatScore: number
  confidence: string
  sources: HotspotNewsItem[]
  recommendations: HotspotRecommendation[]
  risks: string[]
  firstSeenAt: string | null
  updatedAt: string | null
}

export interface HotspotSnapshot {
  status: 'IDLE' | 'RUNNING' | 'READY' | 'PARTIAL' | 'FAILED'
  generatedAt: string | null
  startedAt: string | null
  lastAttemptAt: string | null
  nextAutomaticRefreshAt: string | null
  refreshAvailableAt: string | null
  summary: string
  newsCount: number
  companyCount: number
  news: HotspotNewsItem[]
  events: HotspotEvent[]
  dataGaps: string[]
}
