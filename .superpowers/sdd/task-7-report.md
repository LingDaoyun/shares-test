# Task 7 Independent Review Repair Report

Status: DONE

Original repair commit: `53c6723`

Configured-risk replay repair commit: this report's
`fix: replay configured overnight risk rules` commit

## Scope

- Kept `GET /api/backtests/right-side` and its 20-day `BacktestReport`
  semantics unchanged.
- Added `ShortTermTechnicalSignalEvaluator` as the single pure K-line
  evaluator used by production `ShortTermService` and overnight replay.
- Replayed each signal with `rows[0..signalDay]` and
  `latestBarCompleted=false`; only production-analyzer `CONFIRMED` recent
  golden crosses plus `右侧早期确认` are eligible.
- Explicitly rejects signal-day `FORMING`, stale `ESTABLISHED`, and
  `NONE` golden-cross states.
- Added report `validationScope` and `unreplayedGates` for financial quality,
  market sentiment, tail-minute confirmation, and live quote freshness.
- Modeled production `firstReductionRatio=0.5`: first target exits half,
  while the remainder continues to second target, hard stop, T1/T2 time exit,
  or delayed limit-down exit.
- Added auditable exit legs, weighted executable exit price, target-hit flags,
  proxy-entry-based gap/run-up/drawdown, and non-duplicated slippage costs.
- Added per-symbol `SOURCE_FAILED`, `INSUFFICIENT_HISTORY`, `NO_SIGNAL`, and
  `OK` results plus report-level `OK`, `PARTIAL`, and `DATA_BLOCKED`.
- Limited the batch summary to the area above the candidate list and removed
  batch-derived labels from individual candidates and details.
- Added sorted-symbol/full-rule request identity and generation ownership so
  stale resolve, reject, and finally handlers cannot update a newer batch.

## RED Evidence

### Backend contract and behavior

```bash
mvn -pl apps/api \
  -Dtest=BacktestServiceTest,BacktestControllerTest,ShortTermTechnicalSignalEvaluatorTest \
  test
```

Observed: exit 1 during `testCompile`, with expected missing types and methods
for target-hit fields, split exit legs, weighted exit price, per-symbol
results, report status/message, validation scope, and unreplayed gates.

### Frontend contract and ownership

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
```

Observed: 5 failed, 16 passed. Failures covered technical-validation wording,
`DATA_BLOCKED`, partial data gaps, same-size/different-symbol request identity,
and stale rejection/finally ownership.

## GREEN Evidence

### Production signal and backend regression

```bash
mvn -pl apps/api \
  -Dtest=BacktestServiceTest,BacktestControllerTest,ShortTermServiceTest,ShortTermGoldenCrossAnalyzerTest,ShortTermTechnicalSignalEvaluatorTest \
  test
```

Result: PASS, 83 tests, 0 failures, 0 errors. This includes 53 production
short-term service tests, 12 golden-cross analyzer tests, 4 point-in-time
evaluator boundary tests, 13 backtest tests, and 1 controller contract test.

### Brief Step 6

```bash
mvn -pl apps/api -Dtest=BacktestServiceTest test
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
npm --prefix apps/web-react run build
```

Result: PASS. Backend 13/13; frontend 21/21 including deferred Promise batch
switch, empty-batch, and stale rejection cases; TypeScript and Vite production
build passed with 1,673 modules transformed. The frontend test also asserts
that no request contains `holdingDays: 20`.

### Staging Checks

```bash
git diff --cached --check
```

Result: PASS with no output.

Only Task 7 repair source and test files were staged. This report and all
unrelated user changes remained unstaged.

## Concerns

- No blocking concerns.
- This is intentionally a technical-signal historical validation, not a full
  replay of production strategy performance. The four unreplayed live or
  non-K-line gates are explicit in both the API and page.
- Daily bars cannot identify intraday ordering, so adverse open/hard-stop
  handling remains conservatively ordered before profit targets.

## Configured-Risk Replay Re-review

### Scope

- Added `minVolumeRatio`, `maxDistanceToMa20Percent`, and
  `trailingDrawdownPercent` to the independent overnight request and report
  rule contract, with explicit defaults of `1.15`, `8.00`, and `2.00`.
- The historical technical evaluator now receives the configured production
  volume and MA20-distance thresholds instead of using fixed backtest
  constants.
- `ShortTermPage` sends the exact current report technical thresholds and the
  current production trade-plan trailing drawdown. The sorted-symbol request
  identity now includes all overnight rules, so a threshold-only change
  starts and owns a new request.
- After the first target exits 50%, replay tracks the post-target peak. On
  subsequent bars the remaining leg can exit with `TRAILING_STOP`, including
  its position ratio, base price, executable price, costs, and weighted net
  return.
- Daily bars cannot reveal whether a later intraday high preceded a low.
  Therefore the trailing threshold for a day is based on the previously known
  post-target peak; hard stop and trailing stop are checked before the second
  target when both are reachable. A new high only updates the peak after those
  conservative checks.

### RED Evidence

```bash
mvn -pl apps/api \
  -Dtest=BacktestServiceTest,BacktestControllerTest test
```

Observed: exit 1 during `testCompile` with 10 expected contract errors for the
three missing request/rule parameters and accessors.

```bash
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
```

Observed: 2 failed, 20 passed. The request omitted the configured thresholds,
and changing thresholds for the same symbol did not issue a new owned request.

### GREEN Evidence

```bash
mvn -pl apps/api \
  -Dtest=BacktestServiceTest,BacktestControllerTest,ShortTermTechnicalSignalEvaluatorTest,ShortTermTradePlanServiceTest \
  test
npm --prefix apps/web-react test -- ShortTermPage.test.tsx
npm --prefix apps/web-react run build
git diff --check
```

Result: PASS. Backend 31/31; frontend 22/22, including configured-threshold
behavior, explicit defaults, trailing-stop weighted exit, and deferred
same-symbol/different-rule request ownership; TypeScript/Vite production build
passed with 1,673 modules transformed; diff check produced no output.

### Re-review Concerns

- No blocking concerns.
- The trailing-stop ordering is deliberately conservative at daily-bar
  resolution and is documented in the report methodology. It does not claim
  unavailable intraday sequencing.
