# Short-Term Golden-Cross Priority Design

Date: 2026-07-22  
Status: Approved for implementation planning

## 1. Purpose

Improve the short-term right-side strategy by recognizing a recent MA5-over-MA10 golden cross as an early trend signal, while preserving the existing liquidity, market-regime, valuation-context, fundamental-floor, freshness, and tail-confirmation controls.

The strategy uses two layers:

1. Confirmed golden-cross candidates receive the highest eligible ranking priority.
2. Approaching-cross candidates remain visible as watch candidates but cannot receive an executable add action before confirmation.

The golden cross is a prioritization and confirmation signal. It never overrides a hard risk gate.

The first implementation uses rule version `short-golden-cross-v1.0.0`. The version is included in recommendation evidence and decision replay payloads.

## 2. Scope

This change applies only to the short-term right-side module and its V2 short-right-side compatibility output.

It does not change:

- the long-term value strategy;
- the cycle-trial strategy;
- the all-A-share quote universe and common-share eligibility rules;
- the market-coverage and quote-freshness gates;
- the rule that intraday or tail data cannot be replaced with cached recommendations;
- the post-close and next-session execution rules.

Golden-cross coverage must be disclosed as K-line-reviewed coverage. The UI and API must not imply that every A-share received a golden-cross calculation when the configured K-line review limit is smaller than the eligible quote pool.

## 3. Definitions

### 3.1 Moving averages

For a completed daily bar at trading day `t`:

```text
MA5(t)  = average(close[t-4 ... t])
MA10(t) = average(close[t-9 ... t])
MA20(t) = average(close[t-19 ... t])
```

### 3.2 Confirmed golden cross

A golden cross occurs on trading day `t` when:

```text
MA5(t) > MA10(t)
MA5(t-1) <= MA10(t-1)
```

The cross is recent when it occurred within the latest three completed trading bars, including the latest completed bar.

### 3.3 Approaching cross

A candidate is approaching a cross when all conditions hold:

- `MA5 <= MA10` on the latest completed bar;
- the signed normalized spread `(MA5 - MA10) / MA10 * 100` is between `-0.8%` and `0%`;
- MA5 has a positive three-bar slope;
- the MA5-to-MA10 spread has narrowed over the latest three completed bars;
- no confirmed cross occurred in the latest three completed bars.

The `0.8%` spread is an internal versioned default. It is not added to the existing public request parameters in the first implementation.

### 3.4 Established cross

When `MA5 > MA10` but the latest cross is older than three completed trading bars, the structure is `ESTABLISHED`. It may remain a normal right-side candidate but receives no recent-cross priority.

### 3.5 Intraday forming cross

When the latest K-line represents the current, unfinished trading session and the cross exists only after including that bar, the state is `FORMING`. A forming cross is research evidence only and cannot satisfy an executable add gate.

## 4. Data Model

Add a `ShortTermGoldenCrossSnapshot` record and expose it from `ShortTermTechnicalSnapshot`. The record contains:

```text
state                 NONE | APPROACHING | FORMING | CONFIRMED | ESTABLISHED | UNAVAILABLE
crossDate             trading date of the latest detected cross
tradingDaysSinceCross number of completed trading bars since the cross
ma5Ma10SpreadPercent  signed normalized MA5-to-MA10 spread
spreadTrend           NARROWING | WIDENING | FLAT | UNAVAILABLE
maAlignment           BEARISH | CONVERGING | MA5_ABOVE_MA10 | BULLISH_STACK | UNAVAILABLE
priorityTier          integer 0..3
evidenceStatus        COMPLETE | PARTIAL | UNAVAILABLE
```

The snapshot must retain the existing MA5, MA10, MA20, MA60, distance, volume, range, and right-side fields. Existing clients must remain backward compatible.

## 5. Detection Flow

1. Sort valid K-lines by trade date and reject rows without a close.
2. Require enough bars to calculate MA20 and at least one previous MA10 observation.
3. Calculate MA5 and MA10 for each recent completed bar, not only the latest bar.
4. Search backward for the latest true crossing event.
5. Classify the event as confirmed, established, approaching, forming, none, or unavailable.
6. Calculate spread, spread trend, alignment, and days since cross.
7. Feed the result into right-side classification, ranking, action gating, evidence, and UI output.

If the data source cannot prove whether the latest bar is completed, the cross must not be upgraded from `FORMING` to `CONFIRMED` during trading hours.

## 6. Ranking

Hard gates run before golden-cross priority:

- common A-share and non-ST eligibility;
- usable current price;
- minimum liquidity;
- unstable-industry exclusion where configured;
- reliable market coverage;
- quote freshness;
- basic K-line integrity;
- long-sideways exclusion unless a valid breakout is present.

After hard gates, candidates are ranked in these structural tiers:

1. `priorityTier = 3`: confirmed cross within three completed trading bars plus valid early right-side structure.
2. `priorityTier = 2`: approaching cross plus an improving MA20/right-side structure.
3. `priorityTier = 1`: established cross or another valid early right-side structure.
4. `priorityTier = 0`: no valid cross evidence or a conflicting structure.

Within the same tier, the existing action priority and explainable final score remain the tie-breakers. Volume, market heat, fundamentals, valuation context, liquidity, crowding, and chase-risk penalties continue to contribute.

A recent cross with poor fundamentals, stale data, excessive extension, weak liquidity, or a risk-off market does not receive tier 3 eligibility.

## 7. Action Rules

The legacy `RIGHT_EARLY_ADD`/`TradingAdvice.ADD` path and the V2 `StrategyAction.ADD` path require all existing gates plus:

- golden-cross state is `CONFIRMED`;
- the cross occurred no more than three completed trading bars ago;
- price is above MA20;
- MA20 slope is flat or improving under the existing tolerance;
- price is not beyond the configured maximum distance from MA20;
- volume is confirmed or constructively shrinking on a rise;
- the candidate is not in an excessive chase-risk position;
- fundamentals meet the short-term floor;
- market sentiment is not risk-off;
- required tail or post-close confirmation is complete.

`APPROACHING` and `FORMING` states can only produce watch-oriented advice. They cannot produce `ADD`.

`CONFIRMED` does not guarantee `ADD`; the response must state the blocking evidence when another gate fails.

## 8. Intraday and Post-Close Semantics

- Before the daily bar is complete, a same-day cross is `FORMING`.
- The current session must be reconciled with the trading clock and K-line trade date.
- At the regular close, a completed bar can produce `CONFIRMED` only when the source timestamp and trade date are current.
- Tail and post-close fixed-price evidence remains separate from daily golden-cross evidence.
- Stale, missing, or ambiguous timestamps downgrade the action instead of inferring confirmation.

## 9. API and UI

Each short-term candidate must expose and display:

- golden-cross state and a Chinese status label;
- cross date;
- completed trading days since cross;
- MA5, MA10, and MA20;
- MA5-to-MA10 spread percentage;
- spread trend;
- moving-average alignment;
- golden-cross priority tier;
- positive evidence and blocking counter-evidence.

Suggested labels:

```text
CONFIRMED  -> 金叉已确认
APPROACHING -> 临界交汇
FORMING -> 金叉形成中
ESTABLISHED -> 多头延续
NONE -> 尚未交汇
UNAVAILABLE -> 金叉数据不足
```

Candidate cards remain clickable and show full calculations in the existing detail surface. Missing data must be shown as missing, never silently converted to zero.

## 10. Error Handling and Auditability

- Fewer than the required K-line bars produces `UNAVAILABLE` and blocks add actions.
- A K-line source failure is recorded as evidence failure and must not be replaced with an old candidate result.
- Partial golden-cross coverage is disclosed using reviewed and eligible counts.
- The strategy output records the rule version and all cross inputs needed to replay the decision.
- The recommendation history stores the cross state used at decision time so a later recalculation cannot rewrite historical evidence.

## 11. Tests

Backend tests must cover:

1. a true MA5-over-MA10 cross on the latest completed bar;
2. a cross one, two, and three completed bars ago;
3. a cross older than three bars classified as established;
4. an approaching cross with a narrowing spread;
5. a widening spread that must not be classified as approaching;
6. an intraday-only cross classified as forming and blocked from add;
7. a confirmed cross below MA20 or with a falling MA20 that remains watch-only;
8. a high-position or overextended cross that remains wait-only;
9. a confirmed cross in a risk-off market that remains blocked;
10. stale quote, insufficient K-lines, and partial-coverage behavior;
11. ranking confirmed before approaching, and approaching before ordinary right-side candidates after hard gates;
12. regression coverage for constructive shrinking rise, hot-direction ranking, financial floors, tail confirmation, and post-close handling;
13. V2 compatibility output and decision replay fields.

Frontend tests must cover status labels, missing-data rendering, ranking order, cross details, and counter-evidence display.

## 12. Success Criteria

- Confirmed recent crosses rank before otherwise comparable approaching and ordinary right-side candidates.
- Approaching and forming crosses never emit executable add advice.
- Risk, freshness, coverage, fundamentals, and tail gates still downgrade or block a confirmed cross.
- Every displayed cross conclusion can be replayed from dated K-line inputs.
- Existing short-term tests continue to pass, with new tests proving the cross-specific behavior.
- Strategy effectiveness is measured through the existing recommendation and trade-feedback history; no win-rate improvement is claimed until an out-of-sample sample is available.
