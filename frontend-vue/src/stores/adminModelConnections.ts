import { defineStore } from 'pinia';
import {
  authorizeModelConnection,
  deleteModelConnection,
  listModelConnectionPresets,
  listModelConnections,
  probeModelConnection,
  updateModelConnectionActivation,
  upsertModelConnection,
} from '../api/modelConnections';
import { ApiError } from '../api/errors';
import type { ModelConnection, ModelPreset, ModelProbeData } from '../types/modelConnections';
import { mergeModels, probeFailureMessage } from '../utils/modelConnection';

interface FailureFields {
  detail: string | null;
  code: string | null;
  traceId: string | null;
}

export interface ConnectInput {
  name: string;
  presetId: string | null;
  baseUrl: string;
  model: string;
  /** 明文 Key，只在本次编排里出现，随后立即丢弃；不写进 state。 */
  secret: string;
  /** 本地无 Key 端点（Ollama 等）跳过带凭据的探测。 */
  noKeyAllowed: boolean;
  /** 端点不提供 GET /models 时，仍按填的模型名保存并设为生效。 */
  allowWithoutProbe: boolean;
}

export interface ConnectResult {
  ok: boolean;
  connectionId: string | null;
  message: string | null;
  /** 探测未通过但按降级策略保留时的提示。 */
  warning: string | null;
}

export interface AdminModelConnectionsState extends FailureFields {
  /** null 是「还没读到服务端事实」，不是「没有连接」。 */
  items: ModelConnection[] | null;
  loading: boolean;
  loaded: boolean;
  /** 服务端下发的厂商预设；null 表示还没读到，不是「没有预设」。 */
  presets: ModelPreset[] | null;
  presetsLoading: boolean;
  /** 写入串行锁：同一时刻只允许一个在途写操作。 */
  submitting: boolean;
  /** 最近一次探测结论；null 表示还没探测过。 */
  probe: ModelProbeData | null;
  /** 写操作的结果文案（role=status），与错误面板分开。 */
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

export const useAdminModelConnectionsStore = defineStore('adminModelConnections', {
  state: (): AdminModelConnectionsState => ({
    items: null,
    loading: false,
    loaded: false,
    presets: null,
    presetsLoading: false,
    submitting: false,
    probe: null,
    notice: null,

    detail: null,
    code: null,
    traceId: null,
  }),

  getters: {
    activeConnection: (state): ModelConnection | null => state.items?.find((item) => item.active) ?? null,
    /** 未加载时是 null 而不是 0：把「未知」渲染成「没有连接」就是伪造事实。 */
    count: (state): number | null => state.items?.length ?? null,
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

    /** 不受 loading 守卫约束：写入之后必须真取一次，否则服务端事实被一次在途旧读挤掉。 */
    async fetch(): Promise<void> {
      this.loading = true;
      try {
        const data = await listModelConnections();
        this.items = data.items;
        this.clearFailure();
      } catch (error) {
        applyFailure(this, error, '模型连接读取失败，请稍后重试。');
      } finally {
        this.loading = false;
        this.loaded = true;
      }
    },

    /**
     * 写失败后重读：只回填服务端事实，**不清除错误条**。
     * 直接调 fetch 会在重读成功时把刚发生的失败抹掉，用户就看不到失败原因了。
     */
    async readAfterFailedWrite(): Promise<void> {
      try {
        const data = await listModelConnections();
        this.items = data.items;
      } catch {
        // 重读也失败时保留原错误：屏幕上最后一条错误必须说明「状态未确认」。
      }
    },

    /**
     * 厂商预设由服务端下发。读取失败就如实报错并提供「自定义端点」入口，
     * 不在前端退回一份硬编码清单——那既违反红线，也会把过期端点当成事实呈现。
     */
    async loadPresets(): Promise<void> {
      if (this.presetsLoading || this.presets !== null) {
        return;
      }
      this.presetsLoading = true;
      try {
        const data = await listModelConnectionPresets();
        this.presets = data.items;
      } catch (error) {
        applyFailure(this, error, '厂商预设读取失败，可改用自定义端点。');
      } finally {
        this.presetsLoading = false;
      }
    },

    /**
     * 向导编排：建立 → 录入凭据 → 探测 → 设为生效。
     *
     * 回滚只删**本次新建**的连接：创建是按租户内 name 的 upsert，同名命中既有行时
     * 删掉它等于摧毁用户原有配置，所以先记下调用前的 id 集合再决定是否回滚。
     * 明文 secret 只作为局部变量传给那一次请求，不写进 state、不进 notice。
     */
    async connect(input: ConnectInput): Promise<ConnectResult> {
      if (this.submitting) {
        return { ok: false, connectionId: null, message: '已有写入在进行中，请稍后重试。', warning: null };
      }
      this.submitting = true;
      this.notice = null;
      this.probe = null;
      const knownIds = new Set((this.items ?? []).map((item) => item.id));
      let createdId: string | null = null;
      let isNew = false;
      try {
        const created = await upsertModelConnection({
          name: input.name,
          presetId: input.presetId,
          baseUrl: input.baseUrl,
          model: input.model,
          enabled: true,
        });
        createdId = created.id;
        isNew = !knownIds.has(created.id);

        const secret = input.secret.trim();
        if (secret) {
          await authorizeModelConnection(created.id, { secret });
        }

        let warning: string | null = null;
        const probeAttempted = Boolean(secret);
        if (probeAttempted) {
          const probe = await probeModelConnection(created.id);
          this.probe = probe;
          if (!probe.ok && !input.allowWithoutProbe) {
            await this.rollback(createdId, isNew);
            return {
              ok: false,
              connectionId: null,
              message: probeFailureMessage(probe.failureReason, probe.detail),
              warning: null,
            };
          }
          if (!probe.ok) {
            warning = `端点未提供模型清单（${probeFailureMessage(probe.failureReason, probe.detail)}），已按填写的模型名保存，请在问答中确认`;
          }
          if (probe.ok && probe.models.length > 0) {
            // 探测到的候选只用于展示与后续编辑，不落库（契约有意不持久化瞬态清单）。
            this.notice = `探测通过，可用模型：${mergeModels([input.model], probe.models).join('、')}`;
          }
        } else {
          warning = '未使用 API Key，跳过自动探测，请在问答中确认';
        }

        await updateModelConnectionActivation(created.id, { active: true });
        await this.fetch();
        this.notice = warning ?? this.notice ?? '连接已建立并设为生效。';
        return { ok: true, connectionId: createdId, message: null, warning };
      } catch (error) {
        await this.rollback(createdId, isNew);
        const message = error instanceof ApiError ? error.detail : '建立连接失败，请稍后重试。';
        applyFailure(this, error, '建立连接失败，请稍后重试。');
        return { ok: false, connectionId: null, message, warning: null };
      } finally {
        this.submitting = false;
      }
    },

    /** 只回滚本次新建的行；回滚失败不掩盖真实错误，残留连接可在列表里删除。 */
    async rollback(connectionId: string | null, isNew: boolean): Promise<void> {
      if (!connectionId || !isNew) {
        return;
      }
      try {
        await deleteModelConnection(connectionId);
      } catch {
        // 清理失败不影响把真实错误告知用户。
      }
    },

    async setActive(connectionId: string, active: boolean): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        await updateModelConnectionActivation(connectionId, { active });
        await this.fetch();
        this.notice = active ? '已设为生效连接，问答立即使用它，无需重启。' : '已取消生效；若无其它生效连接，问答回退到部署配置的模型。';
        this.clearFailure();
        return true;
      } catch (error) {
        applyFailure(this, error, '生效状态变更失败，请稍后重试。');
        await this.readAfterFailedWrite();
        return false;
      } finally {
        this.submitting = false;
      }
    },

    /** 启用/停用没有独立端点，走同一个 upsert，其余字段原样回填以免误改配置。 */
    async setEnabled(connection: ModelConnection, enabled: boolean): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        await upsertModelConnection({
          name: connection.name,
          presetId: connection.presetId,
          baseUrl: connection.baseUrl,
          model: connection.model,
          enabled,
        });
        await this.fetch();
        this.notice = enabled ? '连接已启用。' : '连接已停用；停用期间不参与生效判定。';
        this.clearFailure();
        return true;
      } catch (error) {
        applyFailure(this, error, '启用状态变更失败，请稍后重试。');
        await this.readAfterFailedWrite();
        return false;
      } finally {
        this.submitting = false;
      }
    },

    async reprobe(connectionId: string): Promise<ModelProbeData | null> {
      if (this.submitting) {
        return null;
      }
      this.submitting = true;
      try {
        const probe = await probeModelConnection(connectionId);
        this.probe = probe;
        this.notice = probe.ok
          ? `探测通过，可用模型：${probe.models.join('、')}`
          : probeFailureMessage(probe.failureReason, probe.detail);
        await this.fetch();
        this.clearFailure();
        return probe;
      } catch (error) {
        applyFailure(this, error, '探测请求失败，请稍后重试。');
        return null;
      } finally {
        this.submitting = false;
      }
    },

    async remove(connectionId: string): Promise<boolean> {
      if (this.submitting) {
        return false;
      }
      this.submitting = true;
      try {
        await deleteModelConnection(connectionId);
        await this.fetch();
        this.notice = '连接已删除，其凭据密文一并删除。';
        this.clearFailure();
        return true;
      } catch (error) {
        applyFailure(this, error, '删除失败，请稍后重试。');
        await this.readAfterFailedWrite();
        return false;
      } finally {
        this.submitting = false;
      }
    },
  },
});
