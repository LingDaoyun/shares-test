// @vitest-environment jsdom
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermLimitUpBoardSnapshot, ShortTermMarketBreadth } from '../../types'
import { LimitUpBoardDetail, LimitUpBoardEntry } from './LimitUpBoardPanel'

const snapshot: ShortTermLimitUpBoardSnapshot = {
  tradeDate: '2026-08-21',
  fetchedAt: '2026-08-21T15:26:00Z',
  available: true,
  unavailableReason: null,
  stocks: [
    {
      symbol: '000017',
      name: '深中华A',
      industry: '饰品',
      latestPrice: 7.11,
      changePercent: 10.06,
      amount: 294182288,
      turnoverRate: 9.43,
      consecutiveBoards: 2,
      statDays: 2,
      statBoards: 2,
      sealFunds: 39809601,
      firstSealTime: '09:25:00',
      lastSealTime: '09:39:09',
      sealBreakCount: 1
    },
    {
      symbol: '002209',
      name: '达 意 隆',
      industry: '专用设备',
      latestPrice: 13.16,
      changePercent: 10.03,
      amount: 206290574,
      turnoverRate: 9.94,
      consecutiveBoards: 1,
      statDays: 1,
      statBoards: 1,
      sealFunds: 23922248,
      firstSealTime: '09:25:00',
      lastSealTime: '09:42:36',
      sealBreakCount: 0
    }
  ],
  brokenBoardAvailable: true,
  brokenStocks: [],
  industryStats: [
    {
      industry: '饰品',
      limitUpCount: 1,
      maxConsecutiveBoards: 2,
      totalAmount: 294182288,
      leaders: ['深中华A']
    },
    {
      industry: '专用设备',
      limitUpCount: 1,
      maxConsecutiveBoards: 1,
      totalAmount: 206290574,
      leaders: ['达 意 隆']
    }
  ],
  sentiment: {
    limitUpCount: 2,
    brokenCount: 1,
    limitDownCount: 0,
    sealBreakRatioPercent: 33.33,
    maxConsecutiveBoards: 2,
    boards2PlusCount: 1,
    boards3PlusCount: 0,
    sealedBeforeTenCount: 2,
    sealedMorningCount: 0,
    sealedAfternoonCount: 0,
    sealedTailCount: 0,
    earlySealSharePercent: 100,
    tone: '情绪冰点',
    explanation: '涨停 2 家，炸板 1 家（炸板率 33.33%），最高 2 连板。'
  },
  marketTurnover: {
    tradeDate: '2026-08-21',
    todayVolumeHands: 990849782,
    previousVolumeHands: 1103204190,
    volumeChangePercent: -10.18,
    todayAmountYuan: 1879264405345,
    label: '显著缩量',
    explanation: '上证指数+深证综指成交量 2026-08-21 合计 990849782 手，前一日 1103204190 手。'
  },
  dataGaps: []
}

const breadth: ShortTermMarketBreadth = {
  tradeDate: '2026-08-21', fetchedAt: '2026-08-21T15:26:00Z',
  advancing: 2500, declining: 3000, unchanged: 50, excludedCount: 10,
  totalCount: 5560, source: '东方财富行情'
}

describe.each(['entry', 'detail'] as const)('Market breadth timing in %s', (view) => {
  function renderBreadth(value: ShortTermMarketBreadth) {
    const props = { snapshot, breadth: value, loading: false, error: null, onRefresh: () => undefined }
    return renderToStaticMarkup(view === 'entry'
      ? <LimitUpBoardEntry {...props} onOpen={() => undefined} />
      : <LimitUpBoardDetail {...props} />)
  }

  it('shows the quote timestamp range separately from the fetch time and labels missing timestamps', () => {
    const html = renderBreadth({ ...breadth,
      marketTimestampFrom: '2026-08-21T06:58:00Z', marketTimestampTo: '2026-08-21T07:00:00Z', missingTimestampCount: 3
    } as ShortTermMarketBreadth)
    expect(html).toContain('最新可用涨跌数据')
    expect(html).toContain('源时间 2026-08-21 14:58:00 至 2026-08-21 15:00:00')
    expect(html).toContain('获取时间 2026-08-21 23:26:00')
    expect(html).toContain('3 只缺少源时间')
  })

  it('labels a missing source timestamp as unknown instead of using the fetch time', () => {
    const html = renderBreadth(breadth)
    expect(html).toContain('源时间 未知')
    expect(html).toContain('获取时间 2026-08-21 23:26:00')
    expect(html).not.toContain('源时间 2026-08-21 23:26:00')
  })

  it('shows a single source timestamp when the endpoints are the same', () => {
    const html = renderBreadth({ ...breadth,
      marketTimestampFrom: '2026-08-21T07:00:00Z', marketTimestampTo: '2026-08-21T07:00:00Z', missingTimestampCount: 0
    } as ShortTermMarketBreadth)
    expect(html).toContain('源时间 2026-08-21 15:00:00')
    expect(html).not.toContain(' 至 ')
    expect(html).not.toContain('只缺少源时间')
  })
})

describe('LimitUpBoardEntry', () => {
  it('renders a one-line summary without the full tables', () => {
    const html = renderToStaticMarkup(
      <LimitUpBoardEntry
        snapshot={snapshot}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )

    expect(html).toContain('涨停看板')
    expect(html).toContain('情绪冰点')
    expect(html).toContain('涨停 2 家')
    expect(html).toContain('跌停 0 家')
    expect(html).toContain('炸板率')
    expect(html).toContain('33.33%')
    expect(html).toContain('最高 2 连板')
    expect(html).toContain('量能 显著缩量 -10.18%')
    expect(html).toContain('2026-08-21')
    expect(html).toContain('查看看板')
    expect(html).not.toContain('行业聚合')
    expect(html).not.toContain('涨停明细')
  })

  it('renders the unavailable reason inline instead of blocking the page', () => {
    const html = renderToStaticMarkup(
      <LimitUpBoardEntry
        snapshot={{
          tradeDate: '2026-08-16',
          fetchedAt: '2026-08-16T01:00:00Z',
          available: false,
          unavailableReason: '当日涨停池为空（可能是非交易日、盘前或数据源尚未生成）',
          stocks: [],
          brokenBoardAvailable: false,
          brokenStocks: [],
          industryStats: [],
          sentiment: null,
          marketTurnover: null,
          dataGaps: []
        }}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )

    expect(html).toContain('当日涨停池为空')
    // 涨跌统计独立于涨停池，仍允许打开详情查看统计或错误原因。
    expect(html).toContain('查看看板')
    expect(html).not.toContain('<button disabled=""')
  })

  it('surfaces fetch failures inline with a retry entry', () => {
    const html = renderToStaticMarkup(
      <LimitUpBoardEntry
        snapshot={null}
        loading={false}
        error="接口超时"
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )

    expect(html).toContain('获取失败：接口超时')
    expect(html).toContain('刷新')
  })
})

describe('LimitUpBoardDetail', () => {
  it('shows industry cards by count with only three stock previews in server order', () => {
    const stocks = [
      { ...snapshot.stocks[0], symbol: '600001', name: '饰品高标', industry: '饰品', consecutiveBoards: 6 },
      { ...snapshot.stocks[0], symbol: '600002', name: '芯片高标', industry: ' 半导体 ', consecutiveBoards: 4, firstSealTime: '10:00:00' },
      { ...snapshot.stocks[0], symbol: '600003', name: '未知高标', industry: null, consecutiveBoards: 3 },
      { ...snapshot.stocks[0], symbol: '600004', name: '芯片早封', industry: '半导体', consecutiveBoards: 2, firstSealTime: '09:25:00' },
      { ...snapshot.stocks[0], symbol: '600005', name: '芯片晚封', industry: '半导体', consecutiveBoards: 2, firstSealTime: '10:10:00' },
      { ...snapshot.stocks[0], symbol: '600006', name: '未知首板', industry: '  ', consecutiveBoards: 1 },
      { ...snapshot.stocks[0], symbol: '600007', name: '芯片缺时', industry: '半导体', consecutiveBoards: 1, firstSealTime: null },
    ]
    stocks.forEach(Object.freeze)
    Object.freeze(stocks)
    const container = document.createElement('div')
    container.innerHTML = renderToStaticMarkup(
      <LimitUpBoardDetail snapshot={{ ...snapshot, stocks, industryStats: [] }} loading={false} error={null} onRefresh={() => undefined} />
    )

    expect(container.querySelector('table')).toBeNull()
    const groups = Array.from(container.querySelectorAll('section[aria-label="涨停行业卡片"] button'))
    expect(groups).toHaveLength(3)
    const expectedGroups = [
      { industry: '半导体', count: 4, rows: ['芯片高标', '芯片早封', '芯片晚封'] },
      { industry: '未分类', count: 2, rows: ['未知高标', '未知首板'] },
      { industry: '饰品', count: 1, rows: ['饰品高标'] },
    ]
    groups.forEach((group, index) => {
      const expected = expectedGroups[index]
      expect(group.getAttribute('aria-label')).toBe(`查看${expected.industry}涨停及上涨股票`)
      expect(group.textContent).toContain(`涨停 ${expected.count} 家`)
      const positions = expected.rows.map(name => group.textContent!.indexOf(name))
      expect(positions.every(position => position >= 0)).toBe(true)
      expect(positions).toEqual([...positions].sort((a, b) => a - b))
    })
    expect(groups[0].textContent).toContain('最高 4 板')
    expect(groups[0].textContent).toContain('还有 1 只')
    expect(container.textContent).not.toContain('芯片缺时')
  })

  it('keeps every industry accessible and preserves first appearance when counts tie', () => {
    const stocks = Array.from({ length: 9 }, (_, index) => ({
      ...snapshot.stocks[0], symbol: `60000${index}`, industry: `行业${index}`,
    }))
    const container = document.createElement('div')
    container.innerHTML = renderToStaticMarkup(
      <LimitUpBoardDetail snapshot={{ ...snapshot, stocks }} loading={false} error={null} onRefresh={() => undefined} />
    )
    const cards = Array.from(container.querySelectorAll('section[aria-label="涨停行业卡片"] button'))
    expect(cards.map(card => card.getAttribute('aria-label'))).toEqual(
      stocks.map(stock => `查看${stock.industry}涨停及上涨股票`)
    )
  })

  it('shows limit-down counts beside breadth and keeps missing counts distinct from zero', () => {
    const available = renderToStaticMarkup(
      <LimitUpBoardDetail snapshot={snapshot} breadth={breadth} loading={false} error={null} onRefresh={() => undefined} />
    )
    const firstMetrics = available.match(/<dl[^>]*>([\s\S]*?)<\/dl>/)?.[1]
    expect(firstMetrics).toContain('上涨')
    expect(firstMetrics).toContain('下跌')
    expect(firstMetrics).toContain('平盘')
    expect(firstMetrics).toContain('跌停')
    expect(firstMetrics).toContain('0 家')

    const missingSnapshot = { ...snapshot, sentiment: { ...snapshot.sentiment!, limitDownCount: null } }
    const missing = renderToStaticMarkup(
      <LimitUpBoardDetail snapshot={missingSnapshot} breadth={breadth} loading={false} error={null} onRefresh={() => undefined} />
    )
    const missingMetrics = missing.match(/<dl[^>]*>([\s\S]*?)<\/dl>/)?.[1]
    expect(missingMetrics).toContain('跌停')
    expect(missingMetrics).toContain('待补充')
    const entry = renderToStaticMarkup(
      <LimitUpBoardEntry snapshot={missingSnapshot} loading={false} error={null} onRefresh={() => undefined} onOpen={() => undefined} />
    )
    expect(entry).toContain('跌停 待补充')
    expect(entry).not.toContain('跌停 0 家')
  })

  it('renders the board metrics and industry previews inside the overlay body', () => {
    const html = renderToStaticMarkup(
      <LimitUpBoardDetail
        snapshot={snapshot}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />
    )

    expect(html).toContain('共 2 只涨停')
    expect(html).toContain('10 点前封板占比')
    expect(html).toContain('100.00%')
    expect(html).toContain('量能对比(量)')
    expect(html).toContain('显著缩量 -10.18%')
    expect(html).toContain('今日两市成交额')
    expect(html).toContain('18792.64 亿')
    expect(html).toContain('涨停行业卡片')
    expect(html).toContain('深中华A')
    expect(html).toContain('09:25')
    expect(html).toContain('2天2板')
    expect(html).toContain('快照口径')
  })

  it('keeps the unavailable reason for a closed overlay body', () => {
    const html = renderToStaticMarkup(
      <LimitUpBoardDetail
        snapshot={{
          tradeDate: '2026-08-16',
          fetchedAt: '2026-08-16T01:00:00Z',
          available: false,
          unavailableReason: '当日涨停池为空',
          stocks: [],
          brokenBoardAvailable: false,
          brokenStocks: [],
          industryStats: [],
          sentiment: null,
          marketTurnover: null,
          dataGaps: []
        }}
        loading={false}
        error={null}
        onRefresh={() => undefined}
      />
    )

    expect(html).toContain('当日涨停池为空')
    expect(html).not.toContain('行业聚合')
  })
})
