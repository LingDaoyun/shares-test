# Short-Term Golden-Cross Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an auditable MA5-over-MA10 two-layer golden-cross signal that prioritizes confirmed crosses, keeps approaching crosses watch-only, and never bypasses existing short-term risk gates.

**Architecture:** Put deterministic moving-average and crossing logic in a focused `ShortTermGoldenCrossAnalyzer`, then attach its snapshot to the existing short-term technical snapshot. `ShortTermService` consumes the snapshot for ranking, action gating, evidence, and coverage disclosure; V2 receives the same state for replay-safe decisions. React renders the nested snapshot and forwards it to the V2 compatibility request.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, AssertJ, Maven, React 18, TypeScript 5, Vitest, Vite, Docker Compose.

## Global Constraints

- Rule version is exactly `short-golden-cross-v1.0.0`.
- A confirmed cross is `MA5(t) > MA10(t)` and `MA5(t-1) <= MA10(t-1)` on completed daily bars.
- A confirmed cross is recent for zero through three completed trading bars after the cross.
- The signed spread is exactly `(MA5 - MA10) / MA10 * 100`; approaching is `-0.8% <= spread <= 0%`.
- `APPROACHING` and `FORMING` never emit legacy `ADD`, legacy `RIGHT_EARLY_ADD`, V2 `ADD`, or V2 `LIGHT_TRIAL`.
- Golden-cross priority never overrides market coverage, quote freshness, liquidity, financial floor, chase risk, market regime, evidence completeness, or tail confirmation.
- Missing or ambiguous K-line completion produces watch/data-review behavior, never inferred confirmation.
- Preserve backward-compatible JSON fields and all-A-share quote-universe rules.
- Disclose golden-cross coverage as K-line-reviewed coverage; do not call a bounded K-line sample a full-market golden-cross scan.
- Existing unrelated working-tree changes must not be reverted, overwritten, or included in a feature commit.

---

### Task 1: Deterministic Golden-Cross Domain Analyzer

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermGoldenCrossSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermGoldenCrossAnalyzer.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermGoldenCrossAnalyzerTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/trading/TradingClockService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/trading/TradingClockServiceTest.java`

**Interfaces:**
- Consumes: `EastMoneyKLine.tradeDate()` and `EastMoneyKLine.close()`.
- Produces: `ShortTermGoldenCrossSnapshot ShortTermGoldenCrossAnalyzer.analyze(List<EastMoneyKLine> source, boolean latestBarCompleted)`.
- Produces: `boolean TradingClockService.isCompletedDailyBar(LocalDate tradeDate)`.

- [ ] **Step 1: Write analyzer tests for every state**

Create `ShortTermGoldenCrossAnalyzerTest` with deterministic close sequences:

```java
class ShortTermGoldenCrossAnalyzerTest {
    private final ShortTermGoldenCrossAnalyzer analyzer = new ShortTermGoldenCrossAnalyzer();

    @Test
    void confirmsLatestCompletedMa5OverMa10Cross() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(0), true);
        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.tradingDaysSinceCross()).isZero();
        assertThat(result.priorityTier()).isEqualTo(3);
        assertThat(result.ma5Ma10SpreadPercent()).isPositive();
    }

    @Test
    void keepsCrossRecentThroughThreeCompletedBars() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(3), true);
        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.tradingDaysSinceCross()).isEqualTo(3);
    }

    @Test
    void classifiesOlderCrossAsEstablished() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(4), true);
        assertThat(result.state()).isEqualTo("ESTABLISHED");
        assertThat(result.priorityTier()).isEqualTo(1);
    }

    @Test
    void detectsNarrowingApproachWithoutCallingItConfirmed() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(approachingRows(), true);
        assertThat(result.state()).isEqualTo("APPROACHING");
        assertThat(result.spreadTrend()).isEqualTo("NARROWING");
        assertThat(result.ma5Ma10SpreadPercent())
                .isBetween(new BigDecimal("-0.80"), BigDecimal.ZERO);
    }

    @Test
    void marksCurrentUnfinishedBarCrossAsForming() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(crossRows(0), false);
        assertThat(result.state()).isEqualTo("FORMING");
        assertThat(result.priorityTier()).isEqualTo(2);
    }

    @Test
    void returnsUnavailableWhenTwentyBarsAreNotPresent() {
        ShortTermGoldenCrossSnapshot result = analyzer.analyze(rows(List.of("10", "10.1", "10.2")), true);
        assertThat(result.state()).isEqualTo("UNAVAILABLE");
        assertThat(result.evidenceStatus()).isEqualTo("UNAVAILABLE");
    }

    private List<EastMoneyKLine> crossRows(int barsAfterCross) {
        List<String> closes = new ArrayList<>(Collections.nCopies(20, "10.00"));
        closes.add("10.50");
        for (int index = 0; index < barsAfterCross; index++) {
            closes.add(new BigDecimal("10.55")
                    .add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index))).toPlainString());
        }
        return rows(closes);
    }

    private List<EastMoneyKLine> approachingRows() {
        List<String> closes = new ArrayList<>(Collections.nCopies(15, "10.50"));
        closes.addAll(Collections.nCopies(5, "10.00"));
        closes.addAll(List.of("10.10", "10.20", "10.30"));
        return rows(closes);
    }

    private List<EastMoneyKLine> rows(List<String> closes) {
        LocalDate start = LocalDate.parse("2026-01-01");
        return IntStream.range(0, closes.size())
                .mapToObj(index -> {
                    BigDecimal close = new BigDecimal(closes.get(index));
                    return new EastMoneyKLine("600001", start.plusDays(index), close, close,
                            close, close, new BigDecimal("100000"), null);
                })
                .toList();
    }
}
```

- [ ] **Step 2: Run the analyzer test and verify the missing-type failure**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermGoldenCrossAnalyzerTest test
```

Expected: FAIL because the analyzer and snapshot do not exist.

- [ ] **Step 3: Add the immutable snapshot contract**

Create `ShortTermGoldenCrossSnapshot` with this public contract:

```java
public record ShortTermGoldenCrossSnapshot(
        String ruleVersion,
        String state,
        String stateLabel,
        LocalDate crossDate,
        Integer tradingDaysSinceCross,
        BigDecimal ma5Ma10SpreadPercent,
        String spreadTrend,
        String maAlignment,
        int priorityTier,
        String evidenceStatus
) {
    public static final String RULE_VERSION = "short-golden-cross-v1.0.0";

    public static ShortTermGoldenCrossSnapshot unavailable() {
        return new ShortTermGoldenCrossSnapshot(
                RULE_VERSION, "UNAVAILABLE", "金叉数据不足", null, null, null,
                "UNAVAILABLE", "UNAVAILABLE", 0, "UNAVAILABLE");
    }

    public boolean confirmedRecent() {
        return "CONFIRMED".equals(state)
                && tradingDaysSinceCross != null
                && tradingDaysSinceCross >= 0
                && tradingDaysSinceCross <= 3;
    }

    public boolean watchLayer() {
        return "APPROACHING".equals(state) || "FORMING".equals(state);
    }
}
```

- [ ] **Step 4: Implement the analyzer without external state**

Implement `ShortTermGoldenCrossAnalyzer` as a package-local `@Component`. Classification order is `UNAVAILABLE`, unfinished-bar `FORMING`, completed-bar `CONFIRMED`, `ESTABLISHED`, `APPROACHING`, then `NONE`.

Use these exact calculations:

```java
private boolean crossedAt(List<EastMoneyKLine> rows, int index) {
    if (index < 10) return false;
    BigDecimal currentMa5 = movingAverageAt(rows, index, 5);
    BigDecimal currentMa10 = movingAverageAt(rows, index, 10);
    BigDecimal previousMa5 = movingAverageAt(rows, index - 1, 5);
    BigDecimal previousMa10 = movingAverageAt(rows, index - 1, 10);
    return currentMa5 != null && currentMa10 != null
            && previousMa5 != null && previousMa10 != null
            && currentMa5.compareTo(currentMa10) > 0
            && previousMa5.compareTo(previousMa10) <= 0;
}

private BigDecimal spreadAt(List<EastMoneyKLine> rows, int index) {
    BigDecimal ma5 = movingAverageAt(rows, index, 5);
    BigDecimal ma10 = movingAverageAt(rows, index, 10);
    if (ma5 == null || ma10 == null || ma10.signum() == 0) return null;
    return ma5.subtract(ma10).multiply(new BigDecimal("100"))
            .divide(ma10, 4, RoundingMode.HALF_UP);
}
```

`APPROACHING` requires the latest signed spread in `[-0.80, 0]`, positive three-bar MA5 slope, and strictly narrowing absolute spreads across the latest three observations. Use these exact labels:

```java
private String stateLabel(String state) {
    return switch (state) {
        case "CONFIRMED" -> "金叉已确认";
        case "APPROACHING" -> "临界交汇";
        case "FORMING" -> "金叉形成中";
        case "ESTABLISHED" -> "多头延续";
        case "NONE" -> "尚未交汇";
        default -> "金叉数据不足";
    };
}
```

Alignment is `BULLISH_STACK` when `MA5 > MA10 > MA20`, `MA5_ABOVE_MA10` when only `MA5 > MA10`, `CONVERGING` for an approaching cross, `BEARISH` for a valid non-converging bearish arrangement, and `UNAVAILABLE` when required averages are missing.

- [ ] **Step 5: Add trading-clock tests and implementation**

In `TradingClockServiceTest` add:

```java
@Test
void completesCurrentDailyBarOnlyAtOrAfterRegularClose() {
    LocalDate tradeDate = LocalDate.parse("2026-07-07");
    TradingClockService beforeClose = serviceAt("2026-07-07T06:59:00Z");
    TradingClockService afterClose = serviceAt("2026-07-07T07:00:01Z");
    assertThat(beforeClose.isCompletedDailyBar(tradeDate)).isFalse();
    assertThat(afterClose.isCompletedDailyBar(tradeDate)).isTrue();
    assertThat(beforeClose.isCompletedDailyBar(tradeDate.minusDays(1))).isTrue();
    assertThat(afterClose.isCompletedDailyBar(tradeDate.plusDays(1))).isFalse();
}
```

Implement `isCompletedDailyBar` using the service clock and `REGULAR_CLOSE`; a same-date bar is complete only when Shanghai time is not before 15:00.

- [ ] **Step 6: Run focused domain tests**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermGoldenCrossAnalyzerTest,TradingClockServiceTest test
```

Expected: PASS with no failures.

- [ ] **Step 7: Checkpoint domain files**

Run `git diff --check` on the five Task 1 files. Do not commit already-dirty files if doing so would include pre-existing unrelated changes.

---

### Task 2: Integrate Golden-Cross Tiers into the Legacy Short-Term Report

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: analyzer and trading-clock completion method from Task 1.
- Produces: `ShortTermTechnicalSnapshot.goldenCross()` in the existing report JSON.
- Produces: tier-first ranking and confirmed-cross execution gating.

- [ ] **Step 1: Write failing service tests**

Add tests proving this behavior:

```java
assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
        .containsExactly("600501", "600502", "600503");
assertThat(find(report, "600501").technical().goldenCross().state()).isEqualTo("CONFIRMED");
assertThat(find(report, "600502").technical().goldenCross().state()).isEqualTo("APPROACHING");
assertThat(find(report, "600502").todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
assertThat(overextendedConfirmed.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
assertThat(report.quoteNote()).contains(
        "金叉K线复核 " + report.klineReviewedCount() + "/" + report.reviewedCount());
```

Use separate fixtures; do not silently change unrelated fixture meanings:

```java
private List<EastMoneyKLine> recentGoldenCrossKLines(String symbol, int barsAfterCross) {
    List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(20, new BigDecimal("10.00")));
    closes.add(new BigDecimal("10.50"));
    for (int index = 0; index < barsAfterCross; index++) {
        closes.add(new BigDecimal("10.55").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index))));
    }
    return klineRows(symbol, closes);
}

private List<EastMoneyKLine> approachingGoldenCrossKLines(String symbol) {
    List<BigDecimal> closes = new ArrayList<>(Collections.nCopies(15, new BigDecimal("10.50")));
    closes.addAll(Collections.nCopies(5, new BigDecimal("10.00")));
    closes.addAll(List.of(new BigDecimal("10.10"), new BigDecimal("10.20"), new BigDecimal("10.30")));
    return klineRows(symbol, closes);
}

private List<EastMoneyKLine> establishedRightSideKLines(String symbol) {
    return recentGoldenCrossKLines(symbol, 4);
}

private List<EastMoneyKLine> klineRows(String symbol, List<BigDecimal> closes) {
    LocalDate start = LocalDate.parse("2026-06-01");
    return IntStream.range(0, closes.size())
            .mapToObj(index -> kline(symbol, start.plusDays(index), closes.get(index), "180000"))
            .toList();
}
```

- [ ] **Step 2: Run focused service tests and verify failure**

Run `mvn -pl apps/api -Dtest=ShortTermServiceTest test`.

Expected: FAIL because `goldenCross()` and tiered ranking do not exist.

- [ ] **Step 3: Attach the nested snapshot and analyzer**

Append `ShortTermGoldenCrossSnapshot goldenCross` to `ShortTermTechnicalSnapshot`. The insufficient-K-line construction uses `ShortTermGoldenCrossSnapshot.unavailable()`.

Inject `ShortTermGoldenCrossAnalyzer` through the autowired `ShortTermService` constructor while preserving convenience constructors. For valid K-lines use:

```java
boolean latestBarCompleted = tradingClockService.isCompletedDailyBar(last.tradeDate());
ShortTermGoldenCrossSnapshot goldenCross = goldenCrossAnalyzer.analyze(sorted, latestBarCompleted);
```

- [ ] **Step 4: Make cross state an explicit right-side and score input**

Pass `goldenCross` to `rightSideSignal`. Preserve existing signal labels, but allow `右侧早期观察` for an approaching cross only when price is above MA20, MA20 is flat/improving, position is valid, and price remains near MA20.

Replace the unconditional `MA5 >= MA10` bonus with:

```java
score = score.add(switch (snapshot.goldenCross().state()) {
    case "CONFIRMED" -> new BigDecimal("14");
    case "APPROACHING" -> new BigDecimal("8");
    case "FORMING" -> new BigDecimal("6");
    case "ESTABLISHED" -> new BigDecimal("4");
    default -> BigDecimal.ZERO;
});
```

- [ ] **Step 5: Put eligible cross tier before ordinary structure ranking**

Add:

```java
private int goldenCrossTechnicalPriority(TechnicalCandidate candidate) {
    return candidate.technical().snapshot().goldenCross().priorityTier();
}

private int eligibleGoldenCrossPriority(ShortTermCandidate candidate) {
    int tier = candidate.technical().goldenCross().priorityTier();
    if (tier == 3 && Set.of("RIGHT_EARLY_ADD", "WATCH_RIGHT_SIDE").contains(candidate.action())) return 3;
    if (tier == 2 && !Set.of("DATA_REVIEW", "MARKET_RISK_WAIT", "VALUATION_REVIEW").contains(candidate.action())) return 2;
    return Math.min(tier, 1);
}
```

Use technical priority before technical/volume score in K-line pre-ranking. Use eligible priority before structure priority, action priority, and final score in final ranking.

- [ ] **Step 6: Require a completed recent cross for executable legacy advice**

In `decide`, require:

```java
boolean confirmedRecentGoldenCross = technical.goldenCross() != null
        && technical.goldenCross().confirmedRecent();
```

The `RIGHT_EARLY_ADD` branch requires this boolean. Add an explicit watch branch for `APPROACHING` and `FORMING`. Keep market, coverage, freshness, valuation, fundamentals, volume, and chase checks authoritative.

- [ ] **Step 7: Add evidence, counter-evidence, and coverage copy**

Add a `均线金叉` evidence item containing state, cross date, days, signed spread, trend, alignment, tier, and version. Add risks for forming, approaching, unavailable, and overextended confirmed crosses. Extend `quoteNote` with:

```text
金叉K线复核 {klineReviewedCount}/{reviewedCount}；金叉排序只代表已复核样本，不代表全市场每只股票均已计算。
```

- [ ] **Step 8: Run the legacy short-term regression suite**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermGoldenCrossAnalyzerTest,ShortTermServiceTest,ShortTermScanJobServiceTest test
```

Expected: PASS, including shrinking-rise, hot-sector, freshness, coverage, tail, and post-close tests.

- [ ] **Step 9: Checkpoint the legacy integration diff**

Run `git diff --check` on the three modified files. Do not commit the already-dirty service or test wholesale.

---

### Task 3: Enforce the Same Cross Gate in V2 and Replay Evidence

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/v2/strategy/ShortRightSideStrategyInput.java`
- Modify: `apps/api/src/main/java/com/aistock/research/v2/strategy/ShortRightSideStrategyService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalController.java`
- Modify: `apps/api/src/test/java/com/aistock/research/v2/strategy/ShortRightSideStrategyServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java`

**Interfaces:**
- Consumes: cross state, days since cross, and priority tier from the legacy candidate/React request.
- Produces: replay payload key `goldenCross` and strategy version `short-right-side-v2.1.0`.

- [ ] **Step 1: Add failing V2 action and replay tests**

Add this helper and assertions:

```java
private ShortRightSideStrategyInput inputWithCross(String state, Integer days, int tier) {
    return new ShortRightSideStrategyInput(
            "000977",
            "浪潮信息",
            "AI算力",
            Instant.parse("2026-07-20T07:20:00Z"),
            Instant.parse("2026-07-20T07:19:30Z"),
            "POST_CLOSE_1520",
            new BigDecimal("84"),
            new BigDecimal("82"),
            new BigDecimal("86"),
            new BigDecimal("74"),
            new BigDecimal("81"),
            new BigDecimal("70"),
            new BigDecimal("86"),
            new BigDecimal("32"),
            state,
            days,
            tier,
            List.of());
}
```

```java
assertThat(service.evaluate(inputWithCross("APPROACHING", null, 2)).action())
        .isEqualTo(StrategyAction.WAIT);
assertThat(service.evaluate(inputWithCross("FORMING", 0, 2)).action())
        .isEqualTo(StrategyAction.WAIT);
assertThat(service.evaluate(inputWithCross("CONFIRMED", 2, 3)).action())
        .isEqualTo(StrategyAction.ADD);
assertThat(service.evaluate(inputWithCross("CONFIRMED", 4, 1)).action())
        .isEqualTo(StrategyAction.WAIT);
assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 3)).replayPayload())
        .containsKey("goldenCross");
```

Add a controller assertion for version `short-right-side-v2.1.0` and the three replay values.

- [ ] **Step 2: Run V2 tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=ShortRightSideStrategyServiceTest,V2SignalControllerTest test
```

Expected: FAIL because V2 has no golden-cross input or gate.

- [ ] **Step 3: Extend the V2 input compatibly**

Add canonical record components before `riskFlags`:

```java
String goldenCrossState,
Integer goldenCrossTradingDays,
Integer goldenCrossPriorityTier,
```

Keep an overloaded constructor with the old signature that supplies `"NONE", null, 0`. Normalize blank state to `NONE` and null tier to zero in the compact constructor.

- [ ] **Step 4: Gate executable V2 actions and update scoring**

Update the strategy version to `short-right-side-v2.1.0`. Add:

```java
private boolean confirmedRecentGoldenCross(ShortRightSideStrategyInput input) {
    return "CONFIRMED".equals(input.goldenCrossState())
            && input.goldenCrossTradingDays() != null
            && input.goldenCrossTradingDays() >= 0
            && input.goldenCrossTradingDays() <= 3
            && input.goldenCrossPriorityTier() >= 3;
}
```

Return `WAIT` before `ADD` or `LIGHT_TRIAL` when this method is false. Rebalance positive rank weights to market 16%, right-side 24%, absorption 16%, volume breakout 10%, shrinking rise 10%, fundamentals 8%, liquidity 6%, golden cross 10%; preserve the 18% crowding penalty. Map tiers to scores as `3 -> 100`, `2 -> 70`, `1 -> 45`, otherwise `0`.

- [ ] **Step 5: Add V2 context and replay fields**

Add context keys `goldenCrossState`, `goldenCrossTradingDays`, `goldenCrossPriorityTier`, and `goldenCrossRuleVersion`. Add replay payload:

```java
payload.put("goldenCross", Map.of(
        "state", input.goldenCrossState(),
        "tradingDays", input.goldenCrossTradingDays() == null ? -1 : input.goldenCrossTradingDays(),
        "priorityTier", input.goldenCrossPriorityTier(),
        "ruleVersion", ShortTermGoldenCrossSnapshot.RULE_VERSION
));
```

Evidence must explain why approaching, forming, or old crosses cannot trigger an executable action.

- [ ] **Step 6: Extend controller request parameters**

Add:

```java
@RequestParam(defaultValue = "NONE") String goldenCrossState,
@RequestParam(required = false) Integer goldenCrossTradingDays,
@RequestParam(defaultValue = "0") Integer goldenCrossPriorityTier
```

Forward them through `defaultShortTermInput` and update `versionOf(SHORT_RIGHT_SIDE)` to `short-right-side-v2.1.0`.

- [ ] **Step 7: Run V2 tests**

Run:

```bash
mvn -pl apps/api -Dtest=ShortRightSideStrategyServiceTest,V2SignalControllerTest,StrategyValidationGateTest test
```

Expected: PASS with V2 `ADD` and `LIGHT_TRIAL` allowed only for confirmed recent crosses.

- [ ] **Step 8: Checkpoint V2 changes**

Run `git diff --check` on the five V2 files. They were already untracked/modified before this feature, so leave them unstaged unless the user later approves an aggregate commit.

---

### Task 4: Render Cross State and Forward It to V2

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Create: `apps/web-react/src/lib/shortTermGoldenCross.ts`
- Create: `apps/web-react/src/lib/shortTermGoldenCross.test.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`

**Interfaces:**
- Consumes: nested `technical.goldenCross` from Task 2.
- Produces: visible status, metrics, counter-evidence, and V2 request parameters from Task 3.

- [ ] **Step 1: Write failing view-model tests**

Create `shortTermGoldenCross.test.ts`:

```typescript
const snapshot = (state: ShortTermGoldenCrossSnapshot['state']): ShortTermGoldenCrossSnapshot => ({
  ruleVersion: 'short-golden-cross-v1.0.0',
  state,
  stateLabel: goldenCrossLabel(state),
  crossDate: state === 'CONFIRMED' ? '2026-07-22' : null,
  tradingDaysSinceCross: state === 'CONFIRMED' ? 0 : null,
  ma5Ma10SpreadPercent: state === 'APPROACHING' ? -0.35 : 0.22,
  spreadTrend: 'NARROWING',
  maAlignment: state === 'APPROACHING' ? 'CONVERGING' : 'BULLISH_STACK',
  priorityTier: state === 'CONFIRMED' ? 3 : state === 'APPROACHING' ? 2 : 0,
  evidenceStatus: 'COMPLETE'
})

describe('short-term golden-cross view model', () => {
  it('labels confirmed and approaching states distinctly', () => {
    expect(goldenCrossLabel('CONFIRMED')).toBe('金叉已确认')
    expect(goldenCrossLabel('APPROACHING')).toBe('临界交汇')
  })

  it('keeps forming and unavailable states warning-oriented', () => {
    expect(goldenCrossTone('FORMING')).toBe('warning')
    expect(goldenCrossTone('UNAVAILABLE')).toBe('neutral')
  })

  it('explains why approaching cannot be an add action', () => {
    expect(goldenCrossCounterEvidence(snapshot('APPROACHING'))).toContain('尚未完成上穿')
  })
})
```

- [ ] **Step 2: Run Vitest and verify missing-helper failure**

From `apps/web-react`, run `npm test -- shortTermGoldenCross.test.ts`.

Expected: FAIL because the helper module does not exist.

- [ ] **Step 3: Add TypeScript contracts and presentation helpers**

Add this contract and append `goldenCross` to `ShortTermTechnicalSnapshot`:

```typescript
export type ShortTermGoldenCrossState =
  | 'NONE'
  | 'APPROACHING'
  | 'FORMING'
  | 'CONFIRMED'
  | 'ESTABLISHED'
  | 'UNAVAILABLE'

export interface ShortTermGoldenCrossSnapshot {
  ruleVersion: string
  state: ShortTermGoldenCrossState
  stateLabel: string
  crossDate: string | null
  tradingDaysSinceCross: number | null
  ma5Ma10SpreadPercent: number | null
  spreadTrend: 'NARROWING' | 'WIDENING' | 'FLAT' | 'UNAVAILABLE'
  maAlignment: 'BEARISH' | 'CONVERGING' | 'MA5_ABOVE_MA10' | 'BULLISH_STACK' | 'UNAVAILABLE'
  priorityTier: number
  evidenceStatus: 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE'
}
```

Extend `V2StrategyBundleParams` with:

```typescript
goldenCrossState?: string
goldenCrossTradingDays?: number
goldenCrossPriorityTier?: number
```

Implement exhaustive label, tone, and counter-evidence functions. Missing data returns `金叉数据不足`, `neutral`, and a data-gap explanation, not numeric zero.

- [ ] **Step 4: Add compact candidate-row status**

Immediately after the right-side tag render:

```tsx
<Tag tone={goldenCrossTone(candidate.technical.goldenCross.state)}>
  {candidate.technical.goldenCross.stateLabel}
  {candidate.technical.goldenCross.tradingDaysSinceCross != null
    ? ` · ${candidate.technical.goldenCross.tradingDaysSinceCross}日`
    : ''}
</Tag>
```

Do not add a nested card. Preserve current row dimensions and wrapping.

- [ ] **Step 5: Add full detail metrics and counter-evidence**

Add detail metrics for cross date, signed spread, spread trend, alignment, priority tier, and rule version. Add a compact warning line from `goldenCrossCounterEvidence` only when state is not `CONFIRMED`.

- [ ] **Step 6: Forward real cross state to V2**

Extend `shortTermFactorContext`:

```typescript
goldenCrossState: candidate.technical.goldenCross.state,
goldenCrossTradingDays: candidate.technical.goldenCross.tradingDaysSinceCross ?? undefined,
goldenCrossPriorityTier: candidate.technical.goldenCross.priorityTier,
```

The generic query serializer already forwards these fields.

- [ ] **Step 7: Run frontend tests and production build**

From `apps/web-react`, run:

```bash
npm test
npm run build
```

Expected: all Vitest tests pass and TypeScript/Vite exits zero.

- [ ] **Step 8: Checkpoint frontend changes**

Run `git diff --check` on the four frontend files. Do not stage the already-dirty `types.ts` and `ShortTermPage.tsx` wholesale.

---

### Task 5: Full Regression, Runtime Contract, and Browser Verification

**Files:**
- Modify: `README.md` only if the visible methodology description lacks the two-layer golden-cross rule.
- Verify: `docker-compose.yml`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermController.java`

**Interfaces:**
- Consumes: all prior task outputs.
- Produces: verified API on `19080` and UI on `5176`.

- [ ] **Step 1: Run the complete backend test suite**

Run `mvn -pl apps/api test`.

Expected: BUILD SUCCESS with zero test failures.

- [ ] **Step 2: Run complete frontend verification**

From `apps/web-react`, run `npm test` and then `npm run build`.

Expected: all tests pass and production bundle builds.

- [ ] **Step 3: Inspect aggregate diff for scope expansion**

Run `git diff --check` and `git status --short`. Confirm no unrelated file was reverted, and distinguish pre-existing dirty paths from feature-created paths in the completion report.

- [ ] **Step 4: Rebuild and restart application containers**

Run:

```bash
mvn -pl apps/api -DskipTests package
docker compose build --no-cache api web
docker compose up -d api web
```

Expected: `ai-stock-api` and `ai-stock-web` start without the optional infrastructure profile.

- [ ] **Step 5: Verify health and API contract**

Run:

```bash
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS --max-time 180 'http://127.0.0.1:19080/api/short-term/report?limit=3&scanLimit=100&klineLimit=20'
```

Expected: health is `{"status":"UP"}`. Every candidate has `technical.goldenCross`; `quoteNote` discloses K-line cross coverage; approaching/forming candidates have no executable add advice.

- [ ] **Step 6: Verify the visible short-term page**

Open `http://127.0.0.1:5176/#/short-term`, trigger a scan, and inspect desktop and mobile widths. Verify confirmed candidates precede comparable approaching candidates; rows do not overflow; details show date, days, spread, alignment, tier, version, and counter-evidence; approaching/forming candidates are watch-only.

- [ ] **Step 7: Report validation status honestly**

Report the implementation as rule-complete and regression-tested, but keep win-rate status `EXPERIMENTAL_LIGHT_TRIAL` until recommendation history supplies an out-of-sample sample. Do not claim that a golden cross guarantees profit.

---

## Working-Tree Safety

The repository already contains unrelated uncommitted and untracked strategy work, including shared files required by this feature. Execution must work with that state and must not reset, stash, or commit those changes. New files may be committed separately only when their commit does not depend on unstaged shared-file changes; otherwise leave the complete feature unstaged and report exact changed paths.

## Final Verification Matrix

| Requirement | Verification |
|---|---|
| True recent cross | `ShortTermGoldenCrossAnalyzerTest` |
| Approaching and forming are watch-only | Analyzer, legacy service, and V2 tests |
| Golden-cross priority ordering | `ShortTermServiceTest` |
| Existing risk gates remain authoritative | Legacy service and V2 tests |
| Replay-safe versioned evidence | V2 service/controller tests |
| Missing data is explicit | Analyzer and frontend helper tests |
| UI is readable | TypeScript build plus desktop/mobile browser check |
| Runtime works | Docker health, API smoke scan, visible page scan |
