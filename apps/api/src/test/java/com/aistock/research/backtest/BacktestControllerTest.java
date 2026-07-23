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
                new BigDecimal("9.80"),
                new BigDecimal("1.35"),
                new BigDecimal("6.50"),
                new BigDecimal("2.00")
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
                new BigDecimal("0.05"), new BigDecimal("9.80"),
                new BigDecimal("1.35"), new BigDecimal("6.50"), new BigDecimal("2.00")
        )).thenReturn(new OvernightBacktestReport(
                "短线 T+1/T+2 技术信号历史验证",
                List.of("生产同源 K 线技术信号"),
                List.of("财报质量门禁", "市场情绪门禁", "尾盘分钟确认门禁", "实时行情新鲜度门禁"),
                List.of("不使用未来数据"),
                ruleSet,
                List.of("600795"),
                "OK",
                "技术信号历史样本已生成",
                summary,
                List.of(new OvernightBacktestSymbolResult(
                        "600795", "OK", 900, 1, List.of()
                )),
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
                        .param("limitMovePercent", "9.80")
                        .param("minVolumeRatio", "1.35")
                        .param("maxDistanceToMa20Percent", "6.50")
                        .param("trailingDrawdownPercent", "2.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("短线 T+1/T+2 技术信号历史验证"))
                .andExpect(jsonPath("$.validationScope[0]").value("生产同源 K 线技术信号"))
                .andExpect(jsonPath("$.unreplayedGates").isArray())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.ruleSet.maxHoldingTradingDays").value(2))
                .andExpect(jsonPath("$.ruleSet.minVolumeRatio").value(1.35))
                .andExpect(jsonPath("$.ruleSet.maxDistanceToMa20Percent").value(6.5))
                .andExpect(jsonPath("$.ruleSet.trailingDrawdownPercent").value(2.0))
                .andExpect(jsonPath("$.summary.sampleCount").value(1))
                .andExpect(jsonPath("$.summary.positiveRatePercent").value(100.0))
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.trades").isArray());

        verify(service).overnightBacktest(
                "600795", 900,
                new BigDecimal("2.5"), new BigDecimal("4.5"), new BigDecimal("3.5"), 2,
                new BigDecimal("0.03"), new BigDecimal("0.05"),
                new BigDecimal("0.05"), new BigDecimal("9.80"),
                new BigDecimal("1.35"), new BigDecimal("6.50"), new BigDecimal("2.00")
        );
    }
}
