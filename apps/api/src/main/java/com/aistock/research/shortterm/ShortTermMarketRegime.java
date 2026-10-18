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
        List<String> dataGaps,
        ShortTermMarketLiquidity liquidity,
        Integer limitUpCount,
        Integer limitDownCount,
        Integer brokenCount,
        BigDecimal sealBreakRatioPercent,
        String limitCountSource
) {
    public ShortTermMarketRegime(String state, String label, BigDecimal breadthPercent,
            BigDecimal medianChangePercent, BigDecimal averageAbsoluteChangePercent,
            BigDecimal advancingTurnoverSharePercent, BigDecimal limitUpRatioPercent,
            BigDecimal limitDownRatioPercent, int sampleCount, String maxAction,
            String explanation, List<String> dataGaps) {
        this(state, label, breadthPercent, medianChangePercent, averageAbsoluteChangePercent,
                advancingTurnoverSharePercent, limitUpRatioPercent, limitDownRatioPercent,
                sampleCount, maxAction, explanation, dataGaps, null, null, null, null, null, "UNAVAILABLE");
    }
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

    public boolean blocksExecution() {
        return "NO_TRADE".equals(maxAction) || "UNAVAILABLE".equals(state) || riskOff();
    }
}
