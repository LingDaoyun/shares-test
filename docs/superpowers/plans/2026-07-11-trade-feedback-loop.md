# Trade Feedback Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a traceable loop from a system recommendation to multiple real buy/sell fills, automatic T+1/T+5/T+20 evaluation, and bounded historical calibration evidence for strategy Agents.

**Architecture:** Add a focused `tradefeedback` backend package with three persisted aggregates: recommendation cases, immutable fill facts, and replaceable outcome snapshots. Keep market access behind a small gateway so outcome math is deterministic and testable; expose REST APIs and add one React trade-review page plus reusable “加入复盘” controls on recommendation pages. Historical feedback is aggregated by source module and rule version, then injected into AI committee prompts only after the configured sample threshold.

**Tech Stack:** Java 17, Spring Boot 3, Spring Data JPA, H2/PostgreSQL, JUnit 5/Mockito/AssertJ, React 18, TypeScript, Zustand, Axios, Tailwind CSS, Lucide React, Docker Compose.

## Global Constraints

- Special attention and trade review never affect the stock universe and never become a whitelist.
- Preserve original recommendation and fill facts; recompute derived outcomes without rewriting source facts.
- Record each fill as `side + executedAt + price + quantity` and reject cumulative overselling.
- Use moving weighted-average cost and gross return; do not include commission, stamp duty, dividends, splits, or financing costs in v1.
- Evaluate T+1/T+5/T+20 by actual A-share trading rows, not calendar days.
- Do not fabricate market outcomes when data is missing; keep `PENDING` or `UNAVAILABLE` state.
- Historical evidence needs 5 matured samples before Agent prompt injection and 20 matured samples before a bounded `-5..+5` reliability adjustment is emitted.
- Do not automatically write adjusted thresholds back to Nacos.

---

### Task 1: Persist Recommendation Cases, Fills, and Outcomes

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeRepository.java`
- Modify: `apps/api/src/main/resources/schema.sql`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackPersistenceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java`

**Interfaces:**
- Produces: `TradeCaseRepository.findAllByOrderByCreatedAtDesc()`, `findByRecommendationFingerprint(String)`, and `findById(String)`.
- Produces: `TradeFillRepository.findByCaseIdOrderByExecutedAtAscCreatedAtAsc(String)`.
- Produces: `TradeOutcomeRepository.findByCaseIdOrderByHorizonAsc(String)` and `findByCaseIdAndBaselineTypeAndHorizon(String caseId, String baselineType, String horizon)`.

- [ ] **Step 1: Write the failing persistence test**

```java
@DataJpaTest
class TradeFeedbackPersistenceTest {
    @Autowired TradeCaseRepository cases;
    @Autowired TradeFillRepository fills;
    @Autowired TradeOutcomeRepository outcomes;

    @Test
    void persistsOneRecommendationWithMultipleOrderedFillsAndOutcomes() {
        Instant now = Instant.parse("2026-07-11T07:00:00Z");
        cases.save(TradeCaseEntity.planned(
                "case-1", "fp-1", null, "002714", "牧原股份", "MISPRICING",
                "分批建仓", new BigDecimal("78"), "mispricing-v2",
                new BigDecimal("36.20"), now, "{}", now));
        fills.save(TradeFillEntity.create("fill-2", "case-1", "SELL", now.plusSeconds(7200),
                new BigDecimal("38.00"), 100L, now));
        fills.save(TradeFillEntity.create("fill-1", "case-1", "BUY", now.plusSeconds(3600),
                new BigDecimal("35.00"), 200L, now));
        outcomes.save(TradeOutcomeEntity.pending("outcome-1", "case-1", "RECOMMENDATION", "T5", now));

        assertThat(fills.findByCaseIdOrderByExecutedAtAscCreatedAtAsc("case-1"))
                .extracting(TradeFillEntity::getSide).containsExactly("BUY", "SELL");
        assertThat(outcomes.findByCaseIdOrderByHorizonAsc("case-1")).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the persistence test and verify it fails**

Run: `mvn -pl apps/api -Dtest=TradeFeedbackPersistenceTest test`

Expected: compilation fails because the trade-feedback entities and repositories do not exist.

- [ ] **Step 3: Add portable schema and focused JPA entities**

Add tables with these constraints:

```sql
CREATE TABLE IF NOT EXISTS strategy_trade_case (
  case_id VARCHAR(36) PRIMARY KEY,
  recommendation_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  decision_id VARCHAR(36),
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  source_module VARCHAR(64) NOT NULL,
  recommendation_action VARCHAR(64) NOT NULL,
  recommendation_score NUMERIC(8, 2),
  rule_version VARCHAR(64) NOT NULL,
  recommended_price NUMERIC(20, 6) NOT NULL,
  recommended_at TIMESTAMP WITH TIME ZONE NOT NULL,
  recommendation_payload_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_case_decision FOREIGN KEY (decision_id)
    REFERENCES investment_decision_history(decision_id)
);

CREATE TABLE IF NOT EXISTS strategy_trade_fill (
  fill_id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  side VARCHAR(8) NOT NULL,
  executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  price NUMERIC(20, 6) NOT NULL,
  quantity BIGINT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_fill_case FOREIGN KEY (case_id) REFERENCES strategy_trade_case(case_id),
  CONSTRAINT ck_trade_fill_side CHECK (side IN ('BUY', 'SELL')),
  CONSTRAINT ck_trade_fill_price CHECK (price > 0),
  CONSTRAINT ck_trade_fill_quantity CHECK (quantity > 0)
);

CREATE TABLE IF NOT EXISTS strategy_outcome_snapshot (
  snapshot_id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  baseline_type VARCHAR(32) NOT NULL,
  horizon VARCHAR(16) NOT NULL,
  baseline_price NUMERIC(20, 6),
  evaluation_price NUMERIC(20, 6),
  evaluation_date DATE,
  return_pct NUMERIC(12, 4),
  max_runup_pct NUMERIC(12, 4),
  max_drawdown_pct NUMERIC(12, 4),
  status VARCHAR(32) NOT NULL,
  calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_trade_outcome_case FOREIGN KEY (case_id) REFERENCES strategy_trade_case(case_id),
  CONSTRAINT uk_trade_outcome_scope UNIQUE (case_id, baseline_type, horizon)
);
```

Implement plain JPA entities with explicit constructors/getters, `TEXT` payload columns, no `@Lob`, and repository methods named in the Interfaces block.

- [ ] **Step 4: Run persistence and schema compatibility tests**

Run: `mvn -pl apps/api -Dtest=TradeFeedbackPersistenceTest,SchemaCompatibilityTest test`

Expected: both tests pass, including the assertion that shared SQL contains no H2-only `CLOB` type.

- [ ] **Step 5: Commit the persistence slice**

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback apps/api/src/main/resources/schema.sql apps/api/src/test/java/com/aistock/research/tradefeedback apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java
git commit -m "feat: persist trade feedback cases"
```

### Task 2: Implement Fill Validation and Moving-Average Ledger Math

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeSide.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/LedgerFill.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/CreateTradeCaseRequest.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/UpsertTradeFillRequest.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerSummary.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerCalculator.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackService.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeLedgerCalculatorTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackServiceTest.java`

**Interfaces:**
- Consumes: repositories from Task 1.
- Produces: `TradeLedgerCalculator.calculate(List<TradeFillEntity>, BigDecimal latestPrice): TradeLedgerSummary`.
- Produces: service methods `createCase`, `addFill`, `updateFill`, `deleteFill`, `cancelCase`, `listCases`, and `getCase`.

- [ ] **Step 1: Write failing ledger tests**

```java
@Test
void calculatesWeightedCostPartialSaleAndRemainingPosition() {
    List<LedgerFill> fills = List.of(
            new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 100),
            new LedgerFill(BUY, at("2026-07-14T01:35:00Z"), decimal("40"), 300),
            new LedgerFill(SELL, at("2026-07-15T01:35:00Z"), decimal("45"), 150));

    TradeLedgerSummary result = calculator.calculate(fills, decimal("44"));

    assertThat(result.positionQuantity()).isEqualTo(250);
    assertThat(result.averageCost()).isEqualByComparingTo("37.5");
    assertThat(result.realizedProfit()).isEqualByComparingTo("1125");
    assertThat(result.unrealizedProfit()).isEqualByComparingTo("1625");
}

@Test
void rejectsSaleAboveAvailablePosition() {
    assertThatThrownBy(() -> calculator.calculate(List.of(
            new LedgerFill(BUY, at("2026-07-13T01:35:00Z"), decimal("30"), 100),
            new LedgerFill(SELL, at("2026-07-13T02:35:00Z"), decimal("31"), 101)), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("卖出股数超过当前持仓");
}
```

- [ ] **Step 2: Run ledger tests and verify they fail**

Run: `mvn -pl apps/api -Dtest=TradeLedgerCalculatorTest test`

Expected: compilation fails because the calculator types do not exist.

- [ ] **Step 3: Implement deterministic ledger calculation**

Use moving weighted-average cost:

```java
if (fill.side() == BUY) {
    BigDecimal oldCost = averageCost.multiply(BigDecimal.valueOf(position));
    BigDecimal newCost = fill.price().multiply(BigDecimal.valueOf(fill.quantity()));
    position += fill.quantity();
    averageCost = oldCost.add(newCost)
            .divide(BigDecimal.valueOf(position), 6, RoundingMode.HALF_UP);
} else {
    if (fill.quantity() > position) {
        throw new IllegalArgumentException("卖出股数超过当前持仓");
    }
    realized = realized.add(fill.price().subtract(averageCost)
            .multiply(BigDecimal.valueOf(fill.quantity())));
    position -= fill.quantity();
    if (position == 0) averageCost = BigDecimal.ZERO;
}
```

Sort by `executedAt`, then `createdAt`; reject fills before `recommendedAt`, non-positive values, invalid sides, edits that create an oversold prefix, and fills on cancelled cases.

- [ ] **Step 4: Write failing service tests for idempotency and status transitions**

Cover these exact cases:

```java
assertThat(service.createCase(request).caseId())
        .isEqualTo(service.createCase(request).caseId());
assertThat(service.addFill(caseId, buy100).status()).isEqualTo("HOLDING");
assertThat(service.addFill(caseId, sell40).status()).isEqualTo("HOLDING");
assertThat(service.addFill(caseId, sell60).status()).isEqualTo("CLOSED");
assertThatThrownBy(() -> service.addFill(caseId, sellOneMore))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 5: Implement the service and rerun tests**

Compute a SHA-256 recommendation fingerprint from normalized `symbol|sourceModule|ruleVersion|recommendedAt|decisionId`. Return the existing case when the same fingerprint is posted twice. Recompute status and ledger after every create/update/delete fill operation inside one transaction.

Run: `mvn -pl apps/api -Dtest=TradeLedgerCalculatorTest,TradeFeedbackServiceTest test`

Expected: all ledger and service tests pass.

- [ ] **Step 6: Commit ledger behavior**

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback apps/api/src/test/java/com/aistock/research/tradefeedback
git commit -m "feat: calculate multi-fill trade ledger"
```

### Task 3: Expose the Trade Review API

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseSummary.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseDetail.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillView.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackController.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackControllerTest.java`

**Interfaces:**
- Consumes: `TradeFeedbackService` from Task 2.
- Produces: REST endpoints under `/api/trade-cases` with JSON contracts consumed by Task 6.

- [ ] **Step 1: Write failing MockMvc controller tests**

```java
mockMvc.perform(post("/api/trade-cases/{id}/fills", "case-1")
        .contentType(APPLICATION_JSON)
        .content("""
                {"side":"BUY","executedAt":"2026-07-13T01:35:00Z","price":35.20,"quantity":200}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ledger.positionQuantity").value(200));

mockMvc.perform(post("/api/trade-cases/{id}/fills", "case-1")
        .contentType(APPLICATION_JSON)
        .content("""{"side":"SELL","executedAt":"2026-07-13T02:35:00Z","price":36,"quantity":201}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过当前持仓")));
```

- [ ] **Step 2: Run the controller test and verify it fails**

Run: `mvn -pl apps/api -Dtest=TradeFeedbackControllerTest test`

Expected: HTTP 404 because `/api/trade-cases` is not mapped.

- [ ] **Step 3: Implement controller and response records**

Map exactly:

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
TradeCaseDetail create(@Valid @RequestBody CreateTradeCaseRequest request)

@GetMapping
List<TradeCaseSummary> list(@RequestParam(required = false) String status,
                            @RequestParam(required = false) String symbol)

@GetMapping("/{caseId}")
TradeCaseDetail detail(@PathVariable String caseId)

@PostMapping("/{caseId}/fills")
@ResponseStatus(HttpStatus.CREATED)
TradeCaseDetail addFill(@PathVariable String caseId,
                        @Valid @RequestBody UpsertTradeFillRequest request)

@PutMapping("/{caseId}/fills/{fillId}")
TradeCaseDetail updateFill(@PathVariable String caseId,
                           @PathVariable String fillId,
                           @Valid @RequestBody UpsertTradeFillRequest request)

@DeleteMapping("/{caseId}/fills/{fillId}")
TradeCaseDetail deleteFill(@PathVariable String caseId,
                           @PathVariable String fillId)

@PostMapping("/{caseId}/cancel")
TradeCaseDetail cancel(@PathVariable String caseId)
```

Use `ResponseStatusException` with 404 for missing cases/fills and 409 for invalid state transitions; existing global exception handling must preserve those statuses.

- [ ] **Step 4: Run API tests**

Run: `mvn -pl apps/api -Dtest=TradeFeedbackControllerTest,GlobalExceptionHandlerTest test`

Expected: controller contracts and status preservation pass.

- [ ] **Step 5: Commit the API slice**

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback apps/api/src/test/java/com/aistock/research/tradefeedback
git commit -m "feat: expose trade review API"
```

### Task 4: Calculate T+1/T+5/T+20 and Closed-Trade Outcomes

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeMarketDataGateway.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/EastMoneyTradeMarketDataGateway.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/MarketBar.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/OutcomeResult.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeCalculator.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeService.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeScheduler.java`
- Modify: `apps/api/src/main/java/com/aistock/research/AiStockResearchApplication.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackController.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeCalculatorTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeServiceTest.java`

**Interfaces:**
- Produces: `TradeMarketDataGateway.dailyKLines(String, LocalDate, LocalDate)` and `latestPrice(String)`.
- Produces: `TradeOutcomeService.refresh(String caseId)` and `refreshOpenCases()`.

- [ ] **Step 1: Write failing trading-row outcome tests**

```java
@Test
void evaluatesHorizonsByTradingRowsAndKeepsFutureHorizonPending() {
    List<MarketBar> rows = bars(
            bar("2026-07-13", "10.5", "10.8", "9.8"),
            bar("2026-07-14", "11.0", "11.2", "10.1"),
            bar("2026-07-15", "9.5", "11.4", "9.0"),
            bar("2026-07-16", "10.8", "11.5", "8.8"),
            bar("2026-07-17", "12.0", "12.2", "8.7"));

    List<OutcomeResult> result = calculator.evaluate(decimal("10"), rows, at("2026-07-10T07:00:00Z"));

    assertThat(outcome(result, "T1").returnPct()).isEqualByComparingTo("5.0000");
    assertThat(outcome(result, "T5").returnPct()).isEqualByComparingTo("20.0000");
    assertThat(outcome(result, "T5").maxRunupPct()).isEqualByComparingTo("22.0000");
    assertThat(outcome(result, "T5").maxDrawdownPct()).isEqualByComparingTo("-13.0000");
    assertThat(outcome(result, "T20").status()).isEqualTo("PENDING");
}
```

- [ ] **Step 2: Run the outcome tests and verify they fail**

Run: `mvn -pl apps/api -Dtest=TradeOutcomeCalculatorTest test`

Expected: compilation fails because outcome types do not exist.

- [ ] **Step 3: Implement pure outcome math and the EastMoney adapter**

The adapter calls:

```java
eastMoneyClient.fetchDailyKLines(symbol, begin, end);
eastMoneyClient.fetchEastMoneyQuotesBySymbols(List.of(symbol), 1);
```

It falls back to Tencent quotes only when the EastMoney symbol quote is unavailable. Daily K-lines are already archived through `KlineHistoryRecorder`; no duplicate archive path is added.

- [ ] **Step 4: Write failing refresh service tests**

Mock the gateway and verify:

```java
service.refresh("case-1");
service.refresh("case-1");
assertThat(outcomes.findByCaseIdOrderByHorizonAsc("case-1"))
        .extracting(TradeOutcomeEntity::getHorizon)
        .containsExactlyInAnyOrder("T1", "T5", "T20", "CURRENT");
verify(outcomes, atLeastOnce()).saveAll(anyList());
```

Also verify a gateway exception leaves existing matured snapshots untouched and returns a refresh warning rather than deleting history.

- [ ] **Step 5: Implement idempotent upsert, refresh API, and scheduler**

Add:

```java
@PostMapping("/{caseId}/refresh")
TradeCaseDetail refresh(@PathVariable String caseId)
```

Enable scheduling and run after close:

```java
@Scheduled(cron = "0 10 18 * * MON-FRI", zone = "Asia/Shanghai")
public void refreshOpenCases() {
    outcomeService.refreshOpenCases();
}
```

Catch failures per case so one stock cannot stop the batch.

- [ ] **Step 6: Run outcome tests and commit**

Run: `mvn -pl apps/api -Dtest=TradeOutcomeCalculatorTest,TradeOutcomeServiceTest,TradeFeedbackControllerTest test`

Expected: all outcome and API tests pass.

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback apps/api/src/main/java/com/aistock/research/AiStockResearchApplication.java apps/api/src/test/java/com/aistock/research/tradefeedback
git commit -m "feat: evaluate recommendation outcomes"
```

### Task 5: Aggregate Historical Feedback and Inject It into Agent Prompts

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackSummary.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackService.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackController.java`
- Modify: `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteePromptPreview.java`
- Modify: `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteePromptService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteeAiService.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/StrategyFeedbackServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/committee/AgentCommitteePromptServiceTest.java`

**Interfaces:**
- Produces: `StrategyFeedbackService.summaries()` and `promptContext(String symbol)`.
- Produces: `GET /api/strategy-feedback`.

- [ ] **Step 1: Write failing aggregation threshold tests**

```java
@Test
void hidesPromptContextBelowFiveAndCapsAdjustmentAfterTwentySamples() {
    seedMaturedSamples("SHORT_TERM", "short-term-right-side-v2", 4, 3, decimal("2.0"));
    assertThat(service.promptContext("002714")).isEmpty();

    seedMaturedSamples("MISPRICING", "mispricing-v2", 20, 18, decimal("12.0"));
    StrategyFeedbackSummary summary = summary("MISPRICING", "mispricing-v2");
    assertThat(summary.promptEligible()).isTrue();
    assertThat(summary.adjustmentEligible()).isTrue();
    assertThat(summary.reliabilityAdjustment()).isEqualByComparingTo("5.00");
}
```

- [ ] **Step 2: Run feedback tests and verify they fail**

Run: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest test`

Expected: compilation fails because feedback aggregation does not exist.

- [ ] **Step 3: Implement aggregation using matured T20 snapshots**

Group by `sourceModule + ruleVersion`; calculate count, positive count/rate, average and median return, average run-up/drawdown, and average first-buy execution deviation. Calculate the reliability suggestion only for 20 or more matured samples:

```java
BigDecimal raw = positiveRate.subtract(new BigDecimal("0.50"))
        .multiply(new BigDecimal("10"))
        .add(medianReturn.signum() > 0 ? BigDecimal.ONE : BigDecimal.ONE.negate());
BigDecimal adjustment = raw.max(new BigDecimal("-5")).min(new BigDecimal("5"));
```

Include sample count and date range in every summary.

- [ ] **Step 4: Inject eligible summaries into the committee prompt**

Extend prompt preview with `historicalFeedback` and append this instruction:

```text
历史策略反馈只是带样本量的校准证据，不能覆盖公告、财务、流动性和风险否决。
样本不足 20 时不得建议调整分数；达到 20 时，可靠性修正也只能使用输入中的 ±5 上限。
```

Keep the deterministic consensus score unchanged. Allow historical evidence only in the AI committee summary, counter-evidence, and suggested stage adjustment.

- [ ] **Step 5: Run feedback and prompt tests**

Run: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest,AgentCommitteePromptServiceTest test`

Expected: the 5/20 sample gates, `±5` cap, and prompt evidence text all pass.

- [ ] **Step 6: Commit Agent calibration**

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback apps/api/src/main/java/com/aistock/research/committee apps/api/src/test/java/com/aistock/research/tradefeedback apps/api/src/test/java/com/aistock/research/committee
git commit -m "feat: calibrate agents with trade outcomes"
```

### Task 6: Add Typed Frontend API and Reusable “加入复盘” Control

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Create: `apps/web-react/src/store/tradeFeedbackStore.ts`
- Create: `apps/web-react/src/components/tradefeedback/TradeReviewButton.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/TechTrackerPage.tsx`
- Modify: `apps/web-react/src/pages/MispricingPage.tsx`
- Modify: `apps/web-react/src/pages/CycleTrialPage.tsx`
- Modify: `apps/web-react/src/pages/DailySignalsPage.tsx`

**Interfaces:**
- Consumes: REST contracts from Task 3.
- Produces: `TradeReviewButton` props `{ symbol, companyName, sourceModule, action, score, ruleVersion, recommendedPrice, recommendedAt, payload }`.

- [ ] **Step 1: Add exact TypeScript contracts**

```ts
export type TradeSide = 'BUY' | 'SELL'
export type TradeCaseStatus = 'PLANNED' | 'HOLDING' | 'CLOSED' | 'CANCELLED'

export interface CreateTradeCaseRequest {
  decisionId?: string | null
  symbol: string
  companyName: string
  sourceModule: string
  recommendationAction: string
  recommendationScore?: number | null
  ruleVersion: string
  recommendedPrice: number
  recommendedAt: string
  recommendationPayload: unknown
}

export interface TradeFill {
  fillId: string
  side: TradeSide
  executedAt: string
  price: number
  quantity: number
}
```

Add `TradeLedgerSummary`, `TradeOutcomeSnapshot`, `TradeCaseSummary`, `TradeCaseDetail`, and `StrategyFeedbackSummary` matching backend field names exactly.

- [ ] **Step 2: Add API and Zustand actions**

```ts
export const createTradeCase = (request: CreateTradeCaseRequest) =>
  http.post<TradeCaseDetail>('/trade-cases', request).then((res) => res.data)

export const addTradeFill = (caseId: string, request: UpsertTradeFillRequest) =>
  http.post<TradeCaseDetail>(`/trade-cases/${encodeURIComponent(caseId)}/fills`, request)
    .then((res) => res.data)
```

Store cases by `caseId`, keep a `symbol|sourceModule|recommendedAt` lookup, and expose `refreshCases()` plus `ensureCase(request)`.

- [ ] **Step 3: Build the reusable command control**

Use Lucide `ClipboardPlus` / `ClipboardCheck`, a tooltip, loading state, and toast. Disable only while submitting. The button must not alter recommendation sorting or filtering.

- [ ] **Step 4: Place the control on all recommendation modules**

Pass the card’s actual generated time and price; pass the complete candidate as `recommendationPayload`. Do not synthesize a missing price. When price or timestamp is missing, show a disabled control with an explicit tooltip.

- [ ] **Step 5: Build the frontend**

Run: `npm run build --prefix apps/web-react`

Expected: TypeScript and Vite build complete without errors.

- [ ] **Step 6: Commit the recommendation capture UI**

```bash
git add apps/web-react/src/types.ts apps/web-react/src/api/client.ts apps/web-react/src/store/tradeFeedbackStore.ts apps/web-react/src/components/tradefeedback apps/web-react/src/pages
git commit -m "feat: capture recommendations for review"
```

### Task 7: Build the Trade Review Page

**Files:**
- Create: `apps/web-react/src/pages/TradeReviewPage.tsx`
- Modify: `apps/web-react/src/App.tsx`
- Modify: `apps/web-react/src/components/layout/pageMeta.ts`
- Modify: `apps/web-react/src/components/layout/NavRail.tsx`
- Modify: `apps/web-react/src/index.css`

**Interfaces:**
- Consumes: frontend store/API from Task 6.
- Produces: route `/trade-review` and navigation key `tradeReview`.

- [ ] **Step 1: Add route metadata and lazy route**

```ts
export type PageKey = 'market' | 'shortTerm' | 'tech' | 'mispricing' | 'cycle' |
  'signals' | 'watchlist' | 'tradeReview' | 'rules' | 'settings'
```

Use title `交易复盘`, eyebrow `TRADE REVIEW`, and description `连接推荐现场、真实分批成交与后续策略表现。`.

- [ ] **Step 2: Implement the scan-friendly case list**

Display an unframed page band with status tabs, then one `DataTable` showing symbol, source, recommended action/time, position, weighted cost, latest price, total gross return, and T1/T5/T20. Clicking a row opens a right-side detail panel; do not nest cards.

- [ ] **Step 3: Implement fill entry/edit/delete**

Use a segmented buy/sell control, `datetime-local`, numeric price input with `step="0.01"`, and integer share input with `step="100"`. Submit ISO timestamps, surface backend validation messages, confirm deletions, and immediately replace the selected detail with the response.

- [ ] **Step 4: Implement details and outcome states**

Show recommendation snapshot, fill timeline, execution deviation, realized/unrealized gross profit, and distinct `PENDING`, `MATURED`, `UNAVAILABLE` badges. Keep `收益未计佣金、印花税、分红和送转股` visible near the totals.

- [ ] **Step 5: Build and visually verify desktop/mobile**

Run: `npm run build --prefix apps/web-react`

Then use the in-app browser at widths 1440 and 390 to verify:

- no overlapping table controls or clipped labels;
- fill modal fields remain usable on mobile;
- pending/error states are visible;
- refreshing the page preserves data.

- [ ] **Step 6: Commit the page**

```bash
git add apps/web-react/src/App.tsx apps/web-react/src/components/layout apps/web-react/src/pages/TradeReviewPage.tsx apps/web-react/src/index.css
git commit -m "feat: add trade review workspace"
```

### Task 8: Complete Database, Docker, and End-to-End Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docker-compose.yml` only if health or persistence configuration needs correction.
- Test: all backend and frontend verification commands.

**Interfaces:**
- Consumes: the completed API and web slices.
- Produces: documented operating workflow and verified persistent deployment.

- [ ] **Step 1: Run the complete backend suite**

Run: `mvn -pl apps/api test`

Expected: zero failures and zero errors.

- [ ] **Step 2: Prove the shared schema on empty PostgreSQL 16**

Run the temporary database and apply the schema with `ON_ERROR_STOP=1`. Start the packaged API against `jdbc:postgresql://127.0.0.1:55432/aistock`, then call `/actuator/health`.

Expected: every table/index/constraint is created and health is `UP`.

- [ ] **Step 3: Build and restart the application stack**

Run:

```bash
mvn -pl apps/api -DskipTests package
npm run build --prefix apps/web-react
docker compose up -d --build
docker compose ps
```

Expected: API and web containers are healthy; the API Java process still runs as the non-root app user.

- [ ] **Step 4: Exercise one complete multi-fill case**

Create a real recommendation case, then submit two buys and two sells through the API. Verify after each fill:

- weighted average cost;
- remaining quantity;
- realized and total gross profit;
- `PLANNED -> HOLDING -> CLOSED` transition;
- repeated refresh does not duplicate T1/T5/T20 rows.

- [ ] **Step 5: Verify persistence across restart**

Restart the API container and fetch the same case. Expected: case, fills, outcomes, and strategy feedback are unchanged.

- [ ] **Step 6: Update operating documentation**

Document the recommendation capture workflow, fill fields, gross-return scope, automatic 18:10 refresh, Agent 5/20 sample gates, H2 local storage, and PostgreSQL production option.

- [ ] **Step 7: Commit verification documentation**

```bash
git add README.md docs/architecture.md docker-compose.yml
git commit -m "docs: document trade feedback workflow"
```

## Final Verification Checklist

- [ ] No static stock whitelist or special-attention list is used by recommendation universe code.
- [ ] H2 and PostgreSQL accept the same `schema.sql`.
- [ ] Duplicate recommendation capture is idempotent.
- [ ] A sell that overshoots holdings is rejected with a 4xx response.
- [ ] T+1/T+5/T+20 use trading rows and preserve pending/unavailable states.
- [ ] Historical feedback never bypasses hard risk rules and never exceeds `±5`.
- [ ] Trade review survives Docker restart.
- [ ] React build and desktop/mobile browser checks pass without console errors.
