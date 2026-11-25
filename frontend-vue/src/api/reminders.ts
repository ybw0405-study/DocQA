import type {
  ReminderCreateData,
  ReminderCreateRequest,
  ReminderListData,
  ReminderStatus,
  ReminderStatusUpdateData,
  ReminderStatusUpdateRequest,
  ScheduleImportData,
  SchedulePreviewData,
} from '../types/reminders';
import { get, patch, postFormWithTrace, postWithTrace, type Traced } from './http';

export interface ReminderListQuery {
  status?: ReminderStatus | null;
  cursor?: string;
  limit?: number;
}

/** 课表导入的三个表单值，与契约 `ScheduleImportRequest` 的三个 part 一一对应。 */
export interface ScheduleImportForm {
  file: File;
  fallbackTimezone: string;
  leadMinutes: number;
}

/**
 * 契约的看板就是 `GET /api/v1/reminders` 本身，没有独立的 dashboard 端点；
 * 2.3.0 补上了状态变更入口（`PATCH /api/v1/reminders/{reminderId}`），
 * 但契约上没有删除与改标题，看板因此仍是「读、建、改状态」三个动作。
 */
export function listReminders(query: ReminderListQuery): Promise<ReminderListData> {
  return get<ReminderListData>('/reminders', {
    status: query.status ?? undefined,
    cursor: query.cursor,
    limit: query.limit,
  });
}

/**
 * 创建必须拿到成功信封的 `traceId`：`ReminderCreateData` 里没有该字段，
 * 而新提醒的 `nextTriggerAt` 可能落在首页之外，列表里看不到时需要同一个 trace 与服务端对账。
 */
export function createReminder(
  request: ReminderCreateRequest,
  idempotencyKey: string,
): Promise<Traced<ReminderCreateData>> {
  return postWithTrace<ReminderCreateData>('/reminders', request, idempotencyKey);
}

/**
 * 状态机（契约 `PATCH /api/v1/reminders/{reminderId}`）。三种 409 的客户端动作相同——重读看板再决定，
 * 因此错误码本身不需要在这里分流。两个容易读错的语义：同态提交是 **200 no-op**（连点两次「暂停」应当成功，
 * 且 `version` 不被推进）；`PAUSED → ACTIVE` 是**重算而非补发**，规则已无未来 occurrence 时返回 409 且不写入，
 * 成功响应里 `status = ACTIVE` 恒带非空 `nextTriggerAt`。
 *
 * 幂等键请求头必填，指纹是 `sha256(reminderId | status | version)`：同键同指纹返回首次结果，
 * 同键改内容即 409 `REM-CONFLICT-001`，所以每次新尝试都要换新键（见 stores/reminders 的说明）。
 */
export function updateReminderStatus(
  reminderId: string,
  request: ReminderStatusUpdateRequest,
  idempotencyKey: string,
): Promise<ReminderStatusUpdateData> {
  return patch<ReminderStatusUpdateData>(
    `/reminders/${encodeURIComponent(reminderId)}`,
    request,
    idempotencyKey,
  );
}

/**
 * 三个 part 的形态就是服务端的判据，两处不能想当然：
 * - 文件包成 `text/calendar` 的 Blob。内核对 `.ics` 给出的类型不一，`text/plain` 这类会被 415
 * 直接拒掉，而契约允许的是 `text/calendar` / `application/octet-stream` / 空值；
 * 文件名只作展示，服务端不消费。
 * - 两个文本字段按字符串 append。字符串形态不会产生 Part 级 `Content-Type`，
 * 这正是契约要求的「普通表单字段」；写成 Blob 会让服务端把它们当消息体去选转换器，直接 500。
 */
export function buildScheduleImportForm(form: ScheduleImportForm): FormData {
  const data = new FormData();
  data.append('file', new Blob([form.file], { type: 'text/calendar' }), form.file.name);
  data.append('fallbackTimezone', form.fallbackTimezone);
  data.append('leadMinutes', String(form.leadMinutes));
  return data;
}

/**
 * 预览不接受调用方给定键：契约上这里「幂等键接受但不消费」，请求拦截器补的那个随机键
 * 只是统一形态，服务端不为它写幂等记录，所以反复预览不会被同键指纹误判成 409。
 */
export function previewSchedule(form: ScheduleImportForm): Promise<Traced<SchedulePreviewData>> {
  return postFormWithTrace<SchedulePreviewData>('/reminders/ics/preview', buildScheduleImportForm(form));
}

/** 导入必须复用调用方持有的键：同键同文件才是重放，换键就是再写一整批。 */
export function importSchedule(form: ScheduleImportForm, idempotencyKey: string): Promise<Traced<ScheduleImportData>> {
  return postFormWithTrace<ScheduleImportData>('/reminders/ics/import', buildScheduleImportForm(form), idempotencyKey);
}
