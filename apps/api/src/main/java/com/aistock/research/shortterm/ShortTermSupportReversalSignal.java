package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermSupportReversalSignal(
        String state,
        String stateLabel,
        BigDecimal score,
        BigDecimal lowerShadowPercent,
        BigDecimal bodyPercent,
        BigDecimal upperShadowPercent,
        BigDecimal closeLocationPercent,
        String supportType,
        BigDecimal supportPrice,
        boolean supportReclaimed,
        boolean trendQualified,
        boolean volumeQualified,
        boolean turnoverQualified,
        boolean provisional,
        List<String> reasons,
        List<String> dataGaps
) {
    public ShortTermSupportReversalSignal {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public boolean confirmed() {
        return "CONFIRMED".equals(state);
    }

    public boolean watchLayer() {
        return "OBSERVATION".equals(state);
    }

    public static ShortTermSupportReversalSignal unavailable() {
        return new ShortTermSupportReversalSignal(
                "UNAVAILABLE",
                "长下影承接待复核",
                BigDecimal.ZERO.setScale(2),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                List.of("K线或趋势数据不足")
        );
    }
}
