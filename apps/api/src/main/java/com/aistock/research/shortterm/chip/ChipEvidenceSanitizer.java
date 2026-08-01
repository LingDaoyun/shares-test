package com.aistock.research.shortterm.chip;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class ChipEvidenceSanitizer {

    private static final Pattern BEARER_CREDENTIAL = Pattern.compile(
            "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:access[-_]?token|refresh[-_]?token|token|api[-_]?key|authorization)"
                    + "[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\\"'\\s,;&}\\]]+)"
    );
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile(
            "(?i)\\bsk-(?:proj-)?[A-Za-z0-9_-]{12,}"
    );

    private ChipEvidenceSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = BEARER_CREDENTIAL.matcher(value)
                .replaceAll("$1[REDACTED]");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized)
                .replaceAll("$1[REDACTED]");
        sanitized = OPENAI_STYLE_KEY.matcher(sanitized)
                .replaceAll("[REDACTED]")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    static List<String> sanitizeAll(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            String safeValue = sanitize(value);
            if (safeValue != null) {
                sanitized.add(safeValue);
            }
        }
        return List.copyOf(sanitized);
    }
}
