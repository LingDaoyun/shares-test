# Long-Term Eight-Candidate Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make long-term value scanning default to eight candidates and add live, auditable industry, official-policy, and cycle context to each candidate detail.

**Architecture:** Keep the full-market scan focused on ranking and load stock-specific context lazily through a new endpoint. Split official-policy matching and cycle evaluation into independent services, then compose them in `LongTermCandidateContextService`; partial upstream failures become explicit data gaps and never block the existing candidate detail.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, AssertJ, React, TypeScript, Vitest, Docker Compose, existing EastMoney and government-policy clients.

## Global Constraints

- Long-term scan defaults to eight research candidates, not eight buy actions.
- Policy evidence accepts only official government sources from the configured policy-source chain.
- Policy matching covers the most recent two years and returns at most five relevant documents.
- Industry business cycle and stock price cycle remain separate conclusions.
- Missing product-price, inventory, capacity, financial, K-line, or policy evidence must lower confidence or return `INSUFFICIENT`.
- No example, cached fallback, or generic news may fill a missing live evidence slot.
- Existing canonical advice and evidence-completeness gates remain authoritative.

---

### Task 1: Default Long-Term Candidate Count

**Files:**
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.test.tsx`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Modify: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`

**Interfaces:**
- Consumes: `UniversalScreenRequest.limit()`.
- Produces: a default limit of eight in both the React request and backend fallback.

- [x] **Step 1: Write failing backend and frontend tests**

```java
@Test
void defaultsValueModeToEightCandidates() {
    UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
            null, 6000, null, null, null, null, null, true, "VALUE"));
    assertThat(report.candidates()).hasSize(8);
}
```

```tsx
it('defaults long-term value scanning to eight candidates', async () => {
  render(<MarketScanPage />)
  await waitFor(() => expect(fetchMarketScanReport).toHaveBeenCalled())
  expect(fetchMarketScanReport).toHaveBeenCalledWith(expect.objectContaining({ limit: 8 }))
  expect(document.body.textContent).toContain('默认输出八只候选')
})
```

- [x] **Step 2: Run tests and verify expected failures**

Run:

```bash
mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test
npm test -- --run MarketScanPage.test.tsx
```

Expected: backend or frontend still observes the old default of three.

- [x] **Step 3: Implement the default**

Set the backend `DEFAULT_LIMIT` used by `UniversalAshareScreener` to `8`. Set `DEFAULT_DRAFT.limit` in `MarketScanPage.tsx` to `8` and replace both “三支/三只” descriptions with “八只”.

- [x] **Step 4: Run focused tests**

Expected: both focused test commands pass.

---

### Task 2: Official Policy Evidence Matcher

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermPolicyDocument.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermPolicyEvidence.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermPolicyEvidenceService.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/context/LongTermPolicyEvidenceServiceTest.java`

**Interfaces:**
- Consumes: `GovPolicyClient.fetchLatestPolicies(int)`, industry, company name.
- Produces: `LongTermPolicyEvidence evaluate(String industry, String companyName)`.

```java
public record LongTermPolicyDocument(
        String title,
        String source,
        String publishedAt,
        String url,
        String impact,
        int relevanceScore,
        List<String> matchedKeywords,
        String rationale
) {}

public record LongTermPolicyEvidence(
        List<LongTermPolicyDocument> documents,
        List<String> dataGaps
) {}
```

- [x] **Step 1: Write failing matcher tests**

Cover:

```java
@Test
void keepsOnlyRecentRelevantOfficialDocumentsAndCapsAtFive() {
    LongTermPolicyEvidence evidence = service.evaluate("电力", "国电电力");
    assertThat(evidence.documents()).hasSizeLessThanOrEqualTo(5);
    assertThat(evidence.documents()).allSatisfy(document -> {
        assertThat(document.url()).contains(".gov.cn");
        assertThat(LocalDate.parse(document.publishedAt().substring(0, 10)))
                .isAfterOrEqualTo(LocalDate.now().minusYears(2));
        assertThat(document.matchedKeywords()).isNotEmpty();
    });
}

@Test
void returnsAnExplicitGapInsteadOfUnrelatedPolicies() {
    LongTermPolicyEvidence evidence = service.evaluate("未知行业", "未知公司");
    assertThat(evidence.documents()).isEmpty();
    assertThat(evidence.dataGaps()).contains("最近两年未匹配到可靠官方政策文件");
}
```

Also test that ordinary commercial domains and stale documents are removed, and that restriction keywords such as “规范、限制、整治、去产能” produce `CONSTRAINT` rather than `SUPPORT`.

- [x] **Step 2: Run the policy tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=LongTermPolicyEvidenceServiceTest test
```

Expected: missing types and service.

- [x] **Step 3: Implement deterministic matching**

Use an immutable industry-keyword map for the major EastMoney industry labels and a generic token fallback. Accept official URLs only when the host equals `gov.cn`, ends with `.gov.cn`, or is an explicitly recognized central-government host. Reject blank dates and dates older than two years.

Score each document from:

```text
40 base
+ 12 per industry keyword hit, capped at 36
+ sourceWeight * 0.20, capped at 20
+ 4 when company-name or direct business keyword matches
```

Keep only scores at least `58`, sort descending by score and publication date, and return at most five.

- [x] **Step 4: Run focused tests**

Expected: `LongTermPolicyEvidenceServiceTest` passes.

---

### Task 3: Industry and Price Cycle Evaluation

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermIndustryContext.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermCycleSnapshot.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermCycleContextService.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/context/LongTermCycleContextServiceTest.java`

**Interfaces:**
- Consumes: symbol, company, industry, `List<EastMoneyAnnualIndicator>`, `List<EastMoneyKLine>`, policy evidence.
- Produces: `LongTermIndustryContext` and `LongTermCycleSnapshot`.

```java
public record LongTermIndustryContext(
        String industry,
        String modelCode,
        String modelLabel,
        String cycleType,
        List<String> evidence,
        List<String> dataGaps
) {}

public record LongTermCycleSnapshot(
        String businessStage,
        String businessStageLabel,
        String priceStage,
        String priceStageLabel,
        int confidence,
        boolean provisional,
        List<String> supportingEvidence,
        List<String> contraryEvidence,
        List<String> dataGaps
) {}
```

- [x] **Step 1: Write failing cycle tests**

Test these behaviors separately:

```java
@Test
void cyclicalIndustryWithoutSupplyEvidenceCannotClaimHighConfidenceRecovery() {
    LongTermCycleSnapshot snapshot = service.evaluate(
            "002714", "牧原股份", "养殖业", improvingFinancials(), recoveringKLines(), List.of());
    assertThat(snapshot.businessStage()).isIn("EARLY_RECOVERY", "INSUFFICIENT");
    assertThat(snapshot.confidence()).isLessThan(70);
    assertThat(snapshot.provisional()).isTrue();
    assertThat(snapshot.dataGaps()).anyMatch(item -> item.contains("产品价格")
            || item.contains("库存") || item.contains("产能"));
}

@Test
void weakCycleConsumerUsesStableTemplate() {
    LongTermCycleSnapshot snapshot = service.evaluate(
            "600887", "伊利股份", "乳品", stableFinancials(), stableKLines(), List.of());
    assertThat(snapshot.businessStage()).isEqualTo("STABLE");
}

@Test
void highRangePositionAndFallingLongAverageIsNotExpansion() {
    LongTermCycleSnapshot snapshot = service.evaluate(
            "600000", "样本", "银行Ⅱ", stableFinancials(), overheatedKLines(), List.of());
    assertThat(snapshot.priceStage()).isIn("OVERHEATED", "PULLBACK");
}
```

- [x] **Step 2: Run tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=LongTermCycleContextServiceTest test
```

- [x] **Step 3: Implement cycle rules**

Classify financial and cyclical industries with the same industry vocabulary already used by `LongTermInvestmentAssessmentService` and `UniversalAshareScreener`.

Business-stage inputs:

- three-to-five-year revenue, profit, ROE, gross-margin and operating-cash-flow direction;
- policy support and constraint counts;
- cycle type;
- explicit supply evidence presence.

Price-stage inputs:

- MA20/60/120 order and slope;
- 120/250-day range position;
- drawdown from 250-day high.

Never return confidence above `69` for a strong-cycle industry without product-price, inventory and capacity evidence.

- [x] **Step 4: Run focused tests**

Expected: cycle tests pass without changing policy or scan behavior.

---

### Task 4: Lazy Candidate Context Endpoint

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermCandidateContext.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermCandidateContextService.java`
- Create: `apps/api/src/main/java/com/aistock/research/market/context/LongTermCandidateContextController.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/context/LongTermCandidateContextServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/context/LongTermCandidateContextControllerTest.java`

**Interfaces:**
- Consumes: `GET /api/market-scan/candidates/{symbol}/context?industry=...`.
- Produces:

```java
public record LongTermCandidateContext(
        String symbol,
        String companyName,
        LongTermIndustryContext industryContext,
        LongTermPolicyEvidence policyEvidence,
        LongTermCycleSnapshot cycleContext,
        List<String> dataGaps,
        Instant generatedAt
) {}
```

- [x] **Step 1: Write failing service and controller tests**

Assert that:

- the service resolves the live quote and server-side industry;
- a conflicting request industry is ignored and recorded;
- policy failure returns an empty policy list plus a gap;
- K-line failure returns `INSUFFICIENT` price cycle without failing the endpoint;
- controller returns `200` and the complete response shape for a valid six-digit A-share symbol;
- invalid symbols return `400`.

- [x] **Step 2: Run tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=LongTermCandidateContextServiceTest,LongTermCandidateContextControllerTest test
```

- [x] **Step 3: Implement orchestration**

Fetch one live quote with `fetchEastMoneyQuotesBySymbols`, fall back to `fetchStockBoardIndustry` for missing industry, fetch up to five annual indicators and one year of daily K-lines, then call the policy and cycle services. Catch each upstream failure independently and append a stable Chinese gap message.

- [x] **Step 4: Run focused tests**

Expected: service and controller tests pass.

---

### Task 5: React Detail Integration

**Files:**
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.test.tsx`
- Create: `apps/web-react/src/components/longterm/LongTermCandidateContextPanel.tsx`
- Test: `apps/web-react/src/components/longterm/LongTermCandidateContextPanel.test.tsx`

**Interfaces:**
- Consumes: `fetchLongTermCandidateContext(symbol, industry)`.
- Produces: a detail panel with industry, policy, business cycle and price cycle.

- [x] **Step 1: Add failing frontend tests**

Test:

```tsx
it('loads stock-specific context only after opening a candidate', async () => {
  render(<MarketScanPage />)
  expect(fetchLongTermCandidateContext).not.toHaveBeenCalled()
  await userEvent.click(await screen.findByRole('button', { name: /测试银行 600000/ }))
  await waitFor(() => expect(fetchLongTermCandidateContext).toHaveBeenCalledWith('600000', '银行Ⅱ'))
})
```

Panel tests must assert:

- explicit industry and model label;
- official policy title, date, source and link;
- separate business-cycle and price-cycle labels;
- provisional and confidence labels;
- empty-policy gap;
- partial API failure does not remove the base detail.

- [x] **Step 2: Run frontend tests and verify failure**

Run:

```bash
npm test -- --run MarketScanPage.test.tsx LongTermCandidateContextPanel.test.tsx
```

- [x] **Step 3: Implement lazy loading**

Add state keyed by selected symbol:

```ts
const [contextState, setContextState] = useState<{
  symbol: string | null
  loading: boolean
  data: LongTermCandidateContext | null
  error: string
}>({ symbol: null, loading: false, data: null, error: '' })
```

Fetch on `selectedSymbol` change, ignore responses whose symbol no longer matches, and render `LongTermCandidateContextPanel` immediately after the basic valuation metrics.

- [x] **Step 4: Run focused tests and production build**

Run:

```bash
npm test -- --run MarketScanPage.test.tsx LongTermCandidateContextPanel.test.tsx
npm run build
```

Expected: tests and TypeScript build pass.

---

### Task 6: Full Verification and Runtime Deployment

**Files:**
- Modify if required: `docs/superpowers/plans/2026-07-30-long-term-eight-candidate-context.md`

**Interfaces:**
- Produces: tested Docker runtime at `http://127.0.0.1:5176/#/market`.

- [x] **Step 1: Run all backend and frontend tests**

```bash
mvn -pl apps/api test
cd apps/web-react && npm test -- --run && npm run build
```

Expected: zero failures.

- [x] **Step 2: Package and deploy**

```bash
mvn -pl apps/api -DskipTests package
docker compose up -d --build api web
docker compose ps
```

Expected: `ai-stock-api` and `ai-stock-web` are healthy.

- [x] **Step 3: Verify live endpoints**

Request an eight-candidate value scan, open at least one candidate context endpoint, and verify:

- candidate count is eight when the live pool has eight eligible stocks;
- policy URLs are official domains;
- no policy item is older than two years;
- business and price cycles are separate;
- missing cycle inputs appear as data gaps.

- [x] **Step 4: Verify in the in-app browser**

Open `http://127.0.0.1:5176/#/market`, confirm eight candidate rows, open a candidate, inspect the three new context sections, close the overlay, and confirm no console errors.

- [x] **Step 5: Review and commit**

Run `git diff --check`, stage only files from this plan, request a read-only code review, and commit the implementation with:

```bash
git commit -m "feat: enrich long-term candidate context"
```
