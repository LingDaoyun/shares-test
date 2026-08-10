// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from 'vitest'
import {
  defaultShortTermViewPreferences,
  loadShortTermViewPreferences,
  saveShortTermViewPreferences,
  SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY
} from './shortTermViewPreferences'

describe('shortTermViewPreferences', () => {
  beforeEach(() => {
    window.localStorage.removeItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
  })

  it('falls back to the default result view when storage is empty', () => {
    expect(loadShortTermViewPreferences()).toEqual(defaultShortTermViewPreferences())
  })

  it('persists toggled result view preferences', () => {
    saveShortTermViewPreferences({
      methodologyVisible: true,
      marketSentimentVisible: false,
      fundFlowVisible: true,
      snapshotVisible: false,
      hotDirectionsVisible: false
    })

    expect(loadShortTermViewPreferences()).toEqual({
      methodologyVisible: true,
      marketSentimentVisible: false,
      fundFlowVisible: true,
      snapshotVisible: false,
      hotDirectionsVisible: false
    })
  })

  it('recovers from invalid stored payloads', () => {
    window.localStorage.setItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY, '{"methodologyVisible":"yes"}')

    expect(loadShortTermViewPreferences()).toEqual(defaultShortTermViewPreferences())
  })
})
