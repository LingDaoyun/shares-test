# Task 4 Implementation Report

## Status

DONE

## Summary

- Added `ShortTermCoverageSnapshot` and report metadata for exact market coverage, actual reviewed symbols, and point-in-time data cutoff.
- Added `report(ShortTermScanRequest)` and `finalReport(ShortTermScanRequest, Set<String>)` over one shared report pipeline.
- Final refresh still loads full-market quotes for coverage, sentiment, and hot directions, while K-line and financial review are restricted to the preselected set.
- Replaced executable `14:57-15:00` semantics with the half-open `14:45-14:57` window.
- Added a decision-time fence so cached future minute bars cannot enter scoring.
- Preserved report metadata when the recommendation attestation layer adds capture tokens.
- Kept old report constructors and old stored JSON explicitly coverage-unreliable.

## TDD Evidence

RED:

```text
mvn -pl apps/api -Dtest=ShortTermServiceTest test
14 compilation failures for missing finalReport, coverage, reviewedSymbols,
dataCutoffAt, and renamed tail fields.
```

```text
mvn -pl apps/api -Dtest=RecommendationAttestationServiceTest#preservesShortTermCoverageAndCutoffWhenAddingAttestations test
1 failure: attestation downgraded coverage to explicitly unreliable.
```

```text
mvn -pl apps/api -Dtest=ShortTermServiceTest#actionableTailNeverUsesMinuteAfterDecisionTime test
1 failure: 14:50 decision incorrectly used the cached 14:52 minute.
```

GREEN:

```text
mvn -pl apps/api -Dtest=ShortTermServiceTest,ShortTermGoldenCrossAnalyzerTest,ShortTermScanJobServiceTest,TradingClockServiceTest,RecommendationAttestationServiceTest test
Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
```

The same 74-test suite also passed from a detached clean worktree at commit `76c2681`.

```text
git diff --check
PASS
```

## Scope

No scheduler/orchestration, endpoint/frontend presentation, or trade-review behavior was implemented in this task.

## Concerns

None.

## Review Fix: Full-Market Coverage And Quote Point-In-Time

Commit: `ed50255 fix: enforce short-term final market coverage`

### Changes

- `finalReport` now requests the source-reported full A-share universe independently of the manual `scanLimit`.
- `AshareQuoteSnapshot.expectedCount` now retains the source-reported universe total. A 100-row request against a reported 5000-row universe remains `100/5000`, incomplete, and execution-unreliable.
- Missing or inconsistent source totals produce an unknown expected universe and can never become execution-reliable.
- Remote row caps continue pagination with symbol deduplication; final execution also requires the source snapshot to be complete, so 90% is necessary but not sufficient.
- Final reports deduplicate quotes and require a real market timestamp on the decision market date at or before `decisionAt` before any prefilter, sentiment, hot-direction, scoring, or expensive review step.
- Missing, future, and cross-date market timestamps block execution reliability.
- `fetchedAt` remains provenance only. `dataCutoffAt` is derived from eligible market timestamps and actionable minute evidence, never from fetch time.
- Manual reports retain their explicit `scanLimit` research behavior, while their coverage metadata cannot claim full-market execution reliability unless the full reported universe was actually requested.

### Review TDD Evidence

RED:

```text
ShortTermServiceTest
Tests run: 50, Failures: 3
- final report requested 100 instead of the full reported universe
- 14:52 quote entered a 14:50 final report
- missing market timestamp entered review and cutoff

EastMoneyClientTest#snapshotKeepsSourceReportedUniverseWhenRequestIsOnlyASample
expected expectedCount 5000 but was 100
```

GREEN:

```text
Current worktree focused suite:
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0

Detached clean worktree containing only the focused commit:
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0

git diff --cached --check
PASS
```

The focused suite included `ShortTermServiceTest`, `ShortTermGoldenCrossAnalyzerTest`,
`ShortTermScanJobServiceTest`, `TradingClockServiceTest`,
`RecommendationAttestationServiceTest`, and `EastMoneyClientTest`.

## Final Review Fix: Per-Page Universe Metadata

Commit: `4ec477e fix: reject inconsistent quote page totals`

### Exact Pagination Rule

- The first non-empty page must provide a positive `totalCount`; that value becomes the authoritative source universe.
- Every later non-empty page must also provide a positive `totalCount` equal to the first authoritative value.
- A non-empty page with zero, negative, missing, or inconsistent `totalCount` permanently invalidates metadata consistency. The snapshot then exposes `expectedCount=0`, `complete=false`, and cannot become execution-reliable even if the unique quote count reaches the first reported total.
- An empty page is treated as the provider's normal page-after-end sentinel. Its `totalCount` is not authoritative and is not required. If it arrives before the authoritative universe is fully fetched, the original expected count is retained but `complete=false` because rows are missing.

### TDD Evidence

RED:

```text
EastMoneyClientTest focused pagination cases:
Tests run: 3, Failures: 1

snapshotRejectsZeroReportedTotalOnLaterNonEmptyPage
expected expectedCount 0 but was 2
```

GREEN:

```text
EastMoneyClientTest:
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0

Task 4 focused suite, current worktree:
Tests run: 97, Failures: 0, Errors: 0, Skipped: 0

Task 4 focused suite, detached clean worktree:
Tests run: 97, Failures: 0, Errors: 0, Skipped: 0

git diff --check
PASS
```

No Task 5 scheduler/persistence orchestration, frontend, or trade-review code was changed.

## Final Point-In-Time Fix: Manual And Final Reports

Commit: `1fa0158 fix: enforce manual quote point-in-time fence`

### Changes

- Manual `report` and `finalReport` now enter the same point-in-time quote universe before prefiltering, market sentiment, hot-direction aggregation, K-line selection, financial review, candidate scoring, and ranking.
- A quote can enter analysis only when it has a real `marketTimestamp`, the timestamp belongs to the decision market date, and it is not later than authoritative `decisionAt`.
- Missing, future, and cross-date quotes remain visible only through reduced coverage/missing-row metadata. They cannot become research candidates or change valid candidates' scores.
- Coverage below the execution threshold still forces `MARKET_RISK_WAIT` and a `WAIT` daily action.
- Existing minute-fence and closed-market tests now use quote timestamps that are valid for their own decision date/time; their original behavioral assertions remain unchanged.
- Full-market `finalReport` request and completeness semantics from the earlier review fixes are unchanged.

### TDD Evidence

RED:

```text
ShortTermServiceTest manual point-in-time cases:
Tests run: 2, Failures: 2

Adding a future or missing-timestamp robot quote changed:
- hot-direction sample count from 1 to 2
- robot heat score from 68.95 to 95.56
- leaders and weighted market evidence
```

GREEN:

```text
Task 4 focused suite, current worktree:
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0

Task 4 focused suite, detached clean worktree:
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0

git diff --check
PASS
```

No Task 5 scheduler/persistence orchestration, frontend, or trade-review code was changed.
