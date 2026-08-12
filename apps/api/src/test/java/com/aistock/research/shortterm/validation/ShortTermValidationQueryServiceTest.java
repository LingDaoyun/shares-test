package com.aistock.research.shortterm.validation;

import com.aistock.research.tradefeedback.RecommendationSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortTermValidationQueryServiceTest {

    private final ShortTermSignalOutcomeRepository outcomes = mock(ShortTermSignalOutcomeRepository.class);
    private final ShortTermValidationSettings settings = mock(ShortTermValidationSettings.class);
    private final ShortTermValidationQueryService service = new ShortTermValidationQueryService(
            outcomes, settings, new ShortTermValidationSummaryCalculator());

    @Test
    void returnsDeduplicatedCohortsAndKeepsSmallSamplesUncalibrated() {
        when(settings.enabled()).thenReturn(true);
        when(settings.minimumCohortSamples()).thenReturn(3);
        when(outcomes.findMaturedCohortSamples(
                RecommendationSource.SHORT_TERM.ruleVersion(),
                "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T1"))
                .thenReturn(List.of(sample("1.20"), sample("-0.30")));

        List<ShortTermValidationSummary> result = service.summaries(
                new ShortTermValidationBatchRequest(List.of(
                        cohort("golden_cross_volume", "trend_expansion", "t1"),
                        cohort("GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T1")
                )));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(result.get(0).sampleCount()).isEqualTo(2);
        assertThat(result.get(0).positiveRatePercent()).isNull();
        verify(outcomes).findMaturedCohortSamples(
                RecommendationSource.SHORT_TERM.ruleVersion(),
                "GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T1");
    }

    @Test
    void returnsDisabledStatusWithoutReadingOutcomeSamples() {
        when(settings.enabled()).thenReturn(false);
        when(settings.minimumCohortSamples()).thenReturn(30);

        List<ShortTermValidationSummary> result = service.summaries(
                new ShortTermValidationBatchRequest(List.of(
                        cohort("GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T2")
                )));

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.status()).isEqualTo("VALIDATION_DISABLED");
            assertThat(summary.sampleCount()).isZero();
            assertThat(summary.positiveRatePercent()).isNull();
        });
    }

    @Test
    void rejectsUnknownHorizonsInsteadOfQueryingAmbiguousLabels() {
        when(settings.enabled()).thenReturn(true);

        assertThatThrownBy(() -> service.summaries(
                new ShortTermValidationBatchRequest(List.of(
                        cohort("GOLDEN_CROSS_VOLUME", "TREND_EXPANSION", "T20")
                ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T1/T2");
    }

    private ShortTermValidationCohortRequest cohort(String family, String regime, String horizon) {
        return new ShortTermValidationCohortRequest(family, regime, horizon);
    }

    private ShortTermValidationSample sample(String netReturn) {
        return new ShortTermValidationSample(
                new BigDecimal(netReturn), new BigDecimal("2.00"), new BigDecimal("-1.00"));
    }
}
