import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { CompositeScoreBadge, RightSideSignalTag } from './ShortTermCandidateIndicators'

describe('short-term candidate indicators', () => {
  it('renders the confirmation icon and emphasized capsule classes', () => {
    const markup = renderToStaticMarkup(<RightSideSignalTag signal="右侧早期确认" />)

    expect(markup).toContain('右侧早期确认')
    expect(markup).toContain('<svg')
    expect(markup).toContain('rounded-full')
    expect(markup).toContain('bg-emerald-50')
    expect(markup).toContain('text-emerald-800')
  })

  it('renders observation without the confirmation icon or emerald emphasis', () => {
    const markup = renderToStaticMarkup(<RightSideSignalTag signal="右侧早期观察" />)

    expect(markup).toContain('右侧早期观察')
    expect(markup).not.toContain('<svg')
    expect(markup).toContain('bg-sky-50')
    expect(markup).not.toContain('bg-emerald-50')
  })

  it('labels the numeric value as composite score', () => {
    const markup = renderToStaticMarkup(<CompositeScoreBadge value={94.3} />)

    expect(markup).toContain('综合分')
    expect(markup).toContain('94.3')
    expect(markup).toContain('whitespace-nowrap')
  })
})
