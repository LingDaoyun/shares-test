package com.aistock.research.cycle;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyFundFlowSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CycleTrialServiceTest {

    private final StubEastMoneyClient eastMoneyClient = new StubEastMoneyClient();
    private final CycleTrialService service = new CycleTrialService(eastMoneyClient);

    @Test
    void shouldRequireVerifiedCycleEvidenceBeforeLeftTrial() {
        eastMoneyClient.quotes = List.of(quote("002772", "众兴菌业", "11.92", "0.10", "10.20", "1.25"));
        eastMoneyClient.klines.put("002772", leftTrialKLines());

        CycleTrialReport report = service.report(10, null, null, null, null);

        CycleTrialCandidate zhongxing = find(report, "002772");
        assertThat(zhongxing.action()).isEqualTo("EVIDENCE_REVIEW");
        assertThat(zhongxing.actionLabel()).isEqualTo("周期证据待补");
        assertThat(zhongxing.todayAdvice().action()).isEqualTo("WAIT");
        assertThat(zhongxing.todayAdvice().reasons())
                .anyMatch(reason -> reason.contains("行业价格") && reason.contains("供需"));
    }

    @Test
    void shouldHoldExistingPositionAndWaitPullbackAfterSharpRightSideSpike() {
        eastMoneyClient.quotes = List.of(quote("002772", "众兴菌业", "12.84", "7.18", "10.71", "1.37"));
        eastMoneyClient.klines.put("002772", rightSpikeKLines());
        eastMoneyClient.fundFlows.put("002772", fundFlow("002772"));

        CycleTrialReport report = service.report(10, null, null, null, null);

        CycleTrialCandidate zhongxing = find(report, "002772");
        assertThat(zhongxing.action()).isEqualTo("RIGHT_START_WAIT_PULLBACK");
        assertThat(zhongxing.phaseLabel()).isEqualTo("右侧早期");
        assertThat(zhongxing.todayAdvice().action()).isEqualTo("HOLD");
        assertThat(zhongxing.todayAdvice().actionLabel()).contains("等回踩");
        assertThat(zhongxing.todayAdvice().reasons())
                .anyMatch(reason -> reason.contains("主力净流入") || reason.contains("主动做多"));
        assertThat(zhongxing.evidence())
                .anyMatch(evidence -> "主力资金流".equals(evidence.title()) && evidence.summary().contains("超大单"));
    }

    @Test
    void shouldFlagValuationAdvantageAgainstIndustryLeaders() {
        eastMoneyClient.quotes = List.of(quote("002772", "众兴菌业", "11.92", "0.10", "10.20", "1.25"));
        eastMoneyClient.klines.put("002772", leftTrialKLines());
        eastMoneyClient.industryPeers.put("农牧饲渔", List.of(
                peerQuote("600001", "农业龙头A", "农牧饲渔", "20.00", "2.50", "950000000"),
                peerQuote("600002", "农业龙头B", "农牧饲渔", "18.00", "2.20", "860000000"),
                peerQuote("600003", "农业龙头C", "农牧饲渔", "16.00", "2.00", "720000000")
        ));

        CycleTrialReport report = service.report(10, null, null, null, null);

        CycleTrialCandidate zhongxing = find(report, "002772");
        assertThat(zhongxing.peerValuation()).isNotNull();
        assertThat(zhongxing.peerValuation().industry()).isEqualTo("农牧饲渔");
        assertThat(zhongxing.peerValuation().peers()).hasSize(3);
        assertThat(zhongxing.peerValuation().averagePeTtm()).isEqualByComparingTo("18.00");
        assertThat(zhongxing.peerValuation().averagePbRatio()).isEqualByComparingTo("2.23");
        assertThat(zhongxing.peerValuation().valuationAdvantage()).isTrue();
        assertThat(zhongxing.peerValuation().conclusion()).contains("估值优势");
        assertThat(zhongxing.evidence())
                .anyMatch(evidence -> "行业头部 PE/PB 对比".equals(evidence.title()));
        assertThat(zhongxing.todayAdvice().reasons())
                .anyMatch(reason -> reason.contains("同业估值"));
    }

    @Test
    void shouldNotMixOtherCycleGroupsWhenPeerBoardFallbackIsUsed() {
        eastMoneyClient.quotes = List.of(
                quote("002772", "众兴菌业", "11.92", "0.10", "10.20", "1.25"),
                quote("002714", "牧原股份", "45.00", "0.20", "22.72", "2.65"),
                quote("600309", "万华化学", "82.00", "0.30", "16.90", "2.07"),
                quote("601088", "中国神华", "39.00", "-0.20", "17.56", "1.81")
        );
        eastMoneyClient.klines.put("002772", leftTrialKLines());

        CycleTrialReport report = service.report(10, null, null, null, null);

        CycleTrialCandidate zhongxing = find(report, "002772");
        assertThat(zhongxing.peerValuation().peers())
                .extracting(CyclePeerValuationCompany::symbol)
                .contains("002714", "600309", "601088");
    }

    private CycleTrialCandidate find(CycleTrialReport report, String symbol) {
        return report.candidates().stream()
                .filter(candidate -> symbol.equals(candidate.symbol()))
                .findFirst()
                .orElseThrow();
    }

    private List<EastMoneyKLine> leftTrialKLines() {
        List<EastMoneyKLine> rows = downtrendRows();
        rows.add(kline("2026-06-29", "11.45", "11.84", "11.97", "11.10", "107500"));
        rows.add(kline("2026-06-30", "11.69", "11.36", "11.74", "11.28", "71514"));
        rows.add(kline("2026-07-01", "11.40", "11.98", "12.17", "11.31", "117403"));
        rows.add(kline("2026-07-02", "11.91", "11.92", "12.05", "11.82", "70000"));
        return rows;
    }

    private List<EastMoneyKLine> rightSpikeKLines() {
        List<EastMoneyKLine> rows = downtrendRows();
        rows.add(kline("2026-06-29", "11.45", "11.84", "11.97", "11.10", "107500"));
        rows.add(kline("2026-06-30", "11.69", "11.36", "11.74", "11.28", "71514"));
        rows.add(kline("2026-07-01", "11.40", "11.98", "12.17", "11.31", "117403"));
        rows.add(kline("2026-07-02", "11.91", "12.84", "13.07", "11.91", "162829"));
        return rows;
    }

    private List<EastMoneyKLine> downtrendRows() {
        List<EastMoneyKLine> rows = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-04-01");
        BigDecimal close = new BigDecimal("16.80");
        for (int index = 0; index < 58; index++) {
            BigDecimal dayClose = close.subtract(new BigDecimal("0.08").multiply(BigDecimal.valueOf(index)));
            rows.add(new EastMoneyKLine(
                    "002772",
                    start.plusDays(index),
                    dayClose.add(new BigDecimal("0.05")),
                    dayClose,
                    dayClose.add(new BigDecimal("0.16")),
                    dayClose.subtract(new BigDecimal("0.18")),
                    new BigDecimal("68000"),
                    null
            ));
        }
        return rows;
    }

    private EastMoneyKLine kline(String date, String open, String close, String high, String low, String volume) {
        return new EastMoneyKLine(
                "002772",
                LocalDate.parse(date),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(volume),
                null
        );
    }

    private EastMoneyQuote quote(String symbol, String name, String price, String changePercent, String pe, String pb) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                "农牧饲渔",
                new BigDecimal(price),
                new BigDecimal(changePercent),
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal("200000000"),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-02T06:50:00Z")
        );
    }

    private EastMoneyQuote peerQuote(String symbol, String name, String industry, String pe, String pb, String amount) {
        return new EastMoneyQuote(
                symbol,
                name,
                symbol.startsWith("6") ? "上交所" : "深交所",
                industry,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                BigDecimal.ONE,
                new BigDecimal("100000"),
                new BigDecimal(amount),
                new BigDecimal(pe),
                new BigDecimal(pb),
                new BigDecimal(pe),
                "测试行情",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-02T06:50:00Z")
        );
    }

    private EastMoneyFundFlowSnapshot fundFlow(String symbol) {
        return new EastMoneyFundFlowSnapshot(
                symbol,
                "众兴菌业",
                new BigDecimal("54563039"),
                new BigDecimal("15987968"),
                new BigDecimal("38575071"),
                new BigDecimal("14572502"),
                new BigDecimal("-69135541"),
                new BigDecimal("13.39"),
                new BigDecimal("3.92"),
                new BigDecimal("9.47"),
                new BigDecimal("3.58"),
                new BigDecimal("-16.97"),
                "东方财富资金流",
                "https://quote.example.com/" + symbol,
                Instant.parse("2026-07-03T07:00:00Z")
        );
    }

    private static final class StubEastMoneyClient extends EastMoneyClient {

        private List<EastMoneyQuote> quotes = List.of();
        private final Map<String, List<EastMoneyKLine>> klines = new java.util.HashMap<>();
        private final Map<String, EastMoneyFundFlowSnapshot> fundFlows = new java.util.HashMap<>();
        private final Map<String, List<EastMoneyQuote>> industryPeers = new java.util.HashMap<>();

        private StubEastMoneyClient() {
            super(null, null, null);
        }

        @Override
        public List<EastMoneyQuote> fetchEastMoneyQuotesBySymbols(List<String> symbols, int limit) {
            return quotes;
        }

        @Override
        public List<EastMoneyQuote> fetchAshareQuotes(int limit) {
            return quotes;
        }

        @Override
        public List<EastMoneyQuote> fetchTencentQuotes(List<String> symbols, int limit) {
            return List.of();
        }

        @Override
        public List<EastMoneyQuote> fetchIndustryBoardConstituents(String industryName, int limit) {
            return industryPeers.getOrDefault(industryName, List.of()).stream()
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<EastMoneyKLine> fetchDailyKLines(String symbol, LocalDate begin, LocalDate end) {
            return klines.getOrDefault(symbol, List.of());
        }

        @Override
        public Optional<EastMoneyFundFlowSnapshot> fetchFundFlowSnapshot(String symbol) {
            return Optional.ofNullable(fundFlows.get(symbol));
        }
    }
}
