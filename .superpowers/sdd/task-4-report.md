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

## Re-Review Fix: Canonical Ledger Payload JSON

- Configured the ledger serializer with deterministic object-property and map-entry ordering before deriving `payload_json` and the recommendation fingerprint.
- Added a regression test that records two semantically identical signals whose `context` and replay-payload maps have opposite insertion orders; both return one ledger id and persist one row.
- Strengthened replay-payload verification by parsing `payload_json` with `ObjectMapper` and asserting distinct `context` and `replayPayload` values, plus `sourceQuality` and `signalProvenance`.

## Re-Review Fix Verification

- The new map-order regression failed before the serialization fix because the two recordings generated different ledger ids.
- `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test` passed after the fix: 4 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` passed with no trailing-whitespace errors.

## Review Fix: Concurrent Ledger Recording

- Changed `V2RecommendationLedgerService.record` to perform creation in a `REQUIRES_NEW` transaction with `saveAndFlush`.
- When the database rejects a duplicate ledger fingerprint/identifier, the service now re-reads and returns the committed ledger entry instead of surfacing the race failure.
- Added an eight-caller `CountDownLatch` concurrency test. Every future completes with the same ledger id and the repository retains exactly one row.
- Extended replay-payload assertions to cover the canonical Task 1 context value (`valuation=pb-percentile`), `sourceQuality=VERIFIED`, and `signalProvenance=RULE_ENGINE`.

## Review Fix Verification

- The new concurrent test failed against the pre-fix service with the expected `DataIntegrityViolationException` from the unique ledger key.
- After the isolated-insert recovery fix, `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test` passed: 3 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` passed.

## Review Fix: Suppress Expected Same-Instance Race Noise

- Added a ref-counted per-fingerprint `ReentrantLock` in `V2RecommendationLedgerService` around the existing create transaction, so concurrent callers in the same service instance re-check and create one ledger serially.
- Kept the `DataIntegrityViolationException` lookup recovery and database unique constraint as cross-process safety nets.
- Preserved the eight-caller concurrency test assertions: all callers return one ledger id and `repository.count()` remains 1.

## Review Fix Verification

- Ran `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test`: 3 tests passed, 0 failures, 0 errors, 0 skipped; output contained no SQL unique-key, constraint, duplicate, or integrity-violation diagnostics.
- Ran `git diff --check` successfully.
- The only remaining warning is the existing Nacos notice that `ai-stock-api-local.yml` is empty.

## Review Fix: Canonical Numeric Fingerprinting

- Canonicalized every `BigDecimal` in the serialized signal payload tree recursively by removing insignificant trailing zeros and emitting a deterministic plain decimal representation.
- Applied the persisted ledger scales to the top-level signal values before fingerprinting: `rankScore`, `dataConfidence`, `historicalHitRate`, and `riskReward` use scale 2; optional `positionLimit` uses scale 4.
- Added a regression that records two signals differing only by BigDecimal lexical scale, including a nested replay-payload decimal. Both calls return the same ledger id, persist one row, and retain identical deterministic `payload_json`.

## Final Verification

- `mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test` passed: 5 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check` passed with no whitespace errors.
