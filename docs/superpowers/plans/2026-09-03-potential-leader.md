# Potential Leader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the standalone first-board alert with an auditable dual-lane “潜力龙头” module whose deterministic ranking is authoritative while AI review and historical calibration remain shadow-only.

**Architecture:** Reuse the persisted first-board daily-bar/baseline infrastructure through a small public façade, and reuse the short-term limit-up board snapshot after adding normalized broken-board rows. A new `leader` domain computes objective industry directions, selects and scores `POTENTIAL`/`COMPETITION` candidates, enriches competition candidates with a soft Dragon-Tiger adjustment, persists one close snapshot per day, and exposes one React-facing API. PostgreSQL JSONB stores immutable close facts; AI and outcome services read those facts but cannot write into deterministic scores.

**Tech Stack:** Java 17, Spring Boot 3.3.5, `RestClient`, Jackson, `JdbcTemplate`, Flyway/PostgreSQL JSONB, JUnit 5/AssertJ/Mockito, React 18, TypeScript 5.6, Axios, Vitest/jsdom, Tailwind utility classes.

## Global Constraints

- Formal rule version is exactly `potential-leader-v1`.
- Formal lanes are exactly `POTENTIAL` and `COMPETITION`; they are ranked independently and each returns at most 10 candidates with score `>= 55`.
- Grades are `A` for `>=80`, `B` for `>=65 && <80`, and `C` for `>=55 && <65`.
- Dragon-Tiger evidence adjusts only `COMPETITION`, is clamped to `[-5, 10]`, and missing/failed disclosure contributes exactly `0`.
- AI verdicts and historical calibration never mutate score, grade, rank, qualification, notification eligibility, or the deterministic snapshot hash.
- Do not infer a named hot-money operator from a brokerage branch. Use “营业部席位” and distinguish institution/Stock Connect rows.
- Keep the embedded “短线右侧” limit-up board behavior and endpoint intact.
- Keep `first_board_daily_bar`, `first_board_market_day`, `first_board_baseline_run`, `first_board_baseline_item`, ingestion, baseline scheduling, and retention intact.
- Current migration head is V3; add exactly `V4__potential_leader_shadow_learning.sql` with three new tables.
- Do not add auto-trading, position advice, external push, online learning, black-box modeling, or a subjective hot-money mapping.
- Add focused test code before implementation code, but do not run or delegate tests, Maven/JUnit, builds, compilation, lint/type checks, static/diff validation, browser/E2E, API/health/log/database checks, startup/restart, or deployment. User-only commands below are documentation, not authorization to run them.
- Every implementation handoff must say `未由 Codex 测试`.

---

## File Structure

### Existing infrastructure adapted, not replaced

- `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java`: add circulating market value without breaking existing constructor call sites.
- `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`: request and parse EastMoney `f21`.
- `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardSnapshot.java`: expose normalized broken-board rows and their availability.
- `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardService.java`: populate those rows from the same cached board fetch already used by “短线右侧”.
- `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java`: expose a read-only leader façade and public recent-bar method.

### New backend leader domain

- `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderSnapshot.java`: complete JSON contract, nested enums and immutable DTOs.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderRuleSet.java`: version, thresholds, clamping, percentile and linear score helpers.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderDirectionEngine.java`: objective industry aggregation and top-five strong-direction calculation.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderCandidateEngine.java`: common eligibility, board selection, touched-limit split, and pool deduplication.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderScoreEngine.java`: both official scorecards, deductions, grades, stable ranking and snapshot hashes.
- `apps/api/src/main/java/com/aistock/research/leader/DragonTigerGateway.java`: batch disclosure interface and failure-safe result.
- `apps/api/src/main/java/com/aistock/research/leader/EastMoneyDragonTigerGateway.java`: five-trading-day EastMoney report fetch, parse, branch dedup and soft adjustment.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderSnapshotStore.java`: JSONB close snapshots, pending outcomes and cohort aggregate queries; Task 6 adds AI-review persistence after its type exists.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderLearningService.java`: idempotent close capture, future-session labels and Wilson calibration.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderAiReview.java`: shadow AI response contract.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderAiReviewService.java`: canonical candidate reload, hash precondition, structured LLM prompt and persistence.
- `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderService.java`: 60-second keyed snapshot orchestration, stale semantics, bars and canonical candidate lookup.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderBarSeries.java`: public 20-day QFQ chart response.
- `apps/api/src/main/java/com/aistock/research/leader/LeaderLearningScheduler.java`: close capture retry and outcome maturation triggers after snapshot orchestration exists.
- `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderController.java`: the three `/api/potential-leaders` endpoints.
- `apps/api/src/main/resources/db/migration/V4__potential_leader_shadow_learning.sql`: exactly three new tables and indexes.

### Backend files retired after replacement

- Delete `FirstBoardAlertService.java`, `FirstBoardAlertController.java`, `FirstBoardAlertCandidate.java`, `FirstBoardAlertCoverage.java`, `FirstBoardAlertLane.java`, `FirstBoardAlertLevel.java`, and `FirstBoardAlertSnapshot.java`.
- Keep `FirstBoardAlertStatus.java` temporarily because the persisted baseline access layer still uses it internally.
- Replace `FirstBoardAlertServiceTest.java` with leader-domain tests; retain baseline, ingestion, retention and technical-evaluator tests.

### New frontend module

- `apps/web-react/src/pages/PotentialLeaderPage.tsx`: page state, polling, refresh, board switches and detail selection.
- `apps/web-react/src/components/leader/LeaderMarketOverview.tsx`: status, coverage, sentiment and strongest-five direction summary.
- `apps/web-react/src/components/leader/LeaderCandidateTable.tsx`: lane-specific columns and empty semantics.
- `apps/web-react/src/components/leader/LeaderDetailOverlay.tsx`: score evidence, risks, seats, calibration, chart and manual AI review.
- `apps/web-react/src/components/leader/LeaderCandlestickChart.tsx`: move the existing chart implementation under the new domain name.
- `apps/web-react/src/lib/potentialLeaderPreferences.ts`: Growth/STAR local preferences.
- `apps/web-react/src/lib/potentialLeaderNotifications.ts`: new-A and upgrade-to-A same-day deduplication.
- Modify `types.ts`, `api/client.ts`, `App.tsx`, `pageMeta.ts`, `NavRail.tsx` and their focused tests.
- Delete the old first-board page, page test, preferences, notifications and old chart after replacements import the new files.

---

### Task 1: Adapt Shared Quote, Board, and Baseline Contracts

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermBrokenBoardStock.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardService.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardLeaderBaselineAccess.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardLeaderBaselineStock.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java`
- Test: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermLimitUpBoardServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServiceTest.java`

**Interfaces:**
- Produces: `EastMoneyQuote.circulatingMarketValue()`.
- Produces: `ShortTermLimitUpBoardSnapshot.brokenBoardAvailable()` and `brokenStocks()` from the same cached fetch as `stocks()`.
- Produces: `FirstBoardBaselineService.leaderAccess(LocalDate)` and public `recentBars(LocalDate,String)`.

- [ ] **Step 1: Add focused contract tests before implementation**

Add an `EastMoneyClientTest` case whose JSON contains `"f21":8800000000` and assert:

```java
EastMoneyQuote quote = client.readQuote(node, Instant.parse("2026-09-03T02:00:00Z")).orElseThrow();
assertThat(quote.circulatingMarketValue()).isEqualByComparingTo("8800000000");
```

Extend `ShortTermLimitUpBoardServiceTest.buildSnapshotOrdersStocksByBoardDepthThenEarliestSealTime` with one `EastMoneyBrokenBoardPoolEntry`, then assert:

```java
assertThat(snapshot.brokenBoardAvailable()).isTrue();
assertThat(snapshot.brokenStocks()).singleElement().satisfies(stock -> {
    assertThat(stock.symbol()).isEqualTo("600099");
    assertThat(stock.currentlySealed()).isFalse();
    assertThat(stock.sealBreakCount()).isEqualTo(2);
});
```

Add a baseline façade test that stubs a READY internal snapshot and asserts the public view contains the stock, 60-bar-qualified metrics, baseline date, publication time and progress/data-gap semantics without exposing mutable maps.

- [ ] **Step 2: Add circulating market value compatibly**

Append `BigDecimal circulatingMarketValue` to the canonical `EastMoneyQuote` record. Keep every existing convenience constructor signature and delegate with a final `null`; add one new full constructor used by `EastMoneyClient.readQuote`. Change:

```java
private static final String QUOTE_FIELDS =
        "f2,f3,f5,f6,f8,f9,f10,f12,f13,f14,f15,f16,f17,f20,f21,f23,f60,f100,f115,f124";
```

Parse `f21` with `positiveOrNull(decimal(item, "f21"))`. Tencent fallback sets this field to `null`; formal leader ranking later requires a complete EastMoney full-market snapshot and a non-null value.

- [ ] **Step 3: Expose broken boards without changing the embedded board UI**

Create:

```java
public record ShortTermBrokenBoardStock(
        String symbol,
        String name,
        String industry,
        BigDecimal latestPrice,
        BigDecimal limitUpPrice,
        BigDecimal changePercent,
        BigDecimal amount,
        LocalTime firstSealTime,
        int sealBreakCount,
        boolean currentlySealed
) {}
```

Add `boolean brokenBoardAvailable` and `List<ShortTermBrokenBoardStock> brokenStocks` to `ShortTermLimitUpBoardSnapshot`. In `fetchSnapshot`, set availability false only when the broken-pool fetch throws; an empty successful list remains available. In `buildSnapshot`, map and sort broken rows by first seal time then symbol. Preserve existing `available` semantics: the limit-up pool remains the primary availability gate.

- [ ] **Step 4: Add a read-only baseline façade**

Create:

```java
public record FirstBoardLeaderBaselineStock(
        String symbol,
        String name,
        String industry,
        FirstBoardBoard board,
        LocalDate baselineDate,
        BigDecimal previousClose,
        BigDecimal ma10,
        BigDecimal ma20,
        BigDecimal ma20Slope,
        BigDecimal rsi14,
        BigDecimal macdHistogram,
        int technicalPassCount,
        BigDecimal previousDayVolumeRatio,
        BigDecimal recentFiveMaxVolumeRatio,
        BigDecimal upperShadowRatio,
        BigDecimal threeDayCloseRange,
        boolean previousLimitUp,
        List<FirstBoardBar> recentBars
) {
    public FirstBoardLeaderBaselineStock {
        recentBars = recentBars == null ? List.of() : List.copyOf(recentBars);
    }
}
```

Also create `FirstBoardLeaderBaselineAccess` with:

```java
public record FirstBoardLeaderBaselineAccess(
        FirstBoardAlertStatus status,
        LocalDate tradeDate,
        LocalDate baselineDate,
        Instant publishedAt,
        Map<String, FirstBoardLeaderBaselineStock> stocks,
        FirstBoardIngestionProgress progress,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount,
        List<String> dataGaps
) {
    public FirstBoardLeaderBaselineAccess {
        stocks = stocks == null ? Map.of() : Map.copyOf(stocks);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
```

`leaderAccess` calls the existing package-private `access`, maps READY records, and returns empty stock maps for preparation/failure. Make the existing `recentBars` method public; do not expose write stores or change ingestion/build behavior.

- [ ] **Step 5: Record the user-only verification commands and commit**

Do not run these commands. User-only commands:

```bash
mvn -pl apps/api -Dtest=EastMoneyClientTest,ShortTermLimitUpBoardServiceTest,FirstBoardBaselineServiceTest test
```

Commit only this task’s files:

```bash
git add apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java \
  apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermBrokenBoardStock.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardSnapshot.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermLimitUpBoardService.java \
  apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardLeaderBaselineAccess.java \
  apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardLeaderBaselineStock.java \
  apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java \
  apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermLimitUpBoardServiceTest.java \
  apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServiceTest.java
git commit -m "feat: expose leader market inputs"
```

### Task 2: Build the Deterministic Direction, Candidate, and Score Engines

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderRuleSet.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderDirectionEngine.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderCandidateEngine.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderScoreEngine.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderDirectionEngineTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderCandidateEngineTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderScoreEngineTest.java`

**Interfaces:**
- Consumes: full-market quotes, public baseline stock views, normalized sealed/broken board rows.
- Produces: immutable directions, scorable lane inputs, official candidates and stable hashes.

- [ ] **Step 1: Write boundary and isolation tests first**

Cover these exact cases:

```java
assertThat(directionEngine.rank(quotes, sealed, broken))
        .first().extracting(PotentialLeaderSnapshot.Direction::industry).isEqualTo("半导体");
assertThat(directionEngine.rank(fourMemberIndustry, sealed, broken)).isEmpty();

assertThat(candidateEngine.select(inputWithHighBelowLimit)).extracting("potential").asList().hasSize(1);
assertThat(candidateEngine.select(inputWhoseHighEqualsLimit)).extracting("potential").asList().isEmpty();
assertThat(candidateEngine.select(sameSymbolInSealedAndBroken)).extracting("competition").asList().hasSize(1);

assertThat(scoreEngine.grade(new BigDecimal("80.00"))).isEqualTo(Grade.A);
assertThat(scoreEngine.grade(new BigDecimal("79.99"))).isEqualTo(Grade.B);
assertThat(scoreEngine.grade(new BigDecimal("65.00"))).isEqualTo(Grade.B);
assertThat(scoreEngine.grade(new BigDecimal("64.99"))).isEqualTo(Grade.C);
assertThat(scoreEngine.grade(new BigDecimal("54.99"))).isNull();
assertThat(scoreEngine.capRisk(new BigDecimal("37"))).isEqualByComparingTo("30");
```

Also prove that a Dragon-Tiger adjustment passed to a `POTENTIAL` input is ignored, while `COMPETITION` clamps `-9` to `-5` and `17` to `10`.

- [ ] **Step 2: Define the complete outward snapshot contract**

Use one public record with nested public records/enums so Jackson and TypeScript receive one coherent contract:

```java
public record PotentialLeaderSnapshot(
        Status status,
        LocalDate tradeDate,
        LocalDate quoteTradeDate,
        Instant quoteTimestamp,
        LocalDate dragonTigerDate,
        Instant generatedAt,
        boolean autoRefreshAllowed,
        String ruleVersion,
        BoardOptions boardOptions,
        MarketSummary marketSummary,
        List<Direction> directions,
        List<Candidate> potentialCandidates,
        List<Candidate> competitionCandidates,
        Coverage coverage,
        List<String> dataGaps
) {
    public enum Status { PREPARING, READY, STALE, UNAVAILABLE }
    public enum Lane { POTENTIAL, COMPETITION }
    public enum Grade { A, B, C }
    public enum DisclosureStatus { DISCLOSED, NOT_DISCLOSED, FETCH_FAILED, STALE_DISCLOSURE }
    public enum CalibrationStatus { READY, COLLECTING }

    public record BoardOptions(boolean includeGrowth, boolean includeStar) {}
    public record MarketSummary(
            int limitUpCount, Integer brokenCount, int maxBoard,
            BigDecimal sealRatePercent, BigDecimal boards2PlusRatioPercent,
            String sentimentTone) {}
    public record Direction(
            String industry, int rank, BigDecimal score,
            BigDecimal breadthPercent, BigDecimal medianChangePercent,
            int limitUpCount, int brokenCount, int maxBoard,
            BigDecimal amount, List<String> constituentSymbols) {}
    public record ScoreComponent(
            String code, String label, BigDecimal rawValue,
            BigDecimal points, BigDecimal maximumPoints) {}
    public record RiskDeduction(
            String code, String label, BigDecimal rawValue,
            BigDecimal threshold, BigDecimal points) {}
    public record Seat(
            String side, String branchCode, String branchName,
            BigDecimal buyAmount, BigDecimal sellAmount, BigDecimal netAmount,
            boolean institution, boolean stockConnect) {}
    public record DragonTigerEvidence(
            DisclosureStatus status, LocalDate disclosureDate,
            BigDecimal adjustment, List<Seat> seats, List<String> dataGaps) {}
    public record Calibration(
            CalibrationStatus status, int sampleSize,
            BigDecimal successRate, BigDecimal lower95, BigDecimal upper95) {}
    public record Candidate(
            Lane lane, int rank, Grade grade, BigDecimal score,
            String symbol, String name, String industry, String board,
            BigDecimal latestPrice, BigDecimal changePercent,
            BigDecimal realtimeVolumeRatio, BigDecimal turnoverRate,
            BigDecimal amount, BigDecimal circulatingMarketValue,
            BigDecimal ma10, BigDecimal ma20, BigDecimal ma20Slope,
            BigDecimal breakoutPositionPercent, boolean currentlySealed,
            int consecutiveBoards, LocalTime firstSealTime, LocalTime lastSealTime,
            int sealBreakCount, BigDecimal sealFunds,
            List<ScoreComponent> scoreBreakdown,
            List<RiskDeduction> riskDeductions,
            List<String> evidence,
            DragonTigerEvidence dragonTiger,
            Calibration calibration,
            String snapshotHash) {}
    public record Coverage(
            int universeCount, int baselineCount, int scopedCount,
            int liquidCount, int strongDirectionCount,
            int potentialEligibleCount, int competitionEligibleCount,
            int hiddenBelowScoreCount, int excludedMissingDataCount) {}
}
```

Every list constructor uses `List.copyOf`; nullable evidence/calibration values remain nullable instead of fabricated zeros.

- [ ] **Step 3: Implement exact math helpers and objective directions**

`LeaderRuleSet` owns `RULE_VERSION`, all thresholds, and these methods:

```java
static BigDecimal percentile(BigDecimal value, List<BigDecimal> sortedValues);
static BigDecimal linear(BigDecimal value, BigDecimal low, BigDecimal high, BigDecimal maxPoints);
static BigDecimal clamp(BigDecimal value, BigDecimal low, BigDecimal high);
static BigDecimal scale(BigDecimal value);
```

`LeaderDirectionEngine.rank` normalizes industry names, excludes `UNKNOWN`, requires 5 tradable constituents, computes the exact five factors in the approved spec, sorts deterministically, assigns ranks, and returns only directions with score `>=60` and rank `<=5`. Percentile ties use inclusive rank `(count <= value) / count`, making results deterministic for small sets.

- [ ] **Step 4: Implement lane qualification and board deduplication**

Create immutable internal records `CandidateUniverse` and `ScorableCandidate` in `LeaderCandidateEngine`. `select` must:

```java
record BoardState(
        boolean currentlySealed,
        int consecutiveBoards,
        LocalTime firstSealTime,
        LocalTime lastSealTime,
        int sealBreakCount,
        BigDecimal sealFunds
) {}

record ScorableCandidate(
        PotentialLeaderSnapshot.Lane lane,
        EastMoneyQuote quote,
        FirstBoardLeaderBaselineStock baseline,
        PotentialLeaderSnapshot.Direction direction,
        BoardState boardState,
        BigDecimal amountPercentileWithinDirection,
        BigDecimal changePercentileWithinDirection,
        BigDecimal turnoverPercentileWithinDirection,
        BigDecimal marketCapPercentileWithinStrongDirections
) {}

record SelectionCounts(
        int universeCount,
        int baselineCount,
        int scopedCount,
        int liquidCount,
        int strongDirectionCount,
        int potentialEligibleCount,
        int competitionEligibleCount,
        int excludedMissingDataCount
) {}

record CandidateUniverse(
        List<ScorableCandidate> potential,
        List<ScorableCandidate> competition,
        SelectionCounts counts
) {
    CandidateUniverse {
        potential = List.copyOf(potential);
        competition = List.copyOf(competition);
    }

    List<String> competitionSymbols() {
        return competition.stream().map(row -> row.quote().symbol()).distinct().toList();
    }
}
```

`select` must:

1. apply enabled-board, risk-name, date, positive-data, 60-bar baseline, amount P20, turnover `>=0.30%`, and strong-direction conditions;
2. index sealed and broken rows by normalized six-digit symbol;
3. merge a duplicate as one `COMPETITION` row with `currentlySealed=true`, earliest non-null first seal, latest non-null last seal and maximum break count;
4. send any quote for which `AsharePriceLimitRule.hasTouchedLimitUp(quote.symbol(), baseline.previousClose(), quote.highPrice())` is true to competition only when present in a board pool, never back to potential;
5. return exclusion counts used by `Coverage`.

- [ ] **Step 5: Implement both official scorecards exactly once**

`LeaderScoreEngine.rank` consumes scorable rows, direction metrics, market summary, evidence and prior same-day direction scores. Keep each approved component in one named method: `potentialDirection`, `potentialRelativeStrength`, `potentialLiquidity`, `potentialStructure`, `potentialMarketCap`, `competitionDirection`, `competitionBoardPosition`, `competitionSealQuality`, `competitionLiquidity`, and `competitionRelay`. Produce `ScoreComponent` rows, apply the eight risk rules, cap risk at 30, and compute:

```java
BigDecimal official = lane == Lane.POTENTIAL
        ? potentialBase.subtract(risk)
        : competitionBase.add(clamp(seatAdjustment, MIN_SEAT, MAX_SEAT)).subtract(risk);
official = clamp(official, ZERO, HUNDRED);
```

Sort by score descending, risk ascending, direction rank ascending, amount descending, symbol ascending. Drop `<55`, assign ranks after sorting, and retain at most 10. Hash only deterministic inputs with SHA-256; exclude calibration, AI, generated time and display rank from the hash.

- [ ] **Step 6: Record user-only verification commands and commit**

Do not run:

```bash
mvn -pl apps/api -Dtest=LeaderDirectionEngineTest,LeaderCandidateEngineTest,LeaderScoreEngineTest test
```

Commit:

```bash
git add apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderSnapshot.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderRuleSet.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderDirectionEngine.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderCandidateEngine.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderScoreEngine.java \
  apps/api/src/test/java/com/aistock/research/leader/LeaderDirectionEngineTest.java \
  apps/api/src/test/java/com/aistock/research/leader/LeaderCandidateEngineTest.java \
  apps/api/src/test/java/com/aistock/research/leader/LeaderScoreEngineTest.java
git commit -m "feat: add deterministic leader ranking"
```

### Task 3: Add the Dragon-Tiger Batch Gateway

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/leader/DragonTigerGateway.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/EastMoneyDragonTigerGateway.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/EastMoneyDragonTigerGatewayTest.java`

**Interfaces:**
- Consumes: scan trade date and all competition symbols before final ranking.
- Produces: one `DragonTigerBatch` with evidence for every requested symbol and a batch-level latest disclosure date/data gaps.

- [ ] **Step 1: Write parsing, dedup and failure tests first**

Use captured minimal JSON fixtures for `RPT_DAILYBILLBOARD_PROFILE`, `RPT_BILLBOARD_DAILYDETAILSBUY` and `RPT_BILLBOARD_DAILYDETAILSSELL`. Assert branch code wins over differing names, missing code falls back to trimmed name, one institution and one Stock Connect row are marked separately, and these exact adjustment boundaries hold:

```java
assertThat(gateway.adjustment(diverseNetBuyingSeats)).isEqualByComparingTo("10.00");
assertThat(gateway.adjustment(concentratedOverlappingSeats)).isEqualByComparingTo("-5.00");
assertThat(failed.evidenceBySymbol().get("600000").status()).isEqualTo(DisclosureStatus.FETCH_FAILED);
assertThat(notListed.adjustment()).isEqualByComparingTo("0");
```

- [ ] **Step 2: Define the interface and total failure result**

```java
public interface DragonTigerGateway {
    DragonTigerBatch fetch(LocalDate scanTradeDate, Collection<String> symbols);

    record DragonTigerBatch(
            LocalDate latestDisclosureDate,
            Map<String, PotentialLeaderSnapshot.DragonTigerEvidence> evidenceBySymbol,
            List<String> dataGaps
    ) {
        public DragonTigerBatch {
            evidenceBySymbol = evidenceBySymbol == null ? Map.of() : Map.copyOf(evidenceBySymbol);
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }
}
```

An empty symbol collection returns an empty successful batch. A remote or parse failure returns `FETCH_FAILED` with zero adjustment for every requested symbol; it never throws into formal ranking.

- [ ] **Step 3: Fetch and parse the three reports in batches**

`EastMoneyDragonTigerGateway` uses `RestClient`, Jackson and `TradingClockService`. Fetch the date range covering the scan day and nine preceding trading days from:

```text
https://datacenter-web.eastmoney.com/api/data/v1/get
```

Use `pageSize=500`, follow `pages`, and request `columns=ALL`. Group profiles and seat rows by `(SECURITY_CODE, TRADE_DATE)`, select each symbol’s newest disclosure, accept only the scan day plus four preceding sessions for adjustment, and use the next five older sessions solely to distinguish `STALE_DISCLOSURE` from `NOT_DISCLOSED`. Cache the parsed date-range batch for 300 seconds by scan date; filter requested symbols after cache lookup.

Compute adjustment using the exact branch-count, net-buy ratio, top-one concentration, buy/sell overlap and normalized-entropy formulas in the approved spec, then clamp once to `[-5,10]`. A missing profile is `NOT_DISCLOSED`, not `FETCH_FAILED`.

- [ ] **Step 4: Record user-only verification and commit**

Do not run:

```bash
mvn -pl apps/api -Dtest=EastMoneyDragonTigerGatewayTest test
```

Commit:

```bash
git add apps/api/src/main/java/com/aistock/research/leader/DragonTigerGateway.java \
  apps/api/src/main/java/com/aistock/research/leader/EastMoneyDragonTigerGateway.java \
  apps/api/src/test/java/com/aistock/research/leader/EastMoneyDragonTigerGatewayTest.java
git commit -m "feat: add dragon tiger evidence gateway"
```

### Task 4: Add Immutable Close Snapshots and Historical Calibration

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V4__potential_leader_shadow_learning.sql`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderSnapshotStore.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderLearningService.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderSnapshotStoreTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderLearningServiceTest.java`
- Modify test: `apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java`

**Interfaces:**
- Produces: `capture(PotentialLeaderSnapshot)`, `refreshPending(LocalDate)`, and `calibration(Lane,Grade,BigDecimal)`.
- Consumes later: ready snapshots from orchestration; canonical calibration is injected only after official ranking.

- [ ] **Step 1: Add migration/store/label tests first**

Extend the migration table assertion with exactly:

```java
"leader_daily_snapshot", "leader_outcome", "leader_ai_review"
```

Add unit/store tests for duplicate `(trade_date, rule_version)` capture returning the existing UUID, no intraday write before 15:10, PENDING_DATA on an incomplete future horizon, exact potential/competition success examples, and calibration:

```java
assertThat(service.calibration(Lane.POTENTIAL, Grade.A, new BigDecimal("80")))
        .extracting(Calibration::status, Calibration::sampleSize)
        .containsExactly(CalibrationStatus.COLLECTING, 29);
assertThat(service.calibration(Lane.POTENTIAL, Grade.A, new BigDecimal("80")))
        .satisfies(value -> assertThat(value.lower95()).isNotNull()); // after n=30 fixture
```

- [ ] **Step 2: Add exactly three V4 tables**

The migration must contain this shape and no raw Dragon-Tiger table:

```sql
CREATE TABLE leader_daily_snapshot (
  id UUID PRIMARY KEY,
  trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  quote_trade_date DATE NOT NULL,
  quote_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
  dragon_tiger_date DATE,
  market_summary_json JSONB NOT NULL,
  directions_json JSONB NOT NULL,
  potential_candidates_json JSONB NOT NULL,
  competition_candidates_json JSONB NOT NULL,
  source_status_json JSONB NOT NULL,
  data_gaps_json JSONB NOT NULL,
  snapshot_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (trade_date, rule_version)
);

CREATE TABLE leader_outcome (
  snapshot_id UUID NOT NULL REFERENCES leader_daily_snapshot(id) ON DELETE CASCADE,
  lane VARCHAR(16) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  grade VARCHAR(1) NOT NULL,
  direction_heat VARCHAR(8) NOT NULL,
  status VARCHAR(16) NOT NULL,
  success BOOLEAN,
  return_t1 NUMERIC(12,6), return_t3 NUMERIC(12,6), return_t5 NUMERIC(12,6),
  max_drawdown NUMERIC(12,6),
  closed_limit_up_count INTEGER,
  max_board_count INTEGER,
  evaluated_through_date DATE,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (snapshot_id, lane, symbol)
);

CREATE TABLE leader_ai_review (
  id UUID PRIMARY KEY,
  trade_date DATE NOT NULL,
  lane VARCHAR(16) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  snapshot_hash VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  verdict VARCHAR(16), phase VARCHAR(16),
  advantages_json JSONB NOT NULL,
  counter_evidence_json JSONB NOT NULL,
  data_gaps_json JSONB NOT NULL,
  must_verify_json JSONB NOT NULL,
  confidence NUMERIC(6,5),
  provider VARCHAR(64), model VARCHAR(128), prompt_version VARCHAR(64) NOT NULL,
  error_summary VARCHAR(500),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

Add lookup indexes on outcome cohort/status and AI `(trade_date,lane,symbol,created_at DESC)`.

- [ ] **Step 3: Implement JSONB persistence and idempotent capture**

Make `FirstBoardDailyBarStore` public so the leader learning service can read `recentBars` and `confirmedLimitUpSymbols`; do not expose mutation methods through a second interface. `LeaderSnapshotStore` uses `JdbcTemplate` and `ObjectMapper`, serializes already-immutable records, casts parameters with `?::jsonb`, and provides:

```java
Optional<UUID> findSnapshotId(LocalDate tradeDate, String ruleVersion);
UUID saveSnapshotIfAbsent(PotentialLeaderSnapshot snapshot);
void initializeOutcomes(UUID snapshotId, PotentialLeaderSnapshot snapshot);
List<PendingOutcome> pendingOutcomes();
void saveOutcome(OutcomeResult result);
CohortCounts cohortCounts(Lane lane, Grade grade, String directionHeat);
```

Define the store projections in `LeaderSnapshotStore` so no later task invents incompatible shapes:

```java
record PendingOutcome(
        UUID snapshotId, Lane lane, String symbol, LocalDate tradeDate,
        String ruleVersion, Grade grade, String directionHeat,
        String industry, BigDecimal sampleClose,
        List<String> directionConstituents
) {}

record OutcomeResult(
        UUID snapshotId, Lane lane, String symbol, String status,
        Boolean success, BigDecimal returnT1, BigDecimal returnT3,
        BigDecimal returnT5, BigDecimal maxDrawdown,
        Integer closedLimitUpCount, Integer maxBoardCount,
        LocalDate evaluatedThroughDate
) {}

record CohortCounts(int sampleSize, int successCount) {}
```

`saveSnapshotIfAbsent` checks the unique row before serialization and uses `ON CONFLICT DO NOTHING`, then rereads the UUID. Store all T-day direction constituent symbols in `directions_json`.

- [ ] **Step 4: Implement labels without lookahead**

`LeaderLearningService.capture` accepts only READY, matching-date snapshots at or after 15:10 and persists only the top-ten lists already returned. For a catch-up after the sample day, reject any candidate whose Dragon-Tiger disclosure date is not strictly before T.

`refreshPending` obtains verified T+3/T+5 dates from `TradingClockService`, requires every target market day to be READY, and then:

- Potential succeeds when at least one candidate session is in `confirmedLimitUpSymbols` and its maximum T-close cumulative return is at or above the 80th percentile of the frozen T-day direction constituent set over the identical window.
- Competition succeeds when one T+1..T+3 session is a confirmed close limit-up and its computed consecutive-board height ranks first or second among frozen direction constituents that day.
- Missing candidate/peer bars or a suspended session writes `PENDING_DATA`, never `success=false`.

Compute Wilson 95% interval with `z=1.96`; return `COLLECTING` with null rate/interval below 30 READY rows.

- [ ] **Step 5: Record user-only verification and commit**

Do not run:

```bash
mvn -pl apps/api -Dtest=LeaderSnapshotStoreTest,LeaderLearningServiceTest,PostgresFlywayMigrationTest test
```

Commit the task files with message:

```bash
git add apps/api/src/main/resources/db/migration/V4__potential_leader_shadow_learning.sql \
  apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderSnapshotStore.java \
  apps/api/src/main/java/com/aistock/research/leader/LeaderLearningService.java \
  apps/api/src/test/java/com/aistock/research/leader/LeaderSnapshotStoreTest.java \
  apps/api/src/test/java/com/aistock/research/leader/LeaderLearningServiceTest.java \
  apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java
git commit -m "feat: persist leader shadow outcomes"
```

### Task 5: Orchestrate the Snapshot API and Retire the Old Backend Candidate Layer

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderService.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderController.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderBarSeries.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderLearningScheduler.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/PotentialLeaderServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/PotentialLeaderControllerTest.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertService.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertController.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertCandidate.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertCoverage.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertLane.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertLevel.java`
- Delete: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertSnapshot.java`
- Delete: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardAlertServiceTest.java`

**Interfaces:**
- Produces: `snapshot(boolean includeGrowth, boolean includeStar)`, `bars(String)`, and `currentCandidate(Lane,String,String)`.
- Produces endpoints: GET snapshot, GET bars, with POST AI added in Task 6.

- [ ] **Step 1: Write orchestration/error/controller tests first**

Test exact behavior:

- Baseline preparation returns PREPARING and causes no quote, board or Dragon-Tiger calls.
- Incomplete full-market quote, unavailable limit-up pool, broken-pool failure, or mismatched trade date returns UNAVAILABLE with empty candidate arrays.
- Dragon-Tiger failure leaves READY candidates and a zero adjustment/data gap.
- A later core failure returns the previous successful candidate arrays only as STALE, preserves the old quote timestamp, and never marks auto-refresh notification-safe.
- Growth/STAR filtering changes scoped candidates but not the baseline build.
- Controller routes use `/api/potential-leaders/snapshot` and `/bars/{symbol}`; no old controller mapping remains.

- [ ] **Step 2: Implement keyed 60-second orchestration**

Use cache key `(tradeDate, includeGrowth, includeStar)` and one successful prior snapshot per key. `snapshot` order is fixed:

```java
FirstBoardLeaderBaselineAccess baseline = baselineService.leaderAccess(tradeDate);
if (baseline.status() != READY) return preparingOrUnavailable(baseline);
AshareQuoteSnapshot quotes = eastMoneyClient.fetchAshareQuoteSnapshot(6000);
ShortTermLimitUpBoardSnapshot boards = boardService.snapshot(tradeDate);
requireCompleteAndDateAligned(quotes, boards, tradeDate);
List<Direction> directions = directionEngine.rank(quotes.quotes(), boards.stocks(), boards.brokenStocks());
CandidateUniverse universe = candidateEngine.select(
        baseline, quotes.quotes(), boards, directions, includeGrowth, includeStar);
DragonTigerBatch seats = dragonTigerGateway.fetch(tradeDate, universe.competitionSymbols());
List<Candidate> potential = scoreEngine.rankPotential(
        universe.potential(), directions, previousDirectionScores);
List<Candidate> competition = scoreEngine.rankCompetition(
        universe.competition(), directions, marketSummary, seats, previousDirectionScores);
PotentialLeaderSnapshot official = readySnapshot(
        tradeDate, baseline, quotes, boards, directions, potential, competition, universe, seats);
return attachCalibrationAfterOfficialRanking(official);
```

Historical calibration is attached by copying already-ranked candidates after score/hash/rank are final. Build `MarketSummary` from the existing limit-up sentiment. Keep the previous same-day direction snapshot only for the direction-fade deduction.

Core failure with no prior success returns UNAVAILABLE and empty arrays. Core failure with prior success returns STALE and the prior arrays plus a new gap; it must not replace trade/quote timestamps with `now`. `autoRefreshAllowed` is based on the trading clock, while the frontend separately prohibits STALE notifications.

- [ ] **Step 3: Add bars and canonical candidate lookup**

Create:

```java
public record LeaderBarSeries(
        String symbol,
        LocalDate tradeDate,
        LocalDate baselineDate,
        String adjustMode,
        List<FirstBoardBar> bars
) {
    public LeaderBarSeries {
        adjustMode = "QFQ";
        bars = bars == null ? List.of() : List.copyOf(bars);
    }
}
```

`currentCandidate(lane,symbol,expectedHash)` scans non-expired current-day READY cache entries for the exact `(lane,symbol,snapshotHash)`. It does not recompute with guessed board switches and does not create an arbitrary candidate; absence is a 409 stale-selection conflict, so the user refreshes before asking AI again.

- [ ] **Step 4: Schedule retries without repeated writes**

Create `LeaderLearningScheduler` only after `PotentialLeaderService` exists:

```java
@Scheduled(cron = "0 */10 15-23 * * MON-FRI", zone = "Asia/Shanghai")
void captureCloseIfMissing() {
    LocalDate tradeDate = clock.currentMarketDate();
    if (learningService.hasSnapshot(tradeDate, LeaderRuleSet.RULE_VERSION)) return;
    learningService.capture(snapshotService.snapshot(false, false));
}

@Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Shanghai")
void matureOutcomes() {
    learningService.refreshPending(clock.currentMarketDate());
}
```

The existence guard runs before any quote fetch. The 15:00 attempt exits because `capture` enforces 15:10.

- [ ] **Step 5: Add the controller and remove old candidate code**

```java
@RestController
@RequestMapping("/api/potential-leaders")
public class PotentialLeaderController {
    @GetMapping("/snapshot")
    PotentialLeaderSnapshot snapshot(
            @RequestParam(defaultValue="false") boolean includeGrowth,
            @RequestParam(defaultValue="false") boolean includeStar) {
        return service.snapshot(includeGrowth, includeStar);
    }

    @GetMapping("/bars/{symbol}")
    LeaderBarSeries bars(@PathVariable String symbol) {
        return service.bars(symbol).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "该股票的日线数据不可用"));
    }
}
```

Delete only the seven old candidate/controller DTO files and old service test listed above. Do not delete any persisted-baseline type, store, scheduler, ingestion, retention or evaluator.

- [ ] **Step 6: Record user-only verification and commit**

Do not run:

```bash
mvn -pl apps/api -Dtest=PotentialLeaderServiceTest,PotentialLeaderControllerTest,FirstBoardBaselineServiceTest test
```

Commit with message `feat: replace first board API with potential leaders` using only Task 5 files.

### Task 6: Add Manual AI Shadow Review

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderAiReview.java`
- Create: `apps/api/src/main/java/com/aistock/research/leader/LeaderAiReviewService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/leader/LeaderSnapshotStore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/leader/PotentialLeaderController.java`
- Test: `apps/api/src/test/java/com/aistock/research/leader/LeaderAiReviewServiceTest.java`
- Modify test: `apps/api/src/test/java/com/aistock/research/leader/PotentialLeaderControllerTest.java`

**Interfaces:**
- Consumes: lane, symbol and expected deterministic `snapshotHash` only.
- Produces: persisted `SUPPORT|NEUTRAL|OPPOSE` and `EMERGING|COMPETING|CROWDED|FADING` review.

- [ ] **Step 1: Write canonical reload and isolation tests first**

Prove that a mismatched hash raises a conflict before calling `LlmChatClient`, a missing candidate returns not found, malformed model JSON persists FAILED, valid JSON persists SUCCEEDED, and neither result can alter the before/after official candidate score/rank/hash.

- [ ] **Step 2: Define the review contract**

```java
public record LeaderAiReview(
        UUID id, LocalDate tradeDate, PotentialLeaderSnapshot.Lane lane,
        String symbol, String ruleVersion, String snapshotHash,
        String status, String verdict, String phase,
        List<String> advantages, List<String> counterEvidence,
        List<String> dataGaps, List<String> mustVerify,
        BigDecimal confidence, String provider, String model,
        String promptVersion, String errorSummary, Instant createdAt
) {
    public LeaderAiReview {
        advantages = advantages == null ? List.of() : List.copyOf(advantages);
        counterEvidence = counterEvidence == null ? List.of() : List.copyOf(counterEvidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        mustVerify = mustVerify == null ? List.of() : List.copyOf(mustVerify);
    }

    public record Request(String snapshotHash) {}
}
```

Prompt version is `potential-leader-ai-v1`. Confidence is decimal `0..1`, not `0..100`.

- [ ] **Step 3: Implement structured review from server facts**

`review(lane,symbol,expectedHash)` calls `PotentialLeaderService.currentCandidate(lane,symbol,expectedHash)`, rechecks the hash with constant-time string equality, builds a payload only from its score breakdown/evidence/risks/seats/gaps, and calls `completeJson` with a schema that has `additionalProperties=false` and all seven output fields required. Limit advantages and counter-evidence to three nonblank strings each; invalid enum or confidence fails the review rather than guessing.

Add `void saveAiReview(LeaderAiReview review)` to `LeaderSnapshotStore`, casting all four list columns to JSONB. Persist both success and failure with this method. Failure returns a FAILED review with the deterministic candidate untouched.

- [ ] **Step 4: Add POST endpoint**

```java
@PostMapping("/ai-review/{lane}/{symbol}")
LeaderAiReview aiReview(
        @PathVariable String lane,
        @PathVariable String symbol,
        @RequestBody LeaderAiReview.Request request) {
    PotentialLeaderSnapshot.Lane parsed = switch (lane.toLowerCase(Locale.ROOT)) {
        case "potential" -> PotentialLeaderSnapshot.Lane.POTENTIAL;
        case "competition" -> PotentialLeaderSnapshot.Lane.COMPETITION;
        default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知候选通道");
    };
    return aiReviewService.review(parsed, symbol, request.snapshotHash());
}
```

Map an invalid lane to 400, missing candidate to 404, and hash mismatch to 409. Do not accept score components, evidence or market facts in the body.

- [ ] **Step 5: Record user-only verification and commit**

Do not run:

```bash
mvn -pl apps/api -Dtest=LeaderAiReviewServiceTest,PotentialLeaderControllerTest test
```

Commit with message `feat: add leader AI shadow review` using Task 6 files.

### Task 7: Replace Frontend Data Contracts, Preferences, and Notifications

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify test: `apps/web-react/src/components/shortterm/LimitUpBoardPanel.test.tsx`
- Create: `apps/web-react/src/lib/potentialLeaderPreferences.ts`
- Create: `apps/web-react/src/lib/potentialLeaderNotifications.ts`
- Create test: `apps/web-react/src/lib/potentialLeaderPreferences.test.ts`
- Create test: `apps/web-react/src/lib/potentialLeaderNotifications.test.ts`
- Delete: `apps/web-react/src/lib/firstBoardAlertPreferences.ts`
- Delete: `apps/web-react/src/lib/firstBoardAlertPreferences.test.ts`
- Delete: `apps/web-react/src/lib/firstBoardAlertNotifications.ts`
- Delete: `apps/web-react/src/lib/firstBoardAlertNotifications.test.ts`

**Interfaces:**
- Produces: TypeScript mirror of the backend snapshot/review/bar contracts.
- Produces: `loadPotentialLeaderPreferences`, `savePotentialLeaderPreferences`, `collectPotentialLeaderNotifications`.

- [ ] **Step 1: Write preference and notification tests first**

Preferences default to both false, persist booleans under `ai-stock:potential-leader:boards:v1`, and recover from invalid JSON. Notification tests use a supplied memory `Storage` and prove:

```ts
expect(collectPotentialLeaderNotifications(readyWith(candidate('A')))).toHaveLength(1)
expect(collectPotentialLeaderNotifications(readyWith(candidate('A')))).toHaveLength(0)
collectPotentialLeaderNotifications(readyWith(candidate('B')))
expect(collectPotentialLeaderNotifications(readyWith(candidate('A')))).toHaveLength(1)
expect(collectPotentialLeaderNotifications(Object.assign({}, ready, { status: 'STALE' }))).toHaveLength(0)
```

Use `lane:symbol` in keys so the same symbol in different lanes cannot collide.

- [ ] **Step 2: Replace the old TypeScript contracts**

Delete the first-board alert types at the start of `types.ts` and define exact unions/interfaces matching every nested Java field. Add required `brokenBoardAvailable: boolean` and `brokenStocks: ShortTermBrokenBoardStock[]` fields to `ShortTermLimitUpBoardSnapshot`, plus the matching broken-stock interface. Update `LimitUpBoardPanel.test.tsx` with `brokenBoardAvailable: true` and `brokenStocks: []`; the embedded component remains behaviorally unchanged.

Add:

```ts
export function fetchPotentialLeaderSnapshot(params: PotentialLeaderPreferences) {
  return http.get<PotentialLeaderSnapshot>('/potential-leaders/snapshot', { params }).then(res => res.data)
}
export function fetchPotentialLeaderBars(symbol: string) {
  return http.get<LeaderBarSeries>(`/potential-leaders/bars/${encodeURIComponent(symbol)}`).then(res => res.data)
}
export function requestPotentialLeaderAiReview(candidate: PotentialLeaderCandidate) {
  return http.post<LeaderAiReview>(
    `/potential-leaders/ai-review/${candidate.lane.toLowerCase()}/${encodeURIComponent(candidate.symbol)}`,
    { snapshotHash: candidate.snapshotHash }
  ).then(res => res.data)
}
```

Remove `fetchFirstBoardAlertSnapshot` and `fetchFirstBoardBars`.

- [ ] **Step 3: Implement same-day notification state**

Persist one object `{ tradeDate, grades, notifiedA }`. On READY only, update grades for every visible candidate; notify exactly when current grade is A and its lane/symbol has not been placed in `notifiedA`. This covers a newly seen A and B/C-to-A once, while preventing leave/re-enter duplicates. Do not mutate storage for PREPARING/STALE/UNAVAILABLE.

- [ ] **Step 4: Record user-only verification and commit**

Do not run:

```bash
npm --prefix apps/web-react test -- potentialLeaderPreferences.test.ts potentialLeaderNotifications.test.ts
```

Commit with message `feat: add potential leader web contracts` using Task 7 files.

### Task 8: Build the Potential Leader Page and Replace Navigation

**Files:**
- Create: `apps/web-react/src/pages/PotentialLeaderPage.tsx`
- Create: `apps/web-react/src/pages/PotentialLeaderPage.test.tsx`
- Create: `apps/web-react/src/components/leader/LeaderMarketOverview.tsx`
- Create: `apps/web-react/src/components/leader/LeaderCandidateTable.tsx`
- Create: `apps/web-react/src/components/leader/LeaderDetailOverlay.tsx`
- Create: `apps/web-react/src/components/leader/LeaderCandlestickChart.tsx`
- Modify: `apps/web-react/src/App.tsx`
- Modify: `apps/web-react/src/components/layout/pageMeta.ts`
- Modify: `apps/web-react/src/components/layout/NavRail.tsx`
- Modify test: `apps/web-react/src/components/layout/pageMeta.test.ts`
- Modify test: `apps/web-react/src/components/layout/AppShell.test.tsx`
- Delete: `apps/web-react/src/pages/FirstBoardAlertPage.tsx`
- Delete: `apps/web-react/src/pages/FirstBoardAlertPage.test.tsx`
- Delete: `apps/web-react/src/components/firstboard/FirstBoardCandlestickChart.tsx`

**Interfaces:**
- Consumes: Task 7 client/types/preferences/notification helpers.
- Produces: `/potential-leader` page and `/first-board-alert` redirect.

- [ ] **Step 1: Write page and route tests first**

Mock the three API calls and assert:

- the first snapshot call uses `{includeGrowth:false, includeStar:false}`;
- READY renders “潜龙”, “竞龙”, “最强方向”, both score columns and distinct empty text;
- PREPARING keeps 60-second polling even outside trading hours;
- STALE renders its quote timestamp and never calls notification helper;
- clicking a row requests 20-day bars; clicking “AI 复核” posts the selected hash and renders verdict without changing table order;
- `pathToPage['potential-leader'] === 'potentialLeader'` and old `/first-board-alert` renders a redirect to `/potential-leader`.

- [ ] **Step 2: Implement the page state and polling shell**

`PotentialLeaderPage` follows the existing request-id race guard. It owns preferences, snapshot, loading/error, selected candidate, bars, AI loading/result/error. Poll when `autoRefreshAllowed` or status is PREPARING/UNAVAILABLE, every 60 seconds. Only call `collectPotentialLeaderNotifications` after receiving READY.

Use `SectionBanner`:

```tsx
<SectionBanner
  eyebrow="POTENTIAL LEADER"
  title="潜力龙头"
  description="主线共振与板块辨识度形成正式双通道排名；龙虎榜仅作竞龙软增强，AI 与历史结果保持影子展示。"
  extra={(
    <div className="flex items-center gap-3">
      {snapshot?.quoteTimestamp ? <span>{formatDateTime(snapshot.quoteTimestamp)}</span> : null}
      <Button loading={loading} onClick={() => void load()}>刷新</Button>
    </div>
  )}
/>
```

Keep Growth/STAR switches and clearly state main board always enabled.

- [ ] **Step 3: Implement overview and lane tables**

`LeaderMarketOverview` renders the status strip, all data gaps, four market metrics and up to five directions in a single responsive grid. It displays quote date/time and Dragon-Tiger date separately.

`LeaderCandidateTable` receives `lane`, candidates and `onSelect`. Potential columns: grade/score, stock/industry rank, change/volume ratio, turnover/amount, circulating cap, breakout, risk. Competition columns: grade/score, stock/industry, boards, first seal/breaks, seal strength, turnover, seat adjustment, risk. Use current `DataTable`, `Tag`, `Card`, `format` helpers, tabular numerals and minimum widths; no new chart library.

- [ ] **Step 4: Implement the evidence-first detail overlay**

Move the existing canvas/SVG candlestick implementation unchanged except export/file name. `LeaderDetailOverlay` renders chart, score components, direction facts, evidence, deductions, disclosure status/date and seat rows, calibration, then the manual AI area. Disable AI button while loading and on non-READY selection; show `COLLECTING` as “样本积累中（n/30）”. Clearly label AI “影子复核，不影响正式排名”.

- [ ] **Step 5: Replace route and navigation names**

Change `PageKey` from `firstBoardAlert` to `potentialLeader`; use label/title `潜力龙头`, count `双通道`, route `potential-leader`, eyebrow `POTENTIAL LEADER`. In `App.tsx` lazy-load the new page, add the formal route, and retain:

```tsx
<Route path="first-board-alert" element={<Navigate to="/potential-leader" replace />} />
```

Delete the old page/chart only after no imports refer to them.

- [ ] **Step 6: Record user-only verification and commit**

Do not run:

```bash
npm --prefix apps/web-react test -- PotentialLeaderPage.test.tsx pageMeta.test.ts AppShell.test.tsx
npm --prefix apps/web-react run build
```

Commit with message `feat: replace first board page with potential leaders` using Task 8 files.

### Task 9: Reconcile the Written Spec and Hand Off Without Self-Verification

**Files:**
- Modify: `docs/superpowers/specs/2026-09-03-potential-leader-design.md`
- Create/modify: `docs/superpowers/plans/2026-09-03-potential-leader.md`

**Interfaces:**
- Produces: final source/spec consistency and an explicit user-owned verification handoff.

- [ ] **Step 1: Preserve the already identified spec corrections**

Ensure the spec says `V4__potential_leader_shadow_learning.sql`, not V3, and the AI request accepts only `snapshotHash` as a concurrency token in addition to path identifiers. Do not alter approved scoring or product scope during cleanup.

- [ ] **Step 2: Do not perform a verification sweep**

Do not run tests, compilation, build, lint, typecheck, `git diff --check`, browser checks, API calls, database migration checks, application startup, health/log checks, or deployment. Do not delegate those actions.

The user may choose to run, in their own environment, the focused commands documented in Tasks 1–8 and a PostgreSQL-backed migration test.

- [ ] **Step 3: Commit documentation state and report exact limitations**

Stage only the design correction and this plan if they are not already committed:

```bash
git add docs/superpowers/specs/2026-09-03-potential-leader-design.md \
  docs/superpowers/plans/2026-09-03-potential-leader.md
git commit -m "docs: plan potential leader implementation"
```

Final handoff must summarize implemented lanes, official-vs-shadow isolation, route replacement, migration name, commits, and explicitly end the engineering status with `未由 Codex 测试`.
