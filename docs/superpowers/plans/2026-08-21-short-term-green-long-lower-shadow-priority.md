# Short-Term Green Long-Lower-Shadow Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `绿十字星长下影优先` result lane below the existing short-term right-side candidates, calculated from the same manual quote scan using a green candle, body percentage at most 10%, and lower-shadow percentage at least 50%.

**Architecture:** Extend the existing full-market quote snapshot with intraday open/high/low values, calculate the independent shape directly from that snapshot inside `ShortTermService`, and add a lightweight result list to `ShortTermReport`. Keep the main candidate model and its ranking unchanged; React renders the new list in a separate card immediately below `右侧候选`.

**Tech Stack:** Java 21 records and streams, Spring Boot/Jackson, React 18, TypeScript, Tailwind utility classes.

## Global Constraints

- Work directly on the existing `main` branch; do not create a worktree.
- Use the same manual scan and scan-start quote snapshot; do not add a second scan or K-line pass.
- Match only when `latestPrice < open`, `bodyPercent <= 10`, and `lowerShadowPercent >= 50`.
- Do not restrict upper shadow, moving averages, previous 20-day high, daily change, volume ratio, turnover, trend, or support reclaim.
- Respect the existing ST/board permission, quote freshness, maximum-price, and minimum-liquidity boundaries.
- Keep the existing main candidate ranks, actions, scores, and detail workflow unchanged.
- Do not restore scheduled selection, result caches, scan snapshots, or background scans.
- Do not run or delegate tests, builds, lint, static/diff review, browser checks, health checks, logs, or deployment. The user owns verification and regression.

---

### Task 1: Carry intraday OHLC in the existing quote snapshot

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`

**Interfaces:**
- Produces: `EastMoneyQuote.openPrice()`, `highPrice()`, and `lowPrice()` as nullable `BigDecimal` values.
- Preserves: all existing shorter `EastMoneyQuote` constructors by defaulting new fields to null.

- [ ] **Step 1: Append nullable OHLC fields to the quote record**

Append the fields after `totalMarketValue`:

```java
BigDecimal openPrice,
BigDecimal highPrice,
BigDecimal lowPrice
```

Add a compatibility constructor with the former canonical signature ending in
`totalMarketValue` and delegate with three trailing nulls. Update the existing
shorter constructors the same way so unrelated production and fixture callers
continue compiling without changes.

- [ ] **Step 2: Request and parse EastMoney OHLC fields**

Extend `QUOTE_FIELDS` with `f15,f16,f17`, then pass:

```java
positiveOrNull(scaled(item, "f17", 2)), // open
positiveOrNull(scaled(item, "f15", 2)), // high
positiveOrNull(scaled(item, "f16", 2))  // low
```

to the full record constructor after total market value.

- [ ] **Step 3: Parse Tencent fallback OHLC fields**

Use Tencent's existing quote array:

```java
positiveOrNull(decimal(fields[5])),  // open
positiveOrNull(decimal(fields[33])), // high
positiveOrNull(decimal(fields[34]))  // low
```

Pass `null` for total market value and the three parsed OHLC values to the full
record constructor.

### Task 2: Calculate and expose the independent priority list

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermGreenLongLowerShadowCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`

**Interfaces:**
- Produces: `ShortTermReport.greenLongLowerShadowCandidates()`.
- Produces DTO fields: rank, identity, price/change, OHLC, body/lower-shadow percentages, amount, turnover, quote freshness, and provisional state.
- Consumes: OHLC fields from Task 1 and the existing `QuoteFreshnessService`.

- [ ] **Step 1: Add the lightweight result DTO**

Create this record in the short-term package:

```java
public record ShortTermGreenLongLowerShadowCandidate(
        int rank,
        String symbol,
        String name,
        String market,
        String industry,
        BigDecimal latestPrice,
        BigDecimal changePercent,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal bodyPercent,
        BigDecimal lowerShadowPercent,
        BigDecimal amount,
        BigDecimal turnoverRate,
        QuoteFreshnessSnapshot quoteFreshness,
        boolean provisional
) {}
```

- [ ] **Step 2: Extend the report compatibly**

Append this field to the canonical `ShortTermReport` record:

```java
List<ShortTermGreenLongLowerShadowCandidate> greenLongLowerShadowCandidates
```

Normalize null to `List.of()` in the compact constructor. All historical
overloads delegate with `List.of()` so legacy construction remains compatible.

- [ ] **Step 3: Select matches from the same quote universe**

Add constants to `ShortTermService`:

```java
private static final BigDecimal MAX_GREEN_CANDLE_BODY_PERCENT = new BigDecimal("10.00");
private static final BigDecimal MIN_GREEN_CANDLE_LOWER_SHADOW_PERCENT = new BigDecimal("50.00");
```

Add a private selection method that:

1. starts from the already point-in-time-safe `quoteUniverse`;
2. applies maximum price and `RecommendationQuality.hasSufficientLiquidity`;
3. rejects missing/non-positive/internally inconsistent OHLC;
4. requires `latestPrice < openPrice`;
5. calculates:

```java
BigDecimal range = highPrice.subtract(lowPrice);
BigDecimal bodyPercent = latestPrice.subtract(openPrice).abs()
        .multiply(new BigDecimal("100"))
        .divide(range, 2, RoundingMode.HALF_UP);
BigDecimal lowerShadowPercent = latestPrice.subtract(lowPrice)
        .multiply(new BigDecimal("100"))
        .divide(range, 2, RoundingMode.HALF_UP);
```

6. keeps body `<= 10.00` and lower shadow `>= 50.00`;
7. excludes a row only when existing quote freshness blocks it;
8. sorts by lower shadow descending, amount descending, symbol ascending;
9. limits using the resolved short-term `limit` and assigns lane ranks from 1;
10. sets `provisional` from `!tradingClockService.isCompletedDailyBar(quote.tradeDate())`.

- [ ] **Step 4: Attach the list to both report paths**

Compute the list once immediately after `quoteUniverse` is created. Pass the same
list into both the normal report and the extreme-risk-off report. The latter may
show shape observations while its main recommendation list remains empty and
its market-risk warning remains unchanged.

### Task 3: Preserve the new list through attestation

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java`

**Interfaces:**
- Consumes and reproduces: `report.greenLongLowerShadowCandidates()`.

- [ ] **Step 1: Preserve the collection when rebuilding reports**

Append `report.greenLongLowerShadowCandidates()` to the canonical
`ShortTermReport` construction inside `attest(ShortTermReport)`. Do not register
trade-capture tokens for these shape-only observations because they are not
full recommendations or buy actions.

### Task 4: Render the independent lane below right-side candidates

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`

**Interfaces:**
- Consumes: optional `ShortTermReport.greenLongLowerShadowCandidates` for legacy-response compatibility.
- Produces: `GreenLongLowerShadowCard` inside the short-term page.

- [ ] **Step 1: Add frontend types**

Declare `ShortTermGreenLongLowerShadowCandidate` with the backend DTO fields and
add this optional property to `ShortTermReport`:

```ts
greenLongLowerShadowCandidates?: ShortTermGreenLongLowerShadowCandidate[]
```

- [ ] **Step 2: Place the independent card**

Immediately after the existing `右侧候选` card, render:

```tsx
<GreenLongLowerShadowCard
  candidates={report.greenLongLowerShadowCandidates ?? []}
/>
```

Do not add controls, polling, a second loading state, or another scan request.

- [ ] **Step 3: Render concise evidence rows**

The new card title is `绿十字星长下影优先`. Each row shows lane rank, name, symbol,
latest price, signed daily change, lower-shadow percentage as the emphasized
metric, body percentage, open/high/low, traded amount, and a `盘中暂定` or
`正式日K` tag. It does not render a score, buy action, or invented support claim.

When empty, render:

```text
本次扫描未发现实体不超过 10%、下影线占比达到 50% 的绿十字星
```

### Task 5: Commit the implementation without verification hooks

**Files:**
- Include only the production and plan files listed above.
- Leave `.zcode/` and all unrelated user files untouched.

- [ ] **Step 1: Commit without running hooks**

```bash
git add \
  apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java \
  apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermGreenLongLowerShadowCandidate.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java \
  apps/web-react/src/types.ts \
  apps/web-react/src/pages/ShortTermPage.tsx \
  docs/superpowers/plans/2026-08-21-short-term-green-long-lower-shadow-priority.md
git commit --no-verify -m "feat: add green long-shadow priority lane"
```

No test, build, lint, diff review, browser check, runtime check, deployment, or
push follows this commit unless the user explicitly requests it.
