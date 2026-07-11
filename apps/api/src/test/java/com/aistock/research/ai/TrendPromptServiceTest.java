package com.aistock.research.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendPromptServiceTest {

    private final TrendPromptService service = new TrendPromptService();

    @Test
    void buildsEvidenceFirstTrendPrompt() {
        TrendPromptPreview preview = service.preview(new TrendPromptRequest(
                "测试规划",
                "政府规划文件",
                "测试机构",
                "2026-06-14",
                "https://example.com/policy",
                "推动数字基础设施与高端装备协同发展。",
                List.of("数字基础设施"),
                List.of("示例公司")
        ));

        assertThat(preview.name()).isEqualTo("policy-industry-hidden-trend-analysis");
        assertThat(preview.modelInstruction()).contains("证据优先", "只输出 JSON");
        assertThat(preview.userPrompt()).contains("测试规划", "推动数字基础设施与高端装备协同发展");
        assertThat(preview.outputSchema()).containsKeys(
                "explicit_signals",
                "hidden_trends",
                "counter_evidence",
                "company_research_tasks"
        );
        assertThat(service.structuredOutputSchema()).containsEntry("type", "object");
        assertThat(preview.guardrails()).anyMatch(item -> item.contains("不得编造"));
    }
}
