package com.aistock.research.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.aistock.research.tradefeedback.StrategyFeedbackSummary;

class StrategyDecisionBrokerTest {

    private final StrategyDecisionBroker broker = new StrategyDecisionBroker();

    @Test
    void shouldDowngradeAddWhenAnotherStrategyRequiresEvidenceReview() {
        TradingAdvice advice = broker.resolve(List.of(
                new StrategyDecisionInput(
                        "short_term",
                        "短线右侧",
                        "SHORT_TERM",
                        "ADD",
                        "加仓",
                        78,
                        new BigDecimal("82"),
                        "右侧结构、缩量上涨和热门方向共振。",
                        List.of("跌破 MA20 退出")
                ),
                new StrategyDecisionInput(
                        "value",
                        "长期价投",
                        "VALUE",
                        "WAIT",
                        "财报待补",
                        64,
                        new BigDecimal("69"),
                        "行业地位可观察，但近三年财报质量和公告反证仍需补齐。",
                        List.of("补齐财报和公告证据前不加仓")
                )
        ));

        assertThat(advice.action()).isEqualTo("LIGHT_TRIAL");
        assertThat(advice.actionLabel()).contains("轻仓");
        assertThat(advice.summary()).contains("策略分歧", "不直接加仓");
        assertThat(advice.reasons()).anySatisfy(reason ->
                assertThat(reason).contains("短线右侧", "加仓"));
        assertThat(advice.reasons()).anySatisfy(reason ->
                assertThat(reason).contains("长期价投", "财报待补"));
        assertThat(advice.riskControls()).anySatisfy(control ->
                assertThat(control).contains("证据补齐"));
    }

    @Test
    void shouldKeepAddOnlyWhenActionableStrategiesAgreeAndNoReviewGateExists() {
        TradingAdvice advice = broker.resolve(List.of(
                new StrategyDecisionInput(
                        "short_term",
                        "短线右侧",
                        "SHORT_TERM",
                        "ADD",
                        "加仓",
                        80,
                        new BigDecimal("84"),
                        "右侧早期确认，尾盘承接未走坏。",
                        List.of("单笔仓位 <= 10%")
                ),
                new StrategyDecisionInput(
                        "cycle",
                        "周期试仓",
                        "CYCLE",
                        "LIGHT_TRIAL",
                        "左侧试仓",
                        72,
                        new BigDecimal("76"),
                        "周期底部赔率可观察。",
                        List.of("只允许小仓")
                )
        ));

        assertThat(advice.action()).isEqualTo("ADD");
        assertThat(advice.summary()).contains("多策略支持");
        assertThat(advice.riskControls()).anySatisfy(control ->
                assertThat(control).contains("分批"));
    }

    @Test
    void shouldApplyMaturedFeedbackToConfidenceWithoutChangingAction() {
        TradingAdvice advice = broker.resolve(
                List.of(new StrategyDecisionInput(
                        "mispricing",
                        "错杀估值池",
                        "VALUE",
                        "ADD",
                        "加仓",
                        80,
                        new BigDecimal("84"),
                        "估值修复和财报质量匹配。",
                        List.of("分批加仓"),
                        "MISPRICING",
                        "mispricing-v2"
                )),
                List.of(feedback("MISPRICING", "mispricing-v2", new BigDecimal("-4.00")))
        );

        assertThat(advice.action()).isEqualTo("ADD");
        assertThat(advice.confidence()).isEqualTo(76);
        assertThat(advice.reasons()).anySatisfy(reason ->
                assertThat(reason).contains("历史复盘", "MISPRICING", "mispricing-v2"));
        assertThat(advice.riskControls()).anySatisfy(control ->
                assertThat(control).contains("历史复盘为负修正"));
    }

    private StrategyFeedbackSummary feedback(String sourceModule, String ruleVersion, BigDecimal adjustment) {
        return new StrategyFeedbackSummary(
                sourceModule,
                ruleVersion,
                "T20",
                24,
                9,
                new BigDecimal("0.3750"),
                new BigDecimal("-1.2000"),
                new BigDecimal("-2.0000"),
                new BigDecimal("4.5000"),
                new BigDecimal("-6.8000"),
                BigDecimal.ZERO,
                0,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 1),
                true,
                true,
                adjustment
        );
    }
}
