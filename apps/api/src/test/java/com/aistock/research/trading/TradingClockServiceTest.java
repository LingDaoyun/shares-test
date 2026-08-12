package com.aistock.research.trading;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TradingClockServiceTest {

    @Test
    void shouldClassifyClosingAuctionAsResearchOnlyWindow() {
        TradingClockService service = serviceAt("2026-07-08T06:58:00Z");

        TradingSessionSnapshot snapshot = service.currentSession();

        assertThat(snapshot.phase()).isEqualTo("CLOSING_CALL_AUCTION");
        assertThat(snapshot.phaseLabel()).isEqualTo("收盘集合竞价");
        assertThat(snapshot.regularAuctionOpen()).isTrue();
        assertThat(snapshot.closingDecisionWindow()).isFalse();
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

    @Test
    void exposesExecutableCheckpointOnlyBeforeClosingAuction() {
        assertThat(serviceAt("2026-07-23T06:44:59Z").shortTermDecisionCheckpoint())
                .startsWith("NOT_CONFIRMED:");
        assertThat(serviceAt("2026-07-23T06:45:00Z").shortTermDecisionCheckpoint())
                .isEqualTo("TAIL_ENTRY_1445_1449");
        assertThat(serviceAt("2026-07-23T06:49:59Z").shortTermDecisionCheckpoint())
                .isEqualTo("TAIL_ENTRY_1445_1449");
        assertThat(serviceAt("2026-07-23T06:49:59.999999999Z").shortTermDecisionCheckpoint())
                .isEqualTo("TAIL_ENTRY_1445_1449");
        assertThat(serviceAt("2026-07-23T06:50:00Z").shortTermDecisionCheckpoint())
                .startsWith("NOT_CONFIRMED:");
        assertThat(serviceAt("2026-07-23T07:20:00Z").shortTermDecisionCheckpoint())
                .startsWith("NOT_CONFIRMED:");
        assertThat(serviceAt("2026-07-25T06:50:00Z").shortTermDecisionCheckpoint())
                .startsWith("NOT_CONFIRMED:");
    }

    @Test
    void advancesAcrossWeekendsAndOfficialExchangeHolidays() {
        TradingClockService service = serviceAt("2026-06-18T06:50:00Z");

        assertThat(service.currentMarketDate()).isEqualTo(LocalDate.parse("2026-06-18"));
        assertThat(service.nextTradingDay(LocalDate.parse("2026-06-18")))
                .isEqualTo(LocalDate.parse("2026-06-22"));
        assertThat(service.tradingDayAfter(LocalDate.parse("2026-06-18"), 2))
                .isEqualTo(LocalDate.parse("2026-06-23"));
        assertThat(service.tradingDayAfter(LocalDate.parse("2026-06-18"), 0))
                .isEqualTo(LocalDate.parse("2026-06-18"));
        assertThat(service.tradingDayAfter(LocalDate.parse("2026-06-18"), -1))
                .isEqualTo(LocalDate.parse("2026-06-18"));
    }

    @Test
    void completesCurrentDailyBarOnlyAtOrAfterRegularClose() {
        LocalDate tradeDate = LocalDate.parse("2026-07-07");
        TradingClockService beforeClose = serviceAt("2026-07-07T06:59:00Z");
        TradingClockService afterClose = serviceAt("2026-07-07T07:00:01Z");

        assertThat(beforeClose.isCompletedDailyBar(tradeDate)).isFalse();
        assertThat(afterClose.isCompletedDailyBar(tradeDate)).isTrue();
        assertThat(beforeClose.isCompletedDailyBar(tradeDate.minusDays(1))).isTrue();
        assertThat(afterClose.isCompletedDailyBar(tradeDate.plusDays(1))).isFalse();
    }

    private TradingClockService serviceAt(String instant) {
        return new TradingClockService(Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")));
    }
}
