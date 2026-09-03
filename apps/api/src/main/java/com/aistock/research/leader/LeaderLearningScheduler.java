package com.aistock.research.leader;

import com.aistock.research.trading.TradingClockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
class LeaderLearningScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaderLearningScheduler.class);

    private final LeaderLearningService learningService;
    private final PotentialLeaderService snapshotService;
    private final TradingClockService clock;

    LeaderLearningScheduler(
            LeaderLearningService learningService,
            PotentialLeaderService snapshotService,
            TradingClockService clock
    ) {
        this.learningService = learningService;
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 */10 15-23 * * MON-FRI", zone = "Asia/Shanghai")
    void captureCloseIfMissing() {
        LocalDate tradeDate = clock.currentMarketDate();
        if (clock.isMarketClosedDay(tradeDate)
                || learningService.hasSnapshot(tradeDate, LeaderRuleSet.RULE_VERSION)) {
            return;
        }
        try {
            learningService.capture(snapshotService.snapshot(false, false));
        } catch (RuntimeException exception) {
            log.warn("潜力龙头收盘样本保存失败，将在下一轮重试：{}", rootMessage(exception));
        }
    }

    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "Asia/Shanghai")
    void matureOutcomes() {
        try {
            learningService.refreshPending(clock.currentMarketDate());
        } catch (RuntimeException exception) {
            log.warn("潜力龙头历史结果刷新失败，将在下一交易日重试：{}", rootMessage(exception));
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
