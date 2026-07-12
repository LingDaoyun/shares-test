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
