package com.aistock.research.shortterm.chip;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChipStructureScorerTest {

    private final ChipStructureScorer scorer = new ChipStructureScorer(new BigDecimal("0.25"));

    @Test
    void usesTheCompleteValidLocalDistributionWithoutAPaidVerifierDiscount() {
        ChipVerificationResult verification = new ChipVerificationResult(
                ChipVerificationStatus.SINGLE_SOURCE,
                BigDecimal.ONE,
                null, null, null,
                List.of("外部筹码认证不可用")
        );

        ShortTermChipSnapshot snapshot = scorer.score(ChipTestFixtures.localDistribution(), verification, null);

        assertThat(snapshot.chipStructureScore()).isEqualByComparingTo("76.30");
        assertThat(snapshot.verificationCoefficient()).isEqualByComparingTo("1.00");
        assertThat(snapshot.contributionScore()).isEqualByComparingTo("19.08");
        assertThat(snapshot.verificationLabel()).isEqualTo("本地估算 · 未交叉验证");
        assertThat(snapshot.distributionBuckets()).hasSize(3);
        assertThat(snapshot.concentrationZones()).hasSize(1);
        assertThat(snapshot.dominantPeakPrice()).isEqualByComparingTo("10.00");
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
