import axios from 'axios'
import type { MarketThemeDetail, MarketThemeSnapshot } from '../types/marketThemes'

export function fetchMarketThemes() {
  return axios.get<MarketThemeSnapshot>('/api/short-term/market-themes', { timeout: 30000 })
    .then(response => response.data)
}

export function fetchMarketThemeDetail(code: string, tradeDate: string) {
  return axios.get<MarketThemeDetail>(`/api/short-term/market-themes/${encodeURIComponent(code)}`,
    { params: { date: tradeDate }, timeout: 60000 }).then(response => response.data)
}
