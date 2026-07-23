import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import {
  DetailOverlay,
  acquireBodyScrollLock,
  isBackdropClose,
  isEscapeClose,
  resolveDetailSelection,
  resolveReturnFocus
} from './DetailOverlay'

describe('DetailOverlay', () => {
  it('only treats the backdrop itself as an outside close gesture', () => {
    const backdrop = new EventTarget()
    const child = new EventTarget()

    expect(isBackdropClose(backdrop, backdrop)).toBe(true)
    expect(isBackdropClose(child, backdrop)).toBe(false)
  })

  it('recognizes Escape without consuming unrelated keys', () => {
    expect(isEscapeClose('Escape')).toBe(true)
    expect(isEscapeClose('Escape', true)).toBe(false)
    expect(isEscapeClose('Enter')).toBe(false)
  })

  it('keeps body scrolling locked until the last overlay releases it', () => {
    const body = { style: { overflow: 'auto' } } as HTMLElement
    const releaseOuter = acquireBodyScrollLock(body)
    const releaseInner = acquireBodyScrollLock(body)

    expect(body.style.overflow).toBe('hidden')
    releaseOuter()
    expect(body.style.overflow).toBe('hidden')
    releaseInner()
    expect(body.style.overflow).toBe('auto')
  })

  it('falls back to the page focus target when the trigger was removed', () => {
    const removedTrigger = { isConnected: false } as HTMLElement
    const page = { isConnected: true } as HTMLElement

    expect(resolveReturnFocus(removedTrigger, page)).toBe(page)
  })

  it('renders an accessible labelled modal frame', () => {
    const html = renderToStaticMarkup(
      <DetailOverlay open title="股票详情" subtitle="基本面与交易证据" onClose={() => undefined}>
        <p>详情正文</p>
      </DetailOverlay>
    )

    expect(html).toContain('role="dialog"')
    expect(html).toContain('aria-modal="true"')
    expect(html).toContain('股票详情')
    expect(html).toContain('基本面与交易证据')
    expect(html).toContain('aria-label="关闭详情"')
  })

  it('does not select the first item implicitly', () => {
    const items = [{ symbol: '600000' }, { symbol: '000001' }]

    expect(resolveDetailSelection(items, null, (item) => item.symbol)).toBeNull()
    expect(resolveDetailSelection(items, 'missing', (item) => item.symbol)).toBeNull()
    expect(resolveDetailSelection(items, '600000', (item) => item.symbol)).toEqual({ symbol: '600000' })
  })
})
