package com.aistock.research.shortterm.schedule;

import com.aistock.research.tradefeedback.RecommendationAttestationService;
import com.aistock.research.trading.TradingClockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/short-term/scheduled-snapshots")
public class ShortTermScheduledSnapshotController {

    private final TradingClockService tradingClock;
    private final ShortTermScheduledSnapshotStore store;
    private final ShortTermAutomationSettings settings;
    private final RecommendationAttestationService attestationService;

    public ShortTermScheduledSnapshotController(
            TradingClockService tradingClock,
            ShortTermScheduledSnapshotStore store,
            ShortTermAutomationSettings settings,
            RecommendationAttestationService attestationService
    ) {
        this.tradingClock = tradingClock;
        this.store = store;
        this.settings = settings;
        this.attestationService = attestationService;
    }

    @GetMapping("/latest")
    public ShortTermScheduledSnapshot latest() {
        LocalDate tradeDate = tradingClock.currentMarketDate();
        return store.latest(tradeDate, ShortTermSnapshotStage.FINAL)
                .map(snapshot -> failClosedIfUncertified(snapshot, tradeDate))
                .map(this::attestExecutableFinal)
                .orElseGet(() -> ShortTermScheduledSnapshot.waiting(
                        tradeDate, "等待 " + settings.preselectCron() + " 自动预选"));
    }

    private ShortTermScheduledSnapshot failClosedIfUncertified(
            ShortTermScheduledSnapshot snapshot,
            LocalDate tradeDate
    ) {
        if (snapshot.status() != ShortTermSnapshotStatus.FINAL_READY) {
            return snapshot;
        }
        Instant deadline = tradeDate.atTime(settings.finalDeadline())
                .atZone(TradingClockService.CHINA_MARKET_ZONE)
                .toInstant();
        if (snapshot.hasCertifiedPublicationProof(deadline)) {
            return snapshot;
        }
        return store.expireUncertifiedFinal(snapshot, deadline);
    }

    private ShortTermScheduledSnapshot attestExecutableFinal(ShortTermScheduledSnapshot snapshot) {
        if (snapshot.status() != ShortTermSnapshotStatus.FINAL_READY || snapshot.report() == null) {
            return snapshot.withReport(null);
        }
        return snapshot.withReport(attestationService.attest(snapshot.report()));
    }
}
