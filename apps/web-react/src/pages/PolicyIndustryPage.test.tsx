// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchPolicyDaily, fetchPolicyReviewHistory, refreshPolicyDaily, reviewPolicyTheme } from '../api/client'
import { useAppStore } from '../store/appStore'
import { PolicyIndustryPage } from './PolicyIndustryPage'
import type { PolicyAiReview, PolicyDailySnapshot, PolicyTheme } from '../types'

vi.mock('../api/client', () => ({ fetchPolicyDaily: vi.fn(), fetchPolicyReviewHistory: vi.fn(), refreshPolicyDaily: vi.fn(), reviewPolicyTheme: vi.fn() }))
const theme: PolicyTheme = {
  themeCode: 'DIGITAL_INFRA', name: '数字经济与算力', policyLevel: '政策线索', timeHorizon: '2–12周观察', strengthScore: 60,
  chainSegments: ['算力设备'], risks: ['订单需要验证'], signals: [{ source: '政府网', summary: '推进算力设施建设方案', signalType: '标题关键词匹配', confidence: 60, url: 'https://www.gov.cn/policy-test', publishedAt: '2026-10-17' }],
  companyPool: [{ symbol: '000977', companyName: '浪潮信息', industry: '计算机设备', chainSegment: '算力设备', researchRole: '行业相关候选', leadershipRationale: ['行业关键词匹配'], financialQualityScore: 65, financialQualityLabel: '财报待核验', latestPrice: null, peTtm: null, pbRatio: null, amount: null, actionLabel: '观察', dataGaps: ['主营占比待核验'] }],
}
const review: PolicyAiReview = {
  themeCode: theme.themeCode, themeName: theme.name, provider: 'deepseek', model: 'test-model', relevanceScore: 70,
  direction: 'POSITIVE', directionLabel: '政策利好', phase: 'EARLY', phaseLabel: '发酵初期', logicSummary: '旧研判摘要',
  confirmations: [], disagreements: ['落实节奏待验证'], risks: [], mustVerify: ['核实原文适用范围'], confidence: 65,
  reviewedAt: '2026-10-17T00:40:00Z', dataGaps: [], evidenceSnapshot: theme.signals,
  trendPlan: { thesis: '观察设备订单能否逐步兑现', policyIncrement: '缺少旧政策对照', benefitChain: ['建设支持→设备需求'], shortTermCatalysts: ['跟踪配套细则'], mediumTermCatalysts: ['跟踪招标与订单'], validationSignals: ['公告出现实际订单'], invalidationSignals: ['计划被取消'], marketConfirmation: '量价待验证', companyAssessments: [{ symbol: '000977', companyName: '浪潮信息', relevance: 'INDUSTRY', rationale: '设备行业相关', verificationNeeded: '核验主营收入' }] },
}
const daily: PolicyDailySnapshot = { status: 'READY', scheduledTime: '每天08:30（北京时间）', startedAt: '2026-10-17T00:30:00Z', completedAt: '2026-10-17T00:40:00Z', themes: [theme], reviewedThemeCodes: [theme.themeCode], dataGaps: [] }

describe('daily policy research board', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    localStorage.clear()
    vi.mocked(fetchPolicyDaily).mockResolvedValue(daily)
    vi.mocked(fetchPolicyReviewHistory).mockResolvedValue([review])
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    useAppStore.setState({ llmConfig: { provider: 'deepseek', model: 'test-model', apiKeyConfigured: true } as never })
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => { act(() => root.unmount()); host.remove(); vi.useRealTimers(); vi.restoreAllMocks(); delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT })
  async function render() { await act(async () => { root.render(<PolicyIndustryPage />) }) }
  it('shows source authority prominently and keeps a local repost distinct from its claimed origin', async () => {
    const signals = [{ ...theme.signals[0], source: '工信部规划解读', url: 'https://www.miit.gov.cn/article',
      sourceInfo: { publisher: '工业和信息化部', authorityTier: 'CENTRAL_DEPARTMENT', authorityLabel: '中央部门官网', authorityRank: 95, siteHost: 'www.miit.gov.cn', attributedSource: '电子信息司' } },
    { ...theme.signals[0], summary: '地方转载算力规划', source: '湖北省经信厅', url: 'https://jxt.hubei.gov.cn/article',
      sourceInfo: { publisher: 'jxt.hubei.gov.cn', authorityTier: 'LOCAL_GOVERNMENT', authorityLabel: '地方政府官网', authorityRank: 80, siteHost: 'jxt.hubei.gov.cn', attributedSource: '工信微报' } }]
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ ...daily, themes: [{ ...theme, signals }] })
    await render()
    expect(host.querySelector('article')?.textContent).toContain('中央部门官网')
    expect(host.querySelector('article')?.textContent).toContain('工业和信息化部')
    await detail(); await click('政策与新闻')
    const article = document.querySelector('a[href="https://jxt.hubei.gov.cn/article"]')?.closest('article')
    expect(article?.textContent).toContain('地方政府官网')
    expect(article?.textContent).toContain('网页标注来源：工信微报')
    expect(article?.textContent).not.toContain('中央部门官网')
  })

  it('retains unclassified policy links without pretending they are completed industry reviews', async () => {
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ ...daily, unclassifiedSignals: [{ ...theme.signals[0], summary: '尚未归类的政策', url: 'https://www.gov.cn/unclassified' }] })
    await render()
    expect(host.textContent).toContain('待归类政策 · 1 条')
    expect(host.querySelector('a[href="https://www.gov.cn/unclassified"]')?.textContent).toContain('尚未归类的政策')
    expect(reviewPolicyTheme).not.toHaveBeenCalled()
  })
  async function click(text: string) {
    const button = [...document.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === text || item.getAttribute('aria-label') === text)
    expect(button).toBeDefined(); expect(button!.disabled).toBe(false)
    await act(async () => { button!.focus(); button!.click() })
  }
  async function detail() {
    const title = [...host.querySelectorAll<HTMLElement>('article h3')].find(item => item.textContent === theme.name)
    expect(title).toBeDefined()
    await act(async () => { title!.click() })
  }

  it('reads saved intelligence and reviews without launching paid analysis', async () => {
    await render()
    expect(host.textContent).toContain('每日产业情报')
    expect(host.textContent).toContain('每天08:30')
    expect(host.textContent).toContain('观察设备订单能否逐步兑现')
    expect(host.textContent).toContain('部分来源仅有标题')
    expect(reviewPolicyTheme).not.toHaveBeenCalled()
    expect(refreshPolicyDaily).not.toHaveBeenCalled()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })

  it('opens source articles and validated company assessments in separate detail tabs', async () => {
    await render(); await detail(); await click('关闭详情')
    expect(document.activeElement).toBe(host.querySelector('[data-policy-detail]'))
    await click(`查看${theme.name}趋势详情`); await click('政策与新闻')
    expect(document.querySelector<HTMLAnchorElement>('[role="dialog"] a')?.href).toBe('https://www.gov.cn/policy-test')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('仅标题线索')
    expect(document.querySelector('[role="dialog"] blockquote')).toBeNull()
    await click('上市公司')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('浪潮信息')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('设备行业相关')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('核验主营收入')
    await click('研判记录 · 1')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('研判时的政策来源')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('仅标题线索')
  })

  it('shows collected excerpts as plain text and keeps old planning evidence separate from new catalysts', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-17T04:00:00Z'))
    const excerpt = '推进算力与先进制造业协同发展。\n<img src=x onerror="alert(1)">'
    const signals = [theme.signals[0], {
      ...theme.signals[0], summary: '制造业长期发展规划', publishedAt: '2021-03-12',
      url: 'https://www.gov.cn/long-term-plan', excerpt, planningBaseline: true
    }]
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ ...daily, themes: [{ ...theme, signals }] })
    vi.mocked(fetchPolicyReviewHistory).mockResolvedValue([{ ...review, evidenceSnapshot: signals }])

    await render()
    const card = host.querySelector('article')!
    expect(card.textContent).toContain('长期规划')
    expect(card.textContent).toContain('近30天 1 条')
    expect(card.textContent).toContain('2021-03-12')
    await detail(); await click('政策与新闻')
    const dialog = document.querySelector('[role="dialog"]')!
    const link = dialog.querySelector<HTMLAnchorElement>('a[href="https://www.gov.cn/long-term-plan"]')!
    expect(link.textContent).toContain('制造业长期发展规划')
    expect(link.target).toBe('_blank')
    expect(link.rel).toContain('noopener')
    expect(dialog.querySelector('blockquote')?.textContent).toBe(excerpt)
    expect(dialog.querySelector('img')).toBeNull()
    expect(dialog.textContent).toContain('正文片段')
    expect(dialog.textContent).toContain('仅标题线索')
    expect(dialog.textContent).toContain('不代表新增催化')

    await click('研判记录 · 1')
    expect(document.querySelector('[role="dialog"] details blockquote')?.textContent).toBe(excerpt)
    expect(document.querySelector('[role="dialog"] details')?.textContent).toContain('2021-03-12')
    expect(document.querySelector('[role="dialog"] details')?.textContent).toContain('长期规划')
  })

  it('surfaces historical news in card previews and filters industries without hiding history-only themes', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-17T04:00:00Z'))
    const historical = { ...theme.signals[0], summary: '去年发布的固态电池产业报告', url: 'https://www.gov.cn/historical', publishedAt: '2025-10-17' }
    const historicalTheme = { ...theme, themeCode: 'BATTERY', name: '固态电池', signals: [historical] }
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ ...daily, themes: [
      { ...theme, signals: [theme.signals[0], { ...theme.signals[0], summary: '另一条近期政策', publishedAt: '2026-10-16' }, historical] },
      historicalTheme,
      { ...theme, themeCode: 'RECENT_ONLY', name: '近期线索产业' },
    ] })
    await render()
    const card = [...host.querySelectorAll('article')].find(item => item.querySelector('h3')?.textContent === theme.name)!
    expect(card.textContent).toContain('近30天 2 条 · 近两年 3 条')
    expect(card.textContent).toContain('历史线索 1 条')
    expect(card.textContent).toContain('去年发布的固态电池产业报告')
    expect(host.textContent).toContain('属于已采样范围')
    expect(host.textContent).toContain('2–12 周趋势观察')
    await click('有历史线索')
    expect([...host.querySelectorAll('article h3')].map(item => item.textContent)).toEqual([theme.name, '固态电池'])
    const oldCard = [...host.querySelectorAll('article')].find(item => item.querySelector('h3')?.textContent === '固态电池')!
    expect(oldCard.textContent).toContain('近30天 0 条 · 近两年 1 条')
    expect(reviewPolicyTheme).not.toHaveBeenCalled()
  })

  it('filters detail sources by publication age while keeping planning and unknown dates distinct', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-17T04:00:00Z'))
    const signal = (id: string, publishedAt: string | null, planningBaseline = false) => ({ ...theme.signals[0], summary: id, url: `https://www.gov.cn/${id}`, publishedAt, planningBaseline })
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ ...daily, themes: [{ ...theme, signals: [
      signal('recent', '2026-10-17'), signal('historical', '2025-10-17'), signal('planning', '2021-03-12', true),
      signal('unknown', null), signal('future', '2026-10-18'),
    ] }] })
    await render(); await detail(); await click('政策与新闻')
    const links = () => [...document.querySelectorAll<HTMLAnchorElement>('[role="dialog"] article a')].map(link => new URL(link.href).pathname)
    expect(links()).toEqual(['/recent', '/historical', '/planning', '/unknown'])
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.textContent).toContain('近两年 2')
    expect(dialog.textContent).toContain('日期未知 1')
    expect(dialog.textContent).toContain('最多展示 24 条代表线索')
    expect(dialog.textContent).toContain('新采集不等于新发布')
    await click('历史线索')
    expect(links()).toEqual(['/historical'])
    expect(dialog.querySelector('article')?.textContent).toContain('来源发布日期：2025-10-17')
    expect(dialog.querySelector('article')?.textContent).toContain('核验文件是否仍有效')
    await click('近90天'); expect(links()).toEqual(['/recent'])
    await click('长期规划'); expect(links()).toEqual(['/planning'])
    await click('全部'); expect(links()).toEqual(['/recent', '/historical', '/planning', '/unknown'])
  })

  it('keeps the last successful analysis when a manual update fails', async () => {
    vi.mocked(reviewPolicyTheme).mockRejectedValue(new Error('模型接口超时'))
    await render(); await detail(); await click('更新 AI 研判')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('已保留上次结果')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('观察设备订单能否逐步兑现')
  })

  it('replaces the visible analysis on a successful manual review', async () => {
    vi.mocked(reviewPolicyTheme).mockResolvedValue({ ...review, reviewedAt: '2026-10-17T01:00:00Z', trendPlan: { ...review.trendPlan!, thesis: '新的验证重点' } })
    await render(); await detail(); await click('更新 AI 研判')
    expect(host.textContent).toContain('新的验证重点')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('研判记录 · 2')
  })

  it('keeps a new manual review when an older history response arrives later', async () => {
    await render()
    let resolveHistory!: (value: PolicyAiReview[]) => void
    vi.mocked(fetchPolicyReviewHistory).mockReturnValueOnce(new Promise(resolve => { resolveHistory = resolve }))
    await click('读取结果')
    const latest = { ...review, reviewedAt: '2026-10-17T01:00:00Z', trendPlan: { ...review.trendPlan!, thesis: '刚完成的新研判' } }
    vi.mocked(reviewPolicyTheme).mockResolvedValue(latest)
    await detail(); await click('更新 AI 研判')
    expect(host.textContent).toContain('刚完成的新研判')

    await act(async () => { resolveHistory([review]) })

    expect(host.textContent).toContain('刚完成的新研判')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('研判记录 · 2')
    await click('关闭详情')
    vi.mocked(fetchPolicyReviewHistory).mockResolvedValue([review, latest, review])
    await click('读取结果'); await detail()
    expect(host.textContent).toContain('刚完成的新研判')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('研判记录 · 2')
  })

  it('shows model-specific risks in both the current analysis and saved history', async () => {
    vi.mocked(fetchPolicyReviewHistory).mockResolvedValue([{ ...review, risks: ['新增产能可能压低行业利润率'] }])
    await render(); await detail()
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('新增产能可能压低行业利润率')
    await click('研判记录 · 1')
    expect(document.querySelector('[role="dialog"] details')?.textContent).toContain('新增产能可能压低行业利润率')
  })

  it('prevents manual model calls when configuration is unavailable', async () => {
    useAppStore.setState({ llmConfig: null })
    await render(); await detail()
    expect([...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(button => button.textContent === '更新 AI 研判')?.disabled).toBe(true)
    expect(reviewPolicyTheme).not.toHaveBeenCalled()
  })

  it('starts a background collection and polls until the saved snapshot is ready', async () => {
    vi.useFakeTimers()
    vi.mocked(fetchPolicyDaily).mockResolvedValueOnce({ ...daily, status: 'IDLE', themes: [], reviewedThemeCodes: [] }).mockResolvedValue(daily)
    vi.mocked(refreshPolicyDaily).mockResolvedValue({ ...daily, status: 'RUNNING', themes: [], reviewedThemeCodes: [] })
    await render(); await click('立即采集与研判')
    expect(host.textContent).toContain('正在采集与研判')
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(host.textContent).toContain('本轮完成')
    expect(host.textContent).toContain(theme.name)
    expect(refreshPolicyDaily).toHaveBeenCalledTimes(1)
  })

  it('keeps the watchlist in this browser and filters by it', async () => {
    await render(); await click(`关注${theme.name}`)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    await click('我的关注')
    expect(host.textContent).toContain(theme.name)
    expect(JSON.parse(localStorage.getItem('ai-stock.policy-tracked.v1')!)).toEqual([theme.themeCode])
    await click(`取消关注${theme.name}`)
    expect(host.textContent).toContain('没有符合筛选条件的产业')
  })
})
