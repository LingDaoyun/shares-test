# Task 1 Report: V2 Strategy Signal Contract

## Status

Complete.

## Scope

Implemented the V2 strategy signal contract in `com.aistock.research.v2.strategy`:

- Added `StrategyCode`.
- Added `StrategyAction`.
- Added `CandidateStage`.
- Added immutable `StrategySignal` record with defensive copies for collection fields.
- Added `StrategySignalFactory.blocked(...)` and `StrategySignalFactory.research(...)`.
- Added the focused `StrategySignalFactoryTest` covering blocked and research signals.

No unrelated source files were modified. The pre-existing dirty file `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` was left unchanged.

## TDD Evidence

1. Added the focused test first.
2. Ran `mvn -pl apps/api -Dtest=StrategySignalFactoryTest test`.
3. Confirmed the expected failure because the strategy contract classes did not yet exist.
4. Added the production contract implementation.
5. Re-ran the focused test successfully.

## Verification

Command:

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
```

Result: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`.

Additional check: `git diff --check` passed.

## Commit

`2317777 feat: add v2 strategy signal contract`

## Concerns

None for this task. The working tree still contains the unrelated pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md`.

## Review Fix Report

### Status

Complete.

### Changes

- Added `SourceQualityStatus` with `VERIFIED`, `SINGLE_SOURCE`, `STALE`, `CONFLICT`, and `MISSING`.
- Added `sourceQuality` to `StrategySignal` and retained the original constructor shape with a `VERIFIED` compatibility default.
- Added validation for all mandatory decision metadata, including nonblank text fields and non-null enum/time fields.
- Added stage/action consistency validation: blocked stages require `DATA_BLOCKED` or `RISK_BLOCKED` plus a blocked reason, and blocked actions require the `BLOCKED` stage.
- Added focused tests for the source-quality default, every mandatory metadata field, and contradictory or incomplete blocked states.

### Verification

Command:

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
```

Result: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`.

Additional check: `git diff --check` passed.

### Commit

`7400008 fix: harden v2 strategy signal contract`

## Second Review Fix Report

### Status

Complete.

### Changes

- `DATA_BLOCKED` factory signals now use `SourceQualityStatus.MISSING`; `RISK_BLOCKED` defaults to `VERIFIED`.
- Research signals reject null `dataConfidence`, and `MISSING` source quality is restricted to `BLOCKED` plus `DATA_BLOCKED`.
- Added defensive, non-null `replayPayload` support with compatibility constructors defaulting to `Map.of()`; factories copy context values or accept an explicit payload.
- Added `SignalProvenance` and reject `AI_EVIDENCE_ONLY` for `ADD`, `LIGHT_TRIAL`, `REDUCE`, and `EXIT`.
- Blocked reasons now reject null, blank, and whitespace-only entries.
- Added focused regression tests for each second-review finding.

### Verification

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
git diff --check
```

Result: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`; diff check passed.

### Commit

`5bedfd6 fix: address second review of strategy signal contract`

### Concerns

The unrelated pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remains untouched.

## Fifth Review Fix Report

### Status

Complete.

### Changes

- Replay payloads now always contain the canonical decision envelope, including decision metadata, scores, source/provenance, evidence, blocked reasons, context, and execution conditions. Canonical values override conflicting caller payload values while preserving additional caller fields.
- Ordinary blocked, research, and compatibility construction now defaults to `SourceQualityStatus.SINGLE_SOURCE`; explicit source-quality overloads remain available.
- Signals now reject a `dataCutoffAt` later than `decisionAt` with an error containing `dataCutoffAt`.
- Added regression tests for nonempty context-derived payloads, canonical replay-field precedence, default source quality, compatibility defaults, and future-data rejection.

### Verification

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
git diff --check
```

Result: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`; diff check passed.

### Commit

`c5044a7 fix: address fifth review of strategy signal contract`

### Concerns

The unrelated pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remains untouched.

## Fourth Review Fix Report

### Status

Complete.

### Changes

- Compatibility and direct constructors now derive a non-empty replay payload from core decision fields whenever the supplied payload is null or empty, including `strategyVersion`, `symbol`, decision metadata, source quality, provenance, and context.
- JSON replay payload validation now rejects non-finite `Double` and `Float` values, including `NaN` and positive infinity.
- Added regression coverage for empty-context compatibility construction and both required non-finite numeric cases.

### Verification

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
git diff --check
```

Result: `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`; diff check passed.

### Commit

`fix: close fourth review of strategy signal contract`

### Concerns

The unrelated pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remains untouched.

## Third Review Fix Report

### Status

Complete.

### Changes

- Made `replayPayload` recursively JSON-safe while retaining the public `Map<String, Object>` type.
- Deep-copied and unmodifiable-wrapped nested maps and lists so source or returned collections cannot mutate the signal.
- Rejected unsupported values, non-string map keys, and cyclic map/list references with `IllegalArgumentException` messages containing `replayPayload`.
- Added regression tests for nested immutability and unsupported values.

### Verification

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
git diff --check
```

Result: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`; Maven `BUILD SUCCESS`; diff check passed.

### Commit

`a252f0f fix: make replay payload deeply immutable`

### Concerns

The unrelated pre-existing modification to `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remains untouched.
