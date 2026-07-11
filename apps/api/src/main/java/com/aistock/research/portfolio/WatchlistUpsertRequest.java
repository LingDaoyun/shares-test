package com.aistock.research.portfolio;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WatchlistUpsertRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "股票代码必须是 6 位数字")
        String symbol,
        @Size(max = 1000, message = "关注备注不能超过 1000 个字符")
        String note
) {
}
