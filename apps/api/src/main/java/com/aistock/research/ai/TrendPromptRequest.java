package com.aistock.research.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TrendPromptRequest(
        @NotBlank
        String documentTitle,

        @NotBlank
        String documentType,

        String sourceOrganization,

        String publishedAt,

        String sourceUrl,

        @NotBlank
        @Size(max = 12000)
        String contentExcerpt,

        List<String> focusThemes,

        List<String> knownCompanies
) {
}
