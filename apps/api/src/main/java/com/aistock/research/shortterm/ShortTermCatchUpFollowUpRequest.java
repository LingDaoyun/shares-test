package com.aistock.research.shortterm;

import java.util.List;

public record ShortTermCatchUpFollowUpRequest(List<ShortTermCatchUpObservation> observations) {
    public ShortTermCatchUpFollowUpRequest {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
