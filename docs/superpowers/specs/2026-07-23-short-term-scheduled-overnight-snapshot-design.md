# Short-Term Scheduled Overnight Snapshot Design

## Goal

Turn the short-term page from an on-demand long-running scan into a trading-day scheduled decision surface.

On a normal A-share trading day, a user opening `/#/short-term` at 14:55 must immediately receive the completed result calculated from that day's market data. The page must not wait for a new full-market scan and must never present a previous trading day's snapshot as today's executable advice.

This design also changes the short-term holding model to an overnight T+1 strategy: enter before the regular closing call auction, normally exit on the next trading day, and hold no later than the second trading day.

## Success Criteria

- A full-market preselection starts at 14:30 Asia/Shanghai on trading days.
- A final live refresh starts at 14:48 and publishes the final snapshot no later than 14:53 under normal source conditions.
- A 14:54 readiness guard records an explicit blocked state if a valid final snapshot is unavailable.
- Opening the short-term page reads one persisted latest-snapshot endpoint and does not automatically submit a scan job.
- A completed scan with zero executable candidates returns `NO_TRADE`; it does not force a recommendation.
- Missing, stale, incomplete, or wrong-date data returns `DATA_BLOCKED`; it does not fall back to yesterday's report.
- Every displayed candidate includes a deterministic T+1/T+2 trading plan with entry, take-profit, stop-loss, scenario, position, and deadline rules.
- Scheduled snapshots survive API container restarts through the existing Docker H2 file volume or a configured PostgreSQL database.

## Scope

### Included

- Scheduled preselection, final refresh, and readiness guard.
- Persistent scheduled scan snapshots.
- Latest scheduled snapshot API.
- Page-load behavior and scheduled-result status UI.
- Deterministic overnight trading plans.
- T+1/T+2 validation and outcome metrics.
- Correction of the current generic 14:57-15:00 and 15:20 execution semantics for ordinary A shares.
- Nacos configuration under the existing `ai-stock-api.yml` data ID.

### Not Included

- Automatic order submission to a broker.
- Intraday continuous auto-trading.
- Replacing the existing explicit manual `scan-jobs` endpoint.
- Treating one successful stock outcome as sufficient evidence to change strategy weights.
- A full exchange-calendar service for every future year. The initial implementation uses the existing trading clock plus same-day market-data freshness as the final authority.

## Recommended Architecture

### 1. Scheduled scan orchestration

Add a dedicated scheduler and orchestration service instead of calling the existing in-memory manual job queue.

- `ShortTermScanScheduler` owns the three cron triggers.
- `ShortTermScheduledScanService` owns idempotency, state transitions, scan execution, validation, and snapshot publication.
- `ShortTermScheduledScanProperties` reads refreshable configuration from Nacos.
- Scheduled scans have a dedicated single-worker execution lane so a burst of manual scans cannot reject or delay the 14:48 final run.

The scheduled run key is deterministic:

`tradeDate + stage + parameterFingerprint`

Only one instance may own a run key. A database-backed claim prevents duplicate execution if the API is later deployed with multiple instances. A failed run may be retried by updating the same run record while retaining attempt timestamps and failure text.

### 2. Two-stage data flow

#### PRESELECT at 14:30

1. Fetch the live full A-share quote universe.
2. Enforce tradable common-share, ST, liquidity, unstable-industry, source coverage, and quote-date gates.
3. Calculate market sentiment and hot directions.
4. Rank preliminary candidates and retain the configured K-line review set, default 60.
5. Fetch K-lines and financial context needed for stable factors.
6. Persist a `PRESELECT_READY` snapshot and its candidate evidence.

The preselection is research-only and cannot produce an executable buy instruction.

#### FINAL at 14:48

1. Load the same-day preselection snapshot.
2. Refresh full-market quotes for current sentiment and coverage.
3. Refresh quotes and intraday points for the preselected symbols.
4. Evaluate the actionable tail window from 14:45 through the latest available minute no later than 14:56.
5. Recompute tail strength, right-side maturity, risk gates, ordering, and overnight trading plans.
6. Attest recommendations and persist either `FINAL_READY`, `NO_TRADE`, or `DATA_BLOCKED`.

The final result records both `startedAt` and `dataCutoffAt`. A run can finish at 14:53 while clearly showing the latest market minute used in the decision.

#### READINESS_GUARD at 14:54

The guard does not start a third full scan. It verifies that a same-day final record exists, is terminal, uses same-day quotes, and passes coverage and freshness gates. If not, it publishes `DATA_BLOCKED` with a machine-readable reason and a short user-facing explanation.

## Trading-Time Semantics

The strategy's ordinary A-share execution window is 14:45-14:56. The system must complete the actionable decision before 14:57.

- 14:45-14:56: executable tail observation and entry window.
- 14:57-15:00: closing call auction; data may be saved for later validation but cannot retroactively create a 14:55 recommendation.
- 15:05-15:30: never treated as a generic ordinary A-share entry window. Any eligible after-hours product requires a separately identified trading capability and is outside this feature.
- The existing `SHORT_TERM_DECISION_START = 15:20`, `TAIL_CONFIRM_TIME = 14:57`, and related user copy must be removed or redefined for this strategy.

On a weekend or configured exchange holiday, the scheduler records no executable run. Same-day quote freshness remains the safety check for unexpected closures or incomplete holiday configuration.

## Snapshot Persistence

Add `short_term_scheduled_snapshot` to `schema.sql` with these logical fields:

- `snapshot_key`: deterministic primary key for date, stage, and parameters.
- `trade_date`: A-share trading date.
- `stage`: `PRESELECT`, `FINAL`, or `READINESS_GUARD`.
- `status`: `RUNNING`, `PRESELECT_READY`, `FINAL_READY`, `NO_TRADE`, `DATA_BLOCKED`, or `FAILED`.
- `parameter_fingerprint` and `parameters_json`.
- `report_json`: canonical serialized report, nullable while running or blocked before a report exists.
- `data_cutoff_at`, `started_at`, `completed_at`, and `updated_at`.
- `attempt_count`, `message`, and `blocked_reason`.

The repository returns the latest same-day scheduled snapshot only. Historical rows remain available for replay and outcome analysis. The Docker deployment already maps `/data` to the `api-data` volume and uses a file-backed H2 URL, so these rows survive container replacement. PostgreSQL uses the same JPA contract.

## API Contract

Add:

`GET /api/short-term/scheduled-snapshots/latest`

Response fields:

- `tradeDate`
- `stage`
- `status`
- `message`
- `dataCutoffAt`
- `completedAt`
- `parameterFingerprint`
- `report`
- `blockedReasons`

The endpoint does not silently search previous dates. Before the daily run it returns a same-day waiting state. After the readiness deadline it returns `DATA_BLOCKED` when no valid final result exists.

Keep the existing manual endpoints:

- `POST /api/short-term/scan-jobs`
- `GET /api/short-term/scan-jobs/{jobId}`

Manual results are marked `MANUAL`, remain explicit, and do not overwrite the scheduled default snapshot.

## Overnight Trading Plan

Add a structured `ShortTermTradePlan` to every final candidate. It is calculated by a deterministic service from the final quote, technical snapshot, recent support, ATR, entry zone, and risk state. AI prose is not allowed to invent prices or deadlines.

The plan contains:

- strategy label: `隔夜超短波段`
- entry window and valid-until time
- reference entry price and entry zone
- maximum position as a fraction of the short-term allocation, default one third
- first take-profit price and reduction ratio, default around +3% and 50%
- second take-profit price, default around +5%
- hard stop price
- trailing-stop condition after the first target
- normal exit deadline on T+1 at 14:50
- optional extension conditions for retaining at most half the position to T+2
- mandatory final exit on T+2 at 14:50
- high-open, flat-open, and low-open action scenarios
- analysis factors and invalidation evidence

Take-profit and stop percentages are volatility-aware. ATR-derived values are clamped by configurable floors and ceilings so a low-volatility bank and a high-volatility technology stock do not receive the same absolute rule. The UI always displays concrete prices as well as percentages.

Because A shares use T+1 settlement for ordinary stock selling, the entry plan must explicitly warn that an adverse same-day move cannot be exited before the next trading day. This overnight gap risk is part of position sizing, not an afterthought.

## Page Behavior

On mount, `ShortTermPage` calls only the latest scheduled snapshot endpoint.

- `FINAL_READY`: render the full-width candidate list and allow candidate detail overlays.
- `NO_TRADE`: render the completed scan summary and a clear no-trade result.
- `PRESELECT_READY` or `RUNNING`: show progress and label the result as non-executable.
- `DATA_BLOCKED` or `FAILED`: hide actionable buy labels and show the exact data reason.
- No same-day record: show the next configured task time, without starting a scan.

The header displays the trade date, data cutoff, completed time, scheduled/manual provenance, source coverage, and strategy version.

The detail overlay puts `隔夜交易纪律` before research details. It shows entry timing, take-profit prices, hard stop, T+1 deadline, T+2 absolute deadline, and next-day open scenarios in a compact high-contrast panel. It does not rely on scattered prose in `entryRules` and `exitRules`.

Changing threshold fields does not mutate the scheduled result. The user must press `重新扫描` to start an explicit manual job, and the UI labels that report as manual.

## Configuration

The base Nacos data ID is `ai-stock-api.yml` under group `AI_STOCK` unless deployment configuration overrides the group.

```yaml
research:
  short-term:
    schedule:
      enabled: true
      zone: Asia/Shanghai
      preselect-cron: "0 30 14 * * MON-FRI"
      final-cron: "0 48 14 * * MON-FRI"
      readiness-cron: "0 54 14 * * MON-FRI"
      final-deadline: "14:53:59"
      freshness-seconds: 180
    overnight:
      entry-start: "14:45"
      entry-end: "14:56"
      normal-exit-time: "14:50"
      max-holding-trading-days: 2
      max-position-ratio: 0.3333
      first-target-floor-percent: 2.5
      first-target-cap-percent: 4.0
      second-target-floor-percent: 4.5
      second-target-cap-percent: 7.0
      stop-floor-percent: 2.5
      stop-cap-percent: 4.5
```

Runtime refresh may change future runs. A running or completed snapshot retains the exact parameter JSON and fingerprint used when it started.

## Failure Handling

- External source retries and source rotation remain bounded; a scheduled run cannot retry past its decision deadline.
- Market coverage below the existing 90% execution threshold blocks actionable advice.
- Wrong-date quotes, stale intraday minutes, missing preselection, serialization failure, or database publication failure produce an explicit terminal failure or blocked state.
- `report_json` is published in the same transaction as the terminal status so readers never observe `FINAL_READY` without its report.
- An empty candidate list after all valid gates is `NO_TRADE`, not `FAILED`.
- The readiness guard never copies a previous day's successful report into today's row.
- Structured logs include the run key, stage, duration, coverage, data cutoff, status, and root failure reason. API keys and raw credentials are never logged.

## Validation and Feedback

The existing 20-day right-side backtest shown on the short-term page is not suitable as the primary validation for this strategy. Add T+1 and T+2 outcome horizons and display:

- sample count and sample period
- positive-return rate after estimated costs
- average and median return
- maximum run-up and drawdown
- first-target, second-target, hard-stop, and time-stop trigger rates
- gap-down frequency
- result split by `FINAL_READY` action state and market-sentiment regime

Actual user fills continue to be the strongest feedback evidence. A recommendation without a recorded fill is evaluated as a model outcome, not treated as the user's position. No factor weight changes automatically until the configured minimum sample count and validation gates are satisfied.

## Test Strategy

### Backend

- Scheduler triggers the correct stage at injected Shanghai-market times.
- Weekend, holiday, wrong-date quote, and insufficient-coverage cases cannot publish executable advice.
- Run-key claiming prevents duplicate scheduled execution.
- Manual job saturation does not block the scheduled worker.
- State transitions are atomic and the latest endpoint never returns a previous date.
- Snapshots deserialize after application-context restart against a file-backed test database.
- `NO_TRADE` and `DATA_BLOCKED` remain distinct.
- Trading-plan prices, ATR clamps, T+1/T+2 deadlines, and high/flat/low-open scenarios are deterministic.
- Ordinary A-share decision copy contains no generic 15:20 buy checkpoint.

### Frontend

- Page mount performs a latest-snapshot `GET` and no scan-job `POST`.
- Each snapshot status renders the correct actionable or blocked state.
- Scheduled and manual reports are visibly distinguished.
- The overlay presents trading discipline before research evidence.
- Stale or previous-date responses cannot show add/buy controls.
- Desktop and mobile layouts preserve full-width lists and accessible overlays.

### End-to-end

- Simulate 14:30, 14:48, 14:54, and 14:55 with an injected clock and deterministic market fixtures.
- Verify Docker restart retains the final snapshot through the `api-data` volume.
- Verify `http://127.0.0.1:5176/#/short-term` renders the prepared result without a foreground scan.

## Rollout

1. Add persistence and latest-snapshot reads while retaining the current manual flow.
2. Add scheduler and two-stage orchestration behind `schedule.enabled=false` by default in local code.
3. Add structured overnight plans and T+1/T+2 tests.
4. Switch page mount to scheduled snapshots.
5. Publish the approved Nacos configuration with `enabled=true`.
6. Run a dry trading-day rehearsal, inspect timings and source load, then enable normal daily publication.

If the final stage cannot reliably complete by 14:53 in the rehearsal, reduce final-stage work by reusing immutable preselection evidence. Do not move the actionable decision past 14:56.
