package com.aistock.research.shortterm;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ShortTermMarketPulseFreshnessTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime DECISION = DATE.atTime(14, 49);
    private static final ShortTermLimitUpSentiment PULSE = new ShortTermLimitUpSentiment(
            20, 10, 0, new BigDecimal("33.33"), 3, 4, 1, 10, 6, 4, 0, new BigDecimal("50"), "修复", "测试");

    @Test
    void onlyDatedAvailableAndRecentPoolCanClaimMeasuredCounts() {
        assertThat(ShortTermService.trustedLimitUpPulse(board(DATE, "2026-10-16T06:48:00Z"), DECISION)).isEqualTo(PULSE);
        assertThat(ShortTermService.trustedLimitUpPulse(board(DATE.minusDays(1), "2026-10-16T06:48:00Z"), DECISION)).isNull();
        assertThat(ShortTermService.trustedLimitUpPulse(board(DATE, "2026-10-16T06:49:01Z"), DECISION)).isNull();
        assertThat(ShortTermService.trustedLimitUpPulse(board(DATE, "2026-10-16T06:45:59Z"), DECISION)).isNull();
        assertThat(ShortTermService.trustedLimitUpPulse(ShortTermLimitUpBoardSnapshot.unavailable(DATE,
                Instant.parse("2026-10-16T06:48:00Z"), "失败"), DECISION)).isNull();
    }

    private ShortTermLimitUpBoardSnapshot board(LocalDate date, String at) {
        return new ShortTermLimitUpBoardSnapshot(date, Instant.parse(at), true, null, List.of(), true,
                List.of(), List.of(), PULSE, null, List.of());
    }
}
