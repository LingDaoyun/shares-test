import { useState } from 'react'
import { Plus, RefreshCw, Check, Trash2 } from 'lucide-react'
import { useAppStore } from '../store/appStore'
import { Card } from '../components/ui/Card'
import { Button } from '../components/ui/Button'
import { Tag } from '../components/ui/Badge'
import { toast } from '../components/ui/Toast'
import { fetchPolicySources, updatePolicySources } from '../api/client'
import { extractErrorMessage } from '../lib/format'
import { defaultPolicySources } from '../lib/runtimeConfigDefaults'
import type { PolicySourceConfig } from '../types'

export function SettingsPage() {
  const form = useAppStore((s) => s.runtimeConfigForm)
  const setRuntimeConfigForm = useAppStore((s) => s.setRuntimeConfigForm)
  const [policyLoading, setPolicyLoading] = useState(false)
  const [policySaving, setPolicySaving] = useState(false)

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

  const replacePolicySources = (policySources: PolicySourceConfig[]) => {
    useAppStore.setState((state) => ({
      runtimeConfigForm: {
        ...state.runtimeConfigForm,
        policySources: policySources.map((source) => ({ ...source }))
      }
    }))
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
