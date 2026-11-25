-- 提醒规则表；重复提醒只能一行，禁止按 occurrence 插多行
CREATE TABLE reminder (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NULL,
  rrule VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dtstart DATETIME(3) NOT NULL,
  timezone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  exdates JSON NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  source_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CUSTOM',
  source_uid VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_sequence INT UNSIGNED NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  next_trigger_at DATETIME(3) NULL,
  last_triggered_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='提醒规则；重复提醒只能一行，禁止按 occurrence 插多行';
