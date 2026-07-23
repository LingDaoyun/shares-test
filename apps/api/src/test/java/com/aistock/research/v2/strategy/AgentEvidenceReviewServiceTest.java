package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvidenceReviewServiceTest {

    private final AgentEvidenceReviewService service = new AgentEvidenceReviewService();

    @Test
    void forcesAbstainWhenAgentHasNoSourceEvidence() {
        AgentEvidenceReview review = service.review(List.of(
                new AgentEvidenceFinding(
                        "ValuationAgent",
                        "估值",
                        AgentEvidenceVote.SUPPORT,
                        "",
                        "",
                        null,
                        "",
                        "估值低估，但没有来源。"
                )
        ), Instant.parse("2026-07-20T08:00:00Z"));

        assertThat(review.findings()).hasSize(1);
        assertThat(review.findings().get(0).vote()).isEqualTo(AgentEvidenceVote.ABSTAIN);
        assertThat(review.warnings()).contains("ValuationAgent 缺少可核验证据，已强制弃权。");
        assertThat(review.supportCount()).isZero();
        assertThat(review.abstainCount()).isEqualTo(1);
    }

    @Test
    void detectsSourceOverlapAndOppositeEvidence() {
        AgentEvidenceReview review = service.review(List.of(
                finding("PolicyAgent", AgentEvidenceVote.SUPPORT, "https://example.com/report-a", "政策支持"),
                finding("IndustryAgent", AgentEvidenceVote.SUPPORT, "https://example.com/report-a", "行业景气"),
                finding("RiskAgent", AgentEvidenceVote.OPPOSE, "https://example.com/risk", "产能过剩")
        ), Instant.parse("2026-07-20T08:00:00Z"));

        assertThat(review.supportCount()).isEqualTo(2);
        assertThat(review.opposeCount()).isEqualTo(1);
        assertThat(review.sourceOverlapCount()).isEqualTo(1);
        assertThat(review.hasConflict()).isTrue();
        assertThat(review.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("来源重合"));
        assertThat(review.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("同时存在支持和反对证据"));
    }

    private AgentEvidenceFinding finding(
            String agentName,
            AgentEvidenceVote vote,
            String sourceUrl,
            String claim
    ) {
        return new AgentEvidenceFinding(
                agentName,
                "测试角色",
                vote,
                sourceUrl,
                "测试来源",
                Instant.parse("2026-07-19T08:00:00Z"),
                Integer.toHexString(sourceUrl.hashCode()),
                claim
        );
    }
}
