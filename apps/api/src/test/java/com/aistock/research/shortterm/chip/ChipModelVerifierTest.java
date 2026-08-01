package com.aistock.research.shortterm.chip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChipModelVerifierTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 30);
    private final ChipModelVerifier verifier = new ChipModelVerifier(
            new BigDecimal("0.03"),
            new BigDecimal("0.70"),
            new BigDecimal("0.10"),
            new BigDecimal("0.60")
    );

    @Test
    void verifiesMatchingModelsAtTheLatestCompletedTradeDate() {
        ChipVerificationResult result = verifier.verify(local(), external("10.20", "9.40", "10.60", "65"), TRADE_DATE);

        assertThat(result.status()).isEqualTo(ChipVerificationStatus.VERIFIED);
        assertThat(result.coefficient()).isEqualByComparingTo("1.00");
        assertThat(result.averageCostDeviation()).isLessThanOrEqualTo(new BigDecimal("0.03"));
        assertThat(result.cost70BandOverlap()).isGreaterThanOrEqualTo(new BigDecimal("0.70"));
        assertThat(result.winnerRateDeviation()).isEqualByComparingTo("0.05");
    }

    @Test
    void usesSingleSourceCoefficientWhenExternalModelIsUnavailable() {
        ChipVerificationResult result = verifier.verify(local(), null, TRADE_DATE);

        assertThat(result.status()).isEqualTo(ChipVerificationStatus.SINGLE_SOURCE);
        assertThat(result.coefficient()).isEqualByComparingTo("0.60");
        assertThat(result.dataGaps()).contains("外部筹码认证不可用");
    }

    @Test
    void rejectsSameDateModelsThatExceedCostTolerance() {
        ChipVerificationResult result = verifier.verify(local(), external("11.00", "9.40", "10.60", "65"), TRADE_DATE);

        assertThat(result.status()).isEqualTo(ChipVerificationStatus.CONFLICT);
        assertThat(result.coefficient()).isZero();
    }

    @Test
    void rejectsStaleExternalTradeDate() {
        ExternalChipPerformance stale = new ExternalChipPerformance(
                "002580", TRADE_DATE.minusDays(1),
                bd("9.00"), bd("9.40"), bd("10.00"), bd("10.60"), bd("11.00"),
                bd("10.20"), bd("65"), "Tushare cyq_perf", Instant.parse("2026-07-31T10:00:00Z")
        );

        ChipVerificationResult result = verifier.verify(local(), stale, TRADE_DATE);

        assertThat(result.status()).isEqualTo(ChipVerificationStatus.STALE);
        assertThat(result.coefficient()).isZero();
    }

    @Test
    void rejectsInsufficientLocalDistributionBeforeExternalComparison() {
        LocalChipDistribution insufficient = LocalChipDistribution.insufficient(
                ChipCalculationMode.COMPLETED_BAR, TRADE_DATE, 60, bd("50"), List.of("筹码历史数据不足"));

        ChipVerificationResult result = verifier.verify(insufficient, external("10.20", "9.40", "10.60", "65"), TRADE_DATE);

        assertThat(result.status()).isEqualTo(ChipVerificationStatus.INSUFFICIENT);
        assertThat(result.coefficient()).isZero();
    }

    private LocalChipDistribution local() {
        return ChipTestFixtures.localDistribution();
    }

    private ExternalChipPerformance external(String average, String cost15, String cost85, String winner) {
        return new ExternalChipPerformance(
                "002580", TRADE_DATE,
                bd("9.00"), bd(cost15), bd("10.00"), bd(cost85), bd("11.00"),
                bd(average), bd(winner), "Tushare cyq_perf", Instant.parse("2026-07-31T10:00:00Z")
        );
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
