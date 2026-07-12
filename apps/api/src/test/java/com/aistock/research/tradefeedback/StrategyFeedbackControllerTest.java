package com.aistock.research.tradefeedback;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StrategyFeedbackControllerTest {

    @Test
    void exposesStableStrategyFeedbackSummaries() throws Exception {
        StrategyFeedbackService service = mock(StrategyFeedbackService.class);
        when(service.summaries()).thenReturn(List.of(summary()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StrategyFeedbackController(service)).build();

        mockMvc.perform(get("/api/strategy-feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceModule").value("MISPRICING"))
                .andExpect(jsonPath("$[0].ruleVersion").value("mispricing-v2"))
                .andExpect(jsonPath("$[0].positiveRate").value(0.75))
                .andExpect(jsonPath("$[0].reliabilityAdjustment").value(3.50));
    }

    private StrategyFeedbackSummary summary() {
        return new StrategyFeedbackSummary(
                "MISPRICING",
                "mispricing-v2",
                "T20",
                20,
                15,
                new BigDecimal("0.7500"),
                new BigDecimal("4.0000"),
                new BigDecimal("3.0000"),
                new BigDecimal("8.0000"),
                new BigDecimal("-4.0000"),
                new BigDecimal("1.0000"),
                12,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                true,
                true,
                new BigDecimal("3.50")
        );
    }
}
