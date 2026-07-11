package com.aistock.research.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/llm-config")
public class LlmConfigController {

    private final LlmTrendAnalysisService llmTrendAnalysisService;

    public LlmConfigController(LlmTrendAnalysisService llmTrendAnalysisService) {
        this.llmTrendAnalysisService = llmTrendAnalysisService;
    }

    @GetMapping
    public LlmConfigPreview currentConfig() {
        return llmTrendAnalysisService.currentConfig();
    }
}
