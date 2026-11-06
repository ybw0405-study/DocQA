CREATE TABLE conversation (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title               VARCHAR(200) NOT NULL DEFAULT '新会话',
  document_scope_ids  JSON NULL,
  last_message_at     DATETIME(3) NULL,
  created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conversation_user_last (user_id, last_message_at)
    COMMENT '按用户展示最近会话与会话回访',
  CONSTRAINT chk_conversation_scope CHECK (
    document_scope_ids IS NULL OR JSON_TYPE(document_scope_ids) = 'ARRAY'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='会话控制面元数据；文档范围是逻辑关联，不是授权凭证';

CREATE TABLE message (
  id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content           MEDIUMTEXT NOT NULL,
  token_count       INT UNSIGNED NOT NULL DEFAULT 0,
  client_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  trace_id          CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  metadata          JSON NULL,
  created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_client_request (conversation_id, client_request_id)
    COMMENT '问答幂等：同一会话同一客户端请求只产生一条用户消息',
  KEY idx_message_conversation_created (conversation_id, created_at, id)
    COMMENT '按会话读取最近 6 条上下文与消息回放',
  KEY idx_message_trace (trace_id)
    COMMENT '按 traceId 关联一次问答的请求与回答',
  CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
    REFERENCES conversation (id) ON DELETE CASCADE,
  CONSTRAINT chk_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='会话消息；用户原文属于业务数据，不等于允许进入日志';

CREATE TABLE agent_execution (
  id                  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  trace_id            CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  conversation_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_message_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  response_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  status              VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  budget_status       VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
  token_budget        INT UNSIGNED NOT NULL,
  token_used          INT UNSIGNED NOT NULL DEFAULT 0,
  tool_calls_made     INT UNSIGNED NOT NULL DEFAULT 0,
  rounds_used         INT UNSIGNED NOT NULL DEFAULT 0,
  cache_hit           BOOLEAN NOT NULL DEFAULT FALSE,
  latency_ms          BIGINT UNSIGNED NOT NULL,
  error_code          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  audit_outbox        JSON NOT NULL,
  started_at          DATETIME(3) NOT NULL,
  finished_at         DATETIME(3) NOT NULL,
  recorded_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_execution_trace (trace_id)
    COMMENT '一次请求只允许一个终态执行快照，按 trace 查询与幂等写入',
  KEY idx_agent_execution_user_started (user_id, started_at)
    COMMENT '按用户统计问答量、预算消耗与失败率',
  KEY idx_agent_execution_status_started (status, started_at)
    COMMENT '状态维度的告警、对账与审计查询',
  KEY idx_agent_execution_outbox (recorded_at, id)
    COMMENT '审计 outbox relay 扫描待投递执行快照',
  CONSTRAINT chk_agent_execution_status CHECK (
    status IN ('RUNNING', 'SUCCESS', 'BUDGET_EXHAUSTED', 'FAILED')
  ),
  CONSTRAINT chk_agent_execution_budget CHECK (
    budget_status IS NULL OR budget_status IN ('OK', 'LOW_CONFIDENCE', 'BUDGET_EXHAUSTED')
  ),
  CONSTRAINT chk_agent_execution_audit_outbox CHECK (
    JSON_TYPE(audit_outbox) = 'ARRAY'
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Agent 执行终态快照；追加写，禁止更新和删除；audit_outbox 兼作审计发件箱';
