export type PageKey = 'market' | 'shortTerm' | 'tech' | 'mispricing' | 'cycle' | 'signals' | 'watchlist' | 'rules' | 'settings'

export interface PageMeta {
  eyebrow: string
  title: string
  description: string
}

export const pageMeta: Record<PageKey, PageMeta> = {
  market: {
    eyebrow: 'MARKET SCAN',
    title: '全市场扫描',
    description: '覆盖沪深北 A 股，核对行情完整度并按不同策略应用独立资格门槛。'
  },
  shortTerm: {
    eyebrow: 'SHORT TERM',
    title: '短线右侧',
    description: '用 K 线右侧早期、量能、估值和财报质量共同筛选价值回归候选。'
  },
  tech: {
    eyebrow: 'HOT SECTOR TRACKER',
    title: '热门追踪池',
    description: '从全 A 股动态识别热门板块，结合行业热度、业绩、估值和交易纪律建立追踪队列。'
  },
  mispricing: {
    eyebrow: 'MISPRICED ASSETS',
    title: '错杀估值池',
    description: '在热门方向过热时，提前寻找被资金虹吸压低的非热门优质资产。'
  },
  cycle: {
    eyebrow: 'CYCLE TRIAL',
    title: '周期试仓池',
    description: '把周期底部赔率、左侧试仓、右侧加仓和急拉回避拆成独立信号。'
  },
  signals: {
    eyebrow: 'DAILY SIGNALS',
    title: '每日决策信号',
    description: '融合 DSA 策略包、每日市场上下文和结构化操作建议，形成当天可复核信号。'
  },
  watchlist: {
    eyebrow: 'SPECIAL ATTENTION',
    title: '特别关注',
    description: '独立管理主动关注的股票，并按需运行完整证据分析。'
  },
  rules: {
    eyebrow: 'RULE ENGINE',
    title: '规则目录',
    description: '把规则从主页面抽出来，便于单独查看优先级、动作和版本。'
  },
  settings: {
    eyebrow: 'RUNTIME CONFIG',
    title: '系统配置',
    description: '模型和政策源配置保留在独立页面，避免和投研主界面混在一起。'
  }
}

export const navItems: { key: PageKey; label: string }[] = [
  { key: 'market', label: '全市场' },
  { key: 'shortTerm', label: '短线右侧' },
  { key: 'tech', label: '热门追踪' },
  { key: 'mispricing', label: '错杀估值' },
  { key: 'cycle', label: '周期试仓' },
  { key: 'signals', label: '每日信号' },
  { key: 'watchlist', label: '特别关注' },
  { key: 'rules', label: '规则目录' },
  { key: 'settings', label: '配置' }
]

export const pathToPage: Record<string, PageKey> = {
  '': 'shortTerm',
  market: 'market',
  'short-term': 'shortTerm',
  backtest: 'shortTerm',
  tech: 'tech',
  mispricing: 'mispricing',
  cycle: 'cycle',
  signals: 'signals',
  watchlist: 'watchlist',
  rules: 'rules',
  settings: 'settings'
}
