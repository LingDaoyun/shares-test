# Short-Term Scheduled Overnight Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Precompute and persist the current trading day's short-term result before 14:55, load it immediately on page entry, and attach deterministic T+1/T+2 overnight trading discipline to every executable candidate.

**Architecture:** A database-backed scheduled snapshot store separates durable daily results from the existing in-memory manual job queue. A two-stage scheduler runs a 14:30 full-market preselection and a 14:48 restricted final refresh, while a 14:54 guard blocks stale or incomplete results. The React page reads only the latest same-day snapshot on mount; manual recalculation remains explicit.

**Tech Stack:** Java 17, Spring Boot 3.3, Spring Scheduling, Spring Data JPA, Jackson, H2/PostgreSQL, Nacos Config, React 18, TypeScript, Vite, Vitest, Tailwind CSS, Docker Compose.

## Global Constraints

- Ordinary A-share executable entry time is `14:45-14:56`; data from `14:57-15:00` may validate history but cannot create a 14:55 recommendation.
- Scheduled times are `14:30` preselection, `14:48` final refresh, `14:53:59` final deadline, and `14:54` readiness guard in `Asia/Shanghai`.
- `NO_TRADE` is a successful empty result; `DATA_BLOCKED` is a data-quality failure. Neither may be converted into a forced recommendation.
- The latest endpoint never searches a previous trading date for an executable result.
- A recommendation is not a user position unless a fill exists in the trade-feedback ledger.
- Market coverage below the existing `90%` execution threshold blocks actionable advice.
- Scheduled work uses a dedicated single-worker executor and cannot be rejected by the manual scan queue.
- Default maximum position is one third of the short-term allocation, not one third of the total portfolio.
- Nacos settings belong in the base `ai-stock-api.yml` data ID; API keys and current user configuration must not be overwritten.
- Preserve the existing full-width list and explicit `DetailOverlay` interaction; do not restore a right-side split pane or auto-select the first stock.
- The worktree already contains unrelated user changes. Stage only files owned by each task and never revert unrelated edits.

---

### Task 1: Persist Scheduled Snapshot State

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermSnapshotStage.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermSnapshotStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotStore.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotStoreTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`, `ShortTermReport`, Spring Data JPA.
- Produces: `boolean claim(...)`, `ShortTermScheduledSnapshot finish(...)`, `ShortTermScheduledSnapshot fail(...)`, `Optional<ShortTermScheduledSnapshot> latest(LocalDate)`, exact stage/fingerprint lookup, and `ShortTermScheduledSnapshot.waiting(...)`.

- [ ] **Step 1: Write failing persistence tests**

Cover deterministic claiming, atomic terminal publication, one failed-run reclaim with an incremented attempt count, JSON round-trip, same-day latest ordering, and the prohibition on previous-date fallback.

```java
@SpringBootTest
class ShortTermScheduledSnapshotStoreTest {
    @Autowired ShortTermScheduledSnapshotStore store;
    @Autowired ShortTermScheduledSnapshotRepository repository;

    @AfterEach void clean() { repository.deleteAll(); }

    @Test
    void claimsOneRunKeyAndPublishesReportAtomically() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant started = Instant.parse("2026-07-23T06:48:00Z");
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started)).isTrue();
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started.plusSeconds(1))).isFalse();

        ShortTermScheduledSnapshot saved = store.finish(
                date, FINAL, "rules-v1", FINAL_READY, sampleReport(),
                Instant.parse("2026-07-23T06:52:30Z"),
                Instant.parse("2026-07-23T06:53:00Z"),
                "尾盘最终结果已就绪", List.of());

        assertThat(saved.report()).isNotNull();
        assertThat(store.latest(date)).get().extracting(ShortTermScheduledSnapshot::status)
                .isEqualTo(FINAL_READY);
        assertThat(store.latest(date.minusDays(1))).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test and verify failure**

Run: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest test`

Expected: compilation failure because the scheduled snapshot types do not exist.

- [ ] **Step 3: Add the schema and domain enums**

```sql
CREATE TABLE IF NOT EXISTS short_term_scheduled_snapshot (
  snapshot_key VARCHAR(160) PRIMARY KEY,
  trade_date DATE NOT NULL,
  stage VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  parameter_fingerprint VARCHAR(64) NOT NULL,
  parameters_json TEXT NOT NULL,
  report_json TEXT,
  data_cutoff_at TIMESTAMP WITH TIME ZONE,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  attempt_count INTEGER NOT NULL,
  message VARCHAR(1000) NOT NULL,
  blocked_reason VARCHAR(2000),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_short_term_snapshot_latest
  ON short_term_scheduled_snapshot(trade_date, updated_at, snapshot_key);
```

Use these exact enum values:

```java
public enum ShortTermSnapshotStage { PRESELECT, FINAL, READINESS_GUARD, MANUAL }

public enum ShortTermSnapshotStatus {
    RUNNING, PRESELECT_READY, FINAL_READY, NO_TRADE, DATA_BLOCKED, FAILED
}
```

- [ ] **Step 4: Implement the persistence boundary**

```java
public record ShortTermScheduledSnapshot(
        String snapshotKey,
        LocalDate tradeDate,
        ShortTermSnapshotStage stage,
        ShortTermSnapshotStatus status,
        String parameterFingerprint,
        Instant dataCutoffAt,
        Instant startedAt,
        Instant completedAt,
        String message,
        List<String> blockedReasons,
        ShortTermReport report
) {
    public static ShortTermScheduledSnapshot waiting(LocalDate tradeDate, String message) {
        return new ShortTermScheduledSnapshot(
                tradeDate + ":PRESELECT:WAITING", tradeDate, PRESELECT, RUNNING,
                "waiting", null, null, null, message, List.of(), null);
    }
}
```

```java
public interface ShortTermScheduledSnapshotRepository
        extends JpaRepository<ShortTermScheduledSnapshotEntity, String> {
    Optional<ShortTermScheduledSnapshotEntity>
            findFirstByTradeDateOrderByUpdatedAtDescSnapshotKeyDesc(LocalDate tradeDate);
}
```

`claim` builds `date + ":" + stage + ":" + fingerprint`, inserts `RUNNING`, and returns `false` on `DataIntegrityViolationException`. `finish` loads that exact row and sets terminal status, report JSON, cutoff, completion time, message, and blocked reasons in one `@Transactional` method. Serialize blocked reasons as JSON.

On a duplicate key, `claim` may atomically change only `FAILED -> RUNNING` and increment `attempt_count`. It must return `false` for `RUNNING`, `PRESELECT_READY`, `FINAL_READY`, `NO_TRADE`, and `DATA_BLOCKED`. Implement the conditional transition as a repository `@Modifying` update so two API instances cannot both reclaim it.

- [ ] **Step 5: Run persistence and schema tests**

Run: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test`

Expected: PASS; duplicate claim returns false and a report round-trips through Jackson.

- [ ] **Step 6: Commit Task 1**

```bash
git add apps/api/src/main/resources/schema.sql \
  apps/api/src/main/java/com/aistock/research/shortterm/schedule \
  apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotStoreTest.java
git commit -m "feat: persist scheduled short-term snapshots"
```

---

### Task 2: Make Timing and Nacos Settings Authoritative

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettings.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/OvernightRuleSet.java`
- Modify: `apps/api/src/main/java/com/aistock/research/trading/TradingClockService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/v2/strategy/ShortRightSideStrategyService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/trading/TradingClockServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/v2/strategy/ShortRightSideStrategyServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettingsTest.java`

**Interfaces:**
- Consumes: Spring `Environment`, existing `TradingClockService` holiday rules.
- Produces: refresh-by-read Nacos values, `TAIL_ENTRY_1445_1456`, and next-trading-day helpers.

- [ ] **Step 1: Write failing settings and clock tests**

```java
@Test
void exposesExecutableCheckpointOnlyBeforeClosingAuction() {
    assertThat(serviceAt("2026-07-23T06:44:59Z").shortTermDecisionCheckpoint())
            .startsWith("NOT_CONFIRMED:");
    assertThat(serviceAt("2026-07-23T06:45:00Z").shortTermDecisionCheckpoint())
            .isEqualTo("TAIL_ENTRY_1445_1456");
    assertThat(serviceAt("2026-07-23T06:56:59Z").shortTermDecisionCheckpoint())
            .isEqualTo("TAIL_ENTRY_1445_1456");
    assertThat(serviceAt("2026-07-23T06:57:00Z").shortTermDecisionCheckpoint())
            .startsWith("NOT_CONFIRMED:");
    assertThat(serviceAt("2026-07-23T07:20:00Z").shortTermDecisionCheckpoint())
            .startsWith("NOT_CONFIRMED:");
}
```

Use `MockEnvironment` in `ShortTermAutomationSettingsTest`, override one property after constructing the settings object, and prove the next getter call sees the new value.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `mvn -pl apps/api -Dtest=TradingClockServiceTest,ShortTermAutomationSettingsTest,ShortRightSideStrategyServiceTest,V2SignalControllerTest test`

Expected: FAIL because 15:20 is still accepted and the settings class is absent.

- [ ] **Step 3: Implement refresh-by-read settings**

```java
@Component
public class ShortTermAutomationSettings {
    private final Environment environment;

    public ShortTermAutomationSettings(Environment environment) {
        this.environment = environment;
    }

    public boolean enabled() { return bool("research.short-term.schedule.enabled", false); }
    public String zone() { return text("research.short-term.schedule.zone", "Asia/Shanghai"); }
    public String preselectCron() { return text("research.short-term.schedule.preselect-cron", "0 30 14 * * MON-FRI"); }
    public String finalCron() { return text("research.short-term.schedule.final-cron", "0 48 14 * * MON-FRI"); }
    public String readinessCron() { return text("research.short-term.schedule.readiness-cron", "0 54 14 * * MON-FRI"); }
    public LocalTime finalDeadline() { return LocalTime.parse(text("research.short-term.schedule.final-deadline", "14:53:59")); }
    public Duration freshness() { return Duration.ofSeconds(integer("research.short-term.schedule.freshness-seconds", 180)); }
    public ShortTermScanRequest scanRequest() {
        return new ShortTermScanRequest(
                integer("research.short-term.schedule.limit", 3),
                integer("research.short-term.schedule.scan-limit", 6000),
                integer("research.short-term.schedule.kline-limit", 60),
                decimal("research.short-term.schedule.min-amount", "80000000"),
                decimal("research.short-term.schedule.max-pe", "100"),
                decimal("research.short-term.schedule.max-pb", "15"),
                decimal("research.short-term.schedule.min-volume-ratio", "1.15"),
                decimal("research.short-term.schedule.max-entry-rise", "4"),
                decimal("research.short-term.schedule.max-distance-to-ma20", "8"),
                decimal("research.short-term.schedule.min-financial-score", "58"));
    }

    public OvernightRuleSet overnightRules() {
        return new OvernightRuleSet(
                LocalTime.parse(text("research.short-term.overnight.entry-start", "14:45")),
                LocalTime.parse(text("research.short-term.overnight.entry-end", "14:56")),
                LocalTime.parse(text("research.short-term.overnight.normal-exit-time", "14:50")),
                integer("research.short-term.overnight.max-holding-trading-days", 2),
                decimal("research.short-term.overnight.max-position-ratio", "0.3333"),
                decimal("research.short-term.overnight.max-t2-position-ratio", "0.50"),
                decimal("research.short-term.overnight.first-target-floor-percent", "2.5"),
                decimal("research.short-term.overnight.first-target-cap-percent", "4.0"),
                decimal("research.short-term.overnight.second-target-floor-percent", "4.5"),
                decimal("research.short-term.overnight.second-target-cap-percent", "7.0"),
                decimal("research.short-term.overnight.stop-floor-percent", "2.5"),
                decimal("research.short-term.overnight.stop-cap-percent", "4.5"),
                decimal("research.short-term.overnight.trailing-drawdown-percent", "2.0"));
    }
}
```

Create the exact configuration value object in the same task so the settings class compiles independently:

```java
public record OvernightRuleSet(
        LocalTime entryStart,
        LocalTime entryEnd,
        LocalTime normalExitTime,
        int maxHoldingTradingDays,
        BigDecimal maxPositionRatio,
        BigDecimal maxT2PositionRatio,
        BigDecimal firstTargetFloor,
        BigDecimal firstTargetCap,
        BigDecimal secondTargetFloor,
        BigDecimal secondTargetCap,
        BigDecimal stopFloor,
        BigDecimal stopCap,
        BigDecimal trailingDrawdownPercent
) {}
```

Implement typed parsing with bounded defaults. Invalid refreshed values log a warning and use the approved default; they never terminate the scheduler thread.

- [ ] **Step 4: Correct the checkpoint throughout V2 short-side logic**

```java
public static final LocalTime SHORT_TERM_ENTRY_START = LocalTime.of(14, 45);
public static final LocalTime SHORT_TERM_ENTRY_END = LocalTime.of(14, 56, 59);

public String shortTermDecisionCheckpoint() {
    LocalDateTime now = LocalDateTime.now(clock.withZone(CHINA_MARKET_ZONE));
    if (!isMarketClosedDay(now.toLocalDate())
            && !now.toLocalTime().isBefore(SHORT_TERM_ENTRY_START)
            && !now.toLocalTime().isAfter(SHORT_TERM_ENTRY_END)) {
        return "TAIL_ENTRY_1445_1456";
    }
    return "NOT_CONFIRMED:" + classify(now).phase();
}
```

Update `ShortRightSideStrategyService` and its tests so only `TAIL_ENTRY_1445_1456` passes the execution checkpoint. Closing-auction and after-hours phases remain research labels only.

- [ ] **Step 5: Add trading-day helpers**

```java
public LocalDate nextTradingDay(LocalDate date) {
    LocalDate cursor = date.plusDays(1);
    while (isMarketClosedDay(cursor)) cursor = cursor.plusDays(1);
    return cursor;
}

public LocalDate tradingDayAfter(LocalDate date, int offset) {
    LocalDate cursor = date;
    for (int i = 0; i < Math.max(0, offset); i++) cursor = nextTradingDay(cursor);
    return cursor;
}

public LocalDate currentMarketDate() {
    return LocalDate.now(clock.withZone(CHINA_MARKET_ZONE));
}
```

- [ ] **Step 6: Run the timing suite**

Run: `mvn -pl apps/api -Dtest=TradingClockServiceTest,ShortTermAutomationSettingsTest,ShortRightSideStrategyServiceTest,V2SignalControllerTest test`

Expected: PASS; no production path accepts `POST_CLOSE_1520` as an executable ordinary-stock checkpoint.

- [ ] **Step 7: Commit Task 2**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettings.java \
  apps/api/src/main/java/com/aistock/research/shortterm/OvernightRuleSet.java \
  apps/api/src/main/java/com/aistock/research/trading/TradingClockService.java \
  apps/api/src/main/java/com/aistock/research/v2/strategy/ShortRightSideStrategyService.java \
  apps/api/src/test/java/com/aistock/research/trading/TradingClockServiceTest.java \
  apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettingsTest.java \
  apps/api/src/test/java/com/aistock/research/v2/strategy/ShortRightSideStrategyServiceTest.java \
  apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java
git commit -m "fix: align short-term timing with executable tail window"
```

---

### Task 3: Generate Deterministic Overnight Trading Plans

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermOpenScenario.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTradePlan.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTradePlanService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTradePlanServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: `OvernightRuleSet`, quote/technical data, and `TradingClockService`.
- Produces: `ShortTermTradePlan create(LocalDate, BigDecimal, BigDecimal, BigDecimal, ShortTermTechnicalSnapshot, OvernightRuleSet)`.

- [ ] **Step 1: Write failing formula and calendar tests**

Test low, normal, and high volatility. Assert percentages are clamped and all displayed prices are concrete when the reference price exists.

```java
@Test
void createsClampedT1T2Plan() {
    ShortTermTradePlan plan = service.create(
            LocalDate.of(2026, 7, 23), bd("10.00"), bd("9.90"), bd("10.05"),
            technicalWithAtrAndSupport("3.00", "9.72"), rules());

    assertThat(plan.strategyLabel()).isEqualTo("隔夜超短波段");
    assertThat(plan.firstTargetPrice()).isEqualByComparingTo("10.27");
    assertThat(plan.secondTargetPrice()).isEqualByComparingTo("10.48");
    assertThat(plan.hardStopPrice()).isLessThan(bd("10.00"));
    assertThat(plan.normalExitDate()).isEqualTo(LocalDate.of(2026, 7, 24));
    assertThat(plan.absoluteExitDate()).isEqualTo(LocalDate.of(2026, 7, 27));
    assertThat(plan.openScenarios()).extracting(ShortTermOpenScenario::code)
            .containsExactly("HIGH_OPEN", "FLAT_OPEN", "LOW_OPEN");
}
```

Also verify that a missing reference price produces `status = "BLOCKED"` instead of invented target prices.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -pl apps/api -Dtest=ShortTermTradePlanServiceTest test`

Expected: compilation failure because the plan types do not exist.

- [ ] **Step 3: Add structured plan records**

```java
public record ShortTermOpenScenario(
        String code,
        String label,
        String condition,
        String action,
        List<String> invalidationRules
) {}
```

```java
public record ShortTermTradePlan(
        String strategyLabel,
        String status,
        String entryWindow,
        Instant validUntil,
        BigDecimal referenceEntryPrice,
        BigDecimal entryLow,
        BigDecimal entryHigh,
        BigDecimal maxPositionRatio,
        BigDecimal maxT2PositionRatio,
        BigDecimal firstTargetPercent,
        BigDecimal firstTargetPrice,
        BigDecimal firstReductionRatio,
        BigDecimal secondTargetPercent,
        BigDecimal secondTargetPrice,
        BigDecimal hardStopPercent,
        BigDecimal hardStopPrice,
        BigDecimal trailingDrawdownPercent,
        String trailingStopRule,
        LocalDate normalExitDate,
        LocalTime normalExitTime,
        LocalDate absoluteExitDate,
        LocalTime absoluteExitTime,
        List<String> t2ExtensionConditions,
        List<ShortTermOpenScenario> openScenarios,
        List<String> analysisBasis,
        List<String> riskWarnings
) {}
```

Append `atr14Percent` and `recentSupportPrice` to `ShortTermTechnicalSnapshot`, and append `ShortTermTradePlan tradePlan` to `ShortTermCandidate`. Update all candidate creation/copy sites so tail enrichment and reranking preserve the plan.

- [ ] **Step 4: Implement ATR, support, and price calculations**

Compute true range using completed K-lines and average the latest 14 values. Use:

```java
firstTargetPct = clamp(atr14Pct.multiply(bd("0.90")), rules.firstTargetFloor(), rules.firstTargetCap());
secondTargetPct = clamp(atr14Pct.multiply(bd("1.60")), rules.secondTargetFloor(), rules.secondTargetCap());
stopPct = clamp(atr14Pct.multiply(bd("1.10")), rules.stopFloor(), rules.stopCap());
volatilityStop = referencePrice.multiply(ONE.subtract(stopPct.movePointLeft(2)));
hardStopPrice = recentSupportPrice == null
        ? volatilityStop
        : recentSupportPrice.max(volatilityStop);
```

Round prices to two decimals. Set the first reduction ratio to `0.50`, `maxT2PositionRatio` to the configured `0.50`, normal exit to T+1 14:50, and absolute exit to T+2 14:50. The trailing rule activates only after the first target and exits the remainder after a configured 2% drawdown from the post-target high. T+2 extension requires all of: positive T+1 return, T+1 close above MA5, T+1 close above the prior close, and no freshness/risk gate failure. The low-open scenario must explain T+1 forced-carry risk and may not imply same-day sellability after a new entry.

- [ ] **Step 5: Attach plans without weakening gates**

Inject `ShortTermTradePlanService`. Candidates with blocked freshness or non-executable actions receive a blocked plan; other final candidates receive an actionable plan. AI prose is never an input to prices or deadlines.

- [ ] **Step 6: Run trade-plan regressions**

Run: `mvn -pl apps/api -Dtest=ShortTermTradePlanServiceTest,ShortTermServiceTest,ShortTermScanJobServiceTest test`

Expected: PASS; reranking and tail enrichment preserve identical plan values.

- [ ] **Step 7: Commit Task 3**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTradePlanServiceTest.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java
git commit -m "feat: add deterministic overnight trade plans"
```

---

### Task 4: Split Preselection From Final Tail Refresh

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoverageSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTailSignal.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: `ShortTermScanRequest` and an optional preselected symbol set.
- Produces: `ShortTermReport report(ShortTermScanRequest)` and `ShortTermReport finalReport(ShortTermScanRequest, Set<String>)` with coverage, reviewed symbols, and cutoff metadata.

- [ ] **Step 1: Write failing pipeline tests**

```java
@Test
void finalReportRestrictsExpensiveReviewToPreselectedSymbols() {
    ShortTermReport report = service.finalReport(request(), Set.of("600795", "002128"));

    assertThat(report.reviewedSymbols()).containsExactlyInAnyOrder("600795", "002128");
    verify(client).fetchDailyKLines(eq("600795"), any(), any());
    verify(client).fetchDailyKLines(eq("002128"), any(), any());
    verify(client, never()).fetchDailyKLines(eq("601918"), any(), any());
}
```

Add a minute fixture containing 14:44, 14:45, 14:52, and 14:57. Only 14:45 through the latest pre-14:57 point may contribute to executable-tail scoring.

- [ ] **Step 2: Run the short-term test and verify failure**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest test`

Expected: FAIL because `finalReport`, `reviewedSymbols`, and coverage metadata are absent and the old tail window starts at 14:57.

- [ ] **Step 3: Add coverage and reviewed-symbol metadata**

```java
public record ShortTermCoverageSnapshot(
        int expectedCount,
        int fetchedCount,
        int missingCount,
        BigDecimal coverageRatio,
        boolean executionReliable,
        String source,
        Instant fetchedAt
) {}
```

Append `ShortTermCoverageSnapshot coverage`, `List<String> reviewedSymbols`, and `Instant dataCutoffAt` to `ShortTermReport`. Keep compatibility constructors for tests and older stored JSON, defaulting absent coverage to explicitly unreliable.

- [ ] **Step 4: Refactor the report pipeline once**

```java
public ShortTermReport report(ShortTermScanRequest request) {
    return buildReport(request, Set.of());
}

public ShortTermReport finalReport(ShortTermScanRequest request, Set<String> preselectedSymbols) {
    if (preselectedSymbols == null || preselectedSymbols.isEmpty()) {
        throw new IllegalArgumentException("当日预选股票为空，不能执行尾盘终选");
    }
    return buildReport(request, Set.copyOf(preselectedSymbols));
}
```

The existing ten-argument method delegates to the request method. `buildReport` always refreshes full-market quotes for coverage, sentiment, and hot directions. With a symbol restriction, only matching quotes enter K-line and financial review. `reviewedSymbols` comes from the actual K-line review input, not the final top three.

- [ ] **Step 5: Replace post-close tail semantics**

Use 14:45 as the actionable tail start and reject points at or after 14:57 for executable scoring. Rename labels that say `afterTailConfirm` or `14:57-15:00` so the API cannot imply a buy decision based on unavailable future data. Closing-auction points remain historical evidence only.

- [ ] **Step 6: Run the complete short-term suite**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermGoldenCrossAnalyzerTest,ShortTermScanJobServiceTest,TradingClockServiceTest test`

Expected: PASS; manual scans still work and final scans stay within the preselected set.

- [ ] **Step 7: Commit Task 4**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java
git commit -m "refactor: split short-term preselection and final refresh"
```

---

### Task 5: Orchestrate and Schedule the Daily Snapshot

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledExecutorConfig.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanService.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScanScheduler.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScanSchedulerTest.java`

**Interfaces:**
- Consumes: settings, trading clock, snapshot store, `ShortTermService`, `ResearchHistoryService`, and recommendation attestation.
- Produces: `submit(PRESELECT|FINAL|READINESS_GUARD)`, `runNow(stage)`, and three refreshable triggers.

- [ ] **Step 1: Write failing orchestration tests**

Cover preselection success, final success, valid zero-candidate `NO_TRADE`, stale quote `DATA_BLOCKED`, missing preselection, deadline expiry, duplicate claims, weekend skip, and manual-queue independence.

```java
@Test
void publishesNoTradeInsteadOfInventingCandidate() {
    when(shortTermService.finalReport(any(), eq(Set.of("600795"))))
            .thenReturn(reportWithCandidates(List.of(), reliableCoverage(), currentCutoff()));

    service.runNow(FINAL);

    assertThat(store.latest(tradeDate)).get()
            .extracting(ShortTermScheduledSnapshot::status)
            .isEqualTo(NO_TRADE);
}

@Test
void blocksFinalWhenQuoteDateIsNotToday() {
    when(shortTermService.finalReport(any(), anySet()))
            .thenReturn(reportWithCutoff(yesterdayCutoff()));

    service.runNow(FINAL);

    assertThat(store.latest(tradeDate)).get()
            .extracting(ShortTermScheduledSnapshot::status)
            .isEqualTo(DATA_BLOCKED);
}
```

- [ ] **Step 2: Run scheduler tests and verify failure**

Run: `mvn -pl apps/api -Dtest=ShortTermScheduledScanServiceTest,ShortTermScanSchedulerTest test`

Expected: compilation failure because orchestration classes do not exist.

- [ ] **Step 3: Implement the dedicated executor and fingerprint**

```java
@Bean(name = "shortTermScheduledExecutor", destroyMethod = "shutdown")
ExecutorService shortTermScheduledExecutor() {
    return new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
            new ArrayBlockingQueue<>(1), namedThreadFactory("short-term-scheduled-"),
            new ThreadPoolExecutor.AbortPolicy());
}
```

Fingerprint canonical Jackson JSON for the resolved scan request and overnight rules with SHA-256. Persist the exact same parameter JSON. Duplicate stage/date submissions are deduplicated by the database claim, not queued repeatedly.

- [ ] **Step 4: Implement stage execution and validation**

```java
public void runNow(ShortTermSnapshotStage stage) {
    LocalDate tradeDate = tradingClock.currentMarketDate();
    if (!settings.enabled() || tradingClock.isMarketClosedDay(tradeDate)) return;
    ResolvedParameters parameters = resolveParameters(
            settings.scanRequest(), settings.overnightRules());
    if (!store.claim(tradeDate, stage, parameters.fingerprint(), parameters.json(), clock.instant())) return;
    try {
        switch (stage) {
            case PRESELECT -> runPreselect(tradeDate, parameters);
            case FINAL -> runFinal(tradeDate, parameters);
            case READINESS_GUARD -> runReadinessGuard(tradeDate, parameters);
            default -> throw new IllegalArgumentException("Unsupported scheduled stage " + stage);
        }
    } catch (RuntimeException exception) {
        store.fail(tradeDate, stage, parameters.fingerprint(), rootMessage(exception), clock.instant());
    }
}
```

Define the parameter carrier in `ShortTermScheduledScanService` so fingerprint, JSON, and resolved values cannot drift:

```java
private record ResolvedParameters(
        ShortTermScanRequest scanRequest,
        OvernightRuleSet overnightRules,
        String json,
        String fingerprint
) {}
```

`runFinal` loads the same-day `PRESELECT_READY` report, reads `reviewedSymbols`, calls `finalReport`, and validates coverage >= 0.90, same-day cutoff, freshness within the configured seconds, and completion before `finalDeadline`. Publish `NO_TRADE` only after all gates pass and candidates are empty. Attest and record history only for a valid final report.

`runReadinessGuard` performs no market fetch. If no same-day `FINAL_READY` or `NO_TRADE` exists, publish `DATA_BLOCKED` with `FINAL_MISSING`, `FINAL_STALE`, or `FINAL_FAILED`.

- [ ] **Step 5: Register refreshable cron triggers**

Implement `SchedulingConfigurer`; each trigger reads current settings before calculating its next execution:

```java
registrar.addTriggerTask(
        () -> scheduledScanService.submit(PRESELECT),
        context -> nextExecution(settings.preselectCron(), settings.zone(), context));
registrar.addTriggerTask(
        () -> scheduledScanService.submit(FINAL),
        context -> nextExecution(settings.finalCron(), settings.zone(), context));
registrar.addTriggerTask(
        () -> scheduledScanService.submit(READINESS_GUARD),
        context -> nextExecution(settings.readinessCron(), settings.zone(), context));
```

Invalid refreshed cron text retains the last valid trigger and logs one warning; it must not stop the other triggers.

- [ ] **Step 6: Run orchestration and existing queue tests**

Run: `mvn -pl apps/api -Dtest=ShortTermScheduledScanServiceTest,ShortTermScanSchedulerTest,ShortTermScanJobServiceTest test`

Expected: PASS; a full manual queue does not prevent the scheduled final worker.

- [ ] **Step 7: Commit Task 5**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/schedule \
  apps/api/src/test/java/com/aistock/research/shortterm/schedule
git commit -m "feat: schedule two-stage short-term scans"
```

---

### Task 6: Load the Prepared Snapshot in the Short-Term Page

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermController.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermScheduledSnapshotControllerTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Create: `apps/web-react/src/components/shortterm/OvernightTradePlanPanel.tsx`
- Create: `apps/web-react/src/components/shortterm/ScheduledSnapshotStatus.tsx`
- Create: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Create: `apps/web-react/src/components/shortterm/OvernightTradePlanPanel.test.tsx`

**Interfaces:**
- Consumes: same-day `ShortTermScheduledSnapshot` and `ShortTermTradePlan`.
- Produces: `GET /api/short-term/scheduled-snapshots/latest` and immediate snapshot rendering.

- [ ] **Step 1: Write failing controller and mount tests**

```java
mockMvc.perform(get("/api/short-term/scheduled-snapshots/latest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tradeDate").value("2026-07-23"))
        .andExpect(jsonPath("$.status").value("FINAL_READY"))
        .andExpect(jsonPath("$.report.candidates[0].tradePlan.strategyLabel")
                .value("隔夜超短波段"));
```

```tsx
it('loads the prepared snapshot without starting a scan job', async () => {
  vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue(finalReadySnapshot)
  await renderPage()

  expect(fetchLatestShortTermScheduledSnapshot).toHaveBeenCalledTimes(1)
  expect(startShortTermScanJob).not.toHaveBeenCalled()
  expect(document.body.textContent).toContain('尾盘最终结果已就绪')
})
```

Add UI assertions for `NO_TRADE`, `PRESELECT_READY`, `RUNNING`, `DATA_BLOCKED`, `FAILED`, and no same-day record.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotControllerTest test
npm --prefix apps/web-react test -- ShortTermPage.test.tsx OvernightTradePlanPanel.test.tsx
```

Expected: FAIL because the endpoint, client function, and components do not exist.

- [ ] **Step 3: Add endpoint and TypeScript contracts**

```java
@GetMapping("/scheduled-snapshots/latest")
public ShortTermScheduledSnapshot latestScheduledSnapshot() {
    LocalDate today = tradingClockService.currentMarketDate();
    return snapshotStore.latest(today)
            .orElseGet(() -> ShortTermScheduledSnapshot.waiting(
                    today, "等待 " + automationSettings.preselectCron() + " 自动预选"));
}
```

The store call must use today only. Add matching frontend types:

```ts
export type ShortTermSnapshotStatus =
  | 'RUNNING' | 'PRESELECT_READY' | 'FINAL_READY'
  | 'NO_TRADE' | 'DATA_BLOCKED' | 'FAILED'

export interface ShortTermScheduledSnapshot {
  tradeDate: string
  stage: 'PRESELECT' | 'FINAL' | 'READINESS_GUARD' | 'MANUAL'
  status: ShortTermSnapshotStatus
  message: string
  dataCutoffAt: string | null
  completedAt: string | null
  blockedReasons: string[]
  report: ShortTermReport | null
}
```

Add `fetchLatestShortTermScheduledSnapshot()` to `client.ts`.

- [ ] **Step 4: Replace automatic mount scanning**

Initial mount calls the latest endpoint once. Move the existing start-and-poll behavior into `runManualScan()`, invoked only by `重新扫描` or `应用阈值`. Keep provenance separate:

```ts
type ReportOrigin = 'SCHEDULED' | 'MANUAL'
const [origin, setOrigin] = useState<ReportOrigin>('SCHEDULED')
```

Do not start a backtest before a report exists and do not auto-select the first candidate.

- [ ] **Step 5: Render status and discipline first**

`ScheduledSnapshotStatus` shows trade date, cutoff, completion, coverage, origin, and strategy version. `OvernightTradePlanPanel` is the first detail block and renders exact entry/target/stop prices, maximum position, T+1/T+2 deadlines, and three open scenarios.

Use muted emerald for ready, neutral for running/preselect, amber for no trade, and muted red for blocked/failed. No gradient, nested card, or split detail pane.

- [ ] **Step 6: Run API and frontend tests**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotControllerTest test
npm --prefix apps/web-react test -- ShortTermPage.test.tsx OvernightTradePlanPanel.test.tsx DetailOverlay.dom.test.tsx
npm --prefix apps/web-react run build
```

Expected: PASS; initial page mount records zero calls to `startShortTermScanJob`.

- [ ] **Step 7: Commit Task 6**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/ShortTermController.java \
  apps/api/src/test/java/com/aistock/research/shortterm/ShortTermScheduledSnapshotControllerTest.java \
  apps/web-react/src/types.ts apps/web-react/src/api/client.ts \
  apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx \
  apps/web-react/src/components/shortterm/OvernightTradePlanPanel.tsx \
  apps/web-react/src/components/shortterm/OvernightTradePlanPanel.test.tsx \
  apps/web-react/src/components/shortterm/ScheduledSnapshotStatus.tsx
git commit -m "feat: load prepared short-term snapshots"
```

---

### Task 7: Add T+1/T+2 Overnight Validation

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/backtest/OvernightBacktestRuleSet.java`
- Create: `apps/api/src/main/java/com/aistock/research/backtest/OvernightBacktestTrade.java`
- Create: `apps/api/src/main/java/com/aistock/research/backtest/OvernightBacktestSummary.java`
- Create: `apps/api/src/main/java/com/aistock/research/backtest/OvernightBacktestReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/backtest/BacktestService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/backtest/BacktestController.java`
- Modify: `apps/api/src/test/java/com/aistock/research/backtest/BacktestServiceTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: symbols, T+1/T+2 rule values, daily K-lines, and cost assumptions.
- Produces: `GET /api/backtests/overnight` with cost-adjusted T+1/T+2 metrics.

- [ ] **Step 1: Write failing simulation tests**

Use deterministic K-lines for first target, second target, hard stop, T+1 time exit, T+2 extension, T+2 mandatory exit, next-day gap down, one-price limit-up entry rejection, and one-price limit-down delayed exit.

```java
@Test
void summarizesT1T2ExitReasonsAndGapRisk() {
    OvernightBacktestReport report = service.overnightBacktest(
            "600795", 900, bd("2.5"), bd("4.5"), bd("3.5"), 2,
            bd("0.03"), bd("0.05"), bd("0.05"), bd("9.80"));

    assertThat(report.summary().firstTargetRatePercent()).isNotNull();
    assertThat(report.summary().hardStopRatePercent()).isNotNull();
    assertThat(report.summary().timeStopRatePercent()).isNotNull();
    assertThat(report.summary().gapDownRatePercent()).isNotNull();
    assertThat(report.trades()).allSatisfy(trade ->
            assertThat(trade.holdingTradingDays()).isBetween(1, 2));
}
```

- [ ] **Step 2: Run the test and verify failure**

Run: `mvn -pl apps/api -Dtest=BacktestServiceTest test`

Expected: compilation failure because overnight report types and method are absent.

- [ ] **Step 3: Add a separate overnight contract**

Do not overload the existing 20-day summary:

```java
public record OvernightBacktestSummary(
        int symbolCount,
        int sampleCount,
        BigDecimal positiveRatePercent,
        BigDecimal averageReturnPercent,
        BigDecimal medianReturnPercent,
        BigDecimal averageRunupPercent,
        BigDecimal averageDrawdownPercent,
        BigDecimal firstTargetRatePercent,
        BigDecimal secondTargetRatePercent,
        BigDecimal hardStopRatePercent,
        BigDecimal timeStopRatePercent,
        BigDecimal gapDownRatePercent,
        LocalDate sampleStart,
        LocalDate sampleEnd,
        String conclusion
) {}
```

`OvernightBacktestTrade` records signal date, proxy entry, T+1/T+2 dates, net return, run-up, drawdown, gap, holding days, costs, and one exact exit reason.

- [ ] **Step 4: Implement conservative T+1/T+2 simulation**

Use signal-day close plus entry slippage as the 14:55 fill proxy. On T+1, evaluate adverse gap/open and hard-stop reach before take-profit reach when daily bars cannot establish intraday ordering. Allow a T+2 extension only when T+1 closes profitable, above the prior close, and above MA5; otherwise time-exit on T+1. Apply existing commission, stamp duty, and slippage defaults.

Use exactly these exits: `FIRST_TARGET`, `SECOND_TARGET`, `HARD_STOP`, `T1_TIME_EXIT`, `T2_TIME_EXIT`, and `LIMIT_DOWN_DELAYED`.

- [ ] **Step 5: Expose and consume the overnight endpoint**

Add `/api/backtests/overnight` and the matching frontend client. Replace the short-term page request that currently sends `holdingDays: 20`, `stopLossPercent: 6`, and `takeProfitPercent: 18`. Render overnight sample count, positive rate, average/median return, drawdown, target/stop rates, and gap-down rate.

- [ ] **Step 6: Run validation regressions**

Run:

```bash
mvn -pl apps/api -Dtest=BacktestServiceTest test
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
npm --prefix apps/web-react run build
```

Expected: PASS; no short-term page request contains `holdingDays: 20`.

- [ ] **Step 7: Commit Task 7**

```bash
git add apps/api/src/main/java/com/aistock/research/backtest \
  apps/api/src/test/java/com/aistock/research/backtest/BacktestServiceTest.java \
  apps/web-react/src/types.ts apps/web-react/src/api/client.ts \
  apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx
git commit -m "feat: validate overnight strategy on T1 and T2"
```

---

### Task 8: Publish Configuration and Verify the Running System

**Files:**
- Modify: `infra/nacos/ai-stock-api.yml`
- Modify: `docs/nacos-config.md`
- Modify: `docs/backtest-methodology.md`
- Modify: `docs/architecture.md`
- Test: existing backend and frontend suites

**Interfaces:**
- Consumes: all prior tasks.
- Produces: documented Nacos keys, healthy Docker images, durable same-day snapshots, and browser evidence at `http://127.0.0.1:5176/#/short-term`.

- [ ] **Step 1: Add non-secret sample configuration**

Append under `research` without changing LLM or API-key fields:

```yaml
  short-term:
    schedule:
      enabled: true
      zone: Asia/Shanghai
      preselect-cron: "0 30 14 * * MON-FRI"
      final-cron: "0 48 14 * * MON-FRI"
      readiness-cron: "0 54 14 * * MON-FRI"
      final-deadline: "14:53:59"
      freshness-seconds: 180
      limit: 3
      scan-limit: 6000
      kline-limit: 60
      min-amount: 80000000
      max-pe: 100
      max-pb: 15
      min-volume-ratio: 1.15
      max-entry-rise: 4
      max-distance-to-ma20: 8
      min-financial-score: 58
    overnight:
      entry-start: "14:45"
      entry-end: "14:56"
      normal-exit-time: "14:50"
      max-holding-trading-days: 2
      max-position-ratio: 0.3333
      max-t2-position-ratio: 0.50
      first-target-floor-percent: 2.5
      first-target-cap-percent: 4.0
      second-target-floor-percent: 4.5
      second-target-cap-percent: 7.0
      stop-floor-percent: 2.5
      stop-cap-percent: 4.5
      trailing-drawdown-percent: 2.0
```

- [ ] **Step 2: Document operations and failure semantics**

`docs/nacos-config.md` documents the base data ID, live reads, three scheduled stages, and `FINAL_READY`/`NO_TRADE`/`DATA_BLOCKED`. `docs/backtest-methodology.md` documents the 14:55 proxy, conservative intraday ordering, costs, T+1 forced carry, and T+2 maximum. `docs/architecture.md` documents the durable snapshot flow and page-read boundary.

- [ ] **Step 3: Run complete automated verification**

Run:

```bash
mvn -pl apps/api test
npm --prefix apps/web-react test
npm --prefix apps/web-react run build
git diff --check
```

Expected: all backend tests, frontend tests, TypeScript/Vite build, and whitespace checks pass.

- [ ] **Step 4: Build and restart Docker services**

Run:

```bash
mvn -pl apps/api -DskipTests package
docker compose build api web
docker compose up -d api web
docker compose ps
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:5176/healthz
```

Expected: both containers are healthy, API reports `UP`, and web health returns `ok`.

- [ ] **Step 5: Verify persistence across API restart**

Create a final snapshot through a test-only fixture or controlled same-day run, record its key, restart only the API, and read it again:

```bash
curl -fsS http://127.0.0.1:19080/api/short-term/scheduled-snapshots/latest
docker compose restart api
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:19080/api/short-term/scheduled-snapshots/latest
```

Expected: the same key/report remains through the `api-data` H2 file volume. Do not fabricate `FINAL_READY`; use a test profile or report `DATA_BLOCKED` when the live day lacks valid data.

- [ ] **Step 6: Verify browser behavior**

At desktop and `390x844`, open `http://127.0.0.1:5176/#/short-term` and verify:

- initial load issues no `POST /api/short-term/scan-jobs`;
- status shows current date and cutoff;
- blocked/no-trade states contain no actionable buy wording;
- candidate click opens the detail overlay;
- overnight discipline precedes analysis evidence;
- backdrop, close button, and Escape close the overlay;
- no overlap and no first-row auto-selection.

- [ ] **Step 7: Publish Nacos configuration safely**

Fetch current `ai-stock-api.yml`, merge only `research.short-term`, and publish without printing or replacing API keys. Re-fetch and verify only non-secret short-term values. If the scheduled time has passed, use a controlled manual stage or wait for the next trading day; never relabel a manual report as scheduled.

- [ ] **Step 8: Commit Task 8**

```bash
git add infra/nacos/ai-stock-api.yml docs/nacos-config.md docs/backtest-methodology.md docs/architecture.md
git commit -m "docs: configure scheduled overnight analysis"
```

## Completion Checklist

- [ ] `14:55` page entry reads a persisted same-day result without foreground analysis.
- [ ] No previous-day result is presented as current execution advice.
- [ ] `NO_TRADE` and `DATA_BLOCKED` are visibly and behaviorally distinct.
- [ ] Ordinary A-share execution logic contains no `POST_CLOSE_1520` gate.
- [ ] Trade plans contain exact prices, position cap, T+1/T+2 deadlines, and open scenarios.
- [ ] T+1/T+2 backtest replaces the 20-day short-term validation card.
- [ ] Manual scans remain explicit and do not overwrite the scheduled snapshot.
- [ ] Snapshot survives API container restart.
- [ ] Backend tests, frontend tests/build, Docker health, API endpoint, and desktop/mobile checks pass.
