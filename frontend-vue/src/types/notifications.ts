/**
 * 契约 `openapi-java.yaml` 2.5.0 中站内通知相关的 schema 逐字段镜像，字段名不做本地化改名。
 * 该域自 2.3.0 起字段无变更；镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账，不靠本注释。
 */

import type { ReminderKind } from './reminders';

/** 契约 `NotificationData.title` 与 `ReminderListItemData.title` 同为 200 字上限。 */
export const NOTIFICATION_TITLE_MAX_LENGTH = 200;

export const NOTIFICATION_DESCRIPTION_MAX_LENGTH = 1000;

/** 契约 `NotificationReadRequest.ids` 的 `maxItems`：「勾选一批」保持单次请求，全部已读走 `all=true`。 */
export const NOTIFICATION_IDS_MAX = 500;

/**
 * 一条通知等于一次**已落库的触发**，不是提醒规则本身：同一条提醒可以有多条通知，
 * 提醒被完成或取消后历史通知仍然保留。`title`/`description` 是触发当时的快照文本。
 */
export interface NotificationData {
  notificationId: string;
  /** 触发来源提醒的 ID，只作跳回看板的线索；对应提醒可能已不再是 `ACTIVE`。 */
  reminderId: string;
  /** 触发当时的来源类型快照，取自 `reminder-due` 事件的 `sourceType`。 */
  kind: ReminderKind;
  title: string;
  description: string | null;
  /**
   * 计划触发时刻，逐字取自事件的 `occurrenceStartUtc`，即「提前提醒的时刻」而不是上课时刻。
   * 列表的排序键；同一条提醒的同一计划触发时刻只有一行通知。
   */
  plannedTriggerAt: string;
  /** 事件里带的该提醒下一次触发时刻，规则已耗尽时为 null；与 `plannedTriggerAt` 同口径，可直接比较。 */
  nextTriggerAt: string | null;
  /** 首次落库时刻；RocketMQ 是 at-least-once，重复投递被唯一键挡掉，因此不随重投刷新。 */
  deliveredAt: string;
  /** 首次标记已读的时刻；为 null 表示未读，重复标记不会改写它。 */
  readAt: string | null;
  createdAt: string;
}

export interface NotificationListData {
  items: NotificationData[];
  /** 下一页游标；为 null 表示已到末页。按 `plannedTriggerAt DESC, id DESC` 编码。 */
  nextCursor: string | null;
  limit: number;
}

/**
 * 契约 `NotificationReadRequest`（`additionalProperties: false`）。
 * `all` 与 `ids` 互斥：`all=true` 时 `ids` 必须缺省，`all=false` 时至少给一个，
 * 违反即 400 `COM-VALIDATION-001`。类型镜像按契约的 required/properties 原样声明，
 * 互斥由调用侧构造请求时保证；越权与不存在的 id 不逐项报错、也不返回 404。
 */
export interface NotificationReadRequest {
  all: boolean;
  ids?: string[];
}

/** `updated` 是本次**真正由空变有**的行数，因此重复提交同一批 id 会得到 0。 */
export interface NotificationReadData {
  updated: number;
  all: boolean;
}
