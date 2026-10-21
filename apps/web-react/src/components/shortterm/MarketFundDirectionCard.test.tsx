// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it } from 'vitest'
import type { ShortTermIndustryFundDirection, ShortTermMarketFundDirection } from '../../types'
import { MarketFundDirectionCard } from './MarketFundDirectionCard'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const industry: ShortTermIndustryFundDirection = {
  code: 'BK0001',
  name: '测试行业',
  mainNetInflow: null,
  mainNetInflowRatio: null,
  superLargeNetInflow: null,
  largeNetInflow: null,
  advancing: 0,
  declining: 0,
  constituentCount: 0,
  concentrationPercent: null,
  sourceUrl: null
}

const direction: ShortTermMarketFundDirection = {
  topInflows: [industry],
  topOutflows: [],
  coveredIndustryCount: 3,
  expectedIndustryCount: 6,
  coverageRatio: 0.5,
  tradeDate: '2026-10-20',
  fetchedAt: '2026-10-21T09:00:00+08:00',
  sourceName: 'EASTMONEY_INDUSTRY_FUND_FLOW',
  dataGaps: []
}

let root: Root | null = null
afterEach(() => {
  if (root) act(() => root?.unmount())
  root = null
  document.body.innerHTML = ''
})

function renderCard(value: ShortTermMarketFundDirection | null) {
  return renderToStaticMarkup(<MarketFundDirectionCard direction={value} onSelectIndustry={() => {}} />)
}

describe('MarketFundDirectionCard', () => {
  it('keeps the data day separate from the collection time and preserves source coverage', () => {
    const html = renderCard(direction)
    expect(html).toContain('今日资金去向')
    expect(html).toContain('EASTMONEY_INDUSTRY_FUND_FLOW')
    expect(html).toMatch(/数据日[\s\S]*2026-10-20/)
    expect(html).toMatch(/抓取时间[\s\S]*2026-10-21 09:00:00/)
    expect(html).toContain('3/6')
    expect(html).toContain('50.00%')
  })

  it('labels an unavailable side without inventing a zero total', () => {
    const html = renderCard(direction)
    expect(html).toContain('测试行业')
    expect(html).toContain('主力流出暂不可用')
    expect(html).not.toContain('0.00 亿')
    expect(html).not.toContain('0 项')
  })

  it('shows missing values as pending while keeping real zeros', () => {
    const html = renderCard({
      ...direction,
      topInflows: [industry, { ...industry, code: 'BK0002', name: '零值行业', mainNetInflow: 0, mainNetInflowRatio: 0, concentrationPercent: 0 }],
      tradeDate: null,
      fetchedAt: null,
      coverageRatio: null
    })
    expect(html).toContain('待补')
    expect(html).toContain('待补充')
    expect(html).toContain('0.00 亿')
    expect(html).toContain('占比 0.00%')
    expect(html).toContain('集中 0.00%')
    expect(html).not.toContain('1970')
    expect(html).not.toContain('2026-10-21')
    const host = document.createElement('div')
    host.innerHTML = html
    expect(host.querySelector('dl')?.textContent).toContain('数据日 待补')
    expect(host.querySelector('dl')?.textContent).toContain('抓取时间 待补')
    expect(host.querySelector('dl')?.textContent).toContain('行业覆盖 3/6（待补）')
  })

  it('keeps the unavailable explanation and reported data gaps', () => {
    expect(renderCard(null)).toContain('行业资金流暂不可用')
    expect(renderCard({ ...direction, topInflows: [], dataGaps: ['来源未提供当日数据'] })).toContain('来源未提供当日数据')
  })

  it('treats an HTTP 200 unavailable snapshot as unknown coverage', () => {
    const html = renderCard({
      topInflows: [], topOutflows: [], coveredIndustryCount: 0, expectedIndustryCount: 0,
      coverageRatio: 0, tradeDate: null, fetchedAt: null,
      sourceName: '行业资金流未返回', dataGaps: ['行业资金流获取失败：来源超时']
    })
    const host = document.createElement('div')
    host.innerHTML = html
    expect(host.querySelector('dl')?.textContent).toContain('行业覆盖 待补')
    expect(html).not.toContain('0/0')
    expect(html).not.toContain('0.00%')
    expect(html).toContain('行业资金流获取失败：来源超时')
  })

  it('preserves zero coverage when the returned industry range is known', () => {
    const html = renderCard({ ...direction, topInflows: [], coveredIndustryCount: 0, coverageRatio: 0 })
    const host = document.createElement('div')
    host.innerHTML = html
    expect(host.querySelector('dl')?.textContent).toContain('行业覆盖 0/6（0.00%）')
  })

  it('passes the selected industry and flow to the overlay callback', async () => {
    const selected: Array<{ code: string; flow: string }> = []
    const host = document.createElement('div')
    document.body.appendChild(host)
    root = createRoot(host)
    await act(async () => {
      root?.render(<MarketFundDirectionCard
        title="资金流向看板"
        wide
        direction={{ ...direction, topOutflows: [{ ...industry, code: 'BK0003', name: '流出行业', mainNetInflow: -100000000 }] }}
        onSelectIndustry={(item, flow) => selected.push({ code: item.code, flow })}
      />)
    })
    expect(host.textContent).toContain('资金流向看板')
    const buttons = Array.from(host.querySelectorAll('button'))
    await act(async () => {
      buttons[0].click()
      buttons[1].click()
    })
    expect(selected).toEqual([{ code: 'BK0001', flow: 'INFLOW' }, { code: 'BK0003', flow: 'OUTFLOW' }])
  })
})
