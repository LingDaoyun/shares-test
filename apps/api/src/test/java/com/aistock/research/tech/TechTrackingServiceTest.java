package com.aistock.research.tech;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechTrackingServiceTest {

    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final TechTrackingService service = new TechTrackingService(eastMoneyClient);

    @Test
    void shouldPromoteRealizedAiHardwareAndCapCrowdedNames() {
        eastMoneyClient.quotes = List.of(
                quote("601138", "工业富联", "33.78", "8.12"),
                quote("002463", "沪电股份", "59.18", "18.58"),
                quote("000977", "浪潮信息", "42.49", "4.62", "-4.00"),
                quote("300308", "中际旭创", "61.75", "40.88"),
                quote("688256", "寒武纪", "247.35", "81.91")
        );

        TechTrackingReport report = service.report(5, null, null, null, null);

        assertThat(report.scope()).isEqualTo("A股科技追踪池");
        assertThat(report.ruleSet().coreMaxPe()).isEqualByComparingTo("80");
        assertThat(report.candidates()).extracting(TechTrackedStock::symbol)
                .contains("601138", "002463", "000977", "300308", "688256");
        TechTrackedStock industrialFii = find(report, "601138");
        assertThat(industrialFii.action()).isIn("WAIT_PULLBACK", "SMALL_TREND");
        assertThat(industrialFii.score().finalScore()).isGreaterThan(new BigDecimal("65"));

        TechTrackedStock inspur = find(report, "000977");
        assertThat(inspur.todayAdvice().action()).isIn("ADD", "HOLD");
        assertThat(inspur.todayAdvice().summary()).containsAnyOf("小仓", "观察");

        TechTrackedStock innolight = find(report, "300308");
        assertThat(innolight.pbRatio()).isEqualByComparingTo("40.88");
        assertThat(innolight.action()).isIn("THEME_ONLY", "SMALL_TREND");
        assertThat(innolight.entryRules()).anyMatch(rule -> rule.contains("小仓"));
    }

    private TechTrackedStock find(TechTrackingReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private EastMoneyQuote quote(String symbol, String name, String pe, String pb) {
        return quote(symbol, name, pe, pb, "0");
    }

    private EastMoneyQuote quote(String symbol, String name, String pe, String pb, String changePercent) {
        return new EastMoneyQuote(
                symbol,
                name,
                "深交所",
                "科技",
                new BigDecimal("10.00"),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal("100000000"),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> quotes = List.of();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchEastMoneyQuotesBySymbols(List<String> symbols, int limit) {
            return quotes;
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            return quotes;
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return List.of();
        }
    }
}
