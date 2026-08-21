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
