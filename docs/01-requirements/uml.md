# UML 建模与关键流程

## 1. 类图：业务实体与所有权

**这张图在评审会上回答：哪些数据是强拥有关系、文档状态以哪张表为准、Agent 执行与审计如何关联，以及分块和 PGVector 的边界在哪里。**

```mermaid
classDiagram
    direction LR

    class User {
        +String id
        +String email
        +String passwordHash
        +Instant createdAt
    }

    class Document {
        +String id
        +String userId
        +String fileName
        +String md5
        +long sizeBytes
        +String objectKey
        +DocumentStatus status
        +int retryCount
        +String errorCode
        +String indexVersion
        +Instant createdAt
        +Instant updatedAt
    }

    class Chunk {
        +String id
        +String documentId
        +int chunkIndex
        +String vectorId
        +String contentHash
        +int tokenCount
        +String indexVersion
    }

    class Conversation {
        +String id
        +String userId
        +String title
        +List~String~ documentScopeIds
        +Instant lastMessageAt
        +Instant createdAt
    }

    class Message {
        +String id
        +String conversationId
        +MessageRole role
        +String content
        +int tokenCount
        +String traceId
        +Instant createdAt
    }

    class Reminder {
        +String id
        +String userId
        +String title
        +String rrule
        +List~String~ exdates
        +String timezone
        +Instant nextTriggerAt
        +ReminderStatus status
        +String sourceType
        +String sourceRef
        +Instant createdAt
    }

    class AuditEvent {
        +String id
        +String traceId
        +String userId
        +String eventType
        +String agentName
        +String payloadRedacted
        +Instant occurredAt
    }

    class AgentExecution {
        +String id
        +String traceId
        +String conversationId
        +String requestMessageId
        +String responseMessageId
        +ExecutionStatus status
        +int tokenUsed
        +int toolCallsMade
        +int roundsUsed
        +boolean cacheHit
        +BudgetStatus budgetStatus
        +long latencyMs
        +Instant startedAt
        +Instant finishedAt
    }

    class DocumentStatus {
        <<enumeration>>
        UPLOADED
        PARSING
        INDEXED
        FAILED
        DEAD
    }

    class MessageRole {
        <<enumeration>>
        USER
        ASSISTANT
        SYSTEM
    }

    class ReminderStatus {
        <<enumeration>>
        ACTIVE
        PAUSED
        COMPLETED
        CANCELLED
    }

    class ExecutionStatus {
        <<enumeration>>
        RUNNING
        SUCCESS
        BUDGET_EXHAUSTED
        FAILED
    }

    class BudgetStatus {
        <<enumeration>>
        OK
        LOW_CONFIDENCE
        BUDGET_EXHAUSTED
    }

    User "1" --> "0..*" Document : owns
    User "1" --> "0..*" Conversation : owns
    User "1" --> "0..*" Reminder : owns
    User "1" --> "0..*" AuditEvent : acts

    Document "1" *-- "0..*" Chunk : contains
    Conversation "1" *-- "0..*" Message : contains
    Conversation "1" --> "0..*" AgentExecution : records

    Document "0..*" --> "0..*" Conversation : visible-scope
    Message "0..1" --> "0..*" AgentExecution : triggers
    AgentExecution "0..*" --> "0..*" AuditEvent : correlated-by-traceId

    Document --> DocumentStatus
    Message --> MessageRole
    Reminder --> ReminderStatus
    AgentExecution --> ExecutionStatus
    AgentExecution --> BudgetStatus
```

关键约束：

- `Document -> Chunk`、`Conversation -> Message` 是强拥有关系，文档或会话被移除时不得留下孤儿分块或消息；`User`、`Document`、`Conversation`、`Reminder` 都以 `userId` 作为所有权边界。
- `AgentExecution` 是每次问答尝试的执行事实，必须记录 `traceId`、`tokenUsed`、`toolCallsMade`、`roundsUsed` 和四种状态 `RUNNING/SUCCESS/BUDGET_EXHAUSTED/FAILED`；重试会产生新的执行记录，不能覆盖旧记录。
- `AuditEvent` 是不可变追加表，通过 `traceId` 与执行记录关联，不依赖外键级联；它不能因为删除会话、消息或执行记录而消失。
- PGVector 中保存 `content + embedding + metadata`，MySQL `Chunk` 只保存控制面需要的分块元数据与 `vectorId`；`vectorId` 是跨存储的稳定映射，不直接拿数据库自增主键充当向量主键。
- `Conversation.documentScopeIds` 是逻辑访问范围，不是物理多对多关联表；检索前仍由 Java 按 `userId` 和文档状态复核，不能只信任前端传入的 ID 列表。
- `BudgetStatus` 中的 `LOW_CONFIDENCE` 与 `BUDGET_EXHAUSTED` 不是执行状态；低置信度可以是 `SUCCESS`，预算耗尽则同时体现在 `AgentExecution.status` 和 `budgetStatus`。

## 2. 序列图一：文档上传到解析完成

**这张图在评审会上回答：上传请求在哪个时刻同步返回、解析任务何时异步投递、并发如何避免重复，以及 PGVector 与 MySQL 不一致时到底由谁补偿。**

```mermaid
sequenceDiagram
    autonumber
    actor U as 学生
    participant FE as Vue 前端
    participant GW as Java Gateway
    participant DS as document-service
    participant R as Redis
    participant DB as MySQL
    participant M as MinIO
    participant MQ as RocketMQ
    participant PY as Python 解析 Worker
    participant V as PGVector

    U->>FE: 选择文件并计算 MD5
    FE->>GW: POST /api/v1/documents/upload/init
    GW->>GW: JWT 校验 + 用户级限流
    GW->>DS: 转发 userId / traceId
    DS->>R: 查询 md5:{userId}:{md5} 秒传索引
    alt 同一用户已有 INDEXED 文档
        R-->>DS: 命中 documentId
        DS-->>FE: 200 instantUpload=true
    else 未命中
        DS->>R: Redisson 加锁 upload:lock:{userId}:{md5}
        DS->>DB: 唯一约束下查询/创建 document=UPLOADED
        DS->>R: 释放初始化锁
        DS-->>FE: 201 uploadId + 预签名分片 URL
        FE->>M: PUT 分片到 MinIO（浏览器不持有密钥）
        FE->>GW: POST /api/v1/documents/{uploadId}/complete
        GW->>DS: 完成上传（按 uploadId 幂等）
        DS->>M: 校验分片/合并对象/核对大小与 MD5
        DS->>DB: 事务写 status=PARSING + outbox(parse-requested)
        DS-->>FE: 202 documentId, status=PARSING
        Note over DS,FE: 同步返回点：只代表解析任务已接收，不代表解析完成
        DS->>MQ: outbox relay 投递 parse-requested
        Note over DS,MQ: 异步投递点：数据库事务提交后才发送
        MQ->>PY: 消费解析任务
        PY->>M: 下载原文件
        PY->>PY: 解析、切分、PII 过滤、Embedding
        PY->>V: 幂等 upsert vectors（documentId + indexVersion + chunkIndex）
        Note over V,DB: 不一致窗口：PGVector 已写成功，MySQL 仍为 PARSING
        PY->>MQ: 发布 parse-completed(documentId, indexVersion, chunks)
        MQ->>DS: 消费 parse-completed
        alt 消费成功
            DS->>DB: 单事务写 chunk 元数据 + status=INDEXED
            U->>FE: 刷新/轮询状态
            FE->>GW: GET /api/v1/documents/{documentId}/status
            GW->>DS: 查询
            DS-->>FE: 200 status=INDEXED
        else 事件丢失或状态更新失败
            DS->>DB: 幂等重试；仍失败则保持 PARSING
            Note over DS,DB: 补偿位置：对账任务扫描超时 PARSING 并重投解析，Python 幂等跳过已有向量
        end
    end
```

流程解释：

- **同步返回点**是 `202 documentId, status=PARSING`：它只承诺任务已被事务性接收，不承诺解析成功。真正的解析在 outbox 消息发出后才开始。
- **异步投递点**是 outbox relay 向 RocketMQ 发送 `parse-requested`。先写数据库再发消息，避免“任务已投递但状态没落库”的方向性错误。
- **不一致窗口**发生在 Python 已成功 upsert PGVector、`parse-completed` 尚未被 `document-service` 成功消费的这段时间。文档会暂时停留在 `PARSING`，用户不会看到半成品答案。
- **补偿位置**在 `document-service` 的重试与对账：消费者先做幂等重试；超过阈值的 `PARSING` 文档由定时对账任务重投解析。Python 使用确定性向量 ID 和 upsert，重复执行不会产生重复分块。
- `complete` 和 `parse-completed` 都必须幂等，重复事件只允许产生一次状态推进和一次分块元数据写入。

## 3. 序列图二：用户提问到返回回答

**这张图在评审会上回答：一次问答如何在限流、缓存、预算、RAG、审计之间形成闭环，缓存命中与未命中分别经过哪些节点，以及失败信号最终回到哪里。**

```mermaid
sequenceDiagram
    autonumber
    actor U as 学生
    participant FE as Vue 前端
    participant GW as Spring Cloud Gateway
    participant AG as agent-gateway
    participant R as Redis
    participant DB as MySQL
    participant PY as FastAPI
    participant LG as LangGraph
    participant V as PGVector
    participant LLM as LLM API
    participant MQ as RocketMQ
    participant AUD as audit-service

    U->>FE: 输入问题
    FE->>GW: POST /api/v1/conversations/{conversationId}/ask
    GW->>GW: JWT 校验
    GW->>R: 原子限流（userId/IP/会话）
    alt 超过限流
        GW-->>FE: 429 + traceId，不进入 AI
    else 通过
        GW->>AG: 转发鉴权上下文与 traceId
        AG->>DB: 校验会话归属/文档权限，读取最近 6 条消息
        AG->>R: GET qa:{userId}:{docScopeHash}:{historyHash}:{questionHash}:{promptVersion}
        alt 缓存命中
            R-->>AG: answer / citations / confidence
            AG->>DB: 追加消息 + agent_execution(cacheHit=true, tokenUsed=0)
            AG->>MQ: outbox relay 审计事件
            MQ->>AUD: 追加写 audit_event
            AG-->>FE: 200 + 缓存答案 + traceId
        else 缓存未命中
            AG->>R: 初始化 agent:budget:{traceId}（token/工具/轮次）
            AG->>PY: POST /agent/ask
            Note over AG,PY: 治理字段 + piiMaskedQuestion + tokenBudget=8000 + maxToolCalls=3 + maxRounds=3 + deadlineMs=15000
            PY->>PY: Pydantic 校验 + 第二层 PII 检测
            PY->>LG: 启动受预算约束的 LangGraph
            LG->>V: 按 Java 授权的 documentIds 过滤并检索 topK
            V-->>LG: chunks + scores
            LG->>R: 原子累加 token/工具计数并校验剩余预算
            LG->>LLM: 调用模型（仅发送已脱敏 Prompt）
            LLM-->>LG: answer + usage
            LG->>LG: 校验引用、置信度、轮次与预算
            LG-->>PY: answer / confidence / budgetStatus / usage / auditEvents
            PY-->>AG: AgentResponse 治理信号
            AG->>DB: 本地事务：追加消息 + agent_execution + audit outbox
            alt budgetStatus=OK 且 confidence>=0.55
                AG->>R: SET qa 缓存，TTL=10 分钟
            end
            AG->>MQ: outbox relay 审计事件
            MQ->>AUD: 追加写 audit_event
            AG-->>FE: 200 + answer + citations + budgetStatus
        end
    end
```

流程解释：

- **限流在 Java Gateway 完成**，发生在任何缓存与 Python 调用之前。限流命中时只返回 429 和 `traceId`，不消耗 token，也不进入 LangGraph。
- **缓存命中路径**仍然写 `agent_execution` 和审计，只是 `tokenUsed=0`、`cacheHit=true`。缓存键包含 `userId`、文档范围、历史摘要、问题摘要和 Prompt 版本，禁止跨用户或跨文档版本复用答案。
- **缓存未命中路径**由 `agent-gateway` 组装全部治理字段；Python 只拿到已授权文档 ID、已脱敏问题与不可突破的上限，不直接访问用户表。
- **Python 在每次 LLM/工具调用前检查预算**，Redis 计数器提供跨进程硬停止能力；Java 在响应返回后核对 `tokenUsed/toolCallsMade/roundsUsed` 并写入权威执行记录。
- **审计回写**以 Java 本地事务中的 outbox 为主：消息入队与执行记录、消息记录同事务提交，再由 MQ 异步送 `audit-service`。Python 返回的 `auditEvents` 是证据输入，不是最终审计存储。
- 只有 `budgetStatus=OK` 且置信度达到阈值时才写缓存；`LOW_CONFIDENCE` 与 `BUDGET_EXHAUSTED` 都不能被缓存为一次成功答案。

## 4. 状态图：Document 生命周期

**这张图在评审会上回答：文档在什么条件下可以被问答、失败后何时重试、什么情况下彻底停止自动处理，以及人工重试是否被允许。**

```mermaid
stateDiagram-v2
    [*] --> UPLOADED : 创建上传会话
    UPLOADED --> PARSING : 上传完成、校验通过并投递解析任务
    PARSING --> INDEXED : PGVector 与 MySQL 分块/状态一致提交
    PARSING --> FAILED : 解析、向量化或完成事件消费失败
    FAILED --> PARSING : retryable=true 且 retryCount < MAX_RETRY
    FAILED --> DEAD : retryCount >= MAX_RETRY
    INDEXED --> [*]
    DEAD --> [*]

    note right of PARSING
        PARSING 是异步状态。
        超时对账会重投解析，
        Python 写入幂等。
    end note
```

状态解释与不变量：

- 只有 `document-service` 可以推进 `Document` 状态，Python 只能通过 `parse-completed/failed` 事件提供事实，不能直接改 MySQL 的 `document` 行。
- `UPLOADED` 只允许进入 `PARSING`；非法跳转（例如直接调用内部接口把文档改成 `INDEXED`）必须拒绝并记录审计。
- `INDEXED` 表示“状态、分块元数据、向量写入”已经达到可检索条件，不表示 PGVector 与 MySQL 永远一致；一致性靠版本号、幂等写入和对账持续维护。
- 瞬时错误按指数退避重试，`retry_count` 达到上限进入 `DEAD`。加密 PDF 等永久错误标记为不可重试，停留在 `FAILED` 并指导用户重新上传，不占用自动重试次数。
- 人工重试只允许用于 `FAILED` 且错误可重试、或 `DEAD` 且管理员已确认基础设施恢复的场景；重试会递增 `retry_count` 并要求幂等处理，不能重复生成分块。

---

拦截的是什么问题：拦截“实体关系只画在文档里、状态机和补偿逻辑却各自实现”的不一致，尤其是上传并发、PGVector 写成功但状态未更新、以及问答审计只存在 Python 侧的链路盲区。
