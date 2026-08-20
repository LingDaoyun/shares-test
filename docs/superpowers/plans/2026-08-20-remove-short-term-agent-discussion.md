# Remove Short-Term Agent Discussion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove only the uncommitted short-term Agent discussion feature while preserving the repository's original general Agent committee and every unrelated dirty-worktree change.

**Architecture:** Treat `com.aistock.research.shortterm.committee` and the matching React panel/store as one removable feature slice. Revert only its additions in shared files, validate the user-visible absence with a page regression test, and use a strict residue scan to prove that routes, schema, recommendation source, types, and copy are gone.

**Tech Stack:** Java 17, Spring Boot, Maven, React, TypeScript, Vitest, Git.

## Global Constraints

- Remove only the short-term committee feature; preserve `com.aistock.research.committee` and its tests.
- Preserve `research.short-term.schedule.enabled: false`, V4 scoring changes, scan-status simplification, and trade-review changes.
- Do not execute `DROP TABLE`, connect to a database, or mutate runtime data.
- Delete only `.zcode/plans/plan-sess_7b0b6d0d-a88f-4078-8f42-8cf96a42bd42.md`; preserve the other `.zcode` plan.
- Shared dirty files must be edited surgically and must not be staged wholesale.

---

### Task 1: Add a User-Visible Removal Regression

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Test: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: an existing final-ready scheduled report containing candidate `600795`.
- Produces: a regression assertion that the short-term candidate page contains neither the `Agent讨论` button nor the `Agent推荐` badge.

- [ ] **Step 1: Add a failing absence test before deleting production code**

Add this test inside `describe('ShortTermPage manual scan flow', ...)`:

```tsx
it('does not expose the removed Agent discussion feature', async () => {
  vi.mocked(fetchLatestShortTermScheduledSnapshot).mockResolvedValue({
    ...finalReadySnapshot,
    report: reportWithCandidates(['600795'])
  })

  await renderPage(root)

  expect(document.body.textContent).not.toContain('Agent讨论')
  expect(document.body.textContent).not.toContain('Agent推荐')
})
```

- [ ] **Step 2: Run the test and verify the red state**

Run:

```bash
cd apps/web-react
npm test -- --run src/pages/ShortTermPage.test.tsx -t "does not expose the removed Agent discussion feature"
```

Expected: FAIL because the current page still renders the `Agent讨论` action.

---

### Task 2: Remove the Frontend Feature Slice

**Files:**
- Delete: `apps/web-react/src/components/shortterm/AgentCommitteePanel.tsx`
- Delete: `apps/web-react/src/components/shortterm/AgentCommitteePanel.test.tsx`
- Delete: `apps/web-react/src/store/shortTermCommitteeStore.ts`
- Delete: `apps/web-react/src/store/shortTermCommitteeStore.test.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`

**Interfaces:**
- Consumes: the existing candidate page without a separate committee state owner.
- Produces: a candidate list that retains scanning and trade-review behavior but exposes no short-term committee API, types, panel, store, button, restoration flow, or tags.

- [ ] **Step 1: Delete the four feature-only files**

Delete exactly these files with patch-based file deletion:

```text
apps/web-react/src/components/shortterm/AgentCommitteePanel.tsx
apps/web-react/src/components/shortterm/AgentCommitteePanel.test.tsx
apps/web-react/src/store/shortTermCommitteeStore.ts
apps/web-react/src/store/shortTermCommitteeStore.test.ts
```

- [ ] **Step 2: Remove the three short-term committee client functions and their type imports**

Remove these names from `apps/web-react/src/api/client.ts`:

```ts
ShortTermCommitteeDiscussionRequest
ShortTermCommitteeJobStatus
ShortTermCommitteeResult
startShortTermCommitteeJob
fetchShortTermCommitteeJob
fetchLatestShortTermCommitteeDiscussion
```

The surrounding short-term scan and validation client functions remain unchanged.

- [ ] **Step 3: Remove only the committee type block**

In `apps/web-react/src/types.ts`, remove the contiguous block beginning with:

```ts
export type ShortTermCommitteeStance =
```

and ending after:

```ts
export interface ShortTermCommitteeDiscussionRequest {
  sourceType: 'SCHEDULED' | 'MANUAL'
  sourceKey?: string | null
}
```

Preserve the later `TradeLedgerSummary.openedAt` field and all unrelated types.

- [ ] **Step 4: Remove the page integration without restoring the deleted status card**

From `apps/web-react/src/pages/ShortTermPage.tsx`, remove:

```text
Bot import
AgentCommitteePanel import
useShortTermCommitteeStore import
ShortTermCommitteePick type import
all committee store selectors
committeeTradeDate restoration effect
activeCommitteeResult memo
agentPicksBySymbol memo
AgentCommitteePanel JSX
Agent讨论 button and Card extra prop
agentPick CandidateRow prop
Agent推荐 tag
```

Keep the current `ScheduledScanPulse`-only status behavior; do not re-import or restore `ScheduledSnapshotStatus`.

- [ ] **Step 5: Remove committee fixtures and tests while keeping the new absence test**

From `apps/web-react/src/pages/ShortTermPage.test.tsx`, remove committee API imports/mocks, the store import/reset, `committeeDiscussionResult`, the three committee behavior tests, and committee setup/teardown calls. Preserve the already-modified assertion that no buy-ready status copy is displayed, preserve the removed `ScheduledSnapshotStatus` test block, and retain the absence test from Task 1.

- [ ] **Step 6: Run the page test and verify the green state**

Run:

```bash
cd apps/web-react
npm test -- --run src/pages/ShortTermPage.test.tsx
```

Expected: all `ShortTermPage` tests pass; no import resolution error references deleted Agent files.

---

### Task 3: Remove the Backend Feature Slice

**Files:**
- Delete: `apps/api/src/main/java/com/aistock/research/shortterm/committee/`
- Delete: `apps/api/src/test/java/com/aistock/research/shortterm/committee/`
- Modify: `apps/api/src/main/java/com/aistock/research/ai/LlmChatClient.java`
- Modify: `apps/api/src/main/java/com/aistock/research/tradefeedback/RecommendationSource.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/resources/schema.sql`
- Modify: `README.md`
- Delete: `.zcode/plans/plan-sess_7b0b6d0d-a88f-4078-8f42-8cf96a42bd42.md`

**Interfaces:**
- Consumes: the original four-argument `LlmChatClient.completeJson` contract and the original recommendation-source set.
- Produces: a compiling backend with no short-term committee controller, service, persistence model, schema declaration, config block, README instructions, or exclusive LLM overload.

- [ ] **Step 1: Verify the static absence check is red before deletion**

Run:

```bash
if rg -n "ShortTermCommittee|committee-jobs|short_term_committee_discussion|SHORT_TERM_AGENT_COMMITTEE" \
  apps/api/src/main apps/api/src/test README.md; then
  echo "expected red state: short-term Agent discussion residue still exists"
  exit 1
fi
```

Expected: exit 1 with current short-term committee matches.

- [ ] **Step 2: Delete the two feature-only Java trees and one feature-only plan**

Delete every tracked-on-disk file below these exact directories with patch-based deletion:

```text
apps/api/src/main/java/com/aistock/research/shortterm/committee/
apps/api/src/test/java/com/aistock/research/shortterm/committee/
```

Delete only:

```text
.zcode/plans/plan-sess_7b0b6d0d-a88f-4078-8f42-8cf96a42bd42.md
```

- [ ] **Step 3: Restore the original shared LLM contract**

In `LlmChatClient`, restore the four-argument method to contain the body directly, remove the six-argument overload, use only `settings.maxCompletionTokens()`, call `callChatCompletions(settings, body)`, and restore:

```java
private String callChatCompletions(LlmSettings settings, Map<String, Object> body) {
```

with the fixed request timeout:

```java
.timeout(Duration.ofSeconds(150))
```

- [ ] **Step 4: Remove the exclusive enum, config, schema, and README additions**

Remove exactly:

```java
SHORT_TERM_AGENT_COMMITTEE("SHORT_TERM_AGENT_COMMITTEE", "short-term-agent-committee-v1"),
```

Remove only the `research.short-term.committee` YAML block, leaving:

```yaml
schedule:
  enabled: false
```

Remove the `short_term_committee_discussion` table and `idx_short_term_committee_latest` index declarations from `schema.sql`. Restore the short-term README summary line and remove the `### 短线 Agent 讨论` section without changing other README content.

- [ ] **Step 5: Verify the backend absence check is green**

Run the Step 1 command again.

Expected: exit 0 with no matches in backend production sources, backend tests, or README. Matches in the approved historical documents under `docs/superpowers` are intentionally outside this scan.

- [ ] **Step 6: Run preserved general-Agent and affected backend tests**

Run:

```bash
mvn -pl apps/api \
  -Dtest=LlmChatClientTest,AgentCommitteeAiServiceTest,AgentCommitteePromptServiceTest,AgentCommitteeServiceTest,ShortTermServiceTest,TradeLedgerCalculatorTest \
  test
```

Expected: all selected test classes pass, proving the original general committee still compiles and the unrelated trade/short-term edits remain viable.

---

### Task 4: Verify the Deletion Boundary

**Files:**
- Verify: all files named in Tasks 1-3.

**Interfaces:**
- Consumes: the cleaned source tree.
- Produces: fresh evidence that the short-term feature is absent and unrelated changes remain.

- [ ] **Step 1: Run the frontend focused regression and production build**

Run:

```bash
cd apps/web-react
npm test -- --run \
  src/pages/ShortTermPage.test.tsx \
  src/pages/TradeReviewPage.test.tsx \
  src/store/tradeFeedbackStore.test.ts \
  src/components/tradefeedback/BuyEntryButton.test.tsx
npm run build
```

Expected: all selected tests pass and the production build exits with code 0.

- [ ] **Step 2: Run the complete cross-layer residue audit**

Run from the repository root:

```bash
if rg -n "ShortTermCommittee|committee-jobs|short_term_committee_discussion|SHORT_TERM_AGENT_COMMITTEE|AgentCommitteePanel|shortTermCommitteeStore|Agent讨论|Agent推荐" \
  README.md apps/api/src/main apps/api/src/test apps/web-react/src; then
  echo "short-term Agent discussion residue found"
  exit 1
fi
```

Expected: exit 0 with no matches.

- [ ] **Step 3: Confirm preserved files and parallel diffs**

Run:

```bash
test -f .zcode/plans/plan-sess_4d2da69a-50e1-4366-b239-cab6e676054b.md
rg -n -A2 "schedule:" apps/api/src/main/resources/application.yml
git diff -- apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoreSignalScorer.java
git diff -- apps/api/src/main/java/com/aistock/research/tradefeedback/TradeLedgerCalculator.java
git diff -- apps/web-react/src/pages/TradeReviewPage.tsx
git diff --check
```

Expected: the unrelated `.zcode` plan exists, schedule remains disabled, unrelated scoring/trade-review diffs remain present, and no whitespace errors are reported.

- [ ] **Step 4: Record the clean deletion checkpoint**

Run:

```bash
git status --short --branch
```

Expected: feature-only untracked files are absent. Shared files that contained only Agent additions return to `HEAD`; shared files with unrelated edits remain modified. Do not stage the unrelated modified files.
