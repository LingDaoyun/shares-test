package com.aistock.research.integration.eastmoney;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TencentDailyWindowRequestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYMBOL = "600893";

    @ParameterizedTest
    @ValueSource(ints = {421, 500})
    void aWindowThatFitsTheResponseLimitNeedsOneRequest(int inclusiveDays) {
        LocalDate begin = LocalDate.of(2023, 1, 2);
        LocalDate end = begin.plusDays(inclusiveDays - 1L);
        Client client = new Client(end);

        EastMoneyKLineSeries series = client.fetchDailyKLineSeries(SYMBOL, begin, end);

        assertThat(series.complete()).isTrue();
        assertThat(series.sourceName()).isEqualTo("TENCENT_QFQ_DAILY");
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate)
                .containsExactlyElementsOf(begin.datesUntil(end.plusDays(1)).toList());
        assertThat(client.urls).singleElement().asString()
                .contains(",day," + begin + "," + end + ",500,qfq");
    }

    @Test
    void current420DayLookbackUsesOneOpenEndedRequestAndRetainsToday() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusDays(420);
        Client client = new Client(today);

        EastMoneyKLineSeries series = client.fetchDailyKLineSeries(SYMBOL, begin, today);

        assertThat(series.complete()).isTrue();
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate)
                .containsExactlyElementsOf(begin.datesUntil(today.plusDays(1)).toList());
        assertThat(client.urls).singleElement().asString()
                .contains(",day," + begin + ",,500,qfq");
    }

    @ParameterizedTest
    @ValueSource(ints = {501, 732})
    void aLargerWindowKeepsEveryOriginalAnnualSlice(int inclusiveDays) {
        LocalDate begin = LocalDate.of(2023, 1, 2);
        LocalDate end = begin.plusDays(inclusiveDays - 1L);
        Client client = new Client(end);

        EastMoneyKLineSeries series = client.fetchDailyKLineSeries(SYMBOL, begin, end);

        assertThat(series.complete()).isTrue();
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate)
                .containsExactlyElementsOf(begin.datesUntil(end.plusDays(1)).toList());
        assertThat(client.urls).hasSize(inclusiveDays == 501 ? 2 : 3);
        LocalDate nextBegin = begin;
        for (String url : client.urls) {
            LocalDate annualEnd = nextBegin.plusYears(1).minusDays(1);
            LocalDate expectedEnd = annualEnd.isAfter(end) ? end : annualEnd;
            assertThat(url).contains(",day," + nextBegin + "," + expectedEnd + ",500,qfq");
            nextBegin = expectedEnd.plusDays(1);
        }
    }

    private static final class Client extends EastMoneyClient {
        private final LocalDate currentEnd;
        private final List<String> urls = new ArrayList<>();

        private Client(LocalDate currentEnd) {
            super(null, JSON, null);
            this.currentEnd = currentEnd;
        }

        @Override
        String fetchBodyWithCurl(String url, Exception failure, String referer) {
            if (!url.contains("/fqkline/get")) {
                throw new AssertionError("Complete Tencent data must not need another provider: " + url);
            }
            urls.add(url);
            String[] fields = url.substring(url.indexOf("?param=") + 7).split(",", -1);
            LocalDate begin = LocalDate.parse(fields[2]);
            LocalDate end = fields[3].isEmpty() ? currentEnd : LocalDate.parse(fields[3]);
            List<List<String>> rows = begin.datesUntil(end.plusDays(1))
                    .map(date -> List.of(date.toString(), "10", "11", "12", "9", "100"))
                    .toList();
            assertThat(rows).hasSizeLessThanOrEqualTo(500);
            return JSON.valueToTree(Map.of("data", Map.of("sh" + SYMBOL, Map.of("qfqday", rows)))).toString();
        }
    }
}
