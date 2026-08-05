package com.aistock.research.shortterm.chip;

public enum ChipVerificationStatus {
    VERIFIED("本地估算 · 外部数据已核验"),
    SINGLE_SOURCE("本地估算 · 未交叉验证"),
    CONFLICT("本地与外部数据冲突"),
    STALE("外部认证数据过期"),
    INSUFFICIENT("本地筹码数据不足");

    private final String label;

    ChipVerificationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
