package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyAnnualIndicator;
import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyIntradayPoint;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.quality.EvidenceCompletenessService;
import com.aistock.research.trading.QuoteFreshnessService;
import com.aistock.research.trading.TradingClockService;
import com.aistock.research.valuation.ValuationContextState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortTermServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-07T06:59:00Z"), SHANGHAI);
    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final TradingClockService tradingClockService = new TradingClockService(TEST_CLOCK);
    private final ShortTermService service = new ShortTermService(
            eastMoneyClient,
            new EvidenceCompletenessService(),
            tradingClockService,
            new QuoteFreshnessService(tradingClockService, TEST_CLOCK)
    );

    @Test
    void shouldDefaultToFullMarketQuotePoolWithoutStaticStockWhitelist() {
        ShortTermReport report = service.report(8, null, null, null, null, null, null, null, null, null);

        assertThat(eastMoneyClient.requestedQuoteLimit).isEqualTo(6000);
        assertThat(report.ruleSet().scanLimit()).isEqualTo(6000);
        assertThat(report.ruleSet().klineLimit()).isEqualTo(60);
    }

    @Test
    void shouldDiscoverPreviouslyUnknownHotIndustryFromCurrentMarketQuotes() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600101", "量子甲", "量子通信", "10.62", "3.20", "80", "8", "600000000"),
                quoteWithIndustry("600102", "量子乙", "量子通信", "10.62", "2.80", "90", "9", "500000000"),
                quoteWithIndustry("600103", "量子丙", "量子通信", "10.62", "2.40", "100", "10", "400000000"),
                quoteWithIndustry("600104", "冷门样本", "纸制品", "10.62", "0.10", "15", "1", "200000000")
        );
        for (EastMoneyQuote quote : eastMoneyClient.quotes) {
            eastMoneyClient.klines.put(quote.symbol(), rightEarlyKLines(quote.symbol(), "10.62", "180000"));
            eastMoneyClient.financials.put(quote.symbol(), goodFinancial(quote.symbol()));
        }

        ShortTermReport report = service.report(4, 50, 10, null, null, null, null, null, null, null);

        assertThat(report.hotDirections()).extracting(ShortTermHotDirection::label).contains("量子通信");
        assertThat(report.hotDirections()).filteredOn(direction -> "量子通信".equals(direction.label()))
                .singleElement()
                .satisfies(direction -> assertThat(direction.heatScore()).isLessThan(new BigDecimal("100")));
        ShortTermCandidate hotCandidate = find(report, "600101");
        ShortTermCandidate coldCandidate = find(report, "600104");
        assertThat(hotCandidate.score().marketHeatScore()).isGreaterThan(coldCandidate.score().marketHeatScore());
    }

    @Test
    void shouldNotTurnIcePointMarketIntoBuyAfterTailConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600201", "右侧候选", "10.62", "1.60", "18", "1.6", "600000000"),
                quote("600202", "下跌甲", "10.00", "-2.10", "18", "1.6", "300000000"),
                quote("600203", "下跌乙", "10.00", "-1.80", "18", "1.6", "300000000"),
                quote("600204", "下跌丙", "10.00", "-1.50", "18", "1.6", "300000000"),
                quote("600205", "下跌丁", "10.00", "-1.20", "18", "1.6", "300000000")
        );
        eastMoneyClient.klines.put("600201", rightEarlyKLines("600201", "10.62", "180000"));
        eastMoneyClient.financials.put("600201", goodFinancial("600201"));
        eastMoneyClient.intraday.put("600201", confirmedTail("600201"));

        ShortTermReport report = service.report(5, 50, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600201");
        assertThat(report.marketSentiment().phase()).isEqualTo("冰点/混沌");
        assertThat(candidate.action()).isEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldDowngradeCrowdedMarketToLightTrialInsteadOfHardBlockingEveryCandidate() {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600301", "右侧候选", "10.62", "1.60", "18", "1.6", "600000000"));
        for (int index = 0; index < 80; index++) {
            quotes.add(quote(String.format("60%04d", 400 + index), "涨停样本" + index,
                    "10.00", "9.60", "30", "3", "300000000"));
        }
        eastMoneyClient.quotes = quotes;
        eastMoneyClient.klines.put("600301", rightEarlyKLines("600301", "10.62", "180000"));
        eastMoneyClient.financials.put("600301", goodFinancial("600301"));
        eastMoneyClient.intraday.put("600301", confirmedTail("600301"));

        ShortTermReport report = service.report(5, 100, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600301");
        assertThat(report.marketSentiment().phase()).isEqualTo("高潮");
        assertThat(candidate.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(candidate.todayAdvice().action()).isEqualTo("LIGHT_TRIAL");
    }

    @Test
    void shouldLabelClosedMarketQuoteAsSnapshotInsteadOfRealtimeEvidence() {
        Clock closedClock = Clock.fixed(Instant.parse("2026-07-11T03:00:00Z"), SHANGHAI);
        TradingClockService closedTradingClock = new TradingClockService(closedClock);
        ShortTermService closedService = new ShortTermService(
                eastMoneyClient,
                new EvidenceCompletenessService(),
                closedTradingClock,
                new QuoteFreshnessService(closedTradingClock, closedClock)
        );
        eastMoneyClient.quotes = List.of(
                quote("600302", "休市样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600302", rightEarlyKLines("600302", "10.62", "180000"));
        eastMoneyClient.financials.put("600302", goodFinancial("600302"));

        ShortTermCandidate candidate = find(
                closedService.report(3, 50, 10, null, null, null, null, null, null, null),
                "600302"
        );

        assertThat(candidate.quoteFreshness().status()).isEqualTo("MARKET_CLOSED_SNAPSHOT");
        assertThat(candidate.evidenceCompleteness().presentEvidence()).doesNotContain("实时行情");
        assertThat(candidate.evidenceCompleteness().missingEvidence()).contains("实时行情");
    }

    @Test
    void shouldRejectPreviousTradingDayIntradayAsTodayTailConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600303", "跨日样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600303", rightEarlyKLines("600303", "10.62", "180000"));
        eastMoneyClient.financials.put("600303", goodFinancial("600303"));
        eastMoneyClient.intraday.put("600303", confirmedTail("600303", LocalDate.parse("2026-07-06")));

        ShortTermCandidate candidate = find(
                service.report(3, 50, 10, null, null, null, null, null, null, null),
                "600303"
        );

        assertThat(candidate.tailSignal().status()).isEqualTo("STALE_TRADING_DAY");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldBlockExecutionAndDiscloseWhenMarketCoverageIsIncomplete() {
        eastMoneyClient.snapshotComplete = false;
        eastMoneyClient.snapshotExpectedCount = 100;
        eastMoneyClient.quotes = List.of(
                quote("600304", "覆盖不足样本", "10.62", "1.60", "18", "1.6", "600000000")
        );
        eastMoneyClient.klines.put("600304", rightEarlyKLines("600304", "10.62", "180000"));
        eastMoneyClient.financials.put("600304", goodFinancial("600304"));
        eastMoneyClient.intraday.put("600304", confirmedTail("600304"));

        ShortTermReport report = service.report(3, 100, 10, null, null, null, null, null, null, null);
        ShortTermCandidate candidate = find(report, "600304");

        assertThat(report.quoteNote()).contains("覆盖不足", "1/100");
        assertThat(report.marketSentiment().phase()).isEqualTo("行情覆盖不足");
        assertThat(candidate.action()).isEqualTo("MARKET_RISK_WAIT");
        assertThat(candidate.todayAdvice().action()).isNotIn("ADD", "LIGHT_TRIAL");
    }

    @Test
    void shouldFindRightSideEarlyStockWithFinancialSupport() {
        eastMoneyClient.unstableIndustrySymbols = Set.of("300059");
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000"),
                quote("600002", "急拉股份", "12.90", "6.60", "18.00", "1.80", "260000000"),
                quote("600004", "低流动性", "10.62", "1.20", "16.00", "1.40", "50000000"),
                quoteWithIndustry("600003", "样本证券", "证券", "11.30", "1.20", "14.00", "1.30", "200000000"),
                quote("300059", "东方财富", "18.20", "1.10", "26.00", "3.20", "300000000"),
                quote("000002", "*ST样本", "2.10", "1.00", "8.00", "0.90", "100000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "12.90", "420000"));
        eastMoneyClient.klines.put("600004", rightEarlyKLines("600004", "10.62", "160000"));
        eastMoneyClient.klines.put("600003", rightEarlyKLines("600003", "11.30", "210000"));
        eastMoneyClient.klines.put("300059", rightEarlyKLines("300059", "18.20", "300000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));
        eastMoneyClient.financials.put("600004", goodFinancial("600004"));
        eastMoneyClient.financials.put("600003", goodFinancial("600003"));
        eastMoneyClient.financials.put("300059", goodFinancial("300059"));
        eastMoneyClient.intraday.put("600001", confirmedTail("600001"));

        ShortTermReport report = service.report(5, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(report.scope()).contains("短线右侧");
        assertThat(report.ruleSet().scanLimit()).isEqualTo(100);
        assertThat(candidate.phaseLabel()).isEqualTo("右侧早期");
        assertThat(candidate.action()).isEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.todayAdvice().action()).isEqualTo("ADD");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("加仓");
        assertThat(candidate.tailSignal().status()).isEqualTo("CONFIRMED");
        assertThat(candidate.technical().rightSideSignal()).contains("右侧早期");
        assertThat(candidate.financial().qualityScore()).isGreaterThanOrEqualTo(new BigDecimal("58"));
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("000002");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600004");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600003");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("300059");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).contains("600004", "600003", "300059");
        assertThat(report.exclusions()).filteredOn(exclusion -> "600004".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("LOW_LIQUIDITY");
                    assertThat(exclusion.reason()).contains("流动性");
                });
        assertThat(report.exclusions()).filteredOn(exclusion -> "300059".equals(exclusion.symbol()))
                .singleElement()
                .satisfies(exclusion -> {
                    assertThat(exclusion.category()).isEqualTo("UNSTABLE_INDUSTRY");
                    assertThat(exclusion.reason()).contains("证券/券商");
                });
    }

    @Test
    void shouldAvoidChasingAfterSharpRightSideMove() {
        eastMoneyClient.quotes = List.of(
                quote("600002", "急拉股份", "12.90", "6.60", "18.00", "1.80", "260000000")
        );
        eastMoneyClient.klines.put("600002", rightEarlyKLines("600002", "12.90", "420000"));
        eastMoneyClient.financials.put("600002", goodFinancial("600002"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600002");
        assertThat(candidate.action()).isEqualTo("WAIT_PULLBACK");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT_PULLBACK");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("等回踩");
        assertThat(candidate.risks()).anySatisfy(risk -> assertThat(risk).contains("单日涨幅"));
    }

    @Test
    void shouldWaitUntilClosingAuctionForRegularTailConfirmation() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", tailBeforeClosingAuction("600001"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(candidate.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(candidate.tailSignal().status()).isEqualTo("NOT_READY");
        assertThat(candidate.tailSignal().statusLabel()).isEqualTo("等14:57");
        assertThat(candidate.tailSignal().reasons()).anySatisfy(reason -> assertThat(reason).contains("14:57"));
    }

    @Test
    void shouldTreatPostCloseFixedPriceAsSeparateFromRegularTailBuyPoint() {
        eastMoneyClient.quotes = List.of(
                quote("600001", "右侧股份", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600001", rightEarlyKLines("600001", "10.62", "180000"));
        eastMoneyClient.financials.put("600001", goodFinancial("600001"));
        eastMoneyClient.intraday.put("600001", postCloseOnly("600001"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600001");
        assertThat(candidate.todayAdvice().action()).isEqualTo("NEXT_WATCH");
        assertThat(candidate.tailSignal().status()).isEqualTo("POST_CLOSE_FIXED_PRICE");
        assertThat(candidate.tailSignal().statusLabel()).isEqualTo("盘后固定价");
        assertThat(candidate.tailSignal().riskControls()).anySatisfy(control -> assertThat(control).contains("不能和普通尾盘买点混用"));
        assertThat(candidate.evidenceCompleteness().allowsBuy()).isFalse();
    }

    @Test
    void shouldHideLongSidewaysStockWithoutEffectiveBreakout() {
        eastMoneyClient.quotes = List.of(
                quote("600005", "横盘股份", "10.08", "0.20", "12.00", "1.20", "180000000")
        );
        eastMoneyClient.klines.put("600005", longSidewaysKLines("600005"));
        eastMoneyClient.financials.put("600005", goodFinancial("600005"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).doesNotContain("600005");
    }

    @Test
    void shouldKeepHotDirectionCandidateWhenValuationIsStretchedButNotExtreme() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry("600010", "风口机器人", "机器人", "10.62", "1.60", "95.00", "10.00", "600000000"),
                quote("600011", "低估设备", "10.62", "1.20", "16.00", "1.40", "120000000")
        );
        eastMoneyClient.klines.put("600010", rightEarlyKLines("600010", "10.62", "230000"));
        eastMoneyClient.klines.put("600011", rightEarlyKLines("600011", "10.62", "160000"));
        eastMoneyClient.financials.put("600010", goodFinancial("600010"));
        eastMoneyClient.financials.put("600011", goodFinancial("600011"));

        ShortTermReport report = service.report(5, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600010");
        assertThat(report.ruleSet().maxPe()).isEqualByComparingTo("100");
        assertThat(report.ruleSet().maxPb()).isEqualByComparingTo("15.0");
        assertThat(report.candidates()).extracting(ShortTermCandidate::symbol).contains("600010");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600010");
        assertThat(candidate.score().marketHeatScore()).isGreaterThan(new BigDecimal("60"));
        assertThat(candidate.score().valuationScore()).isGreaterThan(new BigDecimal("65"));
    }

    @Test
    void shouldKeepHotRightSideCandidateEvenWhenPeAndPbExceedOldExtremeGate() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry(
                        "600020",
                        "高估值机器人",
                        "机器人",
                        "10.62",
                        "1.60",
                        "300.00",
                        "45.00",
                        "900000000"
                )
        );
        eastMoneyClient.klines.put("600020", rightEarlyKLines("600020", "10.62", "230000"));
        eastMoneyClient.financials.put("600020", goodFinancial("600020"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600020");
        assertThat(report.exclusions()).extracting(ShortTermRiskExclusion::symbol).doesNotContain("600020");
        assertThat(candidate.valuationContext().state()).isEqualTo(ValuationContextState.STRETCHED);
        assertThat(candidate.risks()).anySatisfy(item -> assertThat(item).contains("参考"));
    }

    @Test
    void shouldExposeApprovedSoftValuationWeights() {
        eastMoneyClient.quotes = List.of(
                quote("600021", "权重样本", "10.62", "1.60", "18.00", "1.60", "900000000")
        );
        eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.62", "230000"));
        eastMoneyClient.financials.put("600021", goodFinancial("600021"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        assertThat(report.weightProfile().preliminaryTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().finalTotal()).isEqualByComparingTo("1.00");
        assertThat(report.weightProfile().preliminaryValuation()).isEqualByComparingTo("0.10");
        assertThat(report.weightProfile().finalValuation()).isEqualByComparingTo("0.05");
    }

    @Test
    void shouldNotGiveMuyuanSymbolSpecificHotDirectionBonus() {
        eastMoneyClient.quotes = List.of(
                quoteWithIndustry(
                        "002714",
                        "中性样本",
                        "未知行业",
                        "10.62",
                        "1.60",
                        "18.00",
                        "1.60",
                        "900000000"
                )
        );
        eastMoneyClient.klines.put("002714", rightEarlyKLines("002714", "10.62", "230000"));
        eastMoneyClient.financials.put("002714", goodFinancial("002714"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "002714");
        assertThat(candidate.score().marketHeatScore()).isEqualByComparingTo("60");
        assertThat(report.hotDirections()).isEmpty();
    }

    @Test
    void shouldGiveLightTrialWhenRightSideObservationHasWatchedTailSupport() {
        eastMoneyClient.quotes = List.of(
                quote("600012", "观察试错", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600012", rightEarlyKLines("600012", "10.62", "108000"));
        eastMoneyClient.financials.put("600012", goodFinancial("600012"));
        eastMoneyClient.intraday.put("600012", watchedTail("600012"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600012");
        assertThat(candidate.action()).isEqualTo("WATCH_RIGHT_SIDE");
        assertThat(candidate.tailSignal().status()).isEqualTo("WATCH");
        assertThat(candidate.todayAdvice().action()).isEqualTo("LIGHT_TRIAL");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("轻仓试错");
        assertThat(candidate.todayAdvice().summary()).contains("轻仓");
    }

    @Test
    void shouldKeepStrongDailySignalAsNextDayWatchWhenTailTurnsWeak() {
        eastMoneyClient.quotes = List.of(
                quote("600013", "次日关注", "10.62", "1.60", "18.00", "1.60", "180000000")
        );
        eastMoneyClient.klines.put("600013", rightEarlyKLines("600013", "10.62", "180000"));
        eastMoneyClient.financials.put("600013", goodFinancial("600013"));
        eastMoneyClient.intraday.put("600013", weakTail("600013"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600013");
        assertThat(candidate.action()).isEqualTo("RIGHT_EARLY_ADD");
        assertThat(candidate.tailSignal().status()).isEqualTo("WEAK");
        assertThat(candidate.todayAdvice().action()).isEqualTo("NEXT_WATCH");
        assertThat(candidate.todayAdvice().actionLabel()).isEqualTo("次日关注");
        assertThat(candidate.todayAdvice().summary()).contains("次日");
    }

    @Test
    void shouldUseLowerTailAmountRatioThresholdForLargeTurnoverStocks() {
        eastMoneyClient.quotes = List.of(
                quote("600014", "大额成交", "10.62", "1.60", "18.00", "1.60", "3600000000")
        );
        eastMoneyClient.klines.put("600014", rightEarlyKLines("600014", "10.62", "180000"));
        eastMoneyClient.financials.put("600014", goodFinancial("600014"));
        eastMoneyClient.intraday.put("600014", largeTurnoverConfirmedTail("600014"));

        ShortTermReport report = service.report(3, 100, 5, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600014");
        assertThat(candidate.tailSignal().tailAmountRatioPercent()).isLessThan(new BigDecimal("6.00"));
        assertThat(candidate.tailSignal().status()).isEqualTo("CONFIRMED");
        assertThat(candidate.todayAdvice().action()).isEqualTo("ADD");
    }

    @Test
    void shouldFailInsteadOfReturningEmptyCandidatesWhenRealtimeQuotesUnavailable() {
        eastMoneyClient.quoteFailure = new IllegalStateException("实时行情超时");

        assertThatThrownBy(() -> service.report(3, 100, 5, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("短线右侧实时行情加载失败")
                .hasMessageContaining("不返回空候选降级");
    }

    @Test
    void shouldScoreDecimalFinancialRatiosUsingDeclaredUnits() {
        eastMoneyClient.quotes = List.of(
                quote("600021", "比例样本", "10.50", "1.20", "35", "3.2", "600000000")
        );
        eastMoneyClient.klines.put("600021", rightEarlyKLines("600021", "10.50", "180000"));
        eastMoneyClient.financials.put("600021", goodFinancial("600021"));

        ShortTermReport report = service.report(3, 50, 10, null, null, null, null, null, null, null);

        ShortTermCandidate candidate = find(report, "600021");
        assertThat(candidate.financial().roe()).isEqualByComparingTo("0.125");
        assertThat(candidate.financial().qualityScore()).isEqualByComparingTo("100");
        assertThat(candidate.evidence()).extracting(ShortTermEvidence::summary)
                .anySatisfy(summary -> assertThat(summary).contains("ROE 12.5%"));
    }

    private ShortTermCandidate find(ShortTermReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private List<EastMoneyKLine> rightEarlyKLines(String symbol, String finalClose, String finalVolume) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 35; index++) {
            BigDecimal close = new BigDecimal("12.20").subtract(new BigDecimal("0.035").multiply(BigDecimal.valueOf(index)));
            rows.add(kline(symbol, start.plusDays(index), close, "95000"));
        }
        for (int index = 35; index < 72; index++) {
            BigDecimal close = new BigDecimal("10.95").subtract(new BigDecimal("0.035").multiply(BigDecimal.valueOf(index - 35)));
            rows.add(kline(symbol, start.plusDays(index), close, "98000"));
        }
        for (int index = 72; index < 103; index++) {
            BigDecimal close = new BigDecimal("9.60").add(new BigDecimal("0.018").multiply(BigDecimal.valueOf(index - 72)));
            rows.add(kline(symbol, start.plusDays(index), close, "100000"));
        }
        for (int index = 103; index < 119; index++) {
            BigDecimal close = new BigDecimal("10.05").add(new BigDecimal("0.030").multiply(BigDecimal.valueOf(index - 103)));
            rows.add(kline(symbol, start.plusDays(index), close, "105000"));
        }
        rows.add(kline(symbol, start.plusDays(119), new BigDecimal(finalClose), finalVolume));
        return rows;
    }

    private List<EastMoneyKLine> longSidewaysKLines(String symbol) {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 100; index++) {
            BigDecimal close = new BigDecimal("10.00").add(new BigDecimal(index % 2 == 0 ? "0.08" : "-0.06"));
            rows.add(kline(symbol, start.plusDays(index), close, "110000"));
        }
        return rows;
    }

    private EastMoneyKLine kline(String symbol, LocalDate date, BigDecimal close, String volume) {
        return new EastMoneyKLine(
                symbol,
                date,
                close.subtract(new BigDecimal("0.05")),
                close,
                close.add(new BigDecimal("0.12")),
                close.subtract(new BigDecimal("0.14")),
                new BigDecimal(volume),
                null
        );
    }

    private EastMoneyQuote quote(String symbol, String name, String price, String changePercent, String pe, String pb, String amount) {
        return quoteWithIndustry(symbol, name, "通用设备", price, changePercent, pe, pb, amount);
    }

    private EastMoneyQuote quoteWithIndustry(String symbol, String name, String industry, String price, String changePercent, String pe, String pb, String amount) {
        return new EastMoneyQuote(
                symbol,
                name,
                "上交所",
                industry,
                new BigDecimal(price),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal(amount),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-07T06:59:00Z"),
                LocalDate.parse("2026-07-07"),
                Instant.parse("2026-07-07T06:58:00Z")
        );
    }

    private List<EastMoneyAnnualIndicator> goodFinancial(String symbol) {
        return List.of(
                new EastMoneyAnnualIndicator(symbol, "样本", "2025-12-31", "年报", new BigDecimal("0.1250"),
                        new BigDecimal("1.20"), new BigDecimal("0.2800"), new BigDecimal("0.0600"), new BigDecimal("0.1200"), BigDecimal.ONE, new BigDecimal("5.00")),
                new EastMoneyAnnualIndicator(symbol, "样本", "2024-12-31", "年报", new BigDecimal("0.1080"),
                        new BigDecimal("1.05"), new BigDecimal("0.2600"), new BigDecimal("0.0300"), new BigDecimal("0.0800"), BigDecimal.ONE, new BigDecimal("4.60")),
                new EastMoneyAnnualIndicator(symbol, "样本", "2023-12-31", "年报", new BigDecimal("0.0960"),
                        new BigDecimal("0.90"), new BigDecimal("0.2400"), new BigDecimal("0.0200"), new BigDecimal("0.0500"), BigDecimal.ONE, new BigDecimal("4.20"))
        );
    }

    private List<EastMoneyIntradayPoint> confirmedTail(String symbol) {
        return confirmedTail(symbol, LocalDate.parse("2026-07-07"));
    }

    private List<EastMoneyIntradayPoint> confirmedTail(String symbol, LocalDate tradeDate) {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            BigDecimal close = new BigDecimal("10.45").add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(index)));
            points.add(new EastMoneyIntradayPoint(
                    symbol,
                    tradeDate.atTime(14, 57).plusMinutes(index),
                    close.subtract(new BigDecimal("0.02")),
                    close,
                    close.add(new BigDecimal("0.03")),
                    close.subtract(new BigDecimal("0.04")),
                    new BigDecimal("30000").add(BigDecimal.valueOf(index).multiply(new BigDecimal("1000"))),
                    new BigDecimal("32000000").add(BigDecimal.valueOf(index).multiply(new BigDecimal("1500000"))),
                    new BigDecimal("10.45").add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.01")))
            ));
        }
        return points;
    }

    private List<EastMoneyIntradayPoint> watchedTail(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:57", "10.48", "10.50", "10.52", "10.47", "26000", "26000000", "10.48"),
                intraday(symbol, "2026-07-07T14:58", "10.50", "10.51", "10.53", "10.49", "24000", "25000000", "10.49"),
                intraday(symbol, "2026-07-07T14:59", "10.51", "10.51", "10.52", "10.50", "23000", "24000000", "10.49"),
                intraday(symbol, "2026-07-07T15:00", "10.51", "10.51", "10.52", "10.50", "22000", "23000000", "10.49")
        );
    }

    private List<EastMoneyIntradayPoint> weakTail(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:57", "10.55", "10.55", "10.57", "10.53", "28000", "29000000", "10.50"),
                intraday(symbol, "2026-07-07T14:58", "10.55", "10.49", "10.55", "10.48", "26000", "27000000", "10.50"),
                intraday(symbol, "2026-07-07T14:59", "10.49", "10.45", "10.50", "10.44", "25000", "26000000", "10.50"),
                intraday(symbol, "2026-07-07T15:00", "10.45", "10.42", "10.46", "10.41", "24000", "25000000", "10.50")
        );
    }

    private List<EastMoneyIntradayPoint> largeTurnoverConfirmedTail(String symbol) {
        List<EastMoneyIntradayPoint> points = new ArrayList<>();
        points.add(intraday(symbol, "2026-07-07T14:30", "10.32", "10.38", "10.40", "10.31", "2600000", "3000000000", "10.36"));
        points.add(intraday(symbol, "2026-07-07T14:57", "10.48", "10.50", "10.52", "10.47", "36000", "36000000", "10.48"));
        points.add(intraday(symbol, "2026-07-07T14:58", "10.50", "10.52", "10.53", "10.49", "36000", "37000000", "10.49"));
        points.add(intraday(symbol, "2026-07-07T14:59", "10.52", "10.54", "10.55", "10.51", "36000", "38000000", "10.50"));
        points.add(intraday(symbol, "2026-07-07T15:00", "10.54", "10.55", "10.56", "10.53", "36000", "39000000", "10.50"));
        return points;
    }

    private List<EastMoneyIntradayPoint> tailBeforeClosingAuction(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T14:51", "10.34", "10.38", "10.39", "10.33", "14000", "14000000", "10.32"),
                intraday(symbol, "2026-07-07T14:54", "10.38", "10.42", "10.43", "10.37", "18000", "19000000", "10.36"),
                intraday(symbol, "2026-07-07T14:56", "10.42", "10.48", "10.49", "10.41", "22000", "24000000", "10.39")
        );
    }

    private List<EastMoneyIntradayPoint> postCloseOnly(String symbol) {
        return List.of(
                intraday(symbol, "2026-07-07T15:05", "10.40", "10.45", "10.46", "10.39", "20000", "20000000", "10.42"),
                intraday(symbol, "2026-07-07T15:15", "10.44", "10.48", "10.49", "10.43", "18000", "19000000", "10.43"),
                intraday(symbol, "2026-07-07T15:19", "10.50", "10.56", "10.58", "10.49", "30000", "32000000", "10.45")
        );
    }

    private EastMoneyIntradayPoint intraday(
            String symbol,
            String minute,
            String open,
            String close,
            String high,
            String low,
            String volume,
            String amount,
            String averagePrice
    ) {
        return new EastMoneyIntradayPoint(
                symbol,
                LocalDateTime.parse(minute),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(volume),
                new BigDecimal(amount),
                new BigDecimal(averagePrice)
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> quotes = List.of();
        private RuntimeException quoteFailure;
        private int requestedQuoteLimit;
        private boolean snapshotComplete = true;
        private int snapshotExpectedCount;
        private Set<String> unstableIndustrySymbols = Set.of();
        private final Map<String, List<EastMoneyKLine>> klines = new HashMap<>();
        private final Map<String, List<EastMoneyAnnualIndicator>> financials = new HashMap<>();
        private final Map<String, List<EastMoneyIntradayPoint>> intraday = new HashMap<>();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            requestedQuoteLimit = limit;
            if (quoteFailure != null) {
                throw quoteFailure;
            }
            return quotes.stream().limit(limit).toList();
        }

        @Override
        public AshareQuoteSnapshot fetchAshareQuoteSnapshot(int limit) {
            requestedQuoteLimit = limit;
            if (quoteFailure != null) {
                throw quoteFailure;
            }
            int fetched = Math.min(quotes.size(), limit);
            int expected = snapshotExpectedCount > 0 ? snapshotExpectedCount : fetched;
            return new AshareQuoteSnapshot(
                    quotes.stream().limit(limit).toList(),
                    limit,
                    expected,
                    fetched,
                    Math.max(0, expected - fetched),
                    snapshotComplete,
                    "测试行情",
                    Instant.parse("2026-07-07T06:59:00Z")
            );
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return klines.getOrDefault(symbol, List.of());
        }

        @Override
        public List<EastMoneyQuote> fetchIndustryBoardConstituents(String industryName, int limit) {
            return quotes.stream()
                    .filter(quote -> unstableIndustrySymbols.contains(quote.symbol()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EastMoneyAnnualIndicator> fetchAnnualIndicatorHistory(String symbol, int limit) {
            return financials.getOrDefault(symbol, List.of()).stream().limit(limit).toList();
        }

        @Override
        public List<EastMoneyIntradayPoint> fetchIntradayTrends(String symbol) {
            return intraday.getOrDefault(symbol, List.of());
        }
    }
}
