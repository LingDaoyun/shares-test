# Task 5 Report: Historical Strategy Feedback in Agent Prompts

## Status

Implemented and verified against base HEAD `283e3194ffc2486656dc0e0b0984c79d9610ffcd`.

The unrelated modification in `docs/superpowers/plans/2026-07-10-soft-valuation-context-p01.md` was not edited, staged, or reverted.

## Implementation

- Added a bulk matured `RECOMMENDATION/T20` outcome/case projection and a single bulk `BUY` fill query.
- Aggregated exact `sourceModule + ruleVersion` cohorts in stable order with sample gates, positive fraction, return/run-up/drawdown/deviation percentage-point metrics, deterministic median, Asia/Shanghai recommendation dates, and clamped reliability adjustment.
- Excluded `CURRENT`, `EXECUTION`, `PENDING`, `UNAVAILABLE`, non-T20, and null-return rows. Missing BUY fills affect only the deviation denominator.
- Added portable feedback lookup indexes to both schema copies.
- Added `GET /api/strategy-feedback`.
- Added typed historical feedback to committee prompt previews while preserving `preview(report)`.
- Injected `StrategyFeedbackService` only at the AI orchestration boundary, with one `promptContext(symbol)` lookup per `preview` or `enhance` call.
- Labeled the evidence as GLOBAL, non-symbol-matched strategy-cohort evidence and constrained it to committee summary, counter-evidence, and suggested stage. Deterministic score and Nacos behavior remain unchanged.

## RED Evidence

1. `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest test`
   - Failed during test compilation with 40 missing-symbol/missing-method errors for the feedback service, summary, projection, and bulk repository methods.
2. `mvn -pl apps/api -Dtest=StrategyFeedbackRepositoryTest,StrategyFeedbackControllerTest,TradeOutcomeSchemaMigrationTest test`
   - Failed during test compilation because `StrategyFeedbackController` did not exist. The schema tests also required the new indexes.
3. `mvn -pl apps/api -Dtest=AgentCommitteePromptServiceTest,AgentCommitteeAiServiceTest test`
   - Failed during test compilation with 5 errors for the missing prompt feedback field/overload and AI-service constructor dependency.

An intermediate aggregation run compiled production code but produced 8 Mockito matcher errors. The test harness was corrected from a mixed raw/matcher call to `eq("BUY")`; behavior assertions were unchanged.

## GREEN Evidence

- Baseline before Task 5: `mvn -pl apps/api test`
  - 183 tests, 0 failures, 0 errors, 0 skipped.
- Aggregation slice: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest test`
  - 8 tests, 0 failures, 0 errors, 0 skipped.
- Data/API/schema slice: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest,StrategyFeedbackRepositoryTest,StrategyFeedbackControllerTest,TradeOutcomeSchemaMigrationTest test`
  - 11 tests, 0 failures, 0 errors, 0 skipped.
- Prompt/orchestration slice: `mvn -pl apps/api -Dtest=AgentCommitteePromptServiceTest,AgentCommitteeAiServiceTest test`
  - 4 tests, 0 failures, 0 errors, 0 skipped.
- Brief-specified focused command: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest,AgentCommitteePromptServiceTest test`
  - 10 tests, 0 failures, 0 errors, 0 skipped.
- Final focused Task 5 suite:
  - Command: `mvn -pl apps/api -Dtest=StrategyFeedbackServiceTest,StrategyFeedbackRepositoryTest,StrategyFeedbackControllerTest,TradeOutcomeSchemaMigrationTest,AgentCommitteePromptServiceTest,AgentCommitteeAiServiceTest test`
  - 15 tests, 0 failures, 0 errors, 0 skipped.
- Full backend: `mvn -pl apps/api test`
  - 197 tests, 0 failures, 0 errors, 0 skipped.
- Diff hygiene: `git diff --check`
  - Exit 0, no whitespace errors.

## Files

Production and repositories:

- `apps/api/src/main/java/com/aistock/research/tradefeedback/MaturedRecommendationRow.java`
- `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackSummary.java`
- `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackService.java`
- `apps/api/src/main/java/com/aistock/research/tradefeedback/StrategyFeedbackController.java`
- `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeOutcomeRepository.java`
- `apps/api/src/main/java/com/aistock/research/tradefeedback/TradeFillRepository.java`
- `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteePromptPreview.java`
- `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteePromptService.java`
- `apps/api/src/main/java/com/aistock/research/committee/AgentCommitteeAiService.java`

Schemas:

- `apps/api/src/main/resources/schema.sql`
- `infra/db/init/001_schema.sql`

Tests:

- `apps/api/src/test/java/com/aistock/research/tradefeedback/StrategyFeedbackServiceTest.java`
- `apps/api/src/test/java/com/aistock/research/tradefeedback/StrategyFeedbackRepositoryTest.java`
- `apps/api/src/test/java/com/aistock/research/tradefeedback/StrategyFeedbackControllerTest.java`
- `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeSchemaMigrationTest.java`
- `apps/api/src/test/java/com/aistock/research/committee/AgentCommitteePromptServiceTest.java`
- `apps/api/src/test/java/com/aistock/research/committee/AgentCommitteeAiServiceTest.java`

Report:

- `.superpowers/sdd/task-5-report.md`

## Self-Review

- Confirmed the outcome query is restricted to `MATURED`, `RECOMMENDATION`, `T20`, and non-null returns.
- Confirmed aggregation executes one outcome/case query and one BUY-fill query for non-empty samples, with no per-case repository calls.
- Confirmed first BUY selection uses `executedAt`, `createdAt`, then `fillId`, even if a repository mock returns unsorted data.
- Confirmed missing BUY data preserves strategy sample counts and only reduces `executionDeviationSampleCount`.
- Confirmed positive rate is a `0..1` fraction; other metrics remain percentage points.
- Confirmed 4/5/20 gates, both adjustment clamps, even/odd medians, stable cohort order, and Asia/Shanghai date boundaries are tested.
- Confirmed prompt formatting has no repository/service dependency, the compatibility overload remains, and feedback is explicitly GLOBAL rather than symbol-matched.
- Confirmed AI preview/enhance each perform exactly one feedback lookup and enhanced reports retain the deterministic score.
- Confirmed no Nacos configuration or deterministic committee scoring code was changed.

## Concerns

- `promptContext` currently returns all eligible cohorts, as required. If the number of retained rule versions grows substantially, a later explicitly specified prompt-budget cap may be needed while keeping `summaries()` complete and stable.
- The repository projection uses JPQL constructor projection supported by the current Hibernate/Spring Data stack and covered by the H2 repository test.
