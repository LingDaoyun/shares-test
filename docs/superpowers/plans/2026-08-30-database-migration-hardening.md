# Database Migration Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the confirmed PostgreSQL cutover evidence gaps and prevent incomplete first-board market data from ever being published as `READY`.

**Architecture:** Keep Flyway as the sole schema authority and add a V3 state invariant instead of editing V1/V2. Enforce the same invariant in `FirstBoardMarketDayIngestionService`, add an explicit credential-gated migration verification entry point, and preserve local backup evidence without claiming historical checks that were not retained.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring JDBC, Flyway, PostgreSQL 17, JUnit 5, Mockito, Bash

## Global Constraints

- Work on the current canonical `main` checkout; do not create a branch, worktree, or subagent.
- Do not connect to H2/PostgreSQL, start services, apply Flyway, run tests, build, lint, or deploy.
- Do not modify V1 or V2 after they have been published; add V3.
- Do not invent missing historical row counts, table digests, image IDs, or volume names.
- Keep backup manifests free of passwords, API keys, and `payload_json`.
- Each code change includes a user-owned verification command but Codex does not execute it.

---

## File Map

- Modify `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java` — fail closed when any initial-history symbol fails.
- Modify `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java` — encode the incomplete-universe regression.
- Create `apps/api/src/main/resources/db/migration/V3__first_board_market_day_state_integrity.sql` — normalize unsafe legacy states and enforce state invariants.
- Modify `apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java` — prove V3 repairs unsafe V2 data and rejects future invalid READY rows.
- Create `scripts/verify-postgres-migrations.sh` — credential-gated PostgreSQL verification entry point.
- Modify `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java` — assert the explicit script and the removal of the legacy schema source.
- Delete `infra/db/init/001_schema.sql` — remove the conflicting schema copy.
- Modify `README.md`, `docs/runtime-config.md`, and `docs/architecture.md` — document PostgreSQL-only operation and verification/restore boundaries.
- Create ignored `backups/SHA256SUMS` and `backups/MIGRATION-AUDIT.md` — record current backup evidence.
- Change local modes under `backups/` — directories `0700`, files `0600`.

---

### Task 1: Fail Closed on Incomplete Initial History

**Files:**
- Modify: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java:61-87`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java:100-116`

**Interfaces:**
- Consumes: `FirstBoardIngestionProgress ensureBaselineDayReady(LocalDate scanTradeDate)`.
- Produces: `FAILED` progress with the exact completed/total/failed counts when any gap fetch fails.

- [ ] **Step 1: Replace the permissive regression expectation**

Rename `publishesExistingCoverageWhenOneStockHistoryProviderFails` to `failsClosedWhenOneStockHistoryProviderFails` and assert:

```java
assertThat(progress.status()).isEqualTo(FirstBoardMarketDayStatus.FAILED);
assertThat(progress.completed()).isEqualTo(1);
assertThat(progress.total()).isEqualTo(2);
assertThat(progress.failed()).isEqualTo(1);
assertThat(progress.nextRetryAt()).isNotNull();
assertThat(progress.errorSummary()).contains("历史日线补齐不完整");
```

- [ ] **Step 2: Record the user-owned RED command without running it**

User-owned command:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest#failsClosedWhenOneStockHistoryProviderFails test
```

Expected before the implementation: FAIL because the service returns `READY`.

- [ ] **Step 3: Add the minimal completeness gate**

Immediately after all gap tasks join and before reading the limit-up pool, compute `written` and fail with the current counters:

```java
int written = completed.get();
int failureCount = failed.get();
if (total <= 0 || written != total || failureCount != 0) {
    FirstBoardMarketDay partial = new FirstBoardMarketDay(
            baselineDate, baselineDate, FirstBoardMarketDayStatus.IMPORTING,
            total, written, failureCount, false, null,
            startedAt, null, null, null);
    return fail(baselineDate, startedAt, partial,
            new IllegalStateException(
                    "历史日线补齐不完整: " + written + "/" + total + ", failed=" + failureCount));
}
```

Reuse `written` and `failureCount` when constructing the later READY value. Do not add retries or new state types.

- [ ] **Step 4: Record the user-owned GREEN command without running it**

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected after implementation: both tests PASS.

- [ ] **Step 5: Commit the focused behavior change**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java \
  apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java
git commit -m "fix: fail closed on incomplete first-board history"
```

---

### Task 2: Enforce Market-Day State Integrity in Flyway V3

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V3__first_board_market_day_state_integrity.sql`
- Modify: `apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java`

**Interfaces:**
- Consumes: the V2 `first_board_market_day` table.
- Produces: constraint `ck_first_board_market_day_state_integrity` and repaired legacy rows.

- [ ] **Step 1: Add a PostgreSQL migration regression test**

Create a fresh random schema, migrate only through V2, insert an invalid READY row, then apply all migrations. Assert that the row becomes FAILED with a retry time and that a new invalid READY insert throws `SQLException`. Use the same environment credential gate and schema cleanup pattern already present in `PostgresFlywayMigrationTest`.

The V2 seed row is:

```sql
INSERT INTO first_board_market_day (
  trade_date, source_trade_date, status, expected_symbol_count, written_symbol_count,
  failed_symbol_count, limit_up_pool_complete, bar_data_version, started_at, completed_at,
  next_retry_at, error_summary
) VALUES (
  DATE '2026-08-28', DATE '2026-08-28', 'READY', 2, 1,
  1, TRUE, 'partial', now(), now(), NULL, NULL
)
```

- [ ] **Step 2: Record the user-owned RED command without running it**

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected before V3 exists: the unsafe row remains READY and the regression test fails.

- [ ] **Step 3: Add V3 normalization and the state constraint**

The migration must:

```sql
UPDATE first_board_market_day
SET status = 'FAILED',
    failed_symbol_count = GREATEST(failed_symbol_count, expected_symbol_count - written_symbol_count, 1),
    limit_up_pool_complete = FALSE,
    bar_data_version = NULL,
    completed_at = NULL,
    next_retry_at = COALESCE(next_retry_at, now()),
    error_summary = LEFT(COALESCE(NULLIF(error_summary, ''),
      'Flyway V3: historical READY row failed completeness validation'), 500)
WHERE status = 'READY'
  AND NOT (
    expected_symbol_count > 0
    AND written_symbol_count = expected_symbol_count
    AND failed_symbol_count = 0
    AND limit_up_pool_complete
    AND bar_data_version IS NOT NULL AND LENGTH(TRIM(bar_data_version)) > 0
    AND completed_at IS NOT NULL
    AND next_retry_at IS NULL
    AND error_summary IS NULL
  );
```

Normalize existing FAILED and IMPORTING rows to their fail-closed shapes, then add one CHECK whose three OR branches exactly describe IMPORTING, READY, and FAILED. READY requires complete counts/version/timestamp; FAILED requires a positive failure count, retry timestamp, and nonblank error.

- [ ] **Step 4: Record the user-owned GREEN command without running it**

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected after V3: Flyway applies V1-V3, repairs the V2 seed, rejects a new invalid READY row, and exits 0.

- [ ] **Step 5: Commit V3 and its regression test**

```bash
git add apps/api/src/main/resources/db/migration/V3__first_board_market_day_state_integrity.sql \
  apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java
git commit -m "fix: enforce first-board market-day integrity"
```

---

### Task 3: Add a Non-Skippable Migration Verification Entry Point

**Files:**
- Create: `scripts/verify-postgres-migrations.sh`
- Modify: `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java`

**Interfaces:**
- Consumes: `AISTOCK_TEST_JDBC_URL`, `AISTOCK_TEST_DB_USERNAME`, `AISTOCK_TEST_DB_PASSWORD`.
- Produces: a process that exits before Maven when any required value is blank, otherwise runs only `PostgresFlywayMigrationTest`.

- [ ] **Step 1: Add static assertions for the explicit script**

Read `../../scripts/verify-postgres-migrations.sh` and assert it contains all three environment variable names, a nonzero `exit 1`, and `-Dtest=PostgresFlywayMigrationTest`. Also assert `../../infra/db/init/001_schema.sql` does not exist.

- [ ] **Step 2: Create the script**

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
required=(AISTOCK_TEST_JDBC_URL AISTOCK_TEST_DB_USERNAME AISTOCK_TEST_DB_PASSWORD)
missing=()
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done
if (( ${#missing[@]} > 0 )); then
  echo "PostgreSQL migration verification requires: ${missing[*]}" >&2
  exit 1
fi

cd "$ROOT_DIR"
exec mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Set mode `0755`.

- [ ] **Step 3: Record user-owned verification commands without running them**

```bash
env -u AISTOCK_TEST_JDBC_URL -u AISTOCK_TEST_DB_USERNAME -u AISTOCK_TEST_DB_PASSWORD \
  ./scripts/verify-postgres-migrations.sh
```

Expected: exit 1 before Maven.

```bash
./scripts/verify-postgres-migrations.sh
```

Expected with all credentials configured: migration test executes rather than skips.

- [ ] **Step 4: Delete the conflicting schema source**

Delete `infra/db/init/001_schema.sql`; do not replace it with another initialization file.

- [ ] **Step 5: Commit the verification boundary**

```bash
git add scripts/verify-postgres-migrations.sh \
  apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java \
  infra/db/init/001_schema.sql
git commit -m "chore: make PostgreSQL migration verification explicit"
```

---

### Task 4: Preserve Backup Evidence and Correct Operations Documentation

**Files:**
- Create ignored: `backups/SHA256SUMS`
- Create ignored: `backups/MIGRATION-AUDIT.md`
- Modify: `README.md`
- Modify: `docs/runtime-config.md`
- Modify: `docs/architecture.md`

**Interfaces:**
- Consumes: the two existing backup artifacts only.
- Produces: checksum and recovery metadata without changing either database.

- [ ] **Step 1: Generate current backup metadata**

Use read-only `shasum -a 256` and `stat` on the H2 and PostgreSQL backup files. Write only those observed values to `backups/SHA256SUMS` and `backups/MIGRATION-AUDIT.md` using `apply_patch`.

- [ ] **Step 2: Document the evidence boundary**

State that file existence/size/hash are confirmed, while historical per-table counts/digests, old image ID, and old H2 volume name are unavailable in the workspace and must not be represented as verified.

Include an isolated PostgreSQL restore template using `pg_restore --exit-on-error --no-owner --no-privileges` against a newly created non-production database. Do not provide a command that overwrites `aistock`.

- [ ] **Step 3: Restrict local access**

Set `backups/` and every nested directory to `0700`; set every contained file to `0600`. This is a local data-protection change, not a database operation.

- [ ] **Step 4: Update current documentation**

README must point migration verification to `./scripts/verify-postgres-migrations.sh`, remove `infra/db/init` from the directory map, and describe backup manifests as local ignored artifacts. `docs/runtime-config.md` and `docs/architecture.md` must describe PostgreSQL as the only runtime database and must not mention the old `api-data` H2 volume as current state.

- [ ] **Step 5: Record user-owned final verification without running it**

```bash
./scripts/verify-postgres-migrations.sh
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest,SchemaCompatibilityTest test
```

Expected: migration and focused tests PASS when the user supplies the test database credentials.

- [ ] **Step 6: Commit tracked documentation**

```bash
git add README.md docs/runtime-config.md docs/architecture.md
git commit -m "docs: close PostgreSQL migration operations gaps"
```

Do not add ignored backup artifacts to Git.

---

## Handoff

Report the exact tracked commits, ignored backup artifact paths and resulting modes. Explicitly state that Codex did not run tests, builds, migrations, database connections, services, or restore drills, so no passing or applied-migration claim is made.
