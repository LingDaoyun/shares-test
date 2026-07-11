package com.aistock.research.decision;

import com.aistock.research.history.ResearchHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/investment-decision")
public class InvestmentDecisionController {

    private final InvestmentDecisionService investmentDecisionService;
    private final ResearchHistoryService researchHistoryService;

    public InvestmentDecisionController(
            InvestmentDecisionService investmentDecisionService,
            ResearchHistoryService researchHistoryService
    ) {
        this.investmentDecisionService = investmentDecisionService;
        this.researchHistoryService = researchHistoryService;
    }

    @GetMapping
    public InvestmentDecisionReport getDecision(@PathVariable String symbol) {
        return investmentDecisionService.evaluate(symbol);
    }

    @PostMapping("/run")
    public InvestmentDecisionReport runDecision(@PathVariable String symbol) {
        InvestmentDecisionReport report = investmentDecisionService.evaluate(symbol);
        researchHistoryService.recordDecision(report, "MANUAL_RESEARCH");
        return report;
    }
}
