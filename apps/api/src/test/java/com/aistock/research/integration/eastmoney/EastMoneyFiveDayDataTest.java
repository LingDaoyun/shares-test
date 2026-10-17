package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EastMoneyFiveDayDataTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EastMoneyClient client = new EastMoneyClient(mock(RestClient.class), mapper, null);
    private final Instant fetchedAt = Instant.parse("2026-09-08T08:00:00Z");
    private final String sourceUrl = "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get?secid=1.600000&lmt=120";

    @Test
    void keepsOnlyRequestedDatesWithoutFillingGapsAndDeduplicatesIdenticalRows() {
        var lines = mapper.createArrayNode()
                .add(flow("2026-09-09", "900"))
                .add(flow("2026-09-08", "800"))
                .add(flow("2026-09-04", "400"))
                .add(flow("2026-09-08", "800"))
                .add(flow("2026-08-31", "310"));

        List<EastMoneyFundFlowSnapshot> rows = client.readFundFlowHistory(
                "600000", lines, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-08"),
                fetchedAt, sourceUrl);

        assertThat(rows).extracting(EastMoneyFundFlowSnapshot::tradeDate)
                .containsExactly(LocalDate.parse("2026-09-04"), LocalDate.parse("2026-09-08"));
        assertThat(rows.get(1).mainNetInflow()).isEqualByComparingTo("800");
        assertThat(rows.get(1).sourceName()).isEqualTo("东方财富主力净流入（日级）");
        assertThat(rows.get(1).sourceUrl()).isEqualTo(sourceUrl);
        assertThat(rows.get(1).fetchedAt()).isEqualTo(fetchedAt);
        assertThat(rows.get(1).marketTimestamp()).isNull();
    }

    @Test
    void rejectsInvalidDatesInsteadOfPresentingACompleteHistory() {
        var lines = mapper.createArrayNode().add(flow("not-a-date", "100"));

        assertThatThrownBy(() -> client.readFundFlowHistory(
                "600000", lines, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-08"),
                fetchedAt, sourceUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void rejectsConflictingValuesForTheSameTradingDay() {
        var lines = mapper.createArrayNode()
                .add(flow("2026-09-08", "100"))
                .add(flow("2026-09-08", "200"));

        assertThatThrownBy(() -> client.readFundFlowHistory(
                "600000", lines, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-08"),
                fetchedAt, sourceUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void fillsOnlyMissingMetricsForTheSameSymbolAndDateWhileKeepingCanonicalOhlcv() {
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyKLine canonical = bar("600000", day, "10", "1000", null, "2.5");
        EastMoneyKLine supplement = bar("600000", day, "20", "2000", "500000", "9");
        EastMoneyKLine otherSymbol = bar("000001", day, "30", "3000", "900000", "8");

        List<EastMoneyKLine> merged = EastMoneyClient.mergeDailyTradingMetrics(
                List.of(canonical), List.of(supplement, otherSymbol));

        assertThat(merged).singleElement().satisfies(row -> {
            assertThat(row.open()).isEqualTo(canonical.open());
            assertThat(row.close()).isEqualTo(canonical.close());
            assertThat(row.high()).isEqualTo(canonical.high());
            assertThat(row.low()).isEqualTo(canonical.low());
            assertThat(row.volume()).isEqualTo(canonical.volume());
            assertThat(row.amount()).isEqualByComparingTo("500000");
            assertThat(row.turnoverRate()).isEqualByComparingTo("2.5");
        });
    }

    @Test
    void retainsMissingValuesAndDoesNotAppendUnmatchedSupplementDates() {
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyKLine missing = bar("600000", day, "10", "1000", null, null);
        EastMoneyKLine otherDay = bar("600000", day.minusDays(1), "20", "2000", "500000", "9");
        EastMoneyKLine otherSymbol = bar("000001", day, "20", "2000", "500000", "9");

        assertThat(EastMoneyClient.mergeDailyTradingMetrics(
                List.of(missing), List.of(otherDay, otherSymbol))).containsExactly(missing);
        assertThat(EastMoneyClient.mergeDailyTradingMetrics(List.of(), List.of(otherDay))).isEmpty();
    }

    @Test
    void fillsMissingTurnoverWithoutOverwritingAnExistingAmount() {
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyKLine canonical = bar("600000", day, "10", "1000", "400000", null);
        EastMoneyKLine supplement = bar("600000", day, "20", "2000", "500000", "3");

        assertThat(EastMoneyClient.mergeDailyTradingMetrics(List.of(canonical), List.of(supplement)))
                .singleElement().satisfies(row -> {
                    assertThat(row.amount()).isEqualByComparingTo("400000");
                    assertThat(row.turnoverRate()).isEqualByComparingTo("3");
                });
    }

    private String flow(String date, String main) {
        return date + "," + main + ",10,20,30,40,1,2,3,4,5";
    }

    private EastMoneyKLine bar(String symbol, LocalDate date, String price, String volume,
                              String amount, String turnover) {
        BigDecimal value = new BigDecimal(price);
        return new EastMoneyKLine(symbol, date, value, value, value, value, new BigDecimal(volume),
                amount == null ? null : new BigDecimal(amount),
                turnover == null ? null : new BigDecimal(turnover));
    }
}
