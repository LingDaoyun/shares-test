# 删除短线 Agent 讨论功能设计

## 目标

完整删除当前工作区中尚未提交的“短线候选 Agent 讨论”功能，包括入口、状态管理、接口、后台编排、存档模型、专属推荐来源和测试，使短线模块重新只保留规则扫描、候选展示与既有交易复盘入口。

本次删除只针对 `com.aistock.research.shortterm.committee` 这一套新功能。项目原有的通用 Agent 委员会、证据复核和其他模块依赖的 `com.aistock.research.committee` 必须保留。

删除完成后继续实施已经批准的“今日成交量 / 前3日均量”股票条目展示。

## 完整删除范围

### 后端专属实现

删除以下未跟踪目录及其全部文件：

- `apps/api/src/main/java/com/aistock/research/shortterm/committee/`
- `apps/api/src/test/java/com/aistock/research/shortterm/committee/`

从共享文件中移除只为短线 Agent 讨论新增的内容：

- `LlmChatClient` 的单次请求超时和最大输出 token 重载；原有四参数 `completeJson` 恢复直接使用既有配置。
- `RecommendationSource.SHORT_TERM_AGENT_COMMITTEE`。
- `application.yml` 的 `research.short-term.committee` 配置块。
- `schema.sql` 的 `short_term_committee_discussion` 表与索引声明。
- README 中短线 Agent 讨论能力说明和使用章节。

`research.short-term.schedule.enabled: false` 与委员会无关，必须保留。

### 前端专属实现

删除以下未跟踪文件：

- `apps/web-react/src/components/shortterm/AgentCommitteePanel.tsx`
- `apps/web-react/src/components/shortterm/AgentCommitteePanel.test.tsx`
- `apps/web-react/src/store/shortTermCommitteeStore.ts`
- `apps/web-react/src/store/shortTermCommitteeStore.test.ts`

从共享文件中移除：

- `api/client.ts` 的三个短线委员会 API 方法和类型导入。
- `types.ts` 的全部 `ShortTermCommittee*` 类型。
- `ShortTermPage.tsx` 的委员会 store、存档恢复、讨论面板、按钮、候选映射和“Agent推荐”标签。
- `ShortTermPage.test.tsx` 的委员会 mock、fixture、store reset 和专项测试。

短线页面中已经进行的计划任务状态卡精简、V4 权重调整、扫描流程改动以及其他非委员会变化必须保留。

### 开发过程产物

删除只描述该功能的未跟踪方案文件：

- `.zcode/plans/plan-sess_7b0b6d0d-a88f-4078-8f42-8cf96a42bd42.md`

保留 `.zcode` 下其他无关方案文件。

## 数据与运行边界

本次只删除源码中的建表声明，不添加 `DROP TABLE`，也不连接或修改本地、测试或生产数据库。若某个运行环境曾经创建过 `short_term_committee_discussion`，遗留空表不会被本次源码修改主动删除。

不删除任何既有交易复盘数据。由于该功能尚未提交，源码中移除 `SHORT_TERM_AGENT_COMMITTEE` 后不再允许新建该来源的推荐证明；已经存在的未知外部数据不在本次范围内。

## 必须保留的并行改动

以下当前未提交变化与短线 Agent 讨论无关，删除时不得回退：

- 短线 V4 金叉、量能、换手、收盘强度权重调整及其测试。
- `research.short-term.schedule.enabled: false`。
- 手动与定时扫描页面状态卡精简。
- 交易复盘的建仓时间、持仓排序、总计盈亏和相关测试。
- 交易反馈 store、买入按钮测试和其他未明确归属于委员会的修改。

## 删除后的三日成交量实施顺序

1. 先完成短线 Agent 讨论源码拆除并验证无残留引用。
2. 在清理后的 `ShortTermTechnicalSnapshot` 中增加 `todayVolume`、`averageVolume3`、`volumeRatio3`。
3. 后端使用最新日 K 与紧邻的前三个交易日一次性计算并返回。
4. 股票条目展示“今日量 / 前3日均”的具体值和倍数。
5. 三日对比只展示，不进入评分、过滤、排序或交易动作。

## 验证与验收

### 静态残留检查

删除后，除历史设计资料和项目原有通用 Agent 能力外，业务源码中不得再出现：

- `ShortTermCommittee`
- `/short-term/committee-jobs`
- `short_term_committee_discussion`
- `SHORT_TERM_AGENT_COMMITTEE`
- `AgentCommitteePanel`
- `shortTermCommitteeStore`
- 短线页面文案 `Agent讨论`、`Agent推荐`

### 回归测试

- 后端短线、LLM 通用委员会和交易复盘测试通过，证明通用 Agent 能力仍可编译使用。
- 前端 `ShortTermPage`、交易复盘和成交量格式化聚焦测试通过。
- 前端生产构建通过。
- Git 差异审计证明只移除了短线 Agent 讨论，列出的并行改动仍然存在。

### 完成边界

本地源码与测试通过只能证明当前工作区完成删除和新增展示，不代表线上服务已经部署。若将来需要清理运行库中的遗留表，必须另行确认目标数据库、备份和恢复方式。
