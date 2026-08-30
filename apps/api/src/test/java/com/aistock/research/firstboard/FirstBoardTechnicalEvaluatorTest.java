package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirstBoardTechnicalEvaluatorTest {

    private final FirstBoardTechnicalEvaluator evaluator = new FirstBoardTechnicalEvaluator();

    @Test
    void rejectsSeriesWithFewerThanSixtyCompletedBars() {
        assertThat(evaluator.evaluate("600000", bars(59), LocalDate.parse("2026-08-28"), false)).isEmpty();
    }

    @Test
    void calculatesTrendMomentumVolumeAndShapeMetricsFromTMinusOne() {
        FirstBoardBaseline result = evaluator
                .evaluate("600000", bars(80), LocalDate.parse("2026-08-28"), true)
                .orElseThrow();

        assertThat(result.baselineDate()).isEqualTo(LocalDate.parse("2026-08-28"));
        assertThat(result.previousLimitUp()).isTrue();
        assertThat(result.ma10()).isGreaterThan(result.ma20());
        assertThat(result.ma20Slope()).isPositive();
        assertThat(result.rsi14()).isGreaterThanOrEqualTo(new BigDecimal("50"));
        assertThat(result.macdHistogram()).isPositive();
        assertThat(result.technicalPassCount()).isEqualTo(5);
        assertThat(result.previousDayVolumeRatio()).isPositive();
        assertThat(result.recentFiveMaxVolumeRatio()).isGreaterThanOrEqualTo(result.previousDayVolumeRatio());
        assertThat(result.upperShadowRatio()).isNotNegative();
        assertThat(result.threeDayCloseRange()).isPositive();
        assertThat(result.recentBars()).hasSize(20);
    }

    private List<EastMoneyKLine> bars(int count) {
        LocalDate end = LocalDate.parse("2026-08-28");
        List<EastMoneyKLine> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal close = new BigDecimal("8.00").add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.05")));
            BigDecimal open = close.subtract(new BigDecimal("0.03"));
            rows.add(new EastMoneyKLine(
                    "600000",
                    end.minusDays(count - 1L - i),
                    open,
                    close,
                    close.add(new BigDecimal("0.08")),
                    open.subtract(new BigDecimal("0.05")),
                    BigDecimal.valueOf(1_000_000L + i * 20_000L),
                    BigDecimal.valueOf(10_000_000L + i * 100_000L)
            ));
        }
        return rows;
    }
}
