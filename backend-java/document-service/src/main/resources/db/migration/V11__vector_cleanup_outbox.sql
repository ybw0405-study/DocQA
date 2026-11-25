-- DQ-1：待确认的向量版本删除（持久化 outbox）。
--
-- 背景：重建索引成功后，旧版本的向量要靠一条 vector.change.requested(DELETE_VERSION)
-- 才能从 PG 清掉。以前这条消息一旦「发布失败 / 消费端报 FAILED / 进程重启 / 消息丢失」，
-- 就只剩一行日志，孤儿向量永久残留；DocumentReconciliationJob 的删除扫描只覆盖软删文档
-- 的 DELETE_DOCUMENT，看不见 DELETE_VERSION。
--
-- 修法：把 (tenant, document, version, operation) 作为待确认任务持久化。
-- 1. 发布前先入队（幂等，唯一键去重）；发送失败也留着，由对账任务重投。
-- 2. DocumentReconciliationJob.republishPendingVectorCleanups() 有限次重投。
-- 3. 只有收到 vector-change-completed(completed=true) 才出队；FAILED 保留并继续重投。
-- attempts 上限 20 是「有限次重投」，超过后留行给运维排查，不静默丢弃。
--
-- 纯 DDL（0 条 INSERT/UPDATE/DELETE）：迁移账号 ss_migrator 只有 schema 级 DDL 权限，
-- 业务数据的写入全部由 app 账号在运行时完成。
CREATE TABLE vector_cleanup_task (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  index_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_vector_cleanup_scope (tenant_id, document_id, index_version, operation),
  KEY idx_vector_cleanup_pending (updated_at, attempts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='待确认的向量删除；只有收到成功确认才出队（DQ-1）';
