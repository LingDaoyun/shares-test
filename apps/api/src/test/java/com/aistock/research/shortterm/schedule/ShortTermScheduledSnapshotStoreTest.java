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

        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started)).isTrue();
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started.plusSeconds(1))).isFalse();

        ShortTermScheduledSnapshot saved = store.finish(
                date, FINAL, "rules-v1", FINAL_READY, sampleReport(),
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

        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started)).isTrue();
        store.fail(date, FINAL, "rules-v1", started.plusSeconds(30), "行情源超时", List.of("行情源超时"));
        assertThat(store.find(date, FINAL, "rules-v1")).get()
                .extracting(ShortTermScheduledSnapshot::status)
                .isEqualTo(FAILED);
        assertThat(store.find(date, FINAL, "rules-v1").orElseThrow().blockedReasons())
                .containsExactly("行情源超时");

        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started.plusSeconds(60))).isTrue();

        ShortTermScheduledSnapshotEntity entity = repository.findById(date + ":FINAL:rules-v1").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(RUNNING);
        assertThat(entity.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void returnsTheMostRecentlyUpdatedSnapshotForTheRequestedDateOnly() {
        LocalDate date = LocalDate.of(2026, 7, 23);
        Instant started = Instant.parse("2026-07-23T06:48:00Z");

        assertThat(store.claim(date, PRESELECT, "rules-v1", "{}", started)).isTrue();
        store.finish(date, PRESELECT, "rules-v1", PRESELECT_READY, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "盘前候选已就绪", List.of());
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started.plusSeconds(1))).isTrue();
        store.finish(date, FINAL, "rules-v1", FINAL_READY, sampleReport(),
                started.plusSeconds(20), started.plusSeconds(20), "尾盘最终结果已就绪", List.of());

        assertThat(store.latest(date)).get().extracting(ShortTermScheduledSnapshot::stage)
                .isEqualTo(FINAL);
        assertThat(store.latest(date.minusDays(1))).isEmpty();
    }

    @Test
    void publishesOnlyOnceFromRunningAndPreservesTheFirstTerminalResult() {
        LocalDate date = LocalDate.of(2026, 7, 28);
        Instant started = Instant.parse("2026-07-28T06:48:00Z");
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started)).isTrue();

        store.finish(date, FINAL, "rules-v1", FINAL_READY, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "最终结果已就绪", List.of());

        assertThatIllegalStateException().isThrownBy(() -> store.finish(
                date, FINAL, "rules-v1", PRESELECT_READY, sampleReport(),
                started.plusSeconds(20), started.plusSeconds(20), "旧任务覆盖", List.of("不应写入")));
        assertThatIllegalStateException().isThrownBy(() -> store.fail(
                date, FINAL, "rules-v1", started.plusSeconds(30), "旧任务失败", List.of("不应写入")));

        ShortTermScheduledSnapshot persisted = store.find(date, FINAL, "rules-v1").orElseThrow();
        assertThat(persisted.status()).isEqualTo(FINAL_READY);
        assertThat(persisted.message()).isEqualTo("最终结果已就绪");
        assertThat(persisted.blockedReasons()).isEmpty();
    }

    @Test
    void rejectsNonTerminalFinishStatusesWithoutPublishing() {
        LocalDate date = LocalDate.of(2026, 7, 29);
        Instant started = Instant.parse("2026-07-29T06:48:00Z");
        assertThat(store.claim(date, FINAL, "rules-v1", "{}", started)).isTrue();

        assertThatIllegalArgumentException().isThrownBy(() -> store.finish(
                date, FINAL, "rules-v1", RUNNING, sampleReport(),
                started.plusSeconds(10), started.plusSeconds(10), "无效状态", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> store.finish(
                date, FINAL, "rules-v1", FAILED, null,
                null, started.plusSeconds(10), "无效状态", List.of()));

        assertThat(store.find(date, FINAL, "rules-v1").orElseThrow().status()).isEqualTo(RUNNING);
    }

    private ShortTermReport sampleReport() {
        return new ShortTermReport(
                "A股全市场", 1, 1, 1, 0, "测试快照", null,
                List.of("测试方法"), null, null, List.of(), List.of(), null, List.of(),
                Instant.parse("2026-07-23T06:52:30Z"));
    }
}
