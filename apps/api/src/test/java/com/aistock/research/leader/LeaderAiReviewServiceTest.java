package com.aistock.research.leader;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaderAiReviewServiceTest {

    private final PotentialLeaderService potentialLeaderService = mock(PotentialLeaderService.class);
    private final LlmChatClient llmChatClient = mock(LlmChatClient.class);
    private final LeaderSnapshotStore snapshotStore = mock(LeaderSnapshotStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final LeaderAiReviewService service = new LeaderAiReviewService(
            potentialLeaderService, llmChatClient, snapshotStore, objectMapper);

    @Test
    void rejectsHashMismatchBeforeCallingModelEvenIfCandidateLookupReturns() {
        PotentialLeaderSnapshot.Candidate candidate = candidate();
        when(potentialLeaderService.currentCandidate(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600000", "stale-hash"))
                .thenReturn(candidate);

        assertThatThrownBy(() -> service.review(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600000", "stale-hash"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(llmChatClient, never()).completeJson(anyString(), anyString(), anyString(), any());
        verify(snapshotStore, never()).saveAiReview(any());
    }

    @Test
    void propagatesMissingCandidateAsNotFound() {
        when(potentialLeaderService.currentCandidate(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600099", "missing"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "当前快照中没有该候选"));

        assertThatThrownBy(() -> service.review(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600099", "missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void persistsFailedReviewForInvalidStructuredOutputWithoutMutatingCandidate() throws Exception {
        PotentialLeaderSnapshot.Candidate candidate = candidate();
        stubCandidateAndConfig(candidate);
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), any()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "verdict":"MAYBE",
                          "phase":"COMPETING",
                          "advantages":[],
                          "counter_evidence":[],
                          "data_gaps":[],
                          "must_verify":[],
                          "confidence":0.70
                        }
                        """));

        LeaderAiReview review = service.review(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600000", candidate.snapshotHash());

        assertThat(review.status()).isEqualTo("FAILED");
        assertThat(review.errorSummary()).contains("verdict");
        assertThat(candidate.score()).isEqualByComparingTo("82");
        assertThat(candidate.rank()).isEqualTo(1);
        assertThat(candidate.snapshotHash()).isEqualTo("candidate-hash");
        verify(snapshotStore).saveAiReview(review);
    }

    @Test
    void persistsValidShadowReviewAndLimitsNarrativeLists() throws Exception {
        PotentialLeaderSnapshot.Candidate candidate = candidate();
        stubCandidateAndConfig(candidate);
        when(llmChatClient.completeJson(anyString(), anyString(), anyString(), any()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "verdict":"SUPPORT",
                          "phase":"EMERGING",
                          "advantages":["主线共振","量价结构清晰","容量适中","第四条不应保留"],
                          "counter_evidence":["  ","上影仍需观察"],
                          "data_gaps":["龙虎榜尚未披露"],
                          "must_verify":["核对次日板块广度"],
                          "confidence":0.78
                        }
                        """));

        LeaderAiReview review = service.review(
                PotentialLeaderSnapshot.Lane.POTENTIAL, "600000", candidate.snapshotHash());

        assertThat(review.status()).isEqualTo("SUCCEEDED");
        assertThat(review.verdict()).isEqualTo("SUPPORT");
        assertThat(review.phase()).isEqualTo("EMERGING");
        assertThat(review.advantages()).containsExactly("主线共振", "量价结构清晰", "容量适中");
        assertThat(review.counterEvidence()).containsExactly("上影仍需观察");
        assertThat(review.confidence()).isEqualByComparingTo("0.78");
        verify(snapshotStore).saveAiReview(review);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(llmChatClient).completeJson(anyString(), anyString(), anyString(), schema.capture());
        assertThat(schema.getValue()).containsEntry("additionalProperties", false);
        assertThat((List<?>) schema.getValue().get("required")).hasSize(7);
    }

    private void stubCandidateAndConfig(PotentialLeaderSnapshot.Candidate candidate) {
        when(potentialLeaderService.currentCandidate(
                candidate.lane(), candidate.symbol(), candidate.snapshotHash())).thenReturn(candidate);
        when(potentialLeaderService.currentTradeDate()).thenReturn(LocalDate.parse("2026-09-03"));
        when(llmChatClient.currentConfig()).thenReturn(new LlmConfigPreview(
                "deepseek", "deepseek-v4-pro", "https://api.deepseek.com", "json_schema",
                true, true, "database", null, 4096, null));
    }

    private PotentialLeaderSnapshot.Candidate candidate() {
        return new PotentialLeaderSnapshot.Candidate(
                PotentialLeaderSnapshot.Lane.POTENTIAL, 1, PotentialLeaderSnapshot.Grade.A, bd("82"),
                "600000", "测试股份", "半导体", "MAIN", bd("10.8"), bd("8"), bd("1.5"),
                bd("5"), bd("100000000"), bd("8000000000"), bd("9.8"), bd("9.5"), bd("0.02"),
                bd("101"), false, 0, null, null, 0, BigDecimal.ZERO,
                List.of(new PotentialLeaderSnapshot.ScoreComponent(
                        "POTENTIAL_DIRECTION", "主线共振", bd("82"), bd("24.6"), bd("30"))),
                List.of(), List.of("所属强方向第 1 名"),
                new PotentialLeaderSnapshot.DragonTigerEvidence(
                        PotentialLeaderSnapshot.DisclosureStatus.NOT_DISCLOSED, null, BigDecimal.ZERO,
                        List.of(), List.of("龙虎榜尚未披露")), null, "candidate-hash");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
