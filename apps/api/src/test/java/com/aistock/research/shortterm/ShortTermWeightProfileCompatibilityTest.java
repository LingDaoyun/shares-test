package com.aistock.research.shortterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortTermWeightProfileCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsTheLegacyWeightProfileShapeFromStoredSnapshots() throws Exception {
        String legacyJson = """
                {
                  "preliminaryValuation": 0.10,
                  "preliminaryLiquidity": 0.30,
                  "preliminaryNonChase": 0.25,
                  "preliminaryHeat": 0.35,
                  "finalTechnical": 0.40,
                  "finalVolume": 0.20,
                  "finalHeat": 0.15,
                  "finalFinancial": 0.20,
                  "finalValuation": 0.05
                }
                """;

        ShortTermWeightProfile result = objectMapper.readValue(legacyJson, ShortTermWeightProfile.class);

        assertThat(result.modelVersion()).isEqualTo("legacy-short-term-v1");
        assertThat(result.finalGoldenCross()).isEqualByComparingTo("0.40");
        assertThat(result.finalVolume()).isEqualByComparingTo("0.20");
        assertThat(result.finalTurnover()).isEqualByComparingTo("0.15");
        assertThat(result.finalCloseStrength()).isEqualByComparingTo("0.25");
        assertThat(result.finalTotal()).isEqualByComparingTo("1.00");
    }
}
