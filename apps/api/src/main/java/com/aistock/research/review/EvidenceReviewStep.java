package com.aistock.research.review;

import java.util.List;

public record EvidenceReviewStep(
        String stepCode,
        String actor,
        String conclusion,
        List<String> evidenceRefs
) {
}
