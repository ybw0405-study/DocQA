import { defineStore } from 'pinia';
import { getTenantAiControls, updateTenantAiControls } from '../api/admin';
import { ApiError } from '../api/errors';
import type { KillSwitchState } from '../types/admin';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface TenantControlsState extends FailureFields {
  /**
   * null 是「还没读到服务端事实」，不是「本租户开关是关的」。
   * `active` 是平台级全局键与本租户键按 OR 叠加后的**有效状态**，不是本租户键本身。
   */
  data: KillSwitchState | null;
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
  target.detail = '本租户熔断状态读取失败，请稍后重试。';
  target.code = null;
  target.traceId = null;
}

export const useTenantControlsStore = defineStore('tenantControls', {
  state: (): TenantControlsState => ({
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
    active: (state): boolean | null => state.data?.active ?? null,
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
        this.data = await getTenantAiControls();
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error);
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    /**
     * 全量赋值本租户键、按值幂等：契约上既没有 `version` 也没有幂等键参数。
     * 成功响应是写入后重新读取的**有效状态**（全局 OR 本租户），可以整体采纳；
     * 若平台级全局键仍开启，写入 false 的回显依旧是 `active=true`——这不是写入失败，
     * 界面必须如实呈现，不能宣称「服务已恢复」。
     * 500 是「开关已生效、审计写入失败」，失败分支仍要重读，且重读不抹掉这条错误。
     */
    async setTenantKillSwitch(active: boolean): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        this.data = await updateTenantAiControls({ killSwitchActive: active });
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
        this.data = await getTenantAiControls();
      } catch (error) {
        applyFailure(this, error);
      }
    },
  },
});
