package com.aistock.research.quality;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/recommendation-evidence")
public class RecommendationEvidenceController {

    private final RecommendationEvidenceEnrichmentService enrichmentService;

    public RecommendationEvidenceController(RecommendationEvidenceEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    @GetMapping
    public RecommendationEvidenceBundle evidence(@PathVariable String symbol) {
        return enrichmentService.enrichForInteractiveCard(symbol);
    }
}
