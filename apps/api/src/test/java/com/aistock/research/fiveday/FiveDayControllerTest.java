package com.aistock.research.fiveday;

import com.aistock.research.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FiveDayControllerTest {
    private final FiveDayService service = mock(FiveDayService.class);
    private final FiveDayHistoryService history = mock(FiveDayHistoryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FiveDayController(service, history))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void unsupportedStrategyCannotSilentlyUseTheOldModel() throws Exception {
        mvc.perform(get("/api/five-day/snapshot").param("strategy", "v3"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, history);
    }

    @Test
    void omittedStrategyKeepsOldClientBehavior() throws Exception {
        mvc.perform(get("/api/five-day/snapshot")).andExpect(status().isOk());
        verify(service).snapshot(false, false);
    }

    @Test
    void v2RequestsKeepScanHistoryRefreshAndExecutionOnTheSameVersion() throws Exception {
        mvc.perform(get("/api/five-day/snapshot").param("strategy","v2").param("includeGrowth","true")).andExpect(status().isOk());
        mvc.perform(post("/api/five-day/scan").param("strategy","v2")).andExpect(status().isOk());
        mvc.perform(get("/api/five-day/history").param("strategy","v2")).andExpect(status().isOk());
        mvc.perform(post("/api/five-day/history/refresh").param("strategy","v2")).andExpect(status().isOk());
        mvc.perform(post("/api/five-day/execution-check/600001").param("strategy","v2")).andExpect(status().isOk());
        mvc.perform(post("/api/five-day/attestation/600001").param("strategy","v2")).andExpect(status().isOk());
        verify(service).snapshot(true,false,FiveDayModels.VERSION_V2);
        verify(service).start(false,false,FiveDayModels.VERSION_V2);
        verify(history,times(2)).history(FiveDayModels.VERSION_V2);
        verify(history).refresh(FiveDayModels.VERSION_V2);
        verify(service).executionCheck("600001",false,false,FiveDayModels.VERSION_V2);
        verify(service).attestation("600001",false,false,FiveDayModels.VERSION_V2);
    }

    @Test
    void malformedEntryDateReturnsAParameterErrorWithoutCallingTheService() throws Exception {
        mvc.perform(get("/api/five-day/trade-schedule").param("entryDate", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.entryDate").value("请求参数格式错误"));
        verifyNoInteractions(service, history);
    }

    @Test
    void missingEntryDateReturnsAParameterErrorWithoutCallingTheService() throws Exception {
        mvc.perform(get("/api/five-day/trade-schedule"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.entryDate").value("缺少必填请求参数"));
        verifyNoInteractions(service, history);
    }

    @Test
    void validEntryDateIsPassedToTheScheduleService() throws Exception {
        LocalDate entry = LocalDate.parse("2026-09-08");
        when(service.tradeSchedule(entry)).thenReturn(new FiveDayModels.TradeSchedule(
                entry, LocalDate.parse("2026-09-14"), 1, "HOLDING", "买入日计第1天"));
        mvc.perform(get("/api/five-day/trade-schedule").param("entryDate", "2026-09-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HOLDING"));
        verify(service).tradeSchedule(entry);
        verifyNoInteractions(history);
    }
}
