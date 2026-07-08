# Universal A-Share Screener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a unified full-A-share candidate generator that filters ST, loss-making, illiquid, and long-sideways stocks before downstream recommendation modules score them.

**Architecture:** Add a focused `com.aistock.research.universe` domain service that returns a reusable screen report with stage statistics, exclusions, candidates, scores, and trace. Refactor `MarketScanService` to consume this screener first, then adapt the React market page to show the funnel and exclusion sample. Keep downstream module migration incremental so the first deliverable is testable and usable.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, AssertJ, React 18, TypeScript, Vite, existing EastMoney/Tencent data clients.

## Global Constraints

- Do not create a git worktree for this workspace because current app source files are untracked; a new worktree would not contain the app.
- Do not stage or commit broad untracked app files; only commit documentation files unless the repository tracking state is normalized later.
- Use `apply_patch` for manual source edits.
- Use test-first implementation for production behavior.
- Do not use cached or demo data for buy decisions when realtime data fails.
- ST, loss-making, insufficient liquidity, and long-sideways stocks must not enter buy candidates.
- All buy-like actions remain batch/partial position only; never output full-position buy.

---

### Task 1: Add Universal Screener Domain And Tests

**Files:**
- Create: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenRequest.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenRuleSet.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenReport.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenCandidate.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenScore.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenStageStats.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenExclusion.java`
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenTraceStep.java`

**Interfaces:**
- Consumes: `EastMoneyClient.fetchAshareQuotes(int)`, `EastMoneyClient.fetchTencentQuotes(List<String>, int)`, `EastMoneyClient.fetchDailyKLines(String, LocalDate, LocalDate)`, `RecommendationQuality`.
- Produces: `UniversalAshareScreener.screen(UniversalScreenRequest request): UniversalScreenReport`.

- [ ] **Step 1: Write the failing test**

Create `UniversalAshareScreenerTest` with tests:

```java
@Test
void shouldFilterFullMarketThroughHardGatesAndKeepNonSeedWinner() {
    client.baseQuotes = List.of(
            quote("600036", "招商银行", "银行", "36.80", "-1.00", "6.00", "0.85", "900000000"),
            quote("000777", "朋友推荐", "机械设备", "18.00", "-0.20", "16.00", "1.80", "260000000"),
            quote("000002", "*ST样本", "房地产", "1.20", "-1.00", "8.00", "0.70", "120000000"),
            quote("300001", "亏损样本", "软件", "12.00", "0.10", "-4.00", "3.00", "180000000"),
            quote("002001", "低流动样本", "消费", "8.00", "0.20", "12.00", "1.00", "30000000"),
            quote("600777", "横盘样本", "公用事业", "9.00", "0.00", "10.00", "0.90", "160000000")
    );
    client.tencentQuotes = client.baseQuotes;
    client.sidewaysSymbols = Set.of("600777");

    UniversalScreenReport report = screener.screen(new UniversalScreenRequest(10, 100, null, null, null, null, true, true, "ALL"));

    assertThat(report.stageStats()).extracting(UniversalScreenStageStats::stage)
            .contains("UNIVERSE", "TRADABLE", "PROFITABLE", "LIQUID", "NON_SIDEWAYS", "FINAL");
    assertThat(report.candidates()).extracting(UniversalScreenCandidate::symbol)
            .contains("000777", "600036")
            .doesNotContain("000002", "300001", "002001", "600777");
    assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::stage)
            .contains("TRADABLE", "PROFITABLE", "LIQUIDITY", "SIDEWAYS");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: compilation failure because `com.aistock.research.universe` types do not exist.

- [ ] **Step 3: Implement minimal domain records and screener**

Implement records with nullable-friendly fields and a service that:

- resolves rules from request defaults;
- fetches base quotes via `fetchAshareQuotes(scanLimit)`;
- merges realtime quotes via `fetchTencentQuotes`;
- records stage stats after each gate;
- excludes ST/退/name-risk, non-positive PE, insufficient amount, and long-sideways;
- scores valuation, liquidity, quality proxy, trend, and risk;
- returns ranked candidates and exclusion sample.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: tests pass with `Failures: 0, Errors: 0`.

### Task 2: Expose Universal Screener API

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenController.java`
- Modify: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`

**Interfaces:**
- Consumes: `UniversalAshareScreener.screen(...)`.
- Produces: `GET /api/universe/screen/report`.

- [ ] **Step 1: Write the failing controller shape test**

Add a lightweight assertion that the request defaults are accepted by `UniversalScreenRequest` and produce the expected rule values through service output.

- [ ] **Step 2: Run targeted tests**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: failure until controller/request default shape is implemented if missing.

- [ ] **Step 3: Implement controller**

Add a `@RestController` with query params:

```text
limit, scanLimit, minAmount, maxPe, maxPb, minFinancialScore, excludeSideways, includeNorthExchange, mode
```

- [ ] **Step 4: Run targeted tests**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: pass.

### Task 3: Refactor Market Scan To Use Universal Screener

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanRuleSet.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`

**Interfaces:**
- Consumes: `UniversalAshareScreener.screen(...)`.
- Produces: existing `/api/market-scan/report` with added `stageStats` and `exclusionsSample`.

- [ ] **Step 1: Write failing market scan test**

Update `MarketScanServiceTest.shouldScanBroadMarketAndMergeRealtimeQuotes` to assert:

```java
assertThat(report.stageStats()).extracting(UniversalScreenStageStats::stage).contains("UNIVERSE", "FINAL");
assertThat(report.exclusionsSample()).anySatisfy(item -> assertThat(item.reason()).contains("ST"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest test`

Expected: compilation failure or assertion failure because market report lacks universal funnel fields.

- [ ] **Step 3: Refactor service**

Inject `UniversalAshareScreener`; convert universal candidates into `MarketScanCandidate`; preserve existing action labels and today advice semantics where practical.

- [ ] **Step 4: Run market tests**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest test`

Expected: pass.

### Task 4: Upgrade React Types And Market Page

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`

**Interfaces:**
- Consumes: `MarketScanReport.stageStats`, `MarketScanReport.exclusionsSample`.
- Produces: funnel stats, exclusion sample, and strategy mode controls in the existing market page.

- [ ] **Step 1: Update TypeScript types first**

Add interfaces:

```ts
export interface UniversalScreenStageStats {
  stage: string
  label: string
  inputCount: number
  passedCount: number
  excludedCount: number
}

export interface UniversalScreenExclusion {
  symbol: string
  name: string
  stage: string
  reason: string
  evidence: string[]
}
```

Add `stageStats` and `exclusionsSample` to `MarketScanReport`.

- [ ] **Step 2: Run frontend build to verify failure or success**

Run: `npm run build` from `apps/web-react`.

Expected before page update: TypeScript may still pass if fields are unused.

- [ ] **Step 3: Update market page**

Add:

- funnel stat strip above candidate list;
- `excludeSideways`, `minFinancialScore`, `mode` controls;
- exclusion sample panel;
- details showing candidate data gaps and trace remain visible.

- [ ] **Step 4: Run frontend build**

Run: `npm run build` from `apps/web-react`.

Expected: pass.

### Task 5: Integrate Mispricing And Daily Signal Lightly

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/mispricing/MispricingService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/dailysignal/DailySignalService.java`
- Modify: related tests if constructor signatures change.

**Interfaces:**
- Consumes: `UniversalAshareScreener.screen(...)`.
- Produces: fewer seed-only blind spots and shared exclusion assumptions.

- [ ] **Step 1: Add targeted test expectations**

Update existing tests so dynamic, non-seed candidates that pass universal screening remain eligible for mispricing or daily signal observation.

- [ ] **Step 2: Run targeted tests**

Run: `mvn -pl apps/api -Dtest=MispricingServiceTest,DailySignalServiceTest test`

Expected: fail until services use universal screener or adapt constructor.

- [ ] **Step 3: Implement light integration**

Use universal screener as the dynamic candidate source; keep hand-built seed list as an optional supplement, not the primary universe.

- [ ] **Step 4: Run targeted tests**

Run: `mvn -pl apps/api -Dtest=MispricingServiceTest,DailySignalServiceTest test`

Expected: pass.

### Task 6: Full Verification

**Files:**
- No new files unless fixes are required.

**Interfaces:**
- Verifies API and frontend build.

- [ ] **Step 1: Run backend tests**

Run: `mvn -pl apps/api test`

Expected: `BUILD SUCCESS`, `Failures: 0, Errors: 0`.

- [ ] **Step 2: Run frontend build**

Run: `npm run build` from `apps/web-react`.

Expected: Vite build succeeds.

- [ ] **Step 3: Optional live API smoke**

Run local API and request:

```bash
curl 'http://127.0.0.1:19080/api/market-scan/report?limit=8&scanLimit=500&minAmount=80000000'
```

Expected: response includes `stageStats`, `candidates`, and `exclusionsSample`.
