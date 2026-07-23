package com.aistock.research.shortterm.schedule;

public record ShortTermSnapshotClaim(String snapshotKey, int attemptCount) {

    public ShortTermSnapshotClaim {
        if (snapshotKey == null || snapshotKey.isBlank()) {
            throw new IllegalArgumentException("snapshotKey must not be blank");
        }
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
    }
}
