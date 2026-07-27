// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fetchLlmRuntimeConfig,
  fetchPolicySources,
  updateLlmRuntimeConfig,
  updatePolicySources
} from '../api/client'
import { defaultLlmRuntimeConfig, defaultPolicySources } from '../lib/runtimeConfigDefaults'
import { emptyRuntimeConfig, useAppStore } from '../store/appStore'
import { SettingsPage } from './SettingsPage'

vi.mock('../api/client', () => ({
  fetchLlmConfig: vi.fn(),
  fetchRules: vi.fn(),
  fetchRuntimeConfig: vi.fn(),
  updateRuntimeConfig: vi.fn(),
  fetchLlmRuntimeConfig: vi.fn(),
  updateLlmRuntimeConfig: vi.fn(),
  fetchPolicySources: vi.fn(),
  updatePolicySources: vi.fn()
}))

vi.mock('../components/ui/Toast', () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
}))

const mockedFetchLlm = vi.mocked(fetchLlmRuntimeConfig)
const mockedUpdateLlm = vi.mocked(updateLlmRuntimeConfig)
const mockedFetchPolicies = vi.mocked(fetchPolicySources)
const mockedUpdatePolicies = vi.mocked(updatePolicySources)

describe('SettingsPage section actions', () => {
  let host: HTMLDivElement
  let root: Root

  beforeEach(() => {
    ;(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    vi.clearAllMocks()
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
    useAppStore.setState({
      runtimeConfig: null,
      runtimeConfigForm: {
        ...emptyRuntimeConfig(),
        llm: { ...defaultLlmRuntimeConfig(), apiKeyConfigured: true, apiKeySource: 'research.ai.llm.api-key' },
        policySources: [{ name: '当前政策源', type: 'html', url: 'https://current.example', weight: 80 }]
      },
      runtimeConfigLoading: false
    })
    mockedFetchLlm.mockResolvedValue(defaultLlmRuntimeConfig())
    mockedUpdateLlm.mockResolvedValue(defaultLlmRuntimeConfig())
    mockedFetchPolicies.mockResolvedValue(defaultPolicySources())
    mockedUpdatePolicies.mockImplementation(async (sources) => sources)
    act(() => root.render(<SettingsPage />))
  })

  afterEach(() => {
    act(() => root.unmount())
    host.remove()
    delete (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT
    vi.restoreAllMocks()
  })

  it('renders independent action rows and saves only the selected section', async () => {
    expect(actionButtons('大模型配置')).toHaveLength(3)
    expect(actionButtons('政策源配置')).toHaveLength(3)

    await click(buttonIn('大模型配置', '保存到 Nacos'))
    expect(mockedUpdateLlm).toHaveBeenCalledTimes(1)
    expect(mockedUpdatePolicies).not.toHaveBeenCalled()

    await click(buttonIn('政策源配置', '保存到 Nacos'))
    expect(mockedUpdatePolicies).toHaveBeenCalledTimes(1)
  })

  it('reloads one section without replacing unsaved edits in the other', async () => {
    const model = fieldIn('大模型配置', '模型')
    await change(model, 'unsaved-model')

    await click(buttonIn('政策源配置', '重新读取'))

    expect(model.value).toBe('unsaved-model')
    expect(mockedFetchPolicies).toHaveBeenCalledTimes(1)
    expect(mockedFetchLlm).not.toHaveBeenCalled()
  })

  it('resets only the selected section without publishing', async () => {
    const model = fieldIn('大模型配置', '模型')
    await change(model, 'unsaved-model')
    await click(buttonIn('政策源配置', '重置为默认'))

    expect(section('政策源配置').querySelectorAll('input[placeholder="来源名称"]')).toHaveLength(10)
    expect(fieldIn('大模型配置', '模型').value).toBe('unsaved-model')
    expect(mockedUpdatePolicies).not.toHaveBeenCalled()
    expect(mockedUpdateLlm).not.toHaveBeenCalled()
  })

  it('keeps policy actions enabled while the LLM section is saving', async () => {
    mockedUpdateLlm.mockReturnValue(new Promise(() => undefined))

    act(() => buttonIn('大模型配置', '保存到 Nacos').click())
    await act(async () => Promise.resolve())

    expect(buttonIn('大模型配置', '保存到 Nacos').disabled).toBe(true)
    expect(buttonIn('政策源配置', '保存到 Nacos').disabled).toBe(false)
    expect(buttonIn('政策源配置', '重新读取').disabled).toBe(false)
  })

  function section(title: string) {
    const heading = [...host.querySelectorAll<HTMLElement>('h3, .section-title')]
      .find((element) => element.textContent?.trim() === title)
    const card = heading?.closest('section')
    if (!card) throw new Error(`Section not found: ${title}`)
    return card
  }

  function actionButtons(title: string) {
    return [...section(title).querySelectorAll<HTMLButtonElement>('button')]
      .filter((button) => ['重新读取', '重置为默认', '保存到 Nacos'].includes(button.textContent?.trim() ?? ''))
  }

  function buttonIn(title: string, label: string) {
    const button = [...section(title).querySelectorAll<HTMLButtonElement>('button')]
      .find((element) => element.textContent?.trim() === label)
    if (!button) throw new Error(`Button not found: ${title} / ${label}`)
    return button
  }

  function fieldIn(title: string, label: string) {
    const fieldLabel = [...section(title).querySelectorAll<HTMLLabelElement>('label')]
      .find((element) => element.textContent?.trim() === label)
    const input = fieldLabel?.parentElement?.querySelector<HTMLInputElement>('input')
    if (!input) throw new Error(`Field not found: ${title} / ${label}`)
    return input
  }
})

async function click(button: HTMLButtonElement) {
  await act(async () => {
    button.click()
    await Promise.resolve()
  })
}

async function change(input: HTMLInputElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    setter?.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await Promise.resolve()
  })
}
