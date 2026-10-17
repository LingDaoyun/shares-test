package com.aistock.research.fiveday;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import static com.aistock.research.fiveday.FiveDayModels.*;

@RestController
@RequestMapping("/api/five-day")
public class FiveDayController {
    private final FiveDayService service;
    private final FiveDayHistoryService history;
    public FiveDayController(FiveDayService service, FiveDayHistoryService history) {
        this.service = service; this.history = history;
    }
    @GetMapping("/snapshot")
    public Snapshot snapshot(@RequestParam(defaultValue = "false") boolean includeGrowth,
                             @RequestParam(defaultValue = "false") boolean includeStar,
                             @RequestParam(defaultValue = "v1") String strategy) {
        String version = version(strategy);
        return VERSION.equals(version) ? service.snapshot(includeGrowth, includeStar)
                : service.snapshot(includeGrowth, includeStar, version);
    }
    @PostMapping("/scan")
    public Snapshot scan(@RequestParam(defaultValue = "false") boolean includeGrowth,
                         @RequestParam(defaultValue = "false") boolean includeStar,
                         @RequestParam(defaultValue = "v1") String strategy) {
        String version = version(strategy);
        return VERSION.equals(version) ? service.start(includeGrowth, includeStar)
                : service.start(includeGrowth, includeStar, version);
    }
    @GetMapping("/history") public History history(@RequestParam(defaultValue = "v1") String strategy) {
        String version = version(strategy);
        return VERSION.equals(version) ? history.history() : history.history(version);
    }
    @PostMapping("/history/refresh") public History refreshHistory(@RequestParam(defaultValue = "v1") String strategy) {
        String version = version(strategy);
        if (VERSION.equals(version)) { history.refresh(); return history.history(); }
        history.refresh(version); return history.history(version);
    }
    @GetMapping("/trade-schedule")
    public TradeSchedule tradeSchedule(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate entryDate) {
        return service.tradeSchedule(entryDate);
    }
    @PostMapping("/attestation/{symbol}")
    public Attestation attestation(@PathVariable String symbol,
                                   @RequestParam(defaultValue = "false") boolean includeGrowth,
                                   @RequestParam(defaultValue = "false") boolean includeStar,
                                   @RequestParam(defaultValue = "v1") String strategy) {
        String version = version(strategy);
        return VERSION.equals(version) ? service.attestation(symbol, includeGrowth, includeStar)
                : service.attestation(symbol, includeGrowth, includeStar, version);
    }
    @PostMapping("/execution-check/{symbol}")
    public FiveDayExecutionCheckService.ExecutionCheck executionCheck(@PathVariable String symbol,
                                   @RequestParam(defaultValue = "false") boolean includeGrowth,
                                   @RequestParam(defaultValue = "false") boolean includeStar,
                                   @RequestParam(defaultValue = "v1") String strategy) {
        return service.executionCheck(symbol, includeGrowth, includeStar, version(strategy));
    }
    private String version(String strategy) {
        return switch (strategy) {
            case "v1" -> VERSION;
            case "v2" -> VERSION_V2;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "strategy只支持v1或v2");
        };
    }
}
