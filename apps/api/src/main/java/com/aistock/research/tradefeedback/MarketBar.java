package com.aistock.research.tradefeedback;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarketBar(
        LocalDate tradeDate,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low
) {
}
