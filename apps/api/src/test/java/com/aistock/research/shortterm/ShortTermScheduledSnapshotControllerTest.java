package com.aistock.research.shortterm;

import com.aistock.research.quality.EvidenceCompleteness;
import com.aistock.research.shortterm.schedule.ShortTermAutomationSettings;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshot;
import com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotStore;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStage;
import com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus;
import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShortTermScheduledSnapshotControllerTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-07-23");

    @Test
    void returnsLatestPreparedSnapshotForCurrentMarketDate() throws Exception {
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        RecommendationAttestationService attestations = mock(RecommendationAttestationService.class);
        ShortTermScheduledSnapshot stored = finalReadySnapshot();
        when(clock.currentMarketDate()).thenReturn(TODAY);
        when(store.latest(TODAY)).thenReturn(Optional.of(stored));
        when(attestations.attest(stored.report())).thenReturn(stored.report());

        mockMvc(store, clock, mock(ShortTermAutomationSettings.class), attestations)
                .perform(get("/api/short-term/scheduled-snapshots/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeDate").value("2026-07-23"))
                .andExpect(jsonPath("$.status").value("FINAL_READY"))
                .andExpect(jsonPath("$.strategyVersion").value("short-term-right-side-v3-chip-verified"))
                .andExpect(jsonPath("$.report.candidates[0].tradePlan.strategyLabel")
                        .value("隔夜超短波段"));

        verify(store).latest(TODAY);
        verify(attestations).attest(stored.report());
    }

    @Test
    void returnsSameDayWaitingSnapshotWhenNoPreparedRecordExists() throws Exception {
        ShortTermScheduledSnapshotStore store = mock(ShortTermScheduledSnapshotStore.class);
        TradingClockService clock = mock(TradingClockService.class);
        ShortTermAutomationSettings settings = mock(ShortTermAutomationSettings.class);
        when(clock.currentMarketDate()).thenReturn(TODAY);
        when(store.latest(TODAY)).thenReturn(Optional.empty());
        when(settings.preselectCron()).thenReturn("0 30 14 * * MON-FRI");

        mockMvc(store, clock, settings, mock(RecommendationAttestationService.class))
                .perform(get("/api/short-term/scheduled-snapshots/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeDate").value("2026-07-23"))
                .andExpect(jsonPath("$.stage").value("PRESELECT"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.message").value("等待 0 30 14 * * MON-FRI 自动预选"))
                .andExpect(jsonPath("$.report").isEmpty());

        verify(store).latest(TODAY);
    }

    private MockMvc mockMvc(
            ShortTermScheduledSnapshotStore store,
            TradingClockService clock,
            ShortTermAutomationSettings settings,
            RecommendationAttestationService attestations
    ) {
        ShortTermController controller = new ShortTermController(
                mock(ShortTermService.class),
                mock(ShortTermScanJobService.class),
                attestations,
                clock,
                store,
                settings
        );
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper()
                                .registerModule(new JavaTimeModule())
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                .build();
    }

    private ShortTermScheduledSnapshot finalReadySnapshot() {
        Instant cutoff = Instant.parse("2026-07-23T06:52:00Z");
        Instant completed = Instant.parse("2026-07-23T06:53:00Z");
        return new ShortTermScheduledSnapshot(
                "2026-07-23:FINAL:test",
                TODAY,
                ShortTermSnapshotStage.FINAL,
                ShortTermSnapshotStatus.FINAL_READY,
                1,
                "test",
                "{}",
                cutoff,
                Instant.parse("2026-07-23T06:48:00Z"),
                completed,
                "尾盘最终结果已就绪",
                List.of(),
                report(cutoff, completed)
        );
    }

    private ShortTermReport report(Instant cutoff, Instant completed) {
        ShortTermCandidate candidate = new ShortTermCandidate(
                1,
                "600000",
                "浦发银行",
                "沪市",
                "银行",
                new BigDecimal("10.20"),
                new BigDecimal("1.20"),
                null,
                null,
                new BigDecimal("1000000000"),
                null,
                null,
                "TAIL_CONFIRMED",
                "尾盘确认",
                "RIGHT_EARLY_ADD",
                "右侧早期确认",
                "尾盘量价结构确认",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("10.10"),
                new BigDecimal("10.30"),
                new BigDecimal("9.80"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new EvidenceCompleteness(
                        100,
                        "COMPLETE",
                        "证据完整",
                        true,
                        List.of(),
                        List.of(),
                        List.of()
                ),
                List.of(),
                tradePlan()
        );
        return new ShortTermReport(
                "短线右侧",
                5500,
                60,
                60,
                1,
                "全市场覆盖可靠",
                null,
                List.of(),
                null,
                null,
                List.of(candidate),
                List.of(),
                null,
                List.of(),
                Map.of(),
                new ShortTermCoverageSnapshot(
                        5500,
                        5500,
                        0,
                        BigDecimal.ONE,
                        true,
                        "SINA",
                        cutoff
                ),
                List.of("600000"),
                cutoff,
                completed
        );
    }

    private ShortTermTradePlan tradePlan() {
        return new ShortTermTradePlan(
                "隔夜超短波段",
                "ACTIONABLE",
                List.of(),
                "14:45-14:56",
                Instant.parse("2026-07-23T06:56:59Z"),
                new BigDecimal("10.20"),
                new BigDecimal("10.10"),
                new BigDecimal("10.30"),
                new BigDecimal("0.3333"),
                new BigDecimal("0.50"),
                new BigDecimal("3.0"),
                new BigDecimal("10.51"),
                new BigDecimal("0.50"),
                new BigDecimal("5.0"),
                new BigDecimal("10.71"),
                new BigDecimal("3.0"),
                new BigDecimal("9.89"),
                new BigDecimal("2.0"),
                "高点回撤 2% 触发保护",
                LocalDate.parse("2026-07-24"),
                LocalTime.parse("14:50"),
                LocalDate.parse("2026-07-27"),
                LocalTime.parse("14:50"),
                List.of("T+1 趋势延续"),
                List.of(
                        new ShortTermOpenScenario("GAP_UP", "高开", "高开 2% 以上", "分批止盈", List.of()),
                        new ShortTermOpenScenario("FLAT", "平开", "涨跌幅在 2% 内", "按计划观察", List.of()),
                        new ShortTermOpenScenario("GAP_DOWN", "低开", "低开 2% 以上", "优先风控", List.of())
                ),
                List.of("尾盘数据"),
                List.of("推荐不等于持仓")
        );
    }
}
