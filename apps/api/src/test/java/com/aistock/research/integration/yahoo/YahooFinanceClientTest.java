package com.aistock.research.integration.yahoo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YahooFinanceClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseSparkClosesAndComputeChangePercent() {
        // 批量 symbols 参数的真实响应形态：symbol 作 key 的映射，close/timestamp 平铺
        String body = """
                {"1306.T":{"timestamp":[1787011200,1787097600],"symbol":"1306.T","close":[431.0,424.7]},
                 "1617.T":{"timestamp":[1787011200,1787097600,1787356800],"symbol":"1617.T","close":[6989.0,6769.0,6855.0]},
                 "091160.KS":{"timestamp":[1787011200,1787097600],"symbol":"091160.KS","close":[32000,31200,null]},
                 "1305.T":{"timestamp":[1787011200],"symbol":"1305.T","close":[1750.0]}}
                """;

        List<YahooSparkQuote> quotes = YahooFinanceClient.parseSparkBody(body, objectMapper);

        assertThat(quotes).hasSize(4);
        YahooSparkQuote foods = quotes.stream().filter(q -> "1617.T".equals(q.symbol())).findFirst().orElseThrow();
        assertThat(foods.lastClose()).isEqualByComparingTo("6855");
        assertThat(foods.changePercent()).isEqualByComparingTo(
                new BigDecimal("6855").subtract(new BigDecimal("6769"))
                        .divide(new BigDecimal("6769"), 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)));
        assertThat(foods.lastTradeTime()).isEqualTo(Instant.ofEpochSecond(1787356800));

        // 收盘序列里的 null 直接跳过，涨跌幅基于最后两个有效收盘
        YahooSparkQuote semiconductor = quotes.stream().filter(q -> "091160.KS".equals(q.symbol())).findFirst().orElseThrow();
        assertThat(semiconductor.changePercent()).isEqualByComparingTo("-2.5");
        assertThat(semiconductor.lastTradeTime()).isEqualTo(Instant.ofEpochSecond(1787097600));

        // 只有 1 根有效收盘时涨跌幅为空，价格仍保留
        YahooSparkQuote single = quotes.stream().filter(q -> "1305.T".equals(q.symbol())).findFirst().orElseThrow();
        assertThat(single.lastClose()).isEqualByComparingTo("1750");
        assertThat(single.changePercent()).isNull();
    }

    @Test
    void shouldAlsoParseSingleSymbolSparkResultShape() {
        // 单 symbol 路径的响应形态：{"spark":{"result":[{symbol, response:[...]}]}}
        String body = """
                {"spark":{"result":[
                  {"symbol":"1617.T","response":[{"timestamp":[1787011200,1787097600],"indicators":{"quote":[{"close":[6989.0,6855.0]}]}}]}
                ],"error":null}}
                """;

        List<YahooSparkQuote> quotes = YahooFinanceClient.parseSparkBody(body, objectMapper);

        assertThat(quotes).singleElement().satisfies(quote -> {
            assertThat(quote.symbol()).isEqualTo("1617.T");
            assertThat(quote.lastClose()).isEqualByComparingTo("6855");
            assertThat(quote.changePercent()).isEqualByComparingTo("-1.9173");
            assertThat(quote.lastTradeTime()).isEqualTo(Instant.ofEpochSecond(1787097600));
        });
    }

    @Test
    void shouldRejectNonJsonBodyAsRateLimitOrBlock() {
        assertThatThrownBy(() -> YahooFinanceClient.parseSparkBody("Too Many Requests", objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非 JSON");
        assertThatThrownBy(() -> YahooFinanceClient.parseSparkBody("", objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空响应");
    }

    @Test
    void shouldTolerateMissingResultArray() {
        List<YahooSparkQuote> quotes = YahooFinanceClient.parseSparkBody("{\"spark\":{\"error\":null}}", objectMapper);
        assertThat(quotes).isEmpty();
    }
}
