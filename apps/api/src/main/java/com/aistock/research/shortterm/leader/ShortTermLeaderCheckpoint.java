package com.aistock.research.shortterm.leader;

record ShortTermLeaderCheckpoint(
        ShortTermLeaderSnapshot snapshot,
        ShortTermLeaderRisk risk
) {
}
