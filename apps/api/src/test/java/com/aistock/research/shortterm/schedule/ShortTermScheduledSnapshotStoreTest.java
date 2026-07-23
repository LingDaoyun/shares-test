package com.aistock.research.shortterm.schedule;

import com.aistock.research.shortterm.ShortTermReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.FINAL;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStage.PRESELECT;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FINAL_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.FAILED;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.PRESELECT_READY;
import static com.aistock.research.shortterm.schedule.ShortTermSnapshotStatus.RUNNING;

@SpringBootTest
class ShortTermScheduledSnapshotStoreTest {

    @Autowired
    private ShortTermScheduledSnapshotStore store;

    @Autowired
    private ShortTermScheduledSnapshotRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void claimsOneRunKeyAndPublishesReportAtomically() {
        LocalDate date = LocalDate.of(2026, 7, 24);
        Instant started = Instant.parse("2026-07-24T06:48:00Z");

        ShortTermSnapshotClaim claim = store.claim(date, FINAL, "rules-v1", "{}", started).orElseThrow();
        assertThat(claim.attemptCount()).isEqualTo(1);
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started.plusSeconds(1))).isEmpty();

        ShortTermScheduledSnapshot saved = store.finish(
                claim, FINAL_READY, sampleReport(),
                Instant.parse("2026-07-23T06:52:30Z"),
                Instant.parse("2026-07-23T06:53:00Z"),
                "尾盘最终结果已就绪", List.of());

        assertThat(saved.report()).isEqualTo(sampleReport());
        assertThat(saved.blockedReasons()).isEmpty();
        assertThat(store.latest(date)).get().extracting(ShortTermScheduledSnapshot::status)
                .isEqualTo(FINAL_READY);
        assertThat(store.latest(date.minusDays(1))).isEmpty();
    }

    @Test
    void reclaimsOneFailedRunWithAnIncrementedAttemptCount() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        Instant started = Instant.parse("2026-07-25T06:48:00Z");

        ShortTermSnapshotClaim firstClaim = store.claim(date, FINAL, "rules-v1", "{}", started).orElseThrow();
        store.fail(firstClaim, started.plusSeconds(30), "行情源超时", List.of("行情源超时"));
        assertThat(store.find(date, FINAL, "rules-v1")).get()
                .extracting(ShortTermScheduledSnapshot::status)
                .isEqualTo(FAILED);
        assertThat(store.find(date, FINAL, "rules-v1").orElseThrow().blockedReasons())
                .containsExactly("行情源超时");

        Instant retryStarted = started.plusSeconds(60);
        ShortTermSnapshotClaim retryClaim = store.claim(
                date, FINAL, "rules-v1", "{}", retryStarted).orElseThrow();
        assertThat(retryClaim.attemptCount()).isEqualTo(2);

        ShortTermScheduledSnapshotEntity entity = repository.findById(date + ":FINAL:rules-v1").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RUNNING);
        assertThat(entity.getAttemptCount()).isEqualTo(2);
        assertThat(entity.getStartedAt()).isEqualTo(retryStarted);
        assertThat(entity.getUpdatedAt()).isEqualTo(retryStarted);
        assertThat(entity.getCompletedAt()).isNull();
        assertThat(entity.getReportJson()).isNull();
        assertThat(entity.getDataCutoffAt()).isNull();
        assertThat(entity.getBlockedReasonsJson()).isNull();
        assertThat(entity.getMessage()).isEqualTo("正在执行");
    }

    @Test
    void returnsTheMostRecentlyUpdatedSnapshotForTheRequestedDateOnly() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant started = Instant.parse("2026-07-23T06:48:00Z");

        ShortTermSnapshotClaim preselectClaim = store.claim(
                date, PRESELECT, "rules-v1", "{}", started).orElseThrow();
        store.finish(preselectClaim, PRESELECT_READY, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "盘前候选已就绪", List.of());
        ShortTermSnapshotClaim finalClaim = store.claim(
                date, FINAL, "rules-v1", "{}", started.plusSeconds(1)).orElseThrow();
        store.finish(finalClaim, FINAL_READY, sampleReport(),
                started.plusSeconds(20), started.plusSeconds(20), "尾盘最终结果已就绪", List.of());

        assertThat(store.latest(date)).get().extracting(ShortTermScheduledSnapshot::stage)
                .isEqualTo(FINAL);
        assertThat(store.latest(date.minusDays(1))).isEmpty();
    }

    @Test
    void publishesOnlyOnceFromRunningAndPreservesTheFirstTerminalResult() {
        LocalDate date = LocalDate.of(2026, 7, 28);
        Instant started = Instant.parse("2026-07-28T06:48:00Z");
        ShortTermSnapshotClaim claim = store.claim(date, FINAL, "rules-v1", "{}", started).orElseThrow();

        store.finish(claim, FINAL_READY, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "最终结果已就绪", List.of());

        assertThatIllegalStateException().isThrownBy(() -> store.finish(
                claim, PRESELECT_READY, sampleReport(),
                started.plusSeconds(20), started.plusSeconds(20), "旧任务覆盖", List.of("不应写入")));
        assertThatIllegalStateException().isThrownBy(() -> store.fail(
                claim, started.plusSeconds(30), "旧任务失败", List.of("不应写入")));

        ShortTermScheduledSnapshot persisted = store.find(date, FINAL, "rules-v1").orElseThrow();
        assertThat(persisted.status()).isEqualTo(FINAL_READY);
        assertThat(persisted.message()).isEqualTo("最终结果已就绪");
        assertThat(persisted.blockedReasons()).isEmpty();
    }

    @Test
    void rejectsNonTerminalFinishStatusesWithoutPublishing() {
        LocalDate date = LocalDate.of(2026, 7, 29);
        Instant started = Instant.parse("2026-07-29T06:48:00Z");
        ShortTermSnapshotClaim claim = store.claim(date, FINAL, "rules-v1", "{}", started).orElseThrow();

        assertThatIllegalArgumentException().isThrownBy(() -> store.finish(
                claim, RUNNING, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "无效状态", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.finish(
                claim, FAILED, null,
                null, started.plusSeconds(10), "无效状态", List.of()));

        assertThat(store.find(date, FINAL, "rules-v1").orElseThrow().status()).isEqualTo(RUNNING);
    }

    @Test
    void rejectsDelayedAttemptAfterFailedRunIsReclaimed() {
        LocalDate date = LocalDate.of(2026, 7, 30);
        Instant started = Instant.parse("2026-07-30T06:48:00Z");
        ShortTermSnapshotClaim attemptA = store.claim(
                date, FINAL, "rules-v1", "{}", started).orElseThrow();
        store.fail(attemptA, started.plusSeconds(10), "首次执行失败", List.of("行情源超时"));

        ShortTermSnapshotClaim attemptB = store.claim(
                date, FINAL, "rules-v1", "{}", started.plusSeconds(20)).orElseThrow();
        assertThat(attemptB.attemptCount()).isEqualTo(2);

        assertThatIllegalStateException().isThrownBy(() -> store.finish(
                attemptA, FINAL_READY, sampleReport(), started.plusSeconds(30),
                started.plusSeconds(30), "迟到的旧结果", List.of()));
        assertThatIllegalStateException().isThrownBy(() -> store.fail(
                attemptA, started.plusSeconds(31), "迟到的旧失败", List.of("不应写入")));

        ShortTermScheduledSnapshot published = store.finish(
                attemptB, FINAL_READY, sampleReport(), started.plusSeconds(40),
                started.plusSeconds(40), "重试结果已就绪", List.of());
        assertThat(published.status()).isEqualTo(FINAL_READY);
        assertThat(published.message()).isEqualTo("重试结果已就绪");
    }

    private ShortTermReport sampleReport() {
        return new ShortTermReport(
                "A股全市场", 1, 1, 1, 0, "测试快照", null,
                List.of("测试方法"), null, null, List.of(), List.of(), null, List.of(),
                Instant.parse("2026-07-23T06:52:30Z"));
    }
}
