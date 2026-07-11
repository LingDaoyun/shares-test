package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOutcomeSchedulerTest {

    @Test
    void runsAt1810ShanghaiOnWeekdays() throws Exception {
        Scheduled scheduled = TradeOutcomeScheduler.class
                .getDeclaredMethod("refreshOpenCases")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 10 18 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
    }
}
