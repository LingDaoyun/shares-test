package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.shortterm.chip.ChipActivationMode;
import com.aistock.research.shortterm.chip.ChipCalculationMode;
import com.aistock.research.shortterm.chip.ChipDataQuality;
import com.aistock.research.shortterm.chip.ChipOverheadPressure;
import com.aistock.research.shortterm.chip.ChipVerificationStatus;
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
    private static final BigDecimal MAX_CHIP_CONTRIBUTION = new BigDecimal("25");
    private static final BigDecimal V3_TECHNICAL_WEIGHT = new BigDecimal("0.45");
    private static final BigDecimal V3_PRESSURE_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal V3_BUY_WEIGHT = new BigDecimal("0.10");

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
        return score(fundFlow, quoteTradeDate, technical, technicalRankingScore,
                chip, activationMode, new BigDecimal("10"));
    }

    public ShortTermSupplyDemandScore score(
            EastMoneyFundFlowSnapshot fundFlow,
            LocalDate quoteTradeDate,
            ShortTermTechnicalSnapshot technical,
            BigDecimal technicalRankingScore,
            ShortTermChipSnapshot chip,
            ChipActivationMode activationMode,
            BigDecimal nearOverheadMaxPenalty
    ) {
        return score(fundFlow, quoteTradeDate, technical, technicalRankingScore,
                chip, activationMode, nearOverheadMaxPenalty, BigDecimal.ZERO);
    }

    public ShortTermSupplyDemandScore score(
            EastMoneyFundFlowSnapshot fundFlow,
            LocalDate quoteTradeDate,
            ShortTermTechnicalSnapshot technical,
            BigDecimal technicalRankingScore,
            ShortTermChipSnapshot chip,
            ChipActivationMode activationMode,
            BigDecimal nearOverheadMaxPenalty,
            BigDecimal additionalRankingAdjustment
    ) {
        List<String> dataGaps = new ArrayList<>();
        FlowScore flowScore = buyingPressure(fundFlow, quoteTradeDate, dataGaps);
        BigDecimal pressureRelief = overheadPressureRelief(technical, dataGaps);
        BigDecimal technicalScore = clamp(technicalRankingScore == null
                ? new BigDecimal("50")
                : technicalRankingScore);
        BigDecimal fundFlowAdjustment = flowScore.available()
                ? flowScore.score().subtract(new BigDecimal("50"))
                .divide(new BigDecimal("25"), 6, RoundingMode.HALF_UP)
                .max(new BigDecimal("-2"))
                .min(new BigDecimal("2"))
                : BigDecimal.ZERO;
        BigDecimal v2RankingScore = clamp(technicalScore.add(fundFlowAdjustment));
        BigDecimal chipContribution = chip == null || chip.contributionScore() == null
                ? BigDecimal.ZERO
                : chip.contributionScore().max(BigDecimal.ZERO).min(MAX_CHIP_CONTRIBUTION);
        BigDecimal v3RankingScore = technicalScore.multiply(V3_TECHNICAL_WEIGHT)
                .add(chipContribution)
                .add(pressureRelief.multiply(V3_PRESSURE_WEIGHT))
                .add(flowScore.available()
                        ? flowScore.score().multiply(V3_BUY_WEIGHT)
                        : BigDecimal.ZERO);
        BigDecimal rankingBase = clamp(v2RankingScore.add(additionalRankingAdjustment == null
                ? BigDecimal.ZERO : additionalRankingAdjustment));
        ChipPressureScore chipPressure = chipPressure(
                chip, quoteTradeDate, activationMode, nearOverheadMaxPenalty, rankingBase, dataGaps);
        BigDecimal rankingScore = chipPressure.penalty() == null
                ? rankingBase : rankingBase.subtract(chipPressure.penalty());
        return new ShortTermSupplyDemandScore(
                scale(flowScore.mainRatio()),
                scale(flowScore.largeOrderRatio()),
                scale(flowScore.score()),
                scale(fundFlowAdjustment),
                scale(pressureRelief),
                scale(technicalScore),
                scale(clamp(v2RankingScore)),
                scale(chipContribution),
                scale(clamp(v3RankingScore)),
                scale(clamp(rankingScore)),
                dataGaps,
                scale(chipPressure.penalty()),
                chipPressure.summary()
        );
    }

    private ChipPressureScore chipPressure(
            ShortTermChipSnapshot chip,
            LocalDate quoteTradeDate,
            ChipActivationMode activationMode,
            BigDecimal maxPenalty,
            BigDecimal availableScore,
            List<String> dataGaps
    ) {
        if (activationMode == null || activationMode == ChipActivationMode.OFF
                || maxPenalty == null || maxPenalty.signum() <= 0) {
            return new ChipPressureScore(null, "近端筹码压力扣分已关闭，未参与本次排序。");
        }
        String unavailable = null;
        if (chip == null || chip.dataQuality() != ChipDataQuality.VALID || chip.shortTermPressure() == null) {
            unavailable = "近端筹码压力未评估：本地有效筹码分布不足";
        } else if (quoteTradeDate == null || !quoteTradeDate.equals(chip.localTradeDate())) {
            unavailable = "近端筹码压力未评估：筹码日期与行情日期不一致";
        } else if ((chip.verificationStatus() != ChipVerificationStatus.VERIFIED
                && chip.verificationStatus() != ChipVerificationStatus.SINGLE_SOURCE)
                || chip.verificationCoefficient() == null || chip.verificationCoefficient().signum() <= 0) {
            unavailable = "近端筹码压力未评估：模型校验未通过或数据已过期";
        }
        if (unavailable != null) {
            dataGaps.add(unavailable);
            return new ChipPressureScore(null, unavailable + "；未扣分，不代表上方压力低。");
        }
        ChipOverheadPressure pressure = chip.shortTermPressure();
        if (pressure.referencePrice() == null || pressure.referencePrice().signum() <= 0
                || pressure.nearChipRatioPercent() == null || pressure.fartherChipRatioPercent() == null
                || pressure.weightedChipRatioPercent() == null) {
            String gap = "近端筹码压力未评估：近端价格桶指标缺失";
            dataGaps.add(gap);
            return new ChipPressureScore(null, gap + "；未扣分，不代表上方压力低。");
        }
        BigDecimal estimatedPenalty = clamp(pressure.weightedChipRatioPercent())
                .multiply(maxPenalty.max(BigDecimal.ZERO).min(HUNDRED))
                .divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal appliedPenalty = activationMode == ChipActivationMode.ACTIVE
                ? scale(estimatedPenalty.min(availableScore.max(BigDecimal.ZERO))) : BigDecimal.ZERO;
        String source = chip.calculationMode() == ChipCalculationMode.INTRADAY_ESTIMATE
                ? "本地盘中估算" : "本地收盘估算";
        String calibration = chip.verificationStatus() == ChipVerificationStatus.SINGLE_SOURCE
                ? "未交叉验证" : "汇总指标已交叉核验";
        String effect = activationMode == ChipActivationMode.ACTIVE
                ? "排序扣分 " + scale(appliedPenalty).toPlainString() + " 分"
                : "仅观察，估算扣分 " + scale(estimatedPenalty).toPlainString() + " 分，未参与排序";
        return new ChipPressureScore(appliedPenalty,
                source + "（" + chip.localTradeDate() + "，" + calibration + "），参考价 "
                        + pressure.referencePrice().toPlainString()
                        + " 元；上方 0～5% 筹码 " + scale(pressure.nearChipRatioPercent()).toPlainString()
                        + "%，5～10% 筹码 " + scale(pressure.fartherChipRatioPercent()).toPlainString()
                        + "%，距离加权占比 " + scale(pressure.weightedChipRatioPercent()).toPlainString()
                        + "%；扣分上限 " + scale(maxPenalty.max(BigDecimal.ZERO).min(HUNDRED)).toPlainString()
                        + " 分，" + effect + "。10% 以外筹码不计入此项，区间与权重为待验证规则。");
    }

    private record ChipPressureScore(BigDecimal penalty, String summary) {
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
