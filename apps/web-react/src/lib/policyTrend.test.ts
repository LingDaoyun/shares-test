import { describe, expect, it } from 'vitest'
import type { PolicyTheme } from '../types'
import { policyEvidence, policySourceUrl } from './policyTrend'

describe('policy sample evidence', () => {
  it('deduplicates titles and excludes future and invalid dates from recent counts', () => {
    const theme = { signals: [
      { summary: '制造规划', source: '政府网', publishedAt: '2026-10-17' },
      { summary: ' 制造规划 ', source: '转载', publishedAt: '2026-10-17' },
      { summary: '上周政策', source: '政府网', publishedAt: '2026-10-10' },
      { summary: '背景文件', source: '政府网', publishedAt: '2026-01-01' },
      { summary: '未标日期', source: '政府网', publishedAt: null },
      { summary: '错误日期', source: '政府网', publishedAt: '2026-02-30' },
      { summary: '未来文件', source: '政府网', publishedAt: '2026-10-18' },
    ] } as PolicyTheme
    const value = policyEvidence(theme, new Date('2026-10-17T02:00:00Z'))
    expect(value.recent7).toBe(2)
    expect(value.recent30).toBe(2)
    expect(value.unknown).toBe(2)
    expect(value.signals.map(item => item.summary)).not.toContain('未来文件')
    expect(value.sources).toBe(1)
  })
  it('counts publication days in Beijing time with inclusive 7, 30 and 90 day boundaries', () => {
    const theme = { signals: [
      { summary: '七天边界', source: '政府网', publishedAt: '2026-10-09T18:00:00Z' },
      { summary: '三十天边界', source: '政府网', publishedAt: '2026-09-16T18:00:00Z' },
      { summary: '九十天边界', source: '政府网', publishedAt: '2026-07-18T18:00:00Z' },
      { summary: '九十一天旧政策', source: '政府网', publishedAt: '2026-07-18' },
    ] } as PolicyTheme
    const value = policyEvidence(theme, new Date('2026-10-17T02:00:00Z'))
    expect(value.recent7).toBe(1)
    expect(value.recent30).toBe(2)
    expect(value.recent90).toBe(3)
    expect(value.unknown).toBe(0)
  })
  it('excludes future instants and interprets local publication times as Beijing time', () => {
    const theme = { signals: [
      { summary: '北京时间刚发布', source: '政府网', publishedAt: '2026-10-17 10:00:00' },
      { summary: 'UTC刚发布', source: '政府网', publishedAt: '2026-10-17T02:00:00Z' },
      { summary: '北京时间未来', source: '政府网', publishedAt: '2026-10-17 10:00:01' },
      { summary: 'UTC未来', source: '政府网', publishedAt: '2026-10-17T02:00:01Z' },
      { summary: '不合法时刻', source: '政府网', publishedAt: '2026-10-17T25:00:00Z' },
    ] } as PolicyTheme
    const value = policyEvidence(theme, new Date('2026-10-17T02:00:00Z'))
    expect(value.recent7).toBe(2)
    expect(value.unknown).toBe(1)
    expect(value.signals.map(signal => signal.summary)).not.toContain('北京时间未来')
    expect(value.signals.map(signal => signal.summary)).not.toContain('UTC未来')
  })
  it('counts the two-year sample at the 90, 91, 730 and 731 day boundaries without mixing plans or unknown dates into history', () => {
    const now = new Date('2026-10-17T02:00:00Z')
    const day = (age: number) => new Date(Date.UTC(2026, 9, 17) - age * 86400000).toISOString().slice(0, 10)
    const theme = { signals: [
      ...[0, 90, 91, 730, 731].map(age => ({ summary: `${age}天前`, source: '政府网', publishedAt: day(age) })),
      { summary: '长期规划', source: '政府网', publishedAt: '2021-01-01', planningBaseline: true },
      { summary: '日期未知', source: '政府网', publishedAt: null },
      { summary: '日期无效', source: '政府网', publishedAt: '2026-02-30' },
      { summary: '明天文章', source: '政府网', publishedAt: day(-1) },
      { summary: '今天未来时刻', source: '政府网', publishedAt: '2026-10-17T02:00:01Z' },
    ] } as PolicyTheme
    const value = policyEvidence(theme, now)
    expect(value.recent90).toBe(2)
    expect(value.recent730).toBe(4)
    expect(value.historicalSignals.map(signal => signal.summary)).toEqual(['91天前', '730天前'])
    expect(value.planningSignals.map(signal => signal.summary)).toEqual(['长期规划'])
    expect(value.unknown).toBe(2)
    expect(value.signals.map(signal => signal.summary)).not.toContain('明天文章')
    expect(value.signals.map(signal => signal.summary)).not.toContain('今天未来时刻')
  })
  it('reserves a preview for historical evidence without changing source authority ordering', () => {
    const theme = { signals: [
      { summary: '最新中央文件', source: '中央部门', publishedAt: '2026-10-17', sourceInfo: { authorityRank: 95 } },
      { summary: '另一份中央文件', source: '中央部门', publishedAt: '2026-10-16', sourceInfo: { authorityRank: 95 } },
      { summary: '历史产业新闻', source: '地方部门', publishedAt: '2025-10-17', sourceInfo: { authorityRank: 80 } },
    ] } as PolicyTheme
    const value = policyEvidence(theme, new Date('2026-10-17T02:00:00Z'))
    expect(value.signals.map(signal => signal.summary)).toEqual(['最新中央文件', '另一份中央文件', '历史产业新闻'])
    expect(value.previewSignals.map(signal => signal.summary)).toEqual(['最新中央文件', '历史产业新闻'])
  })
  it('keeps old plans in the preview even when they fall outside the two-year news window', () => {
    const theme = { signals: [
      { summary: '近期新闻一', source: '政府网', publishedAt: '2026-10-17' },
      { summary: '近期新闻二', source: '政府网', publishedAt: '2026-10-16' },
      { summary: '长期产业规划', source: '政府网', publishedAt: '2021-01-01', planningBaseline: true },
    ] } as PolicyTheme
    const value = policyEvidence(theme, new Date('2026-10-17T02:00:00Z'))
    expect(value.recent730).toBe(2)
    expect(value.historicalSignals).toEqual([])
    expect(value.previewSignals.map(signal => signal.summary)).toEqual(['近期新闻一', '长期产业规划'])
  })
  it('allows article links without allowing executable URLs', () => {
    expect(policySourceUrl('javascript:alert(1)')).toBeNull()
    expect(policySourceUrl('https://www.gov.cn/zhengce/')).toBe('https://www.gov.cn/zhengce/')
  })
})
