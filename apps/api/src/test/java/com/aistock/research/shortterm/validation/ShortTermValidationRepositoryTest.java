package com.aistock.research.shortterm.validation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ShortTermValidationRepositoryTest {

    @Autowired
    private ShortTermSignalObservationRepository observations;

    @Autowired
    private ShortTermSignalOutcomeRepository outcomes;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clean() {
        outcomes.deleteAll();
        observations.deleteAll();
    }

    @Test
    void storesStrategyObservationsWithoutCreatingUserHoldings() {
        ShortTermSignalObservationEntity observation = observation(
                "obs-1", "scheduled-final-1", true, true,
                "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION");
        observations.saveAndFlush(observation);
        outcomes.saveAndFlush(ShortTermSignalOutcomeEntity.pending(
                "outcome-1", "obs-1", "T1", LocalDate.parse("2026-08-13"), observation.getCreatedAt()));

        assertThat(observations.findById("obs-1")).isPresent();
        assertThat(outcomes.findByObservationIdOrderByHorizonAsc("obs-1")).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from strategy_trade_case", Long.class))
                .isZero();
    }

    @Test
    void cohortQueryExcludesManualAndMismatchedObservations() {
        Instant calculatedAt = Instant.parse("2026-08-14T07:00:00Z");
        List<ShortTermSignalObservationEntity> rows = List.of(
                observation("obs-included", "scheduled-1", true, true,
                        "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION"),
                observation("obs-manual", "manual-1", true, false,
                        "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION"),
                observation("obs-other-regime", "scheduled-2", true, true,
                        "GOLDEN_CROSS_VOLUME", "REPAIR")
        );
        observations.saveAll(rows);
        rows.forEach(row -> outcomes.save(matured(row.getObservationId(), calculatedAt)));
        observations.flush();
        outcomes.flush();

        assertThat(outcomes.findMaturedCohortSamples(
                "short-term-right-side-v4-transparent-ranking",
                "GOLDEN_CROSS_VOLUME",
                "TREND_EXPANSION",
                "T1"
        )).singleElement().satisfies(sample ->
                assertThat(sample.netReturnPercent()).isEqualByComparingTo("1.25"));
    }

    private ShortTermSignalObservationEntity observation(
            String id,
            String publicationKey,
            boolean validationEligible,
            boolean calibrationEligible,
            String signalFamily,
            String regime
    ) {
        Instant now = Instant.parse("2026-08-12T06:49:00Z");
        return new ShortTermSignalObservationEntity(
                id,
                publicationKey,
                calibrationEligible ? "SCHEDULED_FINAL" : "MANUAL_SCAN",
                "short-term-right-side-v4-transparent-ranking",
                "600001",
                "样本公司",
                1,
                "WAIT",
                signalFamily,
                regime,
                new BigDecimal("100.00"),
                LocalDate.parse("2026-08-12"),
                now,
                now,
                "EAST_MONEY",
                new BigDecimal("1.00"),
                5885,
                5885,
                validationEligible,
                calibrationEligible,
                validationEligible ? null : "DATA_BLOCKED",
                "{}",
                new ShortTermValidationCostAssumptions(
                        new BigDecimal("0.03"), new BigDecimal("0.03"), new BigDecimal("0.05"),
                        new BigDecimal("0.05"), new BigDecimal("0.05")),
                now
        );
    }

    private ShortTermSignalOutcomeEntity matured(String observationId, Instant now) {
        ShortTermSignalOutcomeEntity outcome = ShortTermSignalOutcomeEntity.pending(
                "outcome-" + observationId,
                observationId,
                "T1",
                LocalDate.parse("2026-08-13"),
                now.minusSeconds(60)
        );
        outcome.applyEvaluation(
                new ShortTermHorizonEvaluation(
                        "T1", "MATURED", LocalDate.parse("2026-08-13"),
                        new BigDecimal("101.50"), new BigDecimal("1.50"), new BigDecimal("1.25"),
                        new BigDecimal("3.00"), new BigDecimal("-1.00"), "test"),
                "EAST_MONEY",
                now,
                now
        );
        return outcome;
    }
}
