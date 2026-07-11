package com.aistock.research.decision;

import java.util.List;

public record ExitTrigger(
        String triggerCode,
        String triggerName,
        String severity,
        String condition,
        String action,
        List<String> evidenceRefs
) {
}
