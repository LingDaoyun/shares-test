export type PageKey = 'limitUp' | 'market' | 'shortTerm' | 'potentialLeader' | 'overseas' | 'policy' | 'watchlist' | 'tradeReview' | 'rules' | 'settings'

export interface PageMeta {
  eyebrow: string
  title: string
  description: string
}

export const pageMeta: Record<PageKey, PageMeta> = {
  limitUp: {
    eyebrow: 'LIMIT UP',
    title: '涨停看板',
    description: '按行业查看涨停分布、连板高度和封板时间，跟踪当日市场情绪。'
  },
  market: {
    eyebrow: 'LONG VALUE',
    title: '长期价投',
    description: '覆盖沪深北 A 股，按长期价值投资逻辑筛选低估、稳健且证据完整的候选。'
  },
  shortTerm: {
    eyebrow: 'SHORT TERM',
    title: '短线右侧',
    description: '用 K 线右侧早期、量能和财报质量共同筛选价值回归候选。'
  },
  potentialLeader: {
    eyebrow: 'FIVE TRADING DAYS',
    title: '五日观察',
    description: '从横盘、换手与资金证据选出观察股，按五个交易日跟踪日线情景与真实成交。'
  },
  overseas: {
    eyebrow: 'GLOBAL MARKETS',
    title: '外围市场',
    description: '看美股、日本、韩国的指数与行业涨跌，跟踪隔夜外围情绪对 A 股的传导。'
  },
  policy: {
    eyebrow: 'POLICY INDUSTRY',
    title: '政策解读',
    description: '每日追踪政府政策与产业新闻，结合 Agent 研判观察未来几周到三个月的产业方向。'
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
  { key: 'limitUp', label: '涨停看板' },
  { key: 'shortTerm', label: '短线右侧' },
  { key: 'potentialLeader', label: '五日观察' },
  { key: 'market', label: '长期价投' },
  { key: 'overseas', label: '外围市场' },
  { key: 'policy', label: '政策解读' },
  { key: 'watchlist', label: '特别关注' },
  { key: 'tradeReview', label: '交易复盘' },
  { key: 'rules', label: '规则目录' },
  { key: 'settings', label: '配置' }
]

export const pathToPage: Record<string, PageKey> = {
  '': 'limitUp',
  'limit-up': 'limitUp',
  market: 'market',
  'short-term': 'shortTerm',
  'potential-leader': 'potentialLeader',
  'five-day': 'potentialLeader',
  'first-board-alert': 'potentialLeader',
  backtest: 'shortTerm',
  overseas: 'overseas',
  policy: 'policy',
  watchlist: 'watchlist',
  'trade-review': 'tradeReview',
  rules: 'rules',
  settings: 'settings'
}
