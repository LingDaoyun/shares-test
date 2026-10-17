package com.aistock.research.policy;

import java.time.LocalDate;

/** Scheduler bookkeeping is persisted separately from the public snapshot. */
record PolicyDailyState(
        PolicyDailySnapshot snapshot,
        LocalDate lastAutomaticAttemptDate,
        LocalDate lastSuccessfulDate
) { }
