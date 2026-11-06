/**
 * 契约 `openapi-java.yaml` 2.5.0 中与提醒相关的 schema 逐字段镜像，字段名不做本地化改名。
 * 该域自 2.3.0 起字段无变更；镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账，不靠本注释。
 */

/**
 * 契约 `ReminderKind` 的三个来源类型。`COURSE` 只出现在读侧（看板行与站内通知），
 * 只能由课表导入产生，因此创建请求不接受它——见 {@link ReminderCreateKind}。
 */
export type ReminderKind = 'DEADLINE' | 'CUSTOM' | 'COURSE';

/** 契约 `ReminderCreateRequest` 的 `discriminator.mapping`：两个分支，`COURSE` 无法经创建接口产生。 */
export type ReminderCreateKind = 'DEADLINE' | 'CUSTOM';

export type ReminderStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';

/** 契约 `ReminderStatus` 的全集；成员名拼错会在类型检查期报错。 */
export const REMINDER_STATUSES: readonly ReminderStatus[] = ['ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED'];

/** 契约 `ReminderKind` 的全集（读侧三值）；看板徽标与通知徽标按它穷举。 */
export const REMINDER_KINDS: readonly ReminderKind[] = ['DEADLINE', 'CUSTOM', 'COURSE'];

/** 创建表单的可选类型；比 {@link REMINDER_KINDS} 少一个只读侧存在的 `COURSE`。 */
export const REMINDER_CREATE_KINDS: readonly ReminderCreateKind[] = ['DEADLINE', 'CUSTOM'];

/** 契约里提醒列表与创建响应的 `title` 上限。 */
export const REMINDER_TITLE_MAX_LENGTH = 200;

export const REMINDER_DESCRIPTION_MAX_LENGTH = 1000;

export const REMINDER_TIMEZONE_MAX_LENGTH = 64;

/** 契约 `ReminderCreateRequest.leadMinutes` 的区间：0 分钟到 7 天。 */
export const LEAD_MINUTES_MIN = 0;
export const LEAD_MINUTES_MAX = 10_080;

export const RRULE_MIN_LENGTH = 6;
export const RRULE_MAX_LENGTH = 1024;

/** 契约 `CustomReminderCreateRequest.exdates` 的 `maxItems`，手工表单不提交该字段。 */
export const EXDATES_MAX = 365;

/** 契约 `timezone` 的 pattern：`UTC` 或 `区域/城市` 形态的 IANA 名。 */
export const TIMEZONE_PATTERN = /^UTC$|^[A-Za-z]+(?:\/[A-Za-z0-9_+-]+)+$/;

/** 契约 `rrule` 的 pattern：`FREQ=<七种频率>` 开头，后跟若干 `大写键=值` 段。 */
export const RRULE_PATTERN = /^FREQ=(SECONDLY|MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[^;\r\n]+)*$/;

/** `additionalProperties: false`，因此两形态的字段集必须各自精确构造。 */
export interface DeadlineReminderCreateRequest {
  kind: 'DEADLINE';
  title: string;
  description?: string | null;
  timezone: string;
  dueAt: string;
  leadMinutes?: number;
}

export interface CustomReminderCreateRequest {
  kind: 'CUSTOM';
  title: string;
  description?: string | null;
  timezone: string;
  rrule: string;
  dtstart: string;
  exdates?: string[];
}

/** 契约的 `oneOf` + `discriminator.kind` 联合。 */
export type ReminderCreateRequest = DeadlineReminderCreateRequest | CustomReminderCreateRequest;

export interface ReminderCreateData {
  reminderId: string;
  status: ReminderStatus;
  kind: ReminderKind;
  title: string;
  rrule: string;
  dtstart: string;
  timezone: string;
  exdates: string[];
  nextTriggerAt: string | null;
  version: number;
  idempotentReplay: boolean;
  createdAt: string;
  updatedAt: string;
}

/** 一行就是一条提醒规则本身，`nextTriggerAt` 由 Java 按 RRULE/时区/EXDATE 算出。 */
export interface ReminderListItemData {
  reminderId: string;
  kind: ReminderKind;
  status: ReminderStatus;
  title: string;
  description: string | null;
  rrule: string;
  dtstart: string;
  timezone: string;
  exdates: string[];
  /**
   * 作用在 occurrence 上的提前量：`nextTriggerAt = occurrence − leadMinutes`。
   * 只有 `COURSE` 行携带真实值，`DEADLINE` 与 `CUSTOM` 恒为 0（创建时提前量已并进 dtstart），
   * 所以界面只在 `kind === 'COURSE'` 时显示「提前 N 分钟」徽章。
   */
  leadMinutes: number;
  nextTriggerAt: string | null;
  lastTriggeredAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReminderListData {
  items: ReminderListItemData[];
  nextCursor: string | null;
  limit: number;
}

/**
 * 契约 `ReminderStatusUpdateRequest`（`additionalProperties: false`）。
 * `version` 读的是「我以为它还是几」，不是目标版本：取自看板行或上一次成功响应，
 * 服务端以 `WHERE id = ? AND version = ?` 提交，不匹配即 409 `REM-CONFLICT-003`。
 */
export interface ReminderStatusUpdateRequest {
  status: ReminderStatus;
  version: number;
}

/**
 * 契约 `ReminderStatusUpdateData`。`nextTriggerAt` 四种情形各有取值：恢复是重算且恒非空、
 * 暂停保留原值、终态恒为 null、同态 no-op 回显当前值。`version` 在真正写入时是请求值 + 1，
 * no-op 与重放都不推进，客户端据此判断本次是否真的改过。
 */
export interface ReminderStatusUpdateData {
  reminderId: string;
  status: ReminderStatus;
  nextTriggerAt: string | null;
  version: number;
  updatedAt: string;
  idempotentReplay: boolean;
}

/** 契约 `ScheduleImportAction`：预览与导入共用的逐事件判定，四态互斥且穷尽。 */
export type ScheduleImportAction = 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'STALE';

/** 契约 `ScheduleImportAction` 的全集；判定徽标的展示文案按这份名单穷举。 */
export const SCHEDULE_IMPORT_ACTIONS: readonly ScheduleImportAction[] = ['CREATE', 'UPDATE', 'UNCHANGED', 'STALE'];

/**
 * 契约 `ScheduleImportRequest.file` 的 `x-max-bytes`。前端在提交前按它本地拦截：
 * 一份注定 413 的两兆请求没有理由真的发出去。
 */
export const ICS_MAX_BYTES = 2_097_152;

/** 契约的 `itemCount` 上限与 `items` 的 `maxItems` 同值：一次导入最多 500 个事件。 */
export const SCHEDULE_EVENT_MAX = 500;

/** 单个 `VEVENT` 的解析与判定结果；`description` 契约未列入 required，但后端 record 恒序列化该键。 */
export interface ScheduleImportItemData {
  uid: string;
  summary: string;
  sequence: number;
  currentSequence: number | null;
  action: ScheduleImportAction;
  dtstart: string;
  timezone: string;
  rrule: string;
  exdates: string[];
  nextTriggerAt: string | null;
  followingTriggerAt: string | null;
  reminderId: string | null;
  description: string | null;
}

/** 预览的判定摘要；`CREATE` 条目的 `reminderId` 为 null，因为还没有落库。 */
export interface SchedulePreviewData {
  itemCount: number;
  createCount: number;
  updateCount: number;
  unchangedCount: number;
  staleCount: number;
  fallbackTimezone: string;
  leadMinutes: number;
  items: ScheduleImportItemData[];
}

/** 导入的权威判定；同键重放时 `items` 恒为空数组，四类计数仍是首次结果。 */
export interface ScheduleImportData {
  itemCount: number;
  createCount: number;
  updateCount: number;
  unchangedCount: number;
  staleCount: number;
  idempotentReplay: boolean;
  fallbackTimezone: string;
  leadMinutes: number;
  items: ScheduleImportItemData[];
}
