package com.aistock.research.stocksearch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/stocks")
public class StockSearchController {
    private final StockSearchService service;

    public StockSearchController(StockSearchService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public StockSearchResult search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "false") boolean excludeChiNext,
            @RequestParam(defaultValue = "false") boolean excludeStar,
            @RequestParam(defaultValue = "false") boolean excludeBeijing,
            @RequestParam(defaultValue = "false") boolean excludeSt,
            @RequestParam(required = false) BigDecimal minChangePercent,
            @RequestParam(required = false) BigDecimal maxChangePercent,
            @RequestParam(required = false) BigDecimal minMarketCap,
            @RequestParam(required = false) BigDecimal maxMarketCap,
            @RequestParam(required = false) BigDecimal minCirculatingMarketCap,
            @RequestParam(required = false) BigDecimal maxCirculatingMarketCap,
            @RequestParam(required = false) BigDecimal minTurnoverRate,
            @RequestParam(required = false) BigDecimal maxTurnoverRate,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount
    ) {
        return service.search(keyword, refresh, new StockSearchFilters(excludeChiNext, excludeStar, excludeBeijing, excludeSt,
                minChangePercent, maxChangePercent, minMarketCap, maxMarketCap, minCirculatingMarketCap, maxCirculatingMarketCap,
                minTurnoverRate, maxTurnoverRate, minPrice, maxPrice, minAmount, maxAmount));
    }
}
