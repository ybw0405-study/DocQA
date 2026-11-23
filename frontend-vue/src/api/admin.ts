import type {
  AdminAiControls,
  AiQuota,
  AiQuotaListData,
  AuditEventListData,
  KillSwitchState,
  ObservabilityData,
  UpdateAiControlsRequest,
  UpdateAiQuotaRequest,
  UsageBucket,
  UsageSummary,
} from '../types/admin';
import { get, put } from './http';

/**
 * 管理面治理操作的唯一出口（契约 `Admin` tag）：S9 的六个平台级操作，
 * 加上 2.0.0 新增的两个租户级 Kill Switch 操作（`/admin/ai/controls/tenant`），
 * 以及 2.8.0 的可观测性入口只读（`/admin/observability`，部署配置而非治理动作）。
 *
 * **这里不构造任何请求头**：八个管理操作的参数集合里都没有名为 `IdempotencyKey` 的 parameter，
 * 三个 PUT 的请求体也都没有 `version` 字段（D-197）。共享拦截器仍会给非 GET 补一个随机幂等键头
 * （那个字面量只存在于 `http.ts`），服务端不消费它——与 `api/notifications.ts` 的已读接口是同一处
 * 既有裁决，因此不为管理端单开一条通道。按值幂等与 last-write-wins 的边界在 store 与界面文案里如实表述。
 */

export interface AiQuotaListQuery {
  /** 取自上一页的 `nextCursor`；不透明，客户端不得自行构造。 */
  cursor?: string;
  limit?: number;
}

export interface AuditEventQuery {
  traceId?: string;
  userId?: string;
  eventType?: string;
  /** RFC 3339 date-time，服务端按 UTC 解释。缺省为 `to - 24h`。 */
  from?: string;
  to?: string;
  cursor?: string;
  limit?: number;
}

export interface AiUsageQuery {
  from?: string;
  to?: string;
  bucket?: UsageBucket;
  topUsers?: number;
}

/** Kill Switch 与实验账本两项互相独立的治理状态；`updatedAt`/`updatedBy` 缺失即为 null。 */
export function getAiControls(): Promise<AdminAiControls> {
  return get<AdminAiControls>('/admin/ai/controls');
}

/**
 * 全量赋值开关，响应回显写入后的完整状态。
 * 审计写入失败时服务端返回 500 并在 `detail` 说明「开关状态已变更」——**开关其实已经生效**，
 * 所以调用方失败后必须重读，不能把这次调用呈现成「什么都没发生」。
 */
export function updateAiControls(request: UpdateAiControlsRequest): Promise<AdminAiControls> {
  return put<AdminAiControls>('/admin/ai/controls', request);
}

/**
 * 读取**当前登录租户视角**的 Kill Switch 有效状态（契约 2.6.0 `getTenantAiControls`）。
 * `tenantId` 只由服务端取自已验签 JWT，不接受请求体或 Header 传参，因此这个方法没有租户参数。
 *
 * 返回的 `active` 是「平台级全局键 OR 本租户键」的叠加结果：任一开启即为 `true`。
 * `updatedAt` / `updatedBy` 只来自**租户键**的 meta——全局键开启但本租户键从未写入时，
 * 会出现 `active=true` 且两个元数据为 `null`，这不是数据缺失，界面必须如实呈现。
 * 与平台级快照的区别：本端点不返回实验账本。
 */
export function getTenantAiControls(): Promise<KillSwitchState> {
  return get<KillSwitchState>('/admin/ai/controls/tenant');
}

/**
 * 全量赋值当前登录租户的 Kill Switch 键（契约 2.6.0 `updateTenantAiControls`）。
 * 请求体与平台级端点共用 `UpdateAiControlsRequest`：只有 `killSwitchActive` 一个布尔字段。
 * **不影响平台级一键全停键**；PUT 回显的仍是 OR 叠加后的有效状态，所以全局键开启时
 * 写入 `false` 拿到的回显可能依旧是 `active=true`——那不是写入失败，调用方不能据此宣称已恢复服务。
 */
export function updateTenantAiControls(request: UpdateAiControlsRequest): Promise<KillSwitchState> {
  return put<KillSwitchState>('/admin/ai/controls/tenant', request);
}

/** 只返回**存在覆盖行**的用户；不在列表里表示不设上限，而不是「查不到这个人」。 */
export function listAiQuotas(query: AiQuotaListQuery): Promise<AiQuotaListData> {
  return get<AiQuotaListData>('/admin/ai/quotas', { cursor: query.cursor, limit: query.limit });
}

/**
 * 全量替换某用户的日配额：两个字段都必须出现，`null` 表示该维度不设上限，
 * 两项都为 `null` 即清除覆盖行。目标用户不存在返回 404 `USR-NOT_FOUND-001`。
 */
export function updateAiQuota(userId: string, request: UpdateAiQuotaRequest): Promise<AiQuota> {
  return put<AiQuota>(`/admin/ai/quotas/${encodeURIComponent(userId)}`, request);
}

/**
 * 审计事件查询。排序 `occurred_at DESC, id DESC`，keyset 分页且不返回总数；
 * 三个过滤条件是任意组合，未给条件时走时间窗（默认最近 24 小时，最长 31 天）。
 */
export function listAuditEvents(query: AuditEventQuery): Promise<AuditEventListData> {
  return get<AuditEventListData>('/admin/audit/events', {
    traceId: query.traceId,
    userId: query.userId,
    eventType: query.eventType,
    from: query.from,
    to: query.to,
    cursor: query.cursor,
    limit: query.limit,
  });
}

/** 用量汇总只提供计数与均值，不返回 p50/p95，也不承诺延迟上界。 */
export function getAiUsage(query: AiUsageQuery): Promise<UsageSummary> {
  return get<UsageSummary>('/admin/ai/usage', {
    from: query.from,
    to: query.to,
    bucket: query.bucket,
    topUsers: query.topUsers,
  });
}

/** 可观测性入口：`langfuseBaseUrl` 空串 = 未部署 LangFuse，调用方不得渲染追踪链接。 */
export function getObservability(): Promise<ObservabilityData> {
  return get<ObservabilityData>('/admin/observability');
}
