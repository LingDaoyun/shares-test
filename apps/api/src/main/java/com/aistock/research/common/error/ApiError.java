package com.aistock.research.common.error;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        Instant timestamp,
        Map<String, String> fields
) {
}

