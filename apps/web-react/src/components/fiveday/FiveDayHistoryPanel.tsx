import { useCallback, useEffect, useRef, useState } from 'react'
import { changeClass, extractErrorMessage, formatDateTime, formatNumber, formatPercent, formatSignedPercent } from '../../lib/format'
import { fetchFiveDayHistory, refreshFiveDayHistory } from '../../api/fiveDay'
import type { FiveDayCohort, FiveDayHistory, FiveDayHistoryRow, FiveDayOutcome, FiveDayStrategy } from '../../types/fiveDay'
import { Tag } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { DataTable, type Column } from '../ui/DataTable'
import { Loader } from '../ui/Loader'
import { Pagination } from '../ui/Pagination'
import { DecisionTag } from './FiveDayContextPanel'
import { flowLabel } from './FiveDayCandidateTable'

const OUTCOME_LABELS: Record<string, string> = {
  PENDING_REFRESH: '等待本地日 K 重算',
  COMPLETED: '已完成',
  COMPLETED_DEFERRED: '延期后完成',
  PENDING_ENTRY: '等待入场日',
  PENDING_EXIT: '等待退出',
  EXIT_DEFERRED: '退出延期',
  ENTRY_NOT_FILLED: '未假设入场成交',
  MISSING_DATA: '数据缺失',
  CALENDAR_UNAVAILABLE: '日历待核实'
}

export function FiveDayHistoryPanel({ snapshotKey, strategy = 'v1' }: { snapshotKey: string; strategy?: FiveDayStrategy }) {
  const [history, setHistory] = useState<FiveDayHistory | null>(null)
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const requestGeneration = useRef(0)

  const load = useCallback(async (recalculate = false) => {
    const generation = ++requestGeneration.current
    setLoading(true)
    setRefreshing(recalculate)
    setError(null)
    try {
      const result = await (recalculate ? refreshFiveDayHistory(strategy) : fetchFiveDayHistory(strategy))
      if (generation !== requestGeneration.current) return
      setHistory(result)
      setPage(1)
    } catch (caught) {
      if (generation === requestGeneration.current) setError(extractErrorMessage(caught))
    } finally {
      if (generation === requestGeneration.current) {
        setLoading(false)
        setRefreshing(false)
      }
    }
  }, [strategy])

  useEffect(() => {
    void load()
    return () => { requestGeneration.current += 1 }
  }, [load, snapshotKey])

  const cohortColumns: Column<FiveDayCohort>[] = [
    { key: 'label', title: '同一覆盖样本分组' },
    { key: 'samples', title: '前瞻样本数' },
    { key: 'completed', title: '固定五日已完成' },
    { key: 'unavailable', title: '固定五日未完成 / 不可用' },
    { key: 'meanFixedNetReturnPercent', title: '固定五日计划平均净收益', render: (cohort) => <ReturnValue value={cohort.meanFixedNetReturnPercent} /> },
    { key: 'meanRiskNetReturnPercent', title: '风险退出平均净收益', render: (cohort) => <ReturnValue value={cohort.meanRiskNetReturnPercent} /> },
    { key: 'profitablePercent', title: '固定五日正收益占比', render: (cohort) => formatPercent(cohort.profitablePercent) }
  ]

  const rowColumns: Column<FiveDayHistoryRow>[] = [
    {
      key: 'signal', title: '信号 / 样本',
      render: (row) => (
        <div>
          <div className="tabular mb-1">{row.signalDate}</div>
          <Tag tone={row.forwardEligible ? 'brand' : 'neutral'}>{row.forwardEligible ? '前瞻' : '非前瞻 · 不纳统计'}</Tag>
        </div>
      )
    },
    {
      key: 'stock', title: '观察股',
      render: (row) => (
        <div>
          <div className="font-semibold text-ink-900">{row.name} <span className="text-xs font-normal text-ink-500">{row.symbol}</span></div>
          <div className="mt-1 text-xs text-ink-500">{strategy === 'v2' ? <DecisionTag decision={row.decision} /> : flowLabel(row)}</div>
        </div>
      )
    },
    {
      key: 'entry', title: '情景入场 / 第 5 日计划',
      render: (row) => (
        <div className="tabular text-xs leading-6">
          <div>{row.outcome?.entryDate || '日期待核实'} · {row.outcome?.entryPrice == null ? '无入场价格' : `${formatNumber(row.outcome.entryPrice)} 元`}</div>
          <div className="text-ink-500">第 5 日 {row.outcome?.plannedExitDate || '日历待核实'}</div>
        </div>
      )
    },
    { key: 'fixed', title: '固定五日计划 · 净收益', render: (row) => <OutcomeCell outcome={row.outcome} model="fixed" /> },
    { key: 'risk', title: '风险退出 · 净收益', render: (row) => <OutcomeCell outcome={row.outcome} model="risk" /> },
    {
      key: 'details', title: '缺失 / 延期 / 依据',
      render: (row) => (
        <details className="max-w-[330px] text-xs leading-6">
          <summary className="cursor-pointer text-brand-600">查看情景说明</summary>
          <div className="mt-2 text-ink-500">留存于 {formatDateTime(row.capturedAt)}</div>
          <div className="break-all text-ink-400">观察记录 {row.observationId}</div>
          {row.outcome?.details?.length ? (
            <ul className="mt-1 list-disc pl-4 text-ink-600">{row.outcome.details.map((detail, index) => <li key={index}>{detail}</li>)}</ul>
          ) : <p className="mt-1 text-ink-500">尚无情景说明。</p>}
        </details>
      )
    }
  ]

  return (
    <Card title="前瞻观察·日线情景估算">
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Button type="button" disabled={loading} onClick={() => void load()}>读取记录</Button>
        <Button type="button" loading={refreshing} disabled={loading} onClick={() => void load(true)}>按本地日 K 重算</Button>
      </div>
      <p className="text-xs leading-6 text-ink-500">
        此处展示当前版本、全部板块范围的留存记录，独立于上方候选开关。仅在下一交易日 09:15 前完成并固化的样本进入前瞻统计；过期补扫仅供研究。
        同信号日、板块范围和版本的首个完整成功快照固化，不用后来数据覆盖；未完成扫描和关键覆盖失败不固化为完整样本。
        {strategy === 'v2' ? '配对比较后端留存的主线量价对照组与执行条件齐备子集。对照情景可能包含不可执行观察，包括继续观察及数据未知；情景收益不是实际收益。两组重叠，不能作为因果证明。' : '配对比较“资金数据完整的量价组”及其“资金条件合格子集”，两组重叠，含重复股票和相关交易，不是独立样本，也不是组合回测或组合收益。'}
        正收益占比是已完成样本的描述统计，不是未来上涨概率。
      </p>
      <p className="mt-2 text-xs leading-6 text-ink-500">
        两种情景各扣 20 bps（0.20%）往返成本假设，均不是成交凭证。重算只读取已存储的日 K；缺失数据不会按事后高点补造交易。
        未完成、未成交、缺失和延期保留原状态，不当作零收益。延期完成使用实际情景退出日，持有期可能超过 5 日，不能视为恰好五日收益。
      </p>
      {error ? <p role="alert" className="mt-3 text-sm text-red-700">读取或重算未完成：{error}</p> : null}
      {loading && !history ? <div className="py-5"><Loader text="读取前瞻观察记录" /></div> : null}
      {history ? (
        <div className="mt-4 flex flex-col gap-4">
          <DataTable columns={cohortColumns} data={history.cohorts} rowKey={(cohort, index) => `${cohort.label}-${index}`} tableClassName="min-w-[980px]" emptyText="尚无可进入配对统计的前瞻样本" />
          {history.notes.length > 0 ? <ul className="list-disc space-y-1 pl-5 text-xs leading-6 text-ink-500">{history.notes.map((note, index) => <li key={index}>{note}</li>)}</ul> : null}
          <div className="border-t border-line-soft pt-4">
            <DataTable columns={rowColumns} data={history.rows.slice((page - 1) * pageSize, page * pageSize)} rowKey={(row) => `${row.observationId}-${row.symbol}`} tableClassName="min-w-[1200px]" emptyText="尚无已固化的观察记录" />
            {history.rows.length > 0 ? <Pagination page={page} pageSize={pageSize} total={history.rows.length} pageSizeOptions={[10, 20, 50]} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(1) }} /> : null}
          </div>
        </div>
      ) : null}
    </Card>
  )
}

function ReturnValue({ value }: { value: number | null | undefined }) {
  return <span className={`tabular font-semibold ${changeClass(value)}`}>{value == null ? '—' : formatSignedPercent(value)}</span>
}

function OutcomeCell({ outcome, model }: { outcome: FiveDayOutcome | null; model: 'fixed' | 'risk' }) {
  if (!outcome) return <Tag tone="warning">数据待补</Tag>
  const status = model === 'fixed' ? outcome.fixedStatus : outcome.riskStatus
  const completed = status === 'COMPLETED' || status === 'COMPLETED_DEFERRED'
  const value = model === 'fixed' ? outcome.fixedNetReturnPercent : outcome.riskNetReturnPercent
  const date = model === 'fixed' ? outcome.fixedExitDate : outcome.riskExitDate
  const price = model === 'fixed' ? outcome.fixedExitPrice : outcome.riskExitPrice
  const missing = status === 'MISSING_DATA' || status === 'CALENDAR_UNAVAILABLE' || status === 'EXIT_DEFERRED'
  return (
    <div className="space-y-1 text-xs leading-6">
      <div><Tag tone={completed ? 'success' : missing ? 'warning' : 'neutral'}>{OUTCOME_LABELS[status] || '状态待核实'}</Tag></div>
      <div><ReturnValue value={completed ? value : null} /></div>
      {date ? <div className="tabular text-ink-500">{date} · {price == null ? '价格待补' : `${formatNumber(price)} 元`}</div> : null}
      {status === 'COMPLETED_DEFERRED' ? <div className="text-amber-800">原第 5 日计划，实际延期退出</div> : null}
    </div>
  )
}
