export type PageKey = 'limitUp' | 'batchSearch' | 'shortTerm' | 'overseas' | 'policy' | 'watchlist' | 'settings'

export interface PageMeta {
  eyebrow: string
  title: string
  description: string
}

export const pageMeta: Record<PageKey, PageMeta> = {
  batchSearch: {
    eyebrow: 'STOCK SEARCH',
    title: '批量搜股',
    description: '按名称或代码搜索 A 股，在同一页对照行情与日 K。'
  },
  limitUp: {
    eyebrow: 'LIMIT UP',
    title: '涨停看板',
    description: '按行业查看涨停分布、连板高度和封板时间，跟踪当日市场情绪。'
  },
  shortTerm: {
    eyebrow: 'SHORT TERM',
    title: '短线右侧',
    description: '用 K 线右侧早期、量能和财报质量共同筛选价值回归候选。'
  },
  overseas: {
    eyebrow: 'GLOBAL MARKETS',
    title: '外围市场',
    description: '看美股、日本、韩国的指数与行业涨跌，跟踪隔夜外围情绪对 A 股的传导。'
  },
  policy: {
    eyebrow: 'FINANCIAL NEWS',
    title: '消息面',
    description: '持续查看财经新闻与官方金融发布，按需解读市场和行业影响。'
  },
  watchlist: {
    eyebrow: 'SPECIAL ATTENTION',
    title: '特别关注',
    description: '独立管理主动关注的股票，并按需运行完整证据分析。'
  },
  settings: {
    eyebrow: 'RUNTIME CONFIG',
    title: '系统配置',
    description: '模型和政策源配置保留在独立页面，避免和投研主界面混在一起。'
  }
}

export const navItems: { key: PageKey; label: string }[] = [
  { key: 'limitUp', label: '涨停看板' },
  { key: 'batchSearch', label: '批量搜股' },
  { key: 'shortTerm', label: '短线右侧' },
  { key: 'policy', label: '消息面' },
  { key: 'overseas', label: '外围市场' },
  { key: 'settings', label: '配置' }
]

export const pathToPage: Record<string, PageKey> = {
  '': 'limitUp',
  'limit-up': 'limitUp',
  'batch-search': 'batchSearch',
  'short-term': 'shortTerm',
  backtest: 'shortTerm',
  overseas: 'overseas',
  policy: 'policy',
  watchlist: 'watchlist',
  settings: 'settings'
}
