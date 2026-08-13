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
| 旧最终可执行资格截止 | 14:49:40 |
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
10. 实施采用多 Agent 分阶段开发和独立对抗复核；发现 Critical 或 Important 问题即驳回。当前受审阶段代码或任一上游冻结合同变化会使该范围旧审查结论失效，修复并重新完成两类复审后才能进入下一阶段；纯下游实现或只追加审查账本不反向改变已经固定的 reviewed tree/hash。
11. AI 讨论全链路完成后，短线结果视图默认展示“今日资金去向”，移除“扫描快照”结果卡及其显示开关；旧 scheduled snapshot 只读保留用于历史审计，不再作为新 AI 触发源。
12. 取消所有短线自动调度：14:30 预选、14:47 尾盘最终和 14:49:50 就绪检查均不再注册或补跑；短线候选只由用户主动手动扫描生成。React 主按钮明确命名为“扫描并讨论”（重试为“重新扫描并讨论”），且只由该明确动作在请求体写 `requestAiDiscussion=true`；可信代理身份、feature/config 可用性只能决定能力是否允许，绝不能替用户补写意图。AI 另外只可由用户明确点击“继续讨论”触发。
13. 时间敏感写统一由 GuardUse 深模块完成“业务锁齐全 -> authority 单语句原子取时/推进并提交不可复用 ticket -> 同事务 CAS/marker”；应用代码不能在持有 authority 行锁时暂停，也不能拿裸 decision。source 的认证事实与最终讨论用途正交，linkage 不复制 source deadline。

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
- 交易日手动结果只有在数据库时间 14:47:00 前完成确定性认证，并由 materializer 在该线前取得一次合法 materialization admission，才有资格尝试 SAME_DAY_RESEARCH；已 admission 的事务可能在 14:47 后才对其他事务可见，但四个 INITIAL 仍须在 14:47:20 前派发。未赶上任一线标 AI_DEFERRED_CUTOFF/INITIAL_DISPATCH_CUTOFF；只有原 materialization 窗口与尾盘黑窗相交并取得一次性延期资格的 source，才在同日 14:50 后、14:52 前作为 RESEARCH_ONLY，其他 source 按自身 +120s/尾盘封顶 deadline 失败。
- 尾盘角色最迟在 14:47:55 前从统一 AiEgressGate 取得 egress handoff，为 14:48:30 内容接收截止留出 35 秒；14:48:45 是不可逆 outcome 的生效点。在发布支持负载（同一 operator 同时最多 4 个活跃 batch）、健康数据库且无 JVM/OS/DB 暂停时，专用 OUTCOME_FINALIZER 必须在 effectiveOutcomeAt 后 250ms 内开始，500ms 内提交唯一 freeze 或形成稳定 503，React 对活跃 batch 的轮询间隔不超过 1 秒，因此尾盘结果/明确失败目标最晚 14:48:46.750 可见，早于 14:49。模型超时、全角色异常、队列或预算失败都属于该 SLO；数据库不可用、进程/宿主暂停只服从安全不变量：任何在 effectiveOutcomeAt 后完成的读只能返回唯一 freeze 或稳定 503/PERSISTENCE_UNAVAILABLE，绝不返回 PENDING。这里也只保证“截止后不再创建新 handoff”，不把任意基础设施暂停伪装成物理网络包硬实时保证。
- 不再宣称某个毫秒之后数据库物理上“绝对零事务”。AI 表、连接池和 executor 与确定性扫描隔离；截止后 AI 审计写不能改变已提交 outcome freeze、不能触碰确定性表，也不能阻塞手动结果的两条不同门禁：MANUAL_CERTIFY 准入截止为 14:49:30（等号允许），FINAL_READY 只读 executionEligibility 截止为 14:49:40（等号允许）。
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

### 7.5 短线估值隔离策略

仅从候选 DTO/PROMPT 删除字段仍不足以证明 PE/PB 不会经模型联网或自由文本回流。Stage 0 必须冻结版本化 `ValuationIsolationPolicyV1`，并且只作用于短线扫描、短线 AI、短线历史与其公开 API；长期价投模块不套用该策略。策略覆盖 continuation question、模型 query、工具返回的 title/snippet/excerpt/sourceName、所有会进入模型或公开 UI 的 provider 文本、URL 的 Unicode host 显示形式与严格 percent-decode 后的 path/query key/value/fragment、历史工具投影、结构化 opinion 全部文本字段和原始模型 chunk。匹配 shadow pipeline 的顺序逐字固定：严格 UTF-8；最多三轮有界 HTML entity 解码，仍有可解码嵌套实体则保守拒绝；Unicode NFKC 与 locale-independent case fold；任何 `Bidi_Control` 直接拒绝；从 shadow 移除全部 `Default_Ignorable_Code_Point`（包含零宽连接/非连接字符），使视觉拆分 token 重新相邻；按 Stage 0 冻结的 Unicode revision、UTS #39 `confusables.txt` SHA-256 与单次 skeleton 算法生成另一个只用于判定的 confusable shadow，至少覆盖 Latin/Greek/Cyrillic 混用和纯同形写法；再对原 shadow 与 confusable shadow 分别做全半角、点号、斜杠、连字符、空白与 Stage 0 separator 集归一化。URL 先完成 HTTPS/host/SSRF 校验，再对 path/query/fragment 最多三轮严格 UTF-8 percent decode；非法编码或三轮后仍含有效 `%HH` 嵌套编码一律保守拒绝。最低禁止集包含独立金融token `PE/P-E/P.E/P/E/市盈率/盈利倍数`、`PB/P-B/P.B/P/B/市净率/账面倍数`、`PEG`、`valuation/估值/估值水平/估值折价/估值溢价` 及 Stage 0 冻结的中英文变体；两个 shadow 都必须命中完整词法边界或冻结的金融上下文，不能把 open、type 或普通多语种文本的字母子串误判；但 skeleton 精确形成独立 `PE/PB/PEG` 时必须拒绝，例如 `PΕ`、`РВ`。confusable shadow 与命中版本只存 hash/revision/concept，永不替换或对外返回原文；只有 CLEAR 才保留原文，命中按所在 surface 的拒绝规则处理。

隔离点和结果唯一固定：

- Prompt 与 tool schema 明示短线不得请求、比较、引用或输出上述概念；模型仍提交命中 query 时，`LogicalToolAdmissionGate` 将该原生 tool call 记为 `VALIDATION_REJECTED/VALUATION_QUERY_FORBIDDEN`，有逻辑call余额时消耗一次以阻止循环，余额已满时只写零额DENIED reservation但同样必须持久taint，不能让BUDGET_EXHAUSTED遮蔽策略命中；两支都零 Tavily/direct execution、零外部预算、零 evidence，并只把固定拒绝码返回模型。四个角色都是一次同时分析最多八只股票的批次级上下文，无法证明一次 query 只影响参数中自报的 symbol；因此任一原生 tool call 的 query 命中时，必须对所属 role 持久写一次不可清除的 `valuationQueryTaint=true`，并使该 role 在本 batch 的全部股票 opinion 都不可聚合。参数中的 symbol 仅保留为审计事实，不能缩小污染范围。参数规范化与估值检测必须发生在同一tool admission事务的预算结果分支之前；业务校验优先级不能因剩余额度不同而改变taint事实。
- Provider 返回项的 title/snippet/excerpt/sourceName、URL host/path/query/fragment 或未来任何模型/UI 可见字段任一命中时，整项只保存 requestId、contentHash、urlHash、命中 concept code/surface 和 `visibility=INTERNAL_POLICY_AUDIT` 的有界审计事实；title/snippet/excerpt/sourceName/url/content_text 必须为空，不得建立可引用 evidenceId，不得把原文、数值、完整 URL 或链接摘要回传模型。内部 `valuationPolicySurfaces` 只允许 TITLE/SNIPPET/EXCERPT/SOURCE_NAME/URL_HOST/URL_PATH/URL_QUERY/URL_FRAGMENT，公开 OpenAPI 不暴露。未命中的同一响应项独立处理，不能把整个正常响应静默丢失；模型工具结果对 CLEAR evidence 也只接收 evidenceId/sourceType/canonicalDomain 和经过策略的文本，不接收完整 URL，完整已校验 URL 仅供公开 UI 外链。
- `search_discussion_history`、父轮预载和所有短线公开查询只投影已经通过本策略的结构化内容；旧历史中存在估值文本时，对模型与公开端只返回通用 `SHORT_TERM_POLICY_CONTENT_WITHHELD` 占位和原记录 hash，不返回内部 concept code、该文本或数值；`VALUATION_CONTENT_WITHHELD` 只保存在受限审计事实中。
- Opinion 任一公开结构化字段命中时，服务器保留受限 raw transport 审计并记内部`VALUATION_CONTENT_REJECTED`，且被拒字段绝不插入opinion_content_chunk/公开content manifest。raw 分类只允许两个 role-wide taint scope：由 provider 原生 tool-call framing 唯一归属到一个完整 argument stream 的 TOOL_ARGUMENT 命中为 `ROLE_QUERY`；CONTENT、REASONING、结构化正文命中，或无法证明属于原生 tool-call argument stream、混入自由文本、序列损坏的 TOOL_ARGUMENT 命中为 `ROLE_OUTPUT`。两者都单调污染所属 role 的全部股票，参数 symbol 不参与缩小范围。`severeRisks.claim`属于结构化正文而非query，命中时固定ROLE_OUTPUT，不能伪装成ROLE_QUERY。同一内容双重命中时 ROLE_OUTPUT 优先；无法在角色终态前完成 framing/重构时也保守升级 ROLE_OUTPUT。ROLE_QUERY 使该role在本batch全部opinion固定`validated=false,qualifiedAdvisoryUse=null,qualificationReason=VALUATION_QUERY_FORBIDDEN`；ROLE_OUTPUT 固定使用`VALUATION_RAW_OUTPUT_REJECTED`。两者都不进入任何股票的意见聚合。`/invocations/{id}/chunks` 对策略拒绝chunk只返回 hash、byteCount 与通用 `SHORT_TERM_POLICY_CONTENT_WITHHELD`，不返回内部concept code或正文。这里的策略拒绝不是对合法研究内容做业务截断，而是明确拒绝一个不属于短线合同的输入。
- 流式raw不能逐chunk天真匹配：scanner 对 CONTENT/REASONING 按 `(invocationId,contentType)`，对 TOOL_ARGUMENT 按 `(invocationId,nativeToolCallStreamKey)` 分别依 sequence 保留足以覆盖最长规范化token、三轮entity/percent状态与Default-Ignorable跨界的carry，先完成同一shadow pipeline再跨边界匹配。provider stream adapter 在收到每个原生 tool-call 增量的第一帧时冻结从0开始的 `nativeToolCallOrdinal` 与 `nativeToolCallStreamKey=SHA-256(invocationId|ordinal|规范化providerToolCallId-or-MISSING)`；所有 argument chunk 在首次落库时即携带二者，多个原生 tool call 即使交错也不得合并 carry 或事后猜归属。完整参数形成 logical tool-call 后再把对应chunk单调绑定policyToolCallRowId；缺失/重复provider ID由ordinal保持稳定，framing损坏则按ROLE_OUTPUT。chunk初建固定`publicVisibility=PENDING_POLICY`，只有扫描到足以证明该chunk全部规范化代码点安全的后续边界或流终结后才单调变PUBLIC或INTERNAL_POLICY_AUDIT；跨chunk命中时，承载该命中规范化跨度的全部尚未公开chunk都必须变INTERNAL，不能只拒绝最后一段。公开API对PENDING遵守第17.1节连续前缀/202协议，对INTERNAL只返回withheld元数据。角色任何终态前必须把全部PENDING收口，无法证明安全的尾段保守INTERNAL，防止先展示前半段后才发现跨chunk的P/E旁路。
- React、旧 Vue、导出和历史详情都不得渲染 `INTERNAL_POLICY_AUDIT` 内容或内部valuation concept code；公开页面只显示通用“该内容不属于短线策略，已隐藏”。内部审计读取需要独立权限且不属于本次短线页面。任何调用方不得通过“展开原始响应”绕过隔离。
- 讨论事件、tool-call状态、opinion qualificationReason 与错误响应的公开投影遇到任何 `VALUATION_*` 内部代码时都统一映射 `SHORT_TERM_POLICY_CONTENT_WITHHELD`；公开OpenAPI不枚举内部concept code。模型tool消息与受限运维审计仍可使用精确内部代码，以便阻止循环和排障，但两者都不是短线用户页面的数据源。

策略 revision、命中 concept code、输入/输出 hash 和处置结果进入不可变事件；不得保存 API Key、Authorization 或任意密钥。当前策略变形测试除直接字段外，还必须覆盖工具 query、provider snippet、历史文本和模型输出四条回流路径。

CONTINUATION question 是同一策略的入口 surface，但必须在任何副作用前处理：HTTP body 先完成严格 UTF-8/JSON/4096-byte 校验，然后在零锁、零数据库、零持久日志正文阶段运行 `ValuationIsolationPolicyV1`；只有 CLEAR 才计算 questionFingerprint、进入 Idempotency/continuation guard、预算、source 与 Prompt 装配。命中固定返回通用 `400 SHORT_TERM_QUESTION_POLICY_REJECTED` 和“问题包含短线讨论不支持的内容”，响应/日志不得含 `VALUATION_*`、concept code、原 question、hash 或命中片段；source/guard/attempt/budget/storage/event/audit 行与所有模型/Tavily/direct 调用均为零，只允许无内容的进程内聚合指标。同一 key 被策略拒绝后以合法 question 提交，视为第一次持久操作。

## 8. AI 讨论触发、批次和状态机

### 8.1 持久化触发源

讨论批次支持：

- MANUAL_SCAN：手动扫描的持久化结果成功提交后触发；扫描 HTTP 请求不等待 AI。
- CONTINUATION：用户从一个已结束父轮次继续讨论，形成只含该股票的新批次。

source type 只定义 MANUAL_SCAN、CONTINUATION。阶段 S 删除 ShortTermScanScheduler、仅供它调用的 ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 及 shortTermScheduledExecutor bean；ShortTermScheduledSnapshotStore 拆成只读历史查询接口，生产 bean 不再暴露 prepare/claim/save/publish/runNow/expire 等 mutator。底层 ShortTermScheduledSnapshotRepository 不得继承 JpaRepository、CrudRepository 或其他暴露 save/delete/flush 的接口；它只继承 Spring Data 标记 Repository 并声明 SELECT 查询，无 @Modifying 方法。PRESELECT/FINAL/READINESS_GUARD 枚举与 DTO 只为旧行反序列化保留。ShortTermAutomationSettings 重命名为 ShortTermExecutionSettings，只保留手动路径仍使用的 finalDeadline、freshness 和 overnightRules；删除 enabled/zone/三个 cron/自动 scanRequest 配置与动态刷新入口，旧 cron property 即使存在也必须被忽略。应用启动时不注册 14:30/14:47/14:49:50 task，也不对历史 scheduled snapshot 自动补讨论。

扫描结果与 AI materialization 之间使用“持久源 + after-commit materializer”：

1. 手动扫描在接受 HTTP 请求时先提交持久化 PENDING manual result，再由 worker 原子改为 RUNNING/SUCCEEDED/FAILED；只有 SUCCEEDED 提交后才可能触发 AI。内存 job 只作为缓存，重启后由持久任务恢复，不能成为唯一事实源。
2. manual result 在首次提交时冻结 aiRequested、featureRevision 和 requestSemanticHash；以后开启 feature 不能追溯触发旧扫描。
3. after-commit source/linkage 信号只负责低延迟唤醒，不是恢复事实源；其丢失或异常不得回滚、延迟或改写确定性结果。
4. `ManualAiIdentityReconciler` 对 aiRequested=true、已成功、候选非空且 deterministicStatus 属于 FINAL_READY/CACHE_PREVIEW/RESEARCH_PREVIEW 的每个 MANUAL_SCAN，先幂等 ensure/read source admission，再按其已提交状态分支：只有 MATERIALIZATION_PENDING/DEFERRED_CUTOFF 才 ensure 依附该 admission 的 manual linkage，linkage=PENDING 才重新唤醒 worker；七个不可逆终态严格零 ensure/零 wake；MATERIALIZED 只读并核验 committed batch 与既有 RESOLVED linkage，缺失/不一致返回 503 CORRUPTED_MATERIALIZATION，绝不补建。linkage 不得先于 source 独立存在。它也处理已持久化的 pending continuation，但绝不扫描旧 scheduled snapshot、创建 manual result 或重跑行情。
5. AI 表、队列或 materializer 不可用时，确定性结果照常成功；前端根据 admissionStatus/reasonCode 显示 AI_MATERIALIZATION_PENDING 或 AI_UNAVAILABLE，不伪称已入队。
6. materializer 为 source 的全部确定性候选先写 candidate admission，再按 ruleRank ASC、symbol ASC 选前八为 SELECTED 并创建 round；其余写 NOT_SELECTED_LIMIT、roundId=NULL。前端不能让这些候选永久显示等待。

数据质量阻断、扫描失败或候选为空时，manual result 自身保存 aiAdmissionReason=NOT_APPLICABLE，前端立即停止等待；reconciler、两个after-commit handler、GET和恢复均永远不为这类结果创建source/linkage/batch/费用，也不存在14:50后补镜像。Source枚举中的NOT_APPLICABLE只用于一个原本合格且已存在的PENDING/DEFERRED source在锁后发现确定性identity、候选payload或schema已不可合法物化时的不可逆收口，并必须同步失败既有PENDING linkage。

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
- DEFERRED_CUTOFF
- MATERIALIZED
- NOT_APPLICABLE
- FEATURE_DISABLED
- BUDGET_EXHAUSTED
- STORAGE_PRESSURE
- TIME_AUTHORITY_UNAVAILABLE
- MATERIALIZATION_INPUT_RESOURCE_LIMIT
- MATERIALIZATION_FAILED

讨论用途 `materializedAdvisoryUse`（source materialize 前为 null；batch/round 创建时复制）：

- SAME_DAY_RESEARCH
- RESEARCH_ONLY

queueAdmission：

- ACCEPTED
- DEFERRED

执行器队列满不产生不存在的 QUEUE_REJECTED 终态。数据库 batch 保持 QUEUED，batch.queueAdmission=DEFERRED，稍后由 dispatcher 重试。DEFERRED_CUTOFF 是“当前窗口已有明确结论、等待 nextRetryAt”的可恢复 source 状态，不是仍在执行的 PENDING；页面停止高频 source 轮询并显示“未赶上同日 AI，14:50 后转研究讨论”。raw role 终态只允许停止该角色的进度动画；raw batch/round 终态不是公开 outcome 最终事实。batch outcome 轮询只有在读到并校验 committed freeze、`outcomeFinal=true,freezeRevision=1` 后才停止，或在稳定 503 时保持上次画面并继续恢复；raw 终态到 freeze 之间显示“角色已结束，正在固定最终结果”，不得弹最终成功/共识消息。FEATURE_DISABLED、BUDGET_EXHAUSTED、STORAGE_PRESSURE、TIME_AUTHORITY_UNAVAILABLE、NOT_APPLICABLE、MATERIALIZATION_INPUT_RESOURCE_LIMIT 与 MATERIALIZATION_FAILED 是不可自动复活的 source 终态。

reason 到 source 状态的映射唯一固定：不合格manual结果的数据阻断/扫描失败/空候选只写manual.aiAdmissionReason=NOT_APPLICABLE且根本不创建source；仅一个已存在的合格PENDING/DEFERRED source在锁后发现冻结schema不支持、identity payload失效或合法候选变为零时才收口source=NOT_APPLICABLE。feature/模型配置不可用为 FEATURE_DISABLED；预算不足为 BUDGET_EXHAUSTED；存储水位为 STORAGE_PRESSURE；canonical materialization payload 超过4194304字节固定为 MATERIALIZATION_INPUT_RESOURCE_LIMIT/MATERIALIZATION_INPUT_RESOURCE_LIMIT；TimeAuthority 失败为 TIME_AUTHORITY_UNAVAILABLE；满足第 15.3 节一次延期资格才为 DEFERRED_CUTOFF/AI_DEFERRED_CUTOFF，nextRetryAt 固定 source 日期 D14:50；队列暂满是 MATERIALIZED+batch QUEUED；deadline/重试耗尽为 MATERIALIZATION_FAILED。输入超限禁止映射成 STORAGE_PRESSURE、NOT_APPLICABLE 或普通重试失败。

`status/executionStatus` 始终表示原始执行事实；`effectiveStatus` 是唯一 `short_term_ai_outcome_freeze` 行内的截止投影，可为 COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED，但不是原状态机的一次转换，也不复制回 batch/round/role。raw 终态不可覆盖，读页面在 effectiveOutcomeAt 后只从 freeze 取 effectiveStatus，live raw status 仅作 basis 外审计。CONTENT_SNAPSHOT 的角色 immutable fact -> effective 映射唯一固定：封存范围内存在 terminal content fact 时 rawFactKind=SEALED_TERMINAL，COMPLETED/INCOMPLETE/FAILED/TIMED_OUT/CANCELLED 保持同名，OUTCOME_UNKNOWN 映射 INCOMPLETE 且 effectiveReasonCode=OUTCOME_UNKNOWN；不存在该 immutable fact 时固定 rawFactKind=UNSEALED、rawStatus=null、terminalContentCommitNo=null、effectiveStatus=TIMED_OUT、effectiveReasonCode=UNSEALED_TERMINAL_EXCLUDED。SYSTEM_FALLBACK 不伪装为自然超时，按第15.20节专用真值表把四角色、round、batch、advisory全部投影INCOMPLETE。两类都绝不读取 seal 后 live role.status 参与 hash，effectiveStatus/OutcomeBasisV1 永不包含 OUTCOME_UNKNOWN。

状态转换合同：

| 实体 | 合法主转换 |
| --- | --- |
| Source | MATERIALIZATION_PENDING -> MATERIALIZED/DEFERRED_CUTOFF；DEFERRED_CUTOFF -> MATERIALIZED；MATERIALIZATION_PENDING/DEFERRED_CUTOFF 也可到 NOT_APPLICABLE/FEATURE_DISABLED/BUDGET_EXHAUSTED/STORAGE_PRESSURE/TIME_AUTHORITY_UNAVAILABLE/MATERIALIZATION_INPUT_RESOURCE_LIMIT/MATERIALIZATION_FAILED；上述七个不可逆终态与 MATERIALIZED 无出边 |
| Batch | QUEUED -> RUNNING；QUEUED/RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Round | PENDING -> RUNNING；PENDING/RUNNING -> COMPLETED/PARTIAL/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED |
| Role | QUEUED -> CLAIMED -> RUNNING；QUEUED/CLAIMED/RUNNING -> TIMED_OUT/CANCELLED；RUNNING -> COMPLETED/INCOMPLETE/FAILED/OUTCOME_UNKNOWN |

终态不可自动回到运行态。OUTCOME_UNKNOWN 只能由用户显式发起“继续讨论”创建新的 batch，原 role session 和原事件保持不可变。

executionStatus 的派生使用以下有序且穷尽合同，避免各端自行解释：

- Role 正常得到 batch 中每个 symbol 的可解析结构化结果才是 COMPLETED；正常返回但结构化不全、输出上限或资源上限为 INCOMPLETE；任何 deadline coordinator 从 QUEUED/CLAIMED/RUNNING 收口均为 TIMED_OUT；已发送结果未知为 OUTCOME_UNKNOWN；发送前不可恢复错误为 FAILED；显式用户/运维取消且尚未 DISPATCHED 为 CANCELLED。
- Role 的五类常规 raw 终态 `COMPLETED/INCOMPLETE/TIMED_OUT/FAILED/CANCELLED` 与 `OUTCOME_UNKNOWN` 使用同一个 version CAS 集合：只有从非终态到终态的首个已提交事务可更新 status，任何终态到另一终态影响行数必须为 0。若 deadline coordinator 先赢得 TIMED_OUT，UNKNOWN finalizer 仍把 invocation/toolExecution 写 OUTCOME_UNKNOWN/TOOL_OUTCOME_UNKNOWN，并追加 ROLE_TERMINAL_ALREADY_TIMED_OUT 审计事件，但不得覆盖 role；若 UNKNOWN 先赢，deadline coordinator 同样不得覆盖。effective freeze 仍只承认 seal 范围内 immutable terminal fact。
- Round 先判断是否仍有 role 非终态：四个均为 QUEUED 时是 PENDING，只要任一已经 CLAIMED/RUNNING 或已有终态就是 RUNNING。全部 role 终态后，再判断完整且 validated opinion：四份齐全为 COMPLETED；一至三份为 PARTIAL。若为零，再按顺序判断：任一 role 为 OUTCOME_UNKNOWN、INCOMPLETE，或存在无法归属到 symbol 的 raw chunk，为 INCOMPLETE；四个 role 全部 TIMED_OUT 为 TIMED_OUT；四个全部 FAILED 为 FAILED；四个全部 CANCELLED 为 CANCELLED；其他任意混合终态（例如 FAILED+TIMED_OUT、OUTCOME_UNKNOWN+FAILED、CANCELLED+TIMED_OUT）统一为 INCOMPLETE，并保存 MIXED_TERMINAL_STATES。
- Batch 在 role 非终态时，四个均为 QUEUED 才是 QUEUED；任一 role 已 CLAIMED/RUNNING 或已有终态就是 RUNNING。全部 role 终态后，全部 round COMPLETED 为 COMPLETED；任一 round 为 COMPLETED/PARTIAL 且非全完成为 PARTIAL；否则若全部 round TIMED_OUT/FAILED/CANCELLED 中同一种则取该状态；其他所有终态组合为 INCOMPLETE。空 round batch 禁止创建。
- advisoryState 始终按第 10 节逐股票计算，与 executionStatus 正交；例如 executionStatus=PARTIAL 的 round 仍可能因合格反方证据得到 RISK_ALERT。

### 8.3 队列、角色会话与执行器

- 一个有界 dispatcher 负责 materialization 和 batch 编排；每个 batch 固定创建四个并行 role session，每个 session 一次分析最多八只候选并为每个 symbol 输出独立 opinion，缺失 symbol 记 INSUFFICIENT，不补开第五个初始会话。模型网络期间不持数据库锁/事务。
- 每个 role session 有 claimOwner、claimToken、单调 claimEpoch、leaseUntil、heartbeatAt；认领后的 outcome-affecting 写以 roleRunId+token+epoch+RUNNING 和第 15.19 节 content ledger 共同 fencing，旧租约迟到写零行。
- deadline coordinator 只收口已有用户 batch。`DEADLINE_REACHED(roleContentDeadline)` GuardUse 以 lowerNow 证明到线后投影 TIMED_OUT 并取消 transport；正文只有 `STRICT_BEFORE` GuardUse 的 upperNow 严格早于 deadline 才可持久化。等于/晚于截止的正文不保存，只记有界 discardedLateBytes。
- PostgreSQL raw clock 使用 `clock_timestamp()`；H2 使用 non-deterministic `APP_WALL_CLOCK_NOW()` UTC alias。唯一 `app_time_authority_state` 行保存 authorityEpoch、durableRawHighWater、durableGuardHighWater；raw 只记录真实 DB wall clock 并检测回退，guard 保存保守上界。两者当前 epoch 只增，初始化原子 insert/重复键重读；未来余量绝不写 raw，也不拆多行。
- 启动/恢复读取持久双高水位后，必须取得一条健康候选基线和其后至少间隔100ms的一条有效推进样本；watchdog 至少每250ms使用专用 `TimeAuthorityProbe` 推进。Probe 在调用数据库前记录 requestStartNano，把每次JVM启动唯一的bootId、instanceId、epoch、probeMode=BOOTSTRAP|REBASELINE|ADVANCE、candidateNow或null、expectedPreviousSampleToken或null、requestStartToken、requestStartNano作为参数交给一条autocommit DML：PostgreSQL用`WITH sample AS MATERIALIZED (...) UPDATE ... RETURNING ...`再upsert sample，H2用`MERGE ... USING(single raw alias)`包在`FINAL TABLE`后upsert；raw恰求值一次，authority推进与sample upsert同一语句/事务提交。应用在DML acknowledgement后立即记录responseEndNano，再普通SELECT本实例sample；不存在应用持authority行锁的暂停点。BOOTSTRAP与REBASELINE都固定candidate=null并在SQL内以raw派生effectiveCandidate，只要求expected token/boot/epoch CAS和`oldRaw<=rawNow`，提高双高水位、sample_generation+1并写sample_kind=BASELINE，但永远health=false。BOOTSTRAP只用于当前boot无sample；REBASELINE用于已有sample被RTT>250ms、ack unknown、raw/nano delta超界或并发输家判为不可信锚点时，必须携带最后committed token，双线程只允许一个赢家。只有上一条committed sample是本boot/epoch的BASELINE或VALID_ADVANCE且其ack已被当前monitor明确观察为成功，ADVANCE才固定`candidateNow=ceilMicros(previous.sampledDbNow+(requestStartNano-previous.requestStartNano))`；单语句要求token/generation匹配、raw严格推进、`oldRaw<=rawNow<=candidateNow+250ms`且raw/nano delta偏差<=250ms，成功写sample_kind=VALID_ADVANCE。previous.requestStartNano必须来自同boot最后committed sample，绝不取输家线程内存。语句以`max(oldGuard,rawNow+350ms,effectiveCandidate+350ms)`更新guard，绝不在oldGuard递归加余量。
- Probe 的 sampledDbNow 固定为该instance sample的rawNow并保守锚定requestStartNano，RTT=`responseEndNano-requestStartNano`且必须<=250ms。任何BASELINE本身、raw回退/不推进、delta超界、RTT>250ms、ack unknown或最后有效样本age>750ms均health=false。若BOOTSTRAP/ADVANCE已提交但ack unknown，普通SELECT只可安装其token/generation/raw/requestStartNano为不健康事实，下一次必须先REBASELINE；不能直接以其为ADVANCE前样本或HealthProof。若一次BOOTSTRAP/REBASELINE的ack成功但RTT>250ms，它也是坏锚点，下一次继续REBASELINE，直到一次ack成功且RTT<=250ms的BASELINE，再由其后正常ADVANCE恢复健康；因此requestStart后任意长暂停不会永久污染同boot。health只在一次RTT<=250ms、delta合法的VALID_ADVANCE ack后建立，750ms age从该responseEndNano计算。换bootId忽略旧nano并从BOOTSTRAP重建。恢复必须raw追平旧raw highwater后重新建基线+advance；epoch维护只能提高floor。nano虽为同进程并发/ack恢复持久化在sample行，但只在同boot比较；watchdog不读业务表、不扫描市场或创建业务事实。
- `instanceId`不是任意进程随机字符串，而是发布清单中唯一的ASCII replica slot（1..64字节），H2固定LOCAL，PostgreSQL每个声明副本必须不同。sample行同时保存`boot_lease_until`；每次成功BOOTSTRAP/REBASELINE/ADVANCE都以同一raw alias写`rawNow+2s`。同instanceId不同boot的BOOTSTRAP只有在行缺失，或`rawNow>=旧bootLeaseUntil`且旧token/generation仍匹配时才能CAS接管；租约未到零行并使新实例readiness失败，稳定原因码为`INSTANCE_BOOT_LEASE_HELD`。不在发布清单中的slot固定`INSTANCE_SLOT_NOT_DECLARED`。接管后旧boot的下一次probe因boot/token不匹配必须把本地monitor永久fence为`INSTANCE_BOOT_FENCED`，停止该进程AI readiness且不再尝试BOOTSTRAP/REBASELINE/ADVANCE；只有全新JVM bootId才可在租约到期后参与接管，旧进程不能等待新租约再抢回。同boot的REBASELINE不受该等待影响。发布清单、sample租约与连接预热共同证明实际副本：重复instanceId只能有一个健康boot，额外不同instanceId仍由数据库角色CONNECTION LIMIT挡住。DB冻结/回退不能让租约提前到期，最多保守延迟接管。
- 所有硬门禁都由 `GuardUse` 深模块实现，但 `GuardUse.execute/evaluate` 与其 command/result 类型只在 gate implementation package 可见；业务包跨越的外部 seam 是各领域的单一封闭 interface，例如 `SourceMaterializationModule.resolve(admissionId)`、`ManualCertificationModule.certify(manualResultId)`、`OutcomeFreezeModule.freeze(batchId)`、`ExecutionEligibilityModule.read(manualResultId)` 与 `AiEgressGate.execute(TransportOperationRefV1)`。这些 interface 只收稳定业务键以及确实属于 owner 身份的 opaque ClaimRef，不暴露 predicate、deadline、workloadClass、adapter、lock plan、mutation kind、time proof 或 SQL；删掉 GuardUse 后上述复杂度会重新散落到全部调用点，故该模块 seam 有真实深度。读 interface 只能返回最终只读 DTO（例如 `ExecutionEligibilityV1`），不能返回时间、handle 或可供 mutation/transport 消费的中间授权。
- 包私有 `GuardUseCommandV1` 只能由 operation-specific facade 的注册 policy 构造。`operationKind -> GuardPredicateV1 -> decision branch -> LockPlanV1 -> MutationAdapterV1/ReadProjectionAdapterV1` 是 Stage 0 封闭矩阵；operationKind 由具体 facade 固定，subject/version/status/deadline/runtimeGuardRevision/hash/requestHash 从锁后业务行重建，claim token/epoch 只从 opaque ClaimRef 解封，调用方不能选择或覆盖任何一项。guardUseId 由 GuardUse 生成，HealthProof 由 `TimeAuthorityHealthMonitor` 提供且构造器包私有、绑定当前 instance/bootId/epoch；业务代码不能创建或替换。讨论 batch 的 budgetBucketDate 固定取 source.windowDate。旧 PAID_HTTP PREPARE 没有 source：它必须在 authority DML 前预建并按唯一 scope-first 比较器锁住 `GLOBAL(D),GLOBAL(D+1),ACTOR(D),ACTOR(D+1)`，其中 D 是HealthProof.sampledDbNow的Asia/Shanghai日，同scope内日期再升序；因proof age<=750ms且statement限制raw<=candidate+250ms，合法lowerNow不可能落在其他日期。DML后只能选择lowerNow对应的已锁桶并冻结budgetBucketDate，若不在该两日则SUBJECT_CHANGED/零mutation，绝不得授时后新建/锁第三个桶。未选桶保持零计数，只是幂等guard。后续operation只核验已冻结值。业务锁后快照与authority branch输入注册adapter，唯一派生resultMutationKind与mutationId；mutationId是`operationKind+businessKey+resultMutationKind+expectedVersion/hash`的canonical hash，同一状态迁移重试相同，不同DEFER/MATERIALIZE/FAIL/SEAL转换必然不同。ticket在authority autocommit statement中已保存唯一结果，业务阶段不得改选。包私有sealed `GuardUseResultV1`只允许COMMITTED、REJECTED_WINDOW、ALREADY_DECIDED、SUBJECT_CHANGED、BUSY_RETRYABLE、TIME_AUTHORITY_UNAVAILABLE、COMMIT_UNKNOWN，并只携带稳定identity/mutationId与commitObservation，不暴露raw/lower/upper/businessNow/branch/ticket/hash；adapter需要写入的businessNow、guardUseId和authority identity由模块直接绑定SQL参数。业务包只得到operation-specific最终domain DTO，任何DTO都不能被另一个mutation或transport interface当作许可。COMMIT_UNKNOWN只能先按业务唯一键恢复，绝不重放本次mutation或外发。predicate逐字固定：`STRICT_BEFORE(t): upperNow<t`；`ON_OR_BEFORE(t): upperNow<=t`；`ON_OR_AFTER(t)`/`DEADLINE_REACHED(t): lowerNow>=t`。周期黑窗只能使用`NOT_IN_LOCAL_WINDOW(windowDate,Asia/Shanghai,start,end)`，先构造`startAt=windowDate+start,endAt=windowDate+end`完整instant，再按互斥顺序判断：`lowerNow>=endAt`唯一为AFTER_WINDOW；否则`upperNow<startAt`唯一为BEFORE_WINDOW；否则CLOSED_WINDOW。尚未创建CONTINUATION固定windowDate=lowerNow本地日，已有source永远使用source.windowDate；禁止裸LocalTime比较或双命中。
- 每次执行先同时取得业务连接和专用 authority 连接；未取得齐全前不得持锁。业务连接按 operationKind 的固定隔离级别/锁序开启事务，先锁定所有可能阻塞的业务行、预算行、唯一键 guard 行，重建 subject 并校验 version/claim/hash；已有唯一赢家直接 ALREADY_DECIDED，subject 改变直接回滚。真正时间尚未采样，所以业务锁等待跨过截止不会沿用旧授权。没有天然行的未来唯一事实必须在实体创建时预建 guard 行：batch 的 outcome guard、round 的 parent-marker guard；授时后禁止再取得任何未预锁业务行、预算行或唯一键。
- GuardUse 的连接租约顺序固定为 business pool 后 authority pool；取得 business connection 后若 authority connection 不能在本次连接获取预算内取得，必须释放前者且零事务/锁，禁止反向租约。所有 UUID、canonical hash、序列化 payload、候选纯函数排序和固定 SQL 参数，必须在零锁阶段完成，或在业务锁齐全后、guardUseStartNano 之前基于锁后 snapshot 有界重算；ticket commit 后只允许 policy 预注册的有界 SQL，不得解析模型正文、规范化无界内容、申请连接、生成新业务身份或执行网络调用。MATERIALIZE 的最多40只候选/4MiB canonical artifact 必须在guardUseStartNano前写成第15.3.1节不可变SEALED stage；stage事务不持 AI_RUNTIME_GUARD、确定性manual表行锁、budget/slot/authority锁，但必须按第15.3.1节锁住 AI 侧 MATERIALIZATION_STAGE_GUARD、存储账本、MANUAL_ATTESTATION_GUARD/admission/linkage 并重验唯一身份，不得用无锁旧快照落stage。bind事务锁后只比较stage/source/attestation/linkage version/hash/固定引用，ticket后仅从businessNow和最多8条小manifest计算deadline/roundCreatedAt/roundIdentityHash并绑定stageRef；禁止复制、重编码、遍历或`INSERT ... SELECT`搬运长payload。严格100ms只覆盖authority DML、小引用/manifest insert/CAS与commit acknowledgement，超时统一COMMIT_UNKNOWN并按唯一batch/source/stage事实恢复。
- 业务锁齐全后重新校验 proof age，记录 `guardUseStartNano` 并推导 `candidateNow=ceilMicros(proof.sampledDbNow+(guardUseStartNano-proof.requestStartNano))`。`execute` 的 authority adapter 只能调用一条 autocommit DML：PostgreSQL 用 materialized raw CTE + `UPDATE ... RETURNING` + `INSERT guard_use_ticket`，H2 用 single-row raw alias + `FINAL TABLE(MERGE ...)` + outer `INSERT guard_use_ticket`。两套模板都必须在 SQL 内恰求值一次 raw、核验 `oldRaw<=rawNow<=candidateNow+250ms`、推进 raw/guard/version、计算 lower/upper/互斥 branch/businessNow/resultMutation/ticketHash，并插入恰一条 immutable ticket；语句 update count 必须为 1。应用在 statement acknowledgement 前拿不到 decision，ack 后 authority 行锁已经由 autocommit 释放，再以无锁 SELECT 读取并核验 ticket；不存在跨两条 JDBC 指令持有 authority 行锁的路径。`evaluate` 用对应的单条 autocommit UPDATE/MERGE 推进高水位但不插 ticket，ack 后普通 SELECT 当前双高水位并只允许得到相同或更晚的保守 read projection；并发推进只能使读更保守，不能授予更早权限。常量固定 `CLOCK_FORWARD_UNCERTAINTY=250ms`、`GUARD_USE_COMMIT_BUDGET=100ms`、`GUARD_MARGIN=350ms`；`lowerNow=rawNow`、`upperNow=max(oldGuard,rawNow+350ms,candidateNow+350ms)`。旧 guard 不递归加余量；raw 只写通过健康校验的真实时间。predicate true/false 都推进时间并由 execute 保存 ticket。epoch/raw 回退、相对 candidate 前跳>250ms、零/多行、statement timeout、proof 过期、authority statement acknowledgement unknown 或 ticket 核验失败均回滚业务事务、零业务 mutation/读结论；不能等下一次 watchdog 才发现本次异常前跳。
- H2 2.2.224 的 Stage 0 SQL 不能留给实现者猜测 `MERGE ... RETURNING`。三个模板的最外层语法形状逐字冻结为：probe=`MERGE INTO app_time_authority_probe_sample dst USING (SELECT ... FROM FINAL TABLE (MERGE INTO app_time_authority_state s USING (SELECT raw_now FROM (VALUES(APP_WALL_CLOCK_NOW())) raw(raw_now)) ...)) src ...`；execute=`INSERT INTO guard_use_ticket(...) SELECT ... FROM FINAL TABLE (MERGE INTO app_time_authority_state s USING (SELECT raw_now FROM (VALUES(APP_WALL_CLOCK_NOW())) raw(raw_now)) ... )`；evaluate=`SELECT ... FROM FINAL TABLE (MERGE INTO app_time_authority_state s USING (SELECT raw_now FROM (VALUES(APP_WALL_CLOCK_NOW())) raw(raw_now)) ... )`。省略号只能由Stage 0 canonical SQL resource中的固定列/谓词展开，业务代码不能拼接。epoch/boot/token/raw健康谓词属于MERGE MATCH条件，失败返回零行；业务时间predicate不属于MATCH条件，true/false都必须更新双高水位并由outer SELECT形成PERMITTED/REJECTED结果。probe/execute最外层update count必须恰为1，evaluate结果行恰为1；否则按上一条失败。PostgreSQL同样冻结完整resource而非运行时拼接。Stage 0 必须从 canonical DDL 创建的空 H2 2.2.224 数据库原样执行这些resource，不得创建测试替身表，并用有计数的APP_WALL_CLOCK_NOW测试alias恰调用一次；之后G/M/B/C/D/E只能调用生成的adapter，不能各自改写SQL。
- `guard_use_ticket` 只记录 `execute` 的只增 mutation 决定，含 canonical ticket_hash 且不向业务表建 FK；`app_guard_use` 是业务事务中的只增消费事实，复制 ticket_hash，同样无 FK，guardUseId PRIMARY KEY 且 UNIQUE(operationKind,mutationId)。业务 REPEATABLE_READ 快照即使建立在 ticket 提交前，也可用模块内部的不可复制决定插入 app_guard_use；二者事后按 guardUseId/ticketHash 审计。orphan permitted ticket 永不由恢复器消费，ABANDONED 只是“ticketResult=PERMITTED、无匹配 app_guard_use、且该 operationKind 的 guard-use TTL 已过”的派生审计投影；REJECTED ticket 和仍可能提交/commit-unknown 的新 ticket不标 ABANDONED，表本身禁止更新。重试必须新 guardUseId/proof/candidate/两连接。这样 authority 高水位在业务回滚时仍不会降低，而 app_guard_use 与业务 marker/CAS 全有或全无。
- authority commit 后，GuardUse 只能在已经持锁的业务事务中插 `app_guard_use` 并执行 policy 注册 mutation；不得嵌套 GuardUse。PERMITTED ticket 只能被对应正向 mutation 消费；REJECTED/CLOSED ticket 只有矩阵明确登记的 DEFER/FAIL/拒绝审计 mutation 才可消费，绝不能授权正文、materialization 或 transport。`guardUseStartNano` 到业务 commit acknowledgement 的总预算严格 `<100ms`，等于 100ms 拒绝；lock/statement/transaction timeout 均使用剩余预算。commit 调用前预算耗尽必须回滚；commit acknowledgement 到达时若 monotonic duration 已>=100ms，无论数据库实际提交/回滚都统一返回 COMMIT_UNKNOWN，对外部 transport 零授权；即使数据库稍后出现 marker，也只由恢复器按唯一事实保守收口。非外发 COMMIT_UNKNOWN 只读唯一业务事实，不复用 ticket。业务准入的线性化点是“业务锁后单条 authority DML 已提交的 raw/ticket 与随后同一业务事务消费事实”，不承诺任意 JVM/OS/DB 暂停下事务在某毫秒前对其他连接可见。`execute/evaluate` 的公开 facade、日志、异常和 metric label 都不得泄露可重放的决定对象、内部 COMMITTED 状态或精确授时值。
- `GuardUse.evaluate` 是严格无业务锁的读投影算法，不能复用 execute 的 LockPlan：facade 先 freeze/final-fact-first 普通 SELECT；缺失时以 READ_MODEL 普通 SELECT 读取 `ReadSubjectSnapshotV1`（identity/version/status/deadline/runtimeGuardRevision/hash），执行上述单语句 authority 推进但不建 ticket/app_guard_use，再在新的 READ_COMMITTED statement 中先重读最终事实、后重读同一 subject。若此时出现已校验 freeze/不可逆最终事实，立即优先返回；否则只有第二份 snapshot 与第一份逐字段完全相同，才允许包私有 `ReadProjectionAdapterV1` 使用本次更保守 authority projection 生成最终 DTO。任一字段变化都丢弃本次 projection，并从 final-fact-first 最多重试一次；仍变化或总 monotonic 时长达到100ms固定503 `READ_PROJECTION_CHANGED`，不得返回旧 PENDING/eligibility。返回前若总时长达到100ms同样丢弃。整个路径只允许普通 SELECT 与 authority autocommit evaluate，禁止 `SELECT ... FOR UPDATE`、业务行锁、ticket/app_guard_use 或任何 AI/确定性业务 mutation。最终 DTO 可展示但不得被任何 mutation/transport adapter 接受，也不得缓存为未来 eligibility；因此高频 GET 既不会制造无上限 ticket 行，也不会与 worker 的 source/admission 锁互相阻塞。
- 截止型 businessNow=upperNow；开窗/截止投影型=lowerNow；带 windowDate 的 NOT_IN 命中 BEFORE/AFTER 时分别取 upper/lower，CLOSED rejection 为 null。`upperNow` 的 350ms 包含 250ms forward uncertainty 与 100ms GuardUse 完成余量；调用方不能选择时间方向。若 raw 在业务锁等待期间前跳，之后发出的 statement 必须看到；若 raw alias 在 authority statement 内部行等待前已求值，等待期间其他语句推进 oldRaw 必须使本语句零行，而未推进时也受 candidate+350ms 与总预算保守约束，绝不能消费一个过时 raw 的正向决定。若系统在 sample 后发生超出健康模型的异常时钟阶跃，后续 probe 会 health=false，所有新 GuardUse fail closed，不能伪称对任意外部时钟破坏具有硬实时保证。
- 本文其余“post-lock time/raw/businessNow”仅是“业务行已按序锁齐后，由随后单条 autocommit authority DML 产生的值”的缩写，不表示应用在两条 JDBC 指令之间持有 authority 行锁。
- 全局锁序固定为 `AI_RUNTIME_GUARD -> MATERIALIZATION_STAGE_GUARD -> PAID_ADMISSION_GUARD -> PAID_ADMISSION_ATTEMPT -> AI_STORAGE_QUOTA_GUARD -> STORAGE_RESERVATION -> CONTINUATION_ADMISSION_GUARD -> ACTOR_WORKLOAD_GUARD -> ACTIVE_BATCH_SLOT -> PAID_WORKLOAD_GUARD -> PAID_OPERATION_SLOT -> BUDGET_GLOBAL -> BUDGET_ACTOR -> BUDGET_BATCH -> USAGE_RESERVATION -> BATCH_CONTENT_QUOTA -> OPINION_CONTENT_QUOTA -> MANUAL_ATTESTATION_GUARD -> ADMISSION -> LINKAGE -> THREAD_GUARD/OUTCOME_GUARD -> ROLE_RUN -> INVOCATION/TOOL_EXECUTION/PAID_OPERATION -> TOOL_CALL -> CONTENT_LEDGER -> CONTENT_MANIFEST_GUARD -> PARENT_MARKER_SEQUENCE_GUARD -> PARENT_MARKER_GUARD -> EVENT_TIMELINE_GUARD -> TIME_AUTHORITY_STATEMENT`，同层按稳定业务键升序；预算同层比较器固定`scopeOrdinal,budgetDate,scopeKey`。MATERIALIZATION_STAGE_GUARD由source创建事务与source全有或全无地预建；此后只由stage创建、bind、source终态和cleanup四类路径锁定使用，按admissionId升序，同时保护未来stage/storage owner唯一身份并构成四类路径的唯一线性化点。PAID_ADMISSION两层只用于尚无durable operation的旧付费POST，已有operation的egress/response/terminal路径直接省略；其余操作也省略。锁住的PAID_ADMISSION_ATTEMPT是其确定性operationId、storage reservation、paid slot和usage reservation全部未来唯一事实的唯一键guard，只有PaidAiGuard可写这些派生ID；新operation分支必须在authority前按全局序把这些固定准备行全部插入/锁住，授时后只更新或prepare-cancel已持有行，绝不插入新身份。OPINION_CONTENT_QUOTA 是materializer按确定性opinionId为每个role+round预建的小行，不是内容写入时后取的新锁。MANUAL_ATTESTATION_GUARD 是 AI schema 中按 manualResultId 预建的唯一键guard，AI只锁它并以非锁定READ_MODEL查询第15.2.1节不可变attestation；AI角色对manual/deterministic表无UPDATE/DELETE/行锁权限。仅使用所需子序列，局部章节不得反序。runtime config PUT与materializer/AiEgressGate锁同一AI_RUNTIME_GUARD；存储监控器按runtime->storage guard更新，内容写只用storage起始子序列；所有guard持到各自marker/chunk提交。materializer在authority前以确定性ID幂等upsert并锁`short_term_ai_discussion_thread`本行（它就是THREAD_GUARD，不另建表）、outcome/parent guard；DEFER/BUSY提交时storage/active准备行必须prepare-cancel，固定尺寸且无history语义的thread/outcome/parent唯一键guard可保留并在重试逐字段重用，API/父链/预算均不得把只有guard的ID视为batch/round。authority autocommit statement永远最后且ack后无内部行锁；所有连接在第一把业务锁前取得。BudgetSettlement仍用GLOBAL->ACTOR->BATCH->reservation，UNKNOWN finalization不反向锁预算。
- 发布资源合同固定为独立Hikari pools且全部`minimumIdle=maximumPoolSize`并在readiness前真实借出/归还逐条预热：AI_BUSINESS=4/acquire<=20ms，AI_AUTHORITY=2/acquire<=5ms/statement<=5ms，WATCHDOG_AUTHORITY=1/acquire<=50ms/statement<=5ms，DETERMINISTIC_BUSINESS=4/acquire<=50ms，DETERMINISTIC_AUTHORITY=1/acquire<=50ms/statement<=20ms，READ_MODEL=4/acquire<=100ms。WATCHDOG_AUTHORITY使用DETERMINISTIC数据库角色而不是AI角色，使TimeAuthority健康样本在AI capacity关闭时仍服务manual stage/certify；它仍是独立pool，不能借用DETERMINISTIC_AUTHORITY。authority connection强制autocommit=true，adapter禁止显式begin/commit/`SELECT ... FOR UPDATE`，架构测试只允许Stage 0单语句模板。讨论网络执行器core=max=16/queue=64，旧付费另用core=max=4/queue=20且还受数据库持久并发槽；网络期间不持数据库连接。OUTCOME_FINALIZER用独立优先executor core=max=4/queue=20，拒绝时调用线程立即执行capability closer；materialization 注册的 monotonic wake 仅是低延迟优化，不是持久恢复事实。发布支持负载固定同一operator最多4个活跃batch。

  PostgreSQL profile冻结为`server_version_num>=160000`，并使用互不继承的AI、DETERMINISTIC、READ、LEGACY普通角色；四者均必须NOSUPERUSER、NOBYPASSRLS且对`pg_use_reserved_connections`的`pg_has_role(...,'MEMBER')=false`。读取`max_connections`、`superuser_reserved_connections`与PG16+的`reserved_connections`，定义`ordinaryUsable=max-superuser_reserved-reserved`。AI/DETERMINISTIC/READ角色CONNECTION LIMIT必须分别精确等于`6*replicaCount`、`6*replicaCount`、`4*replicaCount`，其中DETERMINISTIC的6条明确包含4条business、1条manual authority与1条watchdog；LEGACY精确等于声明总量。四应用角色无限(-1)、高于声明值或具特权都拒绝。发布清单必须枚举整个PostgreSQL cluster全部`rolcanlogin=true`角色，而非只看当前database授权，并互斥分为四应用角色、OTHER普通角色和OPERATOR特权角色；NOLOGIN系统角色不计。OTHER每项必须有限connectionLimit、NOSUPERUSER/NOBYPASSRLS/无reserved权限，其保守和为`declaredOtherOrdinaryRoleCapTotal`。OPERATOR允许superuser或reserved权限但同样必须有限connectionLimit，全部OPERATOR limit之和必须`<=5`；由于特权连接可能先占普通槽，required中的5条operator reserve按其完整上限保守扣除，不能只依赖superuser_reserved_connections。实际LOGIN集合、类别、继承/成员关系和limit必须与清单完全相等，未知/无限/提权/改大角色立即使AI readiness关闭。为避免重复计数，LEGACY只包含应用声明的非AI旧池，OTHER/OPERATOR均排除上述四应用角色；`required=replicaCount*(6 AI+6 deterministic+4 read)+declaredLegacyNonAiPoolMaxTotal+declaredOtherOrdinaryRoleCapTotal+5 operator reserve`。启动严格先预热每实例6条deterministic（含watchdog）、4条read和该实例声明的legacy连接，确认TimeAuthority可为manual提供健康proof，再预热全部6条AI连接；所有已启用pool在ready期间维持min=max。这样错误启动第`replicaCount+1`个实例时至少一个全局应用角色上限使它无法完成完整readiness，已ready实例的预留连接已物理占住；不得只信声明的replicaCount。只有`ordinaryUsable>=required`、LOGIN角色集合/类别/限额仍匹配发布清单、全部应用pool实际预热证明成立才启用AI；5条operator reserve只是required中的未分配容量，普通或特权LOGIN清单的上限不能越过它。AI pool预热/容量证明失败时关闭并清空AI_BUSINESS/AI_AUTHORITY，但不得关闭已健康的WATCHDOG/DETERMINISTIC/READ，manual仍可按自身TimeAuthority合同运行。任一参数不可读、PG版本过低、角色清单漂移/有特权、实际副本超额或预热不足均使AI fail closed。GuardUse总预算仍优先；WATCHDOG只用自己的pool，READ_MODEL不mutation。H2单实例使用同语句模板但不伪造服务端角色隔离；任一profile缺AI独立pool/容量证明都强制AI feature=false，而确定性pool健康状态单独报告。

  发布清单同时固定恰好`replicaCount`个不同instanceId slot；每个进程只可声明其中一个，且必须先取得第8.3/15.1节数据库boot lease、完成该slot的baseline+advance，再计入ready。连接容量证明和slot租约缺一不可：相同slot的第二boot即使还能借到连接也不ready，不同的额外slot即使伪造名称也因不在清单和角色连接上限双重拒绝。
- materialization、INITIAL/continuation、logical tool、旧付费、AiEgressGate、content、seal、正常 freeze/fallback、manual 认证和 eligibility 都通过方向固定的 GuardUse。health=false 只有两个不能授权任何执行/外发的窄化能力失败 adapter，均不建立 ticket/app_guard_use，也不能被其他 operation 复用。其一是无内容 TIME_AUTHORITY_UNAVAILABLE batch fallback：先取得全部连接，按全局子序列锁齐storage quota/reservation、actor workload/active slot、outcome、parent-marker-sequence/parent guards，最后只读 commit-visible authority 高水位；`trustedFallbackAt=max(commit-visible durableGuardHighWater,batch.materializedAt,manifest 中全部 roundCreatedAt)`，坏 raw/CURRENT_TIMESTAMP 只可写 basis 外 auditObservedAt/createdAt。authority/高水位/manifest 读不到、锁超时或 identity 不符就回滚并返回 503，零storage结算/slot释放/freeze/marker。其二是 `MANUAL_TIME_AUTHORITY_FAILURE`：只用确定性business/authority连接，先锁唯一manual result，再只读commit-visible durableGuardHighWater；若可读，以`trustedFailureAt=max(durableGuardHighWater,payloadCommittedByAt若有)`把PENDING/RUNNING/FINAL_PENDING不可逆收口为SUCCEEDED+DATA_BLOCKED、certificationErrorCode=TIME_AUTHORITY_UNAVAILABLE、aiAdmissionReason=NOT_APPLICABLE、certifiedAt=null，deterministicFinalizedAt/completedAt=trustedFailureAt；若authority state缺失/不可读但manual业务事务仍可提交，则只可收口FAILED+NOT_EVALUATED、certificationErrorCode=TIME_AUTHORITY_STATE_UNAVAILABLE、aiAdmissionReason=NOT_APPLICABLE，deterministicFinalizedAt/certifiedAt=null，普通completedAt只作非业务审计。两支都保留已有report/hash供排障但永不创建source/linkage/执行资格；AI/runtime/linkage表不可读也不影响该收口。
  `MANUAL_TIME_AUTHORITY_FAILURE` 在进入 DATA_BLOCKED 时若候选事实尚未形成，必须同事务冻结 `eligibleCandidateCount=0,candidateSetHash=null`；若已有候选/report则只保留并核验其原值，不能借失败路径改写内容。这样所有 DATA_BLOCKED 行满足第15.2节确定性终态CHECK。
- capability closer 要求 immutable manifest 的 N>=1（N=0 是损坏，503 且零写），在一个短事务写无内容 SYSTEM_FALLBACK/TIME_AUTHORITY_UNAVAILABLE、canonical basis、effective fallback，并为恰好 N 个 round ensure parent marker；缺行插 OUTCOME，已有 RAW/OUTCOME 按第 15.7.1 节分支核验且首个 marker 保持不变。任一 identity/highwater/commit 问题全回滚；不得出现 final 无 marker或部分 marker。health 恢复不复活原 source/batch。
- H2 单实例、PostgreSQL 多实例。AI 使用上述独立池和有界 executor，对 deterministic/scheduled 表只允许 READ_MODEL 非锁定 SELECT，禁止 `FOR UPDATE/SHARE`、UPDATE、DELETE；AI主事务只锁AI侧MANUAL_ATTESTATION_GUARD，AI schema 不向 deterministic 表建 FK。manual终态与attestation的原子写由确定性事务负责，无法证明隔离则 AI capability fail closed。
- SAME_DAY 常量固定为 14:47/14:47:20/14:47:55/14:48:30/14:48:44/14:48:45。全天 materialization 使用 `NOT_IN_LOCAL_WINDOW(source.windowDate,Asia/Shanghai,14:47,14:50)` 的完整 instant；尚未创建的 CONTINUATION 用 lowerNow 日期作为 windowDate。SAME_DAY 还要求 BEFORE_WINDOW、source.windowDate=manual.tradeDate/交易日、certifiedAt<该日14:47。raw=该日14:49:59.999 即使 upper 越过14:50也不开放，lower 达到该日14:50才允许 AFTER_WINDOW/RESEARCH_ONLY。23:59:59.900 到次日00:00:00.250 的不确定带对原 windowDate 只可能是 AFTER，不会同时命中 BEFORE。
- materializedAt=`m` 由 MATERIALIZE adapter 在模块内部写 GuardUse.businessNow；令 D 为 m 的 Asia/Shanghai 日期。所有 m<D14:47 的 SAME_DAY_RESEARCH/RESEARCH_ONLY 都使用同一完整公式：`initialDispatchDeadline=min(m+15s,D14:47:20)`、`latestEgressPermitAt=min(m+75s,D14:47:55)`、`roleContentDeadline=min(m+105s,D14:48:30)`、`freezePublishAdmissionDeadline=min(m+119s,D14:48:44)`、`effectiveOutcomeAt=min(m+120s,D14:48:45)`。所有 m>=D14:50 的 RESEARCH_ONLY 使用无尾盘封顶的 `m+15s/+75s/+105s/+119s/+120s`；[14:47,14:50) 不存在合法 m。INITIAL 只有 upperNow 严格早于 initialDispatchDeadline 才可原子 DISPATCH；该事务固定 `sessionDeadline=min(initialDispatchedAt+90s,roleContentDeadline)`，等于任一 dispatch/session/egress/content deadline 都拒绝新相应动作。所有后续发送/content adapter 使用 STRICT_BEFORE upperNow，收口/effectiveOutcome adapter 使用 DEADLINE_REACHED lowerNow；这些内部时值不返回业务调用方。
- `AiEgressGate.execute(TransportOperationRefV1)` 是 MODEL/TAVILY/DIRECT/PAID_HTTP 唯一 transport interface；业务包不能直接依赖 Provider/Tavily/direct/paid HttpClient。TransportOperationRef 只含封闭 operationType、稳定 operationId 和需要时的 opaque ClaimRef；requestHash、adapterCode、response sink code、配置 revision 和完整 request snapshot 均从锁后 operation 行唯一取得，调用方不能传或选择。Gate 按注册policy先锁AI_RUNTIME_GUARD，再按全局子序列锁reservation；MODEL/TAVILY/DIRECT还必须在ROLE_RUN之前锁该batch预建的OUTCOME_GUARD并确认不存在committed freeze，PAID_HTTP则锁paid workload/slot，随后才锁operation/role和marker guard。在同一锁内验证enabled/revision/operationType/row kind/provider/config/sink唯一组合并解析已批准endpoint、密钥引用、冻结请求快照和内部adapter，持runtime guard到handoff业务commit。已存在freeze、任何组合缺失或漂移都零handoff、零transport。内部TransportCommand不接收任意URL、header、request body、lambda、closure或可执行对象。唯一业务事务原子写app_guard_use、marker-null->transportHandoffAt=businessNow与handoffGuardUseId。只有commit acknowledged、总预算内出现包私有COMMITTED时，Gate同一个不可恢复的方法调用才在释放全部数据库锁/连接后直接调用注册transport adapter一次；COMMITTED不返回业务包，业务调用方也没有“稍后发送”入口。durable handoff commit是不可撤销的at-most-once授权点：marker提交后Gate内线程暂停、feature关闭、config revision变化、claim迁移或recovery已保守写OUTCOME_UNKNOWN，都不能让另一owner发送，也不能撤销原调用帧最多一次的发送；该帧恢复后记录TRANSPORT_HANDOFF_DELAYED/CONFIG_CHANGED_AFTER_HANDOFF，返回正文仍过content gate。需要阻止这类已handoff尚未写socket的极端暂停请求时，运维必须终止原进程或切断出口网络，不能只切feature flag。gate最终只返回不可作为新请求凭据的TransportAttemptOutcomeV1；commit unknown、回滚或marker输家均零transport。系统承诺锁后授权、提交前/提交不明零外发、marker单赢家和恢复零重发；不承诺任意JVM/OS/网络暂停下物理数据包必在同一毫秒前发出。截止拒绝由新的DEADLINE_REACHED GuardUse把operation=FAILED_BEFORE_SEND、reservation=SETTLEMENT_PENDING原子提交。旧付费入口的[14:47:55,14:50)也用带windowDate的方向窗口；14:50只有lowerNow到线才可建新operation。
- `BatchOutcomeDueCoordinator` 是唯一持久 outcome 到点唤醒者，只处理已存在、source=MATERIALIZED 且 committed freeze 缺失的 batch，绝不创建source/batch/invocation/tool/transport。它启动即恢复，最大空闲扫描间隔250ms，分别按`roleContentDeadline,batchId`与`effectiveOutcomeAt,batchId`有界读取前64条；候选查询只是唤醒提示。到content deadline幂等封未封ledger，四条已封且仍严格早于freeze admission线时尝试CONTENT_SNAPSHOT，到effectiveOutcomeAt仍无freeze时尝试SYSTEM_FALLBACK；每个mutation都重新经过GuardUse、ledger、immutable content manifest、outcome/marker锁序，health=false走TIME_AUTHORITY_UNAVAILABLE closer。已提交freeze只读，identity损坏或唯一赢家未决503。effectiveOutcomeAt 后普通 GET 不创建 freeze，只由该 coordinator/OUTCOME_FINALIZER 以 DEADLINE_REACHED 的 lowerNow 竞争唯一 freeze；CONTENT_SNAPSHOT/正常 fallback 与每个 round 的已存在或新插 parent marker 在同一事务核验/提交。basis 只承认封存 terminal fact；live raw status 仅作 basis 外审计。

### 8.4 付费调用 at-most-once

每次物理模型请求在发送前创建不可变 invocation：

1. 事务按AI_STORAGE_QUOTA_GUARD->batch storage reservation/REQUEST_SNAPSHOT bucket->ROLE_RUN锁序，重验claim token/epoch/RUNNING与workflow cursor后，以稳定invocationId/业务唯一键直接`INSERT ... ON CONFLICT DO NOTHING` PREPARED：插入1行才在已持有的storage账本中原子消费完整snapshot字节，任何额度失败使插入一并回滚；影响0行则重读并只接受逐字段相同的既有赢家，零再扣storage，禁止exists-then-insert。PREPARED包含roleRunId、invocationNo、callKind、无密钥 `transportRequestSnapshotJson`、requestHash 和 provider 设置快照；requestHash 必须对该 snapshot 的 canonical UTF-8 字节计算，二者在 PREPARED 后均不可更新。单invocation snapshot canonical UTF-8硬上限2MiB；超限不substring或生成不完整JSON，而是把固定有界ContextManifest引用/排除原因写入EVIDENCE_EVENT（额度已满时只写免计errorCode/ref/hash）并以INPUT_CONTEXT_RESOURCE_LIMIT收口角色，永不借FINALIZATION_BASIS。快照保存实际将发送的 system/user/tool messages、工具 schema 与采样参数，但不含 endpoint、Authorization、API key；Gate 只可从该持久事实装配注册 adapter 请求，不能再次运行提示拼装逻辑。该输入边界不限制任何已接收模型输出的完整落库/分页读取。
2. 一个短事务按 GLOBAL -> ACTOR -> BATCH -> USAGE_RESERVATION -> ROLE_RUN -> INVOCATION 固定顺序重验同一claim/cursor、预占预算、插入以 MODEL_INVOCATION + invocationId 唯一的 usage reservation，并以 CAS 把 PREPARED 改为 DISPATCHED；三者必须同事务提交，任何一步失败全部回滚。因PREPARED创建事务本身不建usage reservation，唯一合法的committed PREPARED必定没有该reservation；恢复从本步骤重新竞争一次原子reservation+DISPATCHED事务。若读到PREPARED却已有MODEL_INVOCATION reservation，说明半套状态只能来自损坏/非法人工数据，固定503/CORRUPTED_MODEL_PREPARE并零外发，不能把不可能状态解释成“可恢复且不二次预占”。DISPATCHED 表示“已保守计费并获准尝试”，不等于物理请求已经发送。
3. 业务执行器只能调用第 8.3 节 `AiEgressGate.execute`，不能调用 HttpClient。Gate 在 marker-null 单赢家收到 commit acknowledgement 且 GuardUse 总预算内出现内部 COMMITTED 后，立即由注册 adapter 进行唯一一次物理请求；公开结果不包含 COMMITTED/permit。COMMIT_UNKNOWN 当前调用零外发，由恢复读取 marker 保守收口。因截止/取消且 marker 仍空的唯一拒绝者才在新的 DEADLINE_REACHED GuardUse 中把 DISPATCHED -> FAILED_BEFORE_SEND、reservation RESERVED -> SETTLEMENT_PENDING；BudgetSettlementReconciler 再以固定预算锁序结算为零。ALREADY_HANDED_OFF 输家既不发送也不释放。Gate 的注册 response sink 收到响应后写 RESPONSE_RECEIVED，并流式/分块保存内容；完整且校验成功才转 PERSISTED。模型或工具在 handoff 与 response/result received 均已提交后，若因32MiB/行数上限、确定性校验或明确的本地持久化/解析失败无法形成合法结果，必须转各自 `FAILED_AFTER_SEND`；只有外部结果或本地提交结果确实无法判断时才是 OUTCOME_UNKNOWN/TOOL_OUTCOME_UNKNOWN。
4. 本版本不实现自动 provider-status 查询，也不以相同幂等键重新 POST/生成。transportHandoffAt 已存在而本地结果无法确认时，无论供应商是否宣称接受幂等键，都由 UNKNOWN_FINALIZATION_ONLY 收口；未来若加入只读状态查询，必须另建无生成副作用的 operation/gate 合同。
5. 供应商不支持幂等键时，PREPARED 或 DISPATCHED 但 transportHandoffAt 为空且 egress gate 未放行的操作可安全收口为 FAILED_BEFORE_SEND；transportHandoffAt 已存在而结果无法确认才标 OUTCOME_UNKNOWN，绝不自动重发。
6. 本版不在原 role session 上提供重试。用户只能通过“继续讨论”创建新的 batch 和四个新 role session，原 OUTCOME_UNKNOWN 记录保持不可变并明确提示会产生新费用。

callKind 为 INITIAL、TOOL_CONTINUATION 或 OUTPUT_CONTINUATION。invocationNo、toolRoundNo 与 outputSegmentNo 分开存储，不能复用 attempt_no 表示多个概念。现有 LlmChatClient 的 IOException 自动重试逻辑不得直接复用于本功能。

模型工具调用也采用同一 at-most-once 原则。每个服务器接受的原生 tool_call 先生成稳定 toolCallId；每次物理 Tavily/direct adapter 请求创建 short_term_ai_tool_execution，状态为 PREPARED、DISPATCHED、RESULT_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND 或 TOOL_OUTCOME_UNKNOWN。预算 reservation 与 PREPARED -> DISPATCHED 必须在同一事务。恢复使用同一三分法：合法PREPARED必须无对应reservation并可从原executionId重新竞争原子预占+DISPATCHED；PREPARED已有reservation固定503/CORRUPTED_TOOL_PREPARE；DISPATCHED 但 transportHandoffAt IS NULL 时只读原 operation/reservation 并竞争 AiEgressGate 单赢家；transportHandoffAt IS NOT NULL 且结果未知时 TOOL_OUTCOME_UNKNOWN，绝不再发生成/搜索请求；已知本地失败从RESULT_RECEIVED收口FAILED_AFTER_SEND。direct fetch 虽不按次收费也使用该账本，避免恢复时重复外部副作用。每个 toolExecutionId、requestHash 和 reservation 永久可追溯。

Discussion 的 FAILED_AFTER_SEND 结算合同唯一固定：FAILED_BEFORE_SEND 才按零实际消费结算；FAILED_AFTER_SEND 已发生外部调用，provider usage 可证明时与 usage reservation=SETTLED 同事务提交，不可证明时同事务写 UNKNOWN_CONSUMED 并保守保留全部预算。同一 operation 的终态/结算只允许一个 version CAS 赢家。它不释放 batch storage reservation 或 ACTIVE batch slot，已接收 chunk 永久计入 used；只有唯一 outcome freeze 赢家结算该 batch 未用 storage 并释放 slot。模型 FAILED_AFTER_SEND 使 role 收口 INCOMPLETE 且不重发；logical tool 把 FAILED_BEFORE_SEND 与 FAILED_AFTER_SEND 都视为已知失败，有 PERSISTED 混合为 PARTIAL、全部已知失败为 FAILED，任一 UNKNOWN 仍优先 OUTCOME_UNKNOWN。

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

`complete`、`validated` 与 `qualifiedAdvisoryUse` 都是服务器派生字段，不由模型自报。`complete=true` 只表示该 symbol 的 Schema 必填字段完整且内容资源未超限；`validated=true` 还要求 symbol/evidence/策略校验全部通过。`stance=INSUFFICIENT` 可以是结构完整且通过校验的诚实回答，但固定 `qualifiedAdvisoryUse=null,qualificationReason=INSUFFICIENT`，永远不算一张聚合有效票。其他意见只有 `complete && validated && stance!=INSUFFICIENT` 才可能合格：RESEARCH_ONLY batch 派生 `qualifiedAdvisoryUse=RESEARCH_ONLY`；SAME_DAY batch 的事实证据通道只允许确定性点时快照和 `pointInTimeEligible=true/advisoryUse=SAME_DAY_RESEARCH` 的外部证据进入模型，RESEARCH_ONLY外部结果必须在Gateway处无原文收口；在此基础上且没有估值等策略拒绝内容时才派生 SAME_DAY_RESEARCH，否则为 null。父轮观点可作为明确标注的“历史模型观点”上下文，但不能伪装成外部事实/citation或让被隔离的RESEARCH_ONLY provider内容回流。SAME_DAY 聚合绝不把 RESEARCH_ONLY 降格证据当作 SUPPORT、OPPOSE、confidence 或 risk 的输入。

结构化字段与完整流式原始响应同时保存；第 7.5 节估值策略拒绝的 raw chunk 仅作受限审计且不在短线公开端点回放。解析失败时保留所有其他已接收 chunk、错误和 finishReason，role session 标记 INCOMPLETE。

## 10. 确定性聚合器

不增加第五个模型主席。聚合器只读取通过服务器校验的 opinion 字段，不解析自由文本推断风险。

advisoryState：

- PENDING
- RISK_ALERT
- INCOMPLETE
- CONSENSUS_SUPPORT
- DIVERGENT

固定优先级和真值表。普通GET先执行第17.1节freeze-first；尚无freeze时才用同一个 GuardUse.evaluate 取得内部保守区间，并且只允许三个互斥分支：`upperNow<effectiveOutcomeAt` 是 PRE_DEADLINE；`lowerNow<effectiveOutcomeAt<=upperNow` 是 TIME_UNCERTAIN；`lowerNow>=effectiveOutcomeAt` 是 POST_DEADLINE。TIME_UNCERTAIN 固定零 mutation、零 freeze、零 PENDING DTO，HTTP 返回 `503 TIME_AUTHORITY_UNCERTAIN` 与 `Retry-After: 1`；React 保留最后已显示数据并继续最多一秒一次轮询。只有 PRE_DEADLINE 查询可投影活跃状态；POST_DEADLINE 的普通GET只读取已提交freeze，缺失/未决固定503。只有BatchOutcomeDueCoordinator/OUTCOME_FINALIZER可在POST_DEADLINE经GuardUse创建freeze，并在CONTENT_SNAPSHOT中把所有无封存terminal fact的role视为TIMED_OUT：

1. 仅当 upperNow < effectiveOutcomeAt，batch 尚未终止且任一 role session 仍非终态时：PENDING。lowerNow 到达 effectiveOutcomeAt 后 PENDING 不是合法 freeze 值。
2. 截止或所有角色终止后，先按当前 batch advisory use 计算 `qualifiedOpinion`：必须 `complete=true,validated=true,stance!=INSUFFICIENT`，SAME_DAY_RESEARCH 还必须 `qualifiedAdvisoryUse=SAME_DAY_RESEARCH`；RESEARCH_ONLY 接受 SAME_DAY_RESEARCH 或 RESEARCH_ONLY。未合格 opinion 的 stance/confidence/severeRisks 对本次聚合全部视为不存在。
3. 若合格意见中存在 RISK_CONTRARIAN 的 OPPOSE、confidence >= 75，且至少一个 HIGH severeRisk 绑定同股、与当前 advisory use 相容的 evidence：RISK_ALERT；SAME_DAY 要求该 evidence pointInTimeEligible 且 advisoryUse=SAME_DAY_RESEARCH。
4. 否则，qualifiedOpinion 少于三个：INCOMPLETE。四个 INSUFFICIENT、两个合格加两个 INSUFFICIENT都固定落本行，不能显示 DIVERGENT。
5. 否则，合格 SUPPORT 数量 >= 3，且合格意见中不存在 confidence >= 75 的 OPPOSE：CONSENSUS_SUPPORT。
6. 其他至少三份合格意见的组合：DIVERGENT。

一个角色对某只股票缺项只影响该股票的 opinion 完整数，不影响同 batch 其他股票。聚合结果不改变候选、排序、得分或交易动作；页面必须把“规则动作”和“AI 研究意见”分开。

## 11. 模型发起的受控联网搜索

### 11.1 工具调用事实边界

模型只会生成工具名和参数，应用必须校验参数、执行外部搜索，再把结果作为 tool 消息返回模型。不能把“模型支持 function calling”描述成模型自身可以不经应用访问互联网。

模型客户端使用原生 tools/tool_calls 合同；DeepSeek thinking 模式的后续工具轮必须完整回传上一轮 reasoning_content 和 tool_call_id。工具参数永远重新做服务器校验，不能因为 strict schema 就跳过。

首期提供两个工具：

1. search_trusted_stock_evidence
2. search_discussion_history

两个工具共用唯一 `LogicalToolAdmissionGate` GuardUse adapter。每个原生tool_call在预占逻辑配额或创建execution前，严格引用第8.3节全局子序列 `AI_RUNTIME_GUARD->AI_STORAGE_QUOTA_GUARD->batch storage reservation/EVIDENCE_EVENT bucket->BUDGET_GLOBAL->BUDGET_ACTOR->BUDGET_BATCH->USAGE_RESERVATION->ROLE_RUN->INVOCATION->TOOL_CALL->EVENT_TIMELINE_GUARD->TIME_AUTHORITY_STATEMENT`，不得按toolCall/role/预算的文字顺序反锁；再用STRICT_BEFORE post-lock时间同时冻结`admissionLowerBoundAt=lowerNow,admissionUpperBoundAt=upperNow`，并要求upperNow严格早于session/egress两个deadline。upper只证明工具准入没有越过发送截止，lower才是“外部事实已公开”的保守证据截止；二者不得合并成一个 observedCutoffAt。规范化参数的ValuationIsolationPolicy结果在零锁阶段预计算、锁后按requestHash重建并进入每个注册mutation；因此即使越界或预算已满，命中query仍同事务持久taint。越界写唯一零额拒绝和有界审计事件，逻辑/物理计数零增量；普通审计bucket不足时只写免计的固定errorCode/contentRef/hash并收口，不能借用FINALIZATION_BASIS或让RECEIVED永久等待。后续AiEgressGate对每个物理请求独立复核。

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
3. 在任何预算决议、外部 execution 或物理搜索之前执行第 7.5 节 `ValuationIsolationPolicyV1` 的纯规范化，随后由同一LogicalToolAdmissionGate事务把校验结果、逻辑计数/零额DENIED reservation与toolCall原子建立；命中 query 无论余额/窗口分支都写REJECTED+valuationTaint和固定事件并返回 `VALUATION_QUERY_FORBIDDEN`，有余额才消费一次逻辑call，不创建toolExecution、不调用provider；
4. 复用已通过 `LogicalToolAdmissionGate` 的 immutable admissionLowerBoundAt/admissionUpperBoundAt，将 provider publishedTo 截断到 upper，但点时资格只使用 lower；不得再取时升级；
5. 使用服务器维护的可信域名集合，而不是模型提供的域名；
6. 调用直接 source adapter 或 Tavily discovery provider；
7. 规范化返回 URL，验证 HTTPS、无 userinfo、无 IP literal、精确 host 边界和 source type，再按第7.5节扫描全部可见 surface；
8. 对每个返回项先执行估值隔离，再判断点时资格；命中估值的项只存 INTERNAL_POLICY_AUDIT hash/拒绝码，永不产生模型可见 evidence；
9. 对外部publishedAt：`publishedAt<=lower`才可继续判断SAME_DAY；`lower<publishedAt<=upper`固定`EVIDENCE_TIME_UNCERTAIN`且只能RESEARCH_ONLY；`publishedAt>upper`固定`EVIDENCE_NOT_POINT_IN_TIME`。后两支在SAME_DAY中只返回无原文gap，不返回title/snippet/excerpt/URL；对RESEARCH_ONLY batch才可返回其合法研究内容；
10. 服务器分配可见 evidenceId 后先持久化，再把带类型的 tool data 回传模型；
11. 将网页文本和历史模型文本视为不可信数据，不能改变工具、域名、预算、权限或系统指令；
12. 记录工具请求、校验结果、搜索耗时、供应商 requestId/credits、每条证据和失败原因。

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
- DirectPolicyAdapter：只复用与 TrustedSourceRegistryV1 exactHost 相交的政府政策源，并使用与公告附件同等级的受限 HTTPS fetcher；只有来源自身给出的权威发布时间不晚于该次工具 admissionLowerBoundAt 时才可标记点时合格。retrievedAt 必然可能晚于行情快照截止，只用于审计，不能被误作发布时间。运行时配置里任意新增的 URL 不能自动获得 AI 联网权限；
- TavilySearchAdapter：用固定 https://api.tavily.com/search 做发现和新闻摘要，不直接抓取结果 URL。

历史讨论和已经持久化的证据由 search_discussion_history 与本地 evidence repository 提供，不伪装成新的互联网事实。

Tavily API Key 只允许从固定操作系统环境变量 TAVILY_API_KEY 读取，不能通过模型、数据库任意字段、Spring Environment 任意属性名或 API 请求选择。搜索端点固定，不允许在管理页面修改。

Tavily 请求固定 search_depth=basic、auto_parameters=false、max_results=8、include_answer=false、include_raw_content=false、include_usage=true，并显式传 include_domains、start_date/end_date 与 topic。第三方过滤不替代本地 registry 校验。

本版本绝不从 API 服务器对 Tavily 返回 URL 发起 HEAD、GET 或重定向探测，因此不会声称复验其重定向链或 DNS。只保存 provider snippet 和经过规范化的链接。未来若需要正文抓取，必须另行设计隔离 egress fetcher。

Tavily 的日期过滤只有自然日粒度。只有 topic=news、返回精确可解析 publishedAt、且 publishedAt <= 该次工具 admissionLowerBoundAt 的结果，才可能成为 SAME_DAY_RESEARCH 的 pointInTimeEligible 证据；该工具还必须满足 `admissionUpperBoundAt < min(role.sessionDeadline, role.latestEgressPermitAt)` 并通过本地 registry。`lower<publishedAt<=upper` 永远不是点时证明。general/finance、时间缺失、政府页或公司 IR 的 Tavily 结果只能 RESEARCH_ONLY。它们在 RESEARCH_ONLY batch 可供研究，在 SAME_DAY_RESEARCH batch 只能形成无原文的 evidenceGap，模型看不到其 title/snippet/excerpt/URL，因此既不能形成支持票也不能以高置信反对票阻止共识。公告、交易所与政策的同日点时证据必须来自 direct adapter 的权威时间。

每条 evidence 额外保存 providerPublishedAtRaw、admissionLowerBoundAt、admissionUpperBoundAt、pointInTimeBasis、firstSeenAt、contentVersion、contentHash、eligibilityReason 和 advisoryUse。pointInTimeBasis只允许DETERMINISTIC_DATA_CUTOFF、AUTHORITATIVE_PUBLISHED_AT、NONE；外部pointInTimeEligible=true要求publishedAt非空且`publishedAt<=admissionLowerBoundAt<=admissionUpperBoundAt`、advisoryUse=SAME_DAY_RESEARCH、basis=AUTHORITATIVE_PUBLISHED_AT。分类创建后不可升级。搜索不可用时工具返回 SEARCH_UNAVAILABLE，角色继续使用本地合格证据并公开 evidenceGaps。

DirectDisclosureAdapter 获取公告附件时使用专用受限 fetcher：仅 HTTPS/443、固定 host、禁止 userinfo/IP literal、IDNA 规范化、逐跳手动重定向且每跳重验 A/AAAA、阻断私网/链路本地/保留地址和 IPv4-mapped IPv6、连接期 DNS pin。运输限制固定连接2秒、整次8秒、最多3跳、解压后正文2MiB、只接受registry声明的MIME；达到任一边界立即停止且只返回SOURCE_RESOURCE_LIMIT，不把部分正文伪装为合格证据。上游给出的任意绝对 URL不能直接交给通用 HttpClient。Tavily官方固定端点同样连接2秒、整次8秒、解压后响应2MiB，redirect=NEVER；这些时间都包含在role session预算内。

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
- batch、actor=local-operator、source.windowDate 三层预算持久化到数据库，默认每批最多16个模型 invocation、8个 evidence tool call、4个 history tool call、8个 Tavily 请求、16个 direct fetch；每 actor/source预算日 discussion batch 合计最多20，其中 CONTINUATION batch 最多10，同时处于 PENDING/DEFERRED 的 continuation source 最多2，同 actor 已物化但尚未提交 outcome freeze 的活跃 batch 最多4；模型 invocation最多400、evidence tool call最多160、history tool call最多80、Tavily请求最多160、direct fetch最多320。两个旧付费路由另有每actor/本地日最多500个不同Idempotency-Key admission attempt的固定行数上限，其中最多400个能消费模型预算；同key黑窗重提不重复计attempt。每次换 Idempotency-Key 仍受同一持久额度；所有上限只可从服务器配置文件收紧，不能由模型或浏览器扩大。

## 12. 历史讨论搜索与延续

### 12.1 股票线程

每个六位股票代码只有一个持久化 discussion thread。每次手动扫描讨论或手动继续创建不可变的新 round。

- 初次讨论 parentRoundId 为空。
- 用户点击“继续讨论”时必须指定当前查看轮次为父轮次。
- 全链路只有一个父轮终结定义：`short_term_ai_parent_eligibility` 中该 round 的唯一不可变 marker 已提交才算“已结束”，`roundEndAt=marker.eligibleAt`、basis=RAW_ACCEPTED_TERMINAL|OUTCOME_FREEZE。正常 raw 终态只有 terminalContentCommitNo 已被 content ledger 接纳时，才可在最终 round 聚合事务插入 RAW_ACCEPTED_TERMINAL marker，eligibleAt 固定为同事务写入的 round.completedAt；恢复器只可从这个已提交 completedAt 幂等补插，不能取恢复时刻。未封存 deadline/UNKNOWN/非终态必须等待不可逆 freeze 事务用 frozenAt 插入 OUTCOME_FREEZE marker。每个 marker 事务先锁 `PARENT_MARKER_SEQUENCE_GUARD`，分配严格递增 markerNo 并持锁到 marker commit；因此较大 markerNo 不会先于较小值提交。`UNIQUE(round_id)` 第一个已提交赢家永久冻结 markerNo/eligibleAt/basis；后来的 freeze/closer 只重读，不得更新。父链解析、继续讨论校验、HistoryPage 的“已结束”和服务插入校验只读这个 marker，不再从 mutable raw/effective 状态推导 roundEndAt。
- MANUAL_SCAN 的确定性事务 A/B 与 HTTP 返回路径禁止读取任何 AI 表，也不保存父链结果；形成合格终态时按第15.2.1节与manual行同事务写只增attestation。after-commit linkage worker 使用 AI 专用双连接执行一次 REPEATABLE_READ GuardUse：业务事务第一条业务SQL即按全局子序列从AI_RUNTIME_GUARD开始，随后锁storage guard、AI侧MANUAL_ATTESTATION_GUARD、admission、linkage；它以READ_MODEL非锁定读取已提交attestation并核验hash，这条SQL建立本事务唯一MVCC snapshot。之后按 symbol ASC 只查询该snapshot内commit-visible parent marker，再按 marker.eligibleAt DESC、marker.roundCreatedAt DESC、roundId DESC 唯一选择，不依赖或锁mutable round表，并把GuardUse写入的parentResolutionAt仅作审计。业务锁齐后独立authority连接的单语句DML只用于延期/最终deadline/CAS门禁，不会重建、推进或扩大已经建立的业务snapshot。业务边界是“第一条业务SQL建立snapshot前已提交”，不是manual时间或较晚的authority sample。成功完成查询但某symbol没有合格marker时仍写RESOLVED，并冻结null父三元组与resolutionCode=NO_ELIGIBLE_PARENT；父查询/锁局部失败必须回滚查询事务，linkage保持PENDING，仅在独立审计字段记录PARENT_QUERY_FAILED/attempt，且在唯一materializationDeadlineAt前重试，不能把异常伪装成null父轮。到线仍未解析时source与linkage同事务失败；datasource/linkage表或commit本身不可用时只能503。任何情况都不改manual。materializer只消费committed RESOLVED，复制冻结值，不重查最新父轮。
- CONTINUATION 必须在创建 source 的独立 AI REPEATABLE_READ事务中，先按正常LockPlan建立snapshot，再读取指定 parentRoundId 的已提交 marker与对应不可变round identity，逐字段重算并核验marker.roundIdentityHash；把 parentRoundId、resolvedParentRoundEndAt、resolutionBasis、resolutionCode=EXPLICIT_PARENT 一起冻结到 sourcePayload。marker/round读取不加反序锁，因为两者相关身份列均只增/创建后不可更新且禁止删除；读不到、hash不符或serialization failure就回滚，绝不创建source。materializer 禁止替换成“最新轮”，只在其自身snapshot重读相同marker/round identity并验证四个冻结值，不满足则CORRUPTED_PARENT_IDENTITY/503而不是猜测MATERIALIZATION_FAILED。
- CONTINUATION 的候选确定性快照、ruleAction/rank/hash 与 dataCutoffAt 只能从上述已核验的指定父轮不可变列逐字复制，不在后台偷偷重跑行情或改用最新手动结果；这些列与parent marker identity不一致时零batch。新联网证据有独立 evidenceCutoffAt。若用户需要新行情基础，必须重新手动扫描形成 MANUAL_SCAN round。
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

应用强制 symbol 属于当前 batch，且只查询 `parent_eligibility.markerNo<=batch.historyVisibilityMarkerNo` 的已结束轮次；current batchId 及其全部 round 无条件排除。history query同样先过ValuationIsolationPolicy，命中估值概念固定内部拒绝/零历史正文。materializer 在创建 batch 的完整主事务中按全局锁序锁 marker sequence guard，读取已提交 high-water 后持锁到 batch/round/source 提交，并把该值冻结为 historyVisibilityMarkerNo；因此不可能出现读到高水位后较小 marker 迟提交，所有四角色看到完全相同的历史集合，先完成角色的当前意见也绝不会被另一角色搜到。返回命中轮次的标识、日期、角色、已通过估值隔离的观点摘要、完整内容引用标识和当时证据。直接父轮次及历史工具投影命中旧估值内容时，模型和所有公开端只返回 `SHORT_TERM_POLICY_CONTENT_WITHHELD` 与原记录 hash；`VALUATION_CONTENT_WITHHELD`、concept code 和命中 surface 只存在于受限审计事实，禁止进入模型 tool message、Prompt、OpenAPI、事件公开投影或 DOM。其余结构化结论、变化字段、证据 ID 和原文引用可正常预载，但不把可能达到数十 MiB 的完整 raw output 无条件塞入模型上下文；更早内容通过工具按需检索。

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

- continuation question 只使用第15.3/17.2节同一canonical UTF-8 4096字节硬上限，不再存在另一套字符数上限；
- 工具 query 最多 512 个字符，JSON 嵌套深度最多 32；
- 单个供应商 HTTP 响应最多持久接收32MiB，单batch原始模型内容最多256MiB；二者由第15.6.1/15.9/15.19节的持久原子counter执行，不以最终SUM或内存计数近似；
- 持久化时按 UTF-8 代码点边界切成不超过 64 KiB 的 chunk，绝不切坏字符；
- counter 加上下一段完整 UTF-8 chunk 后精确等于上限可提交，超过上限则该超额chunk零写；允许chunker仅按UTF-8代码点边界生成一个填满剩余额度的末chunk，随后停止接收。保留此前所有chunk，并以Role status=INCOMPLETE、errorCode=INCOMPLETE_RESOURCE_LIMIT收口，不做无审计substring后伪装完整；
- 新客户端不能使用 BodyHandlers.ofString 先把不受控响应全部读入内存；每个完整有界 chunk 须在继续读 provider stream 前经 content ledger 反压持久；
- 输入/运输限制保护资源，不限制已经合法接收的研究论述在数据库和详情页中的显示。

原始历史不自动删除。feature 关闭映射 FEATURE_DISABLED，达到 AI 存储高水位映射 STORAGE_PRESSURE，materializer 数据库写重试耗尽映射 MATERIALIZATION_FAILED；普通队列压力只保持 batch QUEUED + DEFERRED。所有情况确定性扫描继续，已有历史仍可读取。

## 14. 点时与手动扫描时间

### 14.1 无自动短线任务与 AI 截止投影

应用不再注册 14:30、14:47、14:49:50 短线 cron，也不做错过后的 catch-up。下面的 coordinator/dispatcher/reconciler/watchdog 只处理某次用户操作已经持久化的 source 或基础设施健康/预算结算，不会主动扫描市场、创建候选或凭空创建source。`UserSourceDueCoordinator` 可以在已明确请求 AI 的 durable source 到点后唤醒既有工作流，随后物理网络仍只能由该 source 已创建的 operation 经 AiEgressGate 发送；这属于恢复用户已经提交的后台任务，不是自动选股或尾盘自动扫描。时区固定 Asia/Shanghai，时间来自第 8.3 节 TimeAuthority：

| TimeAuthority 区间 | AI 行为 | 手动确定性行为 |
| --- | --- | --- |
| [00:00:00, 14:30:00) | 用户 source 可按 RESEARCH_ONLY 运行 | 仅点击时扫描；不自动产生候选 |
| [14:30:00, 14:47:00) | 已认证合格 manual source 可尝试 SAME_DAY_RESEARCH；四角色并行 | manual report 完成两阶段认证后先返回 |
| [14:47:00, 14:47:20) | 不准入任何新 materialization；14:47 前已准入的 SAME_DAY/RESEARCH_ONLY batch 即使稍后才可见，仍仅可在各自 initialDispatchDeadline 前完成 INITIAL | 晚到或积压 source 冻结为 14:50 后 RESEARCH_ONLY |
| [14:47:20, 14:47:55) | 未派发 INITIAL 以 INITIAL_DISPATCH_CUTOFF 收口；已运行 role 可在预算内为工具/续写申请 egress permit | 手动扫描不等待 AI |
| [14:47:55, 14:48:30) | AiEgressGate 不再授予新 permit；已完成 transport handoff 的响应仍可以反压流式入库，只接受 receivedAt < roleContentDeadline 的内容；旧付费入口不创建新 operation | 手动扫描不等待 AI |
| [14:48:30, 14:48:44) | 取消流式 transport，封闭四个 content ledger；全部封闭后生成 canonical OutcomeBasisV1，并在严格早于 14:48:44 时由 OUTCOME_FREEZE GuardUse 执行 freeze+markers 原子短事务 | 手动扫描继续独立认证 |
| [14:48:44, 14:48:45) | 不再接纳新的 CONTENT_SNAPSHOT SQL；此前已准入语句以 500ms 为无暂停环境的排空目标，随后只等待已提交赢家 | 手动扫描继续独立认证 |
| [14:48:45, 14:50:00) | BatchOutcomeDueCoordinator/OUTCOME_FINALIZER 尝试提交唯一 outcome freeze；API 只读取已提交 freeze，缺失或唯一赢家事务未决固定 503，绝不创建或推进任何业务事实；旧付费路由保持 503 | MANUAL_CERTIFY 仅到 14:49:30（等号允许）；已认证 FINAL_READY 的只读 executionEligibility 仅到 14:49:40（等号允许），均不受 AI 锁/连接影响 |
| [14:50:00, 次日) | 只有 lowerNow>=14:50 且 upperNow<冻结 deadline 的延后 source 才以唯一 CAS materialize 一次 RESEARCH_ONLY；新用户 source 可立即 RESEARCH_ONLY | 仅点击时扫描；AI 不得创建当日买点 |

14:48:45 是“用户不再看到 PENDING”的状态线，不是虚假的 JDBC commit 屏障。OUTCOME_FINALIZER 在发布支持负载下按第 4.3/8.3 节的 250ms 启动、500ms决议 SLO 主动收口；持久层可决议时返回已固定结论，唯一赢家事务未决或数据库不可用时返回 503/PERSISTENCE_UNAVAILABLE。若一次健康 read projection 恰处于 `lowerNow<effectiveOutcomeAt<=upperNow`，它既不能证明截止前也不能证明到线，固定返回503/TIME_AUTHORITY_UNCERTAIN、Retry-After:1、零mutation且绝不返回PENDING；只有后续lowerNow到线才可freeze。freeze 只纳入四个已封闭 content ledger 中 contentCommitNo<=acceptedThroughCommitNo 的内容，MODEL_CHUNK 还必须 receivedAt<14:48:30；未通过 content gate 或未进入封存范围的写不在 basis 内。14:48:44 是 CONTENT_SNAPSHOT post-lock GuardUse 授权线，不是 commit 可见线：500ms 是无进程/DB暂停时的排空目标，不是任意暂停下绝对最晚可见时刻。14:48:45 尝试 fallback；若数据库在 fallback 的 500ms timeout 内仍无法决议唯一赢家，API 返回503而非PENDING/假fallback；恢复后只读最终已提交唯一行。读路径与最终 closer 使用同一纯函数和同一 basis hash。

没有用户手动操作时，系统不会自动生成、认证或发布短线结果。故障注入证明 AI 网络、AI 表锁、连接池、队列或恢复器不能阻塞同时发生的 manual result，而不是证明一个已取消的 cron 会运行。

### 14.2 手动讨论

手动扫描在确定性接口允许的时间均可触发；sourceDecisionAt 对 MANUAL_SCAN 指 manual.deterministicFinalizedAt，对 CONTINUATION 指 admission.createdAt。FINAL_READY 的认证 adapter 在模块内部写 GuardUse.businessNow；CACHE_PREVIEW/RESEARCH_PREVIEW 的终态 adapter 同样在模块内部写 businessNow，不伪造 certifiedAt。是否启动 AI 使用以下有序矩阵：

| source 提交时状态 | AI 行为 |
| --- | --- |
| 数据质量阻断、扫描失败或候选为空 | 只冻结 manual.aiAdmissionReason=NOT_APPLICABLE；零 source/linkage/batch/模型任务 |
| 已形成合格确定性终态且候选非空的 MANUAL_SCAN，其 sourceDecisionAt 位于任意日期的 [14:47:00, 14:50:00) | 冻结请求时 aiRequested，并由 after-commit creator 创建 DEFERRED_CUTOFF/AI_DEFERRED_CUTOFF；14:50 后仅对这条已存在且提交时 aiRequested=true 的 source 按 RESEARCH_ONLY materialize。health 失败的 manual 必须按第8.3/15.2节收口 DATA_BLOCKED 或 FAILED/NOT_EVALUATED，严格零 source/linkage，不进入本行 |
| CONTINUATION 的 sourceDecisionAt 位于任意日期的 [14:47:00, 14:50:00) | 返回 503 AI_EGRESS_CUTOFF 和 Retry-After；客户端保留同一 key/原请求，14:50 后仅重试该用户操作 |
| 交易日、非缓存、确定性状态 FINAL_READY、TimeAuthority health proof 通过，且 certifiedAt 在 [14:30:00, 14:47:00) | 只在 materializer lowerNow 本地日期=manual.tradeDate 且 upperNow<14:47 时取得 SAME_DAY_RESEARCH admission；否则按黑窗延期或 RESEARCH_ONLY。既有 batch 的 INITIAL 未在 14:47:20 前派发则 INITIAL_DISPATCH_CUTOFF |
| CONTINUATION 位于其他允许时段 | 立即 materialize，RESEARCH_ONLY |
| 交易日但提交时间在 [00:00, 14:30:00)，或报告是缓存/非执行预览 | 允许立即运行，但只能 RESEARCH_ONLY |
| 非交易日 | 允许立即运行，但只能 RESEARCH_ONLY |
| 任意日期 [14:50:00, 24:00) | 可立即运行，RESEARCH_ONLY |

上述“允许运行”还要求 aiRequested=true、预算/存储/配置和 health proof 通过；materializer 使用 NOT_IN_LOCAL_WINDOW 的 upper/lower 重判。黑窗延期严格按第 15.3 节一次性资格和 D 日固定时间，不能取“当前日”重置。14:47 是认证/admission 资格线而非点击或可见屏障；页面 14:46 后提示可能赶不上同日 AI。

### 14.3 后续手动扫描与历史变化

取消自动预选/最终后，不再创建 ADDED、RETAINED_CHANGED、RETAINED_UNCHANGED、DROPPED transition 或 FINAL_SUPPLEMENT batch。每次成功手动扫描都是独立 source；最多前八只形成各自 round，并按第 12 节连接当时已经结束的父轮。discussionMaterialHash 用于页面和角色比较当前 round 与父 round 的实质字段变化，snapshotHash 用于精确审计；它们不触发第二个自动付费批次。

## 15. 数据模型与版本化合同

### 15.1 通用 SQL 约定

- 应用生成的主键统一 VARCHAR(36) UUID。
- symbol 为 VARCHAR(6)，hash 为 VARCHAR(64)，枚举统一为 VARCHAR(64) 并带 CHECK；稳定 error/reason code 同样使用 VARCHAR(64)。Stage 0 必须在 H2/PostgreSQL 对每个 canonical enum 的最长合法字面量逐一做 DDL/JPA/OpenAPI round-trip，禁止再次以较短默认列宽截坏 `MATERIALIZATION_INPUT_RESOURCE_LIMIT` 等正式状态。
- 所有用户提供的 Idempotency-Key 在任何持久行、guard、ticket或预算前统一验证为ASCII `[A-Za-z0-9._:-]{1,128}`，按原字节区分且不trim/Unicode归一化；缺失或越界固定400，数据库列VARCHAR(128)。scan、CONTINUATION与两个旧付费路由共用同一验证器，不能让超长key进入UUIDv5/name、日志或唯一索引。
- 时间为 TIMESTAMP WITH TIME ZONE；普通审计时间可使用 CURRENT_TIMESTAMP，硬门禁、状态 CAS 和租约必须使用第 8.3 节的数据库 wall-clock 读取方式，不能在 PostgreSQL 长事务里使用冻结的事务级 CURRENT_TIMESTAMP。
- TimeAuthority adapter 的 raw/lower/upper/businessNow 统一 UTC 微秒精度：H2 alias 向下截断微秒，PostgreSQL 原生微秒；所有 deadline/持久时间/OutcomeBasisV1 沿用，禁止跨库 hash 漂移。
- 长内容在 canonical DDL 中使用 TEXT，不能 VARCHAR 截断。
- 所有外键默认 ON DELETE RESTRICT；讨论历史不可级联删除。
- 所有 mutable entity 带 BIGINT version NOT NULL DEFAULT 0。
- 必填列明确 NOT NULL；可空只用于尚未发生的时间、父轮次或错误。
- H2 文件库只支持单实例；PostgreSQL 承担多实例和真实并发合同。
- H2 的 `APP_WALL_CLOCK_NOW()` 只有一个实现：`com.aistock.research.shortterm.ai.time.H2WallClockAlias.appWallClockNow()` 静态方法返回 UTC `OffsetDateTime` 并向下截断微秒，内部使用注入/生产 `Clock.systemUTC()`，不得标记 DETERMINISTIC。H2 profile 在任何 TimeAuthority bean/migration 使用前，由幂等 bootstrap 执行 `CREATE ALIAS IF NOT EXISTS APP_WALL_CLOCK_NOW FOR 'com.aistock.research.shortterm.ai.time.H2WallClockAlias.appWallClockNow'` 并用两次调用验证推进/返回 JDBC TIMESTAMP_WITH_TIMEZONE；PostgreSQL profile 禁止注册 alias。该 Java alias 是平台适配 bootstrap，不是第二份业务 schema，不进入 canonical business DDL/hash；其方法名、返回类型、注册顺序和推进测试仍由 Stage 0 冻结。

阶段 0 必须在实现 G 前实际提交并审查以下合同文件；不是只写文档占位：

- canonical schema.sql/版本化 migration 的完整 DDL，以及 H2 alias bootstrap；
- MaterializationArtifactV1/StageContentRefV1、AiCandidateBatchSnapshotV1、AiCandidateSnapshotV1、RoleOpinionsV1、OutcomeBasisV1 和两个工具/分阶段 execution plan 的 JSON Schema；
- OpenAPI 3 合同与 React 生成/契约类型；
- 状态枚举、CHECK、索引、FK 和错误码表。

TimeAuthority/GuardUse 的 canonical DDL 也在 Stage 0 冻结：`app_time_authority_state` 包含 authority_key PRIMARY KEY、authority_epoch BIGINT、durable_raw_high_water TIMESTAMP(6) WITH TIME ZONE、durable_guard_high_water TIMESTAMP(6) WITH TIME ZONE、updated_at TIMESTAMP(6) WITH TIME ZONE、version BIGINT，CHECK authority_key='WALL_CLOCK' AND durable_guard_high_water>=durable_raw_high_water。`app_time_authority_probe_sample` 以 instance_id PRIMARY KEY 保存最近一次 statement 已提交的 boot_id/epoch/raw/guard/request_start_token/request_start_nano/sample_generation/sample_kind BASELINE|VALID_ADVANCE；generation只增，request_start_nano只在同boot内比较，boot变化走BOOTSTRAP，坏锚点用expected token/generation的REBASELINE，三种mode都只允许watchdog单语句upsert。单行初始化只捕获且核验目标唯一约束，禁止exists-then-insert。epoch audit保存旧/新双floor且只允许提高。`guard_use_ticket` 保存 execute 的 guard_use_id PK、operation/adapter/resultMutation/mutation/subject/predicate identity、authority epoch/version、candidate/raw/lower/upper/windowDate/businessNow/branch、PERMITTED|REJECTED、ticket_hash；表本身 immutable，生产逻辑禁止恢复消费 orphan 或写 ABANDONED 状态。`app_guard_use` 保存 guard_use_id PK、operationKind+mutationId唯一、ticket_hash、相同identity/时间、business_identity_hash与mutation result，和业务mutation同事务全有或全无。`evaluate`只用单条autocommit DML推进同一高水位，ack后无锁读保守projection，不写两张只增表。Stage 0 同时提交并实际执行 PostgreSQL `materialized raw CTE -> UPDATE RETURNING -> INSERT ticket/sample` 与 H2 `single raw alias -> FINAL TABLE(inner MERGE) -> probe outer MERGE / execute outer INSERT / evaluate outer SELECT` 的逐字单语句canonical resource；禁止应用侧 `SELECT ... FOR UPDATE` 多语句 authority transaction。还冻结包私有HealthProofV1/GuardUseCommandV1/GuardedReadCommandV1/GuardUseResultV1/GuardPredicateV1/LockPlanV1/TransportCommandV1，公开的最小operation-specific interface/领域DTO、TransportOperationRefV1/TransportAttemptOutcomeV1合同和架构测试；内部类型禁止Jackson/JPA/Java serialization，HealthProof构造器仅monitor可见，TransportCommand不能承载URL/header/body/lambda/closure，GuardUseResult/COMMITTED不能导出gate implementation package。Stage0还冻结mutation/read/transport policy矩阵、direction/windowDate/businessNow映射、两张只增表及 runtime/materialization-stage/paid-admission+attempt/continuation/actor-workload/active-slot/storage-quota/storage-reservation+bucket/paid-workload/paid-operation-slot/budget/content-quota/marker-sequence/event-timeline/outcome/parent guard DDL，paid response chunk运输上限，event cause_key生成/UNIQUE语义，以及 `AI_FEATURE_DISABLED`、`FEATURE_REVISION_CHANGED`、`LLM_CONFIG_UNAVAILABLE`、`STORAGE_HIGH_WATER`、`PAID_DAILY_ATTEMPT_LIMIT`、`PAID_RESPONSE_RESOURCE_LIMIT`、`ACTIVE_BATCH_LIMIT` 等稳定错误码；G/M/B/C/D/E不得自行发明时间、锁序或transport语义。

Stage 0 的稳定错误码集合还必须明确包含 `INSTANCE_BOOT_LEASE_HELD`、`INSTANCE_SLOT_NOT_DECLARED`、`INSTANCE_BOOT_FENCED`、`TIME_AUTHORITY_UNCERTAIN`、`READ_PROJECTION_CHANGED`、`VALUATION_QUERY_FORBIDDEN`、`VALUATION_CONTENT_REJECTED`、`VALUATION_RAW_OUTPUT_REJECTED`、`VALUATION_CONTENT_WITHHELD`、公开投影专用的 `SHORT_TERM_POLICY_CONTENT_WITHHELD`、`SHORT_TERM_QUESTION_POLICY_REJECTED`、`POLICY_CLASSIFICATION_INCOMPLETE`、`EVIDENCE_NOT_POINT_IN_TIME`、`EVIDENCE_TIME_UNCERTAIN`、`CORRUPTED_MODEL_PREPARE`、`CORRUPTED_TOOL_PREPARE`、`CORRUPTED_MANUAL_ATTESTATION`、`CORRUPTED_MATERIALIZATION_STAGE`、`MATERIALIZATION_INPUT_RESOURCE_LIMIT`、`MODEL_RESPONSE_RESOURCE_LIMIT`、`MODEL_RESPONSE_PERSISTENCE_FAILED`、`PROVIDER_REDIRECT_REJECTED`、`TOOL_RESULT_RESOURCE_LIMIT`、`TOOL_RESULT_PERSISTENCE_FAILED`、`PAID_RESPONSE_DEADLINE_EXCEEDED`、`CONTENT_ROW_LIMIT_EXCEEDED`、`CORRUPTED_CONTENT_MANIFEST`、`CORRUPTED_CHUNK_SEQUENCE` 与 `AI_STATE_RECONCILIATION_PENDING`，并冻结 manual AI 意图/身份原因码 `AI_NOT_REQUESTED`、`AI_PROXY_IDENTITY_UNAVAILABLE`、`AI_FEATURE_DISABLED_AT_SUBMISSION`、`AI_REQUESTED`；readiness、HTTP、日志、指标和启动诊断必须使用这些冻结语义，不得以笼统连接失败/模型失败替代。

`app_time_authority_probe_sample` 的raw列逐字命名为`sampled_db_now`，并必须有 `boot_lease_until TIMESTAMP(6) WITH TIME ZONE NOT NULL`，CHECK `boot_lease_until>=sampled_db_now`；三种probe模板都从同一raw alias写这两列，BOOTSTRAP对不同boot执行“租约已到+expected旧token/generation”CAS，不能用应用先SELECT再判断。发布配置的replica slot清单与数据库中当前未到期boot逐项核验；重复slot的新实例必须在启动探针阶段fail closed，不能进入Web ready后再竞争。

`short_term_ai_runtime_guard` 是唯一 `guard_key='AI_RUNTIME'` 行，保存 enabled、runtime_guard_revision、llm_config_revision、storage_state、version。运行时配置 PUT 必须先锁该行，在同一事务更新 runtime_config_section 与两个 revision/enable 状态；存储监控器也只能在检测结果跨越 NORMAL/HIGH_WATER 状态边界时锁该行并提交 storage_state，不能在 materializer 锁外另读一个可穿透的进程变量。materializer、CONTINUATION admission 与每次 AiEgressGate handoff 都先锁同一行、读取配置并持锁到 source/batch/handoff marker 的业务 commit。PUT/存储状态 commit 后本实例原子发布只含 enabled/runtimeGuardRevision 的无秘密本地 snapshot，其他实例由只读配置刷新器最终更新；manual HTTP 只读该 snapshot 以保守冻结 AI 意图，任何滞后都会在 after-commit source creator 的数据库 guard revision 核验中拒绝，不能授权外发。任何秘密仍只在受控配置存储/环境访问器中解析，不复制到 guard/snapshot。DDL/Stage0 双实例 latch 固定 PUT先提交与marker先提交两种唯一结果。

batch/round/role 不保存 `effective_status/outcome_basis_hash/outcome_fixed_at`；唯一 freeze 的 batch_effective_status、round_statuses_json、advisory_states_json 与 OutcomeBasisV1 effectiveRoleStates 才是 effective 真相。后者固定 rawFactKind/rawStatus/terminalContentCommitNo/effectiveReasonCode，封存 UNKNOWN 映射 INCOMPLETE；UNSEALED rawStatus固定null，effectiveStatus再由freezeKind真值表唯一决定。

以下结构是该冻结合同的最低要求，阶段 0 不能减少。

### 15.2 short_term_manual_scan_result

手动扫描不再只存在内存：

- result_id；
- actor_id；
- idempotency_key；
- request_hash，等同 request_semantic_hash；
- normalized_request_json，只保存当前策略实际生效字段；原始兼容 DTO 可另作不参与身份的审计记录；
- feature_revision，来自请求开始时本实例原子发布的只读 AI runtime snapshot，只作后续数据库 guard 比对，manual HTTP/A/B 均不查 AI 表；
- ai_user_requested，NOT NULL，逐字冻结请求体必填布尔字段 `requestAiDiscussion`；可信代理/header、feature/config 或服务器默认值都不得替用户把 false/缺失改成 true，缺失固定400；
- ai_request_identity_status：TRUSTED_PROXY、UNTRUSTED_PROXY，冻结本次请求是否通过第17.3节代理身份；
- ai_requested，NOT NULL；当且仅当 `ai_user_requested=true AND identity=TRUSTED_PROXY AND 本地runtime snapshot.enabled=true` 才为 true。它是保守能力快照，不是外发许可；
- ai_admission_reason，按优先级唯一为 AI_NOT_REQUESTED、AI_PROXY_IDENTITY_UNAVAILABLE、AI_FEATURE_DISABLED_AT_SUBMISSION、AI_REQUESTED。用户意图、代理身份和能力状态分列保存，不能把“未请求”和“请求但暂不可用”压成同一事实；
- trade_date；
- result_status：PENDING、RUNNING、SUCCEEDED、FAILED、TIMED_OUT；
- deterministic_status：NOT_EVALUATED、FINAL_PENDING、FINAL_READY、NO_TRADE、DATA_BLOCKED、CACHE_PREVIEW、RESEARCH_PREVIEW；
- result_message；
- report_json；
- report_payload_hash；
- eligible_candidate_count、candidate_set_hash；报告尚未stage时二者可同时为空，之后count是当前短线策略规范化后可进入AI的候选数且固定`0<=count<=40`，与现有后端limit硬上限一致；hash对按ruleRank ASC、symbol ASC排序的完整候选引用清单做canonical SHA-256。确定性终态count>0时hash必填，count=0时hash为空，二者与report_json/hash在同一stage/finalize事务冻结；
- blocked_reasons_json，结构化稳定 reason code 数组；
- data_cutoff_at；
- deterministic_deadline_at；普通交易日固定 14:49:40；
- payload_committed_by_at、certification_attempted_at、certified_at；
- deterministic_finalized_at，只有确定性终态必填，是后续 sourceDecisionAt 的权威值；
- certification_epoch、certification_error_code；
- claim_owner、claim_token、claim_epoch、lease_until、heartbeat_at；
- created_at、started_at、completed_at、version；
- UNIQUE(actor_id, idempotency_key)；
- CHECK：`ai_requested=true` 必须同时满足 userRequested、trusted identity、reason=AI_REQUESTED；其余三个原因各自固定 false 与对应输入组合。candidate字段只允许`count/hash均空`、`count=0/hash空`或`1<=count<=40/hash非空`三组；FINAL_PENDING 与任一确定性终态都要求count非空。FINAL_PENDING 必须有 report_json/hash/payload_committed_by_at、deterministic_finalized_at/certified_at 均为空，且 result_status=RUNNING；FINAL_READY 必须另有 certified_at 与 deterministic_finalized_at，两者相等且 result_status=SUCCEEDED；DATA_BLOCKED/NO_TRADE/CACHE_PREVIEW/RESEARCH_PREVIEW 为 SUCCEEDED 的确定性终态，deterministic_finalized_at 必填但 certified_at 必须为空；FAILED/TIMED_OUT/NOT_EVALUATED 不得伪造确定性终态或认证时间。

POST 先提交 PENDING；worker 用 CLAIM_MANUAL GuardUse 的 businessNow、claim token/epoch/lease CAS 认领 RUNNING，心跳/report 均通过注册 GuardUse adapter。恢复器可认领 PENDING/过期 RUNNING；health 无效时不得等待可执行claim或永久保持PENDING，而是调用第8.3节 `MANUAL_TIME_AUTHORITY_FAILURE` 唯一收口；它可保留已经计算/暂存的非执行报告但不得 FINAL_READY。超时原子 TIMED_OUT，旧 worker 迟到写零行。

manual result 不复用即将删除的 scheduled store，而是迁移其两阶段语义：

1. ShortTermFinalResultGate manual 分支使用真实窗口，不再 enforceDeadline=false。health 正常时以注册 GuardUse 的 businessNow 写非执行终态 deterministicFinalizedAt/completedAt；不读 AI 表/父链，certifiedAt=null。health 无效只能走 `MANUAL_TIME_AUTHORITY_FAILURE`：高水位可读时为 DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE，高水位本身不可读时为 FAILED/NOT_EVALUATED/TIME_AUTHORITY_STATE_UNAVAILABLE；两者都不伪造点时状态、不创建AI source。
2. 对候选可执行报告，事务 A 用 MANUAL_STAGE GuardUse 写 canonical report_json、SHA-256、blocked reasons、dataCutoffAt、payloadCommittedByAt，并将 NOT_EVALUATED -> FINAL_PENDING；result_status 仍为 RUNNING。
3. 独立 MANUAL_CERTIFY GuardUse 重读同一 hash/epoch，使用 `ON_OR_BEFORE(D14:49:30)`；以 upperNow 同时写 certificationAttemptedAt/certifiedAt/deterministicFinalizedAt、FINAL_READY/SUCCEEDED，并在符合第15.2.1节条件时同事务插入不可变attestation。事务 A/B/HTTP 禁止查询 AI 业务表；能力失败adapter也只能额外读取TimeAuthority持久高水位。健康但截止失败由GuardUse拒绝分支以其upperNow收口DATA_BLOCKED/FINAL_CERTIFICATION_CUTOFF；health失败按上一条能力失败真值表收口TIME_AUTHORITY_UNAVAILABLE或TIME_AUTHORITY_STATE_UNAVAILABLE。14:49:30 是认证准入截止且等号允许，不是 14:49:40 executionEligibility 截止；业务锁等待发生在 post-lock 取时前，GuardUse 总预算<100ms，物理 commit 未决为 503/恢复读唯一事实。是否仍可执行另看 executionEligibility。
4. 进程在 A/B 之间崩溃时，恢复器在 upperNow<=D14:49:30 时可按同 hash/epoch 完成 B；upperNow>D14:49:30 必须原子 FINAL_PENDING -> DATA_BLOCKED，reason=FINAL_CERTIFICATION_INTERRUPTED，不能把仅 stage 的 payload 恢复成 READY。
5. 事务 B 后线程暂停或响应丢失不会重复认证：若数据库已是同epoch/hash的FINAL_READY，GET必须原样返回这条不可逆认证事实，后续TimeAuthority健康变化只让独立ExecutionEligibilityV1变TIME_AUTHORITY_UNAVAILABLE/EXPIRED，绝不把行改回DATA_BLOCKED。只有数据库仍是FINAL_PENDING且B未提交时，公开认证截止后才按第4条或能力失败真值表收口DATA_BLOCKED；已持久FINAL_READY的epoch/hash/certifiedAt自相矛盾则503/CORRUPTED_MANUAL_CERTIFICATION并报警，不自动猜写。Stage M 先用H2/注入时间实现A后崩溃和B提交/响应丢失两支，Stage E只以真实推进时间和数据库故障复验，不得首次补写该逻辑。

`FINAL_READY` 是持久认证事实。`deterministicDeadlineAt=D14:49:40` 只表示读时执行资格截止；读 API 以一次性 GuardUse.evaluate 的 `ON_OR_BEFORE(deterministicDeadlineAt)` upperNow 直接生成 `ExecutionEligibilityV1`，等号允许。只有 proof/subject 完整且 permitted 才 ELIGIBLE，最终 DTO 只能展示且不能授权 mutation/transport，health 无效优先 TIME_AUTHORITY_UNAVAILABLE。14:49:30 后不能新认证，但此前已认证且迟可见的 FINAL_READY 在 upperNow<=14:49:40 仍可读为 ELIGIBLE；之后只作历史，不会重开。candidate count/hash属于确定性payload事实，不因AI创建、预算或配置改变。

reconciler 只为 ai_requested=true、result_status=SUCCEEDED、deterministic_status 属于 FINAL_READY/RESEARCH_PREVIEW/CACHE_PREVIEW 且eligibleCandidateCount>0/candidateSetHash非空的结果处理 admission；NO_TRADE、DATA_BLOCKED、扫描 FAILED/TIMED_OUT 和空候选永不收费。manual after-commit source creator 不信任本地 snapshot 作授权：它锁 AI_RUNTIME_GUARD，按确定性admissionId先插入/锁预建MATERIALIZATION_STAGE_GUARD，再锁AI侧MANUAL_ATTESTATION_GUARD，使用READ_MODEL非锁定读取attestation/manual immutable payload并重算全部hash，绝不锁deterministic表；stage guard、source admission与任何初始终态必须同事务全有或全无。只有 enabled=true 且 guard.runtimeGuardRevision=attestation.featureRevision 才创建 MATERIALIZATION_PENDING。enabled=false 时在同一幂等 source identity 下直接创建不可逆 status=FEATURE_DISABLED、reason=AI_FEATURE_DISABLED，enabled=true 但 revision 不一致时使用 reason=FEATURE_REVISION_CHANGED，二者均零 linkage/batch/role/预算，之后开启 feature 不得复活。与 PUT 并发时以 runtime guard 锁先提交者线性化；本地 snapshot 滞后最多保守拒绝，不能越过数据库 guard。之后补 token/开启 feature 不能追溯改变 ai_requested。

requestSemanticHash 必须复用第 7.3 节的当前策略规范化器：先移除 deprecated maxPe/maxPb 和所有忽略字段，但必须保留并哈希必填 `requestAiDiscussion`，再按 Stage 0 canonical JSON 计算 SHA-256。同一 actor/idempotencyKey 且 semantic hash 相同返回原结果，即使旧客户端只改变 maxPe/maxPb；同key从false改true或改变其他实际生效字段都必须409 IDEMPOTENCY_KEY_REUSED。内存 job 可以作为进度缓存，但 GET、恢复与讨论 sourceKey 都以 result_id 为准。

### 15.2.1 short_term_manual_scan_attestation

为了让AI验证手动结果而不锁确定性表，确定性终态事务为`aiRequested=true`、status为FINAL_READY/CACHE_PREVIEW/RESEARCH_PREVIEW且`candidateCount>0`的结果原子插入只增attestation：manual_result_id PRIMARY KEY、manual_result_version、actor_id、trade_date、feature_revision、ai_requested=true、deterministic_status、deterministic_finalized_at、source_certified_at/source_certification_cutoff、report_payload_hash、candidate_count、candidate_set_hash、request_semantic_hash、candidate_identity_manifest_json/hash、attestation_hash、created_at。candidate identity manifest 按ruleRank ASC/symbol ASC冻结最多40个候选的symbol与不可变candidate hash，canonical UTF-8不超过64KiB；attestationHash覆盖除createdAt外全部字段。FINAL_READY要求certified/cutoff非空且certifiedAt=deterministicFinalizedAt，两个preview要求二者为空；`0<candidateCount<=40`且candidateSetHash必填。UNIQUE(manual_result_id,attestation_hash)，生产路径禁止UPDATE/DELETE。

manual终态行与attestation全有或全无，确定性事务不读AI表。历史升级若存在符合条件的已提交manual终态但attestation缺失，`ManualAttestationBackfillReconciler`只可在任何source创建前通过确定性连接锁manual行、重算并幂等补建；source已存在后attestation缺失或任一hash/version不符固定503/CORRUPTED_MANUAL_ATTESTATION，禁止猜写。AI零锁预构建阶段可用READ_MODEL非锁定读取immutable manual report与attestation并重算hash；AI主事务只锁AI schema中的MANUAL_ATTESTATION_GUARD，AI数据库角色对manual/deterministic表无行锁、UPDATE、DELETE权限。attestation不是AI source，也不触发任何模型调用。

### 15.3 short_term_ai_source_admission

连接确定性持久源与 AI materializer：

本节状态机文字中的 `PENDING` 是 `MATERIALIZATION_PENDING` 的简称，`DEFERRED` 是 `DEFERRED_CUTOFF` 的简称；canonical enum、DDL、JSON 和 API 只使用完整名称，不得另建 PENDING/DEFERRED 枚举值。

- admission_id；
- source_type：MANUAL_SCAN、CONTINUATION；
- source_key；
- actor_id；
- normalized_request_hash；
- source_payload_json，字段白名单且canonical UTF-8硬上限64KiB。CONTINUATION 在任何副作用前按第7.5节验证question，CLEAR后才在 source 创建事务保存 parentRoundId、resolvedParentRoundEndAt、resolutionBasis、resolutionCode=EXPLICIT_PARENT、question 和 questionFingerprint；question canonical UTF-8硬上限4096字节，超限或策略拒绝均在创建任何source/guard/budget/log行前返回400，不substring。MANUAL_SCAN只保存manualResultId、attestationHash、reportPayloadHash、candidateCount、candidateSetHash和requestSemanticHash六个不可变引用，不复制候选、报告或请求正文；materializer在完整LockPlan中锁AI侧MANUAL_ATTESTATION_GUARD并非锁定读取attestation/manual immutable payload，逐字段核验后才从该唯一确定性事实生成stage。该固定有界source元数据计入发布时普通行增长证明，不占讨论长内容bucket；真正batch/round候选快照进入第15.3.1节MATERIALIZATION_STAGE bucket。MANUAL_SCAN不写父链，materializer只从committed linkage复制resolvedParentRoundId/null、resolvedParentRoundEndAt、resolutionBasis、resolutionCode到新round，禁止回写sourcePayload或动态重查父轮；
- admission_status；
- certification_kind：FINAL_READY_CERTIFIED、NON_EXECUTION_SOURCE，创建后不可更新；
- materialized_advisory_use，可空；只在成功 materialization 的唯一事务由 null 写 SAME_DAY_RESEARCH 或 RESEARCH_ONLY，之后不可更新；
- reason_code、reason_message；
- source_effective_at，NOT NULL；MANUAL_SCAN 取 manual.deterministic_finalized_at；CONTINUATION 创建 facade 在同一 GuardUse mutation 以 AFTER_WINDOW/BEFORE_WINDOW 的 businessNow 同时写 source_effective_at 与 created_at，二者必须相等，禁止使用事务级 CURRENT_TIMESTAMP/JPA 审计时间参与窗口；若需记录 wall-clock 审计另写不参与任何业务判断的 audit_recorded_at；
- window_date、budget_bucket_date，均为 Asia/Shanghai DATE、创建后不可更新且必须相等；MANUAL_SCAN 取 source_effective_at 日期，CONTINUATION 取创建 GuardUse.lowerNow 日期。周期窗口、延期、恢复和整个 batch 的预算永远使用这两个冻结值，不因 materializer 跨午夜而换日；
- runtime_guard_revision，MANUAL_SCAN 始终复制 manual.featureRevision 这个 requested revision；source creator 同事务锁 AI_RUNTIME_GUARD，只有当前 guard revision 与它相等才可创建 PENDING，不匹配则保留 requested revision、把 observed revision 只写入有界 reason metadata，并直接写不可逆终态。CONTINUATION 复制同一创建事务锁住的 guard revision。该字段创建后不可更新，不得先写 PENDING 后等待新 revision；
- source_certified_at，可空；FINAL_READY_CERTIFIED 必填且等于 source_effective_at，NON_EXECUTION_SOURCE 必须为空，创建后不可更新；
- source_certification_cutoff；FINAL_READY_CERTIFIED 必填并创建时冻结，NON_EXECUTION_SOURCE 必须为空；
- materialized_batch_id，可空；
- continuation_pending_slot_released，BOOLEAN NOT NULL；MANUAL_SCAN 固定true，CONTINUATION创建时false，且只可在MATERIALIZED/不可逆终态事务CAS为true；
- materialization_attempts、next_retry_at、deferred_window_date、materialization_deadline_at；
- next_wake_at、wake_claim_owner、wake_claim_token、wake_claim_epoch BIGINT NOT NULL DEFAULT 0、wake_lease_until、wake_attempts；nextWakeAt 是持久后台恢复索引，不是新的业务deadline：初建MATERIALIZATION_PENDING固定为createdAt，DEFERRED_CUTOFF固定D14:50，等待parent或可恢复失败时固定为`min(本次GuardUse内部businessNow+1s,最终materializationDeadlineAt)`，MATERIALIZED/不可逆终态必须为空。owner/token/lease三元组只允许在PENDING/DEFERRED时存在；claimEpoch从0只增且清claim/终结时也保留最后值，用于fence迟到worker；完成一次未终结处理时原子清除owner/token/lease并写下一nextWakeAt，终结时清nextWakeAt/owner/token/lease；
- source_outcome_at；令 D 为 source_effective_at 的 Asia/Shanghai 日期：本地时刻<14:47 固定 `min(source_effective_at+120s,D 14:48:45)`；位于 [14:47,14:50) 固定等于 source_effective_at（该段只允许 MANUAL_SCAN）；>=14:50 固定 +120s。CONTINUATION 黑窗内不建 source；初始 materialization_deadline_at=source_outcome_at，next_retry_at/deferred_window_date=null；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- INDEX(admission_status, next_wake_at, admission_id)，另保留诊断用 INDEX(admission_status, updated_at)。

DDL CHECK 仅固定本表可证明的关系：window_date=budget_bucket_date=source_effective_at 的 Asia/Shanghai 日期；FINAL_READY_CERTIFIED 时 source_type=MANUAL_SCAN、source_certified_at/source_certification_cutoff 均非空且 source_certified_at=source_effective_at；NON_EXECUTION_SOURCE 时二者均为空。materialized_advisory_use=SAME_DAY_RESEARCH 时必须是 FINAL_READY_CERTIFIED；RESEARCH_ONLY 允许保留真实认证事实。MATERIALIZATION_PENDING/DEFERRED_CUTOFF/所有失败终态的 materialized_advisory_use 必须为空，MATERIALIZED 必须非空。MATERIALIZED与七个不可逆终态必须nextWakeAt/owner/token/lease为空但允许保留非负wakeClaimEpoch/wakeAttempts；MATERIALIZATION_PENDING/DEFERRED_CUTOFF必须nextWakeAt非空，owner/token/lease三者同空同非空且非空时wakeClaimEpoch>0。MANUAL_SCAN必须`continuation_pending_slot_released=true`；CONTINUATION在MATERIALIZATION_PENDING/DEFERRED_CUTOFF时必须false，在MATERIALIZED或七个不可逆终态时必须true。materializer只以attestation/manual immutable事实校验确定性状态/hash，不得为缓存/非交易日预览伪造认证时间。FINAL_READY source 即使延期为 RESEARCH_ONLY，也永久保留 certificationKind/certifiedAt/cutoff；延期不得擦除真实认证事实。

`deferredWindowDate` 只能从 null 写为 D 一次，之后不可更新、清空或改日。只有 PENDING、无 batch、deferredWindowDate=null、处理时 lowerNow 的本地日期=D 且 lowerNow<D14:52，并满足以下之一，才具一次延期资格：(A) sourceEffectiveAt 位于 D[14:47,14:50)；(B) sourceEffectiveAt<D14:47 且 sourceOutcomeAt>=D14:47，即原窗口确实与黑窗相交。首次延期 CAS 原子写 DEFERRED_CUTOFF/AI_DEFERRED_CUTOFF、deferredWindowDate=D、nextRetryAt=nextWakeAt=D14:50、materializationDeadlineAt=D14:52，并清除当前wake claim；前三个窗口字段以后均不可推进。10:00 或 14:44:59 等在 14:47 前已到期的 source 无延期资格。

materializer、reconciler、linkage worker 与 UserSourceDueCoordinator 必须调用同一 `SourceMaterializationStateMachine`/GuardUse adapter；全部GET严格只读。状态机按下列顺序第一条命中即停止，所有窗口用 source.windowDate 的绝对 instant。定义 `parentInputReady`：CONTINUATION 因 source_payload 已冻结父四元组恒为 true；MANUAL_SCAN 仅在 linkage=RESOLVED 时为 true，成功查询但无父轮也必须是 RESOLVED+NO_ELIGIBLE_PARENT，PENDING/FAILED 永不视为 ready。Stage 0 为同一状态机冻结不会反序的 LockPlan 家族，所有可能写source终态、绑定或回收stage的分支都先锁预建 `MATERIALIZATION_STAGE_GUARD`。该guard状态为NO_STAGE时可跳过storage层；状态为SEALED/RECLAIM_PENDING时必须继续按序锁AI_STORAGE_QUOTA_GUARD与既有STORAGE_RESERVATION，缺行或身份不符固定503/CORRUPTED_MATERIALIZATION_STAGE，不能在取得admission后反向补锁。MANUAL 的 health/runtime/config/输入超限终态固定 `AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->[按guard状态可选storage/reservation]->MANUAL_ATTESTATION_GUARD->admission->linkage`；CONTINUATION 对应省略attestation/linkage并在storage后加入CONTINUATION_ADMISSION_GUARD。仍有物化可能且零锁读取已见parentInputReady时才生成或复用第15.3.1节SEALED stage；parentInput未ready固定在stage/owner前返回，RESOLVED后不可倒退。完整MANUAL bind分支使用`AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION->ACTOR_WORKLOAD_GUARD->ACTIVE_BATCH_SLOT->预算->MANUAL_ATTESTATION_GUARD->admission->linkage->thread/outcome guards->parent-marker-sequence guard->parent-marker guards->authority statement`，只锁AI guard并非锁定读取attestation/manual；CONTINUATION在storage后加CONTINUATION_ADMISSION_GUARD并省略attestation/linkage。linkage worker可提交source终态，故固定使用`AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->[按guard状态可选storage/reservation]->MANUAL_ATTESTATION_GUARD->admission->linkage->authority statement`。每个分支都是全局序列子序列，guard状态在持锁后唯一决定是否进入后续storage段，不存在后置锁升级。提交MATERIALIZED保留reservation、slot ACTIVE且stage/guard都BOUND；不可逆失败把slot ABORTED、storage未用额结算并将SEALED stage与guard一起置RECLAIM_PENDING；NO_STAGE终态持guard到source commit，从而阻止迟到stage写入。PENDING/DEFERRED若stage尚未创建则零reservation/slot，若竞态前已SEALED则保留同一stage/hash与storage reservation、但commit-visible ACTIVE/PROVISIONAL slot为零，下一次禁止重复写4MiB。可恢复数据库/authority异常整笔业务事务回滚。任一 PENDING/DEFERRED source 进入不可逆失败终态时，CONTINUATION 必须同事务仅一次释放 pending-source slot；若 MANUAL linkage 仍 PENDING，必须同一事务写 linkage=FAILED、errorCode=`SOURCE_TERMINAL_<source reason>`；reconciler 对 terminal source 不再 ensure/wake linkage：

1. 存在 committed batch：identity/admission 一致则只读返回；不一致 503 CORRUPTED_SOURCE_IDENTITY。status=MATERIALIZED 但无 committed batch 时 503 CORRUPTED_MATERIALIZATION，不投影/修复；
2. status 属于 NOT_APPLICABLE、FEATURE_DISABLED、BUDGET_EXHAUSTED、STORAGE_PRESSURE、TIME_AUTHORITY_UNAVAILABLE、MATERIALIZATION_INPUT_RESOURCE_LIMIT、MATERIALIZATION_FAILED：原样返回且零更新。只有 PENDING/DEFERRED 才继续；
3. health proof 无效：在创建storage/slot准备行前把PENDING/DEFERRED收口 TIME_AUTHORITY_UNAVAILABLE，并按上述通用规则同步失败 PENDING linkage；数据库不可决议返回 503；
4. 健康取时后先判断最终过期：DEFERRED 且 lowerNow>=materializationDeadlineAt；PENDING 非延期资格且 lowerNow>=sourceOutcomeAt；或 PENDING 有延期资格且 lowerNow>=D14:52，均写 MATERIALIZATION_FAILED/MATERIALIZATION_DEADLINE_MISSED，PENDING linkage 同事务 FAILED/LINKAGE_DEADLINE_EXPIRED，不写/推进延期字段；若为获得post-lock时间已建准备行则按不可逆真值表SETTLED+ABORTED。保守不确定带 `lower<deadline<=upper` 零更新并prepare-cancel；
5. 尚未过期时按固定能力优先级检查锁住的事实：attestation/source主键、hash或不可变identity不一致是CORRUPTED_MANUAL_ATTESTATION|CORRUPTED_SOURCE_IDENTITY/503且零状态猜测；identity一致但冻结schema已不受支持或合法候选为零 -> status=NOT_APPLICABLE,reason=SOURCE_SCHEMA_UNSUPPORTED|NO_ELIGIBLE_CANDIDATE；零锁canonical artifact>4194304字节 -> status=MATERIALIZATION_INPUT_RESOURCE_LIMIT,reason=MATERIALIZATION_INPUT_RESOURCE_LIMIT；AI_RUNTIME_GUARD enabled=false -> status=FEATURE_DISABLED,reason=AI_FEATURE_DISABLED；enabled=true 但 revision!=source.runtimeGuardRevision -> status=FEATURE_DISABLED,reason=FEATURE_REVISION_CHANGED；LLM 配置/凭据无合法 revision -> status=FEATURE_DISABLED,reason=LLM_CONFIG_UNAVAILABLE；AI_STORAGE_QUOTA_GUARD非NORMAL或无法原子预留627MiB owner -> status=STORAGE_PRESSURE,reason=STORAGE_HIGH_WATER；actor activeBatchCount已为4 -> status=BUDGET_EXHAUSTED,reason=ACTIVE_BATCH_LIMIT；source日batch预算已满 -> status=BUDGET_EXHAUSTED并写对应reason。首项命中即终结source与PENDING linkage、零batch/role；输入超限固定零stage/storage/slot/budget，其他分支与已有stage按第15.3.1节结算/reclaim。所有guard持到commit，不能被PUT、存储监控器或并发容量/预算穿透；
6. status=DEFERRED_CUTOFF：CLOSED_WINDOW 或 lowerNow<nextRetryAt 原样返回并prepare-cancel；AFTER_WINDOW、upperNow<materializationDeadlineAt 时 parentInputReady 则在同一事务消费 batch 预算并 materialize RESEARCH_ONLY，MANUAL linkage=PENDING则原样返回并prepare-cancel；
7. status=PENDING 且具一次延期资格：若 branch=BEFORE_WINDOW，明确不消费本步骤，继续第 8 步普通 materialization；CLOSED_WINDOW 原子写延期三元组、prepare-cancel并返回 DEFERRED；AFTER_WINDOW 且 upperNow<D14:52 时，MANUAL linkage=PENDING 原子写延期三元组、prepare-cancel并返回 DEFERRED，parentInputReady 则在同一 `PENDING_EXTENDED_MATERIALIZE` 事务写延期三元组、消费 batch 预算、写 materialized_advisory_use=RESEARCH_ONLY、唯一 batch/round/role/seal并直接变 MATERIALIZED，不要求第二次调用；
8. 普通 PENDING：upperNow<sourceOutcomeAt 且 BEFORE_WINDOW/AFTER_WINDOW 时，parentInputReady 才在同一事务消费 batch 预算并 materialize，MANUAL linkage=PENDING则原样返回并prepare-cancel。BEFORE_WINDOW 只有 certificationKind=FINAL_READY_CERTIFIED、tradeDate 与认证事实都匹配才写 SAME_DAY_RESEARCH，其他写 RESEARCH_ONLY；
9. 所有 materialization 必须由 GuardUse post-lock 时间证明 BEFORE_WINDOW/AFTER_WINDOW 且 upperNow<最终 materializationDeadlineAt。等号失败。source、batch、round 的 materialized advisory use 和 budgetBucketDate 必须在同一事务写同值且以后不可更新。

任一 CAS/唯一赢家事务 500ms 内未决返回 503，不返回 PENDING/臆造失败。14:50 第一次且唯一一次处理一个具延期资格、parentInputReady 的 PENDING source，必须在该调用中直接 MATERIALIZED/RESEARCH_ONLY；并发输家重读唯一 batch。sourceEffectiveAt、windowDate、sourceOutcomeAt、nextRetryAt（若有）与 materializationDeadlineAt 均冻结；只有 lowerNow>=最终 materializationDeadlineAt 才失败。D23:59:30 创建的 +120s source 可在 D+1 00:01:30 前继续一次，等号失败；这不是过期 source 的跨日复活。D+1 黑窗没有第二次延期资格。用户仍要研究已过期 source 必须显式新建 CONTINUATION。14:47:20 仅约束已存在 role 的 INITIAL；CACHE_PREVIEW/RESEARCH_PREVIEW 的 certificationKind=NON_EXECUTION_SOURCE、sourceCertifiedAt=null。

`UserSourceDueCoordinator` 是上述状态机的唯一持久到点唤醒者。它启动时立即恢复，运行时最大空闲扫描间隔250ms；候选查询只读取 `admissionStatus in (MATERIALIZATION_PENDING,DEFERRED_CUTOFF)`、`nextWakeAt<=TimeAuthorityMonitor当前持久guard high-water` 的有界前64行并按`nextWakeAt,admissionId`排序，该查询只是可能提前的唤醒提示，不授权任何状态变化。health正常时，每个候选随后必须进入注册 `SOURCE_WAKE_CLAIM` GuardUse；该 operation 的 LockPlan 只有两种且逐字固定：MANUAL_SCAN=`AI_RUNTIME_GUARD->ADMISSION`，CONTINUATION=`AI_RUNTIME_GUARD->CONTINUATION_ADMISSION_GUARD->ADMISSION`，均是全局序列的子序列，不锁MANUAL_ATTESTATION_GUARD、LINKAGE、storage、budget或batch事实。它使用 `ON_OR_AFTER(nextWakeAt)` 的lowerNow，只有claim为空或lease已由lowerNow证明过期才CAS写owner/token/epoch+1/`leaseUntil=businessNow+5s`；`lowerNow<nextWakeAt`（包括`lower<nextWakeAt<=upper`的不确定带）统一是REJECTED_WINDOW/零claim，coordinator静默等待下轮，不发业务PENDING/失败通知。claim提交后 worker 只凭opaque WakeClaimRef调用同一 SourceMaterializationStateMachine/linkage流程；所有业务窗口仍由后者重新取时，claim本身绝不授予materialization或网络。health=false但commit-visible authority high-water可读时，coordinator不建claim，直接调用第15.3步骤3的窄化capability adapter，以source version CAS收口TIME_AUTHORITY_UNAVAILABLE并同步linkage/pending slot/wake字段；高水位或业务库不可读则503并保留原事实等待恢复。这样坏时钟也不会让用户source永久PENDING。

`WakeClaimRef` 解封得到的 admissionId/owner/token/epoch/leaseUntil 必须进入 SourceMaterializationStateMachine 的锁后 subject hash：取得 admission 锁后逐字段等值才允许继续，并由同一 GuardUse 额外要求 `upperNow<wakeLeaseUntil`；不相等或已过期固定 SUBJECT_CHANGED/零source、linkage、batch mutation，旧 worker 不能在新 epoch 后推进。after-commit 与 reconciler 是明确的无claim调用模式，可在同一 admission 锁内直接重建subject；它们若先提交会清除/改变claim并使worker失败，worker若先持锁则这些调用只在其提交后重读唯一状态。GET没有调用该状态机的重载。不得存在可选地忽略一个已传WakeClaimRef的重载。

状态机每个提交分支必须同时结算wake事实：MATERIALIZED/不可逆终态清空nextWakeAt与claim；写DEFERRED固定nextWakeAt=D14:50；linkage仍PENDING或可恢复异常若选择提交可恢复状态，清claim并把nextWakeAt设为不晚于`min(businessNow+1s,最终deadline)`；数据库/authority异常导致业务回滚时保留原claim，5秒后可接管。双实例通过source version/claim token只有一个有效worker，重复唤醒仍由batch唯一键和状态机CAS兜底。浏览器关闭、没有GET、after-commit事件丢失或进程在D14:50前后重启，都必须由它把具延期资格source在D14:50后重新推进，并在D14:52 lower到线后不可逆失败；它不得遍历旧scheduled snapshot、创建manual result/source/candidate或为已终态source发起任何动作。

### 15.3.1 short_term_ai_materialization_stage

候选快照不在授时后的100ms事务运输。每个source创建事务必须同时预建 `short_term_ai_materialization_stage_guard`：admission_id PRIMARY KEY、deterministic_batch_id UNIQUE、stage_state=NO_STAGE|SEALED|BOUND|RECLAIM_PENDING|RECLAIMED、stage_id/storage_reservation_id/stage_hash 可空、version。NO_STAGE要求三引用全空，其他状态要求全非空；合法边只有NO_STAGE->SEALED->BOUND或NO_STAGE->SEALED->RECLAIM_PENDING->RECLAIMED。guard和source全有或全无，生产路径禁止删除。stage创建、source不可逆终态、bind与cleanup都先锁该行：终态先赢且状态NO_STAGE时持锁提交source，迟到stage事务只能重读终态并整体回滚；stage先赢时终态必看到SEALED并按全局后续storage段结算。这样不存在“终态提交后又落一个孤立627MiB owner”的窗口。

`short_term_ai_materialization_stage` 保存 stage_id、admission_id UNIQUE、确定性batch_id、source/manual-attestation/linkage identity hash与version、artifact_schema_version、artifact_hash、payload_byte_count、artifact_manifest_json、object_count、chunk_count、reclaimable_charged_bytes、reclaimable_rows、status=SEALED|BOUND|RECLAIM_PENDING|RECLAIMED、storage_reservation_id、created_at/bound_at/reclaimed_at、version。Stage 0 冻结 `MaterializationArtifactV1` 与 `StageContentRefV1(stageId,objectCode,objectType,byteCount,sha256)`；一个stage最多只有下列10个immutable canonical JCS object：恰一 `ADMISSION_MANIFEST`（按ruleRank/symbol列出最多40只的symbol、rank、candidateHash、selectionStatus与可空selectedRef）、恰一 `SELECTED_BATCH_SNAPSHOT`（就是将发送给四角色的完整AiCandidateBatchSnapshotV1，最多八只），以及每只已选候选恰一 `SELECTED_CANDIDATE/<symbol>`（完整AiCandidateSnapshotV1，最多八个）。`short_term_ai_materialization_stage_object` 以(stage_id,object_code)唯一保存type/ordinal/byte_count/hash/chunk_count/storage_charged_bytes；chunk表以(stage_id,object_code,sequence_no)唯一保存最多64KiB UTF-8完整分片、byte_count/content_hash/storage_charged_bytes。分片算法是canonical object UTF-8字节上的唯一算法：在不切断代码点的前提下，每个非final chunk必须取不超过65536字节的最长前缀，final chunk保存全部余下字节，禁止为增加行数任意细分；不超过10个非空object、aggregate<=4MiB时chunk总数上界为74（每个非final chunk至少65533字节，最多10个object各有一个final chunk）。artifact manifest只列上述对象引用并以objectType/ordinal排序，artifactHash是其canonical root；manifest字节加全部object字节总和精确`<=4194304`。SEALED 事务把 stage 主行的长 manifest 计费、所有 object 描述与 chunk 的 charged bytes/rows 逐行求和后冻结为 `reclaimableChargedBytes/reclaimableRows`；两数必须大于0、精确等于 MATERIALIZATION_STAGE bucket 本次 used 增量且之后不改。candidateBatchSnapshotRef只能指向SELECTED_BATCH_SNAPSHOT，round.candidateSnapshotRef只能指向对应SELECTED_CANDIDATE，任何provider prompt/request只能逐字解引用前者；第9–40名仅存在ADMISSION_MANIFEST和candidate admission元数据，禁止临时从root投影或进入模型上下文。SEALED要求所有对象/chunk/逐对象hash/root完整，BOUND要求boundAt非空且永久不可更新/删除，RECLAIMED清空artifact_manifest_json/payload长字段并删除object/chunk，仅保留不计入AI长内容配额的固定ID/hash/count/reclaimable计数 tombstone；stage与guard状态每次同事务同值。

完整编码、JCS/hash发生在零业务锁阶段；只有零锁 `parentInputReady=true` 才允许进入stage事务。stage提交事务固定按 `MATERIALIZATION_STAGE_GUARD->AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION(627MiB)->MANUAL_ATTESTATION_GUARD(仅MANUAL)->ADMISSION->LINKAGE(仅MANUAL)`，在任何guardUseStartNano之前重验source仍PENDING/DEFERRED、parentInput仍ready、全部immutable identity/version/hash与预构建对象一致，再原子预留owner、写stage/object/chunk并把guard NO_STAGE->SEALED。它不锁runtime、budget、slot或authority，也不取得任何时间授权；竞争终态使整个reservation/stage事务回滚。SEALED尚未BOUND不是batch/round/history/API可见事实。最终MATERIALIZE事务只锁后核验stage/source/attestation/linkage identity/hash，插入小型batch/round/candidate/role/seal引用与deadline，消费预算/slot，写source=MATERIALIZED并原子把stage/guard都改BOUND；authority ticket 后禁止复制、重编码、遍历或`INSERT ... SELECT`搬运4MiB正文。API只为BOUND stage解引用。`<100ms`只覆盖authority DML、小manifest/reference插入、CAS和commit；慢stage允许错过窗口，但必须重新取得新GuardUse，不能沿用stage前proof。

aggregate payload=4194305字节或以上固定在stage/storage owner创建前，以`AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->...->ADMISSION`锁住并证明guard=NO_STAGE后把source收口`MATERIALIZATION_INPUT_RESOURCE_LIMIT/MATERIALIZATION_INPUT_RESOURCE_LIMIT`，清wake/claim，MANUAL既有PENDING linkage同事务FAILED，CONTINUATION pending slot仅释放一次；零stage/storage reservation/active slot/usage reservation/batch/round/role且无自动重试，UI固定“AI 候选快照超过 4MiB 安全上限，本次未启动讨论”。source在绑定前进入其他不可逆终态时同事务把已有SEALED stage与guard置RECLAIM_PENDING；`StageCleanupReconciler`只处理该状态，一次事务的逐字锁序为 `MATERIALIZATION_STAGE_GUARD->AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION->MATERIALIZATION_STAGE bucket->stage->object/chunk`。它锁后重算object/chunk/stage长字段的charged bytes/rows并要求与SEALED时冻结的C/R及bucket/owner used增量完全相等，任一不等固定503/CORRUPTED_MATERIALIZATION_STAGE且零删除/零减账。合法唯一CAS先删除object/chunk并清空stage长字段，再以同一C/R把global accounted_used_bytes减C、owner与MATERIALIZATION_STAGE bucket的used bytes/rows各减C/R、reclaimed bytes/rows各加C/R；remaining/released、owner/bucket status与settledAt全部不变，最后stage/guard同事务写RECLAIMED及不可变tombstone。commit acknowledgement不明时必须先读guard/stage决议；已RECLAIMED的重放零二次删除、零二次减账。bind与cleanup竞争最终只能得到BOUND或terminal+RECLAIMED，绝无交叉状态。BOUND永久历史不可回收；该reconciler不联网、不建source/batch，不是自动扫描。

### 15.3.2 short_term_manual_ai_linkage

manual 与 AI 父链之间使用独立 linkage 行，禁止把 AI 查询塞回确定性事务：

- manual_result_id PRIMARY KEY（保存已校验的 deterministic business key，不建 manual FK）；admission_id UNIQUE NOT NULL，保存已校验的 source admission 业务键且创建后不可更新；source_decision_at（复制 manual.deterministicFinalizedAt，仅作审计）。linkage 不复制 deadline，唯一权威始终是 admission.materialization_deadline_at；
- linkage_status：PENDING、RESOLVED、FAILED；parent_resolution_at（linkage REPEATABLE_READ 快照中的数据库时间）；resolved_parent_rounds_json；last_attempt_at、attempt_count、error_code；resolved_at；version；
- resolved_parent_rounds_json 按 symbol ASC，每项固定 symbol、resolvedParentRoundId UUID/null、resolvedParentRoundEndAt 时间/null、resolutionBasis RAW_ACCEPTED_TERMINAL|OUTCOME_FREEZE|null、resolutionCode；resolutionCode 仅允许 LATEST_ELIGIBLE_PARENT、NO_ELIGIBLE_PARENT，选到父轮固定前者，无父轮固定后者；
- manual after-commit 与 reconciler 收到唤醒都先幂等 ensure admission；GET严格只读。只有读回的 MANUAL admission 仍为 MATERIALIZATION_PENDING/DEFERRED_CUTOFF 才以 admissionId ensure PENDING linkage，创建时已因 runtime/config 不匹配直接终态的 source 固定零 linkage。禁止 linkage 在 admission 缺失时独立存在。linkage worker 使用两个不会反序的注册分支，二者都先按 `AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD`；guard=NO_STAGE时跳过storage，guard=SEALED/RECLAIM_PENDING时继续`AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION`，然后统一`MANUAL_ATTESTATION_GUARD->admission->linkage->authority statement`，并以非锁定READ_MODEL核验attestation。若锁后事实已明确runtime/storage/identity终态，或当前健康proof的保守lower已到达第15.3步骤4按source状态/延期资格计算的最终失败线，则走不查询父表的`LINKAGE_TERMINAL_DECISION`，最后仍由authority DML复核并原子失败source/linkage，存在SEALED stage时同时转RECLAIM_PENDING并结算；否则走`LINKAGE_RESOLVE`，第一条业务SQL建立唯一REPEATABLE_READ snapshot，在authority DML之前按symbol查询该snapshot内parent marker、冻结完整resolvedParentRoundsJson与payloadHash，再把该hash纳入GuardSubjectSnapshot。随后唯一authority DML按最新post-lock时间只允许DEFER、DEADLINE_FAIL或RESOLVE三种注册mutation；任何DEFER先原子写D14:50/D14:52三元组且丢弃本次父查询结果，提交后用新guardUseId/新snapshot重进。RESOLVE则把刚才已冻结的payload与app_guard_use同事务提交；授时后绝不查询父表、生成新payload或改变mutation。只有NO_STAGE的非终态RESOLVE分支可跳过storage；任何source终态分支都必须按stage guard状态完成对应结算。只有按步骤4重算后`lowerNow>=最终失败线`才可写MATERIALIZATION_FAILED/LINKAGE_DEADLINE_EXPIRED。局部parent查询失败必须在authority前回滚本次查询，随后用独立有界审计mutation只更新PENDING linkage的lastAttemptAt/attemptCount/errorCode=PARENT_QUERY_FAILED；下一次若deadline已到，TERMINAL分支不再依赖父查询即可失败。RESOLVED commit后只唤醒materializer；materializer从头取得完整前置锁序，禁止linkage worker持admission/linkage反向申请actor/预算锁。datasource/linkage表或commit unknown时投影端503。任何异常不得传播到manual HTTP；
- 上一项的唤醒者集合还明确包含 `UserSourceDueCoordinator`：它读到缺失linkage的PENDING/DEFERRED MANUAL admission时必须幂等ensure，而不能等待GET或after-commit事件。由due coordinator触发的ensure/linkage调用携带同一WakeClaimRef，并在插入linkage前按第15.3节锁后等值校验；过期或旧epoch固定零linkage写。其他唤醒者使用明确的无claim模式。
- source admission/materializer 只消费 committed RESOLVED linkage。linkage 尚 PENDING 时只等待该用户事件的 worker，不创建 batch；成功快照没有父轮时也必须为每个 symbol 冻结 null+NO_ELIGIBLE_PARENT 后 RESOLVED。进入 14:47 黑窗时 source 截止投影仍优先变 DEFERRED_CUTOFF，linkage 后来完成也只能在固定 materializationDeadlineAt 前作为 RESEARCH_ONLY；过线 source 与 linkage 同事务失败，不跨日自动复活。

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
- runtime_guard_revision，等于 materialization 事务持锁核验的 source.runtime_guard_revision；
- llm_config_revision、provider、model、model_settings_json，字段白名单且不含 key；
- execution_status；
- advisory_use，等于 source.materialized_advisory_use；
- queue_admission；
- candidate_batch_snapshot_ref，必须是StageContentRefV1且只指向同一batch已BOUND stage的唯一SELECTED_BATCH_SNAPSHOT对象；该对象逐字等于四角色实际请求使用的AiCandidateBatchSnapshotV1，绝不指向含40只准入清单的artifact root；
- candidate_batch_hash；
- data_cutoff_at；
- materialized_at，由 MATERIALIZE adapter 在模块内部按 GuardUse.businessNow 冻结，四个 role deadline 均由它派生；
- budget_bucket_date，严格复制 source.budget_bucket_date/source.window_date；即使23:59 source在次日00:00物化也仍计入原 source 日，调用方不能改日；
- history_visibility_marker_no，materializer 主事务按全局序锁住 parent marker sequence guard 后读取 committed high-water，并持该 guard 到 batch/round/source 同事务提交；四角色历史工具只读取不大于该值的 marker；
- initial_dispatch_deadline、latest_egress_permit_at、role_content_deadline、freeze_publish_admission_deadline、effective_outcome_at，均由同一 materialized_at 与第 8.3 节公式派生并复制给四个 role；
- round_identity_manifest_json、round_identity_manifest_hash：非空数组，条数必须等于 batch 被选 round 数且至少 1；按 roundId ASC 冻结每个 roundId/threadId/symbol/roundCreatedAt/roundIdentityHash，随 batch/round 创建同事务写入，之后不可修改；freeze/fallback 即使运行表被锁也只从该快照/GuardSubjectSnapshot 取 marker 身份；
- started_at、completed_at；
- error_code、error_message；
- created_at、updated_at、version；
- UNIQUE(source_type, source_key)；
- UNIQUE(admission_id, batch_id)；
- INDEX(execution_status, effective_outcome_at)；
- INDEX(role_content_deadline, batch_id) 与 INDEX(effective_outcome_at, batch_id)，供BatchOutcomeDueCoordinator有界恢复。

### 15.6.1 short_term_ai_batch_content_quota

每个 batch 创建同事务预建一行：batch_id PRIMARY KEY、accepted_raw_bytes BIGINT NOT NULL DEFAULT 0、accepted_raw_rows BIGINT NOT NULL DEFAULT 0、accepted_opinion_rows BIGINT NOT NULL DEFAULT 0、tool_call_rows BIGINT NOT NULL DEFAULT 0、tool_execution_rows BIGINT NOT NULL DEFAULT 0、evidence_rows BIGINT NOT NULL DEFAULT 0、event_rows BIGINT NOT NULL DEFAULT 0、version；CHECK `0<=accepted_raw_bytes<=268435456 AND 0<=accepted_raw_rows<=16384 AND 0<=accepted_opinion_rows<=8192 AND 0<=tool_call_rows<=256 AND 0<=tool_execution_rows<=256 AND 0<=evidence_rows<=512 AND 0<=event_rows<=4096`。它是不依赖最终聚合的字节+行数硬账本，不向可能长锁的batch建FK。任何 MODEL_OUTPUT/opinion/tool/evidence/event 行事务都先锁本行，再按全局序锁 role/invocation/content ledger；四角色并发必须在本行串行完成对应counter CAS。batch删除不允许，quota随历史永久可审计。

同一 materialization 还按 `opinionId=UUIDv5(roleRunId|roundId|schemaVersion)` 为四个role与每个已选round预建 `short_term_ai_opinion_content_quota(opinion_id PRIMARY KEY,batch_id,role_run_id,round_id,accepted_item_rows,accepted_chunk_rows,version)`，CHECK `0<=accepted_item_rows<=2048 AND 0<=accepted_chunk_rows<=2048`，复合唯一约束固定归属。写sequenceNo=0的新(fieldCode,itemNo)时在锁住本行后同时把item与chunk各加1；sequenceNo>0必须已经存在同item前一连续chunk且只加chunk。任一CAS越界使超额chunk与contentCommitNo均零写。该表与最终`short_term_ai_role_opinion.opinion_id`共用同一确定性ID，最终意见缺失不删除quota或已接纳审计内容。

### 15.6.2 short_term_ai_storage_quota

AI 的逻辑存储必须在写入前被持久额度覆盖，`storageState=HIGH_WATER` 不是只挡新 batch 的提示开关：

- `short_term_ai_storage_quota_guard` 只有 `guard_key='AI_STORAGE'` 一行，保存 `ai_writable_capacity_bytes`、`accounted_used_bytes`、`reserved_remaining_bytes`、`write_state=NORMAL|HIGH_WATER`、`state_revision`、version，CHECK 三个字节值非负且 `accounted_used_bytes+reserved_remaining_bytes<=ai_writable_capacity_bytes`。capacity 是独立于实时“剩余磁盘”的AI专用逻辑硬配额：运维必须从数据库卷已分配容量中先扣除确定性数据增长、索引/WAL/临时放大与至少2GiB应急余量，再显式提供 `AI_STORAGE_PROVISIONED_BYTES`；`ai_writable_capacity_bytes` 必须小于等于该值。两者缺失、为0、关系不成立或发布证据不可读都令writeState=HIGH_WATER/AI关闭；不得把共享卷总量或 `pg_database_size` 伪装成可写空间。
- `short_term_ai_storage_reservation` 保存 reservation_id、owner_type=DISCUSSION_BATCH|PAID_OPERATION、owner_id、maximum_bytes、used_bytes、remaining_bytes、released_bytes、reclaimed_bytes、maximum_rows、used_rows、remaining_rows、released_rows、reclaimed_rows、status=RESERVED|SETTLED|EXHAUSTED、created_at/settled_at/version，UNIQUE(owner_type,owner_id)，maximum创建后不改且必须大于0。每个维度都满足`0<=used,remaining,released,reclaimed<=maximum`且`used+remaining+released+reclaimed=maximum`；只有remaining_bytes计入全局reserved，只有used_bytes计入全局accountedUsed，remaining_rows只在owner/bucket本地计数。released表示从未使用便归还的额度，reclaimed表示曾由唯一未BOUND stage消费、随后随其物理长内容删除而冲销的额度，二者不可互换。bucket三态CHECK互斥：RESERVED要求两种remaining都大于0且settledAt空；任一维度曾命中硬上限的写事务必须同时把另一维度尚未使用的remaining转为released，写EXHAUSTED且settledAt非空；主动提前结束且两个used都未命中maximum时把两种remaining全转released，写SETTLED且settledAt非空。后续合法STAGE_RECLAIM只减少used并等额增加reclaimed，不重开remaining、不改变SETTLED/EXHAUSTED及settledAt；EXHAUSTED表达“历史上曾命中上限”。其子表以(reservation_id,bucket_code)为主键保存同样的byte/row maximum/used/remaining/released/reclaimed/status/settledAt/version。owner各数值始终精确汇总bucket；owner状态为：任一bucket仍RESERVED则RESERVED/settledAt空，否则任一bucket EXHAUSTED则EXHAUSTED，否则SETTLED，后二者settledAt非空。owner结算与reclaim都按bucketCode升序，任一汇总不等全回滚。讨论batchId固定为namespace UUIDv5(`admissionId|DISCUSSION_BATCH|schemaVersion`)，每次重试使用同一值；在stage首写前以它一次预留627MiB、29,840行 charged capacity，bucket固定RAW_MODEL=272MiB/16,384行、OPINION_CONTENT=264MiB/8,192行、OPINION_MANIFEST=3MiB/32行、MATERIALIZATION_STAGE=5MiB/88行、REQUEST_SNAPSHOT=33MiB/16行、EVIDENCE_EVENT=47MiB/5,120行、FINALIZATION_BASIS=3MiB/8行；各bucket bytes/rows求和必须逐字等于owner maximum。业务payload上限仍分别是raw256MiB、opinion content256MiB、单个opinion manifest64KiB、stage aggregate4MiB、request32MiB、evidence/event原上限、basis512KiB；增量只覆盖行/索引/WAL的保守计费。逐类映射唯一：provider原始response/reasoning/tool-argument chunk只消费RAW_MODEL；解析后的opinion content/severe-risk claim chunk只消费OPINION_CONTENT；最多32条role-opinion标量+manifest行只消费OPINION_MANIFEST，每行`contentManifestJson+severeRisksJson` canonical UTF-8合计<=65536且charged>=payload+2048，正文仍只在chunk；stage主行/object描述/chunk/root manifest只消费MATERIALIZATION_STAGE，冻结分片上界下实际最多需1个stage+10个object+74个chunk=85行，88行额度另留3行保守余量而不放宽object/chunk数量合同；最多16个request snapshot只消费REQUEST_SNAPSHOT；tool/evidence/event只消费EVIDENCE_EVENT；四个sealed content manifest与唯一freeze basis只消费FINALIZATION_BASIS。每条raw/opinion/request chunk的`storageChargedBytes>=payloadBytes+1024`，tool/evidence/event/manifest至少`payloadBytes+2048`，freeze basis至少`payloadBytes+4096`，owner/bucket/global扣charged bytes，业务payload counter独立执行原上限。旧付费operation预留48MiB、3,075行，bucket固定REQUEST=5MiB/1行、RESPONSE=34MiB/1,024行、AUDIT_DETAIL=8MiB/2,048行、TERMINAL_CLOSER=1MiB/2行；两个请求JSON同属唯一operation行并合计消费REQUEST的一行，response chunk只消费RESPONSE，普通审计只消费AUDIT_DETAIL，终态/reconciliation只消费TERMINAL_CLOSER。每次写同事务CAS byte+row counter；超任一维度时超额行/字节零写、counter不增并以稳定RESOURCE_LIMIT终态收口，已接纳内容完整保留。
- charged-column 映射是 canonical DDL 的必填部分，不能只靠业务代码临时算数：stage主行保存`artifact_manifest_byte_count/artifact_manifest_storage_charged_bytes`，object行保存`descriptor_byte_count/storage_charged_bytes`，stage chunk保存`byte_count/storage_charged_bytes`；model invocation保存`request_snapshot_byte_count/request_storage_charged_bytes`；tool call保存`payload_byte_count/storage_charged_bytes`；tool execution保存`request_snapshot_byte_count/request_storage_charged_bytes`；role opinion保存`manifest_byte_count/manifest_storage_charged_bytes`；event保存`payload_byte_count/storage_charged_bytes`；evidence保存`excerpt_byte_count/storage_charged_bytes`；sealed manifest使用已定义的两列；outcome freeze保存`basis_byte_count/basis_storage_charged_bytes`；paid operation分开保存`request_payload_byte_count/request_storage_charged_bytes`与`response_manifest_byte_count/response_manifest_storage_charged_bytes`，paid response chunk保存`byte_count/storage_charged_bytes`，TERMINAL_CLOSER/AUDIT_DETAIL独立记录各有`payload_byte_count/storage_charged_bytes`。非空payload的byteCount必须等于严格UTF-8字节数，chunk/request最低charged=`byteCount+1024`，其他manifest/descriptor/event/evidence/audit最低`byteCount+2048`，freeze basis最低`byteCount+4096`；可空payload与两个计费列只允许按各实体CHECK同空或同为0。每次写行必须以该行实际charged值与bucket/owner/global及行计数同事务CAS；两库审计重建必须逐行求和精确等于bucket used，任一缺列、小于公式、重复映射或无bucket的大TEXT都使Stage 0/启动readiness失败。固定错误码/hash/ref的免计降级行必须把原长payload及上述计费列全置空/0，不得借免计保存任何正文。
- 预留事务先锁quota guard，只有write_state=NORMAL且`used+reserved+maximum<=capacity`才增加全局reserved并插reservation；否则source以STORAGE_PRESSURE/STORAGE_HIGH_WATER或paid入口503收口，零batch/handoff。普通长字段/chunk/证据/event/request snapshot写事务都从AI_STORAGE_QUOTA_GUARD->对应reservation开始，要求write_state=NORMAL、对应bucket两种remaining都足以接纳本次完整item及一行，再原子把byte/row remaining与全局reserved减少、used增加，并写业务行；全回滚或全提交。任一维度恰好用尽时按上一项同事务关闭bucket并释放另一维度余量。因总和不变，已预留内容不会越过逻辑capacity，但HIGH_WATER提交后连已handoff响应也只能以INCOMPLETE_RESOURCE_LIMIT/PAID_RESPONSE_RESOURCE_LIMIT收口，不能继续填库。唯一例外是为了消除PENDING所需且已在初始reservation中不可挪用保留的终态额度：freeze basis只含ID/hash/枚举引用、canonical UTF-8硬上限512KiB并只消费FINALIZATION_BASIS；paid FAILED_AFTER_SEND/OUTCOME_UNKNOWN/slot释放使用TERMINAL_CLOSER中首个<=64KiB记录，UNKNOWN_HELD后显式reconciliation最多再用第二个<=64KiB记录。EVIDENCE_EVENT/AUDIT_DETAIL及任何正文都不能挤占这两个closer bucket。两类closer可在HIGH_WATER下按同一storage锁序、remaining与发布时2GiB应急物理余量证明提交，但不得携带模型正文、网页正文或业务成功响应；保留额度/应急余量被外部破坏时仍为503而不是写半条终态。固定状态列/计数器本身不按长内容计费。
- 存储监控器按AI_RUNTIME_GUARD->AI_STORAGE_QUOTA_GUARD取锁。H2从文件所在FileStore取usableSpace；PostgreSQL不用标准SQL猜宿主剩余卷，而是依赖发布时已验证的 `AI_STORAGE_PROVISIONED_BYTES` 硬上限与数据库卷告警探针。任一profile探针失效、配置证据失效，或“可用/已分配空间<=确定性应急余量+单个最大chunk+未兑现物理预留”时必须提交HIGH_WATER并同步runtime guard.storageState；恢复到NORMAL必须有更高stateRevision和同一保守证明。content append持storage guard短锁，因此状态切换前最多已有的一个完整64KiB事务提交，阈值已预留该空间。
- 可重试且仍有出边、又必须提交状态的materialization分支（CLOSED_WINDOW写DEFERRED_CUTOFF、已锁定linkage/parent仍未就绪且要保存稳定attempt）不得留下已提交SETTLED reservation：若本事务新建且used=0，必须在仍持quota/owner/bucket锁时先把全局reserved减回maximum，再按已持有的bucketCode ASC锁删除本事务尚未公开的子行、最后删owner行，随后才提交source非终态；若无法证明“本事务新建且used=0”则整笔回滚/503。statement timeout、authority BUSY、数据库可恢复异常不提交attempt，整笔回滚即可。prepare-cancel不是历史删除API，Repository只向MaterializationAdapter暴露该包私有语句。不可逆source终态才可保留SETTLED reservation审计。CONTENT_SNAPSHOT/SYSTEM_FALLBACK/TIME_AUTHORITY_UNAVAILABLE freeze按storage->owner reservation->actor/outcome/marker子序列、paid已知终态按storage->paid->budget->operation子序列，把未用remaining从全局reserved释放，并依互斥真值表把owner/bucket写成SETTLED或EXHAUSTED；BOUND stage、batch正文和其他已物化历史的实际used永久计入global used，唯一例外是未BOUND stage按第15.3.1节物理删除长内容后以STAGE_RECLAIM将其used精确迁移到reclaimed并同量减global used。OUTCOME_UNKNOWN若仍可能有已发送响应则保守保留未用额度，只有受身份保护的运维 reconciliation 能在确认无后续写后结算。已物化历史不自动删除，生产Repository无已提交历史delete。

上文“remaining计入全局reserved”只指remaining_bytes进入`storage_quota_guard.reserved_remaining_bytes`；remaining_rows只是owner/bucket本地硬计数，不存在全局row reservation。所有global used/reserved增减量均为charged bytes，行CAS只更新owner/bucket与对应业务quota行。

Stage 0 必须冻结三类字节的“计费列”清单，ArchUnit/Repository测试禁止任何AI大TEXT/BLOB绕过storage reservation；小固定枚举/时间/ID可免计但不能借此复制模型正文。这个全局额度保护的是确定性数据库可写空间，和单invocation/batch运输上限同时生效。

### 15.7 short_term_ai_discussion_round

每只候选在 batch 中一个 round：

- round_id；
- batch_id FK；
- thread_id FK；
- symbol；
- parent_round_id，可空；
- parent_round_end_at、parent_resolution_basis、parent_resolution_code，三者与 parent_round_id 一起创建后不可更新；resolutionCode 只允许 LATEST_ELIGIBLE_PARENT、NO_ELIGIBLE_PARENT、EXPLICIT_PARENT。MANUAL_SCAN 选到父轮固定 LATEST_ELIGIBLE_PARENT，CONTINUATION 固定 EXPLICIT_PARENT；这两类有父轮时 endAt/basis 必填。无父轮只允许 MANUAL_SCAN，parentRoundId/endAt/basis 均为空且 resolutionCode=NO_ELIGIBLE_PARENT；
- phase：MANUAL、CONTINUATION；
- execution_status；
- advisory_state；
- advisory_use，等于 batch/source.materialized_advisory_use；
- candidate_snapshot_ref，必须是StageContentRefV1且只指向同一已BOUND stage中本symbol的SELECTED_CANDIDATE对象；
- candidate_snapshot_hash；
- candidate_material_hash；
- round_identity_hash；
- data_cutoff_at；
- effective_outcome_at；execution_status 只表示 raw 状态，effective 结论只存在 outcome freeze；
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

`candidate_snapshot_ref/candidate_snapshot_hash/candidate_material_hash/data_cutoff_at/strategy版本引用/thread_id/symbol/created_at` 全部在round创建后不可更新，生产Repository无对应setter/update/delete。`round_identity_hash` 对schemaVersion、roundId、batchId、threadId、symbol、roundCreatedAt、上述候选两hash/dataCutoff/strategy版本做Stage 0 canonical JCS/SHA-256；marker必须复制该值。服务在插入时只以第 12.1 节 committed parent eligibility marker 验证父轮已终止、父子 symbol/thread 相同、父 createdAt 早于子，并保存已冻结 parentRoundEndAt/resolutionBasis/resolutionCode；raw 状态本身不能代替 marker。DDL CHECK 固定上述有父/无父两组 nullability；四个父字段和全部候选身份字段创建后不可更新，OpenAPI/History/RoundDetail 直接读取 round，不回查 mutable linkage。

### 15.7.1 short_term_ai_parent_eligibility

父资格是一次写入的 commit-visible marker：

- marker 是与 outcome freeze 同类的独立只增事实表，故意不向 round/thread/role 建 FK；字段包含 round_id PRIMARY KEY、marker_no BIGINT UNIQUE NOT NULL、batch_id、thread_id、symbol、round_created_at、round_identity_hash、eligible_at、basis、basis_ref_id、outcome_basis_hash 可空、freeze_revision 可空、created_at；RAW marker 的 outcome_basis_hash/freeze_revision 必须为空，OUTCOME_FREEZE 必须非空且 `freeze_revision=1`；
- basis 只允许 RAW_ACCEPTED_TERMINAL、OUTCOME_FREEZE；RAW basis_ref_id=role/content terminal aggregate identity，FREEZE basis_ref_id=outcome freeze ID；
- eligible_at/thread_id/symbol/round_created_at NOT NULL，CHECK eligible_at>=round_created_at；生产 Repository 无 update/delete。写入前由 RAW_TERMINAL/OUTCOME_FREEZE GuardUse 从 immutable manifest 验证 batch/round/thread/symbol/identity hash 并冻结，materializer/continuation 再以 marker 自身值校验父子，不回锁原 round；
- `short_term_ai_parent_marker_sequence` 是单行 last_committed_marker_no guard。raw round聚合或freeze插marker时先按全局锁序锁该guard与预建parent-marker guard；单marker分配+1，freeze的N个缺失marker按roundId ASC预留连续N个号并一次把high-water增加N，已有合法marker不占新号。sequence guard持有到全部marker与freeze commit，因此不存在较小markerNo迟提交。materializer 冻结 history visibility 时也必须在其完整业务事务中、按全局序到达该 guard 后读取 `last_committed_marker_no` 并一直持有到新 batch/round/guard/source 提交；禁止在主事务前用独立短事务读 high-water，否则读后迟提交的较小 marker 会落入同一个 markerNo 边界却不在四角色共同快照中。raw round聚合终态只有其每个受承认role terminalContentCommitNo均在已接纳范围，才在同一事务写completedAt并插RAW marker；否则freeze插OUTCOME marker。两个路径以round_id唯一键竞争，第一个committed marker永久获胜：遇既有RAW只核验共同round identity/RAW basisRef/eligibleAt/markerNo并保留；遇既有OUTCOME必须核验basisRefId、freezeRevision=1、outcomeBasisHash、eligibleAt、markerNo和完整round identity。任一身份不符整笔回滚503；
- linkage 在一次只读 REPEATABLE_READ 事务中由第一条 SELECT 建立 MVCC snapshot；H2 单实例同隔离测试。不得先取时间、commit 后再开新 snapshot。timeout<=50ms；失败按第 15.3.2 节区分局部查询失败与 store 不可达；
- 后续 raw closer、freeze、恢复和历史投影不得更新 eligible_at/basis。这样父轮结束时间不会因后写 freeze 反向改变。

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
- valuation_query_taint、valuation_output_taint BOOLEAN NOT NULL DEFAULT false，均只允许false->true；任一所属invocation原生tool argument命中第7.5节策略时由分类/LogicalToolAdmission事务持role锁置query=true，任一CONTENT/REASONING/结构化正文命中置output=true，所有opinion validator必须锁后读取这两个role-wide事实；
- started_at、completed_at；
- error_code、error_message；
- created_at、updated_at、version；
- UNIQUE(batch_id, role_code)；
- UNIQUE(batch_id, role_run_id)；
- INDEX(status, lease_until)。

role_run 的可执行 DDL CHECK 只引用本行：`initial_invocation_id/initial_dispatched_at/session_deadline/started_at` 必须四者全空或全非空；`initial_dispatch_deadline <= latest_egress_permit_at < role_content_deadline < freeze_publish_admission_deadline < effective_outcome_at`，四字段非空时还要求 `initial_dispatched_at < session_deadline AND session_deadline<=role_content_deadline`；并固定 `(recovery_mode='EXECUTION') OR (recovery_mode='UNKNOWN_FINALIZATION_ONLY' AND workflow_step IN ('UNKNOWN_FINALIZATION','DONE'))`。INITIAL invocation 的 PREPARED -> DISPATCHED 与 role 四字段在同一事务 CAS，sessionDeadline 按第 8.3 节唯一公式写入；跨表一致性由服务与 H2/PostgreSQL 集成测试保证，不编写普通 CHECK 无法表达的跨表子查询。deadline coordinator 以 initial_dispatched_at 是否为空选择 initialDispatchDeadline 或 sessionDeadline/roleContentDeadline。

### 15.9 short_term_ai_model_invocation

每个物理供应商请求一行：

- invocation_id；
- role_run_id FK；
- invocation_no；
- call_kind：INITIAL、TOOL_CONTINUATION、OUTPUT_CONTINUATION；
- tool_round_no，NOT NULL DEFAULT 0；
- output_segment_no，NOT NULL DEFAULT 0；
- delivery_status：PREPARED、DISPATCHED、RESPONSE_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND、OUTCOME_UNKNOWN；合法边固定`PREPARED->DISPATCHED|FAILED_BEFORE_SEND`、`DISPATCHED->RESPONSE_RECEIVED|FAILED_BEFORE_SEND|OUTCOME_UNKNOWN`、`RESPONSE_RECEIVED->PERSISTED|FAILED_AFTER_SEND`，四个终态无出边；
- transport_request_snapshot_json TEXT，保存无密钥、已规范化且实际发送的完整请求；
- request_hash，等于 transport_request_snapshot_json canonical UTF-8 的 SHA-256；
- provider_idempotency_key，可空；
- provider_request_id，可空；
- finish_reason；
- input_tokens、output_tokens、reasoning_tokens、response_bytes（供应商最终审计值）、accepted_response_bytes BIGINT NOT NULL DEFAULT 0、accepted_chunk_count BIGINT NOT NULL DEFAULT 0、discarded_resource_limit_bytes BIGINT NOT NULL DEFAULT 0；CHECK `0<=accepted_response_bytes<=33554432 AND 0<=accepted_chunk_count<=1024`；
- dispatched_at、transport_handoff_at、handoff_guard_use_id、response_received_at、completed_at；
- completed_after_content_deadline，仅作供应商/取消审计，不使任何返回正文绕过 content ledger；
- error_code、error_message；
- next_chunk_sequence；
- created_at、updated_at、version；
- UNIQUE(role_run_id, invocation_no)；
- UNIQUE(role_run_id, call_kind, tool_round_no, output_segment_no)；
- UNIQUE(role_run_id, invocation_id)；
- transport_request_snapshot_json/request_hash 在 PREPARED 后不可修改；invocation_id 主键本身提供全局唯一性。Gate 锁后必须重算 hash 并与 TransportCommand 引用值一致，任何漂移零 handoff/零 transport。
- FAILED_AFTER_SEND要求transport_handoff_at/response_received_at/completed_at/error_code均非空，errorCode只允许MODEL_RESPONSE_RESOURCE_LIMIT、MODEL_RESPONSE_PERSISTENCE_FAILED、PROVIDER_REDIRECT_REJECTED或Stage0扩展的同义已知失败码；OUTCOME_UNKNOWN不得用于已知资源/解析失败。

### 15.10 short_term_ai_tool_call

每个模型原生 tool_call 先形成一条逻辑调用，预算与多个物理 provider 请求不能混为一行：

- tool_call_row_id、role_run_id、invocation_id、tool_call_id；
- tool_name、symbol、arguments_hash、normalized_arguments_json；symbol从已校验参数冻结且必须属于batch；
- status：RECEIVED、REJECTED、ACCEPTED、EXECUTING、COMPLETED、PARTIAL、FAILED、OUTCOME_UNKNOWN；
- execution_plan_json、plan_stage：NONE、DISCOVERY_FROZEN、FINAL_FROZEN，plan_revision、target_set_hash、planned_execution_count；
- result_content_ref、validation_code、error_code、error_message；valuation_policy_revision、valuation_taint BOOLEAN NOT NULL DEFAULT false；命中VALUATION_QUERY_FORBIDDEN时同一RECEIVED->REJECTED事务把taint=true，之后不可清除；
- payload_byte_count、storage_charged_bytes；对normalized_arguments_json、execution_plan_json与result_content_ref的canonical UTF-8字节合计计费，硬上陕65536字节且`charged>=byteCount+2048`，任一阶段更新plan都必须与EVIDENCE_EVENT bucket的byte CAS同事务；超限不substring，以TOOL_INPUT_RESOURCE_LIMIT已知失败收口且零新物理execution；
- created_at、completed_at、version；
- UNIQUE(role_run_id, tool_call_id)；
- UNIQUE(role_run_id, tool_call_row_id, tool_call_id)；
- 复合 FK(role_run_id, invocation_id) -> invocation(role_run_id, invocation_id)。

TOOL_CALL reservation 以 tool_call_row_id 唯一。预算可用时，扣除 evidence/history call 配额并与 RECEIVED -> ACCEPTED/REJECTED CAS 同事务；非法参数也消费一次 logical call，防止模型用无效参数绕过轮数。预算不足时，不更新任何 budget counter，但在同一事务插入 status=DENIED、零额度 reservation，并写 toolCall=REJECTED、errorCode=BUDGET_EXHAUSTED，避免 RECEIVED 永久重试。

DDL CHECK要求valuation_taint=true时`status='REJECTED' AND validation_code='VALUATION_QUERY_FORBIDDEN' AND result_content_ref IS NULL`。任一toolCall命中时同事务把role.valuationQueryTaint单调置true；Opinion写入按roleRunId锁定全部toolCall并OR query taint，同时锁role读取valuationQueryTaint/valuationOutputTaint，任一query命中都使该role全部symbol不可合格，不能只检查目标symbol、最后一次调用或依赖内存标志。工具预算已经耗尽时若query仍命中策略，toolCall保留validationCode=VALUATION_QUERY_FORBIDDEN/taint=true，对应零额reservation保留denialReason=BUDGET_EXHAUSTED；validation与预算审计分列，不能二选一。

execution plan 分两段不可变推进。ACCEPTED -> EXECUTING 先冻结 DISCOVERY_FROZEN：请求参数在进入任何 provider 前已足以从服务器 registry 决定的 direct adapter 目标，以及至多一个 Tavily discovery，均使用固定 namespace UUIDv5(`toolCallRowId|provider|ordinal|requestHash`) 派生 executionId；plan 同时保存哪些第一阶段权威 direct execution 是后续目标的 parent。所有 parent 均到物理终态后必须确定性封闭第二阶段：PERSISTED parent 按 adapter 结构化 schema/registry 贡献规范化目标；FAILED_BEFORE_SEND parent 贡献空集合；任一 TOOL_OUTCOME_UNKNOWN 则不派生新 execution，并按 logical OUTCOME_UNKNOWN 真值表立即终结。将所有 PERSISTED parent 的目标去重后按 target hash 排序，计算 targetSetHash；零 parent、零成功 parent、或成功 parent 解析为零目标时也必须以唯一 CAS 冻结空集合。CAS 一并追加稳定 direct executionId、固定 plannedExecutionCount 并把 planStage 改为 FINAL_FROZEN；计划提交前不发任何第二阶段 direct 请求。Tavily 返回 URL 永远只展示/引用，绝不产生 direct execution、HEAD/GET 或重定向探测。namespace 常量和 name 的 UTF-8 拼接格式在 Stage 0 schema vectors 冻结。崩溃后从相同 parent 终态/result hash 重建相同 targetSetHash/ID；plannedExecutionCount 只随 FINAL_FROZEN 的版本化 CAS 一次确定，不能由 worker 临时 append。

logical tool call 的有序穷尽真值表：REJECTED 为验证/预算终态；已持久本地 history/SOURCE_UNAVAILABLE 结果且 plannedExecutionCount=0 为 COMPLETED；任一 physical execution 为 TOOL_OUTCOME_UNKNOWN 时为 OUTCOME_UNKNOWN；planStage=FINAL_FROZEN 且 plannedExecutionCount=0 为 COMPLETED；全部已冻结 planned execution=PERSISTED 为 COMPLETED；至少一个 PERSISTED 且其余全为 FAILED_BEFORE_SEND/FAILED_AFTER_SEND 时为 PARTIAL；没有 PERSISTED 且全部为这两种已知失败时为 FAILED；其他含 PREPARED/DISPATCHED/RESULT_RECEIVED，或仍有第一阶段 parent 非终态的组合为 EXECUTING。DISCOVERY_FROZEN 且所有 parent 已终态不是可停留状态，reconciler 必须执行上一段 FINAL_FROZEN CAS；终态不可回到执行态，OUTCOME_UNKNOWN 不自动重跑整组。

### 15.11 short_term_ai_tool_execution

每个物理外部工具请求一行：

- tool_execution_id、tool_call_row_id、role_run_id、invocation_id、tool_call_id、execution_no；同一原生 tool_call 可能依次产生 Tavily discovery 和多个受限 direct fetch，每个物理请求各占一行；
- tool_name、provider、transport_request_snapshot_json TEXT、request_hash；snapshot 保存 registry 已规范化的查询或 direct target descriptor，不保存密钥、任意 endpoint 或未验证 URL；canonical UTF-8最多64KiB并消费batch EVIDENCE_EVENT bucket，超限在handoff前稳定TOOL_INPUT_RESOURCE_LIMIT；request_hash 等于完整 snapshot canonical UTF-8 的 SHA-256；
- delivery_status：PREPARED、DISPATCHED、RESULT_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND、TOOL_OUTCOME_UNKNOWN；合法边穷尽固定为`PREPARED->DISPATCHED|FAILED_BEFORE_SEND`、`DISPATCHED->RESULT_RECEIVED|FAILED_BEFORE_SEND|TOOL_OUTCOME_UNKNOWN`、`RESULT_RECEIVED->PERSISTED|FAILED_AFTER_SEND`，四个终态无出边。合法committed PREPARED必定无usage reservation；恢复时窗口/feature/config/预算允许才原子插RESERVED reservation并转DISPATCHED，精确到/晚于latestEgressPermitAt或能力已关闭则插唯一零额DENIED reservation、写对应denialReason并原子转FAILED_BEFORE_SEND，零外发；PREPARED已有reservation仍是CORRUPTED_TOOL_PREPARE；
- provider_request_id、tavily_request_id、credits；
- dispatched_at、transport_handoff_at、handoff_guard_use_id、result_received_at、completed_at；
- error_code、error_message、version；
- UNIQUE(role_run_id, tool_call_id, execution_no)、UNIQUE(tool_execution_id, request_hash)；
- 复合 FK(role_run_id, invocation_id) -> invocation(role_run_id, invocation_id)；
- 复合 FK(role_run_id, tool_call_row_id, tool_call_id) -> tool_call(role_run_id, tool_call_row_id, tool_call_id)。

FAILED_AFTER_SEND要求transport_handoff_at/result_received_at/completed_at/error_code均非空且无出边，errorCode只允许TOOL_RESULT_RESOURCE_LIMIT、TOOL_RESULT_PERSISTENCE_FAILED、PROVIDER_REDIRECT_REJECTED或Stage0扩展的同义已知失败码。

toolCallId 来自模型时先做长度/格式校验并与 roleRunId 组合；模型缺失或重复 ID 时服务器生成稳定 ID 并记录原值。transportRequestSnapshotJson/requestHash 在 PREPARED 后不可更新，Gate 锁后重算并校验；DISPATCHED 后结果未知不得自动重发。

### 15.12 short_term_ai_model_output_chunk

- chunk_id；
- invocation_id FK；
- sequence_no；
- content_type：CONTENT、REASONING、TOOL_ARGUMENT；
- content_text，只是存储分片而非业务截断；stream adapter 对连续属于同一`contentType/nativeToolCallStreamKey-or-NONE`的任意小 provider frame 做UTF-8增量解码并合并缓冲，达到边界时按“不切断代码点且不超过65536字节的最长前缀”落chunk，只在stream key切换、响应终结或安全失败时落不足上限的final chunk；不得把每个小frame直接变一行。按sequence重建必须与已接受原字节一致；provider非法UTF-8固定已知解析失败，绝不替换字符后继续。
- native_tool_call_ordinal、native_tool_call_stream_key，可空；TOOL_ARGUMENT要求二者非空且ordinal>=0，CONTENT/REASONING要求均空。stream key按第7.5节首次provider frame固定，同一invocation内ordinal唯一；交错tool-call argument以该键各自重建，禁止仅按全局sequence拼接；
- public_visibility：PENDING_POLICY、PUBLIC、INTERNAL_POLICY_AUDIT；valuation_policy_revision、valuation_concept_codes_json、policy_taint_scope=NONE|ROLE_QUERY|ROLE_OUTPUT、policy_tool_call_row_id；只允许PENDING_POLICY->PUBLIC|INTERNAL_POLICY_AUDIT一次，正文/hash/byteCount/native framing不可更新。PUBLIC要求scope=NONE；ROLE_QUERY要求INTERNAL、contentType=TOOL_ARGUMENT且最终形成logical row时toolCallRowId非空；ROLE_OUTPUT要求INTERNAL且toolCallRowId为空；PENDING尚未形成最终scope。PUBLIC才可返回content_text；INTERNAL只返回hash/byteCount/withheld reason；角色终态与content seal都要求该role的PENDING_POLICY计数为0；
- received_at；
- content_commit_no，来自该 role 的 content ledger；
- byte_count、storage_charged_bytes、content_hash；CHECK `0<=byte_count<=65536 AND storage_charged_bytes>=byte_count+1024`；
- UNIQUE(invocation_id, sequence_no)；
- INDEX(public_visibility, invocation_id, sequence_no)。

received_at 由 CONTENT_APPEND adapter 按全局子序列持有 batch-content-quota、role、invocation、content-ledger 后执行 authority statement，以模块内部 GuardUse.upperNow 写入，不是回调自报时间，业务调用方拿不到upperNow。MODEL_OUTPUT chunk插入、contentCommitNo分配、invocation的byte/row counter与batch的byte/row counter、storage charged byte/row CAS必须同一事务；只有全部`current+next<=limit`才提交。单invocation最多1024 raw chunk，batch最多16384；任一级超限均零chunk/零commitNo/零counter增量，随后以INCOMPLETE_RESOURCE_LIMIT收口并取消继续读取provider stream。估值scanner对visibility的单向CAS同样携带claim/content fencing，按ROLE_RUN->INVOCATION->chunk稳定序取得行；ROLE_QUERY置role.valuationQueryTaint=true并在logical row形成后回填toolCallRowId，ROLE_OUTPUT置role.valuationOutputTaint=true。正常stream sink在读取下一provider frame前完成上一chunk的可判定分类。

`PolicyClassificationReconciler`只恢复已有invocation中崩溃留下的PENDING_POLICY，启动时运行且最大空闲扫描间隔1秒，每轮按索引读取前64个`invocationId,sequenceNo`。它不取得执行/egress资格，而是在短事务按ROLE_RUN->INVOCATION->chunk稳定序重读immutable contentHash/sequence、相邻carry和invocation stream是否已终结，运行同一版本化纯分类器并CAS `PENDING_POLICY->PUBLIC|INTERNAL_POLICY_AUDIT`；命中时同事务单调置role taint。非终结stream只分类后续字节已足以证明安全的chunk，尾carry保留PENDING；终结stream将无法证明安全或序列损坏的尾段保守INTERNAL并报警。两个实例从相同字节必算出相同结果，CAS输家只重读，不需要也不能创建第二套业务claim；freeze已存在时仍只允许上述visibility/role-taint审计CAS，零opinion/role终态/content/freeze mutation。它绝不发网络、创建source/batch/invocation或改变正文。OPINION_FINALIZATION、角色任何终态与content seal前都要求该role零PENDING；到截止仍不能完成分类时不伪造正常意见/终态，outcome按未封存事实进入唯一fallback，reconciler以后只完成审计分类且不得改变freeze。详情按invocation/sequence重建所有PUBLIC输出，INTERNAL返回等位withheld metadata，PENDING返回202且不泄露正文；不复制截断rawResponseText，截止后正文无读取旁路。

### 15.13 short_term_ai_role_opinion

每个 role session 对每个 round 一条：

- opinion_id；
- batch_id；
- role_run_id；
- round_id；
- symbol；
- stance、confidence；
- content_manifest_json，只含字段、item 数、byteCount、hash 与 content URL，不含长正文；
- citation_ids_json、severe_risks_json；后者只是有界manifest，每项固定ordinal、riskCode、severity、evidenceIds、claimContentRef、claimByteCount、claimHash，不复制claim正文。content_manifest_json与severe_risks_json的canonical UTF-8合计硬上限65536字节，整条opinion标量/manifest行消费OPINION_MANIFEST bucket一行且charged>=该合计+2048；正文/claim仍完整进入OPINION_CONTENT chunk，不受这个manifest上限截断；
- data_cutoff_at；
- evidence_cutoff_at；无联网工具时等于候选 data_cutoff_at，有工具时等于该 opinion 实际引用证据的最大 admission_lower_bound_at；它只表达这些引用被保守认定已公开的最晚时点，不能用upper或provider抓取完成时间替代；
- complete、validated；
- qualified_advisory_use，可空，仅允许 SAME_DAY_RESEARCH、RESEARCH_ONLY；qualification_reason NOT NULL，只允许 QUALIFIED、VALUATION_RAW_OUTPUT_REJECTED、VALUATION_QUERY_FORBIDDEN、INSUFFICIENT、STRUCTURE_INCOMPLETE、VALIDATION_FAILED、ADVISORY_USE_INCOMPATIBLE；两者均由服务器验证器写入，模型不能提供；
- valuation_policy_revision、valuation_policy_result：CLEAR、TAINTED_QUERY、TAINTED_RAW_OUTPUT；所属role任一VALUATION_QUERY_FORBIDDEN使该role全部symbol固定至少TAINTED_QUERY，任一raw/结构化正文命中使全部symbol固定TAINTED_RAW_OUTPUT；优先级为TAINTED_RAW_OUTPUT、TAINTED_QUERY、CLEAR，非CLEAR都不可回退；
- opinion_hash，对除数据库审计时间外的 canonical opinion manifest、stance/confidence、citation/risk ID、complete/validated、qualifiedAdvisoryUse/qualificationReason、valuation policy result 和 data/evidence cutoff 做 JCS/SHA-256；
- content_commit_no，来自该 role 的 content ledger；
- created_at；
- UNIQUE(role_run_id, round_id)；
- 复合 FK(batch_id, role_run_id) -> role_run(batch_id, role_run_id)；
- 复合 FK(batch_id, round_id, symbol) -> round(batch_id, round_id, symbol)。

服务器派生qualification使用唯一首项命中顺序：role raw/结构化输出 taint -> `TAINTED_RAW_OUTPUT/null/VALUATION_RAW_OUTPUT_REJECTED`；role任一query taint -> `TAINTED_QUERY/null/VALUATION_QUERY_FORBIDDEN`；stance=INSUFFICIENT -> `CLEAR/null/INSUFFICIENT`且confidence=0；complete=false -> `CLEAR/null/STRUCTURE_INCOMPLETE`；validated=false -> `CLEAR/null/VALIDATION_FAILED`；当前batch advisory use不相容 -> `CLEAR/null/ADVISORY_USE_INCOMPATIBLE`；否则 `CLEAR/派生use/QUALIFIED`。DDL CHECK 固定：qualified_advisory_use 非空当且仅当qualification_reason=QUALIFIED，并要求 `complete=true AND validated=true AND stance<>'INSUFFICIENT' AND valuation_policy_result='CLEAR'`；其余reason都要求qualified use为空，两种valuation reason与valuationPolicyResult逐一对应。跨表的“qualifiedAdvisoryUse 与 batch.materializedAdvisoryUse 相容、SAME_DAY 引用的每条 evidence 均 pointInTimeEligible/SAME_DAY_RESEARCH、role没有任何query/raw taint”由同一CONTENT_APPEND opinion事务按`AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION->BATCH_CONTENT_QUOTA->ROLE_RUN->TOOL_CALL(稳定ID升序)->CONTENT_LEDGER->TIME_AUTHORITY_STATEMENT`子序列锁住可变事实并验证；batch与已PERSISTED evidence是immutable hash引用，只在锁住role workflow cursor后的同一快照读取，不另取会反序的行锁。事务必须处于不会再创建新invocation/tool-call的OPINION_FINALIZATION cursor、所属role全部raw chunk的PENDING_POLICY计数为0；任一条件不满足零opinion commit并由恢复器先完成分类。验证结果进入 opinionHash 与 OutcomeBasisV1；数据库 CHECK 不能伪装可证明这一跨表条件。

### 15.14 short_term_ai_opinion_content_chunk

thesis、supportingEvidence、negativeEvidence、nextDayDrivers、invalidationConditions、evidenceGaps、priorRoundDelta、priorRoundDeltaReasons 与 severeRisks.claim 不放在单个 opinion JSON：

- content_chunk_id、opinion_id；
- field_code；固定包含THESIS、SUPPORTING_EVIDENCE、NEGATIVE_EVIDENCE、NEXT_DAY_DRIVER、INVALIDATION_CONDITION、EVIDENCE_GAP、PRIOR_ROUND_DELTA、PRIOR_ROUND_DELTA_REASON、SEVERE_RISK_CLAIM；
- item_no、sequence_no；
- content_text，按 UTF-8 代码点边界不超过 64 KiB；
- byte_count、storage_charged_bytes、content_hash、created_at；CHECK `storage_charged_bytes>=byte_count+1024`；
- content_commit_no，来自 opinion 所属 role 的 content ledger；
- UNIQUE(opinion_id, field_code, item_no, sequence_no)。

数组每个 item 独立编号，severe risk使用itemNo=ordinal，超长 item 再分 chunk；cursor 不能在 item/chunk 中间 substring。单opinion全部数组item最多2048、content chunk最多2048，单batch opinion content chunk最多8192；每次插入按`BATCH_CONTENT_QUOTA->OPINION_CONTENT_QUOTA->ROLE_RUN->CONTENT_LEDGER`子序列同时CAS两级row counter，超行数时超额chunk/contentCommitNo/charged bytes全为零并明确INCOMPLETE_RESOURCE_LIMIT，不静默截断。claim使用相同content ledger、storage、ValuationIsolationPolicy carry scanner与分页，命中估值概念固定ROLE_OUTPUT；opinionHash覆盖risk manifest及claim内容Merkle root。HIGH风险只有claim所有chunk在acceptedThrough内且evidenceIds合法时可聚合。模型结构化内容因此可完整保存和分页读取，/opinions/{id}只返回manifest与标量。

### 15.15 short_term_ai_discussion_event

- event_id；
- stream_key，NOT NULL；
- batch_id，NOT NULL；
- round_id，可空；
- role_run_id，可空；
- invocation_id，可空；
- symbol，可空；
- cause_key，CHAR(64) NOT NULL，是对Stage 0冻结的`EventCauseV1 = schemaVersion|eventType|stableOwnerOperationId|phase|ordinal-or-NONE` canonical UTF-8取小写SHA-256；一次性生命周期事件同时以固定namespace UUIDv5(EventCauseV1原始canonical字节)生成event_id，重复回调/commit acknowledgement丢失重读同一行。明确可重复的heartbeat/progress事件必须先持久分配单调ordinal，不能用JVM计数器或occurredAt代替；
- event_scope：BATCH_SHARED、SYMBOL、ROUND；BATCH_SHARED 要求 round_id/symbol 均为空，SYMBOL 要求 symbol 非空且 round_id 为空，ROUND 要求 round_id/symbol 均非空并与同一 round 对齐；
- timeline_no，NOT NULL；
- event_type；
- tool_name；
- request_json、response_json 或 response_text；
- occurred_at、duration_ms；
- success、error_code、error_message；
- UNIQUE(batch_id, timeline_no)；
- UNIQUE(batch_id, cause_key)；
- 可空 round_id/role_run_id 若存在，必须分别通过复合 FK 与同一 batch 对齐；invocation_id 若存在必须经 role_run 归属校验。

streamKey 分别使用 batch:{id}、role:{id} 或 invocation:{id}，避免 NULL 唯一键语义。一个批次级四角色模型请求、通用 invocation/工具生命周期是 BATCH_SHARED；只针对一只股票的证据搜索是 SYMBOL；意见解析、聚合和父轮差异是 ROUND。写入者必须在事件创建时冻结 scope，不允许读 API 猜测。event payload 只保存白名单元数据、参数摘要和 contentRef，编码后上限 1 MiB并消费batch EVIDENCE_EVENT bucket；模型原文、长 opinion 和超限工具数据必须进各自 chunk 表，不能复制到 event 造成单行巨物。payload超限或普通审计bucket不足只保存免计的固定errorCode/contentRef/hash，不substring、不消费FINALIZATION_BASIS，不能留下永久运行态。

event 不再维护第二套 per-stream sequence。写入者以注册GuardUse按全局子序列锁owner行和batch timeline guard；先按causeKey重读，已有合法行直接返回，不再分配编号。不存在时以`timelineNo=nextTimelineNo`插不可变event并加1，持guard到commit；commit ack丢失后的重试只命中causeKey。后一个timelineNo无法在前一个commit前取得，故是跨stream commit-visible全序；回滚不产生可见事件，已提交编号永不复用。MODEL_OUTPUT chunk用invocation nextChunkSequence且与chunk/字节账本同事务；禁止SELECT MAX+1，读取不得假设连续。

`/rounds/{roundId}/events` 的成员集合固定为同 batch 中 `(event_scope=ROUND AND round_id=:roundId) OR (event_scope=SYMBOL AND symbol=:roundSymbol) OR event_scope=BATCH_SHARED`；其他 symbol/round 的事件排除。共享事件只引用同一 eventId，不复制行。跨 stream 分页固定 `timeline_no ASC`，签名 cursor 保存 batchId+timelineNo；occurredAt 只展示真实发生/观测时刻，不参与活跃分页。较早 occurredAt 但较晚提交的事件会取得较大 timelineNo，下一页仍能看到，不能按时间四元组永久跳过。

### 15.16 short_term_ai_source_evidence

- evidence_id；
- batch_id，NOT NULL；round_id、role_run_id、event_id 可空；
- symbol；
- origin：DETERMINISTIC_SNAPSHOT、DIRECT_ADAPTER、TAVILY_DISCOVERY、DISCUSSION_HISTORY；
- source_type、source_name；
- title、url、canonical_domain；
- provider_published_at_raw；
- published_at、admission_lower_bound_at、admission_upper_bound_at、point_in_time_basis、first_seen_at、retrieved_at；CHECK `admission_lower_bound_at<=admission_upper_bound_at`；
- excerpt，工具输入运输边界最大 64 KiB并消费batch EVIDENCE_EVENT bucket，不是模型输出；超出时 source evidence 标 INPUT_RESOURCE_LIMIT且只保存contentHash/ref，不substring也不传给模型；
- content_version、content_hash；
- point_in_time_eligible；
- eligibility_reason；
- advisory_use；
- rejection_reason；
- visibility：MODEL_AND_UI、INTERNAL_POLICY_AUDIT；valuation_policy_revision、valuation_concept_codes_json、valuation_policy_surfaces_json、url_hash；INTERNAL_POLICY_AUDIT 必须 excerpt/title/source_name/url 均为空、不可出现在 citationIds/severeRisk evidenceIds，也不进入公开 evidence/event/history API；
- content_commit_no，DETERMINISTIC_SNAPSHOT 预载可用预留值 0，角色工具证据必须来自该 role content ledger；
- created_at；
- evidence_scope_key，NOT NULL；预载证据使用 batch:{batchId}，角色私有证据使用 role:{roleRunId}；
- UNIQUE(evidence_scope_key, symbol, content_hash)；
- INDEX(symbol, published_at, created_at)；
- 可空 round_id/role_run_id/event_id 若存在，必须通过复合 FK 与同一 batch、symbol 和 scope 对齐；数据库约束与服务器 TrustedEvidenceValidator 双重校验。

外部point_in_time_eligible=true要求publishedAt非空、`publishedAt<=admissionLowerBoundAt`、advisoryUse=SAME_DAY_RESEARCH且pointInTimeBasis=AUTHORITATIVE_PUBLISHED_AT；`lower<publishedAt<=upper`固定EVIDENCE_TIME_UNCERTAIN/RESEARCH_ONLY，`publishedAt>upper`固定EVIDENCE_NOT_POINT_IN_TIME。tool call、tool execution、evidence、event硬上限分别为每batch256/256/512/4096行，超限走CONTENT_ROW_LIMIT_EXCEEDED并按已知失败收口。

### 15.17 short_term_ai_usage_budget

- budget_id；
- budget_date，Asia/Shanghai；discussion batch 严格复制 source.budget_bucket_date，旧 PAID_HTTP operation 才在首次 PREPARE mutation 从 lowerNow 派生；调用方不接触 lowerNow，绝不取 JVM 当前日期、upperNow 或恢复日期；
- actor_id；
- scope_type：GLOBAL、ACTOR、BATCH；
- scope_key；
- model_invocations；
- evidence_tool_calls；
- history_tool_calls；
- tavily_requests；
- tavily_credits；
- direct_fetches；
- discussion_batches；
- continuation_batches；
- version；
- UNIQUE(budget_date, scope_type, scope_key)。

batch 的 materialization、model invocation、logical tool、Tavily、direct fetch 全部继承 source 的不可变 budgetBucketDate；reservation、拒绝结算、UNKNOWN_CONSUMED 和重启只重读该字段。23:59 source 即使次日00:00才物化/发送也计入原 source 日。只有没有 source 的旧 PAID_HTTP 使用本次 lower 日期；这是明确的审计语义。

PAID_HTTP PREPARE 的预算锁计划固定为授时前预建并按全局唯一比较器 `scopeOrdinal, budgetDate, scopeKey` 锁住proof sampled日D和D+1，即严格`GLOBAL(D)->GLOBAL(D+1)->ACTOR(D)->ACTOR(D+1)`；authority ticket的lowerNow只能选其中一日计数并冻结operation.budgetBucketDate，未选行零增量。所有PAID重试从operation重读单一冻结日，不再锁D+1或按恢复日改桶。

`short_term_ai_continuation_admission_guard` 以 actor_id 为主键，保存 active_count、admission_budget_date NOT NULL、admission_count、version，DDL CHECK 固定 `0<=active_count<=2 AND 0<=admission_count<=10`；配置只可收紧2/10，不能放大硬上限。CONTINUATION POST 在完整 GuardUse 中先锁该行；以本次唯一 windowDate 做日切，日期改变只把admission_count重置为0，跨日尚未终结source仍计入active_count。默认 admission_count<10 且 active_count<2 才可与 source admission 同事务递增。相同幂等 source 重读不增；source MATERIALIZED 或进入任一不可逆终态时以 source.continuation_pending_slot_released=false CAS 同事务递减一次并置 true。每个提交点active_count必须等于该actor全部CONTINUATION admission中`continuation_pending_slot_released=false`的行数，由两库一致性查询与恢复断言；超过任一限制返回429且不创建source。它阻止不断换key制造无界PENDING source；真正 batch 仍受下述 discussion_batches/continuation_batches 原子预算。

旧付费POST另用两张固定尺寸准入表阻止预算拒绝或黑窗请求通过不断换key制造无界operation/ticket：`short_term_ai_paid_admission_guard(actor_id,budget_date,attempt_count,version,PRIMARY KEY(actor_id,budget_date),CHECK 0<=attempt_count<=500)`；`short_term_ai_paid_admission_attempt(attempt_id PRIMARY KEY,actor_id,route_code,idempotency_key,attempt_date,request_semantic_hash,attempt_status=WINDOW_BLOCKED|OPERATION_BOUND,operation_id NULL,created_at,updated_at,version,UNIQUE(actor_id,route_code,idempotency_key,attempt_date))`。WINDOW_BLOCKED要求operationId为空，OPERATION_BOUND要求非空；唯一合法边是WINDOW_BLOCKED->OPERATION_BOUND。attemptId固定UUIDv5(`actor|route|idempotencyKey|attemptDate|PAID_ATTEMPT_V1`)，operationId固定UUIDv5(`attemptId|PAID_OPERATION_V1`)，storage/slot/usage主键再从operationId固定派生；任何其他writer或随机换ID都被架构测试禁止。新operation入口先用HealthProof.sampledDbNow的本地日D按 `AI_RUNTIME_GUARD->PAID_ADMISSION_GUARD(D)->PAID_ADMISSION_ATTEMPT` 锁齐：锁后已有同key operation直接走恢复；已有当日attempt必须hash相同，否则409；新attempt且count=500时回滚、在任何authority ticket/storage/slot/usage/operation之前返回429/PAID_DAILY_ATTEMPT_LIMIT，by-key仍404。新attempt可在同一未提交业务事务中先以WINDOW_BLOCKED形态插入并锁住，最终只允许提交WINDOW_BLOCKED或在首次可见前更新为OPERATION_BOUND；事务回滚时两者都不可见。只有最终提交新attempt时令count+1。若跨午夜另一attempt在本事务锁后才赢得operation唯一键，当前事务必须在剩余锁预算内重读赢家：hash相同则整笔回滚当前attempt/count并返回赢家，hash不同409；timeout/未决503，绝不换operationId。已有WINDOW_BLOCKED且健康proof.sampledDbNow仍早于attemptDate的14:50时直接返回缓存的503/Retry-After，零新GuardUse ticket/app_guard_use/count；只有同一attemptDate且sampledDbNow>=14:50才可把该行与新operation原子改为OPERATION_BOUND。次日重试使用次日新attempt行/额度，旧日WINDOW_BLOCKED不跨日绑定。authority lowerNow本地日必须仍等于D，否则整笔回滚并用新HealthProof重进，不能跨午夜改锁第三行。配置只可把500收紧。这样每actor/日本地最多500个不同旧付费key，其中默认最多400个能消费模型预算，拒绝/窗口事实也被固定上界覆盖。

`short_term_ai_actor_workload_guard`以actor_id为主键，保存active_batch_count、version，CHECK `0<=active_batch_count AND active_batch_count<=4`。`short_term_ai_active_batch_slot`以第15.6.2节确定性batch_id为主键，保存actor_id、admission_id UNIQUE、slot_status=PROVISIONAL|ACTIVE|RELEASED|ABORTED、activated_at/released_at/aborted_at、abort_reason、version，不向batch建FK；CHECK逐状态固定为：PROVISIONAL四个时间/原因全空，ACTIVE仅activatedAt非空，RELEASED要求activatedAt/releasedAt非空且abort字段空，ABORTED要求abortedAt/reason非空且activatedAt/releasedAt空。materializer在预算前锁actor guard，以`INSERT ... ON CONFLICT DO NOTHING`插入PROVISIONAL并逐字段核验可能的既有行，但此时绝不增加active_batch_count。只有最终MATERIALIZE分支才在同一主事务把count+1、slot PROVISIONAL->ACTIVE、预算预占、batch插入和source=MATERIALIZED全提交。不可逆source终态（deadline、runtime/config/storage、active上限、预算拒绝、TimeAuthority capability失败）若需提交审计，才把本次PROVISIONAL->ABORTED且count不变；active_count已为4时仍可提交ABORTED/ACTIVE_BATCH_LIMIT，但不能插ACTIVE。仍可重试的CLOSED_WINDOW/DEFERRED_CUTOFF、linkage/parent未ready或BUSY_RETRYABLE必须在提交source非终态前删除本事务新建且从未激活的PROVISIONAL行；若行并非本事务新建、状态不为PROVISIONAL或已被外部看见则整笔回滚/503，不能留下会被admission_id唯一键挡住的ABORTED。事务整体回滚同样无slot。CONTENT_SNAPSHOT/SYSTEM_FALLBACK/TIME_AUTHORITY_UNAVAILABLE唯一freeze赢家仅对ACTIVE slot执行count-1与ACTIVE->RELEASED；PROVISIONAL/ABORTED不能freeze释放。这样终态拒绝可审计、可重试分支零committed slot，任何拒绝路径committed ACTIVE行数都为零。

continuation/workload/budget guard 的首行初始化都必须用 insert-on-conflict/安全 MERGE 或捕获且核验目标主键后重读，禁止 exists-then-insert；双实例首次同 actor/日期并发不得使任一实例启动或请求失败。

### 15.18 short_term_ai_usage_reservation

- reservation_id；
- operation_type：BATCH_MATERIALIZATION、MODEL_INVOCATION、TOOL_CALL、TAVILY_REQUEST、DIRECT_FETCH、PAID_HTTP_MODEL；
- operation_id；
- budget_date、actor_id、batch_id，可空；PAID_HTTP_MODEL 不属于讨论 batch 时 batch_id 为空，但 budget_date 必须来自首次 PREPARED GuardUse 冻结值；
- reserved_discussion_batches、reserved_continuation_batches、reserved_model_invocations、reserved_evidence_calls、reserved_history_calls、reserved_tavily_requests、reserved_tavily_credits、reserved_direct_fetches；
- status：RESERVED、SETTLEMENT_PENDING、SETTLED、UNKNOWN_CONSUMED、DENIED；
- denial_reason，可空；CHECK 要求 DENIED 时必填且所有 reserved_* 为零，其他状态时必须为空；
- created_at、settled_at、version；
- UNIQUE(operation_type, operation_id)。

materializer 的bind子序列为 AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->AI_STORAGE_QUOTA_GUARD->已有STORAGE_RESERVATION(627MiB)->CONTINUATION_ADMISSION_GUARD(仅CONTINUATION)->ACTOR_WORKLOAD_GUARD->ACTIVE_BATCH_SLOT(PROVISIONAL)->GLOBAL->ACTOR->BATCH budget->reservation->MANUAL_ATTESTATION_GUARD(仅MANUAL)->admission->linkage(仅MANUAL)->thread/outcome/parent guards；只有materialize许可分支才让active count+1、slot ACTIVE、stage/guard BOUND、预算预占、batch/source全有或全无。不可逆非materialize终态结算未用storage、将SEALED stage/guard置RECLAIM_PENDING并把PROVISIONAL->ABORTED；active count已为4时写ACTIVE_BATCH_LIMIT。预算不足提交DENIED reservation/source终态/slot ABORTED并reclaim stage。仍可重试的DEFERRED/BUSY分支删除本事务新建PROVISIONAL slot但保留同一SEALED stage/storage charged bytes供重试，绝不重复写4MiB。所有拒绝分支active count不变、committed ACTIVE slot为零。

materializer 已从 source 知道唯一 budgetBucketDate，故在 admission 前按 GLOBAL->ACTOR->预生成BATCH budget row->reservation 锁齐；BATCH_MATERIALIZATION 以 admissionId 为 operationId，在创建任何 batch/round/role 前原子预占 discussion_batches=1，CONTINUATION 再预占 continuation_batches=1，并与唯一 batch/source=MATERIALIZED 同事务提交。默认 actor/day 上限20/10；不足时插入唯一零额 DENIED reservation、source=BUDGET_EXHAUSTED、PENDING linkage同步FAILED，零batch/role并释放continuation pending-source slot。每次模型/Tavily/direct fetch 在 DISPATCHED 前同样按固定 GLOBAL->ACTOR->BATCH 顺序原子预占最坏额度；同一事务插入唯一 reservation 并 CAS 对应 invocation/toolExecution。logical tool call 按上一节处理：预算成功才更新 counter，预算不足则写唯一零额度 DENIED reservation + REJECTED，不把拒绝事实一起回滚。恢复先按operationId读取reservation，禁止二次预占。供应商返回usage后只允许向下结算未使用credits，响应未知则保守标UNKNOWN_CONSUMED。并发请求不能穿透上限或因锁顺序死锁。

gate 的 marker-null 拒绝与可恢复结算不能依赖进程内后续调用：把 operation 写为 FAILED_BEFORE_SEND 的同一事务必须把对应 RESERVED reservation CAS 为 SETTLEMENT_PENDING；若任一步失败则整个拒绝事务回滚，operation 仍可由 gate/recovery 重新判断。独立 BudgetSettlementReconciler 只扫描 `FAILED_BEFORE_SEND + transportHandoffAt IS NULL + reservation.status=SETTLEMENT_PENDING|RESERVED`，按 GLOBAL -> ACTOR -> BATCH -> reservation 固定锁序把实际消费结算为零并写 SETTLED；PAID_HTTP_MODEL 省略 BATCH 层。它幂等、可多实例竞争，不发网络、不创建 source/batch，不属于短线自动执行任务。若历史异常行已是 FAILED_BEFORE_SEND+RESERVED，也由同一规则修复。GET 可显示 `budgetSettlementPending=true`，但 operation 业务终态不回退；预算结算成功前 reservation 仍被保守计入上限。

PAID_HTTP_MODEL 没有 batch scope，只按 GLOBAL -> ACTOR 锁定；每个首次获准的旧付费 operation 在两层都预占 `model_invocations=1`，与讨论模型调用共享 actor/date 的默认 400 上限和可进一步收紧的全局上限，不能换 routeCode/Idempotency-Key 绕过。预算不足只创建零额 DENIED reservation。其他 operation 必须包含 batch 并锁三层。该差异由数据库 CHECK 和 PaidAiGuard API 固定，调用方不能自行选择跳过预算层级。

### 15.19 short_term_ai_content_seal

为了让 outcome basis 取决于数据库封闭结果，而不取决于哪个 coordinator 在 14:48:30–14:48:45 之间先抢到 CPU，每个 role 在 materialize 时同时创建一条独立 content ledger：

- seal_id、batch_id、role_run_id，role_run_id UNIQUE；业务键已校验，故意不对可能长时锁住的 role/batch 建 FK；
- role_content_deadline；
- next_content_commit_no，初始为 1；
- accepted_through_commit_no，封闭前可空；
- sealed_at，可空；
- version；
- UNIQUE(batch_id, role_run_id)。

每个可影响outcome的chunk/evidence/opinion/opinion-content/角色终态都调用CONTENT_APPEND GuardUse并引用全局锁序；所有可变长写先锁AI_STORAGE_QUOTA_GUARD与batch storage reservation/目标bucket，MODEL_OUTPUT再锁batch content quota与invocation，其他内容使用所需子序列。只有storage writeState=NORMAL、owner/bucket remaining足够、时间permitted且ledger未sealed时，才在同一事务同时消费storage字节、写app_guard_use、分配contentCommitNo并提交；角色终态同时写terminalContentCommitNo。MODEL_OUTPUT还原子增加invocation/batch两级counter。storage HIGH_WATER、精确截止、health失效或任一quota超界都零正文写并以INCOMPLETE_RESOURCE_LIMIT收口。sealer/freeze按storage->actor全局顺序结算未用额度；流式adapter每个完整有界chunk反压到短事务commit后再读provider。

lowerNow>=roleContentDeadline 的 DEADLINE_REACHED GuardUse 逐条锁 ledger 后写 sealedAt=lowerNow、acceptedThroughCommitNo=nextContentCommitNo-1。由于正文与 seal 都是 ledger->authority，先取得 ledger 的事务才有资格随后按 post-lock 时间决议；若已到线则正文拒绝，绝不因“截止前排队”被接纳。按 roleCode 逐条短事务封存，四条全封且 OUTCOME_FREEZE GuardUse 的 upperNow<freezePublishAdmissionDeadline 才能 CONTENT_SNAPSHOT；错过则 effectiveOutcomeAt 尝试 fallback。

### 15.19.1 short_term_ai_sealed_content_manifest

为防止逐实体清单放大freeze basis，ledger sealed且PENDING_POLICY=0后为每个role生成一条只增manifest：manifest_id、batch_id、role_run_id UNIQUE、accepted_through_commit_no、leaf_schema_version、entity_counts_json、accepted_payload_bytes、public_count、internal_policy_count、content_root_hash、manifest_json、manifest_byte_count、storage_charged_bytes、created_at；CHECK manifestByteCount<=65536、charged>=byteCount+2048、counts/bytes非负。叶子逐字为roleCode/contentCommitNo/entityTypeOrdinal/entityId/contentHash/byteCount/finalPublicVisibility/valuationPolicyRevision/conceptCodesHash，按`roleCode,contentCommitNo,entityTypeOrdinal,entityId`排序，算法固定SHA-256-MERKLE-V1并冻结空树与奇数叶复制规则。

manifest finalizer在零锁阶段流式扫描acceptedThrough范围计算counts/root，再以`AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION/FINALIZATION_BASIS->CONTENT_LEDGER->CONTENT_MANIFEST_GUARD`短事务核验seal未变、CAS bucket byte/row额度并幂等插入；commit unknown先按roleRunId唯一键读，不生成第二root或重复扣账。恢复器只补已sealed ledger缺失manifest，不改内容/role/freeze。四条immutable manifest齐全前不得CONTENT_SNAPSHOT；全量内容仍可按commitNo重算root追溯。

### 15.20 short_term_ai_outcome_freeze

为避免迟提交事务改变 effectiveOutcomeAt 后展示的 AI 结论，freeze 使用独立表且故意不建立指向可能被锁 AI 行的 FK；它是 batch/round/role effective 结论、basis hash 和 fixedAt 的唯一权威，运行表不保存这些复制列：

本节所有“outcome/parent guards”缩写的可执行锁序始终是 `AI_STORAGE_QUOTA_GUARD -> STORAGE_RESERVATION -> ACTOR_WORKLOAD_GUARD -> ACTIVE_BATCH_SLOT -> OUTCOME_GUARD -> PARENT_MARKER_SEQUENCE_GUARD -> PARENT_MARKER_GUARD(roundId ASC)`；normal/system/time-authority fallback 均在同一事务结算未用storage额度、释放ACTIVE slot并提交freeze/markers，不得跳序。

- freeze_id、batch_id，UNIQUE；batch_id 作为已校验业务键保存但无 FK；
- schema_version、freeze_revision BIGINT NOT NULL CHECK(freeze_revision=1)、freeze_kind：CONTENT_SNAPSHOT、SYSTEM_FALLBACK；
- effective_outcome_at、frozen_at、failure_code；
- basis_json TEXT、basis_hash、basis_byte_count；包含四条sealed content manifest引用/root、各 role 派生状态和排除原因，不复制逐实体列表或长正文；CHECK `basis_byte_count<=524288`；
- batch_effective_status；
- round_statuses_json、advisory_states_json；
- created_at；
- UNIQUE(batch_id)、UNIQUE(batch_id, basis_hash)。

Stage 0 必须冻结 `OutcomeBasisV1` JSON Schema，不能让数据库返回顺序或 seal 后 live role 状态决定 hash。最低字段为 schemaVersion=1、freezeRevision=1、freezeKind、failureCode、batchId、advisoryUse、roleContentDeadline、effectiveOutcomeAt、四条roleSeal、恰四条acceptedContentManifests、effectiveRoleStates、qualifiedOpinionStates、roundStates、advisoryStates、exclusionReasons。manifest只含manifestId/roleCode/acceptedThrough/counts/bytes/root；qualifiedOpinionStates最多32条，round/advisory各最多8条，exclusionReasons只允许枚举且最多256项，因此合法basis必然<=512KiB。qualifiedOpinionStates只从acceptedThrough内immutable opinion生成并冻结complete/validated/qualified use/reason/valuation result；无意见写固定missing reason。CONTENT_SNAPSHOT的effectiveRoleStates沿用SEALED_TERMINAL/UNSEALED唯一映射。构建时任一manifest缺失、root不符或acceptedThrough内仍有PENDING_POLICY，固定拒绝正常freeze并报警`CORRUPTED_CONTENT_MANIFEST|POLICY_CLASSIFICATION_INCOMPLETE`，不得补猜；effectiveOutcomeAt由SYSTEM_FALLBACK收口。字段不能以缺失代null，时间UTC RFC3339微秒，basis用RFC8785 JCS UTF-8与小写SHA-256；auditObservedAt/createdAt/live status在basis外。

SYSTEM_FALLBACK 专用真值表逐字段固定：acceptedContentManifests=[]、qualifiedOpinionStates=[]；四条 roleSeal.acceptedThroughCommitNo=null；每条 effectiveRoleState 为 rawFactKind=UNSEALED/rawStatus=null/terminalContentCommitNo=null/effectiveStatus=INCOMPLETE/effectiveReasonCode=failureCode；每个 roundStatusesJson/OutcomeBasisV1.roundStates.status=INCOMPLETE；freeze.batchEffectiveStatus=INCOMPLETE；每个 advisoryStatesJson/OutcomeBasisV1.advisoryStates.state=INCOMPLETE。failureCode 只能是 CUTOFF_FREEZE_MISSED 或 TIME_AUTHORITY_UNAVAILABLE。CONTENT_SNAPSHOT 才使用上一段 TIMED_OUT/qualified-opinion聚合规则，两个 freezeKind 不共享隐式默认值。

四条 ledger 封闭后，accepted 集合不可变；builder 只纳入 acceptedThrough 范围内容，且只以范围内 immutable terminal fact 生成 rawFactKind/rawStatus。seal 后 live status 不改变 basis。CONTENT_SNAPSHOT 用 GuardUse 严格按本节的 `AI_STORAGE_QUOTA_GUARD -> STORAGE_RESERVATION -> ACTOR_WORKLOAD_GUARD -> ACTIVE_BATCH_SLOT -> OUTCOME_GUARD -> PARENT_MARKER_SEQUENCE_GUARD -> PARENT_MARKER_GUARD`子序列锁齐并持有；授时后在一个业务事务写一条含 basis 与全部 effective 字段的 freeze、释放storage未用remaining并按互斥真值表结算为SETTLED或EXHAUSTED、释放恰一个ACTIVE slot，以及提交manifest全部markers；事务成功后 marker_count=N，任一步失败全回滚。OUTCOME marker 的 basisRefId=freezeId、freezeRevision=freeze.freezeRevision=basis.freezeRevision=1、outcomeBasisHash=freeze.basisHash，必须从同一 freeze/basis 复制，禁止独立生成。缺 marker 时插 OUTCOME；已存在 RAW marker按第 15.7.1 节核验共同身份并保留 RAW 首胜；已存在 OUTCOME 要逐字段核验。任一不符整笔回滚 503/报警。14:48:44 是 post-lock GuardUse 授权线，不是物理 commit 可见线；未决唯一赢家按 503。

到 effectiveOutcomeAt 仍无已提交行时，只有BatchOutcomeDueCoordinator/OUTCOME_FINALIZER用独立短事务按同一storage->actor->outcome->marker子序列锁齐，再尝试插入无内容 SYSTEM_FALLBACK/CUTOFF_FREEZE_MISSED，并为每个 round ensure OUTCOME_FREEZE parent marker；普通GET只读且不创建赢家。storage未用额度结算+ACTIVE slot释放+freeze+markers 原子提交。正常情况下 500ms 排空窗已结束，CONTENT_SNAPSHOT 与 fallback 仍由 batch 唯一约束决定一个赢家；若未决合法事务等待至timeout，GET返回503，后续只读最终唯一赢家；任何SYSTEM_FALLBACK不可反转。

并发 freeze 依靠 batch 唯一约束只允许一个赢家；失败方核验 constraint 后还要完整核验 immutable identity。TIME_AUTHORITY_UNAVAILABLE 只能按第 8.3 节使用 trustedFallbackAt，并在同一事务结算storage未用额度、释放ACTIVE slot、提交含 basis/effective 的 freeze 与 manifest 全部 markers；高水位/manifest 不可读或任一步失败为 503、全回滚。freeze 只额外锁/更新storage quota/reservation、actor workload guard/active batch slot、outcome/marker guards，不更新/锁定 role、round、batch 或 deterministic 表；不存在 closer 后置 effective 投影或storage结算事务。详情页只把 live raw 状态作为 basis 外审计元数据。

### 15.21 ai_paid_http_operation

现有趋势分析和 agent-consensus AI POST 也属于真实付费入口，不能成为新预算与幂等合同的旁路：

- operation_id；
- actor_id、route_code：TREND_ANALYSIS、AGENT_CONSENSUS；
- idempotency_key、request_semantic_hash；
- normalized_request_json，可空；只有 `FAILED_BEFORE_SEND/BUDGET_EXHAUSTED` 的预算拒绝行允许为空，其余状态必填。非空时只含该路由实际生效的白名单字段，与transport_request_snapshot_json合计UTF-8最多4MiB并消费PAID storage REQUEST bucket，其 canonical SHA-256 等于 request_semantic_hash；预算拒绝仍对内存中的完整规范化请求计算并保存相同semantic hash，以便同key不同body返回409，但不保存被拒正文；
- transport_request_snapshot_json TEXT、transport_request_hash，可空；二者只能同时为空，且仅允许 `FAILED_BEFORE_SEND/BUDGET_EXHAUSTED`。由 normalized request、冻结 prompt/config revision 确定性生成实际无密钥 provider 请求，DISPATCHED 前保存，之后不可更新；Gate 锁后重算 snapshot hash 并校验，不得重新读取会漂移的 HTTP body/config，也不得把密钥、Authorization 或任意 endpoint 写入快照；
- delivery_status：PREPARED、DISPATCHED、RESPONSE_RECEIVED、PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND、OUTCOME_UNKNOWN；FAILED_AFTER_SEND表示transport已发生但已知本地无法完整持久化/解析响应，是不可自动重发的稳定终态，不与结果未知的OUTCOME_UNKNOWN混用；
- llm_config_revision、provider、model、provider_request_id；
- response_manifest_json、response_hash、response_bytes；完整响应不放单行，见下述chunk表；manifest只含chunk序号范围、逐段/整体hash、byteCount、finish/provider审计引用，canonical UTF-8硬上限1MiB并消费AUDIT_DETAIL，不复制正文。PERSISTED要求response_manifest/hash非空且`0<=response_bytes<=33554432`，RESPONSE_RECEIVED可在流式期间保存当前accepted bytes，FAILED_AFTER_SEND仅保留partial-audit manifest/hash/bytes并禁止作为成功响应，其他状态按Stage 0 CHECK固定nullability；
- error_code、error_message；
- admitted_at、paid_dispatch_window_seconds、latest_egress_permit_at、budget_bucket_date、created_at、dispatched_at、transport_handoff_at、response_deadline_at、handoff_guard_use_id、completed_at、version；responseDeadlineAt只在Gate提交handoff时与transportHandoffAt同事务写为`transportHandoffAt+120s`，二者必须同空/同非空且以后不可延长；
- UNIQUE(actor_id, route_code, idempotency_key)。

相同 key/hash 返回已持久响应或当前状态，相同 key 不同 hash 返回 409。paid operation合法边固定：PREPARED->DISPATCHED|FAILED_BEFORE_SEND；DISPATCHED且marker空->FAILED_BEFORE_SEND，marker非空->RESPONSE_RECEIVED|OUTCOME_UNKNOWN；RESPONSE_RECEIVED->PERSISTED|FAILED_AFTER_SEND；四个终态PERSISTED/FAILED_BEFORE_SEND/FAILED_AFTER_SEND/OUTCOME_UNKNOWN无出边。PaidAiGuard 的 PREPARE GuardUse adapter 先在零锁阶段按第15.17节计算attempt/operation及全部派生ID；业务事务必须从AI_RUNTIME_GUARD开始，再严格按`AI_RUNTIME_GUARD->PAID_ADMISSION_GUARD->PAID_ADMISSION_ATTEMPT->AI_STORAGE_QUOTA_GUARD->STORAGE_RESERVATION(48MiB)->PAID_WORKLOAD_GUARD->PAID_OPERATION_SLOT(WAITING)->BUDGET_GLOBAL->BUDGET_ACTOR->USAGE_RESERVATION->PAID_OPERATION(PREPARED)`取得/复用attempt并在authority前建立全部确定性准备行、写入受REQUEST bucket覆盖的完整无密钥请求快照并锁齐D/D+1预算。随后唯一authority DML以lowerNow冻结budgetBucketDate并注册WINDOW_BLOCKED、ADMIT或BUDGET_DENY mutation：ADMIT才把选中日预算计数、RESERVED usage、PREPARED->DISPATCHED与attempt=OPERATION_BOUND提交；BUDGET_DENY把两个请求JSON及其本事务storage字节反向prepare-cancel，提交零额DENIED、FAILED_BEFORE_SEND/BUDGET_EXHAUSTED、WAITING->RELEASED、storage零使用SETTLED和attempt=OPERATION_BOUND；WINDOW_BLOCKED必须在仍持全部锁时撤销本事务operation/usage/slot/storage及未选/选中预算增量，最终只提交至多一条同key WINDOW_BLOCKED attempt、attempt_count和拒绝app_guard_use，故by-key=404。prepare-cancel只作用于本事务从未公开的行，任一行已存在或字节无法精确反向则全回滚/503。同日14:50前重复key直接读attempt返回、零新ticket，14:50同key/hash才复用该attempt重新执行完整准备计划且不再次计数。feature/config/storage/输入非法在authority前回滚本次新attempt和全部准备行，返回稳定4xx/503且by-key=404；输入超过4MiB更在任何attempt/guard前400。相同key/hash的预算拒绝重试仍返回原429，相同key不同hash仍409，by-key只读返回终态事实。任何故障点全有或全无，不能留下PREPARED+WAITING、孤立OPERATION_BOUND或半套请求快照绕过预算。后续 AiEgressGate GuardUse 提交唯一 ACTIVE slot+handoff 后才允许物理模型发送。DISPATCHED 后响应未知必须 OUTCOME_UNKNOWN，不由 HTTP 客户端自动重发。该表、预算、内部身份与 Idempotency-Key 未完成前，这两个旧付费 POST 在发布配置中保持关闭，不能仅靠前端隐藏。

`ai_paid_http_response_chunk` 以(operation_id,sequence_no)唯一，保存content_text、byte_count<=65536、storage_charged_bytes、content_hash、received_at，CHECK `charged>=byteCount+1024`；adapter跨任意小provider frame增量解码并合并，非final chunk取不切断代码点且不超过65536字节的最长前缀，终结才落final chunk，严格32MiB响应最多513个chunk，不能用“一frame一行”提前撞1024行上限。正常接纳chunk事务使用AI_STORAGE_QUOTA_GUARD->PAID storage reservation/RESPONSE bucket->paid operation子序列，只有writeState=NORMAL、`responseBytes+byteCount<=32MiB`且GuardUse `STRICT_BEFORE(responseDeadlineAt)`时才与counter/chunk原子提交。完整重建/hash/Schema校验必须在有界流中完成；PERSISTED终态同样要求锁后`STRICT_BEFORE(responseDeadlineAt)`并与ACTIVE->RELEASED/count-1及storage/预算结算原子提交，不能只因最后一chunk较早就越过最终线。越界、HIGH_WATER或已知持久化/解析失败时不写超额chunk，另以`storage quota->storage reservation/TERMINAL_CLOSER->paid workload guard->paid slot->BUDGET_GLOBAL->BUDGET_ACTOR->USAGE_RESERVATION->paid operation`小元数据事务消费首个最多64KiB保留记录并原子写`deliveryStatus=FAILED_AFTER_SEND,errorCode=PAID_RESPONSE_RESOURCE_LIMIT|PAID_RESPONSE_PERSISTENCE_FAILED`、ACTIVE->RELEASED/count-1及未用storage/预算结算；该事务仍持有storage guard但不消耗RESPONSE/AUDIT_DETAIL。lowerNow>=responseDeadlineAt时按锁后已提交事实唯一分支：若operation=RESPONSE_RECEIVED或已有任何已提交response chunk，使用同一锁序消费首个TERMINAL_CLOSER记录，原子写`FAILED_AFTER_SEND/PAID_RESPONSE_DEADLINE_EXCEEDED`、usage可证则SETTLED否则UNKNOWN_CONSUMED、slot ACTIVE->RELEASED/count-1以及storage结算；这是响应已到而本地未在时限内形成合法PERSISTED的已知失败，不得写OUTCOME_UNKNOWN。只有operation=DISPATCHED、marker非空且没有已提交RESPONSE_RECEIVED/chunk，并且响应/结果或其提交ack确实无法判断时，才消费首个TERMINAL_CLOSER并原子写OUTCOME_UNKNOWN、slot ACTIVE->UNKNOWN_HELD且active_count不减。两支都主动取消本地stream；迟到回调只能记录免计计数，零chunk/零状态覆盖。response receipt与deadline closer使用相同operation版本CAS，先提交者唯一决定上述分支；commit acknowledgement不明先读operation/slot/closer，不猜写另一终态。已接受chunk仅作partial-audit manifest保留，不作业务成功响应。只有PERSISTED才允许API按sequence重建完整响应并核验responseHash，不在内存先读取无界BodyHandlers.ofString。

旧付费物理并发由 `short_term_ai_paid_workload_guard(guard_key='PAID_HTTP',active_count,version,CHECK 0<=active_count<=4)` 与 `short_term_ai_paid_operation_slot(operation_id PRIMARY KEY,actor_id,slot_status=WAITING|ACTIVE|RELEASED|UNKNOWN_HELD,activated_at,unknown_held_at,released_at,release_reason,version)` 线性化，不借用discussion activeBatchCount。DDL CHECK固定为互斥真值表：WAITING四个历史字段全空；ACTIVE只有activatedAt非空；UNKNOWN_HELD只有activatedAt/unknownHeldAt非空；RELEASED要求releasedAt/reason非空，并且其历史只能是`activatedAt/unknownHeldAt均空`（WAITING释放）、`activatedAt非空且unknownHeldAt空`（ACTIVE释放）或二者均非空（UNKNOWN_HELD释放），禁止activatedAt空但unknownHeldAt非空。合法边只有WAITING->ACTIVE|RELEASED、ACTIVE->RELEASED|UNKNOWN_HELD、UNKNOWN_HELD->RELEASED，RELEASED无出边。guard.activeCount在每个提交点必须等于ACTIVE+UNKNOWN_HELD slot数且<=4，由同事务更新与两库一致性查询验证。PREPARE与operation同事务创建WAITING slot；持久付费dispatcher只处理已有用户operation，executor拒绝只保留WAITING并按原截止时间重试，不先写handoff。AiEgressGate只在专用core4 worker内按全局子序列锁runtime/storage reservation/paid guard/slot/budget reservation/operation：active_count<4且时间允许时，才把count+1、WAITING->ACTIVE、transportHandoffAt marker与responseDeadlineAt全事务提交后在该同一Gate调用帧发一次；已满时保持WAITING/零handoff并在原latestEgressPermitAt前有界重试。WAITING到截止后以同一storage->paid->budget->operation子序列把FAILED_BEFORE_SEND、slot RELEASED、storage未用额结算与usage reservation=SETTLEMENT_PENDING全有或全无，count不变；BudgetSettlementReconciler随后只结算预算。PERSISTED/FAILED_AFTER_SEND已知终态使用同一子序列，把业务终态、ACTIVE->RELEASED/count-1及未用storage/预算结算全有或全无。handoff后崩溃或responseDeadlineAt到达且结果不明时为OUTCOME_UNKNOWN+UNKNOWN_HELD并继续计入active_count，只能由受内部身份保护且有provider审计证据的运维reconciliation释放，不能靠重启/超时自动腾槽。双实例任意key并发物理HTTP始终<=4；deadline coordinator只收口已有operation、绝不创建或重发请求，队列/worker不创建扫描、候选或新operation。

paidDispatchWindow 固定 30 秒。入口 GuardUse 的方向矩阵为：upperNow<14:47:55 时 admittedAt=upperNow、permit=min(+30s,14:47:55)；否则仅当 lowerNow>=14:50 时 admittedAt=lowerNow、permit=+30s；中间 CLOSED_WINDOW 返回 503 且不插行。egress 始终要求 upperNow<latestEgressPermitAt；旧 operation 不跨 14:50 续期。

旧付费 POST 的 durable operation 是响应恢复事实源：DISPATCHED/RESPONSE_RECEIVED 查询返回 202 和 Retry-After，PERSISTED 返回 200 及已校验完整原响应，FAILED_BEFORE_SEND/FAILED_AFTER_SEND/OUTCOME_UNKNOWN 返回带稳定 errorCode 的终态响应，不允许同 key 重新发送。PREPARED只允许作为同一未提交PREPARE事务内的过渡值，新生产事务绝不能以PREPARED提交；若故障注入/人工数据出现committed PREPARED，读取固定503/CORRUPTED_PAID_PREPARE而不是尝试补写。`FAILED_BEFORE_SEND/BUDGET_EXHAUSTED` 的POST及同key重试固定429；只读by-key端点仍以200返回该终态DTO，不把查询本身伪装成一次新拒绝。gate 超界使用 errorCode=FAILED_BEFORE_SEND_CUTOFF；只有用户明确新建一次操作才产生新 key。入口黑窗的AI_EGRESS_CUTOFF只留下有界WINDOW_BLOCKED attempt而不是operation，同 key 查询为404，14:50后可用原 key/body把它绑定到首次operation；新key超过每日500 attempt时在零ticket/零operation前返回429/PAID_DAILY_ATTEMPT_LIMIT，次日本地日才有新额度。

## 16. 事务、一致性与恢复

### 16.1 确定性结果与 AI 解耦

确定性源结果永远先提交：

1. durable manual job/report 按第 15.2 节独立 stage+certify；只有 result_status=SUCCEEDED 的确定性终态才发布 after-commit source 事件。旧 scheduled 表不再产生新 source。
2. aiRequested=true 且候选非空的合格 manual result 与attestation同事务提交后可发布两个同义唤醒信号；任一 handler 都必须先 ensure/read admission，并且只有读回 MATERIALIZATION_PENDING/DEFERRED_CUTOFF 才 ensure/wake linkage。不可逆终态零 linkage 操作，MATERIALIZED 只核验既有 committed batch/RESOLVED linkage；信号丢失只由reconciler/due coordinator恢复，GET严格只读。两者都不能进入 manual 事务或延迟 HTTP。aiRequested=false 或不合格终态冻结原因，不会日后追溯收费。
3. CONTINUATION 在允许时段由 HTTP GuardUse 使用REPEATABLE_READ，按 `AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD->CONTINUATION_ADMISSION_GUARD->admission->authority statement` 唯一LockPlan；deterministic admissionId/stage guard在零锁阶段预生成，stage guard与source同事务首次可见。第一条runtime SQL建立snapshot，锁齐后在authority前只读并核验不可变parent marker/roundIdentityHash，把指定父轮候选身份纳入GuardSubjectSnapshot，再提交带白名单sourcePayload的MATERIALIZATION_PENDING admission，同时原子执行每日10次/active2的continuation admission限制。问题文本在返回202前已持久化。14:47至14:50按第14.2节返回503且不创建半成品guard/admission。
4. materializer 先做零锁parentInputReady与能力预检；只有仍有物化可能且parentInputReady=true才按第15.3.1节通过stage guard生成/复用SEALED stage，再用 MATERIALIZE_BIND GuardUse 处理 MATERIALIZATION_PENDING/DEFERRED_CUTOFF；parent未ready固定零stage/owner。以post-lock businessNow作为materializedAt。health、过期、runtime/config/storage/active-capacity/budget、黑窗按第15.3节唯一顺序收口。锁后只逐hash/版本重验stage/source/attestation/linkage；允许时authority后只绑定stageRef与小manifest，原子消费actor active-batch slot与BATCH_MATERIALIZATION预算，写batch/thread/round/candidate/四role/seal/content quota/event timeline guard，CAS stage/guard=BOUND、admission=MATERIALIZED并释放continuation pending-source slot。authority后长payload SQL调用数必须为0；全部小型后置SQL与commit落在GuardUse严格100ms内，超时按COMMIT_UNKNOWN只读唯一事实。14:47前合法准入但稍晚commit-visible不重算materializedAt，也不能越过INITIAL gate。
5. materializer 唯一冲突按 constraint 重读赢家；可恢复异常可增加 attempts，但 nextRetryAt 只能在不晚于现有 materializationDeadlineAt 的范围内提前安排重试，绝不覆盖第 15.3 节 DEFERRED 的固定 D14:50、提高任何 deadline 或跨日续命。达到固定 deadline 优先 MATERIALIZATION_FAILED；配置重试耗尽也可提前失败，且不传播回 manual。
6. reconciler 只从提交时 aiRequested=true 的 eligible manual attestation 和 pending continuation admission 重建 identity；对 manual 固定先 ensure/read admission，只有 PENDING/DEFERRED 才补 linkage且仅在linkage=PENDING时唤醒，终态零ensure/零wake，MATERIALIZED缺committed batch或既有RESOLVED linkage视为损坏503；之后统一执行第 15.3 节有序状态机，不读取旧 scheduled snapshot。`UserSourceDueCoordinator`独立消费source.nextWakeAt，保证没有GET/after-commit信号时仍到点恢复；两者可重复唤醒但共享wake claim/source CAS，不形成第二套状态机。

任何 AI 初始化失败、唯一冲突、执行器拒绝、Tavily 或 LLM 配置错误都不能改变手动确定性 payload 的规范化 hash、status、attestation proof 或 API 响应。

### 16.2 创建和父链

materializer 以“SEALED stage + 一个 MATERIALIZE_BIND GuardUse”完成：

- 零业务锁阶段用READ_MODEL非锁定读取immutable manual attestation/manual payload或continuation source/linkage事实，先证明parentInputReady，再规范化最多40只候选并构建第15.3.1节最多10个canonical object：40只准入manifest、前八完整batch snapshot和逐只selected candidate。aggregate payload`<=4194304`才进入stage guard事务，4194305起锁guard证明NO_STAGE后进入MATERIALIZATION_INPUT_RESOURCE_LIMIT。零锁阶段不得读取parent marker sequence high-water；stage提交事务只使用第15.3.1节固定锁序且不取authority。依赖businessNow的deadline与最多8个roundIdentityHash只在bind authority后对小manifest计算；
- bind主事务同时取得business/authority连接，从`AI_RUNTIME_GUARD->MATERIALIZATION_STAGE_GUARD`开始并要求guard/stage=SEALED，再锁已有storage owner；CONTINUATION随后锁admission guard，actor workload/PROVISIONAL slot与source日期预算；MANUAL按全局序锁MANUAL_ATTESTATION_GUARD、admission、linkage并非锁定核验attestation/manual immutable payload，CONTINUATION只锁admission；之后按symbol插入/重读固定尺寸thread/outcome guard，锁parent-marker-sequence并冻结high-water，再锁parent guard。锁后只比较stage/source/attestation/linkage/parentInput与预构建hash；禁止再次解析reportJson、排序候选、编码或搬运4MiB artifact。任一变化整笔回滚并从parent-ready/stage阶段重建；
- identity算法由Stage 0固定namespace与UTF-8 name：batchId=UUIDv5(`admissionId|DISCUSSION_BATCH|schemaVersion`)、roundId=UUIDv5(`batchId|symbol`)、candidateAdmissionId=UUIDv5(`batchId|symbol|CANDIDATE`)、roleRunId=UUIDv5(`batchId|roleCode`)、sealId=UUIDv5(`roleRunId|CONTENT_SEAL`)、outcomeGuardId=batchId、parentMarkerGuardId=roundId。threadId按symbol固定namespace UUIDv5；这些ID在同一source所有重试完全相同，碰撞/既有行必须逐字段核验，不能随机换ID绕过唯一键。已锁admission行就是batch/admission/sourceKey及其派生round/candidate/role/seal/content/event guard所有未来唯一键的天然guard；任意竞争者必须先取得同一admission锁并在步骤1看到赢家，故authority后insert不会取得未预锁的独立唯一键。跨source共享的thread和未来parent marker没有这个天然保护，所以必须在authority前插入/锁住专用guard。MANUAL_SCAN只复制committed linkage四元组，CONTINUATION只复制source创建事务四元组；禁止动态重查最新父轮；
- 锁与hash核验完成后立即记录guardUseStartNano并执行authority statement。PERMITTED/MATERIALIZE时，唯一MutationAdapter只插入batch/round/candidate/四role/seal/content quota/event timeline guard的StageContentRef与小manifest、写deadline，绑定admission.batch、source/batch/round advisoryUse/budgetBucketDate、PROVISIONAL->ACTIVE、stage/guard SEALED->BOUND、admission->MATERIALIZED并释放continuation pending/wake claim；authority后stage object/chunk/长snapshot SQL调用数必须为0。任一约束失败全事务回滚，后续必须新GuardUse；
- 黑窗、时间不确定、坏时钟或任何非MATERIALIZE authority branch不绑定stage，按注册的小型DEFER/FAIL mutation提交或安全回滚。ticket后的引用insert、CAS和commit acknowledgement计入严格100ms；到100ms一律COMMIT_UNKNOWN，恢复只按source/batch/stage唯一事实判定；
- 为所有候选插入 candidate admission：前八绑定已生成 roundId，其余为 NOT_SELECTED_LIMIT 且 roundId=NULL；这些行与batch一次commit可见；
- 四个 batch-level role run、四条 content seal及其NOT NULL deadline在authority后一次插入，全部使用同一materializedAt和第8.3节公式，不存在可提交的空deadline临时状态；
- 同事务消费 ticket、写 app_guard_use；
- 提交后唤醒 dispatcher。

父轮真值表只有两行：MANUAL_SCAN 使用 after-commit linkage REPEATABLE_READ snapshot 中可见的 marker 集合冻结逐 symbol parent 四元组；CONTINUATION 使用 source 创建事务冻结的 parent 四元组。只有 snapshot 中 commit-visible marker 表示可作父轮；manual sourceDecisionAt 只作展示/审计，不参与回溯边界。任何实现不得把 CONTINUATION 换成最新轮，不得对 MANUAL_SCAN 的 null 事后补父轮，也不得因后来的 freeze/closer 改变 marker。

并发 thread/batch/round 创建只依赖唯一约束与重复键后的重新读取，禁止 exists 后 insert。continuation 的幂等作用域为 actorId + symbol + parentRoundId + idempotencyKey；同时保存 questionFingerprint。同 key 不同 fingerprint 返回 409。

### 16.3 认领与 fencing

PostgreSQL 认领先按 `OUTCOME_GUARD -> ROLE_RUN` 子序列锁定预建guard和目标role；已存在committed outcome freeze时直接只读最终事实，零claim/零dispatcher wake/零transport。尚无freeze才用 DEADLINE_REACHED 型 lowerNow 判断 leaseUntil 已过：QUEUED 或 CLAIMED/RUNNING 且 leaseUntil<=lowerNow；成功 claimEpoch+1/token/lease。health 无效不认领 EXECUTION，只允许 capability closer。若 marker-nonnull operation 结果未知，则 UNKNOWN_FINALIZATION_ONLY，禁止重发；否则 EXECUTION。受影响行数必须一。freeze与claim以同一outcome guard线性化：claim先提交只代表本次owner事实，后续Gate仍会重锁guard并被后来freeze拒绝；freeze先提交则claim零更新。

UNKNOWN_FINALIZATION_ONLY owner 不用一个跨预算层级的大事务收口，避免与 GLOBAL -> ACTOR -> BATCH 的预占顺序形成死锁：第一步在普通预算结算事务中按 GLOBAL -> ACTOR -> BATCH -> reservation 固定顺序把相关 reservation 幂等改为 UNKNOWN_CONSUMED，不持有 role/operation 锁；第二步按 role -> operation ID -> toolCall -> content ledger 锁序，重新验证这些 reservation 均已 UNKNOWN_CONSUMED 和新 token/epoch，再把 invocation/tool execution 改为 OUTCOME_UNKNOWN/TOOL_OUTCOME_UNKNOWN、logical tool call 改为 OUTCOME_UNKNOWN，并仅以 `role.status IN (QUEUED,CLAIMED,RUNNING)` 的 CAS 尝试把 role 收口 OUTCOME_UNKNOWN。若 role 已由 deadline/完成/失败/取消任一终态首胜，CAS 必须零行并追加 ROLE_TERMINAL_ALREADY_* 审计，绝不覆盖。第二步不得调用 AiEgressGate、创建新 operation或预占预算；第一步完成而第二步崩溃时，新 owner 只重做第二步。若 UNKNOWN 首胜且仍早于 roleContentDeadline，role 终态按 content ledger 写 terminalContentCommitNo；若已过内容截止，原始 operation 未知仍可审计，但 freeze 不承认未封存 role 终态。recoveryMode 永远不能回到 EXECUTION。

普通恢复 owner 从持久 workflowStep/cursor 继续：PERSISTED 只读；DISPATCHED 且 marker 为空可用原 operation/reservation 竞争 gate 单赢家；marker 非空且本地结果未知的生成/搜索请求禁止再次 POST，直接进入 UNKNOWN_FINALIZATION_ONLY。所有后续写入携带新 token/epoch；聚合只读取 freeze basis 中 validated opinion。

### 16.4 流式完成

- 模型 chunk 以有界缓冲聚合后短事务追加，网络期间无长事务。
- 工具请求先记 TOOL_REQUEST；校验失败记 TOOL_REJECTED；合格 evidence 先落库再回传模型。
- role 解析完成事务写每股 opinion 并更新 role 终态。
- batch 正常提前完成按固定真值表；lowerNow 到 roleContentDeadline 后封 ledger，全部封闭且 upperNow<freezePublishAdmissionDeadline 才准入 CONTENT_SNAPSHOT；lowerNow 到 effectiveOutcomeAt 无 freeze 则尝试 SYSTEM_FALLBACK。含 basis/effective 的 freeze 与 markers 原子，之后只读该唯一行，不复制投影。
- roleContentDeadline 到达时主动取消迟到网络线程；已持久的截止前 chunk 保留，截止后不写新正文 chunk，只在独立审计事件/指标中保存取消结果和 discardedLateBytes。
- 每个 outcome-affecting 事务执行方向固定的 TimeAuthority predicate、claim/epoch、workflow cursor、状态谓词和 content gate；截止后正文拒绝，取消/丢弃审计无 contentCommitNo，不能进 freeze。

### 16.5 重启恢复

| 持久状态 | 自动行为 |
| --- | --- |
| eligible manual 已提交，admission/linkage 均缺 | 先在 runtime guard 下唯一 ensure/read admission；只有结果为 MATERIALIZATION_PENDING/DEFERRED_CUTOFF 才以 admissionId ensure linkage=PENDING并唤醒，直接 FEATURE_DISABLED 等终态严格零 linkage |
| MANUAL admission=PENDING/DEFERRED、linkage 缺失 | ensure linkage=PENDING；不得因 admission 已有而跳过。CONTINUATION 永远没有 linkage，直接进入 source 状态机 |
| admission 为七个不可逆终态、linkage 缺失 | 原样返回终态；零 ensure、零 wake，重启/GET/两个after-commit handler均不得补建 |
| linkage 存在、admission 缺失 | 非法 identity：503/报警；禁止创建孤立 linkage 的生产路径 |
| MANUAL linkage=PENDING | GuardUse按runtime->storage->MANUAL_ATTESTATION_GUARD->admission->linkage建立REPEATABLE_READ snapshot，以READ_MODEL非锁定核验attestation/manual；只有仍可能解析时查询marker。成功RESOLVED，局部异常PENDING，deadline到达与source同事务FAILED；GET不执行本分支 |
| admission=MATERIALIZATION_PENDING/DEFERRED_CUTOFF、无 batch | 执行第 15.3 节唯一状态机；D 日 deadline 到达必失败，D+1 不改冻结字段、不复活 |
| admission=MATERIALIZED 或已有 committed batch | 只读唯一 batch并核验MANUAL既有linkage=RESOLVED；batch/linkage任一缺失或不一致为503 CORRUPTED_MATERIALIZATION，不补建；未决唯一事务500ms后仍不明则503 |
| SEALED materialization stage、source仍PENDING/DEFERRED | 重验同一stageHash后重新取得MATERIALIZE_BIND GuardUse；不重复写4MiB；不可逆source终态转RECLAIM_PENDING并由cleanup单次减账 |
| BOUND stage | 永久历史，只能由已提交batch解引用；cleanup零操作 |
| MATERIALIZED batch无freeze（无论raw role是否已终态） | BatchOutcomeDueCoordinator启动即按持久deadline恢复seal/content manifest/freeze/fallback；浏览器GET不是唤醒条件 |
| sealed ledger缺content manifest | manifest finalizer按acceptedThrough重算同一Merkle root并幂等插入；零正文/role/freeze修改 |
| batch 已有 committed outcome freeze、raw role 仍非终态 | 只读 freeze；零 claim、零 dispatcher wake、零 handoff、零 network。live raw role 只作 basis 外审计，不再由恢复器改写 |
| Role QUEUED | 可认领 |
| Role CLAIMED/RUNNING，租约过期且无 marker-nonnull 未知 operation | 新 epoch/recoveryMode=EXECUTION 从 workflow cursor 继续 |
| Role CLAIMED/RUNNING，租约过期且存在 marker-nonnull 未知 operation | 新 epoch/recoveryMode=UNKNOWN_FINALIZATION_ONLY；按两阶段合同收口 UNKNOWN，零生成/搜索 transport |
| Invocation/ToolExecution PERSISTED | 不重放；推进到 cursor 的下一安全步骤 |
| Invocation/ToolExecution FAILED_AFTER_SEND | 已知终态，零重放；只核验usage为SETTLED或UNKNOWN_CONSUMED，role/tool truth table继续收口 |
| Invocation PREPARED且无MODEL_INVOCATION reservation | 以原invocationId竞争唯一reservation+DISPATCHED原子事务；只允许一赢家 |
| Invocation PREPARED但已有MODEL_INVOCATION reservation | 503/CORRUPTED_MODEL_PREPARE并报警；零状态猜测、零外发 |
| Invocation/ToolExecution DISPATCHED，transportHandoffAt 为空 | 读取既有 reservation，在截止前重新过 egress gate；不重复预占 |
| paid admission attempt=WINDOW_BLOCKED、无operation | 不自动创建/派发；by-key保持404。仅用户在允许窗口以同key/hash重提POST时原子转OPERATION_BOUND，attempt_count不增 |
| paid admission attempt=OPERATION_BOUND但operation缺失，或operation存在但对应attempt缺失/身份不符 | 503/CORRUPTED_PAID_ADMISSION并报警；不补写、不发transport。生产新路径必须全有或全无 |
| paid operation=PREPARED 已commit-visible | 503/CORRUPTED_PAID_PREPARE并报警；零预算修复、零handoff。PREPARED只可存在于尚未提交的完整PREPARE事务 |
| paid operation+slot=DISPATCHED/WAITING，transportHandoffAt为空 | 只在原latestEgressPermitAt前把同一operation重新投递到专用executor；队列拒绝仍WAITING/零handoff，到线原子FAILED_BEFORE_SEND+RELEASED |
| paid operation+slot=ACTIVE，transportHandoffAt已有，进程恢复时原调用帧已丢失或responseDeadlineAt已到且结果未知 | 消费首个TERMINAL_CLOSER记录并原子OUTCOME_UNKNOWN+UNKNOWN_HELD，active_count不减；重启/超时不重发也不腾槽。原调用帧仍存活且未到线时只等待其有界响应，不由第二worker接管 |
| paid operation+slot=ACTIVE，operation=RESPONSE_RECEIVED或已有已提交chunk，responseDeadlineAt已到 | 消费首个TERMINAL_CLOSER并原子FAILED_AFTER_SEND/PAID_RESPONSE_DEADLINE_EXCEEDED、ACTIVE->RELEASED/count-1、usage/storage结算；零重发，禁止降级OUTCOME_UNKNOWN |
| paid operation 已PERSISTED/FAILED_AFTER_SEND但ACTIVE slot未释放 | 仅作为历史异常修复，按storage quota->storage reservation->paid guard->slot->BUDGET_GLOBAL->BUDGET_ACTOR->USAGE_RESERVATION->operation幂等完成ACTIVE->RELEASED/count-1与未用storage/预算结算，零transport；正常路径该组合不可提交 |
| paid operation=FAILED_BEFORE_SEND/BUDGET_EXHAUSTED + usage reservation=DENIED + slot=RELEASED + storage=SETTLED | 原样返回稳定429/终态DTO，零transport、零BudgetSettlement；两个请求JSON必须为空。四项任一缺失/状态不符是不可自动猜测的损坏事实，503/报警，不补写半套终态 |
| Invocation/ToolExecution/paid operation FAILED_BEFORE_SEND 且 reservation RESERVED/SETTLEMENT_PENDING | 零 transport；BudgetSettlementReconciler 按固定预算锁序幂等结算为零，operation raw 终态不回退 |
| Invocation DISPATCHED，transportHandoffAt 非空且本地结果未知 | UNKNOWN_FINALIZATION_ONLY -> OUTCOME_UNKNOWN，不自动查询或重发 |
| ToolCall RECEIVED | 读取唯一 reservation；无记录则完成预算接受或 DENIED+REJECTED 原子事务 |
| ToolCall ACCEPTED | CAS 冻结 DISCOVERY_FROZEN plan/稳定 executionId，推进 EXECUTING |
| ToolCall EXECUTING、尚无或缺少当前 plan execution 行 | 按当前冻结 revision 幂等插入缺失 PREPARED 行，不重复 logical 配额 |
| DISCOVERY_FROZEN 且全部第一阶段 parent 终止 | PERSISTED parent 贡献规范化目标、FAILED_BEFORE_SEND 贡献空集合；任一 TOOL_OUTCOME_UNKNOWN 直接终结 logical OUTCOME_UNKNOWN，否则以唯一 CAS 冻结含空集合在内的 FINAL_FROZEN；Tavily 结果不得贡献后续目标 |
| Tool result PERSISTED、continuation 尚未创建 | 用稳定 invocation ID 创建唯一 TOOL_CONTINUATION PREPARED |
| ToolExecution PREPARED且无reservation | 原子创建唯一reservation并按原executionId推进DISPATCHED；只允许一赢家 |
| ToolExecution PREPARED但已有reservation | 503/CORRUPTED_TOOL_PREPARE并报警；零状态猜测、零外发 |
| ToolExecution DISPATCHED、transportHandoffAt 为空 | 读取既有 reservation，以原 executionId 重过 AiEgressGate；gate 超界则 FAILED_BEFORE_SEND |
| ToolExecution DISPATCHED、transportHandoffAt 非空且结果未知 | TOOL_OUTCOME_UNKNOWN，不自动重发 Tavily/direct 请求 |
| 已收到 chunk、deadline 未到 | 只有原连接存活时继续；进程重启后标 INCOMPLETE，不重新生成已有段 |
| deadline 已到 | TIMED_OUT/PARTIAL/INCOMPLETE；advisoryUse 不升级 |
| 已提交 parent eligibility marker（RAW_ACCEPTED_TERMINAL 或 OUTCOME_FREEZE） | 只读，不自动覆盖；续讨论/父链只以不可变 marker.eligibleAt 视为已结束，effective 结论只读 outcome freeze |

“不重复收费”的验收限定为：重复 HTTP、双击、双实例、dispatcher 重试和系统自动恢复不会创建重复 source/batch/initial invocation/logical tool call/tool execution/reservation；供应商不支持幂等键的未知模型或 Tavily 结果采用 at-most-once 停止，而不是承诺无法实现的 exactly-once。

### 16.6 Schema 升级

apps/api/src/main/resources/schema.sql 是唯一 canonical additive schema，Spring 在 H2/PostgreSQL 启动时执行。infra/db/init/001_schema.sql 不再维护第二份业务 schema；空 PostgreSQL 也由 API 启动应用 canonical schema。

本次新增 AI 表、索引和约束；旧 scheduled 表保留，但删除 scheduler/service bean、写 store、cron/自动 scanRequest 配置与前端轮询，不做破坏性删表。历史 API 对旧行只读且强制 historical/non-executable。发布前：

- 备份 H2 api-data 卷或 PostgreSQL 数据库；
- 若升级基线中已有无 `requestAiDiscussion` 事实的兼容 manual 行，migration 只允许保守回填 `ai_user_requested=false,ai_requested=false,ai_admission_reason=AI_NOT_REQUESTED`；这些历史行永不生成attestation/source/linkage/batch，不得从代理token、当前feature或旧请求内容猜测用户意图。新表基线时该向量仍以0行通过；
- 在 H2 文件副本上从 a46cae9 升级并重启；
- 用 Testcontainers PostgreSQL 从当前 schema 基线升级，执行完整 DDL、复合 FK、CHECK、数据库时间 CAS 和双实例并发测试；
- Stage S 的 ACCEPTED SHA 是本功能最小应用回滚基线；后续只能回滚到S或更新版本并保留新增表，不做破坏性down migration。普通发布禁止回滚到仍含ShortTermScanScheduler的a46cae9。灾难恢复若必须运行pre-S，必须先启用该旧版确实识别的外部scheduler kill switch；若旧版没有可靠kill switch，应用保持停止直到S恢复，不能用“保留新表”冒充自动任务关闭；
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
| GET | /rounds/{roundId}/events?cursor=&limit= | 按冻结 scope 与跨 stream 全序读取完整事件时间线 |
| GET | /rounds/{roundId}/evidence?cursor=&limit= | 分页读取证据与受限 excerpt |
| GET | /stocks/{symbol}/history?cursor=&limit= | 分页获取同股全部历史轮次 |
| POST | /stocks/{symbol}/continuations | 从指定父轮次继续讨论，返回 202 |

现有 POST /api/short-term/scan-jobs 增加必填 Idempotency-Key 请求头，canonical Java/OpenAPI/TypeScript request 同时增加必填布尔 `requestAiDiscussion`；缺失不是false而是400。React 主按钮“扫描并讨论”与“重新扫描并讨论”固定发送 true，服务器绝不根据代理token自行推断该字段。GET scan job 状态增加 persistentResultId、aiUserRequested、aiRequested、aiAdmissionStatus、aiBatchId、aiReasonCode 和每候选 aiSelectionStatus。新增 GET /api/short-term/scan-jobs/by-idempotency-key/{key}，只在通过内部身份后按固定 actor=local-operator 查询原持久任务，支持 POST 响应丢失/刷新恢复。

新增受内部身份保护的 `GET /api/ai/paid-operations/by-idempotency-key?routeCode=&key=`，为旧 `TREND_ANALYSIS` 和 `AGENT_CONSENSUS` 客户端恢复丢失的 POST 响应。服务端固定 actor=local-operator，校验 routeCode 白名单；不存在返回 404，进行中返回 202，PERSISTED 返回 200 和完整原业务响应，FAILED_BEFORE_SEND/FAILED_AFTER_SEND/OUTCOME_UNKNOWN 以200返回不可自动重放的终态DTO（其中BUDGET_EXHAUSTED不把只读GET变成429）。查询结果含 operationId、routeCode、deliveryStatus、requestSemanticHash、errorCode、createdAt/completedAt，不回显 normalizedRequestJson 中可能的敏感业务字段；预算拒绝行本来就不持久化两个请求JSON。

旧 /api/short-term/scheduled-snapshots/latest 保留为只读兼容：只查询当前 market date 已存在的旧行，无记录返回 204，不再合成“等待自动预选”、不再执行 expire/attest 写回；有记录也包装为 automationDisabled=true、historical=true、executable=false，不能成为当前买点。新增只读 GET /api/short-term/scheduled-snapshots/history?tradeDate=&stage=&cursor=&limit= 用于明确的历史诊断；两个端点都不得产生、修复、认证或推进 snapshot。

### 17.1 读取

- 所有 GET 都是业务只读、`Cache-Control:no-store`：禁止ensure admission/linkage、wake worker、调用SourceMaterializationStateMachine、创建freeze/fallback、写event/budget/storage/source/batch/linkage。允许普通SELECT与GuardUse.evaluate推进TimeAuthority高水位，但evaluate不插ticket/app_guard_use或改AI业务事实。eligible manual尚无source返回`202 AI_STATE_RECONCILIATION_PENDING/Retry-After:1`；source截止已到但终态未提交、effectiveOutcomeAt后freeze未提交或唯一赢家未决均503 PERSISTENCE_UNAVAILABLE。恢复唯一写入者是after-commit creator、ManualAiIdentityReconciler、UserSourceDueCoordinator、linkage worker、materializer与BatchOutcomeDueCoordinator；人工催办未来另设内部身份+Idempotency-Key POST。
- `GET /batches/by-source` 只校验并读取已提交source；七个不可逆终态原样返回，MATERIALIZED只读committed batch且MANUAL必须已有RESOLVED linkage，缺失为503 CORRUPTED_MATERIALIZATION；PENDING/DEFERRED只投影持久事实，不催办。SourceOutcomeV1 的MATERIALIZATION_PENDING仅在evaluate证明PRE时可返回，TIME_UNCERTAIN固定503；到线未收口503，不能以GET推进状态。
- 所有batch/round outcome GET固定freeze-first：先用READ_MODEL按batchId读committed freeze；存在时核验freezeRevision/basisHash/batch identity后直接200最终DTO，不调用GuardUse.evaluate，TimeAuthority health=false/raw回退/watchdog停止均不得遮蔽。仅freeze缺失时执行PRE/TIME_UNCERTAIN/POST投影；准备返回PENDING/503前再读一次freeze，并发赢家已提交则优先返回freeze。freeze损坏仍503。BatchSummaryV1返回rawExecutionStatus、outcomeFinal、freezeRevision、basisHash、effectiveStatus/effectiveOutcomeAt；`outcomeFinal=true`当且仅当committed freeze通过校验。PRE才可返回活跃raw，TIME_UNCERTAIN 503，POST无freeze503；live raw只作basis外审计。
- OutcomeBasisV1/basis_json 是内部可重复验证事实，不由公开讨论API直接返回；公开端只返回basisHash、effective字段和经第7.5节投影的内容引用，不能借basis中的INTERNAL_POLICY_AUDIT conceptCodes绕过隐藏策略。受限运维导出若读取basis必须走独立权限与审计。
- RoundDetailV1 返回 candidate snapshot、四个 opinion manifest/detail URL、invocation 元数据、证据 page URL、父轮次和变化摘要；结构化长内容、原始 chunk、事件和证据通过各自 cursor 接口字节保真读取，避免把最多 256 MiB 内容装进单个 JSON 响应。字节保真只适用于已被短线合同接受的公开内容；第7.5节 INTERNAL_POLICY_AUDIT/VALUATION_CONTENT_REJECTED 对公开端只返回hash、byteCount和通用`SHORT_TERM_POLICY_CONTENT_WITHHELD`，绝不返回内部conceptCode、PE/PB/估值正文或数值。
- HistoryPageV1 的 item 只含时间、状态、角色计数、父轮次和 detail URL 等列表摘要，不触发额外模型调用，也不假装是完整原文；点击详情一定能读取未截断内容。
- raw chunk/opinion content页面每页最多100chunk且编码后最多4MiB，event/evidence页面每页最多200项且编码后最多2MiB；raw/opinion/evidence使用各自冻结稳定键，event使用第15.15节`(batchId,timelineNo)`签名cursor，并保持完整item/chunk边界。raw cursor表示最后一个已公开定性的sequenceNo，PENDING永不推进cursor：服务端只返回cursor后的最大连续PUBLIC/INTERNAL前缀；前缀非空返回200/nextCursor=最后定性项，并在下一项PENDING时标`blockedByPolicyPending=true,hasMore=true`；第一项即PENDING返回202、items=[]、retryCursor=原cursor、pending hash/byteCount与Retry-After:1，不返回nextCursor/正文；INTERNAL withheld metadata可推进。sequence缺口503 CORRUPTED_CHUNK_SEQUENCE。React遇202/blocked只重试同一未决cursor。达到其他项数或字节页上限时返回nextCursor，不能substring或伪装结尾。
- API 不截断结构化 opinion、证据或按 chunk 重建的已接受原始响应；单次 HTTP 有界不等于业务内容被丢弃。估值隔离是显式策略拒绝并返回可追溯占位，不得实现为substring/省略号，也不能以“不截断”为由泄露拒绝正文。
- 历史按 createdAt DESC、roundId DESC 稳定排序；cursor 是这两个字段签名后的不透明值。limit 默认 20，最小 1，最大 100。
- 活跃任务响应包含 completedRoles、totalRoles、startedAt、deadlineAt 和 lastEventAt。
- 所有错误字段使用稳定 errorCode 与可读中文 message。
- 固定 HTTP 语义：400 参数/Schema，401 专用付费/配置端点未通过本地代理身份，403 权限，404 不存在，409 幂等载荷冲突，429 状态改变POST的持久预算/频率超限，503 AI 功能或供应商不可用；by-key只读恢复按第17节以200返回已存在预算拒绝终态。scan-jobs 的确定性扫描是例外，按第 17.3 节降级而不是因 AI 身份返回 401。

### 17.2 继续讨论

请求包含：

- parentRoundId；
- question，可空；规范化后canonical UTF-8最多4096字节，超限400且零source/预算/存储准备行；
- 必填 Idempotency-Key 请求头；body 不再复制第二份 key。

校验：

- symbol 与父轮次一致；
- 父轮次存在且已有 committed parent eligibility marker；raw 仍 RUNNING 但不可逆 freeze 已插 OUTCOME_FREEZE marker 时必须允许继续讨论；
- 同一 idempotencyKey 不重复收费或生成轮次；
- 问题只作为研究补充，不能要求改变确定性动作；
- question在任何Idempotency/guard/source/budget/storage/event/log行之前经过第7.5节同一策略；命中固定400 SHORT_TERM_QUESTION_POLICY_REJECTED、零持久化/外发，只有CLEAR才计算fingerprint并保存；
- 服务端限制并发继续任务数量和调用频率，避免误点或接口滥用。
- idempotencyKey 的作用域为 actorId + symbol + parentRoundId；相同 key 不同 questionFingerprint 返回 409。
- 14:47:00 至 14:50 返回 503 AI_EGRESS_CUTOFF、Retry-After 和可读提示，不创建 admission、不扣预算；14:50 后同 key/同 body 可安全续办这次用户操作。

### 17.3 访问控制

本版明确选择“本机单用户 + 可信反向代理”边界，不引入全站账号系统：

1. 发布 Compose 只把 React Web 绑定到 127.0.0.1；API 不映射宿主机端口，只接受 Docker internal network 的 Web 代理。
2. Web 与 API 只读挂载同一 /run/secrets/ai_internal_token。Web entrypoint 读取 secret 并通过官方 Nginx template envsubst 生成 proxy_set_header；客户端传入的 X-AI-Internal-Token 被丢弃，代理重新设置唯一的 secret 值。
3. API 通过 Spring config tree 读取同一 secret，并用常量时间比较验证 token；未配置 token 时 AI discussion feature 强制关闭。
4. POST continuation、未来任何 AI retry、POST /api/ai/trend-analysis、POST /api/companies/{symbol}/agent-consensus/ai 和运行时模型配置 PUT 必须有该内部身份，否则 401。POST scan-jobs 本身仍允许完成确定性扫描；只有请求体 `requestAiDiscussion=true`、内部身份有效且本地 feature snapshot 开启时才冻结 `aiRequested=true`。用户请求true但缺失/错误 token时扫描照常返回，持久保存`aiUserRequested=true,aiRequested=false,reasonCode=AI_PROXY_IDENTITY_UNAVAILABLE`；用户明确false固定AI_NOT_REQUESTED。代理token不能把false改true，AI鉴权也不能改变确定性扫描 payload/status。已取消所有 scheduled AI actor。
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

不得重新引入此前已移除的“手动扫描执行中/完成”顶部状态页卡；扫描与 AI 完成/失败继续通过右上角浮动消息反馈，候选内只展示与该候选相关的 AI 状态。手动扫描被95%门禁阻断时，右上角消息必须直接显示“手动扫描未生成结果：全市场行情覆盖率低于95%”及稳定码`COVERAGE_BELOW_95`，可展开查看实际覆盖率/数据截止，但不得降低门禁、伪造候选或启动AI；其他blockedReasons同样由稳定码映射可读文案，不能只显示“扫描没结果”。

React 在第一次 POST 前生成 Idempotency-Key，并把 key、包含必填requestAiDiscussion的规范化生效请求体、clientRequestFingerprint、operationType、createdAt 写入 sessionStorage；clientRequestFingerprint 由 Stage 0 生成的共享 canonicalizer 和跨 Java/TypeScript 测试向量计算，只用于客户端自检，服务器返回的 requestSemanticHash 才是权威值。收到 202 后再补服务器 hash、persistentResultId/jobId。网络重试复用同一 key 和原请求体；响应丢失或刷新先用 by-idempotency-key 查询，404 才以原请求体重放 POST，不能用当前表单或默认值。直到读到持久manual终态才清理这条“POST恢复记录”。用户在终态后明确再次点击才生成新 key。继续讨论同样保存 symbol、parentRoundId、question 和 key，不能在 axios 自动重试时生成新 key。

POST恢复记录与后台结果通知是两套职责。应用根节点新增唯一 `AiDiscussionNotificationWatcher`：manual终态先按第8.1节判定是否具备source资格；数据阻断、FAILED/TIMED_OUT、NO_TRADE、空候选或其他`aiAdmissionReason=NOT_APPLICABLE`即使`aiUserRequested=true`也只按持久manual reason弹右上角扫描/AI未启动消息并不创建watch。其余合格结果若`aiUserRequested=true,aiRequested=false`，立即按持久aiAdmissionReason弹“AI讨论未启动”且不创建watch；`aiUserRequested=false`既不弹AI消息也不创建watch。只有合格结果且`aiRequested=true`时，才在清理POST记录前向localStorage `ai-stock.short-term.ai-watch.v1`写最小 `AiDiscussionWatchV1={schemaVersion,resultId,sourceType,sourceKey,createdAt,lastState,nextRetryAt,batchId,finalKey,notifiedAt,acknowledgedAt}`，不保存扫描请求、问题或模型正文。active与已终结但用户尚未点击/关闭的记录不得被路由切换、刷新或浏览器重启清除；用户点击消息进入讨论页或明确关闭后才写ack并清理。已ack记录可立即删除，损坏记录隔离并给一次通用恢复提示，不能触发POST。watcher只调用第17.1节GET，永不ensure/wake/retry POST或创建source。

watcher状态机唯一固定：`202 AI_STATE_RECONCILIATION_PENDING`或MATERIALIZATION_PENDING在应用可见时最多1秒一次、隐藏时最多15秒一次；DEFERRED_CUTOFF保留记录并在`nextRetryAt`或应用重新可见时立即恢复source GET，期间不伪装失败；MATERIALIZED取得batchId后改轮询freeze-first batch GET；七个无batch source终态按`sourceKey+status+reasonCode`作为finalKey弹可读失败/未启动消息；committed freeze按`batchId+basisHash`作为finalKey弹完成/部分/超时/失败消息。稳定503保留记录和最后画面并按Retry-After重试。消息始终位于右上角、可点击；同finalKey恰提示一次但在ack前刷新仍恢复同一消息。详情页可以额外读取数据，却不拥有或停止全局watcher。

活跃 batch/round 轮询使用可见页面固定 1,000ms 上限；页面隐藏可降频，但重新可见时立即请求。raw role终态可停止相应角色进度动画，raw batch/round终态仍必须继续outcome轮询；只有响应`outcomeFinal=true,freezeRevision=1`才停止并在右上角弹出最终消息。raw终态到freeze之间显示“角色已结束，正在固定最终结果”，SYSTEM_FALLBACK可把此前provisional共识改为INCOMPLETE。最终消息以`batchId+basisHash`去重且只由freeze触发；稳定503保留最后画面并继续恢复，不提前宣告成功/失败。前端 SLO 监控记录 effectiveOutcomeAt、首次最终响应和消息显示的 monotonic latency。

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

- 进入活跃讨论后定时查询 batch/round；应用根节点的AiDiscussionNotificationWatcher独立观察所有未ack后台任务；
- 不再轮询 scheduled-snapshots/latest，也不渲染 ScheduledScanPulse/ScheduledSnapshotStatus；历史 scheduled API 只供兼容/诊断页按需读取。
- 页面离开只停止该详情组件自己的batch/round数据轮询，不停止全局watcher；详情与watcher都只有`outcomeFinal=true`后才停止该batch outcome轮询，raw终态不满足停止条件；
- freeze确定的完成、部分完成、超时和失败都在右上角显示浮动消息；
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
3. 只有读到 PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND 或 OUTCOME_UNKNOWN 终态后才清理本次恢复记录。三类FAILED/UNKNOWN均不使用原 key 自动再发；尤其 deliveryStatus=FAILED_BEFORE_SEND、errorCode=FAILED_BEFORE_SEND_CUTOFF 表示已有 operation 在 transport gate 被拒绝，用户明确再次点击才是新操作、使用新 key。
4. 14:47:55 至 14:50:00 若入口在创建 operation 之前返回 503 AI_EGRESS_CUTOFF，客户端保留 key/body，用同 key 查询必须为 404；14:50 后可由用户确认或一次性恢复机制完成该 key 的首次提交。若查询命中 FAILED_BEFORE_SEND_CUTOFF，则不属于这种未创建情形，必须按第 3 条以新操作/新 key 处理。两种恢复都源自明确的用户操作，不是定时短线扫描。
5. Java/TypeScript 对两个 route 使用共享 canonical test vectors；同 key 不同语义载荷必须 409，客户端 fingerprint 只做本地防错，服务端 requestSemanticHash 才是权威。

## 19. 模型配置与密钥安全

- materializer 为整个 batch 一次性锁 `short_term_ai_runtime_guard`，核验 source.runtimeGuardRevision/enabled/storageState 后读取同事务 runtime_config_section 的 LLM revision 和 LlmSettings；四个role共享同一份不可变非密钥快照，guard持有到batch/source commit，避免角色启动先后混用或配置PUT检查后穿透。
- manual result 冻结的 featureRevision 只是本地只读 snapshot 上的保守 AI 意图，不是“提交当时已获数据库授权”；after-commit source creator 只有在锁住 runtime guard 且 enabled/revision 匹配时才能创建 PENDING。之后 materializer、CONTINUATION admission和每次新 transport handoff GuardUse 都把同一runtime guard行置于全局第一锁位，并持锁到source/batch/handoff marker commit；配置PUT与存储水位状态转换也锁该行。PUT先提交时，enabled=false 固定以 source status=FEATURE_DISABLED/reason=AI_FEATURE_DISABLED 或 operation errorCode=AI_FEATURE_DISABLED 收口；仍 enabled 但 revision 变化固定使用 FEATURE_REVISION_CHANGED；storageState=HIGH_WATER 固定使用 STORAGE_PRESSURE/STORAGE_HIGH_WATER。三者都零新marker。operation marker先提交时才属于第8.3节不可撤销授权。未handoff角色不会悄悄改用新revision。已提交durable handoff的原Gate调用帧仍可能发送一次，关闭flag不是已授权请求的kill switch；其响应只按CONTENT_APPEND GuardUse保存。终态后重开feature也不自动复活，用户需重新手动扫描或明确继续讨论。
- batch/role 保存 llmConfigRevision、provider、model、baseUrl、能力版本和非密钥设置，不保存 API Key；页面据此可证明实际使用的模型。
- 每次新 handoff 前重新解析凭据并要求 LLM revision 仍等于 batch/operation 快照。若配置已变化，尚未 handoff 的 role/operation 以 CONFIG_REVISION_CHANGED 失败，不能悄悄改用新模型；已提交 handoff 的调用继续使用该 Gate 帧已解析的不可变凭据/请求快照。用户可基于父轮发起新讨论使用新配置。
- 新配置值只被之后创建的新 batch/paid operation 采用；revision 漂移同时会 fence 既有 batch 尚未 handoff 的 operation，但不改写该 batch 已冻结的 revision，也不撤销已提交 handoff。
- API Key 环境变量只能从 Provider 固定 allowlist 名称通过可测试的环境访问器解析；生产实现最终只读 System.getenv，不能用用户可控名称查询整个 Spring Environment。
- 模型 Base URL 只能使用 Provider 对应的固定官方 HTTPS 地址，不能指向用户任意服务器。
- MODEL、TAVILY 与旧 PAID 的官方端点 HttpClient 固定 redirect=NEVER、禁用系统/环境代理和客户端自动重试；任何3xx都不跟随且绝不把Authorization跨host转发，但它发生在物理handoff之后，绝不能写FAILED_BEFORE_SEND。response sink在一个原子业务事务内按合法内部边先填充最小响应事实（MODEL/PAID=`RESPONSE_RECEIVED`，TAVILY=`RESULT_RECEIVED`，保存statusCode、Location值的SHA-256、receivedAt；Location明文、响应body、URL和凭据均不进入该事实），再写`FAILED_AFTER_SEND/PROVIDER_REDIRECT_REJECTED`，仅最终终态对其他事务可见；usage可证则同事务SETTLED，不可证则UNKNOWN_CONSUMED，零自动重试。旧PAID还必须同事务ACTIVE->RELEASED/count-1并结算未用storage。受限direct fetch仍只使用逐跳手动重定向、每跳DNS/IP/host重验的独立client，不能复用带模型密钥的client。
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
| health正常但deadline落在lower/upper不确定带 | 503/TIME_AUTHORITY_UNCERTAIN、Retry-After:1；零业务mutation、零PENDING DTO，后续重新取时 |
| gate 在 transport 前拒绝后进程崩溃 | operation 已是 FAILED_BEFORE_SEND 且 reservation 为 SETTLEMENT_PENDING；reconciler 零外发并幂等释放预算 |
| 并发 gate 已有 transportHandoffAt 赢家 | 输家 ALREADY_HANDED_OFF，零发送且不释放赢家 reservation |
| DISPATCHED 且 transportHandoffAt 已持久后进程崩溃，供应商无幂等键 | OUTCOME_UNKNOWN，不自动重发；用户可新建继续讨论 |
| 工具参数非法 | TOOL_REJECTED，模型可在剩余轮次修正 |
| 短线query/工具结果/模型文本命中估值隔离 | query固定VALUATION_QUERY_FORBIDDEN且零外发；result/content固定INTERNAL_POLICY_AUDIT或VALUATION_CONTENT_REJECTED，公开端只给withheld hash/reason且不参与聚合 |
| 搜索未配置/超时 | SEARCH_UNAVAILABLE，角色使用本地证据并列出缺口 |
| 搜索结果越权域名或时间 | 保存 rejectionReason，不回传为可用证据 |
| 结构化解析失败 | 通过 chunk 接口保留并可完整读取已接受raw response，角色 INCOMPLETE；估值策略拒绝chunk仍只给withheld hash/reason |
| finishReason=length | 保存片段；有时间则一次续写，否则 INCOMPLETE |
| source/linkage after-commit 任一事件丢失 | reconciler/due coordinator先ensure/read admission；仅PENDING/DEFERRED补linkage，终态零补建，MATERIALIZED缺既有RESOLVED linkage报损坏；GET只读且不影响manual |
| linkage parent 查询局部失败 | 回滚查询事务；linkage 保持 PENDING，仅记录 attempt/PARENT_QUERY_FAILED 并在唯一 deadline 内重试，禁止复制 null 父链；到线 source+linkage 同事务失败 |
| linkage store 整体不可达 | 当前投影 503 AI_LINKAGE_STORE_UNAVAILABLE；恢复后只在 admission 唯一 materializationDeadlineAt 前解析，过线 LINKAGE_DEADLINE_EXPIRED |
| materializer 暂时失败 | 仅在冻结 materializationDeadlineAt 前有界重试，不重置 sourceOutcomeAt/deadline |
| materializer 进入尾盘黑窗 | 仅符合一次延期资格时 MATERIALIZATION_PENDING->DEFERRED_CUTOFF，固定 D14:50/D14:52 |
| materialization deadline 到达仍无 batch | MATERIALIZATION_FAILED/MATERIALIZATION_DEADLINE_MISSED，不跨日复活 |
| admission/batch 唯一赢家未决或数据库不可用 | 当前读 503/PERSISTENCE_UNAVAILABLE；恢复后按原冻结期限决议 |
| 模型发送前数据库短暂失败 | 仅PREPARED+零reservation可按原ID有界重试原子预占；PREPARED已有reservation视为损坏503 |
| 模型/工具响应已知但本地资源、解析或持久化失败 | invocation/toolExecution=FAILED_AFTER_SEND；usage可证则SETTLED、不可证则UNKNOWN_CONSUMED；role INCOMPLETE，零重发，batch storage/slot只由freeze释放 |
| MODEL/TAVILY/旧PAID 官方端点返回3xx | redirect=NEVER且零跟随；同一原子收口内写RESPONSE_RECEIVED/RESULT_RECEIVED最小事实并最终FAILED_AFTER_SEND/PROVIDER_REDIRECT_REJECTED；绝不伪装FAILED_BEFORE_SEND，旧PAID同时释放ACTIVE槽 |
| 旧付费已提交RESPONSE_RECEIVED或partial chunk但到responseDeadlineAt仍未PERSISTED | FAILED_AFTER_SEND/PAID_RESPONSE_DEADLINE_EXCEEDED；usage可证SETTLED否则UNKNOWN_CONSUMED，ACTIVE->RELEASED/count-1；只有零receipt且结果/ack确实未知才OUTCOME_UNKNOWN+UNKNOWN_HELD |
| 模型发送后结果或本地提交确实未知 | 不自动重发；OUTCOME_UNKNOWN/TOOL_OUTCOME_UNKNOWN，不能用UNKNOWN掩盖已知本地失败 |
| materialization canonical artifact超过4MiB | source=MATERIALIZATION_INPUT_RESOURCE_LIMIT/MATERIALIZATION_INPUT_RESOURCE_LIMIT；零stage/storage/slot/budget/batch，已有linkage/pending slot原子收口，不自动重试 |
| sealed content manifest缺失/损坏或basis超过512KiB | 正常CONTENT_SNAPSHOT拒绝；effectiveOutcomeAt由SYSTEM_FALLBACK收口，不裁剪basis |
| 旧付费响应超过32MiB、存储HIGH_WATER或已知持久化失败 | 零超额chunk；以FAILED_AFTER_SEND/稳定errorCode终结，释放并发槽与未用额度，已收chunk只作审计不返回为成功业务结果 |
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
- 手动扫描提交/完成时间、FINAL_PENDING/FINAL_READY 认证时间、14:49:30 认证准入结果和 14:49:40 executionEligibility 结果；
- TimeAuthority watchdog 最后健康样本 age、冻结/回退/过期次数、health=false 时长、恢复probe次数、instanceId/boot lease剩余时间、重复slot拒绝与接管次数；
- TimeAuthority durableRawHighWater/durableGuardHighWater/authorityEpoch、按 workloadClass 的 GuardUse 成功/窗口拒绝/业务锁拒绝/authority statement 竞争/超时/零行/坏 proof/commit unknown 计数和 p50/p95/p99 延迟、orphan ticket/ABANDONED 数量，以及 DETERMINISTIC 保留连接重试/耗尽次数；
- 发布启动的server_version、max/superuser_reserved/reserved_connections、四应用角色与全部其他LOGIN角色的权限/CONNECTION LIMIT、declaredOtherOrdinaryRoleCapTotal、declared replicaCount与逐pool min=max实际预热结果、5条operator reserve证明，以及AI feature fail-closed原因；
- AI_RUNTIME_GUARD 的 PUT/materializer/egress 等待时间、revision 拒绝和 marker-before-PUT/PUT-before-marker 线性化计数；
- OUTCOME_FINALIZER 从 effectiveOutcomeAt 到 executor 开始/持久决议的 monotonic p50/p95/p99/最大值，以及前端首个终态响应/右上角消息可见延迟和 250ms/500ms/1,750ms SLO 超标数；
- ShortTermScanScheduler 注册任务数必须为零、旧 scheduled snapshot 新增行数必须为零；
- 重启恢复和租约抢占数量。
- PREPARED/DISPATCHED/OUTCOME_UNKNOWN invocation 数量；paid admission的每日attempt_count/500拒绝/WINDOW_BLOCKED age与转OPERATION_BOUND数量；paid operation的WAITING/ACTIVE/RELEASED/UNKNOWN_HELD slot数、age、active_count、executor拒绝、FAILED_AFTER_SEND与显式reconciliation计数；
- discussion/continuation batch 的 actor/source-day 用量、continuation admission/pending-source 占用和 20/10/2 拒绝数、actor activeBatchCount/slot age 与 4 上限拒绝/释放数，以及 model invocation、Tavily request/credit 的 batch/actor/day 用量；
- SETTLEMENT_PENDING reservation 数量、最老 age 与幂等结算成功/失败数；
- materialization gap 和 reconciler 修复数量；
- content gate 拒绝正文写入数量、discardedLateBytes/discardedResourceLimitBytes、每 invocation acceptedResponseBytes/每 batch acceptedRawBytes 与 32MiB/256MiB 配额拒绝数、content seal 成功/超时及 transport handoff 延迟数量；
- AI feature、存储水位和预算熔断状态；storage provisioned/logical capacity/accounted used/reserved remaining、每owner/bucket已用/未用、最旧未结算reservation、HIGH_WATER/NORMAL revision转换与探针失效原因；
- AI 故障注入前后确定性 payload hash 是否一致。

日志使用 batchId、roundId、roleRunId 和 correlationId 关联，不记录完整 Prompt、密钥或 Authorization。

## 22. 测试策略

### 22.1 短线去估值

- preliminaryScore 不调用估值计算器。
- PE/PB 极值、空值和异常值的完整变形测试，覆盖 todayAdvice、evidenceCompleteness、tradePlan、validation/calibration、观察样本、历史和所有 identity hash。
- K 线复核池顺序与 PE/PB 无关；只变 deprecated 参数时 snapshotKey 与 batch identity 不变。
- 当前版本 AI 快照与提示不含 PE/PB。
- `ValuationIsolationPolicyV1` golden vectors覆盖PE/PB/P E/P.B/全角/三轮HTML entity/大小写/中文市盈率市净率估值、Default-Ignorable/零宽拆词、Bidi_Control、UTS #39 skeleton、`PΕ`、`РВ`、Latin/Greek/Cyrillic混写/纯同形以及不应误伤的普通英文和普通多语种非金融文本；URL另覆盖host Unicode显示、path/query key/value/fragment一至三轮percent decode、非法编码和第四层嵌套。把`P`/`&sol;`/`E`与中文token拆到三个64KiB chunk仍必须让承载命中跨度的全部chunk被carry scanner拒绝，PENDING_POLICY期间公开GET只得202/hash，终态后零PENDING。故障注入让role terminal/freeze与仍PENDING的scanner竞争时，角色终态必须等待或以资源/策略失败收口，CONTENT_SNAPSHOT绝不读取live visibility补猜；PUBLIC与INTERNAL两种sealed content manifest/Merkle root的跨H2/PG basis/hash向量固定且不同。任一完整且Schema合法的原生TOOL_ARGUMENT query命中都固定ROLE_QUERY：即使参数唯一绑定股票A，也让所属role在同batch全部最多八只股票固定TAINTED_QUERY/null/VALUATION_QUERY_FORBIDDEN，股票B至H不得qualified；其他三个role不被跨role污染。CONTENT/REASONING、severeRisks.claim、结构化正文以及不完整/无法归属原生argument stream的TOOL_ARGUMENT命中均升级ROLE_OUTPUT并让该role全部symbol固定TAINTED_RAW_OUTPUT。两个原生tool call以chunk交错、provider ID缺失/重复、query跨chunk形成时分别按native ordinal/stream key重建，不能把carry串到另一stream或把命中缩回参数symbol。先提交结构干净opinion、后分类出reasoning中的PE/PB必须被OPINION_FINALIZATION门禁阻止。逻辑预算已满时提交估值query仍必须留下taint+零额DENIED且零external。`search_trusted_stock_evidence`与`search_discussion_history`命中query在有余额时只消费一次逻辑call、余额已满时零增量，两者均零外部execution。Tavily/direct title/snippet/excerpt/sourceName/URL各surface、旧历史、父轮预载和模型结构化输出分别注入估值内容：整条provider evidence只能得到无原文/URL的INTERNAL_POLICY_AUDIT，历史与所有公开DTO/DOM只见SHORT_TERM_POLICY_CONTENT_WITHHELD；模型可见tool data、qualified opinion、公开raw/evidence中均无内部concept code、原文或数值，长期页面仍完整显示。冻结Unicode/confusables resource revision或SHA不匹配时feature readiness fail closed且零AI外发；CONTINUATION question在4096-byte内逐项注入同一绕过向量，固定400且source/guard/idempotency/budget/storage/event/log正文和所有外发均为零；随后同key提交合法question仍是首次持久操作。
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
- 聚合golden vectors逐symbol覆盖：4个INSUFFICIENT、2个qualified+2个INSUFFICIENT均固定INCOMPLETE；3个SAME_DAY qualified SUPPORT才可CONSENSUS_SUPPORT；把第三票改成只含RESEARCH_ONLY证据后必须INCOMPLETE；一个RESEARCH_ONLY高置信OPPOSE既不能阻止三个SAME_DAY SUPPORT也不能触发RISK_ALERT；RESEARCH_ONLY batch则按研究用途正常计票。两库OutcomeBasisV1的qualifiedOpinionStates字节/hash完全一致。
- 四个角色共享同一 LLM revision/provider/model；DISPATCH 前 revision 改变时未发送角色明确失败且不会混用新模型。
- 四角色全失败时确定性结果不受影响。
- 队列满仍保留 durable QUEUED；dispatcher 可恢复。
- 无 AI 身份的确定性降级、双击、响应丢失重试、相同 key 不同生效 payload 和双实例并发；只改变 deprecated PE/PB 仍读取同一持久结果且不生成重复讨论。三类POST的Idempotency-Key按ASCII 1/128/129字节、Unicode与非法字符覆盖，非法固定400且零row/ticket/log key；CONTINUATION question按UTF-8 4096字节-1/等号/多1字节覆盖，多1字节固定400且零source/guard/budget/storage行，不在代码点中间截断。
- 手动矩阵覆盖交易/非交易日、缓存预览、研究预览、数据阻断、空候选，以及 14:29:59.999、14:30:00、14:46:59.999、14:47:00、14:47:19.999、14:47:20、14:47:54.999、14:47:55、14:48:29.999、14:48:30、14:48:43.999、14:48:44、14:48:45、14:49:30、14:49:40、14:50:00、15:00:00 与次日重启；逐项断言 deterministicFinalizedAt/sourceEffectiveAt/certificationKind/sourceCertifiedAt、SAME_DAY 仅 FINAL_READY_CERTIFIED、initial dispatch、egress permit、content acceptance/publication/freeze、materializedAdvisoryUse 和同 key 恢复。数据阻断、扫描FAILED/TIMED_OUT、NO_TRADE与空候选再跨两个handler、reconciler、GET、14:50和重启，始终只读manual.aiAdmissionReason，source/linkage/batch/usage行数全为0。
- materializer 的真实H2/PostgreSQL准入SQL谓词分别对当日14:50、15:00新建source，以及次交易日13:00、15:00新建source放行RESEARCH_ONLY；[14:47,14:50)精确全部拒绝。D日遗留source只有在lowerNow>=其冻结materializationDeadlineAt时才MATERIALIZATION_FAILED/零batch；D23:59:30 source在D+1 00:01:30前仍可一次物化，等号失败。用户对已过期source次日研究只能显式新建CONTINUATION。断言materializationAdmissionCutoff只参与SAME_DAY额外谓词，不复活过期source。
- `SourceMaterializationStateMachine` 对BEFORE_WINDOW的显式向量：source=14:45:10、处理=14:45:10时具延期资格但必须从第7步落到第8步并立即materialize；FINAL_READY_CERTIFIED写SAME_DAY_RESEARCH，NON_EXECUTION_SOURCE写RESEARCH_ONLY。source=14:46:30同样立即materialize，不得预写DEFERRED_CUTOFF/D14:50/D14:52；只有真正CLOSED_WINDOW才消费延期资格。
- source 公式固定向量：10:00->10:02 且不延期，14:44:59->14:46:59 且不延期，14:45->14:47 可延期，14:46:59.900->14:48:45 可延期，14:47/14:49:59 立即 DEFERRED(D14:50/D14:52)，14:50->14:52、15:00->15:02 可立即 RESEARCH_ONLY。精确 source/deadline 等号失败，不在等号建 batch。
- 对sourceOutcomeAt、materializationDeadlineAt、effectiveOutcomeAt分别注入`lower=deadline-100ms,upper=deadline+250ms`：状态机/读API均零mutation且固定503/TIME_AUTHORITY_UNCERTAIN，绝不返回PENDING/freeze；React保留最后画面、Retry-After后继续轮询。下一样本lower精确到deadline时，source或freeze唯一赢家收口。
- 方向边界逐项断言：lower=14:46:59.700、upper=14:47:00.050时CLOSED_WINDOW；lower=14:49:59.999999即使upper>=14:50仍CLOSED_WINDOW；lower=14:50:00才AFTER_WINDOW。跨午夜lower=D23:59:59.900/upper=D+1 00:00:00.250以windowDate=D只能AFTER、businessNow=lower、source/budget日=D；D+1 00:00新source以windowDate=D+1只能BEFORE。D日PENDING/DEFERRED在最终deadline后的D+1恢复冻结时间不变、MATERIALIZATION_FAILED、零batch/调用。
- 唯一状态机能力矩阵在BEFORE/CLOSED/AFTER三支分别让feature关闭、runtime revision变化、LLM配置无效、storage高水位、actor activeBatchCount=4、discussion/continuation batch预算耗尽；逐项断言步骤4过期优先，否则步骤5固定首项终态且零batch/role。feature/config/storage分支发生在预留前且零storage/budget reservation；active-capacity因全局锁序在storage预留后决议，固定留下一个零使用SETTLED storage reservation+ABORTED slot但零usage reservation/扣费；真正预算耗尽再产生恰一条零额DENIED usage reservation并结算storage/slot。另逐个latch证明MANUAL的runtime/config短LockPlan先锁并核验immutable manual result，能力正常时回滚释放manual/admission/linkage再从完整分支重进，storage短LockPlan在NORMAL时同理；CONTINUATION两条短计划都必须在admission前锁continuation guard，终态与pending-source slot释放全有或全无，能力正常时释放全部短计划锁再按完整计划重进。绝不在持有后置锁时升级申请storage/actor前置锁。篡改manual source五元引用必须503且不能被runtime/storage终态掩盖；已有 MANUAL linkage=PENDING 必须与source同事务FAILED/SOURCE_TERMINAL_*；source creator 因 runtime mismatch 直接创建终态时固定零 linkage，reconciler之后零ensure/唤醒。配置PUT先提交与materializer先提交各用双实例latch得到唯一结果。
- manual AI 意图先做五个接口向量：requestAiDiscussion缺失固定400且零manual/source；显式false即使代理token/feature均正常也固定`aiUserRequested=false,aiRequested=false,AI_NOT_REQUESTED`且零source；显式true但无可信代理固定`true/false/AI_PROXY_IDENTITY_UNAVAILABLE`且确定性payload不变；显式true+身份但本地feature snapshot关闭固定`true/false/AI_FEATURE_DISABLED_AT_SUBMISSION`；显式true+身份+feature开启才可`true/true/AI_REQUESTED`。相同key从false改true固定409，Nginx自动header绝不能改变body意图。随后用四个snapshot向量验证不绕过数据库 guard：本地 stale-enabled/数据库 disabled 时 manual 确定性响应仍正常，after-commit 只建 status=FEATURE_DISABLED,reason=AI_FEATURE_DISABLED 的 source 且零 linkage/batch；本地 stale-enabled revision=R/数据库仍enabled但revision=R+1时固定 reason=FEATURE_REVISION_CHANGED；本地 stale-disabled/数据库 enabled 时 aiRequested=false且该次永不追溯建 source，刷新 snapshot 后的新手动请求才可进入；source creator 与 PUT 并发时以同一 runtime guard 锁的先提交者唯一决议。全部向量都断言 manual HTTP/A/B SQL 对 AI 表查询数为0。
- 丢事件恢复覆盖：两个 handler 都未运行、只提交 admission 后事件丢失、linkage=PENDING worker 崩溃、ManualAiIdentityReconciler 在 ensure admission 后崩溃、双实例并发补建；生产路径不得只提交孤立 linkage，故障注入直接造孤立 linkage 必须 503/报警而非自动猜 deadline。最终 manual/admission/linkage 各一、batch<=1，成功时恰四 INITIAL，否则零，manual payload/hash/status/certifiedAt 不变；并逐个断言GET前后所有AI业务表version/hash/行数完全一致。
- 终态linkage分支覆盖runtime disabled、revision mismatch和预置七个不可逆source终态：将“manual-result handler”、“admission handler”、reconciler与应用重启恢复四个写入口分别单独和双实例并发执行，每个都必须只读原终态、linkage行数为0、wake计数为0；`GET /batches/by-source`另作为纯查询重复执行并断言零表变化。再构造MATERIALIZED+已提交batch但linkage缺失/非RESOLVED，所有写入口与GET均稳定503/CORRUPTED_MATERIALIZATION、零补建/零worker唤醒；不得用正常“丢事件”测试替代该损坏分支。
- admission/linkage 并发使用真实 latch：一个 worker 在锁 admission 前暂停，另一个先把 source 延期到 D14:52；以及只收到 linkage 唤醒但 admission handler 丢失。两种情况都必须先 ensure/锁同一 admission，以 source materializationDeadlineAt 为唯一权威，不得提前 LINKAGE_DEADLINE_EXPIRED；DDL 中不存在 linkage_deadline_at。
- linkage的两条LockPlan用SQL顺序探针证明：第一条业务SQL建立RR snapshot，LINKAGE_RESOLVE在authority statement前完成全部marker查询并冻结payloadHash；在authority ack后注入一个更晚parent marker、篡改查询adapter或让线程暂停，已注册RESOLVE payload仍完全相同且后续父表SELECT计数为0。查询后authority跨过延期/最终失败线时只能提交DEFER/DEADLINE_FAIL并丢弃旧payload；局部查询失败后下一轮到线直接走LINKAGE_TERMINAL_DECISION，不再依赖父表即可使source/linkage原子失败。
- 在 14:46:59.900 提交有候选且 aiRequested=true 的 manual 后分别暂停 before-admission after-commit handler、linkage worker 和 materializer 到 14:48:45/14:49：ManualAiIdentityReconciler/UserSourceDueCoordinator 必须幂等补 admission/linkage，并以 source version CAS 得到 DEFERRED_CUTOFF/AI_DEFERRED_CUTOFF、nextRetryAt=14:50；此间首个GET只允许观察202或已提交终态，未决事务超过500ms则503/PERSISTENCE_UNAVAILABLE，且前后零写。恢复后由 admission 唯一赢家证明只能是已提交 batch 或 DEFERRED_CUTOFF，14:50 后后者仅 materialize 一个 RESEARCH_ONLY batch。另在 15:00 新建 source 后暂停全部worker到sourceOutcomeAt=+120s，due coordinator必须收口MATERIALIZATION_FAILED/MATERIALIZATION_DEADLINE_MISSED；读API只观察终态或503，不能重置窗口或永久PENDING。
- `UserSourceDueCoordinator`用真实H2双线程/PostgreSQL双实例覆盖：14:49写DEFERRED后关闭浏览器且之后零GET，D14:50自动唤醒并只生成一个RESEARCH_ONLY batch；进程在14:49:59退出、14:50:30重启仍恢复；两个实例同抢只一个wake claim/worker有效；worker claim后崩溃由5秒lease接管；让epoch1 worker在epoch2提交后携旧ref恢复，必须SUBJECT_CHANGED且零mutation；让GET在worker持admission锁期间并发读取，只能得到旧commit-visible事实或等待后读唯一赢家，GET自身SQL不得申请业务行锁、不得改变任何version/hash/行数。parent持续未ready到D14:52时source/linkage唯一失败。所有用例scan service调用数与新manual/source创建数为0，证明只是恢复既有用户任务。health=false且高水位可读时不建claim而直接收口TIME_AUTHORITY_UNAVAILABLE，不能永久PENDING。
- 明确使用 CACHE_PREVIEW/RESEARCH_PREVIEW fixture：source=14:46:59 时让 materializer 分别在 14:46:59、14:47:00、14:49:00、14:50:00 运行，首例使用受尾盘上限约束的 RESEARCH_ONLY deadline，中间两例提交 DEFERRED_CUTOFF/nextRetryAt=14:50 且零 INITIAL，末例只 materialize 一个相对窗口 batch。若 D 日到固定 `materializationDeadlineAt=D 14:52` 仍无 batch，source 必须 MATERIALIZATION_FAILED，D+1 重启也绝不自动复活；若数据库在 D 14:50–14:52 不可用，读为 503，恢复后仍按已过 deadline 收口失败。source 正好 14:50 或 15:00 在其 +120s 固定 deadline 前可立即运行，不能写过去的 retry 时间；任何次日由用户明确继续旧讨论都只能新建 CONTINUATION/RESEARCH_ONLY，不能让旧 source 重新获得 SAME_DAY_RESEARCH。
- FINAL_READY_CERTIFIED@14:46:59.900 -> DEFERRED -> 首次且只调用一次状态机于14:50 的两库向量，必须在同一 PENDING_EXTENDED_MATERIALIZE 事务直接得到 MATERIALIZED、materializedAdvisoryUse=RESEARCH_ONLY、D14:50/D14:52 三元组、一个 batch/四 role；sourceCertifiedAt/cutoff/certificationKind 不变，source/batch/round use 一致。双实例只有一个赢家。七个不可逆 source 终态在 health 正常/异常与 GET 重放时均原样短路。
- deadline golden vectors 覆盖三类合法 materializedAt：D10:00 得到 10:00:15/10:01:15/10:01:45/10:01:59/10:02:00；D14:46:30 得到封顶的 14:46:45/14:47:45/14:48:15/14:48:29/14:48:30；D14:50:00 得到 14:50:15/14:51:15/14:51:45/14:51:59/14:52:00。每类都让 INITIAL 分别立即派发与延迟到 dispatch deadline 前 1 微秒，断言 `sessionDeadline=min(initialDispatchedAt+90s,roleContentDeadline)`；精确等于 dispatch/session/egress/content deadline 的新动作均拒绝。四角色完整、1–3 角色完整、全部超时、content seal/发布超时四种结果在各自 effectiveOutcomeAt 均不返回 PENDING，分别得到 COMPLETED/PARTIAL/TIMED_OUT 或 SYSTEM_FALLBACK/INCOMPLETE。
- 历史工具冻结测试先创建markerNo 1..N并materialize batch冻结historyVisibilityMarkerNo=N；随后提交N+1和让当前batch一个角色先持久化意见，其他三个角色任意时序搜索都只能命中<=N、current batch命中0。另用latch让materializer持sequence guard读取N后暂停：任何N+1 marker必须等待batch/source提交，不能成为边界内迟到成员；marker sequence较小事务暂停时较大marker不得先提交，重启后high-water不回退。
- 父子手动 round 的 discussionMaterialHash 与精确白名单匹配；只改变 dataCutoffAt 时 material hash 保持一致、完整 snapshotHash 不同且变化摘要明确为“仅审计快照更新”，不触发额外付费批次。
- CONTINUATION source创建与materialization分别在H2/PostgreSQL重算父roundIdentityHash；故障注入直接篡改parent candidate JSON/hash、dataCutoff、thread/symbol/createdAt任一值，必须在source创建前回滚，或对已存在source稳定503/CORRUPTED_PARENT_IDENTITY，零batch/费用。架构测试禁止生产Repository更新这些身份列；新手动行情只能生成新MANUAL round，不能改变continuation已复制的candidate snapshot。
- 父链竞态使用真实 H2/PostgreSQL 冻结：正常 RAW/FREEZE marker 的不可变父资格与 snapshot 可见性；成功查询但没有父轮时 linkage 必须 RESOLVED，并为每个 symbol 保存 null/null/null/NO_ELIGIBLE_PARENT。局部 parent 表查询错误但 linkage 表可写时用新 GuardUse 只在 PENDING 行落 lastAttemptAt/attemptCount/PARENT_QUERY_FAILED，source/linkage 均保持非终态且零 batch；deadline 前恢复后只解析一次。若一直失败到 admission.materializationDeadlineAt，同一事务写 source=MATERIALIZATION_FAILED、linkage=FAILED/LINKAGE_DEADLINE_EXPIRED，历史不得出现 null parent 的孤儿 round。linkage 表缺失/整个 datasource 不可达时投影 503，manual 不变且不关联晚到 parent。
- 生产代码中不存在 ShortTermScanScheduler、ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 或 shortTermScheduledExecutor bean，ShortTermExecutionSettings 不再暴露 cron/自动 scanRequest，历史 store 只暴露查询；架构测试禁止 scheduled repository 继承 JpaRepository/CrudRepository，禁止 save/delete/flush/@Modifying 及任何 scheduled snapshot mutator 依赖。模拟跨过 14:30、14:47、14:49:50 与应用重启均不会调用扫描服务或向 short_term_scheduled_snapshot 插入新行。
- 旧 scheduled `/latest` 在无当日旧行时返回 204，不出现“等待自动预选”；有旧行和 history 分页均只读、historical=true、executable=false，调用前后表内容/hash/行数完全一致。
- ShortTermValidationOutcomeScheduler、TradeOutcomeScheduler 仍按原配置注册并通过各自定向回归，不能通过关闭全局 scheduling 取巧。

### 22.3 数据库与恢复

- H2 文件副本升级/重启测试与真实 Testcontainers PostgreSQL schema 启动。
- 升级fixture若含缺少新AI意图字段的兼容manual行，两库只能回填`false/false/AI_NOT_REQUESTED`；启动、feature开启、代理token存在、reconciler与跨日恢复后attestation/source/linkage/batch行数仍为0。
- canonical enum/error/reason逐值在H2/PostgreSQL真实DDL、JPA写读、OpenAPI生成类型间round-trip；每类至少包含最长字面量，明确覆盖36字符的MATERIALIZATION_INPUT_RESOURCE_LIMIT及所有新码。任何VARCHAR(32)残留、截断、unknown enum回退或两库差异都使Stage 0失败。
- 两库实际执行 role_run 本行 CHECK：四个 INITIAL 字段全空/全非空、deadline 严格时序与 UNKNOWN_FINALIZATION_ONLY 禁止发送步骤；跨表 INITIAL invocation/role 同事务故障点不得留下半套字段。
- thread/batch/round 唯一约束并发测试。
- PostgreSQL 两实例并发 materialize/认领只产生一个 batch、四 role/seal；materializedAt 相同。在业务锁后单语句 raw=14:46:59.500 合法准入并提交 ticket，但业务 commit acknowledgement 被暂停至 14:47:00.400 时，当前调用必须返回 COMMIT_UNKNOWN 且零直接 dispatcher/transport handoff：实际提交分支只由恢复器读到一个 businessNow 不变的 SAME_DAY batch，再让 INITIAL 以新的 STRICT_BEFORE(14:47:20) GuardUse 决定是否派发；实际回滚分支无 batch并重新取 GuardUse。业务行锁等待到 14:47 后才继续的首次尝试必须以新 guardUseStartNano/单语句 raw 拒绝；authority DML 在其他已提交推进后才取得行时，只能因 oldRaw/raw 不匹配零行、statement timeout，或以 candidate+350ms 保守拒绝，不存在“释放应用持有的 authority 锁后沿用旧采样”的通过路径。
- MATERIALIZE用40只候选、恰4MiB artifact证明规范化/排序/JCS/参数数组和stage分块全部发生在零业务锁/零authority阶段，先提交唯一SEALED stage；逐字断言ADMISSION_MANIFEST含全部40只及稳定选择状态、SELECTED_BATCH_SNAPSHOT只含前八且与四个provider request的候选数组/hash完全相同、恰八个SELECTED_CANDIDATE ref可供round详情解引用，第9–40名symbol/candidate字段在prompt/request/body中出现次数为0；用10个非空object构造最坏UTF-8代码点边界，非final chunk必须是不超过65536字节的最长前缀、总chunk<=74且精确重建原字节，任意非最长前缀细分或第75个chunk使stage验证失败且零BOUND/provider请求；在bind锁后注入会失败的解析器与长payload DAO必须调用数0，只允许version/hash/小manifest/ref比较，authority ack后的SQL trace不得出现stage chunk SELECT、长参数或`INSERT ... SELECT`。authority ack后记录固定小PreparedStatement batch与commit的monotonic时长：99.999ms可按唯一事实提交，100.000ms固定COMMIT_UNKNOWN；实际提交/回滚两支均不重复batch/扣storage。慢预构建可以错过窗口但不能持runtime/actor/预算/authority锁或沿用旧proof；4194305字节在stage/owner前直接收口且零stage/storage/slot/budget/batch。
- PostgreSQL 两个应用实例同时初始化空 runtime_config_section，均能启动且每个 section 只有一行。
- claim token/epoch、数据库时间、心跳、过期抢占；旧 owner 的 event/chunk/evidence/opinion/终态全部被 GuardUse subject fencing。无幂等 marker-nonnull operation 在 lease 过期后必须被 UNKNOWN_FINALIZATION_ONLY 新 owner 两阶段收口：先按预算锁序把 reservation 变为 UNKNOWN_CONSUMED，再按 role 锁序把 operation/toolCall/role 变为 UNKNOWN。分别在两阶段之间崩溃并恢复，调用计数保持原值、无死锁且状态最终不再 RUNNING。另以真实 latch 让 deadline coordinator 的 TIMED_OUT CAS 与 UNKNOWN finalizer 的 OUTCOME_UNKNOWN CAS 同时竞争，逐个交换提交顺序：首个 committed role 终态永久获胜，后者 role CAS 必须为零；invocation/tool execution 仍保存 UNKNOWN 原始事实并只追加稳定 `ROLE_TERMINAL_ALREADY_*` 审计，freeze golden vector 不随线程顺序漂移。
- source commit 后、materializer 前崩溃由 reconciler 恢复。
- durable manual scan 的 PENDING/租约过期 RUNNING 恢复、总超时和旧 worker 最终 report GuardUse fencing。
- manual事务A写FINAL_PENDING后崩溃，upperNow精确等于14:49:30可按同epoch/hash恢复认证，14:49:30.000001与14:49:35都只能落DATA_BLOCKED/FINAL_CERTIFICATION_INTERRUPTED。事务B响应丢失重试不得产生第二次认证；report hash/epoch/proof任一不一致均fail closed。B必须在GuardUse总预算<100ms内发起commit；随后用JDBC proxy/网络故障分别制造commit acknowledgement延迟到14:49:40后且数据库实际提交、实际回滚两支，当前调用统一COMMIT_UNKNOWN。实际提交支后续读出FINAL_READY但executionEligibility=EXPIRED，实际回滚支不得出现FINAL_READY；不能在commit调用前暂停十余秒并仍声称合法提交。另断言此前已认证行在eligibility upperNow精确等于14:49:40时仍ELIGIBLE，14:49:40.000001时EXPIRED。
- `MANUAL_TIME_AUTHORITY_FAILURE` 两库向量覆盖PENDING、RUNNING、FINAL_PENDING和过期claim：health=false但durableGuardHighWater可读时，以`max(highWater,payloadCommittedByAt)`收口SUCCEEDED+DATA_BLOCKED/TIME_AUTHORITY_UNAVAILABLE，certifiedAt=null；尚无候选时同事务写count=0/hash=null，已有report/candidate hash逐字段保持。authority state缺失/权限拒绝时只收口FAILED+NOT_EVALUATED/TIME_AUTHORITY_STATE_UNAVAILABLE，deterministicFinalizedAt=null。两支重复恢复均幂等且source/linkage/batch为0；恢复健康后原manual不复活，只有新手动请求可重新认证。
- GuardUse.evaluate读投影以SQL探针和高并发GET验证：final/freeze-first、S1普通SELECT、authority evaluate、最终事实重读、S2普通SELECT全程零FOR UPDATE/零业务锁/零ticket/app_guard_use/零业务version变化。让worker在S1后提交source/freeze/runtime revision变化，S2不等必须丢弃旧projection并从final-first只重试一次；第二次仍变化或总时长精确100ms固定503/READ_PROJECTION_CHANGED，99.999ms且S1=S2才可返回。已提交final fact始终优先，GET并发不能创建freeze、source、linkage或阻塞manual。
- MODEL PREPARED且无reservation可用原invocationId恢复并只竞争一次原子预占+DISPATCHED；构造PREPARED+已有MODEL_INVOCATION reservation必须稳定503/CORRUPTED_MODEL_PREPARE、零外发。DISPATCHED 后供应商已接收、本地提交前崩溃在无幂等支持时变 OUTCOME_UNKNOWN，断言不发生第二次供应商请求。
- 预算 reservation 插入后、PREPARED -> DISPATCHED 前故障必须整事务回滚，恢复仍看到PREPARED+零reservation；DISPATCHED 提交后但 transportHandoffAt 仍为空时，恢复只读原 reservation 并重过 egress gate，超界则在同一事务写 FAILED_BEFORE_SEND+SETTLEMENT_PENDING。分别在该拒绝提交后、预算结算前崩溃，以及以历史异常 FAILED_BEFORE_SEND+RESERVED 启动，BudgetSettlementReconciler 都必须零外发、按固定锁序仅结算一次并恢复全部额度。transportHandoffAt 已持久后崩溃才在无供应商幂等能力时保守进入 OUTCOME_UNKNOWN；所有合法恢复都不二次预占。
- Tavily/direct tool 在合法PREPARED+零reservation、原子预占+DISPATCHED且transportHandoffAt为空、transportHandoffAt非空但结果未知三个故障点分别验证：第一支以原executionId竞争一次预占，第二支只读原reservation并竞争gate，第三支为TOOL_OUTCOME_UNKNOWN；另构造PREPARED+已有reservation固定503/CORRUPTED_TOOL_PREPARE。再对PREPARED+零reservation以upperNow=latestEgressPermitAt-1微秒、精确等号、晚1微秒及feature/config关闭做向量：只有前者可原子RESERVED+DISPATCHED，后三者都必须提交唯一零额DENIED reservation+FAILED_BEFORE_SEND并零handoff；供应商请求计数始终至多一次。
- 证据工具和历史工具分别在 `upperNow<min(sessionDeadline,latestEgressPermitAt)` 前1微秒、精确等号与之后运行 LogicalToolAdmissionGate；只有前者可建立logical call，等号/之后logical counter、tool round、Tavily/direct execution均零增量且留下稳定拒绝事实，不会等到TOOL_CONTINUATION transport才失败。同一次合法准入冻结lower/upper；证据publishedAt分别取`<=lower`、`lower<publishedAt<=upper`、`>upper`，逐一得到SAME_DAY_RESEARCH、EVIDENCE_TIME_UNCERTAIN/RESEARCH_ONLY、EVIDENCE_NOT_POINT_IN_TIME/RESEARCH_ONLY。
- 同一原生 tool_call 重复投递只生成一个 logical tool-call reservation；一个 evidence tool call 产生一条 Tavily 加两条 direct execution 时，逻辑 evidence 配额只扣一次、Tavily 一次、direct 两次，任一未知结果不重跑整组。
- logical tool 预算不足时在一次可提交事务中保存零额 DENIED reservation 和 REJECTED/BUDGET_EXHAUSTED，重启不反复尝试；对 FINAL_FROZEN+plannedExecutionCount=0、全 PERSISTED、部分 PERSISTED+余下 FAILED_BEFORE_SEND、全 FAILED_BEFORE_SEND、任一 OUTCOME_UNKNOWN 和中间态逐行验证穷尽真值表。
- 完成历史不可覆盖。
- round事件分页用三个stream/相同occurredAt/混合scope并以limit=1遍历，按timelineNo恰好一次且不泄漏。latch覆盖A持guard时B等待、A较早发生但晚取guard仍在下一页出现。每种一次性event再注入commit acknowledgement丢失与重复回调：相同causeKey/确定性eventId只重读原timelineNo，事件数与nextTimelineNo不二增；heartbeat用持久ordinal区分。schema/API不存在per-stream cursor。
- 复合父 FK 阻止跨股票/thread，父字段不可更新。
- 超过普通页面长度的 raw chunk、每个 opinion 长字段与 evidence 分页往返、逐页重建和详情“展开全部”字节一致；单个模型结构化响应不在内存聚合成无界字符串。
- 内容硬配额的 H2/PostgreSQL 向量固定 32MiB-1、恰好 32MiB、下一个完整 UTF-8 chunk 越过 1 字节，以及四 role 并发累计 256MiB-1、恰好 256MiB、下一 chunk 越界。越界事务必须零 chunk、零 contentCommitNo、invocation/batch counter 均不增，role 稳定收口 INCOMPLETE_RESOURCE_LIMIT；成功事务的 `acceptedResponseBytes`/`acceptedRawBytes` 必须分别等于已接受 chunk.byteCount 之和。在 chunk 插入、两级 counter CAS、nextChunkSequence/contentCommitNo 任意两步之间注入异常/崩溃，都只能整事务全回滚或全提交，重启后不重算、不重放已拒绝 chunk。 用一字节provider frame流输入32MiB ASCII与最坏三/四字节UTF-8，adapter必须合并成最长前缀chunk，字节恰等号成功且chunk行数分别不超过512/513；不得因frame数量先触发1024行限制。
- storage quota在H2/PostgreSQL执行：缺失/0/小于capacity的`AI_STORAGE_PROVISIONED_BYTES`均fail closed；capacity刚好容纳1个627MiB/29,840行 discussion owner、少1字节、4个discussion owner与1个48MiB/3,075行 paid owner。预留/写入/结算每个故障点都断言`global used+reserved<=capacity<=provisioned`，每个owner/bucket满足`used+remaining+released+reclaimed=maximum`且owner逐字段等于bucket汇总。逐状态golden vector断言byte/row remaining都大于0唯一是RESERVED；主动提前释放两种余量是SETTLED；恰好耗尽byte或row任一维度时同事务释放另一维度余量并唯一变EXHAUSTED；一个bucket EXHAUSTED而其他bucket仍RESERVED时owner保持RESERVED，最后bucket关闭后owner按“任一EXHAUSTED优先”变EXHAUSTED，三态CHECK无交集且重启不改写。四个batch已handoff后切HIGH_WATER，所有后续raw/opinion/event/evidence/request写最多仅让切换前已持锁的一个<=64KiB事务提交，之后零正文并INCOMPLETE_RESOURCE_LIMIT；manual确定性表仍能在发布保留空间写入。H2 FileStore/PostgreSQL发布探针失效均切HIGH_WATER，NORMAL恢复必须新revision。逐bucket字节向量覆盖sourcePayload 64KiB边界且MANUAL不复制报告正文；MATERIALIZATION_STAGE aggregate payload 4194304字节时完整stage/10-object refs/hash/manifest计费，4194305字节时固定MATERIALIZATION_INPUT_RESOURCE_LIMIT且零stage/owner/slot/budget；16个各2MiB request snapshot恰好32MiB；tool descriptor 64KiB、evidence excerpt 64KiB、event 1MiB及EVIDENCE_EVENT累计30MiB。逐行数向量分别在invocation raw 1024、batch raw 16384、单opinion item/chunk 2048、batch opinion chunk 8192、opinion manifest 32、tool call/execution 256、evidence 512、event 4096、request 16、stage 88、finalization 8的最后一行成功，下一完整行零写/零contentCommitNo/零所有counter增量并以稳定RESOURCE_LIMIT收口；8192th opinion chunk成功后全部32条<=64KiB manifest仍必须成功，下一chunk或第33条manifest才分别失败。四角色并发不能穿透。paid REQUEST逐字断言1行，四bucket rows求和=3,075。先把EVIDENCE_EVENT任一byte/row维度用尽再触发CONTENT_SNAPSHOT/SYSTEM_FALLBACK，唯一freeze仍只能从未被普通写触及的FINALIZATION_BASIS保存四条<=64KiB sealed manifest与唯一<=512KiB basis并原子收口，Merkle root在两库一致。paid response按32MiB-1/等号/超1字节流式重建，超限原子FAILED_AFTER_SEND/PAID_RESPONSE_RESOURCE_LIMIT、仅保留partial-audit chunk、不返回成功且无单行巨型response_json；请求+snapshot超4MiB在handoff前拒绝。先用尽AUDIT_DETAIL再依次写OUTCOME_UNKNOWN与显式reconciliation，两条各<=64KiB且只能消费TERMINAL_CLOSER；第三条拒绝。freeze/已知paid终态释放未用额度，UNKNOWN_HELD保留至证据化reconciliation，重启不重复释放。 另外逐类向每个canonical长字段写入多字节UTF-8边界payload，断言其byteCount、chargedBytes、bucket used、owner/global used在H2/PostgreSQL逐行重建完全相等；删除任一charged列、把值减1、让同一字段映射两个bucket、保留免计降级正文或让paid operation的REQUEST/AUDIT_DETAIL共用一个charged列都必须由DDL/启动审计拒绝。
- paid workload用PostgreSQL两实例/H2单实例双线程提交大量不同key：任意时刻ACTIVE+UNKNOWN_HELD<=4且fake HTTP并发<=4，第5个WAITING零handoff；PERSISTED、FAILED_BEFORE_SEND、FAILED_AFTER_SEND各释放一个已知终态后只允许一个WAITING取得名额。跨latestEgressPermitAt的WAITING原子FAILED_BEFORE_SEND/count不变；handoff后在responseDeadlineAt前1微秒仍可接纳完整chunk；精确等号分两组：仍为DISPATCHED且零已提交response receipt/chunk时由DEADLINE_REACHED单赢家写OUTCOME_UNKNOWN+UNKNOWN_HELD，已为RESPONSE_RECEIVED或有partial chunk时则唯一写FAILED_AFTER_SEND/PAID_RESPONSE_DEADLINE_EXCEEDED+RELEASED/count-1，usage分别按UNKNOWN_HELD或可证SETTLED/不可证UNKNOWN_CONSUMED处理，任何迟到chunk均零写。用latch交换response receipt事务与deadline closer的锁定/提交顺序并在各commit acknowledgement处断开，恢复只能读出先提交分支且不出现RESPONSE_RECEIVED->OUTCOME_UNKNOWN。四个无receipt的UNKNOWN_HELD会保守阻断新请求，只有带内部身份和provider审计的显式reconciliation逐个释放。executor queue在Gate前拒绝不丢durable operation、不写handoff、不自动创建新key或扫描。另把GLOBAL/ACTOR当日modelInvocations置于上限前1：一个旧付费key恰好获准并两层各加1，随后跨两个routeCode的新key只提交`DENIED+FAILED_BEFORE_SEND/BUDGET_EXHAUSTED+RELEASED+SETTLED+OPERATION_BOUND`，两个请求JSON为空、POST及同key重试429、by-key 200终态、fake HTTP为0；在该五元组每个写点注入故障都只能全回滚或全提交，重启不补写、不进入BudgetSettlement。paid attempt再覆盖count=499时两个实例用不同新key竞争只允许一个提交第500行，另一个在零authority ticket/app_guard_use/storage/slot/usage/operation前429；count=500后一万个新key仍零新增行。黑窗同key重复100次只有一个WINDOW_BLOCKED/count+1和一个拒绝ticket/app_guard_use，14:50健康sample到线后同key/hash转OPERATION_BOUND、不再增attempt且只新增本次准入ticket；不同hash409。午夜lower日与sample日不一致必须回滚取新proof，D/D+1各自最多500且不把旧日行改日。
- paid admission故障矩阵逐点中断guard count+1、attempt插入、WINDOW_BLOCKED拒绝app_guard_use、OPERATION_BOUND绑定、operation/storage/slot/usage五元组与业务commit acknowledgement：每个点只能全回滚或出现一套可由`actor+route+key+attemptDate`重读的完整赢家。WINDOW_BLOCKED commit已成功但ack丢失时，同key重试只读原attempt/原拒绝事实，count与ticket都不再增；OPERATION_BOUND提交不明时必须先按attempt.operationId与operation唯一键恢复，绝不能另建operation。attempt与operation任一单边存在只能来自故障注入并稳定503/CORRUPTED_PAID_ADMISSION，不由reconciler猜写。
- 日批次预算用H2单实例双线程/双连接与PostgreSQL两应用实例、不断更换Idempotency-Key覆盖：同actor/source.windowDate逐批提交freeze释放workload slot后精确接受20个discussion batch，第21个MANUAL source在materialization以唯一零额DENIED reservation+BUDGET_EXHAUSTED收口且零batch/role；其中逐次物化并收口后精确接受10个CONTINUATION admission/batch，第11个POST 429且零source。两个active continuation未物化时第3个POST 429，任一source materialize/终态只释放一次pending slot；同key响应丢失重试不增admission_count/active_count。跨零点、重启、PG双实例竞争、DENIED事务每个故障点都断言guard/usage/reservation/source计数一致，无超扣、无负active_count、无先建空batch再拒绝的旁路。
- actor workload guard 用五个并发可物化source与PostgreSQL两实例/H2单实例双线程latch验证：恰四个主事务各自原子count+ACTIVE slot+batch，第五个只得到source status=BUDGET_EXHAUSTED/reason=ACTIVE_BATCH_LIMIT、一个ABORTED审计slot，且零batch/role、active count不增；其storage reservation为零使用SETTLED审计。runtime/storage拒绝发生在PROVISIONAL之前并断言零slot；deadline、budget及capability终态发生在PROVISIONAL之后时，提交拒绝仅ABORTED且committed ACTIVE为0。CLOSED_WINDOW->DEFERRED分两支故障注入：stage尚未创建时slot/reservation/bucket全为零；已有完整SEALED stage时保留唯一627MiB owner及其已消费MATERIALIZATION_STAGE字节、零PROVISIONAL/ACTIVE slot，下一次按相同stageId/hash重用而不二次预留或重写4MiB。parent/linkage未ready固定在建stage前返回，零stage/reservation/slot；已RESOLVED后不得回到BUSY。上述CLOSED_WINDOW两支source都保持有出边，允许保留的确定性thread/outcome/parent guard逐字段相同且所有batch/round/history API计数仍为0；14:50或parent ready后以同一确定性batch/round/role ID重新进入并成功ACTIVE，证明admission/storage唯一键不挡重试。另用双latch分别暂停在stage guard后/SEALED提交前与source FEATURE_DISABLED/deadline终态竞争，并让bind与cleanup反向竞争；最终只能BOUND或terminal+RECLAIMED且无死锁。BOUND分支object/chunk仍完整、MATERIALIZATION_STAGE used与global accountedUsed不减并拒绝cleanup；terminal分支先SETTLED/RECLAIM_PENDING，再以SEALED冻结C/R执行唯一cleanup，断言object/chunk与stage长字段全消失、owner/bucket used各减C/R、reclaimed各加C/R、global accountedUsed只减C、remaining/released/status/settledAt不变且global reserved为0。分别在删除、owner/bucket计数、global计数、tombstone及commit acknowledgement处注入故障，只能全回滚或完整RECLAIMED；重复cleanup零变化，篡改任一C/R或逐行和固定503且正文与账本均不变。四个ACTIVE batch分别以三类freeze及并发败者重读收口，每个唯一赢家与count-1/ACTIVE->RELEASED全有或全无，重放不为负；释放后新source才可取得名额。
- H2真实alias与PostgreSQL样本必须推进。BOOTSTRAP/REBASELINE永远health=false；只有一次ack成功且RTT<=250ms的BASELINE之后，间隔>=100ms的VALID_ADVANCE满足raw/nano delta才健康。用latch让首次bootstrap在requestStart后暂停500ms：该sample提交但RTT坏，随后REBASELINE+ADVANCE须在同boot恢复，不能永久delta失败；BOOTSTRAP/ADVANCE ack unknown必须先CAS REBASELINE。两个线程持同expected token同时REBASELINE仅一赢家，generation只增，输家零proof。另逐项覆盖首样本、启动probe失败、第二样本delta超界、probe后冻结、raw回退、proof>750ms：SAME_DAY、RESEARCH_ONLY、CONTINUATION、MODEL、TAVILY、DIRECT、PAID_HTTP全部零新调用，新source不建batch，既有batch只能TIME_AUTHORITY_UNAVAILABLE/INCOMPLETE而非永久PENDING，manual认证/executionEligibility均fail closed。再在有效proof后、下一probe前把单语句raw从14:46:50前跳到14:50:00：本次GuardUse必须因`rawNow>candidateNow+250ms`立即回滚并使health失效，零materialization/handoff/reservation/budgetBucketDate更新，不能按AFTER_WINDOW提前开窗；恢复必须追平全局floor后重新BASELINE+ADVANCE。
- PostgreSQL以两个进程声明同一instanceId、两个合法不同slot和第三个清单外slot做boot lease/capacity组合测试：同slot并发BOOTSTRAP只允许一个未到期boot建立baseline，输家零proof且readiness=`INSTANCE_BOOT_LEASE_HELD`；赢家停止后raw严格到达2秒租约才允许新boot CAS接管，旧boot恢复后的第一次probe得到`INSTANCE_BOOT_FENCED`并停止本boot全部后续probe，不能等新租约再抢回。不同合法slot可同时健康；清单外slot即使连接暂时可借也固定`INSTANCE_SLOT_NOT_DECLARED`。H2 LOCAL重启同样在旧租约未到时保守等待，DB冻结/回退不提前接管。租约更新、sample token/generation与authority high-water必须在同一probe statement全有或全无，ack unknown只按committed行恢复。
- 在已健康观察 14:50:10 后把 raw DB 回退到 14:46:50：即使回退时钟继续严格推进、即使应用在回退后重启，raw 未追平旧 durableRawHighWater 前 health 始终 false，durableGuardHighWater 也不降低，14:47 materialization/14:47:55 egress/14:48:30 content/14:48:44 freeze/14:49:30 certify/14:49:40 eligibility 与 budgetBucketDate 都不能重开或倒退；运维 epoch 迁移仅接受 raw/guard 两个 new floor 分别不低于旧值。
- GuardUse interface/架构测试禁止业务包引用或构造 GuardUseCommand/GuardedReadCommand/GuardDecision/ticket/GuardUseResult/COMMITTED，禁止序列化、缓存、任意 SQL callback，也禁止业务包选择 predicate/deadline/adapter/lockPlan/mutation；operation-specific facade 仅稳定业务引用/opaque ClaimRef，policy 必须从锁后行唯一派生内部矩阵。operationKind/predicate/branch/lockPlan/mutation 不匹配必须在 authority/业务 adapter 的相应安全点零越权 mutation。编译/ArchUnit 还必须证明只有 gate implementation package 可引用 Provider/Tavily/direct/paid HttpClient 和注册 transport adapter；业务包不能传 URL/header/body/lambda/closure，不能依据任何 domain DTO 触发网络。四类 operation 的持久 transportRequestSnapshot/requestHash 篡改测试必须全部零 handoff/零网络并报警；snapshot 不含 secret/Authorization/任意 endpoint。相同 mutationId 双线程/双实例只提交一个 app_guard_use；失败、回滚、COMMIT_UNKNOWN 后旧 ticket 永不复用。
- 使用 249ms health sample 验证 requestStartNano 锚定。worker 在获得业务行前暂停并跨过截止后，新 guardUseStartNano/单语句 raw 必须到线并拒绝；在 authority DML 发送前暂停同样必须重校 proof age/candidate 并拒绝。用第二连接造成 authority 行竞争并在等待期间推进 oldRaw，被阻语句只能零行/timeout/保守拒绝，不得以语句开始时的旧 raw 通过。逐 gate 在业务锁后且 authority DML 发送前、DML acknowledgement/ticket commit 后、business mutation 前和 commit 前暂停；DML ack 后暂停时数据库 authority 行必须已可被另一连接立即更新。GuardUse 总预算精确99.999ms可 commit ack，100.000ms不得授权，COMMIT_UNKNOWN 对四类 transport 调用均为零。
- 在真实H2 2.2.224/PostgreSQL执行Stage 0逐字BOOTSTRAP/REBASELINE/ADVANCE/execute/evaluate canonical SQL resource：PostgreSQL为materialized raw CTE+UPDATE RETURNING+outer INSERT/MERGE；H2 probe必须是outer MERGE<-SELECT FROM FINAL TABLE(inner MERGE)，execute必须是outer INSERT<-SELECT FROM FINAL TABLE(inner MERGE)，evaluate必须是SELECT FROM FINAL TABLE(inner MERGE)，不得使用H2不存在的MERGE RETURNING。两库raw alias都恰调用一次；probe/execute update count=1、evaluate row count=1，mode/token/generation/sampleKind/返回列向量逐字段一致。坏baseline可在同boot重建且不降低双floor；换boot不比较旧nano。同token并发REBASELINE仅一赢家，赢家ack丢失/输家先返回都只能把committed sample安装为不健康事实，下一次成功REBASELINE+ADVANCE前不得产生proof，candidate只取committed requestStartNano。execute predicate true/false都推进双高水位、分别写唯一PERMITTED/REJECTED ticket且连续调用不递归+350ms；evaluate任意次数都不增ticket/app_guard_use。epoch/boot/raw回退、`rawNow>candidateNow+250ms`、statement timeout、零行/多行均零ticket/业务mutation/读结论。每个autocommit ack后立即以另一连接证明零authority锁；UTC/Shanghai/New_York JVM与H2 session时区交叉组合得到相同Instant和TIMESTAMP WITH TIME ZONE元数据。lower=14:49:59.999999即使upper越界也不开窗，lower=14:50:00才开放。
- authority ticket 提交后业务回滚保留高水位/ticket但无 app_guard_use/marker，orphan 只投影 ABANDONED；业务 commit unknown 的“实际提交/实际回滚”各一例，当前调用均零 transport，恢复分别按 marker收口 OUTCOME_UNKNOWN或以新 GuardUse 重试。H2 必须用两个真实连接验证业务锁+独立 authority ticket 事务与 PostgreSQL 同义。
- 午夜预算向量：尚未创建的 CONTINUATION 在 upper 已跨零点而 lower 未跨时仍以 lower 日作 windowDate/budgetBucketDate；lower 到零点后新建的 CONTINUATION 与 PAID_HTTP 使用次日。PAID PREPARE无论线程/实例都逐行断言`GLOBAL(D)->GLOBAL(D+1)->ACTOR(D)->ACTOR(D+1)`，lower=D23:59:59.999999只增D，lower=D+1 00:00:00只增D+1，未选桶为0且authority后零新锁；已有D23:59:30 source即使次日才materialize/发送仍复制D；恢复不换日。
- H2 与 PostgreSQL 对相同的内容写/锁时序分别让 coordinator 在 roleContentDeadline 后立即运行和临近 freezePublishAdmissionDeadline 但仍可准入时运行，四条 acceptedThroughCommitNo、basisHash、batch/round/advisory 必须完全相同；精确在 roleContentDeadline 申请 contentCommitNo 必须被拒绝。
- PostgreSQL 用 latch 让内容事务在取得 ledger 锁后停留并跨过 roleContentDeadline：它随后的 authority DML 必须拒绝正文；sealer 再封存。若 ledger 锁持续到 freezePublishAdmissionDeadline，effectiveOutcomeAt 的唯一赢家为 SYSTEM_FALLBACK，后续不能生成 CONTENT_SNAPSHOT。AI 表长锁/小连接池耗尽不得阻断 manual 独立业务池/authority 保留连接的 report stage/certify；AI GuardUse/watchdog 分别在单语句发送前与 ack 后暂停时，manual 都必须在自身预算内完成，证明应用不可在两条 JDBC 指令之间持有共享 authority 行锁。
- freeze golden vector 覆盖 SEALED OUTCOME_UNKNOWN，以及 CONTENT_SNAPSHOT 的 UNSEALED 在 seal 后 live RUNNING->TIMED_OUT/OUTCOME_UNKNOWN 两种先后顺序；后者必须固定 rawFactKind=UNSEALED、rawStatus=null、role effectiveStatus=TIMED_OUT 且 basis 字节/hash完全相同。SYSTEM_FALLBACK 另以专用 golden vector 逐字断言 acceptedContentManifests=[]、qualifiedOpinionStates=[]、四 roleSeal.acceptedThrough=null、四 role INCOMPLETE/failureCode、所有 round INCOMPLETE、batch INCOMPLETE、所有 advisory INCOMPLETE，不得复用 CONTENT_SNAPSHOT 的 UNSEALED->TIMED_OUT/qualified-opinion规则。
- TIME_AUTHORITY_UNAVAILABLE 在 raw 回退/冻结时以 durable trustedFallbackAt 提交；eligibleAt>=roundCreatedAt/highwater。manifest=0 必须 503/零写；manifest=1/N 且预置无/RAW/OUTCOME marker 时，含 basis/effective 的唯一 freeze 与全部 N 个已核验 marker 事实全有或全无；freeze/OUTCOME markers 的 freezeRevision 全为1，正确 hash 但错误 revision 必须整事务回滚；第k marker/冲突/commit unknown 故障全回滚，合法 RAW 首胜不被覆盖，身份不符503/报警。高水位/manifest不可读时零新增、HTTP 503。
- 发布配置绑定测试逐池断言minIdle=max、timeout/autocommit/角色隔离并实际同时借满。PG16+向量覆盖`reserved_connections>0`、普通角色错误获pg_use_reserved_connections、应用角色superuser/unlimited/过高limit、OPERATOR为无限或limit总和6、cluster新增一个未声明/无限LOGIN角色、OTHER清单caps之和超限、LOGIN角色删除/改名/提权/跨类别继承、max不足、declaredReplicaCount=1却启动第二实例、恰好充足容量：ordinaryUsable必须同时减两类reserved并覆盖发布清单全部普通LOGIN role caps+完整5条operator余量且不重复计算LEGACY，四app角色无特权且limit精确，全部16条/实例连接预热后才启用AI；超额实例至少一个pool无法完成readiness，已ready实例manual连接不受影响。分别占满AI_BUSINESS+AI_AUTHORITY、占满WATCHDOG_AUTHORITY、令AI角色limit不足：前两项证明短事务超时/隔离，第三项必须只关闭AI两池，WATCHDOG仍以DETERMINISTIC角色产生健康proof，manual用已预热deterministic完成stage/certify，READ只读。任一profile缺AI证明即AI fail closed，但确定性readiness独立判定。
- H2 bootstrap 测试在任何 Flyway/schema/JPA bean 前执行 `CREATE ALIAS IF NOT EXISTS APP_WALL_CLOCK_NOW FOR 'com.aistock.research.shortterm.ai.time.H2WallClockAlias.appWallClockNow'`；断言返回 `OffsetDateTime`、UTC、微秒截断、同一 session 间隔>=100ms 严格推进，注入 Clock 可复现冻结/前跳/回退。PostgreSQL profile 不创建 alias；两个 profile 仍只消费同一 canonical 业务 schema 合同。
- 在 reservation+DISPATCHED 已提交后用 latch 暂停 worker、跨过 latestEgressPermitAt 再进入 AiEgressGate：gate 必须拒绝注册 transport adapter，transportHandoffAt 不得写入，fake Provider/Tavily/direct/paid-route 调用计数均为零。
- runtime/config/storage 提交前 TOCTOU 用 materializer 与 MODEL/TAVILY/DIRECT/PAID_HTTP 四类 AiEgressGate 各跑两个双实例 latch：PUT 先锁 runtime guard 并提交 disabled 时固定 AI_FEATURE_DISABLED，提交仍 enabled 的新 revision 时固定 FEATURE_REVISION_CHANGED；存储监控器先提交 HIGH_WATER 时固定 STORAGE_HIGH_WATER，三者都要求旧 source/operation 零 handoff/零网络。materializer/gate 先锁 guard 时 PUT/监控器必须等待，只有 batch/transportHandoffAt marker 在旧 revision/storageState 下先提交才属于不可撤销的旧操作。在 gate 读取状态后到 marker commit 前任何暂停都不能让 PUT/监控器穿过同一 guard，不得只测“marker 已提交后再关 feature”。
- 另在 AiEgressGate 已写 transportHandoffAt、但尚未由 gate internal 调用注册 adapter 时暂停并跨过 permit deadline，再依次关闭 feature、切换 config revision、让 claim 过期并由 recovery 写 OUTCOME_UNKNOWN：仍只允许原 gate 调用帧继续一次，记录 TRANSPORT_HANDOFF_DELAYED/CONFIG_CHANGED_AFTER_HANDOFF；断言业务包从未收到 COMMITTED/permit、不会取得第二次 handoff 或自动重试，精确在/晚于 roleContentDeadline 的返回正文被 content gate 拒绝，freeze hash 保持不变。另以终止原进程/阻断 fake egress 证明运维硬停路径；该用例不虚假断言仅切 flag 后供应商调用计数为零。
- 两个真实 PostgreSQL 应用实例同时对同一 marker-null operation 进入 gate，并在赢家提交 marker 后暂停：MODEL、TAVILY、DIRECT、PAID_HTTP 四类各自必须恰有一个赢家和一次 fake transport 调用；输家返回 ALREADY_HANDED_OFF，不释放 reservation、不改 marker。H2 单实例用双线程执行同一合同。
- freeze/恢复竞态逐个覆盖QUEUED、RUNNING与DISPATCHED-marker-null role：freeze先持outcome guard时claim/egress等待后只读freeze且零网络；claim先提交后freeze获胜时，随后egress重锁outcome guard并拒绝；egress handoff先提交则只允许原Gate帧最多一次发送，freeze仍按content seal排除截止后正文。应用重启扫描到已有freeze+非终态raw role时不得claim、改role或反复排队，fake transport与dispatcher wake均为0。
- OutcomeBasisV1 固定向量在两库字节/hash一致且包含 freezeRevision=1；插入/查询顺序不影响，修改 accepted raw chunk/opinion/sealed terminal reason 才改变 hash，basis 外 live status 不改变。CONTENT_SNAPSHOT 若业务锁在 14:48:44 前已齐且 post-lock upperNow仍严格早于线可合法授权，commit visibility 可稍晚；若任何所需业务锁到 14:48:44 后才释放，post-lock GuardUse 零新准入。14:48:45 fallback 与未决唯一键 500ms 未决为503，决议后只读赢家。
- 在发布支持负载“同 actor 恰 4 个活跃尾盘 batch”下用真实 monotonic latch/执行器而非只移动注入 Clock 验证 SLO：每个 batch 仅一个 finalizer wake，`startNano-effectiveOutcomeNano<=250ms`，唯一 freeze 提交或稳定 503 决议 `<=500ms`；伪 React 可见页以最坏 1,000ms poll 显示右上角消息，`messageVisibleNano-effectiveOutcomeNano<=1,750ms`。模型全失败、队列饱和、预算/存储终态均在该支持负载内测；另对 DB/JVM 暂停只断言 freeze 或 503、永不 PENDING，不伪造 SLO 通过。
- 旧 scheduled snapshot 即使存在且 feature 后来开启，也不能被 reconciler 追溯 materialize、扣费或生成 source/batch。

### 22.4 模型与搜索

- Tool call JSON 合法与非法参数。
- symbol 越权、模型提供 URL、exact-host 旁路、eviltrusted.com、未知 COMPANY_IR。
- Tavily adapter 断言绝不对 result URL 发起二次请求；固定请求参数、include_domains、requestId 与 credits 落库。
- staged tool plan 覆盖零 parent、权威 direct parent 返回 0/1/多个允许目标、parent 全 FAILED_BEFORE_SEND、多个 parent 混合 PERSISTED/FAILED_BEFORE_SEND、任一 TOOL_OUTCOME_UNKNOWN，以及崩溃在 DISCOVERY_FROZEN、全部 parent 终止后 FINAL_FROZEN CAS 前/后：非 UNKNOWN 路径即使零目标也必须冻结 FINAL_FROZEN；相同 resultHash/终态集合派生相同 targetSetHash、排序和 executionId，plan 提交前后续 direct 调用为零，恢复不追加重复 execution。另以 Tavily 返回 0/1/多 URL 断言 tool execution 数不增加且服务器对这些 URL 的 HEAD/GET 始终为零。
- Cninfo 附件 fetcher 覆盖 localhost、私网、IPv4-mapped IPv6、DNS rebinding、逐跳重定向、2秒连接/8秒总时限、3跳/2MiB边界、压缩炸弹与错误 MIME；Tavily同样覆盖8秒/2MiB/3xx拒绝，超限均零可用evidence且不阻塞其他角色。
- MODEL、TAVILY、旧PAID 的官方client分别以301/302/307响应验证redirect=NEVER且零后续请求：唯一handoff已存在，MODEL/PAID在同一事务写RESPONSE_RECEIVED字段、TAVILY写RESULT_RECEIVED字段，并以PROVIDER_REDIRECT_REJECTED最终提交FAILED_AFTER_SEND；三者均不得出现FAILED_BEFORE_SEND。最小receipt只含status/locationHash/receivedAt，不含Location明文、body、URL、Authorization；PAID同事务RELEASED/count-1，usage不可证时UNKNOWN_CONSUMED。
- Tavily 自然日结果、精确 news 时间、直接公告时间、admissionLowerBoundAt/admissionUpperBoundAt、role.sessionDeadline 与 latestEgressPermitAt 点时资格真值表；逻辑工具接纳必须满足upper严格小于两个deadline，SAME_DAY证据资格必须满足publishedAt不晚于lower，每个物理execution仍单独经过AiEgressGate。
- 无发布时间或截止后证据只能 RESEARCH_ONLY；在SAME_DAY batch其title/snippet/excerpt/URL不回传模型，只产生EVIDENCE_NOT_POINT_IN_TIME gap，既不能触发RISK_ALERT、贡献SUPPORT，也不能用高置信OPPOSE改变共识。正反两向及三票/两票边界都做golden vector。
- 网页 Prompt Injection 和历史模型文本不能增加工具/域名/预算；伪造 citationId 和 severe risk 被服务器拒绝。
- 搜索密钥和 LLM 密钥不出现在响应、事件或日志。
- 运行时配置 PUT 无内部 token 为 401；任意 apiKeyEnv、非官方 Base URL 与跨 Provider 复用旧 key 均被拒绝，GET 不回显 key。
- Nginx 未知 Host、DNS rebinding Host、cross-site Origin/Sec-Fetch-Site 与伪造 X-AI-Internal-Token 均不能触发付费调用。
- POST /api/ai/trend-analysis 与 POST /api/companies/{symbol}/agent-consensus/ai 均验证 token、Idempotency-Key、语义冲突 409、并发/日预算与供应商 at-most-once；保护未完成时发布配置断言路由关闭。
- 两个旧付费路由用 admittedAt=14:00:00 时 gate=+29.999s/+30.000s，以及 14:47:54.900/14:47:54.999/14:47:55/14:49:59.999/14:50:00 验证公式和严格 `<`：入口黑窗拒绝时只创建/复用一个WINDOW_BLOCKED attempt、503且by-key=404，14:50 同 key/body 把该attempt绑定到首次operation并得到新30秒permit；截止前已有 operation 后被 gate 拒绝则持久 FAILED_BEFORE_SEND_CUTOFF，原 key 终态且只有新点击/新 key。预算耗尽的首次POST/同key重试均429，同key不同body仍409，by-key以200只读终态且不含请求正文；第501个当日不同key固定PAID_DAILY_ATTEMPT_LIMIT且零ticket/operation。React 和旧 Vue 都覆盖 404 首提与 existing-operation 两条 sessionStorage 恢复，另验证 202、200、FAILED_AFTER_SEND、OUTCOME_UNKNOWN 和查询零付费。
- 流式中途超时保存已接收 chunk；finishReason=length 的唯一续写、合并与 INCOMPLETE。
- 32 MiB/256 MiB 运输边界只产生 INCOMPLETE_RESOURCE_LIMIT，不伪装完整。
- 不支持工具调用的模型返回明确 SEARCH_UNAVAILABLE 或 TOOL_UNSUPPORTED。

### 22.5 API 与 React

- OpenAPI 与 React 类型契约测试。
- BatchSummary、RoundDetail、签名 cursor、稳定排序、父轮次变化和全部错误码。
- 活跃轮询停止条件和网络失败保留数据。
- TIME_AUTHORITY_UNCERTAIN返回503/Retry-After:1时保留最后可见数据、不弹“最终失败”、不停止轮询；lower到线后只显示唯一freeze/终态。精确`lower<deadline<=upper`期间DOM与API均不得出现新的PENDING快照或伪fallback。
- 右上角完成/失败浮动消息；`COVERAGE_BELOW_95` fixture断言消息位于右上角、包含稳定码与实际覆盖率、零候选/零AI source，且不重新出现手动扫描状态页卡。
- `AiDiscussionNotificationWatcher` 用React fake timers/localStorage覆盖：manual终态true/false意图分支，以及true但覆盖率阻断/FAILED/TIMED_OUT/NO_TRADE/空候选均即时提示且零watch/零source GET；POST记录转watch的原子顺序、pre-source 202、MATERIALIZATION_PENDING、DEFERRED_CUTOFF到nextRetryAt、MATERIALIZED后切freeze GET、七个无batch终态和committed freeze。路由离开、页面刷新、浏览器重启、hidden->visible都保留/恢复未ack记录；同finalKey只提示一次但ack前可恢复，点击进入详情或关闭才ack清理。逐请求断言watcher只发GET，POST/ensure/wake/写API计数恒0；损坏localStorage只给一次通用提示且零副作用。
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

注入 TimeAuthority health proof 自动证明 14:47:00 source/materialization admission 线、14:47:20 INITIAL 派发线、14:47:55 egress permit 线、14:48:30 严格内容接受线、14:48:44 CONTENT_SNAPSHOT GuardUse 授权线、14:48:45 不可逆 fallback/read 线、14:49:30 手动认证准入线和 14:49:40 executionEligibility 线；另证明启动后冻结/回退 fail closed。真实交易日观察单独记录，不能用当天是否恰有候选替代自动证明。现场验收包括：

- 真实手动扫描只生成一个持久结果、一个 batch、四个初始角色；
- 后台讨论完成并从右上角消息进入完整详情；
- 有效 TAVILY_API_KEY 下完成一次可信 news 域名搜索并展示时间/引用；
- 同股继续讨论形成父子关系；
- 容器重启后历史仍存在；
- 应用跨过 14:30、14:47、14:49:50 均没有自动短线扫描、scheduled snapshot 新行或 AI 付费调用；只有用户点击才产生手动结果；
- 14:49 至 14:50 人工触发 AI 故障时，手动确定性扫描仍正常返回或按原门禁失败关闭。

## 23. 验收标准

1. 改变 PE/PB 不会改变当前短线任何候选、复核顺序、identity、完整度、交易计划、验证资格、动作、解释、AI opinion或历史；当前版本不调用估值计算器，模型query/provider snippet/历史/自由输出四条旁路命中估值时均零聚合影响。
2. 短线页面、AI讨论过程、原始响应公开端点及历史详情看不到 PE/PB 与估值语境，也不再通过 strategy-bundle 产生长期 ledger；策略拒绝内容只显示withheld hash/reason，长期入口不受影响。
3. 成功扫描后 HTTP 请求不等待 AI；一个最多八候选 batch 只创建四个真实并行初始角色会话。
4. 用户可以查看四角色全部被短线合同接受的输出、原始响应、工具过程、引用、错误和模型版本；估值策略拒绝项可追溯但不泄露正文。
5. 模型可以发起受控联网搜索；同日意见只接收 registry 可信且可证明不晚于截止的证据，RESEARCH_ONLY provider结果在同日角色中无原文、零票数影响。
6. Tavily result URL 不被服务器抓取；直接公告 fetcher 不能访问内网/越权域名；不存在密钥泄漏、伪 citation 或网页提示词越权。
7. 不对模型论述做业务层长度截断；供应商截断被识别、保存并按规则续写。
8. 任一或全部 AI 异常、队列饱和、预算/存储熔断和 materializer 失败都不改变手动确定性 payload hash、14:49:30 认证准入或 14:49:40 executionEligibility。
9. 同一股票所有历史轮次可查，新轮次正确链接父轮次并显示观点变化。
10. 讨论记录在 H2/PostgreSQL 中持久化，容器重启后可追溯；PostgreSQL 的租约 epoch 能 fence 所有旧 owner 写入。
11. 重复 HTTP、误点、响应丢失、dispatcher 重试和多实例 gate 竞争不生成重复 source/batch/initial invocation 或 transport；marker-null 只有一个 handoff 赢家，DISPATCHED 未知结果在无供应商幂等键时由专用 owner 收口 OUTCOME_UNKNOWN，停止自动重发。
12. 发布 Web 只绑定 loopback、API 不暴露宿主端口、付费触发必须通过可信代理 token；专用付费端点无 token 401，scan-jobs 无 token 仍完成确定性扫描但不触发 AI，缺 token 时 AI feature 关闭。
13. 后端完整测试、React 测试与构建、旧 Vue 新增恢复单测与构建、schema 启动、容器健康和浏览器真实链路验收通过。
14. H2 文件副本和真实 PostgreSQL 升级、OpenAPI/React 契约、fake streaming Provider 与浏览器现场链路全部通过。
15. 每个阶段的规格与对抗审查均未留下 Critical 或 Important，最终全分支红队也通过。
16. ShortTermScanScheduler、ShortTermScheduledScanService、ShortTermScheduledExecutorConfig 与 shortTermScheduledExecutor 均不再是生产 bean，ShortTermExecutionSettings 无 cron/自动 scanRequest，历史 scheduled repository/store/API 从 Spring Data 到 controller 全链只读且一律 non-executable；PRESELECT、FINAL、READINESS_GUARD 注册和 catch-up 全部为零，跨过旧触发时间或重启都不产生自动扫描、新 scheduled snapshot、AI source 或费用；ShortTermValidationOutcomeScheduler 与 TradeOutcomeScheduler 不受影响。
17. 阶段 F 完成后，短线新用户和一次性迁移的旧用户默认看到“今日资金去向”，结果视图中没有“扫描快照”开关或卡片，ShortTermPage 不再轮询 scheduled-snapshots/latest；用户之后可主动隐藏资金去向，长期价投和历史 scheduled snapshot 只读兼容 API 不受影响。
18. TimeAuthority 启动或运行期健康证明失败时所有 AI 类型都零新外发且无永久 PENDING，manual 认证/executionEligibility 也 fail closed；单行持久高水位在回退后继续推进、JVM 重启或业务事务回滚时均不降低，AI GuardUse 竞争不能耗尽 manual 保留连接或持长 authority 锁；OutcomeBasisV1 在 H2/PostgreSQL 字节/hash 一致，14:48:44 后无新 CONTENT_SNAPSHOT post-lock 授权，14:48:45 fallback 对未决唯一键返回503而非PENDING，`lower<deadline<=upper`固定TIME_AUTHORITY_UNCERTAIN且零业务mutation，数据库决议后只有不可逆唯一赢家。
19. 对 sourceEffectiveAt<14:50 的尾盘相关用户 source，14:48:45 起无 batch时只能显示 DEFERRED_CUTOFF/明确失败或 503；14:50 后仅具一次延期资格且未过 D14:52 的 source 可生成一个 RESEARCH_ONLY batch。即使用户关闭页面、没有GET或进程跨14:50重启，UserSourceDueCoordinator也只恢复这条既有用户source并由双实例单赢家推进；绝不创建自动扫描/source。14:50 后新建 source 仍按自己的 +120s deadline 有界 MATERIALIZATION_PENDING。
20. manual A/B/HTTP 从不读取 AI 表；父链由 after-commit REPEATABLE_READ linkage snapshot 与一次写入 marker 解析，AI 锁/缺表/超时不改变手动认证，后写 freeze/closer不改变既有父 ID 或 roundEndAt。
21. 所有时间敏感 mutation 都只能通过 GuardUse 封闭 adapter：先按固定顺序锁齐全部业务行，再以一条 autocommit authority DML 恰求值一次 DB raw 并提交 ticket，ack 后不持有 authority 行锁；H2使用已实测的FINAL TABLE(inner MERGE)+outer MERGE/INSERT/SELECT三种固定resource，350ms guard margin 与100ms提交预算在H2/PostgreSQL一致。MATERIALIZE的40只/4MiB规范化发生在零锁阶段，锁后只验hash，ticket后固定SQL仍受100ms。裸decision/ticket不能被业务代码消费，COMMIT_UNKNOWN零外发，恢复不复用ticket，四类transport的marker至多一个。
22. source.materializationDeadlineAt 是 linkage 唯一截止权威，不存在孤立 linkage 或复制 deadline；已认证 FINAL_READY 延期为 RESEARCH_ONLY 时认证事实永久保留，source/batch/round 的 materializedAdvisoryUse 一致。14:50 首次单调用、并发双实例也只生成一个batch。
23. 任何AI长内容写入前都已被持久storage owner/bucket字节与行数额度覆盖，全局`accountedUsed+reservedRemaining<=aiWritableCapacity<=AI_STORAGE_PROVISIONED_BYTES`、各owner/bucket的`used+remaining+released+reclaimed=maximum`始终成立；MANUAL source不复制报告正文，materialization快照全部计入MATERIALIZATION_STAGE且只由BOUND stage公开。未BOUND stage的唯一回收事务必须把物理删除的charged bytes/rows从used精确迁移到reclaimed，绝不重开remaining或改写历史状态。HIGH_WATER后已handoff响应也零新长内容；普通证据/事件与付费审计即使用满，也不能消费FINALIZATION_BASIS/TERMINAL_CLOSER，sealed content manifest/Merkle root与已知付费终态仍有不可挪用的收口额度并只释放未用额，不得侵占manual应急空间。
24. 旧付费路由在H2双线程和PostgreSQL双实例下物理HTTP并发始终不超过4；WAITING零handoff，PERSISTED/FAILED_BEFORE_SEND/FAILED_AFTER_SEND原子释放槽，OUTCOME_UNKNOWN保守UNKNOWN_HELD且仅显式运维证据可释放，32MiB响应硬上限不以单行TEXT或自动重发绕过；不同key的paid attempt每actor/日硬上限500，第501个在零ticket/operation前拒绝，黑窗同key可在14:50安全续办且不重复计数。
25. 手动扫描请求必须显式携带requestAiDiscussion；React只有“扫描并讨论/重新扫描并讨论”的明确用户动作发送true，缺失、false、true+不可信身份、true+可信身份但feature关闭、true+可信身份且feature开启五分支持久意图唯一且代理header永不伪造用户选择。AI意图变化不改变确定性扫描payload。
26. 应用根节点的AiDiscussionNotificationWatcher在路由切换、刷新、浏览器重启和DEFERRED跨14:50后仍只读追踪既有用户任务；无batch终态与唯一freeze都在右上角恰提示一次，ack前可恢复、ack后清理，watcher永远不POST、不ensure、不唤醒或创建任务。

## 24. 多 Agent 实施与对抗复核协议

### 24.1 角色分工

主 Agent 只承担规格所有权、共享接口冻结、阶段集成、驳回裁决和最终验收。实现任务由每阶段新的开发 Agent 执行，审查 Agent 不得审查自己写的代码。

阶段划分：

| 阶段 | 开发责任 | 强制对抗重点 |
| --- | --- | --- |
| S | 删除 ShortTermScanScheduler/ShortTermScheduledScanService/ShortTermScheduledExecutorConfig，删除专用 executor，收窄并重命名 settings/store，把底层 repository 也改为 SELECT-only，保留 non-executable 历史只读 API，保护其他调度器 | 旧 cron/property/runNow 仍触发、隐藏 JpaRepository mutator、GET 偷写/合成等待、重启补跑、追溯写 scheduled/AI、误关全局 scheduling 或其他业务任务 |
| A | PE/PB 决策移除与 React 完全隐藏 | 寻找任何隐藏估值影响、兼容破坏和长期模块误伤 |
| 0 | 冻结 canonical DDL、OpenAPI、OutcomeBasisV1/其他 JSON Schema、GuardUse/两库单语句 authority 模板、锁计划/adapter 矩阵、状态枚举、错误码与生成类型 | 合同矛盾、不可执行 CHECK、跨库 hash 漂移、可绕过 GuardUse、锁序环、字段遗漏、长内容和兼容 |
| G | 独占实现 TimeAuthorityProbe/HealthMonitor/boot lease、GuardUse engine、H2/PostgreSQL canonical adapters、专用连接池/HealthProof、AiEgressGate核心与transport registry、authority/ticket/app_guard_use基础Repository；不实现具体模型/Tavily或业务状态机 | SQL resource原样可执行、raw单次求值、连接/锁隔离、票据不可泄漏、故障/commit unknown与架构边界 |
| M | 只消费Stage 0/G能力，实现durable manual result、scan-jobs POST/status/by-key 后端、必填requestAiDiscussion与意图/能力分列、manual worker、A/B stage+certify、ExecutionEligibility读投影、attestation及对新意图schema合格行的缺失attestation恢复、after-commit事件发布；legacy manual迁移只能回填AI_NOT_REQUESTED且零attestation；不创建AI source，不实现discussion状态机 | 确定性payload被AI故障污染、14:49:30/14:49:40方向错误、GET加锁/写入、意图被代理token伪造、attestation缺失/重复、响应丢失恢复 |
| B | 只消费 Stage 0/G/M 已冻结能力，实现after-commit source handler/creator、ManualAiIdentityReconciler、materialization stage guard/object、讨论 entity/Repository、source状态机、原子认领与恢复；MANUAL链必须消费M真实提交的attestation，禁止测试直插替代；不得修改 canonical schema/migration/合同生成源 | 并发初始化、stage/终态线性化、绑定/回收、重复收费、租约竞态、重启数据完整性、H2/PostgreSQL 差异；发现合同缺口必须退回并重开 Stage 0/G/M |
| C | 四角色编排、LLM 长输出、工具调用、Tavily 与安全网关 | SSRF、DNS/重定向、Prompt Injection、点时越界、密钥外泄、工具死循环 |
| D | 讨论查询/历史/继续讨论 API、React 状态与完整讨论页，只消费M已冻结的scan-jobs后端；同时负责旧 Vue `apps/web` 两个付费入口的 sessionStorage/by-key 恢复与两种 cutoff 路径 | 内容截断、父链错误、轮询泄漏、规则动作与 AI 混淆、旧 Vue 漏实现、权限和幂等 |
| E | 手动时间窗集成、数据库级 AI 隔离、既有GuardUse/M/B故障注入、容器与真实链路；不得首次实现或替换G核心、M manual producer或B source/materializer seam | 业务锁后发出单语句 authority DML、ack后零持锁、350ms方向界、100ms消费、14:47:00/14:47:20/14:47:55/14:48:30/14:48:44/14:48:45/14:49:30/14:49:40 竞态、content seal/freeze、egress commit unknown、AI 阻塞手动扫描、重启、多实例与现场可用性 |
| F | 短线结果视图偏好迁移、资金去向默认展示、扫描快照卡移除 | 旧偏好迁移循环、误删后台 snapshot、资金流无数据误导、长期页面误伤 |

依赖 DAG 固定为 S -> A -> 0 -> G -> M -> B -> C -> D -> E -> F。S 和 A 都可作为独立可发布变更，但本分支在完整功能交付前不默认开启 AI。G未ACCEPTED时M不得开工，M未以真实durable manual/attestation通过验收时B不得开工；M至E共享并消费Stage0/G能力，严格串行。F只在E后开始。每阶段使用新开发Agent，审查可并行只读。canonical合同由Stage0独占，G核心文件由G独占，M的manual producer/attestation文件由M独占，B的source consumer/materializer文件由B独占；下游触碰任一上游独占seam必须停止、reopen对应阶段及全部下游并重跑审查。每个阶段head都必须可启动，B的MANUAL集成测试必须从M公开POST/service生成真实committed attestation，不能用Repository测试直插伪造上游完成。

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
7. 修复后使用新的 head SHA 重新生成审查包。当前阶段任何 head 变化都会使该阶段旧的规格 PASS 和对抗 PASS 同时失效，两个独立审查都必须对新 head 重跑；纯下游实现代码变化不反向失效已冻结 Stage 0，但触碰任一 Stage 0 合同文件会显式 reopen Stage 0，并使所有依赖阶段的 PASS 一并失效。
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
- 注入 TimeAuthority health proof 证明旧 cron 时间点零自动扫描，并在 fake Provider、H2 双连接、PostgreSQL 双实例和故障注入下验证：业务锁齐后才发单语句 authority DML、raw恰求值一次且ack后零未提交authority锁、350ms方向界/100ms消费、14:47:55 后零新 egress permit、marker 单赢家、commit unknown零外发、14:48:30 后零正文接受、14:48:44 后零 CONTENT_SNAPSHOT 授权、14:48:45 唯一不可逆结果/未决时503、运行期坏时钟全AI与manual认证fail-closed、回退+重启不重开窗口、高并发AI GuardUse不阻断manual保留连接，以及14:49:30手动认证准入和14:49:40 executionEligibility。

只有全部通过且无 Critical/Important 发现，才可向用户报告具备交付条件；合并或推送仍需遵循用户届时的明确指令，不把规格确认扩张成远程写授权。任何阶段反复失败都继续驳回修复，除非出现必须由用户决定的真实产品歧义或外部凭据阻塞。

## 25. 实施前仍需满足的依赖

- 用户书面确认本文。
- 书面实施计划列出具体文件、测试、阶段 SHA 和 Agent 门禁。
- 发布环境提供受控搜索所需的 TAVILY_API_KEY；没有密钥时不得伪称实时联网已验收。
- 发布环境提供至少 256 bit、base64url 无换行的随机只读代理 token，并保持 Web loopback、API 无宿主端口；未满足时 AI feature 保持关闭。
- 现有主分支基线测试通过；若存在基线失败，先记录并与本功能失败区分。
