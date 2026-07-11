package com.aistock.research.review;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/evidence-review")
public class EvidenceReviewController {

    private final EvidenceReviewService evidenceReviewService;

    public EvidenceReviewController(EvidenceReviewService evidenceReviewService) {
        this.evidenceReviewService = evidenceReviewService;
    }

    @GetMapping
    public EvidenceReviewReport getReview(@PathVariable String symbol) {
        return evidenceReviewService.review(symbol);
    }

    @PostMapping("/run")
    public EvidenceReviewReport runReview(@PathVariable String symbol) {
        return evidenceReviewService.review(symbol);
    }
}
