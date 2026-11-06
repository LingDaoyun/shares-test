package com.aistock.research.integration.eastmoney;

/** A per-stock daily-history gap, rather than a failed data-source connection. */
public final class IncompleteKLineHistoryException extends IllegalStateException {
    public IncompleteKLineHistoryException(String message) {
        super(message);
    }
}
