# Long-Term Value Discipline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an industry-aware, explainable long-term value assessment with reverse implied-expectation valuation, position discipline, and auditable thesis reviews to the full-A-share long-term recommendation page.

**Architecture:** A pure Java calculator in `com.aistock.research.longterm` receives the five-year annual indicator sequence and existing valuation/industry context. `UniversalAshareScreener` uses the result for VALUE-mode ranking and passes the immutable assessment through `MarketScanCandidate`; React renders the same server-calculated evidence without recomputing investment logic.

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5/AssertJ, React 18, TypeScript 5.6, Vitest, Vite.

## Global Constraints

- PE, PB and a five-year ROE threshold remain soft context; they cannot be universal eligibility cliffs.
- STANDARD, CYCLICAL and FINANCIAL use different valuation and quality interpretations.
- Missing capital expenditure means reverse DCF is labelled an owner-earnings proxy, never strict free cash flow.
- A 15% price decline triggers thesis review only; it never directly creates an add-position action.
- No symbol whitelist, symbol bonus, AI-created numeric factor, or automatic order path is allowed.
- Existing dirty-worktree changes must be preserved.

---

### Task 1: Long-Term Assessment Domain And Calculator

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermFactorScores.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermFinancialQuality.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermValuationExpectation.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermPositionDiscipline.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermLogicAudit.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermInvestmentAssessment.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermAssessmentInput.java`
- Create: `apps/api/src/main/java/com/aistock/research/longterm/LongTermInvestmentAssessmentService.java`
- Test: `apps/api/src/test/java/com/aistock/research/longterm/LongTermInvestmentAssessmentServiceTest.java`

**Interfaces:**
- Consumes: `LongTermAssessmentInput` with symbol, industry, price, `ValuationContext`, annual indicators and industry rank evidence.
- Produces: `LongTermInvestmentAssessment assess(LongTermAssessmentInput input)`.

- [ ] **Step 1: Write failing tests for sector routing and non-binary ROE**

Create fixtures for a STANDARD company with four strong years and one weak year, a CYCLICAL company with volatile EPS, and a FINANCIAL company with PB/BPS/ROE. Assert:

```java
assertThat(standard.modelCode()).isEqualTo("STANDARD");
assertThat(standard.financialQuality().sampleYears()).isEqualTo(5);
assertThat(standard.status()).isNotEqualTo("BLOCKED");
assertThat(cyclical.valuation().normalizedEarningsUsed()).isTrue();
assertThat(financial.valuation().metricCode()).isEqualTo("IMPLIED_ROE");
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=LongTermInvestmentAssessmentServiceTest test
```

Expected: compilation failure because the long-term domain does not exist.

- [ ] **Step 3: Implement immutable assessment records**

The records expose model/status/version, five factor scores, financial sequence diagnostics, valuation range, position rules, audit rules, evidence, risks and data gaps. Nullable values represent unavailable evidence; no missing numeric value is replaced with zero.

- [ ] **Step 4: Implement the deterministic calculator**

Implement:

```java
public LongTermInvestmentAssessment assess(LongTermAssessmentInput input)
```

Use five year-end indicators, medians instead of single-year maxima, owner-earnings proxy `min(positive EPS, positive OCF/share)`, ten-year discounted cash-flow solving between -10% and 30%, and the financial-industry PB-ROE equation. Clamp all factor scores to `[0,100]`.

- [ ] **Step 5: Add failing discipline tests**

Assert a 15% decline appears only in `reviewTriggers`; `addConditions` must still require intact thesis, wider margin of safety, no cash-flow/balance-sheet deterioration, and no governance red flag.

- [ ] **Step 6: Run focused tests**

Run:

```bash
mvn -pl apps/api -Dtest=LongTermInvestmentAssessmentServiceTest test
```

Expected: all long-term assessment tests pass.

### Task 2: Full-Market Ranking Integration

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `LongTermInvestmentAssessmentService`.
- Produces: VALUE candidates carrying `longTermAssessment`; VALUE final rank uses its five-factor `overallScore`.

- [ ] **Step 1: Add failing market-scan tests**

Extend the existing 航民股份 fixture to assert:

```java
assertThat(hangmin.longTermAssessment().modelCode()).isEqualTo("STANDARD");
assertThat(hangmin.longTermAssessment().financialQuality().sampleYears()).isEqualTo(5);
assertThat(hangmin.longTermAssessment().valuation().metricCode()).isEqualTo("IMPLIED_GROWTH");
```

Add a bank fixture assertion for `FINANCIAL` and `IMPLIED_ROE`.

- [ ] **Step 2: Run focused market tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=MarketScanServiceTest test
```

Expected: compilation failure because candidates do not expose the assessment.

- [ ] **Step 3: Integrate the calculator**

Create one assessment per VALUE candidate from the annual evidence already loaded for the full-market pass. Use assessment factor scores to populate financial, valuation and risk-facing score fields, and use `overallScore` for VALUE ranking. Non-VALUE modes retain current scoring behavior.

- [ ] **Step 4: Merge evidence without erasing existing trace**

Add a `LONG_TERM_DISCIPLINE` trace step containing model, implied expectation, safety-margin range, position discipline and evidence gaps. Preserve quote, quality, valuation and risk trace steps.

- [ ] **Step 5: Run focused tests**

Run:

```bash
mvn -pl apps/api -Dtest=MarketScanServiceTest,LongTermInvestmentAssessmentServiceTest test
```

Expected: all selected tests pass.

### Task 3: Market API Contract And Advice Gate

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Consumes: candidate assessment from Task 2.
- Produces: serialized `longTermAssessment` and evidence-aware `todayAdvice`.

- [ ] **Step 1: Write failing advice tests**

Assert incomplete strict-FCF or balance-sheet evidence keeps advice at WAIT, while the explanation names the model and does not say “automatic add after a 15% fall”.

- [ ] **Step 2: Pass the assessment through the API**

Append `LongTermInvestmentAssessment longTermAssessment` to `MarketScanCandidate` and map it from `UniversalScreenCandidate`.

- [ ] **Step 3: Replace stale proxy wording**

Remove the unconditional `FINANCIAL_HISTORY_GAP`. Gate buy-like advice using assessment status and data gaps. Include the model’s add conditions and invalidation rules in risk controls.

- [ ] **Step 4: Run controller/service tests**

Run:

```bash
mvn -pl apps/api -Dtest=MarketScanServiceTest test
```

Expected: all tests pass and JSON serialization remains automatic through records.

### Task 4: Long-Term Detail Experience

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Create: `apps/web-react/src/pages/MarketScanPage.test.tsx`

**Interfaces:**
- Consumes: API `longTermAssessment`.
- Produces: a readable industry-model, expectation, valuation range, position discipline and thesis-audit panel.

- [ ] **Step 1: Add TypeScript contracts**

Define `LongTermInvestmentAssessment` and nested records with nullable valuation fields. Add `longTermAssessment` to `MarketScanCandidate`.

- [ ] **Step 2: Write a failing render test**

Mock `fetchMarketScanReport`, open a candidate detail overlay, and assert the document contains:

```text
行业估值模板
市场隐含增长
经营者收益代理
下跌15%只触发复核
季度轻审计
```

- [ ] **Step 3: Implement the detail panels**

Render compact metric grids for the five factor scores and valuation range, followed by flat list blocks for add conditions, invalidation rules, review triggers and data gaps. Keep the existing detail overlay and recommendation evidence panels.

- [ ] **Step 4: Run frontend tests and build**

Run:

```bash
npm --prefix apps/web-react test
npm --prefix apps/web-react run build
```

Expected: Vitest passes and TypeScript/Vite build succeeds.

### Task 5: Full Verification And Runtime Check

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: documented strategy version and verified local UI/API behavior.

- [ ] **Step 1: Document the long-term model**

Add the three sector templates, five factor weights, proxy-DCF limitation, no-mechanical-add rule and quarterly/annual audit cadence to `docs/architecture.md`.

- [ ] **Step 2: Run backend regression**

Run:

```bash
mvn -pl apps/api test
```

Expected: all backend tests pass.

- [ ] **Step 3: Rebuild and restart Docker services**

Run:

```bash
docker compose up -d --build api web
```

Expected: `ai-stock-api` and `ai-stock-web` are healthy/running.

- [ ] **Step 4: Verify API and browser**

Verify `/api/market-scan/report?mode=VALUE&limit=3&scanLimit=6000` returns three candidates with non-null `longTermAssessment`. Open `http://127.0.0.1:5176/#/market`, select a candidate and confirm the new detail blocks are visible without overlap at desktop and mobile widths.

- [ ] **Step 5: Review the final diff**

Confirm no whitelist, automatic trade path, PE/PB hard exclusion, unrelated deletion, secret or generated build artifact entered the change set.
