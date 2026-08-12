package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermMarketRegime(
        String state,
        String label,
        BigDecimal breadthPercent,
        BigDecimal medianChangePercent,
        BigDecimal averageAbsoluteChangePercent,
        BigDecimal advancingTurnoverSharePercent,
        BigDecimal limitUpRatioPercent,
        BigDecimal limitDownRatioPercent,
        int sampleCount,
        String maxAction,
        String explanation,
        List<String> dataGaps
) {
    public ShortTermMarketRegime {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermMarketRegime unavailable(String reason) {
        return new ShortTermMarketRegime(
                "UNAVAILABLE", "市场状态待补", null, null, null, null, null, null,
                0, "NO_TRADE", "市场状态证据不足，不允许据此产生执行动作。",
                reason == null || reason.isBlank() ? List.of() : List.of(reason)
        );
    }

    public boolean riskOff() {
        return "RISK_OFF".equals(state);
    }

    public boolean lightTrialOnly() {
        return "LIGHT_TRIAL".equals(maxAction);
    }
}
