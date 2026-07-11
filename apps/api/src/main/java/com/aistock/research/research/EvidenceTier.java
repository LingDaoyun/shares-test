package com.aistock.research.research;

import java.util.List;

public record EvidenceTier(
        String code,
        String label,
        int strength,
        List<String> evidenceRefs
) {
}
