# Short-Term Supply-Demand Ranking Design

## Goal

Prioritize qualified short-term recommendations with stronger real buying
pressure and weaker overhead selling pressure.

The ranking must remain explainable and must not allow capital-flow strength
to bypass existing eligibility, market-data, financial-risk, chase-risk, or
execution gates.

## Current Problem

The current final score is based on:

- golden-cross quality: 45%;
- rising volume: 30%;
- turnover suitability: 15%;
- closing strength: 10%.

The final comparator also places golden-cross stage ahead of the score. Fund
flow is not part of short-term ranking, and overhead pressure is represented
only indirectly through closing-strength text. A candidate with stronger
buying and weaker selling pressure can therefore rank below a technically
similar candidate.

## Selected Approach

Keep the current technical qualification model as the eligibility layer and
add a separate supply-demand ranking model for candidates that pass it.

The new ranking score is:

```text
rankingScore =
    buyPressureScore * 45%
  + overheadPressureReliefScore * 30%
  + technicalRankingScore * 25%
```

The comparator order becomes:

1. hard eligibility gate;
2. executable action priority;
3. supply-demand ranking score;
4. golden-cross and right-side maturity tie-breakers.

This ensures that supply and demand decide the order within the same
executable-action layer without admitting technically unqualified stocks.

## Buying-Pressure Score

### Data Source

Use the existing EastMoney fund-flow endpoint and its:

- main net inflow ratio;
- super-large-order net inflow ratio;
- large-order net inflow ratio;
- source and market timestamp.

Fund flows for the technically eligible candidate set are fetched in one
batch request. The scan must not issue one network request per candidate.

### Date Validation

Fund flow contributes only when its market trade date matches the quote trade
date. Missing, stale, wrong-date, or malformed fund flow is treated as
unavailable and explicitly disclosed.

### Calculation

The main-flow component is:

```text
mainFlowScore = clamp(50 + mainNetInflowRatio * 5, 0, 100)
```

The large-order consistency component is:

- 100 when both super-large and large-order ratios are positive;
- 75 when their combined ratio is positive;
- 35 when their combined ratio is zero or negative;
- 40 when both ratios are unavailable.

The final buying-pressure score is:

```text
buyPressureScore =
    mainFlowScore * 80%
  + largeOrderConsistencyScore * 20%
```

When the main net inflow ratio is unavailable or wrong-date, the complete
buying-pressure score is 35. This prevents missing evidence from receiving a
neutral or high rank.

## Overhead-Pressure Relief Score

The score represents how weak overhead selling pressure appears. A larger
value is better.

### Upper-Shadow Component

Use the median upper-shadow percentage of recent bullish candles, falling
back to the latest upper-shadow percentage:

```text
upperShadowRelief = clamp(100 - upperShadowPercent * 1.5, 20, 100)
```

### Closing-Location Component

Use the existing close location within the latest candle:

```text
closeLocationRelief = clamp(closeLocationPercent, 20, 100)
```

### Resistance-Clearance Component

Use `breakoutFromPreviousHigh20Percent`:

- 100 for a confirmed clearance from 0% through 4%;
- 70 when more than 2% below the previous 20-day high;
- 45 when within 2% below the previous 20-day high;
- 70 when 4% through 8% above the previous high;
- 35 when more than 8% above the previous high because chase risk dominates;
- 45 when unavailable.

The final relief score is:

```text
overheadPressureReliefScore =
    upperShadowRelief * 40%
  + closeLocationRelief * 35%
  + resistanceClearance * 25%
```

Existing extreme-upper-shadow and chase-risk rules remain active.

## Backend Data Flow

1. Load and prefilter the full-market quote universe.
2. Review K-lines and apply existing technical hard exclusions.
3. Apply existing financial hard exclusions.
4. Batch-fetch same-day fund flow for eligible technical candidates.
5. Calculate buying pressure and overhead-pressure relief with a dedicated,
   deterministic scorer.
6. Build candidates with the current four-factor technical score plus the new
   supply-demand fields.
7. Sort by eligibility, action priority, supply-demand ranking, and technical
   tie-breakers.

Batch fund-flow failure does not fail the complete scan. It produces an
explicit fund-flow data gap and a buying-pressure score of 35.

## API and UI

Extend the short-term score breakdown with:

- `mainNetInflowRatio`;
- `largeOrderNetInflowRatio`;
- `buyPressureScore`;
- `overheadPressureReliefScore`;
- `technicalRankingScore`;
- the recomputed `rankingScore`.

Older persisted reports may omit the new fields. React treats them as
optional and labels them as unavailable instead of showing zero.

The candidate summary and detail view show:

- main net inflow ratio;
- buying-pressure score with 45% weight;
- overhead-pressure relief score with 30% weight;
- technical ranking score with 25% weight;
- final supply-demand ranking score.

Recommendation strengths and evidence include the EastMoney fund-flow source
and the pressure explanation. Negative main inflow and strong upper-shadow or
near-resistance conditions appear in risk observations.

## Testing

### Scorer Tests

- positive main and large-order inflow outranks negative inflow;
- two positive large-order directions receive the consistency premium;
- short upper shadows, strong closes, and cleared resistance score higher;
- imminent resistance and extreme upper shadows score lower;
- unavailable fund flow receives buying-pressure score 35;
- all scores remain in the 0-100 range.

### Integration Tests

- EastMoney batch parsing returns all requested symbols and timestamps;
- fund-flow dates must match quote dates;
- a stronger supply-demand candidate outranks a technically similar weaker
  candidate;
- negative or missing flow cannot outrank strong positive flow solely through
  the previous technical score;
- hard exclusions and action priority still precede supply-demand ranking.

### Frontend Tests

- the new weighted scores and main-flow ratio render;
- unavailable values render as "待补";
- old archived reports without new fields remain usable.

### Runtime Verification

Run a real full-market manual scan and verify:

- it completes without per-symbol fund-flow request amplification;
- candidates are descending by the new ranking score within each action layer;
- the first-page recommendation exposes fund-flow and overhead-pressure
  evidence;
- backend and frontend builds and test suites pass.

## Non-Goals

- No tick-by-tick active-buy ratio is introduced.
- No order-book or five-level bid/ask feed is introduced.
- No current eligibility or risk gate is removed.
- No recommendation is made executable solely because of positive fund flow.
