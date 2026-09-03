package com.aistock.research.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PotentialLeaderControllerTest {

    private final PotentialLeaderService service = mock(PotentialLeaderService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new PotentialLeaderController(service)).build();
    }

    @Test
    void exposesOnlyPotentialLeaderSnapshotAndBarsRoutes() throws Exception {
        LocalDate date = LocalDate.parse("2026-09-03");
        when(service.snapshot(true, false)).thenReturn(new PotentialLeaderSnapshot(
                PotentialLeaderSnapshot.Status.READY, date, date,
                Instant.parse("2026-09-03T07:00:00Z"), null,
                Instant.parse("2026-09-03T07:00:01Z"), true, LeaderRuleSet.RULE_VERSION,
                new PotentialLeaderSnapshot.BoardOptions(true, false), null,
                List.of(), List.of(), List.of(),
                new PotentialLeaderSnapshot.Coverage(0, 0, 0, 0, 0, 0, 0, 0, 0), List.of()));
        when(service.bars("600000")).thenReturn(Optional.of(new LeaderBarSeries(
                "600000", date, date.minusDays(1), "IGNORED", List.of())));

        mockMvc.perform(get("/api/potential-leaders/snapshot")
                        .param("includeGrowth", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.boardOptions.includeGrowth").value(true));
        mockMvc.perform(get("/api/potential-leaders/bars/600000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("600000"))
                .andExpect(jsonPath("$.adjustMode").value("QFQ"));
        mockMvc.perform(get("/api/first-board-alert/snapshot"))
                .andExpect(status().isNotFound());

        verify(service).snapshot(true, false);
        verify(service).bars("600000");
    }
}
