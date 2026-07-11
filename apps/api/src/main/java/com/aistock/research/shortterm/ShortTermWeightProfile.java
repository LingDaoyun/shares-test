package com.aistock.research.shortterm;

import java.math.BigDecimal;

public record ShortTermWeightProfile(
        BigDecimal preliminaryValuation,
        BigDecimal preliminaryLiquidity,
        BigDecimal preliminaryNonChase,
        BigDecimal preliminaryHeat,
        BigDecimal finalTechnical,
        BigDecimal finalVolume,
        BigDecimal finalHeat,
        BigDecimal finalFinancial,
        BigDecimal finalValuation
) {
    public BigDecimal preliminaryTotal() {
        return preliminaryValuation
                .add(preliminaryLiquidity)
                .add(preliminaryNonChase)
                .add(preliminaryHeat);
    }

    public BigDecimal finalTotal() {
        return finalTechnical
                .add(finalVolume)
                .add(finalHeat)
                .add(finalFinancial)
                .add(finalValuation);
    }
}
