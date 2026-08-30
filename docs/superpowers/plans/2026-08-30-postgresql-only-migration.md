# PostgreSQL 单库迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有系统从 H2 文件库完整迁移到 PostgreSQL 16，保留全部业务数据，并从最终应用、配置和镜像中移除 H2 接入。

**Architecture:** PostgreSQL 成为唯一事实库，Flyway 成为唯一结构迁移入口。正式切换前使用被 `.gitignore` 排除的一次性 JDBC 搬运程序只读 H2 备份、批量写入全新 PostgreSQL，并通过表清单、行数、主键摘要和序列校验阻止不完整切换。

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Data JPA, Flyway, PostgreSQL 16/pgvector, Testcontainers, Docker Compose, Maven

## Global Constraints

- PostgreSQL 16 是最终唯一数据库；默认、本地和生产配置均不得回退到 H2。
- 现有 H2 中每一张真实业务表都必须迁移；任何表清单或数据校验失败都不得切换。
- 最终 API 依赖、配置、Docker 镜像和 Compose 中不得存在 H2 驱动、`jdbc:h2:` 或 H2 文件卷挂载。
- H2 物理备份在业务验收前只离线保留，不删除旧卷，不提供运行时连接入口。
- `apps/api/src/main/resources/db/migration` 是唯一业务结构来源；关闭 Spring `schema.sql` 自动初始化。
- 不引入 TimescaleDB、分库分表或第二个数据库。
- 保留工作区内现有首板代码改动；每次提交只暂存本任务明确列出的路径。
- 运行测试、构建、容器检查、备份和切换必须在用户明确选择执行计划后进行；本计划编写阶段不执行这些动作。

---

## File Map

### PostgreSQL/Flyway runtime

- Modify: `apps/api/pom.xml` — Flyway、PostgreSQL Testcontainers 和最终运行依赖。
- Create: `apps/api/src/main/resources/db/migration/V1__baseline_schema.sql` — 当前正式业务表结构的 PostgreSQL 基线。
- Modify: `apps/api/src/main/resources/application.yml` — PostgreSQL 默认数据源、Flyway 和 JPA 校验。
- Modify: `apps/api/src/main/resources/application-prod.yml` — 与默认配置一致的 PostgreSQL 覆盖项。
- Modify: `apps/api/src/main/resources/application-local.yml` — 本地同样使用 PostgreSQL。
- Delete: `apps/api/src/main/resources/schema.sql` — 删除重复结构入口。
- Delete: `infra/db/init/001_schema.sql` — 删除第二份业务结构副本。
- Modify: `docker-compose.yml` — PostgreSQL 默认启动、API 健康依赖、移除 H2 卷。
- Modify: `.env.example` — PostgreSQL 连接参数。

### Verification

- Create: `apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java` — 在 PostgreSQL 16 上验证空库和重复迁移。
- Modify: `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java` — 验证运行配置只允许 PostgreSQL。
- Delete: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeSchemaMigrationTest.java` — 删除 H2 专用迁移测试，把仍需保留的约束断言迁到 Flyway/PostgreSQL。

### One-time migration workspace

- Modify: `.gitignore` — 忽略 `.migration-work/` 和 `backups/`。
- Create temporarily: `.migration-work/h2-postgres-copy/pom.xml` — 独立一次性工具依赖，不进入 API。
- Create temporarily: `.migration-work/h2-postgres-copy/src/main/java/com/aistock/migration/H2PostgresCopy.java` — 元数据门禁、批量搬运和摘要校验。
- Delete after cutover: `.migration-work/` — 最终工作区不保留 H2 搬运程序。

### Documentation

- Modify: `README.md` — PostgreSQL 唯一运行方式、备份和恢复边界。
- Modify: `docs/runtime-config.md` — 运行配置只存 PostgreSQL。

---

### Task 1: Establish Flyway as the PostgreSQL schema source

**Files:**
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/main/resources/db/migration/V1__baseline_schema.sql`
- Create: `apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java`

**Interfaces:**
- Consumes: the current exact DDL in `apps/api/src/main/resources/schema.sql`.
- Produces: Flyway location `classpath:db/migration` and a PostgreSQL 16 schema that later data migration can target.

- [ ] **Step 1: Add the failing PostgreSQL migration test**

Create `PostgresFlywayMigrationTest.java` with this complete test:

```java
package com.aistock.research.history;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresFlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aistock")
            .withUsername("aistock")
            .withPassword("aistock");

    @Test
    void migratesAnEmptyPostgresDatabaseAndIsIdempotent() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(0);
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                     ORDER BY table_name
                     """)) {
            Set<String> tables = new TreeSet<>();
            while (rows.next()) tables.add(rows.getString(1));
            assertThat(tables).contains(
                    "flyway_schema_history",
                    "market_kline_history",
                    "runtime_config_section",
                    "short_term_signal_observation",
                    "strategy_trade_case",
                    "trend_analysis_run"
            );
        }
    }
}
```

- [ ] **Step 2: Run the test and verify the expected failure**

Run:

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected: compilation fails because Flyway and Testcontainers dependencies are absent, or the test fails because no `db/migration` resource exists.

- [ ] **Step 3: Add Flyway and Testcontainers dependencies**

Add these dependencies to `apps/api/pom.xml`; remove nothing yet in this step:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

Spring Boot 3.3.5 dependency management supplies compatible versions; do not add independent Flyway or Testcontainers version properties.

Also add this plugin inside the existing `<build><plugins>` block so the cutover can run migrations without starting the API:

```xml
<plugin>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-maven-plugin</artifactId>
    <version>${flyway.version}</version>
</plugin>
```

- [ ] **Step 4: Create the initial Flyway schema from the current authoritative runtime schema**

Run these as two separate actions:

```bash
mkdir -p apps/api/src/main/resources/db/migration
```

```bash
cp apps/api/src/main/resources/schema.sql apps/api/src/main/resources/db/migration/V1__baseline_schema.sql
```

Do not copy `infra/db/init/001_schema.sql`; it is known to differ from the schema the running API initializes.

- [ ] **Step 5: Run the migration test on PostgreSQL 16**

Run:

```bash
mvn -pl apps/api -Dtest=PostgresFlywayMigrationTest test
```

Expected: PASS; first `migrate()` applies V1, second applies zero migrations, and the asserted tables exist.

- [ ] **Step 6: Commit only the Flyway foundation**

```bash
git add apps/api/pom.xml apps/api/src/main/resources/db/migration/V1__baseline_schema.sql apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java
git commit -m "feat: add PostgreSQL Flyway baseline"
```

---

### Task 2: Make PostgreSQL the only application database

**Files:**
- Modify: `apps/api/pom.xml`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/resources/application-prod.yml`
- Modify: `apps/api/src/main/resources/application-local.yml`
- Delete: `apps/api/src/main/resources/schema.sql`
- Delete: `infra/db/init/001_schema.sql`
- Modify: `apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java`
- Delete: `apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeSchemaMigrationTest.java`

**Interfaces:**
- Consumes: Flyway V1 from Task 1.
- Produces: one PostgreSQL-only Spring Boot runtime; missing PostgreSQL causes startup failure instead of an H2 fallback.

- [ ] **Step 1: Replace the old compatibility test with PostgreSQL-only assertions**

Replace `SchemaCompatibilityTest.java` with:

```java
package com.aistock.research.history;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCompatibilityTest {

    @Test
    void allRuntimeProfilesUsePostgresAndFlywayOnly() throws Exception {
        for (String resource : new String[]{
                "/application.yml", "/application-local.yml", "/application-prod.yml"
        }) {
            try (var input = getClass().getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(configuration).contains("jdbc:postgresql:");
                assertThat(configuration).doesNotContain("jdbc:h2:", "org.h2.Driver");
            }
        }
    }

    @Test
    void applicationDisablesSchemaSqlInitialization() throws Exception {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration).contains("mode: never", "enabled: true", "ddl-auto: validate");
        }
    }
}
```

- [ ] **Step 2: Run the configuration test and verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest test
```

Expected: FAIL because default and local profiles still contain H2 and SQL initialization is still `always`.

- [ ] **Step 3: Change all Spring profiles to PostgreSQL and Flyway**

Use this datasource/migration block in `application.yml`:

```yaml
spring:
  application:
    name: ai-stock-api
  datasource:
    url: ${JDBC_DATABASE_URL:jdbc:postgresql://localhost:5432/aistock}
    driver-class-name: org.postgresql.Driver
    username: ${JDBC_DATABASE_USERNAME:aistock}
    password: ${JDBC_DATABASE_PASSWORD:aistock}
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  sql:
    init:
      mode: never
```

Keep the existing `management`, `server` and `research` sections unchanged. Make `application-local.yml` and `application-prod.yml` contain only PostgreSQL datasource overrides and `ddl-auto: validate`; neither file may mention H2.

- [ ] **Step 4: Remove H2 from the API dependency graph**

Delete this dependency from `apps/api/pom.xml`:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 5: Remove duplicated schema initialization files**

Delete exactly:

```text
apps/api/src/main/resources/schema.sql
infra/db/init/001_schema.sql
```

Do not delete `infra/db/init` if it contains unrelated files at execution time; remove the directory only when it is empty.

- [ ] **Step 6: Move the trade schema test to PostgreSQL**

Delete `TradeOutcomeSchemaMigrationTest.java`; both of its tests are tied to H2 or the deleted duplicate schema files. Keep business constraint coverage by adding this method to `PostgresFlywayMigrationTest`:

```java
@Test
void enforcesTradeRevisionAndOutcomeConstraintsOnPostgres() throws Exception {
    Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

    try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         var rows = connection.createStatement().executeQuery("""
                 SELECT indexname
                 FROM pg_indexes
                 WHERE schemaname = 'public'
                 """)) {
        Set<String> indexes = new TreeSet<>();
        while (rows.next()) indexes.add(rows.getString(1));
        assertThat(indexes).contains(
                "uk_trade_fill_revision_sequence",
                "idx_trade_outcome_feedback_lookup",
                "uk_trade_outcome_scope"
        );
    }
}
```

- [ ] **Step 7: Run PostgreSQL schema tests**

Run:

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest,PostgresFlywayMigrationTest test
```

Expected: PASS with PostgreSQL 16; no test opens an H2 JDBC URL.

- [ ] **Step 8: Commit the PostgreSQL-only application runtime**

```bash
git add apps/api/pom.xml apps/api/src/main/resources/application.yml apps/api/src/main/resources/application-local.yml apps/api/src/main/resources/application-prod.yml apps/api/src/main/resources/db/migration/V1__baseline_schema.sql apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java apps/api/src/test/java/com/aistock/research/history/PostgresFlywayMigrationTest.java
git add -u apps/api/src/main/resources/schema.sql infra/db/init/001_schema.sql apps/api/src/test/java/com/aistock/research/tradefeedback/TradeOutcomeSchemaMigrationTest.java
git commit -m "refactor: make PostgreSQL the only application database"
```

---

### Task 3: Convert Docker Compose to PostgreSQL-only startup

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `.gitignore`
- Test: `docker-compose.yml`

**Interfaces:**
- Consumes: PostgreSQL-only application configuration from Task 2.
- Produces: default Compose dependency `api -> healthy postgres`, persistent `postgres-data`, and no API H2 volume.

- [ ] **Step 1: Add a failing static Compose assertion**

Add this method to `SchemaCompatibilityTest.java`:

```java
@Test
void composeUsesPostgresWithoutAnH2Volume() throws Exception {
    String compose = java.nio.file.Files.readString(
            java.nio.file.Path.of("../../docker-compose.yml"));
    assertThat(compose)
            .contains("jdbc:postgresql://postgres:5432/aistock")
            .contains("condition: service_healthy")
            .doesNotContain("jdbc:h2:", "api-data:/data", "profiles: [\"infra\"]");
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest#composeUsesPostgresWithoutAnH2Volume test
```

Expected: FAIL because Compose still defaults to H2 and PostgreSQL is profile-gated.

- [ ] **Step 3: Replace the API and PostgreSQL Compose wiring**

Make the relevant Compose sections exactly equivalent to:

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: ai-stock-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-aistock}
      POSTGRES_USER: ${POSTGRES_USER:-aistock}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-aistock}
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 20

  api:
    build:
      context: .
      dockerfile: apps/api/Dockerfile
    container_name: ai-stock-api
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      SERVER_PORT: 19080
      JDBC_DATABASE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-aistock}
      JDBC_DATABASE_USERNAME: ${POSTGRES_USER:-aistock}
      JDBC_DATABASE_PASSWORD: ${POSTGRES_PASSWORD:-aistock}
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
      MOONSHOT_API_KEY: ${MOONSHOT_API_KEY:-}
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:-}
      KIMI_API_KEY: ${KIMI_API_KEY:-}
      YAHOO_PROXY_URL: ${YAHOO_PROXY_URL:-http://host.docker.internal:7897}
    ports:
      - "127.0.0.1:${API_PORT:-19080}:19080"
```

Keep the existing `web`, `redis` and `minio` definitions. Remove `api.volumes`, remove the PostgreSQL `profiles` line, remove the bind mount of `infra/db/init`, and remove `api-data` from the top-level `volumes` declaration. Do not remove the actual old Docker volume during implementation.

- [ ] **Step 4: Add environment documentation and ignored migration paths**

Append to `.env.example`:

```dotenv
POSTGRES_DB=aistock
POSTGRES_USER=aistock
POSTGRES_PASSWORD=
POSTGRES_PORT=5432
```

Append to `.gitignore`:

```gitignore
# One-time database cutover artifacts
.migration-work/
backups/
```

- [ ] **Step 5: Validate the Compose model without starting containers**

Run:

```bash
docker compose config --quiet
```

Expected: exit code 0 and no output.

Run:

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the deployment wiring**

```bash
git add docker-compose.yml .env.example .gitignore apps/api/src/test/java/com/aistock/research/history/SchemaCompatibilityTest.java
git commit -m "ops: run the platform on PostgreSQL only"
```

---

### Task 4: Build the ignored one-time H2-to-PostgreSQL copy tool

**Files:**
- Create temporarily: `.migration-work/h2-postgres-copy/pom.xml`
- Create temporarily: `.migration-work/h2-postgres-copy/src/main/java/com/aistock/migration/H2PostgresCopy.java`
- Create temporarily: `.migration-work/h2-postgres-copy/src/test/java/com/aistock/migration/H2PostgresCopyTest.java`

**Interfaces:**
- Consumes: four arguments in order: H2 JDBC URL, PostgreSQL JDBC URL, PostgreSQL user, PostgreSQL password.
- Produces: an all-or-nothing pre-cutover target whose source/target table sets, columns, row counts and deterministic table digests match.

- [ ] **Step 1: Create the isolated Maven project**

Create `pom.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.aistock</groupId>
  <artifactId>h2-postgres-copy</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.3.232</version></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.4</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.3</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><version>1.20.3</version><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>1.20.3</version><scope>test</scope></dependency>
  </dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>
    <plugin>
      <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.0</version>
      <configuration><mainClass>com.aistock.migration.H2PostgresCopy</mainClass></configuration>
    </plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: Write a failing copy/verification test**

Create `H2PostgresCopyTest.java`:

```java
package com.aistock.migration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class H2PostgresCopyTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void copiesTextNumericTimestampAndForeignKeysAndVerifiesDigest() throws Exception {
        String h2 = "jdbc:h2:mem:copy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var source = DriverManager.getConnection(h2, "sa", "");
             var target = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            source.createStatement().execute("CREATE TABLE parent(id BIGINT PRIMARY KEY, note TEXT, amount NUMERIC(20,6))");
            source.createStatement().execute("CREATE TABLE child(id VARCHAR(20) PRIMARY KEY, parent_id BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE, CONSTRAINT fk_child_parent FOREIGN KEY(parent_id) REFERENCES parent(id))");
            source.createStatement().execute("INSERT INTO parent VALUES (7, '保留数据', 12.340000)");
            source.createStatement().execute("INSERT INTO child VALUES ('c-1', 7, TIMESTAMP WITH TIME ZONE '2026-08-30 09:30:00+08')");
            target.createStatement().execute("CREATE TABLE parent(id BIGINT PRIMARY KEY, note TEXT, amount NUMERIC(20,6))");
            target.createStatement().execute("CREATE TABLE child(id VARCHAR(20) PRIMARY KEY, parent_id BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE, CONSTRAINT fk_child_parent FOREIGN KEY(parent_id) REFERENCES parent(id))");
        }

        H2PostgresCopy.migrate(h2, "sa", "", PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());

        try (var target = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var rows = target.createStatement().executeQuery("SELECT count(*) FROM child")) {
            rows.next();
            assertEquals(1L, rows.getLong(1));
        }
    }
}
```

- [ ] **Step 3: Run the temporary test and verify it fails**

Run:

```bash
mvn -f .migration-work/h2-postgres-copy/pom.xml test
```

Expected: compilation fails because `H2PostgresCopy` does not exist.

- [ ] **Step 4: Implement the migration contract**

Create `H2PostgresCopy.java` with these exact public entry points:

```java
package com.aistock.migration;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class H2PostgresCopy {
    private static final int BATCH_SIZE = 1_000;

    private H2PostgresCopy() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("参数依次为 H2_URL H2_USER H2_PASSWORD PG_URL PG_USER PG_PASSWORD");
        }
        migrate(args[0], args[1], args[2], args[3], args[4], args[5]);
    }

    public static void migrate(
            String h2Url, String h2User, String h2Password,
            String pgUrl, String pgUser, String pgPassword
    ) throws Exception {
        try (Connection source = DriverManager.getConnection(h2Url, h2User, h2Password);
             Connection target = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            source.setReadOnly(true);
            Set<String> sourceTables = tables(source, "PUBLIC");
            Set<String> targetTables = tables(target, "public");
            targetTables.remove("flyway_schema_history");
            if (!sourceTables.equals(targetTables)) {
                throw new IllegalStateException("表清单不一致 source=" + sourceTables + " target=" + targetTables);
            }
            for (String table : sourceTables) {
                Set<String> sourceColumns = new TreeSet<>(columns(source, "PUBLIC", table));
                Set<String> targetColumns = new TreeSet<>(columns(target, "public", table));
                if (!sourceColumns.equals(targetColumns)) {
                    throw new IllegalStateException(table + " 列清单不一致 source=" + sourceColumns + " target=" + targetColumns);
                }
            }

            target.setAutoCommit(false);
            try {
                target.createStatement().execute("SET session_replication_role = replica");
                if (!sourceTables.isEmpty()) {
                    target.createStatement().execute("TRUNCATE " + sourceTables.stream()
                            .map(H2PostgresCopy::quoted).reduce((a, b) -> a + "," + b).orElseThrow()
                            + " RESTART IDENTITY CASCADE");
                }
                for (String table : sourceTables) copyTable(source, target, table);
                target.createStatement().execute("SET session_replication_role = origin");
                resetIdentitySequences(target);
                target.commit();
            } catch (Exception failure) {
                target.rollback();
                throw failure;
            }

            for (String table : sourceTables) {
                long sourceCount = count(source, table);
                long targetCount = count(target, table);
                String sourceDigest = digest(source, table, primaryKeyColumns(source, "PUBLIC", table));
                String targetDigest = digest(target, table, primaryKeyColumns(target, "public", table));
                if (sourceCount != targetCount || !sourceDigest.equals(targetDigest)) {
                    throw new IllegalStateException(table + " 校验失败 sourceCount=" + sourceCount
                            + " targetCount=" + targetCount + " sourceDigest=" + sourceDigest
                            + " targetDigest=" + targetDigest);
                }
                System.out.println(table + "\t" + sourceCount + "\t" + sourceDigest);
            }
        }
    }

    private static void copyTable(Connection source, Connection target, String table) throws Exception {
        List<String> columns = columns(source, "PUBLIC", table);
        String names = columns.stream().map(H2PostgresCopy::quoted).reduce((a, b) -> a + "," + b).orElseThrow();
        String marks = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        try (ResultSet rows = source.createStatement().executeQuery("SELECT " + names + " FROM " + quoted(table));
             PreparedStatement insert = target.prepareStatement(
                     "INSERT INTO " + quoted(table) + " (" + names + ") VALUES (" + marks + ")")) {
            int batched = 0;
            while (rows.next()) {
                for (int index = 1; index <= columns.size(); index++) setValue(insert, index, rows.getObject(index));
                insert.addBatch();
                if (++batched % BATCH_SIZE == 0) insert.executeBatch();
            }
            if (batched % BATCH_SIZE != 0) insert.executeBatch();
        }
    }

    private static void setValue(PreparedStatement statement, int index, Object value) throws Exception {
        if (value instanceof Clob clob) statement.setString(index, clob.getSubString(1, Math.toIntExact(clob.length())));
        else if (value instanceof Blob blob) statement.setBytes(index, blob.getBytes(1, Math.toIntExact(blob.length())));
        else statement.setObject(index, value);
    }

    private static Set<String> tables(Connection connection, String schema) throws Exception {
        Set<String> result = new TreeSet<>();
        try (ResultSet rows = connection.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (!name.startsWith("flyway_")) result.add(name);
            }
        }
        return result;
    }

    private static List<String> columns(Connection connection, String schema, String table) throws Exception {
        Map<Integer, String> ordered = new LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, schema, table, "%")) {
            while (rows.next()) ordered.put(rows.getInt("ORDINAL_POSITION"), rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        return ordered.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    private static List<String> primaryKeyColumns(Connection connection, String schema, String table) throws Exception {
        Map<Short, String> ordered = new LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rows.next()) ordered.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        List<String> keys = ordered.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
        return keys.isEmpty() ? columns(connection, schema, table) : keys;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (ResultSet row = connection.createStatement().executeQuery("SELECT count(*) FROM " + quoted(table))) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String digest(Connection connection, String table, List<String> order) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> selectedColumns = new ArrayList<>(columns(
                connection,
                connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2") ? "PUBLIC" : "public",
                table));
        selectedColumns.sort(Comparator.naturalOrder());
        String select = selectedColumns.stream().map(H2PostgresCopy::quoted)
                .reduce((a, b) -> a + "," + b).orElseThrow();
        String orderBy = order.stream().map(H2PostgresCopy::quoted).reduce((a, b) -> a + "," + b).orElseThrow();
        try (ResultSet rows = connection.createStatement().executeQuery(
                "SELECT " + select + " FROM " + quoted(table) + " ORDER BY " + orderBy)) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    byte[] bytes = canonical(rows.getObject(index)).getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String canonical(Object value) throws Exception {
        if (value == null) return "<NULL>";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Clob clob) return clob.getSubString(1, Math.toIntExact(clob.length()));
        if (value instanceof Blob blob) return Base64.getEncoder().encodeToString(blob.getBytes(1, Math.toIntExact(blob.length())));
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant().toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        return value.toString();
    }

    private static void resetIdentitySequences(Connection target) throws Exception {
        try (ResultSet rows = target.createStatement().executeQuery("""
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND is_identity = 'YES'
                ORDER BY table_name, ordinal_position
                """)) {
            List<String[]> identities = new ArrayList<>();
            while (rows.next()) identities.add(new String[]{rows.getString(1), rows.getString(2)});
            for (String[] identity : identities) {
                String table = identity[0];
                String column = identity[1];
                String sequence;
                try (PreparedStatement lookup = target.prepareStatement("SELECT pg_get_serial_sequence(?, ?)") ) {
                    lookup.setString(1, table);
                    lookup.setString(2, column);
                    try (ResultSet result = lookup.executeQuery()) { result.next(); sequence = result.getString(1); }
                }
                if (sequence != null) {
                    target.createStatement().execute("SELECT setval('" + sequence.replace("'", "''")
                            + "', COALESCE((SELECT max(" + quoted(column) + ") FROM " + quoted(table) + "), 1), true)");
                }
            }
        }
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
```

Before using this tool on the real backup, its table-set equality check must pass. Do not weaken that check to skip unknown source tables.

- [ ] **Step 5: Run the temporary migration test**

Run:

```bash
mvn -f .migration-work/h2-postgres-copy/pom.xml test
```

Expected: PASS and one parent plus one child row exist in PostgreSQL.

Do not commit `.migration-work`; verify `git status --short` does not list it.

---

### Task 5: Back up H2, migrate all data, and cut over

**Files/State:**
- Read: running `ai-stock-api` container and its `/data/aistock.mv.db`.
- Create ignored: `backups/h2/pg-cutover-20260830/aistock.mv.db`.
- Write: `ai-stock-postgres` volume only after the source backup exists.

**Interfaces:**
- Consumes: final code from Tasks 1–3 and temporary migrator from Task 4.
- Produces: validated PostgreSQL data and a final API process connected only to it.

- [ ] **Step 1: Record current targets with read-only commands**

Run separately and save the output in the task transcript:

```bash
docker inspect ai-stock-api --format '{{.Image}} {{range .Mounts}}{{.Name}}:{{.Destination}} {{end}}'
```

```bash
docker compose ps
```

```bash
docker exec ai-stock-api sh -lc 'ls -l /data/aistock.mv.db'
```

Expected: the API container exists, `/data/aistock.mv.db` exists, and the exact old volume name is visible. If the file is absent, stop before any state change.

- [ ] **Step 2: Stop only API writes**

Run:

```bash
docker compose stop api
```

Expected: `ai-stock-api` is stopped; PostgreSQL and web state are otherwise unchanged.

- [ ] **Step 3: Copy the physical H2 database backup**

Run:

```bash
mkdir -p /Users/mac/Documents/shares-test/backups/h2/pg-cutover-20260830
```

```bash
docker cp ai-stock-api:/data/aistock.mv.db /Users/mac/Documents/shares-test/backups/h2/pg-cutover-20260830/aistock.mv.db
```

```bash
shasum -a 256 /Users/mac/Documents/shares-test/backups/h2/pg-cutover-20260830/aistock.mv.db
```

Expected: a non-empty backup file and a recorded SHA-256. Do not continue if copying or hashing fails.

- [ ] **Step 4: Build the PostgreSQL-only API image and start PostgreSQL**

Run:

```bash
docker compose build api
```

Expected: build succeeds and the dependency tree contains PostgreSQL/Flyway but no H2.

Run:

```bash
docker compose up -d postgres
```

Expected: `ai-stock-postgres` reaches healthy state.

- [ ] **Step 5: Apply Flyway to the empty PostgreSQL database without starting API traffic**

Run the migration test against the containerized PostgreSQL through Maven:

```bash
mvn -pl apps/api -Dflyway.url=jdbc:postgresql://127.0.0.1:5432/aistock -Dflyway.user=aistock -Dflyway.password=aistock flyway:migrate
```

Expected: V1 applies successfully. Use the actual environment password instead of the example value when it differs; never print a non-example password in the transcript.

- [ ] **Step 6: Run the one-time data copy against the read-only backup**

Run:

```bash
mvn -f .migration-work/h2-postgres-copy/pom.xml exec:java -Dexec.args="jdbc:h2:file:/Users/mac/Documents/shares-test/backups/h2/pg-cutover-20260830/aistock;ACCESS_MODE_DATA=r sa '' jdbc:postgresql://127.0.0.1:5432/aistock aistock aistock"
```

Expected: one line per source table with matching row count and SHA-256 digest; process exits 0. If the table-set gate fails, add the missing explicit PostgreSQL DDL as a new Flyway migration, recreate the still-isolated PostgreSQL volume, and rerun from Step 5. Never drop a source table from the comparison.

- [ ] **Step 7: Verify critical business data directly in PostgreSQL**

Run:

```bash
docker exec ai-stock-postgres psql -U aistock -d aistock -c "SELECT 'market_kline_history' AS table_name, count(*) FROM market_kline_history UNION ALL SELECT 'runtime_config_section', count(*) FROM runtime_config_section UNION ALL SELECT 'strategy_trade_case', count(*) FROM strategy_trade_case UNION ALL SELECT 'short_term_signal_observation', count(*) FROM short_term_signal_observation;"
```

Expected: counts equal the migration report; zero is acceptable only when the corresponding H2 source table was also zero.

- [ ] **Step 8: Start the final API and verify the database identity**

Run:

```bash
docker compose up -d api web
```

Expected: PostgreSQL is healthy before API starts.

Run:

```bash
curl -fsS http://127.0.0.1:19080/actuator/health
```

Expected: JSON contains `"status":"UP"`.

Run:

```bash
docker inspect ai-stock-api --format '{{range .Config.Env}}{{println .}}{{end}}' | rg '^JDBC_DATABASE_(URL|USERNAME)='
```

Expected: URL starts with `jdbc:postgresql://postgres:5432/`; no H2 URL appears.

- [ ] **Step 9: Preserve rollback evidence without retaining H2 runtime wiring**

Keep these two items until business acceptance:

```text
/Users/mac/Documents/shares-test/backups/h2/pg-cutover-20260830/aistock.mv.db
the old Docker H2 named volume recorded in Step 1
```

Do not delete either item in this plan. Rollback, if required, uses the previously recorded image plus the offline backup; the final Compose and application remain PostgreSQL-only.

---

### Task 6: Remove the temporary migrator and document the final runtime

**Files:**
- Delete ignored: `.migration-work/h2-postgres-copy/`
- Modify: `README.md`
- Modify: `docs/runtime-config.md`

**Interfaces:**
- Consumes: accepted PostgreSQL cutover from Task 5.
- Produces: final documentation and an audit showing no runtime H2 access path.

- [ ] **Step 1: Remove the ignored one-time migration workspace**

Move `.migration-work/h2-postgres-copy` to the system Trash rather than recursively deleting it. Confirm `.migration-work` is empty. Do not remove the physical H2 backup or old named volume.

- [ ] **Step 2: Update README storage and startup instructions**

Replace H2/PostgreSQL dual-mode text with these facts:

```markdown
运行时数据库：PostgreSQL 16（唯一支持的数据源）

Docker Compose 默认启动 PostgreSQL、API 和 Web。业务表由 Flyway 管理；API 不使用 `schema.sql`，也不会回退到内存数据库。

启动：
`docker compose up -d --build`

持久化卷：
`postgres-data` 保存全部业务数据。停止容器不会删除数据；不得使用 `docker compose down -v` 进行普通重启。
```

Update `docs/runtime-config.md` to state that `runtime_config_section` resides only in PostgreSQL.

- [ ] **Step 3: Audit runtime files for H2 references**

Run:

```bash
rg -n "com\.h2database|org\.h2\.Driver|jdbc:h2:|api-data:/data|spring\.h2" apps/api/pom.xml apps/api/src/main docker-compose.yml .env.example README.md docs/runtime-config.md
```

Expected: no matches.

- [ ] **Step 4: Run the focused final verification**

Run:

```bash
mvn -pl apps/api -Dtest=SchemaCompatibilityTest,PostgresFlywayMigrationTest test
```

Expected: PASS.

Run:

```bash
docker compose config --quiet
```

Expected: exit code 0.

- [ ] **Step 5: Commit documentation only**

```bash
git add README.md docs/runtime-config.md
git commit -m "docs: document PostgreSQL-only runtime"
```

The ignored H2 backup is not committed. Report its absolute path and SHA-256 separately in the deployment handoff.
