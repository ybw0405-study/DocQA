/**
 * 契约 `openapi-java.yaml` 2.5.0 管理面（S9 交付的 6 个 ADMIN 操作）schema 的逐字段镜像，
 * 字段名不做本地化改名。镜像与契约是否仍然一致由 `tests/contract-drift.spec.ts` 当场对账，不靠本注释。
 *
 * 三条贯穿全片的口径：
 * 1. 「键恒在、值可为 null」——后端这些 DTO 一律是 Java record 且仓库内没有排除 null 的 Jackson 装配，
 *    因此契约里未列入 `required` 的可空字段在这里写成 TS 必填 `| null`，消费方不必到处写可选链。
 * 2. 列表是 **keyset**（`limit` + `cursor` → `{items, nextCursor, limit}`），契约有意不返回 `total`，
 *    前端不得显示「共 N 条」，也不得用已加载行数冒充总数。
 * 3. 上限不可知 ≠ 0：`limitsKnown=false` 时必须显示「上限未发布」，把不可知渲染成 `0` 是假数据。
 */

/** 契约 `AiQuota` 与 `UpdateAiQuotaRequest` 的两个上限共用同一组数值约束（`minimum`/`maximum`）。 */
export const QUOTA_LIMIT_MIN = 1;

export const QUOTA_LIMIT_MAX = 4294967295;

/** 契约参数 `QuotaListLimit` 的上限，与 `AiQuotaListData.items` 的 `maxItems` 同值。 */
export const QUOTA_LIST_LIMIT_MAX = 200;

/** 契约参数 `AuditQueryLimit` 的上限，与 `AuditEventListData.items` 的 `maxItems` 同值。 */
export const AUDIT_QUERY_LIMIT_MAX = 500;

/** 契约参数 `UsageTopUsers` 的上限，与 `UsageSummary.topUsers` 的 `maxItems` 同值。 */
export const USAGE_TOP_USERS_MAX = 50;

/**
 * 审计查询表单的本地预拦：契约把 `traceId`/`eventType` 的取值形态写成 pattern，
 * 越界只会换回一个 400 `COM-VALIDATION-001`，因此提交前按同一 pattern 判定。
 * `AuditTraceId` 与 `AuditEventType` 两个查询参数、以及读侧 `AuditEventItem` 的同名字段
 * 共用这一对常量——契约的三条 pattern 逐字节相同，复制两份就会失去对账意义。
 */
export const AUDIT_TRACE_ID_PATTERN = /^[0-9a-f]{32}$/;

export const AUDIT_EVENT_TYPE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;

/** 事件写入端。它来自 `source` 字段而不是查询条件：管理面不提供按端过滤的参数。 */
export type AuditSource = 'JAVA' | 'PYTHON';

export const AUDIT_SOURCES: readonly AuditSource[] = ['JAVA', 'PYTHON'];

export type AuditSeverity = 'INFO' | 'WARN' | 'ERROR';

export const AUDIT_SEVERITIES: readonly AuditSeverity[] = ['INFO', 'WARN', 'ERROR'];

/** `agent_execution.status` 的四值分布；`BUDGET_EXHAUSTED` 单列，不得被并进「故障」。 */
export type UsageStatus = 'RUNNING' | 'SUCCESS' | 'BUDGET_EXHAUSTED' | 'FAILED';

export const USAGE_STATUSES: readonly UsageStatus[] = ['RUNNING', 'SUCCESS', 'BUDGET_EXHAUSTED', 'FAILED'];

/** 聚合粒度。契约当前只有 `day`，传入其它值返回 400 而不是被忽略，因此类型收窄成一个值。 */
export type UsageBucket = 'day';

/**
 * Kill Switch 快照。`active` 的事实来源是 Redis 键的存在性，经网关读取并受 **30 秒本地缓存**影响，
 * 所以 PUT 成功后界面回显的是「重读到的值」，不能宣称已经瞬时生效。
 */
export interface KillSwitchState {
  /** 为 `true` 时新的问答返回 503 `AGT-UPSTREAM-003`；登录、上传、提醒与审计查询不受影响。 */
  active: boolean;
  /** 最近一次写入时刻；元数据缺失时为 null，**不改变 `active` 的判读**（S9-L2 的既有边界）。 */
  updatedAt: string | null;
  /** 最近一次写入的管理员 userId，只来自已验签 JWT 的 `sub`。 */
  updatedBy: string | null;
}

/**
 * 本轮实验账本快照，只读：计数没有 TTL，契约上也不提供在线重置入口。
 * 上限由 Python 启动时发布、网关只负责回显，所以离线部署或 Python 未启动时 `limitsKnown` 为 false。
 */
export interface ExperimentBudgetState {
  callsUsed: number;
  tokensUsed: number;
  /** `limitsKnown=false` 时为 null，表示「上限未发布」而不是「上限为 0」。 */
  callLimit: number | null;
  tokenLimit: number | null;
  limitsKnown: boolean;
}

/** 治理状态的读侧聚合；Kill Switch 与实验账本互相独立，一个不可读不代表另一个不可读。 */
export interface AdminAiControls {
  killSwitch: KillSwitchState;
  experimentBudget: ExperimentBudgetState;
}

/**
 * 契约 `UpdateAiControlsRequest`（`additionalProperties: false`）：全量只有一个字段。
 * 契约上**没有** `version` 乐观锁、**没有** `IdempotencyKey` 参数（D-197 的本轮解析结论），
 * 因此这是「按值幂等」——重复设置同一状态无副作用；并发覆盖在服务端是 last-write-wins，界面检测不到。
 */
export interface UpdateAiControlsRequest {
  killSwitchActive: boolean;
}

/**
 * 一个用户的日配额覆盖。行不存在表示不设上限；两个维度各自为空表示该维度不设上限。
 * `username` 实时取自 `app_user`，仅用于展示、不作为写入键；写入键只有 path 上的 `userId`。
 */
export interface AiQuota {
  userId: string;
  username: string | null;
  /** UTC 自然日的问答次数硬闸。 */
  dailyQuestionLimit: number | null;
  /** UTC 自然日的 token 软闸。 */
  dailyTokenLimit: number | null;
  updatedAt: string;
  /** 最近一次写入的管理员 userId。 */
  updatedBy: string | null;
}

/**
 * 契约 `UpdateAiQuotaRequest`（`additionalProperties: false`）是**全量替换**：
 * 两个字段都必须出现、可显式为 `null`；只省略其中一个不是「清除」，而是 400 `COM-VALIDATION-001`。
 * 因此编辑表单提交时必须同时带上另一维度的当前值。
 */
export interface UpdateAiQuotaRequest {
  dailyQuestionLimit: number | null;
  dailyTokenLimit: number | null;
}

/**
 * 一条已落库的审计事件。`payload` 是写入时经过 17 键白名单与 PII 清洗后的 `payload_redacted`，
 * 不是原始输入；本对象不携带任何可修改标记，契约上也没有任何写操作面。
 */
export interface AuditEventItem {
  eventId: string;
  /** W3C trace id，与问答响应信封里的 `traceId` 同形，可用它把一次问答的审计时间线串起来。 */
  traceId: string;
  /** 事件归属用户；由 Java 入库时注入，不接受上游伪造值。 */
  userId: string;
  eventType: string;
  agentName: string | null;
  source: AuditSource;
  severity: AuditSeverity;
  /**
   * 已清洗的治理载荷，键集开放（契约 `additionalProperties: true`）。白名单是**展示**白名单，
   * 不能反过来当校验：未知键必须显示原始键名，不能丢弃。
   */
  payload: Record<string, unknown>;
  occurredAt: string;
}

/**
 * 时间窗内的计数与均值。只提供计数与均值，**不提供分位数**；
 * `avgLatencyMs` 是服务端记录的历史均值，展示时必须带「非压测结论」标注（D-200）。
 */
export interface UsageTotals {
  /** 问答次数，含缓存命中。 */
  questions: number;
  success: number;
  /** 单列统计 `status='BUDGET_EXHAUSTED'`，就是为了不被界面合并成「故障」。 */
  budgetExhausted: number;
  /** 只统计 `status='FAILED'`。 */
  failed: number;
  tokens: number;
  toolCalls: number;
  rounds: number;
  cacheHits: number;
  /** 口径 `AVG(latency_ms)`；窗口内无执行时为 null，不是 0。 */
  avgLatencyMs: number | null;
}

/** 按 UTC 自然日聚合的序列点。 */
export interface UsageDayPoint {
  date: string;
  questions: number;
  tokens: number;
  failed: number;
}

export interface UsageStatusCount {
  status: UsageStatus;
  count: number;
}

/** Top 用户条目。排序为 `questions DESC, tokens DESC, userId ASC`，排序键完整因此同分顺序稳定。 */
export interface UsageTopUser {
  userId: string;
  username: string | null;
  questions: number;
  tokens: number;
}

/**
 * 用量看板的数据体。三个数组的上限来自请求参数（`byDay` ≤ 窗口天数、`byStatus` 恰好四值、
 * `topUsers` 受 `topUsers` 参数约束），不是服务端截断的结果。
 */
export interface UsageSummary {
  from: string;
  to: string;
  bucket: UsageBucket;
  totals: UsageTotals;
  byDay: UsageDayPoint[];
  byStatus: UsageStatusCount[];
  topUsers: UsageTopUser[];
}

/** 配额列表的 keyset 信封；契约有意不返回 `total`。 */
export interface AiQuotaListData {
  items: AiQuota[];
  /** 下一页游标；null 表示已是最后一页。 */
  nextCursor: string | null;
  /** 本次实际生效的每页条数，由服务端回显而不是前端假设。 */
  limit: number;
}

/** 审计查询的 keyset 信封；同样的口径，不返回总数。 */
export interface AuditEventListData {
  items: AuditEventItem[];
  nextCursor: string | null;
  limit: number;
}

/**
 * 可观测性入口（契约 2.8.0）。`langfuseBaseUrl` 为空串表示未部署 LangFuse：
 * 审计页 traceId 保持纯文本；非空时运行时拼 `{base}/trace/{traceId}`，地址不烘焙进产物。
 */
export interface ObservabilityData {
  langfuseBaseUrl: string;
}
