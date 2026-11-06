import { defineStore } from 'pinia';
import { getAiUsage } from '../api/admin';
import { ApiError } from '../api/errors';
import type { UsageBucket, UsageSummary } from '../types/admin';

/** 契约 `UsageTopUsers` 的默认值；显式发送，与分页参数同口径。 */
const TOP_USERS = 10;
/** 契约目前只发布 `day` 一种粒度。 */
const BUCKET: UsageBucket = 'day';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface AdminUsageState extends FailureFields {
  summary: UsageSummary | null;
  loading: boolean;
  loaded: boolean;
  /** 时间窗表单的提交在途；与首屏读锁分开，刷新不该让「查询」按钮转起来。 */
  submitting: boolean;
  /** null 表示不给这个条件，交给服务端默认的「最近 24 小时」。 */
  from: string | null;
  to: string | null;
}

function applyFailure(target: FailureFields, error: unknown): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    return;
  }
  target.detail = '用量汇总加载失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
}

function normalized(value: string | undefined): string | null {
  const trimmed = value?.trim() ?? '';
  return trimmed === '' ? null : trimmed;
}

export const useAdminUsageStore = defineStore('adminUsage', {
  state: (): AdminUsageState => ({
    summary: null,
    loading: false,
    loaded: false,
    submitting: false,
    from: null,
    to: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    /** 有窗口才带条件；空串既不是合法时刻也不是「不筛」。 */
    queryParams: (state) => ({
      from: state.from ?? undefined,
      to: state.to ?? undefined,
      bucket: BUCKET,
      topUsers: TOP_USERS,
    }),
  },

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
    },

    async load(): Promise<void> {
      if (this.loading) {
        return;
      }
      await this.fetch();
    },

    async fetch(): Promise<void> {
      this.loading = true;
      try {
        this.summary = await getAiUsage(this.queryParams);
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    /**
     * 时间窗只做「空 → 不给条件」的归一：起点早于终点、跨度不超过 31 天这两条写在契约的
     * description 里，没有机器可读键，本地预拦就得靠猜。猜错了把合法查询挡下来，
     * 比让服务端给出那条 400 更糟。
     */
    async applyWindow(input: { from?: string; to?: string }): Promise<void> {
      if (this.submitting) {
        return;
      }
      this.from = normalized(input.from);
      this.to = normalized(input.to);
      this.submitting = true;
      try {
        await this.fetch();
      } finally {
        this.submitting = false;
      }
    },
  },
});
