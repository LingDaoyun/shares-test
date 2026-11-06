export type NewsCategory = 'MEDIA' | 'OFFICIAL'

export interface FinancialNewsItem {
  id: string
  category: NewsCategory
  title: string
  source: string
  url: string
  publishedAt: string | null
  summary: string | null
}

export interface NewsSourceStatus {
  id: string
  name: string
  category: NewsCategory
  url: string
  status: 'READY' | 'EMPTY' | 'FAILED'
  itemCount: number
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  message: string | null
}

export interface NewsAnalysis {
  itemId: string
  summary: string
  affectedSectors: string[]
  transmission: string
  watchPoints: string[]
  risks: string[]
  evidenceUrl: string
  analyzedAt: string | null
  dataGaps: string[]
}

export interface FinancialNewsSnapshot {
  status: 'IDLE' | 'RUNNING' | 'READY' | 'PARTIAL' | 'FAILED'
  startedAt: string | null
  completedAt: string | null
  items: FinancialNewsItem[]
  sources: NewsSourceStatus[]
  analyses: Record<string, NewsAnalysis>
  dataGaps: string[]
}
