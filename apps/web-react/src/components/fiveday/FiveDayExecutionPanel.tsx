import { useEffect, useRef, useState } from 'react'
import { checkFiveDayExecution } from '../../api/fiveDay'
import { extractErrorMessage, formatDateTime } from '../../lib/format'
import type { FiveDayExecutionCheck, FiveDayScope } from '../../types/fiveDay'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { EventReview } from './FiveDayContextPanel'

const PRICE_LABELS: Record<string, string> = {
  WITHIN_LIMITS: '价格在参考边界内', PASSED: '价格检查通过', UNKNOWN: '报价数据未知',
  ABOVE_ENTRY_LIMIT: '价格高于入场上限', BELOW_STOP: '价格触及或跌破失效位',
  OUTSIDE_LIMITS: '价格超出参考边界', STALE: '报价时点过期', STALE_QUOTE: '报价时点过期'
}

export function FiveDayExecutionPanel({ symbol, entryDate, scope }: { symbol: string; entryDate: string | null; scope: FiveDayScope }) {
  const [result, setResult] = useState<FiveDayExecutionCheck | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)
  const pending = useRef(false)
  useEffect(() => () => { generation.current += 1 }, [])

  const check = async () => {
    if (pending.current) return
    pending.current = true
    const current = ++generation.current
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      const next = await checkFiveDayExecution(symbol, scope)
      if (current === generation.current) setResult(next)
    } catch (caught) {
      if (current === generation.current) setError(extractErrorMessage(caught))
    } finally {
      if (current === generation.current) {
        pending.current = false
        setLoading(false)
      }
    }
  }

  const today = formatDateTime(new Date().toISOString()).slice(0, 10)
  const entryIsToday = entryDate === today
  const checkedToday = result?.checkedAt ? formatDateTime(result.checkedAt).slice(0, 10) === today : false
  const passed = result?.status === 'PASSED' && entryIsToday && checkedToday
  const label = passed ? '执行检查通过（仍非成交）' : result?.status === 'BLOCKED' ? '执行检查未通过' : result?.status === 'UNKNOWN' ? '执行检查数据未知' : '执行条件待核实'
  return <Card title="入场前独立检查">
    <p className="mb-3 text-xs leading-6 text-ink-500">核对计划入场日、前瞻资格、当日报价日期和时点、开盘与现价边界、市场环境和公司公告。本次检查不覆盖冻结观察，也不写入交易；真实成交需要另外录入。检查通过不代表一定能成交。</p>
    {entryDate && entryDate > today ? <p className="mb-3 text-xs text-amber-800">计划入场日尚未到来，可浏览当前证据，尚不能确认计划入场日的执行条件。</p> : null}
    <Button type="button" loading={loading} onClick={() => void check()}>检查入场条件</Button>
    {error ? <p role="alert" className="mt-3 text-sm text-red-700">执行检查未完成：{error}</p> : null}
    {result ? <div className="mt-4 space-y-3">
      <div className="flex flex-wrap items-center gap-3"><Tag tone={passed ? 'success' : result.status === 'BLOCKED' ? 'danger' : 'warning'}>{label}</Tag><span className="text-xs text-ink-500">检查时间 {formatDateTime(result.checkedAt)}</span></div>
      <p className="text-xs leading-6 text-ink-600">报价状态：{PRICE_LABELS[result.priceState] || result.priceState || '待核实'}。实时全市场环境的核实范围以本次检查说明为准。</p>
      {result.notes.length ? <ul className="list-disc space-y-1 pl-5 text-xs leading-6 text-ink-600">{result.notes.map((note, index) => <li key={index}>{note}</li>)}</ul> : null}
      {result.status === 'PASSED' && !passed ? <p className="text-xs text-amber-800">检查日期或计划入场日尚未对齐，尚不能确认计划入场日的执行条件。</p> : null}
      <EventReview review={result.events} />
    </div> : null}
  </Card>
}
