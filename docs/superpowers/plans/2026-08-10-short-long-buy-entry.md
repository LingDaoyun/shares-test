# Short/Long Buy Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Allow users to confirm and record a BUY fill from both short-term and long-term recommendation detail views.

**Architecture:** Long-term market-scan responses will use the existing server-side recommendation attestation service, matching the short-term flow. A shared React `BuyEntryButton` will own modal state, Shanghai datetime conversion, validation, case creation/reuse, fill persistence, store merging, and toast feedback; both recommendation pages will pass only candidate context and their server-issued token.

**Tech Stack:** Spring Boot records/services/tests, React + TypeScript, Zustand, Axios client, Vitest/jsdom, existing Tailwind-style utility classes.

## Global Constraints

- Do not persist a BUY fill before the modal confirmation submit.
- Use the existing `/api/trade-cases/{caseId}/fills` endpoint and `UpsertTradeFillRequest`.
- Direction is fixed to `BUY`; quantity must be a positive integer.
- Use `RecommendationAttestationService`; never fabricate recommendation facts in the browser.
- Preserve the existing `加入复盘` button and existing trade ledger semantics.
- Use `formatShanghaiDateTimeLocal`, `parseShanghaiDateTimeLocal`, and `extractTradeMutationError` for datetime/error behavior.

---

### Task 1: Attest Long-Term Market-Scan Recommendations

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationSource.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanController.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationAttestationServiceTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationControllerAttestationTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Produce `RecommendationSource.LONG_TERM_VALUE` with source module `LONG_TERM` and stable rule version `long-term-value-v1`.
- Produce `MarketScanReport.tradeCaptureTokens(): Map<String, String>` keyed by candidate symbol.
- `MarketScanController.report` returns `attestationService.attest(marketScanService.report(...))`.

- [ ] **Step 1: Add the failing service/report test.** Assert a factual long-term candidate receives an empty-map-free report field only after controller attestation, and the service report itself initializes `tradeCaptureTokens` to an empty map.
- [ ] **Step 2: Add the failing attestation test.** Assert `attest(MarketScanReport)` registers the candidate's symbol, action label, score, price, timestamp, and payload; assert missing price or unusable timestamp omits the token.
- [ ] **Step 3: Run the focused backend tests and confirm RED.**

```bash
mvn -pl apps/api -Dtest=MarketScanServiceTest,RecommendationAttestationServiceTest,RecommendationControllerAttestationTest test
```

Expected: compilation/test failure because the report has no token field, source has no long-term value entry, and attestation has no market-scan overload.

- [ ] **Step 4: Implement the minimal backend changes.** Add the record field, empty map construction, source constant, attestation overload, and controller delegation following the existing short-term/tech/mispricing patterns.
- [ ] **Step 5: Re-run focused backend tests and inspect the diff.**

```bash
mvn -pl apps/api -Dtest=MarketScanServiceTest,RecommendationAttestationServiceTest,RecommendationControllerAttestationTest test
git diff --check
```

- [ ] **Step 6: Commit the backend deliverable.**

```bash
git add apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationSource.java apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationAttestationService.java apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java apps/api/src/main/java/com/aistock/research/market/MarketScanService.java apps/api/src/main/java/com/aistock/research/market/MarketScanController.java apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationAttestationServiceTest.java apps/api/src/test/java/com/aistock/research/tradefeedback/RecommendationControllerAttestationTest.java apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java
git commit -m "feat: attest long-term recommendation picks"
```

### Task 2: Define and Test the Shared Buy Entry Contract

**Files:**
- Create: `apps/web-react/src/components/tradefeedback/BuyEntryButton.tsx`
- Create: `apps/web-react/src/components/tradefeedback/BuyEntryButton.test.tsx`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/store/tradeFeedbackStore.ts`

**Interfaces:**
- `BuyEntryButton` props: `symbol`, `companyName`, `latestPrice`, `recommendedAt`, and `attestationToken`.
- The component calls `ensureCase({ attestationToken })`, then `addTradeFill(caseId, { side: 'BUY', executedAt, price, quantity })`, then `upsertCase(detail)`.
- Missing token/time disables the button; positive numeric price and positive integer quantity are required.

- [ ] **Step 1: Write the failing component tests.** Cover disabled state without a token, default modal values after opening, no API call before confirmation, successful BUY request/store merge, inline validation, duplicate-submit prevention, and preserving the modal on API failure.
- [ ] **Step 2: Run the component test alone and confirm RED.**

```bash
npm --prefix apps/web-react test -- BuyEntryButton.test.tsx
```

Expected: failure because `BuyEntryButton` is not defined.

- [ ] **Step 3: Implement the shared component with the existing dialog patterns.** Use the existing body scroll lock, focus restoration, tab loop, backdrop close, Escape close, field-aware error extraction, and toast helpers. The submit action closes only after the fill endpoint returns a detail.
- [ ] **Step 4: Run the component test and store regression tests.**

```bash
npm --prefix apps/web-react test -- BuyEntryButton.test.tsx tradeFeedbackStore.test.ts
```

- [ ] **Step 5: Commit the shared frontend deliverable.**

```bash
git add apps/web-react/src/components/tradefeedback/BuyEntryButton.tsx apps/web-react/src/components/tradefeedback/BuyEntryButton.test.tsx apps/web-react/src/types.ts apps/web-react/src/api/client.ts apps/web-react/src/store/tradeFeedbackStore.ts
git commit -m "feat: add confirmed buy entry dialog"
```

### Task 3: Integrate Buy Entry Into Both Recommendation Details

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/pages/MarketScanPage.test.tsx`

**Interfaces:**
- Short-term detail passes `report.tradeCaptureTokens[selected.symbol]` and the candidate latest price/timestamp.
- Long-term detail passes `report.tradeCaptureTokens[selected.symbol]` and the candidate latest price/timestamp.
- Existing `WatchButton` and `TradeReviewButton` remain rendered.

- [ ] **Step 1: Add failing page tests.** Open each detail and assert the `买入` button is present; assert the long-term fixture carries a token; assert the missing-token state is disabled and explanatory.
- [ ] **Step 2: Run the focused page tests and confirm RED.**

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx MarketScanPage.test.tsx
```

- [ ] **Step 3: Add the button to both detail headers.** Keep event propagation isolated so clicking the action cannot select/close the underlying candidate.
- [ ] **Step 4: Update all affected fixtures and TypeScript contracts.**
- [ ] **Step 5: Run the focused page/component tests.**

```bash
npm --prefix apps/web-react test -- BuyEntryButton.test.tsx ShortTermPage.test.tsx MarketScanPage.test.tsx tradeFeedbackStore.test.ts
```

- [ ] **Step 6: Commit the page integration.**

```bash
git add apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/MarketScanPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx apps/web-react/src/pages/MarketScanPage.test.tsx
git commit -m "feat: wire buy entry into short and long recommendations"
```

### Task 4: Full Verification

**Files:** No source changes expected.

- [ ] **Step 1: Run the complete backend test suite.**

```bash
mvn -pl apps/api test
```

- [ ] **Step 2: Run the complete frontend test suite.**

```bash
npm --prefix apps/web-react test -- --run
```

- [ ] **Step 3: Build the frontend and backend.**

```bash
npm --prefix apps/web-react run build
mvn -pl apps/api -DskipTests package
```

- [ ] **Step 4: Run repository hygiene checks.**

```bash
git diff --check
git status --short --branch
```

- [ ] **Step 5: Review the final diff against the design acceptance criteria before reporting results.**
