package com.aistock.research.shortterm;

import java.util.ArrayList;
import java.util.List;

public class ShortTermSignalProfileResolver {

    public ShortTermSignalProfile resolve(
            ShortTermTechnicalSnapshot technical,
            ShortTermVolatilityQuality volatility
    ) {
        if (technical == null) {
            return ShortTermSignalProfile.unavailable("技术快照缺失");
        }
        ShortTermVolatilityQuality safeVolatility = volatility == null
                ? ShortTermVolatilityQuality.unavailable("波动质量快照缺失")
                : volatility;
        List<String> families = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>(safeVolatility.dataGaps());

        if (safeVolatility.contractionBreakout()) {
            families.add("VOLATILITY_CONTRACTION_BREAKOUT");
            evidence.add("近期真实波幅收缩后出现 ATR 归一化扩张突破");
        }
        ShortTermSupportReversalSignal support = technical.supportReversal();
        if (support != null && (support.confirmed() || support.watchLayer())) {
            families.add("SUPPORT_REVERSAL");
            evidence.add(support.confirmed() ? "长下影收复支撑并通过承接确认" : "长下影承接进入观察层");
        }
        ShortTermGoldenCrossSnapshot cross = technical.goldenCross();
        if (cross != null && (cross.confirmedRecent() || cross.watchLayer())) {
            families.add("GOLDEN_CROSS_MOMENTUM");
            evidence.add(cross.confirmedRecent() ? "MA5/MA10 近期金叉确认" : "MA5/MA10 金叉形成中");
        }
        if (families.isEmpty() && technical.rightSideSignal() != null
                && technical.rightSideSignal().contains("右侧")) {
            families.add("RIGHT_SIDE_TREND");
            evidence.add("价格位于右侧趋势观察结构");
        }
        if (families.isEmpty()) {
            return new ShortTermSignalProfile(
                    "UNAVAILABLE", "信号族待补", List.of(), List.of(), gaps
            );
        }
        String primary = families.get(0);
        return new ShortTermSignalProfile(primary, label(primary), families, evidence, gaps);
    }

    private String label(String family) {
        return switch (family) {
            case "VOLATILITY_CONTRACTION_BREAKOUT" -> "收缩后扩张突破";
            case "SUPPORT_REVERSAL" -> "下影承接反转";
            case "GOLDEN_CROSS_MOMENTUM" -> "金叉动量";
            case "RIGHT_SIDE_TREND" -> "右侧趋势";
            default -> "信号族待补";
        };
    }
}
