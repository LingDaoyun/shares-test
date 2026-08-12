package com.aistock.research.history;

import com.aistock.research.decision.InvestmentDecisionReport;
import com.aistock.research.shortterm.ShortTermCandidate;
import com.aistock.research.shortterm.ShortTermReport;
import com.aistock.research.shortterm.ShortTermScoreBreakdown;
import com.aistock.research.shortterm.ShortTermMarketSentiment;
import com.aistock.research.shortterm.validation.ShortTermObservationService;
import com.aistock.research.trading.TradingAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchHistoryServiceTest {

    private final AnalysisHistoryRepository analysisRepository = mock(AnalysisHistoryRepository.class);
    private final DecisionHistoryRepository decisionRepository = mock(DecisionHistoryRepository.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final ResearchHistoryService service = new ResearchHistoryService(
            analysisRepository,
            decisionRepository,
            objectMapper
    );

    @Test
    void recordsAnalysisAndDecisionAsLinkedAppendOnlyHistory() throws Exception {
        InvestmentDecisionReport report = mock(InvestmentDecisionReport.class);
        when(report.symbol()).thenReturn("002714");
        when(report.companyName()).thenReturn("牧原股份");
        when(report.actionStage()).thenReturn("EVIDENCE_REVIEW");
        when(report.actionLabel()).thenReturn("证据复核");
        when(report.actionReason()).thenReturn("等待财报和周期证据");
        when(report.decisionScore()).thenReturn(new BigDecimal("68.50"));
        when(report.generatedAt()).thenReturn(Instant.parse("2026-07-11T08:00:00Z"));
        when(objectMapper.writeValueAsString(report)).thenReturn("{\"symbol\":\"002714\"}");
        when(analysisRepository.save(any(AnalysisHistoryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DecisionHistoryEntry entry = service.recordDecision(report, "SPECIAL_ATTENTION");

        assertThat(entry.symbol()).isEqualTo("002714");
        assertThat(entry.actionLabel()).isEqualTo("证据复核");
        assertThat(entry.analysisId()).isNotBlank();
        verify(analysisRepository).save(any(AnalysisHistoryEntity.class));
        verify(decisionRepository).save(any(DecisionHistoryEntity.class));
    }

    @Test
    void recordsAutomatedShortTermCandidatesWithTheirFinalAdvice() throws Exception {
        ShortTermCandidate candidate = mock(ShortTermCandidate.class);
        ShortTermScoreBreakdown score = mock(ShortTermScoreBreakdown.class);
        ShortTermMarketSentiment sentiment = mock(ShortTermMarketSentiment.class);
        TradingAdvice advice = mock(TradingAdvice.class);
        ShortTermReport report = mock(ShortTermReport.class);
        when(candidate.symbol()).thenReturn("600001");
        when(candidate.name()).thenReturn("右侧股份");
        when(candidate.action()).thenReturn("RIGHT_EARLY_ADD");
        when(candidate.reason()).thenReturn("右侧与量能共振");
        when(candidate.score()).thenReturn(score);
        when(candidate.todayAdvice()).thenReturn(advice);
        when(score.finalScore()).thenReturn(new BigDecimal("76.20"));
        when(advice.action()).thenReturn("LIGHT_TRIAL");
        when(advice.actionLabel()).thenReturn("轻仓试错");
        when(report.candidates()).thenReturn(List.of(candidate));
        when(report.marketSentiment()).thenReturn(sentiment);
        when(report.generatedAt()).thenReturn(Instant.parse("2026-07-11T08:30:00Z"));
        when(objectMapper.writeValueAsString(any(ShortTermHistoryPayload.class)))
                .thenReturn("{\"symbol\":\"600001\"}");

        service.recordShortTermReport(report);

        verify(analysisRepository).save(any(AnalysisHistoryEntity.class));
        verify(decisionRepository).save(any(DecisionHistoryEntity.class));
        ArgumentCaptor<ShortTermHistoryPayload> payloadCaptor = ArgumentCaptor.forClass(ShortTermHistoryPayload.class);
        verify(objectMapper).writeValueAsString(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().candidate()).isSameAs(candidate);
        assertThat(payloadCaptor.getValue().marketSentiment()).isSameAs(sentiment);
    }

    @Test
    void archivesScheduledShortTermHistoryIdempotentlyBySnapshotIdentity() throws Exception {
        ShortTermCandidate candidate = mock(ShortTermCandidate.class);
        ShortTermReport report = mock(ShortTermReport.class);
        when(candidate.symbol()).thenReturn("600001");
        when(candidate.name()).thenReturn("右侧股份");
        when(report.candidates()).thenReturn(List.of(candidate));
        when(objectMapper.writeValueAsString(any(ShortTermHistoryPayload.class)))
                .thenReturn("{\"symbol\":\"600001\"}");
        when(analysisRepository.existsById(any(String.class))).thenReturn(false, true);
        when(decisionRepository.existsById(any(String.class))).thenReturn(false, true);

        service.recordShortTermReport("2026-07-23:FINAL:fingerprint", report);
        service.recordShortTermReport("2026-07-23:FINAL:fingerprint", report);

        ArgumentCaptor<AnalysisHistoryEntity> analysisCaptor =
                ArgumentCaptor.forClass(AnalysisHistoryEntity.class);
        ArgumentCaptor<DecisionHistoryEntity> decisionCaptor =
                ArgumentCaptor.forClass(DecisionHistoryEntity.class);
        verify(analysisRepository, times(1)).save(analysisCaptor.capture());
        verify(decisionRepository, times(1)).save(decisionCaptor.capture());
        assertThat(decisionCaptor.getValue().toEntry().analysisId())
                .isEqualTo(analysisCaptor.getValue().getAnalysisId());
    }

    @Test
    void scheduledArchiveAlsoCapturesAnIndependentStrategyObservation() throws Exception {
        ShortTermObservationService observationService = mock(ShortTermObservationService.class);
        ResearchHistoryService serviceWithObservation = new ResearchHistoryService(
                analysisRepository, decisionRepository, objectMapper, observationService);
        ShortTermCandidate candidate = mock(ShortTermCandidate.class);
        ShortTermReport report = mock(ShortTermReport.class);
        when(candidate.symbol()).thenReturn("600001");
        when(candidate.name()).thenReturn("右侧股份");
        when(report.candidates()).thenReturn(List.of(candidate));
        when(objectMapper.writeValueAsString(any(ShortTermHistoryPayload.class)))
                .thenReturn("{\"symbol\":\"600001\"}");

        serviceWithObservation.recordShortTermReport("2026-08-12:FINAL:fingerprint", report);

        verify(observationService).captureScheduledFinal(
                org.mockito.ArgumentMatchers.eq("2026-08-12:FINAL:fingerprint"),
                org.mockito.ArgumentMatchers.same(report),
                any(Instant.class));
    }
}
