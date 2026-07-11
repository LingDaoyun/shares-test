# Explainable Multifactor Platform P0 Correctness Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the current full-market scanner and short-term financial scoring so later point-in-time factors and out-of-sample validation are built on truthful units, strategy-specific eligibility, complete coverage accounting, and one authoritative final advice field.

**Architecture:** Keep the existing Java 17 modular monolith and React application. Add small contracts for decimal financial ratios, paged quote snapshots, and universal-screen modes; route the existing scanner through those contracts, then adapt `MarketScanService` and the React market page without introducing a new service or persistence layer in P0.

**Tech Stack:** Java 17, Spring Boot 3.3, Maven, JUnit 5, AssertJ, React 18, TypeScript, Vite, existing EastMoney/Tencent clients.

## Global Constraints

- This plan implements only P0 from `docs/superpowers/specs/2026-07-10-explainable-multifactor-point-in-time-validation-design.md`.
- This plan supersedes the shared hard-gate assumptions in `docs/superpowers/plans/2026-07-08-universal-ashare-screener.md`.
- Financial ratios are stored as decimal ratios: 14.26% is `0.1426`; presentation converts to percentage points.
- `ALL`, `VALUE`, `CYCLE`, and `SHORT_TERM` share security/data-quality checks but do not share profitability, sideways, PE, or PB hard gates.
- `ALL` is an inventory/eligibility view and cannot emit a buy-like final advice.
- Only `todayAdvice` is an executable user-facing recommendation; screening-stage actions are explanatory metadata.
- Missing or partial live data must be visible in coverage metadata and cannot be presented as complete full-market coverage.
- Production behavior follows red-green-refactor: write one failing test, observe the expected failure, implement the minimum change, and rerun the focused test.
- The application source tree is currently untracked in Git. Do not stage existing application files as part of implementation commits; use test checkpoints and commit only this plan document until the baseline repository state is normalized.
- Use `apply_patch` for manual source edits.

---

### Task 1: Enforce Decimal-Ratio Financial Units

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/factor/RatioScale.java`
- Create: `apps/api/src/test/java/com/aistock/research/factor/RatioScaleTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/web-react/src/lib/format.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`

**Interfaces:**
- Produces: `RatioScale.fromPercentPoints(String): BigDecimal` and `RatioScale.toPercentPoints(BigDecimal): BigDecimal`.
- Consumes: `EastMoneyAnnualIndicator.roe`, `grossMargin`, `revenueGrowth`, and `netProfitGrowth`, which already arrive as decimal ratios.

- [ ] **Step 1: Write the failing ratio contract test**

```java
package com.aistock.research.factor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RatioScaleTest {

    @Test
    void convertsBetweenPercentagePointsAndStoredDecimalRatio() {
        BigDecimal stored = RatioScale.fromPercentPoints("14.26");

        assertThat(stored).isEqualByComparingTo("0.1426");
        assertThat(RatioScale.toPercentPoints(stored)).isEqualByComparingTo("14.26");
    }
}
```

- [ ] **Step 2: Run the test and observe RED**

Run: `mvn -pl apps/api -Dtest=RatioScaleTest test`

Expected: test compilation fails because `RatioScale` does not exist.

- [ ] **Step 3: Implement the ratio contract**

```java
package com.aistock.research.factor;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RatioScale {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private RatioScale() {
    }

    public static BigDecimal fromPercentPoints(String value) {
        return new BigDecimal(value).divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    public static BigDecimal toPercentPoints(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(ONE_HUNDRED).stripTrailingZeros();
    }
}
```

- [ ] **Step 4: Run the ratio test and observe GREEN**

Run: `mvn -pl apps/api -Dtest=RatioScaleTest test`

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write the failing short-term scoring test with real source units**

Change `goodFinancial` test fixtures from `12.50`, `28.00`, and `6.00` to `0.1250`, `0.2800`, and `0.0600`, then add:

```java
@Test
void shouldScoreDecimalFinancialRatiosUsingDeclaredUnits() {
    eastMoneyClient.quotes = List.of(quote("600021", "比例样本", "设备", "10.50", "1.20", "35", "3.2", "600000000"));
    eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.50", "1800000"));
    eastMoneyClient.financials.put("600021", goodFinancial("600021"));

    ShortTermReport report = service.report(3, 50, 10, null, null, null, null, null, null, null);

    ShortTermCandidate candidate = report.candidates().stream()
            .filter(item -> "600021".equals(item.symbol()))
            .findFirst()
            .orElseThrow();
    assertThat(candidate.financial().roe()).isEqualByComparingTo("0.125");
    assertThat(candidate.financial().qualityScore()).isGreaterThanOrEqualTo("72");
}
```

- [ ] **Step 6: Run the focused test and observe RED**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest#shouldScoreDecimalFinancialRatiosUsingDeclaredUnits test`

Expected: assertion fails because the current service compares `0.125` with thresholds `12` and `8`.

- [ ] **Step 7: Replace percentage-point thresholds and evidence formatting**

In `ShortTermService`, define ratio constants through `RatioScale`:

```java
private static final BigDecimal ROE_STRONG = RatioScale.fromPercentPoints("12");
private static final BigDecimal ROE_ACCEPTABLE = RatioScale.fromPercentPoints("8");
private static final BigDecimal GROSS_MARGIN_STRONG = RatioScale.fromPercentPoints("30");
private static final BigDecimal GROSS_MARGIN_ACCEPTABLE = RatioScale.fromPercentPoints("15");
```

Use these constants in `financialQualityScore`, including the three-year average ROE comparison. Convert ratios through `RatioScale.toPercentPoints` before appending `%` in evidence text.

- [ ] **Step 8: Add a React decimal-ratio formatter and use it**

Add to `format.ts`:

```ts
export function formatRatioPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '待补充'
  return `${(Number(value) * 100).toFixed(2)}%`
}
```

In `ShortTermPage.tsx`, render ROE and gross margin with `formatRatioPercent` instead of adding `%` to `formatNumber`.

- [ ] **Step 9: Verify the task**

Run: `mvn -pl apps/api -Dtest=RatioScaleTest,ShortTermServiceTest test`

Expected: all ratio and short-term tests pass.

Run from `apps/web-react`: `npm run build`

Expected: TypeScript and Vite build succeed.

### Task 2: Make Quote Pagination And Coverage Auditable

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/AshareQuotePage.java`
- Create: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/AshareQuotePaginator.java`
- Create: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/AshareQuoteSnapshot.java`
- Create: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/AshareQuotePaginatorTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenCoverage.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Modify: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`

**Interfaces:**
- Produces: `EastMoneyClient.fetchAshareQuoteSnapshot(int): AshareQuoteSnapshot`.
- Produces: `UniversalScreenReport.coverage(): UniversalScreenCoverage`.
- Preserves: `EastMoneyClient.fetchAshareQuotes(int)` for existing callers by delegating to the snapshot method or shared collector.

- [ ] **Step 1: Write the failing paginator test**

```java
@Test
void continuesWhenProviderCapsRequestedPageSize() {
    AtomicInteger calls = new AtomicInteger();
    List<EastMoneyQuote> result = AshareQuotePaginator.collect(250, pageNumber -> {
        calls.incrementAndGet();
        int start = (pageNumber - 1) * 100;
        return new AshareQuotePage(12_378, IntStream.range(start, start + 100)
                .mapToObj(this::quote)
                .toList());
    });

    assertThat(result).hasSize(250);
    assertThat(calls).hasValue(3);
}
```

Use this exact helper so deduplication is exercised by unique six-digit symbols:

```java
private EastMoneyQuote quote(int index) {
    String symbol = "6" + String.format("%05d", index);
    return new EastMoneyQuote(
            symbol,
            "样本" + index,
            "上交所",
            "测试行业",
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ONE,
            BigDecimal.ONE,
            new BigDecimal("100000000"),
            new BigDecimal("20"),
            new BigDecimal("2"),
            new BigDecimal("20"),
            "分页测试",
            "https://quote.example.com/" + symbol,
            Instant.parse("2026-07-10T07:00:00Z")
    );
}
```

- [ ] **Step 2: Run the paginator test and observe RED**

Run: `mvn -pl apps/api -Dtest=AshareQuotePaginatorTest test`

Expected: compilation fails because the page and paginator contracts do not exist.

- [ ] **Step 3: Implement the bounded paginator**

```java
public final class AshareQuotePaginator {

    private static final int MAX_PAGES = 160;

    private AshareQuotePaginator() {
    }

    public static List<EastMoneyQuote> collect(int limit, IntFunction<AshareQuotePage> pageFetcher) {
        int target = Math.max(1, limit);
        Map<String, EastMoneyQuote> quotes = new LinkedHashMap<>();
        int expectedPages = MAX_PAGES;
        for (int pageNumber = 1; pageNumber <= Math.min(expectedPages, MAX_PAGES); pageNumber++) {
            AshareQuotePage page = pageFetcher.apply(pageNumber);
            if (page == null || page.quotes().isEmpty()) {
                break;
            }
            page.quotes().stream()
                    .filter(item -> item.symbol() != null)
                    .forEach(item -> quotes.putIfAbsent(item.symbol(), item));
            int effectivePageSize = page.quotes().size();
            int availableTarget = page.totalCount() > 0 ? Math.min(target, page.totalCount()) : target;
            expectedPages = Math.min(MAX_PAGES, (int) Math.ceil(availableTarget / (double) effectivePageSize));
            if (quotes.size() >= availableTarget) {
                break;
            }
        }
        return quotes.values().stream().limit(target).toList();
    }
}
```

- [ ] **Step 4: Run the paginator test and observe GREEN**

Run: `mvn -pl apps/api -Dtest=AshareQuotePaginatorTest test`

Expected: the test passes and records three page calls instead of one.

- [ ] **Step 5: Write failing client and coverage tests**

Add to `EastMoneyClientTest`:

```java
@Test
void bundledSecurityMasterIncludesBeijingExchange() {
    List<String> symbols = client.loadBundledAshareSymbols();

    assertThat(symbols).contains("920964", "600000", "000001");
    assertThat(symbols.size()).isGreaterThan(5_500);
}
```

Add to `UniversalAshareScreenerTest` using a stub snapshot with `expectedCount=100` and six returned quotes:

```java
assertThat(report.coverage().requestedCount()).isEqualTo(100);
assertThat(report.coverage().expectedCount()).isEqualTo(100);
assertThat(report.coverage().fetchedCount()).isEqualTo(6);
assertThat(report.coverage().missingCount()).isEqualTo(94);
assertThat(report.coverage().complete()).isFalse();
```

Update the existing test stub with an explicit snapshot override so the test never reaches the network:

```java
private int expectedCount;

@Override
public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
    List<EastMoneyQuote> quotes = baseQuotes.stream().limit(limit).toList();
    int expected = expectedCount > 0 ? expectedCount : quotes.size();
    int missing = Math.max(0, expected - quotes.size());
    return new AshareQuoteSnapshot(
            quotes,
            limit,
            expected,
            quotes.size(),
            missing,
            missing == 0,
            "测试行情",
            Instant.parse("2026-07-10T07:00:00Z")
    );
}
```

- [ ] **Step 6: Run focused tests and observe RED**

Run: `mvn -pl apps/api -Dtest=EastMoneyClientTest,UniversalAshareScreenerTest test`

Expected: BSE assertion fails because bundled loading currently accepts only market `CN`; coverage access fails because the report lacks the field.

- [ ] **Step 7: Integrate page metadata and snapshot coverage**

Use immutable records for page and snapshot metadata:

```java
public record AshareQuotePage(int totalCount, List<EastMoneyQuote> quotes) {
    public AshareQuotePage {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
}

public record AshareQuoteSnapshot(
        List<EastMoneyQuote> quotes,
        int requestedCount,
        int expectedCount,
        int fetchedCount,
        int missingCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {
    public AshareQuoteSnapshot {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
}
```

Change the EastMoney page reader to retain `data.total` and use the live paginated result as the canonical沪深北 candidate universe. Do not restrict it through a bundled symbol index or a secondary security master. Build a snapshot as follows:

```text
targetSymbols = balancedAshareSymbols(securityMaster, min(requestedCount, securityMaster.size))
primary       = paged EastMoney quotes filtered to targetSymbols
missing       = targetSymbols - primary.symbols
fallback      = Tencent quotes requested only for missing
merged        = primary + fallback, deduplicated in targetSymbols order
```

If the security master is unavailable, set `expectedCount=requestedCount` and use the provider result without claiming completeness unless its distinct count reaches that value. `fetchAshareQuoteSnapshot` calls an internal quote collector; `fetchAshareQuotes(int)` delegates to `fetchAshareQuoteSnapshot(int).quotes()` so the two methods do not call each other recursively.

Change the screener timeout path to fetch `AshareQuoteSnapshot`, then map its counts to `UniversalScreenCoverage`. Empty quotes still fail; partial non-empty coverage returns candidates with `complete=false` and a visible warning.

Use this API record:

```java
public record UniversalScreenCoverage(
        int requestedCount,
        int expectedCount,
        int fetchedCount,
        int missingCount,
        boolean complete,
        String source,
        Instant fetchedAt
) {
}
```

- [ ] **Step 8: Verify the task**

Run: `mvn -pl apps/api -Dtest=AshareQuotePaginatorTest,EastMoneyClientTest,UniversalAshareScreenerTest test`

Expected: pagination, BSE master coverage, and partial-coverage assertions pass.

### Task 3: Route Universal Screening Through Mode-Specific Eligibility

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenMode.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenRuleSet.java`
- Modify: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Produces: `UniversalScreenMode.fromExternal(String): UniversalScreenMode`.
- Preserves external mode values: `ALL`, `VALUE`, `CYCLE`, and `SHORT_TERM`.
- Changes default effective `excludeSideways` to `false` except in `SHORT_TERM` when the request enables it.

- [ ] **Step 1: Write four failing eligibility tests**

```java
@Test
void allModeKeepsTradableInventoryWithoutProfitLiquidityOrSidewaysHardGates() {
    client.sidewaysSymbols = Set.of("300001");
    UniversalScreenReport report = screen("ALL", true,
            quote("300001", "亏损样本", "软件", "12", "0", "-4", "3", "20000000"));

    assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol).contains("300001");
    assertThat(report.ruleSet().excludeSideways()).isFalse();
    assertThat(find(report, "300001").action()).isEqualTo("ELIGIBLE");
}

@Test
void valueModeRequiresPositiveProfitProxyButKeepsSidewaysCompaniesForResearch() {
    client.sidewaysSymbols = Set.of("600001");
    UniversalScreenReport report = screen("VALUE", true,
            quote("600001", "价值横盘", "食品", "10", "0", "12", "1.2", "200000000"),
            quote("600002", "亏损价值", "食品", "10", "0", "-2", "1.2", "200000000"));

    assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol).contains("600001").doesNotContain("600002");
    assertThat(report.exclusionsSample()).anySatisfy(item -> assertThat(item.stage()).isEqualTo("MODE_ELIGIBILITY"));
}

@Test
void cycleModeAllowsNegativePeForCycleIndustriesAndRejectsNonCycleIndustries() {
    UniversalScreenReport report = screen("CYCLE", true,
            quote("002772", "周期样本", "农业", "10", "0", "-2", "1.2", "200000000"),
            quote("600003", "普通样本", "软件", "10", "0", "20", "2", "200000000"));

    assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol).contains("002772").doesNotContain("600003");
}

@Test
void shortTermModeRequiresLiquidityAndRemovesOnlyConfirmedSidewaysWithoutBreakout() {
    client.sidewaysSymbols = Set.of("600004");
    UniversalScreenReport report = screen("SHORT_TERM", true,
            quote("600004", "横盘样本", "设备", "10", "0", "40", "6", "200000000"),
            quote("600005", "低流动样本", "设备", "10", "0", "40", "6", "20000000"));

    assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol).doesNotContain("600004", "600005");
    assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::stage).contains("SIDEWAYS", "LIQUIDITY");
}
```

Add these helpers to the existing test class:

```java
private UniversalScreenReport screen(String mode, boolean excludeSideways, EastMoneyQuote... quotes) {
    client.baseQuotes = List.of(quotes);
    client.tencentQuotes = client.baseQuotes;
    client.expectedCount = quotes.length;
    return screener.screen(new UniversalScreenRequest(
            20, 50, null, null, null, null, excludeSideways, true, mode
    ));
}

private UniversalScreenCandidate find(UniversalScreenReport report, String symbol) {
    return report.candidates().stream()
            .filter(candidate -> symbol.equals(candidate.symbol()))
            .findFirst()
            .orElseThrow();
}

private UniversalScreenStageStats stage(UniversalScreenReport report, String stage) {
    return report.stageStats().stream()
            .filter(item -> stage.equals(item.stage()))
            .findFirst()
            .orElseThrow();
}
```

- [ ] **Step 2: Run the eligibility tests and observe RED**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: `ALL` still removes negative PE and low liquidity, `VALUE` removes sideways, and `CYCLE` does not route to a distinct policy.

- [ ] **Step 3: Implement the mode policy enum**

```java
public enum UniversalScreenMode {
    ALL(false, false, false, false),
    VALUE(true, true, false, false),
    CYCLE(false, true, false, true),
    SHORT_TERM(false, true, true, false);

    private final boolean positiveProfitProxyRequired;
    private final boolean liquidityRequired;
    private final boolean sidewaysReviewSupported;
    private final boolean cycleIndustryRequired;

    UniversalScreenMode(
            boolean positiveProfitProxyRequired,
            boolean liquidityRequired,
            boolean sidewaysReviewSupported,
            boolean cycleIndustryRequired
    ) {
        this.positiveProfitProxyRequired = positiveProfitProxyRequired;
        this.liquidityRequired = liquidityRequired;
        this.sidewaysReviewSupported = sidewaysReviewSupported;
        this.cycleIndustryRequired = cycleIndustryRequired;
    }

    public boolean positiveProfitProxyRequired() {
        return positiveProfitProxyRequired;
    }

    public boolean liquidityRequired() {
        return liquidityRequired;
    }

    public boolean cycleIndustryRequired() {
        return cycleIndustryRequired;
    }

    public boolean effectiveSidewaysReview(boolean requested) {
        return requested && sidewaysReviewSupported;
    }

    public boolean allowsBuyLikeScreeningAction() {
        return this == VALUE;
    }

    public static UniversalScreenMode fromExternal(String value) {
        if (value == null || value.isBlank()) return ALL;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
```

- [ ] **Step 4: Rebuild the funnel around policy stages**

The stage order becomes:

```text
UNIVERSE -> TRADABLE -> MODE_ELIGIBILITY -> LIQUIDITY
         -> SCORE -> DEEP_REVIEW -> SIDEWAYS -> FINAL
```

Skipped gates retain all input and report zero exclusions. `SIDEWAYS.inputCount` must equal the number actually reviewed, not the entire scored pool. Stocks outside the deep-review budget are counted as deferred, not as sideways exclusions; add `deferredCount` to `UniversalScreenStageStats` and set it only on `DEEP_REVIEW`.

```java
public record UniversalScreenStageStats(
        String stage,
        String label,
        int inputCount,
        int passedCount,
        int excludedCount,
        int deferredCount
) {
}
```

Mode behavior:

```text
ALL        tradable inventory; no profit/liquidity/sideways hard gate; action ELIGIBLE
VALUE      positive PE proxy and liquidity; no sideways gate; valuation research actions allowed
CYCLE      cycle-industry mapping and liquidity; negative/high PE allowed; action CYCLE_RESEARCH
SHORT_TERM liquidity and optional sideways-without-breakout gate; action SHORT_RESEARCH
```

- [ ] **Step 5: Verify funnel arithmetic**

Add an assertion:

```java
UniversalScreenStageStats sideways = stage(report, "SIDEWAYS");
assertThat(sideways.inputCount()).isEqualTo(client.klineRequestCount);
assertThat(sideways.excludedCount()).isEqualTo(sideways.inputCount() - sideways.passedCount());
assertThat(report.stageStats()).allSatisfy(item ->
        assertThat(item.inputCount()).isEqualTo(item.passedCount() + item.excludedCount() + item.deferredCount()));
```

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest,MarketScanServiceTest test`

Expected: all mode and funnel arithmetic tests pass.

### Task 4: Separate Screening Metadata From Final Advice In API And UI

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`

**Interfaces:**
- Renames market API fields `action` to `screeningAction` and `actionLabel` to `screeningActionLabel`.
- Keeps `todayAdvice: TradingAdvice` as the only final recommendation field.
- Adds `coverage: UniversalScreenCoverage` and `deferredCount` to the React report types.

- [ ] **Step 1: Write failing final-advice tests**

```java
@Test
void allModeNeverTurnsEligibilityRankingIntoBuyAdvice() {
    MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "ALL");

    MarketScanCandidate candidate = report.candidates().get(0);
    assertThat(candidate.screeningAction()).isEqualTo("ELIGIBLE");
    assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
    assertThat(candidate.todayAdvice().summary()).contains("全市场资格");
}

@Test
void valueModeCanOnlyKeepBuyAdviceAfterEvidenceGate() {
    StubRecommendationEvidenceEnrichmentService enrichmentService = new StubRecommendationEvidenceEnrichmentService();
    MarketScanService evidenceAwareService = new MarketScanService(
            universalScreener,
            new EvidenceCompletenessService(),
            enrichmentService
    );
    eastMoneyClient.baseQuotes = List.of(
            quote("600036", "招商银行", "银行", "36.83", "-0.50", "6.15", "0.83", "900000000")
    );
    eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;
    enrichmentService.bundle = completeBundle("600036");

    MarketScanReport report = evidenceAwareService.report(3, 50, null, null, null, null, true, true, "VALUE");

    MarketScanCandidate candidate = report.candidates().get(0);
    assertThat(candidate.screeningAction()).isEqualTo("ACCUMULATE");
    assertThat(candidate.todayAdvice().action()).isEqualTo("ADD");
}
```

- [ ] **Step 2: Run market tests and observe RED**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest test`

Expected: compilation fails because `screeningAction` fields do not exist and `ALL` can currently inherit `ACCUMULATE`.

- [ ] **Step 3: Rename the DTO fields and make advice mode-aware**

Use this record segment:

```java
MarketScanScoreBreakdown score,
String screeningAction,
String screeningActionLabel,
String reason,
TradingAdvice todayAdvice,
```

In `MarketScanService.todayAdvice`, return `WAIT` before evaluating raw score when mode is `ALL`, `CYCLE`, or `SHORT_TERM` in this generic market page. The dedicated cycle and short-term services remain responsible for their executable recommendations. Only `VALUE` can map `ACCUMULATE` to an `ADD` candidate, and `EvidenceCompletenessService.gateAdvice` remains the final veto.

- [ ] **Step 4: Update React contracts and presentation**

Update the TypeScript fields to `screeningAction` and `screeningActionLabel`. Candidate rows show only:

```tsx
<Tag tone={adviceTone(candidate.todayAdvice.action)}>
  今日：{candidate.todayAdvice.actionLabel}
</Tag>
```

The detail panel may show `screeningActionLabel` under the neutral label “资格阶段”, but it must not place it beside `todayAdvice` as a competing recommendation.

Add coverage metrics:

```tsx
<Metric label="计划覆盖" value={report.coverage.expectedCount} />
<Metric label="实际覆盖" value={report.coverage.fetchedCount} />
<Metric label="缺失" value={report.coverage.missingCount} />
<Metric label="覆盖状态" value={report.coverage.complete ? '完整' : '部分'} />
```

Change the scan default and maximum from `5000` to `6000`; the backend limits the effective target to the known active security master count.

- [ ] **Step 5: Verify API and UI**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest,UniversalAshareScreenerTest test`

Expected: raw eligibility and final advice tests pass.

Run from `apps/web-react`: `npm run build`

Expected: TypeScript and Vite build succeed with no stale `candidate.action` references on the market page.

### Task 5: P0 Regression And Live Contract Check

**Files:**
- No source files are planned for modification; this task verifies Tasks 1-4. A newly discovered defect starts a separate red-green cycle in its owning test and source file before this verification is rerun.

**Interfaces:**
- Verifies the complete P0 API and frontend contract.

- [ ] **Step 1: Run the complete backend suite**

Run: `mvn -pl apps/api test`

Expected: `BUILD SUCCESS`, with at least the baseline 86 tests plus the new ratio, paginator, coverage, mode, and final-advice tests; `Failures: 0, Errors: 0`.

- [ ] **Step 2: Run the production frontend build**

Run from `apps/web-react`: `npm run build`

Expected: Vite emits a production bundle and TypeScript reports no errors.

- [ ] **Step 3: Start the existing Docker stack without changing secrets**

Run: `docker compose up -d --build api web-react`

Expected: both containers reach healthy/running state; existing Nacos API keys remain untouched.

- [ ] **Step 4: Smoke-test strategy separation and coverage**

Run:

```bash
curl --max-time 120 'http://127.0.0.1:19080/api/market-scan/report?limit=8&scanLimit=6000&mode=ALL'
```

Expected JSON properties:

```text
coverage.requestedCount = 6000
coverage.expectedCount <= 6000
coverage.fetchedCount = universeCount
coverage.complete accurately reflects missingCount
candidates[*].screeningAction = ELIGIBLE
candidates[*].todayAdvice.action = WAIT
```

Repeat with `mode=VALUE`, `mode=CYCLE`, and `mode=SHORT_TERM`; candidate/exclusion sets must differ, proving the mode is no longer a response-only field.

- [ ] **Step 5: Record the implementation checkpoint**

Run: `git status --short -- apps/api apps/web-react docs/superpowers/plans/2026-07-10-p0-correctness-baseline.md`

Expected: only this plan is tracked; application files remain untracked baseline files. Do not stage them automatically.
