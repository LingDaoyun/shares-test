package com.aistock.research.shortterm;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/short-term/market-themes")
public class MarketThemeController {
    private final MarketThemeService service;

    public MarketThemeController(MarketThemeService service) {
        this.service = service;
    }

    @GetMapping
    public MarketThemeSnapshot snapshot() {
        return service.snapshot();
    }

    @GetMapping("/{code}")
    public MarketThemeDetail detail(@PathVariable String code,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.detail(code, date);
    }
}
