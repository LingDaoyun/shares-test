import { describe, expect, it } from 'vitest'
import { rightSideSignalPresentation } from './shortTermRightSide'

describe('short-term right-side presentation', () => {
  it('emphasizes confirmation with a calm high-contrast capsule', () => {
    const result = rightSideSignalPresentation('右侧早期确认')

    expect(result.emphasized).toBe(true)
    expect(result.className).toContain('rounded-full')
    expect(result.className).toContain('bg-emerald-50')
    expect(result.className).toContain('border-emerald-300')
    expect(result.className).toContain('text-emerald-800')
  })

  it('keeps observation quieter than confirmation', () => {
    const result = rightSideSignalPresentation('右侧早期观察')

    expect(result.emphasized).toBe(false)
    expect(result.className).toContain('bg-sky-50')
    expect(result.className).not.toContain('bg-emerald-50')
  })

  it('keeps unknown states explicit and neutral', () => {
    const result = rightSideSignalPresentation(null)

    expect(result.label).toBe('右侧状态待确认')
    expect(result.emphasized).toBe(false)
    expect(result.className).not.toContain('emerald')
  })
})
