# Short-Term Three-Day Volume Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compute today's volume against the average of the immediately preceding three trading days on the backend and display both concrete values plus the ratio on every short-term candidate row.

**Architecture:** Extend `ShortTermTechnicalSnapshot` with three additive nullable fields. `ShortTermTechnicalSignalEvaluator` remains the single calculation owner, while a focused React formatting module owns compact lot-unit presentation and `CandidateRow` only renders the returned values.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, React, TypeScript, Vitest.

## Global Constraints

- The previous-three-day average strictly excludes the latest K-line and uses the immediately preceding three trading days.
- Missing, zero, or negative volume in any of the four required rows makes the comparison unavailable; do not skip backward or invent a value.
- Preserve the existing 5-day and 20-day volume ratios, scoring, filters, ranking, and actions; the new three-day comparison is display-only.
- The frontend must not fetch K-lines or recompute the average.
- Historical payloads without the additive fields must render `成交量待补` without throwing.
- Existing uncommitted user changes in `ShortTermPage.tsx`, `ShortTermPage.test.tsx`, and `types.ts` must be preserved and must not be staged wholesale.

---

### Task 1: Add the Backend Volume Comparison Contract and Calculation

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluator.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluatorTest.java`

**Interfaces:**
- Consumes: sorted `List<EastMoneyKLine>` already used by `ShortTermTechnicalSignalEvaluator.evaluate`.
- Produces: nullable `BigDecimal todayVolume()`, `averageVolume3()`, and `volumeRatio3()` accessors on `ShortTermTechnicalSnapshot`.

- [ ] **Step 1: Write failing backend tests for the exact calculation and fail-closed behavior**

Add these tests and helper to `ShortTermTechnicalSignalEvaluatorTest`:

```java
@Test
void returnsTodayVolumeAndTheImmediatelyPreviousThreeDayAverage() {
    List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600205"));
    int size = rows.size();
    replaceVolume(rows, size - 4, "100000");
    replaceVolume(rows, size - 3, "200000");
    replaceVolume(rows, size - 2, "300000");
    replaceVolume(rows, size - 1, "300000");

    ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
            rows,
            rows.get(rows.size() - 1).close(),
            false,
            ruleSet
    );

    assertThat(result.snapshot().todayVolume()).isEqualByComparingTo("300000.00");
    assertThat(result.snapshot().averageVolume3()).isEqualByComparingTo("200000.00");
    assertThat(result.snapshot().volumeRatio3()).isEqualByComparingTo("1.50");
}

@Test
void marksThreeDayVolumeComparisonUnavailableWhenARequiredVolumeIsMissing() {
    List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600206"));
    int size = rows.size();
    replaceVolume(rows, size - 4, "100000");
    replaceVolume(rows, size - 3, null);
    replaceVolume(rows, size - 2, "300000");
    replaceVolume(rows, size - 1, "300000");

    ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
            rows,
            rows.get(rows.size() - 1).close(),
            false,
            ruleSet
    );

    assertThat(result.snapshot().todayVolume()).isNull();
    assertThat(result.snapshot().averageVolume3()).isNull();
    assertThat(result.snapshot().volumeRatio3()).isNull();
}

@Test
void marksThreeDayVolumeComparisonUnavailableForZeroOrNegativeRequiredVolume() {
    for (String invalidVolume : List.of("0", "-1")) {
        List<EastMoneyKLine> rows = new ArrayList<>(confirmedReplayRows("600207"));
        int size = rows.size();
        replaceVolume(rows, size - 4, "100000");
        replaceVolume(rows, size - 3, invalidVolume);
        replaceVolume(rows, size - 2, "300000");
        replaceVolume(rows, size - 1, "300000");

        ShortTermTechnicalSignalEvaluation result = evaluator.evaluate(
                rows,
                rows.get(rows.size() - 1).close(),
                false,
                ruleSet
        );

        assertThat(result.snapshot().todayVolume()).isNull();
        assertThat(result.snapshot().averageVolume3()).isNull();
        assertThat(result.snapshot().volumeRatio3()).isNull();
    }
}

private void replaceVolume(List<EastMoneyKLine> rows, int index, String volume) {
    EastMoneyKLine existing = rows.get(index);
    rows.set(index, new EastMoneyKLine(
            existing.symbol(),
            existing.tradeDate(),
            existing.open(),
            existing.close(),
            existing.high(),
            existing.low(),
            volume == null ? null : new BigDecimal(volume),
            existing.amount(),
            existing.turnoverRate()
    ));
}
```

- [ ] **Step 2: Run the focused backend test and verify the red state**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermTechnicalSignalEvaluatorTest test
```

Expected: compilation fails because `todayVolume()`, `averageVolume3()`, and `volumeRatio3()` do not exist.

- [ ] **Step 3: Extend the snapshot without breaking existing constructors**

Append these components after the existing `supportReversal` component in `ShortTermTechnicalSnapshot`:

```java
BigDecimal todayVolume,
BigDecimal averageVolume3,
BigDecimal volumeRatio3
```

Update both compatibility constructors so their canonical `this(...)` calls append:

```java
null,
null,
null
```

Update `withMomentumQuality(...)` and `withSupportReversal(...)` so their canonical constructor calls preserve:

```java
todayVolume,
averageVolume3,
volumeRatio3
```

Add this copy method so the evaluator can attach the calculation without duplicating the full constructor at its call site:

```java
public ShortTermTechnicalSnapshot withVolumeComparison(
        BigDecimal today,
        BigDecimal previousThreeDayAverage,
        BigDecimal ratio
) {
    return new ShortTermTechnicalSnapshot(
            tradeDate,
            ma5,
            ma10,
            ma20,
            ma60,
            ma20SlopePercent,
            ma60SlopePercent,
            previousHigh20,
            previousHigh60,
            breakoutFromPreviousHigh20Percent,
            previousRange20Percent,
            high120,
            low120,
            volumeRatio5,
            volumeRatio20,
            rangePosition60,
            rangePosition120,
            distanceToMa20Percent,
            drawdownFrom120HighPercent,
            todayAmplitudePercent,
            consecutiveAboveMa20Days,
            rightSideSignal,
            goldenCross,
            atr14Percent,
            recentSupportPrice,
            momentumQuality,
            supportReversal,
            today,
            previousThreeDayAverage,
            ratio
    );
}
```

- [ ] **Step 4: Implement one strict backend calculation**

In `ShortTermTechnicalSignalEvaluator.evaluate`, calculate and attach the comparison after building the existing snapshot:

```java
VolumeComparison volumeComparison = threeDayVolumeComparison(rows);
ShortTermTechnicalSnapshot snapshot = new ShortTermTechnicalSnapshot(
        last.tradeDate(),
        scale(ma5),
        scale(ma10),
        scale(ma20),
        scale(ma60),
        scale(ma20Slope),
        scale(ma60Slope),
        scale(previousHigh20),
        scale(previousHigh60),
        scale(breakoutFromPreviousHigh20),
        scale(previousRange20),
        scale(high120),
        scale(low120),
        scale(volumeRatio5),
        scale(volumeRatio20),
        scale(range60),
        scale(range120),
        scale(distanceToMa20),
        scale(drawdownFromHigh120),
        scale(amplitude),
        consecutiveAboveMa20,
        rightSideSignal,
        goldenCross,
        scale(atr14Percent),
        scale(recentSupportPrice)
).withVolumeComparison(
        scale(volumeComparison.todayVolume()),
        scale(volumeComparison.averageVolume3()),
        scale(volumeComparison.volumeRatio3())
);
```

Add the strict helper and local result record:

```java
private VolumeComparison threeDayVolumeComparison(List<EastMoneyKLine> rows) {
    if (rows == null || rows.size() < 4) {
        return VolumeComparison.unavailable();
    }
    int size = rows.size();
    BigDecimal today = rows.get(size - 1).volume();
    List<BigDecimal> previousVolumes = rows.subList(size - 4, size - 1).stream()
            .map(EastMoneyKLine::volume)
            .toList();
    if (!positive(today) || previousVolumes.stream().anyMatch(value -> !positive(value))) {
        return VolumeComparison.unavailable();
    }
    BigDecimal averageVolume3 = previousVolumes.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(3), 6, RoundingMode.HALF_UP);
    BigDecimal volumeRatio3 = today.divide(averageVolume3, 6, RoundingMode.HALF_UP);
    return new VolumeComparison(today, averageVolume3, volumeRatio3);
}

private boolean positive(BigDecimal value) {
    return value != null && value.signum() > 0;
}

private record VolumeComparison(
        BigDecimal todayVolume,
        BigDecimal averageVolume3,
        BigDecimal volumeRatio3
) {
    private static VolumeComparison unavailable() {
        return new VolumeComparison(null, null, null);
    }
}
```

- [ ] **Step 5: Run backend tests and verify the green state**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermTechnicalSignalEvaluatorTest,ShortTermServiceTest test
```

Expected: all tests in both classes pass with zero failures and zero errors.

- [ ] **Step 6: Commit only the clean backend files**

Run:

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java
git add apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluator.java
git add apps/api/src/test/java/com/aistock/research/shortterm/ShortTermTechnicalSignalEvaluatorTest.java
git diff --cached --check
git commit -m "feat: expose three-day short-term volume comparison"
```

Expected: the commit contains only these three backend files.

---

### Task 2: Format and Render the Concrete Volume Values

**Files:**
- Create: `apps/web-react/src/lib/shortTermVolume.ts`
- Create: `apps/web-react/src/lib/shortTermVolume.test.ts`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Test: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: optional `todayVolume`, `averageVolume3`, and `volumeRatio3` numbers from `ShortTermTechnicalSnapshot`.
- Produces: `formatThreeDayVolumeComparison(...)` returning either `128.4万手 / 96.2万手 · 1.33×` or `成交量待补`.

- [ ] **Step 1: Write failing unit tests for volume formatting**

Create `shortTermVolume.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatThreeDayVolumeComparison, formatVolumeLots } from './shortTermVolume'

describe('short-term volume formatting', () => {
  it('formats lots into compact concrete values', () => {
    expect(formatVolumeLots(8_600)).toBe('8600手')
    expect(formatVolumeLots(1_284_000)).toBe('128.4万手')
    expect(formatVolumeLots(120_000_000)).toBe('1.2亿手')
  })

  it('formats today, previous-three-day average, and ratio together', () => {
    expect(formatThreeDayVolumeComparison({
      todayVolume: 1_284_000,
      averageVolume3: 962_000,
      volumeRatio3: 1.3347
    })).toBe('128.4万手 / 96.2万手 · 1.33×')
  })

  it('fails closed for historical or invalid values', () => {
    expect(formatThreeDayVolumeComparison({})).toBe('成交量待补')
    expect(formatThreeDayVolumeComparison({
      todayVolume: 0,
      averageVolume3: 962_000,
      volumeRatio3: 0
    })).toBe('成交量待补')
  })
})
```

- [ ] **Step 2: Run the formatter test and verify the red state**

Run:

```bash
cd apps/web-react
npm test -- --run src/lib/shortTermVolume.test.ts
```

Expected: FAIL because `shortTermVolume.ts` does not exist.

- [ ] **Step 3: Implement the focused formatter module**

Create `shortTermVolume.ts`:

```ts
interface ThreeDayVolumeComparison {
  todayVolume?: number | null
  averageVolume3?: number | null
  volumeRatio3?: number | null
}

export function formatVolumeLots(value: number | null | undefined) {
  if (!isPositiveFinite(value)) return null
  if (value >= 100_000_000) return `${compact(value / 100_000_000)}亿手`
  if (value >= 10_000) return `${compact(value / 10_000)}万手`
  return `${compact(value)}手`
}

export function formatThreeDayVolumeComparison(comparison: ThreeDayVolumeComparison) {
  const today = formatVolumeLots(comparison.todayVolume)
  const average = formatVolumeLots(comparison.averageVolume3)
  const ratio = comparison.volumeRatio3
  if (!today || !average || !isPositiveFinite(ratio)) return '成交量待补'
  return `${today} / ${average} · ${Number(ratio).toFixed(2)}×`
}

function compact(value: number) {
  return Number(value.toFixed(1)).toString()
}

function isPositiveFinite(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isFinite(value) && value > 0
}
```

- [ ] **Step 4: Run the formatter test and verify the green state**

Run:

```bash
cd apps/web-react
npm test -- --run src/lib/shortTermVolume.test.ts
```

Expected: 3 tests pass.

- [ ] **Step 5: Write the failing candidate-row rendering test**

Add these fields to the `candidate` technical fixture in `ShortTermPage.test.tsx`:

```ts
todayVolume: 1_284_000,
averageVolume3: 962_000,
volumeRatio3: 1.3347,
```

Add this page test:

```ts
it('shows today volume against the previous three-day average on each candidate row', async () => {
  vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
    ...finalReadySnapshot,
    report: reportWithCandidates(['600795'])
  })

  await renderPage(root)

  expect(document.body.textContent).toContain('今日量 / 前3日均')
  expect(document.body.textContent).toContain('128.4万手 / 96.2万手 · 1.33×')
})

it('shows a fail-closed placeholder for historical candidates without the new fields', async () => {
  const report = reportWithCandidates(['600795'])
  report.candidates[0].technical.todayVolume = null
  report.candidates[0].technical.averageVolume3 = null
  report.candidates[0].technical.volumeRatio3 = null
  vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
    ...finalReadySnapshot,
    report
  })

  await renderPage(root)

  expect(document.body.textContent).toContain('今日量 / 前3日均')
  expect(document.body.textContent).toContain('成交量待补')
})
```

- [ ] **Step 6: Run the page test and verify the red state**

Run:

```bash
cd apps/web-react
npm test -- --run src/pages/ShortTermPage.test.tsx
```

Expected: FAIL because the candidate row does not render the new metric.

- [ ] **Step 7: Extend the TypeScript contract and render the returned values**

Add these optional fields beside `volumeRatio5` and `volumeRatio20` in `ShortTermTechnicalSnapshot` within `types.ts`:

```ts
todayVolume?: number | null
averageVolume3?: number | null
volumeRatio3?: number | null
```

Import the formatter in `ShortTermPage.tsx`:

```ts
import { formatThreeDayVolumeComparison } from '../lib/shortTermVolume'
```

Add this metric in the existing `CandidateRow` metric grid without removing the 20-day ratio or turnover metric:

```tsx
<Metric
  label="今日量 / 前3日均"
  value={formatThreeDayVolumeComparison(candidate.technical)}
  compact
/>
```

- [ ] **Step 8: Run focused frontend tests and build**

Run:

```bash
cd apps/web-react
npm test -- --run src/lib/shortTermVolume.test.ts src/pages/ShortTermPage.test.tsx
npm run build
```

Expected: formatter and page tests pass; the production build exits with code 0. Existing React `act(...)` warnings may remain warnings but must not become failures.

- [ ] **Step 9: Preserve pre-existing frontend edits during commit handling**

Inspect before staging:

```bash
git diff -- apps/web-react/src/types.ts
git diff -- apps/web-react/src/pages/ShortTermPage.tsx
git diff -- apps/web-react/src/pages/ShortTermPage.test.tsx
```

Do not run a whole-file `git add` on those three already-dirty files. Commit the new formatter files only if useful:

```bash
git add apps/web-react/src/lib/shortTermVolume.ts
git add apps/web-react/src/lib/shortTermVolume.test.ts
git diff --cached --check
git commit -m "feat: format short-term volume comparison"
```

Leave the three shared-file edits unstaged and report them explicitly unless their new hunks can be isolated without including the user's prior work.

---

### Task 3: Verify the Complete Feature Boundary

**Files:**
- Verify: all files changed in Tasks 1 and 2.

**Interfaces:**
- Consumes: backend JSON fields and frontend formatting/rendering.
- Produces: fresh evidence that the feature works without changing short-term decision semantics.

- [ ] **Step 1: Run the complete focused regression**

Run from the repository root:

```bash
mvn -pl apps/api -Dtest=ShortTermTechnicalSignalEvaluatorTest,ShortTermCoreSignalScorerTest,ShortTermServiceTest test
```

Run from `apps/web-react`:

```bash
npm test -- --run src/lib/shortTermVolume.test.ts src/pages/ShortTermPage.test.tsx src/components/shortterm/ShortTermCandidateIndicators.test.tsx
npm run build
```

Expected: every command exits with code 0 and reports zero test failures.

- [ ] **Step 2: Audit the diff against the non-goals**

Run:

```bash
git diff --check
git diff -- apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoreSignalScorer.java
git diff -- apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java
git status --short --branch
```

Expected: no whitespace errors; this feature adds no new scorer, filter, ranking, action, or Agent committee dependency. Existing unrelated diffs in the two audited backend files remain attributable to the user's prior work.

- [ ] **Step 3: Report the verified boundary**

Report the concrete rendered form, backend and frontend test counts, build status, files changed by this feature, any existing warnings, and the distinction between local verification and deployed production state.
