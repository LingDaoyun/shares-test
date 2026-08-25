import { create } from 'zustand'
import type { RuleDefinition, RuntimeConfigSnapshot } from '../types'
import { fetchRules, fetchRuntimeConfig } from '../api/client'
import { extractErrorMessage } from '../lib/format'
import { toast } from '../components/ui/Toast'
import { defaultLlmRuntimeConfig, defaultPolicySources } from '../lib/runtimeConfigDefaults'

export function emptyRuntimeConfig(): RuntimeConfigSnapshot {
  return {
    storage: 'database',
    llmRevision: 0,
    policySourcesRevision: 0,
    llm: defaultLlmRuntimeConfig(),
    policySources: defaultPolicySources(),
    updatedAt: new Date().toISOString()
  }
}

interface AppState {
  rules: RuleDefinition[]

  runtimeConfig: RuntimeConfigSnapshot | null
  runtimeConfigForm: RuntimeConfigSnapshot

  loading: boolean
  runtimeConfigLoading: boolean
  runtimeConfigSaving: boolean

  loadAll: () => Promise<void>
  loadRuntimeConfig: () => Promise<void>
  setRuntimeConfigForm: (form: RuntimeConfigSnapshot) => void
}

export const useAppStore = create<AppState>((set) => ({
  rules: [],

  runtimeConfig: null,
  runtimeConfigForm: emptyRuntimeConfig(),

  loading: false,
  runtimeConfigLoading: false,
  runtimeConfigSaving: false,

  loadAll: async () => {
    set({ loading: true })
    try {
      const rules = await fetchRules()
      set({ rules })
    } catch (e) {
      set({ rules: [] })
      toast.error(`规则目录加载失败：${extractErrorMessage(e)}`)
    } finally {
      set({ loading: false })
    }
  },

  loadRuntimeConfig: async () => {
    set({ runtimeConfigLoading: true })
    try {
      const data = await fetchRuntimeConfig()
      set({
        runtimeConfig: data,
        runtimeConfigForm: { ...data, llm: { ...data.llm, apiKey: '' }, policySources: data.policySources.map((s) => ({ ...s })) }
      })
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      set({ runtimeConfigLoading: false })
    }
  },

  setRuntimeConfigForm: (form) => set({ runtimeConfigForm: form })
}))
