package com.aistock.research.firstboard;

public enum FirstBoardBoard {
    MAIN,
    GROWTH,
    STAR,
    UNSUPPORTED;

    public static FirstBoardBoard fromSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return UNSUPPORTED;
        }
        if (symbol.startsWith("300") || symbol.startsWith("301")) {
            return GROWTH;
        }
        if (symbol.startsWith("688") || symbol.startsWith("689")) {
            return STAR;
        }
        if (symbol.startsWith("000") || symbol.startsWith("001") || symbol.startsWith("002")
                || symbol.startsWith("003") || symbol.startsWith("600") || symbol.startsWith("601")
                || symbol.startsWith("603") || symbol.startsWith("605")) {
            return MAIN;
        }
        return UNSUPPORTED;
    }
}
