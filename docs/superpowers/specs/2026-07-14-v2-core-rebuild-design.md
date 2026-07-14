# A 股投研平台 V2 内核完全重构设计

日期：2026-07-14

状态：待用户复核

## 1. 背景与决策

用户确认采用 C 方案：完全重构当前投研平台的核心能力。这里的“完全重构”定义为重构业务内核，而不是盲目丢弃已经稳定的工程资产。

本设计继承并收敛以下既有设计：

- `2026-07-08-universal-ashare-screener-design.md`：全 A 股票池与候选漏斗。
- `2026-07-10-explainable-multifactor-point-in-time-validation-design.md`：可解释多因子、点时数据与样本外验证。
- `2026-07-11-trade-feedback-loop-design.md`：推荐凭证、分批交易回填和策略反馈。
- `2026-07-10-soft-valuation-context-design.md`：行业估值上下文与软约束。

V2 的核心判断是：

- 当前系统的方向正确，但多个模块仍像“功能叠加”，缺少统一策略内核。
- 未来荐股质量不应依赖单次 AI 判断，而应依赖点时数据、因子快照、策略版本、样本外验证、Agent 证据链和真实交易反馈的闭环。
- 重构后的系统可以在工程正确性、可解释性和可追溯性上明显优于当前方案，但不能承诺收益率一定提高。

## 2. 重构范围

### 2.1 必须重构

- 全 A 数据采集与对账。
- 点时行情、财报、公告、行业、政策和资金流数据存储。
- 因子定义、单位校验、归一化和快照。
- 长线价值、周期反转、短线右侧和热门追踪策略。
- 风控门禁、证据门禁和组合门禁。
- 多 Agent 证据计划、交叉验证和弃权机制。
- 推荐台账、策略版本、样本外验证和决策回放。
- 页面信息架构，减少重复模块和口径冲突。

### 2.2 保留并接入 V2

- Java 17 + Spring Boot 3 + React + Vite 技术路线。
- Nacos 配置中心和服务发现。
- Docker 常驻运行方式。
- 现有特别关注、交易复盘、分批成交、结果刷新和策略反馈能力。
- 已验证的东方财富、腾讯行情、巨潮公告等数据源客户端，但需要统一进入点时数据层。
- 现有页面组件中成熟的卡片、证据面板和复盘入口。

### 2.3 明确不做

- 不做自动下单。
- 不把 AI 输出直接当作买卖动作。
- 不以论坛情绪、传闻或“主力意图”作为事实依据。
- 不为了“完全重构”拆成大量微服务；第一阶段仍采用模块化单体，等数据采集、文档解析或验证任务确实需要独立扩缩容后再拆服务。
- 不承诺任何策略能稳定战胜市场。

## 3. V2 总体架构

```text
MarketDataIngestion / FilingIngestion / PolicyIngestion / FinancialIngestion
                              |
                              v
                    PointInTimeDataStore
         effective_at + available_at + ingested_at + source_version
                              |
                              v
                         FactorEngine
        FactorDefinition + UnitCheck + Normalization + FactorSnapshot
                              |
          +-------------------+-------------------+
          |                   |                   |
          v                   v                   v
 LongTermStrategyEngine  ShortTermStrategyEngine  MarketRegimeEngine
  价值/成长/周期          右侧/量价/热点           指数/宽度/风险
          |                   |                   |
          +-------------------+-------------------+
                              |
                              v
              RiskGate + EvidenceGate + PortfolioGate
                              |
                              v
                     DecisionAggregator
          分策略动作 + 今日建议 + 失效条件 + 仓位上限
                              |
          +-------------------+-------------------+
          |                   |                   |
          v                   v                   v
 RecommendationLedger   WalkForwardValidator   TradeFeedbackLoop
 推荐回放与凭证          样本外验证              真实交易纠错
```

核心包建议：

```text
com.aistock.research.v2.data
com.aistock.research.v2.factor
com.aistock.research.v2.strategy
com.aistock.research.v2.risk
com.aistock.research.v2.evidence
com.aistock.research.v2.validation
com.aistock.research.v2.decision
```

旧模块先通过 Adapter 调用 V2 内核。等 V2 页面稳定后，再逐步下线旧服务中的重复算法。

## 4. 点时数据原则

每条参与评分的数据必须具备：

```text
symbol              股票代码
effective_at        数据对应的业务时间
available_at        投资者最早可获得时间
ingested_at         系统实际入库时间
source              数据源
source_version      请求指纹、文件版本或公告编号
quality_status      VERIFIED / SINGLE_SOURCE / STALE / CONFLICT / MISSING
raw_payload_hash    原始内容哈希
```

硬规则：

- 回测和历史决策只能读取 `available_at <= decision_at` 的数据。
- 财报不能按报告期末提前生效，必须按公告可见时间生效。
- 数据更正生成新版本，不能覆盖旧版本。
- 历史股票池必须包含后来退市、停牌或 ST 的样本，避免幸存者偏差。
- 任一数据源失败时可以重试和换源，但不能静默使用旧缓存冒充实时数据。

## 5. 全 A 股票池

V2 统一股票池只做资格和数据质量判断，不直接给买入建议。

通用硬排除：

- 非普通 A 股、退市整理、长期停牌、明显异常价格。
- ST 或重大退市风险，除非用户主动在特别关注中做风险研究。
- 20 日成交额中位数低于策略要求。
- 行情时间戳缺失、交易阶段不明或分页对账失败。

通用不再硬排除：

- PE/PB 偏高。
- 长期横盘。
- 周期行业阶段性亏损。
- 股价短期回撤。

这些因素交给具体策略处理。全 A 层必须记录每只股票进入或离开策略池的轨迹。

## 6. 因子引擎

每个因子必须用 `FactorDefinition` 定义：

```text
code
name
strategy_scope
value_unit
direction
required_inputs
availability_rule
missing_policy
normalization
winsorization
industry_neutralization
version
```

评分必须拆成四类，不允许混成一个黑盒总分：

```text
rank_score          当前截面排序分
data_confidence     数据完整度与来源质量
historical_hit_rate 样本外验证命中率和置信区间
risk_reward         历史收益回撤比与赔率
```

缺失数据不按 0 分处理，而是降低 `data_confidence` 并给出补证清单。AI 只能抽取证据和反证，不能凭常识补数值因子。

## 7. 长线策略族

长线策略不再使用单一“长线价值”口径，而是拆成三类。

### 7.1 VALUE_REVERSION

目标：寻找 6 到 24 个月内可能发生价值回归的低估龙头或低估优质公司。

核心因子：

- 估值折价：历史分位、行业分位、现金流收益率、股息率。
- 质量底线：ROE/ROIC、毛利率、经营现金流、负债压力。
- 行业位置：规模、份额、成本、渠道、品牌或牌照。
- 催化阶段：政策、业绩、价格、订单、产能、资产重估。
- 治理与回报：分红、回购、大股东行为、审计质量。

PE/PB 是估值上下文，不是硬卡尺。对银行、公用事业、周期、软件、医药、消费等行业使用不同估值模板。

### 7.2 QUALITY_COMPOUNDER

目标：寻找估值不一定便宜，但长期资本回报、增长质量和商业壁垒更好的公司。

核心因子：

- 多年 ROIC/ROE 稳定性。
- 收入和利润质量。
- 自由现金流。
- 壁垒证据。
- 管理层资本配置。

这类股票允许估值略高，但必须证明长期质量，而不是只靠热点叙事。

### 7.3 CYCLE_REVERSAL

目标：寻找周期底部或底部右侧，赔率明显改善的公司。

核心因子：

- 产品价格、库存、产能、成本、供需格局。
- 历史周期位置和利润弹性。
- 资产负债表抗压能力。
- 同行业头部公司 PE/PB、PB-ROE 和历史估值带。
- 左侧试仓、右侧加仓、急拉降温分别给出动作。

牧原、众兴菌业这类讨论应优先进入该策略族，而不是被普通 PE/PB 误伤。

## 8. 短线右侧策略

短线目标周期为 3 到 20 个交易日。目标不是追所有涨停，而是找“基本面无硬伤、流动性充足、当前热门方向内、右侧启动早期、尚未明显透支”的股票。

### 8.1 策略因子

```text
right_side_structure   右侧结构
volume_price_quality   量价质量
supply_absorption      惜售承接
hot_direction          动态热门方向
market_regime          市场状态
fundamental_floor      基本面底线
risk_overheat          过热与踩踏风险
```

PE/PB 只做极端风险提示，不作为短线主排序因子。

### 8.2 缩量上涨的处理

用户提出“基本面不错且缩量上涨，说明惜售情绪明显，可以做多”。V2 采纳这个思路，但不把“缩量上涨”单独等同于买入信号。

缩量上涨分为三种场景：

```text
POSITIVE_ABSORPTION
  放量突破已经出现，随后缩量上涨或缩量回踩不破关键位置。
  解释为抛压较轻、筹码稳定，可提高惜售承接分。

WEAK_PARTICIPATION
  突破当天也缩量，且行业热度或市场宽度不足。
  解释为跟随资金不足，不能给买入动作。

HIGH_LEVEL_DIVERGENCE
  高位连续缩量上涨，涨幅已远离均线或 ATR 合理区间。
  解释为追高风险，倾向 WAIT_PULLBACK 或 EXIT。
```

`SupplyAbsorptionScore` 初始因子：

```text
+ 放量突破确认
+ 缩量回踩不破 MA5/MA10/突破位
+ 上涨日收盘接近日内高位
+ 下跌日成交量明显低于上行日
+ 20 日成交额中位数充足
+ 换手稳定且未极端拥挤
- 高位缩量背离
- 长上影线放大
- 板块热度退潮
- 15:20 数据缺失或订单失衡不支持
```

只有以下条件同时满足，短线才允许输出 `ADD` 或 `LIGHT_TRIAL`：

- 基本面无硬风险。
- 当前行业或主题属于动态热门方向。
- 右侧结构处在启动早期，而不是高位末端。
- 流动性充足，用户仓位不会显著影响成交。
- 量价结构满足“放量确认 + 缩量承接”之一。
- 市场状态不是 `STRESS`。

### 8.3 交易阶段

```text
14:57 前
  只能形成候选，不把未完成日线当作收盘信号。

14:57-15:00
  评估收盘价、收盘位置和集合竞价风险。

15:05-15:30
  按 A 股盘后固定价格阶段处理，15:20 生成 provisional 信号。

15:31 后
  生成归档快照，用于次日跟踪和历史标签。
```

15:20 买入类动作必须有真实可用的盘后成交量、时间戳和订单相关数据。缺失时只能输出 `NEXT_WATCH` 或 `DATA_BLOCKED`。

## 9. 动作与口径

所有策略统一输出 `StrategySignal`：

```text
strategy_code
strategy_version
symbol
decision_at
data_cutoff_at
candidate_stage
action
position_limit
entry_condition
invalid_condition
rank_score
data_confidence
historical_hit_rate
risk_reward
evidence_summary
blocked_reasons
```

统一动作：

```text
ADD             加仓
LIGHT_TRIAL     轻仓试错
HOLD            持有
NEXT_WATCH      次日继续观察
WAIT_PULLBACK   等回踩
WAIT            观望
REDUCE          降仓
EXIT            清仓退出
DATA_BLOCKED    数据不足
RISK_BLOCKED    风险阻断
```

页面展示“今日建议”时，只展示最终动作；原始策略动作、风控调整和 Agent 分歧放在详情中。

## 10. Agent 证据链

多 Agent 不再只是多个角色共同读同一份材料，而是执行不同证据计划。

```text
PolicyAgent       政策、产业规划和监管方向
FinancialAgent    财报、审计、现金流和债务
MoatAgent         主营、客户、产能、专利、品牌和成本
ValuationAgent    历史估值、行业估值和情景估值
RiskAgent         诉讼、问询、质押、减持、关联交易和更正
MarketAgent       盘口、量价、热点和交易阶段
ContrarianAgent   反方证据和失败路径
```

每个 Agent 输出：

```text
claim
stance            SUPPORT / AGAINST / NEUTRAL / ABSTAIN
source_url
published_at
available_at
quote_hash
confidence
freshness
contradictions
```

找不到证据时必须 `ABSTAIN`。Agent 共识只能影响证据完整度、反证提示和研究阶段，不能绕过硬风控直接给买入。

## 11. 样本外验证

V2 策略必须先验证再发布。

验证要求：

- 使用点时数据，禁止未来函数。
- 覆盖牛市、熊市、震荡、轮动和压力环境。
- 使用滚动样本外验证，不能只看单一年份。
- 包含交易成本、滑点、涨跌停、停牌、T+1 和最小交易单位。
- 报告命中率、平均收益、盈亏比、最大回撤、回撤修复时间和样本数。

规则修改流程：

```text
DRAFT -> VALIDATING -> CANARY -> PUBLISHED -> ROLLED_BACK
```

未经验证的规则可以用于研究展示，但不能成为正式推荐依据。

## 12. 页面信息架构

V2 首屏不做营销页，直接进入投研工作台。

建议保留页面：

```text
全市场
长线价值
短线右侧
热门追踪
特别关注
交易复盘
规则设置
运行设置
```

建议合并或下线：

- 错杀估值：并入长线价值的 `VALUE_REVERSION`。
- 周期试仓：并入长线价值的 `CYCLE_REVERSAL` 子视图。
- 每日信号：变成全局聚合栏，不再独立制造一套口径。
- 回测验证：内化到推荐详情，不单独做白屏风险页面。

股票详情右侧固定展示：

- 策略动作和仓位上限。
- 因子分解。
- 证据链。
- Agent 分歧。
- 样本外验证摘要。
- 失效条件。
- 加入交易复盘入口。

## 13. 数据库与迁移

新增 V2 表时不破坏旧表。旧推荐和旧交易复盘继续可查。

建议新增表族：

```text
v2_security_master_history
v2_quote_snapshot
v2_daily_bar_snapshot
v2_financial_statement_snapshot
v2_financial_metric_snapshot
v2_industry_membership_history
v2_factor_definition
v2_factor_snapshot
v2_strategy_definition
v2_strategy_signal
v2_recommendation_ledger
v2_evidence_claim
v2_validation_run
v2_strategy_experiment
```

旧交易复盘表通过推荐凭证关联 V2 `recommendation_ledger`。不迁移历史旧推荐为 V2 结果，避免伪造当时不存在的因子快照。

## 14. 分阶段实施

### P0：V2 骨架与兼容层

- 新增 V2 包结构、策略契约和 DTO。
- 建立 V2 推荐信号标准。
- 旧页面可读取 V2 信号，但旧算法暂不删除。
- 建立测试夹具，验证同一股票在不同策略下动作不同。

### P1：点时数据内核

- 建立点时表和数据质量状态。
- 将行情、K 线、财务、公告和行业数据写入 V2 快照。
- 修复财报可见时间和估值历史未来函数风险。
- 全 A 扫描输出覆盖对账和缺失原因。

### P2：因子引擎

- 实现因子定义、单位校验、缺失策略、行业中性化和快照。
- 将 PE/PB 从硬阈值改为估值上下文。
- 输出 rank_score、data_confidence、historical_hit_rate、risk_reward 四类分。

### P3：长线策略族

- 实现 `VALUE_REVERSION`、`QUALITY_COMPOUNDER`、`CYCLE_REVERSAL`。
- 周期策略支持行业头部 PE/PB 对比和周期位置判断。
- 牧原、众兴菌业等特别关注股票可通过对应策略回放解释。

### P4：短线右侧 V2

- 实现市场状态、动态热门方向和右侧结构。
- 实现 `SupplyAbsorptionScore`，识别缩量承接、缩量乏力和高位背离。
- 15:00 与 15:20 信号分开展示。
- 风险模型剔除股票不在推荐列表展示。

### P5：样本外验证与策略发布

- 建立滚动验证、策略版本生命周期和发布门禁。
- 推荐详情展示验证摘要。
- 规则页面支持草稿、验证、发布和回滚。

### P6：Agent 证据独立性

- 每个 Agent 执行独立证据计划。
- 缺证据必须弃权。
- 展示证据重合、反证、冲突和新鲜度。

### P7：页面收敛与旧逻辑下线

- 合并错杀估值、周期试仓和每日信号。
- 页面只展示 V2 策略动作。
- 删除或隔离不再使用的旧算法入口。

## 15. 验收标准

- 全 A 扫描能对账实际覆盖数量、失败数量和失败原因。
- 任一推荐都能回放当时使用的数据、因子、规则版本和 Agent 证据。
- 长线和短线对同一股票可以给出不同动作且解释不冲突。
- 短线推荐不再因为 PE/PB 偏高直接错过热点基本面公司。
- “缩量上涨”只在合适场景提高惜售承接分，不单独触发买入。
- 任一买入类建议必须有仓位上限、失效条件和推荐期限。
- 样本外验证不足时，策略不能发布为正式推荐依据。
- 交易复盘能关联 V2 推荐快照，并继续支持分批买卖回填。
- 旧数据不被删除，旧页面在迁移期间仍可使用。
- 所有关键路径有单元测试、集成测试和至少一次本地页面验证。

## 16. 风险与应对

```text
风险：完全重构周期过长
应对：按 P0-P7 分阶段验收，每阶段都保持系统可运行。

风险：新模型推荐减少或全部观望
应对：分离硬风控、数据阻断和策略保守原因，页面展示漏斗分布。

风险：短线被缩量上涨误导
应对：必须区分放量确认后的缩量承接、突破缩量乏力和高位背离。

风险：回测虚高
应对：强制点时数据、退市样本、交易约束和样本外验证。

风险：AI 证据幻觉
应对：Agent 必须引用来源、时间和哈希，缺证据必须弃权。

风险：旧交易复盘断链
应对：保留旧表，V2 只新增推荐台账，不伪迁移旧快照。
```

## 17. 实施顺序结论

本重构按“新内核并行、旧系统兼容、逐步切流”的方式执行。

第一批实现应聚焦 P0 到 P2：

- V2 策略信号契约。
- 点时数据快照。
- 因子引擎。
- 推荐台账与旧页面 Adapter。

这些完成后，再进入长线策略族和短线右侧 V2。这样可以尽早证明系统骨架正确，而不是一次性重写到页面再发现数据和策略口径仍然不稳。
