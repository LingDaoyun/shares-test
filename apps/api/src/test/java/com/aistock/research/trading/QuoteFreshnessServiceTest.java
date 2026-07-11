package com.aistock.research.trading;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteFreshnessServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant TRADING_NOW = Instant.parse("2026-07-10T06:45:00Z");

    @Test
    void acceptsRecentSameDayQuoteDuringContinuousTrading() {
        QuoteFreshnessService service = serviceAt(TRADING_NOW);

        QuoteFreshnessSnapshot result = service.evaluate(quote(
                LocalDate.parse("2026-07-10"),
                Instant.parse("2026-07-10T06:43:00Z")
        ));

        assertThat(result.status()).isEqualTo("FRESH");
        assertThat(result.blocksRealtimeDecision()).isFalse();
        assertThat(result.ageSeconds()).isEqualTo(120);
    }

    @Test
    void blocksOldPreviousDayAndMissingTimestampDuringTrading() {
        QuoteFreshnessService service = serviceAt(TRADING_NOW);

        assertThat(service.evaluate(quote(
                LocalDate.parse("2026-07-10"),
                Instant.parse("2026-07-10T06:20:00Z")
        )).status()).isEqualTo("STALE");
        assertThat(service.evaluate(quote(
                LocalDate.parse("2026-07-09"),
                Instant.parse("2026-07-09T07:00:00Z")
        )).status()).isEqualTo("STALE_TRADING_DAY");
        assertThat(service.evaluate(quote(null, null)).status()).isEqualTo("TIMESTAMP_MISSING");

        assertThat(service.evaluate(quote(null, null)).blocksRealtimeDecision()).isTrue();
    }

    @Test
    void keepsLastCloseAsHistoricalSnapshotOnMarketClosedDay() {
        QuoteFreshnessService service = serviceAt(Instant.parse("2026-07-11T03:00:00Z"));

        QuoteFreshnessSnapshot result = service.evaluate(quote(
                LocalDate.parse("2026-07-10"),
                Instant.parse("2026-07-10T07:00:00Z")
        ));

        assertThat(result.status()).isEqualTo("MARKET_CLOSED_SNAPSHOT");
        assertThat(result.blocksRealtimeDecision()).isFalse();
    }

    private QuoteFreshnessService serviceAt(Instant instant) {
        Clock clock = Clock.fixed(instant, SHANGHAI);
        return new QuoteFreshnessService(new TradingClockService(clock), clock);
    }

    private EastMoneyQuote quote(LocalDate tradeDate, Instant marketTimestamp) {
        return new EastMoneyQuote(
                "600000", "浦发银行", "SH", "银行",
                new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("1.00"),
                new BigDecimal("1000000"), new BigDecimal("100000000"),
                new BigDecimal("6.00"), new BigDecimal("0.70"), new BigDecimal("6.00"),
                "行情源", null, TRADING_NOW, tradeDate, marketTimestamp
        );
    }
}
