# Task 6 Report: Trade Feedback Capture UI

## Status

Implemented typed trade-feedback contracts, REST client helpers, a Zustand review-case store, and the reusable compact `TradeReviewButton` control.

## Build Evidence

Ran from `/Users/mac/Documents/shares-test`:

```text
npm run build --prefix apps/web-react
```

Result: exit code 0. TypeScript completed and Vite built the production bundle successfully (`1667 modules transformed`, `built in 1.51s`).

Also ran:

```text
git diff --check
```

Result: exit code 0 with no whitespace errors.

## Changed Files

- `apps/web-react/src/types.ts`
- `apps/web-react/src/api/client.ts`
- `apps/web-react/src/store/tradeFeedbackStore.ts`
- `apps/web-react/src/components/tradefeedback/TradeReviewButton.tsx`
- `apps/web-react/src/pages/ShortTermPage.tsx`
- `apps/web-react/src/pages/TechTrackerPage.tsx`
- `apps/web-react/src/pages/MispricingPage.tsx`
- `apps/web-react/src/pages/CycleTrialPage.tsx`
- `apps/web-react/src/pages/DailySignalsPage.tsx`

## Page Mapping Self-Review

- Short Term: `SHORT_TERM` / `short-term-right-side-v2`; renders only in `CandidateDetail` beside `WatchButton`, uses `candidate.latestPrice`, `report.generatedAt`, and the selected candidate snapshot. It does not nest a control in `CandidateRow`.
- Hot Tracker: `HOT_TRACKER` / `hot-tracker-v2`; uses each stock's latest price, report generation timestamp, and stock snapshot.
- Mispricing: `MISPRICING` / `mispricing-v2`; uses each asset's latest price, report generation timestamp, and asset snapshot.
- Cycle Trial: `CYCLE_TRIAL` / `cycle-trial-v2`; uses each candidate's latest price, report generation timestamp, and candidate snapshot.
- Daily Signals: `DAILY_SIGNAL` / `daily-signal-v1`; passes no price, so the same control is visibly disabled with the `缺少实时每股价格` tooltip and never invents a stale price.

All pages prefer `todayAdvice.actionLabel` and then `todayAdvice.action`, falling back to the row action fields. Scores use `finalScore`, except Daily Signals which uses `signal.score ?? signal.confidence`. No recommendation grouping, order, or filtering logic changed.

## Concerns

There is no frontend test harness in this project, so static TypeScript/Vite verification and source-level mapping checks were used. The trade-feedback API was not exercised against a running backend in this task; error handling uses the existing toast and API error-message conventions.
