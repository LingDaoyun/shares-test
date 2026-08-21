package com.aistock.research.shortterm.leader;

import com.aistock.research.shortterm.ShortTermService;
import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Component
public class ShortTermLeaderCheckpointScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShortTermLeaderCheckpointScheduler.class);

    private final ShortTermService shortTermService;
    private final TradingClockService tradingClockService;
    private final ShortTermLeaderSnapshotStore snapshotStore;

    public ShortTermLeaderCheckpointScheduler(
            ShortTermService shortTermService,
            TradingClockService tradingClockService,
            ShortTermLeaderSnapshotStore snapshotStore
    ) {
        this.shortTermService = shortTermService;
        this.tradingClockService = tradingClockService;
        this.snapshotStore = snapshotStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void captureMissedCheckpointAfterStartup() {
        LocalDateTime marketNow = tradingClockService.currentMarketDateTime();
        LocalTime expectedCheckpoint = latestExpectedCheckpoint(marketNow.toLocalTime());
        if (expectedCheckpoint == null
                || tradingClockService.isMarketClosedDay(marketNow.toLocalDate())) {
            return;
        }
        Instant expectedFloor = LocalDateTime.of(
                        marketNow.toLocalDate(), expectedCheckpoint.minusMinutes(2))
                .atZone(TradingClockService.CHINA_MARKET_ZONE)
                .toInstant();
        Instant now = marketNow.atZone(TradingClockService.CHINA_MARKET_ZONE).toInstant();
        boolean checkpointAlreadyCaptured = snapshotStore.latestSameDayBefore(
                        DefaultShortTermLeaderRiskModule.RULE_VERSION,
                        marketNow.toLocalDate(),
                        now.plusSeconds(1))
                .map(snapshot -> !snapshot.capturedAt().isBefore(expectedFloor))
                .orElse(false);
        if (!checkpointAlreadyCaptured) {
            capture("启动补采");
        }
    }

    @Scheduled(cron = "0 50 9 * * MON-FRI", zone = "Asia/Shanghai")
    public void captureOpenCheckpoint() {
        capture("09:50");
    }

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Shanghai")
    public void captureMorningCloseCheckpoint() {
        capture("11:30");
    }

    @Scheduled(cron = "0 40 14 * * MON-FRI", zone = "Asia/Shanghai")
    public void captureLateSessionCheckpoint() {
        capture("14:40");
    }

    private void capture(String checkpointLabel) {
        LocalDate tradeDate = tradingClockService.currentMarketDate();
        if (tradeDate == null || tradingClockService.isMarketClosedDay(tradeDate)) {
            return;
        }
        try {
            ShortTermLeaderRisk risk = shortTermService.captureLeaderRiskCheckpoint();
            log.info(
                    "Short-term leader checkpoint captured, checkpoint={}, tradeDate={}, status={}",
                    checkpointLabel, tradeDate, risk.status());
        } catch (RuntimeException exception) {
            log.warn(
                    "Short-term leader checkpoint capture failed, checkpoint={}, tradeDate={}",
                    checkpointLabel, tradeDate, exception);
        }
    }

    private LocalTime latestExpectedCheckpoint(LocalTime now) {
        if (!now.isBefore(LocalTime.of(14, 40)) && now.isBefore(LocalTime.of(15, 0))) {
            return LocalTime.of(14, 40);
        }
        if (!now.isBefore(LocalTime.of(11, 30)) && now.isBefore(LocalTime.of(14, 40))) {
            return LocalTime.of(11, 30);
        }
        if (!now.isBefore(LocalTime.of(9, 50)) && now.isBefore(LocalTime.of(11, 30))) {
            return LocalTime.of(9, 50);
        }
        return null;
    }
}
