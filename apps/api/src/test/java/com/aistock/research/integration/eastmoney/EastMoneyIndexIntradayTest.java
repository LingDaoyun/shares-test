package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EastMoneyIndexIntradayTest {
    @Test
    void preservesTwoDatesAndMinuteUnitsUsingExplicitShanghaiIndexId() {
        var client = new FixtureClient();
        var rows = client.fetchIndexIntradayTrends("sh000001");
        assertThat(client.url).contains("secid=1.000001", "ndays=2");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).minute()).isEqualTo(LocalDateTime.parse("2026-10-15T09:30"));
        assertThat(rows.get(1).minute()).isEqualTo(LocalDateTime.parse("2026-10-16T09:30"));
        assertThat(rows.get(1).amount()).isEqualByComparingTo("8928357376");
        assertThat(rows.get(1).volume()).isEqualByComparingTo("6103465");
        client.body = "not json";
        assertThat(client.fetchIndexIntradayTrends("sh000001")).isEqualTo(rows);
        assertThat(client.fetchIndexIntradayTrends("sz399106")).isEmpty();
        assertThat(client.url).contains("secid=0.399106");
    }

    @Test
    void stockSymbolsCannotAccidentallyBecomeShanghaiIndexRequests() {
        assertThatThrownBy(() -> new FixtureClient().fetchIndexIntradayTrends("000001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FixtureClient extends EastMoneyClient {
        String url;
        String body = """
                {"data":{"trends":["2026-10-15 09:30,0,3700,3700,3700,100,200,3700",
                "2026-10-16 09:30,0,3710.70,3710.70,3710.70,6103465,8928357376.00,3692.285"]}}
                """;
        FixtureClient() { super(RestClient.create(), new ObjectMapper(), null); }
        @Override String fetchIndexIntradayBody(String url) { this.url = url; return body; }
    }
}
