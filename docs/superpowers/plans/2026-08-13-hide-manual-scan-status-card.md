# Hide Manual Scan Status Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the top manual scan status card from the React short-term page without changing manual scan execution or scheduled scan feedback.

**Architecture:** Keep the scan store, polling, API calls, loaders, errors, toasts, and reports unchanged. Narrow the existing top-of-page status render branch so `ScheduledScanPulse` and `ScheduledSnapshotStatus` are reachable only when the store origin is `SCHEDULED`.

**Tech Stack:** React 19, TypeScript, Zustand, Vitest, jsdom, Vite, Docker Compose

## Global Constraints

- Do not change backend, database, API, scan store, strategy, ranking, or V4 decision-gate behavior.
- Do not hide scheduled scan progress or scheduled completed status.
- Preserve manual button loading, compact no-report loader, errors, completion toast, results, background polling, and result restoration.
- Push the resulting `main` only after focused and full verification succeeds.

---

### Task 1: Hide Manual Status Presentation

**Files:**
- Modify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Modify: `apps/web-react/src/pages/ShortTermPage.tsx:153-159`

**Interfaces:**
- Consumes: `useShortTermScanStore` fields `origin`, `scheduledSnapshot`, and `snapshot`; existing `ScheduledScanPulse` and `ScheduledSnapshotStatus` components.
- Produces: page-level rendering in which top scan status UI is shown only for `origin === 'SCHEDULED'`.

- [ ] **Step 1: Write the failing regression test**

Add this test inside `describe('ShortTermPage manual scan flow', ...)`, near the other manual scan presentation tests:

```tsx
it('does not render the top status card after a manual scan result loads', async () => {
  await renderWithManualReport(root, reportWithCandidates(['600795']), 'manual-status-hidden')

  expect(document.body.textContent).toContain('候选600795')
  expect(toast.success).toHaveBeenCalledWith('手动扫描完成，已生成 1 个候选')
  expect(document.body.textContent).not.toContain('手动最终结果已就绪')
  expect(document.body.textContent).not.toContain('手动重算')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd apps/web-react
npm test -- ShortTermPage.test.tsx -t "does not render the top status card after a manual scan result loads"
```

Expected: FAIL because the current page renders `手动最终结果已就绪` and `手动重算` from `ScheduledSnapshotStatus`.

- [ ] **Step 3: Apply the minimal render-condition change**

Replace the top status branch in `ShortTermPage` with:

```tsx
{origin === 'SCHEDULED' ? (
  scheduledSnapshot && isScheduledScanRunning(scheduledSnapshot) ? (
    <ScheduledScanPulse snapshot={scheduledSnapshot} />
  ) : snapshot && !isWaitingScheduledSnapshot(snapshot) ? (
    <ScheduledSnapshotStatus snapshot={snapshot} origin="SCHEDULED" />
  ) : null
) : null}
```

Do not modify `runManualScan`, the store, the loader/error/report branches, or `ScheduledSnapshotStatus` itself.

- [ ] **Step 4: Run the focused page test and verify GREEN**

Run:

```bash
cd apps/web-react
npm test -- ShortTermPage.test.tsx
```

Expected: all tests in `ShortTermPage.test.tsx` pass, including scheduled pulse/status coverage and the new manual-card regression.

- [ ] **Step 5: Commit the behavior change**

```bash
git add apps/web-react/src/pages/ShortTermPage.tsx apps/web-react/src/pages/ShortTermPage.test.tsx
git commit -m "fix: hide manual scan status card"
```

### Task 2: Verify, Deploy, and Push Main

**Files:**
- Verify: `apps/web-react/src/pages/ShortTermPage.tsx`
- Verify: `apps/web-react/src/pages/ShortTermPage.test.tsx`
- Deploy from: `docker-compose.yml`

**Interfaces:**
- Consumes: committed React build on local `main`, existing `shares-test` Compose project, and existing `ai-stock-web` container.
- Produces: verified local Web deployment and `origin/main` pointing at the tested `main` commit.

- [ ] **Step 1: Run complete repository verification**

```bash
mvn -pl apps/api test
(cd apps/web-react && npm test -- --reporter=dot && npm run build)
(cd apps/web && npm run build)
```

Expected: all backend and React tests pass, and both React and Vue production
builds exit 0. Existing Vue bundle-size or dependency annotation warnings do
not fail the build.

- [ ] **Step 2: Confirm repository integrity before deployment**

```bash
git diff --check
git status --short --branch
```

Expected: no diff-check errors and a clean `main` working tree ahead of `origin/main`.

- [ ] **Step 3: Rebuild and replace only the Web container**

```bash
docker compose -p shares-test build web
docker compose -p shares-test up -d --no-deps web
```

Expected: `ai-stock-web` is recreated; `ai-stock-api` and the `shares-test_api-data` database volume are not replaced.

- [ ] **Step 4: Verify the deployed services and manual-card behavior**

Run:

```bash
docker compose -p shares-test ps
docker port ai-stock-web
curl --fail --silent --show-error http://127.0.0.1:19080/actuator/health
```

Expected: `ai-stock-web` and `ai-stock-api` are healthy, the Web port is
`127.0.0.1:5176`, and the API reports `{"status":"UP"}`.

Then open `http://127.0.0.1:5176/short-term` in the local browser, click
`重新扫描`, and wait for the existing manual job to return or enter its running
state. Inspect the rendered page and confirm:

- no top card contains `手动扫描执行中`, `手动最终结果已就绪`, or `手动重算`;
- the `重新扫描` button still exposes its loading state while the request runs;
- the existing result area or existing compact no-report loader remains visible.

Do not trigger an external model call.

- [ ] **Step 5: Push and verify the remote ref**

```bash
git fetch origin
git rev-list --left-right --count main...origin/main
git push origin main
git fetch origin
test "$(git rev-parse main)" = "$(git rev-parse origin/main)"
```

Expected before push: remote has no new commits that would make `main...origin/main` diverge. Expected after push: local and remote `main` hashes are identical.

- [ ] **Step 6: Clean up the merged feature worktree and branch**

From `/Users/mac/Documents/shares-test` after the push is verified:

```bash
git worktree remove /Users/mac/Documents/shares-test/.worktrees/database-runtime-config
git worktree prune
git branch -d codex/database-runtime-config
```

Expected: the already-merged feature worktree is removed and the local feature branch is deleted without force; `main` and running containers remain intact.
