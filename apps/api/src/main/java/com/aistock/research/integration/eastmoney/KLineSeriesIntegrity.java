package com.aistock.research.integration.eastmoney;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class KLineSeriesIntegrity {

    private static final long MAX_BOUNDARY_GAP_DAYS = 10;

    private KLineSeriesIntegrity() {
    }

    static Result assess(
            List<EastMoneyKLine> rows,
            int responseRows,
            int parsedRows,
            int duplicateRows,
            int requestedSlices,
            int successfulSlices,
            LocalDate begin,
            LocalDate end
    ) {
        List<EastMoneyKLine> safeRows = rows == null ? List.of() : rows;
        String metrics = "解析 " + parsedRows + "/" + responseRows + " 行，分片 "
                + successfulSlices + "/" + requestedSlices;
        if (begin == null || end == null || begin.isAfter(end)) {
            return new Result(false, metrics + "；请求日期范围无效");
        }
        if (requestedSlices <= 0 || successfulSlices != requestedSlices) {
            return new Result(false, metrics + "；存在失败或空分片");
        }
        if (responseRows <= 0 || parsedRows != responseRows) {
            return new Result(false, metrics + "；存在空响应或解析丢行");
        }
        if (duplicateRows > 0) {
            return new Result(false, metrics + "；存在 " + duplicateRows + " 个重复交易日");
        }
        if (safeRows.isEmpty()) {
            return new Result(false, metrics + "；没有可用交易日");
        }

        Set<LocalDate> dates = new HashSet<>();
        for (EastMoneyKLine row : safeRows) {
            if (row == null || row.tradeDate() == null || row.tradeDate().isBefore(begin) || row.tradeDate().isAfter(end)) {
                return new Result(false, metrics + "；存在无效或越界交易日");
            }
            if (!positive(row.open()) || !positive(row.close()) || !positive(row.high()) || !positive(row.low())) {
                return new Result(false, metrics + "；存在无效 OHLC 行");
            }
            if (row.high().compareTo(row.open().max(row.close())) < 0
                    || row.low().compareTo(row.open().min(row.close())) > 0
                    || row.high().compareTo(row.low()) < 0) {
                return new Result(false, metrics + "；存在结构异常 OHLC 行");
            }
            if (!dates.add(row.tradeDate())) {
                return new Result(false, metrics + "；标准化后仍存在重复交易日");
            }
        }
        LocalDate first = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate last = dates.stream().max(LocalDate::compareTo).orElseThrow();
        if (ChronoUnit.DAYS.between(begin, first) > MAX_BOUNDARY_GAP_DAYS) {
            return new Result(false, metrics + "；起始边界缺口过大：" + begin + "~" + first);
        }
        if (ChronoUnit.DAYS.between(last, end) > MAX_BOUNDARY_GAP_DAYS) {
            return new Result(false, metrics + "；结束边界缺口过大：" + last + "~" + end);
        }
        return new Result(true, metrics + "；日期覆盖 " + first + "~" + last);
    }

    private static boolean positive(java.math.BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    record Result(boolean complete, String detail) {
    }
}
