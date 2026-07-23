package com.aistock.research.v2.strategy;

import org.springframework.stereotype.Service;

import com.aistock.research.shortterm.ShortTermGoldenCrossSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShortRightSideStrategyService {

    private static final String VERSION = "short-right-side-v2.1.1";

    public StrategySignal evaluate(ShortRightSideStrategyInput input) {
        validate(input);
        List<String> riskReasons = riskReasons(input);
        if (!riskReasons.isEmpty()) {
            return StrategySignalFactory.blocked(
                    StrategyCode.SHORT_RIGHT_SIDE,
                    VERSION,
                    input.symbol(),
                    input.companyName(),
                    input.decisionAt(),
                    input.dataCutoffAt(),
                    StrategyAction.RISK_BLOCKED,
                    riskReasons,
                    context(input),
                    SourceQualityStatus.CONFLICT,
                    replayPayload(input, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        BigDecimal rankScore = rankScore(input);
        BigDecimal dataConfidence = dataConfidence(input);
        StrategyAction action = action(input, rankScore, dataConfidence);
        return new StrategySignal(
                StrategyCode.SHORT_RIGHT_SIDE,
                VERSION,
                input.symbol(),
                input.companyName(),
                input.decisionAt(),
                input.dataCutoffAt(),
                stage(action),
                action,
                positionLimit(action),
                entryCondition(action),
                invalidCondition(input),
                rankScore,
                dataConfidence,
                null,
                riskReward(input),
                evidence(input, action),
                List.of(),
                context(input),
                SourceQualityStatus.VERIFIED,
                replayPayload(input, rankScore, dataConfidence),
                SignalProvenance.RULE_ENGINE);
    }

    private StrategyAction action(
            ShortRightSideStrategyInput input,
            BigDecimal rankScore,
            BigDecimal dataConfidence
    ) {
        if (dataConfidence.compareTo(new BigDecimal("60")) < 0) {
            return StrategyAction.WAIT;
        }
        if (!"TAIL_ENTRY_1445_1456".equals(checkpoint(input))) {
            return StrategyAction.WAIT;
        }
        if (!legacyExecutionAllowed(input)) {
            return StrategyAction.WAIT;
        }
        if (!confirmedRecentGoldenCross(input)) {
            return StrategyAction.WAIT;
        }
        boolean rightSideValid = score(input.rightSideStructureScore()).compareTo(new BigDecimal("70")) >= 0;
        boolean fundamentalValid = score(input.fundamentalFloorScore()).compareTo(new BigDecimal("60")) >= 0;
        boolean liquidityValid = score(input.liquidityScore()).compareTo(new BigDecimal("60")) >= 0;
        boolean crowded = score(input.crowdingRiskScore()).compareTo(new BigDecimal("75")) >= 0;
        boolean absorptionValid = score(input.supplyAbsorptionScore()).compareTo(new BigDecimal("70")) >= 0;
        if (!rightSideValid || !fundamentalValid || !liquidityValid || crowded) {
            return StrategyAction.WAIT;
        }
        if (rankScore.compareTo(new BigDecimal("75")) >= 0 && absorptionValid) {
            return "ADD".equals(input.legacyAdviceAction()) ? StrategyAction.ADD : StrategyAction.LIGHT_TRIAL;
        }
        if (rankScore.compareTo(new BigDecimal("68")) >= 0) {
            return StrategyAction.LIGHT_TRIAL;
        }
        return StrategyAction.WAIT;
    }

    private BigDecimal rankScore(ShortRightSideStrategyInput input) {
        BigDecimal crowdingPenalty = score(input.crowdingRiskScore()).multiply(new BigDecimal("0.18"));
        return score(input.marketHotScore()).multiply(new BigDecimal("0.16"))
                .add(score(input.rightSideStructureScore()).multiply(new BigDecimal("0.24")))
                .add(score(input.supplyAbsorptionScore()).multiply(new BigDecimal("0.16")))
                .add(score(input.volumeBreakoutScore()).multiply(new BigDecimal("0.10")))
                .add(score(input.shrinkRiseScore()).multiply(new BigDecimal("0.10")))
                .add(score(input.fundamentalFloorScore()).multiply(new BigDecimal("0.08")))
                .add(score(input.liquidityScore()).multiply(new BigDecimal("0.06")))
                .add(goldenCrossScore(input).multiply(new BigDecimal("0.10")))
                .subtract(crowdingPenalty)
                .max(BigDecimal.ZERO)
                .min(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> evidence(ShortRightSideStrategyInput input, StrategyAction action) {
        String shrinkContext = action == StrategyAction.ADD || action == StrategyAction.LIGHT_TRIAL
                ? "缩量承接与右侧结构同向，说明筹码惜售更可能是建设性信号。"
                : "缩量上涨不能单独触发买入，必须同时满足右侧结构、基本面底线和拥挤风险约束。";
        return List.of(
                shrinkContext,
                checkpointEvidence(input),
                "右侧结构分 " + plain(input.rightSideStructureScore())
                        + "，缩量承接分 " + plain(input.supplyAbsorptionScore())
                        + "，拥挤风险分 " + plain(input.crowdingRiskScore()) + "。",
                "热门方向分 " + plain(input.marketHotScore())
                        + "，基本面底线分 " + plain(input.fundamentalFloorScore())
                        + "，流动性分 " + plain(input.liquidityScore()) + "。",
                legacyGateEvidence(input),
                goldenCrossEvidence(input, action)
        );
    }

    private List<String> riskReasons(ShortRightSideStrategyInput input) {
        return input.riskFlags().stream()
                .filter(flag -> flag != null && !flag.isBlank())
                .map(String::trim)
                .filter(flag -> {
                    String normalized = flag.toUpperCase();
                    return normalized.equals("LOW_LIQUIDITY")
                            || normalized.equals("ST")
                            || normalized.contains("退市")
                            || normalized.contains("重大利空");
                })
                .map(flag -> "SHORT_RISK_FLAG:" + flag)
                .distinct()
                .toList();
    }

    private CandidateStage stage(StrategyAction action) {
        return switch (action) {
            case ADD, LIGHT_TRIAL -> CandidateStage.QUALIFIED;
            case WAIT_PULLBACK, NEXT_WATCH -> CandidateStage.WATCH;
            default -> CandidateStage.RESEARCH;
        };
    }

    private BigDecimal positionLimit(StrategyAction action) {
        return switch (action) {
            case ADD -> new BigDecimal("0.0800");
            case LIGHT_TRIAL -> new BigDecimal("0.0300");
            default -> BigDecimal.ZERO;
        };
    }

    private String entryCondition(StrategyAction action) {
        return switch (action) {
            case ADD -> "只允许 14:45-14:56 尾盘可成交窗口内分批买入，单票短线仓位不超过 8%。";
            case LIGHT_TRIAL -> "只允许 3% 以内试错，次日不能确认承接则退出观察。";
            default -> "不满足右侧早期确认，不开新仓。";
        };
    }

    private String invalidCondition(ShortRightSideStrategyInput input) {
        return "跌破 20 日线、放量下杀、热门方向退潮或尾盘入场后承接失败。";
    }

    private Map<String, String> context(ShortRightSideStrategyInput input) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("strategyFamily", "SHORT_TERM");
        context.put("tradingCheckpoint", checkpoint(input));
        context.put("hotDirection", text(input.hotDirection()));
        context.put("supplyAbsorptionUsage", "CONTEXTUAL_CONFIRMATION");
        context.put("riskModel", "ACTIVE");
        context.put("goldenCrossState", input.goldenCrossState());
        context.put("goldenCrossTradingDays", input.goldenCrossTradingDays() == null
                ? "UNKNOWN" : input.goldenCrossTradingDays().toString());
        context.put("goldenCrossPriorityTier", input.goldenCrossPriorityTier().toString());
        context.put("goldenCrossRuleVersion", ShortTermGoldenCrossSnapshot.RULE_VERSION);
        context.put("legacyAttestationVerified", Boolean.toString(input.legacyAttestationVerified()));
        context.put("legacyCandidateAction", input.legacyCandidateAction());
        context.put("legacyAdviceAction", input.legacyAdviceAction());
        context.put("tailSignalStatus", input.tailSignalStatus());
        context.put("evidenceAllowsBuy", Boolean.toString(input.evidenceAllowsBuy()));
        return context;
    }

    private Map<String, Object> replayPayload(
            ShortRightSideStrategyInput input,
            BigDecimal rankScore,
            BigDecimal dataConfidence
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategyFamily", "SHORT_TERM");
        payload.put("tradingCheckpoint", checkpoint(input));
        payload.put("rankScoreBeforeValidation", rankScore);
        payload.put("dataConfidenceBeforeValidation", dataConfidence);
        payload.put("scores", Map.of(
                "marketHotScore", score(input.marketHotScore()),
                "rightSideStructureScore", score(input.rightSideStructureScore()),
                "supplyAbsorptionScore", score(input.supplyAbsorptionScore()),
                "volumeBreakoutScore", score(input.volumeBreakoutScore()),
                "shrinkRiseScore", score(input.shrinkRiseScore()),
                "fundamentalFloorScore", score(input.fundamentalFloorScore()),
                "liquidityScore", score(input.liquidityScore()),
                "crowdingRiskScore", score(input.crowdingRiskScore())
        ));
        payload.put("goldenCross", Map.of(
                "state", input.goldenCrossState(),
                "tradingDays", input.goldenCrossTradingDays() == null ? -1 : input.goldenCrossTradingDays(),
                "priorityTier", input.goldenCrossPriorityTier(),
                "ruleVersion", ShortTermGoldenCrossSnapshot.RULE_VERSION
        ));
        payload.put("legacyExecutionGate", Map.of(
                "attestationVerified", input.legacyAttestationVerified(),
                "candidateAction", input.legacyCandidateAction(),
                "adviceAction", input.legacyAdviceAction(),
                "tailSignalStatus", input.tailSignalStatus(),
                "evidenceAllowsBuy", input.evidenceAllowsBuy()
        ));
        payload.put("riskFlags", input.riskFlags());
        return payload;
    }

    private boolean legacyExecutionAllowed(ShortRightSideStrategyInput input) {
        return input.legacyAttestationVerified()
                && "RIGHT_EARLY_ADD".equals(input.legacyCandidateAction())
                && ("ADD".equals(input.legacyAdviceAction()) || "LIGHT_TRIAL".equals(input.legacyAdviceAction()))
                && ("CONFIRMED".equals(input.tailSignalStatus()) || "WATCH".equals(input.tailSignalStatus()))
                && input.evidenceAllowsBuy();
    }

    private String legacyGateEvidence(ShortRightSideStrategyInput input) {
        if (legacyExecutionAllowed(input)) {
            return "主短线策略凭证已核验，日线动作、尾盘状态和证据完整度均允许进入 V2 复核。";
        }
        return "主短线策略执行闸门未通过：凭证=" + input.legacyAttestationVerified()
                + "，日线=" + input.legacyCandidateAction()
                + "，建议=" + input.legacyAdviceAction()
                + "，尾盘=" + input.tailSignalStatus()
                + "，证据可买=" + input.evidenceAllowsBuy() + "。";
    }

    private boolean confirmedRecentGoldenCross(ShortRightSideStrategyInput input) {
        return "CONFIRMED".equals(input.goldenCrossState())
                && input.goldenCrossTradingDays() != null
                && input.goldenCrossTradingDays() >= 0
                && input.goldenCrossTradingDays() <= 3
                && input.goldenCrossPriorityTier() >= 3;
    }

    private BigDecimal goldenCrossScore(ShortRightSideStrategyInput input) {
        return switch (input.goldenCrossPriorityTier()) {
            case 3 -> new BigDecimal("100");
            case 2 -> new BigDecimal("70");
            case 1 -> new BigDecimal("45");
            default -> BigDecimal.ZERO;
        };
    }

    private String goldenCrossEvidence(ShortRightSideStrategyInput input, StrategyAction action) {
        if (confirmedRecentGoldenCross(input)) {
            return "均线金叉已确认：第 " + input.goldenCrossTradingDays()
                    + " 个完成交易日，优先级 " + input.goldenCrossPriorityTier()
                    + "，满足短线可执行门槛。";
        }
        return "均线金叉状态 " + input.goldenCrossState()
                + "（完成后 " + (input.goldenCrossTradingDays() == null ? "未知" : input.goldenCrossTradingDays())
                + " 日，优先级 " + input.goldenCrossPriorityTier()
                + "）未满足近期确认门槛；临界、形成中、过期或缺失金叉均不能触发 "
                + action + " 之外的可执行建仓。";
    }

    private String checkpointEvidence(ShortRightSideStrategyInput input) {
        if ("TAIL_ENTRY_1445_1456".equals(checkpoint(input))) {
            return "服务端交易时钟已进入 14:45-14:56 普通股票可成交决策窗口。";
        }
        return "服务端交易时钟不在 14:45-14:56 可成交决策窗口，当前不能发布买入动作。";
    }

    private BigDecimal dataConfidence(ShortRightSideStrategyInput input) {
        long present = List.of(
                input.marketHotScore(),
                input.rightSideStructureScore(),
                input.supplyAbsorptionScore(),
                input.volumeBreakoutScore(),
                input.shrinkRiseScore(),
                input.fundamentalFloorScore(),
                input.liquidityScore(),
                input.crowdingRiskScore()
        ).stream().filter(value -> value != null).count();
        return BigDecimal.valueOf(present)
                .multiply(new BigDecimal("12.50"))
                .subtract(BigDecimal.valueOf(input.riskFlags().size()).multiply(new BigDecimal("6")))
                .max(BigDecimal.ZERO)
                .min(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal riskReward(ShortRightSideStrategyInput input) {
        BigDecimal reward = score(input.rightSideStructureScore()).add(score(input.supplyAbsorptionScore()))
                .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
        BigDecimal risk = score(input.crowdingRiskScore()).max(BigDecimal.TEN);
        return reward.divide(risk, 4, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal score(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private String plain(BigDecimal value) {
        return score(value).stripTrailingZeros().toPlainString();
    }

    private String checkpoint(ShortRightSideStrategyInput input) {
        return input.tradingCheckpoint() == null || input.tradingCheckpoint().isBlank()
                ? "UNKNOWN"
                : input.tradingCheckpoint();
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private void validate(ShortRightSideStrategyInput input) {
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
