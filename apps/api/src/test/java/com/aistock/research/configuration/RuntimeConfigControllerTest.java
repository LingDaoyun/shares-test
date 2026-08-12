package com.aistock.research.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RuntimeConfigControllerTest {

    private final RuntimeConfigService service = mock(RuntimeConfigService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = standaloneSetup(new RuntimeConfigController(service))
                .setValidator(validator)
                .build();
    }

    @Test
    void readsDatabaseSnapshotWithoutLegacyNacosMetadataOrApiKey() throws Exception {
        when(service.currentConfig()).thenReturn(new RuntimeConfigSnapshot(
                "database",
                7,
                3,
                llmConfig(),
                List.of(new PolicySourceConfig(
                        "中国政府网", "json", "https://gov.cn", 100)),
                Instant.parse("2026-08-13T00:00:00Z")
        ));

        mockMvc.perform(get("/api/runtime-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storage").value("database"))
                .andExpect(jsonPath("$.llmRevision").value(7))
                .andExpect(jsonPath("$.policySourcesRevision").value(3))
                .andExpect(jsonPath("$.llm.apiKey").value(nullValue()))
                .andExpect(jsonPath("$.dataId").doesNotExist())
                .andExpect(jsonPath("$.group").doesNotExist());
    }

    @Test
    void readsAndUpdatesLlmSectionWithoutReturningApiKey() throws Exception {
        LlmRuntimeConfig config = llmConfig();
        when(service.currentLlmConfig()).thenReturn(config);
        when(service.updateLlmConfig(any())).thenReturn(config);

        mockMvc.perform(get("/api/runtime-config/llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.apiKey").value(nullValue()));

        mockMvc.perform(put("/api/runtime-config/llm")
                        .contentType(APPLICATION_JSON)
                        .content(llmJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.apiKey").value(nullValue()));
        verify(service).updateLlmConfig(any());
    }

    @Test
    void rejectsBlankModelInvalidUrlAndOutOfRangeTemperature() throws Exception {
        mockMvc.perform(put("/api/runtime-config/llm")
                        .contentType(APPLICATION_JSON)
                        .content(llmJson().replace("deepseek-v4-pro", "")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/runtime-config/llm")
                        .contentType(APPLICATION_JSON)
                        .content(llmJson().replace(
                                "https://api.deepseek.com", "file:///tmp/model")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/runtime-config/llm")
                        .contentType(APPLICATION_JSON)
                        .content(llmJson().replace(
                                "\"temperature\": null", "\"temperature\": 2.1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readsAndUpdatesPolicySourceSection() throws Exception {
        List<PolicySourceConfig> sources = List.of(
                new PolicySourceConfig("中国政府网", "json", "https://gov.cn", 100));
        when(service.currentPolicySources()).thenReturn(sources);
        when(service.updatePolicySources(any())).thenReturn(sources);

        mockMvc.perform(get("/api/runtime-config/policy-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("中国政府网"));

        mockMvc.perform(put("/api/runtime-config/policy-sources")
                        .contentType(APPLICATION_JSON)
                        .content(policyJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].weight").value(100));
        verify(service).updatePolicySources(any());
    }

    @Test
    void rejectsInvalidPolicySourceFieldsAndScheme() throws Exception {
        mockMvc.perform(put("/api/runtime-config/policy-sources")
                        .contentType(APPLICATION_JSON)
                        .content("[{\"name\":\"\",\"type\":\"html\",\"url\":\"\",\"weight\":0}]"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/runtime-config/policy-sources")
                        .contentType(APPLICATION_JSON)
                        .content(policyJson().replace("https://gov.cn", "ftp://gov.cn")))
                .andExpect(status().isBadRequest());
    }

    private LlmRuntimeConfig llmConfig() {
        return new LlmRuntimeConfig(
                "deepseek", null, "DEEPSEEK_API_KEY", "deepseek-v4-pro",
                "https://api.deepseek.com", "json_object", false,
                null, 8192, null, true, "database");
    }

    private String llmJson() {
        return """
                {
                  "provider": "deepseek",
                  "apiKey": null,
                  "apiKeyEnv": "DEEPSEEK_API_KEY",
                  "model": "deepseek-v4-pro",
                  "baseUrl": "https://api.deepseek.com",
                  "responseFormat": "json_object",
                  "strictJsonSchema": false,
                  "thinking": null,
                  "maxCompletionTokens": 8192,
                  "temperature": null,
                  "apiKeyConfigured": true,
                  "apiKeySource": "database"
                }
                """;
    }

    private String policyJson() {
        return "[{\"name\":\"中国政府网\",\"type\":\"json\",\"url\":\"https://gov.cn\",\"weight\":100}]";
    }
}
