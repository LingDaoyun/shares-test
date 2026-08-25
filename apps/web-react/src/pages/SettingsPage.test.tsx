// @vitest-environment jsdom

import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchPolicySources, updatePolicySources } from '../api/client'
import { defaultLlmRuntimeConfig, defaultPolicySources } from '../lib/runtimeConfigDefaults'
import { emptyRuntimeConfig, useAppStore } from '../store/appStore'
import { toast } from '../components/ui/Toast'
import { SettingsPage } from './SettingsPage'

vi.mock('../api/client', () => ({
  fetchRules: vi.fn(),
  fetchRuntimeConfig: vi.fn(),
  updateRuntimeConfig: vi.fn(),
  fetchPolicySources: vi.fn(),
  updatePolicySources: vi.fn()
}))

vi.mock('../components/ui/Toast', () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }
}))

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
        llm: defaultLlmRuntimeConfig(),
        policySources: [{ name: '当前政策源', type: 'html', url: 'https://current.example', weight: 80 }]
      },
      runtimeConfigLoading: false
    })
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

  it('no longer renders the unused LLM provider section', () => {
    expect(host.textContent).not.toContain('大模型配置')
    expect(host.textContent).not.toContain('API Key')
    expect(host.textContent).toContain('政策源配置')
  })

  it('saves policy sources with the section actions', async () => {
    expect(actionButtons('政策源配置')).toHaveLength(3)

    await click(buttonIn('政策源配置', '保存配置'))
    expect(mockedUpdatePolicies).toHaveBeenCalledTimes(1)
    expect(toast.success).toHaveBeenCalledWith('政策源配置已保存并生效')
  })

  it('reloads policy sources from the backend', async () => {
    await click(buttonIn('政策源配置', '重新读取'))

    expect(mockedFetchPolicies).toHaveBeenCalledTimes(1)
    expect(host.querySelectorAll('input[placeholder="来源名称"]')).toHaveLength(10)
  })

  it('resets policy sources to defaults without publishing', async () => {
    const name = policyNameInput()
    await change(name, '未保存修改')

    await click(buttonIn('政策源配置', '重置为默认'))

    expect(host.querySelectorAll('input[placeholder="来源名称"]')).toHaveLength(10)
    expect(mockedUpdatePolicies).not.toHaveBeenCalled()
  })

  it('shows database metadata and no Nacos identifiers', () => {
    expect(section('政策源配置').textContent).toContain('政策源修订 0')
    expect(host.textContent).not.toContain('Nacos')
    expect(host.textContent).not.toContain('AI_STOCK')
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
      .filter((button) => ['重新读取', '重置为默认', '保存配置'].includes(button.textContent?.trim() ?? ''))
  }

  function buttonIn(title: string, label: string) {
    const button = [...section(title).querySelectorAll<HTMLButtonElement>('button')]
      .find((element) => element.textContent?.trim() === label)
    if (!button) throw new Error(`Button not found: ${title} / ${label}`)
    return button
  }

  function policyNameInput() {
    const input = section('政策源配置').querySelector<HTMLInputElement>('input[placeholder="来源名称"]')
    if (!input) throw new Error('Policy name input not found')
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
