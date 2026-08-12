package com.aistock.research.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmChatClientTest {

    @Test
    void consecutiveConfigReadsUseConsecutiveDatabaseSnapshots() {
        LlmSettingsProvider provider = mock(LlmSettingsProvider.class);
        when(provider.current())
                .thenReturn(settings("model-a"))
                .thenReturn(settings("model-b"));
        LlmChatClient client = new LlmChatClient(new ObjectMapper(), provider);

        assertThat(client.currentConfig().model()).isEqualTo("model-a");
        assertThat(client.currentConfig().model()).isEqualTo("model-b");
    }

    private LlmSettings settings(String model) {
        return new LlmSettings(
                "deepseek",
                null,
                "missing",
                model,
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null
        );
    }
}
