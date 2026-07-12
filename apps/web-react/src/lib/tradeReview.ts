const shanghaiDateTimeFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23'
})

export function formatShanghaiDateTimeLocal(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const parts = shanghaiDateTimeFormatter.formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}`
}

export function parseShanghaiDateTimeLocal(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value)
  if (!match) return null
  const normalized = `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:${match[6] ?? '00'}`
  const date = new Date(`${normalized}+08:00`)
  if (Number.isNaN(date.getTime()) || formatShanghaiDateTimeLocal(date.toISOString()) !== normalized) return null
  return date.toISOString()
}

export function extractTradeMutationError(error: unknown) {
  const responseData = (error as { response?: { data?: unknown } })?.response?.data
  if (responseData && typeof responseData === 'object') {
    const body = responseData as { fields?: unknown; message?: unknown }
    if (body.fields && typeof body.fields === 'object' && !Array.isArray(body.fields)) {
      const fieldMessages = Object.entries(body.fields as Record<string, unknown>)
        .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
        .map(([, message]) => typeof message === 'string' ? message.trim() : '')
        .filter((message, index, messages) => Boolean(message) && messages.indexOf(message) === index)
      if (fieldMessages.length) return fieldMessages.join('；')
    }
    if (typeof body.message === 'string' && body.message.trim()) return body.message.trim()
  }
  return '请求失败，请稍后重试'
}
