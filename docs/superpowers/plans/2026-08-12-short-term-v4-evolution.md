# Short-Term V4 Evolution Implementation Plan

> **Execution rule:** Every phase is implemented with tests, then reviewed by a fresh read-only review Agent. Critical and important findings are fixed and re-reviewed before the next phase begins.

**Goal:** Deliver an explainable, point-in-time, T1/T2-oriented short-term strategy with transparent ranking, market-regime adaptation, automatic outcomes, and honest validation status.

**Architecture:** Keep `ShortTermService` as orchestration while extracting pure evaluators/rankers and a dedicated observation/outcome module. Preserve existing API compatibility through optional record fields. Use schema-first JPA persistence and deterministic tests.

**Tech Stack:** Java 17, Spring Boot 3, Spring Data JPA, H2/MySQL-compatible schema, React 18, TypeScript, Vitest, Maven.

## Phase 0: Baseline And Plan Gate

- [x] Confirm clean worktree and record current commits.
- [x] Run `mvn -pl apps/api test`.
- [x] Run `npm --prefix apps/web-react test -- --run`.
- [x] Write the approved design and implementation plan.
- [x] Reviewer Agent checks scope, contradictions, migrations, compatibility, and testability.
- [x] Fix review findings and commit the design checkpoint.

## Phase 1: Ranking And Timing Correctness

**Primary files:**

- `ShortTermSupplyDemandScorer.java`
- `ShortTermSupplyDemandScore.java`
- `ShortTermService.java`
- `ShortTermAutomationSettings.java`
- `StrategyFeedbackService.java`
- `TradeOutcomeRepository.java`
- default and Nacos YAML files
- corresponding unit tests

- [x] Write failing tests proving chip shadow mode cannot alter production order.
- [x] Bound fresh same-day fund flow to a visible `[-2,+2]` tie-break contribution.
- [x] Return the active contribution and factor state in the score breakdown.
- [x] Change default/Nacos chip activation to `SHADOW`.
- [x] Enforce final deadline `14:49:40` and compatible cron/default validation.
- [x] Test `14:49:39`, exactly `14:49:40`, `14:49:41`, `Asia/Shanghai`, and a run that starts before but persists after the deadline.
- [x] Select T1/T2 feedback for `SHORT_TERM`; retain T20 for long-term cohorts.
- [x] Run targeted and complete backend tests.
- [x] Reviewer Agent inspects ranking invariants, time boundaries, refreshed configuration, and query correctness.
- [x] Fix findings, re-run tests, and commit the phase.

## Phase 2: Full-Universe Sector Context And Relative Strength

**Primary files:**

- create `ShortTermCrossSectionContext.java`
- create `ShortTermCrossSectionAnalyzer.java`
- create `ShortTermRelativeStrength.java`
- modify `ShortTermService.java`, report/score records, and tests

- [x] Calculate market/industry heat from the unfiltered quote universe.
- [x] Replace industry-turnover top-three hard exclusion with a soft leadership percentile.
- [x] Calculate 5/10/20-day candidate relative strength without optimistic missing-data defaults.
- [x] Add bounded, visible cross-section contributions to ranking.
- [x] Preserve full-universe coverage counts separately from K-line-reviewed counts.
- [x] Assert `marketQuoteCoverage = valid unique in-scope quotes / source-reported in-scope total`; assert `technicalReviewCoverage = technically reviewed / quote-preselected` and never reuse one as the other.
- [x] Assert market quote coverage below 95% remains `DATA_BLOCKED/NO_TRADE` regardless of technical-review coverage.
- [x] Add tests for circular-filter prevention, small early leaders, missing cohorts, and deterministic ties.
- [x] Reviewer Agent checks universe chronology, look-ahead safety, ranking stability, and explainability.
- [x] Fix findings, re-run tests, and commit the phase.

## Phase 3: Volatility Normalization And Market Regime

**Primary files:**

- create `ShortTermVolatilityQuality.java`
- create `ShortTermVolatilityQualityEvaluator.java`
- create `ShortTermMarketRegime.java`
- create `ShortTermMarketRegimeClassifier.java`
- modify technical snapshot/report/action mapping and tests

- [x] Calculate ATR percentage, MA20 distance in ATR units, range contraction, and breakout expansion.
- [x] Add an independent `VOLATILITY_CONTRACTION_BREAKOUT` signal family.
- [x] Classify risk-off, repair, trend-expansion, and crowded-volatile states from full-market inputs.
- [x] Require regime inputs to use the same unfiltered quote universe and point-in-time cutoff established in Phase 2.
- [x] Keep safety gates hard; make ranking thresholds volatility/percentile aware.
- [x] Limit repair/crowded states to light actions and keep risk-off non-executable.
- [x] Add boundary, zero-range, missing-data, and action-downgrade tests.
- [x] Reviewer Agent checks formula correctness, duplicate-factor risk, regime leakage, and action safety.
- [x] Fix findings, re-run tests, and commit the phase.

## Phase 4: Automatic T1/T2 Observation And Outcome Loop

**Primary files:**

- add schema tables for signal observations and horizon outcomes
- create JPA entities/repositories/services/scheduler under `shortterm/validation`
- integrate scheduled/manual final report archival
- update feedback summaries and tests

- [x] Persist every published executable/watch candidate idempotently without creating a user holding.
- [x] Persist point-in-time payload, source, coverage, cutoff, signal family, regime, action, and validation eligibility.
- [x] Mature T1/T2 close return, configured net return, MFE, and MAE after future bars become available.
- [x] Define T1/T2 as market trading days, baseline them on the cutoff recommendation price, and mark a suspended/missing symbol bar unavailable without shifting horizons.
- [x] Persist commission, stamp-duty, and optional slippage assumptions used by each net-return label.
- [x] Keep unavailable source results retryable and immutable matured labels protected.
- [x] Aggregate by rule version, signal family, and market regime.
- [x] Require minimum sample counts before probability/expected-return presentation.
- [x] Add repository, chronology, idempotency, source-failure, and cohort tests.
- [x] Reviewer Agent checks point-in-time integrity, selection bias, idempotency, cost treatment, and schema compatibility.
- [x] Fix findings, re-run tests, and commit the phase.

## Phase 5: Frontend Explainability And Final Verification

**Primary files:**

- `apps/web-react/src/types.ts`
- short-term page/components and tests
- runtime configuration defaults when required

- [ ] Show signal family, market regime, setup score, rank score, and all active contributions.
- [ ] Show validation status/sample count without fake probabilities.
- [ ] Keep shadow factors absent from recommendation evidence.
- [ ] Preserve detail overlay, manual scan, scheduled animation, and buy-entry flows.
- [ ] Run all backend/frontend tests, frontend production build, and `git diff --check`.
- [ ] Restart the application and smoke-test `http://127.0.0.1:5176/#/short-term`.
- [ ] Final Reviewer Agent compares implementation against every design acceptance criterion and reviews the complete diff.
- [ ] Fix findings, repeat final verification, and commit the final phase.
