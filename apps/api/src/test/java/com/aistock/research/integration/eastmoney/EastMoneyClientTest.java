package com.aistock.research.integration.eastmoney;

import com.aistock.research.config.LiveDataProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
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
                .put("f124", marketTime.getEpochSecond());

        EastMoneyQuote quote = client.readQuote(item, Instant.parse("2026-07-11T07:25:00Z")).orElseThrow();

        assertThat(quote.marketTimestamp()).isEqualTo(marketTime);
        assertThat(quote.tradeDate()).isEqualTo(LocalDate.parse("2026-07-11"));
        assertThat(quote.fetchedAt()).isEqualTo(Instant.parse("2026-07-11T07:25:00Z"));
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
    void snapshotUsesLivePaginationAsCanonicalUniverse() {
        SnapshotStubClient snapshotClient = new SnapshotStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(3);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.expectedCount()).isEqualTo(3);
        assertThat(snapshot.fetchedCount()).isEqualTo(3);
        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol)
                .containsExactly("600001", "000001", "920001");
    }

    @Test
    void snapshotDoesNotRestrictLiveQuotesToBundledSecurityMaster() {
        DynamicFallbackStubClient snapshotClient = new DynamicFallbackStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(6000);

        assertThat(snapshot.quotes()).extracting(EastMoneyQuote::symbol).containsExactly("688999");
        assertThat(snapshot.expectedCount()).isEqualTo(1);
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void snapshotContinuesWhenRemoteCapsRowsBelowRequestedPageSize() {
        CappedPageStubClient snapshotClient = new CappedPageStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(250);

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

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(100);

        assertThat(snapshot.requestedCount()).isEqualTo(100);
        assertThat(snapshot.expectedCount()).isEqualTo(5000);
        assertThat(snapshot.fetchedCount()).isEqualTo(100);
        assertThat(snapshot.missingCount()).isEqualTo(4900);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotMarksChangingSourceReportedUniverseAsUnknown() {
        InconsistentUniverseStubClient snapshotClient = new InconsistentUniverseStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(snapshot.expectedCount()).isZero();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotRejectsZeroReportedTotalOnLaterNonEmptyPage() {
        MissingLaterTotalStubClient snapshotClient = new MissingLaterTotalStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

        assertThat(snapshot.expectedCount()).isZero();
        assertThat(snapshot.fetchedCount()).isEqualTo(2);
        assertThat(snapshot.complete()).isFalse();
    }

    @Test
    void snapshotAllowsZeroReportedTotalOnEmptyTerminalPage() {
        EmptyTerminalPageStubClient snapshotClient = new EmptyTerminalPageStubClient();

        AshareQuoteSnapshot snapshot = snapshotClient.fetchAshareQuoteSnapshot(Integer.MAX_VALUE);

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
                List.of()
        );
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
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
}
