# Database Runtime Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Nacos from the modular monolith and make the LLM and policy-source settings persist in the existing H2/PostgreSQL database and take effect on the next business operation without an application restart.

**Architecture:** Store each editable section in a separate `runtime_config_section` row containing typed JSON, revision, and database timestamp. A transactional `RuntimeConfigStore` owns persistence, `LlmSettingsProvider` resolves one immutable model snapshot per call, and `GovPolicyClient` reads one policy-source snapshot per fetch; no process cache or configuration broadcast is introduced.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Data JPA, H2/PostgreSQL-compatible SQL, Jackson, JUnit 5, AssertJ, Mockito, React 18, Zustand, Vitest, Vue 3, Docker Compose.

## Global Constraints

- Dynamic scope is limited to LLM settings and policy sources.
- Do not change short-term V4 scoring, point-in-time checks, 95% coverage gates, transparent contributions, T1/T2 outcome maturation, `FINAL_PENDING` database-time certification, or legacy `FINAL_READY` fail-closed behavior.
- Never read, print, migrate, or commit a real API key. Tests use literal fake values such as `test-secret` only.
- Every API response must set `apiKey` to `null`; logs and exception messages must not contain stored keys.
- Directly saved keys remain supported for compatibility, but environment-variable references remain the recommended deployment path.
- H2 and PostgreSQL must share the same `schema.sql`; do not add H2-only `CLOB` or PostgreSQL-only JSON column types.
- Active React and retained Vue settings pages must use database wording and must not display Nacos Data ID or Group.
- Historical specs and plans remain historical evidence; update only current README, architecture, and operations documentation.
- Preserve unrelated working-tree changes if any appear during execution.

---

## File Structure

### New backend files

- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionKey.java`: the two allowed database section keys.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionEntity.java`: JPA mapping for the current section payload and revision.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionRepository.java`: normal and pessimistically locked reads.
- `apps/api/src/main/java/com/aistock/research/configuration/StoredLlmConfig.java`: internal persisted LLM shape including the optional secret.
- `apps/api/src/main/java/com/aistock/research/configuration/StoredPolicySources.java`: wrapper used to persist a policy-source list as JSON.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigState.java`: internal typed snapshot of both rows and their revisions.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigDefaults.java`: server-side no-secret bootstrap defaults.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigStore.java`: transactional section reads, writes, initialization, and revision handling.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigInitializer.java`: startup runner that inserts only missing section rows.
- `apps/api/src/main/java/com/aistock/research/ai/LlmSettings.java`: immutable internal effective model settings.
- `apps/api/src/main/java/com/aistock/research/ai/LlmSettingsProvider.java`: database and environment-variable resolution shared by both model clients.
- `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigStoreTest.java`: persistence, isolation, secret, rollback, and concurrency coverage.
- `apps/api/src/test/java/com/aistock/research/ai/LlmSettingsProviderTest.java`: provider normalization, defaults, dynamic reads, and secret-source coverage.
- `apps/api/src/test/java/com/aistock/research/integration/gov/GovPolicyClientTest.java`: next-fetch policy-source reload coverage.
- `docs/runtime-config.md`: current database runtime-configuration operations guide.

### Existing backend files to modify

- `apps/api/src/main/resources/schema.sql`: create `runtime_config_section`.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigService.java`: replace Nacos HTTP/YAML behavior with the store.
- `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSnapshot.java`: database metadata instead of Data ID/Group.
- `apps/api/src/main/java/com/aistock/research/configuration/LlmRuntimeConfig.java`: URL and temperature validation.
- `apps/api/src/main/java/com/aistock/research/configuration/PolicySourceConfig.java`: URL validation.
- `apps/api/src/main/java/com/aistock/research/ai/LlmTrendAnalysisService.java`: consume `LlmSettingsProvider`.
- `apps/api/src/main/java/com/aistock/research/ai/LlmChatClient.java`: consume `LlmSettingsProvider`.
- `apps/api/src/main/java/com/aistock/research/integration/gov/GovPolicyClient.java`: consume dynamic policy sources.
- `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigServiceTest.java`: replace fake Nacos server tests.
- `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigControllerTest.java`: database metadata and stricter validation.
- `apps/api/src/test/java/com/aistock/research/ai/LlmTrendAnalysisServiceTest.java`: use the common settings provider.
- Constructor-based tests affected by the two model clients or `GovPolicyClient`: update only their construction helpers.

### Frontend files to modify

- `apps/web-react/src/types.ts`
- `apps/web-react/src/store/appStore.ts`
- `apps/web-react/src/pages/SettingsPage.tsx`
- `apps/web-react/src/pages/SettingsPage.test.tsx`
- `apps/web-react/src/lib/runtimeConfigDefaults.ts`
- `apps/web/src/types.ts`
- `apps/web/src/App.vue`

### Nacos/runtime files to remove or replace

- Remove `apps/api/src/main/resources/application-nacos.yml`.
- Remove `scripts/publish-nacos-config.sh`.
- Remove `infra/nacos/ai-stock-api.yml`.
- Remove `infra/nacos/ai-stock-api-local.yml`.
- Remove `infra/nacos/ai-stock-api-local-deepseek.yml`.
- Remove `infra/nacos/ai-stock-api-local-kimi.yml`.
- Remove `docs/nacos-config.md` after its current non-Nacos information is represented in `docs/runtime-config.md` or existing docs.
- Modify `apps/api/pom.xml`, `apps/api/src/main/java/com/aistock/research/AiStockResearchApplication.java`, `apps/api/src/main/resources/application.yml`, `.env.example`, `docker-compose.yml`, `README.md`, and `docs/architecture.md`.

---

### Task 1: Persist independent runtime-config sections

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionKey.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/StoredLlmConfig.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/StoredPolicySources.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigState.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigDefaults.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigStore.java`
- Create: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigInitializer.java`
- Create: `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigStoreTest.java`
- Modify: `apps/api/src/main/resources/schema.sql`
- Modify: `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`, `RuntimeConfigSectionRepository`, and existing `LlmRuntimeConfig` / `PolicySourceConfig` DTOs.
- Produces: `RuntimeConfigStore.readLlm()`, `updateLlm(...)`, `readPolicySources()`, `updatePolicySources(...)`, `readState()`, and `updateAll(...)` for later tasks.

- [ ] **Step 1: Write the schema and store tests first**

Add `RuntimeConfigStoreTest` as a `@DataJpaTest` with `@Import(RuntimeConfigStore.class, RuntimeConfigDefaults.class, TestObjectMapperConfig.class)` and non-transactional concurrency coverage. Include these concrete assertions:

```java
@Test
void initializesOnlyMissingSectionsWithoutOverwritingExistingLlm() {
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
```

Add a two-thread test that updates `LLM` concurrently and asserts the final row contains one complete JSON document, revision increased twice, and `POLICY_SOURCES` revision did not change.

Use these concrete test helpers so every stored payload is valid JSON and no real credential is involved:

```java
private RuntimeConfigSectionEntity section(RuntimeConfigSectionKey key, String json, long revision) {
    return new RuntimeConfigSectionEntity(key.name(), json, revision, Instant.parse("2026-08-13T00:00:00Z"));
}

private String existingLlmJson() throws JsonProcessingException {
    return objectMapper.writeValueAsString(new StoredLlmConfig(
            "deepseek", "test-secret", "DEEPSEEK_API_KEY", "existing-model",
            "https://api.deepseek.com", "json_object", false, null, 8192, null));
}

private LlmRuntimeConfig request(String key, String model) {
    return new LlmRuntimeConfig(
            "deepseek", key, "DEEPSEEK_API_KEY", model,
            "https://api.deepseek.com", "json_object", false,
            null, 8192, null, key != null, key == null ? "missing" : "database");
}

@Test
void concurrentLlmUpdatesCommitWholeDocumentsAndIncrementTwice() throws Exception {
    store.initializeMissingSections();
    long before = store.readState().llmRevision();
    long policyBefore = store.readState().policySourcesRevision();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
        Future<StoredLlmConfig> first = executor.submit(() -> store.updateLlm(request(null, "model-a")));
        Future<StoredLlmConfig> second = executor.submit(() -> store.updateLlm(request(null, "model-b")));
        assertThat(List.of(first.get().model(), second.get().model())).containsExactlyInAnyOrder("model-a", "model-b");
    } finally {
        executor.shutdownNow();
    }
    RuntimeConfigState state = store.readState();
    assertThat(state.llmRevision()).isEqualTo(before + 2);
    assertThat(state.policySourcesRevision()).isEqualTo(policyBefore);
    assertThat(state.llm().model()).isIn("model-a", "model-b");
}
```

Extend `SchemaCompatibilityTest` with:

```java
assertThat(schema).contains("CREATE TABLE IF NOT EXISTS runtime_config_section");
assertThat(schema).contains("payload_json TEXT NOT NULL");
assertThat(schema.toUpperCase()).doesNotContain(" CLOB");
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigStoreTest,SchemaCompatibilityTest test
```

Expected: compilation fails because the new persistence types and store do not exist. This is the correct RED reason.

- [ ] **Step 3: Add the portable table and minimal persistence types**

Append this exact table to `schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS runtime_config_section (
  section_key VARCHAR(64) PRIMARY KEY,
  payload_json TEXT NOT NULL,
  revision BIGINT NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

Define keys and stored records:

```java
public enum RuntimeConfigSectionKey { LLM, POLICY_SOURCES }

public record StoredLlmConfig(
        String provider, String apiKey, String apiKeyEnv, String model,
        String baseUrl, String responseFormat, boolean strictJsonSchema,
        String thinking, Integer maxCompletionTokens, Double temperature
) {}

public record StoredPolicySources(List<PolicySourceConfig> sources) {
    public StoredPolicySources {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

public record RuntimeConfigState(
        StoredLlmConfig llm,
        long llmRevision,
        List<PolicySourceConfig> policySources,
        long policySourcesRevision,
        Instant updatedAt
) {}
```

Map `RuntimeConfigSectionEntity.sectionKey` as the string primary key, `payloadJson` as `TEXT`, `revision` as `long`, and `updatedAt` as `Instant`. Repository signatures:

```java
public interface RuntimeConfigSectionRepository
        extends JpaRepository<RuntimeConfigSectionEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select section from RuntimeConfigSectionEntity section where section.sectionKey = :sectionKey")
    Optional<RuntimeConfigSectionEntity> findForUpdate(@Param("sectionKey") String sectionKey);
}
```

- [ ] **Step 4: Implement defaults and transactional store**

`RuntimeConfigDefaults` returns a no-secret DeepSeek configuration and the existing ten policy sources. `RuntimeConfigStore` must:

```java
@Transactional
public void initializeMissingSections() {
    insertIfMissing(LLM, write(defaults.llm()));
    insertIfMissing(POLICY_SOURCES, write(new StoredPolicySources(defaults.policySources())));
}

@Transactional(readOnly = true)
public StoredLlmConfig readLlm() {
    return parseLlm(required(LLM).getPayloadJson());
}

@Transactional
public StoredLlmConfig updateLlm(LlmRuntimeConfig request) {
    RuntimeConfigSectionEntity row = locked(LLM);
    StoredLlmConfig before = parseLlm(row.getPayloadJson());
    String preservedKey = hasText(request.apiKey()) ? request.apiKey().trim() : before.apiKey();
    StoredLlmConfig next = normalized(request, preservedKey);
    row.replacePayload(write(next), row.getRevision() + 1, Instant.now());
    return next;
}
```

`insertIfMissing` calls `repository.existsById(key.name())` and saves revision `0` with `Instant.now()` only when absent. `required` throws `IllegalStateException("运行配置栏目不存在: " + key)`; `parseLlm` and policy parsing wrap Jackson failures as `IllegalStateException("数据库运行配置损坏: " + key)` without including payload text.

Implement `RuntimeConfigInitializer` as an `ApplicationRunner` whose `run` method calls `RuntimeConfigStore.initializeMissingSections()` after SQL initialization. `updatePolicySources` follows the same locked-row pattern. `updateAll` locks keys in `LLM`, then `POLICY_SOURCES` order.

- [ ] **Step 5: Run focused persistence tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigStoreTest,SchemaCompatibilityTest test
```

Expected: both classes pass, no secret value appears in test output.

- [ ] **Step 6: Commit the persistence core**

```bash
git add apps/api/src/main/resources/schema.sql \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionKey.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionEntity.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSectionRepository.java \
  apps/api/src/main/java/com/aistock/research/configuration/StoredLlmConfig.java \
  apps/api/src/main/java/com/aistock/research/configuration/StoredPolicySources.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigState.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigDefaults.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigStore.java \
  apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigInitializer.java \
  apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigStoreTest.java \
  apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java
git commit -m "feat: persist runtime config sections"
```

---

### Task 2: Replace Nacos-backed configuration API with database transactions

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/LlmRuntimeConfig.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/PolicySourceConfig.java`
- Modify: `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigControllerTest.java`

**Interfaces:**
- Consumes: `RuntimeConfigStore` from Task 1.
- Produces: unchanged endpoint paths with `RuntimeConfigSnapshot(storage, llmRevision, policySourcesRevision, llm, policySources, updatedAt)`.

- [ ] **Step 1: Replace fake-Nacos tests with database-store behavior tests**

Rewrite `RuntimeConfigServiceTest` around a mocked `RuntimeConfigStore`. Prove that the service never exposes a stored key:

```java
@Test
void updateLlmReturnsDatabaseResultWithoutApiKey() {
    when(store.updateLlm(any())).thenReturn(new StoredLlmConfig(
            "deepseek", "test-secret", "DEEPSEEK_API_KEY", "new-model",
            "https://api.deepseek.com", "json_object", false, null, 8192, null));

    LlmRuntimeConfig response = service.updateLlmConfig(new LlmRuntimeConfig(
            "deepseek", null, "DEEPSEEK_API_KEY", "new-model",
            "https://api.deepseek.com", "json_object", false,
            null, 8192, null, false, "missing"));

    assertThat(response.model()).isEqualTo("new-model");
    assertThat(response.apiKey()).isNull();
    assertThat(response.apiKeyConfigured()).isTrue();
    assertThat(response.apiKeySource()).isEqualTo("database");
}
```

Update Controller assertions so `GET /api/runtime-config` returns `storage=database`, revisions, and no `dataId`/`group`. Add invalid URL and temperature cases:

```java
mockMvc.perform(put("/api/runtime-config/llm")
        .contentType(APPLICATION_JSON)
        .content(llmJson().replace("https://api.deepseek.com", "file:///tmp/model")))
    .andExpect(status().isBadRequest());
```

- [ ] **Step 2: Run service/controller tests and verify RED**

```bash
mvn -pl apps/api -Dtest=RuntimeConfigServiceTest,RuntimeConfigControllerTest test
```

Expected: compile/assertion failures because `RuntimeConfigService` still requires Nacos/Environment dependencies and snapshot fields are still `dataId`/`group`.

- [ ] **Step 3: Rewrite the service as a thin database facade**

Replace all HTTP, URI, form, YAML, Data ID, Group, and Namespace code with `RuntimeConfigStore` calls. Use this DTO shape:

```java
public record RuntimeConfigSnapshot(
        String storage,
        long llmRevision,
        long policySourcesRevision,
        LlmRuntimeConfig llm,
        List<PolicySourceConfig> policySources,
        Instant updatedAt
) {}
```

Add Bean Validation annotations:

```java
@Pattern(regexp = "https?://.+", message = "必须是 http 或 https 地址") String baseUrl
@DecimalMin("0.0") @DecimalMax("2.0") Double temperature
```

Apply the same URL pattern to `PolicySourceConfig.url`. Keep Controller paths unchanged.

- [ ] **Step 4: Run service/controller tests and verify GREEN**

```bash
mvn -pl apps/api -Dtest=RuntimeConfigStoreTest,RuntimeConfigServiceTest,RuntimeConfigControllerTest test
```

Expected: all tests pass and response JSON contains no secret.

- [ ] **Step 5: Commit the database API**

```bash
git add apps/api/src/main/java/com/aistock/research/configuration \
  apps/api/src/test/java/com/aistock/research/configuration
git commit -m "feat: serve runtime config from database"
```

---

### Task 3: Centralize effective LLM settings and make model switches immediate

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/ai/LlmSettings.java`
- Create: `apps/api/src/main/java/com/aistock/research/ai/LlmSettingsProvider.java`
- Create: `apps/api/src/test/java/com/aistock/research/ai/LlmSettingsProviderTest.java`
- Create: `apps/api/src/test/java/com/aistock/research/ai/LlmChatClientTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/ai/LlmTrendAnalysisService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/ai/LlmChatClient.java`
- Modify: `apps/api/src/test/java/com/aistock/research/ai/LlmTrendAnalysisServiceTest.java`
- Modify: tests constructing `LlmChatClient`, if the compiler identifies any.
- Delete after consumers migrate: `apps/api/src/main/java/com/aistock/research/config/LlmProperties.java`

**Interfaces:**
- Consumes: `RuntimeConfigStore.readLlm()` on every `current()` call and `Environment` only for named secret lookup.
- Produces: `LlmSettingsProvider.current()` returning one immutable `LlmSettings` snapshot for each model operation.

- [ ] **Step 1: Write provider tests first**

Cover the exact precedence and dynamic-read behavior:

```java
@Test
void readsTheNewDatabaseModelOnTheNextCall() {
    when(store.readLlm())
            .thenReturn(storedModel(null, "model-a"))
            .thenReturn(storedModel(null, "model-b"));

    assertThat(provider.current().model()).isEqualTo("model-a");
    assertThat(provider.current().model()).isEqualTo("model-b");
}

@Test
void directDatabaseKeyWinsButPreviewOnlyReportsItsSource() {
    when(store.readLlm()).thenReturn(storedModel("test-secret", "deepseek-chat"));

    LlmSettings settings = provider.current();

    assertThat(settings.apiKey()).isEqualTo("test-secret");
    assertThat(settings.apiKeySource()).isEqualTo("database");
}

private StoredLlmConfig storedModel(String key, String model) {
    return new StoredLlmConfig(
            "deepseek", key, "DEEPSEEK_API_KEY", model,
            "https://api.deepseek.com", "json_object", false, null, 8192, null);
}
```

Also retain provider canonicalization (`kimi` -> `moonshot`), Kimi Code, DeepSeek, OpenAI defaults, response-format normalization, max-token defaults, and missing-key behavior from `LlmTrendAnalysisServiceTest`. Add `LlmChatClientTest` proving two consecutive `currentConfig()` calls expose two model values supplied by consecutive provider snapshots.

- [ ] **Step 2: Run provider tests and verify RED**

```bash
mvn -pl apps/api -Dtest=LlmSettingsProviderTest,LlmChatClientTest,LlmTrendAnalysisServiceTest test
```

Expected: compilation fails because `LlmSettingsProvider` and `LlmSettings` do not exist.

- [ ] **Step 3: Implement the common provider**

Use this internal record:

```java
public record LlmSettings(
        String provider,
        String apiKey,
        String apiKeySource,
        String model,
        String baseUrl,
        String responseFormat,
        boolean strictJsonSchema,
        String thinking,
        Integer maxCompletionTokens,
        Double temperature
) {}
```

`current()` reads one `StoredLlmConfig`, canonicalizes provider, fills provider defaults only when stored fields are blank, and resolves keys in this order:

```text
StoredLlmConfig.apiKey
-> System.getenv(StoredLlmConfig.apiKeyEnv)
-> System.getenv(defaultKeyEnv(provider))
-> missing
```

Do not include key values in `toString`, errors, or logs.

- [ ] **Step 4: Inject the provider into both model clients**

Change constructors to accept `LlmSettingsProvider`. At the start of `currentConfig`, `analyze`, and `completeJson`, call `settingsProvider.current()` once. Delete duplicated `settings()`, provider-default, environment-property, legacy OpenAI property, and API-key-source methods from both classes. Keep HTTP request, response parsing, JSON repair, and prompt behavior unchanged.

- [ ] **Step 5: Run all LLM-focused tests and verify GREEN**

```bash
mvn -pl apps/api -Dtest='*Llm*Test,*AgentCommitteeAiServiceTest' test
```

Expected: all tests pass; no test output includes `test-secret`.

- [ ] **Step 6: Commit dynamic LLM resolution**

```bash
git add apps/api/src/main/java/com/aistock/research/ai \
  apps/api/src/main/java/com/aistock/research/config/LlmProperties.java \
  apps/api/src/test/java/com/aistock/research/ai \
  apps/api/src/test/java/com/aistock/research/committee
git commit -m "refactor: resolve llm settings from database"
```

---

### Task 4: Make policy-source updates effective on the next fetch

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/integration/gov/GovPolicyClient.java`
- Create: `apps/api/src/test/java/com/aistock/research/integration/gov/GovPolicyClientTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/config/LiveDataProperties.java`
- Modify: any test constructors reported by compilation after removing `policySources` from `LiveDataProperties`.

**Interfaces:**
- Consumes: `RuntimeConfigStore.readPolicySources()` once at the start of `fetchLatestPoliciesWithStatus`.
- Produces: each fetch cycle uses the latest committed list without changing other live-data URLs.

- [ ] **Step 1: Write a next-fetch dynamic source test**

Use `MockRestServiceServer` with a `RestClient.Builder` and a mocked store:

```java
@Test
void nextFetchUsesTheLatestDatabasePolicySources() {
    when(store.readPolicySources())
            .thenReturn(List.of(new PolicySourceConfig("来源甲", "html", "https://policy-a.example/list", 90)))
            .thenReturn(List.of(new PolicySourceConfig("来源乙", "html", "https://policy-b.example/list", 80)));
    server.expect(requestTo("https://policy-a.example/list"))
            .andRespond(withSuccess("<a href='/a'>政策方案甲正式发布通知</a>", TEXT_HTML));
    server.expect(requestTo("https://policy-b.example/list"))
            .andRespond(withSuccess("<a href='/b'>政策方案乙正式发布通知</a>", TEXT_HTML));

    assertThat(client.fetchLatestPoliciesWithStatus(8).items()).extracting(GovPolicyItem::source).containsOnly("来源甲");
    assertThat(client.fetchLatestPoliciesWithStatus(8).items()).extracting(GovPolicyItem::source).containsOnly("来源乙");
    server.verify();
}
```

Add an empty-list test returning no items and no network request.

- [ ] **Step 2: Run the test and verify RED**

```bash
mvn -pl apps/api -Dtest=GovPolicyClientTest test
```

Expected: constructor/behavior failure because the client still reads startup-bound `LiveDataProperties.policySources`.

- [ ] **Step 3: Read the database list once per fetch**

Inject `RuntimeConfigStore`, replace `policySources()` with a conversion from `PolicySourceConfig` to the internal fetch shape, and retain `LiveDataProperties.govPolicyUrl()` only if another non-dynamic fallback still needs it. An explicitly empty stored list means no sources; do not silently restore the China Government default.

Remove `policySources` and nested `PolicySourceProperties` from `LiveDataProperties` after all consumers compile, or retain the nested record only as a private client type if that produces the smallest focused diff.

- [ ] **Step 4: Run policy and affected integration tests**

```bash
mvn -pl apps/api -Dtest=GovPolicyClientTest,CompanyServiceTest,FilingEvidenceServiceTest,EastMoneyClientTest test
```

Expected: all selected existing tests and `GovPolicyClientTest` pass.

- [ ] **Step 5: Commit dynamic policy sources**

```bash
git add apps/api/src/main/java/com/aistock/research/integration/gov/GovPolicyClient.java \
  apps/api/src/main/java/com/aistock/research/config/LiveDataProperties.java \
  apps/api/src/test/java/com/aistock/research/integration/gov/GovPolicyClientTest.java \
  apps/api/src/test/java/com/aistock/research/company/CompanyServiceTest.java \
  apps/api/src/test/java/com/aistock/research/filing/FilingEvidenceServiceTest.java \
  apps/api/src/test/java/com/aistock/research/integration/eastmoney/EastMoneyClientTest.java
git commit -m "feat: reload policy sources from database"
```

---

### Task 5: Update React and Vue settings UI for database storage

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/store/appStore.ts`
- Modify: `apps/web-react/src/pages/SettingsPage.tsx`
- Modify: `apps/web-react/src/pages/SettingsPage.test.tsx`
- Modify: `apps/web-react/src/lib/runtimeConfigDefaults.ts`
- Modify: `apps/web/src/types.ts`
- Modify: `apps/web/src/App.vue`

**Interfaces:**
- Consumes: database-backed API response from Task 2.
- Produces: no Nacos wording; independent section save behavior remains unchanged.

- [ ] **Step 1: Change React tests first**

Replace button expectations with `保存配置`, then add:

```tsx
it('shows database metadata and no Nacos identifiers', () => {
  expect(section('大模型配置').textContent).toContain('数据库配置')
  expect(host.textContent).not.toContain('Nacos')
  expect(host.textContent).not.toContain('AI_STOCK')
})

it('reports that a saved model is immediately effective', async () => {
  await click(buttonIn('大模型配置', '保存配置'))
  expect(toast.success).toHaveBeenCalledWith('大模型配置已保存并生效')
})
```

Update `emptyRuntimeConfig()` test fixtures to use `storage`, `llmRevision`, and `policySourcesRevision`.

- [ ] **Step 2: Run React settings tests and verify RED**

```bash
cd apps/web-react
npm test -- SettingsPage.test.tsx
```

Expected: assertions fail because the page still renders Data ID/Group and “保存到 Nacos”.

- [ ] **Step 3: Update React types, store defaults, and page copy**

Use this TypeScript snapshot shape:

```ts
export interface RuntimeConfigSnapshot {
  storage: 'database'
  llmRevision: number
  policySourcesRevision: number
  llm: LlmRuntimeConfig
  policySources: PolicySourceConfig[]
  updatedAt: string
}
```

Render `数据库配置 · 模型修订 {form.llmRevision}` on the LLM card and the policy revision on the policy card. Change save labels, success copy, and API Key placeholder to database wording. After section save, update only that section form; do not fabricate a revision increase unless the section endpoint returns revision metadata. The next full load provides authoritative revisions.

- [ ] **Step 4: Update retained Vue types and copy**

Change its snapshot fields and remove `dataId / group`. Change “保存到 Nacos” to “保存配置”, “配置已发布到 Nacos” to “配置已保存并生效”, and the key placeholder to database wording. Preserve its current full-snapshot API compatibility.

- [ ] **Step 5: Run both frontend validations**

```bash
cd apps/web-react
npm test -- SettingsPage.test.tsx
npm run build

cd ../web
npm run build
```

Expected: React tests pass and both production builds complete without type errors.

- [ ] **Step 6: Commit frontend migration**

```bash
git add apps/web-react/src apps/web/src
git commit -m "feat: show database-backed runtime settings"
```

---

### Task 6: Remove Nacos dependencies, deployment resources, and current documentation

**Files:**
- Modify: `apps/api/pom.xml`
- Modify: `apps/api/src/main/java/com/aistock/research/AiStockResearchApplication.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Create: `docs/runtime-config.md`
- Delete: `apps/api/src/main/resources/application-nacos.yml`
- Delete: `scripts/publish-nacos-config.sh`
- Delete: `infra/nacos/ai-stock-api.yml`
- Delete: `infra/nacos/ai-stock-api-local.yml`
- Delete: `infra/nacos/ai-stock-api-local-deepseek.yml`
- Delete: `infra/nacos/ai-stock-api-local-kimi.yml`
- Delete: `docs/nacos-config.md`

**Interfaces:**
- Consumes: completed database configuration runtime.
- Produces: API startup and Compose operation with no Nacos process, client, profile, or current operational instruction.

- [ ] **Step 1: Capture the current runtime/dependency evidence**

Run the real build/deployment consumers before editing:

```bash
mvn -pl apps/api dependency:tree -Dincludes=com.alibaba.cloud
docker compose config
```

Expected before removal: Maven resolves both Alibaba Nacos starters and rendered Compose contains the Nacos variables/service. This is diagnostic evidence, not a source-text unit test.

- [ ] **Step 2: Remove Nacos runtime code and resources**

Remove both Alibaba dependencies from `apps/api/pom.xml`, remove `@EnableDiscoveryClient`, delete `application-nacos.yml`, and remove the Nacos default Profile and cloud block from `application.yml`.

Delete the Nacos service, environment variables, and volume from Compose. Keep the API H2 volume and database environment unchanged. Remove model/provider environment variables that are no longer configuration sources from `.env.example`; retain only supported API-key environment variables.

Before deleting `infra/nacos`, compare every short-term value against the effective `application.yml` and Java defaults. Do not copy dormant values that differ from the currently effective runtime. Specifically, do not change the active V4 financial-score threshold merely because the unused local Nacos file contains another value.

- [ ] **Step 3: Replace current operations documentation**

Write `docs/runtime-config.md` with:

```text
Storage: runtime_config_section in the configured H2/PostgreSQL database
Sections: LLM and POLICY_SOURCES
Save semantics: transaction commit, next operation sees the new revision
Key semantics: blank preserves; environment variables recommended; responses are masked
Diagnostics: GET /api/runtime-config and GET /api/ai/llm-config
```

Update README and architecture to describe the modular monolith and database runtime configuration. Delete `docs/nacos-config.md`. Do not rewrite old dated specs/plans.

- [ ] **Step 4: Run build/deployment consumers and repository scans**

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest test
mvn -pl apps/api dependency:tree -Dincludes=com.alibaba.cloud
docker compose config > /tmp/shares-test-compose.txt
rg -n -i "nacos" apps/api/src/main apps/web-react/src apps/web/src docker-compose.yml scripts infra README.md docs/architecture.md docs/runtime-config.md
```

Expected: schema tests pass; dependency output contains no Alibaba Nacos artifact; Compose renders; the final `rg` returns no current runtime reference. Historical `docs/superpowers` references are intentionally outside this scan.

- [ ] **Step 5: Commit Nacos removal**

```bash
git add apps/api/pom.xml apps/api/src/main apps/api/src/test \
  .env.example docker-compose.yml README.md docs/architecture.md docs/runtime-config.md \
  docs/nacos-config.md scripts/publish-nacos-config.sh infra/nacos
git commit -m "refactor: remove nacos from modular monolith"
```

---

### Task 7: Full regression, local deployment, persistence acceptance, and restoration

**Files:**
- Modify only if verification finds an in-scope defect: files already listed in Tasks 1-6.
- Runtime backup: an ignored timestamped backup of the H2 file or Docker volume, created without committing it.

**Interfaces:**
- Consumes: completed implementation and current local Docker deployment.
- Produces: test, build, Compose, live API, restart-persistence, and V4 non-regression evidence.

- [ ] **Step 1: Run the focused backend suite**

```bash
mvn -pl apps/api -Dtest='RuntimeConfig*Test,LlmSettingsProviderTest,LlmTrendAnalysisServiceTest,GovPolicyClientTest,SchemaCompatibilityTest' test
```

Expected: all focused tests pass with no warnings containing a key value.

- [ ] **Step 2: Run V4 safety-gate regression tests**

```bash
mvn -pl apps/api -Dtest='ShortTermServiceTest,ShortTermSupplyDemandScorerTest,ShortTermScheduledScanServiceTest,ShortTermScheduledSnapshotStoreTest,ShortTermOutcomeMaturationServiceTest' test
```

Expected: all selected V4 tests pass; `FINAL_PENDING` and legacy fail-closed assertions remain unchanged.

- [ ] **Step 3: Run full backend and frontend validation**

```bash
mvn -pl apps/api test

cd apps/web-react
npm test
npm run build

cd ../web
npm run build
```

Expected: complete Maven and React suites pass and both frontends build.

- [ ] **Step 4: Prepare a recoverable local database backup**

Record current container/volume targets with read-only inspection. Stop only `ai-stock-api`, copy the H2 database file to an ignored timestamped backup, then restart only if implementation deployment is not immediately following. Never stop or modify unrelated project containers.

Example target naming:

```text
tmp/backups/aistock-before-runtime-config-20260813THHMMSS.mv.db
```

Verify the backup exists and has non-zero size before rebuilding.

- [ ] **Step 5: Build and restart only this project's API/Web**

```bash
mvn -pl apps/api -DskipTests package
docker compose build api web
docker compose up -d --no-deps api web
docker ps --filter name=ai-stock-api --filter name=ai-stock-web
```

Expected: both containers become healthy/up; no Nacos container is created.

- [ ] **Step 6: Exercise database save and immediate model visibility**

Read `GET /api/runtime-config/llm` and retain only non-secret fields locally. PUT a temporary model value with `apiKey: null`, then assert:

```text
PUT /api/runtime-config/llm -> HTTP 200
GET /api/runtime-config/llm -> temporary model
GET /api/ai/llm-config -> temporary model
```

Do not call the external model and do not print any key.

- [ ] **Step 7: Prove restart persistence and restore the original model**

Restart only `ai-stock-api`, wait for health, and verify the temporary model remains. PUT the original model configuration back, then verify both configuration endpoints show the original model.

- [ ] **Step 8: Exercise and restore policy sources**

Read the current list, make one reversible non-secret change such as adjusting the first source weight within `1..100`, verify the PUT/GET result, restart API if needed to prove persistence, then restore the exact original list. Do not trigger external policy crawling as part of this mutation check.

- [ ] **Step 9: Final repository and runtime audit**

```bash
git status --short
git diff --check
curl -fsS http://127.0.0.1:19080/actuator/health
curl -fsS http://127.0.0.1:19080/api/runtime-config
curl -fsS http://127.0.0.1:19080/api/ai/llm-config
```

Redact or omit secret-bearing fields from reported output. Confirm port 8848 remains unavailable and the configuration endpoints succeed.

- [ ] **Step 10: Commit only verification-driven in-scope fixes, if any**

If Tasks 1-6 commits already contain all changes, do not create an empty commit. If verification required a fix, rerun the smallest failing test, then the full affected suite. Stage the exact files shown by `git diff --name-only` after confirming every path belongs to this runtime-configuration scope, then commit:

```bash
git commit -m "fix: complete database runtime config migration"
```

---

## Completion Evidence

The final handoff must report:

- Exact commits created after the design checkpoint.
- Focused and full test/build commands with observed pass counts or success output.
- Live configuration PUT/GET result, restart-persistence result, and restoration result without secrets.
- Current API/Web container status and confirmation that Nacos was not started.
- H2 backup path and recovery instructions.
- Any historical Nacos mentions intentionally retained only under dated `docs/superpowers` artifacts.
- Whether work was pushed; do not imply a push unless one actually succeeds.
