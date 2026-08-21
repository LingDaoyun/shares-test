package com.aistock.research.shortterm.leader;

import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermCoverageSnapshot;
import com.aistock.research.shortterm.ShortTermHotDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultShortTermLeaderRiskModuleTest {

    private static final LocalDate BASELINE_DATE = LocalDate.parse("2026-08-20");
    private static final LocalDate CURRENT_DATE = LocalDate.parse("2026-08-21");
    private static final Instant BASELINE_AT = Instant.parse("2026-08-20T06:30:00Z");
    private static final Instant CURRENT_AT = Instant.parse("2026-08-21T01:45:00Z");

    @Test
    void firstReliableScanBuildsAndSavesAnInitialBaseline() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);

        ShortTermLeaderRisk initial = module.evaluate(input(
                BASELINE_DATE, BASELINE_AT, baselineQuotes(), baselineDirections(),
                List.of("化学制药", "化学制药")
        ));

        assertThat(initial.status()).isEqualTo(ShortTermLeaderRisk.Status.BASELINE_BUILDING);
        assertThat(initial.baselineType()).isEqualTo(ShortTermLeaderRisk.BaselineType.INITIAL);
        assertThat(store.saved).hasSize(1);
    }

    @Test
    void firstScanOfTradingDayUsesPreviousDayAndDetectsBothTracks() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        module.evaluate(input(BASELINE_DATE, BASELINE_AT, baselineQuotes(), baselineDirections(), List.of()));

        ShortTermLeaderRisk previousDay = module.evaluate(input(
                CURRENT_DATE, CURRENT_AT, strengthenedQuotes(), strengthenedDirections(),
                List.of("化学制药", "化学制药", "医疗服务")
        ));

        assertThat(previousDay.status()).isEqualTo(ShortTermLeaderRisk.Status.WARNING);
        assertThat(previousDay.baselineType())
                .isEqualTo(ShortTermLeaderRisk.BaselineType.PREVIOUS_TRADING_DAY);
        assertThat(previousDay.signals()).extracting(ShortTermLeaderRiskSignal::track)
                .contains(ShortTermLeaderRiskSignal.Track.WEIGHT,
                        ShortTermLeaderRiskSignal.Track.THEME);
        assertThat(previousDay.directionConflict()).isTrue();
    }

    @Test
    void laterScanOfTradingDayPrefersPreviousScan() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        module.evaluate(input(BASELINE_DATE, BASELINE_AT, baselineQuotes(), baselineDirections(), List.of()));
        module.evaluate(input(CURRENT_DATE, CURRENT_AT, strengthenedQuotes(), strengthenedDirections(), List.of()));

        ShortTermLeaderRisk previousScan = module.evaluate(input(
                CURRENT_DATE, Instant.parse("2026-08-21T02:15:00Z"),
                acceleratedAgainQuotes(), strengthenedDirections(), List.of("化学制药")
        ));

        assertThat(previousScan.baselineType())
                .isEqualTo(ShortTermLeaderRisk.BaselineType.PREVIOUS_SCAN);
        assertThat(previousScan.baselineAt()).isEqualTo(CURRENT_AT);
    }

    @Test
    void sensitiveWarningDoesNotRequireThePriorHotDirectionToWeaken() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        module.evaluate(input(BASELINE_DATE, BASELINE_AT, baselineQuotes(), baselineDirections(), List.of()));

        ShortTermLeaderRisk risk = module.evaluate(input(
                CURRENT_DATE, CURRENT_AT, strengthenedQuotes(), directionsWithoutPriorWeakening(),
                List.of("化学制药", "化学制药")
        ));

        assertThat(risk.signals()).isNotEmpty();
        assertThat(risk.signals()).extracting(ShortTermLeaderRiskSignal::track)
                .contains(ShortTermLeaderRiskSignal.Track.THEME);
        assertThat(risk.summary()).contains("资金切换");
    }

    @Test
    void unreliableCoverageReturnsUnavailableWithoutSavingBaseline() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        List<EastMoneyQuote> quotes = strengthenedQuotes();
        ShortTermCoverageSnapshot unreliableCoverage = new ShortTermCoverageSnapshot(
                quotes.size(), quotes.size() - 3, 3, new BigDecimal("0.9250"), false,
                "测试", CURRENT_AT
        );

        ShortTermLeaderRisk unavailable = module.evaluate(new ShortTermLeaderRiskInput(
                CURRENT_DATE, CURRENT_AT, unreliableCoverage, quotes,
                strengthenedDirections(), List.of("化学制药")
        ));

        assertThat(unavailable.status()).isEqualTo(ShortTermLeaderRisk.Status.UNAVAILABLE);
        assertThat(unavailable.advisoryOnly()).isTrue();
        assertThat(store.saved).isEmpty();
    }

    @Test
    void directionConflictUsesAllDetectedSignalsBeforeDisplayCap() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        module.evaluate(input(
                BASELINE_DATE, BASELINE_AT, multiThemeQuotes(false), multiThemeDirections(), List.of()
        ));

        ShortTermLeaderRisk risk = module.evaluate(input(
                CURRENT_DATE, CURRENT_AT, multiThemeQuotes(true), multiThemeDirections(),
                List.of("化学制药", "化学制药", "化学制药")
        ));

        assertThat(risk.signals()).hasSize(3);
        assertThat(risk.signals()).extracting(ShortTermLeaderRiskSignal::direction)
                .doesNotContain("化学制药");
        assertThat(risk.directionConflict()).isFalse();
    }

    @Test
    void unresolvedBaselineLeaderDoesNotBecomeReplacementSignalAndIsReportedAsGap() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        module.evaluate(input(
                BASELINE_DATE,
                BASELINE_AT,
                unchangedResolvedLeaderQuotes(BASELINE_DATE, BASELINE_AT),
                List.of(direction(
                        "THEME:AI", "人工智能", "100.00", "4.50", "4800",
                        List.of("无法解析龙头(399999)")
                )),
                List.of()
        ));

        ShortTermLeaderRisk risk = module.evaluate(input(
                CURRENT_DATE,
                CURRENT_AT,
                unchangedResolvedLeaderQuotes(CURRENT_DATE, CURRENT_AT),
                List.of(direction(
                        "THEME:AI", "人工智能", "100.00", "4.50", "4800",
                        List.of("人工智能龙头(300001)")
                )),
                List.of()
        ));

        assertThat(risk.signals()).extracting(ShortTermLeaderRiskSignal::track)
                .doesNotContain(ShortTermLeaderRiskSignal.Track.THEME);
        assertThat(risk.status()).isEqualTo(ShortTermLeaderRisk.Status.CLEAR);
        assertThat(risk.dataGaps()).anyMatch(gap -> gap.contains("基准热门方向首位龙头未解析"));
    }

    @Test
    void partiallyResolvedBaselineDoesNotPromoteSecondLeaderOrManufactureReplacement() {
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        ShortTermLeaderRiskModule module = new DefaultShortTermLeaderRiskModule(store);
        List<String> unchangedEncodedLeaders = List.of(
                "首位龙头(399999)",
                "人工智能龙头(300001)"
        );
        module.evaluate(input(
                BASELINE_DATE,
                BASELINE_AT,
                unchangedResolvedLeaderQuotes(BASELINE_DATE, BASELINE_AT),
                List.of(direction(
                        "THEME:AI", "人工智能", "100.00", "4.50", "4800",
                        unchangedEncodedLeaders
                )),
                List.of()
        ));

        ShortTermLeaderRisk risk = module.evaluate(input(
                CURRENT_DATE,
                CURRENT_AT,
                quotesWithResolvedTopLeader(),
                List.of(direction(
                        "THEME:AI", "人工智能", "100.00", "4.50", "4800",
                        unchangedEncodedLeaders
                )),
                List.of()
        ));

        assertThat(risk.status()).isEqualTo(ShortTermLeaderRisk.Status.CLEAR);
        assertThat(risk.signals()).extracting(ShortTermLeaderRiskSignal::track)
                .doesNotContain(ShortTermLeaderRiskSignal.Track.THEME);
        assertThat(risk.dataGaps()).anyMatch(gap -> gap.contains("基准热门方向首位龙头未解析"));
    }

    private ShortTermLeaderRiskInput input(
            LocalDate tradeDate,
            Instant capturedAt,
            List<EastMoneyQuote> quotes,
            List<ShortTermHotDirection> directions,
            List<String> candidateIndustries
    ) {
        ShortTermCoverageSnapshot coverage = new ShortTermCoverageSnapshot(
                quotes.size(), quotes.size(), 0, BigDecimal.ONE, true, "测试", capturedAt
        );
        return new ShortTermLeaderRiskInput(
                tradeDate, capturedAt, coverage, quotes, directions, candidateIndustries
        );
    }

    private List<EastMoneyQuote> baselineQuotes() {
        return market("0.50", "2.00", "5.20", "120", "100", "5000", BASELINE_DATE, BASELINE_AT);
    }

    private List<EastMoneyQuote> strengthenedQuotes() {
        return market("3.20", "5.80", "5.30", "5000", "4800", "4700", CURRENT_DATE, CURRENT_AT);
    }

    private List<EastMoneyQuote> acceleratedAgainQuotes() {
        Instant capturedAt = Instant.parse("2026-08-21T02:15:00Z");
        return market("4.40", "7.50", "5.40", "5500", "5200", "4900", CURRENT_DATE, capturedAt);
    }

    private List<EastMoneyQuote> unchangedResolvedLeaderQuotes(LocalDate tradeDate, Instant capturedAt) {
        return market("0.50", "4.50", "5.20", "120", "4800", "5000", tradeDate, capturedAt);
    }

    private List<EastMoneyQuote> quotesWithResolvedTopLeader() {
        List<EastMoneyQuote> quotes = new ArrayList<>(
                unchangedResolvedLeaderQuotes(CURRENT_DATE, CURRENT_AT)
        );
        quotes.remove(quotes.size() - 1);
        quotes.add(quote(
                "399999", "首位龙头", "软件服务", "4.50", "4900",
                "90000000000", CURRENT_DATE, CURRENT_AT
        ));
        return List.copyOf(quotes);
    }

    private List<EastMoneyQuote> multiThemeQuotes(boolean current) {
        LocalDate tradeDate = current ? CURRENT_DATE : BASELINE_DATE;
        Instant capturedAt = current ? CURRENT_AT : BASELINE_AT;
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600001", "权重银行", "银行", "0.20", "100",
                "1000000000000", tradeDate, capturedAt));
        quotes.add(quote("601000", "大市值样本", "综合", "0.20", "200",
                "900000000000", tradeDate, capturedAt));
        quotes.add(quote("300001", "人工智能龙头", "软件服务", current ? "9.00" : "4.00", "6000",
                "100000000000", tradeDate, capturedAt));
        quotes.add(quote("601001", "算力龙头", "计算机设备", current ? "8.00" : "4.00", "5800",
                "90000000000", tradeDate, capturedAt));
        quotes.add(quote("601002", "机器人龙头", "自动化设备", current ? "7.00" : "4.00", "5600",
                "80000000000", tradeDate, capturedAt));
        quotes.add(quote("600003", "医药龙头", "化学制药", current ? "6.00" : "4.00", "5400",
                "70000000000", tradeDate, capturedAt));
        for (int index = 0; index < 34; index++) {
            quotes.add(quote(
                    String.format("602%03d", index),
                    "多信号样本" + index,
                    "基础行业",
                    "0.20",
                    Integer.toString(3000 - index * 50),
                    Long.toString(60000000000L - index * 1000000000L),
                    tradeDate,
                    capturedAt
            ));
        }
        return List.copyOf(quotes);
    }

    private List<ShortTermHotDirection> multiThemeDirections() {
        return List.of(
                direction("THEME:AI", "人工智能", "100.00", "9.00", "6000",
                        List.of("人工智能龙头(300001)")),
                direction("THEME:COMPUTE", "算力", "95.00", "8.00", "5800",
                        List.of("算力龙头(601001)")),
                direction("THEME:ROBOT", "机器人", "90.00", "7.00", "5600",
                        List.of("机器人龙头(601002)")),
                direction("INDUSTRY:化学制药", "化学制药", "85.00", "6.00", "5400",
                        List.of("医药龙头(600003)"))
        );
    }

    private List<EastMoneyQuote> market(
            String weightChange,
            String themeChange,
            String pharmaChange,
            String weightAmount,
            String themeAmount,
            String pharmaAmount,
            LocalDate tradeDate,
            Instant capturedAt
    ) {
        List<EastMoneyQuote> quotes = new ArrayList<>();
        quotes.add(quote("600001", "权重银行", "银行", weightChange, weightAmount,
                "1000000000000", tradeDate, capturedAt));
        quotes.add(quote("300001", "人工智能龙头", "软件服务", themeChange, themeAmount,
                "100000000000", tradeDate, capturedAt));
        quotes.add(quote("600003", "医药龙头", "化学制药", pharmaChange, pharmaAmount,
                "80000000000", tradeDate, capturedAt));
        for (int index = 0; index < 37; index++) {
            quotes.add(quote(
                    String.format("601%03d", index),
                    "样本" + index,
                    index == 0 ? "综合" : "基础行业",
                    "0.20",
                    Integer.toString(4000 - index * 80),
                    index == 0 ? "900000000000" : Long.toString(70000000000L - index * 1000000000L),
                    tradeDate,
                    capturedAt
            ));
        }
        return List.copyOf(quotes);
    }

    private EastMoneyQuote quote(
            String symbol,
            String name,
            String industry,
            String change,
            String amount,
            String totalMarketValue,
            LocalDate tradeDate,
            Instant capturedAt
    ) {
        return new EastMoneyQuote(
                symbol, name, "测试市场", industry,
                new BigDecimal("10.00"), new BigDecimal(change), new BigDecimal("3.00"),
                new BigDecimal("100000"), new BigDecimal(amount), new BigDecimal("18"),
                new BigDecimal("1.60"), new BigDecimal("18"), "测试",
                "https://example.test/" + symbol, capturedAt, tradeDate, capturedAt,
                new BigDecimal(totalMarketValue)
        );
    }

    private List<ShortTermHotDirection> baselineDirections() {
        return List.of(direction(
                "INDUSTRY:化学制药", "化学制药", "100.00", "5.20", "5000",
                List.of("医药龙头(600003)")
        ));
    }

    private List<ShortTermHotDirection> strengthenedDirections() {
        return List.of(
                direction("THEME:AI", "人工智能", "100.00", "5.80", "4800",
                        List.of("人工智能龙头(300001)")),
                direction("INDUSTRY:化学制药", "化学制药", "95.00", "5.30", "4700",
                        List.of("医药龙头(600003)"))
        );
    }

    private List<ShortTermHotDirection> directionsWithoutPriorWeakening() {
        return List.of(
                direction("INDUSTRY:化学制药", "化学制药", "100.00", "5.30", "4700",
                        List.of("医药龙头(600003)")),
                direction("THEME:AI", "人工智能", "95.00", "5.80", "4800",
                        List.of("人工智能龙头(300001)"))
        );
    }

    private ShortTermHotDirection direction(
            String code,
            String label,
            String heat,
            String averageChange,
            String totalAmount,
            List<String> leaders
    ) {
        return new ShortTermHotDirection(
                code, label, new BigDecimal(heat), new BigDecimal(averageChange),
                new BigDecimal("100.00"), new BigDecimal(totalAmount), leaders.size(),
                leaders, "测试方向"
        );
    }

    private static final class InMemorySnapshotStore implements ShortTermLeaderSnapshotStore {

        private final List<ShortTermLeaderSnapshot> saved = new ArrayList<>();

        @Override
        public Optional<ShortTermLeaderSnapshot> latestSameDayBefore(
                String ruleVersion,
                LocalDate tradeDate,
                Instant capturedAt
        ) {
            return saved.stream()
                    .filter(snapshot -> snapshot.ruleVersion().equals(ruleVersion))
                    .filter(snapshot -> snapshot.tradeDate().equals(tradeDate))
                    .filter(snapshot -> snapshot.capturedAt().isBefore(capturedAt))
                    .max(Comparator.comparing(ShortTermLeaderSnapshot::capturedAt)
                            .thenComparing(ShortTermLeaderSnapshot::snapshotId));
        }

        @Override
        public Optional<ShortTermLeaderSnapshot> latestBeforeTradeDate(
                String ruleVersion,
                LocalDate tradeDate
        ) {
            return saved.stream()
                    .filter(snapshot -> snapshot.ruleVersion().equals(ruleVersion))
                    .filter(snapshot -> snapshot.tradeDate().isBefore(tradeDate))
                    .max(Comparator.comparing(ShortTermLeaderSnapshot::tradeDate)
                            .thenComparing(ShortTermLeaderSnapshot::capturedAt)
                            .thenComparing(ShortTermLeaderSnapshot::snapshotId));
        }

        @Override
        public void save(ShortTermLeaderSnapshot snapshot) {
            saved.add(snapshot);
        }
    }
}
