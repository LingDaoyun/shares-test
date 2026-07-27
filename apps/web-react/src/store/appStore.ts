import { create } from 'zustand'
import type {
  LlmConfigPreview,
  RuleDefinition,
  RuntimeConfigSnapshot
} from '../types'
import {
  fetchLlmConfig,
  fetchRules,
  fetchRuntimeConfig
} from '../api/client'
import { extractErrorMessage } from '../lib/format'
import { toast } from '../components/ui/Toast'
import { defaultLlmRuntimeConfig, defaultPolicySources } from '../lib/runtimeConfigDefaults'

export function emptyRuntimeConfig(): RuntimeConfigSnapshot {
  return {
    dataId: 'ai-stock-api.yml',
    group: 'AI_STOCK',
    llm: defaultLlmRuntimeConfig(),
    policySources: defaultPolicySources(),
    updatedAt: new Date().toISOString()
  }
}

interface AppState {
  rules: RuleDefinition[]

  llmConfig: LlmConfigPreview | null
  runtimeConfig: RuntimeConfigSnapshot | null
  runtimeConfigForm: RuntimeConfigSnapshot

  loading: boolean
  configLoading: boolean
  runtimeConfigLoading: boolean
  runtimeConfigSaving: boolean

  loadAll: () => Promise<void>
  refreshLlmConfig: () => Promise<void>
  loadRuntimeConfig: () => Promise<void>
  setRuntimeConfigForm: (form: RuntimeConfigSnapshot) => void
}

export const useAppStore = create<AppState>((set) => ({
  rules: [],

  llmConfig: null,
  runtimeConfig: null,
  runtimeConfigForm: emptyRuntimeConfig(),

  loading: false,
  configLoading: false,
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

  refreshLlmConfig: async () => {
    set({ configLoading: true })
    try {
      const cfg = await fetchLlmConfig()
      set({ llmConfig: cfg })
    } catch (e) {
      toast.error(`模型配置加载失败：${extractErrorMessage(e)}`)
    } finally {
      set({ configLoading: false })
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
