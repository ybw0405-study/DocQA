# API 文档与契约维护规范

## 1. 基本原则

1. **契约先行。** 先修改契约，再实现代码。契约是前后端、Java 与 Python 之间的唯一事实来源，不以 Controller 注解或聊天记录为准。
2. **契约入库。** 契约文件必须进入 Git，与实现同分支评审；禁止只在 Apifox、Postman 或口头约定中维护。
3. **实现向契约靠拢。** 契约不是"代码写完后自动导出的说明书"。允许用 Springdoc 生成实现快照，但生成结果只能用于校验，不能反向覆盖已评审的契约。
4. **一个字段只有一种含义。** 字段命名、枚举值、空值语义、错误码、幂等键位置一经发布，变更必须按兼容性规则处理。

## 2. 文件位置与责任人

| 契约 | 路径 | 维护责任人（角色） | 审批人 |
|---|---|---|---|
| Java 对外 OpenAPI | `docs/02-design/openapi-java.yaml` | 各接口所属模块 Owner | 模块 Owner + 架构 Owner |
| 统一响应、错误码、ProblemDetail | `docs/02-design/openapi-java.yaml` 的 `components` 与错误码登记表 | `common` Owner | 架构 Owner |
| 跨语言治理协议 | `docs/02-design/cross-language-protocol.md` | `agent-gateway` Java Owner 与 `ai-python` Owner 共同维护 | 双方 Owner 必须同时批准 |
| 数据库与存储设计 | `docs/02-design/storage.md` | 架构 Owner + 对应模块 Owner | 架构 Owner |
| UML 与需求变更 | `docs/01-requirements/` | 需求提出人 + 架构 Owner | 委托方确认 |

接口 Owner 对照：

- 文档上传、完成、状态查询：`document-service` Owner。
- 问答与会话：`agent-gateway` Owner。
- 提醒：`reminder-service` Owner。
- 登录/JWT：`user-service` Owner。
- `/agent/*` 的 Java 请求与响应适配：`agent-gateway` Owner；Python 侧实现：`ai-python` Owner。

责任人不是"最后改文档的人"，而是**必须参加契约 MR 评审并对兼容性负责的人**。

## 3. 契约编写规范

### 3.1 OpenAPI 版本与媒体类型

- 使用 OpenAPI `3.0.3`，YAML 格式，UTF-8 编码。
- 所有路径以 `/api/v1` 开头；不把版本号放进 Header 代替路径版本。
- JSON 请求/响应为 `application/json`；错误响应为 `application/problem+json`。
- 每个操作必须有唯一 `operationId`、`tags`、`summary`、成功响应、至少 3 个错误响应和请求示例。

### 3.2 统一响应与错误

- 成功响应使用统一信封：

```json
{
  "code": "OK",
  "msg": "success",
  "data": {},
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

- 错误响应使用 RFC 9457 Problem Details，不嵌套在成功信封的 `data` 中：

```json
{
  "type": "https://docqa.example/problems/document-encrypted",
  "title": "Document is encrypted",
  "status": 422,
  "detail": "无法解析加密 PDF，请先解除密码保护",
  "instance": "/api/v1/documents/123/status",
  "code": "DOC-VALIDATION-003",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

约定：成功信封与错误详情都必须携带 `traceId`；错误码在 `code` 扩展字段中；HTTP 状态码必须与 `ProblemDetail.status` 一致，不得出现 HTTP 200 包裹业务失败。

### 3.3 错误码格式

格式：`[模块码]-[类型]-[序号]`，例如 `DOC-VALIDATION-003`。

- 模块码：`COM`、`USR`、`DOC`、`AGT`、`REM`、`AUD`。
- 类型：`VALIDATION`、`AUTH`、`FORBIDDEN`、`NOT_FOUND`、`CONFLICT`、`RATE_LIMIT`、`BUDGET`、`UPSTREAM`、`INTERNAL`。
- 序号为三位数字，模块内唯一，不复用已删除的编号。
- 错误码一旦发布，语义不可改变；废弃时保留映射并标注替代码。

### 3.4 幂等与并发

- 写接口在契约中必须声明幂等键位置、作用域和判重方式。
- 上传初始化/完成使用 `Idempotency-Key` Header，服务端按 `userId + operation + key` 记录 24 小时结果。
- 提醒创建使用 `Idempotency-Key`，避免前端重试造成重复提醒。
- 问答接口按 `conversationId + question` 做短期缓存，但不把缓存命中等同于幂等写。
- 契约必须定义重复请求返回同一结果还是冲突错误，不能留空让实现自行发挥。

### 3.5 Schema 约束

- 字符串必须给出 `minLength`/`maxLength`/`pattern`；枚举使用封闭 `enum`，未知值不得静默降级。
- 时间统一为 ISO 8601 UTC，字段名使用 `...At`；金额、token 数等使用整数并提供单位说明。
- `nullable` 与"字段缺省"必须分别定义，禁止用空字符串表示不存在。
- 不在响应中暴露 JPA 实体、数据库自增主键内部细节或堆栈。

### 3.6 跨语言契约

- 跨语言唯一同步调用边界是 `agent-gateway -> /agent/*`，字段定义以 `docs/02-design/cross-language-protocol.md` 为准。
- 治理字段缺失或非法时的错误码、HTTP 状态和 Java 侧动作必须逐字段写明。
- Python 侧 Pydantic 模型和 Java 侧 DTO 都必须有契约测试；新增字段默认可选，删除或改义视为破坏性变更。
- 变更流程：先改协议文档与机器可校验 schema -> 双方评审 -> Java/Python 同步实现 -> 集成测试 -> 再发布。任一方未实现时，不能靠"运行时试试看"上线。

## 4. CI 中的契约一致性校验

契约检查分四层，任意一层失败都阻断合并：

1. **语法与规范检查（PR 阶段）**
   - 使用 Spectral 或 Redocly CLI 对 `openapi-java.yaml` 做 lint。
   - 校验所有 operation 有唯一 `operationId`、错误响应、示例、错误码格式。
   - 校验通过才允许后续步骤运行。

2. **实现与契约的接口测试（PR 阶段）**
   - 使用 `SwaggerRequestValidator` 在 MockMvc/WebTestClient 集成测试中，按契约验证请求与响应。
   - 测试覆盖每个接口的成功路径、至少 3 个错误码和幂等重复请求。
   - 任何未在契约中声明的字段、缺失必填字段或状态码不一致都失败。

3. **生成快照差异检查（PR 阶段）**
   - 用 Springdoc 在测试环境生成 `target/openapi-generated.yaml`。
   - 与入库的 `docs/02-design/openapi-java.yaml` 做语义 diff，比较 path、method、参数必填性、响应状态码、schema 字段。
   - 描述文本、排序等非语义差异通过规范化配置消除，不允许使用宽泛 allowlist 掩盖字段差异。
   - 该步骤发现"代码改了但契约没改"或"契约改了但实现没跟上"。

4. **破坏性变更检查（PR 阶段）**
   - 用 `openapi-diff` 对比目标分支与当前分支，识别删除字段、修改类型、收紧校验、改变枚举、删除错误码等破坏性变化。
   - 破坏性变更必须升级 API 版本（如 `/api/v2`）或提供明确的兼容期；不得静默合并。

补充：跨语言协议在 CI 中独立执行 JSON Schema/契约测试。`ai-python` 的请求模型与响应模型必须能反序列化 2C 文档中的示例和边界样例；Java 侧 Agent 客户端测试使用同一组 fixture。两端使用同一份 canonical fixture，避免各写一套"看起来差不多"的测试。

## 5. 跨语言契约不一致在哪一步发现

按优先级从早到晚：

1. **本地提交前：** 修改 2C 文档后运行 schema lint 与双方模型单元测试，最早发现字段改名、类型不一致。
2. **PR CI（主要防线）：** Java DTO 契约测试、Python Pydantic 契约测试、共享 fixture 校验、OpenAPI diff。此阶段应拦下 95% 以上的不一致。
3. **预发集成测试：** 真实启动 Java 与 Python，验证超时、错误码映射、审计事件、trace 传播；发现 mock 无法暴露的协议偏差。
4. **运行时：** Python 接收到非法治理字段时返回 `AGT-UPSTREAM-*` 协议错误，Java 记录并降级。这是最后兜底，不作为主要发现手段。

如果只能在运行时发现不一致，说明 CI 的契约测试没有覆盖真实字段或 fixture 已过期，按缺陷处理，不把运行时告警当作正常流程。

## 6. 版本与废弃流程

- 非破坏性新增：保留旧字段，新增字段可选；OpenAPI `info.version` 递增次版本。
- 破坏性变更：新增 `/api/v2` 或跨语言协议主版本，旧版本至少保留一个发布周期。
- 废弃标记：在契约中写 `deprecated: true`，给出替代字段与截止日期，并在响应中返回弃用提示 Header。
- 契约变更必须与实现同 MR；若确实需要先发布契约供双方并行开发，必须在 MR 中标记 `contract-only`，并在实现 MR 合并前禁止部署到共享环境。

---

拦截的是什么问题：拦截"代码和文档各说各话、跨语言字段靠猜、上线后才发现契约不一致"的集成事故，把接口不一致从生产故障前移到 PR 阶段。
