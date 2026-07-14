package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2RecommendationLedgerServiceTest {

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @Autowired
    private V2RecommendationLedgerService service;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void recordsSignalWithReplayPayloadAndStableFingerprint() {
        StrategySignal signal = signal();

        V2RecommendationLedgerEntity first = service.record(signal);
        V2RecommendationLedgerEntity second = service.record(signal);

        assertThat(second.getLedgerId()).isEqualTo(first.getLedgerId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.getRecommendationFingerprint()).hasSize(64);
        assertThat(first.getStrategyCode()).isEqualTo("VALUE_REVERSION");
        assertThat(first.getAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(first.getPayloadJson()).contains("\"rankScore\":68.25");
        assertThat(first.getPayloadJson()).contains("\"blockedReasons\":[]");
    }

    @Test
    void latestReturnsMostRecentDecisionForSymbol() {
        service.record(signal());
        StrategySignal later = new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-15T07:20:00Z"), Instant.parse("2026-07-15T07:19:30Z"),
                CandidateStage.WATCH, StrategyAction.NEXT_WATCH, null, "", "",
                new BigDecimal("61.00"), new BigDecimal("82.00"), null, null,
                List.of("估值仍有优势"), List.of(), Map.of("reason", "price-up"));
        service.record(later);

        assertThat(service.latest("600036")).isPresent();
        assertThat(service.latest("600036").get().getAction()).isEqualTo("NEXT_WATCH");
    }

    private StrategySignal signal() {
        return new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.LIGHT_TRIAL, new BigDecimal("0.10"),
                "PB low percentile", "ROE deterioration",
                new BigDecimal("68.25"), new BigDecimal("86.00"), null, null,
                List.of("行业估值低位"), List.of(), Map.of("valuation", "pb-percentile"));
    }
}
