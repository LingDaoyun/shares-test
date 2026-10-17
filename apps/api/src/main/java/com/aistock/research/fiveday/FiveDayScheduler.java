package com.aistock.research.fiveday;

import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FiveDayScheduler {
    private static final Logger log = LoggerFactory.getLogger(FiveDayScheduler.class);
    private final FiveDayService service;
    private final FiveDayHistoryService history;
    private final TradingClockService clock;
    public FiveDayScheduler(FiveDayService service, FiveDayHistoryService history, TradingClockService clock) {
        this.service = service; this.history = history; this.clock = clock;
    }
    @Scheduled(cron = "0 0,20,40 16-23 * * MON-FRI", zone = "Asia/Shanghai")
    void capture() {
        if (clock.isMarketClosedDay(clock.currentMarketDate())) return;
        try { service.start(false, false, FiveDayModels.VERSION_V2); service.start(false, false); }
        catch (RuntimeException e) { log.warn("五日观察收盘扫描未完成：{}", e.getMessage()); }
    }
    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Shanghai")
    void mature() {
        if (clock.isMarketClosedDay(clock.currentMarketDate())) return;
        try { history.refresh(FiveDayModels.VERSION_V2); history.refresh(); }
        catch (RuntimeException e) { log.warn("五日观察情景结果未更新：{}", e.getMessage()); }
    }
}
