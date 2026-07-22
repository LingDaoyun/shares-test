# Short-Term Confirmation Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank eligible `右侧早期确认` candidates ahead of `右侧早期观察` candidates and render confirmation as a calm, high-contrast capsule without changing composite scores.

**Architecture:** Keep safety, market coverage, liquidity, golden-cross, and action gates unchanged. Add a dedicated right-side maturity priority inside `ShortTermService`, then isolate frontend signal presentation in a pure view-model helper so its copy and Tailwind classes are regression tested independently from the page.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, React 18, TypeScript, Tailwind CSS, Vitest.

## Global Constraints

- `finalScore` remains the cross-dimensional research score and must not be rewritten.
- `右侧早期确认` ranks before `右侧早期观察` only after existing eligibility and safety gates.
- Confirmation uses a low-saturation emerald capsule with sufficient text and border contrast.
- Observation remains visually quieter.
- The candidate layout must remain stable at desktop and mobile widths.

---

### Task 1: Right-Side Maturity Ranking

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java:265-270`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java:2051-2065`

**Interfaces:**
- Consumes: `ShortTermCandidate.technical().rightSideSignal()` and the existing eligibility/action priorities.
- Produces: `private int rightSideMaturityPriority(ShortTermCandidate candidate)` returning a stable ordinal used before `finalScore`.

- [ ] **Step 1: Write the failing ranking regression test**

Add a service-level test that creates one eligible `右侧早期确认` candidate with deliberately weaker valuation and one eligible `右侧早期观察` candidate with a higher composite score. Assert that the confirmation candidate ranks first while its score remains lower:

```java
@Test
void shouldRankRightSideConfirmationBeforeHigherScoringObservation() {
    eastMoneyClient.quotes = List.of(
            quote("600601", "确认样本", "10.62", "1.20", "90", "12", "600000000"),
            quote("600602", "观察样本", "10.62", "1.20", "12", "1.2", "600000000")
    );
    eastMoneyClient.klines.put("600601", rightEarlyKLines("600601", "10.62", "180000"));
    eastMoneyClient.klines.put("600602", rightEarlyKLines("600602", "10.62", "105000"));
    eastMoneyClient.quotes.forEach(quote ->
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol())));

    ShortTermReport report = service.report(2, 100, 10, null, null, null, null, null, null, null);

    ShortTermCandidate confirmed = find(report, "600601");
    ShortTermCandidate observed = find(report, "600602");
    assertThat(confirmed.technical().rightSideSignal()).isEqualTo("右侧早期确认");
    assertThat(observed.technical().rightSideSignal()).isEqualTo("右侧早期观察");
    assertThat(confirmed.score().finalScore()).isLessThan(observed.score().finalScore());
    assertThat(report.candidates()).extracting(ShortTermCandidate::symbol)
            .containsExactly("600601", "600602");
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./mvnw -pl apps/api -Dtest=ShortTermServiceTest#shouldRankRightSideConfirmationBeforeHigherScoringObservation test
```

If the repository has no Maven wrapper, run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest#shouldRankRightSideConfirmationBeforeHigherScoringObservation test
```

Expected: FAIL because the higher composite score observation currently ranks first.

- [ ] **Step 3: Add explicit maturity priority**

Replace the phase-only tie with a signal-aware priority while retaining the existing fallback structure:

```java
private int rightSideMaturityPriority(ShortTermCandidate candidate) {
    String signal = candidate.technical() == null ? null : candidate.technical().rightSideSignal();
    if ("右侧早期确认".equals(signal)) return 6;
    if ("右侧早期观察".equals(signal)) return 5;
    if ("BASE_TURNING".equals(candidate.phase())) return 4;
    if ("RIGHT_EXTENDED".equals(candidate.phase())) return 3;
    if (isQualifiedRightSideSignal(signal)) return 2;
    return 1;
}
```

Use it in the comparator after eligible golden-cross priority and before action/composite score:

```java
.sorted(Comparator.comparingInt((ScoredShortTerm item) -> eligibleGoldenCrossPriority(item.candidate())).reversed()
        .thenComparing(Comparator.comparingInt((ScoredShortTerm item) -> rightSideMaturityPriority(item.candidate())).reversed())
        .thenComparing(Comparator.comparingInt((ScoredShortTerm item) -> actionPriority(item.candidate().action())).reversed())
        .thenComparing(item -> item.candidate().score().finalScore(), Comparator.reverseOrder()))
```

- [ ] **Step 4: Run focused and package tests**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermServiceTest test
```

Expected: all `ShortTermServiceTest` tests pass and the two asserted final scores are unchanged.

### Task 2: Confirmation Capsule and Score Label

**Files:**
- Create: `apps/web-react/src/lib/shortTermRightSide.ts`
- Create: `apps/web-react/src/lib/shortTermRightSide.test.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx:1-16`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx:382-446`

**Interfaces:**
- Consumes: the backend `rightSideSignal` string.
- Produces: `rightSideSignalPresentation(signal)` returning `{ label, className, emphasized }`.

- [ ] **Step 1: Write the failing presentation test**

```typescript
import { describe, expect, it } from 'vitest'
import { rightSideSignalPresentation } from './shortTermRightSide'

describe('short-term right-side presentation', () => {
  it('emphasizes confirmation with a calm high-contrast capsule', () => {
    const result = rightSideSignalPresentation('右侧早期确认')
    expect(result.emphasized).toBe(true)
    expect(result.className).toContain('rounded-full')
    expect(result.className).toContain('bg-emerald-50')
    expect(result.className).toContain('border-emerald-300')
    expect(result.className).toContain('text-emerald-800')
  })

  it('keeps observation quieter than confirmation', () => {
    const result = rightSideSignalPresentation('右侧早期观察')
    expect(result.emphasized).toBe(false)
    expect(result.className).toContain('bg-sky-50')
    expect(result.className).not.toContain('bg-emerald-50')
  })
})
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
npm --prefix apps/web-react test -- src/lib/shortTermRightSide.test.ts
```

Expected: FAIL because `shortTermRightSide.ts` does not exist.

- [ ] **Step 3: Implement the pure presentation helper**

```typescript
export interface RightSideSignalPresentation {
  label: string
  className: string
  emphasized: boolean
}

export function rightSideSignalPresentation(signal: string | null | undefined): RightSideSignalPresentation {
  if (signal === '右侧早期确认') {
    return {
      label: signal,
      className: 'rounded-full border-emerald-300 bg-emerald-50 px-2.5 py-1 font-semibold text-emerald-800',
      emphasized: true
    }
  }
  if (signal === '右侧早期观察') {
    return {
      label: signal,
      className: 'rounded-full border-sky-200 bg-sky-50 px-2.5 py-1 text-sky-700',
      emphasized: false
    }
  }
  return {
    label: signal?.trim() || '右侧状态待确认',
    className: 'rounded-full px-2.5 py-1',
    emphasized: false
  }
}
```

- [ ] **Step 4: Apply it to the candidate row**

Resolve the presentation once per row, render it through `Tag`, and add a compact `综合分` label without changing `ScoreBadge` globally:

```tsx
const rightSidePresentation = rightSideSignalPresentation(candidate.technical.rightSideSignal)

<Tag className={rightSidePresentation.className}>{rightSidePresentation.label}</Tag>

<div className="flex items-center gap-1.5 md:flex-col md:items-end md:gap-1">
  <span className="text-[11px] font-medium text-ink-400">综合分</span>
  <ScoreBadge value={candidate.score.finalScore} />
</div>
```

- [ ] **Step 5: Run frontend tests and build**

Run:

```bash
npm --prefix apps/web-react test
npm --prefix apps/web-react run build
```

Expected: all Vitest tests pass and TypeScript/Vite build succeeds.

### Task 3: Integrated Verification

**Files:**
- No production files added.
- Update checklist status in this plan after verification.

**Interfaces:**
- Consumes: backend ranking and frontend presentation from Tasks 1 and 2.
- Produces: a rebuilt local system verified at `http://127.0.0.1:5176/#/short-term`.

- [ ] **Step 1: Run backend regression suite**

```bash
mvn -pl apps/api test
```

Expected: all API tests pass.

- [ ] **Step 2: Rebuild and restart containers**

```bash
docker compose up -d --build api web
```

Expected: API and web containers become healthy without restart loops.

- [ ] **Step 3: Verify runtime endpoints**

```bash
docker compose ps
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:5176/
```

Expected: containers are up, API reports `UP`, and the frontend returns HTML.

- [ ] **Step 4: Browser verification**

Open `http://127.0.0.1:5176/#/short-term` at desktop and mobile widths. Verify confirmed rows precede observations inside the same eligibility tier, confirmation uses the subdued emerald capsule, observation remains quieter, `综合分` is explicit, and no text overlaps or overflows.
