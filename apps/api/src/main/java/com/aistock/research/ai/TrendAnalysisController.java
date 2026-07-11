package com.aistock.research.ai;

import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/trend-analysis")
public class TrendAnalysisController {

    private final TrendAnalysisArchiveService trendAnalysisArchiveService;

    public TrendAnalysisController(TrendAnalysisArchiveService trendAnalysisArchiveService) {
        this.trendAnalysisArchiveService = trendAnalysisArchiveService;
    }

    @PostMapping
    public TrendAnalysisResponse analyze(@Valid @RequestBody TrendPromptRequest request) {
        return trendAnalysisArchiveService.analyze(request);
    }

    @PostMapping("/latest")
    public ResponseEntity<TrendAnalysisResponse> latest(@Valid @RequestBody TrendPromptRequest request) {
        return trendAnalysisArchiveService.findLatestForToday(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/history")
    public List<TrendAnalysisHistoryItem> history(@RequestParam(defaultValue = "20") int limit) {
        return trendAnalysisArchiveService.listHistory(limit);
    }
}
