package com.aistock.research.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PolicySourceConfig(
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank
        @Pattern(regexp = "(?i)https?://.+", message = "必须是 http 或 https 地址")
        String url,
        @Min(1) @Max(100) int weight
) {
}
