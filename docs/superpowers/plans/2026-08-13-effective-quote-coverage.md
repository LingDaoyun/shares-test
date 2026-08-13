# Effective Quote Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make manual short-term scans pass the unchanged 95% market-data gate when the complete live universe is present, while continuing to block raw page loss, stale or future data, and genuine effective coverage below 95%.

**Architecture:** Fetch the EastMoney raw universe with stable security-code pagination, then keep raw transport completeness separate from effective current-quote coverage. Extend `ShortTermCoverageSnapshot` with additive raw-audit fields, compute the effective denominator by subtracting only fetched rows with no usable current price, and make the final-result gate return distinct reason codes for low effective coverage, incomplete raw acquisition, and other reliability failures.

**Tech Stack:** Java 17, Spring Boot 3.3, Jackson, JUnit 5, AssertJ, Mockito, Maven

## Global Constraints

- Keep the V4 effective coverage threshold exactly `0.95`; do not lower or bypass it.
- Full-universe acquisition remains fail-closed: a missing or duplicate raw row cannot be treated as an excluded no-price security.
- Change EastMoney quote-list sorting from `fid=f6` to `fid=f12` while retaining the existing A-share filter and 100-row maximum page size.
- Exclude only fetched rows that fail the existing usable-price rule from the effective denominator.
- Usable-price rows with invalid A-share context or invalid point-in-time timestamps remain effective coverage misses.
- Keep candidate ranking, strategy thresholds, K-line review, polling, scheduled control, database persistence, and model configuration unchanged.
- Add `rawExpectedCount`, `rawFetchedCount`, `excludedNoPriceCount`, and `rawComplete` to the API response without a database migration.
- Return `COVERAGE_BELOW_95` only for an absent or genuinely sub-95% effective ratio, `QUOTE_UNIVERSE_INCOMPLETE` for raw page loss, and `QUOTE_COVERAGE_UNRELIABLE` for remaining reliability failures.
- Do not invoke an external model during implementation or acceptance.

---

### Task 1: Make Raw Quote Pagination Stable and Complete

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java:365-680`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java:36-260`

**Interfaces:**
- Consumes: `LiveDataProperties.eastmoneyQuoteUrl()`, `A_SHARE_FILTER`, `QUOTE_FIELDS`, `AshareQuotePage`, and the existing unique-symbol merge.
- Produces: package-private `String ashareQuotePageUrl(int pageNumber, int pageSize)` and stable full-snapshot pagination using `fid=f12`.

- [ ] **Step 1: Write failing URL and duplicate-middle-page tests**

Add these tests to `EastMoneyClientTest`:

```java
@Test
void quotePageUrlUsesStableSecurityCodeSort() {
    String url = client.ashareQuotePageUrl(2, 100);

    assertThat(url)
            .contains("pn=2", "pz=100", "po=1", "fid=f12")
            .doesNotContain("fid=f6");
}

@Test
void snapshotContinuesPastADuplicatePageToCollectLaterUniqueRows() {
    DuplicateMiddlePageStubClient snapshotClient = new DuplicateMiddlePageStubClient();

    AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(3);

    assertThat(snapshot.complete()).isTrue();
    assertThat(snapshot.expectedCount()).isEqualTo(3);
    assertThat(snapshot.fetchedCount()).isEqualTo(3);
    assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
            .containsExactly("600001", "600002", "600003");
    assertThat(snapshotClient.requestedPages).isEqualTo(3);
}
```

Add this nested fixture beside the existing snapshot stubs:

```java
private static final class DuplicateMiddlePageStubClient extends EastMoneyClient {
    private int requestedPages;

    private DuplicateMiddlePageStubClient() {
        super(null, new ObjectMapper(), null);
    }

    @Override
    AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
        requestedPages = Math.max(requestedPages, pageNumber);
        return switch (pageNumber) {
            case 1 -> new AshareQuotePage(3, List.of(
                    SnapshotStubClient.quote("600001", "工业", "东方财富实时全市场")));
            case 2 -> new AshareQuotePage(3, List.of(
                    SnapshotStubClient.quote("600001", "工业", "东方财富实时全市场")));
            case 3 -> new AshareQuotePage(3, List.of(
                    SnapshotStubClient.quote("600002", "工业", "东方财富实时全市场"),
                    SnapshotStubClient.quote("600003", "工业", "东方财富实时全市场")));
            default -> new AshareQuotePage(3, List.of());
        };
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
mvn -pl apps/api -Dtest=EastMoneyClientTest test
```

Expected: FAIL because `ashareQuotePageUrl` does not exist and the current paginator stops after the duplicate second page.

- [ ] **Step 3: Implement deterministic URL construction and duplicate-safe continuation**

Add and use this method in `EastMoneyClient`:

```java
String ashareQuotePageUrl(int pageNumber, int pageSize) {
    int safePageNumber = Math.max(1, pageNumber);
    int safePageSize = Math.max(1, Math.min(MAX_QUOTE_PAGE_SIZE, pageSize));
    return properties.eastmoneyQuoteUrl()
            + "?pn=" + safePageNumber
            + "&pz=" + safePageSize
            + "&po=1"
            + "&fid=f12"
            + "&fs=" + A_SHARE_FILTER
            + "&fields=" + QUOTE_FIELDS;
}
```

`fetchAshareQuotePage` must call `ashareQuotePageUrl(pageNumber, pageSize)`. In `fetchAshareQuoteSnapshot`, retain `putIfAbsent` but remove the early stop based on `merged.size() == beforeMergeCount` and remove the unused `beforeMergeCount` variable. The existing completion, empty-page, request-failure, and 160-page stops remain.

- [ ] **Step 4: Run the focused test and verify GREEN**

```bash
mvn -pl apps/api -Dtest=EastMoneyClientTest test
```

Expected: all `EastMoneyClientTest` tests pass.

- [ ] **Step 5: Commit stable raw pagination**

```bash
git add apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java \
  apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java
git commit -m "fix: stabilize full-market quote pagination"
```

### Task 2: Separate Raw Acquisition from Effective Quote Coverage

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java:145-455`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoverageSnapshot.java:1-28`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java:340-380,586-610,1493-1547`

**Interfaces:**
- Consumes: Task 1's raw `AshareQuoteSnapshot`, `hasUsablePrice(EastMoneyQuote)`, A-share context filtering, and point-in-time filtering.
- Produces: effective `expectedCount/fetchedCount/missingCount/coverageRatio` plus raw audit accessors `rawExpectedCount()`, `rawFetchedCount()`, `excludedNoPriceCount()`, and `rawComplete()`.

- [ ] **Step 1: Write failing effective-denominator regressions**

Replace `invalidQuotesNeverCountTowardTheNinetyFivePercentCoverageGate` with:

```java
@Test
void completeNoPriceRowsAreAuditedButExcludedFromEffectiveCoverageDenominator() {
    List<EastMoneyQuote> valid = IntStream.range(0, 94)
            .mapToObj(index -> quote(String.format("600%03d", index), "有效样本" + index,
                    "10.62", "1.20", "18", "1.60", "600000000"))
            .toList();
    List<EastMoneyQuote> noPrice = IntStream.range(94, 100)
            .mapToObj(index -> quote(String.format("600%03d", index), "无价格样本" + index,
                    "0", "1.20", "18", "1.60", "600000000"))
            .toList();
    eastMoneyClient.quotes = java.util.stream.Stream.concat(valid.stream(), noPrice.stream()).toList();
    eastMoneyClient.snapshotExpectedCount = 100;

    ShortTermReport report = service.report(new ShortTermScanRequest(
            3, 6000, 10, null, null, null, null, null, null, null, null, null));

    assertThat(report.coverage().rawExpectedCount()).isEqualTo(100);
    assertThat(report.coverage().rawFetchedCount()).isEqualTo(100);
    assertThat(report.coverage().excludedNoPriceCount()).isEqualTo(6);
    assertThat(report.coverage().rawComplete()).isTrue();
    assertThat(report.coverage().expectedCount()).isEqualTo(94);
    assertThat(report.coverage().fetchedCount()).isEqualTo(94);
    assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("1.0000");
    assertThat(report.coverage().executionReliable()).isTrue();
    assertThat(report.quoteNote()).contains(
            "有效行情覆盖 94/94", "行情源原始抓取 100/100", "无有效现价排除 6");
}
```

Add:

```java
@Test
void missingRawRowsRemainInEffectiveDenominatorAndKeepCoverageUnreliable() {
    List<EastMoneyQuote> valid = IntStream.range(0, 99)
            .mapToObj(index -> quote(String.format("600%03d", index), "有效样本" + index,
                    "10.62", "1.20", "18", "1.60", "600000000"))
            .toList();
    List<EastMoneyQuote> noPrice = IntStream.range(0, 5)
            .mapToObj(index -> quote(String.format("601%03d", index), "无价格样本" + index,
                    "0", "1.20", "18", "1.60", "600000000"))
            .toList();
    eastMoneyClient.quotes = java.util.stream.Stream.concat(valid.stream(), noPrice.stream()).toList();
    eastMoneyClient.snapshotExpectedCount = 105;
    eastMoneyClient.snapshotComplete = false;

    ShortTermReport report = service.report(new ShortTermScanRequest(
            3, 6000, 10, null, null, null, null, null, null, null, null, null));

    assertThat(report.coverage().rawExpectedCount()).isEqualTo(105);
    assertThat(report.coverage().rawFetchedCount()).isEqualTo(104);
    assertThat(report.coverage().excludedNoPriceCount()).isEqualTo(5);
    assertThat(report.coverage().rawComplete()).isFalse();
    assertThat(report.coverage().expectedCount()).isEqualTo(100);
    assertThat(report.coverage().fetchedCount()).isEqualTo(99);
    assertThat(report.coverage().coverageRatio()).isEqualByComparingTo("0.9900");
    assertThat(report.coverage().executionReliable()).isFalse();
}
```

Change `shouldExposeCoverageAndKeepAbsentCompatibilityCoverageUnreliable` so an incomplete requested universe expects `executionReliable()` to be false.

- [ ] **Step 2: Run the focused service tests and verify RED**

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest test
```

Expected: FAIL because the raw audit accessors do not exist and zero-price rows remain in the effective denominator.

- [ ] **Step 3: Extend the coverage record with a compatibility constructor**

Use this record shape in `ShortTermCoverageSnapshot`:

```java
public record ShortTermCoverageSnapshot(
        int expectedCount,
        int fetchedCount,
        int missingCount,
        BigDecimal coverageRatio,
        boolean executionReliable,
        String source,
        Instant fetchedAt,
        int rawExpectedCount,
        int rawFetchedCount,
        int excludedNoPriceCount,
        boolean rawComplete
) {
    public ShortTermCoverageSnapshot(
            int expectedCount, int fetchedCount, int missingCount,
            BigDecimal coverageRatio, boolean executionReliable,
            String source, Instant fetchedAt
    ) {
        this(expectedCount, fetchedCount, missingCount, coverageRatio,
                executionReliable, source, fetchedAt,
                expectedCount, executionReliable ? expectedCount : fetchedCount,
                0, executionReliable);
    }

    public static ShortTermCoverageSnapshot unreliable() {
        return new ShortTermCoverageSnapshot(
                0, 0, 0, BigDecimal.ZERO, false, "未知", null,
                0, 0, 0, false);
    }
}
```

The seven-argument constructor preserves Java source compatibility. Jackson uses the canonical constructor, so old stored JSON without audit fields defaults to zero/false and remains fail-closed.

- [ ] **Step 4: Compute effective and raw counts independently**

Replace the count portion of `coverageSnapshot` with:

```java
List<EastMoneyQuote> safeUniqueQuotes = uniqueMarketQuotes == null ? List.of() : uniqueMarketQuotes;
int rawExpectedCount = Math.max(0, snapshot.expectedCount());
int rawFetchedCount = safeUniqueQuotes.size();
List<EastMoneyQuote> usableRawQuotes = safeUniqueQuotes.stream()
        .filter(this::hasUsablePrice)
        .toList();
int excludedNoPriceCount = rawFetchedCount - usableRawQuotes.size();
int effectiveExpectedCount = Math.max(0, rawExpectedCount - excludedNoPriceCount);
int effectiveFetchedCount = eligibleQuotes == null ? 0 : eligibleQuotes.size();
int effectiveMissingCount = Math.max(0, effectiveExpectedCount - effectiveFetchedCount);
BigDecimal ratio = effectiveExpectedCount == 0
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(effectiveFetchedCount)
        .divide(BigDecimal.valueOf(effectiveExpectedCount), 4, RoundingMode.HALF_UP);
```

Keep the existing source, freshness, and point-in-time checks. Replace count consistency and the final completeness predicate with:

```java
boolean requestedFullReportedUniverse = rawExpectedCount > 0
        && snapshot.requestedCount() >= rawExpectedCount;
boolean countsConsistent = snapshot.fetchedCount() == rawFetchedCount
        && rawFetchedCount <= rawExpectedCount
        && excludedNoPriceCount <= rawFetchedCount
        && effectiveFetchedCount <= effectiveExpectedCount;
boolean allQuotesRespectDecisionAt = usableRawQuotes.stream()
        .allMatch(quote -> quoteAvailableAtDecision(
                quote, decisionAt, allowClosedMarketCachePreview));
boolean rawCompletenessRequired = requireCompleteUniverse || requestedFullReportedUniverse;
boolean reliable = ratio.compareTo(MIN_RELIABLE_MARKET_COVERAGE) >= 0
        && validSource
        && freshSnapshot
        && requestedFullReportedUniverse
        && countsConsistent
        && allQuotesRespectDecisionAt
        && (!rawCompletenessRequired || snapshot.complete());
```

Return all fields explicitly:

```java
return new ShortTermCoverageSnapshot(
        effectiveExpectedCount, effectiveFetchedCount, effectiveMissingCount,
        ratio, reliable, validSource ? snapshot.source() : "未知", snapshot.fetchedAt(),
        rawExpectedCount, rawFetchedCount, excludedNoPriceCount, snapshot.complete());
```

The absent-total branch must also use the canonical constructor and set `rawComplete=false`.

- [ ] **Step 5: Make the report note disclose both measures**

Build these fragments in `quoteNote`:

```java
String effectiveCoverageText = coverage.fetchedCount() + "/" + coverage.expectedCount();
String rawCoverageText = coverage.rawFetchedCount() + "/" + coverage.rawExpectedCount();
String acquisitionText = " 行情源原始抓取 " + rawCoverageText
        + (coverage.rawComplete() ? "，原始页完整" : "，原始页不完整")
        + "；无有效现价排除 " + coverage.excludedNoPriceCount() + " 只。";
```

The reliable branch starts with `本轮有效行情覆盖 <effectiveCoverageText>` and appends `acquisitionText`. The unreliable branch starts with `本轮有效行情覆盖不足 <effectiveCoverageText>，缺失 <missingCount> 只；`, appends `acquisitionText`, and retains the existing execution-closed and technical-review explanations.

- [ ] **Step 6: Run the focused service tests and verify GREEN**

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest test
```

Expected: all `ShortTermServiceTest` tests pass.

- [ ] **Step 7: Commit the dual coverage model**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoverageSnapshot.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java
git commit -m "fix: separate effective and raw quote coverage"
```

### Task 3: Return Precise Final-Result Blocking Reasons

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermManualResultGateTest.java:150-330`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java:100-180`

**Interfaces:**
- Consumes: Task 2's effective counts/ratio and raw audit fields.
- Produces: deterministic `COVERAGE_BELOW_95`, `QUOTE_UNIVERSE_INCOMPLETE`, and `QUOTE_COVERAGE_UNRELIABLE` results.

- [ ] **Step 1: Write failing reason-code and message tests**

Extend the existing below-95% test with:

```java
assertThat(result.message()).isEqualTo("全市场有效行情覆盖率 94.99%（5450/5500）低于95%");
```

Add:

```java
@Test
void blocksIncompleteRawUniverseWithItsOwnReasonWhenEffectiveRatioPasses() {
    ShortTermReport report = report(Instant.parse("2026-07-23T06:51:00Z"), true,
            new BigDecimal("0.9900"), false, List.of());
    when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
            100, 99, 1, new BigDecimal("0.9900"), false,
            "东方财富行情", Instant.parse("2026-07-23T06:51:00Z"),
            105, 104, 5, false));

    ShortTermFinalResultGate.Result result = gate.evaluateManual(report, DECISION_AT);

    assertThat(result.blockedReasons()).containsExactly("QUOTE_UNIVERSE_INCOMPLETE");
    assertThat(result.message()).isEqualTo("全市场原始行情抓取不完整（104/105）");
}

@Test
void blocksOtherReliabilityFailureWithoutMislabelingItBelowNinetyFive() {
    ShortTermReport report = report(Instant.parse("2026-07-23T06:51:00Z"), true,
            new BigDecimal("0.9900"), false, List.of());
    when(report.coverage()).thenReturn(new ShortTermCoverageSnapshot(
            100, 99, 1, new BigDecimal("0.9900"), false,
            "东方财富行情", Instant.parse("2026-07-23T06:51:00Z"),
            105, 105, 5, true));

    ShortTermFinalResultGate.Result result = gate.evaluateManual(report, DECISION_AT);

    assertThat(result.blockedReasons()).containsExactly("QUOTE_COVERAGE_UNRELIABLE");
    assertThat(result.message()).isEqualTo("全市场行情覆盖未通过数据来源、新鲜度或点时一致性校验");
}
```

- [ ] **Step 2: Run the focused gate tests and verify RED**

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest test
```

Expected: FAIL because the gate currently maps every coverage reliability problem to `COVERAGE_BELOW_95`.

- [ ] **Step 3: Split validation branches and format the effective ratio**

Replace the combined coverage branch with:

```java
ShortTermCoverageSnapshot coverage = report.coverage();
if (coverage == null || coverage.coverageRatio() == null
        || coverage.coverageRatio().compareTo(MINIMUM_COVERAGE) < 0) {
    return Optional.of(new Failure("COVERAGE_BELOW_95", coverageBelowMessage(coverage)));
}
if (!coverage.rawComplete()) {
    return Optional.of(new Failure("QUOTE_UNIVERSE_INCOMPLETE",
            "全市场原始行情抓取不完整（" + coverage.rawFetchedCount()
                    + "/" + coverage.rawExpectedCount() + "）"));
}
if (!coverage.executionReliable()) {
    return Optional.of(new Failure("QUOTE_COVERAGE_UNRELIABLE",
            "全市场行情覆盖未通过数据来源、新鲜度或点时一致性校验"));
}
```

Add:

```java
private String coverageBelowMessage(ShortTermCoverageSnapshot coverage) {
    if (coverage == null || coverage.coverageRatio() == null) {
        return "全市场有效行情覆盖率缺失，无法通过95%门槛";
    }
    String percentage = coverage.coverageRatio()
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toPlainString();
    return "全市场有效行情覆盖率 " + percentage + "%（"
            + coverage.fetchedCount() + "/" + coverage.expectedCount() + "）低于95%";
}
```

Keep the exact-95% comparison and later cutoff/freshness failures unchanged.

- [ ] **Step 4: Run focused gate and service tests and verify GREEN**

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest,ShortTermServiceTest test
```

Expected: both classes pass and the exact `0.9500` boundary is accepted.

- [ ] **Step 5: Commit precise blocking reasons**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java \
  apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermManualResultGateTest.java
git commit -m "fix: distinguish quote coverage blocking reasons"
```

### Task 4: Verify the Complete Backend Contract

**Files:**
- Verify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoverageSnapshot.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: a backend commit set ready for integrated deployment and live manual-scan acceptance.

- [ ] **Step 1: Run the complete API suite**

```bash
mvn -pl apps/api test
```

Expected: the complete API test suite exits 0 with no failures or errors.

- [ ] **Step 2: Run build and patch integrity checks**

```bash
mvn -pl apps/api -DskipTests package
git diff --check
git status --short --branch
```

Expected: packaging exits 0, `git diff --check` is silent, and only explicitly planned uncommitted files remain.

- [ ] **Step 3: Inspect the contract before handoff**

```bash
rg -n "fid=f12|rawExpectedCount|excludedNoPriceCount|QUOTE_UNIVERSE_INCOMPLETE|QUOTE_COVERAGE_UNRELIABLE" \
  apps/api/src/main/java apps/api/src/test/java
```

Expected: stable pagination, additive audit fields, and all three failure classifications occur in production code and regression tests.
