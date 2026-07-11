package com.aistock.research.market;

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
import com.aistock.research.universe.UniversalAshareScreener;
import com.aistock.research.universe.UniversalScreenExclusion;
import com.aistock.research.universe.UniversalScreenStageStats;
import com.aistock.research.valuation.ValuationContextState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketScanServiceTest {

    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final UniversalAshareScreener universalScreener = new UniversalAshareScreener(eastMoneyClient);
    private final MarketScanService service = new MarketScanService(universalScreener);

    @Test
    void shouldScanBroadMarketAndMergeRealtimeQuotes() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", null, "-0.30", "6.13", "0.84", "0"),
                quote("000100", "TCL科技", "光学光电子", "4.80", "-2.10", "42.00", "1.80", "280000000"),
                quote("002772", "众兴菌业", "农产品加工", "9.20", "1.10", "30.00", "1.20", "65000000"),
                quote("000002", "*ST测试", "房地产", "1.00", "-1.00", "8.00", "0.60", "90000000")
        );
        eastMoneyClient.tencentQuotes = List.of(
                quote("600036", "招商银行", null, "36.83", "-0.50", "6.15", "0.83", "900000000")
        );

        MarketScanReport report = service.report(5, 500, null, null, null, null, true, true, "VALUE");

        assertThat(report.scope()).contains("沪深北");
        assertThat(report.stageStats()).extracting(UniversalScreenStageStats::stage)
                .contains("UNIVERSE", "TRADABLE", "MODE_ELIGIBILITY", "LIQUIDITY", "FINAL");
        assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::stage)
                .contains("TRADABLE", "LIQUIDITY");
        assertThat(report.universeCount()).isEqualTo(4);
        assertThat(report.quoteNote()).contains("实时行情复核").contains("不使用缓存或示例数据");
        assertThat(report.candidates()).extracting(MarketScanCandidate::symbol)
                .contains("600036")
                .doesNotContain("002772")
                .doesNotContain("000002");
        MarketScanCandidate cmb = find(report, "600036");
        assertThat(cmb.latestPrice()).isEqualByComparingTo("36.83");
        assertThat(cmb.tags()).contains("低估值", "红利防守");
        assertThat(cmb.evidenceCompleteness().status()).isEqualTo("INSUFFICIENT");
        assertThat(cmb.evidenceCompleteness().missingEvidence()).contains("公告/定期报告反证", "行业估值对比");
        assertThat(cmb.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(cmb.todayAdvice().summary()).contains("点时财报");
        assertThat(cmb.trace()).extracting(MarketScanTraceStep::step).contains("QUOTE", "VALUATION", "RISK");
    }

    @Test
    void shouldAvoidChasingWhenSingleDayRiseIsTooHigh() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600000", "浦发银行", "银行", "10.00", "5.20", "7.00", "0.60", "600000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;

        MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate candidate = find(report, "600000");
        assertThat(candidate.screeningAction()).isEqualTo("WAIT_PULLBACK");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.risks()).anySatisfy(risk -> assertThat(risk).contains("单日涨幅偏大"));
    }

    @Test
    void shouldBlockAddWhenRealFinancialHistoryIsMissingDespitePeerAndAgentEvidence() {
        StubRecommendationEvidenceEnrichmentService enrichmentService = new StubRecommendationEvidenceEnrichmentService();
        MarketScanService evidenceAwareService = new MarketScanService(
                universalScreener,
                new EvidenceCompletenessService(),
                enrichmentService
        );
        eastMoneyClient.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "36.83", "-0.50", "6.15", "0.83", "900000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;
        enrichmentService.bundle = completeBundle("600036");

        MarketScanReport report = evidenceAwareService.report(3, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate candidate = find(report, "600036");
        assertThat(candidate.screeningAction()).isEqualTo("VALUE_RESEARCH");
        assertThat(candidate.evidenceBundle().peerValuation().available()).isTrue();
        assertThat(candidate.evidenceBundle().agentConsensus().available()).isTrue();
        assertThat(candidate.evidenceCompleteness().allowsBuy()).isFalse();
        assertThat(candidate.evidenceCompleteness().missingEvidence()).contains("近三年财报质量");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().summary()).contains("点时财报");
        assertThat(candidate.dataGaps()).anyMatch(gap -> gap.contains("点时财报"));
    }

    @Test
    void shouldKeepMissingValuationInResearchWithBuyGateClosed() {
        eastMoneyClient.baseQuotes = List.of(
                quote("300750", "宁德时代", "电池", "260.00", "-0.40", null, null, "800000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;

        MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate candidate = find(report, "300750");
        assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.MISSING);
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.evidenceCompleteness().allowsBuy()).isFalse();
        assertThat(report.exclusionsSample()).extracting(UniversalScreenExclusion::symbol).doesNotContain("300750");
    }

    @Test
    void shouldUseEastMoneyRealtimePoolWhenTencentReviewIsEmpty() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600000", "浦发银行", "银行", "10.00", "0.20", "7.00", "0.60", "600000000")
        );
        eastMoneyClient.tencentQuotes = List.of();

        MarketScanReport report = service.report(3, 50, null, null, null);

        MarketScanCandidate candidate = find(report, "600000");
        assertThat(candidate.latestPrice()).isEqualByComparingTo("10.00");
        assertThat(report.quoteNote()).contains("实时行情");
    }

    @Test
    void allModeNeverTurnsEligibilityRankingIntoBuyAdvice() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "36.83", "-0.50", "6.15", "0.83", "900000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;

        MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "ALL");

        MarketScanCandidate candidate = find(report, "600036");
        assertThat(candidate.screeningAction()).isEqualTo("ELIGIBLE");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.todayAdvice().summary()).contains("全市场资格");
    }

    private MarketScanCandidate find(MarketScanReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private EastMoneyQuote quote(
            String symbol,
            String name,
            String industry,
            String latestPrice,
            String changePercent,
            String peTtm,
            String pbRatio,
            String amount
    ) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                industry,
                decimal(latestPrice),
                decimal(changePercent),
                BigDecimal.ONE,
                BigDecimal.ONE,
                decimal(amount),
                decimal(peTtm),
                decimal(pbRatio),
                decimal(peTtm),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private RecommendationEvidenceBundle completeBundle(String symbol) {
        return new RecommendationEvidenceBundle(
                symbol,
                new PeerValuationBrief(
                        true,
                        "同行业可比",
                        5,
                        new BigDecimal("6.15"),
                        new BigDecimal("0.83"),
                        new BigDecimal("7.20"),
                        new BigDecimal("0.92"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.40"),
                        List.of(new PeerValuationBriefPeer(
                                "601398",
                                "工商银行",
                                "同行业",
                                new BigDecimal("6.30"),
                                new BigDecimal("0.58"),
                                new BigDecimal("6.10")
                        )),
                        List.of("已形成同行业估值样本。"),
                        List.of()
                ),
                new AgentConsensusBrief(
                        true,
                        "可观察",
                        new BigDecimal("78"),
                        3,
                        2,
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

        private List<EastMoneyQuote> baseQuotes = List.of();
        private List<EastMoneyQuote> tencentQuotes = List.of();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            return baseQuotes.stream().limit(limit).toList();
        }

        @Override
        public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
            List<EastMoneyQuote> quotes = baseQuotes.stream().limit(limit).toList();
            return new AshareQuoteSnapshot(
                    quotes,
                    limit,
                    quotes.size(),
                    quotes.size(),
                    0,
                    true,
                    "测试行情",
                    Instant.parse("2026-07-10T07:00:00Z")
            );
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            return tencentQuotes.stream()
                    .filter(quote -> symbols.contains(quote.symbol()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return List.of();
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
