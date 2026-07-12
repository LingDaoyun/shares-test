package com.aistock.research.tradefeedback;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TradeFillProjector {

    private static final Comparator<TradeFillRevisionEntity> REVISION_ORDER = Comparator
            .comparing(TradeFillRevisionEntity::getCreatedAt)
            .thenComparing(TradeFillRevisionEntity::getRevisionId);
    private static final Comparator<TradeFillSnapshot> ACTIVE_ORDER = Comparator
            .comparing(TradeFillSnapshot::executedAt)
            .thenComparing(TradeFillSnapshot::createdAt)
            .thenComparing(TradeFillSnapshot::fillId);

    public List<TradeFillSnapshot> project(
            Collection<TradeFillEntity> originals,
            Collection<TradeFillRevisionEntity> revisions
    ) {
        Map<String, TradeFillSnapshot> active = new LinkedHashMap<>();
        for (TradeFillEntity original : originals == null ? List.<TradeFillEntity>of() : originals) {
            if (original == null) {
                throw new IllegalStateException("成交原始事实不能为空");
            }
            active.put(original.getFillId(), snapshot(original));
        }

        List<TradeFillRevisionEntity> orderedRevisions = new ArrayList<>(
                revisions == null ? List.of() : revisions);
        orderedRevisions.sort(REVISION_ORDER);
        for (TradeFillRevisionEntity revision : orderedRevisions) {
            TradeFillSnapshot current = active.get(revision.getFillId());
            if (current == null) {
                throw new IllegalStateException("成交修订引用了不存在或已作废的成交：" + revision.getFillId());
            }
            if (!current.caseId().equals(revision.getCaseId())) {
                throw new IllegalStateException("成交修订与复盘单不匹配");
            }
            if ("CORRECTION".equals(revision.getRevisionType())) {
                active.put(revision.getFillId(), new TradeFillSnapshot(
                        current.fillId(),
                        current.caseId(),
                        revision.getSide(),
                        revision.getExecutedAt(),
                        revision.getPrice(),
                        revision.getQuantity(),
                        current.createdAt(),
                        revision.getCreatedAt()));
            } else if ("VOID".equals(revision.getRevisionType())) {
                active.remove(revision.getFillId());
            } else {
                throw new IllegalStateException("无法识别的成交修订类型：" + revision.getRevisionType());
            }
        }
        return active.values().stream().sorted(ACTIVE_ORDER).toList();
    }

    private TradeFillSnapshot snapshot(TradeFillEntity fill) {
        return new TradeFillSnapshot(
                fill.getFillId(),
                fill.getCaseId(),
                fill.getSide(),
                fill.getExecutedAt(),
                fill.getPrice(),
                fill.getQuantity(),
                fill.getCreatedAt(),
                fill.getUpdatedAt());
    }
}
