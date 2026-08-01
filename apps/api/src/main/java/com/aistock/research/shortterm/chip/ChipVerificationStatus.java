package com.aistock.research.shortterm.chip;

public enum ChipVerificationStatus {
    VERIFIED("筹码模型已认证"),
    SINGLE_SOURCE("单源模型"),
    CONFLICT("模型数据冲突"),
    STALE("认证数据过期"),
    INSUFFICIENT("筹码数据不足");

    private final String label;

    ChipVerificationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
