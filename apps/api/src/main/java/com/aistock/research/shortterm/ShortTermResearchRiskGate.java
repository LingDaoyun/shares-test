package com.aistock.research.shortterm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** A scenario-based research gate; passing it does not replace the other execution gates. */
final class ShortTermResearchRiskGate {
    private static final BigDecimal ROUND_TRIP_COST_ASSUMPTION = new BigDecimal("0.0020");
    private static final BigDecimal MINIMUM_NET_REWARD_RISK = new BigDecimal("1.20");

    private ShortTermResearchRiskGate() {
    }

    static Assessment assess(ShortTermTradePlan plan) {
        if (plan == null) {
            return invalid("缺少交易计划，无法计算分批目标情景的净风险收益比");
        }

        BigDecimal fill;
        BigDecimal lowestEntry;
        String fillBasis;
        if (plan.entryLow() == null && plan.entryHigh() == null) {
            if (!positive(plan.referenceEntryPrice())) {
                return invalid("入场区间和有效参考入场价均缺失，未计算净风险收益比");
            }
            fill = plan.referenceEntryPrice();
            lowestEntry = fill;
            fillBasis = "未提供入场区间，按计划明确提供的参考入场价 " + number(fill) + " 元计算";
        } else {
            if (!positive(plan.entryLow()) || !positive(plan.entryHigh())
                    || plan.entryHigh().compareTo(plan.entryLow()) < 0) {
                return invalid("入场区间缺失、非正数或上下界倒置，未用参考价替代");
            }
            fill = plan.entryHigh();
            lowestEntry = plan.entryLow();
            fillBasis = "按入场区间上沿 " + number(fill) + " 元作为保守成交价计算";
        }

        if (!positive(plan.firstTargetPrice()) || !positive(plan.secondTargetPrice())
                || plan.firstTargetPrice().compareTo(fill) <= 0
                || plan.secondTargetPrice().compareTo(plan.firstTargetPrice()) < 0) {
            return invalid("目标价缺失或无效：第一目标须高于保守成交价，第二目标不得低于第一目标");
        }
        BigDecimal reduction = plan.firstReductionRatio();
        if (reduction == null || reduction.signum() < 0 || reduction.compareTo(BigDecimal.ONE) > 0) {
            return invalid("首次减仓比例缺失或不在 0 至 1 范围，未假定分批比例");
        }
        if (!positive(plan.hardStopPrice()) || plan.hardStopPrice().compareTo(lowestEntry) >= 0) {
            return invalid("硬止损价缺失或无效：须为正数且低于整个入场区间（无区间时低于参考入场价）");
        }

        BigDecimal scenarioExit = plan.firstTargetPrice().multiply(reduction)
                .add(plan.secondTargetPrice().multiply(BigDecimal.ONE.subtract(reduction)));
        BigDecimal cost = fill.multiply(ROUND_TRIP_COST_ASSUMPTION);
        BigDecimal netReward = scenarioExit.subtract(fill).subtract(cost);
        BigDecimal risk = fill.subtract(plan.hardStopPrice()).add(cost);
        // Compare exact values before display rounding, so an almost-qualifying plan cannot pass.
        boolean allowsExecution = netReward.compareTo(risk.multiply(MINIMUM_NET_REWARD_RISK)) >= 0;
        BigDecimal ratio = netReward.divide(risk, 4, RoundingMode.DOWN);

        List<String> reasons = new ArrayList<>();
        reasons.add(fillBasis);
        reasons.add("分批目标情景：第一目标价按首次减仓比例加权，剩余比例按第二目标价加权；不代表概率或预期收益");
        reasons.add("往返费用与滑点合计按成交价的 0.20% 作研究假设，并非实际费率；目标收益扣成本、止损风险加成本");
        reasons.add("净风险收益比 " + ratio.toPlainString() + "，研究门槛 1.20（未经样本外验证的初始参数）"
                + (allowsExecution ? "，通过本项检查" : "，低于门槛，阻断执行"));
        return new Assessment(allowsExecution, ratio, reasons);
    }

    private static Assessment invalid(String reason) {
        return new Assessment(false, null, List.of(reason));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    record Assessment(boolean allowsExecution, BigDecimal netRewardRiskRatio, List<String> reasons) {
        Assessment {
            reasons = List.copyOf(reasons);
        }
    }
}
