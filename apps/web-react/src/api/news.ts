import axios from 'axios'
import type { FinancialNewsSnapshot, NewsAnalysis } from '../types/news'

export function fetchFinancialNews() {
  return axios.get<FinancialNewsSnapshot>('/api/news/current', { timeout: 30000 }).then(response => response.data)
}

export function refreshFinancialNews() {
  return axios.post<FinancialNewsSnapshot>('/api/news/refresh', undefined, { timeout: 30000 }).then(response => response.data)
}

export function analyzeFinancialNews(id: string) {
  return axios.post<NewsAnalysis>(`/api/news/${encodeURIComponent(id)}/analysis`, undefined, { timeout: 360000 }).then(response => response.data)
}
