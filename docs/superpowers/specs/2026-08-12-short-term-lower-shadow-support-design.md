# Short-Term Lower-Shadow Support Reversal Design

## Goal

Add a second short-term technical path for stocks that close slightly lower on
the day but show measurable intraday support through a long lower shadow and a
strong recovery from the low. Keep the existing golden-cross and rising-volume
path unchanged.

The signal describes price behaviour only. It must not claim that a market
maker or major institution is buying without independent order-flow evidence.

## Selected Approach

Use an independent `SUPPORT_REVERSAL` path after full-market quote prefiltering
and before financial review. Two rejected alternatives are:

- treating a long lower shadow as a small bonus inside the rising-stock model,
  which cannot work because non-positive quotes are currently removed first;
- simply allowing all small decliners into the existing model, which would leak
  ordinary falling stocks into the recommendation list.

The independent path lets a small decliner reach K-line review, then requires a
complete quantitative confirmation. A failed confirmation is recorded as a
technical exclusion and remains hidden from recommendations.

## Quantitative Definition

The latest daily candle uses official OHLC after close and a provisional close
during the trading session.

```text
lowerShadowPercent =
  (min(open, close) - low) / (high - low) * 100

bodyPercent =
  abs(close - open) / (high - low) * 100

upperShadowPercent =
  (high - max(open, close)) / (high - low) * 100

closeLocationPercent =
  (close - low) / (high - low) * 100
```

A confirmed support reversal requires all conditions:

- daily change is from `-2.00%` through `0.00%`;
- lower shadow is at least `50%` of the full candle range;
- close location is at least `70%` of the full candle range;
- upper shadow is no more than `20%`;
- candle body is no more than `35%`;
- the intraday low touches or crosses a support reference with a `0.5%`
  tolerance, while the close finishes back at or above that support;
- the close is at or above MA20 and no farther than the configured MA20
  distance limit;
- MA20 five-day slope is at least `-0.20%`;
- 20-day volume ratio is from `1.00` through `2.50`;
- turnover is in the existing `1%-8%` tradable range.

Support references are MA5, MA10, MA20, and the previous 20-day high. The
highest touched-and-reclaimed valid reference is reported so the decision is
auditable.

An observation state may be retained for diagnostics when the candle shape,
support recovery, and trend pass but volume ratio is only `0.80-1.00`. It does
not independently bypass the golden-cross requirement or create a trade
action.

## Score And Ranking

The support-reversal score is separate from the existing four-factor score:

- support recovery quality: 35%;
- lower-shadow and closing quality: 30%;
- volume and turnover quality: 20%;
- trend and golden-cross context: 15%.

For a confirmed support reversal, the technical ranking score may be calibrated
up to the signal score but is capped at `86`. This lets the new path enter the
candidate set without outranking a strong rising, recently confirmed
golden-cross candidate merely because it is new. The original four-factor raw
score remains visible and unchanged.

The final candidate comparator uses this order within otherwise eligible
stocks:

1. `RIGHT_EARLY_ADD`;
2. `SUPPORT_REVERSAL_LIGHT_TRIAL` and existing light-trial actions;
3. right-side observation actions;
4. pullback and wait actions.

## Risk Gates And Actions

All existing market, ST, liquidity, industry-leader, financial-red-flag,
ChiNext-permission, quote-freshness, and extreme-market-risk gates remain in
force.

- a decline below `-2.00%` is rejected before K-line requests;
- a `-2.00%` through `0.00%` quote without confirmed support reversal is
  excluded after K-line review;
- a confirmed support reversal receives
  `SUPPORT_REVERSAL_LIGHT_TRIAL`, never `RIGHT_EARLY_ADD`;
- the initial advice is `LIGHT_TRIAL` with at most one fifth of the planned
  short-term position;
- actionable entry still requires valid `14:45-14:56` tail evidence;
- loss of the reclaimed support, a break below the candle low, or a weak next
  trading-day open invalidates the trial.

## API And UI

Add `ShortTermSupportReversalSignal` to `ShortTermTechnicalSnapshot`. Historical
reports may omit it; both backend and frontend treat the missing field as
unavailable.

Expose:

- state and label;
- score;
- lower-shadow, body, upper-shadow, and close-location percentages;
- reclaimed support type and price;
- trend, volume, and turnover qualification;
- provisional/official candle status;
- reasons and data gaps.

The candidate row displays a restrained `长下影承接确认` capsule only when the
signal is confirmed. The detail overlay shows the signal metrics, reclaimed
support, action discipline, and invalidation condition. It does not restore the
removed chip-distribution UI.

## Verification

- evaluator tests cover exact threshold boundaries and zero-range candles;
- a `-1%` confirmed long-lower-shadow fixture reaches recommendations as a
  light trial;
- a `-1%` ordinary candle is reviewed and then excluded;
- a decline below `-2%` is excluded before K-line fetch;
- a confirmed support reversal can qualify without a golden cross, while an
  observation-only signal cannot;
- strong rising golden-cross fixtures retain their existing actions and rank
  priority;
- market-risk and financial hard gates still block the new path;
- React tests cover the capsule, detail metrics, and legacy responses;
- full backend tests, frontend tests, production build, and a local browser
  smoke check pass.
