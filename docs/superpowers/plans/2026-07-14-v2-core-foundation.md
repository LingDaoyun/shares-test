# A 股投研平台 V2 Core Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the P0-P2 V2 foundation: strategy signal contract, point-in-time snapshots, factor engine, recommendation ledger, and a compatibility API that old pages can consume without switching all strategies at once.

**Architecture:** Add a parallel `com.aistock.research.v2` namespace and append `v2_*` tables to the existing schema. Existing recommendation pages continue to run while the V2 core produces auditable signals through adapters. The first implementation deliberately stops before real long-term and short-term ranking algorithms; those belong to the P3/P4 plans after this foundation is verified.

**Tech Stack:** Java 17, Spring Boot 3, Spring Data JPA, H2/PostgreSQL-compatible schema SQL, JUnit 5, MockMvc, React 18, TypeScript, Vite.

## Global Constraints

- Keep Java 17 + Spring Boot 3 + React + Vite.
- Do not implement auto trading or order placement.
- Do not allow AI output to directly produce buy/sell actions.
- Do not delete old recommendation, watchlist, trade-review, or history tables.
- All V2 decision records must include strategy version, data cutoff time, source quality, and replayable JSON payload.
- Missing data must reduce `dataConfidence` or produce a blocked reason; missing data must not silently become a zero score.
- PE/PB must be context fields in V2 foundation, not universal hard filters.
- This plan covers P0-P2 only. P3 long-term strategy families, P4 short-term right-side strategy, P5 validation lifecycle, P6 Agent evidence independence, and P7 page consolidation are separate implementation plans.

---

## Scope Check

The approved V2 spec covers seven phases. This plan intentionally implements only the first three foundation phases:

- P0: V2 skeleton and compatibility layer.
- P1: point-in-time data kernel.
- P2: factor engine.

That produces working, testable software on its own: a V2 signal can be constructed, persisted, queried, and shown through a stable API. Strategy-specific scoring will plug into this foundation later.

## File Structure

Create these backend packages:

```text
apps/api/src/main/java/com/aistock/research/v2/strategy
apps/api/src/main/java/com/aistock/research/v2/data
apps/api/src/main/java/com/aistock/research/v2/factor
apps/api/src/main/java/com/aistock/research/v2/decision
apps/api/src/main/java/com/aistock/research/v2/api
```

Create matching test packages:

```text
apps/api/src/test/java/com/aistock/research/v2/strategy
apps/api/src/test/java/com/aistock/research/v2/data
apps/api/src/test/java/com/aistock/research/v2/factor
apps/api/src/test/java/com/aistock/research/v2/decision
apps/api/src/test/java/com/aistock/research/v2/api
```

Modify shared files:

```text
apps/api/src/main/resources/schema.sql
apps/web-react/src/types.ts
apps/web-react/src/api/client.ts
```

Frontend work in this plan is deliberately minimal: define V2 response types and a client method. Rendering V2 in the main pages is part of the next page-integration plan.

---

### Task 1: V2 Strategy Signal Contract

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategyCode.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategyAction.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/strategy/CandidateStage.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategySignal.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategySignalFactory.java`
- Test: `apps/api/src/test/java/com/aistock/research/v2/strategy/StrategySignalFactoryTest.java`

**Interfaces:**
- Produces: `StrategySignalFactory.blocked(...)`, `StrategySignalFactory.research(...)`, and `StrategySignal` record.
- Consumes later: Task 4 `V2RecommendationLedgerService.record(StrategySignal signal)`.

- [ ] **Step 1: Write the failing test**

Create `apps/api/src/test/java/com/aistock/research/v2/strategy/StrategySignalFactoryTest.java`:

```java
package com.aistock.research.v2.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySignalFactoryTest {

    @Test
    void createsBlockedSignalWithSeparatedScoresAndReasons() {
        StrategySignal signal = StrategySignalFactory.blocked(
                StrategyCode.SHORT_RIGHT_SIDE,
                "short-right-side-v2.0.0",
                "002714",
                "牧原股份",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                StrategyAction.DATA_BLOCKED,
                List.of("QUOTE_SNAPSHOT_MISSING"),
                Map.of("quoteStage", "AFTER_HOURS_1520"));

        assertThat(signal.strategyCode()).isEqualTo(StrategyCode.SHORT_RIGHT_SIDE);
        assertThat(signal.action()).isEqualTo(StrategyAction.DATA_BLOCKED);
        assertThat(signal.candidateStage()).isEqualTo(CandidateStage.BLOCKED);
        assertThat(signal.rankScore()).isNull();
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.riskReward()).isNull();
        assertThat(signal.blockedReasons()).containsExactly("QUOTE_SNAPSHOT_MISSING");
        assertThat(signal.context()).containsEntry("quoteStage", "AFTER_HOURS_1520");
    }

    @Test
    void createsResearchSignalWithoutPretendingScoreIsProbability() {
        StrategySignal signal = StrategySignalFactory.research(
                StrategyCode.VALUE_REVERSION,
                "value-reversion-v2.0.0",
                "600036",
                "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"),
                Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("72.35"),
                new BigDecimal("84.00"),
                null,
                null,
                Map.of("valuationContext", "industry-percentile"));

        assertThat(signal.rankScore()).isEqualByComparingTo(new BigDecimal("72.35"));
        assertThat(signal.dataConfidence()).isEqualByComparingTo(new BigDecimal("84.00"));
        assertThat(signal.historicalHitRate()).isNull();
        assertThat(signal.action()).isEqualTo(StrategyAction.NEXT_WATCH);
        assertThat(signal.blockedReasons()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
```

Expected: FAIL because `com.aistock.research.v2.strategy` classes do not exist.

- [ ] **Step 3: Create strategy enums**

Create `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategyCode.java`:

```java
package com.aistock.research.v2.strategy;

public enum StrategyCode {
    VALUE_REVERSION,
    QUALITY_COMPOUNDER,
    CYCLE_REVERSAL,
    SHORT_RIGHT_SIDE,
    HOT_DIRECTION,
    ALL_MARKET
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategyAction.java`:

```java
package com.aistock.research.v2.strategy;

public enum StrategyAction {
    ADD,
    LIGHT_TRIAL,
    HOLD,
    NEXT_WATCH,
    WAIT_PULLBACK,
    WAIT,
    REDUCE,
    EXIT,
    DATA_BLOCKED,
    RISK_BLOCKED
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/strategy/CandidateStage.java`:

```java
package com.aistock.research.v2.strategy;

public enum CandidateStage {
    QUALIFIED,
    RESEARCH,
    WATCH,
    BLOCKED
}
```

- [ ] **Step 4: Create `StrategySignal` record**

Create `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategySignal.java`:

```java
package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StrategySignal(
        StrategyCode strategyCode,
        String strategyVersion,
        String symbol,
        String companyName,
        Instant decisionAt,
        Instant dataCutoffAt,
        CandidateStage candidateStage,
        StrategyAction action,
        BigDecimal positionLimit,
        String entryCondition,
        String invalidCondition,
        BigDecimal rankScore,
        BigDecimal dataConfidence,
        BigDecimal historicalHitRate,
        BigDecimal riskReward,
        List<String> evidenceSummary,
        List<String> blockedReasons,
        Map<String, String> context
) {
    public StrategySignal {
        evidenceSummary = evidenceSummary == null ? List.of() : List.copyOf(evidenceSummary);
        blockedReasons = blockedReasons == null ? List.of() : List.copyOf(blockedReasons);
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
```

- [ ] **Step 5: Create `StrategySignalFactory`**

Create `apps/api/src/main/java/com/aistock/research/v2/strategy/StrategySignalFactory.java`:

```java
package com.aistock.research.v2.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class StrategySignalFactory {

    private StrategySignalFactory() {
    }

    public static StrategySignal blocked(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            StrategyAction action,
            List<String> blockedReasons,
            Map<String, String> context
    ) {
        return new StrategySignal(
                strategyCode,
                strategyVersion,
                symbol,
                companyName,
                decisionAt,
                dataCutoffAt,
                CandidateStage.BLOCKED,
                action,
                BigDecimal.ZERO,
                "",
                "",
                null,
                new BigDecimal("0.00"),
                null,
                null,
                List.of(),
                blockedReasons,
                context);
    }

    public static StrategySignal research(
            StrategyCode strategyCode,
            String strategyVersion,
            String symbol,
            String companyName,
            Instant decisionAt,
            Instant dataCutoffAt,
            CandidateStage candidateStage,
            StrategyAction action,
            BigDecimal rankScore,
            BigDecimal dataConfidence,
            BigDecimal historicalHitRate,
            BigDecimal riskReward,
            Map<String, String> context
    ) {
        return new StrategySignal(
                strategyCode,
                strategyVersion,
                symbol,
                companyName,
                decisionAt,
                dataCutoffAt,
                candidateStage,
                action,
                null,
                "",
                "",
                rankScore,
                dataConfidence,
                historicalHitRate,
                riskReward,
                List.of(),
                List.of(),
                context);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run:

```bash
mvn -pl apps/api -Dtest=StrategySignalFactoryTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/java/com/aistock/research/v2/strategy \
        apps/api/src/test/java/com/aistock/research/v2/strategy/StrategySignalFactoryTest.java
git commit -m "feat: add v2 strategy signal contract"
```

---

### Task 2: Point-In-Time Quote Snapshot Store

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/v2/data/DataQualityStatus.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/data/QuoteStage.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotService.java`
- Test: `apps/api/src/test/java/com/aistock/research/v2/data/V2QuoteSnapshotServiceTest.java`

**Interfaces:**
- Consumes: no earlier task required.
- Produces: `V2QuoteSnapshotService.record(...)` and `V2QuoteSnapshotService.latestVisible(symbol, quoteStage, decisionAt)`.
- Consumes later: Task 3 factor tests can use quote snapshots as point-in-time inputs.

- [ ] **Step 1: Write the failing repository/service test**

Create `apps/api/src/test/java/com/aistock/research/v2/data/V2QuoteSnapshotServiceTest.java`:

```java
package com.aistock.research.v2.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2QuoteSnapshotServiceTest {

    @Autowired
    private V2QuoteSnapshotRepository repository;

    @Autowired
    private V2QuoteSnapshotService service;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsOnlySnapshotsAvailableAtDecisionTime() {
        service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("35.10"), new BigDecimal("123456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:01:00Z"),
                Instant.parse("2026-07-14T07:01:05Z"),
                "EAST_MONEY", "request-a", DataQualityStatus.VERIFIED, "{}");
        service.record("002714", "牧原股份", QuoteStage.CLOSE_1500,
                new BigDecimal("36.20"), new BigDecimal("223456789.00"),
                Instant.parse("2026-07-14T07:00:00Z"),
                Instant.parse("2026-07-14T07:10:00Z"),
                Instant.parse("2026-07-14T07:10:05Z"),
                "EAST_MONEY", "request-b", DataQualityStatus.VERIFIED, "{}");

        Optional<V2QuoteSnapshotEntity> visible = service.latestVisible(
                "002714", QuoteStage.CLOSE_1500, Instant.parse("2026-07-14T07:05:00Z"));

        assertThat(visible).isPresent();
        assertThat(visible.get().getLastPrice()).isEqualByComparingTo(new BigDecimal("35.100000"));
        assertThat(visible.get().getAvailableAt()).isEqualTo(Instant.parse("2026-07-14T07:01:00Z"));
    }

    @Test
    void keepsConflictingSourceQualityVisibleForGates() {
        V2QuoteSnapshotEntity saved = service.record("600036", "招商银行", QuoteStage.INTRADAY,
                new BigDecimal("42.00"), new BigDecimal("323456789.00"),
                Instant.parse("2026-07-14T06:30:00Z"),
                Instant.parse("2026-07-14T06:30:03Z"),
                Instant.parse("2026-07-14T06:30:04Z"),
                "TENCENT", "request-conflict", DataQualityStatus.CONFLICT, "{\"reason\":\"price-diff\"}");

        assertThat(saved.getQualityStatus()).isEqualTo(DataQualityStatus.CONFLICT);
        assertThat(saved.getRawPayloadHash()).hasSize(64);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=V2QuoteSnapshotServiceTest test
```

Expected: FAIL because V2 data classes and table do not exist.

- [ ] **Step 3: Append quote snapshot schema**

Modify `apps/api/src/main/resources/schema.sql` by appending:

```sql
CREATE TABLE IF NOT EXISTS v2_quote_snapshot (
  snapshot_id VARCHAR(64) PRIMARY KEY,
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  quote_stage VARCHAR(32) NOT NULL,
  last_price NUMERIC(20, 6),
  amount NUMERIC(30, 4),
  effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  ingested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  source VARCHAR(128) NOT NULL,
  source_version VARCHAR(128) NOT NULL,
  quality_status VARCHAR(32) NOT NULL,
  raw_payload_hash VARCHAR(64) NOT NULL,
  raw_payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_v2_quote_visible
  ON v2_quote_snapshot(symbol, quote_stage, available_at, ingested_at);

CREATE INDEX IF NOT EXISTS idx_v2_quote_quality
  ON v2_quote_snapshot(quality_status, symbol, available_at);
```

- [ ] **Step 4: Create enums**

Create `apps/api/src/main/java/com/aistock/research/v2/data/DataQualityStatus.java`:

```java
package com.aistock.research.v2.data;

public enum DataQualityStatus {
    VERIFIED,
    SINGLE_SOURCE,
    STALE,
    CONFLICT,
    MISSING
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/data/QuoteStage.java`:

```java
package com.aistock.research.v2.data;

public enum QuoteStage {
    INTRADAY,
    CLOSE_1500,
    AFTER_HOURS_1520,
    ARCHIVE_1531
}
```

- [ ] **Step 5: Create quote entity**

Create `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotEntity.java`:

```java
package com.aistock.research.v2.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_quote_snapshot")
public class V2QuoteSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_stage", nullable = false, length = 32)
    private QuoteStage quoteStage;

    @Column(name = "last_price", precision = 20, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "amount", precision = 30, scale = 4)
    private BigDecimal amount;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Column(name = "source", nullable = false, length = 128)
    private String source;

    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 32)
    private DataQualityStatus qualityStatus;

    @Column(name = "raw_payload_hash", nullable = false, length = 64)
    private String rawPayloadHash;

    @Column(name = "raw_payload_json", nullable = false, columnDefinition = "TEXT")
    private String rawPayloadJson;

    protected V2QuoteSnapshotEntity() {
    }

    public V2QuoteSnapshotEntity(String snapshotId, String symbol, String companyName, QuoteStage quoteStage,
                                 BigDecimal lastPrice, BigDecimal amount, Instant effectiveAt, Instant availableAt,
                                 Instant ingestedAt, String source, String sourceVersion,
                                 DataQualityStatus qualityStatus, String rawPayloadHash, String rawPayloadJson) {
        this.snapshotId = snapshotId;
        this.symbol = symbol;
        this.companyName = companyName;
        this.quoteStage = quoteStage;
        this.lastPrice = lastPrice;
        this.amount = amount;
        this.effectiveAt = effectiveAt;
        this.availableAt = availableAt;
        this.ingestedAt = ingestedAt;
        this.source = source;
        this.sourceVersion = sourceVersion;
        this.qualityStatus = qualityStatus;
        this.rawPayloadHash = rawPayloadHash;
        this.rawPayloadJson = rawPayloadJson;
    }

    public String getSnapshotId() { return snapshotId; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public QuoteStage getQuoteStage() { return quoteStage; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public BigDecimal getAmount() { return amount; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getIngestedAt() { return ingestedAt; }
    public String getSource() { return source; }
    public String getSourceVersion() { return sourceVersion; }
    public DataQualityStatus getQualityStatus() { return qualityStatus; }
    public String getRawPayloadHash() { return rawPayloadHash; }
    public String getRawPayloadJson() { return rawPayloadJson; }
}
```

- [ ] **Step 6: Create repository**

Create `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotRepository.java`:

```java
package com.aistock.research.v2.data;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface V2QuoteSnapshotRepository extends JpaRepository<V2QuoteSnapshotEntity, String> {

    Optional<V2QuoteSnapshotEntity> findFirstBySymbolAndQuoteStageAndAvailableAtLessThanEqualOrderByAvailableAtDescIngestedAtDesc(
            String symbol,
            QuoteStage quoteStage,
            Instant decisionAt
    );
}
```

- [ ] **Step 7: Create service with deterministic hash**

Create `apps/api/src/main/java/com/aistock/research/v2/data/V2QuoteSnapshotService.java`:

```java
package com.aistock.research.v2.data;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class V2QuoteSnapshotService {

    private final V2QuoteSnapshotRepository repository;

    public V2QuoteSnapshotService(V2QuoteSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public V2QuoteSnapshotEntity record(String symbol, String companyName, QuoteStage quoteStage,
                                        BigDecimal lastPrice, BigDecimal amount,
                                        Instant effectiveAt, Instant availableAt, Instant ingestedAt,
                                        String source, String sourceVersion,
                                        DataQualityStatus qualityStatus, String rawPayloadJson) {
        String rawHash = sha256(rawPayloadJson);
        String snapshotId = sha256(symbol + "|" + quoteStage + "|" + availableAt + "|" + source + "|" + sourceVersion + "|" + rawHash);
        return repository.save(new V2QuoteSnapshotEntity(
                snapshotId,
                symbol,
                companyName,
                quoteStage,
                lastPrice,
                amount,
                effectiveAt,
                availableAt,
                ingestedAt,
                source,
                sourceVersion,
                qualityStatus,
                rawHash,
                rawPayloadJson));
    }

    @Transactional(readOnly = true)
    public Optional<V2QuoteSnapshotEntity> latestVisible(String symbol, QuoteStage quoteStage, Instant decisionAt) {
        return repository.findFirstBySymbolAndQuoteStageAndAvailableAtLessThanEqualOrderByAvailableAtDescIngestedAtDesc(
                symbol, quoteStage, decisionAt);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run:

```bash
mvn -pl apps/api -Dtest=V2QuoteSnapshotServiceTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add apps/api/src/main/resources/schema.sql \
        apps/api/src/main/java/com/aistock/research/v2/data \
        apps/api/src/test/java/com/aistock/research/v2/data/V2QuoteSnapshotServiceTest.java
git commit -m "feat: add v2 point-in-time quote snapshots"
```

---

### Task 3: Factor Engine and Snapshot Contract

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorDirection.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorMissingPolicy.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorDefinition.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorInput.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorValue.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/FactorEngine.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/V2FactorSnapshotEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/factor/V2FactorSnapshotRepository.java`
- Test: `apps/api/src/test/java/com/aistock/research/v2/factor/FactorEngineTest.java`

**Interfaces:**
- Produces: `FactorEngine.evaluate(FactorDefinition definition, FactorInput input)`.
- Produces: `FactorValue.rawValue`, `normalizedValue`, `dataConfidenceImpact`, and `missingReason`.
- Consumes later: Task 4 stores factor summaries in recommendation payload JSON.

- [ ] **Step 1: Write failing factor tests**

Create `apps/api/src/test/java/com/aistock/research/v2/factor/FactorEngineTest.java`:

```java
package com.aistock.research.v2.factor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactorEngineTest {

    private final FactorEngine engine = new FactorEngine();

    @Test
    void missingDataReducesConfidenceInsteadOfReturningZeroScore() {
        FactorDefinition definition = new FactorDefinition(
                "TURNOVER_STABILITY",
                "换手稳定性",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "turnover_stability",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorValue value = engine.evaluate(definition, new FactorInput("002714", Map.of()));

        assertThat(value.rawValue()).isNull();
        assertThat(value.normalizedValue()).isNull();
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("-15.00"));
        assertThat(value.missingReason()).isEqualTo("MISSING_REQUIRED_FIELD:turnover_stability");
    }

    @Test
    void validatesExpectedUnitBeforeScoring() {
        FactorDefinition definition = new FactorDefinition(
                "AMOUNT_20D_MEDIAN",
                "20日成交额中位数",
                "SHORT_RIGHT_SIDE",
                "cny",
                FactorDirection.HIGHER_IS_BETTER,
                "amount_20d_median",
                FactorMissingPolicy.BLOCK,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "amount_20d_median", new FactorInput.Measure(new BigDecimal("300000000"), "ratio")));

        assertThatThrownBy(() -> engine.evaluate(definition, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIT_MISMATCH:AMOUNT_20D_MEDIAN expected cny but got ratio");
    }

    @Test
    void normalizesHigherIsBetterValuesBetweenZeroAndOneHundred() {
        FactorDefinition definition = new FactorDefinition(
                "RELATIVE_STRENGTH_20D",
                "20日相对强度",
                "SHORT_RIGHT_SIDE",
                "ratio",
                FactorDirection.HIGHER_IS_BETTER,
                "rs_20d",
                FactorMissingPolicy.REDUCE_CONFIDENCE,
                "v2.0.0");

        FactorInput input = new FactorInput("002714", Map.of(
                "rs_20d", new FactorInput.Measure(new BigDecimal("0.63"), "ratio")));

        FactorValue value = engine.evaluate(definition, input);

        assertThat(value.rawValue()).isEqualByComparingTo(new BigDecimal("0.63"));
        assertThat(value.normalizedValue()).isEqualByComparingTo(new BigDecimal("63.00"));
        assertThat(value.dataConfidenceImpact()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(value.missingReason()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=FactorEngineTest test
```

Expected: FAIL because factor classes do not exist.

- [ ] **Step 3: Append factor snapshot schema**

Modify `apps/api/src/main/resources/schema.sql` by appending:

```sql
CREATE TABLE IF NOT EXISTS v2_factor_snapshot (
  snapshot_id VARCHAR(64) PRIMARY KEY,
  strategy_code VARCHAR(64) NOT NULL,
  strategy_version VARCHAR(64) NOT NULL,
  factor_code VARCHAR(64) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  raw_value NUMERIC(24, 8),
  normalized_value NUMERIC(8, 2),
  data_confidence_impact NUMERIC(8, 2) NOT NULL,
  value_unit VARCHAR(32) NOT NULL,
  missing_reason VARCHAR(255) NOT NULL,
  available_at TIMESTAMP WITH TIME ZONE NOT NULL,
  calculated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  source_snapshot_id VARCHAR(64),
  payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_v2_factor_symbol_strategy
  ON v2_factor_snapshot(symbol, strategy_code, strategy_version, available_at);

CREATE INDEX IF NOT EXISTS idx_v2_factor_code_available
  ON v2_factor_snapshot(factor_code, available_at);
```

- [ ] **Step 4: Create factor value types**

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorDirection.java`:

```java
package com.aistock.research.v2.factor;

public enum FactorDirection {
    HIGHER_IS_BETTER,
    LOWER_IS_BETTER
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorMissingPolicy.java`:

```java
package com.aistock.research.v2.factor;

public enum FactorMissingPolicy {
    REDUCE_CONFIDENCE,
    BLOCK
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorDefinition.java`:

```java
package com.aistock.research.v2.factor;

public record FactorDefinition(
        String code,
        String name,
        String strategyScope,
        String valueUnit,
        FactorDirection direction,
        String requiredField,
        FactorMissingPolicy missingPolicy,
        String version
) {
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorInput.java`:

```java
package com.aistock.research.v2.factor;

import java.math.BigDecimal;
import java.util.Map;

public record FactorInput(String symbol, Map<String, Measure> measures) {

    public FactorInput {
        measures = measures == null ? Map.of() : Map.copyOf(measures);
    }

    public record Measure(BigDecimal value, String unit) {
    }
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorValue.java`:

```java
package com.aistock.research.v2.factor;

import java.math.BigDecimal;

public record FactorValue(
        String factorCode,
        String symbol,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal dataConfidenceImpact,
        String missingReason
) {
}
```

- [ ] **Step 5: Implement factor engine**

Create `apps/api/src/main/java/com/aistock/research/v2/factor/FactorEngine.java`:

```java
package com.aistock.research.v2.factor;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class FactorEngine {

    public FactorValue evaluate(FactorDefinition definition, FactorInput input) {
        FactorInput.Measure measure = input.measures().get(definition.requiredField());
        if (measure == null || measure.value() == null) {
            if (definition.missingPolicy() == FactorMissingPolicy.BLOCK) {
                return new FactorValue(definition.code(), input.symbol(), null, null,
                        new BigDecimal("-100.00"),
                        "MISSING_REQUIRED_FIELD:" + definition.requiredField());
            }
            return new FactorValue(definition.code(), input.symbol(), null, null,
                    new BigDecimal("-15.00"),
                    "MISSING_REQUIRED_FIELD:" + definition.requiredField());
        }
        if (!definition.valueUnit().equals(measure.unit())) {
            throw new IllegalArgumentException("UNIT_MISMATCH:" + definition.code()
                    + " expected " + definition.valueUnit() + " but got " + measure.unit());
        }
        BigDecimal normalized = normalizeToPercentileLikeScore(measure.value(), definition.direction());
        return new FactorValue(definition.code(), input.symbol(), measure.value(), normalized,
                new BigDecimal("0.00"), "");
    }

    private BigDecimal normalizeToPercentileLikeScore(BigDecimal value, FactorDirection direction) {
        BigDecimal bounded = value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal score = bounded.multiply(new BigDecimal("100"));
        if (direction == FactorDirection.LOWER_IS_BETTER) {
            score = new BigDecimal("100").subtract(score);
        }
        return score.setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 6: Create factor snapshot entity and repository**

Create `apps/api/src/main/java/com/aistock/research/v2/factor/V2FactorSnapshotEntity.java`:

```java
package com.aistock.research.v2.factor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_factor_snapshot")
public class V2FactorSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, length = 64)
    private String snapshotId;

    @Column(name = "strategy_code", nullable = false, length = 64)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false, length = 64)
    private String strategyVersion;

    @Column(name = "factor_code", nullable = false, length = 64)
    private String factorCode;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "raw_value", precision = 24, scale = 8)
    private BigDecimal rawValue;

    @Column(name = "normalized_value", precision = 8, scale = 2)
    private BigDecimal normalizedValue;

    @Column(name = "data_confidence_impact", nullable = false, precision = 8, scale = 2)
    private BigDecimal dataConfidenceImpact;

    @Column(name = "value_unit", nullable = false, length = 32)
    private String valueUnit;

    @Column(name = "missing_reason", nullable = false, length = 255)
    private String missingReason;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Column(name = "source_snapshot_id", length = 64)
    private String sourceSnapshotId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    protected V2FactorSnapshotEntity() {
    }

    public V2FactorSnapshotEntity(String snapshotId, String strategyCode, String strategyVersion, String factorCode,
                                  String symbol, BigDecimal rawValue, BigDecimal normalizedValue,
                                  BigDecimal dataConfidenceImpact, String valueUnit, String missingReason,
                                  Instant availableAt, Instant calculatedAt, String sourceSnapshotId,
                                  String payloadJson) {
        this.snapshotId = snapshotId;
        this.strategyCode = strategyCode;
        this.strategyVersion = strategyVersion;
        this.factorCode = factorCode;
        this.symbol = symbol;
        this.rawValue = rawValue;
        this.normalizedValue = normalizedValue;
        this.dataConfidenceImpact = dataConfidenceImpact;
        this.valueUnit = valueUnit;
        this.missingReason = missingReason;
        this.availableAt = availableAt;
        this.calculatedAt = calculatedAt;
        this.sourceSnapshotId = sourceSnapshotId;
        this.payloadJson = payloadJson;
    }

    public String getSnapshotId() { return snapshotId; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getFactorCode() { return factorCode; }
    public String getSymbol() { return symbol; }
    public BigDecimal getRawValue() { return rawValue; }
    public BigDecimal getNormalizedValue() { return normalizedValue; }
    public BigDecimal getDataConfidenceImpact() { return dataConfidenceImpact; }
    public String getValueUnit() { return valueUnit; }
    public String getMissingReason() { return missingReason; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public String getSourceSnapshotId() { return sourceSnapshotId; }
    public String getPayloadJson() { return payloadJson; }
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/factor/V2FactorSnapshotRepository.java`:

```java
package com.aistock.research.v2.factor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface V2FactorSnapshotRepository extends JpaRepository<V2FactorSnapshotEntity, String> {

    List<V2FactorSnapshotEntity> findBySymbolAndStrategyCodeAndStrategyVersionAndAvailableAtLessThanEqualOrderByAvailableAtDesc(
            String symbol,
            String strategyCode,
            String strategyVersion,
            Instant decisionAt
    );
}
```

- [ ] **Step 7: Run test to verify it passes**

Run:

```bash
mvn -pl apps/api -Dtest=FactorEngineTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/resources/schema.sql \
        apps/api/src/main/java/com/aistock/research/v2/factor \
        apps/api/src/test/java/com/aistock/research/v2/factor/FactorEngineTest.java
git commit -m "feat: add v2 factor engine contract"
```

---

### Task 4: V2 Recommendation Ledger

**Files:**
- Modify: `apps/api/src/main/resources/schema.sql`
- Create: `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerEntity.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerRepository.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerService.java`
- Test: `apps/api/src/test/java/com/aistock/research/v2/decision/V2RecommendationLedgerServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `StrategySignal`.
- Produces: `V2RecommendationLedgerService.record(StrategySignal signal)` and `latest(symbol)`.
- Consumes later: Task 5 API and later trade-review attestation integration.

- [ ] **Step 1: Write failing ledger service test**

Create `apps/api/src/test/java/com/aistock/research/v2/decision/V2RecommendationLedgerServiceTest.java`:

```java
package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2RecommendationLedgerServiceTest {

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @Autowired
    private V2RecommendationLedgerService service;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void recordsSignalWithReplayPayloadAndStableFingerprint() {
        StrategySignal signal = signal();

        V2RecommendationLedgerEntity first = service.record(signal);
        V2RecommendationLedgerEntity second = service.record(signal);

        assertThat(second.getLedgerId()).isEqualTo(first.getLedgerId());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.getRecommendationFingerprint()).hasSize(64);
        assertThat(first.getStrategyCode()).isEqualTo("VALUE_REVERSION");
        assertThat(first.getAction()).isEqualTo("LIGHT_TRIAL");
        assertThat(first.getPayloadJson()).contains("\"rankScore\":68.25");
        assertThat(first.getPayloadJson()).contains("\"blockedReasons\":[]");
    }

    @Test
    void latestReturnsMostRecentDecisionForSymbol() {
        service.record(signal());
        StrategySignal later = new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-15T07:20:00Z"), Instant.parse("2026-07-15T07:19:30Z"),
                CandidateStage.WATCH, StrategyAction.NEXT_WATCH, null, "", "",
                new BigDecimal("61.00"), new BigDecimal("82.00"), null, null,
                List.of("估值仍有优势"), List.of(), Map.of("reason", "price-up"));
        service.record(later);

        assertThat(service.latest("600036")).isPresent();
        assertThat(service.latest("600036").get().getAction()).isEqualTo("NEXT_WATCH");
    }

    private StrategySignal signal() {
        return new StrategySignal(
                StrategyCode.VALUE_REVERSION, "value-reversion-v2.0.0", "600036", "招商银行",
                Instant.parse("2026-07-14T07:20:00Z"), Instant.parse("2026-07-14T07:19:30Z"),
                CandidateStage.RESEARCH, StrategyAction.LIGHT_TRIAL, new BigDecimal("0.10"),
                "PB low percentile", "ROE deterioration",
                new BigDecimal("68.25"), new BigDecimal("86.00"), null, null,
                List.of("行业估值低位"), List.of(), Map.of("valuation", "pb-percentile"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test
```

Expected: FAIL because ledger table/classes do not exist.

- [ ] **Step 3: Append recommendation ledger schema**

Modify `apps/api/src/main/resources/schema.sql` by appending:

```sql
CREATE TABLE IF NOT EXISTS v2_recommendation_ledger (
  ledger_id VARCHAR(64) PRIMARY KEY,
  recommendation_fingerprint VARCHAR(64) NOT NULL UNIQUE,
  strategy_code VARCHAR(64) NOT NULL,
  strategy_version VARCHAR(64) NOT NULL,
  symbol VARCHAR(6) NOT NULL,
  company_name VARCHAR(128) NOT NULL,
  decision_at TIMESTAMP WITH TIME ZONE NOT NULL,
  data_cutoff_at TIMESTAMP WITH TIME ZONE NOT NULL,
  candidate_stage VARCHAR(32) NOT NULL,
  action VARCHAR(32) NOT NULL,
  rank_score NUMERIC(8, 2),
  data_confidence NUMERIC(8, 2),
  historical_hit_rate NUMERIC(8, 2),
  risk_reward NUMERIC(8, 2),
  payload_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_v2_ledger_symbol_time
  ON v2_recommendation_ledger(symbol, decision_at, ledger_id);

CREATE INDEX IF NOT EXISTS idx_v2_ledger_strategy_time
  ON v2_recommendation_ledger(strategy_code, strategy_version, decision_at);
```

- [ ] **Step 4: Create ledger entity and repository**

Create `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerEntity.java`:

```java
package com.aistock.research.v2.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "v2_recommendation_ledger")
public class V2RecommendationLedgerEntity {

    @Id
    @Column(name = "ledger_id", nullable = false, length = 64)
    private String ledgerId;

    @Column(name = "recommendation_fingerprint", nullable = false, unique = true, length = 64)
    private String recommendationFingerprint;

    @Column(name = "strategy_code", nullable = false, length = 64)
    private String strategyCode;

    @Column(name = "strategy_version", nullable = false, length = 64)
    private String strategyVersion;

    @Column(name = "symbol", nullable = false, length = 6)
    private String symbol;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "decision_at", nullable = false)
    private Instant decisionAt;

    @Column(name = "data_cutoff_at", nullable = false)
    private Instant dataCutoffAt;

    @Column(name = "candidate_stage", nullable = false, length = 32)
    private String candidateStage;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "rank_score", precision = 8, scale = 2)
    private BigDecimal rankScore;

    @Column(name = "data_confidence", precision = 8, scale = 2)
    private BigDecimal dataConfidence;

    @Column(name = "historical_hit_rate", precision = 8, scale = 2)
    private BigDecimal historicalHitRate;

    @Column(name = "risk_reward", precision = 8, scale = 2)
    private BigDecimal riskReward;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected V2RecommendationLedgerEntity() {
    }

    public V2RecommendationLedgerEntity(String ledgerId, String recommendationFingerprint, String strategyCode,
                                        String strategyVersion, String symbol, String companyName,
                                        Instant decisionAt, Instant dataCutoffAt, String candidateStage,
                                        String action, BigDecimal rankScore, BigDecimal dataConfidence,
                                        BigDecimal historicalHitRate, BigDecimal riskReward,
                                        String payloadJson, Instant createdAt) {
        this.ledgerId = ledgerId;
        this.recommendationFingerprint = recommendationFingerprint;
        this.strategyCode = strategyCode;
        this.strategyVersion = strategyVersion;
        this.symbol = symbol;
        this.companyName = companyName;
        this.decisionAt = decisionAt;
        this.dataCutoffAt = dataCutoffAt;
        this.candidateStage = candidateStage;
        this.action = action;
        this.rankScore = rankScore;
        this.dataConfidence = dataConfidence;
        this.historicalHitRate = historicalHitRate;
        this.riskReward = riskReward;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public String getLedgerId() { return ledgerId; }
    public String getRecommendationFingerprint() { return recommendationFingerprint; }
    public String getStrategyCode() { return strategyCode; }
    public String getStrategyVersion() { return strategyVersion; }
    public String getSymbol() { return symbol; }
    public String getCompanyName() { return companyName; }
    public Instant getDecisionAt() { return decisionAt; }
    public Instant getDataCutoffAt() { return dataCutoffAt; }
    public String getCandidateStage() { return candidateStage; }
    public String getAction() { return action; }
    public BigDecimal getRankScore() { return rankScore; }
    public BigDecimal getDataConfidence() { return dataConfidence; }
    public BigDecimal getHistoricalHitRate() { return historicalHitRate; }
    public BigDecimal getRiskReward() { return riskReward; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
```

Create `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerRepository.java`:

```java
package com.aistock.research.v2.decision;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface V2RecommendationLedgerRepository extends JpaRepository<V2RecommendationLedgerEntity, String> {

    Optional<V2RecommendationLedgerEntity> findByRecommendationFingerprint(String recommendationFingerprint);

    Optional<V2RecommendationLedgerEntity> findFirstBySymbolOrderByDecisionAtDescLedgerIdDesc(String symbol);
}
```

- [ ] **Step 5: Create ledger service**

Create `apps/api/src/main/java/com/aistock/research/v2/decision/V2RecommendationLedgerService.java`:

```java
package com.aistock.research.v2.decision;

import com.aistock.research.v2.strategy.StrategySignal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class V2RecommendationLedgerService {

    private final V2RecommendationLedgerRepository repository;
    private final ObjectMapper objectMapper;

    public V2RecommendationLedgerService(V2RecommendationLedgerRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public V2RecommendationLedgerEntity record(StrategySignal signal) {
        String payloadJson = toJson(signal);
        String fingerprint = sha256(signal.strategyCode() + "|" + signal.strategyVersion() + "|"
                + signal.symbol() + "|" + signal.decisionAt() + "|" + payloadJson);
        Optional<V2RecommendationLedgerEntity> existing = repository.findByRecommendationFingerprint(fingerprint);
        if (existing.isPresent()) {
            return existing.get();
        }
        return repository.save(new V2RecommendationLedgerEntity(
                sha256("ledger|" + fingerprint),
                fingerprint,
                signal.strategyCode().name(),
                signal.strategyVersion(),
                signal.symbol(),
                signal.companyName(),
                signal.decisionAt(),
                signal.dataCutoffAt(),
                signal.candidateStage().name(),
                signal.action().name(),
                signal.rankScore(),
                signal.dataConfidence(),
                signal.historicalHitRate(),
                signal.riskReward(),
                payloadJson,
                Instant.now()));
    }

    @Transactional(readOnly = true)
    public Optional<V2RecommendationLedgerEntity> latest(String symbol) {
        return repository.findFirstBySymbolOrderByDecisionAtDescLedgerIdDesc(symbol);
    }

    private String toJson(StrategySignal signal) {
        try {
            return objectMapper.writeValueAsString(signal);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize strategy signal", ex);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run:

```bash
mvn -pl apps/api -Dtest=V2RecommendationLedgerServiceTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/resources/schema.sql \
        apps/api/src/main/java/com/aistock/research/v2/decision \
        apps/api/src/test/java/com/aistock/research/v2/decision/V2RecommendationLedgerServiceTest.java
git commit -m "feat: add v2 recommendation ledger"
```

---

### Task 5: V2 Compatibility API

**Files:**
- Create: `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalResponse.java`
- Create: `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalController.java`
- Test: `apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `StrategySignalFactory`.
- Consumes: Task 4 `V2RecommendationLedgerService`.
- Produces: `GET /api/v2/signals/sample?symbol=...&strategyCode=...`.
- This endpoint is a compatibility probe, not a final recommendation endpoint.

- [ ] **Step 1: Write failing controller test**

Create `apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java`:

```java
package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class V2SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private V2RecommendationLedgerRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void returnsSampleSignalAndRecordsItInLedger() throws Exception {
        mockMvc.perform(get("/api/v2/signals/sample")
                        .param("symbol", "002714")
                        .param("companyName", "牧原股份")
                        .param("strategyCode", "CYCLE_REVERSAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("002714"))
                .andExpect(jsonPath("$.strategyCode").value("CYCLE_REVERSAL"))
                .andExpect(jsonPath("$.strategyVersion").value("cycle-reversal-v2.0.0"))
                .andExpect(jsonPath("$.action").value("NEXT_WATCH"))
                .andExpect(jsonPath("$.rankScore").value(50.0))
                .andExpect(jsonPath("$.dataConfidence").value(40.0))
                .andExpect(jsonPath("$.ledgerId").isNotEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl apps/api -Dtest=V2SignalControllerTest test
```

Expected: FAIL because controller does not exist.

- [ ] **Step 3: Create response record**

Create `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalResponse.java`:

```java
package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.aistock.research.v2.strategy.StrategySignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record V2SignalResponse(
        String ledgerId,
        String strategyCode,
        String strategyVersion,
        String symbol,
        String companyName,
        Instant decisionAt,
        Instant dataCutoffAt,
        String candidateStage,
        String action,
        BigDecimal positionLimit,
        String entryCondition,
        String invalidCondition,
        BigDecimal rankScore,
        BigDecimal dataConfidence,
        BigDecimal historicalHitRate,
        BigDecimal riskReward,
        List<String> evidenceSummary,
        List<String> blockedReasons,
        Map<String, String> context
) {
    public static V2SignalResponse from(StrategySignal signal, V2RecommendationLedgerEntity ledger) {
        return new V2SignalResponse(
                ledger.getLedgerId(),
                signal.strategyCode().name(),
                signal.strategyVersion(),
                signal.symbol(),
                signal.companyName(),
                signal.decisionAt(),
                signal.dataCutoffAt(),
                signal.candidateStage().name(),
                signal.action().name(),
                signal.positionLimit(),
                signal.entryCondition(),
                signal.invalidCondition(),
                signal.rankScore(),
                signal.dataConfidence(),
                signal.historicalHitRate(),
                signal.riskReward(),
                signal.evidenceSummary(),
                signal.blockedReasons(),
                signal.context());
    }
}
```

- [ ] **Step 4: Create controller**

Create `apps/api/src/main/java/com/aistock/research/v2/api/V2SignalController.java`:

```java
package com.aistock.research.v2.api;

import com.aistock.research.v2.decision.V2RecommendationLedgerEntity;
import com.aistock.research.v2.decision.V2RecommendationLedgerService;
import com.aistock.research.v2.strategy.CandidateStage;
import com.aistock.research.v2.strategy.StrategyAction;
import com.aistock.research.v2.strategy.StrategyCode;
import com.aistock.research.v2.strategy.StrategySignal;
import com.aistock.research.v2.strategy.StrategySignalFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/signals")
public class V2SignalController {

    private final V2RecommendationLedgerService ledgerService;

    public V2SignalController(V2RecommendationLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/sample")
    public V2SignalResponse sample(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "") String companyName,
            @RequestParam(defaultValue = "VALUE_REVERSION") StrategyCode strategyCode
    ) {
        Instant now = Instant.now();
        StrategySignal signal = StrategySignalFactory.research(
                strategyCode,
                versionOf(strategyCode),
                symbol,
                companyName.isBlank() ? symbol : companyName,
                now,
                now,
                CandidateStage.RESEARCH,
                StrategyAction.NEXT_WATCH,
                new BigDecimal("50.00"),
                new BigDecimal("40.00"),
                null,
                null,
                Map.of("source", "v2-compatibility-probe"));
        V2RecommendationLedgerEntity ledger = ledgerService.record(signal);
        return V2SignalResponse.from(signal, ledger);
    }

    private String versionOf(StrategyCode strategyCode) {
        return switch (strategyCode) {
            case VALUE_REVERSION -> "value-reversion-v2.0.0";
            case QUALITY_COMPOUNDER -> "quality-compounder-v2.0.0";
            case CYCLE_REVERSAL -> "cycle-reversal-v2.0.0";
            case SHORT_RIGHT_SIDE -> "short-right-side-v2.0.0";
            case HOT_DIRECTION -> "hot-direction-v2.0.0";
            case ALL_MARKET -> "all-market-v2.0.0";
        };
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
mvn -pl apps/api -Dtest=V2SignalControllerTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/aistock/research/v2/api \
        apps/api/src/test/java/com/aistock/research/v2/api/V2SignalControllerTest.java
git commit -m "feat: expose v2 compatibility signal api"
```

---

### Task 6: Frontend V2 Client Types

**Files:**
- Modify: `apps/web-react/src/types.ts`
- Modify: `apps/web-react/src/api/client.ts`
- Test: run existing frontend typecheck/build command.

**Interfaces:**
- Consumes: Task 5 `GET /api/v2/signals/sample`.
- Produces: `V2SignalResponse` TypeScript interface and `fetchV2SampleSignal(params)`.

- [ ] **Step 1: Add TypeScript response type**

Modify `apps/web-react/src/types.ts` by appending:

```ts
export interface V2SignalResponse {
  ledgerId: string
  strategyCode: string
  strategyVersion: string
  symbol: string
  companyName: string
  decisionAt: string
  dataCutoffAt: string
  candidateStage: string
  action: string
  positionLimit: number | null
  entryCondition: string
  invalidCondition: string
  rankScore: number | null
  dataConfidence: number | null
  historicalHitRate: number | null
  riskReward: number | null
  evidenceSummary: string[]
  blockedReasons: string[]
  context: Record<string, string>
}

export interface V2SampleSignalParams {
  symbol: string
  companyName?: string
  strategyCode?: string
}
```

- [ ] **Step 2: Add client method**

Modify `apps/web-react/src/api/client.ts`:

1. Add imports if `V2SignalResponse` and `V2SampleSignalParams` are not already included in the type import list.
2. Append this function near the other API helper functions:

```ts
export async function fetchV2SampleSignal(params: V2SampleSignalParams): Promise<V2SignalResponse> {
  const search = new URLSearchParams()
  search.set('symbol', params.symbol)
  if (params.companyName) search.set('companyName', params.companyName)
  if (params.strategyCode) search.set('strategyCode', params.strategyCode)
  return request<V2SignalResponse>(`/api/v2/signals/sample?${search.toString()}`)
}
```

- [ ] **Step 3: Run frontend verification**

Run:

```bash
cd apps/web-react
npm run build
```

Expected: PASS. If the project has a separate typecheck script, also run:

```bash
cd apps/web-react
npm run typecheck
```

Expected: PASS or script not found. If `typecheck` is not defined, note that `npm run build` completed TypeScript compilation.

- [ ] **Step 4: Commit**

```bash
git add apps/web-react/src/types.ts apps/web-react/src/api/client.ts
git commit -m "feat: add frontend v2 signal client"
```

---

### Task 7: Foundation Verification

**Files:**
- No new files required.

**Interfaces:**
- Verifies all prior tasks together.
- Produces evidence for moving to P3/P4 strategy implementation.

- [ ] **Step 1: Run targeted backend V2 tests**

Run:

```bash
mvn -pl apps/api -Dtest='com.aistock.research.v2.**.*Test' test
```

Expected: PASS. If Maven Surefire does not accept that package wildcard, run the explicit set:

```bash
mvn -pl apps/api \
  -Dtest=StrategySignalFactoryTest,V2QuoteSnapshotServiceTest,FactorEngineTest,V2RecommendationLedgerServiceTest,V2SignalControllerTest \
  test
```

Expected: PASS.

- [ ] **Step 2: Run backend package test with existing critical areas**

Run:

```bash
mvn -pl apps/api \
  -Dtest=StrategySignalFactoryTest,V2QuoteSnapshotServiceTest,FactorEngineTest,V2RecommendationLedgerServiceTest,V2SignalControllerTest,TradeFeedbackControllerTest \
  test
```

Expected: PASS. This protects the existing trade-review chain while adding V2 ledger tables.

- [ ] **Step 3: Run frontend build**

Run:

```bash
cd apps/web-react
npm run build
```

Expected: PASS.

- [ ] **Step 4: Run API locally and smoke-check V2 endpoint**

Run backend:

```bash
SPRING_PROFILES_ACTIVE=local mvn -pl apps/api spring-boot:run
```

In a second terminal, run:

```bash
curl 'http://127.0.0.1:19080/api/v2/signals/sample?symbol=002714&companyName=%E7%89%A7%E5%8E%9F%E8%82%A1%E4%BB%BD&strategyCode=CYCLE_REVERSAL'
```

Expected JSON contains:

```json
{
  "symbol": "002714",
  "companyName": "牧原股份",
  "strategyCode": "CYCLE_REVERSAL",
  "action": "NEXT_WATCH",
  "context": {
    "source": "v2-compatibility-probe"
  }
}
```

- [ ] **Step 5: Commit verification notes if any docs changed**

If no docs changed during verification, do not create an empty commit. If a verification note was added to a project document, commit it:

```bash
git add docs/superpowers/plans/2026-07-14-v2-core-foundation.md
git commit -m "docs: record v2 foundation verification"
```

---

## Self-Review Checklist

- Spec coverage: P0 skeleton is covered by Task 1 and Task 5. P1 point-in-time snapshot is covered by Task 2. P2 factor engine is covered by Task 3. Recommendation ledger and old-system compatibility are covered by Task 4 and Task 6.
- Out of scope by design: real `VALUE_REVERSION`, `QUALITY_COMPOUNDER`, `CYCLE_REVERSAL`, `SHORT_RIGHT_SIDE`, `SupplyAbsorptionScore`, walk-forward validation lifecycle, and Agent evidence independence are not implemented here because the approved spec splits them into P3-P6.
- Placeholder scan: this plan contains no unfinished marker words, no vague edge-case instruction, and no reference to functions that lack a defining task.
- Type consistency: `StrategySignal`, `StrategyCode`, `StrategyAction`, `CandidateStage`, `V2RecommendationLedgerService.record`, and `V2SignalResponse.from` are defined before they are consumed.
- Migration safety: all database changes append `v2_*` tables only. No old table is dropped or overwritten.
