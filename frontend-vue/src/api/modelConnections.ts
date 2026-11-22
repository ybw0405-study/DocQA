import type {
  AuthorizeModelConnectionRequest,
  CreateModelConnectionRequest,
  ModelConnection,
  ModelConnectionListData,
  ModelPresetListData,
  ModelProbeData,
  UpdateActivationRequest,
} from '../types/modelConnections';
import { deleteNoContent, get, post, put } from './http';

/**
 * 模型连接管理面七个操作的唯一出口（契约 2.7.0 的 `Admin` tag）。
 *
 * **这里不构造任何请求头**：七个操作的参数集合里都没有 `IdempotencyKey` parameter（D-197），
 * 与 `api/admin.ts` 的八个操作同一裁决。共享拦截器仍会给非 GET 补一个随机幂等键头，服务端不消费它。
 * 创建按租户内 `name` upsert、凭据与生效状态都是覆盖式写入，因此按值幂等；
 * 并发覆盖在服务端是 last-write-wins，界面不得假装能拦住别人的写入。
 *
 * `tenantId` 一律由服务端取自已验签 JWT，所以这些方法都没有租户参数。
 * 厂商预设目录也由服务端下发：前端红线禁止生产源码出现绝对服务地址。
 */

const BASE = '/admin/ai/model-connections';

/** 厂商预设目录：公开端点与模型别名，不含任何密钥；不读配置、不出网。 */
export function listModelConnectionPresets(): Promise<ModelPresetListData> {
  return get<ModelPresetListData>(`${BASE}/presets`);
}

/** 本租户的连接清单；生效与否由每项的 `active` 表达，至多一条为真。 */
export function listModelConnections(): Promise<ModelConnectionListData> {
  return get<ModelConnectionListData>(BASE);
}

/**
 * 建立或更新连接（按租户内 name upsert）。同名重复提交收敛到同一行且**不改变生效状态**。
 * 请求体不含 API Key；baseUrl 不满足出网策略或连接数达上限时返回 400 `COM-VALIDATION-001`。
 */
export function upsertModelConnection(request: CreateModelConnectionRequest): Promise<ModelConnection> {
  return post<ModelConnection>(BASE, request);
}

/**
 * 录入或替换 API Key。明文只在这一个请求体里出现，服务端立即封装成密文落库；
 * 响应只回 `hasCredential`，任何形式的密钥材料都不回显。
 * 主密钥未配置时返回 500 `COM-INTERNAL-001`，`detail` 会说明要配置哪个变量。
 */
export function authorizeModelConnection(
  connectionId: string,
  request: AuthorizeModelConnectionRequest,
): Promise<ModelConnection> {
  return put<ModelConnection>(`${BASE}/${encodeURIComponent(connectionId)}/credential`, request);
}

/**
 * 测试连通性：服务端解密后真实出网 `GET {baseUrl}/models`。
 * **探测失败也返回 200**，由 `ok` 与 `failureReason` 表达结论，调用方不得把非 2xx 之外的
 * 失败当成 HTTP 错误处理，也不得把 `ok=false` 呈现成「请求没发出去」。
 */
export function probeModelConnection(connectionId: string): Promise<ModelProbeData> {
  return post<ModelProbeData>(`${BASE}/${encodeURIComponent(connectionId)}/probe`);
}

/**
 * 设为生效或取消生效。`active=true` 时服务端在同一事务里先清掉本租户其它生效标记再设本条；
 * 对已停用的连接设为生效返回 400。`active=false` 只取消这一条，不影响其它连接。
 */
export function updateModelConnectionActivation(
  connectionId: string,
  request: UpdateActivationRequest,
): Promise<ModelConnection> {
  return put<ModelConnection>(`${BASE}/${encodeURIComponent(connectionId)}/activation`, request);
}

/** 删除连接即删除其密文，不留副本；删掉的若是生效连接，数据面回退到 env 配置的 provider。 */
export function deleteModelConnection(connectionId: string): Promise<void> {
  return deleteNoContent(`${BASE}/${encodeURIComponent(connectionId)}`);
}
