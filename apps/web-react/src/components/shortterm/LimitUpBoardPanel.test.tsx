import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ShortTermLimitUpBoardSnapshot } from '../../types'
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
  dataGaps: []
}

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
    expect(html).toContain('炸板率')
    expect(html).toContain('33.33%')
    expect(html).toContain('最高 2 连板')
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
          industryStats: [],
          sentiment: null,
          dataGaps: []
        }}
        loading={false}
        error={null}
        onRefresh={() => undefined}
        onOpen={() => undefined}
      />
    )

    expect(html).toContain('当日涨停池为空')
    expect(html).toContain('<button disabled=""')
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
  it('renders the full board metrics, industries and stock rows inside the overlay body', () => {
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
    expect(html).toContain('行业聚合')
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
          industryStats: [],
          sentiment: null,
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
