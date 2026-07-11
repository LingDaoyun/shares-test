import { create } from 'zustand'
import { addToWatchlist, fetchWatchlist, removeFromWatchlist } from '../api/client'
import type { WatchlistEntry } from '../types'

let loadPromise: Promise<void> | null = null

interface WatchlistState {
  entries: WatchlistEntry[]
  loaded: boolean
  loading: boolean
  load: (force?: boolean) => Promise<void>
  add: (symbol: string, note?: string) => Promise<WatchlistEntry>
  remove: (symbol: string) => Promise<void>
}

export const useWatchlistStore = create<WatchlistState>((set, get) => ({
  entries: [],
  loaded: false,
  loading: false,
  load: async (force = false) => {
    if (get().loaded && !force) return
    if (loadPromise && !force) return loadPromise
    set({ loading: true })
    loadPromise = fetchWatchlist()
      .then((entries) => set({ entries, loaded: true }))
      .finally(() => {
        set({ loading: false })
        loadPromise = null
      })
    return loadPromise
  },
  add: async (symbol, note = '') => {
    const entry = await addToWatchlist(symbol, note)
    set((state) => ({
      entries: [entry, ...state.entries.filter((item) => item.symbol !== entry.symbol)],
      loaded: true
    }))
    return entry
  },
  remove: async (symbol) => {
    await removeFromWatchlist(symbol)
    set((state) => ({ entries: state.entries.filter((item) => item.symbol !== symbol) }))
  }
}))
