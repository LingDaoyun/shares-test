import type { StockSearchFilters } from '../../types'

export type StockSearchFilterDraft = Partial<Record<keyof StockSearchFilters, string | boolean>>

const exclusions = [
  ['excludeChiNext', '剔除创业板'], ['excludeStar', '剔除科创板'],
  ['excludeBeijing', '剔除北交所'], ['excludeSt', '剔除 ST']
] as const
const ranges = [
  ['minChangePercent', 'maxChangePercent', '涨跌幅', '%'],
  ['minMarketCap', 'maxMarketCap', '总市值', '亿元'],
  ['minCirculatingMarketCap', 'maxCirculatingMarketCap', '流通市值', '亿元'],
  ['minTurnoverRate', 'maxTurnoverRate', '换手率', '%'],
  ['minPrice', 'maxPrice', '最新价', '元'],
  ['minAmount', 'maxAmount', '成交额', '亿元']
] as const

export function StockSearchFilterFields({ value, onChange }: {
  value: StockSearchFilterDraft
  onChange: (value: StockSearchFilterDraft) => void
}) {
  return <div className="space-y-3 border-t border-line-soft pt-3">
    <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
      {exclusions.map(([key, label]) => <label key={key} className="inline-flex cursor-pointer items-center gap-2 text-xs text-ink-600">
        <input type="checkbox" name={key} checked={value[key] === true}
          className="h-4 w-4 accent-brand-500" onChange={event => onChange({ ...value, [key]: event.target.checked })} />
        {label}
      </label>)}
      <button type="button" className="ml-auto text-xs text-brand-600 hover:underline" onClick={() => onChange({})}>清空筛选</button>
    </div>
    <div className="grid grid-cols-1 gap-x-5 gap-y-3 sm:grid-cols-2 xl:grid-cols-3">
      {ranges.map(([minKey, maxKey, label, unit]) => <fieldset key={minKey} className="min-w-0">
        <legend className="mb-1.5 text-xs text-ink-500">{label}<span className="ml-1 text-ink-400">（{unit}）</span></legend>
        <div className="flex items-center gap-2">
          {([minKey, maxKey] as const).map((key, index) => <div key={key} className="contents">
            {index === 1 ? <span aria-hidden="true" className="text-xs text-ink-300">至</span> : null}
            <input type="number" name={key} step="any" min={minKey === 'minChangePercent' ? undefined : 0}
              aria-label={`${label}${index === 0 ? '下限' : '上限'}（${unit}）`}
              className="field min-w-0 py-1.5 text-xs tabular" placeholder={index === 0 ? '最低不限' : '最高不限'}
              value={typeof value[key] === 'string' ? value[key] as string : ''}
              onChange={event => onChange({ ...value, [key]: event.target.value })} />
          </div>)}
        </div>
      </fieldset>)}
    </div>
    <p className="text-[11px] text-ink-400">留空表示不限，区间包含上下限，所有条件同时满足。关键词可留空，直接按条件搜索全 A 股。</p>
  </div>
}

export function parseStockSearchFilters(draft: StockSearchFilterDraft): StockSearchFilters {
  const filters: StockSearchFilters = {}
  for (const [key] of exclusions) if (draft[key] === true) filters[key] = true
  for (const [minKey, maxKey, label] of ranges) {
    for (const key of [minKey, maxKey]) {
      const input = draft[key]
      if (typeof input !== 'string' || !input.trim()) continue
      const number = Number(input)
      if (!Number.isFinite(number)) throw new Error(`${label}请输入有效数字`)
      if (minKey !== 'minChangePercent' && number < 0) throw new Error(`${label}不能小于 0`)
      filters[key] = number
    }
    const min = filters[minKey], max = filters[maxKey]
    if (min !== undefined && max !== undefined && min > max) throw new Error(`${label}下限不能大于上限`)
  }
  return filters
}

export function describeStockSearchFilters(filters: StockSearchFilters): string {
  const labels: string[] = exclusions.filter(([key]) => filters[key]).map(([, label]) => label)
  for (const [minKey, maxKey, label, unit] of ranges) {
    const min = filters[minKey], max = filters[maxKey]
    if (min !== undefined && max !== undefined) labels.push(`${label} ${min}～${max}${unit}`)
    else if (min !== undefined) labels.push(`${label} ≥ ${min}${unit}`)
    else if (max !== undefined) labels.push(`${label} ≤ ${max}${unit}`)
  }
  return labels.join(' · ') || '不限板块和行情指标'
}
