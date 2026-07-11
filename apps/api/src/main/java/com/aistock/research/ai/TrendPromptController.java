package com.aistock.research.ai;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/trend-prompts")
public class TrendPromptController {

    private final TrendPromptService trendPromptService;
    private final TrendRequestEnrichmentService trendRequestEnrichmentService;

    public TrendPromptController(
            TrendPromptService trendPromptService,
            TrendRequestEnrichmentService trendRequestEnrichmentService
    ) {
        this.trendPromptService = trendPromptService;
        this.trendRequestEnrichmentService = trendRequestEnrichmentService;
    }

    @GetMapping("/sample")
    public TrendPromptPreview sample() {
        return trendPromptService.preview(new TrendPromptRequest(
                "国务院关于印发《现代化应急体系建设“十五五”规划》的通知",
                "政府规划文件",
                "国务院",
                "2026-06-08",
                "https://www.gov.cn/zhengce/content/202606/content_7071451.htm",
                "围绕现代化应急体系建设，分析安全韧性、数字化、装备升级、基层治理和产业支撑可能形成的长期趋势。",
                List.of("新质生产力", "高端制造", "数字基础设施", "公共安全"),
                List.of("浪潮信息", "东方电子", "中际旭创")
        ));
    }

    @PostMapping("/preview")
    public TrendPromptPreview preview(@Valid @RequestBody TrendPromptRequest request) {
        return trendPromptService.preview(trendRequestEnrichmentService.enrich(request));
    }
}
