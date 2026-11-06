// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { FinancialNewsPanel } from './FinancialNewsPanel'

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
vi.mock('../../api/client', () => ({ fetchLlmConfig: vi.fn(), fetchRules: vi.fn(), fetchRuntimeConfig: vi.fn() }))
const official = { id: 'policy', category: 'OFFICIAL', title: '央行宣布下调政策利率', source: '中国人民银行', url: 'https://www.pbc.gov.cn/policy', publishedAt: '2026-11-05T16:30:00Z', summary: null }
const fiscal = { ...official, id: 'fiscal', title: '财政部公布国债发行计划', publishedAt: '2026-11-05', url: 'https://www.mof.gov.cn/debt' }
const company = { ...official, id: 'company', category: 'MEDIA', title: '某公司：融资净买入100万元', source: '财经媒体', publishedAt: '2026-11-06T09:00:00+08:00', url: 'https://example.com/company' }
const undated = { ...company, id: 'unknown', title: '综合动态消息', publishedAt: null }
const data = { status: 'READY', startedAt: null, completedAt: '2026-11-06T01:00:00Z', items: [undated, fiscal, official, company], sources: [], analyses: {}, dataGaps: [] }

describe('news topics and publication timeline', () => {
  let host: HTMLDivElement
  let root: Root
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.resetAllMocks(); vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-06T02:00:00Z'))
    vi.mocked(axios.get).mockResolvedValue({ data })
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => { act(() => root.unmount()); host.remove(); vi.useRealTimers(); delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT })
  async function render() { await act(async () => { root.render(<FinancialNewsPanel />) }) }
  async function click(label: string) {
    const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(b => b.getAttribute('aria-label') === label || b.textContent === label)
    expect(button).toBeDefined()
    await act(async () => { button!.click() })
  }
  const titles = () => [...host.querySelectorAll('[data-news-title]')].map(e => e.textContent)

  it('groups by Beijing publication date with an honest date-only time and unknown dates last', async () => {
    await render()
    expect([...host.querySelectorAll('[data-news-day]')].map(e => e.getAttribute('data-news-day'))).toEqual(['2026-11-06', '2026-11-05', 'unknown'])
    expect(host.querySelector('[data-news-day="2026-11-06"]')?.textContent).toContain('今天')
    expect(host.querySelector('[data-news-day="2026-11-05"]')?.textContent).toContain('昨天')
    expect(host.querySelector('[data-news-id="policy"] time')?.textContent).toBe('00:30')
    expect(host.querySelector('[data-news-id="fiscal"] time')?.textContent).toBe('仅日期')
    expect(titles()).toEqual([company.title, official.title, fiscal.title, undated.title])
  })

  it('shows topic counts and combines topic selection with publication-date filters', async () => {
    await render()
    expect(host.querySelector('button[aria-label="宏观政策"]')?.textContent).toContain('2')
    await click('宏观政策')
    expect(titles()).toEqual([official.title, fiscal.title])
    await click('昨天')
    expect(titles()).toEqual([fiscal.title])
    await click('全部主题')
    expect(titles()).toEqual([fiscal.title])
    expect(axios.post).not.toHaveBeenCalled()
  })

  it('renders a bounded batch and resets the batch when filtering changes', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { ...data, items: Array.from({length:125}, (_,i)=>({...company,id:`company-${i}`,title:`公司${i}：融资净买入100万元`})) } })
    await render()
    expect(titles()).toHaveLength(60)
    await click('加载更多消息')
    expect(titles()).toHaveLength(120)
    await click('产业公司')
    expect(titles()).toHaveLength(60)
    expect(host.textContent).toContain('125')
  })

  it('resets only the inner list when switching filters and preserves its position when loading more', async () => {
    vi.mocked(axios.get).mockResolvedValue({ data: { ...data, items: Array.from({ length: 125 }, (_, i) => ({ ...company, id: `company-${i}` })) } })
    await render()
    const frame = host.querySelector<HTMLElement>('section[aria-label="按发布时间浏览消息"]')!
    const list = host.querySelector<HTMLElement>('[aria-label="消息列表滚动区域"]')!
    frame.scrollIntoView = vi.fn()
    list.scrollTop = 500
    await click('加载更多消息')
    expect(list.scrollTop).toBe(500)
    await click('产业公司')
    expect(list.scrollTop).toBe(0)
    expect(frame.scrollIntoView).not.toHaveBeenCalled()
  })
})
