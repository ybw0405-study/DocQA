-- AI 治理运维面；事实源：docs/02-design/storage.md §2.17，代码侧由 MigrationV9ContractTest 逐字对账。
--
-- ai_quota 只存「按用户的日配额覆盖」，不是配额计量的权威账本：
-- 当日已用次数与 token 在 Redis 的 agent:quota:usage:{userId}:{yyyy-MM-dd}，这里只有上限。
-- 缺行表示该用户不设上限，因此没有默认行、也没有 0 值语义；两个上限列可空且互不牵连。
-- updated_by 不建外键：管理员身份以 audit_event 为准，app_user 也不做物理删除。
--
-- 两个索引只服务管理面的时间窗查询，不改既有列、不删既有索引：
-- agent_execution 既有索引都以 user_id / status / recorded_at 开头，纯时间窗聚合会全表扫描；
-- audit_event 既有索引都以 trace_id / user_id / event_type 开头，无法服务「只按时间窗」的 keyset 分页，
-- id 作为第二列是为了让 (occurred_at DESC, id DESC) 的游标顺序与索引顺序一致。

-- 按用户的 AI 日配额覆盖；缺行表示不设上限
CREATE TABLE ai_quota (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  daily_question_limit INT UNSIGNED NULL,
  daily_token_limit INT UNSIGNED NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                          ON UPDATE CURRENT_TIMESTAMP(3),
  updated_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
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
