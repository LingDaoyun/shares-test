import type { PotentialLeaderPreferences } from '../types'

const STORAGE_KEY = 'ai-stock:potential-leader:boards:v1'
const DEFAULTS: PotentialLeaderPreferences = { includeGrowth: false, includeStar: false }

export function loadPotentialLeaderPreferences(storage: Storage | null = browserStorage()): PotentialLeaderPreferences {
  if (!storage) return { ...DEFAULTS }
  try {
    const parsed = JSON.parse(storage.getItem(STORAGE_KEY) ?? '{}') as Partial<PotentialLeaderPreferences>
    return {
      includeGrowth: parsed.includeGrowth === true,
      includeStar: parsed.includeStar === true
    }
  } catch {
    return { ...DEFAULTS }
  }
}

export function savePotentialLeaderPreferences(
  preferences: PotentialLeaderPreferences,
  storage: Storage | null = browserStorage()
) {
  if (!storage) return
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify({
      includeGrowth: preferences.includeGrowth === true,
      includeStar: preferences.includeStar === true
    }))
  } catch {
    // 浏览器禁用本地存储时仍允许页面按本次选择工作。
  }
}

function browserStorage() {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}
