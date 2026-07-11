package com.aistock.research.financial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/financial-history")
public class FinancialHistoryController {

    private final FinancialHistoryService financialHistoryService;

    public FinancialHistoryController(FinancialHistoryService financialHistoryService) {
        this.financialHistoryService = financialHistoryService;
    }

    @GetMapping
    public FinancialHistoryReport history(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10") int years
    ) {
        return financialHistoryService.history(symbol, Math.max(1, Math.min(years, 10)));
    }
}
