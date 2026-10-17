# Five-day Observation Implementation Plan

> **Authorization update, 2026-09-08:** after the initial source-only handoff, the user explicitly requested self-testing and deployment. That new authorization permits this follow-up's tests, fixes, builds, runtime checks and local deployment. See docs/five-day-observation-verification.md for evidence.

**Goal:** Replace the dragon-ranking entry with an evidence-based five-session observation workflow, dated plans and separately labelled fixed/risk scenario outcomes.

**Architecture:** Reuse FirstBoardBaselineService for the stock universe and board scope, FirstBoardDailyBarStore for dated bars and TradingClockService for verified market dates. The five-day scan verifies each stock's exact 25-session window before price preselection, repairs incomplete windows through the existing historical provider, and carries the same verified bars into subsequent evaluation. The new fiveday package owns immutable observation records, deterministic rules and asynchronous scan status; reuse the existing trade-feedback ledger for actual fills.

**Tech Stack:** Java/Spring Boot, PostgreSQL/Flyway, React/TypeScript and existing UI components.

**Delivery status:** Tasks 1–5 were initially delivered as untested source. The checked implementation items do not themselves prove validation; the later authorized self-test/deployment record contains actual results. No commit or push was requested.

**Verified follow-up status:** The final backend package passed 806 tests and the frontend passed 163 tests. At this documentation update, the final real scan with the 25-session repair path is still pending. Automated test results do not prove live scan completion or financial effectiveness.

## Global Constraints

- Exact domain/API records: apps/api/src/main/java/com/aistock/research/fiveday/FiveDayModels.java.
- Spec: docs/superpowers/specs/2026-09-08-five-day-observation-design.md.
- VERSION five-day-observation-v1; research values live in Rules.defaults(), not advertised as optimized.
- The initial no-verification boundary was explicitly overridden for this follow-up by the user's self-test/deployment request. Keep regression databases isolated from the real application database.
- Keep changes on codex/five-day-observation; do not commit other agents' in-progress files.

## Task 1 — Historical data adapter

- [x] EastMoneyClient.fetchFundFlowHistory(symbol, begin, end) uses the existing daykline parser; filter exact dates, reject malformed/conflicting data and preserve source URL.
- [x] EastMoneyClient.enrichDailyKLineTradingMetrics(symbol, begin, end, canonicalRows) supplements missing amount and turnover only, preserving canonical OHLCV.
- [x] Initially deliver isolated parser/merge tests as source; execute them under the later self-test authorization.

## Task 2 — Scan and rules

- [x] New FiveDayModels/FiveDayEvaluator/FiveDayService/FiveDayController. GET /api/five-day/snapshot is read-only status; POST /api/five-day/scan starts a background scan; GET /api/five-day/history reads samples; POST /api/five-day/history/refresh updates local-bar scenarios; GET /api/five-day/trade-schedule?entryDate=... provides the verified five-session plan; POST /api/five-day/attestation/{symbol} creates a trade-feedback token from the current frozen candidate.
- [x] Check the exact 25 market dates and valid OHLCV before price preselection. Reuse complete local windows without fetching historical prices; otherwise fetch and persist a complete replacement window through the existing provider. Do not splice newly adjusted prices into old gaps or alter the legacy 15-bar baseline policy.
- [x] Keep no more than eight repair jobs outstanding, preserve provider throttling, shut down each scan's worker pool, and publish “补齐/检查连续日线 x/n” progress after every completed stock.
- [x] Use the verified replacement or cached window for both 10-day price preselection and subsequent turnover/amount evaluation. Preserve the stable range-then-symbol ordering and maximum 100-stock review cap after price filtering.
- [x] Count unresolved preselection gaps separately and disclose their count and up to five examples. An empty candidate result with any unresolved data gap is FAILED and remains retryable, without saving a successful snapshot. Actual candidates may be saved while other missing histories remain disclosed; the existing all-fund-data-missing no-freeze rule remains in force.
- [x] Date-complete filtering, explicit missing fields, historical-only inputs, all parameters and coverage visible.
- [x] Background scan has one refresh lock per board/date, bounded cached state, completion timestamp and error messages. No heavy work in polling GET.

## Task 3 — Observation persistence and outcomes

- [x] New FiveDayHistoryService and FiveDayOutcomeCalculator with V5__five_day_observation.sql. Save immutable snapshots before the entry cutoff, read history, separately calculate fixed and risk exits, and preserve unavailable/delayed cases.
- [x] History is a descriptive forward observation sample; no success probability or claim of portfolio backtest.
- [x] Initially deliver calendar, unavailable-day, future-cutoff, adjustment-anchor and cost tests as source; execute them under the later self-test authorization.
- [x] Under the later self-test authorization, verify complete-window reuse, internal-gap repair, one-window QFQ replacement, fresh-bar preselection, unresolved-gap disclosure/no empty freeze, and the eight-job bound; include them in the passing 806-test backend package run.

## Task 4 — UI and actual trade record integration

- [x] New FiveDayPage and scoped components/types/API file; use the existing /potential-leader route and add /five-day alias; navigation reads 五日观察.
- [x] Show candidate/control filters, rule explanation, dated plans, coverage, raw price/turnover/fund evidence and fixed/risk outcome history.
- [x] Source FIVE_DAY uses existing attestation and real-fill recording. Trade review shows verified plan from first fill and does not label old recommendation T5 as the new five-day result.
- [x] Disable the legacy dragon capture schedule by default; new close capture and local-history refresh use the new strategy.

## Task 5 — User handoff

- [x] Update README with the route, research assumptions, migrations and user-run acceptance scenarios.
- [x] Complete the initial source-only handoff as 未由 Codex 测试 before the later self-test/deployment authorization; keep runtime results separate from financial effectiveness.
- [x] Record the later authorized automated results: backend 806 and frontend 163 tests passed.
- [ ] Complete and record the final real scan after deployment of the 25-session repair path; do not infer completion from the automated tests.

最终运行验收补充：真实主板扫描、候选分组、历史读取/重算、重复扫描及重启恢复已完成；完整覆盖与限制见 [自测部署记录](../../five-day-observation-verification.md)。
