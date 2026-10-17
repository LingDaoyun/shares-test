package com.aistock.research.policy;

/** Industry association inferred by AI; verification is required before investment use. */
public record PolicyCompanyAssessment(
        String symbol,
        String companyName,
        String relevance,
        String rationale,
        String verificationNeeded
) { }
