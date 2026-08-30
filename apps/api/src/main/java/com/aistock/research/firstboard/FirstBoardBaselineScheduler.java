package com.aistock.research.firstboard;

import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
class FirstBoardBaselineScheduler {

    private static final Logger log = LoggerFactory.getLogger(FirstBoardBaselineScheduler.class);

    private final FirstBoardBaselineService baselineService;
    private final FirstBoardMarketDayIngestionService ingestionService;
    private final FirstBoardRetentionService retentionService;
    private final TradingClockService clock;

    FirstBoardBaselineScheduler(
            FirstBoardBaselineService baselineService,
            FirstBoardMarketDayIngestionService ingestionService,
            FirstBoardRetentionService retentionService,
            TradingClockService clock
    ) {
        this.baselineService = baselineService;
        this.ingestionService = ingestionService;
        this.retentionService = retentionService;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reconcileOnStartup() {
        FirstBoardBaselineAccess access = baselineService.access(effectiveScanDate());
        cleanupIfReady(access);
    }

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Shanghai")
    void ingestCloseAndPrepareNextTradingDay() {
        LocalDate today = clock.currentMarketDate();
        FirstBoardIngestionProgress progress = ingestionService.ingestClosedDay(today);
        if (progress.status() == FirstBoardMarketDayStatus.READY) {
            cleanupIfReady(baselineService.access(clock.nextTradingDay(today)));
        }
    }

    @Scheduled(cron = "0 */5 15-23 * * MON-FRI", zone = "Asia/Shanghai")
    void finishPublicationAndCleanup() {
        LocalDate today = clock.currentMarketDate();
        LocalDate scanDate = clock.isCompletedDailyBar(today) && !clock.isMarketClosedDay(today)
                ? clock.nextTradingDay(today) : effectiveScanDate();
        cleanupIfReady(baselineService.access(scanDate));
    }

    private LocalDate effectiveScanDate() {
        LocalDate today = clock.currentMarketDate();
        return clock.isMarketClosedDay(today) ? clock.previousTradingDay(today) : today;
    }

    private void cleanupIfReady(FirstBoardBaselineAccess access) {
        if (access.status() != FirstBoardAlertStatus.READY) {
            return;
        }
        try {
            retentionService.cleanup();
        } catch (RuntimeException exception) {
            log.warn("首板历史数据清理失败，将在下次对账重试: {}", bounded(exception.getMessage()));
        }
    }

    private String bounded(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
