# 短线去估值与多角色 AI 讨论设计

## 1. 文档状态

- 日期：2026-08-13
- 状态：待书面规格确认
- 目标分支：codex/short-term-ai-discussion
- 基线：main / a46cae9
- 适用模块：Spring Boot API、React 短线页面、H2/PostgreSQL 数据库
- 不适用模块：长期价投、政策解读、特别关注、交易复盘

本文把已确认的产品方向固化为可实施、可测试、可对抗复核的合同。实现 Agent 不得自行扩大 AI 的决策权限，也不得为了赶进度降低 95% 行情覆盖、点时、尾盘窗口、风险门禁或 14:49:40 终态认证要求。

## 2. 背景与当前事实

当前短线预选仍把估值语境计入排序：

- 估值 10%
- 流动性 30%
- 不追高 25%
- 市场热度 35%

ShortTermService.preliminaryScore 会读取 PE、PB 与参考上限，ShortTermPage 仍显示 PE 参考带、PB 参考带、PE TTM、PB、估值参考和估值语境分。即使 PE/PB 已不再是最终技术结构的主因，它们仍会影响进入 K 线复核池的顺序，因此“仅在页面隐藏”不能满足本次要求。

当前短线结果由确定性规则生成，模型配置虽已从数据库动态读取，但短线扫描本身没有调用大模型。大模型只被其他 AI 分析入口显式调用。现有手动扫描任务保存在进程内且只短期保留，不能承担讨论历史。

当前调度边界保持：

| 事件 | 当前时间 |
| --- | --- |
| 预选任务 | 14:30 |
| 尾盘最终任务 | 14:47 |
| 最终认证截止 | 14:49:40 |
| 就绪检查 | 14:49:50 |
| 普通股票可执行尾盘窗口 | 14:45 至 14:49 |

数据库默认是命名卷中的 H2 文件，生产可切换 PostgreSQL。新表必须使用两者兼容的普通 SQL/JPA，不引入 Redis、消息队列、Nacos或新的必选基础设施。

## 3. 产品结论

本次采用以下结论：

1. PE/PB 从短线决策链完全移除。
2. 短线候选详情对新旧记录均完全隐藏 PE/PB 与估值语境。
3. 长期价投继续保留估值分析，不受本次变更影响。
4. 短线候选生成后，在后台启动四个真正独立的 AI 角色并行讨论。
5. AI 讨论只提供研究意见，不能增删候选、改变排序、改变动作、绕过硬门禁或拖延最终发布。
6. 模型可自行决定是否调用受控联网搜索与历史讨论搜索工具；应用负责实际执行工具。
7. 讨论的原始响应、结构化意见、工具调用、证据和错误全过程落库。
8. 同一股票的所有历史讨论可查看，新讨论必须指向父讨论并说明观点变化。
9. 不设置业务层文字数、段落数或结论长度上限；用时间预算、工具轮数和异常续写保障完整性。
10. 实施采用多 Agent 分阶段开发和独立对抗复核；发现 Critical 或 Important 问题即驳回，修复并复审后才能进入下一阶段。

## 4. 目标

### 4.1 短线规则目标

- PE、PB、估值折价、估值语境和估值参考不参与短线预选、复核池顺序、最终排序、动作、风险扣分或解释。
- 仅改变 PE/PB 输入时，新版本短线候选、排名、得分、动作和理由保持不变。
- 市场热度、成交流动性和不追高承担预选排序，不削弱财务硬风险、ST、退市、权限、T+1、行情覆盖和点时门禁。
- 旧 V4 记录继续可反序列化和查看，但短线界面不再展示估值字段。

### 4.2 AI 讨论目标

- 自动对短线候选进行多维度、短时、可追溯讨论。
- 优先判断隔夜持有到下一交易日的溢价条件、兑现路径和失效条件。
- 支持模型主动搜索可信公开资料，而不是由固定关键词替模型决定所有检索。
- 任一角色超时、异常或搜索失败时保留已完成内容并形成 PARTIAL 或 INCOMPLETE 结论。
- 应用重启后仍能查看历史，并能恢复尚可继续的队列任务。
- 前台收到讨论结果后可进入独立页面查看全过程与同股历史。

### 4.3 时间目标

- 预选完成后立即排队，四角色并行。
- 单角色目标完成时间 60 秒，普通硬超时 90 秒。
- 调度批次在 14:44:30 后不再等待未完成角色；届时形成部分结论或超时记录。
- 14:47 的确定性尾盘任务不等待 AI。
- 无论模型、搜索或数据库讨论任务是否异常，确定性最终结果仍必须按既有两阶段认证在 14:49:40 前完成可执行发布，否则按既有规则失败关闭。

## 5. 非目标

- 不让 AI 直接荐股、扩大候选池或调整确定性排名。
- 不让 AI 生成或覆盖买入、加仓、卖出动作。
- 不把论坛、股吧、自媒体、匿名聚合站或未知域名作为证据。
- 不允许模型请求任意 URL、内网地址、文件地址或本机服务。
- 不建设通用聊天系统、向量数据库、知识图谱或长时间辩论室。
- 不把讨论历史塞进现有内存扫描任务。
- 不修改长期价投模块中的 PE/PB、估值纪律和估值页面。
- 不保证外部模型永不达到其物理上下文或输出上限；系统负责识别并续写，而不是把截断内容伪装成完整结论。

## 6. 不可破坏的确定性边界

以下边界优先级高于所有 AI 输出：

1. 全市场行情覆盖率低于 95% 时，不得产生可执行结果。
2. 行情新鲜度、交易日、数据截止时间和点时顺序必须有效。
3. ST、退市、流动性、交易权限、T+1、严重财务风险和大盘风险闸门保持。
4. 普通股票只使用 14:45 至 14:49 已产生的分时完成尾盘确认。
5. FINAL_PENDING 与 FINAL_READY 的数据库时间认证和负向恢复语义保持。
6. AI 结论不能写回候选的 rank、rankingScore、action、confidence、validationEligible 或 gateStatus。
7. AI 晚到不能回写或重新解释为当日可执行买点。

候选主结果与 AI 研究结果是两个并列读模型：

    确定性扫描 -> 候选快照 -> 最终发布
                         |
                         +-> AI 讨论队列 -> 研究意见与历史

前端可以在候选旁展示 AI 状态和观点，但不得把 AI 状态混入 FINAL_READY 判定。

## 7. PE/PB 从短线链路完全移除

### 7.1 新预选权重

新短线策略版本使用：

| 因子 | 权重 |
| --- | ---: |
| 市场/题材热度 | 45% |
| 成交流动性 | 30% |
| 不追高 | 25% |
| PE/PB 或估值语境 | 0% |

实现不能先计算估值再乘以零；新版本的 preliminaryScore 不得调用估值计算器，也不得读取 maxPe、maxPb、peTtm 或 pbRatio。

### 7.2 移除范围

新版本中 PE/PB 不得影响：

- 全市场 quote 预过滤；
- 预选得分；
- K 线复核池排序和截断；
- 任一信号族是否入围；
- 最终 setupScore 或 rankingScore；
- 风险惩罚、动作、置信度；
- 证据完整度和数据质量状态；
- 候选 strengths、risks、reasoning、evidence；
- AI 候选快照、系统提示、角色提示或搜索词；
- 手动扫描、自动预选、自动最终和历史重放的当前版本路径。

### 7.3 兼容策略

- ShortTermScanRequest 后端可暂时接受 maxPe 与 maxPb，以兼容旧链接或旧客户端，但新版本忽略它们并在 API 文档中标为 deprecated。
- React 短线请求和配置表单不再发送或显示这两个字段。
- ShortTermCandidate、旧 JSON 快照和数据库中可保留原始 PE/PB 字段以保证历史反序列化，但新记录应置空或只留在不对外的原始行情快照。
- ShortTermWeightProfile 保持向后反序列化能力；新策略版本使用明确的新 modelVersion，不复用 V4 标识。
- 历史 V4 决策原始数据不迁移、不重算、不覆盖。
- 所有短线详情，包括历史详情，统一隐藏 PE/PB、估值参考、估值语境和 valuationScore。
- 长期模块及其 DTO、页面、测试不做机械删除。

### 7.4 反隐藏影响证明

必须增加变形测试：固定除 PE/PB 外的所有输入，分别使用极低、极高、空值和异常 PE/PB，断言当前策略版本的候选集合、复核顺序、排名、得分、动作、理由和风险代码完全一致。

还必须增加短线包范围检查，确保新提示构建器和当前规则路径没有引用 PE/PB。单纯搜索不到页面文字不算完成。

## 8. AI 讨论触发与生命周期

### 8.1 触发源

讨论批次支持：

- SCHEDULED_PRESELECT：14:30 预选成功后自动触发。
- MANUAL_SCAN：手动扫描成功并持久化候选快照后自动触发，扫描 HTTP 请求不等待 AI。
- FINAL_SUPPLEMENT：最终候选与预选候选不同或关键快照变化时，在确定性结果发布后补充讨论；只标记为研究用途。
- CONTINUATION：用户在讨论页主动继续讨论，生成子轮次。

数据质量阻断、扫描失败或候选为空时，不创建空角色任务；批次保存明确的 NOT_ENQUEUED 原因供前端显示。

### 8.2 生命周期状态

批次和轮次使用以下状态：

- QUEUED
- RUNNING
- PARTIAL
- COMPLETED
- INCOMPLETE
- TIMED_OUT
- FAILED
- RESEARCH_ONLY
- CANCELLED

合法主路径：

    QUEUED -> RUNNING -> COMPLETED
                      -> PARTIAL
                      -> INCOMPLETE
                      -> TIMED_OUT
                      -> FAILED

FINAL_SUPPLEMENT、14:50 后完成的调度讨论以及非交易时段手动研究带 RESEARCH_ONLY 标签。它可以同时具有完成状态，但任何前端文案都不得将其描述为当日执行依据。

### 8.3 队列和执行器

- 一个有界编排执行器负责批次和轮次。
- 四个有界角色执行器并行调用同一轮的四个角色。
- 队列满时数据库记录 QUEUE_REJECTED，不阻塞扫描线程。
- 先提交数据库任务，再异步唤醒执行器，避免进程在入队与记录之间崩溃造成不可追踪任务。
- 模型网络调用期间不持有数据库行锁或事务。
- 每个运行任务具有 claimOwner、claimToken、leaseUntil 和 heartbeatAt。
- 多实例使用带状态和租约条件的原子 UPDATE 认领任务，以受影响行数判定所有权；不得使用 exists 后 insert 或进程锁模拟跨实例互斥。
- 唯一约束保证同一 sourceType、sourceKey 和 symbol 只生成一个初始轮次。
- 进程重启后重新扫描 QUEUED 和租约过期的 RUNNING 任务；若当日时间预算已过则原子标记 TIMED_OUT 或 RESEARCH_ONLY，不重复生成一个新轮次。

## 9. 四个独立角色

四个角色是四次独立模型调用，而不是在一个提示中要求模型模拟四种口吻：

| 角色代码 | 关注范围 |
| --- | --- |
| SENTIMENT_THEME | 市场情绪、板块热度、题材持续性、同题材竞争与退潮风险 |
| PRICE_VOLUME | 价量结构、资金方向、尾盘承接、涨幅位置、次日流动性 |
| OVERNIGHT_CATALYST | 隔夜催化、次日溢价兑现路径、公告与政策时效 |
| RISK_CONTRARIAN | 反方审查、拥挤交易、利好兑现、公告风险、次日低开与止损难度 |

每个角色收到相同的不可变候选快照、数据截止时间和历史父轮次摘要，但角色系统提示不同。任何角色不得看到 PE/PB 或估值字段。

每个角色必须返回：

- stance：SUPPORT、WATCH、OPPOSE 或 INSUFFICIENT；
- confidence：0 至 100，仅表示该角色对研究判断的主观把握，不是收益概率；
- thesis：完整论点；
- supportingEvidence：支持证据；
- negativeEvidence：反证；
- nextDayDrivers：下一交易日可能的实现驱动；
- invalidationConditions：观点失效条件；
- evidenceGaps：证据缺口；
- citations：引用证据标识；
- dataCutoffAt：判断使用的最晚时间；
- priorRoundDelta：相对父轮次的观点变化；
- priorRoundDeltaReasons：变化原因。

结构化字段和完整原始响应同时保存。结构化解析失败时保存 rawResponseText、解析错误和 finishReason，并把该角色标记 INCOMPLETE，不丢弃原文。

## 10. 聚合器

首期不增加第五个模型主席。确定性聚合器读取四个角色的结构化状态并生成：

- CONSENSUS_SUPPORT
- DIVERGENT
- RISK_ALERT
- INCOMPLETE
- PENDING

基本规则：

- 三个及以上 SUPPORT 且无高置信 OPPOSE：CONSENSUS_SUPPORT。
- 至少一个高置信 RISK_CONTRARIAN OPPOSE，或任意角色发现明确严重公告风险：RISK_ALERT。
- SUPPORT 与 OPPOSE 均存在且未触发风险警报：DIVERGENT。
- 少于三个角色形成完整意见，或存在未续写完成的 length 截断：INCOMPLETE。
- 仍有角色运行：PENDING。

聚合状态不改变候选排序和交易动作。页面必须把“规则动作”和“AI 研究共识”分开显示。

## 11. 模型发起的受控联网搜索

### 11.1 工具调用事实边界

模型只会生成工具名和参数，应用必须校验参数、执行外部搜索，再把结果作为 tool 消息返回模型。不能把“模型支持 function calling”描述成模型自身可以不经应用访问互联网。

首期提供两个工具：

1. search_trusted_stock_evidence
2. search_discussion_history

### 11.2 search_trusted_stock_evidence 合同

模型可以选择：

- symbol：当前候选股票代码，只能等于轮次代码；
- query：搜索问题；
- sourceTypes：DISCLOSURE、EXCHANGE、COMPANY_IR、GOVERNMENT、FINANCIAL_MEDIA；
- publishedFrom 与 publishedTo；
- intent：CATALYST、RISK、THEME、FLOW_CONTEXT 或 FACT_CHECK。

模型不能提供 URL、域名、IP、请求头、HTTP 方法、端口或认证信息。

应用侧 TrustedEvidenceToolGateway：

1. 校验 JSON Schema 和枚举；
2. 强制 symbol 等于当前轮次；
3. 将 publishedTo 截断到轮次 dataCutoffAt；
4. 使用服务器维护的可信域名集合，而不是模型提供的域名；
5. 调用 source adapter 或搜索 provider；
6. 对每个结果重新解析最终 URL、协议、域名、重定向和 IP；
7. 丢弃截止时间后、发布时间不明而又要求点时执行的证据；
8. 将网页文本视为不可信数据，包裹在明确的数据边界内再回传模型；
9. 记录工具请求、校验结果、搜索耗时、供应商请求号、每条证据和失败原因。

### 11.3 搜索来源

可信来源首批包括：

- 巨潮资讯；
- 上海证券交易所与深圳证券交易所；
- 上市公司官网和投资者关系页面；
- 国务院、部委、省市政府与依法公开的政策网站；
- 财联社、证券时报、上海证券报、中国证券报等主流财经媒体。

不允许：

- 论坛、股吧、自媒体、无来源转载；
- 用户或模型临时添加的域名；
- localhost、私网、链路本地、保留地址和非 HTTP(S) 协议；
- 域名校验后又重定向到不可信目标；
- 把网页里的提示词、脚本或操作指令当成系统指令。

### 11.4 实际搜索适配器

第一版使用三层来源：

- 复用现有 CninfoClient 与已获取的公告/政策证据；
- 复用数据库中的历史讨论与已经持久化的证据；
- 新增 TavilySearchAdapter，用固定的 https://api.tavily.com/search 调用通用搜索，并始终传入服务器端 include_domains、日期范围、include_answer=false 与 include_raw_content=false。

Tavily API Key 只允许从固定操作系统环境变量 TAVILY_API_KEY 读取，不能通过模型、数据库任意字段、Spring Environment 任意属性名或 API 请求选择。搜索端点固定，不允许在管理页面修改。

Tavily 返回结果仍必须经过本地可信域名、重定向、IP 和时间复验；第三方的域名过滤不替代本地校验。搜索不可用时工具返回结构化 SEARCH_UNAVAILABLE，角色继续使用本地证据并公开 evidenceGaps。

部署验收必须配置有效的 TAVILY_API_KEY 并完成一次可信域名实时搜索；若密钥未配置，代码功能可以通过 fake adapter 验证，但不能宣称“联网搜索已在当前运行环境可用”。

### 11.5 搜索预算

- 每个角色最多两轮外部搜索。
- 每轮最多返回八条结果。
- 采用快速或基础搜索深度，不请求第三方生成答案，不抓取任意完整网页。
- 搜索 HTTP 超时包含在单角色 90 秒预算内。
- 搜索轮数和结果数用于控制时延、攻击面和成本，不是对模型最终论述长度的截断。

## 12. 历史讨论搜索与延续

### 12.1 股票线程

每个六位股票代码只有一个持久化 discussion thread。每次自动讨论或手动继续创建不可变的新 round。

- 初次讨论 parentRoundId 为空。
- 新交易日自动讨论默认指向该股票最新已结束轮次。
- 用户点击“继续讨论”时必须指定当前查看轮次为父轮次。
- 父轮次不能被后续讨论覆盖。
- 页面可沿父链查看观点演化，也可按时间列出全部历史。

### 12.2 search_discussion_history 合同

模型可以请求：

- 当前 symbol；
- 时间范围；
- roleCodes；
- stance；
- query；
- limit。

应用强制 symbol 等于当前轮次，返回命中轮次的标识、日期、角色、观点摘要、完整内容引用标识和当时证据。直接父轮次的完整角色输出可以预载；更早内容通过工具按需检索，避免把全部历史无条件塞入上下文。

历史讨论属于研究证据，不能被描述为市场事实。模型必须区分“过去角色观点”与“公开来源事实”。

## 13. 不截断输出

本功能不设置业务层字符数、段落数、观点数或前 N 条论述上限：

- 数据库长文本使用 TEXT/CLOB 兼容映射。
- rawResponseText、thesis、证据、反证、失效条件和变化说明完整保存。
- API 不对子字段 substring，不追加省略号，不只返回摘要替代详情。
- UI 默认可折叠以保持可读性，但“展开全部”返回并显示完整内容。
- 讨论调用在供应商允许时不设置更低的 max_tokens；如必须设置，使用该供应商/模型支持的最大配置值，而不是页面展示需要决定的较小值。

供应商物理上限和时间截止仍然存在：

- finishReason=length 时保存当前完整原始片段并标记 INCOMPLETE。
- 时间预算允许时最多发起一次同角色续写，携带上一片段和结构化缺失字段。
- 续写内容单独作为事件保存，合并时保留片段边界。
- 仍未完成时展示“模型输出达到上限，内容不完整”，不得伪装为 COMPLETED。
- 90 秒或 14:44:30 截止优先于继续等待；超时不删除已产生内容。

## 14. 点时与发布时间

### 14.1 调度讨论

| 时间 | 行为 |
| --- | --- |
| 14:30 左右 | 预选完成后保存批次、候选快照并并行启动四角色 |
| 14:31 左右 | 目标完成时间，可提前展示已完成角色 |
| 14:44:30 | 同日执行相关 AI 等待上限；未完成任务转为部分/超时 |
| 14:47 | 确定性最终任务独立启动，不查询 AI 是否完成 |
| 14:49:40 | 确定性最终结果认证截止 |
| 14:50 后 | 晚到 AI 只能 RESEARCH_ONLY，不能回写当日动作 |

### 14.2 手动讨论

手动扫描可在任意允许的研究时段触发。其 AI 任务仍使用单角色 90 秒预算，但只有扫描本身满足当日点时与执行门禁时，页面才可并列展示当日规则动作。AI 本身从不让研究预览变成可执行结果。

### 14.3 最终候选变化

预选与最终候选可能不同：

- 相同候选沿用预选讨论并显示候选快照时间。
- 新增或关键快照变化的最终候选在结果发布后创建 FINAL_SUPPLEMENT。
- 补充讨论标记 RESEARCH_ONLY。
- 不为了等待补充讨论延迟或重新发布最终候选。

## 15. 数据模型

所有主键采用应用生成的 UUID 字符串，时间使用带时区语义的数据库字段，长内容使用 TEXT。表结构同时兼容 H2 PostgreSQL mode 与 PostgreSQL。

### 15.1 short_term_ai_discussion_thread

一只股票一个线程：

- thread_id，主键；
- symbol，唯一；
- company_name_snapshot；
- created_at；
- last_round_at；
- version，用于乐观并发。

### 15.2 short_term_ai_discussion_batch

一次扫描或继续操作的批次：

- batch_id，主键；
- source_type；
- source_key；
- trade_date；
- source_snapshot_key；
- strategy_version；
- prompt_version；
- data_cutoff_at；
- same_day_wait_deadline；
- status；
- aggregate_started_at；
- completed_at；
- error_code；
- error_message；
- created_at、updated_at；
- version；
- 唯一约束 source_type + source_key。

source_key 对自动快照使用不可变 snapshotKey，对手动扫描使用已持久化的 scan result identity，对继续讨论使用幂等请求键。不能依赖进程内 scan job 30 分钟保留期。

### 15.3 short_term_ai_discussion_round

一名候选在一个批次中的讨论轮次：

- round_id，主键；
- batch_id，外键；
- thread_id，外键；
- symbol；
- parent_round_id，可空自外键；
- phase：PRESELECT、FINAL_SUPPLEMENT、MANUAL、CONTINUATION；
- status；
- advisory_state；
- research_only；
- candidate_rank_snapshot；
- candidate_action_snapshot；
- candidate_snapshot_json；
- data_cutoff_at；
- started_at、completed_at；
- change_summary；
- error_code、error_message；
- created_at、updated_at；
- version；
- 唯一约束 batch_id + symbol。

candidate_snapshot_json 必须通过专用 AI 快照 DTO 构建，明确排除 PE/PB、估值和密钥字段，不能直接序列化整个 ShortTermCandidate。

### 15.4 short_term_ai_role_run

一次角色模型调用及其尝试：

- role_run_id，主键；
- round_id，外键；
- role_code；
- attempt_no；
- status；
- provider；
- model；
- model_settings_json，脱敏；
- prompt_version；
- request_started_at；
- response_completed_at；
- timeout_at；
- finish_reason；
- input_tokens、output_tokens、reasoning_tokens；
- raw_response_text；
- parse_error；
- error_code、error_message；
- claim_owner、claim_token、lease_until、heartbeat_at；
- created_at、updated_at；
- version；
- 唯一约束 round_id + role_code + attempt_no。

attempt_no=1 是主调用；达到输出上限后的唯一续写为 attempt_no=2。网络重试不能无记录覆盖原尝试。

### 15.5 short_term_ai_role_opinion

解析成功或部分成功的角色意见：

- opinion_id，主键；
- role_run_id，唯一外键；
- stance；
- confidence；
- thesis；
- supporting_evidence_json；
- negative_evidence_json；
- next_day_drivers_json；
- invalidation_conditions_json；
- evidence_gaps_json；
- citation_ids_json；
- data_cutoff_at；
- prior_round_delta；
- prior_round_delta_reasons_json；
- complete；
- created_at。

### 15.6 short_term_ai_discussion_event

只追加的全过程事件：

- event_id，主键；
- round_id；
- role_run_id，可空；
- sequence_no；
- event_type；
- tool_name；
- request_json，脱敏；
- response_json 或 response_text；
- occurred_at；
- duration_ms；
- success；
- error_code、error_message；
- 唯一约束 role_run_id + sequence_no。

事件类型至少包括 MODEL_REQUEST、MODEL_RESPONSE、TOOL_REQUEST、TOOL_REJECTED、TOOL_RESULT、PARSE_RESULT、CONTINUATION_REQUEST、TIMEOUT 与 AGGREGATED。

### 15.7 short_term_ai_source_evidence

标准化引用：

- evidence_id，主键；
- round_id；
- role_run_id；
- event_id；
- source_type；
- source_name；
- title；
- url；
- canonical_domain；
- published_at；
- retrieved_at；
- excerpt；
- content_hash；
- point_in_time_eligible；
- rejection_reason；
- created_at。

证据不因角色失败而删除。重复 URL 与内容可按 round_id + content_hash 去重，但每个角色的引用关系仍通过事件或引用表可追踪。

## 16. 事务、一致性与恢复

### 16.1 创建

1. 确定性扫描完成并产生可持久化候选快照。
2. 在一个短事务内插入或读取股票 thread，插入 batch、round 和四个 QUEUED role run。
3. 提交事务。
4. 提交后唤醒执行器。
5. 唤醒失败不回滚数据库任务，恢复扫描器会重新发现它。

并发插入 thread、batch 或 round 必须依赖唯一约束和重复键后的重新读取，不使用 existsById 后 save 的竞态写法。

### 16.2 认领

执行器通过条件 UPDATE 从 QUEUED 或租约过期的 RUNNING 认领一行并生成 claimToken。只有持有当前 token 的实例可以写完成状态。心跳延长租约；完成、失败和超时都清除租约。

### 16.3 完成

每个模型/工具事件短事务追加。角色完成事务同时写 raw response、结构化意见和终态。聚合器在所有角色终态或批次截止时读取快照并原子更新 round 与 batch。

### 16.4 重启

- QUEUED：重新认领。
- RUNNING 且租约有效：不抢占。
- RUNNING 且租约过期、时间预算仍允许：认领并产生新的有记录尝试。
- 当日截止已过：保存已有片段并标记 TIMED_OUT/RESEARCH_ONLY。
- COMPLETED/PARTIAL/INCOMPLETE/FAILED：只读，不自动覆盖。

## 17. API 合同

建议统一前缀 /api/short-term/ai-discussions：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /batches/by-source?sourceType=&sourceKey= | 查询扫描对应批次和候选 AI 状态 |
| GET | /batches/{batchId} | 查询批次、轮次和四角色进度 |
| GET | /rounds/{roundId} | 获取完整讨论、事件、证据和父轮次差异 |
| GET | /stocks/{symbol}/history | 分页获取同股全部历史轮次 |
| POST | /stocks/{symbol}/continuations | 从指定父轮次继续讨论 |

### 17.1 读取

- 列表 API 返回状态和简短摘要，详情 API 返回完整长文本。
- 详情 API 不截断 rawResponseText、thesis、证据或事件。
- 历史分页按轮次数分页，不对每个轮次内容截断。
- 活跃任务响应包含 completedRoles、totalRoles、startedAt、deadlineAt 和 lastEventAt。
- 所有错误字段使用稳定 errorCode 与可读中文 message。

### 17.2 继续讨论

请求包含：

- parentRoundId；
- question，可空；
- idempotencyKey。

校验：

- symbol 与父轮次一致；
- 父轮次存在且已结束；
- 同一 idempotencyKey 不重复收费或生成轮次；
- 问题只作为研究补充，不能要求改变确定性动作；
- 服务端限制并发继续任务数量和调用频率，避免误点或接口滥用。

### 17.3 访问控制

AI 调用和联网搜索会产生费用。继续讨论与运行时模型配置写接口不能作为匿名公网写接口。实现前必须明确当前部署访问边界：

- 若系统已有用户/管理员认证，则仅管理员或授权研究用户可创建继续任务；
- 若当前单机版本没有认证，则这些写接口只允许来自受信反向代理的内部请求，并通过不可伪造的服务端边界验证；未建立该边界前不得暴露到公网；
- 只依赖 CORS、Referer 或前端隐藏按钮不算访问控制；
- 所有创建请求有幂等键、用户/来源审计和速率限制。

本次不要求重做全站权限系统，但发布验收必须证明付费写接口不对匿名公网开放。

## 18. React 前端

### 18.1 短线页

完全移除：

- PE 参考带；
- PB 参考带；
- PE TTM；
- PB；
- 估值参考；
- 估值语境；
- valuationScore；
- 所有短线说明、证据和风险里的 PE/PB 文案。

候选卡新增独立 AI 区域：

- 等待讨论；
- 4 个角色进行中；
- n/4 完成；
- 共识支持；
- 观点分歧；
- 风险警报；
- 内容不完整；
- 讨论失败；
- 仅供事后研究。

始终保留规则动作在主区域，AI 状态不取代动作标签。按钮文案为“查看 AI 讨论”。

### 18.2 讨论页

新路由：

    /short-term/discussions/:roundId

页面结构：

1. 股票、候选快照时间、规则动作与 AI 研究用途提示；
2. 聚合状态；
3. 四角色完整意见；
4. 模型请求与工具调用时间线；
5. 联网证据、出处、发布时间、抓取时间和点时资格；
6. Provider、Model、Prompt Version、Strategy Version；
7. 超时、解析失败、输出达到上限和续写记录；
8. 同股历史时间线；
9. 相对父轮次的变化；
10. “继续讨论”入口。

长内容默认折叠可以接受，但必须有“展开全部”，不能只渲染摘要。原始模型响应作为高级追溯区显示，并明确其未经确定性验证。

### 18.3 更新方式

沿用 React 当前轮询模式，不新增 SSE：

- 进入活跃讨论后定时查询 batch/round；
- 页面离开或终态后停止轮询；
- 完成、部分完成、超时和失败都在右上角显示浮动消息；
- 消息可点击进入讨论页；
- 轮询异常不清空最后一次成功数据。

## 19. 模型配置与密钥安全

- 每个 role run 启动时读取一次不可变 LlmSettings 快照，整次调用不因后台配置变化而混用 Provider、Model、Base URL 或 Key。
- 配置变化只影响新 role run。
- role run 保存 provider、model 和非密钥设置快照，不保存 API Key。
- API Key 环境变量只能从固定 allowlist 名称通过 System.getenv 解析，不能用用户可控名称查询整个 Spring Environment。
- 模型 Base URL 只能使用 Provider 对应的固定官方 HTTPS 地址，不能指向用户任意服务器。
- Prompt、事件、错误、HTTP 日志和数据库不得包含 LLM Key、Tavily Key、数据库密码或 Authorization Header。
- 工具结果中的网页文本必须与系统指令分区，提示模型不得执行其中的命令。
- 页面和 API 不能返回完整隐藏提示中的密钥或内部认证信息。

## 20. 失败语义

| 失败 | 处理 |
| --- | --- |
| 单角色模型超时 | 保存已有事件，角色 TIMED_OUT，聚合 PARTIAL/INCOMPLETE |
| 单角色 HTTP/供应商异常 | 保存错误码，其他角色继续 |
| 工具参数非法 | TOOL_REJECTED，模型可在剩余轮次修正 |
| 搜索未配置/超时 | SEARCH_UNAVAILABLE，角色使用本地证据并列出缺口 |
| 搜索结果越权域名或时间 | 保存 rejectionReason，不回传为可用证据 |
| 结构化解析失败 | 保存完整 raw response，角色 INCOMPLETE |
| finishReason=length | 保存片段；有时间则一次续写，否则 INCOMPLETE |
| 数据库短暂失败 | 有界重试；不重复模型收费调用；无法落库则角色失败并报警 |
| 执行器队列满 | 数据库标记 QUEUE_REJECTED，不阻塞扫描 |
| 应用重启 | 依据数据库状态和租约恢复 |
| AI 全部失败 | 确定性结果仍按原流程发布 |

“AI 全部失败”不能把 FINAL_READY 改成 FINAL_PENDING、DATA_BLOCKED 或无候选；“确定性数据失败”也不能被 AI 支持意见挽救。

## 21. 可观测性

至少记录不含敏感信息的指标：

- 批次/轮次/角色各状态数量；
- 队列深度与拒绝数；
- 角色模型耗时、搜索耗时、总耗时；
- 各 Provider/Model 错误码；
- finishReason=length 数量和续写成功率；
- 工具请求、拒绝、无结果和超时数量；
- 14:44:30 前讨论完成率；
- 确定性最终发布时间和 14:49:40 门禁结果；
- 重启恢复和租约抢占数量。

日志使用 batchId、roundId、roleRunId 和 correlationId 关联，不记录完整 Prompt、密钥或 Authorization。

## 22. 测试策略

### 22.1 短线去估值

- preliminaryScore 不调用估值计算器。
- PE/PB 极值、空值和异常值的变形测试。
- K 线复核池顺序与 PE/PB 无关。
- 候选排名、动作、理由与 PE/PB 无关。
- 当前版本 AI 快照与提示不含 PE/PB。
- React 短线配置与详情不显示相关字段或文案。
- 历史短线详情也不显示。
- 长期估值页面和服务回归通过。

### 22.2 讨论编排

- 四角色是四个并行调用，不是顺序调用。
- 扫描 API 在 AI 未完成时立即返回。
- 一个角色失败时其他角色和聚合正常。
- 四角色全失败时确定性结果不受影响。
- 队列满、重复触发和幂等键行为。
- 14:44:30、14:49:40 和 14:50 边界测试。
- 最终候选变化只触发事后补充，不延迟发布。

### 22.3 数据库与恢复

- H2 与 PostgreSQL schema 启动测试。
- thread/batch/round 唯一约束并发测试。
- 两个实例同时创建和认领时只有一个成功。
- claim 租约、心跳、过期抢占和旧 token 不能提交。
- 重启后 QUEUED/RUNNING 恢复。
- 完成历史不可覆盖。
- 父子轮次与跨交易日自动链接。
- 原始长文本往返不截断。

### 22.4 模型与搜索

- Tool call JSON 合法与非法参数。
- symbol 越权、任意 URL、未知域名、localhost、私网 IP、DNS rebinding 和重定向逃逸。
- 发布时间晚于 dataCutoffAt。
- 无发布时间证据不能用于点时执行讨论。
- 网页 Prompt Injection 文本不能改变系统工具权限。
- Tavily include_domains 与本地二次校验。
- 搜索密钥和 LLM 密钥不出现在响应、事件或日志。
- finishReason=length 保存、续写、合并与最终 INCOMPLETE。
- 不支持工具调用的模型返回明确 SEARCH_UNAVAILABLE 或 TOOL_UNSUPPORTED。

### 22.5 API 与 React

- 列表状态、详情完整内容、分页历史、父轮次变化。
- 活跃轮询停止条件和网络失败保留数据。
- 右上角完成/失败浮动消息。
- 继续讨论幂等、防连点和权限失败。
- 展开全部显示完整长文本。
- 规则动作与 AI 状态在视觉和语义上分离。

### 22.6 故障注入与现场验收

- 模型 90 秒超时。
- 搜索 429、500、连接超时和非法重定向。
- 一个/四个角色异常。
- 聚合时数据库短暂故障。
- 14:47 前后应用重启。
- 两个 API 实例竞争同一任务。
- 真实手动扫描产生候选，后台讨论完成并可进入详情。
- 同股继续讨论并在历史中形成父子关系。
- 容器重启后讨论与历史仍存在。
- 配置有效 Tavily Key 后完成一次真实可信域名搜索。
- 模型和搜索全异常时，确定性最终仍在 14:49:40 前发布或按原门禁失败关闭。

## 23. 验收标准

1. 改变 PE/PB 不会改变当前短线候选、复核池顺序、排名、得分、动作、理由或风险代码。
2. 短线页面及历史详情看不到 PE/PB 与估值语境；长期价投不受影响。
3. 成功扫描后 HTTP 请求不等待 AI，后台创建四个真实并行角色。
4. 用户可以查看四角色完整输出、原始响应、工具过程、引用、错误和模型版本。
5. 模型可以发起受控联网搜索，应用只返回可信域名且不晚于数据截止的证据。
6. 不存在任意 URL 抓取、内网访问、密钥泄漏或网页提示词越权。
7. 不对模型论述做业务层长度截断；供应商截断被识别、保存并按规则续写。
8. 任一或全部 AI 异常都不影响确定性 14:49:40 发布门禁。
9. 同一股票所有历史轮次可查，新轮次正确链接父轮次并显示观点变化。
10. 讨论记录在 H2/PostgreSQL 中持久化，容器重启后可追溯，运行任务按租约安全恢复。
11. 重复触发、误点和多实例竞争不会重复收费或生成重复轮次。
12. 付费写接口不向匿名公网开放。
13. 后端完整测试、React 测试与构建、旧 Vue 构建、schema 启动、容器健康和浏览器真实链路验收通过。
14. 最终对抗 Agent 未留下 Critical 或 Important 发现。

## 24. 多 Agent 实施与对抗复核协议

### 24.1 角色分工

主 Agent 只承担规格所有权、共享接口冻结、阶段集成、驳回裁决和最终验收。实现任务由每阶段新的开发 Agent 执行，审查 Agent 不得审查自己写的代码。

阶段划分：

| 阶段 | 开发责任 | 强制对抗重点 |
| --- | --- | --- |
| A | PE/PB 决策移除与 React 完全隐藏 | 寻找任何隐藏估值影响、兼容破坏和长期模块误伤 |
| B | 讨论领域模型、schema、Repository、原子认领与恢复 | 并发初始化、重复收费、租约竞态、重启数据完整性、H2/PostgreSQL 差异 |
| C | 四角色编排、LLM 长输出、工具调用、Tavily 与安全网关 | SSRF、DNS/重定向、Prompt Injection、点时越界、密钥外泄、工具死循环 |
| D | API、React 状态、完整讨论页、历史与继续讨论 | 内容截断、父链错误、轮询泄漏、规则动作与 AI 混淆、权限和幂等 |
| E | 调度集成、故障注入、容器与真实链路 | 14:44:30/14:49:40 竞态、AI 阻塞确定性发布、重启、多实例与现场可用性 |

共享 schema、DTO 和 API 合同先由主 Agent 冻结。两个 Agent 不得同时修改同一共享文件；只有真正独立且无共享状态的任务可以并行。

### 24.2 每阶段硬门禁

每阶段严格执行：

1. 开发 Agent 先写失败测试，再写最小实现，再跑定向和相关回归。
2. 主 Agent 记录阶段 base SHA 与 head SHA，生成独立审查包。
3. 规格审查 Agent 逐条核对本阶段合同和验收标准。
4. 对抗审查 Agent 主动构造失败场景，检查并发、安全、点时、兼容和不可见副作用。
5. 任一 Critical 或 Important 发现使阶段状态变为 REJECTED。
6. REJECTED 任务返回修复 Agent；必须增加能复现问题的失败测试，不能只改实现。
7. 修复后使用新的 head SHA 重新生成审查包并由独立 Agent 复审。
8. 只有两个审查结论均 PASS，且主 Agent 复跑验证通过，阶段才可进入 ACCEPTED。
9. 未 ACCEPTED 的阶段不得作为下一共享阶段的基线。

Minor 建议可以记录到清单，但若它影响验收、数据安全、投资边界或未来不可逆兼容，则升级为 Important。

### 24.3 五个强制红队检查点

1. 去估值红队：只改变 PE/PB、旧快照和兼容 DTO，尝试证明候选发生变化。
2. 数据红队：并发双实例启动、重复入队、租约失效、崩溃恢复和长文本往返。
3. 联网红队：任意 URL、私网、重定向、DNS rebinding、网页注入、截止后新闻和密钥诱导。
4. 追溯红队：不完整响应、父子轮次、全部历史、内容不截断、失败过程可见。
5. 发布红队：让模型和搜索同时超时、数据库短暂失败、14:47 重启，尝试阻断或污染确定性最终结果。

红队任务是尝试证伪验收标准，不是复述代码。没有可复现证据的笼统评价不作为放行依据。

### 24.4 持久进度账本

分支维护 .superpowers/sdd/progress.md，记录：

- 阶段和任务编号；
- 开发 Agent；
- base/head SHA；
- 测试命令与结果；
- 审查 Agent；
- Critical/Important/Minor 发现；
- 驳回、修复和复审次数；
- 当前门禁状态；
- 下一步唯一入口。

每个 Agent 开工前必须读取规格、实施计划和账本。主 Agent 在每次阶段切换后提交账本，避免长任务压缩或重启后跑偏。

### 24.5 最终放行

所有阶段完成后，使用未参与开发的最强审查 Agent 对整个分支做一次全量规格与代码审查。主 Agent随后亲自执行：

- 后端完整测试；
- React 完整测试与生产构建；
- 旧 Vue 构建；
- H2/PostgreSQL schema 验证；
- 容器重建和健康检查；
- 浏览器真实手动扫描、AI 讨论、联网证据、历史、继续讨论和重启恢复；
- 故障注入下的 14:49:40 确定性发布验证。

只有全部通过且无 Critical/Important 发现，才可合并和推送。任何阶段反复失败都继续驳回修复，除非出现必须由用户决定的真实产品歧义或外部凭据阻塞。

## 25. 实施前仍需满足的依赖

- 用户书面确认本文。
- 书面实施计划列出具体文件、测试、阶段 SHA 和 Agent 门禁。
- 发布环境提供受控搜索所需的 TAVILY_API_KEY；没有密钥时不得伪称实时联网已验收。
- 明确当前部署中付费写接口的认证或受信代理边界。
- 现有主分支基线测试通过；若存在基线失败，先记录并与本功能失败区分。

