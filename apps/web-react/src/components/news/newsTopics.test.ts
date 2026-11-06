import { describe, expect, it } from 'vitest'
import { classifyNewsTopic, NEWS_TOPICS, type NewsTopicId } from './newsTopics'

describe('financial news primary topics', () => {
  it('provides the seven stable, concise topics in navigation order', () => {
    expect(NEWS_TOPICS.map(topic => [topic.id, topic.label])).toEqual([
      ['macro', '宏观政策'], ['capital', '资本市场'], ['finance', '银行保险'],
      ['commodities', '外汇大宗'], ['industry', '产业公司'], ['global', '海外市场'], ['other', '综合动态']
    ])
    expect(NEWS_TOPICS.every(topic => topic.description.length <= 12 && topic.description.length > 0)).toBe(true)
  })

  it.each<[string, NewsTopicId]>([
    ['固德电材：融资净偿还99.83万元，融资余额1.17亿元', 'industry'],
    ['富乐德：融资净买入998.19万元，融资余额4.55亿元', 'industry'],
    ['贵州茅台第三季度净利润同比增长', 'industry'],
    ['上海银行公布前三季度业绩', 'industry'],
    ['微软宣布回购计划', 'industry'],
    ['联讯仪器董事长提议最高1.5亿元回购', 'industry'],
    ['【调研快报】招商轮船接待71家机构调研', 'industry'],
    ['工信部：加强智能体终端设备进网管理', 'industry'],
    ['全球首个仿生人形机器人新闻主播正式发布', 'industry'],
    ['沪指盘中上涨，创业板指走强', 'capital'],
    ['指数估值与市场结构观察', 'capital'],
    ['美股ETF产品申请发行', 'capital'],
    ['超聚变IPO将上会', 'capital'],
    ['沪深300股指期货低开', 'capital'],
    ['富时中国A50指数期货低开', 'capital'],
    ['中国证监会发布并购重组政策', 'capital'],
    ['英国央行宣布加息25个基点', 'macro'],
    ['美联储维持利率不变', 'macro'],
    ['中国人民银行开展1000亿元逆回购', 'macro'],
    ['股票回购增持再贷款筑牢市场稳定基石', 'macro'],
    ['财政部发布税收优惠政策', 'macro'],
    ['美国10月CPI同比上涨', 'macro'],
    ['银行存款利率下调', 'finance'],
    ['最新预定利率研究值1.9%，保险产品不降息', 'finance'],
    ['金融监管总局部署保险服务工作', 'finance'],
    ['苹果期货主力合约日内大涨4%', 'commodities'],
    ['布伦特原油期货涨幅扩大至1%', 'commodities'],
    ['台币兑美元跌破33元大关', 'commodities'],
    ['人民币汇率保持稳定', 'commodities'],
    ['MSCI亚太指数下跌4.8%', 'global'],
    ['欧洲股指期货下跌', 'global'],
    ['韩国交易所启动SIDECAR机制', 'global'],
    ['恒生指数盘中上涨', 'global'],
    ['纳斯达克指数下跌', 'global'],
    ['近期重要活动安排', 'other'],
    ['600000', 'other']
  ])('classifies %s as %s', (title, expected) => {
    expect(classifyNewsTopic({ title, summary: null })).toBe(expected)
  })

  it('uses the title topic before a different theme in the summary', () => {
    expect(classifyNewsTopic({ title: '苹果期货主力上涨', summary: '摘要同时提到上市公司业绩和英国央行加息。' })).toBe('commodities')
    expect(classifyNewsTopic({ title: '沪深300指数上涨', summary: '海外市场和银行保险均有变化。' })).toBe('capital')
  })

  it('consults the summary only when the title has no recognizable topic', () => {
    expect(classifyNewsTopic({ title: '最新消息', summary: '中国人民银行开展公开市场逆回购操作。' })).toBe('macro')
    expect(classifyNewsTopic({ title: '今日观察', summary: '人形机器人进入产线验证阶段。' })).toBe('industry')
    expect(classifyNewsTopic({ title: '最新消息', summary: '' })).toBe('other')
  })
})
