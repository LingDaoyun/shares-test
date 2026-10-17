package com.aistock.research.fiveday;

import com.aistock.research.trading.TradingClockService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Source-only acceptance examples; not executed by Codex. */
class FiveDayTradeScheduleTest {
    @Test
    void countsTheRealBuyDayAsOneAndSkipsTheWeekend() {
        var schedule = serviceAt("2026-09-08T08:00:00Z").tradeSchedule(LocalDate.parse("2026-09-04"));
        assertEquals(LocalDate.parse("2026-09-10"), schedule.plannedExitDate());
        assertEquals(3, schedule.holdingDay());
        assertEquals("HOLDING", schedule.status());
    }

    @Test
    void skipsMidAutumnAndNationalDayMarketHolidays() {
        var schedule = serviceAt("2026-09-24T08:00:00Z").tradeSchedule(LocalDate.parse("2026-09-24"));
        assertEquals(LocalDate.parse("2026-10-08"), schedule.plannedExitDate());
        assertEquals(1, schedule.holdingDay());
    }

    @Test
    void dueAndOverdueDoNotClaimAnActualSale() {
        var entry = LocalDate.parse("2026-09-07");
        var due = serviceAt("2026-09-11T08:00:00Z").tradeSchedule(entry);
        var overdue = serviceAt("2026-09-14T08:00:00Z").tradeSchedule(entry);
        assertEquals("DUE", due.status());
        assertEquals(5, due.holdingDay());
        assertEquals("OVERDUE", overdue.status());
        assertEquals(6, overdue.holdingDay());
    }

    @Test
    void refusesAnUnverifiedCalendarAndNonTradingBuyDate() {
        var service = serviceAt("2026-12-30T08:00:00Z");
        assertEquals("CALENDAR_UNAVAILABLE", service.tradeSchedule(LocalDate.parse("2026-12-30")).status());
        assertEquals("INVALID_DATE", service.tradeSchedule(LocalDate.parse("2026-09-06")).status());
    }

    private FiveDayService serviceAt(String instant) {
        return new FiveDayService(null, null, null, new TradingClockService(
                Clock.fixed(Instant.parse(instant), TradingClockService.CHINA_MARKET_ZONE)), null, null, null);
    }
}
