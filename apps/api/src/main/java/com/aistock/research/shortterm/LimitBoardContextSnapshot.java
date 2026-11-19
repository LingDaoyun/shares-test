package com.aistock.research.shortterm;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record LimitBoardContextSnapshot(
        LocalDate tradeDate,
        String source,
        String sourceUrl,
        Map<String, Context> up,
        Map<String, Context> down
) {
    public LimitBoardContextSnapshot {
        up = Map.copyOf(up);
        down = Map.copyOf(down);
    }

    public record Context(List<String> topics, String reason) {
        public Context {
            topics = List.copyOf(topics);
        }
    }
}
