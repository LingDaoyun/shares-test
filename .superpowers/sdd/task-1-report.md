# Task 1 Report: Persist Scheduled Snapshot State

## Status

DONE

## Implemented Behavior

- Added the `short_term_scheduled_snapshot` schema with the required primary key, timestamps, JSON payload columns, attempt counter, and same-day latest index.
- Added the exact snapshot stages and statuses, the immutable API record, and `waiting(...)` placeholder factory.
- Added a JPA persistence boundary that creates deterministic run keys, publishes terminal snapshots atomically, serializes `ShortTermReport` and blocked reasons with Jackson, and returns same-date latest snapshots only.
- Duplicate claims return `false` for non-failed rows. A repository-level conditional update exclusively reclaims `FAILED` rows, transitions them to `RUNNING`, and increments `attempt_count` atomically.
- Added exact date/stage/fingerprint lookup through `ShortTermScheduledSnapshotStore.find(...)`.

## RED Evidence

Command:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest test
```

Output:

```text
[ERROR] COMPILATION ERROR
ShortTermSnapshotStage: cannot find symbol
ShortTermSnapshotStatus: cannot find symbol
ShortTermScheduledSnapshotStore: cannot find symbol
ShortTermScheduledSnapshotRepository: cannot find symbol
ShortTermScheduledSnapshot: cannot find symbol
ShortTermScheduledSnapshotEntity: cannot find symbol
[INFO] 32 errors
BUILD FAILURE
```

This failed for the intended reason: the scheduled snapshot persistence types did not yet exist.

## GREEN Evidence

Command:

```bash
mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test
```

Output:

```text
Running com.aistock.research.shortterm.schedule.ShortTermScheduledSnapshotStoreTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Running com.aistock.research.history.SchemaCompatibilityTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The store test covers deterministic duplicate claims, terminal report JSON round-trip, blocked-reason JSON round-trip, failed-run reclaim with attempt count `2`, same-day latest ordering, and no previous-date fallback. H2 logs primary-key violations during intentional duplicate claims; the store catches these as `DataIntegrityViolationException` and the tests pass.

## Files Changed

- `apps/api/src/main/resources/schema.sql`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermSnapshotStage.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermSnapshotStatus.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshot.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotEntity.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotRepository.java`
- `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotStore.java`
- `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermScheduledSnapshotStoreTest.java`

## Commit

`a2018a1 feat: persist scheduled short-term snapshots`

## Self-Review and Concerns

- Reviewed every required schema column, enum value, deterministic key component, and record field against the task brief.
- Confirmed the duplicate insert is forced to persist (rather than merge) so the required `DataIntegrityViolationException` path is exercised.
- Confirmed reclaim is a single transactional conditional update on `snapshot_key` and `FAILED`, preventing two API instances from both reclaiming the same row.
- Confirmed terminal publication is performed in one transactional store method and both JSON payloads deserialize through the Spring `ObjectMapper`.
- Ran whitespace checks on the committed scope before commit; no errors.
- No Task 1 concerns. Maven printed pre-existing unrelated deprecation and unchecked-operation compiler warnings.

The report is intentionally not included in the feature commit. No unrelated dirty or untracked workspace files were staged, reverted, reset, stashed, or modified.

## Review Fix: One-Way Terminal Publication

- Commit: `dec7f22 fix: guard scheduled snapshot terminal publication`
- Files: `ShortTermScheduledSnapshotEntity.java`, `ShortTermScheduledSnapshotRepository.java`, `ShortTermScheduledSnapshotStore.java`, and `ShortTermScheduledSnapshotStoreTest.java`.
- Behavior: terminal publication now uses one conditional `RUNNING -> terminal` database update. A second `finish(...)` or `fail(...)` fails without changing the first `PRESELECT_READY`, `FINAL_READY`, `NO_TRADE`, `DATA_BLOCKED`, or `FAILED` result. `finish(...)` rejects `RUNNING` and `FAILED` arguments.
- Persistence: `Persistable.isNew()` now changes to false after JPA load/persist lifecycle callbacks, preserving the insert-only duplicate-claim path while preventing a future loaded-entity save from being treated as an insert.
- Tests: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test` passed: 7 tests, 0 failures, 0 errors.
- Concern: intentional duplicate-claim tests still emit H2 primary-key violation logs before the store catches `DataIntegrityViolationException`; this is expected for the insert-first claim design.

## Second Review Fix: Retry Generation Fence

- Commit: `3e137b4 fix: fence scheduled snapshot retry attempts`
- Files: `ShortTermSnapshotClaim.java`, `ShortTermScheduledSnapshotRepository.java`, `ShortTermScheduledSnapshotStore.java`, and `ShortTermScheduledSnapshotStoreTest.java`.
- Behavior: successful acquisition now returns an immutable snapshot claim containing `snapshotKey` and `attemptCount`; duplicate or non-reclaimable acquisition returns empty. Terminal publication requires this claim and atomically matches both `RUNNING` and the claim attempt.
- Retry reset: `FAILED -> RUNNING` now advances `attempt_count`, refreshes `started_at` and `updated_at`, and clears the prior completion time, report, data cutoff, blocked reasons, and terminal message.
- Regression: a focused test proves delayed attempt A `finish/fail` calls cannot publish after attempt B reclaims the row, while attempt B can publish normally.
- RED: the updated desired API failed compilation before the claim type and tokenized method signatures existed.
- GREEN: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test` passed: 8 tests, 0 failures, 0 errors.
- Concern: intentional duplicate claims continue to emit expected H2 primary-key violation logs before the store handles the collision.

## Final Review Fix: Stale RUNNING Recovery

- Commit: `56cc8f2 fix: recover stale scheduled snapshot claims`
- Files: `ShortTermScheduledSnapshotRepository.java`, `ShortTermScheduledSnapshotStore.java`, and `ShortTermScheduledSnapshotStoreTest.java`.
- Recovery API: added transactional `recoverStaleRunning(...)`. Its conditional update requires the deterministic key, `RUNNING`, the expected attempt count, and both `started_at` and `updated_at` at or before the supplied cutoff.
- Recovery reset: a successful reclaim increments `attempt_count`, refreshes `started_at`/`updated_at`, clears completion/report/data-cutoff/block fields, resets the running message, and returns the new claim. Old attempts remain fenced out of terminal publication.
- Exception handling: `claim(...)` now confirms the deterministic key exists before treating `DataIntegrityViolationException` as a duplicate; otherwise it rethrows the original storage exception.
- RED: the real H2 `parameters_json` non-null violation was previously swallowed, and the stale-recovery API did not exist.
- GREEN: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test` passed: 10 tests, 0 failures, 0 errors.
- Concern: duplicate-claim and deliberate non-null-violation tests emit expected H2 constraint logs; the assertions verify their distinct application behavior.

## Restart Recovery and Claim Validation Follow-up

- Commit: `9fa9c79 fix: support restart-safe snapshot recovery`
- Files: `ShortTermScheduledSnapshot.java`, `ShortTermScheduledSnapshotStore.java`, and `ShortTermScheduledSnapshotStoreTest.java`.
- Restart recovery: persisted snapshots now expose `attemptCount`. A scheduler can load a `RUNNING` snapshot and call the deterministic identity overload of `recoverStaleRunning(...)` with its persisted attempt, cutoff, and restart time; the repository transition remains atomic and attempt-fenced.
- Claim validation: `claim(...)` now rejects null trade date, null stage, null/blank/over-64 fingerprint, null parameters JSON, null start time, and any computed snapshot key over 160 characters before calling the repository.
- Regression: tests prove restart-style recovery without an in-memory prior claim, old-attempt rejection, successful publication by the recovered claim, and same-key invalid input rejection without changing the persisted attempt.
- Prior storage guarantee: arbitrary `DataIntegrityViolationException` propagation remains covered using valid claim inputs and a repository failure stub.
- RED: the restart test failed to compile because persisted snapshots lacked `attemptCount`; the desired identity recovery overload was also absent.
- GREEN: `mvn -pl apps/api -Dtest=ShortTermScheduledSnapshotStoreTest,SchemaCompatibilityTest test` passed: 12 tests, 0 failures, 0 errors.
- Concern: intentional duplicate-claim tests continue to emit expected H2 primary-key logs before the store resolves the duplicate path.
