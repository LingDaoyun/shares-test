package com.aistock.research.shortterm.validation;

import java.util.List;

public record ShortTermValidationBatchRequest(
        List<ShortTermValidationCohortRequest> cohorts
) {
}
