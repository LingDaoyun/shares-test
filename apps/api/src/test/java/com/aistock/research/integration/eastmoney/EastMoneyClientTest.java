package com.aistock.research.integration.eastmoney;

import com.aistock.research.config.LiveDataProperties;
import com.aistock.research.shortterm.ShortTermRuleSet;
import com.aistock.research.shortterm.ShortTermTechnicalSignalEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EastMoneyClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EastMoneyClient client = new EastMoneyClient(mock(RestClient.class), objectMapper, properties());

    @Test
    void shouldScopeNorthExchangeQuotesToListedSharesInsteadOfAllType81Securities() {
        assertThat(EastMoneyClient.A_SHARE_FILTER)
                .isEqualTo("m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048");
    }

    @Test
    void shouldParseTurnoverRateFromExpandedDailyKLine() throws Exception {
        java.lang.reflect.Method method = EastMoneyClient.class
                .getDeclaredMethod("readKLine", String.class, String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Optional<EastMoneyKLine> parsed = (Optional<EastMoneyKLine>) method.invoke(
                client,
                "002580",
                "2026-07-30,10.00,10.50,10.80,9.90,123456,128000000,5.00,2.30,1.20,3.42"
        );

        assertThat(parsed).isPresent();
        assertThat(parsed.orElseThrow().turnoverRate()).isEqualByComparingTo("3.42");
    }

    @Test
    void shouldMergeTurnoverByTradeDateWithoutReplacingCanonicalPrices() {
        LocalDate date = LocalDate.of(2026, 7, 30);
        EastMoneyKLine canonical = new EastMoneyKLine(
                "002580", date, bd("10.00"), bd("10.50"), bd("10.80"), bd("9.90"),
                bd("123456"), bd("128000000")
        );
        EastMoneyKLine turnoverSource = new EastMoneyKLine(
                "002580", date, bd("9.80"), bd("10.40"), bd("10.70"), bd("9.70"),
                bd("120000"), bd("125000000"), bd("3.42")
        );

        List<EastMoneyKLine> merged = EastMoneyClient.mergeTurnoverRates(
                List.of(canonical), List.of(turnoverSource));

        assertThat(merged).singleElement().satisfies(row -> {
            assertThat(row.close()).isEqualByComparingTo("10.50");
            assertThat(row.volume()).isEqualByComparingTo("123456");
            assertThat(row.turnoverRate()).isEqualByComparingTo("3.42");
        });
    }

    @Test
    void shouldParseExchangeTimestampInsteadOfTreatingFetchTimeAsTradeTime() {
        Instant marketTime = Instant.parse("2026-07-11T07:20:30Z");
        JsonNode item = objectMapper.createObjectNode()
                .put("f12", "600000")
                .put("f14", "浦发银行")
                .put("f13", 1)
                .put("f2", 1000)
                .put("f3", 20)
                .put("f6", 900000000)
                .put("f20", 245600000000L)
                .put("f124", marketTime.getEpochSecond());

        EastMoneyQuote quote = client.readQuote(item, Instant.parse("2026-07-11T07:25:00Z")).orElseThrow();

        assertThat(quote.marketTimestamp()).isEqualTo(marketTime);
        assertThat(quote.tradeDate()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(quote.fetchedAt()).isEqualTo(Instant.parse("2026-07-11T07:25:00Z"));
        assertThat(quote.totalMarketValue()).isEqualByComparingTo("245600000000");
    }

    @Test
    void shouldParseRealtimeVolumeRatioFromBatchQuote() {
        JsonNode item = objectMapper.createObjectNode()
                .put("f12", "600000")
                .put("f14", "浦发银行")
                .put("f13", 1)
                .put("f2", 1000)
                .put("f10", 187);

        EastMoneyQuote quote = client.readQuote(item, Instant.parse("2026-08-28T02:00:00Z")).orElseThrow();

        assertThat(quote.realtimeVolumeRatio()).isEqualByComparingTo("1.87");
    }

    @Test
    void shouldParseCirculatingMarketValueFromBatchQuote() {
        JsonNode item = objectMapper.createObjectNode()
                .put("f12", "600000")
                .put("f14", "浦发银行")
                .put("f13", 1)
                .put("f2", 1000)
                .put("f21", 8800000000L);

        EastMoneyQuote quote = client.readQuote(
                item, Instant.parse("2026-09-03T02:00:00Z")).orElseThrow();

        assertThat(quote.circulatingMarketValue()).isEqualByComparingTo("8800000000");
    }

    @Test
    void shouldParseGlobalQuoteWithFlttDecimalFields() {
        Instant marketTime = Instant.parse("2026-08-21T20:00:00Z");
        JsonNode item = objectMapper.createObjectNode()
                .put("f12", "DJIA")
                .put("f13", 100)
                .put("f14", "道琼斯")
                .put("f2", 53277.01)
                .put("f3", 0.98)
                .put("f4", 517.80)
                .put("f18", 52759.21)
                .put("f124", marketTime.getEpochSecond());

        EastMoneyGlobalQuote quote = client.readGlobalQuote(item, Instant.parse("2026-08-21T20:05:00Z")).orElseThrow();

        assertThat(quote.code()).isEqualTo("DJIA");
        assertThat(quote.name()).isEqualTo("道琼斯");
        assertThat(quote.latestPrice()).isEqualByComparingTo("53277.01");
        assertThat(quote.changePercent()).isEqualByComparingTo("0.98");
        assertThat(quote.changeValue()).isEqualByComparingTo("517.8");
        assertThat(quote.prevClose()).isEqualByComparingTo("52759.21");
        assertThat(quote.marketCode()).isEqualTo(100);
        assertThat(quote.quoteTime()).isEqualTo(marketTime);
    }

    @Test
    void shouldFallBackToFetchedAtWhenGlobalQuoteTimestampMissing() {
        JsonNode item = objectMapper.createObjectNode()
                .put("f12", "XLK")
                .put("f13", 107)
                .put("f14", "科技行业精选指数ETF-SPDR")
                .put("f2", 230.15)
                .put("f3", 1.5);

        Instant fetchedAt = Instant.parse("2026-08-21T20:05:00Z");
        EastMoneyGlobalQuote quote = client.readGlobalQuote(item, fetchedAt).orElseThrow();

        assertThat(quote.quoteTime()).isEqualTo(fetchedAt);
        assertThat(quote.changeValue()).isNull();
    }

    @Test
    void shouldParseTencentExchangeTimestampInChinaMarketZone() {
        String[] fields = new String[49];
        Arrays.fill(fields, "");
        fields[1] = "平安银行";
        fields[2] = "000001";
        fields[3] = "12.34";
        fields[30] = "20260711152030";
        fields[32] = "1.25";
        fields[37] = "12345";
        String line = "v_sz000001=\"" + String.join("~", fields) + "\"";

        EastMoneyQuote quote = client.readTencentQuote(line, Instant.parse("2026-07-11T07:25:00Z")).orElseThrow();

        assertThat(quote.marketTimestamp()).isEqualTo(Instant.parse("2026-07-11T07:20:30Z"));
        assertThat(quote.tradeDate()).isEqualTo(LocalDate.parse("2026-07-11"));
    }

    @Test
    void normalizesTencentStarDailyShareVolumeToHands() throws Exception {
        EastMoneyKLine row = tencentVolumeBar("688696", LocalDate.of(2026, 10, 16), "1527513");

        assertThat(row.volume()).isEqualByComparingTo("15275.13");
        assertThat(row.close()).isEqualByComparingTo("86.93");
    }

    @Test
    void normalizesTencentStarQuotesButKeepsMainBoardVolumesInHands() {
        assertThat(tencentVolumeQuote("688696", "1527513").volume()).isEqualByComparingTo("15275.13");
        assertThat(tencentVolumeQuote("688297", "13612442").volume()).isEqualByComparingTo("136124.42");
        assertThat(tencentVolumeQuote("600435", "452628").volume()).isEqualByComparingTo("452628");
        assertThat(tencentVolumeQuote("000768", "353216").volume()).isEqualByComparingTo("353216");
    }

    @Test
    void keepsTencentMainBoardDailyVolumeInHands() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 16);
        assertThat(tencentVolumeBar("600435", date, "452628").volume()).isEqualByComparingTo("452628");
        assertThat(tencentVolumeBar("000768", date, "353216").volume()).isEqualByComparingTo("353216");
    }

    @Test
    void eastMoneyStarQuotesAndDailyBarsAreAlreadyInHands() throws Exception {
        JsonNode quote = objectMapper.createObjectNode()
                .put("f12", "688696").put("f14", "极米科技").put("f13", 1)
                .put("f2", 8693).put("f5", 15275);
        assertThat(client.readQuote(quote, Instant.parse("2026-10-16T07:30:00Z")).orElseThrow().volume())
                .isEqualByComparingTo("15275");
        java.lang.reflect.Method read = EastMoneyClient.class.getDeclaredMethod("readKLine", String.class, String.class);
        read.setAccessible(true);
        @SuppressWarnings("unchecked")
        Optional<EastMoneyKLine> row = (Optional<EastMoneyKLine>) read.invoke(client, "688696",
                "2026-10-16,84.85,86.93,89.89,84.32,15275,133303909.00,6.63,3.55,2.98,2.18");
        assertThat(row.orElseThrow().volume()).isEqualByComparingTo("15275");
    }

    @Test
    void normalizedTencentStarVolumeMatchesTheThreeDayQuoteComparison() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 16);
        List<EastMoneyKLine> bars = new ArrayList<>();
        for (int daysAgo = 60; daysAgo >= 0; daysAgo--) {
            String volume = switch (daysAgo) {
                case 3 -> "986100";
                case 2 -> "1575989";
                case 1 -> "861067";
                case 0 -> "1527513";
                default -> "1000000";
            };
            bars.add(tencentVolumeBar("688696", date.minusDays(daysAgo), volume));
        }
        ShortTermRuleSet rules = new ShortTermRuleSet(0, 0, bd("80000000"), bd("1.20"),
                bd("6.50"), bd("8.00"), bd("55"));
        // EastMoney's current quote is in hands; Tencent fallback must now use that same unit.
        for (BigDecimal quoteVolume : List.of(bd("15275"), tencentVolumeQuote("688696", "1527513").volume())) {
            var technical = new ShortTermTechnicalSignalEvaluator().evaluate(
                    bars, bd("86.93"), date, quoteVolume, true, rules).snapshot();
            assertThat(technical.averageVolume3()).isEqualByComparingTo("11410.52");
            assertThat(technical.volumeRatio3()).isEqualByComparingTo("1.34");
            assertThat(technical.todayVolume()).isEqualByComparingTo(quoteVolume);
        }
    }

    private EastMoneyKLine tencentVolumeBar(String symbol, LocalDate date, String volume) throws Exception {
        java.lang.reflect.Method read = EastMoneyClient.class.getDeclaredMethod("readTencentKLine", String.class, JsonNode.class);
        read.setAccessible(true);
        JsonNode raw = objectMapper.valueToTree(List.of(date.toString(), "84.85", "86.93", "89.89", "84.32", volume));
        @SuppressWarnings("unchecked")
        Optional<EastMoneyKLine> row = (Optional<EastMoneyKLine>) read.invoke(client, symbol, raw);
        return row.orElseThrow();
    }

    private EastMoneyQuote tencentVolumeQuote(String symbol, String volume) {
        String[] fields = new String[49];
        Arrays.fill(fields, "");
        fields[1] = "成交量口径测试";
        fields[2] = symbol;
        fields[3] = "86.93";
        fields[30] = "20261016161445";
        fields[36] = volume;
        fields[37] = "13330.3909";
        return client.readTencentQuote("v_quote=\"" + String.join("~", fields) + "\"",
                Instant.parse("2026-10-16T08:30:00Z")).orElseThrow();
    }

    @Test
    void shouldParseFundFlowSnapshotFieldsFromEastMoneyPush2() throws Exception {
        JsonNode item = objectMapper.readTree("""
                {
                  "f12": "002772",
                  "f14": "众兴菌业",
                  "f62": 54563039,
                  "f184": 13.39,
                  "f66": 15987968,
                  "f69": 3.92,
                  "f72": 38575071,
                  "f75": 9.47,
                  "f78": 14572502,
                  "f81": 3.58,
                  "f84": -69135541,
                  "f87": -16.97,
                  "f124": 1785481200
                }
                """);

        EastMoneyFundFlowSnapshot snapshot = client.readFundFlowSnapshot(
                        item,
                        "002772",
                        Instant.parse("2026-07-03T07:00:00Z"),
                        "https://quote.eastmoney.com/sz002772.html"
                )
                .orElseThrow();

        assertThat(snapshot.symbol()).isEqualTo("002772");
        assertThat(snapshot.mainNetInflow()).isEqualByComparingTo(new BigDecimal("54563039"));
        assertThat(snapshot.superLargeNetInflow()).isEqualByComparingTo(new BigDecimal("15987968"));
        assertThat(snapshot.largeNetInflow()).isEqualByComparingTo(new BigDecimal("38575071"));
        assertThat(snapshot.smallNetInflow()).isEqualByComparingTo(new BigDecimal("-69135541"));
        assertThat(snapshot.mainNetInflowRatio()).isEqualByComparingTo(new BigDecimal("13.39"));
        assertThat(snapshot.marketTimestamp()).isEqualTo(Instant.parse("2026-07-31T07:00:00Z"));
        assertThat(snapshot.tradeDate()).isEqualTo(LocalDate.parse("2026-07-31"));
    }

    @Test
    void shouldParseFundFlowMinuteLine() {
        EastMoneyFundFlowPoint point = client.readFundFlowPoint(
                        "002772",
                        "2026-07-03 14:33,54618358.0,-69409582.0,14791225.0,38630390.0,15987968.0"
                )
                .orElseThrow();

        assertThat(point.minute()).isEqualTo("2026-07-03 14:33");
        assertThat(point.mainNetInflow()).isEqualByComparingTo(new BigDecimal("54618358"));
        assertThat(point.smallNetInflow()).isEqualByComparingTo(new BigDecimal("-69409582"));
        assertThat(point.largeNetInflow()).isEqualByComparingTo(new BigDecimal("38630390"));
        assertThat(point.superLargeNetInflow()).isEqualByComparingTo(new BigDecimal("15987968"));
    }

    @Test
    void shouldParseIntradayTrendLine() {
        EastMoneyIntradayPoint point = client.readIntradayPoint(
                        "000100",
                        "2026-07-07 14:31,5.28,5.31,5.32,5.27,138636,73004458.00,5.216"
                )
                .orElseThrow();

        assertThat(point.symbol()).isEqualTo("000100");
        assertThat(point.minute().toString()).isEqualTo("2026-07-07T14:31");
        assertThat(point.open()).isEqualByComparingTo(new BigDecimal("5.28"));
        assertThat(point.close()).isEqualByComparingTo(new BigDecimal("5.31"));
        assertThat(point.high()).isEqualByComparingTo(new BigDecimal("5.32"));
        assertThat(point.low()).isEqualByComparingTo(new BigDecimal("5.27"));
        assertThat(point.volume()).isEqualByComparingTo(new BigDecimal("138636"));
        assertThat(point.amount()).isEqualByComparingTo(new BigDecimal("73004458"));
        assertThat(point.averagePrice()).isEqualByComparingTo(new BigDecimal("5.216"));
    }

    @Test
    void shouldParseTencentIntradayCumulativeRowsAsMinuteDeltas() throws Exception {
        JsonNode rows = objectMapper.readTree("""
                [
                  "0930 5.19 222649 115554831.00",
                  "0931 5.17 523553 270958203.29"
                ]
                """);

        List<EastMoneyIntradayPoint> points = client.readTencentIntradayPoints("000100", "20260707", rows);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).minute().toString()).isEqualTo("2026-07-07T09:30");
        assertThat(points.get(0).close()).isEqualByComparingTo(new BigDecimal("5.19"));
        assertThat(points.get(0).volume()).isEqualByComparingTo(new BigDecimal("222649"));
        assertThat(points.get(0).amount()).isEqualByComparingTo(new BigDecimal("115554831"));
        assertThat(points.get(0).averagePrice()).isEqualByComparingTo(new BigDecimal("5.19"));
        assertThat(points.get(1).minute().toString()).isEqualTo("2026-07-07T09:31");
        assertThat(points.get(1).volume()).isEqualByComparingTo(new BigDecimal("300904"));
        assertThat(points.get(1).amount()).isEqualByComparingTo(new BigDecimal("155403372.29"));
        assertThat(points.get(1).averagePrice()).isEqualByComparingTo(new BigDecimal("5.1754"));
    }

    @Test
    void shouldChooseTencentAveragePriceUnitClosestToCurrentPrice() throws Exception {
        JsonNode rows = objectMapper.readTree("""
                [
                  "1523 408.28 13712729 5457226430.16"
                ]
                """);

        List<EastMoneyIntradayPoint> points = client.readTencentIntradayPoints("688017", "20260709", rows);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).close()).isEqualByComparingTo(new BigDecimal("408.28"));
        assertThat(points.get(0).averagePrice()).isEqualByComparingTo(new BigDecimal("397.9679"));
    }

    @Test
    void shouldParseLatestDailyFundFlowWhenRealtimeFlowIsEmptyBeforeMarketOpen() {
        EastMoneyFundFlowSnapshot snapshot = client.readFundFlowDaySnapshot(
                        "002772",
                        "2026-07-03,60819019.0,-79788940.0,18969921.0,44818622.0,16000397.0,13.71,-17.99,4.28,10.11,3.61,13.88,8.44,0.00,0.00",
                        Instant.parse("2026-07-06T00:55:00Z"),
                        "https://quote.eastmoney.com/sz002772.html"
                )
                .orElseThrow();

        assertThat(snapshot.sourceName()).contains("2026-07-03");
        assertThat(snapshot.mainNetInflow()).isEqualByComparingTo(new BigDecimal("60819019"));
        assertThat(snapshot.superLargeNetInflow()).isEqualByComparingTo(new BigDecimal("16000397"));
        assertThat(snapshot.largeNetInflow()).isEqualByComparingTo(new BigDecimal("44818622"));
        assertThat(snapshot.mediumNetInflow()).isEqualByComparingTo(new BigDecimal("18969921"));
        assertThat(snapshot.smallNetInflow()).isEqualByComparingTo(new BigDecimal("-79788940"));
        assertThat(snapshot.mainNetInflowRatio()).isEqualByComparingTo(new BigDecimal("13.71"));
        assertThat(snapshot.smallNetInflowRatio()).isEqualByComparingTo(new BigDecimal("-17.99"));
        assertThat(snapshot.tradeDate()).isEqualTo(LocalDate.parse("2026-07-03"));
        assertThat(snapshot.marketTimestamp()).isNull();
    }

    @Test
    void shouldBuildOneDeduplicatedBatchFundFlowUrl() {
        String url = client.fundFlowBatchUrl(List.of("600000", "000001", "600000", "invalid"));

        assertThat(url)
                .contains("secids=1.600000%2C0.000001")
                .contains("fields=f12%2Cf13%2Cf14%2Cf62");
    }

    @Test
    void shouldParseMultipleFundFlowSnapshotsFromOneBatchResponse() throws Exception {
        JsonNode diff = objectMapper.readTree("""
                [
                  {
                    "f12": "600000",
                    "f14": "浦发银行",
                    "f62": 100000000,
                    "f184": 5.2,
                    "f66": 60000000,
                    "f69": 3.1,
                    "f72": 40000000,
                    "f75": 2.1,
                    "f124": 1785481200
                  },
                  {
                    "f12": "000001",
                    "f14": "平安银行",
                    "f62": -50000000,
                    "f184": -2.5,
                    "f66": -30000000,
                    "f69": -1.5,
                    "f72": -20000000,
                    "f75": -1.0,
                    "f124": 1785481200
                  }
                ]
                """);

        java.util.Map<String, EastMoneyFundFlowSnapshot> snapshots =
                client.readFundFlowSnapshots(diff, Instant.parse("2026-07-31T07:01:00Z"));

        assertThat(snapshots).containsOnlyKeys("600000", "000001");
        assertThat(snapshots.get("600000").mainNetInflowRatio()).isEqualByComparingTo("5.2");
        assertThat(snapshots.get("000001").mainNetInflowRatio()).isEqualByComparingTo("-2.5");
        assertThat(snapshots.values()).allSatisfy(snapshot ->
                assertThat(snapshot.tradeDate()).isEqualTo(LocalDate.parse("2026-07-31")));
    }

    @Test
    void shouldParseIndustryFundFlowSnapshotsFromBoardBatchResponse() throws Exception {
        JsonNode diff = objectMapper.readTree("""
                [
                  {
                    "f12": "BK1201",
                    "f14": "电子",
                    "f62": 25470566400,
                    "f184": 3.75,
                    "f66": 18464870400,
                    "f69": 2.72,
                    "f72": 7005696000,
                    "f75": 1.03,
                    "f104": 411,
                    "f105": 102,
                    "f124": 1786083165
                  },
                  {
                    "f12": "GC001",
                    "f14": "非行业样本",
                    "f62": 999999,
                    "f124": 1786083165
                  },
                  {
                    "f12": "BK1036",
                    "f14": "半导体",
                    "f62": 9668880640,
                    "f184": 2.84,
                    "f66": 6056454400,
                    "f69": 1.78,
                    "f72": 3612426240,
                    "f75": 1.06,
                    "f104": 171,
                    "f105": 11,
                    "f124": 1786083165
                  }
                ]
                """);

        List<EastMoneyIndustryFundFlowSnapshot> snapshots = client.readIndustryFundFlows(
                diff,
                Instant.parse("2026-08-07T06:15:00Z"),
                "https://push2delay.eastmoney.com/api/qt/clist/get"
        );

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots).extracting(EastMoneyIndustryFundFlowSnapshot::code)
                .containsExactly("BK1201", "BK1036");
        EastMoneyIndustryFundFlowSnapshot electronics = snapshots.get(0);
        assertThat(electronics.name()).isEqualTo("电子");
        assertThat(electronics.mainNetInflow()).isEqualByComparingTo("25470566400");
        assertThat(electronics.mainNetInflowRatio()).isEqualByComparingTo("3.75");
        assertThat(electronics.superLargeNetInflow()).isEqualByComparingTo("18464870400");
        assertThat(electronics.largeNetInflow()).isEqualByComparingTo("7005696000");
        assertThat(electronics.advancing()).isEqualTo(411);
        assertThat(electronics.declining()).isEqualTo(102);
        assertThat(electronics.constituentCount()).isEqualTo(513);
        assertThat(electronics.marketTimestamp()).isEqualTo(Instant.parse("2026-08-07T06:12:45Z"));
        assertThat(electronics.tradeDate()).isEqualTo(LocalDate.parse("2026-08-07"));
        assertThat(electronics.sourceUrl()).isEqualTo("https://push2delay.eastmoney.com/api/qt/clist/get");
    }

    @Test
    void industryFundFlowRetainsMissingAmountsInTheReturnedCoverageUniverse() throws Exception {
        JsonNode diff = objectMapper.readTree("""
                [{"f12":"BK1201","f14":"电子","f62":"-"},
                 {"f12":"BK1036","f14":"半导体","f62":0},
                 {"f12":"BKbad","f14":"无效板块","f62":100}]
                """);

        List<EastMoneyIndustryFundFlowSnapshot> snapshots = client.readIndustryFundFlows(
                diff, Instant.parse("2026-08-07T06:15:00Z"), "fixture");

        assertThat(snapshots).extracting(EastMoneyIndustryFundFlowSnapshot::code)
                .containsExactly("BK1201", "BK1036");
        assertThat(snapshots.get(0).mainNetInflow()).isNull();
        assertThat(snapshots.get(0).tradeDate()).isNull();
        assertThat(snapshots.get(1).mainNetInflow()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingBrokenPoolStructureIsNotAConfirmedZeroBrokenBoards() throws Exception {
        assertThat(client.readBrokenBoardPoolEntries(objectMapper.readTree("[]"))).isEmpty();
        assertThatThrownBy(() -> client.readBrokenBoardPoolEntries(objectMapper.readTree("{}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("炸板池");
        assertThatThrownBy(() -> client.readBrokenBoardPoolEntries(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("炸板池");
    }

    @Test
    void shouldParseBoardStockFundFlowRanksFromBoardConstituentResponse() throws Exception {
        JsonNode diff = objectMapper.readTree("""
                [
                  {
                    "f12": "002475",
                    "f14": "立讯精密",
                    "f2": 41.2,
                    "f3": 5.6,
                    "f62": 1200000000,
                    "f184": 8.4,
                    "f66": 800000000,
                    "f72": 400000000,
                    "f124": 1786083165
                  },
                  {
                    "f12": "600703",
                    "f14": "三安光电",
                    "f2": 15.8,
                    "f3": 3.2,
                    "f62": 600000000,
                    "f184": 5.1,
                    "f66": 400000000,
                    "f72": 200000000,
                    "f124": 1786083165
                  }
                ]
                """);

        List<EastMoneyStockFundFlowRank> ranks = client.readBoardStockFundFlowRanks(
                diff,
                Instant.parse("2026-08-07T06:15:00Z"),
                "https://push2delay.eastmoney.com/api/qt/clist/get"
        );

        assertThat(ranks).hasSize(2);
        EastMoneyStockFundFlowRank first = ranks.get(0);
        assertThat(first.symbol()).isEqualTo("002475");
        assertThat(first.name()).isEqualTo("立讯精密");
        assertThat(first.latestPrice()).isEqualByComparingTo("41.2");
        assertThat(first.changePercent()).isEqualByComparingTo("5.6");
        assertThat(first.mainNetInflow()).isEqualByComparingTo("1200000000");
        assertThat(first.mainNetInflowRatio()).isEqualByComparingTo("8.4");
        assertThat(first.superLargeNetInflow()).isEqualByComparingTo("800000000");
        assertThat(first.largeNetInflow()).isEqualByComparingTo("400000000");
        assertThat(first.marketTimestamp()).isEqualTo(Instant.parse("2026-08-07T06:12:45Z"));
        assertThat(first.tradeDate()).isEqualTo(LocalDate.parse("2026-08-07"));
        assertThat(first.sourceUrl()).isEqualTo("https://push2delay.eastmoney.com/api/qt/clist/get");

        String inflowUrl = client.boardStockFundFlowUrl("BK1201", 10, false);
        assertThat(inflowUrl).contains("pz=10");
        assertThat(inflowUrl).contains("po=1");
        assertThat(inflowUrl).contains("fid=f62");
        assertThat(inflowUrl).contains("fs=b%3ABK1201+f%3A%2150");

        assertThat(client.boardStockFundFlowUrl("BK0475", 10, true)).contains("po=0");
        assertThat(client.fetchBoardStockFundFlowRanks("600519", 10, false)).isEmpty();
    }

    @Test
    void shouldParseLimitUpPoolEntriesFromTopicPoolPayload() throws Exception {
        String payload = "{\"rc\":0,\"data\":{\"tc\":2,\"pool\":["
                + "{\"c\":\"000017\",\"m\":0,\"n\":\"深中华A\",\"p\":7110,\"zdp\":10.06,\"amount\":294182288,"
                + "\"ltsz\":3134244071.61,\"tshare\":4900104838.08,\"hs\":9.43,\"lbc\":2,\"fbt\":92500,"
                + "\"lbt\":93909,\"fund\":39809601,\"zbc\":1,\"hybk\":\"饰品\",\"zttj\":{\"days\":2,\"ct\":2}},"
                + "{\"c\":\"002209\",\"m\":0,\"n\":\"达 意 隆\",\"p\":13160,\"zdp\":10.03,\"amount\":206290574,"
                + "\"hs\":9.93,\"lbc\":1,\"fbt\":92500,\"lbt\":94236,\"fund\":23922248,\"zbc\":1,"
                + "\"hybk\":\"专用设备\",\"zttj\":{\"days\":1,\"ct\":1}}"
                + "]}}";

        List<EastMoneyLimitUpPoolEntry> entries = client.readLimitUpPoolEntries(
                objectMapper.readTree(payload).path("data").path("pool"));

        assertThat(entries).hasSize(2);
        EastMoneyLimitUpPoolEntry first = entries.get(0);
        assertThat(first.symbol()).isEqualTo("000017");
        assertThat(first.name()).isEqualTo("深中华A");
        assertThat(first.industry()).isEqualTo("饰品");
        assertThat(first.latestPrice()).isEqualByComparingTo(new BigDecimal("7.11"));
        assertThat(first.changePercent()).isEqualByComparingTo(new BigDecimal("10.06"));
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("294182288"));
        assertThat(first.turnoverRate()).isEqualByComparingTo(new BigDecimal("9.43"));
        assertThat(first.consecutiveBoards()).isEqualTo(2);
        assertThat(first.statDays()).isEqualTo(2);
        assertThat(first.statBoards()).isEqualTo(2);
        assertThat(first.firstSealTime()).isEqualTo(LocalTime.of(9, 25, 0));
        assertThat(first.lastSealTime()).isEqualTo(LocalTime.of(9, 39, 9));
        assertThat(first.sealBreakCount()).isEqualTo(1);
        assertThat(first.sealFunds()).isEqualByComparingTo(new BigDecimal("39809601"));
    }

    @Test
    void shouldParseBrokenBoardAndLimitDownPoolEntries() throws Exception {
        String brokenPayload = "{\"rc\":0,\"data\":{\"tc\":1,\"pool\":["
                + "{\"c\":\"600250\",\"m\":1,\"n\":\"南京商旅\",\"p\":9150,\"ztp\":10160,\"zdp\":-0.97,"
                + "\"amount\":528110016,\"hs\":17.43,\"fbt\":93030,\"zbc\":1,\"zf\":11.25,"
                + "\"zttj\":{\"days\":2,\"ct\":1},\"hybk\":\"旅游及景\"}"
                + "]}}";
        String limitDownPayload = "{\"rc\":0,\"data\":{\"tc\":1,\"pool\":["
                + "{\"c\":\"603102\",\"m\":1,\"n\":\"百合股份\",\"p\":39720,\"zdp\":-9.99,\"amount\":284086912,"
                + "\"fund\":4202376,\"lbt\":150000,\"days\":1,\"oc\":13,\"hybk\":\"食品加工\"}"
                + "]}}";

        List<EastMoneyBrokenBoardPoolEntry> broken = client.readBrokenBoardPoolEntries(
                objectMapper.readTree(brokenPayload).path("data").path("pool"));
        List<EastMoneyLimitDownPoolEntry> limitDown = client.readLimitDownPoolEntries(
                objectMapper.readTree(limitDownPayload).path("data").path("pool"));

        assertThat(broken).singleElement().satisfies(entry -> {
            assertThat(entry.symbol()).isEqualTo("600250");
            assertThat(entry.industry()).isEqualTo("旅游及景");
            assertThat(entry.latestPrice()).isEqualByComparingTo(new BigDecimal("9.15"));
            assertThat(entry.limitUpPrice()).isEqualByComparingTo(new BigDecimal("10.16"));
            assertThat(entry.changePercent()).isEqualByComparingTo(new BigDecimal("-0.97"));
            assertThat(entry.firstSealTime()).isEqualTo(LocalTime.of(9, 30, 30));
            assertThat(entry.sealBreakCount()).isEqualTo(1);
        });
        assertThat(limitDown).singleElement().satisfies(entry -> {
            assertThat(entry.symbol()).isEqualTo("603102");
            assertThat(entry.industry()).isEqualTo("食品加工");
            assertThat(entry.changePercent()).isEqualByComparingTo(new BigDecimal("-9.99"));
            assertThat(entry.sealFunds()).isEqualByComparingTo(new BigDecimal("4202376"));
            assertThat(entry.lastSealTime()).isEqualTo(LocalTime.of(15, 0, 0));
            assertThat(entry.openCount()).isEqualTo(13);
        });
    }

    @Test
    void shouldBuildLimitPoolUrlWithTradeDateAndSort() {
        assertThat(client.limitPoolUrl("getTopicZTPool", LocalDate.of(2026, 8, 21), "fbt:asc"))
                .contains("getTopicZTPool")
                .contains("dpt=wz.ztzt")
                .contains("sort=fbt%3Aasc")
                .contains("date=20260821");
    }

    @Test
    void shouldParseIndexVolumeBarsFromTencentDailyRows() throws Exception {
        JsonNode rows = objectMapper.readTree("""
                [
                  ["2026-08-20", "3907.210", "3903.720", "3925.060", "3888.100", "506633954.000"],
                  ["2026-08-21", "3891.180", "3905.200", "3912.130", "3883.790", "446895868.000"],
                  ["2026-08-19", "broken-row"]
                ]
                """);

        List<EastMoneyIndexVolumeBar> bars = client.readIndexVolumeBars(rows);

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).tradeDate()).isEqualTo(LocalDate.parse("2026-08-20"));
        assertThat(bars.get(0).volumeHands()).isEqualByComparingTo(new BigDecimal("506633954"));
        assertThat(bars.get(1).tradeDate()).isEqualTo(LocalDate.parse("2026-08-21"));
        assertThat(bars.get(1).volumeHands()).isEqualByComparingTo(new BigDecimal("446895868"));
    }

    @Test
    void shouldPreferStablePush2DelayHostAndKeepFallbackQuery() {
        String url = "https://29.push2.eastmoney.com/api/qt/clist/get?pn=1&fs=m:0+t:6,m:1+t:2&fields=f2,f3,f12";

        List<String> candidates = client.remoteCandidateUrls(url);

        assertThat(candidates.get(0)).startsWith("https://push2delay.eastmoney.com/");
        assertThat(candidates)
                .anySatisfy(candidate -> assertThat(candidate).startsWith("https://push2.eastmoney.com/"))
                .anySatisfy(candidate -> assertThat(candidate).startsWith("https://push2delay.eastmoney.com/"))
                .anySatisfy(candidate -> assertThat(candidate).startsWith("https://17.push2.eastmoney.com/"))
                .contains(url)
                .anySatisfy(candidate -> assertThat(candidate).contains("fs=m:0+t:6,m:1+t:2&fields=f2,f3,f12"));
    }

    @Test
    void shouldKeepNonPush2UrlAsSingleCandidate() {
        String url = "https://datacenter-web.eastmoney.com/api/data/v1/get?filter=(SECURITY_CODE%3D%22002772%22)";

        assertThat(client.remoteCandidateUrls(url)).containsExactly(url);
    }

    @Test
    void shouldPreferTencentFinanceProxyForDailyKlineRequests() {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get"
                + "?param=sz300700,day,2026-09-02,2026-09-02,500,qfq";

        assertThat(client.remoteCandidateUrls(url)).containsExactly(
                "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get"
                        + "?param=sz300700,day,2026-09-02,2026-09-02,500,qfq",
                url);
    }

    @Test
    void currentDailySliceIncludesTodayWhichTencentOmitsForAFixedEnd() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusDays(3);
        List<String> urls = new ArrayList<>();
        EastMoneyClient history = tencentDailyClient(url -> tencentDailyBody(List.of(
                tencentBar(begin), tencentBar(url.contains(",,500,qfq") ? today : today.minusDays(1)))), urls);

        EastMoneyKLineSeries series = history.fetchDailyKLineSeries("600893", begin, today);

        assertThat(series.complete()).isTrue();
        assertThat(series.sourceName()).isEqualTo("TENCENT_QFQ_DAILY");
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(begin, today);
        assertThat(urls).singleElement().asString().contains(",day," + begin + ",,500,qfq");
    }

    @Test
    void onlyTheCurrentLastSliceUsesAnOpenEnd() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusYears(1).minusDays(3);
        LocalDate firstEnd = begin.plusYears(1).minusDays(1);
        List<String> urls = new ArrayList<>();
        EastMoneyClient history = tencentDailyClient(url -> {
            String[] fields = url.substring(url.indexOf("?param=") + 7).split(",", -1);
            LocalDate start = LocalDate.parse(fields[2]);
            LocalDate end = fields[3].isEmpty() ? today : LocalDate.parse(fields[3]);
            // The rolling endpoint can also return rows belonging to the previous slice.
            return tencentDailyBody(fields[3].isEmpty()
                    ? List.of(tencentBar(firstEnd), tencentBar(start), tencentBar(end))
                    : List.of(tencentBar(start), tencentBar(end)));
        }, urls);

        EastMoneyKLineSeries series = history.fetchDailyKLineSeries("600893", begin, today);

        assertThat(series.complete()).isTrue();
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate)
                .containsExactly(begin, firstEnd, firstEnd.plusDays(1), today);
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).contains(",day," + begin + "," + firstEnd + ",500,qfq");
        assertThat(urls.get(1)).contains(",day," + firstEnd.plusDays(1) + ",,500,qfq");
    }

    @Test
    void historicalDailyRequestsKeepTheirExplicitEnd() {
        LocalDate end = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2);
        LocalDate begin = end.minusDays(3);
        List<String> urls = new ArrayList<>();
        EastMoneyClient history = tencentDailyClient(
                url -> tencentDailyBody(List.of(tencentBar(begin), tencentBar(end))), urls);

        EastMoneyKLineSeries series = history.fetchDailyKLineSeries("600893", begin, end);

        assertThat(series.complete()).isTrue();
        assertThat(urls).singleElement().asString().contains(",day," + begin + "," + end + ",500,qfq");
    }

    @Test
    void rollingDailyResponseIsCroppedToRequestedDatesWithExcludedRowsDisclosed() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusDays(3);
        EastMoneyClient history = tencentDailyClient(url -> tencentDailyBody(List.of(
                tencentBar(begin.minusDays(1)), tencentBar(begin), tencentBar(today),
                tencentBar(today.plusDays(1)))), new ArrayList<>());

        EastMoneyKLineSeries series = history.fetchDailyKLineSeries("600893", begin, today);

        assertThat(series.complete()).isTrue();
        assertThat(series.rows()).extracting(EastMoneyKLine::tradeDate).containsExactly(begin, today);
        assertThat(series.detail()).contains("解析 4/4", "排除 2 行区间外数据");
    }

    @Test
    void croppingCannotHideMalformedDuplicateOrInvalidOhlcRows() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusDays(3);
        LocalDate outside = begin.minusDays(1);
        for (List<List<String>> extras : List.of(
                List.of(List.of(outside.toString(), "broken")),
                List.of(List.of(outside.toString(), "10", "11", "9", "8", "100")),
                List.of(tencentBar(outside), tencentBar(outside)),
                List.of(tencentBar(today)))) {
            List<List<String>> rows = new ArrayList<>(List.of(tencentBar(begin), tencentBar(today)));
            rows.addAll(extras);
            EastMoneyClient history = tencentDailyClient(url -> tencentDailyBody(rows), new ArrayList<>());

            EastMoneyKLineSeries series = history.fetchDailyKLineSeries("600893", begin, today);

            assertThat(series.complete()).as("invalid raw rows %s", extras).isFalse();
            assertThat(series.sourceName()).isEqualTo("HISTORICAL_KLINE_PARTIAL");
            assertThat(series.detail()).contains("拒绝使用部分数据");
            if (extras.get(0).size() < 6) assertThat(series.detail()).contains("解析 2/3");
        }
    }

    private EastMoneyClient tencentDailyClient(Function<String, String> response, List<String> urls) {
        return new EastMoneyClient(null, objectMapper, properties()) {
            @Override
            String fetchBodyWithCurl(String url, Exception failure, String referer) {
                if (!url.contains("/fqkline/get")) return "{\"data\":{\"klines\":[]}}";
                urls.add(url);
                return response.apply(url);
            }
        };
    }

    private String tencentDailyBody(List<List<String>> rows) {
        return objectMapper.valueToTree(Map.of("data", Map.of("sh600893", Map.of("qfqday", rows)))).toString();
    }

    private List<String> tencentBar(LocalDate date) {
        return List.of(date.toString(), "10", "11", "12", "9", "100");
    }

    @Test
    void quotePageUrlUsesStableSecurityCodeSort() {
        String url = client.ashareQuotePageUrl(2, 100);

        assertThat(url)
                .contains("pn=2", "pz=100", "po=1", "fid=f12")
                .doesNotContain("fid=f6");
    }

    @Test
    void snapshotContinuesPastADuplicatePageToCollectLaterUniqueRows() {
        DuplicateMiddlePageStubClient snapshotClient = new DuplicateMiddlePageStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(3);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600001", "600002", "600003");
        assertThat(snapshotClient.requestedPages).isEqualTo(3);
    }

    @Test
    void conflictingQuoteCannotKeepAnEarlierInRangePriceFromTheSamePage() {
        Instant marketAt = Instant.parse("2026-09-08T02:00:00Z");
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyQuote inside = snapshotQuote("600001", "10.10", "10", "1", "100", day, marketAt, marketAt);
        EastMoneyQuote outside = snapshotQuote("600001", "10.30", "10", "3", "100", day, marketAt, marketAt);
        EastMoneyQuote other = snapshotQuote("600002", "10", "10", "0", "100", day, marketAt, marketAt);

        AshareQuoteSnapshot snapshot = quotePages(2, List.of(List.of(inside, outside, other)))
                .fetchEastMoneyQuoteSnapshot(10000);

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600002");
        assertThat(snapshot.expectedCount()).isEqualTo(2);
        assertThat(snapshot.fetchedCount()).isEqualTo(1);
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void aConflictingSymbolCannotReturnThroughALaterDuplicatePage() {
        Instant marketAt = Instant.parse("2026-09-08T02:00:00Z");
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyQuote inside = snapshotQuote("600001", "10.10", "10", "1", "100", day, marketAt, marketAt);
        EastMoneyQuote outside = snapshotQuote("600001", "10.30", "10", "3", "100", day, marketAt, marketAt);
        EastMoneyQuote other = snapshotQuote("600002", "10", "10", "0", "100", day, marketAt, marketAt);

        AshareQuoteSnapshot snapshot = quotePages(2, List.of(List.of(inside), List.of(outside), List.of(inside, other)))
                .fetchEastMoneyQuoteSnapshot(10000);

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600002");
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void everyExecutionBusinessFieldMustAgreeAcrossDuplicateQuotes() {
        Instant marketAt = Instant.parse("2026-09-08T02:00:00Z");
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyQuote first = snapshotQuote("600001", "10.10", "10", "1", "100", day, marketAt, marketAt);
        EastMoneyQuote other = snapshotQuote("600002", "10", "10", "0", "100", day, marketAt, marketAt);
        for (EastMoneyQuote conflict : List.of(
                snapshotQuote("600001", "10.30", "10", "1", "100", day, marketAt, marketAt),
                snapshotQuote("600001", "10.10", "10.30", "1", "100", day, marketAt, marketAt),
                snapshotQuote("600001", "10.10", "10", "3", "100", day, marketAt, marketAt),
                snapshotQuote("600001", "10.10", "10", "1", "101", day, marketAt, marketAt),
                snapshotQuote("600001", "10.10", "10", "1", "100", day.minusDays(1), marketAt, marketAt),
                snapshotQuote("600001", "10.10", "10", "1", "100", day, marketAt.plusSeconds(1), marketAt))) {
            AshareQuoteSnapshot snapshot = quotePages(2, List.of(List.of(first, conflict, other)))
                    .fetchEastMoneyQuoteSnapshot(10000);

            assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600002");
            assertThat(snapshot.missingCount()).isEqualTo(1);
        }
    }

    @Test
    void matchingBusinessQuotesIgnoreFetchTimeAndDecimalScale() {
        Instant marketAt = Instant.parse("2026-09-08T02:00:00Z");
        LocalDate day = LocalDate.parse("2026-09-08");
        EastMoneyQuote first = snapshotQuote("600001", "10.10", "10", "1", "100", day, marketAt, marketAt);
        EastMoneyQuote later = snapshotQuote("600001", "10.100", "10.0", "1.00", "100.0", day, marketAt, marketAt.plusSeconds(20));
        EastMoneyQuote other = snapshotQuote("600002", "10", "10", "0", "100", day, marketAt, marketAt);

        AshareQuoteSnapshot snapshot = quotePages(2, List.of(List.of(first), List.of(later, other)))
                .fetchEastMoneyQuoteSnapshot(10000);

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("600001", "600002");
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.missingCount()).isZero();
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void snapshotRetriesOnlyTheFailedPageAfterATransientProviderError() {
        TransientPageFailureStubClient snapshotClient = new TransientPageFailureStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(2);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(2);
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600001", "600002");
        assertThat(snapshotClient.firstPageAttempts).isEqualTo(1);
        assertThat(snapshotClient.secondPageAttempts).isEqualTo(2);
    }

    @Test
    void snapshotUsesLivePaginationAsCanonicalUniverse() {
        SnapshotStubClient snapshotClient = new SnapshotStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(3);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600001", "000001", "920001");
    }

    @Test
    void snapshotDoesNotRestrictLiveQuotesToBundledSecurityMaster() {
        DynamicFallbackStubClient snapshotClient = new DynamicFallbackStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(6000);

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("688999");
        assertThat(snapshot.expectedCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void snapshotContinuesWhenRemoteCapsRowsBelowRequestedPageSize() {
        CappedPageStubClient snapshotClient = new CappedPageStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(250);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(250);
        assertThat(snapshot.fetchedCount()).isEqualTo(250);
        assertThat(snapshot.quotes()).hasSize(250);
        assertThat(snapshotClient.requestedPages).isEqualTo(3);
        assertThat(snapshotClient.requestedPageSizes).containsOnly(100);
    }

    @Test
    void snapshotKeepsSourceReportedUniverseWhenRequestIsOnlyASample() {
        SampledUniverseStubClient snapshotClient = new SampledUniverseStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(100);

        assertThat(snapshot.requestedCount()).isEqualTo(100);
        assertThat(snapshot.expectedCount()).isEqualTo(5000);
        assertThat(snapshot.fetchedCount()).isEqualTo(100);
        assertThat(snapshot.missingCount()).isEqualTo(4900);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotMarksChangingSourceReportedUniverseAsUnknown() {
        InconsistentUniverseStubClient snapshotClient = new InconsistentUniverseStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(snapshot.expectedCount()).isZero();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotRejectsZeroReportedTotalOnLaterNonEmptyPage() {
        MissingLaterTotalStubClient snapshotClient = new MissingLaterTotalStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(snapshot.expectedCount()).isZero();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotAllowsZeroReportedTotalOnEmptyTerminalPage() {
        EmptyTerminalPageStubClient snapshotClient = new EmptyTerminalPageStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchEastMoneyQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(snapshot.expectedCount()).isEqualTo(2);
        assertThat(snapshot.fetchedCount()).isEqualTo(1);
        assertThat(snapshot.missingCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isFalse();
    }

    private LiveDataProperties properties() {
        return new LiveDataProperties(
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_URL,
                LiveDataProperties.DEFAULT_EASTMONEY_FUND_FLOW_MINUTE_URL,
                20,
                "https://quote.example.com",
                "https://financial.example.com",
                "https://cninfo.example.com",
                "https://policy.example.com",
                false,
                12,
                2,
                6,
                LiveDataProperties.DEFAULT_YAHOO_SPARK_URL,
                null
        );
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private EastMoneyClient quotePages(int total, List<List<EastMoneyQuote>> pages) {
        return new EastMoneyClient(null, objectMapper, null) {
            @Override
            AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
                return new AshareQuotePage(total, pageNumber <= pages.size() ? pages.get(pageNumber - 1) : List.of());
            }
        };
    }

    private EastMoneyQuote snapshotQuote(String symbol, String latest, String open, String change, String volume,
                                         LocalDate day, Instant marketAt, Instant fetchedAt) {
        return new EastMoneyQuote(symbol, "示例公司", "上交所", "示例行业", bd(latest), bd(change), null,
                bd(volume), null, null, null, null, "页面夹具", "https://quote.example/" + symbol,
                fetchedAt, day, marketAt, null, bd(open), null, null);
    }

    private static final class SnapshotStubClient extends EastMoneyClient {

        private SnapshotStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (pageNumber > 1) {
                return new AshareQuotePage(3, List.of());
            }
            return new AshareQuotePage(3, List.of(
                    quote("600001", "银行", "东方财富实时全市场"),
                    quote("000001", "软件", "东方财富实时全市场"),
                    quote("920001", "工业", "东方财富实时全市场")
            ));
        }

        private static EastMoneyQuote quote(String symbol, String industry, String source) {
            return new EastMoneyQuote(
                    symbol,
                    "样本" + symbol,
                    symbol.startsWith("6") ? "上交所" : symbol.startsWith("92") ? "北交所" : "深交所",
                    industry,
                    new BigDecimal("10"),
                    BigDecimal.ZERO,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    new BigDecimal("100000000"),
                    new BigDecimal("20"),
                    new BigDecimal("2"),
                    new BigDecimal("20"),
                    source,
                    "https://quote.example.com/" + symbol,
                    Instant.parse("2026-07-10T07:00:00Z")
            );
        }
    }

    private static final class DynamicFallbackStubClient extends EastMoneyClient {

        private DynamicFallbackStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            return pageNumber == 1
                    ? new AshareQuotePage(1, List.of(SnapshotStubClient.quote(
                            "688999", "半导体", "东方财富实时全市场")))
                    : new AshareQuotePage(1, List.of());
        }
    }

    private static final class CappedPageStubClient extends EastMoneyClient {

        private int requestedPages;
        private final java.util.Set<Integer> requestedPageSizes = new java.util.LinkedHashSet<>();

        private CappedPageStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            requestedPages = Math.max(requestedPages, pageNumber);
            requestedPageSizes.add(pageSize);
            int start = (pageNumber - 1) * 100;
            if (start >= 250) {
                return new AshareQuotePage(250, List.of());
            }
            int end = Math.min(250, start + 100);
            return new AshareQuotePage(
                    250,
                    IntStream.range(start, end)
                            .mapToObj(index -> SnapshotStubClient.quote(
                                    String.format("600%03d", index),
                                    "工业",
                                    "东方财富实时全市场"
                            ))
                            .toList()
            );
        }
    }

    private static final class SampledUniverseStubClient extends EastMoneyClient {

        private SampledUniverseStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (pageNumber > 1) {
                return new AshareQuotePage(5000, List.of());
            }
            return new AshareQuotePage(
                    5000,
                    java.util.stream.IntStream.range(0, 100)
                            .mapToObj(index -> SnapshotStubClient.quote(
                                    String.format("600%03d", index),
                                    "工业",
                                    "东方财富实时全市场"
                            ))
                            .toList()
            );
        }
    }

    private static final class InconsistentUniverseStubClient extends EastMoneyClient {

        private InconsistentUniverseStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (pageNumber > 2) {
                return new AshareQuotePage(3, List.of());
            }
            return new AshareQuotePage(
                    pageNumber == 1 ? 2 : 3,
                    List.of(SnapshotStubClient.quote(
                            pageNumber == 1 ? "600001" : "600002",
                            "工业",
                            "东方财富实时全市场"
                    ))
            );
        }
    }

    private static final class MissingLaterTotalStubClient extends EastMoneyClient {

        private MissingLaterTotalStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (pageNumber > 2) {
                return new AshareQuotePage(0, List.of());
            }
            return new AshareQuotePage(
                    pageNumber == 1 ? 2 : 0,
                    List.of(SnapshotStubClient.quote(
                            pageNumber == 1 ? "600001" : "600002",
                            "工业",
                            "东方财富实时全市场"
                    ))
            );
        }
    }

    private static final class EmptyTerminalPageStubClient extends EastMoneyClient {

        private EmptyTerminalPageStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            return pageNumber == 1
                    ? new AshareQuotePage(2, List.of(SnapshotStubClient.quote(
                            "600001", "工业", "东方财富实时全市场")))
                    : new AshareQuotePage(0, List.of());
        }
    }

    private static final class DuplicateMiddlePageStubClient extends EastMoneyClient {

        private int requestedPages;

        private DuplicateMiddlePageStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            requestedPages = Math.max(requestedPages, pageNumber);
            return switch (pageNumber) {
                case 1 -> new AshareQuotePage(3, List.of(
                        SnapshotStubClient.quote("600001", "工业", "东方财富实时全市场")));
                case 2 -> new AshareQuotePage(3, List.of(
                        SnapshotStubClient.quote("600001", "工业", "东方财富实时全市场")));
                case 3 -> new AshareQuotePage(3, List.of(
                        SnapshotStubClient.quote("600002", "工业", "东方财富实时全市场"),
                        SnapshotStubClient.quote("600003", "工业", "东方财富实时全市场")));
                default -> new AshareQuotePage(3, List.of());
            };
        }
    }

    private static final class TransientPageFailureStubClient extends EastMoneyClient {

        private int firstPageAttempts;
        private int secondPageAttempts;

        private TransientPageFailureStubClient() {
            super(null, new ObjectMapper(), null);
        }

        @Override
        AshareQuotePage fetchAshareQuotePage(int pageNumber, int pageSize) {
            if (pageNumber == 1) {
                firstPageAttempts++;
                return new AshareQuotePage(2, List.of(
                        SnapshotStubClient.quote("600001", "工业", "东方财富实时全市场")));
            }
            if (pageNumber == 2) {
                secondPageAttempts++;
                if (secondPageAttempts == 1) {
                    throw new IllegalStateException("transient provider error");
                }
                return new AshareQuotePage(2, List.of(
                        SnapshotStubClient.quote("600002", "工业", "东方财富实时全市场")));
            }
            return new AshareQuotePage(2, List.of());
        }
    }
}
