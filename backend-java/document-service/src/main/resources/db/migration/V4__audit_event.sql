-- 不可变审计事件；只允许 INSERT/SELECT，禁止 UPDATE/DELETE
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
