package com.aistock.research.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.aistock.research.configuration.RuntimeConfigSectionKey.LLM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({
        RuntimeConfigStore.class,
        RuntimeConfigDefaults.class,
        RuntimeConfigStoreTest.ObjectMapperTestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RuntimeConfigStoreTest {

    private final RuntimeConfigStore store;
    private final RuntimeConfigSectionRepository repository;
    private final ObjectMapper objectMapper;

    @Autowired
    RuntimeConfigStoreTest(
            RuntimeConfigStore store,
            RuntimeConfigSectionRepository repository,
            ObjectMapper objectMapper
    ) {
        this.store = store;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @AfterEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void initializesOnlyMissingSectionsWithoutOverwritingExistingLlm() throws Exception {
        repository.saveAndFlush(section(LLM, existingLlmJson(), 7));

        store.initializeMissingSections();

        assertThat(store.readLlm().model()).isEqualTo("existing-model");
        assertThat(repository.findById("LLM").orElseThrow().getRevision()).isEqualTo(7);
        assertThat(store.readPolicySources()).hasSize(10);
    }

    @Test
    void blankKeyPreservesSecretAndUpdatesOnlyLlmRevision() {
        store.initializeMissingSections();
        store.updateLlm(request("test-secret", "old-model"));
        long policyRevision = store.readState().policySourcesRevision();

        StoredLlmConfig updated = store.updateLlm(request(null, "new-model"));

        assertThat(updated.apiKey()).isEqualTo("test-secret");
        assertThat(updated.model()).isEqualTo("new-model");
        assertThat(store.readState().policySourcesRevision()).isEqualTo(policyRevision);
    }

    @Test
    void invalidPayloadRollsBackWithoutIncrementingRevision() {
        store.initializeMissingSections();
        long before = store.readState().llmRevision();

        assertThatThrownBy(() -> store.updateLlm(request(null, " ")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(store.readState().llmRevision()).isEqualTo(before);
    }

    @Test
    void policyUpdateDoesNotChangeLlmRevision() {
        store.initializeMissingSections();
        long llmRevision = store.readState().llmRevision();

        List<PolicySourceConfig> updated = store.updatePolicySources(List.of(
                new PolicySourceConfig("测试来源", "json", "https://example.test/policy.json", 88)
        ));

        assertThat(updated).hasSize(1);
        assertThat(store.readState().llmRevision()).isEqualTo(llmRevision);
        assertThat(store.readState().policySourcesRevision()).isEqualTo(1);
    }

    @Test
    void concurrentLlmUpdatesCommitWholeDocumentsAndIncrementTwice() throws Exception {
        store.initializeMissingSections();
        long before = store.readState().llmRevision();
        long policyBefore = store.readState().policySourcesRevision();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredLlmConfig> first = executor.submit(
                    () -> store.updateLlm(request(null, "model-a")));
            Future<StoredLlmConfig> second = executor.submit(
                    () -> store.updateLlm(request(null, "model-b")));

            assertThat(List.of(first.get().model(), second.get().model()))
                    .containsExactlyInAnyOrder("model-a", "model-b");
        } finally {
            executor.shutdownNow();
        }

        RuntimeConfigState state = store.readState();
        assertThat(state.llmRevision()).isEqualTo(before + 2);
        assertThat(state.policySourcesRevision()).isEqualTo(policyBefore);
        assertThat(state.llm().model()).isIn("model-a", "model-b");
    }

    private RuntimeConfigSectionEntity section(
            RuntimeConfigSectionKey key,
            String json,
            long revision
    ) {
        return new RuntimeConfigSectionEntity(
                key.name(),
                json,
                revision,
                Instant.parse("2026-08-13T00:00:00Z")
        );
    }

    private String existingLlmJson() throws JsonProcessingException {
        return objectMapper.writeValueAsString(new StoredLlmConfig(
                "deepseek",
                "test-secret",
                "DEEPSEEK_API_KEY",
                "existing-model",
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null
        ));
    }

    private LlmRuntimeConfig request(String key, String model) {
        return new LlmRuntimeConfig(
                "deepseek",
                key,
                "DEEPSEEK_API_KEY",
                model,
                "https://api.deepseek.com",
                "json_object",
                false,
                null,
                8192,
                null,
                key != null,
                key == null ? "missing" : "database"
        );
    }

    @TestConfiguration
    static class ObjectMapperTestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
