package com.aistock.research.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LimitBoardContextServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
    private static final String SOURCE = """
            {"ok":true,"schemaVersion":1,"date":"2026-11-19","latest":{
                "turnover":{"date":"2026-11-18","amount":100},
                "anomaly":{"date":"2026-11-19","available":true,"stale":false,"anomalies":[
                    {"ticker":"600156","tag":"大涨","keywords":["次要标签"],"analysis":"次要解读。"},
                    {"ticker":"000002","tag":"大跌","keywords":["同日大跌标签"],"analysis":"同日大跌解读。"},
                    {"ticker":"600156","tag":"涨停","keywords":["算力租赁","重组推进","湖南国资","算力租赁"],
                        "analysis":"1、公司拟收购易信科技，布局智算中心。\\n2、纺织主业继续经营。"},
                    {"ticker":"603366","tag":"跌停","keywords":["股价异常波动","高管减持"],"analysis":"1、公司发布异动公告。"},
                    {"ticker":"000003","tag":"炸板","keywords":["炸板题材"],"analysis":"炸板解读。"}
                ]},"unrelated":true}}
            """;

    private final LimitBoardContextService service = new LimitBoardContextService(
            new ObjectMapper(), mock(ShortTermLimitUpBoardService.class));

    @Test
    void joinsPublishedContextByTradingDayTickerAndDirectionWithExactBoardTagsTakingPriority() {
        var result = read(SOURCE, Set.of(), Set.of());

        assertThat(result.complete()).isTrue();
        assertThat(result.snapshot().tradeDate()).isEqualTo(DATE);
        assertThat(result.snapshot().up()).containsOnlyKeys("600156");
        assertThat(result.snapshot().up().get("600156").topics()).containsExactly("算力租赁", "重组推进", "湖南国资");
        assertThat(result.snapshot().up().get("600156").reason()).isEqualTo("公司拟收购易信科技，布局智算中心。");
        assertThat(result.snapshot().down()).containsOnlyKeys("000002", "603366");
        assertThat(result.snapshot().down().get("603366").topics()).containsExactly("股价异常波动", "高管减持");
    }

    @Test
    void rejectsWrongReportDayOrStaleSourceBeforeAcceptingRows() {
        var wrongDay = read(SOURCE.replace("2026-11-19", "2026-11-20"), Set.of(), Set.of());
        var mismatchedAnalysisDay = read(SOURCE.replace("anomaly\":{\"date\":\"2026-11-19", "anomaly\":{\"date\":\"2026-11-20"),
                Set.of(), Set.of());
        var stale = read(SOURCE.replace("\"stale\":false", "\"stale\":true"), Set.of(), Set.of());

        for (var result : List.of(wrongDay.snapshot(), mismatchedAnalysisDay.snapshot(), stale.snapshot())) {
            assertThat(result.up()).isEmpty();
            assertThat(result.down()).isEmpty();
        }
    }

    @Test
    void stopsStreamingOnceTheCachedPoolsHaveExactContextAndKeepsOnlyCompleteRowsOnTruncation() {
        String partial = SOURCE.substring(0, SOURCE.indexOf("{\"ticker\":\"000003"));

        var covered = read(partial, Set.of("600156"), Set.of("603366"));
        var missing = read(partial, Set.of("600156"), Set.of("603366", "603000"));

        assertThat(covered.complete()).isTrue();
        assertThat(missing.complete()).isFalse();
        assertThat(missing.snapshot()).isEqualTo(covered.snapshot());
        assertThat(missing.snapshot().down()).doesNotContainKey("603000");
    }

    private LimitBoardContextService.Load read(String json, Set<String> up, Set<String> down) {
        return service.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), DATE, up, down);
    }
}
