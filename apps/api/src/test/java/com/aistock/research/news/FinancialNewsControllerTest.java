package com.aistock.research.news;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FinancialNewsControllerTest {
    private final FinancialNewsService service = mock(FinancialNewsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FinancialNewsController(service)).build();

    @Test
    void currentOnlyReadsSavedSnapshot() throws Exception {
        when(service.current()).thenReturn(FinancialNewsSnapshot.idle());
        mvc.perform(get("/api/news/current")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.analyses").isMap());
        verify(service).current();
        verifyNoMoreInteractions(service);
    }

    @Test
    void refreshReturnsAcceptedAndAnalysisUsesOnlyRequestedItem() throws Exception {
        when(service.refresh()).thenReturn(FinancialNewsSnapshot.idle());
        mvc.perform(post("/api/news/refresh")).andExpect(status().isAccepted());
        when(service.analyze("id")).thenReturn(new NewsAnalysis("id", "标题", List.of(), "", List.of(), List.of(),
                "https://example.com/original", null, List.of("未配置模型")));
        mvc.perform(post("/api/news/id/analysis")).andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("id")).andExpect(jsonPath("$.dataGaps[0]").value("未配置模型"));
        when(service.analyze("missing")).thenThrow(new NoSuchElementException());
        mvc.perform(post("/api/news/missing/analysis")).andExpect(status().isNotFound());
    }
}
