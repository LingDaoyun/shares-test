package com.aistock.research.tradefeedback;

public enum RecommendationSource {
    SHORT_TERM("SHORT_TERM", "short-term-right-side-v3-chip-verified"),
    LONG_TERM_VALUE("LONG_TERM", "long-term-value-v1"),
    HOT_TRACKER("HOT_TRACKER", "hot-tracker-v2"),
    MISPRICING("MISPRICING", "mispricing-v2"),
    CYCLE_TRIAL("CYCLE_TRIAL", "cycle-trial-v2"),
    DAILY_SIGNAL("DAILY_SIGNAL", "daily-signal-v1");

    private final String sourceModule;
    private final String ruleVersion;

    RecommendationSource(String sourceModule, String ruleVersion) {
        this.sourceModule = sourceModule;
        this.ruleVersion = ruleVersion;
    }

    public String sourceModule() {
        return sourceModule;
    }

    public String ruleVersion() {
        return ruleVersion;
    }
}
