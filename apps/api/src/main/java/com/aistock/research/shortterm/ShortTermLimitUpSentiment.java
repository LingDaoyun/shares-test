package com.aistock.research.shortterm;

import java.math.BigDecimal;

/**
 * 涨停看板的情绪聚合指标，也是市场状态判定的真实涨停池脉冲。
 *
 * <p>炸板率 = 炸板家数 / (涨停家数 + 炸板家数)，衡量接力意愿；
 * 封板时间分布按 10:00 前、10:00-11:30、11:30-14:30、14:30 后四档统计。
 * brokenCount / limitDownCount / sealBreakRatioPercent 为空表示对应数据源缺口，不计入结论。
 */
public record ShortTermLimitUpSentiment(
        int limitUpCount,
        Integer brokenCount,
        Integer limitDownCount,
        BigDecimal sealBreakRatioPercent,
        int maxConsecutiveBoards,
        int boards2PlusCount,
        int boards3PlusCount,
        int sealedBeforeTenCount,
        int sealedMorningCount,
        int sealedAfternoonCount,
        int sealedTailCount,
        BigDecimal earlySealSharePercent,
        String tone,
        String explanation
) {
}
