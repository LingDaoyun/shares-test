package com.aistock.research.mispricing;

import java.util.List;

public record MispricingReviewResult(
        String status,
        String statusLabel,
        String conclusion,
        List<String> verifiedFindings,
        List<String> blockers,
        List<MispricingEvidenceItem> sources
) {
}
