package com.aistock.research.hotspot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hotspots")
public class HotspotController {
    private final HotspotService service;

    public HotspotController(HotspotService service) { this.service = service; }

    @GetMapping("/current")
    public HotspotSnapshot current() { return service.current(); }

    @PostMapping("/refresh")
    public ResponseEntity<HotspotSnapshot> refresh() { return ResponseEntity.accepted().body(service.refresh()); }
}
