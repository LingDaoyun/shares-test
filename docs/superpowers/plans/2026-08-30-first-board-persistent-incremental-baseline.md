# 首板持久化增量基线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 PostgreSQL 上持久化首板规范日线、每日完整性和技术基线，使首次数据只补缺口、正常每天只增加一个交易日，并让 API 重启后直接恢复候选。

**Architecture:** `FirstBoardDailyBarStore` 管理规范日线和每日完整性，`FirstBoardBaselineStore` 管理不可变构建 run 与技术条目；网络导入和技术计算通过两个窄接口连接。基线只有在日线日期、规则版本和数据版本全部匹配时才能原子发布为 READY，任何缺失或失败都返回空候选。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring JDBC/JPA transactions, PostgreSQL 16, Flyway, JUnit 5, Mockito, React 18, TypeScript, Vitest

## Global Constraints

- 本计划在 `2026-08-30-postgresql-only-migration.md` 完成且 PostgreSQL 数据通过验收后执行。
- 首次种子优先复用 `market_kline_history` 中最新 `DAY_QFQ` 版本，只对缺失或不足 60 根的股票访问历史行情。
- 正常交易日只写入一个新交易日；停机多日才按实际缺失日期顺序补齐。
- 基线、规则版本或目标日期不匹配时不得展示旧候选；状态失败时候选数组必须为空。
- 创业板和科创板默认关闭但可分别打开；主板始终扫描。
- 上一交易日涨停股票继续进入独立观察通道，既有成交量门槛不放宽。
- 页面只显示技术指标和页面通知，不增加逐股触发原因或外部消息渠道。
- 非交易日扫描最近交易日收盘行情，基线再回退一个交易日。
- 不引入 Redis、第二个数据库、TimescaleDB、分库分表或新的微服务。
- 保留工作区中的其他改动；提交时只暂存每个任务列出的文件。

---

## File Map

### Database and storage seam

- Create: `apps/api/src/main/resources/db/migration/V2__first_board_persistent_baseline.sql` — 四张首板持久化表和索引。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayStatus.java` — 日线导入状态。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRunStatus.java` — 基线 run 状态。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDay.java` — 每日完整性领域值。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRun.java` — 持久化 run 元数据。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java` — 日线存储接口。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardDailyBarStore.java` — PostgreSQL 批量实现。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineStore.java` — 基线发布/读取接口。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardBaselineStore.java` — PostgreSQL 原子发布实现。
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardPersistenceStoreTest.java` — PostgreSQL 集成测试。

### Ingestion and calculation

- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDataGateway.java` — 首板所需网络数据窄接口。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/EastMoneyFirstBoardMarketDataGateway.java` — 现有东财客户端适配器。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardIngestionProgress.java` — 可持久恢复的进度值。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java` — 首次种子、日增量和漏日补齐。
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java` — 缺口和失败关闭测试。
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardTechnicalEvaluator.java` — 保持纯计算并接收本地日线。

### Baseline lifecycle

- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java` — 从内存全量网络构建改为 PostgreSQL 恢复/发布。
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineSnapshot.java` — 增加 run/data 版本，不再作为唯一事实源。
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineAccess.java` — 返回阶段、计数和重试时间。
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineScheduler.java` — 15:20 增量和启动对账。
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServicePersistenceTest.java` — 重启恢复、原子发布和规则重算。

### Page contract

- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertStatus.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertController.java`
- Modify: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardAlertServiceTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/FirstBoardAlertPage.tsx`
- Modify: `apps/web-react/src/pages/FirstBoardAlertPage.test.tsx`

---

### Task 1: Create PostgreSQL tables and persistence interfaces

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V2__first_board_persistent_baseline.sql`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRunStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDay.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRun.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineStore.java`

**Interfaces:**
- Consumes: Flyway PostgreSQL V1 from the database plan.
- Produces: V2 tables plus exact store signatures used by every later task.

- [ ] **Step 1: Add a failing PostgreSQL schema test**

Extend `PostgresFlywayMigrationTest.migratesAnEmptyPostgresDatabaseAndIsIdempotent()` table assertion with:

```java
assertThat(tables).contains(
        "first_board_daily_bar",
        "first_board_market_day",
        "first_board_baseline_run",
        "first_board_baseline_item"
);
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected: FAIL because V2 tables do not exist.

- [ ] **Step 3: Create the complete V2 migration**

Create `V2__first_board_persistent_baseline.sql`:

```sql
CREATE TABLE first_board_daily_bar (
  symbol VARCHAR(6) NOT NULL,
  trade_date DATE NOT NULL,
  open_price NUMERIC(20, 6) NOT NULL,
  close_price NUMERIC(20, 6) NOT NULL,
  high_price NUMERIC(20, 6) NOT NULL,
  low_price NUMERIC(20, 6) NOT NULL,
  volume NUMERIC(30, 4) NOT NULL,
  amount NUMERIC(30, 4),
  turnover_rate NUMERIC(12, 4),
  adjust_mode VARCHAR(16) NOT NULL,
  was_limit_up BOOLEAN NOT NULL DEFAULT FALSE,
  limit_up_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  source_name VARCHAR(128) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (symbol, trade_date),
  CONSTRAINT ck_first_board_bar_adjust CHECK (adjust_mode = 'QFQ'),
  CONSTRAINT ck_first_board_bar_prices CHECK (
    open_price > 0 AND close_price > 0 AND high_price >= low_price
    AND high_price >= open_price AND high_price >= close_price
    AND low_price <= open_price AND low_price <= close_price
  )
);

CREATE INDEX idx_first_board_bar_symbol_date
  ON first_board_daily_bar(symbol, trade_date DESC);
CREATE INDEX idx_first_board_bar_trade_date
  ON first_board_daily_bar(trade_date, symbol);

CREATE INDEX idx_kline_symbol_date_observed_desc
  ON market_kline_history(symbol, trade_date, observed_at DESC);
CREATE INDEX idx_kline_trade_date_brin
  ON market_kline_history USING BRIN(trade_date);

CREATE TABLE first_board_market_day (
  trade_date DATE PRIMARY KEY,
  source_trade_date DATE,
  status VARCHAR(16) NOT NULL,
  expected_symbol_count INTEGER NOT NULL DEFAULT 0,
  written_symbol_count INTEGER NOT NULL DEFAULT 0,
  failed_symbol_count INTEGER NOT NULL DEFAULT 0,
  limit_up_pool_complete BOOLEAN NOT NULL DEFAULT FALSE,
  bar_data_version VARCHAR(64),
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  next_retry_at TIMESTAMP WITH TIME ZONE,
  error_summary VARCHAR(500),
  CONSTRAINT ck_first_board_market_day_status CHECK (status IN ('IMPORTING', 'READY', 'FAILED')),
  CONSTRAINT ck_first_board_market_day_counts CHECK (
    expected_symbol_count >= 0 AND written_symbol_count >= 0 AND failed_symbol_count >= 0
  )
);

CREATE TABLE first_board_baseline_run (
  run_id UUID PRIMARY KEY,
  scan_trade_date DATE NOT NULL,
  baseline_trade_date DATE NOT NULL,
  rule_version VARCHAR(64) NOT NULL,
  bar_data_version VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  universe_count INTEGER NOT NULL,
  supported_count INTEGER NOT NULL,
  excluded_name_count INTEGER NOT NULL,
  insufficient_history_count INTEGER NOT NULL,
  item_count INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE,
  error_summary VARCHAR(500),
  CONSTRAINT ck_first_board_run_status CHECK (status IN ('BUILDING', 'READY', 'FAILED'))
);

CREATE INDEX idx_first_board_run_lookup
  ON first_board_baseline_run(scan_trade_date, rule_version, published_at DESC);

CREATE TABLE first_board_baseline_item (
  run_id UUID NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  industry VARCHAR(128),
  board VARCHAR(16) NOT NULL,
  baseline_date DATE NOT NULL,
  previous_close NUMERIC(20, 6) NOT NULL,
  ma10 NUMERIC(20, 6) NOT NULL,
  ma20 NUMERIC(20, 6) NOT NULL,
  ma20_slope NUMERIC(20, 6) NOT NULL,
  rsi14 NUMERIC(20, 6) NOT NULL,
  macd_histogram NUMERIC(20, 6) NOT NULL,
  technical_pass_count INTEGER NOT NULL,
  previous_day_volume_ratio NUMERIC(20, 6) NOT NULL,
  recent_five_max_volume_ratio NUMERIC(20, 6) NOT NULL,
  upper_shadow_ratio NUMERIC(20, 6) NOT NULL,
  three_day_close_range NUMERIC(20, 6) NOT NULL,
  previous_limit_up BOOLEAN NOT NULL,
  PRIMARY KEY (run_id, symbol),
  CONSTRAINT fk_first_board_item_run FOREIGN KEY (run_id)
    REFERENCES first_board_baseline_run(run_id) ON DELETE CASCADE,
  CONSTRAINT ck_first_board_item_board CHECK (board IN ('MAIN', 'GROWTH', 'STAR'))
);

CREATE INDEX idx_first_board_item_symbol_date
  ON first_board_baseline_item(symbol, baseline_date DESC);
```

- [ ] **Step 4: Define status and metadata records**

Create these types exactly:

```java
package com.aistock.research.firstboard;
public enum FirstBoardMarketDayStatus { IMPORTING, READY, FAILED }
```

```java
package com.aistock.research.firstboard;
public enum FirstBoardBaselineRunStatus { BUILDING, READY, FAILED }
```

```java
package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;

public record FirstBoardMarketDay(
        LocalDate tradeDate,
        LocalDate sourceTradeDate,
        FirstBoardMarketDayStatus status,
        int expectedSymbolCount,
        int writtenSymbolCount,
        int failedSymbolCount,
        boolean limitUpPoolComplete,
        String barDataVersion,
        Instant startedAt,
        Instant completedAt,
        Instant nextRetryAt,
        String errorSummary
) {}
```

```java
package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FirstBoardBaselineRun(
        UUID runId,
        LocalDate scanTradeDate,
        LocalDate baselineTradeDate,
        String ruleVersion,
        String barDataVersion,
        FirstBoardBaselineRunStatus status,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount,
        int itemCount,
        Instant startedAt,
        Instant publishedAt,
        String errorSummary
) {}
```

- [ ] **Step 5: Define the two storage interfaces**

Create `FirstBoardDailyBarStore.java`:

```java
package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.EastMoneyKLine;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

interface FirstBoardDailyBarStore {
    int importLatestHistoryVersions();
    void upsertBars(List<EastMoneyKLine> rows, String sourceName);
    void confirmLimitUpPool(LocalDate tradeDate, Set<String> limitUpSymbols);
    Map<String, Integer> completedBarCounts(Collection<String> symbols, LocalDate endDate, int maximumBars);
    List<EastMoneyKLine> recentBars(String symbol, LocalDate endDate, int maximumBars);
    Optional<FirstBoardMarketDay> marketDay(LocalDate tradeDate);
    Optional<LocalDate> latestReadyMarketDay();
    void saveMarketDay(FirstBoardMarketDay marketDay);
    String calculateBarDataVersion(LocalDate tradeDate);
}
```

Create `FirstBoardBaselineStore.java`:

```java
package com.aistock.research.firstboard;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

interface FirstBoardBaselineStore {
    FirstBoardBaselineRun startRun(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion,
            int universeCount,
            int supportedCount,
            int excludedNameCount,
            int insufficientHistoryCount
    );
    FirstBoardBaselineSnapshot publish(FirstBoardBaselineRun run, Collection<FirstBoardBaseline> items);
    void fail(FirstBoardBaselineRun run, String errorSummary);
    Optional<FirstBoardBaselineSnapshot> findReady(
            LocalDate scanTradeDate,
            LocalDate baselineTradeDate,
            String ruleVersion,
            String barDataVersion
    );
}
```

- [ ] **Step 6: Run the migration test**

Run:

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected: PASS and all four tables exist.

- [ ] **Step 7: Commit schema and interfaces**

```bash
git add apps/api/src/main/resources/db/migration/V2__first_board_persistent_baseline.sql apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayStatus.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRunStatus.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDay.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineRun.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineStore.java apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java
git commit -m "feat: add first-board persistence schema"
```

---

### Task 2: Implement PostgreSQL stores with batch and atomic semantics

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardDailyBarStore.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardBaselineStore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineSnapshot.java`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardPersistenceStoreTest.java`

**Interfaces:**
- Consumes: store signatures and V2 tables from Task 1.
- Produces: PostgreSQL implementations; published snapshots are visible only after item insertion and READY update commit together.

- [ ] **Step 1: Write failing store integration tests**

Create `FirstBoardPersistenceStoreTest` with the existing `PostgresFlywayMigrationTest` container pattern. Call Flyway once in `@BeforeAll`, truncate the four first-board tables plus `market_kline_history` in `@BeforeEach`, and construct both stores with the container-backed `JdbcTemplate`. Do not mock JDBC.

Implement these four executable tests with the stated exact setup and assertions:

- `importsOnlyLatestObservedVersionPerSymbolAndDate`: insert observation `old` for `600000/2026-08-28` with close `10.00` and observation `new` with close `10.50` one minute later; call `importLatestHistoryVersions()`; query the canonical table and assert one row, close `10.500000`, content hash `new`.
- `upsertIsIdempotentAndChangesContentHashOnlyWhenBarChanges`: upsert one concrete `EastMoneyKLine` twice, assert row count one; upsert the same key with close/high changed from `10.00` to `10.20`, assert row count remains one and close becomes `10.200000`.
- `publishMakesRunAndAllItemsVisibleTogether`: start a run for scan date `2026-08-31`, publish concrete `600000` and `000001` baselines, assert the returned run is READY, item count is two, and both symbols are present.
- `findReadyRequiresExactDateRuleAndBarVersion`: publish rule `first-board-baseline-v1` and bar version `bars-a`; assert exact lookup is present while lookups with `bars-b`, rule `v2`, scan date `2026-09-01`, or baseline date `2026-08-27` are all empty.

- [ ] **Step 2: Run the tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardPersistenceStoreTest test
```

Expected: compilation fails because PostgreSQL store implementations do not exist.

- [ ] **Step 3: Implement canonical history import and idempotent upsert**

In `PostgresFirstBoardDailyBarStore`, inject `JdbcTemplate` and `NamedParameterJdbcTemplate`. Implement `importLatestHistoryVersions()` with this PostgreSQL statement:

```sql
INSERT INTO first_board_daily_bar (
  symbol, trade_date, open_price, close_price, high_price, low_price,
  volume, amount, turnover_rate, adjust_mode, was_limit_up,
  limit_up_confirmed, source_name, content_hash, observed_at, updated_at
)
SELECT DISTINCT ON (symbol, trade_date)
  symbol, trade_date, open_price, close_price, high_price, low_price,
  volume, amount, turnover_rate, 'QFQ', FALSE, FALSE, source_name,
  observation_id, observed_at, now()
FROM market_kline_history
WHERE bar_type = 'DAY_QFQ'
  AND open_price IS NOT NULL AND close_price IS NOT NULL
  AND high_price IS NOT NULL AND low_price IS NOT NULL AND volume IS NOT NULL
ORDER BY symbol, trade_date, observed_at DESC
ON CONFLICT (symbol, trade_date) DO UPDATE SET
  open_price = EXCLUDED.open_price,
  close_price = EXCLUDED.close_price,
  high_price = EXCLUDED.high_price,
  low_price = EXCLUDED.low_price,
  volume = EXCLUDED.volume,
  amount = EXCLUDED.amount,
  turnover_rate = EXCLUDED.turnover_rate,
  source_name = EXCLUDED.source_name,
  content_hash = EXCLUDED.content_hash,
  observed_at = EXCLUDED.observed_at,
  updated_at = now()
WHERE first_board_daily_bar.content_hash <> EXCLUDED.content_hash;
```

Implement `upsertBars` with `JdbcTemplate.batchUpdate` and the same `ON CONFLICT ... WHERE content_hash <> EXCLUDED.content_hash` rule. Generate SHA-256 from symbol, date, OHLC, volume, amount, turnover and `QFQ` using the canonical `stripTrailingZeros().toPlainString()` representation already used by `KlineHistoryRecorder`.

- [ ] **Step 4: Implement limit-up confirmation and market-day state**

`confirmLimitUpPool(tradeDate, symbols)` must execute in one transaction:

```sql
UPDATE first_board_daily_bar
SET was_limit_up = FALSE, limit_up_confirmed = TRUE, updated_at = now()
WHERE trade_date = ?;
```

Then batch-update the returned symbols:

```sql
UPDATE first_board_daily_bar
SET was_limit_up = TRUE, limit_up_confirmed = TRUE, updated_at = now()
WHERE trade_date = ? AND symbol = ?;
```

An empty successfully fetched pool is valid and still confirms all rows as false. A thrown network exception must not call this method.

Implement `saveMarketDay` as an upsert keyed by `trade_date`, and `calculateBarDataVersion` as SHA-256 over ordered `(symbol, content_hash, was_limit_up, limit_up_confirmed)` values for that date.

- [ ] **Step 5: Implement atomic baseline publication**

`PostgresFirstBoardBaselineStore.publish` must be `@Transactional` and perform these actions in order:

1. Verify the run row is still `BUILDING`.
2. Batch insert every `FirstBoardBaseline` into `first_board_baseline_item`.
3. Update the run to `READY`, set `item_count` and `published_at`.
4. Reload that exact run and its items into a `FirstBoardBaselineSnapshot`.

`findReady` must use this lookup before loading items:

```sql
SELECT *
FROM first_board_baseline_run
WHERE scan_trade_date = ?
  AND baseline_trade_date = ?
  AND rule_version = ?
  AND bar_data_version = ?
  AND status = 'READY'
ORDER BY published_at DESC
LIMIT 1;
```

Reconstruct each `FirstBoardBaseline` with `recentBars = List.of()`; charts read bars through the daily-bar store.

- [ ] **Step 6: Add run metadata to the snapshot record**

Make `FirstBoardBaselineSnapshot` contain:

```java
record FirstBoardBaselineSnapshot(
        java.util.UUID runId,
        java.time.LocalDate tradeDate,
        java.time.LocalDate baselineDate,
        String ruleVersion,
        String barDataVersion,
        java.time.Instant generatedAt,
        java.util.Map<String, FirstBoardBaseline> stocks,
        int universeCount,
        int supportedCount,
        int excludedNameCount,
        int insufficientHistoryCount
) {
    FirstBoardBaselineSnapshot {
        stocks = stocks == null ? java.util.Map.of() : java.util.Map.copyOf(stocks);
    }
}
```

- [ ] **Step 7: Run store tests**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardPersistenceStoreTest test
```

Expected: PASS, including exact-match lookup and atomic item visibility.

- [ ] **Step 8: Commit the PostgreSQL store implementations**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardDailyBarStore.java apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardBaselineStore.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineSnapshot.java apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardPersistenceStoreTest.java
git commit -m "feat: persist first-board bars and baselines"
```

---

### Task 3: Implement resumable initial seed and daily ingestion

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDataGateway.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/EastMoneyFirstBoardMarketDataGateway.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardIngestionProgress.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java`

**Interfaces:**
- Consumes: `FirstBoardDailyBarStore`, `EastMoneyClient`, `TradingClockService`.
- Produces: `ensureBaselineDayReady(scanTradeDate)` and `ingestClosedDay(tradeDate)`; both return persistent progress and never mark partial data READY.

- [ ] **Step 1: Define the gateway and progress contract**

Create `FirstBoardMarketDataGateway.java`:

```java
package com.aistock.research.firstboard;

import com.aistock.research.integration.eastmoney.AshareQuoteSnapshot;
import com.aistock.research.integration.eastmoney.EastMoneyKLine;
import com.aistock.research.integration.eastmoney.EastMoneyLimitUpPoolEntry;

import java.time.LocalDate;
import java.util.List;

interface FirstBoardMarketDataGateway {
    AshareQuoteSnapshot fullMarketQuotes();
    List<EastMoneyKLine> dailyBars(String symbol, LocalDate start, LocalDate end);
    List<EastMoneyLimitUpPoolEntry> limitUpPool(LocalDate tradeDate);
}
```

Create `FirstBoardIngestionProgress.java`:

```java
package com.aistock.research.firstboard;

import java.time.Instant;
import java.time.LocalDate;

public record FirstBoardIngestionProgress(
        LocalDate tradeDate,
        FirstBoardMarketDayStatus status,
        String phase,
        int completed,
        int total,
        int failed,
        Instant nextRetryAt,
        String errorSummary
) {}
```

`EastMoneyFirstBoardMarketDataGateway.fullMarketQuotes()` calls `fetchAshareQuoteSnapshot(6000)`; `dailyBars` calls `fetchDailyKLines`; `limitUpPool` calls `fetchLimitUpPool`. A returned empty limit-up list is successful; a fetch failure is represented by an exception.

- [ ] **Step 2: Write failing ingestion tests**

Create tests with an in-memory fake `FirstBoardDailyBarStore`, a mocked gateway, and a fixed Shanghai clock. Implement these exact cases:

- `seedImportsHistoryThenFetchesOnlySymbolsBelowSixtyBars`: return two eligible quotes, stored counts `600000=60` and `000001=59`; return 60 bars for `000001`; assert `dailyBars` is called once for `000001` and never for `600000`, then assert the day becomes READY with completed `2/2`.
- `persistsEachSuccessfulSymbolSoRetryOnlyFetchesRemainingGaps`: start both symbols at 59, make the first call persist `600000` and throw for `000001`; on retry expose count 60 for `600000` and 59 for `000001`; assert total gateway calls are one for `600000` and two for `000001`.
- `closedDayUsesOneCompleteMarketSnapshotAndOneLimitUpPool`: return a complete two-quote snapshot for `2026-08-28` and a one-symbol limit-up pool; call `ingestClosedDay`; verify both gateway methods exactly once and assert READY with expected/written `2/2`.
- `incompleteQuoteSnapshotMarksDayFailedAndNeverReady`: return `complete=false`; assert saved status FAILED, completed count zero, non-null retry time, and no limit-up call.
- `emptyButSuccessfulLimitUpPoolConfirmsTheDay`: return a complete quote snapshot and `List.of()` for the pool; assert `confirmLimitUpPool(date, Set.of())` is called and the final day is READY.

- [ ] **Step 3: Run tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected: compilation fails because the ingestion service does not exist.

- [ ] **Step 4: Implement first seed as a resumable gap fill**

`ensureBaselineDayReady(scanTradeDate)` must:

1. Resolve `baselineDate = tradingClockService.previousTradingDay(scanTradeDate)`.
2. Return immediately when `marketDay(baselineDate)` is READY.
3. Call `importLatestHistoryVersions()` only when `latestReadyMarketDay()` is empty; once any seed day is READY, later startups and missed-day recovery skip the full history import.
4. Fetch a complete full-market quote snapshot and reject `complete=false`.
5. Apply existing board support and `eligibleName` rules to obtain the expected universe.
6. Query `completedBarCounts(symbols, baselineDate, 60)` in one database call.
7. For only counts below 60, fetch `baselineDate.minusDays(260)` through `baselineDate`, persist each successful response immediately, and update persistent progress after each batch.
8. Fetch and confirm the baseline-date limit-up pool.
9. Recount; mark READY only when every eligible symbol has at least 60 bars and the pool is confirmed.

Use a bounded executor of 12 threads only for the one-time missing-history fetch. Do not create a per-request executor; create it once and stop it with `@PreDestroy`.

- [ ] **Step 5: Implement the normal one-day close path**

`ingestClosedDay(tradeDate)` must reject a day for which `TradingClockService.isCompletedDailyBar(tradeDate)` is false. For a completed day:

- fetch one `AshareQuoteSnapshot` and require `complete=true`;
- require all accepted quotes to have `quote.tradeDate().equals(tradeDate)`;
- convert each quote to `EastMoneyKLine(symbol, tradeDate, open, latest, high, low, volume, amount, turnoverRate)`;
- require non-null positive OHLC and volume before including a row;
- upsert the batch;
- fetch/confirm the day’s limit-up pool;
- calculate `barDataVersion` and save READY only when written count equals expected eligible count.

On any exception save FAILED with a message capped at 500 characters and `nextRetryAt = now + 5 minutes`; do not overwrite an existing READY day with FAILED.

- [ ] **Step 6: Run ingestion tests**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected: PASS; the retry test proves already persisted symbols are not downloaded again.

- [ ] **Step 7: Commit ingestion**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDataGateway.java apps/api/src/main/java/com/aistock/research/firstboard/EastMoneyFirstBoardMarketDataGateway.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardIngestionProgress.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java
git commit -m "feat: add resumable first-board market ingestion"
```

---

### Task 4: Replace volatile baseline construction with persisted publication and recovery

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineAccess.java`
- Create: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineScheduler.java`
- Create: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServicePersistenceTest.java`

**Interfaces:**
- Consumes: `ensureBaselineDayReady`, both persistence stores, `FirstBoardTechnicalEvaluator`, and `TradingClockService`.
- Produces: restart-safe `access(scanTradeDate)` and local `recentBars` reads; scheduled daily reconciliation at 15:20 Asia/Shanghai.

- [ ] **Step 1: Write failing persistence lifecycle tests**

Create `FirstBoardBaselineServicePersistenceTest` with mocked stores, ingestion service and evaluator. Implement these exact cases:

- `restartLoadsExactReadyRunWithoutCallingIngestion`: return READY market day `2026-08-27/bars-a` and an exact persisted scan snapshot for `2026-08-28`; assert `access(2026-08-28)` is READY with the same run ID and `verifyNoInteractions(ingestionService, evaluator)`.
- `ruleVersionChangeRecalculatesFromLocalBarsWithoutDownloadingHistory`: return READY market day but no matching run; return 60 local bars; call access and await the captured asynchronous calculation; verify evaluator and `baselineStore.publish` are called, while ingestion is never asked to fetch history.
- `failedOrImportingMarketDayReturnsNoSnapshot`: return FAILED, then IMPORTING in two assertions; both accesses must have null snapshot and empty candidate-capable data.
- `publisherDoesNotExposeBuildingRun`: return no READY lookup while the captured publisher future is incomplete; assert status UPDATING and null snapshot; complete publication, call again, assert READY.
- `recentBarsReadsTwentyRowsFromDailyBarStore`: return 20 rows and assert conversion preserves first/last dates and count exactly 20.
- `weekendUsesFridayScanDateAndThursdayBaselineDate`: fixed time Sunday `2026-08-30`; call access for effective Friday `2026-08-28`; verify store lookup uses baseline Thursday `2026-08-27`.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardBaselineServicePersistenceTest test
```

Expected: FAIL because the current service only knows its volatile `readySnapshot` and network build.

- [ ] **Step 3: Replace the baseline access contract**

Make `FirstBoardBaselineAccess` contain:

```java
record FirstBoardBaselineAccess(
        FirstBoardAlertStatus status,
        FirstBoardBaselineSnapshot snapshot,
        FirstBoardIngestionProgress progress,
        java.util.List<String> dataGaps
) {
    FirstBoardBaselineAccess {
        dataGaps = dataGaps == null ? java.util.List.of() : java.util.List.copyOf(dataGaps);
    }
}
```

Provide factories `initializing(progress)`, `updating(progress)`, `ready(snapshot)`, and `failed(progress)`. Only `ready` accepts a non-null snapshot.

- [ ] **Step 4: Rewrite `FirstBoardBaselineService.access` around exact persisted state**

Use constant:

```java
static final String BASELINE_RULE_VERSION = "first-board-baseline-v1";
```

For an effective scan date:

1. Resolve baseline date.
2. Read `first_board_market_day` for that baseline date.
3. When absent/importing, start or reuse one asynchronous ingestion and return INITIALIZING/UPDATING with persisted progress.
4. When FAILED and before `nextRetryAt`, return FAILED with no snapshot.
5. When READY, call `baselineStore.findReady(scanDate, baselineDate, BASELINE_RULE_VERSION, barDataVersion)`.
6. Return it immediately when present; this is the restart fast path.
7. Otherwise start one asynchronous local calculation and return UPDATING.

Local calculation reads recent bars from PostgreSQL, invokes the existing evaluator, starts a run, and publishes only after every eligible stock is evaluated. A per-stock evaluator exception increments `insufficientHistoryCount`; an empty evaluated market fails the run.

Remove the old 12-thread per-symbol network calls, `volatile readySnapshot`, `failedDate`, and `failedAt`. Keep only an in-process map of active futures to deduplicate concurrent work; it is coordination, not data storage.

- [ ] **Step 5: Read chart bars from PostgreSQL**

Implement:

```java
Optional<List<FirstBoardBar>> recentBars(LocalDate tradeDate, String symbol) {
    LocalDate baselineDate = tradingClockService.previousTradingDay(tradeDate);
    List<com.aistock.research.integration.eastmoney.EastMoneyKLine> rows =
            dailyBarStore.recentBars(symbol, baselineDate, 20);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.stream()
            .map(row -> new FirstBoardBar(
                    row.tradeDate(), row.open(), row.close(), row.high(), row.low(), row.volume()))
            .toList());
}
```

- [ ] **Step 6: Add scheduled and startup reconciliation**

Create `FirstBoardBaselineScheduler`:

```java
package com.aistock.research.firstboard;

import com.aistock.research.trading.TradingClockService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class FirstBoardBaselineScheduler {
    private final FirstBoardBaselineService baselineService;
    private final FirstBoardMarketDayIngestionService ingestionService;
    private final TradingClockService clock;

    FirstBoardBaselineScheduler(
            FirstBoardBaselineService baselineService,
            FirstBoardMarketDayIngestionService ingestionService,
            TradingClockService clock
    ) {
        this.baselineService = baselineService;
        this.ingestionService = ingestionService;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reconcileOnStartup() {
        baselineService.access(effectiveScanDate());
    }

    @Scheduled(cron = "0 20 15 * * MON-FRI", zone = "Asia/Shanghai")
    void ingestCloseAndPrepareNextTradingDay() {
        var today = clock.currentMarketDate();
        ingestionService.ingestClosedDay(today);
        baselineService.access(clock.nextTradingDay(today));
    }

    private java.time.LocalDate effectiveScanDate() {
        var today = clock.currentMarketDate();
        return clock.isMarketClosedDay(today) ? clock.previousTradingDay(today) : today;
    }
}
```

The startup listener must return quickly because `access` schedules work asynchronously. It must not block Spring startup on network calls.

- [ ] **Step 7: Add missed-day reconciliation**

When the latest READY market day precedes the required baseline day, iterate with `TradingClockService.nextTradingDay` until the required date. The most recent completed day may use one full-market close snapshot; older missing dates use `dailyBars(symbol, missingDate, missingDate)` only for the missing dates. Persist each day separately and stop at the first FAILED day.

Add a test with two missed trading days and assert call order, two READY market-day rows, then one exact run for the next scan date.

- [ ] **Step 8: Run baseline lifecycle tests**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardBaselineServicePersistenceTest,FirstBoardTechnicalEvaluatorTest test
```

Expected: PASS; restart test has zero network interactions.

- [ ] **Step 9: Commit persisted lifecycle**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineService.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineAccess.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardBaselineScheduler.java apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardBaselineServicePersistenceTest.java
git commit -m "feat: restore first-board baselines from PostgreSQL"
```

---

### Task 5: Enforce fail-closed page status and expose real progress

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertStatus.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertSnapshot.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertService.java`
- Modify: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardAlertServiceTest.java`
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/pages/FirstBoardAlertPage.tsx`
- Modify: `apps/web-react/src/pages/FirstBoardAlertPage.test.tsx`

**Interfaces:**
- Consumes: enriched `FirstBoardBaselineAccess` from Task 4.
- Produces: four-state page contract `INITIALIZING | UPDATING | READY | FAILED`; all non-READY states have empty candidate arrays.

- [ ] **Step 1: Write backend fail-closed tests**

Add three executable cases to `FirstBoardAlertServiceTest`:

- `baselineFailureReturnsFailedWithNoCandidates`: baseline access returns FAILED progress for `2026-08-27`; assert status FAILED, both candidate lists empty, and `verifyNoInteractions(eastMoneyClient)`.
- `incompleteIntradayQuoteReturnsFailedInsteadOfStaleCandidates`: configure a READY baseline and `fetchAshareQuoteSnapshot` to return one complete snapshot followed by one incomplete snapshot after cache expiry; assert the second response is FAILED, both candidate lists empty, and no candidate from the first response remains.
- `updatingResponseIncludesPersistentCounts`: baseline access returns UPDATING with completed `1832`, total `5120`, failed `3`; assert those exact response fields and empty candidates.

Delete any test that expects `STALE` reuse; no replacement may assert stale candidates.

- [ ] **Step 2: Run backend tests and verify failure**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardAlertServiceTest test
```

Expected: FAIL because current code returns PREPARING/UNAVAILABLE and reuses stale intraday candidates.

- [ ] **Step 3: Replace the status and response contract**

Use:

```java
public enum FirstBoardAlertStatus {
    INITIALIZING,
    UPDATING,
    READY,
    FAILED
}
```

Add these fields to `FirstBoardAlertSnapshot` immediately after `ruleVersion`:

```java
String phase,
int progressCompleted,
int progressTotal,
int progressFailed,
Instant baselinePublishedAt,
Instant nextRetryAt,
```

Every constructor call must populate them. READY uses phase `READY`, completed/total from coverage, `progressFailed=0`, and the persisted run publish time.

- [ ] **Step 4: Remove stale candidate fallback**

Delete `lastGood`, `CachedSnapshot`, `stale(...)`, and the `STALE` branch. Keep the 45-second READY cache only if its key also includes `runId`; otherwise remove it. On any quote exception or incomplete quote snapshot return FAILED with both candidate lists empty and data gap `盘中全市场行情不可用`.

For INITIALIZING, UPDATING and FAILED baseline access, map progress directly and do not call `fetchAshareQuoteSnapshot`.

- [ ] **Step 5: Update TypeScript contract and page rendering**

Use:

```ts
export type FirstBoardAlertStatus = 'INITIALIZING' | 'UPDATING' | 'READY' | 'FAILED'
```

Add to `FirstBoardAlertSnapshot`:

```ts
phase: string
progressCompleted: number
progressTotal: number
progressFailed: number
baselinePublishedAt: string | null
nextRetryAt: string | null
```

Poll every 60 seconds when status is INITIALIZING, UPDATING or FAILED. Render the progress line only when `progressTotal > 0`:

```tsx
<span className="tabular text-xs opacity-75">
  {snapshot.progressCompleted} / {snapshot.progressTotal}
  {snapshot.progressFailed > 0 ? ` · 失败 ${snapshot.progressFailed}` : ''}
</span>
```

Only `status === 'READY'` renders `CoverageStrip` and candidate tables. Preserve both board switches and all technical columns.

- [ ] **Step 6: Update page status labels**

Use these labels:

```ts
const statusCopy = {
  INITIALIZING: '首次候选池准备中',
  UPDATING: '候选池数据更新中',
  READY: '盘中扫描已就绪',
  FAILED: '候选池更新失败，推荐已暂停'
} as const
```

Do not add per-stock reason text. Page notifications continue to run only after a READY response.

- [ ] **Step 7: Run backend and frontend focused tests**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardAlertServiceTest test
```

Expected: PASS.

Run:

```bash
npm --prefix apps/web-react test -- --run src/pages/FirstBoardAlertPage.test.tsx src/lib/firstBoardAlertNotifications.test.ts
```

Expected: PASS; FAILED and UPDATING never render candidate rows or trigger notifications.

- [ ] **Step 8: Commit page contract changes**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertStatus.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertSnapshot.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardAlertService.java apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardAlertServiceTest.java apps/web-react/src/types.ts apps/web-react/src/pages/FirstBoardAlertPage.tsx apps/web-react/src/pages/FirstBoardAlertPage.test.tsx
git commit -m "feat: show persistent first-board baseline progress"
```

---

### Task 6: Add ex-right repair and end-to-end acceptance

**Files:**
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java`
- Modify: `apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardDailyBarStore.java`
- Modify: `apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: current quote `latestPrice` and `changePercent`, previous canonical close, and history gateway.
- Produces: targeted QFQ refresh for discontinuous symbols and documented daily behavior.

- [ ] **Step 1: Add a failing QFQ discontinuity test**

Create a test where stored previous close is `10.00`, latest is `8.00`, and provider change percent is `0.00`. Assert that ingestion calls `dailyBars(symbol, baselineDate.minusDays(260), tradeDate)` for that symbol only, replaces its canonical window, and does not refresh an unaffected symbol.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=FirstBoardMarketDayIngestionServiceTest test
```

Expected: FAIL because no continuity repair exists.

- [ ] **Step 3: Add previous-close lookup to the store**

Add this signature:

```java
java.util.Map<String, java.math.BigDecimal> closingPrices(
        java.util.Collection<String> symbols,
        java.time.LocalDate tradeDate
);
```

Implement it with one named-parameter query:

```sql
SELECT symbol, close_price
FROM first_board_daily_bar
WHERE trade_date = :tradeDate AND symbol IN (:symbols);
```

- [ ] **Step 4: Detect and repair only discontinuous symbols**

Derive provider previous close as:

```java
BigDecimal impliedPreviousClose = quote.latestPrice()
        .divide(BigDecimal.ONE.add(
                quote.changePercent().divide(new BigDecimal("100"), MathContext.DECIMAL64)),
                MathContext.DECIMAL64);
```

Calculate absolute percentage difference from stored previous close. When it exceeds `0.50%`, refetch that symbol’s QFQ window and replace via `upsertBars`. Recalculate continuity once; if it still exceeds the threshold, mark the day FAILED. Do not trigger a full-market history rebuild.

- [ ] **Step 5: Run all first-board backend and frontend tests**

Run:

```bash
mvn -pl apps/api -Dtest='com.aistock.research.firstboard.*' test
```

Expected: PASS.

Run:

```bash
npm --prefix apps/web-react test -- --run src/pages/FirstBoardAlertPage.test.tsx src/lib/firstBoardAlertNotifications.test.ts src/lib/firstBoardAlertPreferences.test.ts
```

Expected: PASS.

- [ ] **Step 6: Document the persistence lifecycle**

Add this operational summary to README:

```markdown
### 首板候选池数据

- 首次运行从 `market_kline_history` 导入可复用前复权日线，只联网补齐不足 60 根的股票。
- 每个交易日收盘后只增加该交易日，并为下一交易日发布不可变技术基线。
- API 重启直接读取 PostgreSQL READY 基线，不重新拉取全市场历史。
- 当前交易日增量或基线失败时，页面暂停推荐且不回退旧候选。
- 非交易日继续使用最近交易日收盘行情。
```

- [ ] **Step 7: Commit repair and documentation**

```bash
git add apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionService.java apps/api/src/main/java/com/aistock/research/firstboard/FirstBoardDailyBarStore.java apps/api/src/main/java/com/aistock/research/firstboard/PostgresFirstBoardDailyBarStore.java apps/api/src/test/java/com/aistock/research/firstboard/FirstBoardMarketDayIngestionServiceTest.java README.md
git commit -m "feat: repair first-board QFQ discontinuities"
```

---

### Task 7: Deploy once and prove incremental restart behavior

**Files/State:**
- Write: PostgreSQL `first_board_*` tables through Flyway and the running application.
- Preserve: PostgreSQL backup and existing H2 offline backup from the database migration plan.

**Interfaces:**
- Consumes: completed implementation and passing focused tests.
- Produces: running page with a persisted READY run and evidence that a restart does not rebuild all histories.

- [ ] **Step 1: Back up PostgreSQL before deployment**

Run:

```bash
mkdir -p /Users/mac/Documents/shares-test/backups/postgres/first-board-20260830
```

```bash
docker exec ai-stock-postgres pg_dump -U aistock -d aistock -Fc > /Users/mac/Documents/shares-test/backups/postgres/first-board-20260830/aistock.dump
```

```bash
shasum -a 256 /Users/mac/Documents/shares-test/backups/postgres/first-board-20260830/aistock.dump
```

Expected: non-empty dump and recorded SHA-256.

- [ ] **Step 2: Build and deploy API/Web**

Run:

```bash
docker compose build api web
```

Expected: both images build successfully.

Run:

```bash
docker compose up -d postgres api web
```

Expected: PostgreSQL healthy, then API and Web running.

- [ ] **Step 3: Observe first seed until it reaches a terminal state**

Poll:

```bash
curl -fsS 'http://127.0.0.1:19080/api/first-board-alert/snapshot?includeGrowth=false&includeStar=false'
```

Expected: INITIALIZING/UPDATING contains increasing persistent counts, then READY contains candidate arrays. FAILED is a real failure and must not be reported as success.

- [ ] **Step 4: Record persisted database evidence**

Run:

```bash
docker exec ai-stock-postgres psql -U aistock -d aistock -c "SELECT trade_date,status,expected_symbol_count,written_symbol_count,failed_symbol_count,bar_data_version FROM first_board_market_day ORDER BY trade_date DESC LIMIT 5;"
```

```bash
docker exec ai-stock-postgres psql -U aistock -d aistock -c "SELECT scan_trade_date,baseline_trade_date,rule_version,status,item_count,published_at FROM first_board_baseline_run ORDER BY started_at DESC LIMIT 5;"
```

Expected: the target market day and one current run are READY with non-zero item count.

- [ ] **Step 5: Restart only API and prove no full-market history rebuild**

Record the current READY `run_id` and `published_at` from PostgreSQL, restart only API, then query the page immediately:

```bash
docker compose restart api
```

```bash
curl -fsS 'http://127.0.0.1:19080/api/first-board-alert/snapshot?includeGrowth=false&includeStar=false'
```

Expected: response returns READY with the same published timestamp, without entering INITIALIZING or starting thousands of historical requests.

Confirm the run identity after restart:

```bash
docker exec ai-stock-postgres psql -U aistock -d aistock -c "SELECT run_id,published_at,status FROM first_board_baseline_run WHERE status='READY' ORDER BY published_at DESC LIMIT 1;"
```

Expected: `run_id` and `published_at` equal the values recorded before restart; no new full-market rebuild run was created.

- [ ] **Step 6: Verify non-trading-day and board switches in the page**

Open `/first-board-alert` in the existing system. Confirm:

- latest completed trading date is displayed on a non-trading day;
- 创业板 and 科创板 are off by default and each toggle changes scope;
- both “首板潜伏” and “昨日涨停观察” remain present;
- only technical fields appear; no per-stock trigger reason appears;
- FAILED state, if simulated through a controlled test environment, renders no candidate tables.

- [ ] **Step 7: Final runtime checks**

Run:

```bash
curl -fsS http://127.0.0.1:19080/actuator/health
```

Expected: `"status":"UP"`.

Run:

```bash
docker compose ps
```

Expected: PostgreSQL, API and Web are running/healthy. Report build evidence, database evidence and page acceptance separately.
