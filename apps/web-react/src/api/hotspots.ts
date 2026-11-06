import axios from 'axios'
import type { HotspotSnapshot } from '../types/hotspot'

export function fetchHotspotSnapshot() {
  return axios.get<HotspotSnapshot>('/api/hotspots/current', { timeout: 30000 }).then(response => response.data)
}

export function refreshHotspots() {
  return axios.post<HotspotSnapshot>('/api/hotspots/refresh', undefined, { timeout: 30000 }).then(response => response.data)
}
