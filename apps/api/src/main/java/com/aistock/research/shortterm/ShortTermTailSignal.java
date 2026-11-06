package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermTailSignal(
        String status,
        String statusLabel,
        boolean actionableTailWindow,
        String tradeDate,
        String latestMinute,
        BigDecimal latestPrice,
        BigDecimal tailStartPrice,
        BigDecimal changeFromActionableTailPercent,
        BigDecimal drawdownFromTailHighPercent,
        BigDecimal closeVsAveragePricePercent,
        BigDecimal tailAmount,
        BigDecimal tailAmountRatioPercent,
        BigDecimal score,
        List<String> reasons,
        List<String> riskControls,
        ShortTermIntradayPullbackSignal intradayPullback
) {
    public ShortTermTailSignal(String status, String statusLabel, boolean actionableTailWindow,
            String tradeDate, String latestMinute, BigDecimal latestPrice, BigDecimal tailStartPrice,
            BigDecimal changeFromActionableTailPercent, BigDecimal drawdownFromTailHighPercent,
            BigDecimal closeVsAveragePricePercent, BigDecimal tailAmount, BigDecimal tailAmountRatioPercent,
            BigDecimal score, List<String> reasons, List<String> riskControls) {
        this(status, statusLabel, actionableTailWindow, tradeDate, latestMinute, latestPrice, tailStartPrice,
                changeFromActionableTailPercent, drawdownFromTailHighPercent, closeVsAveragePricePercent,
                tailAmount, tailAmountRatioPercent, score, reasons, riskControls, null);
    }

    public ShortTermTailSignal withIntradayPullback(ShortTermIntradayPullbackSignal signal) {
        boolean confirmed = signal != null && signal.confirmed();
        java.util.ArrayList<String> evidence = new java.util.ArrayList<>(reasons == null ? List.of() : reasons);
        if (signal != null) {
            evidence.addAll(signal.reasons());
            evidence.addAll(signal.dataGaps());
        }
        // Preserve an existing timing, stale-data or weak-tail block and expose pullback evidence
        // separately. Only otherwise usable or missing tail evidence needs the pullback gate label.
        boolean preserveTailStatus = confirmed || !("CONFIRMED".equals(status)
                || "WATCH".equals(status) || "UNAVAILABLE".equals(status));
        String gatedStatus = preserveTailStatus ? status
                : signal == null || "UNAVAILABLE".equals(signal.status()) ? "PULLBACK_UNAVAILABLE" : "PULLBACK_WAIT";
        return new ShortTermTailSignal(gatedStatus, preserveTailStatus ? statusLabel
                : signal == null ? "分时回踩数据不足" : signal.statusLabel(), actionableTailWindow,
                tradeDate, latestMinute, latestPrice, tailStartPrice, changeFromActionableTailPercent,
                drawdownFromTailHighPercent, closeVsAveragePricePercent, tailAmount, tailAmountRatioPercent,
                score, List.copyOf(evidence), riskControls, signal);
    }
}
