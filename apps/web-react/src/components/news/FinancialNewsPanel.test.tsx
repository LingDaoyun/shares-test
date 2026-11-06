// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import axios from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PolicyIndustryPage } from '../../pages/PolicyIndustryPage'
import { fetchPolicyDaily, fetchPolicyReviewHistory, refreshPolicyDaily, reviewPolicyTheme } from '../../api/client'
import { useAppStore } from '../../store/appStore'

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('../../api/client', () => ({ fetchPolicyDaily: vi.fn(), fetchPolicyReviewHistory: vi.fn(), refreshPolicyDaily: vi.fn(), reviewPolicyTheme: vi.fn() }))

const media = { id: 'media-1', category: 'MEDIA', title: '市场资金动态', source: '财经媒体', url: 'https://example.com/media', publishedAt: '2026-11-05', summary: '原始摘要：资金面出现变化。' }
const official = { id: 'official-1', category: 'OFFICIAL', title: '金融政策发布', source: '中国人民银行', url: 'https://example.com/policy', publishedAt: '2026-11-04T16:30:00Z', summary: null }
const older = { ...media, id: 'older', title: '一周内新闻', url: 'https://example.com/older', publishedAt: '2026-11-01' }
const previous = { ...media, id: 'previous', title: '上月新闻', url: 'https://example.com/previous', publishedAt: '2026-10-10' }
const unknown = { ...media, id: 'unknown', title: '日期待核验新闻', url: 'javascript:alert(1)', publishedAt: null }
const analysis = { itemId: media.id, summary: '仅依据已采集摘要分析。', affectedSectors: ['银行'], transmission: '资金成本变化可能影响融资需求。', watchPoints: ['观察实际投放'], risks: ['尚不能确认实际订单'], evidenceUrl: media.url, analyzedAt: '2026-11-05T00:05:00Z', dataGaps: ['尚无全文'] }
const snapshot = { status: 'PARTIAL', startedAt: null, completedAt: '2026-11-05T00:00:00Z', items: [previous, unknown, media, older, official], sources: [
  { id: 'media', name: '财经媒体', category: 'MEDIA', url: 'https://example.com', status: 'READY', itemCount: 3, lastAttemptAt: '2026-11-05T00:00:00Z', lastSuccessAt: '2026-11-05T00:00:00Z', message: '已读取消息' },
  { id: 'official', name: '中国人民银行', category: 'OFFICIAL', url: 'https://example.com', status: 'FAILED', itemCount: 0, lastAttemptAt: '2026-11-05T00:00:00Z', lastSuccessAt: '2026-11-04T00:00:00Z', message: '来源暂不可用，保留上次消息' }
], analyses: {}, dataGaps: ['一个来源更新失败'] }

function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('financial news read, filter and analysis workflow', () => {
  let host: HTMLDivElement
  let root: Root
  let unmounted: boolean
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-05T00:10:00Z'))
    vi.mocked(axios.get).mockResolvedValue({ data: snapshot })
    vi.mocked(axios.post).mockResolvedValue({ data: { ...snapshot, status: 'RUNNING' } })
    vi.mocked(fetchPolicyDaily).mockResolvedValue({ status: 'READY', scheduledTime: '每天08:30', startedAt: null, completedAt: null, themes: [], reviewedThemeCodes: [], dataGaps: [] })
    vi.mocked(fetchPolicyReviewHistory).mockResolvedValue([])
    useAppStore.setState({ llmConfig: { provider: 'deepseek', model: 'test', apiKeyConfigured: true } as never })
    host = document.createElement('div'); document.body.append(host); root = createRoot(host); unmounted = false
  })
  afterEach(() => {
    if (!unmounted) act(() => root.unmount())
    host.remove(); vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })
  async function render() { await act(async () => { root.render(<PolicyIndustryPage />) }) }
  function button(label: string, scope: ParentNode = document) {
    const found = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === label || item.getAttribute('aria-label') === label)
    expect(found, `button ${label}`).toBeDefined(); return found!
  }
  async function click(label: string) { await act(async () => { button(label).click() }) }
  async function advance(ms: number) { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
  async function select(label: string, value: string) {
    const element = host.querySelector<HTMLSelectElement>(`select[aria-label="${label}"]`)!
    expect(element).not.toBeNull()
    await act(async () => { element.value = value; element.dispatchEvent(new Event('change', { bubbles: true })) })
  }
  function titles() { return [...host.querySelectorAll('[data-news-title]')].map(element => element.textContent) }

  it('opens a read-only message list and mounts industry research only after selecting its tab', async () => {
    await render()
    expect(host.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toBe('消息列表')
    expect(axios.get).toHaveBeenCalledWith('/api/news/current', expect.any(Object))
    expect(axios.post).not.toHaveBeenCalled()
    expect(fetchPolicyDaily).not.toHaveBeenCalled()
    expect(fetchPolicyReviewHistory).not.toHaveBeenCalled()
    expect(host.textContent).toContain(media.title)
    await click('行业解读')
    expect(fetchPolicyDaily).toHaveBeenCalledTimes(1)
    expect(refreshPolicyDaily).not.toHaveBeenCalled()
    expect(reviewPolicyTheme).not.toHaveBeenCalled()
  })

  it('sorts source dates, preserves date-only precision, and filters by Beijing day, source, category and keywords', async () => {
    await render()
    expect(titles()).toEqual([official.title, media.title, older.title, previous.title, unknown.title])
    const dateOnly = host.querySelector(`[data-news-id="${media.id}"] time`)
    expect(dateOnly?.textContent).toBe('仅日期')
    expect(dateOnly?.getAttribute('title')).toBe('2026-11-05')
    await click('今日')
    expect(titles()).toEqual([official.title, media.title])
    await click('近7天')
    expect(titles()).toEqual([official.title, media.title, older.title])
    await select('消息类别', 'OFFICIAL')
    expect(titles()).toEqual([official.title])
    await select('消息类别', 'ALL'); await select('消息来源', media.source)
    expect(titles()).toEqual([media.title, older.title])
    const input = host.querySelector<HTMLInputElement>('input[aria-label="搜索消息"]')!
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, '一周内')
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
    expect(titles()).toEqual([older.title])
  })

  it('refreshes explicitly, retains messages while running, then reads at five seconds and every minute', async () => {
    await render(); await click('刷新消息')
    expect(axios.post).toHaveBeenCalledWith('/api/news/refresh', undefined, expect.any(Object))
    expect(host.textContent).toContain(media.title)
    expect(button('正在更新').disabled).toBe(true)
    vi.mocked(axios.get).mockResolvedValue({ data: { ...snapshot, status: 'READY', items: [{ ...media, title: '新消息已到达' }] } })
    await advance(4999); expect(axios.get).toHaveBeenCalledTimes(1)
    await advance(1); expect(host.textContent).toContain('新消息已到达')
    await advance(59999); expect(axios.get).toHaveBeenCalledTimes(2)
    await advance(1); expect(axios.get).toHaveBeenCalledTimes(3)
    expect(axios.post).toHaveBeenCalledTimes(1)
  })

  it('preserves messages after read and refresh failures and exposes per-source last success', async () => {
    await render()
    expect(host.querySelector('details[aria-label="来源状态"]')?.textContent).toContain('上次成功')
    expect(host.textContent).toContain('来源暂不可用，保留上次消息')
    vi.mocked(axios.get).mockRejectedValueOnce(new Error('offline'))
    await advance(60000)
    expect(host.textContent).toContain(media.title)
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('offline')
    vi.mocked(axios.post).mockRejectedValueOnce(new Error('refresh failed'))
    await click('刷新消息')
    expect(host.textContent).toContain(media.title)
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('refresh failed')
  })

  it('opens original facts without an AI request and saves an on-demand analysis across later reads', async () => {
    await render(); await click(`查看消息：${media.title}`)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(media.summary)
    expect(document.querySelector('[role="dialog"] a')?.getAttribute('href')).toBe(media.url)
    expect(axios.post).not.toHaveBeenCalled()
    vi.mocked(axios.post).mockResolvedValueOnce({ data: analysis })
    await click('AI 解读')
    expect(axios.post).toHaveBeenCalledWith(`/api/news/${media.id}/analysis`, undefined, expect.any(Object))
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.transmission)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('推演')
    await advance(60000)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    await click('关闭详情'); await click(`查看消息：${media.title}`)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    expect(axios.post).toHaveBeenCalledTimes(1)
  })

  it('shows cached analyses without calling the model and keeps news accessible when the model is unavailable', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, analyses: { [media.id]: analysis } } })
    useAppStore.setState({ llmConfig: { apiKeyConfigured: false } as never })
    await render(); await click(`查看消息：${media.title}`)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    expect(axios.post).not.toHaveBeenCalled()
    await click('关闭详情'); await click(`查看消息：${official.title}`)
    const frame = document.querySelector<HTMLIFrameElement>('[role="dialog"] iframe')
    expect(frame?.getAttribute('src')).toBe(official.url)
    expect(frame?.title).toContain(official.title)
    expect(frame?.getAttribute('sandbox')).not.toContain('allow-top-navigation')
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain('暂无摘要')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('模型尚未配置')
    expect(button('AI 解读').disabled).toBe(true)
    await click('关闭详情'); await click(`查看消息：${unknown.title}`)
    expect(document.querySelector('a[href^="javascript:"]')).toBeNull()
    expect(document.querySelector('[role="dialog"] iframe')).toBeNull()
  })

  it('opens a source that denies embedding through an original-page link without rendering a blocked frame', async () => {
    const restricted = { ...official, url: 'https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=1277195&itemId=915', source: '国家金融监督管理总局' }
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, items: [restricted] } })
    await render(); await click(`查看消息：${restricted.title}`)
    expect(document.querySelector('[role="dialog"] iframe')).toBeNull()
    const link = document.querySelector<HTMLAnchorElement>('[role="dialog"] a[data-original-page]')
    expect(link?.getAttribute('href')).toBe(restricted.url)
    expect(link?.target).toBe('_blank')
    expect(link?.rel).toContain('noopener')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('不支持在此内嵌显示')
    expect(axios.post).not.toHaveBeenCalled()
  })

  it('keeps an analysis failure visible and supports retry without collecting news again', async () => {
    await render(); await click(`查看消息：${media.title}`)
    vi.mocked(axios.post).mockRejectedValueOnce(new Error('model unavailable')).mockResolvedValueOnce({ data: analysis })
    await click('AI 解读')
    expect(document.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain('model unavailable')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(media.summary)
    await click('AI 解读')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    expect(axios.post).toHaveBeenCalledTimes(2)
  })

  it('treats a 200 response without analyzedAt as a retryable failure and ignores unsuccessful saved analyses', async () => {
    const unsuccessful = { ...analysis, analyzedAt: null, summary: '', dataGaps: ['模型未返回有效解读'] }
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, analyses: { [media.id]: unsuccessful } } })
    await render(); await click(`查看消息：${media.title}`)
    expect(button('AI 解读').disabled).toBe(false)
    vi.mocked(axios.post).mockResolvedValueOnce({ data: unsuccessful }).mockResolvedValueOnce({ data: analysis })
    await click('AI 解读')
    expect(document.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain('模型未返回有效解读')
    expect(button('AI 解读').disabled).toBe(false)
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain('已保存')
    await click('AI 解读')
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, analyses: { [media.id]: unsuccessful } } })
    await advance(60000)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
  })

  it('clears a saved analysis when the same news id has updated source content', async () => {
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, analyses: { [media.id]: analysis } } })
    await render(); await click(`查看消息：${media.title}`)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(analysis.summary)
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, items: [{ ...media, summary: '来源更正后的摘要' }] } })
    await advance(60000)
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('来源更正后的摘要')
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain(analysis.summary)
    expect(button('AI 解读').disabled).toBe(false)
  })

  it('does not attach an in-flight analysis to news whose summary changed during the request', async () => {
    const pending = deferred<{ data: typeof analysis }>()
    vi.mocked(axios.post).mockReturnValueOnce(pending.promise)
    await render(); await click(`查看消息：${media.title}`); await click('AI 解读')
    vi.mocked(axios.get).mockResolvedValueOnce({ data: { ...snapshot, items: [{ ...media, summary: '请求期间更新的摘要' }] } })
    await advance(60000)
    await act(async () => pending.resolve({ data: analysis }))
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain('请求期间更新的摘要')
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain(analysis.summary)
    expect(button('AI 解读').disabled).toBe(false)
  })

  it('ignores an older read when refresh has already been accepted and stops polling after unmount', async () => {
    await render()
    const oldRead = deferred<{ data: typeof snapshot }>()
    vi.mocked(axios.get).mockReturnValueOnce(oldRead.promise)
    await click('读取结果'); await click('刷新消息')
    await act(async () => oldRead.resolve({ data: { ...snapshot, items: [] } }))
    expect(host.textContent).toContain(media.title)
    expect(button('正在更新').disabled).toBe(true)
    act(() => root.unmount()); unmounted = true
    const count = vi.mocked(axios.get).mock.calls.length
    await advance(120000)
    expect(axios.get).toHaveBeenCalledTimes(count)
  })
})
