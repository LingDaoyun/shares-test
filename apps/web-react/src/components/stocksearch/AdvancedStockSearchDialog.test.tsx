// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from '../ui/Toast'
import { AdvancedStockSearchDialog } from './AdvancedStockSearchDialog'
import type { StockSearchFilterDraft } from './StockSearchFilterFields'

describe('AdvancedStockSearchDialog', () => {
  let host: HTMLDivElement
  let root: Root
  const onApply = vi.fn()
  const onClose = vi.fn()
  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    vi.spyOn(toast, 'warning').mockImplementation(() => undefined)
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { callback(0); return 1 })
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => undefined)
    host = document.createElement('div'); document.body.append(host); root = createRoot(host)
  })
  afterEach(() => {
    act(() => root.unmount()); host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })
  async function render(initialValue: StockSearchFilterDraft) {
    await act(async () => root.render(<AdvancedStockSearchDialog initialValue={initialValue} onApply={onApply} onClose={onClose} />))
  }
  async function fill(name: string, value: string) {
    await act(async () => {
      const input = document.querySelector<HTMLInputElement>(`input[name="${name}"]`)!
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
    })
  }
  async function click(label: string) {
    const button = Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find(item => item.textContent === label)!
    await act(async () => { button.click() })
  }

  it('keeps edits local and cancels without changing the supplied values', async () => {
    const initialValue = Object.freeze({ excludeChiNext: true, excludeStar: true, excludeSt: true, minPrice: '10' })
    await render(initialValue)
    expect(document.querySelector<HTMLInputElement>('input[name="excludeBeijing"]')!.checked).toBe(false)
    await fill('minPrice', '20')
    await act(async () => { document.querySelector<HTMLInputElement>('input[name="excludeChiNext"]')!.click() })
    await click('取消')
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(onApply).not.toHaveBeenCalled()
    expect(initialValue).toEqual({ excludeChiNext: true, excludeStar: true, excludeSt: true, minPrice: '10' })
  })

  it('applies a validated draft while leaving saving and closing to the parent', async () => {
    await render({ excludeStar: true })
    await fill('minChangePercent', '-2')
    await fill('maxChangePercent', '5')
    await fill('minMarketCap', '50')
    await click('应用条件')
    expect(onApply).toHaveBeenCalledExactlyOnceWith({ excludeStar: true, minChangePercent: '-2', maxChangePercent: '5', minMarketCap: '50' })
    expect(onClose).not.toHaveBeenCalled()
    expect(toast.warning).not.toHaveBeenCalled()
  })

  it('warns about an invalid range and preserves the open dialog and entered values', async () => {
    await render({ minMarketCap: '300', maxMarketCap: '50' })
    await click('应用条件')
    expect(toast.warning).toHaveBeenCalledExactlyOnceWith('总市值下限不能大于上限')
    expect(onApply).not.toHaveBeenCalled()
    expect(onClose).not.toHaveBeenCalled()
    expect(document.querySelector('[role="dialog"]')).not.toBeNull()
    expect(document.querySelector<HTMLInputElement>('input[name="minMarketCap"]')!.value).toBe('300')
    expect(document.querySelector<HTMLInputElement>('input[name="maxMarketCap"]')!.value).toBe('50')
  })
})
