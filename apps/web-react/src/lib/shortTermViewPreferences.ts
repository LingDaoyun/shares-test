export type ShortTermViewPreferences = {
  marketSentimentVisible: boolean
  fundFlowVisible: boolean
  hotDirectionsVisible: boolean
}

export const SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY = 'ai-stock.short-term.result-view.v1'

export function defaultShortTermViewPreferences(): ShortTermViewPreferences {
  return {
    marketSentimentVisible: true,
    fundFlowVisible: false,
    hotDirectionsVisible: true
  }
}

export function loadShortTermViewPreferences(): ShortTermViewPreferences {
  const defaults = defaultShortTermViewPreferences()

  if (typeof window === 'undefined') {
    return defaults
  }

  try {
    const storage = window.localStorage
    const raw = storage.getItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY)
    if (!raw) return defaults
    const parsed = JSON.parse(raw) as Partial<Record<keyof ShortTermViewPreferences, unknown>>
    return {
      marketSentimentVisible: typeof parsed.marketSentimentVisible === 'boolean' ? parsed.marketSentimentVisible : defaults.marketSentimentVisible,
      fundFlowVisible: typeof parsed.fundFlowVisible === 'boolean' ? parsed.fundFlowVisible : defaults.fundFlowVisible,
      hotDirectionsVisible: typeof parsed.hotDirectionsVisible === 'boolean' ? parsed.hotDirectionsVisible : defaults.hotDirectionsVisible
    }
  } catch {
    return defaults
  }
}

export function saveShortTermViewPreferences(preferences: ShortTermViewPreferences) {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(SHORT_TERM_VIEW_PREFERENCES_STORAGE_KEY, JSON.stringify(preferences))
  } catch {
    // Ignore storage failures and keep the page usable.
  }
}
