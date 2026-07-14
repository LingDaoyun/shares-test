# Task 4 Report: V2 Recommendation Ledger

## Scope Delivered

- Appended the `v2_recommendation_ledger` table and the required symbol/time and strategy/time indexes to `apps/api/src/main/resources/schema.sql`.
- Added `V2RecommendationLedgerEntity` with the required JPA field mappings and immutable read accessors.
- Added `V2RecommendationLedgerRepository` with fingerprint lookup and deterministic latest-by-symbol lookup.
- Added `V2RecommendationLedgerService`:
  - serializes the complete `StrategySignal`, including its canonical replay payload;
  - derives a SHA-256 recommendation fingerprint from strategy, version, symbol, decision time, and payload;
  - reuses an existing record for the same fingerprint;
  - derives a stable ledger id and persists the normalized ledger columns;
  - returns the latest decision per symbol ordered by decision time and ledger id.
- Added focused integration coverage for idempotent recording, replay-payload persistence, fingerprint length, and latest-decision lookup.

## TDD Evidence

1. Added `V2RecommendationLedgerServiceTest` before production code.
2. Ran `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test` and observed the expected red failure: the three ledger production classes and repository were unresolved.
3. Implemented only the schema/entity/repository/service specified in the task brief.
4. Re-ran the focused test after implementation. It passed with 2 tests, 0 failures, 0 errors, and 0 skipped.

## Review And Verification

- Reviewed the final scoped diff against the task brief.
- Ran `git diff --check` successfully.
- Re-ran `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test` successfully: 2 tests passed.

## Scope Protection

- Did not modify the pre-existing user change in `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md`.
- No files outside the task-brief list were changed except this required task report.

## Concerns

None.
