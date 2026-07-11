package com.aistock.research.valuation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{symbol}/valuation-history")
public class ValuationHistoryController {

    private final ValuationHistoryService valuationHistoryService;

    public ValuationHistoryController(ValuationHistoryService valuationHistoryService) {
        this.valuationHistoryService = valuationHistoryService;
    }

    @GetMapping
    public ValuationHistoryReport history(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10") int years
    ) {
        return valuationHistoryService.history(symbol, Math.max(1, Math.min(years, 10)));
    }
}
