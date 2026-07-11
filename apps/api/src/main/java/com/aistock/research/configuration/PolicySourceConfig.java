package com.aistock.research.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PolicySourceConfig(
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank String url,
        @Min(1) @Max(100) int weight
) {
}
