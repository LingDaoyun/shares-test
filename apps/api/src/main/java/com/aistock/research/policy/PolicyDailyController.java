package com.aistock.research.policy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policy/daily")
public class PolicyDailyController {
    private final PolicyDailyService service;

    public PolicyDailyController(PolicyDailyService service) {
        this.service = service;
    }

    @GetMapping
    public PolicyDailySnapshot current() {
        return service.current();
    }

    @PostMapping("/refresh")
    public ResponseEntity<PolicyDailySnapshot> refresh() {
        return ResponseEntity.accepted().body(service.refresh());
    }
}
