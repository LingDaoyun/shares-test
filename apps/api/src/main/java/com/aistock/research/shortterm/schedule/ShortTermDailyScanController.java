package com.aistock.research.shortterm.schedule;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/short-term/daily-scan")
public class ShortTermDailyScanController {
    private final ShortTermDailyScanService service;
    public ShortTermDailyScanController(ShortTermDailyScanService service) { this.service = service; }
    @GetMapping("/latest")
    public ShortTermDailySnapshot latest() { return service.latest(); }
    @GetMapping("/result-cache")
    public ShortTermDailySnapshot resultCache() { return service.resultCache(); }
    @PostMapping("/retry")
    public ShortTermDailySnapshot retry() { return service.retryToday(); }
}
