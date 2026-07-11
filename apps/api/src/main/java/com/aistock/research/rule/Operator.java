package com.aistock.research.rule;

import java.math.BigDecimal;

public enum Operator {
    GT(">") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) > 0;
        }
    },
    GTE(">=") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) >= 0;
        }
    },
    LT("<") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) < 0;
        }
    },
    LTE("<=") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) <= 0;
        }
    },
    EQ("==") {
        @Override
        public boolean test(BigDecimal actual, BigDecimal expected) {
            return actual.compareTo(expected) == 0;
        }
    };

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public abstract boolean test(BigDecimal actual, BigDecimal expected);
}

