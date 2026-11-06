import type { NewsCategory } from '../../types/news'

const beijingDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })
const beijingTime = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

export function newsCategory(category: NewsCategory) {
  return category === 'OFFICIAL' ? '官方发布' : '财经新闻'
}

export function newsTimestamp(value: string | null) {
  if (!value) return NaN
  const text = value.trim().replace(' ', 'T')
  const withZone = /^\d{4}-\d{2}-\d{2}$/.test(text) ? `${text}T00:00:00+08:00`
    : /(?:Z|[+-]\d{2}:\d{2})$/i.test(text) ? text : `${text}+08:00`
  return Date.parse(withZone)
}

export function newsDay(value: string | null) {
  const timestamp = newsTimestamp(value)
  return Number.isFinite(timestamp) ? beijingDate.format(timestamp) : null
}

export function newsDate(value: string | null) {
  const timestamp = newsTimestamp(value)
  if (!Number.isFinite(timestamp)) return '日期未知'
  return /^\d{4}-\d{2}-\d{2}$/.test(value ?? '') ? value! : beijingTime.format(timestamp)
}
