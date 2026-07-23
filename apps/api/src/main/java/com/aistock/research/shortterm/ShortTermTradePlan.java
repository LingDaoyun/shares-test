package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ShortTermTradePlan(
        String strategyLabel,
        String status,
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
}
