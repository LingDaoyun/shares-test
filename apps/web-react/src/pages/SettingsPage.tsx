import { useState } from 'react'
import { Plus, RefreshCw, Check, Trash2 } from 'lucide-react'
import { useAppStore } from '../store/appStore'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Tag } from '../components/ui/Badge'
import { toast } from '../components/ui/Toast'
import {
  fetchLlmRuntimeConfig,
  fetchPolicySources,
  updateLlmRuntimeConfig,
  updatePolicySources
} from '../api/client'
import { extractErrorMessage } from '../lib/format'
import { defaultLlmRuntimeConfig, defaultPolicySources } from '../lib/runtimeConfigDefaults'
import type { PolicySourceConfig } from '../types'

const PROVIDERS = [
  {
    label: 'DeepSeek', value: 'deepseek', model: 'deepseek-v4-pro',
    baseUrl: 'https://api.deepseek.com', apiKeyEnv: 'DEEPSEEK_API_KEY',
    responseFormat: 'json_object', maxCompletionTokens: 8192
  },
  {
    label: 'OpenAI', value: 'openai', model: 'gpt-5.5',
    baseUrl: 'https://api.openai.com/v1', apiKeyEnv: 'OPENAI_API_KEY',
    responseFormat: 'json_schema', maxCompletionTokens: null
  },
  {
    label: 'Moonshot / Kimi 开放平台', value: 'moonshot', model: 'kimi-k2.6',
    baseUrl: 'https://api.moonshot.ai/v1', apiKeyEnv: 'MOONSHOT_API_KEY',
    responseFormat: 'json_schema', maxCompletionTokens: null
  },
  {
    label: 'Kimi Code', value: 'kimi-code', model: 'kimi-for-coding',
    baseUrl: 'https://api.kimi.com/coding/v1', apiKeyEnv: 'KIMI_API_KEY',
    responseFormat: 'json_schema', maxCompletionTokens: null
  }
]
const FORMATS = ['json_object', 'json_schema', 'none']

export function SettingsPage() {
  const form = useAppStore((s) => s.runtimeConfigForm)
  const setRuntimeConfigForm = useAppStore((s) => s.setRuntimeConfigForm)
  const refreshLlmConfig = useAppStore((s) => s.refreshLlmConfig)
  const [llmLoading, setLlmLoading] = useState(false)
  const [llmSaving, setLlmSaving] = useState(false)
  const [policyLoading, setPolicyLoading] = useState(false)
  const [policySaving, setPolicySaving] = useState(false)

  const patchLlm = (patch: Partial<typeof form.llm>) => {
    setRuntimeConfigForm({ ...form, llm: { ...form.llm, ...patch } })
  }

  const changeProvider = (provider: string) => {
    const defaults = PROVIDERS.find((item) => item.value === provider)
    if (!defaults) return
    patchLlm({
      provider,
      model: defaults.model,
      baseUrl: defaults.baseUrl,
      apiKeyEnv: defaults.apiKeyEnv,
      responseFormat: defaults.responseFormat,
      maxCompletionTokens: defaults.maxCompletionTokens,
      apiKey: '',
      apiKeyConfigured: false,
      apiKeySource: 'missing'
    })
  }

  const addPolicySource = () => {
    const next: PolicySourceConfig = { name: '新政策源', type: 'html', url: 'https://', weight: 80 }
    setRuntimeConfigForm({ ...form, policySources: [...form.policySources, next] })
  }
  const removePolicySource = (index: number) => {
    setRuntimeConfigForm({ ...form, policySources: form.policySources.filter((_, i) => i !== index) })
  }
  const patchPolicySource = (index: number, patch: Partial<PolicySourceConfig>) => {
    setRuntimeConfigForm({
      ...form,
      policySources: form.policySources.map((s, i) => (i === index ? { ...s, ...patch } : s))
    })
  }

  const replaceLlm = (llm: typeof form.llm) => {
    useAppStore.setState((state) => ({
      runtimeConfigForm: { ...state.runtimeConfigForm, llm }
    }))
  }

  const replacePolicySources = (policySources: PolicySourceConfig[]) => {
    useAppStore.setState((state) => ({
      runtimeConfigForm: {
        ...state.runtimeConfigForm,
        policySources: policySources.map((source) => ({ ...source }))
      }
    }))
  }

  const reloadLlm = async () => {
    setLlmLoading(true)
    try {
      const llm = await fetchLlmRuntimeConfig()
      replaceLlm({ ...llm, apiKey: '' })
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      setLlmLoading(false)
    }
  }

  const saveLlm = async () => {
    setLlmSaving(true)
    try {
      const apiKey = form.llm.apiKey?.trim()
      const updated = await updateLlmRuntimeConfig({
        ...form.llm,
        apiKey: apiKey ? apiKey : null
      })
      replaceLlm({ ...updated, apiKey: '' })
      toast.success('大模型配置已保存并生效')
      await refreshLlmConfig()
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      setLlmSaving(false)
    }
  }

  const resetLlm = () => {
    replaceLlm({
      ...defaultLlmRuntimeConfig(),
      apiKeyConfigured: form.llm.apiKeyConfigured,
      apiKeySource: form.llm.apiKeySource
    })
  }

  const reloadPolicySources = async () => {
    setPolicyLoading(true)
    try {
      replacePolicySources(await fetchPolicySources())
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      setPolicyLoading(false)
    }
  }

  const savePolicySources = async () => {
    setPolicySaving(true)
    try {
      const updated = await updatePolicySources(
        form.policySources.map((source) => ({ ...source }))
      )
      replacePolicySources(updated)
      toast.success('政策源配置已保存并生效')
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      setPolicySaving(false)
    }
  }

  const resetPolicySources = () => replacePolicySources(defaultPolicySources())

  return (
    <div className="flex flex-col gap-4">
      {/* 大模型配置 */}
      <Card>
        <div className="mb-4 flex items-center justify-between">
          <div>
            <div className="eyebrow mb-1">MODEL PROVIDER</div>
            <h3 className="text-lg font-semibold text-ink-900">大模型配置</h3>
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            <Tag tone={form.llm.apiKeyConfigured ? 'success' : 'danger'}>
              {form.llm.apiKeyConfigured ? 'Key 已配置' : 'Key 缺失'}
            </Tag>
            <Tag>数据库配置 · 模型修订 {form.llmRevision}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="Provider">
            <select className="field" value={form.llm.provider} onChange={(e) => changeProvider(e.target.value)}>
              {PROVIDERS.map((p) => (
                <option key={p.value} value={p.value}>{p.label}</option>
              ))}
            </select>
          </Field>
          <Field label="模型">
            <input className="field" value={form.llm.model} onChange={(e) => patchLlm({ model: e.target.value })} />
          </Field>
          <Field label="Base URL">
            <input className="field" value={form.llm.baseUrl} onChange={(e) => patchLlm({ baseUrl: e.target.value })} />
          </Field>
          <Field label="Response Format">
            <select className="field" value={form.llm.responseFormat} onChange={(e) => patchLlm({ responseFormat: e.target.value })}>
              {FORMATS.map((f) => (
                <option key={f} value={f}>{f}</option>
              ))}
            </select>
          </Field>
          <Field label="API Key">
            <input
              type="password"
              className="field"
              placeholder="留空则保留数据库中已有 Key"
              value={form.llm.apiKey ?? ''}
              onChange={(e) => patchLlm({ apiKey: e.target.value })}
            />
          </Field>
          <Field label="API Key 环境变量名">
            <input className="field" placeholder="例如 DEEPSEEK_API_KEY" value={form.llm.apiKeyEnv} onChange={(e) => patchLlm({ apiKeyEnv: e.target.value })} />
          </Field>
          <Field label="最大输出 Token">
            <input
              type="number"
              min={1}
              max={200000}
              className="field"
              value={form.llm.maxCompletionTokens ?? ''}
              onChange={(e) => patchLlm({ maxCompletionTokens: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </Field>
          <Field label="Temperature">
            <input
              type="number"
              min={0}
              max={2}
              step={0.1}
              className="field"
              value={form.llm.temperature ?? ''}
              onChange={(e) => patchLlm({ temperature: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </Field>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-4">
          <label className="flex cursor-pointer items-center gap-2">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-line text-brand-500 focus:ring-brand-200"
              checked={form.llm.strictJsonSchema}
              onChange={(e) => patchLlm({ strictJsonSchema: e.target.checked })}
            />
            <span className="text-sm text-ink-600">严格 JSON Schema</span>
          </label>
          <input
            className="field max-w-xs"
            placeholder="thinking 类型，可留空"
            value={form.llm.thinking ?? ''}
            onChange={(e) => patchLlm({ thinking: e.target.value || null })}
          />
        </div>

        <SectionActions
          loading={llmLoading}
          saving={llmSaving}
          onReload={() => void reloadLlm()}
          onReset={resetLlm}
          onSave={() => void saveLlm()}
        />
      </Card>

      {/* 政策源配置 */}
      <Card
        title="政策源配置"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Tag>数据库配置 · 政策源修订 {form.policySourcesRevision}</Tag>
            <Button icon={<Plus className="h-4 w-4" />} onClick={addPolicySource}>
              新增来源
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-2">
          {form.policySources.length === 0 ? (
            <span className="text-sm text-ink-400">暂无政策源，点击「新增来源」添加</span>
          ) : (
            form.policySources.map((source, index) => (
              <div key={index} className="grid grid-cols-1 items-center gap-2 rounded-lg border border-line-soft p-3 sm:grid-cols-[32px_1fr_120px_2fr_100px_40px]">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-50 text-xs font-bold text-brand-600">
                  {index + 1}
                </span>
                <input
                  className="field"
                  placeholder="来源名称"
                  value={source.name}
                  onChange={(e) => patchPolicySource(index, { name: e.target.value })}
                />
                <select
                  className="field"
                  value={source.type}
                  onChange={(e) => patchPolicySource(index, { type: e.target.value })}
                >
                  <option value="html">HTML</option>
                  <option value="json">JSON</option>
                </select>
                <input
                  className="field"
                  placeholder="政策源 URL"
                  value={source.url}
                  onChange={(e) => patchPolicySource(index, { url: e.target.value })}
                />
                <input
                  type="number"
                  min={1}
                  max={100}
                  className="field"
                  value={source.weight}
                  onChange={(e) => patchPolicySource(index, { weight: Number(e.target.value) })}
                />
                <button
                  type="button"
                  title="删除来源"
                  onClick={() => removePolicySource(index)}
                  className="flex h-9 w-9 items-center justify-center rounded-lg text-danger transition hover:bg-red-50"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            ))
          )}
        </div>

        <SectionActions
          loading={policyLoading}
          saving={policySaving}
          onReload={() => void reloadPolicySources()}
          onReset={resetPolicySources}
          onSave={() => void savePolicySources()}
        />
      </Card>
    </div>
  )
}

function SectionActions({
  loading,
  saving,
  onReload,
  onReset,
  onSave
}: {
  loading: boolean
  saving: boolean
  onReload: () => void
  onReset: () => void
  onSave: () => void
}) {
  return (
    <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line-soft pt-4">
      <Button
        icon={<RefreshCw className="h-4 w-4" />}
        loading={loading}
        disabled={saving}
        onClick={onReload}
      >
        重新读取
      </Button>
      <Button variant="ghost" disabled={loading || saving} onClick={onReset}>
        重置为默认
      </Button>
      <Button
        variant="primary"
        icon={<Check className="h-4 w-4" />}
        loading={saving}
        disabled={loading}
        onClick={onSave}
      >
        保存配置
      </Button>
    </div>
  )
}

function Field({ label, children, className = '' }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={className}>
      <label className="field-label">{label}</label>
      {children}
    </div>
  )
}
