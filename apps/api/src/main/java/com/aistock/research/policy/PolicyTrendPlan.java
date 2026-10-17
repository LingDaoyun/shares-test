package com.aistock.research.policy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** A conditional AI hypothesis, not verified policy facts or market confirmation. */
public record PolicyTrendPlan(
        String thesis,
        String policyIncrement,
        List<String> benefitChain,
        List<String> shortTermCatalysts,
        List<String> mediumTermCatalysts,
        List<String> validationSignals,
        List<String> invalidationSignals,
        String marketConfirmation,
        List<PolicyCompanyAssessment> companyAssessments
) {
    public static final String MARKET_CONFIRMATION = "尚未接入板块量价序列，需验证价格、成交与持续性";

    public PolicyTrendPlan {
        benefitChain = copy(benefitChain);
        shortTermCatalysts = copy(shortTermCatalysts);
        mediumTermCatalysts = copy(mediumTermCatalysts);
        validationSignals = copy(validationSignals);
        invalidationSignals = copy(invalidationSignals);
        marketConfirmation = MARKET_CONFIRMATION;
        companyAssessments = companyAssessments == null ? List.of() : List.copyOf(companyAssessments);
    }

    public PolicyTrendPlan(String thesis, String policyIncrement, List<String> benefitChain,
                           List<String> shortTermCatalysts, List<String> mediumTermCatalysts,
                           List<String> validationSignals, List<String> invalidationSignals, String marketConfirmation) {
        this(thesis, policyIncrement, benefitChain, shortTermCatalysts, mediumTermCatalysts,
                validationSignals, invalidationSignals, marketConfirmation, List.of());
    }

    @JsonIgnore
    public boolean isValid() {
        return validText(thesis) && validText(policyIncrement)
                && validList(benefitChain) && !benefitChain.isEmpty()
                && validList(shortTermCatalysts) && validList(mediumTermCatalysts)
                && validList(validationSignals) && !validationSignals.isEmpty()
                && validList(invalidationSignals) && !invalidationSignals.isEmpty()
                && companyAssessments.size() <= 8
                && companyAssessments.stream().allMatch(company -> validText(company.symbol())
                    && validText(company.companyName()) && validText(company.rationale())
                    && validText(company.verificationNeeded())
                    && List.of("DIRECT", "INDUSTRY", "UNCERTAIN").contains(company.relevance()));
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 600;
    }

    private static boolean validList(List<String> values) {
        return values.size() <= 8 && values.stream().allMatch(PolicyTrendPlan::validText);
    }
}
