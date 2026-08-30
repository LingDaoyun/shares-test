package com.aistock.research.firstboard;

import java.util.List;

record FirstBoardBaselineAccess(
        FirstBoardAlertStatus status,
        FirstBoardBaselineSnapshot snapshot,
        FirstBoardIngestionProgress progress,
        List<String> dataGaps
) {
    FirstBoardBaselineAccess {
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    static FirstBoardBaselineAccess initializing(FirstBoardIngestionProgress progress) {
        return new FirstBoardBaselineAccess(
                FirstBoardAlertStatus.INITIALIZING, null, progress, List.of("首次候选池数据正在准备"));
    }

    static FirstBoardBaselineAccess updating(FirstBoardIngestionProgress progress) {
        return new FirstBoardBaselineAccess(
                FirstBoardAlertStatus.UPDATING, null, progress, List.of("候选池数据正在更新"));
    }

    static FirstBoardBaselineAccess ready(FirstBoardBaselineSnapshot snapshot) {
        return new FirstBoardBaselineAccess(FirstBoardAlertStatus.READY, snapshot, null, List.of());
    }

    static FirstBoardBaselineAccess failed(FirstBoardIngestionProgress progress) {
        String message = progress == null || progress.errorSummary() == null
                ? "候选池更新失败，推荐已暂停" : progress.errorSummary();
        return new FirstBoardBaselineAccess(FirstBoardAlertStatus.FAILED, null, progress, List.of(message));
    }
}
