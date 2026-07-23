package com.aistock.research.v2.strategy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LongTermStrategyService {

    private static final String FAMILY = "LONG_TERM";
    private static final String VALUATION_USAGE = "CONTEXT_NOT_HARD_GATE";

    public List<StrategySignal> evaluate(LongTermStrategyInput input) {
        validate(input);
        List<String> hardRiskReasons = hardRiskReasons(input.riskFlags());
        if (!hardRiskReasons.isEmpty()) {
            return List.of(
                    blocked(StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", input, hardRiskReasons),
                    blocked(StrategyCode.QUALITY_COMPOUNDER, "quality-compounder-v2.0.0", input, hardRiskReasons),
                    blocked(StrategyCode.CYCLE_REVERSAL, "cycle-reversal-v2.0.0", input, hardRiskReasons)
            );
        }
        return List.of(
                valueReversion(input),
                qualityCompounder(input),
                cycleReversal(input)
        );
    }

    private StrategySignal valueReversion(LongTermStrategyInput input) {
        BigDecimal rankScore = weighted(
                score(input.valuationDiscountScore()), "0.38",
                score(input.qualityScore()), "0.20",
                score(input.industryLeaderScore()), "0.18",
                score(input.cashFlowScore()), "0.12",
                score(input.policyCatalystScore()), "0.12");
        StrategyAction action = actionFor(rankScore, dataConfidenceProxy(input), new BigDecimal("80"), new BigDecimal("72"), new BigDecimal("58"));
        return signal(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                input,
                action,
                rankScore,
                riskReward(score(input.valuationDiscountScore()), score(input.qualityScore())),
                List.of(
                        "估值修复分 " + plain(input.valuationDiscountScore()) + "，只作为估值上下文，不做单一 PE/PB 硬门槛。",
                        "质量分 " + plain(input.qualityScore()) + "，行业地位分 " + plain(input.industryLeaderScore()) + "。"
                ));
    }

    private StrategySignal qualityCompounder(LongTermStrategyInput input) {
        BigDecimal qualityCore = weighted(
                score(input.qualityScore()), "0.28",
                score(input.moatScore()), "0.24",
                score(input.profitabilityScore()), "0.20",
                score(input.cashFlowScore()), "0.18",
                score(input.industryLeaderScore()), "0.10");
        BigDecimal valuationContext = score(input.valuationDiscountScore()).multiply(new BigDecimal("0.08"));
        BigDecimal rankScore = qualityCore.multiply(new BigDecimal("0.92")).add(valuationContext).setScale(2, RoundingMode.HALF_UP);
        StrategyAction action = actionFor(rankScore, dataConfidenceProxy(input), new BigDecimal("84"), new BigDecimal("72"), new BigDecimal("62"));
        if (action == StrategyAction.ADD && score(input.valuationDiscountScore()).compareTo(new BigDecimal("30")) < 0) {
            action = StrategyAction.LIGHT_TRIAL;
        }
        return signal(
                StrategyCode.QUALITY_COMPOUNDER,
                "quality-compounder-v2.0.0",
                input,
                action,
                rankScore,
                riskReward(qualityCore, score(input.valuationDiscountScore()).max(new BigDecimal("15"))),
                List.of(
                        "质量、壁垒和现金流是主因子：质量分 " + plain(input.qualityScore())
                                + "，壁垒分 " + plain(input.moatScore()) + "，现金流分 " + plain(input.cashFlowScore()) + "。",
                        "估值偏贵时降低排序分，但不直接阻断优质公司研究。"
                ));
    }

    private StrategySignal cycleReversal(LongTermStrategyInput input) {
        BigDecimal rankScore = weighted(
                score(input.cyclePositionScore()), "0.34",
                score(input.cycleRecoveryScore()), "0.24",
                score(input.industryLeaderScore()), "0.18",
                score(input.qualityScore()), "0.14",
                score(input.policyCatalystScore()), "0.10");
        StrategyAction action = actionFor(rankScore, dataConfidenceProxy(input), new BigDecimal("84"), new BigDecimal("68"), new BigDecimal("58"));
        return signal(
                StrategyCode.CYCLE_REVERSAL,
                "cycle-reversal-v2.0.0",
                input,
                action,
                rankScore,
                riskReward(score(input.cyclePositionScore()), new BigDecimal("100").subtract(score(input.cycleRecoveryScore())).max(BigDecimal.TEN)),
                List.of(
                        "周期位置分 " + plain(input.cyclePositionScore())
                                + "，修复确认分 " + plain(input.cycleRecoveryScore()) + "。",
                        "周期反转优先允许小仓验证，右侧确认前不打满仓位。"
                ));
    }

    private StrategySignal signal(
            StrategyCode strategyCode,
            String strategyVersion,
            LongTermStrategyInput input,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal riskReward,
            List<String> evidence
    ) {
        BigDecimal dataConfidence = dataConfidence(input);
        return new StrategySignal(
                strategyCode,
                strategyVersion,
                input.symbol(),
                input.companyName(),
                input.decisionAt(),
                input.dataCutoffAt(),
                stageFor(action),
                action,
                positionLimitFor(action),
                entryConditionFor(action),
                invalidConditionFor(strategyCode),
                rankScore,
                dataConfidence,
                null,
                riskReward,
                evidence,
                List.of(),
                context(input, strategyCode),
                SourceQualityStatus.VERIFIED,
                replayPayload(input, strategyCode, rankScore, dataConfidence),
                SignalProvenance.RULE_ENGINE);
    }

    private StrategySignal blocked(
            StrategyCode strategyCode,
            String strategyVersion,
            LongTermStrategyInput input,
            List<String> reasons
    ) {
        return StrategySignalFactory.blocked(
                strategyCode,
                strategyVersion,
                input.symbol(),
                input.companyName(),
                input.decisionAt(),
                input.dataCutoffAt(),
                StrategyAction.RISK_BLOCKED,
                reasons,
                context(input, strategyCode),
                SourceQualityStatus.CONFLICT,
                replayPayload(input, strategyCode, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private StrategyAction actionFor(
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal addThreshold,
            BigDecimal trialThreshold,
            BigDecimal watchThreshold
    ) {
        if (dataConfidence.compareTo(new BigDecimal("50")) < 0) {
            return StrategyAction.WAIT;
        }
        if (rankScore.compareTo(addThreshold) >= 0) {
            return StrategyAction.ADD;
        }
        if (rankScore.compareTo(trialThreshold) >= 0) {
            return StrategyAction.LIGHT_TRIAL;
        }
        if (rankScore.compareTo(watchThreshold) >= 0) {
            return StrategyAction.NEXT_WATCH;
        }
        return StrategyAction.WAIT;
    }

    private CandidateStage stageFor(StrategyAction action) {
        return switch (action) {
            case ADD, LIGHT_TRIAL -> CandidateStage.QUALIFIED;
            case NEXT_WATCH, HOLD, WAIT_PULLBACK -> CandidateStage.WATCH;
            default -> CandidateStage.RESEARCH;
        };
    }

    private BigDecimal positionLimitFor(StrategyAction action) {
        return switch (action) {
            case ADD -> new BigDecimal("0.1000");
            case LIGHT_TRIAL -> new BigDecimal("0.0500");
            default -> BigDecimal.ZERO;
        };
    }

    private String entryConditionFor(StrategyAction action) {
        return switch (action) {
            case ADD -> "分批执行，单票首笔不超过 10%，不得一次性打满。";
            case LIGHT_TRIAL -> "只允许 5% 以内试仓，等待右侧或基本面二次确认后再追加。";
            case NEXT_WATCH -> "进入长线观察，等待估值、业绩或周期证据继续确认。";
            default -> "当前不触发买入，继续观察。";
        };
    }

    private String invalidConditionFor(StrategyCode strategyCode) {
        return switch (strategyCode) {
            case VALUE_REVERSION -> "估值修复逻辑被业绩恶化、资产质量下降或行业景气下行证伪。";
            case QUALITY_COMPOUNDER -> "护城河、ROE、现金流或行业地位出现持续恶化。";
            case CYCLE_REVERSAL -> "周期价格、库存、产能出清或盈利修复证据继续恶化。";
            default -> "核心策略证据失效。";
        };
    }

    private Map<String, String> context(LongTermStrategyInput input, StrategyCode strategyCode) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("strategyFamily", FAMILY);
        context.put("strategyCode", strategyCode.name());
        context.put("industry", text(input.industry()));
        context.put("valuationUsage", VALUATION_USAGE);
        context.put("riskFlagCount", Integer.toString(input.riskFlags().size()));
        return context;
    }

    private Map<String, Object> replayPayload(
            LongTermStrategyInput input,
            StrategyCode strategyCode,
            BigDecimal rankScore,
            BigDecimal dataConfidence
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategyFamily", FAMILY);
        payload.put("strategyCode", strategyCode.name());
        payload.put("valuationUsage", VALUATION_USAGE);
        payload.put("rankScoreBeforeValidation", rankScore);
        payload.put("dataConfidenceBeforeValidation", dataConfidence);
        payload.put("scores", Map.of(
                "valuationDiscountScore", score(input.valuationDiscountScore()),
                "qualityScore", score(input.qualityScore()),
                "moatScore", score(input.moatScore()),
                "profitabilityScore", score(input.profitabilityScore()),
                "cashFlowScore", score(input.cashFlowScore()),
                "cyclePositionScore", score(input.cyclePositionScore()),
                "cycleRecoveryScore", score(input.cycleRecoveryScore()),
                "industryLeaderScore", score(input.industryLeaderScore()),
                "policyCatalystScore", score(input.policyCatalystScore()),
                "liquidityScore", score(input.liquidityScore())
        ));
        payload.put("riskFlags", input.riskFlags());
        return payload;
    }

    private List<String> hardRiskReasons(List<String> riskFlags) {
        List<String> reasons = new ArrayList<>();
        for (String riskFlag : riskFlags) {
            if (riskFlag == null || riskFlag.isBlank()) {
                continue;
            }
            String normalized = riskFlag.trim().toUpperCase();
            if ("ST".equals(normalized) || normalized.contains("退市") || normalized.contains("FRAUD")) {
                reasons.add("HARD_RISK_FLAG:" + riskFlag.trim());
            }
        }
        return reasons;
    }

    private BigDecimal dataConfidence(LongTermStrategyInput input) {
        long present = List.of(
                input.valuationDiscountScore(),
                input.qualityScore(),
                input.moatScore(),
                input.profitabilityScore(),
                input.cashFlowScore(),
                input.cyclePositionScore(),
                input.cycleRecoveryScore(),
                input.industryLeaderScore(),
                input.policyCatalystScore(),
                input.liquidityScore()
        ).stream().filter(value -> value != null).count();
        BigDecimal completeness = BigDecimal.valueOf(present)
                .multiply(new BigDecimal("10"));
        BigDecimal riskPenalty = BigDecimal.valueOf(input.riskFlags().size()).multiply(new BigDecimal("5"));
        return clamp(completeness.subtract(riskPenalty));
    }

    private BigDecimal dataConfidenceProxy(LongTermStrategyInput input) {
        return dataConfidence(input);
    }

    private BigDecimal weighted(
            BigDecimal firstValue,
            String firstWeight,
            BigDecimal secondValue,
            String secondWeight,
            BigDecimal thirdValue,
            String thirdWeight,
            BigDecimal fourthValue,
            String fourthWeight,
            BigDecimal fifthValue,
            String fifthWeight
    ) {
        return firstValue.multiply(new BigDecimal(firstWeight))
                .add(secondValue.multiply(new BigDecimal(secondWeight)))
                .add(thirdValue.multiply(new BigDecimal(thirdWeight)))
                .add(fourthValue.multiply(new BigDecimal(fourthWeight)))
                .add(fifthValue.multiply(new BigDecimal(fifthWeight)))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal riskReward(BigDecimal reward, BigDecimal risk) {
        return reward.divide(risk.max(BigDecimal.ONE), 4, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal score(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return clamp(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100"));
    }

    private String plain(BigDecimal value) {
        return score(value).stripTrailingZeros().toPlainString();
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private void validate(LongTermStrategyInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (input.symbol() == null || input.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (input.companyName() == null || input.companyName().isBlank()) {
            throw new IllegalArgumentException("companyName must not be blank");
        }
        if (input.decisionAt() == null || input.dataCutoffAt() == null) {
            throw new IllegalArgumentException("decisionAt and dataCutoffAt must not be null");
        }
        if (input.dataCutoffAt().isAfter(input.decisionAt())) {
            throw new IllegalArgumentException("dataCutoffAt must not be after decisionAt");
        }
    }
}
