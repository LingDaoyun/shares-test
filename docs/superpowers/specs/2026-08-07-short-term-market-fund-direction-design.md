# Short-Term Market Fund Direction Design

## Goal

短线右侧模块把 `K线复核数` 默认值从 60 调整为 120，并在报告中新增“今日资金去向”市场背景，让页面能看到主力资金当天主要流入、流出的行业方向。

## User Outcomes

- 手动扫描和定时扫描默认复核 120 只候选股的近一年 K 线，提高短线池覆盖面。
- 短线首页的市场概览不只展示涨跌家数，还展示今日行业主力资金流向。
- 第一屏仍按候选股自身推荐指数排序，资金去向只作为市场背景和解释证据，不直接改写单股排名。
- 当行业资金数据缺失或覆盖不足时，页面明确显示数据缺口，不推断主题。

## Scope

In scope:

- 前端默认参数、测试夹具和配置 UI 中的 `klineLimit` 默认值改为 120。
- 后端 `ShortTermService` 默认 K 线复核数改为 120。
- 定时任务 `ShortTermAutomationSettings` 的 `research.short-term.schedule.kline-limit` fallback 改为 120。
- Nacos 本地短线配置中的 `kline-limit` 改为 120，保留 DeepSeek 等敏感配置值不覆盖。
- 新增报告级 `ShortTermMarketFundDirection`，包含主力净流入 Top 5、主力净流出 Top 3、覆盖率、数据日期和缺口说明。
- 前端短线页新增“今日资金去向”展示。
- 旧历史快照没有该字段时保持兼容。

Out of scope:

- 不把行业资金流直接纳入个股推荐指数。
- 不做逐股票全市场资金流请求放大。
- 不改变现有筹码结构、上方抛压、买盘优先等单股排序权重。
- 不新增数据库持久化结构；本次沿用已有短线报告和快照载荷。

## Domain Meaning

`K线复核数` 是进入近一年 K 线技术复核的股票数量，不是 K 线条数。扫描流程先用全市场行情做流动性、涨幅、财务和初筛排序，再对前 `klineLimit` 个股票拉取日 K 线、技术信号、筹码和资金流证据。

`K线复核 113/120` 表示本次选择 120 只股票进入 K 线复核，其中 113 只有有效 K 线结果。

`今日资金去向` 是全市场行业层面的当天主力资金方向，用来回答“今天主力资金都跑哪儿去了”。它和单股候选的资金流证据不同：前者看行业背景，后者看候选股自身买卖盘。

## Backend Design

### K-Line Default

Change these defaults to 120:

- `ShortTermService.DEFAULT_KLINE_LIMIT`
- `ShortTermAutomationSettings.scanRequest()` fallback for `research.short-term.schedule.kline-limit`
- Frontend `DEFAULT_DRAFT.klineLimit`
- Frontend test fixtures where the default report/request currently expects 60
- `infra/nacos/ai-stock-api-local.yml` short-term schedule value

Keep `MAX_KLINE_LIMIT` unchanged at 160 unless implementation finds an existing test expecting a tighter UI range. The existing UI max of 160 is still enough for the requested default 120.

### Market Fund Direction Model

Add immutable report DTOs under `com.aistock.research.shortterm`:

```java
public record ShortTermMarketFundDirection(
        List<ShortTermIndustryFundDirection> topInflows,
        List<ShortTermIndustryFundDirection> topOutflows,
        int coveredIndustryCount,
        int expectedIndustryCount,
        BigDecimal coverageRatio,
        LocalDate tradeDate,
        Instant fetchedAt,
        String sourceName,
        List<String> dataGaps
) {
}
```

```java
public record ShortTermIndustryFundDirection(
        String code,
        String name,
        BigDecimal mainNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        int advancing,
        int declining,
        int constituentCount,
        BigDecimal concentrationPercent,
        String sourceUrl
) {
}
```

Field semantics:

- `topInflows`: industries sorted by positive `mainNetInflow` descending, capped at 5.
- `topOutflows`: industries sorted by negative `mainNetInflow` ascending, capped at 3.
- `coveredIndustryCount`: industries with parseable main fund-flow data.
- `expectedIndustryCount`: industry boards returned by EastMoney, or 0 when unknown.
- `coverageRatio`: `coveredIndustryCount / expectedIndustryCount`, null-safe and rounded to 4 decimals.
- `concentrationPercent`: industry absolute main net flow divided by total absolute main net flow across covered industries, expressed as percent.
- `dataGaps`: explicit reasons such as provider failure, empty board list, stale trade date, or coverage below threshold.

Add `marketFundDirection` to `ShortTermReport`. Constructors must default missing values to an unavailable object with empty lists and a data gap such as `行业资金流快照缺失，旧报告或数据源未返回。`

### EastMoney Integration

Add a bounded industry fund-flow fetch path to `EastMoneyClient`:

- Reuse the existing EastMoney request helpers, host fallback, throttling, and `diffItems` parsing style.
- Prefer one industry-board batch request using `clist/get` with industry-board filter and fund-flow fields.
- Do not call `fetchFundFlowSnapshots` for every A-share symbol.
- Keep industry board constituents optional. If advancing/declining or constituent count can be read directly from board fields, use direct board fields. If not available, omit or derive only from a bounded industry-board source, not full per-stock flow.
- Validate trade date against quote date when EastMoney returns timestamps. If the date is missing, keep the data but add a gap noting the missing provider trade date.

The implementation should introduce a dedicated parsed type, for example `EastMoneyIndustryFundFlowSnapshot`, instead of reusing `EastMoneyFundFlowSnapshot` whose symbol semantics are stock-specific.

### Service Integration

`ShortTermService.report(...)` will fetch market fund direction once per report after full-market quotes are available and before constructing `ShortTermReport`.

Service rules:

- Fund direction failure must not fail the short-term scan.
- If fetch fails, return an unavailable snapshot with the exception message summarized in `dataGaps`.
- If coverage is below 70%, keep parsed rows but mark `dataGaps` with `行业资金流覆盖不足`.
- Market fund direction is appended to methodology and quote note only as background context.
- Candidate scoring and ranking order remain unchanged.

## Frontend Design

### Types

Add TypeScript interfaces:

```typescript
export interface ShortTermIndustryFundDirection {
  code: string
  name: string
  mainNetInflow: number | null
  mainNetInflowRatio: number | null
  superLargeNetInflow: number | null
  largeNetInflow: number | null
  advancing: number
  declining: number
  constituentCount: number
  concentrationPercent: number | null
  sourceUrl: string | null
}

export interface ShortTermMarketFundDirection {
  topInflows: ShortTermIndustryFundDirection[]
  topOutflows: ShortTermIndustryFundDirection[]
  coveredIndustryCount: number
  expectedIndustryCount: number
  coverageRatio: number | null
  tradeDate: string | null
  fetchedAt: string | null
  sourceName: string
  dataGaps: string[]
}
```

`ShortTermReport.marketFundDirection` is optional or nullable at the type boundary so old snapshots render safely.

### UI

In `ShortTermPage.tsx`, add a compact market overview card near the existing “市场情绪” card:

- Title: `今日资金去向`
- Show `数据日`、`覆盖`、`来源`
- Show top 5 inflow industries with net amount, main net ratio, concentration, and up/down breadth if available.
- Show top 3 outflow industries with the same fields.
- When `dataGaps` is non-empty, display a muted warning block.
- When both lists are empty, display `行业资金流暂不可用` rather than guessing a theme.

Formatting should reuse existing `formatAmount`, `formatPercent`, `formatDateTime`, `Tag`, and `Card` patterns. Keep the dashboard dense and scannable; this is an operations page, not a landing page.

## Configuration

Update only the short-term schedule subtree needed for this change:

```yaml
research:
  short-term:
    schedule:
      kline-limit: 120
```

Preserve all existing Nacos keys, especially configured model provider and API keys. Do not rewrite the full file from defaults.

If production `infra/nacos/ai-stock-api.yml` intentionally omits local schedule overrides, do not add a duplicate unless existing project convention says local and production Nacos samples should stay aligned.

## Testing

Backend tests:

- `ShortTermServiceTest` proves default request uses `klineLimit=120` when no explicit value is supplied.
- `ShortTermAutomationSettingsTest` proves schedule fallback emits a `ShortTermScanRequest` with `klineLimit=120`.
- EastMoney client parser test proves industry fund-flow rows are parsed, sorted, and dated correctly.
- Service test proves a market fund-flow failure does not fail report generation and creates a data gap.
- Service test proves candidate ordering is unchanged by a strong industry inflow background.

Frontend tests:

- `ShortTermPage.test.tsx` proves default manual request sends `klineLimit=120`.
- Existing fixtures update `reviewedCount`, `klineReviewedCount`, and rule-set `klineLimit` where they represent defaults.
- Add a render test for `今日资金去向` with inflow/outflow rows.
- Add a render test for missing market fund direction on old snapshots.

Verification commands:

```bash
mvn -pl apps/api test
cd apps/web-react && npm test -- --run
cd apps/web-react && npm run build
git diff --check
```

Runtime verification:

- Rebuild/restart API and React containers.
- Run a real manual short-term scan.
- Confirm report status reaches `FINAL_READY` or manual success status.
- Confirm `K线复核` target is 120.
- Confirm `今日资金去向` renders either industry rows or an explicit data gap.
- Confirm first page candidate sorting remains by recommendation score.

## Compatibility And Risk

- Existing short-term snapshots remain readable because `marketFundDirection` defaults to unavailable.
- Increasing default K-line review from 60 to 120 can increase scan time and provider requests. Keep the max at 160 and rely on existing async scan flow and user-visible progress.
- EastMoney industry fund-flow fields can drift. Parser failures must degrade into `dataGaps` instead of blocking the scan.
- Industry fund-flow timestamps may lag the quote snapshot. When stale or missing, the UI must surface that uncertainty.

## Acceptance Criteria

- Manual scan default request and scheduled scan fallback both use 120 K-line review count.
- Short-term page shows “今日资金去向” with top inflow/outflow industries when data exists.
- Missing or stale industry fund-flow evidence is explicit.
- Individual candidate ranking does not change solely because its industry is in top inflow.
- Backend tests, frontend tests, frontend build, and diff whitespace check pass.
- Real scan runs end to end against the configured Nacos/DeepSeek environment without exposing secrets.
