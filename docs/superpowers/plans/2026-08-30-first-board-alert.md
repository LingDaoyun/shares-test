# 首板预警 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. This workspace must be implemented inline because the active session does not authorize sub-agent delegation.

**Goal:** 在现有 Spring Boot + React 系统中增加独立的“首板预警”页面，用 T-1 技术基线和盘中全市场快照筛选尚未触板的首板候选，并把昨日涨停股票作为独立观察通道。

**Architecture:** 后端新增独立 `firstboard` 模块。`FirstBoardBaselineService` 每个交易日只构建一次 T-1 历史技术基线，`FirstBoardAlertService` 将它与一批东财盘中行情合并并按确定性规则分级，Controller 提供快照与单股 20 日图表数据。前端新增独立路由，60 秒轮询快照，使用现有表格、弹层和 Toast 组件展示，不引入新的图表依赖。

**Tech Stack:** Java 17、Spring Boot、Jackson、现有 EastMoneyClient、JUnit 5/Mockito；React 18、TypeScript、Axios、Vitest、现有 Tailwind/UI 组件。

## Global Constraints

- 只改现有系统，不新建独立应用或服务。
- 不自动交易，不展示综合评分或自然语言触发原因。
- 默认排除创业板和科创板；页面开关打开后才包含，选择保存在本地。
- ST、退市整理、有效日线不足 60 日的股票始终排除。
- 昨日涨停不是排除项，进入“昨日涨停观察”通道。
- T-1 基线不完整时返回 `PREPARING` 或 `UNAVAILABLE`，不得用部分样本发信号。
- 非交易日回退到最近一个交易日收盘扫描，技术基线再前移一个交易日；不得直接禁用页面。
- 盘中全市场行情一次批量获取，不允许逐股请求盘中数据。
- 本项目当前禁止代理自行运行测试、构建、lint、类型检查、浏览器或运行态验收。本计划会补齐聚焦测试代码，但本轮不执行。

## Task 1: 扩展底层行情和交易日能力

**Files:**

- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Modify: `apps/api/src/main/java/com/aistock/research/trading/AsharePriceLimitRule.java`
- Modify: `apps/api/src/main/java/com/aistock/research/trading/TradingClockService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/trading/AsharePriceLimitRuleTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/trading/TradingClockServiceTest.java`

### Step 1: 写入底层契约测试

增加三个聚焦断言：东财 `f10` 解析为实时量比；涨停价按前收和板块比例精确到分；可找到前一交易日并跳过周末和已登记休市日。

```java
assertThat(quote.realtimeVolumeRatio()).isEqualByComparingTo("1.87");
assertThat(AsharePriceLimitRule.limitUpPrice("600000", new BigDecimal("10.01")))
        .isEqualByComparingTo("11.01");
assertThat(service.previousTradingDay(LocalDate.parse("2026-08-24")))
        .isEqualTo(LocalDate.parse("2026-08-21"));
```

### Step 2: 扩展 EastMoneyQuote，保持旧调用兼容

在 record 尾部加入 `BigDecimal realtimeVolumeRatio`，所有现有兼容构造器传 `null`；同时保留原 21 参数构造器并转发到新 canonical constructor，避免现有生产代码和测试桩大面积改写。

```java
public EastMoneyQuote(
        String symbol, String name, String market, String industry,
        BigDecimal latestPrice, BigDecimal changePercent, BigDecimal turnoverRate,
        BigDecimal volume, BigDecimal amount, BigDecimal peRatio, BigDecimal pbRatio,
        BigDecimal peTtm, String sourceName, String quoteUrl, Instant fetchedAt,
        LocalDate tradeDate, Instant marketTimestamp, BigDecimal totalMarketValue,
        BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice
) {
    this(symbol, name, market, industry, latestPrice, changePercent, turnoverRate,
            volume, amount, peRatio, pbRatio, peTtm, sourceName, quoteUrl,
            fetchedAt, tradeDate, marketTimestamp, totalMarketValue,
            openPrice, highPrice, lowPrice, null);
}
```

### Step 3: 批量行情加入 f10

把 `f10` 加入 `QUOTE_FIELDS`，在 `readQuote` 的最后一个参数写入：

```java
scaled(item, "f10", 2)
```

### Step 4: 增加精确涨停价和触板判断

```java
public static BigDecimal limitUpPrice(String symbol, BigDecimal previousClose) {
    if (previousClose == null) return null;
    BigDecimal multiplier = BigDecimal.ONE.add(limitPercent(symbol).movePointLeft(2));
    return previousClose.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
}

public static boolean hasTouchedLimitUp(String symbol, BigDecimal previousClose, BigDecimal highPrice) {
    BigDecimal limit = limitUpPrice(symbol, previousClose);
    return limit != null && highPrice != null && highPrice.compareTo(limit) >= 0;
}
```

科创板识别同时覆盖 `688` 和 `689`。

### Step 5: 增加 previousTradingDay

```java
public LocalDate previousTradingDay(LocalDate date) {
    LocalDate cursor = date.minusDays(1);
    while (isMarketClosedDay(cursor)) cursor = cursor.minusDays(1);
    return cursor;
}
```

## Task 2: 建立 T-1 技术基线和纯规则计算器

**Files:**

- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBoard.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBar.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaseline.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineAccess.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardTechnicalEvaluator.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardTechnicalEvaluatorTest.java`

### Step 1: 定义板块与内部基线模型

`FirstBoardBoard` 只允许 `MAIN/GROWTH/STAR/UNSUPPORTED`，集中处理代码前缀。`FirstBoardBaseline` 保存：证券基本信息、T-1 收盘、MA10/MA20、MA20 斜率、RSI14、MACD 柱、五条件命中数、T-1 量比、近 5 日最大量比、上影线比例、3 日收盘区间、昨日是否涨停、最近 20 根日线。

```java
record FirstBoardBaseline(
        String symbol, String name, String industry, FirstBoardBoard board,
        LocalDate baselineDate, BigDecimal previousClose,
        BigDecimal ma10, BigDecimal ma20, BigDecimal ma20Slope,
        BigDecimal rsi14, BigDecimal macdHistogram, int technicalPassCount,
        BigDecimal previousDayVolumeRatio, BigDecimal recentFiveMaxVolumeRatio,
        BigDecimal upperShadowRatio, BigDecimal threeDayCloseRange,
        boolean previousLimitUp, List<FirstBoardBar> recentBars
) {}
```

### Step 2: 用纯函数计算所有技术指标

`FirstBoardTechnicalEvaluator.evaluate(...)` 必须先按日期排序和去重，确认最后一根等于 T-1 且有效日线不少于 60。公式固定如下：

- MA10/MA20：最近 10/20 个收盘均值。
- MA20 斜率：`(当前MA20 / 5日前MA20 - 1) * 100`。
- RSI14：Wilder 平滑；无下跌且有上涨时为 100，涨跌都为 0 时为 50。
- MACD：EMA12 - EMA26，signal 为 DEA9，柱值为 `(DIF-DEA)*2`。
- 日量比：当日成交量 / 前 5 日成交量均值。
- 上影线比例：`(high - max(open, close)) / previousClose * 100`。
- 3 日收盘区间：`(max(close3)-min(close3))/min(close3)*100`。

五项条件严格为：`close>MA20`、`MA10>MA20`、`MA20 slope>0`、`RSI14>=50`、`MACD histogram>0`。

### Step 3: 写纯规则的边界测试

覆盖：不足 60 日返回空；五条件全满足；恰好四项；RSI 横盘为 50；量比、上影线和 3 日区间公式；昨日涨停标记不会被过滤。

### Step 4: 实现每日单飞基线构建

`FirstBoardBaselineService` 行为：

1. `@Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Shanghai")` 预热当日基线。
2. 首次请求没有基线时，通过同步锁只启动一个异步构建，立即返回 `PREPARING`。
3. 一次抓取全市场静态股票列表与 T-1 涨停池。
4. 排除不支持板块、ST/退市整理名称，再用固定大小线程池并发获取 T-1 之前的日线。
5. 所有任务完成后原子替换基线；构建失败记录 `UNAVAILABLE`，不得发布部分 Map。
6. `@PreDestroy` 关闭模块自有线程池。

对外仅暴露：

```java
FirstBoardBaselineAccess access(LocalDate tradeDate);
Optional<List<FirstBoardBar>> recentBars(LocalDate tradeDate, String symbol);
```

`FirstBoardBaselineAccess` 只包含 `PREPARING/READY/UNAVAILABLE`、快照和数据缺口，不暴露半成品。

## Task 3: 实现盘中筛选、分级、降级与 API

**Files:**

- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertLane.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertLevel.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertCandidate.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertCoverage.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBarSeries.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertService.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertController.java`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardAlertServiceTest.java`

### Step 1: 定义稳定的 JSON 契约

主接口：

```http
GET /api/first-board-alert/snapshot?includeGrowth=false&includeStar=false
```

返回 `status/tradeDate/baselineDate/generatedAt/quoteFetchedAt/autoRefreshAllowed/ruleVersion/coverage/latentCandidates/previousLimitUpCandidates/dataGaps`。

图表接口：

```http
GET /api/first-board-alert/bars/{symbol}
```

返回基线里的最近 20 根日线；这是详情弹层的次级只读接口，不触发外部逐股请求。

### Step 2: 写分级边界测试

测试数据只注入一个 ready baseline 和一批行情，覆盖：

- `0%`、`7%` 可进入，负数或大于 7% 排除。
- 当日最高价达到精确涨停价即排除。
- 默认排除创业板/科创板，开关后包含。
- 实时量比 `<0.8` 排除。
- 五项 + `>3.2` 为 `ACCELERATING`。
- 五项 + `[1.2,3.2]` 为 `STRONG`。
- 四项 + `>=0.8` 或五项 + `[0.8,1.2)` 为 `WATCH`。
- 昨日涨停沿用独立 level；10:30 前可强/加速，10:30-11:00 降一级，11:00 后最高 `WATCH`。
- 昨日量比和盘中冲高回落只修正同级排序，不改变准入。
- 行情失败时有旧快照返回 `STALE`，没有旧快照返回 `UNAVAILABLE`。

### Step 3: 实现单次批量融合

`FirstBoardAlertService.snapshot(...)` 每次刷新只调用一次 `fetchAshareQuoteSnapshot(6000)`。逐股合并时依次执行：板块开关、关键字段完整性、涨幅范围、未触涨停、实时量比阈值、技术项数和时间分级。

昨日涨停的时间规则基于 `TradingClockService.currentMarketDateTime()`：

```java
if (!time.isBefore(LocalTime.of(11, 0))) level = WATCH;
else if (!time.isBefore(LocalTime.of(10, 30))) level = downgrade(level);
```

排序使用显式 comparator，不生成隐藏总分：level、技术项数、实时量比、MA20 斜率、MACD 柱；昨日涨停同级内再用昨日量比优先和回撤惩罚修正。

### Step 4: 处理缓存与失败状态

- 4 种板块开关组合分别保留最近一次成功快照。
- 45 秒内复用同组合快照，避免用户连续点击重复拉取。
- 全量行情失败时复制最近成功数据并标记 `STALE`、附加缺口；没有可用成功快照时 `UNAVAILABLE`。
- `PREPARING/STALE/UNAVAILABLE` 的响应由前端禁止通知。
- `autoRefreshAllowed` 仅在当日交易窗口为 true；非交易日 `PREPARING` 由前端临时轮询至快照完成。

## Task 4: 加入前端数据契约、偏好和通知去重

**Files:**

- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Create: `apps/web-react/src/lib/firstBoardAlertPreferences.ts`
- Create: `apps/web-react/src/lib/firstBoardAlertNotifications.ts`
- Create: `apps/web-react/src/lib/firstBoardAlertPreferences.test.ts`
- Create: `apps/web-react/src/lib/firstBoardAlertNotifications.test.ts`

### Step 1: 增加与后端一一对应的 TypeScript 类型

```ts
export type FirstBoardAlertStatus = 'PREPARING' | 'READY' | 'STALE' | 'UNAVAILABLE'
export type FirstBoardAlertLane = 'LATENT' | 'PREVIOUS_LIMIT_UP'
export type FirstBoardAlertLevel = 'WATCH' | 'STRONG' | 'ACCELERATING' | 'REPEAT_STRONG' | 'REPEAT_ACCELERATING'
```

候选字段只包含技术面展示所需数字，不增加 `reason`、`score` 或建议文字。

### Step 2: 增加 API 函数

```ts
export function fetchFirstBoardAlertSnapshot(params: { includeGrowth: boolean; includeStar: boolean }) {
  return http.get<FirstBoardAlertSnapshot>('/first-board-alert/snapshot', { params }).then((res) => res.data)
}

export function fetchFirstBoardBars(symbol: string) {
  return http.get<FirstBoardBarSeries>(`/first-board-alert/bars/${encodeURIComponent(symbol)}`).then((res) => res.data)
}
```

### Step 3: 实现开关持久化

固定 key 为 `ai-stock:first-board-alert:boards:v1`，无值或解析失败时回落 `{includeGrowth:false, includeStar:false}`。只保存两个布尔值。

### Step 4: 实现页面通知去重

固定 key 为 `ai-stock:first-board-alert:notified:v1`。去重键为 `tradeDate:symbol:level`；同股票同级一天一次，升级后的新 level 可以再次通知。只对 `READY` 快照中的强级别触发 Toast，`WATCH` 不弹，昨日涨停 11:00 后自然只有 WATCH。

## Task 5: 实现独立页面、轮询、技术表格和 K 线弹层

**Files:**

- Create: `apps/web-react/src/pages/FirstBoardAlertPage.tsx`
- Create: `apps/web-react/src/components/firstboard/FirstBoardCandlestickChart.tsx`
- Create: `apps/web-react/src/pages/FirstBoardAlertPage.test.tsx`
- Modify: `apps/web-react/src/App.tsx`
- Modify: `apps/web-react/src/components/layout/pageMeta.ts`
- Modify: `apps/web-react/src/components/layout/pageMeta.test.ts`
- Modify: `apps/web-react/src/components/layout/NavRail.tsx`

### Step 1: 先写页面行为测试

Mock API 后覆盖：路由元信息与导航位置；默认两个开关关闭；READY 数据分成两张表；不渲染“触发原因/综合分”；点行后拉取图表并打开已有 `DetailOverlay`；`autoRefreshAllowed` 为 true 时创建 60 秒轮询；强级候选只弹一次 Toast。

### Step 2: 增加导航与路由

`PageKey` 加 `firstBoardAlert`，导航顺序放在 `短线右侧` 后面，路径为 `/first-board-alert`，`NavRail` 的路径与角标为 `首板`，大屏网格改为 9 列。`App.tsx` lazy load 新页面。

### Step 3: 实现页面状态和刷新

页面首次进入立即请求；手动刷新按钮可随时请求；后端返回 `autoRefreshAllowed=true` 或快照处于
`PREPARING` 时启动 60 秒 interval，切换板块开关后清除旧数据并按新范围重拉。请求竞态用递增
request id 丢弃旧响应。

### Step 4: 使用现有视觉体系展示两条通道

页面由顶部说明/状态区、板块开关、覆盖率摘要、`首板潜伏` 表和 `昨日涨停观察` 表组成。列为：

- 股票/行业
- 现价/涨幅
- MA10/MA20/MA20 斜率
- RSI14/MACD 柱
- 实时量比/T-1 量比/近 5 日最大量比
- 上影线比例或盘中回撤
- 3 日收盘区间
- 技术项数与级别

不得新增触发原因列、综合分列或买入建议文案。

### Step 5: 实现纯 SVG K 线和成交量图

`FirstBoardCandlestickChart` 接收 20 根日线，在固定 viewBox 中计算价格/成交量比例，绘制影线、实体和下方量柱；涨/跌使用项目既有红/绿语义。空数据展示 `Empty`。组件不引入第三方图表库。

### Step 6: 接入页面 Toast

每次 READY 快照后调用去重 helper，新增强级项用现有 `toast.warning` 或 `toast.success` 逐条展示“股票名 级别 现价/涨幅”，不播放声音、不请求浏览器通知权限。

## Task 6: 完成非执行式交付整理

**Files:**

- Review only the files listed above; do not run repository validation commands.

### Step 1: 逐项对照已确认规则

人工检查源码中阈值、边界符号、昨日涨停时间降级、开关默认值、无原因/无评分、失败状态和通知门槛是否与设计文档一致。

### Step 2: 明确未执行的验证

交付说明必须写明：已补测试代码；因用户对 `/Users/mac/Documents/shares-test` 的既有限制，未运行测试、构建、lint、类型检查、浏览器或运行态验收，不能声称这些验证已通过。

### Step 3: 提供关键文件入口

最终交付至少链接：后端 Controller、核心 Service、前端页面、技术图表组件和设计/计划文档。
