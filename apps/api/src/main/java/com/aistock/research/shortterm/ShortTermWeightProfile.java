package com.aistock.research.shortterm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ShortTermWeightProfile(
        BigDecimal preliminaryValuation,
        BigDecimal preliminaryLiquidity,
        BigDecimal preliminaryNonChase,
        BigDecimal preliminaryHeat,
        BigDecimal finalGoldenCross,
        BigDecimal finalVolume,
        BigDecimal finalTurnover,
        BigDecimal finalCloseStrength,
        String modelVersion,
        String weightMeaning
) {
    private static final String CURRENT_VERSION = "short-four-signal-v2";
    private static final String LEGACY_VERSION = "legacy-short-term-v1";

    public ShortTermWeightProfile(
            BigDecimal preliminaryValuation,
            BigDecimal preliminaryLiquidity,
            BigDecimal preliminaryNonChase,
            BigDecimal preliminaryHeat,
            BigDecimal finalGoldenCross,
            BigDecimal finalVolume,
            BigDecimal finalTurnover,
            BigDecimal finalCloseStrength
    ) {
        this(
                preliminaryValuation,
                preliminaryLiquidity,
                preliminaryNonChase,
                preliminaryHeat,
                finalGoldenCross,
                finalVolume,
                finalTurnover,
                finalCloseStrength,
                CURRENT_VERSION,
                "golden-cross, volume, turnover, close-strength"
        );
    }

    public ShortTermWeightProfile(
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
        this(
                preliminaryValuation,
                preliminaryLiquidity,
                preliminaryNonChase,
                preliminaryHeat,
                finalTechnical,
                finalVolume,
                finalHeat,
                add(finalFinancial, finalValuation),
                LEGACY_VERSION,
                "legacy technical, volume, heat, financial-and-valuation"
        );
    }

    @JsonCreator
    public static ShortTermWeightProfile fromJson(
            @JsonProperty("preliminaryValuation") BigDecimal preliminaryValuation,
            @JsonProperty("preliminaryLiquidity") BigDecimal preliminaryLiquidity,
            @JsonProperty("preliminaryNonChase") BigDecimal preliminaryNonChase,
            @JsonProperty("preliminaryHeat") BigDecimal preliminaryHeat,
            @JsonProperty("finalGoldenCross") BigDecimal finalGoldenCross,
            @JsonProperty("finalVolume") BigDecimal finalVolume,
            @JsonProperty("finalTurnover") BigDecimal finalTurnover,
            @JsonProperty("finalCloseStrength") BigDecimal finalCloseStrength,
            @JsonProperty("finalTechnical") BigDecimal finalTechnical,
            @JsonProperty("finalHeat") BigDecimal finalHeat,
            @JsonProperty("finalFinancial") BigDecimal finalFinancial,
            @JsonProperty("finalValuation") BigDecimal finalValuation,
            @JsonProperty("modelVersion") String modelVersion,
            @JsonProperty("weightMeaning") String weightMeaning
    ) {
        boolean legacy = LEGACY_VERSION.equals(modelVersion)
                || (finalGoldenCross == null && finalTechnical != null);
        if (legacy) {
            return new ShortTermWeightProfile(
                    preliminaryValuation,
                    preliminaryLiquidity,
                    preliminaryNonChase,
                    preliminaryHeat,
                    finalTechnical,
                    finalVolume,
                    finalHeat,
                    finalFinancial,
                    finalValuation
            );
        }
        return new ShortTermWeightProfile(
                preliminaryValuation,
                preliminaryLiquidity,
                preliminaryNonChase,
                preliminaryHeat,
                finalGoldenCross,
                finalVolume,
                finalTurnover,
                finalCloseStrength,
                modelVersion == null ? CURRENT_VERSION : modelVersion,
                weightMeaning == null
                        ? "golden-cross, volume, turnover, close-strength"
                        : weightMeaning
        );
    }

    public BigDecimal preliminaryTotal() {
        return preliminaryValuation
                .add(preliminaryLiquidity)
                .add(preliminaryNonChase)
                .add(preliminaryHeat);
    }

    public BigDecimal finalTotal() {
        return finalGoldenCross
                .add(finalVolume)
                .add(finalTurnover)
                .add(finalCloseStrength);
    }

    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        return (left == null ? BigDecimal.ZERO : left)
                .add(right == null ? BigDecimal.ZERO : right);
    }
}
