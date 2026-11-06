import { defineStore } from 'pinia';
import { listAuditEvents } from '../api/admin';
import { ApiError } from '../api/errors';
import type { AuditEventItem } from '../types/admin';
import { AUDIT_EVENT_TYPE_PATTERN, AUDIT_TRACE_ID_PATTERN } from '../types/admin';

/** 契约 `AuditQueryLimit` 的默认值。 */
const PAGE_LIMIT = 100;

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

/** 查询条件表单的输入；与 store 字段的区别只在于这里都是可选的原始文本。 */
export interface AuditFilterInput {
  traceId?: string;
  userId?: string;
  eventType?: string;
  from?: string;
  to?: string;
}

/** 归一化之后的查询参数：条件为空就不出现在请求里。 */
export interface AuditQueryPayload {
  traceId?: string;
  userId?: string;
  eventType?: string;
  from?: string;
  to?: string;
  cursor?: string;
  limit: number;
}

export interface AdminAuditState extends FailureFields {
  events: AuditEventItem[];
  nextCursor: string | null;
  loading: boolean;
  loaded: boolean;
  /** 查询条件提交的在途标记；与列表读锁分开，翻页不该让「查询」按钮转起来。 */
  submitting: boolean;

  filterTraceId: string | null;
  filterUserId: string | null;
  filterEventType: string | null;
  filterFrom: string | null;
  filterTo: string | null;
}

function applyFailure(target: FailureFields, error: unknown): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    return;
  }
  target.detail = '审计事件加载失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
}

function rejectLocal(target: FailureFields, message: string): void {
  target.detail = message;
  target.code = null;
  target.traceId = null;
}

function isStaleCursor(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400;
}

/** 空串与纯空格都归一成「不给这个条件」：契约的 pattern 对空串同样校验，发出去只会换来 400。 */
function normalized(value: string | undefined): string | null {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? null : trimmed;
}

export const useAdminAuditStore = defineStore('adminAudit', {
  state: (): AdminAuditState => ({
    events: [],
    nextCursor: null,
    loading: false,
    loaded: false,
    submitting: false,

    filterTraceId: null,
    filterUserId: null,
    filterEventType: null,
    filterFrom: null,
    filterTo: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.loading && state.nextCursor !== null,
    hasFilter: (state): boolean =>
      state.filterTraceId !== null ||
      state.filterUserId !== null ||
      state.filterEventType !== null ||
      state.filterFrom !== null ||
      state.filterTo !== null,

    queryParams: (state): AuditQueryPayload => ({
      traceId: state.filterTraceId ?? undefined,
      userId: state.filterUserId ?? undefined,
      eventType: state.filterEventType ?? undefined,
      from: state.filterFrom ?? undefined,
      to: state.filterTo ?? undefined,
      limit: PAGE_LIMIT,
    }),
  },

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
    },

    async loadFirst(): Promise<void> {
      if (this.loading) {
        return;
      }
      await this.fetchFirstPage();
    },

    async fetchFirstPage(): Promise<void> {
      this.loading = true;
      try {
        const data = await listAuditEvents(this.queryParams);
        this.events = data.items;
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    async loadMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      this.loading = true;
      try {
        const data = await listAuditEvents({ ...this.queryParams, cursor });
        this.events = [...this.events, ...data.items];
        this.nextCursor = data.nextCursor;
        this.clearFailure();
      } catch (error) {
        if (isStaleCursor(error)) {
          // 排序键是 `occurred_at DESC, id DESC`：锚点失效就回第一页，停在坏游标上只会看不到更旧的事件。
          this.nextCursor = null;
          this.loading = false;
          await this.fetchFirstPage();
          this.detail = '审计翻页游标已失效，列表已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        this.loading = false;
      }
    },

    /**
     * 条件在每一页重新求值，不是快照：换条件等于换一页，必须清空已拼接的旧结果。
     * 返回值只表示「条件通过本地校验并已发起查询」，读失败经 `detail` 呈现，不由它代述。
     * 31 天窗口上限只写在契约的 description 里、没有机器可读键，因此本地不预拦，
     * 由服务端那条 400 原样呈现——按人话猜出来的上限不是契约。
     */
    async applyFilters(input: AuditFilterInput): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      const traceId = normalized(input.traceId);
      const eventType = normalized(input.eventType);
      if (traceId !== null && !AUDIT_TRACE_ID_PATTERN.test(traceId)) {
        rejectLocal(this, 'traceId 必须是 32 位小写十六进制。');
        return false;
      }
      if (eventType !== null && !AUDIT_EVENT_TYPE_PATTERN.test(eventType)) {
        rejectLocal(this, 'eventType 需是大写字母开头、仅含大写字母/数字/下划线且不超过 64 个字符。');
        return false;
      }
      this.filterTraceId = traceId;
      this.filterUserId = normalized(input.userId);
      this.filterEventType = eventType;
      this.filterFrom = normalized(input.from);
      this.filterTo = normalized(input.to);
      this.events = [];
      this.nextCursor = null;
      this.loaded = false;
      this.submitting = true;
      try {
        await this.fetchFirstPage();
        return true;
      } finally {
        this.submitting = false;
      }
    },
  },
});
