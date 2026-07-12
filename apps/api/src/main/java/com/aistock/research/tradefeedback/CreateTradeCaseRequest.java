package com.aistock.research.tradefeedback;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTradeCaseRequest(
        @NotBlank(message = "推荐凭证不能为空")
        @Size(max = 128, message = "推荐凭证不能超过 128 个字符")
        String attestationToken
) {
}
