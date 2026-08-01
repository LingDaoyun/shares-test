# Scheduled Overnight Snapshot Task 6 Hardening Report

## Status

DONE

## Scope

This report covers the independent review fixes applied after Task 6's prepared
short-term snapshot implementation. The existing full-width candidate list,
explicit DetailOverlay selection, no automatic first selection, and
snapshot-only initial mount behavior remain unchanged.

## RED Evidence

### Authoritative manual result gate

Command:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotControllerTest,RecommendationControllerAttestationTest,ShortTermManualResultGateTest test
```

Observed expected failure during test compilation:

```text
ShortTermManualResultGateTest: cannot find symbol ShortTermFinalResultGate
6 compilation errors
BUILD FAILURE
```

The missing shared backend gate proved that manual completion still had no
authoritative classifier.

After the first implementation, the explicit 14:55 boundary test was added and
run separately:

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest test
```

Observed expected failure:

```text
allowsManualReportAt1455EvenAfterScheduledDeadline
expected: NO_TRADE
but was: DATA_BLOCKED
Tests run: 7, Failures: 1
```

This exposed accidental reuse of the scheduled 14:53:59 deadline. Manual scans
now use only the executable 14:45-inclusive/14:57-exclusive tail window, while
scheduled runs still enforce `finalDeadline`.

### Scheduled attestation contract

The first GREEN attempt exposed a real contract fixture failure:

```text
preparedSnapshotReceivesConsumableAttestationWithoutPersistingToken
IllegalArgumentException: 服务端推荐快照无法序列化
JavaTimeModule required for LocalDate
```

After correcting the fixture to use the production Java time module, the
contract proved that a FINAL_READY response receives a non-empty token that
`RecommendationAttestationService.require(token)` can consume, while the
stored report retains its old token map unchanged.

A second RED test verified blocked manual reports cannot receive usable
attestations:

```text
dataBlockedManualResponseDoesNotIssueConsumableAttestations
Expecting empty but was: {"600000"="<issued-token>"}
Tests run: 1, Failures: 1
```

### Frontend authority and request ownership

Command:

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx OvernightTradePlanPanel.test.tsx DetailOverlay.dom.test.tsx
```

Observed expected failures:

```text
Test Files 2 failed | 1 passed
Tests 7 failed | 13 passed
```

The failures covered:

- a late scheduled resolve replacing the manual result;
- a late scheduled reject replacing manual loading/error ownership;
- manual RUNNING, FAILED, and ready labels still using automatic-task copy;
- ACTIONABLE status and single definition-grid rendering not implemented;
- BLOCKED plans still exposing entry, position, and target semantics.

An additional blocked-plan RED run failed because the panel rendered calculated
entry/ATR analysis instead of structured risk reasons.

## Changes

### Backend authority

- Extracted `ShortTermFinalResultGate` and reused it from scheduled and manual
  scan paths.
- Manual scan-job responses now expose backend-owned `tradeDate`,
  `resultStatus`, `blockedReasons`, and `strategyVersion`.
- Wrong-date quotes, non-tail execution time, unreliable or sub-90% coverage,
  and stale quotes produce `DATA_BLOCKED`.
- Reliable empty reports produce `NO_TRADE`; reliable reports with candidates
  produce `FINAL_READY`.
- Manual 14:55 results are allowed; 14:57 is blocked. Scheduled runs continue
  to obey the configured 14:53:59 completion deadline.
- DATA_BLOCKED manual reports are not attested.

### Snapshot contract and attestation

- FINAL_READY scheduled snapshot GET responses call
  `attestationService.attest(report)` and return a reconstructed snapshot.
- Stored snapshots are not mutated and persisted token maps are not reused.
- `strategyVersion` is supplied by the backend from
  `RecommendationSource.SHORT_TERM.ruleVersion()`.

### Frontend ownership and copy

- Manual completion consumes `job.resultStatus`; candidate count no longer
  upgrades a report locally.
- Scheduled mount `then`, `catch`, and `finally` all check the same request
  generation/ownership before writing state.
- Manual status copy no longer says automatic task.
- A pending prepared-snapshot request does not disable the explicit manual
  action, allowing ownership to transfer immediately.

### Trade-plan discipline

- TypeScript uses the backend's real `ACTIONABLE | BLOCKED` status contract.
- `ShortTermTradePlan` now carries structured `blockedReasons`.
- ACTIONABLE renders executable details in a single definition/divider grid.
- BLOCKED explicitly says it is non-executable, renders only risk reasons and
  warnings, and hides entry, position, target, stop, deadline, and scenario
  action semantics.
- Nested metric/scenario cards were removed.

## GREEN Evidence

Required backend verification:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotControllerTest,RecommendationControllerAttestationTest,ShortTermManualResultGateTest,ShortTermScanJobServiceTest test
```

Result:

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Required frontend verification:

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx OvernightTradePlanPanel.test.tsx DetailOverlay.dom.test.tsx
```

Result:

```text
Test Files 3 passed
Tests 20 passed
```

Required production build:

```bash
npm --prefix apps/web-react run build
```

Result:

```text
TypeScript build passed
Vite production build passed
1673 modules transformed
```

Additional backend regression coverage:

```bash
mvn -pl apps/api -Dtest=ShortTermManualResultGateTest,ShortTermScheduledScanServiceTest,ShortTermScheduledSnapshotControllerTest,RecommendationControllerAttestationTest,ShortTermScanJobServiceTest,ShortTermTradePlanServiceTest test
```

Result:

```text
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Staged-scope checks:

```text
git diff --cached --check: PASS
20 Task 6 hardening files reviewed
No task report, progress file, unrelated backend file, or unrelated page staged
```

## Second Review Closure

### RED Evidence

Backend command:

```bash
mvn -pl apps/api -Dtest=ShortTermTradePlanServiceTest,ShortTermManualResultGateTest test
```

Expected result:

```text
Tests run: 19, Failures: 1
blockedPlanUsesIndependentNonExecutableRiskWarnings
expected one non-executable warning, but received production T+1, maximum
position, T+2 retention, and exit guidance
BUILD FAILURE
```

The same run showed the two new scheduled boundary tests already passing:
`14:53:59` remains eligible and `14:54:00` returns
`FINAL_DEADLINE_EXPIRED`. Existing manual boundaries remain `14:55` eligible
and `14:57` blocked.

Frontend command:

```bash
npm --prefix apps/web-react test -- OvernightTradePlanPanel.test.tsx
```

Expected result:

```text
Test Files 1 failed
Tests 1 failed | 1 passed
```

The production-equivalent BLOCKED payload rendered its T+1, new-position,
maximum-position, T+2 retention, and exit instructions. The panel's own copy
also contained entry, position, and target language.

### Changes

- `ShortTermTradePlanService` now generates a dedicated, immutable pure-risk
  warning list for every BLOCKED plan.
- ACTIONABLE plans retain the existing T+1, position, T+2, and exit discipline.
- The BLOCKED panel never renders payload `riskWarnings`; it renders only a
  fixed non-executable explanation and structured `blockedReasons`.
- No keyword filtering or content heuristics were introduced.
- Added exact scheduled deadline tests for `14:53:59` and `14:54:00`.

### GREEN Evidence

```bash
mvn -pl apps/api -Dtest=ShortTermTradePlanServiceTest,ShortTermManualResultGateTest test
```

Result:

```text
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```bash
npm --prefix apps/web-react test -- OvernightTradePlanPanel.test.tsx ShortTermPage.test.tsx
```

Result:

```text
Test Files 2 passed
Tests 17 passed
```

```bash
npm --prefix apps/web-react run build
```

Result:

```text
TypeScript build passed
Vite production build passed
1673 modules transformed
```

Second-review staged-scope checks:

```text
git diff --cached --check: PASS
5 reviewed files staged
No report, progress file, or unrelated dirty-worktree file staged
```

## Commit

```text
83e9a97 fix: harden prepared short-term snapshots
a67fc86 fix: isolate blocked short-term guidance
```

Earlier Task 6 commits retained in history:

```text
da32dc4 chore: checkpoint short-term detail frontend
2a5eb51 feat: load prepared short-term snapshots
```

## Risks And Concerns

- The shared worktree remains dirty with unrelated user changes. They were not
  reverted, overwritten, staged, or included in either Task 6 fix commit.
- Verification covered all requested commands plus focused scheduled-service,
  scan-job, and trade-plan regressions. The complete repository-wide backend
  and frontend test suites were not run.
- This report is intentionally left unstaged, following the instruction not to
  include task reports in the code commit.
