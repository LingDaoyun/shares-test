import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { HighDividendBoardSnapshot, HighDividendStock } from '../../types'
import type { DividendBoardFilters } from './HighDividendBoardPanel'
import {
  ALL_INDUSTRIES,
  DEFAULT_DIVIDEND_FILTERS,
  HighDividendBoardDetail,
  HighDividendBoardEntry,
  filterDividendStocks
} from './HighDividendBoardPanel'

const snapshot: HighDividendBoardSnapshot = {
  reportYear: 2025,
  generatedAt: '2026-08-22T01:30:00Z',
  available: true,
  unavailableReason: null,
  thresholdYield: 0.04,
  totalCount: 3,
  industryStats: [],
  stocks: [
    stock('600001', '甲银行', '银行', 0.081, 0.65),
    stock('600002', '乙银行', '银行', 0.066, 0.55),
    stock('600003', '丙煤炭', '煤炭', 0.062, 1.3)
  ],
  dataGaps: []
}

function stock(
  symbol: string,
  name: string,
  industry: string,
  dividendYield: number,
  pbRatio: number
): HighDividendStock {
  return {
    rank: 1,
    symbol,
    name,
    market: '沪市',
    industry,
    dividendYield,
    payoutRatio: 0.4,
    latestPrice: 10.5,
    changePercent: 1.2,
    amount: 120000000,
    peTtm: 6.2,
    pbRatio,
    roe: 0.112,
    dividendPlanDescription: '10派2.5元(含税)'
  }
}

describe('filterDividendStocks', () => {
  it('keeps stocks meeting the yield floor by default', () => {
    const filtered = filterDividendStocks(snapshot.stocks, { ...DEFAULT_DIVIDEND_FILTERS, minYieldPercent: 6 })
    expect(filtered.map((item) => item.symbol)).toEqual(['600001', '600002', '600003'])
    const stricter = filterDividendStocks(snapshot.stocks, { ...DEFAULT_DIVIDEND_FILTERS, minYieldPercent: 7 })
    expect(stricter.map((item) => item.symbol)).toEqual(['600001'])
  })

  it('filters by industry', () => {
    const filtered = filterDividendStocks(snapshot.stocks, {
      ...DEFAULT_DIVIDEND_FILTERS,
      industry: '银行'
    })
    expect(filtered.map((item) => item.symbol)).toEqual(['600001', '600002'])
    const all = filterDividendStocks(snapshot.stocks, {
      ...DEFAULT_DIVIDEND_FILTERS,
      industry: ALL_INDUSTRIES
    })
    expect(all).toHaveLength(3)
  })

  it('filters by pb ceiling and drops missing pb', () => {
    const withMissingPb: HighDividendStock = { ...stock('600004', '丁缺PB', '银行', 0.055, 1.3), pbRatio: null }
    const stocks = [...snapshot.stocks, withMissingPb]
    const filtered = filterDividendStocks(stocks, { ...DEFAULT_DIVIDEND_FILTERS, maxPb: 1 })
    expect(filtered.map((item) => item.symbol)).toEqual(['600001', '600002'])
  })

  it('combines all three filters', () => {
    const filtered = filterDividendStocks(snapshot.stocks, {
      minYieldPercent: 6,
      industry: '银行',
      maxPb: 0.6
    })
    expect(filtered.map((item) => item.symbol)).toEqual(['600002'])
  })
})

describe('HighDividendBoardEntry', () => {
  it('shows yield floor, filtered count, average and top yield in the summary', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardEntry
        snapshot={snapshot}
        stocks={snapshot.stocks}
        minYieldPercent={5}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )
    expect(markup).toContain('高股息看板')
    expect(markup).toContain('股息率≥5%：3 家')
    expect(markup).toContain('平均 6.97%')
    expect(markup).toContain('最高 甲银行 8.10%')
    expect(markup).toContain('2025 年报')
  })

  it('reflects filtered stocks in the summary', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardEntry
        snapshot={snapshot}
        stocks={snapshot.stocks.slice(0, 1)}
        minYieldPercent={7}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )
    expect(markup).toContain('股息率≥7%：1 家')
  })

  it('shows unavailable reason when the board is not available', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardEntry
        snapshot={{ ...snapshot, available: false, unavailableReason: '年报指标获取失败', stocks: [] }}
        stocks={[]}
        minYieldPercent={5}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )
    expect(markup).toContain('年报指标获取失败')
  })
})

describe('HighDividendBoardDetail', () => {
  const filters: DividendBoardFilters = DEFAULT_DIVIDEND_FILTERS

  it('renders filter controls, summary metrics, industry distribution and stock table', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardDetail
        snapshot={snapshot}
        stocks={snapshot.stocks}
        filters={filters}
        onFiltersChange={() => undefined}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />
    )
    expect(markup).toContain('全部行业')
    expect(markup).toContain('银行（2）')
    expect(markup).toContain('煤炭（1）')
    expect(markup).toContain('股息率 ≥(%)')
    expect(markup).toContain('PB ≤(留空不限)')
    expect(markup).toContain('重置筛选')
    expect(markup).toContain('达标家数')
    expect(markup).toContain('3 家')
    expect(markup).toContain('派息率')
    expect(markup).toContain('40.00%')
    expect(markup).toContain('甲银行')
    expect(markup).toContain('10派2.5元(含税)')
    expect(markup).toContain('PB 上限')
    expect(markup).toContain('不限')
  })

  it('shows empty hint when filters exclude everything', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardDetail
        snapshot={snapshot}
        stocks={[]}
        filters={{ ...filters, industry: '未知行业' }}
        onFiltersChange={() => undefined}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />
    )
    expect(markup).toContain('当前筛选条件下暂无达标样本')
  })

  it('renders data gaps when present', () => {
    const markup = renderToStaticMarkup(
      <HighDividendBoardDetail
        snapshot={{ ...snapshot, dataGaps: ['实时行情补充失败，现价缺失'] }}
        stocks={snapshot.stocks}
        filters={filters}
        onFiltersChange={() => undefined}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />
    )
    expect(markup).toContain('实时行情补充失败，现价缺失')
  })
})
