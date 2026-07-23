# Trade Review Realized P&L Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users record real buys directly from short-term and long-term recommendations, apply configurable frozen transaction fees, and show a closed-trades-only account result.

**Architecture:** Extend the existing recommendation-attested trade-case ledger instead of creating a parallel portfolio subsystem. Persist a fee-policy snapshot on every fill and revision, calculate weighted-average net realized P&L in the backend, expose a pagination-independent overview, and reuse the current accessible nested-dialog behavior for direct entries and settings.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, H2/PostgreSQL-compatible `schema.sql`, JUnit 5/AssertJ/MockMvc, React 18, TypeScript, Zustand, Axios, Vitest, Testing Library, Tailwind CSS.

## Global Constraints

- Account aggregate profit and return include only cases with zero position and at least one buy and one sell.
- Open cases stay visible but never contribute unrealized profit or any total-profit estimate.
- Commission applies to both sides, stamp duty to sells only, and transfer fee to both sides.
- Fee settings accept non-negative values; minimum commission `0` represents a no-minimum-commission channel.
- Every new fill freezes the active fee policy. Later settings changes never rewrite historical fills.
- Fill corrections reuse the original fill's frozen policy; fill deletion remains an immutable `VOID` revision.
- Existing weighted-average position, partial fills, oversell protection, recommendation attestation, and audit projection remain authoritative.
- Browser-created recommendation facts are untrusted; both target modules must use server-issued attestation tokens.
- Preserve unrelated dirty-worktree changes and stage only task-owned files.

---

### Task 1: Persist and Validate Configurable Fee Policy

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeePolicy.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsView.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/UpdateTradeFeeSettingsRequest.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsService.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsController.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsControllerTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsPersistenceTest.java`

**Interfaces:**
- Produces: `TradeFeePolicy(BigDecimal commissionRate, BigDecimal minimumCommission, BigDecimal stampDutyRate, BigDecimal transferFeeRate)` using decimal fractions internally.
- Produces: `TradeFeeSettingsService.currentPolicy()`, `view()`, and `update(UpdateTradeFeeSettingsRequest)`.
- Produces: `GET /api/trade-fee-settings` and `PUT /api/trade-fee-settings` with percentage-valued JSON fields.

- [ ] **Step 1: Write failing policy and persistence tests**

Assert the default API view is exactly:

```java
TradeFeeSettingsView actual = service.view();
assertThat(actual.commissionPercent()).isEqualByComparingTo("0.025");
assertThat(actual.minimumCommission()).isEqualByComparingTo("5.00");
assertThat(actual.stampDutyPercent()).isEqualByComparingTo("0.05");
assertThat(actual.transferFeePercent()).isEqualByComparingTo("0.001");
assertThat(actual.updatedAt()).isNotNull();
```

Assert `minimumCommission=0` saves successfully, negative fields fail with their field names, and two service instances read the same persisted singleton row.

- [ ] **Step 2: Run the tests and confirm RED**

Run:

```bash
mvn -pl apps/api -Dtest=TradeFeeSettingsServiceTest,TradeFeeSettingsControllerTest,TradeFeeSettingsPersistenceTest test
```

Expected: test compilation fails because the fee settings types and endpoints do not exist.

- [ ] **Step 3: Add the singleton schema and domain types**

Add an idempotent table with one row identified by `settings_id = 1`:

```sql
CREATE TABLE IF NOT EXISTS trade_fee_settings (
  settings_id INTEGER PRIMARY KEY,
  commission_rate NUMERIC(12, 8) NOT NULL,
  minimum_commission NUMERIC(20, 2) NOT NULL,
  stamp_duty_rate NUMERIC(12, 8) NOT NULL,
  transfer_fee_rate NUMERIC(12, 8) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT ck_trade_fee_settings_singleton CHECK (settings_id = 1),
  CONSTRAINT ck_trade_fee_settings_non_negative CHECK (
    commission_rate >= 0 AND minimum_commission >= 0
    AND stamp_duty_rate >= 0 AND transfer_fee_rate >= 0
  )
);
```

`TradeFeeSettingsService` seeds defaults only when row `1` is absent. Convert API percentages with `movePointLeft(2)` and internal rates with `movePointRight(2)`; never divide a rate in the fee calculator again.

- [ ] **Step 4: Add controller validation**

Use this request contract:

```java
public record UpdateTradeFeeSettingsRequest(
        @NotNull @DecimalMin("0") BigDecimal commissionPercent,
        @NotNull @DecimalMin("0") BigDecimal minimumCommission,
        @NotNull @DecimalMin("0") BigDecimal stampDutyPercent,
        @NotNull @DecimalMin("0") BigDecimal transferFeePercent
) {}
```

Return the saved `TradeFeeSettingsView`; translate optimistic-lock conflicts to HTTP 409 through the project's existing exception conventions.

- [ ] **Step 5: Run focused tests and commit**

Run the command from Step 2 and `git diff --check`. Expected: all focused tests pass.

Commit:

```bash
git add apps/api/src/main/resources/schema.sql \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsEntity.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsRepository.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeePolicy.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsView.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/UpdateTradeFeeSettingsRequest.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsService.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeSettingsController.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsServiceTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsControllerTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeSettingsPersistenceTest.java
git commit -m "feat: add configurable trade fees"
```

Stage only the new fee-policy files and `schema.sql`, not unrelated trade-feedback changes.

---

### Task 2: Freeze Fill Fees and Calculate Net Realized Results

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeBreakdown.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeCalculator.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillEntity.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillRevisionEntity.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillProjector.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/LedgerFill.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillView.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerSummary.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerCalculator.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackMapper.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackService.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeCalculatorTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeLedgerCalculatorTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFillAuditTrailTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackPersistenceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackServiceTest.java`

**Interfaces:**
- Consumes: `TradeFeeSettingsService.currentPolicy()` from Task 1.
- Produces: immutable `TradeFeeBreakdown` on each projected fill.
- Produces: `TradeLedgerSummary` fields `openInvestedCost`, `totalFees`, `closedBuyCashOutflow`, `realizedReturnPercent`, and `aggregateEligible`.

- [ ] **Step 1: Write failing fee-formula tests**

Cover both fee channels:

```java
TradeFeePolicy standard = new TradeFeePolicy(
        decimal("0.00025"), decimal("5"), decimal("0.0005"), decimal("0.00001"));
assertThat(calculator.calculate(BUY, decimal("10"), 100, standard).totalFee())
        .isEqualByComparingTo("5.01");
assertThat(calculator.calculate(SELL, decimal("10"), 100, standard).totalFee())
        .isEqualByComparingTo("5.51");

TradeFeePolicy noMinimum = new TradeFeePolicy(
        decimal("0.00025"), decimal("0"), decimal("0.0005"), decimal("0.00001"));
assertThat(calculator.calculate(BUY, decimal("10"), 100, noMinimum).totalFee())
        .isEqualByComparingTo("0.26");
```

Also assert component-level `HALF_UP` cent rounding and zero commission when commission rate is zero.

- [ ] **Step 2: Write failing ledger and audit tests**

For a standard-fee buy of 100 shares at 10 and sell at 11, assert:

```java
assertThat(result.positionQuantity()).isZero();
assertThat(result.totalFees()).isEqualByComparingTo("10.57");
assertThat(result.closedBuyCashOutflow()).isEqualByComparingTo("1005.01");
assertThat(result.realizedProfit()).isEqualByComparingTo("89.43");
assertThat(result.totalProfit()).isEqualByComparingTo("89.43");
assertThat(result.aggregateEligible()).isTrue();
```

For an open or partially sold position, assert `unrealizedProfit == null`, `totalProfit == null`, and `aggregateEligible == false`. Correcting a fill must retain its original rates/minimum while recomputing amounts; deleting it must project the preceding fee snapshot into the `VOID` revision.

- [ ] **Step 3: Run tests and confirm RED**

```bash
mvn -pl apps/api -Dtest=TradeFeeCalculatorTest,TradeLedgerCalculatorTest,TradeFillAuditTrailTest,TradeFeedbackPersistenceTest,TradeFeedbackServiceTest test
```

Expected: missing fee snapshots and net-ledger fields fail compilation/assertions.

- [ ] **Step 4: Extend schema with zero-fee-compatible columns**

Add `gross_amount`, the four policy fields, and four fee amount fields to both `strategy_trade_fill` and `strategy_trade_fill_revision`. Use `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ... DEFAULT 0 NOT NULL` so existing rows preserve their prior gross accounting as zero-fee history.

- [ ] **Step 5: Implement one authoritative fee calculator**

Use this shape:

```java
public TradeFeeBreakdown calculate(TradeSide side, BigDecimal price, long quantity, TradeFeePolicy policy) {
    BigDecimal gross = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    BigDecimal rawCommission = gross.multiply(policy.commissionRate());
    BigDecimal commission = policy.commissionRate().signum() == 0
            ? BigDecimal.ZERO.setScale(2)
            : rawCommission.max(policy.minimumCommission()).setScale(2, RoundingMode.HALF_UP);
    BigDecimal stamp = side == TradeSide.SELL
            ? gross.multiply(policy.stampDutyRate()).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2);
    BigDecimal transfer = gross.multiply(policy.transferFeeRate()).setScale(2, RoundingMode.HALF_UP);
    return TradeFeeBreakdown.of(gross, policy, commission, stamp, transfer);
}
```

- [ ] **Step 6: Thread snapshots through create, correction, void, projection, mapper, and ledger**

On new fill, use the current policy. On correction, derive `TradeFeePolicy` from the active fill snapshot. Replace market-price unrealized math with `null` and calculate sell realization as net proceeds minus fee-inclusive average cost. Preserve oversell validation and deterministic ordering.

- [ ] **Step 7: Run regression tests and commit**

Run Step 3 plus:

```bash
mvn -pl apps/api -Dtest=TradeFeedbackControllerTest,TradeFeedbackConcurrencyIntegrationTest,TradeFeedbackCriticalServiceTest test
git diff --check
```

Expected: all pass.

Commit:

```bash
git add apps/api/src/main/resources/schema.sql \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeBreakdown.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeeCalculator.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillEntity.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillRevisionEntity.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillSnapshot.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillProjector.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/LedgerFill.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillView.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerSummary.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerCalculator.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackMapper.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackService.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeeCalculatorTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeLedgerCalculatorTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFillAuditTrailTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackPersistenceTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackServiceTest.java
git commit -m "feat: calculate net realized trade results"
```

---

### Task 3: Add Atomic Direct Entry and Closed-Only Account Overview

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/CreateTradeEntryRequest.java`
- Create: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeAccountOverview.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackController.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseRepository.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackControllerTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackConcurrencyIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/trade-cases/entries` with `attestationToken`, `executedAt`, `price`, and `quantity`.
- Produces: `GET /api/trade-cases/overview` returning pagination-independent closed-only totals.

- [ ] **Step 1: Write failing atomic-entry tests**

Use this request contract:

```java
public record CreateTradeEntryRequest(
        @NotBlank String attestationToken,
        @NotNull Instant executedAt,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price,
        @Positive long quantity
) {}
```

Assert the endpoint creates one case plus one `BUY` fill and returns `HOLDING`. Invalid/expired attestation, invalid fill time, or fee-calculation failure must leave neither a new case nor a fill. Reusing the same recommendation must append the new buy to the existing case rather than violate the fingerprint constraint.

- [ ] **Step 2: Write failing overview tests**

Create one closed, one holding, one planned, and one cancelled case. Assert only the closed case contributes to:

```java
Instant fixedNow = Instant.parse("2026-07-23T07:00:00Z");
assertThat(service.overview()).isEqualTo(new TradeAccountOverview(
        4, 1, 1, 1,
        new BigDecimal("1005.01"),
        new BigDecimal("89.43"),
        new BigDecimal("8.89841892"),
        new BigDecimal("10.57"),
        fixedNow));
```

Reopen the closed case with a later buy and assert it immediately leaves the aggregate.

- [ ] **Step 3: Run tests and confirm RED**

```bash
mvn -pl apps/api -Dtest=TradeFeedbackControllerTest,TradeFeedbackServiceTest,TradeFeedbackConcurrencyIntegrationTest test
```

- [ ] **Step 4: Refactor case creation into a shared transactional primitive**

Extract attestation verification and fingerprint-based case lookup/creation so `createCase` and `createEntry` use one implementation. `createEntry` must lock the case before appending the fill and commit case plus fill in the same transaction.

- [ ] **Step 5: Calculate overview from all active projected fills**

Do not derive overview from the paginated `GET /api/trade-cases` response. Load all non-cancelled cases in a bounded repository projection, batch-load originals/revisions, calculate ledgers once, and include only `ledger.aggregateEligible()` rows. Return zeros with a `null` return percentage when no eligible denominator exists.

- [ ] **Step 6: Run focused tests and commit**

Run Step 3, `TradeFillAuditTrailTest`, and `TradeFeedbackCriticalServiceTest`; then `git diff --check`.

Commit:

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback/CreateTradeEntryRequest.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeAccountOverview.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackController.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFeedbackService.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/TradeCaseRepository.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackControllerTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackServiceTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/TradeFeedbackConcurrencyIntegrationTest.java
git commit -m "feat: add direct trade entry and account overview"
```

---

### Task 4: Attest Long-Term Value Recommendations

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationSource.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanController.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationAttestationServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationControllerAttestationTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Produces: `RecommendationSource.LONG_TERM_VALUE("LONG_TERM_VALUE", "long-term-value-v1")`.
- Produces: `MarketScanReport.tradeCaptureTokens(): Map<String,String>` keyed by six-digit symbol.

- [ ] **Step 1: Write failing attestation tests**

Assert a factual candidate with positive price and usable `marketTimestamp` receives a token; missing/stale timestamps do not. Requiring the token must yield source module `LONG_TERM_VALUE`, rule version `long-term-value-v1`, and server-captured candidate payload.

- [ ] **Step 2: Run tests and confirm RED**

```bash
mvn -pl apps/api -Dtest=RecommendationAttestationServiceTest,RecommendationControllerAttestationTest,MarketScanServiceTest test
```

- [ ] **Step 3: Implement market-scan attestation**

Add `attest(MarketScanReport)` using candidate `todayAdvice.actionLabel`, `score.finalScore`, `latestPrice`, and `marketTimestamp`. Put `tradeCaptureTokens` immediately before `generatedAt` in the report record, pass an empty map from the service, and let the controller return `attestationService.attest(marketScanService.report(...))`.

- [ ] **Step 4: Run regression tests and commit**

Run Step 2 and `mvn -pl apps/api -Dtest=TradeFeedbackServiceTest test`; then `git diff --check`.

Commit:

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationSource.java \
  apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java \
  apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java \
  apps/api/src/main/java/com/aistock/research/market/MarketScanController.java \
  apps/api/src/main/java/com/aistock/research/market/MarketScanService.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationAttestationServiceTest.java \
  apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationControllerAttestationTest.java \
  apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java
git commit -m "feat: attest long-term value picks"
```

---

### Task 5: Add Direct Buy Dialog to Short-Term and Long-Term Details

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/store/tradeFeedbackStore.ts`
- Modify: `apps/web-react/src/store/tradeFeedbackStore.test.ts`
- Create: `apps/web-react/src/lib/tradeFees.ts`
- Create: `apps/web-react/src/lib/tradeFees.test.ts`
- Create: `apps/web-react/src/components/tradefeedback/TradeEntryButton.tsx`
- Create: `apps/web-react/src/components/tradefeedback/TradeEntryButton.test.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Create: `apps/web-react/src/pages/MarketScanPage.test.tsx`

**Interfaces:**
- Consumes: Task 1 fee settings, Task 3 direct-entry endpoint, Task 4 long-term token map.
- Produces: reusable `TradeEntryButton` for a trusted recommendation.
- Produces: `useTradeFeedbackStore.createEntry(request)`.

- [ ] **Step 1: Add failing client/store and fee-preview tests**

Add types matching backend JSON:

```ts
export interface TradeFeeSettings {
  commissionPercent: number
  minimumCommission: number
  stampDutyPercent: number
  transferFeePercent: number
  updatedAt: string
}

export interface CreateTradeEntryRequest {
  attestationToken: string
  executedAt: string
  price: number
  quantity: number
}
```

Assert `createEntry` merges the returned detail into both store indexes. Assert the TypeScript fee preview matches backend examples for minimum `5` and `0`, with each component rounded to cents.

- [ ] **Step 2: Add failing interaction tests**

For both pages, click a candidate and then `录入买入`. Assert:

- source is `短线推荐` or `长期价投` and read-only;
- time defaults to a valid Shanghai-local value;
- price is prefilled from the candidate;
- quantity validation rejects zero/non-integers;
- fee preview changes when minimum commission is `0`;
- backdrop and Escape close only the entry dialog first;
- successful save calls `/trade-cases/entries`, updates the store, and restores focus;
- expired/missing token disables entry with a refresh explanation;
- failed save keeps entered values.

- [ ] **Step 3: Run tests and confirm RED**

```bash
npm --prefix apps/web-react test -- tradeFees.test.ts TradeEntryButton.test.tsx ShortTermPage.test.tsx MarketScanPage.test.tsx tradeFeedbackStore.test.ts
```

- [ ] **Step 4: Implement API/store contracts and preview helper**

Add `fetchTradeFeeSettings`, `createTradeEntry`, and store `createEntry`. The preview helper treats rates as percentages and divides by 100 exactly once. Label the preview `预计费用`; the saved backend detail remains authoritative.

- [ ] **Step 5: Implement the accessible nested entry dialog**

Use the same scroll-lock, focus trap, Escape propagation, backdrop, mobile bottom-sheet, and desktop centered-dialog behavior as the existing fill dialog. Keep stock, source, and recommendation price context visible without making them editable.

- [ ] **Step 6: Wire only the two requested recommendation modules**

Replace the short-term detail's bookmark control with `TradeEntryButton`. Add the same control to `MarketScanPage` using `report.tradeCaptureTokens[candidate.symbol]`. Do not change bookmark behavior in cycle, hot-tracker, mispricing, or daily-signal pages.

- [ ] **Step 7: Run tests/build and commit**

Run Step 3 plus:

```bash
npm --prefix apps/web-react run build
git diff --check
```

Commit:

```bash
git add apps/web-react/src/types.ts apps/web-react/src/api/client.ts apps/web-react/src/store/tradeFeedbackStore.ts apps/web-react/src/store/tradeFeedbackStore.test.ts apps/web-react/src/lib/tradeFees.ts apps/web-react/src/lib/tradeFees.test.ts apps/web-react/src/components/tradefeedback/TradeEntryButton.tsx apps/web-react/src/components/tradefeedback/TradeEntryButton.test.tsx apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx apps/web-react/src/pages/MarketScanPage.tsx apps/web-react/src/pages/MarketScanPage.test.tsx
git commit -m "feat: record buys from recommendation details"
```

---

### Task 6: Build the Closed-Only Trade Overview and Fee Settings UI

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/pages/TradeReviewPage.tsx`
- Create: `apps/web-react/src/pages/TradeReviewPage.test.tsx`
- Modify: `apps/web-react/src/lib/format.ts`

**Interfaces:**
- Consumes: `GET /api/trade-cases/overview`, fee settings GET/PUT, and extended ledger/fill views.
- Produces: account summary independent of case list pagination and compact fee settings dialog.

- [ ] **Step 1: Write failing overview and settings tests**

Mock a closed case and a holding case. Assert the summary renders backend aggregate values only, while the holding row/detail says `未清仓，不计总览` and shows position cost without a floating or total result. Assert fill rows show commission, stamp duty, transfer fee, and total fee.

Open fee settings and assert `最低佣金` accepts `0`, invalid negatives show field errors, save reloads the view, Escape/backdrop close, and focus returns to the settings button.

- [ ] **Step 2: Run tests and confirm RED**

```bash
npm --prefix apps/web-react test -- TradeReviewPage.test.tsx
```

- [ ] **Step 3: Add API types and load ownership**

Add `TradeAccountOverview`, extended ledger/fill fields, `fetchTradeAccountOverview`, and `updateTradeFeeSettings`. Load overview separately from the paginated case store. Use request-generation ownership so a stale refresh cannot overwrite a later refresh or clear its loading state.

- [ ] **Step 4: Replace gross/unrealized wording**

Summary cards use:

- `已完成交易`
- `净已实现收益`
- `已完成收益率`
- `累计交易费用`

Closed details show `净已实现收益` and `已完成收益率`. Holding details show `持仓成本`, `已卖部分净收益` when present, and `未清仓，不计总览`; never render `浮动收益` or `累计毛收益`.

- [ ] **Step 5: Implement compact fee settings dialog**

Use four numeric inputs with explicit percent/unit suffixes. Explain only the key accounting fact in visible copy: `最低佣金设为 0 可用于免五渠道；设置仅影响后续新增成交。` Do not place explanatory feature text elsewhere in the application.

- [ ] **Step 6: Run tests/build and commit**

```bash
npm --prefix apps/web-react test -- TradeReviewPage.test.tsx tradeFeedbackStore.test.ts
npm --prefix apps/web-react run build
git diff --check
```

Commit:

```bash
git add apps/web-react/src/types.ts apps/web-react/src/api/client.ts apps/web-react/src/pages/TradeReviewPage.tsx apps/web-react/src/pages/TradeReviewPage.test.tsx apps/web-react/src/lib/format.ts
git commit -m "feat: show closed trade account results"
```

---

### Task 7: Document, Verify, Restart, and Exercise the Full Flow

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/nacos-config.md`
- Create: `docs/trade-review-accounting.md`
- Test: existing backend/frontend suites

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reproducible accounting documentation and a healthy running system at `http://127.0.0.1:5176/`.

- [ ] **Step 1: Document the accounting contract**

Record the fee formulas, internal fraction versus UI percentage units, defaults, no-minimum configuration, fill snapshot immutability, weighted-average method, closed-only aggregate denominator, and exact meaning of every overview field. Explicitly distinguish strategy outcome snapshots from account P&L.

- [ ] **Step 2: Run complete automated verification**

```bash
mvn -pl apps/api test
npm --prefix apps/web-react test
npm --prefix apps/web-react run build
git diff --check
```

Expected: all tests and production build pass.

- [ ] **Step 3: Build and restart the application**

```bash
mvn -pl apps/api -DskipTests package
docker compose build api web
docker compose up -d api web
docker compose ps
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:5176/healthz
```

Expected: API reports `UP`, web returns `ok`, and both containers are healthy.

- [ ] **Step 4: Exercise persistence and accounting through the API**

Using a current server-issued short-term or long-term token, create a small buy, add a partial sell, verify it is excluded from overview, sell the remainder, and verify exact net profit/fees enter overview. Set minimum commission to `0`, add a separate controlled fill, and confirm the earlier fill fee does not change. Do not fabricate recommendation tokens or overwrite the user's existing trade records; use a test fixture/profile if a safe live candidate is unavailable.

- [ ] **Step 5: Verify desktop and mobile browser behavior**

At desktop and `390x844`, verify both recommendation pages, nested buy dialog closure/focus, trade-review overview, fee settings, closed/open labels, long text, and no overlap. Confirm no recommendation detail is auto-selected.

- [ ] **Step 6: Commit documentation**

```bash
git add docs/architecture.md docs/nacos-config.md docs/trade-review-accounting.md
git commit -m "docs: explain realized trade accounting"
```

## Completion Checklist

- [ ] Short-term and long-term details can record a real buy with default current time, price, and quantity.
- [ ] The recommendation source is trusted and persisted from a server attestation.
- [ ] Partial fills and audit revisions retain frozen fee policies.
- [ ] Minimum commission `0` works without changing old fills.
- [ ] Open positions are visible but do not enter account aggregate profit or return.
- [ ] Closed P&L is net of commission, stamp duty, and transfer fee.
- [ ] Account overview is independent of pagination and latest-price availability.
- [ ] Backend tests, frontend tests/build, Docker health, API persistence, and desktop/mobile checks pass.
