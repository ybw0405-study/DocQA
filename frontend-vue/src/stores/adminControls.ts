import { defineStore } from 'pinia';
import { getAiControls, updateAiControls } from '../api/admin';
import { ApiError } from '../api/errors';
import type { AdminAiControls } from '../types/admin';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface AdminControlsState extends FailureFields {
  /** null 是「还没读到服务端事实」，不是「开关是关的」。 */
  data: AdminAiControls | null;
  loading: boolean;
  loaded: boolean;
  /** 写入串行锁：同一时刻只允许一个在途 PUT。 */
  submitting: boolean;
}

function applyFailure(target: FailureFields, error: unknown): void {
  if (error instanceof ApiError) {
    target.detail = error.detail;
    target.code = error.code;
    target.traceId = error.traceId;
    return;
  }
  target.detail = '治理状态读取失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
}

export const useAdminControlsStore = defineStore('adminControls', {
  state: (): AdminControlsState => ({
    data: null,
    loading: false,
    loaded: false,
    submitting: false,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    /** 未加载时给出 null 而不是 false：界面把「未知」渲染成「关」就是伪造治理事实。 */
    killSwitchActive: (state): boolean | null => state.data?.killSwitch.active ?? null,
    limitsKnown: (state): boolean => state.data?.experimentBudget.limitsKnown ?? false,
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

    /** 不受 `loading` 守卫约束：写入之后必须真取一次，否则服务端事实被一次在途旧读挤掉。 */
    async fetch(): Promise<void> {
      this.loading = true;
      try {
        this.data = await getAiControls();
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    /**
     * 开关是全量赋值、按值幂等：契约上既没有 `version` 也没有幂等键参数（D-197），
     * 重复提交同一个值仍然合法。并发覆盖在服务端是 last-write-wins，界面检测不到，
     * 所以这里不做任何本地比对，也不假装能拦住别人的写入。
     *
     * 成功响应就是写入后的完整状态，可以整体采纳；500 是「开关已生效、审计写入失败」，
     * 因此失败分支仍要重读，且重读不抹掉这条错误。
     */
    async setKillSwitch(active: boolean): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        this.data = await updateAiControls({ killSwitchActive: active });
        this.clearFailure();
        return true;
      } catch (error) {
        applyFailure(this, error);
        if (error instanceof ApiError && error.status === 500) {
          await this.readAfterFailedWrite();
        }
        return false;
      } finally {
        this.submitting = false;
      }
    },

    /** 只回填状态：重读也失败时，屏幕上最后一条错误必须说明「状态未确认」而不是旧的开关值。 */
    async readAfterFailedWrite(): Promise<void> {
      try {
        this.data = await getAiControls();
      } catch (error) {
        applyFailure(this, error);
      }
    },
  },
});
