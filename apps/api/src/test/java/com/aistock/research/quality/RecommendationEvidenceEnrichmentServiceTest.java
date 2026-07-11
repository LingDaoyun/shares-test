package com.aistock.research.quality;

import com.aistock.research.committee.AgentCommitteeService;
import com.aistock.research.committee.AgentConsensusReport;
import com.aistock.research.committee.AgentOpinion;
import com.aistock.research.valuation.PeerValuationCompany;
import com.aistock.research.valuation.PeerValuationReport;
import com.aistock.research.valuation.ValuationHistoryReport;
import com.aistock.research.valuation.ValuationHistoryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationEvidenceEnrichmentServiceTest {

    private final ValuationHistoryService valuationHistoryService = mock(ValuationHistoryService.class);
    private final AgentCommitteeService agentCommitteeService = mock(AgentCommitteeService.class);
    private final RecommendationEvidenceEnrichmentService service = new RecommendationEvidenceEnrichmentService(
            valuationHistoryService,
            agentCommitteeService
    );

    @Test
    void shouldConvertPeerValuationAndAgentConsensusIntoRecommendationEvidenceBundle() {
        when(valuationHistoryService.history("600036", 10)).thenReturn(valuationHistoryReport());
        when(agentCommitteeService.discuss("600036")).thenReturn(agentConsensusReport());

        RecommendationEvidenceBundle bundle = service.enrich("600036");

        assertThat(bundle.available()).isTrue();
        assertThat(bundle.peerValuation().available()).isTrue();
        assertThat(bundle.peerValuation().scopeLabel()).isEqualTo("同行业可比");
        assertThat(bundle.peerValuation().peerCount()).isEqualTo(4);
        assertThat(bundle.peerValuation().medianPe()).isEqualByComparingTo("7.20");
        assertThat(bundle.peerValuation().medianPb()).isEqualByComparingTo("0.72");
        assertThat(bundle.peerValuation().peers()).extracting(PeerValuationBriefPeer::symbol)
                .containsExactly("601398", "601939");
        assertThat(bundle.agentConsensus().available()).isTrue();
        assertThat(bundle.agentConsensus().consensusLabel()).isEqualTo("可观察");
        assertThat(bundle.agentConsensus().supportCount()).isEqualTo(3);
        assertThat(bundle.agentConsensus().vetoCount()).isZero();
        assertThat(bundle.agentConsensus().contrarianSummary()).contains("拨备和息差仍需复核");
        assertThat(bundle.dataGaps()).contains("当前为年末估值样本，不是日频或月频 PE/PB 序列。");
    }

    @Test
    void shouldKeepDataGapWhenPeerValuationOrAgentDiscussionFails() {
        when(valuationHistoryService.history("000001", 10)).thenThrow(new IllegalStateException("估值源超时"));
        when(agentCommitteeService.discuss("000001")).thenThrow(new IllegalStateException("公告源阻塞"));

        RecommendationEvidenceBundle bundle = service.enrich("000001");

        assertThat(bundle.available()).isFalse();
        assertThat(bundle.peerValuation().available()).isFalse();
        assertThat(bundle.agentConsensus().available()).isFalse();
        assertThat(bundle.dataGaps()).contains(
                "行业估值对比暂不可用：估值源超时",
                "多 Agent 共识暂不可用：公告源阻塞"
        );
    }

    @Test
    void shouldReturnQueuedPlaceholderImmediatelyForListEnrichment() {
        when(valuationHistoryService.history(eq("600036"), eq(10))).thenAnswer(invocation -> {
            Thread.sleep(600);
            return valuationHistoryReport();
        });

        long started = System.nanoTime();
        RecommendationEvidenceBundle bundle = service.enrichForList("600036");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(250);
        assertThat(bundle.available()).isFalse();
        assertThat(bundle.dataGaps()).contains("深度证据复核已进入后台队列，刷新后显示结果。");
    }

    @Test
    void shouldUseExplicitGapLabelsForUnavailableEvidence() {
        RecommendationEvidenceBundle bundle = RecommendationEvidenceBundle.unavailable("601899", "深度证据复核超时，请稍后重试。");

        assertThat(bundle.peerValuation().scopeLabel()).isEqualTo("同业估值缺口");
        assertThat(bundle.agentConsensus().consensusLabel()).isEqualTo("Agent 共识缺口");
        assertThat(bundle.dataGaps()).contains("深度证据复核超时，请稍后重试。");
    }

    @Test
    void shouldResolveQueuedListEvidenceForInteractiveCardsAndReuseCache() {
        when(valuationHistoryService.history(eq("600036"), eq(10))).thenAnswer(invocation -> {
            Thread.sleep(80);
            return valuationHistoryReport();
        });
        when(agentCommitteeService.discuss("600036")).thenReturn(agentConsensusReport());

        RecommendationEvidenceBundle queued = service.enrichForList("600036");
        RecommendationEvidenceBundle resolved = service.enrichForInteractiveCard("600036");
        RecommendationEvidenceBundle cached = service.enrichForList("600036");

        assertThat(queued.available()).isFalse();
        assertThat(resolved.available()).isTrue();
        assertThat(resolved.peerValuation().scopeLabel()).isEqualTo("同行业可比");
        assertThat(resolved.agentConsensus().consensusLabel()).isEqualTo("可观察");
        assertThat(cached.available()).isTrue();
        verify(valuationHistoryService, times(1)).history("600036", 10);
        verify(agentCommitteeService, times(1)).discuss("600036");
    }

    private ValuationHistoryReport valuationHistoryReport() {
        PeerValuationReport peerValuation = new PeerValuationReport(
                "INDUSTRY",
                "同行业可比",
                4,
                new BigDecimal("5.90"),
                new BigDecimal("0.80"),
                new BigDecimal("7.20"),
                new BigDecimal("0.72"),
                new BigDecimal("7.80"),
                new BigDecimal("0.78"),
                new BigDecimal("0.25"),
                new BigDecimal("0.60"),
                1,
                2,
                List.of(
                        new PeerValuationCompany("601398", "工商银行", "银行", null, new BigDecimal("6.10"), new BigDecimal("0.55"), new BigDecimal("6.00"), new BigDecimal("900000000"), "同行业"),
                        new PeerValuationCompany("601939", "建设银行", "银行", null, new BigDecimal("6.80"), new BigDecimal("0.62"), new BigDecimal("9.00"), new BigDecimal("800000000"), "同行业")
                ),
                List.of("已基于同行业可比形成 4 个可比样本。"),
                List.of()
        );
        return new ValuationHistoryReport(
                "600036",
                "招商银行",
                "LOW_PERCENTILE",
                "历史低分位",
                8,
                new BigDecimal("5.90"),
                new BigDecimal("0.80"),
                new BigDecimal("0.30"),
                new BigDecimal("0.45"),
                new BigDecimal("8.20"),
                new BigDecimal("1.02"),
                new BigDecimal("5.20"),
                new BigDecimal("12.80"),
                new BigDecimal("0.70"),
                new BigDecimal("1.60"),
                peerValuation,
                List.of(),
                List.of("估值处于历史低分位。"),
                List.of("当前为年末估值样本，不是日频或月频 PE/PB 序列。"),
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    private AgentConsensusReport agentConsensusReport() {
        AgentOpinion support = new AgentOpinion(
                "VALUATION_DISCIPLINARIAN",
                "估值纪律 Agent",
                "只关心价格是否给出足够安全边际",
                "SUPPORT",
                "支持",
                new BigDecimal("82"),
                new BigDecimal("78"),
                List.of("估值安全边际分 78"),
                List.of(),
                List.of("3/5/10 年估值分位", "同业可比估值")
        );
        AgentOpinion contrarian = new AgentOpinion(
                "RISK_CONTRARIAN",
                "反方风控 Agent",
                "专门寻找否决条件、反证和尾部风险",
                "WATCH",
                "观察",
                new BigDecimal("70"),
                new BigDecimal("72"),
                List.of("暂未触发硬性拦截"),
                List.of("拨备和息差仍需复核"),
                List.of("监管处罚/问询函/诉讼/质押/减持全文")
        );
        return new AgentConsensusReport(
                "600036",
                "招商银行",
                "WATCH",
                "可观察",
                new BigDecimal("76"),
                "多数 Agent 支持，反方要求继续复核息差和资产质量。",
                3,
                2,
                0,
                0,
                List.of(support, contrarian),
                List.of("估值安全边际可见"),
                List.of("拨备和息差仍需复核"),
                List.of("同业可比估值", "监管处罚/问询函/诉讼/质押/减持全文"),
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }
}
