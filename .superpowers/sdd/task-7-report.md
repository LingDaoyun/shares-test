# Task 7 Report: Independent Trade Review Page

## Status

Implemented the independent `/trade-review` workspace, navigation entry, monotonic mutation-response upsert, scan table, sticky detail pane, outcome review, and accessible fill management flow.

## Build Evidence

Ran from `/Users/mac/Documents/shares-test`:

```text
npm run build --prefix apps/web-react
```

Result: exit code 0. TypeScript completed and Vite built the production bundle successfully (`1668 modules transformed`, `built in 1.50s`).

Also ran:

```text
git diff --check
```

Result: exit code 0 with no whitespace errors, including the pre-existing unrelated documentation change.

## Interaction Checklist

- Added lazy `/trade-review` route, page metadata, navigation item/count, responsive `lg:5` / `xl:10` navigation grid, and `aria-current` on the active module.
- Added ALL, PLANNED, HOLDING, CLOSED, and CANCELLED filters with counts. Selection survives list refresh when the case remains valid and otherwise moves to the first valid row.
- Added a real button in each case identity cell while retaining row-click selection. Mobile selection scrolls to the stacked detail pane.
- Loads full selected detail and serially hydrates remaining summaries so T1/T5/T20 values become scan-ready. All list, detail, and mutation responses pass through the Task 6 monotonic store merge policy.
- Shows recommendation source/action/time/price, case status, position, weighted cost, latest price, realized/unrealized/total gross profit, execution deviation, recommendation outcomes, execution outcomes, sources, market times, statuses, and warnings.
- Keeps `收益未计佣金、印花税、分红和送转股` visible beside profit totals.
- Recommendation evidence is collapsed by default and rendered as readable formatted JSON.
- Added add/edit fill modal with BUY/SELL segmented control, `datetime-local`, price step `0.01`, quantity step `100`, local validation, ISO timestamp submission, backend error messages, focus trap, Escape close, focus return, and body-scroll restoration.
- Added explicit delete confirmation, planned-case cancellation, manual outcome refresh, loading/error live regions, and success/error toasts.

## Files

- `apps/web-react/src/App.tsx`
- `apps/web-react/src/components/layout/NavRail.tsx`
- `apps/web-react/src/components/layout/pageMeta.ts`
- `apps/web-react/src/index.css`
- `apps/web-react/src/pages/TradeReviewPage.tsx`
- `apps/web-react/src/store/tradeFeedbackStore.ts`
- `.superpowers/sdd/task-7-report.md`

## Accessibility And Mobile Self-Review

- Native buttons cover navigation, case selection, filters, icon commands, modal actions, and BUY/SELL selection. Icon-only commands include `aria-label` and `title` tooltips.
- Loading states use `role="status"`; request and validation failures use `role="alert"` and toasts.
- The modal uses `role="dialog"`, `aria-modal`, labelled heading, trapped Tab focus, Escape handling, focus return, and a mobile bottom-sheet layout with `92dvh` scrolling.
- At widths below `xl`, list and detail stack with `min-w-0`; the data table keeps horizontal scrolling and the filter control scrolls horizontally without shrinking labels.
- At `xl`, the page uses an unframed flexible list plus a `380-460px` sticky detail pane with its own viewport-bounded scroll.
- Long source, payload, warning, and outcome text uses wrapping or bounded overflow. No page-level fixed action bar competes with the modal.
- Live browser QA was intentionally not run because the task assigns it to the main controller; this review is source-level as requested.

## Concerns

- There is no frontend test harness, so verification is TypeScript/Vite build plus source-level interaction review.
- The trade-case summary contract omits outcomes. The page therefore performs one deduplicated, serial detail request per summary to populate all T1/T5/T20 table cells; a future summary API that includes compact outcomes would remove this N+1 read pattern.
- The frontend was not exercised against a running backend in this task. Backend validation and outcome-refresh failures are surfaced through inline alerts and existing toast conventions.
- The unrelated modified `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remains untouched and will not be staged.

## Review Fixes At HEAD 1731593

- Successful outcome reconciliation now advances and saves the locked parent case version. The timestamp is forced to be strictly newer even when the clock has not advanced.
- `TradeCaseSummary` now includes compact outcomes. The list endpoint fetches outcomes for all filtered case IDs with one bulk repository query, maps them by case, and reuses CURRENT prices for the existing ledger summaries. No per-case outcome read was added.
- The page no longer hydrates every summary through detail requests. Only the selected summary loads its detail.
- Fill quantity accepts any positive integer with `min="1"` and `step="1"`.
- Structured backend field messages are shown in stable field-name order before top-level text. Non-API/parser failures use a safe generic message.
- `datetime-local` values display Asia/Shanghai wall time with seconds and submit through explicit `+08:00` parsing with `step="1"`.
- Scan cells distinguish `未评估`, `待成熟`, `数据不可用`, and matured returns.
- Refresh, cancel, add, edit, and delete share one tokenized selected-case mutation gate. All related controls disable while it is active, and only the matching operation token can clear it.
- Existing focus trapping, Escape handling, focus restoration, scroll locking, responsive table/detail layout, alerts, labels, and tooltips remain in place. Browser visual QA remains deferred to Task 8.

## RED Evidence

Ran:

```text
mvn -pl apps/api -Dtest=TradeOutcomeServiceTest,TradeFeedbackControllerTest test
```

After adding the missing bulk repository signature so the behavioral tests could compile, the focused run failed as expected: `Tests run: 23, Failures: 2, Errors: 0, Skipped: 0`.

- `advancesAndSavesTheLockedCaseVersionAfterEverySuccessfulReconciliation`: refreshed `updatedAt` remained equal to the prior version.
- `listsCompactOutcomesWithOneBulkQueryAndNoPerCaseOutcomeReads`: list JSON had no summary `outcomes` value.

## GREEN Evidence

Focused backend regression run:

```text
mvn -pl apps/api -Dtest=TradeOutcomeServiceTest,TradeFeedbackControllerTest test
```

Result: `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0`. This includes service-level monotonic version saves, refresh-response/list-version equality, compact summary outcomes, retained summary latest price, exactly one bulk outcome repository call, and no per-case outcome repository reads from the list endpoint.

Full backend suite:

```text
mvn -pl apps/api test
```

Result: `Tests run: 202, Failures: 0, Errors: 0, Skipped: 0`.

Frontend production build:

```text
npm run build --prefix apps/web-react
```

Result: exit code 0; TypeScript and Vite completed with `1669 modules transformed`, `built in 1.55s`.

Pure helper/source checks used Node 24 type stripping and source scans. Verified:

```text
2026-07-13T01:35:42Z -> 2026-07-13T09:35:42 -> 2026-07-13T01:35:42.000Z
fields { quantity, price } -> 价格错误；股数错误
raw parser Error -> 请求失败，请稍后重试
```

Source scans found no old background hydration, 100-share divisibility, browser-zone offset conversion, or `min/step="100"` code.

## Remaining Concerns After Review

- There is still no frontend test harness; frontend verification is the production build, executable pure-helper checks, and source-level interaction review.
- Live backend/frontend and visual browser QA are intentionally deferred to Task 8.
- The unrelated modified `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` remained untouched and is excluded from the Task 7 commit.

## Follow-up Fix: Stale Selection And Cancellation Confirmation

### RED Evidence

Ran from `/Users/mac/Documents/shares-test` before adding the selection-aware completion gate:

```text
node apps/web-react/scripts/tradeReviewSelectionGate.check.mjs
```

Result: exit code 1. The deterministic assertion failed because the prior token-only gate accepted operation `7` for case A even after case B was selected:

```text
AssertionError [ERR_ASSERTION]: a completed operation for case A must not update visible detail state after case B is selected
false !== true
```

### Fix And GREEN Evidence

- Added `shouldApplySelectedCaseOperation` and a deterministic Node check covering stale case selection, matching case selection, and superseded operation tokens.
- Detail errors are now stored by case ID and are cleared on every selection, including already-hydrated detail cases.
- Refresh, cancellation, and fill deletion only surface completion/error feedback when both the operation token and current selected case still match; successful responses still update their keyed case cache.
- Added an accessible cancellation confirmation dialog with initial focus, Tab trapping, Escape/backdrop dismissal, focus restoration, scroll locking, keyboard controls, and mobile bottom-sheet layout.

Ran:

```text
node apps/web-react/scripts/tradeReviewSelectionGate.check.mjs
npm run build --prefix apps/web-react
git diff --check
```

Results: the focused Node check exited 0; the production build exited 0 (`1669 modules transformed`, `built in 1.54s`); and `git diff --check` exited 0 with no whitespace errors.

### Changed Files

- `apps/web-react/src/pages/TradeReviewPage.tsx`
- `apps/web-react/src/lib/tradeReview.ts`
- `apps/web-react/scripts/tradeReviewSelectionGate.check.mjs`
- `.superpowers/sdd/task-7-report.md`
