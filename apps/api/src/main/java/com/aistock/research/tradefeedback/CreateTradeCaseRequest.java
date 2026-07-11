package com.aistock.research.tradefeedback;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateTradeCaseRequest(
        @Size(max = 36, message = "决策 ID 不能超过 36 个字符")
        String decisionId,
        @NotBlank(message = "股票代码不能为空")
        @Pattern(regexp = "\\d{6}", message = "股票代码必须是 6 位数字")
        String symbol,
        @NotBlank(message = "公司名称不能为空")
        @Size(max = 128, message = "公司名称不能超过 128 个字符")
        String companyName,
        @NotBlank(message = "来源模块不能为空")
        @Size(max = 64, message = "来源模块不能超过 64 个字符")
        String sourceModule,
        @NotBlank(message = "推荐动作不能为空")
        @Size(max = 64, message = "推荐动作不能超过 64 个字符")
        String recommendationAction,
        BigDecimal recommendationScore,
        @NotBlank(message = "规则版本不能为空")
        @Size(max = 64, message = "规则版本不能超过 64 个字符")
        String ruleVersion,
        @NotNull(message = "推荐价格不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "推荐价格必须大于零")
        BigDecimal recommendedPrice,
        @NotNull(message = "推荐时间不能为空")
        Instant recommendedAt,
        Object recommendationPayload
) {
}
