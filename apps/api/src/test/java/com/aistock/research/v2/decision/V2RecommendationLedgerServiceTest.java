package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.SignalProvenance;
import com.aistock.research.v2.strategy.SourceQualityStatus;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2RecommendationLedgerServiceTest {

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @Autowired
    private V2RecommendationLedgerService service;

    @Autowired
    private ObjectMapper objectMapper;

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
        JsonNode payload = readPayload(first);
        assertThat(payload.path("rankScore").decimalValue()).isEqualByComparingTo("68.25");
        assertThat(payload.path("blockedReasons")).isEmpty();
        assertThat(payload.path("context").path("valuation").asText()).isEqualTo("context-pb-percentile");
        assertThat(payload.path("replayPayload").path("valuation").asText()).isEqualTo("replay-sector-percentile");
        assertThat(payload.path("sourceQuality").asText()).isEqualTo("VERIFIED");
        assertThat(payload.path("signalProvenance").asText()).isEqualTo("RULE_ENGINE");
    }

    @Test
    void recordsEquivalentMapOrdersAsOneCanonicalLedgerEntry() {
        V2RecommendationLedgerEntity first = service.record(signalWithMapOrder(false));
        V2RecommendationLedgerEntity second = service.record(signalWithMapOrder(true));

        assertThat(second.getLedgerId()).isEqualTo(first.getLedgerId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(second.getPayloadJson()).isEqualTo(first.getPayloadJson());
    }

    @Test
    void recordsEquivalentNumericScalesAsOneCanonicalLedgerEntry() {
        V2RecommendationLedgerEntity first = service.record(signalWithNumericScales(false));
        V2RecommendationLedgerEntity second = service.record(signalWithNumericScales(true));

        assertThat(second.getLedgerId()).isEqualTo(first.getLedgerId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(second.getPayloadJson()).isEqualTo(first.getPayloadJson());
        assertThat(first.getPayloadJson()).contains("\"rankScore\":68.25");
        assertThat(first.getPayloadJson()).contains("\"dataConfidence\":86.00");
        assertThat(first.getPayloadJson()).contains("\"positionLimit\":0.1000");
        assertThat(first.getPayloadJson()).contains("\"threshold\":12.34");
    }

    @Test
    void concurrentRecordingReturnsTheSameLedgerEntry() throws Exception {
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<V2RecommendationLedgerEntity>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return service.record(signal());
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> ledgerIds = new ArrayList<>();
            for (Future<V2RecommendationLedgerEntity> future : futures) {
                ledgerIds.add(future.get(20, TimeUnit.SECONDS).getLedgerId());
            }
            assertThat(ledgerIds).hasSize(callers).containsOnly(ledgerIds.get(0));
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
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
                List.of("行业估值低位"), List.of(), Map.of("valuation", "context-pb-percentile"),
                SourceQualityStatus.VERIFIED,
                Map.of("valuation", "replay-sector-percentile"),
                SignalProvenance.RULE_ENGINE);
    }

    private StrategySignal signalWithMapOrder(boolean reverseOrder) {
        Map<String, String> context = new LinkedHashMap<>();
        Map<String, Object> replayPayload = new LinkedHashMap<>();
        if (reverseOrder) {
            context.put("region", "CN");
            context.put("valuation", "context-pb-percentile");
            replayPayload.put("source", "replay-research-feed");
            replayPayload.put("valuation", "replay-sector-percentile");
        } else {
            context.put("valuation", "context-pb-percentile");
            context.put("region", "CN");
            replayPayload.put("valuation", "replay-sector-percentile");
            replayPayload.put("source", "replay-research-feed");
        }
        return new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.LIGHT_TRIAL, new BigDecimal("0.10"),
                "PB low percentile", "ROE deterioration",
                new BigDecimal("68.25"), new BigDecimal("86.00"), null, null,
                List.of("行业估值低位"), List.of(), context,
                SourceQualityStatus.VERIFIED, replayPayload, SignalProvenance.RULE_ENGINE);
    }

    private StrategySignal signalWithNumericScales(boolean expandedScale) {
        Map<String, Object> replayPayload = Map.of(
                "analysis", Map.of("threshold", new BigDecimal(expandedScale ? "12.3400" : "12.34")));
        return new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.LIGHT_TRIAL,
                new BigDecimal(expandedScale ? "0.1000" : "0.1"),
                "PB low percentile", "ROE deterioration",
                new BigDecimal(expandedScale ? "68.250" : "68.25"),
                new BigDecimal(expandedScale ? "86.00" : "86.0"), null, null,
                List.of("行业估值低位"), List.of(), Map.of("valuation", "context-pb-percentile"),
                SourceQualityStatus.VERIFIED, replayPayload, SignalProvenance.RULE_ENGINE);
    }

    private JsonNode readPayload(V2RecommendationLedgerEntity entity) {
        try {
            return objectMapper.readTree(entity.getPayloadJson());
        } catch (Exception ex) {
            throw new AssertionError("Ledger payload must be valid JSON", ex);
        }
    }
}
