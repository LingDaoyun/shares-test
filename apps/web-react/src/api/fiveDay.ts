import axios from 'axios'
import type {
  FiveDayAttestation,
  FiveDayHistory,
  FiveDayExecutionCheck,
  FiveDayStrategy,
  FiveDayScope,
  FiveDaySnapshot,
  FiveDayTradeSchedule
} from '../types/fiveDay'

const http = axios.create({ baseURL: '/api/five-day', timeout: 120000 })

export function fetchFiveDaySnapshot(scope: FiveDayScope) {
  return http.get<FiveDaySnapshot>('/snapshot', { params: { ...scope, strategy: scope.strategy ?? 'v1' } }).then((response) => response.data)
}

export function startFiveDayScan(scope: FiveDayScope) {
  return http.post<FiveDaySnapshot>('/scan', undefined, { params: { ...scope, strategy: scope.strategy ?? 'v1' } }).then((response) => response.data)
}

export function fetchFiveDayHistory(strategy: FiveDayStrategy = 'v1') {
  return http.get<FiveDayHistory>('/history', { params: { strategy } }).then((response) => response.data)
}

export function refreshFiveDayHistory(strategy: FiveDayStrategy = 'v1') {
  return http.post<FiveDayHistory>('/history/refresh', undefined, { params: { strategy } }).then((response) => response.data)
}

export function createFiveDayAttestation(symbol: string, scope: FiveDayScope) {
  return http.post<FiveDayAttestation>(`/attestation/${encodeURIComponent(symbol)}`, undefined, {
    params: { ...scope, strategy: scope.strategy ?? 'v1' }
  }).then((response) => response.data)
}

export function fetchFiveDayTradeSchedule(entryDate: string): Promise<FiveDayTradeSchedule> {
  return http.get<FiveDayTradeSchedule>('/trade-schedule', {
    params: { entryDate }
  }).then((response) => response.data)
}

export function checkFiveDayExecution(symbol: string, scope: FiveDayScope) {
  return http.post<FiveDayExecutionCheck>(`/execution-check/${encodeURIComponent(symbol)}`, undefined, {
    params: { ...scope, strategy: scope.strategy ?? 'v1' }
  }).then((response) => response.data)
}
