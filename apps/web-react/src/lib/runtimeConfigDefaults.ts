import type { LlmRuntimeConfig, PolicySourceConfig } from '../types'

const LLM_DEFAULTS: LlmRuntimeConfig = {
  provider: 'deepseek',
  apiKey: '',
  apiKeyEnv: 'DEEPSEEK_API_KEY',
  model: 'deepseek-v4-pro',
  baseUrl: 'https://api.deepseek.com',
  responseFormat: 'json_object',
  strictJsonSchema: false,
  thinking: null,
  maxCompletionTokens: 8192,
  temperature: null,
  apiKeyConfigured: false,
  apiKeySource: 'missing'
}

const POLICY_SOURCE_DEFAULTS: PolicySourceConfig[] = [
  { name: '中国政府网', type: 'json', url: 'https://www.gov.cn/zhengce/zuixin/ZUIXINZHENGCE.json', weight: 100 },
  { name: '国家发展改革委', type: 'html', url: 'https://www.ndrc.gov.cn/xxgk/zcfb/ghwb/', weight: 92 },
  { name: '工业和信息化部', type: 'html', url: 'https://www.miit.gov.cn/zwgk/zcwj/', weight: 90 },
  { name: '科学技术部', type: 'html', url: 'https://www.most.gov.cn/xxgk/xinxifenlei/fdzdgknr/fgzc/gfxwj/', weight: 86 },
  { name: '财政部', type: 'html', url: 'https://www.mof.gov.cn/zhengwuxinxi/caizhengxinwen/', weight: 82 },
  { name: '国家能源局', type: 'html', url: 'https://www.nea.gov.cn/zcfb/', weight: 84 },
  { name: '中国证监会', type: 'html', url: 'https://www.csrc.gov.cn/csrc/c100028/zfxxgk_zdgk.shtml', weight: 80 },
  { name: '生态环境部', type: 'html', url: 'https://www.mee.gov.cn/xxgk/', weight: 84 },
  { name: '农业农村部', type: 'html', url: 'https://www.moa.gov.cn/govpublic/', weight: 82 },
  { name: '交通运输部', type: 'html', url: 'https://xxgk.mot.gov.cn/zhengceapp/740/833/list_7234.html', weight: 82 }
]

export function defaultLlmRuntimeConfig(): LlmRuntimeConfig {
  return { ...LLM_DEFAULTS }
}

export function defaultPolicySources(): PolicySourceConfig[] {
  return POLICY_SOURCE_DEFAULTS.map((source) => ({ ...source }))
}
