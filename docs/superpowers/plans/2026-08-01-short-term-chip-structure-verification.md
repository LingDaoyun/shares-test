# Short-Term Chip Structure Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an auditable local chip-cost model and Tushare cross-source verification to short-term candidate ranking without changing qualification gates or making external data a scan dependency.

**Architecture:** EastMoney daily K-lines supply OHLC, amount, volume, and turnover for a deterministic Java chip-distribution calculator. A cached Tushare `cyq_perf` adapter verifies the latest completed trading-day model; the resulting snapshot contributes only to same-action-layer ordering and runs in `SHADOW` mode by default. The API persists verification evidence and the React detail modal renders compact, non-trading-claim evidence.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, PostgreSQL-compatible SQL, JUnit 5, Mockito, React 18, TypeScript, Vitest, Testing Library, Nacos configuration.

## Global Constraints

- Strategy version is exactly `short-term-right-side-v3-chip-verified`.
- Default activation mode is `SHADOW`; supported values are `OFF`, `SHADOW`, and `ACTIVE`.
- Chip evidence changes ranking only; it cannot change qualification, action, position sizing, entry/exit rules, or global scan status.
- Ranking weights are technical 45%, verified chip 25%, overhead-pressure relief 20%, and fund-flow buy pressure 10%.
- Verification coefficients are `VERIFIED=1.00`, `SINGLE_SOURCE=0.60`, and `CONFLICT/STALE/INSUFFICIENT=0.00`.
- Local input uses 120 completed bars, 150 price buckets, at least 80 valid bars, and at least 95% turnover coverage.
- Tushare is disabled when no token is configured; token values never enter logs, API responses, report JSON, or Git.
- Tushare failure, timeout, rate limiting, stale data, conflict, or cache miss never creates a global `DATA_BLOCKED` result.
- Existing V2 report JSON without chip fields must remain deserializable and display `历史版本未计算`.
- UI wording uses `推算筹码成本`, `上方供给压力`, and `筹码模型已认证`; it must not claim real shareholder positions or certain main-force intent.
- Existing quote coverage, freshness, ST, suspension, liquidity, limit-up executability, financial-red-flag, chase-risk, and market-regime gates remain authoritative.

---

### Task 1: Preserve Turnover in EastMoney K-lines and History

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyKLine.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Modify: `apps/api/src/main/java/com/aistock/research/history/KlineHistoryEntity.java`
- Modify: `apps/api/src/main/java/com/aistock/research/history/KlineHistoryRecorder.java`
- Modify: `apps/api/src/main/resources/schema.sql`
- Test: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/history/KlineHistoryRecorderTest.java`

**Interfaces:**
- Consumes: EastMoney K-line rows requested with `fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61`.
- Produces: `EastMoneyKLine.turnoverRate()` as a nullable percentage-point `BigDecimal`; `market_kline_history.turnover_rate` with the same unit.

- [ ] **Step 1: Write failing parsing and persistence tests**

```java
assertThat(series.items().getFirst().turnoverRate()).isEqualByComparingTo("3.42");
verify(repository).save(argThat(entity ->
        entity.getTurnoverRate().compareTo(new BigDecimal("3.42")) == 0));
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run: `mvn -pl apps/api -Dtest=EastMoneyClientTest,KlineHistoryRecorderTest test`

Expected: compilation or assertion failure because `turnoverRate` does not exist.

- [ ] **Step 3: Add the nullable field and parse `f61` without breaking fallbacks**

```java
public record EastMoneyKLine(
        String symbol, LocalDate tradeDate, BigDecimal open, BigDecimal close,
        BigDecimal high, BigDecimal low, BigDecimal volume, BigDecimal amount,
        BigDecimal turnoverRate
) {
    public EastMoneyKLine(String symbol, LocalDate tradeDate, BigDecimal open, BigDecimal close,
                          BigDecimal high, BigDecimal low, BigDecimal volume, BigDecimal amount) {
        this(symbol, tradeDate, open, close, high, low, volume, amount, null);
    }
}
```

Persist `turnoverRate` through `KlineHistoryEntity` and `KlineHistoryRecorder`, and add:

```sql
turnover_rate numeric(12, 4),
```

- [ ] **Step 4: Run the focused tests and confirm pass**

Run: `mvn -pl apps/api -Dtest=EastMoneyClientTest,KlineHistoryRecorderTest test`

Expected: both test classes pass.

- [ ] **Step 5: Commit the K-line contract**

```bash
git add apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyKLine.java apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java apps/api/src/main/java/com/aistock/research/history/KlineHistoryEntity.java apps/api/src/main/java/com/aistock/research/history/KlineHistoryRecorder.java apps/api/src/main/resources/schema.sql apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java apps/api/src/test/java/com/aistock/research/history/KlineHistoryRecorderTest.java
git commit -m "feat: preserve turnover in kline history"
```

### Task 2: Calculate a Deterministic Local Chip Distribution

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipCalculationMode.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipDataQuality.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/LocalChipDistribution.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/LocalChipDistributionCalculator.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/chip/LocalChipDistributionCalculatorTest.java`

**Interfaces:**
- Consumes: `List<EastMoneyKLine>` ordered by trade date and a current price.
- Produces: `LocalChipDistributionCalculator.calculate(List<EastMoneyKLine>, BigDecimal, ChipCalculationMode)` returning costs, winner/overhead percentages, old-high residual ratio, turnover since prior high, quality, and gaps.

- [ ] **Step 1: Write failing tests for decay, triangle allocation, one-price bars, invalid turnover, and percentiles**

```java
@Test
void replacesOldDistributionWhenTurnoverIsOneHundredPercent() {
    LocalChipDistribution result = calculator.calculate(
            List.of(bar("2026-07-29", "9", "10", "11", "9", "100"),
                    bar("2026-07-30", "19", "20", "21", "19", "100")),
            new BigDecimal("20"), ChipCalculationMode.COMPLETED_BAR);
    assertThat(result.averageCost()).isBetween(new BigDecimal("19"), new BigDecimal("21"));
    assertThat(result.cost5()).isGreaterThan(new BigDecimal("18"));
}

@Test
void marksInsufficientWhenTurnoverCoverageIsBelowNinetyFivePercent() {
    LocalChipDistribution result = calculator.calculate(barsWithMissingTurnover(120, 7),
            new BigDecimal("10"), ChipCalculationMode.COMPLETED_BAR);
    assertThat(result.quality()).isEqualTo(ChipDataQuality.INSUFFICIENT);
    assertThat(result.dataGaps()).contains("换手率有效覆盖低于95%");
}
```

- [ ] **Step 2: Run the calculator test and confirm failure**

Run: `mvn -pl apps/api -Dtest=LocalChipDistributionCalculatorTest test`

Expected: compilation failure because the calculator contract does not exist.

- [ ] **Step 3: Implement 150 buckets, clamped turnover decay, triangular daily allocation, and quality gates**

```java
public LocalChipDistribution calculate(
        List<EastMoneyKLine> input,
        BigDecimal currentPrice,
        ChipCalculationMode mode
) {
    List<EastMoneyKLine> bars = validCompletedBars(input).stream()
            .sorted(Comparator.comparing(EastMoneyKLine::tradeDate))
            .toList();
    if (bars.size() < settings.minValidBars() || turnoverCoverage(bars) < settings.minTurnoverCoverage()) {
        return LocalChipDistribution.insufficient(mode, List.of("筹码历史数据不足"));
    }
    double[] buckets = new double[settings.priceBuckets()];
    for (EastMoneyKLine bar : bars) {
        decay(buckets, clamp01(bar.turnoverRate().doubleValue() / 100d));
        allocateTriangle(buckets, bar, averageTradePrice(bar));
    }
    return summarize(bars, buckets, currentPrice, mode);
}
```

Use `amount / volume` only when its derived price lies inside `[low, high]`; otherwise use `(open + close + high + low) / 4`. Put a one-price bar entirely into its single nearest bucket.

- [ ] **Step 4: Run the calculator tests and confirm pass**

Run: `mvn -pl apps/api -Dtest=LocalChipDistributionCalculatorTest test`

Expected: all distribution and quality cases pass.

- [ ] **Step 5: Commit the local model**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/chip apps/api/src/test/java/com/aistock/research/shortterm/chip/LocalChipDistributionCalculatorTest.java
git commit -m "feat: calculate local chip distribution"
```

### Task 3: Score Chip Structure and Verify Two Models

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipVerificationStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ExternalChipPerformance.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipStructureScorer.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipModelVerifier.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipStructureScorerTest.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipModelVerifierTest.java`

**Interfaces:**
- Consumes: a valid `LocalChipDistribution`, optional `ExternalChipPerformance`, and expected completed trade date.
- Produces: `ShortTermChipSnapshot` containing score, verification coefficient, contribution, metrics, status, source dates, model version, and gaps.

- [ ] **Step 1: Write failing score and verification-boundary tests**

```java
assertThat(verifier.verify(local, externalWithinTolerance, LocalDate.of(2026, 7, 30)).status())
        .isEqualTo(ChipVerificationStatus.VERIFIED);
assertThat(verifier.verify(local, externalWithCostDeviationOverThreePercent, LocalDate.of(2026, 7, 30)).status())
        .isEqualTo(ChipVerificationStatus.CONFLICT);
assertThat(verifier.verify(local, null, LocalDate.of(2026, 7, 30)).coefficient())
        .isEqualByComparingTo("0.60");
assertThat(snapshot.contributionScore()).isEqualByComparingTo(
        snapshot.chipStructureScore().multiply(snapshot.verificationCoefficient()).multiply(new BigDecimal("0.25")));
```

- [ ] **Step 2: Run the new tests and confirm failure**

Run: `mvn -pl apps/api -Dtest=ChipStructureScorerTest,ChipModelVerifierTest test`

Expected: compilation failure because scorer and verifier do not exist.

- [ ] **Step 3: Implement continuous scores and exact verification states**

```java
BigDecimal rawScore = costPosition.multiply(new BigDecimal("0.30"))
        .add(concentration.multiply(new BigDecimal("0.25")))
        .add(overheadRelief.multiply(new BigDecimal("0.25")))
        .add(priorHighDigestion.multiply(new BigDecimal("0.20")));

boolean verified = sameDate
        && averageCostDeviation.compareTo(settings.maxAverageCostDeviation()) <= 0
        && costBandOverlap.compareTo(settings.minCostBandOverlap()) >= 0
        && winnerRateDeviation.compareTo(settings.maxWinnerRateDeviation()) <= 0;
```

Return `INSUFFICIENT` before checking external data, `STALE` for a mismatched expected date, `SINGLE_SOURCE` for unavailable external evidence, and `CONFLICT` for same-date out-of-tolerance evidence.

- [ ] **Step 4: Run the new tests and confirm pass**

Run: `mvn -pl apps/api -Dtest=ChipStructureScorerTest,ChipModelVerifierTest test`

Expected: all scoring and five-state boundary cases pass.

- [ ] **Step 5: Commit scoring and certification**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/chip apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipStructureScorerTest.java apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipModelVerifierTest.java
git commit -m "feat: score and verify chip structure"
```

### Task 4: Add a Safe Tushare `cyq_perf` Adapter

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/configuration/ShortTermChipSettings.java`
- Create: `apps/api/src/main/java/com/aistock/research/integration/tushare/TushareChipClient.java`
- Create: `apps/api/src/main/java/com/aistock/research/integration/tushare/TushareChipResponseParser.java`
- Create: `apps/api/src/test/java/com/aistock/research/integration/tushare/TushareChipClientTest.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/resources/application-local.yml`
- Modify: `apps/api/src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: `TushareChipClient.fetchPerformance(String symbol, LocalDate tradeDate)` and configuration under `research.short-term.chip.tushare`.
- Produces: `Optional<ExternalChipPerformance>`; all recoverable client failures return empty and expose a token-free reason through an internal result type.

- [ ] **Step 1: Write failing request, code-mapping, parsing, timeout, rate-limit, and token-redaction tests**

```java
assertThat(capturedBody).contains("\"api_name\":\"cyq_perf\"");
assertThat(capturedBody).contains("\"ts_code\":\"002580.SZ\"");
assertThat(result.value().orElseThrow().winnerRatePercent()).isEqualByComparingTo("63.50");
assertThat(client.fetchPerformance("002580", date).value()).isEmpty();
assertThat(client.fetchPerformance("002580", date).errorSummary()).doesNotContain("secret-token");
```

- [ ] **Step 2: Run the client test and confirm failure**

Run: `mvn -pl apps/api -Dtest=TushareChipClientTest test`

Expected: compilation failure because the client does not exist.

- [ ] **Step 3: Implement POST JSON, explicit field mapping, timeouts, and failure isolation**

```java
Map<String, Object> body = Map.of(
        "api_name", "cyq_perf",
        "token", settings.tushare().token(),
        "params", Map.of("ts_code", toTsCode(symbol), "trade_date", BASIC_DATE.format(tradeDate)),
        "fields", "ts_code,trade_date,cost_5pct,cost_15pct,cost_50pct,cost_85pct,cost_95pct,weight_avg,winner_rate"
);
```

Do not interpolate `body`, HTTP headers, or exceptions containing request content into logs. Convert Tushare winner rate to percentage points explicitly after validating its source range.

- [ ] **Step 4: Run the client test and confirm pass**

Run: `mvn -pl apps/api -Dtest=TushareChipClientTest test`

Expected: request, parser, and failure-isolation cases pass.

- [ ] **Step 5: Commit the adapter and defaults**

```bash
git add apps/api/src/main/java/com/aistock/research/configuration/ShortTermChipSettings.java apps/api/src/main/java/com/aistock/research/integration/tushare apps/api/src/test/java/com/aistock/research/integration/tushare/TushareChipClientTest.java apps/api/src/main/resources/application.yml apps/api/src/main/resources/application-local.yml apps/api/src/main/resources/application-prod.yml
git commit -m "feat: add safe tushare chip adapter"
```

### Task 5: Cache and Persist Verification Evidence

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationStore.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationStoreTest.java`
- Modify: `apps/api/src/main/resources/schema.sql`

**Interfaces:**
- Consumes: stock symbol, completed trade date, model version, local summary, optional external summary, and verifier result.
- Produces: cached lookup keyed by `(symbol, tradeDate, modelVersion)` and persisted source/status/deviation evidence without token data.

- [ ] **Step 1: Write failing cache-key, round-trip, and secret-exclusion tests**

```java
store.save("002580", date, "chip-v1", snapshot);
assertThat(store.find("002580", date, "chip-v1")).contains(snapshot);
assertThat(store.find("002580", date, "chip-v2")).isEmpty();
assertThat(saved.getErrorSummary()).doesNotContain("token");
```

- [ ] **Step 2: Run the store test and confirm failure**

Run: `mvn -pl apps/api -Dtest=ShortTermChipVerificationStoreTest test`

Expected: compilation failure because the entity and store do not exist.

- [ ] **Step 3: Add the table, unique key, JSON summaries, and safe lookup**

```sql
create table if not exists short_term_chip_verification (
    id bigserial primary key,
    symbol varchar(12) not null,
    trade_date date not null,
    model_version varchar(80) not null,
    verification_status varchar(24) not null,
    verification_coefficient numeric(8, 4) not null,
    average_cost_deviation numeric(12, 6),
    cost_band_overlap numeric(12, 6),
    winner_rate_deviation numeric(12, 6),
    local_summary_json text not null,
    external_summary_json text,
    data_cutoff_at timestamp,
    observed_at timestamp not null,
    error_summary varchar(500),
    unique (symbol, trade_date, model_version)
);
```

Use Jackson for structured summaries and normalize error text before persistence so request bodies and token-shaped values cannot be stored.

- [ ] **Step 4: Run the store test and confirm pass**

Run: `mvn -pl apps/api -Dtest=ShortTermChipVerificationStoreTest test`

Expected: key and safe-persistence cases pass.

- [ ] **Step 5: Commit persistence**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationEntity.java apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationRepository.java apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationStore.java apps/api/src/test/java/com/aistock/research/shortterm/chip/ShortTermChipVerificationStoreTest.java apps/api/src/main/resources/schema.sql
git commit -m "feat: persist chip verification evidence"
```

### Task 6: Integrate Verified Chip Contribution into Short-Term Ranking

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipActivationMode.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipAnalysisService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupplyDemandScore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorer.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorerTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: technical candidate, K-lines, current quote, fund flow, chip activation mode, and cached external evidence.
- Produces: V2 score, V3 score, applied score, rank delta, and nullable `ShortTermChipSnapshot` on every candidate.

- [ ] **Step 1: Write failing ranking, degradation, action-layer, SHADOW, ACTIVE, and old-JSON tests**

```java
assertThat(scorer.score(flow, date, technical, new BigDecimal("80"), verifiedChip).v3RankingScore())
        .isEqualByComparingTo("79.00");
assertThat(conflictScore.chipContributionScore()).isZero();
assertThat(shadowReport.candidates()).extracting(ShortTermCandidate::symbol)
        .containsExactlyElementsOf(v2Order);
assertThat(activeReport.candidates()).extracting(ShortTermCandidate::symbol)
        .containsExactlyElementsOf(v3Order);
assertThat(readOldReportWithoutChipField.candidates().getFirst().chip()).isNull();
```

- [ ] **Step 2: Run focused service/scorer tests and confirm failure**

Run: `mvn -pl apps/api -Dtest=ShortTermSupplyDemandScorerTest,ShortTermServiceTest test`

Expected: compilation or assertion failure because V3 fields and activation behavior are absent.

- [ ] **Step 3: Implement the V3 formula without changing action calculation**

```java
BigDecimal v3 = technicalScore.multiply(new BigDecimal("0.45"))
        .add(chipContribution)
        .add(pressureRelief.multiply(new BigDecimal("0.20")))
        .add(flowScore.score().multiply(new BigDecimal("0.10")));

BigDecimal applied = switch (settings.activationMode()) {
    case OFF, SHADOW -> v2RankingScore;
    case ACTIVE -> v3;
};
```

Sort first by the existing action priority and only then by `appliedRankingScore`. Catch all chip-analysis failures per candidate, attach a gap, and preserve the candidate and scan result. Store V2/V3 rank positions in report evidence while SHADOW is active.

- [ ] **Step 4: Run all short-term backend tests and confirm pass**

Run: `mvn -pl apps/api -Dtest='com.aistock.research.shortterm.*Test,com.aistock.research.shortterm.schedule.*Test' test`

Expected: all short-term tests pass and existing qualification/action expectations remain unchanged.

- [ ] **Step 5: Commit the ranking integration**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm apps/api/src/test/java/com/aistock/research/shortterm
git commit -m "feat: rank short-term candidates with verified chips"
```

### Task 7: Render Chip Evidence in the Short-Term Modal

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/components/shortterm/ShortTermCandidateIndicators.tsx`
- Modify: `apps/web-react/src/components/shortterm/ShortTermCandidateIndicators.test.tsx`

**Interfaces:**
- Consumes: nullable API field `candidate.chip` and V2/V3 ranking diagnostics.
- Produces: compact list chips for verification/cost distance/contribution and a complete `筹码结构` detail section with muted accessible status capsules.

- [ ] **Step 1: Write failing legacy, verified, single-source, and conflict rendering tests**

```tsx
expect(screen.getByText('筹码模型已认证')).toBeInTheDocument()
expect(screen.getByText('距成本中枢 +4.20%')).toBeInTheDocument()
expect(screen.getByText('筹码贡献 18.75')).toBeInTheDocument()
expect(screen.getByText('历史版本未计算')).toBeInTheDocument()
expect(screen.queryByText('主力必然拉升')).not.toBeInTheDocument()
```

- [ ] **Step 2: Run frontend tests and confirm failure**

Run: `npm test -- ShortTermPage.test.tsx ShortTermCandidateIndicators.test.tsx`

Working directory: `apps/web-react`

Expected: assertions fail because chip evidence is not rendered.

- [ ] **Step 3: Add typed evidence and accessible low-saturation capsules**

```ts
export type ChipVerificationStatus = 'VERIFIED' | 'SINGLE_SOURCE' | 'CONFLICT' | 'STALE' | 'INSUFFICIENT'

export interface ShortTermChipSnapshot {
  verificationStatus: ChipVerificationStatus
  verificationLabel: string
  averageCost?: number | null
  distanceToAverageCostPercent?: number | null
  cost70Low?: number | null
  cost70High?: number | null
  cost90Low?: number | null
  cost90High?: number | null
  winnerRatePercent?: number | null
  overheadChipRatioPercent?: number | null
  priorHighZoneResidualRatioPercent?: number | null
  turnoverSincePriorHighPercent?: number | null
  chipStructureScore?: number | null
  verificationCoefficient?: number | null
  contributionScore?: number | null
  localTradeDate?: string | null
  externalTradeDate?: string | null
  calculationMode?: 'COMPLETED_BAR' | 'INTRADAY_ESTIMATE' | null
  dataGaps: string[]
}
```

Use semantic text plus color; status may never be communicated by color alone. Keep full evidence inside the existing secondary detail modal, not a right-side panel.

- [ ] **Step 4: Run tests and production build**

Run: `npm test -- ShortTermPage.test.tsx ShortTermCandidateIndicators.test.tsx && npm run build`

Working directory: `apps/web-react`

Expected: tests pass and Vite build exits 0.

- [ ] **Step 5: Commit the UI**

```bash
git add apps/web-react/src/types.ts apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx apps/web-react/src/components/shortterm/ShortTermCandidateIndicators.tsx apps/web-react/src/components/shortterm/ShortTermCandidateIndicators.test.tsx
git commit -m "feat: show verified chip evidence"
```

### Task 8: Validate Configuration, Compatibility, and Runtime Degradation

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-01-short-term-chip-structure-verification-design.md` only if implementation names differ from the approved contract.
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanServiceTest.java`

**Interfaces:**
- Consumes: Nacos keys under `research.short-term.chip`, Docker Compose API/web services, and the real manual scan endpoint.
- Produces: documented safe defaults, passing full test suites, healthy containers, and a real scan that remains successful when Tushare is disabled.

- [ ] **Step 1: Add an integration regression proving external failure cannot block the scan**

```java
when(tushareChipClient.fetchPerformance(anyString(), any())).thenReturn(ChipFetchResult.failure("HTTP 429"));
ShortTermReport report = service.scan(request);
assertThat(report.status()).isNotEqualTo("DATA_BLOCKED");
assertThat(report.candidates()).allSatisfy(candidate ->
        assertThat(candidate.chip().verificationStatus()).isEqualTo(ChipVerificationStatus.SINGLE_SOURCE));
```

- [ ] **Step 2: Run the full backend and frontend suites**

Run: `mvn -pl apps/api test`

Expected: BUILD SUCCESS.

Run: `npm test && npm run build`

Working directory: `apps/web-react`

Expected: all Vitest suites pass and Vite build exits 0.

- [ ] **Step 3: Document Nacos configuration and SHADOW-to-ACTIVE promotion criteria**

Document the exact YAML from the design, state that `token` is user-supplied, and require out-of-sample comparison before changing `activation-mode: ACTIVE`.

- [ ] **Step 4: Rebuild and start the local stack**

Run: `docker compose up -d --build api web`

Expected: `api` and `web` containers are running.

- [ ] **Step 5: Verify health, live page, and a real non-blocking manual scan**

Run: `curl -fsS http://127.0.0.1:19080/actuator/health`

Expected: JSON contains `"status":"UP"`.

Run: `curl -fsS -X POST http://127.0.0.1:19080/api/short-term/scan-jobs -H 'Content-Type: application/json' -d '{}'`

Expected: a job ID; polling it reaches a terminal successful research status unless an existing authoritative quote-coverage/freshness gate blocks it. A missing Tushare token must never be listed as a global blocked reason.

- [ ] **Step 6: Inspect the browser at desktop and mobile widths**

Open `http://127.0.0.1:5176/#/short-term`, select a candidate, and verify that the modal closes by clicking its backdrop; list text does not overflow; all chip metrics align; capsules are readable but not visually harsh; and legacy reports show `历史版本未计算`.

- [ ] **Step 7: Commit documentation and final regressions**

```bash
git add README.md apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanServiceTest.java
git commit -m "docs: explain chip verification rollout"
```

## Plan Self-Review

- Spec coverage: Tasks 1-8 cover source data, local distribution, scoring, verification, caching, ranking isolation, activation modes, API/UI evidence, configuration, compatibility, degradation, and runtime checks.
- Placeholder scan: this plan contains no deferred implementation markers; every test and implementation step names its exact contract and command.
- Type consistency: `LocalChipDistribution` feeds `ChipModelVerifier` and `ChipStructureScorer`; both produce `ShortTermChipSnapshot`; the snapshot is attached as nullable `ShortTermCandidate.chip` and mirrored by the React `ShortTermChipSnapshot` interface.
- Safety review: no task changes qualification or action computation; external errors are converted to candidate-level evidence gaps; Tushare token handling is backend-only.
