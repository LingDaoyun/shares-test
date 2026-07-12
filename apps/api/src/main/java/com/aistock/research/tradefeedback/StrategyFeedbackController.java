package com.aistock.research.tradefeedback;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/strategy-feedback")
public class StrategyFeedbackController {

    private final StrategyFeedbackService strategyFeedbackService;

    public StrategyFeedbackController(StrategyFeedbackService strategyFeedbackService) {
        this.strategyFeedbackService = strategyFeedbackService;
    }

    @GetMapping
    public List<StrategyFeedbackSummary> summaries() {
        return strategyFeedbackService.summaries();
    }
}
