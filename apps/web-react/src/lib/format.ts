import type { ApiErrorBody, ValuationContextState } from '../types'

// ===== 数字 / 价格 / 日期格式化 =====

export function formatNumber(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return Number(value).toFixed(2)
}

export function formatPerSharePrice(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  const price = Number(value)
  if (!Number.isFinite(price) || price <= 0 || price > 100000) return '待复核'
  return `${price.toFixed(2)} 元/股`
}

export function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return Number(value).toFixed(1)
}

export function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${Number(value).toFixed(2)}%`
}

export function formatRatioPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${(Number(value) * 100).toFixed(2)}%`
}

export function formatValuationState(state: ValuationContextState) {
  return {
    CHEAP: '相对便宜',
    FAIR: '估值中性',
    STRETCHED: '预期偏高',
    DISTORTED: '盈利口径失真',
    MISSING: '估值待补'
  }[state]
}

// 金额：亿元
export function formatAmount(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${(Number(value) / 1e8).toFixed(2)} 亿`
}

export function changeClass(value: number | null | undefined) {
  if (value === null || value === undefined) return ''
  if (value > 0) return 'price-up'
  if (value < 0) return 'price-down'
  return ''
}

// 带正负号的百分比（涨跌幅）
export function formatSignedPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  const n = Number(value)
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toFixed(2)}%`
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '—'
  // 兼容带时间的 ISO 串
  return value.slice(0, 10)
}

export function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 19)
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date)
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find((part) => part.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}:${get('second')}`
}

export function extractErrorMessage(error: unknown) {
  const maybeError = error as {
    response?: { data?: ApiErrorBody | { message?: string }; status?: number }
    message?: string
  }
  const data = maybeError.response?.data
  if (data && typeof data === 'object' && 'message' in data && typeof data.message === 'string') {
    return data.message
  }
  return maybeError.message ?? '请求失败'
}
