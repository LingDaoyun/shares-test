# Short-Term Chip Concentration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the dominant chip-cost peaks and concentration price zones, make valid local T-1 chip data fully usable without a paid verifier, and activate chip-aware ranking within the existing short-term action layers.

**Architecture:** Extend the deterministic local chip calculator so it preserves a compact auditable distribution and derives up to three non-overlapping concentration zones. Carry those immutable values through the existing chip snapshot, render them in a focused React distribution component, and switch the existing V3 comparator on through Nacos while preserving every eligibility and action gate.

**Tech Stack:** Java 21, Spring Boot, Jackson, JUnit 5, AssertJ, React 19, TypeScript, Vitest, Tailwind CSS, Nacos

## Global Constraints

- The local model remains a turnover-and-price estimate, not actual shareholder ownership or a claim about the main force's true cost.
- T-1 completed-day chip data is valid; Tushare remains optional verification and never blocks a scan.
- Internal calculation uses 150 price buckets; API output contains at most 60 deterministic display buckets whose ratios sum to 100% within rounding tolerance.
- Return at most three non-overlapping concentration zones with peak price, low/high prices, chip ratio, distance to current price, and position relative to current price.
- Chip ranking can only reorder candidates inside the same eligibility and action layer.
- Active V3 weights are technical 45%, chip structure 25%, overhead-pressure relief 20%, and buying pressure 10%.
- Valid local data without Tushare uses coefficient 1.00; conflict, stale data, or insufficient local data contributes zero.
- Old persisted reports without distribution fields remain readable.
- Preserve all unrelated dirty-worktree edits.

---

### Task 1: Preserve Price Distribution and Detect Concentration Zones

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipPricePosition.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipDistributionBucket.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipConcentrationZone.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/LocalChipDistribution.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/LocalChipDistributionCalculator.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/chip/LocalChipDistributionCalculatorTest.java`

**Interfaces:**
- Consumes: normalized internal `BigDecimal[] prices`, `double[] weights`, current price, display bucket limit, peak threshold, edge threshold, and maximum zone count.
- Produces: `List<ChipDistributionBucket> distributionBuckets()`, `List<ChipConcentrationZone> concentrationZones()`, and dominant-zone convenience values on `LocalChipDistribution`.

- [ ] **Step 1: Add failing single-peak and bucket-total tests**

Add tests that build repeated narrow bars near 10.00 and assert:

```java
assertThat(result.distributionBuckets()).hasSizeLessThanOrEqualTo(60);
assertThat(result.distributionBuckets())
        .extracting(ChipDistributionBucket::chipRatioPercent)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .isCloseTo(new BigDecimal("100.00"), within(new BigDecimal("0.10")));
assertThat(result.concentrationZones()).isNotEmpty();
assertThat(result.concentrationZones().get(0).peakPrice())
        .isBetween(new BigDecimal("9.80"), new BigDecimal("10.20"));
assertThat(result.dominantPeakPrice()).isEqualByComparingTo(
        result.concentrationZones().get(0).peakPrice());
```

- [ ] **Step 2: Add failing multi-peak and overhead-zone tests**

Generate one group near 10.00 and a later smaller group near 12.00, then assert:

```java
assertThat(result.concentrationZones()).hasSizeBetween(2, 3);
assertThat(result.concentrationZones())
        .extracting(ChipConcentrationZone::rank)
        .containsExactly(1, 2);
assertThat(result.concentrationZones().get(0).chipRatioPercent())
        .isGreaterThanOrEqualTo(result.concentrationZones().get(1).chipRatioPercent());
assertThat(result.nearestOverheadZone()).isNotNull();
assertThat(result.nearestOverheadZone().positionToCurrentPrice())
        .isEqualTo(ChipPricePosition.ABOVE);
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=LocalChipDistributionCalculatorTest test
```

Expected: compilation fails because the distribution bucket and concentration zone interfaces do not exist.

- [ ] **Step 4: Add immutable domain records**

Implement:

```java
public enum ChipPricePosition { BELOW, AROUND, ABOVE }

public record ChipDistributionBucket(
        BigDecimal lowPrice,
        BigDecimal highPrice,
        BigDecimal price,
        BigDecimal chipRatioPercent,
        BigDecimal normalizedHeight
) {}

public record ChipConcentrationZone(
        int rank,
        BigDecimal lowPrice,
        BigDecimal highPrice,
        BigDecimal peakPrice,
        BigDecimal chipRatioPercent,
        BigDecimal distanceToCurrentPricePercent,
        ChipPricePosition positionToCurrentPrice
) {}
```

Extend `LocalChipDistribution` with lists plus:

```java
BigDecimal dominantPeakPrice,
BigDecimal dominantZoneLow,
BigDecimal dominantZoneHigh,
BigDecimal dominantZoneChipRatioPercent,
ChipPricePosition currentPricePosition,
ChipConcentrationZone nearestOverheadZone
```

Its compact constructor must replace null lists with `List.of()` and copy non-null lists.

- [ ] **Step 5: Extend calculator configuration**

Use a compatibility constructor for existing callers and a full constructor:

```java
public LocalChipDistributionCalculator(
        int lookbackBars,
        int priceBuckets,
        int minValidBars,
        BigDecimal minTurnoverCoverage,
        int displayBuckets,
        int maxConcentrationZones,
        BigDecimal minPeakRelativeHeight,
        BigDecimal zoneEdgeRelativeHeight
)
```

The old four-argument constructor delegates with `60`, `3`, `0.20`, and `0.25`.

- [ ] **Step 6: Implement deterministic display buckets**

Partition the 150 internal buckets into contiguous groups using:

```java
int groupSize = Math.max(1, (int) Math.ceil((double) weights.length / displayBuckets));
```

For each group, return low/high prices, a weight-adjusted representative price, summed ratio percentage, and height normalized against the largest display-group ratio. Preserve ascending price order.

- [ ] **Step 7: Implement concentration-zone detection**

Find local maxima where the bucket is no smaller than its neighbors and its weight is at least `maxWeight * minPeakRelativeHeight`. Sort candidate peaks by raw peak weight, suppress peaks whose expansion overlaps a stronger selected peak, and keep at most the configured maximum.

For each selected peak, expand left/right while bucket weight is at least `peakWeight * zoneEdgeRelativeHeight`. When expanded regions overlap, split them at the minimum-weight valley between peak indexes. Sum each zone's unique bucket weights, rank zones by `chipRatioPercent` descending, and derive `BELOW`, `AROUND`, or `ABOVE` from the current price and zone bounds.

- [ ] **Step 8: Re-run the focused test and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=LocalChipDistributionCalculatorTest test
```

Expected: all calculator tests pass and display bucket totals remain within 0.10 percentage points of 100.

### Task 2: Carry Distribution Through Snapshot and Clarify Verification

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipStructureScorer.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ChipVerificationStatus.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/ShortTermChipSettings.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/chip/ShortTermChipAnalysisService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipTestFixtures.java`
- Test: `apps/api/src/test/java/com/aistock/research/shortterm/chip/ChipStructureScorerTest.java`
- Test: `apps/api/src/test/java/com/aistock/research/configuration/ShortTermChipSettingsTest.java`

**Interfaces:**
- Consumes: new `LocalChipDistribution` fields and external-verification result.
- Produces: API-safe chip snapshot fields and settings getters used by calculator construction.

- [ ] **Step 1: Write failing snapshot and settings tests**

Update the scorer expectation and add settings assertions:

```java
assertThat(snapshot.verificationLabel()).isEqualTo("本地估算 · 未交叉验证");
assertThat(snapshot.verificationCoefficient()).isEqualByComparingTo("1.00");
assertThat(snapshot.distributionBuckets()).isNotEmpty();
assertThat(snapshot.concentrationZones()).isNotEmpty();
assertThat(settings.displayBuckets()).isEqualTo(60);
assertThat(settings.maxConcentrationZones()).isEqualTo(3);
assertThat(settings.minPeakRelativeHeight()).isEqualByComparingTo("0.20");
assertThat(settings.zoneEdgeRelativeHeight()).isEqualByComparingTo("0.25");
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=ChipStructureScorerTest,ShortTermChipSettingsTest test
```

Expected: compilation or assertions fail because new snapshot/settings fields and labels are absent.

- [ ] **Step 3: Extend settings and analysis wiring**

Add validated getters:

```java
public int displayBuckets() { return integer("display-buckets", 60, 20, 150); }
public int maxConcentrationZones() { return integer("max-concentration-zones", 3, 1, 5); }
public BigDecimal minPeakRelativeHeight() {
    return decimal("min-peak-relative-height", "0.20", new BigDecimal("0.01"), BigDecimal.ONE);
}
public BigDecimal zoneEdgeRelativeHeight() {
    return decimal("zone-edge-relative-height", "0.25", new BigDecimal("0.01"), BigDecimal.ONE);
}
```

Change the default activation mode to `ACTIVE` and default single-source coefficient to `1.00`. Pass all new getters when `ShortTermChipAnalysisService` constructs the calculator.

- [ ] **Step 4: Extend the API snapshot**

Append the distribution, zone, dominant-zone, current-position, and overhead-zone fields to `ShortTermChipSnapshot`. The compact constructor must normalize missing collections to empty immutable lists so Jackson can read old reports. Update `withAdditionalGaps` to preserve all new fields. Increment the model version to `short-term-chip-v2-peaks`.

Update `ChipTestFixtures.localDistributionWithDistance` with a deterministic three-bucket distribution and one concentration zone so scorer tests exercise field propagation instead of constructing empty summaries.

- [ ] **Step 5: Copy local values and update user-facing labels**

`ChipStructureScorer.score` copies every new local field unchanged. Update labels:

```java
VERIFIED("本地估算 · 外部数据已核验"),
SINGLE_SOURCE("本地估算 · 未交叉验证"),
CONFLICT("本地与外部数据冲突"),
STALE("外部认证数据过期"),
INSUFFICIENT("本地筹码数据不足")
```

- [ ] **Step 6: Re-run focused tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=LocalChipDistributionCalculatorTest,ChipStructureScorerTest,ShortTermChipAnalysisServiceTest,ShortTermChipSettingsTest test
```

Expected: all focused chip tests pass.

### Task 3: Activate V3 Ranking Without Crossing Action Layers

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorer.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermSupplyDemandScorerTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: `ShortTermChipSnapshot.contributionScore()` in the 0-25 range and `ChipActivationMode`.
- Produces: populated `v3RankingScore` and `rankingScore` equal to V3 only in `ACTIVE` mode.

- [ ] **Step 1: Add failing scorer tests for exact V3 weights**

Use technical 80, chip contribution 20, overhead relief 70, and buying pressure 60:

```java
assertThat(active.v3RankingScore()).isEqualByComparingTo("76.00");
assertThat(active.rankingScore()).isEqualByComparingTo("76.00");
assertThat(shadow.rankingScore()).isEqualByComparingTo(shadow.v2RankingScore());
```

The V3 calculation is:

```text
80 * 0.45 + 20 + 70 * 0.20 + 60 * 0.10 = 76
```

- [ ] **Step 2: Add failing service ordering tests**

Create same-action candidates where one has stronger valid local chip structure but a slightly weaker V2 score. Assert ACTIVE mode orders the stronger-chip candidate first. Add a second test where a `WATCH_RIGHT_SIDE` candidate with excellent chips cannot move ahead of a higher-priority executable action.

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermSupplyDemandScorerTest,ShortTermServiceTest test
```

Expected: V3 remains null or ranking still uses V2, so new assertions fail.

- [ ] **Step 4: Implement V3 score selection**

Calculate:

```java
BigDecimal v3RankingScore = technicalScore.multiply(new BigDecimal("0.45"))
        .add(chipContribution)
        .add(pressureRelief.multiply(new BigDecimal("0.20")))
        .add(flowScore.available()
                ? flowScore.score().multiply(new BigDecimal("0.10"))
                : BigDecimal.ZERO);
BigDecimal rankingScore = activationMode == ChipActivationMode.ACTIVE
        ? v3RankingScore
        : v2RankingScore;
```

Continue clamping and scaling all exposed scores. OFF and SHADOW retain V2 formal ranking.

- [ ] **Step 5: Select the comparator by activation mode**

Keep eligibility and action priority first. In ACTIVE mode compare `rankingScore` descending, then golden-cross and maturity tie-breakers. In OFF/SHADOW retain the existing V2 comparator. Do not compare chip score before action priority.

Update `attachRankingDiagnostics` to calculate both maps and persist the difference:

```java
Map<String, Integer> v2Ranks = rankingPositions(scored, shortTermV2RankingComparator());
Map<String, Integer> v3Ranks = rankingPositions(scored, shortTermV3RankingComparator());
Integer v2Rank = v2Ranks.get(symbol);
Integer v3Rank = v3Ranks.get(symbol);
Integer rankDelta = v2Rank == null || v3Rank == null ? null : v2Rank - v3Rank;
```

- [ ] **Step 6: Update methodology and evidence copy**

Replace “筹码只用于独立诊断/不参与主排序” with copy stating that valid local chip structure contributes 25% within the same action layer. Preserve explicit local-estimate and optional-verification wording.

- [ ] **Step 7: Re-run focused tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermSupplyDemandScorerTest,ShortTermServiceTest test
```

Expected: exact V3 formula and action-layer ordering tests pass.

### Task 4: Render Peak Zones and Distribution in React

**Files:**
- Create: `apps/web-react/src/components/shortterm/ChipDistributionChart.tsx`
- Create: `apps/web-react/src/components/shortterm/ChipDistributionChart.test.tsx`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: optional distribution and zone arrays from `ShortTermChipSnapshot`.
- Produces: accessible horizontal distribution chart and concise dominant-zone summary.
- The chart component receives `chip: ShortTermChipSnapshot` and `currentPrice: number | null`.

- [ ] **Step 1: Add failing component tests**

Use a compact three-bucket fixture and assert visible text plus accessible labels:

```tsx
expect(screen.getByText('主筹码峰 10.20')).toBeInTheDocument()
expect(screen.getByText('9.90 - 10.40')).toBeInTheDocument()
expect(screen.getByText('46.80%')).toBeInTheDocument()
expect(screen.getByLabelText('当前价 10.55')).toBeInTheDocument()
expect(screen.getByLabelText('价格 10.20，筹码占比 4.80%')).toBeInTheDocument()
```

- [ ] **Step 2: Add failing page compatibility tests**

Extend the candidate fixture with full chip distribution and assert the detail displays “主筹码峰”“主要集中区”“最近上方筹码区”“本地估算 · 未交叉验证” and “参与同层排序”. Add an old-report fixture without new fields and assert “历史版本未计算完整筹码峰” while the detail remains open.

- [ ] **Step 3: Run frontend focused tests and verify RED**

Run:

```bash
cd apps/web-react
npm test -- --run src/components/shortterm/ChipDistributionChart.test.tsx src/pages/ShortTermPage.test.tsx
```

Expected: component/type or rendering assertions fail because the full distribution UI does not exist.

- [ ] **Step 4: Extend TypeScript types**

Add:

```typescript
export type ChipPricePosition = 'BELOW' | 'AROUND' | 'ABOVE'

export interface ChipDistributionBucket {
  lowPrice: number
  highPrice: number
  price: number
  chipRatioPercent: number
  normalizedHeight: number
}

export interface ChipConcentrationZone {
  rank: number
  lowPrice: number
  highPrice: number
  peakPrice: number
  chipRatioPercent: number
  distanceToCurrentPricePercent: number
  positionToCurrentPrice: ChipPricePosition
}
```

Append all new chip snapshot fields as optional or nullable for old-report compatibility.

- [ ] **Step 5: Implement the focused chart component**

Render ascending price rows in a fixed-height, scroll-free panel. Use CSS grid with a stable price-label column and bar column. Bar width uses `normalizedHeight%`; dominant-zone bars use muted green, overhead-zone bars muted amber, and remaining bars neutral gray. Include textual peak/zone summaries and `aria-label` on each bar and the current-price marker. Do not add a chart dependency.

- [ ] **Step 6: Integrate the component and correct labels**

Add dominant peak, dominant zone, current-price position, nearest overhead zone, and the chart to `ChipStructurePanel`. Change summary copy from “单源模型” to “本地估算”. Display “参与同层排序” when the report carries a V3 ranking and chip contribution; otherwise preserve the historical compatibility statement.

- [ ] **Step 7: Re-run focused tests and verify GREEN**

Run:

```bash
cd apps/web-react
npm test -- --run src/components/shortterm/ChipDistributionChart.test.tsx src/pages/ShortTermPage.test.tsx
```

Expected: all focused React tests pass without console warnings.

### Task 5: Publish Configuration and Verify the Complete Flow

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `infra/nacos/ai-stock-api.yml`
- Modify: `docs/nacos-config.md`

**Interfaces:**
- Produces: ACTIVE local chip ranking defaults and documented Nacos overrides.

- [ ] **Step 1: Update checked-in defaults and Nacos source**

Set:

```yaml
activation-mode: ACTIVE
single-source-coefficient: 1.00
display-buckets: 60
max-concentration-zones: 3
min-peak-relative-height: 0.20
zone-edge-relative-height: 0.25
```

Keep `tushare.enabled: false` and retain the environment-based token placeholder.

- [ ] **Step 2: Run all backend verification**

Run:

```bash
mvn -pl apps/api test
mvn -pl apps/api -DskipTests package
```

Expected: all tests pass and the API package builds.

- [ ] **Step 3: Run all frontend verification**

Run:

```bash
cd apps/web-react
npm test -- --run
npm run build
```

Expected: all tests pass and the production bundle builds.

- [ ] **Step 4: Publish Nacos configuration**

Run the repository publisher:

```bash
./scripts/publish-nacos-config.sh
```

Then read `ai-stock-api.yml` from group `AI_STOCK` and verify the six chip values match the checked-in source. Never print or copy the configured DeepSeek key into logs or reports.

- [ ] **Step 5: Rebuild and health-check services**

Run:

```bash
docker compose up -d --build api web
docker compose ps
curl -fsS http://127.0.0.1:19080/actuator/health
```

Expected: API and Web are healthy and the actuator response contains `"status":"UP"`.

- [ ] **Step 6: Trigger and poll one real scan**

Run:

```bash
JOB_ID=$(curl -fsS -X POST -H 'Content-Type: application/json' -d '{}' \
  http://127.0.0.1:19080/api/short-term/scan-jobs | jq -r '.jobId')
curl -fsS "http://127.0.0.1:19080/api/short-term/scan-jobs/${JOB_ID}"
```

Poll until terminal status. Expected: `SUCCEEDED`; candidates contain no more than 60 distribution buckets, one to three concentration zones, a dominant peak/zone, `v3RankingScore`, and `rankingScore == v3RankingScore` in ACTIVE mode.

- [ ] **Step 7: Verify ordering, persistence, and page rendering**

For each action layer, verify candidate `rankingScore` is descending. Sum at least three candidates' bucket ratios and confirm each is within 0.10 of 100. Refresh `http://127.0.0.1:5176/short-term` and confirm it reads the persisted report without starting another scan.

- [ ] **Step 8: Final repository checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; unrelated pre-existing dirty files remain untouched and visible.
