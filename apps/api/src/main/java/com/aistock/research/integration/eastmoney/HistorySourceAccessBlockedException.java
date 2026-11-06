package com.aistock.research.integration.eastmoney;

/** An explicit provider access challenge, rather than a missing individual stock history. */
public final class HistorySourceAccessBlockedException extends IllegalStateException {
    public HistorySourceAccessBlockedException(String message) {
        super(message);
    }

    public HistorySourceAccessBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
