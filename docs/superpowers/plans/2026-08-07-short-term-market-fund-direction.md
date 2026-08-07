# Short-Term Market Fund Direction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change short-term K-line review defaults to 120 and add a report-level “今日资金去向” industry fund-flow view.

**Architecture:** Keep individual candidate ranking unchanged. Add a bounded EastMoney industry fund-flow integration, convert it into a `ShortTermMarketFundDirection` report DTO, and render it as market context beside existing sentiment. Old snapshots default to an unavailable fund-direction object.

**Tech Stack:** Java 17, Spring Boot, Maven, JUnit 5, AssertJ, React, TypeScript, Vitest, Vite, Tailwind-style utility classes.

## Global Constraints

- Do not expose or rewrite DeepSeek API keys or other Nacos secrets.
- Do not add per-stock full-market fund-flow request amplification.
- Keep `MAX_KLINE_LIMIT` at 160.
- Market fund direction is report-level context only and must not directly change candidate ranking.
- Preserve existing dirty worktree changes that are unrelated or pre-existing.
- Use TDD: write failing tests before production code.

---

### Task 1: Backend Defaults

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettingsTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettings.java`

**Interfaces:**
- Consumes: existing `ShortTermService.report(ShortTermScanRequest.empty())`
- Produces: default `ShortTermRuleSet.klineLimit()` and schedule `ShortTermScanRequest.klineLimit()` equal to 120

- [ ] **Step 1: Write failing backend default tests**

```java
assertThat(report.ruleSet().klineLimit()).isEqualTo(120);
assertThat(eastMoneyClient.requestedKlineSymbols).hasSize(120);
assertThat(settings.scanRequest().klineLimit()).isEqualTo(120);
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermAutomationSettingsTest test`

Expected: FAIL because current defaults are 60.

- [ ] **Step 3: Change defaults to 120**

```java
private static final int DEFAULT_KLINE_LIMIT = 120;
integer("research.short-term.schedule.kline-limit", 120, 20, 500)
```

- [ ] **Step 4: Run targeted tests and verify pass**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermAutomationSettingsTest test`

Expected: PASS.

### Task 2: Industry Fund-Flow Integration

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyIndustryFundFlowSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Modify: `apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java`

**Interfaces:**
- Produces: `public List<EastMoneyIndustryFundFlowSnapshot> fetchIndustryFundFlows()`
- Produces: package-visible parser `List<EastMoneyIndustryFundFlowSnapshot> readIndustryFundFlows(JsonNode diff, Instant fetchedAt, String sourceUrl)`

- [ ] **Step 1: Write failing parser tests**

```java
List<EastMoneyIndustryFundFlowSnapshot> rows =
        client.readIndustryFundFlows(diff, fetchedAt, "https://push2.eastmoney.com/api/qt/clist/get");

assertThat(rows).extracting(EastMoneyIndustryFundFlowSnapshot::name)
        .containsExactly("半导体", "电力");
assertThat(rows.get(0).mainNetInflow()).isEqualByComparingTo("900000000");
```

- [ ] **Step 2: Run parser tests and verify fail**

Run: `mvn -pl apps/api -Dtest=EastMoneyClientTest test`

Expected: FAIL because the type and parser do not exist.

- [ ] **Step 3: Add the integration type, URL builder, parser, and fetch method**

Use EastMoney `clist/get` with industry board filter and board fund-flow fields. Parse code, name, main/super-large/large flow, ratios, advancing, declining, constituent count, provider timestamp, source, and source URL.

- [ ] **Step 4: Run parser tests and verify pass**

Run: `mvn -pl apps/api -Dtest=EastMoneyClientTest test`

Expected: PASS.

### Task 3: Report-Level Market Fund Direction

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermIndustryFundDirection.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermMarketFundDirection.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: `EastMoneyClient.fetchIndustryFundFlows()`
- Produces: `ShortTermReport.marketFundDirection()`

- [ ] **Step 1: Write failing service tests**

```java
assertThat(report.marketFundDirection().topInflows())
        .extracting(ShortTermIndustryFundDirection::name)
        .containsExactly("半导体");
assertThat(report.marketFundDirection().topOutflows())
        .extracting(ShortTermIndustryFundDirection::name)
        .containsExactly("银行");
```

Add a second test where the client throws and assert report generation still succeeds with a data gap.

- [ ] **Step 2: Run service tests and verify fail**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest test`

Expected: FAIL because `marketFundDirection` does not exist.

- [ ] **Step 3: Add DTOs, compatibility defaults, service builder, and error fallback**

Compute top 5 inflows, top 3 outflows, coverage ratio, total absolute-flow concentration, and explicit data gaps. Call this for normal and extreme-risk-off reports.

- [ ] **Step 4: Run service tests and verify pass**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest test`

Expected: PASS.

### Task 4: Frontend Types, Defaults, And UI

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`

**Interfaces:**
- Consumes: `ShortTermReport.marketFundDirection?: ShortTermMarketFundDirection | null`
- Produces: manual scan request with `klineLimit: 120`
- Produces: rendered `今日资金去向` card

- [ ] **Step 1: Write failing frontend tests**

```typescript
expect(startShortTermScanJob).toHaveBeenCalledWith(expect.objectContaining({
  klineLimit: 120
}))
expect(document.body.textContent).toContain('今日资金去向')
expect(document.body.textContent).toContain('半导体')
```

Add old-snapshot coverage by rendering a report without `marketFundDirection` and asserting `行业资金流暂不可用`.

- [ ] **Step 2: Run frontend tests and verify fail**

Run: `cd apps/web-react && npm test -- --run ShortTermPage.test.tsx`

Expected: FAIL because the default and UI still use the old behavior.

- [ ] **Step 3: Add types, update default draft to 120, and render the fund-direction card**

Use existing `Card`, `Metric`, `Tag`, `formatAmount`, `formatPercent`, and compact utility classes.

- [ ] **Step 4: Run frontend tests and verify pass**

Run: `cd apps/web-react && npm test -- --run ShortTermPage.test.tsx`

Expected: PASS.

### Task 5: Configuration And Full Verification

**Files:**
- Modify: `infra/nacos/ai-stock-api-local.yml`
- Modify only if needed by tests: `docs/nacos-config.md` and sample Nacos files

**Interfaces:**
- Produces: local Nacos short-term schedule `kline-limit: 120`

- [ ] **Step 1: Update local Nacos short-term schedule only**

```yaml
research:
  short-term:
    schedule:
      kline-limit: 120
```

- [ ] **Step 2: Run backend full tests**

Run: `mvn -pl apps/api test`

Expected: PASS.

- [ ] **Step 3: Run frontend full tests and build**

Run: `cd apps/web-react && npm test -- --run`

Expected: PASS.

Run: `cd apps/web-react && npm run build`

Expected: PASS.

- [ ] **Step 4: Run diff check**

Run: `git diff --check`

Expected: no output and exit 0.

- [ ] **Step 5: Runtime verification**

Rebuild/restart the local API and React containers, run a manual short-term scan, and verify the resulting report has `K线复核 */120` and either industry fund-flow rows or explicit data gaps.

## Self-Review

- Spec coverage: defaults, backend model, EastMoney integration, service fallback, frontend UI, config, tests, and runtime verification are covered.
- Placeholder scan: no `TBD`, `TODO`, or undefined future work remains.
- Type consistency: `ShortTermMarketFundDirection`, `ShortTermIndustryFundDirection`, and `EastMoneyIndustryFundFlowSnapshot` names are consistent across tasks.
