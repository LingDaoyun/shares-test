package com.aistock.research.market.context;

import com.aistock.research.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LongTermCandidateContextControllerTest {

    @Test
    void exposesLazyCandidateContextEndpoint() throws Exception {
        LongTermCandidateContextService service = mock(LongTermCandidateContextService.class);
        when(service.load("600795", "电力")).thenReturn(new LongTermCandidateContext(
                "600795",
                "国电电力",
                "沪A",
                "电力",
                new LongTermIndustryContext(
                        "电力",
                        "STANDARD",
                        "普通企业模型",
                        "WEAK_CYCLE",
                        "弱周期",
                        List.of(),
                        List.of()
                ),
                new LongTermPolicyEvidence(List.of(), List.of()),
                new LongTermCycleSnapshot(
                        "STABLE",
                        "弱周期稳定",
                        "RECOVERY",
                        "价格修复",
                        75,
                        false,
                        List.of(),
                        List.of(),
                        List.of()
                ),
                Instant.parse("2026-07-30T04:00:00Z"),
                List.of()
        ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new LongTermCandidateContextController(service))
                .build();

        mvc.perform(get("/api/market-scan/candidates/600795/context").param("industry", "电力"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("600795"))
                .andExpect(jsonPath("$.industry").value("电力"))
                .andExpect(jsonPath("$.cycleContext.priceStage").value("RECOVERY"));
    }

    @Test
    void rejectsInvalidStockSymbolsAsBadRequests() throws Exception {
        LongTermCandidateContextService service = mock(LongTermCandidateContextService.class);
        when(service.load("123", null)).thenThrow(new IllegalArgumentException("股票代码格式不正确"));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new LongTermCandidateContextController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/market-scan/candidates/123/context"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("股票代码格式不正确"));
    }
}
