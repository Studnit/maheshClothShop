package com.maheshclothes.inventory;

/** Manual stock changes. Write-off and count variances are "sensitive" and need the approve permission. */
public enum AdjustType {
    OPENING(false),
    ADJUST_IN(false),
    ADJUST_OUT(false),
    DAMAGE(false),
    WRITE_OFF(true),
    COUNT_GAIN(true),
    COUNT_LOSS(true);

    private final boolean sensitive;

    AdjustType(boolean sensitive) {
        this.sensitive = sensitive;
    }

    public boolean isSensitive() {
        return sensitive;
    }
}
