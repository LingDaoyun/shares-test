# Short-Term Golden Cross Volume Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current diluted short-term score with an explainable golden-cross, volume, turnover, and close-strength model, and show up to eight honestly labelled candidates.

**Architecture:** Keep market-data acquisition and hard risk gates in `ShortTermService`, but move deterministic candle and core-score calculations into pure Java evaluators. Persist the new breakdown in the existing report contract, expose it through typed React models, and render the same evidence used by the action decision.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, React, TypeScript, Vitest, Docker Compose.

## Global Constraints

- A confirmed recent golden cross must rank above a forming or observation-only cross.
- Main score weights are fixed at golden cross 45%, rising volume 30%, turnover 15%, and close strength 10%.
- PE, PB, market heat, and ordinary financial quality are context, not repeated score penalties.
- ST, missing quotes/K-lines, inadequate liquidity, limit-board inaccessibility, failed crosses, and serious financial red flags remain hard exclusions.
- Default output is eight. Observation candidates may fill the display, but the service must never manufacture eight executable actions.
- All new calculations must be deterministic, testable, and present in the API explanation.
- Existing unrelated working-tree changes must not be reverted or staged accidentally.

## Task 1: Lock the Core Metric Contract

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermMomentumQuality.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermMomentumQualityEvaluator.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermMomentumQualityEvaluatorTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermTechnicalSnapshot.java`

- [ ] Write failing tests for turnover scoring at 1%, 2%, 3%, 5%, and 8%.
- [ ] Write failing tests for upper-shadow ratio, close location, three-bullish-candle median, and provisional current-day bars.
- [ ] Run the focused test and confirm failures are caused by the missing evaluator.
- [ ] Implement the smallest pure evaluator and immutable result record.
- [ ] Attach the result to the technical snapshot with a null-safe missing value.
- [ ] Re-run the focused test and existing technical evaluator tests.

## Task 2: Replace the Diluted Final Score

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoreSignalScorer.java`
- Create: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermCoreSignalScorerTest.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermScoreBreakdown.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermWeightProfile.java`

- [ ] Write failing tests proving confirmed recent crosses outrank forming crosses.
- [ ] Write failing tests for the 45/30/15/10 weighted total and volume-risk bands.
- [ ] Run the tests and confirm RED.
- [ ] Implement the core scorer without market, valuation, or ordinary financial weights.
- [ ] Preserve legacy context fields in the report only where compatibility requires them.
- [ ] Re-run the focused scorer tests.

## Task 3: Integrate Ranking, Actions, and Eight Results

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermReport.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettings.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/ShortTermServiceTest.java`
- Modify: `apps/api/src/test/java/com/aistock/research/shortterm/schedule/ShortTermAutomationSettingsTest.java`

- [ ] Add service tests showing high PE/PB alone does not block a valid short-term candidate.
- [ ] Add service tests showing an extreme upper shadow becomes observation-only.
- [ ] Add service tests showing hard-risk candidates remain hidden.
- [ ] Add a default-limit test proving the report can return eight ranked candidates.
- [ ] Run the focused service tests and confirm RED.
- [ ] Integrate momentum evaluation and the core scorer into candidate construction.
- [ ] Make action decisions consume the same signal values shown to the user.
- [ ] Set service and scheduler defaults to eight while respecting explicit request overrides.
- [ ] Re-run the focused API tests and repair only behavior caused by this model change.

## Task 4: Expose the Same Explanation in React

**Files:**
- Modify: `apps/web-react/src/types/api.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/components/shortterm/ShortTermCandidateIndicators.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

- [ ] Add failing UI tests for the default eight-result request.
- [ ] Add failing UI tests for golden-cross, volume, turnover, upper-shadow, close-location, and provisional labels.
- [ ] Run the focused Vitest file and confirm RED.
- [ ] Extend API types with null-safe momentum and core-score fields.
- [ ] Replace the old diluted score panel with the four core weights and separate risk/context evidence.
- [ ] Add compact candidate badges and full modal explanations without changing the overlay interaction.
- [ ] Re-run focused frontend tests and the production TypeScript build.

## Task 5: Regression and Runtime Verification

**Files:**
- Modify if required: `infra/nacos/ai-stock-api-local.yml`
- Modify if required: Docker runtime configuration without changing model API keys.

- [x] Run all API tests.
- [x] Run all web tests and the production build.
- [x] Inspect the full diff for accidental unrelated changes.
- [x] Rebuild and restart the Docker services.
- [x] Run one real full-market short-term scan with limit eight.
- [x] Verify coverage, candidate count, action labels, core-score arithmetic, candle evidence, and hard-risk audit output.
- [x] Verify `http://127.0.0.1:5176/#/short-term` in the in-app browser at desktop.
- [x] Commit only the short-term model, tests, documentation, and necessary runtime settings.
