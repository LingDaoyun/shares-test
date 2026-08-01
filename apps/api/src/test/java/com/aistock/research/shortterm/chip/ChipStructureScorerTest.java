package com.aistock.research.shortterm.chip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChipStructureScorerTest {

    private final ChipStructureScorer scorer = new ChipStructureScorer(new BigDecimal("0.25"));

    @Test
    void appliesSingleSourceCoefficientToTheMaximumTwentyFivePointContribution() {
        ChipVerificationResult verification = new ChipVerificationResult(
                ChipVerificationStatus.SINGLE_SOURCE,
                new BigDecimal("0.60"),
                null, null, null,
                List.of("外部筹码认证不可用")
        );

        ShortTermChipSnapshot snapshot = scorer.score(ChipTestFixtures.localDistribution(), verification, null);

        assertThat(snapshot.chipStructureScore()).isEqualByComparingTo("76.30");
        assertThat(snapshot.verificationCoefficient()).isEqualByComparingTo("0.60");
        assertThat(snapshot.contributionScore()).isEqualByComparingTo("11.45");
        assertThat(snapshot.verificationLabel()).isEqualTo("单源模型");
    }

    @Test
    void contributesZeroWhenModelVerificationConflicts() {
        ChipVerificationResult verification = new ChipVerificationResult(
                ChipVerificationStatus.CONFLICT,
                BigDecimal.ZERO,
                new BigDecimal("0.08"), new BigDecimal("0.50"), new BigDecimal("0.20"),
                List.of("本地与外部筹码模型超出容差")
        );

        ShortTermChipSnapshot snapshot = scorer.score(ChipTestFixtures.localDistribution(), verification, null);

        assertThat(snapshot.chipStructureScore()).isPositive();
        assertThat(snapshot.contributionScore()).isZero();
        assertThat(snapshot.verificationStatus()).isEqualTo(ChipVerificationStatus.CONFLICT);
    }

    @Test
    void doesNotRewardAPriceFarAboveTheAverageCost() {
        LocalChipDistribution local = ChipTestFixtures.localDistributionWithDistance(new BigDecimal("18"));
        ChipVerificationResult verified = new ChipVerificationResult(
                ChipVerificationStatus.VERIFIED, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, List.of());

        ShortTermChipSnapshot snapshot = scorer.score(local, verified, null);

        assertThat(snapshot.costPositionScore()).isZero();
        assertThat(snapshot.dataGaps()).contains("当前价明显远离推算成本中枢，存在追高风险");
    }
}
