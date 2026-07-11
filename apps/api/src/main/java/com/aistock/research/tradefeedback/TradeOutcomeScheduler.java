package com.aistock.research.tradefeedback;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TradeOutcomeScheduler {

    private final TradeOutcomeService outcomeService;

    public TradeOutcomeScheduler(TradeOutcomeService outcomeService) {
        this.outcomeService = outcomeService;
    }

    @Scheduled(cron = "0 10 18 * * MON-FRI", zone = "Asia/Shanghai")
    public void refreshOpenCases() {
        outcomeService.refreshOpenCases();
    }
}
