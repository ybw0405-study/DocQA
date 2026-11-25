package com.docqa.reminder.schedule;

import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ScheduleImportAction;
import java.time.Instant;
import java.util.UUID;

/**
 * 单个 {@code VEVENT} 的判定结果，预览与导入共用。
 *
 * <p>{@code row} 只在 {@code CREATE} / {@code UPDATE} 时非空，即「本次要写的行」；
 * {@code UNCHANGED} / {@code STALE} 不产生任何 SQL 写入，因此没有待写行，
 * 其响应里的触发时刻直接取库内行的 {@code next_trigger_at}——那才是真正会响的时刻，
 * 而 {@code event} 的其余字段仍取文件解析结果（与契约的字段口径一致）。
 *
 * @param currentSequence 库内同 UID 行的 {@code source_sequence}，库内无此行（{@code CREATE}）时为 null
 * @param reminderId      库内提醒 ID，{@code UPDATE} / {@code UNCHANGED} / {@code STALE} 取既存行的 ID，
 *                       {@code CREATE} 取预先生成的新 ID。是否对外暴露由调用方决定：契约规定预览里
 *                       {@code CREATE} 为 null（尚未落库），导入响应里为这个新 ID。
 * @param nextTriggerAt   首个未过期 occurrence 减提前量；规则已耗尽或全被排除时为 null
 * @param followingTriggerAt 再下一次提醒时刻，供调用方肉眼确认重复规则展开正确
 */
public record ScheduleDecision(
        IcsEvent event,
        ScheduleImportAction action,
        Integer currentSequence,
        UUID reminderId,
        Instant nextTriggerAt,
        Instant followingTriggerAt,
        ReminderRow row) {
}
