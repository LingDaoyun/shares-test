# Short-Term Green Long-Lower-Shadow Priority Design

## Goal

Add a dedicated intraday result lane for green candles whose lower shadow is at
least half of the current day's full range. The lane belongs to the existing
short-term module, appears directly below the current `右侧候选` card, and is
produced by the same manual scan.

This is an independent shape priority. It does not replace or outrank the
existing recent high-volume golden-cross/right-side confirmation path, and it
does not change the ranking of the existing `candidates` list.

## Selected Approach

Return a separate `greenLongLowerShadowCandidates` collection from the existing
short-term report. Derive it from the full-market quote snapshot already fetched
for the manual scan, then render it in its own card below `右侧候选`.

This approach is preferred over two alternatives:

- adding a score bonus to ordinary candidates, which would not provide the
  independent priority or independent display requested;
- running a second scan or a second K-line pass, which would duplicate remote
  requests and could make the two result areas use different market timestamps.

The full-market quote payload will carry today's open, high, and low alongside
the existing latest price. This makes the shape calculation available for the
entire scanned universe without an additional K-line request.

## Shape Definition

The calculation uses the market values available when the user clicks scan.
During trading hours the latest price is the provisional close; after the close
it is the official close.

```text
greenCandle = latestPrice < open

dayRange = high - low

lowerShadowPercent =
  (min(open, latestPrice) - low) / dayRange * 100

bodyPercent =
  abs(latestPrice - open) / dayRange * 100

matched = greenCandle
  and dayRange > 0
  and bodyPercent <= 10
  and lowerShadowPercent >= 50
```

Because a matched candle is green, `min(open, latestPrice)` is normally the
latest price. The general formula is retained so the calculation remains
auditable.

The `10%` body ceiling keeps the result within the system's quantitative
definition of a standard green doji while still using exact scan-time prices.

The independent shape does **not** require:

- an upper-shadow limit;
- touching or reclaiming MA5, MA10, MA20, or the previous 20-day high;
- a particular daily percentage-change interval;
- a volume-ratio, turnover, trend, or support-reversal confirmation.

Rows with missing open/high/low/latest-price values or a non-positive day range
are not synthesized and do not enter the dedicated lane.

## Universe And Priority

The dedicated lane uses the same manual scan and the same scan-start quote
snapshot as the existing short-term result. It respects the common short-term
universe boundaries already selected by the user, including ST exclusion,
market/board permission, quote freshness, price ceiling, and liquidity floor.

It does not depend on a stock surviving the existing golden-cross or
support-reversal technical path. Therefore a matching stock may appear in the
dedicated lane even when it is absent from `report.candidates`.

The `绿十字星长下影优先` lane has its own ordering:

1. lower-shadow percentage descending;
2. traded amount descending;
3. symbol ascending for deterministic ties.

The current short-term `limit` controls the maximum number of rows in this lane.
This keeps the result compact without adding another threshold control.

The resulting product-level priority is:

1. existing recent high-volume golden-cross/right-side strong confirmations;
2. the separate `绿十字星长下影优先` lane;
3. ordinary long-lower-shadow support and other short-term candidates.

The second item is expressed by a dedicated result lane, not by rewriting the
main candidate ranks.

## API Contract

Add a lightweight green-long-lower-shadow result DTO containing enough evidence
to understand the match without pretending it passed the full candidate model:

- symbol, name, market, and industry;
- latest price and daily change percentage;
- open, high, and low;
- body percentage and lower-shadow percentage;
- traded amount and turnover rate;
- quote market timestamp and freshness state.

Add `greenLongLowerShadowCandidates` to `ShortTermReport`. New reports always
return a non-null list. The frontend treats a missing field in historical or
legacy responses as an empty list.

Extend the live quote model and both supported quote parsers with today's open,
high, and low. Missing provider fields stay null rather than being inferred.

## UI Placement And Behaviour

Inside the existing short-term page:

1. keep the current `右侧候选` card unchanged;
2. add a new `绿K长下影优先` card immediately below it;
3. populate both cards from the same completed manual scan response;
4. do not add another scan button, loading job, scheduled task, cache, or
   background scan.

Each compact row shows:

- independent lane rank;
- stock name and symbol;
- latest price and daily change;
- lower-shadow percentage as the primary value and body percentage as the
  shape constraint;
- open/high/low and traded amount as supporting evidence;
- `盘中暂定` or `正式日K` according to the scan timestamp.

The empty state says `本次扫描未发现下影线占比达到 50% 的绿K` rather than showing
a loader. The card describes the result as a shape-priority observation and does
not manufacture a buy action, score, support recovery, or institutional-flow
claim.

## Compatibility And Failure Behaviour

- Existing `candidates`, their ranks, actions, scores, and details remain
  unchanged.
- A failure to calculate this shape does not invalidate the main short-term
  report; the lane is returned empty with a concise data-gap explanation.
- Historical reports without the new list continue to render the main result.
- The feature remains manual-scan only and does not restore any previously
  removed scheduled selection, result cache, scan snapshot, or dedicated
  background module.

## User-Owned Acceptance

The user will perform regression and runtime acceptance for this repository.
Codex will not run tests, builds, lint, browser checks, health checks, logs, or
other verification unless the user explicitly requests it again.

Acceptance cases for the user to check after implementation are:

- a green candle with a `10.00%` body and exactly `50.00%` lower shadow appears;
- a green candle below `50.00%` does not appear;
- a green candle whose body exceeds `10.00%` does not appear;
- a red candle with the same lower shadow does not appear;
- upper-shadow size and support reclaim do not affect membership;
- the dedicated lane is directly below `右侧候选` and comes from the same scan;
- longer lower shadows appear before shorter qualifying lower shadows;
- the existing main candidate ranks and actions do not change.
