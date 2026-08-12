package com.aistock.research.shortterm;

import java.util.Map;

public record ShortTermCrossSectionAnalysis(
        ShortTermCrossSectionContext context,
        Map<String, ShortTermRelativeStrength> relativeStrengthBySymbol,
        Map<String, ShortTermIndustryLeadership> industryLeadershipBySymbol
) {
    public ShortTermCrossSectionAnalysis {
        context = context == null ? ShortTermCrossSectionContext.unavailable() : context;
        relativeStrengthBySymbol = relativeStrengthBySymbol == null
                ? Map.of() : Map.copyOf(relativeStrengthBySymbol);
        industryLeadershipBySymbol = industryLeadershipBySymbol == null
                ? Map.of() : Map.copyOf(industryLeadershipBySymbol);
    }
}
