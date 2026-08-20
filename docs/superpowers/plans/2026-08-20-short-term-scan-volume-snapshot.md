# Short-Term Scan Volume Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `todayVolume` use the cumulative volume from the manual or scheduled scan's unified quote snapshot while keeping the three-day comparison display-only.

**Architecture:** `ShortTermService` already owns the point-in-time `EastMoneyQuote` captured for a scan. It will pass that quote's trade date and volume into `ShortTermTechnicalSignalEvaluator`; the evaluator will use daily K-lines only to select the three completed sessions strictly before the quote date. Existing 5-day and 20-day ratios continue using their current K-line path.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, React, TypeScript, Vitest, Docker Compose.

## Global Constraints

- “今日成交量” means cumulative volume at the scan quote snapshot, not only the post-close daily total.
- The previous-three-day average uses the three latest positive daily bars strictly before `quote.tradeDate` and excludes a current-day bar when present.
- `volumeRatio3` remains display-only and must not affect candidate eligibility, scoring, ranking, action, or advice.
- Do not add a same-time intraday comparison, projected close volume, threshold configuration, or extra external request.
- Missing or non-positive snapshot volume, missing quote trade date, or invalid history returns all three display fields as `null`; never fall back to a stale K-line volume.
- Preserve unrelated uncommitted changes already present in `ShortTermService.java`, `ShortTermServiceTest.java`, and the frontend. Do not stage or commit implementation files wholesale.

---

### Task 1: Prove the Scan Snapshot Must Own Today's Volume

**Files:**
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluator.java`

**Interfaces:**
- Consumes: `EastMoneyQuote.tradeDate()`, `EastMoneyQuote.volume()`, and the candidate's sorted `List<EastMoneyKLine>`.
- Produces: the existing `ShortTermTechnicalSnapshot.todayVolume()`, `averageVolume3()`, and `volumeRatio3()` fields without changing the JSON contract.

- [ ] **Step 1: Add a failing service regression test**

Add this test near the existing volume behavior tests in `ShortTermServiceTest`:

```java
@Test
void usesScanSnapshotVolumeForDisplayAndKeepsLowThreeDayRatioAsCandidate() {
    String symbol = "600215";
    EastMoneyQuote scanQuote = withVolume(
            quote(symbol, "扫描量样本", "10.62", "1.10", "28", "2.6", "280000000"),
            "50000"
    );
    eastMoneyClient.quotes = List.of(scanQuote);
    eastMoneyClient.klines.put(
            symbol,
            endingOn(
                    confirmedRightEarlyKLines(symbol, "10.62", "230000"),
                    scanQuote.tradeDate()
            )
    );
    eastMoneyClient.financials.put(symbol, goodFinancial(symbol));

    ShortTermReport report = service.report(
            3, 100, 10, null, null, null, null, null, null, null
    );

    ShortTermCandidate candidate = find(report, symbol);
    assertThat(candidate.technical().todayVolume()).isEqualByComparingTo("50000.00");
    assertThat(candidate.technical().averageVolume3()).isEqualByComparingTo("105000.00");
    assertThat(candidate.technical().volumeRatio3()).isEqualByComparingTo("0.48");
    assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains(symbol);
}
```

Add these two helpers near the existing quote and K-line helpers:

```java
private EastMoneyQuote withVolume(EastMoneyQuote quote, String volume) {
    return new EastMoneyQuote(
            quote.symbol(),
            quote.name(),
            quote.market(),
            quote.industry(),
            quote.latestPrice(),
            quote.changePercent(),
            quote.turnoverRate(),
            new BigDecimal(volume),
            quote.amount(),
            quote.peRatio(),
            quote.pbRatio(),
            quote.peTtm(),
            quote.sourceName(),
            quote.quoteUrl(),
            quote.fetchedAt(),
            quote.tradeDate(),
            quote.marketTimestamp()
    );
}

private List<EastMoneyKLine> endingOn(List<EastMoneyKLine> rows, LocalDate endDate) {
    LocalDate startDate = endDate.minusDays(rows.size() - 1L);
    return IntStream.range(0, rows.size())
            .mapToObj(index -> {
                EastMoneyKLine row = rows.get(index);
                return new EastMoneyKLine(
                        row.symbol(),
                        startDate.plusDays(index),
                        row.open(),
                        row.close(),
                        row.high(),
                        row.low(),
                        row.volume(),
                        row.amount(),
                        row.turnoverRate()
                );
            })
            .toList();
}
```

- [ ] **Step 2: Run the single test and verify the red state**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest#usesScanSnapshotVolumeForDisplayAndKeepsLowThreeDayRatioAsCandidate test
```

Expected: FAIL because the current implementation returns the current daily K-line volume `230000.00` instead of the scan quote volume `50000.00`.

- [ ] **Step 3: Add an explicit scan-volume evaluation overload**

Keep the existing evaluator method unchanged for historical and direct unit callers. Add this overload, which recalculates only the three display fields from the scan observation and preserves every existing technical result:

```java
public ShortTermTechnicalSignalEvaluation evaluate(
        List<EastMoneyKLine> source,
        BigDecimal evaluationClose,
        LocalDate evaluationTradeDate,
        BigDecimal evaluationVolume,
        boolean latestBarCompleted,
        ShortTermRuleSet ruleSet
) {
    ShortTermTechnicalSignalEvaluation evaluation = evaluate(
            source,
            evaluationClose,
            latestBarCompleted,
            ruleSet
    );
    VolumeComparison comparison = threeDayVolumeComparison(
            evaluation.rows(),
            evaluationVolume
    );
    ShortTermTechnicalSnapshot snapshot = evaluation.snapshot().withVolumeComparison(
            scale(comparison.todayVolume()),
            scale(comparison.averageVolume3()),
            scale(comparison.volumeRatio3())
    );
    return new ShortTermTechnicalSignalEvaluation(
            evaluation.rows(),
            snapshot,
            evaluation.last(),
            evaluation.previous(),
            evaluation.dataGaps()
    );
}
```

Add `java.time.LocalDate` to the evaluator imports. For this first green step, add this helper beside the existing one; it accepts the snapshot volume while preserving the current assumption that the last row is the current day:

```java
private VolumeComparison threeDayVolumeComparison(
        List<EastMoneyKLine> rows,
        BigDecimal evaluationVolume
) {
    if (rows == null || rows.size() < 4 || !positive(evaluationVolume)) {
        return VolumeComparison.unavailable();
    }
    int size = rows.size();
    List<BigDecimal> previousVolumes = rows.subList(size - 4, size - 1).stream()
            .map(EastMoneyKLine::volume)
            .toList();
    if (previousVolumes.stream().anyMatch(value -> !positive(value))) {
        return VolumeComparison.unavailable();
    }
    BigDecimal averageVolume3 = previousVolumes.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    BigDecimal volumeRatio3 = evaluationVolume.divide(
            averageVolume3, 6, RoundingMode.HALF_UP);
    return new VolumeComparison(evaluationVolume, averageVolume3, volumeRatio3);
}
```

- [ ] **Step 4: Wire the scan quote snapshot into the evaluator**

Change the call in `ShortTermService.technicalContext` to:

```java
ShortTermTechnicalSignalEvaluation evaluation = technicalSignalEvaluator.evaluate(
        sorted,
        close,
        quote.tradeDate(),
        quote.volume(),
        latestBarCompleted,
        ruleSet
);
```

Do not change any candidate filters, score inputs, ranking comparators, decisions, or advice.

- [ ] **Step 5: Run the single service test and verify the first green state**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest#usesScanSnapshotVolumeForDisplayAndKeepsLowThreeDayRatioAsCandidate test
```

Expected: PASS. The candidate remains present with `volumeRatio3 = 0.48`.

---

### Task 2: Support a Snapshot Before the Current Daily Bar Appears

**Files:**
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluatorTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluator.java`

**Interfaces:**
- Consumes: evaluation trade date and cumulative scan volume independently of whether that date already exists in the daily K-line list.
- Produces: a comparison against the latest three positive rows strictly before the evaluation trade date.

- [ ] **Step 1: Add a failing evaluator test for a missing current-day K-line**

Add this test to `ShortTermTechnicalSignalEvaluatorTest`:

```java
@Test
void comparesSnapshotVolumeWithThreeCompletedBarsWhenCurrentDailyBarIsAbsent() {
    List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600208"));
    int size = rows.size();
    replaceVolume(rows, size - 4, "50000");
    replaceVolume(rows, size - 3, "100000");
    replaceVolume(rows, size - 2, "200000");
    replaceVolume(rows, size - 1, "300000");
    LocalDate scanTradeDate = rows.get(size - 1).tradeDate().plusDays(1);

    ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
            rows,
            rows.get(size - 1).close(),
            scanTradeDate,
            new BigDecimal("150000"),
            false,
            ruleSet
    );

    assertThat(result.snapshot().todayVolume()).isEqualByComparingTo("150000.00");
    assertThat(result.snapshot().averageVolume3()).isEqualByComparingTo("200000.00");
    assertThat(result.snapshot().volumeRatio3()).isEqualByComparingTo("0.75");
}
```

- [ ] **Step 2: Run the single evaluator test and verify the second red state**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermTechnicalSignalEvaluatorTest#comparesSnapshotVolumeWithThreeCompletedBarsWhenCurrentDailyBarIsAbsent test
```

Expected: FAIL because the first implementation blindly excludes the latest K-line and averages `50000`, `100000`, and `200000` instead of the three bars before `scanTradeDate`.

- [ ] **Step 3: Select history strictly by the scan trade date**

Change the call to:

```java
VolumeComparison comparison = threeDayVolumeComparison(
        evaluation.rows(),
        evaluationTradeDate,
        evaluationVolume
);
```

Replace the two-argument scan-snapshot helper from Task 1 with:

```java
private VolumeComparison threeDayVolumeComparison(
        List<EastMoneyKLine> rows,
        LocalDate evaluationTradeDate,
        BigDecimal evaluationVolume
) {
    if (rows == null || evaluationTradeDate == null || !positive(evaluationVolume)) {
        return VolumeComparison.unavailable();
    }
    List<EastMoneyKLine> completedRows = rows.stream()
            .filter(row -> row != null && row.tradeDate() != null)
            .filter(row -> row.tradeDate().isBefore(evaluationTradeDate))
            .toList();
    if (completedRows.size() < 3) {
        return VolumeComparison.unavailable();
    }
    List<BigDecimal> previousVolumes = completedRows
            .subList(completedRows.size() - 3, completedRows.size())
            .stream()
            .map(EastMoneyKLine::volume)
            .toList();
    if (previousVolumes.stream().anyMatch(value -> !positive(value))) {
        return VolumeComparison.unavailable();
    }
    BigDecimal averageVolume3 = previousVolumes.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    BigDecimal volumeRatio3 = evaluationVolume.divide(
            averageVolume3, 6, RoundingMode.HALF_UP);
    return new VolumeComparison(evaluationVolume, averageVolume3, volumeRatio3);
}
```

- [ ] **Step 4: Verify both focused backend tests are green**

Run:

```bash
mvn -pl apps/api \
  -Dtest=ShortTermTechnicalSignalEvaluatorTest,ShortTermServiceTest \
  test
```

Expected: both classes pass with zero failures and zero errors.

- [ ] **Step 5: Verify no three-day ratio was introduced into decision logic**

Run:

```bash
rg -n "volumeRatio3|averageVolume3|todayVolume" \
  apps/api/src/main/java/com/aistock/research/shortterm
```

Expected: the fields appear only in the snapshot/evaluator data path and do not appear in `technicalHardExclusion`, scoring, ranking, `decide`, or advice logic.

---

### Task 3: Regression, Build, and Local Docker Runtime Verification

**Files:**
- Verify: `apps/api`
- Verify: `apps/web-react`
- Rebuild: `docker-compose.yml` services `api` and `web`

**Interfaces:**
- Consumes: the completed backend implementation and existing frontend display contract.
- Produces: fresh host artifacts and healthy local containers running those exact artifacts.

- [ ] **Step 1: Run complete backend tests**

Run:

```bash
mvn -pl apps/api test
```

Expected: Maven exits `0` with zero failures and zero errors.

- [ ] **Step 2: Run frontend tests and production build**

Run:

```bash
cd apps/web-react
npm test -- --run
npm run build
```

Expected: Vitest and Vite both exit `0`; the unchanged row formatter continues rendering ratios below `1.00` as concrete values.

- [ ] **Step 3: Check the working diff without staging unrelated changes**

Run:

```bash
git diff --check
git diff -- \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluator.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluatorTest.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java
```

Expected: no whitespace errors; the reviewed diff contains the snapshot-volume change plus pre-existing unrelated working-tree hunks, none staged wholesale.

- [ ] **Step 4: Build fresh deployable artifacts and images**

Run:

```bash
mvn -pl apps/api -DskipTests package
cd apps/web-react
npm run build
cd ../..
docker compose build api web
```

Expected: all commands exit `0` and both local images receive fresh image IDs.

- [ ] **Step 5: Recreate only API and Web while preserving volumes**

Run:

```bash
docker compose up -d --force-recreate api web
```

Expected: `api-data` remains attached; neither `docker compose down -v` nor any volume deletion command is used.

- [ ] **Step 6: Verify the running containers and deployed artifact**

Run:

```bash
docker compose ps
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:5176/healthz
```

Expected: both containers are `healthy`, API returns `{"status":"UP"}`, and Web returns `ok`.

Compare the host API JAR SHA-256 with `/app/app.jar` in the running API container, verify restart counts remain `0`, and confirm the runtime web asset still contains `今日量 / 前3日均`.

Do not commit the implementation files automatically because the working tree already contains unrelated user changes in overlapping files. Hand back the exact diff and verification evidence for review.
