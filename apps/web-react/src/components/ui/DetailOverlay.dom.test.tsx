// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DetailOverlay } from './DetailOverlay'

describe('DetailOverlay DOM behavior', () => {
  let host: HTMLDivElement
  let trigger: HTMLButtonElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    host = document.createElement('div')
    trigger = document.createElement('button')
    trigger.textContent = '打开详情'
    document.body.append(trigger, host)
    trigger.focus()
    root = createRoot(host)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    trigger.remove()
    document.body.style.overflow = ''
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  function render(open: boolean, onClose: () => void) {
    act(() => {
      root.render(
        <DetailOverlay open={open} title="股票详情" onClose={onClose}>
          <button type="button">详情内操作</button>
        </DetailOverlay>
      )
    })
  }

  it('closes only when the backdrop itself is pressed', () => {
    const onClose = vi.fn()
    render(true, onClose)
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    const backdrop = dialog?.parentElement

    expect(dialog).not.toBeNull()
    expect(backdrop).not.toBeNull()
    act(() => dialog?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })))
    expect(onClose).not.toHaveBeenCalled()
    act(() => backdrop?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape but respects an already consumed Escape', () => {
    const onClose = vi.fn()
    render(true, onClose)
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')
    const consumed = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    consumed.preventDefault()

    act(() => dialog?.dispatchEvent(consumed))
    expect(onClose).not.toHaveBeenCalled()
    act(() => dialog?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('locks background scrolling and restores focus after closing', () => {
    render(true, () => undefined)

    expect(document.body.style.overflow).toBe('hidden')
    expect(document.activeElement?.getAttribute('role')).toBe('dialog')

    render(false, () => undefined)
    expect(document.body.style.overflow).toBe('')
    expect(document.activeElement).toBe(trigger)
  })
})
