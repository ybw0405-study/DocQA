package com.docqa.reminder.service;

import com.docqa.reminder.dto.ScheduleImportData;

/**
 * 导入结果。
 *
 * @param data    返回给调用方的判定与计数
 * @param created true 表示本次是首次执行整批判定（201，即使批内全是 UNCHANGED/STALE 而没有 SQL 写入）；
 *                false 表示同键同指纹的幂等重放（200），没有第二次判定也没有任何写入
 */
public record ImportScheduleResult(ScheduleImportData data, boolean created) {
}
