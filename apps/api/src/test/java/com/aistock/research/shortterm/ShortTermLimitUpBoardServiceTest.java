package com.aistock.research.shortterm;

import com.aistock.research.integration.eastmoney.EastMoneyClient;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShortTermLimitUpBoardServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-08-21");
    private final ShortTermLimitUpBoardService service =
            new ShortTermLimitUpBoardService(mock(EastMoneyClient.class));

    @Test
    void industryStatsGroupCountBoardDepthAndLeaders() {
        List<ShortTermLimitUpIndustryStat> stats = ShortTermLimitUpBoardService.industryStats(List.of(
                entry("600001", "甲股", "半导体", 1, "09:25:00"),
                entry("600002", "乙股", "半导体", 3, "10:10:00"),
                entry("600003", "丙股", "半导体", 2, "09:35:00"),
                entry("600004", "丁股", "医药", 1, "13:30:00")
        ));

        assertThat(stats).hasSize(2);
        ShortTermLimitUpIndustryStat semiconductor = stats.get(0);
        assertThat(semiconductor.industry()).isEqualTo("半导体");
        assertThat(semiconductor.limitUpCount()).isEqualTo(3);
        assertThat(semiconductor.maxConsecutiveBoards()).isEqualTo(3);
        assertThat(semiconductor.leaders()).containsExactly("乙股", "丙股", "甲股");
        assertThat(stats.get(1).industry()).isEqualTo("医药");
        assertThat(stats.get(1).limitUpCount()).isEqualTo(1);
    }

    @Test
    void sentimentComputesBreakRatioTimeBucketsAndCounts() {
        ShortTermLimitUpSentiment sentiment = ShortTermLimitUpBoardService.sentiment(List.of(
                entry("600001", "甲", "半导体", 1, "09:25:00"),
                entry("600002", "乙", "半导体", 2, "09:59:59"),
                entry("600003", "丙", "半导体", 1, "10:00:00"),
                entry("600004", "丁", "医药", 4, "11:29:59"),
                entry("600005", "戊", "医药", 1, "11:30:00"),
                entry("600006", "己", "医药", 1, "14:29:59"),
                entry("600007", "庚", "医药", 1, "14:30:00")
        ), 3, 2);

        assertThat(sentiment.limitUpCount()).isEqualTo(7);
        assertThat(sentiment.brokenCount()).isEqualTo(3);
        assertThat(sentiment.limitDownCount()).isEqualTo(2);
        assertThat(sentiment.sealBreakRatioPercent()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(sentiment.maxConsecutiveBoards()).isEqualTo(4);
        assertThat(sentiment.boards2PlusCount()).isEqualTo(2);
        assertThat(sentiment.boards3PlusCount()).isEqualTo(1);
        assertThat(sentiment.sealedBeforeTenCount()).isEqualTo(2);
        assertThat(sentiment.sealedMorningCount()).isEqualTo(2);
        assertThat(sentiment.sealedAfternoonCount()).isEqualTo(2);
        assertThat(sentiment.sealedTailCount()).isEqualTo(1);
        assertThat(sentiment.earlySealSharePercent()).isEqualByComparingTo(new BigDecimal("28.57"));
        assertThat(sentiment.tone()).isEqualTo("情绪冰点");
        assertThat(sentiment.explanation()).contains("炸板率 30.00%").contains("最高 4 连板");
    }

    @Test
    void tonePhasesFollowSealBreakRatioAndEarlySealShare() {
        List<EastMoneyLimitUpPoolEntry> sixtyEarly = IntStream.range(0, 60)
                .mapToObj(index -> entry(
                        "60" + String.format("%03d", index),
                        "股" + index,
                        "半导体",
                        1,
                        index < 35 ? "09:30:00" : "10:30:00"
                ))
                .toList();
        assertThat(ShortTermLimitUpBoardService.sentiment(sixtyEarly, 6, 2).tone()).isEqualTo("情绪强势");
        assertThat(ShortTermLimitUpBoardService.sentiment(sixtyEarly, 40, 2).tone()).isEqualTo("接力退潮");

        List<EastMoneyLimitUpPoolEntry> sixtyLateSeal = IntStream.range(0, 60)
                .mapToObj(index -> entry(
                        "60" + String.format("%03d", index),
                        "股" + index,
                        "半导体",
                        1,
                        index < 29 ? "09:30:00" : "10:30:00"
                ))
                .toList();
        assertThat(ShortTermLimitUpBoardService.sentiment(sixtyLateSeal, 6, 2).tone()).isEqualTo("情绪偏暖");

        List<EastMoneyLimitUpPoolEntry> mixed = IntStream.range(0, 30)
                .mapToObj(index -> entry(
                        "60" + String.format("%03d", index),
                        "股" + index,
                        "半导体",
                        1,
                        index < 29 ? "09:30:00" : "10:30:00"
                ))
                .toList();
        assertThat(ShortTermLimitUpBoardService.sentiment(mixed, 6, 2).tone()).isEqualTo("中性震荡");
    }

    @Test
    void buildSnapshotOrdersStocksByBoardDepthThenEarliestSealTime() {
        ShortTermLimitUpBoardSnapshot snapshot = service.buildSnapshot(
                TRADE_DATE,
                Instant.parse("2026-08-21T08:00:00Z"),
                List.of(
                        entry("600001", "甲", "半导体", 1, "09:25:00"),
                        entry("600002", "乙", "半导体", 3, "10:10:00"),
                        entry("600003", "丙", "半导体", 2, "09:30:00"),
                        entry("600004", "丁", "医药", 2, "09:26:00")
                ),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.stocks()).extracting(ShortTermLimitUpStock::symbol)
                .containsExactly("600002", "600004", "600003", "600001");
        assertThat(snapshot.sentiment().maxConsecutiveBoards()).isEqualTo(3);
        assertThat(snapshot.sentiment().limitUpCount()).isEqualTo(4);
        assertThat(snapshot.dataGaps()).isEmpty();
    }

    private EastMoneyLimitUpPoolEntry entry(
            String symbol, String name, String industry, int boards, String firstSealTime
    ) {
        return new EastMoneyLimitUpPoolEntry(
                symbol,
                name,
                industry,
                new BigDecimal("7.11"),
                new BigDecimal("10.00"),
                new BigDecimal("100000000"),
                new BigDecimal("5.00"),
                new BigDecimal("2000000000"),
                boards,
                1,
                1,
                new BigDecimal("50000000"),
                LocalTime.parse(firstSealTime),
                LocalTime.parse(firstSealTime),
                0
        );
    }
}
