# Manual Short-Term Scan Anytime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the closing decision-window restriction from user-triggered short-term scans while retaining all data-quality gates and scheduled-scan deadlines.

**Architecture:** `ShortTermFinalResultGate.evaluateManual` will delegate directly to the existing shared validator with scheduled deadline enforcement disabled. `evaluateScheduled` remains unchanged, so automatic final selection keeps its deadline. Existing unit and job-service tests will prove the invocation types stay isolated.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, Maven, Docker Compose

## Global Constraints

- Manual scans must not check the 14:45-14:56 closing decision window.
- Manual scans must still require current-date, fresh, reliable market data with at least 90% coverage.
- Scheduled scans must retain their existing completion deadline.
- Existing unrelated working-tree changes must not be reverted or included in the feature commit.

---

### Task 1: Remove the Manual Decision-Window Gate

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermManualResultGateTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java`

**Interfaces:**
- Consumes: `ShortTermFinalResultGate.evaluateManual(ShortTermReport, Instant)`
- Produces: manual `FINAL_READY`, `NO_TRADE`, or data-quality `DATA_BLOCKED` results without `MANUAL_OUTSIDE_DECISION_WINDOW`

- [ ] **Step 1: Replace the before-window rejection test with a failing readiness test**

```java
@Test
void allowsManualReportBeforeTailDecisionWindow() {
    ShortTermFinalResultGate.Result result = gate.evaluateManual(
            report(
                    Instant.parse("2026-07-23T06:39:00Z"), false,
                    new BigDecimal("0.99"), true, List.of()),
            Instant.parse("2026-07-23T06:40:00Z"));

    assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.NO_TRADE);
    assertThat(result.blockedReasons()).isEmpty();
}
```

- [ ] **Step 2: Replace the after-window rejection test with a failing 15:51 readiness test**

```java
@Test
void allowsManualReportAfterTailDecisionWindow() {
    Instant decisionAt = Instant.parse("2026-07-23T07:51:00Z");

    ShortTermFinalResultGate.Result result = gate.evaluateManual(
            report(
                    Instant.parse("2026-07-23T07:50:00Z"), false,
                    new BigDecimal("0.99"), true,
                    List.of(mock(com.aistock.research.shortterm.ShortTermCandidate.class))),
            decisionAt);

    assertThat(result.status()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
    assertThat(result.blockedReasons()).isEmpty();
}
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest test
```

Expected: both new tests fail because the current implementation returns `DATA_BLOCKED` with `MANUAL_OUTSIDE_DECISION_WINDOW`.

- [ ] **Step 4: Implement the minimal manual-gate change**

Replace `evaluateManual` with:

```java
public Result evaluateManual(ShortTermReport report, Instant decisionCompletedAt) {
    return evaluate(
            tradingClock.currentMarketDate(),
            report,
            decisionCompletedAt,
            decisionCompletedAt,
            false);
}
```

Delete the now-unused `insideTailWindow(LocalTime)` helper and its `LocalTime` import. Do not change `evaluateScheduled` or the shared `validate` method.

- [ ] **Step 5: Run focused gate tests and verify GREEN**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest test
```

Expected: all `ShortTermManualResultGateTest` tests pass, including scheduled deadline, wrong-date, coverage, and freshness regressions.

- [ ] **Step 6: Commit the gate behavior**

```bash
git add apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermManualResultGateTest.java
git commit -m "fix: allow manual short-term scans anytime"
```

### Task 2: Align the Manual Scan Job Contract and Verify End to End

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermScanJobServiceTest.java`

**Interfaces:**
- Consumes: `ShortTermScanJobService.start(ShortTermScanRequest)` and `get(String)`
- Produces: a successful job carrying a ready manual-gate result without window-block reasons

- [ ] **Step 1: Update the job-service test to require a ready result**

Configure the mocked manual gate as:

```java
when(gate.evaluateManual(any(), any())).thenReturn(new ShortTermFinalResultGate.Result(
        ShortTermSnapshotStatus.FINAL_READY,
        "手动扫描结果已就绪",
        List.of()
));
```

Update `shouldRunScanJobAndExposeSucceededReport` assertions to:

```java
assertThat(finished.resultStatus()).isEqualTo(ShortTermSnapshotStatus.FINAL_READY);
assertThat(finished.blockedReasons()).isEmpty();
assertThat(finished.message()).isEqualTo("手动扫描结果已就绪");
```

- [ ] **Step 2: Run both focused test classes**

Run:

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest,ShortTermScanJobServiceTest test
```

Expected: all tests in both classes pass.

- [ ] **Step 3: Run the complete backend suite**

Run:

```bash
mvn -pl apps/api test
```

Expected: build succeeds with zero failures and zero errors.

- [ ] **Step 4: Commit the scan-job contract test**

```bash
git add apps/api/src/test/java/com/aistock/research/shortterm/ShortTermScanJobServiceTest.java
git commit -m "test: cover anytime manual scan jobs"
```

- [ ] **Step 5: Package and deploy the API**

Run:

```bash
mvn -pl apps/api -DskipTests package
docker compose up -d --build api
```

Expected: `ai-stock-api` reports healthy on port `19080`.

- [ ] **Step 6: Trigger and poll a real manual scan**

Start and retain the returned identifier:

```bash
JOB_ID=$(curl -fsS -X POST -H 'Content-Type: application/json' \
  -d '{}' http://127.0.0.1:19080/api/short-term/scan-jobs | jq -r '.jobId')
```

Poll until the scan leaves `RUNNING`:

```bash
while true; do
  RESPONSE=$(curl -fsS "http://127.0.0.1:19080/api/short-term/scan-jobs/${JOB_ID}")
  echo "${RESPONSE}" | jq '{status, resultStatus, blockedReasons, message, dataCutoffAt, finishedAt}'
  [ "$(echo "${RESPONSE}" | jq -r '.status')" != "RUNNING" ] && break
  sleep 2
done
```

Expected: terminal `status` is `SUCCEEDED`; `blockedReasons` does not contain `MANUAL_OUTSIDE_DECISION_WINDOW`. If current-date freshness or another quality gate fails, the result may remain `DATA_BLOCKED` only for that specific quality reason.

- [ ] **Step 7: Verify runtime health and diff hygiene**

Run:

```bash
curl -fsS http://127.0.0.1:19080/actuator/health
git diff --check
git status --short
```

Expected: health is `UP`, diff check is clean, and unrelated pre-existing changes remain untouched.
