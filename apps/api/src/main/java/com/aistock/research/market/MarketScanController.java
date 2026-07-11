package com.aistock.research.market;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/market-scan")
public class MarketScanController {

    private final MarketScanService marketScanService;

    public MarketScanController(MarketScanService marketScanService) {
        this.marketScanService = marketScanService;
    }

    @GetMapping("/report")
    public MarketScanReport report(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer scanLimit,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxPe,
            @RequestParam(required = false) BigDecimal maxPb,
            @RequestParam(required = false) BigDecimal minFinancialScore,
            @RequestParam(required = false) Boolean excludeSideways,
            @RequestParam(required = false) Boolean includeNorthExchange,
            @RequestParam(required = false) String mode
    ) {
        return marketScanService.report(
                limit,
                scanLimit,
                minAmount,
                maxPe,
                maxPb,
                minFinancialScore,
                excludeSideways,
                includeNorthExchange,
                mode
        );
    }
}
