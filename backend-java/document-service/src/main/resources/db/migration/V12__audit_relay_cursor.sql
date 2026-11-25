-- 审计 outbox relay 游标的持久化。
--
-- 原设计把游标 audit:relay:cursor 存在 Redis，而本地 Redis 配置为
-- maxmemory 200mb + allkeys-lru：游标是无 TTL 的冷 key，内存紧张时会被驱逐。
-- 驱逐后 relay 会从表头重扫，重复投递全部历史审计事件。
--
-- 游标属于「不可丢失的进度」，不是缓存，应存 DB。表只保留一行（id=1），
-- 用 upsert 覆盖；不设外键，避免对 agent_execution 的写入产生约束开销。

CREATE TABLE IF NOT EXISTS audit_relay_cursor (
  id TINYINT UNSIGNED NOT NULL PRIMARY KEY COMMENT '固定为 1，单行游标',
  recorded_at DATETIME(3) NOT NULL COMMENT '已投递到的 recorded_at',
  execution_id CHAR(36) NOT NULL COMMENT '与 recorded_at 共同构成 keyset 位置',
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
