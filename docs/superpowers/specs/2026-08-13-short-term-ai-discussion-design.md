# 短线去估值、多角色 AI 讨论与结果视图设计

## 1. 文档状态

- 日期：2026-08-13
- 状态：待书面规格确认
- 目标分支：codex/short-term-ai-discussion
- 基线：main / a46cae9
- 适用模块：Spring Boot API、React 短线页面、H2/PostgreSQL 数据库
- 不适用模块：长期价投、政策解读、特别关注、交易复盘

本文把已确认的产品方向固化为可实施、可测试、可对抗复核的合同。实现 Agent 不得自行扩大 AI 的决策权限，也不得为了赶进度降低 95% 行情覆盖、点时、手动扫描尾盘窗口或风险门禁。

## 2. 背景与当前事实

当前短线预选仍把估值语境计入排序：

- 估值 10%
- 流动性 30%
- 不追高 25%
- 市场热度 35%

ShortTermService.preliminaryScore 会读取 PE、PB 与参考上限，ShortTermPage 仍显示 PE 参考带、PB 参考带、PE TTM、PB、估值参考和估值语境分。即使 PE/PB 已不再是最终技术结构的主因，它们仍会影响进入 K 线复核池的顺序，因此“仅在页面隐藏”不能满足本次要求。

当前短线结果由确定性规则生成，模型配置虽已从数据库动态读取，但短线扫描本身没有调用大模型。大模型只被其他 AI 分析入口显式调用。现有手动扫描任务保存在进程内且只短期保留，不能承担讨论历史。

当前短线结果视图默认 fundFlowVisible=false、snapshotVisible=true，因此“今日资金去向”首次打开不展示，而“扫描快照”占用一个摘要卡和一个偏好开关。当前后台还注册了 14:30 预选、14:47 最终和 14:49:50 就绪检查；本次按最新确认取消这些短线定时任务，只保留手动扫描。

当前代码中的旧调度边界如下，但实施后不再自动执行：

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
4. 每个确定性 source 最多选择 ruleRank 前八只候选进入一个 AI batch，后台只启动四个真正独立的 AI 角色会话；每个角色一次分析这最多八只并分别产出意见。第九名以后仍保留确定性候选，但持久标记 NOT_SELECTED_LIMIT，不伪装成等待讨论。工具回传和输出续写可以形成有记录的后续模型请求，但不能把八只候选放大成 32 个初始角色调用。
5. AI 讨论只提供研究意见，不能增删候选、改变排序、改变动作、绕过硬门禁或拖延手动扫描响应。
6. 模型可自行决定是否调用受控联网搜索与历史讨论搜索工具；应用负责实际执行工具。
7. 讨论的原始响应、结构化意见、工具调用、证据和错误全过程落库。
8. 同一股票的所有历史讨论可查看，新讨论必须指向父讨论并说明观点变化。
9. 不设置业务层文字数、段落数或结论长度上限；用时间预算、工具轮数和异常续写保障完整性。
10. 实施采用多 Agent 分阶段开发和独立对抗复核；发现 Critical 或 Important 问题即驳回，任何代码变更都会使旧审查结论失效，修复并重新完成两类复审后才能进入下一阶段。
11. AI 讨论全链路完成后，短线结果视图默认展示“今日资金去向”，移除“扫描快照”结果卡及其显示开关；旧 scheduled snapshot 只读保留用于历史审计，不再作为新 AI 触发源。
12. 取消所有短线自动调度：14:30 预选、14:47 尾盘最终和 14:49:50 就绪检查均不再注册或补跑；短线候选只由用户主动手动扫描生成，AI 只由成功手动结果或用户继续讨论触发。

本规格中“多角色讨论”的产品定义是四名角色对同一批快照并行独立审阅、页面并列呈现分歧，再由确定性真值表聚合；首版不让模型角色互相自由追问或多轮辩论。这样既保留多维视角和全过程，也把时延、费用与截止风险固定住。

## 4. 目标

### 4.1 短线规则目标

- PE、PB、估值折价、估值语境和估值参考不参与短线预选、复核池顺序、最终排序、动作、风险扣分或解释。
- 仅改变 PE/PB 输入时，新版本短线候选、排名、得分、动作和理由保持不变。
- 市场热度、成交流动性和不追高承担预选排序，不削弱财务硬风险、ST、退市、权限、T+1、行情覆盖和点时门禁。
- 旧 V4 记录继续可反序列化和查看，但短线界面不再展示估值字段。

### 4.2 AI 讨论目标

- 对用户主动扫描出的短线候选进行多维度、短时、可追溯讨论。
- 优先判断隔夜持有到下一交易日的溢价条件、兑现路径和失效条件。
- 支持模型主动搜索可信公开资料，而不是由固定关键词替模型决定所有检索。
- 任一角色超时、异常或搜索失败时保留已完成内容并形成 PARTIAL 或 INCOMPLETE 结论。
- 应用重启后仍能查看历史，并能恢复尚可继续的队列任务。
- 前台收到讨论结果后可进入独立页面查看全过程与同股历史。
- 每个批次只有四个初始角色会话，避免候选数线性放大初始模型调用数量。
- 确定性扫描可合法返回超过八只；AI 只按规范化 ruleRank、symbol 稳定选择前八，其余候选明确显示“本批未纳入 AI（上限 8）”。

### 4.3 时间目标

- 手动扫描的确定性结果先独立完成持久认证并立即返回；AI materializer 只读取其已提交 source，四角色在后台并行。
- RESEARCH_ONLY 单角色目标 60 秒、硬超时 90 秒；尾盘 SAME_DAY_RESEARCH 为满足 14:49 前可见结果，使用更早的派发/发送/内容截止。
- 交易日手动结果只有在数据库时间 14:47:00 前完成确定性认证，并由 materializer 在该线前取得一次合法 materialization admission，才有资格尝试 SAME_DAY_RESEARCH；已 admission 的事务可能在 14:47 后才对其他事务可见，但四个 INITIAL 仍必须按实际 gate 时间在 14:47:20 前派发。未赶上任一线立即标 AI_DEFERRED_CUTOFF/INITIAL_DISPATCH_CUTOFF，未取得 admission 的 source 到 14:50 后才作为 RESEARCH_ONLY 处理。
- 尾盘角色最迟在 14:47:55 前从统一 AiEgressGate 取得 egress permit，为 14:48:30 内容接收截止留出 35 秒；从 14:48:45 起，读 API 即按不可逆截止投影返回完整、部分或超时状态，不等待 closer 写事务，因此 14:49 前一定有可解释状态。这里保证“截止后不再授权新外发”，不把 JVM/OS 在两条指令间可被暂停的事实伪装成“某毫秒后绝对零网络包”。
- 不再宣称某个毫秒之后数据库物理上“绝对零事务”。AI 表、连接池和 executor 与确定性扫描隔离；截止后提交的 AI 审计写不能改变 effectiveStatus、不能触碰确定性表，也不能阻塞手动结果的 14:49:40 两阶段认证。
- 系统没有用户操作时不自动生成或发布短线结果；用户在尾盘主动扫描时，确定性响应不等待 AI，仍按既有数据、点时和交易窗口门禁决定是否可执行。

### 4.4 结果视图目标

- “今日资金去向”对新用户默认可见。
- 现有浏览器从旧偏好版本迁移时只在迁移当次把“今日资金去向”设为可见，之后仍允许用户主动关闭。
- “扫描快照”不再出现在结果视图开关或结果摘要卡中。
- 不删除 short_term_scheduled_snapshot 的历史表或旧数据；停止新写入、停止前端轮询，并保留只读兼容 API，便于回滚和历史审计。覆盖率门禁继续由手动扫描 report 执行。

## 5. 非目标

- 不让 AI 直接荐股、扩大候选池或调整确定性排名。
- 不让 AI 生成或覆盖买入、加仓、卖出动作。
- 不把论坛、股吧、自媒体、匿名聚合站或未知域名作为证据。
- 不允许模型请求任意 URL、内网地址、文件地址或本机服务。
- 不建设通用聊天系统、向量数据库、知识图谱或长时间辩论室。
- 不把讨论历史塞进现有内存扫描任务。
- 不修改长期价投模块中的 PE/PB、估值纪律和估值页面。
- 不移除长期价投页面独立的“扫描快照”卡；本次新增要求只针对短线结果视图。
- 不增加新的短线自动补跑、自动预选或自动最终发布。
- 不删除全局 @EnableScheduling，也不误伤 ShortTermValidationOutcomeScheduler 或 TradeOutcomeScheduler；只移除 ShortTermScanScheduler 注册的 PRESELECT、FINAL、READINESS_GUARD 三项扫描任务。
- 不保证外部模型永不达到其物理上下文或输出上限；系统负责识别并续写，而不是把截断内容伪装成完整结论。
- 不承诺第三方模型调用的 exactly-once。供应商不支持幂等键时采用 at-most-once：请求发出后结果未知就停止自动重试并公开标记，避免静默重复收费。

## 6. 不可破坏的确定性边界

以下边界优先级高于所有 AI 输出：

1. 全市场行情覆盖率低于 95% 时，不得产生可执行结果。
2. 行情新鲜度、交易日、数据截止时间和点时顺序必须有效。
3. ST、退市、流动性、交易权限、T+1、严重财务风险和大盘风险闸门保持。
4. 普通股票只使用 14:45 至 14:49 已产生的分时完成尾盘确认。
5. 手动扫描把旧 scheduled store 中经过验证的 FINAL_PENDING/FINAL_READY 思路迁入新的 manual result 合同：可执行报告必须先 stage payload/hash，再由独立短事务认证；不再由 cron 创建、推进或做 14:49:50 自动就绪检查。旧 scheduled store 的写实现本身仍删除。
6. AI 结论不能写回候选的 rank、rankingScore、action、confidence、validationEligible 或 gateStatus。
7. AI 晚到不能回写或重新解释为当日可执行买点。

候选主结果与 AI 研究结果是两个并列读模型：

    手动确定性扫描 -> 两阶段持久认证结果 -> HTTP 返回
                         |
                         +-> AI 讨论队列 -> 研究意见与历史

前端可以在候选旁展示 AI 状态和观点，但不得把 AI 状态混入手动结果的 FINAL_READY 判定。

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
- 手动扫描和历史重放的当前版本路径；旧 scheduled 记录只读兼容。

### 7.3 兼容策略

- ShortTermScanRequest 后端可暂时接受 maxPe 与 maxPb，以兼容旧链接或旧客户端，但新版本忽略它们并在 API 文档中标为 deprecated。
- React 短线请求和配置表单不再发送或显示这两个字段。
- ShortTermCandidate、旧 JSON 快照和数据库中可保留原始 PE/PB 字段以保证历史反序列化，但新记录应置空或只留在不对外的原始行情快照。
- 新策略唯一权威版本常量固定为 short-term-emotion-v5。ShortTermWeightProfile、手动持久结果、参数指纹、ResearchHistory、Observation、TradeFeedback、RecommendationSource 和 AI batch 都引用同一常量；新记录不得继续硬编码 V4，旧 scheduled 行保持原版本只读。
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

- MANUAL_SCAN：手动扫描的持久化结果成功提交后触发；扫描 HTTP 请求不等待 AI。
- CONTINUATION：用户从一个已结束父轮次继续讨论，形成只含该股票的新批次。

source type 只定义 MANUAL_SCAN、CONTINUATION。阶段 S 删除 ShortTermScanScheduler、仅供它调用的 ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 及 shortTermScheduledExecutor bean；ShortTermScheduledSnapshotStore 拆成只读历史查询接口，生产 bean 不再暴露 prepare/claim/save/publish/runNow/expire 等 mutator。底层 ShortTermScheduledSnapshotRepository 不得继承 JpaRepository、CrudRepository 或其他暴露 save/delete/flush 的接口；它只继承 Spring Data 标记 Repository 并声明 SELECT 查询，无 @Modifying 方法。PRESELECT/FINAL/READINESS_GUARD 枚举与 DTO 只为旧行反序列化保留。ShortTermAutomationSettings 重命名为 ShortTermExecutionSettings，只保留手动路径仍使用的 finalDeadline、freshness 和 overnightRules；删除 enabled/zone/三个 cron/自动 scanRequest 配置与动态刷新入口，旧 cron property 即使存在也必须被忽略。应用启动时不注册 14:30/14:47/14:49:50 task，也不对历史 scheduled snapshot 自动补讨论。

扫描结果与 AI materialization 之间使用“持久源 + after-commit materializer”：

1. 手动扫描在接受 HTTP 请求时先提交持久化 PENDING manual result，再由 worker 原子改为 RUNNING/SUCCEEDED/FAILED；只有 SUCCEEDED 提交后才可能触发 AI。内存 job 只作为缓存，重启后由持久任务恢复，不能成为唯一事实源。
2. manual result 在首次提交时冻结 aiRequested、featureRevision 和 requestSemanticHash；以后开启 feature 不能追溯触发旧扫描。
3. after-commit 信号尝试创建 AI admission/batch；其异常不得回滚或改写确定性结果。
4. reconciler 只扫描 aiRequested=true 且已成功、尚无 admission 的 manual result，以及已持久化的 pending continuation，补建缺失批次；不扫描旧 scheduled snapshot。
5. AI 表、队列或 materializer 不可用时，确定性结果照常成功；前端根据 admissionStatus/reasonCode 显示 AI_MATERIALIZATION_PENDING 或 AI_UNAVAILABLE，不伪称已入队。
6. materializer 为 source 的全部确定性候选先写 candidate admission，再按 ruleRank ASC、symbol ASC 选前八为 SELECTED 并创建 round；其余写 NOT_SELECTED_LIMIT、roundId=NULL。前端不能让这些候选永久显示等待。

数据质量阻断、扫描失败或候选为空时，manual result 自身先保存 aiAdmissionReason=NOT_APPLICABLE；允许 AI 写入时可幂等镜像一条终态 source admission，发送截止区间则 14:50 后再镜像。无论镜像是否已经存在都不创建 batch、模型任务或费用，前端以 manual result 的冻结原因立即停止等待。

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
- TIME_AUTHORITY_UNAVAILABLE
- MATERIALIZATION_FAILED

advisoryUse：

- SAME_DAY_RESEARCH
- RESEARCH_ONLY

queueAdmission：

- ACCEPTED
- DEFERRED

执行器队列满不产生不存在的 QUEUE_REJECTED 终态。数据库 batch 保持 QUEUED，batch.queueAdmission=DEFERRED，稍后由 dispatcher 重试。前端在 batch/round/role 到达上述任一终态后停止相应轮询；FEATURE_DISABLED、BUDGET_EXHAUSTED、STORAGE_PRESSURE、TIME_AUTHORITY_UNAVAILABLE、NOT_APPLICABLE 与 MATERIALIZATION_FAILED 也是 source 终态。

reason 到 source 状态的映射唯一固定：feature 或模型配置在 source 提交时不可用为 FEATURE_DISABLED；数据阻断/空候选为 NOT_APPLICABLE；持久日预算不足为 BUDGET_EXHAUSTED；AI 表存储高水位为 STORAGE_PRESSURE；TimeAuthority capability 失败为 TIME_AUTHORITY_UNAVAILABLE；队列暂满不是 source 终态，而是 MATERIALIZED + batch QUEUED + queueAdmission=DEFERRED；materializer 数据库重试耗尽为 MATERIALIZATION_FAILED。

`status/executionStatus` 始终表示原始执行事实；`effectiveStatus` 是 outcome freeze 的截止投影，可为 COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED，但不是原状态机的一次转换。raw 终态不可覆盖，读页面在 effectiveOutcomeAt 后以 effectiveStatus 为用户结论、raw status 为审计信息。

状态转换合同：

| 实体 | 合法主转换 |
| --- | --- |
| Source | MATERIALIZATION_PENDING -> MATERIALIZED；或到 NOT_APPLICABLE/FEATURE_DISABLED/BUDGET_EXHAUSTED/STORAGE_PRESSURE/TIME_AUTHORITY_UNAVAILABLE/MATERIALIZATION_FAILED |
| Batch | QUEUED -> RUNNING；QUEUED/RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Round | PENDING -> RUNNING；PENDING/RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Role | QUEUED -> CLAIMED -> RUNNING；QUEUED/CLAIMED/RUNNING -> TIMED_OUT/CANCELLED；RUNNING -> COMPLETED/INCOMPLETE/FAILED/OUTCOME_UNKNOWN |

终态不可自动回到运行态。OUTCOME_UNKNOWN 只能由用户显式发起“继续讨论”创建新的 batch，原 role session 和原事件保持不可变。

executionStatus 的派生使用以下有序且穷尽合同，避免各端自行解释：

- Role 正常得到 batch 中每个 symbol 的可解析结构化结果才是 COMPLETED；正常返回但结构化不全、输出上限或资源上限为 INCOMPLETE；任何 deadline coordinator 从 QUEUED/CLAIMED/RUNNING 收口均为 TIMED_OUT；已发送结果未知为 OUTCOME_UNKNOWN；发送前不可恢复错误为 FAILED；显式用户/运维取消且尚未 DISPATCHED 为 CANCELLED。
- Round 先判断是否仍有 role 非终态：四个均为 QUEUED 时是 PENDING，只要任一已经 CLAIMED/RUNNING 或已有终态就是 RUNNING。全部 role 终态后，再判断完整且 validated opinion：四份齐全为 COMPLETED；一至三份为 PARTIAL。若为零，再按顺序判断：任一 role 为 OUTCOME_UNKNOWN、INCOMPLETE，或存在无法归属到 symbol 的 raw chunk，为 INCOMPLETE；四个 role 全部 TIMED_OUT 为 TIMED_OUT；四个全部 FAILED 为 FAILED；四个全部 CANCELLED 为 CANCELLED；其他任意混合终态（例如 FAILED+TIMED_OUT、OUTCOME_UNKNOWN+FAILED、CANCELLED+TIMED_OUT）统一为 INCOMPLETE，并保存 MIXED_TERMINAL_STATES。
- Batch 在 role 非终态时，四个均为 QUEUED 才是 QUEUED；任一 role 已 CLAIMED/RUNNING 或已有终态就是 RUNNING。全部 role 终态后，全部 round COMPLETED 为 COMPLETED；任一 round 为 COMPLETED/PARTIAL 且非全完成为 PARTIAL；否则若全部 round TIMED_OUT/FAILED/CANCELLED 中同一种则取该状态；其他所有终态组合为 INCOMPLETE。空 round batch 禁止创建。
- advisoryState 始终按第 10 节逐股票计算，与 executionStatus 正交；例如 executionStatus=PARTIAL 的 round 仍可能因合格反方证据得到 RISK_ALERT。

### 8.3 队列、角色会话与执行器

- 一个有界 dispatcher 负责 materialization 和 batch 编排。
- 每个 batch 固定创建四个 role session；四个初始 session 并行运行，每个 session 一次分析 batch 内最多八只候选。
- 每个 role session 的结构化输出必须包含 batch 中每个 symbol 的独立 opinion；缺失 symbol 记为该候选的 INSUFFICIENT，不补开第五个初始会话。
- 模型网络调用期间不持有数据库锁或事务。
- 每个 role session 有 claimOwner、claimToken、单调 claimEpoch、leaseUntil 和 heartbeatAt。
- 认领后的心跳、普通审计事件以及所有 outcome-affecting chunk/evidence/opinion/终态写入都必须以 roleRunId + claimToken + claimEpoch + RUNNING 作为 fencing 条件；outcome-affecting 写还必须通过第 15.19 节 content ledger。旧租约迟到写入受影响行数必须为零。
- deadline coordinator 只服务于已有的用户触发 batch，不会自行扫描市场。guardNow >= roleContentDeadline 时它把尚未终止的 role 投影为 TIMED_OUT，取消流式 socket/读取器，并以短审计事件记录 CONTENT_DEADLINE_REACHED 和供应商取消结果。每个完整有界 chunk 只有在 content ledger 锁内以有效 health proof 重新取得的 guardNow 严格小于 roleContentDeadline 时才算“已接收”并随同 contentCommitNo 短事务持久化；精确等于或晚于截止的回调字节不保存正文，只增加 discardedLateBytes 计数。不使用 1 MiB 之类未公开的迟到内容截断 buffer；这是公开的时间截止，不是业务文字长度限制。
- 统一 TimeAuthority：PostgreSQL 原始时间使用 `clock_timestamp()`；H2 注册非 DETERMINISTIC 的 `APP_WALL_CLOCK_NOW()` Java alias，每次调用系统 UTC wall clock。它不是一次启动 probe，而是每实例持续健康租约：启动/恢复必须取得至少两次、间隔不小于 100ms 的严格推进样本；之后 watchdog 每 250ms 内重新采样，任一回退、连续不推进、数据库往返超过 250ms、与 `System.nanoTime()` 单调间隔偏差超过 250ms，或最后健康样本超过 750ms 都立即使本实例 health=false。一次失败后必须重新取得两次合格推进样本才能恢复，不能用单个正常值抹掉故障。watchdog 只检查时钟能力，不扫描市场、不创建 source/batch、不调用模型或搜索，不属于短线自动任务。
- 每个硬门禁事务必须同时携带未过期的 `TimeAuthorityHealthProof`，并在执行 SQL 前读取一次 raw database time；门禁时间 `guardNow=max(rawDbNow, proof.sampledDbNow + (System.nanoTime()-proof.sampledNano))`，向上取整到微秒。proof 保存 instanceId、sampledDbNow、sampledNano 和 expiresAfter=750ms，只能在同一 JVM 实例使用；`System.nanoTime()` 只计算差值，不持久化/跨重启比较。raw 时间小于 proof 样本、proof 已失效或 watchdog 已标坏时钟时，事务 fail closed。PostgreSQL 使用 `GREATEST(clock_timestamp(), :guardNow)`，H2 使用等价 `CASE`，不能只比较可能冻结的数据库值。materialization、INITIAL/continuation/tool/旧付费 dispatch、AiEgressGate、content ledger、seal、CONTENT_SNAPSHOT 准入、fallback、manual 事务 B 认证和 `executionEligibility` 全部使用这一合同。仍不宣称两条 CPU 指令之间绝对不会暂停；单调外推的作用是数据库时间冻结/倒退后不延长任何业务窗口，proof 新鲜度失效则立即拒绝。
- health=false 时关闭所有新的 AI admission materialization、INITIAL/continuation/tool/旧付费 dispatch，包括 RESEARCH_ONLY。manual 的行情计算和报告持久化可以完成，但不得生成新的 FINAL_READY：待认证报告原子收口为 `DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE`；已存在 FINAL_READY 只保留历史认证事实，读 API 的 `executionEligibility=TIME_AUTHORITY_UNAVAILABLE`，绝不能返回 ELIGIBLE。已有非终态 AI batch 不再尝试 transport，而由 capability-failure closer 以普通审计时间插入无内容 SYSTEM_FALLBACK/TIME_AUTHORITY_UNAVAILABLE，batch/round/advisory 均为 INCOMPLETE；读 API 在 health=false 时不比较坏时钟与 effectiveOutcomeAt，而是立即返回该 fallback。若数据库本身不可写则返回 503，绝不永久返回 PENDING。坏 proof 下不得续办该 source/batch；health 恢复后，已有 TIME_AUTHORITY_UNAVAILABLE source/batch 也不自动复活，用户须重新扫描或明确继续讨论。H2 CURRENT_TIMESTAMP 和 PostgreSQL 事务级 CURRENT_TIMESTAMP 均禁止用于 deadline。
- H2 文件模式只支持一个 API 实例；多实例运行必须使用 PostgreSQL。跨实例 CAS、数据库租约和多 owner fencing 只在真实 PostgreSQL 上宣称成立。
- AI 使用独立固定最大 2 连接的小连接池和独立有界 executor；确定性 manual result 使用另一连接池并保留连接。外部网络等待不占数据库连接，AI 事务不得查询 FOR UPDATE、更新或持有 short_term_scheduled_snapshot、manual result 及任何确定性认证表的锁。
- AI schema FK 只指向 AI 表或保存 deterministic source key 值，不从 AI 表对确定性表建立 FK/级联/锁传播。即使 AI 表被故意长锁，确定性连接池和表写入仍必须前进；若 H2/PostgreSQL 实测不能满足，该环境的全部 AI 启动 capability 必须 fail closed，而不能降低手动扫描门禁。
- roleContentDeadline 到达后，deadline coordinator 必须先按第 15.19 节封闭四条 content ledger；只有四条均已 sealed 才能在 freezePublishAdmissionDeadline 前按第 15.20 节 canonical `OutcomeBasisV1` 取得 CONTENT_SNAPSHOT SQL 准入。basis 只读取 contentCommitNo <= 对应 acceptedThroughCommitNo 的 validated opinion/evidence/chunk；model chunk 还必须满足 receivedAt < roleContentDeadline。terminalContentCommitNo 落在封存范围内的角色原始终态才被承认，其他状态只在 freeze 中投影为 TIMED_OUT。精确等于边界不纳入。内容 freeze 只用带 SQL 内 guardNow 谓词的单语句 autocommit INSERT；并发唯一键冲突仅在核验 constraint 名称后重读赢家。effectiveOutcomeAt 时仍无已提交 freeze 行，读 API 只能同样单语句尝试插入无内容 SYSTEM_FALLBACK，不得重读 AI 内容生成内容型 freeze。若它被一个尚未决议的唯一键事务阻塞并触发 lock/statement timeout，则返回 503/PERSISTENCE_UNAVAILABLE，不能先显示 PENDING 或臆造 fallback；事务决议后只读取唯一已提交赢家。
- 所有 materialization、budget/reservation、event、chunk/content、evidence、opinion、heartbeat 和终态 native SQL 仍带 TimeAuthority health/guardNow、claimToken/epoch 和状态谓词；但不再声称 beforeCommit 能证明实际 JDBC commit 时刻。事务 timeout、lock timeout 和独立 outcome freeze 是资源/可见性边界，确定性隔离依赖独立池/表而不是不可实现的“某毫秒后零事务”。
- SAME_DAY_RESEARCH 常量固定：sourceCertificationCutoff=14:47:00、materializationAdmissionCutoff=14:47:00、initialDispatchDeadline=14:47:20、latestEgressPermitAt=14:47:55、roleContentDeadline=14:48:30、freezePublishAdmissionDeadline=14:48:44、effectiveOutcomeAt=14:48:45。资格只看已提交 MANUAL_SCAN 的 deterministicStatus=FINAL_READY、manual.certifiedAt=manual.deterministicFinalizedAt=admission.sourceEffectiveAt 且该值 < sourceCertificationCutoff；缓存/研究预览只有 sourceEffectiveAt，没有伪造的认证时间。所有 advisoryUse 的 materializer 必须在短事务内取得有效 health proof，并以同一次 guardNow 严格小于 materializationAdmissionCutoff 才能准入；guardNow 位于当前本地自然日 [14:47:00,14:50:00) 的新 materialization 一律不准入。14:47 前已准入的事务即使因调度/提交延迟在 14:47 后才可见，也只消耗原有 deadline，绝不重置 materializedAt；超过 initialDispatchDeadline 尚无 INITIAL DISPATCHED 的 role 以 INITIAL_DISPATCH_CUTOFF 终止且不收费。
- batch.materializedAt 是 materializer 事务取得准入时的同一次 guardNow，不声称等于 JDBC commit 可见时间；四个 role 复制同一基准。RESEARCH_ONLY 若 materializedAt 的 Asia/Shanghai 本地时刻 `<14:47:00`，五条时间统一使用该本地自然日尾盘封顶公式：initial=`min(materializedAt+15s,14:47:20)`、permit=`min(materializedAt+75s,14:47:55)`、content=`min(materializedAt+105s,14:48:30)`、publishAdmission=`min(materializedAt+119s,14:48:44)`、effective=`min(materializedAt+120s,14:48:45)`；上限未命中时自然等于相对值。本地时刻 `>=14:50:00` 时使用不封顶的相对 +15/+75/+105/+119/+120 秒；黑窗内不可能取得新的 materialization admission。SAME_DAY_RESEARCH 还必须满足 materializedAt 的本地日期等于 manual.tradeDate 且为交易日，否则降为 RESEARCH_ONLY，防止次日恢复把旧认证冒充为同日结论。INITIAL DISPATCHED 时再写 sessionDeadline=min(startedAt+90 秒, roleContentDeadline)。所有后续发送同时要求 guardNow<latestEgressPermitAt 且 guardNow<sessionDeadline。
- AiEgressGate 是所有模型、Tavily、direct fetch 和旧付费 HTTP 的唯一 transport 入口。reservation+DISPATCHED 提交不是 permit；每次尝试都进入一个独立短事务。discussion operation 按 role_run -> operation 固定顺序 PESSIMISTIC_WRITE，paid operation 只锁 operation；取得锁后才用有效 health proof 取得 guardNow，并同时验证 `deliveryStatus=DISPATCHED`、`transportHandoffAt IS NULL`、claimToken/epoch/cancel/recoveryMode=EXECUTION 有效且 `guardNow < latestEgressPermitAt`。唯一赢家写 transportHandoffAt=该次 guardNow 并提交，提交成功后才在同一同步调用栈立即调用 transportClosure。并发零赢家重读：若 marker 已非空返回 ALREADY_HANDED_OFF，不发送、不释放 reservation、不改 marker；若 marker 仍空但截止/取消/feature/time health 失败，则唯一拒绝事务同时把 operation 写 FAILED_BEFORE_SEND，并把对应 RESERVED reservation 写 SETTLEMENT_PENDING，缺行/状态不匹配则全部回滚。后续 BudgetSettlementReconciler 按 GLOBAL -> ACTOR -> BATCH -> reservation 的预算锁序幂等结算为零；gate 不在持有 operation 锁时反向锁预算计数。任何第二个实例都不得调用 closure。这些步骤不得拆回 dispatcher、不得主动排队或使用 sendAsync 延后 supplier。
- transportHandoffAt 是 at-most-once 的保守不可重发边界，不声称是网络卡首字节的物理时戳；若 JVM 在 marker 后被 OS/GC 暂停，恢复后调用可能晚于 permit deadline，系统只保证没有第二个赢家，其返回内容受 roleContentDeadline/content seal 排除。旧付费入口在创建 operation 前从有效 health proof 取得 admittedAt=guardNow：本地时间落入 [14:47:55,14:50:00) 时返回 503 AI_EGRESS_CUTOFF 且不插行；其他时段创建 operation，并按第 15.21 节公式持久化 latestEgressPermitAt。若截止前已有 operation 但 gate 后来拒绝，则 FAILED_BEFORE_SEND_CUTOFF，原 key 不可续办。
- 截止读模型不等待 role-row closer：当 guardNow >= effectiveOutcomeAt，API 使用独立 freeze 表按第 8.2 节真值表固定 effectiveStatus/outcomeBasisHash。freeze 聚合器只使用投影后的 effective role/batch 状态：截止时仍非终态、或原始 OUTCOME_UNKNOWN 没有封存 terminalContentCommitNo 的 role 在 basis 中视为 TIMED_OUT，不使用原行状态计算 advisory。raw `status` 与 frozen `effectiveStatus` 是两个字段：closer 以后只可把同一 effectiveStatus/hash/fixedAt 复制到 batch/round/role 的独立投影列，绝不把 OUTCOME_UNKNOWN 改写成 TIMED_OUT，也不覆盖任何原始终态；若 role 表被锁住，freeze 表仍给页面提供 14:49 前的完整/部分/超时结论。任何 SYSTEM_FALLBACK 的 batchEffectiveStatus、roundStatus、advisoryState 均固定 INCOMPLETE 且不得出现 PENDING；正常截止漏冻使用 CUTOFF_FREEZE_MISSED，时钟能力失败使用 TIME_AUTHORITY_UNAVAILABLE。若连独立 freeze 插入也因数据库整体不可用或未决唯一键事务而失败，HTTP 返回 503/PERSISTENCE_UNAVAILABLE，绝不继续显示“运行中”。CONTENT_SNAPSHOT/SYSTEM_FALLBACK 仍是业务结果发布；watchdog、deadline closer、budget settlement 和 retry reconciler 只收口用户已创建事实，不得在无用户 source 时生成任何短线结论。
- AI PostgreSQL session 固定 lock_timeout=250ms、statement_timeout=500ms、idle_in_transaction_session_timeout=1s；H2 AI datasource 固定 LOCK_TIMEOUT<=250ms、查询超时<=500ms；AI 写事务 timeout<=1s。Stage E 必须用 H2 真事务、PostgreSQL content-ledger 锁竞争、AI 表长锁和 dispatcher 暂停证明：截止后 content gate 拒绝正文写入，封存范围和 effectiveStatus 不随 coordinator 时序改变，且手动确定性认证仍可获得连接和锁。

### 8.4 付费调用 at-most-once

每次物理模型请求在发送前创建不可变 invocation：

1. 事务写入 PREPARED，包含稳定 invocationId、roleRunId、invocationNo、callKind、requestHash 和 provider 设置快照。
2. 一个短事务按 GLOBAL -> ACTOR -> BATCH 固定顺序预占预算、插入以 MODEL_INVOCATION + invocationId 唯一的 usage reservation，并以 CAS 把 PREPARED 改为 DISPATCHED；三者必须同事务提交，任何一步失败全部回滚。存在 reservation 的 PREPARED 恢复只读取原 reservation，绝不二次预占。DISPATCHED 表示“已保守计费并获准尝试”，不等于物理请求已经发送。
3. 进入唯一 transport 前执行第 8.3 节 AiEgressGate；只有 marker-null 单赢家提交 transportHandoffAt 后可调用 HttpClient。因截止/取消且 marker 仍空的唯一拒绝者才在同一事务把 DISPATCHED -> FAILED_BEFORE_SEND、reservation RESERVED -> SETTLEMENT_PENDING；BudgetSettlementReconciler 再以固定预算锁序结算为零。ALREADY_HANDED_OFF 输家既不发送也不释放。收到响应后写 RESPONSE_RECEIVED，并流式/分块保存内容，最终转为 PERSISTED。
4. 本版本不实现自动 provider-status 查询，也不以相同幂等键重新 POST/生成。transportHandoffAt 已存在而本地结果无法确认时，无论供应商是否宣称接受幂等键，都由 UNKNOWN_FINALIZATION_ONLY 收口；未来若加入只读状态查询，必须另建无生成副作用的 operation/gate 合同。
5. 供应商不支持幂等键时，PREPARED 或 DISPATCHED 但 transportHandoffAt 为空且 egress gate 未放行的操作可安全收口为 FAILED_BEFORE_SEND；transportHandoffAt 已存在而结果无法确认才标 OUTCOME_UNKNOWN，绝不自动重发。
6. 本版不在原 role session 上提供重试。用户只能通过“继续讨论”创建新的 batch 和四个新 role session，原 OUTCOME_UNKNOWN 记录保持不可变并明确提示会产生新费用。

callKind 为 INITIAL、TOOL_CONTINUATION 或 OUTPUT_CONTINUATION。invocationNo、toolRoundNo 与 outputSegmentNo 分开存储，不能复用 attempt_no 表示多个概念。现有 LlmChatClient 的 IOException 自动重试逻辑不得直接复用于本功能。

模型工具调用也采用同一 at-most-once 原则。每个服务器接受的原生 tool_call 先生成稳定 toolCallId；每次物理 Tavily/direct adapter 请求创建 short_term_ai_tool_execution，状态为 PREPARED、DISPATCHED、RESULT_RECEIVED、PERSISTED、FAILED_BEFORE_SEND 或 TOOL_OUTCOME_UNKNOWN。预算 reservation 与 PREPARED -> DISPATCHED 必须在同一事务。恢复使用同一三分法：PREPARED 可恢复；DISPATCHED 但 transportHandoffAt IS NULL 时只读原 operation/reservation 并竞争 AiEgressGate 单赢家；transportHandoffAt IS NOT NULL 且结果未知时 TOOL_OUTCOME_UNKNOWN，绝不再发生成/搜索请求。direct fetch 虽不按次收费也使用该账本，避免恢复时重复外部副作用。每个 toolExecutionId、requestHash 和 reservation 永久可追溯。

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
- marketRegime、marketRegimeFingerprint、marketCoverage、quoteFreshness；
- 最多八个 AiCandidateSnapshotV1。

AiCandidateSnapshotV1 固定包含 symbol、name、industry、ruleRank、ruleAction、setupScore、rankingScore、latestPrice、changePercent、amount、volumeRatio、hotDirection、relativeStrength、technicalStructure、tailSignal、financialHardRiskFlags、ruleEvidenceIds、marketRegimeFingerprint、snapshotHash 和 discussionMaterialHash。

discussionMaterialHash 的输入不是模糊子集，而是下列字段组成的 DiscussionMaterialV1：schemaVersion、strategyVersion、symbol、industry、ruleRank、ruleAction、setupScore、rankingScore、latestPrice、changePercent、amount、volumeRatio、hotDirection、relativeStrength、technicalStructure、tailSignal、financialHardRiskFlags、ruleEvidenceIds、marketRegimeFingerprint。字符串 trim，symbol 大写；BigDecimal 去无意义尾零后用普通十进制字符串；集合去重并按代码点升序；对象键按 JSON Canonicalization Scheme 固定；null 与缺失按 Schema 区分，最终对 UTF-8 canonical JSON 做 SHA-256。marketRegimeFingerprint 同样对批次的 regime code、label/version 和影响讨论的宽度/风险枚举规范化后做 SHA-256，并复制进每只候选 material，保证市场状态变化能触发候选变化。

snapshotHash 覆盖完整白名单 AiCandidateSnapshotV1（hash 字段本身除外）以及 dataCutoffAt、name、marketCoverage/quoteFreshness 引用版本，用于精确审计。discussionMaterialHash 明确排除 dataCutoffAt、name 等纯审计元数据。两种 hash 都排除 maxPe、maxPb、peTtm、pbRatio、valuationContext、valuationScore、API Key 和任意完整 ShortTermCandidate 序列化。

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

固定优先级和真值表。在 guardNow < effectiveOutcomeAt 的活跃读模型中读当前持久状态；创建/读取 outcome freeze 时只读第 8.3 节投影后的 effective 状态，所有原始非终态 role 已视为 TIMED_OUT：

1. 仅当 guardNow < effectiveOutcomeAt，batch 尚未终止且任一 role session 仍非终态时：PENDING。effectiveOutcomeAt 起 PENDING 不是合法 freeze 值。
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
3. 工具开始时用有效 health proof 取得 immutable observedCutoffAt；将 publishedTo 截断到 observedCutoffAt。所有 advisoryUse 都只在 `observedCutoffAt < min(role.sessionDeadline, role.latestEgressPermitAt)` 时接受逻辑 tool call 并预占配额；否则直接 TOOL_REJECTED/AI_EGRESS_CUTOFF、零 logical counter 增量、零物理请求；
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

Tavily 的日期过滤只有自然日粒度。只有 topic=news、返回精确可解析 publishedAt、且 publishedAt <= 该次工具 observedCutoffAt 的结果，才可能成为 SAME_DAY_RESEARCH 的 pointInTimeEligible 证据；该工具还必须满足 `observedCutoffAt < min(role.sessionDeadline, role.latestEgressPermitAt)` 并通过本地 registry。general/finance、时间缺失、政府页或公司 IR 的 Tavily 结果只能 RESEARCH_ONLY，不影响同日 advisoryState。公告、交易所与政策的同日点时证据必须来自 direct adapter 的权威时间。

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
- batch、actor=local-operator、Asia/Shanghai 自然日三层预算持久化到数据库，默认每批最多 16 个模型 invocation、8 个 evidence tool call、4 个 history tool call、8 个 Tavily 请求、16 个 direct fetch；每日本地手动批次最多 20、模型 invocation 最多 400、evidence tool call 最多 160、history tool call 最多 80、Tavily 请求最多 160、direct fetch 最多 320。预算可从服务器配置文件收紧，不能由模型或浏览器扩大。

## 12. 历史讨论搜索与延续

### 12.1 股票线程

每个六位股票代码只有一个持久化 discussion thread。每次手动扫描讨论或手动继续创建不可变的新 round。

- 初次讨论 parentRoundId 为空。
- 用户点击“继续讨论”时必须指定当前查看轮次为父轮次。
- MANUAL_SCAN 在确定性终态事务中冻结 parentResolutionAt=source.deterministicFinalizedAt；其父轮必须满足同股、终态且 completedAt <= parentResolutionAt，再按 createdAt DESC、roundId DESC 唯一选择。只是在该时点前创建、但之后才结束的 round 不合格，之后完成的新 round 不能改变选择。
- CONTINUATION 必须使用 sourcePayload 中请求指定的不可变 parentRoundId，materializer 禁止替换成“最新轮”；父轮若被发现不满足同 symbol/thread/已终态合同则 MATERIALIZATION_FAILED。
- CONTINUATION 的候选确定性快照、ruleAction/rank/hash 与 dataCutoffAt 复制指定父轮，不在后台偷偷重跑行情或改用最新手动结果；新联网证据有独立 evidenceCutoffAt。若用户需要新行情基础，必须重新手动扫描形成 MANUAL_SCAN round。
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
- RoleOpinionsV1 解析器从已落库 chunk 的有界流增量读取，逐 opinion/field/item 校验并写入 opinion_content_chunk；不得先 join 为一个 String、在内存构造整个 opinions 树或把数十 MiB 结构化正文复制进 event/opinion 单行。解析工作内存上限和临时缓冲策略在 Stage 0 固定，资源耗尽按 INCOMPLETE_RESOURCE_LIMIT 收口并保留全部已接收 raw chunk。
- API 不对子字段 substring，不追加省略号，不只返回摘要替代详情。
- UI 默认可折叠以保持可读性，但“展开全部”必须遍历有界分页直至真实结尾并显示完整内容。
- 讨论调用使用版本化 LlmProviderCapabilities 的 provider/model 最大输出能力；供应商允许省略时省略 max_tokens，必须设置时使用经验证最大值。现有通用配置中的较小展示/旧默认值（例如 8192）不作为本讨论的业务截断值；effective provider limit 进入 role settings 快照并在页面可追溯。

供应商物理上限和时间截止仍然存在：

- finishReason=length 时保存当前完整原始片段并标记 INCOMPLETE。
- 时间预算允许时最多发起一次同角色续写，携带上一片段和结构化缺失字段。
- 续写内容单独作为事件保存，合并时保留片段边界。
- 仍未完成时展示“模型输出达到上限，内容不完整”，不得伪装为 COMPLETED。
- 90 秒、该 role.sessionDeadline 或 roleContentDeadline 中最早到达者优先于继续等待；SAME_DAY_RESEARCH 另受 14:47:55 最后 egress permit 和 14:48:45 结果冻结限制。超时主动取消 provider 流，不删除截止前已持久内容。

“不做业务截断”不等于无限内存或无限请求。运输安全边界固定为：

- continuation question 最多 8,000 个 Unicode 字符；
- 工具 query 最多 512 个字符，JSON 嵌套深度最多 32；
- 单个供应商 HTTP 响应最多接收 32 MiB，单 batch 原始模型内容最多 256 MiB；
- 持久化时按 UTF-8 代码点边界切成不超过 64 KiB 的 chunk，绝不切坏字符；
- 达到运输上限时停止接收，保留此前所有 chunk，并以 Role status=INCOMPLETE、errorCode=INCOMPLETE_RESOURCE_LIMIT 收口，不做 substring 后伪装完整；
- 新客户端不能使用 BodyHandlers.ofString 先把不受控响应全部读入内存；每个完整有界 chunk 须在继续读 provider stream 前经 content ledger 反压持久；
- 输入/运输限制保护资源，不限制已经合法接收的研究论述在数据库和详情页中的显示。

原始历史不自动删除。feature 关闭映射 FEATURE_DISABLED，达到 AI 存储高水位映射 STORAGE_PRESSURE，materializer 数据库写重试耗尽映射 MATERIALIZATION_FAILED；普通队列压力只保持 batch QUEUED + DEFERRED。所有情况确定性扫描继续，已有历史仍可读取。

## 14. 点时与手动扫描时间

### 14.1 无自动短线任务与 AI 截止投影

应用不再注册 14:30、14:47、14:49:50 短线 cron，也不做错过后的 catch-up。下面的 coordinator/dispatcher/reconciler/watchdog 只处理某次用户操作已经持久化的 source 或基础设施健康/预算结算，不会主动扫描市场、创建候选或发起网络调用。时区固定 Asia/Shanghai，时间来自第 8.3 节 TimeAuthority：

| TimeAuthority 区间 | AI 行为 | 手动确定性行为 |
| --- | --- | --- |
| [00:00:00, 14:30:00) | 用户 source 可按 RESEARCH_ONLY 运行 | 仅点击时扫描；不自动产生候选 |
| [14:30:00, 14:47:00) | 已认证合格 manual source 可尝试 SAME_DAY_RESEARCH；四角色并行 | manual report 完成两阶段认证后先返回 |
| [14:47:00, 14:47:20) | 不准入任何新 materialization；14:47 前已准入的 SAME_DAY/RESEARCH_ONLY batch 即使稍后才可见，仍仅可在各自 initialDispatchDeadline 前完成 INITIAL | 晚到或积压 source 冻结为 14:50 后 RESEARCH_ONLY |
| [14:47:20, 14:47:55) | 未派发 INITIAL 以 INITIAL_DISPATCH_CUTOFF 收口；已运行 role 可在预算内为工具/续写申请 egress permit | 手动扫描不等待 AI |
| [14:47:55, 14:48:30) | AiEgressGate 不再授予新 permit；已完成 transport handoff 的响应仍可以反压流式入库，只接受 receivedAt < roleContentDeadline 的内容；旧付费入口不创建新 operation | 手动扫描不等待 AI |
| [14:48:30, 14:48:44) | 取消流式 transport，封闭四个 content ledger；全部封闭后生成 canonical OutcomeBasisV1，并在严格早于 14:48:44 时取得单语句 autocommit 发布准入 | 手动扫描继续独立认证 |
| [14:48:44, 14:48:45) | 不再接纳新的 CONTENT_SNAPSHOT SQL；此前已准入语句最多使用 500ms 排空，随后只等待已提交赢家 | 手动扫描继续独立认证 |
| [14:48:45, 14:50:00) | API 读取或尝试创建独立 outcome freeze，并返回不可逆完整/部分/超时状态；若唯一赢家事务未决则 503，closer 可幂等复制同一 hash；旧付费路由保持 503 | 手动结果继续按 14:49:40 门禁认证，不受 AI 锁/连接影响 |
| [14:50:00, 次日) | 当前不在黑窗且 `guardNow>=nextRetryAt` 的延后 source 只以唯一 CAS materialize 一次 RESEARCH_ONLY；新用户 source 可立即 RESEARCH_ONLY | 仅点击时扫描；AI 不得创建当日买点 |

14:48:45 是“用户可见 AI 结论已固定”的 SLA，不是虚假的 JDBC commit 屏障。freeze 只纳入四个已封闭 content ledger 中 contentCommitNo<=acceptedThroughCommitNo 的内容，MODEL_CHUNK 还必须 receivedAt<14:48:30；未通过 content gate 或未进入封存范围的写不在 basis 内。14:48:44 是 CONTENT_SNAPSHOT SQL 准入线，不是 commit 可见线：此前已准入的语句允许最晚在 14:48:44.500 前可见；14:48:45 才尝试 fallback。若数据库在 fallback 的 500ms timeout 内仍无法决议唯一赢家，API 返回 503 而非 PENDING/fallback；恢复后只读最终已提交唯一行。读路径与最终 closer 使用同一纯函数和同一 basis hash。

没有用户手动操作时，系统不会自动生成、认证或发布短线结果。故障注入证明 AI 网络、AI 表锁、连接池、队列或恢复器不能阻塞同时发生的 manual result，而不是证明一个已取消的 cron 会运行。

### 14.2 手动讨论

手动扫描在确定性接口允许的时间均可触发；以下统一的 sourceDecisionAt 对 MANUAL_SCAN 指 manual result.deterministicFinalizedAt，对 CONTINUATION 指 admission.createdAt。FINAL_READY 的 deterministicFinalizedAt 与 certifiedAt 使用同一次 guardNow；CACHE_PREVIEW/RESEARCH_PREVIEW 在 health 正常时使用其一次性终态提交 guardNow，不伪造 certifiedAt。是否启动 AI 和 advisoryUse 使用以下有序穷尽矩阵，第一条命中即停止：

| source 提交时状态 | AI 行为 |
| --- | --- |
| 数据质量阻断、扫描失败或候选为空 | NOT_APPLICABLE，不创建模型任务 |
| MANUAL_SCAN 的 sourceDecisionAt 位于任意日期的 [14:47:00, 14:50:00) | 确定性结果按原门禁返回（health 失败仍为 DATA_BLOCKED）；冻结 aiRequested 与 AI_DEFERRED_CUTOFF，14:50 后仅对提交时 aiRequested=true 的 source 按 RESEARCH_ONLY materialize |
| CONTINUATION 的 sourceDecisionAt 位于任意日期的 [14:47:00, 14:50:00) | 返回 503 AI_EGRESS_CUTOFF 和 Retry-After；客户端保留同一 key/原请求，14:50 后仅重试该用户操作 |
| 交易日、非缓存、确定性状态 FINAL_READY、TimeAuthority health proof 通过，且 certifiedAt 在 [14:30:00, 14:47:00) | 只在 materializer 本地日期=manual.tradeDate 且 guardNow<14:47 时取得 SAME_DAY_RESEARCH materialization admission；否则按黑窗延期或 RESEARCH_ONLY。既有 batch 的 INITIAL 若未在 14:47:20 前派发，明确 INITIAL_DISPATCH_CUTOFF，不伪称运行中 |
| CONTINUATION 位于其他允许时段 | 立即 materialize，RESEARCH_ONLY |
| 交易日但提交时间在 [00:00, 14:30:00)，或报告是缓存/非执行预览 | 允许立即运行，但只能 RESEARCH_ONLY |
| 非交易日 | 允许立即运行，但只能 RESEARCH_ONLY |
| 任意日期 [14:50:00, 24:00) | 可立即运行，RESEARCH_ONLY |

上述“允许运行”还要求 source 提交时 aiRequested=true、预算/存储/配置和 TimeAuthority health proof 通过；materializer 本身也必须在准入事务重新判断 guardNow 不位于当前本地自然日 [14:47:00,14:50:00)。source 在 14:47 前形成但 materializer 到黑窗才运行时同样不得取得 materialization admission，nextRetryAt 按第 15.3 节推进到当前日 14:50:00。14:47:00 是“确定性结果已经认证/AI materialization 已准入”的资格线，不是点击时间或 commit 可见屏障；前端在 14:46 后明确提示“可能赶不上同日 AI”。只有扫描本身满足当日点时与执行门禁时，页面才可并列展示规则动作；AI 从不让研究预览变成可执行结果。

### 14.3 后续手动扫描与历史变化

取消自动预选/最终后，不再创建 ADDED、RETAINED_CHANGED、RETAINED_UNCHANGED、DROPPED transition 或 FINAL_SUPPLEMENT batch。每次成功手动扫描都是独立 source；最多前八只形成各自 round，并按第 12 节连接当时已经结束的父轮。discussionMaterialHash 用于页面和角色比较当前 round 与父 round 的实质字段变化，snapshotHash 用于精确审计；它们不触发第二个自动付费批次。

## 15. 数据模型与版本化合同

### 15.1 通用 SQL 约定

- 应用生成的主键统一 VARCHAR(36) UUID。
- symbol 为 VARCHAR(6)，hash 为 VARCHAR(64)，枚举为 VARCHAR(32) 并带 CHECK。
- 时间为 TIMESTAMP WITH TIME ZONE；普通审计时间可使用 CURRENT_TIMESTAMP，硬门禁、状态 CAS 和租约必须使用第 8.3 节的数据库 wall-clock 读取方式，不能在 PostgreSQL 长事务里使用冻结的事务级 CURRENT_TIMESTAMP。
- TimeAuthority adapter 的 raw time 与 guardNow 统一输出 UTC 微秒精度：H2 alias 在返回前向下截断到微秒，PostgreSQL 原生为微秒；所有派生 deadline/持久时间和 OutcomeBasisV1 时间沿用该精度，禁止 H2 纳秒与 PostgreSQL 微秒产生跨库 hash 漂移。
- 长内容在 canonical DDL 中使用 TEXT，不能 VARCHAR 截断。
- 所有外键默认 ON DELETE RESTRICT；讨论历史不可级联删除。
- 所有 mutable entity 带 BIGINT version NOT NULL DEFAULT 0。
- 必填列明确 NOT NULL；可空只用于尚未发生的时间、父轮次或错误。
- H2 文件库只支持单实例；PostgreSQL 承担多实例和真实并发合同。

阶段 0 必须在实现 B 前提交并审查：

- canonical schema.sql 的完整 DDL；
- AiCandidateBatchSnapshotV1、AiCandidateSnapshotV1、RoleOpinionsV1、OutcomeBasisV1 和两个工具/分阶段 execution plan 的 JSON Schema；
- OpenAPI 3 合同与 React 生成/契约类型；
- 状态枚举、CHECK、索引、FK 和错误码表。

以下结构是该冻结合同的最低要求，阶段 0 不能减少。

### 15.2 short_term_manual_scan_result

手动扫描不再只存在内存：

- result_id；
- actor_id；
- idempotency_key；
- request_hash，等同 request_semantic_hash；
- normalized_request_json，只保存当前策略实际生效字段；原始兼容 DTO 可另作不参与身份的审计记录；
- feature_revision；
- ai_requested，NOT NULL；只有请求经过可信代理身份且 feature 当时开启才为 true；
- ai_admission_reason；
- trade_date；
- result_status：PENDING、RUNNING、SUCCEEDED、FAILED、TIMED_OUT；
- deterministic_status：NOT_EVALUATED、FINAL_PENDING、FINAL_READY、NO_TRADE、DATA_BLOCKED、CACHE_PREVIEW、RESEARCH_PREVIEW；
- result_message；
- report_json；
- report_payload_hash；
- blocked_reasons_json，结构化稳定 reason code 数组；
- data_cutoff_at；
- deterministic_deadline_at；普通交易日固定 14:49:40；
- payload_committed_by_at、certification_attempted_at、certified_at；
- deterministic_finalized_at，只有确定性终态必填，是后续 sourceDecisionAt 的权威值；
- certification_epoch、certification_error_code；
- claim_owner、claim_token、claim_epoch、lease_until、heartbeat_at；
- created_at、started_at、completed_at、version；
- UNIQUE(actor_id, idempotency_key)；
- CHECK：FINAL_PENDING 必须有 report_json/hash/payload_committed_by_at、deterministic_finalized_at/certified_at 均为空，且 result_status=RUNNING；FINAL_READY 必须另有 certified_at 与 deterministic_finalized_at，两者相等且 result_status=SUCCEEDED；DATA_BLOCKED/NO_TRADE/CACHE_PREVIEW/RESEARCH_PREVIEW 为 SUCCEEDED 的确定性终态，deterministic_finalized_at 必填但 certified_at 必须为空；FAILED/TIMED_OUT/NOT_EVALUATED 不得伪造确定性终态或认证时间。

POST 在把手动扫描交给 worker 前先提交 PENDING 行；worker 用有效 health proof/guardNow、claimToken/claimEpoch 和 lease 的 CAS 认领为 RUNNING，心跳和 report 写入均受 fence。恢复器可以认领 PENDING 或租约过期且未超总扫描时限的 RUNNING；health 无效时仍可用普通审计时间把行情计算/报告持久化，但不得进入 FINAL_READY 认证。超过总时限原子标 TIMED_OUT。旧 worker 的迟到结果受影响行数必须为零，确定性终态不可覆盖。

manual result 不复用即将删除的 scheduled store，而是迁移其两阶段语义：

1. ShortTermFinalResultGate 的 manual 分支改为真实执行窗口合同，不再调用当前 `evaluateManual(... enforceDeadline=false)` 绕过尾盘认证。health 正常时，数据阻断/无候选/缓存/窗口外研究分别一次性落 DATA_BLOCKED、NO_TRADE、CACHE_PREVIEW、RESEARCH_PREVIEW，同一事务以 guardNow 写 deterministicFinalizedAt/completedAt，certifiedAt 保持为空；health 无效时可用普通审计时间完成非执行报告，但确定性状态必须 DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE，不得伪造点时 preview 或认证时间。
2. 对候选可执行报告，事务 A 写 canonical report_json、SHA-256、blocked reasons、dataCutoffAt、payloadCommittedByAt，并以 fence 将 NOT_EVALUATED -> FINAL_PENDING；result_status 仍为 RUNNING。
3. 独立 REQUIRES_NEW 事务 B 重新读同一 hash/epoch，并要求有效 TimeAuthority health proof 且 guardNow <= 14:49:30 的内部认证起始线，对该次 guardNow 同时写 certificationAttemptedAt/certifiedAt/deterministicFinalizedAt、FINAL_READY 与 SUCCEEDED。health=false/过期/回退/冻结时必须把 FINAL_PENDING 原子收口为 DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE，不能继续认证。14:49:30 给公开 14:49:40 截止保留 10 秒提交/响应裕量；事务 timeout<=500ms。API 只把已提交且 certifiedAt<=14:49:30 的行视为历史认证成功，是否当时仍可执行另看下述 executionEligibility。
4. 进程在 A/B 之间崩溃时，恢复器在 14:49:30 前可按同 hash/epoch 完成 B；到线后必须原子 FINAL_PENDING -> DATA_BLOCKED，reason=FINAL_CERTIFICATION_INTERRUPTED，不能把仅 stage 的 payload 恢复成 READY。
5. 事务 B 后线程暂停或响应丢失不会重复认证：同 epoch/hash 返回已持久结果；GET 在公开截止后发现 proof 缺失或不一致时 fail closed 为 DATA_BLOCKED。Stage E 用真实推进时间、A 后崩溃和 B 提交/响应丢失验证该负向语义。

`FINAL_READY` 是持久认证事实，不是永久可执行授权。读 API 另以有效 health proof/guardNow 计算 `executionEligibility=ELIGIBLE|EXPIRED|TIME_AUTHORITY_UNAVAILABLE`；只有认证 proof 完整、health=true 且 guardNow<=deterministicDeadlineAt 才是 ELIGIBLE，时钟证明无效优先返回 TIME_AUTHORITY_UNAVAILABLE。即使 JVM 在事务 B 最后 SQL 与 JDBC commit 之间被长时暂停，迟可见的 FINAL_READY 也只能作为历史审计，不会在 14:49:40 后重新变成当日买点。

reconciler 只为 ai_requested=true、result_status=SUCCEEDED、deterministic_status 属于 FINAL_READY/RESEARCH_PREVIEW/CACHE_PREVIEW 且候选非空的结果处理 admission；NO_TRADE、DATA_BLOCKED、扫描 FAILED/TIMED_OUT 和空候选永不收费。之后补 token/开启 feature 不能追溯改变 ai_requested。

requestSemanticHash 必须复用第 7.3 节的当前策略规范化器：先移除 deprecated maxPe/maxPb 和所有忽略字段，再按 Stage 0 canonical JSON 计算 SHA-256。同一 actor/idempotencyKey 且 semantic hash 相同返回原结果，即使旧客户端只改变 maxPe/maxPb；key 相同而实际生效字段 hash 不同才返回 409 IDEMPOTENCY_KEY_REUSED。内存 job 可以作为进度缓存，但 GET、恢复与讨论 sourceKey 都以 result_id 为准。

### 15.3 short_term_ai_source_admission

连接确定性持久源与 AI materializer：

- admission_id；
- source_type：MANUAL_SCAN、CONTINUATION；
- source_key；
- actor_id；
- normalized_request_hash；
- source_payload_json，字段白名单；CONTINUATION 保存 parentRoundId、question 和 questionFingerprint，其他 source 只保存重建引用；
- admission_status；
- advisory_use；
- reason_code、reason_message；
- source_effective_at，NOT NULL；MANUAL_SCAN 取 manual.deterministic_finalized_at，CONTINUATION 取 admission.created_at；
- source_certified_at，可空，仅 SAME_DAY_RESEARCH 必填且必须等于 source_effective_at；
- source_certification_cutoff、initial_dispatch_deadline，SAME_DAY_RESEARCH 必填；
- materialized_batch_id，可空；
- materialization_attempts、next_retry_at、deferred_window_date；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- INDEX(admission_status, updated_at)。

DDL CHECK 仅固定本表可证明的关系：advisory_use=SAME_DAY_RESEARCH 时 source_type=MANUAL_SCAN、source_certified_at/source_certification_cutoff/initial_dispatch_deadline 均非空且 source_certified_at=source_effective_at；RESEARCH_ONLY 时 source_certified_at/source_certification_cutoff 均为空。materializer 在同一事务重读 source_key 对应 manual 并校验 deterministicStatus=FINAL_READY/hash/proof，不得为缓存/非交易日预览伪造认证时间。

MANUAL_SCAN 的 sourceEffectiveAt 本地时刻位于 [14:47:00,14:50:00)，或任何 source 虽更早形成但 materializer 当前本地时刻已进入该黑窗时，admission 使用 reasonCode=AI_DEFERRED_CUTOFF。materializer/reconciler 每次锁定 admission 后使用以下有序且穷尽合同，第一条命中即停止：

1. health proof 失败：以 version CAS 把 admission 置为终态 TIME_AUTHORITY_UNAVAILABLE，不比较或推进 retry；后续 health 恢复也不自动复活这条用户操作；
2. guardNow 位于当前 Asia/Shanghai 本地自然日 `[14:47:00,14:50:00)`：以 version CAS 写 `nextRetryAt=max(existingNextRetryAt, 当前日14:50:00)`、`deferredWindowDate=当前本地日期` 并保持 MATERIALIZATION_PENDING；同一天重复领取若两值已相等则零更新。即使 existingNextRetryAt 来自前一天且早已过期，也必须先推进到当前日 14:50，绝不在当前黑窗 materialize。查询调度条件必须排除 `deferredWindowDate=当前日 AND nextRetryAt>guardNow`，不得靠反复领取实现等待；
3. 当前不在黑窗且 `nextRetryAt IS NULL OR guardNow>=nextRetryAt`：在同一锁定流程取得 materialization admission 并依赖 source 唯一键/同事务状态转换只创建一次 RESEARCH_ONLY；成功后 admission=MATERIALIZED，重复 owner 只能重读原 batch；
4. 当前不在黑窗且 `guardNow<nextRetryAt`：保持 pending，不热轮询。

此前 FINAL_READY 若未在 14:47:00 前取得 same-day materialization admission 也走同一路径；14:47:20 只用于已存在 role 的 INITIAL 派发。sourceEffectiveAt 本地时刻正好 14:50:00 或更晚则落第 3 条。这个恢复只续办明确用户请求，不重新扫描市场、不新建手动结果、不使用旧 scheduled snapshot。CACHE_PREVIEW/RESEARCH_PREVIEW 在其他允许时段以 sourceEffectiveAt 进入 RESEARCH_ONLY，sourceCertifiedAt 保持为空。

### 15.4 short_term_ai_candidate_admission

确保超过八只候选不会静默遗漏：

- candidate_admission_id、admission_id FK、batch_id FK、symbol、rule_rank；
- selection_status：SELECTED、NOT_SELECTED_LIMIT；
- reason_code；
- round_id，可空；
- candidate_snapshot_hash、candidate_material_hash；
- created_at；
- UNIQUE(admission_id, symbol)、UNIQUE(round_id)；
- 复合 FK(admission_id, batch_id) -> batch(admission_id, batch_id)，防止候选 admission 串到其他 source 的 batch；
- 复合 FK(batch_id, round_id, symbol) -> round(batch_id, round_id, symbol)；round_id 可空时数据库按标准不检查该 FK，CHECK 继续约束未选候选必须为空；
- CHECK：SELECTED 必须有 round_id，NOT_SELECTED_LIMIT 必须无 round_id。

### 15.5 short_term_ai_discussion_thread

- thread_id；
- symbol，UNIQUE；
- company_name_snapshot；
- created_at、last_round_at、version；
- UNIQUE(thread_id, symbol)。

### 15.6 short_term_ai_discussion_batch

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
- materialized_at，由 materializer 准入事务一次 guardNow 采样，四个 role deadline 均由它派生；
- initial_dispatch_deadline、latest_egress_permit_at、role_content_deadline、freeze_publish_admission_deadline、effective_outcome_at，均由同一 materialized_at 与第 8.3 节公式派生并复制给四个 role；
- effective_status、outcome_basis_hash、outcome_fixed_at；其中 execution_status 是 raw 聚合状态，effective_status 是可空截止投影；
- started_at、completed_at；
- error_code、error_message；
- next_event_sequence；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- UNIQUE(admission_id, batch_id)；
- INDEX(execution_status, effective_outcome_at)。

### 15.7 short_term_ai_discussion_round

每只候选在 batch 中一个 round：

- round_id；
- batch_id FK；
- thread_id FK；
- symbol；
- parent_round_id，可空；
- phase：MANUAL、CONTINUATION；
- execution_status；
- advisory_state；
- advisory_use；
- candidate_snapshot_json；
- candidate_snapshot_hash；
- candidate_material_hash；
- data_cutoff_at；
- effective_outcome_at、effective_status、outcome_basis_hash、outcome_fixed_at；其中 execution_status 是 raw 状态，effective_status 是可空截止投影；
- started_at、completed_at；
- change_summary；
- error_code、error_message；
- created_at、updated_at、version；
- UNIQUE(batch_id, symbol)；
- UNIQUE(batch_id, round_id, symbol)；
- UNIQUE(round_id, symbol)；
- UNIQUE(thread_id, round_id)；
- 复合 FK(thread_id, parent_round_id) -> round(thread_id, round_id)；
- 复合 FK(thread_id, symbol) -> thread(thread_id, symbol)；
- INDEX(thread_id, created_at DESC, round_id DESC)。

服务在插入时验证父轮已终止、父子 symbol/thread 相同、父 createdAt 早于子；parent_round_id 创建后不可更新。

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
- recovery_mode：EXECUTION、UNKNOWN_FINALIZATION_ONLY；正常创建为 EXECUTION；
- lease_until、heartbeat_at；
- workflow_step：INITIAL_PENDING、INITIAL_IN_FLIGHT、MODEL_RESPONSE_PERSISTED、TOOL_PLAN_PENDING、TOOL_EXECUTING、TOOL_RESULT_PERSISTED、CONTINUATION_PENDING、OUTPUT_PARSING、UNKNOWN_FINALIZATION、DONE；
- workflow_cursor_json，白名单小对象，记录下一安全步骤和稳定 operation ID，不含正文；
- initial_dispatch_deadline、latest_egress_permit_at、role_content_deadline、freeze_publish_admission_deadline、effective_outcome_at，均 NOT NULL；
- initial_invocation_id、initial_dispatched_at、session_deadline，可空；只有 INITIAL invocation 从 PREPARED -> DISPATCHED 时与 started_at 同事务全部写入；
- terminal_content_commit_no，可空；只有在 content ledger 接受的截止前终态事实才填写，deadline coordinator 后置写入的 TIMED_OUT 不伪造该编号；
- effective_status、outcome_basis_hash、outcome_fixed_at；其中 status 是不可覆盖的 raw execution status，effective_status 是可空截止投影；
- started_at、completed_at；
- error_code、error_message；
- next_event_sequence；
- created_at、updated_at、version；
- UNIQUE(batch_id, role_code)；
- UNIQUE(batch_id, role_run_id)；
- INDEX(status, lease_until)。

role_run 的可执行 DDL CHECK 只引用本行：`initial_invocation_id/initial_dispatched_at/session_deadline/started_at` 必须四者全空或全非空；`initial_dispatch_deadline <= latest_egress_permit_at < role_content_deadline < freeze_publish_admission_deadline < effective_outcome_at`；并固定 `(recovery_mode='EXECUTION') OR (recovery_mode='UNKNOWN_FINALIZATION_ONLY' AND workflow_step IN ('UNKNOWN_FINALIZATION','DONE'))`。INITIAL invocation 的 PREPARED -> DISPATCHED 与 role 四字段在同一事务 CAS，跨表一致性由服务与 H2/PostgreSQL 集成测试保证，不编写普通 CHECK 无法表达的跨表子查询。deadline coordinator 以 initial_dispatched_at 是否为空选择 initialDispatchDeadline 或 sessionDeadline/roleContentDeadline。

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
- dispatched_at、transport_handoff_at、response_received_at、completed_at；
- completed_after_content_deadline，仅作供应商/取消审计，不使任何返回正文绕过 content ledger；
- error_code、error_message；
- next_event_sequence、next_chunk_sequence；
- created_at、updated_at、version；
- UNIQUE(role_run_id, invocation_no)；
- UNIQUE(role_run_id, call_kind, tool_round_no, output_segment_no)；
- UNIQUE(role_run_id, invocation_id)；
- request_hash 在 PREPARED 后不可修改；invocation_id 主键本身提供全局唯一性。

### 15.10 short_term_ai_tool_call

每个模型原生 tool_call 先形成一条逻辑调用，预算与多个物理 provider 请求不能混为一行：

- tool_call_row_id、role_run_id、invocation_id、tool_call_id；
- tool_name、arguments_hash、normalized_arguments_json；
- status：RECEIVED、REJECTED、ACCEPTED、EXECUTING、COMPLETED、PARTIAL、FAILED、OUTCOME_UNKNOWN；
- execution_plan_json、plan_stage：NONE、DISCOVERY_FROZEN、FINAL_FROZEN，plan_revision、target_set_hash、planned_execution_count；
- result_content_ref、validation_code、error_code、error_message；
- created_at、completed_at、version；
- UNIQUE(role_run_id, tool_call_id)；
- UNIQUE(role_run_id, tool_call_row_id, tool_call_id)；
- 复合 FK(role_run_id, invocation_id) -> invocation(role_run_id, invocation_id)。

TOOL_CALL reservation 以 tool_call_row_id 唯一。预算可用时，扣除 evidence/history call 配额并与 RECEIVED -> ACCEPTED/REJECTED CAS 同事务；非法参数也消费一次 logical call，防止模型用无效参数绕过轮数。预算不足时，不更新任何 budget counter，但在同一事务插入 status=DENIED、零额度 reservation，并写 toolCall=REJECTED、errorCode=BUDGET_EXHAUSTED，避免 RECEIVED 永久重试。

execution plan 分两段不可变推进。ACCEPTED -> EXECUTING 先冻结 DISCOVERY_FROZEN：请求参数在进入任何 provider 前已足以从服务器 registry 决定的 direct adapter 目标，以及至多一个 Tavily discovery，均使用固定 namespace UUIDv5(`toolCallRowId|provider|ordinal|requestHash`) 派生 executionId；plan 同时保存哪些第一阶段权威 direct execution 是后续目标的 parent。所有 parent 均到物理终态后必须确定性封闭第二阶段：PERSISTED parent 按 adapter 结构化 schema/registry 贡献规范化目标；FAILED_BEFORE_SEND parent 贡献空集合；任一 TOOL_OUTCOME_UNKNOWN 则不派生新 execution，并按 logical OUTCOME_UNKNOWN 真值表立即终结。将所有 PERSISTED parent 的目标去重后按 target hash 排序，计算 targetSetHash；零 parent、零成功 parent、或成功 parent 解析为零目标时也必须以唯一 CAS 冻结空集合。CAS 一并追加稳定 direct executionId、固定 plannedExecutionCount 并把 planStage 改为 FINAL_FROZEN；计划提交前不发任何第二阶段 direct 请求。Tavily 返回 URL 永远只展示/引用，绝不产生 direct execution、HEAD/GET 或重定向探测。namespace 常量和 name 的 UTF-8 拼接格式在 Stage 0 schema vectors 冻结。崩溃后从相同 parent 终态/result hash 重建相同 targetSetHash/ID；plannedExecutionCount 只随 FINAL_FROZEN 的版本化 CAS 一次确定，不能由 worker 临时 append。

logical tool call 的有序穷尽真值表：REJECTED 为验证/预算终态；已持久本地 history/SOURCE_UNAVAILABLE 结果且 plannedExecutionCount=0 为 COMPLETED；任一 physical execution 为 TOOL_OUTCOME_UNKNOWN 时为 OUTCOME_UNKNOWN；planStage=FINAL_FROZEN 且 plannedExecutionCount=0 为 COMPLETED；全部已冻结 planned execution=PERSISTED 为 COMPLETED；至少一个 PERSISTED 且其余全为 FAILED_BEFORE_SEND 时为 PARTIAL；没有 PERSISTED 且全部 FAILED_BEFORE_SEND 为 FAILED；其他含 PREPARED/DISPATCHED/RESULT_RECEIVED，或仍有第一阶段 parent 非终态的组合为 EXECUTING。DISCOVERY_FROZEN 且所有 parent 已终态不是可停留状态，reconciler 必须执行上一段 FINAL_FROZEN CAS；终态不可回到执行态，OUTCOME_UNKNOWN 不自动重跑整组。

### 15.11 short_term_ai_tool_execution

每个物理外部工具请求一行：

- tool_execution_id、tool_call_row_id、role_run_id、invocation_id、tool_call_id、execution_no；同一原生 tool_call 可能依次产生 Tavily discovery 和多个受限 direct fetch，每个物理请求各占一行；
- tool_name、provider、request_hash；
- delivery_status：PREPARED、DISPATCHED、RESULT_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、TOOL_OUTCOME_UNKNOWN；
- provider_request_id、tavily_request_id、credits；
- dispatched_at、transport_handoff_at、result_received_at、completed_at；
- error_code、error_message、version；
- UNIQUE(role_run_id, tool_call_id, execution_no)、UNIQUE(tool_execution_id, request_hash)；
- 复合 FK(role_run_id, invocation_id) -> invocation(role_run_id, invocation_id)；
- 复合 FK(role_run_id, tool_call_row_id, tool_call_id) -> tool_call(role_run_id, tool_call_row_id, tool_call_id)。

toolCallId 来自模型时先做长度/格式校验并与 roleRunId 组合；模型缺失或重复 ID 时服务器生成稳定 ID 并记录原值。DISPATCHED 后结果未知不得自动重发。

### 15.12 short_term_ai_model_output_chunk

- chunk_id；
- invocation_id FK；
- sequence_no；
- content_type：CONTENT、REASONING、TOOL_ARGUMENT；
- content_text；
- received_at；
- content_commit_no，来自该 role 的 content ledger；
- byte_count、content_hash；
- UNIQUE(invocation_id, sequence_no)。

received_at 是持有 content ledger 行锁后由有效 health proof 取得的 guardNow/acceptedAt，不是网络回调自报时间。详情按 invocation_id、sequence_no 重建所有已被截止 gate 接受的原始输出，不复制一份可能不一致的截断 rawResponseText；截止后到达但未接受的正文没有详情读取旁路。

### 15.13 short_term_ai_role_opinion

每个 role session 对每个 round 一条：

- opinion_id；
- batch_id；
- role_run_id；
- round_id；
- symbol；
- stance、confidence；
- content_manifest_json，只含字段、item 数、byteCount、hash 与 content URL，不含长正文；
- citation_ids_json、severe_risks_json；二者是有界的服务器 evidence ID/risk code 结构，不复制模型长文本；
- data_cutoff_at；
- evidence_cutoff_at；无联网工具时等于候选 data_cutoff_at，有工具时等于该 opinion 实际引用证据的最大 observed_cutoff_at；
- complete、validated；
- opinion_hash，对除数据库审计时间外的 canonical opinion manifest、stance/confidence、citation/risk ID 和 data/evidence cutoff 做 JCS/SHA-256；
- content_commit_no，来自该 role 的 content ledger；
- created_at；
- UNIQUE(role_run_id, round_id)；
- 复合 FK(batch_id, role_run_id) -> role_run(batch_id, role_run_id)；
- 复合 FK(batch_id, round_id, symbol) -> round(batch_id, round_id, symbol)。

### 15.14 short_term_ai_opinion_content_chunk

thesis、supportingEvidence、negativeEvidence、nextDayDrivers、invalidationConditions、evidenceGaps、priorRoundDelta、priorRoundDeltaReasons 不放在单个 opinion JSON：

- content_chunk_id、opinion_id；
- field_code；
- item_no、sequence_no；
- content_text，按 UTF-8 代码点边界不超过 64 KiB；
- byte_count、content_hash、created_at；
- content_commit_no，来自 opinion 所属 role 的 content ledger；
- UNIQUE(opinion_id, field_code, item_no, sequence_no)。

数组每个 item 独立编号，超长 item 再分 chunk；cursor 不能在 item/chunk 中间 substring。模型结构化内容因此可完整保存和分页读取，/opinions/{id} 只返回 manifest 与标量。

### 15.15 short_term_ai_discussion_event

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

streamKey 分别使用 batch:{id}、role:{id} 或 invocation:{id}，避免 NULL 唯一键语义。event payload 只保存白名单元数据、参数摘要和 contentRef，编码后上限 1 MiB；模型原文、长 opinion 和超限工具数据必须进各自 chunk 表，不能复制到 event 造成单行巨物。

event/chunk 的 sequenceNo 不能用 SELECT MAX + 1。写入者先在对应 batch/role/invocation 行上以 fence + 原子 UPDATE 预留 nextEventSequence/nextChunkSequence，再插入不可变记录；预留后崩溃允许留下 gap，但序号不得复用。cursor 以 sequenceNo 大于最后值继续，不能假设连续。

### 15.16 short_term_ai_source_evidence

- evidence_id；
- batch_id，NOT NULL；round_id、role_run_id、event_id 可空；
- symbol；
- origin：DETERMINISTIC_SNAPSHOT、DIRECT_ADAPTER、TAVILY_DISCOVERY、DISCUSSION_HISTORY；
- source_type、source_name；
- title、url、canonical_domain；
- provider_published_at_raw；
- published_at、observed_cutoff_at、first_seen_at、retrieved_at；
- excerpt，工具输入运输边界最大 64 KiB，不是模型输出；超出时 source evidence 标 INPUT_RESOURCE_LIMIT 且不传给模型；
- content_version、content_hash；
- point_in_time_eligible；
- eligibility_reason；
- advisory_use；
- rejection_reason；
- content_commit_no，DETERMINISTIC_SNAPSHOT 预载可用预留值 0，角色工具证据必须来自该 role content ledger；
- created_at；
- evidence_scope_key，NOT NULL；预载证据使用 batch:{batchId}，角色私有证据使用 role:{roleRunId}；
- UNIQUE(evidence_scope_key, symbol, content_hash)；
- INDEX(symbol, published_at, created_at)；
- 可空 round_id/role_run_id/event_id 若存在，必须通过复合 FK 与同一 batch、symbol 和 scope 对齐；数据库约束与服务器 TrustedEvidenceValidator 双重校验。

### 15.17 short_term_ai_usage_budget

- budget_id；
- budget_date，Asia/Shanghai；
- actor_id；
- scope_type：GLOBAL、ACTOR、BATCH；
- scope_key；
- model_invocations；
- evidence_tool_calls；
- history_tool_calls；
- tavily_requests；
- tavily_credits；
- direct_fetches；
- continuation_batches；
- version；
- UNIQUE(budget_date, scope_type, scope_key)。

### 15.18 short_term_ai_usage_reservation

- reservation_id；
- operation_type：MODEL_INVOCATION、TOOL_CALL、TAVILY_REQUEST、DIRECT_FETCH、PAID_HTTP_MODEL；
- operation_id；
- budget_date、actor_id、batch_id，可空；PAID_HTTP_MODEL 不属于讨论 batch 时为空；
- reserved_model_invocations、reserved_evidence_calls、reserved_history_calls、reserved_tavily_requests、reserved_tavily_credits、reserved_direct_fetches；
- status：RESERVED、SETTLEMENT_PENDING、SETTLED、UNKNOWN_CONSUMED、DENIED；
- denial_reason，可空；CHECK 要求 DENIED 时必填且所有 reserved_* 为零，其他状态时必须为空；
- created_at、settled_at、version；
- UNIQUE(operation_type, operation_id)。

每次模型/Tavily/direct fetch 在 DISPATCHED 前按固定 GLOBAL -> ACTOR -> BATCH 顺序原子预占最坏额度；同一事务插入唯一 reservation 并 CAS 对应 invocation/toolExecution。logical tool call 按上一节处理：预算成功才更新 counter，预算不足则写唯一零额度 DENIED reservation + REJECTED，不把拒绝事实一起回滚。恢复先按 operationId 读取 reservation，禁止二次预占。供应商返回 usage 后只允许向下结算未使用 credits，响应未知则保守标 UNKNOWN_CONSUMED。并发请求不能穿透上限或因锁顺序死锁。

gate 的 marker-null 拒绝与可恢复结算不能依赖进程内后续调用：把 operation 写为 FAILED_BEFORE_SEND 的同一事务必须把对应 RESERVED reservation CAS 为 SETTLEMENT_PENDING；若任一步失败则整个拒绝事务回滚，operation 仍可由 gate/recovery 重新判断。独立 BudgetSettlementReconciler 只扫描 `FAILED_BEFORE_SEND + transportHandoffAt IS NULL + reservation.status=SETTLEMENT_PENDING|RESERVED`，按 GLOBAL -> ACTOR -> BATCH -> reservation 固定锁序把实际消费结算为零并写 SETTLED；PAID_HTTP_MODEL 省略 BATCH 层。它幂等、可多实例竞争，不发网络、不创建 source/batch，不属于短线自动执行任务。若历史异常行已是 FAILED_BEFORE_SEND+RESERVED，也由同一规则修复。GET 可显示 `budgetSettlementPending=true`，但 operation 业务终态不回退；预算结算成功前 reservation 仍被保守计入上限。

PAID_HTTP_MODEL 没有 batch scope，只按 GLOBAL -> ACTOR 锁定；其他 operation 必须包含 batch 并锁三层。该差异由数据库 CHECK 和 PaidAiGuard API 固定，调用方不能自行选择跳过预算层级。

### 15.19 short_term_ai_content_seal

为了让 outcome basis 取决于数据库封闭结果，而不取决于哪个 coordinator 在 14:48:30–14:48:45 之间先抢到 CPU，每个 role 在 materialize 时同时创建一条独立 content ledger：

- seal_id、batch_id、role_run_id，role_run_id UNIQUE；业务键已校验，故意不对可能长时锁住的 role/batch 建 FK；
- role_content_deadline；
- next_content_commit_no，初始为 1；
- accepted_through_commit_no，封闭前可空；
- sealed_at，可空；
- version；
- UNIQUE(batch_id, role_run_id)。

每个可影响 outcome 的 chunk/evidence/opinion/opinion-content/角色终态短事务，统一按 role_run -> content ledger 顺序使用 PESSIMISTIC_WRITE 或等价 `SELECT ... FOR UPDATE`；先以 role 锁验证 claim fence/recoveryMode，再取得 ledger 锁并用有效 health proof 取得一次 guardNow=acceptedAt。只有 `acceptedAt < roleContentDeadline` 且 sealedAt IS NULL 时，事务才取当前 nextContentCommitNo、递增 ledger，并以同一编号写入该事务全部内容行；角色终态还写 terminalContentCommitNo。精确等于截止、health 失效或回退即拒绝，事务回滚正文，只记有界审计计数。sealer 只锁 ledger 且不在同一事务回锁 role，避免与该顺序成环。该算法不依赖 PostgreSQL `UPDATE ... RETURNING`，H2 与 PostgreSQL 使用同一锁与时间合同。流式 adapter 对每个完整有界 chunk 实施反压：当前 chunk 的短事务提交前不继续确认更多 provider 内容为“已接收”。

guardNow >= roleContentDeadline 后，sealer 用同一 PESSIMISTIC_WRITE 锁逐条封存 ledger，写 sealedAt 和 acceptedThroughCommitNo=nextContentCommitNo-1。已取得 ledger 锁并通过截止检查的内容事务必须先提交/回滚，sealer 才能取得锁；尚未取得锁或截止后才尝试的内容事务会在 sealer 之后看到 sealedAt，或在 sealer 尚未运行时看到 guardNow 已越界，均被拒绝。因此同一数据库提交/锁顺序无论 coordinator 在合法封存窗口早跑还是晚跑，acceptedThroughCommitNo 都一致。sealer 不持有多条 ledger 锁：按 roleCode 固定顺序逐条短事务封存，随后读取四条封存结果。只有四条 ledger 全部及时封闭且 basis 在 freezePublishAdmissionDeadline 前完成，才允许取得 CONTENT_SNAPSHOT SQL 准入；错过该线只能在 effectiveOutcomeAt 尝试 SYSTEM_FALLBACK。

### 15.20 short_term_ai_outcome_freeze

为避免迟提交事务改变 14:49 前已经展示的 AI 结论，freeze 使用独立表且故意不建立指向可能被锁 AI 行的 FK：

- freeze_id、batch_id，UNIQUE；batch_id 作为已校验业务键保存但无 FK；
- schema_version、freeze_kind：CONTENT_SNAPSHOT、SYSTEM_FALLBACK；
- effective_outcome_at、frozen_at、failure_code；
- basis_json TEXT、basis_hash；包含读取快照中实际纳入的 opinion/evidence/chunk ID+hash、各 role 派生状态和排除原因，不复制长正文；
- batch_effective_status；
- round_statuses_json、advisory_states_json；
- created_at；
- UNIQUE(batch_id)、UNIQUE(batch_id, basis_hash)。

Stage 0 必须冻结 `OutcomeBasisV1` JSON Schema，不能让数据库返回顺序决定 hash。最低字段为 schemaVersion=1、batchId、advisoryUse、roleContentDeadline、effectiveOutcomeAt、四条 roleSeal、acceptedContent、effectiveRoleStates、roundStates、advisoryStates、exclusionReasons。roleSeal 按 roleCode ASC，包含 roleCode/roleRunId/acceptedThroughCommitNo，不包含会随 coordinator 时刻变化的 sealedAt；CONTENT_SNAPSHOT 的 acceptedThrough 必须为整数，SYSTEM_FALLBACK 固定显式 null。acceptedContent 按 `(roleCode, contentCommitNo, entityType 固定枚举序, entityId)` 排序，DETERMINISTIC_SNAPSHOT evidence 使用 roleCode=""、contentCommitNo=0，并为 MODEL_CHUNK/EVIDENCE/OPINION/OPINION_CONTENT 分别携带持久 contentHash/opinionHash；role state 按 roleCode，round/advisory 按 symbol ASC、roundId ASC；reason code 去重后按代码点排序。固定 Schema 要求字段不能靠“缺失”等同 null，时间转 UTC RFC3339 微秒，字符串不隐式 trim，basis_json 使用 RFC 8785 JCS 的 UTF-8 字节，basis_hash 为小写 SHA-256。SYSTEM_FALLBACK 也使用同一 Schema 的空内容和稳定 failureCode。所有列表在 Java 中显式排序，不依赖 H2/PostgreSQL 行顺序。

四条 ledger 封闭后，accepted 集合已不可再改变，因此 basis builder 可无锁分页读取并按上述合同组装；只纳入 `contentCommitNo <= acceptedThroughCommitNo` 的内容，MODEL_CHUNK 另要求 receivedAt < roleContentDeadline。只有 terminalContentCommitNo 在封存范围内的原始角色终态才被承认，其余只在 basis 的 effectiveRoleStates 中投影为 TIMED_OUT，并按第 8.2/10 节计算非 PENDING 状态；role.status 永不因此改写。CONTENT_SNAPSHOT builder 完成后，用一个独立 JDBC autocommit 单语句 `INSERT ... SELECT ... WHERE guardNow < freezePublishAdmissionDeadline` 提交已算好的 canonical basis/status。14:48:44 明确定义为 SQL 准入截止，不是 commit 可见截止；statement/lock timeout 均 <=500ms，所以此前合法准入语句允许最晚在 14:48:44.500 提交可见。线程在 execute 前暂停会因 SQL 内 guardNow 谓词失败；execute 后数据库/线程暂停可能改变可见时间，不能被伪称为 14:48:44 前已提交。

到 effectiveOutcomeAt 仍无已提交行时，协调器或首个读请求用独立 autocommit 单语句尝试插入无内容 SYSTEM_FALLBACK/CUTOFF_FREEZE_MISSED。正常情况下 500ms 排空窗已结束，CONTENT_SNAPSHOT 与 fallback 仍由 batch 唯一约束决定一个赢家；若未决合法事务使 fallback 等待至 lock/statement timeout，当前 HTTP 返回 503/PERSISTENCE_UNAVAILABLE，不能返回 PENDING、不能先显示 fallback，也不能绕过唯一键。数据库事务最终提交或回滚后，后续读只返回或创建最终唯一赢家；任何已提交 SYSTEM_FALLBACK 都不可被 CONTENT_SNAPSHOT 反转。

并发 freeze 依靠 batch 唯一约束只允许一个赢家；失败方只在核验唯一约束名后重读，其他 SQL 错误必须上抛。TIME_AUTHORITY_UNAVAILABLE 是不声称点时成果的独立 SYSTEM_FALLBACK，可在 capability 失败时立即插入。freeze 不更新/锁定 role、round、batch 或 deterministic 表。详情页对未纳入 basis 的调用只显示状态、取消结果和 discardedLateBytes 等有界审计元数据，不暴露任何截止后正文旁路。

### 15.21 ai_paid_http_operation

现有趋势分析和 agent-consensus AI POST 也属于真实付费入口，不能成为新预算与幂等合同的旁路：

- operation_id；
- actor_id、route_code：TREND_ANALYSIS、AGENT_CONSENSUS；
- idempotency_key、request_semantic_hash；
- normalized_request_json，只含该路由实际生效的白名单字段；
- delivery_status：PREPARED、DISPATCHED、RESPONSE_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、OUTCOME_UNKNOWN；
- llm_config_revision、provider、model、provider_request_id；
- response_json TEXT、response_hash；
- error_code、error_message；
- admitted_at、paid_dispatch_window_seconds、latest_egress_permit_at、created_at、dispatched_at、transport_handoff_at、completed_at、version；
- UNIQUE(actor_id, route_code, idempotency_key)。

相同 key/hash 返回已持久响应或当前状态，相同 key 不同 hash 返回 409。PaidAiGuard 用一个事务插入 PAID_HTTP_MODEL reservation 并 CAS PREPARED -> DISPATCHED，之后才允许唯一一次物理模型发送；DISPATCHED 后响应未知必须 OUTCOME_UNKNOWN，不由 HTTP 客户端自动重发。该表、预算、内部身份与 Idempotency-Key 未完成前，这两个旧付费 POST 在发布配置中保持关闭，不能仅靠前端隐藏。

paidDispatchWindow 固定 30 秒并作为 operation 快照。入口在 Asia/Shanghai 每个自然日只用一次有效 health proof/guardNow 作为 admittedAt/createdAt：若本地时刻 `<14:47:55`，`latestEgressPermitAt=min(admittedAt+30s, 当日14:47:55)`；若位于 `[14:47:55,14:50:00)`，在建 operation 前返回 503；若 `>=14:50:00`，`latestEgressPermitAt=admittedAt+30s`。gate 永远使用严格 `guardNow < latestEgressPermitAt`。因此 14:47:54.900 创建的 operation 最多只剩 100ms permit，14:50:00 以此前未建行的同 key 首次提交则得到新的完整 30 秒窗口；旧 operation 不会因跨过 14:50 自动续期。

旧付费 POST 的 durable operation 是响应恢复事实源：PREPARED/DISPATCHED/RESPONSE_RECEIVED 查询返回 202 和 Retry-After，PERSISTED 返回 200 及原响应，FAILED_BEFORE_SEND/OUTCOME_UNKNOWN 返回带稳定 errorCode 的终态响应，不允许同 key 重新发送。其中 gate 超界使用 errorCode=FAILED_BEFORE_SEND_CUTOFF；只有用户明确新建一次操作才产生新 key。入口门禁在创建 operation 前返回的 AI_EGRESS_CUTOFF 不是 operation 终态，同 key 查询为 404，14:50 后才能以原 key/body 完成首次提交。

## 16. 事务、一致性与恢复

### 16.1 确定性结果与 AI 解耦

确定性源结果永远先提交：

1. durable manual job/report 按第 15.2 节独立 stage+certify；只有 result_status=SUCCEEDED 的确定性终态才发布 after-commit source 事件。旧 scheduled 表不再产生新 source。
2. aiRequested=true 且候选非空的合格 manual result 事件只携带 sourceType/sourceKey，不执行模型；handler 幂等 ensure MATERIALIZATION_PENDING admission。aiRequested=false 或 DATA_BLOCKED/NO_TRADE/FAILED/TIMED_OUT 在 manual row 冻结原因，不会日后追溯创建付费任务。
3. CONTINUATION 在允许时段由 HTTP 事务提交带白名单 sourcePayload 的 MATERIALIZATION_PENDING admission，问题文本在返回 202 前已持久化；14:47 至 14:50 按第 14.2 节返回 503 且不创建半成品 admission。
4. 独立 materializer 事务锁定 pending admission，只以一次有效 health proof/guardNow 作为 materializedAt 和准入事实；health 失败时 fail closed，当前黑窗按第 15.3 节推进 retry 而不取得 admission。允许时先冻结候选选择与父轮并生成全部 UUID，再写一个非空 batch、thread/最多八个 round、全部 candidate admission、四个 batch-level role run 与四条 content seal，最后把 admission 改为 MATERIALIZED。14:47 前取得 admission、14:47 后才可见的事务不重算 materializedAt，也不能越过后续 INITIAL gate。
5. materializer 的唯一冲突按唯一键重新读取；可恢复异常只增加 attempts/nextRetryAt，超过配置重试次数才标 MATERIALIZATION_FAILED，且不传播回手动扫描 HTTP 或确定性结果事务。
6. reconciler 只从提交时 aiRequested=true 的 eligible manual result 和 pending continuation admission 重建 source identity；每次锁定后严格执行第 15.3 节 health -> 当前日黑窗推进 -> 窗外 retry 到期 materialize 的顺序，不依据历史 sourceEffectiveAt 绕过当前窗口，不读取旧 scheduled snapshot。

任何 AI 初始化失败、唯一冲突、执行器拒绝、Tavily 或 LLM 配置错误都不能改变手动确定性 payload 的规范化 hash、status、attestation proof 或 API 响应。

### 16.2 创建和父链

materializer 在一个短事务中：

- 锁定或重新读取 MATERIALIZATION_PENDING admission；
- 取得并验证一次 TimeAuthority health proof/guardNow；黑窗或坏时钟按第 15.3 节收口，不执行以下插入；
- 规范化全部候选，按 ruleRank ASC、symbol ASC 冻结前八选择，并生成 batch/round/candidateAdmission/roleRun UUID；
- 为被选 symbol 插入或重复键重读 thread，按 sourceType 父轮真值表解析不可变父轮并验证时间；
- 插入一个 batch、被选候选 round，并在同一事务写完 contentCommitNo=0 的 DETERMINISTIC_SNAPSHOT evidence；这些预载行在 admission=MATERIALIZED 后不可增删改；
- 为所有候选插入 candidate admission：前八绑定已生成 roundId，其余为 NOT_SELECTED_LIMIT 且 roundId=NULL；
- 以同一 materializedAt 和第 8.3 节公式插入四个 batch-level role run 与四条 content seal；
- 绑定 admission.materializedBatchId；
- 将 admission 原子改为 MATERIALIZED；
- 提交后唤醒 dispatcher。

父轮真值表只有两行：MANUAL_SCAN 使用 completedAt <= source.deterministicFinalizedAt（与 admission.sourceEffectiveAt 一致）的最新已结束同股 round；CONTINUATION 使用 sourcePayload.parentRoundId 精确值。任何实现不得把 CONTINUATION 换成最新轮。

并发 thread/batch/round 创建只依赖唯一约束与重复键后的重新读取，禁止 exists 后 insert。continuation 的幂等作用域为 actorId + symbol + parentRoundId + idempotencyKey；同时保存 questionFingerprint。同 key 不同 fingerprint 返回 409。

### 16.3 认领与 fencing

PostgreSQL 认领使用单条条件 UPDATE：条件是 QUEUED，或 CLAIMED/RUNNING 且 leaseUntil < guardNow；成功时 claimEpoch+1、生成 claimToken/leaseUntil。health proof 无效时不认领 EXECUTION，只允许 capability-failure closer 走无内容 fallback。认领不能因未知外部结果而永远排除该 role。claim transaction 检查是否存在 `transportHandoffAt IS NOT NULL` 且本地结果未知的 model/tool operation；若有，禁止重新 POST，并原子设置 recoveryMode=UNKNOWN_FINALIZATION_ONLY、workflowStep=UNKNOWN_FINALIZATION。无此类 operation 才为 EXECUTION。受影响行数必须为一。

UNKNOWN_FINALIZATION_ONLY owner 不用一个跨预算层级的大事务收口，避免与 GLOBAL -> ACTOR -> BATCH 的预占顺序形成死锁：第一步在普通预算结算事务中按 GLOBAL -> ACTOR -> BATCH -> reservation 固定顺序把相关 reservation 幂等改为 UNKNOWN_CONSUMED，不持有 role/operation 锁；第二步按 role -> operation ID -> toolCall -> content ledger 锁序，重新验证这些 reservation 均已 UNKNOWN_CONSUMED 和新 token/epoch，再把 invocation/tool execution 改为 OUTCOME_UNKNOWN/TOOL_OUTCOME_UNKNOWN、logical tool call 改为 OUTCOME_UNKNOWN，并把 role 收口 OUTCOME_UNKNOWN。第二步不得调用 AiEgressGate、创建新 operation或预占预算；第一步完成而第二步崩溃时，新 owner 只重做第二步。若仍早于 roleContentDeadline，role 终态按 content ledger 写 terminalContentCommitNo；若已过内容截止，原始状态仍可审计为 OUTCOME_UNKNOWN，但 freeze 不承认未封存终态。recoveryMode 永远不能回到 EXECUTION。

普通恢复 owner 从持久 workflowStep/cursor 继续：PERSISTED 只读；DISPATCHED 且 marker 为空可用原 operation/reservation 竞争 gate 单赢家；marker 非空且本地结果未知的生成/搜索请求禁止再次 POST，直接进入 UNKNOWN_FINALIZATION_ONLY。所有后续写入携带新 token/epoch；聚合只读取 freeze basis 中 validated opinion。

### 16.4 流式完成

- 模型 chunk 以有界缓冲聚合后短事务追加，网络期间无长事务。
- 工具请求先记 TOOL_REQUEST；校验失败记 TOOL_REJECTED；合格 evidence 先落库再回传模型。
- role 解析完成事务写每股 opinion 并更新 role 终态。
- batch 正常提前完成时按固定真值表更新；roleContentDeadline 后先封闭四个 content ledger，全部封闭且 guardNow<freezePublishAdmissionDeadline 才能准入单语句 CONTENT_SNAPSHOT，effectiveOutcomeAt 仍无已提交冻结则单语句尝试 SYSTEM_FALLBACK，之后只可复制同一 basis/effectiveStatus，不覆盖 raw status。
- roleContentDeadline 到达时主动取消迟到网络线程；已持久的截止前 chunk 保留，截止后不写新正文 chunk，只在独立审计事件/指标中保存取消结果和 discardedLateBytes。
- 每个 outcome-affecting 事务执行 TimeAuthority health/guardNow、claim/epoch、workflow cursor、状态谓词和 content ledger gate；截止后正文事务被拒绝，允许提交的取消/丢弃计数审计事务没有 contentCommitNo，不能进入 outcome freeze。

### 16.5 重启恢复

| 持久状态 | 自动行为 |
| --- | --- |
| aiRequested=true 的 manual source 已成功、无 admission/batch | 允许写窗内 reconciler 幂等 materialize |
| Role QUEUED | 可认领 |
| Role CLAIMED/RUNNING，租约过期且无 marker-nonnull 未知 operation | 新 epoch/recoveryMode=EXECUTION 从 workflow cursor 继续 |
| Role CLAIMED/RUNNING，租约过期且存在 marker-nonnull 未知 operation | 新 epoch/recoveryMode=UNKNOWN_FINALIZATION_ONLY；按两阶段合同收口 UNKNOWN，零生成/搜索 transport |
| Invocation/ToolExecution PERSISTED | 不重放；推进到 cursor 的下一安全步骤 |
| Invocation/ToolExecution DISPATCHED，transportHandoffAt 为空 | 读取既有 reservation，在截止前重新过 egress gate；不重复预占 |
| Invocation/ToolExecution/paid operation FAILED_BEFORE_SEND 且 reservation RESERVED/SETTLEMENT_PENDING | 零 transport；BudgetSettlementReconciler 按固定预算锁序幂等结算为零，operation raw 终态不回退 |
| Invocation DISPATCHED，transportHandoffAt 非空且本地结果未知 | UNKNOWN_FINALIZATION_ONLY -> OUTCOME_UNKNOWN，不自动查询或重发 |
| ToolCall RECEIVED | 读取唯一 reservation；无记录则完成预算接受或 DENIED+REJECTED 原子事务 |
| ToolCall ACCEPTED | CAS 冻结 DISCOVERY_FROZEN plan/稳定 executionId，推进 EXECUTING |
| ToolCall EXECUTING、尚无或缺少当前 plan execution 行 | 按当前冻结 revision 幂等插入缺失 PREPARED 行，不重复 logical 配额 |
| DISCOVERY_FROZEN 且全部第一阶段 parent 终止 | PERSISTED parent 贡献规范化目标、FAILED_BEFORE_SEND 贡献空集合；任一 TOOL_OUTCOME_UNKNOWN 直接终结 logical OUTCOME_UNKNOWN，否则以唯一 CAS 冻结含空集合在内的 FINAL_FROZEN；Tavily 结果不得贡献后续目标 |
| Tool result PERSISTED、continuation 尚未创建 | 用稳定 invocation ID 创建唯一 TOOL_CONTINUATION PREPARED |
| ToolExecution PREPARED | 读取或原子创建唯一 reservation，按原 executionId 推进；不重复预占 |
| ToolExecution DISPATCHED、transportHandoffAt 为空 | 读取既有 reservation，以原 executionId 重过 AiEgressGate；gate 超界则 FAILED_BEFORE_SEND |
| ToolExecution DISPATCHED、transportHandoffAt 非空且结果未知 | TOOL_OUTCOME_UNKNOWN，不自动重发 Tavily/direct 请求 |
| 已收到 chunk、deadline 未到 | 只有原连接存活时继续；进程重启后标 INCOMPLETE，不重新生成已有段 |
| deadline 已到 | TIMED_OUT/PARTIAL/INCOMPLETE；advisoryUse 不升级 |
| 任一 raw 终态 | 只读，不自动覆盖；closer 只写独立 effectiveStatus/hash/fixedAt 投影 |

“不重复收费”的验收限定为：重复 HTTP、双击、双实例、dispatcher 重试和系统自动恢复不会创建重复 source/batch/initial invocation/logical tool call/tool execution/reservation；供应商不支持幂等键的未知模型或 Tavily 结果采用 at-most-once 停止，而不是承诺无法实现的 exactly-once。

### 16.6 Schema 升级

apps/api/src/main/resources/schema.sql 是唯一 canonical additive schema，Spring 在 H2/PostgreSQL 启动时执行。infra/db/init/001_schema.sql 不再维护第二份业务 schema；空 PostgreSQL 也由 API 启动应用 canonical schema。

本次新增 AI 表、索引和约束；旧 scheduled 表保留，但删除 scheduler/service bean、写 store、cron/自动 scanRequest 配置与前端轮询，不做破坏性删表。历史 API 对旧行只读且强制 historical/non-executable。发布前：

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
| GET | /opinions/{opinionId} | 读取角色意见标量与完整内容 manifest |
| GET | /opinions/{opinionId}/content?field=&cursor=&limit= | 按完整 item/chunk 分页读取结构化长内容 |
| GET | /invocations/{invocationId}/chunks?cursor=&limit= | 按稳定序号读取完整原始输出片段 |
| GET | /rounds/{roundId}/events?cursor=&limit= | 按稳定序号读取完整事件时间线 |
| GET | /rounds/{roundId}/evidence?cursor=&limit= | 分页读取证据与受限 excerpt |
| GET | /stocks/{symbol}/history?cursor=&limit= | 分页获取同股全部历史轮次 |
| POST | /stocks/{symbol}/continuations | 从指定父轮次继续讨论，返回 202 |

现有 POST /api/short-term/scan-jobs 增加必填 Idempotency-Key 请求头；GET scan job 状态增加 persistentResultId、aiAdmissionStatus、aiBatchId、aiReasonCode 和每候选 aiSelectionStatus。新增 GET /api/short-term/scan-jobs/by-idempotency-key/{key}，只在通过内部身份后按固定 actor=local-operator 查询原持久任务，支持 POST 响应丢失/刷新恢复。

新增受内部身份保护的 `GET /api/ai/paid-operations/by-idempotency-key?routeCode=&key=`，为旧 `TREND_ANALYSIS` 和 `AGENT_CONSENSUS` 客户端恢复丢失的 POST 响应。服务端固定 actor=local-operator，校验 routeCode 白名单；不存在返回 404，进行中返回 202，PERSISTED 返回 200 和完整原业务响应，FAILED_BEFORE_SEND/OUTCOME_UNKNOWN 返回不可自动重放的终态。查询结果含 operationId、routeCode、deliveryStatus、requestSemanticHash、errorCode、createdAt/completedAt，不回显 normalizedRequestJson 中可能的敏感业务字段。

旧 /api/short-term/scheduled-snapshots/latest 保留为只读兼容：只查询当前 market date 已存在的旧行，无记录返回 204，不再合成“等待自动预选”、不再执行 expire/attest 写回；有记录也包装为 automationDisabled=true、historical=true、executable=false，不能成为当前买点。新增只读 GET /api/short-term/scheduled-snapshots/history?tradeDate=&stage=&cursor=&limit= 用于明确的历史诊断；两个端点都不得产生、修复、认证或推进 snapshot。

### 17.1 读取

- BatchSummaryV1 返回 sourceEffectiveAt、可空 sourceCertifiedAt、source、raw execution/admission/advisory 状态、全部 candidate admission、被选 round 摘要和四角色进度。缓存/研究预览必须有 sourceEffectiveAt 且 sourceCertifiedAt=null；SAME_DAY_RESEARCH 必须返回两者相等。NOT_SELECTED_LIMIT 是终态展示，不轮询。当 guardNow>=effectiveOutcomeAt 时，BatchSummaryV1 和 RoundDetailV1 必须从唯一 freeze 返回 `effectiveStatus`、`effectiveStatusSource=CONTENT_SNAPSHOT|SYSTEM_FALLBACK`、`outcomeBasisHash`、`outcomeFixedAt`和 `failureCode`；不得继续返回 RUNNING。raw role status 可另外展示为审计字段，但不能覆盖 effectiveStatus。数据库当下完全不可读或唯一赢家尚未决议时返回 503 `PERSISTENCE_UNAVAILABLE`，页面显示“结果状态不可确认”，不用缓存的运行中状态冒充。
- RoundDetailV1 返回 candidate snapshot、四个 opinion manifest/detail URL、invocation 元数据、证据 page URL、父轮次和变化摘要；结构化长内容、原始 chunk、事件和证据通过各自 cursor 接口字节保真读取，避免把最多 256 MiB 内容装进单个 JSON 响应。
- HistoryPageV1 的 item 只含时间、状态、角色计数、父轮次和 detail URL 等列表摘要，不触发额外模型调用，也不假装是完整原文；点击详情一定能读取未截断内容。
- raw chunk/opinion content 页面每页最多 100 chunk 且编码后最多 4 MiB，event/evidence 页面每页最多 200 项且编码后最多 2 MiB；它们使用签名不透明 cursor、稳定 sequenceNo/itemNo/createdAt 顺序和完整 item/chunk 边界。达到项数或字节页上限时返回 nextCursor，不能 substring 某一 item、追加省略号或把 nextCursor 缺失伪装成结尾。
- API 不截断结构化 opinion、证据或按 chunk 重建的原始响应；单次 HTTP 有界不等于业务内容被丢弃。
- 历史按 createdAt DESC、roundId DESC 稳定排序；cursor 是这两个字段签名后的不透明值。limit 默认 20，最小 1，最大 100。
- 活跃任务响应包含 completedRoles、totalRoles、startedAt、deadlineAt 和 lastEventAt。
- 所有错误字段使用稳定 errorCode 与可读中文 message。
- 固定 HTTP 语义：400 参数/Schema，401 专用付费/配置端点未通过本地代理身份，403 权限，404 不存在，409 幂等载荷冲突，429 持久预算/频率超限，503 AI 功能或供应商不可用。scan-jobs 的确定性扫描是例外，按第 17.3 节降级而不是因 AI 身份返回 401。

### 17.2 继续讨论

请求包含：

- parentRoundId；
- question，可空；
- 必填 Idempotency-Key 请求头；body 不再复制第二份 key。

校验：

- symbol 与父轮次一致；
- 父轮次存在且已结束；
- 同一 idempotencyKey 不重复收费或生成轮次；
- 问题只作为研究补充，不能要求改变确定性动作；
- 服务端限制并发继续任务数量和调用频率，避免误点或接口滥用。
- idempotencyKey 的作用域为 actorId + symbol + parentRoundId；相同 key 不同 questionFingerprint 返回 409。
- 14:47:00 至 14:50 返回 503 AI_EGRESS_CUTOFF、Retry-After 和可读提示，不创建 admission、不扣预算；14:50 后同 key/同 body 可安全续办这次用户操作。

### 17.3 访问控制

本版明确选择“本机单用户 + 可信反向代理”边界，不引入全站账号系统：

1. 发布 Compose 只把 React Web 绑定到 127.0.0.1；API 不映射宿主机端口，只接受 Docker internal network 的 Web 代理。
2. Web 与 API 只读挂载同一 /run/secrets/ai_internal_token。Web entrypoint 读取 secret 并通过官方 Nginx template envsubst 生成 proxy_set_header；客户端传入的 X-AI-Internal-Token 被丢弃，代理重新设置唯一的 secret 值。
3. API 通过 Spring config tree 读取同一 secret，并用常量时间比较验证 token；未配置 token 时 AI discussion feature 强制关闭。
4. POST continuation、未来任何 AI retry、POST /api/ai/trend-analysis、POST /api/companies/{symbol}/agent-consensus/ai 和运行时模型配置 PUT 必须有该内部身份，否则 401。POST scan-jobs 本身仍允许完成确定性扫描；只有内部身份有效且 feature 开启时才自动创建 AI admission，缺失/错误 token 时扫描照常返回并以 reasonCode=AI_AUTH_REQUIRED 表示未启动 AI，不能让 AI 鉴权改变扫描 payload/status。已取消所有 scheduled AI actor。
5. actorId 对本版固定为 local-operator，并进入幂等和预算键。
6. CORS、Referer、X-Forwarded-For、前端隐藏按钮或容器来源 IP 都不作为身份。
7. 所有付费触发都有持久幂等键、并发限制和每日预算。
8. Nginx 只接受明确的 localhost/127.0.0.1/[::1] Host，未知 Host 直接 444/403，防止 DNS rebinding；状态改变请求若带 Origin，必须与当前 loopback Origin 精确一致，浏览器 Sec-Fetch-Site 为 cross-site 时拒绝。内部 token 只在匹配的 API proxy location 中由 Nginx覆盖写入，不转发客户端自带值。Host/Origin 检查是 CSRF/重绑定防线，不替代内部身份。
9. Stage 0 冻结当前所有真实付费 HTTP 路由清单；新代码不得直接调用 LlmChatClient/Tavily adapter，必须经过 PaidAiGuard。架构测试扫描 Spring mapping 与依赖图，发现未登记的 LLM/Tavily 调用入口即失败。旧 trend/committee 入口必须同时受 token、Idempotency-Key、语义 hash、ai_paid_http_operation、at-most-once 发送、频率和持久预算保护；任一项未完成时发布环境默认关闭该路由，而不能绕过 discussion 预算。

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
- 本批未纳入 AI（上限 8）。

始终保留规则动作在主区域，AI 状态不取代动作标签。按钮文案为“查看 AI 讨论”。

不得重新引入此前已移除的“手动扫描执行中/完成”顶部状态页卡；扫描与 AI 完成/失败继续通过右上角浮动消息反馈，候选内只展示与该候选相关的 AI 状态。

React 在第一次 POST 前生成 Idempotency-Key，并把 key、提交时的规范化生效请求体、clientRequestFingerprint、operationType、createdAt 写入 sessionStorage；clientRequestFingerprint 由 Stage 0 生成的共享 canonicalizer 和跨 Java/TypeScript 测试向量计算，只用于客户端自检，服务器返回的 requestSemanticHash 才是权威值。收到 202 后再补服务器 hash、persistentResultId/jobId。网络重试复用同一 key 和原请求体；响应丢失或刷新先用 by-idempotency-key 查询，404 才以原请求体重放 POST，不能用当前表单或默认值。直到读到持久终态才清理。用户在终态后明确再次点击才生成新 key。继续讨论同样保存 symbol、parentRoundId、question 和 key，不能在 axios 自动重试时生成新 key。

continuation 收到 AI_EGRESS_CUTOFF 时不生成新 key、不清理原 body，并显示“14:50 后继续讨论”；可按 Retry-After 只续办一次，或由用户再次确认。该续办仍是原用户操作的延续，不是无 source 的自动短线任务。

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
- 不再轮询 scheduled-snapshots/latest，也不渲染 ScheduledScanPulse/ScheduledSnapshotStatus；历史 scheduled API 只供兼容/诊断页按需读取。
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
- short_term_scheduled_snapshot 历史表和只读兼容 API 保留；ShortTermPage 删除 scheduled-snapshots/latest 请求、ScheduledScanPulse/ScheduledSnapshotStatus 和自动切换逻辑。覆盖率/点时门禁改由 durable manual result 展示，扫描完成右上角消息和 manual AI materialization 保留。
- 长期价投 MarketScanPage 的同名卡保持不变。

### 18.5 旧 Vue 付费入口的响应恢复

`apps/web` 中的趋势分析与 agent consensus 虽不属于短线讨论页，但使用同一付费模型通道，必须与后端 durable operation 一起收口：

1. 首次发起 `POST /api/ai/trend-analysis` 或 `POST /api/companies/{symbol}/agent-consensus/ai` 前，客户端生成 Idempotency-Key，并将 routeCode、key、symbol（如有）、提交时的规范化原请求体、clientRequestFingerprint 和 createdAt 写入 sessionStorage。agent consensus 即使当前 body 为空，也以 routeCode+symbol 形成规范语义载荷。
2. 网络错误、刷新或 POST 响应丢失后，必须先以同 routeCode/key 调用 paid-operation 查询；命中时只读取/等待该 operation，404 时才使用 sessionStorage 中原始保存的同 key/同语义 body 重放 POST。禁止从已变化的表单、当前默认值或路由状态重建 body。
3. 只有读到 PERSISTED、FAILED_BEFORE_SEND 或 OUTCOME_UNKNOWN 终态后才清理本次恢复记录。FAILED_BEFORE_SEND/OUTCOME_UNKNOWN 不使用原 key 自动再发；尤其 deliveryStatus=FAILED_BEFORE_SEND、errorCode=FAILED_BEFORE_SEND_CUTOFF 表示已有 operation 在 transport gate 被拒绝，用户明确再次点击才是新操作、使用新 key。
4. 14:47:55 至 14:50:00 若入口在创建 operation 之前返回 503 AI_EGRESS_CUTOFF，客户端保留 key/body，用同 key 查询必须为 404；14:50 后可由用户确认或一次性恢复机制完成该 key 的首次提交。若查询命中 FAILED_BEFORE_SEND_CUTOFF，则不属于这种未创建情形，必须按第 3 条以新操作/新 key 处理。两种恢复都源自明确的用户操作，不是定时短线扫描。
5. Java/TypeScript 对两个 route 使用共享 canonical test vectors；同 key 不同语义载荷必须 409，客户端 fingerprint 只做本地防错，服务端 requestSemanticHash 才是权威。

## 19. 模型配置与密钥安全

- materializer 为整个 batch 一次性读取当前 runtime_config_section 的 LLM revision 和 LlmSettings，四个 role 共享同一份不可变非密钥快照，避免角色启动先后导致 Provider/Model 混用。
- manual source 冻结的 featureRevision 只证明“提交当时允许”，不能绕过之后的紧急关闭。materializer 和每次付费 DISPATCH 都要求当前 feature 仍开启且 revision 与 batch 快照一致；关闭或 revision 改变后不再发送新的 INITIAL/continuation/tool 请求，未发送角色以 FEATURE_REVISION_CHANGED/FEATURE_DISABLED 收口，已经发出的响应只按原 fence 保存。该终态以后重新开启 feature 也不自动复活，用户需重新手动扫描或明确继续讨论。
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
| TimeAuthority 启动或持续健康证明失败 | 全部新 AI/搜索/旧付费 dispatch fail closed；既有 batch 无内容 SYSTEM_FALLBACK/TIME_AUTHORITY_UNAVAILABLE；确定性行情计算可继续，但 manual 认证与 executionEligibility 均 fail closed，不能产生/返回可执行 FINAL_READY |
| gate 在 transport 前拒绝后进程崩溃 | operation 已是 FAILED_BEFORE_SEND 且 reservation 为 SETTLEMENT_PENDING；reconciler 零外发并幂等释放预算 |
| 并发 gate 已有 transportHandoffAt 赢家 | 输家 ALREADY_HANDED_OFF，零发送且不释放赢家 reservation |
| DISPATCHED 且 transportHandoffAt 已持久后进程崩溃，供应商无幂等键 | OUTCOME_UNKNOWN，不自动重发；用户可新建继续讨论 |
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
| AI 全部失败 | 已提交的手动确定性结果与其门禁状态保持不变 |

“AI 全部失败”不能把手动结果中的 FINAL_READY 改成 FINAL_PENDING、DATA_BLOCKED 或无候选；“确定性数据失败”也不能被 AI 支持意见挽救。

## 21. 可观测性

至少记录不含敏感信息的指标：

- 批次/轮次/角色各状态数量；
- 队列深度、DEFERRED 次数与恢复耗时；
- 角色模型耗时、搜索耗时、总耗时；
- 各 Provider/Model 错误码；
- finishReason=length 数量和续写成功率；
- 工具请求、拒绝、无结果和超时数量；
- 14:48:45 前同日讨论完整/部分/超时收口率；
- 手动扫描提交/完成时间、FINAL_PENDING/FINAL_READY 认证时间和 14:49:40 门禁结果；
- TimeAuthority watchdog 最后健康样本 age、冻结/回退/过期次数、health=false 时长与恢复 probe 次数；
- ShortTermScanScheduler 注册任务数必须为零、旧 scheduled snapshot 新增行数必须为零；
- 重启恢复和租约抢占数量。
- PREPARED/DISPATCHED/OUTCOME_UNKNOWN invocation 数量；
- model invocation、Tavily request/credit 的 batch/actor/day 用量；
- SETTLEMENT_PENDING reservation 数量、最老 age 与幂等结算成功/失败数；
- materialization gap 和 reconciler 修复数量；
- content gate 拒绝正文写入数量、discardedLateBytes、content seal 成功/超时及 transport handoff 延迟数量；
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
- 扫描返回 9、12、40 只候选时，按 ruleRank ASC、symbol ASC 稳定选择前八；所有候选都有 admission，未选者为 NOT_SELECTED_LIMIT 且永不轮询。
- 工具和输出续写按 invocation 记录，受每 session 四次物理调用上限约束。
- 扫描 API 在 AI 未完成时立即返回。
- 一个角色失败时其他角色和聚合正常。
- 四个角色共享同一 LLM revision/provider/model；DISPATCH 前 revision 改变时未发送角色明确失败且不会混用新模型。
- 四角色全失败时确定性结果不受影响。
- 队列满仍保留 durable QUEUED；dispatcher 可恢复。
- 无 AI 身份的确定性降级、双击、响应丢失重试、相同 key 不同生效 payload 和双实例并发；只改变 deprecated PE/PB 仍读取同一持久结果且不生成重复讨论。
- 手动矩阵覆盖交易/非交易日、缓存预览、研究预览、数据阻断、空候选，以及 14:29:59.999、14:30:00、14:46:59.999、14:47:00、14:47:19.999、14:47:20、14:47:54.999、14:47:55、14:48:29.999、14:48:30、14:48:43.999、14:48:44、14:48:45、14:49:30、14:49:40、14:50:00、15:00:00 与次日重启；逐项断言 deterministicFinalizedAt/sourceEffectiveAt/sourceCertifiedAt、SAME_DAY 仅 FINAL_READY+certifiedAt、initial dispatch、egress permit、content acceptance/publication/freeze、admission、advisoryUse 和同 key 恢复。
- 明确使用 CACHE_PREVIEW/RESEARCH_PREVIEW fixture：source=14:46:59 时让 materializer 分别在 14:46:59、14:47:00、14:49:00、14:50:00 运行，首例使用受尾盘上限约束的 RESEARCH_ONLY deadline，中间两例零新 admission/INITIAL 且 nextRetryAt=当日14:50，末例只 materialize 一个相对窗口 batch。另让 D 日 nextRetryAt=D 14:50 的旧 source 在 D+1 日 14:47/14:48/14:49 重启：每例先以 version CAS 推进到 D+1 14:50、零 batch/调用且不热更新；D+1 14:50 恢复只创建一个 RESEARCH_ONLY。source 正好 14:50 或 15:00 立即运行，不能写过去的 retry 时间；次日恢复旧 FINAL_READY 必须降为 RESEARCH_ONLY，不能重新获得 SAME_DAY_RESEARCH。
- RESEARCH_ONLY 用注入 TimeAuthority 覆盖 materializedAt+15/+75/+105/+119/+120 秒：四角色完整、1–3 角色完整、全部超时、content seal/发布超时四种结果在 +120 秒均不返回 PENDING，分别得到 COMPLETED/PARTIAL/TIMED_OUT 或 SYSTEM_FALLBACK/INCOMPLETE。
- 父子手动 round 的 discussionMaterialHash 与精确白名单匹配；只改变 dataCutoffAt 时 material hash 保持一致、完整 snapshotHash 不同且变化摘要明确为“仅审计快照更新”，不触发额外付费批次。
- 生产代码中不存在 ShortTermScanScheduler、ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 或 shortTermScheduledExecutor bean，ShortTermExecutionSettings 不再暴露 cron/自动 scanRequest，历史 store 只暴露查询；架构测试禁止 scheduled repository 继承 JpaRepository/CrudRepository，禁止 save/delete/flush/@Modifying 及任何 scheduled snapshot mutator 依赖。模拟跨过 14:30、14:47、14:49:50 与应用重启均不会调用扫描服务或向 short_term_scheduled_snapshot 插入新行。
- 旧 scheduled `/latest` 在无当日旧行时返回 204，不出现“等待自动预选”；有旧行和 history 分页均只读、historical=true、executable=false，调用前后表内容/hash/行数完全一致。
- ShortTermValidationOutcomeScheduler、TradeOutcomeScheduler 仍按原配置注册并通过各自定向回归，不能通过关闭全局 scheduling 取巧。

### 22.3 数据库与恢复

- H2 文件副本升级/重启测试与真实 Testcontainers PostgreSQL schema 启动。
- 两库实际执行 role_run 本行 CHECK：四个 INITIAL 字段全空/全非空、deadline 严格时序与 UNKNOWN_FINALIZATION_ONLY 禁止发送步骤；跨表 INITIAL invocation/role 同事务故障点不得留下半套字段。
- thread/batch/round 唯一约束并发测试。
- PostgreSQL 两个应用实例同时 materialize/认领，只有一个 batch、四个 role session 和四条 content seal；四个 role 的 materializedAt 完全相等。另让事务在 14:46:59.900 取得 admission 后暂停并于 14:47:00.400 可见：它保留原 materializedAt，但所有 INITIAL 仍按 guardNow/14:47:20 gate；14:47:00 后才开始的事务零新 admission。验收名称固定为 materialization admission cutoff，不声称 14:47 后零新可见 commit。
- PostgreSQL 两个应用实例同时初始化空 runtime_config_section，均能启动且每个 section 只有一行。
- claim token/epoch、数据库时间、心跳、过期抢占；旧 owner 的 event/chunk/evidence/opinion/终态全部被 fence。无幂等 marker-nonnull operation 在 lease 过期后必须被 UNKNOWN_FINALIZATION_ONLY 新 owner 两阶段收口：先按预算锁序把 reservation 变为 UNKNOWN_CONSUMED，再按 role 锁序把 operation/toolCall/role 变为 UNKNOWN。分别在两阶段之间崩溃并恢复，调用计数保持原值、无死锁且状态最终不再 RUNNING。
- source commit 后、materializer 前崩溃由 reconciler 恢复。
- durable manual scan 的 PENDING/租约过期 RUNNING 恢复、总超时和旧 worker 最终 report fence。
- manual 事务 A 写 FINAL_PENDING 后崩溃，14:49:30 前按同 epoch/hash 恢复认证；超过内部线则只能落 DATA_BLOCKED/FINAL_CERTIFICATION_INTERRUPTED。事务 B 响应丢失重试不得产生第二次认证；report hash/epoch/proof 任一不一致均 fail closed。在 B 最后 SQL 后用 latch 暂停到 14:49:40 之后再 commit，该行即使为 FINAL_READY，读模型也必须返回 executionEligibility=EXPIRED。
- PREPARED 可恢复；DISPATCHED 后供应商已接收、本地提交前崩溃在无幂等支持时变 OUTCOME_UNKNOWN，断言不发生第二次供应商请求。
- 预算 reservation 插入后、PREPARED -> DISPATCHED 前故障必须整事务回滚；DISPATCHED 提交后但 transportHandoffAt 仍为空时，恢复只读原 reservation 并重过 egress gate，超界则在同一事务写 FAILED_BEFORE_SEND+SETTLEMENT_PENDING。分别在该拒绝提交后、预算结算前崩溃，以及以历史异常 FAILED_BEFORE_SEND+RESERVED 启动，BudgetSettlementReconciler 都必须零外发、按固定锁序仅结算一次并恢复全部额度。transportHandoffAt 已持久后崩溃才在无供应商幂等能力时保守进入 OUTCOME_UNKNOWN；所有恢复都不二次预占。
- Tavily/direct tool 在 PREPARED、原子预占+DISPATCHED 且 transportHandoffAt 为空、transportHandoffAt 非空但结果未知三个故障点分别验证：前两者使用原 executionId/reservation 恢复且不二次预占，第三者为 TOOL_OUTCOME_UNKNOWN；供应商请求计数始终至多一次。
- 同一原生 tool_call 重复投递只生成一个 logical tool-call reservation；一个 evidence tool call 产生一条 Tavily 加两条 direct execution 时，逻辑 evidence 配额只扣一次、Tavily 一次、direct 两次，任一未知结果不重跑整组。
- logical tool 预算不足时在一次可提交事务中保存零额 DENIED reservation 和 REJECTED/BUDGET_EXHAUSTED，重启不反复尝试；对 FINAL_FROZEN+plannedExecutionCount=0、全 PERSISTED、部分 PERSISTED+余下 FAILED_BEFORE_SEND、全 FAILED_BEFORE_SEND、任一 OUTCOME_UNKNOWN 和中间态逐行验证穷尽真值表。
- 完成历史不可覆盖。
- 复合父 FK 阻止跨股票/thread，父字段不可更新。
- 超过普通页面长度的 raw chunk、每个 opinion 长字段与 evidence 分页往返、逐页重建和详情“展开全部”字节一致；单个模型结构化响应不在内存聚合成无界字符串。
- persistent usage budget 在并发下不能超扣。
- H2 真实 APP_WALL_CLOCK_NOW() 的启动/持续样本必须推进；分别在启动 probe 失败、probe 后冻结、回退、watchdog/health proof 超过 750ms 四个故障点验证：SAME_DAY、RESEARCH_ONLY、continuation、Tavily/direct 和旧付费路由全部零新调用，新 source 不建 batch，既有 batch返回 TIME_AUTHORITY_UNAVAILABLE/INCOMPLETE 而非永久 PENDING。manual 行情报告仍可持久化，但事务 B 在已知坏时钟下即使真实时间越过 14:49:40 也只能 DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE；旧 FINAL_READY 的 executionEligibility 同样为 TIME_AUTHORITY_UNAVAILABLE，绝不能 ELIGIBLE。恢复必须重新取得两次推进样本。
- H2 与 PostgreSQL 对相同的内容写/锁时序分别让 coordinator 在 roleContentDeadline 后立即运行和临近 freezePublishAdmissionDeadline 但仍可准入时运行，四条 acceptedThroughCommitNo、basisHash、batch/round/advisory 必须完全相同；精确在 roleContentDeadline 申请 contentCommitNo 必须被拒绝。
- PostgreSQL 用 latch 让一个已在截止前取得 ledger 锁的内容短事务停留：sealer 必须等待其提交/回滚后封存；若锁持续到 freezePublishAdmissionDeadline，effectiveOutcomeAt 的唯一赢家为 SYSTEM_FALLBACK，后续不能生成 CONTENT_SNAPSHOT。AI 表长锁/小连接池耗尽不得阻断 manual 独立池的 report 持久化；时钟健康时也不得阻断 stage/certify。
- freeze 时原始四个 role 仍为 RUNNING 时，basis 必须把它们投影为 TIMED_OUT，batch/round/advisory 从 effectiveOutcomeAt 起均不得为 PENDING；原始 role=OUTCOME_UNKNOWN 且无封存 terminalContentCommitNo 时 raw status 必须仍为 OUTCOME_UNKNOWN、effectiveStatus=TIMED_OUT。closer 只复制投影/hash/fixedAt，任一 raw 终态永不覆盖；SYSTEM_FALLBACK 的三层状态一律为 INCOMPLETE。
- 在 reservation+DISPATCHED 已提交后用 latch 暂停 worker、跨过 latestEgressPermitAt 再进入 AiEgressGate：gate 必须拒绝 transportClosure，transportHandoffAt 不得写入，fake Provider/Tavily/direct/paid-route 调用计数均为零。
- 另在 AiEgressGate 已写 transportHandoffAt、但尚未调用 transportClosure 时暂停并跨过 permit deadline：允许原同步调用栈继续一次，记录 TRANSPORT_HANDOFF_DELAYED；断言不会取得第二次 permit或自动重试，精确在/晚于 roleContentDeadline 的返回正文被 content gate 拒绝，freeze hash 保持不变。该用例不虚假断言供应商调用计数为零。
- 两个真实 PostgreSQL 应用实例同时对同一 marker-null operation 进入 gate，并在赢家提交 marker 后暂停：MODEL、TAVILY、DIRECT、PAID_HTTP 四类各自必须恰有一个赢家和一次 fake transport 调用；输家返回 ALREADY_HANDED_OFF，不释放 reservation、不改 marker。H2 单实例用双线程执行同一合同。
- OutcomeBasisV1 使用固定 ID/时间/内容 golden vector 在 H2/PostgreSQL 产生字节完全相同的 canonical JSON 与 SHA-256；反转每张表的插入顺序和查询计划后 hash 不变，修改任一 raw chunk byte/opinion manifest/status reason 后 hash 必变。CONTENT_SNAPSHOT 在 14:48:43.900 取得 SQL 准入后制造 300–400ms 锁等待，断言准入时刻早于 14:48:44、实际 commit 可在其后但不晚于 14:48:44.500；14:48:44 后零新准入。14:48:45 fallback 与未决唯一键竞争若 500ms 内无决议必须 HTTP 503，绝不 PENDING/假 fallback；决议后只读唯一已提交赢家。
- 旧 scheduled snapshot 即使存在且 feature 后来开启，也不能被 reconciler 追溯 materialize、扣费或生成 source/batch。

### 22.4 模型与搜索

- Tool call JSON 合法与非法参数。
- symbol 越权、模型提供 URL、exact-host 旁路、eviltrusted.com、未知 COMPANY_IR。
- Tavily adapter 断言绝不对 result URL 发起二次请求；固定请求参数、include_domains、requestId 与 credits 落库。
- staged tool plan 覆盖零 parent、权威 direct parent 返回 0/1/多个允许目标、parent 全 FAILED_BEFORE_SEND、多个 parent 混合 PERSISTED/FAILED_BEFORE_SEND、任一 TOOL_OUTCOME_UNKNOWN，以及崩溃在 DISCOVERY_FROZEN、全部 parent 终止后 FINAL_FROZEN CAS 前/后：非 UNKNOWN 路径即使零目标也必须冻结 FINAL_FROZEN；相同 resultHash/终态集合派生相同 targetSetHash、排序和 executionId，plan 提交前后续 direct 调用为零，恢复不追加重复 execution。另以 Tavily 返回 0/1/多 URL 断言 tool execution 数不增加且服务器对这些 URL 的 HEAD/GET 始终为零。
- Cninfo 附件 fetcher 覆盖 localhost、私网、IPv4-mapped IPv6、DNS rebinding、逐跳重定向、超大 body 与错误 MIME。
- Tavily 自然日结果、精确 news 时间、直接公告时间、observedCutoffAt、role.sessionDeadline 与 latestEgressPermitAt 点时资格真值表；逻辑工具接纳必须满足 observedCutoffAt 严格小于二者最小值，每个物理 execution 仍单独经过 AiEgressGate。
- 无发布时间或截止后证据只能 RESEARCH_ONLY，不能触发 SAME_DAY RISK_ALERT。
- 网页 Prompt Injection 和历史模型文本不能增加工具/域名/预算；伪造 citationId 和 severe risk 被服务器拒绝。
- 搜索密钥和 LLM 密钥不出现在响应、事件或日志。
- 运行时配置 PUT 无内部 token 为 401；任意 apiKeyEnv、非官方 Base URL 与跨 Provider 复用旧 key 均被拒绝，GET 不回显 key。
- Nginx 未知 Host、DNS rebinding Host、cross-site Origin/Sec-Fetch-Site 与伪造 X-AI-Internal-Token 均不能触发付费调用。
- POST /api/ai/trend-analysis 与 POST /api/companies/{symbol}/agent-consensus/ai 均验证 token、Idempotency-Key、语义冲突 409、并发/日预算与供应商 at-most-once；保护未完成时发布配置断言路由关闭。
- 两个旧付费路由用 admittedAt=14:00:00 时 gate=+29.999s/+30.000s，以及 14:47:54.900/14:47:54.999/14:47:55/14:49:59.999/14:50:00 验证公式和严格 `<`：入口黑窗拒绝时 503、by-key=404，14:50 同 key/body 首次提交得到新 30 秒 permit；截止前已有 operation 后被 gate 拒绝则持久 FAILED_BEFORE_SEND_CUTOFF，原 key 终态且只有新点击/新 key。React 和旧 Vue 都覆盖 404 首提与 existing-operation 两条 sessionStorage 恢复，另验证 202、200、OUTCOME_UNKNOWN 和查询零付费。
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
- 响应丢失后 sessionStorage 保留提交时原请求体；by-idempotency-key 命中时不重发，404 时只重发完全相同字节语义的原请求，不能读取已变化表单；Java/TypeScript canonicalizer 共享测试向量一致。
- 旧 Vue 趋势分析/agent consensus 也在 POST 前持久 route/key/原语义 body；分别模拟空 body+symbol、表单变化、刷新、网络断开、POST 已成功但响应丢失和 14:47:55 截止，断言先查 operation、只有 404 使用同 key/原 body 重放，终态或用户明确新操作之前不换 key。
- 展开全部显示完整长文本。
- 规则动作与 AI 状态在视觉和语义上分离。
- HashRouter 深链 /#/short-term/discussions/:roundId 刷新可用。
- 短线偏好 v1 -> v2 迁移只执行一次：资金去向首次变为可见、其他保留项不丢、snapshotVisible 被删除；随后用户主动关闭资金去向仍能持久化。
- 短线 DOM 与结果视图开关均不存在“扫描快照”，今日资金去向默认存在；长期价投同名卡仍存在。
- 移除短线扫描快照卡后，ShortTermPage 对 scheduled-snapshots/latest 的请求计数为零；手动报告覆盖率阻断、右上角通知和 AI source materialization 回归通过。

### 22.6 可复制验证与现场验收

自动验证使用注入 Clock/数据库时间夹具、固定八候选 fixture、可记录请求次数的 fake streaming LLM/Tavily、Testcontainers PostgreSQL 和两个 API 实例。实施计划必须给出每条命令、预期计数与 SQL/HTTP/DOM 断言，至少包括：

- Maven 定向测试与 mvn test；
- React 定向 Vitest、npm test、npm run build；
- 旧 Vue Vitest 定向/完整测试与 npm run build；
- canonical schema 在 H2 文件副本和 PostgreSQL 上升级、重启；
- 模型 90 秒超时、Tavily 429/500/超时、一个/四个角色异常；
- 供应商接收后本地崩溃、工具请求结果未知、原子预算预占故障、聚合写失败、队列饱和、AI 存储/预算熔断；
- AI 故障注入前后确定性 payload hash、FINAL_PENDING/FINAL_READY 与认证时间完全一致。

注入 TimeAuthority health proof 自动证明 14:47:00 source/materialization admission 线、14:47:20 INITIAL 派发线、14:47:55 egress permit 线、14:48:30 严格内容接受线、14:48:44 CONTENT_SNAPSHOT SQL 准入线、14:48:45 不可逆 fallback/read 线和 14:49:40 手动认证门禁；另证明启动后冻结/回退 fail closed。真实交易日观察单独记录，不能用当天是否恰有候选替代自动证明。现场验收包括：

- 真实手动扫描只生成一个持久结果、一个 batch、四个初始角色；
- 后台讨论完成并从右上角消息进入完整详情；
- 有效 TAVILY_API_KEY 下完成一次可信 news 域名搜索并展示时间/引用；
- 同股继续讨论形成父子关系；
- 容器重启后历史仍存在；
- 应用跨过 14:30、14:47、14:49:50 均没有自动短线扫描、scheduled snapshot 新行或 AI 付费调用；只有用户点击才产生手动结果；
- 14:49 至 14:50 人工触发 AI 故障时，手动确定性扫描仍正常返回或按原门禁失败关闭。

## 23. 验收标准

1. 改变 PE/PB 不会改变当前短线任何候选、复核顺序、identity、完整度、交易计划、验证资格、动作、解释或历史；当前版本不调用估值计算器。
2. 短线页面及历史详情看不到 PE/PB 与估值语境，也不再通过 strategy-bundle 产生长期 ledger；长期入口不受影响。
3. 成功扫描后 HTTP 请求不等待 AI；一个最多八候选 batch 只创建四个真实并行初始角色会话。
4. 用户可以查看四角色完整输出、原始响应、工具过程、引用、错误和模型版本。
5. 模型可以发起受控联网搜索；同日意见只接收 registry 可信且可证明不晚于截止的证据，其他结果明确 RESEARCH_ONLY。
6. Tavily result URL 不被服务器抓取；直接公告 fetcher 不能访问内网/越权域名；不存在密钥泄漏、伪 citation 或网页提示词越权。
7. 不对模型论述做业务层长度截断；供应商截断被识别、保存并按规则续写。
8. 任一或全部 AI 异常、队列饱和、预算/存储熔断和 materializer 失败都不改变手动确定性 payload hash 或 14:49:40 认证门禁。
9. 同一股票所有历史轮次可查，新轮次正确链接父轮次并显示观点变化。
10. 讨论记录在 H2/PostgreSQL 中持久化，容器重启后可追溯；PostgreSQL 的租约 epoch 能 fence 所有旧 owner 写入。
11. 重复 HTTP、误点、响应丢失、dispatcher 重试和多实例 gate 竞争不生成重复 source/batch/initial invocation 或 transport；marker-null 只有一个 handoff 赢家，DISPATCHED 未知结果在无供应商幂等键时由专用 owner 收口 OUTCOME_UNKNOWN，停止自动重发。
12. 发布 Web 只绑定 loopback、API 不暴露宿主端口、付费触发必须通过可信代理 token；专用付费端点无 token 401，scan-jobs 无 token 仍完成确定性扫描但不触发 AI，缺 token 时 AI feature 关闭。
13. 后端完整测试、React 测试与构建、旧 Vue 新增恢复单测与构建、schema 启动、容器健康和浏览器真实链路验收通过。
14. H2 文件副本和真实 PostgreSQL 升级、OpenAPI/React 契约、fake streaming Provider 与浏览器现场链路全部通过。
15. 每个阶段的规格与对抗审查均未留下 Critical 或 Important，最终全分支红队也通过。
16. ShortTermScanScheduler、ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 与 shortTermScheduledExecutor 均不再是生产 bean，ShortTermExecutionSettings 无 cron/自动 scanRequest，历史 scheduled repository/store/API 从 Spring Data 到 controller 全链只读且一律 non-executable；PRESELECT、FINAL、READINESS_GUARD 注册和 catch-up 全部为零，跨过旧触发时间或重启都不产生自动扫描、新 scheduled snapshot、AI source 或费用；ShortTermValidationOutcomeScheduler 与 TradeOutcomeScheduler 不受影响。
17. 阶段 F 完成后，短线新用户和一次性迁移的旧用户默认看到“今日资金去向”，结果视图中没有“扫描快照”开关或卡片，ShortTermPage 不再轮询 scheduled-snapshots/latest；用户之后可主动隐藏资金去向，长期价投和历史 scheduled snapshot 只读兼容 API 不受影响。
18. TimeAuthority 启动或运行期健康证明失败时所有 AI 类型都零新外发且无永久 PENDING，manual 认证/executionEligibility 也 fail closed；OutcomeBasisV1 在 H2/PostgreSQL 字节/hash 一致，14:48:44 后无新 CONTENT_SNAPSHOT SQL 准入，14:48:45 fallback 对未决唯一键返回 503 而非 PENDING，数据库决议后只有不可逆唯一赢家。

## 24. 多 Agent 实施与对抗复核协议

### 24.1 角色分工

主 Agent 只承担规格所有权、共享接口冻结、阶段集成、驳回裁决和最终验收。实现任务由每阶段新的开发 Agent 执行，审查 Agent 不得审查自己写的代码。

阶段划分：

| 阶段 | 开发责任 | 强制对抗重点 |
| --- | --- | --- |
| S | 删除 ShortTermScanScheduler/ShortTermScheduledScanService/ShortTermScheduledExecutorConfig，删除专用 executor，收窄并重命名 settings/store，把底层 repository 也改为 SELECT-only，保留 non-executable 历史只读 API，保护其他调度器 | 旧 cron/property/runNow 仍触发、隐藏 JpaRepository mutator、GET 偷写/合成等待、重启补跑、追溯写 scheduled/AI、误关全局 scheduling 或其他业务任务 |
| A | PE/PB 决策移除与 React 完全隐藏 | 寻找任何隐藏估值影响、兼容破坏和长期模块误伤 |
| 0 | 冻结 canonical DDL、OpenAPI、OutcomeBasisV1/其他 JSON Schema、状态枚举、错误码与生成类型 | 合同矛盾、不可执行 CHECK、跨库 hash 漂移、字段遗漏、长内容和兼容 |
| B | 讨论领域模型、schema、Repository、原子认领与恢复 | 并发初始化、重复收费、租约竞态、重启数据完整性、H2/PostgreSQL 差异 |
| C | 四角色编排、LLM 长输出、工具调用、Tavily 与安全网关 | SSRF、DNS/重定向、Prompt Injection、点时越界、密钥外泄、工具死循环 |
| D | API、React 状态、完整讨论页、历史与继续讨论；同时负责旧 Vue `apps/web` 两个付费入口的 sessionStorage/by-key 恢复与两种 cutoff 路径 | 内容截断、父链错误、轮询泄漏、规则动作与 AI 混淆、旧 Vue 漏实现、权限和幂等 |
| E | 手动时间窗集成、数据库级 AI 隔离、故障注入、容器与真实链路 | 14:47:00 admission/14:47:20/14:47:55/14:48:30/14:48:44 SQL admission/14:48:45/14:49:30/14:49:40 竞态、持续时钟健康、content seal/freeze、egress handoff 暂停、AI 阻塞手动扫描、重启、多实例与现场可用性 |
| F | 短线结果视图偏好迁移、资金去向默认展示、扫描快照卡移除 | 旧偏好迁移循环、误删后台 snapshot、资金流无数据误导、长期页面误伤 |

依赖 DAG 固定为 S -> A -> 0 -> B -> C -> D -> E -> F。S 和 A 都可作为独立可发布变更，但本分支在完整功能交付前不默认开启 AI。B 至 E 共享领域合同，严格串行，不因“多 Agent”而并行修改共享文件；F 只在 E 全链路验收后开始，满足“做完这份之后”再调整结果视图。每阶段使用新的开发 Agent；规格审查与对抗审查可并行只读运行。

阶段 D 的独占前端范围明确包含 `apps/web-react` 与旧 `apps/web` 中 TREND_ANALYSIS/AGENT_CONSENSUS 调用、共享 paid-operation recovery helper 和 package 测试配置；必须为旧 Vue 增加可执行单测（允许引入 Vitest），红测覆盖 pre-operation 503/by-key 404/14:50 同 key 首次提交，以及既有 FAILED_BEFORE_SEND_CUTOFF 必须新 key 两条路径。Stage D 以 React 测试、旧 Vue 单测和两端 build 为绿测；Stage E 再与真实 API/TimeAuthority 联调。任何 Agent 都不能把旧 Vue 仅留到最终 build 才发现。

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

### 24.3 七个强制红队检查点

1. 自动任务移除红队：跨旧 cron 时间、重启、双实例和旧 scheduled 数据存在时，尝试证明仍会自动扫描、补跑、写新 snapshot/AI source，或误伤另两个业务 scheduler。
2. 去估值红队：只改变 PE/PB、旧快照和兼容 DTO，尝试证明候选发生变化。
3. 数据红队：并发双实例启动、重复入队、租约失效、崩溃恢复和长文本往返。
4. 联网红队：Tavily URL 不抓取、Cninfo 受限抓取、私网/重定向/DNS rebinding、网页注入、截止后新闻和密钥诱导。
5. 追溯红队：不完整响应、父子轮次、全部历史、内容不截断、失败过程可见。
6. 发布红队：让模型和搜索同时超时、AI 数据库短暂失败、队列/预算/存储熔断，尝试阻断或污染同时运行的手动确定性结果。
7. 结果视图红队：构造旧 v1 偏好、损坏偏好和用户在 v2 主动关闭资金去向，尝试证明迁移反复覆盖用户选择，或移除 UI 时误删历史只读/长期 snapshot 链路。

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
- 旧 Vue 恢复单测与构建；
- H2/PostgreSQL schema 验证；
- 容器重建和健康检查；
- 浏览器真实手动扫描、AI 讨论、联网证据、历史、继续讨论和重启恢复；
- 浏览器验证今日资金去向默认展示、v1/v2 偏好迁移、扫描快照卡消失、scheduled-snapshots/latest 请求为零且历史只读 API 仍可查询；
- 注入 TimeAuthority health proof 证明旧 cron 时间点零自动扫描，并在 fake Provider、PostgreSQL 双实例和故障注入下验证 14:47:55 后零新 egress permit、marker 单赢家、14:48:30 后零正文接受、14:48:44 后零 CONTENT_SNAPSHOT SQL 准入、14:48:45 唯一不可逆结果/未决时 503、运行期坏时钟全 AI 与 manual 认证 fail-closed，以及 14:49:40 手动认证门禁。

只有全部通过且无 Critical/Important 发现，才可合并和推送。任何阶段反复失败都继续驳回修复，除非出现必须由用户决定的真实产品歧义或外部凭据阻塞。

## 25. 实施前仍需满足的依赖

- 用户书面确认本文。
- 书面实施计划列出具体文件、测试、阶段 SHA 和 Agent 门禁。
- 发布环境提供受控搜索所需的 TAVILY_API_KEY；没有密钥时不得伪称实时联网已验收。
- 发布环境提供至少 256 bit、base64url 无换行的随机只读代理 token，并保持 Web loopback、API 无宿主端口；未满足时 AI feature 保持关闭。
- 现有主分支基线测试通过；若存在基线失败，先记录并与本功能失败区分。
