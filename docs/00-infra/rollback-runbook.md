# 发布回滚 Runbook

适用范围：DocQA 产品版本回退、跨版本 Flyway 迁移、pgvector 索引版本切换。
核心原则：**应用可回退，数据结构不自动回退**；回退前先判断「新代码写过的列 / 行」旧代码是否读得懂。

## 0. 回滚前 5 分钟检查

- 记录现场：当前 tag 与镜像 digest、Flyway `schema_history` 最大版本、`document.index_version` 分布、
  `ai-python/migrations` 最大版本、Redis 里的配额键 / audit relay 游标 / 幂等 DONE 标记、MQ 消费位点。
- 先判断能否用开关止血，不要动版本：AI 全局 / 租户级熔断、按用户配额、`DOCQA_AI_INTERNAL_TOKEN` 配置错误、
  PG 连接池耗尽、MinIO 不可达。能用开关或限流恢复就不回退。
- 摘流量并停止新的上传与解析任务，让在途 MQ 消息消费完（观察 DLQ 无新增）。

## 1. 应用版本回退

1. 网关摘流量；停机顺序：网关 → user / document / reminder → ai-python → parse-worker。
2. 部署上一个 tag 的镜像（**按 digest，不用 latest**）。环境变量沿用当前值，但必须逐项比对新版本引入的变量。
3. 跑 `flyway validate`：若报「已应用但本地缺失的迁移」，说明库结构比目标版本新，按第 2 节判断兼容性。
   **禁止 `flyway clean`、禁止删改 `schema_history` 行**。
4. 反向启动：中间件 → ai-python / worker → Java 服务 → 网关。
5. 冒烟：登录与刷新、文档列表、一次同步问答、一次 SSE 流式、`/health`、`/metrics`（仅内网）、审计写入。

## 2. 数据库 / Flyway 兼容

- 迁移只向前。需要撤销结构时写**新的补偿迁移**，不修改已应用的脚本（见 `git-workflow.md` §4）。
- 旧代码能否读新结构：新增可空列 / 新表 / 索引通常兼容；新增 `NOT NULL` 且无默认值的列会让旧代码 INSERT 失败，
  此时必须补默认值或保留双写，不能只回退应用。
- 唯一键 / 复合键改造（V10 起全部含 `tenant_id`）：旧代码按旧键查询可能失效，回退前确认查询谓词与索引顺序。
- 数据订正脚本必须幂等可重放，并在预发用 `MigrationOnRealMysqlTest` 的等价授权流程演练。
- 审计表 append-only：任何情况下不删除、不更新 `audit_event` 行；回退过程产生的事件照常留痕。

## 3. pgvector / 向量索引版本回退

- `document.index_version` 是向量集合的唯一版本标识，`vector-change` 只在该版本上增删，
  **绝不退化成删整篇文档**（`vector_worker.py` 在作用域缺失时回投 FAILED 而不碰 PG）。
- 回退 embedding 维度或分块策略时不删现有 chunk 表：给新文档启用新的 `index_version`，
  旧版本文档保持原 version 可检索；确认无引用后再按 `(tenant_id, document_id, index_version)` 归档。
- 若回退涉及本轮新增的 `tenant_id` 列：旧代码不写该列时，列必须有默认值，且**检索侧的租户谓词仍要生效**；
  禁止通过去掉 `WHERE tenant_id = %s` 来「兼容」旧代码。
- 向量写入是单事务 `DELETE + INSERT + COUNT`，回滚只需重发一次 `vector-change`；重放前确认幂等键未过期。

## 4. 配置与密钥回退

- `DOCQA_AI_INTERNAL_TOKEN` 必须**两端同时改**：只改一端会让全部问答 401。生产留空会 fail-fast。
- JWT 密钥轮换 / 回退会让全部在途 access token 失效（上限 15 分钟），refresh 按家族吊销；
  操作前确认用户可接受一次全量重新登录。
- Redis 中的配额计数、audit relay 游标、幂等 DONE 标记：清库会导致当日配额归零、历史事件重投风暴，
  仅在确认消费端幂等完好时允许，并记录清理时间点。
- 演示种子账号、审计 append-only 自检模式均为默认关闭 / 默认 fail-fast，回退时不要把配置带回不安全默认值。

## 5. 回退后验证清单

- [ ] `scripts/verify-all.sh --offline` 在目标 tag 上通过
- [ ] 登录 / 刷新轮换 / 改密后旧 token 失效
- [ ] 上传 → 解析 → INDEXED → 问答带引用（抽 1 篇真实文档）
- [ ] 跨租户访问返回 404（双租户环境）
- [ ] 审计事件只追加、无重复投递风暴
- [ ] MQ 无堆积、DLQ 无新增
- [ ] 指标回到基线：问答 P99、PG 连接数、Redis 命中率

## 6. 复盘必填项

记录触发条件、实际止血手段、回退版本与 digest、数据是否发生不可逆变化、补偿脚本编号，
以及下一次发布必须满足的前向兼容约束。
