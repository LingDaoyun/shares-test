package com.aistock.research.shortterm;

import com.aistock.research.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MarketThemeControllerTest {
    private final MarketThemeService service = mock(MarketThemeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketThemeController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void unavailableSnapshotStillPreservesDataQualityContract() throws Exception {
        when(service.snapshot()).thenReturn(new MarketThemeSnapshot(null, Instant.parse("2026-10-26T06:00:00Z"),
                "UNAVAILABLE", "东方财富概念板块（type=3）", 0, 0, 0, List.of(), List.of("行情暂缺")));
        mvc.perform(get("/api/short-term/market-themes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.themes").isEmpty()).andExpect(jsonPath("$.dataGaps[0]").value("行情暂缺"));
    }

    @Test
    void missingOrMalformedDateIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(get("/api/short-term/market-themes/BK1090")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/short-term/market-themes/BK1090").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void changedDateReturnsRefreshInstruction() throws Exception {
        when(service.detail("BK1090", LocalDate.parse("2026-10-23")))
                .thenThrow(new IllegalArgumentException("题材行情日期已变化，请刷新看板后重试。"));
        mvc.perform(get("/api/short-term/market-themes/BK1090").param("date", "2026-10-23"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("题材行情日期已变化，请刷新看板后重试。"));
    }
}
