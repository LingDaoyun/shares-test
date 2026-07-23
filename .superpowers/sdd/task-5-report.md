# Task 5: V2 Compatibility API Report

## Status

Implemented the V2 compatibility probe endpoint:

`GET /api/v2/signals/sample?symbol=...&companyName=...&strategyCode=...`

The controller creates a Task 1-compatible `StrategySignal` through `StrategySignalFactory`, records it through `V2RecommendationLedgerService`, and returns the mapped `V2SignalResponse` including the generated `ledgerId`.

## Files changed

- `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalResponse.java`
- `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalController.java`
- `apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java`

The pre-existing change in `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` was left untouched and excluded from the commit.

## Verification

1. TDD red phase: `mvn -pl apps/api -Dtest=V2SignalControllerTest test` failed before implementation because `/api/v2/signals/sample` had no controller mapping and returned HTTP 500.
2. TDD green phase: the same command passed after implementation.
3. Final focused result: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
4. `git diff --check` passed.

## Concerns

None for the requested scope. The test startup emits existing Nacos configuration warnings and Java deprecation/unchecked compiler warnings, but the focused test completes successfully.

## Review Fix Report

The compatibility sample now passes explicit `SourceQualityStatus.SINGLE_SOURCE` metadata and records both `sourceQualityReason=compatibility probe` and the `v2-compatibility-probe` marker in context/replay payload data.

`V2SignalControllerTest` now links the response `ledgerId` to the persisted repository row, verifies the single-row write, strategy version, decision and cutoff timestamps, and parses `payloadJson` to assert source quality, provenance, replay payload, and compatibility markers while retaining the original endpoint response assertions.

Verification:

- `mvn -pl apps/api -Dtest=V2SignalControllerTest test`: 1 test passed, 0 failures, 0 errors.
- `git diff --check`: passed.

---

# Scheduled Overnight Snapshot - Task 5 Report

## Status

Task 5 is implemented and committed as:

`eb6158c feat: schedule two-stage short-term scans`

This section is intentionally separate from the earlier V2 compatibility report that already occupied this filename.

## Delivered

- Added a dedicated single-worker scheduled executor with a bounded one-item queue, independent of the manual `ShortTermScanJobService` executor.
- Added deterministic queue-saturation publication as `DATA_BLOCKED` with reason `SCHEDULED_EXECUTOR_SATURATED`.
- Added canonical JSON serialization of the resolved `ShortTermScanRequest` and `OvernightRuleSet`, with a SHA-256 parameter fingerprint used by the durable database claim.
- Added `PRESELECT`, `FINAL`, and `READINESS_GUARD` orchestration with enabled/trading-day gates, duplicate deduplication, same-day snapshot lookup, and generation-fenced stale `RUNNING` recovery using persisted `attemptCount`.
- Preserved the hardened store contract: `claim` returns `Optional<ShortTermSnapshotClaim>` and every `finish` or `fail` call carries that claim token.
- Added final-stage gates for nonempty same-day preselection, at least 90% reliable full-market coverage, same-day cutoff, no future-data cutoff, configured freshness, and completion by `14:53:59`.
- Added explicit `NO_TRADE` only when every evidence gate passes and the valid candidate list is empty. Missing, stale, incomplete, unavailable, or late evidence publishes `DATA_BLOCKED`; no candidate is forced.
- Recommendation attestation and research-history recording occur only after a valid nonempty final report passes every gate. No user position is inferred.
- Added a no-fetch readiness guard with exact `FINAL_MISSING`, `FINAL_STALE`, and `FINAL_FAILED` reasons. A valid `FINAL_READY` or `NO_TRADE` result is preserved through a separate guard snapshot rather than overwritten.
- Added three refreshable `SchedulingConfigurer` triggers in `Asia/Shanghai`. Invalid refreshed cron values retain the last valid value independently for each trigger.

## Files

- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettings.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledExecutorConfig.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanService.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScanScheduler.java`
- `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettingsTest.java`
- `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledScanServiceTest.java`
- `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScanSchedulerTest.java`

## TDD And Verification

1. Red phase: `mvn -pl apps/api -Dtest=ShortTermScheduledScanServiceTest,ShortTermScanSchedulerTest test` failed at test compilation because the three Task 5 production classes did not exist.
2. Green phase: the same focused suite passed after the minimum implementation.
3. Cron refresh red/green: a custom valid cron followed by an invalid Nacos refresh initially fell back to the default; the settings implementation was then changed to retain the per-trigger last valid value.
4. Readiness red/green: a `14:54` guard initially treated a valid result completed before `14:53:59` as late; validation was split so the original completion time controls the deadline while guard time controls freshness.
5. Evidence failure red/green: a final market-data exception initially became `FAILED`; it now deterministically publishes `DATA_BLOCKED` with `EVIDENCE_UNAVAILABLE`.
6. Focused orchestration, settings, scheduler, and manual-queue suite: 26 tests passed, 0 failures, 0 errors.
7. Expanded short-term, snapshot-store, attestation, and schema-compatible Spring context suite: 123 tests passed, 0 failures, 0 errors.
8. `git diff --cached --check` passed before commit.

## Scope And Review Note

Only the seven Task 5 production/test files were included in commit `eb6158c`; unrelated dirty backend, frontend, trade-review, documentation, and temporary files were preserved.

An independent CLI review was started after the commit. The first attempt was rejected by an incompatible local default model, and the compatible-model retry was still running when the user requested an immediate stop. No review findings were returned or applied.

---

# Scheduled Overnight Task 5 - Review Fix Report

## Status

The Task 5 review findings are fixed in:

`8484705 fix: harden scheduled short-term lifecycle`

## Review Findings Resolved

- `READINESS_GUARD` now loads the actual latest same-day `FINAL` snapshot by date and stage, independent of the guard's newly refreshed Nacos fingerprint. Valid `FINAL_READY` and `NO_TRADE` snapshots remain accepted.
- The snapshot store can enumerate all same-day `RUNNING` snapshots for a stage across fingerprints, while every stale recovery still uses the persisted `attemptCount`, explicit stale cutoff, and generation-fenced claim token.
- A nonempty valid final report must first publish `FINAL_READY` successfully under claim fencing. Recommendation attestation and history archival run only after that terminal write succeeds.
- Scheduled history IDs are deterministic from `snapshotKey`, record type, and symbol. Restart or retry archival is idempotent and does not create duplicate analysis or decision rows.
- Attestation/history failures after `FINAL_READY` retain the valid terminal snapshot, emit a retryable archival warning, and never downgrade it to `FAILED` or `DATA_BLOCKED`. Startup retries archival idempotently without fetching market data.
- `ApplicationReadyEvent` recovery reclaims stale same-day `PRESELECT` and `FINAL` work before the deadline using each snapshot's persisted canonical parameter JSON and fingerprint. It does not create a fresh claim from changed Nacos settings.
- The dedicated scheduled executor now uses a non-daemon worker and bounded graceful shutdown: it waits up to 30 seconds for running/queued work before forced interruption.
- All schedule, cutoff, freshness-date, and cron calculations are fixed to `Asia/Shanghai`. A non-Shanghai Nacos zone is rejected and warned once per key/raw value.
- Invalid cron warnings are likewise deduplicated per key/raw value. A valid refresh clears warning state so a later invalid refresh can warn once again.

## TDD And Verification

1. Added red tests for latest-by-date-and-stage guard lookup, terminal-before-side-effects ordering, deterministic history idempotence, post-terminal archival failure, startup recovery, multi-fingerprint `RUNNING` discovery, fixed Shanghai scheduling, warning deduplication, non-daemon execution, and graceful shutdown.
2. Focused Task 5 and history suite in the working tree: 49 tests passed, 0 failures, 0 errors.
3. Expanded short-term, snapshot, attestation, history, and trade-feedback suite in the working tree: 169 tests passed, 0 failures, 0 errors.
4. Clean detached worktree at `8484705`, focused Task 5 and history suite: 49 tests passed, 0 failures, 0 errors.
5. The clean expanded 169-test run reached 11 pre-existing trade-feedback fixture errors because fixed market timestamps had aged beyond the attestation freshness boundary. The main worktree already contains unrelated, uncommitted fixes for those fixtures; they were intentionally preserved and excluded from the Task 5 commit.
6. `git diff --cached --check` passed before the implementation commit.

## Scope

No Task 6, frontend, backtest, or trade-review production behavior was changed. Unrelated dirty files remain untouched and uncommitted.
