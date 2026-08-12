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
        RuntimeConfigSectionCreator.class,
        RuntimeConfigDefaults.class,
        LlmProviderPolicy.class,
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
    void concurrentInitializationIsIdempotent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<? extends Future<?>> starts = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> executor.submit(store::initializeMissingSections))
                    .toList();

            for (Future<?> start : starts) {
                start.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.count()).isEqualTo(2);
        assertThat(store.readState().llmRevision()).isZero();
        assertThat(store.readState().policySourcesRevision()).isZero();
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
    void rejectsUntrustedLlmDestinationWithoutChangingTheStoredSecret() {
        store.initializeMissingSections();
        store.updateLlm(request("test-secret", "old-model"));
        LlmRuntimeConfig redirected = request(null, "new-model", "deepseek",
                "https://api.deepseek.com.attacker.example", "DEEPSEEK_API_KEY");

        assertThatThrownBy(() -> store.updateLlm(redirected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Base URL");

        assertThat(store.readLlm().apiKey()).isEqualTo("test-secret");
        assertThat(store.readLlm().model()).isEqualTo("old-model");
    }

    @Test
    void rejectsArbitraryApiKeyPropertyNames() {
        store.initializeMissingSections();

        assertThatThrownBy(() -> store.updateLlm(request(
                null,
                "deepseek-chat",
                "deepseek",
                "https://api.deepseek.com",
                "spring.datasource.password"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("环境变量");
    }

    @Test
    void changingProviderWithAPreservedDatabaseKeyRequiresANewKey() {
        store.initializeMissingSections();
        store.updateLlm(request("deepseek-secret", "deepseek-chat"));

        assertThatThrownBy(() -> store.updateLlm(request(
                null,
                "gpt-5.5",
                "openai",
                "https://api.openai.com/v1",
                "OPENAI_API_KEY"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重新填写 API Key");

        assertThat(store.readLlm().provider()).isEqualTo("deepseek");
        assertThat(store.readLlm().apiKey()).isEqualTo("deepseek-secret");
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
    void fullUpdateRejectsStaleSectionRevisionsInsteadOfOverwritingNewerData() {
        store.initializeMissingSections();
        RuntimeConfigState stale = store.readState();
        store.updateLlm(request(null, "newer-model"));

        assertThatThrownBy(() -> store.updateAll(
                request(null, "stale-model"),
                List.of(new PolicySourceConfig(
                        "过期政策源", "html", "https://example.test/stale", 70)),
                stale.llmRevision(),
                stale.policySourcesRevision()
        ))
                .isInstanceOf(RuntimeConfigRevisionConflictException.class);

        assertThat(store.readLlm().model()).isEqualTo("newer-model");
        assertThat(store.readPolicySources()).hasSize(10);
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
        return request(
                key,
                model,
                "deepseek",
                "https://api.deepseek.com",
                "DEEPSEEK_API_KEY"
        );
    }

    private LlmRuntimeConfig request(
            String key,
            String model,
            String provider,
            String baseUrl,
            String apiKeyEnv
    ) {
        return new LlmRuntimeConfig(
                provider,
                key,
                apiKeyEnv,
                model,
                baseUrl,
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
