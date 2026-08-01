# Soft Valuation Context P0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove PE/PB hard exclusions from long-value and short-right-side selection, preserve signed valuation data, expose a common soft valuation context, and reduce valuation weights without weakening liquidity, financial-quality, chase-risk, or evidence gates.

**Architecture:** Add a small strategy-neutral calculator in the existing `valuation` package and attach its immutable result to universal and short-term candidates. The universal scanner remains a quote-level eligibility ranker rather than pretending to be the full Long-Value V2 model; short-term ranking keeps its existing K-line and financial pipeline but changes both weight stages. Existing `maxPe` and `maxPb` API fields remain compatible and become reference bands in UI copy.

**Tech Stack:** Java 17, Spring Boot 3.3, Maven, JUnit 5, AssertJ, React 18, TypeScript, Vite, existing EastMoney/Tencent integrations and Docker Compose.

## Global Constraints

- Implement only P0.1 from `docs/superpowers/specs/2026-07-10-soft-valuation-context-design.md`.
- PE/PB reference bands affect context scores and warning text only; they cannot exclude a valid stock.
- Signed provider PE/PB values must be preserved. Negative and missing PE are different states.
- A negative-PE cyclical stock enters normalized-cycle research and cannot receive a buy-like screening action solely from quote-level data.
- Do not add a symbol whitelist or score bonus for `002714` or any other stock.
- Low PE/PB cannot create a short-term buy signal without right-side structure, volume confirmation, financial quality, and tail evidence.
- ST/delisting, invalid quote, liquidity, chase-risk, financial-quality, and evidence gates stay active.
- `screeningAction` and `todayAdvice` remain separate.
- Missing values lower evidence confidence; they are not converted to zero.
- Keep `maxPe` and `maxPb` request/response fields for API compatibility during P0.1.
- The application source tree was already untracked before this work. Do not stage the existing `apps/` tree; use test checkpoints and commit only this plan document until the repository baseline is normalized.
- Use `apply_patch` for manual source edits and follow red-green-refactor for every behavior change.

---

### Task 1: Add The Shared Valuation Context Contract

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/valuation/ValuationContextState.java`
- Create: `apps/api/src/main/java/com/aistock/research/valuation/ValuationModel.java`
- Create: `apps/api/src/main/java/com/aistock/research/valuation/ValuationContext.java`
- Create: `apps/api/src/main/java/com/aistock/research/valuation/ValuationContextCalculator.java`
- Create: `apps/api/src/test/java/com/aistock/research/valuation/ValuationContextCalculatorTest.java`

**Interfaces:**
- Produces: `ValuationContextCalculator.evaluate(BigDecimal rawPe, BigDecimal rawPb, BigDecimal peReference, BigDecimal pbReference, String industry): ValuationContext`.
- Produces: `ValuationContext.score(): BigDecimal`, `state(): ValuationContextState`, and `applicableModel(): ValuationModel` for Tasks 2-4.
- Does not fetch data and has no Spring dependency.

- [x] **Step 1: Write the failing calculator tests**

```java
package com.aistock.research.valuation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationContextCalculatorTest {

    private final ValuationContextCalculator calculator = new ValuationContextCalculator();

    @Test
    void preservesNegativePeAsDistortedCycleEvidence() {
        ValuationContext context = calculator.evaluate(
                new BigDecimal("-8"),
                new BigDecimal("3.2"),
                new BigDecimal("45"),
                new BigDecimal("6"),
                "生猪养殖"
        );

        assertThat(context.rawPe()).isEqualByComparingTo("-8");
        assertThat(context.state()).isEqualTo(ValuationContextState.DISTORTED);
        assertThat(context.applicableModel()).isEqualTo(ValuationModel.CYCLICAL);
        assertThat(context.warnings()).anySatisfy(item -> assertThat(item).contains("当前亏损"));
    }

    @Test
    void treatsExtremePositiveMultiplesAsWarningInsteadOfExclusion() {
        ValuationContext context = calculator.evaluate(
                new BigDecimal("300"),
                new BigDecimal("45"),
                new BigDecimal("100"),
                new BigDecimal("15"),
                "机器人"
        );

        assertThat(context.state()).isEqualTo(ValuationContextState.STRETCHED);
        assertThat(context.score()).isPositive();
        assertThat(context.warnings()).isNotEmpty();
    }

    @Test
    void distinguishesMissingFromDistorted() {
        ValuationContext context = calculator.evaluate(null, null, new BigDecimal("45"), new BigDecimal("6"), "软件");

        assertThat(context.state()).isEqualTo(ValuationContextState.MISSING);
        assertThat(context.rawPe()).isNull();
        assertThat(context.score()).isEqualByComparingTo("50.00");
    }
}
```

- [x] **Step 2: Run the tests and observe RED**

Run: `mvn -pl apps/api -Dtest=ValuationContextCalculatorTest test`

Expected: test compilation fails because the four valuation-context types do not exist.

- [x] **Step 3: Add the enums and immutable response contract**

```java
package com.aistock.research.valuation;

public enum ValuationContextState {
    CHEAP,
    FAIR,
    STRETCHED,
    DISTORTED,
    MISSING
}
```

```java
package com.aistock.research.valuation;

public enum ValuationModel {
    STANDARD,
    FINANCIAL,
    CYCLICAL,
    EARLY_GROWTH
}
```

```java
package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.util.List;

public record ValuationContext(
        BigDecimal score,
        ValuationContextState state,
        ValuationModel applicableModel,
        BigDecimal rawPe,
        BigDecimal rawPb,
        BigDecimal peReference,
        BigDecimal pbReference,
        BigDecimal industryPercentile,
        BigDecimal historyPercentile,
        boolean normalizedEarningsUsed,
        List<String> warnings,
        List<String> evidence
) {
}
```

- [x] **Step 4: Implement deterministic soft-band scoring**

Create `ValuationContextCalculator` with these exact public semantics:

```java
package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class ValuationContextCalculator {

    private static final BigDecimal DEFAULT_PE_REFERENCE = new BigDecimal("45");
    private static final BigDecimal DEFAULT_PB_REFERENCE = new BigDecimal("6");

    public ValuationContext evaluate(
            BigDecimal rawPe,
            BigDecimal rawPb,
            BigDecimal peReference,
            BigDecimal pbReference,
            String industry
    ) {
        BigDecimal safePeReference = positiveOrDefault(peReference, DEFAULT_PE_REFERENCE);
        BigDecimal safePbReference = positiveOrDefault(pbReference, DEFAULT_PB_REFERENCE);
        ValuationModel model = modelFor(industry);
        List<String> warnings = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        evidence.add("PE 参考带=" + plain(safePeReference));
        evidence.add("PB 参考带=" + plain(safePbReference));
        evidence.add("参考带只影响估值语境，不参与资格淘汰");

        if (rawPe == null && rawPb == null) {
            warnings.add("PE/PB 缺失，估值语境保持中性并等待补证");
            return context(new BigDecimal("50"), ValuationContextState.MISSING, model, rawPe, rawPb,
                    safePeReference, safePbReference, warnings, evidence);
        }

        boolean distorted = nonPositive(rawPe) || nonPositive(rawPb);
        if (nonPositive(rawPe)) {
            warnings.add("PE 非正，表示当前亏损或盈利倍数失真");
        }
        if (nonPositive(rawPb)) {
            warnings.add("PB 非正，需要复核净资产口径");
        }

        BigDecimal peScore = metricScore(rawPe, safePeReference);
        BigDecimal pbScore = metricScore(rawPb, safePbReference);
        BigDecimal score = combine(peScore, pbScore);
        ValuationContextState state = distorted
                ? ValuationContextState.DISTORTED
                : score.compareTo(new BigDecimal("75")) >= 0
                ? ValuationContextState.CHEAP
                : score.compareTo(new BigDecimal("45")) >= 0
                ? ValuationContextState.FAIR
                : ValuationContextState.STRETCHED;
        if (state == ValuationContextState.STRETCHED) {
            warnings.add("PE/PB 高于参考语境，需要更强增长证据和更严格风控");
        }
        return context(score, state, model, rawPe, rawPb, safePeReference, safePbReference, warnings, evidence);
    }

    private ValuationContext context(
            BigDecimal score,
            ValuationContextState state,
            ValuationModel model,
            BigDecimal rawPe,
            BigDecimal rawPb,
            BigDecimal peReference,
            BigDecimal pbReference,
            List<String> warnings,
            List<String> evidence
    ) {
        return new ValuationContext(scale(score), state, model, rawPe, rawPb, peReference, pbReference,
                null, null, false, List.copyOf(warnings), List.copyOf(evidence));
    }

    private BigDecimal metricScore(BigDecimal value, BigDecimal reference) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal ratio = value.divide(reference, 8, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) <= 0) {
            return new BigDecimal("90").subtract(ratio.multiply(new BigDecimal("20")));
        }
        if (ratio.compareTo(new BigDecimal("1.8")) <= 0) {
            return new BigDecimal("70").subtract(
                    ratio.subtract(BigDecimal.ONE).divide(new BigDecimal("0.8"), 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("20"))
            );
        }
        if (ratio.compareTo(new BigDecimal("2.5")) <= 0) {
            return new BigDecimal("50").subtract(
                    ratio.subtract(new BigDecimal("1.8")).divide(new BigDecimal("0.7"), 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("15"))
            );
        }
        return new BigDecimal("25");
    }

    private BigDecimal combine(BigDecimal peScore, BigDecimal pbScore) {
        if (peScore == null && pbScore == null) {
            return new BigDecimal("50");
        }
        if (peScore == null) {
            return pbScore;
        }
        if (pbScore == null) {
            return peScore;
        }
        return peScore.multiply(new BigDecimal("0.55")).add(pbScore.multiply(new BigDecimal("0.45")));
    }

    private ValuationModel modelFor(String industry) {
        if (containsAny(industry, "银行", "保险", "证券", "多元金融")) {
            return ValuationModel.FINANCIAL;
        }
        if (containsAny(industry, "农业", "养殖", "生猪", "煤炭", "有色", "钢铁", "化工", "水泥", "建材", "航运", "电池")) {
            return ValuationModel.CYCLICAL;
        }
        return ValuationModel.STANDARD;
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean nonPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : fallback;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
```

- [x] **Step 5: Run the focused tests and observe GREEN**

Run: `mvn -pl apps/api -Dtest=ValuationContextCalculatorTest test`

Expected: `Tests run: 3, Failures: 0, Errors: 0`.

### Task 2: Integrate Soft Valuation Into Universal And Long-Value Screening

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalScreenMode.java`
- Modify: `apps/api/src/main/java/com/aistock/research/universe/UniversalAshareScreener.java`
- Modify: `apps/api/src/test/java/com/aistock/research/universe/UniversalAshareScreenerTest.java`

**Interfaces:**
- Consumes: `ValuationContextCalculator.evaluate(...)` from Task 1.
- Produces: `UniversalScreenCandidate.valuationContext(): ValuationContext` for the market adapter.
- Preserves: `UniversalScreenRequest.maxPe/maxPb` and `UniversalScreenRuleSet.maxPe/maxPb` as reference bands.

- [x] **Step 1: Replace the old positive-PE eligibility regression with soft-context tests**

Replace `valueModeRequiresPositiveProfitProxyButKeepsSidewaysCompaniesForResearch` and add the reference-band regression:

```java
@Test
void valueModeKeepsNegativePeCycleCompanyForNormalizedResearch() {
    client.baseQuotes = List.of(
            quote("002714", "周期龙头", "生猪养殖", "36.00", "0.00", "-8.00", "3.20", "900000000")
    );
    client.tencentQuotes = client.baseQuotes;

    UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
            10, 50, null, new BigDecimal("45"), new BigDecimal("6"), null, true, true, "VALUE"
    ));

    UniversalScreenCandidate candidate = find(report, "002714");
    assertThat(candidate.peTtm()).isEqualByComparingTo("-8.00");
    assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.DISTORTED);
    assertThat(candidate.valuationContext().applicableModel()).isEqualTo(ValuationModel.CYCLICAL);
    assertThat(candidate.action()).isEqualTo("NORMALIZED_CYCLE_RESEARCH");
    assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("002714");
}

@Test
void valueModeDoesNotUseReferenceBandsAsEligibilityCliffs() {
    client.baseQuotes = List.of(
            quote("600901", "高估值质量研究", "软件", "80.00", "0.20", "180.00", "20.00", "900000000")
    );
    client.tencentQuotes = client.baseQuotes;

    UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
            10, 50, null, new BigDecimal("45"), new BigDecimal("6"), null, false, true, "VALUE"
    ));

    UniversalScreenCandidate candidate = find(report, "600901");
    assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.STRETCHED);
    assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("参考"));
    assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("600901");
}

@Test
void qualityProxyDoesNotDoubleCountPeAndPb() {
    client.baseQuotes = List.of(
            quote("600911", "低倍数样本", "软件", "10.00", "0.10", "8.00", "0.80", "900000000"),
            quote("600912", "高倍数样本", "软件", "10.00", "0.10", "180.00", "20.00", "900000000")
    );
    client.tencentQuotes = client.baseQuotes;

    UniversalScreenReport report = screener.screen(new UniversalScreenRequest(
            10, 50, null, null, null, null, false, true, "VALUE"
    ));

    assertThat(find(report, "600911").score().financialScore())
            .isEqualByComparingTo(find(report, "600912").score().financialScore());
}
```

Add imports for `ValuationContextState` and `ValuationModel`.

- [x] **Step 2: Run the focused universal tests and observe RED**

Run: `mvn -pl apps/api -Dtest=UniversalAshareScreenerTest test`

Expected: the negative-PE stock is excluded by `MODE_ELIGIBILITY`, context accessors do not exist, and the quality scores differ.

- [x] **Step 3: Preserve signed valuation values during quote merge**

In `mergeQuote`, use a varargs first-non-null helper for valuation fields:

```java
firstNonNull(base.peRatio(), realtime.peRatio()),
firstNonNull(base.pbRatio(), realtime.pbRatio()),
firstNonNull(base.peTtm(), base.peRatio(), realtime.peTtm(), realtime.peRatio()),
```

```java
private BigDecimal firstNonNull(BigDecimal... values) {
    for (BigDecimal value : values) {
        if (value != null) {
            return value;
        }
    }
    return null;
}
```

Keep `firstPositive` for price and amount only.

- [x] **Step 4: Add `ValuationContext` to `UniversalScreenCandidate` and reranking**

Insert this field after `amount`:

```java
ValuationContext valuationContext,
```

Import `com.aistock.research.valuation.ValuationContext` and copy the field in `rerank`.

- [x] **Step 5: Remove positive PE from VALUE eligibility**

Change the enum declaration to:

```java
public enum UniversalScreenMode {
    ALL(false, false, false),
    VALUE(true, false, false),
    CYCLE(true, false, true),
    SHORT_TERM(true, true, false);

    private final boolean liquidityRequired;
    private final boolean sidewaysReviewSupported;
    private final boolean cycleIndustryRequired;
```

Delete `positiveProfitProxyRequired()` and remove the `profitProblem` call from `modeEligibilityProblem`. Keep cycle-industry, liquidity and sideways behavior unchanged.

- [x] **Step 6: Calculate valuation once and stop double counting it**

Add a field:

```java
private final ValuationContextCalculator valuationContextCalculator = new ValuationContextCalculator();
```

In `candidate(...)`, use signed raw values and the context:

```java
BigDecimal rawPe = firstNonNull(quote.peTtm(), quote.peRatio());
BigDecimal rawPb = quote.pbRatio();
ValuationContext valuationContext = valuationContextCalculator.evaluate(
        rawPe, rawPb, ruleSet.maxPe(), ruleSet.maxPb(), quote.industry()
);
BigDecimal valuation = valuationContext.score();
BigDecimal liquidity = liquidityScore(quote.amount(), ruleSet.minAmount());
BigDecimal financial = financialScore(quote);
BigDecimal trend = trendScore(quote.changePercent());
BigDecimal risk = riskScore(quote);
BigDecimal finalScore = weighted(financial, "0.30")
        .add(weighted(valuation, "0.10"))
        .add(weighted(liquidity, "0.20"))
        .add(weighted(trend, "0.10"))
        .add(weighted(risk, "0.30"));
```

Change the quote-level quality proxy so it never reads PE/PB:

```java
private BigDecimal financialScore(EastMoneyQuote quote) {
    BigDecimal score = new BigDecimal("60");
    if (isDefensiveIndustry(quote.industry())) score = score.add(new BigDecimal("8"));
    if (isCycleIndustry(quote.industry())) score = score.add(new BigDecimal("3"));
    if (isTechIndustry(quote.industry())) score = score.add(new BigDecimal("4"));
    return clamp(score);
}

private BigDecimal riskScore(EastMoneyQuote quote) {
    BigDecimal score = new BigDecimal("88");
    if (quote.changePercent() != null && quote.changePercent().compareTo(new BigDecimal("5")) > 0) {
        score = score.subtract(new BigDecimal("18"));
    }
    return clamp(score);
}
```

- [x] **Step 7: Make VALUE decisions context-aware without a valuation cliff**

Before score-based VALUE decisions, add:

```java
if (valuationContext.state() == ValuationContextState.DISTORTED) {
    if (valuationContext.applicableModel() == ValuationModel.CYCLICAL) {
        return new ActionDecision("NORMALIZED_CYCLE_RESEARCH", "周期正常化研究", "当前盈利倍数失真，进入完整周期盈利与成本曲线复核。");
    }
    return new ActionDecision("TURNAROUND_RESEARCH", "困境反转研究", "当前盈利倍数失真，需先证明盈利修复和现金流改善。");
}
if (valuationContext.state() == ValuationContextState.MISSING) {
    return new ActionDecision("VALUE_RESEARCH", "价值证据待补", "估值数据缺失不作淘汰，但买入闸门保持关闭。");
}
```

Delete `valuationOk`. Retain the single-day chase rule, then use final-score thresholds alone for `ACCUMULATE`, `WATCH_BUY_ZONE`, and `WAIT_CONFIRM`. Pass `valuationContext` into `decision`, `strengths`, `risks`, and `trace`.

Replace threshold wording with:

```java
strengths.add("PE/PB 仅按参考带形成估值语境，不参与资格淘汰");
risks.addAll(valuationContext.warnings());
```

Update the VALUE quote note to:

```text
长线价值模式使用实时行情复核，PE/PB 仅作软估值语境；负 PE 可进入周期或反转研究，买入前仍需点时财务和风险证据。
```

- [x] **Step 8: Run focused tests and observe GREEN**

Run: `mvn -pl apps/api -Dtest=ValuationContextCalculatorTest,UniversalAshareScreenerTest test`

Expected: all calculator and universal-screen tests pass.

### Task 3: Reweight Short-Term Selection And Remove Extreme-Valuation Exclusion

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermWeightProfile.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`

**Interfaces:**
- Consumes: the Task 1 valuation calculator.
- Produces: `ShortTermCandidate.valuationContext(): ValuationContext`.
- Produces: `ShortTermReport.weightProfile(): ShortTermWeightProfile` for transparent UI display.

- [x] **Step 1: Write the failing extreme-valuation and weight tests**

```java
@Test
void shouldKeepHotRightSideCandidateEvenWhenPeAndPbExceedOldExtremeGate() {
    eastMoneyClient.quotes = List.of(
            quoteWithIndustry("600020", "高估值机器人", "机器人", "10.62", "1.60", "300.00", "45.00", "900000000")
    );
    eastMoneyClient.klines.put("600020", rightEarlyKLines("600020", "10.62", "230000"));
    eastMoneyClient.financials.put("600020", goodFinancial("600020"));

    ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

    ShortTermCandidate candidate = find(report, "600020");
    assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600020");
    assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.STRETCHED);
    assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("参考"));
}

@Test
void shouldExposeApprovedSoftValuationWeights() {
    eastMoneyClient.quotes = List.of(
            quote("600021", "权重样本", "10.62", "1.60", "18.00", "1.60", "900000000")
    );
    eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.62", "230000"));
    eastMoneyClient.financials.put("600021", goodFinancial("600021"));

    ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

    assertThat(report.weightProfile().preliminaryTotal()).isEqualByComparingTo("1.00");
    assertThat(report.weightProfile().finalTotal()).isEqualByComparingTo("1.00");
    assertThat(report.weightProfile().preliminaryValuation()).isEqualByComparingTo("0.10");
    assertThat(report.weightProfile().finalValuation()).isEqualByComparingTo("0.05");
}
```

Add the `ValuationContextState` import.

- [x] **Step 2: Run the tests and observe RED**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest#shouldKeepHotRightSideCandidateEvenWhenPeAndPbExceedOldExtremeGate+shouldExposeApprovedSoftValuationWeights test`

Expected: `600020` is excluded as `VALUATION_EXTREME`, and the new accessors do not exist.

- [x] **Step 3: Add the immutable weight profile**

```java
package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermWeightProfile(
        BigDecimal preliminaryValuation,
        BigDecimal preliminaryLiquidity,
        BigDecimal preliminaryNonChase,
        BigDecimal preliminaryHeat,
        BigDecimal finalTechnical,
        BigDecimal finalVolume,
        BigDecimal finalHeat,
        BigDecimal finalFinancial,
        BigDecimal finalValuation
) {
    public BigDecimal preliminaryTotal() {
        return preliminaryValuation.add(preliminaryLiquidity).add(preliminaryNonChase).add(preliminaryHeat);
    }

    public BigDecimal finalTotal() {
        return finalTechnical.add(finalVolume).add(finalHeat).add(finalFinancial).add(finalValuation);
    }
}
```

In `ShortTermService`, define:

```java
private static final ShortTermWeightProfile WEIGHT_PROFILE = new ShortTermWeightProfile(
        new BigDecimal("0.10"), new BigDecimal("0.30"), new BigDecimal("0.25"), new BigDecimal("0.35"),
        new BigDecimal("0.40"), new BigDecimal("0.20"), new BigDecimal("0.15"), new BigDecimal("0.20"), new BigDecimal("0.05")
);
```

Add `ShortTermWeightProfile weightProfile` after `ruleSet` in `ShortTermReport` and pass `WEIGHT_PROFILE` from `report(...)`.

- [x] **Step 4: Attach valuation context to every short-term candidate**

Add `ValuationContext valuationContext` after `amount` in `ShortTermCandidate` and copy it in `enrichTailSignal` and `rerank`.

Add this field to `ShortTermService`:

```java
private final ValuationContextCalculator valuationContextCalculator = new ValuationContextCalculator();
```

In `score(...)`:

```java
ValuationContext valuationContext = valuationContextCalculator.evaluate(
        pe(quote), quote.pbRatio(), ruleSet.maxPe(), ruleSet.maxPb(), quote.industry()
);
BigDecimal valuationScore = valuationContext.score();
```

Pass `valuationContext` into the candidate constructor, risk text, strengths and evidence.

In `preliminaryScore(...)`, calculate the same context before applying weights:

```java
BigDecimal valuation = valuationContextCalculator.evaluate(
        pe(quote), quote.pbRatio(), ruleSet.maxPe(), ruleSet.maxPb(), quote.industry()
).score();
```

- [x] **Step 5: Remove the valuation exclusion and apply approved weights**

Delete the `peTooHigh && pbTooHigh` block from `preFilterExclusion` and remove the unused `EXTREME_VALUATION_MULTIPLE` constant if no remaining caller uses it.

After both ranking stages use `ValuationContextCalculator`, delete the old private `valuationScore(...)`, `valuationMetricScore(...)`, `STRETCHED_VALUATION_MULTIPLE`, and `EXTREME_VALUATION_MULTIPLE` implementation so two valuation formulas cannot drift apart.

Change `preliminaryScore` to:

```java
return valuation.multiply(WEIGHT_PROFILE.preliminaryValuation())
        .add(liquidity.multiply(WEIGHT_PROFILE.preliminaryLiquidity()))
        .add(nonChase.multiply(WEIGHT_PROFILE.preliminaryNonChase()))
        .add(marketHeat.multiply(WEIGHT_PROFILE.preliminaryHeat()));
```

Change final scoring to:

```java
BigDecimal finalScore = clamp(
        item.technicalScore().multiply(WEIGHT_PROFILE.finalTechnical())
                .add(item.volumeScore().multiply(WEIGHT_PROFILE.finalVolume()))
                .add(marketHeatScore.multiply(WEIGHT_PROFILE.finalHeat()))
                .add(financialScore.multiply(WEIGHT_PROFILE.finalFinancial()))
                .add(valuationScore.multiply(WEIGHT_PROFILE.finalValuation()))
                .subtract(riskPenalty)
);
```

Do not add valuation to `riskPenalty`; the context score already supplies its full 5% contribution.

- [x] **Step 6: Replace hard-threshold explanations**

Update methodology and advice strings so they say:

```text
PE/PB are 10% of pre-ranking and 5% of final ranking. They are reference context, not eligibility gates.
Stretched valuation tightens risk interpretation but does not override right-side structure.
Low PE/PB cannot create a buy signal without structure, volume, financial quality and tail confirmation.
```

Append `valuationContext.warnings()` to candidate risks. Add one valuation evidence row using `valuationContext.evidence()` and the quote URL. Remove text that says the first layer performs an extreme-valuation filter.

- [x] **Step 7: Run all short-term tests and observe GREEN**

Run: `mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermScanJobServiceTest test`

Expected: all short-term service and job tests pass, including the new extreme-valuation regression.

### Task 4: Adapt Market Responses And React Labels

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanCandidate.java`
- Modify: `apps/api/src/main/java/com/aistock/research/market/MarketScanService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/market/MarketScanServiceTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/lib/format.ts`
- Modify: `apps/web-react/src/pages/MarketScanPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`

**Interfaces:**
- Consumes: `UniversalScreenCandidate.valuationContext()` and `ShortTermReport.weightProfile()`.
- Produces: API JSON fields `valuationContext` on market/short candidates and `weightProfile` on short reports.
- Keeps query parameter names `maxPe` and `maxPb` unchanged.

- [x] **Step 1: Write the failing market-adapter regression**

Replace `shouldExposeDataGapsWhenValuationEvidenceIsMissing` with:

```java
@Test
void shouldKeepMissingValuationInResearchWithBuyGateClosed() {
    eastMoneyClient.baseQuotes = List.of(
            quote("300750", "宁德时代", "电池", "260.00", "-0.40", null, null, "800000000")
    );
    eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;

    MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "VALUE");

    MarketScanCandidate candidate = find(report, "300750");
    assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.MISSING);
    assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
    assertThat(candidate.evidenceCompleteness().allowsBuy()).isFalse();
    assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("300750");
}
```

- [x] **Step 2: Run the test and observe RED**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest#shouldKeepMissingValuationInResearchWithBuyGateClosed test`

Expected: the stock remains excluded and `MarketScanCandidate` has no valuation context.

- [x] **Step 3: Map the context through the market adapter**

Add `ValuationContext valuationContext` after `amount` in `MarketScanCandidate`, import the type, and pass `candidate.valuationContext()` in `toMarketCandidate`.

Keep evidence completeness strict:

```java
boolean hasValuationEvidence = candidate.valuationContext().state() != ValuationContextState.MISSING;
```

Use `hasValuationEvidence` instead of checking only whether both raw ratios are non-null. Missing context must close the final buy gate without removing the research candidate.

- [x] **Step 4: Run market tests and observe GREEN**

Run: `mvn -pl apps/api -Dtest=MarketScanServiceTest test`

Expected: all market adapter tests pass.

- [x] **Step 5: Add frontend contracts and labels**

Add to `types.ts`:

```ts
export type ValuationContextState = 'CHEAP' | 'FAIR' | 'STRETCHED' | 'DISTORTED' | 'MISSING'
export type ValuationModel = 'STANDARD' | 'FINANCIAL' | 'CYCLICAL' | 'EARLY_GROWTH'

export interface ValuationContext {
  score: number
  state: ValuationContextState
  applicableModel: ValuationModel
  rawPe: number | null
  rawPb: number | null
  peReference: number
  pbReference: number
  industryPercentile: number | null
  historyPercentile: number | null
  normalizedEarningsUsed: boolean
  warnings: string[]
  evidence: string[]
}

export interface ShortTermWeightProfile {
  preliminaryValuation: number
  preliminaryLiquidity: number
  preliminaryNonChase: number
  preliminaryHeat: number
  finalTechnical: number
  finalVolume: number
  finalHeat: number
  finalFinancial: number
  finalValuation: number
}
```

Add `valuationContext` to `MarketScanCandidate` and `ShortTermCandidate`. Add `weightProfile` after `ruleSet` in `ShortTermReport`.

Add to `format.ts`:

```ts
import type { ApiErrorBody, ValuationContextState } from '../types'

export function formatValuationState(state: ValuationContextState) {
  return {
    CHEAP: '相对便宜',
    FAIR: '估值中性',
    STRETCHED: '预期偏高',
    DISTORTED: '盈利口径失真',
    MISSING: '估值待补'
  }[state]
}
```

- [x] **Step 6: Change page semantics without changing API parameter names**

On both pages:

- Rename `PE 上限` to `PE 参考带`.
- Rename `PB 上限` to `PB 参考带`.
- Replace threshold help text with `参考带只影响估值语境分和风险提示，不决定股票是否入选。`.
- Show `formatValuationState(candidate.valuationContext.state)` beside the raw PE/PB values.
- Show `周期盈利失真` when model is `CYCLICAL` and state is `DISTORTED`.
- In short-term details, label the score as `估值语境 5%`, and display the final five weights from `report.weightProfile` in one compact unframed row.
- Do not add a nested card or a second competing recommendation label.

- [x] **Step 7: Build the frontend**

Run from `apps/web-react`: `npm run build`

Expected: TypeScript and Vite finish with exit code 0.

### Task 5: Full Verification And Live Regression

**Files:**
- Verify only; no new production files.

**Interfaces:**
- Consumes all P0.1 changes.
- Produces fresh test, Docker and live-data evidence before completion claims.

- [x] **Step 1: Run the complete backend suite**

Run: `mvn test`

Expected: all tests pass with `Failures: 0, Errors: 0`.

- [x] **Step 2: Package the current API jar**

Run: `mvn -pl apps/api package -DskipTests`

Expected: `BUILD SUCCESS` and a refreshed `apps/api/target/ai-stock-api-0.1.0-SNAPSHOT.jar`.

- [x] **Step 3: Rebuild and start Docker services**

Run: `docker compose up -d --build api web`

Expected: both services start.

Run: `docker compose ps`

Expected: `ai-stock-api` and `ai-stock-web` both report `healthy`.

- [x] **Step 4: Run a live VALUE scan**

Run:

```bash
curl --max-time 120 -sS 'http://127.0.0.1:19080/api/market-scan/report?limit=12&scanLimit=300&mode=VALUE&maxPe=45&maxPb=6'
```

Expected:

- candidates above the reference bands remain eligible;
- every candidate contains `valuationContext`;
- missing/distorted context produces research or wait semantics, not silent exclusion;
- `todayAdvice` remains evidence-gated.

- [x] **Step 5: Run a live short-term scan job**

Run:

```bash
curl --max-time 20 -sS -X POST 'http://127.0.0.1:19080/api/short-term/scan-jobs' \
  -H 'Content-Type: application/json' \
  -d '{"limit":8,"scanLimit":300,"klineLimit":12,"maxPe":100,"maxPb":15}'
```

Poll the returned job ID through `GET /api/short-term/scan-jobs/{jobId}`.

Expected:

- the report exposes preliminary valuation weight `0.10` and final valuation weight `0.05`;
- no exclusion has category `VALUATION_EXTREME`;
- every candidate exposes valuation state and warnings;
- liquidity, unstable-industry, chase and sideways rules remain active.

- [x] **Step 6: Verify the React pages in the in-app browser**

Open `http://127.0.0.1:5176/`, then inspect the full-market and short-term pages.

Expected:

- controls say `PE 参考带` and `PB 参考带`;
- no copy claims these values are hard eligibility limits;
- candidate detail shows valuation state and the short-term `5%` contribution;
- no overlapping controls, clipped text or duplicate advice labels;
- browser console contains no application errors.

- [x] **Step 7: Audit for forbidden symbol-specific logic**

Run: `rg -n '002714|牧原' apps/api/src/main apps/web-react/src`

Expected: no new whitelist, bonus or hard-coded recommendation appears. Existing unrelated display data, if any, must not participate in scoring.

- [x] **Step 8: Record the phase boundary**

Report that P0.1 is complete only after Steps 1-7 pass. State explicitly that point-in-time persistence, normalized 5-10 year cycle earnings and rolling out-of-sample weight validation remain P1/P2/P4 work and are not fabricated by this patch.
