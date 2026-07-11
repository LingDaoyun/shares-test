package com.aistock.research.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
class TrendAnalysisArchiveServiceTest {

    @Autowired
    private TrendAnalysisRunRepository repository;

    private TrendRequestEnrichmentService enrichmentService;
    private LlmTrendAnalysisService llmTrendAnalysisService;
    private TrendAnalysisArchiveService archiveService;

    @BeforeEach
    void setUp() {
        enrichmentService = Mockito.mock(TrendRequestEnrichmentService.class);
        llmTrendAnalysisService = Mockito.mock(LlmTrendAnalysisService.class);
        archiveService = new TrendAnalysisArchiveService(
                repository,
                enrichmentService,
                new TrendPromptService(),
                llmTrendAnalysisService,
                new ObjectMapper()
        );
    }

    @Test
    void reusesSameDayArchivedAnalysis() {
        TrendPromptRequest request = new TrendPromptRequest(
                "测试规划",
                "政府规划文件",
                "测试机构",
                "2026-06-16",
                "https://example.com/policy",
                "推动数字基础设施与高端装备协同发展。",
                List.of("数字基础设施"),
                List.of("示例公司")
        );

        when(enrichmentService.enrich(any())).thenReturn(request);
        when(llmTrendAnalysisService.analyze(request)).thenReturn(new TrendAnalysisResponse(
                null,
                false,
                "deepseek",
                "deepseek-v4-pro",
                "policy-industry-hidden-trend-analysis",
                "v1.1.0",
                "resp-1",
                new ObjectMapper().valueToTree(Map.of(
                        "overall_assessment", Map.of(
                                "summary", "趋势成立",
                                "confidence", 88,
                                "next_action", "进入公司池筛选"
                        )
                )),
                Map.of("total_tokens", 1234),
                Instant.parse("2026-06-16T03:45:47Z")
        ));

        TrendAnalysisResponse first = archiveService.analyze(request);
        TrendAnalysisResponse second = archiveService.analyze(request);

        assertThat(first.recordId()).isNotNull();
        assertThat(first.cached()).isFalse();
        assertThat(second.recordId()).isEqualTo(first.recordId());
        assertThat(second.cached()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
        verify(llmTrendAnalysisService, times(1)).analyze(request);
    }

    @Test
    void exposesRecentHistoryFromArchive() {
        TrendPromptRequest request = new TrendPromptRequest(
                "测试规划",
                "政府规划文件",
                "测试机构",
                "2026-06-16",
                "https://example.com/policy",
                "推动数字基础设施与高端装备协同发展。",
                List.of("数字基础设施"),
                List.of("示例公司")
        );

        when(enrichmentService.enrich(any())).thenReturn(request);
        when(llmTrendAnalysisService.analyze(request)).thenReturn(new TrendAnalysisResponse(
                null,
                false,
                "deepseek",
                "deepseek-v4-pro",
                "policy-industry-hidden-trend-analysis",
                "v1.1.0",
                "resp-2",
                new ObjectMapper().valueToTree(Map.of(
                        "overall_assessment", Map.of(
                                "summary", "归档摘要",
                                "confidence", 76,
                                "next_action", "继续收集证据"
                        )
                )),
                Map.of("total_tokens", 888),
                Instant.parse("2026-06-16T06:00:00Z")
        ));

        archiveService.analyze(request);

        List<TrendAnalysisHistoryItem> history = archiveService.listHistory(5);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).documentTitle()).isEqualTo("测试规划");
        assertThat(history.get(0).overallSummary()).isEqualTo("归档摘要");
        assertThat(history.get(0).nextAction()).isEqualTo("继续收集证据");
    }
}
