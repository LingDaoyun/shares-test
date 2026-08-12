package com.aistock.research.shortterm;

import java.util.List;

public record ShortTermCrossSectionContext(
        int marketUniverseCount,
        int industryCount,
        int relativeStrengthSampleCount,
        String basis,
        List<String> dataGaps
) {
    public ShortTermCrossSectionContext {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    public static ShortTermCrossSectionContext unavailable() {
        return new ShortTermCrossSectionContext(
                0, 0, 0,
                "横截面数据未计算",
                List.of("历史报告未包含横截面快照")
        );
    }
}
