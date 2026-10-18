package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ShortTermTradePlan(
        String strategyLabel,
        String status,
        List<String> blockedReasons,
        String entryWindow,
        Instant validUntil,
        BigDecimal referenceEntryPrice,
        BigDecimal entryLow,
        BigDecimal entryHigh,
        BigDecimal maxPositionRatio,
        BigDecimal maxT2PositionRatio,
        BigDecimal firstTargetPercent,
        BigDecimal firstTargetPrice,
        BigDecimal firstReductionRatio,
        BigDecimal secondTargetPercent,
        BigDecimal secondTargetPrice,
        BigDecimal hardStopPercent,
        BigDecimal hardStopPrice,
        BigDecimal trailingDrawdownPercent,
        String trailingStopRule,
        LocalDate normalExitDate,
        LocalTime normalExitTime,
        LocalDate absoluteExitDate,
        LocalTime absoluteExitTime,
        List<String> t2ExtensionConditions,
        List<ShortTermOpenScenario> openScenarios,
        List<String> analysisBasis,
        List<String> riskWarnings
) {
    public ShortTermTradePlan {
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
    }

    ShortTermTradePlan withMarketConstraint(ShortTermMarketRegime regime, Instant completedAt, boolean entryWindow) {
        java.util.ArrayList<String> blocked = new java.util.ArrayList<>(blockedReasons);
        java.util.ArrayList<String> basis = new java.util.ArrayList<>(analysisBasis == null ? List.of() : analysisBasis);
        BigDecimal position = maxPositionRatio;
        if (regime == null || regime.blocksExecution()) {
            blocked.add("大盘环境暂停执行或证据不足，个股信号不能覆盖市场闸门");
            position = BigDecimal.ZERO;
        } else {
            basis.add("大盘约束：" + regime.explanation());
            if (regime.lightTrialOnly()) position = position == null ? new BigDecimal("0.20") : position.min(new BigDecimal("0.20"));
        }
        if ("ACTIONABLE".equals(status) && (!entryWindow || completedAt == null || validUntil == null || !completedAt.isBefore(validUntil))) {
            blocked.add("结果输出时已不在有效入场窗口，原计划已失效，请重新扫描");
        }
        return new ShortTermTradePlan(strategyLabel, blocked.isEmpty() ? status : "BLOCKED", blocked.stream().distinct().toList(),
                entryWindow(), validUntil, referenceEntryPrice, entryLow, entryHigh, position, maxT2PositionRatio,
                firstTargetPercent, firstTargetPrice, firstReductionRatio, secondTargetPercent, secondTargetPrice,
                hardStopPercent, hardStopPrice, trailingDrawdownPercent, trailingStopRule,
                normalExitDate, normalExitTime, absoluteExitDate, absoluteExitTime,
                t2ExtensionConditions, openScenarios, List.copyOf(basis), riskWarnings);
    }

    ShortTermTradePlan withResearchInvalidation(BigDecimal invalidation) {
        if (invalidation == null || invalidation.signum() <= 0 || referenceEntryPrice == null
                || invalidation.compareTo(referenceEntryPrice) >= 0) {
            return withResearchRiskAssessment(new ShortTermResearchRiskGate.Assessment(false, null,
                    List.of("形态失效价缺失或不低于参考入场价，研究计划不可执行")));
        }
        BigDecimal stopPercent = referenceEntryPrice.subtract(invalidation).multiply(new BigDecimal("100"))
                .divide(referenceEntryPrice, 2, java.math.RoundingMode.HALF_UP);
        java.util.ArrayList<String> basis = new java.util.ArrayList<>(analysisBasis == null ? List.of() : analysisBasis);
        basis.add("研究计划以形态失效位 " + invalidation + " 元作为止损参考；入场允许上浮0.20%，重新计算分批目标风险收益比。实际成交与跳空损失可能偏离计划。");
        return new ShortTermTradePlan(strategyLabel, status, blockedReasons, entryWindow, validUntil,
                referenceEntryPrice, entryLow, entryHigh, maxPositionRatio, maxT2PositionRatio,
                firstTargetPercent, firstTargetPrice, firstReductionRatio, secondTargetPercent, secondTargetPrice,
                stopPercent, invalidation, trailingDrawdownPercent, trailingStopRule,
                normalExitDate, normalExitTime, absoluteExitDate, absoluteExitTime,
                t2ExtensionConditions, openScenarios, List.copyOf(basis), riskWarnings);
    }

    ShortTermTradePlan withResearchRiskAssessment(ShortTermResearchRiskGate.Assessment assessment) {
        java.util.ArrayList<String> blocked = new java.util.ArrayList<>(blockedReasons);
        if (!assessment.allowsExecution()) blocked.addAll(assessment.reasons());
        java.util.ArrayList<String> basis = new java.util.ArrayList<>(analysisBasis == null ? List.of() : analysisBasis);
        basis.addAll(assessment.reasons());
        return new ShortTermTradePlan(strategyLabel,
                assessment.allowsExecution() ? status : "BLOCKED", List.copyOf(blocked),
                entryWindow, validUntil, referenceEntryPrice, entryLow, entryHigh, maxPositionRatio, maxT2PositionRatio,
                firstTargetPercent, firstTargetPrice, firstReductionRatio, secondTargetPercent, secondTargetPrice,
                hardStopPercent, hardStopPrice, trailingDrawdownPercent, trailingStopRule,
                normalExitDate, normalExitTime, absoluteExitDate, absoluteExitTime,
                t2ExtensionConditions, openScenarios, List.copyOf(basis), riskWarnings);
    }
}
