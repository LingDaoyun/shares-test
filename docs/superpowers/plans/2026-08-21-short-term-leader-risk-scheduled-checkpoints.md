# Short-Term Leader Risk Scheduled Checkpoints Implementation Plan

> **For agentic workers:** Execute inline on `main`. The user explicitly owns all testing and regression; do not run or delegate tests, reviews, browser checks, health checks, or diff verification.

**Goal:** Build automatic 09:50, 11:30, and 14:40 leader-risk checkpoints and present a cumulative, plain-Chinese intraday result without requiring repeated manual scans.

**Architecture:** Split checkpoint capture from manual evaluation. Scheduled captures persist both the compact market snapshot and interval risk; manual evaluation is read-only against scheduled checkpoints and merges the day’s signals into ongoing/receded states. A lightweight scheduler reuses the existing full-market quote semantics without running the full stock-selection pipeline.

**Tech Stack:** Java 17, Spring Boot scheduling/JPA/Jackson, React/TypeScript, Docker Compose.

## Global Constraints

- Schedule in `Asia/Shanghai` at `09:50`, `11:30`, and `14:40`.
- Skip closed market days and unreliable quote coverage.
- Manual scans must never save or replace checkpoint baselines.
- Preserve intraday warnings and mark them ongoing or receded.
- Risk remains display-only and outside every recommendation decision path.
- Do not run tests or verification; build only as required for deployment.

---

### Task 1: Persist Scheduled Checkpoint Results and Aggregate the Trading Day

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRiskSignal.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderRiskModule.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotStore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotEntity.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderSnapshotRepository.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/JpaShortTermLeaderSnapshotStore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/leader/DefaultShortTermLeaderRiskModule.java`

**Interfaces:**
- `evaluate(input)` performs a read-only manual evaluation.
- `captureCheckpoint(input)` evaluates and persists one scheduled checkpoint.
- Stored rows contain `snapshot_json` and nullable `risk_json`.
- Same-day checkpoint history is ordered by `captured_at ASC, snapshot_id ASC`.
- Signals append `detectedAt` and `movementState` (`DETECTED`, `ONGOING`, `RECEDED`) with an old-constructor compatibility overload.

Implementation steps:

- [ ] Bump the rule version so legacy manual snapshots cannot become scheduled baselines.
- [ ] Add `risk_json` to the schema/entity and serialize it with the application `ObjectMapper`.
- [ ] Add same-day history lookup and checkpoint save methods to the persistence seam.
- [ ] Stop `evaluate` from saving snapshots.
- [ ] Implement `captureCheckpoint` as the only save path.
- [ ] Merge current and stored same-day warnings, deduplicate signals, and label each stored signal ongoing/receded against the current snapshot.
- [ ] Produce plain summaries for active risk, no risk, recovered intraday risk, and missing checkpoint baseline.
- [ ] Commit the backend domain/persistence change without running tests.

### Task 2: Add Lightweight Background Capture at the Three Approved Times

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/shortterm/ShortTermService.java`
- Create: `apps/api/src/main/java/com/aistock/research/shortterm/leader/ShortTermLeaderCheckpointScheduler.java`

**Interfaces:**
- `ShortTermService.captureLeaderRiskCheckpoint()` fetches the same point-in-time full-market quote snapshot, builds reliable coverage and hot directions, and calls `captureCheckpoint` with no candidate industries.
- `ShortTermLeaderCheckpointScheduler` registers three `Asia/Shanghai` cron tasks: `0 50 9 * * MON-FRI`, `0 30 11 * * MON-FRI`, `0 40 14 * * MON-FRI`.

Implementation steps:

- [ ] Add a lightweight capture method that performs no K-line, scoring, ranking, financial, candidate, or trade-plan work.
- [ ] Add the three cron triggers and a `TradingClockService.isMarketClosedDay` guard.
- [ ] Use the existing short-term scheduled executor so cron threads do not block on the quote request.
- [ ] Catch and log capture failures without affecting the normal short-term service.
- [ ] Commit the scheduler/capture change without running tests.

### Task 3: Replace the Internal Status Card with a Plain Intraday Result

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/components/shortterm/ShortTermLeaderRiskCard.tsx`

Implementation steps:

- [ ] Add optional signal detection time and movement-state fields.
- [ ] Remove English headings and internal baseline language.
- [ ] Put the user conclusion first: risk, clear, recovered, or baseline not yet ready.
- [ ] Show the three fixed background times and explain that manual scans do not establish baselines.
- [ ] Label each historical signal “仍在强化” or “已经回落”.
- [ ] Keep the exact advisory-only boundary and do not alter candidate rendering/order.
- [ ] Commit the frontend change without running tests.

### Task 4: Deploy Without Self-Testing

- [ ] Package the API with `mvn -pl apps/api -Dmaven.test.skip=true package`.
- [ ] Generate the frontend production artifact with `npm --prefix apps/web-react run build` only because Docker deployment requires `dist`.
- [ ] Run `docker compose up -d --build api web`.
- [ ] Do not run tests, health checks, logs, curl, browser checks, or other verification.

