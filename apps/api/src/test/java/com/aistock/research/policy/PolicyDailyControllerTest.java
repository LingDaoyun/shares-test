package com.aistock.research.policy;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PolicyDailyControllerTest {
    private final PolicyDailyService service = mock(PolicyDailyService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PolicyDailyController(service)).build();

    @Test
    void getUsesSavedSnapshotWithoutRequestingRefresh() throws Exception {
        when(service.current()).thenReturn(PolicyDailySnapshot.idle());
        mvc.perform(get("/api/policy/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.scheduledTime").value("每天08:30（北京时间）"))
                .andExpect(jsonPath("$.themes").isArray())
                .andExpect(jsonPath("$.reviewedThemeCodes").isEmpty())
                .andExpect(jsonPath("$.dataGaps").isEmpty());
        verify(service).current();
        verifyNoMoreInteractions(service);
    }

    @Test
    void refreshReturnsAcceptedSnapshot() throws Exception {
        when(service.refresh()).thenReturn(new PolicyDailySnapshot("RUNNING", PolicyDailySnapshot.SCHEDULED_TIME,
                Instant.parse("2026-10-17T01:00:00Z"), null, List.of(), List.of(), List.of()));
        mvc.perform(post("/api/policy/daily/refresh"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").exists());
        verify(service).refresh();
    }
}
