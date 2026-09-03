package com.aistock.research.leader;

import com.aistock.research.firstboard.FirstBoardAlertStatus;
import com.aistock.research.firstboard.FirstBoardBar;
import com.aistock.research.firstboard.FirstBoardBoard;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineAccess;
import com.aistock.research.firstboard.FirstBoardLeaderBaselineStock;
import com.aistock.research.integration.eastmoney.EastMoneyQuote;
import com.aistock.research.shortterm.ShortTermBrokenBoardStock;
import com.aistock.research.shortterm.ShortTermLimitUpStock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderCandidateEngineTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-09-03");
    private final LeaderCandidateEngine candidateEngine = new LeaderCandidateEngine();

    @Test
    void keepsUntouchedStockInPotentialLane() {
        LeaderCandidateEngine.CandidateUniverse selected = candidateEngine.select(
                input(quote("600000", "10.99"), List.of(), List.of()));

        assertThat(selected.potential()).hasSize(1);
        assertThat(selected.competition()).isEmpty();
    }

    @Test
    void neverReturnsTouchedStockToPotentialLaneWithoutBoardEvidence() {
        LeaderCandidateEngine.CandidateUniverse selected = candidateEngine.select(
                input(quote("600000", "11.00"), List.of(), List.of()));

        assertThat(selected.potential()).isEmpty();
        assertThat(selected.competition()).isEmpty();
    }

    @Test
    void mergesSameSymbolFromSealedAndBrokenPoolsIntoOneCompetitionRow() {
        ShortTermLimitUpStock sealed = new ShortTermLimitUpStock(
                "600000", "测试股份", "半导体", bd("11.00"), bd("10.00"), bd("180000000"),
                bd("8.00"), 2, 2, 2, bd("20000000"),
                LocalTime.of(10, 15), LocalTime.of(10, 20), 1);
        ShortTermBrokenBoardStock broken = new ShortTermBrokenBoardStock(
                "600000", "测试股份", "半导体", bd("10.80"), bd("11.00"), bd("8.00"),
                bd("180000000"), LocalTime.of(9, 45), 2, false);

        LeaderCandidateEngine.CandidateUniverse selected = candidateEngine.select(
                input(quote("600000", "11.00"), List.of(sealed), List.of(broken)));

        assertThat(selected.competition()).singleElement().satisfies(candidate -> {
            assertThat(candidate.boardState().currentlySealed()).isTrue();
            assertThat(candidate.boardState().firstSealTime()).isEqualTo(LocalTime.of(9, 45));
            assertThat(candidate.boardState().lastSealTime()).isEqualTo(LocalTime.of(10, 20));
            assertThat(candidate.boardState().sealBreakCount()).isEqualTo(2);
        });
    }

    private LeaderCandidateEngine.SelectionInput input(
            EastMoneyQuote quote,
            List<ShortTermLimitUpStock> sealed,
            List<ShortTermBrokenBoardStock> broken
    ) {
        FirstBoardLeaderBaselineStock baseline = baseline(quote.symbol());
        FirstBoardLeaderBaselineAccess access = new FirstBoardLeaderBaselineAccess(
                FirstBoardAlertStatus.READY, TRADE_DATE, TRADE_DATE.minusDays(1),
                Instant.parse("2026-09-02T08:20:00Z"), Map.of(quote.symbol(), baseline), null,
                1, 1, 0, 0, List.of());
        PotentialLeaderSnapshot.Direction direction = new PotentialLeaderSnapshot.Direction(
                "半导体", 1, bd("82.00"), bd("70.00"), bd("4.00"),
                sealed.size(), broken.size(), 2, bd("200000000"), List.of(quote.symbol()));
        return new LeaderCandidateEngine.SelectionInput(
                TRADE_DATE, access, List.of(quote), List.of(direction), sealed, broken, false, false);
    }

    private EastMoneyQuote quote(String symbol, String high) {
        Instant quoteTime = Instant.parse("2026-09-03T06:30:00Z");
        return new EastMoneyQuote(
                symbol, "测试股份", "沪深", "半导体",
                bd("10.80"), bd("8.00"), bd("8.00"), bd("1000000"), bd("180000000"),
                null, null, null, "test", "https://example.test", quoteTime, TRADE_DATE, quoteTime,
                bd("12000000000"), bd("10.10"), bd(high), bd("10.00"), bd("1.80"),
                bd("8000000000"));
    }

    private FirstBoardLeaderBaselineStock baseline(String symbol) {
        List<FirstBoardBar> bars = java.util.stream.IntStream.range(0, 60)
                .mapToObj(index -> new FirstBoardBar(
                        TRADE_DATE.minusDays(60L - index), bd("9.50"), bd("10.00"),
                        bd("10.10"), bd("9.40"), bd("100000")))
                .toList();
        return new FirstBoardLeaderBaselineStock(
                symbol, "测试股份", "半导体", FirstBoardBoard.MAIN, TRADE_DATE.minusDays(1),
                bd("10.00"), bd("9.80"), bd("9.50"), bd("0.03"), bd("58"), bd("0.12"),
                5, bd("1.20"), bd("1.80"), bd("0.10"), bd("0.08"), false, bars);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
