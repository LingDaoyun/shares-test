# Short-Term Supply-Demand Ranking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank qualified short-term candidates primarily by strong EastMoney buying flow and weak overhead selling pressure.

**Architecture:** Add a pure supply-demand scorer, batch-load same-day fund flow after technical and financial gates, and use a 45/30/25 supply-demand ranking inside each action layer. Extend the existing score breakdown and React detail view so every ordering decision is visible.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Maven, React 19, TypeScript, Vitest

## Global Constraints

- Ranking weights are buying pressure 45%, overhead-pressure relief 30%, and existing technical ranking 25%.
- Existing eligibility, action, freshness, coverage, financial-risk, chase-risk, and scheduling gates remain active.
- Fund-flow requests are batched for the candidate set.
- Wrong-date or missing fund flow receives buying-pressure score 35 and is disclosed.
- Existing unrelated dirty-worktree changes must remain intact.

---

### Task 1: Deterministic Supply-Demand Scorer

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupplyDemandScore.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorer.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorerTest.java`

**Interfaces:**
- Consumes: `EastMoneyFundFlowSnapshot`, quote trade date, `ShortTermTechnicalSnapshot`, and the existing technical ranking score
- Produces: raw flow ratios, buying-pressure score, overhead-pressure relief score, technical score, ranking score, and data gaps

- [ ] Write tests for positive versus negative buying flow, large-order consistency, pressure relief, wrong-date/missing flow, and score bounds.

```java
assertThat(scorer.score(positiveFlow, TRADE_DATE, weakPressureTechnical, new BigDecimal("80")).rankingScore())
        .isGreaterThan(scorer.score(negativeFlow, TRADE_DATE, weakPressureTechnical, new BigDecimal("80")).rankingScore());
assertThat(scorer.score(null, TRADE_DATE, weakPressureTechnical, new BigDecimal("80")).buyPressureScore())
        .isEqualByComparingTo("35.00");
```

- [ ] Run `mvn -pl apps/api -Dtest=ShortTermSupplyDemandScorerTest test` and verify compilation or assertions fail because the scorer does not exist.
- [ ] Implement the exact formulas from `docs/superpowers/specs/2026-07-31-short-term-supply-demand-ranking-design.md`.

```java
public record ShortTermSupplyDemandScore(
        BigDecimal mainNetInflowRatio,
        BigDecimal largeOrderNetInflowRatio,
        BigDecimal buyPressureScore,
        BigDecimal overheadPressureReliefScore,
        BigDecimal technicalRankingScore,
        BigDecimal rankingScore,
        List<String> dataGaps
) {
}

public ShortTermSupplyDemandScore score(
        EastMoneyFundFlowSnapshot fundFlow,
        LocalDate quoteTradeDate,
        ShortTermTechnicalSnapshot technical,
        BigDecimal technicalRankingScore
)
```

- [ ] Re-run the focused scorer test and verify all cases pass.

### Task 2: Batch and Date-Aware EastMoney Fund Flow

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyFundFlowSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`

**Interfaces:**
- Produces: `Map<String, EastMoneyFundFlowSnapshot> fetchFundFlowSnapshots(List<String> symbols)`
- Preserves: `Optional<EastMoneyFundFlowSnapshot> fetchFundFlowSnapshot(String symbol)`

- [ ] Add failing parser tests proving `f124` becomes a China-market trade date and daily fallback rows preserve their explicit trade date.

```java
assertThat(snapshot.tradeDate()).isEqualTo(LocalDate.parse("2026-07-31"));
assertThat(snapshot.marketTimestamp()).isEqualTo(Instant.parse("2026-07-31T07:00:00Z"));
```

- [ ] Add failing tests for a batch URL containing deduplicated secids and for parsing multiple returned symbols.

```java
assertThat(client.fundFlowBatchUrl(List.of("600000", "000001", "600000")))
        .contains("secids=1.600000%2C0.000001");
assertThat(client.readFundFlowSnapshots(diff, fetchedAt)).containsOnlyKeys("600000", "000001");
```

- [ ] Extend `EastMoneyFundFlowSnapshot` with trade date and market timestamp while retaining an old-signature compatibility constructor.

```java
public record EastMoneyFundFlowSnapshot(
        String symbol,
        String name,
        BigDecimal mainNetInflow,
        BigDecimal superLargeNetInflow,
        BigDecimal largeNetInflow,
        BigDecimal mediumNetInflow,
        BigDecimal smallNetInflow,
        BigDecimal mainNetInflowRatio,
        BigDecimal superLargeNetInflowRatio,
        BigDecimal largeNetInflowRatio,
        BigDecimal mediumNetInflowRatio,
        BigDecimal smallNetInflowRatio,
        String sourceName,
        String sourceUrl,
        Instant fetchedAt,
        LocalDate tradeDate,
        Instant marketTimestamp
) {
}
```

- [ ] Implement one-request batch URL construction and batch response parsing.

```java
public Map<String, EastMoneyFundFlowSnapshot> fetchFundFlowSnapshots(List<String> symbols) {
    String url = fundFlowBatchUrl(symbols);
    JsonNode diff = fetchQuoteRoot(url).path("data").path("diff");
    return readFundFlowSnapshots(diff, Instant.now());
}
```

- [ ] Run `mvn -pl apps/api -Dtest=EastMoneyClientTest,ShortTermSupplyDemandScorerTest test` and verify green.

### Task 3: Integrate Supply-Demand Ranking

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermScoreBreakdown.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Adds score fields: `mainNetInflowRatio`, `largeOrderNetInflowRatio`, `buyPressureScore`, `overheadPressureReliefScore`, `technicalRankingScore`
- Reinterprets `rankingScore` as the 45/30/25 supply-demand ranking score

- [ ] Add a failing service test with technically similar candidates proving positive flow and weak pressure rank first.

```java
assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
        .startsWith("600001", "600002");
assertThat(report.candidates().get(0).score().buyPressureScore())
        .isGreaterThan(report.candidates().get(1).score().buyPressureScore());
```

- [ ] Add a failing test proving the service invokes one batch fund-flow fetch and exposes missing-flow data gaps.
- [ ] Batch-load flows after financial hard exclusions.

```java
Map<String, EastMoneyFundFlowSnapshot> fundFlowMap = fetchFundFlows(
        eligibleTechnicalCandidates.stream().map(item -> item.quote().symbol()).toList());
```

- [ ] Score each candidate with `ShortTermSupplyDemandScorer`.

```java
ShortTermSupplyDemandScore supplyDemand = supplyDemandScorer.score(
        fundFlowMap.get(quote.symbol()),
        quote.tradeDate(),
        technical,
        stageScore.rankingScore());
```

- [ ] Sort by eligibility, action priority, supply-demand ranking, then technical tie-breakers.

```java
.sorted(Comparator
        .comparingInt((ScoredShortTerm item) -> eligibilityGatePriority(item.candidate().action())).reversed()
        .thenComparing(Comparator.comparingInt(
                (ScoredShortTerm item) -> actionPriority(item.candidate().action())).reversed())
        .thenComparing(item -> item.candidate().score().rankingScore(), Comparator.reverseOrder())
        .thenComparing(Comparator.comparingInt(
                (ScoredShortTerm item) -> eligibleGoldenCrossPriority(item.candidate())).reversed())
        .thenComparing(Comparator.comparingInt(
                (ScoredShortTerm item) -> rightSideMaturityPriority(item.candidate())).reversed()))
```

- [ ] Add fund-flow and pressure explanations to strengths, risks, evidence, quote note, and methodology.
- [ ] Run `mvn -pl apps/api -Dtest=ShortTermSupplyDemandScorerTest,EastMoneyClientTest,ShortTermServiceTest test` and verify green.

### Task 4: Expose Ranking Evidence in React

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes optional supply-demand fields for compatibility with old archived reports
- Displays main flow, 45% buying pressure, 30% pressure relief, 25% technical score, and final ranking

- [ ] Add failing rendering tests for populated and missing supply-demand fields.
- [ ] Extend TypeScript fields as optional and add summary/detail indicators without overwriting existing page edits.

```typescript
mainNetInflowRatio?: number | null
largeOrderNetInflowRatio?: number | null
buyPressureScore?: number | null
overheadPressureReliefScore?: number | null
technicalRankingScore?: number | null
```

```tsx
<ScoreMetric label="买盘强度 45%" value={candidate.score.buyPressureScore} />
<ScoreMetric label="抛压弱度 30%" value={candidate.score.overheadPressureReliefScore} />
<ScoreMetric label="技术底分 25%" value={candidate.score.technicalRankingScore} />
<ScoreMetric label="供需排序分" value={candidate.score.rankingScore} />
```

- [ ] Run `npm test -- --run src/pages/ShortTermPage.test.tsx` in `apps/web-react`.
- [ ] Run all React tests and `npm run build`.

### Task 5: Full Verification and Real Scan

**Files:**
- No source changes expected

**Interfaces:**
- Verifies API, deployed service, and real EastMoney scan behavior

- [ ] Run `mvn -pl apps/api test`.
- [ ] Run `mvn -pl apps/api -DskipTests package`.
- [ ] Run `docker compose up -d --build api web`.
- [ ] Trigger `POST /api/short-term/scan-jobs` and poll the returned job.
- [ ] Verify terminal job status, no per-symbol flow amplification, first action layer descending by `rankingScore`, and visible flow/pressure evidence.
- [ ] Verify `GET /actuator/health`, `git diff --check`, and final worktree state.
