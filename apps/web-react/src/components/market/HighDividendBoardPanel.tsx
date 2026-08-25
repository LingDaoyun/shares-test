import { useCallback, useEffect, useMemo, useState } from 'react'
import { Coins, RefreshCw } from 'lucide-react'
import { fetchHighDividendBoard } from '../../api/client'
import { DetailOverlay } from '../ui/DetailOverlay'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { Spinner } from '../ui/Loader'
import { Tag } from '../ui/Badge'
import { changeClass, formatAmount, formatDateTime, formatNumber, formatRatioPercent, formatSignedPercent } from '../../lib/format'
import type { HighDividendBoardSnapshot, HighDividendStock } from '../../types'

/** 拉取基准：固定以 4% 下限取回样本，更细的阈值交给前端筛选。 */
const FETCH_FLOOR_PERCENT = 4
export const ALL_INDUSTRIES = ''

export interface DividendBoardFilters {
  minYieldPercent: number
  industry: string
  maxPb: number | null
}

export const DEFAULT_DIVIDEND_FILTERS: DividendBoardFilters = {
  minYieldPercent: 5,
  industry: ALL_INDUSTRIES,
  maxPb: null
}

export function HighDividendBoardPanel() {
  const [snapshot, setSnapshot] = useState<HighDividendBoardSnapshot | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const [filters, setFilters] = useState<DividendBoardFilters>(DEFAULT_DIVIDEND_FILTERS)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setSnapshot(await fetchHighDividendBoard(FETCH_FLOOR_PERCENT))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '高股息看板获取失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const stocks = useMemo(
    () => filterDividendStocks(snapshot?.stocks ?? [], filters),
    [snapshot, filters]
  )

  return (
    <>
      <HighDividendBoardEntry
        snapshot={snapshot}
        stocks={stocks}
        minYieldPercent={filters.minYieldPercent}
        loading={loading}
        error={error}
        onRefresh={() => void refresh()}
        onOpen={() => setOpen(true)}
      />
      <DetailOverlay
        open={open}
        title={
          <span className="inline-flex items-center gap-2">
            <Coins className="h-4 w-4 text-emerald-600" />
            高股息看板 · 全市场筛选
          </span>
        }
        subtitle={
          snapshot
            ? `${snapshot.reportYear} 年报口径 · 快照 ${formatDateTime(snapshot.generatedAt)}（股息率随数据源周期更新）`
            : undefined
        }
        onClose={() => setOpen(false)}
      >
        <HighDividendBoardDetail
          snapshot={snapshot}
          stocks={stocks}
          filters={filters}
          onFiltersChange={setFilters}
          loading={loading}
          error={error}
          onRefresh={() => void refresh()}
        />
      </DetailOverlay>
    </>
  )
}

export function filterDividendStocks(
  stocks: HighDividendStock[],
  filters: DividendBoardFilters
): HighDividendStock[] {
  const minYield = filters.minYieldPercent / 100
  return stocks.filter((stock) => {
    if (stock.dividendYield == null || stock.dividendYield < minYield) {
      return false
    }
    if (filters.industry !== ALL_INDUSTRIES && stock.industry !== filters.industry) {
      return false
    }
    if (filters.maxPb != null && (stock.pbRatio == null || stock.pbRatio > filters.maxPb)) {
      return false
    }
    return true
  })
}

interface HighDividendBoardEntryProps {
  snapshot: HighDividendBoardSnapshot | null
  stocks: HighDividendStock[]
  minYieldPercent: number
  loading: boolean
  error: string | null
  onRefresh: () => void
  onOpen: () => void
}

/** 长期价投页面内的一行式入口：高股息样本摘要 + 查看完整看板的按钮。 */
export function HighDividendBoardEntry({
  snapshot,
  stocks,
  minYieldPercent,
  loading,
  error,
  onRefresh,
  onOpen
}: HighDividendBoardEntryProps) {
  const summary = dividendBoardSummary(stocks)
  return (
    <Card>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <span className="inline-flex items-center gap-2 text-sm font-semibold text-ink-900">
          <Coins className="h-4 w-4 shrink-0 text-emerald-600" />
          高股息看板
        </span>
        {error ? (
          <span className="min-w-0 truncate text-xs text-rose-600">获取失败：{error}</span>
        ) : !snapshot && loading ? (
          <span className="inline-flex items-center gap-1.5 text-xs text-ink-400">
            <Spinner className="text-brand-500" />
            正在拉取全市场股息率
          </span>
        ) : snapshot && !snapshot.available ? (
          <span className="min-w-0 truncate text-xs text-amber-700">
            {snapshot.unavailableReason ?? '高股息数据不可用'}
          </span>
        ) : snapshot && snapshot.available && !summary ? (
          <span className="text-xs text-ink-400">当前筛选条件下暂无达标样本</span>
        ) : snapshot && summary ? (
          <span className="inline-flex min-w-0 flex-wrap items-center gap-2 text-xs text-ink-500">
            <Tag tone="success">
              股息率≥{formatThreshold(minYieldPercent / 100)}：{stocks.length} 家
            </Tag>
            <span className="truncate">
              平均 {formatRatioPercent(summary.averageYield)}
              {` · 最高 ${summary.topName} ${formatRatioPercent(summary.topYield)} · ${snapshot.reportYear} 年报`}
            </span>
          </span>
        ) : null}
        <span className="ml-auto inline-flex items-center gap-2">
          <Button
            variant="secondary"
            icon={<RefreshCw className="h-4 w-4" />}
            loading={loading}
            onClick={onRefresh}
          >
            刷新
          </Button>
          <Button variant="primary" disabled={!snapshot?.available} onClick={onOpen}>
            查看看板
          </Button>
        </span>
      </div>
    </Card>
  )
}

interface HighDividendBoardDetailProps {
  snapshot: HighDividendBoardSnapshot | null
  stocks: HighDividendStock[]
  filters: DividendBoardFilters
  onFiltersChange: (filters: DividendBoardFilters) => void
  loading: boolean
  error: string | null
  onRefresh: () => void
}

/** 浮层内的完整看板内容。 */
export function HighDividendBoardDetail({
  snapshot,
  stocks,
  filters,
  onFiltersChange,
  loading,
  error,
  onRefresh
}: HighDividendBoardDetailProps) {
  const summary = dividendBoardSummary(stocks)
  const industryOptions = useMemo(() => industryOptionsOf(snapshot?.stocks ?? []), [snapshot])
  return (
    <div className="flex flex-col gap-4">
      {error ? (
        <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs leading-relaxed text-rose-700">
          高股息看板获取失败：{error}
        </p>
      ) : null}
      {!error && !snapshot ? (
        <p className="py-6 text-center text-sm text-ink-400">
          {loading ? '正在拉取全市场股息率数据…' : '暂无数据，请点击刷新。'}
        </p>
      ) : null}
      {!error && snapshot && !snapshot.available ? (
        <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
          {snapshot.unavailableReason ?? '高股息数据不可用'}
        </p>
      ) : null}
      {!error && snapshot && snapshot.available ? (
        <>
          <FilterBar
            filters={filters}
            industryOptions={industryOptions}
            onFiltersChange={onFiltersChange}
            loading={loading}
            onRefresh={onRefresh}
          />
          {summary ? (
            <>
              <DividendSummary snapshot={snapshot} stocks={stocks} summary={summary} filters={filters} />
              <IndustryDistribution stocks={stocks} />
              <DividendTable stocks={stocks} />
            </>
          ) : (
            <p className="py-6 text-center text-sm text-ink-400">
              当前筛选条件下暂无达标样本，可放宽股息率/行业/PB 筛选后重试。
            </p>
          )}
          <p className="text-[11px] leading-relaxed text-ink-400">
            样本为沪深 A 股（不含北交所/新三板），基准取回股息率 ≥{FETCH_FLOOR_PERCENT}% 的股票后在前端筛选；
            股息率取自最新完整年报（{snapshot.reportYear}）的东方财富最新股息率口径，非盘中实时重算。
            风险筛除：分红超过当年利润（亏损仍分红或派息率超 100%）的样本已剔除，派息率 = 每股分红/每股收益（未含中期分红），
            显示“待核”表示分红方案无法解析需人工确认；股息率超过 30% 的长期停牌异常样本同样已剔除；设置 PB 上限后会剔除 PB 缺失样本。
            高股息样本仍可能包含一次性特别分红或周期顶部盈利，买入前需核对分红持续性与盈利质量。
            行情字段为拉取时点快照，仅作参考。
          </p>
          {snapshot.dataGaps.length ? (
            <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-relaxed text-amber-800">
              {snapshot.dataGaps.join('；')}
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  )
}

function FilterBar({
  filters,
  industryOptions,
  onFiltersChange,
  loading,
  onRefresh
}: {
  filters: DividendBoardFilters
  industryOptions: Array<[string, string]>
  onFiltersChange: (filters: DividendBoardFilters) => void
  loading: boolean
  onRefresh: () => void
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-2 border-b border-line-soft pb-3">
      <div className="flex flex-wrap items-end gap-3">
        <label>
          <span className="field-label">行业</span>
          <select
            className="field"
            value={filters.industry}
            onChange={(e) => onFiltersChange({ ...filters, industry: e.target.value })}
          >
            {industryOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <label>
          <span className="field-label">股息率 ≥(%)</span>
          <input
            className="field tabular"
            type="number"
            min={FETCH_FLOOR_PERCENT}
            max={30}
            step={0.5}
            value={filters.minYieldPercent}
            onChange={(e) => {
              const parsed = Number(e.target.value)
              if (Number.isFinite(parsed)) {
                onFiltersChange({
                  ...filters,
                  minYieldPercent: Math.min(30, Math.max(FETCH_FLOOR_PERCENT, parsed))
                })
              }
            }}
          />
        </label>
        <label>
          <span className="field-label">PB ≤(留空不限)</span>
          <input
            className="field tabular"
            type="number"
            min={0}
            step={0.1}
            value={filters.maxPb ?? ''}
            onChange={(e) => {
              const raw = e.target.value
              const parsed = raw === '' ? Number.NaN : Number(raw)
              onFiltersChange({ ...filters, maxPb: Number.isFinite(parsed) && parsed >= 0 ? parsed : null })
            }}
          />
        </label>
        <Button variant="secondary" onClick={() => onFiltersChange(DEFAULT_DIVIDEND_FILTERS)}>
          重置筛选
        </Button>
      </div>
      <Button
        variant="secondary"
        icon={<RefreshCw className="h-4 w-4" />}
        loading={loading}
        onClick={onRefresh}
      >
        刷新
      </Button>
    </div>
  )
}

interface DividendSummaryData {
  averageYield: number | null
  topYield: number | null
  topName: string
  industryCount: number
}

export function dividendBoardSummary(stocks: HighDividendStock[]): DividendSummaryData | null {
  if (!stocks.length) {
    return null
  }
  const yields = stocks.map((stock) => stock.dividendYield).filter((value): value is number => value != null)
  const averageYield = yields.length
    ? yields.reduce((sum, value) => sum + value, 0) / yields.length
    : null
  return {
    averageYield,
    topYield: stocks[0].dividendYield,
    topName: stocks[0].name,
    industryCount: new Set(stocks.map((stock) => stock.industry ?? '未分类')).size
  }
}

function industryOptionsOf(stocks: HighDividendStock[]): Array<[string, string]> {
  const counts = new Map<string, number>()
  for (const stock of stocks) {
    const industry = stock.industry ?? '未分类'
    counts.set(industry, (counts.get(industry) ?? 0) + 1)
  }
  const options: Array<[string, string]> = [[ALL_INDUSTRIES, '全部行业']]
  for (const [industry, count] of [...counts.entries()].sort((left, right) => right[1] - left[1])) {
    options.push([industry, `${industry}（${count}）`])
  }
  return options
}

function formatThreshold(thresholdYield: number) {
  return `${Number((thresholdYield * 100).toFixed(2))}%`
}

function DividendSummary({
  snapshot,
  stocks,
  summary,
  filters
}: {
  snapshot: HighDividendBoardSnapshot
  stocks: HighDividendStock[]
  summary: DividendSummaryData
  filters: DividendBoardFilters
}) {
  return (
    <dl className="grid grid-cols-2 border-t border-line-soft text-xs md:grid-cols-4">
      <Metric label="达标家数" value={`${stocks.length} 家`} />
      <Metric label="股息率下限" value={`≥${formatThreshold(filters.minYieldPercent / 100)}`} />
      <Metric label="平均股息率" value={formatRatioPercent(summary.averageYield)} />
      <Metric
        label="最高股息率"
        value={stocks.length ? `${stocks[0].name} ${formatRatioPercent(stocks[0].dividendYield)}` : '待补充'}
      />
      <Metric label="覆盖行业" value={`${summary.industryCount} 个`} />
      <Metric label="PB 上限" value={filters.maxPb == null ? '不限' : `≤${filters.maxPb}`} />
      <Metric label="报告期" value={`${snapshot.reportYear} 年报`} />
      <Metric label="样本口径" value="沪深 A 股" />
    </dl>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="border-b border-r border-line-soft px-2 py-2">
      <dt className="text-[11px] text-ink-400">{label}</dt>
      <dd className="mt-0.5 break-words font-semibold text-ink-900">{value}</dd>
    </div>
  )
}

function IndustryDistribution({ stocks }: { stocks: HighDividendStock[] }) {
  const stats = useMemo(() => industryStatsOf(stocks), [stocks])
  if (!stats.length) {
    return <p className="text-xs leading-relaxed text-ink-500">行业聚合暂无数据。</p>
  }
  const max = Math.max(...stats.map((stat) => stat.stockCount))
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-ink-900">行业分布（达标家数 Top {stats.length}）</h4>
      <div className="grid grid-cols-1 gap-1.5 md:grid-cols-2">
        {stats.map((stat) => (
          <div key={stat.industry} className="rounded-xl border border-line-soft bg-white/80 px-3 py-2">
            <div className="flex items-center justify-between gap-2">
              <span className="truncate text-sm font-semibold text-ink-900">{stat.industry}</span>
              <Tag tone="success">
                {stat.stockCount} 家 · 最高 {formatRatioPercent(stat.topYield)}
              </Tag>
            </div>
            <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-line">
              <div
                className="h-full rounded-full bg-emerald-400"
                style={{ width: `${max > 0 ? (stat.stockCount / max) * 100 : 0}%` }}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

interface ClientIndustryStat {
  industry: string
  stockCount: number
  topYield: number | null
}

function industryStatsOf(stocks: HighDividendStock[]): ClientIndustryStat[] {
  const byIndustry = new Map<string, HighDividendStock[]>()
  for (const stock of stocks) {
    const industry = stock.industry ?? '未分类'
    byIndustry.set(industry, [...(byIndustry.get(industry) ?? []), stock])
  }
  return [...byIndustry.values()]
    .map((group) => ({
      industry: group[0].industry ?? '未分类',
      stockCount: group.length,
      topYield: group.reduce<number | null>(
        (top, stock) =>
          stock.dividendYield == null ? top : top == null ? stock.dividendYield : Math.max(top, stock.dividendYield),
        null
      )
    }))
    .sort((left, right) => right.stockCount - left.stockCount)
    .slice(0, 8)
}

function DividendTable({ stocks }: { stocks: HighDividendStock[] }) {
  if (!stocks.length) {
    return <p className="text-xs leading-relaxed text-ink-500">高股息明细暂无数据。</p>
  }
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-ink-900">高股息明细（按股息率降序）</h4>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[900px] border-collapse text-left text-xs">
          <thead>
            <tr className="border-b border-line-soft text-[11px] text-ink-400">
              <th className="px-2 py-1.5 font-medium">名称/代码</th>
              <th className="px-2 py-1.5 font-medium">行业</th>
              <th className="px-2 py-1.5 font-medium">股息率</th>
              <th className="px-2 py-1.5 font-medium">派息率</th>
              <th className="px-2 py-1.5 font-medium">现价</th>
              <th className="px-2 py-1.5 font-medium">涨跌幅</th>
              <th className="px-2 py-1.5 font-medium">PE-TTM</th>
              <th className="px-2 py-1.5 font-medium">PB</th>
              <th className="px-2 py-1.5 font-medium">ROE</th>
              <th className="px-2 py-1.5 font-medium">成交额</th>
              <th className="px-2 py-1.5 font-medium">分红方案</th>
            </tr>
          </thead>
          <tbody>
            {stocks.map((stock) => (
              <tr key={stock.symbol} className="border-b border-line-soft last:border-b-0">
                <td className="px-2 py-1.5">
                  <span className="font-semibold text-ink-900">{stock.name}</span>
                  <span className="ml-1.5 text-[11px] text-ink-400">{stock.symbol}</span>
                </td>
                <td className="px-2 py-1.5 text-ink-600">{stock.industry ?? '未分类'}</td>
                <td className="px-2 py-1.5">
                  <Tag tone={stock.dividendYield != null && stock.dividendYield >= 0.07 ? 'success' : 'neutral'}>
                    {formatRatioPercent(stock.dividendYield)}
                  </Tag>
                </td>
                <td className="px-2 py-1.5 tabular text-ink-600">
                  {stock.payoutRatio == null ? (
                    <span title="分红方案无法解析或收益为负，需人工核对">待核</span>
                  ) : (
                    <span className={stock.payoutRatio >= 0.8 ? 'font-semibold text-amber-700' : undefined}>
                      {formatRatioPercent(stock.payoutRatio)}
                    </span>
                  )}
                </td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatNumber(stock.latestPrice)}</td>
                <td className={`px-2 py-1.5 tabular ${changeClass(stock.changePercent)}`}>
                  {formatSignedPercent(stock.changePercent)}
                </td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatNumber(stock.peTtm)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatNumber(stock.pbRatio)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatRatioPercent(stock.roe)}</td>
                <td className="px-2 py-1.5 tabular text-ink-600">{formatAmount(stock.amount)}</td>
                <td className="max-w-[180px] truncate px-2 py-1.5 text-ink-500" title={stock.dividendPlanDescription ?? undefined}>
                  {stock.dividendPlanDescription ?? '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
