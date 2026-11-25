/**
 * 契约 `openapi-java.yaml` 2.7.0 模型连接管理面 schema 的逐字段镜像，字段名不做本地化改名。
 * 镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账。
 *
 * 两条贯穿口径：
 * 1. 「键恒在、值可为 null」——后端 DTO 是 Java record 且不排除 null，因此契约里可空的字段
 *    在这里写成 TS 必填 `| null`，消费方不必到处写可选链。
 * 2. **凭据只进不出**：读侧只有 `hasCredential` 布尔，没有密文、keyId 或明文 Key 字段；
 *    明文 Key 只作为 `AuthorizeModelConnectionRequest.secret` 出现一次，且只存组件本地 state。
 */

/** 契约 `CreateModelConnectionRequest.name` 的 maxLength。 */
export const MODEL_CONNECTION_NAME_MAX_LENGTH = 64;

/** 契约 `CreateModelConnectionRequest.baseUrl` 的 maxLength。 */
export const MODEL_CONNECTION_BASE_URL_MAX_LENGTH = 512;

/** 契约 `CreateModelConnectionRequest.model` 的 maxLength。 */
export const MODEL_CONNECTION_MODEL_MAX_LENGTH = 64;

/** 契约 `AuthorizeModelConnectionRequest.secret` 的 maxLength。 */
export const MODEL_CONNECTION_SECRET_MAX_LENGTH = 512;

/** 契约 `presetId` 的 pattern；自定义端点不传该字段。 */
export const MODEL_CONNECTION_PRESET_ID_PATTERN = /^[a-z0-9][a-z0-9-]{0,31}$/;

/** 契约 `ModelConnectionListData.items` 的 maxItems，也是服务端每租户连接数上限。 */
export const MODEL_CONNECTIONS_MAX = 20;

/** 契约 `ModelProbeData.models` 的 maxItems。 */
export const PROBE_MODELS_MAX = 200;

/** 契约 `ModelProbeData.models[]` 的 maxLength。 */
export const PROBE_MODEL_ID_MAX_LENGTH = 128;

/** 契约 `ModelProbeData.detail` 的 maxLength。 */
export const PROBE_DETAIL_MAX_LENGTH = 200;

/**
 * 探测失败原因（契约 `ModelProbeFailure`）。与 HTTP 状态码无关：探测失败仍返回 200，
 * 由本枚举表达结论。`CREDENTIAL_UNREADABLE` 的处置是重新录入 Key，而不是重试。
 */
export type ModelProbeFailure =
  | 'HTTP_4XX'
  | 'HTTP_5XX'
  | 'UNREACHABLE'
  | 'TIMEOUT'
  | 'NOT_OPENAI_COMPATIBLE'
  | 'EGRESS_BLOCKED'
  | 'CREDENTIAL_MISSING'
  | 'CREDENTIAL_UNREADABLE';

export const MODEL_PROBE_FAILURES: readonly ModelProbeFailure[] = [
  'HTTP_4XX',
  'HTTP_5XX',
  'UNREACHABLE',
  'TIMEOUT',
  'NOT_OPENAI_COMPATIBLE',
  'EGRESS_BLOCKED',
  'CREDENTIAL_MISSING',
  'CREDENTIAL_UNREADABLE',
];

/** `ModelConnection.probeStatus` 的内联枚举：`UNKNOWN` 是「从未探测」，不是「探测失败」。 */
export type ProbeStatus = 'UNKNOWN' | 'OK' | 'FAILED';

export const PROBE_STATUSES: readonly ProbeStatus[] = ['UNKNOWN', 'OK', 'FAILED'];

/** 读侧形态：没有任何凭据材料，`hasCredential` 是凭据状态的唯一对外表达。 */
export interface ModelConnection {
  id: string;
  name: string;
  presetId: string | null;
  baseUrl: string;
  model: string;
  enabled: boolean;
  active: boolean;
  hasCredential: boolean;
  probeStatus: ProbeStatus;
  lastProbedAt: string | null;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

/** 连接是小集合（每租户上限 20 条），契约不分页、不返回 total。 */
export interface ModelConnectionListData {
  items: ModelConnection[];
}

/**
 * 建立或更新连接。刻意不含 API Key：凭据走独立的 credential 端点。
 * `presetId` / `enabled` 在契约里未列入 required，因此这里是可选字段。
 */
export interface CreateModelConnectionRequest {
  name: string;
  presetId?: string | null;
  baseUrl: string;
  model: string;
  enabled?: boolean | null;
}

/** 录入 API Key。明文只在这一个请求体里出现，响应与日志都不含它。 */
export interface AuthorizeModelConnectionRequest {
  secret: string;
}

/** 切换生效状态；`active` 必须显式出现且非 null。 */
export interface UpdateActivationRequest {
  active: boolean;
}

/** 探测结论：成功时 failureReason/detail 为 null，失败时 models 为空数组。 */
export interface ModelProbeData {
  ok: boolean;
  models: string[];
  failureReason: ModelProbeFailure | null;
  detail: string | null;
}

/** 预设分组（契约 `ModelPresetGroup`），界面按此分节展示。 */
export type ModelPresetGroup = 'recommended' | 'domestic' | 'overseas' | 'local';

export const MODEL_PRESET_GROUPS: readonly ModelPresetGroup[] = ['recommended', 'domestic', 'overseas', 'local'];

/** 分组的中文标题只存在于界面，契约只发布枚举值。 */
export const MODEL_PRESET_GROUP_LABELS: Record<ModelPresetGroup, string> = {
  recommended: '常用推荐',
  domestic: '国内其他',
  overseas: '海外服务',
  local: '本地 / 自托管',
};

/**
 * 一个厂商预设：公开端点与常见模型别名，**不含任何密钥**。
 * 由服务端下发（前端红线禁止生产源码出现绝对服务地址），端点与模型名在向导里都可编辑。
 */
export interface ModelPreset {
  id: string;
  vendor: string;
  mark: string;
  label: string;
  model: string;
  models: string[];
  baseUrl: string;
  keyUrl: string;
  keyHint: string;
  group: ModelPresetGroup;
  tag: string | null;
  note: string;
  noKey: boolean;
  needsServerOptIn: boolean;
}

/** 预设目录；纯静态公开数据，不分页。 */
export interface ModelPresetListData {
  items: ModelPreset[];
}
