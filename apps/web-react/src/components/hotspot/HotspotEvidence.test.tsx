import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { EvidenceLink, NewsSources } from './HotspotEvidence'

describe('HotspotEvidence source provenance', () => {
  it.each(['javascript:alert(1)', 'data:text/html,hello', '/relative-source', 'not a URL', null])('keeps source text without linking unsafe or incomplete URLs: %s', url => {
    const html = renderToStaticMarkup(<EvidenceLink url={url}>公司资料来源</EvidenceLink>)
    expect(html).toBe('<span>公司资料来源</span>')
  })

  it('opens an external source safely and escapes source content', () => {
    const html = renderToStaticMarkup(<EvidenceLink url="https://example.com/source">{'<script>来源原文</script>'}</EvidenceLink>)
    expect(html).toContain('href="https://example.com/source"')
    expect(html).toContain('target="_blank" rel="noopener noreferrer"')
    expect(html).toContain('&lt;script&gt;来源原文&lt;/script&gt;')
    expect(html).not.toContain('<script>')
  })

  it('preserves missing publication time instead of presenting it as a fresh article', () => {
    const html = renderToStaticMarkup(<NewsSources news={[{
      id: 'undated', title: '没有发布时间的新闻', source: '新闻源', url: 'https://example.com/undated', publishedAt: null, excerpt: '原始摘要'
    }]} excerpts />)
    expect(html).toContain('发布于 —')
    expect(html).toContain('原始摘要')
    expect(html).toContain('新闻源')
  })
})
