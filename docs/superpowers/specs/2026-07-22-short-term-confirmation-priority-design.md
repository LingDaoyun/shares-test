# Short-Term Right-Side Confirmation Priority Design

## Goal

Make the short-term candidate list express two different concepts clearly:

- `phaseLabel` and `rightSideSignal` describe execution maturity.
- `finalScore` remains the cross-dimensional research score.

A candidate in `右侧早期确认` must rank ahead of a candidate in `右侧早期观察` when both have passed the same safety and eligibility gates, even when the observation candidate has a higher composite score.

## Ranking Semantics

The candidate pipeline keeps the existing hard exclusions for risk, liquidity, market stage, data quality, and action eligibility. Those gates are not weakened by this change.

Within the remaining candidate set, ranking uses the following order:

1. Eligible recent golden-cross tier and executable action priority.
2. Right-side maturity: `右侧早期确认` before `右侧早期观察`, then weaker structural states.
3. Composite `finalScore` as the tie-breaker inside the same maturity state.

The backend must expose or calculate right-side maturity independently from the capped technical score. A technical score capped at 100 must not erase the difference between confirmation and observation.

The displayed composite score is not rewritten or artificially boosted. It continues to describe overall research quality, while list position describes execution priority.

## User Interface

The list row should make the semantic split visible:

- Render `右侧早期确认` as a rounded capsule with a low-saturation emerald background, a defined border, and dark emerald text.
- Keep sufficient text and border contrast while avoiding bright neon fills or saturated green.
- Render `右侧早期观察` as a quieter sky or neutral capsule so it remains legible without competing with confirmation.
- Label the numeric score as `综合分` instead of showing an unexplained number.
- Keep the existing row height and responsive layout stable; the capsule must not cause wrapping or horizontal overflow on mobile.

Suggested visual tokens:

- Confirmation: `bg-emerald-50`, `border-emerald-300`, `text-emerald-800`, `font-semibold`.
- Observation: `bg-sky-50`, `border-sky-200`, `text-sky-700`.
- Both states: `rounded-full`, compact horizontal padding, unchanged base font size.

## Verification

Backend regression coverage must include two otherwise eligible candidates where:

- the observation candidate has a higher `finalScore`;
- the confirmation candidate has a lower `finalScore`;
- the confirmation candidate still ranks first;
- both original scores remain unchanged.

Frontend coverage must verify that confirmation and observation resolve to different visual treatments and that only confirmation receives the emphasized capsule style.

After automated tests, verify the short-term page at desktop and mobile widths for ordering, contrast, wrapping, hover state, and detail-panel selection.

## Non-Goals

- Changing valuation, financial, heat, or risk factor weights.
- Relaxing hard safety and liquidity exclusions.
- Converting the composite score into a probability or buy guarantee.
- Recommending a stock solely because it reached a confirmed right-side state.
