package com.aistock.research.tradefeedback;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record UpsertTradeFillRequest(
        @NotNull(message = "成交方向必须为 BUY 或 SELL")
        TradeSide side,
        @NotNull(message = "成交时间不能为空")
        Instant executedAt,
        @NotNull(message = "成交价格不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "成交价格必须大于零")
        BigDecimal price,
        @Positive(message = "成交股数必须为正整数")
        long quantity
) {
}
