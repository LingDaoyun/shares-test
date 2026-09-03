// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import { loadPotentialLeaderPreferences, savePotentialLeaderPreferences } from './potentialLeaderPreferences'

describe('potentialLeaderPreferences', () => {
  beforeEach(() => window.localStorage.clear())

  it('defaults Growth and STAR boards to disabled', () => {
    expect(loadPotentialLeaderPreferences()).toEqual({ includeGrowth: false, includeStar: false })
  })

  it('persists only the two optional board switches', () => {
    savePotentialLeaderPreferences({ includeGrowth: true, includeStar: false })

    expect(loadPotentialLeaderPreferences()).toEqual({ includeGrowth: true, includeStar: false })
    expect(window.localStorage.getItem('ai-stock:potential-leader:boards:v1')).toBe(
      JSON.stringify({ includeGrowth: true, includeStar: false })
    )
  })

  it('falls back safely when local data is malformed', () => {
    window.localStorage.setItem('ai-stock:potential-leader:boards:v1', '{bad json')

    expect(loadPotentialLeaderPreferences()).toEqual({ includeGrowth: false, includeStar: false })
  })
})
