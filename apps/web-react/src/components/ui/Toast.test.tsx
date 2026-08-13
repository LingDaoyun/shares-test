// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast, ToastViewport, useToastStore } from './Toast'

describe('Toast lifecycle', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.useFakeTimers()
    useToastStore.setState({ toasts: [] })
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    act(() => root.render(<ToastViewport />))
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    useToastStore.setState({ toasts: [] })
    vi.useRealTimers()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
  })

  it('anchors the global notification stack at the top-right', () => {
    const viewport = host.firstElementChild as HTMLElement | null
    const classes = viewport?.className.split(/\s+/) ?? []

    expect(classes).toEqual(expect.arrayContaining(['fixed', 'top-6', 'right-6']))
    expect(classes).not.toContain('bottom-6')
  })

  it('keeps the 3200 ms default for existing unkeyed calls', () => {
    act(() => toast.info('普通通知'))
    expect(host.textContent).toContain('普通通知')

    act(() => vi.advanceTimersByTime(3199))
    expect(host.textContent).toContain('普通通知')

    act(() => vi.advanceTimersByTime(1))
    expect(host.textContent).not.toContain('普通通知')
  })

  it('keeps a persistent alert until its accessible close button is used', () => {
    act(() => toast.warning('需要人工确认', { persistent: true }))

    const alert = host.querySelector('[role="alert"]')
    expect(alert?.textContent).toContain('需要人工确认')
    expect(alert?.getAttribute('aria-live')).toBe('assertive')

    act(() => vi.advanceTimersByTime(60_000))
    expect(host.textContent).toContain('需要人工确认')

    const close = host.querySelector('button[aria-label="关闭通知"]')
    expect(close).not.toBeNull()
    act(() => close?.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    expect(host.textContent).not.toContain('需要人工确认')
  })

  it('replaces a keyed row and restarts it with the new 5000 ms policy', () => {
    act(() => toast.info('扫描中', { key: 'scan', persistent: true }))
    act(() => toast.success('扫描完成', { key: 'scan', durationMs: 5000 }))

    expect(useToastStore.getState().toasts).toHaveLength(1)
    expect(host.textContent).not.toContain('扫描中')
    expect(host.textContent).toContain('扫描完成')
    expect(host.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite')

    act(() => vi.advanceTimersByTime(4999))
    expect(host.textContent).toContain('扫描完成')

    act(() => vi.advanceTimersByTime(1))
    expect(host.textContent).not.toContain('扫描完成')
  })

  it('lets persistence override a supplied duration and supports keyed dismissal', () => {
    act(() => toast.error('不会自动消失', {
      key: 'persistent-error',
      durationMs: 10,
      persistent: true
    }))

    act(() => vi.advanceTimersByTime(10_000))
    expect(host.textContent).toContain('不会自动消失')

    act(() => toast.dismiss('persistent-error'))
    expect(host.textContent).not.toContain('不会自动消失')
  })

  it('recreates a terminal notification after the running row was manually closed', () => {
    act(() => toast.info('扫描中', { key: 'scan', persistent: true }))
    const close = host.querySelector('button[aria-label="关闭通知"]')
    act(() => close?.dispatchEvent(new MouseEvent('click', { bubbles: true })))
    expect(host.textContent).not.toContain('扫描中')

    act(() => toast.warning('扫描无结果：行情不足', {
      key: 'scan',
      persistent: true
    }))

    expect(useToastStore.getState().toasts).toHaveLength(1)
    expect(host.textContent).toContain('扫描无结果：行情不足')
  })
})
