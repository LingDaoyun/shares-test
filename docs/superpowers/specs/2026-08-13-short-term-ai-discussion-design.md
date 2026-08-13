# 短线去估值、多角色 AI 讨论与结果视图设计

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

当前短线结果视图默认 fundFlowVisible=false、snapshotVisible=true，因此“今日资金去向”首次打开不展示，而“扫描快照”占用一个摘要卡和一个偏好开关。后台 scheduled snapshot 同时承担 FINAL_PENDING/FINAL_READY、覆盖率门禁和后续 AI source，不能随前端卡片一起删除。

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
4. 每个候选批次在后台只启动四个真正独立的 AI 角色会话；每个角色一次分析整批最多八只候选，并分别产出每只股票的意见。工具回传和输出续写可以形成有记录的后续模型请求，但不能把八只候选放大成 32 个初始角色调用。
5. AI 讨论只提供研究意见，不能增删候选、改变排序、改变动作、绕过硬门禁或拖延最终发布。
6. 模型可自行决定是否调用受控联网搜索与历史讨论搜索工具；应用负责实际执行工具。
7. 讨论的原始响应、结构化意见、工具调用、证据和错误全过程落库。
8. 同一股票的所有历史讨论可查看，新讨论必须指向父讨论并说明观点变化。
9. 不设置业务层文字数、段落数或结论长度上限；用时间预算、工具轮数和异常续写保障完整性。
10. 实施采用多 Agent 分阶段开发和独立对抗复核；发现 Critical 或 Important 问题即驳回，任何代码变更都会使旧审查结论失效，修复并重新完成两类复审后才能进入下一阶段。
11. AI 讨论全链路完成后，短线结果视图默认展示“今日资金去向”，移除“扫描快照”结果卡及其显示开关；后台持久扫描快照仍保留为确定性发布、审计和 AI 触发源。

本规格中“多角色讨论”的产品定义是四名角色对同一批快照并行独立审阅、页面并列呈现分歧，再由确定性真值表聚合；首版不让模型角色互相自由追问或多轮辩论。这样既保留多维视角和全过程，也把时延、费用与截止风险固定住。

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
- 每个批次只有四个初始角色会话，避免候选数线性放大初始模型调用数量。

### 4.3 时间目标

- 预选完成后立即排队，四角色并行。
- 单角色批次会话目标完成时间 60 秒，普通硬超时 90 秒。
- 调度批次在 14:44:30 后不再等待未完成角色；届时形成部分结论或超时记录。
- 14:47 的确定性尾盘任务不等待 AI。
- 无论模型、搜索或数据库讨论任务是否异常，确定性最终结果仍必须按既有两阶段认证在 14:49:40 前完成可执行发布，否则按既有规则失败关闭。

### 4.4 结果视图目标

- “今日资金去向”对新用户默认可见。
- 现有浏览器从旧偏好版本迁移时只在迁移当次把“今日资金去向”设为可见，之后仍允许用户主动关闭。
- “扫描快照”不再出现在结果视图开关或结果摘要卡中。
- 不删除 short_term_scheduled_snapshot、latest snapshot API、覆盖率门禁或供 AI materializer 使用的 source；“剔除”只针对冗余前端结果模块。

## 5. 非目标

- 不让 AI 直接荐股、扩大候选池或调整确定性排名。
- 不让 AI 生成或覆盖买入、加仓、卖出动作。
- 不把论坛、股吧、自媒体、匿名聚合站或未知域名作为证据。
- 不允许模型请求任意 URL、内网地址、文件地址或本机服务。
- 不建设通用聊天系统、向量数据库、知识图谱或长时间辩论室。
- 不把讨论历史塞进现有内存扫描任务。
- 不修改长期价投模块中的 PE/PB、估值纪律和估值页面。
- 不移除长期价投页面独立的“扫描快照”卡；本次新增要求只针对短线结果视图。
- 不保证外部模型永不达到其物理上下文或输出上限；系统负责识别并续写，而不是把截断内容伪装成完整结论。
- 不承诺第三方模型调用的 exactly-once。供应商不支持幂等键时采用 at-most-once：请求发出后结果未知就停止自动重试并公开标记，避免静默重复收费。

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
- 新策略唯一权威版本常量固定为 short-term-emotion-v5。ShortTermWeightProfile、调度快照、参数指纹、ResearchHistory、Observation、TradeFeedback、RecommendationSource 和 AI batch 都引用同一常量；新记录不得继续硬编码 V4。
- ShortTermWeightProfile 保持向后反序列化能力，旧 V4 标识只用于旧记录。
- 历史 V4 决策原始数据不迁移、不重算、不覆盖。
- 所有短线详情，包括历史详情，统一隐藏 PE/PB、估值参考、估值语境和 valuationScore。
- 长期模块及其 DTO、页面、测试不做机械删除。
- 当前策略参数指纹先规范化请求，再计算 hash；deprecated 的 maxPe 与 maxPb 不进入指纹、snapshotKey、publicationKey 或 AI sourceKey。只改变它们不能创建新扫描身份或重复讨论。
- ShortTermPage 完全停止调用现有 /api/v2/signals/strategy-bundle。该端点会同时计算并落账长期信号，focus=short 只是前端显示参数，不能作为短线隔离。短线页直接使用当前候选的确定性动作和交易计划；长期入口继续使用原策略束合同。

### 7.4 反隐藏影响证明

必须增加变形测试：固定除 PE/PB 外的所有输入，分别使用极低、极高、空值和异常 PE/PB，断言当前策略版本的下列字段全部一致：

- 候选集合、复核顺序、排名、setupScore、rankingScore、规则动作、理由和风险代码；
- todayAdvice.action、confidence、summary、reasons 和 riskControls；
- evidenceCompleteness.score、status、allowsBuy、presentEvidence 与 missingEvidence；
- tradePlan 是否可执行及其入场、退出和有效期；
- validationEligible、calibrationEligible、观察样本和历史载荷；
- 规范化参数指纹、snapshotKey、publicationKey 与 AI sourceKey。

EvidenceCompletenessService 必须提供短线专用完整度合同，短线分支不再把估值作为关键证据；长期完整度逻辑保持原样。

短线兼容 DTO、旧 JSON 反序列化和原始行情映射允许保留 PE/PB 名称，形成显式文件白名单。确定性当前路径、ShortTermPage、AI 快照和 Prompt 使用依赖交互测试与变形测试证明不读取这些字段，不能只依靠宽泛 grep。

还必须断言打开短线候选详情前后，长期 V2 recommendation ledger 行数保持不变；长期页面的估值输入、信号和 ledger 回归保持不变。

## 8. AI 讨论触发、批次和状态机

### 8.1 持久化触发源

讨论批次支持：

- SCHEDULED_PRESELECT：14:30 预选快照成功提交后触发。
- MANUAL_SCAN：手动扫描的持久化结果成功提交后触发；扫描 HTTP 请求不等待 AI。
- FINAL_SUPPLEMENT：最终快照成功提交后比较 AI 快照 hash，对新增或实质变化的候选形成一个补充批次，只供研究。
- CONTINUATION：用户从一个已结束父轮次继续讨论，形成只含该股票的新批次。

扫描结果与 AI materialization 之间使用“持久源 + after-commit materializer”：

1. 自动预选先独立提交现有 scheduled snapshot。
2. 手动扫描在接受 HTTP 请求时先提交持久化 PENDING manual result，再由 worker 原子改为 RUNNING/SUCCEEDED/FAILED；只有 SUCCEEDED 提交后才触发 AI。内存 job 只作为缓存，重启后由持久任务恢复，不能成为唯一事实源。
3. after-commit 信号尝试创建 AI batch；其异常不得回滚或改写确定性结果。
4. reconciler 定期扫描“已成功持久化但尚无 batch”的 source identity，补建缺失批次，因此进程在 source commit 后崩溃不会永久漏讨论。
5. AI 表、队列或 materializer 不可用时，确定性结果照常成功；前端根据 admissionStatus/reasonCode 显示 AI_MATERIALIZATION_PENDING 或 AI_UNAVAILABLE，不伪称已入队。

数据质量阻断、扫描失败或候选为空时，source 记录 admissionStatus=NOT_APPLICABLE，并保存原因，不创建模型任务。

### 8.2 正交状态字段

RESEARCH_ONLY 不是执行状态。状态拆分如下。

Batch executionStatus：

- QUEUED
- RUNNING
- COMPLETED
- PARTIAL
- INCOMPLETE
- TIMED_OUT
- FAILED
- CANCELLED

Round executionStatus：

- PENDING
- RUNNING
- COMPLETED
- PARTIAL
- INCOMPLETE
- TIMED_OUT
- FAILED
- CANCELLED

Role session status：

- QUEUED
- CLAIMED
- RUNNING
- COMPLETED
- INCOMPLETE
- TIMED_OUT
- FAILED
- OUTCOME_UNKNOWN
- CANCELLED

Source admissionStatus：

- MATERIALIZATION_PENDING
- MATERIALIZED
- NOT_APPLICABLE
- FEATURE_DISABLED
- BUDGET_EXHAUSTED
- STORAGE_PRESSURE
- MATERIALIZATION_FAILED

advisoryUse：

- SAME_DAY_RESEARCH
- RESEARCH_ONLY

queueAdmission：

- ACCEPTED
- DEFERRED

执行器队列满不产生不存在的 QUEUE_REJECTED 终态。数据库 batch 保持 QUEUED，batch.queueAdmission=DEFERRED，稍后由 dispatcher 重试。前端在 batch/round/role 到达上述任一终态后停止相应轮询；FEATURE_DISABLED、BUDGET_EXHAUSTED、STORAGE_PRESSURE、NOT_APPLICABLE 与 MATERIALIZATION_FAILED 也是 source 终态。

状态转换合同：

| 实体 | 合法主转换 |
| --- | --- |
| Source | MATERIALIZATION_PENDING -> MATERIALIZED；或到 NOT_APPLICABLE/FEATURE_DISABLED/BUDGET_EXHAUSTED/STORAGE_PRESSURE/MATERIALIZATION_FAILED |
| Batch | QUEUED -> RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Round | PENDING -> RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Role | QUEUED -> CLAIMED -> RUNNING -> COMPLETED/INCOMPLETE/TIMED_OUT/FAILED/OUTCOME_UNKNOWN/CANCELLED |

终态不可自动回到运行态。OUTCOME_UNKNOWN 只能由用户显式发起“继续讨论”创建新的 batch，原 role session 和原事件保持不可变。

executionStatus 的派生也固定，避免各端自行解释：

- Role 正常得到 batch 中每个 symbol 的可解析结构化结果才是 COMPLETED；已有原文但结构化不全为 INCOMPLETE；硬截止为 TIMED_OUT；发送结果未知为 OUTCOME_UNKNOWN；发送前不可恢复错误为 FAILED。
- Round 在任一 role 非终态时为 RUNNING；四份 opinion 均完整有效为 COMPLETED；至少一份有效但存在缺项、失败或超时为 PARTIAL；没有有效 opinion 但留有原文或可解释失败为 INCOMPLETE；四个 role 全部在产生内容前超时为 TIMED_OUT；四个 role 全部在发送前失败且没有原文为 FAILED。
- Batch 在任一 role 非终态时为 QUEUED/RUNNING；全部 round COMPLETED 才是 COMPLETED；存在 COMPLETED/PARTIAL round 且非全完成为 PARTIAL；没有可用 round 但有原文或结构化缺口为 INCOMPLETE；所有 round 均 TIMED_OUT 才是 TIMED_OUT；所有 round 均 FAILED 才是 FAILED。CANCELLED 只允许显式运维终止未开始任务。
- advisoryState 始终按第 10 节逐股票计算，与 executionStatus 正交；例如 executionStatus=PARTIAL 的 round 仍可能因合格反方证据得到 RISK_ALERT。

### 8.3 队列、角色会话与执行器

- 一个有界 dispatcher 负责 materialization 和 batch 编排。
- 每个 batch 固定创建四个 role session；四个初始 session 并行运行，每个 session 一次分析 batch 内最多八只候选。
- 每个 role session 的结构化输出必须包含 batch 中每个 symbol 的独立 opinion；缺失 symbol 记为该候选的 INSUFFICIENT，不补开第五个初始会话。
- 模型网络调用期间不持有数据库锁或事务。
- 每个 role session 有 claimOwner、claimToken、单调 claimEpoch、leaseUntil 和 heartbeatAt。
- 认领后的心跳、普通事件、非迟到 chunk、evidence、opinion 和终态写入都必须以 roleRunId + claimToken + claimEpoch + RUNNING 作为 fencing 条件。旧租约迟到写入受影响行数必须为零。
- deadline coordinator 终止 role 后，只允许同一未被取代 claimEpoch 的已存在 invocation 通过独立审计路径追加 late=true chunk/event；它不能写 evidence/opinion、改变 role/round 状态或进入聚合。claimEpoch 已变化时连审计片段也拒绝，且 14:47 至 14:50 的 AI 持久化总闸规则优先。
- 租约、deadline 和迟到判定统一使用数据库 CURRENT_TIMESTAMP；应用时钟只用于展示和可注入测试。
- H2 文件模式只支持一个 API 实例；多实例运行必须使用 PostgreSQL。跨实例 CAS、数据库时间和租约只在真实 PostgreSQL 上宣称成立。
- AI 持久化通过独立有界 executor 和数据库 semaphore，最多同时两个短事务；外部网络等待不占数据库连接。14:44:30 至 14:50:00 dispatcher 停止新的模型、搜索和非必要 AI 写入，把核心发布链资源置于最高优先级。

### 8.4 付费调用 at-most-once

每次物理模型请求在发送前创建不可变 invocation：

1. 事务写入 PREPARED，包含稳定 invocationId、roleRunId、invocationNo、callKind、requestHash 和 provider 设置快照。
2. 事务把 PREPARED 改为 DISPATCHED 后才允许网络发送。
3. 收到响应后写 RESPONSE_RECEIVED，并流式/分块保存内容，最终转为 PERSISTED。
4. 供应商支持幂等键时使用稳定 invocationId，允许以同一键恢复。
5. 供应商不支持幂等键时，只在能证明请求尚未发送的 PREPARED 状态自动恢复；DISPATCHED 后进程崩溃或结果无法确认时标记 OUTCOME_UNKNOWN，绝不自动重发。
6. 本版不在原 role session 上提供重试。用户只能通过“继续讨论”创建新的 batch 和四个新 role session，原 OUTCOME_UNKNOWN 记录保持不可变并明确提示会产生新费用。

callKind 为 INITIAL、TOOL_CONTINUATION 或 OUTPUT_CONTINUATION。invocationNo、toolRoundNo 与 outputSegmentNo 分开存储，不能复用 attempt_no 表示多个概念。现有 LlmChatClient 的 IOException 自动重试逻辑不得直接复用于本功能。

## 9. 四个独立角色与输出合同

四个角色是四个独立的批次级会话，而不是一个提示中模拟四种口吻：

| 角色代码 | 关注范围 |
| --- | --- |
| SENTIMENT_THEME | 市场情绪、板块热度、题材持续性、同题材竞争与退潮风险 |
| PRICE_VOLUME | 价量结构、资金方向、尾盘承接、涨幅位置、次日流动性 |
| OVERNIGHT_CATALYST | 隔夜催化、次日溢价兑现路径、公告与政策时效 |
| RISK_CONTRARIAN | 反方审查、拥挤交易、利好兑现、公告风险、次日低开与止损难度 |

四个 session 收到相同的 AiCandidateBatchSnapshotV1、行情快照截止时间和父轮次引用，角色系统提示不同。任何角色不得看到 PE/PB、估值字段或密钥。

AiCandidateBatchSnapshotV1 固定包含：

- schemaVersion、sourceType、sourceKey、strategyVersion、dataCutoffAt；
- marketRegime、marketCoverage、quoteFreshness；
- 最多八个 AiCandidateSnapshotV1。

AiCandidateSnapshotV1 固定包含 symbol、name、industry、ruleRank、ruleAction、setupScore、rankingScore、latestPrice、changePercent、amount、volumeRatio、hotDirection、relativeStrength、technicalStructure、tailSignal、financialHardRiskFlags、ruleEvidenceIds、snapshotHash 和 discussionMaterialHash。snapshotHash 覆盖完整白名单快照及 dataCutoffAt；discussionMaterialHash 只覆盖会实质改变讨论的规范化规则/价量/题材字段，排除纯元数据时间。两种 hash 都明确排除 maxPe、maxPb、peTtm、pbRatio、valuationContext、valuationScore、API Key 和任意完整 ShortTermCandidate 序列化。

每个角色返回 opinions 数组，每只候选一项：

- symbol；
- stance：SUPPORT、WATCH、OPPOSE 或 INSUFFICIENT；
- confidence：0 至 100，仅表示主观研究把握，不是收益概率；
- thesis；
- supportingEvidence；
- negativeEvidence；
- nextDayDrivers；
- invalidationConditions；
- evidenceGaps；
- citationIds；
- severeRisks；
- dataCutoffAt，表示确定性候选行情快照截止；
- evidenceCutoffAt，表示该意见实际采用的最新工具观察截止；
- priorRoundDelta；
- priorRoundDeltaReasons。

severeRisks 每项包含 riskCode、severity、claim 与 evidenceIds。riskCode 只允许 REGULATORY_DISCLOSURE、REDUCTION_OR_PLEDGE、MATERIAL_LITIGATION、SUSPENSION_OR_DELISTING、CATALYST_ALREADY_PRICED、LIQUIDITY_EXIT_RISK 和 OTHER；severity 为 LOW、MEDIUM、HIGH。

citationIds 和 severeRisks.evidenceIds 只能引用服务器在同一 batch、同一 symbol 中预先持久化的确定性证据，或该 role session 工具调用新持久化的 evidenceId。其他角色私有证据、跨股票或截止后证据会被移除并记录 VALIDATION_REJECTED。没有合格证据的 HIGH 风险降为未验证 evidenceGap，不能触发聚合风险警报。

结构化字段与完整流式原始响应同时保存。解析失败时保留所有已接收 chunk、错误和 finishReason，role session 标记 INCOMPLETE。

## 10. 确定性聚合器

不增加第五个模型主席。聚合器只读取通过服务器校验的 opinion 字段，不解析自由文本推断风险。

advisoryState：

- PENDING
- RISK_ALERT
- INCOMPLETE
- CONSENSUS_SUPPORT
- DIVERGENT

固定优先级和真值表：

1. batch 尚未终止且任一 role session 仍非终态：PENDING。
2. 截止或所有角色终止后，若存在 RISK_CONTRARIAN 的 OPPOSE、confidence >= 75，且至少一个 HIGH severeRisk 绑定同股 pointInTimeEligible 证据：RISK_ALERT。
3. 否则，完整且通过校验的 opinion 少于三个：INCOMPLETE。
4. 否则，SUPPORT 数量 >= 3，且不存在 confidence >= 75 的 OPPOSE：CONSENSUS_SUPPORT。
5. 其他所有完整组合：DIVERGENT。

一个角色对某只股票缺项只影响该股票的 opinion 完整数，不影响同 batch 其他股票。聚合结果不改变候选、排序、得分或交易动作；页面必须把“规则动作”和“AI 研究意见”分开。

## 11. 模型发起的受控联网搜索

### 11.1 工具调用事实边界

模型只会生成工具名和参数，应用必须校验参数、执行外部搜索，再把结果作为 tool 消息返回模型。不能把“模型支持 function calling”描述成模型自身可以不经应用访问互联网。

模型客户端使用原生 tools/tool_calls 合同；DeepSeek thinking 模式的后续工具轮必须完整回传上一轮 reasoning_content 和 tool_call_id。工具参数永远重新做服务器校验，不能因为 strict schema 就跳过。

首期提供两个工具：

1. search_trusted_stock_evidence
2. search_discussion_history

### 11.2 search_trusted_stock_evidence 合同

模型可以选择：

- symbol：只能是当前 batch 中的候选股票代码；
- query：搜索问题；
- sourceTypes：DISCLOSURE、EXCHANGE、COMPANY_IR、GOVERNMENT、FINANCIAL_MEDIA；
- publishedFrom 与 publishedTo；
- intent：CATALYST、RISK、THEME、FLOW_CONTEXT 或 FACT_CHECK。

模型不能提供 URL、域名、IP、请求头、HTTP 方法、端口或认证信息。

应用侧 TrustedEvidenceToolGateway：

1. 校验 JSON Schema 和枚举；
2. 强制 symbol 属于当前 AiCandidateBatchSnapshotV1；
3. 工具开始时读取数据库时间形成 immutable observedCutoffAt；将 publishedTo 截断到 observedCutoffAt，SAME_DAY_RESEARCH 还必须早于 sessionDeadline=14:44:30；
4. 使用服务器维护的可信域名集合，而不是模型提供的域名；
5. 调用直接 source adapter 或 Tavily discovery provider；
6. 规范化返回 URL，验证 HTTPS、无 userinfo、无 IP literal、精确 host 边界和 source type；
7. 丢弃截止时间后或无法证明在截止前公开的同日证据；
8. 服务器分配 evidenceId 后先持久化，再把带类型的 tool data 回传模型；
9. 将网页文本和历史模型文本视为不可信数据，不能改变工具、域名、预算、权限或系统指令；
10. 记录工具请求、校验结果、搜索耗时、供应商 requestId/credits、每条证据和失败原因。

### 11.3 搜索来源

可信来源由版本化 TrustedSourceRegistryV1 管理。每项包含 sourceType、exactHost、allowSubdomains、validFrom、validTo、owner 和 pointInTimePolicy。host 规范化后只允许：

- host == exactHost；或
- allowSubdomains=true 且 host 以 "." + exactHost 为完整标签后缀。

不能使用裸 endsWith。sourceTypes 只能收窄 registry，不能扩大。首批包括：

- 巨潮资讯；
- 上海证券交易所与深圳证券交易所；
- 已建立 symbol -> exactHost 映射的上市公司官网和投资者关系页面；
- 国务院、部委、省市政府与依法公开的政策网站；
- 财联社、证券时报、上海证券报、中国证券报等主流财经媒体。

不允许：

- 论坛、股吧、自媒体、无来源转载；
- 用户或模型临时添加的域名；
- localhost、私网、链路本地、保留地址和非 HTTP(S) 协议；
- 未经 registry 审批的公司官网、媒体子站或用户内容域；
- 把网页里的提示词、脚本或操作指令当成系统指令。

### 11.4 实际搜索适配器

第一版使用三层来源：

- DirectDisclosureAdapter：复用并加固 CninfoClient，只访问固定 www.cninfo.com.cn 与 static.cninfo.com.cn，保存权威公告发布时间；
- DirectPolicyAdapter：只复用与 TrustedSourceRegistryV1 exactHost 相交的政府政策源，并使用与公告附件同等级的受限 HTTPS fetcher；只有来源自身给出的权威发布时间不晚于该次工具 observedCutoffAt 时才可标记点时合格。retrievedAt 必然可能晚于行情快照截止，只用于审计，不能被误作发布时间。运行时配置里任意新增的 URL 不能自动获得 AI 联网权限；
- TavilySearchAdapter：用固定 https://api.tavily.com/search 做发现和新闻摘要，不直接抓取结果 URL。

历史讨论和已经持久化的证据由 search_discussion_history 与本地 evidence repository 提供，不伪装成新的互联网事实。

Tavily API Key 只允许从固定操作系统环境变量 TAVILY_API_KEY 读取，不能通过模型、数据库任意字段、Spring Environment 任意属性名或 API 请求选择。搜索端点固定，不允许在管理页面修改。

Tavily 请求固定 search_depth=basic、auto_parameters=false、max_results=8、include_answer=false、include_raw_content=false、include_usage=true，并显式传 include_domains、start_date/end_date 与 topic。第三方过滤不替代本地 registry 校验。

本版本绝不从 API 服务器对 Tavily 返回 URL 发起 HEAD、GET 或重定向探测，因此不会声称复验其重定向链或 DNS。只保存 provider snippet 和经过规范化的链接。未来若需要正文抓取，必须另行设计隔离 egress fetcher。

Tavily 的日期过滤只有自然日粒度。只有 topic=news、返回精确可解析 publishedAt、且 publishedAt <= 该次工具 observedCutoffAt 的结果，才可能成为 SAME_DAY_RESEARCH 的 pointInTimeEligible 证据；该工具还必须在 14:44:30 前开始并通过本地 registry。general/finance、时间缺失、政府页或公司 IR 的 Tavily 结果只能 RESEARCH_ONLY，不影响同日 advisoryState。公告、交易所与政策的同日点时证据必须来自 direct adapter 的权威时间。

每条 evidence 额外保存 providerPublishedAtRaw、observedCutoffAt、firstSeenAt、contentVersion、contentHash、eligibilityReason 和 advisoryUse。搜索不可用时工具返回 SEARCH_UNAVAILABLE，角色继续使用本地合格证据并公开 evidenceGaps。

DirectDisclosureAdapter 获取公告附件时使用专用受限 fetcher：仅 HTTPS/443、固定 host、禁止 userinfo/IP literal、IDNA 规范化、逐跳手动重定向且每跳重验 A/AAAA、阻断私网/链路本地/保留地址和 IPv4-mapped IPv6、连接期 DNS pin、流式 MIME/字节/时间限制。上游给出的任意绝对 URL不能直接交给通用 HttpClient。

COMPANY_IR 在没有 symbol-specific registry 记录时返回 SOURCE_UNAVAILABLE，不从模型名称猜测官网。

部署验收必须配置有效的 TAVILY_API_KEY 并完成一次可信域名实时搜索；若密钥未配置，代码功能可以通过 fake adapter 验证，但不能宣称“联网搜索已在当前运行环境可用”。

### 11.5 搜索预算

- 每个 role session 最多两个外部 evidence tool call，整个 batch 最多八个外部 evidence tool call、八次 Tavily 请求和十六次受限 direct fetch。
- 每个 role session 最多一次历史讨论搜索。
- 每个 role session 最多四个物理模型 invocation：INITIAL、最多两个 TOOL_CONTINUATION、最多一个 OUTPUT_CONTINUATION。最多只有两个工具轮；同一轮可返回多个原生 tool_calls，但每个 call 分别扣 evidence/history 预算，第三个工具轮一律拒绝。达到任一预算立即记录稳定 BUDGET_EXHAUSTED 事件，并以 Role status=INCOMPLETE、errorCode=BUDGET_EXHAUSTED 收口；BUDGET_EXHAUSTED 不是新增的 Role 状态。
- 每轮最多返回八条结果。
- 采用快速或基础搜索深度，不请求第三方生成答案，不抓取任意完整网页。
- 搜索 HTTP 超时包含在单角色 90 秒预算内。
- 搜索轮数和结果数用于控制时延、攻击面和成本，不是对模型最终论述长度的截断。
- batch、localOperator、Asia/Shanghai 自然日三层预算持久化到数据库，默认每批最多 16 个模型 invocation、8 个 evidence tool call、8 个 Tavily 请求、16 个 direct fetch；每日本地手动批次最多 20、模型 invocation 最多 400、evidence tool call 最多 160、Tavily 请求最多 160、direct fetch 最多 320。预算可从服务器配置文件收紧，不能由模型或浏览器扩大。

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
- limit；
- cursor，可空，由服务器上一次返回。

应用强制 symbol 属于当前 batch，返回命中轮次的标识、日期、角色、观点摘要、完整内容引用标识和当时证据。直接父轮次预载结构化结论、变化字段、证据 ID 和原文引用，不把可能达到数十 MiB 的完整 raw output 无条件塞入模型上下文；更早内容通过工具按需检索。

历史工具单次返回最多 20 个命中、编码后最多 128 KiB，并返回 hasMore/cursor/contentRef。ContextAssembler 根据版本化 Provider 输入上下文能力按“当前候选快照 -> 当前规则证据 -> 直接父轮结构化结论 -> 工具证据 -> 历史引用”固定优先级装配；不得在 UTF-8 字符或 JSON item 中间静默截断。被物理上下文上限排除的引用、hash、原因和 token 估算写入 contextManifest 事件，模型与页面都能看到 evidenceGap。该输入边界不限制模型新输出在落库和页面中的完整保存。

历史讨论属于研究证据，不能被描述为市场事实。模型必须区分“过去角色观点”与“公开来源事实”。

## 13. 不截断输出

本功能不设置业务层字符数、段落数、观点数或前 N 条论述上限：

- canonical DDL 的数据库长文本统一使用 H2/PostgreSQL 都支持的 TEXT，并用 JPA 长文本映射。
- 模型讨论使用供应商流式响应；chunk 按序号增量落库，超时或取消时保留已经接收的全部片段。
- raw output chunk、thesis、证据、反证、失效条件和变化说明完整保存。
- API 不对子字段 substring，不追加省略号，不只返回摘要替代详情。
- UI 默认可折叠以保持可读性，但“展开全部”必须遍历有界分页直至真实结尾并显示完整内容。
- 讨论调用使用版本化 LlmProviderCapabilities 的 provider/model 最大输出能力；供应商允许省略时省略 max_tokens，必须设置时使用经验证最大值。现有通用配置中的较小展示/旧默认值（例如 8192）不作为本讨论的业务截断值；effective provider limit 进入 role settings 快照并在页面可追溯。

供应商物理上限和时间截止仍然存在：

- finishReason=length 时保存当前完整原始片段并标记 INCOMPLETE。
- 时间预算允许时最多发起一次同角色续写，携带上一片段和结构化缺失字段。
- 续写内容单独作为事件保存，合并时保留片段边界。
- 仍未完成时展示“模型输出达到上限，内容不完整”，不得伪装为 COMPLETED。
- 90 秒或 14:44:30 截止优先于继续等待；超时不删除已产生内容。

“不做业务截断”不等于无限内存或无限请求。运输安全边界固定为：

- continuation question 最多 8,000 个 Unicode 字符；
- 工具 query 最多 512 个字符，JSON 嵌套深度最多 32；
- 单个供应商 HTTP 响应最多接收 32 MiB，单 batch 原始模型内容最多 256 MiB；
- 持久化时按 UTF-8 代码点边界切成不超过 64 KiB 的 chunk，绝不切坏字符；
- 达到运输上限时停止接收，保留此前所有 chunk，并以 Role status=INCOMPLETE、errorCode=INCOMPLETE_RESOURCE_LIMIT 收口，不做 substring 后伪装完整；
- 新客户端不能使用 BodyHandlers.ofString 先把不受控响应全部读入内存；
- 输入/运输限制保护资源，不限制已经合法接收的研究论述在数据库和详情页中的显示。

原始历史不自动删除。达到配置的 AI 存储水位、数据库连续写失败或队列压力阈值时，熔断新 AI batch，source 标记 FEATURE_DISABLED 或 STORAGE_PRESSURE；确定性扫描继续，已有历史仍可读取。

## 14. 点时与发布时间

### 14.1 调度讨论

所有截止状态转换使用数据库时间，时区固定 Asia/Shanghai。真值表：

| 数据库时间区间 | AI 行为 | 确定性行为 |
| --- | --- | --- |
| [14:30:00, 14:44:30) | 预选 source materialize；四角色批次会话运行 | 预选按原规则可用 |
| [14:44:30, 14:47:00) | 原子终止仍未完成的 SAME_DAY_RESEARCH 会话；关闭 socket；落完截止前已缓冲 chunk；状态 PARTIAL/INCOMPLETE/TIMED_OUT；不创建新讨论 | 不受 AI 影响 |
| [14:47:00, 14:49:40] | 不启动模型、搜索、materializer 或 AI 内容写入；手动扫描只提交确定性 manual result，讨论状态由“成功 source 尚无 admission”派生为 MATERIALIZATION_PENDING | 最终任务独立运行；认证时间精确等于 14:49:40 仍按既有 <= 合同允许 |
| (14:49:40, 14:50:00) | 仍不启动 AI | 新的可执行最终认证失败关闭 |
| [14:50:00, 次日) | 延后任务可恢复，但 advisoryUse=RESEARCH_ONLY | 不得创建或回写当日可执行买点 |

14:47:00 前到达的旧网络响应只能以 late=true 追加到对应 invocation 审计流；claim/deadline fence 禁止它写 opinion、改变 round 终态或升级同日 advisoryState。14:47:00 到 14:50:00 的 AI 持久化总闸关闭，迟到字节只可进入有界内存审计缓冲；14:50:00 后若进程仍存活且 claimEpoch 未被取代才可补写 late=true，进程重启丢失该迟到缓冲是允许的并记指标。14:50:00 本身已是研究时段。

本功能不扩大“应用完全离线时仍必须发布”的承诺。若 API 在调度时间不可用，既有确定性 fail-closed 语义保持；自动补跑 cron 属于独立调度可靠性课题。故障注入只证明 AI 线程、AI 数据库写入或 AI 恢复不会阻塞一个正在运行的确定性发布进程。

### 14.2 手动讨论

手动扫描可在任意允许的研究时段触发。其 AI 任务仍使用单角色批次会话 90 秒预算；14:44:30 至 14:50:00 只提交确定性 manual result，不创建新的 admission/batch，待 14:50:00 后由 reconciler 创建讨论并标 advisoryUse=RESEARCH_ONLY。只有扫描本身满足当日点时与执行门禁时，页面才可并列展示规则动作；AI 本身从不让研究预览变成可执行结果。

### 14.3 最终候选变化

预选与最终候选按 AiCandidateSnapshotV1.discussionMaterialHash 比较，生成不可变 transition；完整 snapshotHash 只用于证明当时看到的精确快照，不能因为 dataCutoffAt 自然变化就把全部 retained 候选误判为变化：

- ADDED：最终新增，进入一个 FINAL_SUPPLEMENT batch。
- RETAINED_UNCHANGED：discussionMaterialHash 一致，可关联预选讨论并显示原快照时间与新旧 snapshotHash。
- RETAINED_CHANGED：rank、ruleAction、setup/ranking score、tailSignal、technicalStructure、marketRegime、规则证据或 dataCutoff 任一规范化字段变化，进入同一个 FINAL_SUPPLEMENT batch。
- DROPPED：最终被淘汰，不再显示为最终候选；预选历史标注“已退出最终候选”并保存确定性淘汰原因。

补充批次对所有 ADDED/RETAINED_CHANGED 候选仍只有四个批次级 role session，advisoryUse=RESEARCH_ONLY。最终页面只有 snapshotHash 精确匹配时才称“当前快照讨论”；RETAINED_UNCHANGED 只能标为“预选观点，讨论要素未实质变化”，其他不匹配则显示“预选旧观点/快照已变化”。任何 transition 和补充讨论都在确定性最终提交后、AI 总闸允许时进行，不延迟或重新发布最终结果。

若没有 ADDED/RETAINED_CHANGED，materializer 仍写入 UNCHANGED/DROPPED transition，但不创建空 batch 或四个无意义 role；source 以 NOT_APPLICABLE、reasonCode=NO_MATERIAL_CHANGE 收口。若有补充候选，transition 与非空 batch 同事务创建。

## 15. 数据模型与版本化合同

### 15.1 通用 SQL 约定

- 应用生成的主键统一 VARCHAR(36) UUID。
- symbol 为 VARCHAR(6)，hash 为 VARCHAR(64)，枚举为 VARCHAR(32) 并带 CHECK。
- 时间为 TIMESTAMP WITH TIME ZONE，写状态和租约时使用数据库 CURRENT_TIMESTAMP。
- 长内容在 canonical DDL 中使用 TEXT，不能 VARCHAR 截断。
- 所有外键默认 ON DELETE RESTRICT；讨论历史不可级联删除。
- 所有 mutable entity 带 BIGINT version NOT NULL DEFAULT 0。
- 必填列明确 NOT NULL；可空只用于尚未发生的时间、父轮次或错误。
- H2 文件库只支持单实例；PostgreSQL 承担多实例和真实并发合同。

阶段 0 必须在实现 B 前提交并审查：

- canonical schema.sql 的完整 DDL；
- AiCandidateBatchSnapshotV1、AiCandidateSnapshotV1、RoleOpinionsV1 和两个工具的 JSON Schema；
- OpenAPI 3 合同与 React 生成/契约类型；
- 状态枚举、CHECK、索引、FK 和错误码表。

以下结构是该冻结合同的最低要求，阶段 0 不能减少。

### 15.2 short_term_manual_scan_result

手动扫描不再只存在内存：

- result_id；
- actor_id；
- idempotency_key；
- request_hash；
- normalized_request_json；
- ai_requested，NOT NULL；只有请求经过可信代理身份且 feature 当时开启才为 true；
- ai_admission_reason；
- trade_date；
- result_status：PENDING、RUNNING、SUCCEEDED、FAILED、TIMED_OUT；
- result_message；
- report_json；
- data_cutoff_at；
- claim_owner、claim_token、claim_epoch、lease_until、heartbeat_at；
- created_at、started_at、completed_at、version；
- UNIQUE(actor_id, idempotency_key)。

POST 在调度扫描前先提交 PENDING 行；worker 用数据库时间、claimToken/claimEpoch 和 lease 的 CAS 认领为 RUNNING，心跳和最终 report 写入均受 fence。恢复器可以认领 PENDING 或租约已过期且尚未超出总扫描时限的 RUNNING；超过扫描时限则原子标 TIMED_OUT。旧 worker 的迟到结果受影响行数必须为零。SUCCEEDED/FAILED/TIMED_OUT 不可覆盖。reconciler 只为 ai_requested=true 的成功结果创建 admission，不能因之后补上 token/开启 feature 而追溯收费。同一 actor/idempotencyKey 且 requestHash 相同返回原结果；key 相同而 hash 不同返回 409 IDEMPOTENCY_KEY_REUSED。内存 job 可以作为进度缓存，但 GET、恢复与讨论 sourceKey 都以 result_id 为准。

### 15.3 short_term_ai_source_admission

连接确定性持久源与 AI materializer：

- admission_id；
- source_type：SCHEDULED_PRESELECT、MANUAL_SCAN、FINAL_SUPPLEMENT、CONTINUATION；
- source_key；
- actor_id；
- normalized_request_hash；
- source_payload_json，字段白名单；CONTINUATION 保存 parentRoundId、question 和 questionFingerprint，其他 source 只保存重建引用；
- admission_status；
- advisory_use；
- reason_code、reason_message；
- materialized_batch_id，可空；
- materialization_attempts、next_retry_at；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- INDEX(admission_status, updated_at)。

### 15.4 short_term_ai_discussion_thread

- thread_id；
- symbol，UNIQUE；
- company_name_snapshot；
- created_at、last_round_at、version。

### 15.5 short_term_ai_discussion_batch

- batch_id；
- admission_id，UNIQUE FK；
- source_type、source_key、actor_id；
- trade_date；
- strategy_version；
- candidate_schema_version；
- prompt_version；
- llm_config_revision、provider、model、model_settings_json，字段白名单且不含 key；
- execution_status；
- advisory_use；
- queue_admission；
- candidate_batch_snapshot_json；
- candidate_batch_hash；
- data_cutoff_at；
- same_day_wait_deadline；
- started_at、completed_at；
- error_code、error_message；
- next_event_sequence；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- INDEX(execution_status, same_day_wait_deadline)。

### 15.6 short_term_ai_discussion_round

每只候选在 batch 中一个 round：

- round_id；
- batch_id FK；
- thread_id FK；
- symbol；
- parent_round_id，可空；
- phase：PRESELECT、FINAL_SUPPLEMENT、MANUAL、CONTINUATION；
- execution_status；
- advisory_state；
- advisory_use；
- candidate_snapshot_json；
- candidate_snapshot_hash；
- candidate_material_hash；
- data_cutoff_at；
- started_at、completed_at；
- change_summary；
- error_code、error_message；
- created_at、updated_at、version；
- UNIQUE(batch_id, symbol)；
- UNIQUE(batch_id, round_id, symbol)；
- UNIQUE(round_id, symbol)；
- UNIQUE(thread_id, round_id)；
- 复合 FK(thread_id, parent_round_id) -> round(thread_id, round_id)；
- INDEX(thread_id, created_at DESC, round_id DESC)。

服务在插入时验证父轮已终止、父子 symbol/thread 相同、父 createdAt 早于子；parent_round_id 创建后不可更新。

### 15.7 short_term_ai_candidate_transition

- transition_id；
- final_source_key；
- symbol；
- transition_type：ADDED、RETAINED_UNCHANGED、RETAINED_CHANGED、DROPPED；
- from_round_id，可空；
- to_round_id，可空；
- from_snapshot_hash、to_snapshot_hash；
- from_material_hash、to_material_hash；
- deterministic_reason；
- created_at；
- UNIQUE(final_source_key, symbol)；
- 复合 FK(from_round_id, symbol) 与 FK(to_round_id, symbol) 分别指向 round(round_id, symbol)，NULL 端按 transition_type 的 CHECK 处理。

CHECK 固定：ADDED 只有 to_round_id；RETAINED_CHANGED 同时有 from/to；RETAINED_UNCHANGED 与 DROPPED 只有 from_round_id。transition 与 FINAL_SUPPLEMENT round 在同一 materializer 事务中一次插入，to_round_id 不做事后补写；from_round_id 必须是该 final source 对应的预选来源 round，而不是任意最新历史轮次。

### 15.8 short_term_ai_role_run

一个 batch 固定四行：

- role_run_id；
- batch_id FK；
- role_code；
- status；
- provider、model；
- model_settings_json，字段白名单脱敏；
- prompt_version；
- claim_owner、claim_token、claim_epoch；
- lease_until、heartbeat_at；
- session_deadline；
- started_at、completed_at；
- error_code、error_message；
- next_event_sequence；
- created_at、updated_at、version；
- UNIQUE(batch_id, role_code)；
- UNIQUE(batch_id, role_run_id)；
- INDEX(status, lease_until)。

### 15.9 short_term_ai_model_invocation

每个物理供应商请求一行：

- invocation_id；
- role_run_id FK；
- invocation_no；
- call_kind：INITIAL、TOOL_CONTINUATION、OUTPUT_CONTINUATION；
- tool_round_no，NOT NULL DEFAULT 0；
- output_segment_no，NOT NULL DEFAULT 0；
- delivery_status：PREPARED、DISPATCHED、RESPONSE_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、OUTCOME_UNKNOWN；
- request_hash；
- provider_idempotency_key，可空；
- provider_request_id，可空；
- finish_reason；
- input_tokens、output_tokens、reasoning_tokens、response_bytes；
- dispatched_at、response_received_at、completed_at；
- late；
- error_code、error_message；
- next_event_sequence、next_chunk_sequence；
- created_at、updated_at、version；
- UNIQUE(role_run_id, invocation_no)；
- UNIQUE(role_run_id, call_kind, tool_round_no, output_segment_no)；
- request_hash 在 PREPARED 后不可修改；invocation_id 主键本身提供全局唯一性。

### 15.10 short_term_ai_model_output_chunk

- chunk_id；
- invocation_id FK；
- sequence_no；
- content_type：CONTENT、REASONING、TOOL_ARGUMENT；
- content_text；
- received_at；
- byte_count；
- late；
- UNIQUE(invocation_id, sequence_no)。

详情按 invocation_id、sequence_no 重建完整原始输出，不复制一份可能不一致的截断 rawResponseText。

### 15.11 short_term_ai_role_opinion

每个 role session 对每个 round 一条：

- opinion_id；
- batch_id；
- role_run_id；
- round_id；
- symbol；
- stance、confidence；
- thesis；
- supporting_evidence_json、negative_evidence_json；
- next_day_drivers_json、invalidation_conditions_json；
- evidence_gaps_json、citation_ids_json、severe_risks_json；
- data_cutoff_at；
- evidence_cutoff_at；无联网工具时等于候选 data_cutoff_at，有工具时等于该 opinion 实际引用证据的最大 observed_cutoff_at；
- prior_round_delta、prior_round_delta_reasons_json；
- complete、validated；
- created_at；
- UNIQUE(role_run_id, round_id)；
- 复合 FK(batch_id, role_run_id) -> role_run(batch_id, role_run_id)；
- 复合 FK(batch_id, round_id, symbol) -> round(batch_id, round_id, symbol)。

### 15.12 short_term_ai_discussion_event

- event_id；
- stream_key，NOT NULL；
- batch_id，NOT NULL；
- round_id，可空；
- role_run_id，可空；
- invocation_id，可空；
- symbol，可空；
- sequence_no；
- event_type；
- tool_name；
- request_json、response_json 或 response_text；
- occurred_at、duration_ms；
- success、error_code、error_message；
- UNIQUE(stream_key, sequence_no)；
- 可空 round_id/role_run_id 若存在，必须分别通过复合 FK 与同一 batch 对齐；invocation_id 若存在必须经 role_run 归属校验。

streamKey 分别使用 batch:{id}、role:{id} 或 invocation:{id}，避免 NULL 唯一键语义。

event/chunk 的 sequenceNo 不能用 SELECT MAX + 1。写入者先在对应 batch/role/invocation 行上以 fence + 原子 UPDATE 预留 nextEventSequence/nextChunkSequence，再插入不可变记录；预留后崩溃允许留下 gap，但序号不得复用。cursor 以 sequenceNo 大于最后值继续，不能假设连续。

### 15.13 short_term_ai_source_evidence

- evidence_id；
- batch_id，NOT NULL；round_id、role_run_id、event_id 可空；
- symbol；
- origin：DETERMINISTIC_SNAPSHOT、DIRECT_ADAPTER、TAVILY_DISCOVERY、DISCUSSION_HISTORY；
- source_type、source_name；
- title、url、canonical_domain；
- provider_published_at_raw；
- published_at、observed_cutoff_at、first_seen_at、retrieved_at；
- excerpt；
- content_version、content_hash；
- point_in_time_eligible；
- eligibility_reason；
- advisory_use；
- rejection_reason；
- created_at；
- evidence_scope_key，NOT NULL；预载证据使用 batch:{batchId}，角色私有证据使用 role:{roleRunId}；
- UNIQUE(evidence_scope_key, symbol, content_hash)；
- INDEX(symbol, published_at, created_at)；
- 可空 round_id/role_run_id/event_id 若存在，必须通过复合 FK 与同一 batch、symbol 和 scope 对齐；数据库约束与服务器 TrustedEvidenceValidator 双重校验。

### 15.14 short_term_ai_usage_budget

- budget_id；
- budget_date，Asia/Shanghai；
- actor_id；
- scope_type：GLOBAL、ACTOR、BATCH；
- scope_key；
- model_invocations；
- evidence_tool_calls；
- tavily_requests；
- tavily_credits；
- direct_fetches；
- continuation_batches；
- version；
- UNIQUE(budget_date, scope_type, scope_key)。

每次模型、Tavily 或 direct fetch 在 DISPATCHED 前按固定 GLOBAL -> ACTOR -> BATCH 顺序原子预占最坏额度；供应商返回 include_usage 后只允许向下结算未使用 credits，响应未知则保守保留预占。预算扣减使用带 version/阈值的条件 UPDATE；任一 scope 失败则整个预占事务回滚，并发请求不能穿透上限或因锁顺序死锁。证据、事件、chunk 和历史轮次均不因角色失败或后续讨论而删除。

## 16. 事务、一致性与恢复

### 16.1 确定性结果与 AI 解耦

确定性源结果永远先提交：

1. Scheduled PRESELECT/FINAL 或 durable manual result 在自己的事务中提交。
2. 提交后的事件只携带 sourceType/sourceKey，不直接执行模型；after-commit handler 先幂等 ensure 一条 MATERIALIZATION_PENDING admission。
3. CONTINUATION 的 HTTP 事务直接提交带白名单 sourcePayload 的 MATERIALIZATION_PENDING admission，因此问题文本在返回 202 前已经持久化。
4. 独立 materializer 事务锁定 pending admission，创建非空 batch、每股 round 和四个 batch-level role run，再把 admission 改为 MATERIALIZED；FINAL_SUPPLEMENT 无实质变化时只写 transition 并按 NO_MATERIAL_CHANGE 收口。
5. materializer 的唯一冲突按唯一键重新读取；可恢复异常只增加 attempts/nextRetryAt，超过配置重试次数才标 MATERIALIZATION_FAILED，且不传播回确定性 HTTP 或最终发布事务。
6. reconciler 从 scheduled snapshot、ai_requested=true 的 successful manual result 和 pending continuation admission 三类持久源重建 source identity，补齐缺失 admission/batch；14:44:30 至 14:50:00 遵守 AI 总闸，不 materialize。

任何 AI 初始化失败、唯一冲突、执行器拒绝、Tavily 或 LLM 配置错误都不能改变确定性 payload 的规范化 hash、status、publication proof 或 API 响应。

### 16.2 创建和父链

materializer 在一个短事务中：

- 锁定或重新读取 MATERIALIZATION_PENDING admission；
- 插入或读取 thread；
- 选择同 thread 最新已终止父轮并验证时间；
- 插入 batch、round 和四个 role run；
- 绑定 admission.materializedBatchId；
- 将 admission 原子改为 MATERIALIZED；
- 提交后唤醒 dispatcher。

FINAL_SUPPLEMENT 先生成全部 transition；只有存在 ADDED/RETAINED_CHANGED 才执行上述 batch/round/role 步骤。否则插入 transition 后把 admission 改为 NOT_APPLICABLE/NO_MATERIAL_CHANGE，不唤醒 dispatcher。

并发 thread/batch/round 创建只依赖唯一约束与重复键后的重新读取，禁止 exists 后 insert。continuation 的幂等作用域为 actorId + symbol + parentRoundId + idempotencyKey；同时保存 questionFingerprint。同 key 不同 fingerprint 返回 409。

### 16.3 认领与 fencing

PostgreSQL 认领使用单条条件 UPDATE：

- 条件是 QUEUED，或 CLAIMED/RUNNING 且 leaseUntil < 数据库 CURRENT_TIMESTAMP 且尚未 DISPATCHED；
- 成功时 claimEpoch + 1、生成 claimToken 和 leaseUntil；
- 受影响行数必须为一。

一旦存在 DISPATCHED 且没有可确认响应的 invocation，租约过期后不能重新发模型请求；恢复器把 role 标为 OUTCOME_UNKNOWN。所有写入均携带 token/epoch fence；late 审计路径还要求 invocation 已存在且 epoch 未被取代。聚合器只读取 validated=true 且未 late 的 opinion。

### 16.4 流式完成

- 模型 chunk 以有界缓冲聚合后短事务追加，网络期间无长事务。
- 工具请求先记 TOOL_REQUEST；校验失败记 TOOL_REJECTED；合格 evidence 先落库再回传模型。
- role 解析完成事务写每股 opinion 并更新 role 终态。
- batch 达到四个 role 终态或 deadline 后，聚合器按固定真值表原子更新所有 round 与 batch。
- deadline fence 先于迟到网络线程；迟到响应只能写 late 审计 chunk。

### 16.5 重启恢复

| 持久状态 | 自动行为 |
| --- | --- |
| source 已成功、无 admission/batch | reconciler 幂等 materialize |
| Role QUEUED | 可认领 |
| Role CLAIMED/RUNNING，尚无 DISPATCHED | 租约过期后可认领 |
| Invocation DISPATCHED，供应商支持稳定幂等键 | 仅以同 invocationId/同幂等键恢复 |
| Invocation DISPATCHED，供应商不支持幂等键 | OUTCOME_UNKNOWN，不自动重发 |
| 已收到 chunk、deadline 未到 | 只有原连接存活时继续；进程重启后标 INCOMPLETE，不重新生成已有段 |
| deadline 已到 | TIMED_OUT/PARTIAL/INCOMPLETE；advisoryUse 不升级 |
| 任一终态 | 只读，不自动覆盖 |

“不重复收费”的验收限定为：重复 HTTP、双击、双实例、dispatcher 重试和系统自动恢复不会创建重复 source/batch/initial invocation；供应商不支持幂等键的未知外部结果采用 at-most-once 停止，而不是承诺无法实现的 exactly-once。

### 16.6 Schema 升级

apps/api/src/main/resources/schema.sql 是唯一 canonical additive schema，Spring 在 H2/PostgreSQL 启动时执行。infra/db/init/001_schema.sql 不再维护第二份业务 schema；空 PostgreSQL 也由 API 启动应用 canonical schema。

本次只新增表、索引和约束，不删除旧列。发布前：

- 备份 H2 api-data 卷或 PostgreSQL 数据库；
- 在 H2 文件副本上从 a46cae9 升级并重启；
- 用 Testcontainers PostgreSQL 从当前 schema 基线升级，执行完整 DDL、复合 FK、CHECK、数据库时间 CAS 和双实例并发测试；
- 回滚应用版本时保留新增表，不做破坏性 down migration；
- optional infra profile 的 PostgreSQL/Redis/MinIO 端口若继续暴露，至少绑定 127.0.0.1；生产不得使用示例默认凭据。

## 17. API 合同

固定前缀 /api/short-term/ai-discussions，版本化响应 schemaVersion=1：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /batches/by-source?sourceType=&sourceKey= | 查询扫描对应批次和候选 AI 状态 |
| GET | /batches/{batchId} | 查询批次、轮次和四角色进度 |
| GET | /rounds/{roundId} | 获取结构化讨论、invocation 元数据、证据和父轮次差异 |
| GET | /opinions/{opinionId} | 按角色读取一份完整结构化意见 |
| GET | /invocations/{invocationId}/chunks?cursor=&limit= | 按稳定序号读取完整原始输出片段 |
| GET | /rounds/{roundId}/events?cursor=&limit= | 按稳定序号读取完整事件时间线 |
| GET | /stocks/{symbol}/history?cursor=&limit= | 分页获取同股全部历史轮次 |
| POST | /stocks/{symbol}/continuations | 从指定父轮次继续讨论，返回 202 |

现有 POST /api/short-term/scan-jobs 增加必填 Idempotency-Key 请求头；GET scan job 状态增加 persistentResultId、aiAdmissionStatus、aiBatchId 和 aiReasonCode。

### 17.1 读取

- BatchSummaryV1 返回 source、execution/admission/advisory 状态、候选 round 摘要和四角色进度。
- RoundDetailV1 返回 candidate snapshot、四个 opinion 元数据/detail URL、invocation 元数据、证据、父轮次和 transition；每份完整结构化 opinion 独立读取，原始 chunk 与事件通过各自 cursor 接口字节保真读取，避免把最多 256 MiB 内容装进单个 JSON 响应。
- HistoryPageV1 的 item 只含时间、状态、角色计数、父轮次和 detail URL 等列表摘要，不触发额外模型调用，也不假装是完整原文；点击详情一定能读取未截断内容。
- chunk 页面每页最多 100 项且编码后最多 4 MiB，event 页面每页最多 200 项且编码后最多 2 MiB；二者使用签名不透明 cursor、稳定 sequenceNo 顺序和完整 item 边界。达到项数或字节页上限时返回 nextCursor，不能 substring 某一 item、追加省略号或把 nextCursor 缺失伪装成结尾。
- API 不截断结构化 opinion、证据或按 chunk 重建的原始响应；单次 HTTP 有界不等于业务内容被丢弃。
- 历史按 createdAt DESC、roundId DESC 稳定排序；cursor 是这两个字段签名后的不透明值。limit 默认 20，最小 1，最大 100。
- 活跃任务响应包含 completedRoles、totalRoles、startedAt、deadlineAt 和 lastEventAt。
- 所有错误字段使用稳定 errorCode 与可读中文 message。
- 固定 HTTP 语义：400 参数/Schema，401 专用付费/配置端点未通过本地代理身份，403 权限，404 不存在，409 幂等载荷冲突，429 持久预算/频率超限，503 AI 功能或供应商不可用。scan-jobs 的确定性扫描是例外，按第 17.3 节降级而不是因 AI 身份返回 401。

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
- idempotencyKey 的作用域为 actorId + symbol + parentRoundId；相同 key 不同 questionFingerprint 返回 409。

### 17.3 访问控制

本版明确选择“本机单用户 + 可信反向代理”边界，不引入全站账号系统：

1. 发布 Compose 只把 React Web 绑定到 127.0.0.1；API 不映射宿主机端口，只接受 Docker internal network 的 Web 代理。
2. Web 与 API 只读挂载同一 /run/secrets/ai_internal_token。Web entrypoint 读取 secret 并通过官方 Nginx template envsubst 生成 proxy_set_header；客户端传入的 X-AI-Internal-Token 被丢弃，代理重新设置唯一的 secret 值。
3. API 通过 Spring config tree 读取同一 secret，并用常量时间比较验证 token；未配置 token 时 AI discussion feature 强制关闭。
4. POST continuation、未来任何 AI retry 和运行时模型配置 PUT 必须有该内部身份，否则 401。POST scan-jobs 本身仍允许完成确定性扫描；只有内部身份有效且 feature 开启时才自动创建 AI admission，缺失/错误 token 时扫描照常返回并以 reasonCode=AI_AUTH_REQUIRED 表示未启动 AI，不能让 AI 鉴权改变扫描 payload/status。Scheduled trigger 使用固定 actorId=scheduler，不经公网 HTTP。
5. actorId 对本版固定为 local-operator 或 scheduler，并进入幂等和预算键。
6. CORS、Referer、X-Forwarded-For、前端隐藏按钮或容器来源 IP 都不作为身份。
7. 所有付费触发都有持久幂等键、并发限制和每日预算。
8. Nginx 只接受明确的 localhost/127.0.0.1/[::1] Host，未知 Host 直接 444/403，防止 DNS rebinding；状态改变请求若带 Origin，必须与当前 loopback Origin 精确一致，浏览器 Sec-Fetch-Site 为 cross-site 时拒绝。内部 token 只在匹配的 API proxy location 中由 Nginx覆盖写入，不转发客户端自带值。Host/Origin 检查是 CSRF/重绑定防线，不替代内部身份。

该边界只适用于本机回环部署。若以后把 Web 绑定到非 loopback、接入公网反向代理或多用户使用，必须先另行增加应用内身份/角色认证；本功能在检测到外部部署配置但没有用户认证时拒绝启用。开发模式可直接访问 API，但 AI feature 默认关闭，只有显式提供测试 token 才启用 fake/真实调用。

发布验收包括：API 宿主机端口不可达、Web 仅 loopback、未知 Host/DNS rebinding 与 cross-site Origin 被拒绝、伪造外部 header 被可信代理值覆盖、专用付费端点无 token 返回 401、scan-jobs 无 token 仍产生相同确定性结果但不触发 AI、双击/响应丢失重试只生成一个 durable manual result、一个 batch 和四个初始 role session。

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

不得重新引入此前已移除的“手动扫描执行中/完成”顶部状态页卡；扫描与 AI 完成/失败继续通过右上角浮动消息反馈，候选内只展示与该候选相关的 AI 状态。

React 在第一次 POST 前生成 Idempotency-Key，并把 key + requestHash 写入 sessionStorage；网络重试、响应丢失和页面刷新继续复用同一 key，直到读到持久终态。用户在终态后明确再次点击才生成新 key。继续讨论采用同样规则，不能在 axios 自动重试时生成新 key。

### 18.2 讨论页

新路由：

    /short-term/discussions/:roundId

React 继续使用 HashRouter，浏览器外部 URL 为 /#/short-term/discussions/:roundId；不迁移 BrowserRouter。

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

长内容默认折叠可以接受，但必须有“展开全部”；前端随后遍历 nextCursor 逐页加载到真实结尾并显示，不能只渲染摘要。原始模型响应作为高级追溯区显示，并明确其未经确定性验证。

所有模型文本、网页摘要和历史文本按纯文本渲染，禁止 dangerouslySetInnerHTML。外链只使用服务器返回且已校验的 HTTPS URL，并加 rel="noopener noreferrer"；CSP 不允许讨论内容执行脚本。

### 18.3 更新方式

沿用 React 当前轮询模式，不新增 SSE：

- 进入活跃讨论后定时查询 batch/round；
- 页面离开或终态后停止轮询；
- 完成、部分完成、超时和失败都在右上角显示浮动消息；
- 消息可点击进入讨论页；
- 轮询异常不清空最后一次成功数据。

### 18.4 AI 完成后的短线结果视图收口

该项必须在阶段 E 的 AI 全链路验收通过后作为独立阶段 F 实施：

- 从 ShortTermViewPreferences 类型、resultViewOptions、ShortTermPage 渲染树和测试中移除 snapshotVisible。
- 删除仅服务于该结果卡的 ScanSnapshotSummaryCard 组件代码，不再显示“扫描快照”开关或卡片。
- fundFlowVisible 的 v2 默认值改为 true。
- 偏好存储新 key 固定为 ai-stock.short-term.result-view.v2，payload 含 schemaVersion=2。v2 不存在时首次读取旧 ai-stock.short-term.result-view.v1，保留 methodologyVisible、marketSentimentVisible、hotDirectionsVisible，把 fundFlowVisible 置为 true，丢弃 snapshotVisible 并立即写回 v2；旧 key 保留供应用回滚。以后 v2 中用户主动关闭资金去向必须被尊重，不能每次加载强制打开。损坏 v2 回退 v2 默认值但不得循环读取旧 v1 覆盖用户。
- 今日资金去向仍使用 report.marketFundDirection，不新增请求，也不把“暂无数据”伪装为资金流正常。
- ShortTermScheduledSnapshot、scheduled-snapshots/latest 请求、覆盖率/点时门禁、扫描完成右上角消息和 AI source materialization 全部保留；它们不再有独立的“扫描快照”结果卡。
- 长期价投 MarketScanPage 的同名卡保持不变。

## 19. 模型配置与密钥安全

- materializer 为整个 batch 一次性读取当前 runtime_config_section 的 LLM revision 和 LlmSettings，四个 role 共享同一份不可变非密钥快照，避免角色启动先后导致 Provider/Model 混用。
- batch/role 保存 llmConfigRevision、provider、model、baseUrl、能力版本和非密钥设置，不保存 API Key；页面据此可证明实际使用的模型。
- 真正 DISPATCHED 前重新解析凭据并要求 LLM revision 仍等于 batch 快照。若配置已变化，尚未发送的 role 以 CONFIG_REVISION_CHANGED 失败，不能悄悄改用新模型；已发送调用继续使用进程内不可变凭据。用户可基于父轮发起新讨论使用新配置。
- 配置变化只影响之后创建的新 batch。
- API Key 环境变量只能从 Provider 固定 allowlist 名称通过可测试的环境访问器解析；生产实现最终只读 System.getenv，不能用用户可控名称查询整个 Spring Environment。
- 模型 Base URL 只能使用 Provider 对应的固定官方 HTTPS 地址，不能指向用户任意服务器。
- 现有 runtime_config_section 仍可按已批准的数据库动态配置合同保存直接 LLM Key；本功能不迁移或复制该 secret。禁止项特指 discussion batch/round/role/invocation/event/evidence/opinion/chunk/usage 表、Prompt 快照、错误和 HTTP 日志，它们不得包含 LLM Key、Tavily Key、数据库密码或 Authorization Header。
- 工具结果中的网页文本必须与系统指令分区，提示模型不得执行其中的命令。
- 页面和 API 不能返回完整隐藏提示中的密钥或内部认证信息。
- Tavily 只从固定 System.getenv("TAVILY_API_KEY") 读取；Compose 与 .env.example 只接线空变量，不提交真实值。
- AI discussion feature flag 默认关闭。阶段 E 的本地安全、预算、联网和故障注入验收全部通过后，发布环境才显式开启。
- model settings、candidate snapshot 和 event 采用字段白名单 DTO 序列化，不允许“先序列化整个对象再正则脱敏”。
- 运行时配置 GET 始终只返回 hasApiKey/apiKeySource 等掩码信息；PUT 必须通过第 17.3 节内部身份，并继续拒绝非官方 Base URL、任意 apiKeyEnv 和切换 Provider 时沿用旧 Provider 密钥。
- 多实例首次启动对 runtime_config_section 的默认行初始化必须依赖原子插入/重复键重读，不能 exists-then-insert 导致第二实例启动失败；真实 PostgreSQL 并发启动纳入阶段 E 回归。

## 20. 失败语义

| 失败 | 处理 |
| --- | --- |
| 单角色模型超时 | 保存已有事件，角色 TIMED_OUT，聚合 PARTIAL/INCOMPLETE |
| 单角色 HTTP/供应商异常 | 保存错误码，其他角色继续 |
| DISPATCHED 后进程崩溃且供应商无幂等键 | OUTCOME_UNKNOWN，不自动重发；用户可新建继续讨论 |
| 工具参数非法 | TOOL_REJECTED，模型可在剩余轮次修正 |
| 搜索未配置/超时 | SEARCH_UNAVAILABLE，角色使用本地证据并列出缺口 |
| 搜索结果越权域名或时间 | 保存 rejectionReason，不回传为可用证据 |
| 结构化解析失败 | 通过 chunk 接口保留并可完整读取 raw response，角色 INCOMPLETE |
| finishReason=length | 保存片段；有时间则一次续写，否则 INCOMPLETE |
| source 已提交、AI materializer 失败 | 确定性结果不变；admission pending；reconciler 重试 |
| 模型发送前数据库短暂失败 | PREPARED 可有界重试 |
| 模型发送后数据库失败 | 不自动重发；保留 chunk 能力范围内标 INCOMPLETE/OUTCOME_UNKNOWN |
| 执行器队列满 | durable batch 保持 QUEUED、queueAdmission=DEFERRED，不阻塞扫描 |
| 预算或存储水位耗尽 | 熔断新 AI，已有历史可读，确定性扫描继续 |
| 应用重启 | 依据数据库状态和租约恢复 |
| AI 全部失败 | 确定性结果仍按原流程发布 |

“AI 全部失败”不能把 FINAL_READY 改成 FINAL_PENDING、DATA_BLOCKED 或无候选；“确定性数据失败”也不能被 AI 支持意见挽救。

## 21. 可观测性

至少记录不含敏感信息的指标：

- 批次/轮次/角色各状态数量；
- 队列深度、DEFERRED 次数与恢复耗时；
- 角色模型耗时、搜索耗时、总耗时；
- 各 Provider/Model 错误码；
- finishReason=length 数量和续写成功率；
- 工具请求、拒绝、无结果和超时数量；
- 14:44:30 前讨论完成率；
- 确定性最终发布时间和 14:49:40 门禁结果；
- 重启恢复和租约抢占数量。
- PREPARED/DISPATCHED/OUTCOME_UNKNOWN invocation 数量；
- model invocation、Tavily request/credit 的 batch/actor/day 用量；
- materialization gap 和 reconciler 修复数量；
- late chunk/fence 拒绝写入数量；
- AI feature、存储水位和预算熔断状态；
- AI 故障注入前后确定性 payload hash 是否一致。

日志使用 batchId、roundId、roleRunId 和 correlationId 关联，不记录完整 Prompt、密钥或 Authorization。

## 22. 测试策略

### 22.1 短线去估值

- preliminaryScore 不调用估值计算器。
- PE/PB 极值、空值和异常值的完整变形测试，覆盖 todayAdvice、evidenceCompleteness、tradePlan、validation/calibration、观察样本、历史和所有 identity hash。
- K 线复核池顺序与 PE/PB 无关；只变 deprecated 参数时 snapshotKey 与 batch identity 不变。
- 当前版本 AI 快照与提示不含 PE/PB。
- React 短线配置与详情不显示相关字段或文案。
- 历史短线详情也不显示。
- 短线详情不请求 strategy-bundle，打开详情前后长期 ledger 行数不变。
- 所有新记录使用唯一 short-term-emotion-v5 常量，没有硬编码 V4。
- 长期估值页面和服务回归通过。

### 22.2 讨论编排

- 一个八候选 batch 只创建四个初始 role session；四个 INITIAL invocation 并行，不创建 32 个。
- 工具和输出续写按 invocation 记录，受每 session 四次物理调用上限约束。
- 扫描 API 在 AI 未完成时立即返回。
- 一个角色失败时其他角色和聚合正常。
- 四个角色共享同一 LLM revision/provider/model；DISPATCH 前 revision 改变时未发送角色明确失败且不会混用新模型。
- 四角色全失败时确定性结果不受影响。
- 队列满仍保留 durable QUEUED；dispatcher 可恢复。
- 无 AI 身份的确定性降级、双击、响应丢失重试、相同 key 不同 payload 和双实例并发。
- 数据库时间边界覆盖 14:44:29.999、14:44:30、14:47、14:49:40、14:49:40.001 与 14:50:00。
- ADDED、RETAINED_UNCHANGED、RETAINED_CHANGED、DROPPED transition 与 discussionMaterialHash 匹配；只改变 dataCutoffAt 不得把 retained 误判为 changed，完整 snapshotHash 仍不同且可追溯。

### 22.3 数据库与恢复

- H2 文件副本升级/重启测试与真实 Testcontainers PostgreSQL schema 启动。
- thread/batch/round 唯一约束并发测试。
- PostgreSQL 两个应用实例同时 materialize/认领，只有一个 batch 和四个 role session。
- PostgreSQL 两个应用实例同时初始化空 runtime_config_section，均能启动且每个 section 只有一行。
- claim token/epoch、数据库时间、心跳、过期抢占；旧 owner 的 event/chunk/evidence/opinion/终态全部被 fence。
- source commit 后、materializer 前崩溃由 reconciler 恢复。
- durable manual scan 的 PENDING/租约过期 RUNNING 恢复、总超时和旧 worker 最终 report fence。
- PREPARED 可恢复；DISPATCHED 后供应商已接收、本地提交前崩溃在无幂等支持时变 OUTCOME_UNKNOWN，断言不发生第二次供应商请求。
- 完成历史不可覆盖。
- 复合父 FK 阻止跨股票/thread，父字段不可更新。
- 超过普通页面长度的 chunk 往返、重建和详情显示字节一致。
- persistent usage budget 在并发下不能超扣。

### 22.4 模型与搜索

- Tool call JSON 合法与非法参数。
- symbol 越权、模型提供 URL、exact-host 旁路、eviltrusted.com、未知 COMPANY_IR。
- Tavily adapter 断言绝不对 result URL 发起二次请求；固定请求参数、include_domains、requestId 与 credits 落库。
- Cninfo 附件 fetcher 覆盖 localhost、私网、IPv4-mapped IPv6、DNS rebinding、逐跳重定向、超大 body 与错误 MIME。
- Tavily 自然日结果、精确 news 时间、直接公告时间、observedCutoffAt 与 14:44:30 点时资格真值表。
- 无发布时间或截止后证据只能 RESEARCH_ONLY，不能触发 SAME_DAY RISK_ALERT。
- 网页 Prompt Injection 和历史模型文本不能增加工具/域名/预算；伪造 citationId 和 severe risk 被服务器拒绝。
- 搜索密钥和 LLM 密钥不出现在响应、事件或日志。
- 运行时配置 PUT 无内部 token 为 401；任意 apiKeyEnv、非官方 Base URL 与跨 Provider 复用旧 key 均被拒绝，GET 不回显 key。
- Nginx 未知 Host、DNS rebinding Host、cross-site Origin/Sec-Fetch-Site 与伪造 X-AI-Internal-Token 均不能触发付费调用。
- 流式中途超时保存已接收 chunk；finishReason=length 的唯一续写、合并与 INCOMPLETE。
- 32 MiB/256 MiB 运输边界只产生 INCOMPLETE_RESOURCE_LIMIT，不伪装完整。
- 不支持工具调用的模型返回明确 SEARCH_UNAVAILABLE 或 TOOL_UNSUPPORTED。

### 22.5 API 与 React

- OpenAPI 与 React 类型契约测试。
- BatchSummary、RoundDetail、签名 cursor、稳定排序、父轮次变化和全部错误码。
- 活跃轮询停止条件和网络失败保留数据。
- 右上角完成/失败浮动消息。
- scan/继续讨论幂等、防连点；scan 无 AI 身份降级，以及专用付费端点的 401/409/429/503。
- POST 前 key 持久化、响应丢失/刷新复用和终态后新操作换 key。
- 展开全部显示完整长文本。
- 规则动作与 AI 状态在视觉和语义上分离。
- HashRouter 深链 /#/short-term/discussions/:roundId 刷新可用。
- 短线偏好 v1 -> v2 迁移只执行一次：资金去向首次变为可见、其他保留项不丢、snapshotVisible 被删除；随后用户主动关闭资金去向仍能持久化。
- 短线 DOM 与结果视图开关均不存在“扫描快照”，今日资金去向默认存在；长期价投同名卡仍存在。
- 移除短线扫描快照卡后，scheduled snapshot 轮询、覆盖率阻断、右上角通知和 AI source materialization 回归通过。

### 22.6 可复制验证与现场验收

自动验证使用注入 Clock/数据库时间夹具、固定八候选 fixture、可记录请求次数的 fake streaming LLM/Tavily、Testcontainers PostgreSQL 和两个 API 实例。实施计划必须给出每条命令、预期计数与 SQL/HTTP/DOM 断言，至少包括：

- Maven 定向测试与 mvn test；
- React 定向 Vitest、npm test、npm run build；
- 旧 Vue npm run build；
- canonical schema 在 H2 文件副本和 PostgreSQL 上升级、重启；
- 模型 90 秒超时、Tavily 429/500/超时、一个/四个角色异常；
- 供应商接收后本地崩溃、聚合写失败、队列饱和、AI 存储/预算熔断；
- AI 故障注入前后确定性 payload hash、FINAL_PENDING/FINAL_READY 与认证时间完全一致。

模拟时钟自动证明 14:49:40 边界；真实交易日观察单独记录，不能用当天是否恰有候选替代自动证明。现场验收包括：

- 真实手动扫描只生成一个持久结果、一个 batch、四个初始角色；
- 后台讨论完成并从右上角消息进入完整详情；
- 有效 TAVILY_API_KEY 下完成一次可信 news 域名搜索并展示时间/引用；
- 同股继续讨论形成父子关系；
- 容器重启后历史仍存在；
- 14:47 至 14:50 人工触发 AI 故障时，运行中的确定性最终仍正常发布或按其原门禁失败关闭。

## 23. 验收标准

1. 改变 PE/PB 不会改变当前短线任何候选、复核顺序、identity、完整度、交易计划、验证资格、动作、解释或历史；当前版本不调用估值计算器。
2. 短线页面及历史详情看不到 PE/PB 与估值语境，也不再通过 strategy-bundle 产生长期 ledger；长期入口不受影响。
3. 成功扫描后 HTTP 请求不等待 AI；一个最多八候选 batch 只创建四个真实并行初始角色会话。
4. 用户可以查看四角色完整输出、原始响应、工具过程、引用、错误和模型版本。
5. 模型可以发起受控联网搜索；同日意见只接收 registry 可信且可证明不晚于截止的证据，其他结果明确 RESEARCH_ONLY。
6. Tavily result URL 不被服务器抓取；直接公告 fetcher 不能访问内网/越权域名；不存在密钥泄漏、伪 citation 或网页提示词越权。
7. 不对模型论述做业务层长度截断；供应商截断被识别、保存并按规则续写。
8. 任一或全部 AI 异常、队列饱和、预算/存储熔断和 materializer 失败都不改变确定性 payload hash 或 14:49:40 发布门禁。
9. 同一股票所有历史轮次可查，新轮次正确链接父轮次并显示观点变化。
10. 讨论记录在 H2/PostgreSQL 中持久化，容器重启后可追溯；PostgreSQL 的租约 epoch 能 fence 所有旧 owner 写入。
11. 重复 HTTP、误点、响应丢失、dispatcher 重试和多实例竞争不生成重复 source/batch/initial invocation；DISPATCHED 未知结果在无供应商幂等键时停止自动重发并标 OUTCOME_UNKNOWN。
12. 发布 Web 只绑定 loopback、API 不暴露宿主端口、付费触发必须通过可信代理 token；专用付费端点无 token 401，scan-jobs 无 token 仍完成确定性扫描但不触发 AI，缺 token 时 AI feature 关闭。
13. 后端完整测试、React 测试与构建、旧 Vue 构建、schema 启动、容器健康和浏览器真实链路验收通过。
14. H2 文件副本和真实 PostgreSQL 升级、OpenAPI/React 契约、fake streaming Provider 与浏览器现场链路全部通过。
15. 每个阶段的规格与对抗审查均未留下 Critical 或 Important，最终全分支红队也通过。
16. 阶段 F 完成后，短线新用户和一次性迁移的旧用户默认看到“今日资金去向”，结果视图中没有“扫描快照”开关或卡片；用户之后可主动隐藏资金去向，长期价投和后台扫描快照链路不受影响。

## 24. 多 Agent 实施与对抗复核协议

### 24.1 角色分工

主 Agent 只承担规格所有权、共享接口冻结、阶段集成、驳回裁决和最终验收。实现任务由每阶段新的开发 Agent 执行，审查 Agent 不得审查自己写的代码。

阶段划分：

| 阶段 | 开发责任 | 强制对抗重点 |
| --- | --- | --- |
| A | PE/PB 决策移除与 React 完全隐藏 | 寻找任何隐藏估值影响、兼容破坏和长期模块误伤 |
| 0 | 冻结 canonical DDL、OpenAPI、JSON Schema、状态枚举、错误码与生成类型 | 合同矛盾、字段遗漏、H2/PostgreSQL、长内容和兼容 |
| B | 讨论领域模型、schema、Repository、原子认领与恢复 | 并发初始化、重复收费、租约竞态、重启数据完整性、H2/PostgreSQL 差异 |
| C | 四角色编排、LLM 长输出、工具调用、Tavily 与安全网关 | SSRF、DNS/重定向、Prompt Injection、点时越界、密钥外泄、工具死循环 |
| D | API、React 状态、完整讨论页、历史与继续讨论 | 内容截断、父链错误、轮询泄漏、规则动作与 AI 混淆、权限和幂等 |
| E | 调度集成、故障注入、容器与真实链路 | 14:44:30/14:49:40 竞态、AI 阻塞确定性发布、重启、多实例与现场可用性 |
| F | 短线结果视图偏好迁移、资金去向默认展示、扫描快照卡移除 | 旧偏好迁移循环、误删后台 snapshot、资金流无数据误导、长期页面误伤 |

依赖 DAG 固定为 A -> 0 -> B -> C -> D -> E -> F。A 可作为独立可发布变更，但本分支在完整功能交付前不默认开启 AI。B 至 E 共享领域合同，严格串行，不因“多 Agent”而并行修改共享文件；F 只在 E 全链路验收后开始，满足“做完这份之后”再调整结果视图。每阶段使用新的开发 Agent；规格审查与对抗审查可并行只读运行。

书面实施计划必须为每阶段列出：

- 唯一 base SHA 来源；
- 文件所有权和允许修改的共享文件；
- 依赖输入与输出；
- 红测/绿测命令；
- 审查包、合并顺序和回滚点。

### 24.2 每阶段硬门禁

每阶段严格执行：

1. 开发 Agent 先写失败测试，再写最小实现，再跑定向和相关回归。
2. 主 Agent 记录阶段 base SHA 与 head SHA，生成独立审查包。
3. 规格审查 Agent 逐条核对本阶段合同和验收标准。
4. 对抗审查 Agent 主动构造失败场景，检查并发、安全、点时、兼容和不可见副作用。
5. 任一 Critical 或 Important 发现使阶段状态变为 REJECTED。
6. REJECTED 任务返回修复 Agent；必须增加能复现问题的失败测试，不能只改实现。
7. 修复后使用新的 head SHA 重新生成审查包。任何 head 变化都会使旧的规格 PASS 和对抗 PASS 同时失效，两个独立审查都必须对新 head 重跑。
8. 只有新 head 的两个审查结论均 PASS，且主 Agent 复跑验证通过，阶段才可进入 ACCEPTED。
9. 未 ACCEPTED 的阶段不得作为下一共享阶段的基线。

Minor 建议可以记录到清单，但若它影响验收、数据安全、投资边界或未来不可逆兼容，则升级为 Important。

### 24.3 六个强制红队检查点

1. 去估值红队：只改变 PE/PB、旧快照和兼容 DTO，尝试证明候选发生变化。
2. 数据红队：并发双实例启动、重复入队、租约失效、崩溃恢复和长文本往返。
3. 联网红队：Tavily URL 不抓取、Cninfo 受限抓取、私网/重定向/DNS rebinding、网页注入、截止后新闻和密钥诱导。
4. 追溯红队：不完整响应、父子轮次、全部历史、内容不截断、失败过程可见。
5. 发布红队：让模型和搜索同时超时、AI 数据库短暂失败、队列/预算/存储熔断，尝试阻断或污染运行中的确定性最终结果。
6. 结果视图红队：构造旧 v1 偏好、损坏偏好和用户在 v2 主动关闭资金去向，尝试证明迁移反复覆盖用户选择，或移除 UI 时误删后台/长期 snapshot 链路。

红队任务是尝试证伪验收标准，不是复述代码。没有可复现证据的笼统评价不作为放行依据。

### 24.4 持久进度账本

分支维护受版本控制的 docs/superpowers/progress/2026-08-13-short-term-ai-discussion-progress.md，记录：

- 阶段和任务编号；
- 开发 Agent；
- base/head SHA；
- 红测命令、预期失败、实际失败证据、绿测命令与结果；
- 审查包的 clean base/head 与 diff hash；
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
- 浏览器验证今日资金去向默认展示、v1/v2 偏好迁移、扫描快照卡消失且后台 scheduled snapshot 仍工作；
- fake Provider、PostgreSQL 双实例和故障注入下的 14:49:40 确定性发布验证。

只有全部通过且无 Critical/Important 发现，才可合并和推送。任何阶段反复失败都继续驳回修复，除非出现必须由用户决定的真实产品歧义或外部凭据阻塞。

## 25. 实施前仍需满足的依赖

- 用户书面确认本文。
- 书面实施计划列出具体文件、测试、阶段 SHA 和 Agent 门禁。
- 发布环境提供受控搜索所需的 TAVILY_API_KEY；没有密钥时不得伪称实时联网已验收。
- 发布环境提供至少 256 bit、base64url 无换行的随机只读代理 token，并保持 Web loopback、API 无宿主端口；未满足时 AI feature 保持关闭。
- 现有主分支基线测试通过；若存在基线失败，先记录并与本功能失败区分。
