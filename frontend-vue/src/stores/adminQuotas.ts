import { defineStore } from 'pinia';
import { listAiQuotas, updateAiQuota } from '../api/admin';
import type { AiQuota, UpdateAiQuotaRequest } from '../types/admin';
import { QUOTA_LIMIT_MAX, QUOTA_LIMIT_MIN } from '../types/admin';
import {
  isStaleCursor,
  makeApplyFailure,
  rejectLocal,
  type FailureFields,
} from './failureFields';

/** 契约 `QuotaListLimit` 的默认值；显式发送，与其余列表页同口径，免得两侧默认各自漂移。 */
const PAGE_LIMIT = 50;

const applyFailure = makeApplyFailure('配额列表加载失败，请稍后重试。');

export interface AdminQuotasState extends FailureFields {
  items: AiQuota[];
  nextCursor: string | null;
  loading: boolean;
  loaded: boolean;
  /** 列表取数代号：写后刷新递增它，在途翻页的过期响应一律丢弃。 */
  listSeq: number;
  /** 配额写入串行锁：整行替换语义下并发提交只会留下 last-write-wins，界面先把自己这一侧串起来。 */
  submitting: boolean;
}

/**
 * 契约的区间只到「整数 1–4294967295 或 null」，null 的含义是「该维度不设个性化上限」。
 * 这里在请求发出前挡住非法值，是为了让管理员看到的是一条能说清楚的本地提示，
 * 而不是一个来自服务端的 400。
 */
function isAcceptableLimit(value: number | null): boolean {
  if (value === null) {
    return true;
  }
  return Number.isInteger(value) && value >= QUOTA_LIMIT_MIN && value <= QUOTA_LIMIT_MAX;
}

export const useAdminQuotasStore = defineStore('adminQuotas', {
  state: (): AdminQuotasState => ({
    items: [],
    nextCursor: null,
    loading: false,
    loaded: false,
    listSeq: 0,
    submitting: false,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    canLoadMore: (state): boolean => !state.loading && state.nextCursor !== null,
  },

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
    },

    /**
     * 首次加载不设 `loading` 守卫：写后刷新与在途翻页并发时，新请求必须真正发出，
     * 由 `fetchFirstPage` 递增 `listSeq` 把旧的在途响应丢弃，而不是被守卫直接挡掉导致刚改的配额不上屏。
     */
    async loadFirst(): Promise<void> {
      await this.fetchFirstPage();
    },

    async fetchFirstPage(): Promise<void> {
      const seq = ++this.listSeq;
      this.loading = true;
      try {
        const data = await listAiQuotas({ limit: PAGE_LIMIT });
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
          this.loading = false;
          this.loaded = true;
        }
      }
    },

    async loadMore(): Promise<void> {
      const cursor = this.nextCursor;
      if (!this.canLoadMore || cursor === null) {
        return;
      }
      const seq = this.listSeq;
      this.loading = true;
      try {
        const data = await listAiQuotas({ cursor, limit: PAGE_LIMIT });
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
          // keyset 游标锚定 `updated_at DESC, user_id DESC`，两次翻页之间有人改过配额就会让锚点失效；
          // 停在坏游标上只会让人永远看不到剩余的行，所以回第一页。
          this.nextCursor = null;
          this.loading = false;
          await this.fetchFirstPage();
          this.detail = '配额翻页游标已失效，列表已回到第一页。';
          return;
        }
        applyFailure(this, error);
      } finally {
        if (seq === this.listSeq) {
          this.loading = false;
        }
      }
    },

    /**
     * 全量替换（D-197）：两个字段都必须出现，缺一个不是「保持原值」而是 400。
     * 成功后重取第一页而不是就地改这一行——排序键是 `updated_at DESC`，
     * 刚写入的行本来就该换到最前面，本地拼接会把顺序说成服务端没有承诺的样子。
     */
    async saveQuota(userId: string, request: UpdateAiQuotaRequest): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      if (!isAcceptableLimit(request.dailyQuestionLimit) || !isAcceptableLimit(request.dailyTokenLimit)) {
        rejectLocal(
          this,
          `上限必须是 ${String(QUOTA_LIMIT_MIN)}–${String(QUOTA_LIMIT_MAX)} 的整数，留空表示该维度不设上限。`,
        );
        return false;
      }
      this.submitting = true;
      try {
        await updateAiQuota(userId, request);
        await this.fetchFirstPage();
        return true;
      } catch (error) {
        applyFailure(this, error);
        return false;
      } finally {
        this.submitting = false;
      }
    },
  },
});
