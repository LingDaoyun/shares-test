package com.aistock.research.news;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/news")
public class FinancialNewsController {
    private final FinancialNewsService service;

    public FinancialNewsController(FinancialNewsService service) { this.service = service; }

    @GetMapping("/current")
    public FinancialNewsSnapshot current() { return service.current(); }

    @PostMapping("/refresh")
    public ResponseEntity<FinancialNewsSnapshot> refresh() { return ResponseEntity.accepted().body(service.refresh()); }

    @PostMapping("/{id}/analysis")
    public ResponseEntity<NewsAnalysis> analyze(@PathVariable String id) {
        try { return ResponseEntity.ok(service.analyze(id)); }
        catch (NoSuchElementException exception) { return ResponseEntity.notFound().build(); }
    }
}
