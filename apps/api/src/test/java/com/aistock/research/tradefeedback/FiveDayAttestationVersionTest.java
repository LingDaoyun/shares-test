package com.aistock.research.tradefeedback;

import com.aistock.research.firstboard.FirstBoardBaselineService;
import com.aistock.research.firstboard.FirstBoardDailyBarStore;
import com.aistock.research.fiveday.FiveDayContextEngine;
import com.aistock.research.fiveday.FiveDayEvaluator;
import com.aistock.research.fiveday.FiveDayEventEvidenceService;
import com.aistock.research.fiveday.FiveDayExecutionCheckService;
import com.aistock.research.fiveday.FiveDayHistoryService;
import com.aistock.research.fiveday.FiveDayService;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.aistock.research.fiveday.FiveDayModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FiveDayAttestationVersionTest {
    @Test
    void realRegistryRetainsIndependentVersionsForTheSameObservedStock() {
        Instant now = Instant.parse("2026-09-08T02:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneId.of("Asia/Shanghai"));
        TradingClockService calendar = new TradingClockService(fixed);
        RecommendationAttestationService registry = new RecommendationAttestationService(
                new ObjectMapper().findAndRegisterModules(), fixed, Duration.ofMinutes(30), 20);
        FiveDayHistoryService history = mock(FiveDayHistoryService.class);
        FiveDayExecutionCheckService execution = mock(FiveDayExecutionCheckService.class);
        Candidate candidate = new Candidate("600001", "示例公司", "半导体", "MAIN", BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                3, true, true, "https://example.test/flow", new BigDecimal("9.8"), new BigDecimal("10.2"),
                LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-14"), List.of(), List.of(), List.of());
        LocalDate signal = LocalDate.parse("2026-09-07");
        Instant generated = Instant.parse("2026-09-07T08:00:00Z");
        Snapshot v1 = new Snapshot("READY", VERSION, signal, generated, true, false, false, "fixture",
                Rules.defaults(), new Coverage(1, 1, 1, 1, 1, 0, 0), List.of(candidate), List.of());
        Snapshot v2 = new Snapshot("READY", VERSION_V2, signal, generated, true, false, false, "fixture",
                Rules.defaults(), v1.coverage(), List.of(candidate), List.of(), null);
        when(history.latest(signal, false, false)).thenReturn(Optional.of(v1));
        when(history.latest(signal, false, false, VERSION_V2)).thenReturn(Optional.of(v2));
        when(execution.check(v2, candidate)).thenReturn(new FiveDayExecutionCheckService.ExecutionCheck(
                candidate.symbol(), "PASSED", now, "IN_RANGE", null, List.of()));
        FiveDayService service = new FiveDayService(mock(FirstBoardBaselineService.class),
                mock(FirstBoardDailyBarStore.class), mock(EastMoneyClient.class), calendar, new FiveDayEvaluator(calendar),
                history, registry, mock(FiveDayContextEngine.class), mock(FiveDayEventEvidenceService.class), execution);

        VerifiedRecommendationSnapshot original = registry.require(service.attestation(candidate.symbol(), false, false).attestationToken());
        VerifiedRecommendationSnapshot current = registry.require(service.attestation(candidate.symbol(), false, false, VERSION_V2).attestationToken());

        assertThat(original.sourceModule()).isEqualTo("FIVE_DAY");
        assertThat(current.sourceModule()).isEqualTo("FIVE_DAY");
        assertThat(original.ruleVersion()).isEqualTo(VERSION);
        assertThat(current.ruleVersion()).isEqualTo(VERSION_V2);
        assertThat(current.attestationId()).isNotEqualTo(original.attestationId());
        assertThat(current.recommendationPayloadJson()).contains("600001");
        verify(history, never()).capture(any());
    }
}
