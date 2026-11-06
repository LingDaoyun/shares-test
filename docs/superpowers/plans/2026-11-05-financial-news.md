# 消息面重构实施计划

> 用户已于 2026-11-05 确认原位重构方案；在当前工作区执行，保留原有未提交工作。

**Goal:** 在 `/policy` 提供持续更新的财经新闻与官方金融发布，保留行业解读。

**Architecture:** 新增轻量消息快照服务，复用 Spring 调度、RestClient、Jsoup、PostgreSQL JSONB 和现有模型客户端。消息采集不触发模型，单条解读由用户按需请求并缓存。原有政策历史与热点推荐接口保持兼容。

**Tech Stack:** Java 17 / Spring Boot 3 / PostgreSQL / React / TypeScript。

## 约束

- 财经媒体每 10 分钟、官方栏目每 30 分钟尝试更新；应用运行时生效。
- 首批来源：东方财富、财联社、中国人民银行、证监会、金融监管总局、财政部、外汇局；逐源报告成功、空数据或失败，禁止虚构已接通状态。
- 新闻保留近 30 天，最多 2000 条；按原始发布日期排序，日期只有年月日时不虚构时分秒。只收有可核验原始文本、原文地址和日期的条目；无独立标题的财联社电报以原文首句或前120字符展示，不生成改写标题。
- 按 URL 去重；同源同标题去重。跨来源同标题允许保留，以保全官方与媒体归属。
- 来源更新失败保留已有有效消息和上次成功时间；前端显示来源状态。服务重启恢复已保存数据。
- 页面 GET 只读；刷新异步执行；消息未完成解读也正常展示。
- AI 只依据现有标题/摘要，区分原始事实和推演，不编造正文、投资结论或来源；解读失败不覆盖已保存成功结果。
- 现有行业详情、关注、研判历史及其他策略 API 保持兼容。不新建通用爬虫框架。

## 接口契约

Java records 统一位于 `com.aistock.research.news`，由后台任务负责创建。

```java
FinancialNewsItem(String id, String category, String title, String source,
                 String url, String publishedAt, String summary)
NewsSourceStatus(String id, String name, String category, String url,
                 String status, int itemCount, Instant lastAttemptAt,
                 Instant lastSuccessAt, String message)
FinancialNewsFetchResult(List<FinancialNewsItem> items, List<NewsSourceStatus> sources)
NewsAnalysis(String itemId, String summary, List<String> affectedSectors,
             String transmission, List<String> watchPoints, List<String> risks,
             String evidenceUrl, Instant analyzedAt, List<String> dataGaps)
FinancialNewsSnapshot(String status, Instant startedAt, Instant completedAt,
             List<FinancialNewsItem> items, List<NewsSourceStatus> sources,
             Map<String, NewsAnalysis> analyses, List<String> dataGaps)
```

类别：`MEDIA` / `OFFICIAL`。来源状态：`READY` / `EMPTY` / `FAILED`。
快照状态：`IDLE` / `RUNNING` / `READY` / `PARTIAL` / `FAILED`。
采集客户端：`integration.news.FinancialNewsClient.fetch(String category, Instant now)`。
API：`GET /api/news/current`、`POST /api/news/refresh`（202）、`POST /api/news/{id}/analysis`。
解读 POST 返回 `NewsAnalysis`，已存在时复用成功结果。

## 任务与验证

- [x] 1. 采集：创建 `integration/news/FinancialNewsClient.java` 与必要的来源解析辅助类；先用真实页面形状的测试验证日期、相对链接、去重、缺失与未来日期、动态源失败，再接入来源。成功与失败均产生明确来源状态。每次请求有时间与体积上限。
- [x] 2. 后台：创建 `news/` 的数据契约、仓库、服务、控制器、解读服务及 `V12__financial_news.sql`。先写调度、失败保留、重启、去重、只读 GET、按需解读证据绑定测试，再实现。刷新不触发旧政策全量采集。
- [x] 3. 前端：创建消息列表组件和独立 API/类型文件，`PolicyIndustryPage` 默认消息页签，原内容移至行业解读页签；更新导航名称。先验证只读加载、刷新轮询、过滤与解读交互；复用原有弹窗。
- [x] 4. 集成：运行新后端测试、政策兼容测试、前端相关测试和生产构建；在独立测试库验证 Flyway 迁移；用实际接口核验首轮采集来源状态；浏览器检查消息与行业页签。

验证命令：

```sh
mvn -pl apps/api -Dtest='FinancialNews*Test,NewsAnalysis*Test,PolicyDaily*Test,PolicyTrendReviewTest' test
cd apps/web-react && npm test -- src/pages/PolicyIndustryPage.test.tsx src/components/news src/components/layout/pageMeta.test.ts
cd apps/web-react && npm run build
./scripts/verify-postgres-migrations.sh
```

部署只更新本机工程服务；不发送消息、不修改服务器、不上传或提交混杂的历史工作。

## 验收记录

2026-11-05：后端/迁移/政策兼容 81 项通过（无跳过），隔离前端消息/行业/路由等 40 项通过，生产构建及独立审查通过。本机首轮真实采集七个来源全部 READY，共132条消息，数据库与页面一致；原28个行业方向可读取。当前模型API Key未配置，已验证未配置响应不会生成或缓存伪解读。仅在当前本机运行版本中添加新消息类与V12、替换本次前端文件，其他工作区修改未一并发布。验证材料在 `.codex-run/financial-news-20261105/`。
