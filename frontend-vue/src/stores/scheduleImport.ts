import { defineStore } from 'pinia';
import { importSchedule, previewSchedule, type ScheduleImportForm } from '../api/reminders';
import { ApiError } from '../api/errors';
import { newIdempotencyKey } from '../api/http';
import type { ScheduleImportData, SchedulePreviewData } from '../types/reminders';
import {
  ICS_MAX_BYTES,
  LEAD_MINUTES_MAX,
  LEAD_MINUTES_MIN,
  REMINDER_TIMEZONE_MAX_LENGTH,
  TIMEZONE_PATTERN,
} from '../types/reminders';
import { formatBytes } from '../utils/display';
import { defaultTimezone, useRemindersStore } from './reminders';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
  retryAfterSeconds: number | null;
}

export interface ScheduleImportState extends FailureFields {
  /** 只在内存里持有 File：契约要的是原始字节，任何「先上传再判定」的形态都不存在。 */
  file: File | null;
  fallbackTimezone: string;
  /** 与手工创建同口径：`number` 输入按字符串保存，避免把非法中间态写进数字字段。 */
  leadMinutes: string;
  previewing: boolean;
  importing: boolean;
  preview: SchedulePreviewData | null;
  /** 预览成功信封的 traceId：422 报障时它是唯一对账凭据，与失败展示用的 `traceId` 分开存放。 */
  previewTraceId: string | null;
  result: ScheduleImportData | null;
  resultTraceId: string | null;
  /** null 表示「尚未提交」；失败后保留同键供重试，任何输入变化都会弃键并作废预览。 */
  pendingKey: string | null;
}

function applyFailure(target: FailureFields, error: unknown): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    target.retryAfterSeconds = error.retryAfterSeconds;
    return;
  }
  target.detail = '课表导入请求失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
  target.retryAfterSeconds = null;
}

/** 本地预检不通过：没有服务端响应，也就没有 code 与 traceId。 */
function rejectLocal(target: FailureFields, message: string): void {
  target.detail = message;
  target.code = null;
  target.traceId = null;
  target.retryAfterSeconds = null;
}

export type ScheduleDraft = { ok: true; form: ScheduleImportForm } | { ok: false; reason: string };

/**
 * 本地只拦「发出去必然失败」的四件事：没选文件、字节超限、时区形态非法、提前分钟数越界。
 * 文件内容是否真是 ICS、时区是否真实存在、事件是否重复 UID，权威都在服务端（415 / 422 / 409）。
 */
function buildDraft(state: Pick<ScheduleImportState, 'file' | 'fallbackTimezone' | 'leadMinutes'>): ScheduleDraft {
  if (state.file === null) {
    return { ok: false, reason: '请先选择 .ics 课表文件。' };
  }
  if (state.file.size > ICS_MAX_BYTES) {
    // 精确字节数是主文案：formatBytes 会把 2097153 显示成「2.0 MiB」，
    // 只报取整值就等于对用户说「不能超过 2.0 MiB（当前 2.0 MiB）」。
    return {
      ok: false,
      reason:
        `课表文件不能超过 ${ICS_MAX_BYTES} 字节（${formatBytes(ICS_MAX_BYTES)}），` +
        `当前 ${state.file.size} 字节。`,
    };
  }
  const timezone = state.fallbackTimezone.trim();
  if (timezone.length === 0) {
    return { ok: false, reason: '请填写兜底时区，例如 Asia/Shanghai。' };
  }
  if (timezone.length > REMINDER_TIMEZONE_MAX_LENGTH || !TIMEZONE_PATTERN.test(timezone)) {
    return { ok: false, reason: '兜底时区格式不合法，请使用 IANA 名称（例如 Asia/Shanghai 或 UTC）。' };
  }
  const minutes = Number(state.leadMinutes.trim());
  if (!Number.isInteger(minutes) || minutes < LEAD_MINUTES_MIN || minutes > LEAD_MINUTES_MAX) {
    return {
      ok: false,
      reason: `提前提醒分钟数必须是 ${LEAD_MINUTES_MIN}–${LEAD_MINUTES_MAX} 之间的整数。`,
    };
  }
  return { ok: true, form: { file: state.file, fallbackTimezone: timezone, leadMinutes: minutes } };
}

export const useScheduleImportStore = defineStore('scheduleImport', {
  state: (): ScheduleImportState => ({
    file: null,
    fallbackTimezone: defaultTimezone(),
    leadMinutes: '10',
    previewing: false,
    importing: false,
    preview: null,
    previewTraceId: null,
    result: null,
    resultTraceId: null,
    pendingKey: null,

    detail: null,
    code: null,
    traceId: null,
    retryAfterSeconds: null,
  }),

  getters: {
    /**
     * 门禁只表示「忙不忙」（预览与导入共用限流桶），文件是否已选交给 `buildDraft` 报原因：
     * 把 `file !== null` 写进门禁会让「请先选择文件」这条预检永远没有出口，
     * 用户面对的是一个不动的按钮，而不是一句能照做的提示。与提醒表单同口径。
     */
    canPreview: (state): boolean => !state.previewing && !state.importing,
    /** 判定可见是导入的前提：没有预览过就没有「这批会写成什么样」的依据。 */
    canImport: (state): boolean => state.preview !== null && !state.previewing && !state.importing,
  },

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
      this.retryAfterSeconds = null;
    },

    /** 换文件就是换请求体：预览结论、它的 trace 与幂等键同时作废。 */
    selectFile(file: File | null): void {
      if (file === this.file) {
        return;
      }
      this.file = file;
      this.invalidatePreview();
      this.clearFailure();
    },

    patchForm(patch: Partial<Pick<ScheduleImportState, 'fallbackTimezone' | 'leadMinutes'>>): void {
      const fields: ('fallbackTimezone' | 'leadMinutes')[] = ['fallbackTimezone', 'leadMinutes'];
      const touched = fields.some((field) => patch[field] !== undefined && patch[field] !== this[field]);
      if (!touched) {
        return;
      }
      Object.assign(this, patch);
      // 判定参数既是幂等指纹的一部分，也是预览结论的前提，两者都不能留在旧值上。
      this.invalidatePreview();
    },

    /** `preview` 与 `previewTraceId` 同生同灭：留着旧 trace 就等于声称「这份结论还在」。 */
    invalidatePreview(): void {
      this.preview = null;
      this.previewTraceId = null;
      this.pendingKey = null;
    },

    async runPreview(): Promise<void> {
      if (!this.canPreview) {
        return;
      }
      const draft = buildDraft(this);
      if (!draft.ok) {
        rejectLocal(this, draft.reason);
        return;
      }
      this.previewing = true;
      this.clearFailure();
      try {
        const traced = await previewSchedule(draft.form);
        this.preview = traced.data;
        // 这份判定结论的唯一对账凭据；随每次请求变化，只保留最近一次。
        this.previewTraceId = traced.traceId;
      } catch (error) {
        // 这里不能用 invalidatePreview：导入失败留下的 pendingKey 要能扛过一次失败的预览重试。
        this.preview = null;
        this.previewTraceId = null;
        applyFailure(this, error);
      } finally {
        this.previewing = false;
      }
    },

    /**
     * 失败后 `pendingKey` 保留，用户点「重试」复用同一个键：
     * 同键同文件是重放（还原首次计数），换键重试则是再写一整批。
     */
    async commitImport(): Promise<void> {
      if (!this.canImport) {
        return;
      }
      const draft = buildDraft(this);
      if (!draft.ok) {
        rejectLocal(this, draft.reason);
        return;
      }
      const key = this.pendingKey ?? newIdempotencyKey();
      this.pendingKey = key;
      this.importing = true;
      this.clearFailure();
      try {
        const traced = await importSchedule(draft.form, key);
        this.pendingKey = null;
        this.result = traced.data;
        this.resultTraceId = traced.traceId;
        // 导入直接改写看板上的 COURSE 行，落库后必须重取；跨页导航由看板自己的加载兜底。
        await useRemindersStore().loadListFirst();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.importing = false;
      }
    },
  },
});
