package com.aistock.research.shortterm;

import com.aistock.research.trading.TradingClockService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ShortTermTradePlanService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal MINIMUM_PRICE_TICK = new BigDecimal("0.01");
    private static final BigDecimal FIRST_REDUCTION_RATIO = new BigDecimal("0.50");
    private static final String STRATEGY_LABEL = "隔夜超短波段";
    private static final List<String> BLOCKED_RISK_WARNINGS = List.of(
            "当前计划未通过执行闸门，仅保留风险依据，不构成可执行交易建议。");

    private final TradingClockService tradingClockService;

    public ShortTermTradePlanService(TradingClockService tradingClockService) {
        this.tradingClockService = Objects.requireNonNull(tradingClockService, "tradingClockService");
    }

    public ShortTermTradePlan create(
            LocalDate tradeDate,
            BigDecimal referencePrice,
            BigDecimal entryLow,
            BigDecimal entryHigh,
            ShortTermTechnicalSnapshot technical,
            OvernightRuleSet rules
    ) {
        Objects.requireNonNull(rules, "rules");
        BigDecimal atr14Percent = technical == null ? null : technical.atr14Percent();
        BigDecimal recentSupportPrice = technical == null ? null : technical.recentSupportPrice();
        LocalDate normalExitDate = tradeDate == null ? null : tradingClockService.nextTradingDay(tradeDate);
        LocalDate absoluteExitDate = tradeDate == null
                ? null
                : tradingClockService.tradingDayAfter(tradeDate, 2);
        Instant validUntil = tradeDate == null
                ? null
                : ZonedDateTime.of(
                        tradeDate,
                        TradingClockService.SHORT_TERM_ENTRY_EXCLUSIVE_END,
                        TradingClockService.CHINA_MARKET_ZONE
                ).toInstant();

        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return plan(
                    "BLOCKED",
                    rules,
                    validUntil,
                    null,
                    entryLow,
                    entryHigh,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    normalExitDate,
                    absoluteExitDate,
                    List.of("缺少可验证的参考入场价，未生成目标价和止损价")
            );
        }

        if (atr14Percent == null || atr14Percent.compareTo(BigDecimal.ZERO) <= 0) {
            return plan(
                    "BLOCKED",
                    rules,
                    validUntil,
                    referencePrice,
                    entryLow,
                    entryHigh,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    normalExitDate,
                    absoluteExitDate,
                    List.of("缺少完成 K 线计算的 ATR14，未生成目标价和止损价")
            );
        }

        BigDecimal volatilityPercent = atr14Percent;
        BigDecimal firstTargetPercent = clamp(
                volatilityPercent.multiply(new BigDecimal("0.90")),
                rules.firstTargetFloor(),
                rules.firstTargetCap()
        );
        BigDecimal secondTargetPercent = clamp(
                volatilityPercent.multiply(new BigDecimal("1.60")),
                rules.secondTargetFloor(),
                rules.secondTargetCap()
        );
        BigDecimal stopPercent = clamp(
                volatilityPercent.multiply(new BigDecimal("1.10")),
                rules.stopFloor(),
                rules.stopCap()
        );
        BigDecimal volatilityStop = referencePrice.multiply(
                ONE.subtract(stopPercent.movePointLeft(2))
        );
        BigDecimal roundedReferencePrice = money(referencePrice);
        BigDecimal roundedVolatilityStop = money(volatilityStop);
        BigDecimal maximumValidStop = roundedReferencePrice.subtract(MINIMUM_PRICE_TICK);
        if (roundedVolatilityStop.compareTo(roundedReferencePrice) >= 0) {
            roundedVolatilityStop = maximumValidStop;
        }
        BigDecimal roundedSupportPrice = money(recentSupportPrice);
        boolean usableSupport = roundedSupportPrice != null
                && roundedSupportPrice.compareTo(BigDecimal.ZERO) > 0
                && roundedSupportPrice.compareTo(roundedReferencePrice) < 0;
        BigDecimal hardStopPrice = usableSupport
                ? roundedSupportPrice.max(roundedVolatilityStop)
                : roundedVolatilityStop;
        if (hardStopPrice.compareTo(roundedReferencePrice) >= 0) {
            hardStopPrice = maximumValidStop;
        }
        BigDecimal displayedStopPercent = roundedReferencePrice
                .subtract(hardStopPrice)
                .multiply(new BigDecimal("100"))
                .divide(roundedReferencePrice, 2, RoundingMode.HALF_UP);

        List<String> analysisBasis = new ArrayList<>();
        analysisBasis.add("参考入场价 " + roundedReferencePrice + " 元");
        analysisBasis.add("ATR14 波动率 " + percent(volatilityPercent) + "%，目标价和止损价只由确定性公式生成");
        if (usableSupport) {
            analysisBasis.add("近期支撑价 " + roundedSupportPrice + " 元");
        } else if (roundedSupportPrice != null) {
            analysisBasis.add("近期支撑价 " + roundedSupportPrice + " 元不为正或不低于参考入场价，未用于硬止损");
        }

        return plan(
                "ACTIONABLE",
                rules,
                validUntil,
                referencePrice,
                entryLow,
                entryHigh,
                firstTargetPercent,
                priceAbove(referencePrice, firstTargetPercent),
                secondTargetPercent,
                priceAbove(referencePrice, secondTargetPercent),
                displayedStopPercent,
                hardStopPrice,
                normalExitDate,
                absoluteExitDate,
                analysisBasis
        );
    }

    public ShortTermTradePlan blocked(
            LocalDate tradeDate,
            BigDecimal referencePrice,
            BigDecimal entryLow,
            BigDecimal entryHigh,
            ShortTermTechnicalSnapshot technical,
            OvernightRuleSet rules,
            List<String> blockedReasons
    ) {
        ShortTermTradePlan calculated = create(
                tradeDate,
                referencePrice,
                entryLow,
                entryHigh,
                technical,
                rules
        );
        List<String> blockedBasis = new ArrayList<>(calculated.blockedReasons());
        if (blockedReasons != null) {
            blockedReasons.stream()
                    .filter(reason -> reason != null && !reason.isBlank())
                    .forEach(blockedBasis::add);
        }
        return new ShortTermTradePlan(
                calculated.strategyLabel(),
                "BLOCKED",
                List.copyOf(blockedBasis),
                calculated.entryWindow(),
                calculated.validUntil(),
                calculated.referenceEntryPrice(),
                calculated.entryLow(),
                calculated.entryHigh(),
                calculated.maxPositionRatio(),
                calculated.maxT2PositionRatio(),
                calculated.firstTargetPercent(),
                calculated.firstTargetPrice(),
                calculated.firstReductionRatio(),
                calculated.secondTargetPercent(),
                calculated.secondTargetPrice(),
                calculated.hardStopPercent(),
                calculated.hardStopPrice(),
                calculated.trailingDrawdownPercent(),
                calculated.trailingStopRule(),
                calculated.normalExitDate(),
                calculated.normalExitTime(),
                calculated.absoluteExitDate(),
                calculated.absoluteExitTime(),
                calculated.t2ExtensionConditions(),
                calculated.openScenarios(),
                calculated.analysisBasis(),
                BLOCKED_RISK_WARNINGS
        );
    }

    private ShortTermTradePlan plan(
            String status,
            OvernightRuleSet rules,
            Instant validUntil,
            BigDecimal referencePrice,
            BigDecimal entryLow,
            BigDecimal entryHigh,
            BigDecimal firstTargetPercent,
            BigDecimal firstTargetPrice,
            BigDecimal secondTargetPercent,
            BigDecimal secondTargetPrice,
            BigDecimal stopPercent,
            BigDecimal hardStopPrice,
            LocalDate normalExitDate,
            LocalDate absoluteExitDate,
            List<String> analysisBasis
    ) {
        return new ShortTermTradePlan(
                STRATEGY_LABEL,
                status,
                "BLOCKED".equals(status) ? List.copyOf(analysisBasis) : List.of(),
                time(rules.entryStart()) + "-" + time(rules.entryEnd()),
                validUntil,
                money(referencePrice),
                money(entryLow),
                money(entryHigh),
                rules.maxPositionRatio(),
                rules.maxT2PositionRatio(),
                percentValue(firstTargetPercent),
                money(firstTargetPrice),
                FIRST_REDUCTION_RATIO,
                percentValue(secondTargetPercent),
                money(secondTargetPrice),
                percentValue(stopPercent),
                money(hardStopPrice),
                rules.trailingDrawdownPercent(),
                "首次止盈后启动移动止损；剩余仓位从目标后最高价回撤 "
                        + percent(rules.trailingDrawdownPercent()) + "% 时退出",
                normalExitDate,
                rules.normalExitTime(),
                absoluteExitDate,
                rules.normalExitTime(),
                List.of(
                        "T+1 收益为正",
                        "T+1 收盘价高于 MA5",
                        "T+1 收盘价高于信号日收盘价",
                        "行情新鲜度和风险闸门均未失败"
                ),
                openScenarios(),
                List.copyOf(analysisBasis),
                "BLOCKED".equals(status)
                        ? BLOCKED_RISK_WARNINGS
                        : actionableRiskWarnings(rules)
        );
    }

    private List<String> actionableRiskWarnings(OvernightRuleSet rules) {
        return List.of(
                "普通 A 股新买仓位遵循 T+1：买入当日无法卖出，盘中急跌也只能等次一交易日处理。",
                "最大仓位比例是相对于短线资金分配，不是总账户资产。",
                "T+2 只允许保留不超过计划仓位的 "
                        + percent(rules.maxT2PositionRatio().movePointRight(2)) + "%，"
                        + "并必须在 " + time(rules.normalExitTime()) + " 前退出。"
        );
    }

    private List<ShortTermOpenScenario> openScenarios() {
        return List.of(
                new ShortTermOpenScenario(
                        "HIGH_OPEN",
                        "明显高开",
                        "T+1 开盘涨幅达到第一止盈目标附近或以上",
                        "不追价；已有仓位优先兑现至少一半，剩余仓位按移动止损处理",
                        List.of("开盘后跌回参考入场价", "放量冲高回落", "行情或风险闸门失败")
                ),
                new ShortTermOpenScenario(
                        "FLAT_OPEN",
                        "平开或小幅波动",
                        "T+1 开盘围绕参考入场价窄幅波动",
                        "观察 MA5、前收盘价和量价承接；未达到延长条件则在 T+1 14:50 前退出",
                        List.of("跌破硬止损价", "跌破 MA5 且无法收回", "行情或风险闸门失败")
                ),
                new ShortTermOpenScenario(
                        "LOW_OPEN",
                        "明显低开",
                        "T+1 开盘低于参考入场价且接近或跌破硬止损价",
                        "承担 T+1 强制隔夜风险；新仓买入当日无法卖出，只能在次一交易日开盘后按硬止损纪律处理",
                        List.of("一字跌停无法成交时只记录延迟退出风险", "打开跌停后优先执行风险退出", "不得补仓摊低成本")
                )
        );
    }

    private BigDecimal priceAbove(BigDecimal referencePrice, BigDecimal targetPercent) {
        return money(referencePrice.multiply(ONE.add(targetPercent.movePointLeft(2))));
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal floor, BigDecimal cap) {
        return value.max(floor).min(cap);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentValue(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String percent(BigDecimal value) {
        return value == null ? "--" : value.stripTrailingZeros().toPlainString();
    }

    private String time(LocalTime value) {
        return value == null ? "--:--" : value.withSecond(0).withNano(0).toString();
    }
}
