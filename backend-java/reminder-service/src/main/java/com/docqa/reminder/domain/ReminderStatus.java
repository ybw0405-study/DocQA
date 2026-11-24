package com.docqa.reminder.domain;

public enum ReminderStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED;

    /**
     * 契约 2.3.0 的六条合法迁移边。终态没有出边，因此「撤销已完成」只能重建提醒。
     * 同态提交不在此判定——它走 no-op 分支，不是非法边。
     */
    public boolean canTransitionTo(ReminderStatus target) {
        if (this == COMPLETED || this == CANCELLED) {
            return false;
        }
        return this != target;
    }

    public static ReminderStatus from(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalStateException("Unknown reminder status: " + value, ex);
        }
    }
}
