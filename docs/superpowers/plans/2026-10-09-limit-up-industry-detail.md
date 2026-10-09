# 涨停行业三级详情实施计划

> 使用 executing-plans 在当前分支实施；定向测试先失败再通过，最后一次独立代码复审。保留工作区已有改动。

**Goal:** 点击涨停看板的行业家数，展示全部涨停股及最多 30 家上涨未涨停股。

**Architecture:** 复用 `DetailOverlay`，新增按行业及看板日期查询的 API。涨停明细来自原看板快照；行业报价按涨幅分页抓取、校验覆盖及日期，再排除涨停股并截取前 30 家。行业行情失败仅影响未涨停列表。

**Tech Stack:** Spring Boot、Java 17、React、TypeScript、JUnit、Vitest。

**Spec:** `docs/superpowers/specs/2026-10-09-limit-up-industry-detail-design.md`，用户已确认。

## Task 1: 行业数据接口

Files: `EastMoneyClient.java`、`ShortTermLimitUpBoardService.java`、`ShortTermController.java`；新建 `ShortTermLimitUpIndustrySnapshot.java`、`ShortTermIndustryRisingStock.java` 与独立测试。

接口：`GET /api/short-term/limit-up-board/industry?industry=半导体&date=2026-10-08`；返回行业、交易日、快照时间、`limitUpCount`、全部 `limitUpStocks`、最多 30 个 `risingStocks`、`risingAvailable`、`dataGaps`、`quoteSource`。

- [x] 写失败测试：跨页报价、规模变化与重复冲突；涨停行业过滤、两组不重复、涨幅排序与 30 家上限、缺失行情保留涨停股、历史日期不混入最新行情。
- [x] 运行 `mvn -pl apps/api -Dtest=EastMoneyIndustryQuoteTest,ShortTermLimitUpIndustryServiceTest test`，确认缺少接口的失败。
- [x] 实现 `fetchIndustryBoardQuoteSnapshot(String industryName)`：每页 100，`fid=f3`，代码去重并拒绝冲突；总数稳定且实际覆盖一致才标记完整。
- [x] 实现 `industrySnapshot(String industry, LocalDate date)`：复用涨停快照，行业报价日期一致且有有效价格和涨幅，再排除涨停池证券，涨幅降序、成交额降序、代码升序，截取 30 家。
- [x] 定向测试通过后检查差异并记录。

## Task 2: 行业详情与交付

Files: `LimitUpBoardPanel.tsx`、`api/client.ts`、`types.ts`；新建 `LimitUpIndustryOverlay.tsx` 及 DOM 交互测试。

接口：行业选择包含 `industry`、`tradeDate` 与当前看板涨停明细；请求 `fetchShortTermLimitUpIndustry(industry, date)`。

- [x] 先写失败交互测试：点击行业家数、日期传递、三级详情与返回、30 家边界、加载失败保留涨停明细。
- [x] 运行新增 Vitest 文件，确认缺少点击/详情行为而失败。
- [x] 家数改按钮；新增详情层的两组表格、加载、空数据、重试和返回；保留上层看板及滚动位置。
- [x] 运行新交互测试、原看板及 DetailOverlay 回归；`npm --prefix apps/web-react run build`、API 定向回归及打包。
- [x] 独立复审；本功能增量已整理，部署验证后单独提交。
- [x] 备份两端镜像，检查数据库迁移不变，重建并更新 API/Web，验证健康、页面资源与新接口。

## Review Focus

行业分页必须覆盖全部行业而非仅成交额首屏；涨停股不截断、不与未涨停股重复；不得混用日期。行业行情异常要保留涨停明细，三级关闭仅返回上层。

## Execution record

- 用户已确认范围与交付。沿用当前 `codex/five-day-observation`，原有源码已备份到 `.codex-run/limit-up-industry-20261009-084929/before`。
- 预检：API 与前端共享字段命名一致；原 DetailOverlay 已支持独立焦点及滚动锁，采用同级门户挂载详情层。
- Task 1: 数据接口已实现，先观察缺少类型与方法的编译失败，再通过定向回归；后端最终定向回归及打包 163/163 通过（`api-package-final.log`）。
- Task 2: 新点击详情先失败，再通过；现有看板与浮层回归 27/27，TypeScript/Vite 构建通过（`web-tests-final.log`、`web-build-final.log`）。Escape 仅关闭三级详情并恢复行业按钮焦点。
- 行情格式回归发现 `fltt=2` 与共享整数报价解析不一致，测试先失败，改为 `fltt=0` 后通过，现价、涨幅、换手率单位一致。
- 独立复审：`review_industry_detail` 只读检查本功能补丁，没有明确缺陷或阻断项，允许提交与部署。
- 可独立提交的源码快照已验证：API 159/159、前端 27/27 与构建通过；原有五日观察/筹码源码不纳入本次提交。部署使用当前完整工作区构建，保留原运行功能。
- 两端旧镜像与运行文件已备份到证据目录，5 个数据库迁移与新构建逐项一致。
- 现场追加修正：行业目录请求原先耗时 73.34 秒；首个失败页停止翻页、拒绝缓存缺页目录并支持对象形式的 diff。三个定向测试先失败，修改后随 163 项回归通过。更新后实际行业接口 HTTP 200，用时 27.02 秒（包含重新取得涨停快照）。
- 现场追加修正：快速 Escape 可在焦点尚未切换时关闭上层。新增复现测试先失败，父层关闭动作优先关闭行业层后，27 项前端回归通过。
- 部署证据：API/Web 均 healthy，API /actuator/health=UP，Web HTTP 200；运行 JAR 与测试构建一致，Web index 与本地 dist 一致。最新运行信息在 `release-final.json`，旧镜像与文件仍保留。
- 行情可用性：电池行业的 2026-10-08 涨停明细实际返回 8 家；行业目录/成分行情仍遇 curl 56 EOF，`risingAvailable=false` 并展示明确缺口，不把未涨停 0 家当作已确认结果。涨幅排序/30 家边界由回归数据验证，尚未取得可用的实时行业报价。
- 浏览器最终核对：临时把看板日期设为 2026-10-08，使用真实接口数据。电池 8 家全部展示；快速 Escape 与返回按钮均只关闭行业层；390px 手机视口内详情宽度为 12..378px，无溢出。历史日期钩子已移除，浏览器检查任务已结束（`browser-final.json`、`industry-mobile-final.png`）。
