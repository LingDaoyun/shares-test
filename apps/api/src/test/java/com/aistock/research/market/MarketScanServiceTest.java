package com.aistock.research.market;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        assertThat(cmb.marketTimestamp()).isEqualTo(Instant.parse("2026-07-01T07:30:00Z"));
        assertThat(cmb.tags()).contains("低估值", "红利防守");
        assertThat(cmb.evidenceCompleteness().status()).isEqualTo("INSUFFICIENT");
        assertThat(cmb.evidenceCompleteness().missingEvidence())
                .contains("近一年K线", "公告/定期报告反证", "行业估值对比");
        assertThat(cmb.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(cmb.todayAdvice().summary()).contains("点时财报");
        assertThat(cmb.trace()).extracting(MarketScanTraceStep::step).contains("QUOTE", "VALUATION", "RISK");
    }

    @Test
    void valueModeDefaultsToTwelveLongTermRecommendations() {
        eastMoneyClient.baseQuotes = List.of(
                quote("601166", "兴业银行", "银行", "17.45", "-0.11", "3.87", "0.45", "1800000000"),
                quote("600036", "招商银行", "银行", "37.18", "-0.19", "6.19", "0.85", "2900000000"),
                quote("601398", "工商银行", "银行", "7.50", "-0.40", "7.69", "0.69", "3500000000"),
                quote("000651", "格力电器", "白色家电", "40.20", "0.20", "8.10", "1.75", "1500000000"),
                quote("601318", "中国平安", "保险", "48.60", "0.10", "8.80", "0.92", "2200000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;

        MarketScanReport report = service.report(null, 100, null, null, null, null, true, true, "VALUE");

        assertThat(report.candidates()).hasSize(5);
        assertThat(report.methodology()).anySatisfy(item ->
                assertThat(item).contains("长线价投", "十二只", "低估", "基本面"));
    }

    @Test
    void shouldPrioritizeLongTermLeaderAssetsOverHighTurnoverMediocreNames() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600987", "航民股份", "纺织制造", "7.20", "0.10", "11.80", "1.02", "90000000"),
                quote("600001", "高换手同业", "纺织制造", "18.00", "0.20", "12.00", "1.05", "2200000000"),
                quote("600002", "普通同业", "纺织制造", "8.80", "-0.20", "13.00", "1.12", "650000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;
        eastMoneyClient.annualIndicators.put("600987", annualIndicator(
                "600987",
                "航民股份",
                "0.1180",
                "0.7200",
                "0.2350",
                "0.0450",
                "0.0380",
                "0.62",
                "13700000000",
                "720000000",
                "10派3元",
                "0.0410"
        ));
        for (int year = 2021; year <= 2025; year++) {
            eastMoneyClient.annualIndicatorsByYear
                    .computeIfAbsent(year, ignored -> new HashMap<>())
                    .put("600987", annualIndicator(
                            "600987",
                            "航民股份",
                            year + "-12-31",
                            year + "年 年报",
                            "0.1180",
                            "0.7200",
                            "0.2350",
                            "0.0450",
                            "0.0380",
                            "0.62",
                            "13700000000",
                            "720000000",
                            "10派3元",
                            "0.0410"
                    ));
        }
        eastMoneyClient.annualIndicators.put("600001", annualIndicator(
                "600001",
                "高换手同业",
                "0.0820",
                "0.1200",
                "0.1700",
                "0.0200",
                "0.0100",
                "0.31",
                "4100000000",
                "180000000",
                null,
                null
        ));
        eastMoneyClient.annualIndicatorsByYear
                .computeIfAbsent(2025, ignored -> new HashMap<>())
                .put("600001", annualIndicator(
                        "600001",
                        "高换手同业",
                        "2025-12-31",
                        "2025年 年报",
                        "0.0820",
                        "0.1200",
                        "0.1700",
                        "0.0200",
                        "0.0100",
                        "0.31",
                        "4100000000",
                        "180000000",
                        null,
                        null
                ));
        eastMoneyClient.annualIndicators.put("600002", annualIndicator(
                "600002",
                "普通同业",
                "0.0750",
                "0.0800",
                "0.1550",
                "0.0100",
                "0.0050",
                "0.28",
                "3600000000",
                "120000000",
                null,
                null
        ));
        eastMoneyClient.annualIndicatorsByYear
                .computeIfAbsent(2025, ignored -> new HashMap<>())
                .put("600002", annualIndicator(
                        "600002",
                        "普通同业",
                        "2025-12-31",
                        "2025年 年报",
                        "0.0750",
                        "0.0800",
                        "0.1550",
                        "0.0100",
                        "0.0050",
                        "0.28",
                        "3600000000",
                        "120000000",
                        null,
                        null
                ));

        MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate hangmin = report.candidates().get(0);
        assertThat(hangmin.symbol()).isEqualTo("600987");
        assertThat(hangmin.longTermAssessment().modelCode()).isEqualTo("STANDARD");
        assertThat(hangmin.longTermAssessment().status()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(hangmin.screeningAction()).isEqualTo("VALUE_RESEARCH");
        assertThat(hangmin.longTermAssessment().financialQuality().sampleYears()).isEqualTo(5);
        assertThat(hangmin.longTermAssessment().valuation().metricCode()).isEqualTo("IMPLIED_GROWTH");
        assertThat(hangmin.longTermAssessment().positionDiscipline().reviewTriggers())
                .anyMatch(item -> item.contains("下跌15%") && item.contains("不自动加仓"));
        assertThat(hangmin.score().qualityProxyScore())
                .isEqualByComparingTo(hangmin.longTermAssessment().factorScores().financialQualityScore())
                .isGreaterThan(new BigDecimal("75"));
        assertThat(hangmin.score().liquidityScore()).isLessThan(new BigDecimal("90"));
        assertThat(hangmin.strengths()).anySatisfy(strength ->
                assertThat(strength).contains("行业地位", "盈利质量"));
        assertThat(hangmin.trace()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("QUALITY");
            assertThat(step.summary()).contains("行业地位", "盈利");
            assertThat(step.findings()).anySatisfy(evidence ->
                    assertThat(evidence).contains("同业营收排名 1/3", "营收", "归母净利"));
            assertThat(step.findings()).anySatisfy(evidence ->
                    assertThat(evidence).contains("近 5 年连续分红 5 年", "平均股息率"));
            assertThat(step.findings()).noneMatch(evidence -> evidence.contains("成交额排名"));
        });
        assertThat(hangmin.strengths()).anySatisfy(strength ->
                assertThat(strength).contains("分红", "10派3元"));
        assertThat(hangmin.dataGaps()).noneMatch(gap -> gap.contains("分红连续性"));
        assertThat(report.methodology()).anySatisfy(item ->
                assertThat(item).contains("行业地位", "盈利质量", "换手"));
    }

    @Test
    void shouldExposeDurableProfitabilityEvidenceForLongTermValueMode() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600931", "稳定龙头", "工业制造", "12.00", "0.10", "14.00", "1.10", "220000000"),
                quote("600932", "单年爆发", "工业制造", "12.00", "0.10", "14.00", "1.10", "1200000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;
        for (int year = 2021; year <= 2025; year++) {
            eastMoneyClient.annualIndicatorsByYear
                    .computeIfAbsent(year, ignored -> new HashMap<>())
                    .put("600931", annualIndicator(
                            "600931",
                            "稳定龙头",
                            year + "-12-31",
                            year + "年 年报",
                            "0.1200",
                            "0.85",
                            "0.2600",
                            "0.0450",
                            "0.0500",
                            "0.72",
                            "9800000000",
                            "860000000",
                            "10派2.5元",
                            "0.0320"
                    ));
        }
        eastMoneyClient.annualIndicatorsByYear
                .computeIfAbsent(2025, ignored -> new HashMap<>())
                .put("600932", annualIndicator(
                        "600932",
                        "单年爆发",
                        "2025-12-31",
                        "2025年 年报",
                        "0.2200",
                        "0.10",
                        "0.2500",
                        "0.1800",
                        "0.4500",
                        "1.20",
                        "5200000000",
                        "620000000",
                        null,
                        null
                ));
        eastMoneyClient.annualIndicatorsByYear
                .computeIfAbsent(2024, ignored -> new HashMap<>())
                .put("600932", annualIndicator(
                        "600932",
                        "单年爆发",
                        "2024-12-31",
                        "2024年 年报",
                        "0.0350",
                        "-0.20",
                        "0.1700",
                        "-0.0800",
                        "-0.3000",
                        "0.18",
                        "3600000000",
                        "90000000",
                        null,
                        null
                ));
        eastMoneyClient.annualIndicatorsByYear
                .computeIfAbsent(2023, ignored -> new HashMap<>())
                .put("600932", annualIndicator(
                        "600932",
                        "单年爆发",
                        "2023-12-31",
                        "2023年 年报",
                        "0.0280",
                        "-0.10",
                        "0.1600",
                        "-0.0600",
                        "-0.2200",
                        "0.15",
                        "3400000000",
                        "76000000",
                        null,
                        null
                ));

        MarketScanReport report = service.report(2, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate durable = find(report, "600931");
        MarketScanCandidate oneYear = find(report, "600932");
        assertThat(durable.score().qualityProxyScore()).isGreaterThan(oneYear.score().qualityProxyScore());
        assertThat(durable.strengths()).anySatisfy(strength ->
                assertThat(strength).contains("多年 ROE"));
        assertThat(durable.strengths()).anySatisfy(strength ->
                assertThat(strength).contains("经营现金流", "连续"));
        assertThat(oneYear.dataGaps()).anySatisfy(gap ->
                assertThat(gap).contains("盈利持续性"));
    }

    @Test
    void shouldUsePbRoeExpectationForFinancialIndustry() {
        eastMoneyClient.baseQuotes = List.of(
                quote("600036", "招商银行", "银行", "36.83", "-0.20", "6.15", "0.83", "900000000")
        );
        eastMoneyClient.tencentQuotes = eastMoneyClient.baseQuotes;
        for (int year = 2021; year <= 2025; year++) {
            eastMoneyClient.annualIndicatorsByYear
                    .computeIfAbsent(year, ignored -> new HashMap<>())
                    .put("600036", annualIndicator(
                            "600036",
                            "招商银行",
                            year + "-12-31",
                            year + "年 年报",
                            "0.1600",
                            "4.90",
                            null,
                            "0.0500",
                            "0.0600",
                            "5.60",
                            "340000000000",
                            "138000000000",
                            "10派20元",
                            "0.0450"
                    ));
        }

        MarketScanReport report = service.report(3, 50, null, null, null, null, true, true, "VALUE");

        MarketScanCandidate candidate = find(report, "600036");
        assertThat(candidate.longTermAssessment().modelCode()).isEqualTo("FINANCIAL");
        assertThat(candidate.longTermAssessment().valuation().metricCode()).isEqualTo("IMPLIED_ROE");
        assertThat(candidate.longTermAssessment().valuation().metricLabel()).contains("ROE");
        assertThat(candidate.longTermAssessment().dataGaps())
                .anyMatch(gap -> gap.contains("不良率") && gap.contains("拨备"));
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
                Instant.parse("2026-07-01T07:30:01Z"),
                LocalDate.parse("2026-07-01"),
                Instant.parse("2026-07-01T07:30:00Z")
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

    private EastMoneyAnnualIndicator annualIndicator(
            String symbol,
            String name,
            String roe,
            String cash,
            String grossMargin,
            String revenueGrowth,
            String netProfitGrowth,
            String eps
    ) {
        return annualIndicator(symbol, name, roe, cash, grossMargin, revenueGrowth, netProfitGrowth, eps, null, null, null, null);
    }

    private EastMoneyAnnualIndicator annualIndicator(
            String symbol,
            String name,
            String roe,
            String cash,
            String grossMargin,
            String revenueGrowth,
            String netProfitGrowth,
            String eps,
            String operatingRevenue,
            String netProfit,
            String dividendPlanDescription,
            String dividendYield
    ) {
        return annualIndicator(
                symbol,
                name,
                "2025-12-31",
                "2025年 年报",
                roe,
                cash,
                grossMargin,
                revenueGrowth,
                netProfitGrowth,
                eps,
                operatingRevenue,
                netProfit,
                dividendPlanDescription,
                dividendYield
        );
    }

    private EastMoneyAnnualIndicator annualIndicator(
            String symbol,
            String name,
            String reportDate,
            String dataType,
            String roe,
            String cash,
            String grossMargin,
            String revenueGrowth,
            String netProfitGrowth,
            String eps,
            String operatingRevenue,
            String netProfit,
            String dividendPlanDescription,
            String dividendYield
    ) {
        return new EastMoneyAnnualIndicator(
                symbol,
                name,
                reportDate,
                dataType,
                decimal(roe),
                decimal(cash),
                decimal(grossMargin),
                decimal(revenueGrowth),
                decimal(netProfitGrowth),
                decimal(eps),
                new BigDecimal("7.05"),
                decimal(operatingRevenue),
                decimal(netProfit),
                dividendPlanDescription,
                decimal(dividendYield)
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> baseQuotes = List.of();
        private List<EastMoneyQuote> tencentQuotes = List.of();
        private final Map<String, EastMoneyAnnualIndicator> annualIndicators = new HashMap<>();
        private final Map<Integer, Map<String, EastMoneyAnnualIndicator>> annualIndicatorsByYear = new HashMap<>();

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

        @Override
        public Map<String, EastMoneyAnnualIndicator> fetchAnnualIndicators(int dataYear, int pageSize) {
            if (!annualIndicatorsByYear.isEmpty()) {
                return annualIndicatorsByYear.getOrDefault(dataYear, Map.of());
            }
            return annualIndicators;
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
