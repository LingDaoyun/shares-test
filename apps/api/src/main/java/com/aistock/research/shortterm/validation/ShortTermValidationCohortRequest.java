package com.aistock.research.shortterm.validation;

public record ShortTermValidationCohortRequest(
        String signalFamily,
        String marketRegime,
        String horizon
) {
}
