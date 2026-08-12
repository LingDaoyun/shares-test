package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShortRightSideStrategyServiceTest {

    private final ShortRightSideStrategyService service = new ShortRightSideStrategyService();

    @Test
    void permitsExecutableActionsAtBothRecentConfirmedGoldenCrossBoundaries() {
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 0, 3)).action())
                .isEqualTo(StrategyAction.ADD);
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 3, 3)).action())
                .isEqualTo(StrategyAction.ADD);
    }

    @Test
    void blocksExecutableActionOutsideAuthoritativeTailEntryCheckpoint() {
        ShortRightSideStrategyInput input = inputWithCrossAndCheckpoint(
                "CONFIRMED", 1, 3, new BigDecimal("84"), "NOT_CONFIRMED:MORNING_CONTINUOUS");

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.action()).isEqualTo(StrategyAction.WAIT);
        assertThat(signal.positionLimit()).isZero();
        assertThat(signal.context()).containsEntry("tradingCheckpoint", "NOT_CONFIRMED:MORNING_CONTINUOUS");
    }

    @Test
    void rejectsFormerPostCloseCheckpointAsNonExecutable() {
        ShortRightSideStrategyInput input = inputWithCrossAndCheckpoint(
                "CONFIRMED", 1, 3, new BigDecimal("84"), "POST_CLOSE_1520");

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.action()).isEqualTo(StrategyAction.WAIT);
        assertThat(signal.positionLimit()).isZero();
        assertThat(signal.evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("14:45-14:49"));
    }

    @Test
    void blocksExecutableActionWhenLegacyStrategyDidNotAuthorizeEntry() {
        ShortRightSideStrategyInput input = inputWithLegacyGate(
                "WATCH_RIGHT_SIDE", "WAIT", "CONFIRMED", true);

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.action()).isEqualTo(StrategyAction.WAIT);
        assertThat(signal.positionLimit()).isZero();
        assertThat(signal.context()).containsEntry("legacyCandidateAction", "WATCH_RIGHT_SIDE");
    }

    @Test
    void neverUpgradesLegacyLightTrialIntoAdd() {
        ShortRightSideStrategyInput input = inputWithLegacyGate(
                "RIGHT_EARLY_ADD", "LIGHT_TRIAL", "WATCH", true);

        assertThat(service.evaluate(input).action()).isEqualTo(StrategyAction.LIGHT_TRIAL);
    }

    @Test
    void blocksExecutableActionsOutsideRecentConfirmedGoldenCrossBoundaries() {
        assertThat(service.evaluate(inputWithCross("APPROACHING", null, 2)).action())
                .isEqualTo(StrategyAction.WAIT);
        assertThat(service.evaluate(inputWithCross("FORMING", 0, 2)).action())
                .isEqualTo(StrategyAction.WAIT);
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 4, 3)).action())
                .isEqualTo(StrategyAction.WAIT);
        assertThat(service.evaluate(inputWithCross("CONFIRMED", -1, 3)).action())
                .isEqualTo(StrategyAction.WAIT);
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 2)).action())
                .isEqualTo(StrategyAction.WAIT);
        assertThat(service.evaluate(inputWithCross("NONE", null, 0)).action())
                .isEqualTo(StrategyAction.WAIT);
    }

    @Test
    void mapsGoldenCrossPriorityTiersIntoThePublicRankScore() {
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 3)).rankScore())
                .isEqualByComparingTo("77.38");
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 2)).rankScore())
                .isEqualByComparingTo("74.38");
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 1)).rankScore())
                .isEqualByComparingTo("71.88");
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 0)).rankScore())
                .isEqualByComparingTo("67.38");
    }

    @Test
    void recordsGoldenCrossReplayEvidence() {
        StrategySignal signal = service.evaluate(inputWithCross("CONFIRMED", 1, 3));

        assertThat(signal.replayPayload())
                .containsKey("goldenCross");
        assertThat(signal.context())
                .containsEntry("goldenCrossState", "CONFIRMED")
                .containsEntry("goldenCrossTradingDays", "1")
                .containsEntry("goldenCrossPriorityTier", "3")
                .containsEntry("goldenCrossRuleVersion", "short-golden-cross-v1.0.0");
    }

    @Test
    void blocksLightTrialWhenGoldenCrossIsNotRecentlyConfirmed() {
        assertThat(service.evaluate(inputWithCross("CONFIRMED", 1, 3, new BigDecimal("50"))).action())
                .isEqualTo(StrategyAction.LIGHT_TRIAL);
        StrategySignal signal = service.evaluate(inputWithCross("FORMING", 0, 2, new BigDecimal("50")));

        assertThat(signal.action()).isEqualTo(StrategyAction.WAIT);
        assertThat(signal.evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("形成中", "不能触发"));
    }

    @Test
    void keepsLegacyInputsOnTheNoneGoldenCrossGate() {
        ShortRightSideStrategyInput input = new ShortRightSideStrategyInput(
                "000977", "浪潮信息", "AI算力",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T06:49:30Z"), "TAIL_ENTRY_1445_1449",
                new BigDecimal("84"), new BigDecimal("82"), new BigDecimal("86"),
                new BigDecimal("74"), new BigDecimal("81"), new BigDecimal("70"),
                new BigDecimal("86"), new BigDecimal("32"), List.of());

        assertThat(input.goldenCrossState()).isEqualTo("NONE");
        assertThat(input.goldenCrossPriorityTier()).isZero();
        assertThat(service.evaluate(input).action()).isEqualTo(StrategyAction.WAIT);
    }

    @Test
    void treatsShrinkRiseAsConstructiveOnlyWhenRightSideContextIsValid() {
        ShortRightSideStrategyInput input = inputWithCross("CONFIRMED", 1, 3);

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.strategyCode()).isEqualTo(StrategyCode.SHORT_RIGHT_SIDE);
        assertThat(signal.action()).isEqualTo(StrategyAction.ADD);
        assertThat(signal.positionLimit()).isEqualByComparingTo("0.0800");
        assertThat(signal.context())
                .containsEntry("tradingCheckpoint", "TAIL_ENTRY_1445_1449")
                .containsEntry("supplyAbsorptionUsage", "CONTEXTUAL_CONFIRMATION");
        assertThat(signal.evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("缩量承接", "右侧结构"));
    }

    @Test
    void doesNotBuyOnlyBecauseShrinkRiseLooksStrong() {
        ShortRightSideStrategyInput input = new ShortRightSideStrategyInput(
                "002580",
                "圣阳股份",
                "储能",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                "CLOSE_1500",
                new BigDecimal("65"),
                new BigDecimal("42"),
                new BigDecimal("88"),
                new BigDecimal("36"),
                new BigDecimal("78"),
                new BigDecimal("62"),
                new BigDecimal("76"),
                new BigDecimal("84"),
                List.of()
        );

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.action()).isEqualTo(StrategyAction.WAIT);
        assertThat(signal.positionLimit()).isZero();
        assertThat(signal.evidenceSummary())
                .anySatisfy(item -> assertThat(item).contains("缩量上涨不能单独触发买入"));
        assertThat(signal.context()).containsEntry("riskModel", "ACTIVE");
    }

    @Test
    void blocksShortTermSignalWhenRiskModelMarksItUnstable() {
        ShortRightSideStrategyInput input = new ShortRightSideStrategyInput(
                "000001",
                "测试公司",
                "测试行业",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                "TAIL_ENTRY_1445_1449",
                new BigDecimal("90"),
                new BigDecimal("90"),
                new BigDecimal("90"),
                new BigDecimal("90"),
                new BigDecimal("90"),
                new BigDecimal("90"),
                new BigDecimal("10"),
                new BigDecimal("20"),
                List.of("LOW_LIQUIDITY")
        );

        StrategySignal signal = service.evaluate(input);

        assertThat(signal.candidateStage()).isEqualTo(CandidateStage.BLOCKED);
        assertThat(signal.action()).isEqualTo(StrategyAction.RISK_BLOCKED);
        assertThat(signal.blockedReasons()).contains("SHORT_RISK_FLAG:LOW_LIQUIDITY");
    }

    private ShortRightSideStrategyInput inputWithCross(String state, Integer days, int tier) {
        return inputWithCross(state, days, tier, new BigDecimal("84"));
    }

    private ShortRightSideStrategyInput inputWithCross(
            String state,
            Integer days,
            int tier,
            BigDecimal marketHotScore
    ) {
        return inputWithCrossAndCheckpoint(state, days, tier, marketHotScore, "TAIL_ENTRY_1445_1449");
    }

    private ShortRightSideStrategyInput inputWithCrossAndCheckpoint(
            String state,
            Integer days,
            int tier,
            BigDecimal marketHotScore,
            String tradingCheckpoint
    ) {
        return new ShortRightSideStrategyInput(
                "000977",
                "浪潮信息",
                "AI算力",
                Instant.parse("2026-07-20T07:20:00Z"),
                Instant.parse("2026-07-20T07:19:30Z"),
                tradingCheckpoint,
                marketHotScore,
                new BigDecimal("82"),
                new BigDecimal("86"),
                new BigDecimal("74"),
                new BigDecimal("81"),
                new BigDecimal("70"),
                new BigDecimal("86"),
                new BigDecimal("32"),
                state,
                days,
                tier,
                true,
                "RIGHT_EARLY_ADD",
                "ADD",
                "CONFIRMED",
                true,
                List.of());
    }

    private ShortRightSideStrategyInput inputWithLegacyGate(
            String candidateAction,
            String adviceAction,
            String tailStatus,
            boolean evidenceAllowsBuy
    ) {
        ShortRightSideStrategyInput base = inputWithCross("CONFIRMED", 1, 3);
        return new ShortRightSideStrategyInput(
                base.symbol(), base.companyName(), base.hotDirection(), base.decisionAt(), base.dataCutoffAt(),
                base.tradingCheckpoint(), base.marketHotScore(), base.rightSideStructureScore(),
                base.supplyAbsorptionScore(), base.volumeBreakoutScore(), base.shrinkRiseScore(),
                base.fundamentalFloorScore(), base.liquidityScore(), base.crowdingRiskScore(),
                base.goldenCrossState(), base.goldenCrossTradingDays(), base.goldenCrossPriorityTier(),
                true, candidateAction, adviceAction, tailStatus, evidenceAllowsBuy, List.of());
    }
}
