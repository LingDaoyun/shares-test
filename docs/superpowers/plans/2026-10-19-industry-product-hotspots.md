# 产业与产品热点追踪实现计划

**目标：** 移除长期价投入口，主动采集产业及产品新闻，识别催化事件，并给出有新闻或主营证据支持的 A 股推荐。

**架构：** 复用 Spring RestClient、现有大模型和行情客户端、PostgreSQL 与 React UI。独立 RSS 采集器读取媒体公开订阅；两阶段研判先发现事件和候选，再读取真实公司身份与主营材料进行推荐。结果保存到数据库，页面只读取已保存快照；定时与手动刷新共用后台任务。

**约束：** 在当前分支实现，保留现有未提交工作，不创建分支或工作树。首轮仅实现源码；用户随后明确授权“编译部署到本地，执行数据库迁移，把热点追踪启动起来，你自己测试一下”，本轮据此执行测试、构建、迁移和本地部署。不提交。没有足够证据时保留事件及缺口，不凑推荐。

## 1. 新闻来源

- [x] 新建 `apps/api/src/main/java/com/aistock/research/integration/news/`，提供 `IndustryNewsClient.fetchRecent(int)`、`IndustryNewsItem`、`IndustryNewsFetchResult`。
- [x] 默认读取 36氪快讯、36氪文章、IT之家 RSS；保留原始链接、发布时间、摘要，限定最近7天。
- [x] 去重并均衡来源；未知日期、失败来源、截断和订阅覆盖范围明确记录。

订阅依据：[36氪官方 RSS 中心](https://www.36kr.com/rss-center)、[IT之家 RSS 公告](https://m.ithome.com/html/37.htm)。本地实机采集已覆盖全部三个来源；36氪需要 www 域名，并使用带 `+0800` 时区的空格分隔发布时间格式。

## 2. 事件研判与股票推荐

- [x] 新建 `apps/api/src/main/java/com/aistock/research/hotspot/` 及数据库迁移；提供 `GET /api/hotspots/current`、`POST /api/hotspots/refresh`。
- [x] 从新闻动态发现产业/产品事件，提出候选股票；通过行情身份和公司主营材料约束第二阶段推荐，不依赖旧80只公司池和固定题材目录。
- [x] 每轮最多6个事件、每事件3只推荐；保存来源、推荐理由、传导机制、关联类型与风险。关联类型区分新闻提及、主营匹配、产业链推演。
- [x] 保存首次推荐时间、首次观测价和报价来源时间；保留最近7天已有事件。原始新闻独立展示，模型未配置或失败时仍可阅读新闻。
- [x] 默认每2小时自动更新，手动更新间隔15分钟；相同新闻复用研判，后台任务互斥，失败保留上次结果。

## 3. 热点页面及长线入口移除

- [x] 新建 `apps/web-react/src/types/hotspot.ts`、`api/hotspots.ts`、`pages/HotspotPage.tsx`、`components/hotspot/`。
- [x] 页面沿用现有色彩与组件，以新闻事件为主，展示产业链推演、推荐股票、原文、时效及关注操作。空态、任务进行中和部分失败给出可操作说明。
- [x] 修改 `App.tsx`、`components/layout/pageMeta.ts`、`NavRail.tsx`：增加 `/hotspots`，移除长期价投菜单，旧 `/market` 重定向至热点追踪。不删除其他模块共享公司/行情能力。
- [x] 更新 README 使用方式与范围，保留各阶段实际验证和部署边界。

## 运行配置与交付边界

| 配置项 | 默认值及用途 |
| --- | --- |
| `research.hotspots.enabled` | `true`，控制自动追踪；手动更新仍可用 |
| `research.hotspots.interval-minutes` | `120`，自动更新间隔，最小15分钟 |
| `research.hotspots.initial-delay-ms` | `20000`，启动后调度延时 |
| `research.hotspots.check-delay-ms` | `60000`，到期检查间隔，不代表新闻刷新频率 |
| `research.hotspot.news.kr36-newsflash-url` | `https://www.36kr.com/feed-newsflash` |
| `research.hotspot.news.kr36-article-url` | `https://www.36kr.com/feed-article` |
| `research.hotspot.news.ithome-url` | `https://www.ithome.com/rss/` |

新闻地址为空时停用对应来源。来源与调度配置使用项目的 Spring 配置机制；模型使用现有系统配置。最多保留40个最近更新且仍在7天新闻窗口内的事件。

内容相同且已有成功研判时复用结果；模型设置与提示词版本也参与复用判断。来源或研判失败不写入成功标记。首次推荐报价及其来源时间单独保存，后续报价更新不覆盖首次记录。

## 2026-10-19 本地部署与验证

- 前后端生产构建通过；后端热点与新闻定向测试25项、真实 PostgreSQL 迁移及持久化测试6项、前端定向测试33项通过（合计64项，未运行全部项目测试）。
- 迁移前已备份业务数据库；应用启动执行 V11，当前最新版本11、失败迁移0。隔离迁移测试库已清理。
- `ai-stock-api` 与 `ai-stock-web` 已重新构建并启动，健康检查通过；本地 JAR 哈希、前端首页与实际服务资源一致。
- 自动采集已在17:45完成并保存60条新闻：36氪快讯、36氪文章、IT之家各20条。API、前端代理和数据库快照一致，手动冷却行为正常，下次自动更新19:45。
- 浏览器检查了热点入口、旧 `/market` 跳转、新闻搜索、事件详情、原文链接、三个来源展示和无横向溢出；浏览器标题已改为“AI 短线投研平台”。
- 截至17:47，本地模型配置仍为 `apiKeyConfigured=false`，数据库模型修订0、无已保存密钥。当前状态为PARTIAL、0只推荐；真实模型推荐链路尚未完成验证，等待用户保存有效模型配置。

运行证据位于 `.codex-run/hotspot-release-20261019/`：构建日志、迁移日志、`verification.json`、快照和页面截图。数据库备份包含业务数据，仅保存在本机，不提交或上传。
