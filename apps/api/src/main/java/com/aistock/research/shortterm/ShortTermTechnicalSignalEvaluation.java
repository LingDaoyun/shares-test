package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;

import java.util.List;

public record ShortTermTechnicalSignalEvaluation(
        List<EastMoneyKLine> rows,
        ShortTermTechnicalSnapshot snapshot,
        EastMoneyKLine last,
        EastMoneyKLine previous,
        List<String> dataGaps
) {
    public boolean eligibleForOvernightValidation() {
        ShortTermGoldenCrossSnapshot goldenCross = snapshot == null ? null : snapshot.goldenCross();
        return snapshot != null
                && "右侧早期确认".equals(snapshot.rightSideSignal())
                && goldenCross != null
                && goldenCross.confirmedRecent();
    }
}
