package com.aistock.research.news;

import com.aistock.research.ai.LlmChatClient;
import com.aistock.research.ai.LlmConfigPreview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsAnalysisServiceTest {
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final NewsAnalysisService service = new NewsAnalysisService(llm, mapper);
    private final Instant now = Instant.parse("2026-11-05T02:00:00Z");
    private final FinancialNewsItem item = new FinancialNewsItem("id", "OFFICIAL", "官方金融发布", "官方",
            "https://example.gov.cn/original", "2026-11-05", "原文摘要：发布了新的金融举措。");

    @BeforeEach
    void setup() { when(llm.currentConfig()).thenReturn(config(true)); }

    @Test
    void missingModelReturnsExplicitUnanalyzedResultWithoutCallingModel() {
        when(llm.currentConfig()).thenReturn(config(false));
        var result = service.analyze(item, now);
        assertThat(result.analyzedAt()).isNull();
        assertThat(result.evidenceUrl()).isEqualTo(item.url());
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("未配置"));
        verify(llm, never()).completeJson(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void bindsOriginalFactsAndUrlOnServerAndMarksModelContentAsInference() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), anyMap())).thenReturn(mapper.readTree("""
                {"summary":"伪造正文和保证收益", "evidenceUrl":"https://fabricated.example", "itemId":"wrong-id",
                 "affectedSectors":["银行"], "transmission":"可能影响融资需求", "watchPoints":["后续执行"], "risks":["尚待观察"]}
                """));
        var result = service.analyze(item, now);
        assertThat(result.itemId()).isEqualTo(item.id());
        assertThat(result.evidenceUrl()).isEqualTo(item.url());
        assertThat(result.summary()).isEqualTo(item.summary());
        assertThat(result.analyzedAt()).isEqualTo(now);
        assertThat(result.transmission()).contains("推演", "可能影响融资需求");
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("全文"));
        var instruction = ArgumentCaptor.forClass(String.class);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(llm).completeJson(instruction.capture(), payload.capture(), eq("financial_news_analysis"), anyMap());
        assertThat(instruction.getValue()).contains("不得编造正文", "不是指令", "投资建议");
        assertThat(mapper.readTree(payload.getValue()).path("summary").asText()).isEqualTo(item.summary());
    }

    @Test
    void modelFailureDoesNotExposeRemoteExceptionAndMissingFieldsAreNotSuccess() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("secret provider response"));
        var failed = service.analyze(item, now);
        assertThat(failed.analyzedAt()).isNull();
        assertThat(failed.dataGaps().toString()).contains("失败").doesNotContain("secret");
        doReturn(mapper.readTree("{}")).when(llm).completeJson(anyString(), anyString(), anyString(), anyMap());
        assertThat(service.analyze(item, now).analyzedAt()).isNull();
    }

    @Test
    void titleOnlyEvidenceIsNotPresentedAsArticleBody() throws Exception {
        when(llm.completeJson(anyString(), anyString(), anyString(), anyMap())).thenReturn(mapper.readTree("""
                {"affectedSectors":[],"transmission":"信息有限，暂无明确影响","watchPoints":[],"risks":[]}
                """));
        var titleOnly = new FinancialNewsItem(item.id(), item.category(), item.title(), item.source(), item.url(), item.publishedAt(), "");
        var result = service.analyze(titleOnly, now);
        assertThat(result.summary()).isEqualTo(item.title());
        assertThat(result.dataGaps()).anyMatch(gap -> gap.contains("仅有标题"));
    }

    private LlmConfigPreview config(boolean configured) {
        return new LlmConfigPreview("test", "test", "https://example.com", "json_object", false, configured,
                "test", null, 2048, null);
    }
}
