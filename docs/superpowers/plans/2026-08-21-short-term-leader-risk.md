# Short-Term Leader Risk Warning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add scan-time warnings for strengthening A-share weight leaders and theme leaders by comparing the current full-market snapshot with the previous scan or previous trading-day snapshot.

**Architecture:** Add one deep `ShortTermLeaderRiskModule` interface. Its implementation owns compact snapshot construction, baseline selection, sensitive dual-track detection, candidate-industry context, and snapshot persistence; `ShortTermService` calls it once after candidate ordering is complete and only attaches the returned DTO to `ShortTermReport`. A JPA adapter persists compact JSON snapshots while an in-memory adapter tests the module through the same seam.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, H2/PostgreSQL-compatible SQL, Jackson, JUnit 5, AssertJ, React 18, TypeScript, Vitest, Vite, Docker Compose.

## Global Constraints

- Monitor both large-cap/weight leaders and theme/industry leaders.
- Prefer the previous reliable scan on the same trading day; otherwise compare with the last reliable snapshot from an earlier trading day.
- Use sensitive triggering: a qualified leader strengthening is enough; the previous hot direction does not also need to weaken.
- The warning is display-only and must not change candidate eligibility, score fields, ranking, action, advice, position sizing, or trade plans.
- Do not compare raw cumulative amount across intraday and prior-close snapshots. Use amount rank and amount share inside each snapshot.
- An unreliable full-market coverage snapshot must return `UNAVAILABLE` and must not be persisted as a future baseline.
- Initial deployment or a rule-version change returns `BASELINE_BUILDING`, not a false confirmed anomaly.
- A leader-risk persistence or evaluation failure must fail open for the recommendation scan and produce an explicit unavailable risk object.
- Preserve the unrelated untracked `.zcode/` plan and do not stage it.
- Work directly on `main`, as explicitly requested by the user; do not create a worktree or feature branch.

---

### Task 1: Add Total Market Value to the Unified Quote Snapshot

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`

**Interfaces:**
- Consumes: EastMoney field `f20` from the existing full-market batch quote request.
- Produces: `EastMoneyQuote.totalMarketValue()` in yuan while retaining the existing 15-argument and 17-argument constructors.

- [ ] **Step 1: Write the failing EastMoney parser test**

Extend `shouldParseExchangeTimestampInsteadOfTreatingFetchTimeAsTradeTime` with a market-value field and assertion:

```java
JsonNode item = objectMapper.createObjectNode()
        .put("f12", "600000")
        .put("f14", "浦发银行")
        .put("f13", 1)
        .put("f2", 1000)
        .put("f3", 20)
        .put("f6", 900000000)
        .put("f20", 245600000000L)
        .put("f124", marketTime.getEpochSecond());

assertThat(quote.totalMarketValue()).isEqualByComparingTo("245600000000");
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=EastMoneyClientTest#shouldParseExchangeTimestampInsteadOfTreatingFetchTimeAsTradeTime test
```

Expected: compilation fails because `totalMarketValue()` does not exist.

- [ ] **Step 3: Append the field with compatibility constructors**

Append `BigDecimal totalMarketValue` after `marketTimestamp` in `EastMoneyQuote`. Preserve the old canonical shape with this overload and update the existing short overload to delegate with a final `null`:

```java
public EastMoneyQuote(
        String symbol, String name, String market, String industry,
        BigDecimal latestPrice, BigDecimal changePercent, BigDecimal turnoverRate,
        BigDecimal volume, BigDecimal amount, BigDecimal peRatio, BigDecimal pbRatio,
        BigDecimal peTtm, String sourceName, String quoteUrl, Instant fetchedAt,
        LocalDate tradeDate, Instant marketTimestamp
) {
    this(symbol, name, market, industry, latestPrice, changePercent, turnoverRate,
            volume, amount, peRatio, pbRatio, peTtm, sourceName, quoteUrl,
            fetchedAt, tradeDate, marketTimestamp, null);
}
```

- [ ] **Step 4: Parse `f20` without adding a second request**

Change the field list and the EastMoney constructor call:

```java
private static final String QUOTE_FIELDS =
        "f2,f3,f5,f6,f8,f9,f12,f13,f14,f20,f23,f60,f100,f115,f124";
```

```java
return Optional.of(new EastMoneyQuote(
        symbol, name, market, text(item, "f100"),
        positiveOrNull(scaled(item, "f2", 2)), scaled(item, "f3", 2),
        scaled(item, "f8", 2), decimal(item, "f5"), decimal(item, "f6"),
        scaled(item, "f9", 2), scaled(item, "f23", 2), scaled(item, "f115", 2),
        "东方财富行情", quoteUrl(symbol), fetchedAt,
        tradeDate(marketTimestamp), marketTimestamp, positiveOrNull(decimal(item, "f20"))
));
```

Tencent fallback continues to pass `null` for total market value.

- [ ] **Step 5: Run the EastMoney client tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=EastMoneyClientTest,AshareQuotePaginatorTest test
```

Expected: zero failures and zero errors.

- [ ] **Step 6: Commit the quote contract change**

```bash
git add apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyQuote.java \
  apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java \
  apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java
git commit -m "feat: expose quote market value for leader risk"
```

---

### Task 2: Build the Deep Leader-Risk Module Through Its Interface

**Files:**
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/leader/DefaultShortTermLeaderRiskModuleTest.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRisk.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRiskSignal.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRiskInput.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRiskModule.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/DefaultShortTermLeaderRiskModule.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotStore.java`

**Interfaces:**
- Consumes: `ShortTermLeaderRiskInput(tradeDate, capturedAt, coverage, quotes, hotDirections, candidateIndustries)`.
- Produces: `ShortTermLeaderRiskModule.evaluate(input)` and one compact reliable snapshot save.
- Rule version: `short-term-leader-risk-v1-sensitive`.

- [ ] **Step 1: Write failing tests for initial, previous-day, and same-day behavior**

The test uses a local in-memory store implementing the snapshot seam. Add three focused tests with these assertions:

```java
ShortTermLeaderRisk initial = module.evaluate(input(
        LocalDate.parse("2026-08-20"), Instant.parse("2026-08-20T06:30:00Z"),
        baselineQuotes(), baselineDirections(), List.of("化学制药", "化学制药")
));
assertThat(initial.status()).isEqualTo(ShortTermLeaderRisk.Status.BASELINE_BUILDING);
assertThat(store.saved).hasSize(1);

ShortTermLeaderRisk previousDay = module.evaluate(input(
        LocalDate.parse("2026-08-21"), Instant.parse("2026-08-21T01:45:00Z"),
        strengthenedQuotes(), strengthenedDirections(),
        List.of("化学制药", "化学制药", "医疗服务")
));
assertThat(previousDay.status()).isEqualTo(ShortTermLeaderRisk.Status.WARNING);
assertThat(previousDay.baselineType())
        .isEqualTo(ShortTermLeaderRisk.BaselineType.PREVIOUS_TRADING_DAY);
assertThat(previousDay.signals()).extracting(ShortTermLeaderRiskSignal::track)
        .contains(ShortTermLeaderRiskSignal.Track.WEIGHT,
                ShortTermLeaderRiskSignal.Track.THEME);
assertThat(previousDay.directionConflict()).isTrue();

ShortTermLeaderRisk previousScan = module.evaluate(input(
        LocalDate.parse("2026-08-21"), Instant.parse("2026-08-21T02:15:00Z"),
        acceleratedAgainQuotes(), strengthenedDirections(), List.of("化学制药")
));
assertThat(previousScan.baselineType())
        .isEqualTo(ShortTermLeaderRisk.BaselineType.PREVIOUS_SCAN);
```

Add a separate test proving the warning triggers without weakening the prior hot direction, and an unreliable-coverage test:

```java
assertThat(risk.signals()).isNotEmpty();
assertThat(risk.summary()).contains("资金切换");
assertThat(unreliable.status()).isEqualTo(ShortTermLeaderRisk.Status.UNAVAILABLE);
assertThat(store.saved).isEmpty();
```

- [ ] **Step 2: Run the module test and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=DefaultShortTermLeaderRiskModuleTest test
```

Expected: compilation fails because the leader-risk module and DTOs do not exist.

- [ ] **Step 3: Define the public result and signal DTOs**

Use these exact public shapes:

```java
public record ShortTermLeaderRisk(
        String ruleVersion,
        Status status,
        BaselineType baselineType,
        Instant baselineAt,
        List<ShortTermLeaderRiskSignal> signals,
        String dominantCandidateIndustry,
        BigDecimal candidateConcentrationPercent,
        boolean directionConflict,
        String summary,
        String evidence,
        List<String> dataGaps,
        boolean advisoryOnly,
        Instant evaluatedAt
) {
    public enum Status { WARNING, CLEAR, BASELINE_BUILDING, UNAVAILABLE }
    public enum BaselineType { PREVIOUS_SCAN, PREVIOUS_TRADING_DAY, INITIAL }
}
```

```java
public record ShortTermLeaderRiskSignal(
        Track track,
        String symbol,
        String name,
        String direction,
        BigDecimal currentChangePercent,
        BigDecimal baselineChangePercent,
        BigDecimal changeDeltaPercentPoints,
        Integer currentAmountRank,
        Integer baselineAmountRank,
        BigDecimal amountSharePercent,
        BigDecimal totalMarketValue,
        String reason
) {
    public enum Track { WEIGHT, THEME }
}
```

Compact constructors copy lists, normalize nulls, and force `advisoryOnly=true`. Add `ShortTermLeaderRisk.unavailable(reason)` for old reports and fail-open paths.

- [ ] **Step 4: Define the one-method module interface and immutable input**

```java
public interface ShortTermLeaderRiskModule {
    ShortTermLeaderRisk evaluate(ShortTermLeaderRiskInput input);

    static ShortTermLeaderRiskModule unavailable() {
        return input -> ShortTermLeaderRisk.unavailable(
                "当前调用路径未配置龙头异动风险模块");
    }
}
```

```java
public record ShortTermLeaderRiskInput(
        LocalDate tradeDate,
        Instant capturedAt,
        ShortTermCoverageSnapshot coverage,
        List<EastMoneyQuote> quotes,
        List<ShortTermHotDirection> hotDirections,
        List<String> candidateIndustries
) {
    public ShortTermLeaderRiskInput {
        quotes = quotes == null ? List.of() : List.copyOf(quotes);
        hotDirections = hotDirections == null ? List.of() : List.copyOf(hotDirections);
        candidateIndustries = candidateIndustries == null ? List.of() : List.copyOf(candidateIndustries);
    }
}
```

- [ ] **Step 5: Define the internal compact snapshot seam**

`ShortTermLeaderSnapshot` contains rule version, snapshot id, date/time, at most 50 weight observations, and at most eight direction observations with three leaders each. The store interface is:

```java
interface ShortTermLeaderSnapshotStore {
    Optional<ShortTermLeaderSnapshot> latestSameDayBefore(
            String ruleVersion, LocalDate tradeDate, Instant capturedAt);
    Optional<ShortTermLeaderSnapshot> latestBeforeTradeDate(
            String ruleVersion, LocalDate tradeDate);
    void save(ShortTermLeaderSnapshot snapshot);
}
```

- [ ] **Step 6: Implement the sensitive detector behind the interface**

Centralize these exact first-version thresholds in `DefaultShortTermLeaderRiskModule`:

```java
static final String RULE_VERSION = "short-term-leader-risk-v1-sensitive";
private static final BigDecimal WEIGHT_MARKET_CAP_COHORT = new BigDecimal("0.05");
private static final BigDecimal WEIGHT_ACTIVE_AMOUNT_COHORT = new BigDecimal("0.10");
private static final BigDecimal WEIGHT_MIN_RISE = new BigDecimal("2.00");
private static final BigDecimal WEIGHT_MIN_ACCELERATION = new BigDecimal("1.00");
private static final int WEIGHT_MIN_RANK_IMPROVEMENT = 20;
private static final BigDecimal THEME_ACTIVE_AMOUNT_COHORT = new BigDecimal("0.15");
private static final BigDecimal THEME_MIN_RISE = new BigDecimal("4.00");
private static final BigDecimal THEME_MIN_ACCELERATION = new BigDecimal("1.50");
private static final int THEME_MIN_DIRECTION_RANK_IMPROVEMENT = 2;
private static final BigDecimal CONCENTRATED_CANDIDATE_PERCENT = new BigDecimal("50.00");
private static final int MAX_SIGNALS = 3;
```

The implementation order is fixed:

```java
public ShortTermLeaderRisk evaluate(ShortTermLeaderRiskInput input) {
    if (!reliable(input)) return unavailableCoverage(input);
    ShortTermLeaderSnapshot current = snapshot(input);
    Baseline baseline = baseline(current);
    ShortTermLeaderRisk risk = baseline.snapshot() == null
            ? baselineBuilding(current, input.candidateIndustries())
            : compare(current, baseline, input.candidateIndustries());
    snapshotStore.save(current);
    return risk;
}
```

Build amount ranks and shares from the current quote set. Parse leader symbols from existing `name(000001)` hot-direction entries, look them up in the same quote map, and never make a new external request. Trigger weight and theme rules exactly as specified in the design document, cap the sorted output at three, and set direction conflict only when candidate concentration is at least 50% and every signal direction differs.

- [ ] **Step 7: Run the module tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=DefaultShortTermLeaderRiskModuleTest test
```

Expected: all module scenarios pass with no errors.

- [ ] **Step 8: Commit the pure module**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/leader \
  apps/api/src/test/java/com/aistock/research/shortterm/leader
git commit -m "feat: detect short-term leader rotation risk"
```

---

### Task 3: Persist Compact Baselines with a JPA Adapter

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/JpaShortTermLeaderSnapshotStore.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/leader/JpaShortTermLeaderSnapshotStoreTest.java`

**Interfaces:**
- Implements: `ShortTermLeaderSnapshotStore` with production JPA/Jackson persistence.
- Produces: strict same-day-before and previous-trading-day lookup ordering.

- [ ] **Step 1: Write the failing persistence test**

Use `@SpringBootTest`, autowire the JPA store and repository, save one prior-day and two same-day snapshots, then assert:

```java
assertThat(store.latestSameDayBefore(RULE_VERSION, today, at1030))
        .get().extracting(ShortTermLeaderSnapshot::capturedAt)
        .isEqualTo(at1000);
assertThat(store.latestBeforeTradeDate(RULE_VERSION, today))
        .get().extracting(ShortTermLeaderSnapshot::tradeDate)
        .isEqualTo(yesterday);
assertThat(store.latestSameDayBefore("other-version", today, at1030)).isEmpty();
```

- [ ] **Step 2: Run the persistence test and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=JpaShortTermLeaderSnapshotStoreTest test
```

Expected: compilation or Spring context failure because the adapter/table does not exist.

- [ ] **Step 3: Add the H2/PostgreSQL-compatible table**

Append to `schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS short_term_leader_snapshot (
  snapshot_id VARCHAR(64) PRIMARY KEY,
  rule_version VARCHAR(80) NOT NULL,
  trade_date DATE NOT NULL,
  captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
  snapshot_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_short_term_leader_snapshot_baseline
  ON short_term_leader_snapshot(rule_version, trade_date, captured_at, snapshot_id);
```

- [ ] **Step 4: Add the entity, repository queries, and JSON adapter**

Repository methods:

```java
Optional<ShortTermLeaderSnapshotEntity>
findFirstByRuleVersionAndTradeDateAndCapturedAtLessThanOrderByCapturedAtDescSnapshotIdDesc(
        String ruleVersion, LocalDate tradeDate, Instant capturedAt);

Optional<ShortTermLeaderSnapshotEntity>
findFirstByRuleVersionAndTradeDateLessThanOrderByTradeDateDescCapturedAtDescSnapshotIdDesc(
        String ruleVersion, LocalDate tradeDate);
```

The adapter serializes/deserializes `ShortTermLeaderSnapshot` with the injected application `ObjectMapper`; Jackson failures throw an `IllegalStateException` containing the snapshot id. Annotate reads `@Transactional(readOnly = true)` and save `@Transactional`.

- [ ] **Step 5: Run persistence and Spring-context tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=JpaShortTermLeaderSnapshotStoreTest,ShortTermScheduledSnapshotStoreTest test
```

Expected: zero failures and zero errors; existing snapshot storage remains unaffected.

- [ ] **Step 6: Commit the persistence adapter**

```bash
git add apps/api/src/main/resources/schema.sql \
  apps/api/src/main/java/com/aistock/research/shortterm/leader \
  apps/api/src/test/java/com/aistock/research/shortterm/leader/JpaShortTermLeaderSnapshotStoreTest.java
git commit -m "feat: persist short-term leader baselines"
```

---

### Task 4: Attach the Warning to Short-Term Reports Without Touching Ranking

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`

**Interfaces:**
- Consumes: `ShortTermLeaderRiskModule.evaluate(ShortTermLeaderRiskInput)` after final candidates are already ordered.
- Produces: `ShortTermReport.leaderRisk()` as the last report field.

- [ ] **Step 1: Write failing report compatibility and integration tests**

Extend the old-JSON test:

```java
assertThat(report.leaderRisk().status())
        .isEqualTo(ShortTermLeaderRisk.Status.UNAVAILABLE);
assertThat(report.leaderRisk().advisoryOnly()).isTrue();
```

Add a module stub that captures its input and returns a fixed warning, then construct a service through a package-visible test constructor and assert:

```java
ShortTermReport report = leaderAwareService.report(ShortTermScanRequest.empty());

assertThat(report.leaderRisk()).isEqualTo(expectedRisk);
assertThat(capturedInput.get().candidateIndustries())
        .containsExactlyElementsOf(report.candidates().stream()
                .map(ShortTermCandidate::industry).toList());
assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
        .containsExactlyElementsOf(capturedCandidateOrder);
```

- [ ] **Step 2: Run the focused service tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest#oldStoredJsonDefaultsCoverageToExplicitlyUnreliable,ShortTermServiceTest#attachesLeaderRiskAfterCandidateOrderingWithoutChangingCandidates test
```

Expected: compilation fails because `leaderRisk` and module injection do not exist.

- [ ] **Step 3: Append `leaderRisk` with old-constructor compatibility**

Append this component to `ShortTermReport`:

```java
ShortTermLeaderRisk leaderRisk
```

In the compact constructor:

```java
leaderRisk = leaderRisk == null
        ? ShortTermLeaderRisk.unavailable("历史报告未包含龙头异动风险快照")
        : leaderRisk;
```

Add one overload matching the old full canonical constructor and delegate with the unavailable value. Leave all existing shorter constructors source-compatible.

- [ ] **Step 4: Inject and call the module after candidate ordering**

Add a final `ShortTermLeaderRiskModule` constructor dependency to the Spring constructor. Existing convenience constructors delegate with `ShortTermLeaderRiskModule.unavailable()`.

After `candidates` and `technicalReviewCoverage` are final, compute the cutoff once and call:

```java
ShortTermLeaderRisk leaderRisk = leaderRisk(
        decisionAt.toLocalDate(), reportDataCutoffAt, coverage,
        marketContextUniverse, hotDirections,
        candidates.stream().map(ShortTermCandidate::industry).toList()
);
```

The helper wraps the module call:

```java
try {
    return leaderRiskModule.evaluate(new ShortTermLeaderRiskInput(
            tradeDate, capturedAt, coverage, quotes, hotDirections, candidateIndustries));
} catch (RuntimeException exception) {
    logger.warn("短线龙头异动风险计算失败，本轮仅关闭风险提示", exception);
    return ShortTermLeaderRisk.unavailable("龙头异动风险计算失败：" + rootMessage(exception));
}
```

Pass `leaderRisk` only to the report constructor. Do not reference it from `preFilterExclusion`, `preliminaryScore`, `score`, ranking comparators, `applyCoverageExecutionGate`, `attachTradePlan`, or action logic.

- [ ] **Step 5: Run service and report-storage tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermScheduledSnapshotStoreTest test
```

Expected: the new tests and all old report JSON/constructor tests pass.

- [ ] **Step 6: Prove the result is not in the decision chain**

Run:

```bash
rg -n "leaderRisk|leaderRiskModule" apps/api/src/main/java/com/aistock/research/shortterm
```

Expected: references are limited to DTO defaults, module invocation, report construction, and the leader package; no scoring, filtering, ranking, action, or trade-plan method consumes the result.

- [ ] **Step 7: Commit report integration**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java \
  apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java
git commit -m "feat: attach leader risk to short-term reports"
```

---

### Task 5: Render a Prominent, Advisory-Only Risk Card

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Create: `apps/web-react/src/components/shortterm/ShortTermLeaderRiskCard.tsx`
- Create: `apps/web-react/src/components/shortterm/ShortTermLeaderRiskCard.test.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: optional `ShortTermReport.leaderRisk` for old-snapshot safety.
- Produces: an always-visible report-level card before the candidate list when the field exists.

- [ ] **Step 1: Write failing component tests**

Create a warning fixture and assert the user-visible evidence:

```typescript
const warning: ShortTermLeaderRisk = {
  ruleVersion: 'short-term-leader-risk-v1-sensitive',
  status: 'WARNING',
  baselineType: 'PREVIOUS_SCAN',
  baselineAt: '2026-08-21T01:30:00Z',
  signals: [{
    track: 'WEIGHT',
    symbol: '600000',
    name: '权重龙头',
    direction: '银行',
    currentChangePercent: 3.2,
    baselineChangePercent: 1.1,
    changeDeltaPercentPoints: 2.1,
    currentAmountRank: 8,
    baselineAmountRank: 42,
    amountSharePercent: 1.8,
    totalMarketValue: 245600000000,
    reason: '涨幅较上次扫描增加 2.10 个百分点'
  }],
  dominantCandidateIndustry: '化学制药',
  candidateConcentrationPercent: 75,
  directionConflict: true,
  summary: '候选方向集中且与新异动方向不同，需防范资金切换。',
  evidence: '基于本次全市场成交额排名和涨幅差分。',
  dataGaps: [],
  advisoryOnly: true,
  evaluatedAt: '2026-08-21T02:00:00Z'
}
```

Assertions:

```typescript
expect(document.body.textContent).toContain('龙头异动风险')
expect(document.body.textContent).toContain('盘中上次扫描')
expect(document.body.textContent).toContain('权重龙头')
expect(document.body.textContent).toContain('候选集中：化学制药 75.00%')
expect(document.body.textContent).toContain('仅作风险提示，不参与候选筛选、评分、排序或交易动作')
```

Add a second test for `BASELINE_BUILDING` that contains “基线建立中” and does not contain “已确认异动”.

- [ ] **Step 2: Run the component test and verify RED**

Run:

```bash
npm --prefix apps/web-react test -- --run src/components/shortterm/ShortTermLeaderRiskCard.test.tsx
```

Expected: compilation fails because the type and component do not exist.

- [ ] **Step 3: Add TypeScript DTOs**

```typescript
export type ShortTermLeaderRiskStatus = 'WARNING' | 'CLEAR' | 'BASELINE_BUILDING' | 'UNAVAILABLE'
export type ShortTermLeaderRiskBaseline = 'PREVIOUS_SCAN' | 'PREVIOUS_TRADING_DAY' | 'INITIAL'
export type ShortTermLeaderRiskTrack = 'WEIGHT' | 'THEME'

export interface ShortTermLeaderRiskSignal {
  track: ShortTermLeaderRiskTrack
  symbol: string
  name: string
  direction: string | null
  currentChangePercent: number | null
  baselineChangePercent: number | null
  changeDeltaPercentPoints: number | null
  currentAmountRank: number | null
  baselineAmountRank: number | null
  amountSharePercent: number | null
  totalMarketValue: number | null
  reason: string
}

export interface ShortTermLeaderRisk {
  ruleVersion: string
  status: ShortTermLeaderRiskStatus
  baselineType: ShortTermLeaderRiskBaseline
  baselineAt: string | null
  signals: ShortTermLeaderRiskSignal[]
  dominantCandidateIndustry: string | null
  candidateConcentrationPercent: number | null
  directionConflict: boolean
  summary: string
  evidence: string
  dataGaps: string[]
  advisoryOnly: boolean
  evaluatedAt: string | null
}
```

Add `leaderRisk?: ShortTermLeaderRisk | null` to `ShortTermReport`.

- [ ] **Step 4: Implement the card as a focused React component**

Use existing `Card`, `Tag`, `formatAmount`, `formatDateTime`, `formatPercent`, and `formatSignedPercent`. Map labels exactly:

```typescript
const baselineLabels = {
  PREVIOUS_SCAN: '盘中上次扫描',
  PREVIOUS_TRADING_DAY: '上一交易日',
  INITIAL: '基线建立中'
} as const
```

Render `WARNING` in amber with `aria-live="polite"`; render baseline-building in sky, clear in neutral/success, unavailable in muted neutral. Keep signal rows flat and readable; do not add nested modal state or new global state.

- [ ] **Step 5: Run component tests and verify GREEN**

Run:

```bash
npm --prefix apps/web-react test -- --run src/components/shortterm/ShortTermLeaderRiskCard.test.tsx
```

Expected: all component tests pass.

- [ ] **Step 6: Integrate the card and write a page regression test**

In `ShortTermPage`, render after the horizontal market summary and before methodology/candidates:

```tsx
<ShortTermLeaderRiskCard risk={report.leaderRisk} />
```

Extend `ShortTermPage.test.tsx` with a report containing `warning`, then assert the warning text appears and candidate buttons remain in their existing ranking-score order. Add an old report without `leaderRisk` and assert the page still renders `右侧候选` without throwing.

- [ ] **Step 7: Run the page tests and verify GREEN**

Run:

```bash
npm --prefix apps/web-react test -- --run src/pages/ShortTermPage.test.tsx
```

Expected: all page tests pass.

- [ ] **Step 8: Commit the frontend warning**

```bash
git add apps/web-react/src/types.ts \
  apps/web-react/src/components/shortterm/ShortTermLeaderRiskCard.tsx \
  apps/web-react/src/components/shortterm/ShortTermLeaderRiskCard.test.tsx \
  apps/web-react/src/pages/ShortTermPage.tsx \
  apps/web-react/src/pages/ShortTermPage.test.tsx
git commit -m "feat: show short-term leader rotation warning"
```

---

### Task 6: Full Verification and Local Runtime Refresh

**Files:**
- Verify: `apps/api`
- Verify: `apps/web-react`
- Rebuild: `docker-compose.yml` services `api` and `web`

**Interfaces:**
- Produces: tested host artifacts and healthy local containers running the new schema, report field, and frontend bundle.

- [ ] **Step 1: Run the complete backend suite**

Run:

```bash
mvn -pl apps/api test
```

Expected: Maven exits 0 with zero failures and zero errors.

- [ ] **Step 2: Run the complete React suite**

Run:

```bash
npm --prefix apps/web-react test -- --run
```

Expected: Vitest exits 0 with all test files and tests passing.

- [ ] **Step 3: Build the production frontend**

Run:

```bash
npm --prefix apps/web-react run build
```

Expected: TypeScript and Vite exit 0 and create `apps/web-react/dist`.

- [ ] **Step 4: Check diff integrity and requirement isolation**

Run:

```bash
git diff --check
rg -n "leaderRisk|leaderRiskModule" apps/api/src/main/java/com/aistock/research/shortterm
git status --short
```

Expected: no whitespace errors; leader-risk output is absent from scoring/filtering/ranking/action code; `.zcode/` remains untracked and unstaged.

- [ ] **Step 5: Rebuild and restart the local API and web containers**

Run:

```bash
docker compose up -d --build api web
docker compose ps api web
```

Expected: both containers are `Up`; API startup applies `short_term_leader_snapshot` idempotently.

- [ ] **Step 6: Verify live health and routes**

Run:

```bash
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsSI http://127.0.0.1:5176/
docker compose logs --tail=120 api web
```

Expected: API reports `UP`, web returns HTTP 200, and logs contain no schema-validation or frontend-startup failure.

- [ ] **Step 7: Commit design documents or final integration adjustments**

```bash
git add docs/superpowers/specs/2026-08-21-short-term-leader-risk-design.md \
  docs/superpowers/plans/2026-08-21-short-term-leader-risk.md
git commit -m "docs: define short-term leader risk warning"
```

Do not push unless the user explicitly asks for the remote update.

## Self-Review

- Spec coverage: both leader tracks, both baselines, sensitive triggers, initial-baseline honesty, candidate concentration context, fail-open behavior, display-only isolation, persistence, UI, compatibility, tests, and Docker refresh each map to a task.
- Placeholder scan: the plan contains no `TBD`, `TODO`, “implement later”, or unnamed handler. Each production edit is paired with a prior failing test and exact verification command.
- Type consistency: `ShortTermLeaderRisk`, `ShortTermLeaderRiskSignal`, `ShortTermLeaderRiskInput`, `ShortTermLeaderRiskModule`, `ShortTermLeaderSnapshot`, and `ShortTermLeaderSnapshotStore` names and fields are consistent across backend, persistence, report, and TypeScript steps.
