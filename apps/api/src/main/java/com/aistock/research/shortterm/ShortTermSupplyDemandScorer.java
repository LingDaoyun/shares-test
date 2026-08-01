package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.shortterm.chip.ChipActivationMode;
import com.aistock.research.shortterm.chip.ShortTermChipSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShortTermSupplyDemandScorer {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal BUY_WEIGHT = new BigDecimal("0.45");
    private static final BigDecimal PRESSURE_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal TECHNICAL_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal V3_TECHNICAL_WEIGHT = new BigDecimal("0.45");
    private static final BigDecimal V3_PRESSURE_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal V3_BUY_WEIGHT = new BigDecimal("0.10");
    private static final BigDecimal MAX_CHIP_CONTRIBUTION = new BigDecimal("25");

    public ShortTermSupplyDemandScore score(
            EastMoneyFundFlowSnapshot fundFlow,
            LocalDate quoteTradeDate,
            ShortTermTechnicalSnapshot technical,
            BigDecimal technicalRankingScore
    ) {
        return score(
                fundFlow,
                quoteTradeDate,
                technical,
                technicalRankingScore,
                null,
                ChipActivationMode.OFF
        );
    }

    public ShortTermSupplyDemandScore score(
            EastMoneyFundFlowSnapshot fundFlow,
            LocalDate quoteTradeDate,
            ShortTermTechnicalSnapshot technical,
            BigDecimal technicalRankingScore,
            ShortTermChipSnapshot chip,
            ChipActivationMode activationMode
    ) {
        List<String> dataGaps = new ArrayList<>();
        FlowScore flowScore = buyingPressure(fundFlow, quoteTradeDate, dataGaps);
        BigDecimal pressureRelief = overheadPressureRelief(technical, dataGaps);
        BigDecimal technicalScore = clamp(technicalRankingScore == null
                ? new BigDecimal("50")
                : technicalRankingScore);
        BigDecimal v2RankingScore = flowScore.available()
                ? flowScore.score().multiply(BUY_WEIGHT)
                .add(pressureRelief.multiply(PRESSURE_WEIGHT))
                .add(technicalScore.multiply(TECHNICAL_WEIGHT))
                : technicalScore;
        BigDecimal chipContribution = chip == null || chip.contributionScore() == null
                ? BigDecimal.ZERO
                : chip.contributionScore().max(BigDecimal.ZERO).min(MAX_CHIP_CONTRIBUTION);
        BigDecimal v3RankingScore = technicalScore.multiply(V3_TECHNICAL_WEIGHT)
                .add(chipContribution)
                .add(pressureRelief.multiply(V3_PRESSURE_WEIGHT));
        if (flowScore.available()) {
            v3RankingScore = v3RankingScore.add(flowScore.score().multiply(V3_BUY_WEIGHT));
        }
        ChipActivationMode safeMode = activationMode == null ? ChipActivationMode.SHADOW : activationMode;
        BigDecimal rankingScore = safeMode == ChipActivationMode.ACTIVE
                ? v3RankingScore
                : v2RankingScore;
        return new ShortTermSupplyDemandScore(
                scale(flowScore.mainRatio()),
                scale(flowScore.largeOrderRatio()),
                scale(flowScore.score()),
                scale(pressureRelief),
                scale(technicalScore),
                scale(clamp(v2RankingScore)),
                scale(chipContribution),
                scale(clamp(v3RankingScore)),
                scale(clamp(rankingScore)),
                dataGaps
        );
    }

    private FlowScore buyingPressure(
            EastMoneyFundFlowSnapshot fundFlow,
            LocalDate quoteTradeDate,
            List<String> dataGaps
    ) {
        if (fundFlow == null) {
            dataGaps.add("东方财富资金流缺失");
            return new FlowScore(null, null, new BigDecimal("35"), false);
        }
        if (quoteTradeDate == null || fundFlow.tradeDate() == null
                || !quoteTradeDate.equals(fundFlow.tradeDate())) {
            dataGaps.add("资金流交易日与行情交易日不一致");
            return new FlowScore(
                    fundFlow.mainNetInflowRatio(),
                    fundFlow.largeOrderNetInflowRatio(),
                    new BigDecimal("35"),
                    false
            );
        }
        BigDecimal mainRatio = fundFlow.mainNetInflowRatio();
        if (mainRatio == null) {
            dataGaps.add("主力净流入比例缺失");
            return new FlowScore(null, fundFlow.largeOrderNetInflowRatio(), new BigDecimal("35"), false);
        }
        BigDecimal mainFlowScore = clamp(new BigDecimal("50").add(mainRatio.multiply(new BigDecimal("5"))));
        BigDecimal consistencyScore = largeOrderConsistency(fundFlow);
        BigDecimal score = mainFlowScore.multiply(new BigDecimal("0.80"))
                .add(consistencyScore.multiply(new BigDecimal("0.20")));
        return new FlowScore(mainRatio, fundFlow.largeOrderNetInflowRatio(), clamp(score), true);
    }

    private BigDecimal largeOrderConsistency(EastMoneyFundFlowSnapshot fundFlow) {
        BigDecimal superLarge = fundFlow.superLargeNetInflowRatio();
        BigDecimal large = fundFlow.largeNetInflowRatio();
        if (superLarge == null && large == null) {
            return new BigDecimal("40");
        }
        if (positive(superLarge) && positive(large)) {
            return HUNDRED;
        }
        BigDecimal combined = zero(superLarge).add(zero(large));
        return combined.compareTo(BigDecimal.ZERO) > 0
                ? new BigDecimal("75")
                : new BigDecimal("35");
    }

    private BigDecimal overheadPressureRelief(
            ShortTermTechnicalSnapshot technical,
            List<String> dataGaps
    ) {
        ShortTermMomentumQuality quality = technical == null ? null : technical.momentumQuality();
        BigDecimal upperShadow = quality == null ? null : firstNonNull(
                quality.bullishUpperShadowMedian3Percent(),
                quality.latestUpperShadowPercent()
        );
        BigDecimal upperShadowRelief;
        if (upperShadow == null) {
            dataGaps.add("上影线数据缺失");
            upperShadowRelief = new BigDecimal("45");
        } else {
            upperShadowRelief = clamp(new BigDecimal("100")
                    .subtract(upperShadow.multiply(new BigDecimal("1.5"))))
                    .max(new BigDecimal("20"));
        }

        BigDecimal closeLocation = quality == null ? null : quality.closeLocationPercent();
        BigDecimal closeLocationRelief;
        if (closeLocation == null) {
            dataGaps.add("收盘位置数据缺失");
            closeLocationRelief = new BigDecimal("45");
        } else {
            closeLocationRelief = clamp(closeLocation).max(new BigDecimal("20"));
        }

        BigDecimal breakout = technical == null ? null : technical.breakoutFromPreviousHigh20Percent();
        BigDecimal resistanceRelief = resistanceClearance(breakout);
        if (breakout == null) {
            dataGaps.add("前20日压力位数据缺失");
        }

        return clamp(upperShadowRelief.multiply(new BigDecimal("0.40"))
                .add(closeLocationRelief.multiply(new BigDecimal("0.35")))
                .add(resistanceRelief.multiply(new BigDecimal("0.25"))));
    }

    private BigDecimal resistanceClearance(BigDecimal breakout) {
        if (breakout == null) {
            return new BigDecimal("45");
        }
        if (breakout.compareTo(BigDecimal.ZERO) >= 0
                && breakout.compareTo(new BigDecimal("4")) <= 0) {
            return HUNDRED;
        }
        if (breakout.compareTo(new BigDecimal("-2")) < 0) {
            return new BigDecimal("70");
        }
        if (breakout.compareTo(BigDecimal.ZERO) < 0) {
            return new BigDecimal("45");
        }
        if (breakout.compareTo(new BigDecimal("8")) <= 0) {
            return new BigDecimal("70");
        }
        return new BigDecimal("35");
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.max(BigDecimal.ZERO).min(HUNDRED);
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first == null ? second : first;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record FlowScore(
            BigDecimal mainRatio,
            BigDecimal largeOrderRatio,
            BigDecimal score,
            boolean available
    ) {
    }
}
