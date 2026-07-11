package com.aistock.research.universe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/universe/screen")
public class UniversalScreenController {

    private final UniversalAshareScreener screener;

    public UniversalScreenController(UniversalAshareScreener screener) {
        this.screener = screener;
    }

    @GetMapping("/report")
    public UniversalScreenReport report(
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
        return screener.screen(new UniversalScreenRequest(
                limit,
                scanLimit,
                minAmount,
                maxPe,
                maxPb,
                minFinancialScore,
                excludeSideways,
                includeNorthExchange,
                mode
        ));
    }
}
