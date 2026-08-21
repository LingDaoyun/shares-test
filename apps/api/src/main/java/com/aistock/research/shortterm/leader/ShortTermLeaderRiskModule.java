package com.aistock.research.shortterm.leader;

public interface ShortTermLeaderRiskModule {

    ShortTermLeaderRisk evaluate(ShortTermLeaderRiskInput input);

    static ShortTermLeaderRiskModule unavailable() {
        return input -> ShortTermLeaderRisk.unavailable(
                "当前调用路径未配置龙头异动风险模块"
        );
    }
}
