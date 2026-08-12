# Short-Term Lower-Shadow Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an auditable short-term path for slightly declining stocks that reclaim support with a long lower shadow.

**Architecture:** A dedicated evaluator produces `ShortTermSupportReversalSignal` from quote, OHLC, trend, volume, and turnover data. `ShortTermService` permits only `-2%-0%` quotes into K-line review, hides unconfirmed decliners, calibrates confirmed signal ranking below strong rising golden-cross candidates, and emits a light-trial action. React renders the new signal while treating old reports as compatible.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, React 18, TypeScript 5, Vitest, Vite.

## Global Constraints

- Keep the existing rising golden-cross path unchanged.
- Never infer major-player buying from a lower shadow alone.
- A confirmed support reversal can only produce `LIGHT_TRIAL`, never `ADD`.
- Preserve quote freshness, market-risk, financial, liquidity, industry-leader, and ChiNext gates.
- Do not restore chip-distribution presentation.

---

### Task 1: Calculate The Support-Reversal Signal

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupportReversalSignal.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupportReversalEvaluator.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermSupportReversalEvaluatorTest.java`

**Interfaces:**
- Consumes: `EastMoneyQuote`, sorted `List<EastMoneyKLine>`, evaluation close, completed-bar flag, `ShortTermTechnicalSnapshot`, and `ShortTermMomentumQuality`.
- Produces: `ShortTermSupportReversalSignal evaluate(...)` with `confirmed()` and `watchLayer()` helpers.

- [x] **Step 1: Write failing evaluator tests**

Cover a `-1.00%` candle whose lower shadow is over `50%`, close location is over
`70%`, body is below `35%`, MA20 is reclaimed, MA20 slope is at least `-0.20%`,
volume ratio is `1.00-2.50`, and turnover is `1%-8%`. Assert `CONFIRMED`, the
reported support type/price, and a score no greater than 100. Add ordinary
candle, falling-trend, excessive-decline, and zero-range cases asserting they
are not confirmed.

- [x] **Step 2: Verify RED**

```bash
mvn -pl apps/api -Dtest=ShortTermSupportReversalEvaluatorTest test
```

Expected: compilation failure because the evaluator and signal do not exist.

- [x] **Step 3: Implement the evaluator and snapshot field**

Create the immutable signal with fields for state, label, score, lower shadow,
body, upper shadow, close location, support type/price, qualification booleans,
provisional state, reasons, and data gaps. Implement exact spec thresholds and
select the highest touched-and-reclaimed support from MA5, MA10, MA20, and the
previous 20-day high. Add nullable `supportReversal` to
`ShortTermTechnicalSnapshot`, with `unavailable()` defaults for old constructors.

- [x] **Step 4: Verify GREEN**

```bash
mvn -pl apps/api -Dtest=ShortTermSupportReversalEvaluatorTest test
```

Expected: all evaluator tests pass.

---

### Task 2: Integrate Qualification, Ranking, And Trading Discipline

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermScoreBreakdown.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: `ShortTermTechnicalSnapshot.supportReversal()` from Task 1.
- Produces: `SUPPORT_REVERSAL_LIGHT_TRIAL`, `supportReversalScore`, support evidence, and deterministic technical exclusions.

- [x] **Step 1: Write failing service tests**

Add fixtures proving:

```java
assertThat(find(report, "600041").action())
        .isEqualTo("SUPPORT_REVERSAL_LIGHT_TRIAL");
assertThat(find(report, "600041").todayAdvice().action())
        .isEqualTo("LIGHT_TRIAL");
assertThat(find(report, "600041").score().supportReversalScore())
        .isGreaterThanOrEqualTo(new BigDecimal("70"));
```

Also prove an ordinary `-1%` candle is excluded after K-line fetch, a decline
below `-2%` is excluded before K-line fetch, and existing positive golden-cross
fixtures retain their action.

- [x] **Step 2: Verify RED**

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest test
```

Expected: new support-reversal assertions fail against the current positive-only prefilter.

- [x] **Step 3: Implement service integration**

- allow daily changes from `-2.00%` through `0.00%` into technical review;
- reject lower declines as `DAILY_DECLINE_TOO_LARGE`;
- attach the evaluator output in `technicalContext`;
- reject non-positive candidates without confirmed support as
  `SUPPORT_REVERSAL_NOT_CONFIRMED`;
- allow confirmed support to bypass only the golden-cross technical gate;
- calibrate its ranking score to `min(signal.score, 86)` without replacing the
  existing four-factor raw score;
- add `supportReversalScore` to `ShortTermScoreBreakdown`;
- emit `SUPPORT_REVERSAL_LIGHT_TRIAL`, phase `SUPPORT_REVERSAL`, detailed
  evidence, entry rules, and loss-of-support invalidation;
- permit the tail-adjustment path to retain `LIGHT_TRIAL` without upgrading the
  action to `ADD`.

- [x] **Step 4: Verify GREEN**

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest test
```

Expected: service tests pass with both strategy paths intact.

---

### Task 3: Render Explainable Support Evidence

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Test: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: optional `technical.supportReversal` and optional `score.supportReversalScore`.
- Produces: a row capsule and detail metrics for confirmed support reversals; legacy reports render unchanged.

- [x] **Step 1: Write a failing React test**

Populate a candidate with a confirmed signal and assert the row/detail contains
`长下影承接确认`, `下影线占比`, `收复支撑`, and `承接反转分`; render a legacy
candidate without the field and assert the page still opens without a placeholder.

- [x] **Step 2: Verify RED**

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx -t "lower-shadow support"
```

Expected: FAIL because no support-reversal UI exists.

- [x] **Step 3: Implement optional frontend rendering**

Add the optional TypeScript interface, a restrained success-tone capsule for a
confirmed signal, and a compact detail section with shape, support, volume,
trend, provisional status, and invalidation text. Add the new action tone without
changing existing action labels.

- [x] **Step 4: Verify frontend and full project**

```bash
npm --prefix apps/web-react test
npm --prefix apps/web-react run build
mvn -pl apps/api test
git diff --check
```

Expected: all tests pass, production build succeeds, and the diff check is clean.

- [x] **Step 5: Browser smoke check and commit**

Reload `http://127.0.0.1:5176/#/short-term`, verify the page renders without
console errors, and inspect a confirmed fixture/report when available. Commit:

```bash
git add apps/api apps/web-react docs/superpowers/plans/2026-08-12-short-term-lower-shadow-support.md
git commit -m "feat: add lower-shadow support reversal path"
```
