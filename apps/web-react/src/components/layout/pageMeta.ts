export type PageKey = 'market' | 'shortTerm' | 'policy' | 'watchlist' | 'tradeReview' | 'rules' | 'settings'

export interface PageMeta {
  eyebrow: string
  title: string
  description: string
}

export const pageMeta: Record<PageKey, PageMeta> = {
  market: {
    eyebrow: 'LONG VALUE',
    title: '长期价投',
    description: '覆盖沪深北 A 股，按长期价值投资逻辑筛选低估、稳健且证据完整的候选。'
  },
  shortTerm: {
    eyebrow: 'SHORT TERM',
    title: '短线右侧',
    description: '用 K 线右侧早期、量能、估值和财报质量共同筛选价值回归候选。'
  },
  policy: {
    eyebrow: 'POLICY INDUSTRY',
    title: '政策解读',
    description: '从国家规划、部委政策和官方新闻中提炼产业方向，再交给 AI 做多 Agent 趋势复核。'
  },
  watchlist: {
    eyebrow: 'SPECIAL ATTENTION',
    title: '特别关注',
    description: '独立管理主动关注的股票，并按需运行完整证据分析。'
  },
  tradeReview: {
    eyebrow: 'TRADE REVIEW',
    title: '交易复盘',
    description: '连接推荐现场、真实分批成交与后续策略表现。'
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
  { key: 'shortTerm', label: '短线右侧' },
  { key: 'market', label: '长期价投' },
  { key: 'policy', label: '政策解读' },
  { key: 'watchlist', label: '特别关注' },
  { key: 'tradeReview', label: '交易复盘' },
  { key: 'rules', label: '规则目录' },
  { key: 'settings', label: '配置' }
]

export const pathToPage: Record<string, PageKey> = {
  '': 'shortTerm',
  market: 'market',
  'short-term': 'shortTerm',
  backtest: 'shortTerm',
  policy: 'policy',
  watchlist: 'watchlist',
  'trade-review': 'tradeReview',
  rules: 'rules',
  settings: 'settings'
}
