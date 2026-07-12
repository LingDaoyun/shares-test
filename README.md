# AI Stock Platform

面向中国股票市场的长线与短线研究平台。当前版本的可审计主链路是：

```text
全 A 动态股票池 -> 点时行情/财报/K线 -> 可解释多因子 -> 风控门禁 -> 推荐/特别关注 -> 历史复盘
```

## 技术路线

```text
前端：React 18 + TypeScript + Vite + ECharts
后端：Java 17 + Spring Boot 3
数据：H2 文件库（Docker 默认）或 PostgreSQL/pgvector + Redis + MinIO + Nacos
规则：Java JSON DSL，版本化、可热更新、可回测
AI：可配置 LLM Provider，先支持 OpenAI 兼容协议，DeepSeek/OpenAI/Moonshot 可通过 Nacos 切换；AgentScope Java 作为二期 ai-agent-service
```

## 本地启动

命令行直接启动时默认使用 H2 内存库，方便开发测试。Docker 常驻模式使用 `/data/aistock` 文件库和命名卷，容器重启后保留特别关注、K 线、分析、决策与交易复盘历史。

```bash
mvn -pl apps/api spring-boot:run
```

默认会使用 Nacos 配置中心和服务发现。你已有测试 Nacos 时，按需指定地址：

```bash
NACOS_SERVER_ADDR=127.0.0.1:8848 ./scripts/run-api-local.sh
```

首次使用本地测试 Nacos 时，可以把项目示例配置发布进去：

```bash
./scripts/publish-nacos-config.sh
```

如需绕过 Nacos：

```bash
SPRING_PROFILES_ACTIVE=local mvn -pl apps/api spring-boot:run
```

如果要启用 AI 趋势分析，把模型 Key 放到本地忽略文件 `.env.local`，再用脚本启动：

```bash
cp .env.example .env.local
# OpenAI 示例：
LLM_PROVIDER=openai
LLM_API_KEY=...

# DeepSeek 开放平台示例：
LLM_PROVIDER=deepseek
LLM_API_KEY=...
LLM_MODEL=deepseek-v4-pro
LLM_BASE_URL=https://api.deepseek.com
LLM_RESPONSE_FORMAT=json_object
./scripts/run-api-local.sh
```

前端：

```bash
cd apps/web-react
npm install
npm run dev
```

Docker 常驻启动：

```bash
./scripts/docker-up.sh
```

默认会启动两个常驻容器：

```text
ai-stock-api  -> http://127.0.0.1:19080
ai-stock-web  -> http://127.0.0.1:5176
```

前端使用 HashRouter，当前页面地址：

```text
全市场扫描 -> http://127.0.0.1:5176/#/market
短线右侧   -> http://127.0.0.1:5176/#/short-term
回测兼容入口 -> http://127.0.0.1:5176/#/backtest（重定向至短线右侧）
热门追踪   -> http://127.0.0.1:5176/#/tech
错杀估值   -> http://127.0.0.1:5176/#/mispricing
周期试仓   -> http://127.0.0.1:5176/#/cycle
每日信号   -> http://127.0.0.1:5176/#/signals
特别关注   -> http://127.0.0.1:5176/#/watchlist
交易复盘   -> http://127.0.0.1:5176/#/trade-review
规则管理   -> http://127.0.0.1:5176/#/rules
运行设置   -> http://127.0.0.1:5176/#/settings
```

后端容器默认连接宿主机已有 Nacos：

```text
NACOS_SERVER_ADDR=host.docker.internal:8848
```

如果要改成其他 Nacos 地址：

```bash
NACOS_SERVER_ADDR=host.docker.internal:8848 ./scripts/docker-up.sh
```

基础设施按需启动，默认不跟应用一起启动，避免和本机已有 Nacos/Redis/MinIO 抢端口：

```bash
docker compose --profile infra up -d postgres redis minio nacos
```

Nacos 说明见 [docs/nacos-config.md](docs/nacos-config.md)。

生产或联调 PostgreSQL 时使用：

```bash
SPRING_PROFILES_ACTIVE=prod \
JDBC_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/aistock \
JDBC_DATABASE_DRIVER=org.postgresql.Driver \
JDBC_DATABASE_USERNAME=aistock \
JDBC_DATABASE_PASSWORD=change-me \
mvn -pl apps/api spring-boot:run
```

`application-prod.yml` 默认使用 `org.postgresql.Driver`；以上四个 `JDBC_DATABASE_*` 变量应按实际 PostgreSQL 连接信息设置。

## 当前 API

```text
GET  /api/policy/themes
GET  /api/companies
GET  /api/companies/{symbol}
GET  /api/companies/{symbol}/research
GET  /api/companies/{symbol}/filings
GET  /api/companies/{symbol}/agent-consensus
GET  /api/companies/{symbol}/agent-consensus/prompt
POST /api/companies/{symbol}/agent-consensus/ai
GET  /api/selection/agent-shortlist
GET  /api/market-scan/report
POST /api/short-term/scan-jobs
GET  /api/short-term/scan-jobs/{jobId}
GET  /api/rules
PUT  /api/rules/{ruleCode}
POST /api/rules/evaluate
GET  /api/watchlist
POST /api/watchlist
DELETE /api/watchlist/{symbol}
POST /api/watchlist/{symbol}/analyze
GET  /api/watchlist/{symbol}/history
GET  /api/trade-cases?status=&symbol=&limit=&beforeCreatedAt=&beforeCaseId=
POST /api/trade-cases                body: {"attestationToken":"..."}
GET  /api/trade-cases/{caseId}
POST /api/trade-cases/{caseId}/fills
PUT  /api/trade-cases/{caseId}/fills/{fillId}
DELETE /api/trade-cases/{caseId}/fills/{fillId}
POST /api/trade-cases/{caseId}/cancel
POST /api/trade-cases/{caseId}/refresh
GET  /api/strategy-feedback
GET  /api/ai/trend-prompts/sample
POST /api/ai/trend-prompts/preview
POST /api/ai/trend-analysis
GET  /api/ai/llm-config
GET  /actuator/health
```

本地 API 端口默认是 `19080`，避免和常见本地服务的 `8080` 冲突。

## 推荐复盘与策略反馈

在短线右侧、热门追踪、错杀估值、周期试仓和每日信号的推荐卡上，点击“加入复盘”即可创建复盘案例。凭证绑定的是推荐价格对应的源行情时间，不使用报告生成时间代替；缺失时间、明显未来时间、超过 7 天的行情，或交易时段内已被短线新鲜度门禁阻断的报价都不会签发。推荐响应中的 `tradeCaptureTokens` 由服务端签发，默认有效 30 分钟；创建接口只接受凭证，不接受客户端自填的来源、规则版本、价格、时间或 payload。凭证过期时刷新对应推荐页即可重新签发。相同推荐快照会幂等地返回同一案例，不会重复入库。复盘操作、成交录入和结果查看在独立页面 `#/trade-review` 完成，不改变推荐排序、全 A 股票池或特别关注列表。

创建时会冻结服务端认证的推荐快照：股票、公司、来源模块、推荐动作、规则版本、推荐价格、推荐时间及原始 payload。迁移前的历史案例标记为“历史未认证”，仍可查看和维护，但不进入策略反馈或 Agent 提示词。一个案例可以录入多笔 `BUY`/`SELL`，支持分批成交。每笔成交必填方向、成交时间、成交价格和正整数股数；买入会移动加权平均成本，卖出超过当前持仓会被拒绝，成交时间不得早于推荐时间或明显晚于服务器当前时间。页面中的修改和删除分别追加 `CORRECTION` 和 `VOID` 审计事件，原始成交行不会覆盖或物理删除；修订按案例内单调序号投影，不依赖数据库时间精度或随机 ID 判断先后。账本显示已实现、未实现和累计**毛收益**，未计佣金、印花税、分红和送转股，不能作为净盈亏使用。

复盘页面同时保留推荐基线和实际执行基线：推荐侧为 `CURRENT`、`T1`、`T5`、`T20`，执行侧为 `CURRENT` 或平仓后的 `CLOSED`。`T1/T5/T20` 按推荐日之后的实际交易日行计算，不以自然日代替；未成熟显示 `PENDING`，数据源失败、空分片、解析丢行、重复交易日或日期边界覆盖不足显示可恢复的 `UNAVAILABLE`，不会使用部分 K 线或伪造收益。已经成熟的固定期限结果不会被后续故障降级。成交新增、修改或作废后，案例会标记为结果待刷新并立即隐藏旧执行结果；成功重算后才恢复展示。每条结果展示真实数据源、行情时间和计算时间；可通过“刷新”或 `POST /api/trade-cases/{caseId}/refresh` 手动刷新，系统也会在每个工作日 `Asia/Shanghai 18:10` 优先处理结果待刷新的案例，再按最久未刷新顺序处理最多 100 个非取消案例。

策略反馈只按“来源模块 + 规则版本”聚合服务端认证且已成熟的推荐 `T20` 样本：查询窗口最多 5000 条并缓存 10 分钟；同一队列达到 5 个样本才允许注入 Agent 提示词，达到 20 个样本才计算可靠性调整，且调整被限制在 `-5..+5`。反馈是历史证据，不会自动写入 Nacos，不会改变确定性共识分，也不会绕过硬风控规则。复盘列表默认每页 50 条、最大 200 条，使用 `createdAt + caseId` 游标加载更早记录；数据库通过 Criteria `setMaxResults` 有界读取，不执行总数统计，前端游标只取服务端页面末尾，不受单条详情缓存影响。

## 历史数据

Docker 默认把数据保存到 `shares-test_api-data` 卷。开发和单机常驻使用 H2 文件库；生产或联调可通过 `SPRING_PROFILES_ACTIVE=prod` 和 `JDBC_DATABASE_*` 切换至 PostgreSQL，两者使用同一份业务 schema。三类决策依据分别落在：

```text
market_kline_history          K 线观察版本，同一交易日数据修订会保留新版本
research_analysis_history     完整分析快照、AI Provider/模型、数据截至时间
investment_decision_history   动作、分数、来源、规则版本，并关联分析快照
```

`SPECIAL_ATTENTION` 表示用户主动触发的长线多 Agent 分析，`SHORT_TERM_SCAN` 表示自动短线扫描。特别关注只负责主动跟踪，不参与任何推荐排序，也不会成为股票池白名单。

## 当前在线数据源

```text
多官方政策源：
中国政府网、国家发展改革委、工业和信息化部、科学技术部、财政部、国家能源局、中国证监会、
生态环境部、农业农村部、交通运输部

腾讯 A 股实时行情：
https://qt.gtimg.cn/q=

东方财富 A 股行情备用源：
https://push2.eastmoney.com/api/qt/clist/get

东方财富主力资金流：
https://push2.eastmoney.com/api/qt/ulist.np/get
https://push2.eastmoney.com/api/qt/stock/fflow/kline/get

东方财富数据中心年报指标：
https://datacenter-web.eastmoney.com/api/data/v1/get

巨潮资讯公告查询：
https://www.cninfo.com.cn/new/hisAnnouncement/query
```

开发联调默认启用快速公司列表，避免每次刷新公司池都同步拉取年报指标：

```text
research.live-data.fast-company-list=true
research.live-data.eastmoney-fund-flow-url=https://push2.eastmoney.com/api/qt/ulist.np/get
research.live-data.eastmoney-fund-flow-minute-url=https://push2.eastmoney.com/api/qt/stock/fflow/kline/get
research.live-data.cninfo-announcement-url=https://www.cninfo.com.cn/new/hisAnnouncement/query
research.live-data.filing-limit=12
research.live-data.filing-pdf-parse-limit=2
research.live-data.filing-pdf-max-pages=6
```

如果要做完整财务因子重算，可以在 Nacos 中改为 `false` 后重启或等待配置刷新。
公告证据当前优先覆盖可匹配巨潮证券内部编码的样本；接口失败或编码缺失时会降级为公司画像中的年报/公告证据，并把数据缺口展示到研究视图。PDF 正文解析默认只抽取少量最新公告的前几页，适合在线详情页；完整年报/公告解析后续应进入批处理和文档库。

数据源优化参考了 [simonlin1212/a-stock-data](https://github.com/simonlin1212/a-stock-data) 的源优先级思路：行情/K 线优先使用腾讯，东方财富主要用于独有数据，如资金流、板块和数据中心指标；东财请求会做串行节流和重试，降低盘中接口风控导致的失败率。历史日 K 已切到前复权口径，避免除权除息日扭曲均线和区间位置。

普通行情、政策和规则试算不需要 AI API Key。AI 趋势分析和 Agent 辩论增强接口已经升级为可配置 LLM Provider：

```text
research.ai.llm.provider=deepseek | openai | moonshot | kimi-code
research.ai.llm.api-key=...
research.ai.llm.model=deepseek-v4-pro | deepseek-v4-flash | gpt-5.5 | kimi-k2.6 | kimi-for-coding
research.ai.llm.base-url=https://api.deepseek.com | https://api.openai.com/v1 | https://api.moonshot.ai/v1 | https://api.kimi.com/coding/v1
research.ai.llm.response-format=json_object | json_schema | none
```

DeepSeek 官方 OpenAI 兼容接口支持 JSON Object 输出，本平台默认 `provider=deepseek` 时使用 `response-format=json_object`，并通过提示词约束结构化字段。

`kimi-code` 对应 Kimi Code 文档中的 `https://api.kimi.com/coding/v1` 和 `kimi-for-coding`，实测服务端会限制为编码 Agent 场景。投研平台后端作为通用应用调用时，应优先使用 Kimi/Moonshot 开放平台 Key，并配置 `provider=moonshot`。

如果没有配置 LLM API Key，`POST /api/ai/trend-analysis` 会直接返回配置缺失提示，不会降级为假分析；`POST /api/companies/{symbol}/agent-consensus/ai` 会保留确定性共识，并在 `aiWarnings` 中返回配置或模型调用问题。

AI 提示词设计见 [docs/ai-trend-analysis-prompt.md](docs/ai-trend-analysis-prompt.md)。

## 研究评分口径

公司详情新增统一研究视图，不直接给买卖结论，而是输出可解释的长线观察阶段：

```text
趋势匹配、财务质量、核心壁垒、估值安全边际、风险排雷
-> 证据强度分层
-> 公告证据：公告样本、PDF 正文解析数、壁垒线索、风险线索、兑现线索
-> 正文事件：风险事件 / 壁垒线索 / 兑现线索
-> 多 Agent 共识：政策策略 / 财务质量 / 公告壁垒 / 估值纪律 / 反方风控
-> 可选 AI 辩论增强：各 Agent 论证 / 反证 / 信心说明 / 建议补证
-> Agent 选股会：全市场候选 / 入选理由 / 讨论 trace / 补证清单
-> 数据缺口
-> 下一步核查动作
-> 观察阶段：样本观察 / 证据验证 / 等待价格 / 估值观察 / 风险复核
```

观察池的推荐指数会融合规则引擎结果和研究评分，避免规则页、公司详情和观察池三套口径不一致。公司详情页额外提供多 Agent 共识讨论，用于暴露分歧、反证和补证清单；也可以手动触发 AI 辩论增强，让模型基于确定性证据补充各 Agent 的论证和反证。Agent 选股会会在全市场公司池中复核一批候选，输出少量入围标的，并保留每只股票的讨论过程、投票和补证要求。共识阶段仍是投研辅助，不构成交易建议。

## 重要原则

- AI 负责资料抽取、证据链整理和研究报告。
- 规则引擎负责筛选、评分、风控和回测。
- 所有规则发布都必须保留版本、审计日志和回滚入口。
- 平台输出用于投研辅助和规则验证，不构成任何投资建议。
