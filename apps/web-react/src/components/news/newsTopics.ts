import type { FinancialNewsItem } from '../../types/news'

export type NewsTopicId = 'macro' | 'capital' | 'finance' | 'commodities' | 'industry' | 'global' | 'other'

export const NEWS_TOPICS: readonly { id: NewsTopicId; label: string; description: string }[] = [
  { id: 'macro', label: '宏观政策', description: '财政·货币·经济数据' },
  { id: 'capital', label: '资本市场', description: 'A股·指数·基金发行' },
  { id: 'finance', label: '银行保险', description: '银行·保险·金融服务' },
  { id: 'commodities', label: '外汇大宗', description: '汇率·能源·商品期货' },
  { id: 'industry', label: '产业公司', description: '业绩·产业·公司动态' },
  { id: 'global', label: '海外市场', description: '海外股市·跨境市场' },
  { id: 'other', label: '综合动态', description: '其他财经与综合消息' }
]

export function classifyNewsTopic(item: Pick<FinancialNewsItem, 'title' | 'summary'>): NewsTopicId {
  return classifyText(item.title) ?? classifyText(item.summary ?? '') ?? 'other'
}

function classifyText(text: string): NewsTopicId | undefined {
  // 公司经营和个股资金记录优先，避免被公司名称中的“银行”等词覆盖。
  if (/融资净买入|融资净偿还|融资余额|融券余额|调研快报|业绩|财报|季报|净利润|净利|营业收入|营收/.test(text)) return 'industry'
  if (/央行|人民银行|美联储|联储|货币政策|逆回购|再贷款|降准|公开市场操作|财政|税收|税率|国债发行|专项债|社会融资|社融|通胀|失业率|非农就业|GDP|CPI|PPI|PMI|\bMLF\b|\bLPR\b/i.test(text)) return 'macro'
  if (/回购|增持|减持|股东户数|股权激励|持股比例/.test(text)) return 'industry'
  if (/ETF|IPO|公募基金|私募基金|基金发行/i.test(text)) return 'capital'

  // 海外股指与股指期货先于通用“指数”“期货”，海外央行则由上面的货币规则处理。
  if (/美股|港股|欧股|日股|韩股|台股|台交所|恒生|恒指|纳斯达克|纳指|道琼斯|道指|标普|日经|MSCI|KOSPI|KOSDAQ|富时100|德国DAX|斯托克|(?:海外|全球|美国|欧洲|英国|日本|韩国|南非|加拿大).*(?:股市|股指|交易所|综合指数)/i.test(text)) return 'global'
  if (/银行|保险|保费|险资|信贷|贷款|存款|理财|金融监管|金融机构/.test(text)) return 'finance'
  if (/证监会|交易所|证券|券商|公募|私募|基金|A股|沪指|深成指|创业板|科创板|沪深\d*|上证|深证|股指期货|A50|并购重组|融资融券/i.test(text)) return 'capital'
  if (/外汇|汇率|中间价|离岸人民币|在岸人民币|兑(?:美元|人民币|欧元|日元|港元)|美元指数|黄金|白银|贵金属|原油|油价|期货|大宗|铜价|焦煤|焦炭|铁矿|螺纹钢|天然气|有色金属/.test(text)) return 'commodities'
  if (/指数/.test(text)) return 'capital'
  if (/产业|公司|企业|股份|集团|工信部|产能|订单|交付|制造|半导体|芯片|算力|智能体|人工智能|机器人|新能源|储能|汽车|医药|创新药|消费电子|数据中心|\bAI\b/i.test(text)) return 'industry'
  if (/加息|降息|经济数据|进出口|固定资产投资|消费复苏|经济增长/.test(text)) return 'macro'
  return undefined
}
