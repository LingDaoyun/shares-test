package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShortTermTailSignalTest {
    @ParameterizedTest
    @ValueSource(strings = {"NOT_READY", "STALE_TRADING_DAY", "POST_CLOSE_FIXED_PRICE", "HISTORICAL_ONLY", "WEAK"})
    void pullbackEvidenceCannotHideOrReleaseAnExistingTailBlock(String status) {
        ShortTermTailSignal tail = tail(status, "WEAK".equals(status));
        for (String pullbackStatus : List.of("UNAVAILABLE", "CONFIRMED")) {
            ShortTermIntradayPullbackSignal pullback = pullback(pullbackStatus);
            ShortTermTailSignal gated = tail.withIntradayPullback(pullback);

            assertThat(gated.status()).isEqualTo(status);
            assertThat(gated.statusLabel()).isEqualTo(tail.statusLabel());
            assertThat(gated.actionableTailWindow()).isEqualTo(tail.actionableTailWindow());
            assertThat(gated.intradayPullback()).isEqualTo(pullback);
            assertThat(gated.reasons()).contains("原尾盘原因", "回踩证据");
            assertThat(gated.riskControls()).containsExactly("原尾盘限制");
            assertThat(advice(gated).action()).isEqualTo("WAIT");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED", "WATCH"})
    void otherwiseUsableTailStillRequiresPullbackConfirmation(String status) {
        ShortTermTailSignal tail = tail(status, true);
        ShortTermTailSignal unavailable = tail.withIntradayPullback(pullback("UNAVAILABLE"));
        ShortTermTailSignal waiting = tail.withIntradayPullback(pullback("WATCH"));

        assertThat(unavailable.status()).isEqualTo("PULLBACK_UNAVAILABLE");
        assertThat(waiting.status()).isEqualTo("PULLBACK_WAIT");
        assertThat(advice(unavailable).action()).isEqualTo("WAIT");
        assertThat(advice(waiting).action()).isEqualTo("WAIT");
        assertThat(tail.withIntradayPullback(pullback("CONFIRMED")).status()).isEqualTo(status);
    }

    private TradingAdvice advice(ShortTermTailSignal tail) {
        ShortTermService service = new ShortTermService(mock(EastMoneyClient.class),
                new EvidenceCompletenessService(), new TradingClockService(Clock.fixed(
                Instant.parse("2026-07-23T06:47:00Z"), ZoneId.of("Asia/Shanghai"))));
        TradingAdvice base = new TradingAdvice("LIGHT_TRIAL", "研究候选", 60, "", List.of(), List.of());
        return ReflectionTestUtils.invokeMethod(service, "tailAdjustedAdvice", base,
                "RESEARCH_LIGHT_TRIAL", tail, true);
    }

    private ShortTermTailSignal tail(String status, boolean actionable) {
        return new ShortTermTailSignal(status, "原尾盘状态", actionable, "2026-07-23", "14:46",
                new BigDecimal("10.5"), new BigDecimal("10.3"), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, new BigDecimal("30000000"), new BigDecimal("5"), new BigDecimal("80"),
                List.of("原尾盘原因"), List.of("原尾盘限制"));
    }

    private ShortTermIntradayPullbackSignal pullback(String status) {
        return new ShortTermIntradayPullbackSignal(status, "分时回踩状态", null, null, null, null, null, null,
                List.of("回踩证据"), "UNAVAILABLE".equals(status) ? List.of("缺少连续分钟") : List.of());
    }
}
