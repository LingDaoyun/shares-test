package com.aistock.research.mispricing;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.quality.AgentConsensusBrief;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.quality.PeerValuationBrief;
import com.aistock.research.quality.PeerValuationBriefPeer;
import com.aistock.research.quality.RecommendationEvidenceBundle;
import com.aistock.research.quality.RecommendationEvidenceEnrichmentService;
import com.aistock.research.tech.TechTrackingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MispricingServiceTest {

    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final StubTechTrackingService techTrackingService = new StubTechTrackingService();
    private final MispricingService service = new MispricingService(eastMoneyClient, techTrackingService);

    @Test
    void shouldReturnEmptyInsteadOfFallingBackToAStaticWhitelist() {
        MispricingReport report = service.report(10, new BigDecimal("82"), null, null, null, 6000);

        assertThat(report.universeCount()).isZero();
        assertThat(report.candidates()).isEmpty();
        assertThat(report.quoteNote()).contains("未使用静态股票白名单");
    }

    @Test
    void shouldPromoteQualityValueAssetsWhenHotThemeIsOverheated() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("000333", "美的集团", "家电", "13.10", "2.20", "-0.50"),
                quoteWithIndustry("600690", "海尔智家", "家电", "10.20", "1.50", "-0.50"),
                quoteWithIndustry("600036", "招商银行", "银行", "5.90", "0.80", "-0.50"),
                quoteWithIndustry("600027", "华电国际", "火电", "8.00", "1.05", "-0.50"),
                quoteWithIndustry("002772", "众兴菌业", "农业", "9.50", "1.20", "-0.50")
        );

        MispricingReport report = service.report(22, new BigDecimal("82"), null, null, null);

        assertThat(report.styleHeat().heatScore()).isEqualByComparingTo("82");
        assertThat(report.ruleSet().minQualityScore()).isEqualByComparingTo("78");
        assertThat(report.candidates()).extracting(MispricedAsset::symbol)
                .contains("000333", "600690", "600036");
        assertThat(find(report, "000333").action()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(find(report, "000333").actionLabel()).isEqualTo("财报复核");
        assertThat(find(report, "000333").todayAdvice().action()).isEqualTo("WAIT");
        assertThat(find(report, "000333").todayAdvice().summary()).contains("点时财报");
        assertThat(find(report, "000333").evidenceCompleteness().missingEvidence()).contains("公告/定期报告反证", "行业估值对比");
        assertThat(find(report, "000333").review().statusLabel()).isEqualTo("系统已核验：财报证据待补");
        assertThat(find(report, "600027").action()).isEqualTo("CYCLICAL_OBSERVE");
        assertThat(find(report, "600027").actionLabel()).isEqualTo("周期交易观察");
        assertThat(find(report, "600027").evidence()).anySatisfy(evidence ->
                assertThat(evidence.title()).contains("周期属性复核"));
        assertThat(find(report, "600027").review().conclusion()).contains("本轮剔出优质错杀池");
        assertThat(find(report, "600027").review().blockers()).anySatisfy(blocker ->
                assertThat(blocker).contains("供需").contains("价格"));
        assertThat(find(report, "002772").action()).isEqualTo("CYCLICAL_OBSERVE");
        assertThat(find(report, "002772").review().status()).isEqualTo("CYCLICAL_ONLY");
    }

    @Test
    void shouldWaitWhenHotThemeIsNotOverheated() {
        eastMoneyClient.quotes = List.of(
                quote("000333", "美的集团", "13.10", "2.20"),
                quote("600690", "海尔智家", "10.20", "1.50")
        );

        MispricingReport report = service.report(3, new BigDecimal("55"), null, null, null);

        assertThat(report.candidates()).allMatch(asset -> !"ACCUMULATE_WEAKNESS".equals(asset.action()));
        assertThat(find(report, "000333").action()).isEqualTo("WAIT_HOT_OVERHEAT");
    }

    @Test
    void shouldWaitForWeakDayWhenCandidateAlreadyRunsUp() {
        eastMoneyClient.quotes = List.of(
                quote("000333", "美的集团", "13.10", "2.20", "2.50")
        );

        MispricingReport report = service.report(3, new BigDecimal("82"), null, null, null);

        MispricedAsset asset = find(report, "000333");
        assertThat(asset.action()).isEqualTo("WAIT_WEAK_DAY");
        assertThat(asset.actionLabel()).isEqualTo("等弱势日");
        assertThat(asset.todayAdvice().action()).isEqualTo("HOLD");
        assertThat(asset.review().status()).isEqualTo("WAIT_PRICE_CONFIRM");
        assertThat(asset.review().blockers()).anySatisfy(blocker -> assertThat(blocker).contains("当日涨幅超过"));
    }

    @Test
    void shouldBlockAddWhenOnlyProxyQualityScoresExist() {
        StubRecommendationEvidenceEnrichmentService enrichmentService = new StubRecommendationEvidenceEnrichmentService();
        MispricingService evidenceAwareService = new MispricingService(
                eastMoneyClient,
                techTrackingService,
                null,
                new EvidenceCompletenessService(),
                enrichmentService
        );
        eastMoneyClient.quotes = List.of(
                quote("000333", "美的集团", "13.10", "2.20")
        );
        enrichmentService.bundle = completeBundle("000333");

        MispricingReport report = evidenceAwareService.report(3, new BigDecimal("82"), null, null, null);

        MispricedAsset asset = find(report, "000333");
        assertThat(asset.evidenceBundle().peerValuation().available()).isTrue();
        assertThat(asset.evidenceBundle().agentConsensus().available()).isTrue();
        assertThat(asset.evidenceCompleteness().allowsBuy()).isFalse();
        assertThat(asset.evidenceCompleteness().missingEvidence()).contains("近三年财报质量");
        assertThat(asset.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(asset.todayAdvice().summary()).contains("点时财报");
    }

    @Test
    void shouldFallbackToNeutralHeatWhenAutoHeatIsUnavailable() {
        techTrackingService.failure = new IllegalStateException("行情源临时不可用");
        eastMoneyClient.quotes = List.of(
                quote("000333", "美的集团", "13.10", "2.20")
        );

        MispricingReport report = service.report(3, BigDecimal.ZERO, null, null, null);

        assertThat(report.styleHeat().heatScore()).isEqualByComparingTo("60");
        assertThat(report.styleHeat().signals()).anySatisfy(signal -> assertThat(signal).contains("自动热度暂不可用"));
        assertThat(find(report, "000333").action()).isEqualTo("WAIT_HOT_OVERHEAT");
    }

    @Test
    void shouldUseTencentToReviewCandidatesFromTheDynamicMarketPool() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600036", "招商银行", "银行", "5.90", "0.80", "-0.50")
        );
        eastMoneyClient.tencentQuotes = List.of(
                quoteWithIndustry("600036", "招商银行", "银行", "5.90", "0.80", "-0.50")
        );

        MispricingReport report = service.report(22, new BigDecimal("82"), null, null, null);

        MispricedAsset cmb = find(report, "600036");
        assertThat(report.quoteNote()).contains("不注入任何静态股票白名单");
        assertThat(cmb.action()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(cmb.strengths()).anySatisfy(source -> assertThat(source).contains("不是固定股票白名单"));
    }

    @Test
    void shouldUseTencentPerSharePriceWhenEastMoneyReturnsInvalidTotalAmountAsPrice() {
        eastMoneyClient.quotes = List.of(
                withPriceAndTimestamp(
                        quote("600036", "招商银行", "5.90", "0.80", "-0.50", "653770000"),
                        "653770000", "2026-07-01T07:31:00Z")
        );
        eastMoneyClient.tencentQuotes = List.of(
                withPriceAndTimestamp(
                        quote("600036", "招商银行", "5.90", "0.80", "-0.50", "36.83"),
                        "36.83", "2026-07-01T07:30:00Z")
        );

        MispricingReport report = service.report(3, new BigDecimal("82"), null, null, null);

        MispricedAsset cmb = find(report, "600036");
        assertThat(cmb.latestPrice()).isEqualByComparingTo("36.83");
        assertThat(cmb.marketTimestamp()).isEqualTo(Instant.parse("2026-07-01T07:30:00Z"));
        assertThat(report.quoteNote()).contains("不注入任何静态股票白名单");
    }

    @Test
    void shouldAddDynamicCandidatesFromAshareScan() {
        eastMoneyClient.marketQuotes = List.of(
                quoteWithIndustry("601998", "中信银行", "银行", "5.80", "0.62", "-0.30")
        );

        MispricingReport report = service.report(5, new BigDecimal("82"), null, null, null, 100);

        MispricedAsset asset = find(report, "601998");
        assertThat(report.ruleSet().scanLimit()).isEqualTo(100);
        assertThat(report.quoteNote()).contains("内置 A 股代码索引 + 腾讯批量行情优先");
        assertThat(report.quoteNote()).contains("新增动态错杀候选 1 条");
        assertThat(asset.assetGroup()).isEqualTo("低估金融");
        assertThat(asset.strengths()).anySatisfy(strength -> assertThat(strength).contains("全 A 股动态初筛"));
        assertThat(asset.action()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(asset.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(asset.evidenceCompleteness().status()).isEqualTo("INSUFFICIENT");
    }

    @Test
    void shouldUseUniversalScreenerForDynamicCandidatesWhenRealtimeReviewIsAvailable() {
        EastMoneyQuote nonSeed = quoteWithIndustry("601998", "中信银行", "银行", "5.80", "0.62", "-0.30");
        eastMoneyClient.marketQuotes = List.of(nonSeed);
        eastMoneyClient.tencentQuotes = List.of(nonSeed);

        MispricingReport report = service.report(5, new BigDecimal("82"), null, null, null, 100);

        MispricedAsset asset = find(report, "601998");
        assertThat(report.quoteNote()).contains("统一全 A 候选漏斗");
        assertThat(asset.assetGroup()).isEqualTo("低估金融");
        assertThat(asset.marketTimestamp()).isEqualTo(Instant.parse("2026-07-01T07:30:00Z"));
        assertThat(asset.strengths()).anySatisfy(strength -> assertThat(strength).contains("全 A 股动态初筛"));
    }

    @Test
    void shouldKeepUniversalPriceSourceMetadataInMispricingReview() {
        EastMoneyQuote cycleCandidate = quoteWithIndustry(
                "002714", "周期样本", "生猪养殖", "12.00", "1.20", "-0.30");
        eastMoneyClient.marketQuotes = List.of(cycleCandidate);
        eastMoneyClient.tencentQuotes = List.of(cycleCandidate);

        MispricingReport report = service.report(5, new BigDecimal("82"), null, null, null, 100);

        MispricedAsset asset = find(report, "002714");
        assertThat(asset.review().sources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("测试行情与估值");
            assertThat(source.url()).isEqualTo("https://quote.example.com/002714");
        });
    }

    private MispricedAsset find(MispricingReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private EastMoneyQuote quote(String symbol, String name, String pe, String pb) {
        return quote(symbol, name, pe, pb, "-0.50");
    }

    private EastMoneyQuote quote(String symbol, String name, String pe, String pb, String changePercent) {
        return quote(symbol, name, pe, pb, changePercent, "10.00");
    }

    private EastMoneyQuote quote(String symbol, String name, String pe, String pb, String changePercent, String latestPrice) {
        return quote(symbol, name, "测试行业", pe, pb, changePercent, latestPrice);
    }

    private EastMoneyQuote quoteWithIndustry(String symbol, String name, String industry, String pe, String pb, String changePercent) {
        return quote(symbol, name, industry, pe, pb, changePercent, "10.00");
    }

    private EastMoneyQuote quote(String symbol, String name, String industry, String pe, String pb, String changePercent, String latestPrice) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                industry,
                new BigDecimal(latestPrice),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal("100000000"),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-01T07:30:01Z"),
                LocalDate.parse("2026-07-01"),
                Instant.parse("2026-07-01T07:30:00Z")
        );
    }

    private EastMoneyQuote withPriceAndTimestamp(
            EastMoneyQuote quote,
            String latestPrice,
            String marketTimestamp
    ) {
        Instant timestamp = Instant.parse(marketTimestamp);
        return new EastMoneyQuote(
                quote.symbol(), quote.name(), quote.market(), quote.industry(), new BigDecimal(latestPrice),
                quote.changePercent(), quote.turnoverRate(), quote.volume(), quote.amount(), quote.peRatio(),
                quote.pbRatio(), quote.peTtm(), quote.sourceName(), quote.quoteUrl(), timestamp.plusSeconds(1),
                timestamp.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate(), timestamp);
    }

    private RecommendationEvidenceBundle completeBundle(String symbol) {
        return new RecommendationEvidenceBundle(
                symbol,
                new PeerValuationBrief(
                        true,
                        "同行业可比",
                        6,
                        new BigDecimal("13.10"),
                        new BigDecimal("2.20"),
                        new BigDecimal("18.50"),
                        new BigDecimal("2.80"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.35"),
                        List.of(new PeerValuationBriefPeer(
                                "000651",
                                "格力电器",
                                "同行业",
                                new BigDecimal("12.30"),
                                new BigDecimal("1.75"),
                                new BigDecimal("42.00")
                        )),
                        List.of("已形成同行业估值样本。"),
                        List.of()
                ),
                new AgentConsensusBrief(
                        true,
                        "可观察",
                        new BigDecimal("80"),
                        4,
                        1,
                        0,
                        0,
                        "反方暂未发现硬性否决。",
                        List.of("近 10 年 ROE/现金流/毛利率序列"),
                        List.of(),
                        List.of()
                ),
                List.of()
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> quotes = List.of();
        private List<EastMoneyQuote> tencentQuotes = List.of();
        private List<EastMoneyQuote> marketQuotes = List.of();
        private RuntimeException primaryFailure;

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchEastMoneyQuotesBySymbols(List<String> symbols, int limit) {
            if (primaryFailure != null) {
                throw primaryFailure;
            }
            return quotes;
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            return tencentQuotes;
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            return (marketQuotes.isEmpty() ? quotes : marketQuotes).stream().limit(limit).toList();
        }

        @Override
        public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
            List<EastMoneyQuote> source = marketQuotes.isEmpty() ? quotes : marketQuotes;
            List<EastMoneyQuote> result = source.stream().limit(limit).toList();
            return new AshareQuoteSnapshot(
                    result,
                    limit,
                    result.size(),
                    result.size(),
                    0,
                    true,
                    "测试行情",
                    Instant.parse("2026-07-10T07:00:00Z")
            );
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return List.of();
        }
    }

    private static final class StubTechTrackingService extends TechTrackingService {

        private RuntimeException failure;

        private StubTechTrackingService() {
            super(null);
        }

        @Override
        public com.aistock.research.tech.TechTrackingReport report(
                Integer limit,
                BigDecimal coreMaxPe,
                BigDecimal coreMaxPb,
                BigDecimal hardMaxPe,
                BigDecimal hardMaxPb
        ) {
            if (failure != null) {
                throw failure;
            }
            throw new AssertionError("Tech tracking report should not be requested in this test");
        }
    }

    private static final class StubRecommendationEvidenceEnrichmentService extends RecommendationEvidenceEnrichmentService {

        private RecommendationEvidenceBundle bundle = RecommendationEvidenceBundle.unavailable("TEST");

        private StubRecommendationEvidenceEnrichmentService() {
            super();
        }

        @Override
        public RecommendationEvidenceBundle enrichForList(String symbol) {
            return bundle;
        }
    }
}
