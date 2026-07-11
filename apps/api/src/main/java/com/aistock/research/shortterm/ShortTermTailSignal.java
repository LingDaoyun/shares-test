package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.util.List;

public record ShortTermTailSignal(
        String status,
        String statusLabel,
        boolean afterTailConfirm,
        String tradeDate,
        String latestMinute,
        BigDecimal latestPrice,
        BigDecimal tailStartPrice,
        BigDecimal changeFromTailConfirmPercent,
        BigDecimal drawdownFromTailHighPercent,
        BigDecimal closeVsAveragePricePercent,
        BigDecimal tailAmount,
        BigDecimal tailAmountRatioPercent,
        BigDecimal score,
        List<String> reasons,
        List<String> riskControls
) {
}
