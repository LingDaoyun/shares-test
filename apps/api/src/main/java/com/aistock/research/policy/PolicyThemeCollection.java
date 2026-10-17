package com.aistock.research.policy;

import java.util.List;

/** Collection coverage belongs to the snapshot, separate from policy facts and industry risks. */
public record PolicyThemeCollection(List<PolicyTheme> themes, List<String> dataGaps) {
    public PolicyThemeCollection {
        themes = themes == null ? List.of() : List.copyOf(themes);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
