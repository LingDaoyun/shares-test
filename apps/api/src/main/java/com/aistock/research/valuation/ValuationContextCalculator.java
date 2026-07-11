package com.aistock.research.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class ValuationContextCalculator {

    private static final BigDecimal DEFAULT_PE_REFERENCE = new BigDecimal("45");
    private static final BigDecimal DEFAULT_PB_REFERENCE = new BigDecimal("6");

    public ValuationContext evaluate(
            BigDecimal rawPe,
            BigDecimal rawPb,
            BigDecimal peReference,
            BigDecimal pbReference,
            String industry
    ) {
        BigDecimal safePeReference = positiveOrDefault(peReference, DEFAULT_PE_REFERENCE);
        BigDecimal safePbReference = positiveOrDefault(pbReference, DEFAULT_PB_REFERENCE);
        ValuationModel model = modelFor(industry);
        List<String> warnings = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        evidence.add("PE 参考带=" + plain(safePeReference));
        evidence.add("PB 参考带=" + plain(safePbReference));
        evidence.add("参考带只影响估值语境，不参与资格淘汰");

        if (rawPe == null || rawPb == null) {
            warnings.add("PE 或 PB 单项缺失，估值证据不完整，不能判定为便宜");
            return context(
                    new BigDecimal("50"),
                    ValuationContextState.MISSING,
                    model,
                    rawPe,
                    rawPb,
                    safePeReference,
                    safePbReference,
                    warnings,
                    evidence
            );
        }

        boolean distorted = nonPositive(rawPe) || nonPositive(rawPb);
        if (nonPositive(rawPe)) {
            warnings.add("PE 非正，表示当前亏损或盈利倍数失真");
        }
        if (nonPositive(rawPb)) {
            warnings.add("PB 非正，需要复核净资产口径");
        }

        BigDecimal peScore = metricScore(rawPe, safePeReference);
        BigDecimal pbScore = metricScore(rawPb, safePbReference);
        BigDecimal score = combine(peScore, pbScore);
        ValuationContextState state = distorted
                ? ValuationContextState.DISTORTED
                : score.compareTo(new BigDecimal("75")) >= 0
                ? ValuationContextState.CHEAP
                : score.compareTo(new BigDecimal("45")) >= 0
                ? ValuationContextState.FAIR
                : ValuationContextState.STRETCHED;
        if (state == ValuationContextState.STRETCHED) {
            warnings.add("PE/PB 高于参考语境，需要更强增长证据和更严格风控");
        }
        return context(
                score,
                state,
                model,
                rawPe,
                rawPb,
                safePeReference,
                safePbReference,
                warnings,
                evidence
        );
    }

    private ValuationContext context(
            BigDecimal score,
            ValuationContextState state,
            ValuationModel model,
            BigDecimal rawPe,
            BigDecimal rawPb,
            BigDecimal peReference,
            BigDecimal pbReference,
            List<String> warnings,
            List<String> evidence
    ) {
        return new ValuationContext(
                scale(score),
                state,
                model,
                rawPe,
                rawPb,
                peReference,
                pbReference,
                null,
                null,
                false,
                List.copyOf(warnings),
                List.copyOf(evidence)
        );
    }

    private BigDecimal metricScore(BigDecimal value, BigDecimal reference) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal ratio = value.divide(reference, 8, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) <= 0) {
            return new BigDecimal("90").subtract(ratio.multiply(new BigDecimal("20")));
        }
        if (ratio.compareTo(new BigDecimal("1.8")) <= 0) {
            return new BigDecimal("70").subtract(
                    ratio.subtract(BigDecimal.ONE)
                            .divide(new BigDecimal("0.8"), 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("20"))
            );
        }
        if (ratio.compareTo(new BigDecimal("2.5")) <= 0) {
            return new BigDecimal("50").subtract(
                    ratio.subtract(new BigDecimal("1.8"))
                            .divide(new BigDecimal("0.7"), 8, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("15"))
            );
        }
        return new BigDecimal("25");
    }

    private BigDecimal combine(BigDecimal peScore, BigDecimal pbScore) {
        if (peScore == null && pbScore == null) {
            return new BigDecimal("50");
        }
        if (peScore == null) {
            return pbScore;
        }
        if (pbScore == null) {
            return peScore;
        }
        return peScore.multiply(new BigDecimal("0.55"))
                .add(pbScore.multiply(new BigDecimal("0.45")));
    }

    private ValuationModel modelFor(String industry) {
        if (containsAny(industry, "银行", "保险", "证券", "多元金融")) {
            return ValuationModel.FINANCIAL;
        }
        if (containsAny(
                industry,
                "农业",
                "养殖",
                "生猪",
                "煤炭",
                "有色",
                "钢铁",
                "化工",
                "水泥",
                "建材",
                "航运",
                "电池"
        )) {
            return ValuationModel.CYCLICAL;
        }
        return ValuationModel.STANDARD;
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean nonPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) <= 0;
    }

    private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : fallback;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.max(BigDecimal.ZERO)
                .min(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
