package com.aistock.research.shortterm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermResearchRiskGateTest {
    @Test
    void partialProfitTakingCannotBorrowTheFullSecondTargetReward() {
        var result = ShortTermResearchRiskGate.assess(plan("100", "100", "100", "102", "106", "0.75", "97"));

        // Three quarters at 102 and one quarter at 106 yields a target of 103,
        // so 2.80 net scenario reward / 3.20 risk = 0.875 (full target two would pass).
        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("0.8750");
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void evaluatesTheEntryRangeAtItsHighestFillPrice() {
        var result = ShortTermResearchRiskGate.assess(plan("100", "99", "101", "103", "105", "0.50", "98"));

        // Worst fill 101: cost .202, reward 2.798, risk 3.202 => 0.8738.
        // Using the 100 reference instead would incorrectly pass.
        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("0.8738");
    }

    @Test
    void usesTheExplicitReferenceOnlyWhenTheWholeRangeIsAbsent() {
        var result = ShortTermResearchRiskGate.assess(plan("100", null, null, "103", "105", "0.50", "98"));

        assertThat(result.allowsExecution()).isTrue();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("1.7272");
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("参考入场价"));
    }

    @Test
    void validRangeDoesNotRequireAnUnusedReferencePrice() {
        var result = ShortTermResearchRiskGate.assess(plan(null, "100", "100", "103", "105", "0.50", "98"));

        assertThat(result.allowsExecution()).isTrue();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("1.7272");
    }

    @Test
    void costsCanBlockAnOtherwiseSufficientGrossRatio() {
        var result = ShortTermResearchRiskGate.assess(plan("100", "100", "100", "101.10", "101.50", "0.50", "99"));

        // Gross reward/risk 1.30, but net 1.10 / 1.20 = .9166.
        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("0.9166");
    }

    @Test
    void permitsTheExactNetBoundaryAndReportsTheAssumptions() {
        var result = ShortTermResearchRiskGate.assess(plan("100", "100", "100", "101.14", "102.14", "0.50", "99"));

        assertThat(result.allowsExecution()).isTrue();
        assertThat(result.netRewardRiskRatio()).isEqualByComparingTo("1.2000");
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("0.20%") && reason.contains("假设"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("1.20") && reason.contains("未经"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("情景"));
    }

    @Test
    void cannotRoundABelowThresholdRatioIntoApproval() {
        var result = ShortTermResearchRiskGate.assess(plan("100", "100", "100", "101.13999999", "102.14", "0.50", "99"));

        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isLessThan(new BigDecimal("1.20"));
    }

    @Test
    void blocksANullPlanWithoutInventingARatio() {
        var result = ShortTermResearchRiskGate.assess(null);

        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isNull();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    void keepsAnAlreadyBlockedPlanBlockedWhenTheResearchRatioPasses() {
        var original = plan("BLOCKED", List.of("实时行情未通过"),
                "100", "100", "100", "103", "105", "0.50", "98");
        var assessment = ShortTermResearchRiskGate.assess(original);

        assertThat(assessment.allowsExecution()).isTrue();
        var result = original.withResearchRiskAssessment(assessment);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockedReasons()).containsExactly("实时行情未通过");
        assertThat(result.analysisBasis()).anyMatch(reason -> reason.contains("0.20%"));
    }

    @Test
    void failedAssessmentBlocksAnActionablePlanAndPreservesItsPriceEvidence() {
        var original = plan("100", "100", "100", "102", "106", "0.75", "97");
        var result = original.withResearchRiskAssessment(ShortTermResearchRiskGate.assess(original));

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockedReasons()).anyMatch(reason -> reason.contains("低于门槛"));
        assertThat(result.firstTargetPrice()).isEqualByComparingTo("102");
        assertThat(result.secondTargetPrice()).isEqualByComparingTo("106");
        assertThat(result.firstReductionRatio()).isEqualByComparingTo("0.75");
        assertThat(result.hardStopPrice()).isEqualByComparingTo("97");
    }

    @Test
    void narrowResearchEntryPassesAfterApplyingItsExplicitPatternInvalidation() {
        // MA20 is 10.00 in this regression scenario, but the entry range follows
        // the current 10.20 quote (up to 10.22), not the old broad MA20 zone.
        var original = plan("10.20", "10.20", "10.22", "10.46", "10.66", "0.50", "9.94");
        var withPatternStop = original.withResearchInvalidation(bd("10.05"));
        var assessment = ShortTermResearchRiskGate.assess(withPatternStop);
        var result = withPatternStop.withResearchRiskAssessment(assessment);

        // Net scenario reward .31956 / risk .19044 = 1.6780, at worst fill 10.22.
        assertThat(assessment.allowsExecution()).isTrue();
        assertThat(assessment.netRewardRiskRatio()).isEqualByComparingTo("1.6780");
        assertThat(result.status()).isEqualTo("ACTIONABLE");
        assertThat(result.entryLow()).isEqualByComparingTo("10.20");
        assertThat(result.entryHigh()).isEqualByComparingTo("10.22");
        assertThat(result.firstTargetPrice()).isEqualByComparingTo("10.46");
        assertThat(result.secondTargetPrice()).isEqualByComparingTo("10.66");
        assertThat(result.hardStopPrice()).isEqualByComparingTo("10.05");
        assertThat(result.hardStopPercent()).isEqualByComparingTo("1.47");
        assertThat(result.analysisBasis()).anySatisfy(reason ->
                assertThat(reason).contains("形态失效位", "10.05"));
        assertThat(result.analysisBasis()).anySatisfy(reason ->
                assertThat(reason).contains("区间上沿", "10.22"));
        assertThat(result.analysisBasis()).anyMatch(reason -> reason.contains("0.20%") && reason.contains("假设"));
    }

    @Test
    void rejectsThePriorWideMa20EntryZoneEvenWithTheSamePatternStop() {
        var original = plan("10.20", "9.95", "10.80", "10.46", "10.66", "0.50", "9.94");
        var withPatternStop = original.withResearchInvalidation(bd("10.05"));
        var assessment = ShortTermResearchRiskGate.assess(withPatternStop);
        var result = withPatternStop.withResearchRiskAssessment(assessment);

        assertThat(assessment.allowsExecution()).isFalse();
        assertThat(assessment.netRewardRiskRatio()).isNull();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.blockedReasons()).anyMatch(reason -> reason.contains("目标价"));
        assertThat(result.hardStopPrice()).isEqualByComparingTo("10.05");
        assertThat(result.hardStopPercent()).isEqualByComparingTo("1.47");
    }

    @ParameterizedTest(name = "invalid pattern stop {0} remains blocked")
    @NullSource
    @ValueSource(strings = {"0", "-0.01", "10.20", "10.21"})
    void invalidPatternStopCannotBeRescuedByTheRetainedPriceEvidence(String invalidation) {
        var original = plan("10.20", "10.20", "10.22", "10.46", "10.66", "0.50", "10.05");
        var rejected = original.withResearchInvalidation(bd(invalidation));

        assertThat(rejected.status()).isEqualTo("BLOCKED");
        assertThat(rejected.blockedReasons()).anyMatch(reason -> reason.contains("形态失效价"));
        assertThat(rejected.analysisBasis()).anyMatch(reason -> reason.contains("形态失效价"));
        assertThat(rejected.hardStopPrice()).isEqualByComparingTo("10.05");

        // The retained prices are evidence, and still form a valid mathematical ratio.
        // A passing ratio must never remove the invalidation block applied above.
        var riskAssessment = ShortTermResearchRiskGate.assess(rejected);
        assertThat(riskAssessment.allowsExecution()).isTrue();
        var reassessed = rejected.withResearchRiskAssessment(riskAssessment);
        assertThat(reassessed.status()).isEqualTo("BLOCKED");
        assertThat(reassessed.blockedReasons()).anyMatch(reason -> reason.contains("形态失效价"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPlans")
    void blocksMissingOrInconsistentInputs(InvalidPlan fixture) {
        var result = ShortTermResearchRiskGate.assess(fixture.plan());

        assertThat(result.allowsExecution()).isFalse();
        assertThat(result.netRewardRiskRatio()).isNull();
        assertThat(result.reasons()).isNotEmpty();
    }

    private static Stream<InvalidPlan> invalidPlans() {
        return Stream.of(
                invalid("missing all entry prices", null, null, null, "103", "105", "0.50", "98"),
                invalid("nonpositive reference", "0", null, null, "103", "105", "0.50", "98"),
                invalid("missing lower bound", "100", null, "101", "103", "105", "0.50", "98"),
                invalid("missing upper bound", "100", "99", null, "103", "105", "0.50", "98"),
                invalid("inverted range", "100", "102", "101", "103", "105", "0.50", "98"),
                invalid("nonpositive bound", "100", "0", "101", "103", "105", "0.50", "98"),
                invalid("missing first target", "100", "100", "100", null, "105", "0.50", "98"),
                invalid("missing second target", "100", "100", "100", "103", null, "0.50", "98"),
                invalid("first target no higher than fill", "100", "100", "101", "101", "105", "0.50", "98"),
                invalid("descending targets", "100", "100", "100", "105", "103", "0.50", "98"),
                invalid("missing reduction", "100", "100", "100", "103", "105", null, "98"),
                invalid("negative reduction", "100", "100", "100", "103", "105", "-0.01", "98"),
                invalid("excessive reduction", "100", "100", "100", "103", "105", "1.01", "98"),
                invalid("missing stop", "100", "100", "100", "103", "105", "0.50", null),
                invalid("nonpositive stop", "100", "100", "100", "103", "105", "0.50", "0"),
                invalid("stop at entry", "100", "100", "100", "103", "105", "0.50", "100"),
                invalid("stop inside entry range", "100", "99", "101", "103", "105", "0.50", "100")
        );
    }

    private static InvalidPlan invalid(String name, String reference, String low, String high,
                                       String first, String second, String reduction, String stop) {
        return new InvalidPlan(name, plan(reference, low, high, first, second, reduction, stop));
    }

    private static ShortTermTradePlan plan(String reference, String low, String high, String first,
                                           String second, String reduction, String stop) {
        return plan("ACTIONABLE", List.of(), reference, low, high, first, second, reduction, stop);
    }

    private static ShortTermTradePlan plan(String status, List<String> blockedReasons, String reference,
                                           String low, String high, String first, String second,
                                           String reduction, String stop) {
        return new ShortTermTradePlan("research", status, blockedReasons, null, null,
                bd(reference), bd(low), bd(high), null, null,
                null, bd(first), bd(reduction), null, bd(second), null, bd(stop),
                null, null, null, null, null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private record InvalidPlan(String name, ShortTermTradePlan plan) {
        @Override
        public String toString() {
            return name;
        }
    }
}
