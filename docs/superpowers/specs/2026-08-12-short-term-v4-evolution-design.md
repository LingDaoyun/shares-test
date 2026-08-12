# Short-Term V4 Evolution Design

## Goal

Evolve the A-share overnight short-term module from a large collection of plausible rules into an auditable, point-in-time strategy whose ranking inputs are visible, whose market and technical factors are orthogonal, and whose T1/T2 outcomes are collected automatically.

The module remains a research and execution-discipline tool. It must never manufacture confidence when market coverage, quote freshness, source alignment, or validation samples are insufficient.

## Existing Strengths To Preserve

- Full-market quote acquisition and explicit coverage/freshness gates.
- ST, liquidity, ChiNext permission, financial-red-flag, T+1, and market-risk controls.
- Separate rising golden-cross and lower-shadow support-reversal signal paths.
- Scheduled preselection/final snapshots and manual scans.
- Explainable score breakdowns, tail confirmation, historical reports, and trade review.

## Problems Being Corrected

1. The visible four-factor score does not fully explain final ordering because fund flow and active chip estimates can dominate `rankingScore`.
2. The scheduled deadline can finish after the user's actionable 14:50 cutoff.
3. Short-term feedback is incorrectly aggregated with a fixed T20 horizon.
4. Sector heat is calculated after stock filtering, while industry turnover rank is used as a hard exclusion. This creates circular selection.
5. Several trend inputs are transformations of the same price series, while relative strength and volatility-normalized location are absent.
6. Fixed thresholds do not adapt to volatility or market regime.
7. Only manually added trade cases mature into feedback, producing selection bias and too few samples.
8. Current backtest output does not replay the complete production strategy and must not be presented as validated production performance.

## Architecture

### 1. Gate Layer

Hard gates remain deterministic and do not contribute points:

- source-reported quote coverage and quote freshness;
- trade date and point-in-time chronology;
- ST/delisting, liquidity, permission, and T+1 eligibility;
- severe financial red flags;
- market-wide risk-off state.

Executable scheduled recommendations require at least 95% market coverage. Research previews may still render below this threshold but must use `DATA_BLOCKED` or `NO_TRADE` and must not expose an executable buy action.

Two coverage metrics are deliberately separate:

- `marketQuoteCoverage = valid unique in-scope A-share quotes / source-reported in-scope A-share total`. This is the only coverage metric allowed to satisfy the 95% executable-data gate.
- `technicalReviewCoverage = candidates with sufficient point-in-time K-line factors / quotes that passed quote-only preselection`. It describes how much of the preliminary cohort received technical review. It never substitutes for market quote coverage and cannot turn a blocked report into an executable report.

Both numerators and denominators are returned. Deduplication uses the six-digit symbol after excluding non-A-share instruments with the existing market-scope rules.

### 2. Signal-Family Layer

Each family is evaluated independently and carries its own state and reason codes:

- `GOLDEN_CROSS_MOMENTUM`: recent MA5/MA10 cross, acceptable volume, turnover, candle quality, and trend continuation.
- `SUPPORT_REVERSAL`: slight decline, long lower shadow, reclaimed support, acceptable trend, volume, and turnover.
- `VOLATILITY_CONTRACTION_BREAKOUT`: compressed recent range/ATR followed by an early, non-overextended breakout.

Signals are not merged into one synthetic setup before validation. The report identifies the winning family and preserves all family outputs for later ablation.

### 3. Cross-Section Ranking Layer

The ordering score is separate from the visible technical setup score.

Ranking uses visible, bounded contributions:

- setup quality;
- 5/10/20-day relative strength against the reviewed cross-section and industry cohort;
- volatility-normalized distance and breakout quality;
- industry heat calculated from the unfiltered full-market quote universe;
- soft industry leadership percentile;
- tail path confirmation when point-in-time minute data is available.

Fund flow is a maximum two-point tie-breaker. Chip estimation remains `SHADOW` until an external source, trade date, coverage, and sample-outcome uplift have been verified. A factor may not change ordering unless its contribution is returned to the client.

### 4. Market-Regime Layer

The market is classified into four deterministic states:

- `RISK_OFF`: broad decline or extreme limit-down stress; no executable recommendation.
- `REPAIR`: breadth is recovering but confirmation is weak; light-trial actions only.
- `TREND_EXPANSION`: positive breadth, turnover participation, and manageable limit-down pressure; normal ranking/actions.
- `CROWDED_VOLATILE`: positive but overextended/high-volatility breadth; reduced action strength.

Inputs include advancing/declining breadth, approximate limit-up/down counts, median stock return, advancing/declining turnover, and breadth quality. The report exposes the state and reasons.

### 5. Point-In-Time Observation And Outcomes

Every published candidate is automatically stored, including watch-only candidates. The observation contains:

- strategy/rule version, signal family, rank, action, score breakdown;
- recommendation price and data cutoff time;
- market regime, coverage, source, and payload snapshot;
- validation eligibility and block reason.

After future daily bars become available, an outcome job labels T1 and T2 independently with close return, configurable cost-adjusted net return, maximum favorable excursion, maximum adverse excursion, source, and market timestamp. User trade cases remain execution feedback but are not the strategy's only outcome cohort.

T1 and T2 mean the first and second **A-share market trading days** after the recommendation trade date, not natural days and not the stock's next two available bars. The recommendation price at or before the actionable data cutoff is the baseline. The corresponding trading-day close is the evaluation price; high/low produce MFE/MAE. If the stock is suspended or lacks a valid bar on that market trading day, the horizon is marked `UNAVAILABLE_SUSPENDED_OR_MISSING` and is not silently shifted to a later day. Net return subtracts configured buy/sell commission, sell-side stamp duty, and optional two-sided slippage from the gross close return; the exact cost parameters are persisted with the observation.

No calibrated probability or expected-return claim is shown until the relevant signal-family/regime cohort has enough matured samples.

### 6. Validation Layer

Production validation uses chronological walk-forward windows and reports:

- sample count, positive rate, average/median net return;
- MFE, MAE, and gap-risk proxies;
- results by signal family and market regime;
- factor ablation: base setup, plus relative strength, plus volatility quality, plus sector context, plus flow tie-breaker;
- in-sample versus out-of-sample periods and data gaps.

The existing simplified backtest remains available only as a research replay and explicitly lists unreplayed production gates.

## Timing

- Preselection remains an earlier background task.
- Final task starts early enough to publish before 14:50.
- The final actionable cutoff is `14:49:40 Asia/Shanghai`.
- Data after the cutoff may update replay/history but may not retroactively create an actionable same-day buy recommendation.
- Final publication uses two transactions. Transaction A must commit the complete immutable payload and move `RUNNING -> FINAL_PENDING` before the cutoff. Transaction B starts only after A returns and uses the database transaction timestamp as an upper-bound proof that A committed no later than the cutoff; B may commit slightly after the cutoff because `FINAL_PENDING` remains non-executable and its report stays hidden until certification commits.
- If transaction B observes database time after the cutoff, certification is interrupted, or a process restarts with `RUNNING`/`FINAL_PENDING`, the snapshot fails closed to `DATA_BLOCKED`. Existing `FINAL_READY` rows without a valid payload hash and certification timestamp are also downgraded rather than backfilled. Boundary tests cover `14:49:39`, exactly `14:49:40`, and `14:49:41` in `Asia/Shanghai`.

## Frontend Contract

The short-term page must show:

- the active signal family and market regime;
- setup score, cross-section rank score, and each active contribution;
- validation status and sample count;
- data cutoff, coverage, freshness, source, and block state;
- entry/exit rules without implying guaranteed returns.

Hidden active factors are forbidden. Shadow factors may be persisted for research but are not displayed as recommendation evidence and do not affect actions or ranking.

## Error Handling

- Missing orthogonal factors lower confidence or remain neutral; they do not receive optimistic defaults.
- A stale/mismatched flow record contributes zero and records a data gap.
- Missing factor snapshots never trigger a fallback to fabricated example data.
- Outcome maturation is idempotent and retries source failures without overwriting previously matured point-in-time labels.

## Acceptance Criteria

1. Chip estimates cannot change production order in default/Nacos configuration.
2. Fund flow changes rank by no more than two points and its contribution is visible.
3. The final scheduled result cannot become actionable after 14:49:40.
4. Short-term feedback uses T1/T2, while long-term feedback retains T20.
5. Sector heat is derived from the full quote universe before stock filtering; industry leadership is a soft factor.
6. Relative-strength, ATR normalization, volatility contraction, and four-state regime tests pass.
7. Every published candidate can be persisted and matured automatically without appearing as a user holding.
8. Validation never reports a calibrated probability with an insufficient cohort.
9. Backend tests, frontend tests/build, schema startup, and browser smoke checks pass.
10. Each implementation phase receives an independent review Agent verdict; important findings are fixed before the next phase.
11. A high technical-review ratio cannot compensate for market quote coverage below 95%; a low technical-review ratio is disclosed but does not falsify the market quote coverage metric.
