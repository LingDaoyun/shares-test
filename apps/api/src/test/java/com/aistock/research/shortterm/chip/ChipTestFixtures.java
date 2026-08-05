package com.aistock.research.shortterm.chip;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

final class ChipTestFixtures {

    private ChipTestFixtures() {
    }

    static LocalChipDistribution localDistribution() {
        return localDistributionWithDistance(new BigDecimal("4.00"));
    }

    static LocalChipDistribution localDistributionWithDistance(BigDecimal distance) {
        List<ChipDistributionBucket> buckets = List.of(
                new ChipDistributionBucket(bd("9.50"), bd("9.80"), bd("9.65"), bd("25.00"), bd("50.00")),
                new ChipDistributionBucket(bd("9.80"), bd("10.20"), bd("10.00"), bd("50.00"), bd("100.00")),
                new ChipDistributionBucket(bd("10.20"), bd("10.50"), bd("10.35"), bd("25.00"), bd("50.00"))
        );
        ChipConcentrationZone dominant = new ChipConcentrationZone(
                1, bd("9.80"), bd("10.20"), bd("10.00"), bd("50.00"), bd("-3.85"), ChipPricePosition.BELOW);
        return new LocalChipDistribution(
                ChipDataQuality.VALID,
                ChipCalculationMode.COMPLETED_BAR,
                LocalDate.of(2026, 7, 30),
                bd("10.00"),
                bd("9.00"), bd("9.50"), bd("10.00"), bd("10.50"), bd("11.00"),
                bd("60.00"), bd("40.00"),
                bd("9.50"), bd("10.50"), bd("10.00"),
                bd("9.00"), bd("11.00"), bd("20.00"),
                distance,
                bd("15.00"), bd("10.00"), bd("120.00"),
                bd("100.00"), 120,
                buckets, List.of(dominant),
                bd("10.00"), bd("9.80"), bd("10.20"), bd("50.00"), ChipPricePosition.ABOVE, null,
                List.of()
        );
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
