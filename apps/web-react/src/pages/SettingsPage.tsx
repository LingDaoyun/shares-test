import { Plus, RefreshCw, Check, Trash2 } from 'lucide-react'
import { useAppStore, emptyRuntimeConfig } from '../store/appStore'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Tag } from '../components/ui/Badge'
import { toast } from '../components/ui/Toast'
import { updateRuntimeConfig } from '../api/client'
import { extractErrorMessage } from '../lib/format'
import type { PolicySourceConfig, RuntimeConfigSnapshot } from '../types'

const PROVIDERS = [
  { label: 'DeepSeek', value: 'deepseek' },
  { label: 'OpenAI', value: 'openai' },
  { label: 'Moonshot / Kimi 开放平台', value: 'moonshot' },
  { label: 'Kimi Code', value: 'kimi-code' }
]
const FORMATS = ['json_object', 'json_schema', 'none']

export function SettingsPage() {
  const form = useAppStore((s) => s.runtimeConfigForm)
  const setRuntimeConfigForm = useAppStore((s) => s.setRuntimeConfigForm)
  const runtimeConfigLoading = useAppStore((s) => s.runtimeConfigLoading)
  const loadRuntimeConfig = useAppStore((s) => s.loadRuntimeConfig)
  const refreshLlmConfig = useAppStore((s) => s.refreshLlmConfig)
  const [saving, setSaving] = useLocal(false)

  const patchLlm = (patch: Partial<typeof form.llm>) => {
    setRuntimeConfigForm({ ...form, llm: { ...form.llm, ...patch } })
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

  const onSave = async () => {
    setSaving(true)
    try {
      const payload: RuntimeConfigSnapshot = {
        ...form,
        llm: { ...form.llm, apiKey: '' },
        policySources: form.policySources.map((s) => ({ ...s }))
      }
      const nextKey = form.llm.apiKey?.trim()
      payload.llm.apiKey = nextKey ? nextKey : null
      const updated = await updateRuntimeConfig(payload)
      useAppStore.setState({
        runtimeConfig: updated,
        runtimeConfigForm: {
          ...payload,
          llm: {
            ...payload.llm,
            apiKey: '',
            apiKeyConfigured: Boolean(nextKey) || form.llm.apiKeyConfigured,
            apiKeySource: Boolean(nextKey) ? 'research.ai.llm.api-key' : form.llm.apiKeySource
          },
          updatedAt: new Date().toISOString()
        }
      })
      toast.success('配置已发布到 Nacos')
      await refreshLlmConfig()
      setTimeout(() => {
        void loadRuntimeConfig()
        void refreshLlmConfig()
      }, 1500)
    } catch (e) {
      toast.error(extractErrorMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const onReset = () => setRuntimeConfigForm(emptyRuntimeConfig())

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
            <Tag>{form.dataId} / {form.group}</Tag>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="Provider">
            <select className="field" value={form.llm.provider} onChange={(e) => patchLlm({ provider: e.target.value })}>
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
              placeholder="留空则保留 Nacos 中已有 Key"
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
      </Card>

      {/* 政策源配置 */}
      <Card
        title="政策源配置"
        extra={
          <Button icon={<Plus className="h-4 w-4" />} onClick={addPolicySource}>
            新增来源
          </Button>
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

        <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line-soft pt-4">
          <Button icon={<RefreshCw className="h-4 w-4" />} loading={runtimeConfigLoading} onClick={() => void loadRuntimeConfig()}>
            重新读取
          </Button>
          <Button variant="ghost" onClick={onReset}>
            重置为默认
          </Button>
          <Button variant="primary" icon={<Check className="h-4 w-4" />} loading={saving} onClick={onSave}>
            保存到 Nacos
          </Button>
        </div>
      </Card>
    </div>
  )
}

import { useState as useReactState } from 'react'
function useLocal(initial: boolean): [boolean, (v: boolean) => void] {
  const [v, setV] = useReactState(initial)
  return [v, setV]
}

function Field({ label, children, className = '' }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={className}>
      <label className="field-label">{label}</label>
      {children}
    </div>
  )
}
