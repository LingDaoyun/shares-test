// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchPolicyThemes, reviewPolicyTheme } from '../api/client'
import { toast } from '../components/ui/Toast'
import { useAppStore } from '../store/appStore'
import { PolicyIndustryPage } from './PolicyIndustryPage'

vi.mock('../api/client', () => ({
  fetchPolicyThemes: vi.fn(),
  reviewPolicyTheme: vi.fn()
}))

vi.mock('../components/ui/Toast', () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn(), loading: vi.fn(), dismiss: vi.fn() }
}))

const mockedFetchPolicyThemes = vi.mocked(fetchPolicyThemes)
const mockedReviewPolicyTheme = vi.mocked(reviewPolicyTheme)

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
    useAppStore.setState({ llmConfig: null })
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

  it('hides the AI review entry when no model key is configured', async () => {
    await renderPage()

    expect(host.textContent).not.toContain('AI 复核')
  })

  it('runs the real agent review and renders structured verdict when configured', async () => {
    useAppStore.setState({
      llmConfig: {
        provider: 'deepseek',
        model: 'deepseek-v4-pro',
        baseUrl: 'https://api.deepseek.com',
        responseFormat: 'json_object',
        strictJsonSchema: false,
        apiKeyConfigured: true,
        apiKeySource: 'env:DEEPSEEK_API_KEY',
        thinking: null,
        maxCompletionTokens: 8192,
        temperature: null
      }
    })
    mockedReviewPolicyTheme.mockResolvedValue({
      themeCode: 'NEW_QUALITY_PRODUCTIVITY',
      themeName: '新质生产力与高端制造',
      provider: 'deepseek',
      model: 'deepseek-v4-pro',
      relevanceScore: 86,
      direction: 'POSITIVE',
      directionLabel: '政策利好',
      phase: 'ONGOING',
      phaseLabel: '持续发酵',
      logicSummary: '高端装备投资规划直接拉动工业母机与核心零部件需求',
      confirmations: ['政策原文Agent命中确认'],
      disagreements: ['部分信号属于地方试点，不宜按全国推广解读'],
      risks: ['订单兑现节奏低于预期'],
      mustVerify: ['核实文件编号与发布机关'],
      confidence: 78,
      reviewedAt: '2026-08-26T10:00:00+08:00',
      dataGaps: []
    })
    await renderPage()

    const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.includes('AI 复核'))
    expect(button).toBeDefined()
    await act(async () => {
      button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(mockedReviewPolicyTheme).toHaveBeenCalledWith('NEW_QUALITY_PRODUCTIVITY')
    const text = host.textContent ?? ''
    expect(text).toContain('AI 复核')
    expect(text).toContain('政策利好')
    expect(text).toContain('持续发酵')
    expect(text).toContain('关联度 86')
    expect(text).toContain('高端装备投资规划')
    expect(text).toContain('复核确认')
    expect(text).toContain('复核异议')
    expect(text).toContain('待人工核实')
  })

  it('reports review failures without breaking the board', async () => {
    useAppStore.setState({
      llmConfig: { ...configuredLlmConfig, apiKeyConfigured: true }
    })
    mockedReviewPolicyTheme.mockRejectedValue(new Error('模型接口超时'))
    await renderPage()

    const button = [...host.querySelectorAll('button')].find((item) => item.textContent?.includes('AI 复核'))
    await act(async () => {
      button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('AI 复核失败'))
    expect(host.textContent).not.toContain('复核确认')
  })

  async function renderPage() {
    await act(async () => {
      root.render(<PolicyIndustryPage />)
      await Promise.resolve()
      await Promise.resolve()
    })
  }
})

const configuredLlmConfig = {
  provider: 'deepseek',
  model: 'deepseek-v4-pro',
  baseUrl: 'https://api.deepseek.com',
  responseFormat: 'json_object',
  strictJsonSchema: false,
  apiKeyConfigured: false,
  apiKeySource: 'missing',
  thinking: null as string | null,
  maxCompletionTokens: 8192 as number | null,
  temperature: null as number | null
}

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
