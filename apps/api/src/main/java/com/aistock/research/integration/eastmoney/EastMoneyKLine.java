package com.aistock.research.integration.eastmoney;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EastMoneyKLine(
        String symbol,
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume,
        BigDecimal amount
) {
}
