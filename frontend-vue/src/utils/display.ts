import type { UserRole, UserStatus } from '../types/api';
import type { DocumentStatus, VectorConsistency } from '../types/documents';
import type { BudgetStatus, MessageRole } from '../types/conversations';
import type { ReminderKind, ReminderStatus, ScheduleImportAction } from '../types/reminders';
import type { AuditSeverity, UsageStatus } from '../types/admin';
import type { UploadPhase } from '../stores/uploads';

export const STATUS_LABELS: Readonly<Record<DocumentStatus, string>> = {
  UPLOADED: '已上传',
  PARSING: '解析中',
  INDEXED: '已可检索',
  FAILED: '解析失败',
  DEAD: '已进死信',
};

/**
 * 契约 `VectorConsistency` 的展示文案。`UNKNOWN` 是「无从对照」而不是「有问题」，
 * 文案必须不带告警语气，否则每个历史文档都会被读成故障。
 */
export const VECTOR_CONSISTENCY_LABELS: Readonly<Record<VectorConsistency, string>> = {
  MATCHED: '行数一致',
  DIVERGED: '行数待收敛',
  UNKNOWN: '未对照',
};

export const PHASE_LABELS: Readonly<Record<UploadPhase, string>> = {
  hashing: '计算校验值',
  uploading: '上传分片',
  indexing: '等待解析与索引',
  done: '已可检索',
  failed: '失败',
  canceled: '已取消',
  stale: '仍在后台处理',
};

/** 契约 `BudgetStatus` 的展示文案；低置信度与预算耗尽都是 HTTP 200，只有这里能区分。 */
export const BUDGET_LABELS: Readonly<Record<BudgetStatus, string>> = {
  OK: '正常回答',
  BUDGET_EXHAUSTED: '预算耗尽',
  LOW_CONFIDENCE: '证据不足',
  PENDING_APPROVAL: '等待审批',
};

/**
 * `LOW_CONFIDENCE` 徽章的补充说明。只有徽章一个词时「证据不足」会被读成「什么都没查到」，
 * 而实际形态是检索有结果、匹配度不够——这句话只能由界面补，协议里没有这个字段。
 */
export const LOW_CONFIDENCE_NOTE = '检索到了内容，但证据与问题的匹配不足。';

/**
 * 引用 `score` 的界面口径（，承接 `` /）。协议里 `score` 一物两义：
 * 混合检索下只被词法通道命中的块恒为 0.0，说成「相关度 0.0%」是把「向量没匹配上」冒充成「完全不相关」。
 * 这里只改**说法**，`score` 的取值与排序一律不动，因此不构成  的闭合。
 */
export const CITATION_LEXICAL_ONLY_LABEL = '词法召回（向量未命中）';

/** 百分比只在引用块头部标注一次口径，逐条重复会把五条引用变成五段说明书。 */
export const CITATION_SCORE_NOTE = '相关度百分比是本次检索的相对得分，不是答案置信度。';

export const ROLE_LABELS: Readonly<Record<MessageRole, string>> = {
  user: '我问',
  assistant: '助手',
};

/**
 * 契约 `UserRole` 的展示文案（账户页与管理端用户列表共用同一处）。
 * 这里的「管理员」是**角色**而不是姓名，界面上不得把它读成某个具体的人。
 */
export const USER_ROLE_LABELS: Readonly<Record<UserRole, string>> = {
  USER: '普通用户',
  ADMIN: '管理员',
};

/** 契约 `UserStatus` 的展示文案；`DISABLED` 是「账号存在但停用」，不能渲染成「查不到这个人」。 */
export const USER_STATUS_LABELS: Readonly<Record<UserStatus, string>> = {
  ACTIVE: '正常',
  DISABLED: '已停用',
};

/**
 * 身份三块各一条口径注脚：契约对这三个字段的陈旧性承诺**并不相同**，
 * `/auth/me` 明确「账号状态必须实时、角色允许短时陈旧（上限是访问令牌有效期）」，
 * 所以三句话必须分开说，合并成一句「以服务端为准」就是把差异抹掉了。
 */
export const IDENTITY_STATUS_NOTE = '账号状态是本次读到的服务端当前值：已停用的账号即刻失去登录能力。';
export const IDENTITY_ROLES_NOTE = '角色是签发本次访问令牌时写入的快照，允许短时陈旧；收权由服务端逐请求判定，这里的显示不构成即时权限。';
export const IDENTITY_TOKEN_TTL_NOTE = '剩余有效期是本次读取时的快照，界面不逐秒倒数；它也正是上面那份角色快照可能陈旧的时间窗。';

/**
 * 改密成功后落到登录页时的提示：契约在服务端吊销了该用户全部刷新令牌家族，
 * 因此这句话描述的是**已发生的后果**，不是「建议重新登录」。
 */
export const PASSWORD_CHANGED_NOTICE = '口令已修改，此前的登录凭据已全部失效，请用新口令重新登录。';

/** 契约 `ReminderKind` 的展示文案；`DEADLINE` 由服务端映射成一条一次性规则，`COURSE` 只能由课表导入产生。 */
export const REMINDER_KIND_LABELS: Readonly<Record<ReminderKind, string>> = {
  DEADLINE: '截止提醒',
  CUSTOM: '重复提醒',
  COURSE: '课程提醒',
};

/** 契约 `ReminderStatus` 的展示文案；2.3.0 起只有两个终态把 `nextTriggerAt` 置空，暂停保留原值。 */
export const REMINDER_STATUS_LABELS: Readonly<Record<ReminderStatus, string>> = {
  ACTIVE: '进行中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

/**
 * 状态机按钮的动词，取自目标状态：`ACTIVE` 在界面上读作「恢复」而不是「设为进行中」。
 * `COMPLETED` 与 `CANCELLED` 是终态（，无回退边），这两个动词必须与确认框里的
 * 「不可回退」同屏出现，否则一次误点就要重建提醒才能挽回。
 */
export const REMINDER_ACTION_LABELS: Readonly<Record<ReminderStatus, string>> = {
  ACTIVE: '恢复',
  PAUSED: '暂停',
  COMPLETED: '完成',
  CANCELLED: '取消',
};

/**
 * 契约 `ScheduleImportAction` 的展示文案。`STALE` 是「文件比库内更旧，跳过这一个事件」，
 * 既不报错也不回滚整批，文案不能带失败语气，否则一次部分跳过的导入会被读成整批没成功。
 */
export const SCHEDULE_IMPORT_ACTION_LABELS: Readonly<Record<ScheduleImportAction, string>> = {
  CREATE: '新建',
  UPDATE: '更新',
  UNCHANGED: '无变化',
  STALE: '旧版忽略',
};

/**
 * 审计 `payload` 的「展示」标签表。键集镜像两处服务端写入白名单——
 * `audit-service` 的 `AuditPayloadSanitizer.ALLOWED_KEYS` 与 Python 的 `governance/audit.ALLOWED_PAYLOAD_KEYS`，
 * 两边逐键相同（同步过一次）。这张表只决定「有没有中文名」：契约上 `payload` 是
 * `additionalProperties: true` 的开放对象，未知键仍然按原始键名渲染，所以它不构成一份校验清单——
 * 拿它当校验就会把服务端将来新增的治理字段静默吞掉，而那是 US-08 可追溯性的一部分。
 */
export const AUDIT_PAYLOAD_LABELS: Readonly<Record<string, string>> = {
  durationMs: '耗时（毫秒）',
  tokenCount: 'token 数',
  toolName: '工具名',
  toolSource: '工具来源',
  chunkCount: '检索块数',
  topScore: '最高匹配分',
  round: '编排轮次',
  confidence: '置信度',
  budgetStatus: '预算状态',
  errorCode: '错误码',
  maskingVersion: '脱敏版本',
  piiCategories: 'PII 类别',
  piiCount: 'PII 命中数',
  model: '模型',
  cacheHit: '缓存命中',
  historyTruncated: '历史已截断',
  targetUserId: '被治理用户',
};

/** 契约 `AuditSeverity` 的展示文案；三值都是事件自带的，界面不重新判级。 */
export const AUDIT_SEVERITY_LABELS: Readonly<Record<AuditSeverity, string>> = {
  INFO: '信息',
  WARN: '告警',
  ERROR: '错误',
};

/** 用量看板的执行状态文案。`BUDGET_EXHAUSTED` 刻意不与 `FAILED` 合并：额度闸拦住和执行失败是两回事。 */
export const USAGE_STATUS_LABELS: Readonly<Record<UsageStatus, string>> = {
  RUNNING: '执行中',
  SUCCESS: '成功',
  BUDGET_EXHAUSTED: '预算耗尽',
  FAILED: '失败',
};

/** `avgLatencyMs` 的口径标注：本项目的性能结论一律是「未压测」，均值缺这句就会被读成性能结论。 */
export const LATENCY_NOTE = '平均延迟是服务端记录的历史均值 · 非压测结论。';

/**
 * 空态文案单点：新增空态必须先在这里登记，视图里的 `<EmptyState>` 只允许引用本表的键，
 * 由 `src/components/ui/EmptyState.spec.ts` 机判。「筛选后为空」与「一条都没有」给出的是两种行动指引，
 * 因此刻意分成两条文案，不允许合并成一句「暂无数据」。
 */
export const EMPTY_STATE_TEXT = {
  documentsFiltered: '当前筛选条件下没有文档。',
  documentsNone: '还没有文档，先上传一份讲义吧。',
  askNoSessions: '还没有会话，先新建一个。',
  askNoCurrent: '选择一个会话后可以回看历史消息。',
  askNoMessages: '这个会话还没有消息。',
  askNoIndexedDocs: '没有已可检索的文档，请先到文档页上传并等待索引完成。',
  remindersFiltered: '这个筛选下还没有提醒，可以在下面创建一条。',
  notificationsUnread: '没有未读通知。',
  notificationsNone: '还没有通知：提醒触发后才会在这里留下一条记录。',
  uploadsNone: '还没有上传任务。',
  usersFiltered: '当前筛选条件下没有账号，可以换一个条件或清空筛选再查一次。',
  usersNone: '还没有可管理的账号：契约未提供建号接口，账号只能由用户自行注册产生。',
  quotasNone: '还没有用户设置个性化日配额：缺行表示不设上限，不表示查不到这个人。',
  auditWindow: '默认时间窗（最近 24 小时）内没有审计事件，可以放宽时刻条件再查一次。',
  auditFiltered: '当前查询条件下没有审计事件。',
  usageNone: '所选窗口内没有执行记录。',
  modelConnectionsNone: '还没有模型连接：问答此时使用部署配置的模型，新建一条即可在界面里切换。',
  memoriesNone: '这个用户还没有长期记忆：记忆只由本人在问答里用「记住：…」显式写入。',
} as const;

export function formatBytes(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${index === 0 ? String(value) : value.toFixed(1)} ${units[index]}`;
}

export function formatDateTime(iso: string | null): string {
  if (iso === null) {
    return '—';
  }
  return new Date(iso).toLocaleString('zh-CN', { hour12: false });
}

/**
 * 提前量的可读文案。整小时说成小时，其余（含 90 这类非整点）一律回到分钟，
 * 避免出现「1.5 小时」这种要心算的表述；0 单独说明，否则「提前 0 分钟」读起来像故障。
 */
export function formatLeadMinutes(minutes: number): string {
  if (minutes <= 0) {
    return '按上课时刻提醒';
  }
  if (minutes % 60 === 0) {
    return `提前 ${String(minutes / 60)} 小时`;
  }
  return `提前 ${String(minutes)} 分钟`;
}

/**
 * 课表行的上课时刻 = 触发时刻 + 提前量。契约的 `plannedTriggerAt` / `nextTriggerAt`
 * 都是「上课时刻 − lead_minutes」的提前提醒时刻，界面要说「上课」就得换算一次。
 * 这是**纯显示层**换算：结果不参与任何身份判定、也不回写任何字段；
 * 非课程行的 `leadMinutes` 恒为 0，因此对该函数是恒等变换。无法解析的输入返回 null 而不是猜一个时刻。
 */
export function classStartAt(triggerAt: string | null, leadMinutes: number): string | null {
  if (triggerAt === null) {
    return null;
  }
  const parsed = Date.parse(triggerAt);
  if (Number.isNaN(parsed)) {
    return null;
  }
  return new Date(parsed + leadMinutes * 60_000).toISOString();
}

/** 契约里 `confidence` 与 `score` 都是 0–1 的 double，展示层固定小数位避免抖动宽度。 */
export function formatRatio(value: number): string {
  return value.toFixed(2);
}

export function formatPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}
