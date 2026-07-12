# 架构设计

## 目标

这个平台的第一目标不是自动交易，而是把长线投研流程产品化、证据化、可复盘：

```text
全 A 证券主数据与实时行情
-> 流动性/ST/亏损/横盘等资格过滤
-> 政策、公告、财报、K线与行业热度证据
-> 长线价值或短线右侧多因子模型
-> 风控门禁与多 Agent 反证
-> 推荐结果或特别关注主动分析
-> K线、分析和决策历史复盘
```

## MVP 服务边界

当前用模块化单体承载核心业务，避免一开始拆成过多微服务。

```text
apps/api
  policy     政策主题与产业链
  company    公司画像与证据链
  rule       规则引擎、规则配置、试算
  portfolio  特别关注与长线主动分析
  universe   全 A 动态候选漏斗
  shortterm  右侧结构、热度、量能和尾盘验证
  history    K 线版本、分析快照与决策历史
```

后续按压力和团队边界拆分：

```text
market-data-service
document-service
policy-service
company-service
factor-service
rule-engine-service
backtest-service
ai-workflow-service
ai-agent-service
```

微服务拆分后统一使用 Nacos Discovery 做服务注册发现，Nacos Config 做动态配置中心。当前 MVP 是模块化单体，但默认 profile 已切到 `nacos`，后端会注册为 `ai-stock-api`。

## 点时与历史原则

- 推荐股票不使用静态白名单，特别关注也不参与全市场排名。
- 交易中行情必须通过交易日期和市场时间戳校验；过期行情只能复核，不能形成短线执行建议。
- 休市日只展示最近收盘快照，不标记为实时行情。
- K 线按内容指纹追加保存，源数据修订不会覆盖旧观察版本。
- 主动长线分析和自动短线候选都保存完整 payload、规则版本、数据截至时间与记录时间。
- 历史决策只作为一致性检查和样本依据，不能用旧结论覆盖当前证据。

## 推荐交易复盘闭环

推荐页面的“加入复盘”只捕获已经生成的推荐事实，成交和结果在独立 Hash 路由 `#/trade-review` 操作；它不参与全 A 候选生成、推荐排序或特别关注。案例以推荐指纹幂等创建，冻结股票、来源模块、动作、规则版本、推荐价格、推荐时间和原始 payload，避免后续页面刷新改写当时证据。

案例可附加任意多笔按时间排序的 `BUY`/`SELL` 成交事实。每笔必须有方向、成交时间、正价格和正整数数量。账本在买入时按持仓数量移动加权平均成本，在卖出时按当前平均成本确认已实现毛收益；任何会使持仓为负的卖出都以 4xx 拒绝。持仓和累计收益只计算成交价格与数量，不计佣金、印花税、分红、送转股，因此不是净 P&L。

结果快照与事实分离，可重复刷新而不复制同一“基线类型 + 期限”的记录：

```text
推荐基线：CURRENT / T1 / T5 / T20
执行基线：CURRENT（仍持仓）/ CLOSED（已平仓）
```

`T1/T5/T20` 从上海时区推荐日之后的实际交易日 K 线行计数。结果保留数据源、行情时间和计算时间；未达到所需交易行时为 `PENDING`，没有可用数据时为 `UNAVAILABLE`，不以自然日或猜测价格补齐。手动接口可刷新单一案例，调度器在工作日 `Asia/Shanghai 18:10` 刷新仍需评估的非取消案例。

复盘历史的反馈队列按 `sourceModule + ruleVersion` 隔离，只使用已成熟的推荐 `T20` 结果。5 个样本后可作为受限的 Agent 提示词上下文，20 个样本后才产生 `-5..+5` 的可靠性调整。历史反馈不能自动发布 Nacos 配置、修改确定性共识分，或绕过既有硬风控门禁。

本地 Docker 使用挂载在命名卷上的 H2 文件库，复盘案例、成交和结果随 API 容器重启保留。生产/联调可切换 PostgreSQL；共享 schema 同时覆盖两种数据库，避免复盘表出现环境分叉。

## AI 框架选择

主链路优先使用确定性 Workflow：

```text
采集 -> 解析 -> 抽取 -> 入库 -> 因子计算 -> 规则评分
```

Spring AI Alibaba 适合 Workflow-first 场景；AgentScope Java 适合 Agent-first 场景。因此：

- MVP：Spring Boot 服务内封装模型调用与结构化输出。
- 二期：新增 `ai-agent-service`，用 AgentScope Java 做开放式深度研究。
- 任何 Agent 输出都只能进入证据层，不能绕过规则引擎直接产生买卖决策。

## AI 趋势分析节点

政府规划文件和产业报告分析是平台的关键 AI 节点。这个节点不做简单摘要，而是输出可审计的长期趋势假设：

```text
原文证据
-> 显性政策/产业信号
-> 多 Agent 交叉验证
-> 隐含趋势推导
-> 产业链环节拆解
-> A 股公司筛选画像
-> 监控指标
-> 反证条件
```

当前已提供 Prompt 预览接口：

```text
GET  /api/ai/trend-prompts/sample
POST /api/ai/trend-prompts/preview
POST /api/ai/trend-analysis
GET  /api/ai/llm-config
```

真实分析接口使用 Nacos 中配置的大模型 Provider。模型输出只进入证据层和趋势层，并包含政策原文核验、产业链机制、数据验证、风险反证、A 股映射和裁判汇总等交叉验证结论，再由规则引擎决定是否进入观察池。

## 当前真实数据源

MVP 已接入以下公开数据源：

```text
中国政府网、发改委、工信部、科技部、财政部、能源局、证监会等多官方政策源
腾讯 A 股实时行情
东方财富 A 股实时行情备用源
东方财富数据中心年度财务指标
```

这些数据用于候选池、政策信号、估值初筛、年度质量规则和观察池生成。公告 PDF 已支持少量在线正文解析；完整问询函、处罚、质押、主营构成等证据仍适合继续扩展为批处理文档库。

## 规则生命周期

```text
编辑规则
-> JSON Schema/业务校验
-> 当前股票池试跑
-> 历史回测预览
-> 生成新版本
-> 发布
-> Redis/Nacos 通知热加载
-> 评分结果记录 rule_version_id
```

## 风控原则

- 硬规则负责排除：退市风险、监管处罚、现金流异常、质押异常等。
- 软规则负责评分：ROE、现金流质量、研发强度、估值分位等。
- 组合规则负责仓位：单股、单行业、单主题、最大回撤、估值过热降权。

## 研究评分视图

公司详情页和观察池共用同一套 `CompanyResearchView`，避免 AI 摘要、规则评分和观察池阶段相互割裂：

```text
CompanyProfile
-> FilingEvidenceProvider：巨潮公告 / 公司画像降级证据
-> CompanyResearchService
-> 五维评分：TREND / QUALITY / MOAT / VALUATION / RISK
-> 证据分层：政策主题线索 / 行情估值证据 / 公告年报证据 / 订单招投标和财务兑现
-> 公告证据：公告样本 / PDF 正文解析数 / 壁垒线索 / 风险线索 / 兑现线索
-> 正文事件：RISK / MOAT / VALIDATION
-> AgentCommitteeService：政策策略 / 财务质量 / 公告壁垒 / 估值纪律 / 反方风控
-> 阶段标签：WATCH_SAMPLE / EVIDENCE_BUILDING / WAIT_FOR_PRICE / VALUATION_WATCH / RISK_REVIEW
```

其中 `VALUATION_WATCH` 只是观察阶段，不代表买入建议。真正进入交易动作前还需要历史回测、组合风险约束和人工复核。

多 Agent 共识层当前是“确定性投票 + 可选 LLM 论证增强”：每个角色基于同一份研究视图、公告正文事件和风险清单给出投票、置信度、支持证据、反对意见和补证清单。手动触发 AI 增强时，模型只负责补充各 Agent 的论证、反证、信心说明和建议阶段；投票、否决、共识分和默认阶段仍保留在可审计规则里。后续可以把开放式深度研究迁到 AgentScope Java 的 `ai-agent-service`，但模型输出仍只能进入证据层。

Agent 选股会在共识层之上做横向 shortlist：先从全市场公司池抽取复核样本，再逐只运行五 Agent 投票，最后按共识分、支持/观察票、复核/否决票和阶段安全性排序，输出少量候选。每个候选都保留 `UNIVERSE_SCREEN`、`AGENT_DISCUSSION`、`DISAGREEMENT_REVIEW`、`FINAL_SHORTLIST` 四段 trace，前端可查看完整讨论过程。
