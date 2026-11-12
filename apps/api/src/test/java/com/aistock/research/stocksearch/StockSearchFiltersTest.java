package com.aistock.research.stocksearch;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockSearchFiltersTest {
    @Test
    void excludesRequestedBoardsAndStNamesUsingExactPrefixes() {
        var filters = new StockSearchFilters(true, true, true, true,
                null, null, null, null, null, null, null, null, null, null, null, null);
        for (String code : List.of("300001", "301001", "309999", "688001", "689009", "680001", "430001", "830001", "920175")) {
            assertThat(filters.allows(new StockDirectorySnapshot.Stock(code, "普通名称", ""))).as(code).isFalse();
        }
        for (String code : List.of("000001", "600000", "310000", "690000", "910000")) {
            assertThat(filters.allows(new StockDirectorySnapshot.Stock(code, "普通名称", ""))).as(code).isTrue();
        }
        for (String name : List.of("ST东方", "*ST东方", "st东方", "东方ST")) {
            assertThat(filters.allows(new StockDirectorySnapshot.Stock("600001", name, "上交所"))).as(name).isFalse();
        }
        assertThat(StockSearchFilters.none().allows(new StockDirectorySnapshot.Stock("300001", "*ST东方", "深交所"))).isTrue();
    }

    @Test
    void combinesInclusiveBoundsAndConvertsYiToYuanForAllMoneyMetrics() {
        var filters = numeric("-2", "3", "100", "100", "60", "60", "2.5", "2.5", "10", "10", "1.5", "1.5");
        assertThat(filters.evaluate(quote("-2", "10000000000", "6000000000", "2.5", "10", "150000000")))
                .isEqualTo(StockSearchFilters.Match.MATCH);
        assertThat(filters.evaluate(quote("3", "10000000000", "6000000000", "2.5", "10", "150000000")))
                .isEqualTo(StockSearchFilters.Match.MATCH);
        for (EastMoneyQuote quote : List.of(
                quote("3.01", "10000000000", "6000000000", "2.5", "10", "150000000"),
                quote("1", "9999999999", "6000000000", "2.5", "10", "150000000"),
                quote("1", "10000000000", "6000000001", "2.5", "10", "150000000"),
                quote("1", "10000000000", "6000000000", "2.51", "10", "150000000"),
                quote("1", "10000000000", "6000000000", "2.5", "10.01", "150000000"),
                quote("1", "10000000000", "6000000000", "2.5", "10", "150000001"))) {
            assertThat(filters.evaluate(quote)).isEqualTo(StockSearchFilters.Match.EXCLUDED);
        }
    }

    @Test
    void supportsOneSidedRangesAndCountsOnlyUndecidableMissingMetrics() {
        var filters = numeric("0", null, null, "100", null, null, null, null, null, null, null, null);
        assertThat(filters.evaluate(quote("0", "10000000000", null, null, null, null))).isEqualTo(StockSearchFilters.Match.MATCH);
        assertThat(filters.evaluate(quote("0", null, null, null, null, null))).isEqualTo(StockSearchFilters.Match.UNVERIFIED);
        // A known failed AND condition already excludes this stock despite another missing metric.
        assertThat(filters.evaluate(quote("-1", null, null, null, null, null))).isEqualTo(StockSearchFilters.Match.EXCLUDED);
        assertThat(StockSearchFilters.none().evaluate(quote(null, null, null, null, null, null))).isEqualTo(StockSearchFilters.Match.MATCH);
    }

    @Test
    void rejectsInvertedRangesAndNegativeNonChangeValues() {
        assertThatThrownBy(() -> numeric("2", "1", null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        for (int index = 2; index < 12; index++) {
            String[] values = new String[12];
            values[index] = "-0.1";
            assertThatThrownBy(() -> numeric(values)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(numeric("-3", "-1", null, null, null, null, null, null, null, null, null, null).hasConditions()).isTrue();
    }

    private static StockSearchFilters numeric(String... values) {
        return new StockSearchFilters(false, false, false, false,
                number(values[0]), number(values[1]), number(values[2]), number(values[3]),
                number(values[4]), number(values[5]), number(values[6]), number(values[7]),
                number(values[8]), number(values[9]), number(values[10]), number(values[11]));
    }

    private static BigDecimal number(String value) { return value == null ? null : new BigDecimal(value); }

    static EastMoneyQuote quote(String change, String marketCap, String circulatingCap, String turnover, String price, String amount) {
        return new EastMoneyQuote("600001", "东方股份", "上交所", null, number(price), number(change), number(turnover),
                null, number(amount), null, null, null, "测试行情", null, Instant.now(), null, null,
                number(marketCap), null, null, null, null, number(circulatingCap));
    }
}
