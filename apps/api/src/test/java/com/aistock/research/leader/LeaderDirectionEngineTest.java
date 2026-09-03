package com.aistock.research.leader;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermBrokenBoardStock;
import com.aistock.research.shortterm.ShortTermLimitUpStock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderDirectionEngineTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-09-03");
    private final LeaderDirectionEngine directionEngine = new LeaderDirectionEngine();

    @Test
    void ranksBroadActiveLimitUpIndustryFirst() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            quotes.add(quote("60000" + index, "半导体", 6 + index, 100 + index * 10));
            quotes.add(quote("60100" + index, "医药", index - 2, 30 + index));
        }
        List<ShortTermLimitUpStock> sealed = List.of(
                sealed("600000", "半导体", 1),
                sealed("600001", "半导体", 2),
                sealed("600002", "半导体", 3),
                sealed("601004", "医药", 1));
        List<ShortTermBrokenBoardStock> broken = List.of(new ShortTermBrokenBoardStock(
                "600003", "半导体股3", "半导体", bd("10.50"), bd("11.00"), bd("5.00"),
                bd("90000000"), LocalTime.of(10, 30), 1, false));

        List<PotentialLeaderSnapshot.Direction> ranked = directionEngine.rank(quotes, sealed, broken);

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).industry()).isEqualTo("半导体");
        assertThat(ranked.get(0).rank()).isEqualTo(1);
        assertThat(ranked.get(0).constituentSymbols()).hasSize(5);
    }

    @Test
    void excludesIndustryWithFewerThanFiveTradableMembers() {
        List<EastMoneyQuote> fourMemberIndustry = List.of(
                quote("600001", "半导体", 5, 100),
                quote("600002", "半导体", 5, 100),
                quote("600003", "半导体", 5, 100),
                quote("600004", "半导体", 5, 100));

        assertThat(directionEngine.rank(fourMemberIndustry, List.of(), List.of())).isEmpty();
    }

    private EastMoneyQuote quote(String symbol, String industry, int change, int amountMillions) {
        Instant quoteTime = Instant.parse("2026-09-03T06:30:00Z");
        return new EastMoneyQuote(
                symbol, industry + symbol, "沪深", industry,
                bd("10.50"), bd(Integer.toString(change)), bd("5.00"), bd("1000000"),
                bd(Integer.toString(amountMillions)).multiply(bd("1000000")), null, null, null,
                "test", "https://example.test", quoteTime, TRADE_DATE, quoteTime,
                bd("12000000000"), bd("10.00"), bd("10.80"), bd("9.90"), bd("1.50"),
                bd("8000000000"));
    }

    private ShortTermLimitUpStock sealed(String symbol, String industry, int boards) {
        return new ShortTermLimitUpStock(
                symbol, industry + symbol, industry, bd("11.00"), bd("10.00"), bd("100000000"),
                bd("8.00"), boards, boards, boards, bd("10000000"),
                LocalTime.of(9, 40), LocalTime.of(9, 40), 0);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
