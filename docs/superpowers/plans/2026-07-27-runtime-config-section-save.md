# Runtime Config Section Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the LLM and policy-source settings sections independently reloadable, resettable, and savable without overwriting unrelated Nacos configuration.

**Architecture:** Add dedicated Spring MVC section endpoints backed by strict read-merge-publish operations on the existing Nacos YAML. Add typed React API functions and centralized defaults, then refactor `SettingsPage` so each card owns independent loading/saving actions while the Zustand form remains the shared page state.

**Tech Stack:** Java 17, Spring Boot 3.3, Jakarta Validation, SnakeYAML, JUnit 5, React 18, TypeScript, Zustand, Axios, Vitest, jsdom.

## Global Constraints

- Keep existing `GET /api/runtime-config` and `PUT /api/runtime-config` compatible.
- `PUT /api/runtime-config/llm` may only change `research.ai.llm`.
- `PUT /api/runtime-config/policy-sources` may only change `research.live-data.policy-sources`.
- Blank API Key means preserve the existing key; no endpoint returns the key plaintext.
- A missing Nacos document may be created from effective defaults, but transport/server/parser failures must not publish a fallback document.
- Reset is local-only until the user explicitly saves that section.
- Do not modify `ai-stock-api-local.yml` or the short-term schedule.

---

### Task 1: Backend section-level Nacos operations

**Files:**
- Create: `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigServiceTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigService.java`

**Interfaces:**
- Produces: `LlmRuntimeConfig currentLlmConfig()`
- Produces: `List<PolicySourceConfig> currentPolicySources()`
- Produces: `LlmRuntimeConfig updateLlmConfig(LlmRuntimeConfig request)`
- Produces: `List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request)`

- [ ] **Step 1: Write failing service tests**

Use a JDK `HttpServer` on an ephemeral port to act as Nacos. Capture the decoded `content` form parameter and assert exact YAML ownership:

```java
@Test
void updatesOnlyLlmAndPreservesPolicySourcesAndUnknownNodes() {
    nacos.respondWith(existingYaml());

    service.updateLlmConfig(llmRequest(null));

    Map<String, Object> published = nacos.publishedYaml();
    assertThat(path(published, "research", "future-module", "enabled")).isEqualTo(true);
    assertThat(path(published, "research", "live-data", "policy-sources")).isEqualTo(existingSources());
    assertThat(path(published, "research", "ai", "llm", "model")).isEqualTo("deepseek-v4-pro");
    assertThat(path(published, "research", "ai", "llm", "api-key")).isEqualTo("existing-secret");
}

@Test
void updatesOnlyPolicySourcesAndPreservesLlmAndUnknownNodes() {
    nacos.respondWith(existingYaml());

    service.updatePolicySources(List.of(new PolicySourceConfig("中国政府网", "json", "https://gov.example", 100)));

    Map<String, Object> published = nacos.publishedYaml();
    assertThat(path(published, "research", "ai", "llm", "api-key")).isEqualTo("existing-secret");
    assertThat(path(published, "research", "future-module", "enabled")).isEqualTo(true);
}

@Test
void doesNotPublishWhenNacosReadFails() {
    nacos.respondToGet(500, "unavailable");
    assertThatThrownBy(() -> service.updateLlmConfig(llmRequest(null)))
            .hasMessageContaining("Nacos 请求失败 HTTP 500");
    assertThat(nacos.publishCount()).isZero();
}
```

Also cover a 404 response creating a new document, a nonblank replacement key, and a response whose `apiKey()` is always null.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigServiceTest test
```

Expected: compilation failure because the four section methods do not exist.

- [ ] **Step 3: Implement strict read-merge-publish methods**

Refactor remote reads into a strict update path:

```java
public LlmRuntimeConfig updateLlmConfig(LlmRuntimeConfig request) {
    Map<String, Object> root = readRemoteConfigForUpdate();
    Map<String, Object> llm = map(map(map(root, "research"), "ai"), "llm");
    boolean previouslyConfigured = currentConfig().llm().apiKeyConfigured();
    updateLlmConfig(llm, request);
    publishRemoteConfig(yaml.dump(root));
    return sanitizedLlm(request, previouslyConfigured || hasText(request.apiKey()));
}

public List<PolicySourceConfig> updatePolicySources(List<PolicySourceConfig> request) {
    Map<String, Object> root = readRemoteConfigForUpdate();
    Map<String, Object> liveData = map(map(root, "research"), "live-data");
    liveData.put("policy-sources", request.stream().map(this::policySourceMap).toList());
    publishRemoteConfig(yaml.dump(root));
    return List.copyOf(request);
}
```

The strict reader treats HTTP 404 as an absent document and returns `fallbackConfigMap()`. Every other non-2xx response, malformed YAML, I/O error, or interruption throws and does not call publish. Keep the existing tolerant reader only for the legacy full update if compatibility requires it.

- [ ] **Step 4: Run service tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigServiceTest test
```

Expected: all `RuntimeConfigServiceTest` tests pass.

---

### Task 2: Backend section endpoints and validation

**Files:**
- Create: `apps/api/src/test/java/com/aistock/research/configuration/RuntimeConfigControllerTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/configuration/RuntimeConfigController.java`

**Interfaces:**
- Consumes: the four `RuntimeConfigService` methods from Task 1.
- Produces: `GET/PUT /api/runtime-config/llm`.
- Produces: `GET/PUT /api/runtime-config/policy-sources`.

- [ ] **Step 1: Write failing MVC tests**

Create a standalone `MockMvc` controller test with a mocked service:

```java
mockMvc.perform(put("/api/runtime-config/llm")
        .contentType(APPLICATION_JSON)
        .content(llmJson("deepseek", "deepseek-v4-pro")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").value(nullValue()));

mockMvc.perform(put("/api/runtime-config/policy-sources")
        .contentType(APPLICATION_JSON)
        .content("[{\"name\":\"中国政府网\",\"type\":\"json\",\"url\":\"https://gov.cn\",\"weight\":100}]"))
        .andExpect(status().isOk());

mockMvc.perform(put("/api/runtime-config/policy-sources")
        .contentType(APPLICATION_JSON)
        .content("[{\"name\":\"\",\"type\":\"html\",\"url\":\"\",\"weight\":0}]"))
        .andExpect(status().isBadRequest());
```

- [ ] **Step 2: Run controller tests and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigControllerTest test
```

Expected: 404 for the new paths.

- [ ] **Step 3: Add dedicated controller methods**

Add methods with exact validation ownership:

```java
@GetMapping("/llm")
public LlmRuntimeConfig currentLlmConfig() { return runtimeConfigService.currentLlmConfig(); }

@PutMapping("/llm")
public LlmRuntimeConfig updateLlmConfig(@Valid @RequestBody LlmRuntimeConfig request) {
    return runtimeConfigService.updateLlmConfig(request);
}

@GetMapping("/policy-sources")
public List<PolicySourceConfig> currentPolicySources() { return runtimeConfigService.currentPolicySources(); }

@PutMapping("/policy-sources")
public List<PolicySourceConfig> updatePolicySources(
        @Valid @RequestBody List<@Valid PolicySourceConfig> request) {
    return runtimeConfigService.updatePolicySources(request);
}
```

- [ ] **Step 4: Run controller and service tests**

Run:

```bash
mvn -pl apps/api -Dtest=RuntimeConfigControllerTest,RuntimeConfigServiceTest test
```

Expected: all tests pass.

---

### Task 3: React API contracts and defaults

**Files:**
- Create: `apps/web-react/src/lib/runtimeConfigDefaults.ts`
- Create: `apps/web-react/src/lib/runtimeConfigDefaults.test.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/store/appStore.ts`

**Interfaces:**
- Produces: `fetchLlmRuntimeConfig`, `updateLlmRuntimeConfig`.
- Produces: `fetchPolicySources`, `updatePolicySources`.
- Produces: `defaultLlmRuntimeConfig()` and `defaultPolicySources()` returning fresh copies.

- [ ] **Step 1: Write failing defaults tests**

```ts
it('returns independent copies of the platform defaults', () => {
  const first = defaultPolicySources()
  first[0].name = 'changed'
  expect(defaultPolicySources()).toHaveLength(10)
  expect(defaultPolicySources()[0].name).toBe('中国政府网')
  expect(defaultLlmRuntimeConfig().provider).toBe('deepseek')
})
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd apps/web-react && npm test -- --run src/lib/runtimeConfigDefaults.test.ts
```

Expected: module-not-found failure.

- [ ] **Step 3: Add typed section APIs and centralized defaults**

Add Axios functions:

```ts
export function fetchLlmRuntimeConfig() {
  return http.get<LlmRuntimeConfig>('/runtime-config/llm').then((res) => res.data)
}
export function updateLlmRuntimeConfig(request: LlmRuntimeConfig) {
  return http.put<LlmRuntimeConfig>('/runtime-config/llm', request).then((res) => res.data)
}
export function fetchPolicySources() {
  return http.get<PolicySourceConfig[]>('/runtime-config/policy-sources').then((res) => res.data)
}
export function updatePolicySources(request: PolicySourceConfig[]) {
  return http.put<PolicySourceConfig[]>('/runtime-config/policy-sources', request).then((res) => res.data)
}
```

Move DeepSeek defaults out of `emptyRuntimeConfig()` and define all ten application policy sources in `runtimeConfigDefaults.ts`. Every exported function must return cloned objects so reset operations cannot mutate constants.

- [ ] **Step 4: Run defaults tests and TypeScript build**

Run:

```bash
cd apps/web-react && npm test -- --run src/lib/runtimeConfigDefaults.test.ts && npm run build
```

Expected: tests and build pass.

---

### Task 4: Refactor SettingsPage to independent section actions

**Files:**
- Create: `apps/web-react/src/pages/SettingsPage.test.tsx`
- Modify: `apps/web-react/src/pages/SettingsPage.tsx`

**Interfaces:**
- Consumes: section API and defaults functions from Task 3.
- Produces: independent LLM and policy action rows with accessible button names.

- [ ] **Step 1: Write failing DOM interaction tests**

Mock `../api/client`, render `SettingsPage` with `createRoot`, and locate buttons by card plus text. Cover these behaviors:

```ts
click(buttonIn('大模型配置', '保存到 Nacos'))
await flushPromises()
expect(updateLlmRuntimeConfig).toHaveBeenCalledTimes(1)
expect(updatePolicySources).not.toHaveBeenCalled()

change(modelInput, 'unsaved-model')
click(buttonIn('政策源配置', '重新读取'))
await flushPromises()
expect(modelInput.value).toBe('unsaved-model')

click(buttonIn('政策源配置', '重置为默认'))
expect(updatePolicySources).not.toHaveBeenCalled()
expect(policyRows()).toHaveLength(10)
```

Also assert that a pending LLM save disables only LLM actions while policy actions remain enabled.

- [ ] **Step 2: Run SettingsPage tests and verify RED**

Run:

```bash
cd apps/web-react && npm test -- --run src/pages/SettingsPage.test.tsx
```

Expected: missing section API mocks and only one shared action row.

- [ ] **Step 3: Implement independent handlers and card action rows**

Replace shared `saving`, `onSave`, `onReset`, and the policy-only footer with four local states and six handlers:

```ts
const [llmLoading, setLlmLoading] = useState(false)
const [llmSaving, setLlmSaving] = useState(false)
const [policyLoading, setPolicyLoading] = useState(false)
const [policySaving, setPolicySaving] = useState(false)
```

Each handler updates only `form.llm` or `form.policySources` through `setRuntimeConfigForm({ ...form, target: next })`. A blank key is sent as null; after successful LLM save, clear the input and preserve the server's `apiKeyConfigured` state. Put `重新读取`, `重置为默认`, and `保存到 Nacos` in each card footer. Use toast messages `大模型配置已保存到 Nacos` and `政策源配置已保存到 Nacos`.

Use React's normal top-level `useState` import and remove the bottom-of-file `useLocal` wrapper.

- [ ] **Step 4: Run React tests and build**

Run:

```bash
cd apps/web-react && npm test -- --run src/pages/SettingsPage.test.tsx src/lib/runtimeConfigDefaults.test.ts && npm run build
```

Expected: all tests and build pass.

---

### Task 5: Integrated verification and deployment

**Files:**
- No source files added.

**Interfaces:**
- Verifies all outputs from Tasks 1-4 against the running Nacos and Docker stack.

- [ ] **Step 1: Run focused and neighboring regression suites**

```bash
mvn -pl apps/api -Dtest=RuntimeConfigControllerTest,RuntimeConfigServiceTest test
cd apps/web-react && npm test -- --run src/pages/SettingsPage.test.tsx src/lib/runtimeConfigDefaults.test.ts
git diff --check
```

Expected: all commands succeed.

- [ ] **Step 2: Build current artifacts and recreate containers**

```bash
mvn -pl apps/api -DskipTests package
cd apps/web-react && npm run build
cd ../.. && docker compose up -d --build api web
```

Expected: both containers become healthy.

- [ ] **Step 3: Verify APIs without changing secrets**

```bash
curl -fsS http://127.0.0.1:19080/api/runtime-config/llm
curl -fsS http://127.0.0.1:19080/api/runtime-config/policy-sources
curl -fsS http://127.0.0.1:19080/actuator/health
```

Expected: LLM response contains `apiKey: null`, policy response contains the configured list, and health is `UP`.

- [ ] **Step 4: Exercise independent writes against a captured Nacos document**

Before each PUT, capture the Nacos YAML and compare non-target nodes after the request. Use a blank `apiKey` in verification so no credential is printed or replaced. Confirm an LLM PUT leaves `research.live-data` and unknown nodes unchanged; confirm a policy PUT leaves `research.ai.llm` unchanged.

- [ ] **Step 5: Report outcomes**

Report the independent controls, endpoint behavior, test counts, container state, URL `http://127.0.0.1:5176/#/settings`, and any remaining external credential requirement without exposing secrets.
