import { describe, expect, it } from 'vitest'
import { defaultLlmRuntimeConfig, defaultPolicySources } from './runtimeConfigDefaults'

describe('runtime config defaults', () => {
  it('uses the approved DeepSeek baseline', () => {
    expect(defaultLlmRuntimeConfig()).toMatchObject({
      provider: 'deepseek',
      apiKeyEnv: 'DEEPSEEK_API_KEY',
      model: 'deepseek-v4-pro',
      baseUrl: 'https://api.deepseek.com',
      responseFormat: 'json_object',
      maxCompletionTokens: 8192
    })
  })

  it('returns independent copies of all platform policy sources', () => {
    const first = defaultPolicySources()
    first[0].name = 'changed'

    expect(defaultPolicySources()).toHaveLength(10)
    expect(defaultPolicySources()[0].name).toBe('中国政府网')
    const defaults = defaultPolicySources()
    expect(defaults[defaults.length - 1].name).toBe('交通运输部')
  })
})
