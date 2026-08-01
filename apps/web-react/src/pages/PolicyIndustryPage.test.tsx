// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchPolicyThemes } from '../api/client'
import { PolicyIndustryPage } from './PolicyIndustryPage'

vi.mock('../api/client', () => ({
  fetchPolicyThemes: vi.fn()
}))

const mockedFetchPolicyThemes = vi.mocked(fetchPolicyThemes)

describe('PolicyIndustryPage', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    mockedFetchPolicyThemes.mockResolvedValue(policyThemes as never)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  it('loads official policy themes and company pool without AI interpretation panel', async () => {
    await act(async () => {
      root.render(<PolicyIndustryPage />)
      await Promise.resolve()
      await Promise.resolve()
    })

    const text = host.textContent ?? ''
    expect(mockedFetchPolicyThemes).toHaveBeenCalled()
    expect(text).toContain('政策产业看板')
    expect(text).toContain('新质生产力与高端制造')
    expect(text).toContain('工业母机')
    expect(text).toContain('公司池')
    expect(text).toContain('三一重工')
    expect(text).toContain('财报质量较好')
    expect(text).toContain('不荐股')
    expect(text).toContain('政策原文Agent命中')
    expect(text).not.toContain('AI 政策解读')
    expect(text).not.toContain('AI解读')
    expect(text).not.toContain('历史解读')
  })
})

const policyThemes = [
  {
    themeCode: 'NEW_QUALITY_PRODUCTIVITY',
    name: '新质生产力与高端制造',
    policyLevel: '国家级',
    timeHorizon: '2026-2030',
    strengthScore: 91.5,
    chainSegments: ['工业母机', '机器人核心零部件'],
    signals: [
      {
        source: '中国政府网',
        signalType: '长期规划 / 政策原文Agent命中',
        summary: '推动高端装备和现代化产业体系建设',
        confidence: 92,
        url: 'https://www.gov.cn/zhengce/example',
        publishedAt: '2026-07-20'
      }
    ],
    risks: ['概念拥挤', '订单兑现慢']
    ,
    companyPool: [
      {
        symbol: '600031',
        companyName: '三一重工',
        industry: '工程机械',
        chainSegment: '高端装备',
        researchRole: '产业龙头研究候选',
        leadershipRationale: ['行业地位靠前', '主题匹配度高'],
        financialQualityScore: 88,
        financialQualityLabel: '财报质量较好',
        latestPrice: 16.2,
        peTtm: 18.5,
        pbRatio: 1.9,
        amount: 5000000000,
        actionLabel: '不荐股',
        dataGaps: ['仍需核验主营收入占比']
      }
    ]
  }
]
