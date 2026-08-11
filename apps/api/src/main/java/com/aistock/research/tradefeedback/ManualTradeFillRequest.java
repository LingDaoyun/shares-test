package com.aistock.research.tradefeedback;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ManualTradeFillRequest(
        @NotBlank(message = "股票代码不能为空")
        @Pattern(regexp = "\\d{6}", message = "股票代码必须是 6 位数字")
        String symbol,
        @NotBlank(message = "公司名称不能为空")
        @Size(max = 128, message = "公司名称不能超过 128 个字符")
        String companyName,
        @NotNull(message = "成交信息不能为空")
        @Valid
        UpsertTradeFillRequest fill
) {
}
