package com.aistock.research.backtest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BacktestControllerTest {

    @Test
    void exposesTheIndependentOvernightBacktestContract() throws Exception {
        BacktestService service = mock(BacktestService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BacktestController(service)).build();
        OvernightBacktestRuleSet ruleSet = new OvernightBacktestRuleSet(
                900,
                new BigDecimal("2.5"),
                new BigDecimal("4.5"),
                new BigDecimal("3.5"),
                2,
                new BigDecimal("0.03"),
                new BigDecimal("0.05"),
                new BigDecimal("0.05"),
                new BigDecimal("9.80")
        );
        OvernightBacktestSummary summary = new OvernightBacktestSummary(
                1,
                1,
                new BigDecimal("100.00"),
                new BigDecimal("2.10"),
                new BigDecimal("2.10"),
                new BigDecimal("3.00"),
                new BigDecimal("-1.00"),
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.parse("2026-07-20"),
                LocalDate.parse("2026-07-20"),
                "样本偏少，只能观察"
        );
        when(service.overnightBacktest(
                "600795", 900,
                new BigDecimal("2.5"), new BigDecimal("4.5"), new BigDecimal("3.5"), 2,
                new BigDecimal("0.03"), new BigDecimal("0.05"),
                new BigDecimal("0.05"), new BigDecimal("9.80")
        )).thenReturn(new OvernightBacktestReport(
                "短线隔夜 T+1/T+2 验证",
                List.of("不使用未来数据"),
                ruleSet,
                List.of("600795"),
                summary,
                List.of(),
                Instant.parse("2026-07-23T07:00:00Z")
        ));

        mockMvc.perform(get("/api/backtests/overnight")
                        .param("symbols", "600795")
                        .param("lookbackDays", "900")
                        .param("firstTargetPercent", "2.5")
                        .param("secondTargetPercent", "4.5")
                        .param("hardStopPercent", "3.5")
                        .param("maxHoldingTradingDays", "2")
                        .param("commissionPercent", "0.03")
                        .param("stampDutyPercent", "0.05")
                        .param("slippagePercent", "0.05")
                        .param("limitMovePercent", "9.80"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("短线隔夜 T+1/T+2 验证"))
                .andExpect(jsonPath("$.ruleSet.maxHoldingTradingDays").value(2))
                .andExpect(jsonPath("$.summary.sampleCount").value(1))
                .andExpect(jsonPath("$.summary.positiveRatePercent").value(100.0))
                .andExpect(jsonPath("$.trades").isArray());

        verify(service).overnightBacktest(
                "600795", 900,
                new BigDecimal("2.5"), new BigDecimal("4.5"), new BigDecimal("3.5"), 2,
                new BigDecimal("0.03"), new BigDecimal("0.05"),
                new BigDecimal("0.05"), new BigDecimal("9.80")
        );
    }
}
