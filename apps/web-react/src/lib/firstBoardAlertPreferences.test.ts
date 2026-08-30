// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import { loadFirstBoardBoardPreferences, saveFirstBoardBoardPreferences } from './firstBoardAlertPreferences'

describe('firstBoardAlertPreferences', () => {
  beforeEach(() => window.localStorage.clear())

  it('defaults growth and STAR boards to disabled', () => {
    expect(loadFirstBoardBoardPreferences()).toEqual({ includeGrowth: false, includeStar: false })
  })

  it('persists only the two board switches', () => {
    saveFirstBoardBoardPreferences({ includeGrowth: true, includeStar: false })
    expect(loadFirstBoardBoardPreferences()).toEqual({ includeGrowth: true, includeStar: false })
  })

  it('falls back safely when local data is malformed', () => {
    window.localStorage.setItem('ai-stock:first-board-alert:boards:v1', '{bad json')
    expect(loadFirstBoardBoardPreferences()).toEqual({ includeGrowth: false, includeStar: false })
  })
})
