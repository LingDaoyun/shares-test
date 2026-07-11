package com.aistock.research.selection;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/selection")
public class StockSelectionController {

    private final StockSelectionService stockSelectionService;

    public StockSelectionController(StockSelectionService stockSelectionService) {
        this.stockSelectionService = stockSelectionService;
    }

    @GetMapping("/agent-shortlist")
    public StockSelectionReport shortlist(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer reviewLimit
    ) {
        return stockSelectionService.shortlist(limit, reviewLimit);
    }
}
