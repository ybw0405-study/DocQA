package com.docqa.reminder.dto;

import java.util.List;

/**
 * 导入结果：整批提交后的权威判定。
 *
 * <p>幂等重放只还原计数摘要：{@code idempotentReplay = true} 时 {@code items} 恒为空数组。
 * 落进 Redis 幂等记录的也已经是去掉明细的形态（{@link #withoutItems()}），
 * 因为 500 个事件的明细约为摘要的百倍，把它们存下来只为了重放时再展示一遍不值得。
 */
public record ScheduleImportData(
        int itemCount,
        int createCount,
        int updateCount,
        int unchangedCount,
        int staleCount,
        boolean idempotentReplay,
        String fallbackTimezone,
        int leadMinutes,
        List<ScheduleImportItemData> items) {

    public ScheduleImportData {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 写入幂等记录的形态：保留四类计数与 itemCount，丢弃项目明细。 */
    public ScheduleImportData withoutItems() {
        return copy(false, List.of());
    }

    /** 重放响应形态：计数沿用首次结果，明细为空。 */
    public ScheduleImportData withIdempotentReplay() {
        return copy(true, List.of());
    }

    private ScheduleImportData copy(boolean replay, List<ScheduleImportItemData> newItems) {
        return new ScheduleImportData(
                itemCount,
                createCount,
                updateCount,
                unchangedCount,
                staleCount,
                replay,
                fallbackTimezone,
                leadMinutes,
                newItems);
    }
}
