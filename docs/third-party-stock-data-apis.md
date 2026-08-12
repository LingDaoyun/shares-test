# 第三方股票市场数据接口对接文档

更新时间：2026-08-11

本文按当前代码实现梳理工程中直接调用的第三方股票市场数据接口。范围包括行情、K 线、资金流、行业板块、财务指标、公告/定期报告和筹码外部认证；不包括 DeepSeek/OpenAI/Kimi 等大模型接口，也不包括政府政策网站抓取接口。

主要代码入口：

| 入口 | 说明 |
| --- | --- |
| `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java` | 东方财富与腾讯行情主入口，覆盖行情、K 线、分时、资金流、行业、财报。 |
| `apps/api/src/main/java/com/aistock/research/integration/tushare/TushareChipClient.java` | Tushare 筹码外部认证。 |
| `apps/api/src/main/java/com/aistock/research/integration/cninfo/CninfoClient.java` | 巨潮资讯公告列表、公告详情链接、附件下载链接。 |
| `apps/api/src/main/java/com/aistock/research/filing/FilingPdfTextService.java` | 下载巨潮公告 PDF 并抽取正文。 |

## 1. 配置项

这些稳定的数据源配置位于 `apps/api/src/main/resources/application.yml`，部署时可通过同名 Spring Boot 环境变量覆盖。大模型和政策源的动态配置另存于数据库，见 `docs/runtime-config.md`。

| 配置键 | 默认值 | 用途 |
| --- | --- | --- |
| `research.live-data.eastmoney-quote-url` | `https://push2.eastmoney.com/api/qt/clist/get` | 东方财富全市场 A 股行情分页。 |
| `research.live-data.eastmoney-financial-url` | `https://datacenter-web.eastmoney.com/api/data/v1/get` | 东方财富数据中心年报财务指标。 |
| `research.live-data.eastmoney-fund-flow-url` | `https://push2.eastmoney.com/api/qt/ulist.np/get` | 东方财富个股实时/批量资金流。 |
| `research.live-data.eastmoney-fund-flow-minute-url` | `https://push2.eastmoney.com/api/qt/stock/fflow/kline/get` | 东方财富分钟资金流。 |
| `research.live-data.cninfo-announcement-url` | `https://www.cninfo.com.cn/new/hisAnnouncement/query` | 巨潮公告查询。 |
| `research.live-data.filing-limit` | `12` | 单只股票最多拉取公告数。 |
| `research.live-data.filing-pdf-parse-limit` | `2` | 单只股票最多解析 PDF 正文数。 |
| `research.live-data.filing-pdf-max-pages` | `6` | 单份 PDF 最多解析页数，上限 20。 |
| `research.short-term.chip.tushare.enabled` | `false` | 是否启用 Tushare 筹码认证。 |
| `research.short-term.chip.tushare.base-url` | `https://api.tushare.pro` | Tushare API 地址。 |
| `research.short-term.chip.tushare.token` | `${TUSHARE_TOKEN:}` | Tushare Token，必须通过环境变量注入。 |
| `research.short-term.chip.tushare.connect-timeout-ms` | `1200` | Tushare 连接超时。 |
| `research.short-term.chip.tushare.read-timeout-ms` | `1800` | Tushare 读取超时，也用于并发排队等待。 |
| `research.short-term.chip.tushare.max-concurrency` | `4` | Tushare 最大并发。 |

## 2. 代码与市场编码规则

| 场景 | 规则 | 示例 |
| --- | --- | --- |
| 东方财富 `secid` | `6` 开头映射为 `1.{symbol}`；`0`、`3`、`4`、`8`、`92` 开头映射为 `0.{symbol}`。 | `600000 -> 1.600000`，`000001 -> 0.000001`。 |
| 腾讯行情代码 | `6` 开头 `sh{symbol}`；`0`、`3` 开头 `sz{symbol}`；`4`、`8`、`92` 开头 `bj{symbol}`。 | `600000 -> sh600000`。 |
| 东方财富 F10 代码 | `6` 开头 `SH{symbol}`；`0`、`3` 开头 `SZ{symbol}`；`4`、`8`、`92` 开头 `BJ{symbol}`。 | `300750 -> SZ300750`。 |
| Tushare `ts_code` | `6` 开头 `{symbol}.SH`；`4`、`8`、`92` 开头 `{symbol}.BJ`；其他 6 位代码 `{symbol}.SZ`。 | `600000.SH`，`000001.SZ`。 |
| 巨潮公告 `column` | `6` 开头使用 `sse`，其他默认 `szse`。 | `600000 -> sse`。 |
| 巨潮公告 `stock` | 优先使用巨潮证券列表返回的 `symbol,orgId`；失败后按交易所猜测。 | `000001,gssz0000001` 形式。 |

## 3. 东方财富接口

### 3.1 A 股全市场实时行情

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchAshareQuoteSnapshot`、`fetchAshareQuotes`、`fetchLiquidAshareQuotes`、`fetchAshareQuotesByPage` |
| URL | `${research.live-data.eastmoney-quote-url}`，默认 `https://push2.eastmoney.com/api/qt/clist/get` |
| Method | `GET` |
| 业务用途 | 公司样本、长线全市场筛选、短线候选池、推荐指数排序基础行情。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `pn` | `pageNumber`，从 1 开始 | 页码。 |
| `pz` | `pageSize`，最大 100 | 每页条数。 |
| `po` | `1` | 排序方向。 |
| `fid` | `f6` | 按成交额字段排序。 |
| `fs` | `m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048` | A 股过滤范围。 |
| `fields` | `f2,f3,f5,f6,f8,f9,f12,f13,f14,f23,f60,f100,f115,f124` | 返回字段集合。 |

响应结构：

| 字段 | 说明 |
| --- | --- |
| `rc` | 业务状态码，代码要求为 `0`。 |
| `data.total` | 供应商返回的总数量。 |
| `data.diff` | 行情列表，可以是数组或对象。 |

`data.diff` 字段映射：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `f12` | `symbol` | 证券代码。 |
| `f14` | `name` | 证券简称。 |
| `f13` | `market` | 市场编码，工程转换为上交所/深交所/北交所。 |
| `f100` | `industry` | 行业名称。 |
| `f2` | `latestPrice` | 最新价。 |
| `f3` | `changePercent` | 当日涨跌幅。短线推荐已强制要求该值大于 0 且不超过追涨上限。 |
| `f8` | `turnoverRate` | 换手率。 |
| `f5` | `volume` | 成交量。 |
| `f6` | `amount` | 成交额。 |
| `f9` | `peRatio` | 市盈率。 |
| `f23` | `pbRatio` | 市净率。 |
| `f115` | `peTtm` | 滚动市盈率。 |
| `f124` | `marketTimestamp` / `tradeDate` | 行情时间戳，秒级 Unix 时间，工程转为中国市场日期。 |

### 3.2 东方财富批量个股行情

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchEastMoneyQuotesBySymbols` |
| URL | `https://push2.eastmoney.com/api/qt/ulist.np/get` |
| Method | `GET` |
| 业务用途 | 公司详情补充实时行情、交易复盘买卖价格核验、腾讯行情回退后的东方财富补充。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `secids` | 逗号分隔 `secid`，每批最多 80 个 | 证券列表。 |
| `fields` | 同 3.1 `QUOTE_FIELDS` | 行情字段。 |

响应字段与 3.1 的 `data.diff` 映射一致。

### 3.3 东方财富当天分时

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchIntradayTrends` 的回退路径 `fetchEastMoneyIntradayTrends` |
| URL | `https://push2his.eastmoney.com/api/qt/stock/trends2/get` |
| Method | `GET` |
| 业务用途 | 短线尾盘信号、日内拉升意愿和分时复核。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `secid` | 东方财富 `secid` | 证券标识。 |
| `fields1` | `f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13` | 元信息字段。 |
| `fields2` | `f51,f52,f53,f54,f55,f56,f57,f58` | 分时行字段。 |
| `iscr` | `0` | 固定参数。 |
| `iscca` | `0` | 固定参数。 |
| `ndays` | `1` | 拉取当天分时。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `data.trends[]` | 分时行 | 字符串 CSV。 |
| `f51` / CSV `[0]` | `minute` | 分钟时间，格式如 `yyyy-MM-dd HH:mm`。 |
| `f52` / CSV `[1]` | `open` | 分钟开盘价。 |
| `f53` / CSV `[2]` | `close` | 分钟收盘/最新价。 |
| `f54` / CSV `[3]` | `high` | 分钟最高价。 |
| `f55` / CSV `[4]` | `low` | 分钟最低价。 |
| `f56` / CSV `[5]` | `volume` | 分钟成交量。 |
| `f57` / CSV `[6]` | `amount` | 分钟成交额。 |
| `f58` / CSV `[7]` | `averagePrice` | 均价。 |

### 3.4 东方财富个股实时/批量资金流

| 项 | 内容 |
| --- | --- |
| 调用方法 | `fetchFundFlowSnapshot`、`fetchFundFlowSnapshots` |
| URL | `${research.live-data.eastmoney-fund-flow-url}`，默认 `https://push2.eastmoney.com/api/qt/ulist.np/get` |
| Method | `GET` |
| 业务用途 | 短线主力买入排序、周期试仓资金确认、买入多且上方抛压不强的优先级。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `fltt` | `2` | 数值格式。 |
| `invt` | `2` | 固定参数。 |
| `secids` | 单个或逗号分隔 `secid` | 个股列表。 |
| `fields` | `f12,f13,f14,f62,f184,f66,f69,f72,f75,f78,f81,f84,f87,f124` | 资金流字段。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `f12` | `symbol` | 证券代码。 |
| `f14` | `name` | 证券简称。 |
| `f62` | `mainNetInflow` | 主力净流入。 |
| `f66` | `superLargeNetInflow` | 超大单净流入。 |
| `f72` | `largeNetInflow` | 大单净流入。 |
| `f78` | `mediumNetInflow` | 中单净流入。 |
| `f84` | `smallNetInflow` | 小单净流入。 |
| `f184` | `mainNetInflowRatio` | 主力净流入占比。 |
| `f69` | `superLargeNetInflowRatio` | 超大单净流入占比。 |
| `f75` | `largeNetInflowRatio` | 大单净流入占比。 |
| `f81` | `mediumNetInflowRatio` | 中单净流入占比。 |
| `f87` | `smallNetInflowRatio` | 小单净流入占比。 |
| `f124` | `marketTimestamp` / `tradeDate` | 行情时间戳。 |

### 3.5 东方财富日级资金流回退

| 项 | 内容 |
| --- | --- |
| 调用方法 | `fetchFundFlowSnapshot` 实时资金流为空或失败时回退到 `fetchLatestFundFlowDaySnapshot` |
| URL | `https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get` |
| Method | `GET` |
| 业务用途 | 个股资金流兜底，不阻断推荐流程。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `secid` | 东方财富 `secid` | 证券标识。 |
| `lmt` | `1` | 最近 1 条日级资金流。 |
| `fields1` | `f1,f2,f3,f7` | 元信息字段。 |
| `fields2` | `f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65` | 日级资金流字段。 |

CSV 行字段映射：

| 位置 | 工程字段 | 说明 |
| --- | --- | --- |
| `[0]` | `tradeDate` | 交易日期。 |
| `[1]` | `mainNetInflow` | 主力净流入。 |
| `[5]` | `superLargeNetInflow` | 超大单净流入。 |
| `[4]` | `largeNetInflow` | 大单净流入。 |
| `[3]` | `mediumNetInflow` | 中单净流入。 |
| `[2]` | `smallNetInflow` | 小单净流入。 |
| `[6]` | `mainNetInflowRatio` | 主力净流入占比。 |
| `[10]` | `superLargeNetInflowRatio` | 超大单净流入占比。 |
| `[9]` | `largeNetInflowRatio` | 大单净流入占比。 |
| `[8]` | `mediumNetInflowRatio` | 中单净流入占比。 |
| `[7]` | `smallNetInflowRatio` | 小单净流入占比。 |

### 3.6 东方财富分钟资金流

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchFundFlowMinutes` |
| URL | `${research.live-data.eastmoney-fund-flow-minute-url}`，默认 `https://push2.eastmoney.com/api/qt/stock/fflow/kline/get` |
| Method | `GET` |
| 业务用途 | 日内资金节奏与短线详情补充。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `ut` | `b2884a393a59ad64002292a3e90d46a5` | 东方财富固定参数。 |
| `secid` | 东方财富 `secid` | 证券标识。 |
| `klt` | `1` | 1 分钟级别。 |
| `lmt` | `1..240` | 拉取条数，代码限制最大 240。 |
| `fields1` | `f1,f2,f3,f7` | 元信息字段。 |
| `fields2` | `f51,f52,f53,f54,f55,f56,f57` | 分钟资金流字段。 |

CSV 行字段映射：

| 位置 | 工程字段 | 说明 |
| --- | --- | --- |
| `[0]` | `minute` | 分钟时间。 |
| `[1]` | `mainNetInflow` | 主力净流入。 |
| `[2]` | `smallNetInflow` | 小单净流入。 |
| `[3]` | `mediumNetInflow` | 中单净流入。 |
| `[4]` | `largeNetInflow` | 大单净流入。 |
| `[5]` | `superLargeNetInflow` | 超大单净流入。 |

### 3.7 东方财富行业资金流

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchIndustryFundFlows` |
| URL | `https://push2delay.eastmoney.com/api/qt/clist/get` |
| Method | `GET` |
| 业务用途 | 短线“今日资金去向”、市场情绪、热门方向。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `pn` | `1` | 页码。 |
| `pz` | `500` | 拉取行业板块数量。 |
| `po` | `1` | 排序方向。 |
| `np` | `1` | 固定参数。 |
| `ut` | `bd1d9ddb04089700cf9c27f6f7426281` | 东方财富固定参数。 |
| `fltt` | `2` | 数值格式。 |
| `invt` | `2` | 固定参数。 |
| `fid` | `f62` | 按主力净流入排序。 |
| `fs` | `m:90+t:2+f:!50` | 行业板块过滤。 |
| `fields` | `f12,f14,f2,f3,f62,f184,f66,f69,f72,f75,f104,f105,f124,f152` | 行业资金流字段。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `f12` | `code` | 行业板块代码，要求以 `BK` 开头。 |
| `f14` | `name` | 行业板块名称。 |
| `f62` | `mainNetInflow` | 主力净流入。 |
| `f184` | `mainNetInflowRatio` | 主力净流入占比。 |
| `f66` | `superLargeNetInflow` | 超大单净流入。 |
| `f69` | `superLargeNetInflowRatio` | 超大单净流入占比。 |
| `f72` | `largeNetInflow` | 大单净流入。 |
| `f75` | `largeNetInflowRatio` | 大单净流入占比。 |
| `f104` | `advancing` | 上涨家数。 |
| `f105` | `declining` | 下跌家数。 |
| `f124` | `marketTimestamp` / `tradeDate` | 行情时间戳。 |

### 3.8 东方财富行业板块列表

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchIndustryBoards` |
| URL | `https://17.push2.eastmoney.com/api/qt/clist/get` |
| Method | `GET` |
| 业务用途 | 通过行业名称解析板块代码，再查行业成分股；缓存 1 小时。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `pn` | `1..8` | 页码，最多 8 页。 |
| `pz` | `100` | 每页 100 条。 |
| `po` | `1` | 排序方向。 |
| `np` | `1` | 固定参数。 |
| `ut` | `bd1d9ddb04089700cf9c27f6f7426281` | 东方财富固定参数。 |
| `fltt` | `2` | 数值格式。 |
| `invt` | `2` | 固定参数。 |
| `fid` | `f3` | 按涨跌幅字段排序。 |
| `fs` | `m:90 t:2 f:!50` | 行业板块过滤。 |
| `fields` | `f12,f14` | 板块代码和名称。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `data.total` | 总数 | 用于判断是否继续翻页。 |
| `data.diff[].f12` | `code` | 行业板块代码，要求以 `BK` 开头。 |
| `data.diff[].f14` | `name` | 行业板块名称。 |

### 3.9 东方财富行业成分股

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchIndustryBoardConstituents` |
| URL | `https://29.push2.eastmoney.com/api/qt/clist/get` |
| Method | `GET` |
| 业务用途 | 行业估值对比、行业龙头前置过滤、证券/券商高 Beta 默认剔除名单。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `pn` | `1` | 当前只取第一页。 |
| `pz` | `min(max(limit, 50), 500)` | 拉取成分股数量。 |
| `po` | `1` | 排序方向。 |
| `np` | `1` | 固定参数。 |
| `ut` | `bd1d9ddb04089700cf9c27f6f7426281` | 东方财富固定参数。 |
| `invt` | `2` | 固定参数。 |
| `fid` | `f6` | 按成交额字段排序。 |
| `fs` | `b:{BKCODE} f:!50` | 指定行业板块代码。 |
| `fields` | 同 3.1 `QUOTE_FIELDS` | 个股行情字段。 |

响应字段与 3.1 的 `data.diff` 映射一致。结果按 `industryName + limit` 缓存 5 分钟。

### 3.10 东方财富历史日 K

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchDailyKLineSeries` 的回退路径 `fetchEastMoneyDailyKLines`，以及换手率补齐 `enrichDailyKLineTurnover` |
| URL | `https://push2his.eastmoney.com/api/qt/stock/kline/get` |
| Method | `GET` |
| 业务用途 | 近一年 K 线、技术形态、筹码本地模型、回测、交易复盘。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `secid` | 东方财富 `secid` | 证券标识。 |
| `fields1` | `f1,f2,f3,f4,f5,f6` | 元信息字段。 |
| `fields2` | `f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61` | K 线行字段。 |
| `klt` | `101` | 日 K。 |
| `fqt` | `1` | 前复权。 |
| `beg` | `yyyyMMdd` | 开始日期。 |
| `end` | `yyyyMMdd` | 结束日期。 |

CSV 行字段映射：

| 位置 | 工程字段 | 说明 |
| --- | --- | --- |
| `[0]` | `tradeDate` | 交易日期。 |
| `[1]` | `open` | 开盘价。 |
| `[2]` | `close` | 收盘价。 |
| `[3]` | `high` | 最高价。 |
| `[4]` | `low` | 最低价。 |
| `[5]` | `volume` | 成交量。 |
| `[6]` | `amount` | 成交额。 |
| `[10]` | `turnoverRate` | 换手率，筹码模型需要较高覆盖率。 |

### 3.11 东方财富单票行业信息

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchStockBoardIndustry` |
| URL | `https://91.push2.eastmoney.com/api/qt/stock/get` |
| Method | `GET` |
| 业务用途 | 公司详情、长线候选上下文、行业估值对比。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `secid` | 东方财富 `secid` | 证券标识。 |
| `fields` | `f57,f58,f127` | 证券代码、名称、所属行业。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `data.f127` | `industry` | 所属板块/行业。 |

### 3.12 东方财富行情详情链接（非数据 API）

代码会为每只股票生成一个可点击的东方财富行情页，用作证据来源链接，但当前工程不会通过该页面抓取数据：

| 市场 | URL 模板 |
| --- | --- |
| 上交所 | `https://quote.eastmoney.com/sh{symbol}.html` |
| 深交所 | `https://quote.eastmoney.com/sz{symbol}.html` |
| 北交所 | `https://quote.eastmoney.com/bj{symbol}.html` |

### 3.13 东方财富 F10 公司概况行业

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchCompanySurveyIndustry` |
| URL | `https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax` |
| Method | `GET` |
| 业务用途 | 单票行业信息回退。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `code` | F10 代码 | 如 `SH600000`、`SZ000001`、`BJ430047`。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `jbzl[0].EM2016` | `industry` | 东方财富行业分类，代码取 `-` 分隔后的细分行业。 |
| `jbzl[0].INDUSTRYCSRC1` | `industry` | 证监会行业分类，作为回退。 |

### 3.14 东方财富年报财务指标

| 项 | 内容 |
| --- | --- |
| 调用方法 | `fetchAnnualIndicators`、`fetchAnnualIndicatorHistory` |
| URL | `${research.live-data.eastmoney-financial-url}`，默认 `https://datacenter-web.eastmoney.com/api/data/v1/get` |
| Method | `GET` |
| 业务用途 | 近三年财报质量、长期推荐质量、公司样本、行业龙头辅助判断。 |

全市场年报请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `sortColumns` | `SECURITY_CODE` | 按证券代码排序。 |
| `sortTypes` | `1` | 升序。 |
| `pageSize` | `1..500` | 每页条数。 |
| `pageNumber` | `1..maxPages` | 页码。 |
| `reportName` | `RPT_LICO_FN_CPD` | 上市公司主要财务指标报表。 |
| `columns` | 见下表 `FINANCIAL_COLUMNS` | 返回字段。 |
| `filter` | `(DATAYEAR="{year}")(DATATYPE="{year}年 年报")` | 指定年度年报。 |

单票历史年报请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `sortColumns` | `REPORTDATE` | 按报告日期排序。 |
| `sortTypes` | `-1` | 倒序。 |
| `pageSize` | `min(max(limit * 4, 12), 80)` | 拉取更多后只保留年报。 |
| `pageNumber` | `1` | 当前只取第一页。 |
| `reportName` | `RPT_LICO_FN_CPD` | 上市公司主要财务指标报表。 |
| `columns` | 见下表 `FINANCIAL_COLUMNS` | 返回字段。 |
| `filter` | `(SECURITY_CODE="{symbol}")` | 指定证券代码。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `result.pages` | 页数 | 全市场分页控制。 |
| `result.data[]` | 年报指标行 | 指标列表。 |
| `SECURITY_CODE` | `symbol` | 证券代码。 |
| `SECURITY_NAME_ABBR` | `name` | 证券简称。 |
| `REPORTDATE` | `reportDate` | 报告日期。 |
| `DATATYPE` | `dataType` | 报告类型，代码只保留包含“年报”的数据。 |
| `WEIGHTAVG_ROE` | `roe` | 加权 ROE，工程按百分比转小数。 |
| `MGJYXJJE` | `operatingCashFlowPerShare` | 每股经营现金流。 |
| `XSMLL` | `grossMargin` | 销售毛利率，工程按百分比转小数。 |
| `YSTZ` | `revenueGrowth` | 营收同比，工程按百分比转小数。 |
| `SJLTZ` | `netProfitGrowth` | 归母净利润同比，工程按百分比转小数。 |
| `BASIC_EPS` | `eps` | 基本每股收益。 |
| `BPS` | `bps` | 每股净资产。 |
| `TOTAL_OPERATE_INCOME` | `operatingRevenue` | 营业总收入。 |
| `PARENT_NETPROFIT` | `netProfit` | 归母净利润。 |
| `ASSIGNDSCRPT` | `dividendPlanDescription` | 分红方案描述。 |
| `ZXGXL` | `dividendYield` | 股息率，工程按百分比转小数。 |
| `SECUCODE` | 未落库 | 请求了该字段，但当前 `EastMoneyAnnualIndicator` 未保存。 |

## 4. 腾讯行情接口

### 4.1 腾讯批量实时行情

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchTencentQuotes` |
| URL | `https://qt.gtimg.cn/q={codes}` |
| Method | `GET` |
| 业务用途 | 公司列表快速模式、实时行情回退、交易复盘实时价回退。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `q` | 逗号分隔腾讯代码，每批最多 60 个 | 如 `sh600000,sz000001`。 |

响应特点：

| 项 | 说明 |
| --- | --- |
| 编码 | GB18030。 |
| 格式 | 每行形如 `v_sh600000="..."`，内部用 `~` 分隔字段。 |

`~` 分隔字段映射：

| 位置 | 工程字段 | 说明 |
| --- | --- | --- |
| `[1]` | `name` | 证券简称。 |
| `[2]` | `symbol` | 证券代码。 |
| `[3]` | `latestPrice` | 最新价。 |
| `[30]` | `marketTimestamp` / `tradeDate` | 行情时间，格式 `yyyyMMddHHmmss`。 |
| `[32]` | `changePercent` | 当日涨跌幅。 |
| `[36]` | `volume` | 成交量。 |
| `[37]` | `amount` | 成交额，工程乘以 10000。 |
| `[38]` | `turnoverRate` | 换手率。 |
| `[39]` | `peRatio` / `peTtm` | 市盈率，非正值置空。 |
| `[46]` | `pbRatio` | 市净率，非正值置空。 |

### 4.2 腾讯当天分时

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchIntradayTrends` 的优先路径 `fetchTencentIntradayTrends` |
| URL | `https://web.ifzq.gtimg.cn/appstock/app/minute/query` |
| Method | `GET` |
| 业务用途 | 短线尾盘/分时信号优先数据源。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `code` | 腾讯代码 | 如 `sh600000`、`sz000001`。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `data.{code}.data.date` | `tradeDate` | 交易日，格式 `yyyyMMdd`。 |
| `data.{code}.data.data[]` | 分时行 | 空格分隔字符串。 |
| 行 `[0]` | `minute` | 分钟，格式 `HHmm`。 |
| 行 `[1]` | `close` / `open` / `high` / `low` | 当前分钟价格，工程四价都使用该价格。 |
| 行 `[2]` | 累计成交量 | 工程转为分钟增量 `volume`。 |
| 行 `[3]` | 累计成交额 | 工程转为分钟增量 `amount`，并推导 `averagePrice`。 |

### 4.3 腾讯前复权日 K

| 项 | 内容 |
| --- | --- |
| 调用方法 | `EastMoneyClient.fetchDailyKLineSeries` 的优先路径 `fetchTencentDailyKLines` |
| URL | `https://web.ifzq.gtimg.cn/appstock/app/fqkline/get` |
| Method | `GET` |
| 业务用途 | 历史 K 线主数据源，供近一年 K 线、技术指标、筹码本地模型、回测使用。 |

请求参数：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `param` | `{code},day,{begin},{end},500,qfq` | `code` 是腾讯代码；`begin/end` 格式为 `yyyy-MM-dd`；每片最多约一年。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `data.{code}.qfqday[]` | 前复权日线 | 优先读取。 |
| `data.{code}.day[]` | 不复权日线 | `qfqday` 为空时回退。 |
| 行 `[0]` | `tradeDate` | 交易日期。 |
| 行 `[1]` | `open` | 开盘价。 |
| 行 `[2]` | `close` | 收盘价。 |
| 行 `[3]` | `high` | 最高价。 |
| 行 `[4]` | `low` | 最低价。 |
| 行 `[5]` | `volume` | 成交量。 |
| `amount` | `null` | 腾讯该路径当前未映射成交额。 |
| `turnoverRate` | `null` | 腾讯该路径当前未映射换手率，工程会用东方财富日 K 补齐。 |

## 5. Tushare 筹码认证接口

| 项 | 内容 |
| --- | --- |
| 调用方法 | `TushareChipClient.fetchPerformance` |
| URL | `${research.short-term.chip.tushare.base-url}`，默认 `https://api.tushare.pro` |
| Method | `POST` |
| Content-Type | `application/json` |
| 业务用途 | 短线筹码本地模型的 T-1 外部认证。未配置、超时或失败时降级为本地单源，不阻断扫描。 |

请求 JSON：

| 字段 | 当前取值 | 说明 |
| --- | --- | --- |
| `api_name` | `cyq_perf` | Tushare 每日筹码及胜率接口。 |
| `token` | 环境变量注入 | Tushare Token，禁止写入 Git、日志、前端响应。 |
| `params.ts_code` | Tushare `ts_code` | 如 `600000.SH`。 |
| `params.trade_date` | `yyyyMMdd` | 交易日期，通常为最近完整交易日。 |
| `fields` | `ts_code,trade_date,cost_5pct,cost_15pct,cost_50pct,cost_85pct,cost_95pct,weight_avg,winner_rate` | 返回字段。 |

响应结构：

| 字段 | 说明 |
| --- | --- |
| `code` | 业务状态码，代码要求为 `0`。 |
| `data.fields[]` | 字段名数组。 |
| `data.items[]` | 数据行数组，当前取第一行。 |

字段映射：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `ts_code` | `symbol` | 去掉 `.SH/.SZ/.BJ` 后缀后的 6 位代码。 |
| `trade_date` | `tradeDate` | 交易日期，格式 `yyyyMMdd`。 |
| `cost_5pct` | `cost5` | 5% 成本分位价。 |
| `cost_15pct` | `cost15` | 15% 成本分位价。 |
| `cost_50pct` | `cost50` | 50% 成本分位价，中位成本。 |
| `cost_85pct` | `cost85` | 85% 成本分位价。 |
| `cost_95pct` | `cost95` | 95% 成本分位价。 |
| `weight_avg` | `averageCost` | 加权平均成本。 |
| `winner_rate` | `winnerRatePercent` | 获利比例，代码要求范围为 0 到 100。 |

## 6. 巨潮资讯接口

### 6.1 巨潮证券列表

| 项 | 内容 |
| --- | --- |
| 调用方法 | `CninfoClient.resolveOrgId` / `loadStockOrgIds` |
| URL | `https://www.cninfo.com.cn/new/data/szse_stock.json` |
| Method | `GET` |
| 业务用途 | 获取巨潮 `orgId`，用于更准确查询公告。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `stockList[].code` | `symbol` | 证券代码。 |
| `stockList[].orgId` | `orgId` | 巨潮机构编码。 |

### 6.2 巨潮公告查询

| 项 | 内容 |
| --- | --- |
| 调用方法 | `CninfoClient.fetchAnnouncements` |
| URL | `${research.live-data.cninfo-announcement-url}`，默认 `https://www.cninfo.com.cn/new/hisAnnouncement/query` |
| Method | `POST` |
| Content-Type | `application/x-www-form-urlencoded` |
| 业务用途 | 公告/定期报告反证、公司详情证据、风险观察。 |

请求表单：

| 参数 | 当前取值 | 说明 |
| --- | --- | --- |
| `pageNum` | `1` | 当前只取第一页。 |
| `pageSize` | `max(1, min(limit, 30))` | 公告条数，上限 30。 |
| `column` | `sse` 或 `szse` | 交易所栏目。 |
| `tabName` | `fulltext` | 全文检索页签。 |
| `plate` | 空字符串 | 当前未指定板块。 |
| `stock` | `symbol,orgId` 或回退值 | 证券代码与巨潮机构编码。 |
| `searchkey` | 空字符串 | 当前不按关键词过滤。 |
| `secid` | 空字符串 | 当前不使用。 |
| `category` | 空字符串 | 当前不限制公告类别。 |
| `trade` | 空字符串 | 当前不限制行业。 |
| `seDate` | 空字符串 | 当前不限制日期区间。 |
| `sortName` | 空字符串 | 当前使用默认排序。 |
| `sortType` | 空字符串 | 当前使用默认排序。 |
| `isHLtitle` | `true` | 标题高亮。 |

请求头：

| Header | 当前取值 | 说明 |
| --- | --- | --- |
| `Origin` | `https://www.cninfo.com.cn` | 巨潮要求的来源。 |
| `Referer` | `https://www.cninfo.com.cn/new/commonUrl/pageOfSearch?url=disclosure/list/search` | 巨潮检索页。 |

响应字段：

| 字段 | 工程字段 | 说明 |
| --- | --- | --- |
| `announcements[]` | 公告列表 | 为空则换下一个 `RequestCandidate` 重试。 |
| `announcementId` | `announcementId` | 公告 ID。 |
| `secCode` | `symbol` | 证券代码，缺失时回退为公司代码。 |
| `secName` | `name` | 证券简称，缺失时回退为公司名称。 |
| `orgId` | `orgId` | 巨潮机构编码。 |
| `announcementTitle` | `title` | 公告标题，工程会去掉 HTML 标签。 |
| `announcementTime` | `announcementTime` | 公告时间戳，毫秒级。 |
| `adjunctUrl` | `adjunctUrl` | 附件相对路径或绝对 URL。 |

### 6.3 巨潮公告详情页链接

| 项 | 内容 |
| --- | --- |
| 调用方法 | `CninfoClient.disclosureUrl` |
| URL | `https://www.cninfo.com.cn/new/disclosure/detail` |
| Method | 页面链接拼接，当前代码不请求该页面正文 |
| 业务用途 | 前端证据来源链接。 |

链接参数：

| 参数 | 来源 | 说明 |
| --- | --- | --- |
| `stockCode` | `symbol` | 证券代码。 |
| `announcementId` | `announcementId` | 公告 ID。 |
| `orgId` | `orgId` | 巨潮机构编码。 |
| `announcementTime` | `announcementTime` 转 `yyyy-MM-dd` | 公告日期。 |

### 6.4 巨潮公告附件/PDF 下载

| 项 | 内容 |
| --- | --- |
| 调用方法 | `CninfoClient.downloadUrl`、`FilingPdfTextService.extract` |
| URL | `https://static.cninfo.com.cn/{adjunctUrl}`，若 `adjunctUrl` 已是绝对 URL 则直接使用 |
| Method | `GET` |
| 业务用途 | 下载公告 PDF，抽取正文用于公告/定期报告反证和风险事件识别。 |

处理规则：

| 项 | 当前规则 |
| --- | --- |
| 最大 PDF 大小 | 12 MB，超过则跳过正文解析。 |
| 最大解析页数 | 默认 6 页，由 `research.live-data.filing-pdf-max-pages` 控制，代码上限 20 页。 |
| 最大文本长度 | 12000 字符，超过截断。 |
| 失败处理 | 记录 warn，返回空，不阻断主流程。 |

## 7. 调用方与证据覆盖

| 证据/模块 | 使用的数据接口 |
| --- | --- |
| 短线预选 | 东方财富全市场行情、东方财富批量资金流、东方财富行业资金流、腾讯/东方财富分时、腾讯/东方财富日 K、东方财富财报、Tushare 筹码认证。 |
| 长线推荐 | 东方财富全市场行情、东方财富年报财务指标、腾讯/东方财富日 K、东方财富行业成分股、巨潮公告。 |
| 公司样本/公司详情 | 东方财富行情、东方财富财报、东方财富行业、巨潮公告/PDF。 |
| 近一年 K 线 | 腾讯前复权日 K 优先，东方财富前复权日 K 回退，东方财富换手率补齐。 |
| 近三年财报质量 | 东方财富 `RPT_LICO_FN_CPD` 年报指标历史。 |
| 公告/定期报告反证 | 巨潮公告查询与附件 PDF 下载。 |
| 行业估值对比 | 东方财富行业板块列表、行业成分股行情、个股估值字段。 |
| 今日市场情绪/资金去向 | 东方财富行业资金流。 |
| 筹码集中区域和价位 | 本地模型使用历史日 K、成交量、成交额、换手率复算；Tushare `cyq_perf` 只做最近完整交易日外部认证。 |
| 交易复盘 | 东方财富/腾讯最新价、腾讯/东方财富历史日 K。 |

## 8. 降级、限流与稳定性

| 机制 | 当前实现 |
| --- | --- |
| 东方财富 Host 轮询 | 对 `push2.eastmoney.com`、`push2delay.eastmoney.com` 和数字前缀 `*.push2.eastmoney.com` 做候选 Host 轮询。候选包括 `push2delay`、`push2`、`17.push2`、`29.push2`、`73.push2`、`91.push2`。 |
| 东方财富限速 | 同进程内最小请求间隔 650 ms。 |
| 东方财富请求方式 | 先 `RestClient`，失败后使用 `curl -L --compressed --ipv4 --http1.1` 兜底。 |
| 腾讯编码 | `qt.gtimg.cn` 响应按 GB18030 解码。 |
| K 线完整性 | 腾讯优先，东方财富回退；如果只拿到部分数据，工程标记为 `HISTORICAL_KLINE_PARTIAL` 并拒绝把部分数据当完整证据。 |
| 筹码认证 | Tushare 未配置、超时、限流或业务错误时，短线筹码展示降级为本地单源，不阻断扫描。 |
| 巨潮公告 | 先用证券列表解析 `orgId`，失败后按交易所编码猜测；公告和 PDF 失败均不阻断公司主流程。 |
| Token 安全 | Tushare Token 只来自环境变量，不能写入仓库、日志、报告或前端响应。 |

## 9. 排查建议

| 现象 | 优先检查 |
| --- | --- |
| 推荐里出现“缺失近一年 K 线” | 查腾讯 `fqkline/get` 是否完整返回；再查东方财富 `stock/kline/get` 回退；确认网络、Host 轮询和 `beg/end` 日期。 |
| 缺失近三年财报质量 | 查东方财富 `datacenter-web.eastmoney.com/api/data/v1/get`，重点看 `filter`、`reportName`、`DATATYPE` 是否仍返回年报。 |
| 缺失公告/定期报告反证 | 查巨潮 `szse_stock.json` 是否能取到 `orgId`；再查 `hisAnnouncement/query` 表单是否返回 `announcements`；最后查 `static.cninfo.com.cn` 附件下载。 |
| 缺失行业估值对比 | 查行业名称能否匹配到 `BK` 板块；再查行业成分股 `clist/get` 是否返回行情估值字段。 |
| 筹码显示“未交叉验证” | 查 `research.short-term.chip.tushare.enabled`、`token`、接口权限和 `cyq_perf` 当日/最近完整交易日是否有数据。 |
| 短线推荐出现下跌股票 | 后端已增加 `NON_POSITIVE_DAILY_CHANGE` 硬过滤；若再次出现，优先确认前端展示是否为旧扫描快照、后端是否已部署最新包、行情源 `changePercent` 是否缺失或缓存未刷新。 |
