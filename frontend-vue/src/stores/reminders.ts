import { defineStore } from 'pinia';
import { createReminder, listReminders, updateReminderStatus } from '../api/reminders';
import { ApiError } from '../api/errors';
import { newIdempotencyKey, type Traced } from '../api/http';
import type {
  ReminderCreateData,
  ReminderCreateKind,
  ReminderCreateRequest,
  ReminderListItemData,
  ReminderStatus,
  ReminderStatusUpdateData,
} from '../types/reminders';
import {
  LEAD_MINUTES_MAX,
  LEAD_MINUTES_MIN,
  REMINDER_DESCRIPTION_MAX_LENGTH,
  REMINDER_STATUSES,
  REMINDER_TIMEZONE_MAX_LENGTH,
  REMINDER_TITLE_MAX_LENGTH,
  RRULE_MAX_LENGTH,
  RRULE_MIN_LENGTH,
  RRULE_PATTERN,
  TIMEZONE_PATTERN,
} from '../types/reminders';
import {
  clearFailureFields,
  isStaleCursor,
  makeApplyFailure,
  PAGE_LIMIT,
  rejectLocal,
  type FailureFields as BaseFailureFields,
} from './failureFields';

const applyFailure = makeApplyFailure('提醒页请求失败，请稍后重试。');

/** 表单模型：`datetime-local` 与 `number` 输入都按字符串保存，避免把非法中间态写进数字字段。 */
export interface ReminderForm {
  kind: ReminderCreateKind;
  title: string;
  description: string;
  timezone: string;
  dueAt: string;
  leadMinutes: string;
  rrule: string;
  dtstart: string;
}

/**
 * 契约 `PATCH /api/v1/reminders/{reminderId}` 的六条合法边在界面上的投影。
 * 判定与 Java `ReminderStatus.canTransitionTo` 逐字相同：终态没有出边，其余状态可到任意**不同**状态；
 * 同态提交不是非法边（服务端是 200 no-op），因此这里也不显示按钮，由界面按当前状态自行决定呈现。
 * 服务端始终是权威，本函数只用于少给一个必然 409 的按钮。
 */
export function allowedTargets(status: ReminderStatus): ReminderStatus[] {
  if (status === 'COMPLETED' || status === 'CANCELLED') {
    return [];
  }
  return REMINDER_STATUSES.filter((target) => target !== status);
}

/**
 * 一次失败的状态变更尝试：幂等键只在「同一行、同一目标、同一期望版本」的重试里复用。
 * 指纹是 `sha256(reminderId | status | version)`，三者任一变了就是不同请求体，
 * 继续用旧键会撞上 409 `REM-CONFLICT-001`，所以键与这三者绑在一起存，而不是像创建那样挂在表单上。
 */
export interface PendingUpdate {
  reminderId: string;
  status: ReminderStatus;
  version: number;
  key: string;
}

/** 创建成功后的对账信息；`nextTriggerAt` 可能落在首页之外，此时它是唯一的成功凭据。 */
export interface CreatedSummary {
  title: string;
  nextTriggerAt: string | null;
  idempotentReplay: boolean;
  traceId: string;
}

/**
 * 看板分组：服务端按 `next_trigger_at ASC, id ASC` 排好并把 NULL 置底，前端**只按 `status` 划组、组内不重排**，
 * 因此每组内部的顺序仍是服务端顺序，划组只是把「会不会响」这一判据显式化。
 */
export interface ReminderGroup {
  heading: string;
  note: string;
  items: ReminderListItemData[];
}

interface FailureFields extends BaseFailureFields {
  retryAfterSeconds: number | null;
}

export interface RemindersState extends FailureFields {
  items: ReminderListItemData[];
  nextCursor: string | null;
  listLoading: boolean;
  listLoaded: boolean;
  /** 列表取数代号：过滤切换与写后刷新递增它，在途翻页的过期响应一律丢弃。 */
  listSeq: number;
  /** null 表示「全部」，此时根本不发送 `status` 参数（方案 A）。 */
  statusFilter: ReminderStatus | null;

  form: ReminderForm;
  creating: boolean;
  /** 本轮创建的幂等键：失败时保留供同键重试复用，成功或改表单任何字段后清空。 */
  pendingKey: string | null;
  createdSummary: CreatedSummary | null;

  /** 状态变更串行锁：同一时刻只允许一个在途 PATCH，行按钮的禁用态由它决定。 */
  updatingId: string | null;
  /** 最近一次失败的 PATCH，只有「同一行、同一目标、同一期望版本」的重试才复用它的键。 */
  pendingUpdate: PendingUpdate | null;
}

/**
 * PATCH 的响应只携带状态机相关的四个字段，看板行的其余投影（标题、规则、时区、排除日）都不受它影响，
 * 所以是「按共有字段回填这一行」而不是整行替换。`groups` 会随 `nextTriggerAt` 的新值自动换组：
 * 完成与取消置空后落到末尾组，暂停保留原值因此仍留在「待触发」组。
 */
function applyStatusToRow(row: ReminderListItemData, data: ReminderStatusUpdateData): ReminderListItemData {
  return {
    ...row,
    status: data.status,
    nextTriggerAt: data.nextTriggerAt,
    version: data.version,
    updatedAt: data.updatedAt,
  };
}

/**
 * 默认时区取引擎给出的 IANA 名（方案 A）；取不到或形态非法时退回 UTC。
 * 时区是否存在、RRULE 语义是否合法，权威始终在服务端 `RruleParser` 与 `RecurrenceCalculator`。
 * 课表导入的 `fallbackTimezone` 默认值复用本函数，两条链路的「默认区」必须是同一个口径。
 */
export function defaultTimezone(): string {
  const zone = new Intl.DateTimeFormat().resolvedOptions().timeZone;
  return typeof zone === 'string'
    && zone.length <= REMINDER_TIMEZONE_MAX_LENGTH
    && TIMEZONE_PATTERN.test(zone)
    ? zone
    : 'UTC';
}

/**
 * `datetime-local` 给出的是不带偏移的本地墙钟，按本机时区解释后转 UTC 提交。
 * 空值与无法解析的值都返回 null，由本地预检拦下。
 */
function toUtcInstant(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return null;
  }
  const parsed = Date.parse(trimmed);
  return Number.isNaN(parsed) ? null : new Date(parsed).toISOString();
}

/** 契约 `leadMinutes` 可省略且默认 0，因此空串按 0 处理，其余必须是区间内整数。 */
function toLeadMinutes(value: string): number | null {
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    return LEAD_MINUTES_MIN;
  }
  const minutes = Number(trimmed);
  if (!Number.isInteger(minutes) || minutes < LEAD_MINUTES_MIN || minutes > LEAD_MINUTES_MAX) {
    return null;
  }
  return minutes;
}

export type ReminderDraft =
  | { ok: true; request: ReminderCreateRequest }
  | { ok: false; reason: string };

/**
 * 一次解析同时给出本地预检结论与请求体：两形态都是 `additionalProperties: false`，
 * 所以请求体只能按 `kind` 白名单重新构造，不能把整个表单 spread 进去——
 * 否则切过 `kind` 的残留字段会让服务端返回 400。
 */
function buildRequest(form: ReminderForm): ReminderDraft {
  const title = form.title.trim();
  if (title.length === 0) {
    return { ok: false, reason: '请填写提醒标题。' };
  }
  if (title.length > REMINDER_TITLE_MAX_LENGTH) {
    return { ok: false, reason: `提醒标题不能超过 ${REMINDER_TITLE_MAX_LENGTH} 字。` };
  }
  const description = form.description.trim();
  if (description.length > REMINDER_DESCRIPTION_MAX_LENGTH) {
    return { ok: false, reason: `备注不能超过 ${REMINDER_DESCRIPTION_MAX_LENGTH} 字。` };
  }
  const timezone = form.timezone.trim();
  if (timezone.length === 0) {
    return { ok: false, reason: '请填写 IANA 时区，例如 Asia/Shanghai。' };
  }
  if (timezone.length > REMINDER_TIMEZONE_MAX_LENGTH || !TIMEZONE_PATTERN.test(timezone)) {
    return { ok: false, reason: '时区格式不合法，请使用 IANA 名称（例如 Asia/Shanghai 或 UTC）。' };
  }
  // 契约里 description 可省略，空串提交上去只会污染指纹。
  const optional = description.length === 0 ? {} : { description };

  if (form.kind === 'DEADLINE') {
    const dueAt = toUtcInstant(form.dueAt);
    if (dueAt === null) {
      return { ok: false, reason: '请选择作业截止时间。' };
    }
    const leadMinutes = toLeadMinutes(form.leadMinutes);
    if (leadMinutes === null) {
      return {
        ok: false,
        reason: `提前提醒分钟数必须是 ${LEAD_MINUTES_MIN}–${LEAD_MINUTES_MAX} 之间的整数。`,
      };
    }
    return { ok: true, request: { kind: 'DEADLINE', title, timezone, dueAt, leadMinutes, ...optional } };
  }

  const rrule = form.rrule.trim();
  if (rrule.length < RRULE_MIN_LENGTH || rrule.length > RRULE_MAX_LENGTH) {
    return { ok: false, reason: `RRULE 长度必须在 ${RRULE_MIN_LENGTH}–${RRULE_MAX_LENGTH} 之间。` };
  }
  if (!RRULE_PATTERN.test(rrule)) {
    return {
      ok: false,
      reason: 'RRULE 必须以 FREQ=SECONDLY/MINUTELY/HOURLY/DAILY/WEEKLY/MONTHLY/YEARLY 开头，参数键一律大写。',
    };
  }
  const dtstart = toUtcInstant(form.dtstart);
  if (dtstart === null) {
    return { ok: false, reason: '请选择首次触发基准时间。' };
  }
  return { ok: true, request: { kind: 'CUSTOM', title, timezone, rrule, dtstart, ...optional } };
}

export const useRemindersStore = defineStore('reminders', {
  state: (): RemindersState => ({
    items: [],
    nextCursor: null,
    listLoading: false,
    listLoaded: false,
    listSeq: 0,
    statusFilter: null,

    form: {
      kind: 'DEADLINE',
      title: '',
      description: '',
      timezone: defaultTimezone(),
      dueAt: '',
      leadMinutes: '120',
      rrule: 'FREQ=WEEKLY;BYDAY=MO',
      dtstart: '',
    },
    creating: false,
    pendingKey: null,
    createdSummary: null,

    updatingId: null,
    pendingUpdate: null,

    detail: null,
    code: null,
    traceId: null,
    retryAfterSeconds: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.listLoading && state.nextCursor !== null,

    groups: (state): ReminderGroup[] => {
      // 分组按 `status` 而不是「`nextTriggerAt` 是否为空」：2.3.0 起暂停行**保留**原计划时刻，
      // 按后者划分会把一条不会响的提醒挂进「待触发」，用户会读成「暂停没生效」。
      const upcoming = state.items.filter((item) => item.status === 'ACTIVE');
      const paused = state.items.filter((item) => item.status === 'PAUSED');
      const stopped = state.items.filter((item) => allowedTargets(item.status).length === 0);
      const groups: ReminderGroup[] = [];
      if (upcoming.length > 0) {
        groups.push({ heading: '待触发', note: '按下一次触发时间由近到远排列，只有仍在生效的提醒会出现在这里。', items: upcoming });
      }
      if (paused.length > 0) {
        groups.push({
          heading: '已暂停，到点不会响',
          note: '暂停保留服务端原计划的下一次触发时刻，但调度器只扫进行中的提醒；恢复时会按规则重算。',
          items: paused,
        });
      }
      if (stopped.length > 0) {
        groups.push({
          heading: '不再有下一次触发',
          note: '已完成或已取消是终态、不可回退，服务端已把下一次触发置空；需要再次提醒就重建一条或重新导入课表。',
          items: stopped,
        });
      }
      return groups;
    },
  },

  actions: {
    clearFailure(): void {
      clearFailureFields(this);
    },

    async loadListFirst(): Promise<void> {
      if (this.listLoading) {
        return;
      }
      await this.fetchFirstListPage();
    },

    /**
     * 取看板第一页，不受 `listLoading` 守卫约束：过滤切换、创建成功、状态冲突后的重读
     * 都必须真正取回服务端最新结果，被一次在途旧读挤掉就等于白刷；过期响应按代号丢弃。
     */
    async fetchFirstListPage(): Promise<void> {
      const seq = ++this.listSeq;
      this.listLoading = true;
      try {
        const data = await listReminders({ status: this.statusFilter, limit: PAGE_LIMIT });
        if (seq !== this.listSeq) {
          return;
        }
        this.items = data.items;
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.listLoading = false;
          this.listLoaded = true;
        }
      }
    },

    async loadListMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      const seq = this.listSeq;
      this.listLoading = true;
      try {
        const data = await listReminders({ status: this.statusFilter, cursor, limit: PAGE_LIMIT });
        if (seq !== this.listSeq) {
          return;
        }
        this.items = [...this.items, ...data.items];
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (seq !== this.listSeq) {
          return;
        }
        if (isStaleCursor(error)) {
          this.nextCursor = null;
          this.listLoading = false;
          await this.fetchFirstListPage();
          this.detail = '提醒翻页游标已失效，看板已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.listLoading = false;
        }
      }
    },

    /** 过滤在服务端做（方案 A），换过滤等于换一页，必须清空已拼接的旧结果。 */
    async updateStatusFilter(value: string): Promise<void> {
      const next: ReminderStatus | null = value === '' ? null : (value as ReminderStatus);
      if (next === this.statusFilter) {
        return;
      }
      this.statusFilter = next;
      this.items = [];
      this.nextCursor = null;
      this.listLoaded = false;
      await this.fetchFirstListPage();
    },

    /** 契约：同一幂等键对应不同请求体一律 409 `REM-CONFLICT-001`，所以任何实际改动都要弃键。 */
    patchForm(patch: Partial<ReminderForm>): void {
      const touched = (Object.keys(patch) as (keyof ReminderForm)[]).some(
        (field) => this.form[field] !== patch[field],
     );
      if (!touched) {
        return;
      }
      Object.assign(this.form, patch);
      this.pendingKey = null;
      this.createdSummary = null;
    },

    /**
     * 失败后 `pendingKey` 保留，用户点「重试」复用同一个键：
     * 服务端对「同键同请求体」返回首次结果，自动重试会把可恢复态变成持续 409。
     */
    async submitReminder(): Promise<void> {
      if (this.creating) {
        return;
      }
      const draft = buildRequest(this.form);
      if (!draft.ok) {
        rejectLocal(this, draft.reason);
        return;
      }
      const request = draft.request;

      const key = this.pendingKey ?? newIdempotencyKey();
      this.pendingKey = key;
      this.creating = true;
      this.clearFailure();
      try {
        const result = await createReminder(request, key);
        this.pendingKey = null;
        this.createdSummary = summarize(result);
        // 新提醒按 next_trigger_at 排序，可能落在首页之外；回第一页只会漏读不会重复。
        await this.fetchFirstListPage();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.creating = false;
      }
    },

    /**
     * 状态机入口（契约 `PATCH /api/v1/reminders/{reminderId}`）。
     *
     * 幂等键的复用条件比创建侧严格：指纹含 `reminderId | status | version`，
     * 所以只有「同一行、同一目标、同一期望版本」的重试才用同一个键（此时服务端返回首次结果，
     * 不重复推进版本）；行版本一变就是另一个请求体，继续沿用旧键只会撞 409 `REM-CONFLICT-001`，
     * 因此那种情况下换新键。
     *
     * 三种 409（键指纹冲突、版本冲突、非法边或恢复算不出未来触发）在客户端动作上完全相同：
     * 重读看板再决定。版本冲突尤其不是错误提示，而是「别处改过」与「它刚触发过一次」的同一种表现，
     * 错误码无法区分成因，所以这里不分支，只把服务端事实重新取回来。
     */
    async updateStatus(reminderId: string, target: ReminderStatus): Promise<boolean> {
      if (this.updatingId !== null) {
        return false;
      }
      const row = this.items.find((item) => item.reminderId === reminderId) ?? null;
      if (row === null) {
        rejectLocal(this, '看板里已经没有这一行，请重新加载后再操作。');
        return false;
      }
      if (target === row.status) {
        // 服务端对同态提交是 200 no-op（不校验版本、不推进版本），但界面上它从来不是一次有效变更。
        rejectLocal(this, '这一行已经是该状态，提交过去不会改变任何东西；看板若与服务端不一致，请重新加载。');
        return false;
      }
      if (allowedTargets(row.status).length === 0) {
        rejectLocal(this, '已完成或已取消的提醒不能回退，这是契约的终态约束；需要再次提醒就重建一条或重新导入课表。');
        return false;
      }

      const pending = this.pendingUpdate;
      const key = pending !== null
        && pending.reminderId === reminderId
        && pending.status === target
        && pending.version === row.version
        ? pending.key
        : newIdempotencyKey();
      this.pendingUpdate = { reminderId, status: target, version: row.version, key };
      this.updatingId = reminderId;
      this.clearFailure();
      try {
        const data = await updateReminderStatus(reminderId, { status: target, version: row.version }, key);
        this.pendingUpdate = null;
        await this.settleAfterStatusUpdate(reminderId, data);
        return true;
      } catch (error) {
        if (error instanceof ApiError && error.status === 409) {
          this.pendingUpdate = null;
          const reason = error.detail;
          await this.fetchFirstListPage();
          // 后端为「恢复时规则已无未来触发」这类情形给的是英文 detail，且与非法边共用 004，
          // 界面因此补一句固定的中文动作说明，而不是把服务端原文当结论。
          // 中文 detail 本身以「。」结尾，拼接前剥掉它，否则界面上出现两个连续句号。
          this.detail = `状态变更未生效：${reason.replace(/。+$/, '')}。看板已按服务端最新状态重新读取，请据此再决定。`;
          this.code = error.code;
          this.traceId = error.traceId;
        } else {
          applyFailure(this, error);
        }
        return false;
      } finally {
        this.updatingId = null;
      }
    },

    /**
     * 状态过滤生效时（方案 A，过滤在服务端做），改完状态的行可能已不属于当前筛选：
     * 按「进行中」过滤后把它暂停，回填就留下一行与筛选条件矛盾的记录。
     * 所以只有「无筛选」或「新状态仍匹配筛选」时回填，否则重取第一页，让这一行按服务端口径消失。
     */
    async settleAfterStatusUpdate(reminderId: string, data: ReminderStatusUpdateData): Promise<void> {
      if (this.statusFilter === null || this.statusFilter === data.status) {
        this.items = this.items.map((item) => (item.reminderId === reminderId ? applyStatusToRow(item, data) : item));
        return;
      }
      await this.fetchFirstListPage();
    },
  },
});

function summarize(result: Traced<ReminderCreateData>): CreatedSummary {
  return {
    title: result.data.title,
    nextTriggerAt: result.data.nextTriggerAt,
    idempotentReplay: result.data.idempotentReplay,
    traceId: result.traceId,
  };
}
