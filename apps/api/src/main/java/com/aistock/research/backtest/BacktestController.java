package com.aistock.research.backtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/backtests")
public class BacktestController {

    private final BacktestService backtestService;

    public BacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @GetMapping("/right-side")
    public BacktestReport rightSide(
            @RequestParam(required = false) String symbols,
            @RequestParam(required = false) Integer lookbackDays,
            @RequestParam(required = false) Integer holdingDays,
            @RequestParam(required = false) BigDecimal minVolumeRatio,
            @RequestParam(required = false) BigDecimal maxVolumeRatio,
            @RequestParam(required = false) BigDecimal maxDistanceToMa20,
            @RequestParam(required = false) BigDecimal stopLossPercent,
            @RequestParam(required = false) BigDecimal takeProfitPercent,
            @RequestParam(required = false) BigDecimal commissionPercent,
            @RequestParam(required = false) BigDecimal stampDutyPercent,
            @RequestParam(required = false) BigDecimal slippagePercent,
            @RequestParam(required = false) BigDecimal limitMovePercent
    ) {
        return backtestService.rightSideBacktest(
                symbols,
                lookbackDays,
                holdingDays,
                minVolumeRatio,
                maxVolumeRatio,
                maxDistanceToMa20,
                stopLossPercent,
                takeProfitPercent,
                commissionPercent,
                stampDutyPercent,
                slippagePercent,
                limitMovePercent
        );
    }
}
