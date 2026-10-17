# 短线近端筹码压力实施计划

> **For agentic workers:** Use subagent-driven-development for the bounded model task and inline integration for ranking. The user subsequently authorized testing, rebuilding and deployment on 2026-09-24.

**Goal:** 将现价附近的上方估算筹码作为短线排序扣分项，并展示具体依据。

**Architecture:** 复用日级筹码模型原始价格桶，在压缩展示桶前计算上方 0～5%、5～10% 占比与距离加权占比；仅从现有排序分减去压力扣分，不切换旧版综合筹码排序，不新增日周月三套评分。

**Tech Stack:** Java records / Spring Boot / React TypeScript。

**验证授权：** 初次交付仅改代码；用户随后明确要求“测试一下，然后重新构建部署”，本轮执行测试、构建、部署和运行检查。仍不提交或推送。

## Global Constraints

- 仅修改短线筹码、排序和其解释，不改其他工作区改动。
- 本轮按用户追加授权验证并重新部署；数据库测试仅使用专用临时 PostgreSQL 容器，不使用业务库。
- 不提交或推送本轮改动。
- 所有筹码是模型估算，不标注为主力意图、真实持仓或已验证策略收益。
- 缺失、冲突、日期不匹配的数据不当作零压力；保留未评估说明。
- 本轮不增加硬淘汰规则，不改变四因子底分、入选条件或交易动作。

## 计算与接入契约

新增 `chip.ChipOverheadPressure`：`referencePrice`、`nearChipRatioPercent`、`fartherChipRatioPercent`、`weightedChipRatioPercent`（均 BigDecimal）。

对原始归一化桶，现价为 P、桶价为 p、筹码百分比为 r：

```text
near  = sum(r), P < p <= 1.05 P
farther = sum(r), 1.05 P < p <= 1.10 P
weighted = sum(r * (1 - (p / P - 1) / 0.10)), P < p <= 1.10 P
rankingBase = clamp(v2RankingScore + crossSectionAdjustment + volatilityContribution)
penalty = min(rankingBase, weighted / 100 * near-overhead-max-penalty)
ranking = rankingBase - penalty
```

范围 5% / 10%、最大扣分默认 10 分是待验证的初始参数，不代表统计最优。最大扣分通过 `research.short-term.chip.near-overhead-max-penalty` 配置；设 0 可停用。`OFF` 不参与，`SHADOW` 只显示估算，`ACTIVE` 才实际扣分，本次默认改为 ACTIVE。旧综合筹码分仍只用于已有诊断。

有效数据要求：本地 quality=VALID，筹码日期与行情日期一致，verification=VERIFIED 或 SINGLE_SOURCE，且校验系数大于 0；盘中结果标注盘中估算。不通过时扣分为 0，但明确未评估，不输出低压力结论。

## Task 1 — 原始桶近端指标

- [x] 新增 `ChipOverheadPressure.java`。
- [x] 在 `LocalChipDistributionCalculator.summarize` 用原始桶计算指标。
- [x] 在 `LocalChipDistribution`、`ShortTermChipSnapshot` 添加 nullable `shortTermPressure`，保留旧构造器；不足数据为 null。
- [x] `ChipStructureScorer` 与 `withAdditionalGaps` 传递新字段，模型版本改为 `short-term-chip-v3-near-overhead`。

## Task 2 — 排序与证据

- [x] `ShortTermChipSettings`、`application.yml` 增加最大扣分配置并开启 ACTIVE。
- [x] `ShortTermSupplyDemandScorer` 增加近端压力扣分及缺失说明；旧重载保持兼容。
- [x] `ShortTermSupplyDemandScore` 保存本次扣分和解释。
- [x] `ShortTermScoreBreakdown` 增加 nullable 的有符号 `chipPressureAdjustment`，旧构造与历史记录不补造数值。
- [x] `ShortTermService` 传递配置、记录扣分贡献、风险与独立证据；复制排名诊断时保留新字段。
- [x] 仅更新 `RecommendationSource.SHORT_TERM` 为 `short-term-right-side-v5-near-overhead`，区分新旧排序记录；四因子权重版本不变。

## Task 3 — 用户可见解释

- [x] 扩展前端类型，在已有“当前排序贡献”列出近端筹码调整。
- [x] 保留现有通用证据展示，不恢复筹码图或新增页面。

## 验证重点

- 同量筹码位于现价上方 2% 与 8% 时，前者距离加权占比及扣分应更大；现价及其下方不计入。
- 5% 的桶只计近端，10% 的桶计远端但距离权重为 0，10% 以外不计入；不重复计数。
- 原始桶统计不受展示桶压缩数量影响。
- 缺失、日期不匹配、冲突或过期不扣分，并展示未评估；有效且上方无筹码才显示真实 0。
- OFF 关闭、SHADOW 只观察、ACTIVE 扣分；最大扣分设 0 可停用。
- 旧报告缺少新字段时不反推历史扣分；新候选保留日期、参考价、分段占比、距离加权占比及扣分依据。
- 入选资格、金叉优先层级、交易动作及成熟度排序仍在前；近端筹码影响这些条件相同时的排序分。
- 先封顶后扣分，避免高分候选吞掉罚分：基础 99、其他贡献 11、筹码罚分 8，最终应为 92，而非 100。
- 对比新旧规则的 T1/T2 净收益、回撤和错过上涨的比例，不能仅凭代码变更判定选股改善。

本轮软件测试和部署检查不等同于策略历史收益验证，未开展收益回测。
