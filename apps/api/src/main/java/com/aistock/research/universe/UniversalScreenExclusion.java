package com.aistock.research.universe;

import java.util.List;

public record UniversalScreenExclusion(
        String symbol,
        String name,
        String stage,
        String reason,
        List<String> evidence
) {
}
