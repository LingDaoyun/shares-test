# First-Board 15-Bar Valid Curve Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Filter first-board symbols with fewer than 15 stored daily bars so unavailable curves no longer block a valid market day.

**Architecture:** Introduce one package-local curve policy containing the 15-bar rule. The ingestion service fetches gaps, then re-reads stored counts and publishes only symbols meeting the rule; the baseline service applies the same rule before technical evaluation, while the evaluator keeps its existing 60-bar requirement.

**Tech Stack:** Java 17, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ, Maven

## Global Constraints

- A valid curve has at least 15 stored daily bars ending on or before the baseline date.
- Symbols with 0 through 14 bars are filtered and do not appear in final market-day expected, written, or failed counts.
- Symbols with 15 through 59 bars remain valid curves but may be counted as technically insufficient by the unchanged evaluator.
- `FirstBoardTechnicalEvaluator.MINIMUM_BARS` remains 60.
- Do not add or modify Flyway migrations, tables, columns, retries, data sources, or persisted per-symbol filter state.
- Do not delete existing bars or mutate the running database.
- This plan changes backend code and unit tests only; deployment and runtime recovery require separate authorization.

---

## File Structure

- Create `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardCurvePolicy.java`: owns the shared 15-bar validity rule.
- Modify `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java`: fetches gaps, re-counts stored bars, filters short curves, and publishes final valid counts.
- Modify `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java`: proves 15-bar inclusion, short-curve filtering, and all-filtered fail-closed behavior.
- Modify `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java`: filters the evaluation universe with the shared policy.
- Create `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServiceTest.java`: proves 0–14 bars are excluded and 15–59 bars remain technically insufficient.

---

### Task 1: Filter unusable curves after historical ingestion

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardCurvePolicy.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java:30,53-126`
- Test: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java`

**Interfaces:**
- Consumes: `FirstBoardDailyBarStore.completedBarCounts(Collection<String>, LocalDate, int)` and the existing full-market quote list.
- Produces: `FirstBoardCurvePolicy.MINIMUM_VALID_BARS` and `FirstBoardCurvePolicy.hasValidCurve(String, Map<String, Integer>)` for both ingestion and baseline construction.

- [ ] **Step 1: Replace the ingestion tests with failing 15-bar behavior tests**

Keep the existing fixture and teardown, replace the two current test methods with these three methods, and add static imports for `never`, `times`, `verify`, and `eq`:

```java
@Test
void includesFetchedStockAfterItReachesFifteenStoredBars() {
    LocalDate scanDate = LocalDate.parse("2026-08-28");
    LocalDate baselineDate = LocalDate.parse("2026-08-27");
    FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
    FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    TradingClockService clock = mock(TradingClockService.class);
    when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
    when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
    when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
    when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
            List.of(quote("600000"), quote("001234")), 2, 2, 2, 0,
            true, "test", Instant.parse("2026-08-28T07:00:00Z")));
    when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
            Map.of("600000", 15, "001234", 0),
            Map.of("600000", 15, "001234", 15));
    when(gateway.dailyBars(any(), any(), any())).thenReturn(List.of());
    when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
    when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
    service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

    FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

    assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
    assertThat(progress.completed()).isEqualTo(2);
    assertThat(progress.total()).isEqualTo(2);
    assertThat(progress.failed()).isZero();
    verify(store, times(2)).completedBarCounts(any(), eq(baselineDate), eq(15));
}

@Test
void filtersStockBelowFifteenBarsWhenAnotherValidCurveExists() {
    LocalDate scanDate = LocalDate.parse("2026-08-28");
    LocalDate baselineDate = LocalDate.parse("2026-08-27");
    FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
    FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    TradingClockService clock = mock(TradingClockService.class);
    when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
    when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
    when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
    when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
            List.of(quote("600000"), quote("001234")), 2, 2, 2, 0,
            true, "test", Instant.parse("2026-08-28T07:00:00Z")));
    when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
            Map.of("600000", 15, "001234", 0),
            Map.of("600000", 15, "001234", 14));
    when(gateway.dailyBars(any(), any(), any()))
            .thenThrow(new IllegalStateException("provider has no curve"));
    when(gateway.limitUpPool(baselineDate)).thenReturn(List.of());
    when(store.calculateBarDataVersion(baselineDate)).thenReturn("bars-v1");
    service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

    FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

    assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.READY);
    assertThat(progress.completed()).isEqualTo(1);
    assertThat(progress.total()).isEqualTo(1);
    assertThat(progress.failed()).isZero();
    assertThat(progress.errorSummary()).isNull();
}

@Test
void failsClosedWhenNoSymbolHasFifteenStoredBars() {
    LocalDate scanDate = LocalDate.parse("2026-08-28");
    LocalDate baselineDate = LocalDate.parse("2026-08-27");
    FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
    FirstBoardDailyBarStore store = mock(FirstBoardDailyBarStore.class);
    TradingClockService clock = mock(TradingClockService.class);
    when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
    when(store.marketDay(baselineDate)).thenReturn(Optional.empty());
    when(store.latestReadyMarketDay()).thenReturn(Optional.empty());
    when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
            List.of(quote("001234")), 1, 1, 1, 0,
            true, "test", Instant.parse("2026-08-28T07:00:00Z")));
    when(store.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
            Map.of("001234", 0),
            Map.of("001234", 14));
    when(gateway.dailyBars(any(), any(), any())).thenReturn(List.of());
    service = new FirstBoardMarketDayIngestionService(gateway, store, clock);

    FirstBoardIngestionProgress progress = service.ensureBaselineDayReady(scanDate);

    assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.FAILED);
    assertThat(progress.completed()).isZero();
    assertThat(progress.total()).isZero();
    assertThat(progress.failed()).isEqualTo(1);
    assertThat(progress.nextRetryAt()).isNotNull();
    assertThat(progress.errorSummary()).contains("至少 15 根有效日线");
    verify(gateway, never()).limitUpPool(any());
}
```

- [ ] **Step 2: Run the focused test to verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected: FAIL. The existing implementation queries with `60`, marks the provider failure as a failed symbol, and cannot return the expected `READY 1/1 failed=0` result.

- [ ] **Step 3: Add the shared 15-bar policy**

Create `FirstBoardCurvePolicy.java`:

```java
package com.aistock.research.firstboard;

import java.util.Map;

final class FirstBoardCurvePolicy {

    static final int MINIMUM_VALID_BARS = 15;

    private FirstBoardCurvePolicy() {
    }

    static boolean hasValidCurve(String symbol, Map<String, Integer> counts) {
        return symbol != null
                && counts != null
                && counts.getOrDefault(symbol, 0) >= MINIMUM_VALID_BARS;
    }
}
```

- [ ] **Step 4: Replace ingestion completion-by-request with completion-by-stored-count**

Delete `MINIMUM_HISTORY_BARS` and replace `ensureBaselineDayReady` with the following implementation. Keep all other methods unchanged.

```java
public FirstBoardIngestionProgress ensureBaselineDayReady(LocalDate scanTradeDate) {
    LocalDate baselineDate = clock.previousTradingDay(scanTradeDate);
    FirstBoardMarketDay existing = store.marketDay(baselineDate).orElse(null);
    if (existing != null && existing.status() == FirstBoardMarketDayStatus.READY) {
        return FirstBoardIngestionProgress.from(existing);
    }
    if (existing != null && existing.status() == FirstBoardMarketDayStatus.FAILED
            && existing.nextRetryAt() != null && Instant.now().isBefore(existing.nextRetryAt())) {
        return FirstBoardIngestionProgress.from(existing);
    }

    Instant startedAt = existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt();
    try {
        if (existing == null && store.latestReadyMarketDay().isEmpty()) {
            store.importLatestHistoryVersions();
        }
        AshareQuoteSnapshot quoteSnapshot = gateway.fullMarketQuotes();
        if (quoteSnapshot == null || !quoteSnapshot.complete()) {
            throw new IllegalStateException("全市场股票列表不完整");
        }
        List<EastMoneyQuote> candidates = eligibleQuotes(quoteSnapshot);
        List<String> symbols = candidates.stream().map(EastMoneyQuote::symbol).toList();
        Map<String, Integer> initialCounts = store.completedBarCounts(
                symbols, baselineDate, FirstBoardCurvePolicy.MINIMUM_VALID_BARS);
        List<EastMoneyQuote> gaps = candidates.stream()
                .filter(quote -> !FirstBoardCurvePolicy.hasValidCurve(quote.symbol(), initialCounts))
                .toList();
        AtomicInteger processed = new AtomicInteger(candidates.size() - gaps.size());
        saveImporting(baselineDate, startedAt, candidates.size(), processed.get(), 0);

        List<CompletableFuture<Void>> tasks = gaps.stream()
                .map(quote -> CompletableFuture.runAsync(() -> {
                    List<EastMoneyKLine> rows;
                    try {
                        rows = gateway.dailyBars(
                                quote.symbol(), baselineDate.minusDays(260), baselineDate);
                    } catch (RuntimeException ignored) {
                        // Final stored-bar counts decide whether this symbol has a valid curve.
                        rows = List.of();
                    }
                    if (rows != null && !rows.isEmpty()) {
                        // Store failures are system failures and must still fail the whole market day.
                        store.upsertBars(rows, "东方财富前复权日线");
                    }
                    saveImporting(baselineDate, startedAt, candidates.size(), processed.incrementAndGet(), 0);
                }, seedExecutor))
                .toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();

        Map<String, Integer> finalCounts = store.completedBarCounts(
                symbols, baselineDate, FirstBoardCurvePolicy.MINIMUM_VALID_BARS);
        int validCount = (int) candidates.stream()
                .filter(quote -> FirstBoardCurvePolicy.hasValidCurve(quote.symbol(), finalCounts))
                .count();
        saveImporting(baselineDate, startedAt, validCount, validCount, 0);
        if (validCount == 0) {
            return fail(baselineDate, startedAt, null,
                    new IllegalStateException("没有股票达到最近至少 15 根有效日线门槛"));
        }

        Set<String> limitUps = gateway.limitUpPool(baselineDate).stream()
                .map(EastMoneyLimitUpPoolEntry::symbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        store.confirmLimitUpPool(baselineDate, limitUps);
        FirstBoardMarketDay ready = new FirstBoardMarketDay(
                baselineDate, baselineDate, FirstBoardMarketDayStatus.READY,
                validCount, validCount, 0, true,
                store.calculateBarDataVersion(baselineDate), startedAt, Instant.now(), null, null);
        store.saveMarketDay(ready);
        return FirstBoardIngestionProgress.from(ready);
    } catch (RuntimeException exception) {
        return fail(baselineDate, startedAt, existing, exception);
    }
}
```

- [ ] **Step 5: Run the focused test to verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected: PASS with 3 tests and no failure or error.

- [ ] **Step 6: Commit Task 1**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardCurvePolicy.java \
  apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java \
  apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java
git commit -m "fix(first-board): filter curves shorter than 15 bars"
```

---

### Task 2: Apply the same 15-bar rule before technical baseline evaluation

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java:156-207`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServiceTest.java`

**Interfaces:**
- Consumes: `FirstBoardCurvePolicy.MINIMUM_VALID_BARS` and `hasValidCurve(...)` from Task 1.
- Produces: a baseline run whose `supportedCount` is the number of name-eligible symbols with at least 15 stored bars; 15–59 bar symbols remain in the evaluator path and increment `insufficientHistoryCount` when evaluation returns empty.

- [ ] **Step 1: Add a failing asynchronous baseline filtering test**

Create `FirstBoardBaselineServiceTest.java`:

```java
package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstBoardBaselineServiceTest {

    private FirstBoardBaselineService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void excludesCurvesBelowFifteenBarsButKeepsFifteenToFiftyNineAsInsufficient() throws Exception {
        LocalDate scanDate = LocalDate.parse("2026-08-28");
        LocalDate baselineDate = LocalDate.parse("2026-08-27");
        FirstBoardMarketDataGateway gateway = mock(FirstBoardMarketDataGateway.class);
        FirstBoardMarketDayIngestionService ingestionService = mock(FirstBoardMarketDayIngestionService.class);
        FirstBoardDailyBarStore dailyBarStore = mock(FirstBoardDailyBarStore.class);
        FirstBoardBaselineStore baselineStore = mock(FirstBoardBaselineStore.class);
        FirstBoardTechnicalEvaluator evaluator = mock(FirstBoardTechnicalEvaluator.class);
        TradingClockService clock = mock(TradingClockService.class);
        FirstBoardMarketDay readyDay = new FirstBoardMarketDay(
                baselineDate, baselineDate, FirstBoardMarketDayStatus.READY,
                2, 2, 0, true, "bars-v1", Instant.now(), Instant.now(), null, null);
        FirstBoardBaselineRun run = new FirstBoardBaselineRun(
                UUID.randomUUID(), scanDate, baselineDate, FirstBoardBaselineService.BASELINE_RULE_VERSION,
                "bars-v1", FirstBoardBaselineRunStatus.BUILDING, 3, 2, 0, 1,
                0, Instant.now(), null, null);
        CountDownLatch published = new CountDownLatch(1);

        when(clock.isMarketClosedDay(scanDate)).thenReturn(false);
        when(clock.previousTradingDay(scanDate)).thenReturn(baselineDate);
        when(dailyBarStore.marketDay(baselineDate)).thenReturn(Optional.of(readyDay));
        when(baselineStore.findReady(scanDate, baselineDate,
                FirstBoardBaselineService.BASELINE_RULE_VERSION, "bars-v1")).thenReturn(Optional.empty());
        when(gateway.fullMarketQuotes()).thenReturn(new AshareQuoteSnapshot(
                List.of(quote("600000"), quote("001234"), quote("000003")),
                3, 3, 3, 0, true, "test", Instant.now()));
        when(dailyBarStore.completedBarCounts(any(), eq(baselineDate), eq(15))).thenReturn(
                Map.of("600000", 60, "001234", 15, "000003", 14));
        when(dailyBarStore.confirmedLimitUpSymbols(baselineDate)).thenReturn(java.util.Set.of());
        when(dailyBarStore.recentBars(anyString(), eq(baselineDate), eq(260))).thenReturn(List.of());
        when(evaluator.evaluate(eq("600000"), any(), any(), eq(FirstBoardBoard.MAIN),
                anyList(), eq(baselineDate), anyBoolean())).thenReturn(Optional.of(baseline("600000", baselineDate)));
        when(evaluator.evaluate(eq("001234"), any(), any(), eq(FirstBoardBoard.MAIN),
                anyList(), eq(baselineDate), anyBoolean())).thenReturn(Optional.empty());
        when(baselineStore.startRun(any(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(run);
        when(baselineStore.publish(eq(run), any())).thenAnswer(invocation -> {
            published.countDown();
            return new FirstBoardBaselineSnapshot(
                    scanDate, baselineDate, Instant.now(), Map.of("600000", baseline("600000", baselineDate)),
                    3, 2, 0, 1);
        });
        service = new FirstBoardBaselineService(
                gateway, ingestionService, dailyBarStore, baselineStore, evaluator, clock);

        FirstBoardBaselineAccess access = service.access(scanDate);

        assertThat(access.status()).isEqualTo(FirstBoardAlertStatus.UPDATING);
        assertThat(published.await(3, TimeUnit.SECONDS)).isTrue();
        verify(baselineStore).startRun(
                scanDate, baselineDate, FirstBoardBaselineService.BASELINE_RULE_VERSION,
                "bars-v1", 3, 2, 0, 1);
        verify(evaluator, never()).evaluate(eq("000003"), any(), any(), any(FirstBoardBoard.class),
                anyList(), eq(baselineDate), anyBoolean());
    }

    private EastMoneyQuote quote(String symbol) {
        return new EastMoneyQuote(
                symbol, "测试股份", "沪深", "测试行业",
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("1000"), new BigDecimal("10000"), null, null, null,
                "test", "https://example.test", Instant.parse("2026-08-28T07:00:00Z"));
    }

    private FirstBoardBaseline baseline(String symbol, LocalDate baselineDate) {
        return new FirstBoardBaseline(
                symbol, "测试股份", "测试行业", FirstBoardBoard.MAIN, baselineDate,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE,
                new BigDecimal("55"), BigDecimal.ONE, 5, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false, List.of());
    }
}
```

- [ ] **Step 2: Run the new test to verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardBaselineServiceTest test
```

Expected: FAIL because the current baseline service does not call `completedBarCounts(..., 15)`, evaluates `000003`, and reports a supported count of 3 instead of 2.

- [ ] **Step 3: Filter `eligibleBySymbol` with the shared curve policy**

In `FirstBoardBaselineService.build`, insert the stored-count filter after name eligibility and before `confirmedLimitUpSymbols`:

```java
Map<String, Integer> curveCounts = dailyBarStore.completedBarCounts(
        eligibleBySymbol.keySet(), baselineDate, FirstBoardCurvePolicy.MINIMUM_VALID_BARS);
eligibleBySymbol.entrySet().removeIf(entry ->
        !FirstBoardCurvePolicy.hasValidCurve(entry.getKey(), curveCounts));
if (eligibleBySymbol.isEmpty()) {
    throw new IllegalStateException("没有股票达到最近至少 15 根有效日线门槛");
}
```

In the same method, change the `startRun` call so `supportedCount` uses the filtered map:

```java
FirstBoardBaselineRun run = baselineStore.startRun(
        scanDate, baselineDate, BASELINE_RULE_VERSION, marketDay.barDataVersion(),
        quoteSnapshot.quotes().size(), eligibleBySymbol.size(), excludedNameCount.get(),
        insufficientHistoryCount.get());
```

Do not change `FirstBoardTechnicalEvaluator.MINIMUM_BARS`; its existing `60` continues to make the 15-bar symbol increment `insufficientHistoryCount`.

- [ ] **Step 4: Run baseline and evaluator tests to verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardBaselineServiceTest,FirstBoardTechnicalEvaluatorTest test
```

Expected: PASS. The new baseline service test reports `supportedCount=2` and `insufficientHistoryCount=1`; the evaluator test still rejects 59 bars and accepts 60 or more.

- [ ] **Step 5: Commit Task 2**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java \
  apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServiceTest.java
git commit -m "fix(first-board): align baseline with valid curves"
```

---

### Task 3: Focused unit regression and clean handoff

**Files:**
- Verify only; no new files.

**Interfaces:**
- Consumes: completed Tasks 1 and 2.
- Produces: fresh unit-test evidence and a clean Git state; it does not deploy or mutate PostgreSQL.

- [ ] **Step 1: Run the complete database-independent first-board unit-test group**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardAlertServiceTest,FirstBoardBaselineServiceTest,FirstBoardMarketDayIngestionServiceTest,FirstBoardTechnicalEvaluatorTest test
```

Expected: PASS for ingestion, baseline, alert, and technical-evaluator tests. Do not include `FirstBoardPersistenceStoreTest` or `FirstBoardRetentionServiceTest`: they require explicitly supplied PostgreSQL integration-test credentials, and this change does not alter persistence SQL.

- [ ] **Step 2: Compile the complete backend test source set without running unrelated tests**

Run:

```bash
mvn -pl apps/api -DskipTests package
```

Expected: `BUILD SUCCESS`; main and test sources compile, and Surefire reports tests skipped for this packaging step.

- [ ] **Step 3: Check the resulting diff and worktree**

Run:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors; no uncommitted implementation files remain; the design commit and the two implementation commits are the newest local commits.

- [ ] **Step 4: Report the bounded result**

Report:

- The 15-bar rule is shared by ingestion and baseline generation.
- 0–14 bars are filtered from final counts.
- 15–59 bars remain valid curves but cannot bypass the unchanged 60-bar technical rule.
- No database migration, deployment, runtime retry, or push was performed.
- PostgreSQL-backed persistence and retention tests were not run because this change does not alter persistence SQL and no test-database authorization is part of this plan.
- A later authorized deployment should allow the existing failed day to retry and normalize from `4951/5081 failed=130` to the valid-curve count.
