package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class V2SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsSampleSignalAndRecordsItInLedger() throws Exception {
        mockMvc.perform(get("/api/v2/signals/sample")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("strategyCode", "CYCLE_REVERSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("002714"))
                .andExpect(jsonPath("$.strategyCode").value("CYCLE_REVERSAL"))
                .andExpect(jsonPath("$.strategyVersion").value("cycle-reversal-v2.0.0"))
                .andExpect(jsonPath("$.action").value("NEXT_WATCH"))
                .andExpect(jsonPath("$.rankScore").value(50.0))
                .andExpect(jsonPath("$.dataConfidence").value(40.0))
                .andExpect(jsonPath("$.ledgerId").isNotEmpty());
    }
}
