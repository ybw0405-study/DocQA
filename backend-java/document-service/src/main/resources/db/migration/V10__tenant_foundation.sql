-- 租户地基（SaaS 内核 P0）。
-- 事实源：docs/02-design/storage.md §2.18，代码侧由 MigrationV10ContractTest 逐字对账。
--
-- 设计要点：
-- 1. 默认租户承载全部存量数据；id 固定为全 1 UUID，便于回滚与人工核对。
-- 2. 所有业务表新增 tenant_id，用 DEFAULT 默认值加列，MySQL 8 对存量行为 instant DDL。
-- 3. 唯一键改造为含 tenant_id 的复合键：username / md5 / 标签名 / 幂等键 / 配额主键
--    全部从「全局唯一」变为「租户内唯一」，这是多租户最直接的冲突点。
-- 4. 直接引用 app_user 的表把外键升级为复合外键 (tenant_id, user_id)，
--    数据库层保证 tenant_id 不能被写错；追加写表（audit_event / agent_execution）
--    保持无外键的既有约定，靠租户谓词与审计约束保证。
-- 5. app_user.id 仍为主键，但另加 (tenant_id, id) 唯一键供复合外键引用。
--
-- 本迁移包含 DROP INDEX / DROP FOREIGN KEY：只删「约束」不删「数据」，
-- 且删除的旧约束均在本文件内以租户化版本重建。

CREATE TABLE tenant (
  id             CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  slug           VARCHAR(64)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name           VARCHAR(120) NOT NULL,
  tier           VARCHAR(24)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  isolation      VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status         VARCHAR(16)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  owner_user_id  CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_slug (slug),
  KEY idx_tenant_status_created (status, created_at, id),
  CONSTRAINT chk_tenant_tier CHECK (tier IN ('FREE','STANDARD','PRO','ENTERPRISE')),
  CONSTRAINT chk_tenant_isolation CHECK (isolation IN ('SHARED','SCHEMA','DEDICATED')),
  CONSTRAINT chk_tenant_status CHECK (status IN ('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='租户控制面；存量数据归属默认租户，id 固定全 1 UUID';

INSERT INTO tenant (id, slug, name, tier, isolation, status, owner_user_id)
VALUES ('11111111-1111-1111-1111-111111111111', 'default', 'Default Workspace',
        'ENTERPRISE', 'SHARED', 'ACTIVE', NULL);

ALTER TABLE app_user
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_app_user_username,
  ADD UNIQUE KEY uk_app_user_tenant_username (tenant_id, username),
  ADD UNIQUE KEY uk_app_user_tenant_id (tenant_id, id),
  ADD KEY idx_app_user_tenant_created (tenant_id, created_at, id);

ALTER TABLE app_user_role
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_app_user_role_user,
  ADD CONSTRAINT fk_app_user_role_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE refresh_token
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_refresh_token_user,
  ADD CONSTRAINT fk_refresh_token_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ai_quota
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (tenant_id, user_id),
  DROP FOREIGN KEY fk_ai_quota_user,
  ADD CONSTRAINT fk_ai_quota_user FOREIGN KEY (tenant_id, user_id)
    REFERENCES app_user (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE document
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_document_user_md5,
  ADD UNIQUE KEY uk_document_tenant_user_md5 (tenant_id, user_id, md5),
  ADD UNIQUE KEY uk_document_tenant_id (tenant_id, id)
    COMMENT '供 chunk / document_tag_link 的复合外键引用',
  ADD KEY idx_document_tenant_user (tenant_id, user_id, created_at, id);

ALTER TABLE chunk
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_chunk_document,
  ADD CONSTRAINT fk_chunk_document FOREIGN KEY (tenant_id, document_id)
    REFERENCES document (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE document_tag
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_document_tag_user_normalized,
  ADD UNIQUE KEY uk_document_tag_tenant_user_normalized
    (tenant_id, user_id, normalized_name),
  ADD UNIQUE KEY uk_document_tag_tenant_id (tenant_id, id)
    COMMENT '供 document_tag_link 的复合外键引用';

ALTER TABLE document_tag_link
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_document_tag_link_document,
  DROP FOREIGN KEY fk_document_tag_link_tag,
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (tenant_id, document_id, tag_id),
  ADD KEY idx_document_tag_link_tenant_tag (tenant_id, tag_id, document_id)
    COMMENT '按标签过滤文档列表（搜索的 tag 参数）',
  ADD CONSTRAINT fk_document_tag_link_document FOREIGN KEY (tenant_id, document_id)
    REFERENCES document (tenant_id, id) ON DELETE CASCADE,
  ADD CONSTRAINT fk_document_tag_link_tag FOREIGN KEY (tenant_id, tag_id)
    REFERENCES document_tag (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE conversation
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD UNIQUE KEY uk_conversation_tenant_id (tenant_id, id)
    COMMENT '供 message 的复合外键引用',
  ADD KEY idx_conversation_tenant_user (tenant_id, user_id, last_message_at, id);

ALTER TABLE message
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP FOREIGN KEY fk_message_conversation,
  ADD CONSTRAINT fk_message_conversation FOREIGN KEY (tenant_id, conversation_id)
    REFERENCES conversation (tenant_id, id) ON DELETE CASCADE,
  DROP INDEX uk_message_client_request,
  ADD UNIQUE KEY uk_message_tenant_client_request
    (tenant_id, conversation_id, client_request_id);

ALTER TABLE agent_execution
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD KEY idx_agent_execution_tenant_user (tenant_id, user_id, started_at, id);

ALTER TABLE audit_event
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  ADD KEY idx_audit_tenant_user (tenant_id, user_id, occurred_at, id);

ALTER TABLE reminder
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_reminder_user_idempotency,
  DROP INDEX uk_reminder_user_source,
  ADD UNIQUE KEY uk_reminder_tenant_user_idempotency
    (tenant_id, user_id, idempotency_key),
  ADD UNIQUE KEY uk_reminder_tenant_user_source
    (tenant_id, user_id, source_type, source_uid),
  ADD KEY idx_reminder_tenant_user_next (tenant_id, user_id, next_trigger_at, id);

ALTER TABLE reminder_trigger
  ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    DEFAULT '11111111-1111-1111-1111-111111111111' COMMENT '所属租户',
  DROP INDEX uk_reminder_trigger_planned,
  ADD UNIQUE KEY uk_reminder_trigger_tenant_planned
    (tenant_id, reminder_id, planned_trigger_at);
