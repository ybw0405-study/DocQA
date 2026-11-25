-- 回填收口：去掉 004 给 tenant_id 加的 DEFAULT ''，让任何漏传租户的 INSERT 直接报错。
--
-- 前置条件（顺序不可颠倒）：
-- 1. 已部署「写侧强制 tenant_id」的代码（parse_worker / vector_worker / vector_writer）；
-- 2. 已执行 `python -m docqa_ai.maintenance.backfill_tenant_id --mapping ... --apply`
-- 且报表中 unresolvedDocuments 与 emptyTenantRows 均为 0；
-- 3. 两条向量空间都已完成回填。
-- 顺序错误会让新写入在 NOT NULL 无默认值下失败，请在低峰期按 runbook 操作并保留回滚窗口。
--
-- 本文件只做两件事：删默认值、保持 NOT NULL。它不会修改任何数据，可重复执行。

ALTER TABLE chunk_embedding_1024
  ALTER COLUMN tenant_id DROP DEFAULT;

ALTER TABLE chunk_embedding_1024_text_embedding_v4
  ALTER COLUMN tenant_id DROP DEFAULT;
