package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EastMoneyIntradayPoint(
        String symbol,
        LocalDateTime minute,
        BigDecimal open,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume,
        BigDecimal amount,
        BigDecimal averagePrice
) {
}
