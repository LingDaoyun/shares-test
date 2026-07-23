package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.TradingAdvice;
import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShortTermExecutionWindowTest {

    @Test
    void permitsConfirmedUpgradeAtLastExecutableSecond() {
        TradingAdvice advice = adjustAt(
                "2026-07-23T06:56:59Z",
                baseAdvice("ADD"),
                tailSignal("CONFIRMED"));

        assertThat(advice.action()).isEqualTo("ADD");
    }

    @Test
    void closingAuctionEvidenceCannotUpgradeAt145700() {
        TradingAdvice advice = adjustAt(
                "2026-07-23T06:57:00Z",
                baseAdvice("ADD"),
                tailSignal("CONFIRMED"));

        assertThat(advice.action()).isEqualTo("WAIT");
        assertThat(advice.summary()).contains("研究", "不可新建");
    }

    @Test
    void closeDataCannotUpgradeToLightTrialAt150000() {
        TradingAdvice advice = adjustAt(
                "2026-07-23T07:00:00Z",
                baseAdvice("WAIT"),
                tailSignal("WATCH"));

        assertThat(advice.action()).isEqualTo("WAIT");
        assertThat(advice.summary()).contains("研究", "不可新建");
    }

    private TradingAdvice adjustAt(
            String instant,
            TradingAdvice base,
            ShortTermTailSignal tailSignal
    ) {
        TradingClockService tradingClock = new TradingClockService(
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
        ShortTermService service = new ShortTermService(
                mock(EastMoneyClient.class),
                new EvidenceCompletenessService(),
                tradingClock);
        return ReflectionTestUtils.invokeMethod(
                service,
                "tailAdjustedAdvice",
                base,
                "RIGHT_EARLY_ADD",
                tailSignal);
    }

    private TradingAdvice baseAdvice(String action) {
        return new TradingAdvice(
                action,
                action,
                80,
                "日线候选",
                List.of("右侧结构通过"),
                List.of("遵守止损"));
    }

    private ShortTermTailSignal tailSignal(String status) {
        return new ShortTermTailSignal(
                status,
                status,
                true,
                "2026-07-23",
                "15:00",
                new BigDecimal("10.50"),
                new BigDecimal("10.30"),
                new BigDecimal("0.80"),
                new BigDecimal("0.50"),
                new BigDecimal("0.20"),
                new BigDecimal("30000000"),
                new BigDecimal("5.00"),
                new BigDecimal("80"),
                List.of("收盘证据完整"),
                List.of("不追高"));
    }
}
