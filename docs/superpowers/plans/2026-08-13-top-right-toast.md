# Top-Right Toast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the existing global floating notification stack from the bottom-right to the top-right without changing its keyed manual-scan lifecycle, persistence, accessibility, or close behavior.

**Architecture:** Keep `ToastViewport` as the single application-wide notification surface and change only its fixed-position utility class. Protect the placement with a jsdom regression test, then run the existing Toast, manual scan store, and short-term page tests before integrated release verification.

**Tech Stack:** React 18, TypeScript 5.6, Zustand 5, Tailwind CSS 3.4, Vitest 3, jsdom, Vite 6

## Global Constraints

- Use the existing `ToastViewport`; do not add a scan-only panel, modal, or restored manual status card.
- The fixed desktop position is exactly `top-6 right-6`, and notifications stack downward.
- Remove `bottom-6` from the viewport.
- Preserve width, z-index, gap, animation, tones, keyed replacement, timers, persistence, manual close, and live-region roles.
- Preserve the stable key `short-term-manual-scan` and all existing lifecycle copy.
- Apply the position consistently to every global Toast message.
- Do not modify scan APIs, polling, backend results, database records, or strategy logic in this plan.

---

### Task 1: Anchor the Existing Toast Viewport at the Top-Right

**Files:**
- Modify: `apps/web-react/src/components/ui/Toast.test.tsx:20-115`
- Modify: `apps/web-react/src/components/ui/Toast.tsx:123-132`

**Interfaces:**
- Consumes: the existing rendered `ToastViewport` and Tailwind position utilities.
- Produces: one global viewport with `top-6 right-6` and no `bottom-6`.

- [ ] **Step 1: Write the failing placement test**

Add this test to `Toast.test.tsx`:

```tsx
it('anchors the global notification stack at the top-right', () => {
  const viewport = host.firstElementChild as HTMLElement | null
  const classes = viewport?.className.split(/\s+/) ?? []

  expect(classes).toEqual(expect.arrayContaining(['fixed', 'top-6', 'right-6']))
  expect(classes).not.toContain('bottom-6')
})
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
cd apps/web-react
npm test -- src/components/ui/Toast.test.tsx
```

Expected: FAIL because the viewport contains `bottom-6` and not `top-6`.

- [ ] **Step 3: Change only the viewport anchor**

Change the `ToastViewport` wrapper to:

```tsx
<div className="pointer-events-none fixed top-6 right-6 z-50 flex w-80 flex-col gap-2">
```

Do not change `ToastRow`, the Zustand store, timers, roles, or scan call sites.

- [ ] **Step 4: Run focused Toast and manual-scan tests and verify GREEN**

```bash
cd apps/web-react
npm test -- \
  src/components/ui/Toast.test.tsx \
  src/store/shortTermScanStore.test.ts \
  src/pages/ShortTermPage.test.tsx
```

Expected: all three files pass, including keyed replacement, persistent terminal warnings, manual close, removed status-card assertions, and top-right placement.

- [ ] **Step 5: Commit the placement change**

```bash
git add apps/web-react/src/components/ui/Toast.tsx \
  apps/web-react/src/components/ui/Toast.test.tsx
git commit -m "fix: move floating notifications to top right"
```

### Task 2: Run Integrated Verification, Deploy, Exercise, and Push

**Files:**
- Verify: `apps/api/src/main/java/com/aistock/research/integration/eastmoney/EastMoneyClient.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermCoverageSnapshot.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Verify: `apps/api/src/main/java/com/aistock/research/shortterm/schedule/ShortTermFinalResultGate.java`
- Verify: `apps/web-react/src/components/ui/Toast.tsx`
- Deploy from: `docker-compose.yml`

**Interfaces:**
- Consumes: the completed effective-coverage plan and Task 1 of this plan.
- Produces: verified local API/Web containers, live manual-scan evidence, and the tested branch integrated into and pushed from `main` without replacing the database volume.

- [ ] **Step 1: Run complete repository verification**

```bash
mvn -pl apps/api test
(cd apps/web-react && npm test -- --reporter=dot && npm run build)
(cd apps/web && npm run build)
```

Expected: the API and React suites have zero failures; the React and Vue production builds exit 0. Existing non-fatal Vue bundle-size or pure-annotation warnings may remain, but no command may fail.

- [ ] **Step 2: Confirm patch and commit integrity**

```bash
git diff --check
git status --short --branch
git log --oneline --decorate -10
```

Expected: no patch errors, no uncommitted implementation files, and the branch contains the approved spec, both plans, stable pagination, dual coverage, precise reasons, and top-right placement commits.

- [ ] **Step 3: Record container and database-volume identity before deployment**

```bash
docker inspect -f '{{.Id}} {{range .Mounts}}{{.Name}}:{{.Destination}} {{end}}' ai-stock-api
docker inspect -f '{{.Id}}' ai-stock-web
docker compose -p shares-test ps
```

Expected: record the existing API/Web container IDs and confirm the API's named `/data` volume before recreation.

- [ ] **Step 4: Rebuild and replace only API and Web services**

```bash
docker compose -p shares-test build api web
docker compose -p shares-test up -d --no-deps api web
```

Expected: only `ai-stock-api` and `ai-stock-web` are recreated from verified source. The named API data volume remains attached; PostgreSQL, Redis, MinIO, and unrelated containers are not recreated.

- [ ] **Step 5: Verify deployed health, ports, and preserved volume**

```bash
docker compose -p shares-test ps
docker port ai-stock-web
docker port ai-stock-api
docker inspect -f '{{range .Mounts}}{{.Name}}:{{.Destination}} {{end}}' ai-stock-api
curl --fail --silent --show-error http://127.0.0.1:19080/actuator/health
```

Expected: Web and API are running, ports remain `127.0.0.1:5176` and `127.0.0.1:19080`, the same named volume is mounted at `/data`, and health returns `{"status":"UP"}`.

- [ ] **Step 6: Exercise a real manual scan and inspect its terminal payload**

Open `http://127.0.0.1:5176/short-term` with the in-app browser, click `重新扫描` once, and verify:

1. exactly one progress Toast appears at the top-right;
2. it says `短线扫描已开始，正在获取实时行情…`;
3. the removed manual status card remains absent;
4. the terminal Toast replaces the progress Toast and remains manually dismissible when it is a warning or error;
5. the terminal API report exposes `rawExpectedCount`, `rawFetchedCount`, `excludedNoPriceCount`, and `rawComplete`;
6. a raw-complete market snapshot does not return `COVERAGE_BELOW_95` solely because fetched rows have no usable current price;
7. if another genuine gate blocks the run, its server message and reason code are displayed without being relabelled as low coverage;
8. closing the Toast does not remove the durable inline report or error.

Expected live coverage shape for the diagnosed market snapshot: raw acquisition is complete near `5895/5895`, roughly 355 known no-price rows are audited, and effective coverage is evaluated against the remaining current-price universe. Exact market counts may move as listings change, so acceptance depends on the invariant rather than a hard-coded production total.

- [ ] **Step 7: Check remote divergence, integrate the tested branch, and push**

Run from the feature worktree first:

```bash
git fetch origin
git rev-list --left-right --count codex/manual-scan-floating-notifications...origin/main
```

Expected: `origin/main` has no unreviewed commit absent from the tested branch. If it has advanced, stop and reconcile the new commits before integration.

Then run:

```bash
git -C /Users/mac/Documents/shares-test status --short --branch
git -C /Users/mac/Documents/shares-test merge --ff-only codex/manual-scan-floating-notifications
git -C /Users/mac/Documents/shares-test push origin main
git -C /Users/mac/Documents/shares-test rev-parse main
git -C /Users/mac/Documents/shares-test rev-parse origin/main
```

Expected: the main checkout is clean before merging, the fast-forward and push succeed, and local `main` equals `origin/main` at the exact commit exercised in the live scan.
