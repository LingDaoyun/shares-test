# 涨停看板与观察反馈实施计划

> **For agentic workers:** Execute the approved changes in this session; preserve the user's master-only workflow and user-owned automated testing boundary.

**Goal:** 在现有涨停看板增加首页刷新与可信状态、行业连板梯队，并接通日线查看、观察锚点和已有隔日反馈。

**Architecture:** 复用现有 React 组件、涨停快照、日线接口、浏览器首次观察记录和隔日反馈接口。只改前端，不增加后端接口、数据库迁移或跨日主线评分。

**Tech Stack:** React 18、TypeScript、Vite、现有 Docker Compose web 服务。

## Global Constraints

- 继续在 `master`，不新建分支/工作区，不自动提交或推送。
- 保留行业卡片按涨停家数排序、前三股预览及情绪弹窗。
- 连板梯队只使用 `consecutiveBoards`；与“几天几板”分开，缺失/无效值不当作首板。
- 取数时间不得冒充源行情时间；刷新失败保留原可用快照并明确标旧数据。
- 复用当前刷新互斥与请求完成后 60 秒轮询，手动刷新不叠加请求。
- 观察记录仍只保存在本浏览器；原始观察价不覆盖；反馈按 ID 对齐且每批不超过 50 条。
- 不把未知、未到期、过期或缺少行情当作零收益；隔日观察变化不称实际交易收益。
- 补充有意义的回归用例，但不运行自动化测试、行情扫描或业务验收；允许已授权的前端编译、本机 web 更新和部署基本核对。

## Task 1: 看板首页与行业梯队

**Files:** `LimitUpBoardPanel.tsx`、其现有两个测试文件。

- [x] 首页工具栏加入“刷新涨停看板”，共用现有 refresh。
- [x] 常驻显示交易日、快照获取时间、更新中/更新失败、数据缺口摘要。
- [x] 请求失败仍显示旧的可用行业卡片；成功但不可用的新快照按其不可用原因展示。
- [x] 每卡展示首板、二板、三板及以上家数，保留最高板和前三股预览。
- [x] 首页加入“观察与隔日反馈”入口，弹窗渲染 `CatchUpFollowUpPanel`。
- [x] 更新日期显示相关旧断言，增加刷新、错误保留、互斥及梯队用例。

## Task 2: 观察与隔日反馈

**Files:** 新建 `CatchUpFollowUpPanel.tsx` 及其测试，复用现有 storage/types/client。

**Interface:** `export function CatchUpFollowUpPanel()`；首页弹窗直接挂载。股票日线按钮复用 `StockKlineButton({symbol, name, tradeDate})`。

- [x] 加载浏览器已有首次观察，并监听本页 storage 保存事件及跨标签 storage 事件。
- [x] 按最近观察分页，每页最多 50 条，用户点击“更新隔日反馈”调用已有接口。
- [x] 展示首次观察日期/价、板块/锚点、下一交易日、反馈状态、行情时间和有效价格变化。
- [x] 翻页或记录集改变使旧请求结果失效；失败保留首次观察信息，提示重试；返回身份缺失/重复不错误配对。
- [x] 空记录显示如何通过行业明细建立观察；明确本浏览器留样和观察价格比较口径。
- [x] 增加空态、分页、ID 对齐、未知不记零、请求过期与错误用例。

## Task 3: 日线与锚点快捷操作

**Files:** 新建 `StockKlineButton.tsx` 及测试；修改 `LimitUpIndustryOverlay.tsx`、`LeaderCatchUpPanel.tsx` 和对应测试。

**Interface:** `StockKlineButton({symbol: string, name: string, tradeDate: string})`；`LeaderCatchUpPanel` 增加可选 `initialLeaderSymbol?: string`、`onLeaderSymbolChange?: (symbol: string) => void` 和 `restoreAnchorFocus?: boolean`。行业弹窗按锚点设 key、切换时整体重置，仅在用户操作锚点后恢复选择框焦点，首次打开保留原弹窗回焦行为。

- [x] 按需加载现有 `fetchPotentialLeaderBars` 并复用 `LeaderCandlestickChart`；展示实际日线截至日、前复权和观察日，空态/异常可重试。
- [x] 已涨停表与补涨候选提供日线入口，已涨停行提供“设为观察锚点”；不自动选择共同板块。
- [x] 切换锚点重置共同板块、旧结果及请求状态，保留原异步响应身份校验。
- [x] 静态审阅集成改动并修复明确问题；编译前端、重建并更新 web 容器，核对新静态产物及健康状态。

## Review and handoff

- [x] 独立代码审阅完整 diff，确认范围、异步过期、数据来源和观察口径。
- [x] 报告改动、本机页面地址、编译/部署结果及未执行自动化测试的边界。

## 实施记录

- 前端编译通过，web 容器已更新且健康，32 个静态文件与本次构建逐一核对一致。
- 独立静态审阅发现的日线截至日期与锚点回焦问题已修复并复核。
- 回归用例已补充，自动化测试与业务验收未执行；代码未提交或推送。
