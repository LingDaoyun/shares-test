package com.aistock.research.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TradingClockServiceTest {

    @Test
    void shouldClassifyClosingAuctionAsRegularCloseDecisionWindow() {
        TradingClockService service = serviceAt("2026-07-08T06:58:00Z");

        TradingSessionSnapshot snapshot = service.currentSession();

        assertThat(snapshot.phase()).isEqualTo("CLOSING_CALL_AUCTION");
        assertThat(snapshot.phaseLabel()).isEqualTo("收盘集合竞价");
        assertThat(snapshot.regularAuctionOpen()).isTrue();
        assertThat(snapshot.closingDecisionWindow()).isTrue();
        assertThat(snapshot.postCloseFixedPrice()).isFalse();
        assertThat(snapshot.warnings()).anySatisfy(warning -> assertThat(warning).contains("14:57-15:00"));
    }

    @Test
    void shouldSeparatePostCloseFixedPriceFromRegularTailSignal() {
        TradingClockService service = serviceAt("2026-07-08T07:20:00Z");

        TradingSessionSnapshot snapshot = service.currentSession();

        assertThat(snapshot.phase()).isEqualTo("POST_CLOSE_FIXED_PRICE");
        assertThat(snapshot.phaseLabel()).isEqualTo("盘后固定价格");
        assertThat(snapshot.regularAuctionOpen()).isFalse();
        assertThat(snapshot.closingDecisionWindow()).isFalse();
        assertThat(snapshot.postCloseFixedPrice()).isTrue();
        assertThat(snapshot.warnings()).anySatisfy(warning -> assertThat(warning).contains("不能和普通尾盘买点混用"));
    }

    @Test
    void shouldTreatWeekendAsClosedEvenDuringNormalTradingHours() {
        TradingClockService service = serviceAt("2026-07-11T02:00:00Z");

        TradingSessionSnapshot snapshot = service.currentSession();

        assertThat(snapshot.phase()).isEqualTo("MARKET_CLOSED_DAY");
        assertThat(snapshot.regularAuctionOpen()).isFalse();
        assertThat(snapshot.warnings()).anySatisfy(warning -> assertThat(warning).contains("周末"));
    }

    @Test
    void shouldTreatOfficialExchangeHolidayAsClosed() {
        TradingClockService service = serviceAt("2026-06-19T02:00:00Z");

        TradingSessionSnapshot snapshot = service.currentSession();

        assertThat(snapshot.phase()).isEqualTo("MARKET_CLOSED_DAY");
        assertThat(snapshot.phaseLabel()).contains("休市");
        assertThat(snapshot.regularAuctionOpen()).isFalse();
    }

    private TradingClockService serviceAt(String instant) {
        return new TradingClockService(Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }
}
