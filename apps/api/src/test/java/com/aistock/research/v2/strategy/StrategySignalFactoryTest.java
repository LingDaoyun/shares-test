package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySignalFactoryTest {

    @Test
    void createsBlockedSignalWithSeparatedScoresAndReasons() {
        StrategySignal signal = StrategySignalFactory.blocked(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                "002714",
                "牧原股份",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                StrategyAction.DATA_BLOCKED,
                List.of("QUOTE_SNAPSHOT_MISSING"),
                Map.of("quoteStage", "AFTER_HOURS_1520"));

        assertThat(signal.strategyCode()).isEqualTo(StrategyCode.SHORT_RIGHT_SIDE);
        assertThat(signal.action()).isEqualTo(StrategyAction.DATA_BLOCKED);
        assertThat(signal.candidateStage()).isEqualTo(CandidateStage.BLOCKED);
        assertThat(signal.rankScore()).isNull();
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.riskReward()).isNull();
        assertThat(signal.blockedReasons()).containsExactly("QUOTE_SNAPSHOT_MISSING");
        assertThat(signal.context()).containsEntry("quoteStage", "AFTER_HOURS_1520");
    }

    @Test
    void createsResearchSignalWithoutPretendingScoreIsProbability() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of("valuationContext", "industry-percentile"));

        assertThat(signal.rankScore()).isEqualByComparingTo(new BigDecimal("72.35"));
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("84.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.action()).isEqualTo(StrategyAction.NEXT_WATCH);
        assertThat(signal.blockedReasons()).isEmpty();
    }
}
