# Task 2 Report: Golden-Cross Tiers in Legacy Short-Term Report

## Scope

- Added `goldenCross` to the legacy technical snapshot.
- Wired `ShortTermGoldenCrossAnalyzer` and completed-bar handling into short-term K-line analysis.
- Ranked verified K-line candidates by golden-cross tier before legacy technical, structural, action, and final-score orderings.
- Required a recent completed confirmed cross before `RIGHT_EARLY_ADD`; approaching/forming crosses remain observation-only and cannot be tail-upgraded to `LIGHT_TRIAL`.
- Added golden-cross evidence, risk disclosure, and reviewed-sample coverage wording.
- Updated only the existing ADD-oriented fixtures that needed a confirmed recent cross, preserving their business intent.

## TDD Evidence

- RED: `mvn -pl apps/api -Dtest=ShortTermServiceTest test` failed at test compilation because `ShortTermTechnicalSnapshot.goldenCross()` did not exist.
- GREEN: `mvn -pl apps/api -Dtest=ShortTermServiceTest test` passed: 27 tests, 0 failures, 0 errors.

## Regression Evidence

`mvn -pl apps/api -Dtest=ShortTermGoldenCrossAnalyzerTest,ShortTermServiceTest,ShortTermScanJobServiceTest test`

- Passed: 39 tests, 0 failures, 0 errors, 0 skipped.
- Expected warning logs occurred in tests that deliberately simulate quote-fetch failures and interrupted queued scan work.

## Independent Review Fixes

- Reordered FORMING/APPROACHING handling so valuation, market state, financial completeness/quality, chase risk, and constructive-volume gates are evaluated before golden-cross watch advice.
- Missing or below-threshold fundamentals now return `DATA_REVIEW`; insufficient volume returns `WAIT_CONFIRM`, so golden-cross evidence cannot imply that financials passed when they are absent.
- Added service-level same-day boundary coverage using the fixed 2026-07-07 14:59 Shanghai clock: `TradingClockService.isCompletedDailyBar(...)` is false, the latest cross is `FORMING`, and it remains non-executable.

### Review-Fix TDD Evidence

- RED: the unfinished-bar/no-financial fixture returned `WATCH_RIGHT_SIDE` instead of `DATA_REVIEW`; the low-volume FORMING fixture also returned `WATCH_RIGHT_SIDE` instead of `WAIT_CONFIRM`.
- GREEN: `mvn -pl apps/api -Dtest=ShortTermServiceTest test` passed: 29 tests, 0 failures, 0 errors.
- Regression rerun: `mvn -pl apps/api -Dtest=ShortTermGoldenCrossAnalyzerTest,ShortTermServiceTest,ShortTermScanJobServiceTest test` passed: 41 tests, 0 failures, 0 errors, 0 skipped.

## Workspace Safety

- No files were staged or committed.
- Existing uncommitted work in `ShortTermService.java` and `ShortTermServiceTest.java` was preserved.

---

# Task 2 Report: Scheduled Overnight Timing and Refreshable Settings

## Scope

- Added refresh-by-read `ShortTermAutomationSettings` backed by Spring `Environment`.
- Added bounded boolean, integer, decimal, time, zone, and cron parsing with warning-and-default fallback.
- Added the exact `OvernightRuleSet` configuration value object.
- Replaced the executable ordinary-stock checkpoint with `TAIL_ENTRY_1445_1456`, valid only from 14:45:00 through 14:56:59 on trading days.
- Added current/next/offset trading-day helpers using the existing official holiday and weekend rules.
- Updated the existing V2 short-right-side working-tree logic and tests so `POST_CLOSE_1520` is rejected and only `TAIL_ENTRY_1445_1456` can publish an executable action.

## TDD Evidence

- RED: `mvn -pl apps/api -Dtest=TradingClockServiceTest,ShortTermAutomationSettingsTest,ShortRightSideStrategyServiceTest,V2SignalControllerTest test`
- RED result: compilation failed because `ShortTermAutomationSettings`, `OvernightRuleSet`, and the trading-day helpers did not exist.
- Intermediate GREEN run exposed one stale clock warning assertion after the production copy was corrected.
- GREEN: the same focused command passed 29 tests with 0 failures and 0 errors.

## Commit

- `740399e fix: align short-term timing with executable tail window`

## Deliberately Uncommitted Task 2 Deltas

- `ShortRightSideStrategyService.java` and `ShortRightSideStrategyServiceTest.java` were already untracked as part of pre-existing V2 work. Their Task 2 substitutions remain in the working tree so the entire unrelated V2 files were not smuggled into this commit.
- `V2SignalControllerTest.java` already contained a large unrelated uncommitted V2 test expansion. Its default mocked checkpoint was updated in the working tree, but the file was not staged because the small Task 2 line cannot be committed independently of that added block.

## Verification and Concerns

- `rg` found no `POST_CLOSE_1520`, `15:20-15:30`, `15:20 后`, or `SHORT_TERM_DECISION_START` references in production sources.
- `git diff --check` passed.
- The committed clock/settings boundary is independently buildable. The V2 checkpoint integration is verified in the current working tree but remains dependent on the owner of the pre-existing untracked V2 implementation committing that work as a coherent unit.

---

# Task 2 Review-Finding Closure

## Fixes

- Legacy `ShortTermService` now requires the authoritative
  `TAIL_ENTRY_1445_1456` checkpoint before `ADD` or `LIGHT_TRIAL` can be
  published. Closing-auction evidence remains available for research and
  replay, but 14:57 and later resolve to non-entry advice.
- The ordinary-stock entry boundary is shared through
  `TradingClockService`: 14:45:00 through 14:56:59, inclusive.
- Refresh-by-read Nacos overrides are bounded to that interval. Out-of-window,
  malformed, or inverted ranges log a warning and fall back to the approved
  defaults; `15:20` cannot become executable.
- Closing auction is explicitly classified as a research-only window.
- V2 short-right-side logic now consumes the shared checkpoint constant.

## TDD Evidence

- RED command:
  `mvn -pl apps/api -Dtest=ShortTermExecutionWindowTest,ShortTermAutomationSettingsTest,TradingClockServiceTest test`
- RED result: 14 tests ran with 6 expected failures covering the 14:56 default
  precision, `15:20`, inverted overrides, 14:57/15:00 advice upgrades, and the
  closing-auction decision flag.
- GREEN result: the same command passed 14 tests with 0 failures and 0 errors.
- The complete legacy service regression initially found four old 14:59
  assertions that still expected `ADD` or `LIGHT_TRIAL`; those assertions were
  corrected to retain the candidate as research while returning `WAIT`.

## Verification

- Original Task 2 suite plus timing tests passed: 33 tests, 0 failures,
  0 errors.
- A detached clean worktree created from the exact staged tree passed:
  `TradingClockServiceTest,ShortTermAutomationSettingsTest,ShortRightSideStrategyServiceTest,V2SignalControllerTest,ShortTermExecutionWindowTest,ShortTermServiceTest`
  with 54 tests, 0 failures, 0 errors.
- The same combined suite passed against the preserved dirty working tree:
  69 tests, 0 failures, 0 errors.
- `git diff --check` and `git diff --cached --check` passed.

## Commits

- `5689ce1 feat: checkpoint buildable v2 strategy bundle`
  - Contains the coherent V2 strategy/API/test dependency closure only.
  - Independently verified in a clean worktree: 26 tests, 0 failures,
    0 errors.
- `deee74d fix: fence short-term advice to executable tail window`
  - Contains only the shared boundary/configuration changes, legacy advice
    gate, V2 constant reuse, and focused timing tests.

## Workspace Ownership

- `ShortTermService.java` and `ShortTermServiceTest.java` had substantial
  pre-existing golden-cross work. The commit was built from the repository
  baseline with only the review-fix hunks, while the equivalent four-parameter
  gate and compatible tests remain in the dirty working-tree evolution.
- Existing unrelated backend, frontend, report, broker, and strategy-decision
  changes were not staged.
- This report remains an append to the already modified report file and was
  not included in either code commit.

## Concerns

- The legacy 14:57-15:00 evidence model is now research-only. Replacing it with
  a live 14:45-14:56 execution snapshot belongs to a later task; no Task 3
  scheduler, snapshot controller, or frontend work was performed here.

---

# Task 2 Final Point-in-Time Review Closure

## Fixes

- Added a point-in-time fence to legacy `ShortTermService`: executable advice
  requires evidence from the current market date whose parsed minute is not
  later than the authoritative server decision instant.
- Cached or replayed future evidence, previous-trading-day evidence, malformed
  timestamps, and missing timestamps remain available for research/history but
  cannot publish `ADD` or `LIGHT_TRIAL`.
- Changed the executable clock boundary to the half-open interval
  `[14:45:00, 14:57:00)`. This preserves
  `14:56:59.999999999` as executable while excluding `14:57:00`.
- Kept the approved default and user-facing end as `14:56:59`; valid Nacos
  overrides may use any subsecond value strictly before `14:57:00`.
- Corrected the old test fixture that paired a 14:56:59 decision with future
  15:00 evidence.

## TDD Evidence

- RED command:
  `mvn -pl apps/api -Dtest=ShortTermExecutionWindowTest,TradingClockServiceTest,ShortTermAutomationSettingsTest test`
- RED result: 16 tests ran with 5 expected failures:
  - the last executable nanosecond was rejected;
  - a valid subsecond Nacos end was rejected;
  - future 14:57 `CONFIRMED` evidence incorrectly produced `ADD`;
  - previous-trading-day evidence incorrectly produced `ADD`;
  - the corrected same-instant evidence test could not execute.
- GREEN result after the shared clock and evidence fence: 16 tests passed with
  0 failures and 0 errors.
- A direct future `WATCH` regression test also passed and proves the same fence
  blocks `LIGHT_TRIAL`.

## Verification

- Preserved dirty working tree:
  `TradingClockServiceTest,ShortTermAutomationSettingsTest,ShortRightSideStrategyServiceTest,V2SignalControllerTest,ShortTermExecutionWindowTest,ShortTermServiceTest`
  passed 72 tests with 0 failures and 0 errors.
- Detached clean worktree created from the exact staged tree passed the same
  suite with 57 tests, 0 failures and 0 errors.
- `git diff --check` and `git diff --cached --check` passed before commit.

## Commit

- `dff75fa fix: fence tail evidence by decision timestamp`

## Workspace Ownership

- Only the Task 2 point-in-time hunks from `ShortTermService.java` were staged
  against the committed three-parameter baseline. The equivalent
  four-parameter golden-cross implementation remains in the user's dirty
  working tree.
- Existing unrelated backend, frontend, broker, test, documentation, and report
  changes were not staged or reverted.
- This report was appended in the already dirty report file and was not mixed
  into the focused code commit.

## Scope Guard

- No Task 3 scheduler, snapshot controller, frontend, or later-task work was
  performed.
