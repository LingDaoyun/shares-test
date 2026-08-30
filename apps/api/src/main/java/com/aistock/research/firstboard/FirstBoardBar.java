package com.aistock.research.firstboard;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FirstBoardBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume
) {
}
