# 数据、存储与向量设计

## 1. 范围与设计原则

本文给出可以直接指导编码的 MySQL 8.0 DDL、Redis Key 设计和 PGVector 集合设计。MySQL 只承载业务控制面与审计，向量由 Python 写入 PostgreSQL + PGVector，原始文件由 MinIO 保存，Redis 只保存短生命周期状态。

本期设计 **9 张核心业务表**：`document`、`chunk`、`document_tag`、`document_tag_link`、`conversation`、`message`、`reminder`、`audit_event`、`agent_execution`。审计 outbox 复用 `agent_execution.audit_outbox`，解析 outbox 由 `document` 状态与对账重投实现，均不新增表。`document_tag` 与 `document_tag_link`（见 §2.14–§2.15）属文档域补全新增，只服务标签过滤与展示，不引入新的服务边界，仍由 `document-service` 独占读写。

身份控制面另有 **3 张表**：`app_user`、`app_user_role`、`refresh_token`（见 §2.11–§2.13）。它们与业务表同库不同域，只有 `user-service` 读写；`password_hash` 与 `token_hash` 属于凭据材料，只允许作为摘要存在，任何 API 响应、日志和错误详情都不得外发。口令摘要用 BCrypt（成本因子 12），刷新令牌用不透明随机串并在库中只存 SHA-256 摘要。生产化时应把身份域拆到独立的 user schema 或外部 IdP，不能把用户凭据长期塞进文档业务库。

所有时间字段存 UTC，数据库会话统一 `time_zone = '+00:00'`。主键使用应用生成的 UUIDv7（`CHAR(36)`），便于分库前保持可排序和可幂等；数据库不做跨服务外键，只有同一服务内的强拥有关系使用外键。

提醒域另有 **1 张表** `reminder_trigger`（见 §2.16，S4 新增，迁移 `V8__reminder_trigger.sql`），MySQL 因此共 **13 张表**。它同样只由 `reminder-service` 读写，一行等于「一次已落库的触发」，一张表同时承载 US-07 的**触发历史**与**站内通知**两个面：前者是事实列（`planned_trigger_at` / `delivered_at`），后者只有 `read_at` 一个可变字段。它不引入新的服务边界，也不引入新的 MQ topic。

## 2. MySQL 8.0 DDL

### 2.1 建库与公共设置

```sql
CREATE DATABASE IF NOT EXISTS docqa
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE docqa;
SET time_zone = '+00:00';
```

### 2.2 document

```sql
CREATE TABLE document (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_name           VARCHAR(255) NOT NULL,
  title               VARCHAR(120) NULL,
  content_type        VARCHAR(127) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  size_bytes          BIGINT UNSIGNED NOT NULL,
  md5                 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_key          VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UPLOADED',
  retry_count         TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_retry           TINYINT UNSIGNED NOT NULL DEFAULT 3,
  error_code          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  error_message       VARCHAR(512) NULL,
  index_version       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'v1',
  pending_index_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  vector_rows         INT UNSIGNED NULL,
  vector_confirmed_at DATETIME(3) NULL,
  parsing_started_at  DATETIME(3) NULL,
  indexed_at          DATETIME(3) NULL,
  deleted_at          DATETIME(3) NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_user_md5 (user_id, md5)
    COMMENT '秒传查询、并发重复上传的数据库唯一兜底',
  UNIQUE KEY uk_document_object_key (object_key)
    COMMENT 'MinIO 对象定位、清理任务与重复对象检查',
  KEY idx_document_status_parse (status, parsing_started_at)
    COMMENT '对账任务扫描长时间 PARSING 的文档',
  KEY idx_document_user_created (user_id, created_at)
    COMMENT '学生文档列表与按用户清理',
  KEY idx_document_deleted_vector (deleted_at, vector_confirmed_at)
    COMMENT '对账扫描：已软删除但向量侧尚未确认的文档，补投 vector-change-requested',
  CONSTRAINT chk_document_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
  CONSTRAINT chk_document_retry CHECK (retry_count <= max_retry),
  CONSTRAINT chk_document_status CHECK (
    status IN ('UPLOADED', 'PARSING', 'INDEXED', 'FAILED', 'DEAD')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='文档控制面元数据；状态机与重试计数由 document-service 维护';
```

说明：

- `id` 同时作为上传会话标识返回给前端（API 中的 `uploadId`），不额外引入上传会话表；未完成的上传由 Redis TTL 和定时清理处理。
- 解析任务不新增 outbox 表：`status=PARSING` 与 `parsing_started_at` 是持久化事实，提交后投递 `parse-requested`；若提交后、投递前崩溃，由 `idx_document_status_parse` 对账扫描并重投，消费者按 `documentId + indexVersion` 幂等。
- RocketMQ 5.x 的 topic 名只允许 `[%A-Za-z0-9_-]`，因此物理 topic 使用 `parse-requested`、`parse-completed`、`parse-failed`；点号形式仅作为逻辑事件名。
- `status` 与 `retry_count` 支撑 `FAILED -> PARSING`；`retry_count >= max_retry` 时只允许进入 `DEAD`，不能继续**自动**重试。`DEAD -> PARSING` 只由 S2 的手动 `retry` 触发，并把 `retry_count` 归零重新计数。
- 重建（`reindex`）**不改变 `status`**：文档保持 `INDEXED`，在途状态由 `pending_index_version` 非空表达。这样网关检索范围（要求 `status='INDEXED'`）在整个重建期间继续放行该文档，重建失败或超时只需清空 `pending_index_version` 就回到原状；`DocumentStateMachine` 里 `INDEXED` 因此没有出边。
- 永久错误（例如加密 PDF）保留 `FAILED` 并设置不可重试错误码，不消耗重试次数；见需求 US-03。
- `index_version` 指向**当前可检索**的 PGVector 版本；`retry` 沿用同一版本以便幂等，`reindex` 才推进版本。版本号的写法是 `v{n}`，由服务端从当前值推导下一个值，不接受客户端传入——全局配置 `docqa.document.index-version` 只作为**新文档的起始值**，不再当作全局唯一版本使用。
- `pending_index_version` 是重建期间的目标版本：`reindex` 把它写入并发 `parse-requested`，`parse.completed` 只在版本号命中 `pending_index_version` 时把 `index_version` 推进过去、清空该列，随后发事件删除旧版本向量。旧版本在推进前一直可检索，因此重建失败不会让文档失去已有索引。
- `vector_rows` 与 `vector_confirmed_at` 是**最后一次向量侧确认**的落点，不是实时查询结果。确认有两个来源：**写入侧**由 `parse-completed` 新增的可空字段 `vectorRows` 带回，与 `status -> INDEXED` 在同一次落库写入——刻意不复用新 topic，否则会出现「文档已 INDEXED 但行数未知」的中间态窗口；**删除侧**由 `vector-change-completed` 带回该作用域删除后剩余的行数。两者都由 Python 在写完/删完的同一事务里 `SELECT COUNT(*)` 得出。
- Java 只在事件携带的 `indexVersion` **等于文档当前 `index_version`** 时才覆盖这两列；命中 `pending_index_version` 的推进事件在推进版本号之后写入，旧版本的清理确认只记日志不改列，否则一次重建会把新版本的确认覆盖成 0。
- 一致性判定在读取时计算：`COUNT(chunk)`（按当前 `index_version`）与 `vector_rows` 相等为 `MATCHED`，不等为 `DIVERGED`，`vector_rows` 为 NULL 为 `UNKNOWN`。它是**最后一次确认的对照**，不是跨库实时比对；实时巡检与自动修复属 S9 运维面。
- `deleted_at` 是软删除位：文档行保留以供历史引用与审计归属，`chunk` 行与 PGVector 向量在删除时**双侧同删**。所有读路径（列表、状态、详情、秒传命中、网关检索范围）必须带 `deleted_at IS NULL`。
- 双侧同删不是分布式事务：MySQL 提交与投递 `vector-change-requested` 之间崩溃，会留下**孤儿向量**——网关已查不到该文档，但历史会话里保存的 `vectorId` 引用回查仍能取回已删正文。判据写在同一行上：`deleted_at IS NOT NULL` 且（`vector_confirmed_at IS NULL` 或 `vector_confirmed_at < deleted_at`），由 `idx_document_deleted_vector` 支撑定时补投，消费者按 `documentId + operation` 幂等，重复删除只是删 0 行。
- `title` 是用户可改的展示名，可空；`file_name` 是不可变的原始文件名（进入 `parse-requested` 事件与 PGVector `metadata.file_name`），展示按 `title ?? file_name`。
- `error_message` 只允许可公开展示的安全文案，不写堆栈、Prompt 或底层连接信息。
- `uk_document_user_md5` 把去重作用域限定在同一用户内，禁止跨用户秒传返回对方 `documentId`。**该唯一键不参与删除位**，因此软删除后重传同一内容会撞唯一键——服务端读回原行、发现 `deleted_at` 非空即返回 409，不复活已删文档。

### 2.3 chunk

```sql
CREATE TABLE chunk (
  id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  chunk_index    INT UNSIGNED NOT NULL,
  vector_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_count    INT UNSIGNED NOT NULL,
  index_version  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_chunk_document_version_index (document_id, index_version, chunk_index)
    COMMENT 'parse-completed 幂等落库、按文档版本顺序读取分块',
  UNIQUE KEY uk_chunk_vector (vector_id)
    COMMENT 'MySQL 分块元数据到 PGVector 向量的稳定映射',
  CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
    REFERENCES document (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='分块元数据；正文与 embedding 在 PGVector，避免双写正文';
```

说明：

- 正文和 embedding 不重复存 MySQL，引用展示通过 `vector_id` 从 PGVector 取回；这样只保留一个正文事实来源。
- `uk_chunk_document_version_index` 让 Python 重投或消费者重试不会产生重复分块；`chunk_index` 从 0 开始。
- `index_version` 让新旧索引共存；RAG 只查询文档当前版本，旧版本在重建成功后清理。
- 文档删除时 MySQL 分块会级联删除，**但 S2 采用软删除（`document.deleted_at`），`document` 行仍在，级联不会触发**；`DELETE /documents/{id}` 必须显式按 `document_id` 删 `chunk` 行，并发 `vector-change-requested` 删 PGVector 侧向量。同理，重建（`reindex`）成功后必须显式删旧 `index_version` 的 `chunk` 行。
- 按 `(document_id, index_version)` 的显式删除走 `uk_chunk_document_version_index` 的最左前缀，不需要额外索引；这两个删除都在本地事务内提交，向量侧删除是异步的，两侧不一致由 §2.2 的行数对账暴露而不是由事务保证。

### 2.4 conversation

```sql
CREATE TABLE conversation (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title               VARCHAR(200) NOT NULL DEFAULT '新会话',
  document_scope_ids  JSON NULL,
  last_message_at     DATETIME(3) NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conversation_user_last (user_id, last_message_at)
    COMMENT '按用户展示最近会话与会话回访',
  CONSTRAINT chk_conversation_scope CHECK (
    document_scope_ids IS NULL OR JSON_TYPE(document_scope_ids) = 'ARRAY'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='会话控制面元数据；文档范围是逻辑关联，不是授权凭证';
```

`document_scope_ids` 保存的是候选文档范围，每次问答仍由 `agent-gateway` 查询 `document` 表校验 `user_id`、状态和 `index_version`，不能直接信任这个 JSON 或前端传入的文档 ID。

### 2.5 message

```sql
CREATE TABLE message (
  id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content           MEDIUMTEXT NOT NULL,
  token_count       INT UNSIGNED NOT NULL DEFAULT 0,
  client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  trace_id          CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  metadata          JSON NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_client_request (conversation_id, client_request_id)
    COMMENT '问答幂等：同一会话同一客户端请求只产生一条用户消息',
  KEY idx_message_conversation_created (conversation_id, created_at, id)
    COMMENT '按会话读取最近 6 条上下文与消息回放',
  KEY idx_message_trace (trace_id)
    COMMENT '按 traceId 关联一次问答的请求与回答',
  CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
    REFERENCES conversation (id) ON DELETE CASCADE,
  CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='会话消息；用户原文属于业务数据，不等于允许进入日志';
```

`uk_message_client_request` 只允许用户消息写入 `client_request_id`，助手消息保持 `NULL`；MySQL 唯一索引允许多个 `NULL`，不会阻止多条助手消息。`metadata` 保存引用数组等展示数据，不保存完整 Prompt。

### 2.6 reminder

DDL 的事实来源是 Flyway 迁移 `V3__reminder.sql` 与 `V7__reminder_schedule_import.sql`（S3 新增两列），
下面的片段是两者合并后的最终形状；改表必须先改迁移，再同步本节。

```sql
CREATE TABLE reminder (
  id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title            VARCHAR(200) NOT NULL,
  description      VARCHAR(1000) NULL,
  rrule            VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dtstart          DATETIME(3) NOT NULL,
  timezone         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lead_minutes     INT UNSIGNED NOT NULL DEFAULT 0,
  exdates          JSON NULL,
  exdate_dates     JSON NULL,
  status           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  source_type      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CUSTOM',
  source_uid       VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_sequence  INT UNSIGNED NOT NULL DEFAULT 0,
  idempotency_key  VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  next_trigger_at  DATETIME(3) NULL,
  last_triggered_at DATETIME(3) NULL,
  version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reminder_user_idempotency (user_id, idempotency_key)
    COMMENT '创建提醒的幂等重放与重复提交防护',
  UNIQUE KEY uk_reminder_user_source (user_id, source_type, source_uid)
    COMMENT 'ICS UID 幂等导入与课程/作业来源去重',
  KEY idx_reminder_due (status, next_trigger_at)
    COMMENT '调度器扫描到点提醒',
  KEY idx_reminder_user_status_next (user_id, status, next_trigger_at)
    COMMENT '提醒看板按用户和状态查询下一批提醒',
  CONSTRAINT chk_reminder_status CHECK (
    status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')
  ),
  CONSTRAINT chk_reminder_source CHECK (source_type IN ('CUSTOM', 'DEADLINE', 'COURSE')),
  CONSTRAINT chk_reminder_rrule CHECK (rrule LIKE 'FREQ=%'),
  CONSTRAINT chk_reminder_exdates CHECK (
    exdates IS NULL OR JSON_TYPE(exdates) = 'ARRAY'
  ),
  CONSTRAINT chk_reminder_exdate_dates CHECK (
    exdate_dates IS NULL OR JSON_TYPE(exdate_dates) = 'ARRAY'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='提醒规则；重复提醒只能一行，禁止按 occurrence 插多行';
```

关键约束：

- 一次性提醒使用 `rrule='FREQ=DAILY;COUNT=1'`，每周提醒使用 `rrule='FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z'` 一类 RFC 5545 值；`dtstart` 为 UTC 时间，`timezone` 保存 IANA 时区（如 `Asia/Shanghai`）。
- **两种 EXDATE 形态分列存储**，因为它们判重的粒度不同：
  - `exdates`：时刻级 `EXDATE`，归一化为 UTC 时间字符串数组，occurrence 判重是**精确 Instant 相等**。
  - `exdate_dates`（V7 新增）：仅日期形态（`EXDATE;VALUE=DATE:20261005`，语义是「整天不上课」），
    存 `YYYY-MM-DD` 字符串数组，按 `timezone` 下的**当地日期**判重。
    不能折算成时刻：无限 RRULE 下「这一整天」对应的是每一天同秒的一个点，折算只能排除其中一天；
    而导入期把 occurrence 全部展开再排除又受规则长度与 `COUNT` 上限约束，会在长学期里漏排除。
- 导入 ICS 时，一个 VEVENT 序列只写 `reminder` 的**一行**，不能展开成多条 occurrence；
  两列该存什么形态的值见上条，EXDATE 的作用点是 occurrence 而非提醒时刻。
- `source_type/source_uid/source_sequence` 用于 ICS 与作业来源幂等：同一 `userId + source_type + source_uid` 只有一个规则。
  S3 的课表导入把 SEQUENCE 比较结果分成**四条出路**而不是「覆盖或报错」两种：
  库内无此行 → `CREATE` 插入；文件值更高 → `UPDATE` 覆盖规则字段并保留 `id`；
  相等 → `UNCHANGED` 不产生写入；更低 → `STALE` 跳过。
  后两者**都不是错误**：教务系统重新导出整份学期时绝大多数课的 SEQUENCE 不变，
  若把「没有变化」当冲突，「重复导入按 UID+SEQUENCE 三态」这条验收就永远走不通。
- `COURSE` 行的 `dtstart` 存的是**上课时刻**（不是提醒时刻），因此 `timezone` + `dtstart` 必须能把
  「08:00 的课」原样还原；提醒时刻只物化在 `next_trigger_at = occurrence - lead_minutes`。
- `lead_minutes` **必须落列**（V7 的第二个新增列）。occurrence 序列定义在上课时刻上，而调度器每次触发后
  都要重算「再下一次」，重算时需要同一个提前量把 occurrence 域平移回提醒域；不落列则第二次及以后的提醒
  退化成「踩点上课时刻提醒」。也不能从行内反推：`next_trigger_at` 与下一次 occurrence 的差值在
  `lead ≥ 相邻 occurrence 间隔` 时必然偏小（每天 00:30 的课提前 60 分钟，反推只得到 30 分钟）。
  默认 0 使 `CUSTOM` 与 `DEADLINE` 行（提醒时刻本身就是 occurrence）行为逐字不变。
- `next_trigger_at` 是 Java 侧按 `rrule` 与两种 EXDATE 计算出的下一次触发时间，作为调度扫描的物化索引；
  原始规则始终保留在这三列里，物化列可以重算，不是判重依据。
- `version` 用于乐观锁，避免多个调度实例同时推进同一条提醒；`last_triggered_at` 只用于可观测性和补救，不作为唯一幂等依据。
- 提醒的触发幂等键由 `reminderId + occurrenceStartUtc` 组成，放 Redis 的短 TTL 键，防止重复扫描造成重复事件。
- **V7 不新增索引**。导入与预览的取数形状是
  `WHERE user_id = ? AND source_type = 'COURSE' AND source_uid IN (...)`，
  现有 `uk_reminder_user_source` 的列序 `(user_id, source_type, source_uid)` 与之**逐字对齐**，
  等值前缀加 `IN` 已能走唯一索引范围扫描；再建一个同列序的二级索引只会被 MySQL 忽略或平摊写放大。

### 2.7 audit_event

```sql
CREATE TABLE audit_event (
  id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  trace_id         CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  agent_name       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  severity         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INFO',
  payload_redacted JSON NOT NULL,
  occurred_at      DATETIME(3) NOT NULL,
  ingested_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_trace_time (trace_id, occurred_at)
    COMMENT '按 traceId 还原一次问答/上传的审计时间线',
  KEY idx_audit_user_time (user_id, occurred_at)
    COMMENT '按用户做合规查询与异常行为排查',
  KEY idx_audit_type_time (event_type, occurred_at)
    COMMENT '按事件类型做统计、告警与保留策略',
  CONSTRAINT chk_audit_source CHECK (source IN ('JAVA', 'PYTHON')),
  CONSTRAINT chk_audit_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='不可变审计事件；只允许 INSERT/SELECT，禁止 UPDATE/DELETE';
```

`id` 由事件生产方生成，作为 MQ 重复投递时的幂等键；`payload_redacted` 只能放白名单字段（状态、耗时、token、工具名、错误码、脱敏摘要），禁止放完整问题、完整 Prompt、API Key、连接串、手机号、身份证号或邮箱原文。`occurred_at` 是事件发生时间，`ingested_at` 是落库时间，两者差值可用于审计消费延迟监控。

### 2.8 agent_execution

```sql
CREATE TABLE agent_execution (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  trace_id            CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_message_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  response_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status              VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  budget_status       VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
  token_budget        INT UNSIGNED NOT NULL,
  token_used          INT UNSIGNED NOT NULL DEFAULT 0,
  tool_calls_made     INT UNSIGNED NOT NULL DEFAULT 0,
  rounds_used         INT UNSIGNED NOT NULL DEFAULT 0,
  cache_hit           BOOLEAN NOT NULL DEFAULT FALSE,
  latency_ms          BIGINT UNSIGNED NOT NULL,
  error_code          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  audit_outbox        JSON NOT NULL,
  started_at          DATETIME(3) NOT NULL,
  finished_at         DATETIME(3) NOT NULL,
  recorded_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_execution_trace (trace_id)
    COMMENT '一次请求只允许一个终态执行快照，按 trace 查询与幂等写入',
  KEY idx_agent_execution_user_started (user_id, started_at)
    COMMENT '按用户统计问答量、预算消耗与失败率',
  KEY idx_agent_execution_status_started (status, started_at)
    COMMENT '状态维度的告警、对账与审计查询',
  KEY idx_agent_execution_outbox (recorded_at, id)
    COMMENT '审计 outbox relay 扫描待投递执行快照',
  CONSTRAINT chk_agent_execution_status CHECK (
    status IN ('RUNNING', 'SUCCESS', 'BUDGET_EXHAUSTED', 'FAILED')
  ),
  CONSTRAINT chk_agent_execution_budget CHECK (
    budget_status IS NULL OR budget_status IN ('OK', 'LOW_CONFIDENCE', 'BUDGET_EXHAUSTED')
  ),
  CONSTRAINT chk_agent_execution_audit_outbox CHECK (
    JSON_TYPE(audit_outbox) = 'ARRAY'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Agent 执行终态快照；追加写，禁止更新和删除；audit_outbox 兼作审计发件箱';
```

`agent_execution` 与追加写约束的取舍：

- 本表每次问答只写入一个**终态快照**，因此天然不需要 UPDATE；`RUNNING` 保留在枚举和 Redis 的 `agent:state:{traceId}` 中，用于在途可观测性，不通过更新数据库行改变状态。
- 如果未来需要落库在途快照，必须改为“追加 RUNNING 快照 + 追加终态快照”，并增加快照序号；不能把本表改成可更新表。
- `uk_agent_execution_trace` 让 Java 重复写同一 trace 的终态时幂等；`token_used/tool_calls_made/rounds_used` 由 Java 在响应返回前后核对，超过协议上限的记录标记协议违规并按失败处理。
- `budget_status` 允许为 `NULL`：协议违规、PII 阻断、超时等在拿到有效 Python 响应前就失败，不能伪造 `OK`；成功响应和预算耗尽必须写入对应枚举。
- `audit_outbox` 保存本次执行最终要投递的审计事件数组，和消息、执行快照在同一本地事务写入；它是 `agent-gateway` 自己的发件箱，不承担审计查询。
- `audit_outbox` 只追加、不清空、不更新；relay 按 `(recorded_at, id)` 游标扫描并发布，游标丢失时从保留窗口起点重放，`audit-service` 按 `eventId` 去重。
- `audit_event` 仍是审计查询与保存的事实表，由 `audit-service` 消费 MQ 后追加；`audit_outbox` 只是投递前的耐久缓冲，二者不互相替代。
- `cache_hit=true` 时 `token_used=0`，但审计仍写入，不能把缓存命中当成“没有发生问答”。

### 2.9 索引用途汇总

| 表 | 索引 | 服务的查询 |
|---|---|---|
| document | `uk_document_user_md5` | 秒传查询、同一用户并发去重 |
| document | `uk_document_object_key` | 对象定位、清理与重复对象检查 |
| document | `idx_document_status_parse` | 扫描超时 `PARSING`、对账重投 |
| document | `idx_document_user_created` | 用户文档列表与元数据搜索；S2 起所有查询附加 `deleted_at IS NULL` |
| document | `idx_document_deleted_vector` | 补投已软删除但向量侧未确认的文档（双侧同删的崩溃窗口） |
| document_tag | `uk_document_tag_user_normalized` | 标签名在同一用户内判等、PUT 全量替换的 get-or-create |
| document_tag_link | 主键 `(document_id, tag_id)` | 读取单文档全部标签、整集替换时按前缀删除、重复挂载兜底 |
| document_tag_link | `idx_document_tag_link_tag` | 搜索接口的 `tag` 过滤（按标签反查文档） |
| chunk | `uk_chunk_document_version_index` | 分块幂等落库、按版本读取 |
| chunk | `uk_chunk_vector` | MySQL 分块到 PGVector 映射 |
| conversation | `idx_conversation_user_last` | 最近会话列表 |
| message | `uk_message_client_request` | 问答幂等 |
| message | `idx_message_conversation_created` | 最近 6 条上下文 |
| message | `idx_message_trace` | 按 trace 回放一次问答 |
| reminder | `uk_reminder_user_idempotency` | 创建提醒幂等 |
| reminder | `uk_reminder_user_source` | ICS UID 幂等导入 |
| reminder | `idx_reminder_due` | 调度器到点扫描 |
| reminder | `idx_reminder_user_status_next` | 提醒看板 |
| reminder_trigger | `uk_reminder_trigger_planned` | 一次 occurrence 一行的消费幂等收敛点 |
| reminder_trigger | `idx_reminder_trigger_user` | 通知列表 keyset 分页（`user_id` 等值 + `planned_trigger_at, id` 降序） |
| audit_event | `idx_audit_trace_time` | 按 trace 查审计时间线 |
| audit_event | `idx_audit_user_time` | 用户合规查询 |
| audit_event | `idx_audit_type_time` | 事件类型统计与告警 |
| audit_event | `idx_audit_occurred` | S9 管理面按时间窗读取最近事件（无其它过滤时的 keyset 分页） |
| agent_execution | `uk_agent_execution_trace` | 执行终态幂等、trace 查询 |
| agent_execution | `idx_agent_execution_user_started` | 用户成本与失败率 |
| agent_execution | `idx_agent_execution_status_started` | 状态告警与对账 |
| agent_execution | `idx_agent_execution_outbox` | 审计 outbox relay 扫描待投递事件 |
| agent_execution | `idx_agent_execution_started` | S9 管理面按时间窗聚合用量 |
| ai_quota | 主键 `(user_id)` | 按用户读取与写入日配额覆盖；一用户一行 |
| ai_quota | `idx_ai_quota_updated` | 管理面按最近变更 keyset 分页 |
| app_user | `uk_app_user_username` | 注册判重、登录按用户名查找 |
| app_user | `idx_app_user_created` | 管理员用户列表默认 keyset 分页 |
| app_user | `idx_app_user_status_created` | 管理员按状态过滤的 keyset 分页 |
| app_user_role | 主键 `(user_id, role)` | 读取用户角色集合、整集替换授予关系 |
| refresh_token | `uk_refresh_token_hash` | 续期时按摘要定位令牌行 |
| refresh_token | `idx_refresh_token_user_status` | 改密/停用时按用户整批吊销活动令牌 |
| refresh_token | `idx_refresh_token_family_status` | 复用检测时吊销整个令牌家族 |
| refresh_token | `idx_refresh_token_expiry` | 过期令牌清理任务扫描 |

主键索引统一服务“按主键/资源 ID 查询状态、消息和执行记录”；其余索引的用途已随 DDL 注释和上表记录。

两条 S2 索引取舍需要记录在案：

- **元数据搜索的 `LIKE` 不使用索引**。`q` 参数匹配 `file_name` 或 `title`，为了支持中间子串必须是前后通配 `%…%`，MySQL 无法用 B-tree 索引；执行计划靠 `user_id = ?` 走 `idx_document_user_created` 把扫描范围压到**单个学生的文档集合**再逐行过滤。本期不建 `FULLTEXT` 索引：它的分词器对中文需要 ngram 插件、且要求把 `title`/`file_name` 复制进一张全文索引表，收益在百量级文档下不可替代前缀过滤。因此契约在搜索操作上**明写不保证大表性能**，量级上升时的正确解法是引入外部检索引擎而不是给 `LIKE` 打补丁。
- **`deleted_at` 不建索引，也不并入复合索引**。它是低选择性的布尔位——绝大多数行恒为 `IS NULL`，把它加在 `(user_id, created_at, id)` 里既不改变过滤量，又会为每次软删除多维护一个索引条目。它的作用是**正确性**（读路径必须带上），不是加速。

S4 的三条取舍同样记录在案：

- **`idx_reminder_trigger_user` 的列序必须与游标编码逐字同序**（`user_id` 等值 → `planned_trigger_at` → `id`）。通知列表是 `DESC, DESC`，InnoDB 二级索引叶子节点带主键值且能反向扫描，因此三列同序即可让有序读取代替文件排序；把 `id` 换成 `created_at` 之类的第二排序键会让游标锚点与索引顺序不一致，keyset 分页随之失去「不重不漏」的保证。`id` 作 tiebreak 是因为同一毫秒可以落两行（两个提醒同时到点），此时没有稳定的第二键就会重复或漏行。
- **`read_at` 不建索引、也不并入复合索引**。与 `deleted_at` 同理：未读是低选择性的瞬态位，绝大多数历史行最终都会被读掉；把它加进排序索引只会破坏上面那条同序性质。`unreadOnly` 的过滤代价随每用户行数线性增长，本期量级为几百行，超限时按 §2.16 登记改为四列复合索引。
- **不另建 `reminder_id` 单列索引**。「某提醒的全部触发历史」是运维/排障查询，本期没有对应 API；`uk_reminder_trigger_planned` 的最左前缀已经是 `reminder_id`，该查询本来就走唯一索引，再加单列索引是纯冗余。

S9 的两条取舍同样记录在案：

- **`agent_execution` 增加 `idx_agent_execution_started (started_at)`**。既有三个索引分别以 `user_id`、`status`、`recorded_at` 开头，管理面的「最近 24 小时 / 按天聚合」没有可用索引；不加就是全表扫描。该表是追加写，索引只增加每条插入的维护成本。
- **`audit_event` 增加 `idx_audit_occurred (occurred_at, id)`**。既有三个索引都以过滤列开头，无法服务「只按时间窗读最近事件」；`id` 作第二列是为了让 `(occurred_at DESC, id DESC)` 的 keyset 分页与索引顺序一致。管理面的只读查询不少，写入侧多一个索引条目是可接受的代价。

### 2.10 追加写表的强制方式

`audit_event` 与 `agent_execution` 采用两层约束：

1. **应用层：** 对应 Mapper/Repository 只暴露 `insert` 和只读查询，不提供 `update*`、`delete*` 方法；服务层没有修改接口；Code Review 和 ArchUnit 检查禁止出现针对这两张表的 UPDATE/DELETE SQL。
2. **数据库权限层：** 业务应用账号对这两张表只有 `SELECT`、`INSERT`，不授予 `UPDATE`、`DELETE`。迁移账号负责 DDL，与业务账号分离。示意：

```sql
-- 仅示意；真实账号、密码由部署环境密钥管理注入，不写入仓库
GRANT SELECT, INSERT ON docqa.audit_event TO 'docqa_app'@'%';
GRANT SELECT, INSERT ON docqa.agent_execution TO 'docqa_app'@'%';
```

即使应用代码出现 SQL 拼接错误，数据库也会拒绝更新/删除。生产环境还可增加 `BEFORE UPDATE`/`BEFORE DELETE` 触发器作为第三层防护，但触发器不能替代最小权限账号，且 DBA 仍可通过高权限绕过。

本节只约束 `audit_event` 与 `agent_execution`。身份三表（§2.11–§2.13）不享有追加写不变式：`app_user.status`、`app_user_role` 的整集替换和 `refresh_token.status` 的状态推进都必须是 UPDATE，把它们纳入只 INSERT 的白名单会让登出和吊销无法实现。

### 2.11 app_user

DDL 的事实来源是 Flyway 迁移 `V5__app_user_and_refresh_token.sql`，下面的片段与它逐字一致；改表必须先改迁移，再同步本节。

```sql
CREATE TABLE app_user (
  id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  username        VARCHAR(32) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  password_hash   CHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  last_login_at   DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_username (username),
  KEY idx_app_user_created (created_at, id),
  KEY idx_app_user_status_created (status, created_at, id),
  CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
  CONSTRAINT chk_app_user_hash_length CHECK (CHAR_LENGTH(password_hash) = 60)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户控制面身份；口令只保存 BCrypt 摘要，任何 API 面都不外发该列';
```

| 字段 | 说明 |
|---|---|
| `id` | 应用生成的 UUIDv7，即 JWT `sub` 和业务表 `user_id` 的取值来源 |
| `username` | 登录名，`ascii_general_ci` 使唯一键大小写不敏感；应用侧写入前仍先做小写归一，两者构成两道防线，防止归一规则被绕过 |
| `password_hash` | BCrypt 摘要，固定 60 字符，成本因子 12；`chk_app_user_hash_length` 用于挡住误写明文或误用其它摘要长度 |
| `status` | `ACTIVE` / `DISABLED`，停用不删行，历史记录与审计必须继续可归属到该用户 |
| `last_login_at` | 仅在登录成功时更新，用于运营排查，不参与权限判定 |

设计要点：

- 账号被删除的情况本期不存在，所以状态用软标记而不是删行；停用必须同时吊销全部刷新令牌并在 Redis 写入用户级访问令牌水位（见 §3），否则“已停用但令牌还在有效期内”会形成立即失效的空窗。
- 用户名不是 PII 但也不是秘密，因此允许出现在幂等键的第一段（注册时还没有 `userId`），而口令、邮箱和手机号一律不进 Redis key。
- 排序键是 `(created_at, id)` 且列表按**降序**取页，与文档列表同构；MySQL 8 能在 `idx_app_user_created` 上做反向索引扫描，所以这一个索引既服务降序翻页也保证游标续页稳定，按状态过滤时走 `idx_app_user_status_created`。

### 2.12 app_user_role

```sql
CREATE TABLE app_user_role (
  user_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role),
  CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN')),
  CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id)
    REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='角色授予关系；多角色由多行表达，权限判定在应用层枚举内完成';
```

| 字段 | 说明 |
|---|---|
| `user_id` | 指向 `app_user.id`；同服务内的强拥有关系，允许外键，`ON DELETE CASCADE` 保证不出现孤儿授权 |
| `role` | 本期只有 `USER` 与 `ADMIN` 两个枚举值，权限判定在 Java 侧枚举内完成，不在 SQL 里做字符串比较 |
| 主键 `(user_id, role)` | 天然去重同一用户的重复授予，同时支撑“按用户读角色集合”的单前缀扫描 |

设计要点：

- 一个用户的角色集合由多行表达，授权变更采用**整集替换**（先按主键删该用户全部行，再插入目标集合，同一事务），不做增量 diff，避免出现“部分成功”的中间态。
- 角色只影响管理面权限判定，不影响业务表所有权；`userId` 仍是唯一的租户边界。
- 角色授予不进 JWT 之外的隐式通道：签发时把当时刻的角色集合写进 `roles` claim，权限判定读取自己验签通过的令牌声明，不接受 Header 或 Body 传入的角色。因此回收角色不会即时改变已签发 access 的权限，生效延迟上限是 access TTL（15 分钟），这一点已写进契约，客户端不得把「改完立刻生效」当成可依赖行为。需要**立即**收权时唯一入口是停用账号（§2.11 的 `status` + 刷新令牌吊销 + 用户级水位），角色替换不承担即时性。

### 2.13 refresh_token

```sql
CREATE TABLE refresh_token (
  id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  family_id    CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  expires_at   DATETIME(3) NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_token_hash (token_hash),
  KEY idx_refresh_token_user_status (user_id, status),
  KEY idx_refresh_token_family_status (family_id, status),
  KEY idx_refresh_token_expiry (expires_at),
  CONSTRAINT chk_refresh_token_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
  CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id)
    REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='刷新令牌轮换链；只存 SHA-256 摘要，family_id 支撑复用检测时的整家族吊销';
```

| 字段 | 说明 |
|---|---|
| `id` | 令牌行主键，UUIDv7；不进入 Cookie |
| `user_id` | 令牌所属用户，续期时必须与令牌行一致 |
| `family_id` | 一次登录建立的轮换链根；每次轮换沿用同一 `family_id`，被盗用者复用旧令牌时可整家族吊销 |
| `token_hash` | 服务端发出的不透明随机串的 SHA-256 十六进制摘要，`uk_refresh_token_hash` 保证按摘要唯一定位 |
| `status` | `ACTIVE` → `USED` / `REVOKED`；`USED` 与 `REVOKED` 都是终态 |
| `expires_at` | 绝对过期时刻（UTC），到点即拒绝，与 `status` 独立判定 |

设计要点：

- 刷新令牌是**一次使用即轮换**：续期时先 `UPDATE refresh_token SET status='USED' WHERE token_hash=? AND status='ACTIVE'`，以受影响行数是否为 1 作为并发判定；行数为 0 说明该摘要已被使用或已吊销，不区分“并发双击”与“被盗复用”，统一按复用检测处理并吊销整个 `family_id`，因为服务端无法证明二者不同。只有更新成功才插入新的 `ACTIVE` 行并在同一事务提交，避免“旧令牌已废、新令牌未发”的丢会话中间态。
- 库里只存摘要，即使 `refresh_token` 整表泄漏也无法还原可用凭据；明文只出现在 `Set-Cookie` 一次。
- `REVOKED` 行不删除：复用检测需要它来区分“从未见过的令牌”与“已被吊销的家族成员”。清理由 `expires_at` 索引支撑的定时任务处理已过 `REVOKED`/`USED` 且远超生命周期的行。
- 改密、管理员停用、主动登出这三种动作都必须吊销刷新令牌，区别只在是否同时写访问令牌吊销水位：登出只吊销当次 `jti`，改密和停用吊销该用户全部活动令牌并写用户级水位。

### 2.14 document_tag

```sql
CREATE TABLE document_tag (
  id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name            VARCHAR(24) NOT NULL,
  normalized_name VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_document_tag_user_normalized (user_id, normalized_name)
    COMMENT '标签名在同一用户内唯一，是 PUT 全量替换的收敛依据',
  CONSTRAINT chk_document_tag_name CHECK (CHAR_LENGTH(normalized_name) BETWEEN 1 AND 24)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='用户级标签词表；name 保存首次创建时的展示写法，normalized_name 保存归一后的判等键';
```

| 字段 | 说明 |
|---|---|
| `id` | 应用生成的 UUIDv7，是 `document_tag_link` 的引用目标 |
| `user_id` | 标签归属用户，标签**不跨用户共享**，与文档所有权同一租户边界 |
| `name` | 展示名，保留创建者输入的大小写；默认 `utf8mb4_0900_ai_ci` 仅影响比较，不参与判等 |
| `normalized_name` | 归一化后的判等键，`utf8mb4_bin` 按字节比较，DB 不做任何二次折叠 |
| `created_at` | 词表排序与排障用；标签无 `updated_at`，改名等价于「删旧建新」 |

要点：

- **不建立指向 `app_user` 的外键**，尽管同库：本文 §1 的原则是跨服务域不做数据库外键，`document` / `chunk` 的 `user_id` 同样没有。用户域与文档域在生产化时会被拆开，加了外键就等于把两个迁移历史焊死。租户边界由应用层保证——所有标签 SQL 的第一条件恒为 `user_id = ?`，且该值只能来自 JWT `sub`。
- 归一化规则只有一处实现，在 Java 侧：`strip()` → 连续空白折叠为单个空格 → `toLowerCase(Locale.ROOT)`。**长度 1–24 的校验对归一后的串进行**，因此「前后空格」和「中间多空格」不会绕过唯一键。
- `normalized_name` 故意用 `utf8mb4_bin` 而不是 `app_user.username` 那样的 CI collation。用户名是纯 ASCII，DB 大小写不敏感是无害的第二道防线；标签是任意语言的自由文本，MySQL 的 UCA 折叠规则（`ai_ci` 会把 `café` 与 `cafe` 判等）与应用规则不可能完全一致，两套判等并存会让「哪两个标签算同一个」随版本漂移。因此判等权归应用，DB 只提供唯一性兜底。
- 同一用户内的标签数量本期**不设上限**：它是词表而非配额，配额作用在单文档上（§2.15）。
- 悬挂标签是允许的：所有文档软删除后，`document_tag` 行仍保留（用户下次打同名标签复用原行，展示写法保持一致）。本期不开放「我的标签」列表接口，因此悬挂行不外露；若后续开放，查询必须 `EXISTS` 关联到 `deleted_at IS NULL` 的文档，否则停用/删除过的内容会污染词表。
- 该表不参与 `chk` 之外的状态机，没有软删除位：删标签只由 `document_tag_link` 的全量替换隐式完成，词表行本身不删。生产化若要清理长期零引用标签，属于运维任务而非 API 面。

### 2.15 document_tag_link

```sql
CREATE TABLE document_tag_link (
  document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tag_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (document_id, tag_id),
  KEY idx_document_tag_link_tag (tag_id, document_id)
    COMMENT '按标签过滤文档列表（搜索的 tag 参数）',
  CONSTRAINT fk_document_tag_link_document FOREIGN KEY (document_id)
    REFERENCES document (id) ON DELETE CASCADE,
  CONSTRAINT fk_document_tag_link_tag FOREIGN KEY (tag_id)
    REFERENCES document_tag (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='文档与标签的多对多关系；PUT 全量替换，增量 diff 不在服务端做';
```

要点：

- 用复合主键而不是代理主键 + 唯一键：关系表没有独立生命周期，`(document_id, tag_id)` 既是标识又是去重约束，重复插入直接撞主键，天然幂等。
- `idx_document_tag_link_tag (tag_id, document_id)` 服务搜索里的 `tag` 过滤；反查方向（某文档的全部标签）走主键前缀 `document_id`，因此不需要 `(document_id)` 单独索引。
- **单文档标签数 ≤ 20 由应用层校验**，MySQL 无法对「同 `document_id` 的行数」写 CHECK。超限、名称长度非法、集合内重复一律返回 400 `COM-VALIDATION-001` 并携带 `fieldViolations`，整批拒绝而不是截断，因为静默丢掉第 21 个标签会让客户端误以为已生效。**S2 不新增错误码**：`DOC-VALIDATION-003` 已被「加密 PDF」占用，`DOC-VALIDATION-004` 的文案专指合并对象的大小/MD5 不一致，把标签校验塞进去会让错误码与用户可见文案对不上；而 400 族的字段级校验正是契约里 `fieldViolations` 的既有通道（`pageSize` 越界、游标非法都走它）。
- 标签写接口是**整集替换**（`PUT`），与 §2.12 的角色替换同构：先按 `document_id` 删该文档全部关联行，再插入目标集合，同一事务。不做增量 diff，避免并发两次替换互相覆盖后留下半套标签。
- `fk_document_tag_link_document` 的 `ON DELETE CASCADE` 只在文档**物理删除**时生效，而本期是软删除，因此软删除 API 必须自己处理关联行：**保留** `document_tag_link`（文档行还在，词表关系是历史事实的一部分），但所有按标签的查询都必须经 `document` 过滤 `deleted_at IS NULL`。这一点与 §2.3 相反——`chunk` 是内容的派生物必须删，`document_tag_link` 是元数据关系不删。
- 契约里标签写接口的请求体传**标签名**而不是 `tagId`：客户端不需要持有或缓存词表 ID，服务端按 `(user_id, normalized_name)` 做 get-or-create 后再写关联行。代价是**按名解析的每条 SQL 都必须带 `user_id = ?`**——少了这个条件就会命中他人词表里的同名行，把文档挂到不属于当前用户的标签上（信息泄漏 + 跨用户污染），而外键完全无法发现它，因为 `tag_id` 指向的确实是一条合法的 `document_tag` 行。
- 由此还有一条推论：**不能给标签写接口提供「按 tagId 直接挂」的变体**。本期不暴露词表 ID，就没有越权面；未来若要暴露必须同时校验 `tag.user_id == JWT.sub`，并把这条校验写进契约的 403/404 语义里。

### 2.16 reminder_trigger

DDL 的事实来源是 Flyway 迁移 `V8__reminder_trigger.sql`（S4 新增，本节即该迁移的落地判据；改表必须先改迁移，再同步本节）。
它承载 US-07 的触发历史与站内通知，契约面是 2.3.0 的 `/api/v1/notifications`，其中 `notificationId` 就是本表的 `id`。

```sql
CREATE TABLE reminder_trigger (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reminder_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title               VARCHAR(200) NOT NULL,
  description         VARCHAR(1000) NULL,
  source_type         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_trigger_at    DATETIME(3) NOT NULL,
  next_trigger_at     DATETIME(3) NULL,
  trace_id            CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delivered_at        DATETIME(3) NOT NULL,
  read_at             DATETIME(3) NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reminder_trigger_planned (reminder_id, planned_trigger_at)
    COMMENT '一次 occurrence 一行；RocketMQ at-least-once 重投的收敛点',
  KEY idx_reminder_trigger_user (user_id, planned_trigger_at, id)
    COMMENT '通知列表 keyset 分页：user_id 等值 + (planned_trigger_at, id) 降序',
  CONSTRAINT chk_reminder_trigger_source CHECK (
    source_type IN ('CUSTOM', 'DEADLINE', 'COURSE')
  ),
  CONSTRAINT chk_reminder_trigger_read CHECK (
    read_at IS NULL OR read_at >= delivered_at
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='提醒触发历史与站内通知；只由 reminder-service 读写';
```

本表**不建任何外键**，理由见下面第三条要点。

要点：

- **一张表同时服务「触发历史」与「站内通知」，不拆两张**。一行的存在条件就是「一次已发布的触发」，
  通知只是它的一个可变属性（`read_at`）。拆成 `reminder_trigger` + `notification` 会得到一张
  与前者行数**逐行 1:1** 的表，两次写必须同事务，否则出现「有触发没通知」这种无法解释的中间态；
  代价是**本表面向「提醒触发」而非泛化通知**：将来若要有非触发来源的通知（如解析完成、系统公告），
  必须另建表或在契约里引入 `type` 列，不能把语义不同的行混进这张表——本期不做，`source_type` 只有三个提醒来源值。
- **幂等键是 `(reminder_id, planned_trigger_at)`，刻意不用 `eventId` 或 MQ `msgId`**。
  `ReminderDueService.dispatch()` 在发布失败时会 `release` Redis 触发标记并返回 `FAILED`，
  下一轮扫描对**同一个 occurrence** 重新构造事件，`eventId` 是 `UUID.randomUUID()` 新值、
  RocketMQ 也会给出新 `msgId`。因此按 `eventId`/`msgId` 判重会稳定地插入第二条通知，
  而「同一次触发被通知两次」正是 US-07 要防的错。`(提醒, 计划触发时刻)` 才是业务上的唯一事实。
- **不加指向 `reminder` 的外键**，尽管同库同服务。消费链路必须能接受任意行序：加了外键之后，
  一条消息只要指向「已被清除或尚未可见」的提醒行就会永久插入失败，RocketMQ 的重投会一路走到死信，
  表现为「通知静默丢失」而不是「一次可恢复的重试」。租户边界与归属关系由应用层保证
  （`user_id` 直接取自事件载荷，而事件的 `userId` 又来自提醒行本身）。这与 §2.15 相反：
  那里的外键服务于「随文档物理删除而收敛」，本表没有删除路径。
- `user_id` **反范式落列**，且列表 SQL 的第一条件恒为 `user_id = ?`（值只能来自已验签 JWT 的 `sub`）。
  少了这一列，通知列表必须 JOIN `reminder` 才能定租户，keyset 分页的排序索引也就无法只靠本表覆盖。
- `title` / `description` / `source_type` 是**触发当时的快照**，从 `ReminderDueEvent` 载荷直接落库，不回填、不随提醒改名。
  通知要回答「当时响了什么」；同时这让列表查询完全不必回 `reminder` 表。
  事件载荷与提醒行的字段一致，因此快照不是第二份真相，只是同一真相在时间轴上的一个切片。
- `planned_trigger_at` 取事件的 `occurrenceStartUtc`，**不换算、不回填**。必须先记下这个事件字段名的历史误名：
  `ReminderDueService.dispatch()` 传给它的实参是 `row.nextTriggerAt()`（截断到秒），也就是**计划触发时刻**；
  `lead_minutes` 是 S3 的 V7 才加的列，事件载荷在那之前就定型了，S4 是它的第一个消费者，这才暴露出
  「字段名叫 occurrence、装的却是 occurrence − lead」。对课表类提醒，本列因此是**提前提醒的时刻**，不是上课时刻。
  不在消费侧回算上课时刻，两条理由：① 事件的 12 个字段里没有 `lead_minutes`，消费者无法自足换算；
  ② 按 `reminder_id` 回读当前行的 `lead_minutes` 会把幂等键建在**可变状态**上——两次投递之间发生一次
  课表重导入，同一条消息就会算出两个不同时刻，唯一键挡不住重复通知。
  界面要显示上课时刻，用看板行的 `leadMinutes`（2.3.0 已投影）做显示层加法即可，不参与身份判定。
  NOT NULL 且是排序键。
  `next_trigger_at` 是**发布当时**算出的下一次触发（同为触发时刻口径，可与本列直接比较），属于快照：
  它可能因用户随后暂停/完成该提醒而变陈旧，**不回写**，展示上应理解为「触发那一刻系统认为的下次是几点」。
- `delivered_at` 是首次落库时刻，重投被唯一键挡掉后不再刷新，因此它与 `created_at` 在同一次插入里等值；
  保留两列是因为前者是业务语义（何时通知到用户）、后者是行生命周期技术列，其余表同此口径。
- `read_at` **只允许由空变有**：更新语句固定为 `... SET read_at = ? WHERE id IN (...) AND user_id = ? AND read_at IS NULL`。
  重复标记既不改写首次已读时刻，也不产生第二次副作用，这就是契约里「标记已读不要求 `Idempotency-Key`」的依据。
  同一条 SQL 里 `user_id = ?` 与 `read_at IS NULL` 缺一不可：前者是越权边界（不属于自己的 id 静默不在集合内，
  不返回 404 也不逐项报告），后者是「真正由空变有」的行数语义来源。
- `chk_reminder_trigger_read CHECK (read_at IS NULL OR read_at >= delivered_at)` 把「先送达才可能已读」写成约束，
  与 §2.6 的 CHECK 同样依赖 MySQL 8.0.16 起才真正强制执行 CHECK。
  **两列都必须截断到整秒再写**：列型是 `DATETIME(3)`，而应用侧 `Clock` 带毫秒，
  若 `delivered_at` 落在 `10:00:00.500` 而用户在同一秒内点开（`read_at` 截断为 `10:00:00.000`），
  约束会把一次正常点击打成 500。落库侧（`ReminderTriggerIngestService`）与标记已读（`NotificationService`）
  因此同用 `truncatedTo(SECONDS)`，两者同口径才能比较。
- **索引只有三条**（主键、唯一键、列表键）。`unreadOnly=true` 本期**不单独建索引**：
  该过滤是 `read_at IS NULL`，落在排序键之后，加进 `(user_id, planned_trigger_at, id)` 只会破坏排序匹配；
  每用户的行数上界由「课程数 × 学期周数 × 已触发周」决定，量级在几百行，
  走 `user_id` 等值前缀再回表过滤即可。**判据而非结论**：本期未压测，若单用户触发历史进入万级，
  应改为 `(user_id, read_at, planned_trigger_at, id)` 并接受写入侧多一个索引，登记为新切片。
- **没有删除接口、不做归档**。提醒被完成或取消后历史通知仍然保留（事实记录不随规则状态消失），
  这也是 §2.16 不加外键 `ON DELETE CASCADE` 的连带理由。清理属于运维任务，本期不提供。

### 2.17 ai_quota（设计落盘）

DDL 的事实来源是 V9 迁移；下面的片段与迁移一致，改表必须先改迁移，再同步本节。

```sql
-- 按用户的 AI 日配额覆盖；缺行表示不设上限
CREATE TABLE ai_quota (
  user_id               CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  daily_question_limit  INT UNSIGNED NULL,
  daily_token_limit     INT UNSIGNED NULL,
  updated_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                          ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  PRIMARY KEY (user_id),
  KEY idx_ai_quota_updated (updated_at, user_id)
    COMMENT '管理面按最近变更 keyset 分页',
  CONSTRAINT fk_ai_quota_user FOREIGN KEY (user_id)
    REFERENCES app_user (id) ON DELETE CASCADE,
  CONSTRAINT chk_ai_quota_question_limit CHECK (
    daily_question_limit IS NULL OR daily_question_limit >= 1
  ),
  CONSTRAINT chk_ai_quota_token_limit CHECK (
    daily_token_limit IS NULL OR daily_token_limit >= 1
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='按用户的 AI 日配额覆盖；缺行表示不设上限';

ALTER TABLE agent_execution
  ADD KEY idx_agent_execution_started (started_at)
    COMMENT '管理面按时间窗聚合问答用量';

ALTER TABLE audit_event
  ADD KEY idx_audit_occurred (occurred_at, id)
    COMMENT '管理面最近事件 keyset 查询（时间窗无其它过滤时）';
```

| 字段 | 说明 |
|---|---|
| `user_id` | 配额主体，同时是主键；与 JWT `sub` 同值，外键兜底 |
| `daily_question_limit` | UTC 自然日允许的问答次数；`NULL` 表示该维度不设限 |
| `daily_token_limit` | UTC 自然日允许的 token 累计；`NULL` 表示该维度不设限；这是**准入软闸**，不是单次硬上限 |
| `updated_at` | 最近一次设置时刻，服务管理面 `updated_at DESC` 分页 |
| `updated_by` | 最近一次操作的管理员 `userId`；不建外键，审计事实以 `audit_event.user_id` 为准 |

设计要点：

- **缺行 = 不设上限**。清除覆盖用 `DELETE`，不保留空行；列表只展示真正配置了配额的用户。
- 配额是业务约束而不是安全边界：Redis 日计数丢失时从 0 重新计，最坏后果是当日多放行若干次，不会造成越权或数据损坏。
- 配额变更与 `audit_event` 的 INSERT 在同一 MySQL 事务内；审计写不进去则配额变更回滚。
- 本表允许 UPDATE（与 §2.10 的追加写表不同），因为配额覆盖本身就是可修改的运营配置。

### 2.18 tenant（SaaS 租户地基）

DDL 的事实来源是 V10 迁移；下面的片段与迁移逐字一致（契约测试 `MigrationV10ContractTest` 对账），改表必须先改迁移，再同步本节。

默认租户那一行**不在迁移里**：`ss_migrator` 只有 schema 级 DDL 权限，没有任何业务表的
`INSERT` 权限（见 `ops/sql/README.md`），而 V1–V10 全部是纯 DDL。种子行由
`document-service` 启动期的 `DefaultTenantInitializer` 用 app 账号幂等写入
（`INSERT IGNORE`），可以安全重复执行。

```sql
-- 租户地基（SaaS 内核 P0）。
-- 事实源：docs/02-design/storage.md §2.18，代码侧由 MigrationV10ContractTest 逐字对账。
--
-- 设计要点：
-- 1. 默认租户承载全部存量数据；id 固定为全 1 UUID，便于回滚与人工核对。
-- 2. 所有业务表新增 tenant_id，用 DEFAULT 默认值加列，MySQL 8 对存量行为 instant DDL。
-- 3. 唯一键改造为含 tenant_id 的复合键：username / md5 / 标签名 / 幂等键 / 配额主键
--    全部从「全局唯一」变为「租户内唯一」，这是多租户最直接的冲突点。
-- 4. 直接引用 app_user 的表把外键升级为复合外键 (tenant_id, user_id)，
--    数据库层保证 tenant_id 不能被写错；追加写表（audit_event / agent_execution）
--    保持无外键的既有约定，靠租户谓词与审计约束保证。
-- 5. app_user.id 仍为主键，但另加 (tenant_id, id) 唯一键供复合外键引用。
-- 6. 新的复合外键一律**另起名字**（fk_<表>_tenant_<目标>），不复用旧名。原因：
--    MySQL 在一条 ALTER 里先校验名字唯一性再应用 DROP，所以
--    「DROP FOREIGN KEY fk_x, ADD CONSTRAINT fk_x ...」会直接报
--    ERROR 1826 Duplicate foreign key constraint name。语义上也该换名——
--    约束从单列外键变成了带租户维度的复合外键。
--
-- 本迁移包含 DROP INDEX / DROP FOREIGN KEY：只删「约束」不删「数据」，
-- 且删除的旧约束均在本文件内以租户化版本重建。
--
-- 本迁移是**纯 DDL**（0 条 INSERT/UPDATE/DELETE）。迁移账号 ss_migrator 只持有
-- schema 级 DDL 与 flyway_schema_history 的 DML，没有业务表的写权限（见
-- ops/sql/README.md）。默认租户那一行因此不在这里种，改由 document-service 启动期的
-- DefaultTenantInitializer 幂等写入（用 app 账号），MigrationV10ContractTest 会拦住
-- 任何把 DML 塞回迁移的改动。

CREATE TABLE tenant (
  id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  slug           VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name           VARCHAR(120) NOT NULL,
  tier           VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  isolation      VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  owner_user_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_slug (slug),
  KEY idx_tenant_status_created (status, created_at, id),
  CONSTRAINT chk_tenant_tier CHECK (tier IN ('FREE','STANDARD','PRO','ENTERPRISE')),
  CONSTRAINT chk_tenant_isolation CHECK (isolation IN ('SHARED','SCHEMA','DEDICATED')),
  CONSTRAINT chk_tenant_status CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='租户控制面；存量数据归属默认租户，id 固定全 1 UUID';

ALTER TABLE app_user
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_app_user_username,
  ADD UNIQUE KEY uk_app_user_tenant_username (tenant_id, username),
  ADD UNIQUE KEY uk_app_user_tenant_id (tenant_id, id),
  ADD KEY idx_app_user_tenant_created (tenant_id, created_at, id);

ALTER TABLE app_user_role
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_app_user_role_user,
  ADD CONSTRAINT fk_app_user_role_tenant_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE refresh_token
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_refresh_token_user,
  ADD CONSTRAINT fk_refresh_token_tenant_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ai_quota
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (tenant_id, user_id),
  DROP FOREIGN KEY fk_ai_quota_user,
  ADD CONSTRAINT fk_ai_quota_tenant_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE document
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_document_user_md5,
  ADD UNIQUE KEY uk_document_tenant_user_md5 (tenant_id, user_id, md5),
  ADD UNIQUE KEY uk_document_tenant_id (tenant_id, id)
    COMMENT '供 chunk / document_tag_link 的复合外键引用',
  ADD KEY idx_document_tenant_user (tenant_id, user_id, created_at, id);

ALTER TABLE chunk
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_chunk_document,
  ADD CONSTRAINT fk_chunk_tenant_document FOREIGN KEY (tenant_id, document_id)
    REFERENCES document (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE document_tag
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_document_tag_user_normalized,
  ADD UNIQUE KEY uk_document_tag_tenant_user_normalized
    (tenant_id, user_id, normalized_name),
  ADD UNIQUE KEY uk_document_tag_tenant_id (tenant_id, id)
    COMMENT '供 document_tag_link 的复合外键引用';

ALTER TABLE document_tag_link
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_document_tag_link_document,
  DROP FOREIGN KEY fk_document_tag_link_tag,
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (tenant_id, document_id, tag_id),
  ADD KEY idx_document_tag_link_tenant_tag (tenant_id, tag_id, document_id)
    COMMENT '按标签过滤文档列表（搜索的 tag 参数）',
  ADD CONSTRAINT fk_document_tag_link_tenant_document FOREIGN KEY (tenant_id, document_id)
    REFERENCES document (tenant_id, id) ON DELETE CASCADE,
  ADD CONSTRAINT fk_document_tag_link_tenant_tag FOREIGN KEY (tenant_id, tag_id)
    REFERENCES document_tag (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE conversation
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD UNIQUE KEY uk_conversation_tenant_id (tenant_id, id)
    COMMENT '供 message 的复合外键引用',
  ADD KEY idx_conversation_tenant_user (tenant_id, user_id, last_message_at, id);

ALTER TABLE message
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_message_conversation,
  ADD CONSTRAINT fk_message_tenant_conversation FOREIGN KEY (tenant_id, conversation_id)
    REFERENCES conversation (tenant_id, id) ON DELETE CASCADE,
  DROP INDEX uk_message_client_request,
  ADD UNIQUE KEY uk_message_tenant_client_request
    (tenant_id, conversation_id, client_request_id);

ALTER TABLE agent_execution
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD KEY idx_agent_execution_tenant_user (tenant_id, user_id, started_at, id);

ALTER TABLE audit_event
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD KEY idx_audit_tenant_user (tenant_id, user_id, occurred_at, id);

ALTER TABLE reminder
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_reminder_user_idempotency,
  DROP INDEX uk_reminder_user_source,
  ADD UNIQUE KEY uk_reminder_tenant_user_idempotency
    (tenant_id, user_id, idempotency_key),
  ADD UNIQUE KEY uk_reminder_tenant_user_source
    (tenant_id, user_id, source_type, source_uid),
  ADD KEY idx_reminder_tenant_user_next (tenant_id, user_id, next_trigger_at, id);

ALTER TABLE reminder_trigger
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_reminder_trigger_planned,
  ADD UNIQUE KEY uk_reminder_trigger_tenant_planned
    (tenant_id, reminder_id, planned_trigger_at);
```

| 字段 | 说明 |
|---|---|
| `tenant.id` | 租户主键，UUID；默认租户固定为全 1 UUID，存量数据全部归属它 |
| `tenant.slug` | URL / 连接串使用的短标识，全局唯一 |
| `tenant.tier` | `FREE` / `STANDARD` / `PRO` / `ENTERPRISE`，决定配额与隔离级别 |
| `tenant.isolation` | `SHARED` / `SCHEMA` / `DEDICATED`；本期只实现 `SHARED` 的强制过滤 |
| `tenant.status` | `TRIAL` / `ACTIVE` / `PAST_DUE` / `SUSPENDED` / `DELETED` |
| `tenant.owner_user_id` | 租户所有者；不做外键，管理员身份以审计事实为准 |

设计要点：

- **默认租户是存量数据的唯一落点**。`DEFAULT` 默认值加列让 MySQL 8 对存量行做 instant DDL，无需逐表 UPDATE；回滚时只需删掉 `tenant` 行与各表的 `tenant_id` 列。
- **唯一键全部租户化**。`uk_app_user_username` 从全局唯一改为 `(tenant_id, username)`，意味着两个租户可以各有一个 `alice`；这是多租户与单租户最本质的语义差异，所有按用户名查询的仓库方法都必须带租户条件。
- **复合外键防写错**。`app_user` 增加 `(tenant_id, id)` 唯一键供引用；`app_user_role` / `refresh_token` / `ai_quota` 的外键升级为 `(tenant_id, user_id)`，数据库层保证子行的租户与父行一致；`chunk` / `message` / `document_tag_link` 同理。
- **追加写表不加外键**。`audit_event` 与 `agent_execution` 保持既有约定（用户可能已软禁用或数据已归档），靠 `tenant_id` 谓词与只追加约束保证隔离。
- **本期不做 Schema / Dedicated 路由**。`tenant.isolation` 列先落盘，三级隔离路由是 P1 的范围；SHARED 级别靠应用层强制租户谓词，数据库层靠复合外键防写错。

## 3. Redis Key 设计

Redis 不是业务事实来源，所有关键数据在 MySQL 或 PGVector 有权威副本。Redis 丢失时优先降级性能和短期协调能力，不允许悄悄改变业务状态或绕过预算。

| Key 格式 | 类型 | TTL | 用途 | 写入方 | 读取方 | 丢失最坏后果 |
|---|---|---|---|---|---|---|
| `upload:md5:{userId}:{md5}` | String（documentId） | 24 小时 | 秒传去重索引；命中后仍需校验数据库状态与用户所有权 | `document-service` | `document-service` | 秒传退化为数据库 `uk_document_user_md5` 查询；高并发时数据库压力上升，但不会越权或产生第二份文档 |
| `upload:lock:{userId}:{md5}` | Redisson Lock（看门狗续期） | 租约 30 秒，任务完成释放 | 保护“查重 -> 建上传记录”的临界区，防止并发重复初始化 | `document-service` | `document-service` | 并发初始化可能同时到达数据库；唯一索引只允许一条成功，其余请求必须重读并返回已有 `documentId`，否则会出现 409 或重复解析风险 |
| `upload:session:{uploadId}` | Hash（objectKey、partSize、minioUploadId、expiresAt） | 45 分钟 | 保存分片上传会话，支持完成与取消 | `document-service` | `document-service` | 正在上传的会话无法完成，用户重新初始化；已存在对象可能成为孤儿，由定时清理任务回收 |
| `qa:{userId}:{docScopeHash}:{historyHash}:{questionHash}:{promptVersion}` | String（JSON） | 10 分钟 | 问答结果缓存；只缓存 `budgetStatus=OK` 且 `confidence>=0.55` 的答案 | `agent-gateway` | `agent-gateway` | 缓存命中率下降、延迟和 LLM 成本上升；答案正确性不受影响，因为下次会重新检索和生成 |
| `auth:revoked:jti:{jti}` | String（`1`） | 该 access 令牌的剩余有效期，上限 15 分钟 | 主动登出时吊销单个 access 令牌；三处自验签点每次请求都要查它的存在性 | `user-service` | `agent-gateway`、`document-service`、`reminder-service` | 已登出的 access 令牌在其自然过期前仍可用，最坏多活 15 分钟；刷新令牌已吊销，因此该 access 过期后无法续期 |
| `auth:revoked:user:{userId}` | String（允许的 `iat` 下界，UTC 毫秒） | 等于 access TTL（15 分钟） | 改密或管理员停用时的用户级批量吊销水位：`token.iat` 早于水位即拒绝，覆盖无法逐个枚举 `jti` 的在途 access | `user-service` | `agent-gateway`、`document-service`、`reminder-service` | 该用户在水位之前签发的 access 最多再存活 15 分钟；其刷新令牌已在数据库置为 `REVOKED`，续期一律失败，所以窗口结束后会话必然中断 |
| `rate:ip:{ipHash}:{windowStart}` | String（原子计数） | 120 秒 | 注册接口的来源 IP 固定窗口限流，防止批量建号；key 中只写 IP 哈希，不写原始 IP | `user-service` | `user-service` | 短时间放行更多注册请求；用户名唯一键与口令强度策略仍然兜底，不产生越权或数据损坏 |
| `rate:user:{userId}:{windowStart}` | String（原子计数） | 120 秒 | 用户级固定窗口限流，覆盖问答、上传、提醒写操作 | `agent-gateway` / 各服务网关 | `agent-gateway` / 各服务网关 | 限流窗口重置，短时间允许额外请求；可能增加 LLM 成本，不直接导致数据损坏 |
| `idem:{userId}:{operation}:{key}` | String（JSON：请求指纹、状态、原响应摘要） | 24 小时 | 写接口幂等结果；同一指纹返回原结果，不同指纹返回 409；`operation` 如 `UPLOAD_INIT`、`UPLOAD_COMPLETE`、`ASK`、`REMINDER_CREATE`、`USER_ROLE_UPDATE`、`USER_STATUS_UPDATE`、`CHANGE_PASSWORD`，S2 文档域补 `DOCUMENT_RENAME`、`DOCUMENT_TAG_UPDATE`、`DOCUMENT_DELETE`、`DOCUMENT_RETRY`、`DOCUMENT_REINDEX`，S3 课表导入补 `SCHEDULE_IMPORT`（请求指纹 `sha256(文件 SHA-256 \| fallbackTimezone \| leadMinutes)`，落库的 `reminder.idempotency_key` 另用 `sha256hex(requestKey \| uid)` 以覆盖一次导入的多行），S4 提醒状态变更补 `REMINDER_UPDATE`（指纹 `sha256(reminderId \| status \| version)`，同指纹重放**不重复推进 `version`**，同 key 不同指纹返回 409 `REM-CONFLICT-001`）；`REGISTER` 是唯一没有 `userId` 的匿名写接口，key 第一段改用归一化后的 `username`。**S2/S3/S4 都不新增 Redis key 类别**，只扩 `operation` 取值和下面 `mq:idem` 的 topic 前缀 | `agent-gateway`、`document-service`、`reminder-service`、`user-service` | 对应写接口 | 可能重复执行一次请求；数据库唯一键、文档状态机和消息 `client_request_id` 仍提供最终兜底，但上传初始化可能多创建一次会话，必须通过 `uk_document_user_md5` 收敛；重复注册会被 `uk_app_user_username` 拦成 409，不会建出两个账号 |
| `mq:idem:{topic}:{messageId}` | String（`1`） | 7 天 | 消费者幂等标记；成功处理后写入，业务层仍用数据库唯一约束兜底。S2 新增两个 topic 前缀 `vector-change-requested`、`vector-change-completed`；S4 提醒消费者使用前缀 `reminder-due`，且**它只是加速层**——发布失败重投会给出新 `msgId`，同一次 occurrence 的最终收敛点是 `uk_reminder_trigger_planned` | `document-service`、`audit-service`、`reminder-service`、`ai-python` worker | 对应消费者 | 重复消费；解析分块、审计主键和消息唯一键会阻止重复业务写入；提醒触发仍可能重复，由 `reminder:fired` 补充 |
| `agent:budget:{traceId}` | Hash（`tokensUsed`、`toolCallsMade`、`roundsUsed`、`tokenBudget`、`maxToolCalls`、`maxRounds`、`deadlineAt`） | 30 分钟 | Agent 的原子预算计数器，支撑 Python 在每次 LLM/工具调用前硬停止 | Java 初始化，Python 原子递增 | Python 读取并校验，Java 终态核对 | Python 若无法读取/重建计数，必须 fail-closed 停止 Agent；否则可能超过 token、工具或轮次预算，造成成本和审计失控 |
| `agent:budget:session-tokens`（前缀由 `budget_key_prefix` 决定） | Hash（`callsUsed`、`tokensUsed`，另有只读回显字段 `callLimit`、`tokenLimit`） | **无 TTL** | 全局累计额度（配置给定的调用次数与 token 上限），与上面的**每请求**预算正交：前者管一次问答，后者管整轮实验窗口。每个外呼出口在字节离开本机前 `authorize_call` 预订、返回后 `settle_call` 用真实 `usage` 结算（D-118 ②）。Python 启动时把配置上限写入 `callLimit` / `tokenLimit`，管理面只读展示，**不提供在线重置** | `ai-python`（API 与 Parse Worker 共用同一本账）；启动时补写限额字段 | `ai-python`；`agent-gateway` 管理面只读 | 重置动作是**人工**的（删键或改配置），因此不设 TTL——自动过期等于把额度上限变成滑动窗口，与「本轮上限」的语义冲突。读不到账本时**fail-closed**（503 / rerank 退回融合次序 / worker 可重试），宁可少花一次也不裸奔；反过来**离线 Provider 组合下该键一次都不读写**（账本对象根本不构造，D-113），所以本地与 CI 永不触碰它 |
| `agent:kill-switch` | String（`1` 为开；键不存在为关） | 无 TTL | 全局 AI 关闭开关；S9 起由管理 API 写入，读路径不变（Java 30s 缓存、Python 5s 缓存） | `agent-gateway`（管理 API） | `agent-gateway`、`ai-python` | 键丢失等于关闭 Kill Switch（流量恢复），因此依赖 Redis 持久化与运维权限；状态语义保持「键在即开」 |
| `agent:kill-switch:meta` | Hash（`updatedAt` UTC 毫秒、`updatedBy` UUID） | 无 TTL | 记录最近一次管理 API 开关动作，供管理面展示「谁在何时改的」；**不是状态事实** | `agent-gateway`（管理 API） | `agent-gateway` | 丢失只影响展示；开关状态仍由 `agent:kill-switch` 决定 |
| `agent:quota:usage:{userId}:{yyyy-MM-dd}` | Hash（`questions`、`tokens`） | 48 小时 | S9 按用户日配额计数；`questions` 在准入时原子自增，`tokens` 在拿到真实用量后累加 | `agent-gateway` | `agent-gateway` | 丢失等于当日配额从 0 重新计；配额是业务约束而非安全边界，接受该退化 |
| `agent:state:{traceId}` | String（`RUNNING`/终态摘要） | 30 分钟 | 在途状态展示与排障；不替代 `agent_execution` 终态快照 | `agent-gateway` | `agent-gateway`、运维查询 | 运维看不到在途状态；终态执行、消息和审计仍可从事务数据和 MQ 中恢复 |
| `audit:relay:cursor` | String（`recorded_at` + `id`） | 无 TTL | 审计 outbox relay 的扫描游标；`agent_execution.audit_outbox` 才是耐久事实 | `agent-gateway` relay | `agent-gateway` relay | 游标丢失后从保留窗口起点重放；`audit-service` 按 `eventId` 去重，不丢事件，只增加重复投递 |
| `reminder:fired:{reminderId}:{occurrenceStartUtc}` | String（`1`） | 48 小时 | 提醒 occurrence 触发幂等，防止重复扫描产生重复 due 事件 | `reminder-service` | `reminder-service` | 可能重复发送 due 事件；消费者/前端需容忍至少一次重复，且不能把它当作用户数据丢失 |

补充原则：

- Key 中只允许出现 `userId`、`traceId`、哈希值、版本号和业务 ID，不允许写手机号、邮箱、问题原文等 PII。
- 秒传优先查 Redis，未命中必须回源 MySQL `uk_document_user_md5`；Redis 不可作为唯一判重依据。
- 写接口幂等键统一使用 `idem:{userId}:{operation}:{key}` 缓存请求指纹和原响应摘要；Redis 丢失后由数据库唯一键/状态机兜底，不能因为缓存缺失而跳过幂等检查。
- 解析锁使用 Redisson 看门狗，持有超过任务上限会触发告警；锁丢失时数据库唯一约束兜底。
- 问答缓存键包含用户、文档范围、历史摘要和 Prompt 版本，任何一项变化都不得复用旧答案；重新索引后通过 `indexVersion` 使旧缓存自然失效。
- 预算计数器丢失时，Python 不能“当作 0 继续”或“当作无限”；必须使用请求中的上限重新初始化本地计数，或在无法安全重建时拒绝执行。
- 限流和审计投递属于可用性与合规权衡：限流短时可退化，审计丢失不可接受；审计依赖 `agent_execution.audit_outbox` 的耐久行与 MQ 重投，而不是只依赖 Redis。
- 吊销检查读 Redis 失败时**有意 fail-open**：签名、有效期和状态都通过的 access 继续放行，同时打 WARN 和指标告警。理由是吊销的自然上限就是 access TTL（15 分钟），而 fail-closed 会让 Redis 抖动直接演变成全站 401；这个权衡成立的前提是 access TTL 已经压到分钟级，TTL 若拉长必须重新评估。
- 与读取相反，吊销的**写入**不得静默失败：刷新令牌吊销以数据库 `status='REVOKED'` 为权威事实，Redis 水位只是加速在途 access 的收敛，写失败必须记 WARN 并可由重放或自然过期兜底。任何情况下都不能因为 Redis 写失败而回滚数据库吊销。
- 登录失败限流当前是进程内 LRU（单机边界，已登记为缺陷）；注册限流必须走 Redis，因为它是匿名写接口、没有 `userId` 可归属，且多实例下进程内计数会各放一份额度。
- access 与 refresh 的生命周期是同一份配置的两侧取值，`auth:revoked:*` 的 TTL 必须由 access TTL 推导，不允许在服务和 Redis key 上各写一套硬编码时长。

## 4. PGVector 集合设计

### 4.1 集合 schema

使用 PostgreSQL + PGVector，一张表对应一个固定 embedding 维度的集合。本期模型选择 `BAAI/bge-m3` 的 1024 维 dense embedding；模型名和维度登记在 `ai-python` 的模型配置/模型注册表中，不在 Java 或数据库里硬编码散落值。

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunk_embedding_1024 (
  vector_id      UUID PRIMARY KEY,
  document_id    UUID NOT NULL,
  chunk_index    INT NOT NULL CHECK (chunk_index >= 0),
  content        TEXT NOT NULL,
  embedding      vector(1024) NOT NULL,
  metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
  index_version  TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, index_version, chunk_index)
);

CREATE INDEX idx_chunk_embedding_document
  ON chunk_embedding_1024 (document_id);

CREATE INDEX idx_chunk_embedding_document_version
  ON chunk_embedding_1024 (document_id, index_version);

CREATE INDEX idx_chunk_embedding_hnsw
  ON chunk_embedding_1024
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
```

字段约定：

| 字段 | 说明 |
|---|---|
| `vector_id` | Python 通过 `UUIDv5(namespace, documentId + indexVersion + chunkIndex)` 生成的确定性 ID，用于幂等 upsert 与 MySQL `chunk.vector_id` 映射 |
| `document_id` | Java 授权上下文中的文档 UUID；不能由前端直接拼接检索条件 |
| `chunk_index` | 同一文档版本内从 0 开始的顺序号 |
| `content` | 用于引用展示的原文片段；不放进日志 |
| `embedding` | 1024 维向量，余弦距离检索 |
| `metadata` | `page_no`、`token_count`、`content_hash`、`embedding_model`、`file_name` 等过滤/展示信息 |
| `index_version` | 与 `document.index_version` 对应；新旧版本共存，检索只取当前版本 |

### 4.2 按 document_id 过滤的检索方式

Java 先完成授权和状态检查，把允许检索的 `(documentId, indexVersion)` 对传给 Python；Python 不接受前端传入的任意文档 ID。示意 SQL：

```sql
SELECT
  vector_id,
  document_id,
  chunk_index,
  content,
  metadata,
  1 - (embedding <=> :query_embedding) AS score
FROM chunk_embedding_1024
WHERE (document_id, index_version) IN (
  SELECT * FROM unnest(:document_ids::uuid[], :index_versions::text[])
)
ORDER BY embedding <=> :query_embedding
LIMIT :top_k;
```

- `document_id` 等值过滤使用 `idx_chunk_embedding_document`/`idx_chunk_embedding_document_version`。
- `embedding <=> :query_embedding` 使用余弦距离，分数由 `1 - distance` 给出。**名次与分数解耦（D-104 的语义注记）**：S5 的 RRF 融合与 S6 的可选第三方 rerank 只决定引用块的**先后次序**，既不改写也不重新计算这个 `score`；`confidence = max(被引用块的向量相似度)` 并 clamp 到 `0..1`，实现上必须**显式取最大值**而不是读排序后的首元素（首元素现在是名次第一位，不再保证相似度最高）。只被词法通道命中、向量通道没给的块对外 `score = 0.0` 且**不参与取最大值**（D-84），所以「引用次序与 score 不单调」是正常形态，不是数据错乱。
- 过滤后召回不足时，不能放宽到未授权文档；只能返回 `LOW_CONFIDENCE` 或请求用户缩小问题范围。
- PGVector 只保存 `document_id`，不保存 `userId`；授权责任在 Java，Python 只执行已授权的范围。
- S2 的向量删除完全复用上述两个索引，**不改 PG DDL**：`DELETE_DOCUMENT` 谓词是 `WHERE document_id = ?`（全部版本，走 `idx_chunk_embedding_document`），`DELETE_VERSION` 谓词是 `WHERE document_id = ? AND index_version = ?`（走 `idx_chunk_embedding_document_version`）。两个作用域都覆盖 `vector_id` 的生成规则 `UUIDv5(documentId, indexVersion, chunkIndex)`，因此删除后同一批 `vector_id` 可以被幂等重写。
- `remainingRows` 与 `vectorRows` 必须在**同一事务内**、删除或写入之后 `SELECT COUNT(*)` 得出，不能提交后再查：事后查询会把并发写入的中间态当成确认值回投，而 Java 一旦收下就再也无法区分「确认错了」与「真的不一致」。

### 4.3 embedding 维度的决策与变更

- **决策方：** AI 数据面负责人（Python 模型/检索 Owner）决定 embedding 模型和维度，并把选择登记在模型注册表；架构 Owner 审核存储与成本影响，Java 只记录 `index_version`，不决定维度。
- **维度匹配：** `chunk_embedding_1024.embedding` 的维度必须与模型配置一致，CI 用配置校验 DDL 中的 `vector(N)`，禁止运行时把一个 1536 维向量写进 1024 维列。
- **维度变更：** 不能直接 `ALTER COLUMN embedding TYPE vector(1536)` 覆盖旧索引。采用新集合表（如 `chunk_embedding_1536`）：
  1. 新建目标维度的集合和 HNSW 索引；
  2. 异步重嵌入全部文档，写入新的 `index_version`，旧版本保持可读；
  3. 校验抽样检索质量、引用和延迟；
  4. 切换 Python 配置的活动集合名，随后清理旧版本；
  5. 保留旧集合至少一个发布周期，支持回滚。
- 迁移期间检索必须固定使用一个活动集合/版本；不允许同一次回答混用不同维度的向量。

### 4.4 词法召回索引

向量通道只回答「语义像不像」，回答不了「这个词面在不在」。精确术语（`vector_id`、`RRULE`、`ON CONFLICT`）、专有名词和代码标识符是向量通道的稳定盲区，因此在**同一张表**上增加一条词法召回通道。

#### 4.4.1 DDL：只加一个表达式 GIN 索引

`ai-python/migrations/002_chunk_lexical_index.sql` 的确切形态：

```sql
-- 词法召回通道：表达式 GIN，零新列、零数据改写、不动 HNSW。
-- 汉字逐字插空格后交给 'simple' 配置，因为该镜像没有中文分词扩展
-- （pg_jieba / zhparser 不在 pg_available_extensions 中，实测 E11）。
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_lexical
  ON chunk_embedding_1024
  USING gin (
    to_tsvector('simple'::regconfig,
                regexp_replace(content, '([一-鿿])', ' \1 ', 'g'))
  );
```

`001_chunk_embedding_1024.sql` **不改**，表结构**不改**（仍是 `vector_id / document_id / chunk_index / content / embedding / metadata / index_version / created_at` 七加一列），HNSW 索引**不重建**。回滚是单条 `DROP INDEX idx_chunk_embedding_lexical`，不触碰任何一行数据。

**为什么不是 `STORED` 生成列。** 生成列写法（`ALTER TABLE … ADD COLUMN lex tsvector GENERATED ALWAYS AS (…) STORED` + `GIN(lex)`）在本实例上语法与易变性都成立——被索引的表达式 `to_tsvector(regconfig,text)`、`regexp_replace(text,text,text,integer)` 实测全是 `IMMUTABLE`，28 行真实数据上加列被接受、`attgenerated='s'`、逐行非空。但它**代价不对称**：

| 观测 | 结果 |
|---|---|
| 加 `STORED` 生成列后，表的 `relfilenode` | `17320 → 17330`（堆全量重写） |
| 同一操作后，HNSW 索引的 `relfilenode` | `17327 → 17336`（**向量索引被全量重建**） |
| 事后索引状态与向量检索 | `indisvalid = true`，`ORDER BY embedding <=> …` 仍走 `Index Scan using …_hnsw` |

功能不受损，但一次「加一条召回通道」的改动被放大成「重写整表 + 重建向量索引」：`chunk` 行数由 `chunk_size_chars=800` 与 50 MiB 文件上限决定，是**无界**的，规模上去后这是一次长时阻塞操作，且此后每次 `content` 变更都要重写整行。表达式 GIN 没有这两个问题，代价换成两条有界的小账，见 4.4.3。

#### 4.4.2 检索 SQL：授权谓词必须与向量通道同一条

Java 侧的授权与状态检查结论（`(documentId, indexVersion)` 对的集合）原样喂给两个通道，**Python 不为词法通道单独构造范围**（对应 D-87 红线）：

```sql
SELECT
  vector_id,
  document_id,
  chunk_index,
  content,
  metadata
FROM chunk_embedding_1024
WHERE (document_id, index_version) IN (
  SELECT * FROM unnest(:document_ids::uuid[], :index_versions::text[])
)
  AND to_tsvector('simple'::regconfig,
                  regexp_replace(content, '([一-鿿])', ' \1 ', 'g'))
      @@ to_tsquery('simple'::regconfig, :lexical_query)
ORDER BY ts_rank(to_tsvector('simple'::regconfig,
                             regexp_replace(content, '([一-鿿])', ' \1 ', 'g')),
                 to_tsquery('simple'::regconfig, :lexical_query)) DESC,
         vector_id
LIMIT :lexical_limit;
```

`:lexical_query` 在同一条语句里出现**两次**（`WHERE` 与 `ORDER BY`），必须绑定同一个参数，不允许各生成一份。

- `:lexical_query` 由 Python 用与 4.4.1 **同一套切分口径**生成，成形规则见 D-97：CJK 连写段取**相邻二元组**（`学习` → `'学' <-> '习'`），ASCII 段**整段保留**（`config.py`、`3.5-alpha` 各成一个引号段），所有短语一律用 ` | ` 连接，**不使用 ` & `**。词位只允许 `\u4e00-\u9fff` 单字与 `[a-z0-9]+(?:[._-][a-z0-9]+)*` 的 ASCII 整段（**连接符留在词内**：`config.py` 在 `simple` 下是一个词位，拆开检索命中数为 0 而真值为 5000，E13/E14，口径见 D-95；点、下划线、连字符三种连接符的两侧同形已逐一实测，`'vector_id'` 与 `'3.5-alpha'` 均命中，E28。`+` 只有正文侧证据，本期不作词内字符），运算符只允许 `<->` / `|`，其余一律拒绝；`[a-z0-9]` 这个字母表的前提是**查询侧先 `lower()`**——`simple` 在正文侧与查询侧**都**做小写折叠（`'HELLO'` → `'hello'`，混合大小写正文 `Config.Py` 用小写词位命中 6666 = 真值，E38），所以 `lower()` 是**必要步骤**而不是可选优化（本模块的切分正则只认小写，不折叠就会把 `Config` 当分隔符整段丢掉）。发出的短语数受**模块常量**夹紧（`text/lexical.py` 的 `MAX_PHRASES = 64`，不是配置项——D-90 的四键集合因此不变；`to_tsquery` 有解析期硬上限，20000 个词位会 `stack depth limit exceeded`，E36）。**字符类必须与索引表达式里的 `[一-鿿]` 逐字相同**——扩展 A 区（U+3400–U+4DBF）不在索引类内，会被 `simple` 粘成多字词位（`㐀㑳` → 一个词位，E18），所以类外汉字一律当分隔符处理、绝不发成词位（D-96）。查询侧**不做** NFKC / 全半角归一（`simple` 不折叠宽度，归一后与正文词位不同形，E29）。**PII 掩码占位符不贡献词位**：进入词法通道的问句已经过掩码，可能字面含有 `[PHONE]` / `[ID_CARD]` / `[EMAIL]`，若不先整体替换为空格就会发出 `'phone'` / `'id_card'` 这类与用户意图无关的 OR 分支（占位符集合取自 `pii/masker.py` 的 `PLACEHOLDERS`，与词法模块同源，不得各写一份）。`to_tsquery` 的入参是语法而非文本（悬挂运算符会 `ERROR: syntax error in tsquery`、引号内含 `_`/`-` 会被解析器二次拆词，E23；`websearch_to_tsquery` 虽然抗注入但发不出 `<->`、会把 `AND` 降成词位、把 `幂等性` 发成恒不命中的多字词位，E26/E27），必须当作不可信输入处理。
- 词法通道**不返回分数**：`SELECT` 列表里没有 `1 - (embedding <=> …)`，也没有 `ts_rank` 列，因此没有任何字段可以冒充相似度。融合阶段只用它的**名次**（RRF，`k=60`），只被词法命中的块对外 `score = 0.0`，且不参与 `confidence` 取最大值（对应 D-82/D-83/D-84）。覆盖度（`lexical-coverage-v1`）由 Python 用**已返回的 content** 计算，不能取自 `ts_rank`：实测 rank 既随命中词组集合移动、也随词频移动，9333 个命中行只有 3 个不同 rank 值（E32/E33），它不是计数。
- 排序键是 `ts_rank(…) DESC, vector_id`（D-98）。这里推翻本节上一版的「`ORDER BY vector_id`」写法，原因不是相关性而是**计划形态不可推理**：只按 `vector_id` 排时，命中密集的查询会被规划成 `Limit → Nested Loop Semi Join →〔Index Scan using …_pkey + Filter〕`，**完全不使用词法 GIN**，其成本由「凑满 k 条要扫多少行」决定而与授权范围无关（E30）；`ts_rank DESC, vector_id` 在零命中、窄授权范围、密集命中三种分布下都稳定为 `Limit → Sort → Nested Loop →〔HashAggregate ← Function Scan on unnest〕+〔Bitmap Heap Scan → BitmapAnd(idx_chunk_embedding_document_version, idx_chunk_embedding_lexical)〕`，授权 btree 与词法 GIN 同时命中，`Recheck Cond` 里逐候选行重算表达式来收敛真正的邻接与词序（E31；实测 `学 <-> 习` 命中数与 `LIKE '%学习%'` 真值在 30000 行语料上完全相等，反序与隔距样本正确排除）。`vector_id` 作为第二排序键是**必需**而非装饰：rank 并列极重（9333 行只有 3 个不同值、最大并列组 5333），去掉它前 N 条就不可复现（E32）。代价：每行在重检与投影里各多算一次 `to_tsvector(regexp_replace(content…))` + `ts_rank`（E22），且 `Sort` 要吃下授权范围内的全部命中行——**这一段未压测**，不得声称成本可接受。
- 召回不足**不放宽**：与 §4.2 同一条规则——不得退化成「去掉授权谓词再查一次」，只能返回 `LOW_CONFIDENCE` 或请用户缩小范围。

#### 4.4.3 与写入侧、删除侧的关系

| 链路 | 影响 | 依据 |
|---|---|---|
| 写入（`parse_worker/vector_writer.py` 的 `INSERT … ON CONFLICT (vector_id) DO UPDATE`） | **零改动**。列清单是显式的且不包含新列，表达式索引由 PG 在行写入时自行维护 | D-79 |
| 删除（S2 的 `DELETE_DOCUMENT` / `DELETE_VERSION`） | **零改动**。两个谓词都只按 `document_id`(+`index_version`) 走既有的 `idx_chunk_embedding_document` / `idx_chunk_embedding_document_version`；GIN 索引条目随堆元组消失 | §4.2 |
| `remainingRows` / `vectorRows` 同事务确认 | **语义不变**。计数仍是同一事务内的 `COUNT(*)`，索引不参与计数 | §4.2 末条 |
| 重检成本 | 表达式索引的 `Recheck Cond` 会**逐候选行重算** `to_tsvector(regexp_replace(content…))`；`ORDER BY ts_rank(…)` 使同一表达式在投影里**再算一次**（E22/E31）。候选集已被授权谓词限制在单个用户的文档内，且受 `retrieval_lexical_candidate_limit` 夹紧；**这一段未压测** | E9/E22/E31 |
| 索引命中条件 | 查询里的表达式必须与索引定义**同形态**：省略 `::regconfig` 仍命中（解析期归一），但 `regexp_replace` 少写第 4 个参数 `'g'` 就退化为 `Seq Scan`；`ORDER BY` 里的那一份与 `WHERE` 里的那一份必须同形，否则只是把同一个脆弱点复制了一遍 | E8/E21 |

最后一行是这个方案唯一的结构性脆弱点，因此它必须被测试钉住而不是被口头约定：Python 侧把该表达式存成**单一常量**（与 002 的 DDL 文本同源），且检索语句里 `WHERE` 与 `ORDER BY` **引用同一个常量、同一个 `:lexical_query` 参数只绑定一次**；`tests/test_migrations.py` 增加一条断言「002 的索引表达式与查询侧常量的函数名、参数个数、flags、`regconfig` 逐字一致」；同时 `test_migrations.py:5` 现在硬编码只读 001 文件，必须扩成遍历 `migrations/*.sql`，否则 002 完全不进门禁。

#### 4.4.4 不变的红线

- **PG 里没有 `user_id`。** 词法通道读的是同一张表、同一套授权入参，不引入任何按用户过滤的列或索引；`tests/test_migrations.py:31` 的 `assert "user_id" not in sql` 必须对 002 同样生效。
- **维度仍然硬绑 1024**（`config.py:117`）。S5 不换模型、不换维度、不新增扩展；`pg_trgm` 虽 available 但**不安装**——实测它不能作为召回通道（`LIKE '%学习%'` 不走 `gin_trgm_ops`，三个相似算子命中数为 0 / 1613 / 0，而子串真值为 8979），理由与未解释项见 §1.6.1 的 E10。
- **正文不进日志。** `content` 只在检索结果里向下游传递，与 §4.1 字段约定一致；PII 掩码仍在召回之后、进上下文之前统一执行，词法通道不得绕过这一段。
- **词法通道对索引字符类之外的汉字没有召回，且这种缺失是静默的。** `[一-鿿]` 只覆盖 U+4E00–U+9FFF，扩展 A 区（U+3400–U+4DBF）与兼容表意区（U+F900–U+FAFF）的字符既不会被索引侧拆成单字，查询侧也一律按分隔符丢弃（D-96），因此「生僻字精确术语」在 S5 是**结构性缺失**而不是缺陷：它不报错、不降级、不产生任何观测信号。能观察它的唯一手段是 S6 的真实标注集，所以本期把「扩展区汉字召回」与「真分词器（`zhparser` / `pg_jieba`，本镜像 `pg_available_extensions` 里根本不存在，E11）」一起登记给 S6，不得在 S5 结论里声称已覆盖。`pg_trgm` 保持**不安装**，理由见上一条与 E10。
- **连接符只能是 ` | `，这不是风格而是召回下限。** 实测掺进一个语料中不存在的词位后，三条件 `&` 命中数为 **0** 而 `|` 仍为 **9333**（E25）——「问句里有一个术语没命中就整条判死」会让词法通道在最需要它的长问句上静默失效。同理，**不得**发多字 CJK 词位（恒 0 命中，E26）、**不得**对查询做全半角归一（与正文词位不同形，E29）、**不得**用 `websearch_to_tsquery` 代替自建引号转义（发不出 `<->`，E27）。
- **`ts_rank` 只作排序键，永远不得被解释或传递为相关性。** 它既随命中词组集合移动也随词频移动，9333 个命中行只有 3 个不同值、最大并列组 5333（E32/E33）；`SELECT` 列表不含该列，对外 `score` 对纯词法命中恒为 `0.0`（D-82/D-83），覆盖度另由 Python 用返回的 content 计算。

---

拦截的是什么问题：拦截“文档状态、提醒重复展开、审计被覆盖、Redis 状态被当成事实、向量维度漂移”这类存储层错误，确保跨语言链路的每个事实都有唯一存储位置和可验证的恢复路径。
