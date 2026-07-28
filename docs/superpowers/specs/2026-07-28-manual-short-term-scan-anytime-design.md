# Manual Short-Term Scan Anytime Design

## Goal

Allow a user-triggered short-term scan to produce an executable result at any
time of the current market day instead of requiring completion during the
14:45-14:56 closing decision window.

## Scope

The change applies only to manual scans handled by
`ShortTermFinalResultGate.evaluateManual`.

Scheduled preselection, scheduled final selection, and readiness checks keep
their existing timing and deadline rules.

## Behavior

Manual evaluation no longer checks:

- whether completion time is inside the closing decision window;
- whether the report trading-session snapshot marks the closing decision
  window as active;
- whether the current market date is a closed day solely as a window gate.

Manual evaluation continues to require:

- a valid report and completion timestamp;
- report data from the current market date;
- reliable market coverage of at least 90%;
- a data cutoff no later than the decision completion time;
- quotes fresh within the configured freshness duration.

Passing reports return `FINAL_READY` when candidates exist and `NO_TRADE` when
there are no candidates. `MANUAL_OUTSIDE_DECISION_WINDOW` is no longer emitted.

## Architecture

`evaluateManual` resolves the current market date and delegates directly to
the shared quality validation with scheduled-deadline enforcement disabled.
`evaluateScheduled` remains unchanged and continues to enable scheduled
deadline enforcement.

This keeps timing policy isolated by invocation type while preserving one
shared implementation for data quality.

## Error Handling

Manual scans still return `DATA_BLOCKED` for missing reports, wrong-date data,
low or unreliable coverage, future cutoffs, and stale quotes. Unexpected scan
failures continue to be exposed by `ShortTermScanJobService` as `FAILED`.

## Testing

Regression tests will verify:

- a manual scan before the closing window can become ready;
- a manual scan after the closing window can become ready;
- scheduled scans still fail after their deadline;
- wrong-date, stale, and unreliable manual data remain blocked;
- a completed manual scan job exposes a ready result rather than
  `MANUAL_OUTSIDE_DECISION_WINDOW`.

## Acceptance Criteria

- A manual scan completed at 15:51 with fresh same-day data is not blocked by
  the decision window.
- No manual result contains `MANUAL_OUTSIDE_DECISION_WINDOW`.
- Scheduled timing constraints and all data quality constraints remain active.
- Focused and full backend tests pass.
