package com.aistock.research.policy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/policy/themes")
public class PolicyThemeController {

    private final PolicyThemeService themeService;
    private final PolicyAiReviewService aiReviewService;

    public PolicyThemeController(
            PolicyThemeService themeService,
            PolicyAiReviewService aiReviewService
    ) {
        this.themeService = themeService;
        this.aiReviewService = aiReviewService;
    }

    @GetMapping
    public List<PolicyTheme> listThemes() {
        return themeService.listThemes();
    }

    @PostMapping("/{themeCode}/ai-review")
    public ResponseEntity<PolicyAiReview> aiReview(@PathVariable String themeCode) {
        try {
            return ResponseEntity.ok(aiReviewService.review(themeCode));
        } catch (NoSuchElementException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
