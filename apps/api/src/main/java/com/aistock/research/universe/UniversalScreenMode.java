package com.aistock.research.universe;

public enum UniversalScreenMode {
    ALL(false, false, false),
    VALUE(true, false, false),
    CYCLE(true, false, true),
    SHORT_TERM(true, true, false);

    private final boolean liquidityRequired;
    private final boolean sidewaysReviewSupported;
    private final boolean cycleIndustryRequired;

    UniversalScreenMode(
            boolean liquidityRequired,
            boolean sidewaysReviewSupported,
            boolean cycleIndustryRequired
    ) {
        this.liquidityRequired = liquidityRequired;
        this.sidewaysReviewSupported = sidewaysReviewSupported;
        this.cycleIndustryRequired = cycleIndustryRequired;
    }

    public boolean liquidityRequired() {
        return liquidityRequired;
    }

    public boolean cycleIndustryRequired() {
        return cycleIndustryRequired;
    }

    public boolean effectiveSidewaysReview(boolean requested) {
        return requested && sidewaysReviewSupported;
    }

    public boolean allowsBuyLikeScreeningAction() {
        return this == VALUE;
    }

    public static UniversalScreenMode fromExternal(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ALL;
        }
    }
}
