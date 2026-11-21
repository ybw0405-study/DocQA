-- 数据面纵深防御：给两张向量空间补 tenant_id 列与租户前缀索引。
-- 事实源：审查 LOG-1。此前隔离完全依赖「document UUID 不可猜 + Java 网关校验 documentScopes」，
-- PG 侧没有任何租户列，端口一暴露即可凭已知 (documentId, indexVersion) 跨租户读到 snippet。
--
-- 回填策略（刻意 fail-closed）：存量行加列后一律落到默认值 ''。真实请求的 tenant_id 是
-- 非空 UUID 字符串，WHERE tenant_id = %s 因此不会命中任何未回填的旧行——旧文档在回填前
-- 不可检索，而不是「隔离失效」。运维需按 document_id 从控制面回查所属租户后回填本列，
-- 或对该文档重投 parse-requested 触发重建索引；重投写入的每行都带真实 tenant_id。
-- 两条空间（offline 与 text-embedding-v4）必须同时回填，否则切换向量空间时隔离会静默回退。
--
-- 本文件全部语句为 IF NOT EXISTS，重复执行不改变结果；索引名在 schema 内全局唯一，
-- 与 001 / 002 / 003 的八个索引互不冲突（由 tests/test_migrations.py 跨文件对账）。
-- 列用 TEXT 而非 UUID：控制面 tenantId 以字符串形态出现，TEXT 兼容旧值空串与未来非 UUID 形态，
-- 且查询侧统一以 str(tenant_id) 绑定，避免隐式类型转换让索引失效。

ALTER TABLE chunk_embedding_1024
  ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT '';

ALTER TABLE chunk_embedding_1024_text_embedding_v4
  ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_tenant
  ON chunk_embedding_1024 (tenant_id, document_id, index_version);

CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v4_tenant
  ON chunk_embedding_1024_text_embedding_v4 (tenant_id, document_id, index_version);

COMMENT ON COLUMN chunk_embedding_1024.tenant_id IS
  'Owning tenant; every read binds this column in addition to the authorized (document_id, index_version) scope. Empty value marks a pre-migration row awaiting backfill.';
COMMENT ON COLUMN chunk_embedding_1024_text_embedding_v4.tenant_id IS
  'Owning tenant; same defense-in-depth predicate as the offline space. Empty value marks a pre-migration row awaiting backfill.';
