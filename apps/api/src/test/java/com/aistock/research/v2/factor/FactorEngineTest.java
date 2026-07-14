package com.aistock.research.v2.factor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FactorEngineTest {

    private final FactorEngine engine = new FactorEngine();

    @Autowired
    private V2FactorSnapshotRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void excludesFactorsCalculatedAfterDecisionEvenWhenAvailabilityWasBackdated() {
        Instant availableAt = Instant.parse("2026-07-14T07:01:00Z");
        repository.saveAndFlush(factorSnapshot(
                "factor-late-calculation", availableAt, Instant.parse("2026-07-14T07:10:00Z")));

        assertThat(repository.findBySymbolAndStrategyCodeAndStrategyVersionAndAvailableAtLessThanEqualAndCalculatedAtLessThanEqualOrderByAvailableAtDescCalculatedAtDesc(
                "002714", "VALUE_REVERSION", "v2.0.0", Instant.parse("2026-07-14T07:05:00Z"),
                Instant.parse("2026-07-14T07:05:00Z"))).isEmpty();
    }

    @Test
    void rejectsFactorSnapshotsAvailableAfterTheyWereCalculated() {
        assertThatThrownBy(() -> factorSnapshot(
                "factor-invalid-chronology", Instant.parse("2026-07-14T07:02:00Z"),
                Instant.parse("2026-07-14T07:01:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableAt <= calculatedAt");
    }

    private V2FactorSnapshotEntity factorSnapshot(String snapshotId, Instant availableAt, Instant calculatedAt) {
        return new V2FactorSnapshotEntity(
                snapshotId, "VALUE_REVERSION", "v2.0.0", "PB_PERCENTILE", "002714",
                new BigDecimal("12.34"), new BigDecimal("68.00"), BigDecimal.ZERO,
                "percentile", "", availableAt, calculatedAt, "quote-snapshot", "{}");
    }

    @Test
    void missingDataReducesConfidenceInsteadOfReturningZeroScore() {
        FactorDefinition definition = new FactorDefinition(
                "TURNOVER_STABILITY",
                "换手稳定性",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "turnover_stability",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorValue value = engine.evaluate(definition, new FactorInput("002714", Map.of()));

        assertThat(value.rawValue()).isNull();
        assertThat(value.normalizedValue()).isNull();
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("-15.00"));
        assertThat(value.missingReason()).isEqualTo("MISSING_REQUIRED_FIELD:turnover_stability");
    }

    @Test
    void validatesExpectedUnitBeforeScoring() {
        FactorDefinition definition = new FactorDefinition(
                "AMOUNT_20D_MEDIAN",
                "20日成交额中位数",
                "SHORT_RIGHT_SIDE",
                "cny",
                FactorDirection.HIGHER_IS_BETTER,
                "amount_20d_median",
                FactorMissingPolicy.BLOCK,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "amount_20d_median", new FactorInput.Measure(new BigDecimal("300000000"), "ratio")));

        assertThatThrownBy(() -> engine.evaluate(definition, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIT_MISMATCH:AMOUNT_20D_MEDIAN expected cny but got ratio");
    }

    @Test
    void normalizesHigherIsBetterValuesBetweenZeroAndOneHundred() {
        FactorDefinition definition = new FactorDefinition(
                "RELATIVE_STRENGTH_20D",
                "20日相对强度",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "rs_20d",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "rs_20d", new FactorInput.Measure(new BigDecimal("0.63"), "ratio")));

        FactorValue value = engine.evaluate(definition, input);

        assertThat(value.rawValue()).isEqualByComparingTo(new BigDecimal("0.63"));
        assertThat(value.normalizedValue()).isEqualByComparingTo(new BigDecimal("63.00"));
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(value.missingReason()).isEmpty();
    }

    @Test
    void rejectsAbsoluteUnitsInsteadOfClampingThemIntoPerfectScores() {
        FactorDefinition definition = new FactorDefinition(
                "AMOUNT_20D_MEDIAN",
                "20日成交额中位数",
                "SHORT_RIGHT_SIDE",
                "cny",
                FactorDirection.HIGHER_IS_BETTER,
                "amount_20d_median",
                FactorMissingPolicy.BLOCK,
                "v2.0.0");

        assertThatThrownBy(() -> engine.evaluate(definition, new FactorInput("002714", Map.of(
                "amount_20d_median", new FactorInput.Measure(new BigDecimal("300000000"), "cny")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_SCORING_UNIT:AMOUNT_20D_MEDIAN cny");
    }

    @Test
    void rejectsOutOfRangeNormalizedValuesInsteadOfSilentlyClampingThem() {
        FactorDefinition ratio = new FactorDefinition(
                "RELATIVE_STRENGTH_20D", "20日相对强度", "SHORT_RIGHT_SIDE", "ratio",
                FactorDirection.HIGHER_IS_BETTER, "rs_20d", FactorMissingPolicy.REDUCE_CONFIDENCE, "v2.0.0");
        FactorDefinition percentile = new FactorDefinition(
                "PB_PERCENTILE", "PB分位", "VALUE_REVERSION", "percentile",
                FactorDirection.LOWER_IS_BETTER, "pb_percentile", FactorMissingPolicy.BLOCK, "v2.0.0");

        assertThatThrownBy(() -> engine.evaluate(ratio, new FactorInput("002714", Map.of(
                "rs_20d", new FactorInput.Measure(new BigDecimal("1.01"), "ratio")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NORMALIZED_VALUE_OUT_OF_RANGE:RELATIVE_STRENGTH_20D ratio");
        assertThatThrownBy(() -> engine.evaluate(percentile, new FactorInput("002714", Map.of(
                "pb_percentile", new FactorInput.Measure(new BigDecimal("100.01"), "percentile")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NORMALIZED_VALUE_OUT_OF_RANGE:PB_PERCENTILE percentile");
    }
}
