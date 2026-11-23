import { defineStore } from 'pinia';
import { deleteMemory, listMemories } from '../api/memories';
import { ApiError } from '../api/errors';
import type { MemoryItem } from '../types/memories';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface AdminMemoriesState extends FailureFields {
  /** null 是「还没读到服务端事实」，不是「没有记忆」。 */
  items: MemoryItem[] | null;
  loading: boolean;
  loaded: boolean;
  /** 写入串行锁：同一时刻只允许一个在途删除。 */
  submitting: boolean;
  /** 删除成功的结果文案（role=status），与错误面板分开。 */
  notice: string | null;
}

function applyFailure(target: FailureFields, error: unknown, fallback: string): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    return;
  }
  target.detail = fallback;
  target.code = null;
  target.traceId = null;
}

export const useAdminMemoriesStore = defineStore('adminMemories', {
  state: (): AdminMemoriesState => ({
    items: null,
    loading: false,
    loaded: false,
    submitting: false,
    notice: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  actions: {
    clearFailure(): void {
      this.detail = null;
      this.code = null;
      this.traceId = null;
    },

    async fetch(targetUserId: string): Promise<void> {
      this.loading = true;
      this.notice = null;
      try {
        const data = await listMemories(targetUserId);
        this.items = data.items;
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error, '记忆读取失败，请稍后重试。');
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    /**
     * 写失败后重读：只回填服务端事实，**不清除错误条**——
     * 直接清除会把刚发生的失败抹掉，用户就看不到失败原因了。
     */
    async readAfterFailedWrite(targetUserId: string): Promise<void> {
      try {
        const data = await listMemories(targetUserId);
        this.items = data.items;
      } catch {
        // 重读也失败时保留原错误：屏幕上最后一条错误必须说明「状态未确认」。
      }
    },

    async remove(targetUserId: string, memoryKey: string): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        await deleteMemory(targetUserId, memoryKey);
        await this.fetch(targetUserId);
        this.notice = '记忆已删除，下一次问答不再注入它。';
        this.clearFailure();
        return true;
      } catch (error) {
        applyFailure(this, error, '删除失败，请稍后重试。');
        await this.readAfterFailedWrite(targetUserId);
        return false;
      } finally {
        this.submitting = false;
      }
    },
  },
});